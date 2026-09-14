package dev.tugline.web;

import dev.tugline.core.AppRuntimeService;
import dev.tugline.core.ProcessRunner;
import dev.tugline.model.Run;
import dev.tugline.store.JsonStore;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

/**
 * 运行记录与日志读取 API。
 */
@RestController
@RequestMapping("/api")
public class RunController {

    private final JsonStore store;
    private final AppRuntimeService appRuntime;

    public RunController(JsonStore store, AppRuntimeService appRuntime) {
        this.store = store;
        this.appRuntime = appRuntime;
    }

    @GetMapping("/repos/{id}/runs")
    public Map<String, Object> runs(@PathVariable String id,
                                    @RequestParam(defaultValue = "1") int page,
                                    @RequestParam(defaultValue = "10") int size) {
        return store.pageRuns(id, page, Math.min(Math.max(size, 1), 50));
    }

    @GetMapping("/runs/{id}")
    public Run run(@PathVariable String id) {
        return store.getRun(id).orElseThrow(() -> new NoSuchElementException("运行记录不存在"));
    }

    @GetMapping("/runs/{id}/logs")
    public Map<String, Object> runLogs(@PathVariable String id,
                                       @RequestParam(defaultValue = "BUILD") String step,
                                       @RequestParam(defaultValue = "300") int tail) {
        Run run = store.getRun(id).orElseThrow(() -> new NoSuchElementException("运行记录不存在"));
        Path log = Path.of(dataDir(), "logs",
                "run-" + run.getId() + "-" + step.toLowerCase() + ".log");
        List<String> lines = ProcessRunner.tail(log, Math.min(tail, 1000));
        return Map.of("lines", lines);
    }

    @GetMapping("/repos/{id}/applog")
    public Map<String, Object> appLog(@PathVariable String id,
                                      @RequestParam(defaultValue = "300") int tail) {
        List<String> lines = ProcessRunner.tail(
                appRuntime.appLogFile(id), Math.min(tail, 1000));
        return Map.of("lines", lines);
    }

    private String dataDir() {
        return appRuntime.dataDir().toString();
    }
}
