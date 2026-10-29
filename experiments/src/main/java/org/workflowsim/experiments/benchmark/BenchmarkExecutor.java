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
 * dimension as protocol {@code benchmark-standard-power-v1} (BAT-E4, 2026-10-27), under
 * the carbon dimension as protocol {@code benchmark-standard-carbon-v1} (design doc
 * docs/advanced/CARBON_DIMENSION_DESIGN.md §5, job batch C4, 2026-10-28), and under
 * the DVFS dimension as protocol {@code benchmark-standard-dvfs-v1} (design doc
 * docs/advanced/DVFS_DIMENSION_DESIGN.md §4, batches D5+D6, 2026-10-29 — the first
 * tri-dimensional run: every cell's platform declares the frozen 5-tier P-state table
 * AND the uniform 380.0 gCO₂/kWh intensity riding the per-tier conserved energy, with
 * per-Job tier assignments in the evidence bundle). The first three protocols share
 * the same 160-cell matrix on the same frozen study conditions, with every VM
 * additionally carrying the homogeneous Intel-style power envelope (idle 250 W /
 * max 400 W) of the Beloglazov linear model and the uniform carbon intensity 380.0
 * gCO₂/kWh (intensity model, CASPER family), plus one 10-cell budget-model
 * demonstration family (CWM family; two declared green-power windows). The two carbon
 * models are mutually exclusive per run, so no cell ever declares an intensity
 * together with windows. Every number is backed by a manifest-v4 bundle whose
 * {@code energyMetrics} and conditional {@code carbonMetrics} sections the
 * repository's Python auditor recomputes from the manifest's own power fields,
 * per-VM busy intervals, and compute-job fleet timeline.
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
 * then rebuilt through {@link #withPower(PlatformProfile)} followed by
 * {@link #withCarbon(PlatformProfile)} (intensity grid) or
 * {@link #withCarbonBudget(PlatformProfile)} (budget family): every host, VM, pin,
 * storage, cost, and topology field is copied verbatim and only the two per-VM power
 * fields plus one carbon model are added. The excluded-input rule from the study
 * family is respected by construction (the conflicting file is simply absent).</p>
 */
public final class BenchmarkExecutor {

    /** Output index file name (per-run rows with identity + metrics — Python-auditable). */
    static final String INDEX = "benchmark-carbon.json";

    /** Protocol identity of the whole carbon run (single literal — freezing discipline). */
    static final String PROTOCOL = "benchmark-standard-carbon-v1";

    static final double POWER_IDLE_WATTS = 250.0;

    static final double POWER_MAX_WATTS = 400.0;

    /**
     * Uniform per-VM carbon intensity (gCO₂/kWh) declared on the 160-cell intensity grid
     * (CASPER-family multiplier model; docs/advanced/CARBON_DIMENSION_DESIGN.md §2.1) and
     * on every cell of the tri-dimensional DVFS variant. Public since
     * {@code benchmark-standard-dvfs-v1}: the D6 demonstrator fleet (experiments test
     * scope) reuses the frozen value for its tri-dimensional evidence bundle.
     */
    public static final double CARBON_INTENSITY_GRAMS_PER_KWH = 380.0;

    /**
     * Budget-model demonstration family (CWM family), job batch C4: the declared
     * renewable windows [0, 3600) at green 300 W and [3600, 7200) at green 600 W. The
     * greens sit deliberately far below the fleet idle draw (8×250 = 2000 W, 32×250 =
     * 8000 W) so every budget cell reports positive excess watt-seconds; the window
     * horizon [0, 7200) is a researcher-declared scenario, not the whole simulated span
     * (the honest-bound note in the benchmark doc).
     */
    static final double CARBON_WINDOW_1_END_SECONDS = 3600.0;

    static final double CARBON_WINDOW_2_END_SECONDS = 7200.0;

    static final double CARBON_WINDOW_1_GREEN_WATTS = 300.0;

    static final double CARBON_WINDOW_2_GREEN_WATTS = 600.0;

    /** Budget-family policy: the canonical first SHARED_DAG planner of the matrix. */
    static final PlanningAlgorithm BUDGET_PLANNER = PlanningAlgorithm.SHARED_STORAGE_HEFT;

    /**
     * Output index file name of the DVFS variant (per-run rows with identity + tier
     * mix + tri-dimensional metrics — Python-auditable).
     */
    static final String DVFS_INDEX = "benchmark-dvfs.json";

    /** Protocol identity of the whole DVFS run (single literal — freezing discipline). */
    static final String DVFS_PROTOCOL = "benchmark-standard-dvfs-v1";

    /**
     * The frozen 5-tier P-state table of the DVFS fleet (D5, 2026-10-29 —
     * docs/advanced/DVFS_DIMENSION_DESIGN.md §4): one uniform table per VM, mips
     * 2×/1×/0.5×/0.25×/0.125× of the 1000-MIPS fleet base (2000/1000/500/250/125),
     * strictly descending with the FIRST tier the declared default.
     *
     * <p>Declared values and their two motivating laws (frozen constants, not generator
     * output — a declared model, faithful to the P-state table schema shared by the
     * surveyed DVFS literature):</p>
     * <ul>
     *   <li><b>dynamic power</b>: at/below the nominal 1000 tier the exact cubic
     *       {@code 150 W × (mips/1000)³} (150 W = the Beloglazov dynamic of the
     *       power-v1 envelope, so the nominal tier IS
     *       benchmark-standard-power-v1's per-VM envelope 250 W idle / 400 W max);
     *       above it, the turbo tier declares 3750 W dynamic — a heavily overvolted
     *       state (real turbo escalates steeper than the cubic trend that holds
     *       below the efficient point).</li>
     *   <li><b>idle power</b>: 250 W at/above nominal (the power-v1 fleet idle
     *       anchor); strictly below nominal, linear voltage-leakage scaling at
     *       0.2 W per MIPS (500→100, 250→50, 125→25).</li>
     * </ul>
     *
     * <p>Consequences pinned by construction: per-MI busy energy falls monotonically
     * toward the deepest tier (2.0 / 0.4 / 0.2375 / 0.209375 / 0.20234375 J/MI) — the
     * density side of the break-even ruling — while the parked draw stays the default
     * tier's 250 W ×vm — the idle-exposure side; and the greedy EDP coefficients
     * (P_max+P_idle)/m² make the interior 1000 tier the early-dispatch winner, so the
     * EDP_HEURISTIC mechanism is distinguishable from DEFAULT (all-turbo) and
     * MIN_POWER (all-eighth).</p>
     */
    static final List<PlatformProfile.FrequencyTier> DVFS_TIERS = Collections.unmodifiableList(Arrays.asList(
            new PlatformProfile.FrequencyTier(2000.0, 250.0, 4000.0),
            new PlatformProfile.FrequencyTier(1000.0, 250.0, 400.0),
            new PlatformProfile.FrequencyTier(500.0, 100.0, 100.0 + 150.0 / 8.0),
            new PlatformProfile.FrequencyTier(250.0, 50.0, 50.0 + 150.0 / 64.0),
            new PlatformProfile.FrequencyTier(125.0, 25.0, 25.0 + 150.0 / 512.0)));

    /** The three config-level frequency policies the DVFS variant compares (frozen order). */
    static final List<SimulationConfig.FrequencyPolicy> DVFS_POLICIES = Collections.unmodifiableList(Arrays.asList(
            SimulationConfig.FrequencyPolicy.DEFAULT,
            SimulationConfig.FrequencyPolicy.MIN_POWER,
            SimulationConfig.FrequencyPolicy.EDP_HEURISTIC));

    /** DVFS matrix layer representative 1: the canonical SHARED_DAG planner. */
    static final PlanningAlgorithm DVFS_SHARED_PLANNER = PlanningAlgorithm.SHARED_STORAGE_HEFT;

    /** DVFS matrix layer representative 2: the canonical LOCAL_DAG planner (endpoint network). */
    static final PlanningAlgorithm DVFS_LOCAL_PLANNER = PlanningAlgorithm.LOCAL_HEFT;

    /** DVFS matrix layer representative 3: the canonical ONLINE label. */
    static final SchedulingAlgorithm DVFS_ONLINE_LABEL = SchedulingAlgorithm.FCFS;

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
     * Entry. Usage: {@code BenchmarkExecutor <datasets-root> <new-output-directory>}
     * (the carbon/standard variant) or
     * {@code BenchmarkExecutor dvfs <datasets-root> <new-output-directory>} (the DVFS
     * variant). The output directory must not pre-exist (retention discipline — never
     * overwrite).
     */
    public static void main(String[] args) throws Exception {
        if (args.length == 3 && "dvfs".equals(args[0])) {
            Path result = executeDvfs(Paths.get(args[1]), Paths.get(args[2]));
            System.out.println("BENCHMARK PASSED " + result);
            return;
        }
        if (args.length != 2) {
            throw new IllegalArgumentException(
                    "Usage: BenchmarkExecutor [dvfs] <datasets-root>" + " <new-output-directory>");
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
            executeBudget(datasets, root, runs);
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
                            "endpoint",
                            false);
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
                                network,
                                false);
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
                            "endpoint",
                            false);
                }
            }
            System.out.println("[benchmark] ONLINE-" + inputId(input) + ": " + runs.size());
        }
    }

    /**
     * Budget-model demonstration family (job batch C4): 5 input families × 2 VM tiers on
     * the canonical SHARED_DAG planner, powered like every other cell but declaring the
     * carbon-budget scenario instead of the per-VM intensity. Run design time for the
     * carbon run stays acceptable (the whole benchmark is dominated by, and comparable
     * to, the prior power round), so the intensity grid runs in full and the budget
     * model is demonstrated on this one representative family instead of re-running all
     * 160 cells a second time under windows.
     */
    private static void executeBudget(Path datasets, Path root, List<Map<String, Object>> runs) throws IOException {
        for (String input : INPUT_PATHS) {
            Path path = resolveInput(datasets, input);
            for (int vm : VM_TIERS) {
                cell(
                        runs,
                        root,
                        path,
                        inputId(input),
                        vm,
                        BUDGET_PLANNER,
                        null,
                        ReplicaCatalog.FileSystem.SHARED,
                        DataMovementModel.legacyWorkflowsimV1(),
                        "endpoint",
                        true);
            }
            System.out.println("[benchmark] BUDGET-" + inputId(input) + ": " + runs.size());
        }
    }

    // ---------- DVFS variant (D5, 2026-10-29) ----------

    /**
     * Executes the frozen DVFS benchmark variant: 5 inputs × 2 VM tiers × 3 frequency
     * policies × a 3-algorithm representative subset (one per decision layer:
     * SHARED_STORAGE_HEFT / LOCAL_HEFT / FCFS) = 90 cells, each on the tri-dimensional
     * fleet (frozen 5-tier P-state table + uniform 380 g/kWh intensity).
     *
     * <p><b>Honest sizing disclosure</b> (recorded verbatim in the plan JSON and the
     * benchmark doc): the full grid would be 480 cells (all 16 algorithm combinations
     * × 3 policies). At the full grid the wall-clock budget of the frozen variant
     * (minutes, not tens of minutes — the three prior variants each run the whole
     * 160-cell matrix once, and this variant multiplies by three policies) is missed,
     * so the variant freezes the 90-cell representative subset: every input family and
     * both VM tiers are covered in full, each decision layer contributes its canonical
     * algorithm, and the DVFS comparison axis (policy × policy within one algorithm)
     * stays same-layer-only exactly as the standard matrix prescribes.</p>
     */
    static Path executeDvfs(Path datasetsRoot, Path outputDirectory) throws Exception {
        Path datasets = datasetsRoot.toAbsolutePath().normalize();
        Path root = outputDirectory.toAbsolutePath().normalize();
        if (Files.exists(root)) {
            throw new IOException("Benchmark output already exists: " + root);
        }
        boolean prior = Log.isDisabled();
        Log.disable();
        try {
            Files.createDirectories(root);
            writeDvfsPlan(root);
            List<Map<String, Object>> runs = new ArrayList<Map<String, Object>>();
            for (String input : INPUT_PATHS) {
                Path path = resolveInput(datasets, input);
                for (int vm : VM_TIERS) {
                    // SHARED_DAG layer representative on the legacy shared envelope.
                    dvfsCell(
                            runs,
                            root,
                            path,
                            inputId(input),
                            vm,
                            DVFS_SHARED_PLANNER,
                            null,
                            ReplicaCatalog.FileSystem.SHARED,
                            DataMovementModel.legacyWorkflowsimV1(),
                            "endpoint");
                    // LOCAL_DAG layer representative on the endpoint contention network.
                    dvfsCell(
                            runs,
                            root,
                            path,
                            inputId(input),
                            vm,
                            DVFS_LOCAL_PLANNER,
                            null,
                            ReplicaCatalog.FileSystem.LOCAL,
                            NetworkStudyPlan.movement("endpoint"),
                            "endpoint");
                    // ONLINE layer representative (plain online track).
                    dvfsCell(
                            runs,
                            root,
                            path,
                            inputId(input),
                            vm,
                            null,
                            DVFS_ONLINE_LABEL,
                            ReplicaCatalog.FileSystem.LOCAL,
                            DataMovementModel.legacyWorkflowsimV1(),
                            "endpoint");
                }
                System.out.println("[benchmark-dvfs] " + inputId(input) + ": " + runs.size());
            }
            writeDvfsIndex(root, runs);
            return root.resolve(DVFS_INDEX);
        } finally {
            Log.setDisabled(prior);
        }
    }

    /** The frozen 5-tier table accessor (read-only copy; the D6 demonstrator fleet reuses its top rows). */
    public static List<PlatformProfile.FrequencyTier> benchmarkDvfsTiers() {
        return DVFS_TIERS;
    }

    /**
     * Rebuilds the frozen registered-study platform into the tri-dimensional DVFS fleet
     * (D5): every host, VM, pin, storage, cost, and topology field is copied verbatim
     * from the {@link NetworkStudyPlan#platform(int, String)} factory and each VM is
     * rebuilt through the 14-argument {@link PlatformProfile.VmSpec} with FLAT power
     * fields kept at 0.0 (BAT-D1 one source of truth — the tier table replaces them),
     * the frozen {@link #DVFS_TIERS} table, and the uniform carbon intensity of the
     * carbon variant. The rebuild keeps the profile name, so vmHost placements are
     * bit-identical to the unpowered factory output.
     */
    static PlatformProfile dvfsSetup(PlatformProfile unpowered) {
        for (PlatformProfile.VmSpec vm : unpowered.getVms()) {
            if (vm.hasPowerModel()) {
                throw new IllegalStateException("dvfsSetup expects the unpowered registered-study platform, VM "
                        + vm.getId() + " already declares a power model");
            }
            if (vm.hasFrequencyTiers()) {
                throw new IllegalStateException("dvfsSetup expects the tierless registered-study platform, VM "
                        + vm.getId() + " already declares a frequency tier table");
            }
            if (vm.getCarbonIntensityGramsPerKWh() != null) {
                throw new IllegalStateException("dvfsSetup expects the carbon-free registered-study" + " platform, VM "
                        + vm.getId() + " already declares a carbon intensity");
            }
        }
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
                    // BAT-D1 单一真源：频档表替代平面功率字段（平面恒 0），强度模型骑档表能量链。
                    0.0,
                    0.0,
                    Double.valueOf(CARBON_INTENSITY_GRAMS_PER_KWH),
                    DVFS_TIERS));
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

    /** Run id of a DVFS cell: input/tier/algorithm/policy in one frozen spelling. */
    static String dvfsRunId(String inputId2, int vm, String algorithm, String policy) {
        return inputId2 + "-v" + vm + "-DVFS-" + algorithm + "-" + policy + "-s11";
    }

    /** The algorithm label of a DVFS subset cell (planner name or online label). */
    static String dvfsAlgorithmLabel(PlanningAlgorithm planner, SchedulingAlgorithm scheduler) {
        return planner != null ? planner.name() : scheduler.name();
    }

    /**
     * Runs one DVFS cell for each of the three frozen frequency policies: config
     * construction, full simulation, evidence write + validate. Deterministic
     * algorithms run once at seed 11 (the standard convention). Every policy cell of a
     * (input, tier, algorithm) triple reuses the same rebuilt tri-dimensional platform.
     */
    private static void dvfsCell(
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
        PlatformProfile platform = dvfsSetup(NetworkStudyPlan.platform(vmCount, platformNetwork));
        String algorithm = dvfsAlgorithmLabel(planner, scheduler);
        String modelKind = model.getKind().name();
        for (SimulationConfig.FrequencyPolicy policy : DVFS_POLICIES) {
            String runId = dvfsRunId(inputId2, vmCount, algorithm, policy.name());
            Map<String, Object> record = new LinkedHashMap<String, Object>();
            record.put("runId", runId);
            record.put("inputId", inputId2);
            record.put("vmCount", Integer.valueOf(vmCount));
            record.put(
                    "layer",
                    planner != null
                            ? (fileSystem == ReplicaCatalog.FileSystem.SHARED ? "SHARED_DAG" : "LOCAL_DAG")
                            : "ONLINE");
            record.put("planner", planner != null ? planner.name() : null);
            record.put("scheduler", scheduler != null ? scheduler.name() : null);
            record.put("model", modelKind);
            record.put("frequencyPolicy", policy.name());
            record.put("carbonIntensityGramsPerKWh", Double.valueOf(CARBON_INTENSITY_GRAMS_PER_KWH));
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
                        .frequencyPolicy(policy)
                        .build();
                SimulationReport report = new SimulationRunner().run(config, platform);
                if (!report.getMetrics().isAllLogicalTasksCompletedSuccessfully()) {
                    throw new IllegalStateException("Incomplete workflow");
                }
                // Failures stay explicit: every DVFS cell declares the tri-dimensional
                // chain — a missing accounting or a tierless evidence bundle is a
                // contract break, not a zero-energy cell.
                if (report.getCarbonAccounting() == null) {
                    throw new IllegalStateException("DVFS benchmark cell declared no carbon accounting");
                }
                if (report.getEnergyAccounting().getTotalEnergyJoules() <= 0.0) {
                    throw new IllegalStateException(
                            "DVFS benchmark cell conserved no energy (tiered fleet is powered)");
                }
                ExperimentEvidenceContext context = ExperimentEvidenceContext.builder(DVFS_PROTOCOL)
                        .artifact("org.workflowsim", "benchmark", "1.0")
                        .driver(BenchmarkExecutor.class, "org/workflowsim/experiments/benchmark")
                        .protocol(DVFS_PROTOCOL, root.resolve("benchmark-dvfs-plan.json"))
                        .datasetRoot(inputPath.getParent())
                        .build();
                ExperimentArtifactWriter.ExperimentArtifacts artifacts = ExperimentArtifactWriter.write(
                        report, root.resolve("runs").resolve(runId), "result", context);
                ExperimentArtifactValidator.validate(artifacts.getManifest());
                record.put("status", "COMPLETED_SUCCESSFULLY");
                record.put(
                        "manifest",
                        root.relativize(artifacts.getManifest()).toString().replace('\\', '/'));
                record.put("makespanSeconds", Double.valueOf(report.getMakespan()));
                record.put(
                        "totalEnergyJoules",
                        Double.valueOf(report.getEnergyAccounting().getTotalEnergyJoules()));
                record.put(
                        "energyMakespanProductJouleSeconds",
                        Double.valueOf(report.getEnergyAccounting().getEnergyMakespanProductJouleSeconds()));
                record.put(
                        "totalCarbonGrams",
                        Double.valueOf(report.getCarbonAccounting().getTotalCarbonGrams()));
                record.put(
                        "meanVmUtilization", Double.valueOf(report.getMetrics().getMeanVmModeledIntervalUtilization()));
                // 台账面（tier mix 证据）：fleet per-tier busy seconds（键=档 mips 升序）。
                record.put("fleetPerTierBusySeconds", report.getMetrics().getFleetPerTierBusySeconds());
            } catch (Exception failure) {
                record.put("status", "FAILED");
                record.put("error", failure.getClass().getSimpleName() + ": " + failure.getMessage());
            }
            runs.add(record);
            writeDvfsIndex(root, runs);
        }
    }

    /** The frozen DVFS plan (matrix, tier table, laws, policies, and the sizing disclosure). */
    private static void writeDvfsPlan(Path root) throws IOException {
        Map<String, Object> plan = new LinkedHashMap<String, Object>();
        plan.put("schema", "workflowsim-benchmark-plan-v1");
        plan.put("protocol", DVFS_PROTOCOL);
        plan.put("javaClass", BenchmarkExecutor.class.getName());
        plan.put(
                "fleet",
                "tri-dimensional: 5-tier DVFS P-state table + per-VM power-free flat fields"
                        + " (tier table replaces them, BAT-D1) + uniform carbon intensity 380 g/kWh riding"
                        + " the per-tier conserved energy");
        List<Map<String, Object>> tiers = new ArrayList<Map<String, Object>>();
        for (PlatformProfile.FrequencyTier tier : DVFS_TIERS) {
            Map<String, Object> row = new LinkedHashMap<String, Object>();
            row.put("mips", Double.valueOf(tier.getMips()));
            row.put("idlePowerWatts", Double.valueOf(tier.getIdlePowerWatts()));
            row.put("maxPowerWatts", Double.valueOf(tier.getMaxPowerWatts()));
            tiers.add(row);
        }
        plan.put("frequencyTiers", tiers);
        plan.put(
                "frequencyTierLaws",
                Arrays.asList(
                        "dynamic = 150 W x (mips/1000)^3 at/below the nominal 1000 tier (the Beloglazov"
                                + " dynamic of benchmark-standard-power-v1); the 2000 turbo tier declares"
                                + " 3750 W dynamic (overvolted above the efficient point)",
                        "idle = 250 W at/above nominal; strictly below nominal linear leakage 0.2 W/MIPS"
                                + " (500->100, 250->50, 125->25)",
                        "declared values are frozen constants (a declared model), not generator output"));
        List<String> policies = new ArrayList<String>();
        for (SimulationConfig.FrequencyPolicy policy : DVFS_POLICIES) {
            policies.add(policy.name());
        }
        plan.put("frequencyPolicies", policies);
        plan.put(
                "algorithms",
                Arrays.asList(
                        DVFS_SHARED_PLANNER.name() + " (SHARED_DAG layer representative)",
                        DVFS_LOCAL_PLANNER.name() + " (LOCAL_DAG layer representative, endpoint contention)",
                        DVFS_ONLINE_LABEL.name() + " (ONLINE layer representative)"));
        plan.put(
                "cellSizing",
                "90 cells = 5 inputs x 2 VM tiers x 3 policies x 3 layer-representative"
                        + " algorithms; the full grid would be 480 cells (16 algorithm combinations x 3"
                        + " policies) and would miss the frozen wall-clock budget of minutes — the"
                        + " 90-cell representative subset covers every input family, both VM tiers, and all"
                        + " three decision layers, and keeps the policy x policy comparison same-layer-only");
        plan.put("carbonIntensityGramsPerKWh", Double.valueOf(CARBON_INTENSITY_GRAMS_PER_KWH));
        ExperimentArtifactWriter.writeJson(root.resolve("benchmark-dvfs-plan.json"), plan);
    }

    private static void writeDvfsIndex(Path root, List<Map<String, Object>> runs) throws IOException {
        Map<String, Object> index = new LinkedHashMap<String, Object>();
        index.put("schema", "workflowsim-benchmark-v1");
        index.put("protocol", DVFS_PROTOCOL);
        index.put("layers", "SHARED_DAG;LOCAL_DAG;ONLINE");
        index.put("frequencyPolicies", "DEFAULT;MIN_POWER;EDP_HEURISTIC");
        index.put("carbonModel", "CARBON_INTENSITY_V1");
        index.put("runCount", Integer.valueOf(runs.size()));
        index.put("runs", runs);
        ExperimentArtifactWriter.writeJson(root.resolve(DVFS_INDEX), index);
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
     * Run id of a budget-scenario cell: the model slot carries the carbon-model tag
     * instead of the data-movement kind (which stays shared with the SHARED battery), so
     * the budget cells never collide with the intensity cells of the same
     * input/tier/policy.
     */
    static String budgetRunId(String inputId2, int vm, String policy) {
        return inputId2 + "-v" + vm + "-CARBON_BUDGET-" + policy + "-s11";
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
     * Rebuilds the powered benchmark platform with the uniform per-VM carbon intensity
     * (job batch C4, 2026-10-28). Chains the frozen {@link #withPower(PlatformProfile)}
     * rebuild, then copies every host, VM, pin, storage, cost, and topology field
     * verbatim once more, extending each VM into the 12-argument
     * {@link PlatformProfile.VmSpec} that adds {@code carbonIntensityGramsPerKWh=380.0}
     * on top of the power envelope. No carbon window is declared — the intensity and
     * budget models are mutually exclusive by contract (BAT-C1).
     */
    static PlatformProfile withCarbon(PlatformProfile unpowered) {
        return rebuildCarbon(withPower(unpowered), Double.valueOf(CARBON_INTENSITY_GRAMS_PER_KWH), false);
    }

    /**
     * Rebuilds the powered benchmark platform as the CARBON_BUDGET scenario (job batch
     * C4, 2026-10-28): the same chained {@link #withPower(PlatformProfile)} copy, every
     * VM kept power-only (per-VM carbon intensity stays undeclared — BAT-C1 mutual
     * exclusivity) and the builder finally declares the two green-power windows
     * [0, 3600) @ 300 W and [3600, 7200) @ 600 W.
     */
    static PlatformProfile withCarbonBudget(PlatformProfile unpowered) {
        return rebuildCarbon(withPower(unpowered), null, true);
    }

    /**
     * Shared copy core of the two carbon rebuilds: every host, VM, pin, storage, cost,
     * and topology field is copied verbatim from the powered profile (obtained by
     * chaining {@link #withPower(PlatformProfile)}), and each VM is rebuilt through the
     * 12-argument {@link PlatformProfile.VmSpec}, adding the given carbon intensity (or
     * keeping it undeclared for the budget scenario). The caller passes either an
     * intensity or the budget windows — never both — mirroring the BAT-C1 exclusivity
     * that {@code SimulationReport} enforces at run time.
     */
    private static PlatformProfile rebuildCarbon(
            PlatformProfile powered, Double carbonIntensity, boolean carbonWindows) {
        if (carbonIntensity != null && carbonWindows) {
            throw new IllegalStateException("rebuildCarbon carries one carbon model per profile (BAT-C1)");
        }
        for (PlatformProfile.VmSpec vm : powered.getVms()) {
            if (vm.getCarbonIntensityGramsPerKWh() != null) {
                throw new IllegalStateException("rebuildCarbon expects the power-only benchmark platform, VM "
                        + vm.getId() + " already declares a carbon intensity");
            }
        }
        if (!powered.getCarbonWindows().isEmpty()) {
            throw new IllegalStateException("rebuildCarbon expects the power-only benchmark platform, carbon"
                    + " windows are already declared");
        }
        PlatformProfile.Builder builder = PlatformProfile.builder(powered.getName())
                .storage(powered.getStorage())
                .costs(powered.getCosts());
        for (PlatformProfile.HostSpec host : powered.getHosts()) {
            builder.addHost(new PlatformProfile.HostSpec(
                    host.getId(),
                    host.getPes(),
                    host.getMipsPerPe(),
                    host.getRamMb(),
                    host.getBandwidth(),
                    host.getStorageMb()));
        }
        for (PlatformProfile.VmSpec vm : powered.getVms()) {
            if (!vm.hasPowerModel()) {
                throw new IllegalStateException("rebuildCarbon expects the chained withPower output, VM " + vm.getId()
                        + " declares no power model");
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
                    vm.getIdlePowerWatts(),
                    vm.getMaxPowerWatts(),
                    carbonIntensity));
        }
        for (Map.Entry<Integer, Integer> pin : powered.getPinnedVmHostIds().entrySet()) {
            builder.pinVmToHost(pin.getKey().intValue(), pin.getValue().intValue());
        }
        if (powered.getNetworkTopology() != null) {
            builder.networkTopology(powered.getNetworkTopology());
        }
        if (powered.getSourceStorage() != null) {
            builder.sourceStorage(powered.getSourceStorage());
        }
        if (carbonWindows) {
            builder.carbonWindow(0.0, CARBON_WINDOW_1_END_SECONDS, CARBON_WINDOW_1_GREEN_WATTS);
            builder.carbonWindow(CARBON_WINDOW_1_END_SECONDS, CARBON_WINDOW_2_END_SECONDS, CARBON_WINDOW_2_GREEN_WATTS);
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
            String platformNetwork,
            boolean carbonBudget)
            throws IOException {
        String policy = planner != null ? planner.name() : scheduler.name();
        String modelKind = model.getKind().name();
        String runId =
                carbonBudget ? budgetRunId(inputId2, vmCount, policy) : runId(inputId2, vmCount, policy, modelKind);
        Map<String, Object> record = new LinkedHashMap<String, Object>();
        record.put("runId", runId);
        record.put("inputId", inputId2);
        record.put("vmCount", Integer.valueOf(vmCount));
        record.put("planner", planner != null ? planner.name() : null);
        record.put("scheduler", scheduler != null ? scheduler.name() : null);
        record.put("model", modelKind);
        record.put("carbonModel", carbonBudget ? "CARBON_BUDGET_V1" : "CARBON_INTENSITY_V1");
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
            platform = carbonBudget ? withCarbonBudget(platform) : withCarbon(platform);
            SimulationReport report = new SimulationRunner().run(config, platform);
            if (!report.getMetrics().isAllLogicalTasksCompletedSuccessfully()) {
                throw new IllegalStateException("Incomplete workflow");
            }
            if (report.getCarbonAccounting() == null) {
                // Failures stay explicit: every carbon benchmark cell declares exactly one
                // carbon model; a missing accounting is a contract break, not zero carbon.
                throw new IllegalStateException("Carbon benchmark cell declared no carbon accounting (BAT-C1)");
            }
            ExperimentEvidenceContext context = ExperimentEvidenceContext.builder(PROTOCOL)
                    .artifact("org.workflowsim", "benchmark", "1.0")
                    .driver(BenchmarkExecutor.class, "org/workflowsim/experiments/benchmark")
                    .protocol(PROTOCOL, root.resolve("benchmark-plan.json"))
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
            if (carbonBudget) {
                record.put(
                        "totalCarbonExcessWattSeconds",
                        Double.valueOf(report.getCarbonAccounting().getTotalCarbonExcessWattSeconds()));
                record.put(
                        "windowExcessWattSeconds", report.getCarbonAccounting().getWindowExcessWattSeconds());
            } else {
                record.put(
                        "totalCarbonGrams",
                        Double.valueOf(report.getCarbonAccounting().getTotalCarbonGrams()));
                record.put(
                        "meanVmCarbonGrams",
                        Double.valueOf(report.getCarbonAccounting().getMeanVmCarbonGrams()));
                record.put(
                        "carbonMakespanProductGramSeconds",
                        Double.valueOf(report.getCarbonAccounting().getCarbonMakespanProductGramSeconds()));
            }
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
        plan.put("protocol", PROTOCOL);
        plan.put("javaClass", BenchmarkExecutor.class.getName());
        // Frozen carbon scenario declaration: the intensity grid (CASPER family) and the
        // budget demonstration family (CWM family) in one immutably recorded place.
        plan.put("carbonIntensityGramsPerKWh", Double.valueOf(CARBON_INTENSITY_GRAMS_PER_KWH));
        List<List<Double>> windows = new ArrayList<List<Double>>();
        windows.add(Arrays.asList(
                Double.valueOf(0.0),
                Double.valueOf(CARBON_WINDOW_1_END_SECONDS),
                Double.valueOf(CARBON_WINDOW_1_GREEN_WATTS)));
        windows.add(Arrays.asList(
                Double.valueOf(CARBON_WINDOW_1_END_SECONDS),
                Double.valueOf(CARBON_WINDOW_2_END_SECONDS),
                Double.valueOf(CARBON_WINDOW_2_GREEN_WATTS)));
        plan.put("budgetCarbonWindows", windows);
        plan.put("budgetPlanner", BUDGET_PLANNER.name());
        ExperimentArtifactWriter.writeJson(root.resolve("benchmark-plan.json"), plan);
    }

    private static void writeIndex(Path root, List<Map<String, Object>> runs) throws IOException {
        Map<String, Object> index = new LinkedHashMap<String, Object>();
        index.put("schema", "workflowsim-benchmark-v1");
        index.put("protocol", PROTOCOL);
        index.put("layers", "SHARED_DAG;LOCAL_DAG;ONLINE");
        index.put("carbonModels", "CARBON_INTENSITY_V1;CARBON_BUDGET_V1");
        index.put("runCount", Integer.valueOf(runs.size()));
        index.put("runs", runs);
        ExperimentArtifactWriter.writeJson(root.resolve(INDEX), index);
    }
}
