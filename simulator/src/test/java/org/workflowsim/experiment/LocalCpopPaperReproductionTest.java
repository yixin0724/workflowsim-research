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
import org.workflowsim.utils.TaskCostMatrix;

/**
 * CPOP 论文例（Critical-Path-on-a-Processor，与 HEFT 同出 Topcuoglu, Hariri &amp;
 * Wu, IEEE TPDS 2002）在受控 LOCAL 执行模型上的复现回归，与
 * {@link LocalHeftPaperReproductionTest} 使用同一份核实过的论文算例数据。
 *
 * <p><b>按经典 CPOP rank 公式手算的受控模型结果（引导偏移 110.1）</b></p>
 * <ul>
 *   <li>关键路径 = {n1, n2, n9, n10}，平均路径长度为 108；向下 rank 从入口
 *       沿前驱累加前驱计算成本与入边通信成本，关键路径节点的 r_u + r_d 均为 108；</li>
 *   <li>关键路径处理器 p_CP = vm1（关键路径计算总秒数：vm0=66、vm1=54、vm2=63），
 *       全部四个关键路径任务绑定该 VM；</li>
 *   <li>去除完整引导偏移后的 makespan 为 86（绝对 196.1），与论文 makespan 86
 *       一致；每任务映射、区间与传输量按该 fixture 的插入式 EFT 规则分别锁定。</li>
 * </ul>
 */
class LocalCpopPaperReproductionTest {

    private static final double[][] PAPER_COST_SECONDS = {
            {14, 16, 9}, {13, 19, 18}, {11, 13, 19}, {13, 8, 17}, {12, 13, 10},
            {13, 16, 9}, {7, 15, 11}, {5, 11, 14}, {18, 12, 20}, {21, 7, 16},
    };
    private static final double BOOTSTRAP_OFFSET = 110.1;

    /** 经典 CPOP rank、关键路径绑定和插入式 EFT 下独立推导的每任务 VM 映射。 */
    private static final int[] CONTROLLED_VM_BY_TASK = {-1, 1, 1, 0, 2, 1, 2, 0, 2, 1, 1};
    /** 受控模型的运行时开始时刻（绝对秒），下标 = 任务 ID。 */
    private static final double[] CONTROLLED_STARTS = {
            0.0, BOOTSTRAP_OFFSET, 126.1, 138.1, 135.1, 145.1, 152.1, 149.1, 164.1, 175.1, 189.1};
    /** 受控模型的运行时完成时刻（绝对秒），下标 = 任务 ID。 */
    private static final double[] CONTROLLED_FINISHES = {
            0.0, 126.1, 145.1, 149.1, 152.1, 158.1, 161.1, 156.1, 178.1, 187.1, 196.1};
    /** 每任务实际建模传输秒数（副本已在目标 VM 上为零；跨 VM = 边权）。 */
    private static final double[] CONTROLLED_TRANSFER_SECONDS = {
            0.0, 0.0, 0.0, 12.0, 9.0, 0.0, 14.0, 0.0, 19.0, 23.0, 28.0};

    @Test
    void cpopPaperExampleReproducesControlledScheduleAndMapping() throws Exception {
        SimulationReport report = runPaperExample();
        assertEquals(196.1, report.getMakespan(), 1.0e-9);
        assertEquals(86.0, report.getMakespan() - BOOTSTRAP_OFFSET, 1.0e-9);
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
        // 关键路径处理器绑定：全部四个关键路径任务同在 p_CP = vm1。
        for (int taskId : new int[] {1, 2, 9, 10}) {
            assertEquals(1, computeJobForTask(report, taskId).getVmId(),
                    "关键路径任务 " + taskId + " 应绑定关键路径处理器 vm1");
        }
    }

    @Test
    void cpopPaperScheduleIsCommunicationAwareAndDeterministic() throws Exception {
        SimulationReport first = runPaperExample();
        for (int taskId = 1; taskId <= 10; taskId++) {
            SimulationEvent event = transferEventForTask(first, taskId);
            assertEquals(CONTROLLED_TRANSFER_SECONDS[taskId],
                    ((Number) event.getAttributes().get("modeledTransferSeconds")).doubleValue(),
                    1.0e-9, "task " + taskId + " 的建模传输秒数");
        }
        assertEquals(105.0, first.getMetrics().getTotalModeledDataTransferSeconds(), 1.0e-9);
        assertEquals(10, first.getMetrics().getDataStageInModelObservationCount());

        SimulationReport second = runPaperExample();
        assertEquals(first.getMakespan(), second.getMakespan(), 0.0);
        for (int taskId = 1; taskId <= 10; taskId++) {
            assertEquals(computeJobForTask(first, taskId).getVmId(),
                    computeJobForTask(second, taskId).getVmId());
            assertTrue(Double.compare(computeJobForTask(first, taskId).getFinishTime(),
                    computeJobForTask(second, taskId).getFinishTime()) == 0);
        }
    }

    @Test
    void localCpopSharesHeftControlledModelRejections() throws Exception {
        String workflow = resourcePath("/dax/heft-paper-example.dax");
        IllegalArgumentException shared = org.junit.jupiter.api.Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> SimulationConfig.builder(workflow, 3)
                        .planningAlgorithm(Parameters.PlanningAlgorithm.LOCAL_CPOP)
                        .schedulingAlgorithm(Parameters.SchedulingAlgorithm.STATIC)
                        .fileSystem(ReplicaCatalog.FileSystem.SHARED)
                        .build());
        assertTrue(shared.getMessage().contains("LOCAL_CPOP"), shared.getMessage());
    }

    private static SimulationReport runPaperExample() throws Exception {
        Log.disable();
        SimulationConfig config = SimulationConfig.builder(resourcePath("/dax/heft-paper-example.dax"), 3)
                .planningAlgorithm(Parameters.PlanningAlgorithm.LOCAL_CPOP)
                .schedulingAlgorithm(Parameters.SchedulingAlgorithm.STATIC)
                .taskCostMatrix(paperCostMatrix())
                .fileSystem(ReplicaCatalog.FileSystem.LOCAL)
                .dataMovementModel(DataMovementModel.preExecutionTransferDelayV1())
                .build();
        return new SimulationRunner().run(config, paperPlatform());
    }

    /** 复现平台：3 台 VM，mips=1.0、带宽 1 MB/s、SPACE_SHARED。 */
    private static PlatformProfile paperPlatform() {
        PlatformProfile.Builder builder = PlatformProfile.builder("cpop-paper-platform");
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
        java.net.URL url = LocalCpopPaperReproductionTest.class.getResource(resource);
        if (url == null) {
            throw new IllegalStateException("Missing test resource " + resource);
        }
        return Paths.get(url.toURI()).toString();
    }
}
