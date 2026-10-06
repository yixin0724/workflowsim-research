package org.workflowsim.scheduling;

import static org.junit.jupiter.api.Assertions.*;
import java.util.Collections;
import org.cloudbus.cloudsim.CloudletSchedulerSpaceShared;
import org.junit.jupiter.api.Test;
import org.workflowsim.CondorVM;
import org.workflowsim.Job;
import org.workflowsim.Task;
import org.workflowsim.utils.Parameters;

class OnlineDataflowStaticGuardTest {
    @Test void invalidComputeMappingDoesNotFallBackAfterDataPreparation()throws Exception{
        Job job=compute(-1,1);StaticSchedulingAlgorithm scheduler=guarded(job);assertThrows(IllegalStateException.class,scheduler::run);assertEquals(-1,job.getVmId());assertTrue(scheduler.getScheduledList().isEmpty());
    }
    @Test void taskAndJobMappingMustAgree()throws Exception{
        Job job=compute(7,1);job.getTaskList().get(0).setVmId(42);StaticSchedulingAlgorithm scheduler=guarded(job);assertThrows(IllegalStateException.class,scheduler::run);assertEquals(7,job.getVmId());
    }
    @Test void computePesMustFitTheActualVm()throws Exception{
        Job job=compute(7,2);StaticSchedulingAlgorithm scheduler=guarded(job);assertThrows(IllegalStateException.class,scheduler::run);
    }
    @Test void validComputeAndLegacyStageInFallbackRemainSupported()throws Exception{
        Job compute=compute(7,1);StaticSchedulingAlgorithm scheduler=guarded(compute);scheduler.run();assertEquals(1,scheduler.getScheduledList().size());
        Job stage=new Job(0,1);stage.setClassType(Parameters.ClassType.STAGE_IN.value);scheduler=guarded(stage);scheduler.run();assertEquals(7,stage.getVmId());assertEquals(1,scheduler.getScheduledList().size());
    }
    private static StaticSchedulingAlgorithm guarded(Job job){StaticSchedulingAlgorithm scheduler=new StaticSchedulingAlgorithm();scheduler.requireDataflowBindings();scheduler.setVmList(Collections.singletonList(new CondorVM(7,0,1000,1,512,1000,10000,"Xen",new CloudletSchedulerSpaceShared())));scheduler.setCloudletList(Collections.singletonList(job));return scheduler;}
    private static Job compute(int vm,int pes){Task task=new Task(1,1000);task.setVmId(vm);task.setNumberOfPes(pes);Job job=new Job(10,1000);job.setVmId(vm);job.setClassType(Parameters.ClassType.COMPUTE.value);job.setTaskList(Collections.singletonList(task));job.setNumberOfPes(pes);return job;}
}
