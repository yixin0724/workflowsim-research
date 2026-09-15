package org.workflowsim.scheduling;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.cloudbus.cloudsim.Cloudlet;
import org.cloudbus.cloudsim.core.CloudSim;
import org.workflowsim.CondorVM;
import org.workflowsim.WorkflowSimTags;
import org.workflowsim.exception.SimulationConfigurationException;
import org.workflowsim.rl.RlObservation;
import org.workflowsim.rl.RlPolicy;
import org.workflowsim.rl.RlPolicyRegistry;

/**
 * R4 RL 轨道的策略驱动调度器：每次 ready-batch 更新向已注册的
 * {@link RlPolicy} 请求 Job→VM 动作并执行。
 *
 * <p>执行契约与 ready-batch 调度器一致——只考虑当前就绪 Job，一次更新内每台
 * VM 至多接收一个 Job。策略动作按观测中的 Job 到达序与 VM ID 升序解释；
 * 动作指向忙/不兼容/本轮已占用 VM 时跳过该 Job（保持就绪，下次更新重新决策）。
 * 策略有义务最终给出可执行动作；持续跳过导致的停滞由平台看门狗显式拦截。</p>
 *
 * <p>策略实例经 {@link RlPolicyRegistry} 送达（调度器按枚举构造，无构造器通道），
 * 决策轨迹同样写回注册表供 episode 收集。无注册策略时以
 * {@link SimulationConfigurationException} 显式失败——RL_POLICY 必须经
 * {@code RlEnvironment} 运行。</p>
 */
public class RlPolicySchedulingAlgorithm extends BaseSchedulingAlgorithm {

    @Override
    public void run() throws Exception {
        validateCloudletCompatibility();
        List<? extends Cloudlet> readyJobs = getCloudletList();
        if (readyJobs.isEmpty()) {
            return;
        }
        List<CondorVM> vms = sortedVmsById();
        RlPolicy policy = RlPolicyRegistry.current();
        RlObservation observation = RlObservation.of(readyJobs, vms);
        RlPolicyRegistry.recordStep();
        int[] actions = policy.selectVms(observation);
        if (actions == null || actions.length != readyJobs.size()) {
            throw new IllegalStateException("RL policy must return exactly "
                    + readyJobs.size() + " action(s), got "
                    + (actions == null ? "null" : actions.length));
        }
        double decisionTime = CloudSim.clock();
        Set<Integer> usedVmIndices = new HashSet<Integer>();
        for (int j = 0; j < readyJobs.size(); j++) {
            int vmIndex = actions[j];
            if (vmIndex == RlPolicy.NO_ASSIGNMENT) {
                continue;
            }
            if (vmIndex < 0 || vmIndex >= vms.size()) {
                throw new IllegalStateException("RL policy returned out-of-range VM index "
                        + vmIndex + " (valid range [0, " + vms.size() + "))");
            }
            if (usedVmIndices.contains(vmIndex)) {
                continue;
            }
            Cloudlet cloudlet = readyJobs.get(j);
            CondorVM vm = vms.get(vmIndex);
            if (vm.getState() != WorkflowSimTags.VM_STATUS_IDLE || !isCompatible(cloudlet, vm)) {
                continue;
            }
            vm.setState(WorkflowSimTags.VM_STATUS_BUSY);
            cloudlet.setVmId(vm.getId());
            getScheduledList().add(cloudlet);
            usedVmIndices.add(vmIndex);
            RlPolicyRegistry.recordDecision(decisionTime, cloudlet.getCloudletId(), vm.getId());
        }
    }
}
