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
    public SimulationReport run(SimulationConfig config, PlatformProfile platform)
            throws SimulationExecutionException {
        if (config == null || platform == null) {
            throw new SimulationConfigurationException("Configuration and platform are required");
        }
        validateSupportedAlgorithms(config);
        validateSupportedFailureModel(config);
        validateSupportedExecutionModel(config, platform);
        if (config.getVmCount() != platform.getVms().size()) {
            throw new SimulationConfigurationException("Configuration VM count " + config.getVmCount()
                    + " does not match platform VM count " + platform.getVms().size());
        }
        validateCostModel(config, platform);

        try (SimulationSession session = SimulationSession.open(config)) {
            SimulationEventRecorder events = new SimulationEventRecorder();
            session.initializeCloudSim(1, Calendar.getInstance(), false);
            WorkflowDatacenter datacenter = PlatformFactory.createDatacenter("datacenter-0", platform);
            datacenter.setEventRecorder(events);
            datacenter.setDataMovementModel(config.getDataMovementModel());
            WorkflowPlanner planner = new WorkflowPlanner("planner-0", 1);
            planner.setEventRecorder(events);
            planner.setPlanningContext(new PlanningContext(config, platform));
            planner.setTaskCostMatrix(config.getTaskCostMatrix());
            WorkflowEngine engine = planner.getWorkflowEngine();
            List<CondorVM> vms = PlatformFactory.createVms(platform, engine.getSchedulerId(0));
            engine.submitVmList(vms, 0);
            engine.bindSchedulerDatacenter(datacenter.getId(), 0);

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
            List<Job> completedJobs = engine.getJobsReceivedList();
            Map<Integer, Integer> actualVmHostAssignments = engine.getScheduler(0)
                    .getCreatedVmHostAssignments();
            if (!platform.getVmHostAssignments().equals(actualVmHostAssignments)) {
                throw new PlatformException("CloudSim VM-to-Host allocation " + actualVmHostAssignments
                        + " does not match preflighted platform placement "
                        + platform.getVmHostAssignments());
            }
            try {
                return SimulationReport.capture(config, platform, makespan,
                        planner.getWorkflowParser().getInputReports(), completedJobs, events.snapshot(),
                        planner.getParsedTaskSnapshot(), planner.getSharedStorageDagPlanTrace(),
                        actualVmHostAssignments);
            } catch (IOException exception) {
                throw new SimulationExecutionException(
                        "Simulation completed but its evidence record could not be created", exception);
            }
        } catch (SimulationExecutionException e) {
            throw e;
        } catch (org.workflowsim.failure.RetryLimitExceededException e) {
            // RetryLimitExceededException 是特定的运行时异常，应该直接抛出以便测试捕获
            throw e;
        } catch (IllegalStateException e) {
            // IllegalStateException 可能是故障生成器或其他验证逻辑抛出的，应该直接抛出
            throw e;
        } catch (Exception e) {
            // 将其他未预期的异常包装为 SimulationExecutionException
            throw new SimulationExecutionException("Unexpected error during simulation execution", e);
        }
    }

    /**
     * 标准运行器仅接受具有维护中语义契约的算法。历史示例仍可使用旧静态 API，
     * 但不得通过此入口悄然生成研究证据。
     */
    private static void validateSupportedAlgorithms(SimulationConfig config) {
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
                throw new SimulationConfigurationException("CostModel.VM requires explicit pricing for every VM; "
                        + "VM " + vm.getId() + " has none");
            }
        }
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
        if (config.getFailureModel().getMonitorMode()
                != FailureParameters.FTCMonitor.MONITOR_NONE) {
            throw new SimulationConfigurationException("SimulationRunner supports MONITOR_NONE only: under the "
                    + "verified FTCLUSTERING_NOOP retry model, other monitor modes do not alter recovery "
                    + "or scheduling and must not be treated as a research factor");
        }
    }

    /**
     * 受维护的研究入口只支持一种受控执行模型。为兼容 API，历史聚类和时间共享 VM 模式仍可
     * 被构造；但它们与 ready-Job 调度器、任务结果和指标的交互不具有标准运行器回归契约。
     */
    private static void validateSupportedExecutionModel(SimulationConfig config,
            PlatformProfile platform) {
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
}
