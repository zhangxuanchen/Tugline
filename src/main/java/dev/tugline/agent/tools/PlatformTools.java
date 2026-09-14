package dev.tugline.agent.tools;

import dev.tugline.core.AppRuntimeService;
import dev.tugline.core.GitService;
import dev.tugline.core.PipelineService;
import dev.tugline.core.ProcessRunner;
import dev.tugline.model.Repo;
import dev.tugline.model.Run;
import dev.tugline.model.RunStep;
import dev.tugline.model.RuntimeState;
import dev.tugline.store.JsonStore;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 【模块】Agent · 平台原生工具（M3 提前）
 * 【文件】PlatformTools.java（dev.tugline.agent.tools）
 * 【核心功能】把「启动/停止/状态/日志」等平台操作作为 Agent 工具注册，
 *            让助手对平台受管应用的操作走平台服务而非裸 shell：
 *            - 状态自动同步到应用列表（解决「助手启动了但列表没更新」）
 *            - 启动走完整流水线（FETCH→BUILD→DEPLOY→HEALTH），经过内容安全扫描闸口
 * 【设计要点】- @Tool 注解式注册，AgentFactory 里 toolkit.registerTool(this)
 *            - 应用以「名称或仓库 id」定位；多命中时报歧义让用户明确
 */
@Component
public class PlatformTools {

    private final JsonStore store;
    private final PipelineService pipeline;
    private final AppRuntimeService appRuntime;
    private final GitService gitService;

    public PlatformTools(JsonStore store, PipelineService pipeline,
                         AppRuntimeService appRuntime, GitService gitService) {
        this.store = store;
        this.pipeline = pipeline;
        this.appRuntime = appRuntime;
        this.gitService = gitService;
    }

    @Tool(name = "list_apps", readOnly = true, concurrencySafe = true,
            description = "列出 Tugline 平台管理的全部应用：名称、仓库 id、端口、运行状态（UP=运行中/STARTING=启动中/DOWN=已停止/未部署=从未通过平台启动）")
    public String listApps() {
        List<Repo> repos = store.listRepos();
        if (repos.isEmpty()) {
            return "平台还没有任何应用仓库。";
        }
        StringBuilder sb = new StringBuilder("平台应用列表：\n");
        for (Repo r : repos) {
            RuntimeState rt = store.runtimeOf(r.getId());
            String state = describeState(rt);
            sb.append("- ").append(r.getName())
                    .append(" | id=").append(r.getId())
                    .append(" | port=").append(r.getPort())
                    .append(" | 状态=").append(state);
            if (rt != null && rt.getPid() > 0) {
                sb.append(" | pid=").append(rt.getPid());
            }
            sb.append('\n');
        }
        return sb.toString();
    }

    @Tool(name = "app_status", readOnly = true, concurrencySafe = true,
            description = "查询单个平台应用的详细状态：运行状态、pid、端口、启动时间、最近一次流水线结果")
    public String appStatus(@ToolParam(name = "repo", required = true,
            description = "应用名称或仓库 id") String repo) {
        Repo r = findRepo(repo);
        RuntimeState rt = store.runtimeOf(r.getId());
        StringBuilder sb = new StringBuilder();
        sb.append(r.getName()).append(" | port=").append(r.getPort())
                .append(" | 状态=").append(describeState(rt));
        if (rt != null && rt.getPid() > 0) {
            sb.append(" | pid=").append(rt.getPid());
            sb.append(" | 进程存活=").append(appRuntime.isAlive(rt));
        }
        Optional<Run> last = store.lastRunOf(r.getId());
        if (last.isPresent()) {
            Run run = last.get();
            sb.append(" | 最近流水线: runId=").append(run.getId())
                    .append(" status=").append(run.getStatus());
        } else {
            sb.append(" | 该应用从未通过平台流水线部署过");
        }
        return sb.toString();
    }

    @Tool(name = "start_app",
            description = "通过平台流水线启动应用（FETCH 拉取→BUILD 构建→DEPLOY 部署→HEALTH 健康检查，含内容安全扫描）。" +
                    "参数可以是应用名称/仓库 id，也可以是 git 仓库地址（平台还没有该地址时会自动接入配置再启动，一步到位）。" +
                    "这是启动平台应用的唯一正确方式：状态会同步到应用列表。流水线异步执行，返回 runId 后可用 app_status 跟踪。")
    public String startApp(@ToolParam(name = "repo", required = true,
            description = "应用名称、仓库 id 或 git 仓库地址") String repo) {
        String q = repo == null ? "" : repo.trim();
        Repo r;
        if (q.matches("^(https?|file)://\\S+$")) {
            // git URL：按 url 精确匹配；平台没有该仓库时自动接入再启动（用户说「启动 <URL>」一步到位）
            r = store.listRepos().stream()
                    .filter(x -> q.equals(x.getUrl()))
                    .findFirst()
                    .orElseGet(() -> {
                        addRepo(q, null, null, null, null);
                        return findRepo(deriveName(q));
                    });
        } else {
            r = findRepo(q);
        }
        if (pipeline.isActive(r.getId())) {
            return r.getName() + " 已有流水线在执行中，请稍候（可用 app_status 查看）。";
        }
        Run run = pipeline.deploy(r, Run.Trigger.MANUAL);
        if (run == null) {
            return r.getName() + " 启动失败：流水线忙。";
        }
        return r.getName() + " 的部署流水线已启动（runId=" + run.getId() + "），"
                + "将依次执行拉取→构建→部署→健康检查，状态同步到应用列表。可用 app_status 跟踪进度。";
    }

    @Tool(name = "stop_app",
            description = "停止平台受管的应用进程（仅限通过平台部署的应用），平台运行状态会同步清除")
    public String stopApp(@ToolParam(name = "repo", required = true,
            description = "应用名称或仓库 id") String repo) {
        Repo r = findRepo(repo);
        RuntimeState rt = store.runtimeOf(r.getId());
        if (rt == null || rt.getPid() <= 0) {
            return r.getName() + " 没有平台运行记录（未通过平台启动过），无进程可停。";
        }
        boolean stopped = appRuntime.stop(r);
        store.removeRuntime(r.getId());
        return stopped ? r.getName() + " 已停止，应用列表状态已同步。"
                : r.getName() + " 停止命令已执行，但进程可能已被手动关闭。";
    }

    @Tool(name = "app_log", readOnly = true, concurrencySafe = true,
            description = "读取平台应用的标准输出日志（最近 N 行，默认 100，最多 500）")
    public String appLog(@ToolParam(name = "repo", required = true,
            description = "应用名称或仓库 id") String repo,
            @ToolParam(name = "lines", required = false,
                    description = "尾部行数，默认 100") Integer lines) {
        Repo r = findRepo(repo);
        int n = lines == null ? 100 : Math.min(Math.max(lines, 1), 500);
        List<String> ls = ProcessRunner.tail(appRuntime.appLogFile(r.getId()), n);
        if (ls.isEmpty()) {
            return r.getName() + " 暂无应用日志。";
        }
        return String.join("\n", ls);
    }

    // ---------- 仓库管理工具（第二批） ----------

    @Tool(name = "add_repo",
            description = "向平台新增一个 Git 仓库（自动从 URL 推断应用名、校验仓库可达、自动分配端口）。" +
                    "仅创建配置，不会自动部署；创建成功后可用 start_app 启动。")
    public String addRepo(@ToolParam(name = "url", required = true,
            description = "git 仓库地址（http(s)/file）") String url,
            @ToolParam(name = "branch", required = false,
                    description = "分支，默认 main") String branch,
            @ToolParam(name = "token", required = false,
                    description = "私有仓库访问 token（可选）") String token,
            @ToolParam(name = "port", required = false,
                    description = "应用端口，不填自动分配") Integer port,
            @ToolParam(name = "healthPath", required = false,
                    description = "健康检查路径，如 /actuator/health") String healthPath) {
        if (url == null || url.isBlank()) {
            throw new IllegalArgumentException("git 仓库地址不能为空");
        }
        String u = url.trim();
        if (!u.matches("^(https?|file)://\\S+$")) {
            throw new IllegalArgumentException("仓库地址需为 http(s)/file git URL");
        }
        Repo repo = new Repo();
        repo.setId("r_" + UUID.randomUUID().toString().replace("-", "").substring(0, 10));
        repo.setName(deriveName(u));
        repo.setUrl(u);
        repo.setBranch(branch == null || branch.isBlank() ? "main" : branch.trim());
        repo.setToken(token == null ? "" : token.trim());
        repo.setHealthPath(healthPath == null ? "" : healthPath.trim());
        repo.setAutoDeploy(true);
        repo.setPort(port != null && port > 0 ? port : pipeline.allocatePort(store.usedPorts()));
        repo.setCreatedAt(System.currentTimeMillis());
        try {
            gitService.validate(repo);
        } catch (Exception e) {
            throw new IllegalArgumentException("仓库校验失败：" + e.getMessage(), e);
        }
        store.addRepo(repo);
        return "应用「" + repo.getName() + "」已添加：id=" + repo.getId()
                + " branch=" + repo.getBranch() + " port=" + repo.getPort()
                + "。配置已保存，可用 start_app 启动。";
    }

    @Tool(name = "update_repo",
            description = "修改平台应用的配置：分支、端口、健康检查路径。流水线执行中不允许修改")
    public String updateRepo(@ToolParam(name = "repo", required = true,
            description = "应用名称或仓库 id") String repo,
            @ToolParam(name = "branch", required = false, description = "新分支") String branch,
            @ToolParam(name = "port", required = false, description = "新端口") Integer port,
            @ToolParam(name = "healthPath", required = false,
                    description = "新健康检查路径") String healthPath) {
        Repo r = findRepo(repo);
        if (pipeline.isActive(r.getId())) {
            throw new IllegalStateException(r.getName() + " 流水线执行中，请稍后再修改配置");
        }
        boolean changed = false;
        if (branch != null && !branch.isBlank()) {
            r.setBranch(branch.trim());
            changed = true;
        }
        if (port != null && port > 0) {
            r.setPort(port);
            changed = true;
        }
        if (healthPath != null && !healthPath.isBlank()) {
            r.setHealthPath(healthPath.trim());
            changed = true;
        }
        if (!changed) {
            return "没有提供任何要修改的字段（branch/port/healthPath）。";
        }
        store.updateRepo(r);
        return r.getName() + " 配置已更新：branch=" + r.getBranch()
                + " port=" + r.getPort() + " healthPath=" + r.getHealthPath();
    }

    @Tool(name = "delete_repo",
            description = "【破坏性操作，执行前必须先向用户确认】从平台删除应用：停止进程、删除配置与本地代码目录")
    public String deleteRepo(@ToolParam(name = "repo", required = true,
            description = "应用名称或仓库 id") String repo) {
        Repo r = findRepo(repo);
        appRuntime.stop(r);
        store.removeRepo(r.getId());
        try {
            GitService.deleteRecursively(appRuntime.repoDir(r));
        } catch (Exception ignored) {
            // 目录可能不存在，忽略
        }
        return "应用「" + r.getName() + "」已从平台删除（进程已停止、配置与本地目录已清理）。";
    }

    @Tool(name = "list_branches", readOnly = true, concurrencySafe = true,
            description = "拉取 git 仓库的远端分支列表（用于新增/切换分支前确认分支名）")
    public String listBranches(@ToolParam(name = "url", required = true,
            description = "git 仓库地址") String url,
            @ToolParam(name = "token", required = false,
                    description = "私有仓库访问 token（可选）") String token) {
        List<String> branches;
        try {
            branches = gitService.listBranches(url.trim(), token == null ? "" : token.trim());
        } catch (Exception e) {
            return "获取分支失败：" + e.getMessage();
        }
        if (branches.isEmpty()) {
            return "远端仓库没有任何分支。";
        }
        return "远端分支：" + String.join("、", branches);
    }

    // ---------- 流水线记录工具 ----------

    @Tool(name = "run_history", readOnly = true, concurrencySafe = true,
            description = "查询某应用最近的流水线运行记录（runId、状态、触发方式、时间）")
    public String runHistory(@ToolParam(name = "repo", required = true,
            description = "应用名称或仓库 id") String repo,
            @ToolParam(name = "limit", required = false,
                    description = "条数，默认 5，最多 20") Integer limit) {
        Repo r = findRepo(repo);
        int n = limit == null ? 5 : Math.min(Math.max(limit, 1), 20);
        List<Run> runs = store.listRuns(r.getId(), n);
        if (runs.isEmpty()) {
            return r.getName() + " 还没有流水线运行记录。";
        }
        StringBuilder sb = new StringBuilder(r.getName()).append(" 最近运行记录：\n");
        for (Run run : runs) {
            sb.append("- runId=").append(run.getId())
                    .append(" 状态=").append(run.getStatus())
                    .append(" 触发=").append(run.getTrigger())
                    .append(" 分支=").append(run.getBranch())
                    .append(" 时间=").append(new java.util.Date(run.getCreatedAt()))
                    .append('\n');
        }
        return sb.toString();
    }

    @Tool(name = "run_detail", readOnly = true, concurrencySafe = true,
            description = "查询单次流水线运行的详细结果：各步骤状态、耗时、安全扫描风险数、错误信息")
    public String runDetail(@ToolParam(name = "runId", required = true,
            description = "流水线运行 id，如 run_be7daca7") String runId) {
        Run run = store.getRun(runId.trim())
                .orElseThrow(() -> new IllegalArgumentException("运行记录不存在：" + runId));
        StringBuilder sb = new StringBuilder("流水线 ").append(run.getId())
                .append(" | 状态=").append(run.getStatus())
                .append(" | 触发=").append(run.getTrigger())
                .append(" | 分支=").append(run.getBranch())
                .append(" | commit=").append(run.getCommit()).append('\n');
        for (RunStep s : run.getSteps()) {
            long cost = s.getEndAt() > 0 && s.getStartAt() > 0 ? s.getEndAt() - s.getStartAt() : 0;
            sb.append("- ").append(s.getName()).append("(").append(s.getKey()).append(")")
                    .append(": ").append(s.getStatus());
            if (cost > 0) {
                sb.append(" 耗时=").append(cost / 1000).append("s");
            }
            if (s.getRisk() > 0) {
                sb.append(" 风险=").append(s.getRisk());
            }
            sb.append('\n');
        }
        if (run.getError() != null && !run.getError().isBlank()) {
            sb.append("错误信息：").append(run.getError()).append('\n');
        }
        if (run.getFindings() != null && !run.getFindings().isEmpty()) {
            sb.append("安全扫描发现 ").append(run.getFindings().size()).append(" 条问题（run_log 可看详情）\n");
        }
        return sb.toString();
    }

    @Tool(name = "run_log", readOnly = true, concurrencySafe = true,
            description = "读取某次流水线运行的步骤日志（step 取值：FETCH/BUILD/DEPLOY/HEALTH，默认 BUILD；tail 默认 100 行，最多 500）")
    public String runLog(@ToolParam(name = "runId", required = true,
            description = "流水线运行 id") String runId,
            @ToolParam(name = "step", required = false,
                    description = "步骤名 FETCH/BUILD/DEPLOY/HEALTH，默认 BUILD") String step,
            @ToolParam(name = "tail", required = false,
                    description = "尾部行数，默认 100") Integer tail) {
        Run run = store.getRun(runId.trim())
                .orElseThrow(() -> new IllegalArgumentException("运行记录不存在：" + runId));
        String st = step == null || step.isBlank() ? "BUILD" : step.trim().toUpperCase();
        int n = tail == null ? 100 : Math.min(Math.max(tail, 1), 500);
        Path log = appRuntime.dataDir().resolve("logs")
                .resolve("run-" + run.getId() + "-" + st.toLowerCase() + ".log");
        List<String> lines = ProcessRunner.tail(log, n);
        if (lines.isEmpty()) {
            return "run " + run.getId() + " 的 " + st + " 日志暂无内容。";
        }
        return String.join("\n", lines);
    }

    /** 从 git URL 推断应用名（与 RepoController.deriveName 逻辑一致） */
    private static String deriveName(String url) {
        String s = url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
        String name = s.substring(s.lastIndexOf('/') + 1);
        if (name.endsWith(".git")) {
            name = name.substring(0, name.length() - ".git".length());
        }
        return name;
    }

    /** 按「名称或 id」定位应用；模糊命中多个时抛出歧义提示 */
    private Repo findRepo(String query) {
        if (query == null || query.isBlank()) {
            throw new IllegalArgumentException("应用名不能为空，可先用 list_apps 查看列表");
        }
        String q = query.trim();
        Optional<Repo> exact = store.listRepos().stream()
                .filter(r -> r.getId().equals(q) || r.getName().equals(q))
                .findFirst();
        if (exact.isPresent()) {
            return exact.get();
        }
        List<Repo> fuzzy = store.listRepos().stream()
                .filter(r -> r.getName().toLowerCase().contains(q.toLowerCase())
                        || q.toLowerCase().contains(r.getName().toLowerCase()))
                .toList();
        if (fuzzy.size() == 1) {
            return fuzzy.get(0);
        }
        if (fuzzy.size() > 1) {
            List<String> names = fuzzy.stream().map(Repo::getName).toList();
            throw new IllegalArgumentException("匹配到多个应用：" + names + "，请用更准确的名称");
        }
        throw new IllegalArgumentException("找不到应用「" + query + "」，可先用 list_apps 查看列表");
    }

    private static String describeState(RuntimeState rt) {
        if (rt == null) {
            return "未部署（从未通过平台启动）";
        }
        return switch (rt.getHealth() == null ? "" : rt.getHealth()) {
            case RuntimeState.HEALTH_UP -> "UP（运行中）";
            case RuntimeState.HEALTH_STARTING -> "STARTING（启动中）";
            case RuntimeState.HEALTH_DOWN -> "DOWN（已停止）";
            default -> rt.getHealth();
        };
    }
}
