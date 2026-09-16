package org.workflowsim.utils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Arrays;
import java.util.Calendar;
import java.util.Collections;
import org.cloudbus.cloudsim.core.CloudSim;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.workflowsim.failure.FailureMonitor;
import org.workflowsim.failure.FailureParameters;
import org.workflowsim.utils.Parameters.PlanningAlgorithm;
import org.workflowsim.utils.Parameters.SchedulingAlgorithm;

class SimulationSessionTest {

    @AfterEach
    void resetLegacyState() {
        Parameters.reset();
        ReplicaCatalog.reset();
    }

    @Test
    void isolatesSequentialConfigurationsAndClearsGlobalStateOnClose() {
        // 第一段会话安装单输入配置；关闭后必须撤销所有遗留静态状态，不能泄漏给下一次运行。
        SimulationConfig first = SimulationConfig.builder("first.dax", 2)
                .schedulingAlgorithm(SchedulingAlgorithm.READY_BATCH_MINMIN)
                .randomSeed(17L)
                .runtimeScale(2.0)
                .runtimeReferenceMips(2500.0)
                .cloudSimMinEventIntervalSeconds(0.25)
                .build();

        try (SimulationSession session = SimulationSession.open(first)) {
            assertEquals("first.dax", Parameters.getDaxPath());
            assertNull(Parameters.getDAXPaths());
            assertEquals(17L, Parameters.getRandomSeed());
            assertEquals(2.0, Parameters.getRuntimeScale());
            assertEquals(2500.0, Parameters.getRuntimeReferenceMips());
            assertEquals(ReplicaCatalog.FileSystem.SHARED, ReplicaCatalog.getFileSystem());
            session.initializeCloudSim(1, Calendar.getInstance(), false);
            assertEquals(0.25, CloudSim.getMinTimeBetweenEvents(), 0.0);
        }

        assertNull(Parameters.getDaxPath());
        assertNull(Parameters.getDAXPaths());
        assertEquals(SchedulingAlgorithm.INVALID, Parameters.getSchedulingAlgorithm());
        assertThrows(IllegalStateException.class, ReplicaCatalog::getFileSystem);
        assertThrows(IllegalStateException.class, () -> FailureMonitor.analyze(0, 0));
        assertThrows(IllegalStateException.class, FailureParameters::getFailureGenerators);

        // 第二段会话改用多输入配置，以验证它不会继承第一段会话的单输入路径。
        SimulationConfig second = SimulationConfig.builder(Arrays.asList("second.dax", "third.json"), 3)
                .planningAlgorithm(PlanningAlgorithm.RANDOM)
                .schedulingAlgorithm(SchedulingAlgorithm.STATIC)
                .build();
        try (SimulationSession session = SimulationSession.open(second)) {
            assertNull(Parameters.getDaxPath());
            assertEquals(Arrays.asList("second.dax", "third.json"), Parameters.getDAXPaths());
        }
    }

    @Test
    void rejectsConcurrentSessionsAndInvalidConfiguration() {
        SimulationConfig config = SimulationConfig.builder("workflow.dax", 1).build();
        try (SimulationSession ignored = SimulationSession.open(config)) {
            assertThrows(IllegalStateException.class, () -> SimulationSession.open(config));
        }
        assertThrows(IllegalArgumentException.class,
                () -> SimulationConfig.builder("workflow.dax", 0).build());
        assertThrows(IllegalArgumentException.class,
                () -> SimulationConfig.builder("workflow.dax", 1).runtimeReferenceMips(0.0).build());
        assertThrows(IllegalArgumentException.class,
                () -> SimulationConfig.builder("workflow.dax", 1)
                        .cloudSimMinEventIntervalSeconds(0.0).build());
        SimulationConfig deadlineObservation = SimulationConfig.builder("workflow.dax", 1)
                .deadline(1L).build();
        assertEquals(1L, deadlineObservation.getDeadline());
        assertThrows(IllegalArgumentException.class,
                () -> SimulationConfig.builder("workflow.dax", 1).deadline(-1L).build());
        assertThrows(IllegalArgumentException.class,
                () -> SimulationConfig.builder("workflow.dax", 1)
                        .schedulingAlgorithm(SchedulingAlgorithm.INVALID).build());
        assertThrows(IllegalArgumentException.class,
                () -> SimulationConfig.builder("workflow.dax", 1)
                        .planningAlgorithm(PlanningAlgorithm.RANDOM)
                        .build());
        assertThrows(IllegalArgumentException.class,
                () -> SimulationConfig.builder("workflow.dax", 1)
                        .schedulingAlgorithm(SchedulingAlgorithm.STATIC)
                        .build());
        assertThrows(IllegalArgumentException.class,
                () -> SimulationConfig.builder("workflow.dax", 1)
                        .planningAlgorithm(PlanningAlgorithm.SHARED_STORAGE_HEFT)
                        .schedulingAlgorithm(SchedulingAlgorithm.STATIC)
                        .fileSystem(ReplicaCatalog.FileSystem.LOCAL)
                        .build());
        assertThrows(IllegalArgumentException.class,
                () -> SimulationConfig.builder("workflow.dax", 1)
                        .planningAlgorithm(PlanningAlgorithm.SHARED_STORAGE_CPOP)
                        .schedulingAlgorithm(SchedulingAlgorithm.STATIC)
                        .fileSystem(ReplicaCatalog.FileSystem.LOCAL)
                        .build());
        assertThrows(IllegalArgumentException.class,
                () -> SimulationConfig.builder("workflow.dax", 1)
                        .planningAlgorithm(PlanningAlgorithm.SHARED_STORAGE_DLS)
                        .schedulingAlgorithm(SchedulingAlgorithm.STATIC)
                        .fileSystem(ReplicaCatalog.FileSystem.LOCAL)
                        .build());
        assertThrows(IllegalArgumentException.class,
                () -> SimulationConfig.builder("workflow.dax", 1)
                        .planningAlgorithm(PlanningAlgorithm.SHARED_STORAGE_ETF)
                        .schedulingAlgorithm(SchedulingAlgorithm.STATIC)
                        .fileSystem(ReplicaCatalog.FileSystem.LOCAL)
                        .build());
        assertThrows(IllegalArgumentException.class,
                () -> SimulationConfig.builder("workflow.dax", 1)
                        .planningAlgorithm(PlanningAlgorithm.SHARED_STORAGE_PEFT)
                        .schedulingAlgorithm(SchedulingAlgorithm.STATIC)
                        .fileSystem(ReplicaCatalog.FileSystem.LOCAL)
                        .build());
    }

    @Test
    void createsConfiguredOverheadDistributionsAfterInstallingTheSeed() {
        OverheadModelConfig overhead = OverheadModelConfig.builder()
                .queueDelays(Collections.singletonMap(0, DistributionSpec.of(
                        DistributionGenerator.DistributionFamily.WEIBULL, 2.0, 3.0)))
                .build();
        SimulationConfig config = SimulationConfig.builder("workflow.dax", 1)
                .randomSeed(41L)
                .overheadModel(overhead)
                .build();

        double first;
        try (SimulationSession ignored = SimulationSession.open(config)) {
            first = Parameters.getOverheadParams().getQueueDelay().get(0).getNextSample();
        }
        try (SimulationSession ignored = SimulationSession.open(config)) {
            assertEquals(first, Parameters.getOverheadParams().getQueueDelay().get(0).getNextSample(), 0.0);
        }
    }
}
