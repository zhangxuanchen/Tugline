package dev.tugline.scan;

import java.util.List;

/**
 * 资源安全监测 · 单条违规发现。
 *
 * @param type   违规类型：违禁词 / 恶意脚本 / 风险外链 / 图片违规
 * @param path   相对路径（jar 内条目用 jar: 前缀）
 * @param line   命中行号（无法定位时为 0）
 * @param detail 人为可读的命中说明（含命中文本摘录）
 */
public record ScanFinding(String type, String path, int line, String detail) {

    /** 单行人为可读格式（用于 run.findings 与运行日志） */
    public String toLine() {
        String loc = line > 0 ? ":" + line : "";
        return "[" + type + "] " + path + loc + " " + detail;
    }

    /** 发现列表按类型排序后转单行集合（稳定输出便于展示与测试） */
    public static List<String> toLines(List<ScanFinding> findings) {
        return findings.stream().map(ScanFinding::toLine).toList();
    }
}
