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
import org.workflowsim.platform.PlatformProfile;
import org.workflowsim.utils.ClusteringParameters.ClusteringMethod;
import org.workflowsim.utils.ReplicaCatalog;
import org.workflowsim.utils.SimulationConfig;
import org.workflowsim.utils.SimulationTiming;

/**
 * 为当前共享存储执行模型计算一个刻意限定范围的关键路径参考值。
 *
 * <p>该参考值是乐观下界，而不是最优调度。它消除 VM 容量争用，让每个任务使用
 * 最快的兼容 VM，并计入模型生成的 stage-in Job 以及
 * {@code WorkflowDatacenter} 使用的逐任务共享存储输入时延。</p>
 */
final class WorkflowModelReference {

    static final String CONTROLLED_SHARED_STORAGE_SCOPE =
            "SHARED_STORAGE_NO_CLUSTERING_NO_FAILURE_NO_OVERHEAD_SPACE_SHARED";
    static final String UNAVAILABLE_SCOPE =
            "UNAVAILABLE_OUTSIDE_CONTROLLED_SHARED_STORAGE_SCOPE";
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
            double duration = fastestCompatibleDuration(task, vms,
                    platform.getStorage().getMaxTransferRateMbPerSecond());
            if (Double.isInfinite(duration)) {
                return Reference.unavailable("UNAVAILABLE_NO_COMPATIBLE_VM");
            }
            double finish = readyTime + duration;
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
        for (PlatformProfile.VmSpec vm : vms) {
            if (task.getNumberOfPes() > vm.getPes()) {
                continue;
            }
            double transferSeconds = transferSeconds(task, transferRateMbPerSecond);
            long transferMi = (long) (vm.getMips() * transferSeconds);
            // CloudSim 多 PE 任务并行执行，使用单 PE 长度与运行时一致
            best = Math.min(best, (task.getCloudletLength() + transferMi) / vm.getMips());
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
