package org.workflowsim.experiment;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Collections;
import org.cloudbus.cloudsim.Cloudlet;
import org.junit.jupiter.api.Test;
import org.workflowsim.utils.Parameters;

/**
 * 验证作业 VM 队列等待时间/响应时间/减速比指标的计算正确性。
 *
 * <p>时间线模型（VM 队列等待）：
 * <pre>
 * submissionTime → startTime → finishTime
 *       |&lt;-vmQueue-&gt;|&lt;-execution-&gt;|
 *       |&lt;------response--------&gt;|
 * </pre>
 * <p><strong>注意：</strong>此测试验证的是 VM 队列等待时间
 * ({@code getMeanJobVmQueueWaitingTimeSeconds()})，在 WorkflowSim 中通常为 0。
 * 真实调度等待时间由 {@code getMeanComputeTotalWaitingTimeSeconds()} 测量，
 * 需要 JOB_READY 事件支持。
 */
class JobWaitingTimeMetricsTest {

    @Test
    void jobOutcomeDerivesWaitingExecutionAndResponseTimes() {
        // 提交=2.0，开始=5.0，完成=15.0
        SimulationReport.JobOutcome job = job(1, 2.0, 5.0, 15.0);

        assertEquals(2.0, job.getSubmissionTime(), 1e-9);
        assertEquals(3.0, job.getWaitingTime(), 1e-9);     // 5 - 2
        assertEquals(10.0, job.getExecutionTime(), 1e-9);  // 15 - 5
        assertEquals(13.0, job.getResponseTime(), 1e-9);   // 15 - 2
    }

    @Test
    void negativeIntervalsAreClampedToZeroForIncompleteJobs() {
        // 异常时间戳（如失败作业 finishTime < startTime）不产生负值
        SimulationReport.JobOutcome job = job(1, 10.0, 5.0, 3.0);

        assertEquals(0.0, job.getWaitingTime(), 1e-9);
        assertEquals(0.0, job.getExecutionTime(), 1e-9);
        assertEquals(0.0, job.getResponseTime(), 1e-9);
    }

    @Test
    void zeroSubmissionTimeYieldsWaitingEqualToStartTime() {
        // submissionTime = 0（仿真开始即提交）
        SimulationReport.JobOutcome job = job(1, 0.0, 4.0, 9.0);

        assertEquals(4.0, job.getWaitingTime(), 1e-9);
        assertEquals(9.0, job.getResponseTime(), 1e-9);
    }

    @Test
    void metricsAggregateMeanWaitingResponseAndSlowdown() {
        // 作业 A：提交 0，开始 2，完成 6 → waiting=2, response=6, exec=4, slowdown=1.5
        // 作业 B：提交 1，开始 5,  完成 9 → waiting=4, response=8, exec=4, slowdown=2.0
        java.util.List<SimulationReport.JobOutcome> jobs = java.util.Arrays.asList(
                job(10, 0.0, 2.0, 6.0),
                job(11, 1.0, 5.0, 9.0));
        java.util.List<SimulationReport.TaskOutcome> tasks = java.util.Arrays.asList(
                task(1, 10, 0.0, 6.0), task(2, 11, 0.0, 9.0));
        java.util.List<org.workflowsim.Task> sourceTasks = java.util.Arrays.asList(
                new org.workflowsim.Task(1, 1000), new org.workflowsim.Task(2, 1000));

        SimulationMetrics metrics = SimulationMetrics.calculate(9.0, jobs, summaries(),
                Collections.<SimulationEvent>emptyList(), tasks, sourceTasks,
                config(), platform());

        assertEquals(3.0, metrics.getMeanJobVmQueueWaitingTimeSeconds(), 1e-9);   // (2+4)/2
        assertEquals(7.0, metrics.getMeanJobResponseTimeSeconds(), 1e-9);  // (6+8)/2
        assertEquals(1.75, metrics.getMeanJobVmLevelSlowdown(), 1e-9);            // (1.5+2.0)/2
    }

    @Test
    void slowdownExcludesJobsWithNearZeroExecutionTime() {
        // 作业 A：exec=4，slowdown=1.5；作业 B：exec=0（被排除）
        java.util.List<SimulationReport.JobOutcome> jobs = java.util.Arrays.asList(
                job(10, 0.0, 2.0, 6.0),
                job(11, 1.0, 5.0, 5.0));  // exec = 0
        java.util.List<SimulationReport.TaskOutcome> tasks = java.util.Arrays.asList(
                task(1, 10, 0.0, 6.0), task(2, 11, 0.0, 5.0));
        java.util.List<org.workflowsim.Task> sourceTasks = java.util.Arrays.asList(
                new org.workflowsim.Task(1, 1000), new org.workflowsim.Task(2, 1000));

        SimulationMetrics metrics = SimulationMetrics.calculate(6.0, jobs, summaries(),
                Collections.<SimulationEvent>emptyList(), tasks, sourceTasks,
                config(), platform());

        assertEquals(1.5, metrics.getMeanJobVmLevelSlowdown(), 1e-9);  // 仅作业 A
    }

    @Test
    void trueSlowdownUsesReadyTimeFromEvents() {
        // 作业 A：ready=0, start=3, finish=13 → wait=3, exec=10, boundedExec=10, trueSlowdown=(3+10)/10=1.3
        // 作业 B：ready=1, start=5, finish=9  → wait=4, exec=4,  boundedExec=10, trueSlowdown=(4+4)/10=0.8
        java.util.List<SimulationReport.JobOutcome> jobs = java.util.Arrays.asList(
                job(10, 2.0, 3.0, 13.0),  // submission=2（不影响 trueSlowdown）
                job(11, 4.0, 5.0, 9.0));
        java.util.List<SimulationEvent> events = java.util.Arrays.asList(
                event(SimulationEventType.JOB_READY, 0.0, 10),  // 作业 10 ready=0
                event(SimulationEventType.JOB_READY, 1.0, 11)); // 作业 11 ready=1
        java.util.List<SimulationReport.TaskOutcome> tasks = java.util.Arrays.asList(
                task(1, 10, 0.0, 13.0), task(2, 11, 0.0, 9.0));
        java.util.List<org.workflowsim.Task> sourceTasks = java.util.Arrays.asList(
                new org.workflowsim.Task(1, 1000), new org.workflowsim.Task(2, 1000));

        SimulationMetrics metrics = SimulationMetrics.calculate(13.0, jobs, summaries(),
                events, tasks, sourceTasks, config(), platform());

        // 作业 A：wait=3, exec=10, bounded=10 → (3+10)/10=1.3 → max(1.3, 1.0)=1.3
        // 作业 B：wait=4, exec=4,  bounded=10 → (4+4)/10=0.8  → max(0.8, 1.0)=1.0（下界钳制）
        assertEquals(1.15, metrics.getMeanComputeTrueSlowdown(), 1e-9);  // (1.3 + 1.0)/2
        assertEquals(2, metrics.getTrueSlowdownObservationCount());
    }

    @Test
    void trueSlowdownBoundsExecutionTimeToTenSeconds() {
        // 极短作业：ready=0, start=0.5, finish=0.6 → wait=0.5, exec=0.1, boundedExec=10
        // trueSlowdown = (0.5 + 0.1) / 10 = 0.06（而非 6.0，防止爆炸）
        java.util.List<SimulationReport.JobOutcome> jobs = java.util.Collections.singletonList(
                job(10, 0.4, 0.5, 0.6));
        java.util.List<SimulationEvent> events = java.util.Collections.singletonList(
                event(SimulationEventType.JOB_READY, 0.0, 10));
        java.util.List<SimulationReport.TaskOutcome> tasks = java.util.Collections.singletonList(
                task(1, 10, 0.0, 0.6));
        java.util.List<org.workflowsim.Task> sourceTasks = java.util.Collections.singletonList(
                new org.workflowsim.Task(1, 100));

        SimulationMetrics metrics = SimulationMetrics.calculate(0.6, jobs, summaries(),
                events, tasks, sourceTasks, config(), platform());

        // wait=0.5, exec=0.1, bounded=10 → (0.5+0.1)/10=0.06 → max(0.06, 1.0)=1.0（下界钳制）
        assertEquals(1.0, metrics.getMeanComputeTrueSlowdown(), 1e-9);
        assertEquals(1, metrics.getTrueSlowdownObservationCount());
    }

    @Test
    void trueSlowdownExcludesJobsWithoutReadyEvent() {
        // 作业 A 有 ready 事件，作业 B 没有（被排除）
        java.util.List<SimulationReport.JobOutcome> jobs = java.util.Arrays.asList(
                job(10, 0.0, 2.0, 6.0),
                job(11, 1.0, 5.0, 9.0));
        java.util.List<SimulationEvent> events = java.util.Collections.singletonList(
                event(SimulationEventType.JOB_READY, 0.0, 10));  // 仅作业 10
        java.util.List<SimulationReport.TaskOutcome> tasks = java.util.Arrays.asList(
                task(1, 10, 0.0, 6.0), task(2, 11, 0.0, 9.0));
        java.util.List<org.workflowsim.Task> sourceTasks = java.util.Arrays.asList(
                new org.workflowsim.Task(1, 1000), new org.workflowsim.Task(2, 1000));

        SimulationMetrics metrics = SimulationMetrics.calculate(9.0, jobs, summaries(),
                events, tasks, sourceTasks, config(), platform());

        // 作业 A: wait=2, exec=4, bounded=10 → (2+4)/10=0.6 → max(0.6, 1.0)=1.0（下界钳制）
        assertEquals(1.0, metrics.getMeanComputeTrueSlowdown(), 1e-9);
        assertEquals(1, metrics.getTrueSlowdownObservationCount());
    }

    private static SimulationEvent event(SimulationEventType type, double time, Integer jobId) {
        return event(1, time, type, jobId);
    }

    @Test
    void totalWaitingTimeIsComputedFromJobReadyEvents() {
        // 作业 A：ready=0.5，start=2.0 → totalWaiting=1.5
        // 作业 B：ready=1.0，start=5.0 → totalWaiting=4.0
        // 平均总等待时间 = (1.5 + 4.0) / 2 = 2.75
        java.util.List<SimulationReport.JobOutcome> jobs = java.util.Arrays.asList(
                job(10, 0.0, 2.0, 6.0),
                job(11, 1.0, 5.0, 9.0));
        java.util.List<SimulationReport.TaskOutcome> tasks = java.util.Arrays.asList(
                task(1, 10, 0.0, 6.0), task(2, 11, 0.0, 9.0));
        java.util.List<org.workflowsim.Task> sourceTasks = java.util.Arrays.asList(
                new org.workflowsim.Task(1, 1000), new org.workflowsim.Task(2, 1000));
        java.util.List<SimulationEvent> events = java.util.Arrays.asList(
                event(1, 0.5, SimulationEventType.JOB_READY, 10),
                event(2, 1.0, SimulationEventType.JOB_READY, 11));

        SimulationMetrics metrics = SimulationMetrics.calculate(9.0, jobs, summaries(),
                events, tasks, sourceTasks, config(), platform());

        assertEquals(2.75, metrics.getMeanComputeTotalWaitingTimeSeconds(), 1e-9);
    }

    @Test
    void totalWaitingTimeIgnoresJobsWithoutReadyEvent() {
        // 作业 A 有 ready 事件，作业 B 没有 → 仅统计作业 A
        java.util.List<SimulationReport.JobOutcome> jobs = java.util.Arrays.asList(
                job(10, 0.0, 2.0, 6.0),
                job(11, 1.0, 5.0, 9.0));
        java.util.List<SimulationReport.TaskOutcome> tasks = java.util.Arrays.asList(
                task(1, 10, 0.0, 6.0), task(2, 11, 0.0, 9.0));
        java.util.List<org.workflowsim.Task> sourceTasks = java.util.Arrays.asList(
                new org.workflowsim.Task(1, 1000), new org.workflowsim.Task(2, 1000));
        java.util.List<SimulationEvent> events = java.util.Arrays.asList(
                event(1, 0.5, SimulationEventType.JOB_READY, 10));  // 仅作业 10

        SimulationMetrics metrics = SimulationMetrics.calculate(9.0, jobs, summaries(),
                events, tasks, sourceTasks, config(), platform());

        assertEquals(1.5, metrics.getMeanComputeTotalWaitingTimeSeconds(), 1e-9);  // 2.0 - 0.5
    }

    // ── 指标增强：分布统计 / success-only / Jain 公平性 / 重试放大率 ──

    @Test
    void distributionStatsTrackWaitingAndSlowdownSamples() {
        // 作业 A：ready=0.5, start=2.0, finish=12.0 → wait=1.5, exec=10 → slowdown=(1.5+10)/10=1.15
        // 作业 B：ready=1.0, start=5.0, finish=9.0  → wait=4.0, exec=4  → slowdown=(4+4)/10=0.8→钳制1.0
        // 作业 C：ready=2.0, start=3.0, finish=23.0 → wait=1.0, exec=20 → slowdown=(1+20)/20=1.05
        java.util.List<SimulationReport.JobOutcome> jobs = java.util.Arrays.asList(
                job(10, 0.0, 2.0, 12.0),
                job(11, 1.0, 5.0, 9.0),
                job(12, 1.5, 3.0, 23.0));
        java.util.List<SimulationEvent> events = java.util.Arrays.asList(
                event(1, 0.5, SimulationEventType.JOB_READY, 10),
                event(2, 1.0, SimulationEventType.JOB_READY, 11),
                event(3, 2.0, SimulationEventType.JOB_READY, 12));
        java.util.List<SimulationReport.TaskOutcome> tasks = java.util.Arrays.asList(
                task(1, 10, 0.0, 12.0), task(2, 11, 0.0, 9.0), task(3, 12, 0.0, 23.0));
        java.util.List<org.workflowsim.Task> sourceTasks = java.util.Arrays.asList(
                new org.workflowsim.Task(1, 1000), new org.workflowsim.Task(2, 1000),
                new org.workflowsim.Task(3, 1000));

        SimulationMetrics metrics = SimulationMetrics.calculate(23.0, jobs, summaries(),
                events, tasks, sourceTasks, config(), platform());

        // 等待样本排序后 [1.0, 1.5, 4.0]：中位数 rank=ceil(0.5·3)=2 → 1.5；P95 rank=3 → 4.0
        assertEquals(3, metrics.getTotalWaitingTimeObservationCount());
        assertEquals(1.5, metrics.getMedianComputeTotalWaitingTimeSeconds(), 1e-9);
        assertEquals(4.0, metrics.getP95ComputeTotalWaitingTimeSeconds(), 1e-9);
        assertEquals(4.0, metrics.getMaxComputeTotalWaitingTimeSeconds(), 1e-9);
        // 减速比样本排序后 [1.0, 1.05, 1.15]：中位数 1.05；P95/最大 1.15
        assertEquals(1.05, metrics.getMedianComputeTrueSlowdown(), 1e-9);
        assertEquals(1.15, metrics.getP95ComputeTrueSlowdown(), 1e-9);
        assertEquals(1.15, metrics.getMaxComputeTrueSlowdown(), 1e-9);
    }

    @Test
    void distributionStatsAreZeroWhenNoObservationsExist() {
        // 无 JOB_READY 事件 → 观测数为 0，分位数返回 0.0（与空集约定一致）
        java.util.List<SimulationReport.JobOutcome> jobs = java.util.Collections.singletonList(
                job(10, 0.0, 2.0, 6.0));
        java.util.List<SimulationReport.TaskOutcome> tasks = java.util.Collections.singletonList(
                task(1, 10, 0.0, 6.0));
        java.util.List<org.workflowsim.Task> sourceTasks = java.util.Collections.singletonList(
                new org.workflowsim.Task(1, 1000));

        SimulationMetrics metrics = SimulationMetrics.calculate(6.0, jobs, summaries(),
                Collections.<SimulationEvent>emptyList(), tasks, sourceTasks,
                config(), platform());

        assertEquals(0, metrics.getTotalWaitingTimeObservationCount());
        assertEquals(0.0, metrics.getMedianComputeTotalWaitingTimeSeconds(), 1e-9);
        assertEquals(0.0, metrics.getP95ComputeTotalWaitingTimeSeconds(), 1e-9);
        assertEquals(0.0, metrics.getMaxComputeTotalWaitingTimeSeconds(), 1e-9);
        assertEquals(0.0, metrics.getMedianComputeTrueSlowdown(), 1e-9);
        assertEquals(0.0, metrics.getP95ComputeTrueSlowdown(), 1e-9);
        assertEquals(0.0, metrics.getMaxComputeTrueSlowdown(), 1e-9);
        assertEquals(0, metrics.getSuccessOnlyWaitingObservationCount());
    }

    @Test
    void successOnlyVariantsExcludeFailedAttempts() {
        // 作业 A 成功 wait=1.5，作业 B 失败 wait=4.0。
        // 总体均值含失败尝试 = (1.5+4.0)/2 = 2.75；success-only = 1.5（n=1）。
        java.util.List<SimulationReport.JobOutcome> jobs = java.util.Arrays.asList(
                jobWithStatus(10, Cloudlet.SUCCESS, 0.0, 2.0, 12.0),
                jobWithStatus(11, Cloudlet.FAILED, 1.0, 5.0, 9.0));
        java.util.List<SimulationEvent> events = java.util.Arrays.asList(
                event(1, 0.5, SimulationEventType.JOB_READY, 10),
                event(2, 1.0, SimulationEventType.JOB_READY, 11));
        java.util.List<SimulationReport.TaskOutcome> tasks = java.util.Arrays.asList(
                task(1, 10, 0.0, 12.0), task(2, 11, 0.0, 9.0));
        java.util.List<org.workflowsim.Task> sourceTasks = java.util.Arrays.asList(
                new org.workflowsim.Task(1, 1000), new org.workflowsim.Task(2, 1000));

        SimulationMetrics metrics = SimulationMetrics.calculate(12.0, jobs, summaries(),
                events, tasks, sourceTasks, config(), platform());

        assertEquals(2.75, metrics.getMeanComputeTotalWaitingTimeSeconds(), 1e-9);
        assertEquals(1.5, metrics.getSuccessOnlyMeanComputeTotalWaitingTimeSeconds(), 1e-9);
        assertEquals(1, metrics.getSuccessOnlyWaitingObservationCount());
        // success-only 减速比仅含作业 A：(1.5+10)/10 = 1.15
        assertEquals(1.15, metrics.getSuccessOnlyMeanComputeTrueSlowdown(), 1e-9);
    }

    @Test
    void jainFairnessIndexCapturesVmUtilizationImbalance() {
        // VM0 繁忙 [0,10]（利用率 1.0），VM1 繁忙 [0,5]（利用率 0.5），makespan=10。
        // Jain = (1.0+0.5)² / (2·(1.0²+0.5²)) = 2.25/2.5 = 0.9
        java.util.Map<Integer, SimulationReport.VmSummary> summaries =
                new java.util.LinkedHashMap<Integer, SimulationReport.VmSummary>();
        summaries.put(0, new SimulationReport.VmSummary(0, 1, 1, 0, 10.0, 10.0));
        summaries.put(1, new SimulationReport.VmSummary(1, 1, 1, 0, 5.0, 5.0));
        java.util.List<SimulationReport.JobOutcome> jobs = java.util.Arrays.asList(
                jobOnVm(10, 0, 0.0, 10.0),
                jobOnVm(11, 1, 0.0, 5.0));
        java.util.List<SimulationReport.TaskOutcome> tasks = java.util.Arrays.asList(
                task(1, 10, 0.0, 10.0), task(2, 11, 0.0, 5.0));
        java.util.List<org.workflowsim.Task> sourceTasks = java.util.Arrays.asList(
                new org.workflowsim.Task(1, 1000), new org.workflowsim.Task(2, 1000));

        SimulationMetrics metrics = SimulationMetrics.calculate(10.0, jobs, summaries,
                Collections.<SimulationEvent>emptyList(), tasks, sourceTasks,
                config(), platform());

        assertEquals(0.9, metrics.getVmUtilizationJainFairnessIndex(), 1e-9);
        assertEquals(0.75, metrics.getMeanVmModeledIntervalUtilization(), 1e-9);
    }

    @Test
    void retryAmplificationRatioCountsAllAttemptsPerLogicalTask() {
        // 逻辑任务 1：首次失败（job 10）+ 再次尝试（job 11 成功）= 2 次尝试；
        // 逻辑任务 2：一次成功（job 12）。放大率 = 3/2 = 1.5。
        java.util.List<SimulationReport.JobOutcome> jobs = java.util.Arrays.asList(
                jobWithStatus(10, Cloudlet.FAILED, 0.0, 1.0, 3.0),
                jobWithStatus(11, Cloudlet.SUCCESS, 3.0, 4.0, 6.0),
                jobWithStatus(12, Cloudlet.SUCCESS, 0.0, 2.0, 5.0));
        java.util.List<SimulationReport.TaskOutcome> tasks = java.util.Arrays.asList(
                new SimulationReport.TaskOutcome(1, 10, 0, Cloudlet.FAILED,
                        Cloudlet.FAILED, 0, 1000L, 1.0, 3.0),
                new SimulationReport.TaskOutcome(1, 11, 0, Cloudlet.SUCCESS,
                        Cloudlet.SUCCESS, 0, 1000L, 4.0, 6.0),
                new SimulationReport.TaskOutcome(2, 12, 0, Cloudlet.SUCCESS,
                        Cloudlet.SUCCESS, 0, 1000L, 2.0, 5.0));
        java.util.List<org.workflowsim.Task> sourceTasks = java.util.Arrays.asList(
                new org.workflowsim.Task(1, 1000), new org.workflowsim.Task(2, 1000));

        SimulationMetrics metrics = SimulationMetrics.calculate(6.0, jobs, summaries(),
                Collections.<SimulationEvent>emptyList(), tasks, sourceTasks,
                config(), platform());

        assertEquals(1.5, metrics.getRetryAmplificationRatio(), 1e-9);
    }

    @Test
    void retryAmplificationRatioIsOneWithoutFailures() {
        java.util.List<SimulationReport.JobOutcome> jobs = java.util.Arrays.asList(
                job(10, 0.0, 2.0, 6.0), job(11, 1.0, 5.0, 9.0));
        java.util.List<SimulationReport.TaskOutcome> tasks = java.util.Arrays.asList(
                task(1, 10, 0.0, 6.0), task(2, 11, 0.0, 9.0));
        java.util.List<org.workflowsim.Task> sourceTasks = java.util.Arrays.asList(
                new org.workflowsim.Task(1, 1000), new org.workflowsim.Task(2, 1000));

        SimulationMetrics metrics = SimulationMetrics.calculate(9.0, jobs, summaries(),
                Collections.<SimulationEvent>emptyList(), tasks, sourceTasks,
                config(), platform());

        assertEquals(1.0, metrics.getRetryAmplificationRatio(), 1e-9);
    }

    // ── 测试辅助 ──

    private static SimulationReport.JobOutcome jobWithStatus(int id, int status,
            double submission, double start, double finish) {
        return new SimulationReport.JobOutcome(id, 0, status,
                Parameters.ClassType.COMPUTE.value, submission, start, finish,
                Math.max(0.0, finish - start), 1.0, 1, Collections.singletonList(id * 100 + 1));
    }

    private static SimulationReport.JobOutcome jobOnVm(int id, int vmId, double start,
            double finish) {
        return new SimulationReport.JobOutcome(id, vmId, Cloudlet.SUCCESS,
                Parameters.ClassType.COMPUTE.value, 0.0, start, finish,
                Math.max(0.0, finish - start), 1.0, 1, Collections.singletonList(id * 100 + 1));
    }

    private static SimulationEvent event(int seq, double time, SimulationEventType type,
            Integer jobId) {
        return new SimulationEvent(seq, time, type, jobId, null, null,
                Collections.<Integer>emptyList(), Collections.<String, Object>emptyMap());
    }

    private static SimulationReport.JobOutcome job(int id, double submission, double start,
            double finish) {
        return new SimulationReport.JobOutcome(id, 0, Cloudlet.SUCCESS,
                Parameters.ClassType.COMPUTE.value, submission, start, finish,
                Math.max(0.0, finish - start), 1.0, 1, Collections.singletonList(id * 100 + 1));
    }

    private static SimulationReport.TaskOutcome task(int taskId, int jobId, double start,
            double finish) {
        return new SimulationReport.TaskOutcome(taskId, jobId, 0, Cloudlet.SUCCESS,
                Cloudlet.SUCCESS, 0, 1000L, start, finish);
    }

    private static java.util.Map<Integer, SimulationReport.VmSummary> summaries() {
        return Collections.emptyMap();
    }

    private static org.workflowsim.utils.SimulationConfig config() {
        return org.workflowsim.utils.SimulationConfig.builder("test.dax", 1).build();
    }

    private static org.workflowsim.platform.PlatformProfile platform() {
        return org.workflowsim.platform.PlatformProfiles.homogeneousLocal("test", 1);
    }
}
