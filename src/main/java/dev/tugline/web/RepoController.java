package dev.tugline.web;

import dev.tugline.core.AppRuntimeService;
import dev.tugline.core.GitService;
import dev.tugline.core.PipelineService;
import dev.tugline.model.Repo;
import dev.tugline.model.Run;
import dev.tugline.model.RuntimeState;
import dev.tugline.store.JsonStore;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.UUID;

/**
 * 仓库管理与触发操作 API。
 */
@RestController
@RequestMapping("/api/repos")
public class RepoController {

    private final JsonStore store;
    private final GitService gitService;
    private final PipelineService pipeline;
    private final AppRuntimeService appRuntime;

    public RepoController(JsonStore store, GitService gitService,
                          PipelineService pipeline, AppRuntimeService appRuntime) {
        this.store = store;
        this.gitService = gitService;
        this.pipeline = pipeline;
        this.appRuntime = appRuntime;
    }

    public static class CreateRepoRequest {
        private String url;
        private String branch = "main";
        private String token = "";
        private String secret = "";
        private Integer port;
        private String healthPath = "";
        private String previewPath = "";
        private Boolean autoDeploy = true;

        public String getUrl() { return url; }
        public void setUrl(String url) { this.url = url; }
        public String getBranch() { return branch; }
        public void setBranch(String branch) { this.branch = branch; }
        public String getToken() { return token; }
        public void setToken(String token) { this.token = token; }
        public String getSecret() { return secret; }
        public void setSecret(String secret) { this.secret = secret; }
        public Integer getPort() { return port; }
        public void setPort(Integer port) { this.port = port; }
        public String getHealthPath() { return healthPath; }
        public void setHealthPath(String healthPath) { this.healthPath = healthPath; }
        public String getPreviewPath() { return previewPath; }
        public void setPreviewPath(String previewPath) { this.previewPath = previewPath; }
        public Boolean getAutoDeploy() { return autoDeploy; }
        public void setAutoDeploy(Boolean autoDeploy) { this.autoDeploy = autoDeploy; }
    }

    /** 仓库配置更新（部分字段，null 不覆盖） */
    public static class UpdateRepoRequest {
        private String branch;
        private Integer port;
        private String healthPath;
        private String previewPath;
        private String secret;
        private String token;
        private Boolean autoDeploy;

        public String getBranch() { return branch; }
        public void setBranch(String branch) { this.branch = branch; }
        public Integer getPort() { return port; }
        public void setPort(Integer port) { this.port = port; }
        public String getHealthPath() { return healthPath; }
        public void setHealthPath(String healthPath) { this.healthPath = healthPath; }
        public String getPreviewPath() { return previewPath; }
        public void setPreviewPath(String previewPath) { this.previewPath = previewPath; }
        public String getSecret() { return secret; }
        public void setSecret(String secret) { this.secret = secret; }
        public String getToken() { return token; }
        public void setToken(String token) { this.token = token; }
        public Boolean getAutoDeploy() { return autoDeploy; }
        public void setAutoDeploy(Boolean autoDeploy) { this.autoDeploy = autoDeploy; }
    }

    /** 拉取远端分支列表（添加仓库时的分支下拉数据源） */
    public static class BranchRequest {
        private String url;
        private String token = "";

        public String getUrl() { return url; }
        public void setUrl(String url) { this.url = url; }
        public String getToken() { return token; }
        public void setToken(String token) { this.token = token; }
    }

    @PostMapping("/branches")
    public Map<String, Object> branches(@RequestBody BranchRequest req) {
        if (req.getUrl() == null || req.getUrl().isBlank()) {
            throw new IllegalArgumentException("git 仓库地址不能为空");
        }
        try {
            List<String> branches = gitService.listBranches(req.getUrl().trim(), req.getToken());
            if (branches.isEmpty()) {
                throw new IllegalArgumentException("远端仓库没有任何分支");
            }
            return Map.of("branches", branches);
        } catch (IllegalArgumentException | IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("获取分支失败：" + e.getMessage(), e);
        }
    }

    @GetMapping
    public List<Repo.RepoView> list() {
        List<Repo.RepoView> views = new ArrayList<>();
        for (Repo r : store.listRepos()) {
            RuntimeState rt = store.runtimeOf(r.getId());
            Run last = store.lastRunOf(r.getId()).orElse(null);
            views.add(r.toView(rt, last));
        }
        return views;
    }

    @PostMapping
    public ResponseEntity<?> create(@RequestBody CreateRepoRequest req) {
        if (req.getUrl() == null || req.getUrl().isBlank()) {
            throw new IllegalArgumentException("git 仓库地址不能为空");
        }
        String url = req.getUrl().trim();
        if (!url.matches("^(https?|file)://\\S+$")) {
            throw new IllegalArgumentException("仓库地址需为 http(s)/file git URL");
        }

        Repo repo = new Repo();
        repo.setId("r_" + UUID.randomUUID().toString().replace("-", "").substring(0, 10));
        repo.setName(deriveName(url));
        repo.setUrl(url);
        repo.setBranch(req.getBranch() == null || req.getBranch().isBlank() ? "main" : req.getBranch().trim());
        repo.setToken(req.getToken() == null ? "" : req.getToken().trim());
        repo.setSecret(req.getSecret() == null ? "" : req.getSecret().trim());
        repo.setHealthPath(req.getHealthPath() == null ? "" : req.getHealthPath().trim());
        repo.setPreviewPath(req.getPreviewPath() == null ? "" : normalizePath(req.getPreviewPath()));
        repo.setAutoDeploy(req.getAutoDeploy() == null || req.getAutoDeploy());
        repo.setPort(req.getPort() != null && req.getPort() > 0
                ? req.getPort() : pipeline.allocatePort(store.usedPorts()));
        repo.setCreatedAt(System.currentTimeMillis());

        try {
            gitService.validate(repo);
        } catch (Exception e) {
            throw new IllegalArgumentException("仓库校验失败：" + e.getMessage(), e);
        }
        store.addRepo(repo);
        return ResponseEntity.status(HttpStatus.CREATED).body(repo.toView(null, null));
    }

    @DeleteMapping("/{id}")
    public Map<String, Object> delete(@PathVariable String id) {
        Repo repo = store.getRepo(id).orElseThrow(() -> new NoSuchElementException("仓库不存在"));
        appRuntime.stop(repo);
        store.removeRepo(id);
        try {
            GitService.deleteRecursively(appRuntime.repoDir(repo));
        } catch (Exception ignored) {
        }
        return Map.of("ok", true);
    }

    @PostMapping("/{id}/deploy")
    public ResponseEntity<?> deploy(@PathVariable String id) {
        Repo repo = store.getRepo(id).orElseThrow(() -> new NoSuchElementException("仓库不存在"));
        Run run = pipeline.deploy(repo, Run.Trigger.MANUAL);
        if (run == null) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(Map.of("error", "该仓库已有流水线在执行中"));
        }
        return ResponseEntity.accepted().body(run);
    }

    @PostMapping("/{id}/stop")
    public Map<String, Object> stop(@PathVariable String id) {
        Repo repo = store.getRepo(id).orElseThrow(() -> new NoSuchElementException("仓库不存在"));
        boolean stopped = appRuntime.stop(repo);
        store.removeRuntime(id);
        return Map.of("ok", true, "stopped", stopped);
    }

    @PostMapping("/{id}/restart")
    public Map<String, Object> restart(@PathVariable String id) {
        Repo repo = store.getRepo(id).orElseThrow(() -> new NoSuchElementException("仓库不存在"));
        if (pipeline.isActive(id)) {
            throw new IllegalStateException("该仓库已有流水线在执行中，无法重启");
        }
        RuntimeState st = pipeline.restart(repo);
        return Map.of("ok", true, "runtime", st);
    }

    @PostMapping("/{id}/update")
    public Repo.RepoView update(@PathVariable String id, @RequestBody UpdateRepoRequest req) {
        Repo repo = store.getRepo(id).orElseThrow(() -> new NoSuchElementException("仓库不存在"));
        if (pipeline.isActive(id)) {
            throw new IllegalStateException("该仓库流水线执行中，请稍后再修改配置");
        }
        if (req.getBranch() != null && !req.getBranch().isBlank()) {
            repo.setBranch(req.getBranch().trim());
        }
        if (req.getPort() != null && req.getPort() > 0) {
            repo.setPort(req.getPort());
        }
        if (req.getHealthPath() != null) {
            repo.setHealthPath(req.getHealthPath().trim());
        }
        if (req.getPreviewPath() != null) {
            repo.setPreviewPath(normalizePath(req.getPreviewPath()));
        }
        if (req.getSecret() != null) {
            repo.setSecret(req.getSecret().trim());
        }
        if (req.getToken() != null) {
            repo.setToken(req.getToken().trim());
        }
        if (req.getAutoDeploy() != null) {
            repo.setAutoDeploy(req.getAutoDeploy());
        }
        store.updateRepo(repo);
        return repo.toView(store.runtimeOf(id), store.lastRunOf(id).orElse(null));
    }

    private static String normalizePath(String p) {
        String s = p == null ? "" : p.trim();
        if (s.isEmpty()) {
            return "";
        }
        return s.startsWith("/") ? s : "/" + s;
    }

    private static String deriveName(String url) {
        String s = url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
        String name = s.substring(s.lastIndexOf('/') + 1);
        if (name.endsWith(".git")) {
            name = name.substring(0, name.length() - ".git".length());
        }
        return name;
    }
}
