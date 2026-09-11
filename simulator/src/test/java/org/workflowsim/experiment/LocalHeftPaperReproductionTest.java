package org.workflowsim.experiment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.Map;
import org.cloudbus.cloudsim.Log;
import org.junit.jupiter.api.Test;
import org.workflowsim.data.DataMovementModel;
import org.workflowsim.platform.PlatformProfile;
import org.workflowsim.utils.ClusteringParameters;
import org.workflowsim.utils.Parameters;
import org.workflowsim.utils.ReplicaCatalog;
import org.workflowsim.utils.SimulationConfig;
import org.workflowsim.utils.TaskCostMatrix;

/**
 * HEFT 论文例（Topcuoglu, Hariri &amp; Wu, IEEE TPDS 2002）在受控 LOCAL 执行模型
 * 上的复现回归。
 *
 * <p><b>数据来源核实（2026-06）</b>：社区转录存在两个互斥变体；本测试采用与论文
 * rank 表（rank_u: 108/77/80/80/69/63.33/42.67/35.67/44.33/14.67）及论文 HEFT
 * makespan=80 完全自洽的变体，与开源参考实现
 * <a href="https://github.com/mackncheesiest/heft">mackncheesiest/heft</a>
 * （heft.py 注释 "taken from Topcuoglu 2002 HEFT paper"，其测试断言调度
 * makespan 恰为 80.0）一致。编码：3 台 VM mips=1.0、带宽 1 MB/s；边权 = 中间
 * 文件字节数/1e6（传输秒数逐位等于论文边权）；计算成本 = 任务×VM 成本矩阵秒数
 * （MI 折算恒等）。</p>
 *
 * <p><b>复现结论（受控模型 vs 论文理想模型）</b></p>
 * <ul>
 *   <li>向上 rank 与论文逐一相同：均匀带宽下非对角 VM 对平均通信成本 c̄ 恰等于
 *       论文边权，rank_u 精确复现论文表值；</li>
 *   <li>VM 映射 10/10 与论文/参考实现一致（n1→vm2, n2→vm0, n3→vm2, n4→vm1,
 *       n5→vm2, n6→vm1, n7→vm2, n8→vm0, n9→vm1, n10→vm1），且每任务区间逐位
 *       等于论文区间 + 引导偏移 110.1（如 n10 [73-80] → [183.1-190.1]）；</li>
 *   <li>语义基础：preExecutionTransferDelayV1 数据移动模型把输入传输建模为执行前
 *       网络延迟（Topcuoglu AST = max{avail[pj], max_pred(AFT+c)}），传输与 VM
 *       忙碌期重叠、VM 只被计算占用；COMM-1 修复前的信封语义（传输折算 MI、VM
 *       空闲后才开始）会使 t6 映射翻转并使 makespan 膨胀到 129.1；</li>
 *   <li>规划与运行时逐位一致：计划开始/完成时刻 == 运行时观测（确定性复跑稳定）。</li>
 * </ul>
 */
class LocalHeftPaperReproductionTest {

    /** 论文任务 i（DAX 文档顺序 → 任务 ID i）在 VM j 上的计算秒数（论文成本矩阵）。 */
    private static final double[][] PAPER_COST_SECONDS = {
            {14, 16, 9},   // t1 = n1
            {13, 19, 18},  // t2 = n2
            {11, 13, 19},  // t3 = n3
            {13, 8, 17},   // t4 = n4
            {12, 13, 10},  // t5 = n5
            {13, 16, 9},   // t6 = n6
            {7, 15, 11},   // t7 = n7
            {5, 11, 14},   // t8 = n8
            {18, 12, 20},  // t9 = n9
            {21, 7, 16},   // t10 = n10
    };

    /** stage-in 引导偏移：stage-in Job（110 MI，mips=1.0）完成 110.0 + 内核间隔 0.1。 */
    private static final double BOOTSTRAP_OFFSET = 110.1;

    /** 受控模型的每任务 VM 映射（10/10 与论文/参考实现一致）。 */
    private static final int[] CONTROLLED_VM_BY_TASK =
            {-1, 2, 0, 2, 1, 2, 1, 2, 0, 1, 1};
    /** 受控模型的运行时开始时刻（绝对秒），下标 = 任务 ID；论文区间 + 引导偏移 110.1。 */
    private static final double[] CONTROLLED_STARTS = {
            0.0, BOOTSTRAP_OFFSET, 137.1, 119.1, 128.1, 138.1, 136.1, 148.1, 167.1, 166.1, 183.1};
    /** 受控模型的运行时完成时刻（绝对秒），下标 = 任务 ID；论文区间 + 引导偏移 110.1。 */
    private static final double[] CONTROLLED_FINISHES = {
            0.0, 119.1, 150.1, 138.1, 136.1, 148.1, 152.1, 159.1, 172.1, 178.1, 190.1};
    /** 每任务实际建模传输秒数（副本已在目标 VM 上为零；跨 VM = 论文边权）。 */
    private static final double[] CONTROLLED_TRANSFER_SECONDS = {
            0.0, 0.0, 18.0, 0.0, 9.0, 0.0, 14.0, 0.0, 42.0, 29.0, 28.0};

    @Test
    void heftPaperExampleReproducesControlledScheduleAndMapping() throws Exception {
        SimulationReport report = runPaperExample();
        // 受控 makespan = 论文 makespan 80 + 引导偏移 110.1（传输与 VM 忙碌期重叠，
        // 不再有信封串行化增量）。
        assertEquals(190.1, report.getMakespan(), 1.0e-9);
        int computeCount = 0;
        for (SimulationReport.JobOutcome job : report.getJobs()) {
            if (job.getClassType() != Parameters.ClassType.COMPUTE.value
                    || job.getTaskIds().size() != 1) {
                continue;
            }
            computeCount++;
            int taskId = job.getTaskIds().get(0).intValue();
            assertEquals(CONTROLLED_VM_BY_TASK[taskId], job.getVmId(),
                    "task " + taskId + " 的 VM 映射");
            assertEquals(CONTROLLED_STARTS[taskId], job.getStartTime(), 1.0e-9,
                    "task " + taskId + " 的开始时刻（规划 == 运行时）");
            assertEquals(CONTROLLED_FINISHES[taskId], job.getFinishTime(), 1.0e-9,
                    "task " + taskId + " 的完成时刻（规划 == 运行时）");
        }
        assertEquals(10, computeCount);
    }

    @Test
    void heftPaperScheduleIsCommunicationAware() throws Exception {
        SimulationReport report = runPaperExample();
        // t3/t5/t6/t7 与父任务同 VM：副本局部性使传输为零；跨 VM 任务的建模传输
        // 秒数逐位等于论文边权（字节数/1e6/1 MB/s）。
        for (int taskId = 1; taskId <= 10; taskId++) {
            SimulationEvent event = transferEventForTask(report, taskId);
            assertEquals(CONTROLLED_TRANSFER_SECONDS[taskId],
                    ((Number) event.getAttributes().get("modeledTransferSeconds")).doubleValue(),
                    1.0e-9, "task " + taskId + " 的建模传输秒数");
        }
        assertEquals(140.0, report.getMetrics().getTotalModeledDataTransferSeconds(), 1.0e-9);
        assertEquals(10, report.getMetrics().getDataStageInModelObservationCount());
    }

    @Test
    void heftPaperReproductionIsDeterministic() throws Exception {
        SimulationReport first = runPaperExample();
        SimulationReport second = runPaperExample();
        assertEquals(first.getMakespan(), second.getMakespan(), 0.0);
        for (int taskId = 1; taskId <= 10; taskId++) {
            assertEquals(computeJobForTask(first, taskId).getVmId(),
                    computeJobForTask(second, taskId).getVmId());
            assertEquals(computeJobForTask(first, taskId).getFinishTime(),
                    computeJobForTask(second, taskId).getFinishTime(), 0.0);
        }
    }

    @Test
    void localHeftRejectsIncompatibleExecutionModels() throws Exception {
        String workflow = resourcePath("/dax/heft-paper-example.dax");
        // SHARED 文件系统：LOCAL_HEFT 的通信建模只对 LOCAL 成立。
        IllegalArgumentException shared = assertThrows(IllegalArgumentException.class,
                () -> SimulationConfig.builder(workflow, 3)
                        .planningAlgorithm(Parameters.PlanningAlgorithm.LOCAL_HEFT)
                        .schedulingAlgorithm(Parameters.SchedulingAlgorithm.STATIC)
                        .taskCostMatrix(paperCostMatrix())
                        .fileSystem(ReplicaCatalog.FileSystem.SHARED)
                        .build());
        assertTrue(shared.getMessage().contains("LOCAL"), shared.getMessage());
        // 非 preExecutionTransferDelayV1 数据移动模型：规划 AST 与运行时传输延迟将失去对齐。
        IllegalArgumentException movement = assertThrows(IllegalArgumentException.class,
                () -> SimulationConfig.builder(workflow, 3)
                        .planningAlgorithm(Parameters.PlanningAlgorithm.LOCAL_HEFT)
                        .schedulingAlgorithm(Parameters.SchedulingAlgorithm.STATIC)
                        .fileSystem(ReplicaCatalog.FileSystem.LOCAL)
                        .dataMovementModel(DataMovementModel.fixedEndpointNoContention(50.0, 0.01, 25.0))
                        .build());
        assertTrue(movement.getMessage().contains("preExecutionTransferDelayV1"), movement.getMessage());
        // INVALID 规划层 + 新数据移动模型：就绪时刻没有目标 VM，无法估计传输
        // （STATIC 派发已被既有校验拦截，此处验证在线派发路径同样被拒绝）。
        IllegalArgumentException unmapped = assertThrows(IllegalArgumentException.class,
                () -> SimulationConfig.builder(workflow, 3)
                        .schedulingAlgorithm(Parameters.SchedulingAlgorithm.MINMIN)
                        .fileSystem(ReplicaCatalog.FileSystem.LOCAL)
                        .dataMovementModel(DataMovementModel.preExecutionTransferDelayV1())
                        .build());
        assertTrue(unmapped.getMessage().contains("INVALID"), unmapped.getMessage());
        // 聚类：LOCAL_HEFT 要求 NONE 聚类（每 Job 对应单 Task）。
        IllegalArgumentException clustering = assertThrows(IllegalArgumentException.class,
                () -> SimulationConfig.builder(workflow, 3)
                        .planningAlgorithm(Parameters.PlanningAlgorithm.LOCAL_HEFT)
                        .schedulingAlgorithm(Parameters.SchedulingAlgorithm.STATIC)
                        .fileSystem(ReplicaCatalog.FileSystem.LOCAL)
                        .clusteringParameters(new ClusteringParameters(0, 0,
                                ClusteringParameters.ClusteringMethod.BALANCED, ""))
                        .build());
        assertTrue(clustering.getMessage().contains("NONE"), clustering.getMessage());
    }

    private static SimulationReport runPaperExample() throws Exception {
        Log.disable();
        SimulationConfig config = SimulationConfig.builder(resourcePath("/dax/heft-paper-example.dax"), 3)
                .planningAlgorithm(Parameters.PlanningAlgorithm.LOCAL_HEFT)
                .schedulingAlgorithm(Parameters.SchedulingAlgorithm.STATIC)
                .taskCostMatrix(paperCostMatrix())
                .fileSystem(ReplicaCatalog.FileSystem.LOCAL)
                .dataMovementModel(DataMovementModel.preExecutionTransferDelayV1())
                .build();
        return new SimulationRunner().run(config, paperPlatform());
    }

    /** 复现平台：3 台 VM，mips=1.0、带宽 1 MB/s——成本/传输秒数逐位等于论文值。 */
    private static PlatformProfile paperPlatform() {
        PlatformProfile.Builder builder = PlatformProfile.builder("heft-paper-platform");
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

    private static SimulationEvent transferEventForTask(SimulationReport report, int taskId) {
        for (SimulationEvent event : report.getEvents()) {
            if (event.getType() == SimulationEventType.DATA_STAGE_IN_MODELED
                    && event.getTaskIds().contains(Integer.valueOf(taskId))) {
                return event;
            }
        }
        throw new AssertionError("No DATA_STAGE_IN_MODELED event for task " + taskId);
    }

    private static SimulationReport.JobOutcome computeJobForTask(SimulationReport report, int taskId) {
        for (SimulationReport.JobOutcome job : report.getJobs()) {
            if (job.getClassType() == Parameters.ClassType.COMPUTE.value
                    && job.getTaskIds().contains(Integer.valueOf(taskId))) {
                return job;
            }
        }
        throw new AssertionError("No compute job for task " + taskId);
    }

    private static String resourcePath(String resource) throws Exception {
        java.net.URL url = LocalHeftPaperReproductionTest.class.getResource(resource);
        if (url == null) {
            throw new IllegalStateException("Missing test resource " + resource);
        }
        return Paths.get(url.toURI()).toString();
    }
}
