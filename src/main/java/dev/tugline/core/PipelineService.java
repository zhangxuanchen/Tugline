package dev.tugline.core;

import dev.tugline.config.TuglineProperties;
import dev.tugline.model.Repo;
import dev.tugline.model.Run;
import dev.tugline.model.RunStep;
import dev.tugline.model.RuntimeState;
import dev.tugline.scan.ContentScanService;
import dev.tugline.scan.ScanFinding;
import dev.tugline.scan.ScanReport;
import dev.tugline.store.JsonStore;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 流水线编排：FETCH → BUILD → DEPLOY → HEALTH 四步线性状态机。
 * - 同一仓库同一时刻仅允许一个 run（内存互斥）
 * - 步骤在虚拟线程上串行执行，状态实时写回 JsonStore
 * - 资源安全监测：拉取后/构建后/部署后三个闸口做内容合规扫描，违规即阻断
 */
@Service
public class PipelineService {

    private static final Logger log = LoggerFactory.getLogger(PipelineService.class);

    private final JsonStore store;
    private final GitService gitService;
    private final BuildService buildService;
    private final AppRuntimeService appRuntime;
    private final ContentScanService scanService;
    private final TuglineProperties props;

    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private final Map<String, Boolean> activeByRepo = new ConcurrentHashMap<>();

    public PipelineService(JsonStore store, GitService gitService,
                           BuildService buildService, AppRuntimeService appRuntime,
                           ContentScanService scanService, TuglineProperties props) {
        this.store = store;
        this.gitService = gitService;
        this.buildService = buildService;
        this.appRuntime = appRuntime;
        this.scanService = scanService;
        this.props = props;
    }

    @PostConstruct
    void init() {
        // 服务重启后：清理已死亡进程的运行时记录，存活进程转回健康探测
        store.sweepDeadRuntime();
        for (Repo repo : store.listRepos()) {
            RuntimeState st = store.runtimeOf(repo.getId());
            if (st != null) {
                watchHealthAsync(repo);
            }
        }
    }

    /** 触发完整流水线；同仓库并发触发返回 null */
    public Run deploy(Repo repo, Run.Trigger trigger) {
        if (activeByRepo.putIfAbsent(repo.getId(), true) != null) {
            return null;
        }
        Run run = newRun(repo, trigger);
        store.addRun(run);
        executor.submit(() -> execute(repo, run));
        return run;
    }

    private Run newRun(Repo repo, Run.Trigger trigger) {
        Run run = new Run();
        run.setId("run_" + UUID.randomUUID().toString().substring(0, 8));
        run.setRepoId(repo.getId());
        run.setTrigger(trigger);
        run.setBranch(repo.getBranch());
        run.setCreatedAt(System.currentTimeMillis());
        run.setSteps(List.of(
                new RunStep(Run.STEP_FETCH, "拉取代码"),
                new RunStep(Run.STEP_BUILD, "构建"),
                new RunStep(Run.STEP_DEPLOY, "部署"),
                new RunStep(Run.STEP_HEALTH, "健康检查")));
        return run;
    }

    private void execute(Repo repo, Run run) {
        Path repoDir = appRuntime.repoDir(repo);
        Path logBase = Path.of(props.getDataDir()).toAbsolutePath().normalize()
                .resolve("logs");
        try {
            // 1. FETCH
            RunStep fetch = run.step(Run.STEP_FETCH);
            begin(fetch);
            persist(run);
            Path fetchLog = runLog(logBase, run, "fetch");
            String commit = gitService.sync(repo, repoDir, fetchLog);
            run.setCommit(commit);
            ok(fetch);
            persist(run);

            // 闸口 1：源码内容安全监测（拉取后）
            if (props.isScanEnabled()) {
                ScanReport report = scanService.scanDirectory(repoDir, "源码", fetchLog);
                if (gateBlocked(run, fetch, report)) {
                    return;
                }
            }

            // 应用自报访问信息（.tugline.yml）：仓库未手动配置时采用
            ProjectMeta meta = ProjectMeta.parse(repoDir);
            boolean repoChanged = false;
            if (isBlank(repo.getPreviewPath()) && meta.previewPath() != null) {
                repo.setPreviewPath(meta.previewPath());
                repoChanged = true;
            }
            if (isBlank(repo.getHealthPath()) && meta.healthPath() != null) {
                repo.setHealthPath(meta.healthPath());
                repoChanged = true;
            }
            if (isBlank(repo.getName()) && meta.displayName() != null) {
                repo.setName(meta.displayName());
                repoChanged = true;
            }
            if (repoChanged) {
                store.updateRepo(repo);
            }

            // 2. BUILD
            RunStep build = run.step(Run.STEP_BUILD);
            begin(build);
            persist(run);
            Path buildLog = runLog(logBase, run, "build");
            BuildService.Artifact artifact = buildService.build(repo, repoDir, buildLog);
            run.setArtifact(artifact.relativePath());
            ok(build);
            persist(run);

            // 3. DEPLOY
            RunStep deploy = run.step(Run.STEP_DEPLOY);
            begin(deploy);
            persist(run);
            appRuntime.stop(repo);
            Path deployLog = runLog(logBase, run, "deploy");
            RuntimeState st = appRuntime.start(repo, repoDir, artifact, commit, deployLog);
            store.setRuntime(repo.getId(), st);
            ok(deploy);
            persist(run);

            // 4. HEALTH
            RunStep health = run.step(Run.STEP_HEALTH);
            begin(health);
            persist(run);
            String finalHealth = appRuntime.awaitHealthy(repo, st);
            st.setHealth(finalHealth);
            store.setRuntime(repo.getId(), st);
            if (RuntimeState.HEALTH_UP.equals(finalHealth)) {
                ok(health);
                run.setStatus(Run.Status.SUCCESS);
            } else {
                fail(health);
                run.setStatus(Run.Status.FAILED);
                run.setError("健康检查未通过（超时 " + props.getHealthTimeoutSec()
                        + "s），进程已保留，请查看运行日志");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            run.setStatus(Run.Status.FAILED);
            run.setError("流水线被中断");
        } catch (Exception e) {
            run.setStatus(Run.Status.FAILED);
            String msg = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            run.setError(msg);
            // 把当前 RUNNING 的步骤标记为失败
            run.getSteps().stream()
                    .filter(s -> s.getStatus() == RunStep.Status.RUNNING)
                    .forEach(PipelineService::fail);
            ProcessRunner.appendLine(
                    Path.of(props.getDataDir(), "logs").resolve("pipeline.log"),
                    "[tugline] run " + run.getId() + " 失败: " + msg);
        } finally {
            run.setEndAt(System.currentTimeMillis());
            persist(run);
            activeByRepo.remove(repo.getId());
        }
    }

    /** 用现有产物重启（不重新构建） */
    public RuntimeState restart(Repo repo) {
        RuntimeState old = store.runtimeOf(repo.getId());
        if (old == null || old.getArtifact() == null || old.getArtifact().isBlank()) {
            throw new IllegalStateException("尚无已部署产物，请先执行完整部署");
        }
        Path repoDir = appRuntime.repoDir(repo);
        boolean jar = old.getMainClass() == null;
        BuildService.Artifact artifact = new BuildService.Artifact(
                old.getArtifact(), old.getMainClass(), jar && buildService.isSpringBoot(repoDir));
        appRuntime.stop(repo);
        try {
            RuntimeState st = appRuntime.start(repo, repoDir, artifact, old.getCommit(),
                    appRuntime.appLogFile(repo.getId()));
            store.setRuntime(repo.getId(), st);
            watchHealthAsync(repo);
            return st;
        } catch (Exception e) {
            throw new IllegalStateException("重启失败: " + e.getMessage(), e);
        }
    }

    /** 异步健康观测：供 restart / 服务恢复后使用 */
    public void watchHealthAsync(Repo repo) {
        executor.submit(() -> {
            RuntimeState st = store.runtimeOf(repo.getId());
            if (st == null) {
                return;
            }
            String h = appRuntime.awaitHealthy(repo, st);
            st.setHealth(h);
            store.setRuntime(repo.getId(), st);
        });
    }

    public boolean isActive(String repoId) {
        return activeByRepo.containsKey(repoId);
    }

    // ---------- helpers ----------

    /** 内容安全监测闸口：违规则将该步骤标记失败、阻断流水线，并返回 true */
    private boolean gateBlocked(Run run, RunStep step, ScanReport report) {
        if (!report.blocked()) {
            return false;
        }
        step.setStatus(RunStep.Status.FAILED);
        step.setRisk(report.findings().size());
        step.setEndAt(System.currentTimeMillis());
        run.getFindings().addAll(ScanFinding.toLines(report.findings()));
        run.setStatus(Run.Status.FAILED);
        run.setError("资源安全监测：" + report.label() + "发现违规 " + report.findings().size()
                + " 处，已阻断流水线（详见运行日志）");
        persist(run);
        log.warn("[ContentScan] run={} {} 违规 {} 处，流水线已阻断", run.getId(), report.label(),
                report.findings().size());
        return true;
    }

    private Path runLog(Path logBase, Run run, String step) {
        return logBase.resolve("run-" + run.getId() + "-" + step + ".log");
    }

    private static void begin(RunStep s) {
        s.setStatus(RunStep.Status.RUNNING);
        s.setStartAt(System.currentTimeMillis());
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    private static void ok(RunStep s) {
        s.setStatus(RunStep.Status.SUCCESS);
        s.setEndAt(System.currentTimeMillis());
    }

    private static void fail(RunStep s) {
        if (s.getStatus() == RunStep.Status.RUNNING || s.getStatus() == RunStep.Status.PENDING) {
            s.setStatus(RunStep.Status.FAILED);
            s.setEndAt(System.currentTimeMillis());
        }
    }

    private void persist(Run run) {
        store.updateRun(run);
    }

    /** 自动分配端口：从 portBase 起找第一个未被占用且未被分配的端口 */
    public int allocatePort(java.util.List<Integer> usedPorts) {
        for (int p = props.getPortBase(); p < props.getPortBase() + 1000; p++) {
            if (usedPorts.contains(p)) {
                continue;
            }
            try (java.net.ServerSocket ss = new java.net.ServerSocket(p)) {
                ss.close();
                return p;
            } catch (Exception ignored) {
                // 端口被系统占用，尝试下一个
            }
        }
        throw new IllegalStateException("端口池已耗尽（" + props.getPortBase() + "+1000）");
    }
}
