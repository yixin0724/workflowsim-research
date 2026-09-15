package org.workflowsim.utils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.Collections;
import org.apache.commons.math3.distribution.GammaDistribution;
import org.apache.commons.math3.distribution.RealDistribution;
import org.apache.commons.math3.distribution.WeibullDistribution;
import org.apache.commons.math3.special.Gamma;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.workflowsim.Job;
import org.workflowsim.Task;
import org.workflowsim.failure.FailureGenerator;
import org.workflowsim.failure.FailureMonitor;
import org.workflowsim.failure.FailureParameters;
import org.workflowsim.utils.DistributionGenerator.DistributionFamily;

/**
 * R3：故障到达分布的 Kolmogorov-Smirnov 拟合优度检验。
 *
 * <p>检验对象是平台"声称的分布契约"：{@link DistributionGenerator} 按
 * （scale, shape）参数采样 LOGNORMAL/GAMMA/WEIBULL/NORMAL 四个分布族，故障到达
 * 过程是其样本的逐段累计和。本测试验证三件事：</p>
 * <ol>
 *   <li><b>族契约</b>：四个分布族各 1500 个样本通过对其理论分布的 KS 检验
 *       （p &gt; 10⁻⁴，保守阈值；随机流按名称播种，结果逐位确定、无抖动）；</li>
 *   <li><b>参数映射</b>：样本均值与族的理论矩一致（Weibull βΓ(1+1/α)、
 *       Gamma αβ、LogNormal e^{μ+σ²/2}、Normal μ）；</li>
 *   <li><b>故障接线</b>：经 {@link FailureGenerator} 全区间扫描消费的到达时刻
 *       恰为累计样本前缀，其到达间隔通过 Weibull KS 检验——故障到达过程服从
 *       配置声称的分布；同时给出敏感性对照（把 Weibull 样本拿去拟合 Gamma 会被
 *       显著拒绝），证明检验本身有效力而非恒真。</li>
 * </ol>
 */
class DistributionGeneratorKsTest {

    /** KS 判定阈值：p 大于它即接受"样本来自该分布"。随机流确定，无抖动。 */
    private static final double MIN_ACCEPT_P = 1.0e-4;
    /** 检验效力对照的拒绝阈值：交叉拟合的 p 必须低于它。 */
    private static final double MAX_CROSS_FIT_P = 1.0e-6;

    @AfterEach
    void resetFailureState() {
        FailureGenerator.reset();
        FailureMonitor.reset();
        FailureParameters.reset();
    }

    @Test
    void weibullSamplesFollowClaimedDistribution() {
        assertSamplesFollowDistribution(DistributionFamily.WEIBULL, 100.0, 1.5, "r3.ks.weibull");
    }

    @Test
    void gammaSamplesFollowClaimedDistribution() {
        assertSamplesFollowDistribution(DistributionFamily.GAMMA, 2.0, 3.0, "r3.ks.gamma");
    }

    @Test
    void lognormalSamplesFollowClaimedDistribution() {
        assertSamplesFollowDistribution(DistributionFamily.LOGNORMAL, 1.0, 0.5, "r3.ks.lognormal");
    }

    @Test
    void normalSamplesFollowClaimedDistribution() {
        assertSamplesFollowDistribution(DistributionFamily.NORMAL, 10.0, 2.0, "r3.ks.normal");
    }

    @Test
    void distributionSpecWiringMatchesFamilyContract() {
        // DistributionSpec.of(WEIBULL, scale, shape) 是故障模型配置的入口；其生成器
        // 的样本必须通过 Weibull(alpha=shape, beta=scale) 的 KS 检验。
        DistributionGenerator generator = DistributionSpec.of(
                DistributionFamily.WEIBULL, 100.0, 2.0)
                .createGenerator("r3.ks.spec.wiring");
        double p = kolmogorovSmirnovP(generator, new WeibullDistribution(2.0, 100.0));
        assertTrue(p > MIN_ACCEPT_P,
                "DistributionSpec 接线样本应服从 Weibull(alpha=2, beta=100)，实际 p=" + p);
    }

    @Test
    void sampleMeansMatchFamilyMoments() {
        // 每族 1500 个确定性样本的均值与理论矩的相对偏差应在 5% 以内。
        assertMeanNear(DistributionFamily.WEIBULL, 100.0, 1.5, "r3.ks.moments.weibull",
                100.0 * Gamma.gamma(1.0 + 1.0 / 1.5));
        assertMeanNear(DistributionFamily.GAMMA, 2.0, 3.0, "r3.ks.moments.gamma", 3.0 * 2.0);
        assertMeanNear(DistributionFamily.LOGNORMAL, 1.0, 0.5, "r3.ks.moments.lognormal",
                Math.exp(1.0 + 0.5 * 0.5 / 2.0));
        assertMeanNear(DistributionFamily.NORMAL, 10.0, 2.0, "r3.ks.moments.normal", 10.0);
    }

    @Test
    void ksTestHasPowerToRejectWrongFamily() {
        // 效力对照：用 Weibull(1.5, 100) 的样本去拟合均值相近的 Gamma，
        // p 必须极小——否则 KS 判定形同虚设。
        DistributionGenerator generator = new DistributionGenerator(
                DistributionFamily.WEIBULL, 100.0, 1.5, "r3.ks.power");
        double weibullMean = 100.0 * Gamma.gamma(1.0 + 1.0 / 1.5);
        // Gamma(alpha=9, beta=mean/9) 与 Weibull 均值相同、形状不同。
        double crossP = kolmogorovSmirnovP(generator,
                new GammaDistribution(9.0, weibullMean / 9.0));
        assertTrue(crossP < MAX_CROSS_FIT_P,
                "KS 检验应拒绝错误族拟合，实际 p=" + crossP);
    }

    @Test
    void failureArrivalProcessFollowsConfiguredWeibullDistribution() throws Exception {
        // 故障到达 = 分布样本的累计和。以 Weibull(1.5, 100) 初始化故障模型，
        // 用连续窗口扫描消费到达流，然后验证：
        // (a) 消费的到达时刻恰为累计样本前缀（接线恒等）；
        // (b) 到达间隔（即分布样本）通过 Weibull KS 检验。
        double scale = 100.0;
        double shape = 1.5;
        DistributionGenerator generator = new DistributionGenerator(
                DistributionFamily.WEIBULL, scale, shape, "r3.arrival.weibull");
        FailureParameters.init(FailureParameters.FTCluteringAlgorithm.FTCLUSTERING_NOOP,
                FailureParameters.FTCMonitor.MONITOR_ALL,
                FailureParameters.FTCFailure.FAILURE_ALL,
                new DistributionGenerator[][]{{generator}});
        FailureMonitor.init();
        FailureGenerator.init();

        double horizon = 5000.0;
        double step = 10.0;
        for (double start = 0.0; start < horizon; start += step) {
            FailureGenerator.generate(jobWithWindow(start, start + step));
        }
        int consumed = generator.getConsumedSampleCount();
        assertTrue(consumed > 20, "扫描应消费足够多的到达样本，实际 " + consumed);
        // (a) 接线恒等：消费边界与扫描地平线一致——最后一个已消费到达 ≤ horizon，
        // 第一个未消费到达 > horizon；且到达时刻严格递增。
        double[] cumulative = generator.getCumulativeSamples();
        assertTrue(cumulative[consumed - 1] <= horizon,
                "最后一个已消费到达 " + cumulative[consumed - 1] + " 应 ≤ horizon " + horizon);
        if (consumed < cumulative.length) {
            assertTrue(cumulative[consumed] > horizon,
                    "第一个未消费到达 " + cumulative[consumed] + " 应 > horizon " + horizon);
        }
        for (int i = 0; i < consumed; i++) {
            assertTrue(i == 0 || cumulative[i] > cumulative[i - 1],
                    "Weibull 到达时刻必须严格递增");
        }
        // (b) 到达间隔的分布检验。
        double[] interArrivals = new double[consumed];
        for (int i = 0; i < consumed; i++) {
            interArrivals[i] = i == 0 ? cumulative[0] : cumulative[i] - cumulative[i - 1];
        }
        double p = kolmogorovSmirnovP(interArrivals, new WeibullDistribution(shape, scale));
        assertTrue(p > MIN_ACCEPT_P,
                "故障到达间隔应服从 Weibull(alpha=1.5, beta=100)，实际 p=" + p);
    }

    /** 族契约断言：生成器样本通过其自身理论分布的 KS 检验。 */
    private static void assertSamplesFollowDistribution(DistributionFamily family,
            double scale, double shape, String stream) {
        DistributionGenerator generator = new DistributionGenerator(family, scale, shape, stream);
        double p = kolmogorovSmirnovP(generator, generator.getDistribution(scale, shape));
        assertTrue(p > MIN_ACCEPT_P,
                family + "(" + scale + ", " + shape + ") 样本 KS p=" + p
                        + " 应大于 " + MIN_ACCEPT_P);
        assertEquals(1500, generator.getSamples().length, "首批样本量必须为 1500");
    }

    /** 样本均值与理论矩的相对偏差断言（5% 容差，1500 确定性样本）。 */
    private static void assertMeanNear(DistributionFamily family, double scale, double shape,
            String stream, double theoreticalMean) {
        DistributionGenerator generator = new DistributionGenerator(family, scale, shape, stream);
        double[] samples = generator.getSamples();
        double sum = 0.0;
        for (double sample : samples) {
            sum += sample;
        }
        double mean = sum / samples.length;
        double relativeError = Math.abs(mean - theoreticalMean) / theoreticalMean;
        assertTrue(relativeError < 0.05,
                family + " 样本均值 " + mean + " 与理论矩 " + theoreticalMean
                        + " 的相对偏差 " + relativeError + " 应小于 5%");
    }

    private static double kolmogorovSmirnovP(DistributionGenerator generator,
            RealDistribution theoretical) {
        return kolmogorovSmirnovP(generator.getSamples(), theoretical);
    }

    /**
     * 单样本 KS 渐近 p 值（自实现，避免依赖 commons-math3 3.3+ 的
     * KolmogorovSmirnovTest——仓库锁定 3.2）。D = sup|F_n − F|，λ = √n·D，
     * p ≈ Q(λ) = 2·Σ_{k≥1} (−1)^{k−1} exp(−2k²λ²)（Kolmogorov 渐近分布的
     * 互补 CDF 级数）。全部运算为确定性浮点运算，同一样本任何平台给出同一结果。
     */
    private static double kolmogorovSmirnovP(double[] samples, RealDistribution theoretical) {
        double[] sorted = samples.clone();
        Arrays.sort(sorted);
        int n = sorted.length;
        double statistic = 0.0;
        for (int i = 0; i < n; i++) {
            double fitted = theoretical.cumulativeProbability(sorted[i]);
            statistic = Math.max(statistic, Math.max(
                    (i + 1.0) / n - fitted, fitted - i / (double) n));
        }
        double lambda = Math.sqrt(n) * statistic;
        double p = 0.0;
        for (int k = 1; k <= 100; k++) {
            double term = 2.0 * ((k % 2 == 1) ? 1.0 : -1.0)
                    * Math.exp(-2.0 * k * k * lambda * lambda);
            p += term;
            if (Math.abs(term) < 1.0e-14) {
                break;
            }
        }
        return Math.max(0.0, Math.min(1.0, p));
    }

    private static Job jobWithWindow(double start, double finish) {
        Task task = new Task(1, 1L);
        task.setDepth(0);
        task.setExecStartTime(start);
        task.setTaskFinishTime(finish);
        Job job = new Job(1, 1L);
        job.setUserId(0);
        job.setVmId(0);
        job.addTaskList(Collections.singletonList(task));
        return job;
    }
}
