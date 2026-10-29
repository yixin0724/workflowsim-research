package org.workflowsim.experiments.registry;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.cloudbus.cloudsim.Log;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.workflowsim.data.DataMovementModel;
import org.workflowsim.experiment.ExperimentArtifactValidator;
import org.workflowsim.experiment.ExperimentArtifactWriter;
import org.workflowsim.experiment.SimulationReport;
import org.workflowsim.experiment.SimulationRunner;
import org.workflowsim.experiment.registry.AlgorithmRegistry;
import org.workflowsim.experiment.registry.SchedulingAlgorithmProvider;
import org.workflowsim.experiments.benchmark.BenchmarkExecutor;
import org.workflowsim.platform.PlatformProfile;
import org.workflowsim.utils.Parameters.ClassType;
import org.workflowsim.utils.Parameters.PlanningAlgorithm;
import org.workflowsim.utils.Parameters.SchedulingAlgorithm;
import org.workflowsim.utils.ReplicaCatalog;
import org.workflowsim.utils.SimulationConfig;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * D6 (2026-10-29): the registered <b>EDP_GREEDY</b> DVFS-aware demonstrator — the
 * closing acceptance test of the DVFS dimension's registry face
 * (docs/advanced/DVFS_DIMENSION_DESIGN.md §2.6 and batch plan D6).
 *
 * <p>Three acceptance surfaces, mirroring the C5-lite carbon demonstrator:</p>
 * <ol>
 *   <li><b>Registry acceptance</b>: SPI discovery + the full declaration implication
 *       chain ({@code dvfsAware + frequencyTiersRequired + powerModelRequired}) is
 *       enforced fail-fast — unpowered and tierless platforms are rejected with the
 *       clause cited, powered+tiered surfaces validate.</li>
 *   <li><b>Evidence chain</b>: a real standard-runner run on a 3-tier powered fleet
 *       (top three tiers of the frozen benchmark P-state table + carbon intensity
 *       380 g/kWh — the tri-dimensional DVFS+energy+carbon fleet): every compute
 *       Job carries its per-VM tier assignment, the manifest exports the tier table +
 *       energyMetrics + carbonMetrics, the session frequencyPolicy stays DEFAULT
 *       (the per-Job decisions are the ALGORITHM's through the scheduling-time API),
 *       and the repository's independent Python auditor re-verifies the per-tier
 *       energy conservation law and the ridden grams with zero issues.</li>
 *   <li><b>The first DVFS-aware scheduling comparison</b>: same DAG and platform,
 *       built-in FCFS (all jobs at the default tier) vs EDP_GREEDY (length-based
 *       per-Job tier split): the tier MIX is altered — the first DVFS-aware
 *       scheduling result — and the EDP ledger is reported honestly regardless of
 *       direction.</li>
 * </ol>
 */
class EdpGreedyRegistryIntegrationTest {

    @TempDir
    Path tempDir;

    private boolean logWasDisabled;

    @BeforeEach
    void quiet() {
        logWasDisabled = Log.isDisabled();
        Log.disable();
    }

    @AfterEach
    void restore() {
        Log.setDisabled(logWasDisabled);
    }

    // ------------------------------------------------------------------
    // 1. registry acceptance — the full implication chain, fail-fast
    // ------------------------------------------------------------------

    @Test
    void registryAcceptanceChainRejectsUnpoweredAndTierlessPlatforms() {
        SchedulingAlgorithmProvider provider = AlgorithmRegistry.find(EdpGreedySchedulerProvider.LABEL);
        assertSame(EdpGreedySchedulerProvider.class, provider.getClass(), "SPI discovery of EDP_GREEDY");

        // the full implication chain is part of the declaration: required ⇒ aware,
        // powerModelRequired ⇒ energyAware (the gates the registry actually enforces).
        assertTrue(provider.declaration().isDvfsAware(), "dvfsAware declaration");
        assertTrue(provider.declaration().isFrequencyTiersRequired(), "frequencyTiersRequired declaration");
        assertTrue(provider.declaration().isPowerModelRequired(), "powerModelRequired declaration — full chain");
        assertTrue(
                provider.declaration().isFrequencyTiersRequired()
                                == provider.declaration().isDvfsAware()
                        && provider.declaration().isFrequencyTiersRequired(),
                "frequencyTiersRequired ⇒ dvfsAware lattice (BAT-D4)");
        assertTrue(provider.declaration().isEnergyAware(), "powerModelRequired ⇒ energyAware lattice (BAT-E3)");

        // fail-fast face 1: unpowered platform.
        IllegalArgumentException unpowered = assertThrows(
                IllegalArgumentException.class,
                () -> AlgorithmRegistry.validate(
                        EdpGreedySchedulerProvider.LABEL,
                        new AlgorithmRegistry.ValidationInput(
                                ReplicaCatalog.FileSystem.LOCAL,
                                DataMovementModel.legacyWorkflowsimV1(),
                                false,
                                null,
                                PlanningAlgorithm.INVALID,
                                false,
                                false)),
                "powerModelRequired must reject an unpowered platform");
        assertTrue(
                unpowered.getMessage().contains("powerModelRequired"),
                "rejection must cite the power clause: " + unpowered.getMessage());

        // fail-fast face 2: powered but tierless platform — no tiers, no assignment.
        IllegalArgumentException tierless = assertThrows(
                IllegalArgumentException.class,
                () -> AlgorithmRegistry.validate(
                        EdpGreedySchedulerProvider.LABEL,
                        new AlgorithmRegistry.ValidationInput(
                                ReplicaCatalog.FileSystem.LOCAL,
                                DataMovementModel.legacyWorkflowsimV1(),
                                false,
                                null,
                                PlanningAlgorithm.INVALID,
                                true,
                                false)),
                "frequencyTiersRequired must reject a tierless platform");
        assertTrue(
                tierless.getMessage().contains("frequencyTiersRequired"),
                "rejection must cite the frequency-tiers clause: " + tierless.getMessage());

        // green face: powered + tiered platform validates.
        AlgorithmRegistry.validate(
                EdpGreedySchedulerProvider.LABEL,
                new AlgorithmRegistry.ValidationInput(
                        ReplicaCatalog.FileSystem.LOCAL,
                        DataMovementModel.legacyWorkflowsimV1(),
                        false,
                        null,
                        PlanningAlgorithm.INVALID,
                        true,
                        true));
    }

    // ------------------------------------------------------------------
    // 2. evidence chain — real run on the 3-tier powered fleet
    // ------------------------------------------------------------------

    @Test
    void edpGreedyRunCarriesPerJobTierEvidenceAndAuditsClean() throws Exception {
        SchedulingAlgorithmProvider provider = AlgorithmRegistry.find(EdpGreedySchedulerProvider.LABEL);
        Path dax = bagDax();
        PlatformProfile platform = threeTierCarbonFleet();

        SimulationConfig config = SimulationConfig.builder(dax.toString(), 2)
                .planningAlgorithm(PlanningAlgorithm.INVALID)
                .schedulingAlgorithm(SchedulingAlgorithm.INVALID)
                .fileSystem(ReplicaCatalog.FileSystem.LOCAL)
                .dataMovementModel(DataMovementModel.legacyWorkflowsimV1())
                .randomSeed(20261029L)
                // 会话策略保持 DEFAULT：逐 Job 档决策通过调度时刻 API（FrequencyTierSelector）
                // 归算法——任何 500 档 Job 都是算法的决策，不是会话斜切。
                .frequencyPolicy(SimulationConfig.FrequencyPolicy.DEFAULT)
                .registeredSchedulingAlgorithm(provider)
                .build();
        SimulationReport report = new SimulationRunner().run(config, platform);
        assertEquals(
                "COMPLETED_SUCCESSFULLY",
                report.getMetrics().getLogicalTaskCompletionStatus(),
                "the DVFS-aware arm must complete the workflow");

        // Every compute job carries a tier from the declared table; the length split is
        // exactly the D6 rule bag (40/30/95/35 s ⇒ 40000/30000/95000/35000 MI).
        Map<Integer, Double> declaredWorkByTaskPosition = new LinkedHashMap<Integer, Double>();
        double[] runtimeSeconds = {40, 30, 95, 35};
        for (int i = 0; i < runtimeSeconds.length; i++) {
            declaredWorkByTaskPosition.put(Integer.valueOf(i), Double.valueOf(runtimeSeconds[i] * 1000.0));
        }
        List<Integer> computeTaskIdsAscending = new ArrayList<Integer>();
        for (SimulationReport.JobOutcome job : report.getJobs()) {
            if (job.getClassType() == ClassType.COMPUTE.value) {
                assertTrue(job.getTierMips() != null, "every compute job gets a tier at admission");
                assertTrue(!job.getTaskIds().isEmpty(), "one task per job in the NONE-clustering bag");
                computeTaskIdsAscending.add(job.getTaskIds().get(0));
            }
        }
        assertEquals(4, computeTaskIdsAscending.size(), "the bag's four compute jobs");
        java.util.Collections.sort(computeTaskIdsAscending);
        Map<Integer, Double> tierByTaskId = new LinkedHashMap<Integer, Double>();
        for (SimulationReport.JobOutcome job : report.getJobs()) {
            if (job.getClassType() == ClassType.COMPUTE.value) {
                tierByTaskId.put(job.getTaskIds().get(0), job.getTierMips());
            }
        }
        for (Map.Entry<Integer, Double> position : declaredWorkByTaskPosition.entrySet()) {
            int taskId = computeTaskIdsAscending.get(position.getKey().intValue());
            double declaredWork = position.getValue().doubleValue();
            double expectedTier = declaredWork >= EdpGreedySchedulerProvider.LONG_JOB_THRESHOLD_MI ? 500.0 : 2000.0;
            assertEquals(
                    Double.valueOf(expectedTier),
                    tierByTaskId.get(Integer.valueOf(taskId)),
                    "length-based split on task position " + position.getKey() + " (declared work " + declaredWork
                            + " MI): long ⇒ last tier, short ⇒ first tier");
        }

        assertTrue(report.getEnergyAccounting().getTotalEnergyJoules() > 0.0, "tiered fleet is powered");
        assertTrue(report.getCarbonAccounting() != null, "declared intensity produces carbon accounting");
        assertTrue(report.getCarbonAccounting().getTotalCarbonGrams() > 0.0, "single-joule fleet carries grams");

        // Evidence bundle: manifest exports the tier table, per-Job tierMips, energy and
        // carbon sections, and the inert session policy alongside the registered label.
        ExperimentArtifactWriter.ExperimentArtifacts artifacts =
                ExperimentArtifactWriter.write(report, tempDir.resolve("edp-greedy-evidence"), "result");
        ExperimentArtifactValidator.validate(artifacts.getManifest());
        JsonObject manifest = JsonParser.parseString(
                        new String(Files.readAllBytes(artifacts.getManifest()), StandardCharsets.UTF_8))
                .getAsJsonObject();
        JsonArray fleetTiers = manifest.getAsJsonObject("platform")
                .getAsJsonArray("vms")
                .get(0)
                .getAsJsonObject()
                .getAsJsonArray("frequencyTiers");
        assertEquals(3, fleetTiers.size(), "the 3-tier powered fleet serializes its table");
        assertEquals(2000.0, fleetTiers.get(0).getAsJsonObject().get("mips").getAsDouble(), 0.0, "tier 1 mips");
        assertEquals(500.0, fleetTiers.get(2).getAsJsonObject().get("mips").getAsDouble(), 0.0, "tier 3 mips");
        int longTierJobs = 0;
        int shortTierJobs = 0;
        for (JsonElement element : manifest.getAsJsonObject("result").getAsJsonArray("jobs")) {
            JsonObject job = element.getAsJsonObject();
            if (job.get("classType").getAsInt() == ClassType.COMPUTE.value) {
                assertTrue(job.has("tierMips"), "compute jobs serialize tierMips (evidence face)");
                if (job.get("tierMips").getAsDouble() == 500.0) {
                    longTierJobs++;
                } else if (job.get("tierMips").getAsDouble() == 2000.0) {
                    shortTierJobs++;
                }
            }
        }
        assertEquals(1, longTierJobs, "exactly the longest task (95000 MI) runs at the last tier");
        assertEquals(3, shortTierJobs, "the three short tasks run at the default tier");
        assertEquals(
                "DEFAULT",
                manifest.getAsJsonObject("configuration").get("frequencyPolicy").getAsString(),
                "the session policy stays DEFAULT — the mix is the algorithm's per-Job decision");
        assertEquals(
                EdpGreedySchedulerProvider.LABEL,
                manifest.getAsJsonObject("configuration")
                        .get("registeredSchedulingAlgorithm")
                        .getAsString(),
                "the registered label serializes");
        assertTrue(
                manifest.getAsJsonObject("energyMetrics")
                                .get("totalEnergyJoules")
                                .getAsDouble()
                        > 0.0,
                "energyMetrics present and positive on the tiered fleet");
        assertTrue(
                manifest.getAsJsonObject("carbonMetrics")
                                .get("totalCarbonGrams")
                                .getAsDouble()
                        > 0.0,
                "carbonMetrics rides the per-tier joules at intensity 380");

        // The independent Python auditor recomputes the per-tier conservation law and the
        // ridden grams from the manifest alone — zero issues is the acceptance (D6).
        assumePythonAuditorPasses(artifacts.getManifest());
    }

    // ------------------------------------------------------------------
    // 3. the first DVFS-aware scheduling comparison (honest ledger)
    // ------------------------------------------------------------------

    @Test
    void fcfsVsEdpGreedyAltersTierMixOnTheSameDagAndPlatform() throws Exception {
        SchedulingAlgorithmProvider provider = AlgorithmRegistry.find(EdpGreedySchedulerProvider.LABEL);
        Path dax = bagDax();
        PlatformProfile platform = threeTierCarbonFleet();

        SimulationReport fcfs = new SimulationRunner().run(fcfsConfig(dax), platform);
        SimulationReport greedy = new SimulationRunner().run(registeredConfig(dax, provider), platform);
        assertEquals(
                "COMPLETED_SUCCESSFULLY", fcfs.getMetrics().getLogicalTaskCompletionStatus(), "FCFS arm completes");
        assertEquals(
                "COMPLETED_SUCCESSFULLY",
                greedy.getMetrics().getLogicalTaskCompletionStatus(),
                "EDP_GREEDY arm completes");

        // The expected demonstrator result: same placement order (FCFS inherited), so the
        // only delta vs the DEFAULT-policy FCFS arm is the TIER MIX — the first
        // DVFS-aware scheduling result. Both directions of the EDP ledger are legal and
        // the numbers are printed for the honest ledger.
        List<Double> fcfsTiers = computeTierMultiset(fcfs);
        List<Double> greedyTiers = computeTierMultiset(greedy);
        assertEquals(4, fcfsTiers.size(), "the bag's four compute jobs (FCFS arm)");
        assertEquals(4, greedyTiers.size(), "the bag's four compute jobs (EDP_GREEDY arm)");
        assertEquals(
                1.0,
                countOf(greedyTiers, 500.0),
                "the longest task is stretched to the last tier by the scheduling-time API");
        assertEquals(3.0, countOf(greedyTiers, 2000.0), "the short tasks stay at the default (first) tier");
        assertEquals(0.0, countOf(fcfsTiers, 500.0), "the FCFS+DEFAULT arm assigns the default tier to every job");
        assertTrue(
                !fcfsTiers.equals(greedyTiers),
                "EDP_GREEDY alters the tier mix vs the DEFAULT policy — the first DVFS-aware result");

        double fcfsEnergy = fcfs.getEnergyAccounting().getTotalEnergyJoules();
        double greedyEnergy = greedy.getEnergyAccounting().getTotalEnergyJoules();
        double fcfsEdp = fcfs.getEnergyAccounting().getEnergyMakespanProductJouleSeconds();
        double greedyEdp = greedy.getEnergyAccounting().getEnergyMakespanProductJouleSeconds();
        System.out.println("[D6-compare] FCFS      : makespan=" + fcfs.getMakespan() + "s energy=" + fcfsEnergy
                + "J edp=" + fcfsEdp + " tierMix=all@2000");
        System.out.println("[D6-compare] EDP_GREEDY: makespan=" + greedy.getMakespan() + "s energy=" + greedyEnergy
                + "J edp=" + greedyEdp + " tierMix=3x2000+1x500");

        // Hand-derivable floors, not direction guesses: the stretched (95 000 MI) job
        // takes at least 95000/500 = 190 simulated seconds, and the DEFAULT arm's medians
        // of the same jobs run no slower than one job of 95000/2000 s. The demonstrator
        // is NOT an optimizer — the EDP direction stays whichever the ledger shows.
        assertTrue(greedy.getMakespan() >= fcfs.getMakespan(), "stretching the critical tail cannot finish earlier");
        assertTrue(
                greedyEnergy >= 2 * 250.0 * greedy.getMakespan(),
                "fleet energy never falls below the default-tier idle floor (2×250 W × makespan)");
    }

    // ------------------------------------------------------------------
    // the Python auditor — assumed available; zero issues required
    // ------------------------------------------------------------------

    /**
     * Runs scripts/verify-study-evidence.py over the freshly written evidence bundle and
     * requires a PASS with zero issues. Skipped (visibly, via assumption) when this
     * checkout cannot run the auditor — the auditor lives outside the Maven module.
     */
    private void assumePythonAuditorPasses(Path manifest) throws Exception {
        Path script = findRepositoryScript();
        Assumptions.assumeTrue(script != null, "python auditor script not found from this module");
        Assumptions.assumeTrue(
                new ProcessBuilder("python3", "--version").start().waitFor(30, TimeUnit.SECONDS),
                "python3 runtime unavailable");
        // the auditor refuses existing output files (never-overwrite contract) and any
        // path inside a source bundle — the temporary evidence directory's parent
        // (outside every bundle) satisfies both faces.
        Path reportOut = tempDir.resolve("edp-greedy-audit-report.json");
        Files.deleteIfExists(reportOut);
        ProcessBuilder builder = new ProcessBuilder(
                "python3", "-B", script.toString(), "--output", reportOut.toString(), manifest.toString());
        builder.environment().put("PYTHONDONTWRITEBYTECODE", "1");
        builder.redirectErrorStream(true);
        Process process = builder.start();
        StringBuilder output = new StringBuilder();
        try (BufferedReader reader =
                new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                output.append(line).append('\n');
            }
        }
        assertTrue(process.waitFor(120, TimeUnit.SECONDS), "the auditor subprocess must terminate");
        String issuesText;
        if (Files.exists(reportOut)) {
            issuesText = new String(Files.readAllBytes(reportOut), StandardCharsets.UTF_8);
        } else {
            issuesText = "no report file written";
        }
        assertEquals(0, process.exitValue(), "auditor exit code is nonzero on failure: " + output + issuesText);
        assertTrue(output.toString().startsWith("PASS:"), "auditor summary must be PASS: " + output + issuesText);
        JsonObject audit = JsonParser.parseString(issuesText).getAsJsonObject();
        assertEquals("PASS", audit.get("status").getAsString(), "auditor status: " + output + issuesText);
        for (JsonElement run : audit.getAsJsonArray("runs")) {
            assertEquals(
                    0,
                    run.getAsJsonObject().get("issueCount").getAsInt(),
                    "audited bundle must carry zero issues: " + output + issuesText);
        }
    }

    /** Locates scripts/verify-study-evidence.py walking up from the module directory. */
    private static Path findRepositoryScript() {
        Path current = Paths.get(System.getProperty("user.dir")).toAbsolutePath();
        for (int depth = 0; depth < 5 && current != null; depth++) {
            Path candidate = current.resolve("scripts").resolve("verify-study-evidence.py");
            if (Files.isRegularFile(candidate)) {
                return candidate;
            }
            current = current.getParent();
        }
        return null;
    }

    // ------------------------------------------------------------------
    // fixtures
    // ------------------------------------------------------------------

    /** 无依赖 bag（40/30/95/35 秒——E5/碳示范同族）：4 个计算 Job 同时就绪。 */
    private Path bagDax() throws Exception {
        StringBuilder dax = new StringBuilder();
        dax.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<adag count=\"4\">\n");
        int[] runs = {40, 30, 95, 35};
        for (int i = 1; i <= runs.length; i++) {
            dax.append("  <job id=\"t")
                    .append(i)
                    .append("\" name=\"b")
                    .append(i)
                    .append("\" runtime=\"")
                    .append(runs[i - 1])
                    .append("\"/>\n");
        }
        dax.append("</adag>\n");
        Path path = tempDir.resolve("edp-greedy-bag.dax");
        Files.write(path, dax.toString().getBytes(StandardCharsets.UTF_8));
        return path;
    }

    /**
     * The D6 demonstrator fleet: the top three tiers of the frozen DVFS benchmark
     * P-state table ({@link BenchmarkExecutor#benchmarkDvfsTiers()} — turbo 2000 /
     * nominal 1000 / half 500) on two uniform 1000-MIPS SPACE_SHARED VMs, carrying
     * the benchmark's uniform carbon intensity 380 g/kWh — the tri-dimensional
     * DVFS+energy+carbon surface.
     */
    private static PlatformProfile threeTierCarbonFleet() {
        List<PlatformProfile.FrequencyTier> fleetTiers =
                BenchmarkExecutor.benchmarkDvfsTiers().subList(0, 3);
        PlatformProfile.Builder builder = PlatformProfile.builder("d6-edp-greedy-fleet");
        int[] ids = {0, 1};
        for (int i = 0; i < 2; i++) {
            builder.addHost(new PlatformProfile.HostSpec(100 + i, 2, 2000, 2048, 10000L, 1000000L))
                    .addVm(new PlatformProfile.VmSpec(
                            ids[i],
                            1000,
                            1,
                            512,
                            1000L,
                            10000L,
                            "Xen",
                            PlatformProfile.CloudletSchedulerMode.SPACE_SHARED,
                            null,
                            0.0,
                            0.0,
                            Double.valueOf(BenchmarkExecutor.CARBON_INTENSITY_GRAMS_PER_KWH),
                            fleetTiers))
                    .pinVmToHost(ids[i], 100 + i);
        }
        return builder.build();
    }

    private static SimulationConfig registeredConfig(Path dax, SchedulingAlgorithmProvider provider) {
        return SimulationConfig.builder(dax.toString(), 2)
                .planningAlgorithm(PlanningAlgorithm.INVALID)
                .schedulingAlgorithm(SchedulingAlgorithm.INVALID)
                .fileSystem(ReplicaCatalog.FileSystem.LOCAL)
                .dataMovementModel(DataMovementModel.legacyWorkflowsimV1())
                .randomSeed(20261029L)
                .frequencyPolicy(SimulationConfig.FrequencyPolicy.DEFAULT)
                .registeredSchedulingAlgorithm(provider)
                .build();
    }

    private static SimulationConfig fcfsConfig(Path dax) {
        return SimulationConfig.builder(dax.toString(), 2)
                .planningAlgorithm(PlanningAlgorithm.INVALID)
                .schedulingAlgorithm(SchedulingAlgorithm.FCFS)
                .fileSystem(ReplicaCatalog.FileSystem.LOCAL)
                .dataMovementModel(DataMovementModel.legacyWorkflowsimV1())
                .randomSeed(20261029L)
                .build();
    }

    private static List<Double> computeTierMultiset(SimulationReport report) {
        List<Double> tiers = new ArrayList<Double>();
        for (SimulationReport.JobOutcome job : report.getJobs()) {
            if (job.getClassType() == ClassType.COMPUTE.value) {
                tiers.add(job.getTierMips());
            }
        }
        return tiers;
    }

    private static double countOf(List<Double> values, double target) {
        double count = 0.0;
        for (double value : values) {
            if (value == target) {
                count += 1.0;
            }
        }
        return count;
    }
}
