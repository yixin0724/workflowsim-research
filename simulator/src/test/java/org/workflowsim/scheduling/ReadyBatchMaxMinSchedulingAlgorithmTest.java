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
 * 经典 Max-Min oracle 测试。
 *
 * 经典定义(Braun 2001):对每个任务计算最小 ECT,从中选全局最大者。
 * 与 Min-Min 的区别:Max-Min 先调度大任务。
 *
 * 手算 oracle:3 任务 × 2 VM(异构)
 *   VM0: 500 MIPS, VM1: 1000 MIPS
 *   Task1: 2000 MI → minECT=2.0 @VM1
 *   Task2: 1000 MI → minECT=1.0 @VM1
 *   Task3: 3000 MI → minECT=3.0 @VM1
 *
 *   Max-Min 选全局最大 minECT = Task3 (3.0) → 调度到 VM1
 *   然后 VM1 BUSY,只剩 VM0:
 *   Task1: ECT(VM0)=4.0, Task2: ECT(VM0)=2.0 → 最大 Task1 (4.0) → VM0
 *
 *   调度顺序: Task3→VM1, Task1→VM0
 */
class ReadyBatchMaxMinSchedulingAlgorithmTest {

    private List<Cloudlet> cloudlets;
    private List<Vm> vms;
    private ReadyBatchMaxMinSchedulingAlgorithm scheduler;

    @BeforeEach
    void setUp() {
        cloudlets = new ArrayList<>();
        vms = new ArrayList<>();
        scheduler = new ReadyBatchMaxMinSchedulingAlgorithm();
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
        assertEquals(2, scheduled.size());

        // Max-Min 先调度大任务:Task3 (minECT=3.0) → VM1
        assertEquals(3, scheduled.get(0).getCloudletId());
        assertEquals(1, scheduled.get(0).getVmId());

        // 然后 VM1 BUSY,只剩 VM0;Task1(4.0) > Task2(2.0) → Task1 → VM0
        assertEquals(1, scheduled.get(1).getCloudletId());
        assertEquals(0, scheduled.get(1).getVmId());
    }

    @Test
    void maxMinPrefersLongTaskFirst() {
        // 与 Min-Min 的关键区别:Max-Min 先调度大任务
        cloudlets.add(new Task(1, 500));
        cloudlets.add(new Task(2, 5000));
        vms.add(createVm(0, 1000));
        vms.add(createVm(1, 1000));

        scheduler.setCloudletList(cloudlets);
        scheduler.setVmList(vms);
        scheduler.run();

        List<Cloudlet> scheduled = (List<Cloudlet>) scheduler.getScheduledList();
        assertEquals(2, scheduled.get(0).getCloudletId(), "Max-Min 应先调度长任务");
    }
}
