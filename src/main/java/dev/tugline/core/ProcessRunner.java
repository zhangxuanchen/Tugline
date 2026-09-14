package dev.tugline.core;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * 子进程执行工具：合并 stdout/stderr 追加写入日志文件，支持超时强杀与日志 tail。
 */
public final class ProcessRunner {

    private ProcessRunner() { }

    public record Result(int exitCode) {
        public boolean ok() { return exitCode == 0; }
    }

    /** 执行命令并追加日志；非 0 退出或超时抛出异常 */
    public static int run(List<String> cmd, Path cwd, Path logFile,
                          java.time.Duration timeout, Map<String, String> extraEnv) throws Exception {
        appendLine(logFile, "");
        appendLine(logFile, "[tugline] $ " + String.join(" ", cmd)
                + "   # " + LocalDateTime.now());
        ProcessBuilder pb = new ProcessBuilder(cmd);
        if (cwd != null) {
            pb.directory(cwd.toFile());
        }
        if (extraEnv != null && !extraEnv.isEmpty()) {
            pb.environment().putAll(extraEnv);
        }
        pb.redirectErrorStream(true);
        if (logFile != null) {
            Files.createDirectories(logFile.getParent());
            pb.redirectOutput(ProcessBuilder.Redirect.appendTo(logFile.toFile()));
        } else {
            pb.inheritIO();
        }
        Process p = pb.start();
        boolean finished = p.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
        if (!finished) {
            p.descendants().forEach(ProcessHandle::destroyForcibly);
            p.destroyForcibly();
            appendLine(logFile, "[tugline] 命令超时（" + timeout.toMinutes() + " 分钟），已终止");
            throw new IllegalStateException("命令执行超时: " + String.join(" ", cmd));
        }
        int code = p.exitValue();
        if (code != 0) {
            appendLine(logFile, "[tugline] 退出码 " + code);
        }
        return code;
    }

    /** 读取日志最后 n 行 */
    public static List<String> tail(Path file, int n) {
        if (file == null || !Files.exists(file)) {
            return List.of();
        }
        try {
            List<String> all = Files.readAllLines(file, StandardCharsets.UTF_8);
            if (all.size() <= n) {
                return all;
            }
            return all.subList(all.size() - n, all.size());
        } catch (IOException e) {
            return List.of("读取日志失败: " + e.getMessage());
        }
    }

    public static void appendLine(Path file, String line) {
        if (file == null) {
            return;
        }
        try {
            Files.createDirectories(file.getParent());
            Files.write(file, (line + System.lineSeparator()).getBytes(StandardCharsets.UTF_8),
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException ignored) {
        }
    }
}
