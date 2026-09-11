package org.workflowsim.experiment;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 一次正式 reference 或 study 运行附加的显式研究身份。
 *
 * <p>核心模拟器不依赖任何具体实验模块；实验调用方通过本类型把自身 driver、协议和
 * 数据集根信息交给通用工件 writer。未提供本上下文的单次运行仍可写出 v3 manifest，
 * 但其中 {@code study} 为 {@code null}，不能被误解释为完整研究证据。</p>
 */
public final class ExperimentEvidenceContext {

    private final String studyId;
    private final String groupId;
    private final String artifactId;
    private final String version;
    private final Class<?> driverClass;
    private final String sourceSubpath;
    private final String protocolId;
    private final Path protocolFile;
    private final Path datasetRoot;

    private ExperimentEvidenceContext(Builder builder) {
        this.studyId = builder.studyId;
        this.groupId = builder.groupId;
        this.artifactId = builder.artifactId;
        this.version = builder.version;
        this.driverClass = builder.driverClass;
        this.sourceSubpath = builder.sourceSubpath;
        this.protocolId = builder.protocolId;
        this.protocolFile = builder.protocolFile;
        this.datasetRoot = builder.datasetRoot;
    }

    /** 创建 reference 或 study 身份构建器。 */
    public static Builder builder(String studyId) {
        return new Builder(studyId);
    }

    /** 返回供 manifest/index 写入的独立、机器可读身份对象。 */
    public Map<String, Object> asMap() {
        Map<String, Object> values = new LinkedHashMap<String, Object>();
        values.put("id", studyId);
        values.put("component", ExperimentProvenance.componentIdentity(driverClass, groupId,
                artifactId, version, sourceSubpath));

        boolean protocolAvailable = protocolFile != null && Files.isRegularFile(protocolFile);
        Map<String, Object> protocol = new LinkedHashMap<String, Object>();
        protocol.put("logicalId", protocolId);
        protocol.put("sha256", protocolAvailable ? ExperimentProvenance.fingerprint(protocolFile) : null);
        protocol.put("available", Boolean.valueOf(protocolAvailable));
        values.put("protocol", protocol);

        Map<String, Object> data = new LinkedHashMap<String, Object>();
        data.put("root", datasetRoot.toString());
        data.put("resolutionPolicy", "EXPLICIT_ABSOLUTE_DATASET_ROOT");
        values.put("dataset", data);
        return values;
    }

    /** 构造不可变研究身份的校验式 builder。 */
    public static final class Builder {
        private final String studyId;
        private String groupId;
        private String artifactId;
        private String version;
        private Class<?> driverClass;
        private String sourceSubpath;
        private String protocolId;
        private Path protocolFile;
        private Path datasetRoot;

        private Builder(String studyId) {
            this.studyId = requireText(studyId, "Study ID");
        }

        /** 声明执行 driver 所属 Maven 构件。 */
        public Builder artifact(String groupId, String artifactId, String version) {
            this.groupId = requireText(groupId, "Study groupId");
            this.artifactId = requireText(artifactId, "Study artifactId");
            this.version = requireText(version, "Study version");
            return this;
        }

        /** 声明执行当前研究入口的类与其源码子树。 */
        public Builder driver(Class<?> driverClass, String sourceSubpath) {
            if (driverClass == null) {
                throw new IllegalArgumentException("Study driver class is required");
            }
            this.driverClass = driverClass;
            this.sourceSubpath = requireText(sourceSubpath, "Study source subpath");
            return this;
        }

        /** 声明人可读协议标识与可选的本地协议文件。 */
        public Builder protocol(String protocolId, Path protocolFile) {
            this.protocolId = requireText(protocolId, "Protocol ID");
            this.protocolFile = protocolFile == null ? null
                    : protocolFile.toAbsolutePath().normalize();
            return this;
        }

        /** 声明研究入口解析输入时使用的绝对数据集根。 */
        public Builder datasetRoot(Path datasetRoot) {
            if (datasetRoot == null) {
                throw new IllegalArgumentException("Dataset root is required");
            }
            if (!datasetRoot.isAbsolute()) {
                throw new IllegalArgumentException("Dataset root must be absolute: " + datasetRoot);
            }
            Path normalized = datasetRoot.normalize();
            if (!Files.isDirectory(normalized)) {
                throw new IllegalArgumentException("Dataset root is not a directory: " + normalized);
            }
            this.datasetRoot = normalized;
            return this;
        }

        /** 校验全部研究身份字段并生成上下文。 */
        public ExperimentEvidenceContext build() {
            if (groupId == null || artifactId == null || version == null || driverClass == null
                    || sourceSubpath == null || protocolId == null || datasetRoot == null) {
                throw new IllegalStateException("Study artifact, driver, protocol, and dataset root are required");
            }
            return new ExperimentEvidenceContext(this);
        }

        private static String requireText(String value, String label) {
            if (value == null || value.trim().isEmpty()) {
                throw new IllegalArgumentException(label + " is required");
            }
            return value.trim();
        }
    }
}
