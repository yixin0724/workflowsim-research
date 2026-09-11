package org.workflowsim.planning;

import java.util.ArrayList;
import java.util.List;
import org.workflowsim.CondorVM;
import org.workflowsim.Task;

/**
 * 独立任务的经典 Max-Min 完成时间基线。
 *
 * <p>每轮先为每个剩余 Task 找到最小暂定完成时间，再选择其中全局最大者；相同完成时间按
 * Task ID 打破平局，VM 内平局由共用逻辑按 VM ID 打破。本策略拒绝带 DAG 边的 Task。</p>
 */
public final class StaticMaxMinPlanningAlgorithm extends StaticIndependentPlanningSupport {

    @Override
    public void run() {
        final String algorithm = "STATIC_MAXMIN";
        List<CondorVM> vms = sortedVms(algorithm);
        double[] availability = new double[vms.size()];
        List<Task> remaining = new ArrayList<Task>(independentTasks(algorithm));
        while (!remaining.isEmpty()) {
            Task selectedTask = null;
            Choice selectedChoice = null;
            for (Task task : remaining) {
                Choice choice = earliestCompletion(task, vms, availability, algorithm);
                if (selectedChoice == null || choice.getFinishTime() > selectedChoice.getFinishTime()
                        || (Double.compare(choice.getFinishTime(), selectedChoice.getFinishTime()) == 0
                        && task.getCloudletId() < selectedTask.getCloudletId())) {
                    selectedTask = task;
                    selectedChoice = choice;
                }
            }
            assign(selectedTask, selectedChoice.getVm());
            availability[selectedChoice.getVmIndex()] = selectedChoice.getFinishTime();
            remaining.remove(selectedTask);
        }
    }
}
