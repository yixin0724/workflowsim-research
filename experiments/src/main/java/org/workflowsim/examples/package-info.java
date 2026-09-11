/**
 * 教学与兼容性示例入口。
 *
 * <p>除 {@code wfcommons} 子包中明确使用 {@code SimulationConfig}、
 * {@code PlatformProfile} 与 {@code SimulationRunner} 的示例外，本包多数类保留了
 * WorkflowSim 的历史静态 API，用于理解旧版 CloudSim 初始化、工作流装配和兼容性行为。
 * 它们不是标准研究实验入口；正式实验应使用 {@code org.workflowsim.experiment} 中的
 * 不可变配置、实验计划和可审计工件链。</p>
 */
package org.workflowsim.examples;
