package org.workflowsim.failure;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.List;
import org.cloudbus.cloudsim.Cloudlet;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.workflowsim.Job;
import org.workflowsim.Task;
import org.workflowsim.reclustering.ReclusteringEngine;
import org.workflowsim.utils.Parameters.ClassType;

/** 验证故障监视器按声明的 VM/深度粒度隔离观测。 */
class FailureMonitorTest {

    @AfterEach
    void resetFailureState() {
        FailureMonitor.reset();
        FailureParameters.reset();
    }

    @Test
    void vmJobMonitorUsesTheVmAndDepthCompositeKey() {
        FailureParameters.init(FailureParameters.FTCluteringAlgorithm.FTCLUSTERING_NOOP,
                FailureParameters.FTCMonitor.MONITOR_VM_JOB,
                FailureParameters.FTCFailure.FAILURE_NONE, null);
        FailureMonitor.init();
        FailureMonitor.postFailureRecord(new FailureRecord(1.0, 2, 3, 4, 7, 10, 0));
        FailureMonitor.postFailureRecord(new FailureRecord(1.0, 0, 4, 2, 7, 11, 0));
        FailureMonitor.postFailureRecord(new FailureRecord(1.0, 0, 3, 3, 8, 12, 0));

        assertEquals(0.5, FailureMonitor.analyzeVmDepth(7, 3), 0.0);
        assertEquals(0.0, FailureMonitor.analyzeVmDepth(7, 4), 0.0);
        assertEquals(0.0, FailureMonitor.analyzeVmDepth(8, 3), 0.0);
        assertEquals(0.0, FailureMonitor.analyzeVmDepth(9, 3), 0.0);
        assertEquals(1, FailureMonitor.getClusteringFactor(
                new FailureRecord(1.0, 0, 3, 4, 7, 13, 0)));
    }

    @Test
    void dcRecoveryReadsTheActualFailedJobVmRatherThanVmZero() throws Exception {
        FailureParameters.init(FailureParameters.FTCluteringAlgorithm.FTCLUSTERING_DC,
                FailureParameters.FTCMonitor.MONITOR_VM,
                FailureParameters.FTCFailure.FAILURE_NONE, null);
        FailureMonitor.init();
        // VM 0 已有失败观测；VM 2 没有。DC 必须为 VM 2 保持一整个三任务重试簇。
        FailureMonitor.postFailureRecord(new FailureRecord(1.0, 1, 1, 3, 0, 0, 0));
        Job failed = computeJob(40, 2,
                task(1, 100L), task(2, 200L), task(3, 300L));

        List<Job> retries = ReclusteringEngine.process(failed, 100);

        assertEquals(1, retries.size());
        assertEquals(100, retries.get(0).getCloudletId());
        assertEquals(Arrays.asList(1, 2, 3), taskIds(retries.get(0)));
    }

    private static Job computeJob(int id, int vmId, Task... tasks) {
        Job job = new Job(id, 1L);
        assertTrue(job.setClassType(ClassType.COMPUTE.value));
        job.setUserId(0);
        job.setVmId(vmId);
        job.setDepth(1);
        job.addTaskList(Arrays.asList(tasks));
        return job;
    }

    private static Task task(int id, long length) throws Exception {
        Task task = new Task(id, length);
        task.setUserId(0);
        task.setDepth(1);
        task.setCloudletStatus(Cloudlet.FAILED);
        return task;
    }

    private static List<Integer> taskIds(Job job) {
        List<Integer> ids = new java.util.ArrayList<Integer>();
        for (Task task : job.getTaskList()) {
            ids.add(task.getCloudletId());
        }
        return ids;
    }
}
