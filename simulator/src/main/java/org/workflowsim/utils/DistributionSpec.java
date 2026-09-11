package org.workflowsim.utils;

import org.workflowsim.utils.DistributionGenerator.DistributionFamily;

/**
 * 抽样分布的不可变、与随机种子无关的参数描述。
 *
 * <p>该对象只保存分布族和参数，不持有随机数发生器。每次调用
 * {@link #createGenerator(String)} 都会从指定的 {@link SimulationRandom} 流创建新的
 * 生成器，从而使配置与随机序列的生命周期分离。</p>
 */
public final class DistributionSpec {

    private final DistributionFamily family;
    private final double scale;
    private final double shape;
    private final Double priorShape;
    private final Double priorScale;
    private final Double likelihoodPrior;

    private DistributionSpec(DistributionFamily family, double scale, double shape,
            Double priorShape, Double priorScale, Double likelihoodPrior) {
        this.family = family;
        this.scale = scale;
        this.shape = shape;
        this.priorShape = priorShape;
        this.priorScale = priorScale;
        this.likelihoodPrior = likelihoodPrior;
    }

    /**
     * 创建不带先验参数的分布描述。
     *
     * @param family 分布族
     * @param scale 尺度参数
     * @param shape 形状参数
     * @return 已验证的分布描述
     */
    public static DistributionSpec of(DistributionFamily family, double scale, double shape) {
        validate(family, scale, shape, null, null, null);
        return new DistributionSpec(family, scale, shape, null, null, null);
    }

    /**
     * 创建带完整先验参数的分布描述。
     *
     * @param family 分布族
     * @param scale 尺度参数
     * @param shape 形状参数
     * @param priorShape 先验形状参数
     * @param priorScale 先验尺度参数
     * @param likelihoodPrior 似然先验参数
     * @return 已验证的分布描述
     */
    public static DistributionSpec withPriors(DistributionFamily family, double scale, double shape,
            double priorShape, double priorScale, double likelihoodPrior) {
        validate(family, scale, shape, priorShape, priorScale, likelihoodPrior);
        return new DistributionSpec(family, scale, shape,
                priorShape, priorScale, likelihoodPrior);
    }

    /** @return 分布族 */
    public DistributionFamily getFamily() { return family; }
    /** @return 尺度参数 */
    public double getScale() { return scale; }
    /** @return 形状参数 */
    public double getShape() { return shape; }
    /** @return 先验形状参数；未配置先验时为 {@code null} */
    public Double getPriorShape() { return priorShape; }
    /** @return 先验尺度参数；未配置先验时为 {@code null} */
    public Double getPriorScale() { return priorScale; }
    /** @return 似然先验参数；未配置先验时为 {@code null} */
    public Double getLikelihoodPrior() { return likelihoodPrior; }

    /**
     * 基于指定的命名随机流创建一个新的分布生成器。
     *
     * @param randomStream 稳定的随机组件名称
     * @return 与该随机流绑定的生成器
     */
    public DistributionGenerator createGenerator(String randomStream) {
        if (priorShape == null) {
            return new DistributionGenerator(family, scale, shape, randomStream);
        }
        return new DistributionGenerator(family, scale, shape,
                priorShape, priorScale, likelihoodPrior, randomStream);
    }

    private static void validate(DistributionFamily family, double scale, double shape,
            Double priorShape, Double priorScale, Double likelihoodPrior) {
        if (family == null || !isFinite(scale) || !isFinite(shape)) {
            throw new IllegalArgumentException("Distribution family and finite parameters are required");
        }
        if (shape <= 0.0 || (family != DistributionFamily.NORMAL && scale <= 0.0)) {
            throw new IllegalArgumentException("Distribution shape and non-normal scale must be positive");
        }
        boolean anyPrior = priorShape != null || priorScale != null || likelihoodPrior != null;
        boolean allPriors = priorShape != null && priorScale != null && likelihoodPrior != null;
        if (anyPrior != allPriors || (allPriors && (!isFinite(priorShape)
                || !isFinite(priorScale) || !isFinite(likelihoodPrior)))) {
            throw new IllegalArgumentException("Distribution priors must be supplied together and be finite");
        }
    }

    private static boolean isFinite(double value) {
        return !Double.isInfinite(value) && !Double.isNaN(value);
    }
}
