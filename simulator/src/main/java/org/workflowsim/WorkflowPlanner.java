/**
 * Copyright 2012-2013 University Of Southern California
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */
package org.workflowsim;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.cloudbus.cloudsim.Log;
import org.cloudbus.cloudsim.Vm;
import org.cloudbus.cloudsim.core.CloudSim;
import org.cloudbus.cloudsim.core.CloudSimTags;
import org.cloudbus.cloudsim.core.SimEntity;
import org.cloudbus.cloudsim.core.SimEvent;
import org.workflowsim.experiment.SimulationEventRecorder;
import org.workflowsim.experiment.SimulationEventType;
import org.workflowsim.failure.FailureParameters;
import org.workflowsim.planning.BasePlanningAlgorithm;
import org.workflowsim.planning.LocalCpopPlanningAlgorithm;
import org.workflowsim.planning.LocalHeftPlanningAlgorithm;
import org.workflowsim.planning.LocalPeftPlanningAlgorithm;
import org.workflowsim.planning.PSOPlanningAlgorithm;
import org.workflowsim.planning.PlanningContext;
import org.workflowsim.planning.RandomPlanningAlgorithm;
import org.workflowsim.planning.SharedStorageCpopPlanningAlgorithm;
import org.workflowsim.planning.SharedStorageDagPlanTrace;
import org.workflowsim.planning.SharedStorageDlsPlanningAlgorithm;
import org.workflowsim.planning.SharedStorageEtfPlanningAlgorithm;
import org.workflowsim.planning.SharedStorageHeftPlanningAlgorithm;
import org.workflowsim.planning.SharedStoragePeftPlanningAlgorithm;
import org.workflowsim.planning.StaticMaxMinPlanningAlgorithm;
import org.workflowsim.planning.StaticMctPlanningAlgorithm;
import org.workflowsim.planning.StaticMetPlanningAlgorithm;
import org.workflowsim.planning.StaticMinMinPlanningAlgorithm;
import org.workflowsim.planning.StaticOlbPlanningAlgorithm;
import org.workflowsim.planning.StaticRoundRobinPlanningAlgorithm;
import org.workflowsim.planning.StaticSufferagePlanningAlgorithm;
import org.workflowsim.utils.Parameters;
import org.workflowsim.utils.Parameters.PlanningAlgorithm;

/**
 * 工作流仿真的解析与规划入口实体。
 *
 * <p>仿真从该实体开始：解析输入任务 DAG，按配置可选地执行离线规划，计算供平衡聚类使用的
 * 影响度，再将任务交给 {@link ClusteringEngine}。规划器只负责任务到 VM 的静态映射或
 * 静态顺序决定；实际派发仍由工作流引擎和调度器完成。</p>
 *
 * @author Weiwei Chen
 * @since WorkflowSim Toolkit 1.0
 * @date Apr 9, 2013
 *
 */
public final class WorkflowPlanner extends SimEntity {

    /** 当前待规划、待聚类的任务列表。 */
    protected List< Task> taskList;
    /** 当前工作流输入解析器。 */
    protected WorkflowParser parser;
    /** 下游聚类引擎实体编号。 */
    private int clusteringEngineId;
    private ClusteringEngine clusteringEngine;
    private SimulationEventRecorder eventRecorder = SimulationEventRecorder.disabled();
    private PlanningContext planningContext;
    private List<Task> parsedTaskSnapshot = Collections.emptyList();
    private SharedStorageDagPlanTrace sharedStorageDagPlanTrace;
    /** 可选的任务×VM 异构执行成本矩阵；为 null 时平台保持 MI/mips 缩放。 */
    private org.workflowsim.utils.TaskCostMatrix taskCostMatrix;

    /**
     * 创建一个使用单个运行时调度器的工作流规划实体。
     *
     * @param name CloudSim 实体名称
     * @throws Exception 当 CloudSim 实体无法创建时
     */
    public WorkflowPlanner(String name) throws Exception {
        this(name, 1);
    }

    /**
     * 创建工作流规划实体及其下游聚类/执行链。
     *
     * @param name CloudSim 实体名称
     * @param schedulers 下游运行时调度器数量
     * @throws Exception 当 CloudSim 实体无法创建时
     */
    public WorkflowPlanner(String name, int schedulers) throws Exception {
        super(name);

        setTaskList(new ArrayList<>());
        this.clusteringEngine = new ClusteringEngine(name + "_Merger_", schedulers);
        this.clusteringEngineId = this.clusteringEngine.getId();
        this.parser = new WorkflowParser(getClusteringEngine().getWorkflowEngine().getSchedulerId(0));

    }

    /** @return 下游聚类引擎的 CloudSim 实体编号 */
    public int getClusteringEngineId() {
        return this.clusteringEngineId;
    }

    /** @return 下游聚类引擎 */
    public ClusteringEngine getClusteringEngine() {
        return this.clusteringEngine;
    }

    /** @return 工作流输入解析器 */
    public WorkflowParser getWorkflowParser() {
        return this.parser;
    }

    /** @return 工作流引擎的 CloudSim 实体编号 */
    public int getWorkflowEngineId() {
        return getClusteringEngine().getWorkflowEngineId();
    }

    /** @return 下游工作流引擎 */
    public WorkflowEngine getWorkflowEngine() {
        return getClusteringEngine().getWorkflowEngine();
    }

    /**
     * 安装本次运行私有的事件记录器，并传播给下游执行链。
     *
     * @param value 本次运行的事件记录器
     */
    public void setEventRecorder(SimulationEventRecorder value) {
        if (value == null) {
            throw new IllegalArgumentException("Event recorder cannot be null");
        }
        this.eventRecorder = value;
        getClusteringEngine().setEventRecorder(value);
    }

    /**
     * 提供需要建模平台存储的规划器所需上下文。
     *
     * @param value 显式规划上下文
     */
    public void setPlanningContext(PlanningContext value) {
        if (value == null) {
            throw new IllegalArgumentException("Planning context cannot be null");
        }
        this.planningContext = value;
    }

    /**
     * 提供可选的任务×VM 异构执行成本矩阵（论文复现支撑）。
     *
     * <p>解析完成后矩阵会被投影到每个逻辑任务上：规划器读取任务投影进行成本估计，
     * STATIC 派发在提交前按 {@code MI = round(秒数 × vm.mips)} 折算。未设置时平台
     * 保持既有 MI/mips 缩放行为。</p>
     *
     * @param value 任务×VM 执行成本矩阵；null 清除投影
     */
    public void setTaskCostMatrix(org.workflowsim.utils.TaskCostMatrix value) {
        this.taskCostMatrix = value;
    }

    /**
     * 处理规划生命周期中的 CloudSim 事件。
     *
     * @param ev 接收的事件
     */
    @Override
    public void processEvent(SimEvent ev) {
        switch (ev.getTag()) {
            case WorkflowSimTags.START_SIMULATION:
                getWorkflowParser().parse();
                setTaskList(getWorkflowParser().getTaskList());
                applyTaskCostMatrix();
                validateFailureModelCoverage();
                parsedTaskSnapshot = Collections.unmodifiableList(new ArrayList<Task>(getTaskList()));
                eventRecorder.record(SimulationEventType.WORKFLOW_PARSED, CloudSim.clock(), null,
                        SimulationEventRecorder.attributes("taskCount", getTaskList().size(),
                                "inputCount", getWorkflowParser().getInputReports().size()));
                boolean explicitPlannerDecision = Parameters.getPlanningAlgorithm()
                        != PlanningAlgorithm.INVALID;
                long planningElapsedNanos = 0L;
                if (explicitPlannerDecision) {
                    long planningStart = System.nanoTime();
                    processPlanning();
                    planningElapsedNanos = System.nanoTime() - planningStart;
                } else {
                    processPlanning();
                }
                eventRecorder.record(SimulationEventType.PLANNING_COMPLETED, CloudSim.clock(), null,
                        SimulationEventRecorder.attributes("planningAlgorithm",
                                Parameters.getPlanningAlgorithm().name(),
                                "explicitPlannerDecision", explicitPlannerDecision,
                                "planningDecisionElapsedNanos", planningElapsedNanos,
                                "sharedStorageDagPlanTraceAvailable", sharedStorageDagPlanTrace != null,
                                "plannedTaskCount", sharedStorageDagPlanTrace == null ? 0
                                        : sharedStorageDagPlanTrace.getTaskPlans().size()));
                processImpactFactors(getTaskList());
                // R5 动态到达：把每个输入的提交时刻与任务归属登记给引擎；全部 t=0 时
                // 引擎行为与历史单时刻提交逐位一致。
                getWorkflowEngine().setWorkflowArrivals(Parameters.getWorkflowArrivalSeconds(),
                        getWorkflowParser().getTaskWorkflowIndices());
                sendNow(getClusteringEngineId(), WorkflowSimTags.JOB_SUBMIT, getTaskList());
                break;
            case CloudSimTags.END_OF_SIMULATION:
                shutdownEntity();
                break;
            // 未识别事件统一进入显式错误处理，避免被静默丢弃。
            default:
                processOtherEvent(ev);
                break;
        }
    }

    private void processPlanning() {
        sharedStorageDagPlanTrace = null;
        if (Parameters.getPlanningAlgorithm().equals(PlanningAlgorithm.INVALID)) {
            return;
        }
        BasePlanningAlgorithm planner = getPlanningAlgorithm(Parameters.getPlanningAlgorithm());
        
        planner.setTaskList(getTaskList());
        planner.setVmList(getWorkflowEngine().getAllVmList());
        try {
            planner.run();
            if (planner instanceof SharedStorageHeftPlanningAlgorithm) {
                sharedStorageDagPlanTrace = ((SharedStorageHeftPlanningAlgorithm) planner)
                        .getLastPlanTrace();
            } else if (planner instanceof SharedStorageCpopPlanningAlgorithm) {
                sharedStorageDagPlanTrace = ((SharedStorageCpopPlanningAlgorithm) planner)
                        .getLastPlanTrace();
            } else if (planner instanceof SharedStorageDlsPlanningAlgorithm) {
                sharedStorageDagPlanTrace = ((SharedStorageDlsPlanningAlgorithm) planner)
                        .getLastPlanTrace();
            } else if (planner instanceof SharedStorageEtfPlanningAlgorithm) {
                sharedStorageDagPlanTrace = ((SharedStorageEtfPlanningAlgorithm) planner)
                        .getLastPlanTrace();
            } else if (planner instanceof SharedStoragePeftPlanningAlgorithm) {
                sharedStorageDagPlanTrace = ((SharedStoragePeftPlanningAlgorithm) planner)
                        .getLastPlanTrace();
            }
        } catch (org.workflowsim.exception.SimulationConfigurationException exception) {
            // R8 审计修复（P1-7）：配置类异常不得被包装成"规划算法失败"的执行语义。
            throw exception;
        } catch (Exception exception) {
            throw new IllegalStateException("Planning algorithm "
                    + Parameters.getPlanningAlgorithm() + " failed", exception);
        }
    }

    /**
     * 在解析已写入实际 DAG 深度、但任务尚未规划或提交之前验证故障模型坐标覆盖。
     *
     * <p>使用平台声明的真实 VM ID，而不是 VM 列表下标；这使非连续 ID 的配置错误在
     * 任何 Job 派发前即可终止。</p>
     */
    private void validateFailureModelCoverage() {
        List<Integer> vmIds = new ArrayList<Integer>();
        for (Vm vm : getWorkflowEngine().getAllVmList()) {
            vmIds.add(Integer.valueOf(vm.getId()));
        }
        FailureParameters.validateRuntimeCoverage(getTaskList(), vmIds);
    }

    /**
     * 把可选的任务×VM 成本矩阵投影到解析出的每个逻辑任务。
     *
     * <p>矩阵必须覆盖全部任务 ID × 全部 VM ID：缺失条目在任何 Job 派发前
     * fail-fast，不做静默回退。投影完成后规划器可读取
     * {@code task.getVmExecutionCostSeconds(vmId)} 做成本估计。</p>
     */
    private void applyTaskCostMatrix() {
        if (taskCostMatrix == null) {
            return;
        }
        List<Integer> taskIds = new ArrayList<Integer>();
        for (Task task : getTaskList()) {
            taskIds.add(Integer.valueOf(task.getCloudletId()));
        }
        List<Integer> vmIds = new ArrayList<Integer>();
        for (Vm vm : getWorkflowEngine().getAllVmList()) {
            vmIds.add(Integer.valueOf(vm.getId()));
        }
        if (!taskCostMatrix.covers(taskIds, vmIds)) {
            throw new IllegalStateException("Task cost matrix does not cover every parsed task ID "
                    + taskIds + " on every VM ID " + vmIds);
        }
        for (Task task : getTaskList()) {
            java.util.Map<Integer, Double> perVm =
                    new java.util.LinkedHashMap<Integer, Double>();
            for (Integer vmId : vmIds) {
                perVm.put(vmId, Double.valueOf(
                        taskCostMatrix.getCostSeconds(task.getCloudletId(), vmId.intValue())));
            }
            task.setVmExecutionCostSeconds(perVm);
        }
    }

    /**
     * 根据配置创建规划算法实现。
     *
     * <p>枚举值到实现的映射是实验协议的一部分。共享存储 DAG 规划器会收到显式
     * {@link PlanningContext}。R9（2026-09-16）已移除与执行模型不对齐的历史
     * HEFT/DHEFT 分发分支，静态 DAG 规划一律使用受维护的 SHARED_STORAGE_*
     * 或 LOCAL_* 系列。</p>
     *
     * @param name 规划算法枚举
     * @return 对应规划算法；无效或未知枚举时为 {@code null}
     */
    private BasePlanningAlgorithm getPlanningAlgorithm(PlanningAlgorithm name) {
        BasePlanningAlgorithm planner;

        // 新增规划器时必须同步维护 Parameters 中可报告的枚举契约。
        switch (name) {
            // INVALID 不会调用此方法，保留 null 以维持历史 fail-fast 边界。
            case INVALID:
                planner = null;
                break;
            case RANDOM:
                planner = new RandomPlanningAlgorithm();
                break;
            case STATIC_OLB:
                planner = new StaticOlbPlanningAlgorithm();
                break;
            case STATIC_MET:
                planner = new StaticMetPlanningAlgorithm();
                break;
            case STATIC_MCT:
                planner = new StaticMctPlanningAlgorithm();
                break;
            case STATIC_MINMIN:
                planner = new StaticMinMinPlanningAlgorithm();
                break;
            case STATIC_MAXMIN:
                planner = new StaticMaxMinPlanningAlgorithm();
                break;
            case STATIC_SUFFERAGE:
                planner = new StaticSufferagePlanningAlgorithm();
                break;
            case STATIC_ROUND_ROBIN:
                planner = new StaticRoundRobinPlanningAlgorithm();
                break;
            case SHARED_STORAGE_HEFT:
                planner = new SharedStorageHeftPlanningAlgorithm(planningContext);
                break;
            case SHARED_STORAGE_CPOP:
                planner = new SharedStorageCpopPlanningAlgorithm(planningContext);
                break;
            case SHARED_STORAGE_DLS:
                planner = new SharedStorageDlsPlanningAlgorithm(planningContext);
                break;
            case SHARED_STORAGE_ETF:
                planner = new SharedStorageEtfPlanningAlgorithm(planningContext);
                break;
            case SHARED_STORAGE_PEFT:
                planner = new SharedStoragePeftPlanningAlgorithm(planningContext);
                break;
            case PSO:
                planner = new PSOPlanningAlgorithm();
                break;
            case LOCAL_HEFT:
                planner = new LocalHeftPlanningAlgorithm(planningContext);
                break;
            case LOCAL_CPOP:
                planner = new LocalCpopPlanningAlgorithm(planningContext);
                break;
            case LOCAL_PEFT:
                planner = new LocalPeftPlanningAlgorithm(planningContext);
                break;
            default:
                planner = null;
                break;
        }
        return planner;
    }

    /**
     * 为每个任务计算影响度，供任务平衡聚类算法使用。
     *
     * <p>每个出口任务获得相同初始影响度，并沿父边均分回传。该值是聚类启发式的内部
     * 特征，不是关键路径长度、现实重要度或实验结果指标。</p>
     *
     * @param taskList 全部任务
     */
    private void processImpactFactors(List<Task> taskList) {
        List<Task> exits = new ArrayList<>();
        for (Task task : taskList) {
            if (task.getChildList().isEmpty()) {
                exits.add(task);
            }
        }
        double avg = 1.0 / exits.size();
        for (Task task : exits) {
            addImpact(task, avg);
        }
    }

    /**
     * 将影响度累加到一个任务并沿其父边均分回传。
     *
     * @param task 接收影响度的任务
     * @param impact 要累加和回传的影响度
     */
    private void addImpact(Task task, double impact) {

        task.setImpact(task.getImpact() + impact);
        int size = task.getParentList().size();
        if (size > 0) {
            double avg = impact / size;
            for (Task parent : task.getParentList()) {
                addImpact(parent, avg);
            }
        }
    }

    /**
     * 处理未知事件并记录错误。
     *
     * @param ev 未被当前规划器识别的事件
     */
    protected void processOtherEvent(SimEvent ev) {
        if (ev == null) {
            Log.printLine(getName() + ".processOtherEvent(): " + "Error - an event is null.");
            return;
        }

        Log.printLine(getName() + ".processOtherEvent(): "
                + "Error - event unknown by this DatacenterBroker.");
    }

    /** 预留的仿真结束通知入口；当前历史实现不发送额外事件。 */
    protected void finishExecution() {
        // sendNow(getId(), CloudSimTags.END_OF_SIMULATION);
    }

    /** 关闭规划实体。 */
    @Override
    public void shutdownEntity() {
        Log.printLine(getName() + " is shutting down...");
    }

    /** 启动规划实体，并安排初始工作流解析事件。 */
    @Override
    public void startEntity() {
        Log.printLine("Starting WorkflowSim " + Parameters.getVersion());
        Log.printLine(getName() + " is starting...");
        schedule(getId(), 0, WorkflowSimTags.START_SIMULATION);
    }

    /** @return 当前待规划或待聚类的任务列表 */
    @SuppressWarnings("unchecked")
    public List<Task> getTaskList() {
        return (List<Task>) taskList;
    }

    /**
     * 返回聚类消耗可变列表前保存的已解析任务图快照。
     *
     * <p>快照中的 {@link Task} 仍是实际仿真对象，因此报告捕获到的是这些任务的最终
     * 任务级结果状态，而非输入数据的深拷贝。</p>
     *
     * @return 不可修改的已解析任务列表快照
     */
    public List<Task> getParsedTaskSnapshot() {
        return parsedTaskSnapshot;
    }

    /**
     * 返回选定规划器产生的完整静态 DAG 规划轨迹。
     *
     * @return 静态 DAG 规划轨迹；未使用相应规划器时为 {@code null}
     */
    public SharedStorageDagPlanTrace getSharedStorageDagPlanTrace() {
        return sharedStorageDagPlanTrace;
    }

    /**
     * 替换当前任务列表。
     *
     * @param taskList 新任务列表
     */
    protected void setTaskList(List<Task> taskList) {
        this.taskList = taskList;
    }
}
