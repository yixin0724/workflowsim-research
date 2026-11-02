package org.workflowsim.experiments.rerun;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

import org.cloudbus.cloudsim.Log;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.workflowsim.data.DataMovementModel;
import org.workflowsim.experiment.ExperimentArtifactWriter;
import org.workflowsim.experiment.SimulationReport;
import org.workflowsim.experiment.SimulationRunner;
import org.workflowsim.failure.FailureModelConfig;
import org.workflowsim.failure.FailureParameters;
import org.workflowsim.platform.PlatformProfiles;
import org.workflowsim.utils.DistributionGenerator.DistributionFamily;
import org.workflowsim.utils.DistributionSpec;
import org.workflowsim.utils.Parameters.PlanningAlgorithm;
import org.workflowsim.utils.Parameters.SchedulingAlgorithm;
import org.workflowsim.utils.ReplicaCatalog;
import org.workflowsim.utils.SimulationConfig;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * 审查轮（2026-11-02）：随机性敏感负载下的重跑契约。
 *
 * <p>此前的重跑模糊测试发现"篡改 rootSeed 仍判 IDENTICAL_CORE"，一度疑为漏洞；实测证明
 * 那是<b>对随机性不敏感</b>的负载：重跑忠实复现了被篡改的记录条件，两侧 seed 相同。
 * 本测试用随机失败重试的负载把这条性质钉死：</p>
 *
 * <ol>
 *   <li>同 seed 重跑 → IDENTICAL_CORE（记录条件可复现）；</li>
 *   <li>篡改 rootSeed → DIVERGED（seed 是科学条件，负载对其敏感时必须分歧）。</li>
 * </ol>
 *
 * <p>期望值来源：RERUN_DIFF_CONTRACT.md 的核心比较域（configuration 在域内，
 * 仅 algorithmContract 与 workflowPaths 是白名单）。</p>
 */
class SeedSensitivityRerunIntegrationTest {

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
    void aStochasticWorkloadRerunsIdenticallyAndSeedTamperingDiverges() throws Exception {
        Path study = directory.resolve("seed");
        Path run = writeStochasticRun(study, 20261107L);

        RerunReport sameSeed = RerunDiffExecutor.execute(run, study.resolve("out-same"));
        assertEquals(RerunVerdict.IDENTICAL_CORE, sameSeed.getVerdict(), sameSeed.toJson());

        Path tampered = study.resolve("tampered");
        copyDirectory(run, tampered);
        bumpSeed(tampered);
        RerunReport changedSeed = RerunDiffExecutor.execute(tampered, study.resolve("out-changed"));
        assertEquals(
                RerunVerdict.DIVERGED,
                changedSeed.getVerdict(),
                "a stochastic workload must diverge when the recorded seed changes: " + changedSeed.toJson());
        // 分歧出现在结果面而非配置面：重建从（被篡改的）记录条件出发，两侧 configuration
        // 因此相同，但不同 seed 会改变随机失败/重试的走向，结果必须大红。
        assertFalse(
                changedSeed.getCoreDivergences().isEmpty(),
                "a changed seed must produce core divergences in the result face: " + pointers(changedSeed));
        assertTrue(
                changedSeed.getCoreDivergences().stream()
                        .anyMatch(entry -> entry.getPointer().startsWith("/result")
                                || entry.getPointer().startsWith("/metrics")),
                "the divergence must land on recomputed results: " + pointers(changedSeed));
    }

    /** 随机失败重试负载：FAILURE_JOB + 威布尔分布，seed 决定哪些作业失败与何时重试。 */
    private Path writeStochasticRun(Path study, long seed) throws Exception {
        Path workflow = study.resolve("inputs/seed.dax");
        Files.createDirectories(workflow.getParent());
        Files.write(
                workflow,
                ("<adag version=\"3.3\" count=\"4\">"
                                + "<job id=\"a\" name=\"a\" runtime=\"30\"/>"
                                + "<job id=\"b\" name=\"b\" runtime=\"20\"/>"
                                + "<job id=\"c\" name=\"c\" runtime=\"15\"/>"
                                + "<job id=\"d\" name=\"d\" runtime=\"25\"/>"
                                + "<child ref=\"b\"><parent ref=\"a\"/></child>"
                                + "<child ref=\"c\"><parent ref=\"b\"/></child>"
                                + "<child ref=\"d\"><parent ref=\"c\"/></child></adag>")
                        .getBytes(StandardCharsets.UTF_8));
        DistributionSpec perDepth = DistributionSpec.of(DistributionFamily.WEIBULL, 20.0, 1.0);
        DistributionSpec[] byDepth = new DistributionSpec[8];
        Arrays.fill(byDepth, perDepth);
        FailureModelConfig failure = FailureModelConfig.builder()
                .clusteringAlgorithm(FailureParameters.FTCluteringAlgorithm.FTCLUSTERING_NOOP)
                .monitorMode(FailureParameters.FTCMonitor.MONITOR_NONE)
                .generatorMode(FailureParameters.FTCFailure.FAILURE_JOB)
                .generatorSpecs(new DistributionSpec[][] {byDepth})
                .maxTotalRetryJobs(200)
                .build();
        SimulationConfig config = SimulationConfig.builder(workflow.toString(), 2)
                .planningAlgorithm(PlanningAlgorithm.INVALID)
                .schedulingAlgorithm(SchedulingAlgorithm.FCFS)
                .fileSystem(ReplicaCatalog.FileSystem.SHARED)
                .dataMovementModel(DataMovementModel.legacyWorkflowsimV1())
                .failureModel(failure)
                .randomSeed(seed)
                .build();
        SimulationReport report = new SimulationRunner().run(config, PlatformProfiles.homogeneousLocal("seed", 2));
        Path run = study.resolve("runs/seed-" + seed);
        ExperimentArtifactWriter.write(report, run, "result");
        return run;
    }

    private static void bumpSeed(Path run) throws Exception {
        Path manifest = run.resolve("result.manifest.json");
        JsonObject doc = JsonParser.parseString(new String(Files.readAllBytes(manifest), StandardCharsets.UTF_8))
                .getAsJsonObject();
        JsonObject configuration = doc.getAsJsonObject("configuration");
        configuration.addProperty("rootSeed", configuration.get("rootSeed").getAsLong() + 1L);
        Files.write(manifest, doc.toString().getBytes(StandardCharsets.UTF_8));
    }

    private static void copyDirectory(Path source, Path target) throws Exception {
        Files.walk(source).forEach(path -> {
            try {
                Path destination = target.resolve(source.relativize(path).toString());
                if (Files.isDirectory(path)) {
                    Files.createDirectories(destination);
                } else {
                    Files.createDirectories(destination.getParent());
                    Files.copy(path, destination);
                }
            } catch (java.io.IOException exception) {
                throw new IllegalStateException(exception);
            }
        });
    }

    private static String pointers(RerunReport report) {
        StringBuilder builder = new StringBuilder();
        for (RerunReport.DivergenceEntry entry : report.getCoreDivergences()) {
            builder.append(entry.getPointer()).append(' ');
        }
        return builder.toString();
    }
}
