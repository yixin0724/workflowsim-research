package org.workflowsim.experiments.registry;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
import org.workflowsim.experiment.CarbonAccounting;
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

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * C5-lite (2026-10-28): the registry accepts <b>carbon-aware</b> scheduling declarations
 * end-to-end — the closing demonstrator of the carbon-dimension extension
 * (docs/advanced/CARBON_DIMENSION_DESIGN.md §2.5).
 *
 * <p>Design: the registered CARBON_DEFER_FIRST provider declares
 * powerModelRequired + carbonObjectiveWeight 1.0 (energyAware implied — both v1 carbon
 * accountings ride the conserved energy chain). The test locks the three acceptance
 * surfaces: (1) SPI discovery and the fail-fast power gate, (2) a full standard-runner run on
 * a <b>powered platform with declared carbon windows</b>, so the report produces
 * <b>budget-model</b> carbon accounting, and (3) an evidence bundle that validates and carries
 * a non-{@code null} {@code carbonMetrics} section with a hand-derivable excess floor:
 * fleet power never falls below Σ idle = 500 W, window 1 green budget = 300 W, so every
 * second inside window 1 contributes at least (500−300) W of excess — an invariant, not an
 * implementation output.</p>
 */
class CarbonAwareRegistryIntegrationTest {

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
    void carbonAwareRegistryLabelRunsBudgetModelAndExportsValidatedEvidence() throws Exception {
        // 1. The provider must be discovered through the standard SPI (registry acceptance).
        SchedulingAlgorithmProvider provider = AlgorithmRegistry.find(CarbonDeferFirstSchedulerProvider.LABEL);
        assertSame(CarbonDeferFirstSchedulerProvider.class, provider.getClass(), "SPI discovery");
        assertTrue(provider.declaration().isCarbonAware(), "carbon-aware declaration");
        assertTrue(provider.declaration().isPowerModelRequired(), "power gate is part of the declaration");
        assertTrue(provider.declaration().isEnergyAware(), "v1 carbon rides the energy chain");

        // 2. Fail-fast gate: powerModelRequired rejects an unpowered platform (clauses cited).
        IllegalArgumentException unpowered = assertThrows(
                IllegalArgumentException.class,
                () -> AlgorithmRegistry.validate(
                        CarbonDeferFirstSchedulerProvider.LABEL,
                        new AlgorithmRegistry.ValidationInput(
                                ReplicaCatalog.FileSystem.LOCAL,
                                DataMovementModel.legacyWorkflowsimV1(),
                                false,
                                null,
                                PlanningAlgorithm.INVALID,
                                false)),
                "powerModelRequired must reject an unpowered platform");
        assertTrue(
                unpowered.getMessage().contains("powerModelRequired"),
                "rejection must cite the power clause: " + unpowered.getMessage());

        // 3. Full powered run with DECLARED CARBON WINDOWS → budget-model accounting.
        Path dax = bagDax();
        PlatformProfile platform = poweredPlatformWithCarbonWindows();
        SimulationConfig config = SimulationConfig.builder(dax.toString(), 2)
                .planningAlgorithm(PlanningAlgorithm.INVALID)
                .schedulingAlgorithm(SchedulingAlgorithm.INVALID)
                .fileSystem(ReplicaCatalog.FileSystem.LOCAL)
                .dataMovementModel(DataMovementModel.legacyWorkflowsimV1())
                .randomSeed(20261028L)
                .registeredSchedulingAlgorithm(provider)
                .build();
        SimulationReport report = new SimulationRunner().run(config, platform);
        assertEquals(
                "COMPLETED_SUCCESSFULLY",
                report.getMetrics().getLogicalTaskCompletionStatus(),
                "the carbon-aware arm must complete the workflow");

        CarbonAccounting carbon = report.getCarbonAccounting();
        assertTrue(carbon != null, "declared carbon windows must produce a budget-model accounting");
        double floor = (500.0 - 300.0) * Math.min(report.getMakespan(), 60.0);
        assertTrue(
                carbon.getTotalCarbonExcessWattSeconds() >= floor,
                "fleet power never falls below the 500W idle floor, so window 1 (green 300W) banks"
                        + " at least (500-300)×coverage W·s: total=" + carbon.getTotalCarbonExcessWattSeconds()
                        + ", floor=" + floor);
        assertTrue(carbon.getTotalCarbonGrams() == 0.0, "budget model leaves the intensity family zero");

        // 4. Evidence chain: the carbon-aware run exports a validated bundle whose manifest
        // serializes the platform's carbon windows and carries the conditional carbonMetrics.
        ExperimentArtifactWriter.ExperimentArtifacts artifacts =
                ExperimentArtifactWriter.write(report, tempDir.resolve("carbon-aware-evidence"), "result");
        ExperimentArtifactValidator.validate(artifacts.getManifest());
        JsonObject manifest = JsonParser.parseString(
                        new String(Files.readAllBytes(artifacts.getManifest()), StandardCharsets.UTF_8))
                .getAsJsonObject();
        assertEquals(
                "COMPLETED_SUCCESSFULLY",
                manifest.getAsJsonObject("result")
                        .get("logicalTaskCompletionStatus")
                        .getAsString(),
                "manifest completion status");
        JsonArray windows = manifest.getAsJsonObject("platform").getAsJsonArray("carbonWindows");
        assertEquals(2, windows.size(), "both declared carbon windows must serialize");
        JsonObject carbonMetrics = manifest.getAsJsonObject("carbonMetrics");
        assertTrue(carbonMetrics != null, "carbonMetrics section must be present for a declared model");
        double excess = carbonMetrics.get("totalCarbonExcessWattSeconds").getAsDouble();
        assertTrue(excess >= 0.0, "serialized excess must be non-negative: " + excess);
        assertEquals(
                report.getCarbonAccounting().getTotalCarbonExcessWattSeconds(),
                excess,
                0.0,
                "manifest excess equals the report's budget accounting at zero tolerance");
        assertEquals(
                0,
                carbonMetrics.getAsJsonObject("perVmCarbonGrams").size(),
                "budget model leaves perVmCarbonGrams empty (intensity family unused)");
    }

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

    /**
     * Two powered uniform VMs (250 W idle / 400 W max) with two declared green windows:
     * [0,60) @ 300 W and [60,120) @ 100 W. The idle floor alone (2×250 = 500 W) exceeds
     * the first window's 300 W budget, so the run banks a hand-derivable positive excess.
     */
    private PlatformProfile poweredPlatformWithCarbonWindows() {
        PlatformProfile.Builder builder = PlatformProfile.builder("c5-carbon-budget");
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
        builder.carbonWindow(0.0, 60.0, 300.0).carbonWindow(60.0, 120.0, 100.0);
        return builder.build();
    }
}
