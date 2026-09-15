package org.workflowsim.experiment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URL;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.List;
import org.cloudbus.cloudsim.Log;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.workflowsim.data.DataMovementModel;
import org.workflowsim.platform.PlatformProfile;
import org.workflowsim.platform.PlatformProfiles;
import org.workflowsim.rl.EarliestFinishGreedyPolicy;
import org.workflowsim.rl.RlEnvironment;
import org.workflowsim.rl.RlEpisodeResult;
import org.workflowsim.utils.Parameters.PlanningAlgorithm;
import org.workflowsim.utils.Parameters.SchedulingAlgorithm;
import org.workflowsim.utils.ReplicaCatalog;
import org.workflowsim.utils.SimulationConfig;

/**
 * 全平台健康矩阵（R1-R5 合并后的核心配置交叉验收）。
 *
 * <p>与单点复现测试不同，本测试把每个端到端结果都通过统一的报告不变量校验器
 * （完成性、指标内部自洽、任务区间划分、流时定义、VM 汇总闭合），确保新旧功能
 * 在同一口径下可用。覆盖交叉：全部在线调度器、全部受维护规划器（含 PSO 端到端）、
 * 三种数据移动模型、多工作流错峰到达 × 在线调度、RL 轨道 × 错峰到达。</p>
 */
class PlatformHealthMatrixIntegrationTest {

    @AfterEach
    void restoreLogging() {
        Log.enable();
    }

    @Test
    void everySupportedOnlineSchedulerIsHealthyOnADependentDag() throws Exception {
        Log.disable();
        for (SchedulingAlgorithm algorithm : Arrays.asList(
                SchedulingAlgorithm.FCFS,
                SchedulingAlgorithm.READY_BATCH_ROUNDROBIN,
                SchedulingAlgorithm.READY_BATCH_MCT,
                SchedulingAlgorithm.READY_BATCH_MINMIN,
                SchedulingAlgorithm.READY_BATCH_MAXMIN,
                SchedulingAlgorithm.DATA)) {
            SimulationConfig config = SimulationConfig
                    .builder(resourcePath("/dax/reproducibility-workflow.dax"), 3)
                    .schedulingAlgorithm(algorithm)
                    .fileSystem(algorithm == SchedulingAlgorithm.DATA
                            ? ReplicaCatalog.FileSystem.LOCAL
                            : ReplicaCatalog.FileSystem.SHARED)
                    .randomSeed(91L)
                    .build();
            SimulationReport report = new SimulationRunner().run(config,
                    PlatformProfiles.homogeneousLocal("health-online-" + algorithm, 3));
            assertReportHealthy(report, "online:" + algorithm);
        }
    }

    @Test
    void everySupportedIndependentPlannerIsHealthyIncludingPsoEndToEnd() throws Exception {
        Log.disable();
        for (PlanningAlgorithm planner : Arrays.asList(
                PlanningAlgorithm.RANDOM,
                PlanningAlgorithm.STATIC_OLB,
                PlanningAlgorithm.STATIC_MET,
                PlanningAlgorithm.STATIC_MCT,
                PlanningAlgorithm.STATIC_MINMIN,
                PlanningAlgorithm.STATIC_MAXMIN,
                PlanningAlgorithm.STATIC_SUFFERAGE,
                PlanningAlgorithm.STATIC_ROUND_ROBIN,
                PlanningAlgorithm.PSO)) {
            SimulationConfig config = SimulationConfig
                    .builder(resourcePath("/dax/independent-tasks.dax"), 2)
                    .planningAlgorithm(planner)
                    .schedulingAlgorithm(SchedulingAlgorithm.STATIC)
                    .randomSeed(91L)
                    .build();
            SimulationReport report = new SimulationRunner().run(config,
                    PlatformProfiles.homogeneousLocal("health-independent-" + planner, 2));
            assertReportHealthy(report, "independent:" + planner);
        }
    }

    @Test
    void everyMaintainedDagPlannerIsHealthyOnItsModel() throws Exception {
        Log.disable();
        // LOCAL 家族：LOCAL 文件系统 + 执行前传输延迟模型（规划 AST 与运行期位对齐）。
        for (PlanningAlgorithm planner : Arrays.asList(
                PlanningAlgorithm.LOCAL_HEFT,
                PlanningAlgorithm.LOCAL_CPOP)) {
            SimulationConfig config = SimulationConfig
                    .builder(resourcePath("/dax/heft-paper-example.dax"), 3)
                    .planningAlgorithm(planner)
                    .schedulingAlgorithm(SchedulingAlgorithm.STATIC)
                    .fileSystem(ReplicaCatalog.FileSystem.LOCAL)
                    .dataMovementModel(DataMovementModel.preExecutionTransferDelayV1())
                    .randomSeed(91L)
                    .build();
            SimulationReport report = new SimulationRunner().run(config, paperPlatform());
            assertReportHealthy(report, "dag-local:" + planner);
        }
        // RANDOM：遗留数据移动模型（SHARED 默认）。
        {
            SimulationConfig config = SimulationConfig
                    .builder(resourcePath("/dax/heft-paper-example.dax"), 3)
                    .planningAlgorithm(PlanningAlgorithm.RANDOM)
                    .schedulingAlgorithm(SchedulingAlgorithm.STATIC)
                    .randomSeed(91L)
                    .build();
            SimulationReport report = new SimulationRunner().run(config, paperPlatform());
            assertReportHealthy(report, "dag-legacy:RANDOM");
        }
        // SHARED_STORAGE 家族：模型前提要求遗留数据移动模型（规划/执行传输估计位对齐）。
        for (PlanningAlgorithm planner : Arrays.asList(
                PlanningAlgorithm.SHARED_STORAGE_HEFT,
                PlanningAlgorithm.SHARED_STORAGE_CPOP,
                PlanningAlgorithm.SHARED_STORAGE_DLS,
                PlanningAlgorithm.SHARED_STORAGE_ETF,
                PlanningAlgorithm.SHARED_STORAGE_PEFT)) {
            SimulationConfig config = SimulationConfig
                    .builder(resourcePath("/dax/p7-shared-storage.dax"), 2)
                    .planningAlgorithm(planner)
                    .schedulingAlgorithm(SchedulingAlgorithm.STATIC)
                    .randomSeed(91L)
                    .build();
            SimulationReport report = new SimulationRunner().run(config, sharedStoragePlatform());
            assertReportHealthy(report, "dag-shared:" + planner);
        }
    }

    @Test
    void localHeftIsHealthyUnderBothExplicitTransferModels() throws Exception {
        Log.disable();
        for (DataMovementModel movement : Arrays.asList(
                DataMovementModel.preExecutionTransferDelayV1(),
                DataMovementModel.preExecutionTransferDelayWithContentionV1())) {
            SimulationConfig config = SimulationConfig
                    .builder(resourcePath("/dax/heft-paper-example.dax"), 3)
                    .planningAlgorithm(PlanningAlgorithm.LOCAL_HEFT)
                    .schedulingAlgorithm(SchedulingAlgorithm.STATIC)
                    .fileSystem(ReplicaCatalog.FileSystem.LOCAL)
                    .dataMovementModel(movement)
                    .randomSeed(91L)
                    .build();
            SimulationReport report = new SimulationRunner().run(config, paperPlatform());
            assertReportHealthy(report, "local-heft:" + movement.getKind());
        }
    }

    @Test
    void everyOnlineSchedulerIsHealthyUnderStaggeredArrival() throws Exception {
        Log.disable();
        for (SchedulingAlgorithm algorithm : Arrays.asList(
                SchedulingAlgorithm.FCFS,
                SchedulingAlgorithm.READY_BATCH_ROUNDROBIN,
                SchedulingAlgorithm.READY_BATCH_MCT,
                SchedulingAlgorithm.READY_BATCH_MINMIN,
                SchedulingAlgorithm.READY_BATCH_MAXMIN,
                SchedulingAlgorithm.DATA)) {
            SimulationConfig config = SimulationConfig
                    .builder(Arrays.asList(resourcePath("/dax/heft-paper-example.dax"),
                            resourcePath("/dax/heft-paper-example.dax")), 3)
                    .schedulingAlgorithm(algorithm)
                    .fileSystem(algorithm == SchedulingAlgorithm.DATA
                            ? ReplicaCatalog.FileSystem.LOCAL
                            : ReplicaCatalog.FileSystem.SHARED)
                    .workflowArrivalSeconds(Arrays.asList(0.0, 2000.0))
                    .randomSeed(91L)
                    .build();
            SimulationReport report = new SimulationRunner().run(config, paperPlatform());
            assertReportHealthy(report, "arrival:" + algorithm);
            assertArrivalGated(report, 2000.0, "arrival:" + algorithm);
        }
    }

    @Test
    void rlPolicyEpisodeIsHealthyUnderStaggeredArrival() throws Exception {
        Log.disable();
        SimulationConfig config = SimulationConfig
                .builder(Arrays.asList(resourcePath("/dax/heft-paper-example.dax"),
                        resourcePath("/dax/heft-paper-example.dax")), 3)
                .schedulingAlgorithm(SchedulingAlgorithm.RL_POLICY)
                .workflowArrivalSeconds(Arrays.asList(0.0, 2000.0))
                .build();
        RlEpisodeResult episode = new RlEnvironment().runEpisode(config, paperPlatform(),
                new EarliestFinishGreedyPolicy());
        SimulationReport report = episode.getReport();
        assertReportHealthy(report, "arrival:RL_POLICY");
        assertArrivalGated(report, 2000.0, "arrival:RL_POLICY");
        assertEquals(report.getTotalJobs(), episode.getDecisions().size(),
                "RL 决策轨迹必须覆盖每个 Job");
        assertEquals(-episode.getMakespan(), episode.getReward(), 0.0,
                "RL 奖励契约 reward = −makespan");
    }

    /**
     * 统一的报告不变量校验器：完成性 + 指标内部自洽 + 任务区间划分 + 流时定义
     * + VM 汇总闭合。任何一处破坏都说明对应配置组合下平台行为异常。
     */
    private static void assertReportHealthy(SimulationReport report, String label) {
        assertTrue(report.getMakespan() > 0.0 && Double.isFinite(report.getMakespan()),
                label + ": makespan 必须为正的有限值");
        assertEquals(report.getTotalJobs(), report.getSuccessfulJobs(),
                label + ": 全部 Job 必须成功");
        assertTrue(report.getMetrics().isAllLogicalTasksCompletedSuccessfully(),
                label + ": 全部逻辑任务必须成功完成");
        assertEquals("COMPLETED_SUCCESSFULLY",
                report.getMetrics().getLogicalTaskCompletionStatus(),
                label + ": 逻辑完成状态");
        assertTrue(report.getMetrics().getMeanComputeJobRunTimeSeconds() > 0.0,
                label + ": 平均计算运行时间必须为正");
        assertTrue(report.getMetrics().getMeanJobVmQueueWaitingTimeSeconds() >= 0.0,
                label + ": 平均排队等待时间必须非负");
        for (SimulationReport.TaskOutcome task : report.getTasks()) {
            assertTrue(task.getStartTime() >= 0.0
                    && task.getFinishTime() >= task.getStartTime(),
                    label + ": 任务 " + task.getTaskId() + " 时序必须自洽");
        }
        int vmJobTotal = 0;
        for (SimulationReport.VmSummary summary : report.getVmSummaries().values()) {
            vmJobTotal += summary.getJobs();
        }
        assertEquals(report.getTotalJobs(), vmJobTotal,
                label + ": VM 汇总 Job 数必须闭合到总 Job 数");
        int taskTotal = 0;
        for (SimulationReport.WorkflowOutcome outcome : report.getWorkflowOutcomes()) {
            assertTrue(outcome.getTaskCount() >= 1, label + ": 每个工作流至少一个任务");
            assertTrue(outcome.getLastTaskId() >= outcome.getFirstTaskId(),
                    label + ": 任务编号区间必须有效");
            assertTrue(outcome.getLastSuccessFinishSecond() >= outcome.getArrivalSecond(),
                    label + ": 工作流 " + outcome.getIndex() + " 完成时刻不得早于提交时刻");
            assertEquals(outcome.getLastSuccessFinishSecond() - outcome.getArrivalSecond(),
                    outcome.getFlowTimeSeconds(), 0.0,
                    label + ": 流时定义（完成 − 提交）");
            taskTotal += outcome.getTaskCount();
        }
        assertEquals(report.getMetrics().getLogicalTaskCount(), taskTotal,
                label + ": 工作流任务区间之和必须等于逻辑任务总数");
        assertTrue(report.getWorkflowOutcomes().size()
                == report.getConfig().getWorkflowPaths().size(),
                label + ": 每工作流结果数必须等于输入数");
    }

    /** 错峰到达门控：晚到工作流的任务不得在其提交时刻之前开始。 */
    private static void assertArrivalGated(SimulationReport report, double arrivalSecond,
            String label) {
        for (SimulationReport.WorkflowOutcome workflow : report.getWorkflowOutcomes()) {
            if (workflow.getArrivalSecond() < arrivalSecond) {
                continue;
            }
            for (SimulationReport.TaskOutcome task : report.getTasks()) {
                if (task.getTaskId() >= workflow.getFirstTaskId()
                        && task.getTaskId() <= workflow.getLastTaskId()) {
                    assertTrue(task.getStartTime() >= arrivalSecond,
                            label + ": 任务 " + task.getTaskId() + " 在提交时刻前开始 "
                                    + task.getStartTime());
                }
            }
        }
    }

    /** 与 HEFT 论文复现同构的平台：3 台 VM，mips=1.0。 */
    private static PlatformProfile paperPlatform() {
        PlatformProfile.Builder builder = PlatformProfile.builder("health-paper-platform");
        for (int id = 0; id < 3; id++) {
            builder.addHost(new PlatformProfile.HostSpec(id, 2, 2.0, 2048, 10_000L, 1_000_000L));
            builder.addVm(new PlatformProfile.VmSpec(id, 1.0, 1, 512, 1L, 10_000L, "Xen",
                    PlatformProfile.CloudletSchedulerMode.SPACE_SHARED));
        }
        return builder.build();
    }

    /** 共享存储平台：供 SHARED_STORAGE_* 家族使用。 */
    private static PlatformProfile sharedStoragePlatform() {
        PlatformProfile.Builder builder = PlatformProfile.builder("health-shared-storage-platform");
        for (int id = 0; id < 2; id++) {
            builder.addHost(new PlatformProfile.HostSpec(id, 2, 1000.0, 2048, 10_000L, 1_000_000L));
            builder.addVm(new PlatformProfile.VmSpec(id, 1000.0, 1, 512, 1000L, 10_000L, "Xen",
                    PlatformProfile.CloudletSchedulerMode.SPACE_SHARED));
        }
        builder.storage(new PlatformProfile.StorageSpec(1_000_000_000L, 10));
        return builder.build();
    }

    private static String resourcePath(String resource) throws Exception {
        URL url = PlatformHealthMatrixIntegrationTest.class.getResource(resource);
        if (url == null) {
            throw new IllegalStateException("Missing test resource " + resource);
        }
        return Paths.get(url.toURI()).toString();
    }
}
