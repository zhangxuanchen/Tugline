package dev.tugline.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * 受管应用进程的运行时状态（repoId → RuntimeState）。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class RuntimeState {

    public static final String HEALTH_UP = "UP";
    public static final String HEALTH_DOWN = "DOWN";
    public static final String HEALTH_STARTING = "STARTING";

    private long pid;
    private int port;
    private String artifact = "";
    /** javac 兜底编译时才有的主类；jar 启动时为 null */
    private String mainClass;
    private long startedAt;
    private String health = HEALTH_STARTING;
    private String commit = "";

    public long getPid() { return pid; }
    public void setPid(long pid) { this.pid = pid; }

    public int getPort() { return port; }
    public void setPort(int port) { this.port = port; }

    public String getArtifact() { return artifact; }
    public void setArtifact(String artifact) { this.artifact = artifact; }

    public String getMainClass() { return mainClass; }
    public void setMainClass(String mainClass) { this.mainClass = mainClass; }

    public long getStartedAt() { return startedAt; }
    public void setStartedAt(long startedAt) { this.startedAt = startedAt; }

    public String getHealth() { return health; }
    public void setHealth(String health) { this.health = health; }

    public String getCommit() { return commit; }
    public void setCommit(String commit) { this.commit = commit; }
}
