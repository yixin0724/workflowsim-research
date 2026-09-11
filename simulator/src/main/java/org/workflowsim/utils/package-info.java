/**
 * WorkflowSim 的配置、随机性、开销、故障参数和遗留全局状态支撑。
 *
 * <p>{@link org.workflowsim.utils.SimulationConfig} 是一次标准运行的不可变声明；
 * {@link org.workflowsim.utils.SimulationSession} 在每次串行运行前后管理遗留
 * {@code Parameters}、副本目录和故障模型的全局状态。由于底层 CloudSim 内核保留静态状态，
 * 本包不承诺同一 JVM 内并发执行多个仿真会话。</p>
 */
package org.workflowsim.utils;
