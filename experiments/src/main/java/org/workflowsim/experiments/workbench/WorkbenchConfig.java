package org.workflowsim.experiments.workbench;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.workflowsim.WorkflowParser;
import org.workflowsim.Task;
import org.workflowsim.FileItem;
import org.workflowsim.data.DataMovementModel;
import org.workflowsim.experiment.AlgorithmCatalog;
import org.workflowsim.network.FatTreeTopology;
import org.workflowsim.network.NetworkTopologySpec;
import org.workflowsim.platform.PlatformProfile;
import org.workflowsim.utils.Parameters;
import org.workflowsim.utils.ReplicaCatalog;
import org.workflowsim.utils.SimulationConfig;
import org.workflowsim.utils.SimulationSession;
import org.workflowsim.utils.TaskCostMatrix;

/** Strict, deliberately bounded user-facing configuration; defaults are made explicit in v4 evidence. */
public final class WorkbenchConfig {
    public static final String SCHEMA = "workflowsim-workbench-v1";
    private final String name;
    private final JsonObject source;
    private final PlatformProfile platform;
    private final List<Candidate> candidates;
    private final List<Long> seeds;

    private WorkbenchConfig(String name, JsonObject source, PlatformProfile platform,
            List<Candidate> candidates, List<Long> seeds) {
        this.name = name; this.source = source; this.platform = platform;
        this.candidates = Collections.unmodifiableList(candidates);
        this.seeds = Collections.unmodifiableList(seeds);
    }

    public static WorkbenchConfig read(Path path) throws IOException {
        JsonObject root;
        try (com.google.gson.stream.JsonReader reader = new com.google.gson.stream.JsonReader(
                Files.newBufferedReader(path, StandardCharsets.UTF_8))) {
            reader.setLenient(false);
            root = readJson(reader, 0).getAsJsonObject();
            if (reader.peek() != com.google.gson.stream.JsonToken.END_DOCUMENT) {
                throw new IllegalArgumentException("Trailing JSON content");
            }
        } catch (RuntimeException | com.google.gson.stream.MalformedJsonException | java.io.EOFException error) {
            throw new IllegalArgumentException("配置必须是严格 JSON 对象，字段不能重复", error);
        }
        fields(root, "configuration", "schema", "name", "workflowPaths", "workflowArrivalSeconds", "platform", "simulation", "algorithms", "seeds");
        if (!SCHEMA.equals(text(root, "schema", null))) { throw new IllegalArgumentException("Unsupported configuration schema"); }
        String name = text(root, "name", "工作流实验");
        if (name.length() > 128) { throw new IllegalArgumentException("Experiment name is too long"); }
        JsonArray inputs = array(root, "workflowPaths");
        if (inputs.size() < 1 || inputs.size() > 32) { throw new IllegalArgumentException("workflowPaths requires 1..32 inputs"); }
        List<String> paths = new ArrayList<String>();
        for (JsonElement input : inputs) {
            if (!input.isJsonPrimitive() || !input.getAsJsonPrimitive().isString()) { throw new IllegalArgumentException("workflowPaths must contain strings"); }
            Path resolved = path.toAbsolutePath().getParent().resolve(input.getAsString()).normalize();
            if (!Files.isRegularFile(resolved)) { throw new IllegalArgumentException("Workflow input does not exist: " + resolved); }
            paths.add(resolved.toString());
        }
        List<Double> arrivals = new ArrayList<Double>();
        if (root.has("workflowArrivalSeconds")) {
            for (JsonElement value : array(root, "workflowArrivalSeconds")) { arrivals.add(number(value, "workflowArrivalSeconds", false)); }
        } else { for (String ignored : paths) { arrivals.add(0.0); } }
        if (arrivals.size() != paths.size()) { throw new IllegalArgumentException("workflowArrivalSeconds must match workflowPaths"); }
        PlatformProfile platform = platform(object(root, "platform"));
        JsonObject sim = root.has("simulation") ? object(root, "simulation") : new JsonObject();
        fields(sim, "simulation", "fileSystem", "dataMovementModel", "runtimeScale", "runtimeReferenceMips", "minEventIntervalSeconds", "deadlineSeconds", "taskCostMatrix", "fixedEndpoint");
        ReplicaCatalog.FileSystem fs = ReplicaCatalog.FileSystem.valueOf(text(sim, "fileSystem", "SHARED"));
        DataMovementModel movement = movement(sim);
        if (movement.isFatTreeContentionV1() != (platform.getNetworkTopology() != null)) { throw new IllegalArgumentException("Fat-tree data movement and networkTopology must be selected together"); }
        List<Long> seeds = new ArrayList<Long>();
        if (root.has("seeds")) {
            for (JsonElement value : array(root, "seeds")) { seeds.add(longValue(value, "seeds")); }
        } else { seeds.add(42L); }
        if (seeds.isEmpty() || seeds.size() > 100 || new HashSet<Long>(seeds).size() != seeds.size()) { throw new IllegalArgumentException("seeds requires 1..100 distinct integers"); }
        List<Candidate> candidates = new ArrayList<Candidate>();
        Set<String> ids = new HashSet<String>();
        String track = null;
        for (JsonElement item : array(root, "algorithms")) {
            if (!item.isJsonObject()) { throw new IllegalArgumentException("algorithms must contain objects"); }
            JsonObject algorithm = item.getAsJsonObject();
            fields(algorithm, "algorithm", "id", "scheduler", "planner");
            String id = text(algorithm, "id", null);
            if (!id.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,63}") || !ids.add(id)) { throw new IllegalArgumentException("Algorithm IDs must be unique safe identifiers"); }
            Parameters.PlanningAlgorithm planner = Parameters.PlanningAlgorithm.valueOf(text(algorithm, "planner", "INVALID"));
            Parameters.SchedulingAlgorithm scheduler = Parameters.SchedulingAlgorithm.valueOf(text(algorithm, "scheduler", planner == Parameters.PlanningAlgorithm.INVALID ? "FCFS" : "STATIC"));
            if (!AlgorithmCatalog.isSupportedBySimulationRunner(planner) || !AlgorithmCatalog.isSupportedBySimulationRunner(scheduler)
                    || scheduler == Parameters.SchedulingAlgorithm.RL_POLICY) {
                throw new IllegalArgumentException("Workbench requires maintained built-in algorithms; external RL policies use the Java RlEnvironment adapter");
            }
            String candidateTrack = planner == Parameters.PlanningAlgorithm.INVALID ? "ONLINE"
                    : planner.name().startsWith("STATIC_") ? "INDEPENDENT" : "DAG_STATIC";
            if (track != null && !track.equals(candidateTrack)) { throw new IllegalArgumentException("Cannot compare different decision layers in one experiment"); }
            track = candidateTrack;
            SimulationConfig config = SimulationConfig.builder(paths, platform.getVms().size())
                    .workflowArrivalSeconds(arrivals).fileSystem(fs).dataMovementModel(movement)
                    .schedulingAlgorithm(scheduler).planningAlgorithm(planner)
                    .randomSeed(seeds.get(0)).runtimeScale(decimal(sim, "runtimeScale", 1, true))
                    .runtimeReferenceMips(decimal(sim, "runtimeReferenceMips", 1000, true))
                    .cloudSimMinEventIntervalSeconds(decimal(sim, "minEventIntervalSeconds", 0.1, true))
                    .deadline(sim.has("deadlineSeconds") ? longValue(sim.get("deadlineSeconds"), "deadlineSeconds") : 0)
                    .taskCostMatrix(matrix(sim)).build();
            candidates.add(new Candidate(id, config));
        }
        if (candidates.isEmpty() || candidates.size() * seeds.size() > 500) { throw new IllegalArgumentException("An experiment needs 1..500 runs"); }
        return new WorkbenchConfig(name, root.deepCopy(), platform, candidates, seeds);
    }

    /** Parse input DAGs and validate model-specific prerequisites without running the simulation. */
    public void validateInputs() {
        for (Candidate candidate : candidates) {
            try (SimulationSession ignored = SimulationSession.open(candidate.config)) {
                WorkflowParser parser = new WorkflowParser(0); parser.parse();
                List<Integer> taskIds = new ArrayList<Integer>();
                List<Integer> vmIds = new ArrayList<Integer>();
                for (PlatformProfile.VmSpec vm : platform.getVms()) { vmIds.add(vm.getId()); }
                Map<String, Double> sizes = new LinkedHashMap<String, Double>();
                boolean localPlanner = candidate.config.getPlanningAlgorithm().name().startsWith("LOCAL_");
                boolean independent = candidate.config.getPlanningAlgorithm().name().startsWith("STATIC_");
                for (Task task : parser.getTaskList()) {
                    taskIds.add(task.getCloudletId());
                    if (independent && (!task.getParentList().isEmpty() || !task.getChildList().isEmpty())) { throw new IllegalArgumentException("Independent-task planner cannot accept DAG edges"); }
                    if (localPlanner) {
                        for (FileItem file : task.getFileList()) {
                            Double before = sizes.put(file.getName(), file.getSize());
                            if (before != null && Double.compare(before, file.getSize()) != 0) { throw new IllegalArgumentException("LOCAL planner requires unambiguous file sizes: " + file.getName()); }
                        }
                    }
                }
                if (candidate.config.getTaskCostMatrix() != null && !candidate.config.getTaskCostMatrix().covers(taskIds, vmIds)) { throw new IllegalArgumentException("taskCostMatrix must cover every parsed task and VM"); }
            }
        }
    }

    private static TaskCostMatrix matrix(JsonObject sim) {
        if (!sim.has("taskCostMatrix") || sim.get("taskCostMatrix").isJsonNull()) { return null; }
        TaskCostMatrix.Builder builder = TaskCostMatrix.builder();
        for (JsonElement value : array(sim, "taskCostMatrix")) {
            if (!value.isJsonObject()) { throw new IllegalArgumentException("taskCostMatrix entries must be objects"); }
            JsonObject entry = value.getAsJsonObject(); fields(entry, "taskCostMatrix", "taskId", "vmId", "executionSeconds");
            builder.put(integer(entry, "taskId", -1, 0, Integer.MAX_VALUE), integer(entry, "vmId", -1, 0, Integer.MAX_VALUE), decimal(entry, "executionSeconds", -1, true));
        }
        return builder.build();
    }

    private static PlatformProfile platform(JsonObject value) {
        fields(value, "platform", "vmMips", "bandwidthMbPerSecond", "cpuCostPerSecond", "storageTransferMbPerSecond", "networkTopology");
        JsonArray mips = array(value, "vmMips");
        if (mips.size() < 1 || mips.size() > 64) { throw new IllegalArgumentException("vmMips requires 1..64 VMs"); }
        int bandwidth = integer(value, "bandwidthMbPerSecond", 1000, 1, 1000000000);
        PlatformProfile.CostSpec costs = new PlatformProfile.CostSpec(decimal(value, "cpuCostPerSecond", 3, false), 0, 0, 0);
        PlatformProfile.Builder result = PlatformProfile.builder("workbench-platform").costs(costs)
                .storage(new PlatformProfile.StorageSpec(1000000000L, integer(value, "storageTransferMbPerSecond", 15, 1, Integer.MAX_VALUE)));
        for (int id = 0; id < mips.size(); id++) {
            double speed = number(mips.get(id), "vmMips", true);
            if (speed > 1e12) { throw new IllegalArgumentException("vmMips exceeds workbench limit"); }
            result.addHost(new PlatformProfile.HostSpec(id, 2, speed * 2, 2048, Math.max(10000L, bandwidth), 1000000));
            result.addVm(new PlatformProfile.VmSpec(id, speed, 1, 512, bandwidth, 10000, "Xen", PlatformProfile.CloudletSchedulerMode.SPACE_SHARED));
            result.pinVmToHost(id, id);
        }
        if (value.has("networkTopology") && !value.get("networkTopology").isJsonNull()) {
            JsonObject topology = object(value, "networkTopology");
            fields(topology, "networkTopology", "k", "linkBandwidthMbPerSecond", "coreSwitchCount", "hostEdgePlacements");
            int k = integer(topology, "k", 4, 2, 16);
            Integer cores = topology.has("coreSwitchCount") && !topology.get("coreSwitchCount").isJsonNull()
                    ? integer(topology, "coreSwitchCount", 0, 1, 64) : null;
            Map<Integer, Integer> placements = null;
            if (topology.has("hostEdgePlacements") && !topology.get("hostEdgePlacements").isJsonNull()) {
                placements = new LinkedHashMap<Integer, Integer>();
                for (Map.Entry<String, JsonElement> entry : object(topology, "hostEdgePlacements").entrySet()) {
                    int host = Integer.parseInt(entry.getKey());
                    if (host < 0 || !Integer.toString(host).equals(entry.getKey())) { throw new IllegalArgumentException("Invalid host placement ID"); }
                    long edge = longValue(entry.getValue(), "hostEdgePlacements");
                    if (edge < 0 || edge > Integer.MAX_VALUE) { throw new IllegalArgumentException("Invalid edge ID"); }
                    placements.put(host, (int) edge);
                }
            }
            NetworkTopologySpec spec = NetworkTopologySpec.fatTree(k, decimal(topology, "linkBandwidthMbPerSecond", 1, true), cores, placements);
            List<Integer> hosts = new ArrayList<Integer>(); for (int id = 0; id < mips.size(); id++) { hosts.add(id); }
            FatTreeTopology.fromSpec(spec, hosts); result.networkTopology(spec);
        }
        return result.build();
    }

    private static DataMovementModel movement(JsonObject sim) {
        String kind = text(sim, "dataMovementModel", "LEGACY_WORKFLOWSIM_V1");
        if (!"FIXED_ENDPOINT_NO_CONTENTION_V1".equals(kind) && sim.has("fixedEndpoint")) { throw new IllegalArgumentException("fixedEndpoint parameters require its data movement model"); }
        switch (DataMovementModel.Kind.valueOf(kind)) {
            case LEGACY_WORKFLOWSIM_V1: return DataMovementModel.legacyWorkflowsimV1();
            case PRE_EXECUTION_TRANSFER_DELAY_V1: return DataMovementModel.preExecutionTransferDelayV1();
            case PRE_EXECUTION_TRANSFER_DELAY_WITH_CONTENTION_V1: return DataMovementModel.preExecutionTransferDelayWithContentionV1();
            case PRE_EXECUTION_TRANSFER_DELAY_WITH_FAT_TREE_CONTENTION_V1: return DataMovementModel.fatTreeContentionV1();
            case FIXED_ENDPOINT_NO_CONTENTION_V1:
                JsonObject fixed = object(sim, "fixedEndpoint"); fields(fixed, "fixedEndpoint", "accessBandwidth", "latencySeconds", "sourceBandwidth");
                return DataMovementModel.fixedEndpointNoContention(decimal(fixed, "accessBandwidth", -1, true), decimal(fixed, "latencySeconds", 0, false), decimal(fixed, "sourceBandwidth", -1, true));
            default: throw new IllegalArgumentException("Unsupported data movement model");
        }
    }

    public String getName() { return name; }
    public JsonObject getSource() { return source.deepCopy(); }
    public PlatformProfile getPlatform() { return platform; }
    public List<Candidate> getCandidates() { return candidates; }
    public List<Long> getSeeds() { return seeds; }
    public int getRunCount() { return candidates.size() * seeds.size(); }
    public static final class Candidate {
        public final String id; public final SimulationConfig config;
        Candidate(String id, SimulationConfig config) { this.id = id; this.config = config; }
    }

    private static JsonElement readJson(com.google.gson.stream.JsonReader reader, int depth) throws IOException {
        if (depth > 64) { throw new IllegalArgumentException("Configuration nesting is too deep"); }
        switch (reader.peek()) {
            case BEGIN_OBJECT:
                JsonObject object = new JsonObject(); reader.beginObject();
                while (reader.hasNext()) {
                    String key = reader.nextName();
                    if (object.has(key)) { throw new IllegalArgumentException("Duplicate JSON field: " + key); }
                    object.add(key, readJson(reader, depth + 1));
                }
                reader.endObject(); return object;
            case BEGIN_ARRAY:
                JsonArray array = new JsonArray(); reader.beginArray();
                while (reader.hasNext()) { array.add(readJson(reader, depth + 1)); }
                reader.endArray(); return array;
            case STRING: return new com.google.gson.JsonPrimitive(reader.nextString());
            case NUMBER: return new com.google.gson.JsonPrimitive(new java.math.BigDecimal(reader.nextString()));
            case BOOLEAN: return new com.google.gson.JsonPrimitive(reader.nextBoolean());
            case NULL: reader.nextNull(); return com.google.gson.JsonNull.INSTANCE;
            default: throw new IllegalArgumentException("Unexpected JSON token: " + reader.peek());
        }
    }

    private static void fields(JsonObject value, String subject, String... allowed) {
        Set<String> known = new HashSet<String>(Arrays.asList(allowed));
        for (String key : value.keySet()) { if (!known.contains(key)) { throw new IllegalArgumentException(subject + " has unknown field: " + key); } }
    }
    private static JsonObject object(JsonObject value, String key) {
        if (!value.has(key) || !value.get(key).isJsonObject()) { throw new IllegalArgumentException(key + " must be an object"); }
        return value.getAsJsonObject(key);
    }
    private static JsonArray array(JsonObject value, String key) {
        if (!value.has(key) || !value.get(key).isJsonArray()) { throw new IllegalArgumentException(key + " must be an array"); }
        return value.getAsJsonArray(key);
    }
    private static String text(JsonObject value, String key, String fallback) {
        if (!value.has(key) && fallback != null) { return fallback; }
        if (!value.has(key) || !value.get(key).isJsonPrimitive() || !value.get(key).getAsJsonPrimitive().isString()
                || value.get(key).getAsString().trim().isEmpty()) { throw new IllegalArgumentException(key + " must be a nonempty string"); }
        return value.get(key).getAsString();
    }
    private static double decimal(JsonObject value, String key, double fallback, boolean positive) {
        if (!value.has(key)) {
            if (fallback < 0) { throw new IllegalArgumentException("Missing " + key); }
            return fallback;
        }
        return number(value.get(key), key, positive);
    }
    private static double number(JsonElement value, String field, boolean positive) {
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) { throw new IllegalArgumentException(field + " must be a number"); }
        double number = value.getAsDouble();
        if (!Double.isFinite(number) || (positive ? number <= 0 : number < 0)) { throw new IllegalArgumentException(field + " must be finite and " + (positive ? "positive" : "nonnegative")); }
        return number;
    }
    private static long longValue(JsonElement value, String field) {
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) { throw new IllegalArgumentException(field + " must be an integer"); }
        try { return value.getAsBigDecimal().longValueExact(); }
        catch (ArithmeticException | NumberFormatException e) { throw new IllegalArgumentException(field + " must be an exact integer", e); }
    }
    private static int integer(JsonObject value, String key, int fallback, int min, int max) {
        long n = value.has(key) ? longValue(value.get(key), key) : fallback;
        if (n < min || n > max) { throw new IllegalArgumentException(key + " is out of range " + min + ".." + max); }
        return (int) n;
    }
}
