package org.workflowsim.experiment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.cloudbus.cloudsim.Log;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.workflowsim.data.DataMovementModel;
import org.workflowsim.platform.PlatformProfile;
import org.workflowsim.platform.PlatformProfiles;
import org.workflowsim.utils.Parameters;
import org.workflowsim.utils.ReplicaCatalog;
import org.workflowsim.utils.SimulationConfig;

/**
 * 大规模工作流（Montage 1000 任务）的算法轨道规模回归测试。
 *
 * <p>动机：既有算法语义测试全部使用 ≤ 24 任务的小规模 fixture，科研实验通常在数百到
 * 数千任务规模上进行。本测试把千任务级执行纳入回归门禁，验证三个代表性轨道在大规模
 * 下的正确性与数值稳定性：</p>
 * <ul>
 *   <li>通信感知静态 DAG 规划（LOCAL_HEFT，LOCAL 文件系统 + 执行前传输延迟模型）；</li>
 *   <li>在线 ready-batch 调度（READY_BATCH_MINMIN 与 FCFS，SHARED 文件系统）；</li>
 *   <li>同配置重复运行的确定性（黄金值 + 重跑逐位一致）。</li>
 * </ul>
 *
 * <p><b>断言口径</b>：黄金 makespan 以精确 double 相等锁定（确定性仿真下任何数值漂移
 * 都构成回归）；同时断言全部逻辑任务成功完成、无失败 Job、VM 映射在合法范围内。黄金
 * 值是本平台当前语义下的观察值，不构成对论文或真实平台的复现声明。</p>
 *
 * <p><b>数据集选择</b>：在线调度轨道使用 {@code datasets/dax/montage/n1000/Montage_1000.dax}
 * （Pegasus 标准 DAX，1000 任务，随仓库提交）。LOCAL 规划器轨道使用
 * {@code datasets/dax/epigenomics/n997/Epigenomics_997.dax}：Pegasus Montage DAX 的扁平
 * 文件命名空间下存在大量同名文件尺寸冲突（如各 mDiffFit 任务各自的 {@code fit.txt}），
 * LOCAL 规划器的严格尺寸守卫会拒绝，这是刻意平台边界而非缺陷；Epigenomics_997 文件名
 * 唯一、零冲突。</p>
 */
final class LargeWorkflowScaleRegressionTest {

    /** 黄金值：LOCAL_HEFT + LOCAL 文件系统 + preExecutionTransferDelayV1，16 VM，seed 42。 */
    private static final double LOCAL_HEFT_GOLDEN_MAKESPAN = 253528.02533242913;
    /** 黄金值：READY_BATCH_MINMIN + SHARED 文件系统，16 VM，seed 42。 */
    private static final double READY_BATCH_MINMIN_GOLDEN_MAKESPAN = 1223.7949999999978;
    /** 黄金值：FCFS + SHARED 文件系统，16 VM，seed 42。 */
    private static final double FCFS_GOLDEN_MAKESPAN = 1223.8519999999985;
    private static final int VM_COUNT = 16;
    private static final int MONTAGE_1000_TASK_COUNT = 1000;
    private static final int EPIGENOMICS_997_TASK_COUNT = 997;

    private static String workflowPath;

    @BeforeEach
    void disableLogging() {
        Log.disable();
    }

    @AfterEach
    void restoreLogging() {
        Log.enable();
    }

    /** 稳健定位仓库根：Maven surefire 的 user.dir 是 simulator/ 模块目录。 */
    static String datasetWorkflowPath() throws Exception {
        if (workflowPath == null) {
            Path root = Paths.get(System.getProperty("user.dir")).toAbsolutePath().normalize();
            while (root != null && !Files.exists(root.resolve("datasets/dax"))) {
                root = root.getParent();
            }
            if (root == null) {
                throw new IllegalStateException("Cannot locate repository root containing datasets/dax");
            }
            Path dax = root.resolve("datasets/dax/montage/n1000/Montage_1000.dax");
            if (!Files.isRegularFile(dax)) {
                throw new IllegalStateException("Missing required scale regression input: " + dax);
            }
            workflowPath = dax.toString();
        }
        return workflowPath;
    }

    @Test
    void localHeftCompletesEpigenomics997WithGoldenMakespan() throws Exception {
        // Montage_1000 不适用于 LOCAL 规划器：Pegasus Montage DAX 的扁平文件命名空间下
        // 大量同名文件（如各 mDiffFit 任务各自的 fit.txt）尺寸声明冲突，规划器的
        // 严格尺寸守卫会拒绝（这是刻意平台边界，见 AbstractLocalCommPlanningAlgorithm）。
        // Epigenomics_997 文件名唯一、零冲突，作为 LOCAL 轨道的大规模样本。
        SimulationConfig config = SimulationConfig.builder(epigenomicsPath(), VM_COUNT)
                .planningAlgorithm(Parameters.PlanningAlgorithm.LOCAL_HEFT)
                .schedulingAlgorithm(Parameters.SchedulingAlgorithm.STATIC)
                .randomSeed(42L)
                .fileSystem(ReplicaCatalog.FileSystem.LOCAL)
                .dataMovementModel(DataMovementModel.preExecutionTransferDelayV1())
                .build();

        SimulationReport report = new SimulationRunner().run(config, platform());

        // 逻辑任务 997 个；作业数 = 997 + 1 个历史 stage-in 引导作业（COMM-2 已知边界）。
        assertCompleteWorkflow(report, EPIGENOMICS_997_TASK_COUNT);
        assertEquals(LOCAL_HEFT_GOLDEN_MAKESPAN, report.getMakespan(),
                "LOCAL_HEFT Epigenomics_997 makespan 黄金值漂移");
        assertVmMappingsInPlatformRange(report);
    }

    @Test
    void readyBatchMinMinCompletesMontage1000WithGoldenMakespan() throws Exception {
        SimulationConfig config = SimulationConfig.builder(datasetWorkflowPath(), VM_COUNT)
                .schedulingAlgorithm(Parameters.SchedulingAlgorithm.READY_BATCH_MINMIN)
                .randomSeed(42L)
                .fileSystem(ReplicaCatalog.FileSystem.SHARED)
                .build();

        SimulationReport report = new SimulationRunner().run(config, platform());

        // 逻辑任务 1000 个；作业数 = 1000 + 1 个历史 stage-in 引导作业（COMM-2 已知边界）。
        assertCompleteWorkflow(report, MONTAGE_1000_TASK_COUNT);
        assertEquals(READY_BATCH_MINMIN_GOLDEN_MAKESPAN, report.getMakespan(),
                "READY_BATCH_MINMIN Montage_1000 makespan 黄金值漂移");
        assertVmMappingsInPlatformRange(report);
    }

    @Test
    void fcfsCompletesMontage1000WithGoldenMakespan() throws Exception {
        SimulationConfig config = SimulationConfig.builder(datasetWorkflowPath(), VM_COUNT)
                .schedulingAlgorithm(Parameters.SchedulingAlgorithm.FCFS)
                .randomSeed(42L)
                .fileSystem(ReplicaCatalog.FileSystem.SHARED)
                .build();

        SimulationReport report = new SimulationRunner().run(config, platform());

        assertCompleteWorkflow(report, MONTAGE_1000_TASK_COUNT);
        assertEquals(FCFS_GOLDEN_MAKESPAN, report.getMakespan(),
                "FCFS Montage_1000 makespan 黄金值漂移");
        assertVmMappingsInPlatformRange(report);
    }

    @Test
    void repeatedLargeScaleRunsAreBitwiseDeterministic() throws Exception {
        SimulationConfig config = SimulationConfig.builder(datasetWorkflowPath(), VM_COUNT)
                .schedulingAlgorithm(Parameters.SchedulingAlgorithm.READY_BATCH_MINMIN)
                .randomSeed(42L)
                .fileSystem(ReplicaCatalog.FileSystem.SHARED)
                .build();

        SimulationReport first = new SimulationRunner().run(config, platform());
        SimulationReport second = new SimulationRunner().run(config, platform());

        assertEquals(first.getMakespan(), second.getMakespan(), "重跑 makespan 必须逐位一致");
        assertEquals(first.getTotalJobs(), second.getTotalJobs());
        assertEquals(first.getSuccessfulJobs(), second.getSuccessfulJobs());
        assertEquals(first.getTasks().size(), second.getTasks().size());
        for (int i = 0; i < first.getJobs().size(); i++) {
            assertEquals(first.getJobs().get(i).getVmId(), second.getJobs().get(i).getVmId(),
                    "Job " + i + " 的 VM 映射在重跑间漂移");
        }
    }

    private static PlatformProfile platform() {
        return PlatformProfiles.homogeneousLocal("scale-regression-platform", VM_COUNT);
    }

    private static String epigenomicsPath() throws Exception {
        Path root = repositoryRoot();
        Path dax = root.resolve("datasets/dax/epigenomics/n997/Epigenomics_997.dax");
        if (!Files.isRegularFile(dax)) {
            throw new IllegalStateException("Missing required scale regression input: " + dax);
        }
        return dax.toString();
    }

    private static Path repositoryRoot() {
        Path root = Paths.get(System.getProperty("user.dir")).toAbsolutePath().normalize();
        while (root != null && !Files.exists(root.resolve("datasets/dax"))) {
            root = root.getParent();
        }
        if (root == null) {
            throw new IllegalStateException("Cannot locate repository root containing datasets/dax");
        }
        return root;
    }

    private static void assertCompleteWorkflow(SimulationReport report, int expectedTaskCount) {
        assertEquals(expectedTaskCount, report.getTasks().size());
        // 作业数 = 逻辑任务数 + 1 个历史 stage-in 引导作业（COMM-2 已知边界，不计入逻辑任务）。
        assertEquals(expectedTaskCount + 1, report.getTotalJobs());
        assertEquals(expectedTaskCount + 1, report.getSuccessfulJobs());
        assertEquals(0, report.getFailedJobs());
        assertTrue(report.getMakespan() > 0.0 && Double.isFinite(report.getMakespan()));
    }

    private static void assertVmMappingsInPlatformRange(SimulationReport report) {
        for (SimulationReport.JobOutcome job : report.getJobs()) {
            assertTrue(job.getVmId() >= 0 && job.getVmId() < VM_COUNT,
                    "Job VM 映射超出平台范围: " + job.getVmId());
        }
    }
}
