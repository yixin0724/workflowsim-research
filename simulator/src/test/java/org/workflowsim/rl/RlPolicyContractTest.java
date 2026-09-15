package org.workflowsim.rl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.List;
import org.cloudbus.cloudsim.CloudletSchedulerSpaceShared;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.workflowsim.CondorVM;
import org.workflowsim.Job;
import org.workflowsim.WorkflowSimTags;
import org.workflowsim.exception.SimulationConfigurationException;

/**
 * R4 RL 轨道的单元契约：观测快照结构、贪心基线策略行为与注册表生命周期。
 * 全部断言基于手工构造的合成状态（真实 Job/CondorVM，无需仿真运行）。
 */
class RlPolicyContractTest {

    @AfterEach
    void resetRegistry() {
        RlPolicyRegistry.reset();
    }

    @Test
    void observationPreservesJobOrderAndSortsVmsById() {
        // VM 以乱序 ID 传入，观测必须按 ID 升序；Job 保持到达序。
        List<CondorVM> vms = Arrays.asList(
                vm(2, 1.0, true), vm(0, 2.0, false), vm(1, 4.0, true));
        List<Job> jobs = Arrays.asList(job(7, 100L), job(3, 50L));
        RlObservation observation = RlObservation.of(jobs, vms);
        assertEquals(7, observation.getReadyJobs().get(0).getJobId(), "Job 保持到达序");
        assertEquals(3, observation.getReadyJobs().get(1).getJobId());
        assertEquals(0, observation.getVms().get(0).getVmId(), "VM 按 ID 升序");
        assertEquals(1, observation.getVms().get(1).getVmId());
        assertEquals(2, observation.getVms().get(2).getVmId());
        assertEquals(4.0, observation.getVms().get(1).getMips(), 0.0);
        assertFalse(observation.getVms().get(0).isIdle(), "VM0 忙");
        assertTrue(observation.getVms().get(2).isIdle(), "VM2 闲");
        assertEquals(100L, observation.getReadyJobs().get(0).getTotalLength());
    }

    @Test
    void greedyPolicyPicksEarliestFinishIdleCompatibleVm() {
        // 1 个 Job（100 MI，1 PE）：VM0 mips=2（ECT 50）、VM1 mips=4（ECT 25）、
        // VM2 mips=1（ECT 100）→ 必须选 VM1（观测下标 1）。
        RlObservation observation = RlObservation.of(
                Arrays.asList(job(1, 100L)),
                Arrays.asList(vm(0, 2.0, true), vm(1, 4.0, true), vm(2, 1.0, true)));
        int[] actions = new EarliestFinishGreedyPolicy().selectVms(observation);
        assertEquals(1, actions.length);
        assertEquals(1, actions[0], "应选择 ECT 最小的 VM1");
    }

    @Test
    void greedyPolicySkipsBusyAndIncompatibleVms() {
        // VM0 忙（最快）、VM1 闲但 PE 不足、VM2 闲兼容 → 只能选 VM2。
        Job job = job(1, 100L);
        job.setNumberOfPes(2);
        CondorVM busyFast = vm(0, 4.0, false);
        CondorVM smallPes = vmWithPes(1, 8.0, true, 1);
        CondorVM compatible = vmWithPes(2, 1.0, true, 2);
        RlObservation observation = RlObservation.of(
                Arrays.asList(job), Arrays.asList(busyFast, smallPes, compatible));
        int[] actions = new EarliestFinishGreedyPolicy().selectVms(observation);
        assertEquals(2, actions[0], "忙/不兼容 VM 必须被跳过");
    }

    @Test
    void greedyPolicyReservesAtMostOneJobPerVmPerBatch() {
        // 2 个 Job、仅 1 台空闲 VM：第一个 Job 占用它，第二个 Job 不分配。
        RlObservation observation = RlObservation.of(
                Arrays.asList(job(1, 50L), job(2, 80L)),
                Arrays.asList(vm(0, 1.0, true), vm(1, 1.0, false)));
        int[] actions = new EarliestFinishGreedyPolicy().selectVms(observation);
        assertEquals(0, actions[0], "首个 Job 占用唯一空闲 VM");
        assertEquals(RlPolicy.NO_ASSIGNMENT, actions[1], "无 VM 可用的 Job 不分配");
    }

    @Test
    void greedyPolicyTiesChooseLowerVmId() {
        // 两台同速空闲 VM：等 ECT 选较小 VM ID（观测下标 0）。
        RlObservation observation = RlObservation.of(
                Arrays.asList(job(1, 100L)),
                Arrays.asList(vm(0, 2.0, true), vm(1, 2.0, true)));
        int[] actions = new EarliestFinishGreedyPolicy().selectVms(observation);
        assertEquals(0, actions[0], "等 ECT 必须选较小 VM ID");
    }

    @Test
    void registryLifecycleCarriesPolicyAndTrace() {
        // 未注册时 current() 显式失败并指向 RlEnvironment。
        SimulationConfigurationException missing = assertThrows(
                SimulationConfigurationException.class, RlPolicyRegistry::current);
        assertTrue(missing.getMessage().contains("RlEnvironment"), missing.getMessage());
        // 注册后 current 返回同一实例；决策与步数独立计数；reset 清空。
        RlPolicy policy = observation -> new int[observation.getReadyJobs().size()];
        RlPolicyRegistry.init(policy);
        assertEquals(policy, RlPolicyRegistry.current());
        RlPolicyRegistry.recordStep();
        RlPolicyRegistry.recordDecision(1.5, 7, 2);
        assertEquals(1, RlPolicyRegistry.stepCount(), "步数只计策略查询");
        assertEquals(1, RlPolicyRegistry.decisions().size());
        assertEquals(7, RlPolicyRegistry.decisions().get(0).getJobId());
        assertEquals(2, RlPolicyRegistry.decisions().get(0).getVmId());
        assertEquals(1.5, RlPolicyRegistry.decisions().get(0).getTime(), 0.0);
        assertThrows(UnsupportedOperationException.class,
                () -> RlPolicyRegistry.decisions().add(null), "轨迹对外不可变");
        RlPolicyRegistry.reset();
        assertThrows(SimulationConfigurationException.class, RlPolicyRegistry::current);
        assertEquals(0, RlPolicyRegistry.stepCount());
        assertTrue(RlPolicyRegistry.decisions().isEmpty());
        // init(null) 拒绝。
        assertThrows(SimulationConfigurationException.class, () -> RlPolicyRegistry.init(null));
    }

    private static Job job(int id, long length) {
        return new Job(id, length);
    }

    private static CondorVM vm(int id, double mips, boolean idle) {
        return vmWithPes(id, mips, idle, 1);
    }

    private static CondorVM vmWithPes(int id, double mips, boolean idle, int pes) {
        CondorVM vm = new CondorVM(id, 0, mips, pes, 512, 1L, 10_000L, "Xen",
                new CloudletSchedulerSpaceShared());
        vm.setState(idle ? WorkflowSimTags.VM_STATUS_IDLE : WorkflowSimTags.VM_STATUS_BUSY);
        return vm;
    }
}
