package dev.tugline.agent.core;

import dev.tugline.agent.AgentProperties;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.extensions.model.dashscope.DashScopeChatModel;
import io.agentscope.extensions.model.openai.OpenAIChatModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 模型策略工厂（移植自 MO ModelFactory，裁剪版）：
 * 按 provider 选择 DashScope（Qwen）或 OpenAI 兼容（DeepSeek/GLM）模型，向上层只暴露 Model。
 */
@Component
public class ModelFactory {

    private static final Logger log = LoggerFactory.getLogger(ModelFactory.class);

    private final AgentProperties props;

    public ModelFactory(AgentProperties props) {
        this.props = props;
    }

    /** 组装模型（全局配置）。 */
    public Model build() {
        return build(null);
    }

    /**
     * 组装模型：用户个人凭证优先，未指定字段回落全局配置。
     *
     * @param cred 用户个人 AK（null = 全局配置）
     */
    public Model build(AgentCredential cred) {
        String provider = cred != null && notBlank(cred.provider()) ? cred.provider() : props.getProvider();
        String dashKey = cred != null && "dashscope".equals(provider) && notBlank(cred.apiKey())
                ? cred.apiKey() : props.getDashscopeApiKey();
        String openaiKey = cred != null && "openai".equals(provider) && notBlank(cred.apiKey())
                ? cred.apiKey() : props.getOpenaiApiKey();
        String openaiUrl = cred != null && "openai".equals(provider) && notBlank(cred.baseUrl())
                ? cred.baseUrl() : props.getOpenaiBaseUrl();
        String model = cred != null && notBlank(cred.model())
                ? cred.model()
                : ("openai".equals(provider) ? props.getOpenaiModel() : props.getDashscopeModel());
        log.info("[ModelFactory] provider={} source={} model={} dashscopeKeySet={} openaiKeySet={}",
                provider, cred != null ? "user" : "global", model,
                notBlank(dashKey), notBlank(openaiKey));
        return switch (provider) {
            case "dashscope" -> DashScopeChatModel.builder()
                    .apiKey(dashKey)
                    .modelName(model)
                    .stream(true)
                    .enableThinking(false) // 关闭模型思考，降低首字延迟
                    .build();
            case "openai" -> OpenAIChatModel.builder()
                    .apiKey(openaiKey)
                    .modelName(model)
                    .baseUrl(openaiUrl)
                    .stream(true)
                    // OpenAI 兼容端点无独立思考开关：透传 enable_thinking=false，
                    // DashScope 兼容模式等识别；不识别的网关会忽略该字段
                    .generateOptions(GenerateOptions.builder()
                            .additionalBodyParam("enable_thinking", false)
                            .build())
                    .build();
            default -> throw new IllegalArgumentException("未知的模型 provider: " + provider);
        };
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }
}
