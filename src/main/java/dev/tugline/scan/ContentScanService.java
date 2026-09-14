package dev.tugline.scan;

import dev.tugline.config.TuglineProperties;
import dev.tugline.core.ProcessRunner;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/**
 * 【模块】资源安全监测 · 扫描引擎
 * 【文件】ContentScanService.java（dev.tugline.scan）
 * 【核心功能】对文本资源做 违禁词 / 恶意脚本特征 / 风险外链 三类本地检测，
 *            对图片资源调用阿里云内容安全 AI 审核；支持扫描目录（源码）与 jar（构建产物/线上资源）
 * 【设计要点】- 词库 = 内置资源 scan/*.txt + data/scan/*.txt 自定义（合并生效，启动加载）
 *            - 任一违规发现即阻断（blocked()）；图片"审核中(review)"仅记日志不阻断
 *            - 单文件超 scan-max-file-kb 跳过；嵌套 jar（依赖库）不扫描；发现条数封顶防爆量
 */
@Service
public class ContentScanService {

    private static final Logger log = LoggerFactory.getLogger(ContentScanService.class);

    /** 参与文本检测的扩展名（源码/配置/静态页面） */
    private static final Set<String> TEXT_EXT = Set.of(
            "html", "htm", "js", "mjs", "cjs", "css", "json", "xml", "svg", "txt", "md",
            "properties", "yml", "yaml", "vue", "ts", "tsx", "jsx", "jsp", "ftl", "vm",
            "php", "ini", "conf", "sql", "csv");

    /** 参与图片 AI 审核的扩展名 */
    private static final Set<String> IMAGE_EXT = Set.of(
            "jpg", "jpeg", "png", "gif", "webp", "bmp");

    /** 目录扫描时跳过的目录（版本控制 / 依赖 / IDE） */
    private static final Set<String> SKIP_DIRS = Set.of(
            ".git", ".svn", ".hg", "node_modules", ".gradle", ".idea", ".vscode");

    /** 单扫描点违规条数上限（防止超量刷爆日志与 findings） */
    private static final int MAX_FINDINGS = 200;

    /** 恶意脚本特征（大小写不敏感）：eval 解码执行 / 隐藏 iframe 引流 / 明文 http 外链脚本 */
    private static final List<Pattern> SCRIPT_PATTERNS = List.of(
            Pattern.compile("eval\\s*\\(\\s*atob\\s*\\(", Pattern.CASE_INSENSITIVE),
            Pattern.compile("eval\\s*\\(\\s*String\\s*\\.fromCharCode", Pattern.CASE_INSENSITIVE),
            Pattern.compile("document\\.write\\s*\\(\\s*unescape\\s*\\(", Pattern.CASE_INSENSITIVE),
            Pattern.compile("atob\\s*\\(\\s*[\"'][A-Za-z0-9+/=]{200,}", Pattern.CASE_INSENSITIVE),
            Pattern.compile("<iframe[^>]{0,400}(display\\s*:\\s*none|visibility\\s*:\\s*hidden)[^>]{0,400}>",
                    Pattern.CASE_INSENSITIVE),
            Pattern.compile("<script[^>]{0,200}src\\s*=\\s*[\"']http://", Pattern.CASE_INSENSITIVE));

    /** 外链域名提取 */
    private static final Pattern URL_HOST = Pattern.compile(
            "https?://([a-zA-Z0-9._\\-]+)", Pattern.CASE_INSENSITIVE);

    private final TuglineProperties props;
    private final ImageAuditClient imageAudit;

    private volatile List<String> sensitiveWords = List.of();
    private volatile List<String> riskyDomains = List.of();
    private int builtinWords;
    private int builtinDomains;

    public ContentScanService(TuglineProperties props, ImageAuditClient imageAudit) {
        this.props = props;
        this.imageAudit = imageAudit;
    }

    @PostConstruct
    void init() {
        if (!props.isScanEnabled()) {
            log.info("[ContentScan] 资源安全监测未启用（tugline.scan-enabled=false），流水线不执行扫描");
            return;
        }
        loadWordlists();
    }

    /** 加载词库：内置 + data/scan 自定义合并；自定义文件不存在则播种空模板 */
    synchronized void loadWordlists() {
        builtinWords = sensitiveWords.size();
        builtinDomains = riskyDomains.size();
        builtinWords = loadClasspathList("scan/sensitive-words.txt").size();
        builtinDomains = loadClasspathList("scan/risky-domains.txt").size();

        Path scanDir = Path.of(props.getDataDir()).toAbsolutePath().normalize().resolve("scan");
        List<String> customWords = loadCustomList(scanDir.resolve("sensitive-words.txt"),
                "# 在此追加自定义违禁词，每行一个，# 开头为注释；与内置词库合并生效");
        List<String> customDomains = loadCustomList(scanDir.resolve("risky-domains.txt"),
                "# 在此追加自定义风险域名关键词，每行一个（子串匹配），# 开头为注释；与内置词库合并生效");

        Set<String> words = new HashSet<>(loadClasspathList("scan/sensitive-words.txt"));
        words.addAll(customWords);
        Set<String> domains = new HashSet<>(loadClasspathList("scan/risky-domains.txt"));
        domains.addAll(customDomains);
        sensitiveWords = List.copyOf(words);
        riskyDomains = List.copyOf(domains);

        log.info("[ContentScan] 词库加载完成：违禁词 {} 条（内置 {} + 自定义 {}），风险域 {} 条（内置 {} + 自定义 {}）",
                sensitiveWords.size(), builtinWords, customWords.size(),
                riskyDomains.size(), builtinDomains, customDomains.size());
    }

    // ---------- 扫描入口 ----------

    /** 扫描目录（源码闸口）：递归文本 + 图片，结果写入日志 */
    public ScanReport scanDirectory(Path root, String label, Path logFile) {
        ProcessRunner.appendLine(logFile, "[资源安全监测] 开始扫描" + label + "：" + root);
        List<ScanFinding> findings = new ArrayList<>();
        int[] textCount = {0};
        int[] imageCount = {0};
        int[] imageSkipped = {0};
        try (Stream<Path> stream = Files.walk(root)) {
            stream.filter(Files::isRegularFile).forEach(p -> {
                String rel = relPath(root, p);
                // 任意路径层级命中跳过目录（.git / node_modules 等）则整段跳过
                boolean skipped = Arrays.stream(rel.split("[/\\\\]"))
                        .anyMatch(SKIP_DIRS::contains);
                if (skipped) {
                    return;
                }
                String ext = ext(p.getFileName().toString());
                if (TEXT_EXT.contains(ext)) {
                    textCount[0]++;
                    scanText(rel, readSafely(p), findings, logFile);
                } else if (IMAGE_EXT.contains(ext)) {
                    auditImageFile(p, rel, imageCount, imageSkipped, findings);
                }
            });
        } catch (Exception e) {
            ProcessRunner.appendLine(logFile, "[资源安全监测] 目录遍历失败（不阻断）: " + e);
            log.warn("[ContentScan] 目录遍历失败（不阻断） {}: {}", root, e.toString());
        }
        return finish(label, findings, logFile, textCount[0], imageCount[0], imageSkipped[0]);
    }

    /** 扫描 jar 条目（构建产物 / 线上资源闸口）：文本 + 图片，嵌套 jar 跳过 */
    public ScanReport scanJar(Path jar, String label, Path logFile) {
        ProcessRunner.appendLine(logFile, "[资源安全监测] 开始扫描" + label + "：" + jar.getFileName());
        List<ScanFinding> findings = new ArrayList<>();
        int textCount = 0;
        int imageCount = 0;
        int imageSkipped = 0;
        try (JarFile jf = new JarFile(jar.toFile())) {
            var entries = jf.entries();
            while (entries.hasMoreElements()) {
                JarEntry e = entries.nextElement();
                if (e.isDirectory() || e.getName().endsWith(".jar")) {
                    continue;
                }
                String name = e.getName();
                String ext = ext(name);
                long size = e.getSize();
                if (TEXT_EXT.contains(ext) && size >= 0 && size <= props.getScanMaxFileKb() * 1024L) {
                    textCount++;
                    String content = new String(jf.getInputStream(e).readAllBytes(), StandardCharsets.UTF_8);
                    scanText("jar:" + name, content, findings, logFile);
                } else if (IMAGE_EXT.contains(ext) && size >= 0 && size <= props.getScanMaxFileKb() * 1024L * 4) {
                    byte[] bytes = jf.getInputStream(e).readAllBytes();
                    var verdict = imageAudit.audit(bytes, name);
                    imageCount++;
                    if (verdict.isEmpty()) {
                        imageSkipped++;
                    } else if (verdict.get().blocked()) {
                        addFinding(findings, new ScanFinding("图片违规", "jar:" + name, 0,
                                "AI 审核判定违规（" + labelOf(verdict.get().label()) + "）"), logFile);
                    }
                }
            }
        } catch (Exception e) {
            ProcessRunner.appendLine(logFile, "[资源安全监测] jar 读取失败（不阻断）: " + e);
            log.warn("[ContentScan] jar 读取失败（不阻断） {}: {}", jar, e.toString());
        }
        return finish(label, findings, logFile, textCount, imageCount, imageSkipped);
    }

    // ---------- 文本三类检测 ----------

    private void scanText(String path, String content, List<ScanFinding> findings, Path logFile) {
        if (content == null || content.isEmpty()) {
            return;
        }
        String lower = content.toLowerCase(Locale.ROOT);

        // 1. 违禁词
        for (String word : sensitiveWords) {
            int idx = lower.indexOf(word.toLowerCase(Locale.ROOT));
            if (idx >= 0) {
                addFinding(findings, new ScanFinding("违禁词", path, lineAt(content, idx),
                        "命中违禁词「" + word + "」：" + snippet(content, idx)), logFile);
            }
        }

        // 2. 恶意脚本特征
        for (Pattern p : SCRIPT_PATTERNS) {
            Matcher m = p.matcher(content);
            if (m.find()) {
                addFinding(findings, new ScanFinding("恶意脚本", path, lineAt(content, m.start()),
                        "命中恶意脚本特征 " + p.pattern() + "：" + snippet(content, m.start())), logFile);
            }
        }

        // 3. 风险外链
        Matcher m = URL_HOST.matcher(content);
        Set<String> reported = new HashSet<>();
        while (m.find()) {
            String host = m.group(1).toLowerCase(Locale.ROOT);
            for (String kw : riskyDomains) {
                if (host.contains(kw) && reported.add(host + "|" + kw)) {
                    addFinding(findings, new ScanFinding("风险外链", path, lineAt(content, m.start()),
                            "外链域名「" + host + "」命中风险关键词「" + kw + "」"), logFile);
                    break;
                }
            }
        }
    }

    // ---------- helpers ----------

    private void auditImageFile(Path file, String rel, int[] imageCount, int[] imageSkipped,
                                List<ScanFinding> findings) {
        try {
            if (Files.size(file) > props.getScanMaxFileKb() * 1024L * 4) {
                imageSkipped[0]++;
                return;
            }
            var verdict = imageAudit.audit(Files.readAllBytes(file), rel);
            imageCount[0]++;
            if (verdict.isEmpty()) {
                imageSkipped[0]++;
            } else if (verdict.get().blocked()) {
                addFinding(findings, new ScanFinding("图片违规", rel, 0,
                        "AI 审核判定违规（" + labelOf(verdict.get().label()) + "）"), null);
            }
        } catch (Exception e) {
            imageSkipped[0]++;
            log.warn("[ContentScan] 图片读取失败（跳过，不阻断） {}: {}", rel, e.toString());
        }
    }

    private void addFinding(List<ScanFinding> findings, ScanFinding f, Path logFile) {
        if (findings.size() >= MAX_FINDINGS) {
            return;
        }
        findings.add(f);
        if (logFile != null) {
            ProcessRunner.appendLine(logFile, f.toLine());
        }
    }

    private ScanReport finish(String label, List<ScanFinding> findings, Path logFile,
                              int textFiles, int images, int imageSkipped) {
        ScanReport report = new ScanReport(label, textFiles, images, imageSkipped, List.copyOf(findings));
        ProcessRunner.appendLine(logFile, report.summaryLine());
        if (report.blocked()) {
            log.warn("[ContentScan] {} 扫描发现违规 {} 处，将阻断流水线", label, findings.size());
        } else {
            log.info("[ContentScan] {} 扫描通过：文本 {} / 图片 {} / 跳过 {}", label, textFiles, images, imageSkipped);
        }
        return report;
    }

    private static String labelOf(String label) {
        return switch (label == null ? "" : label.toLowerCase(Locale.ROOT)) {
            case "porn" -> "涉黄";
            case "terrorism" -> "涉恐";
            case "ad" -> "广告引流";
            case "normal" -> "正常";
            default -> label;
        };
    }

    private static String snippet(String content, int idx) {
        if (idx < 0) {
            return "";
        }
        String s = content.substring(idx, Math.min(content.length(), idx + 60));
        return s.replace('\n', ' ').replace('\r', ' ').trim();
    }

    private static int lineAt(String content, int idx) {
        int line = 1;
        for (int i = 0; i < idx && i < content.length(); i++) {
            if (content.charAt(i) == '\n') {
                line++;
            }
        }
        return line;
    }

    private static String ext(String name) {
        int dot = name.lastIndexOf('.');
        return dot < 0 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    private static String relPath(Path root, Path file) {
        try {
            return root.relativize(file).toString();
        } catch (Exception e) {
            return file.toString();
        }
    }

    private static String readSafely(Path p) {
        try {
            return Files.readString(p, StandardCharsets.UTF_8);
        } catch (Exception e) {
            return "";
        }
    }

    private List<String> loadClasspathList(String location) {
        try (InputStream in = new ClassPathResource(location).getInputStream()) {
            return parseList(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        } catch (Exception e) {
            log.warn("[ContentScan] 内置词库读取失败（回退空） {}: {}", location, e.toString());
            return List.of();
        }
    }

    /** 读取自定义词库文件；不存在则播种模板，存在则解析（返回空行/注释以外的条目） */
    private List<String> loadCustomList(Path file, String seedHeader) {
        try {
            if (!Files.exists(file)) {
                Files.createDirectories(file.getParent());
                Files.writeString(file, seedHeader + System.lineSeparator(), StandardCharsets.UTF_8);
                return List.of();
            }
            return parseList(Files.readString(file, StandardCharsets.UTF_8));
        } catch (IOException e) {
            log.warn("[ContentScan] 自定义词库读取失败（回退空） {}: {}", file, e.toString());
            return List.of();
        }
    }

    private static List<String> parseList(String content) {
        return Arrays.stream(content.split("\\r?\\n"))
                .map(String::trim)
                .filter(s -> !s.isEmpty() && !s.startsWith("#"))
                .toList();
    }
}
