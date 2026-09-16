package org.workflowsim.experiment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Paths;
import java.util.Arrays;
import java.util.List;
import org.cloudbus.cloudsim.Log;
import org.junit.jupiter.api.Test;
import org.workflowsim.data.DataMovementModel;
import org.workflowsim.network.NetworkTopologySpec;
import org.workflowsim.platform.PlatformProfile;
import org.workflowsim.utils.Parameters;
import org.workflowsim.utils.ReplicaCatalog;
import org.workflowsim.utils.SimulationConfig;
import org.workflowsim.utils.TaskCostMatrix;

/**
 * Fat-tree × 调度联合实验的规划器兼容性契约探测（campaign Phase A 产物）。
 *
 * <p>研究问题"网络争用如何改变调度算法相对优劣"要求同一 DAG 在多个规划器 ×
 * 多个数据移动模型（无争用 V1 / R2 端点争用 / R6 Fat-tree 链路争用）下可比。
 * 本测试实证哪些组合合法且端到端健康，哪些被配置契约拒绝——探测结果直接
 * 界定 campaign 对照矩阵（见 docs/experiments/FATTREE_SCHEDULING_CAMPAIGN.md）。</p>
 *
 * <p>fixture 与 {@link FatTreeContentionIntegrationTest} 相同：HEFT 论文例 ×
 * 3 主机（默认轮转放置）× k=4 满配 Fat-tree、链路 1 MB/s。RANDOM/PSO 是通信
 * 无感知规划器，作为争用不敏感基线参与对照；LOCAL_HEFT/LOCAL_CPOP 是通信
 * 感知规划器。在线调度器（FCFS/DATA）要求 INVALID 规划层，与全部
 * preExecution 家族模型互斥，属于已知平台边界。</p>
 */
class FatTreePlannerCompatibilityIntegrationTest {

    /** 论文成本矩阵（与 FatTreeContentionIntegrationTest / LocalHeftPaperReproductionTest 相同）。 */
    private static final double[][] PAPER_COST_SECONDS = {
            {14, 16, 9}, {13, 19, 18}, {11, 13, 19}, {13, 8, 17}, {12, 13, 10},
            {13, 16, 9}, {7, 15, 11}, {5, 11, 14}, {18, 12, 20}, {21, 7, 16}};

    /** campaign 对照矩阵的规划器集合：两个通信感知 + 两个通信无感知基线。 */
    private static final List<Parameters.PlanningAlgorithm> CAMPAIGN_PLANNERS = Arrays.asList(
            Parameters.PlanningAlgorithm.LOCAL_HEFT,
            Parameters.PlanningAlgorithm.LOCAL_CPOP,
            Parameters.PlanningAlgorithm.RANDOM,
            Parameters.PlanningAlgorithm.PSO);

    /** 对照矩阵的数据移动模型：无争用基线、R2 端点争用、R6 Fat-tree 链路争用。 */
    private static final List<DataMovementModel> CAMPAIGN_MODELS = Arrays.asList(
            DataMovementModel.preExecutionTransferDelayV1(),
            DataMovementModel.preExecutionTransferDelayWithContentionV1(),
            DataMovementModel.fatTreeContentionV1());

    /** 全部 campaign 规划器 × 全部模型组合都必须端到端健康——对照矩阵的合法性。 */
    @Test
    void everyCampaignPlannerRunsHealthyUnderEveryMovementModel() throws Exception {
        Log.disable();
        StringBuilder table = new StringBuilder();
        for (Parameters.PlanningAlgorithm planner : CAMPAIGN_PLANNERS) {
            for (DataMovementModel movement : CAMPAIGN_MODELS) {
                boolean fatTree = movement.isFatTreeContentionV1();
                String label = planner + " × " + movement.getKind();
                SimulationConfig config = SimulationConfig
                        .builder(resourcePath("/dax/heft-paper-example.dax"), 3)
                        .planningAlgorithm(planner)
                        .schedulingAlgorithm(Parameters.SchedulingAlgorithm.STATIC)
                        .taskCostMatrix(paperCostMatrix())
                        .fileSystem(ReplicaCatalog.FileSystem.LOCAL)
                        .dataMovementModel(movement)
                        .randomSeed(91L)
                        .build();
                double makespan;
                try {
                    SimulationReport report = new SimulationRunner().run(config,
                            paperPlatform(fatTree ? NetworkTopologySpec.fatTree(4, 1.0) : null));
                    assertEquals("COMPLETED_SUCCESSFULLY",
                            report.getMetrics().getLogicalTaskCompletionStatus(), label);
                    assertEquals(report.getTotalJobs(), report.getSuccessfulJobs(), label);
                    assertTrue(report.getMakespan() > 0.0 && Double.isFinite(report.getMakespan()),
                            label);
                    makespan = report.getMakespan();
                } catch (Exception exception) {
                    table.append(label).append(" -> FAILED: ")
                            .append(exception.getMessage()).append('\n');
                    System.out.println("[compatibility-probe]\n" + table);
                    throw new AssertionError(label + " 运行失败: " + exception, exception);
                }
                table.append(label).append(" -> makespan ").append(makespan).append('\n');
            }
        }
        System.out.println("[compatibility-probe]\n" + table);
    }

    /**
     * 争用语义的弱单调性不变量：约束域逐级扩大（端点 → 端点+路径链路），
     * 同一规划器同一映射的 makespan 不得下降。
     */
    @Test
    void contentionModelsNeverReduceMakespanForAnyCampaignPlanner() throws Exception {
        Log.disable();
        for (Parameters.PlanningAlgorithm planner : CAMPAIGN_PLANNERS) {
            double[] makespans = new double[CAMPAIGN_MODELS.size()];
            for (int i = 0; i < CAMPAIGN_MODELS.size(); i++) {
                DataMovementModel movement = CAMPAIGN_MODELS.get(i);
                SimulationConfig config = SimulationConfig
                        .builder(resourcePath("/dax/heft-paper-example.dax"), 3)
                        .planningAlgorithm(planner)
                        .schedulingAlgorithm(Parameters.SchedulingAlgorithm.STATIC)
                        .taskCostMatrix(paperCostMatrix())
                        .fileSystem(ReplicaCatalog.FileSystem.LOCAL)
                        .dataMovementModel(movement)
                        .randomSeed(91L)
                        .build();
                SimulationReport report = new SimulationRunner().run(config,
                        paperPlatform(movement.isFatTreeContentionV1()
                                ? NetworkTopologySpec.fatTree(4, 1.0) : null));
                makespans[i] = report.getMakespan();
            }
            assertTrue(makespans[1] >= makespans[0] - 1.0e-9,
                    planner + ": R2 端点争用 makespan " + makespans[1]
                            + " 不得低于无争用 " + makespans[0]);
            assertTrue(makespans[2] >= makespans[1] - 1.0e-9,
                    planner + ": Fat-tree 链路争用 makespan " + makespans[2]
                            + " 不得低于 R2 端点争用 " + makespans[1]);
        }
    }

    /** 平台边界：共享存储家族要求遗留模型；在线调度器要求 INVALID 规划层。 */
    @Test
    void knownIncompatibleCombinationsAreRejectedUpFront() throws Exception {
        String workflow = resourcePath("/dax/heft-paper-example.dax");
        // SHARED_STORAGE 家族 + Fat-tree 模型：规划侧传输估计只对齐遗留模型。
        IllegalArgumentException shared = assertThrows(IllegalArgumentException.class,
                () -> SimulationConfig.builder(workflow, 3)
                        .planningAlgorithm(Parameters.PlanningAlgorithm.SHARED_STORAGE_HEFT)
                        .schedulingAlgorithm(Parameters.SchedulingAlgorithm.STATIC)
                        .fileSystem(ReplicaCatalog.FileSystem.LOCAL)
                        .dataMovementModel(DataMovementModel.fatTreeContentionV1())
                        .build());
        assertNotNull(shared.getMessage());
        // 在线调度器（FCFS/DATA）要求 INVALID 规划层，与 preExecution 家族互斥。
        for (Parameters.SchedulingAlgorithm online : Arrays.asList(
                Parameters.SchedulingAlgorithm.FCFS,
                Parameters.SchedulingAlgorithm.DATA)) {
            assertThrows(IllegalArgumentException.class,
                    () -> SimulationConfig.builder(workflow, 3)
                            .schedulingAlgorithm(online)
                            .fileSystem(ReplicaCatalog.FileSystem.LOCAL)
                            .dataMovementModel(DataMovementModel.fatTreeContentionV1())
                            .build(),
                    "在线调度器 " + online + " 应因 INVALID 规划层被 Fat-tree 模型拒绝");
        }
    }

    private static String resourcePath(String name) throws Exception {
        java.net.URL url = FatTreePlannerCompatibilityIntegrationTest.class.getResource(name);
        if (url == null) {
            throw new IllegalStateException("Missing test resource " + name);
        }
        return Paths.get(url.toURI()).toString();
    }

    /** 与论文例相同的平台：3 主机 3 VM（mips=1.0、带宽 1 MB/s），可选拓扑声明。 */
    private static PlatformProfile paperPlatform(NetworkTopologySpec topology) {
        PlatformProfile.Builder builder = PlatformProfile.builder("compat-paper-platform");
        for (int id = 0; id < 3; id++) {
            builder.addHost(new PlatformProfile.HostSpec(id, 2, 2.0, 2048, 10_000L, 1_000_000L));
            builder.addVm(new PlatformProfile.VmSpec(id, 1.0, 1, 512, 1L, 10_000L, "Xen",
                    PlatformProfile.CloudletSchedulerMode.SPACE_SHARED));
        }
        if (topology != null) {
            builder.networkTopology(topology);
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
}
