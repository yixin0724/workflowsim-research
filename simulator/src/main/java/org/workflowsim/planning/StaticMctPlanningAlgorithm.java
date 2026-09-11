package org.workflowsim.planning;

import java.util.List;
import org.workflowsim.CondorVM;
import org.workflowsim.Task;

/**
 * 独立任务的 Minimum Completion Time（MCT）基线。
 *
 * <p>按确定的 Task-ID 顺序逐个映射，每次选择 {@code availability + executionTime} 最小的
 * 兼容 VM，并更新该 VM 的暂定可用时间。它不是运行时 ready-batch MCT 调度器。</p>
 */
public final class StaticMctPlanningAlgorithm extends StaticIndependentPlanningSupport {

    @Override
    public void run() {
        final String algorithm = "STATIC_MCT";
        List<CondorVM> vms = sortedVms(algorithm);
        double[] availability = new double[vms.size()];
        for (Task task : independentTasks(algorithm)) {
            Choice choice = earliestCompletion(task, vms, availability, algorithm);
            assign(task, choice.getVm());
            availability[choice.getVmIndex()] = choice.getFinishTime();
        }
    }
}
