package org.workflowsim.platform;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;

/**
 * WorkflowSim 实验使用的不可变抽象基础设施模型。
 *
 * <p>平台描述只定义被模拟的资源，不能据此声称 WfCommons 执行轨迹曾在相同
 * 基础设施上运行。</p>
 */
public final class PlatformProfile {

    /** VM 内 Cloudlet 的执行资源共享方式。 */
    public enum CloudletSchedulerMode {
        /** 每个任务独占分配到的处理资源，直到该任务完成。 */
        SPACE_SHARED,
        /** 多个任务按时间片共享处理资源。 */
        TIME_SHARED
    }

    private final String name;
    private final List<HostSpec> hosts;
    private final List<VmSpec> vms;
    private final java.util.Map<Integer, Integer> pinnedVmHostIds;
    private final java.util.Map<Integer, Integer> vmHostAssignments;
    private final StorageSpec storage;
    private final CostSpec costs;

    private PlatformProfile(Builder builder) {
        this.name = builder.name;
        this.hosts = Collections.unmodifiableList(new ArrayList<>(builder.hosts));
        this.vms = Collections.unmodifiableList(new ArrayList<>(builder.vms));
        this.pinnedVmHostIds = Collections.unmodifiableMap(
                new LinkedHashMap<Integer, Integer>(builder.pinnedVmHostIds));
        this.vmHostAssignments = Collections.unmodifiableMap(
                new LinkedHashMap<Integer, Integer>(builder.vmHostAssignments));
        this.storage = builder.storage;
        this.costs = builder.costs;
    }

    public String getName() {
        return name;
    }

    public List<HostSpec> getHosts() {
        return hosts;
    }

    public List<VmSpec> getVms() {
        return vms;
    }

    /**
     * 返回显式固定的 VM 到 Host 映射；未固定的 VM 不出现在该映射中。
     *
     * @return 不可变的显式 VM-Host 固定映射
     */
    public java.util.Map<Integer, Integer> getPinnedVmHostIds() {
        return pinnedVmHostIds;
    }

    /**
     * 返回构建平台时预检得到的确定性 VM 到 Host 放置。
     *
     * <p>标准运行器会在发布报告前将其与 CloudSim 实际创建的映射进行比对。</p>
     *
     * @return 不可变的完整 VM-Host 预检放置映射
     */
    public java.util.Map<Integer, Integer> getVmHostAssignments() {
        return vmHostAssignments;
    }

    public StorageSpec getStorage() {
        return storage;
    }

    public CostSpec getCosts() {
        return costs;
    }

    /**
     * 创建一个空的平台描述构建器。
     *
     * @param name 用于报告和证据工件的平台名称，不能为空
     * @return 可继续添加 Host、VM、存储和成本配置的构建器
     * @throws IllegalArgumentException 当名称为空时抛出
     */
    public static Builder builder(String name) {
        return new Builder(name);
    }

    /** 用于校验平台身份、资源容量和确定性放置约束的构建器。 */
    public static final class Builder {

        private final String name;
        private final List<HostSpec> hosts = new ArrayList<>();
        private final List<VmSpec> vms = new ArrayList<>();
        private final java.util.Map<Integer, Integer> pinnedVmHostIds =
                new LinkedHashMap<Integer, Integer>();
        private java.util.Map<Integer, Integer> vmHostAssignments =
                Collections.emptyMap();
        private StorageSpec storage = new StorageSpec(1_000_000_000_000L, 15);
        private CostSpec costs = new CostSpec(3.0, 0.05, 0.1, 0.1);

        private Builder(String name) {
            if (name == null || name.trim().isEmpty()) {
                throw new IllegalArgumentException("Platform profile name cannot be empty");
            }
            this.name = name.trim();
        }

        /**
         * 添加一个 Host 描述。
         *
         * @param host 需要加入的平台 Host 描述，不能为空
         * @return 当前构建器
         * @throws IllegalArgumentException 当 {@code host} 为空时抛出
         */
        public Builder addHost(HostSpec host) {
            hosts.add(requireNonNull(host, "Host specification"));
            return this;
        }

        /**
         * 添加一个 VM 描述。
         *
         * @param vm 需要加入的平台 VM 描述，不能为空
         * @return 当前构建器
         * @throws IllegalArgumentException 当 {@code vm} 为空时抛出
         */
        public Builder addVm(VmSpec vm) {
            vms.add(requireNonNull(vm, "VM specification"));
            return this;
        }

        /**
         * 将一个已声明 VM 固定到一个已声明 Host。
         *
         * <p>未固定的 VM 继续采用标准运行器使用的确定性兼容放置规则。</p>
         *
         * @param vmId 已声明 VM 的非负标识
         * @param hostId 已声明 Host 的非负标识
         * @return 当前构建器
         * @throws IllegalArgumentException 当标识为负或同一 VM 被固定到不同 Host 时抛出
         */
        public Builder pinVmToHost(int vmId, int hostId) {
            if (vmId < 0 || hostId < 0) {
                throw new IllegalArgumentException("Pinned VM and Host IDs must be non-negative");
            }
            Integer previous = pinnedVmHostIds.put(vmId, hostId);
            if (previous != null && previous.intValue() != hostId) {
                throw new IllegalArgumentException("VM " + vmId + " is already pinned to Host "
                        + previous);
            }
            return this;
        }

        /**
         * 设置共享存储的抽象容量与最大传输速率。
         *
         * @param value 非空的存储描述
         * @return 当前构建器
         * @throws IllegalArgumentException 当 {@code value} 为空时抛出
         */
        public Builder storage(StorageSpec value) {
            this.storage = requireNonNull(value, "Storage specification");
            return this;
        }

        /**
         * 设置数据中心级别的抽象成本参数。
         *
         * @param value 非空的成本描述
         * @return 当前构建器
         * @throws IllegalArgumentException 当 {@code value} 为空时抛出
         */
        public Builder costs(CostSpec value) {
            this.costs = requireNonNull(value, "Cost specification");
            return this;
        }

        /**
         * 校验资源描述并计算确定性的 VM-Host 预检放置。
         *
         * @return 不可变的平台描述
         * @throws IllegalArgumentException 当 Host/VM 缺失、标识重复、资源超额或固定放置不可行时抛出
         */
        public PlatformProfile build() {
            if (hosts.isEmpty() || vms.isEmpty()) {
                throw new IllegalArgumentException("Platform profile requires at least one host and one VM");
            }
            validateUniqueHostIds(hosts);
            validateUniqueVmIds(vms);
            validatePinnedVmHostIds(hosts, vms, pinnedVmHostIds);
            for (VmSpec vm : vms) {
                if (!canFitOnAnyHost(vm, hosts)) {
                    throw new IllegalArgumentException("VM " + vm.getId()
                            + " cannot fit on any declared host");
                }
            }
            validateAggregateCapacity(hosts, vms);
            vmHostAssignments = resolveVmHostAssignments(hosts, vms, pinnedVmHostIds);
            return new PlatformProfile(this);
        }

        private static void validateUniqueHostIds(List<HostSpec> values) {
            Set<Integer> ids = new HashSet<>();
            for (HostSpec value : values) {
                if (!ids.add(value.getId())) {
                    throw new IllegalArgumentException("Duplicate host ID " + value.getId());
                }
            }
        }

        private static void validateUniqueVmIds(List<VmSpec> values) {
            Set<Integer> ids = new HashSet<>();
            for (VmSpec value : values) {
                if (!ids.add(value.getId())) {
                    throw new IllegalArgumentException("Duplicate VM ID " + value.getId());
                }
            }
        }

        private static boolean canFitOnAnyHost(VmSpec vm, List<HostSpec> hosts) {
            for (HostSpec host : hosts) {
                if (vm.getPes() <= host.getPes()
                        && vm.getMips() <= host.getMipsPerPe()
                        && vm.getRamMb() <= host.getRamMb()
                        && vm.getBandwidth() <= host.getBandwidth()
                        && vm.getImageSizeMb() <= host.getStorageMb()) {
                    return true;
                }
            }
            return false;
        }

        private static void validatePinnedVmHostIds(List<HostSpec> hosts, List<VmSpec> vms,
                java.util.Map<Integer, Integer> pinned) {
            Set<Integer> hostIds = new HashSet<Integer>();
            for (HostSpec host : hosts) {
                hostIds.add(host.getId());
            }
            Set<Integer> vmIds = new HashSet<Integer>();
            for (VmSpec vm : vms) {
                vmIds.add(vm.getId());
            }
            for (java.util.Map.Entry<Integer, Integer> entry : pinned.entrySet()) {
                if (!vmIds.contains(entry.getKey())) {
                    throw new IllegalArgumentException("Pinned VM " + entry.getKey()
                            + " is not declared in this profile");
                }
                if (!hostIds.contains(entry.getValue())) {
                    throw new IllegalArgumentException("Pinned Host " + entry.getValue()
                            + " is not declared in this profile");
                }
            }
        }

        /**
         * 复现标准 {@code VmAllocationPolicySimple} 的候选顺序：剩余 PE 数最多优先，
         * 再按 Host 声明顺序打破平局。固定绑定会替代对应 VM 的默认选择；可变容量检查
         * 覆盖 CloudSim 创建 VM 时保留的全部资源。
         */
        private static java.util.Map<Integer, Integer> resolveVmHostAssignments(
                List<HostSpec> hosts, List<VmSpec> vms, java.util.Map<Integer, Integer> pinned) {
            List<PlacementCapacity> capacities = new ArrayList<PlacementCapacity>();
            for (HostSpec host : hosts) {
                capacities.add(new PlacementCapacity(host));
            }
            java.util.Map<Integer, Integer> assignments = new LinkedHashMap<Integer, Integer>();
            for (VmSpec vm : vms) {
                Integer pinnedHostId = pinned.get(vm.getId());
                PlacementCapacity selected = pinnedHostId == null
                        ? selectDefaultHost(capacities, vm) : findHost(capacities, pinnedHostId);
                if (selected == null || !selected.canHost(vm)) {
                    String detail = pinnedHostId == null ? "No deterministic host placement for VM "
                            + vm.getId() : "Pinned VM " + vm.getId() + " cannot be placed on Host "
                            + pinnedHostId;
                    throw new IllegalArgumentException(detail + " under declared resource capacity");
                }
                selected.allocate(vm);
                assignments.put(vm.getId(), selected.hostId);
            }
            return assignments;
        }

        private static PlacementCapacity findHost(List<PlacementCapacity> capacities, int hostId) {
            for (PlacementCapacity capacity : capacities) {
                if (capacity.hostId == hostId) {
                    return capacity;
                }
            }
            return null;
        }

        private static PlacementCapacity selectDefaultHost(List<PlacementCapacity> capacities, VmSpec vm) {
            PlacementCapacity selected = null;
            for (PlacementCapacity candidate : capacities) {
                if (!candidate.canHost(vm)) {
                    continue;
                }
                if (selected == null || candidate.remainingPes > selected.remainingPes) {
                    selected = candidate;
                }
            }
            return selected;
        }

        /**
         * 在确定性预检执行更严格的逐 Host 检查之前，先拒绝汇总资源已超额的配置。
         */
        private static void validateAggregateCapacity(List<HostSpec> hosts, List<VmSpec> vms) {
            long hostPes = 0L;
            long hostRam = 0L;
            long hostBandwidth = 0L;
            long hostStorage = 0L;
            long vmPes = 0L;
            long vmRam = 0L;
            long vmBandwidth = 0L;
            long vmStorage = 0L;
            for (HostSpec host : hosts) {
                hostPes += host.getPes();
                hostRam += host.getRamMb();
                hostBandwidth += host.getBandwidth();
                hostStorage += host.getStorageMb();
            }
            for (VmSpec vm : vms) {
                vmPes += vm.getPes();
                vmRam += vm.getRamMb();
                vmBandwidth += vm.getBandwidth();
                vmStorage += vm.getImageSizeMb();
            }
            if (vmPes > hostPes || vmRam > hostRam || vmBandwidth > hostBandwidth
                    || vmStorage > hostStorage) {
                throw new IllegalArgumentException("Aggregate VM demand exceeds declared host capacity");
            }
        }

        private static <T> T requireNonNull(T value, String name) {
            if (value == null) {
                throw new IllegalArgumentException(name + " cannot be null");
            }
            return value;
        }

        private static final class PlacementCapacity {
            private final int hostId;
            private final double mipsPerPe;
            private int remainingPes;
            private double remainingMips;
            private int remainingRamMb;
            private long remainingBandwidth;
            private long remainingStorageMb;

            private PlacementCapacity(HostSpec host) {
                this.hostId = host.getId();
                this.mipsPerPe = host.getMipsPerPe();
                this.remainingPes = host.getPes();
                this.remainingMips = host.getPes() * host.getMipsPerPe();
                this.remainingRamMb = host.getRamMb();
                this.remainingBandwidth = host.getBandwidth();
                this.remainingStorageMb = host.getStorageMb();
            }

            private boolean canHost(VmSpec vm) {
                return vm.getPes() <= remainingPes && vm.getMips() <= mipsPerPe
                        && vm.getMips() * vm.getPes() <= remainingMips
                        && vm.getRamMb() <= remainingRamMb && vm.getBandwidth() <= remainingBandwidth
                        && vm.getImageSizeMb() <= remainingStorageMb;
            }

            private void allocate(VmSpec vm) {
                remainingPes -= vm.getPes();
                remainingMips -= vm.getMips() * vm.getPes();
                remainingRamMb -= vm.getRamMb();
                remainingBandwidth -= vm.getBandwidth();
                remainingStorageMb -= vm.getImageSizeMb();
            }
        }
    }

    /** 一个抽象 Host 的不可变资源规格。 */
    public static final class HostSpec {

        private final int id;
        private final int pes;
        private final double mipsPerPe;
        private final int ramMb;
        private final long bandwidth;
        private final long storageMb;

        /**
         * 创建 Host 资源规格。
         *
         * @param id 非负 Host 标识
         * @param pes 可用处理单元数量，必须为正
         * @param mipsPerPe 每个处理单元的计算能力，必须为正且有限
         * @param ramMb 内存容量，单位 MB，必须为正
         * @param bandwidth 可用带宽，必须为正
         * @param storageMb 本地存储容量，单位 MB，必须为正
         * @throws IllegalArgumentException 当任一资源参数不合法时抛出
         */
        public HostSpec(int id, int pes, double mipsPerPe, int ramMb,
                long bandwidth, long storageMb) {
            if (id < 0 || pes <= 0 || !positiveFinite(mipsPerPe) || ramMb <= 0
                    || bandwidth <= 0L || storageMb <= 0L) {
                throw new IllegalArgumentException("Invalid host specification for ID " + id);
            }
            this.id = id;
            this.pes = pes;
            this.mipsPerPe = mipsPerPe;
            this.ramMb = ramMb;
            this.bandwidth = bandwidth;
            this.storageMb = storageMb;
        }

        public int getId() { return id; }
        public int getPes() { return pes; }
        public double getMipsPerPe() { return mipsPerPe; }
        public int getRamMb() { return ramMb; }
        public long getBandwidth() { return bandwidth; }
        public long getStorageMb() { return storageMb; }
    }

    /** 一个抽象 VM 的不可变资源、调度器和可选计价规格。 */
    public static final class VmSpec {

        private final int id;
        private final double mips;
        private final int pes;
        private final int ramMb;
        private final long bandwidth;
        private final long imageSizeMb;
        private final String vmm;
        private final CloudletSchedulerMode schedulerMode;
        private final CostSpec costs;

        /**
         * 创建不带 VM 级别显式计价的 VM 规格。
         *
         * @param id 非负 VM 标识
         * @param mips VM 的计算能力，必须为正且有限
         * @param pes VM 处理单元数量，必须为正
         * @param ramMb 内存容量，单位 MB，必须为正
         * @param bandwidth VM 带宽，必须为正
         * @param imageSizeMb VM 镜像大小，单位 MB，必须为正
         * @param vmm VMM 名称，不能为空
         * @param schedulerMode VM 内 Cloudlet 调度方式，不能为空
         * @throws IllegalArgumentException 当任一资源参数不合法时抛出
         */
        public VmSpec(int id, double mips, int pes, int ramMb, long bandwidth,
                long imageSizeMb, String vmm, CloudletSchedulerMode schedulerMode) {
            this(id, mips, pes, ramMb, bandwidth, imageSizeMb, vmm, schedulerMode, null);
        }

        /**
         * 创建带可选 VM 级别计价的 VM 规格。
         *
         * <p>标准运行器使用 {@code CostModel.VM} 时，所有 VM 都必须提供计价信息。</p>
         *
         * @param id 非负 VM 标识
         * @param mips VM 的计算能力，必须为正且有限
         * @param pes VM 处理单元数量，必须为正
         * @param ramMb 内存容量，单位 MB，必须为正
         * @param bandwidth VM 带宽，必须为正
         * @param imageSizeMb VM 镜像大小，单位 MB，必须为正
         * @param vmm VMM 名称，不能为空
         * @param schedulerMode VM 内 Cloudlet 调度方式，不能为空
         * @param costs 可选 VM 级别成本规格；是否允许为空由所选成本模型决定
         * @throws IllegalArgumentException 当任一资源参数不合法时抛出
         */
        public VmSpec(int id, double mips, int pes, int ramMb, long bandwidth,
                long imageSizeMb, String vmm, CloudletSchedulerMode schedulerMode, CostSpec costs) {
            if (id < 0 || !positiveFinite(mips) || pes <= 0 || ramMb <= 0
                    || bandwidth <= 0L || imageSizeMb <= 0L || vmm == null || vmm.trim().isEmpty()
                    || schedulerMode == null) {
                throw new IllegalArgumentException("Invalid VM specification for ID " + id);
            }
            this.id = id;
            this.mips = mips;
            this.pes = pes;
            this.ramMb = ramMb;
            this.bandwidth = bandwidth;
            this.imageSizeMb = imageSizeMb;
            this.vmm = vmm;
            this.schedulerMode = schedulerMode;
            this.costs = costs;
        }

        public int getId() { return id; }
        public double getMips() { return mips; }
        public int getPes() { return pes; }
        public int getRamMb() { return ramMb; }
        public long getBandwidth() { return bandwidth; }
        public long getImageSizeMb() { return imageSizeMb; }
        public String getVmm() { return vmm; }
        public CloudletSchedulerMode getSchedulerMode() { return schedulerMode; }
        public boolean hasCosts() { return costs != null; }
        public CostSpec getCosts() { return costs; }
    }

    /** 抽象共享存储的不可变容量与最大传输速率规格。 */
    public static final class StorageSpec {

        private final long capacityMb;
        private final int maxTransferRateMbPerSecond;

        /**
         * 创建共享存储规格。
         *
         * @param capacityMb 存储容量，单位 MB，必须为正
         * @param maxTransferRateMbPerSecond 最大传输速率，单位 MB/s，必须为正
         * @throws IllegalArgumentException 当容量或速率非正时抛出
         */
        public StorageSpec(long capacityMb, int maxTransferRateMbPerSecond) {
            if (capacityMb <= 0L || maxTransferRateMbPerSecond <= 0) {
                throw new IllegalArgumentException("Storage capacity and transfer rate must be positive");
            }
            this.capacityMb = capacityMb;
            this.maxTransferRateMbPerSecond = maxTransferRateMbPerSecond;
        }

        public long getCapacityMb() { return capacityMb; }
        public int getMaxTransferRateMbPerSecond() { return maxTransferRateMbPerSecond; }
    }

    /** 数据中心或 VM 使用的不可变抽象成本规格。 */
    public static final class CostSpec {

        private final double cpuPerSecond;
        private final double memory;
        private final double storage;
        private final double bandwidth;

        /**
         * 创建抽象成本规格。
         *
         * @param cpuPerSecond 每 CPU 秒成本，必须为非负有限值
         * @param memory 内存成本，必须为非负有限值
         * @param storage 存储成本，必须为非负有限值
         * @param bandwidth 带宽成本，必须为非负有限值
         * @throws IllegalArgumentException 当任一成本不是非负有限值时抛出
         */
        public CostSpec(double cpuPerSecond, double memory, double storage, double bandwidth) {
            if (!nonNegativeFinite(cpuPerSecond) || !nonNegativeFinite(memory)
                    || !nonNegativeFinite(storage) || !nonNegativeFinite(bandwidth)) {
                throw new IllegalArgumentException("Datacenter costs must be finite and non-negative");
            }
            this.cpuPerSecond = cpuPerSecond;
            this.memory = memory;
            this.storage = storage;
            this.bandwidth = bandwidth;
        }

        public double getCpuPerSecond() { return cpuPerSecond; }
        public double getMemory() { return memory; }
        public double getStorage() { return storage; }
        public double getBandwidth() { return bandwidth; }
    }

    private static boolean positiveFinite(double value) {
        return value > 0.0 && !Double.isInfinite(value) && !Double.isNaN(value);
    }

    private static boolean nonNegativeFinite(double value) {
        return value >= 0.0 && !Double.isInfinite(value) && !Double.isNaN(value);
    }
}
