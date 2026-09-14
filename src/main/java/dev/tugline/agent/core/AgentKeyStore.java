package dev.tugline.agent.core;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.tugline.config.TuglineProperties;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * 【模块】Agent · 用户大模型 AK 仓库
 * 【文件】AgentKeyStore.java（dev.tugline.agent.core）
 * 【核心功能】按用户维护大模型 API Key（新增/删除/设为当前使用），
 *            聊天时优先使用用户个人 AK，未配置则回落全局 tugline.agent.* 配置
 * 【设计要点】- 落盘 data/agent/keys.json（与 auth.json 同信任域，明文存储；接口只回掩码）
 *            - 同一用户同一时刻仅一个「当前使用」AK（activeId，null = 全局默认）
 *            - 全部方法 synchronized（文件读写 + 内存视图一致）
 */
@Component
public class AgentKeyStore {

    private static final Logger log = LoggerFactory.getLogger(AgentKeyStore.class);

    /** 单条用户 AK（apiKey 只落盘，绝不出接口） */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class KeyEntry {
        private String id;
        private String name;
        private String provider;   // dashscope | openai
        private String apiKey;
        private String model;      // 可空
        private String baseUrl;    // 可空（openai 兼容地址）

        public String getId() { return id; }
        public void setId(String id) { this.id = id; }
        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        public String getProvider() { return provider; }
        public void setProvider(String provider) { this.provider = provider; }
        public String getApiKey() { return apiKey; }
        public void setApiKey(String apiKey) { this.apiKey = apiKey; }
        public String getModel() { return model; }
        public void setModel(String model) { this.model = model; }
        public String getBaseUrl() { return baseUrl; }
        public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    @com.fasterxml.jackson.annotation.JsonAutoDetect(fieldVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.ANY)
    private static class UserKeys {
        List<KeyEntry> keys = new ArrayList<>();
        String activeId;
    }

    private final TuglineProperties props;
    private final ObjectMapper mapper = new ObjectMapper();
    private Path file;
    private Map<String, UserKeys> users = new LinkedHashMap<>();

    public AgentKeyStore(TuglineProperties props) {
        this.props = props;
    }

    @PostConstruct
    synchronized void init() {
        file = Path.of(props.getDataDir()).toAbsolutePath().normalize()
                .resolve("agent").resolve("keys.json");
        try {
            if (Files.exists(file)) {
                users = mapper.readValue(file.toFile(),
                        mapper.getTypeFactory().constructMapType(LinkedHashMap.class, String.class, UserKeys.class));
            }
        } catch (Exception e) {
            log.warn("[AgentKeyStore] AK 文件读取失败（回退空） {}: {}", file, e.toString());
            users = new LinkedHashMap<>();
        }
        log.info("[AgentKeyStore] 已加载 {} 个用户的 AK 配置", users.size());
    }

    /** 某用户的全部 AK（返回内部副本） */
    public synchronized List<KeyEntry> list(String username) {
        UserKeys uk = users.get(username);
        return uk == null ? List.of() : new ArrayList<>(uk.keys);
    }

    /** 某用户当前使用的 AK id（null = 全局默认） */
    public synchronized String activeId(String username) {
        UserKeys uk = users.get(username);
        return uk == null ? null : uk.activeId;
    }

    /** 新增 AK，返回条目 id */
    public synchronized String add(String username, String name, String provider,
                                   String apiKey, String model, String baseUrl) {
        KeyEntry e = new KeyEntry();
        e.setId("ak_" + UUID.randomUUID().toString().substring(0, 8));
        e.setName(name == null || name.isBlank() ? defaultName(provider) : name.trim());
        e.setProvider(provider);
        e.setApiKey(apiKey.trim());
        e.setModel(model == null ? "" : model.trim());
        e.setBaseUrl(baseUrl == null ? "" : baseUrl.trim());
        UserKeys uk = users.computeIfAbsent(username, k -> new UserKeys());
        uk.keys.add(e);
        if (uk.activeId == null) {
            uk.activeId = e.getId(); // 首条自动设为当前使用
        }
        save();
        return e.getId();
    }

    /** 删除 AK；被删的是当前使用则回落全局默认 */
    public synchronized boolean remove(String username, String id) {
        UserKeys uk = users.get(username);
        if (uk == null || uk.keys.removeIf(k -> k.getId().equals(id)) == false) {
            return false;
        }
        if (id.equals(uk.activeId)) {
            uk.activeId = null;
        }
        save();
        return true;
    }

    /** 设为当前使用；id 为 null 表示回落全局默认 */
    public synchronized boolean setActive(String username, String id) {
        UserKeys uk = users.computeIfAbsent(username, k -> new UserKeys());
        if (id == null) {
            uk.activeId = null;
            save();
            return true;
        }
        boolean exists = uk.keys.stream().anyMatch(k -> k.getId().equals(id));
        if (!exists) {
            return false;
        }
        uk.activeId = id;
        save();
        return true;
    }

    /** 解析某用户当前生效的大模型凭证；未配置个人 AK 返回 empty（由调用方回落全局） */
    public synchronized Optional<AgentCredential> resolve(String username) {
        UserKeys uk = users.get(username);
        if (uk == null || uk.activeId == null) {
            return Optional.empty();
        }
        return uk.keys.stream()
                .filter(k -> k.getId().equals(uk.activeId))
                .findFirst()
                .map(k -> new AgentCredential(k.getProvider(), k.getApiKey(), k.getModel(), k.getBaseUrl()));
    }

    /** 掩码显示：只露前 3 后 4（如 sk-****wxyz），任何接口不回传原文、不可复原 */
    public static String mask(String key) {
        if (key == null || key.isBlank()) {
            return "";
        }
        String k = key.trim();
        if (k.length() <= 7) {
            return k.charAt(0) + "****";
        }
        return k.substring(0, 3) + "****" + k.substring(k.length() - 4);
    }

    private void save() {
        try {
            Files.createDirectories(file.getParent());
            // 先写临时文件再原子替换，避免序列化失败把 keys.json 截断损坏
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            mapper.writerWithDefaultPrettyPrinter().writeValue(tmp.toFile(), users);
            Files.move(tmp, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                    java.nio.file.StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            log.warn("[AgentKeyStore] AK 落盘失败（不阻断）: {}", e.toString());
        }
    }

    private static String defaultName(String provider) {
        return "dashscope".equals(provider) ? "通义千问" : "OpenAI 兼容";
    }
}
