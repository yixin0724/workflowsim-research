package org.workflowsim.experiment.registry;

import java.util.List;

import org.workflowsim.Job;
import org.workflowsim.platform.PlatformProfile;
import org.workflowsim.scheduling.BaseSchedulingAlgorithm;
import org.workflowsim.scheduling.FCFSSchedulingAlgorithm;
import org.workflowsim.scheduling.FrequencyTierSelector;

/**
 * BAT-D6（2026-10-29）测试域破坏面之二：selector 返回表外档（mips 1500.0 不在
 * 声明表 2000/1000/500 内，数值恰好居间自欺性强）。档按 MIPS 唯一身份入证据链
 * （JobOutcome.tierMips → Python 审计 tierByMips 同条款），表外档必须显式拒绝，
 * 不允许"最近的声明档"式静默圆整。
 */
public final class SelectorUndeclaredTierDvfsTestSchedulerProvider implements SchedulingAlgorithmProvider {

    public static final String LABEL = "tier-selector-undeclared";

    @Override
    public String label() {
        return LABEL;
    }

    @Override
    public BaseSchedulingAlgorithm create() {
        return new SelectorUndeclaredSchedulingAlgorithm();
    }

    @Override
    public AlgorithmDeclaration declaration() {
        return new AlgorithmDeclaration.Builder()
                .dvfsAware(true)
                .frequencyTiersRequired(true)
                .description("BAT-D6 negative-face provider: selector returns an undeclared"
                        + " tier; must be rejected citing the per-Job tier evidence chain")
                .build();
    }

    static final class SelectorUndeclaredSchedulingAlgorithm extends FCFSSchedulingAlgorithm
            implements FrequencyTierSelector {

        /** 表外档：mips=1500（居声明档 2000/1000 之间），功率对构造合法但档身份未声明。 */
        private static final PlatformProfile.FrequencyTier UNDECLARED_TIER =
                new PlatformProfile.FrequencyTier(1500.0, 205.0, 300.0);

        @Override
        public PlatformProfile.FrequencyTier selectFrequencyTier(Job job, List<PlatformProfile.FrequencyTier> tiers) {
            return UNDECLARED_TIER;
        }
    }
}
