package org.workflowsim.planning;

import java.util.List;
import org.workflowsim.CondorVM;
import org.workflowsim.Task;

/**
 * 独立任务的确定性 VM-ID 轮转基线。
 *
 * <p>按 Task-ID 顺序，从上一次成功映射 VM 的后继位置循环寻找第一个 PE 兼容 VM；它不估计
 * 执行时间、等待时间或 DAG 依赖。</p>
 */
public final class StaticRoundRobinPlanningAlgorithm extends StaticIndependentPlanningSupport {

    @Override
    public void run() {
        final String algorithm = "STATIC_ROUND_ROBIN";
        List<CondorVM> vms = sortedVms(algorithm);
        int nextIndex = 0;
        for (Task task : independentTasks(algorithm)) {
            int selectedIndex = -1;
            for (int offset = 0; offset < vms.size(); offset++) {
                int candidateIndex = (nextIndex + offset) % vms.size();
                if (isCompatible(task, vms.get(candidateIndex))) {
                    selectedIndex = candidateIndex;
                    break;
                }
            }
            if (selectedIndex < 0) {
                throw noCompatibleVm(task, algorithm);
            }
            assign(task, vms.get(selectedIndex));
            nextIndex = (selectedIndex + 1) % vms.size();
        }
    }
}
