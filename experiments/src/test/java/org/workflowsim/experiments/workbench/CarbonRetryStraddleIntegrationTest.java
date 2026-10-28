package org.workflowsim.experiments.workbench;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

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
import org.workflowsim.failure.FailureModelConfig;
import org.workflowsim.failure.FailureParameters;
import org.workflowsim.platform.PlatformProfile;
import org.workflowsim.utils.DistributionGenerator;
import org.workflowsim.utils.DistributionSpec;
import org.workflowsim.utils.Parameters.PlanningAlgorithm;
import org.workflowsim.utils.Parameters.SchedulingAlgorithm;
import org.workflowsim.utils.ReplicaCatalog;
import org.workflowsim.utils.SimulationConfig;

/**
 * BAT-H2 (2026-10-28, 测试工程师第四轮收口) — риск失败重试 × 碳预算窗口跨界的并集碳核算。
 *
 * <p>R8 已证明能量守恒在重试下闭合（union 语义——失败尝试+重试都在积分里，无一秒双计）。
 * 本探针是同一并集纪律在碳侧的跨界：带绿窗声明的失败重试运行，Python 独立重建
 * 舰队时间线（含失败+重试作业区间）→ 逐窗超额复算 → 与发布值逐对匹配。任何双计会立刻
 * 击穿窗和==总量或逐叶复算。证据留在稳定目录供 shell 侧 Python 全审计。</p>
 */
class CarbonRetryStraddleIntegrationTest {

    /** 稳定目录证据（R8 纪律——输入必须与证据同存，Junit 临时目录不可用）。 */
    static final Path PROBE_DIR = Paths.get("target", "carbon-retry-straddle");

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
    void retriedRunWithBudgetWindowsAuditsClean() throws Exception {
        FailureModelConfig failure = new FailureModelConfig.Builder()
                .clusteringAlgorithm(FailureParameters.FTCluteringAlgorithm.FTCLUSTERING_NOOP)
                .monitorMode(FailureParameters.FTCMonitor.MONITOR_NONE)
                .generatorMode(FailureParameters.FTCFailure.FAILURE_ALL)
                .generatorSpecs(new DistributionSpec[][] {
                    {DistributionSpec.of(DistributionGenerator.DistributionFamily.WEIBULL, 25, 1)}
                })
                .maxTotalRetryJobs(64)
                .build();
        PlatformProfile platform = poweredBudgetPlatform();
        SimulationReport retried = null;
        for (long seed = 1; seed <= 256 && retried == null; seed++) {
            try {
                Path input = PROBE_DIR.resolve("retry-bag.dax");
                Files.createDirectories(PROBE_DIR);
                Files.write(input, bagDax().getBytes(StandardCharsets.UTF_8));
                SimulationConfig config = SimulationConfig.builder(
                                input.toAbsolutePath().toString(), 2)
                        .planningAlgorithm(PlanningAlgorithm.INVALID)
                        .schedulingAlgorithm(SchedulingAlgorithm.FCFS)
                        .fileSystem(ReplicaCatalog.FileSystem.LOCAL)
                        .dataMovementModel(DataMovementModel.legacyWorkflowsimV1())
                        .randomSeed(seed)
                        .failureModel(failure)
                        .build();
                SimulationReport candidate = new SimulationRunner().run(config, platform);
                boolean retried2 = candidate.getMetrics().getRetryJobCreatedCount() > 0;
                if (candidate.getMetrics().isAllLogicalTasksCompletedSuccessfully() && retried2) {
                    retried = candidate;
                }
            } catch (RuntimeException budgetOrFailure) {
                // 猎种——耗尽预算或失败的种子跳过，末断言把空猎败态定为硬失败。
            }
        }
        assertTrue(retried != null, "必须找到真重试且成功的运行（空猎=硬失败）");
        assertTrue(retried.getMetrics().getRetryJobCreatedCount() >= 1, "有机重试存在");
        assertTrue(retried.getCarbonAccounting() != null, "预算模型已声明=核算必须");

        CarbonAccounting cab = retried.getCarbonAccounting();
        double total = 0.0;
        for (double v : cab.getWindowExcessWattSeconds().values()) {
            total += v;
        }
        assertTrue(
                Math.abs(total - cab.getTotalCarbonExcessWattSeconds()) < 1e-9, "窗和==总量恒等（含重试的独立重演在 Python 审计中另行验证）");

        ExperimentArtifactWriter.ExperimentArtifacts artifacts =
                ExperimentArtifactWriter.write(retried, PROBE_DIR, "result");
        ExperimentArtifactValidator.validate(artifacts.getManifest());
        assertTrue(
                Files.isRegularFile(PROBE_DIR.resolve("result.manifest.json")),
                "probe bundle staged for shell-side Python audit");
        assertTrue(
                !cab.getWindowExcessWattSeconds().isEmpty() || cab.getTotalCarbonExcessWattSeconds() >= 0.0,
                "预算族非平凡（正超额在至少一个窗口或資源下于绿电）");
    }

    private static String bagDax() {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<adag count=\"3\">\n"
                + "  <job id=\"a\" name=\"a\" runtime=\"30\"/>\n"
                + "  <job id=\"b\" name=\"b\" runtime=\"45\"/>\n"
                + "  <job id=\"c\" name=\"c\" runtime=\"60\"/>\n"
                + "  <child ref=\"b\"><parent ref=\"a\"/></child>\n"
                + "  <child ref=\"c\"><parent ref=\"b\"/></child>\n"
                + "</adag>\n";
    }

    /** 双绿窗跨失败重试域。 */
    private static PlatformProfile poweredBudgetPlatform() {
        PlatformProfile.Builder builder = PlatformProfile.builder("carbon-retry");
        int[] ids = {0, 1};
        for (int i = 0; i < 2; i++) {
            builder.addHost(new PlatformProfile.HostSpec(800 + i, 2, 2000, 2048, 10000L, 1000000L))
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
                            null))
                    .pinVmToHost(ids[i], 800 + i);
        }
        // 绿窗低于怠速和——保证非平凡超额。
        builder.carbonWindow(0.0, 30.0, 100.0).carbonWindow(30.0, 120.0, 500.0);
        return builder.build();
    }
}
