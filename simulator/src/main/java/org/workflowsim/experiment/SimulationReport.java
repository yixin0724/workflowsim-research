package org.workflowsim.experiment;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import org.cloudbus.cloudsim.Cloudlet;
import org.workflowsim.Job;
import org.workflowsim.Task;
import org.workflowsim.WorkflowInputReport;
import org.workflowsim.planning.SharedStorageDagPlanTrace;
import org.workflowsim.platform.PlatformProfile;
import org.workflowsim.utils.SimulationConfig;

/** 一次已完成 WorkflowSim 运行的不可变证据记录。 */
public final class SimulationReport {

    private final SimulationConfig config;
    private final PlatformProfile platform;
    private final double makespan;
    private final List<InputArtifact> inputs;
    private final List<WorkflowInputReport> inputReports;
    private final List<JobOutcome> jobs;
    private final List<TaskOutcome> tasks;
    private final Map<Integer, VmSummary> vmSummaries;
    private final Map<Integer, Integer> actualVmHostAssignments;
    private final int successfulJobs;
    private final int failedJobs;
    private final List<SimulationEvent> events;
    private final WorkflowProfile workflowProfile;
    private final SimulationMetrics metrics;
    private final SharedStorageDagPlanTrace sharedStorageDagPlanTrace;
    private final List<WorkflowOutcome> workflowOutcomes;

    private SimulationReport(SimulationConfig config, PlatformProfile platform,
            double makespan, List<InputArtifact> inputs, List<WorkflowInputReport> inputReports,
            List<JobOutcome> jobs, List<TaskOutcome> tasks, Map<Integer, VmSummary> vmSummaries,
            Map<Integer, Integer> actualVmHostAssignments, int successfulJobs, int failedJobs,
            List<SimulationEvent> events, List<Task> sourceTasks,
            SharedStorageDagPlanTrace sharedStorageDagPlanTrace) {
        this.config = config;
        this.platform = platform;
        this.makespan = makespan;
        this.inputs = Collections.unmodifiableList(new ArrayList<>(inputs));
        this.inputReports = Collections.unmodifiableList(new ArrayList<>(inputReports));
        this.jobs = Collections.unmodifiableList(new ArrayList<>(jobs));
        this.tasks = Collections.unmodifiableList(new ArrayList<>(tasks));
        this.vmSummaries = Collections.unmodifiableMap(new LinkedHashMap<>(vmSummaries));
        this.actualVmHostAssignments = Collections.unmodifiableMap(
                new LinkedHashMap<Integer, Integer>(actualVmHostAssignments));
        this.successfulJobs = successfulJobs;
        this.failedJobs = failedJobs;
        this.events = Collections.unmodifiableList(new ArrayList<>(events));
        this.sharedStorageDagPlanTrace = sharedStorageDagPlanTrace;
        this.workflowProfile = WorkflowProfile.fromTasks(sourceTasks);
        this.metrics = SimulationMetrics.calculate(makespan, this.jobs, this.vmSummaries, this.events,
                this.tasks, sourceTasks, config, platform);
        this.workflowOutcomes = computeWorkflowOutcomes();
    }

    /**
     * R5 动态到达：按输入下标计算每个工作流的到达/完成/流时证据。
     *
     * <p>任务编号在输入间连续分配，任务区间由各输入报告的 taskCount 推导；流时取该工作流
     * 全部成功任务的最大完成时刻减去其配置提交时刻。没有任何成功任务时流时为 {@code NaN}。</p>
     */
    private List<WorkflowOutcome> computeWorkflowOutcomes() {
        List<Double> arrivals = config.getWorkflowArrivalSeconds();
        List<WorkflowOutcome> outcomes = new ArrayList<>();
        int firstTaskId = 1;
        for (int i = 0; i < inputReports.size(); i++) {
            int taskCount = inputReports.get(i).getTaskCount();
            int lastTaskId = firstTaskId + taskCount - 1;
            double arrivalSecond = (arrivals != null && i < arrivals.size())
                    ? arrivals.get(i) : 0.0;
            double lastSuccessFinishSecond = Double.NaN;
            for (TaskOutcome task : tasks) {
                if (task.getTaskId() >= firstTaskId && task.getTaskId() <= lastTaskId
                        && task.getTaskStatus() == Cloudlet.SUCCESS
                        && (Double.isNaN(lastSuccessFinishSecond)
                                || task.getFinishTime() > lastSuccessFinishSecond)) {
                    lastSuccessFinishSecond = task.getFinishTime();
                }
            }
            outcomes.add(new WorkflowOutcome(i, config.getWorkflowPaths().get(i),
                    arrivalSecond, firstTaskId, lastTaskId, taskCount, lastSuccessFinishSecond,
                    Double.isNaN(lastSuccessFinishSecond) ? Double.NaN
                            : lastSuccessFinishSecond - arrivalSecond));
            firstTaskId = lastTaskId + 1;
        }
        return Collections.unmodifiableList(outcomes);
    }

    static SimulationReport capture(SimulationConfig config, PlatformProfile platform,
            double makespan, List<WorkflowInputReport> inputReports, List<Job> completedJobs,
            List<SimulationEvent> events, List<Task> sourceTasks,
            SharedStorageDagPlanTrace sharedStorageDagPlanTrace,
            Map<Integer, Integer> actualVmHostAssignments)
            throws IOException {
        if (inputReports == null || inputReports.size() != config.getWorkflowPaths().size()) {
            throw new IllegalStateException("Completed simulation has "
                    + (inputReports == null ? 0 : inputReports.size())
                    + " parsed input report(s) for " + config.getWorkflowPaths().size()
                    + " configured input(s)");
        }
        if (actualVmHostAssignments == null
                || actualVmHostAssignments.size() != platform.getVms().size()) {
            throw new IllegalStateException("Completed simulation has incomplete VM-to-Host allocation evidence");
        }
        List<InputArtifact> inputs = new ArrayList<>();
        for (String path : config.getWorkflowPaths()) {
            inputs.add(InputArtifact.fromPath(path));
        }

        List<JobOutcome> jobs = new ArrayList<>();
        List<TaskOutcome> tasks = new ArrayList<>();
        Map<Integer, MutableVmSummary> mutableVmSummaries = new TreeMap<>();
        for (PlatformProfile.VmSpec vm : platform.getVms()) {
            mutableVmSummaries.put(vm.getId(), new MutableVmSummary(vm.getId()));
        }
        int successes = 0;
        int failures = 0;
        Set<Integer> seenJobIds = new HashSet<>();
        for (Job job : completedJobs) {
            JobOutcome outcome = JobOutcome.fromJob(job);
            if (!seenJobIds.add(outcome.getJobId())) {
                throw new IllegalStateException("Completed simulation contains duplicate Job ID "
                        + outcome.getJobId());
            }
            jobs.add(outcome);
            tasks.addAll(TaskOutcome.fromJob(job));
            if (outcome.getStatus() == Cloudlet.SUCCESS) {
                successes++;
            } else if (outcome.getStatus() == Cloudlet.FAILED) {
                failures++;
            }
            MutableVmSummary summary = mutableVmSummaries.get(outcome.getVmId());
            if (summary == null) {
                throw new IllegalStateException("Completed job " + outcome.getJobId()
                        + " reports undeclared VM " + outcome.getVmId());
            }
            summary.add(outcome);
        }

        Map<Integer, VmSummary> vmSummaries = new LinkedHashMap<>();
        for (Map.Entry<Integer, MutableVmSummary> entry : mutableVmSummaries.entrySet()) {
            vmSummaries.put(entry.getKey(), entry.getValue().freeze());
        }
        return new SimulationReport(config, platform, makespan, inputs, inputReports,
                jobs, tasks, vmSummaries, actualVmHostAssignments, successes, failures,
                events == null ? Collections.<SimulationEvent>emptyList() : events, sourceTasks,
                sharedStorageDagPlanTrace);
    }

    public SimulationConfig getConfig() { return config; }
    public PlatformProfile getPlatform() { return platform; }
    /**
     * 返回兼容历史 API 的 CloudSim 仿真结束时钟。
     *
     * <p>该值可能包含末个 Job 返回后的 workflow-engine post-delay；研究代码需要最后一个
     * 逻辑 Task 的成功 Job envelope 完成时刻时，应使用
     * {@link #getLogicalTaskCompletionSeconds()}。</p>
     *
     * @return 以模拟秒表示的仿真结束时间
     */
    public double getMakespan() { return makespan; }
    /**
     * 返回本次运行的 CloudSim 仿真结束时间。
     *
     * @return 与历史 {@link #getMakespan()} 完全相同的模拟秒值
     */
    public double getSimulationEndSeconds() { return makespan; }
    /**
     * 返回全部解析逻辑 Task 首次由成功 Job 完成的最大 Job envelope 结束时间。
     *
     * @return 全部逻辑 Task 成功时的完成秒数；否则为 {@code null}
     */
    public Double getLogicalTaskCompletionSeconds() {
        return metrics.getLogicalTaskCompletionSeconds();
    }
    /** @return 逻辑 Task 完成时间是否可用的机器可读状态 */
    public String getLogicalTaskCompletionStatus() {
        return metrics.getLogicalTaskCompletionStatus();
    }
    /**
     * 返回最后一个逻辑 Task 成功完成到 CloudSim 仿真结束之间的生命周期尾部。
     *
     * @return 工作流完整时的非负模拟秒数；否则为 {@code null}
     */
    public Double getTerminalLifecycleTailSeconds() {
        return metrics.getTerminalLifecycleTailSeconds();
    }
    /** @return 本次运行是否使全部逻辑 Task 成功完成 */
    public boolean isWorkflowCompletedSuccessfully() {
        return metrics.isWorkflowCompletedSuccessfully();
    }
    /**
     * 返回本次运行实际消费的输入文件指纹。
     *
     * @return 按配置顺序排列的不可变输入指纹列表
     */
    public List<InputArtifact> getInputs() { return inputs; }
    /**
     * 返回解析器针对每个配置输入给出的验证报告。
     *
     * @return 按配置顺序排列的不可变输入报告列表
     */
    public List<WorkflowInputReport> getInputReports() { return inputReports; }
    public List<JobOutcome> getJobs() { return jobs; }
    public List<TaskOutcome> getTasks() { return tasks; }

    /**
     * R5 动态到达：按输入下标返回每个工作流的到达/流时证据。
     *
     * @return 与 {@link #getInputReports()} 同序的每工作流结果
     */
    public List<WorkflowOutcome> getWorkflowOutcomes() { return workflowOutcomes; }
    public Map<Integer, VmSummary> getVmSummaries() { return vmSummaries; }
    /**
     * 已与平台预检结果比对过的实际 CloudSim VM 到 Host 放置。
     *
     * @return 不可变的完整 VM-Host 实际放置映射
     */
    public Map<Integer, Integer> getActualVmHostAssignments() {
        return actualVmHostAssignments;
    }
    public int getTotalJobs() { return jobs.size(); }
    public int getSuccessfulJobs() { return successfulJobs; }
    public int getFailedJobs() { return failedJobs; }
    public List<SimulationEvent> getEvents() { return events; }
    /**
     * 返回由本次运行解析出的任务快照计算的工作流结构摘要。
     *
     * @return 不可变的工作流画像
     */
    public WorkflowProfile getWorkflowProfile() { return workflowProfile; }
    /**
     * 返回由运行证据推导出的模型层指标。
     *
     * @return 不可变的仿真指标集合
     */
    public SimulationMetrics getMetrics() { return metrics; }
    /**
     * 返回共享存储静态 DAG 规划的追踪记录。
     *
     * <p>没有采用相应规划路径时，该值可以为 {@code null}；调用方须先根据配置和算法契约
     * 判断其是否适用。</p>
     *
     * @return 规划追踪记录，或 {@code null}
     */
    public SharedStorageDagPlanTrace getSharedStorageDagPlanTrace() {
        return sharedStorageDagPlanTrace;
    }

    /** 已消费工作流文件的不可变内容指纹。 */
    public static final class InputArtifact {

        private final String path;
        private final String sha256;
        private final long sizeBytes;

        private InputArtifact(String path, String sha256, long sizeBytes) {
            this.path = path;
            this.sha256 = sha256;
            this.sizeBytes = sizeBytes;
        }

        private static InputArtifact fromPath(String input) throws IOException {
            Path path = java.nio.file.Paths.get(input).toAbsolutePath().normalize();
            if (!Files.isRegularFile(path)) {
                throw new IOException("Workflow input cannot be fingerprinted: " + path);
            }
            return new InputArtifact(path.toString(), sha256(path), Files.size(path));
        }

        public String getPath() { return path; }
        public String getSha256() { return sha256; }
        public long getSizeBytes() { return sizeBytes; }
    }

    /** 一个 WorkflowSim Job 的不可变执行结果。 */
    public static final class JobOutcome {

        private final int jobId;
        private final int vmId;
        private final int status;
        private final int classType;
        private final double submissionTime;
        private final double startTime;
        private final double finishTime;
        private final double waitingTime;
        private final double executionTime;
        private final double responseTime;
        private final double cpuTime;
        private final double modeledCpuEnvelopeCost;
        private final double modeledDeclaredFileBandwidthCost;
        private final double modeledDeclaredFileBytes;
        private final double modeledProcessingCost;
        private final int taskCount;
        private final List<Integer> taskIds;

        JobOutcome(int jobId, int vmId, int status, int classType, double submissionTime,
                double startTime, double finishTime, double cpuTime, double modeledProcessingCost,
                int taskCount, List<Integer> taskIds) {
            this(jobId, vmId, status, classType, submissionTime, startTime, finishTime, cpuTime,
                    modeledProcessingCost, 0.0, 0.0, taskCount, taskIds);
        }

        JobOutcome(int jobId, int vmId, int status, int classType, double submissionTime,
                double startTime, double finishTime, double cpuTime, double modeledCpuEnvelopeCost,
                double modeledDeclaredFileBandwidthCost, double modeledDeclaredFileBytes,
                int taskCount, List<Integer> taskIds) {
            this.jobId = jobId;
            this.vmId = vmId;
            this.status = status;
            this.classType = classType;
            this.submissionTime = submissionTime;
            this.startTime = startTime;
            this.finishTime = finishTime;
            // 边界保护：失败/异常作业的时间戳可能不完整，负值裁剪为 0
            this.waitingTime = Math.max(0.0, startTime - submissionTime);
            this.executionTime = Math.max(0.0, finishTime - startTime);
            this.responseTime = Math.max(0.0, finishTime - submissionTime);
            this.cpuTime = cpuTime;
            this.modeledCpuEnvelopeCost = modeledCpuEnvelopeCost;
            this.modeledDeclaredFileBandwidthCost = modeledDeclaredFileBandwidthCost;
            this.modeledDeclaredFileBytes = modeledDeclaredFileBytes;
            this.modeledProcessingCost = modeledCpuEnvelopeCost + modeledDeclaredFileBandwidthCost;
            this.taskCount = taskCount;
            this.taskIds = Collections.unmodifiableList(new ArrayList<>(taskIds));
        }

        private static JobOutcome fromJob(Job job) {
            List<Integer> taskIds = new ArrayList<>();
            for (org.workflowsim.Task task : job.getTaskList()) {
                taskIds.add(task.getCloudletId());
            }
            return new JobOutcome(job.getCloudletId(), job.getVmId(), job.getCloudletStatus(),
                    job.getClassType(), job.getSubmissionTime(), job.getExecStartTime(), 
                    job.getFinishTime(), job.getActualCPUTime(), job.getModeledCpuEnvelopeCost(),
                    job.getModeledDeclaredFileBandwidthCost(), job.getModeledDeclaredFileBytes(),
                    job.getTaskList().size(), taskIds);
        }

        public int getJobId() { return jobId; }
        public int getVmId() { return vmId; }
        public int getStatus() { return status; }
        public int getClassType() { return classType; }
        public double getSubmissionTime() { return submissionTime; }
        public double getStartTime() { return startTime; }
        public double getFinishTime() { return finishTime; }
        /**
         * 返回 VM 队列等待时间（到达 VM 到开始执行，即 startTime - submissionTime）。
         * <p><strong>注意：</strong>WorkflowSim 的所有调度算法都只向空闲 VM 派发作业，
         * 因此该值几乎总是 0。真实的调度等待时间（就绪到开始）见
         * {@code SimulationMetrics#getMeanComputeTotalWaitingTimeSeconds()}。</p>
         */
        public double getWaitingTime() { return waitingTime; }
        public double getExecutionTime() { return executionTime; }
        public double getResponseTime() { return responseTime; }
        /**
         * CloudSim 报告的该 Job 信封执行时间。启用 stage-in 时，该值可能包括相应延迟的
         * 整数 MI 表示；它不是纯粹的实测 CPU 时间观测。
         *
         * @return 以模拟秒表示的 Job 信封执行时间
         */
        public double getCpuTime() { return cpuTime; }
        /**
         * Job envelope 的 CPU 抽象成本分量。
         *
         * @return 可能包含 CloudSim stage-in MI 注入的 CPU envelope 成本
         */
        public double getModeledCpuEnvelopeCost() { return modeledCpuEnvelopeCost; }
        /**
         * 按 Job 全部声明文件字节数、每十进制 MB 连续计价的带宽抽象成本分量。
         *
         * @return 声明文件带宽成本近似
         */
        public double getModeledDeclaredFileBandwidthCost() {
            return modeledDeclaredFileBandwidthCost;
        }
        /**
         * 进入声明文件带宽成本的连续字节当量。
         *
         * @return Job 文件表中所有声明文件大小之和
         */
        public double getModeledDeclaredFileBytes() { return modeledDeclaredFileBytes; }
        /**
         * 同一 Job 信封的 CloudSim 处理成本。该值既不是服务商资费回放，也不是单独计价的
         * 网络传输成本。
         *
         * @return 该 Job 的建模处理成本
         */
        public double getModeledProcessingCost() { return modeledProcessingCost; }
        public int getTaskCount() { return taskCount; }
        public List<Integer> getTaskIds() { return taskIds; }
    }

    /**
     * 不可变的任务级观测。一个任务可以出现在多个重试 Job 中；任一包含该任务的成功 Job 都会
     * 使该任务被视为成功完成。失败 Job 保留其任务级状态，以便故障与重聚类记录仍可观察。
     * 逻辑 Task 时序是提交时记录的模型推导计算窗口。只有单任务 Job 的该窗口与完成后的
     * CloudSim Job 信封相等时，才标为精确；建模 stage-in 使这两个范围保持区分。
     */
    public static final class TaskOutcome {

        private final int taskId;
        private final int jobId;
        private final int vmId;
        private final int jobStatus;
        private final int taskStatus;
        private final int depth;
        private final long lengthMi;
        private final double startTime;
        private final double finishTime;
        private final boolean exactJobTiming;

        TaskOutcome(int taskId, int jobId, int vmId, int jobStatus, int taskStatus,
                int depth, long lengthMi, double startTime, double finishTime) {
            this(taskId, jobId, vmId, jobStatus, taskStatus, depth, lengthMi,
                    startTime, finishTime, true);
        }

        TaskOutcome(int taskId, int jobId, int vmId, int jobStatus, int taskStatus,
                int depth, long lengthMi, double startTime, double finishTime,
                boolean exactJobTiming) {
            this.taskId = taskId;
            this.jobId = jobId;
            this.vmId = vmId;
            this.jobStatus = jobStatus;
            this.taskStatus = taskStatus;
            this.depth = depth;
            this.lengthMi = lengthMi;
            this.startTime = startTime;
            this.finishTime = finishTime;
            this.exactJobTiming = exactJobTiming;
        }

        static List<TaskOutcome> fromJob(Job job) {
            List<TaskOutcome> results = new ArrayList<>();
            for (org.workflowsim.Task task : job.getTaskList()) {
                boolean exactJobTiming = job.getTaskList().size() == 1
                        && Double.compare(task.getExecStartTime(), job.getExecStartTime()) == 0
                        && Double.compare(task.getTaskFinishTime(), job.getFinishTime()) == 0;
                results.add(new TaskOutcome(task.getCloudletId(), job.getCloudletId(),
                        job.getVmId(), job.getCloudletStatus(), job.getCloudletStatus()
                                == Cloudlet.SUCCESS ? Cloudlet.SUCCESS : task.getCloudletStatus(),
                        task.getDepth(), task.getCloudletLength(), task.getExecStartTime(),
                        task.getTaskFinishTime(), exactJobTiming));
            }
            return results;
        }

        public int getTaskId() { return taskId; }
        public int getJobId() { return jobId; }
        public int getVmId() { return vmId; }
        public int getJobStatus() { return jobStatus; }
        public int getTaskStatus() { return taskStatus; }
        public int getDepth() { return depth; }
        public long getLengthMi() { return lengthMi; }
        public double getStartTime() { return startTime; }
        public double getFinishTime() { return finishTime; }
        /**
         * 仅当单任务计算窗口等于 CloudSim Job 信封时为 {@code true}。
         *
         * @return 此任务是否具有精确 Job 时序
         */
        public boolean hasExactJobTiming() { return exactJobTiming; }
    }

    /** R5 动态到达：单个工作流输入的到达时刻、任务区间与流时证据。 */
    public static final class WorkflowOutcome {

        private final int index;
        private final String path;
        private final double arrivalSecond;
        private final int firstTaskId;
        private final int lastTaskId;
        private final int taskCount;
        private final double lastSuccessFinishSecond;
        private final double flowTimeSeconds;

        WorkflowOutcome(int index, String path, double arrivalSecond, int firstTaskId,
                int lastTaskId, int taskCount, double lastSuccessFinishSecond,
                double flowTimeSeconds) {
            this.index = index;
            this.path = path;
            this.arrivalSecond = arrivalSecond;
            this.firstTaskId = firstTaskId;
            this.lastTaskId = lastTaskId;
            this.taskCount = taskCount;
            this.lastSuccessFinishSecond = lastSuccessFinishSecond;
            this.flowTimeSeconds = flowTimeSeconds;
        }

        /** @return 输入在本次提交中的下标（0 起） */
        public int getIndex() { return index; }
        /** @return 输入文件路径 */
        public String getPath() { return path; }
        /** @return 配置的提交时刻（模拟秒） */
        public double getArrivalSecond() { return arrivalSecond; }
        /** @return 该输入的首个任务编号 */
        public int getFirstTaskId() { return firstTaskId; }
        /** @return 该输入的末个任务编号 */
        public int getLastTaskId() { return lastTaskId; }
        /** @return 该输入的任务数量 */
        public int getTaskCount() { return taskCount; }
        /** @return 全部成功任务的最大完成时刻；无成功任务时为 {@code NaN} */
        public double getLastSuccessFinishSecond() { return lastSuccessFinishSecond; }
        /** @return 流时 = 最大成功完成时刻 − 提交时刻；无成功任务时为 {@code NaN} */
        public double getFlowTimeSeconds() { return flowTimeSeconds; }
    }

    /** 基于已完成 Job 的每 VM 聚合值，不是 Host 利用率模型。 */
    public static final class VmSummary {

        private final int vmId;
        private final int jobs;
        private final int successfulJobs;
        private final int failedJobs;
        private final double totalCpuTime;
        private final double lastFinishTime;

        VmSummary(int vmId, int jobs, int successfulJobs, int failedJobs,
                double totalCpuTime, double lastFinishTime) {
            this.vmId = vmId;
            this.jobs = jobs;
            this.successfulJobs = successfulJobs;
            this.failedJobs = failedJobs;
            this.totalCpuTime = totalCpuTime;
            this.lastFinishTime = lastFinishTime;
        }

        public int getVmId() { return vmId; }
        public int getJobs() { return jobs; }
        public int getSuccessfulJobs() { return successfulJobs; }
        public int getFailedJobs() { return failedJobs; }
        public double getTotalCpuTime() { return totalCpuTime; }
        public double getLastFinishTime() { return lastFinishTime; }

        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            if (!(other instanceof VmSummary)) {
                return false;
            }
            VmSummary that = (VmSummary) other;
            return vmId == that.vmId && jobs == that.jobs
                    && successfulJobs == that.successfulJobs
                    && failedJobs == that.failedJobs
                    && Double.compare(totalCpuTime, that.totalCpuTime) == 0
                    && Double.compare(lastFinishTime, that.lastFinishTime) == 0;
        }

        @Override
        public int hashCode() {
            int result = Integer.hashCode(vmId);
            result = 31 * result + jobs;
            result = 31 * result + successfulJobs;
            result = 31 * result + failedJobs;
            long cpuBits = Double.doubleToLongBits(totalCpuTime);
            result = 31 * result + (int) (cpuBits ^ (cpuBits >>> 32));
            long finishBits = Double.doubleToLongBits(lastFinishTime);
            result = 31 * result + (int) (finishBits ^ (finishBits >>> 32));
            return result;
        }
    }

    private static final class MutableVmSummary {

        private final int vmId;
        private int jobs;
        private int successes;
        private int failures;
        private double cpuTime;
        private double lastFinish;

        private MutableVmSummary(int vmId) {
            this.vmId = vmId;
        }

        private void add(JobOutcome job) {
            jobs++;
            cpuTime += job.getCpuTime();
            lastFinish = Math.max(lastFinish, job.getFinishTime());
            if (job.getStatus() == Cloudlet.SUCCESS) {
                successes++;
            } else if (job.getStatus() == Cloudlet.FAILED) {
                failures++;
            }
        }

        private VmSummary freeze() {
            return new VmSummary(vmId, jobs, successes, failures, cpuTime, lastFinish);
        }
    }

    private static String sha256(Path path) throws IOException {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("JDK does not provide SHA-256", exception);
        }
        byte[] buffer = new byte[8192];
        try (InputStream input = Files.newInputStream(path)) {
            int read;
            while ((read = input.read(buffer)) != -1) {
                digest.update(buffer, 0, read);
            }
        }
        StringBuilder output = new StringBuilder();
        for (byte value : digest.digest()) {
            output.append(String.format(Locale.ROOT, "%02x", value & 0xff));
        }
        return output.toString();
    }
}
