package org.workflowsim.experiments.network;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonPrimitive;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.reflect.TypeToken;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.workflowsim.experiment.ExperimentArtifactValidator;

/** Protocol-certification regressions use fresh small bundles, never retained research output. */
class NetworkStudyValidatorTest {
    private static final Gson JSON = new GsonBuilder().serializeNulls().setPrettyPrinting().create();
    private static final String ZERO_SHA =
            "0000000000000000000000000000000000000000000000000000000000000000";
    private static final String[][] AUXILIARY_METRICS = {
            {"meanWaitingSeconds", "meanComputeTotalWaitingTimeSeconds"},
            {"p95WaitingSeconds", "p95ComputeTotalWaitingTimeSeconds"},
            {"meanVmUtilization", "meanVmModeledIntervalUtilization"},
            {"logicalCompletionSeconds", "logicalTaskCompletionSeconds"}
    };
    @TempDir static Path fixtures;
    @TempDir Path temporary;
    private static Path smoke;

    @BeforeAll
    static void generateSmallStudy() throws Exception {
        smoke = fixtures.resolve("smoke");
        NetworkStudyExecutor.execute("smoke", true, NetworkStudyTest.datasets(), smoke);
    }

    @Test
    void unmodifiedCompleteSmokeStudyIsAccepted() throws Exception {
        assertEquals(18, NetworkStudyValidator.validate(smoke.resolve(NetworkStudyExecutor.INDEX)));
    }

    @Test
    void rejectsEmptyFullMatricesForEveryKnownProtocol() throws Exception {
        assertEmptyMatricesRejected("full");
    }

    @Test
    void rejectsEmptySmokeMatricesForEveryKnownProtocol() throws Exception {
        assertEmptyMatricesRejected("smoke");
    }

    @Test
    void rejectsCoherentlyPrunedSmokeMatrixWithReboundProtocolHashes() throws Exception {
        Path root = copySmoke("pruned");
        JsonObject index = read(root.resolve(NetworkStudyExecutor.INDEX));
        JsonObject plan = index.getAsJsonObject("plan");
        keepFirst(plan, "workflows");
        keepFirst(plan, "planners");
        keepFirst(plan, "networks");
        keepFirst(index, "runs");
        plan.addProperty("runCount", 1);
        refreshSummary(index);
        writeStudyAndRebindProtocol(root, index);

        assertRejected(root, "a self-consistent one-cell subset is not the declared smoke protocol");
    }

    @Test
    void rejectsSmokeMatrixRelabelledAsFullEvenWithMatchingProtocolHashes() throws Exception {
        Path root = copySmoke("wrong-mode");
        JsonObject index = read(root.resolve(NetworkStudyExecutor.INDEX));
        index.getAsJsonObject("plan").addProperty("mode", "full");
        writeStudyAndRebindProtocol(root, index);
        assertRejected(root, "mode participates in the protocol identity");
    }

    @Test
    void rejectsCoherentlyChangedDeterministicSeed() throws Exception {
        Path root = copySmoke("wrong-seed");
        JsonObject index = read(root.resolve(NetworkStudyExecutor.INDEX));
        index.getAsJsonObject("plan").addProperty("deterministicSeed", 12L);
        for (JsonElement element : index.getAsJsonArray("runs")) {
            JsonObject run = element.getAsJsonObject();
            run.addProperty("seed", 12L);
            run.addProperty("runId", run.get("runId").getAsString().replace("-s11", "-s12"));
            Path manifestPath = root.resolve(run.get("manifest").getAsString());
            JsonObject manifest = read(manifestPath);
            manifest.getAsJsonObject("configuration").addProperty("rootSeed", 12L);
            write(manifestPath, manifest);
        }
        refreshSummary(index);
        writeStudyAndRebindProtocol(root, index);
        assertRejected(root, "a new deterministic seed needs a distinct registered protocol");
    }

    @Test
    void rejectsContradictoryPhysicalAndStatisticalProtocolMetadata() throws Exception {
        String[] fields = {"vmMips", "endpointMbPerSecond", "fatTreeK", "constrainedLinkMbPerSecond",
                "wideLinkMbPerSecond", "transferStart", "inference"};
        for (String field : fields) {
            Path root = copySmoke("metadata-" + field);
            JsonObject index = read(root.resolve(NetworkStudyExecutor.INDEX));
            JsonObject plan = index.getAsJsonObject("plan");
            if ("transferStart".equals(field) || "inference".equals(field)) {
                plan.addProperty(field, "UNDECLARED_SEMANTICS");
            } else {
                plan.addProperty(field, 123.0);
            }
            writeStudyAndRebindProtocol(root, index);
            assertRejected(root, "protocol field must match the registered declaration: " + field);
        }
    }

    @Test
    void rejectsProtocolHashMismatchWithoutRequiringCurrentSourceIdentity() throws Exception {
        Path root = copySmoke("protocol-sha");
        mutateFirstManifest(root, manifest -> study(manifest).getAsJsonObject("protocol")
                .addProperty("sha256", ZERO_SHA));
        assertRejected(root, "run provenance must bind the retained protocol bytes");
    }

    @Test
    void rejectsWrongStudyIdLogicalIdAndDriverIdentity() throws Exception {
        String[] fields = {"studyId", "logicalId", "anchorClass", "artifactId", "available"};
        for (String field : fields) {
            Path root = copySmoke("identity-" + field);
            mutateFirstManifest(root, manifest -> {
                JsonObject study = study(manifest);
                if ("studyId".equals(field)) {
                    study.addProperty("id", "unrelated-study");
                } else if ("logicalId".equals(field)) {
                    study.getAsJsonObject("protocol").addProperty("logicalId", "unrelated-protocol");
                } else if ("available".equals(field)) {
                    study.getAsJsonObject("protocol").addProperty("available", false);
                } else {
                    study.getAsJsonObject("component").addProperty(field, "unrelated-driver");
                }
            });
            assertRejected(root, "study provenance identity mismatch: " + field);
        }
    }

    @Test
    void sourceTreeEvolutionAloneDoesNotInvalidateHistoricalIntegrity() throws Exception {
        Path root = copySmoke("different-source");
        mutateFirstManifest(root, manifest -> {
            manifest.getAsJsonObject("provenance").getAsJsonObject("core")
                    .addProperty("sourceTreeSha256", ZERO_SHA);
            study(manifest).getAsJsonObject("component").addProperty("sourceTreeSha256", ZERO_SHA);
        });
        assertEquals(18, NetworkStudyValidator.validate(root.resolve(NetworkStudyExecutor.INDEX)),
                "integrity validation is not a claim of identical current-code execution");
    }

    @Test
    void rejectsNonzeroOverheadUnderTheFixedStudyProtocol() throws Exception {
        Path root = copySmoke("overhead");
        mutateFirstManifest(root, manifest -> manifest.getAsJsonObject("configuration")
                .getAsJsonObject("overheadModel").getAsJsonObject("queueDelays")
                .add("1", JsonParser.parseString("{\"family\":\"WEIBULL\",\"scale\":1.0,\"shape\":1.0,"
                        + "\"priorShape\":null,\"priorScale\":null,\"likelihoodPrior\":null}")));
        assertRejected(root, "fixed study conditions exclude modeled overhead");
    }

    @Test
    void rejectsImpossibleHostCapacityAndIncorrectPreflightOrActualPlacement() throws Exception {
        String[] fields = {"hostCapacity", "preflight", "actual"};
        for (String field : fields) {
            Path root = copySmoke("placement-" + field);
            mutateFirstManifest(root, manifest -> {
                JsonObject platform = manifest.getAsJsonObject("platform");
                if ("hostCapacity".equals(field)) {
                    platform.getAsJsonArray("hosts").get(0).getAsJsonObject().addProperty("mipsPerPe", 1.0);
                } else if ("preflight".equals(field)) {
                    platform.getAsJsonArray("vms").get(0).getAsJsonObject().addProperty("preflightHostId", 1);
                } else {
                    manifest.getAsJsonObject("result").getAsJsonObject("actualVmHostAssignments")
                            .addProperty("0", 1);
                }
            });
            assertRejected(root, "declared and actual one-host-per-VM placement must agree: " + field);
        }
    }

    @Test
    void rejectsChangedCostModelStorageAndPrices() throws Exception {
        String[] fields = {"costModel", "storage", "prices"};
        for (String field : fields) {
            Path root = copySmoke("fixed-" + field);
            mutateFirstManifest(root, manifest -> {
                if ("costModel".equals(field)) {
                    manifest.getAsJsonObject("configuration").addProperty("costModel", "VM");
                } else if ("storage".equals(field)) {
                    manifest.getAsJsonObject("platform").getAsJsonObject("storage")
                            .addProperty("maxTransferRateMbPerSecond", 30);
                } else {
                    manifest.getAsJsonObject("platform").getAsJsonObject("costs")
                            .addProperty("cpuPerSecond", 6.0);
                }
            });
            assertRejected(root, "fixed study platform/configuration mismatch: " + field);
        }
    }

    @Test
    void rejectsIncompleteEvidenceDespiteSuccessfulIndexStatus() throws Exception {
        Path root = copySmoke("incomplete");
        mutateFirstManifest(root, manifest -> {
            manifest.getAsJsonObject("result").addProperty("logicalTaskCompletionStatus", "INCOMPLETE");
            manifest.getAsJsonObject("result").addProperty("workflowCompletedSuccessfully", false);
        });
        assertRejected(root, "the index success label cannot override incomplete run evidence");
    }

    @Test
    void correctedProtocolRequiresItsExecutionSemanticsDeclaration() throws Exception {
        Path root = copySmoke("missing-execution-revision");
        mutateFirstManifest(root, manifest -> manifest.getAsJsonObject("configuration").remove("executionSemantics"));
        assertRejected(root, "new protocol evidence must declare its corrected execution model");
    }

    @Test
    void correctedExecutionCannotImpersonateHistoricalProtocol() throws Exception {
        Path root = copySmoke("new-execution-old-protocol");
        JsonObject index = read(root.resolve(NetworkStudyExecutor.INDEX));
        index.getAsJsonObject("plan").addProperty("protocol", NetworkStudyPlan.HISTORICAL_PEFT_COMPARISON_PROTOCOL);
        index.getAsJsonObject("plan").remove("executionSemantics");
        writeStudyAndRebindProtocol(root, index);
        assertRejected(root, "current executionSemantics cannot be relabelled as a historical protocol");
    }

    @Test
    void historicalWireShapeRemainsReadableWithoutClaimingCurrentModelEquivalence() throws Exception {
        Path root = copySmoke("historical-wire-shape");
        JsonObject index = read(root.resolve(NetworkStudyExecutor.INDEX));
        index.getAsJsonObject("plan").addProperty("protocol", NetworkStudyPlan.HISTORICAL_PEFT_COMPARISON_PROTOCOL);
        index.getAsJsonObject("plan").remove("executionSemantics");
        for (JsonElement value : index.getAsJsonArray("runs")) {
            Path path = root.resolve(value.getAsJsonObject().get("manifest").getAsString());
            JsonObject manifest = read(path);
            manifest.getAsJsonObject("configuration").remove("executionSemantics");
            write(path, manifest);
        }
        writeStudyAndRebindProtocol(root, index);
        // This is a small compatibility fixture, not a replacement for the retained S5 results.
        assertEquals(18, NetworkStudyValidator.validate(root.resolve(NetworkStudyExecutor.INDEX)));
    }

    @Test
    void coordinatedMissingAuxiliaryMetricsAreRejectedAfterArtifactIntegrityValidation() throws Exception {
        // The generic metrics-v2 schema already requires mean waiting. Use the other three
        // fields to ensure the study-specific missing-value guard, not that earlier guard, fires.
        for (int i = 1; i < AUXILIARY_METRICS.length; i++) {
            String[] metric = AUXILIARY_METRICS[i];
            Path root = copySmoke("missing-metric-" + metric[0]);
            changeFirstMetricConsistently(root, metric[0], metric[1], null);
            assertRejected(root, "missing auxiliary metric must not compare equal as null/null: " + metric[0]);
        }
    }

    @Test
    void coordinatedNullAuxiliaryMetricsAreRejectedAfterArtifactIntegrityValidation() throws Exception {
        for (String[] metric : AUXILIARY_METRICS) {
            Path root = copySmoke("null-metric-" + metric[0]);
            changeFirstMetricConsistently(root, metric[0], metric[1], JsonNull.INSTANCE);
            assertRejected(root, "a successful study requires numeric auxiliary metrics: " + metric[0]);
        }
    }

    @Test
    void coordinatedNonNumericAuxiliaryMetricsAreRejectedAfterArtifactIntegrityValidation() throws Exception {
        JsonElement[] values = {new JsonPrimitive("0.0"), new JsonPrimitive(true), new JsonArray(), new JsonObject()};
        for (int type = 0; type < values.length; type++) {
            for (String[] metric : AUXILIARY_METRICS) {
                Path root = copySmoke("typed-metric-" + type + "-" + metric[0]);
                changeFirstMetricConsistently(root, metric[0], metric[1], values[type]);
                assertRejected(root, "equal strings/booleans/containers are not metric numbers: " + metric[0]);
            }
        }
    }

    @Test
    void coordinatedNegativeAuxiliaryMetricsAreRejectedAfterArtifactIntegrityValidation() throws Exception {
        JsonElement[] values = {new JsonPrimitive(-0.25), JsonParser.parseString("-1e-999")};
        for (int value = 0; value < values.length; value++) {
            for (String[] metric : AUXILIARY_METRICS) {
                Path root = copySmoke("negative-metric-" + value + "-" + metric[0]);
                changeFirstMetricConsistently(root, metric[0], metric[1], values[value]);
                assertRejected(root, "negative metrics remain invalid even if double conversion underflows: " + metric[0]);
            }
        }
    }

    @Test
    void coordinatedNonFiniteAuxiliaryMetricsAreRejectedAfterArtifactIntegrityValidation() throws Exception {
        for (String[] metric : AUXILIARY_METRICS) {
            Path root = copySmoke("nonfinite-metric-" + metric[0]);
            // Valid JSON numeric syntax, but not a finite value in the simulator's double domain.
            changeFirstMetricConsistently(root, metric[0], metric[1], JsonParser.parseString("1e999"));
            assertRejected(root, "model observations must be finite: " + metric[0]);
        }
    }

    @Test
    void coordinatedUtilizationAboveOneIsRejectedWithoutRoundingItDown() throws Exception {
        JsonElement[] values = {new JsonPrimitive(1.25), JsonParser.parseString("1.00000000000000000001")};
        for (int i = 0; i < values.length; i++) {
            Path root = copySmoke("over-utilization-" + i);
            changeFirstMetricConsistently(root, "meanVmUtilization", "meanVmModeledIntervalUtilization", values[i]);
            assertRejected(root, "interval-union utilization must not exceed one");
        }
    }

    @Test
    void finiteMetricWireDomainIncludesZeroWaitingAndUnitUtilization() throws Exception {
        Path root = copySmoke("metric-domain-boundaries");
        // These are wire-format boundary controls, not claims that the edited values were simulated.
        changeFirstMetricConsistently(root, "p95WaitingSeconds", "p95ComputeTotalWaitingTimeSeconds", new JsonPrimitive(0.0));
        changeFirstMetricConsistently(root, "meanVmUtilization", "meanVmModeledIntervalUtilization", new JsonPrimitive(1.0));
        assertEquals(18, NetworkStudyValidator.validate(root.resolve(NetworkStudyExecutor.INDEX)));
    }

    private void assertEmptyMatricesRejected(String mode) throws Exception {
        String[] historical = {"network-limited-r10-v2", "peft-comparison-r12-v1", "sensitivity-response-r13-v1"};
        NetworkStudyPlan.StudyVariant[] variants = NetworkStudyPlan.StudyVariant.values();
        for (int i = 0; i < variants.length; i++) {
            Path root = Files.createDirectories(temporary.resolve(mode + "-" + variants[i]));
            NetworkStudyPlan declared = NetworkStudyPlan.create(mode, variants[i], NetworkStudyTest.datasets(),
                    root.resolve("inputs"));
            List<String> ids = new ArrayList<String>();
            ids.add(historical[i]);
            if (!historical[i].equals(declared.getProtocol())) { ids.add(declared.getProtocol()); }
            for (String id : ids) {
                JsonObject plan = JSON.toJsonTree(declared.asMap()).getAsJsonObject();
                plan.addProperty("protocol", id);
                if (historical[i].equals(id)) { plan.remove("executionSemantics"); }
                plan.add("workflows", new JsonArray());
                plan.addProperty("runCount", 0);
                JsonObject index = new JsonObject();
                index.addProperty("schema", "workflowsim-network-study-v1");
                index.add("plan", plan);
                index.add("runs", new JsonArray());
                index.add("summary", JSON.toJsonTree(NetworkStudySummary.summarize(
                        Collections.<Map<String, Object>>emptyList())));
                write(root.resolve("protocol.json"), plan);
                write(root.resolve(NetworkStudyExecutor.INDEX), index);
                assertRejected(root, "an empty " + mode + " matrix cannot claim " + id);
            }
        }
    }

    private Path copySmoke(String name) throws Exception {
        Path target = temporary.resolve(name);
        List<Path> paths;
        try (Stream<Path> stream = Files.walk(smoke)) { paths = stream.collect(Collectors.toList()); }
        for (Path path : paths) {
            Path destination = target.resolve(smoke.relativize(path));
            if (Files.isDirectory(path)) { Files.createDirectories(destination); }
            else { Files.copy(path, destination); }
        }
        return target;
    }

    private static void mutateFirstManifest(Path root, Consumer<JsonObject> mutation) throws Exception {
        JsonObject index = read(root.resolve(NetworkStudyExecutor.INDEX));
        JsonObject run = index.getAsJsonArray("runs").get(0).getAsJsonObject();
        Path path = root.resolve(run.get("manifest").getAsString());
        JsonObject manifest = read(path);
        mutation.accept(manifest);
        write(path, manifest);
    }

    /** Mutate all metric representations and repair the sidecar binding before testing the study gate. */
    private static void changeFirstMetricConsistently(Path root, String runKey, String metricKey,
            JsonElement replacement) throws Exception {
        JsonObject index = read(root.resolve(NetworkStudyExecutor.INDEX));
        JsonObject run = index.getAsJsonArray("runs").get(0).getAsJsonObject();
        Path manifestPath = root.resolve(run.get("manifest").getAsString());
        JsonObject manifest = read(manifestPath);
        JsonObject metricsArtifact = null;
        for (JsonElement value : manifest.getAsJsonArray("artifacts")) {
            JsonObject artifact = value.getAsJsonObject();
            if ("metrics".equals(artifact.get("role").getAsString())) { metricsArtifact = artifact; break; }
        }
        if (metricsArtifact == null) { throw new AssertionError("Fixture has no metrics artifact"); }
        Path metricsPath = manifestPath.getParent().resolve(metricsArtifact.get("path").getAsString());
        JsonObject sidecar = read(metricsPath);
        if (replacement == null) {
            run.remove(runKey);
            manifest.getAsJsonObject("metrics").remove(metricKey);
            sidecar.getAsJsonObject("metrics").remove(metricKey);
        } else {
            run.add(runKey, replacement.deepCopy());
            manifest.getAsJsonObject("metrics").add(metricKey, replacement.deepCopy());
            sidecar.getAsJsonObject("metrics").add(metricKey, replacement.deepCopy());
        }
        write(metricsPath, sidecar);
        byte[] bytes = Files.readAllBytes(metricsPath);
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
        StringBuilder sha = new StringBuilder();
        for (byte value : digest) { sha.append(String.format(java.util.Locale.ROOT, "%02x", value & 255)); }
        metricsArtifact.addProperty("sha256", sha.toString());
        metricsArtifact.addProperty("sizeBytes", bytes.length);
        write(manifestPath, manifest);
        refreshSummary(index);
        write(root.resolve(NetworkStudyExecutor.INDEX), index);
        assertDoesNotThrow(() -> { ExperimentArtifactValidator.validate(manifestPath); },
                "The coordinated " + metricKey + " fixture must pass generic artifact integrity before the study-specific assertion");
    }

    private static JsonObject study(JsonObject manifest) {
        return manifest.getAsJsonObject("provenance").getAsJsonObject("study");
    }

    private static void keepFirst(JsonObject parent, String field) {
        JsonArray values = new JsonArray();
        values.add(parent.getAsJsonArray(field).get(0).deepCopy());
        parent.add(field, values);
    }

    private static void refreshSummary(JsonObject index) {
        List<Map<String, Object>> runs = JSON.fromJson(index.get("runs"),
                new TypeToken<List<Map<String, Object>>>() { }.getType());
        index.add("summary", JSON.toJsonTree(NetworkStudySummary.summarize(runs)));
    }

    /** Keep unrelated provenance defenses satisfied so coordinated matrix mutations are meaningful. */
    private static void writeStudyAndRebindProtocol(Path root, JsonObject index) throws Exception {
        JsonObject plan = index.getAsJsonObject("plan");
        Path protocol = root.resolve("protocol.json");
        write(protocol, plan);
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(protocol));
        StringBuilder sha = new StringBuilder();
        for (byte value : digest) { sha.append(String.format(java.util.Locale.ROOT, "%02x", value & 255)); }
        for (JsonElement element : index.getAsJsonArray("runs")) {
            Path manifestPath = root.resolve(element.getAsJsonObject().get("manifest").getAsString());
            JsonObject manifest = read(manifestPath);
            JsonObject study = study(manifest);
            study.addProperty("id", plan.get("protocol").getAsString());
            study.getAsJsonObject("protocol").addProperty("logicalId", plan.get("protocol").getAsString());
            study.getAsJsonObject("protocol").addProperty("available", true);
            study.getAsJsonObject("protocol").addProperty("sha256", sha.toString());
            write(manifestPath, manifest);
        }
        write(root.resolve(NetworkStudyExecutor.INDEX), index);
    }

    private static void assertRejected(Path root, String reason) {
        assertThrows(IOException.class, () -> NetworkStudyValidator.validate(root.resolve(NetworkStudyExecutor.INDEX)), reason);
    }

    private static JsonObject read(Path path) throws IOException {
        return JsonParser.parseString(new String(Files.readAllBytes(path), StandardCharsets.UTF_8)).getAsJsonObject();
    }

    private static void write(Path path, JsonElement value) throws IOException {
        Files.write(path, JSON.toJson(value).getBytes(StandardCharsets.UTF_8));
    }
}
