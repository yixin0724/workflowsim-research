package org.workflowsim.scheduling;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Arrays;
import org.cloudbus.cloudsim.Cloudlet;
import org.cloudbus.cloudsim.CloudletSchedulerTimeShared;
import org.cloudbus.cloudsim.Log;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.workflowsim.CondorVM;
import org.workflowsim.Job;
import org.workflowsim.WorkflowSimTags;
import org.workflowsim.utils.Parameters.ClassType;

class StaticSchedulingAlgorithmTest {

    @AfterEach
    void restoreLogging() {
        Log.enable();
    }

    @Test
    void completePlanDoesNotDispatchAReadyLaterJobBeforeItsExpectedPredecessor() throws Exception {
        Log.disable();
        Job first = plannedJob(10, 4.0);
        Job later = plannedJob(11, 5.0);
        StaticSchedulePlan plan = StaticSchedulePlan.fromJobs(Arrays.asList(first, later));
        StaticSchedulingAlgorithm scheduler = new StaticSchedulingAlgorithm();
        scheduler.setStaticSchedulePlan(plan);
        CondorVM vm = vm(4);

        scheduler.setVmList(Arrays.asList(vm));
        scheduler.setCloudletList(Arrays.<Cloudlet>asList(later));
        scheduler.run();
        assertEquals(0, scheduler.getScheduledList().size());

        scheduler.setCloudletList(Arrays.<Cloudlet>asList(first, later));
        scheduler.run();
        assertEquals(1, scheduler.getScheduledList().size());
        assertEquals(10, ((Cloudlet) scheduler.getScheduledList().get(0)).getCloudletId());

        vm.setState(WorkflowSimTags.VM_STATUS_IDLE);
        scheduler.getScheduledList().clear();
        scheduler.setCloudletList(Arrays.<Cloudlet>asList(later));
        scheduler.run();
        assertEquals(1, scheduler.getScheduledList().size());
        assertEquals(11, ((Cloudlet) scheduler.getScheduledList().get(0)).getCloudletId());
    }

    private static Job plannedJob(int id, double startTime) {
        Job job = new Job(id, 1000);
        job.setVmId(4);
        job.setClassType(ClassType.COMPUTE.value);
        job.setStaticScheduleStartTime(startTime);
        return job;
    }

    private static CondorVM vm(int id) {
        return new CondorVM(id, 0, 1000.0, 1, 512, 1000, 10_000, "Xen",
                new CloudletSchedulerTimeShared());
    }
}
