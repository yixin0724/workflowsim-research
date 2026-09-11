package org.workflowsim.scheduling;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Arrays;
import java.util.List;
import org.cloudbus.cloudsim.Cloudlet;
import org.cloudbus.cloudsim.CloudletSchedulerTimeShared;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.workflowsim.CondorVM;
import org.workflowsim.FileItem;
import org.workflowsim.Job;
import org.workflowsim.utils.Parameters.FileType;
import org.workflowsim.utils.ReplicaCatalog;

class DataAwareSchedulingAlgorithmTest {

    @AfterEach
    void resetReplicaCatalog() {
        ReplicaCatalog.reset();
    }

    @Test
    void selectsTheIdleVmWithTheFewestNonLocalInputBytes() {
        ReplicaCatalog.init(ReplicaCatalog.FileSystem.LOCAL);
        ReplicaCatalog.addFileToStorage("local-input", "9");
        Job job = job(1, "local-input", 100.0);
        DataAwareSchedulingAlgorithm scheduler = new DataAwareSchedulingAlgorithm();
        scheduler.setCloudletList(Arrays.<Cloudlet>asList(job));
        scheduler.setVmList(Arrays.asList(vm(2), vm(9)));

        scheduler.run();

        assertEquals(1, scheduler.getScheduledList().size());
        assertEquals(9, job.getVmId());
    }

    @Test
    void treatsMissingReplicaRecordsAsNonLocalAndBreaksTiesByVmId() {
        ReplicaCatalog.init(ReplicaCatalog.FileSystem.LOCAL);
        Job job = job(1, "unrecorded-input", 100.0);
        DataAwareSchedulingAlgorithm scheduler = new DataAwareSchedulingAlgorithm();
        scheduler.setCloudletList(Arrays.<Cloudlet>asList(job));
        scheduler.setVmList(Arrays.asList(vm(9), vm(2)));

        scheduler.run();

        assertEquals(1, scheduler.getScheduledList().size());
        assertEquals(2, job.getVmId());
    }

    private static Job job(int id, String inputName, double size) {
        Job job = new Job(id, 1000);
        FileItem input = new FileItem(inputName, size);
        input.setType(FileType.INPUT);
        job.addFile(input);
        return job;
    }

    private static CondorVM vm(int id) {
        return new CondorVM(id, 0, 1000.0, 1, 512, 1000, 10_000, "Xen",
                new CloudletSchedulerTimeShared());
    }
}
