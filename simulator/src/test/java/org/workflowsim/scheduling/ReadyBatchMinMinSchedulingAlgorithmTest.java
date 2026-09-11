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
 * Ready-batch Min-Min oracle 测试。
 *
 * Ready-batch 定义:只对当前 idle VM 计算 ECT，并在一次更新中最多分配
 * 一项任务到每台 VM。
 *
 * 手算 oracle:3 任务 × 2 VM(异构)
 *   VM0: 500 MIPS, VM1: 1000 MIPS
 *   Task1: 2000 MI → ECT(VM0)=4.0, ECT(VM1)=2.0 → min=2.0 @VM1
 *   Task2: 1000 MI → ECT(VM0)=2.0, ECT(VM1)=1.0 → min=1.0 @VM1
 *   Task3: 3000 MI → ECT(VM0)=6.0, ECT(VM1)=3.0 → min=3.0 @VM1
 *
 *   全局最小 ECT = Task2 (1.0 @VM1) → 调度 Task2 到 VM1
 *   然后 VM1 BUSY,只剩 VM0:
 *   Task1: ECT(VM0)=4.0, Task3: ECT(VM0)=6.0 → 全局最小 Task1 (4.0 @VM0)
 *   最后 Task3 无法调度(无 idle VM)
 *
 *   调度顺序: Task2→VM1, Task1→VM0
 */
class ReadyBatchMinMinSchedulingAlgorithmTest {

    private List<Cloudlet> cloudlets;
    private List<Vm> vms;
    private ReadyBatchMinMinSchedulingAlgorithm scheduler;

    @BeforeEach
    void setUp() {
        cloudlets = new ArrayList<>();
        vms = new ArrayList<>();
        scheduler = new ReadyBatchMinMinSchedulingAlgorithm();
    }

    private CondorVM createVm(int id, double mips) {
        return new CondorVM(id, 0, mips, 1, 512, 1000, 10000, "Xen",
                new org.cloudbus.cloudsim.CloudletSchedulerTimeShared());
    }

    @Test
    void oracleThreeTasksTwoVms() {
        cloudlets.add(new Task(1, 2000));
        cloudlets.add(new Task(2, 1000));
        cloudlets.add(new Task(3, 3000));
        vms.add(createVm(0, 500));
        vms.add(createVm(1, 1000));

        scheduler.setCloudletList(cloudlets);
        scheduler.setVmList(vms);
        scheduler.run();

        List<Cloudlet> scheduled = (List<Cloudlet>) scheduler.getScheduledList();
        assertEquals(2, scheduled.size(), "2 VM 最多调度 2 任务");

        // 第一个:全局最小 ECT = Task2 (1000/1000=1.0 @VM1)
        assertEquals(2, scheduled.get(0).getCloudletId());
        assertEquals(1, scheduled.get(0).getVmId());

        // 第二个:VM1 BUSY,只剩 VM0;Task1(2000/500=4.0) < Task3(3000/500=6.0)
        assertEquals(1, scheduled.get(1).getCloudletId());
        assertEquals(0, scheduled.get(1).getVmId());
    }

    @Test
    void readyBatchMinMinPrefersShortestEct() {
        // 与 SPT 变体的关键区别:ready-batch Min-Min 比较的是 ECT(长度/mips),
        // 不是纯任务长度。短任务在快 VM 上 ECT 最小。
        cloudlets.add(new Task(1, 5000)); // 长任务
        cloudlets.add(new Task(2, 500));  // 短任务
        vms.add(createVm(0, 100));
        vms.add(createVm(1, 1000));

        scheduler.setCloudletList(cloudlets);
        scheduler.setVmList(vms);
        scheduler.run();

        List<Cloudlet> scheduled = (List<Cloudlet>) scheduler.getScheduledList();
        // Task2: ECT=500/1000=0.5 @VM1 → 全局最小 → 第一个调度到 VM1
        assertEquals(2, scheduled.get(0).getCloudletId());
        assertEquals(1, scheduled.get(0).getVmId());
    }

    @Test
    void allTasksScheduledWhenEnoughVms() {
        for (int i = 1; i <= 3; i++) {
            cloudlets.add(new Task(i, i * 1000));
        }
        for (int i = 0; i < 3; i++) {
            vms.add(createVm(i, 1000));
        }

        scheduler.setCloudletList(cloudlets);
        scheduler.setVmList(vms);
        scheduler.run();

        assertEquals(3, scheduler.getScheduledList().size());
    }

    @Test
    void stopsWhenNoIdleVm() {
        cloudlets.add(new Task(1, 1000));
        cloudlets.add(new Task(2, 2000));
        vms.add(createVm(0, 1000));

        scheduler.setCloudletList(cloudlets);
        scheduler.setVmList(vms);
        scheduler.run();

        assertEquals(1, scheduler.getScheduledList().size());
    }
}
