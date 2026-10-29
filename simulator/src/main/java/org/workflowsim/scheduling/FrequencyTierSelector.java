package org.workflowsim.scheduling;

import java.util.List;

import org.workflowsim.Job;
import org.workflowsim.platform.PlatformProfile;

/**
 * BAT-D6（2026-10-29，docs/advanced/DVFS_DIMENSION_DESIGN.md §2.2 注册面）：逐 Job
 * 频档指派的<b>第一类调度时刻 API</b>。
 *
 * <p>设计契约：DVFS 的指派发生在派发时刻（assignment at admission，§1.4）——每个计算
 * Job 在获得 VM 指派之后、数据中心提交之前获得恰好一个频档。频档选择的缺省权属于
 * 会话级 {@code SimulationConfig.frequencyPolicy}（DEFAULT/MIN_POWER/EDP_HEURISTIC，
 * 全 Job 一致的斜切）；当<b>活动调度策略实例</b>实现本接口时，逐 Job 档选择权上移给
 * 算法——这是注册 DVFS-aware 算法（{@code AlgorithmDeclaration.dvfsAware}）表达
 * 异质档位策略的唯一通道，也是第一个真正使用逐 Job 档指派的消费面。</p>
 *
 * <p>调用时机与义务：</p>
 * <ul>
 *   <li>仅在派发<b>已获得频档 VM 指派</b>的计算 Job 时被 {@code WorkflowScheduler}
 *   调用（无档 VM、非计算 Job、v1 平台从不调用——v1 逐位不变）；</li>
 *   <li>返回的档<b>必须是候选表内的声明档</b>（按 mips 精确身份匹配，档按 mips 唯一）；
 *       返回 {@code null} 或表外档由调度器显式拒绝（fail-fast），不提供静默回退默认档；
 *   <li>决策必须只用派发时刻可观测状态（本 Job 声明工作、候选表、算法自身状态），
 *       保持确定性 tie-break（AGENTS #9）——这是把"证据链逐 Job tierMips"与算法
 *       行为对齐的义务，接口不验证，属算法作者的声明责任（与
 *       {@link org.workflowsim.experiment.registry.AlgorithmDeclaration} 的声明面同一纪律）。</li>
 * </ul>
 *
 * <p>档表序约定：候选表按 mips 严格降序，<b>首档 = 平台声明的默认档</b>（绝不隐式取
 * 最快）；长度换算（work/档速）仍由 {@code WorkflowScheduler.assignFrequencyTier}
 * 统一执行，实现方只做选择、不改写 Job 长度或台账。</p>
 */
public interface FrequencyTierSelector {

    /**
     * 为刚获得 VM 指派的计算 Job 选择唯一频档。
     *
     * @param job   待派发的计算 Job（提供声明的每 PE 计算 MI）
     * @param tiers 目标 VM 的候选频档表（mips 严格降序，首档=默认档）
     * @return 必须为 {@code tiers} 内的声明档；不合法返回值按证据链断裂显式拒绝
     */
    PlatformProfile.FrequencyTier selectFrequencyTier(Job job, List<PlatformProfile.FrequencyTier> tiers);
}
