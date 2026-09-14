package dev.tugline.agent.web;

import dev.tugline.agent.AgentProperties;
import dev.tugline.agent.core.AgentCredential;
import dev.tugline.agent.core.AgentKeyStore;
import dev.tugline.agent.core.ConversationMemory;
import dev.tugline.agent.core.SingleTurnExecutor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Agent 侧边栏接口：SSE 流式对话 + 会话管理。
 * 鉴权走全局 JWT（/api/agent/** 默认 authenticated）。
 */
@RestController
@RequestMapping("/api/agent")
public class AgentChatController {

    private static final Logger log = LoggerFactory.getLogger(AgentChatController.class);

    private final AgentProperties props;
    private final AgentKeyStore keyStore;
    private final ConversationMemory conversationMemory;
    private final SingleTurnExecutor turnExecutor;

    /** 每会话运行位：同一会话同时只允许一轮执行 */
    private final ConcurrentHashMap<String, AtomicBoolean> running = new ConcurrentHashMap<>();

    public AgentChatController(AgentProperties props,
                               AgentKeyStore keyStore,
                               ConversationMemory conversationMemory,
                               SingleTurnExecutor turnExecutor) {
        this.props = props;
        this.keyStore = keyStore;
        this.conversationMemory = conversationMemory;
        this.turnExecutor = turnExecutor;
    }

    public record ChatRequest(String sessionId, String message) {
    }

    /** Agent 能力状态（前端引导态判断）：keyConfigured = 个人 AK 或全局 Key 任一可用 */
    @GetMapping("/config")
    public Map<String, Object> config(Authentication auth) {
        boolean userAk = auth != null && keyStore.resolve(auth.getName()).isPresent();
        return Map.of(
                "enabled", props.isEnabled(),
                "keyConfigured", props.keyConfigured() || userAk,
                "userAkConfigured", userAk,
                "provider", props.getProvider());
    }

    /** SSE 流式对话：把 Agent 事件游标转发为 SSE event（token/done/error/ping） */
    @PostMapping(value = "/chat", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter chat(@RequestBody ChatRequest req, Authentication auth) {
        final SseEmitter emitter = new SseEmitter(10 * 60_000L); // 10 分钟无事件则超时关闭
        final String sessionId = conversationMemory.key(req.sessionId());
        final String message = req.message() == null ? "" : req.message().trim();

        // 凭证解析：当前用户个人 AK 优先，未配置回落全局默认
        final AgentCredential credential = auth == null ? null : keyStore.resolve(auth.getName()).orElse(null);
        if (!props.isEnabled() || (credential == null && !props.keyConfigured())) {
            complete(emitter, "error", "Agent 未启用：请在右上角「AI 密钥」维护个人 AK，或配置全局 LLM API Key");
            return emitter;
        }
        if (message.isEmpty()) {
            complete(emitter, "error", "消息不能为空");
            return emitter;
        }
        AtomicBoolean flag = running.computeIfAbsent(sessionId, k -> new AtomicBoolean());
        if (!flag.compareAndSet(false, true)) {
            complete(emitter, "error", "上一轮还在执行中，请稍候或点击「停止」");
            return emitter;
        }

        SingleTurnExecutor.TurnSpec spec = new SingleTurnExecutor.TurnSpec(
                sessionId,
                message,
                credential,
                // 心跳保活：模型长思考期间每 5s 发命名 ping 事件，防止连接被掐断
                () -> {
                    try {
                        emitter.send(SseEmitter.event().name("ping").data(""));
                    } catch (Exception ignored) {
                    }
                });
        try {
            turnExecutor.runTurn(spec, new SseEventSink(emitter), new SingleTurnExecutor.TurnListener() {
                @Override
                public void onDone(SingleTurnExecutor.TurnResult result) {
                    flag.set(false);
                    complete(emitter, "done", "ok");
                }

                @Override
                public void onError(Throwable t) {
                    flag.set(false);
                    log.warn("单轮流式异常: {}", t.toString());
                    complete(emitter, "error", "ERROR: " + rootMessage(t));
                }
            });
        } catch (Exception e) {
            flag.set(false);
            log.error("create agent failed: {}", e.toString());
            complete(emitter, "error", "ERROR: " + rootMessage(e));
        }
        return emitter;
    }

    // ---------- 会话管理 ----------

    @GetMapping("/sessions")
    public List<Map<String, Object>> sessions() {
        return conversationMemory.listSessions().stream()
                .map(s -> Map.<String, Object>of(
                        "id", s.id,
                        "title", s.title == null ? "" : s.title,
                        "updatedAt", s.updatedAt,
                        "count", s.messages == null ? 0 : s.messages.size()))
                .toList();
    }

    @PostMapping("/sessions")
    public Map<String, Object> createSession() {
        String id = "s_" + Long.toString(System.currentTimeMillis(), 36) + "_"
                + UUID.randomUUID().toString().substring(0, 6);
        ConversationMemory.Session s = conversationMemory.create(id);
        return Map.of("id", s.id, "title", s.title);
    }

    @DeleteMapping("/sessions/{id}")
    public Map<String, Object> deleteSession(@PathVariable String id) {
        boolean ok = conversationMemory.delete(id);
        return Map.of("ok", ok);
    }

    @GetMapping("/sessions/{id}/messages")
    public Map<String, Object> messages(@PathVariable String id) {
        ConversationMemory.Session s = conversationMemory.load(id);
        if (s == null) {
            return Map.of("id", id, "messages", List.of());
        }
        return Map.of("id", s.id, "messages",
                s.messages == null ? List.of() : s.messages);
    }

    // ---------- 工具 ----------

    private void complete(SseEmitter emitter, String name, String data) {
        try {
            emitter.send(SseEmitter.event().name(name).data(data));
            emitter.complete();
        } catch (Exception e) {
            try {
                emitter.completeWithError(e);
            } catch (Exception ignored) {
            }
        }
    }

    private static String rootMessage(Throwable t) {
        Throwable cur = t;
        while (cur.getCause() != null && cur.getCause() != cur) {
            cur = cur.getCause();
        }
        String m = cur.getMessage() == null ? cur.toString() : cur.getMessage();
        return m.length() > 300 ? m.substring(0, 300) + "…" : m;
    }
}
