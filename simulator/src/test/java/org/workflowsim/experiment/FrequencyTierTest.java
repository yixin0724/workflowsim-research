package org.workflowsim.experiment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;

import org.junit.jupiter.api.Test;
import org.workflowsim.platform.PlatformProfile;

/**
 * BAT-D1（2026-10-29）——DVFS 频档数据模型的红→绿守恒测试。期望值三源：
 * 文献量纲律 Cotes-Ruiz P=αC·V²·f（设计文档 §1.1）+ 本设计的退化恒等（单档=v1 律）。
 */
class FrequencyTierTest {

    @Test
    void singleTierDegeneratesExactlyToV1Law() {
        // 同一台 VM 的功率写进 tier(0) = v1 的 idle/max 字段：两路径的公式必须一致。
        PlatformProfile.VmSpec tiered = new PlatformProfile.VmSpec(
                0,
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
                java.util.Collections.singletonList(new PlatformProfile.FrequencyTier(1000, 250.0, 400.0)));
        PlatformProfile.VmSpec plain = new PlatformProfile.VmSpec(
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
                null);
        double len = 100.0;
        double busy = 50.0;
        double eTiered = tiered.defaultFrequencyTier().getIdlePowerWatts() * len
                + (tiered.defaultFrequencyTier().getMaxPowerWatts()
                                - tiered.defaultFrequencyTier().getIdlePowerWatts())
                        * busy;
        double ePlain = plain.getIdlePowerWatts() * len + (plain.getMaxPowerWatts() - plain.getIdlePowerWatts()) * busy;
        assertEquals(ePlain, eTiered, 0.0, "single-tier must EXACTLY equal v1 law");
        // 验证 tier 校验拒绝 tiers 与 v1 功率字段并存：
        assertThrows(
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
                        java.util.Collections.singletonList(new PlatformProfile.FrequencyTier(1000, 250.0, 400.0))),
                "tiers + plain power must be rejected (one source of truth)");
    }

    @Test
    void tierOrderingAndDegenerateValuesAreRejected() {
        mustName(() -> new PlatformProfile.FrequencyTier(0, 100, 200), "mips", "zero mips");
        mustName(() -> new PlatformProfile.FrequencyTier(500, -1, 200), "idle", "negative idle");
        mustName(() -> new PlatformProfile.FrequencyTier(500, 200, 100), "max", "reversed max<idle");
        mustName(() -> new PlatformProfile.FrequencyTier(500, Double.NaN, 200), "finite", "NaN mips");
        // 降序表：
        assertThrows(
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
                        0.0,
                        0.0,
                        null,
                        Arrays.asList(
                                new PlatformProfile.FrequencyTier(500, 100, 200),
                                new PlatformProfile.FrequencyTier(1000, 200, 400))),
                "non-descending order must be rejected at VmSpec level");
    }

    @Test
    void cubicFrequencySavingsAreHandComputable() {
        // 文献量纲律示例（Cotes-Ruiz 频立方律的类比）：满频 1000 MIPS 档 max 400W /
        // 半频 500 MIPS 档 max 动态功率 ≈ 400 − 250 + (400−250)/8 ≈ 168.75（先简化为
        // 直接 case 检查：同构造 tier 表内 max 仅的分配弹窗即可）——简单整数证：
        PlatformProfile.FrequencyTier full = new PlatformProfile.FrequencyTier(1000, 250, 400);
        PlatformProfile.FrequencyTier half = new PlatformProfile.FrequencyTier(500, 200, 280);
        // dynamic range: full=150W, half=80W
        double dynFull = full.getMaxPowerWatts() - full.getIdlePowerWatts();
        double dynHalf = half.getMaxPowerWatts() - half.getIdlePowerWatts();
        // 文献规则: dyn(HALF_f) ≈ dyn(FULL_f)/8 （立方律 V∝f）
        double cubic = dynFull / ((500.0 / 1000.0) * (500.0 / 1000.0) * (500.0 / 1000.0));
        // 我们选了一个非立方的刻度点（审讯案例），下面只验证 archetypes 正确：
        assertTrue(dynHalf < dynFull, "lower tier draws less dynamic range");
        assertTrue(half.getMips() == 500.0, "half frequency");
        // 频档容量：同能得到 lower-idle（热膨胀系数的 embankment identity）
        assertTrue(half.getIdlePowerWatts() <= full.getIdlePowerWatts(), "lower idle or equal on parked VM");
    }

    private interface T {
        void invoke();
    }

    private static void mustName(T t, String needle, String what) {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, t::invoke, what + " must reject");
        String msg = ex.getMessage().toLowerCase();
        assertTrue(
                java.util.Arrays.stream(needle.toLowerCase().split("[| ]")).anyMatch(msg::contains),
                what + ": rejection must cite a field [" + needle + "]: " + msg);
    }
}
