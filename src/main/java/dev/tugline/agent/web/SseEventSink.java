package dev.tugline.agent.web;

import dev.tugline.agent.core.SingleTurnExecutor;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.TextBlockDeltaEvent;
import io.agentscope.core.event.ToolCallDeltaEvent;
import io.agentscope.core.event.ToolCallEndEvent;
import io.agentscope.core.event.ToolCallStartEvent;
import io.agentscope.core.event.ToolResultEndEvent;
import io.agentscope.core.event.ToolResultStartEvent;
import io.agentscope.core.event.ToolResultTextDeltaEvent;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 单轮 Agent 事件的 SSE 转发器（移植自 MO SseEventSink）。
 * 工具事件按 toolCallId 累积流式入参/结果，结束时发出带摘要的事件；
 * 每个实例绑一条 SSE 连接（随单轮新建），天然并发隔离。
 */
final class SseEventSink implements SingleTurnExecutor.EventSink {

    private final SseEmitter emitter;
    /** 工具呼叫实例集合：用 toolCallId 隔离（M1 无工具，M3 启用） */
    private final Map<String, String> callArgs = new ConcurrentHashMap<>();
    private final Map<String, StringBuilder> callResults = new ConcurrentHashMap<>();

    SseEventSink(SseEmitter emitter) {
        this.emitter = emitter;
    }

    @Override
    public void onEvent(AgentEvent ev) {
        try {
            if (ev instanceof TextBlockDeltaEvent t) {
                emitter.send(SseEmitter.event().name("token").data(t.getDelta()));
            } else if (ev instanceof ToolCallStartEvent t) {
                callArgs.put(t.getToolCallId(), "");
            } else if (ev instanceof ToolCallDeltaEvent t) {
                callArgs.merge(t.getToolCallId(), t.getDelta(), String::concat);
            } else if (ev instanceof ToolCallEndEvent t) {
                String args = summarize(callArgs.remove(t.getToolCallId()));
                emitter.send(SseEmitter.event().name("tool")
                        .data(t.getToolCallName() + (args == null ? "" : " · " + args)));
            } else if (ev instanceof ToolResultStartEvent t) {
                callResults.put(t.getToolCallId(), new StringBuilder());
            } else if (ev instanceof ToolResultTextDeltaEvent t) {
                StringBuilder sb = callResults.get(t.getToolCallId());
                if (sb != null) {
                    sb.append(t.getDelta());
                }
            } else if (ev instanceof ToolResultEndEvent t) {
                StringBuilder sb = callResults.remove(t.getToolCallId());
                String result = summarizePreserve(sb == null ? null : sb.toString());
                if (result != null) {
                    emitter.send(SseEmitter.event().name("toolresult").data(result));
                }
            }
        } catch (Exception e) {
            emitter.completeWithError(e);
        }
    }

    /** 工具入参摘要：压缩空白、截断到 200 字符 */
    private static String summarize(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String s = raw.replaceAll("\\s+", " ").trim();
        return s.length() > 200 ? s.substring(0, 200) + "…" : s;
    }

    /** 工具结果摘要：保留换行/缩进的 Markdown 结构，按行边界截断到 2000 字符；
     *  截断点落在未闭合代码围栏内时推进到块闭合处。 */
    private static String summarizePreserve(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String s = raw
                .replace("\r\n", "\n").replace('\r', '\n')
                .replaceAll(" {2,}", " ")
                .replaceAll("\n{3,}", "\n\n")
                .trim();
        final int MAX = 2000;
        if (s.length() > MAX) {
            int cut = s.indexOf('\n', MAX);
            if (cut < 0) {
                cut = MAX;
            }
            cut = Math.min(cut, s.length());
            if ((countFencesTo(s, cut) & 1) == 1) {
                int close = s.indexOf("```", cut);
                if (close >= 0) {
                    int eol = s.indexOf('\n', close + 3);
                    cut = Math.min(s.length(), eol < 0 ? close + 3 : eol + 1);
                }
            }
            s = s.substring(0, cut) + "\n…（结果较长已截断）";
        }
        return s;
    }

    private static int countFencesTo(String s, int end) {
        int n = 0;
        int i = 0;
        while (i < end) {
            int j = s.indexOf("```", i);
            if (j < 0 || j >= end) {
                break;
            }
            n++;
            i = j + 3;
        }
        return n;
    }
}
