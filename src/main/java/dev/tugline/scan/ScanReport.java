package dev.tugline.scan;

import java.util.List;

/**
 * 资源安全监测 · 单个扫描点的汇总报告。
 *
 * @param label     扫描点名称：源码 / 构建产物 / 线上资源
 * @param textFiles 扫描的文本资源数
 * @param images    送审图片数
 * @param imageSkipped 跳过的图片数（超上限或未配置审核 API）
 * @param findings  违规发现（非空即阻断）
 */
public record ScanReport(String label, int textFiles, int images, int imageSkipped,
                         List<ScanFinding> findings) {

    /** 是否阻断：任一违规发现即阻断 */
    public boolean blocked() {
        return !findings.isEmpty();
    }

    /** 摘要行（写入运行日志） */
    public String summaryLine() {
        String base = "[资源安全监测] " + label + "扫描完成：文本 " + textFiles + " 个 / 图片送审 "
                + images + " 张" + (imageSkipped > 0 ? " / 跳过 " + imageSkipped : "");
        return blocked() ? base + "，发现违规 " + findings.size() + " 处" : base + "，未发现违规内容";
    }
}
