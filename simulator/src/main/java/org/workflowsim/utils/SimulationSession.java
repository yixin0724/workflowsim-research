package org.workflowsim.utils;

import java.util.Calendar;
import org.cloudbus.cloudsim.Log;
import org.cloudbus.cloudsim.core.CloudSim;
import org.workflowsim.failure.FailureGenerator;
import org.workflowsim.failure.FailureMonitor;
import org.workflowsim.failure.FailureParameters;

/**
 * 管理一次串行实验关联的 WorkflowSim 全局状态。
 *
 * <p>随附的 CloudSim 3 内核使用 JVM 全局状态，因此同一 JVM 内会话显式拒绝并发使用；
 * 但每个按顺序打开的会话都会获得全新的 WorkflowSim 配置。调用方应使用
 * try-with-resources 确保 {@link #close()} 被调用。</p>
 */
public final class SimulationSession implements AutoCloseable {

    private static SimulationSession activeSession;

    private final SimulationConfig config;
    private boolean closed;
    private boolean cloudSimInitialized;
    /** 会话打开时的 Log 全局开关状态，close 时恢复（R8 审计修复 P1-5）。 */
    private final boolean logDisabledAtOpen;

    private SimulationSession(SimulationConfig config) {
        this.config = config;
        this.logDisabledAtOpen = Log.isDisabled();
    }

    /**
     * 打开并配置一个新的串行 WorkflowSim 会话。
     *
     * @param config 本次运行的不可变配置
     * @return 已激活的会话
     * @throws IllegalStateException 当同一 JVM 中已有未关闭会话时
     */
    public static synchronized SimulationSession open(SimulationConfig config) {
        if (config == null) {
            throw new IllegalArgumentException("Simulation configuration cannot be null");
        }
        if (activeSession != null) {
            throw new IllegalStateException("A SimulationSession is already active in this JVM");
        }

        Parameters.reset();
        Parameters.setRandomSeed(config.getRandomSeed());
        Parameters.setRuntimeScale(config.getRuntimeScale());
        Parameters.setRuntimeReferenceMips(config.getRuntimeReferenceMips());
        Parameters.setCostModel(config.getCostModel());
        if (config.getWorkflowPaths().size() == 1) {
            Parameters.init(config.getVmCount(), config.getWorkflowPaths().get(0), null, null,
                    config.getOverheadModel().createParameters(), config.getClusteringParameters(),
                    config.getSchedulingAlgorithm(), config.getPlanningAlgorithm(),
                    config.getReduceMethod(), config.getDeadline());
        } else {
            Parameters.init(config.getVmCount(), config.getWorkflowPaths(), null, null,
                    config.getOverheadModel().createParameters(), config.getClusteringParameters(),
                    config.getSchedulingAlgorithm(), config.getPlanningAlgorithm(),
                    config.getReduceMethod(), config.getDeadline());
        }
        Parameters.setWorkflowArrivalSeconds(config.getWorkflowArrivalSeconds());
        ReplicaCatalog.init(config.getFileSystem());
        FailureParameters.reset();
        FailureMonitor.init();
        FailureGenerator.reset();
        config.getFailureModel().applyToFailureParameters(config.getRandomSeed());
        FailureGenerator.init();

        activeSession = new SimulationSession(config);
        return activeSession;
    }

    /**
     * 在所有 WorkflowSim 全局状态已配置、任何仿真实体创建前初始化 CloudSim 内核。
     *
     * @param users CloudSim 用户数
     * @param calendar 仿真开始日历；{@code null} 时交由 CloudSim 处理
     * @param trace 是否启用 CloudSim 事件跟踪输出
     * @throws IllegalStateException 当会话已关闭或 CloudSim 已初始化时
     */
    public void initializeCloudSim(int users, Calendar calendar, boolean trace) {
        requireOpen();
        if (cloudSimInitialized) {
            throw new IllegalStateException("CloudSim is already initialized for this session");
        }
        if (users < 0) {
            throw new IllegalArgumentException("CloudSim user count cannot be negative");
        }
        CloudSim.init(users, calendar, trace, config.getCloudSimMinEventIntervalSeconds());
        // R8 审计修复（P1-3）：vendored CloudSim.init 吞掉全部初始化异常（仅 Log），
        // 失败后 cisId 保持 -1——标准路径不可达，但任何扩展踩中会产出静默停滞或
        // 0 任务"成功"报告。此处断言内核服务实体确实注册成功。
        if (CloudSim.getCloudInfoServiceEntityId() < 0) {
            throw new IllegalStateException("CloudSim kernel initialization failed: "
                    + "Cloud Information Service entity was not registered");
        }
        cloudSimInitialized = true;
    }

    public SimulationConfig getConfig() {
        return config;
    }

    private void requireOpen() {
        if (closed) {
            throw new IllegalStateException("SimulationSession is already closed");
        }
    }

    /**
     * 清理 WorkflowSim 全局状态。
     *
     * <p>已完成的 CloudSim 运行会自行清理内核状态；未完成的运行会在下一会话前被停止。</p>
     */
    @Override
    public void close() {
        synchronized (SimulationSession.class) {
            if (closed) {
                return;
            }
            if (cloudSimInitialized && CloudSim.running()) {
                CloudSim.stopSimulation();
            }
            FailureGenerator.reset();
            FailureMonitor.reset();
            FailureParameters.reset();
            ReplicaCatalog.reset();
            Parameters.reset();
            // R8 审计修复（P1-5）：Log 是 JVM 全局静态开关且无会话管理，测试/执行器
            // disable 后不恢复会让下一会话继承关闭状态，吞掉内核所有"仅日志"诊断。
            Log.setDisabled(logDisabledAtOpen);
            closed = true;
            activeSession = null;
        }
    }
}
