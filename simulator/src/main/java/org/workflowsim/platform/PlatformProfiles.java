package org.workflowsim.platform;

/** 已维护示例使用的标准化、显式抽象平台集合。 */
public final class PlatformProfiles {

    private PlatformProfiles() {
    }

    /**
     * 创建每个 Host 配置一台单核 VM 的同构本地抽象平台。
     *
     * <p>该平台是配置驱动的研究基线，不是 WfCommons 执行机器的回放。</p>
     *
     * @param name 平台名称
     * @param vmCount 需要创建的 VM 和 Host 数量，必须为正数
     * @return 一台 VM 对应一台 Host 的同构抽象平台
     * @throws IllegalArgumentException 当 {@code vmCount} 非正时抛出
     */
    public static PlatformProfile homogeneousLocal(String name, int vmCount) {
        if (vmCount <= 0) {
            throw new IllegalArgumentException("VM count must be positive");
        }
        PlatformProfile.Builder builder = PlatformProfile.builder(name);
        for (int id = 0; id < vmCount; id++) {
            builder.addHost(new PlatformProfile.HostSpec(id, 2, 2000.0,
                    2048, 10_000L, 1_000_000L));
            builder.addVm(new PlatformProfile.VmSpec(id, 1000.0, 1, 512,
                    1000L, 10_000L, "Xen",
                    PlatformProfile.CloudletSchedulerMode.SPACE_SHARED));
        }
        return builder.build();
    }

    /**
     * 创建异构 MIPS 的本地抽象平台：每个 Host 配置一台单核 VM，VM ID 按
     * {@code vmMips} 列表顺序从 0 递增。
     *
     * <p>用于论文复现类实验（如 PSO 复现需要异构 VM 使“成本 = 执行时间 × 单价
     * （mips/1000）”的适应度成本维度有意义）。Host 的每 PE MIPS 固定为对应 VM 的
     * 2 倍，与 {@link #homogeneousLocal(String, int)} 的 Host/VM 比例一致；其余
     * Host/VM 资源规格与同构平台相同。</p>
     *
     * <p>该平台是配置驱动的研究基线，不是任何真实云的机型回放。</p>
     *
     * @param name 平台名称
     * @param vmMips 每台 VM 的 MIPS（必须为正且有限），列表长度 = VM/Host 数量
     * @return 一台 VM 对应一台 Host 的异构抽象平台
     * @throws IllegalArgumentException 当列表为空、含非正或非有限 MIPS 时抛出
     */
    public static PlatformProfile heterogeneousLocal(String name, java.util.List<Double> vmMips) {
        if (vmMips == null || vmMips.isEmpty()) {
            throw new IllegalArgumentException("VM MIPS list must be non-empty");
        }
        PlatformProfile.Builder builder = PlatformProfile.builder(name);
        for (int id = 0; id < vmMips.size(); id++) {
            Double mips = vmMips.get(id);
            if (mips == null || mips.isNaN() || mips.isInfinite() || mips <= 0.0) {
                throw new IllegalArgumentException("VM MIPS must be positive and finite: " + mips);
            }
            builder.addHost(new PlatformProfile.HostSpec(id, 2, mips * 2.0,
                    2048, 10_000L, 1_000_000L));
            builder.addVm(new PlatformProfile.VmSpec(id, mips, 1, 512,
                    1000L, 10_000L, "Xen",
                    PlatformProfile.CloudletSchedulerMode.SPACE_SHARED));
        }
        return builder.build();
    }
}
