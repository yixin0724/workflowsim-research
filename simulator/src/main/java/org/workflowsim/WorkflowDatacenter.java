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

import java.util.Iterator;
import java.util.List;
import org.cloudbus.cloudsim.Cloudlet;
import org.cloudbus.cloudsim.CloudletScheduler;
import org.cloudbus.cloudsim.Consts;
import org.cloudbus.cloudsim.Datacenter;
import org.cloudbus.cloudsim.DatacenterCharacteristics;
import org.cloudbus.cloudsim.Host;
import org.cloudbus.cloudsim.Log;
import org.cloudbus.cloudsim.Storage;
import org.cloudbus.cloudsim.Vm;
import org.cloudbus.cloudsim.VmAllocationPolicy;
import org.cloudbus.cloudsim.core.CloudSim;
import org.cloudbus.cloudsim.core.CloudSimTags;
import org.cloudbus.cloudsim.core.SimEvent;
import org.workflowsim.experiment.SimulationEventRecorder;
import org.workflowsim.experiment.SimulationEventType;
import org.workflowsim.data.DataMovementModel;
import org.workflowsim.data.TransferContentionEngine;
import org.workflowsim.failure.FailureGenerator;
import org.workflowsim.utils.ReplicaCatalog;
import org.workflowsim.utils.Parameters;
import org.workflowsim.utils.Parameters.ClassType;
import org.workflowsim.utils.Parameters.FileType;
import org.workflowsim.utils.SimulationConstants;
import org.workflowsim.utils.SimulationTiming;

/**
 * 工作流专用数据中心，在 CloudSim {@link Datacenter} 的基础上处理 {@link Job}、
 * {@link CondorVM}、输入文件副本和任务级时间证据。
 *
 * <p>提交生命周期为：校验事件负载和目标 VM；为 Job 设置资源计费参数；阶段输入 Job 登记
 * 文件副本，计算 Job 按 {@link DataMovementModel} 估算输入传输时间；随后将 Job 交给 VM 的
 * CloudletScheduler，并安排下一次数据中心处理事件。完成检测会先判定 compute Job 的故障结果，
 * 再仅为成功的逻辑 Task 登记输出副本，最后将完成 Cloudlet 返回给其用户。
 *
 * <p>该类仅建模传输耗时，不实现共享链路竞争、物理存储容量分配或重试策略。负载不是
 * {@link Job}、目标 VM 不存在/不是 {@link CondorVM}、或副本与带宽约束无效时会快速失败并抛出
 * {@link IllegalStateException}；失败 Job 的恢复由工作流引擎及重聚类流程负责。
 *
 * @author Weiwei Chen
 * @since WorkflowSim Toolkit 1.0
 * @date Apr 9, 2013
 */
public class WorkflowDatacenter extends Datacenter {

    /** 本次运行的事件证据接收器，默认禁用。 */
    private SimulationEventRecorder eventRecorder = SimulationEventRecorder.disabled();
    /** 本次运行的输入数据移动模型，默认保留 WorkflowSim v1 历史语义。 */
    private DataMovementModel dataMovementModel = DataMovementModel.legacyWorkflowsimV1();
    /** 链路争用模型下的流体传输争用引擎（惰性创建，见 {@link #getTransferContentionEngine()}）。 */
    private TransferContentionEngine transferContentionEngine;
    /** Fat-tree 链路争用模型使用的已放置拓扑（标准运行器安装，可为 null）。 */
    private org.workflowsim.network.FatTreeTopology fatTreeTopology;

    /**
     * 创建一个工作流数据中心。
     *
     * <p>{@code storageList} 同时传给 CloudSim 父类，并在共享存储输入传输模型中提供最大传输率。
     * 使用共享存储模型时，列表中至少应存在一个具有有限正传输率的存储设备；该条件在真正估算
     * 传输时间时校验，而不是在构造时改变历史初始化行为。
     *
     * @param name 数据中心实体名称
     * @param characteristics 数据中心资源与成本特征
     * @param vmAllocationPolicy VM 到 Host 的分配策略
     * @param storageList 可用存储设备列表
     * @param schedulingInterval CloudSim 数据中心调度间隔
     * @throws Exception 当 CloudSim 数据中心初始化失败时抛出
     */
    public WorkflowDatacenter(String name,
            DatacenterCharacteristics characteristics,
            VmAllocationPolicy vmAllocationPolicy,
            List<Storage> storageList,
            double schedulingInterval) throws Exception {
        super(name, characteristics, vmAllocationPolicy, storageList, schedulingInterval);
    }

    /**
     * 安装本数据中心在一次仿真运行中使用的事件记录器。
     *
     * @param value 非空的运行私有记录器
     * @throws IllegalArgumentException 当记录器为 {@code null} 时抛出
     */
    public void setEventRecorder(SimulationEventRecorder value) {
        if (value == null) {
            throw new IllegalArgumentException("Event recorder cannot be null");
        }
        this.eventRecorder = value;
    }

    /**
     * 安装本次运行的数据移动时间模型。
     *
     * <p>该模型只决定计算 Job 的输入阶段传输耗时和证据记录，不改变副本目录的逻辑登记规则。
     *
     * @param value 非空的运行私有数据移动模型
     * @throws IllegalArgumentException 当模型为 {@code null} 时抛出
     */
    public void setDataMovementModel(DataMovementModel value) {
        if (value == null) {
            throw new IllegalArgumentException("Data movement model cannot be null");
        }
        this.dataMovementModel = value;
    }

    /** 返回本次运行的数据移动时间模型。 */
    public DataMovementModel getDataMovementModel() {
        return dataMovementModel;
    }

    /**
     * 安装 Fat-tree 链路争用模型使用的已放置拓扑。
     *
     * @param value 非空的已放置拓扑（主机覆盖平台全部 Host）
     * @throws IllegalArgumentException 当拓扑为 {@code null} 时抛出
     */
    public void setFatTreeTopology(org.workflowsim.network.FatTreeTopology value) {
        if (value == null) {
            throw new IllegalArgumentException("Fat-tree topology cannot be null");
        }
        this.fatTreeTopology = value;
    }

    /** 返回已安装的 Fat-tree 拓扑；未声明拓扑的模型返回 {@code null}。 */
    public org.workflowsim.network.FatTreeTopology getFatTreeTopology() {
        return fatTreeTopology;
    }

    /**
     * 返回（惰性创建）链路争用模型使用的流体传输争用引擎；其他数据移动模型返回
     * {@code null}。
     *
     * <p>创建时把全部 VM 的网卡带宽注册为端点容量（键 {@code "VM:<vmId>"}，容量
     * {@code vm.getBw() × 10⁶} 字节/秒）；SOURCE 端点不注册容量（无上限）。
     * Fat-tree 模型额外注册拓扑全部链路容量（双工分方向资源键，容量 = 统一
     * 链路带宽）。引擎状态由工作流引擎在数据就绪与推进检查事件中驱动。</p>
     *
     * @return 争用引擎实例；数据移动模型不是链路争用模型时为 {@code null}
     */
    public TransferContentionEngine getTransferContentionEngine() {
        if (!dataMovementModel.isPreExecutionTransferDelayWithContentionV1()
                && !dataMovementModel.isFatTreeContentionV1()) {
            return null;
        }
        if (transferContentionEngine == null) {
            transferContentionEngine = new TransferContentionEngine();
            for (Host host : getVmAllocationPolicy().getHostList()) {
                for (Vm vm : host.getVmList()) {
                    transferContentionEngine.setEndpointCapacity("VM:" + vm.getId(),
                            vm.getBw() * (double) Consts.MILLION);
                }
            }
            if (dataMovementModel.isFatTreeContentionV1()) {
                if (fatTreeTopology == null) {
                    throw new IllegalStateException("Fat-tree contention model requires an installed "
                            + "topology (WorkflowDatacenter.setFatTreeTopology)");
                }
                fatTreeTopology.registerCapacities(transferContentionEngine);
            }
        }
        return transferContentionEngine;
    }

    /**
     * 返回 Fat-tree 拓扑下源 VM 到目标 VM 传输占用的链路资源键序列；非 Fat-tree
     * 模型返回空列表。同主机 VM 间传输不占用拓扑链路。
     *
     * @param sourceVmId 源 VM 标识
     * @param destinationVmId 目标 VM 标识
     * @param userId 用户标识
     * @return 确定性路由的链路资源键列表
     */
    public java.util.List<String> fatTreePathResources(int sourceVmId, int destinationVmId,
            int userId) {
        if (!dataMovementModel.isFatTreeContentionV1() || fatTreeTopology == null) {
            return java.util.Collections.emptyList();
        }
        Host sourceHost = getVmAllocationPolicy().getHost(sourceVmId, userId);
        Host destinationHost = getVmAllocationPolicy().getHost(destinationVmId, userId);
        if (sourceHost == null || destinationHost == null) {
            throw new IllegalStateException("Fat-tree contention requires placed source and "
                    + "destination VMs (" + sourceVmId + " -> " + destinationVmId + ")");
        }
        return fatTreeTopology.route(sourceHost.getId(), destinationHost.getId());
    }

    /**
     * 纯估算给定真实输入文件集合在计算 Job 目标 VM 上的传输秒数（无副作用）。
     *
     * <p>带宽规则与历史 WorkflowSim v1 一致：共享文件系统取最大存储传输率；本地文件系统
     * 逐文件取最快可访问副本（{@code SOURCE} 站点用目标 VM 带宽，VM 站点用
     * {@code min(bw_src, bw_dst)}），文件已在目标 VM 上则为零。本方法不登记副本，供执行前
     * 传输延迟模型在数据就绪时估算、以及规划器镜像运行时估算使用。</p>
     *
     * @param files 候选文件（只统计其中的真实输入文件）
     * @param job 目标计算 Job
     * @return 传输秒数
     * @throws Exception 当所需副本或 VM 无法解析时抛出
     */
    public double estimateTransferSecondsForFiles(List<FileItem> files, Job job) throws Exception {
        double time = 0.0;
        for (FileItem file : files) {
            if (!file.isRealInputFile(files)) {
                continue;
            }
            List siteList = ReplicaCatalog.getStorageList(file.getName());
            if (siteList == null || siteList.isEmpty()) {
                throw new IllegalStateException("Required input file '" + file.getName()
                        + "' has no registered replica");
            }
            switch (ReplicaCatalog.getFileSystem()) {
                case SHARED:
                    double maxRate = 0.0;
                    for (Storage storage : getStorageList()) {
                        double rate = storage.getMaxTransferRate();
                        if (rate > maxRate) {
                            maxRate = rate;
                        }
                    }
                    if (!(maxRate > 0.0) || Double.isInfinite(maxRate)) {
                        throw new IllegalStateException("Shared storage has no positive transfer rate for "
                                + "required input file '" + file.getName() + "'");
                    }
                    time += file.getSize() / (double) Consts.MILLION / maxRate;
                    break;
                case LOCAL:
                    time += localStageInSecondsForFile(file, siteList, job);
                    break;
                default:
                    throw new IllegalStateException("Unsupported replica file system "
                            + ReplicaCatalog.getFileSystem());
            }
        }
        return time;
    }

    /**
     * 在目标 VM 上登记一个计算 Job 全部真实输入文件的副本。
     *
     * <p>执行前传输延迟模型在传输完成时才登记副本，因此传输窗口内的其他 Job 不会把这些
     * 文件误判为本地已有。共享文件系统没有 VM 级副本登记，调用为空操作。</p>
     *
     * @param job 已完成输入传输的计算 Job
     * @throws Exception 当目标 VM 无法解析时抛出
     */
    public void registerStageInReplicasForComputeJob(Job job) throws Exception {
        if (ReplicaCatalog.getFileSystem() != ReplicaCatalog.FileSystem.LOCAL) {
            return;
        }
        boolean hasRealInput = false;
        for (FileItem file : job.getFileList()) {
            if (file.isRealInputFile(job.getFileList())) {
                hasRealInput = true;
                break;
            }
        }
        if (!hasRealInput) {
            return;
        }
        int vmId = job.getVmId();
        int userId = job.getUserId();
        requiredVm(vmId, userId, "pre-execution stage-in replica registration");
        for (FileItem file : job.getFileList()) {
            if (file.isRealInputFile(job.getFileList())) {
                ReplicaCatalog.addFileToStorage(file.getName(), Integer.toString(vmId));
            }
        }
    }

    /**
     * 估算历史 WorkflowSim v1 本地规则下单个文件到目标 VM 的传输秒数。
     *
     * @param file 输入文件
     * @param siteList 已登记副本位置
     * @param job 目标计算 Job
     * @return 传输秒数；目标 VM 已有副本时为 0
     * @throws Exception 当目标或来源 VM 无法解析时抛出
     */
    private double localStageInSecondsForFile(FileItem file, List siteList, Job job) throws Exception {
        int vmId = job.getVmId();
        int userId = job.getUserId();
        Host host = getVmAllocationPolicy().getHost(vmId, userId);
        if (host == null) {
            throw new IllegalStateException("No host contains VM " + vmId + " for local stage-in");
        }
        Vm vm = host.getVm(vmId, userId);
        if (vm == null) {
            throw new IllegalStateException("No VM " + vmId + " is available for local stage-in");
        }
        boolean requiredFileStagein = true;
        double maxBwth = 0.0;
        for (Iterator it = siteList.iterator(); it.hasNext();) {
            // site 表示该文件的一个已登记副本位置。
            String site = (String) it.next();
            if (site.equals(this.getName())) {
                continue;
            }
            /** 文件已在目标 VM 上，无需执行阶段输入。 */
            if (site.equals(Integer.toString(vmId))) {
                requiredFileStagein = false;
                break;
            }
            double bwth;
            if (site.equals(Parameters.SOURCE)) {
                // 历史规则中，源端到 VM 的传输只受目标 VM 带宽限制。
                bwth = vm.getBw();
            } else {
                // 历史规则中，两台 VM 间的传输受双方带宽较小值限制。
                int sourceVmId;
                try {
                    sourceVmId = Integer.parseInt(site);
                } catch (NumberFormatException e) {
                    throw new IllegalStateException("Replica site '" + site
                            + "' is neither the source nor a VM identifier", e);
                }
                Host sourceHost = getVmAllocationPolicy().getHost(sourceVmId, userId);
                Vm sourceVm = sourceHost == null ? null : sourceHost.getVm(sourceVmId, userId);
                if (sourceVm == null) {
                    throw new IllegalStateException("Replica site VM " + sourceVmId
                            + " is unavailable for input file '" + file.getName() + "'");
                }
                bwth = Math.min(vm.getBw(), sourceVm.getBw());
            }
            if (bwth > maxBwth) {
                maxBwth = bwth;
            }
        }
        if (requiredFileStagein && maxBwth > 0.0) {
            return file.getSize() / (double) Consts.MILLION / maxBwth;
        }
        return 0.0;
    }

    /**
     * 处理一个工作流 Job 的提交事件。
     *
     * <p>事件负载必须是 {@link Job}，且 Job 指向的 VM 必须存在并为 {@link CondorVM}。已完成
     * 的 Job 不会再次执行，但仍会返回给用户；当请求确认时还会返回失败确认。阶段输入 Job 只
     * 登记文件位置，计算 Job 则先估算输入数据移动并记录证据，之后才调用 CloudletScheduler。
     *
     * <p>成功提交后，若可推导出有限的完成时间，就向自身安排
     * {@link org.cloudbus.cloudsim.core.CloudSimTags#VM_DATACENTER_EVENT}；请求确认时发送成功
     * 确认。任何校验、数据移动或 VM 访问错误都会被包装为 {@link IllegalStateException}，不会
     * 伪造成功确认或 Cloudlet 返回事件。
     *
     * @param ev 负载为 Job 的 CloudSim 事件
     * @param ack 是否需要向 Job 所属用户发送提交确认
     * @pre ev != null
     * @post $none
     */
    @Override
    protected void processCloudletSubmit(SimEvent ev, boolean ack) {
        updateCloudletProcessing();

        try {
            Object payload = ev.getData();
            if (!(payload instanceof Job)) {
                throw new IllegalArgumentException("Cloudlet submission must contain a WorkflowSim Job");
            }
            Job job = (Job) payload;

            if (job.isFinished()) {
                String name = CloudSim.getEntityName(job.getUserId());
                Log.printLine(getName() + ": Warning - Cloudlet #" + job.getCloudletId() + " owned by " + name
                        + " is already completed/finished.");
                Log.printLine("Therefore, it is not being executed again");
                Log.printLine();

                // 已完成的 Cloudlet 不会再次执行；仍需返回确认（若请求）和返回事件，
                // 否则等待该 Cloudlet 的上游实体会一直阻塞。
                if (ack) {
                    int[] data = new int[3];
                    data[0] = getId();
                    data[1] = job.getCloudletId();
                    data[2] = CloudSimTags.FALSE;

                    // 确认事件使用与提交操作对应的唯一 CloudSim 标签。
                    int tag = CloudSimTags.CLOUDLET_SUBMIT_ACK;
                    sendNow(job.getUserId(), tag, data);
                }

                sendNow(job.getUserId(), CloudSimTags.CLOUDLET_RETURN, job);

                return;
            }

            int userId = job.getUserId();
            int vmId = job.getVmId();
            Host host = getVmAllocationPolicy().getHost(vmId, userId);
            if (host == null) {
                throw new IllegalStateException("No host contains VM " + vmId + " for submitted Job "
                        + job.getCloudletId());
            }
            Vm candidateVm = host.getVm(vmId, userId);
            if (!(candidateVm instanceof CondorVM)) {
                throw new IllegalStateException("Submitted Job " + job.getCloudletId()
                        + " is assigned to an unavailable or non-Condor VM " + vmId);
            }
            CondorVM vm = (CondorVM) candidateVm;

            /**
             * 任务×VM 异构成本矩阵投影：STATIC 派发在此把逻辑任务的精确执行秒数折算为
             * 作业长度（MI = round(秒数 × vm.mips)），使运行时执行时间与论文成本表一致
             * （受 CloudSim 整数 MI 舍入影响）。重试路径同样经过本方法，折算自动生效。
             * 未携带投影的任务保持原始 MI/mips 缩放行为。
             */
            if (job.getClassType() == ClassType.COMPUTE.value && job.getTaskList().size() == 1) {
                Task costTask = job.getTaskList().get(0);
                Double costSeconds = costTask.getVmExecutionCostSeconds(vmId);
                if (costSeconds != null) {
                    long convertedMi = Math.round(costSeconds.doubleValue() * vm.getMips());
                    if (convertedMi <= 0L) {
                        throw new IllegalStateException("Task cost matrix entry for Job "
                                + job.getCloudletId() + " on VM " + vmId
                                + " converts to non-positive MI (" + convertedMi
                                + "); cost seconds and VM MIPS must yield at least 1 MI");
                    }
                    job.setCloudletLength(convertedMi);
                }
            }

            switch (Parameters.getCostModel()) {
                case DATACENTER:
                    // 按配置的成本模型写入本次数据中心或 VM 的计费参数。
                    job.setResourceParameter(getId(), getCharacteristics().getCostPerSecond(),
                            getCharacteristics().getCostPerBw());
                    break;
                case VM:
                    job.setResourceParameter(getId(), vm.getCost(), vm.getCostPerBW());
                    break;
                default:
                    break;
            }

            /** 阶段输入 Job 只登记文件所在位置，位置语义由副本文件系统模式决定。 */
            if (job.getClassType() == ClassType.STAGE_IN.value) {
                stageInFile2FileSystem(job);
            }

            /** 计算 Job 的输入数据移动时间作为 CloudletScheduler 的传输耗时参数。 */
            double fileTransferTime = 0.0;
            if (job.getClassType() == ClassType.COMPUTE.value) {
                if (dataMovementModel.isPreExecutionTransferDelayV1()
                        || dataMovementModel.isPreExecutionTransferDelayWithContentionV1()
                        || dataMovementModel.isFatTreeContentionV1()) {
                    // 论文语义路径（PRE_EXECUTION_TRANSFER_DELAY_V1）、链路争用路径
                    // （R2 PRE_EXECUTION_TRANSFER_DELAY_WITH_CONTENTION_V1）与 Fat-tree
                    // 拓扑争用路径：输入传输已由引擎在数据就绪时作为执行前延迟建模
                    // （争用模型下并发传输公平共享 VM 端点带宽，Fat-tree 下再叠加
                    // 确定性路由路径上的共享链路），传输窗口可与目标 VM 的忙碌期
                    // 重叠，此处不再把传输折算进执行信封——VM 只被计算 MI 占用，
                    // 计算从提交时刻开始。DATA_STAGE_IN_MODELED 证据与输入副本登记
                    // 由引擎在传输窗口处理。
                    fileTransferTime = 0.0;
                } else {
                    DataTransferEstimate estimate = estimateDataStageInForComputeJob(job.getFileList(), job);
                    fileTransferTime = estimate.getTransferSeconds();
                    eventRecorder.record(SimulationEventType.DATA_STAGE_IN_MODELED, CloudSim.clock(), job,
                            SimulationEventRecorder.attributes("modeledTransferSeconds", fileTransferTime,
                                    "requiredFileBytes", estimate.getRequiredFileBytes(),
                                    "modeledTransferFileCount", estimate.getRequiredFileCount(),
                                    "dataMovementModel", dataMovementModel.getKind().name()));
                }
            }

            CloudletScheduler scheduler = vm.getCloudletScheduler();
            long cloudletLengthBeforeStageIn = job.getCloudletLength();
            double estimatedFinishTime = scheduler.cloudletSubmit(job, fileTransferTime);
            // Fail-fast：忙碌 VM 提交路径。CloudSim 对忙碌 VM 返回 0.0 并保持作业
            // QUEUED（execStartTime 未设置，保持默认 0.0），若继续执行，
            // updateTaskExecTime 会把任务时间窗锚定在 ≈0.0 —— 早于该作业的
            // JOB_READY 时刻，构成时间倒挂，并导致 FailureGenerator 基于错误
            // 时间窗做出错误的故障归因（静默数据污染）。
            // WorkflowSim 的标准调度契约是所有算法只向空闲 VM 派发作业，因此
            // 提交到忙碌 VM 属于契约违规，必须显式失败而非静默降级。
            if (!(estimatedFinishTime > 0.0) || Double.isInfinite(estimatedFinishTime)) {
                throw new IllegalStateException(getName()
                        + " received a Job submitted to a busy VM (cloudlet "
                        + job.getCloudletId() + ", VM " + vm.getId()
                        + "): WorkflowSim scheduling contract only dispatches Jobs"
                        + " to idle VMs; a busy-VM submission would anchor the task"
                        + " time window at 0.0 and corrupt failure attribution");
            }
            double effectiveStageInSeconds = effectiveCloudletStageInSeconds(job, vm,
                    cloudletLengthBeforeStageIn, fileTransferTime);
            updateTaskExecTime(job, vm, effectiveStageInSeconds, fileTransferTime);

            double completionTime = SimulationTiming.earliestCloudletCompletionTime(
                    CloudSim.clock(), CloudSim.clock() + estimatedFinishTime,
                    CloudSim.getMinTimeBetweenEvents());
            send(getId(), completionTime - CloudSim.clock(),
                    CloudSimTags.VM_DATACENTER_EVENT);

            if (ack) {
                int[] data = new int[3];
                data[0] = getId();
                data[1] = job.getCloudletId();
                data[2] = CloudSimTags.TRUE;

                int tag = CloudSimTags.CLOUDLET_SUBMIT_ACK;
                sendNow(job.getUserId(), tag, data);
            }
        } catch (Exception e) {
            throw new IllegalStateException(getName() + " could not submit a workflow Job", e);
        }
        checkCloudletCompletion();
    }

    /**
     * 将 CloudSim 实际追加到 Job 的 MI 长度换算为生效的阶段输入耗时。
     *
     * <p>CloudSim 以整数 MI 表示 {@code fileTransferTime}，因此生效耗时为
     * {@code (提交后 Job 长度 - 提交前 Job 长度) / vmMips}，可能与请求的数据移动秒数存在
     * 舍入差异。请求值仍单独写入证据记录，不能用本方法的结果替代数据模型观测。
     *
     * @param job 已传入 CloudletScheduler 的 Job
     * @param vm 执行该 Job 的 VM
     * @param cloudletLengthBeforeStageIn 提交前的 Job 长度（MI）
     * @param requestedStageInSeconds 数据移动模型请求的传输秒数
     * @return CloudSim 实际注入的阶段输入秒数
     * @throws IllegalArgumentException 当请求时间或 VM MIPS 非法时抛出
     * @throws IllegalStateException 当调度器意外缩短 Job 长度时抛出
     */
    private double effectiveCloudletStageInSeconds(Job job, Vm vm, long cloudletLengthBeforeStageIn,
            double requestedStageInSeconds) {
        if (requestedStageInSeconds < 0.0 || Double.isNaN(requestedStageInSeconds)
                || Double.isInfinite(requestedStageInSeconds) || vm.getMips() <= 0.0
                || Double.isNaN(vm.getMips()) || Double.isInfinite(vm.getMips())) {
            throw new IllegalArgumentException("Requested stage-in and VM MIPS must be finite and valid");
        }
        long addedMi = job.getCloudletLength() - cloudletLengthBeforeStageIn;
        if (addedMi < 0L) {
            throw new IllegalStateException("Cloudlet scheduler shortened a Job while applying stage-in");
        }
        return addedMi / vm.getMips();
    }

    /**
     * 依据模型推导 Job 内每个原始 Task 的连续计算时间窗，并写入任务级证据事件。
     *
     * <p>第一个 Task 在 Job 的执行开始时间加上生效阶段输入耗时后开始，后续 Task 按
     * {@code taskLengthMi / vmMips} 顺序串行推进。这里更新的是任务级模型时间窗；CloudSim
     * 不允许在此处直接改写 Job 的真实结束时间，因此两者不能混为同一执行证据。
     *
     * @param job 已提交的聚类 Job
     * @param vm 执行 VM
     * @param effectiveStageInSeconds CloudSim 实际生效的阶段输入秒数
     * @param requestedStageInSeconds 数据移动模型请求的阶段输入秒数
     */
    private void updateTaskExecTime(Job job, Vm vm, double effectiveStageInSeconds,
            double requestedStageInSeconds) {
        // CloudSim 将 fileTransferTime 转化为追加的整数 MI；任务时间窗从该生效时长后开始，
        // 而原始请求值作为数据移动模型观测另行保留。
        double start_time = job.getExecStartTime() + effectiveStageInSeconds;
        boolean firstTask = true;
        for (Task task : job.getTaskList()) {
            task.setExecStartTime(start_time);
            double task_runtime = task.getCloudletLength() / vm.getMips();
            start_time += task_runtime;
            // CloudSim 不支持在这里直接重写 Job 的结束时间，只记录 Task 级模型结束时刻。
            task.setTaskFinishTime(start_time);
            eventRecorder.record(SimulationEventType.TASK_EXECUTION_MODELED, CloudSim.clock(), job,
                    SimulationEventRecorder.attributes("taskId", task.getCloudletId(),
                            "taskStartTime", task.getExecStartTime(), "taskFinishTime",
                            task.getTaskFinishTime(), "taskLengthMi", task.getCloudletLength(),
                            "modeledStageInSecondsBeforeTask",
                            firstTask ? effectiveStageInSeconds : 0.0,
                            "requestedDataStageInSecondsForJob", requestedStageInSeconds,
                            "taskTimingScope", "MODEL_DERIVED_COMPUTE_WINDOW"));
            firstTask = false;
        }
    }

    /** 返回列表中真正输入文件的总字节数，不计由同一 Job 产生的输出文件。 */
    private static double realInputBytes(List<FileItem> files) {
        double result = 0.0;
        for (FileItem file : files) {
            if (file.isRealInputFile(files)) {
                result += file.getSize();
            }
        }
        return result;
    }

    /**
     * 为阶段输入 Job 登记文件副本。
     *
     * <p>无论本次运行采用本地还是共享文件系统，当前实现均以数据中心名称向
     * {@link ReplicaCatalog} 登记文件。该操作更新逻辑副本目录，不分配物理存储容量，也不复制
     * 文件内容；真实数据移动耗时在计算 Job 提交时另行建模。
     *
     * @param job 阶段输入 Job
     * @pre $none
     * @post $none
     */
    private void stageInFile2FileSystem(Job job) {
        List<FileItem> fList = job.getFileList();

        for (FileItem file : fList) {
            switch (ReplicaCatalog.getFileSystem()) {
                /** 本地文件系统也以数据中心名称作为逻辑副本位置。 */
                case LOCAL:
                    ReplicaCatalog.addFileToStorage(file.getName(), this.getName());
                    /** 物理 ClusterStorage 写入仍未接入，保留为未来扩展位置。 */
                    //ClusterStorage storage = (ClusterStorage) getStorageList().get(0);
                    //storage.addFile(file);
                    break;
                /** 共享文件系统同样登记到当前数据中心的共享端点。 */
                case SHARED:
                    ReplicaCatalog.addFileToStorage(file.getName(), this.getName());
                    break;
                default:
                    break;
            }
        }
    }

    /**
     * 兼容历史子类的阶段输入扩展点，返回一个计算 Job 的估算传输秒数。
     *
     * <p>标准运行路径通过更完整的 {@link DataTransferEstimate} 获取秒数、真实输入字节数和
     * 文件数，以便证据记录包含全部量；覆盖本方法的旧子类仍可维持原有调用约定。
     *
     * @param requiredFiles 候选输入文件
     * @param job 即将执行的计算 Job
     * @return 估算的数据阶段输入秒数
     * @throws Exception 当副本或 VM 解析失败时抛出
     */
    protected double processDataStageInForComputeJob(List<FileItem> requiredFiles, Job job) throws Exception {
        return estimateDataStageInForComputeJob(requiredFiles, job).getTransferSeconds();
    }

    /**
     * 根据当前数据移动模型计算一个 Job 的输入传输估计。
     *
     * <p>两种模式都仅统计 {@link FileItem#isRealInputFile(List)} 为真的文件。历史模型保留
     * WorkflowSim v1 的带宽选择规则；固定端点模型逐文件累加
     * {@code latency + bytes / (1,000,000 * bottleneckRate)}，不建模并发或共享链路竞争。
     *
     * @param requiredFiles 候选输入文件
     * @param job 目标计算 Job
     * @return 同时包含时间、字节数和文件数的不可变估计
     * @throws Exception 当所需副本或 VM 无法解析时抛出
     */
    private DataTransferEstimate estimateDataStageInForComputeJob(List<FileItem> requiredFiles, Job job)
            throws Exception {
        int requiredFileCount = realInputFileCount(requiredFiles);
        double requiredBytes = realInputBytes(requiredFiles);
        if (dataMovementModel.isLegacyWorkflowsimV1()) {
            return new DataTransferEstimate(legacyProcessDataStageInForComputeJob(requiredFiles, job),
                    requiredBytes, requiredFileCount);
        }
        return fixedEndpointDataStageIn(requiredFiles, job, requiredBytes, requiredFileCount);
    }

    /** 返回真正输入文件的个数，不计同一 Job 内产生的输出文件。 */
    private static int realInputFileCount(List<FileItem> files) {
        int result = 0;
        for (FileItem file : files) {
            if (file.isRealInputFile(files)) {
                result++;
            }
        }
        return result;
    }

    /**
     * 在固定端点、无竞争模型下累计每个真实输入文件的传输耗时。
     *
     * <p>共享文件系统使用共享存储最大传输率；本地文件系统选择目标 VM 可访问副本中的最短
     * 单文件传输时间。每个文件独立相加，因而不表达并行传输、缓存淘汰或共享链路竞争。
     *
     * @param requiredFiles 候选输入文件
     * @param job 目标计算 Job
     * @param requiredBytes 已统计的真实输入总字节数
     * @param requiredFileCount 已统计的真实输入文件数
     * @return 固定端点模型的传输估计
     * @throws Exception 当副本或目标 VM 无法解析时抛出
     */
    private DataTransferEstimate fixedEndpointDataStageIn(List<FileItem> requiredFiles, Job job,
            double requiredBytes, int requiredFileCount) throws Exception {
        double time = 0.0;
        for (FileItem file : requiredFiles) {
            if (!file.isRealInputFile(requiredFiles)) {
                continue;
            }
            List siteList = ReplicaCatalog.getStorageList(file.getName());
            if (siteList == null || siteList.isEmpty()) {
                throw new IllegalStateException("Required input file '" + file.getName()
                        + "' has no registered replica");
            }
            switch (ReplicaCatalog.getFileSystem()) {
                case SHARED:
                    time += fixedEndpointTransferSeconds(file, sharedStorageTransferRate(),
                            "shared storage");
                    break;
                case LOCAL:
                    time += fixedEndpointLocalTransferSeconds(file, job, siteList);
                    break;
                default:
                    throw new IllegalStateException("Unsupported replica file system "
                            + ReplicaCatalog.getFileSystem());
            }
        }
        return new DataTransferEstimate(time, requiredBytes, requiredFileCount);
    }

    /**
     * 为本地文件系统选择目标 VM 可访问副本中的最短单文件传输时间。
     *
     * <p>目标 VM 已有副本时立即返回零；源端、共享端点和另一 VM 副本分别按可用端点带宽
     * 计算，取最小值后才把文件登记到目标 VM。副本站点不是字符串或无法解析为合法端点时
     * 视为配置错误并失败。
     *
     * @param file 输入文件
     * @param job 目标计算 Job
     * @param siteList 已登记副本位置
     * @return 所选副本的固定端点传输秒数
     * @throws Exception 当目标或来源 VM 无法解析时抛出
     */
    private double fixedEndpointLocalTransferSeconds(FileItem file, Job job, List siteList)
            throws Exception {
        int destinationVmId = job.getVmId();
        int userId = job.getUserId();
        Vm destinationVm = requiredVm(destinationVmId, userId, "fixed local stage-in");
        String destination = Integer.toString(destinationVmId);
        double best = Double.POSITIVE_INFINITY;
        for (Object item : siteList) {
            if (!(item instanceof String)) {
                throw new IllegalStateException("Replica site is not a string for input file '"
                        + file.getName() + "'");
            }
            String site = (String) item;
            if (destination.equals(site)) {
                return 0.0;
            }
            double endpointBandwidth;
            if (Parameters.SOURCE.equals(site)) {
                endpointBandwidth = Math.min(dataMovementModel.getSourceEndpointBandwidthMbPerSecond(),
                        destinationVm.getBw());
            } else if (getName().equals(site)) {
                endpointBandwidth = Math.min(sharedStorageTransferRate(), destinationVm.getBw());
            } else {
                int sourceVmId;
                try {
                    sourceVmId = Integer.parseInt(site);
                } catch (NumberFormatException exception) {
                    throw new IllegalStateException("Replica site '" + site
                            + "' is neither the source, shared endpoint, nor a VM identifier", exception);
                }
                Vm sourceVm = requiredVm(sourceVmId, userId, "fixed local stage-in source");
                endpointBandwidth = Math.min(sourceVm.getBw(), destinationVm.getBw());
            }
            best = Math.min(best, fixedEndpointTransferSeconds(file, endpointBandwidth, "replica " + site));
        }
        if (Double.isInfinite(best)) {
            throw new IllegalStateException("Required input file '" + file.getName()
                    + "' has no usable fixed-endpoint replica");
        }
        ReplicaCatalog.addFileToStorage(file.getName(), destination);
        return best;
    }

    /**
     * 查找某一用户在本数据中心内的 VM，缺失时以包含操作上下文的异常快速失败。
     */
    private Vm requiredVm(int vmId, int userId, String operation) {
        Host host = getVmAllocationPolicy().getHost(vmId, userId);
        if (host == null) {
            throw new IllegalStateException("No host contains VM " + vmId + " for " + operation);
        }
        Vm vm = host.getVm(vmId, userId);
        if (vm == null) {
            throw new IllegalStateException("No VM " + vmId + " is available for " + operation);
        }
        return vm;
    }

    /**
     * 返回共享存储列表中的最大有限正传输率。
     *
     * <p>这是固定端点模型的受控简化：它不选择具体存储设备，也不模拟多文件竞争。
     */
    private double sharedStorageTransferRate() {
        double maxRate = 0.0;
        for (Storage storage : getStorageList()) {
            maxRate = Math.max(maxRate, storage.getMaxTransferRate());
        }
        if (!(maxRate > 0.0) || Double.isInfinite(maxRate) || Double.isNaN(maxRate)) {
            throw new IllegalStateException("Shared storage has no finite positive transfer rate");
        }
        return maxRate;
    }

    /**
     * 计算固定端点模型下单文件传输秒数。
     *
     * <p>有效带宽为端点带宽和访问链路带宽的较小值，公式为
     * {@code accessLinkLatencySeconds + fileSizeBytes / (1,000,000 * effectiveRateMbPerSecond)}。
     */
    private double fixedEndpointTransferSeconds(FileItem file, double endpointBandwidth,
            String endpoint) {
        double rate = Math.min(endpointBandwidth, dataMovementModel.getAccessLinkBandwidthMbPerSecond());
        if (!(rate > 0.0) || Double.isInfinite(rate) || Double.isNaN(rate)) {
            throw new IllegalStateException("Fixed endpoint " + endpoint
                    + " has no finite positive bottleneck bandwidth for input file '" + file.getName() + "'");
        }
        return dataMovementModel.getAccessLinkLatencySeconds()
                + file.getSize() / (double) Consts.MILLION / rate;
    }

    /**
     * 保留 WorkflowSim v1 的历史阶段输入估计行为。
     *
     * <p>共享文件系统取存储列表中的最大传输率；本地文件系统优先识别已在目标 VM 上的副本，
     * 否则从可用来源中取最大带宽。该兼容路径不加入访问链路延迟、不模拟竞争或容量，且在
     * 估算完成后只更新逻辑副本目录；不应将其解释为物理网络拓扑模型。
     *
     * @param requiredFiles 候选输入文件
     * @param job 目标计算 Job
     * @return 历史规则得到的传输秒数
     * @throws Exception 当副本或 VM 解析失败时抛出
     */
    private double legacyProcessDataStageInForComputeJob(List<FileItem> requiredFiles, Job job)
            throws Exception {
        // 先按历史带宽规则估算全部真实输入文件的传输秒数，再把输入副本登记到目标 VM。
        // 估算与登记的先后顺序不影响结果：单个文件的估算只读取该文件的副本目录。
        double time = estimateTransferSecondsForFiles(requiredFiles, job);
        registerStageInReplicasForComputeJob(job);
        return time;
    }

    /**
     * 推进所有 Host/VM 的 Cloudlet 处理，并在可预测完成时刻安排下一次数据中心事件。
     *
     * <p>启动阶段保留历史的 {@code 0.111} 时间阈值，确保初始处理循环不会被 CloudSim 的最小
     * 事件间隔跳过；之后只在满足最小事件间隔时更新。该阈值是仿真事件兼容条件，不是任务运行
     * 时间或数据移动模型的一部分。
     */
    @Override
    protected void updateCloudletProcessing() {
        // 启动期允许额外一次处理循环，避免初始 VM/调度器状态未被推进。
        if (CloudSim.clock() < 0.111 || CloudSim.clock() >= getLastProcessTime()
                + CloudSim.getMinTimeBetweenEvents()) {
            List<? extends Host> list = getVmAllocationPolicy().getHostList();
            double smallerTime = Double.MAX_VALUE;
            // 遍历每个 Host，要求其 VM 推进到当前仿真时刻。
            for (Host host : list) {
                // 通知 Host 下的 VM 更新执行状态。
                double time = host.updateVmsProcessing(CloudSim.clock());
                // 汇总最早的下一次 Cloudlet 完成预测时刻。
                if (time < smallerTime) {
                    smallerTime = time;
                }
            }
            // 规范化为满足 CloudSim 最小事件间隔的完成时刻。
            if (smallerTime != Double.MAX_VALUE) {
                smallerTime = SimulationTiming.earliestCloudletCompletionTime(CloudSim.clock(),
                        smallerTime, CloudSim.getMinTimeBetweenEvents());
            }
            if (smallerTime != Double.MAX_VALUE) {
                schedule(getId(), (smallerTime - CloudSim.clock()), CloudSimTags.VM_DATACENTER_EVENT);
            }
            setLastProcessTime(CloudSim.clock());
        }
    }

    /**
     * 取出所有 VM 调度器已完成的 Cloudlet，并将其返回给所属用户。
     *
     * <p>每个完成的 compute Job 会先按照其已记录的 Task 执行时间窗判定故障，再只登记
     * 成功 Task 的输出文件副本。登记是逻辑目录更新，不代表物理数据复制或容量校验；失败
     * Task 的输出不会进入目录。完成结果随后才通过 {@code CLOUDLET_RETURN} 返回给调度器。
     *
     * @pre $none
     * @post $none
     */
    @Override
    protected void checkCloudletCompletion() {
        List<? extends Host> list = getVmAllocationPolicy().getHostList();
        for (Host host : list) {
            for (Vm vm : host.getVmList()) {
                while (vm.getCloudletScheduler().isFinishedCloudlets()) {
                    Cloudlet cl = vm.getCloudletScheduler().getNextFinishedCloudlet();
                    if (cl != null) {
                        if (cl instanceof Job
                                && ((Job) cl).getClassType() == ClassType.COMPUTE.value) {
                            // 必须在登记输出副本前完成故障判定，避免失败尝试污染副本目录。
                            FailureGenerator.generate((Job) cl);
                        }
                        register(cl);
                        sendNow(cl.getUserId(), CloudSimTags.CLOUDLET_RETURN, cl);
                    }
                }
            }
        }
    }
    /**
     * 将一个已完成 {@link Task}（包括其子类 {@link Job}）的成功输出登记为可用副本。
     *
     * <p>compute Job 按其包含的逻辑 Task 分别提交，只有状态为 {@link Cloudlet#SUCCESS}
     * 的 Task 输出可见。这样可支持选择性重试：同一聚类 Job 中已经成功的 Task 可保留产物，
     * 失败 Task 的产物则不会成为下游输入。共享文件系统登记数据中心名称，本地文件系统登记
     * 执行 VM ID。该方法假定调用者已经验证 Cloudlet 属于 Task 层次；缺失 Host/VM 仍按原有
     * 行为在访问时失败。
     *
     * @param cl 已完成的工作流 Cloudlet
     */
    private void register(Cloudlet cl) {
        if (!(cl instanceof Task)) {
            throw new IllegalArgumentException("Completed workflow cloudlet is not a Task");
        }
        String storageLocation;
        switch (ReplicaCatalog.getFileSystem()) {
            case SHARED:
                storageLocation = this.getName();
                break;
            case LOCAL:
                int vmId = cl.getVmId();
                int userId = cl.getUserId();
                Host host = getVmAllocationPolicy().getHost(vmId, userId);
                if (host == null || !(host.getVm(vmId, userId) instanceof CondorVM)) {
                    throw new IllegalStateException("Completed workflow cloudlet " + cl.getCloudletId()
                            + " references unavailable local-storage VM " + vmId);
                }
                storageLocation = Integer.toString(vmId);
                break;
            default:
                throw new IllegalStateException("Unsupported replica file system "
                        + ReplicaCatalog.getFileSystem());
        }

        if (cl instanceof Job && ((Job) cl).getClassType() == ClassType.COMPUTE.value) {
            for (Task task : ((Job) cl).getTaskList()) {
                registerSuccessfulTaskOutputs(task, storageLocation);
            }
            return;
        }
        registerSuccessfulTaskOutputs((Task) cl, storageLocation);
    }

    /**
     * 将一个逻辑 Task 的输出提交到已确定的逻辑位置。
     *
     * <p>该方法是 package-private，以便核心语义测试直接验证失败 Task 不会污染副本目录。
     * 调用方必须已经完成该 Task 的最终状态判定。</p>
     *
     * @param task 已完成并已判定状态的逻辑 Task
     * @param storageLocation 输出副本的逻辑位置
     * @return 新提交的输出文件数；失败 Task 时为零
     */
    static int registerSuccessfulTaskOutputs(Task task, String storageLocation) {
        if (task == null || storageLocation == null || storageLocation.trim().isEmpty()) {
            throw new IllegalArgumentException("Task and non-empty output storage location are required");
        }
        if (task.getCloudletStatus() != Cloudlet.SUCCESS) {
            return 0;
        }
        int registered = 0;
        for (FileItem file : task.getFileList()) {
            if (file.getType() == FileType.OUTPUT) {
                ReplicaCatalog.addFileToStorage(file.getName(), storageLocation);
                registered++;
            }
        }
        return registered;
    }

    /** 在传输提交、事件证据和聚合指标之间共享的不可变数据移动估计。 */
    private static final class DataTransferEstimate {
        private final double transferSeconds;
        private final double requiredFileBytes;
        private final int requiredFileCount;

        private DataTransferEstimate(double transferSeconds, double requiredFileBytes,
                int requiredFileCount) {
            if (transferSeconds < 0.0 || requiredFileBytes < 0.0 || requiredFileCount < 0
                    || Double.isNaN(transferSeconds) || Double.isInfinite(transferSeconds)
                    || Double.isNaN(requiredFileBytes) || Double.isInfinite(requiredFileBytes)) {
                throw new IllegalArgumentException("Data transfer estimate must be finite and non-negative");
            }
            this.transferSeconds = transferSeconds;
            this.requiredFileBytes = requiredFileBytes;
            this.requiredFileCount = requiredFileCount;
        }

        private double getTransferSeconds() { return transferSeconds; }
        private double getRequiredFileBytes() { return requiredFileBytes; }
        private int getRequiredFileCount() { return requiredFileCount; }
    }
}
