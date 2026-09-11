package org.workflowsim.experiment;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.cloudbus.cloudsim.Cloudlet;
import org.workflowsim.Task;
import org.workflowsim.platform.PlatformProfile;
import org.workflowsim.utils.Parameters;
import org.workflowsim.utils.SimulationConfig;

/**
 * 从不可变 {@link SimulationReport} 推导出的、名称明确的模型层指标。
 * 这些值描述的是本次模拟器运行，不是生产工作流执行的实测数据。
 */
public final class SimulationMetrics {

    private final double makespanSeconds;
    private final Double logicalTaskCompletionSeconds;
    private final String logicalTaskCompletionStatus;
    private final Double terminalLifecycleTailSeconds;
    private final int jobOutcomeCount;
    private final int computeJobOutcomeCount;
    private final int stageInJobOutcomeCount;
    private final int successfulJobOutcomeCount;
    private final int failedJobOutcomeCount;
    private final int successfulComputeJobOutcomeCount;
    private final int failedComputeJobOutcomeCount;
    private final double successfulComputeJobOutcomeRate;
    private final int logicalTaskCount;
    private final int successfullyCompletedLogicalTaskCount;
    private final int logicalTasksNotYetSuccessfullyCompletedCount;
    private final double successfulLogicalTaskCompletionRate;
    private final boolean allLogicalTasksCompletedSuccessfully;
    private final int initialComputeJobOutcomeCount;
    private final int retryJobCreatedCount;
    private final int completedRetryComputeJobOutcomeCount;
    private final int logicalTaskAttemptCount;
    private final int retriedLogicalTaskCount;
    private final double failedComputeAttemptEnvelopeSeconds;
    private final double failedComputeAttemptModeledProcessingCost;
    private final double retryComputeAttemptModeledProcessingCost;
    private final double computeJobOutcomeThroughputPerSecond;
    private final double meanComputeJobRunTimeSeconds;
    private final double meanJobVmQueueWaitingTimeSeconds;
    private final double meanJobResponseTimeSeconds;
    private final double meanJobVmLevelSlowdown;
    /** VM 层面减速比的样本观测数（仅 executionTime > 0.001s 的作业；与均值同集合）。 */
    private final int vmLevelSlowdownObservationCount;
    private final double meanComputeTotalWaitingTimeSeconds;
    private final double meanComputeTrueSlowdown;
    private final int trueSlowdownObservationCount;
    /** 总等待时间的观测数（与真实减速比观测数同口径：就绪事件齐备的计算作业）。 */
    private final int totalWaitingTimeObservationCount;
    /** 总等待时间中位数（秒）；分布尾部统计，调度算法差异常体现在尾部而非均值。 */
    private final double medianComputeTotalWaitingTimeSeconds;
    /** 总等待时间 P95（秒，最近秩法）。 */
    private final double p95ComputeTotalWaitingTimeSeconds;
    /** 总等待时间最大值（秒）。 */
    private final double maxComputeTotalWaitingTimeSeconds;
    /** 真实减速比中位数；分布尾部统计。 */
    private final double medianComputeTrueSlowdown;
    /** 真实减速比 P95（最近秩法）。 */
    private final double p95ComputeTrueSlowdown;
    /** 真实减速比最大值。 */
    private final double maxComputeTrueSlowdown;
    /** 仅成功作业的总等待时间均值（秒）；剔除失败尝试后的无偏口径。 */
    private final double successOnlyMeanComputeTotalWaitingTimeSeconds;
    /** 仅成功作业的真实减速比均值；剔除失败尝试后的无偏口径。 */
    private final double successOnlyMeanComputeTrueSlowdown;
    /** success-only 变体的观测数（成功且就绪事件齐备的计算作业）。 */
    private final int successOnlyWaitingObservationCount;
    /** 重试放大率 = 逻辑任务尝试总数 / 逻辑任务数；无失败时为 1.0，无任务时为 0.0。 */
    private final double retryAmplificationRatio;
    private final double meanComputeReadyToDecisionDelaySeconds;
    private final double meanComputeDecisionToStartDelaySeconds;
    private final int readyToDecisionObservationCount;
    private final int decisionToStartObservationCount;
    private final int schedulingCycleCount;
    private final long totalSchedulingDecisionWallClockNanos;
    private final int explicitPlannerDecisionObservationCount;
    private final long totalPlanningDecisionWallClockNanos;
    private final int dataStageInModelObservationCount;
    private final int modeledDataTransferFileCount;
    private final double totalModeledDataTransferSeconds;
    private final double totalModeledRequiredInputBytes;
    private final double meanModeledDataTransferSeconds;
    private final double totalModeledProcessingCost;
    private final double totalModeledCpuEnvelopeCost;
    private final double totalModeledDeclaredFileBandwidthCost;
    private final double totalModeledDeclaredFileBytes;
    private final Map<Integer, VmMetrics> vmMetrics;
    private final double totalVmModeledBusyIntervalSeconds;
    private final double meanVmModeledIntervalUtilization;
    private final double vmModeledBusyTimeCoefficientOfVariation;
    /** VM 利用率 Jain 公平性指数 ∈ (0,1]；补充 CV 无法区分的分布形态信息。 */
    private final double vmUtilizationJainFairnessIndex;
    private final boolean controlledSharedStorageCriticalPathReferenceAvailable;
    private final String controlledSharedStorageCriticalPathReferenceScope;
    private final double controlledSharedStorageCriticalPathLowerBoundSeconds;
    private final double controlledSharedStorageScheduleLengthRatio;
    private final boolean deadlineObservationEnabled;
    private final String deadlineObservationTimeBasis;
    private final String deadlineObservationOutcome;
    private final boolean deadlineMet;
    private final double deadlineSlackSeconds;
    private final double deadlineTardinessSeconds;
    private final int exactTaskTimingObservationCount;
    private final int modeledApproximateTaskTimingObservationCount;

    private SimulationMetrics(double makespanSeconds, Double logicalTaskCompletionSeconds,
            String logicalTaskCompletionStatus, Double terminalLifecycleTailSeconds,
            int jobOutcomeCount,
            int computeJobOutcomeCount, int stageInJobOutcomeCount,
            int successfulJobOutcomeCount, int failedJobOutcomeCount,
            int successfulComputeJobOutcomeCount, int failedComputeJobOutcomeCount,
            double successfulComputeJobOutcomeRate, double computeJobOutcomeThroughputPerSecond,
            int logicalTaskCount, int successfullyCompletedLogicalTaskCount,
            int logicalTasksNotYetSuccessfullyCompletedCount,
            double successfulLogicalTaskCompletionRate, boolean allLogicalTasksCompletedSuccessfully,
            int initialComputeJobOutcomeCount, int retryJobCreatedCount,
            int completedRetryComputeJobOutcomeCount, int logicalTaskAttemptCount,
            int retriedLogicalTaskCount, double failedComputeAttemptEnvelopeSeconds,
            double failedComputeAttemptModeledProcessingCost,
            double retryComputeAttemptModeledProcessingCost,
            double meanComputeJobRunTimeSeconds, double meanJobVmQueueWaitingTimeSeconds,
            double meanJobResponseTimeSeconds, double meanJobVmLevelSlowdown,
            int vmLevelSlowdownObservationCount,
            double meanComputeTotalWaitingTimeSeconds, double meanComputeTrueSlowdown,
            int trueSlowdownObservationCount,
            int totalWaitingTimeObservationCount,
            double medianComputeTotalWaitingTimeSeconds,
            double p95ComputeTotalWaitingTimeSeconds,
            double maxComputeTotalWaitingTimeSeconds,
            double medianComputeTrueSlowdown,
            double p95ComputeTrueSlowdown,
            double maxComputeTrueSlowdown,
            double successOnlyMeanComputeTotalWaitingTimeSeconds,
            double successOnlyMeanComputeTrueSlowdown,
            int successOnlyWaitingObservationCount,
            double retryAmplificationRatio,
            double meanComputeReadyToDecisionDelaySeconds,
            double meanComputeDecisionToStartDelaySeconds, int readyToDecisionObservationCount,
            int decisionToStartObservationCount, int schedulingCycleCount,
            long totalSchedulingDecisionWallClockNanos, int explicitPlannerDecisionObservationCount,
            long totalPlanningDecisionWallClockNanos, int dataStageInModelObservationCount,
            int modeledDataTransferFileCount, double totalModeledDataTransferSeconds,
            double totalModeledRequiredInputBytes, double meanModeledDataTransferSeconds,
            double totalModeledProcessingCost, double totalModeledCpuEnvelopeCost,
            double totalModeledDeclaredFileBandwidthCost, double totalModeledDeclaredFileBytes,
            Map<Integer, VmMetrics> vmMetrics, double totalVmModeledBusyIntervalSeconds,
            double meanVmModeledIntervalUtilization, double vmModeledBusyTimeCoefficientOfVariation,
            double vmUtilizationJainFairnessIndex,
            WorkflowModelReference.Reference criticalPathReference,
            double controlledSharedStorageScheduleLengthRatio, boolean deadlineObservationEnabled,
            String deadlineObservationOutcome, boolean deadlineMet, double deadlineSlackSeconds,
            double deadlineTardinessSeconds, int exactTaskTimingObservationCount,
            int modeledApproximateTaskTimingObservationCount) {
        this.makespanSeconds = makespanSeconds;
        this.logicalTaskCompletionSeconds = logicalTaskCompletionSeconds;
        this.logicalTaskCompletionStatus = logicalTaskCompletionStatus;
        this.terminalLifecycleTailSeconds = terminalLifecycleTailSeconds;
        this.jobOutcomeCount = jobOutcomeCount;
        this.computeJobOutcomeCount = computeJobOutcomeCount;
        this.stageInJobOutcomeCount = stageInJobOutcomeCount;
        this.successfulJobOutcomeCount = successfulJobOutcomeCount;
        this.failedJobOutcomeCount = failedJobOutcomeCount;
        this.successfulComputeJobOutcomeCount = successfulComputeJobOutcomeCount;
        this.failedComputeJobOutcomeCount = failedComputeJobOutcomeCount;
        this.successfulComputeJobOutcomeRate = successfulComputeJobOutcomeRate;
        this.logicalTaskCount = logicalTaskCount;
        this.successfullyCompletedLogicalTaskCount = successfullyCompletedLogicalTaskCount;
        this.logicalTasksNotYetSuccessfullyCompletedCount = logicalTasksNotYetSuccessfullyCompletedCount;
        this.successfulLogicalTaskCompletionRate = successfulLogicalTaskCompletionRate;
        this.allLogicalTasksCompletedSuccessfully = allLogicalTasksCompletedSuccessfully;
        this.initialComputeJobOutcomeCount = initialComputeJobOutcomeCount;
        this.retryJobCreatedCount = retryJobCreatedCount;
        this.completedRetryComputeJobOutcomeCount = completedRetryComputeJobOutcomeCount;
        this.logicalTaskAttemptCount = logicalTaskAttemptCount;
        this.retriedLogicalTaskCount = retriedLogicalTaskCount;
        this.failedComputeAttemptEnvelopeSeconds = failedComputeAttemptEnvelopeSeconds;
        this.failedComputeAttemptModeledProcessingCost = failedComputeAttemptModeledProcessingCost;
        this.retryComputeAttemptModeledProcessingCost = retryComputeAttemptModeledProcessingCost;
        this.computeJobOutcomeThroughputPerSecond = computeJobOutcomeThroughputPerSecond;
        this.meanComputeJobRunTimeSeconds = meanComputeJobRunTimeSeconds;
        this.meanJobVmQueueWaitingTimeSeconds = meanJobVmQueueWaitingTimeSeconds;
        this.meanJobResponseTimeSeconds = meanJobResponseTimeSeconds;
        this.meanJobVmLevelSlowdown = meanJobVmLevelSlowdown;
        this.vmLevelSlowdownObservationCount = vmLevelSlowdownObservationCount;
        this.meanComputeTotalWaitingTimeSeconds = meanComputeTotalWaitingTimeSeconds;
        this.meanComputeTrueSlowdown = meanComputeTrueSlowdown;
        this.trueSlowdownObservationCount = trueSlowdownObservationCount;
        this.totalWaitingTimeObservationCount = totalWaitingTimeObservationCount;
        this.medianComputeTotalWaitingTimeSeconds = medianComputeTotalWaitingTimeSeconds;
        this.p95ComputeTotalWaitingTimeSeconds = p95ComputeTotalWaitingTimeSeconds;
        this.maxComputeTotalWaitingTimeSeconds = maxComputeTotalWaitingTimeSeconds;
        this.medianComputeTrueSlowdown = medianComputeTrueSlowdown;
        this.p95ComputeTrueSlowdown = p95ComputeTrueSlowdown;
        this.maxComputeTrueSlowdown = maxComputeTrueSlowdown;
        this.successOnlyMeanComputeTotalWaitingTimeSeconds = successOnlyMeanComputeTotalWaitingTimeSeconds;
        this.successOnlyMeanComputeTrueSlowdown = successOnlyMeanComputeTrueSlowdown;
        this.successOnlyWaitingObservationCount = successOnlyWaitingObservationCount;
        this.retryAmplificationRatio = retryAmplificationRatio;
        this.meanComputeReadyToDecisionDelaySeconds = meanComputeReadyToDecisionDelaySeconds;
        this.meanComputeDecisionToStartDelaySeconds = meanComputeDecisionToStartDelaySeconds;
        this.readyToDecisionObservationCount = readyToDecisionObservationCount;
        this.decisionToStartObservationCount = decisionToStartObservationCount;
        this.schedulingCycleCount = schedulingCycleCount;
        this.totalSchedulingDecisionWallClockNanos = totalSchedulingDecisionWallClockNanos;
        this.explicitPlannerDecisionObservationCount = explicitPlannerDecisionObservationCount;
        this.totalPlanningDecisionWallClockNanos = totalPlanningDecisionWallClockNanos;
        this.dataStageInModelObservationCount = dataStageInModelObservationCount;
        this.modeledDataTransferFileCount = modeledDataTransferFileCount;
        this.totalModeledDataTransferSeconds = totalModeledDataTransferSeconds;
        this.totalModeledRequiredInputBytes = totalModeledRequiredInputBytes;
        this.meanModeledDataTransferSeconds = meanModeledDataTransferSeconds;
        this.totalModeledProcessingCost = totalModeledProcessingCost;
        this.totalModeledCpuEnvelopeCost = totalModeledCpuEnvelopeCost;
        this.totalModeledDeclaredFileBandwidthCost = totalModeledDeclaredFileBandwidthCost;
        this.totalModeledDeclaredFileBytes = totalModeledDeclaredFileBytes;
        this.vmMetrics = Collections.unmodifiableMap(new LinkedHashMap<Integer, VmMetrics>(vmMetrics));
        this.totalVmModeledBusyIntervalSeconds = totalVmModeledBusyIntervalSeconds;
        this.meanVmModeledIntervalUtilization = meanVmModeledIntervalUtilization;
        this.vmModeledBusyTimeCoefficientOfVariation = vmModeledBusyTimeCoefficientOfVariation;
        this.vmUtilizationJainFairnessIndex = vmUtilizationJainFairnessIndex;
        this.controlledSharedStorageCriticalPathReferenceAvailable = criticalPathReference.isAvailable();
        this.controlledSharedStorageCriticalPathReferenceScope = criticalPathReference.getScope();
        this.controlledSharedStorageCriticalPathLowerBoundSeconds =
                criticalPathReference.getCriticalPathLowerBoundSeconds();
        this.controlledSharedStorageScheduleLengthRatio = controlledSharedStorageScheduleLengthRatio;
        this.deadlineObservationEnabled = deadlineObservationEnabled;
        this.deadlineObservationTimeBasis = "SIMULATION_END_SECONDS";
        this.deadlineObservationOutcome = deadlineObservationOutcome;
        this.deadlineMet = deadlineMet;
        this.deadlineSlackSeconds = deadlineSlackSeconds;
        this.deadlineTardinessSeconds = deadlineTardinessSeconds;
        this.exactTaskTimingObservationCount = exactTaskTimingObservationCount;
        this.modeledApproximateTaskTimingObservationCount = modeledApproximateTaskTimingObservationCount;
    }

    /**
     * Bounded slowdown 阈值（秒）。
     * 在计算减速比时，执行时间下限设为此值，防止极短作业导致数值爆炸。
     * 参考 Feitelson 等人的调度研究标准实践。
     */
    private static final double BOUNDED_SLOWDOWN_THRESHOLD_SECONDS = 10.0;

    static SimulationMetrics calculate(double makespan, List<SimulationReport.JobOutcome> jobs,
            Map<Integer, SimulationReport.VmSummary> summaries,
            List<SimulationEvent> events, List<SimulationReport.TaskOutcome> taskOutcomes,
            List<Task> sourceTasks, SimulationConfig config, PlatformProfile platform) {
        // 数值稳定性：校验 makespan 有限性，防止 NaN/Infinity 静默传播进指标计算
        requireFinite(makespan, "makespan");
        // 校验每个 JobOutcome 的时间和成本字段有限性
        for (SimulationReport.JobOutcome job : jobs) {
            requireFinite(job.getSubmissionTime(), "job " + job.getJobId() + " submissionTime");
            requireFinite(job.getStartTime(), "job " + job.getJobId() + " startTime");
            requireFinite(job.getFinishTime(), "job " + job.getJobId() + " finishTime");
            requireFinite(job.getCpuTime(), "job " + job.getJobId() + " cpuTime");
            requireFinite(job.getModeledCpuEnvelopeCost(), "job " + job.getJobId() + " cpuCost");
            requireFinite(job.getModeledDeclaredFileBandwidthCost(), "job " + job.getJobId() + " bandwidthCost");
            requireFinite(job.getModeledDeclaredFileBytes(), "job " + job.getJobId() + " declaredFileBytes");
        }
        Map<Integer, SimulationReport.JobOutcome> jobsById = jobOutcomesById(jobs);
        Set<Integer> retryJobIds = retryJobIds(events, jobsById);
        Map<Integer, Double> readyTimes = new LinkedHashMap<Integer, Double>();
        Map<Integer, Double> decisionTimes = new LinkedHashMap<Integer, Double>();
        long totalSchedulingNanos = 0L;
        int schedulingCycles = 0;
        long totalPlanningNanos = 0L;
        int explicitPlannerDecisions = 0;
        int dataStageInObservations = 0;
        int modeledTransferFiles = 0;
        double totalTransferSeconds = 0.0;
        double totalRequiredInputBytes = 0.0;
        for (SimulationEvent event : events) {
            if (event.getType() == SimulationEventType.JOB_READY && event.getJobId() != null) {
                // PLAT-16：每个作业只应有一个 JOB_READY 事件（retry 作业使用新 jobId）。
                // 重复事件说明事件流被破坏，静默覆盖会让等待时间指标悄悄失真，故 fail-fast。
                Double previousReady = readyTimes.put(event.getJobId(), event.getSimulationTime());
                if (previousReady != null) {
                    throw new IllegalStateException("Duplicate JOB_READY event for job "
                            + event.getJobId() + ": ready time was already recorded at "
                            + previousReady + " but a second event arrived at "
                            + event.getSimulationTime());
                }
            } else if (event.getType() == SimulationEventType.SCHEDULING_DECISION
                    && event.getJobId() != null) {
                // PLAT-16：同上，SCHEDULING_DECISION 每作业恰一次，重复即事件流破坏。
                Double previousDecision = decisionTimes.put(event.getJobId(), event.getSimulationTime());
                if (previousDecision != null) {
                    throw new IllegalStateException("Duplicate SCHEDULING_DECISION event for job "
                            + event.getJobId() + ": decision time was already recorded at "
                            + previousDecision + " but a second event arrived at "
                            + event.getSimulationTime());
                }
            } else if (event.getType() == SimulationEventType.SCHEDULING_CYCLE) {
                schedulingCycles++;
                Object elapsed = event.getAttributes().get("decisionElapsedNanos");
                if (elapsed instanceof Number) {
                    totalSchedulingNanos += ((Number) elapsed).longValue();
                }
            } else if (event.getType() == SimulationEventType.PLANNING_COMPLETED
                    && Boolean.TRUE.equals(event.getAttributes().get("explicitPlannerDecision"))) {
                explicitPlannerDecisions++;
                totalPlanningNanos += requiredNonNegativeLongAttribute(event,
                        "planningDecisionElapsedNanos");
            } else if (event.getType() == SimulationEventType.DATA_STAGE_IN_MODELED) {
                dataStageInObservations++;
                totalTransferSeconds += requiredFiniteNonNegativeAttribute(event,
                        "modeledTransferSeconds");
                totalRequiredInputBytes += requiredFiniteNonNegativeAttribute(event,
                        "requiredFileBytes");
                modeledTransferFiles += requiredNonNegativeIntegerAttribute(event,
                        "modeledTransferFileCount");
            }
        }

        int compute = 0;
        int stageIn = 0;
        int success = 0;
        int failure = 0;
        int computeSuccess = 0;
        int computeFailure = 0;
        double computeRunTimeSum = 0.0;
        double jobWaitingTimeSum = 0.0;
        double jobResponseTimeSum = 0.0;
        double jobSlowdownSum = 0.0;
        int jobSlowdownCount = 0;
        double trueSlowdownSum = 0.0;
        int trueSlowdownCount = 0;
        double readyToDecisionSum = 0.0;
        int readyToDecisionCount = 0;
        double decisionToStartSum = 0.0;
        int decisionToStartCount = 0;
        double totalWaitingTimeSum = 0.0;
        int totalWaitingTimeCount = 0;
        // 分布统计样本（中位数/p95/max）与 success-only 变体累加器。
        List<Double> totalWaitingSamples = new ArrayList<Double>();
        List<Double> trueSlowdownSamples = new ArrayList<Double>();
        double successOnlyTotalWaitingSum = 0.0;
        double successOnlyTrueSlowdownSum = 0.0;
        int successOnlyWaitingObservationCount = 0;
        double cost = 0.0;
        double cpuEnvelopeCost = 0.0;
        double declaredFileBandwidthCost = 0.0;
        double declaredFileBytes = 0.0;
        int completedRetryComputeJobs = 0;
        double retryComputeCost = 0.0;
        double failedComputeEnvelopeSeconds = 0.0;
        double failedComputeCost = 0.0;
        Map<Integer, MutableVmMetrics> vm = new LinkedHashMap<Integer, MutableVmMetrics>();
        for (Integer vmId : summaries.keySet()) {
            vm.put(vmId, new MutableVmMetrics(vmId));
        }

        for (SimulationReport.JobOutcome job : jobs) {
            if (job.getStatus() == Cloudlet.SUCCESS) {
                success++;
            } else if (job.getStatus() == Cloudlet.FAILED) {
                failure++;
            }
            cost += job.getModeledProcessingCost();
            cpuEnvelopeCost += job.getModeledCpuEnvelopeCost();
            declaredFileBandwidthCost += job.getModeledDeclaredFileBandwidthCost();
            declaredFileBytes += job.getModeledDeclaredFileBytes();
            MutableVmMetrics perVm = vm.get(job.getVmId());
            if (perVm != null) {
                perVm.add(job);
            }

            if (job.getClassType() == Parameters.ClassType.COMPUTE.value) {
                compute++;
                if (retryJobIds.contains(job.getJobId())) {
                    completedRetryComputeJobs++;
                    retryComputeCost += job.getModeledProcessingCost();
                }
                if (job.getStatus() == Cloudlet.SUCCESS) {
                    computeSuccess++;
                } else if (job.getStatus() == Cloudlet.FAILED) {
                    computeFailure++;
                    failedComputeEnvelopeSeconds += nonNegative(job.getFinishTime()
                            - job.getStartTime());
                    failedComputeCost += job.getModeledProcessingCost();
                }
                computeRunTimeSum += nonNegative(job.getFinishTime() - job.getStartTime());
                
                // 统计等待时间和响应时间（所有作业，包括成功和失败）
                jobWaitingTimeSum += job.getWaitingTime();
                jobResponseTimeSum += job.getResponseTime();
                
                // 统计减速比（仅执行时间 > 0.001 秒的作业，避免除零）
                if (job.getExecutionTime() > 0.001) {
                    double slowdown = job.getResponseTime() / job.getExecutionTime();
                    jobSlowdownSum += slowdown;
                    jobSlowdownCount++;
                }
                
                Double ready = readyTimes.get(job.getJobId());
                Double decision = decisionTimes.get(job.getJobId());
                if (ready != null && decision != null && decision.doubleValue() >= ready.doubleValue()) {
                    readyToDecisionSum += decision.doubleValue() - ready.doubleValue();
                    readyToDecisionCount++;
                }
                if (decision != null && job.getStartTime() >= decision.doubleValue()) {
                    decisionToStartSum += job.getStartTime() - decision.doubleValue();
                    decisionToStartCount++;
                }
                // 总等待时间（就绪到开始执行）：涵盖调度器队列等待 + 派发延迟
                if (ready != null && job.getStartTime() >= ready.doubleValue()) {
                    double trueWait = job.getStartTime() - ready.doubleValue();
                    double execTime = nonNegative(job.getFinishTime() - job.getStartTime());
                    // 真实减速比（bounded slowdown，Feitelson 标准）：
                    // max( (真实等待 + 执行时间) / max(执行时间, 10s), 1.0 )
                    // 分母下限 10 秒防止极短作业导致数值爆炸；
                    // 外层 max(1) 保证减速比语义（不存在 "加速"）
                    double boundedExec = Math.max(execTime, BOUNDED_SLOWDOWN_THRESHOLD_SECONDS);
                    double trueSlowdown = Math.max((trueWait + execTime) / boundedExec, 1.0);

                    totalWaitingTimeSum += trueWait;
                    totalWaitingTimeCount++;
                    totalWaitingSamples.add(Double.valueOf(trueWait));
                    trueSlowdownSum += trueSlowdown;
                    trueSlowdownCount++;
                    trueSlowdownSamples.add(Double.valueOf(trueSlowdown));

                    // success-only 变体：剔除失败尝试，避免跨失败率方案比较时均值有偏。
                    if (job.getStatus() == Cloudlet.SUCCESS) {
                        successOnlyTotalWaitingSum += trueWait;
                        successOnlyTrueSlowdownSum += trueSlowdown;
                        successOnlyWaitingObservationCount++;
                    }
                }
            } else if (job.getClassType() == Parameters.ClassType.STAGE_IN.value) {
                stageIn++;
            }
        }

        Map<Integer, VmMetrics> frozenVm = new LinkedHashMap<Integer, VmMetrics>();
        List<Double> busyTimes = new ArrayList<Double>();
        double totalBusyInterval = 0.0;
        for (Map.Entry<Integer, MutableVmMetrics> entry : vm.entrySet()) {
            VmMetrics metrics = entry.getValue().freeze(makespan);
            frozenVm.put(entry.getKey(), metrics);
            busyTimes.add(metrics.getModeledBusyIntervalSeconds());
            totalBusyInterval += metrics.getModeledBusyIntervalSeconds();
        }
        Set<Integer> logicalTaskIds = logicalTaskIds(sourceTasks, taskOutcomes);
        Map<Integer, Double> earliestSuccessfulJobFinishByLogicalTask =
                earliestSuccessfulJobFinishByLogicalTask(jobs, logicalTaskIds);
        Set<Integer> successfullyCompletedTaskIds =
                new HashSet<Integer>(earliestSuccessfulJobFinishByLogicalTask.keySet());
        int logicalTaskCount = logicalTaskIds.size();
        int logicalSuccesses = successfullyCompletedTaskIds.size();
        String logicalTaskCompletionStatus;
        Double logicalTaskCompletionSeconds;
        Double terminalLifecycleTailSeconds;
        if (logicalTaskCount == 0) {
            logicalTaskCompletionStatus = "NO_LOGICAL_TASKS";
            logicalTaskCompletionSeconds = null;
            terminalLifecycleTailSeconds = null;
        } else if (logicalSuccesses != logicalTaskCount) {
            logicalTaskCompletionStatus = "INCOMPLETE_LOGICAL_TASKS";
            logicalTaskCompletionSeconds = null;
            terminalLifecycleTailSeconds = null;
        } else {
            logicalTaskCompletionStatus = "COMPLETED_SUCCESSFULLY";
            logicalTaskCompletionSeconds = Double.valueOf(latestFinish(
                    earliestSuccessfulJobFinishByLogicalTask));
            terminalLifecycleTailSeconds = Double.valueOf(nonNegative(makespan
                    - logicalTaskCompletionSeconds.doubleValue()));
        }
        int logicalAttempts = logicalTaskAttemptCount(taskOutcomes, jobsById, logicalTaskIds);
        int retriedLogicalTasks = retriedLogicalTaskCount(retryJobIds, jobsById, logicalTaskIds);
        WorkflowModelReference.Reference criticalPathReference =
                WorkflowModelReference.calculate(sourceTasks, config, platform);
        double scheduleLengthRatio = criticalPathReference.isAvailable()
                && criticalPathReference.getCriticalPathLowerBoundSeconds() > 0.0
                ? makespan / criticalPathReference.getCriticalPathLowerBoundSeconds() : 0.0;
        boolean workflowCompleted = "COMPLETED_SUCCESSFULLY".equals(logicalTaskCompletionStatus);
        boolean deadlineEnabled = config.getDeadline() > 0L;
        String deadlineOutcome = deadlineOutcome(deadlineEnabled, workflowCompleted, makespan,
                config.getDeadline());
        boolean deadlineMet = "MET".equals(deadlineOutcome);
        double deadlineSlack = deadlineEnabled ? config.getDeadline() - makespan : 0.0;
        double deadlineTardiness = deadlineEnabled
                ? Math.max(0.0, makespan - config.getDeadline()) : 0.0;
        int exactTaskTimingCount = 0;
        for (SimulationReport.TaskOutcome outcome : taskOutcomes) {
            if (outcome.hasExactJobTiming()) {
                exactTaskTimingCount++;
            }
        }
        // ── 指标增强：分布统计、公平性指数、success-only 变体、重试放大率 ──
        // VM 利用率 Jain 公平性指数：(Σu)² / (n·Σu²)，补充 CV 无法区分的分布形态。
        double vmUtilizationJainFairnessIndex = jainFairnessIndex(vmUtilizations(frozenVm));
        // 等待/减速比分布统计（排序后最近秩法取中位数/p95/max）。
        Collections.sort(totalWaitingSamples);
        Collections.sort(trueSlowdownSamples);
        double retryAmplificationRatio = logicalTaskCount > 0
                ? (double) logicalAttempts / logicalTaskCount : 0.0;
        return new SimulationMetrics(makespan, logicalTaskCompletionSeconds,
                logicalTaskCompletionStatus, terminalLifecycleTailSeconds,
                jobs.size(), compute, stageIn, success, failure,
                computeSuccess, computeFailure, ratio(computeSuccess, compute),
                makespan > 0.0 ? compute / makespan : 0.0, logicalTaskCount, logicalSuccesses,
                logicalTaskCount - logicalSuccesses, ratio(logicalSuccesses, logicalTaskCount),
                workflowCompleted, compute - completedRetryComputeJobs, retryJobIds.size(),
                completedRetryComputeJobs, logicalAttempts, retriedLogicalTasks,
                failedComputeEnvelopeSeconds, failedComputeCost, retryComputeCost,
                mean(computeRunTimeSum, compute),
                mean(jobWaitingTimeSum, compute),
                mean(jobResponseTimeSum, compute),
                mean(jobSlowdownSum, jobSlowdownCount),
                jobSlowdownCount,
                mean(totalWaitingTimeSum, totalWaitingTimeCount),
                mean(trueSlowdownSum, trueSlowdownCount),
                trueSlowdownCount,
                totalWaitingTimeCount,
                percentile(totalWaitingSamples, 0.5),
                percentile(totalWaitingSamples, 0.95),
                percentile(totalWaitingSamples, 1.0),
                percentile(trueSlowdownSamples, 0.5),
                percentile(trueSlowdownSamples, 0.95),
                percentile(trueSlowdownSamples, 1.0),
                mean(successOnlyTotalWaitingSum, successOnlyWaitingObservationCount),
                mean(successOnlyTrueSlowdownSum, successOnlyWaitingObservationCount),
                successOnlyWaitingObservationCount,
                retryAmplificationRatio,
                mean(readyToDecisionSum, readyToDecisionCount),
                mean(decisionToStartSum, decisionToStartCount), readyToDecisionCount,
                decisionToStartCount, schedulingCycles, totalSchedulingNanos,
                explicitPlannerDecisions, totalPlanningNanos, dataStageInObservations,
                modeledTransferFiles, totalTransferSeconds, totalRequiredInputBytes,
                mean(totalTransferSeconds, dataStageInObservations), cost, cpuEnvelopeCost,
                declaredFileBandwidthCost, declaredFileBytes, frozenVm,
                totalBusyInterval, makespan > 0.0 && !frozenVm.isEmpty()
                        ? totalBusyInterval / (makespan * frozenVm.size()) : 0.0,
                coefficientOfVariation(busyTimes), vmUtilizationJainFairnessIndex,
                criticalPathReference, scheduleLengthRatio,
                deadlineEnabled, deadlineOutcome, deadlineMet, deadlineSlack, deadlineTardiness,
                exactTaskTimingCount, taskOutcomes.size() - exactTaskTimingCount);
    }

    /**
     * 返回兼容历史口径的 CloudSim 仿真结束时间。
     *
     * <p>该值可能包含末个 Job 返回后的工作流引擎生命周期尾部，不应无条件理解为最后一个
     * 逻辑 Task 的成功完成时间。</p>
     *
     * @return 以模拟秒表示的仿真结束时间
     */
    public double getMakespanSeconds() { return makespanSeconds; }
    /**
     * 返回本次运行的 CloudSim 仿真结束时间。
     *
     * @return 与 {@link #getMakespanSeconds()} 完全相同的模拟秒值
     */
    public double getSimulationEndSeconds() { return makespanSeconds; }
    /**
     * 返回全部逻辑 Task 首次由成功 compute Job 完成时的最大 Job envelope 结束时刻。
     *
     * @return 全部逻辑 Task 成功时的完成秒数；否则为 {@code null}
     */
    public Double getLogicalTaskCompletionSeconds() { return logicalTaskCompletionSeconds; }
    /**
     * 返回 {@code COMPLETED_SUCCESSFULLY}、{@code INCOMPLETE_LOGICAL_TASKS} 或
     * {@code NO_LOGICAL_TASKS}，以明确逻辑完成时间是否可用。
     *
     * @return 逻辑 Task 完成时间状态
     */
    public String getLogicalTaskCompletionStatus() { return logicalTaskCompletionStatus; }
    /**
     * 返回最后一个逻辑 Task 成功完成到 CloudSim 仿真结束之间的生命周期尾部。
     *
     * @return 工作流完整时的非负模拟秒数；否则为 {@code null}
     */
    public Double getTerminalLifecycleTailSeconds() { return terminalLifecycleTailSeconds; }
    /** @return 已完成 Job attempt 数，等同于 {@link #getJobOutcomeCount()} */
    public int getCompletedJobAttemptCount() { return jobOutcomeCount; }
    public int getJobOutcomeCount() { return jobOutcomeCount; }
    /** @return 已完成 compute Job attempt 数，包含 retry attempt */
    public int getCompletedComputeAttemptCount() { return computeJobOutcomeCount; }
    public int getComputeJobOutcomeCount() { return computeJobOutcomeCount; }
    public int getStageInJobOutcomeCount() { return stageInJobOutcomeCount; }
    public int getSuccessfulJobOutcomeCount() { return successfulJobOutcomeCount; }
    public int getFailedJobOutcomeCount() { return failedJobOutcomeCount; }
    public int getSuccessfulComputeJobOutcomeCount() { return successfulComputeJobOutcomeCount; }
    public int getFailedComputeJobOutcomeCount() { return failedComputeJobOutcomeCount; }
    /**
     * 返回成功计算 Job 占全部计算 Job 的比例。
     *
     * @return 成功计算 Job 结果率；无计算 Job 时为零
     */
    public double getSuccessfulComputeJobOutcomeRate() { return successfulComputeJobOutcomeRate; }
    public int getLogicalTaskCount() { return logicalTaskCount; }
    public int getSuccessfullyCompletedLogicalTaskCount() { return successfullyCompletedLogicalTaskCount; }
    public int getLogicalTasksNotYetSuccessfullyCompletedCount() {
        return logicalTasksNotYetSuccessfullyCompletedCount;
    }
    /**
     * 返回至少被一个成功 compute Job 完成的逻辑任务比例。
     *
     * @return 成功完成的逻辑任务比例；无逻辑任务时为零
     */
    public double getSuccessfulLogicalTaskCompletionRate() {
        return successfulLogicalTaskCompletionRate;
    }
    public boolean isAllLogicalTasksCompletedSuccessfully() {
        return allLogicalTasksCompletedSuccessfully;
    }
    /**
     * 返回单次运行层面的工作流成功指示量。
     *
     * <p>它是一次运行的完成事实，不能单独当作故障分布下的可靠性概率。后者只能由独立
     * 重复实验在 campaign 层估计。</p>
     *
     * @return 全部逻辑 Task 是否都由成功 compute Job 完成
     */
    public boolean isWorkflowCompletedSuccessfully() {
        return allLogicalTasksCompletedSuccessfully;
    }
    /**
     * 返回未在 {@code RETRY_JOB_CREATED} 事件中出现的已完成 compute attempt 数。
     *
     * @return 初始 compute attempt 数
     */
    public int getInitialComputeJobOutcomeCount() { return initialComputeJobOutcomeCount; }
    /** @return 事件流中创建的 retry Job 数 */
    public int getRetryJobCreatedCount() { return retryJobCreatedCount; }
    /** @return 已完成且由 retry 创建事件证实的 compute attempt 数 */
    public int getCompletedRetryComputeJobOutcomeCount() {
        return completedRetryComputeJobOutcomeCount;
    }
    /**
     * 返回 completed compute Job 中逻辑 Task 的尝试数。
     *
     * <p>聚类 Job 可以贡献多个 Task attempt；同一逻辑 Task 的重试会分别计入。</p>
     *
     * @return 逻辑 Task attempt 数
     */
    public int getLogicalTaskAttemptCount() { return logicalTaskAttemptCount; }
    /** @return 至少出现在一个 retry compute Job 中的不同逻辑 Task 数 */
    public int getRetriedLogicalTaskCount() { return retriedLogicalTaskCount; }
    /**
     * 返回所有失败 compute attempt 的 Job envelope 时长之和。
     *
     * <p>它是当前 fail-after-attempt 模型中的失败执行证据，不自动等同于现实系统的中途
     * 宕机损失或可结算浪费。</p>
     *
     * @return 失败 compute attempt 的累计信封秒数
     */
    public double getFailedComputeAttemptEnvelopeSeconds() {
        return failedComputeAttemptEnvelopeSeconds;
    }
    /**
     * 返回失败 compute attempt 的建模处理成本之和。
     *
     * @return 失败 compute attempt 的抽象成本单位值
     */
    public double getFailedComputeAttemptModeledProcessingCost() {
        return failedComputeAttemptModeledProcessingCost;
    }
    /** @return retry compute attempt 的建模处理成本之和 */
    public double getRetryComputeAttemptModeledProcessingCost() {
        return retryComputeAttemptModeledProcessingCost;
    }
    /**
     * 返回计算 Job 结果相对于 makespan 的吞吐率。
     *
     * @return 每模拟秒的计算 Job 结果数；makespan 为零时为零
     */
    public double getComputeJobOutcomeThroughputPerSecond() { return computeJobOutcomeThroughputPerSecond; }
    /**
     * 计算 Job 信封的平均端到端时长，其中包含生效的 stage-in。
     *
     * <p>样本为<b>全部计算 Job attempt</b>（含失败与 retry 尝试），与
     * {@link #getMeanComputeTotalWaitingTimeSeconds()} 的样本口径一致。</p>
     *
     * @return 以模拟秒表示的平均计算 Job 信封时长
     */
    public double getMeanComputeJobRunTimeSeconds() { return meanComputeJobRunTimeSeconds; }
    
    /**
     * 返回作业在 VM 队列内的平均等待时间（到达 VM 到开始执行）。
     * <p><strong>注意：</strong>在 WorkflowSim 中，所有调度算法都只向空闲 VM 派发作业，
     * 因此该值几乎总是 0。它衡量的是 CloudSim 层面的 VM 队列排队，
     * 不包括调度器队列等待。如需真实等待时间，请使用
     * {@link #getMeanComputeTotalWaitingTimeSeconds()}。</p>
     *
     * @return 以模拟秒表示的平均 VM 队列等待时间（通常为 0）
     */
    public double getMeanJobVmQueueWaitingTimeSeconds() { return meanJobVmQueueWaitingTimeSeconds; }

    /**
     * 返回作业平均响应时间（从 VM 到达时刻到完成）。
     *
     * <p><b>⚠️ 口径说明</b>：这里的"提交"是 CloudSim 的 {@code submissionTime}，
     * 即作业<strong>到达执行 VM 的时刻</strong>（由 {@code ResCloudlet} 在 VM 受理时设置），
     * <b>不是</b>作业进入调度器就绪队列的时刻。因此该指标
     * <b>不包含</b>调度器队列等待与派发延迟。</p>
     * <p>如需衡量"就绪 → 完成"的全程响应时间，请改用
     * {@code 平均总等待时间 + 平均执行时间}；如需纯等待口径，请使用
     * {@link #getMeanComputeTotalWaitingTimeSeconds()}。</p>
     *
     * @return 以模拟秒表示的平均作业响应时间（VM 到达 → 完成）
     */
    public double getMeanJobResponseTimeSeconds() { return meanJobResponseTimeSeconds; }

    /**
     * 返回作业平均 VM 层面减速比（基于 VM 到达时间的响应时间 / 执行时间）。
     * <p><b>⚠️ 注意</b>：在 WorkflowSim 中，由于所有调度算法都只向空闲 VM 派发作业，
     * 该值恒为 1.0 左右，<b>不适合</b>用于调度算法性能比较。
     * 请使用 {@link #getMeanComputeTrueSlowdown()} 作为真实调度效率指标。</p>
     * <p>仅统计执行时间 > 0.001 秒的作业，避免除零。</p>
     *
     * @return 无量纲的平均 VM 层面减速比（通常约为 1.0）
     */
    public double getMeanJobVmLevelSlowdown() { return meanJobVmLevelSlowdown; }

    /**
     * 返回 VM 层面减速比的样本观测数。
     *
     * <p>与 {@link #getMeanJobVmLevelSlowdown()} 同一集合：仅统计
     * {@code executionTime > 0.001s} 的计算作业（防比值爆炸的独立门控，
     * 与真实减速比的 max(exec,10s) 分母钳制语义不同）。观测数不足均值时
     * 说明有极短/零长作业被排除。</p>
     *
     * @return 观测计数
     */
    public int getVmLevelSlowdownObservationCount() { return vmLevelSlowdownObservationCount; }

    /**
     * 返回计算作业的平均总等待时间（从就绪到开始执行）。
     * <p>这是调度研究中最核心的等待时间指标，等于
     * {@code startTime - readyTime}，涵盖了调度器队列等待和派发延迟两部分。
     * 它从 {@link SimulationEventType#JOB_READY} 事件提取就绪时间。</p>
     * <p>与 {@link #getMeanComputeReadyToDecisionDelaySeconds()} 和
     * {@link #getMeanComputeDecisionToStartDelaySeconds()} 的关系：
     * 总等待 ≈ 就绪到决策延迟 + 决策到开始延迟。</p>
     *
     * @return 以模拟秒表示的平均总等待时间（就绪到开始）
     */
    public double getMeanComputeTotalWaitingTimeSeconds() { return meanComputeTotalWaitingTimeSeconds; }

    /**
     * 返回计算作业的平均真实减速比（基于就绪时间，bounded slowdown）。
     * <p>定义（Feitelson 标准）：对每个作业计算
     * {@code max((startTime - readyTime + executionTime) / max(executionTime, 10.0), 1.0)}，
     * 然后取算术平均。两处边界处理：</p>
     * <ul>
     *   <li>分母下限 10 秒：防止极短作业导致数值爆炸（bounded slowdown）</li>
     *   <li>结果下限 1.0：保证减速比语义（不存在"加速"，1.0 即理想无等待）</li>
     * </ul>
     * <p>该指标能有效区分不同调度算法的性能：
     * <ul>
     *   <li>1.0 = 理想状态（无等待）</li>
     *   <li>>1.0 = 存在调度等待，数值越大表示效率越低</li>
     * </ul>
     * </p>
     * <p>与 {@link #getMeanJobVmLevelSlowdown()} 的区别：后者基于 VM 到达时间，
     * 在 WorkflowSim 中恒为 1.0；本指标基于作业就绪时间，能真实反映调度延迟。</p>
     *
     * @return 无量纲的平均真实减速比（bounded，分母下限 10s，结果下限 1.0）
     */
    public double getMeanComputeTrueSlowdown() { return meanComputeTrueSlowdown; }

    /**
     * 返回参与真实减速比计算的作业数量。
     * <p>只有同时满足以下条件的作业才参与计算：
     * <ul>
     *   <li>有 JOB_READY 事件记录（readyTime 非 null）</li>
     *   <li>startTime >= readyTime（时间戳有效）</li>
     * </ul>
     * </p>
     *
     * @return 观测计数
     */
    public int getTrueSlowdownObservationCount() { return trueSlowdownObservationCount; }

    /**
     * 返回总等待时间的样本观测数（与真实减速比样本同一集合）。
     *
     * @return 观测计数
     */
    public int getTotalWaitingTimeObservationCount() { return totalWaitingTimeObservationCount; }

    /** @return 总等待时间中位数（秒）；无观测时为 0.0 */
    public double getMedianComputeTotalWaitingTimeSeconds() { return medianComputeTotalWaitingTimeSeconds; }

    /** @return 总等待时间 P95（秒，最近秩法）；无观测时为 0.0 */
    public double getP95ComputeTotalWaitingTimeSeconds() { return p95ComputeTotalWaitingTimeSeconds; }

    /** @return 总等待时间最大值（秒）；无观测时为 0.0 */
    public double getMaxComputeTotalWaitingTimeSeconds() { return maxComputeTotalWaitingTimeSeconds; }

    /** @return 真实减速比中位数；无观测时为 0.0 */
    public double getMedianComputeTrueSlowdown() { return medianComputeTrueSlowdown; }

    /** @return 真实减速比 P95（最近秩法）；无观测时为 0.0 */
    public double getP95ComputeTrueSlowdown() { return p95ComputeTrueSlowdown; }

    /** @return 真实减速比最大值；无观测时为 0.0 */
    public double getMaxComputeTrueSlowdown() { return maxComputeTrueSlowdown; }

    /**
     * 返回仅成功作业的平均总等待时间（秒）。
     *
     * <p>剔除失败尝试后的无偏口径：失败尝试的等待时间计入总体均值会使
     * 跨失败率方案的比较失真，本变体只统计成功完成作业的样本。</p>
     *
     * @return 以模拟秒表示的 success-only 平均总等待时间；无观测时为 0.0
     */
    public double getSuccessOnlyMeanComputeTotalWaitingTimeSeconds() {
        return successOnlyMeanComputeTotalWaitingTimeSeconds;
    }

    /**
     * 返回仅成功作业的平均真实减速比。
     *
     * @return success-only 平均真实减速比；无观测时为 0.0
     */
    public double getSuccessOnlyMeanComputeTrueSlowdown() { return successOnlyMeanComputeTrueSlowdown; }

    /** @return success-only 等待/减速比变体的样本观测数 */
    public int getSuccessOnlyWaitingObservationCount() { return successOnlyWaitingObservationCount; }

    /**
     * 返回重试放大率 = 逻辑任务尝试总数 / 逻辑任务数。
     *
     * <p>无失败时为 1.0；无逻辑任务时为 0.0（此分支无 SLA 含义，消费端应结合
     * {@link #getLogicalTaskCount()} 判断）。衡量失败恢复带来的总执行开销放大。</p>
     *
     * @return 重试放大率；正常闭环下 ≥1.0，无逻辑任务时为 0.0
     */
    public double getRetryAmplificationRatio() { return retryAmplificationRatio; }

    /**
     * 返回从计算 Job 就绪到被调度器作出决策的平均延迟。
     *
     * @return 以模拟秒表示的平均就绪到决策延迟
     */
    public double getMeanComputeReadyToDecisionDelaySeconds() { return meanComputeReadyToDecisionDelaySeconds; }
    /**
     * 返回从调度器决策到计算 Job 开始执行的平均延迟。
     *
     * @return 以模拟秒表示的平均决策到开始延迟
     */
    public double getMeanComputeDecisionToStartDelaySeconds() { return meanComputeDecisionToStartDelaySeconds; }
    public int getReadyToDecisionObservationCount() { return readyToDecisionObservationCount; }
    public int getDecisionToStartObservationCount() { return decisionToStartObservationCount; }
    public int getSchedulingCycleCount() { return schedulingCycleCount; }
    /**
     * 返回调度决策消耗的本地 JVM 墙钟时间总和。
     *
     * <p>该值是本机开销观测，不能用于跨机器性能对比。</p>
     *
     * @return 调度决策墙钟耗时，单位纳秒
     */
    public long getTotalSchedulingDecisionWallClockNanos() { return totalSchedulingDecisionWallClockNanos; }
    /**
     * 事件记录中观测到的显式静态规划器决策次数。
     *
     * @return 显式规划器决策观测数
     */
    public int getExplicitPlannerDecisionObservationCount() {
        return explicitPlannerDecisionObservationCount;
    }
    /**
     * 显式规划器决策消耗的 JVM 墙钟时间。这是本地算法开销证据，不是模拟时间，也不能作为
     * 跨机器性能基准。
     *
     * @return 显式规划器决策消耗的本地墙钟纳秒数
     */
    public long getTotalPlanningDecisionWallClockNanos() {
        return totalPlanningDecisionWallClockNanos;
    }
    /**
     * 本次运行中观测到的计算 Job 数据 stage-in 估计次数。
     *
     * @return 数据 stage-in 模型观测数
     */
    public int getDataStageInModelObservationCount() { return dataStageInModelObservationCount; }
    /**
     * 被配置的数据移动模型观测到的逻辑输入需求文件数。
     *
     * <p>本地副本命中可以使建模 stage-in 时间为零，故该值不是实际网络上传输过的文件数。</p>
     *
     * @return 被计入的文件传输数量
     */
    public int getModeledDataTransferFileCount() { return modeledDataTransferFileCount; }
    /** @return 与 {@link #getModeledDataTransferFileCount()} 相同的逻辑输入需求文件数 */
    public int getModeledRequiredInputDemandFileCount() { return modeledDataTransferFileCount; }
    /**
     * 模型生成的计算 Job 数据 stage-in 延迟总和。
     *
     * @return 以模拟秒表示的总 stage-in 延迟
     */
    public double getTotalModeledDataTransferSeconds() { return totalModeledDataTransferSeconds; }
    /**
     * 上述传输观测所计入的逻辑输入需求字节数总和。
     *
     * <p>该值不是已通过物理链路搬运的字节数；当前数据移动模型不维护该类链路级账本。</p>
     *
     * @return 被模型纳入的数据字节总数
     */
    public double getTotalModeledRequiredInputBytes() { return totalModeledRequiredInputBytes; }
    /** @return 与 {@link #getTotalModeledRequiredInputBytes()} 相同的逻辑输入需求字节数 */
    public double getTotalModeledRequiredInputDemandBytes() {
        return totalModeledRequiredInputBytes;
    }
    /**
     * 每个计算 Job 观测对应的平均数据 stage-in 延迟。
     *
     * @return 以模拟秒表示的平均延迟
     */
    public double getMeanModeledDataTransferSeconds() { return meanModeledDataTransferSeconds; }
    /**
     * 全部完成 Job attempt 的建模处理成本之和；其中可能包含 stage-in 的整数 MI 表示，
     * 也包括失败和 retry attempt，因而不是云服务商价格回放。
     *
     * @return 汇总的建模处理成本
     */
    public double getTotalModeledProcessingCost() { return totalModeledProcessingCost; }
    /**
     * 返回全部 Job attempt 的 CPU envelope 抽象成本分量。
     *
     * @return CPU envelope 成本之和，可能包含生效的 stage-in MI
     */
    public double getTotalModeledCpuEnvelopeCost() { return totalModeledCpuEnvelopeCost; }
    /**
     * 返回全部 Job attempt 按其声明文件字节数连续计价的带宽抽象成本分量。
     *
     * @return 声明文件带宽成本之和
     */
    public double getTotalModeledDeclaredFileBandwidthCost() {
        return totalModeledDeclaredFileBandwidthCost;
    }
    /**
     * 返回进入声明文件带宽抽象成本的总字节当量。
     *
     * @return 所有 Job 文件表声明字节数之和，不是实际传输字节数
     */
    public double getTotalModeledDeclaredFileBytes() { return totalModeledDeclaredFileBytes; }
    /**
     * 返回按 VM 标识索引的不可变聚合指标。
     *
     * @return VM ID 到每 VM 指标的映射
     */
    public Map<Integer, VmMetrics> getVmMetrics() { return vmMetrics; }
    public double getTotalVmModeledBusyIntervalSeconds() {
        return totalVmModeledBusyIntervalSeconds;
    }
    /**
     * 返回基于建模 Job 区间并集计算的所有 VM 平均利用率。
     *
     * @return 以 makespan 归一化的平均建模区间利用率
     */
    public double getMeanVmModeledIntervalUtilization() {
        return meanVmModeledIntervalUtilization;
    }
    /**
     * 返回各 VM 建模繁忙时间的变异系数（CV）。
     *
     * <p>公式：总体标准差（÷n）/ 均值；均值或空集时为 0.0。由于利用率与繁忙时间
     * 同除常数 makespan，CV(繁忙时间) ≡ CV(利用率)，控制台标签"利用率变异系数"
     * 数值等价。<b>注意</b>：此处用总体标准差（÷n），而 campaign 层
     * {@code MetricSummary} 用样本标准差（÷(n−1)），跨层比较离散度时注意约定差异。</p>
     *
     * @return 繁忙时间变异系数；无 VM 或全零繁忙时为 0.0
     */
    public double getVmModeledBusyTimeCoefficientOfVariation() {
        return vmModeledBusyTimeCoefficientOfVariation;
    }
    /**
     * 返回 VM 利用率的 Jain 公平性指数 ∈ (0,1]。
     *
     * <p>1.0 表示所有 VM 利用率完全相同；与变异系数互补——CV 无法区分
     * "两台各 50%"与"一台 100% 一台 0%"这类形态差异。无 VM 或全零利用率时为 1.0。</p>
     *
     * @return Jain 公平性指数
     */
    public double getVmUtilizationJainFairnessIndex() {
        return vmUtilizationJainFairnessIndex;
    }
    /**
     * 返回受控共享存储关键路径参考是否适用于本次运行的模型范围。
     *
     * @return 关键路径下界参考是否可用
     */
    public boolean isControlledSharedStorageCriticalPathReferenceAvailable() {
        return controlledSharedStorageCriticalPathReferenceAvailable;
    }
    /**
     * 返回受控共享存储关键路径参考的作用域或不可用原因。
     *
     * @return 机器可读的参考作用域状态
     */
    public String getControlledSharedStorageCriticalPathReferenceScope() {
        return controlledSharedStorageCriticalPathReferenceScope;
    }
    /**
     * 返回受控共享存储模型下的关键路径 makespan 下界。
     *
     * @return 以模拟秒表示的下界；参考不可用时为零
     */
    public double getControlledSharedStorageCriticalPathLowerBoundSeconds() {
        return controlledSharedStorageCriticalPathLowerBoundSeconds;
    }
    /**
     * 返回 makespan 与可用关键路径下界的比值。
     *
     * @return 调度长度比；参考不可用或下界为零时为零
     */
    public double getControlledSharedStorageScheduleLengthRatio() {
        return controlledSharedStorageScheduleLengthRatio;
    }
    /**
     * 仅当配置提供正数观测阈值时为 {@code true}。
     *
     * @return 是否请求 deadline 观测
     */
    public boolean isDeadlineObservationEnabled() { return deadlineObservationEnabled; }
    /**
     * 返回 deadline 比较所使用的时间轴。
     *
     * @return 固定为 {@code SIMULATION_END_SECONDS}，即历史 makespan 的时间轴
     */
    public String getDeadlineObservationTimeBasis() { return deadlineObservationTimeBasis; }
    /**
     * 返回 {@code NOT_REQUESTED}、{@code MET}、{@code MISSED_LATE} 或
     * {@code MISSED_INCOMPLETE_WORKFLOW}。
     *
     * @return deadline 观测的机器可读结果
     */
    public String getDeadlineObservationOutcome() { return deadlineObservationOutcome; }
    /**
     * 仅报告 SLA 观测，不会影响调度决策。
     *
     * @return 工作流是否满足已请求的 deadline
     */
    public boolean isDeadlineMet() { return deadlineMet; }
    /**
     * 已启用时为 {@code deadline - makespan}，未请求 deadline 时为零。
     *
     * @return 以模拟秒表示的 deadline 余量
     */
    public double getDeadlineSlackSeconds() { return deadlineSlackSeconds; }
    /**
     * 已启用时为 {@code max(0, makespan - deadline)}，未请求时为零。
     *
     * @return 以模拟秒表示的 deadline 迟延
     */
    public double getDeadlineTardinessSeconds() { return deadlineTardinessSeconds; }
    /**
     * 与其 Job 信封完全相等的单任务计算窗口数。
     *
     * @return 精确任务时序观测数
     */
    public int getExactTaskTimingObservationCount() { return exactTaskTimingObservationCount; }
    /**
     * 与其 Job 信封不同的 Task 窗口数。
     *
     * @return 近似任务时序观测数
     */
    public int getModeledApproximateTaskTimingObservationCount() {
        return modeledApproximateTaskTimingObservationCount;
    }

    /**
     * 每个 VM 的利用率基于其建模 Job 区间的并集。
     *
     * <p>繁忙区间包含<b>失败尝试</b>的 start→finish 包络（失败的执行同样占用
     * 了 VM 的建模资源窗口）；重叠区间经并集合并，不重复计。</p>
     */
    public static final class VmMetrics {
        private final int vmId;
        private final int jobOutcomeCount;
        private final double reportedCpuTimeSeconds;
        private final double modeledBusyIntervalSeconds;
        private final double modeledIntervalUtilization;
        private final double reportedCpuTimeOverMakespan;

        private VmMetrics(int vmId, int jobOutcomeCount, double reportedCpuTimeSeconds,
                double modeledBusyIntervalSeconds, double modeledIntervalUtilization,
                double reportedCpuTimeOverMakespan) {
            this.vmId = vmId;
            this.jobOutcomeCount = jobOutcomeCount;
            this.reportedCpuTimeSeconds = reportedCpuTimeSeconds;
            this.modeledBusyIntervalSeconds = modeledBusyIntervalSeconds;
            this.modeledIntervalUtilization = modeledIntervalUtilization;
            this.reportedCpuTimeOverMakespan = reportedCpuTimeOverMakespan;
        }

        public int getVmId() { return vmId; }
        public int getJobOutcomeCount() { return jobOutcomeCount; }
        public double getReportedCpuTimeSeconds() { return reportedCpuTimeSeconds; }
        public double getModeledBusyIntervalSeconds() { return modeledBusyIntervalSeconds; }
        public double getModeledIntervalUtilization() { return modeledIntervalUtilization; }
        public double getReportedCpuTimeOverMakespan() { return reportedCpuTimeOverMakespan; }
    }

    private static final class MutableVmMetrics {
        private final int vmId;
        private final List<Interval> intervals = new ArrayList<Interval>();
        private int jobs;
        private double cpuTime;

        private MutableVmMetrics(int vmId) {
            this.vmId = vmId;
        }

        private void add(SimulationReport.JobOutcome job) {
            jobs++;
            cpuTime += job.getCpuTime();
            intervals.add(new Interval(job.getStartTime(), job.getFinishTime()));
        }

        private VmMetrics freeze(double makespan) {
            double busy = unionLength(intervals);
            return new VmMetrics(vmId, jobs, cpuTime, busy,
                    makespan > 0.0 ? busy / makespan : 0.0,
                    makespan > 0.0 ? cpuTime / makespan : 0.0);
        }
    }

    private static final class Interval {
        private final double start;
        private final double finish;

        private Interval(double start, double finish) {
            this.start = start;
            this.finish = finish;
        }
    }

    private static double unionLength(List<Interval> intervals) {
        if (intervals.isEmpty()) {
            return 0.0;
        }
        List<Interval> sorted = new ArrayList<Interval>(intervals);
        Collections.sort(sorted, new Comparator<Interval>() {
            @Override
            public int compare(Interval left, Interval right) {
                return Double.compare(left.start, right.start);
            }
        });
        double start = sorted.get(0).start;
        double finish = Math.max(start, sorted.get(0).finish);
        double result = 0.0;
        for (int index = 1; index < sorted.size(); index++) {
            Interval interval = sorted.get(index);
            if (interval.start > finish) {
                result += nonNegative(finish - start);
                start = interval.start;
                finish = Math.max(interval.start, interval.finish);
            } else {
                finish = Math.max(finish, interval.finish);
            }
        }
        return result + nonNegative(finish - start);
    }

    private static double coefficientOfVariation(List<Double> values) {
        if (values.isEmpty()) {
            return 0.0;
        }
        double sum = 0.0;
        for (Double value : values) {
            sum += value.doubleValue();
        }
        double average = sum / values.size();
        if (average == 0.0) {
            return 0.0;
        }
        double squared = 0.0;
        for (Double value : values) {
            double difference = value.doubleValue() - average;
            squared += difference * difference;
        }
        return Math.sqrt(squared / values.size()) / average;
    }

    /**
     * 最近秩法百分位数。
     *
     * <p>样本必须已升序排序；rank = ceil(fraction × n)，fraction=1.0 时即最大值。
     * 空样本返回 0.0（与其余指标的空集约定一致）。</p>
     *
     * @param sortedSamples 已升序排序的样本
     * @param fraction 目标分位（0.5 中位数 / 0.95 P95 / 1.0 最大值）
     * @return 对应分位值
     */
    private static double percentile(List<Double> sortedSamples, double fraction) {
        if (sortedSamples.isEmpty()) {
            return 0.0;
        }
        int rank = (int) Math.ceil(fraction * sortedSamples.size());
        return sortedSamples.get(Math.max(1, rank) - 1).doubleValue();
    }

    /**
     * Jain 公平性指数：(Σu)² / (n·Σu²)。
     *
     * <p>值域 (0,1]，1.0 表示完全公平（所有 VM 利用率相同）。与 CV 互补：
     * CV 无法区分"两台各 50%"与"一台 100% 一台 0%"这类形态差异。
     * 空列表或全零利用率返回 1.0（零负载视为完全公平）。</p>
     *
     * @param utilizations 各 VM 的利用率样本（≥0）
     * @return Jain 公平性指数
     */
    private static double jainFairnessIndex(List<Double> utilizations) {
        if (utilizations.isEmpty()) {
            return 1.0;
        }
        double sum = 0.0;
        double sumSquared = 0.0;
        for (Double value : utilizations) {
            double u = value.doubleValue();
            sum += u;
            sumSquared += u * u;
        }
        if (sumSquared <= 0.0) {
            return 1.0;
        }
        return (sum * sum) / (utilizations.size() * sumSquared);
    }

    /** 提取每台 VM 的建模区间利用率，供公平性指数计算。 */
    private static List<Double> vmUtilizations(Map<Integer, VmMetrics> frozenVm) {
        List<Double> result = new ArrayList<Double>();
        for (VmMetrics metrics : frozenVm.values()) {
            result.add(Double.valueOf(metrics.getModeledIntervalUtilization()));
        }
        return result;
    }

    private static Set<Integer> logicalTaskIds(List<Task> sourceTasks,
            List<SimulationReport.TaskOutcome> outcomes) {
        Set<Integer> result = new HashSet<Integer>();
        if (sourceTasks != null) {
            for (Task task : sourceTasks) {
                if (task != null) {
                    result.add(task.getCloudletId());
                }
            }
        }
        if (result.isEmpty() && outcomes != null) {
            for (SimulationReport.TaskOutcome outcome : outcomes) {
                result.add(outcome.getTaskId());
            }
        }
        return result;
    }

    /** 建立 Job outcome 的唯一 ID 索引，避免下游指标静默覆盖执行尝试。 */
    private static Map<Integer, SimulationReport.JobOutcome> jobOutcomesById(
            List<SimulationReport.JobOutcome> jobs) {
        Map<Integer, SimulationReport.JobOutcome> result =
                new LinkedHashMap<Integer, SimulationReport.JobOutcome>();
        if (jobs == null) {
            throw new IllegalArgumentException("Job outcomes cannot be null");
        }
        for (SimulationReport.JobOutcome job : jobs) {
            if (job == null) {
                throw new IllegalStateException("Simulation metrics received a null Job outcome");
            }
            if (result.put(job.getJobId(), job) != null) {
                throw new IllegalStateException("Simulation metrics received duplicate Job outcome ID "
                        + job.getJobId());
            }
        }
        return result;
    }

    /**
     * 从 retry 创建事件恢复并验证 retry Job 与其失败父 attempt 的证据关系。
     *
     * <p>报告捕获时所有 completed Job 都已可用，因此任何 retry 事件没有对应 Job outcome
     * 都表示证据包不完整，而不是一个可忽略的部分观测。</p>
     */
    private static Set<Integer> retryJobIds(List<SimulationEvent> events,
            Map<Integer, SimulationReport.JobOutcome> jobsById) {
        Set<Integer> result = new HashSet<Integer>();
        if (events == null) {
            throw new IllegalArgumentException("Simulation events cannot be null");
        }
        for (SimulationEvent event : events) {
            if (event == null) {
                throw new IllegalStateException("Simulation metrics received a null event");
            }
            if (event.getType() != SimulationEventType.RETRY_JOB_CREATED) {
                continue;
            }
            Integer retryJobId = event.getJobId();
            if (retryJobId == null) {
                throw new IllegalStateException("Retry creation event is missing its retry Job ID");
            }
            if (!result.add(retryJobId)) {
                throw new IllegalStateException("Retry creation events reuse Job ID " + retryJobId);
            }
            SimulationReport.JobOutcome retry = jobsById.get(retryJobId);
            if (retry == null) {
                throw new IllegalStateException("Retry creation event has no completed Job outcome for "
                        + retryJobId);
            }
            if (retry.getClassType() != Parameters.ClassType.COMPUTE.value) {
                throw new IllegalStateException("Retry Job " + retryJobId
                        + " is not a compute Job outcome");
            }
            int failedJobId = requiredIntegerAttribute(event, "failedJobId", "Retry creation event");
            if (failedJobId == retryJobId.intValue()) {
                throw new IllegalStateException("Retry Job " + retryJobId + " cannot be its own failed parent");
            }
            SimulationReport.JobOutcome failed = jobsById.get(Integer.valueOf(failedJobId));
            if (failed == null) {
                throw new IllegalStateException("Retry Job " + retryJobId
                        + " references missing failed Job " + failedJobId);
            }
            if (failed.getStatus() != Cloudlet.FAILED) {
                throw new IllegalStateException("Retry Job " + retryJobId
                        + " references a parent that is not failed: " + failedJobId);
            }
        }
        return result;
    }

    /** 以成功 compute Job 的完成时间作为逻辑 Task 的可用完成边界。 */
    private static Map<Integer, Double> earliestSuccessfulJobFinishByLogicalTask(
            List<SimulationReport.JobOutcome> jobs, Set<Integer> logicalTaskIds) {
        Map<Integer, Double> result = new LinkedHashMap<Integer, Double>();
        for (SimulationReport.JobOutcome job : jobs) {
            if (job.getClassType() != Parameters.ClassType.COMPUTE.value
                    || job.getStatus() != Cloudlet.SUCCESS) {
                continue;
            }
            for (Integer taskId : job.getTaskIds()) {
                if (!logicalTaskIds.contains(taskId)) {
                    continue;
                }
                Double previous = result.get(taskId);
                if (previous == null || job.getFinishTime() < previous.doubleValue()) {
                    result.put(taskId, Double.valueOf(job.getFinishTime()));
                }
            }
        }
        return result;
    }

    private static double latestFinish(Map<Integer, Double> finishes) {
        if (finishes.isEmpty()) {
            throw new IllegalStateException("Cannot calculate logical completion from no successful Tasks");
        }
        double result = Double.NEGATIVE_INFINITY;
        for (Double finish : finishes.values()) {
            if (finish == null || Double.isNaN(finish.doubleValue())
                    || Double.isInfinite(finish.doubleValue())) {
                throw new IllegalStateException("Logical Task completion evidence has invalid Job finish time");
            }
            result = Math.max(result, finish.doubleValue());
        }
        return result;
    }

    private static int logicalTaskAttemptCount(List<SimulationReport.TaskOutcome> outcomes,
            Map<Integer, SimulationReport.JobOutcome> jobsById, Set<Integer> logicalTaskIds) {
        int result = 0;
        if (outcomes == null) {
            throw new IllegalArgumentException("Task outcomes cannot be null");
        }
        for (SimulationReport.TaskOutcome outcome : outcomes) {
            if (outcome == null) {
                throw new IllegalStateException("Simulation metrics received a null Task outcome");
            }
            SimulationReport.JobOutcome job = jobsById.get(Integer.valueOf(outcome.getJobId()));
            if (job == null) {
                throw new IllegalStateException("Task outcome " + outcome.getTaskId()
                        + " references missing Job outcome " + outcome.getJobId());
            }
            if (job.getClassType() == Parameters.ClassType.COMPUTE.value
                    && logicalTaskIds.contains(Integer.valueOf(outcome.getTaskId()))) {
                result++;
            }
        }
        return result;
    }

    private static int retriedLogicalTaskCount(Set<Integer> retryJobIds,
            Map<Integer, SimulationReport.JobOutcome> jobsById, Set<Integer> logicalTaskIds) {
        Set<Integer> result = new HashSet<Integer>();
        for (Integer retryJobId : retryJobIds) {
            SimulationReport.JobOutcome retry = jobsById.get(retryJobId);
            for (Integer taskId : retry.getTaskIds()) {
                if (logicalTaskIds.contains(taskId)) {
                    result.add(taskId);
                }
            }
        }
        return result.size();
    }

    private static double ratio(int numerator, int denominator) {
        return denominator == 0 ? 0.0 : ((double) numerator) / denominator;
    }

    private static double requiredFiniteNonNegativeAttribute(SimulationEvent event, String key) {
        Object value = event.getAttributes().get(key);
        if (!(value instanceof Number)) {
            throw new IllegalStateException("Data-stage-in event is missing numeric " + key);
        }
        double number = ((Number) value).doubleValue();
        if (number < 0.0 || Double.isNaN(number) || Double.isInfinite(number)) {
            throw new IllegalStateException("Data-stage-in event has invalid " + key);
        }
        return number;
    }

    private static int requiredNonNegativeIntegerAttribute(SimulationEvent event, String key) {
        double value = requiredFiniteNonNegativeAttribute(event, key);
        if (value > Integer.MAX_VALUE || Math.rint(value) != value) {
            throw new IllegalStateException("Data-stage-in event has invalid integer " + key);
        }
        return (int) value;
    }

    private static int requiredIntegerAttribute(SimulationEvent event, String key, String subject) {
        Object value = event.getAttributes().get(key);
        if (!(value instanceof Number)) {
            throw new IllegalStateException(subject + " is missing numeric " + key);
        }
        double number = ((Number) value).doubleValue();
        if (number < Integer.MIN_VALUE || number > Integer.MAX_VALUE
                || Math.rint(number) != number || Double.isNaN(number)
                || Double.isInfinite(number)) {
            throw new IllegalStateException(subject + " has invalid integer " + key);
        }
        return (int) number;
    }

    private static long requiredNonNegativeLongAttribute(SimulationEvent event, String key) {
        Object value = event.getAttributes().get(key);
        if (!(value instanceof Number)) {
            throw new IllegalStateException("Planning event is missing numeric " + key);
        }
        double number = ((Number) value).doubleValue();
        if (number < 0.0 || number > Long.MAX_VALUE || Math.rint(number) != number
                || Double.isNaN(number) || Double.isInfinite(number)) {
            throw new IllegalStateException("Planning event has invalid " + key);
        }
        return ((Number) value).longValue();
    }

    private static double mean(double sum, int count) {
        return count == 0 ? 0.0 : sum / count;
    }

    private static String deadlineOutcome(boolean enabled, boolean workflowCompleted,
            double makespan, long deadline) {
        if (!enabled) {
            return "NOT_REQUESTED";
        }
        if (!workflowCompleted) {
            return "MISSED_INCOMPLETE_WORKFLOW";
        }
        return makespan <= deadline ? "MET" : "MISSED_LATE";
    }

    private static double nonNegative(double value) {
        return value < 0.0 ? 0.0 : value;
    }

    /**
     * 要求值为有限数（非 NaN、非无穷大），否则抛出 IllegalStateException。
     *
     * @param value 待检查的数值
     * @param description 值的描述，用于错误信息
     * @throws IllegalStateException 当值为 NaN 或无穷大时
     */
    private static void requireFinite(double value, String description) {
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            throw new IllegalStateException(description + " must be finite, was: " + value);
        }
    }
}
