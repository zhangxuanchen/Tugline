package dev.tugline.agent.core;

/**
 * 大模型访问凭证（用户个人 AK 或全局配置的规整视图）。
 *
 * @param provider dashscope / openai
 * @param apiKey   大模型 API Key
 * @param model    模型名（可空 → 回落全局默认）
 * @param baseUrl  OpenAI 兼容地址（可空 → 回落全局默认）
 */
public record AgentCredential(String provider, String apiKey, String model, String baseUrl) {

    public static AgentCredential of(String provider, String apiKey) {
        return new AgentCredential(provider, apiKey, null, null);
    }
}
