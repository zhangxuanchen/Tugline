package dev.tugline.core;

import dev.tugline.config.TuglineProperties;
import dev.tugline.model.Repo;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Locale;

/**
 * Git 操作：clone / pull / 当前 commit / 连通性校验。
 * 私有仓库通过将 token 注入 remote URL 实现（https://x-access-token:{token}@...）。
 */
@Service
public class GitService {

    /** clone/fetch 网络抖动自动重试次数（含首次）与间隔 */
    private static final int GIT_ATTEMPTS = 3;
    private static final long GIT_RETRY_DELAY_MS = 3000;

    private final TuglineProperties props;

    public GitService(TuglineProperties props) {
        this.props = props;
    }

    private Duration gitTimeout() {
        return Duration.ofMinutes(props.getGitTimeoutMin());
    }

    /** 带凭据的 URL（公开仓库原样返回） */
    public String credUrl(Repo repo) {
        String url = repo.getUrl();
        if (repo.getToken() == null || repo.getToken().isBlank()) {
            return url;
        }
        return url.replaceFirst("^https://", "https://x-access-token:" + repo.getToken() + "@");
    }

    /** 预校验：仓库可达且分支存在 */
    public void validate(Repo repo) throws Exception {
        Path log = Path.of(props.getDataDir(), "logs", "git-validate.log");
        int code = ProcessRunner.run(
                List.of("git", "ls-remote", "--heads", credUrl(repo), repo.getBranch()),
                null, log, gitTimeout(), null);
        if (code != 0) {
            throw new IllegalArgumentException("仓库不可达或分支不存在（检查 URL / 分支 / Token）");
        }
    }

    /** 拉取远端仓库的全部分支（用于添加仓库时的分支下拉选择） */
    public List<String> listBranches(String url, String token) throws Exception {
        String cred = url;
        if (token != null && !token.isBlank()) {
            cred = url.replaceFirst("^https://", "https://x-access-token:" + token + "@");
        }
        ProcessBuilder pb = new ProcessBuilder("git", "ls-remote", "--heads", cred);
        pb.redirectErrorStream(true);
        Process p = pb.start();
        boolean done = p.waitFor(gitTimeout().toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS);
        if (!done) {
            p.destroyForcibly();
            throw new IllegalStateException("获取分支超时，请检查仓库地址");
        }
        String output = new String(p.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        if (p.exitValue() != 0) {
            throw new IllegalArgumentException("仓库不可达（检查地址 / Token）");
        }
        List<String> branches = new java.util.ArrayList<>();
        for (String line : output.split("\n")) {
            // ls-remote 行格式：<sha>  refs/heads/<name>
            int idx = line.indexOf("refs/heads/");
            if (idx >= 0) {
                branches.add(line.substring(idx + "refs/heads/".length()).trim());
            }
        }
        return branches;
    }

    /** clone 或 pull 到 targetDir，返回当前 commit SHA */
    public String sync(Repo repo, Path targetDir, Path logFile) throws Exception {
        if (!Files.exists(targetDir.resolve(".git"))) {
            cloneWithRetry(repo, targetDir, logFile);
        } else {
            // 幂等拉取：fetch + 硬复位，避免本地脏状态
            fetchWithRetry(repo, targetDir, logFile);
            int code = ProcessRunner.run(List.of("git", "reset", "--hard", "origin/" + repo.getBranch()),
                    targetDir, logFile, gitTimeout(), null);
            if (code != 0) {
                throw new IllegalStateException("git reset 失败（退出码 " + code + "），详见拉取日志");
            }
        }
        return currentCommit(targetDir, logFile);
    }

    /** clone 带重试：远端网络抖动（SSL EOF、超时、exec 失败等）自动重试，仍失败则抛出真实原因 */
    private void cloneWithRetry(Repo repo, Path targetDir, Path logFile) throws Exception {
        if (Files.exists(targetDir)) {
            deleteRecursively(targetDir);
        }
        Files.createDirectories(targetDir.getParent());
        String lastError = "";
        for (int attempt = 1; attempt <= GIT_ATTEMPTS; attempt++) {
            if (attempt > 1) {
                ProcessRunner.appendLine(logFile, "[tugline] 拉取失败（" + lastError + "），"
                        + (GIT_RETRY_DELAY_MS / 1000) + " 秒后重试（第 " + attempt + "/" + GIT_ATTEMPTS + " 次）");
                Thread.sleep(GIT_RETRY_DELAY_MS);
                // 清理上次失败残留，避免目标目录已存在导致 clone 二次失败
                if (Files.exists(targetDir)) {
                    deleteRecursively(targetDir);
                }
            }
            try {
                int code = ProcessRunner.run(List.of(
                                "git", "clone", "--branch", repo.getBranch(),
                                "--single-branch", "--depth", "50", credUrl(repo),
                                targetDir.toString()),
                        null, logFile, gitTimeout(), null);
                if (code == 0) {
                    return;
                }
                lastError = "退出码 " + code;
            } catch (InterruptedException ie) {
                throw ie;
            } catch (Exception e) {
                lastError = e.getMessage();
            }
        }
        throw new IllegalStateException("git clone 失败（" + lastError + "，已重试 "
                + (GIT_ATTEMPTS - 1) + " 次）：多为网络无法访问 Git 服务，详见拉取日志");
    }

    /** fetch 带重试：远端网络抖动（SSL EOF、超时、exec 失败等）自动重试，仍失败则抛出真实原因 */
    private void fetchWithRetry(Repo repo, Path targetDir, Path logFile) throws Exception {
        int code = ProcessRunner.run(List.of("git", "remote", "set-url", "origin", credUrl(repo)),
                targetDir, logFile, gitTimeout(), null);
        if (code != 0) {
            throw new IllegalStateException("git remote set-url 失败（退出码 " + code + "）");
        }
        String lastError = "";
        for (int attempt = 1; attempt <= GIT_ATTEMPTS; attempt++) {
            if (attempt > 1) {
                ProcessRunner.appendLine(logFile, "[tugline] 拉取失败（" + lastError + "），"
                        + (GIT_RETRY_DELAY_MS / 1000) + " 秒后重试（第 " + attempt + "/" + GIT_ATTEMPTS + " 次）");
                Thread.sleep(GIT_RETRY_DELAY_MS);
            }
            try {
                code = ProcessRunner.run(List.of("git", "fetch", "origin", repo.getBranch(), "--depth", "50"),
                        targetDir, logFile, gitTimeout(), null);
                if (code == 0) {
                    return;
                }
                lastError = "退出码 " + code;
            } catch (InterruptedException ie) {
                throw ie;
            } catch (Exception e) {
                lastError = e.getMessage();
            }
        }
        throw new IllegalStateException("git fetch 失败（" + lastError + "，已重试 "
                + (GIT_ATTEMPTS - 1) + " 次）：多为网络无法访问 Git 服务，详见拉取日志");
    }

    public String currentCommit(Path dir, Path logFile) throws Exception {
        int code = ProcessRunner.run(List.of("git", "rev-parse", "HEAD"),
                dir, logFile, Duration.ofSeconds(30), null);
        if (code != 0) {
            throw new IllegalStateException("git rev-parse 失败");
        }
        // 直接捕获命令输出作为返回值
        ProcessBuilder pb = new ProcessBuilder("git", "rev-parse", "HEAD");
        pb.directory(dir.toFile());
        pb.redirectErrorStream(true);
        Process p = pb.start();
        String commit = new String(p.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8)
                .trim().toLowerCase(Locale.ROOT);
        p.waitFor(30, java.util.concurrent.TimeUnit.SECONDS);
        return commit;
    }

    public static void deleteRecursively(Path path) throws Exception {
        if (!Files.exists(path)) {
            return;
        }
        try (var walk = Files.walk(path)) {
            walk.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.delete(p);
                } catch (IOException ignored) {
                }
            });
        }
    }
}
