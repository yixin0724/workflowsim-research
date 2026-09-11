package org.workflowsim.scheduling;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import org.cloudbus.cloudsim.Cloudlet;
import org.cloudbus.cloudsim.Vm;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.workflowsim.CondorVM;
import org.workflowsim.Task;

/**
 * 经典 MCT(Minimum Completion Time)oracle 测试。
 *
 * 经典定义(Maheswaran 1999):对每个任务,分配到完成时间最小的机器。
 * completionTime = length / mips。每个任务独立决策。
 *
 * 与旧实现(FastestVm)的关键区别:旧实现比较 VM 的 MIPS(选大者),
 * 经典 MCT 计算 length/mips(选小者)。对同构 VM 两者等价,
 * 对异构 VM 且不同长度任务时结果不同。
 */
class ReadyBatchMCTSchedulingAlgorithmTest {

    private List<Cloudlet> cloudlets;
    private List<Vm> vms;
    private ReadyBatchMCTSchedulingAlgorithm scheduler;

    @BeforeEach
    void setUp() {
        cloudlets = new ArrayList<>();
        vms = new ArrayList<>();
        scheduler = new ReadyBatchMCTSchedulingAlgorithm();
    }

    private CondorVM createVm(int id, double mips) {
        return new CondorVM(id, 0, mips, 1, 512, 1000, 10000, "Xen",
                new org.cloudbus.cloudsim.CloudletSchedulerTimeShared());
    }

    @Test
    void longTaskGoesToFastVm() {
        // 长任务在快 VM 上完成时间更小
        cloudlets.add(new Task(1, 5000)); // 长任务
        vms.add(createVm(0, 500));
        vms.add(createVm(1, 1000));

        scheduler.setCloudletList(cloudlets);
        scheduler.setVmList(vms);
        scheduler.run();

        assertEquals(1, scheduler.getScheduledList().size());
        Cloudlet scheduled = (Cloudlet) scheduler.getScheduledList().get(0);
        assertEquals(1, scheduled.getVmId(), "长任务应分配到快 VM(1000 MIPS)");
    }

    @Test
    void shortTaskAlsoGoesToFastVm() {
        // 短任务在快 VM 上完成时间也更小(MCT 总是选最快 VM 使 length/mips 最小)
        cloudlets.add(new Task(1, 100)); // 短任务
        vms.add(createVm(0, 500));
        vms.add(createVm(1, 1000));

        scheduler.setCloudletList(cloudlets);
        scheduler.setVmList(vms);
        scheduler.run();

        Cloudlet scheduled = (Cloudlet) scheduler.getScheduledList().get(0);
        // 100/500=0.2 vs 100/1000=0.1 → 选 VM1
        assertEquals(1, scheduled.getVmId());
    }

    @Test
    void eachTaskIndependentlySelectsBestVm() {
        // 两个任务、两个 VM:Task1 选最快,Task2 也选最快(但已被占)
        cloudlets.add(new Task(1, 1000));
        cloudlets.add(new Task(2, 2000));
        vms.add(createVm(0, 500));
        vms.add(createVm(1, 1000));

        scheduler.setCloudletList(cloudlets);
        scheduler.setVmList(vms);
        scheduler.run();

        List<Cloudlet> scheduled = (List<Cloudlet>) scheduler.getScheduledList();
        assertEquals(2, scheduled.size());
        // Task1: 1000/500=2.0 vs 1000/1000=1.0 → VM1
        assertEquals(1, scheduled.get(0).getVmId());
        // Task2: VM1 BUSY,只剩 VM0 → VM0
        assertEquals(0, scheduled.get(1).getVmId());
    }
}
