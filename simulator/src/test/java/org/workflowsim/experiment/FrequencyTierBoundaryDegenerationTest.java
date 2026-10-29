package org.workflowsim.experiment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import org.cloudbus.cloudsim.Log;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.workflowsim.data.DataMovementModel;
import org.workflowsim.platform.PlatformProfile;
import org.workflowsim.utils.Parameters.ClassType;
import org.workflowsim.utils.Parameters.PlanningAlgorithm;
import org.workflowsim.utils.Parameters.SchedulingAlgorithm;
import org.workflowsim.utils.ReplicaCatalog;
import org.workflowsim.utils.SimulationConfig;
import org.workflowsim.utils.SimulationConfig.FrequencyPolicy;

/**
 * BAT-V2（2026-10-29 测试工程师对抗轮，DVFS 第五轮）：边界语义深探——"档恰在忙边界"
 * 的退化恒等。期望值三源：①退化恒等（设计 §2.4 承诺：MIN_POWER 在单档平台上 =
 * 每个 Job 跑该唯一档，守恒律与能量对同一组数字恰为 v1 公式）；②守恒律（BAT-E1
 * v1 线性律 E_vm = idle×T_sim + (max−idle)×T_busy）；③边界档速恒等（tier mips ==
 * VM 声明速 → scaledTierWorkMi 因子恰 1.0 → 时钟逐位 v1）。
 *
 * <p>攻击目标：若 MIN_POWER 的选档代码在单档表上退化出错（比如"末档"索引越界、
 * 或把缺省档误指其他档、或长度缩放在因子 1.0 上不恒等），本类全部红——
 * D2 的末档指派与 D1 的单档退化律必须在<b>策略非缺省</b>的路径上同时成立。</p>
 */
class FrequencyTierBoundaryDegenerationTest {

    @TempDir
    Path tempDir;

    private boolean logWasDisabled;
    private Path workflowDax;

    @BeforeEach
    void quietSimLog() {
        logWasDisabled = Log.isDisabled();
        Log.disable();
    }

    @AfterEach
    void restoreSimLog() {
        Log.setDisabled(logWasDisabled);
    }

    /**
     * V2 主断言：MIN_POWER × 单档平台（档速恰为 VM 声明速 1000——升/降频的公共边界）
     * vs v1 平面功率孪生平台：
     *
     * <ul>
     *   <li>每个计算 Job 恰指派该唯一档（tierMips=1000），簿记信封保持 null；</li>
     *   <li>时钟逐位一致（缩放因子 1.0：执行秒、makespan、任务窗全等）；</li>
     *   <li>逐档台账每 VM 只有一个键（1000.0），值 == 该 VM busy 并集；</li>
     *   <li>总能量与孪生 v1 平台<b>逐位相等</b>（0.0 容差），且 == v1 律手推（按
     *       metrics 的 end/busy 逐 VM 求 250×end + 150×busy）；</li>
     *   <li>MIN_POWER 的"更慢/更省"可区分性来源被攻击证伪：单档边界上它既不慢也不省
     *       ——耗时与能量都必须与 v1 恒等（防止退化路径偷跑出假 DVFS 效应）。</li>
     * </ul>
     */
    @Test
    void minPowerOnSingleTierPlatformIsExactlyTheV1LawOnTheSameNumbers() throws Exception {
        // v1 孪生平台无频档（非 DEFAULT 策略会被运行器拒绝），走缺省 DEFAULT 策略。
        SimulationReport tieredReport = run(singleTierPlatform(), FrequencyPolicy.MIN_POWER).report;
        SimulationReport plain = run(plainPoweredPlatform(), null).report;

        assertTrue(tieredReport.getMetrics().isWorkflowCompletedSuccessfully(), "tiered run must complete");
        assertTrue(plain.getMetrics().isWorkflowCompletedSuccessfully(), "plain run must complete");

        // 1. 每个 Job 恰在该唯一档（MIN_POWER=末档=唯一档）；簿记信封（stage-in）null。
        for (SimulationReport.JobOutcome job : tieredReport.getJobs()) {
            if (isCompute(job)) {
                assertNotNull(job.getTierMips(), "compute job must carry its tier on a tiered platform");
                assertEquals(1000.0, job.getTierMips().doubleValue(), 0.0, "the single declared tier");
            } else {
                assertNull(job.getTierMips(), "bookkeeping envelope stays v1-null");
            }
        }
        // v1 侧证书：无档证据面。
        for (SimulationReport.JobOutcome job : plain.getJobs()) {
            assertNull(job.getTierMips(), "v1 platform carries no tier evidence");
        }

        // 2. 时钟逐位一致（边界档速因子 1.0）。
        assertEquals(plain.getMakespan(), tieredReport.getMakespan(), 0.0, "schedule clock must be bit-identical");
        for (int i = 0; i < plain.getJobs().size(); i++) {
            assertEquals(
                    plain.getJobs().get(i).getExecutionTime(),
                    tieredReport.getJobs().get(i).getExecutionTime(),
                    0.0,
                    "per-job execution time identical at the boundary tier speed");
            assertEquals(
                    plain.getJobs().get(i).getStartTime(),
                    tieredReport.getJobs().get(i).getStartTime(),
                    0.0,
                    "per-job start identical");
        }

        // 3. 逐档台账：每 VM 恰一键（该档速），值 == busy 并集；舰队台账键集也只含该档。
        for (Map.Entry<Integer, SimulationMetrics.VmMetrics> entry :
                tieredReport.getMetrics().getVmMetrics().entrySet()) {
            Map<Double, Double> ledger =
                    tieredReport.getMetrics().getPerTierBusySecondsByVm().get(entry.getKey());
            if (entry.getValue().getModeledBusyIntervalSeconds() > 0.0) {
                assertNotNull(ledger, "busy VM must carry a ledger");
                assertEquals(1, ledger.size(), "single tier table must yield exactly one ledger key");
                assertEquals(
                        entry.getValue().getModeledBusyIntervalSeconds(),
                        ledger.values().iterator().next().doubleValue(),
                        1e-9,
                        "the one tier's busy must equal the busy union");
                assertEquals(
                        java.util.Arrays.asList(1000.0),
                        new ArrayList<Double>(ledger.keySet()),
                        "the one ledger key is the declared tier mips");
            }
        }
        assertEquals(1, tieredReport.getMetrics().getFleetPerTierBusySeconds().size(), "fleet ledger key set");

        // 4. 能量：与孪生 v1 平台逐位相等，且 == v1 律手推。
        assertEquals(
                plain.getEnergyAccounting().getTotalEnergyJoules(),
                tieredReport.getEnergyAccounting().getTotalEnergyJoules(),
                0.0,
                "MIN_POWER on a single-tier platform must conserve EXACTLY the v1 law");
        // v1 律手推（合法三源之【守恒律】）：Σ_vm idle×end + (max−idle)×busy_vm。
        double hand = 0.0;
        for (Map.Entry<Integer, SimulationMetrics.VmMetrics> entry :
                tieredReport.getMetrics().getVmMetrics().entrySet()) {
            hand += 250.0 * tieredReport.getMakespan()
                    + 150.0 * entry.getValue().getModeledBusyIntervalSeconds();
        }
        assertEquals(
                hand,
                tieredReport.getEnergyAccounting().getTotalEnergyJoules(),
                1e-6,
                "manual v1 conservation on the tier's power pair");
        // 孪生平台自己也是该手推值（同一数字两侧同一公式——退化恒等闭环）。
        assertEquals(
                hand,
                plain.getEnergyAccounting().getTotalEnergyJoules(),
                1e-6,
                "manual v1 conservation on the flat twin");

        // 5. 攻击证伪：单档边界上 MIN_POWER 不产生假的"更慢或更省"。
        //    （若退化路径错位数到不存在的"更慢档"，能量或时钟必然偏离 v1。）
        assertEquals(
                tieredReport.getEnergyAccounting().getPeakPowerWatts(),
                plain.getEnergyAccounting().getPeakPowerWatts(),
                0.0,
                "fleet peak power identical");
        assertEquals(
                tieredReport.getEnergyAccounting().getEnergyMakespanProductJouleSeconds(),
                plain.getEnergyAccounting().getEnergyMakespanProductJouleSeconds(),
                0.0,
                "EDP identical");
    }

    /**
     * 忙边界的台账归因语义：缺档簿记秒落默认档（= 单档表中唯一档）在 2-档平台上
     * 的对照——MIN_POWER 在 2 档平台选末档（500），簿记秒仍落首档（2000 默认档），
     * 逐档之和 == busy 并集（D2 台账分账恒等在策略档 ≠ 默认档时仍成立）。
     */
    @Test
    void bookkeepingSecondsFallAtTheDefaultTierWhileComputeFallsAtThePolicyTier() throws Exception {
        PlatformProfile platform = twoTierPlatform();
        SimulationReport report = run(platform, FrequencyPolicy.MIN_POWER).report;
        assertTrue(report.getMetrics().isWorkflowCompletedSuccessfully());
        for (SimulationReport.JobOutcome job : report.getJobs()) {
            if (isCompute(job)) {
                assertEquals(500.0, job.getTierMips().doubleValue(), 0.0, "MIN_POWER picks the LAST tier");
            }
        }
        for (Map.Entry<Integer, SimulationMetrics.VmMetrics> entry :
                report.getMetrics().getVmMetrics().entrySet()) {
            Map<Double, Double> ledger =
                    report.getMetrics().getPerTierBusySecondsByVm().get(entry.getKey());
            if (ledger != null) {
                double sum = 0.0;
                for (double busy : new TreeMap<Double, Double>(ledger).values()) {
                    sum += busy;
                }
                assertEquals(
                        entry.getValue().getModeledBusyIntervalSeconds(),
                        sum,
                        1e-9,
                        "per-tier busy must partition the busy union (default-tier bookkeeping included)");
                // 键集 ⊆ {500(计算), 2000(默认档簿记)}——表外键即证据链断裂。
                for (double keyMips : ledger.keySet()) {
                    assertTrue(
                            keyMips == 500.0 || keyMips == 2000.0,
                            "ledger keys must be the policy tier or the default tier: " + ledger.keySet());
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // fixtures（镜像 FrequencyTierAssignmentTest 的夹具纪律：串行链 DAG t1(10s)→t2(30s)）
    // ------------------------------------------------------------------

    private static final class PlatformReportHolder {

        final SimulationReport report;

        PlatformReportHolder(SimulationReport report) {
            this.report = report;
        }
    }

    private PlatformReportHolder run(PlatformProfile platform, FrequencyPolicy policy) throws Exception {
        SimulationConfig.Builder builder = SimulationConfig.builder(chainDax().toString(), 2)
                .planningAlgorithm(PlanningAlgorithm.SHARED_STORAGE_HEFT)
                .schedulingAlgorithm(SchedulingAlgorithm.STATIC)
                .fileSystem(ReplicaCatalog.FileSystem.SHARED)
                .dataMovementModel(DataMovementModel.legacyWorkflowsimV1())
                .randomSeed(20261029L);
        if (policy != null) {
            builder.frequencyPolicy(policy);
        }
        return new PlatformReportHolder(new SimulationRunner().run(builder.build(), platform));
    }

    /** 单档平台：唯一档 (1000/250/400)——档速恰为 VM 基速（升/降频公共边界）。 */
    private static PlatformProfile singleTierPlatform() {
        return tierPlatform(new double[][] {{1000.0, 250.0, 400.0}});
    }

    /** 2 档平台：(2000/250/400)(默认), (500/210/260)（末）。 */
    private static PlatformProfile twoTierPlatform() {
        return tierPlatform(new double[][] {{2000.0, 250.0, 400.0}, {500.0, 210.0, 260.0}});
    }

    /** v1 平面功率孪生：与单档平台同一对功率数字 (250/400)。 */
    private static PlatformProfile plainPoweredPlatform() {
        PlatformProfile.Builder builder = PlatformProfile.builder("v2-plain-twin");
        builder.addHost(new PlatformProfile.HostSpec(0, 2, 2000.0, 2048, 10_000L, 1_000_000L));
        builder.addVm(plainVmSpec(0));
        builder.addHost(new PlatformProfile.HostSpec(1, 2, 2000.0, 2048, 10_000L, 1_000_000L));
        builder.addVm(plainVmSpec(1));
        return builder.build();
    }

    private static PlatformProfile tierPlatform(double[][] tierRows) {
        List<PlatformProfile.FrequencyTier> tiers = new ArrayList<PlatformProfile.FrequencyTier>();
        for (double[] row : tierRows) {
            tiers.add(new PlatformProfile.FrequencyTier(row[0], row[1], row[2]));
        }
        PlatformProfile.Builder builder = PlatformProfile.builder("v2-tier-test");
        builder.addHost(new PlatformProfile.HostSpec(0, 2, 2000.0, 2048, 10_000L, 1_000_000L));
        builder.addVm(tieredVmSpec(0, tiers));
        builder.addHost(new PlatformProfile.HostSpec(1, 2, 2000.0, 2048, 10_000L, 1_000_000L));
        builder.addVm(tieredVmSpec(1, tiers));
        return builder.build();
    }

    private static PlatformProfile.VmSpec tieredVmSpec(int id, List<PlatformProfile.FrequencyTier> tiers) {
        return new PlatformProfile.VmSpec(
                id,
                1000.0,
                1,
                512,
                1_000L,
                10_000L,
                "Xen",
                PlatformProfile.CloudletSchedulerMode.SPACE_SHARED,
                null,
                0.0,
                0.0,
                null,
                tiers);
    }

    private static PlatformProfile.VmSpec plainVmSpec(int id) {
        return new PlatformProfile.VmSpec(
                id,
                1000.0,
                1,
                512,
                1_000L,
                10_000L,
                "Xen",
                PlatformProfile.CloudletSchedulerMode.SPACE_SHARED,
                null,
                250.0,
                400.0,
                null,
                null);
    }

    private Path chainDax() throws Exception {
        if (workflowDax == null) {
            StringBuilder dax = new StringBuilder();
            dax.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
            dax.append("<adag version=\"3.3\" count=\"2\">\n");
            dax.append("  <job id=\"t1\" name=\"a\" runtime=\"10\"/>\n");
            dax.append("  <job id=\"t2\" name=\"b\" runtime=\"30\"/>\n");
            dax.append("  <child ref=\"t2\"><parent ref=\"t1\"/></child>\n");
            dax.append("</adag>\n");
            workflowDax = Paths.get(tempDir.toFile().getAbsolutePath(), "v2-chain.dax");
            Files.write(workflowDax, dax.toString().getBytes(StandardCharsets.UTF_8));
        }
        return workflowDax;
    }

    private static boolean isCompute(SimulationReport.JobOutcome job) {
        return job.getClassType() == ClassType.COMPUTE.value;
    }
}
