package org.workflowsim.experiment;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Paths;
import org.cloudbus.cloudsim.Log;
import org.junit.jupiter.api.Test;
import org.workflowsim.data.DataMovementModel;
import org.workflowsim.platform.PlatformProfile;
import org.workflowsim.utils.Parameters;
import org.workflowsim.utils.ReplicaCatalog;
import org.workflowsim.utils.SimulationConfig;
import org.workflowsim.utils.TaskCostMatrix;

/**
 * PEFT 论文例（Arabnejad &amp; Barbosa, IEEE TPDS 2014, "List Scheduling
 * Algorithm for Heterogeneous Systems by an Optimistic Cost Table"）在受控
 * LOCAL 执行模型上的复现回归。
 *
 * <p><b>数据来源核实（2026-06）</b>：PEFT 论文算例与 HEFT/CPOP 论文
 * （Topcuoglu, Hariri &amp; Wu, IEEE TPDS 2002）使用同一 10 任务 DAG、同一
 * 3×10 计算成本矩阵与同一边权集合，因此本测试复用
 * {@code /dax/heft-paper-example.dax}（该 fixture 已经
 * {@link LocalHeftPaperReproductionTest} 对论文 rank 表核实）与同一平台/成本
 * 矩阵编码：3 台 VM mips=1.0、带宽 1 MB/s；边权 = 中间文件字节数/1e6（跨 VM
 * 传输秒数逐位等于论文边权，同 VM 为零）；计算成本 = 任务×VM 成本矩阵秒数
 * （MI 折算恒等）。论文出口约定 OCT(t_exit, p) = w̄_exit 已实现；对本单出口
 * 算例，该约定相对 OCT(exit)=0 是全体 OCT 的均匀平移（w̄_exit = 44/3），
 * 优先级次序、VM 选择与调度区间不变，但论文发表的 OCT/rank_o 表只在
 * w̄_exit 约定下可复现——OCT 表与 rank_o 表逐项断言见规划层测试
 * {@code LocalPeftPlanningAlgorithmTest#paperExampleOctTableMatchesPublishedValues}。</p>
 *
 * <p><b>复现结论（受控模型 vs 论文理想模型）</b></p>
 * <ul>
 *   <li>任务选择顺序 = rank_o 降序 {n1, n2, n4, n5, n3, n6, n9, n7, n8, n10}，
 *       与论文一致（rank_o: 61, 48, 44, 43, 40, 37.33, 31.33, 25.67, 24.67,
 *       14.67）；</li>
 *   <li>VM 映射 10/10 与论文调度一致（n1→p3, n2→p3, n3→p1, n4→p2, n5→p1,
 *       n6→p3, n7→p1, n8→p1, n9→p2, n10→p2；0 基 VM ID 见常量表），且每任务
 *       区间逐位等于论文区间 + 引导偏移 110.1（如 n10 [69-76] → [179.1-186.1]）；</li>
 *   <li>受控 makespan = 论文 makespan 76 + 引导偏移 110.1 = 186.1，优于同算例
 *       HEFT 的 80（{@link LocalHeftPaperReproductionTest} 锁定 190.1）——
 *       复现论文关于 PEFT 在该算例上短于 HEFT 的结论；</li>
 *   <li>规划与运行时逐位一致：计划开始/完成时刻 == 运行时观测（确定性复跑稳定）。</li>
 * </ul>
 */
class LocalPeftPaperReproductionTest {

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

    /** 受控模型的每任务 VM 映射（10/10 与论文调度一致；论文 p1/p2/p3 = vm0/vm1/vm2）。 */
    private static final int[] CONTROLLED_VM_BY_TASK =
            {-1, 2, 2, 0, 1, 0, 2, 0, 0, 1, 1};
    /** 受控模型的运行时开始时刻（绝对秒），下标 = 任务 ID；论文区间 + 引导偏移 110.1。 */
    private static final double[] CONTROLLED_STARTS = {
            0.0, BOOTSTRAP_OFFSET, 119.1, 142.1, 128.1, 130.1, 137.1, 153.1, 163.1, 155.1, 179.1};
    /** 受控模型的运行时完成时刻（绝对秒），下标 = 任务 ID；论文区间 + 引导偏移 110.1。 */
    private static final double[] CONTROLLED_FINISHES = {
            0.0, 119.1, 137.1, 153.1, 136.1, 142.1, 146.1, 160.1, 168.1, 167.1, 186.1};
    /**
     * 每任务实际建模传输秒数（同 VM 副本局部性为零；跨 VM = 论文边权之和）。
     * n8 = 19(n2,p3→p1) + 27(n4,p2→p1) + 15(n6,p3→p1) = 61；
     * n9 = 16(n2,p3→p2) + 0(n4 同 VM) + 13(n5,p1→p2) = 29；
     * n10 = 17(n7,p1→p2) + 11(n8,p1→p2) + 0(n9 同 VM) = 28。
     */
    private static final double[] CONTROLLED_TRANSFER_SECONDS = {
            0.0, 0.0, 0.0, 12.0, 9.0, 11.0, 0.0, 0.0, 61.0, 29.0, 28.0};

    @Test
    void peftPaperExampleReproducesControlledScheduleAndMapping() throws Exception {
        SimulationReport report = runPaperExample();
        // 受控 makespan = 论文 makespan 76 + 引导偏移 110.1；优于同算例 HEFT 的 80。
        assertEquals(186.1, report.getMakespan(), 1.0e-9);
        assertEquals(76.0, report.getMakespan() - BOOTSTRAP_OFFSET, 1.0e-9);
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
    void peftPaperScheduleIsCommunicationAware() throws Exception {
        SimulationReport report = runPaperExample();
        // n1/n2/n6/n7 与父任务同 VM：副本局部性使传输为零；跨 VM 任务的建模传输
        // 秒数逐位等于论文边权（字节数/1e6/1 MB/s）。
        for (int taskId = 1; taskId <= 10; taskId++) {
            SimulationEvent event = transferEventForTask(report, taskId);
            assertEquals(CONTROLLED_TRANSFER_SECONDS[taskId],
                    ((Number) event.getAttributes().get("modeledTransferSeconds")).doubleValue(),
                    1.0e-9, "task " + taskId + " 的建模传输秒数");
        }
        assertEquals(150.0, report.getMetrics().getTotalModeledDataTransferSeconds(), 1.0e-9);
        assertEquals(10, report.getMetrics().getDataStageInModelObservationCount());
    }

    @Test
    void peftPaperReproductionIsDeterministic() throws Exception {
        SimulationReport first = runPaperExample();
        SimulationReport second = runPaperExample();
        assertEquals(first.getMakespan(), second.getMakespan(), 0.0);
        for (int taskId = 1; taskId <= 10; taskId++) {
            assertEquals(computeJobForTask(first, taskId).getVmId(),
                    computeJobForTask(second, taskId).getVmId());
            assertEquals(computeJobForTask(first, taskId).getStartTime(),
                    computeJobForTask(second, taskId).getStartTime(), 0.0);
            assertEquals(computeJobForTask(first, taskId).getFinishTime(),
                    computeJobForTask(second, taskId).getFinishTime(), 0.0);
        }
    }

    private static SimulationReport runPaperExample() throws Exception {
        Log.disable();
        SimulationConfig config = SimulationConfig.builder(
                        resourcePath("/dax/heft-paper-example.dax"), 3)
                .planningAlgorithm(Parameters.PlanningAlgorithm.LOCAL_PEFT)
                .schedulingAlgorithm(Parameters.SchedulingAlgorithm.STATIC)
                .taskCostMatrix(paperCostMatrix())
                .fileSystem(ReplicaCatalog.FileSystem.LOCAL)
                .dataMovementModel(DataMovementModel.preExecutionTransferDelayV1())
                .build();
        return new SimulationRunner().run(config, paperPlatform());
    }

    /** 复现平台：3 台 VM，mips=1.0、带宽 1 MB/s——成本/传输秒数逐位等于论文值。 */
    private static PlatformProfile paperPlatform() {
        PlatformProfile.Builder builder = PlatformProfile.builder("peft-paper-platform");
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
        java.net.URL url = LocalPeftPaperReproductionTest.class.getResource(resource);
        if (url == null) {
            throw new IllegalStateException("Missing test resource " + resource);
        }
        return Paths.get(url.toURI()).toString();
    }
}
