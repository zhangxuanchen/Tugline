package dev.tugline.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 全局配置（对应 application.yml 的 tugline.*）
 */
@ConfigurationProperties(prefix = "tugline")
public class TuglineProperties {

    /** 状态 / 仓库副本 / 日志根目录 */
    private String dataDir = "./data";

    /** 应用端口自动分配起始值 */
    private int portBase = 9001;

    /** 构建超时（分钟） */
    private int buildTimeoutMin = 15;

    /** git 操作超时（分钟） */
    private int gitTimeoutMin = 5;

    /** 健康检查超时（秒） */
    private int healthTimeoutSec = 90;

    // ---------- 资源安全监测 ----------

    /** 是否启用内容安全监测（拉取后/构建后/部署后三个闸口） */
    private boolean scanEnabled = true;

    /** 单个文本文件扫描大小上限（KB），超过跳过并记录 */
    private int scanMaxFileKb = 2048;

    /** 单个扫描点最多送审图片数（控制外部 API 成本与时延） */
    private int scanMaxImages = 50;

    /** 阿里云内容安全（图片审核）AccessKey；为空则跳过图片审核 */
    private String scanImageAuditKey = "";

    /** 阿里云内容安全（图片审核）Secret */
    private String scanImageAuditSecret = "";

    /** 阿里云内容安全区域 */
    private String scanImageAuditRegion = "cn-shanghai";

    public String getDataDir() { return dataDir; }
    public void setDataDir(String dataDir) { this.dataDir = dataDir; }

    public int getPortBase() { return portBase; }
    public void setPortBase(int portBase) { this.portBase = portBase; }

    public int getBuildTimeoutMin() { return buildTimeoutMin; }
    public void setBuildTimeoutMin(int buildTimeoutMin) { this.buildTimeoutMin = buildTimeoutMin; }

    public int getGitTimeoutMin() { return gitTimeoutMin; }
    public void setGitTimeoutMin(int gitTimeoutMin) { this.gitTimeoutMin = gitTimeoutMin; }

    public int getHealthTimeoutSec() { return healthTimeoutSec; }
    public void setHealthTimeoutSec(int healthTimeoutSec) { this.healthTimeoutSec = healthTimeoutSec; }

    public boolean isScanEnabled() { return scanEnabled; }
    public void setScanEnabled(boolean scanEnabled) { this.scanEnabled = scanEnabled; }

    public int getScanMaxFileKb() { return scanMaxFileKb; }
    public void setScanMaxFileKb(int scanMaxFileKb) { this.scanMaxFileKb = scanMaxFileKb; }

    public int getScanMaxImages() { return scanMaxImages; }
    public void setScanMaxImages(int scanMaxImages) { this.scanMaxImages = scanMaxImages; }

    public String getScanImageAuditKey() { return scanImageAuditKey; }
    public void setScanImageAuditKey(String scanImageAuditKey) { this.scanImageAuditKey = scanImageAuditKey; }

    public String getScanImageAuditSecret() { return scanImageAuditSecret; }
    public void setScanImageAuditSecret(String scanImageAuditSecret) { this.scanImageAuditSecret = scanImageAuditSecret; }

    public String getScanImageAuditRegion() { return scanImageAuditRegion; }
    public void setScanImageAuditRegion(String scanImageAuditRegion) { this.scanImageAuditRegion = scanImageAuditRegion; }
}
