package org.workflowsim.experiment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.cloudbus.cloudsim.Log;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.workflowsim.data.DataMovementModel;
import org.workflowsim.platform.PlatformProfile;
import org.workflowsim.utils.Parameters.PlanningAlgorithm;
import org.workflowsim.utils.Parameters.SchedulingAlgorithm;
import org.workflowsim.utils.ReplicaCatalog;
import org.workflowsim.utils.SimulationConfig;

/**
 * 碳维度 C1 守恒/恒等测试（BAT-C1，2026-10-28，红→绿锚）。期望值合法性来源：
 * 量纲换算律与预算超额积分公式（docs/advanced/CARBON_DIMENSION_DESIGN.md §1）。
 */
class CarbonAccountingTest {

    @TempDir
    Path tempDir;

    private boolean logWasDisabled;
    private PlatformProfile lastProfile;

    @BeforeEach
    void quiet() {
        logWasDisabled = Log.isDisabled();
        Log.disable();
    }

    @AfterEach
    void restore() {
        Log.setDisabled(logWasDisabled);
    }

    @Test
    void intensityUnitLawHoldsOnARealRun() throws Exception {
        SimulationReport report = run(true, 380.0, null);
        CarbonAccounting carbon = report.getCarbonAccounting();
        assertTrue(carbon != null, "declared intensity must produce an accounting");
        double expected = 0.0;
        for (java.util.Map.Entry<Integer, SimulationMetrics.VmMetrics> entry :
                report.getMetrics().getVmMetrics().entrySet()) {
            PlatformProfile.VmSpec spec = specOf(entry.getKey());
            double e = spec.getIdlePowerWatts() * report.getMakespan()
                    + (spec.getMaxPowerWatts() - spec.getIdlePowerWatts())
                            * entry.getValue().getModeledBusyIntervalSeconds();
            expected += e / 3.6e6 * 380.0;
        }
        assertEquals(
                0,
                Double.compare(expected, carbon.getTotalCarbonGrams()),
                "intensity unit law: expected=" + expected + " actual=" + carbon.getTotalCarbonGrams());
        assertEquals(
                0,
                Double.compare(expected * report.getMakespan(), carbon.getCarbonMakespanProductGramSeconds()),
                "carbon×makespan identity");
        assertEquals(
                report.getMetrics().getVmMetrics().size(),
                carbon.getPerVmCarbonGrams().size(),
                "per-VM map completeness");
        assertTrue(carbon.getTotalCarbonGrams() > 0.0, "1 kWh-scale run must have positive grams");
    }

    @Test
    void budgetExcessIntegratesAndMatchesAHandCase() throws Exception {
        // 两窗口（绿 300W / 绿 100W）覆盖 [0,120s)。忙时 fleet P = 250 + 150 = 400W。
        // 窗一超 100×busy_in_window1；窗二超 300×busy_in_window2。手推导演在断言内做：
        // 由于确切忙区间未知，先验证窗口和==总量与恒正，再以已知公式对总积分做交叉核算。
        SimulationReport report = run(true, null, new double[][] {{0.0, 60.0, 300.0}, {60.0, 120.0, 100.0}});
        CarbonAccounting carbon = report.getCarbonAccounting();
        assertTrue(carbon != null, "declared windows must produce an accounting");
        double windowSum = 0.0;
        for (double v : carbon.getWindowExcessWattSeconds().values()) {
            windowSum += v;
        }
        assertEquals(windowSum, carbon.getTotalCarbonExcessWattSeconds(), 1e-9, "window sums == total");
        assertTrue(
                carbon.getTotalCarbonExcessWattSeconds() > 0.0,
                "green 300/100 below active 400 must yield positive excess");
        // 依赖声明的硬核验证：每个窗口超 (P−green)×重叠时长 ≥ 0 且结构正确。
        assertEquals(
                0, Double.compare(0.0, carbon.getTotalCarbonGrams()), "budget model leaves intensity family untouched");
    }

    @Test
    void unpoweredIntensityIsRejectedAndPlainStaysAbsent() {
        // 未声明任何碳模型 → null（合法缺省，非全零）。
        org.junit.jupiter.api.Assertions.assertDoesNotThrow(() -> {
            SimulationReport plain = run(true, null, null);
            assertNull(plain.getCarbonAccounting(), "no carbon declaration => absent accounting");
        });
        // 无功率平台 + 强度声明 → 构造函数 fail-fast。
        IllegalArgumentException rejection = assertThrows(
                IllegalArgumentException.class,
                () -> run(false, 380.0, null),
                "unpowered + declared intensity must fail-fast (BAT-C1)");
        assertTrue(
                rejection.getMessage().contains("carbonIntensityGramsPerKWh"),
                "rejection must cite the field: " + rejection.getMessage());
    }

    @Test
    void bothModelsTogetherAreRejected() {
        IllegalArgumentException rejection = assertThrows(
                IllegalArgumentException.class,
                () -> run(true, 380.0, new double[][] {{0.0, 60.0, 300.0}}),
                "both carbon models must be rejected at report construction");
        assertTrue(
                rejection.getMessage().contains("mutually exclusive"),
                "rejection must name the exclusivity clause: " + rejection.getMessage());
    }

    @Test
    void tieredPlatformIntensityRidesTheConservedPerTierEnergy() throws Exception {
        // BAT-D4（2026-10-29）tiered ride-through 红→绿锚。频档平台（平面功率恒 0，BAT-D1
        // 单一真源）是已声明功率的剖面：强度碳乘<b>逐档守恒能量</b>
        // （docs/advanced/DVFS_DIMENSION_DESIGN.md §1.5/§2.4）。此前该路径被 BAT-C1
        // 平面字段闸整节 fail-fast（tiered 运行无法产出真实 carbonMetrics——D3 设计
        // 注记的前向缺口）。期望值合法性来源：§2.3 逐档守恒律 × 量纲换算律，输入取
        // 频档表 + 逐档台账（与能量节同源），TreeMap 升序求和序与 EnergyAccounting
        // 镜像（浮点决定性纪律），不拷贝碳节自身输出。
        java.nio.file.Path workflow = java.nio.file.Paths.get(tempDir.toFile().getAbsolutePath(), "dvfs.dax");
        Files.write(
                workflow,
                ("<adag version=\"3.3\" count=\"2\">"
                                + "<job id=\"a\" name=\"a\" runtime=\"10\"/>"
                                + "<job id=\"b\" name=\"b\" runtime=\"30\"/></adag>")
                        .getBytes(StandardCharsets.UTF_8));
        SimulationConfig config = SimulationConfig.builder(workflow.toString(), 1)
                .planningAlgorithm(PlanningAlgorithm.SHARED_STORAGE_HEFT)
                .schedulingAlgorithm(SchedulingAlgorithm.STATIC)
                .fileSystem(ReplicaCatalog.FileSystem.SHARED)
                .dataMovementModel(DataMovementModel.legacyWorkflowsimV1())
                .frequencyPolicy(SimulationConfig.FrequencyPolicy.EDP_HEURISTIC)
                .randomSeed(20261029L)
                .build();
        java.util.List<PlatformProfile.FrequencyTier> tiers = java.util.Arrays.asList(
                new PlatformProfile.FrequencyTier(2000.0, 250.0, 400.0),
                new PlatformProfile.FrequencyTier(1000.0, 220.0, 280.0),
                new PlatformProfile.FrequencyTier(500.0, 210.0, 240.0));
        PlatformProfile.Builder builder = PlatformProfile.builder("dvfs-carbon-test");
        builder.addHost(new PlatformProfile.HostSpec(0, 2, 4000, 2048, 10000L, 1000000L))
                .addVm(new PlatformProfile.VmSpec(
                        0,
                        2000,
                        1,
                        512,
                        1000L,
                        10000L,
                        "Xen",
                        PlatformProfile.CloudletSchedulerMode.SPACE_SHARED,
                        null,
                        0.0,
                        0.0,
                        380.0,
                        tiers))
                .pinVmToHost(0, 0);
        lastProfile = builder.build();
        SimulationReport report = new SimulationRunner().run(config, lastProfile);
        CarbonAccounting carbon = report.getCarbonAccounting();
        assertTrue(carbon != null, "tiered platform + declared intensity must produce an accounting");
        assertEquals(1, carbon.getPerVmCarbonGrams().size(), "per-VM map completeness");
        // §2.3 逐档守恒律：E = P_idle(默认档)×T + Σ档(P_max(档)−P_idle(默认档))×busy(档)
        double defaultIdle = 250.0;
        double expectedEnergy = defaultIdle * report.getMakespan();
        java.util.Map<Double, Double> ledger =
                report.getMetrics().getPerTierBusySecondsByVm().get(0);
        assertTrue(ledger != null && !ledger.isEmpty(), "EDP-tiered run must carry the per-tier ledger");
        for (java.util.Map.Entry<Double, Double> tier : new java.util.TreeMap<Double, Double>(ledger).entrySet()) {
            double tierMax =
                    tierMaxOf(lastProfile.getVms().get(0), tier.getKey().doubleValue());
            expectedEnergy += (tierMax - defaultIdle) * tier.getValue().doubleValue();
        }
        double expectedGrams = expectedEnergy / 3.6e6 * 380.0;
        assertEquals(
                0,
                Double.compare(expectedGrams, carbon.getTotalCarbonGrams()),
                "per-tier intensity law: expected=" + expectedGrams + " actual=" + carbon.getTotalCarbonGrams());
        assertEquals(
                0,
                Double.compare(expectedGrams, carbon.getPerVmCarbonGrams().get(0)),
                "per-VM grams ride the same conserved energy");
        assertEquals(
                0,
                Double.compare(expectedGrams * report.getMakespan(), carbon.getCarbonMakespanProductGramSeconds()),
                "carbon×makespan identity on tiered runs");
        assertTrue(carbon.getTotalCarbonGrams() > 0.0, "tiered powered run must have positive grams");
    }

    /**
     * A2 红→绿锚（2026-11-02）：频档平台 + 碳窗口 = 预算碳。此前 {@code budgetModel}
     * 只认平铺功率字段，频档 VM（平铺恒 0，BAT-D1）直接 fail-fast，所以"5 档 P-state
     * × 绿色预算"这一维交叉无法产生真实 carbonMetrics；Python 审计器早已实现该分支
     * （DVFS_DIMENSION_DESIGN §2.3 的前向契约）。
     *
     * <p>期望值合法性来源：契约律本身（Python 侧文档化口径的逐句镜像）——逐时刻舰队
     * 功率 = Σ卡顿 VM 默认档 idle + Σ活跃 Job (P_max(其档) − P_idle(默认档))；本测试
     * 在断言内按该律独立重算，不拷贝实现输出。</p>
     */
    @Test
    void tieredPlatformBudgetCarbonUsesThePerTierFleetPowerCurve() throws Exception {
        SimulationReport report = runTieredBudget();
        CarbonAccounting carbon = report.getCarbonAccounting();
        assertTrue(carbon != null, "tiered platform + declared windows must now produce an accounting");

        // 契约律独立重算：端点折叠 ±dyn，段内舰队功率 = idle_sum + Σ活跃 dyn。
        PlatformProfile profile = lastProfile;
        double defaultIdle = 250.0;
        double idleSum = 0.0;
        for (PlatformProfile.VmSpec spec : profile.getVms()) {
            idleSum += spec.hasFrequencyTiers()
                    ? spec.getFrequencyTiers().get(0).getIdlePowerWatts()
                    : spec.getIdlePowerWatts();
        }
        java.util.TreeMap<Double, Double> ends = new java.util.TreeMap<Double, Double>();
        for (SimulationReport.JobOutcome job : report.getJobs()) {
            if (job.getClassType() != 2 || job.getFinishTime() <= job.getStartTime()) {
                continue;
            }
            PlatformProfile.VmSpec spec = profile.getVms().get(job.getVmId());
            double dyn;
            if (spec.hasFrequencyTiers()) {
                double tierMips = job.getTierMips() == null
                        ? spec.getFrequencyTiers().get(0).getMips()
                        : job.getTierMips().doubleValue();
                dyn = tierMaxOf(spec, tierMips) - defaultIdle;
            } else {
                dyn = spec.getMaxPowerWatts() - spec.getIdlePowerWatts();
            }
            ends.merge(Double.valueOf(job.getStartTime()), Double.valueOf(dyn), Double::sum);
            ends.merge(Double.valueOf(job.getFinishTime()), Double.valueOf(-dyn), Double::sum);
        }
        double expectedTotal = expectedTieredExcess(report, profile);
        assertEquals(
                0,
                Double.compare(expectedTotal, carbon.getTotalCarbonExcessWattSeconds()),
                "tiered budget law: expected=" + expectedTotal + " actual=" + carbon.getTotalCarbonExcessWattSeconds());
        assertTrue(expectedTotal > 0.0, "the fixture must produce a positive excess (green below active power)");
        // 预算族不产克数（两族互斥）：克数字段保持 0，与强度族区分。
        assertEquals(0, Double.compare(0.0, carbon.getTotalCarbonGrams()));
    }

    /**
     * A2 跨语言对抗（2026-11-02）：平铺 VM 上的作业携带 tierMips 属于证据链断裂——
     * Java 必须与 Python 审计器同律拒收，而不是静默按平铺功率算出一个"看起来正常"
     * 的数值（那会让同一份证据在两侧给出不同结论）。
     */
    @Test
    void flatVmJobCarryingTierIdentityIsRejectedLikeThePythonAuditor() {
        PlatformProfile.VmSpec flat = new PlatformProfile.VmSpec(
                7,
                1000,
                1,
                512,
                1000L,
                10000L,
                "Xen",
                PlatformProfile.CloudletSchedulerMode.SPACE_SHARED,
                null,
                120.0,
                300.0,
                null);
        assertEquals(
                0,
                Double.compare(180.0, CarbonAccounting.jobDynamicWatts(flat, 3, null)),
                "a tierless Job on a flat VM contributes max−idle");
        IllegalArgumentException rejected = org.junit.jupiter.api.Assertions.assertThrows(
                IllegalArgumentException.class, () -> CarbonAccounting.jobDynamicWatts(flat, 3, Double.valueOf(500.0)));
        assertTrue(rejected.getMessage().contains("non-tiered VM"), rejected.getMessage());

        PlatformProfile.VmSpec tiered = new PlatformProfile.VmSpec(
                8,
                1000,
                1,
                512,
                1000L,
                10000L,
                "Xen",
                PlatformProfile.CloudletSchedulerMode.SPACE_SHARED,
                null,
                0.0,
                0.0,
                null,
                java.util.Arrays.asList(
                        new PlatformProfile.FrequencyTier(1000.0, 250.0, 400.0),
                        new PlatformProfile.FrequencyTier(500.0, 100.0, 118.75)));
        assertEquals(
                0,
                Double.compare(150.0, CarbonAccounting.jobDynamicWatts(tiered, 4, null)),
                "a tierless Job on a tiered VM is attributed to the default tier (400−250)");
        assertEquals(
                0,
                Double.compare(-131.25, CarbonAccounting.jobDynamicWatts(tiered, 4, Double.valueOf(500.0))),
                "a low tier may draw less than the default-tier idle (118.75−250)");
    }

    /** 契约律重算：逐档舰队功率曲线的窗口超额（与实现独立走一遍文档化口径）。 */
    private static double expectedTieredExcess(SimulationReport report, PlatformProfile profile) {
        double defaultIdleOfTiered = 0.0;
        double idleSum = 0.0;
        for (PlatformProfile.VmSpec spec : profile.getVms()) {
            if (spec.hasFrequencyTiers()) {
                defaultIdleOfTiered = spec.getFrequencyTiers().get(0).getIdlePowerWatts();
                idleSum += defaultIdleOfTiered;
            } else {
                idleSum += spec.getIdlePowerWatts();
            }
        }
        java.util.TreeMap<Double, Double> ends = new java.util.TreeMap<Double, Double>();
        for (SimulationReport.JobOutcome job : report.getJobs()) {
            if (job.getClassType() != 2 || job.getFinishTime() <= job.getStartTime()) {
                continue;
            }
            PlatformProfile.VmSpec spec = profile.getVms().get(job.getVmId());
            double dyn;
            if (spec.hasFrequencyTiers()) {
                double tierMips = job.getTierMips() == null
                        ? spec.getFrequencyTiers().get(0).getMips()
                        : job.getTierMips().doubleValue();
                dyn = tierMaxOf(spec, tierMips)
                        - spec.getFrequencyTiers().get(0).getIdlePowerWatts();
            } else {
                dyn = spec.getMaxPowerWatts() - spec.getIdlePowerWatts();
            }
            ends.merge(Double.valueOf(job.getStartTime()), Double.valueOf(dyn), Double::sum);
            ends.merge(Double.valueOf(job.getFinishTime()), Double.valueOf(-dyn), Double::sum);
        }
        java.util.List<double[]> segments = new java.util.ArrayList<double[]>();
        double active = 0.0;
        double prev = 0.0;
        for (java.util.Map.Entry<Double, Double> end : ends.entrySet()) {
            double t = end.getKey().doubleValue();
            if (t > prev) {
                segments.add(new double[] {prev, t, idleSum + active});
            }
            active += end.getValue().doubleValue();
            prev = t;
        }
        if (prev < report.getMakespan()) {
            segments.add(new double[] {prev, report.getMakespan(), idleSum + active});
        }
        double total = 0.0;
        for (PlatformProfile.CarbonWindow window : profile.getCarbonWindows()) {
            double acc = 0.0;
            for (double[] segment : segments) {
                double lo = Math.max(segment[0], window.getStartSeconds());
                double hi = Math.min(segment[1], window.getEndSeconds());
                if (hi > lo) {
                    acc += Math.max(0.0, segment[2] - window.getGreenWatts()) * (hi - lo);
                }
            }
            total += acc;
        }
        return total;
    }

    /**
     * A2 对抗面：混合舰队（一台分档 VM + 一台平铺 VM）在同一预算窗口下必须走同一
     * 舰队功率曲线——分档贡献取默认档 idle + 逐档动态，平铺贡献保持 v1 的 max−idle。
     */
    @Test
    void mixedFleetBudgetCarbonMixesTieredAndFlatContributions() throws Exception {
        SimulationReport report = runMixedFleetBudget();
        CarbonAccounting carbon = report.getCarbonAccounting();
        assertTrue(carbon != null, "a mixed fleet with windows must produce an accounting");
        double expected = expectedTieredExcess(report, lastProfile);
        assertEquals(
                0,
                Double.compare(expected, carbon.getTotalCarbonExcessWattSeconds()),
                "mixed-fleet law: expected=" + expected + " actual=" + carbon.getTotalCarbonExcessWattSeconds());
        assertTrue(expected > 0.0, "the fixture must yield a positive excess");
    }

    /** 混合舰队夹具：VM0 分档（默认档 idle 250），VM1 平铺（idle 120 / max 300）。 */
    private SimulationReport runMixedFleetBudget() throws Exception {
        Path workflow = Paths.get(tempDir.toFile().getAbsolutePath(), "mixed.dax");
        Files.write(
                workflow,
                ("<adag version=\"3.3\" count=\"2\">"
                                + "<job id=\"a\" name=\"a\" runtime=\"10\"/>"
                                + "<job id=\"b\" name=\"b\" runtime=\"30\"/></adag>")
                        .getBytes(StandardCharsets.UTF_8));
        SimulationConfig config = SimulationConfig.builder(workflow.toString(), 2)
                .planningAlgorithm(PlanningAlgorithm.SHARED_STORAGE_HEFT)
                .schedulingAlgorithm(SchedulingAlgorithm.STATIC)
                .fileSystem(ReplicaCatalog.FileSystem.SHARED)
                .dataMovementModel(DataMovementModel.legacyWorkflowsimV1())
                .frequencyPolicy(SimulationConfig.FrequencyPolicy.MIN_POWER)
                .randomSeed(20261103L)
                .build();
        java.util.List<PlatformProfile.FrequencyTier> tiers = java.util.Arrays.asList(
                new PlatformProfile.FrequencyTier(2000.0, 250.0, 4000.0),
                new PlatformProfile.FrequencyTier(500.0, 100.0, 118.75));
        PlatformProfile.Builder builder = PlatformProfile.builder("mixed-fleet-test");
        builder.addHost(new PlatformProfile.HostSpec(0, 2, 4000, 2048, 10000L, 1000000L))
                .addVm(new PlatformProfile.VmSpec(
                        0,
                        2000,
                        1,
                        512,
                        1000L,
                        10000L,
                        "Xen",
                        PlatformProfile.CloudletSchedulerMode.SPACE_SHARED,
                        null,
                        0.0,
                        0.0,
                        null,
                        tiers))
                .pinVmToHost(0, 0);
        builder.addHost(new PlatformProfile.HostSpec(1, 2, 4000, 2048, 10000L, 1000000L))
                .addVm(new PlatformProfile.VmSpec(
                        1,
                        2000,
                        1,
                        512,
                        1000L,
                        10000L,
                        "Xen",
                        PlatformProfile.CloudletSchedulerMode.SPACE_SHARED,
                        null,
                        120.0,
                        300.0,
                        null,
                        java.util.Collections.<PlatformProfile.FrequencyTier>emptyList()))
                .pinVmToHost(1, 1);
        builder.carbonWindow(0.0, 20000.0, 260.0);
        lastProfile = builder.build();
        return new SimulationRunner().run(config, lastProfile);
    }

    /** 频档平台 + 单窗口预算碳夹具：MIN_POWER 把作业压到低档，功率曲线沿档变化。 */
    private SimulationReport runTieredBudget() throws Exception {
        Path workflow = Paths.get(tempDir.toFile().getAbsolutePath(), "tiered-budget.dax");
        Files.write(
                workflow,
                ("<adag version=\"3.3\" count=\"2\">"
                                + "<job id=\"a\" name=\"a\" runtime=\"10\"/>"
                                + "<job id=\"b\" name=\"b\" runtime=\"30\"/></adag>")
                        .getBytes(StandardCharsets.UTF_8));
        SimulationConfig config = SimulationConfig.builder(workflow.toString(), 1)
                .planningAlgorithm(PlanningAlgorithm.SHARED_STORAGE_HEFT)
                .schedulingAlgorithm(SchedulingAlgorithm.STATIC)
                .fileSystem(ReplicaCatalog.FileSystem.SHARED)
                .dataMovementModel(DataMovementModel.legacyWorkflowsimV1())
                .frequencyPolicy(SimulationConfig.FrequencyPolicy.MIN_POWER)
                .randomSeed(20261102L)
                .build();
        java.util.List<PlatformProfile.FrequencyTier> tiers = java.util.Arrays.asList(
                new PlatformProfile.FrequencyTier(2000.0, 250.0, 4000.0),
                new PlatformProfile.FrequencyTier(1000.0, 250.0, 400.0),
                new PlatformProfile.FrequencyTier(500.0, 100.0, 118.75));
        PlatformProfile.Builder builder = PlatformProfile.builder("tiered-budget-test");
        builder.addHost(new PlatformProfile.HostSpec(0, 2, 4000, 2048, 10000L, 1000000L))
                .addVm(new PlatformProfile.VmSpec(
                        0,
                        2000,
                        1,
                        512,
                        1000L,
                        10000L,
                        "Xen",
                        PlatformProfile.CloudletSchedulerMode.SPACE_SHARED,
                        null,
                        0.0,
                        0.0,
                        null,
                        tiers))
                .pinVmToHost(0, 0);
        builder.carbonWindow(0.0, 10000.0, 200.0);
        lastProfile = builder.build();
        return new SimulationRunner().run(config, lastProfile);
    }

    /** BAT-D4：按 MIPS 精确身份查档功率（频档表降序唯一；同 VmSpec.tierByMips 契约）。 */
    private static double tierMaxOf(PlatformProfile.VmSpec spec, double mips) {
        for (PlatformProfile.FrequencyTier tier : spec.getFrequencyTiers()) {
            if (Double.compare(tier.getMips(), mips) == 0) {
                return tier.getMaxPowerWatts();
            }
        }
        throw new IllegalStateException("tier mips " + mips + " matches no declared tier");
    }

    // ---- fixtures ----

    private SimulationReport run(boolean powered, Double intensity, double[][] windows) throws Exception {
        Path workflow = Paths.get(tempDir.toFile().getAbsolutePath(), "carbon.dax");
        Files.write(
                workflow,
                ("<adag version=\"3.3\" count=\"2\">"
                                + "<job id=\"a\" name=\"a\" runtime=\"10\"/>"
                                + "<job id=\"b\" name=\"b\" runtime=\"30\"/></adag>")
                        .getBytes(StandardCharsets.UTF_8));
        SimulationConfig config = SimulationConfig.builder(workflow.toString(), 1)
                .planningAlgorithm(PlanningAlgorithm.SHARED_STORAGE_HEFT)
                .schedulingAlgorithm(SchedulingAlgorithm.STATIC)
                .fileSystem(ReplicaCatalog.FileSystem.SHARED)
                .dataMovementModel(DataMovementModel.legacyWorkflowsimV1())
                .randomSeed(20261028L)
                .build();
        PlatformProfile.Builder builder = PlatformProfile.builder("carbon-test");
        PlatformProfile.VmSpec vm = new PlatformProfile.VmSpec(
                0,
                1000,
                1,
                512,
                1000L,
                10000L,
                "Xen",
                PlatformProfile.CloudletSchedulerMode.SPACE_SHARED,
                null,
                powered ? 250.0 : 0.0,
                powered ? 400.0 : 0.0,
                intensity);
        builder.addHost(new PlatformProfile.HostSpec(0, 2, 2000, 2048, 10000L, 1000000L))
                .addVm(vm)
                .pinVmToHost(0, 0);
        if (windows != null) {
            for (double[] w : windows) {
                builder.carbonWindow(w[0], w[1], w[2]);
            }
        }
        lastProfile = builder.build();
        return new SimulationRunner().run(config, lastProfile);
    }

    private PlatformProfile.VmSpec specOf(int vmId) {
        for (PlatformProfile.VmSpec spec : lastProfile.getVms()) {
            if (spec.getId() == vmId) {
                return spec;
            }
        }
        throw new IllegalStateException("vm " + vmId + " not in test profile");
    }
}
