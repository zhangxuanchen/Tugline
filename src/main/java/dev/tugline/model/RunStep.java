package dev.tugline.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * 流水线单步执行状态。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class RunStep {

    public enum Status { PENDING, RUNNING, SUCCESS, FAILED }

    private String key;
    private String name;
    private Status status = Status.PENDING;
    private long startAt;
    private long endAt;
    /** 资源安全监测：本步骤发现的违规条数（0 = 无监测风险） */
    private int risk;

    public RunStep() { }

    public RunStep(String key, String name) {
        this.key = key;
        this.name = name;
    }

    public String getKey() { return key; }
    public void setKey(String key) { this.key = key; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public Status getStatus() { return status; }
    public void setStatus(Status status) { this.status = status; }

    public long getStartAt() { return startAt; }
    public void setStartAt(long startAt) { this.startAt = startAt; }

    public long getEndAt() { return endAt; }
    public void setEndAt(long endAt) { this.endAt = endAt; }

    public int getRisk() { return risk; }
    public void setRisk(int risk) { this.risk = risk; }
}
