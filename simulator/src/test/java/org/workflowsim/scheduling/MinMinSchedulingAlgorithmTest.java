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
import org.workflowsim.WorkflowSimTags;

/**
 * MinMin 调度算法 oracle 测试。
 *
 * 注意:当前实现是"在线 SPT-fastest-idle"变体,不是经典 Min-Min。
 * 经典 Min-Min:先计算每个任务在所有 VM 上的最小完成时间(MCT),
 * 再从候选中选全局最小者。当前实现:先选最短任务,再选最快空闲 VM。
 * 这些测试验证的是当前实际行为,不是经典定义。
 */
class MinMinSchedulingAlgorithmTest {

    private List<Cloudlet> cloudlets;
    private List<Vm> vms;
    private MinMinSchedulingAlgorithm scheduler;

    @BeforeEach
    void setUp() {
        cloudlets = new ArrayList<>();
        vms = new ArrayList<>();
        scheduler = new MinMinSchedulingAlgorithm();
    }

    private CondorVM createVm(int id, double mips) {
        return new CondorVM(id, 0, mips, 1, 512, 1000, 10000, "Xen",
                new org.cloudbus.cloudsim.CloudletSchedulerTimeShared());
    }

    @Test
    void shortestTaskGoesToFastestIdleVm() {
        // 2 个任务、2 个 VM:一次 run() 只能调度 min(tasks, vms) 个
        // 因为 VM 分配后标记 BUSY,后续任务找不到 idle VM 就 break
        Task short_ = new Task(1, 1000);
        Task long_  = new Task(2, 3000);
        cloudlets.add(long_);   // 故意不按长度顺序放入
        cloudlets.add(short_);

        // 2 个 VM:慢(500 MIPS)、快(1000 MIPS)
        CondorVM slow = createVm(0, 500);
        CondorVM fast = createVm(1, 1000);
        vms.add(slow);
        vms.add(fast);

        scheduler.setCloudletList(cloudlets);
        scheduler.setVmList(vms);
        scheduler.run();

        // 2 个任务 2 个 VM,都能调度
        assertEquals(2, scheduler.getScheduledList().size());
        // 第一个被调度的应该是最短任务(id=1, 1000 MI),分配到最快 VM(id=1)
        Cloudlet first = (Cloudlet) scheduler.getScheduledList().get(0);
        assertEquals(1, first.getCloudletId(), "第一个被调度的应是最短任务");
        assertEquals(1, first.getVmId(), "最短任务应分配到最快 VM");
        // 第二个是最长任务,分配到剩余的最快 idle VM
        Cloudlet second = (Cloudlet) scheduler.getScheduledList().get(1);
        assertEquals(2, second.getCloudletId());
        assertEquals(0, second.getVmId(), "最长任务分配到剩余 idle VM(500 MIPS)");
    }

    @Test
    void scheduledCountIsCappedByVmCount() {
        // 5 个任务、3 个 VM → run() 最多调度 3 个
        for (int i = 1; i <= 5; i++) {
            cloudlets.add(new Task(i, i * 1000));
        }
        vms.add(createVm(0, 1000));
        vms.add(createVm(1, 1000));
        vms.add(createVm(2, 1000));

        scheduler.setCloudletList(cloudlets);
        scheduler.setVmList(vms);
        scheduler.run();

        // 前 3 个最短的任务被调度,第 4 个找不到 idle VM 时 break
        assertEquals(3, scheduler.getScheduledList().size());
    }

    @Test
    void stopsWhenNoIdleVm() {
        // 3 个任务但只有 1 个 VM → run() 只调度 1 个就 break
        cloudlets.add(new Task(1, 1000));
        cloudlets.add(new Task(2, 2000));
        cloudlets.add(new Task(3, 3000));
        vms.add(createVm(0, 1000));

        scheduler.setCloudletList(cloudlets);
        scheduler.setVmList(vms);
        scheduler.run();

        // VM 被标记 BUSY 后,后续任务找不到 idle VM,break
        assertEquals(1, scheduler.getScheduledList().size());
    }

    @Test
    void taskOrderIsShortestFirst() {
        // 5 个任务、5 个 VM → 全部可调度,验证调度顺序按任务长度升序
        cloudlets.add(new Task(1, 5000));
        cloudlets.add(new Task(2, 1000));
        cloudlets.add(new Task(3, 3000));
        cloudlets.add(new Task(4, 2000));
        cloudlets.add(new Task(5, 4000));
        vms.add(createVm(0, 1000));
        vms.add(createVm(1, 1000));
        vms.add(createVm(2, 1000));
        vms.add(createVm(3, 1000));
        vms.add(createVm(4, 1000));

        scheduler.setCloudletList(cloudlets);
        scheduler.setVmList(vms);
        scheduler.run();

        List<Cloudlet> scheduled = (List<Cloudlet>) scheduler.getScheduledList();
        // 调度顺序应该按任务长度升序: 1000, 2000, 3000, 4000, 5000
        assertEquals(2, scheduled.get(0).getCloudletId()); // 1000 MI
        assertEquals(4, scheduled.get(1).getCloudletId()); // 2000 MI
        assertEquals(3, scheduled.get(2).getCloudletId()); // 3000 MI
        assertEquals(5, scheduled.get(3).getCloudletId()); // 4000 MI
        assertEquals(1, scheduled.get(4).getCloudletId()); // 5000 MI
    }
}
