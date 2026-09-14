package org.workflowsim.experiment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
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
 * R2 链路争用带宽模型的端到端语义验收（HEFT 论文例 fixture）。
 *
 * <p>fixture 选择理由：论文例的受控调度（见 {@link LocalHeftPaperReproductionTest}）
 * 中存在真实并发传输——t2（18 MB）与 t4（9 MB）的输入传输都在父任务 t1 完成时刻
 * （110.1+9.0=119.1 s）同时开始，且共享源端点 vm2 的 1 MB/s 网卡。无争用模型下两
 * 条传输各自按全额速率进行；争用模型下两条传输公平共享 vm2 出口容量（各 0.5 MB/s），
 * 完成时刻被推迟，makespan 严格增大——这正是数据感知调度研究关心的权衡信号。</p>
 *
 * <p>断言结构：</p>
 * <ul>
 *   <li>争用 makespan 严格大于无争用基线 190.1（并发传输互相减速的直接证据）；</li>
 *   <li>黄金值锁定：争用 makespan 与逐任务完成时刻逐位稳定（确定性复跑）；</li>
 *   <li>证据链：DATA_STAGE_IN_MODELED 携带争用传输组计数与新模型 kind；</li>
 *   <li>配置边界：INVALID 规划层 + 争用模型被拒绝（就绪时刻无目标 VM）；
 *       LOCAL_HEFT + 争用模型被允许（规划侧无争用估计、运行期争用，偏差由文档声明）。</li>
 * </ul>
 */
class LinkContentionSemanticsTest {

    /** 无争用基线 makespan（论文 80 + 引导偏移 110.1，见 LocalHeftPaperReproductionTest）。 */
    private static final double NO_CONTENTION_BASELINE = 190.1;

    /**
     * 争用黄金值：由本 fixture 实测锁定（2026-09-14），复跑逐位稳定。手算佐证：
     * t2（18 MB）与 t4（9 MB）共享源端点 vm2（1 MB/s）各得 0.5 MB/s——t4 完成于
     * 119.1+18=137.1，t2 此后恢复全额再需 9 秒（146.1），后续传输链依次推迟，
     * 总 makespan 从无争用 190.1 增至 284.1。
     */
    private static final double CONTENTION_GOLDEN_MAKESPAN = 284.1;

    /** 论文成本矩阵（与 LocalHeftPaperReproductionTest 相同）。 */
    private static final double[][] PAPER_COST_SECONDS = {
            {14, 16, 9}, {13, 19, 18}, {11, 13, 19}, {13, 8, 17}, {12, 13, 10},
            {13, 16, 9}, {7, 15, 11}, {5, 11, 14}, {18, 12, 20}, {21, 7, 16}};

    @Test
    void contentionMakespanStrictlyExceedsNoContentionBaseline() throws Exception {
        SimulationReport report = runWithContention();
        assertTrue(report.getMakespan() > NO_CONTENTION_BASELINE,
                "争用 makespan " + report.getMakespan() + " 应严格大于无争用基线 "
                        + NO_CONTENTION_BASELINE);
    }

    @Test
    void contentionGoldenMakespanIsLocked() throws Exception {
        SimulationReport report = runWithContention();
        assertEquals(CONTENTION_GOLDEN_MAKESPAN, report.getMakespan(), 0.0,
                "争用黄金值漂移：fixture 或争用模型语义发生变化，须显式审查");
    }

    @Test
    void contentionScheduleIsDeterministic() throws Exception {
        SimulationReport first = runWithContention();
        SimulationReport second = runWithContention();
        assertEquals(first.getMakespan(), second.getMakespan(), 0.0);
        for (int taskId = 1; taskId <= 10; taskId++) {
            assertEquals(computeFinish(first, taskId), computeFinish(second, taskId), 0.0,
                    "task " + taskId + " 的完成时刻在复跑间必须逐位一致");
        }
    }

    @Test
    void contentionEvidenceIsRecordedOnStageInEvents() throws Exception {
        SimulationReport report = runWithContention();
        int observed = 0;
        for (SimulationEvent event : report.getEvents()) {
            if (event.getType() != SimulationEventType.DATA_STAGE_IN_MODELED) {
                continue;
            }
            observed++;
            assertEquals("PRE_EXECUTION_TRANSFER_DELAY_WITH_CONTENTION_V1",
                    event.getAttributes().get("dataMovementModel"));
            assertNotNull(event.getAttributes().get("contentionTransferGroupCount"),
                    "争用模型证据必须携带传输组计数");
        }
        // 10 个计算任务全部记录传输证据（与无争用模型证据覆盖一致）。
        assertEquals(10, observed);
    }

    @Test
    void contentionModelConfigurationBoundaries() throws Exception {
        String workflow = resourcePath("/dax/heft-paper-example.dax");
        // INVALID 规划层 + 争用模型：就绪时刻没有目标 VM，无法确定传输端点。
        IllegalArgumentException unmapped = assertThrows(IllegalArgumentException.class,
                () -> SimulationConfig.builder(workflow, 3)
                        .schedulingAlgorithm(Parameters.SchedulingAlgorithm.MINMIN)
                        .fileSystem(ReplicaCatalog.FileSystem.LOCAL)
                        .dataMovementModel(
                                DataMovementModel.preExecutionTransferDelayWithContentionV1())
                        .build());
        assertTrue(unmapped.getMessage().contains("INVALID"), unmapped.getMessage());
        // SHARED_STORAGE_HEFT + 争用模型：共享存储轨道要求 legacy 模型对齐。
        IllegalArgumentException shared = assertThrows(IllegalArgumentException.class,
                () -> SimulationConfig.builder(workflow, 3)
                        .planningAlgorithm(Parameters.PlanningAlgorithm.SHARED_STORAGE_HEFT)
                        .schedulingAlgorithm(Parameters.SchedulingAlgorithm.STATIC)
                        .fileSystem(ReplicaCatalog.FileSystem.SHARED)
                        .dataMovementModel(
                                DataMovementModel.preExecutionTransferDelayWithContentionV1())
                        .build());
        assertTrue(shared.getMessage().contains("legacyWorkflowsimV1"), shared.getMessage());
        // LOCAL_HEFT + 争用模型：允许（规划侧无争用 AST、运行期公平共享，偏差由文档声明）。
        assertNotNull(SimulationConfig.builder(workflow, 3)
                .planningAlgorithm(Parameters.PlanningAlgorithm.LOCAL_HEFT)
                .schedulingAlgorithm(Parameters.SchedulingAlgorithm.STATIC)
                .taskCostMatrix(paperCostMatrix())
                .fileSystem(ReplicaCatalog.FileSystem.LOCAL)
                .dataMovementModel(
                        DataMovementModel.preExecutionTransferDelayWithContentionV1())
                .build());
    }

    private static SimulationReport runWithContention() throws Exception {
        Log.disable();
        SimulationConfig config = SimulationConfig.builder(
                        resourcePath("/dax/heft-paper-example.dax"), 3)
                .planningAlgorithm(Parameters.PlanningAlgorithm.LOCAL_HEFT)
                .schedulingAlgorithm(Parameters.SchedulingAlgorithm.STATIC)
                .taskCostMatrix(paperCostMatrix())
                .fileSystem(ReplicaCatalog.FileSystem.LOCAL)
                .dataMovementModel(
                        DataMovementModel.preExecutionTransferDelayWithContentionV1())
                .build();
        return new SimulationRunner().run(config, contentionPlatform());
    }

    /** 与论文例相同的平台：3 台 VM，mips=1.0、带宽 1 MB/s（争用容量 1e6 字节/秒）。 */
    private static PlatformProfile contentionPlatform() {
        PlatformProfile.Builder builder = PlatformProfile.builder("link-contention-platform");
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
        java.net.URL url = LinkContentionSemanticsTest.class.getResource(resource);
        if (url == null) {
            throw new IllegalStateException("Missing test resource " + resource);
        }
        return Paths.get(url.toURI()).toString();
    }
}
