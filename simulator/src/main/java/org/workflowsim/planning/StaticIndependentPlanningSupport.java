package org.workflowsim.planning;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import org.workflowsim.CondorVM;
import org.workflowsim.Task;

/**
 * 离线独立任务基线共用的确定性机械逻辑。
 *
 * <p>这些规划器只产生 VM 映射。WorkflowSim 的 {@code STATIC} 分派器负责把映射带入
 * 模拟生命周期，但不会把它们变成 DAG 算法或重放真实队列。Task 和 VM 分别按 ID 升序，
 * 等价候选以较小 ID 打破平局。</p>
 */
abstract class StaticIndependentPlanningSupport extends BasePlanningAlgorithm {

    protected final List<Task> independentTasks(String algorithmName) {
        if (getTaskList() == null) {
            throw new IllegalStateException(algorithmName + " requires a task list");
        }

        List<Task> tasks = new ArrayList<Task>(getTaskList());
        for (Task task : tasks) {
            if (!task.getParentList().isEmpty() || !task.getChildList().isEmpty()) {
                throw new IllegalArgumentException(algorithmName + " supports independent tasks only; "
                        + "use a DAG planner for workflows with dependencies");
            }
        }
        Collections.sort(tasks, new Comparator<Task>() {
            @Override
            public int compare(Task first, Task second) {
                return Integer.compare(first.getCloudletId(), second.getCloudletId());
            }
        });
        return tasks;
    }

    protected final List<CondorVM> sortedVms(String algorithmName) {
        if (getVmList() == null || getVmList().isEmpty()) {
            throw new IllegalStateException(algorithmName + " requires at least one VM");
        }

        List<CondorVM> vms = new ArrayList<CondorVM>();
        for (Object object : getVmList()) {
            if (!(object instanceof CondorVM)) {
                throw new IllegalArgumentException(algorithmName + " requires CondorVM instances");
            }
            CondorVM vm = (CondorVM) object;
            if (!isPositiveFinite(vm.getMips())) {
                throw new IllegalArgumentException(algorithmName + " requires VMs with finite positive MIPS");
            }
            vms.add(vm);
        }
        Collections.sort(vms, new Comparator<CondorVM>() {
            @Override
            public int compare(CondorVM first, CondorVM second) {
                return Integer.compare(first.getId(), second.getId());
            }
        });
        return vms;
    }

    protected final boolean isCompatible(Task task, CondorVM vm) {
        return task.getNumberOfPes() <= vm.getNumberOfPes();
    }

    protected final double executionTime(Task task, CondorVM vm) {
        // CloudSim 多 PE 任务并行执行，使用单 PE 长度与运行时一致
        return isCompatible(task, vm) ? task.getCloudletLength() / vm.getMips()
                : Double.POSITIVE_INFINITY;
    }

    protected final void assign(Task task, CondorVM vm) {
        task.setVmId(vm.getId());
    }

    protected final CondorVM fastestMachine(Task task, List<CondorVM> vms, String algorithmName) {
        CondorVM selected = null;
        double selectedTime = Double.POSITIVE_INFINITY;
        for (CondorVM vm : vms) {
            double time = executionTime(task, vm);
            if (time < selectedTime || (Double.compare(time, selectedTime) == 0
                    && selected != null && vm.getId() < selected.getId())) {
                selected = vm;
                selectedTime = time;
            }
        }
        if (selected == null || Double.isInfinite(selectedTime)) {
            throw noCompatibleVm(task, algorithmName);
        }
        return selected;
    }

    /**
     * 计算 {@code availability[vm] + taskLength / vmMips} 最小的可兼容 VM。
     * 相等完成时间选择较小 VM ID。
     */
    protected final Choice earliestCompletion(Task task, List<CondorVM> vms,
            double[] availability, String algorithmName) {
        CondorVM selected = null;
        int selectedIndex = -1;
        double selectedFinish = Double.POSITIVE_INFINITY;
        for (int index = 0; index < vms.size(); index++) {
            CondorVM vm = vms.get(index);
            double duration = executionTime(task, vm);
            double finish = Double.isInfinite(duration) ? Double.POSITIVE_INFINITY
                    : availability[index] + duration;
            if (finish < selectedFinish || (Double.compare(finish, selectedFinish) == 0
                    && selected != null && vm.getId() < selected.getId())) {
                selected = vm;
                selectedIndex = index;
                selectedFinish = finish;
            }
        }
        if (selected == null || Double.isInfinite(selectedFinish)) {
            throw noCompatibleVm(task, algorithmName);
        }
        return new Choice(selected, selectedIndex, selectedFinish);
    }

    protected final IllegalArgumentException noCompatibleVm(Task task, String algorithmName) {
        return new IllegalArgumentException(algorithmName + " cannot map task " + task.getCloudletId()
                + "; no VM has sufficient processing elements");
    }

    protected final boolean isPositiveFinite(double value) {
        return value > 0.0 && !Double.isNaN(value) && !Double.isInfinite(value);
    }

    protected static final class Choice {

        private final CondorVM vm;
        private final int vmIndex;
        private final double finishTime;

        private Choice(CondorVM vm, int vmIndex, double finishTime) {
            this.vm = vm;
            this.vmIndex = vmIndex;
            this.finishTime = finishTime;
        }

        protected CondorVM getVm() {
            return vm;
        }

        protected int getVmIndex() {
            return vmIndex;
        }

        protected double getFinishTime() {
            return finishTime;
        }
    }
}
