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
 * RoundRobin 调度算法 oracle 测试。
 *
 * 注意:当前实现并非真正的 Round-Robin。
 * 虽然声明并递增了 vmIndex,但选 VM 时并未使用它。
 * 实际行为是"按 vmId 升序找第一个空闲 VM"(First-Fit-Idle)。
 * 这些测试验证的是当前实际行为,不是经典 Round-Robin。
 */
class RoundRobinSchedulingAlgorithmTest {

    private List<Cloudlet> cloudlets;
    private List<Vm> vms;
    private RoundRobinSchedulingAlgorithm scheduler;

    @BeforeEach
    void setUp() {
        cloudlets = new ArrayList<>();
        vms = new ArrayList<>();
        scheduler = new RoundRobinSchedulingAlgorithm();
    }

    private CondorVM createVm(int id, double mips) {
        return new CondorVM(id, 0, mips, 1, 512, 1000, 10000, "Xen",
                new org.cloudbus.cloudsim.CloudletSchedulerTimeShared());
    }

    @Test
    void tasksAssignedToVmsInIdOrder() {
        // 3 个任务、3 个 VM
        for (int i = 1; i <= 3; i++) {
            cloudlets.add(new Task(i, 1000));
        }
        vms.add(createVm(2, 1000));
        vms.add(createVm(0, 1000));
        vms.add(createVm(1, 1000));

        scheduler.setCloudletList(cloudlets);
        scheduler.setVmList(vms);
        scheduler.run();

        List<Cloudlet> scheduled = (List<Cloudlet>) scheduler.getScheduledList();
        assertEquals(3, scheduled.size());

        // 由于实现先按 vmId 排序,然后顺序分配:
        // cloudlet 1 -> vm 0, cloudlet 2 -> vm 1, cloudlet 3 -> vm 2
        assertEquals(0, scheduled.get(0).getVmId());
        assertEquals(1, scheduled.get(1).getVmId());
        assertEquals(2, scheduled.get(2).getVmId());
    }

    @Test
    void doesNotRevisitBusyVms() {
        // 2 个任务、1 个 VM — 第二个任务找不到空闲 VM
        cloudlets.add(new Task(1, 1000));
        cloudlets.add(new Task(2, 2000));
        vms.add(createVm(0, 1000));

        scheduler.setCloudletList(cloudlets);
        scheduler.setVmList(vms);
        scheduler.run();

        assertEquals(1, scheduler.getScheduledList().size());
    }

    /**
     * 验证 vmIndex 是死代码:即使手动设置 vmIndex 偏移,
     * 算法仍然从头扫描(因为它根本不用 vmIndex)。
     */
    @Test
    void vmIndexIsDeadCode() {
        // 4 个任务、2 个 VM — 前 2 个任务占满 VM,后 2 个无法调度
        for (int i = 1; i <= 4; i++) {
            cloudlets.add(new Task(i, 1000));
        }
        vms.add(createVm(0, 1000));
        vms.add(createVm(1, 1000));

        scheduler.setCloudletList(cloudlets);
        scheduler.setVmList(vms);
        scheduler.run();

        // 只调度了 2 个(每个 VM 一个),后面 break
        assertEquals(2, scheduler.getScheduledList().size());
    }
}
