package dev.tugline.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.ArrayList;
import java.util.List;

/**
 * 一次流水线执行记录。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class Run {

    public enum Trigger { MANUAL, WEBHOOK }

    public enum Status { RUNNING, SUCCESS, FAILED }

    public static final String STEP_FETCH = "FETCH";
    public static final String STEP_BUILD = "BUILD";
    public static final String STEP_DEPLOY = "DEPLOY";
    public static final String STEP_HEALTH = "HEALTH";

    private String id;
    private String repoId;
    private Trigger trigger;
    private String branch;
    private String commit = "";
    private Status status = Status.RUNNING;
    private List<RunStep> steps = new ArrayList<>();
    private String artifact = "";
    private String error = "";
    private long createdAt;
    private long endAt;
    /** 资源安全监测：本次运行全部违规条目（人为可读单行格式，超出上限截断） */
    private List<String> findings = new ArrayList<>();

    /** 找到指定 key 的步骤 */
    public RunStep step(String key) {
        return steps.stream().filter(s -> s.getKey().equals(key)).findFirst().orElse(null);
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getRepoId() { return repoId; }
    public void setRepoId(String repoId) { this.repoId = repoId; }

    public Trigger getTrigger() { return trigger; }
    public void setTrigger(Trigger trigger) { this.trigger = trigger; }

    public String getBranch() { return branch; }
    public void setBranch(String branch) { this.branch = branch; }

    public String getCommit() { return commit; }
    public void setCommit(String commit) { this.commit = commit; }

    public Status getStatus() { return status; }
    public void setStatus(Status status) { this.status = status; }

    public List<RunStep> getSteps() { return steps; }
    public void setSteps(List<RunStep> steps) { this.steps = steps; }

    public String getArtifact() { return artifact; }
    public void setArtifact(String artifact) { this.artifact = artifact; }

    public String getError() { return error; }
    public void setError(String error) { this.error = error; }

    public long getCreatedAt() { return createdAt; }
    public void setCreatedAt(long createdAt) { this.createdAt = createdAt; }

    public long getEndAt() { return endAt; }
    public void setEndAt(long endAt) { this.endAt = endAt; }

    public List<String> getFindings() { return findings; }
    public void setFindings(List<String> findings) { this.findings = findings; }
}
