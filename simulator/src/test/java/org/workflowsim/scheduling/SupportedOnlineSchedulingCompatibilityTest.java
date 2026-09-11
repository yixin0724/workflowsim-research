package org.workflowsim.scheduling;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Arrays;
import java.util.List;
import java.util.function.Supplier;
import org.cloudbus.cloudsim.Cloudlet;
import org.cloudbus.cloudsim.CloudletSchedulerTimeShared;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.workflowsim.CondorVM;
import org.workflowsim.Job;
import org.workflowsim.utils.ReplicaCatalog;

class SupportedOnlineSchedulingCompatibilityTest {

    @AfterEach
    void resetReplicaCatalog() {
        ReplicaCatalog.reset();
    }

    @Test
    void everySupportedOnlineSchedulerExcludesIncompatibleVmsAndUsesVmIdForEqualChoices()
            throws Exception {
        for (SchedulerFactory factory : supportedSchedulers()) {
            ReplicaCatalog.init(ReplicaCatalog.FileSystem.LOCAL);
            Job job = job(10, 1);
            BaseSchedulingAlgorithm scheduler = factory.create();
            scheduler.setCloudletList(Arrays.<Cloudlet>asList(job));
            scheduler.setVmList(Arrays.asList(vm(9, 1000.0, 1), vm(2, 1000.0, 1)));

            scheduler.run();

            assertEquals(1, scheduler.getScheduledList().size(), factory.name);
            assertEquals(2, job.getVmId(), factory.name);
            ReplicaCatalog.reset();
        }
    }

    @Test
    void everySupportedOnlineSchedulerMapsMultiPeJobsOnlyToCompatibleVms() throws Exception {
        for (SchedulerFactory factory : supportedSchedulers()) {
            ReplicaCatalog.init(ReplicaCatalog.FileSystem.LOCAL);
            Job job = job(10, 2);
            BaseSchedulingAlgorithm scheduler = factory.create();
            scheduler.setCloudletList(Arrays.<Cloudlet>asList(job));
            scheduler.setVmList(Arrays.asList(vm(2, 1000.0, 1), vm(9, 500.0, 2)));

            scheduler.run();

            assertEquals(1, scheduler.getScheduledList().size(), factory.name);
            assertEquals(9, job.getVmId(), factory.name);
            ReplicaCatalog.reset();
        }
    }

    @Test
    void everySupportedOnlineSchedulerFailsFastWhenNoVmCanRunTheReadyJob() {
        for (SchedulerFactory factory : supportedSchedulers()) {
            ReplicaCatalog.init(ReplicaCatalog.FileSystem.LOCAL);
            Job job = job(10, 2);
            BaseSchedulingAlgorithm scheduler = factory.create();
            scheduler.setCloudletList(Arrays.<Cloudlet>asList(job));
            scheduler.setVmList(Arrays.asList(vm(2, 1000.0, 1)));

            IllegalArgumentException exception = assertThrows(IllegalArgumentException.class, scheduler::run,
                    factory.name);

            assertEquals("Ready job 10 requires 2 processing element(s), but no declared VM is compatible",
                    exception.getMessage(), factory.name);
            ReplicaCatalog.reset();
        }
    }

    private static List<SchedulerFactory> supportedSchedulers() {
        return Arrays.asList(
                new SchedulerFactory("FCFS", FCFSSchedulingAlgorithm::new),
                new SchedulerFactory("READY_BATCH_MINMIN", ReadyBatchMinMinSchedulingAlgorithm::new),
                new SchedulerFactory("READY_BATCH_MAXMIN", ReadyBatchMaxMinSchedulingAlgorithm::new),
                new SchedulerFactory("READY_BATCH_MCT", ReadyBatchMCTSchedulingAlgorithm::new),
                new SchedulerFactory("READY_BATCH_ROUNDROBIN", ReadyBatchRoundRobinSchedulingAlgorithm::new),
                new SchedulerFactory("DATA", DataAwareSchedulingAlgorithm::new));
    }

    private static Job job(int id, int pes) {
        Job job = new Job(id, 1000);
        job.setNumberOfPes(pes);
        return job;
    }

    private static CondorVM vm(int id, double mips, int pes) {
        return new CondorVM(id, 0, mips, pes, 512, 1000, 10_000, "Xen",
                new CloudletSchedulerTimeShared());
    }

    private static final class SchedulerFactory {
        private final String name;
        private final Supplier<BaseSchedulingAlgorithm> supplier;

        private SchedulerFactory(String name, Supplier<BaseSchedulingAlgorithm> supplier) {
            this.name = name;
            this.supplier = supplier;
        }

        private BaseSchedulingAlgorithm create() {
            return supplier.get();
        }
    }
}
