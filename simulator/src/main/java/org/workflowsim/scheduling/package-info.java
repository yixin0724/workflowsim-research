/**
 * 工作流运行时就绪 Job 的在线调度策略。
 *
 * <p>本包的调度器只观察当前事件时刻已就绪的 Job 与空闲 VM；一次调度更新中每台 VM
 * 至多接收一个 Job。维护轨道的 {@code FCFS}、{@code READY_BATCH_*} 和 {@code DATA}
 * 实现都使用确定性的 VM-ID 或 Job-ID 作为同分 tie-break，并在无法找到兼容 PE 数的 VM
 * 时快速失败。</p>
 *
 * <p>它们不是离线静态映射器：不会维护未来 VM 可用时间、生成完整执行计划或重放真实队列。
 * 历史标签保留在本包中仅为兼容既有调用；研究入口应使用具有明确语义契约的维护轨道标签。</p>
 */
package org.workflowsim.scheduling;
