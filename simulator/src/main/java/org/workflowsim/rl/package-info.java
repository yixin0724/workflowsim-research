/**
 * R4 RL 调度轨道：状态、动作、奖励契约与环境闭环。
 *
 * <p>本包把平台的确定性事件循环封装成强化学习环境：状态 = 就绪 Job 队列 +
 * VM 负载视图（{@link org.workflowsim.rl.RlObservation}），动作 = 每个就绪 Job
 * 的 VM 选择（{@link org.workflowsim.rl.RlPolicy}），奖励 = −makespan
 * （{@link org.workflowsim.rl.RlEpisodeResult}）。episode 由
 * {@link org.workflowsim.rl.RlEnvironment} 驱动，内部经标准
 * {@code SimulationRunner} 执行，决策轨迹完整可审计。</p>
 *
 * <p>接入方式：配置 {@code SchedulingAlgorithm.RL_POLICY}（在线调度轨道，规划层
 * 必须为 INVALID），经 {@code RlEnvironment.runEpisode} 运行。直接以
 * {@code SimulationRunner} 运行 RL_POLICY 配置会因缺少注册策略而显式失败。</p>
 */
package org.workflowsim.rl;
