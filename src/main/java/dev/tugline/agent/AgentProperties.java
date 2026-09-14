package dev.tugline.agent;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Agent 侧边栏配置（对应 application.yml 的 tugline.agent.*）
 */
@ConfigurationProperties(prefix = "tugline.agent")
public class AgentProperties {

    /** 总开关：false 时不装配任何 Agent Bean */
    private boolean enabled = true;

    /** 模型提供方：dashscope | openai */
    private String provider = "dashscope";

    /** DashScope（通义千问）API Key */
    private String dashscopeApiKey = "";

    /** DashScope 模型名 */
    private String dashscopeModel = "qwen3-max";

    /** OpenAI 兼容（DeepSeek/GLM 等）API Key */
    private String openaiApiKey = "";

    /** OpenAI 兼容模型名 */
    private String openaiModel = "deepseek-chat";

    /** OpenAI 兼容 baseUrl */
    private String openaiBaseUrl = "https://api.deepseek.com";

    /** 会话记忆落盘目录 */
    private String sessionDir = "./data/agent/sessions";

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    public String getProvider() { return provider; }
    public void setProvider(String provider) { this.provider = provider; }

    public String getDashscopeApiKey() { return dashscopeApiKey; }
    public void setDashscopeApiKey(String dashscopeApiKey) { this.dashscopeApiKey = dashscopeApiKey; }

    public String getDashscopeModel() { return dashscopeModel; }
    public void setDashscopeModel(String dashscopeModel) { this.dashscopeModel = dashscopeModel; }

    public String getOpenaiApiKey() { return openaiApiKey; }
    public void setOpenaiApiKey(String openaiApiKey) { this.openaiApiKey = openaiApiKey; }

    public String getOpenaiModel() { return openaiModel; }
    public void setOpenaiModel(String openaiModel) { this.openaiModel = openaiModel; }

    public String getOpenaiBaseUrl() { return openaiBaseUrl; }
    public void setOpenaiBaseUrl(String openaiBaseUrl) { this.openaiBaseUrl = openaiBaseUrl; }

    public String getSessionDir() { return sessionDir; }
    public void setSessionDir(String sessionDir) { this.sessionDir = sessionDir; }

    /** 当前 provider 的 API Key 是否已配置 */
    public boolean keyConfigured() {
        String key = "openai".equals(provider) ? openaiApiKey : dashscopeApiKey;
        return key != null && !key.isBlank();
    }
}
