package org.workflowsim.experiments.rerun;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonSyntaxException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.workflowsim.experiment.ExperimentArtifactValidator;

/**
 * 阶段 1：读取单个 run 目录的证据三件套并做结构校验（见契约“执行流程”第一步）。
 *
 * <p>定位规则：优先使用 {@code result.manifest.json}；不存在时接受目录内唯一的
 * {@code *.manifest.json}，多个或缺失都按 {@code EVIDENCE_INVALID} 拒绝。schema
 * 预检在结构校验之前：v2/v3 是历史可读格式但不支持 rerun，明确拒绝并说明原因；
 * 结构、sidecar 哈希与事件计数校验委托现有
 * {@link ExperimentArtifactValidator#validate(Path)}，与本包无关的校验规则保持
 * 单一来源。</p>
 */
public final class RerunEvidenceReader {

    /** 标准写入约定（runId 为 result）下的 manifest 文件名。 */
    static final String DEFAULT_MANIFEST_NAME = "result.manifest.json";

    private static final String SCHEMA_V4 = "workflowsim-experiment-manifest-v4";
    private static final String SCHEMA_V3 = "workflowsim-experiment-manifest-v3";
    private static final String SCHEMA_V2 = "workflowsim-experiment-manifest-v2";

    private RerunEvidenceReader() {
    }

    /**
     * 读取并校验一个 run 目录的证据包。
     *
     * @param runDirectory 单个 run 的证据目录
     * @return 通过全部校验的证据快照
     * @throws RerunFailureException 当目录/manifest 不存在、schema 不是 v4 或
     *         三件套结构校验失败时（verdict 为 {@code EVIDENCE_INVALID}）
     * @throws IOException 当文件系统读取失败时（非契约失败）
     * @throws IllegalArgumentException 当 runDirectory 为 null 时
     */
    public static RerunEvidence read(Path runDirectory) throws RerunFailureException, IOException {
        if (runDirectory == null) {
            throw new IllegalArgumentException("Run directory is required");
        }
        Path directory = runDirectory.toAbsolutePath().normalize();
        if (!Files.isDirectory(directory)) {
            throw fail("Run directory does not exist: " + directory);
        }
        Path manifest = locateManifest(directory);
        JsonObject root = parseManifest(manifest);
        String schema = schemaOf(root);
        if (!SCHEMA_V4.equals(schema)) {
            if (SCHEMA_V3.equals(schema) || SCHEMA_V2.equals(schema)) {
                throw fail("Rerun only supports manifest v4 (" + SCHEMA_V4 + "); found "
                        + schema + ". Historical v2/v3 evidence remains readable but cannot"
                        + " be rerun.");
            }
            throw fail("Unsupported manifest schema: " + schema);
        }
        ExperimentArtifactValidator.ValidationResult validated;
        try {
            validated = ExperimentArtifactValidator.validate(manifest);
        } catch (IOException e) {
            throw fail("Evidence structure validation failed: " + e.getMessage(), e);
        } catch (IllegalArgumentException e) {
            throw fail("Evidence structure validation failed: " + e.getMessage(), e);
        }
        return new RerunEvidence(directory, validated.getManifest(), validated.getMetrics(),
                validated.getEvents(), validated.getEventCount(), root);
    }

    private static Path locateManifest(Path directory) throws RerunFailureException, IOException {
        Path standard = directory.resolve(DEFAULT_MANIFEST_NAME);
        if (Files.isRegularFile(standard)) {
            return standard;
        }
        List<Path> candidates = new ArrayList<Path>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(directory, "*.manifest.json")) {
            for (Path candidate : stream) {
                candidates.add(candidate);
            }
        }
        if (candidates.size() == 1) {
            return candidates.get(0);
        }
        if (candidates.isEmpty()) {
            throw fail("No manifest found in run directory: " + directory
                    + " (expected " + DEFAULT_MANIFEST_NAME + ")");
        }
        throw fail("Ambiguous run directory: multiple manifests found",
                pathsAsStrings(candidates));
    }

    private static JsonObject parseManifest(Path manifest) throws RerunFailureException, IOException {
        byte[] bytes = Files.readAllBytes(manifest);
        try {
            return JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8)).getAsJsonObject();
        } catch (JsonSyntaxException | IllegalStateException e) {
            throw fail("Manifest is not a JSON object: " + manifest, e);
        }
    }

    private static String schemaOf(JsonObject root) throws RerunFailureException {
        if (!root.has("schema") || !root.get("schema").isJsonPrimitive()) {
            throw fail("Manifest is missing the schema field");
        }
        return root.get("schema").getAsString();
    }

    private static List<String> pathsAsStrings(List<Path> paths) {
        List<String> values = new ArrayList<String>();
        for (Path path : paths) {
            values.add(path.toString());
        }
        return values;
    }

    private static RerunFailureException fail(String message) {
        return new RerunFailureException(RerunVerdict.EVIDENCE_INVALID, message);
    }

    private static RerunFailureException fail(String message, Throwable cause) {
        return new RerunFailureException(RerunVerdict.EVIDENCE_INVALID, message, cause);
    }

    private static RerunFailureException fail(String message, List<String> details) {
        return new RerunFailureException(RerunVerdict.EVIDENCE_INVALID, message, details);
    }
}
