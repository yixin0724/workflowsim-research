package org.workflowsim.experiments.reference.p7;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.cloudbus.cloudsim.Log;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.workflowsim.utils.Parameters;

class P7BaselineExecutorTest {

    private static final Gson JSON = new GsonBuilder().serializeNulls().create();

    @AfterEach
    void restoreLogging() {
        Log.enable();
    }

    @Test
    void selectionWritesASeparateNonBaselineIndexAndRejectsTampering(@TempDir Path outputDirectory)
            throws Exception {
        Path datasetRoot = datasetRoot();
        P7BaselineMatrix.Scenario scenario = P7BaselineMatrix.scenarios(datasetRoot).get(0);

        Log.disable();
        List<P7BaselineExecutor.RunRecord> records = P7BaselineExecutor.executeSelection(datasetRoot,
                outputDirectory, Collections.singletonList(scenario),
                Collections.singletonList(Parameters.SchedulingAlgorithm.FCFS));

        assertEquals(1, records.size());
        P7BaselineExecutor.RunRecord record = records.get(0);
        assertEquals(scenario.getId(), record.getScenarioId());
        assertEquals(0, record.getFailedJobs());
        assertEquals(record.getTotalJobs(), record.getSuccessfulJobs());
        Path manifest = outputDirectory.resolve(record.getManifest());
        Path metrics = outputDirectory.resolve(record.getMetrics());
        Path events = outputDirectory.resolve(record.getEvents());
        assertTrue(Files.isRegularFile(manifest));
        assertTrue(Files.isRegularFile(metrics));
        assertTrue(Files.isRegularFile(events));
        String manifestContents = new String(Files.readAllBytes(manifest), StandardCharsets.UTF_8);
        assertTrue(manifestContents.contains("workflowsim-experiment-manifest-v3"));

        Path selectionIndex = outputDirectory.resolve(P7BaselineExecutor.SELECTION_INDEX_FILE_NAME);
        assertTrue(Files.isRegularFile(selectionIndex));
        String contents = new String(Files.readAllBytes(selectionIndex), StandardCharsets.UTF_8);
        assertTrue(contents.contains(P7BaselineExecutor.SELECTION_INDEX_SCHEMA));
        assertTrue(contents.contains(P7BaselineExecutor.SELECTION_INDEX_KIND));
        assertTrue(contents.contains(P7BaselineExecutor.PROTOCOL_REFERENCE));
        assertTrue(contents.contains(P7ReferenceIdentity.PROTOCOL_ID));
        assertTrue(contents.contains("referenceIdentity"));
        assertTrue(contents.contains("EXPLICIT_ABSOLUTE_DATASET_ROOT"));
        assertTrue(contents.contains("cloudSimMinEventIntervalSeconds"));
        assertTrue(contents.contains(record.getWorkflowSha256()));
        assertTrue(contents.contains(record.getManifest()));
        assertTrue(contents.contains(record.getMetrics()));
        assertTrue(contents.contains(record.getEvents()));
        assertEquals(1, P7EvidenceIndexValidator.validate(selectionIndex).getRunCount());

        JsonObject alteredConfiguration = JsonParser.parseString(manifestContents).getAsJsonObject();
        alteredConfiguration.getAsJsonObject("configuration").addProperty("fileSystem", "LOCAL");
        Files.write(manifest, JSON.toJson(alteredConfiguration).getBytes(StandardCharsets.UTF_8));
        assertThrows(java.io.IOException.class, () -> P7EvidenceIndexValidator.validate(selectionIndex));
        Files.write(manifest, manifestContents.getBytes(StandardCharsets.UTF_8));

        JsonObject alteredPlatform = JsonParser.parseString(manifestContents).getAsJsonObject();
        alteredPlatform.getAsJsonObject("platform").getAsJsonArray("vms").get(0).getAsJsonObject()
                .addProperty("mips", 999.0);
        Files.write(manifest, JSON.toJson(alteredPlatform).getBytes(StandardCharsets.UTF_8));
        assertThrows(java.io.IOException.class, () -> P7EvidenceIndexValidator.validate(selectionIndex));
        Files.write(manifest, manifestContents.getBytes(StandardCharsets.UTF_8));

        Files.write(selectionIndex, contents.replace("\"cloudSimMinEventIntervalSeconds\": 0.1",
                "\"cloudSimMinEventIntervalSeconds\": 0.2")
                .getBytes(StandardCharsets.UTF_8));
        assertThrows(java.io.IOException.class, () -> P7EvidenceIndexValidator.validate(selectionIndex));

        Files.write(selectionIndex, contents.replace("\"schedulingAlgorithm\": \"FCFS\"",
                "\"schedulingAlgorithm\": \"READY_BATCH_MCT\"")
                .getBytes(StandardCharsets.UTF_8));
        assertThrows(java.io.IOException.class, () -> P7EvidenceIndexValidator.validate(selectionIndex));

        JsonObject alteredIdentity = JsonParser.parseString(contents).getAsJsonObject();
        alteredIdentity.getAsJsonObject("referenceIdentity").getAsJsonObject("dataset")
                .addProperty("root", "/different-absolute-dataset-root");
        Files.write(selectionIndex, JSON.toJson(alteredIdentity).getBytes(StandardCharsets.UTF_8));
        assertThrows(java.io.IOException.class, () -> P7EvidenceIndexValidator.validate(selectionIndex));

        JsonObject falseBaseline = JsonParser.parseString(contents).getAsJsonObject();
        falseBaseline.addProperty("schema", P7BaselineExecutor.BASELINE_INDEX_SCHEMA);
        falseBaseline.addProperty("kind", P7BaselineExecutor.BASELINE_INDEX_KIND);
        Path falseBaselineIndex = outputDirectory.resolve(P7BaselineExecutor.BASELINE_INDEX_FILE_NAME);
        Files.write(falseBaselineIndex, JSON.toJson(falseBaseline).getBytes(StandardCharsets.UTF_8));
        assertThrows(java.io.IOException.class, () -> P7EvidenceIndexValidator.validate(falseBaselineIndex));
    }

    @Test
    void frozenBaselineCoversTheExactMatrixAndHistoricalV2ShapeRemainsReadable(
            @TempDir Path outputDirectory) throws Exception {
        Path datasetRoot = datasetRoot();
        Log.disable();
        // 通过正式 CLI 覆盖参数校验、日志恢复和完整冻结矩阵，而不是绕过入口直接调用实现。
        P7BaselineExecutor.main(new String[] {datasetRoot.toString(), outputDirectory.toString()});

        Path indexPath = outputDirectory.resolve(P7BaselineExecutor.BASELINE_INDEX_FILE_NAME);
        JsonObject index = JsonParser.parseString(new String(Files.readAllBytes(indexPath),
                StandardCharsets.UTF_8)).getAsJsonObject();
        com.google.gson.JsonArray records = index.getAsJsonArray("runs");

        assertEquals(P7BaselineMatrix.baselineCellKeys().size(), records.size());
        Set<String> actualCells = new HashSet<String>();
        for (com.google.gson.JsonElement recordElement : records) {
            JsonObject record = recordElement.getAsJsonObject();
            actualCells.add(P7BaselineMatrix.cellKey(record.get("scenarioId").getAsString(),
                    record.get("schedulingAlgorithm").getAsString()));
        }
        assertEquals(P7BaselineMatrix.baselineCellKeys(), actualCells);

        String indexContents = new String(Files.readAllBytes(indexPath), StandardCharsets.UTF_8);
        assertTrue(indexContents.contains(P7BaselineExecutor.BASELINE_INDEX_SCHEMA));
        assertTrue(indexContents.contains(P7BaselineExecutor.BASELINE_INDEX_KIND));
        assertEquals(records.size(), P7EvidenceIndexValidator.validate(indexPath).getRunCount());
        P7EvidenceIndexValidator.main(new String[] {indexPath.toString()});

        for (com.google.gson.JsonElement recordElement : records) {
            JsonObject record = recordElement.getAsJsonObject();
            Path manifestPath = outputDirectory.resolve(record.get("manifest").getAsString());
            JsonObject manifest = JsonParser.parseString(new String(Files.readAllBytes(manifestPath),
                    StandardCharsets.UTF_8)).getAsJsonObject();
            manifest.addProperty("schema", "workflowsim-experiment-manifest-v2");
            Files.write(manifestPath, JSON.toJson(manifest).getBytes(StandardCharsets.UTF_8));
        }
        JsonObject historicalIndex = JsonParser.parseString(indexContents).getAsJsonObject();
        historicalIndex.addProperty("schema", "workflowsim-p7-baseline-index-v2");
        historicalIndex.addProperty("protocol", "P7_EXPERIMENT_PROTOCOL.md");
        historicalIndex.remove("kind");
        historicalIndex.remove("referenceIdentity");
        Files.write(indexPath, JSON.toJson(historicalIndex).getBytes(StandardCharsets.UTF_8));

        assertEquals(records.size(), P7EvidenceIndexValidator.validate(indexPath).getRunCount());
    }

    @Test
    void commandLineRequiresExactlyTwoArgumentsOrUsesDefaults() {
        // 无参数时使用默认值，不再抛异常（支持 IDE 直接运行）
        // P7BaselineExecutor.main(new String[0]); // 现在会使用默认路径
        
        // 只有一个参数仍然抛异常
        assertThrows(IllegalArgumentException.class,
                () -> P7BaselineExecutor.main(new String[] {"datasets"}));
    }

    @Test
    void validatorRetainsHistoricalV3ProtocolIdentityCompatibility(@TempDir Path outputDirectory)
            throws Exception {
        Path datasetRoot = datasetRoot();
        P7BaselineMatrix.Scenario scenario = P7BaselineMatrix.scenarios(datasetRoot).get(0);
        Log.disable();
        List<P7BaselineExecutor.RunRecord> records = P7BaselineExecutor.executeSelection(datasetRoot,
                outputDirectory, Collections.singletonList(scenario),
                Collections.singletonList(Parameters.SchedulingAlgorithm.FCFS));

        Path indexPath = outputDirectory.resolve(P7BaselineExecutor.SELECTION_INDEX_FILE_NAME);
        JsonObject index = JsonParser.parseString(new String(Files.readAllBytes(indexPath),
                StandardCharsets.UTF_8)).getAsJsonObject();
        JsonObject historicalIdentity = index.getAsJsonObject("referenceIdentity").deepCopy();
        historicalIdentity.getAsJsonObject("protocol").addProperty("logicalId",
                "docs/experiments/reference-baselines/P7_EXPERIMENT_PROTOCOL.md");
        index.addProperty("protocol", "P7_EXPERIMENT_PROTOCOL.md");
        index.add("referenceIdentity", historicalIdentity);

        for (P7BaselineExecutor.RunRecord record : records) {
            Path manifestPath = outputDirectory.resolve(record.getManifest());
            JsonObject manifest = JsonParser.parseString(new String(Files.readAllBytes(manifestPath),
                    StandardCharsets.UTF_8)).getAsJsonObject();
            manifest.getAsJsonObject("provenance").add("study", historicalIdentity.deepCopy());
            Files.write(manifestPath, JSON.toJson(manifest).getBytes(StandardCharsets.UTF_8));
        }
        Files.write(indexPath, JSON.toJson(index).getBytes(StandardCharsets.UTF_8));

        assertEquals(records.size(), P7EvidenceIndexValidator.validate(indexPath).getRunCount());
    }

    @Test
    void selectionRejectsDuplicateCellsBeforeWritingArtifacts(@TempDir Path outputDirectory) {
        Path datasetRoot = datasetRoot();
        P7BaselineMatrix.Scenario scenario = P7BaselineMatrix.scenarios(datasetRoot).get(0);
        assertThrows(IllegalArgumentException.class, () -> P7BaselineExecutor.executeSelection(datasetRoot,
                outputDirectory, Collections.singletonList(scenario), Arrays.asList(
                        Parameters.SchedulingAlgorithm.FCFS, Parameters.SchedulingAlgorithm.FCFS)));
    }

    @Test
    void executorRejectsRelativeDatasetRoot(@TempDir Path outputDirectory) {
        assertThrows(IllegalArgumentException.class, () -> P7BaselineExecutor.executeSelection(
                Paths.get("datasets"), outputDirectory,
                Collections.<P7BaselineMatrix.Scenario>singletonList(null),
                Collections.singletonList(Parameters.SchedulingAlgorithm.FCFS)));
    }

    @Test
    void executorRejectsRelativeOutputDirectory() {
        Path datasetRoot = datasetRoot();
        P7BaselineMatrix.Scenario scenario = P7BaselineMatrix.scenarios(datasetRoot).get(0);
        assertThrows(IllegalArgumentException.class, () -> P7BaselineExecutor.executeSelection(
                datasetRoot, Paths.get("p7-output"), Collections.singletonList(scenario),
                Collections.singletonList(Parameters.SchedulingAlgorithm.FCFS)));
    }

    private static Path datasetRoot() {
        String configured = System.getProperty("workflowsim.datasetRoot");
        if (configured == null || configured.trim().isEmpty()) {
            throw new IllegalStateException("Missing required test property workflowsim.datasetRoot");
        }
        return ReferenceDatasetRoot.require(Paths.get(configured));
    }
}
