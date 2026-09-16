package org.workflowsim.experiment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Paths;
import org.cloudbus.cloudsim.Log;
import org.junit.jupiter.api.Test;
import org.workflowsim.data.DataMovementModel;
import org.workflowsim.platform.PlatformProfile;
import org.workflowsim.utils.Parameters;
import org.workflowsim.utils.ReplicaCatalog;
import org.workflowsim.utils.SimulationConfig;
import org.workflowsim.utils.SimulationRandom;
import org.workflowsim.utils.TaskCostMatrix;

/**
 * 规划器方向性契约（R8 审计，算法通道 P2-1）。
 *
 * <p>此前所有规划器测试只锁黄金值/逐位确定性，没有任何测试锁定"复现算法
 * 相对无信息基线的方向"：若 HEFT/CPOP 实现被改坏到比 RANDOM 还差，黄金值
 * 测试会失败，但失败信息无法指向"算法失去启发式优势"这一根因。本测试在
 * HEFT 论文例 fixture（与 {@link LocalHeftPaperReproductionTest} 完全同装置）
 * 上直接锁定：</p>
 * <ul>
 *   <li>LOCAL_HEFT makespan ≤ RANDOM makespan（同种子）；</li>
 *   <li>LOCAL_CPOP makespan ≤ RANDOM makespan（同种子）；</li>
 *   <li>LOCAL_HEFT 黄金值 190.1（论文 80 + 引导偏移 110.1）保持逐位不变。</li>
 * </ul>
 *
 * <p>方向性断言是弱序（≤）而非严格（&lt;）：RANDOM 有小概率撞出与启发式
 * 相同的映射；同种子下该断言仍是确定性的实测事实（RANDOM 黄金值一并记录
 * 在失败消息中，便于漂移排查）。</p>
 */
class PlannerDirectionalityIntegrationTest {

    /** LOCAL_HEFT 论文例黄金 makespan（V1 模型，含引导偏移）。 */
    private static final double HEFT_GOLDEN_MAKESPAN = 190.1;

    /** 论文成本矩阵（与 LocalHeftPaperReproductionTest 相同）。 */
    private static final double[][] PAPER_COST_SECONDS = {
            {14, 16, 9}, {13, 19, 18}, {11, 13, 19}, {13, 8, 17}, {12, 13, 10},
            {13, 16, 9}, {7, 15, 11}, {5, 11, 14}, {18, 12, 20}, {21, 7, 16}};

    @Test
    void heftAndCpopAreNeverWorseThanSameSeedRandom() throws Exception {
        double randomMakespan = runWithPlanner(Parameters.PlanningAlgorithm.RANDOM);
        double heftMakespan = runWithPlanner(Parameters.PlanningAlgorithm.LOCAL_HEFT);
        double cpopMakespan = runWithPlanner(Parameters.PlanningAlgorithm.LOCAL_CPOP);

        assertEquals(HEFT_GOLDEN_MAKESPAN, heftMakespan, 0.0,
                "LOCAL_HEFT 黄金值漂移：方向性测试的前提装置已变化");
        assertTrue(heftMakespan <= randomMakespan,
                "LOCAL_HEFT makespan " + heftMakespan + " 不得劣于同种子 RANDOM "
                        + randomMakespan + "（启发式失去优势=方向性回归）");
        assertTrue(cpopMakespan <= randomMakespan,
                "LOCAL_CPOP makespan " + cpopMakespan + " 不得劣于同种子 RANDOM "
                        + randomMakespan + "（启发式失去优势=方向性回归）");
    }

    private static double runWithPlanner(Parameters.PlanningAlgorithm planner) throws Exception {
        Log.disable();
        SimulationRandom.reset(91L);
        SimulationConfig config = SimulationConfig.builder(
                        resourcePath("/dax/heft-paper-example.dax"), 3)
                .planningAlgorithm(planner)
                .schedulingAlgorithm(Parameters.SchedulingAlgorithm.STATIC)
                .taskCostMatrix(paperCostMatrix())
                .fileSystem(ReplicaCatalog.FileSystem.LOCAL)
                .dataMovementModel(DataMovementModel.preExecutionTransferDelayV1())
                .build();
        SimulationReport report = new SimulationRunner().run(config, paperPlatform());
        int computeJobs = 0;
        for (SimulationReport.JobOutcome job : report.getJobs()) {
            if (job.getClassType() == Parameters.ClassType.COMPUTE.value) {
                computeJobs++;
            }
        }
        assertEquals(10, computeJobs,
                planner + ": 论文例必须完整执行 10 个 COMPUTE Job");
        return report.getMakespan();
    }

    private static PlatformProfile paperPlatform() {
        PlatformProfile.Builder builder = PlatformProfile.builder("directionality-platform");
        for (int id = 0; id < 3; id++) {
            builder.addHost(new PlatformProfile.HostSpec(id, 2, 2.0, 2048, 10_000L, 1_000_000L));
            builder.addVm(new PlatformProfile.VmSpec(id, 1.0, 1, 512, 1L, 10_000L, "Xen",
                    PlatformProfile.CloudletSchedulerMode.SPACE_SHARED));
        }
        return builder.build();
    }

    private static TaskCostMatrix paperCostMatrix() {
        TaskCostMatrix.Builder builder = TaskCostMatrix.builder();
        for (int taskId = 1; taskId <= PAPER_COST_SECONDS.length; taskId++) {
            for (int vmId = 0; vmId < 3; vmId++) {
                builder.put(taskId, vmId, PAPER_COST_SECONDS[taskId - 1][vmId]);
            }
        }
        return builder.build();
    }

    private static String resourcePath(String resource) throws Exception {
        java.net.URL url = PlannerDirectionalityIntegrationTest.class.getResource(resource);
        if (url == null) {
            throw new IllegalStateException("Missing test resource " + resource);
        }
        return Paths.get(url.toURI()).toString();
    }
}
