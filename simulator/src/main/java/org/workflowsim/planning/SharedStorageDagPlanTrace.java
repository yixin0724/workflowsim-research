package org.workflowsim.planning;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 一次维护的共享存储静态 DAG 规划的不可变审计记录。
 *
 * <p>轨迹记录的是规划器的模型级决策量。{@code plannedStartSeconds} 是数据 stage-in 前
 * Job 包络的开始时间；{@code plannedComputeStartSeconds} 是经过 CloudSim 整数 MI stage-in
 * 转换后的逻辑 Task 计算窗口开始时间。它不是生产执行 trace，也不是经网络校准的排程。</p>
 */
public final class SharedStorageDagPlanTrace {

    private final String strategy;
    private final double stageInFinishSeconds;
    private final Map<Integer, TaskPlan> taskPlans;
    private final List<Integer> criticalPathTaskIds;
    private final Integer criticalProcessorVmId;

    SharedStorageDagPlanTrace(String strategy, double stageInFinishSeconds,
            Map<Integer, TaskPlan> taskPlans, List<Integer> criticalPathTaskIds,
            Integer criticalProcessorVmId) {
        this.strategy = strategy;
        this.stageInFinishSeconds = stageInFinishSeconds;
        this.taskPlans = Collections.unmodifiableMap(
                new LinkedHashMap<Integer, TaskPlan>(taskPlans));
        this.criticalPathTaskIds = Collections.unmodifiableList(
                new ArrayList<Integer>(criticalPathTaskIds));
        this.criticalProcessorVmId = criticalProcessorVmId;
    }

    public String getStrategy() {
        return strategy;
    }

    public double getStageInFinishSeconds() {
        return stageInFinishSeconds;
    }

    public Map<Integer, TaskPlan> getTaskPlans() {
        return taskPlans;
    }

    public TaskPlan getTaskPlan(int taskId) {
        return taskPlans.get(taskId);
    }

    /** HEFT、DLS、ETF、PEFT 时为空；CPOP 时按入口至出口顺序记录关键路径。 */
    public List<Integer> getCriticalPathTaskIds() {
        return criticalPathTaskIds;
    }

    /** HEFT、DLS、ETF、PEFT 没有 CPOP 关键处理器决策，故返回 {@code null}。 */
    public Integer getCriticalProcessorVmId() {
        return criticalProcessorVmId;
    }

    /** 单个 Task 的不可变模型级决策记录。 */
    public static final class TaskPlan {

        private final int taskId;
        private final int vmId;
        private final double upwardRankSeconds;
        private final Double cpopDownwardRankSeconds;
        private final Double cpopPrioritySeconds;
        private final Double dlsDynamicLevelAtSelection;
        private final Integer dlsSelectionOrder;
        private final Double etfEarliestStartAtSelection;
        private final Integer etfSelectionOrder;
        private final Double peftRankOctSeconds;
        private final Double peftOptimisticCostAtSelectedVmSeconds;
        private final Double peftEftPlusOptimisticCostSeconds;
        private final Integer peftSelectionOrder;
        private final double plannedStartSeconds;
        private final double plannedFinishSeconds;
        private final double plannedDataStageInSeconds;

        TaskPlan(int taskId, int vmId, double upwardRankSeconds,
                Double cpopDownwardRankSeconds, Double cpopPrioritySeconds,
                Double dlsDynamicLevelAtSelection, Integer dlsSelectionOrder,
                Double etfEarliestStartAtSelection, Integer etfSelectionOrder,
                Double peftRankOctSeconds, Double peftOptimisticCostAtSelectedVmSeconds,
                Double peftEftPlusOptimisticCostSeconds, Integer peftSelectionOrder,
                double plannedStartSeconds, double plannedFinishSeconds,
                double plannedDataStageInSeconds) {
            this.taskId = taskId;
            this.vmId = vmId;
            this.upwardRankSeconds = upwardRankSeconds;
            this.cpopDownwardRankSeconds = cpopDownwardRankSeconds;
            this.cpopPrioritySeconds = cpopPrioritySeconds;
            this.dlsDynamicLevelAtSelection = dlsDynamicLevelAtSelection;
            this.dlsSelectionOrder = dlsSelectionOrder;
            this.etfEarliestStartAtSelection = etfEarliestStartAtSelection;
            this.etfSelectionOrder = etfSelectionOrder;
            this.peftRankOctSeconds = peftRankOctSeconds;
            this.peftOptimisticCostAtSelectedVmSeconds = peftOptimisticCostAtSelectedVmSeconds;
            this.peftEftPlusOptimisticCostSeconds = peftEftPlusOptimisticCostSeconds;
            this.peftSelectionOrder = peftSelectionOrder;
            this.plannedStartSeconds = plannedStartSeconds;
            this.plannedFinishSeconds = plannedFinishSeconds;
            this.plannedDataStageInSeconds = plannedDataStageInSeconds;
        }

        public int getTaskId() {
            return taskId;
        }

        public int getVmId() {
            return vmId;
        }

        public double getUpwardRankSeconds() {
            return upwardRankSeconds;
        }

        /** 仅 CPOP 轨迹提供；其他策略返回 {@code null}。 */
        public Double getCpopDownwardRankSeconds() {
            return cpopDownwardRankSeconds;
        }

        /** 仅 CPOP 轨迹提供；其他策略返回 {@code null}。 */
        public Double getCpopPrioritySeconds() {
            return cpopPrioritySeconds;
        }

        /** 仅 DLS 轨迹提供；其他策略返回 {@code null}。 */
        public Double getDlsDynamicLevelAtSelection() {
            return dlsDynamicLevelAtSelection;
        }

        /** 从 1 开始的 DLS 选择顺序；非 DLS 策略返回 {@code null}。 */
        public Integer getDlsSelectionOrder() {
            return dlsSelectionOrder;
        }

        /** 仅 ETF 轨迹提供；其他策略返回 {@code null}。 */
        public Double getEtfEarliestStartAtSelection() {
            return etfEarliestStartAtSelection;
        }

        /** 从 1 开始的 ETF 选择顺序；非 ETF 策略返回 {@code null}。 */
        public Integer getEtfSelectionOrder() {
            return etfSelectionOrder;
        }

        /** 兼容 VM 平均 optimistic-cost rank；仅 PEFT 轨迹提供。 */
        public Double getPeftRankOctSeconds() {
            return peftRankOctSeconds;
        }

        /** 所选 VM 上的 OCT；仅 PEFT 轨迹提供。 */
        public Double getPeftOptimisticCostAtSelectedVmSeconds() {
            return peftOptimisticCostAtSelectedVmSeconds;
        }

        /** 所选 PEFT 目标值，即插入 EFT 加 OCT；仅 PEFT 轨迹提供。 */
        public Double getPeftEftPlusOptimisticCostSeconds() {
            return peftEftPlusOptimisticCostSeconds;
        }

        /** 从 1 开始的 PEFT 选择顺序；其他策略返回 {@code null}。 */
        public Integer getPeftSelectionOrder() {
            return peftSelectionOrder;
        }

        /** 计划 Job 包络的开始时间，位于建模数据 stage-in 之前。 */
        public double getPlannedStartSeconds() {
            return plannedStartSeconds;
        }

        /** 计划 Job 包络的结束时间，包含建模数据 stage-in 与计算。 */
        public double getPlannedFinishSeconds() {
            return plannedFinishSeconds;
        }

        /** 经 CloudSim 整数 MI 转换后的有效 stage-in 时长。 */
        public double getPlannedDataStageInSeconds() {
            return plannedDataStageInSeconds;
        }

        /** 有效 stage-in 完成后的逻辑 Task 计算窗口开始时间。 */
        public double getPlannedComputeStartSeconds() {
            return plannedStartSeconds + plannedDataStageInSeconds;
        }
    }
}
