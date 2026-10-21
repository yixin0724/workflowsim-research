package org.workflowsim.experiment;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.cloudbus.cloudsim.Log;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.workflowsim.platform.PlatformProfile;
import org.workflowsim.platform.PlatformProfiles;
import org.workflowsim.utils.SimulationConfig;

/**
 * Phase 2 元变缺口补齐（审查工程 C08/C168-系列）。
 *
 * <p>既有覆盖（不重复建设）：ConfigurationMetamorphicIntegrationTest 已覆盖 117 格
 * 组合矩阵与"公共数据规模守恒"，CrossConfigurationContaminationIntegrationTest 已覆盖
 * 同 JVM 交替配置无污染。本类补两个缺口关系：</p>
 *
 * <p><b>R1 零到达退化（R5 契约）</b>：显式 {@code workflowArrivalSeconds=[0.0]} 与完全
 * 省略（默认全零）必须产生<b>逐位一致</b>的模拟事件流与任务时间窗——契约原文：
 * "全部 t=0 时引擎行为与历史单时刻提交逐位一致"。</p>
 *
 * <p><b>R2 尺度分离（计算建模不变量）</b>：{@code runtimeScale×2 且全部 VM MIPS×2}
 * 时，任务 MI 随 ×2、MIPS 随 ×2，二者相消——每个任务的<b>计算时长必须精确不变</b>
 * （0 容差）。这锁定"MI 换算线性 + 计算时长只依赖 sec×scale/mips"的建模分离；
 * 根 stage-in Job（110 MI 固定）允许变化，因此只断言任务窗口时长。</p>
 */
class MetamorphicGapIntegrationTest {

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
    void explicitZeroArrivalsAreBitwiseIdenticalToOmittedArrivals() throws Exception {
        Path workflow = chainDax();
        SimulationReport explicit = run(
                workflow,
                SimulationConfig.builder(workflow.toString(), 2)
                        .workflowArrivalSeconds(java.util.Arrays.asList(0.0))
                        .build());
        SimulationReport omitted =
                run(workflow, SimulationConfig.builder(workflow.toString(), 2).build());

        assertEventStreamsIdentical(explicit, omitted);
        assertTaskWindowsIdentical(explicit, omitted);
        assertEquals(
                explicit.getMetrics().getMakespanSeconds(),
                omitted.getMetrics().getMakespanSeconds(),
                0.0,
                "R5 契约：全零到达与省略到达的 makespan 必须逐位一致");
        assertEquals(explicit.getActualVmHostAssignments(), omitted.getActualVmHostAssignments(), "VM 放置必须一致");
    }

    @Test
    void doublingScaleAndMipsKeepsEveryTaskComputeDurationExact() throws Exception {
        Path workflow = chainDax();
        SimulationReport base = run(
                workflow,
                SimulationConfig.builder(workflow.toString(), 2)
                        .runtimeScale(1.0)
                        .runtimeReferenceMips(1000.0)
                        .build());
        SimulationReport scaled = run(
                workflow,
                SimulationConfig.builder(workflow.toString(), 2)
                        .runtimeScale(2.0)
                        .runtimeReferenceMips(1000.0)
                        .build(),
                2.0);

        List<SimulationReport.TaskOutcome> baseTasks = base.getTasks();
        List<SimulationReport.TaskOutcome> scaledTasks = scaled.getTasks();
        assertEquals(baseTasks.size(), scaledTasks.size(), "任务数必须一致");
        for (int i = 0; i < baseTasks.size(); i++) {
            SimulationReport.TaskOutcome left = baseTasks.get(i);
            SimulationReport.TaskOutcome right = scaledTasks.get(i);
            assertEquals(left.getTaskId(), right.getTaskId());
            double leftDuration = left.getFinishTime() - left.getStartTime();
            double rightDuration = right.getFinishTime() - right.getStartTime();
            long leftMi = left.getLengthMi();
            long rightMi = right.getLengthMi();
            assertEquals(leftMi * 2, rightMi, "任务 " + left.getTaskId() + " 的 MI 应随 scale 翻倍（100 s × 1000 × 2）");
            assertEquals(leftDuration, rightDuration, 0.0, "任务 " + left.getTaskId() + " 计算时长必须精确不变（MI×2 与 MIPS×2 相消）");
        }
    }

    // ---- 公共运行与断言 ----

    private SimulationReport run(Path workflow, SimulationConfig config) throws Exception {
        return run(workflow, config, 1.0);
    }

    private SimulationReport run(Path workflow, SimulationConfig config, double mipsFactor) throws Exception {
        PlatformProfile platform;
        if (mipsFactor == 1.0) {
            platform = PlatformProfiles.homogeneousLocal("metamorphic-base", 2);
        } else {
            platform = PlatformProfiles.heterogeneousLocal(
                    "metamorphic-scaled", java.util.Arrays.asList(1000.0 * mipsFactor, 1000.0 * mipsFactor));
        }
        return new SimulationRunner().run(config, platform);
    }

    private static void assertEventStreamsIdentical(SimulationReport left, SimulationReport right) {
        List<SimulationEvent> a = left.getEvents();
        List<SimulationEvent> b = right.getEvents();
        assertEquals(a.size(), b.size(), "事件总数必须一致");
        for (int i = 0; i < a.size(); i++) {
            SimulationEvent first = a.get(i);
            SimulationEvent second = b.get(i);
            assertEquals(first.getSequence(), second.getSequence(), "事件序号");
            assertEquals(first.getType(), second.getType(), "事件类型@" + i);
            assertEquals(first.getSimulationTime(), second.getSimulationTime(), 0.0, "事件时刻@" + i);
            assertEquals(first.getJobId(), second.getJobId(), "事件 Job@" + i);
        }
    }

    private static void assertTaskWindowsIdentical(SimulationReport left, SimulationReport right) {
        List<SimulationReport.TaskOutcome> a = left.getTasks();
        List<SimulationReport.TaskOutcome> b = right.getTasks();
        assertEquals(a.size(), b.size());
        for (int i = 0; i < a.size(); i++) {
            assertEquals(a.get(i).getTaskId(), b.get(i).getTaskId());
            assertEquals(a.get(i).getStartTime(), b.get(i).getStartTime(), 0.0, "任务开始时刻必须逐位一致");
            assertEquals(a.get(i).getFinishTime(), b.get(i).getFinishTime(), 0.0, "任务完成时刻必须逐位一致");
            assertEquals(a.get(i).getVmId(), b.get(i).getVmId(), "任务 VM 派发必须一致");
        }
    }

    /** 4 任务纯链 DAX（runtime 100 秒，无文件）——远离 100 MI 下限避免 floor 干扰。 */
    private Path chainDax() throws Exception {
        StringBuilder dax = new StringBuilder();
        dax.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        dax.append("<adag version=\"3.3\" count=\"4\">\n");
        for (int id = 1; id <= 4; id++) {
            dax.append("  <job id=\"t").append(id).append("\" name=\"chain\" runtime=\"100\"/>\n");
        }
        for (int id = 2; id <= 4; id++) {
            dax.append("  <child ref=\"t").append(id).append("\">\n");
            dax.append("    <parent ref=\"t").append(id - 1).append("\"/>\n");
            dax.append("  </child>\n");
        }
        dax.append("</adag>\n");
        Path path = tempDir.resolve("chain.dax");
        Files.write(path, dax.toString().getBytes(StandardCharsets.UTF_8));
        return path;
    }
}
