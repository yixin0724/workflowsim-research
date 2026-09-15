package org.workflowsim.experiment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Paths;
import org.cloudbus.cloudsim.Log;
import org.junit.jupiter.api.Test;
import org.workflowsim.data.DataMovementModel;
import org.workflowsim.exception.SimulationConfigurationException;
import org.workflowsim.network.NetworkTopologySpec;
import org.workflowsim.platform.PlatformProfile;
import org.workflowsim.utils.ClusteringParameters;
import org.workflowsim.utils.Parameters;
import org.workflowsim.utils.ReplicaCatalog;
import org.workflowsim.utils.SimulationConfig;
import org.workflowsim.utils.TaskCostMatrix;

/**
 * Fat-tree 拓扑感知链路争用模型的端到端语义验收（HEFT 论文例 fixture）。
 *
 * <p>与 R2 端点争用模型（{@link LinkContentionSemanticsTest}，黄金值 284.1）同一
 * fixture、同一平台，差异只在数据移动模型与平台拓扑声明：k=4 Fat-tree、链路
 * 带宽 1 MB/s（与 VM 端点 1 MB/s 同量级，使链路争用成为可见瓶颈）。默认轮转
 * 放置把 3 台主机分到 (pod0,edge0)、(pod0,edge1)、(pod1,edge0)——VM 间传输
 * 沿确定性路由占用共享链路，同 Pod 跨 edge 与跨 Pod 路径都会出现。</p>
 *
 * <p>断言结构：</p>
 * <ul>
 *   <li>速率约束是 R2 端点约束的超集（端点 + 路径链路取最小），因此 Fat-tree
 *       makespan ≥ R2 黄金值 284.1，且严格大于无争用基线 190.1；</li>
 *   <li>黄金值锁定 + 确定性复跑逐位一致；</li>
 *   <li>证据链：DATA_STAGE_IN_MODELED 携带新模型 kind 与 fatTreePathLinkCount；</li>
 *   <li>健康不变量：全部逻辑任务成功完成、Job 数守恒；</li>
 *   <li>配置契约：SHARED fs / INVALID 规划 / 非 NONE 聚类拒绝；LOCAL_HEFT 允许；
 *       运行器双向契约——模型无拓扑声明拒绝、拓扑声明无模型拒绝。</li>
 * </ul>
 */
class FatTreeContentionIntegrationTest {

    /** 无争用基线 makespan（论文 80 + 引导偏移 110.1）。 */
    private static final double NO_CONTENTION_BASELINE = 190.1;
    /** R2 端点争用黄金值（LinkContentionSemanticsTest）：Fat-tree 的下界。 */
    private static final double R2_ENDPOINT_GOLDEN = 284.1;
    /**
     * Fat-tree 争用黄金值：由本 fixture 实测锁定（2026-09-15），复跑逐位稳定。
     * 链路带宽 1 MB/s 与端点同量级：与 R2（284.1）相比，凡两条并发流共享任一
     * 链路（接入、edge↔aggregate、aggregate↔core）都会进一步减速——18 MB 的
     * t2 输入沿跨 Pod 路径（6 条链路）与并发流公平共享，后续传输链依次推迟，
     * 总 makespan 从 R2 的 284.1 增至 1032.1。
     */
    private static final double FAT_TREE_GOLDEN_MAKESPAN = 1032.1;

    /** 论文成本矩阵（与 LocalHeftPaperReproductionTest 相同）。 */
    private static final double[][] PAPER_COST_SECONDS = {
            {14, 16, 9}, {13, 19, 18}, {11, 13, 19}, {13, 8, 17}, {12, 13, 10},
            {13, 16, 9}, {7, 15, 11}, {5, 11, 14}, {18, 12, 20}, {21, 7, 16}};

    @Test
    void fatTreeMakespanExceedsNoContentionBaselineAndR2EndpointGolden() throws Exception {
        SimulationReport report = runFatTree();
        assertTrue(report.getMakespan() > NO_CONTENTION_BASELINE,
                "Fat-tree makespan " + report.getMakespan() + " 应严格大于无争用基线 "
                        + NO_CONTENTION_BASELINE);
        assertTrue(report.getMakespan() >= R2_ENDPOINT_GOLDEN,
                "Fat-tree makespan " + report.getMakespan() + " 应不小于 R2 端点争用黄金值 "
                        + R2_ENDPOINT_GOLDEN + "（约束超集：端点 + 路径链路取最小）");
    }

    @Test
    void fatTreeGoldenMakespanIsLocked() throws Exception {
        SimulationReport report = runFatTree();
        assertEquals(FAT_TREE_GOLDEN_MAKESPAN, report.getMakespan(), 0.0,
                "Fat-tree 黄金值漂移：fixture、拓扑或争用语义发生变化，须显式审查");
    }

    @Test
    void fatTreeScheduleIsDeterministic() throws Exception {
        SimulationReport first = runFatTree();
        SimulationReport second = runFatTree();
        assertEquals(first.getMakespan(), second.getMakespan(), 0.0);
        for (int taskId = 1; taskId <= 10; taskId++) {
            assertEquals(computeFinish(first, taskId), computeFinish(second, taskId), 0.0,
                    "task " + taskId + " 的完成时刻在复跑间必须逐位一致");
        }
    }

    @Test
    void fatTreeEvidenceIsRecordedOnStageInEvents() throws Exception {
        SimulationReport report = runFatTree();
        int observed = 0;
        int totalPathLinks = 0;
        for (SimulationEvent event : report.getEvents()) {
            if (event.getType() != SimulationEventType.DATA_STAGE_IN_MODELED) {
                continue;
            }
            observed++;
            assertEquals("PRE_EXECUTION_TRANSFER_DELAY_WITH_FAT_TREE_CONTENTION_V1",
                    event.getAttributes().get("dataMovementModel"));
            Object linkCount = event.getAttributes().get("fatTreePathLinkCount");
            assertNotNull(linkCount, "Fat-tree 证据必须携带路径链路计数");
            totalPathLinks += ((Number) linkCount).intValue();
        }
        assertEquals(10, observed);
        assertTrue(totalPathLinks > 0,
                "VM→VM 传输组必须路由经过拓扑链路，总链路计数应大于 0");
    }

    @Test
    void fatTreeRunIsHealthy() throws Exception {
        SimulationReport report = runFatTree();
        assertEquals("COMPLETED_SUCCESSFULLY",
                report.getMetrics().getLogicalTaskCompletionStatus());
        assertTrue(report.getMakespan() > 0.0 && Double.isFinite(report.getMakespan()));
        assertEquals(report.getTotalJobs(), report.getSuccessfulJobs());
        assertTrue(report.getMetrics().isAllLogicalTasksCompletedSuccessfully());
    }

    @Test
    void configurationContractRejectsInvalidCombinations() throws Exception {
        String workflow = resourcePath("/dax/heft-paper-example.dax");
        // SHARED 文件系统：VM→VM 通信仅在 LOCAL 下建模。
        IllegalArgumentException shared = assertThrows(IllegalArgumentException.class,
                () -> SimulationConfig.builder(workflow, 3)
                        .planningAlgorithm(Parameters.PlanningAlgorithm.LOCAL_HEFT)
                        .schedulingAlgorithm(Parameters.SchedulingAlgorithm.STATIC)
                        .taskCostMatrix(paperCostMatrix())
                        .fileSystem(ReplicaCatalog.FileSystem.SHARED)
                        .dataMovementModel(DataMovementModel.fatTreeContentionV1())
                        .build());
        assertTrue(shared.getMessage().contains("LOCAL"), shared.getMessage());
        // INVALID 规划层：就绪时刻没有目标 VM，路径不可知。
        IllegalArgumentException unmapped = assertThrows(IllegalArgumentException.class,
                () -> SimulationConfig.builder(workflow, 3)
                        .schedulingAlgorithm(Parameters.SchedulingAlgorithm.MINMIN)
                        .fileSystem(ReplicaCatalog.FileSystem.LOCAL)
                        .dataMovementModel(DataMovementModel.fatTreeContentionV1())
                        .build());
        assertTrue(unmapped.getMessage().contains("planning algorithm"), unmapped.getMessage());
        // 非 NONE 聚类：Job 与逻辑任务不再一一对应。
        IllegalArgumentException clustered = assertThrows(IllegalArgumentException.class,
                () -> SimulationConfig.builder(workflow, 3)
                        .planningAlgorithm(Parameters.PlanningAlgorithm.LOCAL_HEFT)
                        .schedulingAlgorithm(Parameters.SchedulingAlgorithm.STATIC)
                        .taskCostMatrix(paperCostMatrix())
                        .clusteringParameters(new ClusteringParameters(2, 0,
                                ClusteringParameters.ClusteringMethod.HORIZONTAL, null))
                        .fileSystem(ReplicaCatalog.FileSystem.LOCAL)
                        .dataMovementModel(DataMovementModel.fatTreeContentionV1())
                        .build());
        assertTrue(clustered.getMessage().contains("clustering method NONE"),
                clustered.getMessage());
        // LOCAL_HEFT + Fat-tree 争用：允许（规划侧无争用 AST、运行期链路争用）。
        assertNotNull(SimulationConfig.builder(workflow, 3)
                .planningAlgorithm(Parameters.PlanningAlgorithm.LOCAL_HEFT)
                .schedulingAlgorithm(Parameters.SchedulingAlgorithm.STATIC)
                .taskCostMatrix(paperCostMatrix())
                .fileSystem(ReplicaCatalog.FileSystem.LOCAL)
                .dataMovementModel(DataMovementModel.fatTreeContentionV1())
                .build());
    }

    @Test
    void runnerEnforcesBidirectionalTopologyContract() throws Exception {
        String workflow = resourcePath("/dax/heft-paper-example.dax");
        SimulationConfig fatTreeConfig = SimulationConfig.builder(workflow, 3)
                .planningAlgorithm(Parameters.PlanningAlgorithm.LOCAL_HEFT)
                .schedulingAlgorithm(Parameters.SchedulingAlgorithm.STATIC)
                .taskCostMatrix(paperCostMatrix())
                .fileSystem(ReplicaCatalog.FileSystem.LOCAL)
                .dataMovementModel(DataMovementModel.fatTreeContentionV1())
                .build();
        // 模型声明但平台无拓扑 → 拒绝。
        SimulationConfigurationException missing = assertThrows(
                SimulationConfigurationException.class,
                () -> new SimulationRunner().run(fatTreeConfig, paperPlatform(null)));
        assertTrue(missing.getMessage().contains("networkTopology"), missing.getMessage());
        // 平台声明拓扑但模型不使用 → 拒绝（避免声明被静默忽略）。
        SimulationConfig legacyConfig = SimulationConfig.builder(workflow, 3)
                .planningAlgorithm(Parameters.PlanningAlgorithm.LOCAL_HEFT)
                .schedulingAlgorithm(Parameters.SchedulingAlgorithm.STATIC)
                .taskCostMatrix(paperCostMatrix())
                .fileSystem(ReplicaCatalog.FileSystem.LOCAL)
                .dataMovementModel(DataMovementModel.preExecutionTransferDelayV1())
                .build();
        SimulationConfigurationException unused = assertThrows(
                SimulationConfigurationException.class,
                () -> new SimulationRunner().run(legacyConfig,
                        paperPlatform(NetworkTopologySpec.fatTree(4, 1.0))));
        assertTrue(unused.getMessage().contains("does not use it"), unused.getMessage());
    }

    private static SimulationReport runFatTree() throws Exception {
        Log.disable();
        SimulationConfig config = SimulationConfig.builder(
                        resourcePath("/dax/heft-paper-example.dax"), 3)
                .planningAlgorithm(Parameters.PlanningAlgorithm.LOCAL_HEFT)
                .schedulingAlgorithm(Parameters.SchedulingAlgorithm.STATIC)
                .taskCostMatrix(paperCostMatrix())
                .fileSystem(ReplicaCatalog.FileSystem.LOCAL)
                .dataMovementModel(DataMovementModel.fatTreeContentionV1())
                .build();
        return new SimulationRunner().run(config, paperPlatform(
                NetworkTopologySpec.fatTree(4, 1.0)));
    }

    /** 与论文例相同的平台：3 主机 3 VM（mips=1.0、带宽 1 MB/s），可选拓扑声明。 */
    private static PlatformProfile paperPlatform(NetworkTopologySpec topology) {
        PlatformProfile.Builder builder = PlatformProfile.builder("fat-tree-paper-platform");
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

    private static double computeFinish(SimulationReport report, int taskId) {
        for (SimulationReport.JobOutcome job : report.getJobs()) {
            if (job.getClassType() == Parameters.ClassType.COMPUTE.value
                    && job.getTaskIds().contains(Integer.valueOf(taskId))) {
                return job.getFinishTime();
            }
        }
        throw new AssertionError("No compute job for task " + taskId);
    }

    private static String resourcePath(String resource) throws Exception {
        java.net.URL url = FatTreeContentionIntegrationTest.class.getResource(resource);
        if (url == null) {
            throw new IllegalStateException("Missing test resource " + resource);
        }
        return Paths.get(url.toURI()).toString();
    }
}
