package org.workflowsim.experiment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URL;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import org.cloudbus.cloudsim.Log;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.workflowsim.WorkflowInputReport;
import org.workflowsim.platform.PlatformProfiles;
import org.workflowsim.utils.Parameters;
import org.workflowsim.utils.ReplicaCatalog;
import org.workflowsim.utils.SimulationConfig;

class WorkflowInputIsolationIntegrationTest {

    @AfterEach
    void restoreLogging() {
        Log.enable();
    }

    @Test
    void multipleDaxAndWfCommonsInputsHaveUniqueTaskIdsAndCompleteTogether() throws Exception {
        SimulationConfig config = SimulationConfig.builder(Arrays.asList(
                        resourcePath("/dax/dependency-before-jobs.dax"),
                        resourcePath("/wfcommons/minimum-runtime.json")), 2)
                .schedulingAlgorithm(Parameters.SchedulingAlgorithm.FCFS)
                .build();

        Log.disable();
        SimulationReport report = new SimulationRunner().run(config,
                PlatformProfiles.homogeneousLocal("mixed-inputs", 2));

        assertEquals(2, report.getInputReports().size());
        assertEquals(WorkflowInputReport.Format.DAX_XML,
                report.getInputReports().get(0).getFormat());
        assertEquals(WorkflowInputReport.Format.WFCOMMONS_JSON,
                report.getInputReports().get(1).getFormat());
        assertEquals(3, report.getTasks().size());
        assertEquals(3, report.getMetrics().getLogicalTaskCount());
        assertTrue(report.getMetrics().isAllLogicalTasksCompletedSuccessfully());
        Set<Integer> taskIds = new HashSet<Integer>();
        for (SimulationReport.TaskOutcome task : report.getTasks()) {
            assertTrue(taskIds.add(task.getTaskId()), "Duplicate task ID " + task.getTaskId());
        }
    }

    @Test
    void aParseFailureClosesGlobalStateAndDoesNotPoisonTheNextRun() throws Exception {
        SimulationConfig invalid = SimulationConfig.builder("missing-input.dax", 1)
                .schedulingAlgorithm(Parameters.SchedulingAlgorithm.FCFS)
                .build();
        SimulationRunner runner = new SimulationRunner();

        Log.disable();
        assertThrows(Exception.class, () -> runner.run(invalid,
                PlatformProfiles.homogeneousLocal("failed-input", 1)));
        assertNull(Parameters.getDaxPath());
        assertThrows(IllegalStateException.class, ReplicaCatalog::getFileSystem);

        SimulationConfig valid = SimulationConfig.builder(
                        resourcePath("/wfcommons/minimum-runtime.json"), 1)
                .schedulingAlgorithm(Parameters.SchedulingAlgorithm.FCFS)
                .build();
        SimulationReport report = runner.run(valid,
                PlatformProfiles.homogeneousLocal("recovered-input", 1));
        assertTrue(report.getMetrics().isAllLogicalTasksCompletedSuccessfully());
    }

    private static String resourcePath(String resource) throws Exception {
        URL url = WorkflowInputIsolationIntegrationTest.class.getResource(resource);
        if (url == null) {
            throw new IllegalStateException("Missing test resource " + resource);
        }
        return Paths.get(url.toURI()).toString();
    }
}
