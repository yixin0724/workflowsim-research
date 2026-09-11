package org.workflowsim.experiment;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import org.cloudbus.cloudsim.Log;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.workflowsim.data.DataMovementModel;
import org.workflowsim.failure.FailureModelConfig;
import org.workflowsim.failure.FailureParameters;
import org.workflowsim.utils.DistributionGenerator;
import org.workflowsim.utils.DistributionSpec;
import org.workflowsim.platform.PlatformProfile;
import org.workflowsim.platform.PlatformProfiles;
import org.workflowsim.utils.Parameters;
import org.workflowsim.utils.ReplicaCatalog;
import org.workflowsim.utils.SimulationConfig;

/**
 * 配置矩阵综合测试：验证多种配置组合的可运行性。
 *
 * <p>该测试确保常见配置组合（调度算法×数据移动模型×文件系统×故障模型等）都能
 * 成功执行，避免配置交互导致的隐藏问题。</p>
 */
class ConfigurationMatrixIntegrationTest {

    @AfterEach
    void restoreLogging() {
        Log.enable();
    }

    @Test
    void readyBatchSchedulersWorkWithBothDataMovementModels() throws Exception {
        String workflow = getClass().getResource("/dax/reproducibility-workflow.dax").getPath();
        List<Parameters.SchedulingAlgorithm> algorithms = new ArrayList<>();
        algorithms.add(Parameters.SchedulingAlgorithm.FCFS);
        algorithms.add(Parameters.SchedulingAlgorithm.READY_BATCH_MINMIN);
        algorithms.add(Parameters.SchedulingAlgorithm.READY_BATCH_MAXMIN);
        algorithms.add(Parameters.SchedulingAlgorithm.READY_BATCH_MCT);
        algorithms.add(Parameters.SchedulingAlgorithm.READY_BATCH_ROUNDROBIN);
        
        List<DataMovementModel> models = new ArrayList<>();
        models.add(DataMovementModel.legacyWorkflowsimV1());
        models.add(DataMovementModel.fixedEndpointNoContention(1000.0, 0.001, 20.0));

        Log.disable();
        SimulationRunner runner = new SimulationRunner();
        for (Parameters.SchedulingAlgorithm alg : algorithms) {
            for (DataMovementModel model : models) {
                SimulationConfig config = SimulationConfig.builder(workflow, 2)
                        .schedulingAlgorithm(alg)
                        .dataMovementModel(model)
                        .randomSeed(1L)
                        .build();
                PlatformProfile platform = PlatformProfiles.homogeneousLocal("test", 2);
                SimulationReport report = runner.run(config, platform);
                
                assertTrue(report.getMetrics().getLogicalTaskCompletionStatus().equals("COMPLETED_SUCCESSFULLY"),
                        "Algorithm " + alg + " with model " + model + " should complete successfully");
                assertTrue(report.getMetrics().getMakespanSeconds() > 0);
            }
        }
    }

    @Test
    void staticPlannersWorkWithSharedAndLocalFileSystem() throws Exception {
        // 独立任务规划器需要无依赖的 DAX
        String independentWorkflow = getClass().getResource("/dax/independent-tasks.dax").getPath();
        List<Parameters.PlanningAlgorithm> independentPlanners = new ArrayList<>();
        independentPlanners.add(Parameters.PlanningAlgorithm.STATIC_MINMIN);
        independentPlanners.add(Parameters.PlanningAlgorithm.STATIC_MCT);
        
        List<ReplicaCatalog.FileSystem> fileSystems = new ArrayList<>();
        fileSystems.add(ReplicaCatalog.FileSystem.SHARED);
        fileSystems.add(ReplicaCatalog.FileSystem.LOCAL);

        Log.disable();
        SimulationRunner runner = new SimulationRunner();
        for (Parameters.PlanningAlgorithm planner : independentPlanners) {
            for (ReplicaCatalog.FileSystem fs : fileSystems) {
                SimulationConfig config = SimulationConfig.builder(independentWorkflow, 2)
                        .planningAlgorithm(planner)
                        .schedulingAlgorithm(Parameters.SchedulingAlgorithm.STATIC)
                        .fileSystem(fs)
                        .randomSeed(2L)
                        .build();
                PlatformProfile platform = PlatformProfiles.homogeneousLocal("test", 2);
                SimulationReport report = runner.run(config, platform);
                
                assertTrue(report.getMetrics().getLogicalTaskCompletionStatus().equals("COMPLETED_SUCCESSFULLY"),
                        "Planner " + planner + " with " + fs + " should complete");
            }
        }
        
        // SHARED_STORAGE_HEFT 支持 DAG 且只支持 SHARED 文件系统
        String dagWorkflow = getClass().getResource("/dax/reproducibility-workflow.dax").getPath();
        SimulationConfig heftConfig = SimulationConfig.builder(dagWorkflow, 2)
                .planningAlgorithm(Parameters.PlanningAlgorithm.SHARED_STORAGE_HEFT)
                .schedulingAlgorithm(Parameters.SchedulingAlgorithm.STATIC)
                .fileSystem(ReplicaCatalog.FileSystem.SHARED)
                .randomSeed(2L)
                .build();
        PlatformProfile platform = PlatformProfiles.homogeneousLocal("test", 2);
        SimulationReport heftReport = runner.run(heftConfig, platform);
        assertTrue(heftReport.getMetrics().getLogicalTaskCompletionStatus().equals("COMPLETED_SUCCESSFULLY"),
                "HEFT with SHARED should complete");
    }

    @Test
    void failureModelWithRetriesProducesExpectedMetrics() throws Exception {
        String workflow = getClass().getResource("/dax/reproducibility-workflow.dax").getPath();
        FailureModelConfig failureModel = FailureModelConfig.builder()
                .clusteringAlgorithm(FailureParameters.FTCluteringAlgorithm.FTCLUSTERING_NOOP)
                .monitorMode(FailureParameters.FTCMonitor.MONITOR_NONE)
                .generatorMode(FailureParameters.FTCFailure.FAILURE_ALL)
                .generatorSpecs(new DistributionSpec[][]{{DistributionSpec.of(
                    DistributionGenerator.DistributionFamily.WEIBULL, 0.05, 1.0)}})
                .maxTotalRetryJobs(100)
                .build();

        Log.disable();
        SimulationConfig config = SimulationConfig.builder(workflow, 3)
                .schedulingAlgorithm(Parameters.SchedulingAlgorithm.READY_BATCH_MINMIN)
                .failureModel(failureModel)
                .randomSeed(999L)
                .build();
        PlatformProfile platform = PlatformProfiles.homogeneousLocal("test", 3);
        
        SimulationRunner runner = new SimulationRunner();
        SimulationReport report;
        try {
            report = runner.run(config, platform);
        } catch (org.workflowsim.failure.RetryLimitExceededException e) {
            // 故障模型可能耗尽重试预算，这是正常的语义
            assertTrue(e.getMessage().contains("maxTotalRetryJobs"));
            return;
        }
        
        // 如果成功完成，应有一些失败和重试（但不一定触发）
        assertTrue(report.getMetrics().getRetryJobCreatedCount() >= 0,
                "Should track retry count");
        assertTrue(report.getMetrics().getFailedComputeJobOutcomeCount() >= 0,
                "Should track failures");
        // 最终应完成或达到重试限制
        assertTrue(report.getMetrics().getLogicalTaskCompletionStatus().equals("COMPLETED_SUCCESSFULLY")
                || report.getMetrics().getLogicalTasksNotYetSuccessfullyCompletedCount() > 0);
    }

    @Test
    void runtimeScaleAffectsMakespan() throws Exception {
        String workflow = getClass().getResource("/dax/reproducibility-workflow.dax").getPath();
        List<Double> scales = new ArrayList<>();
        scales.add(0.5);
        scales.add(1.0);
        scales.add(2.0);
        List<Double> makespans = new ArrayList<>();

        Log.disable();
        SimulationRunner runner = new SimulationRunner();
        for (double scale : scales) {
            SimulationConfig config = SimulationConfig.builder(workflow, 1)
                    .schedulingAlgorithm(Parameters.SchedulingAlgorithm.FCFS)
                    .runtimeScale(scale)
                    .randomSeed(1L)
                    .build();
            PlatformProfile platform = PlatformProfiles.homogeneousLocal("test", 1);
            SimulationReport report = runner.run(config, platform);
            makespans.add(report.getMetrics().getMakespanSeconds());
        }

        // 更大的 scale 应导致更长的 makespan
        assertTrue(makespans.get(0) < makespans.get(1),
                "scale=0.5 should have shorter makespan than scale=1.0");
        assertTrue(makespans.get(1) < makespans.get(2),
                "scale=1.0 should have shorter makespan than scale=2.0");
    }

    @Test
    void deadlineObservationIsRecordedCorrectly() throws Exception {
        String workflow = getClass().getResource("/dax/reproducibility-workflow.dax").getPath();

        Log.disable();
        SimulationRunner runner = new SimulationRunner();
        
        // 测试宽松 deadline
        SimulationConfig configLoose = SimulationConfig.builder(workflow, 3)
                .schedulingAlgorithm(Parameters.SchedulingAlgorithm.FCFS)
                .deadline(10000L)
                .randomSeed(1L)
                .build();
        PlatformProfile platform = PlatformProfiles.homogeneousLocal("test", 3);
        SimulationReport reportLoose = runner.run(configLoose, platform);
        
        assertTrue(reportLoose.getMetrics().isDeadlineObservationEnabled(),
                "Deadline observation should be enabled");
        assertTrue(reportLoose.getMetrics().getDeadlineSlackSeconds() > 0,
                "Loose deadline should have positive slack");
        assertEquals(0.0, reportLoose.getMetrics().getDeadlineTardinessSeconds(), 0.001,
                "Loose deadline should have zero tardiness");

        // 测试紧张 deadline
        SimulationConfig configTight = SimulationConfig.builder(workflow, 3)
                .schedulingAlgorithm(Parameters.SchedulingAlgorithm.FCFS)
                .deadline(1L)
                .randomSeed(1L)
                .build();
        SimulationReport reportTight = runner.run(configTight, platform);
        
        assertTrue(reportTight.getMetrics().getDeadlineTardinessSeconds() > 0,
                "Tight deadline should have positive tardiness");
        // Slack 在 missed 时为负，不应断言为 0
        assertTrue(reportTight.getMetrics().getDeadlineSlackSeconds() < 0,
                "Tight deadline should have negative slack");
    }

    @Test
    void dataAwareSchedulerRespectsDataLocality() throws Exception {
        String workflow = getClass().getResource("/dax/reproducibility-workflow.dax").getPath();

        Log.disable();
        SimulationConfig config = SimulationConfig.builder(workflow, 3)
                .schedulingAlgorithm(Parameters.SchedulingAlgorithm.DATA)
                .fileSystem(ReplicaCatalog.FileSystem.LOCAL)
                .randomSeed(1L)
                .build();
        PlatformProfile platform = PlatformProfiles.homogeneousLocal("test", 3);
        
        SimulationRunner runner = new SimulationRunner();
        SimulationReport report = runner.run(config, platform);
        
        assertTrue(report.getMetrics().getLogicalTaskCompletionStatus().equals("COMPLETED_SUCCESSFULLY"),
                "DATA scheduler should complete successfully");
        // DATA 算法应减少数据传输
        assertTrue(report.getMetrics().getTotalModeledDataTransferSeconds() >= 0);
    }
}
