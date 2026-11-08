package dev.tugline.core;

import dev.tugline.config.TuglineProperties;
import dev.tugline.model.Repo;
import dev.tugline.model.RuntimeState;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 受管 Java 应用进程管理：
 * - 以 detached 方式启动 java 子进程（独立进程组），日志追加写入 app-{repoId}.log
 * - 停止时先 SIGTERM 进程树，3 秒后 SIGKILL
 * - 健康探测：healthPath → /actuator/health → /health → /healthz → /（任意 HTTP 响应即视为存活）
 */
@Service
public class AppRuntimeService {

    private final TuglineProperties props;
    /** repoId → 运行中的 Process（本服务实例生命周期内的引用） */
    private final Map<String, Process> processes = new ConcurrentHashMap<>();

    public AppRuntimeService(TuglineProperties props) {
        this.props = props;
    }

    public Path dataDir() {
        return Path.of(props.getDataDir()).toAbsolutePath().normalize();
    }

    public Path repoDir(Repo repo) {
        return dataDir().resolve("repos").resolve(repo.getId());
    }

    public Path appLogFile(String repoId) {
        return dataDir().resolve("logs").resolve("app-" + repoId + ".log");
    }

    /**
     * 启动应用进程（调用前需已停掉旧进程）。
     */
    public RuntimeState start(Repo repo, Path repoDir, BuildService.Artifact artifact,
                              String commit, Path logFile) throws IOException {
        int port = repo.getPort();
        List<String> cmd = new ArrayList<>();
        cmd.add(resolveJavaBin());
        if (artifact.mainClass() == null) {
            cmd.add("-jar");
            cmd.add(repoDir.resolve(artifact.relativePath()).normalize().toString());
            if (artifact.springBoot()) {
                cmd.add("--server.port=" + port);
            }
        } else {
            cmd.add("-cp");
            cmd.add(repoDir.resolve(artifact.relativePath()).normalize().toString());
            cmd.add(artifact.mainClass());
        }

        Path appLog = appLogFile(repo.getId());
        ProcessRunner.appendLine(appLog, "");
        ProcessRunner.appendLine(appLog,
                "[tugline] ── 启动应用 " + repo.getName() + " @ :" + port
                        + " (" + artifact.relativePath()
                        + (artifact.mainClass() != null ? " main=" + artifact.mainClass() : "") + ") ──");

        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.directory(repoDir.toFile());
        Map<String, String> extraEnv = new LinkedHashMap<>();
        extraEnv.put("PORT", String.valueOf(port));
        extraEnv.put("TUGLINE_DEPLOYED_AT", String.valueOf(System.currentTimeMillis()));
        extraEnv.put("TUGLINE_COMMIT", commit == null ? "" : commit);
        // 仓库级别环境变量（密钥、DB 密码等），由 RepoController 校验过
        Map<String, String> repoEnv = repo.getEnv();
        if (repoEnv != null && !repoEnv.isEmpty()) {
            extraEnv.putAll(repoEnv);
        }
        // 先跑准备脚本（如拉起 MySQL），失败则不启动应用
        runPreStart(repo, repoDir, appLog, extraEnv);
        pb.environment().putAll(extraEnv);
        pb.redirectErrorStream(true);
        pb.redirectOutput(ProcessBuilder.Redirect.appendTo(appLog.toFile()));
        Process p = pb.start();
        processes.put(repo.getId(), p);

        RuntimeState st = new RuntimeState();
        st.setPid(p.pid());
        st.setPort(port);
        st.setArtifact(artifact.relativePath());
        st.setMainClass(artifact.mainClass());
        st.setStartedAt(System.currentTimeMillis());
        st.setHealth(RuntimeState.HEALTH_STARTING);
        st.setCommit(commit == null ? "" : commit);
        return st;
    }

    /**
     * 执行启动前脚本（仓库根目录、注入与主进程相同的环境变量）。
     * 非 0 退出或超时直接抛错，避免带着缺失依赖硬启动。
     */
    private void runPreStart(Repo repo, Path repoDir, Path appLog,
                             Map<String, String> env) throws IOException {
        String script = repo.getPreStartCommand();
        if (script == null || script.isBlank()) {
            return;
        }
        ProcessRunner.appendLine(appLog, "[tugline] ── 启动前脚本 ──");
        int code;
        try {
            code = ProcessRunner.run(List.of("/bin/sh", "-c", script), repoDir, appLog,
                    Duration.ofSeconds(300), env);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("启动前脚本被中断", e);
        } catch (Exception e) {
            throw new IOException("启动前脚本执行失败: " + e.getMessage(), e);
        }
        if (code != 0) {
            throw new IOException("启动前脚本失败（退出码 " + code + "），详见运行日志");
        }
        ProcessRunner.appendLine(appLog, "[tugline] 启动前脚本完成");
    }

    /** 停止受管进程（进程树 TERM → 3s → KILL）。返回是否确实停了东西 */
    public boolean stop(Repo repo) {
        Process p = processes.remove(repo.getId());
        boolean stopped = false;
        if (p != null && p.isAlive()) {
            destroyTree(p);
            stopped = true;
        }
        RuntimeState st = null;
        // 兜底：服务重启后丢引用，用记录中的 PID 清理
        var store = storeHolder;
        if (store != null) {
            st = store.runtimeOf(repo.getId());
            if (!stopped && st != null && st.getPid() > 0) {
                stopped = ProcessHandle.of(st.getPid())
                        .map(this::destroyHandleTree).orElse(false);
            }
        }
        if (st != null) {
            ProcessRunner.appendLine(appLogFile(repo.getId()), "[tugline] 应用已停止");
        }
        return stopped;
    }

    private void destroyTree(Process p) {
        p.descendants().forEach(ProcessHandle::destroy);
        p.destroy();
        try {
            if (!p.waitFor(3, java.util.concurrent.TimeUnit.SECONDS)) {
                p.descendants().forEach(ProcessHandle::destroyForcibly);
                p.destroyForcibly();
                p.waitFor(5, java.util.concurrent.TimeUnit.SECONDS); // 等 SIGKILL 真正生效，不能杀完就走
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            p.destroyForcibly();
        }
    }

    private boolean destroyHandleTree(ProcessHandle h) {
        h.descendants().forEach(ProcessHandle::destroy);
        h.destroy();
        waitForExit(h, 3000);
        if (h.isAlive()) {
            h.descendants().forEach(ProcessHandle::destroyForcibly);
            h.destroyForcibly();
            waitForExit(h, 5000);
        }
        return true;
    }

    private void waitForExit(ProcessHandle h, long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (h.isAlive() && System.currentTimeMillis() < deadline) {
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    /** 等待端口释放：旧进程完全退出后再启动新进程，避免端口占用导致新进程起不来 */
    public void awaitPortFree(int port, long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline && !isPortFree(port)) {
            try {
                Thread.sleep(300);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    private boolean isPortFree(int port) {
        try (java.net.Socket s = new java.net.Socket()) {
            s.connect(new java.net.InetSocketAddress(
                    java.net.InetAddress.getLoopbackAddress(), port), 300);
            return false; // 连得上 = 还被占用
        } catch (IOException e) {
            return true; // 连不上 = 已释放
        }
    }

    /** 进程是否仍在运行 */
    public boolean isAlive(RuntimeState st) {
        if (st == null || st.getPid() <= 0) {
            return false;
        }
        return ProcessHandle.of(st.getPid()).map(ProcessHandle::isAlive).orElse(false);
    }

    /**
     * 阻塞式健康检查：轮询直到超时。返回最终健康状态（UP / DOWN）。
     */
    public String awaitHealthy(Repo repo, RuntimeState st) {
        long deadline = System.currentTimeMillis() + props.getHealthTimeoutSec() * 1000L;
        while (System.currentTimeMillis() < deadline) {
            if (!isAlive(st)) {
                return RuntimeState.HEALTH_DOWN;
            }
            if (probeAny(repo)) {
                return RuntimeState.HEALTH_UP;
            }
            try {
                Thread.sleep(2000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return RuntimeState.HEALTH_DOWN;
            }
        }
        return RuntimeState.HEALTH_DOWN;
    }

    /** 单次探测：按候选路径顺序，任意 HTTP 响应（含 404）即认为端口已提供服务 */
    public boolean probeAny(Repo repo) {
        List<String> paths = new ArrayList<>();
        if (repo.getHealthPath() != null && !repo.getHealthPath().isBlank()) {
            paths.add(repo.getHealthPath().trim());
        }
        paths.addAll(List.of("/actuator/health", "/health", "/healthz", "/"));
        for (String path : paths) {
            if (httpOk(repo.getPort(), path)) {
                return true;
            }
        }
        return false;
    }

    private boolean httpOk(int port, String path) {
        try {
            HttpURLConnection conn = (HttpURLConnection) URI.create(
                    "http://127.0.0.1:" + port + path).toURL().openConnection();
            conn.setConnectTimeout(1500);
            conn.setReadTimeout(1500);
            conn.setRequestMethod("GET");
            int code = conn.getResponseCode();
            conn.disconnect();
            return code > 0;
        } catch (IOException e) {
            return false;
        }
    }

    private String resolveJavaBin() {
        String javaHome = System.getenv("JAVA_HOME");
        if (javaHome != null && !javaHome.isBlank()) {
            Path p = Path.of(javaHome, "bin", "java");
            if (Files.exists(p)) {
                return p.toString();
            }
        }
        return "java";
    }

    // JsonStore 循环依赖规避：由 PipelineService 在初始化时注入
    private dev.tugline.store.JsonStore storeHolder;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setStore(dev.tugline.store.JsonStore store) {
        this.storeHolder = store;
    }
}
