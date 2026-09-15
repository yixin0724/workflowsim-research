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
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.cloudbus.cloudsim.Cloudlet;
import org.cloudbus.cloudsim.Consts;
import org.cloudbus.cloudsim.Log;
import org.cloudbus.cloudsim.Vm;
import org.cloudbus.cloudsim.core.CloudSim;
import org.cloudbus.cloudsim.core.CloudSimTags;
import org.cloudbus.cloudsim.core.SimEntity;
import org.cloudbus.cloudsim.core.SimEvent;
import org.workflowsim.data.TransferContentionEngine;
import org.workflowsim.experiment.SimulationEventRecorder;
import org.workflowsim.experiment.SimulationEventType;
import org.workflowsim.failure.FailureParameters;
import org.workflowsim.failure.RetryLimitExceededException;
import org.workflowsim.reclustering.ReclusteringEngine;
import org.workflowsim.scheduling.StaticSchedulePlan;
import org.workflowsim.utils.Parameters;
import org.workflowsim.utils.Parameters.ClassType;
import org.workflowsim.utils.ReplicaCatalog;

/**
 * 面向一个工作流用户的引擎实体，负责把工作流 Job 与运行时 {@link WorkflowScheduler}
 * 连接起来。虚拟机的创建、Job 向 VM 的实际提交和 VM 销毁均由调度器处理，本类只维护
 * 工作流级队列、依赖门控和引擎与调度器之间的事件流。
 *
 * <p>正常生命周期为：启动后请求资源特征；各调度器在 VM 创建完成后发送
 * {@link org.cloudbus.cloudsim.core.CloudSimTags#CLOUDLET_SUBMIT}；引擎找出父依赖已
 * 返回的 Job 并将其交给对应调度器；调度器返回 Job 后，引擎继续释放新就绪 Job 或结束仿真。
 *
 * <p>失败 Job 的重试图由 {@link ReclusteringEngine} 生成。引擎只保证为新 Job 分配不与
 * 待提交、已提交或已返回 Job 冲突的标识，并记录重试事件；它不在此处定义重试图如何重连依赖。
 *
 * @author Weiwei Chen
 * @since WorkflowSim Toolkit 1.0
 * @date Apr 9, 2013
 */
public final class WorkflowEngine extends SimEntity {

    /** 等待依赖门控并将被提交给调度器的 Job 队列。 */
    protected List<? extends Cloudlet> jobsList;
    /** 已向调度器提交过的 Job 历史列表，不会在收到返回事件时移除。 */
    protected List<? extends Cloudlet> jobsSubmittedList;
    /** 已收到返回事件的 Job 列表；其中可包含失败尝试，供依赖门控与重试流程使用。 */
    protected List<? extends Cloudlet> jobsReceivedList;
    /**
     * {@link #jobsReceivedList} 的 Job ID 镜像索引（PLAT-18）。
     *
     * <p>依赖门控需要频繁判断父 Job 是否已返回；历史实现对列表做线性扫描，
     * 大工作流下整体复杂度为 O(n²)。本索引提供 O(1) 查询，且与列表同步维护。</p>
     */
    private final Set<Integer> receivedJobIds = new HashSet<Integer>();
    /**
     * retry Job ID → 失败原 Job ID 的映射（PLAT-13）。
     *
     * <p>用于在 retry Job 的 {@link SimulationEventType#JOB_READY} 事件上补充
     * {@code retryOfFailedJobId} 属性，使下游事件消费者无需回溯
     * RETRY_JOB_CREATED 即可识别重试尝试。</p>
     */
    private final Map<Integer, Integer> retryOfFailedJobIds = new HashMap<Integer, Integer>();
    /** 当前已提交但尚未返回的 Job 数量，用于判定整个工作流是否终止。 */
    protected int jobsSubmitted;
    /** 默认调度器提交的 VM 列表；多调度器模式下可能为 {@code null}。 */
    protected List<? extends Vm> vmList;
    /** 与 {@link #scheduler} 位置一一对应的调度器实体 ID。 */
    private List<Integer> schedulerId;
    /** 本引擎创建并管理的运行时调度器，列表下标是本类 API 使用的调度器索引。 */
    private List<WorkflowScheduler> scheduler;
    /** 本次仿真私有的证据记录器，默认禁用。 */
    private SimulationEventRecorder eventRecorder = SimulationEventRecorder.disabled();
    /** 本引擎在当前运行中已创建的 retry Job 数，仅用于有限故障模型的停止契约。 */
    private int retryJobsCreated;
    /**
     * 最近绑定的数据中心实体 ID（COMM-1 修复）。
     *
     * <p>执行前传输延迟模型（{@code PRE_EXECUTION_TRANSFER_DELAY_V1}）需要在数据就绪时
     * 向数据中心请求传输秒数估算与副本登记，因此引擎需要保存数据中心实体引用。</p>
     */
    private int boundDatacenterId = -1;
    /**
     * 链路争用传输 ID → 所属计算 Job 的映射（R2 争用模型）。
     *
     * <p>争用模型下一条传输组完成时经本映射定位所属 Job；一个 Job 的全部传输组
     * 完成后才登记输入副本并释放。按插入顺序迭代保证确定性。</p>
     */
    private final Map<Long, Job> contentionTransferJobs = new LinkedHashMap<Long, Job>();
    /**
     * 计算 Job ID → 其未完成争用传输组 ID 集合的映射（R2 争用模型）。
     */
    private final Map<Integer, Set<Long>> contentionJobPendingTransfers =
            new LinkedHashMap<Integer, Set<Long>>();
    /** 引擎分配的下一个争用传输 ID（确定性递增）。 */
    private long nextContentionTransferId = 1L;

    /** R5 动态到达：每个工作流输入的提交时刻（模拟秒）；null 表示未配置（全部 t=0）。 */
    private List<Double> workflowArrivalSeconds;

    /** R5 动态到达：任务编号→来源输入下标的映射。 */
    private Map<Integer, Integer> taskWorkflowIndices = Collections.emptyMap();

    /** 已排入事件队列的到达重扫时刻；null 表示没有待处理的到达重扫。 */
    private Double pendingArrivalScanSecond;

    /** 已记录 WORKFLOW_ARRIVED 证据的工作流下标集合。 */
    private final Set<Integer> announcedWorkflowIndices = new HashSet<>();

    /**
     * 创建包含一个运行时调度器的工作流引擎。
     *
     * @param name SimEntity 名称，必须满足 CloudSim 对实体名称的要求
     * @throws Exception 当父类实体初始化失败时抛出
     * @pre name != null
     * @post $none
     */
    public WorkflowEngine(String name) throws Exception {
        this(name, 1);
    }

    /**
     * 创建指定数量的运行时调度器，并建立它们到本引擎的反向关联。
     *
     * <p>{@code schedulers} 是数量而不是调度器实体 ID。常规运行要求它为正数；本构造器
     * 保留历史行为，不额外拒绝零或负数，后续使用默认调度器 API 时则可能因不存在下标 0 而失败。
     *
     * @param name SimEntity 名称
     * @param schedulers 要创建的调度器数量
     * @throws Exception 当父类实体或子调度器初始化失败时抛出
     */
    public WorkflowEngine(String name, int schedulers) throws Exception {
        super(name);

        setJobsList(new ArrayList<>());
        setJobsSubmittedList(new ArrayList<>());
        setJobsReceivedList(new ArrayList<>());

        jobsSubmitted = 0;
        retryJobsCreated = 0;

        setSchedulers(new ArrayList<>());
        setSchedulerIds(new ArrayList<>());

        for (int i = 0; i < schedulers; i++) {
            WorkflowScheduler wfs = new WorkflowScheduler(name + "_Scheduler_" + i);
            getSchedulers().add(wfs);
            getSchedulerIds().add(wfs.getId());
            wfs.setWorkflowEngineId(this.getId());
        }
    }

    /**
     * 将待创建 VM 列表转交给指定运行时调度器。
     *
     * @param list 待创建的 VM 列表
     * @param schedulerId 调度器<strong>索引</strong>，并非 CloudSim 调度器实体 ID
     */
    public void submitVmList(List<? extends Vm> list, int schedulerId) {
        getScheduler(schedulerId).submitVmList(list);
    }

    /**
     * 将 VM 列表交给默认调度器（索引 0），并保存该列表供 {@link #getAllVmList()} 直接返回。
     *
     * <p>这是单调度器时代的兼容入口。多调度器调用方应使用带调度器索引的重载；否则本字段
     * 只能反映默认调度器的 VM 列表。
     *
     * @param list 待创建的 VM 列表
     */
    public void submitVmList(List<? extends Vm> list) {
        // 兼容历史单调度器入口；多调度器场景应显式指定目标索引。
        getScheduler(0).submitVmList(list);
        setVmList(list);
    }

    /**
     * 返回引擎可见的 VM 列表。
     *
     * <p>若默认调度器入口保存过非空 VM 列表，则直接返回该列表；否则按调度器顺序汇总各调度器
     * 的 VM 列表。返回的是历史 API 的可变列表视图/汇总列表，调用方不应借此修改调度器状态。
     *
     * @return 默认 VM 列表，或按调度器顺序汇总的 VM 列表
     */
    public List<? extends Vm> getAllVmList(){
        if(this.vmList != null && !this.vmList.isEmpty()){
            return this.vmList;
        }
        else{
            List list = new ArrayList();
            for(int i = 0;i < getSchedulers().size();i ++){
                list.addAll(getScheduler(i).getVmList());
            }
            return list;
        }
    }

    /**
     * 安装本次运行私有的事件记录器，并向当前所有调度器传播同一实例。
     *
     * @param value 非空记录器
     * @throws IllegalArgumentException 当记录器为 {@code null} 时抛出
     */
    public void setEventRecorder(SimulationEventRecorder value) {
        if (value == null) {
            throw new IllegalArgumentException("Event recorder cannot be null");
        }
        this.eventRecorder = value;
        for (WorkflowScheduler item : getSchedulers()) {
            item.setEventRecorder(value);
        }
    }

    /**
     * 将聚类后的静态执行计划传播到每个运行时调度器。
     *
     * <p>计划中的 Job ID 必须对应聚类后的 Job，而不是解析阶段的原始 Task ID。空计划保留
     * 映射语义，由调度器按常规就绪规则提交。
     *
     * @param value 非空的运行私有计划
     * @throws IllegalArgumentException 当计划为 {@code null} 时抛出
     */
    public void setStaticSchedulePlan(StaticSchedulePlan value) {
        if (value == null) {
            throw new IllegalArgumentException("Static schedule plan cannot be null");
        }
        for (WorkflowScheduler item : getSchedulers()) {
            item.setStaticSchedulePlan(value);
        }
    }

    /**
     * 将 Job 加入待依赖门控队列。
     *
     * <p>该方法只追加列表，不直接发送提交事件；实际释放由引擎生命周期中的
     * {@link #submitJobs()} 完成。
     *
     * @param list 待处理的 Job/Cloudlet 列表
     */
    public void submitCloudletList(List<? extends Cloudlet> list) {
        getJobsList().addAll(list);
    }

    /**
     * 分发工作流引擎收到的事件。
     *
     * <p>资源特征请求会广播给调度器；{@code CLOUDLET_SUBMIT} 触发就绪 Job 扫描；
     * {@code CLOUDLET_RETURN} 驱动重试或后继释放；{@code JOB_SUBMIT} 用事件负载替换待
     * 提交列表；仿真结束事件关闭本实体。未知标签仅记录诊断信息。
     *
     * @param ev CloudSim 事件
     */
    @Override
    public void processEvent(SimEvent ev) {
        switch (ev.getTag()) {
            // 启动时将资源特征请求转发给每个运行时调度器。
            case CloudSimTags.RESOURCE_CHARACTERISTICS_REQUEST:
                processResourceCharacteristicsRequest(ev);
                break;
            // 调度器在 VM 创建完成后通知引擎开始释放当前就绪 Job。
            case CloudSimTags.CLOUDLET_SUBMIT:
                submitJobs();
                break;
            case CloudSimTags.CLOUDLET_RETURN:
                processJobReturn(ev);
                break;
            case CloudSimTags.END_OF_SIMULATION:
                shutdownEntity();
                break;
            case WorkflowSimTags.JOB_SUBMIT:
                processJobSubmit(ev);
                break;
            case WorkflowSimTags.JOB_STAGE_IN_COMPLETE:
                // COMM-1 修复：执行前传输延迟模型下，传输窗口结束并把计算 Job 释放给调度器。
                processPreExecutionStageInComplete(ev);
                break;
            case WorkflowSimTags.TRANSFER_CONTENTION_CHECK:
                // R2 链路争用模型：按当前时钟积分推进全部活动传输并结算完成的传输组。
                processTransferContentionCheck(ev);
                break;
            case WorkflowSimTags.WORKFLOW_ARRIVAL_SCAN:
                // R5 动态到达：最早未到达时刻触发的幂等就绪重扫。
                pendingArrivalScanSecond = null;
                submitJobs();
                break;
            default:
                processOtherEvent(ev);
                break;
        }
    }

    /**
     * 将资源特征请求广播给全部运行时调度器。
     *
     * @param ev 触发广播的 CloudSim 事件；事件负载不参与处理
     */
    protected void processResourceCharacteristicsRequest(SimEvent ev) {
        for (int i = 0; i < getSchedulerIds().size(); i++) {
            schedule(getSchedulerId(i), 0, CloudSimTags.RESOURCE_CHARACTERISTICS_REQUEST);
        }
    }

    /**
     * 将一个运行时调度器绑定到数据中心。
     *
     * @param datacenterId CloudSim 数据中心实体 ID
     * @param schedulerId 调度器<strong>索引</strong>，并非调度器实体 ID
     */
    public void bindSchedulerDatacenter(int datacenterId, int schedulerId) {
        getScheduler(schedulerId).bindSchedulerDatacenter(datacenterId);
        // COMM-1 修复：执行前传输延迟模型需要引擎直接访问数据中心（估算与副本登记）。
        this.boundDatacenterId = datacenterId;
    }

    /**
     * 将数据中心绑定到默认调度器（索引 0）。
     *
     * @param datacenterId CloudSim 数据中心实体 ID
     */
    public void bindSchedulerDatacenter(int datacenterId) {
        bindSchedulerDatacenter(datacenterId, 0);
    }
   
    /**
     * 处理 {@link WorkflowSimTags#JOB_SUBMIT}：以事件负载中的列表替换当前待提交队列，
     * 并立即触发一轮就绪扫描。
     *
     * <p>该路径不是追加语义；发送方必须提供完整的待处理列表，并保证元素属于本引擎的
     * 工作流上下文。
     *
     * <p><strong>幂等释放扫描：</strong>首次就绪释放原本依赖 t=0 事件的 FIFO 顺序
     * （Planner → Clustering → Engine 链先于调度器 VM 创建链，即 JOB_SUBMIT 先于
     * 调度器的 CLOUDLET_SUBMIT 到达）。若该顺序被打破（JOB_SUBMIT 后于
     * CLOUDLET_SUBMIT 被处理），CLOUDLET_SUBMIT 触发的扫描面对空队列空跑，之后
     * 无人再触发释放，工作流将静默不执行。因此本方法在替换队列后主动调用
     * {@link #submitJobs()}；该方法只释放父依赖已满足的作业并从队列移除已释放
     * 作业，重复调用不会造成重复提交。
     *
     * @param ev 负载为 Job/Cloudlet 列表的事件
     */
    protected void processJobSubmit(SimEvent ev) {
        List<? extends Cloudlet> list = (List) ev.getData();
        setJobsList(list);
        // 幂等就绪扫描：不依赖 JOB_SUBMIT 与 CLOUDLET_SUBMIT 的事件到达顺序。
        submitJobs();
    }

    /**
     * R5 动态到达：登记每个工作流输入的提交时刻与任务归属映射。
     *
     * <p>由 {@link WorkflowPlanner} 在解析完成后、投递任务前调用。{@code arrivalSeconds}
     * 为 {@code null} 或全部为 0.0 时行为与历史单时刻提交逐位一致。</p>
     *
     * @param arrivalSeconds 每个工作流输入的提交时刻（模拟秒），与输入路径一一对应
     * @param taskWorkflowIndices 任务编号→来源输入下标的映射
     */
    public void setWorkflowArrivals(List<Double> arrivalSeconds,
            Map<Integer, Integer> taskWorkflowIndices) {
        this.workflowArrivalSeconds = arrivalSeconds == null
                ? null : new ArrayList<>(arrivalSeconds);
        this.taskWorkflowIndices = taskWorkflowIndices == null
                ? Collections.<Integer, Integer>emptyMap()
                : new HashMap<>(taskWorkflowIndices);
    }

    /**
     * 返回指定 Job 所属工作流输入的提交时刻（模拟秒）。
     *
     * @param job 待查询的 Job
     * @return 提交时刻；未配置到达信息或映射缺失时为 0.0
     */
    private double arrivalSecondFor(Job job) {
        if (workflowArrivalSeconds == null || taskWorkflowIndices.isEmpty()) {
            return 0.0;
        }
        Integer workflowIndex = taskWorkflowIndices.get(job.getCloudletId());
        if (workflowIndex == null || workflowIndex < 0
                || workflowIndex >= workflowArrivalSeconds.size()) {
            return 0.0;
        }
        Double arrivalSecond = workflowArrivalSeconds.get(workflowIndex);
        return arrivalSecond == null ? 0.0 : arrivalSecond;
    }

    /**
     * R5 动态到达是否产生 WORKFLOW_ARRIVED 证据。
     *
     * <p>仅当提交是多输入或含非零提交时刻时启用，保证历史单工作流 t=0 提交的事件
     * 轨迹逐位不变。</p>
     */
    private boolean arrivalEvidenceEnabled() {
        if (workflowArrivalSeconds == null) {
            return false;
        }
        if (workflowArrivalSeconds.size() > 1) {
            return true;
        }
        for (Double arrivalSecond : workflowArrivalSeconds) {
            if (arrivalSecond != null && arrivalSecond > 0.0) {
                return true;
            }
        }
        return false;
    }

    /**
     * 为刚越过到达门控的 Job 所属工作流记录一次 WORKFLOW_ARRIVED 证据（每工作流一次）。
     *
     * @param job 刚被释放的 Job
     */
    private void announceWorkflowArrivalFor(Job job) {
        if (!arrivalEvidenceEnabled()) {
            return;
        }
        Integer workflowIndex = taskWorkflowIndices.get(job.getCloudletId());
        if (workflowIndex == null || announcedWorkflowIndices.contains(workflowIndex)) {
            return;
        }
        announcedWorkflowIndices.add(workflowIndex);
        double arrivalSecond = workflowArrivalSeconds.get(workflowIndex);
        eventRecorder.record(SimulationEventType.WORKFLOW_ARRIVED, CloudSim.clock(), job,
                SimulationEventRecorder.attributes("workflowIndex", workflowIndex,
                        "arrivalSecond", arrivalSecond));
    }

    /**
     * 在最早未到达的提交时刻排入一次到达重扫事件；更早的重扫已排队时不重复排队。
     *
     * @param arrivalSecond 触发重扫的目标时刻
     */
    private void scheduleArrivalRescan(double arrivalSecond) {
        if (pendingArrivalScanSecond == null || arrivalSecond < pendingArrivalScanSecond) {
            schedule(getId(), arrivalSecond - CloudSim.clock(),
                    WorkflowSimTags.WORKFLOW_ARRIVAL_SCAN, null);
            pendingArrivalScanSecond = arrivalSecond;
        }
    }

    /**
     * COMM-1 修复：执行前传输延迟模型下返回计算 Job 在数据就绪时点需要持有的秒数。
     *
     * <p>语义对齐 Topcuoglu TPDS 2002 列表调度的 AST 公式：每个父任务的输入文件在该父任务
     * 完成时刻开始传输且并行传输，Job 在最后一个传输完成时点变为可派发，传输窗口可与目标
     * VM 的忙碌期重叠；VM 只被计算 MI 占用。带宽规则沿用历史 WorkflowSim v1 估算
     * （SOURCE→VM 取目标 VM 带宽、VM→VM 取 {@code min(bw)}、目标 VM 已有副本为零）。
     * 适用时记录 {@link SimulationEventType#DATA_STAGE_IN_MODELED} 证据；无需持有时立即
     * 在目标 VM 登记输入副本（与传输完成后登记的时点一致）。</p>
     *
     * @param job 刚被依赖门控释放的 Job
     * @return 负值表示模型不适用（走历史释放路径）；0 表示无需持有；正值表示需要持有的秒数
     */
    private double preExecutionStageInHoldSeconds(Job job) {
        if (boundDatacenterId < 0) {
            return -1.0;
        }
        SimEntity entity = CloudSim.getEntity(boundDatacenterId);
        if (!(entity instanceof WorkflowDatacenter)) {
            return -1.0;
        }
        WorkflowDatacenter datacenter = (WorkflowDatacenter) entity;
        if (!datacenter.getDataMovementModel().isPreExecutionTransferDelayV1()) {
            return -1.0;
        }
        if (job.getClassType() != ClassType.COMPUTE.value) {
            return -1.0;
        }
        // 防御性守卫：就绪时刻没有静态 VM 映射时无法估计目标侧传输（配置层已拒绝
        // INVALID 规划层 + 新数据移动模型的组合，此处仅作兜底，避免运行期崩溃）。
        if (job.getVmId() < 0) {
            return -1.0;
        }
        // 新数据移动模型下，计算 Job 统一记录传输证据（即使没有真实输入文件，
        // 与历史提交路径的证据覆盖保持一致）。
        List<FileItem> fileList = job.getFileList();

        double now = CloudSim.clock();
        double totalSeconds = 0.0;
        long requiredBytes = 0L;
        int fileCount = 0;
        double maxArrival = now;
        Set<String> attributedNames = new HashSet<String>();
        try {
            // 每个父任务的文件在其完成时刻开始传输；到达相互独立，取最晚到达。
            for (Object parentObj : job.getParentList()) {
                Job parent = (Job) parentObj;
                Set<String> produced = new HashSet<String>();
                for (FileItem producedFile : parent.getFileList()) {
                    if (producedFile.getType() == Parameters.FileType.OUTPUT) {
                        produced.add(producedFile.getName());
                    }
                }
                List<FileItem> fromParent = new ArrayList<FileItem>();
                for (FileItem file : fileList) {
                    if (file.isRealInputFile(fileList) && produced.contains(file.getName())) {
                        fromParent.add(file);
                        attributedNames.add(file.getName());
                    }
                }
                if (fromParent.isEmpty()) {
                    continue;
                }
                double seconds = datacenter.estimateTransferSecondsForFiles(fromParent, job);
                totalSeconds += seconds;
                maxArrival = Math.max(maxArrival, parent.getFinishTime() + seconds);
            }
            // 未由任何父任务产生的外部输入（SOURCE 副本）自始可用，传输自就绪时刻开始。
            List<FileItem> external = new ArrayList<FileItem>();
            for (FileItem file : fileList) {
                if (file.isRealInputFile(fileList) && !attributedNames.contains(file.getName())) {
                    external.add(file);
                }
            }
            if (!external.isEmpty()) {
                double seconds = datacenter.estimateTransferSecondsForFiles(external, job);
                totalSeconds += seconds;
                maxArrival = Math.max(maxArrival, now + seconds);
            }
            for (FileItem file : fileList) {
                if (file.isRealInputFile(fileList)) {
                    requiredBytes += file.getSize();
                    fileCount++;
                }
            }
        } catch (Exception e) {
            throw new IllegalStateException("WorkflowEngine could not estimate pre-execution stage-in for Job "
                    + job.getCloudletId(), e);
        }

        double delay = maxArrival - now;
        eventRecorder.record(SimulationEventType.DATA_STAGE_IN_MODELED, now, job,
                SimulationEventRecorder.attributes("modeledTransferSeconds", totalSeconds,
                        "requiredFileBytes", (double) requiredBytes,
                        "modeledTransferFileCount", fileCount,
                        "dataMovementModel", datacenter.getDataMovementModel().getKind().name(),
                        "dataArrivalDelaySeconds", Math.max(delay, 0.0)));
        if (delay <= 0.0) {
            // 传输已在就绪前完成（或全部为本地数据）；立即登记副本，Job 走常规释放路径。
            try {
                datacenter.registerStageInReplicasForComputeJob(job);
            } catch (Exception e) {
                throw new IllegalStateException("WorkflowEngine could not register pre-execution stage-in "
                        + "replicas for Job " + job.getCloudletId(), e);
            }
            return 0.0;
        }
        // 事件延迟不得小于最小事件间隔，避免影响 CloudSim 事件定序。
        return Math.max(delay, CloudSim.getMinTimeBetweenEvents());
    }

    /**
     * 处理 {@link WorkflowSimTags#JOB_STAGE_IN_COMPLETE}：执行前传输窗口结束，在目标 VM
     * 登记输入副本并把计算 Job 释放给所属调度器。
     *
     * <p>副本只在传输完成时登记，传输窗口内其他 Job 的估算不会把这些文件误判为本地已有。
     * 释放批次沿用工作流引擎延迟配置（受控模型下为零）。</p>
     *
     * @param ev 负载为本引擎持有的计算 Job 的事件
     */
    protected void processPreExecutionStageInComplete(SimEvent ev) {
        Job job = (Job) ev.getData();
        WorkflowDatacenter datacenter = (WorkflowDatacenter) CloudSim.getEntity(boundDatacenterId);
        try {
            datacenter.registerStageInReplicasForComputeJob(job);
        } catch (Exception e) {
            throw new IllegalStateException("WorkflowEngine could not register pre-execution stage-in "
                    + "replicas for Job " + job.getCloudletId(), e);
        }
        List<Job> batch = new ArrayList<Job>();
        batch.add(job);
        double delay = 0.0;
        if (Parameters.getOverheadParams().getWEDDelay() != null) {
            delay = Parameters.getOverheadParams().getWEDDelay(batch);
        }
        schedule(job.getUserId(), delay, CloudSimTags.CLOUDLET_SUBMIT, batch);
    }

    /**
     * R2 链路争用模型：在数据就绪时把计算 Job 的输入传输组登记进流体争用引擎。
     *
     * <p>传输组划分与无争用执行前模型一致（按父任务产出归组 + 外部输入一组）；每组
     * 的名义速率取历史带宽规则的聚合速率（字节量 / 无争用估算秒数）。组内文件串行、
     * 组间在同一 VM 端点上公平共享容量。传输组在数据就绪时刻统一开始（不追溯父任务
     * 更早完成时点的部分进度），因此完成时刻不早于无争用模型的对应值。</p>
     *
     * <p>全部输入为本地副本（零传输）时立即登记副本并释放，与无争用模型的零持有
     * 语义一致；否则调度 {@link WorkflowSimTags#TRANSFER_CONTENTION_CHECK} 检查事件。</p>
     *
     * @param job 刚被依赖门控释放的计算 Job
     * @return true 表示争用路径已接管本 Job；false 表示模型不适用（走常规释放路径）
     */
    private boolean startContentionStageIn(Job job) {
        if (boundDatacenterId < 0) {
            return false;
        }
        SimEntity entity = CloudSim.getEntity(boundDatacenterId);
        if (!(entity instanceof WorkflowDatacenter)) {
            return false;
        }
        WorkflowDatacenter datacenter = (WorkflowDatacenter) entity;
        boolean fatTree = datacenter.getDataMovementModel().isFatTreeContentionV1();
        if (!datacenter.getDataMovementModel().isPreExecutionTransferDelayWithContentionV1()
                && !fatTree) {
            return false;
        }
        if (job.getClassType() != ClassType.COMPUTE.value) {
            return false;
        }
        // 防御性守卫：就绪时刻没有静态 VM 映射时无法确定目标端点（配置层已拒绝
        // INVALID 规划层 + 争用模型的组合，此处仅作兜底）。
        if (job.getVmId() < 0) {
            return false;
        }
        TransferContentionEngine contention = datacenter.getTransferContentionEngine();
        double now = CloudSim.clock();
        List<FileItem> fileList = job.getFileList();
        Set<Long> pending = new LinkedHashSet<Long>();
        long requiredBytes = 0L;
        int fileCount = 0;
        int pathLinkCount = 0;
        double modeledSeconds = 0.0;
        boolean localFileSystem =
                ReplicaCatalog.getFileSystem() == ReplicaCatalog.FileSystem.LOCAL;
        try {
            Set<String> attributedNames = new HashSet<String>();
            // 按父任务产出归组：LOCAL 文件系统下源端点取父任务所在 VM；SHARED 文件系统
            // 下文件经由共享存储，源端点视为 SOURCE（容量无上限）。
            for (Object parentObj : job.getParentList()) {
                Job parent = (Job) parentObj;
                Set<String> produced = new HashSet<String>();
                for (FileItem producedFile : parent.getFileList()) {
                    if (producedFile.getType() == Parameters.FileType.OUTPUT) {
                        produced.add(producedFile.getName());
                    }
                }
                List<FileItem> fromParent = new ArrayList<FileItem>();
                for (FileItem file : fileList) {
                    if (file.isRealInputFile(fileList) && produced.contains(file.getName())) {
                        fromParent.add(file);
                        attributedNames.add(file.getName());
                    }
                }
                if (fromParent.isEmpty()) {
                    continue;
                }
                double seconds = datacenter.estimateTransferSecondsForFiles(fromParent, job);
                long bytes = sumRealInputBytes(fromParent);
                requiredBytes += bytes;
                fileCount += fromParent.size();
                modeledSeconds += seconds;
                if (seconds <= 0.0 || bytes <= 0L) {
                    continue; // 副本已在目标 VM 上，零传输。
                }
                String sourceEndpoint = localFileSystem
                        ? "VM:" + parent.getVmId() : Parameters.SOURCE;
                long transferId = nextContentionTransferId++;
                if (fatTree) {
                    // Fat-tree：资源集 = 两端点 + 确定性路由的全部链路键。
                    List<String> pathLinks = Parameters.SOURCE.equals(sourceEndpoint)
                            ? java.util.Collections.<String>emptyList()
                            : datacenter.fatTreePathResources(parent.getVmId(), job.getVmId(),
                                    job.getUserId());
                    pathLinkCount += pathLinks.size();
                    List<String> resources = new ArrayList<String>();
                    resources.add(sourceEndpoint);
                    resources.add("VM:" + job.getVmId());
                    resources.addAll(pathLinks);
                    contention.addTransfer(transferId, bytes, resources, bytes / seconds, now);
                } else {
                    contention.addTransfer(transferId, bytes, sourceEndpoint,
                            "VM:" + job.getVmId(), bytes / seconds, now);
                }
                contentionTransferJobs.put(transferId, job);
                pending.add(transferId);
            }
            // 外部输入（SOURCE 副本）单独一组。
            List<FileItem> external = new ArrayList<FileItem>();
            for (FileItem file : fileList) {
                if (file.isRealInputFile(fileList) && !attributedNames.contains(file.getName())) {
                    external.add(file);
                }
            }
            if (!external.isEmpty()) {
                double seconds = datacenter.estimateTransferSecondsForFiles(external, job);
                long bytes = sumRealInputBytes(external);
                requiredBytes += bytes;
                fileCount += external.size();
                modeledSeconds += seconds;
                if (seconds > 0.0 && bytes > 0L) {
                    long transferId = nextContentionTransferId++;
                    if (fatTree) {
                        // 诚实边界 v1：外部输入流量不经过 Fat-tree，仅占用目标端点。
                        contention.addTransfer(transferId, bytes,
                                java.util.Collections.singletonList("VM:" + job.getVmId()),
                                bytes / seconds, now);
                    } else {
                        contention.addTransfer(transferId, bytes, Parameters.SOURCE,
                                "VM:" + job.getVmId(), bytes / seconds, now);
                    }
                    contentionTransferJobs.put(transferId, job);
                    pending.add(transferId);
                }
            }
        } catch (Exception e) {
            throw new IllegalStateException("WorkflowEngine could not register contention stage-in "
                    + "for Job " + job.getCloudletId(), e);
        }
        Map<String, Object> stageInAttributes = SimulationEventRecorder.attributes(
                "modeledTransferSeconds", modeledSeconds,
                "requiredFileBytes", (double) requiredBytes,
                "modeledTransferFileCount", fileCount,
                "dataMovementModel", datacenter.getDataMovementModel().getKind().name(),
                "contentionTransferGroupCount", (double) pending.size());
        if (fatTree) {
            stageInAttributes.put("fatTreePathLinkCount", (double) pathLinkCount);
        }
        eventRecorder.record(SimulationEventType.DATA_STAGE_IN_MODELED, now, job,
                stageInAttributes);
        if (pending.isEmpty()) {
            // 全部输入本地（或零传输）：立即登记副本并释放。
            dispatchContentionStageInComplete(datacenter, job);
            return true;
        }
        contentionJobPendingTransfers.put(job.getCloudletId(), pending);
        Double nextCompletion = contention.advance(now).getNextCompletionTime();
        schedule(getId(),
                Math.max(nextCompletion.doubleValue() - now, CloudSim.getMinTimeBetweenEvents()),
                WorkflowSimTags.TRANSFER_CONTENTION_CHECK, null);
        return true;
    }

    /** 累计一组文件的字节量（组内均为真实输入文件）。 */
    private static long sumRealInputBytes(List<FileItem> files) {
        long bytes = 0L;
        for (FileItem file : files) {
            bytes += file.getSize();
        }
        return bytes;
    }

    /**
     * 处理 {@link WorkflowSimTags#TRANSFER_CONTENTION_CHECK}：把流体争用引擎积分推进
     * 到当前时钟，结算完成的传输组；当一个计算 Job 的全部传输组完成时登记输入副本
     * 并把它释放给调度器；仍有活动传输时重排下一次检查。
     *
     * @param ev 无负载的检查事件
     */
    protected void processTransferContentionCheck(SimEvent ev) {
        WorkflowDatacenter datacenter = (WorkflowDatacenter) CloudSim.getEntity(boundDatacenterId);
        TransferContentionEngine contention = datacenter.getTransferContentionEngine();
        double now = CloudSim.clock();
        TransferContentionEngine.AdvanceResult result = contention.advance(now);
        List<Job> readyJobs = new ArrayList<Job>();
        for (Long transferId : result.getCompletedTransferIds()) {
            Job job = contentionTransferJobs.remove(transferId);
            if (job == null) {
                continue;
            }
            Set<Long> pending = contentionJobPendingTransfers.get(job.getCloudletId());
            if (pending == null) {
                continue;
            }
            pending.remove(transferId);
            if (pending.isEmpty()) {
                contentionJobPendingTransfers.remove(job.getCloudletId());
                readyJobs.add(job);
            }
        }
        for (Job job : readyJobs) {
            dispatchContentionStageInComplete(datacenter, job);
        }
        Double nextCompletion = result.getNextCompletionTime();
        if (nextCompletion != null) {
            schedule(getId(),
                    Math.max(nextCompletion.doubleValue() - now, CloudSim.getMinTimeBetweenEvents()),
                    WorkflowSimTags.TRANSFER_CONTENTION_CHECK, null);
        }
    }

    /** 争用传输全部完成后登记输入副本并把计算 Job 释放给所属调度器。 */
    private void dispatchContentionStageInComplete(WorkflowDatacenter datacenter, Job job) {
        try {
            datacenter.registerStageInReplicasForComputeJob(job);
        } catch (Exception e) {
            throw new IllegalStateException("WorkflowEngine could not register contention stage-in "
                    + "replicas for Job " + job.getCloudletId(), e);
        }
        List<Job> batch = new ArrayList<Job>();
        batch.add(job);
        double delay = 0.0;
        if (Parameters.getOverheadParams().getWEDDelay() != null) {
            delay = Parameters.getOverheadParams().getWEDDelay(batch);
        }
        schedule(job.getUserId(), delay, CloudSimTags.CLOUDLET_SUBMIT, batch);
    }

    /**
     * 处理调度器返回的 Job，并推进失败恢复或工作流终止状态。
     *
     * <p>失败 Job 交给 {@link ReclusteringEngine} 产生一个或多个重试 Job；每个新 Job 都会
     * 记录 {@link SimulationEventType#RETRY_JOB_CREATED}。无论状态成功或失败，当前尝试都会
     * 进入已返回列表，活动提交计数随之递减。仅当待提交队列为空且活动计数归零时，才向所有
     * 调度器发送仿真结束事件；否则重新触发一轮就绪扫描。
     *
     * @param ev 负载为返回 Job 的事件
     * @pre ev != $null
     * @post $none
     */
    protected void processJobReturn(SimEvent ev) {

        Job job = (Job) ev.getData();
        if (job.getCloudletStatus() == Cloudlet.FAILED) {
            // 输入或历史 Job ID 可以不连续，重试 ID 仍须在三类队列中保持唯一。
            int newId = nextAvailableJobId(job);
            List<Job> retries = ReclusteringEngine.process(job, newId);
            if (retries.isEmpty()) {
                throw new IllegalStateException("Failed job " + job.getCloudletId()
                        + " produced no retry jobs");
            }
            enforceRetryBudget(job, retries.size());
            getJobsList().addAll(retries);
            retryJobsCreated += retries.size();
            for (Job retry : retries) {
                // PLAT-13：登记 retry Job 与失败原 Job 的关联，供 JOB_READY 事件补充属性。
                retryOfFailedJobIds.put(retry.getCloudletId(), job.getCloudletId());
                eventRecorder.record(SimulationEventType.RETRY_JOB_CREATED, CloudSim.clock(), retry,
                        SimulationEventRecorder.attributes("failedJobId", job.getCloudletId()));
            }
        }

        getJobsReceivedList().add(job);
        receivedJobIds.add(job.getCloudletId());
        jobsSubmitted--;
        if (getJobsList().isEmpty() && jobsSubmitted == 0) {
            // 没有待释放或在途 Job 后，通知所有调度器结束本次仿真。
            for (int i = 0; i < getSchedulerIds().size(); i++) {
                sendNow(getSchedulerId(i), CloudSimTags.END_OF_SIMULATION, null);
            }
        } else {
            sendNow(this.getId(), CloudSimTags.CLOUDLET_SUBMIT, null);
        }
    }

    /**
     * 为重试 Job 分配严格大于当前三个工作流队列中最大 ID 的标识。
     *
     * @param currentJob 刚刚返回且可能失败的 Job
     * @return 可安全使用的下一个 Job ID
     * @throws IllegalStateException 当最大 ID 已为 {@link Integer#MAX_VALUE} 时抛出
     */
    private int nextAvailableJobId(Job currentJob) {
        int maximum = currentJob.getCloudletId();
        maximum = Math.max(maximum, maximumJobId(getJobsList()));
        maximum = Math.max(maximum, maximumJobId(getJobsSubmittedList()));
        maximum = Math.max(maximum, maximumJobId(getJobsReceivedList()));
        if (maximum == Integer.MAX_VALUE) {
            throw new IllegalStateException("Cannot allocate a retry Job ID after Integer.MAX_VALUE");
        }
        return maximum + 1;
    }

    /** 在提交任何 retry 前检查现代故障配置声明的总 retry Job 上限。 */
    private void enforceRetryBudget(Job failedJob, int requestedRetryJobs) {
        int budget = FailureParameters.getMaxTotalRetryJobs();
        if (budget < 0) {
            return;
        }
        if (requestedRetryJobs <= 0 || retryJobsCreated > budget - requestedRetryJobs) {
            throw new RetryLimitExceededException(failedJob.getCloudletId(), retryJobsCreated,
                    requestedRetryJobs, budget, FailureParameters.getRootSeed());
        }
    }

    /**
     * 返回列表中的最大 Job ID，并拒绝混入非 Job 的 Cloudlet，以免重试编号与工作流语义脱节。
     */
    private static int maximumJobId(List<? extends Cloudlet> jobs) {
        int maximum = -1;
        for (Cloudlet cloudlet : jobs) {
            if (!(cloudlet instanceof Job)) {
                throw new IllegalStateException("Workflow Engine contains a non-Job cloudlet");
            }
            maximum = Math.max(maximum, cloudlet.getCloudletId());
        }
        return maximum;
    }

    /**
     * 处理本引擎不认识的事件标签。
     *
     * <p>子类可重写以扩展事件协议；默认实现只写入日志，不改变工作流队列或仿真状态。
     *
     * @param ev 未被 {@link #processEvent(SimEvent)} 分支识别的事件
     */
    protected void processOtherEvent(SimEvent ev) {
        if (ev == null) {
            Log.printLine(getName() + ".processOtherEvent(): " + "Error - an event is null.");
            return;
        }
        Log.printLine(getName() + ".processOtherEvent(): "
                + "Error - event unknown by this DatacenterBroker.");
    }

    /**
     * 从待处理队列中取出父依赖已返回的 Job，并批量交给所属调度器。
     *
     * <p>一个 Job 是否可释放由其所有父 Job ID 是否已出现在 {@code jobsReceivedList} 决定；
     * 失败重试时的依赖重连由 {@link ReclusteringEngine} 生成的 Job 图负责。本方法以
     * {@link Job#getUserId()} 作为调度器<strong>实体 ID</strong>查找投递队列，因此调用方
     * 必须将该字段设置为 {@link #getSchedulerIds()} 中的值，而不是调度器索引。
     *
     * <p>提交前递增活动计数并记录 {@link SimulationEventType#JOB_READY}。若配置了工作流
     * 引擎延迟，投递队列会按间隔分块并按块累加延迟；未配置时整批立即发送。
     *
     * @pre $none
     * @post $none
     */
    protected void submitJobs() {

        List<Job> list = getJobsList();
        Map<Integer, List> allocationList = new HashMap<>();
        for (int i = 0; i < getSchedulers().size(); i++) {
            List<Job> submittedList = new ArrayList<>();
            allocationList.put(getSchedulerId(i), submittedList);
        }
        int num = list.size();
        for (int i = 0; i < num; i++) {
            // 删除当前元素后回退下标，避免跳过紧随其后的候选 Job。
            Job job = list.get(i);
            // Cloudlet 的 finished 标志不能代表本引擎的依赖释放状态。
            // PLAT-18：依赖门控用 O(1) 的 receivedJobIds 镜像索引替代线性扫描。
            if (!receivedJobIds.contains(job.getCloudletId())) {
                List<Job> parentList = job.getParentList();
                boolean flag = true;
                for (Job parent : parentList) {
                    if (!receivedJobIds.contains(parent.getCloudletId())) {
                        flag = false;
                        break;
                    }
                }
                /** 当前已返回列表中已出现该 Job 的全部父 Job ID，可以进入投递队列。 */
                if (flag) {
                    // R5 动态到达：提交时刻未到的 Job 留在就绪列表；到达重扫事件会在
                    // 最早未到达的提交时刻再次触发幂等就绪扫描。
                    double arrivalSecond = arrivalSecondFor(job);
                    if (arrivalSecond > CloudSim.clock()) {
                        scheduleArrivalRescan(arrivalSecond);
                        continue;
                    }
                    announceWorkflowArrivalFor(job);
                    // PLAT-13：retry Job 的 JOB_READY 补充与失败原 Job 的关联属性，
                    // 使下游事件消费者能直接识别重试尝试（attempt 语义）。
                    Integer retryOfFailedJobId = retryOfFailedJobIds.get(job.getCloudletId());
                    eventRecorder.record(SimulationEventType.JOB_READY, CloudSim.clock(), job,
                            retryOfFailedJobId != null
                                    ? SimulationEventRecorder.attributes("parentJobCount", parentList.size(),
                                            "retryOfFailedJobId", retryOfFailedJobId)
                                    : SimulationEventRecorder.attributes("parentJobCount", parentList.size()));
                    jobsSubmitted++;
                    getJobsSubmittedList().add(job);
                    list.remove(job);
                    i--;
                    num--;
                    // COMM-1 修复：执行前传输延迟模型（PRE_EXECUTION_TRANSFER_DELAY_V1，
                    // 论文语义）。数据就绪时即开始传输计算 Job 的输入，传输窗口可与目标
                    // VM 的忙碌期重叠；传输完成后才把 Job 释放进派发队列，VM 只被计算
                    // MI 占用。负值表示模型不适用（常规释放路径，行为与历史一致）。
                    // R2：链路争用模型优先——传输组登记进争用引擎后由争用检查事件
                    // 在全部传输组完成时释放；返回 true 表示争用路径已接管本 Job。
                    if (!startContentionStageIn(job)) {
                        double stageInHoldSeconds = preExecutionStageInHoldSeconds(job);
                        if (stageInHoldSeconds < 0.0 || stageInHoldSeconds == 0.0) {
                            allocationList.get(job.getUserId()).add(job);
                        } else {
                            schedule(getId(), stageInHoldSeconds,
                                    WorkflowSimTags.JOB_STAGE_IN_COMPLETE, job);
                        }
                    }
                }
            }

        }
        /** 每个调度器分别按照其工作流引擎延迟配置接收一个投递批次。 */
        for (int i = 0; i < getSchedulers().size(); i++) {

            List submittedList = allocationList.get(getSchedulerId(i));
            // 延迟模型可将一个调度器的待投递列表切分为多个事件批次。

            int interval = Parameters.getOverheadParams().getWEDInterval();
            double delay = 0.0;
            if(Parameters.getOverheadParams().getWEDDelay()!=null){
                delay = Parameters.getOverheadParams().getWEDDelay(submittedList);
            }

            double delaybase = delay;
            int size = submittedList.size();
            if (interval > 0 && interval <= size) {
                int index = 0;
                List subList = new ArrayList();
                while (index < size) {
                    subList.add(submittedList.get(index));
                    index++;
                    if (index % interval == 0) {
                        // 当前子批次已作为事件负载发出，后续元素必须写入新的列表实例。
                        schedule(getSchedulerId(i), delay, CloudSimTags.CLOUDLET_SUBMIT, subList);
                        delay += delaybase;
                        subList = new ArrayList();
                    }
                }
                if (!subList.isEmpty()) {
                    schedule(getSchedulerId(i), delay, CloudSimTags.CLOUDLET_SUBMIT, subList);
                }
            } else if (!submittedList.isEmpty()) {
                sendNow(this.getSchedulerId(i), CloudSimTags.CLOUDLET_SUBMIT, submittedList);
            }
        }
    }

    /**
     * CloudSim 关闭回调。引擎不再产生新事件，只保留日志输出以与历史行为兼容。
     */
    @Override
    public void shutdownEntity() {
        Log.printLine(getName() + " is shutting down...");
    }

    /**
     * CloudSim 启动回调：记录启动日志，并向自身排入资源特征请求以启动调度器生命周期。
     */
    @Override
    public void startEntity() {
        Log.printLine(getName() + " is starting...");
        schedule(getId(), 0, CloudSimTags.RESOURCE_CHARACTERISTICS_REQUEST);
    }

    /**
     * 返回待依赖门控的可变 Job 列表。
     *
     * @param <T> Cloudlet 的具体类型
     * @return 引擎内部的实时列表，而非防御性副本
     */
    @SuppressWarnings("unchecked")
    public <T extends Cloudlet> List<T> getJobsList() {
        return (List<T>) jobsList;
    }

    /**
     * 返回当前已提交给调度器但尚未返回的 Job 数（PLAT-14 停滞看门狗用）。
     *
     * <p>仿真正常终止时该值应为 0；若仿真结束后仍有在途 Job 或待释放 Job，
     * 说明事件链断裂导致工作流静默停滞，调用方应据此显式失败而非产出残缺报告。</p>
     *
     * @return 在途（已提交未返回）Job 数
     */
    public int getInFlightJobCount() {
        return jobsSubmitted;
    }

    /**
     * 替换内部待处理列表；仅供引擎事件路径使用。
     *
     * @param <T> Cloudlet 的具体类型
     * @param jobsList 新的 Job 列表
     */
    private <T extends Cloudlet> void setJobsList(List<T> jobsList) {
        this.jobsList = jobsList;
    }

    /**
     * 返回已向调度器投递过的可变历史列表。
     *
     * @param <T> Cloudlet 的具体类型
     * @return 引擎内部列表，而非防御性副本
     */
    @SuppressWarnings("unchecked")
    public <T extends Cloudlet> List<T> getJobsSubmittedList() {
        return (List<T>) jobsSubmittedList;
    }

    /**
     * 设置已投递 Job 的历史列表；仅供构造和内部状态恢复使用。
     *
     * @param <T> Cloudlet 的具体类型
     * @param jobsSubmittedList 新的已投递列表
     */
    private <T extends Cloudlet> void setJobsSubmittedList(List<T> jobsSubmittedList) {
        this.jobsSubmittedList = jobsSubmittedList;
    }

    /**
     * 返回已收到返回事件的可变列表，其中包括失败尝试。
     *
     * @param <T> Cloudlet 的具体类型
     * @return 引擎内部列表，而非防御性副本
     */
    @SuppressWarnings("unchecked")
    public <T extends Cloudlet> List<T> getJobsReceivedList() {
        return (List<T>) jobsReceivedList;
    }

    /**
     * 设置已返回 Job 列表；仅供构造和内部状态恢复使用。
     *
     * @param <T> Cloudlet 的具体类型
     * @param jobsReceivedList 新的已返回列表
     */
    private <T extends Cloudlet> void setJobsReceivedList(List<T> jobsReceivedList) {
        this.jobsReceivedList = jobsReceivedList;
        // PLAT-18：镜像索引必须与列表保持同步；列表被整体替换时重建索引。
        receivedJobIds.clear();
        if (jobsReceivedList != null) {
            for (Cloudlet item : jobsReceivedList) {
                receivedJobIds.add(item.getCloudletId());
            }
        }
    }

    /**
     * 返回默认调度器入口保存的 VM 列表。
     *
     * <p>多调度器调用方若未使用 {@link #submitVmList(List)}，该值可能为 {@code null}；
     * 需要聚合视图时应使用 {@link #getAllVmList()}。
     *
     * @param <T> VM 的具体类型
     * @return 默认 VM 列表，可能为 {@code null}
     */
    @SuppressWarnings("unchecked")
    public <T extends Vm> List<T> getVmList() {
        return (List<T>) vmList;
    }

    /**
     * 保存默认调度器的 VM 列表；仅供单调度器兼容入口使用。
     *
     * @param <T> VM 的具体类型
     * @param vmList 新的 VM 列表
     */
    private <T extends Vm> void setVmList(List<T> vmList) {
        this.vmList = vmList;
    }

    /**
     * 返回按引擎索引排序的运行时调度器可变列表。
     *
     * <p>外部修改会破坏 {@link #getSchedulerIds()} 与本列表的位置对应关系，正常运行期间不应修改。
     *
     * @return 运行时调度器列表
     */
    public List<WorkflowScheduler> getSchedulers() {
        return this.scheduler;
    }

    /**
     * 设置运行时调度器列表；仅供构造过程使用，须与调度器 ID 列表保持位置一致。
     *
     * @param list 新的调度器列表
     */
    private void setSchedulers(List list) {
        this.scheduler = list;
    }

    /**
     * 返回与调度器列表位置一一对应的 CloudSim 实体 ID 列表。
     *
     * @return 调度器实体 ID 列表
     */
    public List<Integer> getSchedulerIds() {
        return this.schedulerId;
    }

    /**
     * 设置调度器实体 ID 列表；仅供构造过程使用。
     *
     * @param list 新的调度器实体 ID 列表
     */
    private void setSchedulerIds(List list) {
        this.schedulerId = list;
    }

    /**
     * 按调度器索引返回对应的 CloudSim 实体 ID。
     *
     * @param index 调度器索引；无范围检查，越界时由列表访问抛出异常
     * @return 对应调度器实体 ID；仅在内部列表为 {@code null} 时保留历史默认值 0
     */
    public int getSchedulerId(int index) {
        if (this.schedulerId != null) {
            return this.schedulerId.get(index);
        }
        return 0;
    }

    /**
     * 按调度器索引返回运行时调度器。
     *
     * <p>参数名称沿用历史 API，但它是列表索引而不是 CloudSim 实体 ID；实体 ID 请通过
     * {@link #getSchedulerId(int)} 取得。
     *
     * @param schedulerId 调度器索引
     * @return 对应调度器；仅在内部列表为 {@code null} 时返回 {@code null}
     */
    public WorkflowScheduler getScheduler(int schedulerId) {
        if (this.scheduler != null) {
            return this.scheduler.get(schedulerId);
        }
        return null;
    }
}
