package org.workflowsim.experiment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.workflowsim.platform.PlatformProfile;

/**
 * BAT-V1（2026-10-29 测试工程师对抗轮，DVFS 第五轮）：频档入参面的恶意模糊——
 * 期望值三源：D1 数据档契约（mips&gt;0、idle≥0、max≥idle、全有限、表序严格降序）
 * + 单一真源互斥（BAT-D1）+ v1 退化恒等（空/null 表 = 无档 = v1，DVFS_DIMENSION_
 * DESIGN.md §2.4）。quality-of-rejection 检查：拒绝消息必须指名字段（宽匹配可区分
 * "静默吞掉"），不是只看异常类——鏡像碳维度 T 系（CarbonInputFuzzTest）的红→绿纪律。
 *
 * <p>诚实披露（本攻击类的实测结论，非实现抄录）：
 * <b>单元素档表与 null/空 Java 表是合法面</b>——设计把"单档"锚定为 v1 线性律的
 * 精确退化（§2.4"单档链表（或空）在语义上退化为 v1 的线性律"），"声明边界"的
 * declared-but-empty 拒绝发生在<b>受声明语义约束的两处入口</b>：workbench JSON
 * （空数组）与证据 manifest（Python 审计器 BAT-D3 条款）；Java 进程内空表 =
 * v1 退化模式是设计行为（WorkbenchDvfsFuzzTest/BAT-V1 同轮锁 JSON 面）。</p>
 */
class FrequencyTierInputFuzzTest {

    // ------------------------------------------------------------------
    // 数值域：NaN/±Inf 逐字段 × 三字段独立注入（现有 FrequencyTierTest 只锚了
    // mips=0/负 idle/max&lt;idle/NaN-mips 四点，本类补齐全部 Inf 组合与其余字段）
    // ------------------------------------------------------------------

    @Test
    void nanAndInfinityOnEveryFieldAreRejectedWithFieldNaming() {
        // mips 字段：NaN / +Inf / -Inf（+Inf 通过 mips>0，必须由 finite 条款拦下）。
        mustCite(() -> new PlatformProfile.FrequencyTier(Double.NaN, 100.0, 200.0), "mips", "NaN mips");
        mustCite(() -> new PlatformProfile.FrequencyTier(Double.POSITIVE_INFINITY, 100.0, 200.0), "mips", "+Inf mips");
        mustCite(() -> new PlatformProfile.FrequencyTier(Double.NEGATIVE_INFINITY, 100.0, 200.0), "mips", "-Inf mips");
        // idle 字段：NaN / +Inf / -Inf（-Inf 由 idle>=0 拦、+Inf 由 finite 拦）。
        mustCite(() -> new PlatformProfile.FrequencyTier(1000.0, Double.NaN, 200.0), "idle", "NaN idle");
        mustCite(() -> new PlatformProfile.FrequencyTier(1000.0, Double.POSITIVE_INFINITY, 200.0), "idle", "+Inf idle");
        mustCite(() -> new PlatformProfile.FrequencyTier(1000.0, Double.NEGATIVE_INFINITY, 200.0), "idle", "-Inf idle");
        // max 字段：NaN / +Inf / -Inf（-Inf 由 max>=idle 拦、+Inf 由 finite 拦）。
        mustCite(() -> new PlatformProfile.FrequencyTier(1000.0, 100.0, Double.NaN), "max", "NaN max");
        mustCite(() -> new PlatformProfile.FrequencyTier(1000.0, 100.0, Double.POSITIVE_INFINITY), "max", "+Inf max");
        mustCite(() -> new PlatformProfile.FrequencyTier(1000.0, 100.0, Double.NEGATIVE_INFINITY), "max", "-Inf max");
        // 有限条款名面：消息必须引用域规则（mips>0, idle>=0, max>=idle, all finite）。
        IllegalArgumentException finite = assertThrows(
                IllegalArgumentException.class,
                () -> new PlatformProfile.FrequencyTier(
                        Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY));
        assertTrue(
                finite.getMessage().contains("finite"),
                "rejection must cite the finite clause: " + finite.getMessage());
    }

    @Test
    void degenerateFiniteValuesAreRejectedWithFieldNaming() {
        // mips=0（零速档：无意义 P-state）与负 mips。
        mustCite(() -> new PlatformProfile.FrequencyTier(0.0, 100.0, 200.0), "mips", "zero mips");
        mustCite(() -> new PlatformProfile.FrequencyTier(-1000.0, 100.0, 200.0), "mips", "negative mips");
        // 负怠速功率。
        mustCite(() -> new PlatformProfile.FrequencyTier(1000.0, -0.5, 200.0), "idle", "negative idle");
        // max<idle 倒置。
        mustCite(() -> new PlatformProfile.FrequencyTier(1000.0, 250.0, 200.0), "max", "reversed max<idle");
        // max==idle（边界合法：零动态余量档）——不得拒（边界语义见 Boundary 类）。
        assertEquals(
                250.0,
                new PlatformProfile.FrequencyTier(1000.0, 250.0, 250.0).getMaxPowerWatts(),
                0.0,
                "max==idle is the degenerate zero-dynamic tier, legal by contract");
    }

    // ------------------------------------------------------------------
    // 表级：恰等 mips 双档（重复档）必须被"严格降序"条款拦下——现有测试只锚了
    // 升序 (500,1000)，"重复"是升序的边界孪生（对相邻比较 >= 而非 >）
    // ------------------------------------------------------------------

    @Test
    void duplicateMipsInATwoTierTableIsRejectedCitingTheOrderingClause() {
        IllegalArgumentException duplicate = assertThrows(
                IllegalArgumentException.class,
                () -> spec(Arrays.asList(
                        new PlatformProfile.FrequencyTier(1000.0, 220.0, 280.0),
                        new PlatformProfile.FrequencyTier(1000.0, 210.0, 240.0))),
                "two tiers with exactly equal mips must be rejected (duplicate tier)");
        assertTrue(
                duplicate.getMessage().contains("descending"),
                "rejection must cite the strict descending clause: " + duplicate.getMessage());
        assertTrue(
                duplicate.getMessage().contains("mips"),
                "rejection must cite the mips field: " + duplicate.getMessage());
        // 三档中部重复（首尾不同、中档与前档等 mips）。

        IllegalArgumentException middle = assertThrows(
                IllegalArgumentException.class,
                () -> spec(Arrays.asList(
                        new PlatformProfile.FrequencyTier(2000.0, 250.0, 400.0),
                        new PlatformProfile.FrequencyTier(1000.0, 220.0, 280.0),
                        new PlatformProfile.FrequencyTier(1000.0, 210.0, 240.0))),
                "middle duplicate must be rejected too");
        assertTrue(middle.getMessage().contains("descending"), "same clause: " + middle.getMessage());
    }

    // ------------------------------------------------------------------
    // 单一真源：档表 + 平面功率并存必须拒绝并同时引用两处字段（D1 条款）
    // ------------------------------------------------------------------

    @Test
    void tiersAndPlainPowerStillMutuallyExclusiveCitingBothFaces() {
        IllegalArgumentException both = assertThrows(
                IllegalArgumentException.class,
                () -> new PlatformProfile.VmSpec(
                        0,
                        1000,
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
                        Collections.singletonList(new PlatformProfile.FrequencyTier(1000.0, 220.0, 280.0))),
                "one source of truth");
        assertTrue(
                both.getMessage().contains("frequencyTiers")
                        && both.getMessage().contains("idlePowerWatts")
                        && both.getMessage().contains("maxPowerWatts"),
                "rejection must cite BOTH power faces: " + both.getMessage());
    }

    // ------------------------------------------------------------------
    // v1 平台在 Java 各构造入参下保持合法（缺省即 v1）：null 表 / 空 List /
    // 13 参与 12/11 参委托——每一台 VM 无档、无频档序列化、能量走 v1 律
    // ------------------------------------------------------------------

    @Test
    void v1TierlessInputsRemainLegalOnEveryConstructorPath() {
        for (PlatformProfile.VmSpec spec : Arrays.asList(
                v1Spec(null),
                v1Spec(new ArrayList<PlatformProfile.FrequencyTier>()),
                // 12 参/11 参委托：缺省 tiers=null → v1
                new PlatformProfile.VmSpec(
                        0,
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
                        null),
                new PlatformProfile.VmSpec(
                        0,
                        1000.0,
                        1,
                        512,
                        1000L,
                        10000L,
                        "Xen",
                        PlatformProfile.CloudletSchedulerMode.SPACE_SHARED,
                        null,
                        250.0,
                        400.0))) {
            assertFalse(spec.hasFrequencyTiers(), "tierless construction must stay v1");
            assertTrue(spec.getFrequencyTiers().isEmpty(), "no tier table");
            assertTrue(spec.hasPowerModel(), "v1 flat power works through the same constructor");
            // v1 面没有默认档：访问器只允许 hasFrequencyTiers() 为真的台。
            // （defaultFrequencyTier() 的契约即"调用方须先验 hasFrequencyTiers"。）
        }
        try {
            v1Spec(null).defaultFrequencyTier();
            org.junit.jupiter.api.Assertions.fail(
                    "tierless VmSpec.defaultFrequencyTier() must fail loudly, not fabricate a tier");
        } catch (IndexOutOfBoundsException | IllegalStateException expected) {
            // 显式失败（无档莫造档）；不吞静默值即契约。
        }
    }

    /** 单元素档表合法 = v1 退化（D1 恒等），且默认档=该唯一档。 */
    @Test
    void singleElementTierListIsTheLegalV1DegenerationNotARejection() {
        PlatformProfile.VmSpec single =
                spec(Collections.singletonList(new PlatformProfile.FrequencyTier(1000.0, 250.0, 400.0)));
        assertTrue(single.hasFrequencyTiers(), "single-tier table IS DVFS mode (evidence face: tierMips recorded)");
        assertEquals(1000.0, single.defaultFrequencyTier().getMips(), 0.0, "the only tier is the default tier");
        assertEquals(0.0, single.getIdlePowerWatts(), 0.0, "flat power fields stay zero (one source of truth)");
    }

    // ---- helpers ----

    /** 档表构造面：平面功率字段恒 0（单一真源）。 */
    private static PlatformProfile.VmSpec spec(List<PlatformProfile.FrequencyTier> tiers) {
        return new PlatformProfile.VmSpec(
                0,
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
                null,
                tiers);
    }

    /** v1 无档面：平面功率 250/400（真功率声明）+ null/空档表。 */
    private static PlatformProfile.VmSpec v1Spec(List<PlatformProfile.FrequencyTier> tiers) {
        if (tiers != null && !tiers.isEmpty()) {
            throw new IllegalArgumentException("v1Spec is the tierless face; use spec() for tier tables");
        }
        return new PlatformProfile.VmSpec(
                0,
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
                tiers);
    }

    private interface Thunk {

        void run();
    }

    private static void mustCite(Thunk thunk, String needle, String what) {
        IllegalArgumentException rejection =
                assertThrows(IllegalArgumentException.class, thunk::run, what + " must be rejected");
        String message = rejection.getMessage().toLowerCase();
        boolean cites =
                java.util.Arrays.stream(needle.toLowerCase().split("[| ]")).anyMatch(message::contains);
        assertTrue(cites, what + " rejection must cite the field [" + needle + "]: " + message);
        // 宽域名面：D1 值消息包含域清单（required: ...），可索引到修复路径。
        assertTrue(
                message.contains("required") || message.contains("descending") || message.contains("source of truth"),
                what + " rejection must state the violated domain rule: " + message);
    }
}
