package org.workflowsim;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import org.cloudbus.cloudsim.Cloudlet;
import org.cloudbus.cloudsim.Vm;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.workflowsim.scheduling.MinMinSchedulingAlgorithm;
import org.workflowsim.scheduling.RoundRobinSchedulingAlgorithm;

/**
 * 重复运行一致性测试:同一 JVM 内,相同配置跑两次,结果必须逐位相同。
 * 这是可复现实验的基础保证。
 */
class ReproducibilityTest {

    private List<Cloudlet> buildCloudlets() {
        List<Cloudlet> list = new ArrayList<>();
        list.add(new Task(1, 5000));
        list.add(new Task(2, 1000));
        list.add(new Task(3, 3000));
        list.add(new Task(4, 2000));
        return list;
    }

    private List<Vm> buildVms() {
        List<Vm> list = new ArrayList<>();
        list.add(createVm(0, 500));
        list.add(createVm(1, 1000));
        list.add(createVm(2, 2000));
        list.add(createVm(3, 800));
        return list;
    }

    private CondorVM createVm(int id, double mips) {
        return new CondorVM(id, 0, mips, 1, 512, 1000, 10000, "Xen",
                new org.cloudbus.cloudsim.CloudletSchedulerTimeShared());
    }

    @Test
    void minMinSameInputSameOutput() {
        // 第一次运行
        MinMinSchedulingAlgorithm s1 = new MinMinSchedulingAlgorithm();
        List<Cloudlet> c1 = buildCloudlets();
        List<Vm> v1 = buildVms();
        s1.setCloudletList(c1);
        s1.setVmList(v1);
        s1.run();

        // 第二次运行(全新对象,但相同配置)
        MinMinSchedulingAlgorithm s2 = new MinMinSchedulingAlgorithm();
        List<Cloudlet> c2 = buildCloudlets();
        List<Vm> v2 = buildVms();
        s2.setCloudletList(c2);
        s2.setVmList(v2);
        s2.run();

        // 断言两次调度结果一致
        assertEquals(s1.getScheduledList().size(), s2.getScheduledList().size());
        for (int i = 0; i < s1.getScheduledList().size(); i++) {
            Cloudlet a = (Cloudlet) s1.getScheduledList().get(i);
            Cloudlet b = (Cloudlet) s2.getScheduledList().get(i);
            assertEquals(a.getCloudletId(), b.getCloudletId(),
                    "第 " + i + " 个调度的任务 ID 不一致");
            assertEquals(a.getVmId(), b.getVmId(),
                    "第 " + i + " 个调度的 VM 分配不一致");
        }
    }

    @Test
    void roundRobinSameInputSameOutput() {
        RoundRobinSchedulingAlgorithm s1 = new RoundRobinSchedulingAlgorithm();
        s1.setCloudletList(buildCloudlets());
        s1.setVmList(buildVms());
        s1.run();

        RoundRobinSchedulingAlgorithm s2 = new RoundRobinSchedulingAlgorithm();
        s2.setCloudletList(buildCloudlets());
        s2.setVmList(buildVms());
        s2.run();

        assertEquals(s1.getScheduledList().size(), s2.getScheduledList().size());
        for (int i = 0; i < s1.getScheduledList().size(); i++) {
            Cloudlet a = (Cloudlet) s1.getScheduledList().get(i);
            Cloudlet b = (Cloudlet) s2.getScheduledList().get(i);
            assertEquals(a.getCloudletId(), b.getCloudletId());
            assertEquals(a.getVmId(), b.getVmId());
        }
    }
}
