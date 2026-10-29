package org.workflowsim.experiment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;

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
import org.workflowsim.utils.SimulationConfig.FrequencyPolicy;

/**
 * BAT-V4.2（2026-10-29 测试工程师对抗轮，DVFS 第五轮）：配置节 frequencyPolicy 键的
 * 发射口径红→绿。
 *
 * <p>攻击背景（本轮实证发现）：D4 让 {@code ExperimentManifestWriter} 无条件写
 * {@code configuration.frequencyPolicy}——包括 v1 无档平台的 DEFAULT（惰性）值。于是
 * <b>每一份 D4 前冻结的 v1 证据</b>（power-v1 / carbon-v1 / standard-v1 三套基准、
 * 全部历史 bundle）在 EvidenceCoreDiffer 下复跑时都新增一个核心量分歧
 * {@code /configuration/frequencyPolicy <missing> vs "DEFAULT"} → 整批历史证据
 * 从 IDENTICAL_CORE 退化为 DIVERGED——证据配偶（e-prior evidence）与
 * "v1 manifest 逐位不变"（D3 平台节的同款纪律："key omitted when empty"）之间的契约
 * 被 D4 的一行发射破坏。红锚：对 v1 运行断言序列化面不含 {@code frequencyPolicy}。</p>
 *
 * <p>修复后的发射规则（镜像 tierMips/frequencyTiers/carbonWindows 的
 * "声明→出现、缺省→键缺省"文化）：</p>
 * <ul>
 *   <li>v1 无档平台（策略只能是 DEFAULT——运行器 BAT-D2 门拒绝其他）：键缺省；</li>
 *   <li>非 DEFAULT 策略：必写（任何平台）；</li>
 *   <li>档表声明（tiered 平台）：必写（复跑语义需要，D5 三维基准的 DEFAULT 单元
 *       保留显式标签）。</li>
 * </ul>
 */
class FrequencyTierManifestEmissionTest {

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

    /** 红锚（本轮修复前为红）：v1 无档运行的全序列化面不含 frequencyPolicy 键。 */
    @Test
    void v1TierlessManifestOmitsTheFrequencyPolicyKeyEntirely() throws Exception {
        SimulationReport plain = run(plainPoweredPlatform(), null);
        java.util.Map<String, Object> manifest =
                ExperimentManifestWriter.toManifest(plain, new ArrayList<java.util.Map<String, Object>>(), null);
        assertFalse(manifest.get("configuration").toString().isEmpty(), "wire format parse guard");
        String serialized = new com.google.gson.GsonBuilder()
                .serializeNulls()
                .setPrettyPrinting()
                .create()
                .toJson(manifest);
        assertFalse(
                serialized.contains("frequencyPolicy"),
                "v1 tierless manifests must NOT carry a frequencyPolicy key (absent = DEFAULT = v1 legacy"
                        + " shape; unconditional emission demeans every pre-D4 frozen bundle's rerun"
                        + " into a fake core divergence): " + excerpt(serialized));
    }

    /** tiered 运行必写频率策略（复跑语义的一部分；D5 基准 DEFAULT 单元同款）。 */
    @Test
    void tieredManifestRecordsTheFrequencyPolicy() throws Exception {
        SimulationReport tiered = run(twoTierPlatform(), FrequencyPolicy.DEFAULT);
        java.util.Map<String, Object> manifest =
                ExperimentManifestWriter.toManifest(tiered, new ArrayList<java.util.Map<String, Object>>(), null);
        Object policy = ((java.util.Map<?, ?>) manifest.get("configuration")).get("frequencyPolicy");
        assertEquals("DEFAULT", String.valueOf(policy), "tiered manifests record the policy label explicitly");
        String serialized = new com.google.gson.GsonBuilder()
                .serializeNulls()
                .setPrettyPrinting()
                .create()
                .toJson(manifest);
        assertFalse(serialized.contains("\"tierMips\":null"), "sanity: no fabricated null tier evidence");
    }

    private static String excerpt(String serialized) {
        int at = serialized.indexOf("configuration");
        return at < 0
                ? serialized.substring(0, Math.min(200, serialized.length()))
                : serialized.substring(at, Math.min(at + 400, serialized.length()));
    }

    // ------------------------------------------------------------------
    // fixtures
    // ------------------------------------------------------------------

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

    /** 2 档平台（2000 默认 / 500 末档）。 */
    private static PlatformProfile twoTierPlatform() {
        java.util.List<PlatformProfile.FrequencyTier> tiers = new ArrayList<PlatformProfile.FrequencyTier>();
        tiers.add(new PlatformProfile.FrequencyTier(2000.0, 250.0, 400.0));
        tiers.add(new PlatformProfile.FrequencyTier(500.0, 210.0, 260.0));
        PlatformProfile.Builder builder = PlatformProfile.builder("v4-emit-tier");
        builder.addHost(new PlatformProfile.HostSpec(0, 2, 2000.0, 2048, 10_000L, 1_000_000L));
        builder.addVm(tieredVmSpec(0, tiers));
        builder.addHost(new PlatformProfile.HostSpec(1, 2, 2000.0, 2048, 10_000L, 1_000_000L));
        builder.addVm(tieredVmSpec(1, tiers));
        return builder.build();
    }

    /** v1 平面功率平台（250/400，无档）。 */
    private static PlatformProfile plainPoweredPlatform() {
        PlatformProfile.Builder builder = PlatformProfile.builder("v4-emit-plain");
        builder.addHost(new PlatformProfile.HostSpec(0, 2, 2000.0, 2048, 10_000L, 1_000_000L));
        builder.addVm(new PlatformProfile.VmSpec(
                0,
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
                null));
        builder.addHost(new PlatformProfile.HostSpec(1, 2, 2000.0, 2048, 10_000L, 1_000_000L));
        builder.addVm(new PlatformProfile.VmSpec(
                1,
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
                null));
        return builder.build();
    }

    private static PlatformProfile.VmSpec tieredVmSpec(int id, java.util.List<PlatformProfile.FrequencyTier> tiers) {
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

    private Path chainDax() throws Exception {
        if (workflowDax == null) {
            StringBuilder dax = new StringBuilder();
            dax.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
            dax.append("<adag version=\"3.3\" count=\"2\">\n");
            dax.append("  <job id=\"t1\" name=\"a\" runtime=\"10\"/>\n");
            dax.append("  <job id=\"t2\" name=\"b\" runtime=\"30\"/>\n");
            dax.append("  <child ref=\"t2\"><parent ref=\"t1\"/></child>\n");
            dax.append("</adag>\n");
            workflowDax = Paths.get(tempDir.toFile().getAbsolutePath(), "v4-emit-chain.dax");
            Files.write(workflowDax, dax.toString().getBytes(StandardCharsets.UTF_8));
        }
        return workflowDax;
    }
}
