package org.workflowsim.experiments.rerun;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
import org.workflowsim.experiment.ExperimentArtifactWriter;
import org.workflowsim.experiment.SimulationReport;
import org.workflowsim.experiment.SimulationRunner;
import org.workflowsim.platform.PlatformProfile;
import org.workflowsim.utils.Parameters.PlanningAlgorithm;
import org.workflowsim.utils.Parameters.SchedulingAlgorithm;
import org.workflowsim.utils.ReplicaCatalog;
import org.workflowsim.utils.SimulationConfig;

/**
 * BAT-C5.1（2026-10-28 第二轮）——带碳证据的复跑红→绿锚（R7 型缺口：重建器曾丢碳字段，
 * 复跑会现 /carbonMetrics 缺节分歧）。红修复前 DIVERGED；转发修复后须 IDENTICAL_CORE 且
 * 碳指标逐位回放。断言能量守恒证据不变侧作回归对照组。
 */
class CarbonRerunIntegrationTest {

    @TempDir
    Path directory;

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
    void intensityEvidenceRerunsIdentically() throws Exception {
        Path run = fixture(true);
        runRerunChain(run, "intensity");
    }

    @Test
    void budgetEvidenceRerunsIdentically() throws Exception {
        Path run = fixture(false);
        runRerunChain(run, "budget");
    }

    private void runRerunChain(Path run, String model) throws Exception {
        Path output = directory.resolve("rerun-" + model);
        RerunReport result = RerunDiffExecutor.execute(run, output);
        assertEquals(RerunVerdict.IDENTICAL_CORE, result.getVerdict(), result.toJson());

        com.google.gson.JsonObject original = com.google.gson.JsonParser.parseReader(new java.io.InputStreamReader(
                        java.nio.file.Files.newInputStream(run.resolve("result.manifest.json")),
                        StandardCharsets.UTF_8))
                .getAsJsonObject();
        com.google.gson.JsonObject repeat = com.google.gson.JsonParser.parseReader(new java.io.InputStreamReader(
                        java.nio.file.Files.newInputStream(output.resolve("rerun/result.manifest.json")),
                        StandardCharsets.UTF_8))
                .getAsJsonObject();
        com.google.gson.JsonObject a = original.getAsJsonObject("carbonMetrics");
        com.google.gson.JsonObject b = repeat.getAsJsonObject("carbonMetrics");
        assertEquals(
                model.equals("intensity") ? a.get("totalCarbonGrams") : a.get("totalCarbonExcessWattSeconds"),
                model.equals("intensity") ? b.get("totalCarbonGrams") : b.get("totalCarbonExcessWattSeconds"),
                model + " metric must replay bit-exactly");
    }

    private Path fixture(boolean intensityModel) throws Exception {
        Path run = directory.resolve("study/runs/original");
        Path input = run.getParent().getParent().resolve("inputs/carbon.dax");
        Files.createDirectories(input.getParent());
        Files.write(
                input,
                ("<adag version=\"3.3\" count=\"2\">"
                                + "<job id=\"a\" name=\"a\" runtime=\"30\"/>"
                                + "<child ref=\"b\"><parent ref=\"a\"/></child>"
                                + "<job id=\"b\" name=\"b\" runtime=\"45\"/></adag>")
                        .getBytes(StandardCharsets.UTF_8));
        SimulationConfig config = SimulationConfig.builder(
                        input.toAbsolutePath().toString(), 2)
                .planningAlgorithm(PlanningAlgorithm.SHARED_STORAGE_HEFT)
                .schedulingAlgorithm(SchedulingAlgorithm.STATIC)
                .fileSystem(ReplicaCatalog.FileSystem.SHARED)
                .dataMovementModel(DataMovementModel.legacyWorkflowsimV1())
                .randomSeed(20261028L)
                .build();
        PlatformProfile.Builder builder = PlatformProfile.builder("carbon-rerun");
        int[] ids = {0, 1};
        for (int i = 0; i < 2; i++) {
            builder.addHost(new PlatformProfile.HostSpec(500 + i, 2, 2000, 2048, 10000L, 1000000L))
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
                            400.0,
                            intensityModel ? Double.valueOf(380.0) : null))
                    .pinVmToHost(ids[i], 500 + i);
        }
        if (!intensityModel) {
            builder.carbonWindow(0.0, 30.0, 300.0).carbonWindow(30.0, 90.0, 600.0);
        }
        SimulationReport report = new SimulationRunner().run(config, builder.build());
        assertTrue(report.getMetrics().isAllLogicalTasksCompletedSuccessfully(), "fixture completes");
        assertTrue(report.getCarbonAccounting() != null, "carbon accounting must be present");
        ExperimentArtifactWriter.write(report, run, "result");
        return run;
    }
}
