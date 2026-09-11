/**
 * WorkflowSim 的核心工作流执行模型。
 *
 * <p>本包定义逻辑任务（{@link org.workflowsim.Task}）、聚类后的作业
 * （{@link org.workflowsim.Job}）、文件依赖、DAG 校验、输入解析、规划器、调度器、
 * 数据中心与工作流引擎之间的事件生命周期。它既保留原始 WorkflowSim/CloudSim 的
 * 兼容接口，也被 {@code org.workflowsim.experiment} 的标准研究入口调用。</p>
 *
 * <p>本包描述的是抽象离散事件模型，不代表真实云平台、网络拓扑、存储争用或原始
 * WfInstances 执行轨迹的重放。需要可审计的研究运行时，应通过
 * {@code SimulationConfig}、{@code PlatformProfile} 和 {@code SimulationRunner}
 * 建立受控会话，而不是直接修改遗留静态参数。</p>
 */
package org.workflowsim;
