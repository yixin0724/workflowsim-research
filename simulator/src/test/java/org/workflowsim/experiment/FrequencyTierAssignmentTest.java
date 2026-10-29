package org.workflowsim.experiment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
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
import org.workflowsim.exception.SimulationConfigurationException;
import org.workflowsim.platform.PlatformProfile;
import org.workflowsim.utils.Parameters.ClassType;
import org.workflowsim.utils.Parameters.PlanningAlgorithm;
import org.workflowsim.utils.Parameters.SchedulingAlgorithm;
import org.workflowsim.utils.ReplicaCatalog;
import org.workflowsim.utils.SimulationConfig;
import org.workflowsim.utils.SimulationConfig.FrequencyPolicy;

/**
 * BAT-D2（2026-10-29）——每作业频档指派 + 证据链 + 逐档守恒台账的红→绿集成测试。
 *
 * <p>期望值合法来源（三源）：①扩展守恒律
 * {@code E_vm = P_idle(default)×T_sim + Σ档[(P_max(档)−P_idle(默认))×busy(档)]}
 * （docs/advanced/DVFS_DIMENSION_DESIGN.md §2.3）；②D2 任务契约的 EDP 估计式
 * {@code EDP_t = (P_max(t)·T_t + P_idle(default)·makespan_est) × T_t}（§2.2），
 * 数字由测试注释手推；③退化恒等（无档/单档 = v1 律，BAT-D1 已锚）。禁止把实现
 * 输出当期望值——所有守恒断言都对台账（metrics 逐档忙秒 + 平台功率表）按律复算。</p>
 */
class FrequencyTierAssignmentTest {

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

    // ------------------------------------------------------------------
    // 1. DEFAULT：首档指派 + 逐档台账分账 + 全默认档时两种口径恒等
    // ------------------------------------------------------------------

    /**
     * SHARED_STORAGE_HEFT 两任务链 DAG（10s/30s @1000 参考吞吐 → 10000/30000 MI），
     * 2-VM 舰队，3 档平台（自基频 1000 降序 2000/1000/500）。断言：每个计算
     * JobOutcome 携带非 null tierMips；逐档忙秒之和==该 VM busy 并集；舰队合计
     * ==逐 VM 合计；逐档守恒律手算==报告能量；全默认档时逐档口径与 D1"整段
     * 默认档"口径相等（区别恒等分支）。
     */
    @Test
    void defaultPolicyAssignsFirstTierWithPerTierLedgerPartition() throws Exception {
        PlatformProfile platform = threeTierPlatform();
        SimulationReport report = run(platform, FrequencyPolicy.DEFAULT);

        assertTrue(report.getMetrics().isWorkflowCompletedSuccessfully());
        for (SimulationReport.JobOutcome job : report.getJobs()) {
            if (isCompute(job)) {
                assertNotNull(job.getTierMips(), "compute job must carry its assigned tier");
                assertEquals(2000.0, job.getTierMips().doubleValue(), 0.0, "DEFAULT = FIRST tier");
            } else {
                assertNull(job.getTierMips(), "non-compute attempts must stay v1-null (bookkeeping)");
            }
        }

        // 台账分账恒等：每 VM 逐档忙秒之和 == 该 VM 总 busy 并集秒。
        double fleetSumsFromLedger = 0.0;
        for (Map.Entry<Integer, SimulationMetrics.VmMetrics> entry :
                report.getMetrics().getVmMetrics().entrySet()) {
            double busyUnion = entry.getValue().getModeledBusyIntervalSeconds();
            Map<Double, Double> tiers =
                    report.getMetrics().getPerTierBusySecondsByVm().get(entry.getKey());
            if (busyUnion > 0.0) {
                assertNotNull(tiers, "busy VM must have a per-tier ledger entry");
            }
            double ledgerSum = 0.0;
            if (tiers != null) {
                for (double busy : tiers.values()) {
                    ledgerSum += busy;
                }
            }
            assertEquals(busyUnion, ledgerSum, 1e-9, "per-tier busy must partition the busy union");
            fleetSumsFromLedger += ledgerSum;
        }
        double fleetFromFleetMap = 0.0;
        for (double busy : report.getMetrics().getFleetPerTierBusySeconds().values()) {
            fleetFromFleetMap += busy;
        }
        assertEquals(
                fleetSumsFromLedger,
                fleetFromFleetMap,
                1e-9,
                "fleet per-tier aggregate must equal the per-VM ledger sums");

        // 守恒律（§2.3）按台账手算 vs 报告能量。
        ManualEnergy hand = handConservation(report, platform);
        assertEquals(
                hand.perTierTotal,
                report.getEnergyAccounting().getTotalEnergyJoules(),
                1e-6,
                "per-tier conservation law hand recompute");
        // 全默认档运行：逐档口径与 D1 全默认档口径相等（可证明的恒等分支）。
        assertEquals(
                hand.allAtDefaultTotal,
                hand.perTierTotal,
                1e-6,
                "all jobs at the DEFAULT tier: per-tier and all-at-default accountings agree");
        assertNotEquals(
                0.0,
                report.getEnergyAccounting().getTotalEnergyJoules(),
                "tiered platform must be" + " powered (BAT-D2 fixes the D1 dead gate that zeroed tiered platforms)");
    }

    // ------------------------------------------------------------------
    // 2. MIN_POWER：末档指派 + 明显拉长 + 逐档口径与全默认档口径可区分
    // ------------------------------------------------------------------

    /**
     * 同一 3 档平台：MIN_POWER 全部跑末档（500）→ 每 MI 工作墙钟翻倍
     * （exec = work/500，档律手推：10000 MI→20s、30000 MI→60s），makespan 显著
     * 大于 DEFAULT 运行（×4 vs ×0.5 速度）；任一作业在非默认档时，逐档守恒与
     * D1 全默认档口径必然不同——区别由两条独立手算公式给出，不背书实现值。
     */
    @Test
    void minPowerRunsAtLowestTierTakesNoticeablyLongerAndSplitsConservation() throws Exception {
        PlatformProfile platform = threeTierPlatform();
        SimulationReport min = run(platform, FrequencyPolicy.MIN_POWER);
        SimulationReport def = run(platform, FrequencyPolicy.DEFAULT);

        assertTrue(min.getMetrics().isWorkflowCompletedSuccessfully());
        for (SimulationReport.JobOutcome job : min.getJobs()) {
            if (isCompute(job)) {
                assertEquals(500.0, job.getTierMips().doubleValue(), 0.0, "MIN_POWER = LAST tier");
                // 档律：exec = work/500（CloudSim 最小事件间隔 0.1s 的精度预算）。
                double expectedExec = job.getTaskIds().contains(Integer.valueOf(1)) ? 20.0 : 60.0;
                assertEquals(
                        expectedExec,
                        job.getExecutionTime(),
                        0.11,
                        "declared work at the 500 MIPS tier must double the runtime");
            }
        }
        assertTrue(
                min.getMakespan() > 3.0 * def.getMakespan(),
                "MIN_POWER (0.5x speed) must have a clearly larger makespan than DEFAULT (2x speed): min="
                        + min.getMakespan() + " default=" + def.getMakespan());

        // 台账键面：未指派的非计算尝试（簿记信封）按平台默认档归因——合法键只有
        // 末档（计算工作）与首档（簿记），任何其他键都是证据链断裂。
        for (Map<Double, Double> tiers :
                min.getMetrics().getPerTierBusySecondsByVm().values()) {
            for (double keyMips : tiers.keySet()) {
                assertTrue(
                        keyMips == 500.0 || keyMips == 2000.0,
                        "MIN_POWER ledger keys must be the last tier (compute) or the default tier " + "(bookkeeping): "
                                + tiers);
            }
        }
        // 台账值面：每 VM 的末档忙秒 == 该 VM 上计算 Job 执行秒之和（簿记秒落默认档）。
        Map<Integer, Double> computeBusyPerVm = new java.util.LinkedHashMap<Integer, Double>();
        for (SimulationReport.JobOutcome job : min.getJobs()) {
            if (isCompute(job)) {
                computeBusyPerVm.merge(
                        Integer.valueOf(job.getVmId()), Double.valueOf(job.getExecutionTime()), Double::sum);
            }
        }
        for (Map.Entry<Integer, Map<Double, Double>> perVm :
                min.getMetrics().getPerTierBusySecondsByVm().entrySet()) {
            double lastTierBusy = perVm.getValue().getOrDefault(Double.valueOf(500.0), Double.valueOf(0.0));
            assertEquals(
                    computeBusyPerVm.getOrDefault(perVm.getKey(), Double.valueOf(0.0)),
                    lastTierBusy,
                    1e-9,
                    "last-tier busy must equal compute-job execution seconds on VM " + perVm.getKey());
        }

        ManualEnergy hand = handConservation(min, platform);
        assertEquals(
                hand.perTierTotal,
                min.getEnergyAccounting().getTotalEnergyJoules(),
                1e-6,
                "per-tier conservation law hand recompute (all busy at 500)");
        assertTrue(
                Math.abs(hand.allAtDefaultTotal - hand.perTierTotal) > 1.0,
                "any busy second at a non-default tier must change the total: per-tier (" + hand.perTierTotal
                        + ") vs all-at-default (" + hand.allAtDefaultTotal + ")");
        assertNotEquals(0.0, min.getEnergyAccounting().getTotalEnergyJoules(), "tiered platform must be powered");
    }

    // ------------------------------------------------------------------
    // 3. EDP_HEURISTIC：功率非对称档表上的手推中档选择（三机制身份区分）
    // ------------------------------------------------------------------

    /**
     * 功率非对称档表（2000@max4000W / 1000@280W / 500@260W，默认档怠速 250W）。
     * EDP 手推（D2 契约公式 EDP_t=(P_max(t)·T_t + P_idle(default)·(clock+T_t))×T_t）：
     *
     * <p>t1（10000 MI，派发时刻 clock≈0）：A(2000):T=5, E=4000×5+250×5=21250→EDP
     * 106250；B(1000):T=10, E=280×10+250×10=5300→EDP 53000；C(500):T=20,
     * E=260×20+250×20=10200→EDP 204000 ⇒ B。</p>
     *
     * <p>t2（30000 MI，t1 完成后派发 clock≈10）：A:T=15, E=4000×15+250×25=66250
     * →EDP 993750；B:T=30, E=280×30+250×40=18400→EDP 552000；C:T=60,
     * E=260×60+250×70=33100→EDP 1986000 ⇒ B。</p>
     *
     * <p>两作业都取中档 1000——EDP 与 DEFAULT（首档 2000）、MIN_POWER（末档 500）
     * 在同一平台上做出三种不同选择，机制身份可区分；运行完整完成是前提。</p>
     */
    @Test
    void edpHeuristicPicksHandComputedMiddleTierOnPowerAsymmetricTable() throws Exception {
        PlatformProfile platform = edpAsymmetricTierPlatform();
        SimulationReport edp = run(platform, FrequencyPolicy.EDP_HEURISTIC);

        assertTrue(edp.getMetrics().isWorkflowCompletedSuccessfully(), "EDP run must complete");
        List<Double> declaredTiers = new ArrayList<Double>();
        for (PlatformProfile.FrequencyTier tier : platform.getVms().get(0).getFrequencyTiers()) {
            declaredTiers.add(Double.valueOf(tier.getMips()));
        }
        for (SimulationReport.JobOutcome job : edp.getJobs()) {
            if (isCompute(job)) {
                assertNotNull(job.getTierMips(), "EDP must assign a tier to every compute job");
                assertTrue(
                        declaredTiers.contains(job.getTierMips()),
                        "assigned tier must come from the declared set: " + job.getTierMips());
                assertEquals(1000.0, job.getTierMips().doubleValue(), 0.0, "hand-computed EDP winner");
            }
        }
        // 机制身份三脚架（同平台不同策略 → 不同档）：
        assertEquals(2000.0, firstComputeTier(run(platform, FrequencyPolicy.DEFAULT)), 0.0, "DEFAULT picks first");
        assertEquals(500.0, firstComputeTier(run(platform, FrequencyPolicy.MIN_POWER)), 0.0, "MIN_POWER picks last");
        assertEquals(1000.0, firstComputeTier(edp), 0.0, "EDP_HEURISTIC picks the hand-computed middle");
    }

    // ------------------------------------------------------------------
    // 4. 退化：未配置策略 = DEFAULT；单档平台 = v1 能量
    // ------------------------------------------------------------------

    /** 未调用 frequencyPolicy 的配置必须与显式 DEFAULT 逐位同轨（缺省即 DEFAULT）。 */
    @Test
    void unsetPolicyBehavesExactlyLikeExplicitDefault() throws Exception {
        PlatformProfile platform = threeTierPlatform();
        SimulationReport unset = run(platform, null);
        SimulationReport explicit = run(platform, FrequencyPolicy.DEFAULT);

        assertEquals(unset.getMakespan(), explicit.getMakespan(), 0.0, "same tier decisions => same clock");
        List<Double> unsetTiers = computeTierMultiset(unset);
        List<Double> explicitTiers = computeTierMultiset(explicit);
        assertEquals(explicitTiers, unsetTiers, "unset policy must assign the DEFAULT (first) tier");
        assertEquals(
                unset.getEnergyAccounting().getTotalEnergyJoules(),
                explicit.getEnergyAccounting().getTotalEnergyJoules(),
                0.0,
                "same accountings");
    }

    /**
     * 每台 VM 只声明单档（=把 v1 功率写进频档表，BAT-D1 的单档退化律）：运行序列
     * 与 v1 平面功率平台逐位一致（缩放因子 1.0），能量恒等；差异仅在证据面
     * （tierMips=1000 记录在案 vs v1 缺键）。
     */
    @Test
    void singleTierPerVmPlatformDegeneratesToV1Energy() throws Exception {
        SimulationReport tiered = run(singleTierPlatform(), null);
        SimulationReport plain = run(plainPoweredPlatform(), null);

        assertTrue(tiered.getMetrics().isWorkflowCompletedSuccessfully());
        assertTrue(plain.getMetrics().isWorkflowCompletedSuccessfully());
        assertEquals(plain.getMakespan(), tiered.getMakespan(), 0.0, "scale factor 1.0 keeps the clock identical");
        assertEquals(
                0,
                Double.compare(
                        plain.getEnergyAccounting().getTotalEnergyJoules(),
                        tiered.getEnergyAccounting().getTotalEnergyJoules()),
                "single-tier platform must conserve EXACTLY like the v1 plain-power platform");
        for (SimulationReport.JobOutcome job : tiered.getJobs()) {
            if (isCompute(job)) {
                assertEquals(1000.0, job.getTierMips().doubleValue(), 0.0, "the single declared tier");
            }
        }
        for (SimulationReport.JobOutcome job : plain.getJobs()) {
            if (isCompute(job)) {
                assertNull(job.getTierMips(), "v1 plain platform carries no tier evidence");
            }
        }
        // 台账仍分账成立（全部落在唯一档）。
        for (Map<Double, Double> tiers :
                tiered.getMetrics().getPerTierBusySecondsByVm().values()) {
            assertEquals(1, tiers.size(), "single tier platform ledger");
        }
    }

    // ------------------------------------------------------------------
    // 5. 证据面：manifest 的 jobs 区 tierMips 仅在非 null 时出现
    // ------------------------------------------------------------------

    /**
     * tierMips 在结果清单中的合同：非 null → 键出现且取指派值；null（非计算尝试）
     * → 键缺省；v1 平台运行 → jobs 序列化面原样（identity 快路径）且全文不含
     * "tierMips" 键（外层 Gson serializeNulls 不因此产生 null 键）。
     */
    @Test
    void manifestCarriesTierMipsOnlyWhenAssigned() throws Exception {
        SimulationReport tiered = run(threeTierPlatform(), FrequencyPolicy.MIN_POWER);
        Map<String, Object> tieredManifest =
                ExperimentManifestWriter.toManifest(tiered, new ArrayList<Map<String, Object>>(), null);
        Object tieredJobs = ((Map<?, ?>) tieredManifest.get("result")).get("jobs");
        assertTrue(tieredJobs instanceof List, "view mode produces a list");
        int tieredComputeWithKey = 0;
        for (Object entry : (List<?>) tieredJobs) {
            com.google.gson.JsonObject job = (com.google.gson.JsonObject) entry;
            boolean compute = job.get("classType").getAsInt() == ClassType.COMPUTE.value;
            assertEquals(compute, job.has("tierMips"), "tierMips appears exactly for compute attempts: " + job);
            if (compute) {
                assertEquals(500.0, job.get("tierMips").getAsDouble(), 0.0, "MIN_POWER evidence");
                tieredComputeWithKey++;
            }
        }
        assertTrue(tieredComputeWithKey >= 2, "chain DAG must have at least two compute attempts");

        SimulationReport plain = run(plainPoweredPlatform(), null);
        Map<String, Object> plainManifest =
                ExperimentManifestWriter.toManifest(plain, new ArrayList<Map<String, Object>>(), null);
        Object plainJobs = ((Map<?, ?>) plainManifest.get("result")).get("jobs");
        assertSame(plain.getJobs(), plainJobs, "v1 runs keep the lazy reflection path (byte-identical manifest)");
        String serialized = new com.google.gson.GsonBuilder()
                .serializeNulls()
                .setPrettyPrinting()
                .create()
                .toJson(plainManifest);
        assertFalse(
                serialized.contains("tierMips"),
                "v1 manifests must never carry a tierMips key (null = absent = v1 legacy)");
    }

    // ------------------------------------------------------------------
    // 5b. 证据面：manifest 平台节的 frequencyTiers（D3 前提，Python 审计器消费）
    // ------------------------------------------------------------------

    /**
     * BAT-D2 后续收口（2026-10-29）：平台节逐 VM 序列化频档表。tiered 运行 →
     * {@code platform.vms[].frequencyTiers} 为表序（声明降序）的
     * {@code {mips,idlePowerWatts,maxPowerWatts}} 三元组列表——与声明表逐值相等（期望值
     * 三源：契约 DVFS_DIMENSION_DESIGN.md §2.5 的档表 schema × 测试声明的平台表，非实现
     * 输出抄录）；v1 平面功率运行 → 全文不含 "frequencyTiers" 键（legacy 平台节逐位不变，
     * 与 carbonWindows 的"仅非空时序列化"同款键缺省合同）。tiered VM 的平面
     * idlePowerWatts/maxPowerWatts 恒为 0.0（单一真源），同步锚定——审计器据此区分
     * powered 的两路真源。
     */
    @Test
    void manifestPlatformSerializesPerVmFrequencyTiers() throws Exception {
        SimulationReport tiered = run(threeTierPlatform(), FrequencyPolicy.MIN_POWER);
        Map<String, Object> tieredManifest =
                ExperimentManifestWriter.toManifest(tiered, new ArrayList<Map<String, Object>>(), null);
        List<?> tieredVms = (List<?>) ((Map<?, ?>) tieredManifest.get("platform")).get("vms");
        assertEquals(2, tieredVms.size(), "fixture platform declares two VMs");
        for (Object entry : tieredVms) {
            Map<?, ?> vm = (Map<?, ?>) entry;
            assertTrue(vm.containsKey("frequencyTiers"), "tiered VM must carry its frequencyTiers table: " + vm);
            List<?> tiers = (List<?>) vm.get("frequencyTiers");
            assertEquals(3, tiers.size(), "three-tier platform must serialize three tiers");
            double[][] declared = {{2000.0, 250.0, 400.0}, {1000.0, 220.0, 280.0}, {500.0, 210.0, 260.0}};
            for (int index = 0; index < tiers.size(); index++) {
                Map<?, ?> tier = (Map<?, ?>) tiers.get(index);
                assertTrue(
                        tier.containsKey("mips")
                                && tier.containsKey("idlePowerWatts")
                                && tier.containsKey("maxPowerWatts"),
                        "tier triple must expose mips/idlePowerWatts/maxPowerWatts: " + tier);
                assertEquals(declared[index][0], ((Number) tier.get("mips")).doubleValue(), 0.0, "tier mips");
                assertEquals(
                        declared[index][1],
                        ((Number) tier.get("idlePowerWatts")).doubleValue(),
                        0.0,
                        "tier idlePowerWatts");
                assertEquals(
                        declared[index][2],
                        ((Number) tier.get("maxPowerWatts")).doubleValue(),
                        0.0,
                        "tier maxPowerWatts");
            }
            // 单一真源：tiered VM 的平面功率字段在平台节必须为 0（两路互斥由 VmSpec 保证）。
            assertEquals(
                    0.0,
                    ((Number) vm.get("idlePowerWatts")).doubleValue(),
                    0.0,
                    "tiered VM keeps its flat power fields at zero (one source of truth)");
            assertEquals(
                    0.0,
                    ((Number) vm.get("maxPowerWatts")).doubleValue(),
                    0.0,
                    "tiered VM keeps its flat power fields at zero (one source of truth)");
        }

        SimulationReport plain = run(plainPoweredPlatform(), null);
        Map<String, Object> plainManifest =
                ExperimentManifestWriter.toManifest(plain, new ArrayList<Map<String, Object>>(), null);
        String serialized = new com.google.gson.GsonBuilder()
                .serializeNulls()
                .setPrettyPrinting()
                .create()
                .toJson(plainManifest);
        assertFalse(
                serialized.contains("frequencyTiers"),
                "v1 plain-power manifests must NOT carry a frequencyTiers key (empty table = undeclared = v1 legacy)");
    }

    // ------------------------------------------------------------------
    // 6. 前提拒绝：非 DEFAULT 策略不可在全无档平台上静默失效
    // ------------------------------------------------------------------

    /** MIN_POWER + 全平面功率平台 → 显式拒绝并引用 frequencyTiers 条款（不静默 v1）。 */
    @Test
    void nonDefaultPolicyOnPlainPlatformFailsFast() throws Exception {
        SimulationConfigurationException rejection = assertThrows(
                SimulationConfigurationException.class,
                () -> run(plainPoweredPlatform(), FrequencyPolicy.MIN_POWER),
                "an inert policy must be rejected, not silently ignored");
        assertTrue(
                rejection.getMessage().contains("frequencyTiers"),
                "rejection must cite the frequencyTiers clause: " + rejection.getMessage());
    }

    // ------------------------------------------------------------------
    // fixtures
    // ------------------------------------------------------------------

    /** 2-VM/2-Host 平台，每 VM 基频 1000、3 档（2000/1000/500）。 */
    private static PlatformProfile threeTierPlatform() {
        return tierPlatform(new double[][] {{2000.0, 250.0, 400.0}, {1000.0, 220.0, 280.0}, {500.0, 210.0, 260.0}});
    }

    /** EDP 功率非对称档表（首档满载 4000W——择档有真实权衡）。 */
    private static PlatformProfile edpAsymmetricTierPlatform() {
        return tierPlatform(new double[][] {{2000.0, 250.0, 4000.0}, {1000.0, 220.0, 280.0}, {500.0, 210.0, 260.0}});
    }

    private static PlatformProfile tierPlatform(double[][] tierRows) {
        List<PlatformProfile.FrequencyTier> tiers = new ArrayList<PlatformProfile.FrequencyTier>();
        for (double[] row : tierRows) {
            tiers.add(new PlatformProfile.FrequencyTier(row[0], row[1], row[2]));
        }
        PlatformProfile.Builder builder = PlatformProfile.builder("dvfs-tier-test");
        builder.addHost(new PlatformProfile.HostSpec(0, 2, 2000.0, 2048, 10_000L, 1_000_000L));
        builder.addVm(tieredVmSpec(0, tiers));
        builder.addHost(new PlatformProfile.HostSpec(1, 2, 2000.0, 2048, 10_000L, 1_000_000L));
        builder.addVm(tieredVmSpec(1, tiers));
        return builder.build();
    }

    /** 单档 VM（1000/250/400）——D1 单档退化律的运行级对应。 */
    private static PlatformProfile singleTierPlatform() {
        return tierPlatform(new double[][] {{1000.0, 250.0, 400.0}});
    }

    /** v1 平面功率平台（250/400）——与单档平台功率对相同。 */
    private static PlatformProfile plainPoweredPlatform() {
        PlatformProfile.Builder builder = PlatformProfile.builder("dvfs-plain-test");
        builder.addHost(new PlatformProfile.HostSpec(0, 2, 2000.0, 2048, 10_000L, 1_000_000L));
        builder.addVm(plainVmSpec(0));
        builder.addHost(new PlatformProfile.HostSpec(1, 2, 2000.0, 2048, 10_000L, 1_000_000L));
        builder.addVm(plainVmSpec(1));
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
                null);
    }

    /**
     * 串行链 DAG：t1（10s）→ t2（30s）。runtime 秒 ×参考吞吐 1000 → 10000/30000
     * MI 的每 PE 声明工作。
     */
    private Path chainDax() throws Exception {
        if (workflowDax == null) {
            StringBuilder dax = new StringBuilder();
            dax.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
            dax.append("<adag version=\"3.3\" count=\"2\">\n");
            dax.append("  <job id=\"t1\" name=\"a\" runtime=\"10\"/>\n");
            dax.append("  <job id=\"t2\" name=\"b\" runtime=\"30\"/>\n");
            dax.append("  <child ref=\"t2\"><parent ref=\"t1\"/></child>\n");
            dax.append("</adag>\n");
            workflowDax = Paths.get(tempDir.toFile().getAbsolutePath(), "dvfs-chain.dax");
            Files.write(workflowDax, dax.toString().getBytes(StandardCharsets.UTF_8));
        }
        return workflowDax;
    }

    /** 真实运行：SimulationRunner + SimulationConfig + 声明了频档的平台。 */
    private SimulationReport run(PlatformProfile platform, FrequencyPolicy policy) throws Exception {
        SimulationConfig.Builder builder = SimulationConfig.builder(chainDax().toString(), 2)
                .planningAlgorithm(PlanningAlgorithm.SHARED_STORAGE_HEFT)
                .schedulingAlgorithm(SchedulingAlgorithm.STATIC)
                .fileSystem(ReplicaCatalog.FileSystem.SHARED)
                .dataMovementModel(DataMovementModel.legacyWorkflowsimV1())
                .randomSeed(20261029L);
        if (policy != null) {
            builder.frequencyPolicy(policy);
        }
        return new SimulationRunner().run(builder.build(), platform);
    }

    private static boolean isCompute(SimulationReport.JobOutcome job) {
        return job.getClassType() == ClassType.COMPUTE.value;
    }

    private static double firstComputeTier(SimulationReport report) {
        for (SimulationReport.JobOutcome job : report.getJobs()) {
            if (isCompute(job)) {
                return job.getTierMips().doubleValue();
            }
        }
        throw new IllegalStateException("run has no compute job");
    }

    private static List<Double> computeTierMultiset(SimulationReport report) {
        List<Double> tiers = new ArrayList<Double>();
        for (SimulationReport.JobOutcome job : report.getJobs()) {
            if (isCompute(job)) {
                tiers.add(job.getTierMips());
            }
        }
        return tiers;
    }

    /** §2.3 扩展守恒律的手工复算结果对：逐档口径 vs D1 整段默认档口径。 */
    private static final class ManualEnergy {
        final double perTierTotal;
        final double allAtDefaultTotal;

        ManualEnergy(double perTierTotal, double allAtDefaultTotal) {
            this.perTierTotal = perTierTotal;
            this.allAtDefaultTotal = allAtDefaultTotal;
        }
    }

    /**
     * 从台账（metrics 逐档忙秒 + VmMetrics 并集 + 平台功率表）按两条独立公式
     * 手推能量：perTier = idle(默认)×T + Σ档(P_max(档)−idle(默认))×busy(档)；
     * allAtDefault = idle(默认)×T + (P_max(默认)−idle(默认))×busy(总)。
     */
    private static ManualEnergy handConservation(SimulationReport report, PlatformProfile platform) {
        double makespan = report.getMakespan();
        Map<Integer, Map<Double, Double>> ledger = report.getMetrics().getPerTierBusySecondsByVm();
        double perTierTotal = 0.0;
        double allAtDefaultTotal = 0.0;
        for (Map.Entry<Integer, SimulationMetrics.VmMetrics> entry :
                report.getMetrics().getVmMetrics().entrySet()) {
            PlatformProfile.VmSpec spec = specOf(platform, entry.getKey());
            PlatformProfile.FrequencyTier defaultTier = spec.defaultFrequencyTier();
            double busyUnion = entry.getValue().getModeledBusyIntervalSeconds();
            double ePerTier = defaultTier.getIdlePowerWatts() * makespan;
            Map<Double, Double> tiers = ledger.get(entry.getKey());
            if (tiers != null) {
                for (Map.Entry<Double, Double> tier : new TreeMap<Double, Double>(tiers).entrySet()) {
                    ePerTier += (maxOfTier(spec, tier.getKey()) - defaultTier.getIdlePowerWatts()) * tier.getValue();
                }
            }
            perTierTotal += ePerTier;
            allAtDefaultTotal += defaultTier.getIdlePowerWatts() * makespan
                    + (defaultTier.getMaxPowerWatts() - defaultTier.getIdlePowerWatts()) * busyUnion;
        }
        return new ManualEnergy(perTierTotal, allAtDefaultTotal);
    }

    private static PlatformProfile.VmSpec specOf(PlatformProfile platform, int vmId) {
        for (PlatformProfile.VmSpec spec : platform.getVms()) {
            if (spec.getId() == vmId) {
                return spec;
            }
        }
        throw new IllegalStateException("VM " + vmId + " not in test platform");
    }

    private static double maxOfTier(PlatformProfile.VmSpec spec, double mips) {
        for (PlatformProfile.FrequencyTier tier : spec.getFrequencyTiers()) {
            if (tier.getMips() == mips) {
                return tier.getMaxPowerWatts();
            }
        }
        throw new IllegalStateException("ledger tier " + mips + " not declared by VM " + spec.getId());
    }
}
