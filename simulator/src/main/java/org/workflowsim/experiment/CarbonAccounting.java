package org.workflowsim.experiment;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import org.workflowsim.platform.PlatformProfile;

/**
 * 碳维度的两种核算（BAT-C1，2026-10-28），全部骑在已过守恒律的能量量上。
 * 模型不可互换（docs/advanced/CARBON_DIMENSION_DESIGN.md v1 §1）：
 *
 * <p><b>强度模型（CASPER 族）</b>：gCO₂ = 能耗 × 每 VM 声明碳强度。量纲自检可手算：
 * {@code grams_vm = E_vm[J] / 3.6e6 × I_vm[g/kWh]}（kWh = J/3.6e6）。</p>
 *
 * <p><b>预算模型（CWM/CaWoSched 族）</b>：t 时刻碳成本 = 舰队功率高于该窗口绿电功率
 * 的超额部分；CC = Σ_window ∫ max(0, P_fleet(t) − green) dt。舰队时间线由报告的
 * 计算 Job 区间重建（每 VM 并集——重试区间并集语义与能量链一致），功率 = Σ怠速 +
 * 活跃 VM 数 × 每 VM 动态功率（v1 舰队动态功率均一，见诚实边界）。</p>
 */
public final class CarbonAccounting {

    private final double totalCarbonGrams;
    private final double meanVmCarbonGrams;
    private final double carbonMakespanProductGramSeconds;
    private final Map<Integer, Double> perVmCarbonGrams;
    private final double totalCarbonExcessWattSeconds;
    private final Map<Integer, Double> windowExcessWattSeconds;

    private CarbonAccounting(
            double totalCarbonGrams,
            double meanVmCarbonGrams,
            double carbonMakespanProductGramSeconds,
            Map<Integer, Double> perVmCarbonGrams,
            double totalCarbonExcessWattSeconds,
            Map<Integer, Double> windowExcessWattSeconds) {
        this.totalCarbonGrams = totalCarbonGrams;
        this.meanVmCarbonGrams = meanVmCarbonGrams;
        this.carbonMakespanProductGramSeconds = carbonMakespanProductGramSeconds;
        this.perVmCarbonGrams = perVmCarbonGrams;
        this.totalCarbonExcessWattSeconds = totalCarbonExcessWattSeconds;
        this.windowExcessWattSeconds = windowExcessWattSeconds;
    }

    /** @return 强度模型 gCO₂ 总量；未声明该模型时为 0（以 JSON 是否含该节为准） */
    public double getTotalCarbonGrams() {
        return totalCarbonGrams;
    }

    /** @return 强度模型逐 VM gCO₂；未声明为空映射 */
    public Map<Integer, Double> getPerVmCarbonGrams() {
        return perVmCarbonGrams;
    }

    /** @return 强度模型逐 VM 均值 */
    public double getMeanVmCarbonGrams() {
        return meanVmCarbonGrams;
    }

    /** @return 碳×时长积 g·s（强度模型） */
    public double getCarbonMakespanProductGramSeconds() {
        return carbonMakespanProductGramSeconds;
    }

    /** @return 预算模型超额功率积分（W·s）；预算内为 0 */
    public double getTotalCarbonExcessWattSeconds() {
        return totalCarbonExcessWattSeconds;
    }

    /** @return 预算模型逐窗口超额（W·s），键为窗口序号 */
    public Map<Integer, Double> getWindowExcessWattSeconds() {
        return windowExcessWattSeconds;
    }

    /**
     * 强度模型。要求每台 VM 都已声明功率（平面字段<b>或</b>频档表——BAT-D1 单一真源
     * 两路互斥，任一声明即 powered）与碳强度——半声明即 fail-fast（契约破坏而非零贡献）。
     *
     * <p>BAT-D4（2026-10-29）tiered ride-through 收口：gram 直接乘<b>已冻结的逐 VM 守恒
     * 能量</b>（EnergyAccounting 按平面/逐档守恒律复算，SimulationReport 构造序先能量后
     * 碳）——强度模型不另立能量复算面（同一乘法、同源 E，镜像 Python 审计器
     * verify-study-evidence.py 的"强度乘 audited E"口径，docs/advanced/
     * DVFS_DIMENSION_DESIGN.md §1.5/§2.4）。平面平台的逐位结果与此前内联式恒等
     * （同一式同序求和）；tiered 平台的 E 为逐档守恒律值——此前该路径对 tiered 运行
     * 整节 fail-fast（tiered VM 平面字段恒 0 被 BAT-C1 闸拒），D3 设计注记的前向缺口
     * 本批补齐：tiered 平台自此可达真实 carbonMetrics。</p>
     *
     * @param profile 平台剖面（功率/频档/强度声明的事实源）
     * @param verifiedEnergy 已按守恒律冻结的逐 VM 能量（同包 EnergyAccounting 输出）
     * @param makespanSeconds 仿真结束秒（碳×时距乘积用）
     */
    public static CarbonAccounting intensityModel(
            PlatformProfile profile, EnergyAccounting verifiedEnergy, double makespanSeconds) {
        Map<Integer, Double> perVm = new LinkedHashMap<Integer, Double>();
        double total = 0.0;
        for (Map.Entry<Integer, Double> entry :
                verifiedEnergy.getVmEnergyJoules().entrySet()) {
            int vmId = entry.getKey();
            PlatformProfile.VmSpec spec = findSpec(profile, vmId);
            if (spec == null
                    || !(spec.hasPowerModel() || spec.hasFrequencyTiers())
                    || spec.getCarbonIntensityGramsPerKWh() == null) {
                throw new IllegalArgumentException("Intensity carbon model requires powered VMs with declared"
                        + " carbonIntensityGramsPerKWh on every VM; VM " + vmId + " violates this (BAT-C1)");
            }
            double grams = entry.getValue().doubleValue()
                    / 3.6e6
                    * spec.getCarbonIntensityGramsPerKWh().doubleValue();
            perVm.put(vmId, grams);
            total += grams;
        }
        double mean = perVm.isEmpty() ? 0.0 : total / perVm.size();
        return new CarbonAccounting(total, mean, total * makespanSeconds, perVm, 0.0, Collections.emptyMap());
    }

    /**
     * 预算模型。从报告的计算 Job 区间重建每 VM 忙并集 → 舰队并发曲线 → 逐窗口
     * 超额积分。舰队动态功率按 v1 约束取首 VM 的 ΔP（舰队均一，诚实边界已声明）。
     */
    public static CarbonAccounting budgetModel(SimulationReport report, PlatformProfile profile) {
        List<PlatformProfile.CarbonWindow> windows = profile.getCarbonWindows();
        if (windows == null || windows.isEmpty()) {
            throw new IllegalArgumentException("Budget carbon model requires declared carbonWindows (BAT-C1)");
        }
        double makespan = report.getMakespan();
        double idleSum = 0.0;
        double dynamicPerVm = 0.0;
        boolean firstVm = true;
        for (PlatformProfile.VmSpec spec : profile.getVms()) {
            if (!spec.hasPowerModel()) {
                throw new IllegalArgumentException("Budget carbon model requires powered VMs (BAT-C1)");
            }
            idleSum += spec.getIdlePowerWatts();
            // BAT-C2（2026-10-28 跨语言对齐）：舰队动态功率取<b>首台</b> VM 的 max−idle——
            // v1 假设舰队动态功率均一（设计文档 §2.1 诚实边界）；此前循环体逐台覆盖，
            // 实际生效的是末台，与 Python 审计器（verify-study-evidence.py）文档化的首台
            // 口径在异质舰队上漂移，违反"Java 与 Python 审计量纲必须对齐"纪律，现两侧统一。
            if (firstVm) {
                dynamicPerVm = spec.getMaxPowerWatts() - spec.getIdlePowerWatts();
                firstVm = false;
            }
        }
        // 事件折叠：每 VM 忙并集的时间端点 → 舰队并发活跃 VM 数曲线。
        TreeMap<Double, Integer> ends = new TreeMap<Double, Integer>();
        for (SimulationReport.JobOutcome job : report.getJobs()) {
            if (job.getClassType() != 2) {
                continue; // 仅计算 Job 定义忙区间（stage-in 不占 SPACE_SHARED 执行区间）
            }
            double a = job.getStartTime();
            double b = job.getFinishTime();
            if (b > a) {
                bump(ends, a, 1);
                bump(ends, b, -1);
            }
        }
        // 分段舰队功率（并发数在段上恒定）。
        List<double[]> segments = new ArrayList<double[]>();
        double concurrent = 0.0;
        double prev = 0.0;
        for (Map.Entry<Double, Integer> e : ends.entrySet()) {
            double t = e.getKey().doubleValue();
            if (t > prev) {
                segments.add(new double[] {prev, t, idleSum + concurrent * dynamicPerVm});
            }
            concurrent += e.getValue().intValue();
            prev = t;
        }
        if (prev < makespan) {
            segments.add(new double[] {prev, makespan, idleSum + concurrent * dynamicPerVm});
        }
        // 逐窗口超额积分。
        Map<Integer, Double> windowExcess = new LinkedHashMap<Integer, Double>();
        double total = 0.0;
        for (int j = 0; j < windows.size(); j++) {
            PlatformProfile.CarbonWindow w = windows.get(j);
            double acc = 0.0;
            for (double[] seg : segments) {
                double lo = Math.max(seg[0], w.getStartSeconds());
                double hi = Math.min(seg[1], w.getEndSeconds());
                if (hi > lo) {
                    acc += Math.max(0.0, seg[2] - w.getGreenWatts()) * (hi - lo);
                }
            }
            if (acc > 0.0) {
                windowExcess.put(Integer.valueOf(j), acc);
                total += acc;
            }
        }
        return new CarbonAccounting(0.0, 0.0, 0.0, Collections.emptyMap(), total, windowExcess);
    }

    private static PlatformProfile.VmSpec findSpec(PlatformProfile profile, int vmId) {
        for (PlatformProfile.VmSpec spec : profile.getVms()) {
            if (spec.getId() == vmId) {
                return spec;
            }
        }
        return null;
    }

    private static void bump(TreeMap<Double, Integer> map, double key, int delta) {
        Double boxed = Double.valueOf(key);
        Integer current = map.get(boxed);
        map.put(boxed, Integer.valueOf((current == null ? 0 : current.intValue()) + delta));
    }
}
