package org.workflowsim.utils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;

import java.util.Arrays;
import org.junit.jupiter.api.Test;
import org.workflowsim.data.DataMovementModel;
import org.workflowsim.failure.FailureModelConfig;
import org.workflowsim.utils.Parameters.CostModel;
import org.workflowsim.utils.Parameters.PlanningAlgorithm;
import org.workflowsim.utils.Parameters.SchedulingAlgorithm;

class SimulationConfigCopyTest {

    @Test
    void withRandomSeedCopiesEveryOtherConfigurationValue() {
        // 有意设置所有可复制的非默认选项，避免新增字段在 withRandomSeed 中被静默遗漏。
        SimulationConfig original = SimulationConfig.builder(Arrays.asList("first.dax", "second.dax"), 2)
                .overheadModel(OverheadModelConfig.none())
                .clusteringParameters(new ClusteringParameters(0, 0,
                        ClusteringParameters.ClusteringMethod.NONE, null))
                .schedulingAlgorithm(SchedulingAlgorithm.FCFS)
                .planningAlgorithm(PlanningAlgorithm.INVALID)
                .reduceMethod("none")
                .deadline(17L)
                .fileSystem(ReplicaCatalog.FileSystem.SHARED)
                .randomSeed(3L)
                .runtimeScale(1.25)
                .runtimeReferenceMips(2500.0)
                .cloudSimMinEventIntervalSeconds(0.2)
                .costModel(CostModel.DATACENTER)
                .failureModel(FailureModelConfig.disabled())
                .dataMovementModel(DataMovementModel.fixedEndpointNoContention(50.0, 0.01, 25.0))
                .build();

        SimulationConfig copy = original.withRandomSeed(99L);

        assertNotSame(original, copy);
        assertEquals(original.getWorkflowPaths(), copy.getWorkflowPaths());
        assertEquals(original.getVmCount(), copy.getVmCount());
        assertEquals(original.getOverheadModel(), copy.getOverheadModel());
        assertEquals(original.getClusteringParameters(), copy.getClusteringParameters());
        assertEquals(original.getSchedulingAlgorithm(), copy.getSchedulingAlgorithm());
        assertEquals(original.getPlanningAlgorithm(), copy.getPlanningAlgorithm());
        assertEquals(original.getReduceMethod(), copy.getReduceMethod());
        assertEquals(original.getDeadline(), copy.getDeadline());
        assertEquals(original.getFileSystem(), copy.getFileSystem());
        assertEquals(99L, copy.getRandomSeed());
        assertEquals(original.getRuntimeScale(), copy.getRuntimeScale(), 0.0);
        assertEquals(original.getRuntimeReferenceMips(), copy.getRuntimeReferenceMips(), 0.0);
        assertEquals(original.getCloudSimMinEventIntervalSeconds(),
                copy.getCloudSimMinEventIntervalSeconds(), 0.0);
        assertEquals(original.getCostModel(), copy.getCostModel());
        assertEquals(original.getFailureModel(), copy.getFailureModel());
        assertEquals(original.getDataMovementModel().getKind(), copy.getDataMovementModel().getKind());
        assertEquals(original.getDataMovementModel().getAccessLinkBandwidthMbPerSecond(),
                copy.getDataMovementModel().getAccessLinkBandwidthMbPerSecond(), 0.0);
    }
}
