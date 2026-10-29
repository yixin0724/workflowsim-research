package org.workflowsim.experiments.rerun;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.cloudbus.cloudsim.Log;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.workflowsim.data.DataMovementModel;
import org.workflowsim.experiment.ExperimentArtifactWriter;
import org.workflowsim.experiment.SimulationReport;
import org.workflowsim.experiment.SimulationRunner;
import org.workflowsim.platform.PlatformProfile;
import org.workflowsim.utils.Parameters.PlanningAlgorithm;
import org.workflowsim.utils.Parameters.SchedulingAlgorithm;
import org.workflowsim.utils.ReplicaCatalog;
import org.workflowsim.utils.SimulationConfig;
import org.workflowsim.utils.SimulationConfig.FrequencyPolicy;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * BAT-V4（2026-10-29 测试工程师对抗轮，DVFS 第五轮）：DVFS 证据的复跑兼容性——
 * "诚实大红，不是崩溃、不是-green、不是静默"。
 *
 * <p>本轮（对 D5 冻结基准 bundle 的真实 CLI 复跑）发现的两个真实缺陷，此处红→绿锁：</p>
 * <ol>
 *   <li><b>运行期拒绝逃逸 verdict 契约（真实缺陷 #1）</b>：三维（tiered+carbon）证据的
 *       manifest 在重建时被 {@code ManifestConfigRebuilder} 剥离频档（文档化的前向契约），
 *       重建出的"无功率+碳强度"非法半声明平台在 {@code SimulationReport} 构造期抛
 *       {@code IllegalArgumentException}（BAT-C1 半声明律）——该异常此前<b>未被任何
 *       rerun 捕获</b>：冒泡到 CLI 的 usage 兜底（"rerun refused"，exit 64），<b>两份报告
 *       一页不写</b>——恰是契约 §执行流程"运行期拒绝是 RECONSTRUCTION_REJECTED 的合法
 *       形态"要消灭的崩溃类失败。修复：RerunExecutor 把执行阶段的
 *       IllegalArgumentException/IllegalStateException/ SimulationConfigurationException
 *       统一映射 RECONSTRUCTION_REJECTED（照写报告，exit 4）。</li>
 *   <li><b>v1 历史证据的假核心分歧（真实缺陷 #2）</b>：D4 起配置节无条件写
 *       frequencyPolicy——pre-D4 冻结证据（power-v1/carbon-v1/standard-v1 三套基准）
 *       复跑时全部新增 {@code /configuration/frequencyPolicy &lt;missing&gt; vs "DEFAULT"}
 *       单点核心分歧 → 整批从 IDENTICAL_CORE 退化 DIVERGED（本测试以"删键还原
 *       pre-D4 形状"的等值 bundle 复现；修复在 ExperimentManifestWriter 的键缺省
 *       发射规则，见 FrequencyTierManifestEmissionTest）。</li>
 * </ol>
 *
 * <p>期望值三源：RERUN_DIFF_CONTRACT.md（verdict 语义/退出码表）+ DVFS_DIMENSION_
 * DESIGN.md §2.4（"tiered manifest 复跑按记录的红差异失败，不静默"——无碳 tiered
 * 形态走 differ 大红；三维形态在重建后被当前代码运行期拒绝 → verdict 契约的
 * RECONSTRUCTION_REJECTED）。v1（无档平面功率）证据的复跑必须持续 IDENTICAL_CORE。</p>
 */
class DvfsRerunLoudRedIntegrationTest {

    @TempDir
    Path directory;

    private boolean logWasDisabled;

    @BeforeEach
    void quiet() {
        logWasDisabled = Log.isDisabled();
        Log.disable();
    }

    @AfterEach
    void restore() {
        Log.setDisabled(logWasDisabled);
    }

    /**
     * 缺陷 #1 的红→绿主锚（修复前：IllegalArgumentException 冒泡，测试在此抛出——
     * 无报告、无 verdict；修复后：RECONSTRUCTION_REJECTED 报告落盘）。
     */
    @Test
    void triDimensionalDvfsEvidenceRerunIsAnHonestReportedRejectionNotACrash() throws Exception {
        Path study = directory.resolve("tri");
        Path run = runAndWriteEvidence(study, "runs/tri", tieredCarbonPlatform(), FrequencyPolicy.DEFAULT);

        RerunReport report = assertDoesNotThrow(
                () -> RerunDiffExecutor.execute(run, study.resolve("out")),
                "run-time law rejection must be mapped into the verdict contract, not escape"
                        + " as an uncaught usage-class exception (no report written)");

        assertEquals(RerunVerdict.RECONSTRUCTION_REJECTED, report.getVerdict(), report.toJson());
        assertEquals(4, report.getExitCode(), "contract exit code for a code-level rebuild/run refusal");
        // 两份报告必须落盘（失败路径不产 rerun/ 三件套，但报告照写）。
        Path json = study.resolve("out/rerun-report.json");
        Path markdown = study.resolve("out/rerun-report.md");
        assertTrue(Files.exists(json), "rerun-report.json must exist even on the rejection path");
        assertTrue(Files.exists(markdown), "rerun-report.md must exist even on the rejection path");
        assertFalse(Files.exists(study.resolve("out/rerun")), "failed reruns must not fabricate a rerun bundle");
        String reason = report.toJson();
        assertTrue(
                reason.contains("carbon") || reason.contains("碳") || reason.contains("BAT-C1"),
                "the refusal reason must cite the actual law that rejected the rebuilt platform: " + reason);
        assertTrue(
                reason.contains("模拟执行阶段") || reason.contains("simulation") || reason.contains("execut"),
                "the refusal must note the execution phase (contract wording): " + reason);
    }

    /**
     * 文档化的"记录红差异"形态（§2.4）：无碳 tiered 证据复跑 → 重建出 v1 平台 →
     * 复跑侧无 tierMips/频档表 → differ 对证据面逐叶子大红（DIVERGED，exit 1）——
     * 不是静默通过（档表非白名单）、也不是崩溃。档速取 VM 基速，时钟本体重放全等，
     * 分歧集恰为频档证据面（/platform/…/frequencyTiers、jobs[].tierMips、/energyMetrics）。
     */
    @Test
    void tieredWithoutCarbonEvidenceRerunDivergesLoudlyOnTheTierEvidenceFace() throws Exception {
        Path study = directory.resolve("tiered");
        Path run = runAndWriteEvidence(study, "runs/tiered", tieredPlatform(), FrequencyPolicy.DEFAULT);

        RerunReport report = RerunDiffExecutor.execute(run, study.resolve("out"));
        assertEquals(RerunVerdict.DIVERGED, report.getVerdict(), report.toJson());
        assertEquals(1, report.getExitCode(), "DIVERGED exit code");
        List<RerunReport.DivergenceEntry> core = report.getCoreDivergences();
        assertFalse(core.isEmpty(), "the tier evidence face must produce core divergences");
        assertTrue(
                core.stream().anyMatch(d -> d.getPointer().contains("frequencyTiers")),
                "platform tier table leaf must diverge: " + pointers(core));
        assertTrue(
                core.stream().anyMatch(d -> d.getPointer().contains("tierMips")),
                "per-job tier attribution leaf must diverge: " + pointers(core));
        assertTrue(
                core.stream().anyMatch(d -> d.getPointer().startsWith("/energyMetrics")),
                "the conserved energy face must diverge (tiered joules vs unpowered rerun): " + pointers(core));
        // 根因可证：复跑侧 manifest（若产出）不含频档痕迹。DIVERGED 路径产出 rerun/ 三件套。
        String rerunManifest =
                new String(Files.readAllBytes(study.resolve("out/rerun/result.manifest.json")), StandardCharsets.UTF_8);
        assertFalse(rerunManifest.contains("frequencyTiers"), "the rebuilt v1 side carries no tier table");
        assertFalse(rerunManifest.contains("tierMips"), "the rebuilt v1 side carries no per-job tier evidence");
    }

    /** v1（无档平面功率）证据：复跑必须持续绿（BAT-E5 同款不变量在本轮的回归位）。 */
    @Test
    void v1FlatPowerEvidenceStillRerunsIdentically() throws Exception {
        Path study = directory.resolve("v1");
        Path run = runAndWriteEvidence(study, "runs/v1", plainPoweredPlatform(), null);

        RerunReport report = RerunDiffExecutor.execute(run, study.resolve("out"));
        assertEquals(RerunVerdict.IDENTICAL_CORE, report.getVerdict(), report.toJson());
        assertEquals(0, report.getExitCode(), "IDENTICAL_CORE exit code");
    }

    /**
     * 缺陷 #2 的红→红→绿锚：pre-D4 形状的 v1 证据（配置节无 frequencyPolicy 键——
     * power-v1/carbon-v1/standard-v1 全部冻结 bundle 的实际形状）必须仍然
     * IDENTICAL_CORE。值语义等价（缺键 == tierless 平台上唯一合法的 DEFAULT），
     * 分歧只能是发射面的假信号。
     */
    @Test
    void preD4ShapedV1EvidenceRerunsIdentically() throws Exception {
        Path study = directory.resolve("legacy");
        Path run = runAndWriteEvidence(study, "runs/legacy", plainPoweredPlatform(), null);
        stripFrequencyPolicyKey(run);

        RerunReport report = RerunDiffExecutor.execute(run, study.resolve("out"));
        assertEquals(
                RerunVerdict.IDENTICAL_CORE,
                report.getVerdict(),
                "legacy pre-D4 v1 evidence must rerun green; any core divergence here is a fabrication"
                        + " of the D4 unconditional frequencyPolicy emission: " + report.toJson());
        assertEquals(0, report.getExitCode());
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    /** 把 run-manifest 的配置节 frequencyPolicy 成员抹去：pre-D4 冻结 bundle 的形状。 */
    private static void stripFrequencyPolicyKey(Path runDirectory) throws Exception {
        Path manifest = runDirectory.resolve("result.manifest.json");
        JsonObject doc = JsonParser.parseString(new String(Files.readAllBytes(manifest), StandardCharsets.UTF_8))
                .getAsJsonObject();
        JsonObject configuration = doc.getAsJsonObject("configuration");
        // BAT-V4.2 修复后：v1 无档运行的 manifest 本就键缺省（pre-D4 冻结形状的逐位
        // 复原）；修复前该键被无条件发射，此处删除之以复现冻结 bundle 的实际形状——
        // 无论当前发射面如何，本测试锚定的都是"无键原始 <em>必须</em> 复跑绿"。
        configuration.remove("frequencyPolicy");
        assertTrue(
                !configuration.has("frequencyPolicy"),
                "the pre-D4 frozen shape (no frequencyPolicy member) must be constructible");
        Files.write(manifest, doc.toString().getBytes(StandardCharsets.UTF_8));
    }

    private static String pointers(List<RerunReport.DivergenceEntry> divergences) {
        List<String> values = new ArrayList<String>();
        for (RerunReport.DivergenceEntry divergence : divergences) {
            values.add(divergence.getPointer());
        }
        return values.toString();
    }

    private Path runAndWriteEvidence(Path study, String relativeRun, PlatformProfile platform, FrequencyPolicy policy)
            throws Exception {
        Path input = study.resolve("inputs/chain.dax");
        Files.createDirectories(input.getParent());
        Files.write(input, chainDax().getBytes(StandardCharsets.UTF_8));
        SimulationConfig.Builder builder = SimulationConfig.builder(input.toString(), 2)
                .planningAlgorithm(PlanningAlgorithm.SHARED_STORAGE_HEFT)
                .schedulingAlgorithm(SchedulingAlgorithm.STATIC)
                .fileSystem(ReplicaCatalog.FileSystem.SHARED)
                .dataMovementModel(DataMovementModel.legacyWorkflowsimV1())
                .randomSeed(20261029L);
        if (policy != null) {
            builder.frequencyPolicy(policy);
        }
        SimulationReport report = new SimulationRunner().run(builder.build(), platform);
        if (!report.getMetrics().isAllLogicalTasksCompletedSuccessfully()) {
            throw new IllegalStateException("fixture workflow incomplete");
        }
        Path run = study.resolve(relativeRun);
        ExperimentArtifactWriter.write(report, run, "result");
        return run;
    }

    private static String chainDax() {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                + "<adag version=\"3.3\" count=\"2\">\n"
                + "  <job id=\"t1\" name=\"a\" runtime=\"10\"/>\n"
                + "  <job id=\"t2\" name=\"b\" runtime=\"30\"/>\n"
                + "  <child ref=\"t2\"><parent ref=\"t1\"/></child>\n"
                + "</adag>\n";
    }

    /** 单档平台：档速 = VM 基速 1000（时钟与 v1 全等），pair (250/400)。 */
    private static PlatformProfile tieredPlatform() {
        return tierPlatform(new double[][] {{1000.0, 250.0, 400.0}}, null);
    }

    /** 三维平台：同表 + 均一碳强度 380（D5 冻结基准的 fleet 面）。 */
    private static PlatformProfile tieredCarbonPlatform() {
        return tierPlatform(new double[][] {{1000.0, 250.0, 400.0}}, Double.valueOf(380.0));
    }

    /** v1 平面功率平台（250/400），与 tiered 平台同一对功率数字。 */
    private static PlatformProfile plainPoweredPlatform() {
        PlatformProfile.Builder builder = PlatformProfile.builder("v4-v1-plain");
        builder.addHost(new PlatformProfile.HostSpec(0, 2, 2000, 2048, 10000L, 1000000L));
        builder.addVm(plainVm(0));
        builder.addHost(new PlatformProfile.HostSpec(1, 2, 2000, 2048, 10000L, 1000000L));
        builder.addVm(plainVm(1));
        return builder.build();
    }

    private static PlatformProfile tierPlatform(double[][] tierRows, Double carbonIntensity) {
        List<PlatformProfile.FrequencyTier> tiers = new ArrayList<PlatformProfile.FrequencyTier>();
        for (double[] row : tierRows) {
            tiers.add(new PlatformProfile.FrequencyTier(row[0], row[1], row[2]));
        }
        PlatformProfile.Builder builder = PlatformProfile.builder("v4-tiered");
        builder.addHost(new PlatformProfile.HostSpec(0, 2, 2000, 2048, 10000L, 1000000L));
        builder.addVm(tieredVm(0, tiers, carbonIntensity));
        builder.addHost(new PlatformProfile.HostSpec(1, 2, 2000, 2048, 10000L, 1000000L));
        builder.addVm(tieredVm(1, tiers, carbonIntensity));
        return builder.build();
    }

    private static PlatformProfile.VmSpec tieredVm(
            int id, List<PlatformProfile.FrequencyTier> tiers, Double carbonIntensity) {
        return new PlatformProfile.VmSpec(
                id,
                1000.0,
                1,
                512,
                1000L,
                10000L,
                "Xen",
                PlatformProfile.CloudletSchedulerMode.SPACE_SHARED,
                null,
                0.0,
                0.0,
                carbonIntensity,
                tiers);
    }

    private static PlatformProfile.VmSpec plainVm(int id) {
        return new PlatformProfile.VmSpec(
                id,
                1000.0,
                1,
                512,
                1000L,
                10000L,
                "Xen",
                PlatformProfile.CloudletSchedulerMode.SPACE_SHARED,
                null,
                250.0,
                400.0,
                null,
                null);
    }
}
