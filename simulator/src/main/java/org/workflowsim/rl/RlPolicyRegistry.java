package org.workflowsim.rl;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.workflowsim.exception.SimulationConfigurationException;

/**
 * RL 策略的会话级注册表：把策略实例送达调度器内部，并收集决策轨迹。
 *
 * <p>调度器实例由 {@code WorkflowScheduler} 按枚举构造，无法通过构造器接收策略，
 * 故沿用平台既有静态配置模式（参照 {@code FailureParameters}）：{@code RlEnvironment}
 * 在每个 episode 开始前 {@link #init(RlPolicy)}、结束后 {@link #reset()}。
 * 注册表不是线程安全资源；平台仿真是单线程事件循环。</p>
 */
public final class RlPolicyRegistry {

    private static RlPolicy currentPolicy;
    private static final List<RlDecision> decisions = new ArrayList<RlDecision>();
    private static int stepCount;

    private RlPolicyRegistry() {
    }

    /**
     * 注册 episode 策略并清空轨迹。
     *
     * @param policy 本 episode 使用的策略
     * @throws SimulationConfigurationException 当策略为空时
     */
    public static void init(RlPolicy policy) {
        if (policy == null) {
            throw new SimulationConfigurationException("RL policy is required");
        }
        currentPolicy = policy;
        decisions.clear();
        stepCount = 0;
    }

    /**
     * @return 当前已注册策略
     * @throws SimulationConfigurationException 当无注册策略时（RL_POLICY 调度器
     *         必须在 {@code RlEnvironment} 管理的 episode 内运行）
     */
    public static RlPolicy current() {
        if (currentPolicy == null) {
            throw new SimulationConfigurationException("SchedulingAlgorithm.RL_POLICY requires an RL "
                    + "policy registered through RlEnvironment.runEpisode; run the episode through the "
                    + "environment facade instead of SimulationRunner directly");
        }
        return currentPolicy;
    }

    /**
     * 记录一次成功的分派决策。
     *
     * <p>仅供 {@code RlPolicySchedulingAlgorithm} 在执行分派时调用；其他调用方
     * 不得写入轨迹。</p>
     */
    public static void recordDecision(double time, int jobId, int vmId) {
        decisions.add(new RlDecision(time, jobId, vmId));
    }

    /**
     * 记录一次策略查询（每次 ready-batch 更新计一步，无论产生多少分派）。
     *
     * <p>仅供 {@code RlPolicySchedulingAlgorithm} 在请求动作前调用。</p>
     */
    public static void recordStep() {
        stepCount++;
    }

    /** @return 本 episode 的决策轨迹（不可变，插入序） */
    public static List<RlDecision> decisions() {
        return Collections.unmodifiableList(new ArrayList<RlDecision>(decisions));
    }

    /** @return 本 episode 的策略查询次数（每次 ready-batch 更新计一步） */
    public static int stepCount() {
        return stepCount;
    }

    /** 释放 episode 状态；测试必须在每个用例后调用。 */
    public static void reset() {
        currentPolicy = null;
        decisions.clear();
        stepCount = 0;
    }
}
