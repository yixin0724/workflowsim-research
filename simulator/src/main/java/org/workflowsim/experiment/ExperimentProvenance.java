package org.workflowsim.experiment;

import java.io.IOException;
import java.io.InputStream;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 在不依赖 Git 或 JVM 当前工作目录的前提下采集内容寻址 provenance 信息。
 *
 * <p>v3 将核心模拟器身份与可选的 study/reference driver 身份分开记录。类路径所在模块
 * 的 POM 和源码树由类的 code source 推导，而不是从 {@code user.dir} 向上猜测项目根，
 * 因而同一工件从 Reactor 根、子模块目录或外部目录启动时不会得到错误的核心身份。</p>
 */
final class ExperimentProvenance {

    static final String SCHEMA_V3 = "workflowsim-provenance-v3";
    private static final String CORE_GROUP_ID = "org.workflowsim";
    private static final String CORE_ARTIFACT_ID = "workflowsim";
    private static final String CORE_VERSION = "1.0";

    private ExperimentProvenance() {
    }

    static Map<String, Object> capture() {
        return capture(null);
    }

    static Map<String, Object> capture(ExperimentEvidenceContext context) {
        Map<String, Object> values = new LinkedHashMap<String, Object>();
        values.put("schema", SCHEMA_V3);
        values.put("core", componentIdentity(SimulationRunner.class, CORE_GROUP_ID,
                CORE_ARTIFACT_ID, CORE_VERSION, null));
        values.put("study", context == null ? null : context.asMap());

        Map<String, Object> execution = new LinkedHashMap<String, Object>();
        String classPath = System.getProperty("java.class.path", "");
        execution.put("javaClassPathSha256", sha256(classPath.getBytes(StandardCharsets.UTF_8)));
        execution.put("workingDirectory", System.getProperty("user.dir"));
        Path moduleRoot = moduleRoot(SimulationRunner.class);
        Path reactorPom = moduleRoot == null ? null : moduleRoot.getParent().resolve("pom.xml");
        execution.put("reactorPomSha256", fingerprintOrNull(reactorPom));
        values.put("execution", execution);
        return values;
    }

    static Map<String, Object> componentIdentity(Class<?> anchor, String groupId, String artifactId,
            String version, String sourceSubpath) {
        if (anchor == null || isBlank(groupId) || isBlank(artifactId) || isBlank(version)) {
            throw new IllegalArgumentException("Component anchor and Maven coordinates are required");
        }
        Map<String, Object> values = new LinkedHashMap<String, Object>();
        values.put("groupId", groupId);
        values.put("artifactId", artifactId);
        values.put("version", version);
        values.put("anchorClass", anchor.getName());

        Path codeSourcePath = codeSourcePath(anchor);
        values.put("codeSource", codeSource(anchor));
        values.put("binarySha256", codeSourcePath != null && Files.isRegularFile(codeSourcePath)
                ? fingerprint(codeSourcePath) : null);

        Path moduleRoot = moduleRoot(anchor);
        values.put("modulePomSha256", moduleRoot == null ? null
                : fingerprintOrNull(moduleRoot.resolve("pom.xml")));
        Path sourceRoot = moduleRoot == null ? null : moduleRoot.resolve("src/main/java");
        String normalizedSubpath = normalizeSourceSubpath(sourceSubpath);
        if (sourceRoot != null && normalizedSubpath != null) {
            sourceRoot = sourceRoot.resolve(normalizedSubpath).normalize();
        }
        Map<String, Object> sourceTree = sourceTree(sourceRoot);
        values.put("sourceTreeSha256", sourceTree.get("sha256"));
        values.put("sourceFileCount", sourceTree.get("fileCount"));
        values.put("sourceTreeScope", sourceTreeScope(normalizedSubpath, sourceRoot));
        return values;
    }

    static Path moduleRoot(Class<?> anchor) {
        return moduleRootForCodeSource(codeSourcePath(anchor));
    }

    /**
     * 根据 Maven 构建目录中的类目录或已打包 JAR 推导模块根目录。
     *
     * <p>单元测试通常从 {@code target/classes} 加载核心类，而 Failsafe 或实际的已打包
     * 运行可能从 {@code target/*.jar} 加载。两种情形均有明确、无需依赖工作目录的模块
     * 边界。仓库外安装的 JAR 则不臆测其源码位置，返回 {@code null}。</p>
     */
    static Path moduleRootForCodeSource(Path location) {
        if (location == null || location.getFileName() == null) {
            return null;
        }
        Path normalized = location.toAbsolutePath().normalize();
        Path target;
        if (Files.isDirectory(normalized) && "classes".equals(normalized.getFileName().toString())) {
            target = normalized.getParent();
        } else if (Files.isRegularFile(normalized)
                && normalized.getFileName().toString().endsWith(".jar")) {
            target = normalized.getParent();
        } else {
            return null;
        }
        if (target == null || target.getFileName() == null || !"target".equals(target.getFileName().toString())) {
            return null;
        }
        Path root = target.getParent();
        return root != null && Files.isRegularFile(root.resolve("pom.xml")) ? root : null;
    }

    static String fingerprint(Path path) {
        if (path == null) {
            throw new IllegalArgumentException("Path is required");
        }
        try (InputStream input = Files.newInputStream(path)) {
            MessageDigest digest = digest();
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) != -1) {
                digest.update(buffer, 0, read);
            }
            return hex(digest.digest());
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to fingerprint " + path, exception);
        }
    }

    static String sha256(byte[] content) {
        return hex(digest().digest(content));
    }

    private static String fingerprintOrNull(Path path) {
        return path != null && Files.isRegularFile(path) ? fingerprint(path) : null;
    }

    private static Map<String, Object> sourceTree(Path root) {
        if (root == null || !Files.isDirectory(root)) {
            Map<String, Object> unavailable = new LinkedHashMap<String, Object>();
            unavailable.put("sha256", null);
            unavailable.put("fileCount", Integer.valueOf(0));
            return unavailable;
        }
        List<Path> files = new ArrayList<Path>();
        collectJavaFiles(root, files);
        Collections.sort(files);
        MessageDigest digest = digest();
        for (Path file : files) {
            String relative = root.relativize(file).toString().replace('\\', '/');
            digest.update(relative.getBytes(StandardCharsets.UTF_8));
            digest.update((byte) 0);
            digest.update(fingerprint(file).getBytes(StandardCharsets.UTF_8));
            digest.update((byte) '\n');
        }
        Map<String, Object> values = new LinkedHashMap<String, Object>();
        values.put("sha256", hex(digest.digest()));
        values.put("fileCount", Integer.valueOf(files.size()));
        return values;
    }

    private static void collectJavaFiles(Path directory, List<Path> results) {
        try (java.util.stream.Stream<Path> stream = Files.walk(directory)) {
            stream.filter(path -> Files.isRegularFile(path)
                    && path.getFileName().toString().endsWith(".java"))
                    .forEach(results::add);
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to fingerprint source tree " + directory, exception);
        }
    }

    private static String sourceTreeScope(String sourceSubpath, Path sourceRoot) {
        if (sourceRoot == null || !Files.isDirectory(sourceRoot)) {
            return "unavailable: class code source is not a Maven target/classes directory";
        }
        return sourceSubpath == null ? "src/main/java/**/*.java"
                : "src/main/java/" + sourceSubpath + "/**/*.java";
    }

    private static String normalizeSourceSubpath(String sourceSubpath) {
        if (sourceSubpath == null) {
            return null;
        }
        String normalized = sourceSubpath.replace('\\', '/').replaceAll("^/+|/+$", "");
        if (normalized.isEmpty() || normalized.contains("..") || normalized.startsWith("/")) {
            throw new IllegalArgumentException("Source subpath must be a non-empty relative path");
        }
        return normalized;
    }

    private static Path codeSourcePath(Class<?> anchor) {
        try {
            URL url = anchor.getProtectionDomain().getCodeSource().getLocation();
            return url == null ? null : java.nio.file.Paths.get(url.toURI()).toAbsolutePath().normalize();
        } catch (SecurityException | URISyntaxException | IllegalArgumentException exception) {
            return null;
        }
    }

    private static String codeSource(Class<?> anchor) {
        try {
            URL url = anchor.getProtectionDomain().getCodeSource().getLocation();
            return url == null ? null : url.toExternalForm();
        } catch (SecurityException exception) {
            return null;
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    private static MessageDigest digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("JDK does not provide SHA-256", exception);
        }
    }

    private static String hex(byte[] bytes) {
        StringBuilder result = new StringBuilder();
        for (byte value : bytes) {
            result.append(String.format(Locale.ROOT, "%02x", value & 0xff));
        }
        return result.toString();
    }
}
