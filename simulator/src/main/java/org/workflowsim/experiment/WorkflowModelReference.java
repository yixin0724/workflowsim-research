package org.workflowsim.experiment;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.cloudbus.cloudsim.Consts;
import org.workflowsim.FileItem;
import org.workflowsim.Task;
import org.workflowsim.data.DataMovementModel;
import org.workflowsim.platform.PlatformProfile;
import org.workflowsim.utils.ClusteringParameters.ClusteringMethod;
import org.workflowsim.utils.ReplicaCatalog;
import org.workflowsim.utils.SimulationConfig;
import org.workflowsim.utils.SimulationTiming;
import org.workflowsim.utils.TaskExecutionModel;

/**
 * 为当前共享存储执行模型计算一个刻意限定范围的关键路径参考值。
 *
 * <p>该参考值是乐观下界，而不是最优调度。它消除 VM 容量争用及非零工作流到达约束，
 * 让每个任务使用有效执行时长最短的兼容 VM；有任务×VM 成本矩阵时，计算工作量采用与运行时
 * 相同的整数 MI 投影，而不是原始输入长度。参考计入模型生成的 stage-in Job，以及历史共享
 * 存储规则下逐任务串行输入的整数 MI 时延。</p>
 *
 * <p>可用于 LEGACY_WORKFLOWSIM_V1 和 FIXED_ENDPOINT_NO_CONTENTION_V1：后者的
 * 非负接入时延和额外带宽上限被乐观地去掉，所以保留的历史存储时延不会高估其输入时延。
 * 执行前传输模型可让不同父任务的输入重叠，不能复用此串行输入下界；这些合法运行仅将本参考
 * 标记为不可用。参考不是每工作流流时，也不作跨数据移动模型的 makespan 单调性承诺。</p>
 *
 * <p>这是可选观测：任一 PE 兼容候选的计算工作量、输入信封或时长无法表示时，整个参考报告
 * {@code UNAVAILABLE_UNREPRESENTABLE_EXECUTION_COST}，不回退原始成本，也不跳过坏候选后
 * 冒称已有完整参考。这不新增全矩阵预检，不改变规划器或实际所选坐标的执行校验；不能使一个
 * 已完成有效执行的运行仅因未选候选的参考计算而失败。</p>
 */
final class WorkflowModelReference {

    static final String CONTROLLED_SHARED_STORAGE_SCOPE =
            "SHARED_STORAGE_NO_CLUSTERING_NO_FAILURE_NO_OVERHEAD_SPACE_SHARED";
    static final String UNAVAILABLE_SCOPE =
            "UNAVAILABLE_OUTSIDE_CONTROLLED_SHARED_STORAGE_SCOPE";
    private static final String UNREPRESENTABLE_EXECUTION_COST_SCOPE =
            "UNAVAILABLE_UNREPRESENTABLE_EXECUTION_COST";
    private static final long STAGE_IN_JOB_LENGTH_MI = 110L;

    private WorkflowModelReference() {
    }

    static Reference calculate(List<Task> sourceTasks, SimulationConfig config,
            PlatformProfile platform) {
        if (!supportsControlledSharedStorage(config, platform)) {
            return Reference.unavailable(UNAVAILABLE_SCOPE);
        }
        if (sourceTasks == null || sourceTasks.isEmpty()) {
            return Reference.unavailable("UNAVAILABLE_NO_SOURCE_TASKS");
        }

        List<Task> tasks = new ArrayList<Task>(sourceTasks);
        Collections.sort(tasks, new Comparator<Task>() {
            @Override
            public int compare(Task first, Task second) {
                return Integer.compare(first.getCloudletId(), second.getCloudletId());
            }
        });
        List<PlatformProfile.VmSpec> vms = new ArrayList<PlatformProfile.VmSpec>(platform.getVms());
        Collections.sort(vms, new Comparator<PlatformProfile.VmSpec>() {
            @Override
            public int compare(PlatformProfile.VmSpec first, PlatformProfile.VmSpec second) {
                return Integer.compare(first.getId(), second.getId());
            }
        });

        Map<Task, Integer> remainingParents = new HashMap<Task, Integer>();
        Set<Task> taskSet = new HashSet<Task>(tasks);
        Set<Integer> taskIds = new HashSet<Integer>();
        for (Task task : tasks) {
            if (task == null || !taskIds.add(task.getCloudletId())) {
                return Reference.unavailable("UNAVAILABLE_INVALID_SOURCE_TASK_GRAPH");
            }
            for (Task parent : task.getParentList()) {
                if (parent == null || !taskSet.contains(parent)) {
                    return Reference.unavailable("UNAVAILABLE_INVALID_SOURCE_TASK_GRAPH");
                }
            }
            for (Task child : task.getChildList()) {
                if (child == null || !taskSet.contains(child)) {
                    return Reference.unavailable("UNAVAILABLE_INVALID_SOURCE_TASK_GRAPH");
                }
            }
            remainingParents.put(task, task.getParentList().size());
        }

        double fastestMips = 0.0;
        for (PlatformProfile.VmSpec vm : vms) {
            fastestMips = Math.max(fastestMips, vm.getMips());
        }
        if (fastestMips <= 0.0) {
            return Reference.unavailable("UNAVAILABLE_NO_POSITIVE_VM_MIPS");
        }
        // 根计算 Job 在 stage-in 事件完成后的一个 CloudSim 内核间隔才释放，
        // 与 WorkflowEngine/WorkflowScheduler 的实际行为保持一致。
        double stageInSeconds = SimulationTiming.earliestCloudletCompletionTime(0.0,
                STAGE_IN_JOB_LENGTH_MI / fastestMips,
                config.getCloudSimMinEventIntervalSeconds())
                + config.getCloudSimMinEventIntervalSeconds();
        Map<Task, Double> finishTimes = new HashMap<Task, Double>();
        Deque<Task> ready = new ArrayDeque<Task>();
        for (Task task : tasks) {
            if (remainingParents.get(task).intValue() == 0) {
                ready.addLast(task);
            }
        }
        int processed = 0;
        double lowerBound = 0.0;
        while (!ready.isEmpty()) {
            Task task = ready.removeFirst();
            double readyTime = task.getParentList().isEmpty() ? stageInSeconds : 0.0;
            for (Task parent : task.getParentList()) {
                readyTime = Math.max(readyTime, finishTimes.get(parent).doubleValue());
            }
            double duration;
            try {
                duration = fastestCompatibleDuration(task, vms,
                        platform.getStorage().getMaxTransferRateMbPerSecond());
            } catch (IllegalArgumentException | ArithmeticException unrepresentableCost) {
                // This catches only the optional per-task candidate-cost calculation, never
                // actual dispatch/execution or planner validation. A bad alternative invalidates
                // the whole reference; ignoring it could advertise an unjustified bound.
                return Reference.unavailable(UNREPRESENTABLE_EXECUTION_COST_SCOPE);
            }
            if (Double.isInfinite(duration)) {
                return Reference.unavailable("UNAVAILABLE_NO_COMPATIBLE_VM");
            }
            double finish = readyTime + duration;
            if (!Double.isFinite(finish) || finish < 0.0) {
                return Reference.unavailable(UNREPRESENTABLE_EXECUTION_COST_SCOPE);
            }
            finishTimes.put(task, finish);
            lowerBound = Math.max(lowerBound, finish);
            processed++;

            List<Task> children = new ArrayList<Task>(task.getChildList());
            Collections.sort(children, new Comparator<Task>() {
                @Override
                public int compare(Task first, Task second) {
                    return Integer.compare(first.getCloudletId(), second.getCloudletId());
                }
            });
            for (Task child : children) {
                int remaining = remainingParents.get(child).intValue() - 1;
                remainingParents.put(child, remaining);
                if (remaining == 0) {
                    ready.addLast(child);
                }
            }
        }
        if (processed != tasks.size()) {
            return Reference.unavailable("UNAVAILABLE_CYCLIC_SOURCE_TASK_GRAPH");
        }
        return Reference.available(CONTROLLED_SHARED_STORAGE_SCOPE, lowerBound, tasks.size());
    }

    private static double fastestCompatibleDuration(Task task, List<PlatformProfile.VmSpec> vms,
            int transferRateMbPerSecond) {
        double best = Double.POSITIVE_INFINITY;
        // Shared-storage input demand is independent of the candidate VM; scan the files once.
        double transferSeconds = transferSeconds(task, transferRateMbPerSecond);
        if (!Double.isFinite(transferSeconds) || transferSeconds < 0.0) {
            throw new IllegalArgumentException("Reference transfer seconds must be finite and non-negative");
        }
        for (PlatformProfile.VmSpec vm : vms) {
            if (task.getNumberOfPes() > vm.getPes()) {
                continue;
            }
            long computeMi = TaskExecutionModel.executionLengthMi(task, vm.getId(), vm.getMips());
            double transferWorkMi = vm.getMips() * transferSeconds;
            // 2^63 is the first positive double outside long's range; casting it would saturate.
            if (!Double.isFinite(transferWorkMi) || transferWorkMi < 0.0 || transferWorkMi >= 0x1.0p63) {
                throw new IllegalArgumentException("Reference transfer MI cannot be represented as a non-negative long");
            }
            long transferMi = (long) transferWorkMi;
            long envelopeMi = Math.addExact(computeMi, transferMi);
            TaskExecutionModel.requireRepresentableLength(envelopeMi, task.getNumberOfPes());
            // CloudSim 多 PE 任务并行执行，使用单 PE 长度与运行时一致。
            double candidateSeconds = envelopeMi / vm.getMips();
            if (!Double.isFinite(candidateSeconds) || candidateSeconds <= 0.0) {
                throw new IllegalArgumentException("Reference execution seconds must be finite and positive");
            }
            best = Math.min(best, candidateSeconds);
        }
        return best;
    }

    private static double transferSeconds(Task task, int transferRateMbPerSecond) {
        double result = 0.0;
        for (FileItem file : task.getFileList()) {
            if (file.isRealInputFile(task.getFileList())) {
                result += file.getSize() / (double) Consts.MILLION / transferRateMbPerSecond;
            }
        }
        return result;
    }

    private static boolean supportsControlledSharedStorage(SimulationConfig config,
            PlatformProfile platform) {
        if (config.getFileSystem() != ReplicaCatalog.FileSystem.SHARED
                || config.getClusteringParameters().getClusteringMethod() != ClusteringMethod.NONE
                || config.getFailureModel().isEnabled()
                || !isNoOverhead(config)
                || platform.getStorage().getMaxTransferRateMbPerSecond() <= 0) {
            return false;
        }
        DataMovementModel.Kind movement = config.getDataMovementModel().getKind();
        if (movement != DataMovementModel.Kind.LEGACY_WORKFLOWSIM_V1
                && movement != DataMovementModel.Kind.FIXED_ENDPOINT_NO_CONTENTION_V1) {
            // Pre-execution parent inputs may overlap: their serial sum is not a lower bound.
            return false;
        }
        for (PlatformProfile.VmSpec vm : platform.getVms()) {
            if (vm.getSchedulerMode() != PlatformProfile.CloudletSchedulerMode.SPACE_SHARED
                    || vm.getMips() <= 0.0) {
                return false;
            }
        }
        return true;
    }

    private static boolean isNoOverhead(SimulationConfig config) {
        return config.getOverheadModel().getWorkflowEngineDelayInterval() == 0
                && config.getOverheadModel().getBandwidth() == 0.0
                && config.getOverheadModel().getWorkflowEngineDelays().isEmpty()
                && config.getOverheadModel().getQueueDelays().isEmpty()
                && config.getOverheadModel().getPostDelays().isEmpty()
                && config.getOverheadModel().getClusteringDelays().isEmpty();
    }

    static final class Reference {
        private final boolean available;
        private final String scope;
        private final double criticalPathLowerBoundSeconds;
        private final int sourceTaskCount;

        private Reference(boolean available, String scope, double criticalPathLowerBoundSeconds,
                int sourceTaskCount) {
            this.available = available;
            this.scope = scope;
            this.criticalPathLowerBoundSeconds = criticalPathLowerBoundSeconds;
            this.sourceTaskCount = sourceTaskCount;
        }

        private static Reference available(String scope, double lowerBound, int taskCount) {
            return new Reference(true, scope, lowerBound, taskCount);
        }

        private static Reference unavailable(String scope) {
            return new Reference(false, scope, 0.0, 0);
        }

        boolean isAvailable() { return available; }
        String getScope() { return scope; }
        double getCriticalPathLowerBoundSeconds() { return criticalPathLowerBoundSeconds; }
        int getSourceTaskCount() { return sourceTaskCount; }
    }
}
