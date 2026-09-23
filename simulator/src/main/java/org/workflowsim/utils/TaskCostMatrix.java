package org.workflowsim.utils;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 任务×VM 异构执行成本矩阵——论文复现轮新增的平台能力。
 *
 * <p><b>动机</b>：WorkflowSim 的原生执行模型是"单一 MI ÷ 统一 MIPS 缩放"
 * （执行秒数 = cloudletLength / vmMips），每台 VM 的成本只能是同一任务成本的线性缩放。
 * 而经典 DAG 调度论文（HEFT/CPOP/DLS/ETF/PEFT 等）普遍使用<b>任务×处理器任意成本矩阵</b>
 * （如 Topcuoglu 2002 Table I：每个任务在每台处理器上有独立成本，不满足线性缩放）。
 * 没有本矩阵，这类论文的精确数值复现全部受阻。</p>
 *
 * <p><b>语义</b>：矩阵条目 {@code (taskId, vmId) → 执行秒数}。STATIC 派发在作业提交前
 * 按 {@code MI = round(执行秒数 × vm.mips)} 折算，保证运行时执行时间与矩阵条目一致
 * （受 CloudSim 整数 MI 舍入影响，见 WorkflowDatacenter 换算 Javadoc）。规划器直接读取
 * 矩阵进行成本估计。</p>
 *
 * <p><b>契约</b>：</p>
 * <ul>
 *   <li>矩阵为可选配置：未配置时平台保持既有 MI/mips 缩放行为，参考数字不变；</li>
 *   <li>配置矩阵时必须使用 STATIC 派发（在线调度器不做折算）且聚类方法为 NONE
 *       （作业必须与逻辑任务一一对应，折算才有唯一 VM 目标）；</li>
 *   <li>矩阵必须覆盖仿真中出现的全部任务 ID × 全部 VM ID：缺失条目在应用阶段
 *       fail-fast，不做静默回退；</li>
 *   <li>条目必须为正有限秒数。</li>
 * </ul>
 *
 * <p>本类是不可变值对象：构建后深拷贝为不可变映射。</p>
 */
public final class TaskCostMatrix {

    private final Map<Integer, Map<Integer, Double>> costSecondsByTaskAndVm;

    private TaskCostMatrix(Map<Integer, Map<Integer, Double>> source) {
        Map<Integer, Map<Integer, Double>> deepCopy =
                new LinkedHashMap<Integer, Map<Integer, Double>>();
        for (Map.Entry<Integer, Map<Integer, Double>> entry : source.entrySet()) {
            deepCopy.put(entry.getKey(),
                    Collections.unmodifiableMap(new LinkedHashMap<Integer, Double>(entry.getValue())));
        }
        this.costSecondsByTaskAndVm = Collections.unmodifiableMap(deepCopy);
    }

    /**
     * 返回一个新的构建器。
     *
     * @return 空构建器
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * 返回某任务在某 VM 上的执行秒数。
     *
     * @param taskId 逻辑任务 ID（cloudlet ID）
     * @param vmId VM ID
     * @return 执行秒数
     * @throws IllegalArgumentException 当条目缺失时抛出（fail-fast，不静默回退）
     */
    public double getCostSeconds(int taskId, int vmId) {
        Map<Integer, Double> perVm = costSecondsByTaskAndVm.get(Integer.valueOf(taskId));
        if (perVm == null) {
            throw new IllegalArgumentException("Task cost matrix has no entry for task " + taskId);
        }
        Double seconds = perVm.get(Integer.valueOf(vmId));
        if (seconds == null) {
            throw new IllegalArgumentException("Task cost matrix has no entry for task " + taskId
                    + " on VM " + vmId);
        }
        return seconds.doubleValue();
    }

    /**
     * 返回矩阵覆盖的任务 ID 集合（不可变，构建顺序）。
     *
     * @return 任务 ID 集合
     */
    public java.util.Set<Integer> getTaskIds() {
        return costSecondsByTaskAndVm.keySet();
    }

    /**
     * 返回指定任务在全部已登记 VM 上的执行秒数（不可变快照）。
     *
     * <p>该接口供证据写出与审计工具使用；调用方不能通过返回值修改矩阵内部状态。</p>
     *
     * @param taskId 逻辑任务 ID
     * @return VM ID 到执行秒数的不可变映射
     * @throws IllegalArgumentException 当任务没有任何矩阵条目时抛出
     */
    public Map<Integer, Double> getCostsForTask(int taskId) {
        Map<Integer, Double> perVm = costSecondsByTaskAndVm.get(Integer.valueOf(taskId));
        if (perVm == null) {
            throw new IllegalArgumentException("Task cost matrix has no entry for task " + taskId);
        }
        return Collections.unmodifiableMap(new LinkedHashMap<Integer, Double>(perVm));
    }

    /**
     * 返回完整任务×VM矩阵的不可变深拷贝。
     *
     * @return 任务 ID 到 VM 成本映射的不可变快照
     */
    public Map<Integer, Map<Integer, Double>> asMap() {
        Map<Integer, Map<Integer, Double>> copy =
                new LinkedHashMap<Integer, Map<Integer, Double>>();
        for (Map.Entry<Integer, Map<Integer, Double>> entry : costSecondsByTaskAndVm.entrySet()) {
            copy.put(entry.getKey(), Collections.unmodifiableMap(
                    new LinkedHashMap<Integer, Double>(entry.getValue())));
        }
        return Collections.unmodifiableMap(copy);
    }

    /**
     * 判断某任务×VM 组合是否有登记条目。
     *
     * @param taskId 逻辑任务 ID（cloudlet ID）
     * @param vmId VM ID
     * @return 有登记条目时返回 true
     */
    public boolean contains(int taskId, int vmId) {
        Map<Integer, Double> perVm = costSecondsByTaskAndVm.get(Integer.valueOf(taskId));
        return perVm != null && perVm.containsKey(Integer.valueOf(vmId));
    }

    /**
     * 判断矩阵是否覆盖给定的任务 ID 与 VM ID 集合。
     *
     * @param taskIds 需要覆盖的任务 ID
     * @param vmIds 需要覆盖的 VM ID
     * @return 全部覆盖时返回 true
     */
    public boolean covers(Iterable<Integer> taskIds, Iterable<Integer> vmIds) {
        for (Integer taskId : taskIds) {
            Map<Integer, Double> perVm = costSecondsByTaskAndVm.get(taskId);
            if (perVm == null) {
                return false;
            }
            for (Integer vmId : vmIds) {
                if (!perVm.containsKey(vmId)) {
                    return false;
                }
            }
        }
        return true;
    }

    /** TaskCostMatrix 的可变构建器。 */
    public static final class Builder {

        private final Map<Integer, Map<Integer, Double>> entries =
                new LinkedHashMap<Integer, Map<Integer, Double>>();

        private Builder() {
        }

        /**
         * 登记一个条目。
         *
         * @param taskId 逻辑任务 ID（cloudlet ID）
         * @param vmId VM ID
         * @param costSeconds 该任务在该 VM 上的执行秒数，必须为正有限值
         * @return 本构建器
         * @throws IllegalArgumentException 当秒数非正/非有限，或条目重复登记时抛出
         */
        public Builder put(int taskId, int vmId, double costSeconds) {
            if (!(costSeconds > 0.0) || Double.isNaN(costSeconds) || Double.isInfinite(costSeconds)) {
                throw new IllegalArgumentException("Task cost matrix entry must be positive finite seconds: "
                        + "task " + taskId + " on VM " + vmId + " got " + costSeconds);
            }
            Map<Integer, Double> perVm = entries.get(Integer.valueOf(taskId));
            if (perVm == null) {
                perVm = new LinkedHashMap<Integer, Double>();
                entries.put(Integer.valueOf(taskId), perVm);
            }
            if (perVm.put(Integer.valueOf(vmId), Double.valueOf(costSeconds)) != null) {
                throw new IllegalArgumentException("Duplicate task cost matrix entry for task " + taskId
                        + " on VM " + vmId);
            }
            return this;
        }

        /**
         * 构建不可变矩阵。
         *
         * @return 不可变 TaskCostMatrix
         * @throws IllegalArgumentException 当矩阵为空时抛出
         */
        public TaskCostMatrix build() {
            if (entries.isEmpty()) {
                throw new IllegalArgumentException("Task cost matrix must contain at least one entry");
            }
            return new TaskCostMatrix(entries);
        }
    }
}
