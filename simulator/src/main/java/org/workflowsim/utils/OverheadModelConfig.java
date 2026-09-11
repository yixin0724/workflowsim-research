package org.workflowsim.utils;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;

/**
 * 在会话内实例化抽样延迟的不可变开销模型。
 *
 * <p>四个延迟映射均以任务深度为键；键 {@code 0} 保留历史语义，表示未显式配置深度时
 * 使用的默认延迟。此配置不持有可变抽样状态，真正的生成器在已设置根种子的会话中创建。</p>
 */
public final class OverheadModelConfig {

    private static final OverheadModelConfig NONE = new OverheadModelConfig(
            0, 0.0, Collections.<Integer, DistributionSpec>emptyMap(),
            Collections.<Integer, DistributionSpec>emptyMap(),
            Collections.<Integer, DistributionSpec>emptyMap(),
            Collections.<Integer, DistributionSpec>emptyMap());

    private final int workflowEngineDelayInterval;
    private final double bandwidth;
    private final Map<Integer, DistributionSpec> workflowEngineDelays;
    private final Map<Integer, DistributionSpec> queueDelays;
    private final Map<Integer, DistributionSpec> postDelays;
    private final Map<Integer, DistributionSpec> clusteringDelays;

    private OverheadModelConfig(int workflowEngineDelayInterval, double bandwidth,
            Map<Integer, DistributionSpec> workflowEngineDelays,
            Map<Integer, DistributionSpec> queueDelays,
            Map<Integer, DistributionSpec> postDelays,
            Map<Integer, DistributionSpec> clusteringDelays) {
        this.workflowEngineDelayInterval = workflowEngineDelayInterval;
        this.bandwidth = bandwidth;
        this.workflowEngineDelays = frozenCopy(workflowEngineDelays);
        this.queueDelays = frozenCopy(queueDelays);
        this.postDelays = frozenCopy(postDelays);
        this.clusteringDelays = frozenCopy(clusteringDelays);
    }

    /** @return 不引入任何工作流开销的共享不可变模型 */
    public static OverheadModelConfig none() {
        return NONE;
    }

    /** @return 新的开销模型构建器 */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * 面向早期 {@link SimulationConfig} 调用方的兼容桥。
     *
     * <p>该方法只提取旧生成器的分布族和参数，并在会话中按配置的根种子重新抽样，避免
     * 将既有可变随机状态泄漏进新的实验会话。</p>
     *
     * @param parameters 旧式开销参数
     * @return 等价的不可变开销模型
     */
    public static OverheadModelConfig fromLegacy(OverheadParameters parameters) {
        if (parameters == null) {
            throw new IllegalArgumentException("Overhead parameters cannot be null");
        }
        return builder()
                .workflowEngineDelayInterval(parameters.getWEDInterval())
                .bandwidth(parameters.getBandwidth())
                .workflowEngineDelays(copyLegacy(parameters.getWEDDelay()))
                .queueDelays(copyLegacy(parameters.getQueueDelay()))
                .postDelays(copyLegacy(parameters.getPostDelay()))
                .clusteringDelays(copyLegacy(parameters.getClustDelay()))
                .build();
    }

    /** @return 工作流引擎处理延迟的采样间隔 */
    public int getWorkflowEngineDelayInterval() { return workflowEngineDelayInterval; }
    /** @return 旧开销模型使用的逻辑带宽 */
    public double getBandwidth() { return bandwidth; }
    /** @return 以任务深度为键的工作流引擎延迟分布 */
    public Map<Integer, DistributionSpec> getWorkflowEngineDelays() { return workflowEngineDelays; }
    /** @return 以任务深度为键的队列延迟分布 */
    public Map<Integer, DistributionSpec> getQueueDelays() { return queueDelays; }
    /** @return 以任务深度为键的后处理延迟分布 */
    public Map<Integer, DistributionSpec> getPostDelays() { return postDelays; }
    /** @return 以任务深度为键的聚类延迟分布 */
    public Map<Integer, DistributionSpec> getClusteringDelays() { return clusteringDelays; }

    /**
     * 为已设定随机种子的会话创建新的、有状态的旧式开销参数。
     *
     * @return 供历史执行路径使用的开销参数
     */
    public OverheadParameters createParameters() {
        return new OverheadParameters(workflowEngineDelayInterval,
                createGenerators(workflowEngineDelays, "overhead.workflow-engine"),
                createGenerators(queueDelays, "overhead.queue"),
                createGenerators(postDelays, "overhead.post"),
                createGenerators(clusteringDelays, "overhead.clustering"), bandwidth);
    }

    private static Map<Integer, DistributionSpec> copyLegacy(Map<Integer, DistributionGenerator> source) {
        if (source == null || source.isEmpty()) {
            return Collections.emptyMap();
        }
        Map<Integer, DistributionSpec> result = new LinkedHashMap<>();
        for (Map.Entry<Integer, DistributionGenerator> entry : source.entrySet()) {
            DistributionGenerator generator = entry.getValue();
            if (generator == null) {
                throw new IllegalArgumentException("Legacy overhead generator for depth "
                        + entry.getKey() + " is null");
            }
            result.put(entry.getKey(), DistributionSpec.of(generator.getFamily(),
                    generator.getScale(), generator.getShape()));
        }
        return result;
    }

    private static Map<Integer, DistributionGenerator> createGenerators(
            Map<Integer, DistributionSpec> specs, String prefix) {
        if (specs.isEmpty()) {
            return null;
        }
        Map<Integer, DistributionGenerator> result = new LinkedHashMap<>();
        for (Map.Entry<Integer, DistributionSpec> entry : specs.entrySet()) {
            result.put(entry.getKey(), entry.getValue().createGenerator(
                    prefix + ".depth" + entry.getKey()));
        }
        return result;
    }

    private static Map<Integer, DistributionSpec> frozenCopy(Map<Integer, DistributionSpec> source) {
        validateDelayMap(source);
        return Collections.unmodifiableMap(new LinkedHashMap<>(new TreeMap<>(source)));
    }

    private static void validateDelayMap(Map<Integer, DistributionSpec> values) {
        if (values == null) {
            throw new IllegalArgumentException("Overhead delay map cannot be null");
        }
        for (Map.Entry<Integer, DistributionSpec> entry : values.entrySet()) {
            if (entry.getKey() == null || entry.getKey() < 0 || entry.getValue() == null) {
                throw new IllegalArgumentException("Overhead delays require non-negative depths and non-null specs");
            }
            if (entry.getValue().getFamily() == DistributionGenerator.DistributionFamily.NORMAL) {
                throw new IllegalArgumentException("Overhead delays cannot use NORMAL because it can generate "
                        + "negative simulated time; use WEIBULL, GAMMA, or LOGNORMAL");
            }
        }
    }

    /**
     * 可确定性重建、可记录到实验清单的开销模型构建器。
     */
    public static final class Builder {

        private int workflowEngineDelayInterval;
        private double bandwidth;
        private Map<Integer, DistributionSpec> workflowEngineDelays = Collections.emptyMap();
        private Map<Integer, DistributionSpec> queueDelays = Collections.emptyMap();
        private Map<Integer, DistributionSpec> postDelays = Collections.emptyMap();
        private Map<Integer, DistributionSpec> clusteringDelays = Collections.emptyMap();

        /** @param value 工作流引擎延迟采样间隔 @return 当前构建器 */
        public Builder workflowEngineDelayInterval(int value) {
            workflowEngineDelayInterval = value;
            return this;
        }

        /** @param value 旧开销模型的逻辑带宽 @return 当前构建器 */
        public Builder bandwidth(double value) {
            bandwidth = value;
            return this;
        }

        /** @param value 工作流引擎延迟分布 @return 当前构建器 */
        public Builder workflowEngineDelays(Map<Integer, DistributionSpec> value) {
            workflowEngineDelays = value;
            return this;
        }

        /** @param value 队列延迟分布 @return 当前构建器 */
        public Builder queueDelays(Map<Integer, DistributionSpec> value) {
            queueDelays = value;
            return this;
        }

        /** @param value 后处理延迟分布 @return 当前构建器 */
        public Builder postDelays(Map<Integer, DistributionSpec> value) {
            postDelays = value;
            return this;
        }

        /** @param value 聚类延迟分布 @return 当前构建器 */
        public Builder clusteringDelays(Map<Integer, DistributionSpec> value) {
            clusteringDelays = value;
            return this;
        }

        /**
         * 校验数值范围并创建开销模型。
         *
         * @return 不可变开销模型
         */
        public OverheadModelConfig build() {
            if (workflowEngineDelayInterval < 0 || Double.isNaN(bandwidth)
                    || Double.isInfinite(bandwidth) || bandwidth < 0.0) {
                throw new IllegalArgumentException("Overhead interval and bandwidth must be finite and non-negative");
            }
            return new OverheadModelConfig(workflowEngineDelayInterval, bandwidth,
                    workflowEngineDelays, queueDelays, postDelays, clusteringDelays);
        }
    }
}
