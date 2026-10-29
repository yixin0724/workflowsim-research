package org.workflowsim.experiments.registry;

import java.util.List;

import org.workflowsim.Job;
import org.workflowsim.experiment.registry.AlgorithmDeclaration;
import org.workflowsim.experiment.registry.SchedulingAlgorithmProvider;
import org.workflowsim.platform.PlatformProfile;
import org.workflowsim.scheduling.BaseSchedulingAlgorithm;
import org.workflowsim.scheduling.FCFSSchedulingAlgorithm;
import org.workflowsim.scheduling.FrequencyTierSelector;

/**
 * D6 demonstrator (2026-10-29): the platform's first <b>DVFS-aware</b> registered
 * scheduling label — the first SPI caller that actually uses per-Job tier assignment
 * through the scheduling-time {@link FrequencyTierSelector} API
 * (docs/advanced/DVFS_DIMENSION_DESIGN.md §2.2, registered-algorithm face).
 *
 * <p><b>Declaration — the full implication chain, deliberately:</b>
 * {@code dvfsAware(true) + frequencyTiersRequired(true) + powerModelRequired(true)}.
 * The D4 guidance note (AlgorithmDeclaration BAT-D4 javadoc) says a tier-requiring
 * algorithm need not ALSO declare the flat-power gate because the tier table replaces
 * the flat fields on the platform (one source of truth, BAT-D1). This demonstrator
 * declares all three on purpose — the registry acceptance surface for the whole chain
 * is the thing under test (D6): on a tiered platform the power gate is satisfied by
 * the tier table itself (BAT-D2 powered predicate {@code hasPowerModel() ||
 * hasFrequencyTiers()}), so the three flags gate exactly the three platform faces and
 * reject an unpowered or tierless profile with the clause spelled out. No platform
 * ever carries BOTH flat power and tiers, so the declaration cannot demand both —
 * it demands the one face each flag is about.</p>
 *
 * <p><b>Heuristic — length-based tier split (a demonstrator, NOT an optimizer):</b>
 * VM placement is inherited verbatim from FCFS (lowest-id idle compatible VM), so
 * against a plain FCFS arm the ONLY delta is the tier assignment. Per Job: declared
 * work ≥ {@link #LONG_JOB_THRESHOLD_MI} (the "longest tasks") runs at the LAST tier
 * (MIN_POWER's tier — low speed, low draw, per-MI the cheapest busy energy on the
 * frozen benchmark table); shorter jobs run at the FIRST tier (the declared default).
 * This is the simple two-pocket split the task pinned; a real slack-aware EDP engine
 * (the design doc §3 "exact EDP optimization" omission row) is follow-up work.</p>
 */
public final class EdpGreedySchedulerProvider implements SchedulingAlgorithmProvider {

    /** SPI label (workbench/manifest identity; unique per registry contract). */
    public static final String LABEL = "EDP_GREEDY";

    /**
     * Length-based split threshold: compute Jobs with declared work of at least this
     * many MI (60 s at the 1000-MIPS reference) are "long" and run at the last tier.
     * Frozen with the demonstrator so the tier mix is reproducible from the label.
     */
    public static final double LONG_JOB_THRESHOLD_MI = 60_000.0;

    @Override
    public String label() {
        return LABEL;
    }

    @Override
    public BaseSchedulingAlgorithm create() {
        return new EdpGreedySchedulingAlgorithm();
    }

    @Override
    public AlgorithmDeclaration declaration() {
        return new AlgorithmDeclaration.Builder()
                .fileSystemScope(AlgorithmDeclaration.FileSystemScope.ANY)
                .allowDataMovementKind("LEGACY_WORKFLOWSIM_V1")
                .acceptsTaskCostMatrix(false)
                .allowsFailureModel(false)
                .dvfsAware(true)
                .frequencyTiersRequired(true)
                .powerModelRequired(true)
                .description("D6 demonstrator: FCFS placement with a length-based per-Job tier"
                        + " split (long jobs to the lowest tier, short jobs to the default tier)"
                        + " through the scheduling-time tier API; requires a tiered powered"
                        + " profile; a slack-aware EDP engine is follow-up work")
                .build();
    }

    /**
     * The scheduling body: pure FCFS placement (the honest baseline delta) plus the
     * scheduling-time tier selection — the platform's first registered algorithm that
     * assigns a frequency tier per Job.
     */
    static final class EdpGreedySchedulingAlgorithm extends FCFSSchedulingAlgorithm implements FrequencyTierSelector {

        @Override
        public PlatformProfile.FrequencyTier selectFrequencyTier(Job job, List<PlatformProfile.FrequencyTier> tiers) {
            // 长度分裂（文档化的示范规则，勿当优化器阅读）：声明工作 ≥ 阈值 → 末档
            // （MIN_POWER 之档）；短任务 → 首档（平台声明的默认档）。档身份经调度器
            // 的声明档成员校验（BAT-D6），此处只返回表内档。
            long workMi = job.getEffectiveExecutionLengthMi();
            return workMi >= LONG_JOB_THRESHOLD_MI ? tiers.get(tiers.size() - 1) : tiers.get(0);
        }
    }
}
