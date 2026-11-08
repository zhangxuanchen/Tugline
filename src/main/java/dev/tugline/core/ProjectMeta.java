package dev.tugline.core;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/**
 * 解析仓库根目录的 .tugline.yml 声明文件（应用自报访问信息）：
 *
 * <pre>
 * tugline:
 *   preview-path: /admin/index.html       # 演示地址的访问路径（默认 /）
 *   health-path: /actuator/health         # 健康检查路径（留空走自动探测）
 *   display-name: Citadel 权限中心         # 可选，控制台展示名
 *   pre-start: docker compose up -d --wait mysql   # 启动应用前执行的准备命令
 * </pre>
 *
 * 键也可直接放在顶层（preview-path / health-path / display-name / pre-start）。
 * 文件不存在或解析失败一律静默忽略，不影响部署。
 */
public record ProjectMeta(String previewPath, String healthPath, String displayName,
                          String preStart) {

    public static final String FILE_NAME = ".tugline.yml";

    private static final ProjectMeta EMPTY = new ProjectMeta(null, null, null, null);

    public static ProjectMeta parse(Path repoDir) {
        Path file = repoDir.resolve(FILE_NAME);
        if (!Files.exists(file)) {
            return EMPTY;
        }
        try {
            String text = Files.readString(file, StandardCharsets.UTF_8);
            org.yaml.snakeyaml.Yaml yaml = new org.yaml.snakeyaml.Yaml();
            Object root = yaml.load(text);
            if (!(root instanceof Map<?, ?> map)) {
                return EMPTY;
            }
            Object fl = map.get("tugline");
            Map<?, ?> cfg = fl instanceof Map<?, ?> m ? m : map;
            return new ProjectMeta(
                    str(cfg.get("preview-path"), cfg.get("previewPath")),
                    str(cfg.get("health-path"), cfg.get("healthPath")),
                    raw(cfg.get("display-name"), cfg.get("displayName")),
                    raw(cfg.get("pre-start"), cfg.get("preStartCommand")));
        } catch (IOException | org.yaml.snakeyaml.error.YAMLException e) {
            return EMPTY;
        }
    }

    private static String str(Object... candidates) {
        for (Object c : candidates) {
            if (c != null) {
                String s = String.valueOf(c).trim();
                if (!s.isEmpty()) {
                    return s.startsWith("/") ? s : "/" + s;
                }
            }
        }
        return null;
    }

    /** 原样取值，不做路径补斜杠（用于命令类字段） */
    private static String raw(Object... candidates) {
        for (Object c : candidates) {
            if (c != null) {
                String s = String.valueOf(c).trim();
                if (!s.isEmpty()) {
                    return s;
                }
            }
        }
        return null;
    }
}
