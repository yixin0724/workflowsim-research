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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.cloudbus.cloudsim.Cloudlet;
import org.cloudbus.cloudsim.DatacenterBroker;
import org.cloudbus.cloudsim.Log;
import org.cloudbus.cloudsim.core.CloudSim;
import org.cloudbus.cloudsim.core.CloudSimTags;
import org.cloudbus.cloudsim.core.SimEvent;
import org.cloudbus.cloudsim.lists.VmList;
import org.workflowsim.experiment.SimulationEventRecorder;
import org.workflowsim.experiment.SimulationEventType;
import org.workflowsim.scheduling.DataAwareSchedulingAlgorithm;
import org.workflowsim.scheduling.BaseSchedulingAlgorithm;
import org.workflowsim.scheduling.FCFSSchedulingAlgorithm;
import org.workflowsim.scheduling.ReadyBatchMaxMinSchedulingAlgorithm;
import org.workflowsim.scheduling.ReadyBatchMCTSchedulingAlgorithm;
import org.workflowsim.scheduling.ReadyBatchMinMinSchedulingAlgorithm;
import org.workflowsim.scheduling.ReadyBatchRoundRobinSchedulingAlgorithm;
import org.workflowsim.scheduling.RlPolicySchedulingAlgorithm;
import org.workflowsim.scheduling.FastestVmSchedulingAlgorithm;
import org.workflowsim.scheduling.FirstFitIdleSchedulingAlgorithm;
import org.workflowsim.scheduling.LjfFastestIdleSchedulingAlgorithm;
import org.workflowsim.scheduling.SptFastestIdleSchedulingAlgorithm;
import org.workflowsim.scheduling.StaticSchedulingAlgorithm;
import org.workflowsim.scheduling.StaticSchedulePlan;
import org.workflowsim.utils.Parameters;
import org.workflowsim.utils.Parameters.SchedulingAlgorithm;

/**
 * 代表一个工作流用户执行运行时调度的 CloudSim Broker。
 *
 * <p>该实体管理 VM 创建与销毁、就绪作业派发和完成事件回收，并按配置创建一个
 * {@link BaseSchedulingAlgorithm}。算法对象在同一调度器生命周期内复用，以保持如
 * ready-batch round robin 等有状态策略的跨事件语义。</p>
 *
 * @author Weiwei Chen
 * @since WorkflowSim Toolkit 1.0
 * @date Apr 9, 2013
 */
public class WorkflowScheduler extends DatacenterBroker {

    /** 上游工作流引擎的 CloudSim 实体编号。 */
    private int workflowEngineId;

    /** 在当前调度实体生命周期内保留的策略实例。 */
    private BaseSchedulingAlgorithm activeSchedulingPolicy;

    /** 创建当前策略实例时使用的配置枚举值。 */
    private SchedulingAlgorithm activeSchedulingPolicyType;
    private SimulationEventRecorder eventRecorder = SimulationEventRecorder.disabled();
    private StaticSchedulePlan staticSchedulePlan = StaticSchedulePlan.empty();
    /**
     * VM 创建成功时冻结的 VM 到 Host 映射。
     *
     * <p>CloudSim 关闭阶段会释放 VM 的 Host 引用，故这是记录执行证据的唯一可靠
     * 生命周期时点。</p>
     */
    private final Map<Integer, Integer> createdVmHostAssignments = new LinkedHashMap<Integer, Integer>();

    /**
     * 创建工作流运行时调度实体。
     *
     * @param name CloudSim 实体名称
     * @throws Exception 当 CloudSim Broker 无法创建时
     */
    public WorkflowScheduler(String name) throws Exception {
        super(name);
    }

    /**
     * 绑定一个可供该调度器请求 VM 的数据中心。
     *
     * @param datacenterId 数据中心实体编号
     */
    public void bindSchedulerDatacenter(int datacenterId) {
        if (datacenterId <= 0) {
            Log.printLine("Error in data center id");
            return;
        }
        this.datacenterIdsList.add(datacenterId);
    }

    /**
     * 设置上游工作流引擎编号。
     *
     * @param workflowEngineId 工作流引擎实体编号
     */
    public void setWorkflowEngineId(int workflowEngineId) {
        this.workflowEngineId = workflowEngineId;
    }

    /**
     * 在仿真启动前安装本次运行私有的事件记录器。
     *
     * @param value 本次运行的事件记录器
     */
    public void setEventRecorder(SimulationEventRecorder value) {
        if (value == null) {
            throw new IllegalArgumentException("Event recorder cannot be null");
        }
        this.eventRecorder = value;
    }

    /**
     * 返回 VM 创建成功时观测到的 VM 到 Host 映射。
     *
     * <p>CloudSim 在关闭阶段会清除 VM Host 引用，因此该副本保留了唯一可用于执行证据的
     * 生命周期观测。</p>
     *
     * @return 不可修改的 VM 编号到 Host 编号映射
     */
    public Map<Integer, Integer> getCreatedVmHostAssignments() {
        return Collections.unmodifiableMap(
                new LinkedHashMap<Integer, Integer>(createdVmHostAssignments));
    }

    /**
     * 安装本次运行聚类后的完整静态计划顺序。
     *
     * @param value 完整静态计划
     */
    public void setStaticSchedulePlan(StaticSchedulePlan value) {
        if (value == null) {
            throw new IllegalArgumentException("Static schedule plan cannot be null");
        }
        this.staticSchedulePlan = value;
        if (activeSchedulingPolicy instanceof StaticSchedulingAlgorithm) {
            ((StaticSchedulingAlgorithm) activeSchedulingPolicy).setStaticSchedulePlan(value);
        }
    }

    /**
     * 分发运行时调度生命周期事件。
     *
     * @param ev 接收的 CloudSim 事件
     */
    @Override
    public void processEvent(SimEvent ev) {
        switch (ev.getTag()) {
            // 请求各数据中心资源特征。
            case CloudSimTags.RESOURCE_CHARACTERISTICS_REQUEST:
                processResourceCharacteristicsRequest(ev);
                break;
            // 接收数据中心资源特征。
            case CloudSimTags.RESOURCE_CHARACTERISTICS:
                processResourceCharacteristics(ev);
                break;
            // 接收 VM 创建确认。
            case CloudSimTags.VM_CREATE_ACK:
                processVmCreate(ev);
                break;
            // 接收作业完成或由完成检查触发的返回事件。
            case WorkflowSimTags.CLOUDLET_CHECK:
                processCloudletReturn(ev);
                break;
            case CloudSimTags.CLOUDLET_RETURN:
                processCloudletReturn(ev);
                break;
            case CloudSimTags.END_OF_SIMULATION:
                shutdownEntity();
                break;
            case CloudSimTags.CLOUDLET_SUBMIT:
                processCloudletSubmit(ev);
                break;
            case WorkflowSimTags.CLOUDLET_UPDATE:
                processCloudletUpdate(ev);
                break;
            default:
                processOtherEvent(ev);
                break;
        }
    }

    /**
     * 根据配置创建运行时调度策略。
     *
     * <p>映射需与 {@link Parameters.SchedulingAlgorithm} 的公开实验语义一致。四个
     * 已弃用枚举仍绑定其历史策略，只用于兼容既有调用；研究运行应选择明确维护的
     * ready-batch 或静态策略。</p>
     *
     * @param name 调度算法枚举
     * @return 对应的调度策略
     */
    private BaseSchedulingAlgorithm createSchedulingPolicy(SchedulingAlgorithm name) {
        BaseSchedulingAlgorithm algorithm;

        // 新增策略时必须同步维护 Parameters 中的可报告算法枚举。
        switch (name) {
            // FCFS 是默认在线调度策略。
            case FCFS:
                algorithm = new FCFSSchedulingAlgorithm();
                break;
            case READY_BATCH_MINMIN:
                algorithm = new ReadyBatchMinMinSchedulingAlgorithm();
                break;
            case READY_BATCH_MAXMIN:
                algorithm = new ReadyBatchMaxMinSchedulingAlgorithm();
                break;
            case READY_BATCH_MCT:
                algorithm = new ReadyBatchMCTSchedulingAlgorithm();
                break;
            case READY_BATCH_ROUNDROBIN:
                algorithm = new ReadyBatchRoundRobinSchedulingAlgorithm();
                break;
            case RL_POLICY:
                // R4 RL 轨道：策略经 RlPolicyRegistry 送达；episode 外运行显式失败。
                algorithm = new RlPolicySchedulingAlgorithm();
                break;
            case MINMIN:
                // 保留旧行为:SPT-fastest-idle 变体
                algorithm = new SptFastestIdleSchedulingAlgorithm();
                break;
            case MAXMIN:
                // 保留旧行为:LJF-fastest-idle 变体
                algorithm = new LjfFastestIdleSchedulingAlgorithm();
                break;
            case MCT:
                // 保留旧行为:贪心最快 VM
                algorithm = new FastestVmSchedulingAlgorithm();
                break;
            case ROUNDROBIN:
                // 保留旧行为:First-Fit-Idle
                algorithm = new FirstFitIdleSchedulingAlgorithm();
                break;
            case DATA:
                algorithm = new DataAwareSchedulingAlgorithm();
                break;
            case STATIC:
                algorithm = new StaticSchedulingAlgorithm();
                break;
            default:
                algorithm = new StaticSchedulingAlgorithm();
                break;

        }
        return algorithm;
    }

    /**
     * 返回本次仿真保留的调度策略。
     *
     * <p>ready-batch round robin 等有状态策略必须跨多个 Cloudlet 更新事件存活；若配置
     * 算法值发生变化，则刻意创建新的策略实例。</p>
     *
     * @return 当前活动调度策略
     */
    protected BaseSchedulingAlgorithm getSchedulingPolicy() {
        SchedulingAlgorithm configuredType = Parameters.getSchedulingAlgorithm();
        if (activeSchedulingPolicy == null || activeSchedulingPolicyType != configuredType) {
            activeSchedulingPolicy = createSchedulingPolicy(configuredType);
            activeSchedulingPolicyType = configuredType;
        }
        return activeSchedulingPolicy;
    }

    /**
     * 处理 VM 创建请求的确认事件。
     *
     * <p>若所有候选数据中心均无法创建所请求的全部 VM，则显式失败，而不在资源退化的
     * 平台上继续运行并产生难以比较的实验结果。</p>
     *
     * @param ev VM 创建确认事件
     */
    @Override
    protected void processVmCreate(SimEvent ev) {
        int[] data = (int[]) ev.getData();
        int datacenterId = data[0];
        int vmId = data[1];
        int result = data[2];

        if (result == CloudSimTags.TRUE) {
            getVmsToDatacentersMap().put(vmId, datacenterId);
            // 历史 CloudSim 路径可能返回未知 VM 编号，不能将 null 加入已创建列表。
            if (VmList.getById(getVmList(), vmId) != null) {
                getVmsCreatedList().add(VmList.getById(getVmList(), vmId));
                CondorVM createdVm = (CondorVM) VmList.getById(getVmsCreatedList(), vmId);
                if (createdVm.getHost() == null) {
                    throw new IllegalStateException(getName() + " received successful creation for VM #"
                            + vmId + " without a Host assignment");
                }
                if (createdVmHostAssignments.put(createdVm.getId(), createdVm.getHost().getId()) != null) {
                    throw new IllegalStateException(getName() + " received duplicate successful creation for VM #"
                            + vmId);
                }
                Log.printLine(CloudSim.clock() + ": " + getName() + ": VM #" + vmId
                        + " has been created in Datacenter #" + datacenterId + ", Host #"
                        + createdVm.getHost().getId());
            }
        } else {
            Log.printLine(CloudSim.clock() + ": " + getName() + ": Creation of VM #" + vmId
                    + " failed in Datacenter #" + datacenterId);
        }

        incrementVmsAcks();

        // 所有请求 VM 已创建后，才允许提交待调度作业。
        if (getVmsCreatedList().size() == getVmList().size() - getVmsDestroyed()) {
            submitCloudlets();
            // 冲刷早到的就绪作业：若引擎在 VM 创建完成前就释放了作业（例如 JOB_SUBMIT
            // 幂等释放路径），这些作业已在就绪列表中等待；此处补发一次调度周期，
            // 避免它们在无新提交事件驱动时静默停滞。标准顺序（作业后于 VM 到达）下
            // 就绪列表为空，本补发是空转周期，不产生派发。
            if (!getCloudletList().isEmpty()) {
                sendNow(this.getId(), WorkflowSimTags.CLOUDLET_UPDATE);
            }
        } else {
            // 所有确认已到达但仍有 VM 创建失败时，尝试下一个尚未请求的数据中心。
            if (getVmsRequested() == getVmsAcks()) {
                // 查找尚未尝试创建 VM 的下一个数据中心。
                for (int nextDatacenterId : getDatacenterIdsList()) {
                    if (!getDatacenterRequestedIdsList().contains(nextDatacenterId)) {
                        createVmsInDatacenter(nextDatacenterId);
                        return;
                    }
                }

                // 所有数据中心均已尝试，拒绝在退化资源规模下继续运行。
                throw new IllegalStateException(getName() + " could create only "
                        + getVmsCreatedList().size() + " of " + getVmList().size()
                        + " requested VMs; refusing to run on a degraded platform");
            }
        }
    }

    /**
     * 对当前就绪作业和已创建 VM 执行一次调度决策周期。
     *
     * <p>该方法记录决策耗时、就绪/空闲数量和每个作业的派发决定。计时反映本机 Java
     * 调度开销，不是模拟时间，也不能与不同硬件直接比较。</p>
     *
     * @param ev Cloudlet 更新事件
     */
    protected void processCloudletUpdate(SimEvent ev) {

        // VM 尚未全部创建完成时不能执行调度周期：调度算法在空（或不完整）VM 列表上
        // 无法做出有效决策。此时就绪作业保留在就绪列表中，待 VM 创建完成后由
        // processVmCreate 触发的调度周期统一冲刷（见该方法的 CLOUDLET_UPDATE 补发逻辑）。
        // 该守卫使作业到达顺序不再依赖 VM 创建完成时刻（例如引擎在 JOB_SUBMIT 时
        // 幂等释放就绪作业的场景）。
        if (getVmsCreatedList().size() < getVmList().size() - getVmsDestroyed()) {
            return;
        }
        BaseSchedulingAlgorithm scheduler = getSchedulingPolicy();
        if (scheduler instanceof StaticSchedulingAlgorithm) {
            ((StaticSchedulingAlgorithm) scheduler).setStaticSchedulePlan(staticSchedulePlan);
        }
        scheduler.getScheduledList().clear();
        scheduler.setCloudletList(getCloudletList());
        scheduler.setVmList(getVmsCreatedList());
        int readyJobs = getCloudletList().size();
        int idleVms = 0;
        for (Object item : getVmsCreatedList()) {
            if (((CondorVM) item).getState() == WorkflowSimTags.VM_STATUS_IDLE) {
                idleVms++;
            }
        }
        long decisionStart = System.nanoTime();
        try {
            scheduler.run();
        } catch (Exception exception) {
            throw new IllegalStateException("Scheduling algorithm "
                    + Parameters.getSchedulingAlgorithm() + " failed", exception);
        }
        long decisionElapsed = System.nanoTime() - decisionStart;

        List<Cloudlet> scheduledList = scheduler.getScheduledList();
        eventRecorder.record(SimulationEventType.SCHEDULING_CYCLE, CloudSim.clock(), null,
                SimulationEventRecorder.attributes("schedulingAlgorithm",
                        Parameters.getSchedulingAlgorithm().name(), "readyJobCount", readyJobs,
                        "idleVmCount", idleVms, "scheduledJobCount", scheduledList.size(),
                        "decisionElapsedNanos", decisionElapsed));
        for (Cloudlet cloudlet : scheduledList) {
            Job job = (Job) cloudlet;
            int vmId = cloudlet.getVmId();
            double delay = 0.0;
            if (Parameters.getOverheadParams().getQueueDelay() != null) {
                delay = Parameters.getOverheadParams().getQueueDelay(cloudlet);
            }
            eventRecorder.record(SimulationEventType.SCHEDULING_DECISION, CloudSim.clock(), job,
                    SimulationEventRecorder.attributes("schedulingAlgorithm",
                            Parameters.getSchedulingAlgorithm().name(), "queueDelaySeconds", delay));
            schedule(getVmsToDatacentersMap().get(vmId), delay, CloudSimTags.CLOUDLET_SUBMIT, cloudlet);
            eventRecorder.record(SimulationEventType.JOB_DISPATCHED, CloudSim.clock(), job,
                    SimulationEventRecorder.attributes("queueDelaySeconds", delay));
        }
        getCloudletList().removeAll(scheduledList);
        getCloudletSubmittedList().addAll(scheduledList);
        cloudletsSubmitted += scheduledList.size();
    }

    /**
     * 处理一个作业返回事件。
     *
     * <p>数据中心已在提交输出副本之前判定 compute Job 的故障结果。本路径只负责释放 VM、
     * 记录事件，再向工作流引擎回传作业。后处理延迟按当前作业深度从开销模型抽样。</p>
     *
     * @param ev 作业返回事件
     */
    @Override
    protected void processCloudletReturn(SimEvent ev) {
        Cloudlet cloudlet = (Cloudlet) ev.getData();
        Job job = (Job) cloudlet;

        getCloudletReceivedList().add(cloudlet);
        getCloudletSubmittedList().remove(cloudlet);

        CondorVM vm = findCreatedVm(cloudlet.getVmId());
        // 作业已返回，虚拟机重新可接收后续作业。
        vm.setState(WorkflowSimTags.VM_STATUS_IDLE);

        double delay = 0.0;
        if (Parameters.getOverheadParams().getPostDelay() != null) {
            delay = Parameters.getOverheadParams().getPostDelay(job);
        }
        eventRecorder.record(SimulationEventType.JOB_RETURNED, CloudSim.clock(), job,
                SimulationEventRecorder.attributes("postDelaySeconds", delay,
                        "jobStatus", job.getCloudletStatus(), "taskStatuses", taskStatuses(job)));
        if (job.getCloudletStatus() == Cloudlet.FAILED) {
            eventRecorder.record(SimulationEventType.JOB_FAILED, CloudSim.clock(), job,
                    SimulationEventRecorder.attributes("failedStatus", true));
        }
        schedule(this.workflowEngineId, delay, CloudSimTags.CLOUDLET_RETURN, cloudlet);

        cloudletsSubmitted--;
        // PLAT-12：仅在有就绪作业等待时才触发后续调度周期。历史实现对每个作业返回
        // 无条件补发 CLOUDLET_UPDATE，同刻多个返回会产生多个 readyJobCount=0 的
        // 空转周期，扭曲周期/开销类指标。就绪作业后续到达时由 processCloudletSubmit
        // 自行触发调度周期，跳过空转不会造成作业停滞。
        if (!getCloudletList().isEmpty()) {
            schedule(this.getId(), 0.0, WorkflowSimTags.CLOUDLET_UPDATE);
        }

    }

    /** CloudSim VM 编号可由外部配置，不能假设它等于列表下标。 */
    private CondorVM findCreatedVm(int vmId) {
        for (Object item : getVmsCreatedList()) {
            CondorVM vm = (CondorVM) item;
            if (vm.getId() == vmId) {
                return vm;
            }
        }
        throw new IllegalStateException("Returned cloudlet references unknown created VM " + vmId);
    }

    private static List<Integer> taskStatuses(Job job) {
        List<Integer> statuses = new ArrayList<Integer>();
        for (Task task : job.getTaskList()) {
            statuses.add(task.getCloudletStatus());
        }
        return statuses;
    }

    /** 启动调度实体并注册到 CloudSim 信息服务。 */
    @Override
    public void startEntity() {
        Log.printLine(getName() + " is starting...");
        // 未配置区域 GIS 时，使用系统默认的 CloudInformationService。
        // int gisID = CloudSim.getEntityId(regionalCisName);
        int gisID = -1;
        if (gisID == -1) {
            gisID = CloudSim.getCloudInfoServiceEntityId();
        }

        // 向 CloudSim 信息服务注册本调度实体。
        sendNow(gisID, CloudSimTags.REGISTER_RESOURCE, getId());
    }

    /** 关闭调度实体并释放数据中心资源。 */
    @Override
    public void shutdownEntity() {
        clearDatacenters();
        Log.printLine(getName() + " is shutting down...");
    }

    /** VM 创建完成后通知工作流引擎开始提交作业。 */
    @Override
    protected void submitCloudlets() {
        sendNow(this.workflowEngineId, CloudSimTags.CLOUDLET_SUBMIT, null);
    }

    /**
     * 接收一批来自工作流引擎的就绪作业并触发调度周期。
     *
     * <p>引擎可能向未分配到作业的调度器发送空批次；空批次与 {@code null} 负载
     * 不触发调度周期，避免产生空转周期事件（PLAT-12）。历史死代码标记
     * {@code processCloudletSubmitHasShown} 已移除（PLAT-20：置位后无任何效果）。</p>
     *
     * @param ev 包含作业列表的提交事件
     */
    protected void processCloudletSubmit(SimEvent ev) {
        Object data = ev.getData();
        if (data == null) {
            return;
        }
        List<Job> list = (List) data;
        if (list.isEmpty()) {
            return;
        }
        getCloudletList().addAll(list);

        sendNow(this.getId(), WorkflowSimTags.CLOUDLET_UPDATE);
    }

    /**
     * 请求已绑定数据中心的资源特征。
     *
     * @param ev 资源特征请求事件
     */
    @Override
    protected void processResourceCharacteristicsRequest(SimEvent ev) {
        setDatacenterCharacteristicsList(new HashMap<>());
        Log.printLine(CloudSim.clock() + ": " + getName() + ": Cloud Resource List received with "
                + getDatacenterIdsList().size() + " resource(s)");
        for (Integer datacenterId : getDatacenterIdsList()) {
            sendNow(datacenterId, CloudSimTags.RESOURCE_CHARACTERISTICS, getId());
        }
    }
}
