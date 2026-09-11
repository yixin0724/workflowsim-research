package org.workflowsim.platform;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Calendar;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.cloudbus.cloudsim.Host;
import org.cloudbus.cloudsim.CloudletSchedulerTimeShared;
import org.cloudbus.cloudsim.core.CloudSim;
import org.junit.jupiter.api.Test;
import org.workflowsim.CondorVM;
import org.workflowsim.WorkflowDatacenter;

class PlatformProfileTest {

    @Test
    void factoryCreatesUniqueHostAndVmIdentities() throws Exception {
        CloudSim.init(1, Calendar.getInstance(), false);
        PlatformProfile profile = PlatformProfiles.homogeneousLocal("test-platform", 3);
        WorkflowDatacenter datacenter = PlatformFactory.createDatacenter("test-datacenter", profile);
        List<CondorVM> vms = PlatformFactory.createVms(profile, 99);

        Set<Integer> hostIds = new HashSet<>();
        for (Host host : datacenter.getHostList()) {
            hostIds.add(host.getId());
        }
        Set<Integer> vmIds = new HashSet<>();
        for (CondorVM vm : vms) {
            vmIds.add(vm.getId());
        }
        assertEquals(3, hostIds.size());
        assertEquals(3, vmIds.size());
    }

    @Test
    void profileRejectsDuplicateAndUnplaceableResources() {
        assertThrows(IllegalArgumentException.class, () -> PlatformProfile.builder("duplicates")
                .addHost(new PlatformProfile.HostSpec(0, 1, 1000.0, 512, 1000L, 1000L))
                .addHost(new PlatformProfile.HostSpec(0, 1, 1000.0, 512, 1000L, 1000L))
                .addVm(new PlatformProfile.VmSpec(0, 1000.0, 1, 512, 1000L, 1000L,
                        "Xen", PlatformProfile.CloudletSchedulerMode.SPACE_SHARED))
                .build());
        assertThrows(IllegalArgumentException.class, () -> PlatformProfile.builder("unplaceable")
                .addHost(new PlatformProfile.HostSpec(0, 1, 1000.0, 512, 1000L, 1000L))
                .addVm(new PlatformProfile.VmSpec(0, 2000.0, 1, 512, 1000L, 1000L,
                        "Xen", PlatformProfile.CloudletSchedulerMode.SPACE_SHARED))
                .build());
        assertThrows(IllegalArgumentException.class, () -> PlatformProfile.builder("aggregate-overcommit")
                .addHost(new PlatformProfile.HostSpec(0, 2, 1000.0, 1024, 1000L, 10_000L))
                .addVm(new PlatformProfile.VmSpec(0, 1000.0, 1, 768, 500L, 1000L,
                        "Xen", PlatformProfile.CloudletSchedulerMode.SPACE_SHARED))
                .addVm(new PlatformProfile.VmSpec(1, 1000.0, 1, 768, 500L, 1000L,
                        "Xen", PlatformProfile.CloudletSchedulerMode.SPACE_SHARED))
                .build());
        assertThrows(IllegalArgumentException.class, () -> PlatformProfile.builder("pinned-overcommit")
                .addHost(new PlatformProfile.HostSpec(0, 1, 1000.0, 1024, 1000L, 10_000L))
                .addVm(new PlatformProfile.VmSpec(0, 1000.0, 1, 512, 500L, 1000L,
                        "Xen", PlatformProfile.CloudletSchedulerMode.SPACE_SHARED))
                .addVm(new PlatformProfile.VmSpec(1, 1000.0, 1, 512, 500L, 1000L,
                        "Xen", PlatformProfile.CloudletSchedulerMode.SPACE_SHARED))
                .pinVmToHost(0, 0)
                .pinVmToHost(1, 0)
                .build());
    }

    @Test
    void profilePreflightsPinnedAndDefaultVmHostAssignments() {
        PlatformProfile profile = PlatformProfile.builder("pinned-placement")
                .addHost(new PlatformProfile.HostSpec(0, 2, 1000.0, 2048, 2000L, 10_000L))
                .addHost(new PlatformProfile.HostSpec(1, 1, 1000.0, 1024, 1000L, 10_000L))
                .addVm(new PlatformProfile.VmSpec(0, 1000.0, 1, 512, 500L, 1000L,
                        "Xen", PlatformProfile.CloudletSchedulerMode.SPACE_SHARED))
                .addVm(new PlatformProfile.VmSpec(1, 1000.0, 1, 512, 500L, 1000L,
                        "Xen", PlatformProfile.CloudletSchedulerMode.SPACE_SHARED))
                .pinVmToHost(1, 1)
                .build();

        assertEquals(Integer.valueOf(1), profile.getPinnedVmHostIds().get(1));
        assertEquals(Integer.valueOf(0), profile.getVmHostAssignments().get(0));
        assertEquals(Integer.valueOf(1), profile.getVmHostAssignments().get(1));
    }

    @Test
    void factoryPreservesTheRawTimeSharedVmSchedulerSelection() {
        PlatformProfile profile = PlatformProfile.builder("time-shared-factory")
                .addHost(new PlatformProfile.HostSpec(0, 1, 1000.0,
                        1024, 1_000L, 10_000L))
                .addVm(new PlatformProfile.VmSpec(0, 1000.0, 1, 512,
                        1_000L, 1_000L, "Xen",
                        PlatformProfile.CloudletSchedulerMode.TIME_SHARED))
                .build();

        List<CondorVM> vms = PlatformFactory.createVms(profile, 99);

        assertEquals(1, vms.size());
        org.junit.jupiter.api.Assertions.assertTrue(vms.get(0).getCloudletScheduler()
                instanceof CloudletSchedulerTimeShared);
    }
}
