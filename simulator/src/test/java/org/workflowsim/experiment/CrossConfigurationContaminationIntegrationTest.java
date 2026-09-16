package org.workflowsim.experiment;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.net.URL;
import java.nio.file.Paths;
import java.util.Map;
import java.util.TreeMap;
import org.cloudbus.cloudsim.Log;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.workflowsim.data.DataMovementModel;
import org.workflowsim.platform.PlatformProfile;
import org.workflowsim.platform.PlatformProfiles;
import org.workflowsim.utils.Parameters;
import org.workflowsim.utils.ReplicaCatalog;
import org.workflowsim.utils.SimulationConfig;

/**
 * 同 JVM 串行跨配置交叉污染 IT：在同一 JVM 内依次运行互不相同的配置
 * （DAG 规划器 / 随机规划器 / 在线调度器、不同 DAX、不同平台、不同种子），
 * 然后重跑先前的配置——任何静态状态泄漏（副本目录、随机流注册表、
 * 全局参数、CloudSim 时钟）都会让重跑结果偏离首次运行。
 *
 * <p>已有 {@code repeatedDaxRunsProduceEquivalentAuditableReports} 只覆盖
 * 相邻同配置双跑；本 IT 覆盖"不同配置夹在中间"的交替顺序
 * A→B→C→A'→B'→C'，这是研究者实际批量实验的真实形态。</p>
 */
class CrossConfigurationContaminationIntegrationTest {

    @AfterEach
    void restoreLogging() {
        Log.enable();
    }

    @Test
    void alternatingUnrelatedConfigurationsDoNotContaminateEachOther() throws Exception {
        SimulationConfig dagPlanner = SimulationConfig
                .builder(resourcePath("/dax/heft-paper-example.dax"), 3)
                .planningAlgorithm(Parameters.PlanningAlgorithm.LOCAL_HEFT)
                .schedulingAlgorithm(Parameters.SchedulingAlgorithm.STATIC)
                .fileSystem(ReplicaCatalog.FileSystem.LOCAL)
                .dataMovementModel(DataMovementModel.preExecutionTransferDelayV1())
                .randomSeed(91L)
                .build();
        PlatformProfile dagPlatform = PlatformProfiles.homogeneousLocal("audit-cross-a", 3);

        SimulationConfig randomPlanner = SimulationConfig
                .builder(resourcePath("/dax/independent-tasks.dax"), 2)
                .planningAlgorithm(Parameters.PlanningAlgorithm.RANDOM)
                .schedulingAlgorithm(Parameters.SchedulingAlgorithm.STATIC)
                .fileSystem(ReplicaCatalog.FileSystem.LOCAL)
                .dataMovementModel(DataMovementModel.preExecutionTransferDelayV1())
                .randomSeed(13L)
                .build();
        PlatformProfile randomPlatform = PlatformProfiles.homogeneousLocal("audit-cross-b", 2);

        SimulationConfig onlineScheduler = SimulationConfig
                .builder(resourcePath("/dax/reproducibility-workflow.dax"), 3)
                .schedulingAlgorithm(Parameters.SchedulingAlgorithm.READY_BATCH_MINMIN)
                .randomSeed(7L)
                .build();
        PlatformProfile onlinePlatform = PlatformProfiles.homogeneousLocal("audit-cross-c", 3);

        Log.disable();
        SimulationRunner runner = new SimulationRunner();

        SimulationReport aFirst = runner.run(dagPlanner, dagPlatform);
        SimulationReport bFirst = runner.run(randomPlanner, randomPlatform);
        SimulationReport cFirst = runner.run(onlineScheduler, onlinePlatform);
        SimulationReport aAgain = runner.run(dagPlanner, dagPlatform);
        SimulationReport bAgain = runner.run(randomPlanner, randomPlatform);
        SimulationReport cAgain = runner.run(onlineScheduler, onlinePlatform);

        assertRunStable(aFirst, aAgain);
        assertRunStable(bFirst, bAgain);
        assertRunStable(cFirst, cAgain);
    }

    private static void assertRunStable(SimulationReport first, SimulationReport again) {
        assertEquals(first.getMakespan(), again.getMakespan(), 0.0);
        assertEquals(first.getSuccessfulJobs(), again.getSuccessfulJobs());
        assertEquals(first.getFailedJobs(), again.getFailedJobs());
        assertEquals(first.getInputs().get(0).getSha256(), again.getInputs().get(0).getSha256());
        assertEquals(jobFingerprint(first), jobFingerprint(again));
        assertEquals(taskFingerprint(first), taskFingerprint(again));
        assertEquals(logicalEventFingerprint(first), logicalEventFingerprint(again));
        assertEquals(logicalMetricsFingerprint(first), logicalMetricsFingerprint(again));
        assertEquals(first.getVmSummaries(), again.getVmSummaries());
    }

    private String resourcePath(String resource) throws Exception {
        URL url = getClass().getResource(resource);
        if (url == null) {
            throw new IllegalStateException("Missing test resource " + resource);
        }
        return Paths.get(url.toURI()).toString();
    }

    private static String jobFingerprint(SimulationReport report) {
        StringBuilder result = new StringBuilder();
        for (SimulationReport.JobOutcome job : report.getJobs()) {
            result.append(job.getJobId()).append('|').append(job.getVmId()).append('|')
                    .append(job.getStatus()).append('|').append(job.getStartTime()).append('|')
                    .append(job.getFinishTime()).append('|').append(job.getCpuTime()).append('|')
                    .append(job.getTaskCount()).append('\n');
        }
        return result.toString();
    }

    private static String taskFingerprint(SimulationReport report) {
        StringBuilder result = new StringBuilder();
        for (SimulationReport.TaskOutcome task : report.getTasks()) {
            result.append(task.getTaskId()).append('|').append(task.getJobId()).append('|')
                    .append(task.getVmId()).append('|').append(task.getTaskStatus()).append('|')
                    .append(task.getStartTime()).append('|').append(task.getFinishTime()).append('\n');
        }
        return result.toString();
    }

    /** 确定性重放有意排除墙钟决策耗时。 */
    private static String logicalEventFingerprint(SimulationReport report) {
        StringBuilder result = new StringBuilder();
        for (SimulationEvent event : report.getEvents()) {
            Map<String, Object> attributes = new TreeMap<String, Object>(event.getAttributes());
            attributes.remove("decisionElapsedNanos");
            attributes.remove("planningDecisionElapsedNanos");
            result.append(event.getSequence()).append('|').append(event.getSimulationTime()).append('|')
                    .append(event.getType()).append('|').append(event.getJobId()).append('|')
                    .append(event.getVmId()).append('|').append(event.getTaskIds()).append('|')
                    .append(attributes).append('\n');
        }
        return result.toString();
    }

    private static String logicalMetricsFingerprint(SimulationReport report) {
        SimulationMetrics metrics = report.getMetrics();
        StringBuilder result = new StringBuilder();
        result.append(metrics.getMakespanSeconds()).append('|')
                .append(metrics.getComputeJobOutcomeCount()).append('|')
                .append(metrics.getSuccessfulComputeJobOutcomeRate()).append('|')
                .append(metrics.getMeanComputeJobRunTimeSeconds()).append('|')
                .append(metrics.getMeanComputeReadyToDecisionDelaySeconds()).append('|')
                .append(metrics.getMeanComputeDecisionToStartDelaySeconds()).append('|')
                .append(metrics.getVmModeledBusyTimeCoefficientOfVariation()).append('|');
        for (SimulationMetrics.VmMetrics vm : metrics.getVmMetrics().values()) {
            result.append(vm.getVmId()).append('|').append(vm.getJobOutcomeCount()).append('|')
                    .append(vm.getReportedCpuTimeSeconds()).append('|')
                    .append(vm.getModeledBusyIntervalSeconds()).append('|')
                    .append(vm.getModeledIntervalUtilization()).append('|')
                    .append(vm.getReportedCpuTimeOverMakespan()).append('\n');
        }
        return result.toString();
    }
}
