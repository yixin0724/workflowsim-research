package org.workflowsim.experiment;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;

/**
 * 只读校验实验 manifest 与 metrics/events sidecar 的跨文件完整性契约。
 *
 * <p>校验器持续接受迁移前产生的 v2 工件。v3 在相同 sidecar 完整性约束外，还要求
 * provenance 清楚区分核心模拟器和可选的 reference/study driver 身份。</p>
 */
public final class ExperimentArtifactValidator {

    /** 迁移前 evidence manifest 的只读兼容 schema。 */
    public static final String MANIFEST_SCHEMA_V2 = "workflowsim-experiment-manifest-v2";
    /** 当前 evidence manifest schema。 */
    public static final String MANIFEST_SCHEMA_V3 = ExperimentManifestWriter.SCHEMA_V3;
    /** 完整配置快照的当前 schema。 */
    public static final String MANIFEST_SCHEMA_V4 = ExperimentManifestWriter.SCHEMA_V4;

    private ExperimentArtifactValidator() {
    }

    /**
     * 只读校验 manifest 与 metrics/events sidecar 的 schema、路径、哈希、大小、事件序列，
     * 以及 v3 provenance 的结构化身份契约。
     *
     * @param manifestPath 待校验的 v2 或 v3 manifest 路径
     * @return 包含已验证工件路径和事件数量的校验结果
     * @throws IOException 当工件不存在、格式不合法或跨文件内容不一致时抛出
     * @throws IllegalArgumentException 当 {@code manifestPath} 为空时抛出
     */
    public static ValidationResult validate(Path manifestPath) throws IOException {
        if (manifestPath == null) {
            throw new IllegalArgumentException("Manifest path is required");
        }
        Path manifest = manifestPath.toAbsolutePath().normalize();
        if (!Files.isRegularFile(manifest)) {
            throw new IOException("Experiment manifest does not exist: " + manifest);
        }
        JsonObject root = object(Files.readAllBytes(manifest), "experiment manifest");
        String schema = requireString(root, "schema", "experiment manifest");
        if (!MANIFEST_SCHEMA_V2.equals(schema) && !MANIFEST_SCHEMA_V3.equals(schema)
                && !MANIFEST_SCHEMA_V4.equals(schema)) {
            throw new IOException("Unsupported experiment manifest schema: " + schema);
        }
        validateManifestTopLevelShape(root);
        if (MANIFEST_SCHEMA_V3.equals(schema) || MANIFEST_SCHEMA_V4.equals(schema)) {
            validateV3Provenance(requireObject(root, "provenance", "experiment manifest"));
        }
        if (MANIFEST_SCHEMA_V4.equals(schema)) {
            ManifestV4Validator.validate(root);
        }
        JsonObject eventSummary = requireObject(root, "events", "experiment manifest");
        int expectedEvents = requireInt(eventSummary, "eventCount", "manifest events");
        JsonObject manifestMetrics = requireObject(root, "metrics", "experiment manifest");
        JsonArray artifacts = requireArray(root, "artifacts", "experiment manifest");
        Map<String, Path> paths = validateReferences(manifest.getParent(), artifacts);
        Path metrics = required(paths, "metrics");
        Path events = required(paths, "events");
        JsonObject sidecarMetrics = validateMetrics(metrics);
        if (!manifestMetrics.equals(sidecarMetrics)) {
            throw new IOException("Manifest metrics do not match the metrics sidecar");
        }
        int actualEvents = validateEvents(events);
        if (actualEvents != expectedEvents) {
            throw new IOException("Event count mismatch: manifest declares " + expectedEvents
                    + " but JSONL contains " + actualEvents);
        }
        return new ValidationResult(manifest, metrics, events, actualEvents);
    }

    private static void validateManifestTopLevelShape(JsonObject root) throws IOException {
        requireObject(root, "configuration", "experiment manifest");
        requireObject(root, "platform", "experiment manifest");
        requireArray(root, "inputs", "experiment manifest");
        requireObject(root, "workflowProfile", "experiment manifest");
        requireObject(root, "result", "experiment manifest");
        requireObject(root, "metrics", "experiment manifest");
        requireObject(root, "events", "experiment manifest");
        requireArray(root, "artifacts", "experiment manifest");
        requireObject(root, "provenance", "experiment manifest");
        requireObject(root, "runtime", "experiment manifest");
    }

    /** 命令行校验入口；不会写入任何文件。 */
    public static void main(String[] args) throws Exception {
        if (args.length != 1) {
            throw new IllegalArgumentException("Usage: ExperimentArtifactValidator <manifest.json>");
        }
        ValidationResult result = validate(Paths.get(args[0]));
        System.out.println("EXPERIMENT_ARTIFACT_VALIDATION PASSED manifest=" + result.getManifest()
                + " events=" + result.getEventCount());
    }

    private static void validateV3Provenance(JsonObject provenance) throws IOException {
        if (!ExperimentProvenance.SCHEMA_V3.equals(requireString(provenance, "schema", "provenance"))) {
            throw new IOException("Unsupported provenance schema");
        }
        validateComponent(requireObject(provenance, "core", "provenance"), "provenance core");
        if (!provenance.has("study")) {
            throw new IOException("provenance is missing explicit study field");
        }
        JsonElement study = provenance.get("study");
        if (!study.isJsonNull()) {
            if (!study.isJsonObject()) {
                throw new IOException("provenance study must be an object or null");
            }
            validateStudy(study.getAsJsonObject());
        }
        JsonObject execution = requireObject(provenance, "execution", "provenance");
        requireSha256(execution, "javaClassPathSha256", "provenance execution");
        requireString(execution, "workingDirectory", "provenance execution");
        requireNullableSha256(execution, "reactorPomSha256", "provenance execution");
    }

    private static void validateStudy(JsonObject study) throws IOException {
        requireString(study, "id", "provenance study");
        validateComponent(requireObject(study, "component", "provenance study"),
                "provenance study component");

        JsonObject protocol = requireObject(study, "protocol", "provenance study");
        requireString(protocol, "logicalId", "provenance study protocol");
        boolean protocolAvailable = requireBoolean(protocol, "available", "provenance study protocol");
        String protocolSha256 = requireNullableSha256(protocol, "sha256", "provenance study protocol");
        if (protocolAvailable != (protocolSha256 != null)) {
            throw new IOException("provenance study protocol available/sha256 fields disagree");
        }

        JsonObject dataset = requireObject(study, "dataset", "provenance study");
        String datasetRoot = requireString(dataset, "root", "provenance study dataset");
        if (!isAbsolutePath(datasetRoot)) {
            throw new IOException("provenance study dataset root is not absolute: " + datasetRoot);
        }
        if (!"EXPLICIT_ABSOLUTE_DATASET_ROOT".equals(requireString(dataset,
                "resolutionPolicy", "provenance study dataset"))) {
            throw new IOException("provenance study dataset has an unsupported resolution policy");
        }
    }

    private static void validateComponent(JsonObject component, String subject) throws IOException {
        requireString(component, "groupId", subject);
        requireString(component, "artifactId", subject);
        requireString(component, "version", subject);
        requireString(component, "anchorClass", subject);
        requireNullableString(component, "codeSource", subject);
        requireNullableSha256(component, "binarySha256", subject);
        requireNullableSha256(component, "modulePomSha256", subject);
        requireNullableSha256(component, "sourceTreeSha256", subject);
        requireNonNegativeInt(component, "sourceFileCount", subject);
        requireString(component, "sourceTreeScope", subject);
    }

    private static Map<String, Path> validateReferences(Path directory, JsonArray artifacts)
            throws IOException {
        Map<String, Path> result = new HashMap<String, Path>();
        for (JsonElement element : artifacts) {
            if (!element.isJsonObject()) {
                throw new IOException("Manifest artifact entry must be an object");
            }
            JsonObject item = element.getAsJsonObject();
            String role = requireString(item, "role", "manifest artifact");
            String relativePath = requireString(item, "path", "manifest artifact");
            Path rawPath;
            try {
                rawPath = Paths.get(relativePath);
            } catch (InvalidPathException exception) {
                throw new IOException("Manifest artifact path is invalid: " + relativePath, exception);
            }
            if (rawPath.isAbsolute() || rawPath.getNameCount() != 1) {
                throw new IOException("Manifest artifact path is invalid; it must be a file name: "
                        + relativePath);
            }
            Path resolved = directory.resolve(rawPath).normalize();
            if (!resolved.getParent().equals(directory) || !Files.isRegularFile(resolved)) {
                throw new IOException("Manifest artifact path is invalid or missing: " + relativePath);
            }
            if (!requireSha256(item, "sha256", "manifest artifact")
                    .equals(ExperimentProvenance.fingerprint(resolved))) {
                throw new IOException("SHA-256 mismatch for manifest artifact " + relativePath);
            }
            long expectedSize = requireLong(item, "sizeBytes", "manifest artifact");
            if (expectedSize < 0L || Files.size(resolved) != expectedSize) {
                throw new IOException("Size mismatch for manifest artifact " + relativePath);
            }
            if (result.put(role, resolved) != null) {
                throw new IOException("Duplicate manifest artifact role: " + role);
            }
        }
        return result;
    }

    /** v2 契约要求存在的指标字段（含 v1→v2 重命名与新增字段）。 */
    private static final String[] REQUIRED_METRIC_FIELDS = {
        "makespanSeconds",
        "meanJobVmQueueWaitingTimeSeconds",
        "meanJobVmLevelSlowdown",
        "meanJobResponseTimeSeconds",
        "meanComputeTotalWaitingTimeSeconds",
        "meanComputeTrueSlowdown",
    };

    private static JsonObject validateMetrics(Path path) throws IOException {
        JsonObject document = object(Files.readAllBytes(path), "metrics sidecar");
        if (!document.has("schema") || !document.has("metrics")
                || !document.get("metrics").isJsonObject()) {
            throw new IOException("Invalid metrics sidecar: " + path.getFileName());
        }
        String schema = document.get("schema").getAsString();
        if (!"workflowsim-simulation-metrics-v2".equals(schema)) {
            throw new IOException("Unsupported metrics schema \"" + schema
                    + "\" (expected workflowsim-simulation-metrics-v2): " + path.getFileName());
        }
        JsonObject metrics = document.getAsJsonObject("metrics");
        for (String field : REQUIRED_METRIC_FIELDS) {
            if (!metrics.has(field)) {
                throw new IOException("Metrics sidecar is missing required field \"" + field
                        + "\" (schema v2): " + path.getFileName());
            }
        }
        return metrics;
    }

    private static int validateEvents(Path path) throws IOException {
        int count = 0;
        try (BufferedReader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.trim().isEmpty()) {
                    throw new IOException("Events JSONL contains a blank line: " + path.getFileName());
                }
                JsonObject event;
                try {
                    event = JsonParser.parseString(line).getAsJsonObject();
                } catch (RuntimeException exception) {
                    throw new IOException("Invalid JSONL event " + count + " in " + path.getFileName(), exception);
                }
                long sequence = requireLong(event, "sequence", "JSONL event");
                if (sequence != count) {
                    throw new IOException("JSONL event sequence mismatch at line " + (count + 1)
                            + ": expected " + count + " but found " + sequence);
                }
                count++;
            }
        }
        return count;
    }

    private static Path required(Map<String, Path> paths, String role) throws IOException {
        Path value = paths.get(role);
        if (value == null) {
            throw new IOException("Manifest is missing required artifact role: " + role);
        }
        return value;
    }

    private static JsonObject object(byte[] bytes, String subject) throws IOException {
        try {
            return JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8)).getAsJsonObject();
        } catch (RuntimeException exception) {
            throw new IOException("Invalid JSON " + subject, exception);
        }
    }

    private static JsonObject requireObject(JsonObject object, String key, String subject) throws IOException {
        if (!object.has(key) || !object.get(key).isJsonObject()) {
            throw new IOException(subject + " is missing object " + key);
        }
        return object.get(key).getAsJsonObject();
    }

    private static JsonArray requireArray(JsonObject object, String key, String subject) throws IOException {
        if (!object.has(key) || !object.get(key).isJsonArray()) {
            throw new IOException(subject + " is missing array " + key);
        }
        return object.get(key).getAsJsonArray();
    }

    private static String requireString(JsonObject object, String key, String subject) throws IOException {
        if (!object.has(key) || !object.get(key).isJsonPrimitive()) {
            throw new IOException(subject + " is missing string " + key);
        }
        JsonPrimitive primitive = object.get(key).getAsJsonPrimitive();
        if (!primitive.isString() || primitive.getAsString().trim().isEmpty()) {
            throw new IOException(subject + " has invalid string " + key);
        }
        return primitive.getAsString();
    }

    private static String requireNullableString(JsonObject object, String key, String subject)
            throws IOException {
        if (!object.has(key)) {
            throw new IOException(subject + " is missing nullable string " + key);
        }
        JsonElement element = object.get(key);
        if (element.isJsonNull()) {
            return null;
        }
        if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isString()
                || element.getAsString().trim().isEmpty()) {
            throw new IOException(subject + " has invalid nullable string " + key);
        }
        return element.getAsString();
    }

    private static boolean requireBoolean(JsonObject object, String key, String subject)
            throws IOException {
        if (!object.has(key) || !object.get(key).isJsonPrimitive()
                || !object.get(key).getAsJsonPrimitive().isBoolean()) {
            throw new IOException(subject + " has invalid boolean " + key);
        }
        return object.get(key).getAsBoolean();
    }

    private static int requireNonNegativeInt(JsonObject object, String key, String subject)
            throws IOException {
        int value = requireInt(object, key, subject);
        if (value < 0) {
            throw new IOException(subject + " has negative integer " + key);
        }
        return value;
    }

    private static int requireInt(JsonObject object, String key, String subject) throws IOException {
        long value = requireLong(object, key, subject);
        if (value > Integer.MAX_VALUE || value < Integer.MIN_VALUE) {
            throw new IOException(subject + " has out-of-range integer " + key);
        }
        return (int) value;
    }

    private static long requireLong(JsonObject object, String key, String subject) throws IOException {
        if (!object.has(key) || !object.get(key).isJsonPrimitive()
                || !object.get(key).getAsJsonPrimitive().isNumber()) {
            throw new IOException(subject + " is missing number " + key);
        }
        try {
            return object.get(key).getAsLong();
        } catch (RuntimeException exception) {
            throw new IOException(subject + " has invalid number " + key, exception);
        }
    }

    private static String requireSha256(JsonObject object, String key, String subject) throws IOException {
        String value = requireString(object, key, subject);
        if (!value.matches("[0-9a-f]{64}")) {
            throw new IOException(subject + " has invalid SHA-256 " + key);
        }
        return value;
    }

    private static String requireNullableSha256(JsonObject object, String key, String subject)
            throws IOException {
        String value = requireNullableString(object, key, subject);
        if (value != null && !value.matches("[0-9a-f]{64}")) {
            throw new IOException(subject + " has invalid nullable SHA-256 " + key);
        }
        return value;
    }

    private static boolean isAbsolutePath(String value) {
        try {
            return Paths.get(value).isAbsolute();
        } catch (InvalidPathException exception) {
            return false;
        }
    }

    /** 供程序调用方使用的只读校验结果。 */
    public static final class ValidationResult {
        private final Path manifest;
        private final Path metrics;
        private final Path events;
        private final int eventCount;

        private ValidationResult(Path manifest, Path metrics, Path events, int eventCount) {
            this.manifest = manifest;
            this.metrics = metrics;
            this.events = events;
            this.eventCount = eventCount;
        }

        public Path getManifest() { return manifest; }
        public Path getMetrics() { return metrics; }
        public Path getEvents() { return events; }
        public int getEventCount() { return eventCount; }
    }
}
