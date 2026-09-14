package dev.tugline.core;

import dev.tugline.config.TuglineProperties;
import dev.tugline.model.Repo;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * 构建体系探测与执行：
 * 1) pom.xml      → mvnw / mvn package
 * 2) build.gradle → gradlew / gradle build
 * 3) 存在 .java 源码 → javac 兜底编译（定位 public static void main 主类）
 * 产物：可执行 jar（排除 sources/javadoc/original-）或编译输出目录 + 主类。
 */
@Service
public class BuildService {

    public enum Type { MAVEN, GRADLE, JAVAC }

    public record Plan(Type type, List<String> command, String label) { }

    /** 构建结果：相对 repoDir 的产物路径 + javac 主类 */
    public record Artifact(String relativePath, String mainClass, boolean springBoot) { }

    private final TuglineProperties props;

    public BuildService(TuglineProperties props) {
        this.props = props;
    }

    /** 探测构建方案（支持多模块项目：构建文件可在子目录中） */
    public Plan detect(Path repoDir) {
        Path pom = findBuildFile(repoDir, "pom.xml");
        Path gradle = null;
        if (pom == null) {
            gradle = findBuildFile(repoDir, "build.gradle");
            if (gradle == null) {
                gradle = findBuildFile(repoDir, "build.gradle.kts");
            }
        }

        if (pom != null) {
            boolean wrapper = isExecutable(repoDir.resolve("mvnw"));
            List<String> cmd = new ArrayList<>(wrapper
                    ? List.of("./mvnw", "-q", "-DskipTests", "package")
                    : List.of("mvn", "-q", "-DskipTests", "package"));
            String label = wrapper ? "Maven Wrapper" : "Maven";
            String rel = repoDir.relativize(pom).toString();
            if (!rel.equals("pom.xml")) {
                cmd.add(1, "-f");
                cmd.add(2, rel);
                label += "（模块: " + pom.getParent().getFileName() + "）";
            }
            return new Plan(Type.MAVEN, cmd, label);
        }
        if (gradle != null) {
            boolean wrapper = isExecutable(repoDir.resolve("gradlew"));
            List<String> cmd = new ArrayList<>(wrapper
                    ? List.of("./gradlew", "build", "-x", "test", "-q")
                    : List.of("gradle", "build", "-x", "test", "-q"));
            String label = wrapper ? "Gradle Wrapper" : "Gradle";
            String dir = repoDir.relativize(gradle.getParent()).toString();
            if (!dir.equals(".")) {
                cmd.add(1, "-p");
                cmd.add(2, dir);
                label += "（模块: " + gradle.getParent().getFileName() + "）";
            }
            return new Plan(Type.GRADLE, cmd, label);
        }
        // javac 兜底
        List<String> command = List.of("sh", "-c",
                "mkdir -p .tugline-out && find . -name '*.java' -not -path '*/target/*' -not -path '*/.tugline-out/*' > .tugline-sources.txt "
                        + "&& javac -encoding UTF-8 -d .tugline-out @.tugline-sources.txt");
        return new Plan(Type.JAVAC, command, "javac（未发现 Maven/Gradle，使用兜底编译）");
    }

    /**
     * 在 repoDir 及其子目录（深度 ≤ 3）中查找构建文件；
     * 同名文件取最浅层（优先聚合 pom），保证多模块项目从根模块构建。
     */
    private Path findBuildFile(Path repoDir, String fileName) {
        try (Stream<Path> walk = Files.walk(repoDir, 3)) {
            return walk
                    .filter(p -> !p.equals(repoDir))
                    .filter(p -> {
                        String s = p.toString();
                        return !s.contains("/.git/") && !s.contains("/node_modules/")
                                && !s.contains("/target/") && !s.contains("/build/");
                    })
                    .filter(p -> p.getFileName().toString().equals(fileName))
                    .min(java.util.Comparator.comparingInt(p -> p.getNameCount()))
                    .orElse(null);
        } catch (IOException e) {
            return null;
        }
    }

    public Artifact build(Repo repo, Path repoDir, Path logFile) throws Exception {
        Plan plan = detect(repoDir);
        ProcessRunner.appendLine(logFile, "[tugline] 构建方案: " + plan.label());
        int code = ProcessRunner.run(plan.command(), repoDir, logFile,
                Duration.ofMinutes(props.getBuildTimeoutMin()), null);
        if (code != 0) {
            throw new IllegalStateException("构建失败（退出码 " + code + "），详见构建日志");
        }

        if (plan.type() == Type.JAVAC) {
            String mainClass = findMainClass(repoDir)
                    .orElseThrow(() -> new IllegalStateException(
                            "未在源码中找到 public static void main 主类，无法以 javac 方式部署"));
            return new Artifact(".tugline-out", mainClass, false);
        }

        Path jar = findJar(repoDir)
                .orElseThrow(() -> new IllegalStateException(
                        "构建成功但未找到可执行 jar（target/ 或 build/libs/ 下无产物）"));
        return new Artifact(repoDir.relativize(jar).toString(), null, isSpringBoot(repoDir));
    }

    /** 定位最新可执行 jar：搜索所有 target/ 与 build/libs/（含子模块），优先 Spring Boot 可执行 jar */
    public Optional<Path> findJar(Path repoDir) {
        List<Path> candidates = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(repoDir, 5)) {
            walk.filter(Files::isDirectory)
                    .filter(p -> {
                        String s = p.toString();
                        return !s.contains("/.git/") && !s.contains("/node_modules/");
                    })
                    .filter(p -> p.getFileName().toString().equals("target")
                            || p.getFileName().toString().equals("libs"))
                    .forEach(d -> {
                        try (Stream<Path> s = Files.list(d)) {
                            s.filter(p -> p.getFileName().toString().endsWith(".jar"))
                                    .filter(p -> {
                                        String n = p.getFileName().toString().toLowerCase(Locale.ROOT);
                                        return !n.contains("sources") && !n.contains("javadoc")
                                                && !n.startsWith("original-");
                                    })
                                    .forEach(candidates::add);
                        } catch (IOException ignored) {
                        }
                    });
        } catch (IOException ignored) {
        }
        // 优先 Spring Boot 可执行 jar（含 boot loader），其次取最新
        Optional<Path> boot = candidates.stream()
                .filter(this::isBootJar)
                .max(java.util.Comparator.comparingLong(p -> p.toFile().lastModified()));
        if (boot.isPresent()) {
            return boot;
        }
        return candidates.stream()
                .max(java.util.Comparator.comparingLong(p -> p.toFile().lastModified()));
    }

    private boolean isBootJar(Path jar) {
        try (java.util.zip.ZipFile zf = new java.util.zip.ZipFile(jar.toFile())) {
            return zf.getEntry("org/springframework/boot/loader/") != null
                    || zf.stream().anyMatch(e -> e.getName().startsWith("BOOT-INF/"));
        } catch (IOException e) {
            return false;
        }
    }

    /** pom/gradle 中是否引入 spring-boot（决定是否追加 --server.port 启动参数；含子模块构建文件） */
    public boolean isSpringBoot(Path repoDir) {
        try (Stream<Path> walk = Files.walk(repoDir, 4)) {
            return walk
                    .filter(p -> {
                        String n = p.getFileName().toString();
                        return n.equals("pom.xml") || n.equals("build.gradle") || n.equals("build.gradle.kts");
                    })
                    .filter(p -> {
                        String s = p.toString();
                        return !s.contains("/.git/") && !s.contains("/node_modules/");
                    })
                    .anyMatch(p -> {
                        try {
                            return Files.readString(p, StandardCharsets.UTF_8).contains("spring-boot");
                        } catch (IOException e) {
                            return false;
                        }
                    });
        } catch (IOException e) {
            return false;
        }
    }

    /** 扫描源码定位包含 public static void main 的类（javac 兜底用） */
    public Optional<String> findMainClass(Path repoDir) {
        Path srcRoot = Files.isDirectory(repoDir.resolve("src/main/java"))
                ? repoDir.resolve("src/main/java") : repoDir;
        try (Stream<Path> walk = Files.walk(srcRoot)) {
            return walk.filter(p -> p.getFileName().toString().endsWith(".java"))
                    .filter(p -> {
                        try {
                            return Files.readString(p, StandardCharsets.UTF_8)
                                    .contains("static void main");
                        } catch (IOException e) {
                            return false;
                        }
                    })
                    .findFirst()
                    .map(p -> {
                        Path rel = srcRoot.relativize(p);
                        String s = rel.toString();
                        if (s.endsWith(".java")) {
                            s = s.substring(0, s.length() - ".java".length());
                        }
                        return s.replace('/', '.').replace('\\', '.');
                    });
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    private boolean isExecutable(Path p) {
        return Files.exists(p) && Files.isExecutable(p);
    }
}
