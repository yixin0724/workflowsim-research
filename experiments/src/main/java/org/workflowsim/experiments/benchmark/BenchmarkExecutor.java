package org.workflowsim.experiments.benchmark;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.cloudbus.cloudsim.Log;
import org.workflowsim.data.DataMovementModel;
import org.workflowsim.experiment.ExperimentArtifactValidator;
import org.workflowsim.experiment.ExperimentArtifactWriter;
import org.workflowsim.experiment.ExperimentEvidenceContext;
import org.workflowsim.experiment.SimulationReport;
import org.workflowsim.experiment.SimulationRunner;
import org.workflowsim.experiments.network.NetworkStudyPlan;
import org.workflowsim.platform.PlatformProfile;
import org.workflowsim.utils.Parameters.PlanningAlgorithm;
import org.workflowsim.utils.Parameters.SchedulingAlgorithm;
import org.workflowsim.utils.ReplicaCatalog;
import org.workflowsim.utils.SimulationConfig;

/**
 * The platform's first public frozen benchmark (2026-10-26), re-run under the energy
 * dimension as protocol {@code benchmark-standard-power-v1} (BAT-E4, 2026-10-27): the
 * same 160-cell matrix on the same frozen study conditions, with every VM additionally
 * carrying the homogeneous Intel-style power envelope (idle 250 W / max 400 W) of the
 * Beloglazov linear model. Every number is backed by a manifest-v4 bundle whose
 * {@code energyMetrics} section the repository's Python auditor recomputes from the
 * manifest's own power fields and per-VM busy intervals.
 *
 * <p>Covered layers — all comparisons are same-layer-only (cross-layer comparisons are
 * structurally invalid and the benchmark forbids them):</p>
 * <ul>
 *   <li>SHARED DAG planning: SHARED_STORAGE planners × the legacy envelope × SHARED ×
 *       STATIC cells</li>
 *   <li>LOCAL DAG planning: LOCAL planners × raw networks (endpoint contention,
 *       fat-tree constrained) × LOCAL × STATIC cells</li>
 *   <li>Online CPU scheduling: maintained labels × the legacy envelope × INVALID planning
 *       cells</li>
 * </ul>
 *
 * <p>Platform profiles reuse the registered-study factory
 * {@link NetworkStudyPlan#platform(int, String)} (frozen, never modified here) and are
 * then rebuilt through {@link #withPower(PlatformProfile)}: every host, VM, pin, storage,
 * cost, and topology field is copied verbatim and only the two per-VM power fields are
 * added. The excluded-input rule from the study family is respected by construction (the
 * conflicting file is simply absent).</p>
 */
public final class BenchmarkExecutor {

    /** Output index file name (per-run rows with identity + metrics — Python-auditable). */
    static final String INDEX = "benchmark-power.json";

    static final double POWER_IDLE_WATTS = 250.0;

    static final double POWER_MAX_WATTS = 400.0;

    static final List<String> INPUT_PATHS = Collections.unmodifiableList(Arrays.asList(
            "dax/epigenomics/n100/Epigenomics_100.dax",
            "dax/epigenomics/n997/Epigenomics_997.dax",
            "dax/cybershake/n100/CyberShake_100.dax",
            "dax/cybershake/n1000/CyberShake_1000.dax",
            "dax/inspiral/n100/Inspiral_100.dax"));

    static final List<PlanningAlgorithm> SHARED_PLANNERS = Collections.unmodifiableList(Arrays.asList(
            PlanningAlgorithm.SHARED_STORAGE_HEFT,
            PlanningAlgorithm.SHARED_STORAGE_CPOP,
            PlanningAlgorithm.SHARED_STORAGE_DLS,
            PlanningAlgorithm.SHARED_STORAGE_ETF,
            PlanningAlgorithm.SHARED_STORAGE_PEFT));

    static final List<PlanningAlgorithm> LOCAL_PLANNERS = Collections.unmodifiableList(
            Arrays.asList(PlanningAlgorithm.LOCAL_HEFT, PlanningAlgorithm.LOCAL_CPOP, PlanningAlgorithm.LOCAL_PEFT));

    static final List<SchedulingAlgorithm> ONLINE_LABELS = Collections.unmodifiableList(Arrays.asList(
            SchedulingAlgorithm.FCFS,
            SchedulingAlgorithm.READY_BATCH_MINMIN,
            SchedulingAlgorithm.READY_BATCH_MAXMIN,
            SchedulingAlgorithm.READY_BATCH_MCT,
            SchedulingAlgorithm.READY_BATCH_ROUNDROBIN));

    static final List<String> LOCAL_NETWORKS =
            Collections.unmodifiableList(Arrays.asList("endpoint", "fat-tree-constrained"));

    static final List<Integer> VM_TIERS = Collections.unmodifiableList(Arrays.asList(8, 32));

    private BenchmarkExecutor() {}

    /**
     * Entry. The output directory must not pre-exist (retention discipline — never
     * overwrite).
     */
    public static void main(String[] args) throws Exception {
        if (args.length != 2) {
            throw new IllegalArgumentException("Usage: BenchmarkExecutor <datasets-root> <new-output-directory>");
        }
        Path result = execute(Paths.get(args[0]), Paths.get(args[1]));
        System.out.println("BENCHMARK PASSED " + result);
    }

    /** Executes the frozen benchmark and returns the index path. */
    static Path execute(Path datasetsRoot, Path outputDirectory) throws Exception {
        Path datasets = datasetsRoot.toAbsolutePath().normalize();
        Path root = outputDirectory.toAbsolutePath().normalize();
        if (Files.exists(root)) {
            throw new IOException("Benchmark output already exists: " + root);
        }
        boolean prior = Log.isDisabled();
        Log.disable();
        try {
            Files.createDirectories(root);
            writePlan(root);
            List<Map<String, Object>> runs = new ArrayList<Map<String, Object>>();
            executeShared(datasets, root, runs);
            executeLocal(datasets, root, runs);
            executeOnline(datasets, root, runs);
            writeIndex(root, runs);
            return root.resolve(INDEX);
        } finally {
            Log.setDisabled(prior);
        }
    }

    // layer batteries

    private static void executeShared(Path datasets, Path root, List<Map<String, Object>> runs) throws IOException {
        for (String input : INPUT_PATHS) {
            Path path = resolveInput(datasets, input);
            for (int vm : VM_TIERS) {
                for (PlanningAlgorithm planner : SHARED_PLANNERS) {
                    cell(
                            runs,
                            root,
                            path,
                            inputId(input),
                            vm,
                            planner,
                            null,
                            ReplicaCatalog.FileSystem.SHARED,
                            DataMovementModel.legacyWorkflowsimV1(),
                            "endpoint");
                }
            }
            System.out.println("[benchmark] SHARED-" + inputId(input) + ": " + runs.size());
        }
    }

    private static void executeLocal(Path datasets, Path root, List<Map<String, Object>> runs) throws IOException {
        for (String input : INPUT_PATHS) {
            Path path = resolveInput(datasets, input);
            for (int vm : VM_TIERS) {
                for (PlanningAlgorithm planner : LOCAL_PLANNERS) {
                    for (String network : LOCAL_NETWORKS) {
                        cell(
                                runs,
                                root,
                                path,
                                inputId(input),
                                vm,
                                planner,
                                null,
                                ReplicaCatalog.FileSystem.LOCAL,
                                NetworkStudyPlan.movement(network),
                                network);
                    }
                }
            }
            System.out.println("[benchmark] LOCAL-" + inputId(input) + ": " + runs.size());
        }
    }

    private static void executeOnline(Path datasets, Path root, List<Map<String, Object>> runs) throws IOException {
        for (String input : INPUT_PATHS) {
            Path path = resolveInput(datasets, input);
            for (int vm : VM_TIERS) {
                for (SchedulingAlgorithm label : ONLINE_LABELS) {
                    cell(
                            runs,
                            root,
                            path,
                            inputId(input),
                            vm,
                            null,
                            label,
                            ReplicaCatalog.FileSystem.LOCAL,
                            DataMovementModel.legacyWorkflowsimV1(),
                            "endpoint");
                }
            }
            System.out.println("[benchmark] ONLINE-" + inputId(input) + ": " + runs.size());
        }
    }

    // ---------- helpers ----------

    static Path resolveInput(Path datasets, String relative) {
        Path p = datasets.resolve(relative).toAbsolutePath().normalize();
        if (!Files.isRegularFile(p)) {
            throw new IllegalArgumentException("Missing benchmark input: " + p);
        }
        return p;
    }

    static String inputId(String relative) {
        String name = relative.substring(relative.lastIndexOf('/') + 1);
        return name.replace(".dax", "");
    }

    static String runId(String inputId2, int vm, String policy, String model) {
        return inputId2 + "-v" + vm + "-" + model + "-" + policy + "-s11";
    }

    /**
     * Rebuilds the frozen registered-study platform with per-VM power fields (BAT-E4,
     * 2026-10-27). The frozen factory {@link NetworkStudyPlan#platform(int, String)} is
     * never modified: every host, VM, pin, storage, cost, and topology entry is copied
     * verbatim through {@link PlatformProfile.Builder}, and each VM additionally declares
     * the homogeneous power envelope {@code idlePowerWatts=250.0}/{@code maxPowerWatts=
     * 400.0}. The rebuild keeps the profile name, so vmHost placements (all pinned) are
     * bit-identical to the unpowered factory output.
     */
    static PlatformProfile withPower(PlatformProfile unpowered) {
        PlatformProfile.Builder builder = PlatformProfile.builder(unpowered.getName())
                .storage(unpowered.getStorage())
                .costs(unpowered.getCosts());
        for (PlatformProfile.HostSpec host : unpowered.getHosts()) {
            builder.addHost(new PlatformProfile.HostSpec(
                    host.getId(),
                    host.getPes(),
                    host.getMipsPerPe(),
                    host.getRamMb(),
                    host.getBandwidth(),
                    host.getStorageMb()));
        }
        for (PlatformProfile.VmSpec vm : unpowered.getVms()) {
            if (vm.hasPowerModel()) {
                throw new IllegalStateException("withPower expects the unpowered registered-study platform, VM "
                        + vm.getId() + " already declares a power model");
            }
            builder.addVm(new PlatformProfile.VmSpec(
                    vm.getId(),
                    vm.getMips(),
                    vm.getPes(),
                    vm.getRamMb(),
                    vm.getBandwidth(),
                    vm.getImageSizeMb(),
                    vm.getVmm(),
                    vm.getSchedulerMode(),
                    vm.getCosts(),
                    POWER_IDLE_WATTS,
                    POWER_MAX_WATTS));
        }
        for (Map.Entry<Integer, Integer> pin : unpowered.getPinnedVmHostIds().entrySet()) {
            builder.pinVmToHost(pin.getKey().intValue(), pin.getValue().intValue());
        }
        if (unpowered.getNetworkTopology() != null) {
            builder.networkTopology(unpowered.getNetworkTopology());
        }
        if (unpowered.getSourceStorage() != null) {
            builder.sourceStorage(unpowered.getSourceStorage());
        }
        return builder.build();
    }

    /**
     * Runs one benchmark cell: config construction, full simulation, evidence write +
     * validate. Deterministic algorithms run once at seed 11 (same convention as the
     * study family). Each cell writes the partial-but-valid index after completion, so an
     * interrupted benchmark leaves consistent (though incomplete) evidence.
     */
    private static void cell(
            List<Map<String, Object>> runs,
            Path root,
            Path inputPath,
            String inputId2,
            int vmCount,
            PlanningAlgorithm planner,
            SchedulingAlgorithm scheduler,
            ReplicaCatalog.FileSystem fileSystem,
            DataMovementModel model,
            String platformNetwork)
            throws IOException {
        String policy = planner != null ? planner.name() : scheduler.name();
        String modelKind = model.getKind().name();
        String runId = runId(inputId2, vmCount, policy, modelKind);
        Map<String, Object> record = new LinkedHashMap<String, Object>();
        record.put("runId", runId);
        record.put("inputId", inputId2);
        record.put("vmCount", Integer.valueOf(vmCount));
        record.put("planner", planner != null ? planner.name() : null);
        record.put("scheduler", scheduler != null ? scheduler.name() : null);
        record.put("model", modelKind);
        try {
            SimulationConfig config = SimulationConfig.builder(inputPath.toString(), vmCount)
                    .planningAlgorithm(planner != null ? planner : PlanningAlgorithm.INVALID)
                    .schedulingAlgorithm(
                            scheduler != null
                                    ? scheduler
                                    : (planner != null ? SchedulingAlgorithm.STATIC : SchedulingAlgorithm.INVALID))
                    .fileSystem(fileSystem)
                    .dataMovementModel(model)
                    .randomSeed(11L)
                    .build();
            PlatformProfile platform =
                    NetworkStudyPlan.platform(vmCount, platformNetwork == null ? "endpoint" : platformNetwork);
            platform = withPower(platform);
            SimulationReport report = new SimulationRunner().run(config, platform);
            if (!report.getMetrics().isAllLogicalTasksCompletedSuccessfully()) {
                throw new IllegalStateException("Incomplete workflow");
            }
            ExperimentEvidenceContext context = ExperimentEvidenceContext.builder("benchmark-standard-power-v1")
                    .artifact("org.workflowsim", "benchmark", "1.0")
                    .driver(BenchmarkExecutor.class, "org/workflowsim/experiments/benchmark")
                    .protocol("benchmark-standard-power-v1", root.resolve("benchmark-plan.json"))
                    .datasetRoot(inputPath.getParent())
                    .build();
            ExperimentArtifactWriter.ExperimentArtifacts artifacts =
                    ExperimentArtifactWriter.write(report, root.resolve("runs").resolve(runId), "result", context);
            ExperimentArtifactValidator.validate(artifacts.getManifest());
            record.put("status", "COMPLETED_SUCCESSFULLY");
            record.put(
                    "manifest",
                    root.relativize(artifacts.getManifest()).toString().replace('\\', '/'));
            record.put("makespanSeconds", Double.valueOf(report.getMakespan()));
            record.put("logicalCompletionSeconds", Double.valueOf(report.getLogicalTaskCompletionSeconds()));
            record.put(
                    "meanWaitingSeconds", Double.valueOf(report.getMetrics().getMeanComputeTotalWaitingTimeSeconds()));
            record.put("p95WaitingSeconds", Double.valueOf(report.getMetrics().getP95ComputeTotalWaitingTimeSeconds()));
            record.put("meanVmUtilization", Double.valueOf(report.getMetrics().getMeanVmModeledIntervalUtilization()));
        } catch (Exception failure) {
            record.put("status", "FAILED");
            record.put("error", failure.getClass().getSimpleName() + ": " + failure.getMessage());
        }
        runs.add(record);
        writeIndex(root, runs);
    }

    private static void writePlan(Path root) throws IOException {
        Map<String, Object> plan = new LinkedHashMap<String, Object>();
        plan.put("schema", "workflowsim-benchmark-plan-v1");
        plan.put("protocol", "benchmark-standard-power-v1");
        plan.put("javaClass", BenchmarkExecutor.class.getName());
        ExperimentArtifactWriter.writeJson(root.resolve("benchmark-plan.json"), plan);
    }

    private static void writeIndex(Path root, List<Map<String, Object>> runs) throws IOException {
        Map<String, Object> index = new LinkedHashMap<String, Object>();
        index.put("schema", "workflowsim-benchmark-v1");
        index.put("protocol", "benchmark-standard-power-v1");
        index.put("layers", "SHARED_DAG;LOCAL_DAG;ONLINE");
        index.put("runCount", Integer.valueOf(runs.size()));
        index.put("runs", runs);
        ExperimentArtifactWriter.writeJson(root.resolve(INDEX), index);
    }
}
