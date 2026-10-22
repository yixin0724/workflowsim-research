package org.workflowsim.planning;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.cloudbus.cloudsim.CloudletSchedulerSpaceShared;
import org.cloudbus.cloudsim.Log;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.workflowsim.CondorVM;
import org.workflowsim.Task;
import org.workflowsim.WorkflowParser;
import org.workflowsim.data.DataMovementModel;
import org.workflowsim.experiment.SimulationReport;
import org.workflowsim.experiment.SimulationRunner;
import org.workflowsim.platform.PlatformProfile;
import org.workflowsim.utils.Parameters.PlanningAlgorithm;
import org.workflowsim.utils.Parameters.SchedulingAlgorithm;
import org.workflowsim.utils.ReplicaCatalog;
import org.workflowsim.utils.SimulationConfig;

/**
 * C06：LOCAL_HEFT 规划→执行镜像端到端契约（审查工程 Phase 2，H-9 系统化）。
 *
 * <p>契约（docs 对 LOCAL 家族的宣称）：LOCAL 规划器的估算器"逐位镜像"运行时
 * pre-execution 传输模型。既有测试分别锚定规划公式（rank/插入）与执行侧
 * （本地可用性）；本测试把两侧接到<b>同一次完整仿真的任务窗口</b>：</p>
 * <ol>
 *   <li>标准 SimulationRunner 完整运行（LOCAL + PRE_EXECUTION_TRANSFER_DELAY_V1
 *       + LOCAL_HEFT + STATIC，无开销/故障）；</li>
 *   <li>同一 DAX 独立重解析 + 独立 LocalHeftPlanningAlgorithm 复规划（全新对象）；</li>
 *   <li>逐任务断言：VM 绑定、计划开始时刻、计划完成时刻与运行时
 *       {@link SimulationReport.TaskOutcome} 窗口<b>0 容差相等</b>。</li>
 * </ol>
 *
 * <p>两侧使用同一物理常量体系（同一 PlanningContext 构造路径）；任何一侧公式、
 * 时序或换算漂移都会使本镜像断言红——相对比较不依赖手推绝对值。</p>
 */
class PlannerRuntimeMirrorIntegrationTest {

    private static final double ZERO_TOLERANCE = 0.0;

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
    void localHeftPlannedWindowsMirrorActualRuntimeWindowsExactly() throws Exception {
        mirrorPlannedWindowsAgainstActualRuntime(PlanningAlgorithm.LOCAL_HEFT);
    }

    /** 链 F 扩展（S-F1）：CPOP 的 0 容差镜像——关键处理器逻辑不破坏端到端恒等。 */
    @Test
    void localCpopPlannedWindowsMirrorActualRuntimeWindowsExactly() throws Exception {
        mirrorPlannedWindowsAgainstActualRuntime(PlanningAlgorithm.LOCAL_CPOP);
    }

    /** 链 F 扩展（S-F1）：PEFT 的 0 容差镜像——OCT 评分路径不破坏端到端恒等。 */
    @Test
    void localPeftPlannedWindowsMirrorActualRuntimeWindowsExactly() throws Exception {
        mirrorPlannedWindowsAgainstActualRuntime(PlanningAlgorithm.LOCAL_PEFT);
    }

    /**
     * 通用镜像驱动：完整 SimulationRunner 运行 vs 独立重解析+复规划，
     * 逐任务断言 VM 绑定/计划开始/计划完成 0 容差镜像
     * （LOCAL_HEFT / LOCAL_CPOP / LOCAL_PEFT，AbstractLocalCommPlanningAlgorithm 家族）。
     */
    private void mirrorPlannedWindowsAgainstActualRuntime(PlanningAlgorithm algorithm) throws Exception {
        Path workflow = layeredDax();
        PlatformProfile platform = mirrorPlatform();
        SimulationConfig config = SimulationConfig.builder(workflow.toString(), 2)
                .planningAlgorithm(algorithm)
                .schedulingAlgorithm(SchedulingAlgorithm.STATIC)
                .fileSystem(ReplicaCatalog.FileSystem.LOCAL)
                .dataMovementModel(DataMovementModel.preExecutionTransferDelayV1())
                .randomSeed(20261019L)
                .build();

        SimulationReport report = new SimulationRunner().run(config, platform);
        assertEquals(true, report.getMetrics().isAllLogicalTasksCompletedSuccessfully(), "镜像基线要求仿真完整成功：" + algorithm);

        List<Task> reparsed = reparse(workflow, algorithm);
        org.workflowsim.planning.BasePlanningAlgorithm planner =
                createPlanner(algorithm, new PlanningContext(config, platform));
        planner.setTaskList(reparsed);
        List<CondorVM> vms = new ArrayList<CondorVM>();
        for (PlatformProfile.VmSpec spec : platform.getVms()) {
            vms.add(mirrorVm(spec.getId(), spec.getMips(), spec.getBandwidth()));
        }
        planner.setVmList(vms);
        planner.run();

        List<SimulationReport.TaskOutcome> outcomes = report.getTasks();
        assertEquals(reparsed.size(), outcomes.size(), "任务数必须一致（同一 DAX）：" + algorithm);
        org.workflowsim.planning.AbstractLocalCommPlanningAlgorithm localPlanner =
                (org.workflowsim.planning.AbstractLocalCommPlanningAlgorithm) planner;
        for (SimulationReport.TaskOutcome outcome : outcomes) {
            Task planned = findById(reparsed, outcome.getTaskId());
            assertEquals(
                    planned.getVmId(), outcome.getVmId(), 0, algorithm + " 任务 " + outcome.getTaskId() + " 的 VM 绑定必须镜像");
            assertEquals(
                    planned.getStaticScheduleStartTime(),
                    outcome.getStartTime(),
                    ZERO_TOLERANCE,
                    algorithm + " 任务 " + outcome.getTaskId() + " 计划开始必须逐位镜像");
            assertEquals(
                    localPlanner.plannedFinishOf(planned).doubleValue(),
                    outcome.getFinishTime(),
                    ZERO_TOLERANCE,
                    algorithm + " 任务 " + outcome.getTaskId() + " 计划完成必须逐位镜像");
        }
    }

    private static org.workflowsim.planning.BasePlanningAlgorithm createPlanner(
            PlanningAlgorithm algorithm, PlanningContext context) {
        switch (algorithm) {
            case LOCAL_HEFT:
                return new LocalHeftPlanningAlgorithm(context);
            case LOCAL_CPOP:
                return new org.workflowsim.planning.LocalCpopPlanningAlgorithm(context);
            case LOCAL_PEFT:
                return new org.workflowsim.planning.LocalPeftPlanningAlgorithm(context);
            default:
                throw new IllegalArgumentException("镜像驱动不支持规划算法 " + algorithm);
        }
    }

    // ---- 夹具 ----

    /** 6 任务 3 层 DAG，边带通信文件（层1→层2→层3），桥接文件 4MB/秒带宽八进制换算。 */
    private Path layeredDax() throws Exception {
        StringBuilder dax = new StringBuilder();
        dax.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        dax.append("<adag version=\"3.3\" count=\"6\">\n");
        int id = 1;
        for (int layer = 0; layer < 3; layer++) {
            int count = layer == 1 ? 2 : layer == 0 ? 2 : 2;
            for (int i = 0; i < count; i++) {
                dax.append("  <job id=\"t")
                        .append(id++)
                        .append("\" name=\"l")
                        .append(layer)
                        .append("\" runtime=\"")
                        .append(50 + 10 * i)
                        .append("\">\n");
                if (layer > 0) {
                    dax.append("    <uses name=\"in-")
                            .append(id - 1)
                            .append(".bin\" link=\"input\" size=\"7000000\"/>\n");
                }
                dax.append("    <uses name=\"out-")
                        .append(id - 1)
                        .append(".bin\" link=\"output\" size=\"7000000\"/>\n");
                dax.append("  </job>\n");
            }
        }
        // 层间依赖：1,2 → 3,4 → 5,6（交错父子）。
        dax.append("  <child ref=\"t3\"><parent ref=\"t1\"/></child>\n");
        dax.append("  <child ref=\"t4\"><parent ref=\"t1\"/></child>\n");
        dax.append("  <child ref=\"t4\"><parent ref=\"t2\"/></child>\n");
        dax.append("  <child ref=\"t5\"><parent ref=\"t3\"/></child>\n");
        dax.append("  <child ref=\"t6\"><parent ref=\"t4\"/></child>\n");
        dax.append("</adag>\n");
        Path path = tempDir.resolve("mirror.dax");
        Files.write(path, dax.toString().getBytes(StandardCharsets.UTF_8));
        return path;
    }

    private static PlatformProfile mirrorPlatform() {
        PlatformProfile.Builder builder = PlatformProfile.builder("mirror-platform");
        builder.addHost(new PlatformProfile.HostSpec(0, 2, 2000.0, 2048, 10_000L, 1_000_000L));
        builder.addVm(new PlatformProfile.VmSpec(
                0, 1000.0, 1, 512, 1_000L, 10_000L, "Xen", PlatformProfile.CloudletSchedulerMode.SPACE_SHARED));
        builder.addHost(new PlatformProfile.HostSpec(1, 2, 2000.0, 2048, 10_000L, 1_000_000L));
        builder.addVm(new PlatformProfile.VmSpec(
                1, 1000.0, 1, 512, 1_000L, 10_000L, "Xen", PlatformProfile.CloudletSchedulerMode.SPACE_SHARED));
        return builder.build();
    }

    private static CondorVM mirrorVm(int id, double mips, long bandwidth) {
        return new CondorVM(id, 0, mips, 1, 512, bandwidth, 10_000L, "Xen", new CloudletSchedulerSpaceShared());
    }

    private List<Task> reparse(Path workflow, PlanningAlgorithm algorithm) {
        ReplicaCatalog.reset();
        ReplicaCatalog.init(ReplicaCatalog.FileSystem.LOCAL);
        org.workflowsim.utils.Parameters.reset();
        org.workflowsim.utils.Parameters.init(
                2, workflow.toString(), null, null, null, null, SchedulingAlgorithm.STATIC, algorithm, null, 0L);
        WorkflowParser parser = new WorkflowParser(0);
        parser.parse();
        return parser.getTaskList();
    }

    private static Task findById(List<Task> tasks, int taskId) {
        for (Task task : tasks) {
            if (task.getCloudletId() == taskId) {
                return task;
            }
        }
        throw new AssertionError("复规划结果缺少任务 " + taskId);
    }
}
