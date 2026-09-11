/*
 * 
 *   Copyright 2013-2014 University Of Southern California
 * 
 *   Licensed under the Apache License, Version 2.0 (the "License");
 *   you may not use this file except in compliance with the License.
 *   You may obtain a copy of the License at
 * 
 *   http://www.apache.org/licenses/LICENSE-2.0
 * 
 *   Unless required by applicable law or agreed to in writing,
 *   software distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 * 
 */
package org.workflowsim.utils;

import java.util.Arrays;
import org.apache.commons.math3.distribution.GammaDistribution;
import org.apache.commons.math3.distribution.LogNormalDistribution;
import org.apache.commons.math3.distribution.NormalDistribution;
import org.apache.commons.math3.distribution.RealDistribution;
import org.apache.commons.math3.distribution.WeibullDistribution;
import org.apache.commons.math3.random.RandomGenerator;

/**
 * 为开销和故障模型提供预采样序列的概率分布生成器。
 *
 * <p>生成器从命名 {@link SimulationRandom} 流创建 Apache Commons Math 分布，并缓存
 * 样本及其累计和。缓存仅是性能实现，不改变抽样顺序；调用方应通过
 * {@link DistributionSpec} 或显式随机流名称避免不同模型组件意外共享随机序列。</p>
 *
 * @author Weiwei Chen
 * @since WorkflowSim Toolkit 1.0
 * @date Mar 11, 2014
 */
public class DistributionGenerator {

    protected DistributionFamily dist;
    protected double scale;
    protected double shape;
    protected double scale_prior;
    protected double shape_prior;
    protected double likelihood_prior;
    protected double[] samples;
    protected double[] cumulativeSamples;
    protected int cursor;
    /** 每次预采样或扩容的样本数量。 */
    protected final int SAMPLE_SIZE = 1500;
    private final RandomGenerator randomGenerator;
    private static long streamSequence;
    

    /** 支持的概率分布族。 */
    public enum DistributionFamily {

        LOGNORMAL, GAMMA, WEIBULL, NORMAL
    }

    /**
     * 创建使用自动分配默认随机流的生成器。
     *
     * <p>新实验代码应优先使用带 {@code randomStream} 的构造器，使随机流身份成为
     * 配置的一部分。</p>
     *
     * @param dist 分布族
     * @param scale 尺度参数
     * @param shape 形状参数
     */
    public DistributionGenerator(DistributionFamily dist, double scale, double shape) {
        this(dist, scale, shape, nextDefaultStream(dist));
    }

    /**
     * 创建具有显式随机流身份的生成器。
     *
     * <p>受配置管理的实验应使用该构造器，以免独立延迟类别只因共享分布族而意外相关。</p>
     *
     * @param dist 分布族
     * @param scale 尺度参数
     * @param shape 形状参数
     * @param randomStream 稳定的随机组件名称
     */
    public DistributionGenerator(DistributionFamily dist, double scale, double shape, String randomStream) {
        validateParameters(dist, scale, shape);
        if (randomStream == null || randomStream.trim().isEmpty()) {
            throw new IllegalArgumentException("Distribution random stream cannot be empty");
        }
        this.dist = dist;
        this.scale = scale;
        this.shape = shape;
        this.scale_prior = scale;
        this.shape_prior = shape;
        this.randomGenerator = SimulationRandom.newApacheRandom(randomStream);
        RealDistribution distribution = getDistribution(scale, shape);
        samples = distribution.sample(SAMPLE_SIZE);
        updateCumulativeSamples();
        cursor = 0;
    }

    /**
     * 创建使用自动默认随机流、带先验参数的生成器。
     *
     * @param dist 分布族
     * @param scale 尺度参数
     * @param shape 形状参数
     * @param a 先验形状参数
     * @param b 先验尺度参数
     * @param c 先验似然参数
     */
    public DistributionGenerator(DistributionFamily dist, double scale, double shape, double a, double b, double c) {
        this(dist, scale, shape, a, b, c, nextDefaultStream(dist));
    }

    /**
     * 创建带先验参数和显式随机流身份的生成器。
     *
     * @param dist 分布族
     * @param scale 尺度参数
     * @param shape 形状参数
     * @param a 先验形状参数
     * @param b 先验尺度参数
     * @param c 先验似然参数
     * @param randomStream 稳定的随机组件名称
     */
    public DistributionGenerator(DistributionFamily dist, double scale, double shape,
            double a, double b, double c, String randomStream) {
        this(dist, scale, shape, randomStream);
        this.scale_prior = b;
        this.shape_prior = a;
        this.likelihood_prior = c;
    }

    /** @return 当前预采样序列；调用方不得据此推断现实分布已被校准 */
    public double[] getSamples() {
        return samples;
    }

    /** @return 预采样序列的累计和 */
    public double[] getCumulativeSamples() {
        return cumulativeSamples;
    }

    /**
     * 返回已消费的原始样本数。
     *
     * <p>对于失效到达流，这一游标表示已经被过去时间窗跳过或命中的 inter-arrival
     * 样本数；对于开销模型，它表示已经发出的延迟样本数。</p>
     *
     * @return 已消费样本的前缀长度
     */
    public int getConsumedSampleCount() {
        return cursor;
    }

    /**
     * 单调地将样本消费游标推进到指定下标之后。
     *
     * <p>此方法用于累计到达流：当某个到达事件位于已过去的空闲期或当前执行窗口时，
     * 调用方必须消费该事件及其之前的所有 inter-arrival 样本，避免同一到达被后续
     * 任务重复解释。下标早于当前游标时保持幂等。</p>
     *
     * @param lastConsumedIndex 最后一个应被视为已消费的样本下标
     * @throws IllegalArgumentException 当下标不属于当前缓存时抛出
     */
    public void consumeSamplesThrough(int lastConsumedIndex) {
        if (lastConsumedIndex < 0 || lastConsumedIndex >= samples.length) {
            throw new IllegalArgumentException("Consumed sample index " + lastConsumedIndex
                    + " is outside the current sample cache");
        }
        cursor = Math.max(cursor, lastConsumedIndex + 1);
    }

    /** 使用相同分布和随机流扩展预采样序列。 */
    public void extendSamples() {
        double[] new_samples = getDistribution(scale, shape).sample(SAMPLE_SIZE);
        samples = concat(samples, new_samples);
        updateCumulativeSamples();
    }

    /** 根据当前样本序列重新计算累计和。 */
    public void updateCumulativeSamples() {
        cumulativeSamples = new double[samples.length];
        cumulativeSamples[0] = samples[0];
        for (int i = 1; i < samples.length; i++) {
            cumulativeSamples[i] = cumulativeSamples[i - 1] + samples[i];
        }
    }

    /**
     * 返回基于先验参数的估计值。
     *
     * @return 先验估计值
     */
    public double getPKEMean() {
        return this.shape_prior / this.scale_prior;
    }

    /**
     * 计算已消费样本的算术均值。
     *
     * @return 已消费样本均值
     * @throws IllegalStateException 当尚未消费任何样本时
     */
    public double getMean() {
        if (cursor == 0) {
            throw new IllegalStateException("No distribution samples have been consumed");
        }
        double sum = 0.0;
        for (int i = 0; i < cursor; i++) {
            sum += samples[i];
        }
        return sum / cursor;
    }

    /** @return 先验似然参数 */
    public double getLikelihoodPrior() {
        return this.likelihood_prior;
    }

    /**
     * 根据已消费样本和先验参数计算最大似然相关估计。
     *
     * <p>该公式只为历史故障/开销实验路径保留；它不是对任意分布族的通用估计器，
     * 对不支持的分布族返回 {@code 0.0}。</p>
     *
     * @return 估计延迟
     */
    public double getMLEMean() {
        double a = shape_prior, b = scale_prior;
        double sum = 0.0;

        for (int i = 0; i < cursor; i++) {
            switch (dist) {
                case GAMMA:
                    sum += samples[i];
                    break;
                case WEIBULL:
                    sum += Math.pow(samples[i], likelihood_prior);
                    break;
            }
        }
        double result = 0.0;
        switch (dist) {
            case GAMMA:
                result = (b + sum) / (a + cursor * likelihood_prior - 1);
                break;
            case WEIBULL:
                result = (b + sum) / (a + cursor + 1);
                break;
            default:
                break;
        }
        return result;
    }

    /**
     * 修改分布参数并重新抽样，保留先验参数不变。
     *
     * @param scale 新尺度参数
     * @param shape 新形状参数
     */
    public void varyDistribution(double scale, double shape) {
        validateParameters(dist, scale, shape);
        this.scale = scale;
        this.shape = shape;
        RealDistribution distribution = getDistribution(scale, shape);
        samples = distribution.sample(SAMPLE_SIZE);
        updateCumulativeSamples();
        // 保留已消费位置是历史行为；调用方需要重新开始时应创建新生成器。
    }

    /**
     * 拼接两个样本数组。
     *
     * @param first 前一段样本
     * @param second 后一段样本
     * @return 新的拼接数组
     */
    public double[] concat(double[] first, double[] second) {
        double[] result = Arrays.copyOf(first, first.length + second.length);
        System.arraycopy(second, 0, result, first.length, second.length);
        return result;
    }

    /**
     * 取得下一个样本，缓存耗尽时自动按相同分布扩容。
     *
     * @return 下一个抽样延迟
     */
    public double getNextSample() {
        while (cursor >= samples.length) {
            // 子类可通过 extendSamples() 保持自己的采样语义，例如周期性参数调制。
            extendSamples();
        }
        double delay = samples[cursor];
        cursor++;
        return delay;
    }

    /**
     * 按当前分布族和参数创建 Commons Math 分布实例。
     *
     * @param scale 尺度参数；正态分布中为均值
     * @param shape 形状参数；正态分布中为标准差
     * @return 随机流绑定的分布实例
     */
    public RealDistribution getDistribution(double scale, double shape) {
        validateParameters(dist, scale, shape);
        RealDistribution distribution = null;
        switch (this.dist) {
            case LOGNORMAL:
                distribution = new LogNormalDistribution(randomGenerator, scale, shape,
                        LogNormalDistribution.DEFAULT_INVERSE_ABSOLUTE_ACCURACY);
                break;
            case WEIBULL:
                distribution = new WeibullDistribution(randomGenerator, shape, scale,
                        WeibullDistribution.DEFAULT_INVERSE_ABSOLUTE_ACCURACY);
                break;
            case GAMMA:
                distribution = new GammaDistribution(randomGenerator, shape, scale,
                        GammaDistribution.DEFAULT_INVERSE_ABSOLUTE_ACCURACY);
                break;
            case NORMAL:
                // 正态分布的 scale 是均值，shape 是标准差。
                distribution = new NormalDistribution(randomGenerator, scale, shape,
                        NormalDistribution.DEFAULT_INVERSE_ABSOLUTE_ACCURACY);
                break;
            default:
                break;
        }
        return distribution;
    }
    
    /** @return 当前尺度参数 */
    public double getScale()
    {
        return this.scale;
    }

    /** @return 当前分布族 */
    public DistributionFamily getFamily() {
        return this.dist;
    }
    
    /** @return 当前形状参数 */
    public double getShape(){
        return this.shape;
    }

    /** 在安装新仿真配置时重置默认随机流编号分配。 */
    public static synchronized void resetStreamAllocation() {
        streamSequence = 0L;
    }

    private static synchronized String nextDefaultStream(DistributionFamily family) {
        if (family == null) {
            throw new IllegalArgumentException("Distribution family cannot be null");
        }
        return "distribution." + family.name() + "." + streamSequence++;
    }

    private static void validateParameters(DistributionFamily family, double scale, double shape) {
        if (family == null || Double.isNaN(scale) || Double.isInfinite(scale)
                || Double.isNaN(shape) || Double.isInfinite(shape)) {
            throw new IllegalArgumentException("Distribution family and finite parameters are required");
        }
        if (shape <= 0.0 || (family != DistributionFamily.NORMAL && scale <= 0.0)) {
            throw new IllegalArgumentException("Distribution shape and non-normal scale must be positive");
        }
    }
}
