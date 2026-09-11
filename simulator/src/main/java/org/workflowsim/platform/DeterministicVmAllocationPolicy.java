package org.workflowsim.platform;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.cloudbus.cloudsim.Host;
import org.cloudbus.cloudsim.Vm;
import org.cloudbus.cloudsim.VmAllocationPolicySimple;

/** 在 CloudSim 创建 VM 时强制执行 {@link PlatformProfile} 的预检放置结果。 */
final class DeterministicVmAllocationPolicy extends VmAllocationPolicySimple {

    private final Map<Integer, Integer> hostByVmId;

    DeterministicVmAllocationPolicy(List<? extends Host> hosts,
            Map<Integer, Integer> assignments) {
        super(hosts);
        this.hostByVmId = new LinkedHashMap<Integer, Integer>(assignments);
    }

    @Override
    public boolean allocateHostForVm(Vm vm) {
        if (getHost(vm) != null) {
            return true;
        }
        Integer hostId = hostByVmId.get(vm.getId());
        if (hostId == null) {
            throw new IllegalArgumentException("No declared Host assignment for VM " + vm.getId());
        }
        for (Host host : getHostList()) {
            if (host.getId() == hostId.intValue()) {
                return super.allocateHostForVm(vm, host);
            }
        }
        throw new IllegalStateException("Declared Host " + hostId + " is unavailable for VM " + vm.getId());
    }
}
