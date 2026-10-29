package org.workflowsim.experiment.registry;

import java.util.List;

import org.workflowsim.Job;
import org.workflowsim.platform.PlatformProfile;
import org.workflowsim.scheduling.BaseSchedulingAlgorithm;
import org.workflowsim.scheduling.FCFSSchedulingAlgorithm;
import org.workflowsim.scheduling.FrequencyTierSelector;

/**
 * BAT-D6（2026-10-29）测试域注册提供者：逐 Job 频档指派 API
 * {@link FrequencyTierSelector} 的合法消费面——每 Job 固定取表<b>末档</b>（与
 * MIN_POWER 同档但来源不同：算法侧逐 Job 决策，而非会话级策略）。
 *
 * <p>测试用它钉死优先级：同一运行会话策略保持 DEFAULT（首档），若活动策略实例实现
 * selector，则 JobOutcome.tierMips 必须是末档而非首档——证明"算法侧逐 Job 档选择
 * 上移"真实生效，而不是复述会话策略。VM 放置刻意继承 FCFS（最低编号空闲兼容 VM），
 * 使其与内置 FCFS 对照臂只差频档决策一轴。</p>
 */
public final class SelectorTierDvfsTestSchedulerProvider implements SchedulingAlgorithmProvider {

    public static final String LABEL = "tier-selector-last";

    @Override
    public String label() {
        return LABEL;
    }

    @Override
    public BaseSchedulingAlgorithm create() {
        return new SelectorLastSchedulingAlgorithm();
    }

    @Override
    public AlgorithmDeclaration declaration() {
        return new AlgorithmDeclaration.Builder()
                .dvfsAware(true)
                .frequencyTiersRequired(true)
                .description("BAT-D6 test provider: per-Job selector always picking the last"
                        + " (lowest-mips) tier; exercises the scheduling-time tier API")
                .build();
    }

    /** FCFS 放置 + 逐 Job 恒取末档的 selector。 */
    static final class SelectorLastSchedulingAlgorithm extends FCFSSchedulingAlgorithm
            implements FrequencyTierSelector {

        @Override
        public PlatformProfile.FrequencyTier selectFrequencyTier(Job job, List<PlatformProfile.FrequencyTier> tiers) {
            return tiers.get(tiers.size() - 1);
        }
    }
}
