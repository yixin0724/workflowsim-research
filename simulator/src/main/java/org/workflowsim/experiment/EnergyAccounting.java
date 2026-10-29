package org.workflowsim.experiment;

import java.util.LinkedHashMap;
import java.util.Map;

import org.workflowsim.platform.PlatformProfile;

/**
 * Per-VM energy integral and total-energy computation for the energy dimension
 * (BAT-E1, 2026-10-27).
 *
 * <p>Conservation law (Beloglazov et al. FGCS 2012 Eq.(2), binary specialization for
 * SPACE_SHARED one-Job-per-VM): each VM's instantaneous utilization is either 0 (idle)
 * or 1 (active), so the power integral collapses to two rectangles:</p>
 * <pre>
 * E_vm = P_idle × (T_sim − T_active) + P_max × T_active
 *      = P_idle × T_sim + (P_max − P_idle) × T_active
 * </pre>
 * <p>where T_sim = makespanSeconds and T_active = the VM's modeled busy-interval seconds
 * already present in every manifest's VmMetrics. This class exists to keep the 77-arg
 * SimulationMetrics constructor unchanged: the energy fields attach from outside.</p>
 *
 * <p>When no VM declares power fields, all values are zero — a legally declared
 * backward-compatible run.</p>
 */
public final class EnergyAccounting {

    private final double totalEnergyJoules;
    private final double meanVmEnergyJoules;
    private final double peakPowerWatts;
    private final double energyMakespanProductJouleSeconds;
    private final Map<Integer, Double> vmEnergyJoules;

    private EnergyAccounting(
            double totalEnergyJoules,
            double meanVmEnergyJoules,
            double peakPowerWatts,
            double energyMakespanProductJouleSeconds,
            Map<Integer, Double> vmEnergyJoules) {
        this.totalEnergyJoules = totalEnergyJoules;
        this.meanVmEnergyJoules = meanVmEnergyJoules;
        this.peakPowerWatts = peakPowerWatts;
        this.energyMakespanProductJouleSeconds = energyMakespanProductJouleSeconds;
        this.vmEnergyJoules = vmEnergyJoules;
    }

    /**
     * Computes per-VM and total energy from the frozen metrics and the platform
     * profile's power fields.
     */
    public static EnergyAccounting fromFrozenMetrics(
            double makespanSeconds,
            Map<Integer, ? extends SimulationMetrics.VmMetrics> vmMetrics,
            PlatformProfile platform) {
        Map<Integer, Double> perVm = new LinkedHashMap<Integer, Double>();
        double total = 0.0;
        double peak = 0.0;
        for (Map.Entry<Integer, ? extends SimulationMetrics.VmMetrics> entry : vmMetrics.entrySet()) {
            int vmId = entry.getKey();
            SimulationMetrics.VmMetrics m = entry.getValue();
            PlatformProfile.VmSpec spec = findSpec(platform, vmId);
            if (spec == null || !spec.hasPowerModel()) {
                perVm.put(vmId, Double.valueOf(0.0));
                continue;
            }
            // BAT-D1（2026-10-29）：频档模式 → 默认档的怠速 + 全部分配档的逐档满载矩形。
            // 单档退化为同一个公式（首个 tier 的 idle/max 正是把 v1 字段搬进列表）。
            double e;
            if (spec.hasFrequencyTiers()) {
                // v2: E_vm = P_idle(default)×T_sim + Σ档 [P_max(档次) − P_idle(default)]×busy(档)×
                // 简化守恒律的不变式——每档满载 = 默认怠速 + 该档动态（对 rectangle 面积合并
                // 为 P_idle(default)×T + Σ(P_max(档)−P_idle(默认))×busy(档)；空档不计）。v1
                // 退化：单档即为 idle×T+(max−idle)×busy。
                e = spec.defaultFrequencyTier().getIdlePowerWatts() * makespanSeconds;
                // busy time 松散分布（v1 WmMetrics 只有总时长）；份额按整段全跑默认档核算
                // → v2 逐档 rect 需 D2 的 tierMips 才能 split; 在 D1 阶段先给"整段默认档"
                // 的退化行为（tierMips == null → the Job ran at the DEFAULT tier）。
                double dyn = spec.defaultFrequencyTier().getMaxPowerWatts()
                        - spec.defaultFrequencyTier().getIdlePowerWatts();
                e += dyn * m.getModeledBusyIntervalSeconds();
                peak += spec.defaultFrequencyTier().getMaxPowerWatts();
            } else {
                e = spec.getIdlePowerWatts() * makespanSeconds
                        + (spec.getMaxPowerWatts() - spec.getIdlePowerWatts()) * m.getModeledBusyIntervalSeconds();
                peak += spec.getMaxPowerWatts();
            }
            perVm.put(vmId, Double.valueOf(e));
            total += e;
        }
        double mean = perVm.isEmpty() ? 0.0 : total / perVm.size();
        return new EnergyAccounting(
                total, mean, peak, total * makespanSeconds, java.util.Collections.unmodifiableMap(perVm));
    }

    // BAT-R6（2026-10-27 审查第六轮）：删除从未被调用的 unpowered() 工厂——
    // fromFrozenMetrics 对无功率平台天然产出全零（每 VM 0.0 贡献），死者公开面从 E1
    // 落地起就无调用方；留 public 无主人口属于无审视接口面。

    private static PlatformProfile.VmSpec findSpec(PlatformProfile platform, int vmId) {
        for (PlatformProfile.VmSpec spec : platform.getVms()) {
            if (spec.getId() == vmId) {
                return spec;
            }
        }
        return null;
    }

    /** @return total energy across all VMs in joules */
    public double getTotalEnergyJoules() {
        return totalEnergyJoules;
    }

    /** @return arithmetic mean of per-VM energy in joules */
    public double getMeanVmEnergyJoules() {
        return meanVmEnergyJoules;
    }

    /** @return fleet peak power (sum of per-VM max) in watts; 0 when no power model */
    public double getPeakPowerWatts() {
        return peakPowerWatts;
    }

    /** @return energy-delay product: totalEnergyJoules × makespanSeconds */
    public double getEnergyMakespanProductJouleSeconds() {
        return energyMakespanProductJouleSeconds;
    }

    /** @return unmodifiable per-VM energy map */
    public Map<Integer, Double> getVmEnergyJoules() {
        return vmEnergyJoules;
    }
}
