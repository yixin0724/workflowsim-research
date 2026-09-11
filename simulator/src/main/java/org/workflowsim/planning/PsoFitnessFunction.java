package org.workflowsim.planning;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.cloudbus.cloudsim.Vm;
import org.workflowsim.Task;

/**
 * PSO 适应度函数（忠实移植自 {@code meysamhit/workflowsim-pso}）。
 *
 * <p>{@code fitness = COST_WEIGHT · totalCost + (1 - COST_WEIGHT) · makespan}</p>
 *
 * <p>参考实现的成本模型（源自 Pandey et al., AINA 2010 的简化云计价）：</p>
 * <ul>
 *   <li>VM 单价 {@code price = mips / 1000}（越快越贵）；</li>
 *   <li>任务成本 = 执行时间 × VM 单价，执行时间 = {@code cloudletLength / mips}；</li>
 *   <li>VM 负载 = 该 VM 上任务执行时间之和（<b>顺序执行模型</b>）；</li>
 *   <li>makespan = 各 VM 负载的最大值。</li>
 * </ul>
 *
 * <p><b>忠实性声明</b>：该适应度<b>忽略 DAG 依赖边</b>（VM 负载为顺序累加，
 * 不做依赖感知的完成时间推演）——这是参考实现（及论文简化成本模型）的既定
 * 行为，移植时刻意保留。运行时真实执行仍由引擎的依赖释放约束，因此规划侧
 * makespan 估计与运行侧 makespan 可能不同；对照实验时以运行侧指标为准。</p>
 *
 * @since WorkflowSim Toolkit 1.0（论文复现轮）
 */
public final class PsoFitnessFunction {

    private PsoFitnessFunction() { }

    /**
     * 计算一个粒子位置的适应度（越小越好）。
     *
     * @param position Task→VM 下标映射（下标指向 {@code vms} 列表）
     * @param tasks Task 列表（与 position 一一对应）
     * @param vms VM 候选列表（已按 VM ID 排序）
     * @param costWeight 成本权重 ∈ [0,1]
     * @return 加权适应度
     * @throws IllegalArgumentException 当列表为空或下标越界时抛出
     */
    public static double evaluate(int[] position, List<Task> tasks, List<? extends Vm> vms,
            double costWeight) {
        if (tasks == null || tasks.isEmpty() || vms == null || vms.isEmpty()) {
            throw new IllegalArgumentException("PSO fitness requires non-empty task and VM lists");
        }
        if (position == null || position.length != tasks.size()) {
            throw new IllegalArgumentException("PSO position must match task count");
        }
        Map<Integer, List<Task>> vmTasks = new HashMap<Integer, List<Task>>();
        for (int i = 0; i < tasks.size(); i++) {
            int vmIndex = position[i];
            if (vmIndex < 0 || vmIndex >= vms.size()) {
                throw new IllegalArgumentException("PSO position value out of range: " + vmIndex);
            }
            List<Task> bucket = vmTasks.get(Integer.valueOf(vmIndex));
            if (bucket == null) {
                bucket = new ArrayList<Task>();
                vmTasks.put(Integer.valueOf(vmIndex), bucket);
            }
            bucket.add(tasks.get(i));
        }

        double totalCost = 0.0;
        double makespan = 0.0;
        for (Map.Entry<Integer, List<Task>> entry : vmTasks.entrySet()) {
            Vm vm = vms.get(entry.getKey().intValue());
            double vmLoad = 0.0;
            for (Task task : entry.getValue()) {
                double execTime = task.getCloudletLength() / vm.getMips();
                vmLoad += execTime;
                totalCost += execTime * getPrice(vm);
            }
            makespan = Math.max(makespan, vmLoad);
        }
        return costWeight * totalCost + (1.0 - costWeight) * makespan;
    }

    /**
     * 简单计价模型：VM 越快单价越高。
     *
     * @param vm 目标 VM
     * @return 每时间单位价格 = mips / 1000
     */
    public static double getPrice(Vm vm) {
        return vm.getMips() / 1000.0;
    }
}
