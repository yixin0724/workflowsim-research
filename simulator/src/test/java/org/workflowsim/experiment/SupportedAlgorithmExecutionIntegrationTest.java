package org.workflowsim.experiment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URL;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.List;
import org.cloudbus.cloudsim.Log;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.workflowsim.platform.PlatformProfile;
import org.workflowsim.platform.PlatformProfiles;
import org.workflowsim.utils.Parameters.PlanningAlgorithm;
import org.workflowsim.utils.Parameters.SchedulingAlgorithm;
import org.workflowsim.utils.ReplicaCatalog;
import org.workflowsim.utils.SimulationConfig;

class SupportedAlgorithmExecutionIntegrationTest {

    @AfterEach
    void restoreLogging() {
        Log.enable();
    }

    @Test
    void everySupportedOnlineSchedulerCompletesTheSameDag() throws Exception {
        Log.disable();
        for (SchedulingAlgorithm algorithm : Arrays.asList(
                SchedulingAlgorithm.FCFS,
                SchedulingAlgorithm.READY_BATCH_ROUNDROBIN,
                SchedulingAlgorithm.READY_BATCH_MCT,
                SchedulingAlgorithm.READY_BATCH_MINMIN,
                SchedulingAlgorithm.READY_BATCH_MAXMIN,
                SchedulingAlgorithm.DATA)) {
            // DATA 算法的局部性判定要求 LOCAL 文件系统模式（SHARED 模式下配置层会拒绝）。
            SimulationConfig config = SimulationConfig.builder(
                            resourcePath("/dax/reproducibility-workflow.dax"), 3)
                    .schedulingAlgorithm(algorithm)
                    .fileSystem(algorithm == SchedulingAlgorithm.DATA
                            ? ReplicaCatalog.FileSystem.LOCAL
                            : ReplicaCatalog.FileSystem.SHARED)
                    .randomSeed(91L)
                    .build();

            SimulationReport report = new SimulationRunner().run(config,
                    PlatformProfiles.homogeneousLocal("online-" + algorithm.name(), 3));

            assertEquals(5, report.getMetrics().getLogicalTaskCount(), algorithm.name());
            assertTrue(report.getMetrics().isAllLogicalTasksCompletedSuccessfully(), algorithm.name());
            assertEquals(report.getTotalJobs(), report.getSuccessfulJobs(), algorithm.name());
        }
    }

    @Test
    void everySupportedIndependentPlannerCompletesTheIndependentTaskFixture() throws Exception {
        Log.disable();
        for (PlanningAlgorithm algorithm : Arrays.asList(
                PlanningAlgorithm.STATIC_OLB,
                PlanningAlgorithm.STATIC_MET,
                PlanningAlgorithm.STATIC_MCT,
                PlanningAlgorithm.STATIC_MINMIN,
                PlanningAlgorithm.STATIC_MAXMIN,
                PlanningAlgorithm.STATIC_SUFFERAGE,
                PlanningAlgorithm.STATIC_ROUND_ROBIN)) {
            SimulationConfig config = SimulationConfig.builder(
                            resourcePath("/dax/independent-tasks.dax"), 2)
                    .planningAlgorithm(algorithm)
                    .schedulingAlgorithm(SchedulingAlgorithm.STATIC)
                    .randomSeed(91L)
                    .build();

            SimulationReport report = new SimulationRunner().run(config,
                    PlatformProfiles.homogeneousLocal("independent-" + algorithm.name(), 2));

            assertEquals(3, report.getMetrics().getLogicalTaskCount(), algorithm.name());
            assertTrue(report.getMetrics().isAllLogicalTasksCompletedSuccessfully(), algorithm.name());
        }
    }

    @Test
    void randomMappingAndMaintainedDagPlannersRunWithNonContiguousVmIds() throws Exception {
        Log.disable();
        PlatformProfile platform = nonContiguousPlatform();
        SimulationConfig random = SimulationConfig.builder(
                        resourcePath("/dax/reproducibility-workflow.dax"), 2)
                .planningAlgorithm(PlanningAlgorithm.RANDOM)
                .schedulingAlgorithm(SchedulingAlgorithm.STATIC)
                .randomSeed(91L)
                .build();
        assertComplete(new SimulationRunner().run(random, platform));

        for (PlanningAlgorithm algorithm : Arrays.asList(
                PlanningAlgorithm.SHARED_STORAGE_HEFT, PlanningAlgorithm.SHARED_STORAGE_CPOP,
                PlanningAlgorithm.SHARED_STORAGE_DLS, PlanningAlgorithm.SHARED_STORAGE_ETF,
                PlanningAlgorithm.SHARED_STORAGE_PEFT)) {
            SimulationConfig config = SimulationConfig.builder(
                            resourcePath("/dax/reproducibility-workflow.dax"), 2)
                    .planningAlgorithm(algorithm)
                    .schedulingAlgorithm(SchedulingAlgorithm.STATIC)
                    .randomSeed(91L)
                    .build();
            SimulationReport report = new SimulationRunner().run(config, platform);
            assertComplete(report);
            assertTrue(report.getSharedStorageDagPlanTrace() != null, algorithm.name());
            for (SimulationReport.TaskOutcome task : report.getTasks()) {
                assertTrue(task.getVmId() == 10 || task.getVmId() == 20, algorithm.name());
            }
        }
    }

    private static void assertComplete(SimulationReport report) {
        assertEquals(report.getTotalJobs(), report.getSuccessfulJobs());
        assertTrue(report.getMetrics().isAllLogicalTasksCompletedSuccessfully());
    }

    private static PlatformProfile nonContiguousPlatform() {
        return PlatformProfile.builder("non-contiguous-vm-ids")
                .addHost(new PlatformProfile.HostSpec(0, 2, 2000.0,
                        2048, 10_000L, 1_000_000L))
                .addHost(new PlatformProfile.HostSpec(1, 2, 2000.0,
                        2048, 10_000L, 1_000_000L))
                .addVm(new PlatformProfile.VmSpec(20, 500.0, 1, 512,
                        1000L, 10_000L, "Xen",
                        PlatformProfile.CloudletSchedulerMode.SPACE_SHARED))
                .addVm(new PlatformProfile.VmSpec(10, 1000.0, 1, 512,
                        1000L, 10_000L, "Xen",
                        PlatformProfile.CloudletSchedulerMode.SPACE_SHARED))
                .storage(new PlatformProfile.StorageSpec(1_000_000_000L, 20))
                .build();
    }

    private static String resourcePath(String resource) throws Exception {
        URL url = SupportedAlgorithmExecutionIntegrationTest.class.getResource(resource);
        if (url == null) {
            throw new IllegalStateException("Missing test resource " + resource);
        }
        return Paths.get(url.toURI()).toString();
    }
}
