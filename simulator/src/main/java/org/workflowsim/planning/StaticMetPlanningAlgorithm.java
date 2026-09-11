package org.workflowsim.planning;

import java.util.List;
import org.workflowsim.CondorVM;
import org.workflowsim.Task;

/**
 * 独立任务的 Minimum Execution Time（MET）基线。
 *
 * <p>按 Task-ID 顺序选择纯执行时间 {@code taskLength / vmMips} 最小的兼容 VM；相同时间
 * 选择较小 VM ID。它不维护排队可用时间，因此不能用于解释调度等待或 DAG 依赖。</p>
 */
public final class StaticMetPlanningAlgorithm extends StaticIndependentPlanningSupport {

    @Override
    public void run() {
        final String algorithm = "STATIC_MET";
        List<CondorVM> vms = sortedVms(algorithm);
        for (Task task : independentTasks(algorithm)) {
            assign(task, fastestMachine(task, vms, algorithm));
        }
    }
}
