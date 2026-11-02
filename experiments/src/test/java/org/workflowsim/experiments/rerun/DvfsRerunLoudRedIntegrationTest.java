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
 *
 * <p><b>A1（2026-11-02 用户裁决）：分档证据已可重跑。</b>{@code ManifestConfigRebuilder} 现在往返
 * 重建 {@code platform.vms[].frequencyTiers} 与 {@code configuration.frequencyPolicy}，因此
 * "tiered"与"tiered+carbon"两种证据从"运行期拒绝 / 档位证据面大红"变为 <b>IDENTICAL_CORE</b>。
 * 旧契约（重建剥档 → 拒绝或逐叶大红）由本类前两条测试锚定，本轮按新契约改写；"重建失败仍须
 * 大声"的义务没有消失——表非法（非严格降序）时重建器照旧拒收，由第三条测试锚定。</p>
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
     * A1 主锚（2026-11-02）：三维（tiered+carbon）证据复跑必须 IDENTICAL_CORE。
     *
     * <p>修复前：重建器剥离频档 → 重建平台"碳强度 + 无功率"违反 BAT-C1 半声明律 →
     * 运行期拒绝（RECONSTRUCTION_REJECTED）。修复后：频档表与指派策略随 manifest
     * 往返，重建平台与原运行同构，复跑逐叶子一致。</p>
     */
    @Test
    void triDimensionalDvfsEvidenceRerunsIdenticallyOnceTiersRoundTrip() throws Exception {
        Path study = directory.resolve("tri");
        Path run = runAndWriteEvidence(study, "runs/tri", tieredCarbonPlatform(), FrequencyPolicy.DEFAULT);

        RerunReport report = assertDoesNotThrow(
                () -> RerunDiffExecutor.execute(run, study.resolve("out")),
                "the tier rebuild must not turn a legitimate rerun into a usage-class exception");

        assertEquals(
                RerunVerdict.IDENTICAL_CORE,
                report.getVerdict(),
                "a tiered+carbon bundle must rerun green once the tier table round-trips: " + report.toJson());
        assertEquals(0, report.getExitCode(), "IDENTICAL_CORE exit code");
        assertTrue(
                report.getCoreDivergences().isEmpty(),
                "no core divergence may survive: " + pointers(report.getCoreDivergences()));
    }

    /**
     * A1 第二锚：无碳 tiered 证据复跑同样 IDENTICAL_CORE，且复跑侧必须<b>带上</b>
     * 频档表与逐 Job 档身份——重建不是"降级成 v1 后恰好对上"，而是真的重建了档。
     */
    @Test
    void tieredEvidenceRerunsIdenticallyAndTheRebuiltSideCarriesTheTierTable() throws Exception {
        Path study = directory.resolve("tiered");
        Path run = runAndWriteEvidence(study, "runs/tiered", tieredPlatform(), FrequencyPolicy.MIN_POWER);

        RerunReport report = RerunDiffExecutor.execute(run, study.resolve("out"));
        assertEquals(RerunVerdict.IDENTICAL_CORE, report.getVerdict(), report.toJson());
        assertEquals(0, report.getExitCode(), "IDENTICAL_CORE exit code");

        String rerunManifest =
                new String(Files.readAllBytes(study.resolve("out/rerun/result.manifest.json")), StandardCharsets.UTF_8);
        assertTrue(rerunManifest.contains("frequencyTiers"), "the rebuilt side must carry the tier table");
        assertTrue(rerunManifest.contains("tierMips"), "the rebuilt side must carry per-job tier evidence");
        assertTrue(rerunManifest.contains("MIN_POWER"), "the rebuilt side must carry the frequency policy");
    }

    /**
     * 大声拒收的义务没有消失：manifest 里的档表非法（非严格降序）时，重建器照旧
     * 拒收并映射 RECONSTRUCTION_REJECTED（报告落盘，exit 4）。
     */
    @Test
    void malformedTierTableInTheRecordedManifestIsStillRejectedLoudly() throws Exception {
        Path study = directory.resolve("malformed");
        Path run = runAndWriteEvidence(study, "runs/malformed", twoTierPlatform(), FrequencyPolicy.DEFAULT);
        scrambleTierOrder(run);

        RerunReport report = assertDoesNotThrow(() -> RerunDiffExecutor.execute(run, study.resolve("out")));
        assertEquals(RerunVerdict.RECONSTRUCTION_REJECTED, report.getVerdict(), report.toJson());
        assertEquals(4, report.getExitCode(), "contract exit code for a rebuild refusal");
        assertTrue(Files.exists(study.resolve("out/rerun-report.json")), "the refusal report must be written");
        assertFalse(Files.exists(study.resolve("out/rerun")), "a refused rerun must not fabricate a bundle");
        String reason = report.toJson();
        assertTrue(
                reason.contains("frequency") || reason.contains("mips") || reason.contains("descending"),
                "the refusal must cite the tier table as the cause: " + reason);
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
    /**
     * 对抗面（A1 轮）：重建器只做忠实翻译，任何"记录本身自相矛盾"的形态都必须
     * 拒收为 RECONSTRUCTION_REJECTED，而不是挑一半照跑。四条形态各一个锚点：
     * 表与平铺功率并存（单一真源）/ 档缺 mips（物理量缺失）/ 未知策略枚举 /
     * 策略非 DEFAULT 但记录里没有档（运行期应在树 -> 策略静默失效）。
     */
    @Test
    void manifestDeclaringBothTierTableAndFlatPowerIsRejected() throws Exception {
        assertRejectedAfter(run -> addFlatPowerBesidesTiers(run), "one source of truth", "BOTH frequencyTiers");
    }

    @Test
    void tierEntryWithoutMipsIsRejected() throws Exception {
        assertRejectedAfter(run -> dropFirstTierMips(run), "mips", "missing");
    }

    @Test
    void nonStringFrequencyPolicyIsAReportedRejectionNotAUsageExit() throws Exception {
        assertRejectedAfter(run -> setRawFrequencyPolicy(run, 42), "frequencyPolicy must be a JSON string");
    }

    @Test
    void unknownFrequencyPolicyNameIsRejected() throws Exception {
        assertRejectedAfter(run -> setFrequencyPolicy(run, "TURBO_MODE"), "frequencyPolicy", "TURBO_MODE");
    }

    @Test
    void nonDefaultPolicyWithoutAnyTierTableIsRejected() throws Exception {
        assertRejectedAfter(
                run -> {
                    stripTierTable(run);
                    setFrequencyPolicy(run, "MIN_POWER");
                },
                "MIN_POWER",
                "requires at least one VM");
    }

    private interface Tamper {
        void apply(Path runDirectory) throws Exception;
    }

    private void assertRejectedAfter(Tamper tamper, String... expectedFragments) throws Exception {
        Path study = directory.resolve("tamper-" + System.nanoTime());
        Path run = runAndWriteEvidence(study, "runs/tamper", twoTierPlatform(), FrequencyPolicy.MIN_POWER);
        tamper.apply(run);

        RerunReport report = assertDoesNotThrow(() -> RerunDiffExecutor.execute(run, study.resolve("out")));
        assertEquals(RerunVerdict.RECONSTRUCTION_REJECTED, report.getVerdict(), report.toJson());
        assertEquals(4, report.getExitCode(), "rebuild refusals keep exit 4");
        String reason = report.toJson();
        for (String fragment : expectedFragments) {
            assertTrue(reason.contains(fragment), "the refusal must cite '" + fragment + "': " + reason);
        }
    }

    private static JsonObject readManifest(Path runDirectory) throws Exception {
        Path manifest = runDirectory.resolve("result.manifest.json");
        return JsonParser.parseString(new String(Files.readAllBytes(manifest), StandardCharsets.UTF_8))
                .getAsJsonObject();
    }

    private static void writeManifest(Path runDirectory, JsonObject doc) throws Exception {
        Files.write(runDirectory.resolve("result.manifest.json"), doc.toString().getBytes(StandardCharsets.UTF_8));
    }

    private static JsonObject firstVm(JsonObject doc) {
        return doc.getAsJsonObject("platform").getAsJsonArray("vms").get(0).getAsJsonObject();
    }

    /** 档表 + 平铺功率并存：BAT-D1 单一真源律必须拒收。 */
    private static void addFlatPowerBesidesTiers(Path runDirectory) throws Exception {
        JsonObject doc = readManifest(runDirectory);
        JsonObject vm = firstVm(doc);
        vm.addProperty("idlePowerWatts", 250.0);
        vm.addProperty("maxPowerWatts", 400.0);
        writeManifest(runDirectory, doc);
    }

    /** 档缺 mips：物理量缺失，重建器必须显式失败而不是补默认。 */
    private static void dropFirstTierMips(Path runDirectory) throws Exception {
        JsonObject doc = readManifest(runDirectory);
        com.google.gson.JsonArray tiers = firstVm(doc).getAsJsonArray("frequencyTiers");
        tiers.get(0).getAsJsonObject().remove("mips");
        writeManifest(runDirectory, doc);
    }

    /** 把策略字段写成非字符串（JSON 数字）：类型错误仍须走 verdict 契约。 */
    private static void setRawFrequencyPolicy(Path runDirectory, int value) throws Exception {
        JsonObject doc = readManifest(runDirectory);
        doc.getAsJsonObject("configuration").addProperty("frequencyPolicy", value);
        writeManifest(runDirectory, doc);
    }

    /** 未知策略枚举：不降级为 DEFAULT。 */
    private static void setFrequencyPolicy(Path runDirectory, String policy) throws Exception {
        JsonObject doc = readManifest(runDirectory);
        doc.getAsJsonObject("configuration").addProperty("frequencyPolicy", policy);
        writeManifest(runDirectory, doc);
    }

    /** 抹掉档表，保留（或注入）非 DEFAULT 策略。 */
    private static void stripTierTable(Path runDirectory) throws Exception {
        JsonObject doc = readManifest(runDirectory);
        for (com.google.gson.JsonElement element :
                doc.getAsJsonObject("platform").getAsJsonArray("vms")) {
            element.getAsJsonObject().remove("frequencyTiers");
        }
        writeManifest(runDirectory, doc);
    }

    /** 把记录在 manifest 里的档表顺序颠倒：mips 变为严格升序 = 重建器必须拒收的非法表。 */
    private static void scrambleTierOrder(Path runDirectory) throws Exception {
        Path manifest = runDirectory.resolve("result.manifest.json");
        JsonObject doc = JsonParser.parseString(new String(Files.readAllBytes(manifest), StandardCharsets.UTF_8))
                .getAsJsonObject();
        JsonObject vm =
                doc.getAsJsonObject("platform").getAsJsonArray("vms").get(0).getAsJsonObject();
        com.google.gson.JsonArray tiers = vm.getAsJsonArray("frequencyTiers");
        com.google.gson.JsonArray reversed = new com.google.gson.JsonArray();
        for (int i = tiers.size() - 1; i >= 0; i--) {
            reversed.add(tiers.get(i));
        }
        vm.add("frequencyTiers", reversed);
        Files.write(manifest, doc.toString().getBytes(StandardCharsets.UTF_8));
    }

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

    /** 双档平台：仅用于"非法档表"对照测试（单档表无法构造顺序非法）。 */
    private static PlatformProfile twoTierPlatform() {
        return tierPlatform(new double[][] {{1000.0, 250.0, 400.0}, {500.0, 100.0, 118.75}}, null);
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
