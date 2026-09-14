package dev.tugline.agent.web;

import dev.tugline.agent.core.AgentKeyStore;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 【模块】Agent · 用户大模型 AK 管理
 * 【文件】AgentKeyController.java（dev.tugline.agent.web）
 * 【核心功能】按登录用户维护大模型 AK：列表（掩码）/ 新增 / 删除 / 设为当前使用（含回落全局默认）
 * 【设计要点】- 身份取 JWT Authentication.getName()，天然多用户隔离
 *            - apiKey 只落盘不回传；接口一律给 keyMasked 掩码视图
 */
@RestController
@RequestMapping("/api/agent/keys")
public class AgentKeyController {

    private final AgentKeyStore keyStore;

    public AgentKeyController(AgentKeyStore keyStore) {
        this.keyStore = keyStore;
    }

    public record AddKeyRequest(String name, String provider, String apiKey,
                                String model, String baseUrl) {
    }

    public record ActiveKeyRequest(String id) {
    }

    /** 某用户的 AK 列表 + 当前使用项（items 有序，activeId=null 表示全局默认） */
    @GetMapping
    public Map<String, Object> list(Authentication auth) {
        String user = auth.getName();
        List<Map<String, Object>> items = keyStore.list(user).stream()
                .map(k -> Map.<String, Object>of(
                        "id", k.getId(),
                        "name", k.getName(),
                        "provider", k.getProvider(),
                        "model", k.getModel() == null ? "" : k.getModel(),
                        "baseUrl", k.getBaseUrl() == null ? "" : k.getBaseUrl(),
                        "keyMasked", AgentKeyStore.mask(k.getApiKey())))
                .toList();
        return Map.of("items", items, "activeId", keyStore.activeId(user) == null ? "" : keyStore.activeId(user));
    }

    /** 新增 AK（provider: dashscope | openai；openai 建议带 baseUrl） */
    @PostMapping
    public Map<String, Object> add(Authentication auth, @RequestBody AddKeyRequest req) {
        validate(req);
        String id = keyStore.add(auth.getName(), req.name(), req.provider(),
                req.apiKey(), req.model(), req.baseUrl());
        return Map.of("id", id);
    }

    /** 删除 AK；删除当前使用项后自动回落全局默认 */
    @DeleteMapping("/{id}")
    public Map<String, Object> remove(Authentication auth, @PathVariable String id) {
        return Map.of("ok", keyStore.remove(auth.getName(), id));
    }

    /** 设为当前使用（id 为空 = 回落全局默认） */
    @PostMapping("/active")
    public Map<String, Object> setActive(Authentication auth, @RequestBody ActiveKeyRequest req) {
        String id = req.id() == null || req.id().isBlank() ? null : req.id();
        return Map.of("ok", keyStore.setActive(auth.getName(), id));
    }

    private static void validate(AddKeyRequest req) {
        if (req.apiKey() == null || req.apiKey().isBlank()) {
            throw new IllegalArgumentException("API Key 不能为空");
        }
        if (!"dashscope".equals(req.provider()) && !"openai".equals(req.provider())) {
            throw new IllegalArgumentException("provider 仅支持 dashscope / openai");
        }
    }
}
