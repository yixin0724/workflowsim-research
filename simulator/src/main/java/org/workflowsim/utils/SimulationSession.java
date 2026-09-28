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
    /** True only after this session enters a kernel initialization attempt. */
    private boolean ownsCloudSimKernel;
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
     * <p>通过本地参数检查后，显式初始化调用将内核生命周期交给本会话管理；进入内核的
     * 初始化尝试即使失败也会被清理。仅打开会话、或在进入内核前拒绝参数，不取得其他
     * legacy 内核的归属。会话存续期间不要从其他调用方替换内核；只支持串行生命周期。</p>
     *
     * @param users CloudSim 用户数
     * @param calendar 仿真开始日历；{@code null} 时交由 CloudSim 处理
     * @param trace 是否启用 CloudSim 事件跟踪输出
     * @throws IllegalStateException 当会话已关闭、CloudSim 已初始化或内核初始化失败时
     */
    public void initializeCloudSim(int users, Calendar calendar, boolean trace) {
        requireOpen();
        if (cloudSimInitialized) {
            throw new IllegalStateException("CloudSim is already initialized for this session");
        }
        if (users < 0) {
            throw new IllegalArgumentException("CloudSim user count cannot be negative");
        }
        // Ownership starts after local argument checks, before the first global kernel mutation.
        // Even a failed initialization can leave partial roots that this session must release.
        ownsCloudSimKernel = true;
        try {
            CloudSim.init(users, calendar, trace, config.getCloudSimMinEventIntervalSeconds());
            // The legacy init may log and swallow an initialization exception. A fresh init now
            // clears the old CIS id first, so a missing service cannot masquerade as prior success.
            if (CloudSim.getCloudInfoServiceEntityId() < 0) {
                throw new IllegalStateException("CloudSim kernel initialization failed: "
                        + "Cloud Information Service entity was not registered");
            }
            cloudSimInitialized = true;
        } catch (RuntimeException | Error failure) {
            CloudSim.reset();
            throw failure;
        }
    }

    public SimulationConfig getConfig() {
        return config;
    }

    private void requireOpen() {
        if (closed) {
            throw new IllegalStateException("SimulationSession is already closed");
        }
    }

    /** Attempt every WorkflowSim cleanup even if a preceding cleanup unexpectedly fails. */
    private static void resetWorkflowGlobals() {
        try {
            FailureGenerator.reset();
        } finally {
            try {
                FailureMonitor.reset();
            } finally {
                try {
                    FailureParameters.reset();
                } finally {
                    try {
                        ReplicaCatalog.reset();
                    } finally {
                        Parameters.reset();
                    }
                }
            }
        }
    }

    /**
     * Release session state after serial execution, including failed or unstarted owned kernels.
     *
     * <p>Successful reports must be frozen before closing, as SimulationRunner does. Cleanup
     * invokes no user entity callbacks. A session that never entered kernel initialization
     * leaves an unowned legacy kernel untouched. This is not concurrent cancellation.</p>
     */
    @Override
    public void close() {
        synchronized (SimulationSession.class) {
            if (closed) {
                return;
            }
            try {
                if (ownsCloudSimKernel) {
                    // Do not call finishSimulation: it can run pending user entities and callbacks.
                    CloudSim.reset();
                }
            } finally {
                try {
                    resetWorkflowGlobals();
                } finally {
                    try {
                        Log.setDisabled(logDisabledAtOpen);
                    } finally {
                        closed = true;
                        cloudSimInitialized = false;
                        ownsCloudSimKernel = false;
                        if (activeSession == this) {
                            activeSession = null;
                        }
                    }
                }
            }
        }
    }
}
