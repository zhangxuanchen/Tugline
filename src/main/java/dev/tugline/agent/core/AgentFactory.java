package dev.tugline.agent.core;

import dev.tugline.agent.AgentProperties;
import dev.tugline.agent.tools.PlatformTools;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.model.ExecutionConfig;
import io.agentscope.core.model.Model;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.harness.agent.HarnessAgent;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.nio.file.Path;
import java.util.List;

/**
 * Agent 装配工厂（移植自 MO AgentFactory，M1 极简版）：
 * 每轮新建独立实例（Agent/Toolkit 有状态，不可并发复用），
 * 系统提示 = 基础人设 + 会话历史上下文。M3 在此处注册平台工具。
 */
@Component
public class AgentFactory {

    public static final String AGENT_ID = "tugline-assistant";

    private final AgentProperties props;
    private final ModelFactory modelFactory;
    private final PlatformTools platformTools;

    public AgentFactory(AgentProperties props, ModelFactory modelFactory, PlatformTools platformTools) {
        this.props = props;
        this.modelFactory = modelFactory;
        this.platformTools = platformTools;
    }

    /**
     * 创建一个全新的 Agent 实例。
     *
     * @param sessionId     会话 id（harness 状态目录隔离用）
     * @param memoryContext 会话历史上下文块（空串表示新会话）
     * @param credential    用户个人大模型凭证（null = 使用全局配置）
     */
    public HarnessAgent create(String sessionId, String memoryContext, AgentCredential credential) {
        Toolkit toolkit = new Toolkit(
                io.agentscope.core.tool.ToolkitConfig.builder().allowToolDeletion(true).build());
        // 平台原生工具（M3 提前）：应用启动/停止/状态/日志走平台服务，状态自动同步
        toolkit.registerTool(platformTools);

        // 模型：可重试（幂等读）
        Model model = modelFactory.build(credential);
        ExecutionConfig modelExec = ExecutionConfig.builder()
                .timeout(Duration.ofMinutes(5))
                .maxAttempts(2)
                .build();

        ReActAgent built = ReActAgent.builder()
                .name(AGENT_ID)
                .sysPrompt(composeSystemPrompt(memoryContext))
                .model(model)
                .toolkit(toolkit)
                .maxIters(16)
                .checkRunning(true)
                .modelExecutionConfig(modelExec)
                .build();

        // harness 委托壳：harness 状态收敛到会话目录下的 .workbench 子目录
        // 硬约束（白名单外全部禁用）：
        // - disableShellTool/disableFilesystemTools：禁 execute 与文件读写，杜绝直接 java -jar/kill/改平台数据
        // - disableSubagents：禁 agent_spawn/task_* 子代理编排（编排是 MO 经理职责，员工 Agent 不需要）
        // - disableDynamicSkills：禁 load_skill_through_path 动态技能加载（路径注入面）
        // 平台操作只允许走 PlatformTools 12 个工具
        Path workbench = Path.of(props.getSessionDir()).toAbsolutePath().normalize().resolve(".workbench");
        // deny 封杀：ToolsConfig.deny 优先于默认 allow，把 harness 内置工具全部拒之门外
        // （execute/文件读写/子代理/任务等待/动态技能加载），模型 schema 里不再出现、调用也被拒绝
        io.agentscope.harness.agent.tools.ToolsConfig toolsCfg = new io.agentscope.harness.agent.tools.ToolsConfig();
        toolsCfg.setDeny(List.of(
                "execute", "read_file", "write_file", "edit_file", "grep_files", "glob_files", "list_files",
                "agent_spawn", "agent_send", "agent_list", "task_list", "task_output", "task_cancel",
                "wait_async_results", "load_skill_through_path", "delete_tool",
                // harness 的 memory/session 工具与 Tugline 自己的 ConversationMemory 冗余，且跨会话记忆
                // 是污染源（旧会话的 execute 调用样本/mock 文案会被召回，带偏新会话），全部关闭
                "memory_get", "memory_save", "memory_search",
                "session_history", "session_list", "session_search"));
        HarnessAgent harness = HarnessAgent.Builder.fromAgent(built)
                .workspace(workbench)
                .agentId(AGENT_ID + "-" + sessionId)
                .disableShellTool()
                .disableFilesystemTools()
                .disableSubagents()
                .disableDynamicSkills()
                .disableMemoryTools()
                .disableMemoryHooks()
                .toolsConfig(toolsCfg)
                .build();
        return harness;
    }

    /** 系统提示：基础人设 + 平台工具规范 + 能力边界 + 会话历史 */
    private String composeSystemPrompt(String memoryContext) {
        StringBuilder sb = new StringBuilder();
        sb.append("你是 Tugline 控制台的内置助手。Tugline 是一个多仓库部署流水线平台：")
                .append("管理多个 Git 仓库的拉取、构建（Maven/Gradle）、部署（本地 java -jar）与健康检查，")
                .append("流水线分四步：拉取代码(FETCH) → 构建(BUILD) → 部署(DEPLOY) → 健康检查(HEALTH)。")
                .append('\n')
                .append("【平台操作规范（必须遵守）】平台内的一切操作必须调用平台工具实际执行，禁止只给文字说明，")
                .append("也禁止用 shell 绕过平台（直接 java -jar 启动、kill 杀进程、改平台数据文件）：\n")
                .append("- list_apps：看平台有哪些应用及状态\n")
                .append("- start_app：启动应用（唯一正确方式，走一键部署流程：拉取→构建→部署→健康检查，状态自动同步到应用列表）。")
                .append("参数可直接传 git 地址：平台没有该仓库时自动接入配置并启动，一步到位，不要中途停下询问\n")
                .append("- stop_app：停止平台受管应用\n")
                .append("- app_status：查应用状态与最近流水线结果；app_log：看应用运行日志\n")
                .append("- add_repo：新增仓库（add 后可用 start_app 启动）；update_repo：改分支/端口/健康路径\n")
                .append("- delete_repo：删除应用（破坏性，必须先向用户确认）\n")
                .append("- list_branches：查远端分支；run_history / run_detail / run_log：查流水线记录与步骤日志\n")
                .append("用户说「启动/重启某应用」一律调 start_app；说「关掉/停止」调 stop_app；")
                .append("说「添加/接入某仓库」调 add_repo；问「为什么失败/日志」用 run_detail + run_log 排查。\n")
                .append("你没有 shell 和直接读写文件的能力：凡平台工具覆盖不了的事（如查看系统进程、读平台外文件、执行任意命令），")
                .append("如实告知用户做不了并建议在平台界面操作，不要编造结果、不要尝试绕过。\n")
                .append("回答要求：简体中文；简洁直接，先给结论再给依据；")
                .append("涉及部署/日志排查时，结构化输出「根因 / 证据 / 修复建议」。\n");
        if (memoryContext != null && !memoryContext.isBlank()) {
            sb.append('\n').append(memoryContext).append('\n');
        }
        return sb.toString();
    }
}
