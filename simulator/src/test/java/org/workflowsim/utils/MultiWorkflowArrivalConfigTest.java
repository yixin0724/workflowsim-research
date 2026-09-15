package org.workflowsim.utils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.Collections;
import org.junit.jupiter.api.Test;

/**
 * R5 动态到达配置层契约：默认全零提交（历史行为不变）、显式到达时刻的校验
 * 与复制语义、到达门控与聚类的显式互斥。
 */
class MultiWorkflowArrivalConfigTest {

    private static final String PATH_A = "/tmp/workflow-a.dax";
    private static final String PATH_B = "/tmp/workflow-b.dax";

    @Test
    void defaultArrivalsAreZeroForEveryInput() {
        SimulationConfig single = SimulationConfig.builder(PATH_A, 2).build();
        assertEquals(Collections.singletonList(0.0), single.getWorkflowArrivalSeconds(),
                "单输入默认提交时刻为 0");
        SimulationConfig multi = SimulationConfig.builder(Arrays.asList(PATH_A, PATH_B), 2).build();
        assertEquals(Arrays.asList(0.0, 0.0), multi.getWorkflowArrivalSeconds(),
                "多输入默认全部 t=0");
    }

    @Test
    void explicitArrivalsArePreservedInOrderAndImmutable() {
        SimulationConfig config = SimulationConfig.builder(Arrays.asList(PATH_A, PATH_B), 2)
                .workflowArrivalSeconds(Arrays.asList(0.0, 42.5))
                .build();
        assertEquals(Arrays.asList(0.0, 42.5), config.getWorkflowArrivalSeconds());
        assertThrows(UnsupportedOperationException.class,
                () -> config.getWorkflowArrivalSeconds().set(0, 1.0),
                "到达时刻列表必须不可修改");
    }

    @Test
    void arrivalCountMustMatchInputCount() {
        assertThrows(IllegalArgumentException.class,
                () -> SimulationConfig.builder(Arrays.asList(PATH_A, PATH_B), 2)
                        .workflowArrivalSeconds(Collections.singletonList(0.0))
                        .build());
        assertThrows(IllegalArgumentException.class,
                () -> SimulationConfig.builder(PATH_A, 2)
                        .workflowArrivalSeconds(Arrays.asList(0.0, 1.0))
                        .build());
        assertThrows(IllegalArgumentException.class,
                () -> SimulationConfig.builder(PATH_A, 2)
                        .workflowArrivalSeconds(null)
                        .build());
    }

    @Test
    void arrivalValuesMustBeFiniteAndNonNegative() {
        assertThrows(IllegalArgumentException.class,
                () -> SimulationConfig.builder(PATH_A, 2)
                        .workflowArrivalSeconds(Collections.singletonList(-0.1))
                        .build());
        assertThrows(IllegalArgumentException.class,
                () -> SimulationConfig.builder(PATH_A, 2)
                        .workflowArrivalSeconds(Collections.singletonList(Double.NaN))
                        .build());
        assertThrows(IllegalArgumentException.class,
                () -> SimulationConfig.builder(PATH_A, 2)
                        .workflowArrivalSeconds(
                                Collections.singletonList(Double.POSITIVE_INFINITY))
                        .build());
        assertThrows(IllegalArgumentException.class,
                () -> SimulationConfig.builder(PATH_A, 2)
                        .workflowArrivalSeconds(Collections.<Double>singletonList(null))
                        .build());
    }

    @Test
    void toBuilderRoundTripPreservesArrivals() {
        SimulationConfig original = SimulationConfig.builder(Arrays.asList(PATH_A, PATH_B), 2)
                .workflowArrivalSeconds(Arrays.asList(0.0, 7.25))
                .build();
        SimulationConfig copy = original.toBuilder().randomSeed(123L).build();
        assertEquals(Arrays.asList(0.0, 7.25), copy.getWorkflowArrivalSeconds(),
                "toBuilder 必须保留到达时刻");
        assertEquals(123L, copy.getRandomSeed());
    }

    @Test
    void nonZeroArrivalRequiresClusteringNone() {
        // 聚类生成的 Job ID 不再携带原始任务编号，到达门控无法归属——显式拒绝。
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> SimulationConfig.builder(Arrays.asList(PATH_A, PATH_B), 2)
                        .workflowArrivalSeconds(Arrays.asList(0.0, 10.0))
                        .clusteringParameters(new ClusteringParameters(2, 0,
                                ClusteringParameters.ClusteringMethod.HORIZONTAL, null))
                        .build());
        assertTrue(failure.getMessage().contains("clustering"), failure.getMessage());
        // 全零到达 + 聚类仍被允许（等价历史单时刻提交路径）。
        SimulationConfig zeroArrivalWithClustering = SimulationConfig
                .builder(Arrays.asList(PATH_A, PATH_B), 2)
                .clusteringParameters(new ClusteringParameters(2, 0,
                        ClusteringParameters.ClusteringMethod.HORIZONTAL, null))
                .build();
        assertEquals(Arrays.asList(0.0, 0.0),
                zeroArrivalWithClustering.getWorkflowArrivalSeconds());
    }
}
