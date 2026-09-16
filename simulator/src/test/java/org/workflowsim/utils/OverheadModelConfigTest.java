package org.workflowsim.utils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.workflowsim.utils.DistributionGenerator.DistributionFamily;

/**
 * {@link OverheadModelConfig} 全分支契约：构建器数值校验、延迟映射校验
 * （null 映射/深度键/分布、NORMAL 拒绝）、legacy 桥复制语义、生成器重建
 * 与不可变性。
 */
class OverheadModelConfigTest {

    @Test
    void rejectsUnboundedNormalDistributionForSimulatedDelays() {
        DistributionSpec normal = DistributionSpec.of(DistributionFamily.NORMAL, 1.0, 0.1);

        assertThrows(IllegalArgumentException.class, () -> OverheadModelConfig.builder()
                .queueDelays(Collections.singletonMap(0, normal))
                .build());
    }

    @Test
    void builderRejectsNegativeIntervalAndNonFiniteOrNegativeBandwidth() {
        assertThrows(IllegalArgumentException.class, () -> OverheadModelConfig.builder()
                .workflowEngineDelayInterval(-1).build());
        assertThrows(IllegalArgumentException.class, () -> OverheadModelConfig.builder()
                .bandwidth(Double.NaN).build());
        assertThrows(IllegalArgumentException.class, () -> OverheadModelConfig.builder()
                .bandwidth(Double.POSITIVE_INFINITY).build());
        assertThrows(IllegalArgumentException.class, () -> OverheadModelConfig.builder()
                .bandwidth(-0.001).build());
        // 边界接受：零间隔与零带宽合法。
        OverheadModelConfig zero = OverheadModelConfig.builder()
                .workflowEngineDelayInterval(0).bandwidth(0.0).build();
        assertEquals(0, zero.getWorkflowEngineDelayInterval());
        assertEquals(0.0, zero.getBandwidth(), 0.0);
    }

    @Test
    void delayMapValidationCoversNullMapNullKeyNegativeDepthAndNullSpec() {
        assertThrows(IllegalArgumentException.class, () -> OverheadModelConfig.builder()
                .workflowEngineDelays(null).build());
        assertThrows(IllegalArgumentException.class, () -> OverheadModelConfig.builder()
                .postDelays(null).build());

        Map<Integer, DistributionSpec> nullKey = new HashMap<>();
        nullKey.put(null, weibull(1.0));
        assertThrows(IllegalArgumentException.class, () -> OverheadModelConfig.builder()
                .queueDelays(nullKey).build());

        Map<Integer, DistributionSpec> negativeDepth = new HashMap<>();
        negativeDepth.put(-1, weibull(1.0));
        assertThrows(IllegalArgumentException.class, () -> OverheadModelConfig.builder()
                .clusteringDelays(negativeDepth).build());

        Map<Integer, DistributionSpec> nullSpec = new HashMap<>();
        nullSpec.put(0, null);
        assertThrows(IllegalArgumentException.class, () -> OverheadModelConfig.builder()
                .workflowEngineDelays(nullSpec).build());
    }

    @Test
    void legacyBridgeCopiesSpecsAndRejectsNullParametersOrGenerators() {
        assertThrows(IllegalArgumentException.class, () -> OverheadModelConfig.fromLegacy(null));

        Map<Integer, DistributionGenerator> wed = new LinkedHashMap<>();
        wed.put(0, new DistributionGenerator(DistributionFamily.WEIBULL, 9.0, 2.0,
                "audit.wed"));
        OverheadParameters legacy = new OverheadParameters(3, wed, null, null, null, 1.5);
        OverheadModelConfig config = OverheadModelConfig.fromLegacy(legacy);
        assertEquals(3, config.getWorkflowEngineDelayInterval());
        assertEquals(1.5, config.getBandwidth(), 0.0);
        DistributionSpec copied = config.getWorkflowEngineDelays().get(0);
        assertNotNull(copied);
        assertEquals(DistributionFamily.WEIBULL, copied.getFamily());
        assertEquals(9.0, copied.getScale(), 0.0);
        assertEquals(2.0, copied.getShape(), 0.0);
        assertTrue(config.getQueueDelays().isEmpty());
        assertTrue(config.getPostDelays().isEmpty());
        assertTrue(config.getClusteringDelays().isEmpty());

        Map<Integer, DistributionGenerator> nullGenerator = new LinkedHashMap<>();
        nullGenerator.put(0, null);
        OverheadParameters broken = new OverheadParameters(1, nullGenerator, null, null, null,
                1.0);
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> OverheadModelConfig.fromLegacy(broken));
        assertTrue(ex.getMessage().contains("depth 0"), ex.getMessage());
    }

    @Test
    void createParametersReturnsNullForEmptyMapsAndFreshGeneratorsOtherwise() {
        OverheadModelConfig empty = OverheadModelConfig.none();
        OverheadParameters emptyParams = empty.createParameters();
        assertNull(emptyParams.getWEDDelay());
        assertNull(emptyParams.getQueueDelay());
        assertNull(emptyParams.getPostDelay());
        assertNull(emptyParams.getClustDelay());

        Map<Integer, DistributionSpec> queue = new LinkedHashMap<>();
        queue.put(0, weibull(4.0));
        queue.put(2, gamma(7.0));
        OverheadModelConfig config = OverheadModelConfig.builder()
                .workflowEngineDelayInterval(1)
                .bandwidth(2.0)
                .queueDelays(queue)
                .build();
        OverheadParameters params = config.createParameters();
        assertEquals(2, params.getQueueDelay().size());
        DistributionGenerator depthZero = params.getQueueDelay().get(0);
        assertNotNull(depthZero);
        assertEquals(DistributionFamily.WEIBULL, depthZero.getFamily());
        assertNotNull(params.getQueueDelay().get(2));
        // 未配置的映射仍为 null（历史语义：null = 无该开销）。
        assertNull(params.getWEDDelay());
    }

    @Test
    void configIsImmutableAndDecoupledFromBuilderInput() {
        Map<Integer, DistributionSpec> mutableInput = new LinkedHashMap<>();
        mutableInput.put(0, weibull(3.0));
        OverheadModelConfig config = OverheadModelConfig.builder()
                .postDelays(mutableInput)
                .build();
        // 构建后修改输入映射不影响已构建配置。
        mutableInput.put(5, weibull(99.0));
        assertEquals(1, config.getPostDelays().size());
        // 返回的映射不可修改。
        assertThrows(UnsupportedOperationException.class,
                () -> config.getPostDelays().put(1, weibull(1.0)));
    }

    @Test
    void noneIsASharedZeroOverheadSingleton() {
        OverheadModelConfig none = OverheadModelConfig.none();
        assertSame(none, OverheadModelConfig.none());
        assertEquals(0, none.getWorkflowEngineDelayInterval());
        assertEquals(0.0, none.getBandwidth(), 0.0);
        assertTrue(none.getWorkflowEngineDelays().isEmpty());
        assertTrue(none.getQueueDelays().isEmpty());
        assertTrue(none.getPostDelays().isEmpty());
        assertTrue(none.getClusteringDelays().isEmpty());
    }

    private static DistributionSpec weibull(double scale) {
        return DistributionSpec.of(DistributionFamily.WEIBULL, scale, 2.0);
    }

    private static DistributionSpec gamma(double scale) {
        return DistributionSpec.of(DistributionFamily.GAMMA, scale, 1.5);
    }
}
