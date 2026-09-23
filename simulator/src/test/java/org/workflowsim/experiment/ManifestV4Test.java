package org.workflowsim.experiment;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Consumer;
import org.cloudbus.cloudsim.Log;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.workflowsim.data.DataMovementModel;
import org.workflowsim.network.NetworkTopologySpec;
import org.workflowsim.platform.PlatformProfile;
import org.workflowsim.utils.Parameters;
import org.workflowsim.utils.ReplicaCatalog;
import org.workflowsim.utils.SimulationConfig;
import org.workflowsim.utils.TaskCostMatrix;
import static org.junit.jupiter.api.Assertions.*;

class ManifestV4Test {
    private static final Gson JSON = new GsonBuilder().serializeNulls().create();

    @AfterEach void restoreLog() { Log.enable(); }

    @Test void allFiveModelsWriteDistinctAccurateSemantics() throws Exception {
        DataMovementModel[] models = {DataMovementModel.legacyWorkflowsimV1(),
                DataMovementModel.fixedEndpointNoContention(2.0, 0.3, 1.0),
                DataMovementModel.preExecutionTransferDelayV1(),
                DataMovementModel.preExecutionTransferDelayWithContentionV1(),
                DataMovementModel.fatTreeContentionV1()};
        java.util.Set<String> semantics = new java.util.HashSet<String>();
        for (DataMovementModel model : models) {
            JsonObject root = snapshot(run(model, null, false));
            assertEquals("workflowsim-experiment-manifest-v4", root.get("schema").getAsString());
            JsonObject config = root.getAsJsonObject("configuration");
            assertEquals(0.0, config.getAsJsonArray("workflowArrivalSeconds").get(0).getAsDouble());
            assertTrue(config.get("taskCostMatrix").isJsonNull());
            semantics.add(config.getAsJsonObject("dataMovementModel").get("contentionSemantics").getAsString());
            assertEquals(!model.isFatTreeContentionV1(), root.getAsJsonObject("platform")
                    .get("networkTopology").isJsonNull());
            ManifestV4Validator.validate(root);
        }
        assertEquals(5, semantics.size());
    }

    @Test void staggeredInputsPreserveOrderAndPerWorkflowResults() throws Exception {
        JsonObject root = snapshot(run(DataMovementModel.legacyWorkflowsimV1(), null, true));
        JsonArray arrivals = root.getAsJsonObject("configuration").getAsJsonArray("workflowArrivalSeconds");
        assertEquals(2, arrivals.size());
        assertEquals(7.25, arrivals.get(0).getAsDouble());
        assertEquals(0.0, arrivals.get(1).getAsDouble());
        assertEquals(2, root.getAsJsonObject("result").getAsJsonArray("workflowOutcomes").size());
        ManifestV4Validator.validate(root);
    }

    @Test void costMatrixIncludesAllEntriesInNumericOrder() throws Exception {
        TaskCostMatrix.Builder first = TaskCostMatrix.builder();
        TaskCostMatrix.Builder second = TaskCostMatrix.builder();
        for (int task = 1; task <= 5; task++) {
            first.put(task, 3, task + 0.25).put(task, 8, task + 0.5);
        }
        first.put(99, 17, 1.75);
        second.put(99, 17, 1.75);
        for (int task = 5; task >= 1; task--) {
            second.put(task, 8, task + 0.5).put(task, 3, task + 0.25);
        }
        JsonObject a = snapshot(run(DataMovementModel.preExecutionTransferDelayV1(), first.build(), false));
        JsonObject b = snapshot(run(DataMovementModel.preExecutionTransferDelayV1(), second.build(), false));
        JsonElement matrix = a.getAsJsonObject("configuration").get("taskCostMatrix");
        assertEquals(matrix, b.getAsJsonObject("configuration").get("taskCostMatrix"));
        JsonArray entries = matrix.getAsJsonObject().getAsJsonArray("entries");
        assertEquals(11, entries.size());
        assertEquals(3, entries.get(0).getAsJsonObject().get("vmId").getAsInt());
        assertEquals(99, entries.get(10).getAsJsonObject().get("taskId").getAsInt());
        assertEquals(1.75, entries.get(10).getAsJsonObject().get("executionSeconds").getAsDouble());
        ManifestV4Validator.validate(a);
        reject(a, root -> root.getAsJsonObject("configuration").getAsJsonObject("taskCostMatrix")
                .getAsJsonArray("entries").remove(0), "missing task");
        reject(a, root -> root.getAsJsonObject("configuration").getAsJsonObject("taskCostMatrix")
                .getAsJsonArray("entries").get(0).getAsJsonObject().addProperty("executionSeconds", 0), "executionSeconds");
        reject(a, root -> root.getAsJsonObject("configuration").getAsJsonObject("taskCostMatrix")
                .getAsJsonArray("entries").get(0).getAsJsonObject().addProperty("taskId", 1.2), "taskId");
        reject(a, root -> { JsonArray e = root.getAsJsonObject("configuration")
                .getAsJsonObject("taskCostMatrix").getAsJsonArray("entries"); e.add(e.get(0).deepCopy()); }, "duplicate");
    }

    @Test void topologySnapshotPreservesExplicitAndDefaultDeclarations() throws Exception {
        JsonObject root = snapshot(run(DataMovementModel.fatTreeContentionV1(), null, false));
        JsonObject topology = root.getAsJsonObject("platform").getAsJsonObject("networkTopology");
        assertEquals(4, topology.get("k").getAsInt());
        assertEquals(0.125, topology.get("linkBandwidthMbPerSecond").getAsDouble());
        assertEquals(2, topology.get("coreSwitchCount").getAsInt());
        assertEquals(0, topology.getAsJsonObject("hostEdgePlacements").get("3").getAsInt());
        assertEquals(2, topology.getAsJsonObject("hostEdgePlacements").get("8").getAsInt());
        ManifestV4Validator.validate(root);
        topology.add("coreSwitchCount", JsonNull.INSTANCE);
        topology.add("hostEdgePlacements", JsonNull.INSTANCE);
        ManifestV4Validator.validate(root);
    }

    @Test void rejectsMissingMalformedAndInconsistentV4Fields() throws Exception {
        JsonObject root = snapshot(run(DataMovementModel.fatTreeContentionV1(), null, false));
        reject(root, r -> r.getAsJsonObject("configuration").remove("workflowArrivalSeconds"), "workflowArrivalSeconds");
        reject(root, r -> r.getAsJsonObject("configuration").getAsJsonArray("workflowArrivalSeconds").add(0), "cover");
        reject(root, r -> r.getAsJsonObject("configuration").getAsJsonArray("workflowArrivalSeconds")
                .set(0, new com.google.gson.JsonPrimitive(-1)), "workflowArrivalSeconds");
        reject(root, r -> r.getAsJsonObject("configuration").getAsJsonArray("workflowArrivalSeconds")
                .set(0, JsonParser.parseString("1e999")), "finite");
        reject(root, r -> r.getAsJsonObject("configuration").remove("taskCostMatrix"), "taskCostMatrix");
        reject(root, r -> r.getAsJsonObject("platform").add("networkTopology", JsonNull.INSTANCE), "networkTopology");
        reject(root, r -> r.getAsJsonObject("platform").getAsJsonObject("networkTopology").addProperty("k", 3), "k");
        reject(root, r -> r.getAsJsonObject("platform").getAsJsonObject("networkTopology").addProperty("k", 4.5), "integer");
        reject(root, r -> r.getAsJsonObject("platform").getAsJsonObject("networkTopology").addProperty("coreSwitchCount", 5), "coreSwitchCount");
        reject(root, r -> r.getAsJsonObject("platform").getAsJsonObject("networkTopology").getAsJsonObject("hostEdgePlacements").remove("3"), "cover");
        reject(root, r -> r.getAsJsonObject("platform").getAsJsonObject("networkTopology").getAsJsonObject("hostEdgePlacements").addProperty("3", 99), "range");
        reject(root, r -> r.getAsJsonObject("configuration").getAsJsonObject("dataMovementModel")
                .addProperty("contentionSemantics", "SERIAL_PER_JOB_NO_SHARED_LINK_CONTENTION"), "contentionSemantics");
        reject(root, r -> r.getAsJsonObject("configuration").addProperty("vmCount", 2.2), "integer");
        reject(root, r -> r.getAsJsonObject("configuration").getAsJsonArray("workflowArrivalSeconds")
                .set(0, new com.google.gson.JsonPrimitive(1e9)), "differs from workflowOutcomes");
        reject(root, r -> r.getAsJsonObject("result").remove("workflowOutcomes"), "workflowOutcomes");
        reject(root, r -> r.getAsJsonObject("configuration").getAsJsonArray("workflowPaths")
                .set(0, new com.google.gson.JsonPrimitive("different.dax")), "workflowPaths differ");
    }

    @Test void dependencyGraphRejectsAsymmetricOrInventedEdges() throws Exception {
        JsonObject root = snapshot(run(DataMovementModel.legacyWorkflowsimV1(), null, false));
        assertEquals(5, root.getAsJsonArray("workflowGraph").size());
        reject(root, r -> r.getAsJsonArray("workflowGraph").get(0).getAsJsonObject()
                .getAsJsonArray("childIds").add(999), "workflowGraph");
        reject(root, r -> r.getAsJsonObject("workflowProfile").addProperty("edgeCount", 999), "counts");
        reject(root, r -> r.getAsJsonArray("workflowGraph").add(
                r.getAsJsonArray("workflowGraph").get(0).deepCopy()), "duplicate");
    }

    @Test void currentBundleAndTrueHistoricalV2V3ShapesRemainReadable(@TempDir Path output) throws Exception {
        SimulationReport report = run(DataMovementModel.legacyWorkflowsimV1(), null, false);
        ExperimentArtifactWriter.ExperimentArtifacts artifacts = ExperimentArtifactWriter.write(report, output, "v4");
        ExperimentArtifactValidator.validate(artifacts.getManifest());
        JsonObject original = JsonParser.parseString(new String(Files.readAllBytes(artifacts.getManifest()), StandardCharsets.UTF_8)).getAsJsonObject();
        JsonObject missing = original.deepCopy();
        missing.getAsJsonObject("configuration").remove("workflowArrivalSeconds");
        Files.write(artifacts.getManifest(), JSON.toJson(missing).getBytes(StandardCharsets.UTF_8));
        assertThrows(IOException.class, () -> ExperimentArtifactValidator.validate(artifacts.getManifest()));
        for (String version : Arrays.asList("v2", "v3")) {
            JsonObject historical = original.deepCopy();
            historical.addProperty("schema", "workflowsim-experiment-manifest-" + version);
            for (String field : Arrays.asList("workflowPaths", "workflowArrivalSeconds", "workflowArrivalSemantics", "taskCostMatrix")) {
                historical.getAsJsonObject("configuration").remove(field);
            }
            historical.getAsJsonObject("platform").remove("networkTopology");
            historical.getAsJsonObject("result").remove("workflowOutcomes");
            Files.write(artifacts.getManifest(), JSON.toJson(historical).getBytes(StandardCharsets.UTF_8));
            assertEquals(report.getEvents().size(), ExperimentArtifactValidator.validate(artifacts.getManifest()).getEventCount());
        }
    }

    private static void reject(JsonObject original, Consumer<JsonObject> change, String message) {
        JsonObject copy = original.deepCopy();
        change.accept(copy);
        IOException error = assertThrows(IOException.class, () -> ManifestV4Validator.validate(copy));
        assertTrue(error.getMessage().contains(message), error.getMessage());
    }

    private static JsonObject snapshot(SimulationReport report) {
        return JSON.toJsonTree(ExperimentManifestWriter.toManifest(report,
                Collections.<Map<String, Object>>emptyList())).getAsJsonObject();
    }

    private static SimulationReport run(DataMovementModel movement, TaskCostMatrix matrix,
            boolean multiple) throws Exception {
        Log.disable();
        String workflow = Paths.get(ManifestV4Test.class.getResource("/dax/reproducibility-workflow.dax").toURI()).toString();
        SimulationConfig.Builder config = multiple
                ? SimulationConfig.builder(Arrays.asList(workflow, workflow), 2)
                        .workflowArrivalSeconds(Arrays.asList(7.25, 0.0))
                : SimulationConfig.builder(workflow, 2);
        config.planningAlgorithm(Parameters.PlanningAlgorithm.RANDOM)
                .schedulingAlgorithm(Parameters.SchedulingAlgorithm.STATIC)
                .fileSystem(ReplicaCatalog.FileSystem.LOCAL).randomSeed(42)
                .dataMovementModel(movement).taskCostMatrix(matrix);
        PlatformProfile.Builder platform = PlatformProfile.builder("evidence-v4");
        for (int id : new int[] {3, 8}) {
            platform.addHost(new PlatformProfile.HostSpec(id, 2, 2000, 2048, 10000, 1000000));
            platform.addVm(new PlatformProfile.VmSpec(id, 1000, 1, 512, 1, 10000, "Xen",
                    PlatformProfile.CloudletSchedulerMode.SPACE_SHARED));
        }
        if (movement.isFatTreeContentionV1()) {
            Map<Integer, Integer> placements = new LinkedHashMap<Integer, Integer>();
            placements.put(8, 2); placements.put(3, 0);
            platform.networkTopology(NetworkTopologySpec.fatTree(4, 0.125, 2, placements));
        }
        return new SimulationRunner().run(config.build(), platform.build());
    }
}
