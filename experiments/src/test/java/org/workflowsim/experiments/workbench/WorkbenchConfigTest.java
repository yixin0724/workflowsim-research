package org.workflowsim.experiments.workbench;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.workflowsim.experiments.workbench.WorkbenchTestSupport.algorithm;
import static org.workflowsim.experiments.workbench.WorkbenchTestSupport.algorithms;
import static org.workflowsim.experiments.workbench.WorkbenchTestSupport.configuration;
import static org.workflowsim.experiments.workbench.WorkbenchTestSupport.costs;
import static org.workflowsim.experiments.workbench.WorkbenchTestSupport.json;
import static org.workflowsim.experiments.workbench.WorkbenchTestSupport.localConfiguration;
import static org.workflowsim.experiments.workbench.WorkbenchTestSupport.text;
import static org.workflowsim.experiments.workbench.WorkbenchTestSupport.topology;
import static org.workflowsim.experiments.workbench.WorkbenchTestSupport.workflow;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Locale;
import org.cloudbus.cloudsim.Log;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WorkbenchConfigTest {
    @TempDir Path directory;
    private boolean loggingWasDisabled;

    @BeforeEach
    void silenceSimulationLogging() {
        loggingWasDisabled = Log.isDisabled();
        Log.disable();
    }

    @AfterEach
    void restoreSimulationLogging() {
        Log.setDisabled(loggingWasDisabled);
    }

    @Test
    void resolvesInputsRelativeToConfigurationIncludingSpacesAndAcceptsAbsoluteInputs() throws Exception {
        Path input = workflow(directory.resolve("inputs"), "flow with spaces.dax", true);
        Path config = json(directory.resolve("configs/experiment.json"),
                configuration("../inputs/flow with spaces.dax", 2));
        assertDoesNotThrow(() -> WorkbenchConfig.read(config).validateInputs());

        json(config, configuration(input.toAbsolutePath().toString(), 2));
        assertDoesNotThrow(() -> WorkbenchConfig.read(config).validateInputs());
        Files.delete(input);
        IllegalArgumentException missing = assertThrows(IllegalArgumentException.class,
                () -> WorkbenchConfig.read(config));
        assertContains(missing, "does not exist");
        assertContains(missing, "flow with spaces.dax");
    }

    @Test
    void rejectsMissingDirectoryAndNonStringInputsBeforeSimulation() throws Exception {
        rejectRead(configuration("missing.dax", 1), "does not exist");
        Files.createDirectory(directory.resolve("input-directory"));
        rejectRead(configuration("input-directory", 1), "does not exist");
        JsonObject nonString = base();
        nonString.getAsJsonArray("workflowPaths").set(0, new JsonPrimitive(true));
        rejectRead(nonString, "workflowPaths must contain strings");
        JsonObject noInputs = base(); noInputs.add("workflowPaths", new JsonArray());
        rejectRead(noInputs, "workflowPaths requires");
    }

    @Test
    void rejectsMalformedDocumentsUnsupportedSchemaAndWrongContainerTypes() throws Exception {
        Path config = directory.resolve("config.json");
        for (String malformed : Arrays.asList("[]", "null", "{")) {
            text(config, malformed);
            assertThrows(IllegalArgumentException.class, () -> WorkbenchConfig.read(config), malformed);
        }
        JsonObject wrongSchema = base(); wrongSchema.addProperty("schema", "workbench-future-v99");
        rejectRead(wrongSchema, "schema");
        JsonObject wrongPlatform = base(); wrongPlatform.add("platform", JsonNull.INSTANCE);
        rejectRead(wrongPlatform, "platform must be an object");
        JsonObject wrongAlgorithms = base(); wrongAlgorithms.addProperty("algorithms", "FCFS");
        rejectRead(wrongAlgorithms, "algorithms must be an array");
        JsonObject wrongEntry = base(); wrongEntry.getAsJsonArray("algorithms").set(0, new JsonPrimitive("FCFS"));
        rejectRead(wrongEntry, "algorithms must contain objects");
    }

    @Test
    void rejectsDuplicateKeysCommentsAndUnquotedNames() throws Exception {
        Path config = json(directory.resolve("strict.json"), base());
        String valid = text(config);
        for (String invalid : Arrays.asList(valid.replace("\"schema\":", "\"name\":\"duplicate\",\"schema\":"),
                "/*comment*/" + valid, valid.replace("\"schema\":", "schema:"), valid + " {}")) {
            text(config, invalid);
            assertThrows(IllegalArgumentException.class, () -> WorkbenchConfig.read(config));
        }
    }

    @Test
    void rejectsUnknownFieldsAtEveryUserConfigurationLevel() throws Exception {
        JsonObject root = base(); root.addProperty("titel", "typo");
        rejectRead(root, "configuration has unknown field: titel");
        JsonObject platform = base(); platform.getAsJsonObject("platform").addProperty("vmCount", 2);
        rejectRead(platform, "platform has unknown field: vmCount");
        JsonObject simulation = base(); simulation.getAsJsonObject("simulation").addProperty("runtimeScael", 1);
        rejectRead(simulation, "simulation has unknown field: runtimeScael");
        JsonObject candidate = base(); candidate.getAsJsonArray("algorithms").get(0).getAsJsonObject().addProperty("schedulre", "FCFS");
        rejectRead(candidate, "algorithm has unknown field: schedulre");
        JsonObject network = local(true);
        network.getAsJsonObject("platform").getAsJsonObject("networkTopology").addProperty("linkBw", 1);
        rejectRead(network, "networkTopology has unknown field: linkBw");
        JsonObject fixed = fixed(); fixed.getAsJsonObject("simulation").getAsJsonObject("fixedEndpoint").addProperty("latency", 0);
        rejectRead(fixed, "fixedEndpoint has unknown field: latency");
        JsonObject matrix = matrix();
        matrix.getAsJsonObject("simulation").getAsJsonArray("taskCostMatrix").get(0).getAsJsonObject().addProperty("seconds", 1);
        rejectRead(matrix, "taskCostMatrix has unknown field: seconds");
    }

    @Test
    void rejectsCoercedNonfiniteAndNonpositiveNumericValues() throws Exception {
        for (JsonElement invalid : Arrays.<JsonElement>asList(
                new JsonPrimitive("1000"), new JsonPrimitive(true), JsonNull.INSTANCE,
                new JsonPrimitive(0), new JsonPrimitive(-1), new JsonPrimitive(new BigDecimal("1e999")))) {
            JsonObject root = base(); root.getAsJsonObject("platform").getAsJsonArray("vmMips").set(0, invalid);
            rejectRead(root, "vmMips");
        }
        JsonObject scale = base(); scale.getAsJsonObject("simulation").addProperty("runtimeScale", "1.0");
        rejectRead(scale, "runtimeScale");
        JsonObject cadence = base(); cadence.getAsJsonObject("simulation").addProperty("minEventIntervalSeconds", 0);
        rejectRead(cadence, "minEventIntervalSeconds");
        JsonObject cpuCost = base(); cpuCost.getAsJsonObject("platform").addProperty("cpuCostPerSecond", -1);
        rejectRead(cpuCost, "cpuCostPerSecond");
        JsonObject duration = matrix();
        duration.getAsJsonObject("simulation").getAsJsonArray("taskCostMatrix").get(0).getAsJsonObject().addProperty("executionSeconds", 0);
        rejectRead(duration, "executionSeconds");
        JsonObject finiteZero = base();
        finiteZero.getAsJsonObject("platform").addProperty("cpuCostPerSecond", 0);
        JsonArray arrivals = new JsonArray(); arrivals.add(0); finiteZero.add("workflowArrivalSeconds", arrivals);
        assertDoesNotThrow(() -> read(finiteZero).validateInputs(), "zero is valid for costs and arrival times");
    }

    @Test
    void rejectsFractionalAndOutOfRangeIntegerSettingsWithoutTruncation() throws Exception {
        JsonObject bandwidth = base(); bandwidth.getAsJsonObject("platform").addProperty("bandwidthMbPerSecond", 1.5);
        rejectRead(bandwidth, "bandwidthMbPerSecond");
        JsonObject storage = base(); storage.getAsJsonObject("platform").addProperty("storageTransferMbPerSecond", 2.5);
        rejectRead(storage, "storageTransferMbPerSecond");
        JsonObject deadline = base(); deadline.getAsJsonObject("simulation").addProperty("deadlineSeconds", 4.5);
        rejectRead(deadline, "deadlineSeconds");
        JsonObject k = local(true); k.getAsJsonObject("platform").getAsJsonObject("networkTopology").addProperty("k", 4.5);
        rejectRead(k, "k must be an exact integer");
        JsonObject cores = local(true); cores.getAsJsonObject("platform").getAsJsonObject("networkTopology").addProperty("coreSwitchCount", 2.5);
        rejectRead(cores, "coreSwitchCount");
        JsonObject placement = local(true); JsonObject hosts = new JsonObject(); hosts.addProperty("0", 0.5); hosts.addProperty("1", 1);
        placement.getAsJsonObject("platform").getAsJsonObject("networkTopology").add("hostEdgePlacements", hosts);
        rejectRead(placement, "hostEdgePlacements");
        for (String idField : Arrays.asList("taskId", "vmId")) {
            JsonObject matrix = matrix();
            matrix.getAsJsonObject("simulation").getAsJsonArray("taskCostMatrix").get(0).getAsJsonObject().addProperty(idField, 0.5);
            rejectRead(matrix, idField);
        }
        for (JsonElement seed : Arrays.<JsonElement>asList(new JsonPrimitive(1.5), new JsonPrimitive("17"),
                new JsonPrimitive(new BigInteger("9223372036854775808")))) {
            JsonObject root = base(); root.getAsJsonArray("seeds").set(0, seed);
            rejectRead(root, "seeds");
        }
        JsonObject oversized = base(); oversized.getAsJsonObject("platform").addProperty("bandwidthMbPerSecond", 1000000001L);
        rejectRead(oversized, "out of range");
    }

    @Test
    void rejectsArrivalCountAndDomainErrorsAndDuplicateSeeds() throws Exception {
        JsonObject mismatch = base(); JsonArray arrivals = new JsonArray(); arrivals.add(0); arrivals.add(1);
        mismatch.add("workflowArrivalSeconds", arrivals); rejectRead(mismatch, "must match workflowPaths");
        for (JsonElement arrival : Arrays.<JsonElement>asList(new JsonPrimitive(-0.1), new JsonPrimitive("0"),
                new JsonPrimitive(new BigDecimal("1e999")), JsonNull.INSTANCE)) {
            JsonObject root = base(); JsonArray one = new JsonArray(); one.add(arrival);
            root.add("workflowArrivalSeconds", one); rejectRead(root, "workflowArrivalSeconds");
        }
        JsonObject duplicates = base(); duplicates.getAsJsonArray("seeds").add(17L);
        rejectRead(duplicates, "distinct integers");
        JsonObject empty = base(); empty.add("seeds", new JsonArray());
        rejectRead(empty, "seeds requires");
    }

    @Test
    void permitsComparisonsWithinEachLayerAndRejectsMixedDecisionLayers() throws Exception {
        JsonObject online = base(); algorithms(online, algorithm("fcfs", "FCFS", null), algorithm("minmin", "READY_BATCH_MINMIN", null));
        assertDoesNotThrow(() -> read(online).validateInputs());
        JsonObject staticDag = base(); algorithms(staticDag, algorithm("heft", null, "SHARED_STORAGE_HEFT"), algorithm("cpop", null, "SHARED_STORAGE_CPOP"));
        assertDoesNotThrow(() -> read(staticDag).validateInputs());
        JsonObject mixed = base(); algorithms(mixed, algorithm("fcfs", "FCFS", null), algorithm("heft", null, "SHARED_STORAGE_HEFT"));
        rejectRead(mixed, "different decision layers");
        JsonObject independentAndDag = base(); algorithms(independentAndDag,
                algorithm("minmin", null, "STATIC_MINMIN"), algorithm("heft", null, "SHARED_STORAGE_HEFT"));
        rejectRead(independentAndDag, "different decision layers");
        JsonObject onlineAndIndependent = base(); algorithms(onlineAndIndependent,
                algorithm("fcfs", "FCFS", null), algorithm("minmin", null, "STATIC_MINMIN"));
        rejectRead(onlineAndIndependent, "different decision layers");
    }

    @Test
    void rejectsUnsafeDuplicateUnsupportedAndIncompatibleAlgorithmSelections() throws Exception {
        JsonObject unsafe = base(); algorithms(unsafe, algorithm("../escape", "FCFS", null));
        rejectRead(unsafe, "safe identifiers");
        JsonObject duplicates = base(); algorithms(duplicates, algorithm("same", "FCFS", null), algorithm("same", "READY_BATCH_MCT", null));
        rejectRead(duplicates, "unique");
        for (String scheduler : Arrays.asList("MINMIN", "RL_POLICY")) {
            JsonObject root = base(); algorithms(root, algorithm("candidate", scheduler, null));
            rejectRead(root, "maintained built-in algorithms");
        }
        JsonObject noPlanner = base(); algorithms(noPlanner, algorithm("static", "STATIC", null));
        rejectRead(noPlanner, "requires a planning algorithm");
        JsonObject localWithShared = base(); algorithms(localWithShared, algorithm("local", null, "LOCAL_HEFT"));
        rejectRead(localWithShared, "LOCAL");
        JsonObject noCandidates = base(); noCandidates.add("algorithms", new JsonArray());
        rejectRead(noCandidates, "needs 1..500 runs");
    }

    @Test
    void requiresTopologyAndFatTreeMovementTogetherAndValidatesPhysicalPlacement() throws Exception {
        JsonObject valid = local(true);
        JsonObject hosts = new JsonObject(); hosts.addProperty("0", 0); hosts.addProperty("1", 1);
        JsonObject topology = valid.getAsJsonObject("platform").getAsJsonObject("networkTopology");
        topology.addProperty("coreSwitchCount", 2); topology.add("hostEdgePlacements", hosts);
        assertDoesNotThrow(() -> read(valid).validateInputs());

        JsonObject missing = local(true); missing.getAsJsonObject("platform").remove("networkTopology");
        rejectRead(missing, "must be selected together");
        JsonObject unused = base(); unused.getAsJsonObject("platform").add("networkTopology", topology());
        rejectRead(unused, "must be selected together");
        JsonObject odd = local(true); odd.getAsJsonObject("platform").getAsJsonObject("networkTopology").addProperty("k", 3);
        rejectRead(odd, "even integer");
        JsonObject excessive = local(true); excessive.getAsJsonObject("platform").getAsJsonObject("networkTopology").addProperty("coreSwitchCount", 5);
        rejectRead(excessive, "core switch count");
        JsonObject incomplete = valid.deepCopy(); incomplete.getAsJsonObject("platform").getAsJsonObject("networkTopology").getAsJsonObject("hostEdgePlacements").remove("1");
        rejectRead(incomplete, "must cover host");
        JsonObject unknown = valid.deepCopy(); unknown.getAsJsonObject("platform").getAsJsonObject("networkTopology").getAsJsonObject("hostEdgePlacements").addProperty("2", 2);
        rejectRead(unknown, "unknown host");
        JsonObject edgeOutside = valid.deepCopy(); edgeOutside.getAsJsonObject("platform").getAsJsonObject("networkTopology").getAsJsonObject("hostEdgePlacements").addProperty("0", 8);
        rejectRead(edgeOutside, "edge switch index");
        JsonObject nonCanonical = valid.deepCopy(); nonCanonical.getAsJsonObject("platform").getAsJsonObject("networkTopology").getAsJsonObject("hostEdgePlacements").addProperty("01", 1);
        rejectRead(nonCanonical, "Invalid host placement ID");

        JsonObject overloaded = localConfiguration("workflow.dax", 3, true);
        JsonObject allOnOneEdge = new JsonObject();
        for (int host = 0; host < 3; host++) { allOnOneEdge.addProperty(Integer.toString(host), 0); }
        overloaded.getAsJsonObject("platform").getAsJsonObject("networkTopology").add("hostEdgePlacements", allOnOneEdge);
        rejectRead(overloaded, "hosts at most");
    }

    @Test
    void fixedEndpointParametersRequireTheMatchingModelAndPositiveBandwidths() throws Exception {
        JsonObject valid = fixed(); assertDoesNotThrow(() -> read(valid).validateInputs());
        JsonObject unused = fixed(); unused.getAsJsonObject("simulation").addProperty("dataMovementModel", "LEGACY_WORKFLOWSIM_V1");
        rejectRead(unused, "fixedEndpoint parameters require");
        JsonObject missing = fixed(); missing.getAsJsonObject("simulation").remove("fixedEndpoint");
        rejectRead(missing, "fixedEndpoint must be an object");
        JsonObject zero = fixed(); zero.getAsJsonObject("simulation").getAsJsonObject("fixedEndpoint").addProperty("accessBandwidth", 0);
        rejectRead(zero, "accessBandwidth");
    }

    @Test
    void matrixMustCoverEveryParsedTaskAndVmAndRejectDuplicateOrMissingCells() throws Exception {
        JsonObject complete = matrix(); assertDoesNotThrow(() -> read(complete).validateInputs());
        JsonObject missingVm = matrix(); missingVm.getAsJsonObject("simulation").getAsJsonArray("taskCostMatrix").remove(1);
        rejectInputs(missingVm, "cover every parsed task and VM");
        JsonObject missingTask = matrix(); JsonArray entries = missingTask.getAsJsonObject("simulation").getAsJsonArray("taskCostMatrix");
        entries.remove(3); entries.remove(2); rejectInputs(missingTask, "cover every parsed task and VM");
        JsonObject duplicate = matrix(); JsonArray repeated = duplicate.getAsJsonObject("simulation").getAsJsonArray("taskCostMatrix");
        repeated.add(repeated.get(0).deepCopy()); rejectRead(duplicate, "Duplicate task cost matrix entry");
        JsonObject empty = matrix(); empty.getAsJsonObject("simulation").add("taskCostMatrix", new JsonArray());
        rejectRead(empty, "must contain at least one entry");
        JsonObject online = matrix(); algorithms(online, algorithm("fcfs", "FCFS", null));
        rejectRead(online, "STATIC");
    }

    @Test
    void independentPlannersRejectEdgesWhileDagPlannersAcceptTheSameInput() throws Exception {
        JsonObject independent = base(); algorithms(independent, algorithm("minmin", null, "STATIC_MINMIN"));
        rejectInputs(independent, "cannot accept DAG edges");
        JsonObject dag = base(); algorithms(dag, algorithm("heft", null, "SHARED_STORAGE_HEFT"));
        assertDoesNotThrow(() -> read(dag).validateInputs());
        workflow(directory, "independent.dax", false);
        JsonObject allowed = configuration("independent.dax", 2);
        algorithms(allowed, algorithm("minmin", null, "STATIC_MINMIN"), algorithm("maxmin", null, "STATIC_MAXMIN"));
        assertDoesNotThrow(() -> read(allowed).validateInputs());
    }

    @Test
    void inputValidationRejectsCyclesUnsupportedFormatsAndAmbiguousLocalFiles() throws Exception {
        Path cycle = text(directory.resolve("cycle.dax"), "<adag version=\"3.3\">"
                + "<job id=\"a\" runtime=\"1\"/><job id=\"b\" runtime=\"1\"/>"
                + "<child ref=\"a\"><parent ref=\"b\"/></child>"
                + "<child ref=\"b\"><parent ref=\"a\"/></child></adag>");
        rejectInputs(configuration(cycle.getFileName().toString(), 1), "cycle");
        text(directory.resolve("workflow.txt"), "not a supported workflow extension");
        rejectInputs(configuration("workflow.txt", 1), "Unsupported workflow input format");
        Path ambiguous = workflow(directory, "ambiguous.dax", true);
        text(ambiguous, text(ambiguous).replace("link=\"input\" size=\"1000000\"", "link=\"input\" size=\"2000000\""));
        rejectInputs(localConfiguration("ambiguous.dax", 2, false), "unambiguous file sizes");
    }

    private JsonObject base() throws Exception {
        workflow(directory, "workflow.dax", true);
        return configuration("workflow.dax", 2);
    }

    private JsonObject local(boolean fatTree) throws Exception {
        base(); return localConfiguration("workflow.dax", 2, fatTree);
    }

    private JsonObject fixed() throws Exception {
        JsonObject root = base(); JsonObject sim = root.getAsJsonObject("simulation");
        sim.addProperty("dataMovementModel", "FIXED_ENDPOINT_NO_CONTENTION_V1");
        JsonObject fixed = new JsonObject(); fixed.addProperty("accessBandwidth", 10.0);
        fixed.addProperty("sourceBandwidth", 10.0); fixed.addProperty("latencySeconds", 0.0);
        sim.add("fixedEndpoint", fixed); return root;
    }

    private JsonObject matrix() throws Exception {
        JsonObject root = base(); algorithms(root, algorithm("random", null, "RANDOM"));
        root.getAsJsonObject("simulation").add("taskCostMatrix", costs(2, 2, 1.0));
        return root;
    }

    private WorkbenchConfig read(JsonObject root) throws Exception {
        return WorkbenchConfig.read(json(directory.resolve("config.json"), root));
    }

    private void rejectRead(JsonObject root, String message) throws Exception {
        Path config = json(directory.resolve("config.json"), root);
        assertContains(assertThrows(IllegalArgumentException.class, () -> WorkbenchConfig.read(config)), message);
    }

    private void rejectInputs(JsonObject root, String message) throws Exception {
        WorkbenchConfig config = read(root);
        assertContains(assertThrows(IllegalArgumentException.class, config::validateInputs), message);
    }

    private static void assertContains(Throwable failure, String message) {
        assertTrue(failure.getMessage().toLowerCase(Locale.ROOT).contains(message.toLowerCase(Locale.ROOT)),
                "Expected diagnostic containing '" + message + "', got: " + failure.getMessage());
    }
}
