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
            double e = spec.getIdlePowerWatts() * makespanSeconds
                    + (spec.getMaxPowerWatts() - spec.getIdlePowerWatts()) * m.getModeledBusyIntervalSeconds();
            perVm.put(vmId, Double.valueOf(e));
            total += e;
            peak += spec.getMaxPowerWatts();
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
