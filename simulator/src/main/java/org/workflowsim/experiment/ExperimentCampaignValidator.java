package org.workflowsim.experiment;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashSet;
import java.util.Set;

/** 用于 v1 experiment-campaign 索引的只读完整性校验器。 */
public final class ExperimentCampaignValidator {

    static final String SCHEMA = "workflowsim-experiment-campaign-index-v1";

    private ExperimentCampaignValidator() {
    }

    /**
     * 校验 campaign 索引、其计划声明和所有被引用的证据工件。
     *
     * @param indexPath 待校验的 campaign 索引文件
     * @return 包含索引位置和运行数量的校验结果
     * @throws IOException 当索引、相对路径、证据工件或其交叉引用不一致时抛出
     * @throws IllegalArgumentException 当 {@code indexPath} 为空时抛出
     */
    public static ValidationResult validate(Path indexPath) throws IOException {
        if (indexPath == null) {
            throw new IllegalArgumentException("Campaign index path is required");
        }
        Path index = indexPath.toAbsolutePath().normalize();
        if (!Files.isRegularFile(index)) {
            throw new IOException("Campaign index does not exist: " + index);
        }
        JsonObject document = object(index, "campaign index");
        if (!SCHEMA.equals(requireString(document, "schema", "campaign index"))) {
            throw new IOException("Unsupported campaign index schema: " + document.get("schema"));
        }
        JsonObject plan = requireObject(document, "plan", "campaign index");
        if (!ExperimentPlan.SCHEMA.equals(requireString(plan, "schema", "campaign plan"))) {
            throw new IOException("Campaign index has unsupported plan schema");
        }
        requireString(plan, "id", "campaign plan");
        int planRunCount = requireInt(plan, "runCount", "campaign plan");
        int runCount = requireInt(document, "runCount", "campaign index");
        JsonArray runs = requireArray(document, "runs", "campaign index");
        if (planRunCount != runCount || runs.size() != runCount) {
            throw new IOException("Campaign index run count differs from plan or run records");
        }
        Path root = index.getParent();
        Set<String> uniqueRuns = new HashSet<String>();
        for (JsonElement element : runs) {
            if (!element.isJsonObject()) {
                throw new IOException("Campaign run entry must be an object");
            }
            validateRun(root, element.getAsJsonObject(), uniqueRuns);
        }
        return new ValidationResult(index, runCount);
    }

    /** 命令行校验入口；不会写入任何文件。 */
    public static void main(String[] args) throws Exception {
        if (args.length != 1) {
            throw new IllegalArgumentException("Usage: ExperimentCampaignValidator <experiment-campaign-index.json>");
        }
        ValidationResult result = validate(Paths.get(args[0]));
        System.out.println("EXPERIMENT_CAMPAIGN_VALIDATION PASSED index=" + result.getIndex()
                + " runs=" + result.getRunCount());
    }

    private static void validateRun(Path root, JsonObject record, Set<String> uniqueRuns)
            throws IOException {
        String cellId = requireString(record, "cellId", "campaign run");
        int replication = requireInt(record, "replicationIndex", "campaign run");
        long seed = requireLong(record, "seed", "campaign run");
        String unique = cellId + "\u0000" + replication;
        if (!uniqueRuns.add(unique)) {
            throw new IOException("Campaign index has duplicate cell/replication run: " + cellId + "/" + replication);
        }
        Path manifest = requiredRelativeFile(root,
                requireString(record, "manifest", "campaign run"), "manifest");
        Path metrics = requiredRelativeFile(root,
                requireString(record, "metrics", "campaign run"), "metrics");
        Path events = requiredRelativeFile(root,
                requireString(record, "events", "campaign run"), "events");
        ExperimentArtifactValidator.ValidationResult bundle = ExperimentArtifactValidator.validate(manifest);
        if (!metrics.equals(bundle.getMetrics()) || !events.equals(bundle.getEvents())) {
            throw new IOException("Campaign index sidecars disagree with manifest for " + cellId);
        }
        JsonObject manifestDocument = object(manifest, "referenced experiment manifest");
        JsonObject configuration = requireObject(manifestDocument, "configuration", "experiment manifest");
        if (seed != requireLong(configuration, "rootSeed", "manifest configuration")) {
            throw new IOException("Campaign index seed differs from manifest for " + cellId);
        }
        JsonObject result = requireObject(manifestDocument, "result", "experiment manifest");
        JsonObject profile = requireObject(record, "workflowProfile", "campaign run");
        JsonObject manifestProfile = requireObject(manifestDocument, "workflowProfile", "experiment manifest");
        if (requireInt(profile, "taskCount", "campaign workflow profile")
                != requireInt(manifestProfile, "taskCount", "manifest workflow profile")
                || requireInt(profile, "edgeCount", "campaign workflow profile")
                != requireInt(manifestProfile, "edgeCount", "manifest workflow profile")) {
            throw new IOException("Campaign index workflow profile differs from manifest for " + cellId);
        }
        if (Double.compare(requireFiniteNumber(record, "makespanSeconds", "campaign run"),
                requireFiniteNumber(result, "makespan", "manifest result")) != 0
                || requireInt(record, "totalJobs", "campaign run")
                != requireInt(result, "totalJobs", "manifest result")
                || requireInt(record, "successfulJobs", "campaign run")
                != requireInt(result, "successfulJobs", "manifest result")
                || requireInt(record, "failedJobs", "campaign run")
                != requireInt(result, "failedJobs", "manifest result")) {
            throw new IOException("Campaign index result differs from manifest for " + cellId);
        }
    }

    private static Path requiredRelativeFile(Path root, String relative, String role) throws IOException {
        Path path = root.resolve(relative).normalize();
        if (!path.startsWith(root) || !Files.isRegularFile(path)) {
            throw new IOException("Campaign index " + role + " path is invalid or missing: " + relative);
        }
        return path;
    }

    private static JsonObject object(Path path, String subject) throws IOException {
        try {
            return JsonParser.parseString(new String(Files.readAllBytes(path), StandardCharsets.UTF_8))
                    .getAsJsonObject();
        } catch (RuntimeException exception) {
            throw new IOException("Invalid JSON " + subject + ": " + path.getFileName(), exception);
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
        if (!primitive.isString() || primitive.getAsString().isEmpty()) {
            throw new IOException(subject + " has invalid string " + key);
        }
        return primitive.getAsString();
    }

    private static int requireInt(JsonObject object, String key, String subject) throws IOException {
        long value = requireLong(object, key, subject);
        if (value < Integer.MIN_VALUE || value > Integer.MAX_VALUE) {
            throw new IOException(subject + " has out-of-range integer " + key);
        }
        return (int) value;
    }

    private static long requireLong(JsonObject object, String key, String subject) throws IOException {
        if (!object.has(key)) {
            throw new IOException(subject + " is missing number " + key);
        }
        try {
            return object.get(key).getAsLong();
        } catch (RuntimeException exception) {
            throw new IOException(subject + " has invalid number " + key, exception);
        }
    }

    private static double requireFiniteNumber(JsonObject object, String key, String subject)
            throws IOException {
        if (!object.has(key) || !object.get(key).isJsonPrimitive()) {
            throw new IOException(subject + " is missing number " + key);
        }
        try {
            double value = object.get(key).getAsDouble();
            if (Double.isNaN(value) || Double.isInfinite(value)) {
                throw new IOException(subject + " has non-finite number " + key);
            }
            return value;
        } catch (RuntimeException exception) {
            throw new IOException(subject + " has invalid number " + key, exception);
        }
    }

    /** 供程序调用方使用的只读校验结果。 */
    public static final class ValidationResult {
        private final Path index;
        private final int runCount;

        private ValidationResult(Path index, int runCount) {
            this.index = index;
            this.runCount = runCount;
        }

        public Path getIndex() { return index; }
        public int getRunCount() { return runCount; }
    }
}
