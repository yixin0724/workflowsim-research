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
 * Ready-batch Round-Robin 测试。
 *
 * 该策略维护游标,每次分配后游标移到下一台 VM,循环。
 *
 * 手算 oracle:3 任务 × 2 VM
 *   Task1 → VM0 (游标 0→1)
 *   Task2 → VM1 (游标 1→0)
 *   Task3 → VM0? 不,VM0 BUSY → 游标扫描 VM1 → BUSY → 无 idle → break
 *
 *   结果:调度 2 个任务
 */
class ReadyBatchRoundRobinSchedulingAlgorithmTest {

    private List<Cloudlet> cloudlets;
    private List<Vm> vms;
    private ReadyBatchRoundRobinSchedulingAlgorithm scheduler;

    @BeforeEach
    void setUp() {
        cloudlets = new ArrayList<>();
        vms = new ArrayList<>();
        scheduler = new ReadyBatchRoundRobinSchedulingAlgorithm();
    }

    private CondorVM createVm(int id, double mips) {
        return new CondorVM(id, 0, mips, 1, 512, 1000, 10000, "Xen",
                new org.cloudbus.cloudsim.CloudletSchedulerTimeShared());
    }

    @Test
    void roundRobinAlternatesVms() {
        cloudlets.add(new Task(1, 1000));
        cloudlets.add(new Task(2, 1000));
        cloudlets.add(new Task(3, 1000));
        vms.add(createVm(0, 1000));
        vms.add(createVm(1, 1000));

        scheduler.setCloudletList(cloudlets);
        scheduler.setVmList(vms);
        scheduler.run();

        List<Cloudlet> scheduled = (List<Cloudlet>) scheduler.getScheduledList();
        assertEquals(2, scheduled.size(), "2 VM 最多调度 2 任务");

        // Task1 → VM0, Task2 → VM1
        assertEquals(0, scheduled.get(0).getVmId());
        assertEquals(1, scheduled.get(1).getVmId());
    }

    @Test
    void cursorWrapsAround() {
        // 3 个 VM,3 个任务
        for (int i = 1; i <= 3; i++) {
            cloudlets.add(new Task(i, 1000));
        }
        for (int i = 0; i < 3; i++) {
            vms.add(createVm(i, 1000));
        }

        scheduler.setCloudletList(cloudlets);
        scheduler.setVmList(vms);
        scheduler.run();

        List<Cloudlet> scheduled = (List<Cloudlet>) scheduler.getScheduledList();
        assertEquals(3, scheduled.size());
        // 游标轮转:VM0 → VM1 → VM2
        assertEquals(0, scheduled.get(0).getVmId());
        assertEquals(1, scheduled.get(1).getVmId());
        assertEquals(2, scheduled.get(2).getVmId());
    }

    @Test
    void cursorPersistsAcrossRuns() {
        // 第一次 run: 调度 1 个任务到 VM0,游标移到 VM1
        cloudlets.add(new Task(1, 1000));
        vms.add(createVm(0, 1000));
        vms.add(createVm(1, 1000));

        scheduler.setCloudletList(cloudlets);
        scheduler.setVmList(vms);
        scheduler.run();
        assertEquals(1, scheduler.getScheduledList().size());
        assertEquals(0, ((Cloudlet)scheduler.getScheduledList().get(0)).getVmId());

        // 第二次 run 复用同一策略实例:VM0 仍 BUSY,游标也应从 VM1 开始。
        List<Cloudlet> cloudlets2 = new ArrayList<>();
        cloudlets2.add(new Task(2, 1000));
        scheduler.getScheduledList().clear();
        scheduler.setCloudletList(cloudlets2);
        scheduler.setVmList(vms);
        scheduler.run();

        assertEquals(1, scheduler.getScheduledList().size());
        assertEquals(1, ((Cloudlet)scheduler.getScheduledList().get(0)).getVmId(),
                "复用同一策略时,游标应跨 ready-batch 保持并指向 VM1");
    }
}
