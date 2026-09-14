package org.workflowsim.experiment;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.apache.commons.math3.stat.inference.WilcoxonSignedRankTest;

/**
 * 对按根种子配对的两次仿真运行序列做 Wilcoxon 符号秩显著性检验。
 *
 * <p>这是 campaign 配对比对的推断层：给定候选方案与基线方案在相同根种子下的
 * 指标观测（如 makespan），检验配对差值的分布是否以零为中心。检验是非参数的，
 * 不假设正态性，适合仿真输出常见的偏态分布。</p>
 *
 * <p><b>推断边界（如实声明）</b>：平台的随机模型当前不是事件键控模型，复用根种子
 * 不构成严格的 common-random-numbers 配对（见 {@link RandomizationDesign}）。因此本检验
 * 结论应读作"共享根种子的配对序列之间的分布差异"，而非 CRN 配对下的方差缩减推断。
 * 状态字符串与 {@link ExperimentCampaignSummary.ComparisonSummary#getInferenceStatus()}
 * 一并记录，保证证据链中推断主张的口径可追溯。</p>
 *
 * <p><b>实现口径</b>：</p>
 * <ul>
 * <li>配对按输入下标对齐（调用方负责按相同种子顺序提供两个等长序列）；</li>
 * <li>差值为零的配对不参与秩统计（Wilcoxon 标准做法），有效样本数相应减少；</li>
 * <li>有效非零差值数 ≤ 25 时用精确分布，否则用正态近似（含连续性校正，commons-math3 默认）；</li>
 * <li>全部差值为零（如确定性配置）时不做检验，返回不可用状态而非伪造 p 值。</li>
 * </ul>
 *
 * <p>本类不可变，所有集合均为防御性拷贝。</p>
 *
 * @since R1（多 seed 统计框架）
 */
public final class PairedWilcoxonSignificance {

    /** 默认的显著性水平。 */
    public static final double DEFAULT_ALPHA = 0.05;

    /** 精确分布适用的最大有效非零差值样本数。 */
    private static final int MAX_EXACT_EFFECTIVE_SAMPLES = 25;

    private final String status;
    private final Double pValue;
    private final boolean significantAtDefaultAlpha;
    private final int pairedSampleCount;
    private final int effectiveSampleCount;
    private final Double medianDifference;

    private PairedWilcoxonSignificance(String status, Double pValue, boolean significant,
            int pairedSampleCount, int effectiveSampleCount, Double medianDifference) {
        this.status = status;
        this.pValue = pValue;
        this.significantAtDefaultAlpha = significant;
        this.pairedSampleCount = pairedSampleCount;
        this.effectiveSampleCount = effectiveSampleCount;
        this.medianDifference = medianDifference;
    }

    /**
     * 构造"独立重复设计不适用配对检验"的不可用结果。
     *
     * <p>用于 campaign 中 {@link RandomizationDesign#INDEPENDENT_REPLICATIONS} 的比对：
     * 两侧种子互不相同，不存在配对差值。</p>
     *
     * @return 状态为 {@code UNAVAILABLE_INDEPENDENT_REPLICATIONS_ARE_NOT_PAIRED} 的不可变结果
     */
    public static PairedWilcoxonSignificance unavailableUnpaired() {
        return new PairedWilcoxonSignificance(
                "UNAVAILABLE_INDEPENDENT_REPLICATIONS_ARE_NOT_PAIRED", null, false, 0, 0, null);
    }

    /**
     * 检验候选方案减去基线方案的配对差值分布是否以零为中心。
     *
     * @param baseline 基线方案按种子顺序的指标观测，全部有限
     * @param candidate 候选方案按相同种子顺序的指标观测，全部有限
     * @return 携带状态、p 值与默认显著性判定的不可变结果
     * @throws IllegalArgumentException 当任一序列为空、长度不一致或含非有限值时抛出
     */
    public static PairedWilcoxonSignificance evaluate(List<Double> baseline, List<Double> candidate) {
        validateSamples(baseline, candidate);
        int pairedCount = baseline.size();
        if (pairedCount < 2) {
            return new PairedWilcoxonSignificance(
                    "UNAVAILABLE_FEWER_THAN_TWO_PAIRED_SAMPLES", null, false,
                    pairedCount, 0, null);
        }
        List<Double> differences = new ArrayList<Double>(pairedCount);
        for (int index = 0; index < pairedCount; index++) {
            differences.add(Double.valueOf(
                    candidate.get(index).doubleValue() - baseline.get(index).doubleValue()));
        }
        double medianDifference = median(differences);
        List<Double> nonzero = new ArrayList<Double>(pairedCount);
        for (Double difference : differences) {
            if (difference.doubleValue() != 0.0) {
                nonzero.add(difference);
            }
        }
        if (nonzero.isEmpty()) {
            return new PairedWilcoxonSignificance(
                    "UNAVAILABLE_ALL_PAIRED_DIFFERENCES_ARE_ZERO", null, false,
                    pairedCount, 0, Double.valueOf(medianDifference));
        }
        if (nonzero.size() == 1) {
            return new PairedWilcoxonSignificance(
                    "UNAVAILABLE_SINGLE_NONZERO_PAIRED_DIFFERENCE", null, false,
                    pairedCount, 1, Double.valueOf(medianDifference));
        }
        double[] pairedBaseline = toPrimitiveArray(baseline);
        double[] pairedCandidate = toPrimitiveArray(candidate);
        WilcoxonSignedRankTest test = new WilcoxonSignedRankTest();
        boolean exact = nonzero.size() <= MAX_EXACT_EFFECTIVE_SAMPLES;
        double pValue = test.wilcoxonSignedRankTest(pairedBaseline, pairedCandidate, exact);
        return new PairedWilcoxonSignificance(
                exact ? "AVAILABLE_WILCOXON_SIGNED_RANK_EXACT"
                        : "AVAILABLE_WILCOXON_SIGNED_RANK_NORMAL_APPROXIMATION",
                Double.valueOf(pValue), pValue < DEFAULT_ALPHA,
                pairedCount, nonzero.size(), Double.valueOf(medianDifference));
    }

    private static void validateSamples(List<Double> baseline, List<Double> candidate) {
        if (baseline == null || candidate == null) {
            throw new IllegalArgumentException("Both paired sample series are required");
        }
        if (baseline.size() != candidate.size()) {
            throw new IllegalArgumentException("Paired sample series must have identical length: "
                    + baseline.size() + " vs " + candidate.size());
        }
        for (Double value : baseline) {
            requireFinite(value);
        }
        for (Double value : candidate) {
            requireFinite(value);
        }
    }

    private static void requireFinite(Double value) {
        if (value == null || Double.isNaN(value.doubleValue())
                || Double.isInfinite(value.doubleValue())) {
            throw new IllegalArgumentException("Paired samples must be finite");
        }
    }

    private static double[] toPrimitiveArray(List<Double> values) {
        double[] array = new double[values.size()];
        for (int index = 0; index < array.length; index++) {
            array[index] = values.get(index).doubleValue();
        }
        return array;
    }

    private static double median(List<Double> values) {
        List<Double> sorted = new ArrayList<Double>(values);
        Collections.sort(sorted);
        int size = sorted.size();
        if (size % 2 == 1) {
            return sorted.get(size / 2).doubleValue();
        }
        return (sorted.get(size / 2 - 1).doubleValue() + sorted.get(size / 2).doubleValue()) / 2.0;
    }

    /**
     * @return 机器可读的检验状态：
     * {@code AVAILABLE_WILCOXON_SIGNED_RANK_EXACT}、
     * {@code AVAILABLE_WILCOXON_SIGNED_RANK_NORMAL_APPROXIMATION}、
     * {@code UNAVAILABLE_FEWER_THAN_TWO_PAIRED_SAMPLES}、
     * {@code UNAVAILABLE_SINGLE_NONZERO_PAIRED_DIFFERENCE} 或
     * {@code UNAVAILABLE_ALL_PAIRED_DIFFERENCES_ARE_ZERO}
     */
    public String getStatus() { return status; }

    /** @return 双侧 p 值；不可用状态时为 {@code null} */
    public Double getPValue() { return pValue; }

    /** @return 在 {@link #DEFAULT_ALPHA} 下是否拒绝零假设 */
    public boolean isSignificantAtDefaultAlpha() { return significantAtDefaultAlpha; }

    /** @return 按种子对齐的配对总数 */
    public int getPairedSampleCount() { return pairedSampleCount; }

    /** @return 参与秩统计的非零差值数 */
    public int getEffectiveSampleCount() { return effectiveSampleCount; }

    /** @return 配对差值（候选减基线）的中位数；配对数不足时为 {@code null} */
    public Double getMedianDifference() { return medianDifference; }

    /** @return 本检验使用的默认显著性水平 */
    public double getAlpha() { return DEFAULT_ALPHA; }
}
