package org.workflowsim.experiment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URL;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;
import org.cloudbus.cloudsim.Log;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.workflowsim.platform.PlatformProfile;
import org.workflowsim.platform.PlatformProfiles;
import org.workflowsim.utils.ClusteringParameters;
import org.workflowsim.utils.Parameters;
import org.workflowsim.utils.ReplicaCatalog;
import org.workflowsim.utils.SimulationConfig;
import org.workflowsim.utils.TaskCostMatrix;

/**
 * 任务×VM 异构成本矩阵（论文复现支撑能力）的端到端语义测试。
 *
 * <p>覆盖三个契约：(1) 矩阵条目精确驱动运行时执行时间；(2) 配置层拒绝静默
 * 退化的组合（非 STATIC 派发、非 NONE 聚类）；(3) 覆盖缺口在任何派发前
 * fail-fast。另外验证 LOCAL 模式下任务间通信（中间文件跨 VM 传输）按
 * {@code min(bw_src, bw_dst)} 建模并可从证据事件观测。</p>
 */
class TaskCostMatrixIntegrationTest {

    @AfterEach
    void restoreLogging() {
        Log.enable();
    }

    private static TaskCostMatrix independentTaskMatrix() {
        return TaskCostMatrix.builder()
                .put(1, 0, 2.0).put(1, 1, 3.0).put(1, 2, 5.0)
                .put(2, 0, 5.0).put(2, 1, 4.0).put(2, 2, 6.0)
                .put(3, 0, 1.5).put(3, 1, 7.0).put(3, 2, 2.5)
                .build();
    }

    @Test
    void matrixEntriesDriveExactRuntimeExecutionTimes() throws Exception {
        String workflow = resourcePath("/dax/independent-tasks.dax");
        PlatformProfile platform = PlatformProfiles.homogeneousLocal("cost-matrix-platform", 3);
        TaskCostMatrix matrix = independentTaskMatrix();
        SimulationConfig config = SimulationConfig.builder(workflow, 3)
                .schedulingAlgorithm(Parameters.SchedulingAlgorithm.STATIC)
                .planningAlgorithm(Parameters.PlanningAlgorithm.RANDOM)
                .randomSeed(42L)
                .taskCostMatrix(matrix)
                .build();

        Log.disable();
        SimulationRunner runner = new SimulationRunner();
        assertRuntimeMatchesMatrix(runner.run(config, platform), matrix);
        // 确定性：同配置重跑得到完全相同的执行时间。
        assertRuntimeMatchesMatrix(runner.run(config, platform), matrix);
    }

    @Test
    void configRejectsCostMatrixWithOnlineSchedulerOrClustering() throws Exception {
        String workflow = resourcePath("/dax/independent-tasks.dax");
        TaskCostMatrix matrix = independentTaskMatrix();
        IllegalArgumentException online = assertThrows(IllegalArgumentException.class,
                () -> SimulationConfig.builder(workflow, 3)
                        .schedulingAlgorithm(Parameters.SchedulingAlgorithm.FCFS)
                        .taskCostMatrix(matrix)
                        .build());
        assertTrue(online.getMessage().contains("STATIC"));

        IllegalArgumentException clustering = assertThrows(IllegalArgumentException.class,
                () -> SimulationConfig.builder(workflow, 3)
                        .schedulingAlgorithm(Parameters.SchedulingAlgorithm.STATIC)
                        .planningAlgorithm(Parameters.PlanningAlgorithm.RANDOM)
                        .clusteringParameters(new ClusteringParameters(0, 0,
                                ClusteringParameters.ClusteringMethod.BALANCED, null))
                        .taskCostMatrix(matrix)
                        .build());
        assertTrue(clustering.getMessage().contains("NONE"));
    }

    @Test
    void coverageGapFailsFastBeforeAnyDispatch() throws Exception {
        String workflow = resourcePath("/dax/independent-tasks.dax");
        PlatformProfile platform = PlatformProfiles.homogeneousLocal("cost-matrix-gap-platform", 3);
        // 矩阵只覆盖 VM 0/1，平台有 3 台 VM——必须在解析投影阶段失败。
        TaskCostMatrix partial = TaskCostMatrix.builder()
                .put(1, 0, 2.0).put(1, 1, 3.0)
                .put(2, 0, 5.0).put(2, 1, 4.0)
                .put(3, 0, 1.5).put(3, 1, 7.0)
                .build();
        SimulationConfig config = SimulationConfig.builder(workflow, 3)
                .schedulingAlgorithm(Parameters.SchedulingAlgorithm.STATIC)
                .planningAlgorithm(Parameters.PlanningAlgorithm.RANDOM)
                .randomSeed(42L)
                .taskCostMatrix(partial)
                .build();

        Log.disable();
        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> new SimulationRunner().run(config, platform));
        assertTrue(failure.getMessage().contains("does not cover"));
    }

    @Test
    void localModeModelsCrossVmIntermediateTransferWithMinBandwidth() throws Exception {
        String workflow = resourcePath("/dax/local-data-transfer.dax");
        PlatformProfile platform = PlatformProfiles.homogeneousLocal("local-comm-platform", 2);
        SimulationConfig config = SimulationConfig.builder(workflow, 2)
                .schedulingAlgorithm(Parameters.SchedulingAlgorithm.STATIC)
                .planningAlgorithm(Parameters.PlanningAlgorithm.RANDOM)
                .randomSeed(42L)
                .fileSystem(ReplicaCatalog.FileSystem.LOCAL)
                .build();

        Log.disable();
        SimulationReport report = new SimulationRunner().run(config, platform);

        Map<Integer, Integer> taskVm = new HashMap<Integer, Integer>();
        for (SimulationReport.JobOutcome job : report.getJobs()) {
            if (job.getClassType() == org.workflowsim.utils.Parameters.ClassType.COMPUTE.value
                    && job.getTaskIds().size() == 1) {
                taskVm.put(Integer.valueOf(job.getTaskIds().get(0).intValue()),
                        Integer.valueOf(job.getVmId()));
            }
        }
        assertEquals(2, taskVm.size());
        // 同构平台 VM 带宽均为 1000 MB/s；跨 VM 传输 30 MB = 0.03 秒，同 VM 为 0。
        double expectedTransfer = taskVm.get(Integer.valueOf(1)).equals(taskVm.get(Integer.valueOf(2)))
                ? 0.0 : 30.0 / 1000.0;
        SimulationEvent childTransfer = transferEventForTask(report, 2);
        assertEquals(expectedTransfer,
                ((Number) childTransfer.getAttributes().get("modeledTransferSeconds")).doubleValue(),
                1.0e-12);
        assertEquals("LEGACY_WORKFLOWSIM_V1", childTransfer.getAttributes().get("dataMovementModel"));
        // 子任务信封执行时间 = 计算 1.0 秒 + stage-in MI 表示的传输时间。
        SimulationReport.JobOutcome child = computeJobForTask(report, 2);
        assertEquals(1.0 + expectedTransfer, child.getExecutionTime(), 1.0e-9);
        assertEquals(2, report.getMetrics().getDataStageInModelObservationCount());
        assertEquals(expectedTransfer,
                report.getMetrics().getTotalModeledDataTransferSeconds(), 1.0e-12);
    }

    private static void assertRuntimeMatchesMatrix(SimulationReport report, TaskCostMatrix matrix) {
        int computeCount = 0;
        for (SimulationReport.JobOutcome job : report.getJobs()) {
            if (job.getClassType() != org.workflowsim.utils.Parameters.ClassType.COMPUTE.value
                    || job.getTaskIds().size() != 1) {
                continue;
            }
            computeCount++;
            double expected = matrix.getCostSeconds(job.getTaskIds().get(0).intValue(),
                    job.getVmId());
            assertEquals(expected, job.getExecutionTime(), 1.0e-9,
                    "task " + job.getTaskIds().get(0) + " on VM " + job.getVmId());
        }
        assertEquals(3, computeCount);
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
            if (job.getClassType() == org.workflowsim.utils.Parameters.ClassType.COMPUTE.value
                    && job.getTaskIds().contains(Integer.valueOf(taskId))) {
                return job;
            }
        }
        throw new AssertionError("No compute job for task " + taskId);
    }

    private String resourcePath(String resource) throws Exception {
        URL url = getClass().getResource(resource);
        if (url == null) {
            throw new IllegalStateException("Missing test resource " + resource);
        }
        return Paths.get(url.toURI()).toString();
    }
}
