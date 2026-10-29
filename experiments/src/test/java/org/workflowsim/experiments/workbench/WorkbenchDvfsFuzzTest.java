package org.workflowsim.experiments.workbench;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.workflowsim.experiments.workbench.WorkbenchTestSupport.configuration;
import static org.workflowsim.experiments.workbench.WorkbenchTestSupport.json;
import static org.workflowsim.experiments.workbench.WorkbenchTestSupport.workflow;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.cloudbus.cloudsim.Log;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.workflowsim.platform.PlatformProfile;
import org.workflowsim.utils.SimulationConfig;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

/**
 * BAT-V1（2026-10-29 测试工程师对抗轮，DVFS 第五轮）：workbench JSON 频档入参面的
 * 恶意模糊——declared-but-empty、"非数组"塑形、字符串档、未知策略标签
 * （SPEED_RUNNER）、严格 JSON 的 NaN 常量；v1 平台在每一入参下保持合法。
 * quality-of-rejection：消息必须指名字段（镜像 BAT-D4 既有拒绝面的纪律）。
 *
 * <p>诚实披露（本类锁定的实测语义，非抄录）：
 * <ul>
 *   <li><b>空数组（declared-but-empty）→ 拒绝</b>（ BAT-D4："must be a non-empty array"，
 *       给出"删键即 v1"的最短修复路径）；</li>
 *   <li><b>单元素档表 → 合法</b>（v1 退化恒等的 workbench 面；MIN_POWER 可用）；</li>
 *   <li><b>NaN/Inf 家族在 JSON 面死于严格句法层</b>（RFC 8259 非法常量，先于任何
 *       域校验——与 Python 审计器 _constant 条款同语义），不存在"字段级"回执；
 *       域级有限消息（"must be finite"）保留给域层（数值大但有限的域外值仍见域层
 *       消息）。</li>
 * </ul>
 * （频率档数值域正/负/倒置与表序消息面已由 WorkbenchConfigTest.
 * platformFrequencyTiersRoundTripAndValidate 锚定，本类不重复。）</p>
 */
class WorkbenchDvfsFuzzTest {

    @TempDir
    Path directory;

    private boolean loggingWasDisabled;

    @BeforeEach
    void silenceSimulationLogging() {
        loggingWasDisabled = Log.isDisabled();
        Log.disable();
    }

    @AfterEach
    void restoreSimulationLogging() {
        Log.setDisabled(loggingWasDisabled);
    }

    @Test
    void declaredButEmptyTierArrayIsRejectedCitingTheV1EscapeHatch() throws Exception {
        Path workflow = workflow(directory, "flow.dax", false);
        JsonObject empty = configuration(workflow.toString(), 2);
        empty.getAsJsonObject("platform").add("frequencyTiers", new JsonArray());
        IllegalArgumentException rejection = assertThrows(
                IllegalArgumentException.class,
                () -> WorkbenchConfig.read(json(directory.resolve("empty-tiers.json"), empty)));
        assertTrue(
                rejection.getMessage().contains("frequencyTiers")
                        && rejection.getMessage().contains("non-empty"),
                "declared-but-empty must be rejected citing the field and the v1 escape hatch: "
                        + rejection.getMessage());
    }

    @Test
    void nonArrayTiersShapeIsRejectedCitingTheField() throws Exception {
        Path workflow = workflow(directory, "flow.dax", false);
        JsonObject notArray = configuration(workflow.toString(), 2);
        notArray.getAsJsonObject("platform").add("frequencyTiers", new JsonPrimitive("not-an-array"));
        IllegalArgumentException rejection = assertThrows(
                IllegalArgumentException.class,
                () -> WorkbenchConfig.read(json(directory.resolve("string-tiers.json"), notArray)));
        assertTrue(
                rejection.getMessage().contains("frequencyTiers")
                        && rejection.getMessage().contains("array"),
                "string-shaped tiers must be rejected citing the field: " + rejection.getMessage());
    }

    @Test
    void nonObjectTierEntriesAndUnknownTierFieldsAreRejected() throws Exception {
        Path workflow = workflow(directory, "flow.dax", false);
        JsonObject stringEntry = configuration(workflow.toString(), 2);
        JsonArray entries = new JsonArray();
        entries.add(new JsonPrimitive("not-an-object"));
        stringEntry.getAsJsonObject("platform").add("frequencyTiers", entries);
        IllegalArgumentException shape = assertThrows(
                IllegalArgumentException.class,
                () -> WorkbenchConfig.read(json(directory.resolve("entry-shape.json"), stringEntry)));
        assertTrue(
                shape.getMessage().contains("frequencyTiers")
                        && shape.getMessage().contains("objects"),
                "entry-shape rejection must cite the field: " + shape.getMessage());

        JsonObject extraField = configuration(workflow.toString(), 2);
        extraField.getAsJsonObject("platform").add("frequencyTiers", tiers(2000.0, 250.0, 400.0));
        extraField
                .getAsJsonObject("platform")
                .get("frequencyTiers")
                .getAsJsonArray()
                .get(0)
                .getAsJsonObject()
                .addProperty("turboBoost", 1.5);
        IllegalArgumentException unknown = assertThrows(
                IllegalArgumentException.class,
                () -> WorkbenchConfig.read(json(directory.resolve("extra-field.json"), extraField)));
        assertTrue(
                unknown.getMessage().contains("unknown field")
                        && unknown.getMessage().contains("turboBoost"),
                "extra tier field must be rejected citing it (closed schema): " + unknown.getMessage());
    }

    @Test
    void unknownPolicyLabelSPEED_RUNNERIsRejectedEvenOnTierlessPlatforms() throws Exception {
        Path workflow = workflow(directory, "flow.dax", false);
        // 有档 + 未知标签 → 拒，引用 frequencyPolicy 与合法标签集。
        JsonObject tiered = configuration(workflow.toString(), 2);
        tiered.getAsJsonObject("platform").add("frequencyTiers", tiers(2000.0, 250.0, 400.0));
        tiered.getAsJsonObject("platform").addProperty("frequencyPolicy", "SPEED_RUNNER");
        IllegalArgumentException tieredRejection = assertThrows(
                IllegalArgumentException.class,
                () -> WorkbenchConfig.read(json(directory.resolve("unknown-tiered.json"), tiered)));
        assertTrue(
                tieredRejection.getMessage().contains("frequencyPolicy")
                        && tieredRejection.getMessage().contains("SPEED_RUNNER"),
                "unknown policy must cite the field and the given label: " + tieredRejection.getMessage());
        // 无档 + 未知标签 → 同一致拒绝（不静默回落 DEFAULT）：字段级顺序（valueOf 先于
        // 策略×表复合校验）。
        JsonObject tierless = configuration(workflow.toString(), 2);
        tierless.getAsJsonObject("platform").addProperty("frequencyPolicy", "SPEED_RUNNER");
        IllegalArgumentException tierlessRejection = assertThrows(
                IllegalArgumentException.class,
                () -> WorkbenchConfig.read(json(directory.resolve("unknown-tierless.json"), tierless)));
        assertTrue(
                tierlessRejection.getMessage().contains("frequencyPolicy"),
                "unknown label is rejected even before platform-policy composition: " + tierlessRejection.getMessage());
    }

    @Test
    void rawNanTierValueDiesAtTheStrictJsonSyntaxLayer() throws Exception {
        Path workflow = workflow(directory, "flow.dax", false);
        JsonObject nanTier = configuration(workflow.toString(), 2);
        // NaN 不能作为合法 JSON 常量构造（Gson JsonPrimitive 也不接受非有限双精度的
        // JSON 序列化语义）——手写原始文档把非法常量直接喂进严格模式的 reader。
        Path raw = directory.resolve("nan-raw.json");
        String template = json(directory.resolve("nan-template.json"), nanTier).toString();
        String mutated = template.replaceFirst(
                "\"platform\"\\s*:\\s*\\{",
                "\"platform\": {\"frequencyTiers\": [{\"mips\": NaN,\"idlePowerWatts\": 1,\"maxPowerWatts\": 2}],");
        Files.write(raw, mutated.getBytes(StandardCharsets.UTF_8));
        // 严格 reader 把非标常量映射为 IllegalArgumentException（"配置必须是严格 JSON 对象"，
        // BAM-D4/Wso strict face）——句法层击杀发生在任何域校验之前（与 Python 审计器
        // _study_audit._constant 条款同语义），不存在字段级回执，也不得静默回落 DEFAULT。
        IllegalArgumentException syntax = assertThrows(
                IllegalArgumentException.class, () -> WorkbenchConfig.read(raw), "NaN literal must not parse");
        assertTrue(
                syntax.getMessage() != null && !syntax.getMessage().isEmpty(),
                "syntax-layer rejection must carry a diagnostic: " + syntax.getMessage());
        // 大但有限的域外值（负 mips）→ 域层字段级消息（不靠句法层兜底）。
        JsonObject negative = configuration(workflow.toString(), 2);
        negative.getAsJsonObject("platform").add("frequencyTiers", tiers(-5.0, 1.0, 2.0));
        IllegalArgumentException domain = assertThrows(
                IllegalArgumentException.class,
                () -> WorkbenchConfig.read(json(directory.resolve("negative.json"), negative)));
        assertTrue(domain.getMessage().contains("mips"), "domain rejection must cite mips: " + domain.getMessage());
    }

    @Test
    void singleElementTierTableIsLegalV1DegenerationOnTheWorkbenchFace() throws Exception {
        Path workflow = workflow(directory, "flow.dax", false);
        JsonObject single = configuration(workflow.toString(), 2);
        single.getAsJsonObject("platform").add("frequencyTiers", tiers(1000.0, 250.0, 400.0));
        single.getAsJsonObject("platform").addProperty("frequencyPolicy", "MIN_POWER");
        WorkbenchConfig loaded = WorkbenchConfig.read(json(directory.resolve("single-tier.json"), single));
        for (PlatformProfile.VmSpec spec : loaded.getPlatform().getVms()) {
            assertTrue(spec.hasFrequencyTiers(), "single-tier declaration IS DVFS mode");
            assertEquals(1, spec.getFrequencyTiers().size(), "one tier");
            assertEquals(1000.0, spec.defaultFrequencyTier().getMips(), 0.0, "the default tier");
        }
        for (WorkbenchConfig.Candidate candidate : loaded.getCandidates()) {
            assertEquals(
                    SimulationConfig.FrequencyPolicy.MIN_POWER,
                    candidate.config.getFrequencyPolicy(),
                    "MIN_POWER is legal on a single-tier platform (it picks the only tier)");
        }
    }

    @Test
    void v1ConfigurationWithoutAnyTierKeysStaysLegalWithDefaultPolicy() throws Exception {
        Path workflow = workflow(directory, "flow.dax", false);
        WorkbenchConfig loaded =
                WorkbenchConfig.read(json(directory.resolve("v1.json"), configuration(workflow.toString(), 2)));
        for (PlatformProfile.VmSpec spec : loaded.getPlatform().getVms()) {
            assertFalse(spec.hasFrequencyTiers(), "no frequencyTiers key -> v1 single-tier platform");
            assertFalse(spec.hasPowerModel(), "usable unpowered v1 shape (energy face absent)");
        }
        for (WorkbenchConfig.Candidate candidate : loaded.getCandidates()) {
            assertEquals(
                    SimulationConfig.FrequencyPolicy.DEFAULT,
                    candidate.config.getFrequencyPolicy(),
                    "absent policy resolution is DEFAULT (v1)");
        }
    }

    private static JsonArray tiers(double... mipsIdleMax) {
        JsonArray table = new JsonArray();
        for (int i = 0; i < mipsIdleMax.length; i += 3) {
            JsonObject tier = new JsonObject();
            tier.addProperty("mips", mipsIdleMax[i]);
            tier.addProperty("idlePowerWatts", mipsIdleMax[i + 1]);
            tier.addProperty("maxPowerWatts", mipsIdleMax[i + 2]);
            table.add(tier);
        }
        return table;
    }
}
