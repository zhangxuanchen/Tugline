package dev.tugline.agent.core;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.tugline.agent.AgentProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 会话记忆（M1 简化版，移植自 MO ConversationMemory 的落盘思路）：
 * - 每个会话一个 JSON 文件：data/agent/sessions/{sessionId}.json
 * - context()：把最近若干轮历史组装成文本块注入系统提示（新实例每轮重建，连续性靠它）
 * - append()：单轮结束落盘（用户消息 + 助手回复）
 * - 上下文压缩（要旨/折叠）在 M4 引入，M1 只保留最近 MAX_CONTEXT 条
 */
@Component
public class ConversationMemory {

    private static final Logger log = LoggerFactory.getLogger(ConversationMemory.class);

    /** 注入上下文的最大历史条数（user+assistant 合计） */
    private static final int MAX_CONTEXT = 12;
    /** 单条消息注入上限（字符），超长截断 */
    private static final int MAX_MSG_CHARS = 4000;

    private final ObjectMapper mapper = new ObjectMapper()
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

    private final Path dir;
    private final ConcurrentHashMap<String, ReentrantLock> locks = new ConcurrentHashMap<>();

    public ConversationMemory(AgentProperties props) {
        this.dir = Path.of(props.getSessionDir()).toAbsolutePath().normalize();
    }

    // ---------- 数据结构 ----------

    public static class Session {
        public String id;
        public String title = "";
        public long createdAt;
        public long updatedAt;
        public List<Msg> messages = new ArrayList<>();
    }

    public static class Msg {
        public String role;
        public String content;
        public long ts;

        public Msg() {}

        public Msg(String role, String content, long ts) {
            this.role = role;
            this.content = content;
            this.ts = ts;
        }
    }

    // ---------- 会话管理 ----------

    /** 会话记忆键（M1 单工作区单 Agent，键即 sessionId） */
    public String key(String sessionId) {
        return sessionId == null || sessionId.isBlank() ? "default" : sessionId;
    }

    public Session create(String sessionId) {
        Session s = new Session();
        s.id = key(sessionId);
        s.title = "新会话";
        s.createdAt = System.currentTimeMillis();
        s.updatedAt = s.createdAt;
        save(s);
        return s;
    }

    public Session load(String sessionId) {
        Path f = file(key(sessionId));
        if (!Files.exists(f)) {
            return null;
        }
        try {
            return mapper.readValue(f.toFile(), Session.class);
        } catch (Exception e) {
            log.warn("[ConversationMemory] 会话文件读取失败（回退空） {}: {}", sessionId, e.toString());
            return null;
        }
    }

    /** 会话列表（按更新时间倒序） */
    public List<Session> listSessions() {
        List<Session> out = new ArrayList<>();
        if (!Files.exists(dir)) {
            return out;
        }
        try (var stream = Files.list(dir)) {
            stream.filter(p -> p.getFileName().toString().endsWith(".json"))
                    .forEach(p -> {
                        try {
                            out.add(mapper.readValue(p.toFile(), Session.class));
                        } catch (Exception ignored) {
                        }
                    });
        } catch (IOException e) {
            log.warn("会话目录扫描失败: {}", e.toString());
        }
        out.sort(Comparator.comparingLong((Session s) -> s.updatedAt).reversed());
        return out;
    }

    public boolean delete(String sessionId) {
        try {
            return Files.deleteIfExists(file(key(sessionId)));
        } catch (IOException e) {
            return false;
        }
    }

    // ---------- 上下文注入与落盘 ----------

    /** 组装会话历史上下文（注入系统提示的 MEMORY 区）；空会话返回空串 */
    public String context(String memKey) {
        Session s = load(memKey);
        if (s == null || s.messages.isEmpty()) {
            return "";
        }
        List<Msg> recent = s.messages.size() > MAX_CONTEXT
                ? s.messages.subList(s.messages.size() - MAX_CONTEXT, s.messages.size())
                : s.messages;
        StringBuilder sb = new StringBuilder("[会话历史（早前对话，供连续理解）]\n");
        for (Msg m : recent) {
            String who = "user".equals(m.role) ? "用户" : "助手";
            String c = m.content == null ? "" : m.content;
            if (c.length() > MAX_MSG_CHARS) {
                c = c.substring(0, MAX_MSG_CHARS) + "…（截断）";
            }
            sb.append(who).append(": ").append(c.replace("\n", " ")).append('\n');
        }
        return sb.toString();
    }

    /** 单轮落盘：用户消息 + 助手最终回复；首条用户消息作为会话标题 */
    public void append(String memKey, String userMsg, String assistantMsg) {
        lock(memKey).lock();
        try {
            Session s = load(memKey);
            long now = System.currentTimeMillis();
            if (s == null) {
                s = new Session();
                s.id = memKey;
                s.createdAt = now;
            }
            s.messages.add(new Msg("user", userMsg == null ? "" : userMsg, now));
            if (assistantMsg != null && !assistantMsg.isBlank()) {
                s.messages.add(new Msg("assistant", assistantMsg, now));
            }
            if (s.title == null || s.title.isBlank() || "新会话".equals(s.title)) {
                String t = userMsg == null ? "" : userMsg.trim().replace('\n', ' ');
                s.title = t.length() > 24 ? t.substring(0, 24) + "…" : t;
            }
            s.updatedAt = now;
            save(s);
        } finally {
            lock(memKey).unlock();
        }
    }

    // ---------- IO ----------

    private Path file(String id) {
        // 会话 id 只允许安全字符，防路径穿越
        String safe = id.replaceAll("[^a-zA-Z0-9_-]", "_");
        return dir.resolve(safe + ".json");
    }

    private ReentrantLock lock(String id) {
        return locks.computeIfAbsent(key(id), k -> new ReentrantLock());
    }

    private void save(Session s) {
        try {
            Files.createDirectories(dir);
            Path f = file(s.id);
            Path tmp = f.resolveSibling(f.getFileName() + ".tmp");
            mapper.writerWithDefaultPrettyPrinter().writeValue(tmp.toFile(), s);
            try {
                Files.move(tmp, f, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.AtomicMoveNotSupportedException e) {
                Files.move(tmp, f, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            log.warn("[ConversationMemory] 会话落盘失败（不阻断） {}: {}", s.id, e.toString());
        }
    }
}
