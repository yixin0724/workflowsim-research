package org.workflowsim.experiments.registry;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.cloudbus.cloudsim.Log;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.workflowsim.data.DataMovementModel;
import org.workflowsim.experiment.EnergyAccounting;
import org.workflowsim.experiment.ExperimentArtifactValidator;
import org.workflowsim.experiment.ExperimentArtifactWriter;
import org.workflowsim.experiment.SimulationReport;
import org.workflowsim.experiment.SimulationRunner;
import org.workflowsim.experiment.registry.AlgorithmRegistry;
import org.workflowsim.experiment.registry.SchedulingAlgorithmProvider;
import org.workflowsim.platform.PlatformProfile;
import org.workflowsim.utils.Parameters.PlanningAlgorithm;
import org.workflowsim.utils.Parameters.SchedulingAlgorithm;
import org.workflowsim.utils.ReplicaCatalog;
import org.workflowsim.utils.SimulationConfig;

/**
 * E5 (2026-10-27): the platform's first energy-aware scheduling comparison through the
 * algorithm registry — the closing demonstrator of the energy-dimension extension.
 *
 * <p>Design: the same powered platform and pure-bag DAG run twice — once with the built-in
 * FCFS label, once with the registered ENERGY_LPT_FIRST provider (longest-job-first). On a
 * pure bag with uniform VMs, LPT placement cannot increase makespan against assignment
 * order (Graham), and with the linear power model the total energy is strictly monotone in
 * makespan (E = Σ P_idle×T + (P_max−P_idle)×busy_total; busy_total is workload-fixed) —
 * so EDP(ENERGY_LPT_FIRST) ≤ EDP(FCFS) is a provable expectation, not a hope.</p>
 */
class EnergyAwareSchedulingComparisonIntegrationTest {

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

    @Test
    void energyAwareRegistryAlgorithmBeatsFcfsOnEdp() throws Exception {
        // 1. The provider must be discovered through the standard SPI.
        SchedulingAlgorithmProvider provider = AlgorithmRegistry.find(EnergyLptFirstSchedulerProvider.LABEL);
        assertSame(EnergyLptFirstSchedulerProvider.class, provider.getClass(), "SPI discovery");

        // 2. The energy contract is enforced fail-fast: unpowered platforms are rejected.
        assertThrows(
                IllegalArgumentException.class,
                () -> AlgorithmRegistry.validate(
                        EnergyLptFirstSchedulerProvider.LABEL,
                        new AlgorithmRegistry.ValidationInput(
                                ReplicaCatalog.FileSystem.LOCAL,
                                DataMovementModel.legacyWorkflowsimV1(),
                                false,
                                null,
                                PlanningAlgorithm.INVALID,
                                false)),
                "powerModelRequired must reject an unpowered platform");

        // 3. Both arms on the SAME powered platform and bag DAG.
        Path dax = bagDax();
        PlatformProfile platform = poweredPlatform();
        SimulationReport fcfs = run(dax, platform, null);
        SimulationReport lpt = run(dax, platform, provider);

        assertTrue(fcfs.getMetrics().isAllLogicalTasksCompletedSuccessfully(), "FCFS arm completes");
        assertTrue(lpt.getMetrics().isAllLogicalTasksCompletedSuccessfully(), "LPT arm completes");

        // 4. Evidence chain: the energy-aware arm exports a validated bundle.
        ExperimentArtifactWriter.ExperimentArtifacts artifacts =
                ExperimentArtifactWriter.write(lpt, tempDir.resolve("energy-aware-evidence"), "result");
        ExperimentArtifactValidator.validate(artifacts.getManifest());

        // 5. The provable comparison: LPT makespan ≤ FCFS makespan on a pure bag, hence
        // EDP monotone, hence EDP(LPT) ≤ EDP(FCFS). Both are strict on this fixture.
        EnergyAccounting fcfsEnergy = fcfs.getEnergyAccounting();
        EnergyAccounting lptEnergy = lpt.getEnergyAccounting();
        double fcfsEdp = edp(fcfsEnergy);
        double lptEdp = edp(lptEnergy);
        System.out.println("[E5-compare] FCFS: makespan=" + fcfs.getMakespan() + "s energy="
                + fcfsEnergy.getTotalEnergyJoules() + "J edp=" + fcfsEdp);
        System.out.println("[E5-compare] LPT : makespan=" + lpt.getMakespan() + "s energy="
                + lptEnergy.getTotalEnergyJoules() + "J edp=" + lptEdp);
        assertTrue(
                lpt.getMakespan() <= fcfs.getMakespan(),
                "LPT cannot lose to FCFS on a bag: " + lpt.getMakespan() + " vs " + fcfs.getMakespan());
        assertTrue(lptEdp <= fcfsEdp, "EDP monotone in makespan: " + lptEdp + " vs " + fcfsEdp);
        assertTrue(
                lptEdp < fcfsEdp,
                "this fixture must expose a strict EDP reduction " + "(long-tail bag): " + lptEdp + " vs " + fcfsEdp);
    }

    private static double edp(EnergyAccounting energy) {
        return energy.getEnergyMakespanProductJouleSeconds();
    }

    private SimulationReport run(Path dax, PlatformProfile platform, SchedulingAlgorithmProvider provider)
            throws Exception {
        SimulationConfig.Builder builder = SimulationConfig.builder(dax.toString(), 2)
                .planningAlgorithm(PlanningAlgorithm.INVALID)
                .schedulingAlgorithm(provider != null ? SchedulingAlgorithm.INVALID : SchedulingAlgorithm.FCFS)
                .fileSystem(ReplicaCatalog.FileSystem.LOCAL)
                .dataMovementModel(DataMovementModel.legacyWorkflowsimV1())
                .randomSeed(20261027L);
        if (provider != null) {
            builder.registeredSchedulingAlgorithm(provider);
        }
        return new SimulationRunner().run(builder.build(), platform);
    }

    /**
     * Pure bag, deliberately submission-shuffled: (40, 30, 95, 35) with the dominant job
     * in the middle. FCFS hands the two smallest to the two idle VMs, so the 95-length
     * job lands on whichever VM frees first and stacks its remainder (makespan 125);
     * LPT grabs the 95 first (makespan 105). A balanced bag would make the two policies
     * coincide — this fixture is designed to expose the gap.
     */
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
        Path path = tempDir.resolve("bag.dax");
        Files.write(path, dax.toString().getBytes(StandardCharsets.UTF_8));
        return path;
    }

    /** Two powered uniform VMs (250 W idle / 400 W max). */
    private PlatformProfile poweredPlatform() {
        PlatformProfile.Builder builder = PlatformProfile.builder("e5-powered");
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
                            250.0,
                            400.0))
                    .pinVmToHost(ids[i], 100 + i);
        }
        return builder.build();
    }
}
