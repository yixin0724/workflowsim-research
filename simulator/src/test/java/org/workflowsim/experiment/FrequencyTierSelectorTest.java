package org.workflowsim.experiment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

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
import org.workflowsim.experiment.registry.AlgorithmRegistry;
import org.workflowsim.experiment.registry.SelectorNullTierDvfsTestSchedulerProvider;
import org.workflowsim.experiment.registry.SelectorTierDvfsTestSchedulerProvider;
import org.workflowsim.experiment.registry.SelectorUndeclaredTierDvfsTestSchedulerProvider;
import org.workflowsim.platform.PlatformProfile;
import org.workflowsim.utils.Parameters.ClassType;
import org.workflowsim.utils.Parameters.PlanningAlgorithm;
import org.workflowsim.utils.Parameters.SchedulingAlgorithm;
import org.workflowsim.utils.ReplicaCatalog;
import org.workflowsim.utils.SimulationConfig;

/**
 * BAT-D6（2026-10-29，docs/advanced/DVFS_DIMENSION_DESIGN.md §2.2 注册面）：逐 Job
 * 频档指派第一类调度时刻 API（{@link org.workflowsim.scheduling.FrequencyTierSelector}）
 * 的行为合同——优先级、证据面、fail-fast 语义三件套。
 *
 * <p>期望值来源（AGENTS 三源之"契约文档"）：设计文档 §2.2 声明"注册 DVFS-aware
 * 算法可以通过调度时刻 API 逐 Job 指派频档"——活动策略实例实现 selector 时，逐 Job
 * 档选择权归算法，会话级策略退为无 selector 算法的缺省斜切；指派必须是"候选表内的
 * 声明档"（空档/表外档按证据链断裂显式拒绝，D2 tierByMips 条款的调度侧对应）。</p>
 *
 * <p>测试用三个 test-registry 提供者（SPI 注册于 simulator test classpath）驱动真实
 * 运行：合法面（恒取末档）/ 破坏面一（返回 null）/ 破坏面二（表外档 mips=1500）。</p>
 */
class FrequencyTierSelectorTest {

    @TempDir
    Path tempDir;

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

    /** 1. 优先级：selector（算法侧逐 Job 决策）压过会话级 DEFAULT 策略。 */
    @Test
    void selectorPrecedenceOverSessionDefaultPolicy() throws Exception {
        SimulationReport report = run(null, () -> registeredConfig(SelectorTierDvfsTestSchedulerProvider.LABEL));

        assertTrue(report.getMetrics().isWorkflowCompletedSuccessfully(), "selector run must complete");
        List<Double> computeTiers = new ArrayList<Double>();
        for (SimulationReport.JobOutcome job : report.getJobs()) {
            if (job.getClassType() == ClassType.COMPUTE.value) {
                assertNotNull(job.getTierMips(), "every compute job gets a tier at admission (BAT-D6)");
                computeTiers.add(job.getTierMips());
            }
        }
        assertTrue(computeTiers.size() >= 2, "bag fixture must carry compute jobs");
        for (Double tier : computeTiers) {
            assertEquals(
                    500.0, tier.doubleValue(), 0.0, "selector picks the LAST tier, not the session DEFAULT (first)");
        }
        // 台账面：fleet per-tier ledger 的最低档键即 selector 指派的末档。
        assertEquals(
                Double.valueOf(500.0),
                Double.valueOf(java.util.Collections.min(
                        report.getMetrics().getFleetPerTierBusySeconds().keySet())),
                "the fleet per-tier ledger must reflect the selector's tier (500)");
        // 对照臂：内置 FCFS + 同一 3 档平台 + 会话 DEFAULT → 首档 2000（无 selector 的缺省面）。
        SimulationReport builtin = run(null, () -> builtInConfig(SchedulingAlgorithm.FCFS));
        for (SimulationReport.JobOutcome job : builtin.getJobs()) {
            if (job.getClassType() == ClassType.COMPUTE.value) {
                assertEquals(
                        2000.0,
                        job.getTierMips().doubleValue(),
                        0.0,
                        "the non-selector arm stays on the session DEFAULT (first tier)");
            }
        }
    }

    /** 2. 破坏面一：selector 返回 null → 显式失败，引用"派发时刻恰好一档"条款。 */
    @Test
    void nullSelectorOutputFailsFastCitingAdmissionClause() throws Exception {
        expectRunFailureContaining(SelectorNullTierDvfsTestSchedulerProvider.LABEL, "returned no tier for Job");
    }

    /** 3. 破坏面二：表外档（mips 1500 ∉ {2000,1000,500}）→ 显式失败，引用证据链条款。 */
    @Test
    void undeclaredTierSelectionFailsFastCitingEvidenceChainClause() throws Exception {
        expectRunFailureContaining(
                SelectorUndeclaredTierDvfsTestSchedulerProvider.LABEL, "matches no declared frequency tier");
    }

    // ------------------------------------------------------------------
    // fixture 与运行装配
    // ------------------------------------------------------------------

    private interface ConfigSupplier {
        SimulationConfig config() throws Exception;
    }

    private SimulationReport run(PlatformProfile platform, ConfigSupplier supplier) throws Exception {
        return new SimulationRunner().run(supplier.config(), platform != null ? platform : threeTierPlatform());
    }

    /**
     * 失败必须显式且可归因：注册破坏面提供者的真实运行必须抛出，且异常链（含 CloudSim
     * 事件循环可能的包装）中能找到 BAT-D6 子句——不静默继续、不产出伪档证据。
     */
    private void expectRunFailureContaining(String label, String expectedClause) throws Exception {
        try {
            new SimulationRunner().run(registeredConfig(label), threeTierPlatform());
            fail("Provider " + label + " must fail fail-fast (BAT-D6), but the run completed");
        } catch (Throwable failure) {
            assertCauseChainContains(failure, expectedClause);
        }
    }

    /** 递归异常链匹配。 */
    private static void assertCauseChainContains(Throwable failure, String clause) {
        Throwable current = failure;
        int depth = 0;
        while (current != null && depth < 12) {
            if (current.getMessage() != null && current.getMessage().contains(clause)) {
                return;
            }
            current = current.getCause();
            depth++;
        }
        throw new AssertionError(
                "Expected an exception chain containing \"" + clause + "\" (BAT-D6), got: " + failure, failure);
    }

    private SimulationConfig registeredConfig(String label) throws Exception {
        return SimulationConfig.builder(bagDax().toString(), 2)
                .planningAlgorithm(PlanningAlgorithm.INVALID)
                .schedulingAlgorithm(SchedulingAlgorithm.INVALID)
                .fileSystem(ReplicaCatalog.FileSystem.LOCAL)
                .dataMovementModel(DataMovementModel.legacyWorkflowsimV1())
                .randomSeed(20261029L)
                .frequencyPolicy(SimulationConfig.FrequencyPolicy.DEFAULT)
                .registeredSchedulingAlgorithm(AlgorithmRegistry.find(label))
                .build();
    }

    private SimulationConfig builtInConfig(SchedulingAlgorithm scheduling) throws Exception {
        return SimulationConfig.builder(bagDax().toString(), 2)
                .planningAlgorithm(PlanningAlgorithm.INVALID)
                .schedulingAlgorithm(scheduling)
                .fileSystem(ReplicaCatalog.FileSystem.LOCAL)
                .dataMovementModel(DataMovementModel.legacyWorkflowsimV1())
                .randomSeed(20261029L)
                .build();
    }

    /** 无依赖 bag（40/30/95/35 秒——E5 对照位）：4 个计算 Job 同时就绪。 */
    private Path bagDax() throws Exception {
        StringBuilder dax = new StringBuilder();
        dax.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<adag count=\"4\">\n");
        int[] runs = {40, 30, 95, 35};
        for (int i = 1; i <= runs.length; i++) {
            dax.append("  <job id=\"t")
                    .append(i)
                    .append("\" name=\"b")
                    .append(i)
                    .append("\" runtime=\"")
                    .append(runs[i - 1])
                    .append("\"/>\n");
        }
        dax.append("</adag>\n");
        Path path = tempDir.resolve("selector-bag.dax");
        Files.write(path, dax.toString().getBytes(StandardCharsets.UTF_8));
        return path;
    }

    /** 2-VM/2-Host 平台，每 VM 基频 1000、降序 3 档（2000/1000/500——与 D2 测试同表）。 */
    private static PlatformProfile threeTierPlatform() {
        List<PlatformProfile.FrequencyTier> tiers = new ArrayList<PlatformProfile.FrequencyTier>();
        tiers.add(new PlatformProfile.FrequencyTier(2000.0, 250.0, 400.0));
        tiers.add(new PlatformProfile.FrequencyTier(1000.0, 220.0, 280.0));
        tiers.add(new PlatformProfile.FrequencyTier(500.0, 210.0, 260.0));
        PlatformProfile.Builder builder = PlatformProfile.builder("dvfs-selector-test");
        builder.addHost(new PlatformProfile.HostSpec(0, 2, 2000.0, 2048, 10_000L, 1_000_000L));
        builder.addVm(tieredVm(0, tiers));
        builder.addHost(new PlatformProfile.HostSpec(1, 2, 2000.0, 2048, 10_000L, 1_000_000L));
        builder.addVm(tieredVm(1, tiers));
        return builder.build();
    }

    private static PlatformProfile.VmSpec tieredVm(int id, List<PlatformProfile.FrequencyTier> tiers) {
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
}
