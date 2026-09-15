package org.workflowsim.rl;

/**
 * R4 RL 轨道的策略契约：在每次调度决策时刻为每个就绪 Job 选择一个 VM。
 *
 * <p>这是平台与外部学习算法之间的最小接口。调度器在每次 ready-batch 更新时
 * 构造 {@link RlObservation} 并调用 {@link #selectVms(RlObservation)}；返回值
 * 与观测中的就绪 Job 一一对应，取值是观测 VM 列表的下标。</p>
 *
 * <p><b>契约</b>：</p>
 * <ul>
 *   <li>返回数组长度必须等于 {@code observation.getReadyJobs().size()}，
 *       否则调度器以 {@code IllegalStateException} 中止仿真；</li>
 *   <li>合法取值为 {@code [0, observation.getVms().size())} 或
 *       {@link #NO_ASSIGNMENT}；越界值中止仿真（策略实现缺陷必须显式失败）；</li>
 *   <li>选中 VM 不空闲、PE 不兼容或本次更新内已被占用时，调度器跳过该 Job
 *       （保持就绪，下次更新重新决策）——探索性策略不会因此崩溃，但策略有
 *       义务最终给出可执行动作，停滞由平台看门狗显式拦截；</li>
 *   <li>实现必须对同一观测给出同一动作（确定性），保证 episode 逐位可复现；
 *       需要随机性时使用 {@code SimulationRandom.newApacheRandom(stream)}
 *       的名称播种随机流。</li>
 * </ul>
 */
public interface RlPolicy {

    /** 动作取值：本轮不分配该 Job（保持就绪，等待下次决策）。 */
    int NO_ASSIGNMENT = -1;

    /**
     * 为观测中的每个就绪 Job 选择 VM。
     *
     * @param observation 决策时刻的状态快照
     * @return 与就绪 Job 一一对应的 VM 下标数组（取值见类契约）
     */
    int[] selectVms(RlObservation observation);
}
