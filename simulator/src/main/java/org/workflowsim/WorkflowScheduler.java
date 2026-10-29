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
import org.workflowsim.exception.SimulationConfigurationException;
import org.workflowsim.experiment.SimulationEventRecorder;
import org.workflowsim.experiment.SimulationEventType;
import org.workflowsim.platform.PlatformProfile;
import org.workflowsim.scheduling.BaseSchedulingAlgorithm;
import org.workflowsim.scheduling.DataAwareSchedulingAlgorithm;
import org.workflowsim.scheduling.FCFSSchedulingAlgorithm;
import org.workflowsim.scheduling.FastestVmSchedulingAlgorithm;
import org.workflowsim.scheduling.FirstFitIdleSchedulingAlgorithm;
import org.workflowsim.scheduling.LjfFastestIdleSchedulingAlgorithm;
import org.workflowsim.scheduling.ReadyBatchMCTSchedulingAlgorithm;
import org.workflowsim.scheduling.ReadyBatchMaxMinSchedulingAlgorithm;
import org.workflowsim.scheduling.ReadyBatchMinMinSchedulingAlgorithm;
import org.workflowsim.scheduling.ReadyBatchRoundRobinSchedulingAlgorithm;
import org.workflowsim.scheduling.RlPolicySchedulingAlgorithm;
import org.workflowsim.scheduling.SptFastestIdleSchedulingAlgorithm;
import org.workflowsim.scheduling.StaticSchedulePlan;
import org.workflowsim.scheduling.StaticSchedulingAlgorithm;
import org.workflowsim.utils.Parameters;
import org.workflowsim.utils.Parameters.SchedulingAlgorithm;
import org.workflowsim.utils.SimulationConfig;

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
    private boolean strictDataflowBindings;
    /** Require an intact input-prepared compute binding, without changing stage-in fallback. */
    public void enableDataflowBindingGuard() {
        strictDataflowBindings = true;
    }
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
        return Collections.unmodifiableMap(new LinkedHashMap<Integer, Integer>(createdVmHostAssignments));
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
        // BAT-3（2026-10-26）：注册算轨优先于枚举分派；提供者策略跨事件存活，
        // Parameters.reset() 负责其会话边界失效。既有枚举路径零改动。
        if (Parameters.getRegisteredSchedulingAlgorithmProvider() != null) {
            if (activeSchedulingPolicy == null || activeSchedulingPolicyType != SchedulingAlgorithm.INVALID) {
                activeSchedulingPolicy =
                        Parameters.getRegisteredSchedulingAlgorithmProvider().create();
                activeSchedulingPolicyType = SchedulingAlgorithm.INVALID;
            }
            return activeSchedulingPolicy;
        }
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
                    throw new IllegalStateException(
                            getName() + " received successful creation for VM #" + vmId + " without a Host assignment");
                }
                if (createdVmHostAssignments.put(
                                createdVm.getId(), createdVm.getHost().getId())
                        != null) {
                    throw new IllegalStateException(
                            getName() + " received duplicate successful creation for VM #" + vmId);
                }
                Log.printLine(CloudSim.clock() + ": " + getName() + ": VM #" + vmId
                        + " has been created in Datacenter #" + datacenterId + ", Host #"
                        + createdVm.getHost().getId());
            }
        } else {
            // 创建失败 ACK 是合法流程的一部分：legacy 多集群示例把同一批 VM 提交给
            // 多个数据中心试放，失败方回 ACK FALSE（WorkflowSimMultipleClusterExample1）。
            // 标准 SimulationRunner 路径下平台布局已预校验（PLAT-2/PLAT-5），VM 必可
            // 放置，此处不会触发；若触发则条件计数永不满足，看门狗报停滞（R8 审计
            // P1-6 评估后保留日志行为，fail-fast 会误伤多集群试放语义）。
            Log.printLine(CloudSim.clock() + ": " + getName() + ": Creation of VM #" + vmId + " failed in Datacenter #"
                    + datacenterId);
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
            if (strictDataflowBindings) {
                ((StaticSchedulingAlgorithm) scheduler).requireDataflowBindings();
            }
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
        } catch (SimulationConfigurationException exception) {
            // R8 审计修复（P1-7）：配置类异常（如 RL_POLICY 未注册策略）不得
            // 被包装成"调度算法失败"的执行语义。
            throw exception;
        } catch (Exception exception) {
            throw new IllegalStateException(
                    "Scheduling algorithm " + Parameters.getSchedulingAlgorithm() + " failed", exception);
        }
        long decisionElapsed = System.nanoTime() - decisionStart;

        List<Cloudlet> scheduledList = scheduler.getScheduledList();
        recordSchedulingCycle(readyJobs, idleVms, decisionElapsed, scheduledList);
        dispatchScheduledJobs(scheduledList);
        getCloudletList().removeAll(scheduledList);
        getCloudletSubmittedList().addAll(scheduledList);
        cloudletsSubmitted += scheduledList.size();
    }

    /** processCloudletUpdate 的调度周期事件阶段（事件键序原样）。 */
    private void recordSchedulingCycle(int readyJobs, int idleVms, long decisionElapsed, List<Cloudlet> scheduledList) {
        eventRecorder.record(
                SimulationEventType.SCHEDULING_CYCLE,
                CloudSim.clock(),
                null,
                SimulationEventRecorder.attributes(
                        "schedulingAlgorithm",
                        Parameters.getSchedulingAlgorithm().name(),
                        "readyJobCount",
                        readyJobs,
                        "idleVmCount",
                        idleVms,
                        "scheduledJobCount",
                        scheduledList.size(),
                        "decisionElapsedNanos",
                        decisionElapsed));
    }

    /** processCloudletUpdate 的派发阶段（队列延迟→事件记录→CloudSim 事件次序逐字搬运转写）。 */
    private void dispatchScheduledJobs(List<Cloudlet> scheduledList) {
        for (Cloudlet cloudlet : scheduledList) {
            Job job = (Job) cloudlet;
            int vmId = cloudlet.getVmId();
            double delay = 0.0;
            if (Parameters.getOverheadParams().getQueueDelay() != null) {
                delay = Parameters.getOverheadParams().getQueueDelay(cloudlet);
            }
            eventRecorder.record(
                    SimulationEventType.SCHEDULING_DECISION,
                    CloudSim.clock(),
                    job,
                    SimulationEventRecorder.attributes(
                            "schedulingAlgorithm",
                            Parameters.getSchedulingAlgorithm().name(),
                            "queueDelaySeconds",
                            delay));
            // BAT-D2（2026-10-29）：调度算法已敲定 VM 指派 → 本中心化派发点统一做
            // 频档决策（所有 online/STATIC/注册算轨路径的 setVmId 都汇入本方法）。
            // 置于事件记录之后：events v1 schema 不携带频档字段（F-006 领域裁决），
            // 频档证据链走 JobOutcome.tierMips。
            assignFrequencyTier(job);
            schedule(getVmsToDatacentersMap().get(vmId), delay, CloudSimTags.CLOUDLET_SUBMIT, cloudlet);
            eventRecorder.record(
                    SimulationEventType.JOB_DISPATCHED,
                    CloudSim.clock(),
                    job,
                    SimulationEventRecorder.attributes("queueDelaySeconds", delay));
        }
    }

    /**
     * BAT-D2（2026-10-29）：为刚获得 VM 指派的计算 Job 决定唯一频档并缩放作业长度。
     *
     * <p>语义契约（docs/advanced/DVFS_DIMENSION_DESIGN.md §1.4/§2.2）：assignment at
     * admission——每个计算 Job 在派发到 VM 的时刻获得恰好一个频档，全 Job 时长固定
     * 不做中途变频，保持 one-rectangle-per-Job 的可审计结构。仿真级语义的"低档变慢"
     * 由缩放作业 MI 表达：执行时长 = work/档速，在 CloudSim 按声明 MIPS 执行的机器上
     * 等价于把长度乘 {@code vm.mips / tier.mips}（数据中心提交前的
     * {@code projectComputeExecutionLength} 以 effectiveExecutionLengthMi 为真源重构
     * Job 长度，故此处同时写入 effective 值与 Cloudlet 长度）。stage-in 注入的
     * 传输 MI 发生在其后，按 VM 声明速执行（网络传输不随 CPU 频档伸缩）。</p>
     *
     * <p>非计算 Job（stage-in 平台准备）与无频档 VM（v1 平台或混合平台的无档 VM）
     * 不获得频档：tierMips 保持 null，长度不变——v1 运行逐位不变。</p>
     *
     * @param job 刚被调度算法指派 VM 的计算 Job
     */
    private void assignFrequencyTier(Job job) {
        if (job.getClassType() != Parameters.ClassType.COMPUTE.value) {
            // 非计算 Job（stage-in/stage-out/cleanup 平台簿记）不占用计算频档。
            return;
        }
        CondorVM vm = findCreatedVm(job.getVmId());
        if (!vm.hasFrequencyTiers()) {
            return;
        }
        List<PlatformProfile.FrequencyTier> tiers = vm.getFrequencyTiers();
        PlatformProfile.FrequencyTier selected = selectFrequencyTier(job, tiers);
        long scaledWork = scaledTierWorkMi(job, vm, selected);
        // 证据链：Job 在报告捕获时把该值冻结进 JobOutcome.tierMips。
        job.setTierMips(selected.getMips());
        job.setEffectiveExecutionLengthMi(scaledWork);
        job.setCloudletLength(scaledWork);
    }

    /** BAT-D2：按会话频档策略选择 VM 频档表中的唯一档（表序=降序、首档=默认档）。 */
    private PlatformProfile.FrequencyTier selectFrequencyTier(Job job, List<PlatformProfile.FrequencyTier> tiers) {
        SimulationConfig.FrequencyPolicy policy = Parameters.getFrequencyPolicy();
        switch (policy) {
            case MIN_POWER:
                // 节电上限：每个 Job 都在最低频档（表末档）执行。
                return tiers.get(tiers.size() - 1);
            case EDP_HEURISTIC:
                return selectMinEdpTier(job, tiers);
            case DEFAULT:
                // 缺省（含未配置）：首档=平台声明的默认档（绝不静默"挑最快"——
                // 默认档身份来自表序声明，而非隐式 max()）。
                return tiers.get(0);
            default:
                throw new IllegalStateException("Unknown frequency policy " + policy);
        }
    }

    /**
     * BAT-D2：v1 诚实贪心 EDP——派发时刻逐档估值，取 EDP 最小的档。
     *
     * <p>每个候选档 t 估计（只用派发可观测状态，无千里眼）：
     * {@code T_t = work/档速}；
     * makespan 估计取贪心关键路径下界 {@code clock + T_t}（本 Job 至少把日程推到自身
     * 完成，任何更小的估计都在否认本 Job 自己的运行区间）。按任务契约：
     * {@code EDP_t = (P_max(t)·T_t + P_idle(default)·makespan_est) × T_t}。全局最优 EDP
     * 是 NP-hard 跨 Job 问题（设计 §3 诚实弃项），v2 目标是每 Job 独立贪心；并列时
     * 严格 {@code <} 保表序首位（确定性 tie-break，AGENTS #9）。</p>
     *
     * @param job 待派发的计算 Job（提供声明的每 PE 计算 MI）
     * @param tiers 候选频档表（降序）
     * @return EDP 估计最小的档
     */
    private PlatformProfile.FrequencyTier selectMinEdpTier(Job job, List<PlatformProfile.FrequencyTier> tiers) {
        long workMi = job.getEffectiveExecutionLengthMi();
        double clock = CloudSim.clock();
        PlatformProfile.FrequencyTier defaultTier = tiers.get(0);
        PlatformProfile.FrequencyTier best = null;
        double bestEdp = Double.NaN;
        for (PlatformProfile.FrequencyTier tier : tiers) {
            double estimatedSeconds = workMi / tier.getMips();
            double makespanEstimate = clock + estimatedSeconds;
            double energyEstimate =
                    tier.getMaxPowerWatts() * estimatedSeconds + defaultTier.getIdlePowerWatts() * makespanEstimate;
            double edp = energyEstimate * estimatedSeconds;
            if (best == null || edp < bestEdp) {
                best = tier;
                bestEdp = edp;
            }
        }
        return best;
    }

    /**
     * BAT-D2：把声明的每 PE 计算 MI 按档速折算为 CloudSim 长度。
     *
     * <p>{@code scaled = round(work × vm.mips / tier.mips)}：CloudSim 以声明 MIPS 执行
     * 该长度所得时长恰为 {@code work / tier.mips}（work/档速）。工作不变——Job 的工作
     * 语义量（MI）只随档速换算计时基准，声明任务长度（TaskOutcome.lengthMi）不变。
     * 溢出/塌缩到不可表示值显式失败，不静默截断。</p>
     */
    private long scaledTierWorkMi(Job job, CondorVM vm, PlatformProfile.FrequencyTier tier) {
        long declaredWorkMi = job.getEffectiveExecutionLengthMi();
        double factor = vm.getMips() / tier.getMips();
        double scaled = declaredWorkMi * factor;
        if (!Double.isFinite(scaled) || scaled >= (double) Long.MAX_VALUE) {
            throw new IllegalStateException("Frequency tier " + tier.getMips() + " scaling overflows Job "
                    + job.getCloudletId() + " work of " + declaredWorkMi + " MI");
        }
        return org.workflowsim.utils.TaskExecutionModel.requireRepresentableLength(
                Math.round(scaled), job.getNumberOfPes());
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
        eventRecorder.record(
                SimulationEventType.JOB_RETURNED,
                CloudSim.clock(),
                job,
                SimulationEventRecorder.attributes(
                        "postDelaySeconds",
                        delay,
                        "jobStatus",
                        job.getCloudletStatus(),
                        "taskStatuses",
                        taskStatuses(job)));
        if (job.getCloudletStatus() == Cloudlet.FAILED) {
            eventRecorder.record(
                    SimulationEventType.JOB_FAILED,
                    CloudSim.clock(),
                    job,
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
