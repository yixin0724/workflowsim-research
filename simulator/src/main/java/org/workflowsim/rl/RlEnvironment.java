package org.workflowsim.rl;

import org.workflowsim.experiment.SimulationReport;
import org.workflowsim.experiment.SimulationRunner;
import org.workflowsim.exception.SimulationConfigurationException;
import org.workflowsim.platform.PlatformProfile;
import org.workflowsim.utils.Parameters.SchedulingAlgorithm;
import org.workflowsim.utils.SimulationConfig;

/**
 * R4 RL 轨道的环境门面：把平台的确定性事件循环封装成 episode 接口。
 *
 * <p>一次 {@link #runEpisode(SimulationConfig, PlatformProfile, RlPolicy)} 调用 =
 * 一个完整 episode：注册策略 → 经标准 {@link SimulationRunner} 运行仿真（RL_POLICY
 * 调度器在每次 ready-batch 更新时向策略请求 Job→VM 动作）→ 收集决策轨迹 → 释放
 * 注册表 → 返回 {@link RlEpisodeResult}（makespan、reward = −makespan、步数、轨迹）。</p>
 *
 * <p><b>边界</b>：本环境提供状态、动作、奖励的契约与确定性闭环，不提供学习算法
 * 本身；接入 PyTorch/JAX 等外部训练器需要进程外桥接（平台是单进程 Java 事件循环），
 * 属于 R4 之后的扩展方向。基线策略 {@link EarliestFinishGreedyPolicy} 用于验证
 * 环境闭环并给出可比较的参考成绩。</p>
 */
public final class RlEnvironment {

    /**
     * 运行一个完整 episode。
     *
     * @param config 调度算法必须为 {@link SchedulingAlgorithm#RL_POLICY}
     * @param platform 平台描述（与标准运行器契约一致）
     * @param policy 本 episode 的调度策略（必须确定性）
     * @return episode 结果（证据报告 + 奖励 + 决策轨迹）
     * @throws SimulationConfigurationException 当配置未选择 RL_POLICY 或策略为空时
     * @throws org.workflowsim.exception.SimulationExecutionException 当仿真执行失败时
     */
    public RlEpisodeResult runEpisode(SimulationConfig config, PlatformProfile platform,
            RlPolicy policy) throws org.workflowsim.exception.SimulationExecutionException {
        if (config == null) {
            throw new SimulationConfigurationException("Configuration is required");
        }
        if (config.getSchedulingAlgorithm() != SchedulingAlgorithm.RL_POLICY) {
            throw new SimulationConfigurationException("RlEnvironment requires "
                    + "SchedulingAlgorithm.RL_POLICY, got " + config.getSchedulingAlgorithm());
        }
        RlPolicyRegistry.init(policy);
        try {
            SimulationReport report = new SimulationRunner().run(config, platform);
            return new RlEpisodeResult(report, RlPolicyRegistry.decisions(),
                    RlPolicyRegistry.stepCount());
        } finally {
            RlPolicyRegistry.reset();
        }
    }
}
