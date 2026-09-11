package org.workflowsim.scheduling;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.cloudbus.cloudsim.Cloudlet;
import org.cloudbus.cloudsim.CloudletSchedulerSpaceShared;
import org.junit.jupiter.api.Test;
import org.workflowsim.CondorVM;
import org.workflowsim.Job;

/**
 * 回归测试（PLAT-6）：调度算法不得改写调用方传入的就绪队列。
 *
 * <p>背景：{@link BaseSchedulingAlgorithm#setCloudletList(List)} 历史上直接持有
 * 调度器就绪队列的真实引用，而 {@link FirstFitIdleSchedulingAlgorithm} 会在
 * {@code run()} 内按 Cloudlet ID 排序该列表——排序残留泄漏回调度器队列，
 * 破坏跨调度周期的到达序（FCFS 语义）。修复后 setCloudletList 做防御性复制，
 * 本测试固定该行为。</p>
 */
class SchedulingListIsolationTest {

    @Test
    void firstFitIdleSortingDoesNotLeakIntoCallerReadyList() {
        // 到达序刻意与 Cloudlet ID 升序相反：FirstFitIdle 内部会重排为 ID 升序。
        Job laterArrival = job(30);
        Job middleArrival = job(20);
        Job firstArrival = job(10);
        List<Cloudlet> callerReadyList = new ArrayList<Cloudlet>(
                Arrays.<Cloudlet>asList(laterArrival, middleArrival, firstArrival));

        FirstFitIdleSchedulingAlgorithm scheduler = new FirstFitIdleSchedulingAlgorithm();
        scheduler.setCloudletList(callerReadyList);
        scheduler.setVmList(Arrays.asList(vm(1), vm(2), vm(3)));
        scheduler.run();

        // 调用方列表必须保持原始到达序，不受算法内部排序影响。
        assertEquals(Arrays.<Cloudlet>asList(laterArrival, middleArrival, firstArrival),
                callerReadyList,
                "Algorithm-side sorting must not mutate the caller's ready list");

        // 算法自身仍按 ID 升序派发（FirstFitIdle 的既定语义）。
        assertEquals(3, scheduler.getScheduledList().size());
        assertEquals(firstArrival, scheduler.getScheduledList().get(0));
        assertEquals(middleArrival, scheduler.getScheduledList().get(1));
        assertEquals(laterArrival, scheduler.getScheduledList().get(2));
    }

    @Test
    void firstFitIdleVmSortingDoesNotLeakIntoCallerVmList() {
        Job job = job(10);
        CondorVM higherId = vm(9);
        CondorVM lowerId = vm(3);
        List<CondorVM> callerVmList = new ArrayList<CondorVM>(Arrays.asList(higherId, lowerId));

        FirstFitIdleSchedulingAlgorithm scheduler = new FirstFitIdleSchedulingAlgorithm();
        scheduler.setCloudletList(Arrays.<Cloudlet>asList(job));
        scheduler.setVmList(callerVmList);
        scheduler.run();

        assertEquals(Arrays.asList(higherId, lowerId), callerVmList,
                "Algorithm-side VM sorting must not mutate the caller's VM list");
        // FirstFitIdle 按 VM ID 升序选第一个空闲 VM。
        assertEquals(3, job.getVmId());
    }

    private static Job job(int id) {
        Job job = new Job(id, 1000L);
        job.setNumberOfPes(1);
        return job;
    }

    private static CondorVM vm(int id) {
        return new CondorVM(id, 0, 1000.0, 1, 512, 1000L, 10_000L, "Xen",
                new CloudletSchedulerSpaceShared());
    }
}
