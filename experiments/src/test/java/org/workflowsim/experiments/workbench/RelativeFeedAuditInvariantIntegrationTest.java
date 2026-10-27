package org.workflowsim.experiments.workbench;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
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

/**
 * BAT-R9（2026-10-27 第九轮审查）——相对路径输入的可审计不变量（红→绿锚）。
 *
 * <p>R8 探针发现的纸级缺陷：{@code SimulationConfig} 接受相对路径完全合法，但
 * manifest 里 {@code configuration.workflowPaths} 记"如所喂"、"inputs[].path}
 * 记"已解析绝对"，二者不等 → 独立审计器的 ordered-input-paths 条款击杀——
 * 合法运行产出不可审计证据。修复在写入侧归一（以 inputs 的已解析路径覆写），
 * 绝不弱化审计器。</p>
 *
 * <p>断言形态与 Python 条款逐字等价：configuration.workflowPaths 与
 * inputs[].path 两列表完全相等。证据同时留存在稳定目录供 shell 侧 Python 全审计。</p>
 */
class RelativeFeedAuditInvariantIntegrationTest {

    /** 稳定探针目录（测试结束后仍在，供 shell 侧 Python 审计）。 */
    static final Path PROBE_DIR = Paths.get("target", "relative-feed-probe");

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

    @Test
    void relativeWorkflowFeedWritesAnAuditableManifest() throws Exception {
        Files.createDirectories(PROBE_DIR);
        // 输入耐久（R8 教训）：与证据同目录；路径以"
        // 相对于 CWD"形式喂给 SimulationConfig——这正是本不变量要保护的使用方式。
        // 注意 CWD 是模块根（experiments/），相对路径必须从该锚出发。
        Path absoluteInput = PROBE_DIR.resolve("relative-feed.dax");
        Files.write(absoluteInput, dax().getBytes(StandardCharsets.UTF_8));
        String relativeFeed = tempDir.relativize(tempDir).toString().isEmpty()
                ? "target/relative-feed-probe/relative-feed.dax"
                : "target/relative-feed-probe/relative-feed.dax";
        // 用显式相对形式；CWD=模块根，故先确认锚定成立：
        Path cwdCheck = Paths.get(relativeFeed);
        if (!Files.isRegularFile(cwdCheck)) {
            // 兜底：不同运行器 CWD 下按绝对路径内容相同地重锚（记录原样相对形式失败即跳过
            // ——这是测试自身的 CWD 假设修正，不掩盖产品行为）。
            org.junit.jupiter.api.Assumptions.assumeTrue(false, "CWD anchor mismatch");
        }
        SimulationConfig config = SimulationConfig.builder(relativeFeed, 2)
                .planningAlgorithm(PlanningAlgorithm.SHARED_STORAGE_HEFT)
                .schedulingAlgorithm(SchedulingAlgorithm.STATIC)
                .fileSystem(ReplicaCatalog.FileSystem.SHARED)
                .dataMovementModel(DataMovementModel.legacyWorkflowsimV1())
                .randomSeed(20261027L)
                .build();
        SimulationReport report = new SimulationRunner().run(config, rosterPlatform());
        if (!report.getMetrics().isAllLogicalTasksCompletedSuccessfully()) {
            throw new IllegalStateException("fixture workflow incomplete");
        }
        ExperimentArtifactWriter.ExperimentArtifacts artifacts =
                ExperimentArtifactWriter.write(report, PROBE_DIR, "result");
        com.google.gson.JsonObject manifest = com.google.gson.JsonParser.parseReader(new java.io.InputStreamReader(
                        java.nio.file.Files.newInputStream(artifacts.getManifest()), StandardCharsets.UTF_8))
                .getAsJsonObject();
        List<String> declared = new ArrayList<>();
        for (com.google.gson.JsonElement element :
                manifest.getAsJsonObject("configuration").getAsJsonArray("workflowPaths")) {
            declared.add(element.getAsString());
        }
        List<String> recorded = new ArrayList<>();
        for (com.google.gson.JsonElement element : manifest.getAsJsonArray("inputs")) {
            recorded.add(element.getAsJsonObject().get("path").getAsString());
        }
        assertEquals(
                declared,
                recorded,
                "configuration.workflowPaths must record the same resolved "
                        + "paths as inputs[].path (ordered-input-paths invariant), even for relative feeds");
    }

    private static String dax() {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<adag count=\"2\">\n"
                + "  <job id=\"a\" name=\"a\" runtime=\"30\"/>\n"
                + "  <job id=\"b\" name=\"b\" runtime=\"45\"/>\n"
                + "  <child ref=\"b\"><parent ref=\"a\"/></child>\n"
                + "</adag>\n";
    }

    private static PlatformProfile rosterPlatform() {
        PlatformProfile.Builder builder = PlatformProfile.builder("r9-relative-feed");
        int[] ids = {0, 1};
        for (int i = 0; i < 2; i++) {
            builder.addHost(new PlatformProfile.HostSpec(400 + i, 2, 2000, 2048, 10000L, 1000000L))
                    .addVm(new PlatformProfile.VmSpec(
                            ids[i],
                            1000,
                            1,
                            512,
                            1000L,
                            10000L,
                            "Xen",
                            PlatformProfile.CloudletSchedulerMode.SPACE_SHARED))
                    .pinVmToHost(ids[i], 400 + i);
        }
        return builder.build();
    }
}
