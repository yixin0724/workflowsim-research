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
import java.util.List;
import org.cloudbus.cloudsim.Log;
import org.cloudbus.cloudsim.core.CloudSim;
import org.cloudbus.cloudsim.core.CloudSimTags;
import org.cloudbus.cloudsim.core.SimEntity;
import org.cloudbus.cloudsim.core.SimEvent;
import org.workflowsim.clustering.BasicClustering;
import org.workflowsim.clustering.BlockClustering;
import org.workflowsim.clustering.HorizontalClustering;
import org.workflowsim.clustering.VerticalClustering;
import org.workflowsim.clustering.balancing.BalancedClustering;
import org.workflowsim.experiment.SimulationEventRecorder;
import org.workflowsim.experiment.SimulationEventType;
import org.workflowsim.scheduling.StaticSchedulePlan;
import org.workflowsim.utils.ClusteringParameters;
import org.workflowsim.utils.Parameters;
import org.workflowsim.utils.Parameters.ClassType;
import org.workflowsim.utils.ReplicaCatalog;
import org.workflowsim.utils.SimulationConstants;

/**
 * 可选的任务聚类与统一 stage-in 构造实体。
 *
 * <p>该实体将任务 DAG 合并为 {@link Job}，随后创建一个收集工作流外部输入文件的 stage-in
 * 作业并交给 {@link WorkflowEngine}。stage-in 是简化数据准备模型：它不模拟分块传输、
 * 链路争用、缓存一致性或真实存储协议。</p>
 *
 * @author Weiwei Chen
 * @since WorkflowSim Toolkit 1.0
 * @date Apr 9, 2013
 *
 */
public final class ClusteringEngine extends SimEntity {

    /** 待聚类的原始任务列表。 */
    protected List< Task> taskList;
    /** 聚类后待执行的作业列表。 */
    protected List<Job> jobList;
    /** 已提交任务列表。 */
    protected List<? extends Task> taskSubmittedList;
    /** 已接收任务列表。 */
    protected List<? extends Task> taskReceivedList;
    /** 已提交任务数。 */
    protected int cloudletsSubmitted;
    /** 当前选定的具体聚类实现。 */
    protected BasicClustering engine;
    /** 下游工作流引擎的 CloudSim 实体编号。 */
    private final int workflowEngineId;
    /** 下游工作流引擎。 */
    private final WorkflowEngine workflowEngine;
    private SimulationEventRecorder eventRecorder = SimulationEventRecorder.disabled();

    /**
     * 创建聚类实体及其下游工作流引擎。
     *
     * @param name CloudSim 实体名称
     * @param schedulers 下游运行时调度器数量
     * @throws Exception 当 CloudSim 实体无法创建时
     */
    public ClusteringEngine(String name, int schedulers) throws Exception {
        super(name);
        setJobList(new ArrayList<>());
        setTaskList(new ArrayList<>());
        setTaskSubmittedList(new ArrayList<>());
        setTaskReceivedList(new ArrayList<>());

        cloudletsSubmitted = 0;
        this.workflowEngine = new WorkflowEngine(name + "_Engine_0", schedulers);
        this.workflowEngineId = this.workflowEngine.getId();
    }

    /** @return 下游工作流引擎的 CloudSim 实体编号 */
    public int getWorkflowEngineId() {
        return this.workflowEngineId;
    }

    /** @return 下游工作流引擎 */
    public WorkflowEngine getWorkflowEngine() {
        return this.workflowEngine;
    }

    /**
     * 安装本次运行私有的事件记录器，并传播给工作流引擎。
     *
     * @param value 本次运行的事件记录器
     */
    public void setEventRecorder(SimulationEventRecorder value) {
        if (value == null) {
            throw new IllegalArgumentException("Event recorder cannot be null");
        }
        this.eventRecorder = value;
        this.workflowEngine.setEventRecorder(value);
    }

    /**
     * 追加一批待聚类任务。
     *
     * @param list 待聚类任务列表
     */
    public void submitTaskList(List<Task> list) {
        getTaskList().addAll(list);
    }

    /**
     * 根据当前配置选择聚类实现并生成作业。
     *
     * <p>未启用聚类时使用 {@link BasicClustering}，保留原始任务粒度。</p>
     */
    protected void processClustering() {

        // 聚类方法和粒度均来自本次会话的显式配置。
        ClusteringParameters params = Parameters.getClusteringParameters();
        switch (params.getClusteringMethod()) {
            // 水平聚类按同一 DAG 层级分组合并。
            case HORIZONTAL:
                // 优先按每层目标作业数构造。
                if (params.getClustersNum() != 0) {
                    this.engine = new HorizontalClustering(params.getClustersNum(), 0);
                } // 否则按每个作业的目标任务数构造。
                else if (params.getClustersSize() != 0) {
                    this.engine = new HorizontalClustering(0, params.getClustersSize());
                }
                break;
            // 垂直聚类沿 DAG 边合并，当前入口从第一层开始。
            case VERTICAL:
                int depth = 1;
                this.engine = new VerticalClustering(depth);
                break;
            // 块聚类由实现类按数量和大小参数解释。
            case BLOCK:
                this.engine = new BlockClustering(params.getClustersNum(), params.getClustersSize());
                break;
            // 平衡聚类使用规划阶段计算的任务影响度。
            case BALANCED:
                this.engine = new BalancedClustering(params.getClustersNum());
                break;
            // 默认不合并任务，保留一任务一作业的执行粒度。
            default:
                this.engine = new BasicClustering();
                break;
        }
        engine.setTaskList(getTaskList());
        engine.run();
        setJobList(engine.getJobList());
        eventRecorder.record(SimulationEventType.JOBS_CLUSTERED, CloudSim.clock(), null,
                SimulationEventRecorder.attributes("sourceTaskCount", getTaskList().size(),
                        "jobCount", getJobList().size(), "clusteringMethod",
                        params.getClusteringMethod().name()));
    }

    /**
     * 为所有真实外部输入文件创建一个统一的 stage-in 作业。
     *
     * <p>该历史模型把全部外部输入汇聚到一个最小长度的前置作业，并让所有根作业依赖它。
     * 这不是实际数据移动调度器；真实链路并发、传输重叠和存储容量不在此处建模。</p>
     */
    protected void processDatastaging() {

        // 聚类引擎汇集了当前工作流中所有关联文件。
        List<FileItem> list = this.engine.getTaskFiles();
        /*
         * 历史 CloudSim 路径要求 stage-in Cloudlet 至少为指定的最小长度；
         * 使用当前作业数作为编号以延续既有作业编号约定。
         */
        Job job = new Job(getJobList().size(), SimulationConstants.STAGE_IN_JOB_LENGTH_MI);

        // 统一 stage-in 作业关联所有外部输入，以便既有执行路径计算传输延迟。
        List<FileItem> fileList = new ArrayList<>();
        for (FileItem file : list) {
            // 每个真实外部输入文件只登记一次。
            if (file.isRealInputFile(list)) {
                ReplicaCatalog.addFileToStorage(file.getName(), Parameters.SOURCE);
                fileList.add(file);
            }
        }
        job.setFileList(fileList);
        job.setClassType(ClassType.STAGE_IN.value);

        // stage-in 必须先于工作流根作业执行。
        job.setDepth(0);
        job.setPriority(0);

        // 历史语义：多调度器场景下 stage-in 固定交给第一个调度器。
        job.setUserId(getWorkflowEngine().getSchedulerId(0));

        // 所有根作业依赖 stage-in 作业，形成统一数据准备前置条件。
        for (Job cJob : getJobList()) {
            // 根作业没有父作业，是 stage-in 的直接后继。
            if (cJob.getParentList().isEmpty()) {
                cJob.addParent(job);
                job.addChild(cJob);
            }
        }
        getJobList().add(job);
        long inputBytes = 0L;
        for (FileItem file : fileList) {
            inputBytes += file.getSize();
        }
        eventRecorder.record(SimulationEventType.STAGE_IN_JOB_CREATED, CloudSim.clock(), job,
                SimulationEventRecorder.attributes("inputFileCount", fileList.size(),
                        "inputBytes", inputBytes));
    }

    /**
     * 处理聚类生命周期中的 CloudSim 事件。
     *
     * @param ev 接收的事件
     */
    @Override
    public void processEvent(SimEvent ev) {

        switch (ev.getTag()) {
            case WorkflowSimTags.START_SIMULATION:
                break;
            case WorkflowSimTags.JOB_SUBMIT:
                List list = (List) ev.getData();
                setTaskList(list);
                // 未配置聚类时 processClustering 保留任务粒度，不会强制合并。
                processClustering();
                // 追加统一 stage-in 作业，表达简化的工作流输入准备阶段。
                processDatastaging();
                getWorkflowEngine().setStaticSchedulePlan(StaticSchedulePlan.fromJobs(getJobList()));
                sendNow(this.workflowEngineId, WorkflowSimTags.JOB_SUBMIT, getJobList());
                break;
            case CloudSimTags.END_OF_SIMULATION:
                shutdownEntity();
                break;
            default:
                processOtherEvent(ev);
                break;
        }
    }

    /**
     * 处理未知事件并记录错误。
     *
     * @param ev 未被当前聚类实体识别的事件
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

    /** 关闭聚类实体。 */
    @Override
    public void shutdownEntity() {
        Log.printLine(getName() + " is shutting down...");
    }

    /** 启动聚类实体，并安排初始事件。 */
    @Override
    public void startEntity() {
        Log.printLine(getName() + " is starting...");
        schedule(getId(), 0, WorkflowSimTags.START_SIMULATION);
    }

    /** @return 当前待聚类任务列表 */
    @SuppressWarnings("unchecked")
    public List<Task> getTaskList() {
        return (List<Task>) taskList;
    }

    /** @return 当前聚类后作业列表 */
    public List<Job> getJobList() {
        return jobList;
    }

    /**
     * 替换待聚类任务列表。
     *
     * @param taskList 新任务列表
     */
    protected void setTaskList(List<Task> taskList) {
        this.taskList = taskList;
    }

    /**
     * 替换聚类后作业列表。
     *
     * @param jobList 新作业列表
     */
    protected void setJobList(List<Job> jobList) {
        this.jobList = jobList;
    }

    /** @return 已提交任务列表 */
    @SuppressWarnings("unchecked")
    public List<Task> getTaskSubmittedList() {
        return (List<Task>) taskSubmittedList;
    }

    /**
     * 替换已提交任务列表。
     *
     * @param taskSubmittedList 新的已提交任务列表
     */
    protected void setTaskSubmittedList(List<Task> taskSubmittedList) {
        this.taskSubmittedList = taskSubmittedList;
    }

    /** @return 已接收任务列表 */
    @SuppressWarnings("unchecked")
    public List<Task> getTaskReceivedList() {
        return (List<Task>) taskReceivedList;
    }

    /**
     * 替换已接收任务列表。
     *
     * @param taskReceivedList 新的已接收任务列表
     */
    protected void setTaskReceivedList(List<Task> taskReceivedList) {
        this.taskReceivedList = taskReceivedList;
    }
}
