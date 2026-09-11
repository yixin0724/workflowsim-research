package org.workflowsim.experiment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.workflowsim.Job;
import org.workflowsim.Task;

class SimulationReportTaskOutcomeTest {

    @Test
    void singleTaskJobUsesItsModeledComputeWindowWhenItDiffersFromJobEnvelope() throws Exception {
        Job job = new Job(10, 1000L);
        job.setExecStartTime(7.0);
        job.setCloudletStatus(org.cloudbus.cloudsim.Cloudlet.SUCCESS);
        Task task = task(1, 2.0, 3.0);
        job.addTaskList(Arrays.asList(task));

        List<SimulationReport.TaskOutcome> outcomes = SimulationReport.TaskOutcome.fromJob(job);

        assertEquals(1, outcomes.size());
        assertFalse(outcomes.get(0).hasExactJobTiming());
        assertEquals(org.cloudbus.cloudsim.Cloudlet.SUCCESS, outcomes.get(0).getTaskStatus());
        assertEquals(2.0, outcomes.get(0).getStartTime(), 0.0);
        assertEquals(3.0, outcomes.get(0).getFinishTime(), 0.0);
    }

    @Test
    void clusteredJobMarksSequentialTaskTimingAsModeledApproximation() {
        Job job = new Job(10, 2000L);
        job.setExecStartTime(7.0);
        job.addTaskList(Arrays.asList(task(1, 2.0, 3.0), task(2, 3.0, 5.0)));

        List<SimulationReport.TaskOutcome> outcomes = SimulationReport.TaskOutcome.fromJob(job);

        assertEquals(2, outcomes.size());
        assertFalse(outcomes.get(0).hasExactJobTiming());
        assertFalse(outcomes.get(1).hasExactJobTiming());
        assertEquals(2.0, outcomes.get(0).getStartTime(), 0.0);
        assertEquals(3.0, outcomes.get(0).getFinishTime(), 0.0);
        assertEquals(3.0, outcomes.get(1).getStartTime(), 0.0);
        assertEquals(5.0, outcomes.get(1).getFinishTime(), 0.0);
    }

    @Test
    void failedClusteredJobRetainsTaskLevelFailureStatus() throws Exception {
        Job job = new Job(10, 2000L);
        job.setCloudletStatus(org.cloudbus.cloudsim.Cloudlet.FAILED);
        Task failed = task(1, 2.0, 3.0);
        failed.setCloudletStatus(org.cloudbus.cloudsim.Cloudlet.FAILED);
        Task notCompleted = task(2, 3.0, 5.0);
        job.addTaskList(Arrays.asList(failed, notCompleted));

        List<SimulationReport.TaskOutcome> outcomes = SimulationReport.TaskOutcome.fromJob(job);

        assertEquals(org.cloudbus.cloudsim.Cloudlet.FAILED, outcomes.get(0).getTaskStatus());
        assertEquals(org.cloudbus.cloudsim.Cloudlet.CREATED, outcomes.get(1).getTaskStatus());
    }

    private static Task task(int id, double start, double finish) {
        Task task = new Task(id, 1000L);
        task.setExecStartTime(start);
        task.setTaskFinishTime(finish);
        return task;
    }
}
