package org.workflowsim.experiments.network;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.reflect.TypeToken;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.workflowsim.experiment.ExperimentArtifactValidator;

/** Independently checks the declared matrix, each evidence bundle, and recomputed aggregate statistics. */
public final class NetworkStudyValidator {
    private static final Gson JSON = new GsonBuilder().serializeNulls().create();
    private NetworkStudyValidator() { }

    public static void main(String[] args) throws Exception {
        if (args.length != 1) { throw new IllegalArgumentException("Usage: NetworkStudyValidator <network-study.json>"); }
        System.out.println("NETWORK_STUDY_VALIDATION PASSED runs=" + validate(Paths.get(args[0])));
    }

    public static int validate(Path indexPath) throws IOException {
        try {
            Path root = indexPath.toAbsolutePath().normalize().getParent();
            JsonObject index = read(indexPath);
            if (!"workflowsim-network-study-v1".equals(index.get("schema").getAsString())) { throw new IOException("Unsupported network study schema"); }
            JsonObject plan = index.getAsJsonObject("plan");
            if (!plan.equals(read(root.resolve("protocol.json")))) { throw new IOException("Study plan differs from retained protocol"); }
            if (!NetworkStudyPlan.PROTOCOL.equals(plan.get("protocol").getAsString())) { throw new IOException("Unknown protocol"); }
            Set<String> expected = new HashSet<String>();
            for (JsonElement workflow : plan.getAsJsonArray("workflows")) {
                for (JsonElement count : plan.getAsJsonArray("vmCounts")) {
                    for (JsonElement network : plan.getAsJsonArray("networks")) {
                        for (JsonElement planner : plan.getAsJsonArray("planners")) {
                            String algorithm = planner.getAsString();
                            if ("RANDOM".equals(algorithm) || "PSO".equals(algorithm)) {
                                for (JsonElement seed : plan.getAsJsonArray("randomSeeds")) {
                                    if (!expected.add(key(workflow.getAsJsonObject().get("id").getAsString(), count.getAsInt(), network.getAsString(), algorithm, seed.getAsLong()))) {
                                        throw new IOException("Duplicate planned study cell");
                                    }
                                }
                            } else {
                                if (!expected.add(key(workflow.getAsJsonObject().get("id").getAsString(), count.getAsInt(), network.getAsString(), algorithm, plan.get("deterministicSeed").getAsLong()))) {
                                    throw new IOException("Duplicate planned study cell");
                                }
                            }
                        }
                    }
                }
            }
            if (expected.size() != plan.get("runCount").getAsInt()) { throw new IOException("Plan run count mismatch"); }
            for (JsonElement element : index.getAsJsonArray("runs")) {
                JsonObject run = element.getAsJsonObject();
                String cell = key(run.get("workflowId").getAsString(), run.get("vmCount").getAsInt(),
                        run.get("network").getAsString(), run.get("planner").getAsString(), run.get("seed").getAsLong());
                if (!expected.remove(cell)) { throw new IOException("Duplicate or undeclared study cell: " + cell); }
                JsonObject declaredWorkflow = null;
                for (JsonElement candidateWorkflow : plan.getAsJsonArray("workflows")) {
                    if (candidateWorkflow.getAsJsonObject().get("id").equals(run.get("workflowId"))) {
                        declaredWorkflow = candidateWorkflow.getAsJsonObject(); break;
                    }
                }
                if (declaredWorkflow == null || !declaredWorkflow.get("family").equals(run.get("family"))
                        || !declaredWorkflow.get("population").equals(run.get("population"))) {
                    throw new IOException("Study workflow population/family mismatch: " + cell);
                }
                if (!"COMPLETED_SUCCESSFULLY".equals(run.get("status").getAsString())) { throw new IOException("Study contains failed run: " + cell); }
                String relative = run.get("manifest").getAsString();
                Path manifest = root.resolve(relative).normalize();
                if (Paths.get(relative).isAbsolute() || !manifest.startsWith(root)
                        || !manifest.toRealPath().startsWith(root.toRealPath())) { throw new IOException("Invalid study evidence path"); }
                ExperimentArtifactValidator.validate(manifest);
                JsonObject evidence = read(manifest);
                JsonObject config = evidence.getAsJsonObject("configuration");
                if (config.get("rootSeed").getAsLong() != run.get("seed").getAsLong()
                        || config.get("vmCount").getAsInt() != run.get("vmCount").getAsInt()
                        || !config.get("planningAlgorithm").equals(run.get("planner"))) { throw new IOException("Run configuration differs from study cell: " + cell); }
                String network = run.get("network").getAsString();
                if (!NetworkStudyPlan.movement(network).getKind().name().equals(config.getAsJsonObject("dataMovementModel").get("kind").getAsString())) { throw new IOException("Network mismatch: " + cell); }
                JsonObject platform = evidence.getAsJsonObject("platform");
                if (!"STATIC".equals(config.get("schedulingAlgorithm").getAsString())
                        || !"LOCAL".equals(config.get("fileSystem").getAsString())
                        || config.get("runtimeScale").getAsDouble() != 1.0
                        || config.get("runtimeReferenceMips").getAsDouble() != 1000.0
                        || config.get("cloudSimMinEventIntervalSeconds").getAsDouble() != 0.1
                        || config.get("deadline").getAsLong() != 0
                        || !config.get("taskCostMatrix").isJsonNull()
                        || !"FAILURE_NONE".equals(config.getAsJsonObject("failureModel").get("generatorMode").getAsString())
                        || !"NONE".equals(config.getAsJsonObject("clustering").get("method").getAsString())
                        || config.getAsJsonArray("workflowArrivalSeconds").size() != 1
                        || config.getAsJsonArray("workflowArrivalSeconds").get(0).getAsDouble() != 0.0) {
                    throw new IOException("Fixed study simulation conditions differ: " + cell);
                }
                for (JsonElement vmElement : platform.getAsJsonArray("vms")) {
                    JsonObject vm = vmElement.getAsJsonObject();
                    if (vm.get("mips").getAsDouble() != 1000.0 || vm.get("bandwidth").getAsLong() != 1
                            || vm.get("pes").getAsInt() != 1
                            || !vm.get("id").equals(vm.get("pinnedHostId"))
                            || !"SPACE_SHARED".equals(vm.get("schedulerMode").getAsString())) {
                        throw new IOException("Study VM platform differs: " + cell);
                    }
                }
                if (!"endpoint".equals(network)) {
                    JsonObject topology = platform.getAsJsonObject("networkTopology");
                    double link = topology.get("linkBandwidthMbPerSecond").getAsDouble();
                    if (Double.compare(link, "fat-tree-constrained".equals(network) ? 0.125 : 1.25) != 0
                            || topology.get("k").getAsInt() != 4
                            || !topology.get("coreSwitchCount").isJsonNull()
                            || !topology.get("hostEdgePlacements").isJsonNull()) { throw new IOException("Network bandwidth/topology mismatch"); }
                }
                if (evidence.getAsJsonArray("inputs").size() != 1) { throw new IOException("Study requires exactly one input"); }
                JsonObject input = evidence.getAsJsonArray("inputs").get(0).getAsJsonObject();
                if (!input.get("sha256").equals(declaredWorkflow.get("sha256"))) {
                    throw new IOException("Study input fingerprint differs from protocol: " + cell);
                }
                if (!Paths.get(input.get("path").getAsString()).equals(Paths.get(declaredWorkflow.get("path").getAsString()))) {
                    throw new IOException("Study input path differs from protocol: " + cell);
                }
                if (!input.get("sha256").equals(run.get("inputSha256")) || input.get("taskCount").getAsInt() != run.get("taskCount").getAsInt()) { throw new IOException("Input identity mismatch: " + cell); }
                JsonObject metrics = evidence.getAsJsonObject("metrics");
                match(run, "makespanSeconds", metrics, "makespanSeconds");
                match(run, "logicalCompletionSeconds", metrics, "logicalTaskCompletionSeconds");
                match(run, "meanWaitingSeconds", metrics, "meanComputeTotalWaitingTimeSeconds");
                match(run, "p95WaitingSeconds", metrics, "p95ComputeTotalWaitingTimeSeconds");
                match(run, "meanVmUtilization", metrics, "meanVmModeledIntervalUtilization");
            }
            if (!expected.isEmpty()) { throw new IOException("Study is missing " + expected.size() + " planned cells"); }
            List<Map<String, Object>> runs = JSON.fromJson(index.get("runs"), new TypeToken<List<Map<String, Object>>>() { }.getType());
            if (!JSON.toJsonTree(NetworkStudySummary.summarize(runs)).equals(index.get("summary"))) { throw new IOException("Study summary differs from independent recomputation"); }
            return runs.size();
        } catch (RuntimeException exception) {
            throw new IOException("Malformed network study: " + exception.getMessage(), exception);
        }
    }

    private static void match(JsonObject a, String ak, JsonObject b, String bk) throws IOException {
        if (Double.compare(a.get(ak).getAsDouble(), b.get(bk).getAsDouble()) != 0) { throw new IOException("Study metric mismatch: " + ak); }
    }
    private static String key(String workflow, int count, String network, String planner, long seed) {
        return workflow + "/" + count + "/" + network + "/" + planner + "/" + seed;
    }
    private static JsonObject read(Path path) throws IOException {
        return JsonParser.parseString(new String(Files.readAllBytes(path), StandardCharsets.UTF_8)).getAsJsonObject();
    }
}
