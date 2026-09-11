package org.workflowsim.planning;

import java.util.ArrayList;
import java.util.List;
import org.workflowsim.CondorVM;
import org.workflowsim.Task;

/**
 * 独立任务的经典 Sufferage 基线。
 *
 * <p>每轮比较每个 Task 的次优与最优暂定完成时间之差，优先映射损失最大的 Task；同分按
 * Task ID 打破平局。只有一台兼容 VM 时损失定义为零，以保持有限且确定的排序。</p>
 */
public final class StaticSufferagePlanningAlgorithm extends StaticIndependentPlanningSupport {

    @Override
    public void run() {
        final String algorithm = "STATIC_SUFFERAGE";
        List<CondorVM> vms = sortedVms(algorithm);
        double[] availability = new double[vms.size()];
        List<Task> remaining = new ArrayList<Task>(independentTasks(algorithm));
        while (!remaining.isEmpty()) {
            Task selectedTask = null;
            Choice selectedChoice = null;
            double selectedSufferage = Double.NEGATIVE_INFINITY;
            for (Task task : remaining) {
                Choice best = earliestCompletion(task, vms, availability, algorithm);
                double secondBest = Double.POSITIVE_INFINITY;
                for (int index = 0; index < vms.size(); index++) {
                    if (index == best.getVmIndex()) {
                        continue;
                    }
                    CondorVM candidate = vms.get(index);
                    double duration = executionTime(task, candidate);
                    if (!Double.isInfinite(duration)) {
                        secondBest = Math.min(secondBest, availability[index] + duration);
                    }
                }
                // 只有一台兼容 VM 时没有可失去的备选，将损失定为零以保持有限且确定的排序。
                double sufferage = Double.isInfinite(secondBest) ? 0.0
                        : secondBest - best.getFinishTime();
                if (selectedChoice == null || sufferage > selectedSufferage
                        || (Double.compare(sufferage, selectedSufferage) == 0
                        && task.getCloudletId() < selectedTask.getCloudletId())) {
                    selectedTask = task;
                    selectedChoice = best;
                    selectedSufferage = sufferage;
                }
            }
            assign(selectedTask, selectedChoice.getVm());
            availability[selectedChoice.getVmIndex()] = selectedChoice.getFinishTime();
            remaining.remove(selectedTask);
        }
    }
}
