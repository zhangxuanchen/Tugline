package dev.tugline.agent.core;

import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.TextBlockDeltaEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.harness.agent.HarnessAgent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import reactor.core.Disposable;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 统一单轮执行入口（移植自 MO SingleTurnExecutor，M1 简化版）：
 * 建实例 → 注入会话上下文 → 跑事件流 → 事件转发 + 抽取助手文本 → 落盘 → 回调。
 *
 * 设计要点（沿用 MO）：
 * - 每轮新建 Agent 实例，规避有状态不可并发复用陷阱
 * - 事件经 EventSink 交调用方做表现层转发（SSE）
 * - 看门狗：按 heartbeat 周期回调保活；timeout 为 null 时不强杀（靠 SSE 自身超时）
 */
@Component
public class SingleTurnExecutor {

    private static final Logger log = LoggerFactory.getLogger(SingleTurnExecutor.class);

    private final AgentFactory agentFactory;
    private final ConversationMemory conversationMemory;

    public SingleTurnExecutor(AgentFactory agentFactory, ConversationMemory conversationMemory) {
        this.agentFactory = agentFactory;
        this.conversationMemory = conversationMemory;
    }

    /** 单轮执行所需上下文（由调用方组装） */
    public record TurnSpec(
            String sessionId,
            String message,
            /** 用户个人大模型凭证；null = 使用全局配置 */
            AgentCredential credential,
            /** 长阻塞期间的周期心跳回调（保活 SSE）；可为 null */
            Runnable heartbeat) {

        /** 兼容旧签名（全局配置） */
        public TurnSpec(String sessionId, String message, Runnable heartbeat) {
            this(sessionId, message, null, heartbeat);
        }
    }

    /** 单轮执行结果：助手最终文本 */
    public record TurnResult(String assistantText) {
    }

    /** 单轮事件转发口：调用方决定每个 AgentEvent 如何转出（SSE） */
    public interface EventSink {
        void onEvent(AgentEvent ev);
    }

    /** 单轮生命周期回调：三类收尾互斥触发一次 */
    public abstract static class TurnListener {
        public void onDone(TurnResult result) {
        }

        public void onError(Throwable t) {
        }
    }

    /** 执行单轮 Agent：异步启动事件流订阅后立即返回。 */
    public void runTurn(TurnSpec spec, EventSink sink, TurnListener listener) {
        String memKey = conversationMemory.key(spec.sessionId());

        // 1) 组装上下文 + 建实例
        HarnessAgent agent;
        try {
            String memoryContext = conversationMemory.context(memKey);
            agent = agentFactory.create(spec.sessionId(), memoryContext, spec.credential());
        } catch (Exception e) {
            listener.onError(e);
            return;
        }

        // 2) 跑事件流：事件转表现层（sink），同时抽取助手文本用于落盘
        StringBuilder asst = new StringBuilder();
        AtomicBoolean finished = new AtomicBoolean(false);
        AtomicReference<Disposable> subRef = new AtomicReference<>();
        reactor.core.publisher.Flux<AgentEvent> stream;
        try {
            Msg userMsg = Msg.builder().textContent(spec.message()).build();
            stream = agent.streamEvents(userMsg);
        } catch (Exception e) {
            listener.onError(e);
            return;
        }
        subRef.set(stream.subscribe(
                ev -> {
                    sink.onEvent(ev);
                    if (ev instanceof TextBlockDeltaEvent t && t.getDelta() != null) {
                        asst.append(t.getDelta());
                    }
                },
                err -> {
                    if (finished.compareAndSet(false, true)) {
                        listener.onError(err);
                    }
                },
                () -> {
                    if (finished.compareAndSet(false, true)) {
                        persist(memKey, spec.message(), asst.toString());
                        listener.onDone(new TurnResult(asst.toString()));
                    }
                }));

        // 3) 心跳保活线程（沿用 MO：模型长思考期间 SSE 无字节会被掐断）
        if (spec.heartbeat() != null) {
            Thread heartbeat = new Thread(() -> {
                while (!finished.get()) {
                    try {
                        Thread.sleep(5000);
                    } catch (InterruptedException e) {
                        break;
                    }
                    if (finished.get()) {
                        break;
                    }
                    try {
                        spec.heartbeat().run();
                    } catch (Exception ignored) {
                    }
                }
            }, "tugline-agent-heartbeat");
            heartbeat.setDaemon(true);
            heartbeat.start();
        }
    }

    /** 落盘单轮会话记忆（失败不阻断主流程） */
    private void persist(String memKey, String message, String asst) {
        try {
            conversationMemory.append(memKey, message, asst);
        } catch (Exception e) {
            log.warn("记录会话记忆失败（不阻断）: {}", e.toString());
        }
    }
}
