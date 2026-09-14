package org.workflowsim.experiment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.cloudbus.cloudsim.Log;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.workflowsim.failure.FailureModelConfig;
import org.workflowsim.failure.FailureParameters;
import org.workflowsim.platform.PlatformProfile;
import org.workflowsim.platform.PlatformProfiles;
import org.workflowsim.utils.DistributionGenerator;
import org.workflowsim.utils.DistributionSpec;
import org.workflowsim.utils.Parameters;
import org.workflowsim.utils.SimulationConfig;

/**
 * R1 验收：真实算法对在多 seed 故障模型下的配对显著性检验端到端可用。
 *
 * <p>FCFS（基线）对 READY_BATCH_MCT（候选）在同一共享根种子计划下各跑 10 次。
 * 场景刻意选择两算法必然分歧的组合：</p>
 * <ul>
 * <li><b>异构平台</b>（16 VM，MIPS 800–2300）：同构平台上所有在线调度器的完成
 * 时间全等会退化为同一调度，配对差值恒为零，显著性检验不可用；</li>
 * <li><b>千任务规模</b>（Montage_1000）：小规模 fixture 上多数在线调度器同样收敛；</li>
 * <li><b>故障模型</b>（Weibull 到达、重试预算 64）：提供跨 seed 的随机性方差。</li>
 * </ul>
 *
 * <p>campaign 汇总的 {@code ComparisonSummary.getPairedSignificance()} 必须给出
 * 可用状态、有限 p 值与一致的显著性判定。本测试证明"带 p 值的算法对比报告"
 * 能力真实落地。</p>
 */
class MultiSeedSignificanceIntegrationTest {

    private static final int REPLICATIONS = 10;

    @AfterEach
    void restoreLogging() {
        Log.enable();
    }

    @Test
    void failureDrivenCampaignProducesPairedWilcoxonSignificance() throws Exception {
        String workflow = montage1000Path();
        List<Double> vmMips = new ArrayList<Double>();
        for (int vm = 0; vm < 16; vm++) {
            vmMips.add(Double.valueOf(800.0 + vm * 100.0));
        }
        PlatformProfile platform = PlatformProfiles.heterogeneousLocal("significance-platform", vmMips);
        FailureModelConfig failureModel = FailureModelConfig.builder()
                .clusteringAlgorithm(FailureParameters.FTCluteringAlgorithm.FTCLUSTERING_NOOP)
                .monitorMode(FailureParameters.FTCMonitor.MONITOR_NONE)
                .generatorMode(FailureParameters.FTCFailure.FAILURE_ALL)
                .generatorSpecs(new DistributionSpec[][]{{DistributionSpec.of(
                        DistributionGenerator.DistributionFamily.WEIBULL, 100.0, 1.0)}})
                .maxTotalRetryJobs(64)
                .build();
        SeedPlan seeds = SeedPlan.derived(RandomizationDesign.COMMON_ROOT_SEEDS_NOT_EVENT_KEYED_CRN,
                20260911L, REPLICATIONS);
        ExperimentPlan plan = ExperimentPlan.builder("multi-seed-significance")
                .addCell(cell("fcfs", "fcfs", true, workflow, platform, seeds,
                        failureModel, Parameters.SchedulingAlgorithm.FCFS))
                .addCell(cell("mct", "ready-batch-mct", false, workflow, platform, seeds,
                        failureModel, Parameters.SchedulingAlgorithm.READY_BATCH_MCT))
                .build();

        Log.disable();
        ExperimentCampaignResult result = new ExperimentCampaignExecutor().execute(plan);

        assertEquals(2 * REPLICATIONS, result.getRuns().size());
        assertEquals(1, result.getSummary().getComparisons().size());
        ExperimentCampaignSummary.ComparisonSummary comparison =
                result.getSummary().getComparisons().get(0);
        assertEquals(REPLICATIONS, comparison.getMatchedRootSeedCount());
        assertEquals("DESCRIPTIVE_ONLY_COMMON_ROOT_SEEDS_ARE_NOT_EVENT_KEYED_CRN",
                comparison.getInferenceStatus());

        PairedWilcoxonSignificance significance = comparison.getPairedSignificance();
        assertNotNull(significance);
        assertEquals(REPLICATIONS, significance.getPairedSampleCount());
        // 异构平台 + 千任务规模下两算法调度必然分歧，差值不应全为零。
        assertTrue(significance.getStatus().startsWith("AVAILABLE_"),
                "验收场景必须产生可用的配对检验，实际状态：" + significance.getStatus());
        assertNotNull(significance.getPValue());
        double p = significance.getPValue().doubleValue();
        assertTrue(p >= 0.0 && p <= 1.0, "p 值必须在 [0,1] 区间，实际：" + p);
        assertEquals(p < PairedWilcoxonSignificance.DEFAULT_ALPHA,
                significance.isSignificantAtDefaultAlpha());
        assertTrue(significance.getEffectiveSampleCount() >= 2);
        System.out.println("[R1] status=" + significance.getStatus()
                + " p=" + significance.getPValue()
                + " effectiveN=" + significance.getEffectiveSampleCount()
                + " medianDelta=" + significance.getMedianDifference());
    }

    private static ExperimentPlan.Cell cell(String id, String candidate, boolean baseline,
            String workflow, PlatformProfile platform, SeedPlan seeds,
            FailureModelConfig failureModel, Parameters.SchedulingAlgorithm algorithm) {
        SimulationConfig config = SimulationConfig.builder(workflow, 16)
                .schedulingAlgorithm(algorithm)
                .failureModel(failureModel)
                .build();
        return new ExperimentPlan.Cell(id, "shared-workflow", candidate, baseline, config,
                platform, seeds, Collections.singletonMap("purpose", "r1-acceptance"));
    }

    private static String montage1000Path() throws Exception {
        Path root = Paths.get(System.getProperty("user.dir")).toAbsolutePath().normalize();
        while (root != null && !Files.exists(root.resolve("datasets/dax"))) {
            root = root.getParent();
        }
        if (root == null) {
            throw new IllegalStateException("Cannot locate repository root containing datasets/dax");
        }
        Path dax = root.resolve("datasets/dax/montage/n1000/Montage_1000.dax");
        if (!Files.isRegularFile(dax)) {
            throw new IllegalStateException("Missing required input: " + dax);
        }
        return dax.toString();
    }
}
