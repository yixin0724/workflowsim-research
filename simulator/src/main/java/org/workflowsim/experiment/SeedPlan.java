package org.workflowsim.experiment;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** 一个实验 cell 使用的不可变、确定性根种子序列。 */
public final class SeedPlan {

    private static final long GOLDEN_GAMMA = 0x9e3779b97f4a7c15L;

    private final RandomizationDesign randomizationDesign;
    private final Long derivationRootSeed;
    private final List<Long> seeds;

    private SeedPlan(RandomizationDesign randomizationDesign, Long derivationRootSeed,
            List<Long> seeds) {
        if (randomizationDesign == null || seeds == null || seeds.isEmpty()) {
            throw new IllegalArgumentException("Randomization design and at least one seed are required");
        }
        if (randomizationDesign == RandomizationDesign.DETERMINISTIC && seeds.size() != 1) {
            throw new IllegalArgumentException("A deterministic seed plan must contain exactly one seed");
        }
        Set<Long> uniqueSeeds = new HashSet<Long>(seeds);
        if (uniqueSeeds.size() != seeds.size()) {
            throw new IllegalArgumentException("A seed plan must not repeat a root seed");
        }
        this.randomizationDesign = randomizationDesign;
        this.derivationRootSeed = derivationRootSeed;
        this.seeds = Collections.unmodifiableList(new ArrayList<Long>(seeds));
    }

    /**
     * 创建只包含一个根种子的确定性运行计划。
     *
     * @param seed 本次确定性运行使用的根种子
     * @return 恰有一次重复的确定性种子计划
     */
    public static SeedPlan deterministic(long seed) {
        return new SeedPlan(RandomizationDesign.DETERMINISTIC, Long.valueOf(seed),
                Collections.singletonList(Long.valueOf(seed)));
    }

    /**
     * 使用 SplitMix64 混合函数从显式根种子确定性派生多个种子。
     *
     * <p>这是可复现的种子派生，不是一次随机抽样。</p>
     *
     * @param design 计划采用的随机化解释
     * @param rootSeed 用于派生的根种子
     * @param replicationCount 需要派生的重复次数，必须为正
     * @return 包含派生种子序列的不可变计划
     * @throws IllegalArgumentException 当设计为空、重复次数非正，或确定性设计重复次数不为一时抛出
     */
    public static SeedPlan derived(RandomizationDesign design, long rootSeed, int replicationCount) {
        if (design == null || replicationCount <= 0) {
            throw new IllegalArgumentException("Randomization design and positive replication count are required");
        }
        if (design == RandomizationDesign.DETERMINISTIC && replicationCount != 1) {
            throw new IllegalArgumentException("A deterministic seed plan requires exactly one replication");
        }
        List<Long> values = new ArrayList<Long>(replicationCount);
        long state = rootSeed;
        for (int index = 0; index < replicationCount; index++) {
            state += GOLDEN_GAMMA;
            values.add(Long.valueOf(mix64(state)));
        }
        return new SeedPlan(design, Long.valueOf(rootSeed), values);
    }

    /**
     * 使用调用方完整指定的根种子序列创建计划。
     *
     * @param design 计划采用的随机化解释
     * @param seeds 不可重复且至少包含一个元素的根种子序列
     * @return 不记录派生根的不可变显式种子计划
     * @throws IllegalArgumentException 当设计或种子序列不满足计划约束时抛出
     */
    public static SeedPlan explicit(RandomizationDesign design, List<Long> seeds) {
        return new SeedPlan(design, null, seeds);
    }

    private static long mix64(long value) {
        long mixed = (value ^ (value >>> 30)) * 0xbf58476d1ce4e5b9L;
        mixed = (mixed ^ (mixed >>> 27)) * 0x94d049bb133111ebL;
        return mixed ^ (mixed >>> 31);
    }

    public RandomizationDesign getRandomizationDesign() { return randomizationDesign; }
    public Long getDerivationRootSeed() { return derivationRootSeed; }
    public List<Long> getSeeds() { return seeds; }
    public int getReplicationCount() { return seeds.size(); }
}
