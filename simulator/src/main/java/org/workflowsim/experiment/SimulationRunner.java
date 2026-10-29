package org.workflowsim.experiment;

import java.io.IOException;
import java.util.Calendar;
import java.util.List;
import java.util.Map;

import org.cloudbus.cloudsim.core.CloudSim;
import org.workflowsim.CondorVM;
import org.workflowsim.Job;
import org.workflowsim.WorkflowDatacenter;
import org.workflowsim.WorkflowEngine;
import org.workflowsim.WorkflowPlanner;
import org.workflowsim.exception.PlatformException;
import org.workflowsim.exception.SimulationConfigurationException;
import org.workflowsim.exception.SimulationExecutionException;
import org.workflowsim.failure.FailureParameters;
import org.workflowsim.planning.PlanningContext;
import org.workflowsim.platform.PlatformFactory;
import org.workflowsim.platform.PlatformProfile;
import org.workflowsim.utils.ClusteringParameters.ClusteringMethod;
import org.workflowsim.utils.Parameters.CostModel;
import org.workflowsim.utils.Parameters.SchedulingAlgorithm;
import org.workflowsim.utils.SimulationConfig;
import org.workflowsim.utils.SimulationSession;

/** 在显式配置和平台描述下运行受维护的标准 WorkflowSim 流水线。 */
public final class SimulationRunner {

    /**
     * 执行一次解析、聚类、规划、调度和 CloudSim 事件处理，并在释放全局状态前返回不可变报告。
     *
     * @param config 包含输入、算法、随机性和模型选择的显式仿真配置
     * @param platform 已完成容量预检的平台描述
     * @return 本次运行的完整不可变证据报告
     * @throws SimulationConfigurationException 当配置、平台或受维护执行模型的前置条件不满足时（unchecked）
     * @throws PlatformException 当平台资源配置或 VM-to-Host 放置失败时（unchecked）
     * @throws SimulationExecutionException 当解析、CloudSim 执行或报告捕获失败时
     */
    public SimulationReport run(SimulationConfig config, PlatformProfile platform) throws SimulationExecutionException {
        if (config == null || platform == null) {
            throw new SimulationConfigurationException("Configuration and platform are required");
        }
        validateSupportedAlgorithms(config, platform);
        validateSupportedFailureModel(config);
        validateSupportedExecutionModel(config, platform);
        if (config.getVmCount() != platform.getVms().size()) {
            throw new SimulationConfigurationException("Configuration VM count " + config.getVmCount()
                    + " does not match platform VM count " + platform.getVms().size());
        }
        validateCostModel(config, platform);
        if (config.getDataMovementModel().isCoherentStorageDataflowV3() != (platform.getSourceStorage() != null)) {
            throw new SimulationConfigurationException(
                    "Bounded sourceStorage must be present exactly for the storage V3 dataflow model");
        }
        // BAT-D2（2026-10-29）：DVFS 频档维度前提（docs/advanced/DVFS_DIMENSION_DESIGN.md §2.2）。
        validateFrequencyDimension(config, platform);
        // Fat-tree 链路争用模型：在会话外装配拓扑并校验模型与拓扑声明的双向契约
        // （配置异常在此直接以 SimulationConfigurationException 抛出，不被执行期
        // 包装；拓扑构造是纯配置计算，不触碰 CloudSim 状态）。
        org.workflowsim.network.FatTreeTopology fatTreeTopology = prepareFatTreeTopology(config, platform);

        try (SimulationSession session = SimulationSession.open(config)) {
            return executeWithinSession(session, config, platform, fatTreeTopology);
        } catch (SimulationExecutionException e) {
            throw e;
        } catch (org.workflowsim.failure.RetryLimitExceededException e) {
            // RetryLimitExceededException 是特定的运行时异常，应该直接抛出以便测试捕获
            throw e;
        } catch (IllegalStateException e) {
            // IllegalStateException 可能是故障生成器或其他验证逻辑抛出的，应该直接抛出
            throw e;
        } catch (SimulationConfigurationException e) {
            // R8 审计修复（P1-2）：配置异常不得降级为执行异常——Javadoc 契约
            // 声明 @throws SimulationConfigurationException，按类型分流的调用方
            // 依赖该保证。
            throw e;
        } catch (IllegalArgumentException e) {
            // R8 审计修复（P1-2）：解析/校验类 IAE（含 WorkflowValidationException）
            // 原样放行，根因消息直达调用方，不再包装成 "Unexpected error"。
            throw e;
        } catch (Exception e) {
            // 将其他未预期的异常包装为 SimulationExecutionException
            throw new SimulationExecutionException("Unexpected error during simulation execution", e);
        }
    }

    /** run() 会话内的装配阶段：datacenter/planner/engine/vm/coherent 装配（逐字搬运转写）。 */
    private SimulationAssembly assembleSession(
            SimulationSession session,
            SimulationConfig config,
            PlatformProfile platform,
            SimulationEventRecorder events,
            org.workflowsim.network.FatTreeTopology fatTreeTopology)
            throws Exception {
        session.initializeCloudSim(1, Calendar.getInstance(), false);
        WorkflowDatacenter datacenter = PlatformFactory.createDatacenter("datacenter-0", platform);
        datacenter.setEventRecorder(events);
        datacenter.setDataMovementModel(config.getDataMovementModel());
        datacenter.setNetworkEvidenceConfig(config.getNetworkEvidenceConfig());
        if (fatTreeTopology != null) {
            datacenter.setFatTreeTopology(fatTreeTopology);
        }
        WorkflowPlanner planner = new WorkflowPlanner("planner-0", 1);
        planner.setEventRecorder(events);
        planner.setPlanningContext(new PlanningContext(config, platform));
        planner.setTaskCostMatrix(config.getTaskCostMatrix());
        WorkflowEngine engine = planner.getWorkflowEngine();
        org.workflowsim.data.v2.CoherentDataflowRuntime coherent = null;
        if (config.getDataMovementModel().usesCoherentDataflowRuntime()) {
            int budget = config.getNetworkEvidenceConfig().isEnabled()
                    ? config.getNetworkEvidenceConfig().getMaxTraceRecords()
                    : 0;
            coherent = config.getDataMovementModel().isCoherentStorageDataflowV3()
                    ? org.workflowsim.data.v2.CoherentDataflowRuntime.withStorage(
                            config.getDataMovementModel().usesSharedDataflowResources(),
                            budget,
                            config.getFileSystem() == org.workflowsim.utils.ReplicaCatalog.FileSystem.SHARED)
                    : new org.workflowsim.data.v2.CoherentDataflowRuntime(
                            config.getDataMovementModel().isCoherentShared(), budget);
            if (platform.getSourceStorage() != null) {
                datacenter.setSourceStorage(platform.getSourceStorage());
            }
            planner.setCoherentDataflowRuntime(coherent);
            engine.setCoherentDataflowRuntime(coherent);
            datacenter.setCoherentDataflowRuntime(coherent, engine.getId());
            engine.setDataflowAssignmentConfig(config.getDataflowAssignmentConfig());
            if (config.getDataflowAssignmentConfig().isEnabled()) {
                engine.getScheduler(0).enableDataflowBindingGuard();
            }
        }
        List<CondorVM> vms = PlatformFactory.createVms(platform, engine.getSchedulerId(0));
        engine.submitVmList(vms, 0);
        engine.bindSchedulerDatacenter(datacenter.getId(), 0);
        SimulationAssembly assembly = new SimulationAssembly();
        assembly.datacenter = datacenter;
        assembly.planner = planner;
        assembly.engine = engine;
        assembly.coherent = coherent;
        return assembly;
    }

    /** run() 会话内的执行/校验/捕获阶段（PLAT-14 看门狗与证据快照逐字搬运转写）。 */
    private SimulationReport executeWithinSession(
            SimulationSession session,
            SimulationConfig config,
            PlatformProfile platform,
            org.workflowsim.network.FatTreeTopology fatTreeTopology)
            throws Exception {
        SimulationEventRecorder events = new SimulationEventRecorder();
        SimulationAssembly assembly = assembleSession(session, config, platform, events, fatTreeTopology);
        WorkflowDatacenter datacenter = assembly.datacenter;
        WorkflowPlanner planner = assembly.planner;
        WorkflowEngine engine = assembly.engine;
        org.workflowsim.data.v2.CoherentDataflowRuntime coherent = assembly.coherent;

        double makespan = CloudSim.startSimulation();
        // PLAT-14：停滞看门狗。仿真自然结束但引擎仍有待释放或在途 Job，说明事件链
        // 断裂导致工作流静默停滞（无后续事件驱动释放/返回）。必须显式失败，
        // 而不是产出只含 INCOMPLETE_LOGICAL_TASKS 标记的残缺报告。
        // RetryLimitExceededException 等合法中止路径通过异常传播，不会到达本检查。
        if (!engine.getJobsList().isEmpty() || engine.getInFlightJobCount() != 0) {
            throw new SimulationExecutionException("Simulation terminated while the workflow engine "
                    + "still holds " + engine.getJobsList().size() + " unreleased job(s) and "
                    + engine.getInFlightJobCount() + " in-flight job(s): the event chain stalled "
                    + "silently and the run would produce incomplete evidence");
        }
        if (coherent != null && !coherent.isQuiescent()) {
            throw new SimulationExecutionException(
                    "Simulation stopped before V2 file service and requested Jobs became quiescent");
        }
        List<Job> completedJobs = engine.getJobsReceivedList();
        Map<Integer, Integer> actualVmHostAssignments = engine.getScheduler(0).getCreatedVmHostAssignments();
        if (!platform.getVmHostAssignments().equals(actualVmHostAssignments)) {
            throw new PlatformException("CloudSim VM-to-Host allocation " + actualVmHostAssignments
                    + " does not match preflighted platform placement "
                    + platform.getVmHostAssignments());
        }
        // F-006 contract revision (2026-10-23, user-adjudicated): a naturally completed run
        // must terminate its event stream with the termination clock so the makespan family
        // stays event-recomputable even when a positive post-delay tail follows the last
        // JOB_RETURNED. Aborted and failed runs deliberately pass this point without the
        // marker; absence of the event therefore identifies an incomplete execution.
        events.record(
                SimulationEventType.SIMULATION_TERMINATED,
                makespan,
                null,
                SimulationEventRecorder.attributes("simulationEndSeconds", Double.valueOf(makespan)));
        try {
            return SimulationReport.capture(
                    config,
                    platform,
                    makespan,
                    planner.getWorkflowParser().getInputReports(),
                    completedJobs,
                    events.snapshot(),
                    planner.getParsedTaskSnapshot(),
                    planner.getSharedStorageDagPlanTrace(),
                    actualVmHostAssignments,
                    datacenter.captureNetworkEvidence(),
                    datacenter.captureFileLifecycleEvidence(),
                    coherent == null ? null : coherent.getFilePlanDocument(),
                    datacenter.captureStorageLifecycleEvidence());
        } catch (IOException exception) {
            throw new SimulationExecutionException(
                    "Simulation completed but its evidence record could not be created", exception);
        }
    }

    /** run() 会话装配的私有搬运结构（字段直读，无转发行为）。 */
    private static final class SimulationAssembly {
        private WorkflowDatacenter datacenter;
        private WorkflowPlanner planner;
        private WorkflowEngine engine;
        private org.workflowsim.data.v2.CoherentDataflowRuntime coherent;
    }

    /**
     * 标准运行器仅接受具有维护中语义契约的算法。历史示例仍可使用旧静态 API，
     * 但不得通过此入口悄然生成研究证据。
     */
    private static void validateSupportedAlgorithms(SimulationConfig config, PlatformProfile platform) {
        if (config.getRegisteredSchedulingAlgorithmProvider() != null) {
            // BAT-3（2026-10-26）：注册算轨走自声明契约矩阵；枚举规划/调度都必须为 INVALID。
            if (config.getSchedulingAlgorithm() != SchedulingAlgorithm.INVALID
                    || (config.getPlanningAlgorithm() != null
                            && config.getPlanningAlgorithm()
                                    != org.workflowsim.utils.Parameters.PlanningAlgorithm.INVALID)) {
                throw new SimulationConfigurationException(
                        "A registry-based scheduling algorithm occupies the plain online"
                                + " scheduling layer: schedulingAlgorithm and planningAlgorithm"
                                + " must both be INVALID");
            }
            org.workflowsim.experiment.registry.AlgorithmRegistry.validate(
                    config.getRegisteredSchedulingAlgorithmProvider().label(),
                    new org.workflowsim.experiment.registry.AlgorithmRegistry.ValidationInput(
                            config.getFileSystem(),
                            config.getDataMovementModel(),
                            config.getTaskCostMatrix() != null
                                    && !config.getTaskCostMatrix().asMap().isEmpty(),
                            config.getFailureModel(),
                            config.getPlanningAlgorithm(),
                            anyVmHasPowerModel(platform)));
            return;
        }
        if (!AlgorithmCatalog.isSupportedBySimulationRunner(config.getSchedulingAlgorithm())) {
            if (config.getSchedulingAlgorithm() == SchedulingAlgorithm.INVALID) {
                throw new SimulationConfigurationException(
                        "SchedulingAlgorithm.INVALID is not supported by SimulationRunner");
            }
            throw new SimulationConfigurationException("Scheduling algorithm "
                    + config.getSchedulingAlgorithm() + " is a legacy compatibility label and is "
                    + "not supported by SimulationRunner; use its READY_BATCH_* replacement");
        }
        if (!AlgorithmCatalog.isSupportedBySimulationRunner(config.getPlanningAlgorithm())) {
            throw new SimulationConfigurationException("Planning algorithm "
                    + config.getPlanningAlgorithm() + " is legacy mapping-only code and is not "
                    + "supported by SimulationRunner; use SHARED_STORAGE_HEFT, SHARED_STORAGE_CPOP, or "
                    + "SHARED_STORAGE_DLS, SHARED_STORAGE_ETF, or SHARED_STORAGE_PEFT "
                    + "under their controlled model");
        }
    }

    private static void validateCostModel(SimulationConfig config, PlatformProfile platform) {
        if (config.getCostModel() != CostModel.VM) {
            return;
        }
        for (PlatformProfile.VmSpec vm : platform.getVms()) {
            if (!vm.hasCosts()) {
                throw new SimulationConfigurationException(
                        "CostModel.VM requires explicit pricing for every VM; " + "VM " + vm.getId() + " has none");
            }
        }
    }

    /**
     * 装配 Fat-tree 拓扑并校验其与数据移动模型的双向契约。
     *
     * <p>使用 {@code fatTreeContentionV1()} 时必须通过
     * {@code PlatformProfile.networkTopology(...)} 声明拓扑；coherent V2/V3 可选择
     * 声明拓扑并使用实际放置路径。其他模型拒绝多余拓扑，避免声明被静默忽略。</p>
     *
     * @param config 仿真配置
     * @param platform 平台描述
     * @return 已放置拓扑；模型不使用拓扑时为 {@code null}
     * @throws SimulationConfigurationException 当双向契约任一方向不满足时
     */
    private static org.workflowsim.network.FatTreeTopology prepareFatTreeTopology(
            SimulationConfig config, PlatformProfile platform) {
        boolean coherent = config.getDataMovementModel().usesCoherentDataflowRuntime();
        if (config.getDataMovementModel().isFatTreeContentionV1()
                || (coherent && platform.getNetworkTopology() != null)) {
            if (platform.getNetworkTopology() == null) {
                throw new SimulationConfigurationException(
                        config.getDataMovementModel().getKind()
                                + " requires PlatformProfile.builder(...).networkTopology("
                                + "NetworkTopologySpec.fatTree(...)) so VM-to-VM transfers can be "
                                + "routed along deterministic fat-tree paths");
            }
            if (coherent) {
                int k = platform.getNetworkTopology().getK();
                double bytes = platform.getNetworkTopology().getLinkBandwidthMbPerSecond() * 1_000_000.0;
                if (k < 2 || k > 32 || k % 2 != 0 || !Double.isFinite(bytes) || bytes < Double.MIN_NORMAL) {
                    throw new SimulationConfigurationException(
                            "Coherent V2 supports even Fat-tree k in [2,32] and normal finite converted B/s"
                                    + "capacities");
                }
            }
            List<Integer> hostIds = new java.util.ArrayList<Integer>();
            for (PlatformProfile.HostSpec host : platform.getHosts()) {
                hostIds.add(Integer.valueOf(host.getId()));
            }
            return org.workflowsim.network.FatTreeTopology.fromSpec(platform.getNetworkTopology(), hostIds);
        }
        if (platform.getNetworkTopology() != null) {
            throw new SimulationConfigurationException("Platform declares a network topology but the "
                    + "data movement model " + config.getDataMovementModel().getKind()
                    + " does not use it; declare the topology only with "
                    + "DataMovementModel.fatTreeContentionV1()");
        }
        return null;
    }

    /**
     * 在标准运行器中，只有无操作重聚类具备执行层面的重试契约。其他历史重聚类启发式仍含
     * 硬编码且未经验证的调优规则，因此仅能经由旧 API 使用。
     */
    private static void validateSupportedFailureModel(SimulationConfig config) {
        if (config.getFailureModel().getClusteringAlgorithm()
                != FailureParameters.FTCluteringAlgorithm.FTCLUSTERING_NOOP) {
            throw new SimulationConfigurationException("Failure reclustering algorithm "
                    + config.getFailureModel().getClusteringAlgorithm()
                    + " is legacy compatibility code and is not supported by SimulationRunner; "
                    + "use FTCLUSTERING_NOOP for the verified retry model");
        }
        if (config.getFailureModel().getMonitorMode() != FailureParameters.FTCMonitor.MONITOR_NONE) {
            throw new SimulationConfigurationException("SimulationRunner supports MONITOR_NONE only: under the "
                    + "verified FTCLUSTERING_NOOP retry model, other monitor modes do not alter recovery "
                    + "or scheduling and must not be treated as a research factor");
        }
    }

    /**
     * 受维护的研究入口只支持一种受控执行模型。为兼容 API，历史聚类和时间共享 VM 模式仍可
     * 被构造；但它们与 ready-Job 调度器、任务结果和指标的交互不具有标准运行器回归契约。
     */
    private static void validateSupportedExecutionModel(SimulationConfig config, PlatformProfile platform) {
        if (config.getClusteringParameters().getClusteringMethod() != ClusteringMethod.NONE) {
            throw new SimulationConfigurationException("Task clustering method "
                    + config.getClusteringParameters().getClusteringMethod()
                    + " is not supported by SimulationRunner; use clustering method NONE for "
                    + "the verified task-level execution model");
        }
        for (PlatformProfile.VmSpec vm : platform.getVms()) {
            if (vm.getSchedulerMode() != PlatformProfile.CloudletSchedulerMode.SPACE_SHARED) {
                throw new SimulationConfigurationException("VM " + vm.getId() + " uses "
                        + vm.getSchedulerMode() + "; SimulationRunner supports SPACE_SHARED VMs "
                        + "only for the verified task-level execution model");
            }
        }
    }

    /** BAT-E3（2026-10-27）：@return 平台剖面中是否存在任何声明了功率字段的 VM。 */
    private static boolean anyVmHasPowerModel(PlatformProfile platform) {
        for (PlatformProfile.VmSpec spec : platform.getVms()) {
            if (spec.hasPowerModel()) {
                return true;
            }
        }
        return false;
    }

    /**
     * BAT-D2（2026-10-29）：DVFS 频档维度的运行级前提。
     *
     * <p>（1）非 DEFAULT 策略要求平台至少有一台 VM 声明频档表——在全无档平台上，
     * MIN_POWER/EDP_HEURISTIC 会被静默忽略，产生"以为在研究 DVFS 实际在跑 v1"的
     * 误导性结果；（2）任务×VM 成本矩阵与频档表互斥：STATIC 派发路径在数据中心提交
     * 时以矩阵折算的 MI 为真源重构 Job 长度（{@code projectComputeExecutionLength}），
     * 会覆盖派发时的频档缩放长度、静默丢弃变频减速（与 R8 审计 P1-1 矩阵×规划器
     * 分叉同族）。两处都显式拒绝。</p>
     */
    private static void validateFrequencyDimension(SimulationConfig config, PlatformProfile platform) {
        boolean anyTieredVm = false;
        for (PlatformProfile.VmSpec spec : platform.getVms()) {
            if (spec.hasFrequencyTiers()) {
                anyTieredVm = true;
                break;
            }
        }
        if (config.getFrequencyPolicy() != SimulationConfig.FrequencyPolicy.DEFAULT && !anyTieredVm) {
            throw new SimulationConfigurationException(
                    "FrequencyPolicy " + config.getFrequencyPolicy() + " requires at least one VM with "
                            + "declared frequencyTiers; on an all-plain platform the policy would be "
                            + "silently inert (BAT-D2)");
        }
        if (config.getTaskCostMatrix() != null && anyTieredVm) {
            throw new SimulationConfigurationException("A task cost matrix cannot be combined with frequency"
                    + " tiers: the STATIC dispatch path re-derives Job MI from matrix seconds at datacenter"
                    + " submission (projectComputeExecutionLength) and would silently discard the "
                    + "tier-scaled length (BAT-D2)");
        }
    }
}
