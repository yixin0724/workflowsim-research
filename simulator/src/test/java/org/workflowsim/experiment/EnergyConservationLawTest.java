package org.workflowsim.experiment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;

import org.cloudbus.cloudsim.Log;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.workflowsim.data.DataMovementModel;
import org.workflowsim.platform.PlatformProfile;
import org.workflowsim.utils.Parameters.PlanningAlgorithm;
import org.workflowsim.utils.Parameters.SchedulingAlgorithm;
import org.workflowsim.utils.ReplicaCatalog;
import org.workflowsim.utils.SimulationConfig;

/**
 * Energy-dimension conservation-law tests (BAT-E1, 2026-10-27). Red-first: the
 * conservation law (Beloglazov et al. FGCS 2012 Eq.(2), binary SPACE_SHARED) states:
 *
 * <pre>
 * E_total = Σ_vm P_idle × makespanSeconds + (P_max − P_idle) × vmBusyIntervalSeconds
 * </pre>
 *
 * with T_active per VM already present in the frozen VmMetrics. These tests verify:
 * 1. The energy conservation invariant (zero tolerance)
 * 2. Backward compatibility: un-powered profiles yield zero energy
 * 3. VmSpec power-field validation (fail-fast)
 */
class EnergyConservationLawTest {

    private boolean logWasDisabled;

    @TempDir
    Path tempDir;

    @BeforeEach
    void disableSimulationLog() {
        logWasDisabled = Log.isDisabled();
        Log.disable();
    }

    @AfterEach
    void restoreSimulationLog() {
        Log.setDisabled(logWasDisabled);
    }

    /**
     * Full-suite conservation check: run a real four-task DAG with powered VMs,
     * extract the report, and verify the energy identity to zero tolerance using
     * the report's own busy-interval and makespan fields.
     */
    @Test
    void energyConservationLawHoldsAtZeroTolerance() throws Exception {
        SimulationReport report = runScenario(100.0, 400.0);
        SimulationMetrics metrics = report.getMetrics();
        double makespan = report.getMakespan();
        PlatformProfile platform = poweredPlatform(100.0, 400.0);

        // Extract per-VM busy intervals from the frozen metrics
        Map<Integer, SimulationMetrics.VmMetrics> vmMetrics = metrics.getVmMetrics();
        assertTrue(!vmMetrics.isEmpty(), "run must produce per-VM metrics");

        // Compute the conservation-law expectation from first principles
        double expectedTotal = 0.0;
        for (Map.Entry<Integer, SimulationMetrics.VmMetrics> entry : vmMetrics.entrySet()) {
            PlatformProfile.VmSpec spec = platform.getVms().stream()
                    .filter(s -> s.getId() == entry.getKey())
                    .findFirst()
                    .orElse(null);
            assertNotNull(spec, "VM " + entry.getKey() + " must be in the platform");
            double e = spec.getIdlePowerWatts() * makespan
                    + (spec.getMaxPowerWatts() - spec.getIdlePowerWatts())
                            * entry.getValue().getModeledBusyIntervalSeconds();
            expectedTotal += e;
        }

        EnergyAccounting ea = EnergyAccounting.fromFrozenMetrics(makespan, vmMetrics, platform);
        assertEquals(
                0,
                Double.compare(expectedTotal, ea.getTotalEnergyJoules()),
                "conservation law must hold bit-exactly: expected=" + expectedTotal + " actual="
                        + ea.getTotalEnergyJoules());
        assertTrue(ea.getPeakPowerWatts() > 0, "peak power must be positive for powered VMs");
        assertEquals(
                0,
                Double.compare(ea.getTotalEnergyJoules() * makespan, ea.getEnergyMakespanProductJouleSeconds()),
                "EDP = totalEnergy × makespan");
    }

    @Test
    void unpoweredPlatformYieldsZeroEnergy() throws Exception {
        SimulationReport report = runScenario(0.0, 0.0);
        Map<Integer, SimulationMetrics.VmMetrics> vmMetrics =
                report.getMetrics().getVmMetrics();
        PlatformProfile platform = unpoweredPlatform();
        EnergyAccounting ea = EnergyAccounting.fromFrozenMetrics(report.getMakespan(), vmMetrics, platform);
        assertEquals(0.0, ea.getTotalEnergyJoules(), 0.0, "no power model → zero energy");
        assertEquals(0.0, ea.getPeakPowerWatts(), 0.0);
    }

    @Test
    void vmSpecRejectsInvalidPowerFields() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new PlatformProfile.VmSpec(
                        0,
                        1000.0,
                        1,
                        512,
                        1000L,
                        10000L,
                        "Xen",
                        PlatformProfile.CloudletSchedulerMode.SPACE_SHARED,
                        null,
                        -5.0,
                        100.0),
                "negative idle power must be rejected");
        assertThrows(
                IllegalArgumentException.class,
                () -> new PlatformProfile.VmSpec(
                        0,
                        1000.0,
                        1,
                        512,
                        1000L,
                        10000L,
                        "Xen",
                        PlatformProfile.CloudletSchedulerMode.SPACE_SHARED,
                        null,
                        120.0,
                        100.0),
                "max below idle must be rejected");
        assertThrows(
                IllegalArgumentException.class,
                () -> new PlatformProfile.VmSpec(
                        0,
                        1000.0,
                        1,
                        512,
                        1000L,
                        10000L,
                        "Xen",
                        PlatformProfile.CloudletSchedulerMode.SPACE_SHARED,
                        null,
                        50.0,
                        Double.NaN),
                "NaN max must be rejected");
        // Valid: zero/zero is legal (no power model declared)
        PlatformProfile.VmSpec ok = new PlatformProfile.VmSpec(
                0,
                1000.0,
                1,
                512,
                1000L,
                10000L,
                "Xen",
                PlatformProfile.CloudletSchedulerMode.SPACE_SHARED,
                null,
                0.0,
                0.0);
        assertEquals(0.0, ok.getIdlePowerWatts(), 0.0);
        // Valid: positive idle and max, max >= idle
        PlatformProfile.VmSpec ok2 = new PlatformProfile.VmSpec(
                0,
                1000.0,
                1,
                512,
                1000L,
                10000L,
                "Xen",
                PlatformProfile.CloudletSchedulerMode.SPACE_SHARED,
                null,
                80.0,
                250.0);
        assertTrue(ok2.hasPowerModel());
    }

    // --- fixtures ---

    private SimulationReport runScenario(double idlePower, double maxPower) throws Exception {
        Path workflow = dax();
        PlatformProfile platform =
                idlePower > 0 || maxPower > 0 ? poweredPlatform(idlePower, maxPower) : unpoweredPlatform();
        SimulationConfig config = SimulationConfig.builder(workflow.toString(), 2)
                .planningAlgorithm(PlanningAlgorithm.SHARED_STORAGE_HEFT)
                .schedulingAlgorithm(SchedulingAlgorithm.STATIC)
                .fileSystem(ReplicaCatalog.FileSystem.SHARED)
                .dataMovementModel(DataMovementModel.legacyWorkflowsimV1())
                .randomSeed(20261027L)
                .build();
        return new SimulationRunner().run(config, platform);
    }

    private static PlatformProfile poweredPlatform(double idle, double max) {
        PlatformProfile.Builder b = PlatformProfile.builder("energy-test");
        b.addHost(new PlatformProfile.HostSpec(0, 2, 2000.0, 2048, 10_000L, 1_000_000L));
        b.addVm(new PlatformProfile.VmSpec(
                0,
                1000.0,
                1,
                512,
                1_000L,
                10_000L,
                "Xen",
                PlatformProfile.CloudletSchedulerMode.SPACE_SHARED,
                null,
                idle,
                max));
        b.addHost(new PlatformProfile.HostSpec(1, 2, 2000.0, 2048, 10_000L, 1_000_000L));
        b.addVm(new PlatformProfile.VmSpec(
                1,
                1000.0,
                1,
                512,
                1_000L,
                10_000L,
                "Xen",
                PlatformProfile.CloudletSchedulerMode.SPACE_SHARED,
                null,
                idle,
                max));
        return b.build();
    }

    private static PlatformProfile unpoweredPlatform() {
        PlatformProfile.Builder b = PlatformProfile.builder("energy-test-unpowered");
        b.addHost(new PlatformProfile.HostSpec(0, 2, 2000.0, 2048, 10_000L, 1_000_000L));
        b.addVm(new PlatformProfile.VmSpec(
                0, 1000.0, 1, 512, 1_000L, 10_000L, "Xen", PlatformProfile.CloudletSchedulerMode.SPACE_SHARED));
        b.addHost(new PlatformProfile.HostSpec(1, 2, 2000.0, 2048, 10_000L, 1_000_000L));
        b.addVm(new PlatformProfile.VmSpec(
                1, 1000.0, 1, 512, 1_000L, 10_000L, "Xen", PlatformProfile.CloudletSchedulerMode.SPACE_SHARED));
        return b.build();
    }

    private Path dax() throws Exception {
        StringBuilder dax = new StringBuilder();
        dax.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        dax.append("<adag version=\"3.3\" count=\"4\">\n");
        dax.append("  <job id=\"t1\" name=\"a\" runtime=\"60\"/>\n");
        dax.append("  <job id=\"t2\" name=\"b\" runtime=\"50\"/>\n");
        dax.append("  <job id=\"t3\" name=\"c\" runtime=\"40\"/>\n");
        dax.append("  <job id=\"t4\" name=\"d\" runtime=\"30\"/>\n");
        dax.append("  <child ref=\"t3\"><parent ref=\"t1\"/></child>\n");
        dax.append("  <child ref=\"t4\"><parent ref=\"t2\"/></child>\n");
        dax.append("</adag>\n");
        Path path = Paths.get(tempDir.toFile().getAbsolutePath(), "energy-test.dax");
        Files.write(path, dax.toString().getBytes(StandardCharsets.UTF_8));
        return path;
    }
}
