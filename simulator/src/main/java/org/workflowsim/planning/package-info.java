/**
 * 工作流的离线 Task-to-VM 静态映射与受控静态 DAG 规划策略。
 *
 * <p>{@code STATIC_*} 基线仅接受无依赖的独立任务集合，输出确定性的 VM 映射；它们不会
 * 变成 DAG 调度器或真实队列重放。{@code SHARED_STORAGE_*} 维护轨道在共享存储、无聚类、
 * 无故障、无开销和 {@code SPACE_SHARED} VM 的受控模型下，计算带保留区插入的静态 DAG
 * 计划，并提供可审计的决策轨迹。</p>
 *
 * <p>历史 {@code HEFT} 与 {@code DHEFT} 保留用于兼容性探索，不属于标准
 * {@code SimulationRunner} 的研究证据入口。</p>
 */
package org.workflowsim.planning;
