package org.workflowsim.experiments.network;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.reflect.TypeToken;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.workflowsim.experiment.ExperimentArtifactValidator;
import org.workflowsim.experiment.ExperimentManifestWriter;
import org.workflowsim.experiments.common.ExactJsonValues;
import org.workflowsim.platform.PlatformProfile;
import org.workflowsim.utils.Parameters.PlanningAlgorithm;
import org.workflowsim.utils.SimulationConfig;

/** Checks a registered protocol and its evidence; integrity validation is not a current-code rerun. */
public final class NetworkStudyValidator {
    private static final Gson JSON = new GsonBuilder().serializeNulls().create();
    private NetworkStudyValidator() { }

    public static void main(String[] args) throws Exception {
        if (args.length != 1) { throw new IllegalArgumentException("Usage: NetworkStudyValidator <network-study.json>"); }
        Path index = Paths.get(args[0]);
        int count = validate(index);
        JsonObject plan = read(index).getAsJsonObject("plan");
        NetworkStudyPlan registered = NetworkStudyPlan.canonical(text(plan, "mode"), text(plan, "protocol"));
        System.out.println("NETWORK_STUDY_VALIDATION PASSED runs=" + count
                + " protocol=" + registered.getProtocol() + " mode=" + text(plan, "mode")
                + " scope=" + (registered.isHistoricalProtocol() ? "HISTORICAL_PROTOCOL_INTEGRITY" : "DECLARED_PROTOCOL_INTEGRITY")
                + ";NOT_A_CURRENT_CODE_RERUN");
    }

    public static int validate(Path indexPath) throws IOException {
        try {
            Path root = indexPath.toAbsolutePath().normalize().getParent();
            Path realRoot = root.toRealPath();
            JsonObject index = read(indexPath);
            if (!"workflowsim-network-study-v1".equals(text(index, "schema"))) {
                throw new IOException("Unsupported network study schema");
            }
            JsonObject plan = index.getAsJsonObject("plan");
            Path protocolPath = root.resolve("protocol.json");
            if (!protocolPath.toRealPath().startsWith(realRoot)) { throw new IOException("Invalid protocol path"); }
            byte[] protocolBytes = Files.readAllBytes(protocolPath);
            JsonObject retained = JsonParser.parseString(new String(protocolBytes, StandardCharsets.UTF_8)).getAsJsonObject();
            if (!ExactJsonValues.equal(plan, retained)) { throw new IOException("Study plan differs from retained protocol"); }
            NetworkStudyPlan registered = registeredPlan(plan);
            String protocolSha256 = sha256(protocolBytes);
            Set<String> expected = expectedCells(registered);
            Map<String, JsonObject> workflows = new LinkedHashMap<String, JsonObject>();
            Map<String, NetworkStudyPlan.WorkflowCase> logicalInputs = new LinkedHashMap<String, NetworkStudyPlan.WorkflowCase>();
            for (JsonElement value : plan.getAsJsonArray("workflows")) {
                JsonObject workflow = value.getAsJsonObject();
                workflows.put(text(workflow, "id"), workflow);
            }
            for (NetworkStudyPlan.WorkflowCase workflow : registered.getWorkflows()) { logicalInputs.put(workflow.id, workflow); }
            boolean heterogeneityAxis = registered.getVariant() == NetworkStudyPlan.StudyVariant.SENSITIVITY_R13;
            String datasetIdentity = null;
            for (JsonElement element : index.getAsJsonArray("runs")) {
                JsonObject run = element.getAsJsonObject();
                String workflowId = text(run, "workflowId");
                int count = integer(run, "vmCount");
                String network = text(run, "network");
                String planner = text(run, "planner");
                long seed = wholeNumber(run, "seed");
                if (heterogeneityAxis != run.has("heterogeneity")) { throw new IOException("Unexpected heterogeneity axis"); }
                String heterogeneity = heterogeneityAxis ? text(run, "heterogeneity") : NetworkStudyPlan.HOMOGENEOUS;
                String cell = key(workflowId, count, network, heterogeneity, planner, seed);
                if (!expected.remove(cell)) { throw new IOException("Duplicate or undeclared study cell: " + cell); }
                String runId = workflowId + "-v" + count + "-" + network
                        + (heterogeneityAxis ? "-" + heterogeneity : "") + "-" + planner + "-s" + seed;
                if (!runId.equals(text(run, "runId"))) { throw new IOException("Study runId differs from cell: " + cell); }
                JsonObject workflow = workflows.get(workflowId);
                if (!text(workflow, "family").equals(text(run, "family"))
                        || !text(workflow, "population").equals(text(run, "population"))) {
                    throw new IOException("Study workflow population/family mismatch: " + cell);
                }
                if (!"COMPLETED_SUCCESSFULLY".equals(text(run, "status"))) {
                    throw new IOException("Study contains failed run: " + cell);
                }
                String relative = text(run, "manifest");
                Path manifest = root.resolve(relative).normalize();
                if (Paths.get(relative).isAbsolute() || !manifest.startsWith(root)
                        || !manifest.toRealPath().startsWith(realRoot)) { throw new IOException("Invalid study evidence path"); }
                ExperimentArtifactValidator.validate(manifest);
                JsonObject evidence = read(manifest);
                if (!"workflowsim-experiment-manifest-v4".equals(text(evidence, "schema"))) {
                    throw new IOException("Registered network studies require manifest v4");
                }
                String datasetRoot = validateProvenance(evidence, registered, protocolSha256);
                if (datasetIdentity != null && !datasetIdentity.equals(datasetRoot)) {
                    throw new IOException("Study runs disagree on their declared dataset root");
                }
                datasetIdentity = datasetRoot;
                NetworkStudyPlan.WorkflowCase logicalInput = logicalInputs.get(workflowId);
                if ("CLASSIC_DAX".equals(logicalInput.population)) {
                    String expectedPath = portable(datasetRoot).replaceAll("/+$", "") + "/" + portable(logicalInput.path.toString());
                    if (!expectedPath.equals(portable(text(workflow, "path")))) {
                        throw new IOException("Input path differs from the declared dataset root: " + cell);
                    }
                }
                validateConditions(evidence, registered, text(workflow, "path"), count, network,
                        heterogeneity, PlanningAlgorithm.valueOf(planner), seed, cell);
                if (evidence.getAsJsonArray("inputs").size() != 1) { throw new IOException("Study requires exactly one input"); }
                JsonObject input = evidence.getAsJsonArray("inputs").get(0).getAsJsonObject();
                if (!text(input, "sha256").equals(text(workflow, "sha256"))
                        || !text(input, "sha256").equals(text(run, "inputSha256"))) {
                    throw new IOException("Study input fingerprint differs from protocol: " + cell);
                }
                if (!text(input, "path").equals(text(workflow, "path"))
                        || integer(input, "taskCount") != integer(run, "taskCount")) {
                    throw new IOException("Input identity mismatch: " + cell);
                }
                JsonObject result = evidence.getAsJsonObject("result");
                JsonObject metrics = evidence.getAsJsonObject("metrics");
                if (!"COMPLETED_SUCCESSFULLY".equals(text(result, "logicalTaskCompletionStatus"))
                        || !flag(result, "workflowCompletedSuccessfully")
                        || !"COMPLETED_SUCCESSFULLY".equals(text(metrics, "logicalTaskCompletionStatus"))
                        || !flag(metrics, "allLogicalTasksCompletedSuccessfully")) {
                    throw new IOException("Study evidence is incomplete despite its index status: " + cell);
                }
                match(run, "makespanSeconds", metrics, "makespanSeconds");
                match(run, "logicalCompletionSeconds", metrics, "logicalTaskCompletionSeconds");
                match(run, "meanWaitingSeconds", metrics, "meanComputeTotalWaitingTimeSeconds");
                match(run, "p95WaitingSeconds", metrics, "p95ComputeTotalWaitingTimeSeconds");
                match(run, "meanVmUtilization", metrics, "meanVmModeledIntervalUtilization");
            }
            if (!expected.isEmpty()) { throw new IOException("Study is missing " + expected.size() + " planned cells"); }
            List<Map<String, Object>> runs = JSON.fromJson(index.get("runs"), new TypeToken<List<Map<String, Object>>>() { }.getType());
            if (!ExactJsonValues.equal(JSON.toJsonTree(NetworkStudySummary.summarize(runs)), index.get("summary"))) {
                throw new IOException("Study summary differs from independent recomputation");
            }
            return runs.size();
        } catch (RuntimeException exception) {
            throw new IOException("Malformed network study: " + exception.getMessage(), exception);
        }
    }

    /** Registered declarations contain no filesystem state, so archived inputs need not be present. */
    private static NetworkStudyPlan registeredPlan(JsonObject plan) throws IOException {
        NetworkStudyPlan registered = NetworkStudyPlan.canonical(text(plan, "mode"), text(plan, "protocol"));
        JsonObject expected = JSON.toJsonTree(registered.asMap()).getAsJsonObject();
        bindRetainedPaths(expected.getAsJsonArray("workflows"), plan.getAsJsonArray("workflows"));
        bindRetainedPaths(expected.getAsJsonArray("excludedInputs"), plan.getAsJsonArray("excludedInputs"));
        if (!ExactJsonValues.equal(expected, plan)) {
            throw new IOException("Study declaration differs from registered " + registered.getProtocol()
                    + " " + text(plan, "mode") + " matrix or fixed parameters");
        }
        return registered;
    }

    private static void bindRetainedPaths(JsonArray expected, JsonArray retained) throws IOException {
        if (retained == null || expected.size() != retained.size()) { throw new IOException("Registered workload catalogue is incomplete"); }
        for (int i = 0; i < expected.size(); i++) {
            JsonObject specification = expected.get(i).getAsJsonObject();
            String logical = portable(text(specification, "path"));
            String recorded = text(retained.get(i).getAsJsonObject(), "path");
            String normalized = portable(recorded);
            if (!(normalized.startsWith("/") || normalized.matches("[A-Za-z]:/.*"))
                    || !normalized.endsWith("/" + logical)) {
                throw new IOException("Recorded workload path does not match registered logical input: " + logical);
            }
            specification.addProperty("path", recorded);
        }
    }

    private static Set<String> expectedCells(NetworkStudyPlan registered) {
        Set<String> expected = new HashSet<String>();
        for (NetworkStudyPlan.WorkflowCase workflow : registered.getWorkflows()) {
            for (NetworkStudyPlan.Condition condition : registered.getConditions()) {
                for (PlanningAlgorithm planner : registered.getPlanners()) {
                    for (long seed : registered.seeds(planner)) {
                        expected.add(key(workflow.id, condition.vmCount, condition.network,
                                condition.heterogeneity, planner.name(), seed));
                    }
                }
            }
        }
        return expected;
    }

    private static String validateProvenance(JsonObject evidence, NetworkStudyPlan registered, String sha) throws IOException {
        JsonObject study = evidence.getAsJsonObject("provenance").getAsJsonObject("study");
        JsonObject protocol = study.getAsJsonObject("protocol");
        if (!registered.getProtocol().equals(text(study, "id"))
                || !registered.getProtocol().equals(text(protocol, "logicalId"))
                || !flag(protocol, "available") || !sha.equals(text(protocol, "sha256"))) {
            throw new IOException("Run provenance does not bind the retained study protocol");
        }
        JsonObject component = study.getAsJsonObject("component");
        if (!"org.workflowsim".equals(text(component, "groupId"))
                || !"network-study".equals(text(component, "artifactId"))
                || !"1.0".equals(text(component, "version"))
                || !NetworkStudyExecutor.class.getName().equals(text(component, "anchorClass"))) {
            throw new IOException("Run provenance has an unrelated study driver identity");
        }
        JsonObject dataset = study.getAsJsonObject("dataset");
        if (!"EXPLICIT_ABSOLUTE_DATASET_ROOT".equals(text(dataset, "resolutionPolicy"))) {
            throw new IOException("Unknown study input resolution policy");
        }
        // Historical source/binary hashes are provenance, not claims about this checkout.
        return text(dataset, "root");
    }

    private static void validateConditions(JsonObject evidence, NetworkStudyPlan registered, String input,
            int count, String network, String heterogeneity, PlanningAlgorithm planner, long seed, String cell) throws IOException {
        SimulationConfig config = NetworkStudyPlan.configuration(input, count, network, planner, seed);
        JsonObject expectedConfig = JSON.toJsonTree(ExperimentManifestWriter.configurationSnapshot(config)).getAsJsonObject();
        JsonObject actualConfig = evidence.getAsJsonObject("configuration").deepCopy();
        JsonObject contract = actualConfig.getAsJsonObject("algorithmContract");
        if (!planner.name().equals(text(contract.getAsJsonObject("planner"), "id"))
                || !"STATIC".equals(text(contract.getAsJsonObject("scheduler"), "id"))) {
            throw new IOException("Algorithm contract identity differs from study cell: " + cell);
        }
        expectedConfig.remove("algorithmContract");
        actualConfig.remove("algorithmContract");
        if (registered.isHistoricalProtocol()) {
            expectedConfig.remove("executionSemantics");
            // Do not remove it from actualConfig: corrected-model evidence cannot impersonate
            // a historical protocol, whose original v4 manifests had no such declaration.
        }
        if (!ExactJsonValues.equal(expectedConfig, actualConfig)) {
            throw new IOException("Fixed study simulation conditions/model revision differ: " + cell);
        }
        PlatformProfile platform = NetworkStudyPlan.platform(count, network, heterogeneity);
        if (!ExactJsonValues.equal(JSON.toJsonTree(ExperimentManifestWriter.platformSnapshot(platform)), evidence.get("platform"))) {
            throw new IOException("Fixed study Host/VM/storage/price/topology conditions differ: " + cell);
        }
        if (!ExactJsonValues.equal(JSON.toJsonTree(platform.getVmHostAssignments()),
                evidence.getAsJsonObject("result").get("actualVmHostAssignments"))) {
            throw new IOException("Observed VM placement differs from fixed study placement: " + cell);
        }
    }

    private static void match(JsonObject a, String ak, JsonObject b, String bk) throws IOException {
        boolean utilization = "meanVmModeledIntervalUtilization".equals(bk);
        requireMetricNumber(a.get(ak), ak, utilization);
        requireMetricNumber(b.get(bk), bk, utilization);
        if (!ExactJsonValues.equal(a.get(ak), b.get(bk))) { throw new IOException("Study metric mismatch: " + ak); }
    }

    /** Only the registered study's published observations are subject to these value domains. */
    private static void requireMetricNumber(JsonElement value, String field, boolean utilization) throws IOException {
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) {
            throw new IOException("Missing or non-numeric study metric: " + field);
        }
        BigDecimal exact;
        try { exact = value.getAsBigDecimal(); }
        catch (NumberFormatException invalid) { throw new IOException("Invalid numeric study metric: " + field, invalid); }
        if (!Double.isFinite(exact.doubleValue()) || exact.signum() < 0) {
            throw new IOException("Study metric must be finite and nonnegative: " + field);
        }
        // Check the declared decimal value, not a rounded double: tiny negatives and values
        // just above one must not be accepted as -0.0 or 1.0 after binary64 conversion.
        if (utilization && exact.compareTo(BigDecimal.ONE) > 0) {
            throw new IOException("Study modeled interval utilization must be within [0,1]: " + field);
        }
    }

    private static String text(JsonObject value, String key) throws IOException {
        JsonElement field = value == null ? null : value.get(key);
        if (field == null || !field.isJsonPrimitive() || !field.getAsJsonPrimitive().isString()) {
            throw new IOException("Missing or non-string study field: " + key);
        }
        return field.getAsString();
    }

    private static long wholeNumber(JsonObject value, String key) throws IOException {
        JsonElement field = value.get(key);
        if (field == null || !field.isJsonPrimitive() || !field.getAsJsonPrimitive().isNumber()) {
            throw new IOException("Missing or non-numeric study field: " + key);
        }
        try { return field.getAsBigDecimal().longValueExact(); }
        catch (ArithmeticException | NumberFormatException invalid) { throw new IOException("Non-integral study field: " + key, invalid); }
    }

    private static int integer(JsonObject value, String key) throws IOException {
        return Math.toIntExact(wholeNumber(value, key));
    }

    private static boolean flag(JsonObject value, String key) throws IOException {
        JsonElement field = value.get(key);
        if (field == null || !field.isJsonPrimitive() || !field.getAsJsonPrimitive().isBoolean()) {
            throw new IOException("Missing or non-boolean study field: " + key);
        }
        return field.getAsBoolean();
    }

    private static String key(String workflow, int count, String network, String heterogeneity, String planner, long seed) {
        return workflow + "/" + count + "/" + network + "/" + heterogeneity + "/" + planner + "/" + seed;
    }

    private static String portable(String path) { return path.replace('\\', '/'); }

    private static String sha256(byte[] bytes) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
            StringBuilder hex = new StringBuilder();
            for (byte value : digest) { hex.append(String.format(Locale.ROOT, "%02x", value & 255)); }
            return hex.toString();
        } catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException("SHA-256 is required", impossible); }
    }

    private static JsonObject read(Path path) throws IOException {
        return JsonParser.parseString(new String(Files.readAllBytes(path), StandardCharsets.UTF_8)).getAsJsonObject();
    }
}
