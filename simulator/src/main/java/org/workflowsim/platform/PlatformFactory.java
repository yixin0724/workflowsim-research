package org.workflowsim.platform;

import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import org.cloudbus.cloudsim.CloudletScheduler;
import org.cloudbus.cloudsim.CloudletSchedulerSpaceShared;
import org.cloudbus.cloudsim.CloudletSchedulerTimeShared;
import org.cloudbus.cloudsim.DatacenterCharacteristics;
import org.cloudbus.cloudsim.HarddriveStorage;
import org.cloudbus.cloudsim.Host;
import org.cloudbus.cloudsim.Pe;
import org.cloudbus.cloudsim.Storage;
import org.cloudbus.cloudsim.VmSchedulerTimeShared;
import org.cloudbus.cloudsim.provisioners.BwProvisionerSimple;
import org.cloudbus.cloudsim.provisioners.PeProvisionerSimple;
import org.cloudbus.cloudsim.provisioners.RamProvisionerSimple;
import org.workflowsim.CondorVM;
import org.workflowsim.WorkflowDatacenter;

/** 根据已校验的 {@link PlatformProfile} 构建 CloudSim/WorkflowSim 实体。 */
public final class PlatformFactory {

    private PlatformFactory() {
    }

    /**
     * 将抽象平台描述转换为一个 WorkflowSim 数据中心。
     *
     * @param entityName CloudSim 实体名称，不能为空
     * @param profile 已通过容量与 VM-Host 放置预检的平台描述
     * @return 使用确定性 VM 放置策略的数据中心
     * @throws Exception 当 CloudSim 存储实体无法创建时抛出
     * @throws IllegalArgumentException 当名称为空或平台描述为空时抛出
     */
    public static WorkflowDatacenter createDatacenter(String entityName, PlatformProfile profile)
            throws Exception {
        requireEntityName(entityName);
        if (profile == null) {
            throw new IllegalArgumentException("Platform profile cannot be null");
        }
        List<Host> hosts = new ArrayList<>();
        for (PlatformProfile.HostSpec spec : profile.getHosts()) {
            List<Pe> pes = new ArrayList<>();
            for (int peId = 0; peId < spec.getPes(); peId++) {
                pes.add(new Pe(peId, new PeProvisionerSimple(spec.getMipsPerPe())));
            }
            hosts.add(new Host(spec.getId(), new RamProvisionerSimple(spec.getRamMb()),
                    new BwProvisionerSimple(spec.getBandwidth()), spec.getStorageMb(), pes,
                    new VmSchedulerTimeShared(pes)));
        }

        PlatformProfile.CostSpec costs = profile.getCosts();
        DatacenterCharacteristics characteristics = new DatacenterCharacteristics(
                "x86", "Linux", "Xen", hosts, 10.0, costs.getCpuPerSecond(),
                costs.getMemory(), costs.getStorage(), costs.getBandwidth());
        PlatformProfile.StorageSpec storageSpec = profile.getStorage();
        List<Storage> storage = new LinkedList<>();
        HarddriveStorage disk = new HarddriveStorage(entityName, storageSpec.getCapacityMb());
        disk.setMaxTransferRate(storageSpec.getMaxTransferRateMbPerSecond());
        storage.add(disk);
        return new WorkflowDatacenter(entityName, characteristics,
                new DeterministicVmAllocationPolicy(hosts, profile.getVmHostAssignments()), storage, 0.0);
    }

    /**
     * 根据平台描述创建归属于指定 WorkflowEngine 用户的 VM 列表。
     *
     * @param profile 已校验的平台描述
     * @param userId CloudSim 中接收这些 VM 的用户/调度器标识
     * @return 与平台 VM 描述一一对应的新建 VM 列表
     * @throws IllegalArgumentException 当平台描述为空时抛出
     */
    public static List<CondorVM> createVms(PlatformProfile profile, int userId) {
        if (profile == null) {
            throw new IllegalArgumentException("Platform profile cannot be null");
        }
        List<CondorVM> vms = new ArrayList<>();
        for (PlatformProfile.VmSpec spec : profile.getVms()) {
            if (spec.hasCosts()) {
                PlatformProfile.CostSpec costs = spec.getCosts();
                vms.add(new CondorVM(spec.getId(), userId, spec.getMips(), spec.getPes(),
                        spec.getRamMb(), spec.getBandwidth(), spec.getImageSizeMb(), spec.getVmm(),
                        costs.getCpuPerSecond(), costs.getMemory(), costs.getStorage(),
                        costs.getBandwidth(), createScheduler(spec)));
            } else {
                vms.add(new CondorVM(spec.getId(), userId, spec.getMips(), spec.getPes(),
                        spec.getRamMb(), spec.getBandwidth(), spec.getImageSizeMb(), spec.getVmm(),
                        createScheduler(spec)));
            }
        }
        return vms;
    }

    private static CloudletScheduler createScheduler(PlatformProfile.VmSpec spec) {
        if (spec.getSchedulerMode() == PlatformProfile.CloudletSchedulerMode.TIME_SHARED) {
            return new CloudletSchedulerTimeShared();
        }
        return new CloudletSchedulerSpaceShared();
    }

    private static void requireEntityName(String entityName) {
        if (entityName == null || entityName.trim().isEmpty()) {
            throw new IllegalArgumentException("Datacenter entity name cannot be empty");
        }
    }
}
