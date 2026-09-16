package org.workflowsim.experiment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import org.apache.commons.math3.stat.inference.WilcoxonSignedRankTest;
import org.junit.jupiter.api.Test;

/**
 * {@link PairedWilcoxonSignificance} 的语义测试。
 *
 * <p>覆盖：可用性状态机（样本不足 / 全零差值 / 单非零差值 / 精确与近似两条计算路径）、
 * 显著性判定的方向性正确性、与 commons-math3 直接调用的一致性、以及输入校验。</p>
 */
class PairedWilcoxonSignificanceTest {

    @Test
    void identicalPairedSeriesAreUnavailableAllZero() {
        List<Double> same = Arrays.asList(Double.valueOf(3.0), Double.valueOf(5.5),
                Double.valueOf(7.25), Double.valueOf(9.0));
        PairedWilcoxonSignificance result = PairedWilcoxonSignificance.evaluate(same, same);

        assertEquals("UNAVAILABLE_ALL_PAIRED_DIFFERENCES_ARE_ZERO", result.getStatus());
        assertNull(result.getPValue());
        assertFalse(result.isSignificantAtDefaultAlpha());
        assertEquals(4, result.getPairedSampleCount());
        assertEquals(0, result.getEffectiveSampleCount());
        assertEquals(0.0, result.getMedianDifference().doubleValue(), 0.0);
    }

    @Test
    void fewerThanTwoPairedSamplesAreUnavailable() {
        PairedWilcoxonSignificance single = PairedWilcoxonSignificance.evaluate(
                Arrays.asList(Double.valueOf(1.0)), Arrays.asList(Double.valueOf(2.0)));
        assertEquals("UNAVAILABLE_FEWER_THAN_TWO_PAIRED_SAMPLES", single.getStatus());
        assertNull(single.getPValue());
        assertEquals(1, single.getPairedSampleCount());

        PairedWilcoxonSignificance empty = PairedWilcoxonSignificance.evaluate(
                java.util.Collections.<Double>emptyList(),
                java.util.Collections.<Double>emptyList());
        assertEquals("UNAVAILABLE_FEWER_THAN_TWO_PAIRED_SAMPLES", empty.getStatus());
    }

    @Test
    void singleNonzeroDifferenceIsUnavailable() {
        PairedWilcoxonSignificance result = PairedWilcoxonSignificance.evaluate(
                Arrays.asList(Double.valueOf(5.0), Double.valueOf(7.0)),
                Arrays.asList(Double.valueOf(5.0), Double.valueOf(9.0)));

        assertEquals("UNAVAILABLE_SINGLE_NONZERO_PAIRED_DIFFERENCE", result.getStatus());
        assertNull(result.getPValue());
        assertFalse(result.isSignificantAtDefaultAlpha());
        assertEquals(2, result.getPairedSampleCount());
        assertEquals(1, result.getEffectiveSampleCount());
    }

    @Test
    void consistentShiftIsSignificantWithExactDistribution() {
        // 候选一致快 20 秒：全部差值为负，精确分布下 p 必须很小。
        List<Double> baseline = new ArrayList<Double>();
        List<Double> candidate = new ArrayList<Double>();
        for (int index = 0; index < 20; index++) {
            baseline.add(Double.valueOf(100.0 + index));
            candidate.add(Double.valueOf(80.0 + index));
        }
        PairedWilcoxonSignificance result = PairedWilcoxonSignificance.evaluate(baseline, candidate);

        assertEquals("AVAILABLE_WILCOXON_SIGNED_RANK_EXACT", result.getStatus());
        assertNotNull(result.getPValue());
        assertTrue(result.getPValue().doubleValue() < 0.05);
        assertTrue(result.isSignificantAtDefaultAlpha());
        assertEquals(20, result.getEffectiveSampleCount());
        assertTrue(result.getMedianDifference().doubleValue() < 0.0,
                "差值中位数应为负（候选更快）");
    }

    @Test
    void alternatingSignSmallDifferencesAreNotSignificant() {
        // 差值 ±1 交替，符号接近对半：不应拒绝零假设。
        List<Double> baseline = new ArrayList<Double>();
        List<Double> candidate = new ArrayList<Double>();
        for (int index = 0; index < 12; index++) {
            baseline.add(Double.valueOf(50.0));
            candidate.add(Double.valueOf(50.0 + (index % 2 == 0 ? 1.0 : -1.0)));
        }
        PairedWilcoxonSignificance result = PairedWilcoxonSignificance.evaluate(baseline, candidate);

        assertEquals("AVAILABLE_WILCOXON_SIGNED_RANK_EXACT", result.getStatus());
        assertFalse(result.isSignificantAtDefaultAlpha());
        assertTrue(result.getPValue().doubleValue() > 0.05);
        // R8 审计回归（P1）：库的精确双侧 p 无上限截断，包装层必须钳制到 [0,1]。
        assertTrue(result.getPValue().doubleValue() <= 1.0, "p 值不得超过 1");
    }

    @Test
    void zeroDifferencesAreExcludedFromTheRankStatistic() {
        // 前 6 对为零差值、后 12 对一致快 20 秒：p 值必须与"仅非零子样本"
        // 直接调库完全一致（R8 审计修复 P1：此前全数组传入使零差值占据最低秩）。
        List<Double> baseline = new ArrayList<Double>();
        List<Double> candidate = new ArrayList<Double>();
        for (int index = 0; index < 6; index++) {
            baseline.add(Double.valueOf(100.0));
            candidate.add(Double.valueOf(100.0));
        }
        for (int index = 0; index < 12; index++) {
            baseline.add(Double.valueOf(100.0 + index));
            candidate.add(Double.valueOf(80.0 + index));
        }
        PairedWilcoxonSignificance result = PairedWilcoxonSignificance.evaluate(baseline, candidate);

        assertEquals("AVAILABLE_WILCOXON_SIGNED_RANK_EXACT", result.getStatus());
        assertEquals(18, result.getPairedSampleCount());
        assertEquals(12, result.getEffectiveSampleCount());
        double[] nonzeroX = new double[12];
        double[] nonzeroY = new double[12];
        for (int index = 0; index < 12; index++) {
            nonzeroX[index] = baseline.get(6 + index).doubleValue();
            nonzeroY[index] = candidate.get(6 + index).doubleValue();
        }
        double subset = new WilcoxonSignedRankTest().wilcoxonSignedRankTest(nonzeroX, nonzeroY, true);
        assertEquals(subset, result.getPValue().doubleValue(), 0.0,
                "p 值必须等于剔零子样本的直接检验结果");
    }

    @Test
    void moreThanThirtyPairsWithZeroDifferencesDoNotCrash() {
        // R8 审计回归（P1）：31 对、仅 20 个非零差值。修复前库按完整 N>30
        // 抛 NumberIsTooLargeException 击穿 campaign 汇总；修复后按非零数选
        // 精确路径（20 ≤ 25 < 30）安全出结果。
        List<Double> baseline = new ArrayList<Double>();
        List<Double> candidate = new ArrayList<Double>();
        for (int index = 0; index < 11; index++) {
            baseline.add(Double.valueOf(50.0));
            candidate.add(Double.valueOf(50.0));
        }
        for (int index = 0; index < 20; index++) {
            baseline.add(Double.valueOf(200.0 + index));
            candidate.add(Double.valueOf(190.0 + index));
        }
        PairedWilcoxonSignificance result = PairedWilcoxonSignificance.evaluate(baseline, candidate);

        assertEquals("AVAILABLE_WILCOXON_SIGNED_RANK_EXACT", result.getStatus());
        assertEquals(31, result.getPairedSampleCount());
        assertEquals(20, result.getEffectiveSampleCount());
        assertNotNull(result.getPValue());
        assertTrue(result.getPValue().doubleValue() < 0.05);
    }

    @Test
    void allPositiveExactPValueIsLockedToTwoOverTwoToTheN() {
        // 20 对差值全为正：W 取满总秩和，秩和≥W 的子集仅全集一个，
        // 精确 p = 2/2^20。数值锁定防公式回归（R8 审计测试强度补强）。
        List<Double> baseline = new ArrayList<Double>();
        List<Double> candidate = new ArrayList<Double>();
        for (int index = 0; index < 20; index++) {
            baseline.add(Double.valueOf(100.0 + index));
            candidate.add(Double.valueOf(110.0 + index));
        }
        PairedWilcoxonSignificance result = PairedWilcoxonSignificance.evaluate(baseline, candidate);

        assertEquals("AVAILABLE_WILCOXON_SIGNED_RANK_EXACT", result.getStatus());
        assertEquals(2.0 / (1L << 20), result.getPValue().doubleValue(), 0.0);
    }

    @Test
    void largeSamplesUseTheNormalApproximation() {
        // 有效非零差值 40 个 > 25，走正态近似路径。
        List<Double> baseline = new ArrayList<Double>();
        List<Double> candidate = new ArrayList<Double>();
        Random random = new Random(42L);
        for (int index = 0; index < 40; index++) {
            double base = 200.0 + random.nextDouble() * 50.0;
            baseline.add(Double.valueOf(base));
            candidate.add(Double.valueOf(base - 30.0 - random.nextDouble() * 10.0));
        }
        PairedWilcoxonSignificance result = PairedWilcoxonSignificance.evaluate(baseline, candidate);

        assertEquals("AVAILABLE_WILCOXON_SIGNED_RANK_NORMAL_APPROXIMATION", result.getStatus());
        assertTrue(result.getPValue().doubleValue() < 0.05);
        assertTrue(result.isSignificantAtDefaultAlpha());
        assertEquals(40, result.getEffectiveSampleCount());
    }

    @Test
    void pValueMatchesDirectCommonsMathInvocation() {
        List<Double> baseline = new ArrayList<Double>();
        List<Double> candidate = new ArrayList<Double>();
        Random random = new Random(20260911L);
        for (int index = 0; index < 18; index++) {
            double base = 100.0 + random.nextGaussian() * 10.0;
            baseline.add(Double.valueOf(base));
            candidate.add(Double.valueOf(base - 5.0 + random.nextGaussian() * 3.0));
        }
        PairedWilcoxonSignificance result = PairedWilcoxonSignificance.evaluate(baseline, candidate);

        double[] x = new double[18];
        double[] y = new double[18];
        for (int index = 0; index < 18; index++) {
            x[index] = baseline.get(index).doubleValue();
            y[index] = candidate.get(index).doubleValue();
        }
        double direct = new WilcoxonSignedRankTest().wilcoxonSignedRankTest(x, y, true);
        assertEquals(direct, result.getPValue().doubleValue(), 1.0e-12);
    }

    @Test
    void unpairedIndependentReplicationsHaveNoPairedTest() {
        PairedWilcoxonSignificance unavailable = PairedWilcoxonSignificance.unavailableUnpaired();
        assertEquals("UNAVAILABLE_INDEPENDENT_REPLICATIONS_ARE_NOT_PAIRED", unavailable.getStatus());
        assertNull(unavailable.getPValue());
        assertFalse(unavailable.isSignificantAtDefaultAlpha());
        assertEquals(0, unavailable.getPairedSampleCount());
    }

    @Test
    void invalidInputsAreRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> PairedWilcoxonSignificance.evaluate(null, Arrays.asList(Double.valueOf(1.0))));
        assertThrows(IllegalArgumentException.class,
                () -> PairedWilcoxonSignificance.evaluate(
                        Arrays.asList(Double.valueOf(1.0), Double.valueOf(2.0)),
                        Arrays.asList(Double.valueOf(1.0))));
        assertThrows(IllegalArgumentException.class,
                () -> PairedWilcoxonSignificance.evaluate(
                        Arrays.asList(Double.NaN, Double.valueOf(2.0)),
                        Arrays.asList(Double.valueOf(1.0), Double.valueOf(2.0))));
        assertThrows(IllegalArgumentException.class,
                () -> PairedWilcoxonSignificance.evaluate(
                        Arrays.asList(Double.valueOf(1.0), Double.valueOf(2.0)),
                        Arrays.asList(Double.valueOf(1.0), Double.POSITIVE_INFINITY)));
    }
}
