/**
 * 故障生成、监控与会话级故障模型配置。
 *
 * <p>故障分布和监控方式描述的是模拟中的情景假设，而不是对真实平台故障率的校准。
 * 使用随机故障时，应通过 {@code SimulationConfig} 和种子计划记录完整配置，避免
 * 跨运行复用有状态的生成器。</p>
 */
package org.workflowsim.failure;
