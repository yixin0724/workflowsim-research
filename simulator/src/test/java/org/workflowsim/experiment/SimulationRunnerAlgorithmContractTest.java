package org.workflowsim.experiment;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.workflowsim.exception.SimulationConfigurationException;
import org.workflowsim.failure.FailureModelConfig;
import org.workflowsim.failure.FailureParameters;
import org.workflowsim.platform.PlatformProfile;
import org.workflowsim.platform.PlatformProfiles;
import org.workflowsim.utils.ClusteringParameters;
import org.workflowsim.utils.Parameters.SchedulingAlgorithm;
import org.workflowsim.utils.SimulationConfig;

class SimulationRunnerAlgorithmContractTest {

    @Test
    void configurationRejectsAnUnselectedSchedulerInsteadOfFallingBackToStatic() {
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> SimulationConfig.builder("workflow.dax", 1)
                        .schedulingAlgorithm(SchedulingAlgorithm.INVALID).build());

        assertTrue(exception.getMessage().contains("not a runnable configuration"));
    }

    @Test
    void standardRunnerRejectsEveryLegacySchedulerCompatibilityLabel() {
        assertLegacySchedulerRejected(SchedulingAlgorithm.MINMIN);
        assertLegacySchedulerRejected(SchedulingAlgorithm.MAXMIN);
        assertLegacySchedulerRejected(SchedulingAlgorithm.MCT);
        assertLegacySchedulerRejected(SchedulingAlgorithm.ROUNDROBIN);
    }

    // R9：历史 HEFT/DHEFT 规划器标签已随其未对齐实现一并移除，
    // 原 standardRunnerRejectsLegacyDagPlannersWithUnalignedExecutionModels
    // 的拒绝契约不再适用（枚举中已无遗留 DAG 规划器标签）。

    @Test
    void standardRunnerRejectsLegacyFailureReclustering() {
        SimulationConfig config = SimulationConfig.builder("workflow.dax", 1)
                .schedulingAlgorithm(SchedulingAlgorithm.FCFS)
                .failureModel(FailureModelConfig.builder()
                        .clusteringAlgorithm(FailureParameters.FTCluteringAlgorithm.FTCLUSTERING_DC)
                        .build())
                .build();

        SimulationConfigurationException exception = assertThrows(SimulationConfigurationException.class,
                () -> new SimulationRunner().run(config,
                        PlatformProfiles.homogeneousLocal("legacy-reclustering", 1)));
        assertTrue(exception.getMessage().contains("FTCLUSTERING_DC"));
    }

    @Test
    void standardRunnerRejectsUnverifiedTaskClusteringModes() {
        SimulationConfig config = SimulationConfig.builder("workflow.dax", 1)
                .clusteringParameters(new ClusteringParameters(1, 0,
                        ClusteringParameters.ClusteringMethod.HORIZONTAL, null))
                .build();

        SimulationConfigurationException exception = assertThrows(SimulationConfigurationException.class,
                () -> new SimulationRunner().run(config,
                        PlatformProfiles.homogeneousLocal("clustered", 1)));
        assertTrue(exception.getMessage().contains("HORIZONTAL"));
    }

    @Test
    void standardRunnerRejectsTimeSharedVmsUntilTheirReadyJobSemanticsAreVerified() {
        SimulationConfig config = SimulationConfig.builder("workflow.dax", 1).build();
        PlatformProfile platform = PlatformProfile.builder("time-shared")
                .addHost(new PlatformProfile.HostSpec(0, 1, 1000.0,
                        1024, 1_000L, 10_000L))
                .addVm(new PlatformProfile.VmSpec(0, 1000.0, 1, 512,
                        1_000L, 1_000L, "Xen",
                        PlatformProfile.CloudletSchedulerMode.TIME_SHARED))
                .build();

        SimulationConfigurationException exception = assertThrows(SimulationConfigurationException.class,
                () -> new SimulationRunner().run(config, platform));
        assertTrue(exception.getMessage().contains("TIME_SHARED"));
    }

    private static void assertLegacySchedulerRejected(SchedulingAlgorithm schedulingAlgorithm) {
        SimulationConfig config = SimulationConfig.builder("workflow.dax", 1)
                .schedulingAlgorithm(schedulingAlgorithm).build();
        SimulationConfigurationException exception = assertThrows(SimulationConfigurationException.class,
                () -> new SimulationRunner().run(config,
                        PlatformProfiles.homogeneousLocal("legacy-scheduler", 1)));
        assertTrue(exception.getMessage().contains("not supported by SimulationRunner"));
    }
}
