package org.workflowsim.experiment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URL;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;
import org.cloudbus.cloudsim.Log;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.workflowsim.platform.PlatformProfile;
import org.workflowsim.platform.PlatformProfiles;
import org.workflowsim.utils.Parameters;
import org.workflowsim.utils.SimulationConfig;

class SimulationSemanticContractIntegrationTest {

    private static final long FIXED_SEED = 20260901L;

    @AfterEach
    void restoreLogging() {
        Log.enable();
    }

    @Test
    void noClusteringReportRetainsTaskIdentityAndRespectsFixtureDependencies() throws Exception {
        SimulationConfig config = SimulationConfig.builder(
                        resourcePath("/dax/reproducibility-workflow.dax"), 3)
                .schedulingAlgorithm(Parameters.SchedulingAlgorithm.FCFS)
                .randomSeed(FIXED_SEED)
                .build();

        Log.disable();
        SimulationReport report = new SimulationRunner().run(config,
                PlatformProfiles.homogeneousLocal("p7-dag-contract", 3));

        Map<Integer, SimulationReport.JobOutcome> outcomeByTaskId = new HashMap<Integer, SimulationReport.JobOutcome>();
        int stageInJobs = 0;
        for (SimulationReport.JobOutcome outcome : report.getJobs()) {
            if (outcome.getClassType() == Parameters.ClassType.STAGE_IN.value) {
                stageInJobs++;
                assertTrue(outcome.getTaskIds().isEmpty());
                continue;
            }
            assertEquals(Parameters.ClassType.COMPUTE.value, outcome.getClassType());
            assertEquals(1, outcome.getTaskCount());
            assertEquals(1, outcome.getTaskIds().size());
            outcomeByTaskId.put(outcome.getTaskIds().get(0), outcome);
        }

        assertEquals(1, stageInJobs);
        assertEquals(5, outcomeByTaskId.size());
        assertStartsAfter(outcomeByTaskId, 3, 1);
        assertStartsAfter(outcomeByTaskId, 4, 2);
        assertStartsAfter(outcomeByTaskId, 5, 3);
        assertStartsAfter(outcomeByTaskId, 5, 4);
        assertEquals(report.getTotalJobs(), report.getSuccessfulJobs());
        assertEquals(0, report.getFailedJobs());
    }

    @Test
    void sharedStorageTransferRateChangesOnlyTheModeledTransferComponentMonotonically()
            throws Exception {
        String workflow = resourcePath("/dax/p7-shared-storage.dax");
        SimulationConfig config = SimulationConfig.builder(workflow, 1)
                .schedulingAlgorithm(Parameters.SchedulingAlgorithm.FCFS)
                .randomSeed(FIXED_SEED)
                .build();

        Log.disable();
        SimulationReport slow = new SimulationRunner().run(config, singleVmProfile("p7-storage-10", 10));
        SimulationReport fast = new SimulationRunner().run(config, singleVmProfile("p7-storage-20", 20));

        assertEquals(slow.getTotalJobs(), slow.getSuccessfulJobs());
        assertEquals(fast.getTotalJobs(), fast.getSuccessfulJobs());
        assertTrue(slow.getMakespan() > fast.getMakespan());
        assertEquals(1.5, slow.getMakespan() - fast.getMakespan(), 1.0e-9);
    }

    private static void assertStartsAfter(Map<Integer, SimulationReport.JobOutcome> outcomes,
            int childTaskId, int parentTaskId) {
        SimulationReport.JobOutcome child = outcomes.get(childTaskId);
        SimulationReport.JobOutcome parent = outcomes.get(parentTaskId);
        assertTrue(child != null, "Missing task " + childTaskId);
        assertTrue(parent != null, "Missing task " + parentTaskId);
        assertTrue(child.getStartTime() >= parent.getFinishTime(),
                "Task " + childTaskId + " started before parent " + parentTaskId + " finished");
    }

    private static PlatformProfile singleVmProfile(String name, int storageRate) {
        return PlatformProfile.builder(name)
                .addHost(new PlatformProfile.HostSpec(0, 2, 2000.0,
                        2048, 10_000L, 1_000_000L))
                .addVm(new PlatformProfile.VmSpec(0, 1000.0, 1, 512,
                        1000L, 10_000L, "Xen",
                        PlatformProfile.CloudletSchedulerMode.SPACE_SHARED))
                .storage(new PlatformProfile.StorageSpec(1_000_000_000_000L, storageRate))
                .build();
    }

    private String resourcePath(String resource) throws Exception {
        URL url = getClass().getResource(resource);
        if (url == null) {
            throw new IllegalStateException("Missing test resource " + resource);
        }
        return Paths.get(url.toURI()).toString();
    }
}
