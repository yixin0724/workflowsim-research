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
 * PEFT on the retained HEFT-origin ten-task fixture, not the PEFT article Figure 1.
 * Correct successor-cost Eq. (7), exit zero and insertion scheduling were independently
 * evaluated on this unchanged input: compute makespan85, bootstrap110.1, total195.1.
 * Mapping, intervals, per-task transfers and repeated-run assertions remain covered.
 * The former class name and its attribution of76/80 to the PEFT article were incorrect;
 * primary-source PEFT122/HEFT133 evidence lives in LocalPeftPrimarySourcePaperTest.
 */
class LocalPeftHeftOriginRegressionTest {

    /** 保留HEFT-origin输入的计算矩阵；不是PEFT文章Figure1。 */
    private static final double[][] HEFT_ORIGIN_COST_SECONDS = {
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

    /** 独立Eq7推导的每任务映射，VM ID为0基。 */
    private static final int[] CONTROLLED_VM_BY_TASK =
            {-1, 1, 1, 0, 1, 2, 0, 0, 0, 1, 1};
    /** 受控模型的运行时开始时刻（绝对秒），下标 = 任务 ID；独立Eq7区间 + 引导偏移110.1。 */
    private static final double[] CONTROLLED_STARTS = {
            0.0, BOOTSTRAP_OFFSET, 134.1, 138.1, 126.1, 137.1, 149.1, 162.1, 172.1, 160.1, 188.1};
    /** 受控模型的运行时完成时刻（绝对秒），下标 = 任务 ID；独立Eq7区间 + 引导偏移110.1。 */
    private static final double[] CONTROLLED_FINISHES = {
            0.0, 126.1, 153.1, 149.1, 134.1, 147.1, 162.1, 169.1, 177.1, 172.1, 195.1};
    /**
     * 由新映射与原HEFT-origin边权独立求和：
     * n8=19(n2,VM1→0)+27(n4,VM1→0)+0(n6本地)=46；
     * n9=0(n2本地)+0(n4本地)+13(n5,VM2→1)=13；
     * n10=17(n7,VM0→1)+11(n8,VM0→1)+0(n9本地)=28。
     */
    private static final double[] CONTROLLED_TRANSFER_SECONDS = {
            0.0, 0.0, 0.0, 12.0, 0.0, 11.0, 14.0, 0.0, 46.0, 13.0, 28.0};

    @Test
    void peftHeftOriginExampleReproducesControlledScheduleAndMapping() throws Exception {
        SimulationReport report = runHeftOriginFixture();
        // Independent Eq7 result on this HEFT-origin input is85, not the PEFT paper's122.
        assertEquals(195.1, report.getMakespan(), 1.0e-9);
        assertEquals(85.0, report.getMakespan() - BOOTSTRAP_OFFSET, 1.0e-9);
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
    void peftHeftOriginScheduleIsCommunicationAware() throws Exception {
        SimulationReport report = runHeftOriginFixture();
        // n2/n4/n7与其父任务同VM；其他传输按保留输入的边权逐项求和。
        for (int taskId = 1; taskId <= 10; taskId++) {
            SimulationEvent event = transferEventForTask(report, taskId);
            assertEquals(CONTROLLED_TRANSFER_SECONDS[taskId],
                    ((Number) event.getAttributes().get("modeledTransferSeconds")).doubleValue(),
                    1.0e-9, "task " + taskId + " 的建模传输秒数");
        }
        assertEquals(124.0, report.getMetrics().getTotalModeledDataTransferSeconds(), 1.0e-9);
        assertEquals(10, report.getMetrics().getDataStageInModelObservationCount());
    }

    @Test
    void peftHeftOriginReproductionIsDeterministic() throws Exception {
        SimulationReport first = runHeftOriginFixture();
        SimulationReport second = runHeftOriginFixture();
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

    private static SimulationReport runHeftOriginFixture() throws Exception {
        Log.disable();
        SimulationConfig config = SimulationConfig.builder(
                        resourcePath("/dax/heft-paper-example.dax"), 3)
                .planningAlgorithm(Parameters.PlanningAlgorithm.LOCAL_PEFT)
                .schedulingAlgorithm(Parameters.SchedulingAlgorithm.STATIC)
                .taskCostMatrix(heftOriginCostMatrix())
                .fileSystem(ReplicaCatalog.FileSystem.LOCAL)
                .dataMovementModel(DataMovementModel.preExecutionTransferDelayV1())
                .build();
        return new SimulationRunner().run(config, controlledPlatform());
    }

    /** 受控HEFT-origin平台：3台VM，MIPS=1、带宽1 MB/s，整数成本无MI舍入损失。 */
    private static PlatformProfile controlledPlatform() {
        PlatformProfile.Builder builder = PlatformProfile.builder("peft-heft-origin-regression");
        for (int id = 0; id < 3; id++) {
            builder.addHost(new PlatformProfile.HostSpec(id, 2, 2.0, 2048, 10_000L, 1_000_000L));
            builder.addVm(new PlatformProfile.VmSpec(id, 1.0, 1, 512, 1L, 10_000L, "Xen",
                    PlatformProfile.CloudletSchedulerMode.SPACE_SHARED));
        }
        return builder.build();
    }

    private static TaskCostMatrix heftOriginCostMatrix() {
        TaskCostMatrix.Builder builder = TaskCostMatrix.builder();
        for (int taskId = 1; taskId <= HEFT_ORIGIN_COST_SECONDS.length; taskId++) {
            for (int vmId = 0; vmId < 3; vmId++) {
                builder.put(taskId, vmId, HEFT_ORIGIN_COST_SECONDS[taskId - 1][vmId]);
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
        java.net.URL url = LocalPeftHeftOriginRegressionTest.class.getResource(resource);
        if (url == null) {
            throw new IllegalStateException("Missing test resource " + resource);
        }
        return Paths.get(url.toURI()).toString();
    }
}
