package org.workflowsim.scheduling;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Arrays;
import org.cloudbus.cloudsim.Cloudlet;
import org.cloudbus.cloudsim.CloudletSchedulerSpaceShared;
import org.junit.jupiter.api.Test;
import org.workflowsim.CondorVM;
import org.workflowsim.Job;
import org.workflowsim.WorkflowSimTags;

class FCFSSchedulingAlgorithmTest {

    @Test
    void dispatchesReadyJobsInInputOrderToIdleVmsInAscendingVmIdOrder() {
        Job firstReady = job(40, 1);
        Job secondReady = job(10, 1);
        Job thirdReady = job(20, 1);
        CondorVM busy = vm(1, 1);
        busy.setState(WorkflowSimTags.VM_STATUS_BUSY);
        CondorVM higherIdIdle = vm(9, 1);
        CondorVM lowerIdIdle = vm(3, 1);

        FCFSSchedulingAlgorithm scheduler = new FCFSSchedulingAlgorithm();
        scheduler.setCloudletList(Arrays.<Cloudlet>asList(firstReady, secondReady, thirdReady));
        scheduler.setVmList(Arrays.asList(busy, higherIdIdle, lowerIdIdle));
        scheduler.run();

        assertEquals(2, scheduler.getScheduledList().size());
        assertEquals(firstReady, scheduler.getScheduledList().get(0));
        assertEquals(secondReady, scheduler.getScheduledList().get(1));
        assertEquals(3, firstReady.getVmId());
        assertEquals(9, secondReady.getVmId());
        assertEquals(-1, thirdReady.getVmId(), "FCFS must leave later Jobs ready when no VM remains idle");
    }

    @Test
    void skipsIncompatibleVmsWithoutReorderingReadyJobs() {
        Job twoPeFirst = job(5, 2);
        Job onePeSecond = job(6, 1);
        CondorVM onePeVm = vm(2, 1);
        CondorVM twoPeVm = vm(8, 2);

        FCFSSchedulingAlgorithm scheduler = new FCFSSchedulingAlgorithm();
        scheduler.setCloudletList(Arrays.<Cloudlet>asList(twoPeFirst, onePeSecond));
        scheduler.setVmList(Arrays.asList(onePeVm, twoPeVm));
        scheduler.run();

        assertEquals(Arrays.<Cloudlet>asList(twoPeFirst, onePeSecond), scheduler.getScheduledList());
        assertEquals(8, twoPeFirst.getVmId());
        assertEquals(2, onePeSecond.getVmId());
    }

    private static Job job(int id, int pes) {
        Job job = new Job(id, 1000L);
        job.setNumberOfPes(pes);
        return job;
    }

    private static CondorVM vm(int id, int pes) {
        return new CondorVM(id, 0, 1000.0, pes, 512, 1000L, 10_000L, "Xen",
                new CloudletSchedulerSpaceShared());
    }
}
