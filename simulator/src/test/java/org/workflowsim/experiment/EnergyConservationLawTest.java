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

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

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
 * 4. BAT-E2 manifest energy export: the written energyMetrics section survives full
 *    artifact validation and satisfies the same identities (EDP, peak power, idle floor)
 * 5. BAT-E4 platform power-field export: every manifest VM serializes idlePowerWatts/
 *    maxPowerWatts, because the independent Python auditor keys its powered/unpowered
 *    branch and conservation-law recompute off exactly those manifest fields
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

    /**
     * BAT-E2 (2026-10-27): a powered run must export its energy dimension in the evidence
     * manifest as an {@code energyMetrics} section that survives full
     * {@link ExperimentArtifactValidator} validation. The section is independently
     * recomputable from the manifest's own makespan and per-VM busy intervals, so this test
     * re-derives the identities from the shipped JSON: peak power equals the fleet maximum
     * (2 VMs × 400 W), EDP equals totalEnergyJoules × makespan at zero tolerance, per-VM
     * energy never drops below the idle floor (P_idle × makespan), and total energy strictly
     * exceeds the two-VM idle floor because the two-task chain (60 s → 40 s, dependent) banks
     * strictly positive active energy. The DAG is a two-task dependency chain; the platform
     * is the standard powered profile (100 W idle / 400 W max, 1000 MIPS per VM).
     */
    @Test
    void manifestExportsEnergyMetricsAndSurvivesValidation() throws Exception {
        SimulationConfig config = SimulationConfig.builder(twoTaskDax().toString(), 2)
                .planningAlgorithm(PlanningAlgorithm.SHARED_STORAGE_HEFT)
                .schedulingAlgorithm(SchedulingAlgorithm.STATIC)
                .fileSystem(ReplicaCatalog.FileSystem.SHARED)
                .dataMovementModel(DataMovementModel.legacyWorkflowsimV1())
                .randomSeed(20261027L)
                .build();
        PlatformProfile platform = poweredPlatform(100.0, 400.0);
        SimulationReport report = new SimulationRunner().run(config, platform);

        ExperimentArtifactWriter.ExperimentArtifacts artifacts =
                ExperimentArtifactWriter.write(report, tempDir.resolve("energy-manifest-test"), "result", null);
        ExperimentArtifactValidator.validate(artifacts.getManifest());

        JsonObject manifest = JsonParser.parseString(
                        new String(Files.readAllBytes(artifacts.getManifest()), StandardCharsets.UTF_8))
                .getAsJsonObject();
        double makespan = manifest.getAsJsonObject("result").get("makespan").getAsDouble();
        JsonObject energyMetrics = manifest.getAsJsonObject("energyMetrics");
        assertNotNull(energyMetrics, "powered run must export an energyMetrics section");

        double totalEnergyJoules = energyMetrics.get("totalEnergyJoules").getAsDouble();
        assertTrue(totalEnergyJoules > 0.0, "total energy must be positive for a powered run");
        assertTrue(
                energyMetrics.get("meanVmEnergyJoules").getAsDouble() > 0.0,
                "mean per-VM energy must be positive for a powered run");
        assertEquals(
                800.0,
                energyMetrics.get("peakPowerWatts").getAsDouble(),
                0.0,
                "peak power = fleet maximum of the profile's two 400W VMs");
        assertEquals(
                0,
                Double.compare(
                        totalEnergyJoules * makespan,
                        energyMetrics.get("energyMakespanProductJouleSeconds").getAsDouble()),
                "EDP = totalEnergyJoules × makespan at zero tolerance");

        JsonObject vmEnergyJoules = energyMetrics.getAsJsonObject("vmEnergyJoules");
        assertEquals(platform.getVms().size(), vmEnergyJoules.size(), "exactly one energy entry per profile VM");
        for (PlatformProfile.VmSpec spec : platform.getVms()) {
            String key = String.valueOf(spec.getId());
            assertTrue(vmEnergyJoules.has(key), "energy entry missing for VM " + key);
            assertTrue(
                    spec.getIdlePowerWatts() * makespan
                            <= vmEnergyJoules.get(key).getAsDouble(),
                    "VM " + key + " energy cannot fall below its idle floor (P_idle × makespan)");
        }

        assertTrue(
                totalEnergyJoules > makespan * 200.0,
                "totalEnergyJoules must strictly exceed the two-VM idle floor (2 × 100W × makespan);" + " total="
                        + totalEnergyJoules + ", floor=" + (makespan * 200.0));
    }

    /**
     * BAT-E4 (2026-10-27): the per-VM power fields must be serialized into the
     * manifest's {@code platform.vms} array. The independent Python auditor
     * (scripts/verify-study-evidence.py) reads {@code idlePowerWatts}/{@code maxPowerWatts}
     * from exactly this section to decide whether a run is powered and to recompute the
     * conservation law; a powered run whose platform section lacks them is silently
     * indistinguishable from an unpowered one, making the energy audit vacuous (see
     * docs/advanced/ENERGY_DIMENSION_DESIGN.md §2.1: the verifier recomputes the identity
     * from the manifest's own data). Legal expectation source: the Python-auditor
     * contract, not implementation output.
     */
    @Test
    void manifestPlatformSerializesPerVmPowerFields() throws Exception {
        SimulationConfig config = SimulationConfig.builder(twoTaskDax().toString(), 2)
                .planningAlgorithm(PlanningAlgorithm.SHARED_STORAGE_HEFT)
                .schedulingAlgorithm(SchedulingAlgorithm.STATIC)
                .fileSystem(ReplicaCatalog.FileSystem.SHARED)
                .dataMovementModel(DataMovementModel.legacyWorkflowsimV1())
                .randomSeed(20261027L)
                .build();
        PlatformProfile platform = poweredPlatform(100.0, 400.0);
        SimulationReport report = new SimulationRunner().run(config, platform);

        ExperimentArtifactWriter.ExperimentArtifacts artifacts =
                ExperimentArtifactWriter.write(report, tempDir.resolve("energy-power-export"), "result", null);
        ExperimentArtifactValidator.validate(artifacts.getManifest());

        JsonObject manifest = JsonParser.parseString(
                        new String(Files.readAllBytes(artifacts.getManifest()), StandardCharsets.UTF_8))
                .getAsJsonObject();
        JsonArray vms = manifest.getAsJsonObject("platform").getAsJsonArray("vms");
        assertEquals(platform.getVms().size(), vms.size(), "one platform.vms entry per profile VM");
        for (int i = 0; i < vms.size(); i++) {
            JsonObject vm = vms.get(i).getAsJsonObject();
            String where = "platform VM " + vm.get("id").getAsString();
            assertEquals(100.0, vm.get("idlePowerWatts").getAsDouble(), 0.0, where + " must serialize idlePowerWatts");
            assertEquals(400.0, vm.get("maxPowerWatts").getAsDouble(), 0.0, where + " must serialize maxPowerWatts");
        }
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

    /**
     * Minimal dependency-chain DAG (t1 60 s → t2 40 s) for the BAT-E2 manifest-energy
     * export test: two tasks are enough to produce strictly positive busy time, and the
     * chain guarantees the second task cannot start before the first finishes.
     */
    private Path twoTaskDax() throws Exception {
        StringBuilder dax = new StringBuilder();
        dax.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        dax.append("<adag version=\"3.3\" count=\"2\">\n");
        dax.append("  <job id=\"t1\" name=\"a\" runtime=\"60\"/>\n");
        dax.append("  <job id=\"t2\" name=\"b\" runtime=\"40\"/>\n");
        dax.append("  <child ref=\"t2\"><parent ref=\"t1\"/></child>\n");
        dax.append("</adag>\n");
        Path path = Paths.get(tempDir.toFile().getAbsolutePath(), "energy-manifest-test.dax");
        Files.write(path, dax.toString().getBytes(StandardCharsets.UTF_8));
        return path;
    }
}
