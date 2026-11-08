package dev.tugline.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 受管的 GitHub 仓库定义。
 * 注意：含 token/secret/env 明文，仅持久化在本地 state.json，API 出参由 RepoView 脱敏。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class Repo {

    private String id;
    private String name;
    private String url;
    private String branch = "main";
    private String token = "";
    private String secret = "";
    private int port;
    private String healthPath = "";
    /** 演示地址访问路径（如 /admin/index.html），可由 .tugline.yml 自报 */
    private String previewPath = "";
    private boolean autoDeploy = true;
    /** 部署时注入到应用进程的环境变量 */
    private Map<String, String> env = new LinkedHashMap<>();
    /** 启动应用前的准备脚本（如拉起 MySQL 容器），空则跳过 */
    private String preStartCommand = "";
    private long createdAt;

    /** API 脱敏视图 */
    public record RepoView(
            String id, String name, String url, String branch, int port,
            String healthPath, String previewPath, boolean autoDeploy, long createdAt,
            boolean hasToken, boolean hasSecret, boolean hasEnv, String preStartCommand,
            RuntimeState runtime, Run lastRun, String status) {
    }

    public RepoView toView(RuntimeState runtime, Run lastRun) {
        String status;
        if (runtime != null && runtime.getPid() > 0
                && ProcessHandle.of(runtime.getPid()).isEmpty()) {
            status = "EXITED";
        } else if (runtime != null) {
            status = runtime.getHealth();
        } else {
            status = "STOPPED";
        }
        boolean hasEnv = env != null && !env.isEmpty();
        return new RepoView(id, name, url, branch, port, healthPath, previewPath, autoDeploy,
                createdAt, token != null && !token.isBlank(), secret != null && !secret.isBlank(),
                hasEnv, preStartCommand == null ? "" : preStartCommand,
                runtime, lastRun, status);
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getUrl() { return url; }
    public void setUrl(String url) { this.url = url; }

    public String getBranch() { return branch; }
    public void setBranch(String branch) { this.branch = branch; }

    public String getToken() { return token; }
    public void setToken(String token) { this.token = token; }

    public String getSecret() { return secret; }
    public void setSecret(String secret) { this.secret = secret; }

    public int getPort() { return port; }
    public void setPort(int port) { this.port = port; }

    public String getHealthPath() { return healthPath; }
    public void setHealthPath(String healthPath) { this.healthPath = healthPath; }

    public String getPreviewPath() { return previewPath; }
    public void setPreviewPath(String previewPath) { this.previewPath = previewPath; }

    public boolean isAutoDeploy() { return autoDeploy; }
    public void setAutoDeploy(boolean autoDeploy) { this.autoDeploy = autoDeploy; }

    public long getCreatedAt() { return createdAt; }
    public void setCreatedAt(long createdAt) { this.createdAt = createdAt; }

    public Map<String, String> getEnv() { return env == null ? new LinkedHashMap<>() : env; }
    public void setEnv(Map<String, String> env) { this.env = env == null ? new LinkedHashMap<>() : env; }

    public String getPreStartCommand() { return preStartCommand == null ? "" : preStartCommand; }
    public void setPreStartCommand(String preStartCommand) {
        this.preStartCommand = preStartCommand == null ? "" : preStartCommand;
    }
}
