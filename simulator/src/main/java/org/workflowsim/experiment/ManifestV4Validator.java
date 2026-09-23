package org.workflowsim.experiment;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import org.workflowsim.data.DataMovementModel;

/** Strict checks of the complete v4 configuration snapshot; v2/v3 remain readable. */
final class ManifestV4Validator {
    private ManifestV4Validator() { }

    static void validate(JsonObject root) throws IOException {
        JsonObject config = object(root, "configuration");
        JsonObject platform = object(root, "platform");
        JsonArray inputs = array(root, "inputs");
        JsonArray paths = array(config, "workflowPaths");
        JsonArray arrivals = array(config, "workflowArrivalSeconds");
        if (inputs.size() == 0 || arrivals.size() != inputs.size() || paths.size() != inputs.size()) {
            throw new IOException("configuration.workflowArrivalSeconds/workflowPaths must cover every input");
        }
        JsonArray outcomes = array(object(root, "result"), "workflowOutcomes");
        if (outcomes.size() != inputs.size()) { throw new IOException("workflowOutcomes must cover every input"); }
        String workingDirectory = text(required(object(object(root, "provenance"), "execution"), "workingDirectory"), "workingDirectory");
        for (int i = 0; i < arrivals.size(); i++) {
            double arrival = number(arrivals.get(i), "workflowArrivalSeconds[" + i + "]", false);
            String configuredPath = text(paths.get(i), "workflowPaths[" + i + "]");
            if (!inputs.get(i).isJsonObject() || !outcomes.get(i).isJsonObject()) { throw new IOException("inputs/workflowOutcomes must contain objects"); }
            JsonObject input = inputs.get(i).getAsJsonObject();
            JsonObject outcome = outcomes.get(i).getAsJsonObject();
            try {
                java.nio.file.Path configured = java.nio.file.Paths.get(workingDirectory).resolve(configuredPath).normalize();
                java.nio.file.Path consumed = java.nio.file.Paths.get(text(required(input, "path"), "input.path")).normalize();
                java.nio.file.Path resultPath = java.nio.file.Paths.get(workingDirectory).resolve(text(required(outcome, "path"), "workflowOutcomes.path")).normalize();
                if (!configured.equals(consumed) || !configured.equals(resultPath)) { throw new IOException("workflowPaths differ from inputs/workflowOutcomes"); }
            } catch (java.nio.file.InvalidPathException invalid) { throw new IOException("Invalid workflow path", invalid); }
            if (integer(required(outcome, "index"), "workflowOutcomes.index") != i
                    || Double.compare(arrival, number(required(outcome, "arrivalSecond"), "workflowOutcomes.arrivalSecond", false)) != 0) {
                throw new IOException("workflowArrivalSeconds differs from workflowOutcomes");
            }
            JsonElement finish = required(outcome, "lastSuccessFinishSecond");
            if (!finish.isJsonNull() && number(finish, "workflowOutcomes.lastSuccessFinishSecond", false) < arrival) {
                throw new IOException("workflowOutcomes completion precedes arrival");
            }
        }
        equalText(config, "workflowArrivalSemantics",
                "PREDECLARED_AT_TIME_ZERO;SECONDS_FROM_SIMULATION_ZERO");
        JsonArray hosts = array(platform, "hosts");
        JsonArray vms = array(platform, "vms");
        Set<Integer> hostIds = identifiers(hosts, "hosts");
        Set<Integer> vmIds = identifiers(vms, "vms");
        if (hostIds.isEmpty() || vmIds.isEmpty()
                || integer(required(config, "vmCount"), "configuration.vmCount") != vms.size()
                || integer(required(platform, "vmCount"), "platform.vmCount") != vms.size()
                || integer(required(platform, "hostCount"), "platform.hostCount") != hosts.size()) {
            throw new IOException("configuration/platform resource counts are inconsistent");
        }
        validateMatrix(config, root, vmIds);
        DataMovementModel model = readMovement(object(config, "dataMovementModel"));
        validateTopology(required(platform, "networkTopology"), model, hostIds);
        if (root.has("workflowGraph")) { validateGraph(root); }
    }

    private static void validateGraph(JsonObject root) throws IOException {
        JsonArray graph = array(root, "workflowGraph");
        Map<Integer, JsonObject> nodes = new LinkedHashMap<Integer, JsonObject>();
        for (JsonElement item : graph) {
            if (!item.isJsonObject()) { throw new IOException("workflowGraph nodes must be objects"); }
            JsonObject node = item.getAsJsonObject();
            int id = integer(required(node, "taskId"), "workflowGraph.taskId");
            if (nodes.put(id, node) != null) { throw new IOException("workflowGraph has duplicate taskId"); }
            integer(required(node, "depth"), "workflowGraph.depth");
        }
        int edges = 0;
        for (Map.Entry<Integer, JsonObject> entry : nodes.entrySet()) {
            int id = entry.getKey(); JsonObject node = entry.getValue();
            for (String direction : new String[] {"parentIds", "childIds"}) {
                Set<Integer> unique = new HashSet<Integer>();
                for (JsonElement neighbor : array(node, direction)) {
                    int otherId = integer(neighbor, "workflowGraph." + direction);
                    JsonObject other = nodes.get(otherId);
                    if (other == null || id == otherId || !unique.add(otherId)) {
                        throw new IOException("workflowGraph has unknown/self/duplicate edge");
                    }
                    boolean reciprocal = false;
                    for (JsonElement reverse : array(other, "parentIds".equals(direction) ? "childIds" : "parentIds")) {
                        if (integer(reverse, "workflowGraph edge") == id) { reciprocal = true; }
                    }
                    if (!reciprocal) { throw new IOException("workflowGraph edge must be symmetric"); }
                    if ("childIds".equals(direction)) {
                        edges++;
                        if (other.get("depth").getAsInt() <= node.get("depth").getAsInt()) {
                            throw new IOException("workflowGraph edge must increase depth");
                        }
                    }
                }
            }
        }
        JsonObject profile = object(root, "workflowProfile");
        if (integer(required(profile, "taskCount"), "workflowProfile.taskCount") != nodes.size()
                || integer(required(profile, "edgeCount"), "workflowProfile.edgeCount") != edges) {
            throw new IOException("workflowGraph counts differ from workflowProfile");
        }
    }

    private static void validateMatrix(JsonObject config, JsonObject root, Set<Integer> vmIds)
            throws IOException {
        JsonElement element = required(config, "taskCostMatrix");
        if (element.isJsonNull()) {
            return;
        }
        if (!element.isJsonObject()) {
            throw new IOException("configuration.taskCostMatrix must be an object or null");
        }
        JsonObject matrix = element.getAsJsonObject();
        equalText(matrix, "unit", "EXECUTION_SECONDS");
        equalText(matrix, "runtimeConversion", "ROUND_SECONDS_TIMES_VM_MIPS_TO_POSITIVE_INTEGER_MI");
        JsonArray entries = array(matrix, "entries");
        if (entries.size() == 0) {
            throw new IOException("taskCostMatrix.entries cannot be empty");
        }
        Set<String> coordinates = new HashSet<String>();
        for (JsonElement item : entries) {
            if (!item.isJsonObject()) {
                throw new IOException("taskCostMatrix.entries must contain objects");
            }
            JsonObject entry = item.getAsJsonObject();
            int task = integer(required(entry, "taskId"), "taskCostMatrix.taskId");
            int vm = integer(required(entry, "vmId"), "taskCostMatrix.vmId");
            number(required(entry, "executionSeconds"), "taskCostMatrix.executionSeconds", true);
            if (!coordinates.add(task + ":" + vm)) {
                throw new IOException("taskCostMatrix has duplicate task/VM coordinate");
            }
        }
        Set<Integer> tasks = new HashSet<Integer>();
        for (JsonElement item : array(object(root, "result"), "tasks")) {
            if (!item.isJsonObject()) {
                throw new IOException("result.tasks must contain objects");
            }
            tasks.add(integer(required(item.getAsJsonObject(), "taskId"), "result.tasks.taskId"));
        }
        for (Integer task : tasks) {
            for (Integer vm : vmIds) {
                if (!coordinates.contains(task + ":" + vm)) {
                    throw new IOException("taskCostMatrix is missing task " + task + " / VM " + vm);
                }
            }
        }
        equalText(config, "schedulingAlgorithm", "STATIC");
        equalText(object(config, "clustering"), "method", "NONE");
    }

    private static DataMovementModel readMovement(JsonObject value) throws IOException {
        String kind = text(required(value, "kind"), "dataMovementModel.kind");
        DataMovementModel model;
        try {
            switch (DataMovementModel.Kind.valueOf(kind)) {
                case LEGACY_WORKFLOWSIM_V1:
                    model = DataMovementModel.legacyWorkflowsimV1(); break;
                case FIXED_ENDPOINT_NO_CONTENTION_V1:
                    model = DataMovementModel.fixedEndpointNoContention(
                            number(required(value, "accessLinkBandwidthMbPerSecond"),
                                    "dataMovementModel.accessLinkBandwidthMbPerSecond", true),
                            number(required(value, "accessLinkLatencySeconds"),
                                    "dataMovementModel.accessLinkLatencySeconds", false),
                            number(required(value, "sourceEndpointBandwidthMbPerSecond"),
                                    "dataMovementModel.sourceEndpointBandwidthMbPerSecond", true)); break;
                case PRE_EXECUTION_TRANSFER_DELAY_V1:
                    model = DataMovementModel.preExecutionTransferDelayV1(); break;
                case PRE_EXECUTION_TRANSFER_DELAY_WITH_CONTENTION_V1:
                    model = DataMovementModel.preExecutionTransferDelayWithContentionV1(); break;
                case PRE_EXECUTION_TRANSFER_DELAY_WITH_FAT_TREE_CONTENTION_V1:
                    model = DataMovementModel.fatTreeContentionV1(); break;
                default: throw new IllegalArgumentException(kind);
            }
        } catch (IllegalArgumentException exception) {
            throw new IOException("Invalid dataMovementModel.kind/parameters: " + kind, exception);
        }
        equalText(value, "contentionSemantics", ExperimentManifestWriter.contentionSemantics(model));
        equalText(value, "transferStartSemantics", ExperimentManifestWriter.transferStartSemantics(model));
        equalText(value, "bandwidthUnit", "DECIMAL_MB_PER_SECOND");
        return model;
    }

    private static void validateTopology(JsonElement element, DataMovementModel model,
            Set<Integer> hostIds) throws IOException {
        if (element.isJsonNull()) {
            if (model.isFatTreeContentionV1()) {
                throw new IOException("platform.networkTopology is required by Fat-tree data movement");
            }
            return;
        }
        if (!model.isFatTreeContentionV1() || !element.isJsonObject()) {
            throw new IOException("platform.networkTopology requires Fat-tree data movement");
        }
        JsonObject value = element.getAsJsonObject();
        equalText(value, "kind", "FAT_TREE");
        int k = integer(required(value, "k"), "networkTopology.k");
        number(required(value, "linkBandwidthMbPerSecond"), "networkTopology.linkBandwidthMbPerSecond", true);
        JsonElement core = required(value, "coreSwitchCount");
        Integer cores = core.isJsonNull() ? null
                : Integer.valueOf(integer(core, "networkTopology.coreSwitchCount"));
        JsonElement placement = required(value, "hostEdgePlacements");
        Map<Integer, Integer> placements = null;
        if (!placement.isJsonNull()) {
            if (!placement.isJsonObject()) {
                throw new IOException("networkTopology.hostEdgePlacements must be an object or null");
            }
            placements = new LinkedHashMap<Integer, Integer>();
            for (Map.Entry<String, JsonElement> entry : placement.getAsJsonObject().entrySet()) {
                int host;
                try {
                    host = Integer.parseInt(entry.getKey());
                    if (host < 0 || !Integer.toString(host).equals(entry.getKey())) {
                        throw new NumberFormatException();
                    }
                } catch (NumberFormatException exception) {
                    throw new IOException("networkTopology.hostEdgePlacements has invalid host ID", exception);
                }
                placements.put(host, integer(entry.getValue(), "networkTopology.hostEdgePlacements.edge"));
            }
        }
        equalText(value, "defaultPlacementPolicy", "HOST_ID_ASCENDING_ROUND_ROBIN_OVER_EDGES");
        equalText(value, "routingPolicy", "DETERMINISTIC_AL_FARES_FAT_TREE_V1");
        equalText(value, "linkDirectionality", "INDEPENDENT_DIRECTED_LINKS");
        equalText(value, "externalSourceRouting", "BYPASS_TOPOLOGY_DESTINATION_ENDPOINT_ONLY");
        // Check capacities arithmetically: validation must not allocate an untrusted topology.
        if (k < 2 || k % 2 != 0) {
            throw new IOException("networkTopology.k must be even and >= 2");
        }
        long edgeCount = (long) k * k / 2;
        long coreCount = (long) k * k / 4;
        if (edgeCount > Integer.MAX_VALUE || coreCount > Integer.MAX_VALUE
                || (cores != null && (cores < 1 || cores > coreCount))
                || hostIds.size() > (double) k * k * k / 4.0) {
            throw new IOException("networkTopology capacity/coreSwitchCount is invalid");
        }
        if (placements != null) {
            if (!placements.keySet().equals(hostIds)) {
                throw new IOException("networkTopology.hostEdgePlacements must cover exactly the declared hosts");
            }
            Map<Integer, Integer> loads = new LinkedHashMap<Integer, Integer>();
            for (Integer edge : placements.values()) {
                if (edge >= edgeCount) {
                    throw new IOException("networkTopology.hostEdgePlacements edge is out of range");
                }
                int load = loads.containsKey(edge) ? loads.get(edge) + 1 : 1;
                loads.put(edge, load);
                if (load > k / 2) {
                    throw new IOException("networkTopology.hostEdgePlacements overloads an edge");
                }
            }
        }
    }

    private static Set<Integer> identifiers(JsonArray array, String subject) throws IOException {
        Set<Integer> ids = new HashSet<Integer>();
        for (JsonElement item : array) {
            if (!item.isJsonObject()
                    || !ids.add(integer(required(item.getAsJsonObject(), "id"), subject + ".id"))) {
                throw new IOException(subject + " must contain unique resource IDs");
            }
        }
        return ids;
    }

    private static JsonElement required(JsonObject value, String key) throws IOException {
        if (!value.has(key)) {
            throw new IOException("v4 manifest is missing " + key);
        }
        return value.get(key);
    }

    private static JsonObject object(JsonObject value, String key) throws IOException {
        JsonElement element = required(value, key);
        if (!element.isJsonObject()) {
            throw new IOException(key + " must be an object");
        }
        return element.getAsJsonObject();
    }

    private static JsonArray array(JsonObject value, String key) throws IOException {
        JsonElement element = required(value, key);
        if (!element.isJsonArray()) {
            throw new IOException(key + " must be an array");
        }
        return element.getAsJsonArray();
    }

    private static String text(JsonElement value, String field) throws IOException {
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()
                || value.getAsString().trim().isEmpty()) {
            throw new IOException(field + " must be a non-empty string");
        }
        return value.getAsString();
    }

    private static void equalText(JsonObject value, String key, String expected) throws IOException {
        if (!expected.equals(text(required(value, key), key))) {
            throw new IOException(key + " must be " + expected);
        }
    }

    private static double number(JsonElement value, String field, boolean positive) throws IOException {
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) {
            throw new IOException(field + " must be a number");
        }
        double result = value.getAsDouble();
        if (!Double.isFinite(result) || (positive ? result <= 0.0 : result < 0.0)) {
            throw new IOException(field + " must be finite and " + (positive ? "positive" : "non-negative"));
        }
        return result;
    }

    private static int integer(JsonElement value, String field) throws IOException {
        number(value, field, false);
        try {
            return value.getAsBigDecimal().intValueExact();
        } catch (ArithmeticException | NumberFormatException exception) {
            throw new IOException(field + " must be an exact non-negative integer", exception);
        }
    }
}
