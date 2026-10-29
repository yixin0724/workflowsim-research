package org.workflowsim.experiment.registry;

import java.util.List;

import org.workflowsim.Job;
import org.workflowsim.platform.PlatformProfile;
import org.workflowsim.scheduling.BaseSchedulingAlgorithm;
import org.workflowsim.scheduling.FCFSSchedulingAlgorithm;
import org.workflowsim.scheduling.FrequencyTierSelector;

/**
 * BAT-D6（2026-10-29）测试域破坏面之一：selector 返回 {@code null}——派发时刻
 * 指派必须是"恰好一个声明档"，空指派按证据链断裂显式拒绝（fail-fast），不允许
 * 静默回退默认档。该提供者永不产出有效证据，仅供拒绝测试消费。
 */
public final class SelectorNullTierDvfsTestSchedulerProvider implements SchedulingAlgorithmProvider {

    public static final String LABEL = "tier-selector-null";

    @Override
    public String label() {
        return LABEL;
    }

    @Override
    public BaseSchedulingAlgorithm create() {
        return new SelectorNullSchedulingAlgorithm();
    }

    @Override
    public AlgorithmDeclaration declaration() {
        return new AlgorithmDeclaration.Builder()
                .dvfsAware(true)
                .frequencyTiersRequired(true)
                .description("BAT-D6 negative-face provider: selector returns null; must be"
                        + " rejected fail-fast, never silently fallen back to the default tier")
                .build();
    }

    static final class SelectorNullSchedulingAlgorithm extends FCFSSchedulingAlgorithm
            implements FrequencyTierSelector {

        @Override
        public PlatformProfile.FrequencyTier selectFrequencyTier(Job job, List<PlatformProfile.FrequencyTier> tiers) {
            return null;
        }
    }
}
