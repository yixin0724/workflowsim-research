package org.workflowsim.experiments.rerun;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.cloudbus.cloudsim.Log;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.workflowsim.data.DataMovementModel;
import org.workflowsim.experiment.ExperimentArtifactWriter;
import org.workflowsim.experiment.SimulationReport;
import org.workflowsim.experiment.SimulationRunner;
import org.workflowsim.experiment.registry.SchedulingAlgorithmProvider;
import org.workflowsim.platform.PlatformProfile;
import org.workflowsim.utils.Parameters.PlanningAlgorithm;
import org.workflowsim.utils.Parameters.SchedulingAlgorithm;
import org.workflowsim.utils.ReplicaCatalog;
import org.workflowsim.utils.SimulationConfig;

/**
 * BAT-R7 (2026-10-27, 第七轮审查) — 跨缝组合链：注册标签算法 × 带功率平台 × 复跑路径，
 * 三者没有任何一轮审查串联测过（R1-R6 各守自己的缝隙）。
 *
 * <p>关键问题：注册接口的 powerModelRequired 校验、manifest 功率序列化、
 * EvidenceCoreDiffer 的能耗缺位等价、ManifestConfigRebuilder 的功率转发——
 * 缝各自绿不等于链全程绿。本测试用注册的 ENERGY_LPT_FIRST 提供者（宿在
 * experiments 测试域，SPI 可见）跑一份带功率运行，再全链 RerunDiffExecutor 复跑：
 * 判决必须 IDENTICAL_CORE 且复跑侧能耗逐位等于原始。</p>
 */
class RegisteredPoweredRerunIntegrationTest {

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

    @Test
    void registeredPoweredAlgorithmRerunsIdenticallyEndToEnd() throws Exception {
        // 1. 通过注册面（ServiceLoader）找到提供者——不允许直接 new 绕过接口。
        SchedulingAlgorithmProvider provider =
                org.workflowsim.experiment.registry.AlgorithmRegistry.find("ENERGY_LPT_FIRST");
        assertEquals(
                org.workflowsim.experiments.registry.EnergyLptFirstSchedulerProvider.class,
                provider.getClass(),
                "the SPI must surface the E5 provider in this classpath");

        // 2. 原始运行：注册算法 × 带功率平台。
        Path run = directory.resolve("study/runs/original");
        Path input = run.getParent().getParent().resolve("inputs/bag.dax");
        Files.createDirectories(input.getParent());
        Files.write(input, dax().getBytes(StandardCharsets.UTF_8));
        SimulationConfig config = SimulationConfig.builder(input.toString(), 2)
                .planningAlgorithm(PlanningAlgorithm.INVALID)
                .schedulingAlgorithm(SchedulingAlgorithm.INVALID)
                .fileSystem(ReplicaCatalog.FileSystem.LOCAL)
                .dataMovementModel(DataMovementModel.legacyWorkflowsimV1())
                .randomSeed(20261027L)
                .deadline(400) // BAT-R9：死线并入注册链（R8 立目标时被砍掉的一轴）
                .registeredSchedulingAlgorithm(provider)
                .build();
        PlatformProfile platform = poweredPlatform();
        SimulationReport report = new SimulationRunner().run(config, platform);
        if (!report.getMetrics().isAllLogicalTasksCompletedSuccessfully()) {
            throw new IllegalStateException("fixture workflow incomplete");
        }
        ExperimentArtifactWriter.write(report, run, "result");

        // 3. 全链复跑：重建（读注册标签 + 功率）→ 重跑 → 核心量比对。
        Path output = directory.resolve("study/runs/rerun");
        RerunReport result = RerunDiffExecutor.execute(run, output);
        java.nio.file.Files.write(
                directory.resolve("verdict.json"), result.toJson().getBytes(StandardCharsets.UTF_8));
        assertEquals(RerunVerdict.IDENTICAL_CORE, result.getVerdict(), result.toJson());

        // 4. 能耗逐位回放：复跑侧总能量与原始侧完全一致（守恒律由同一引擎保证）。
        com.google.gson.JsonObject original = com.google.gson.JsonParser.parseString(
                        new String(Files.readAllBytes(run.resolve("result.manifest.json")), StandardCharsets.UTF_8))
                .getAsJsonObject();
        com.google.gson.JsonObject repeat = com.google.gson.JsonParser.parseString(new String(
                        Files.readAllBytes(output.resolve("rerun/result.manifest.json")), StandardCharsets.UTF_8))
                .getAsJsonObject();
        assertEquals(
                original.getAsJsonObject("energyMetrics").get("totalEnergyJoules"),
                repeat.getAsJsonObject("energyMetrics").get("totalEnergyJoules"),
                "energy must replay bit-exactly across the registered-algorithm rerun");
        // 注册标签必须原样出现在两份 manifest 里（不是 INVALID 枚举字符串上等价）。
        assertEquals(
                original.getAsJsonObject("configuration").get("registeredSchedulingAlgorithm"),
                repeat.getAsJsonObject("configuration").get("registeredSchedulingAlgorithm"));
        assertEquals(
                true,
                repeat.getAsJsonObject("metrics")
                        .get("deadlineObservationEnabled")
                        .getAsBoolean(),
                "the deadline axis must be observed in metrics alongside the registered label");
        assertEquals(
                original.getAsJsonObject("metrics").get("deadlineMet"),
                repeat.getAsJsonObject("metrics").get("deadlineMet"),
                "deadline verdicts must replay identically");
    }

    private static String dax() {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<adag count=\"3\">\n"
                + "  <job id=\"a\" name=\"a\" runtime=\"40\"/>\n"
                + "  <job id=\"b\" name=\"b\" runtime=\"95\"/>\n"
                + "  <job id=\"c\" name=\"c\" runtime=\"35\"/>\n"
                + "</adag>\n";
    }

    private static PlatformProfile poweredPlatform() {
        PlatformProfile.Builder builder = PlatformProfile.builder("r7-powered");
        int[] ids = {0, 1};
        for (int i = 0; i < 2; i++) {
            builder.addHost(new PlatformProfile.HostSpec(100 + i, 2, 2000, 2048, 10000L, 1000000L))
                    .addVm(new PlatformProfile.VmSpec(
                            ids[i],
                            1000,
                            1,
                            512,
                            1000L,
                            10000L,
                            "Xen",
                            PlatformProfile.CloudletSchedulerMode.SPACE_SHARED,
                            null,
                            250.0,
                            400.0))
                    .pinVmToHost(ids[i], 100 + i);
        }
        return builder.build();
    }
}
