package org.workflowsim.examples;

import org.cloudbus.cloudsim.Log;
import org.workflowsim.experiment.*;
import org.workflowsim.platform.PlatformProfile;
import org.workflowsim.platform.PlatformProfiles;
import org.workflowsim.utils.Parameters.*;
import org.workflowsim.utils.ReplicaCatalog;
import org.workflowsim.utils.SimulationConfig;

import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * 可配置实验模板 - 所有参数在代码中配置，IDEA 中可直接右键运行。
 * 
 * <p>复制这个文件，修改下面的配置部分，然后右键 Run 即可。</p>
 */
public class MyConfigurableExperiment {
    
    // ========================================================================
    // 📝 实验配置区（修改这里的参数）
    // ========================================================================
    
    /** 实验名称（用于输出目录） */
    private static final String EXPERIMENT_NAME = "my-experiment";
    
    /** 工作流文件路径（相对于项目根目录） */
    private static final String WORKFLOW_PATH = "datasets/dax/montage/n25/Montage_25.dax";
    
    /** 调度算法 */
    private static final SchedulingAlgorithm ALGORITHM = SchedulingAlgorithm.READY_BATCH_MINMIN;
    
    /** VM 数量 */
    private static final int VM_COUNT = 4;
    
    /** 随机种子（用于可复现） */
    private static final long RANDOM_SEED = 42L;
    
    /** deadline（秒，设为 0 表示无 deadline） */
    private static final long DEADLINE = 0L;  // 0 = 无 deadline
    
    /** 文件系统类型 */
    private static final ReplicaCatalog.FileSystem FILE_SYSTEM = ReplicaCatalog.FileSystem.SHARED;
    
    /** 是否保存实验证据（manifest/metrics/events） */
    private static final boolean SAVE_ARTIFACTS = true;
    
    /** 是否生成 CSV 表格（jobs/tasks/vms） */
    private static final boolean SAVE_CSV = true;
    
    /** 是否生成 HTML 可视化报告 */
    private static final boolean SAVE_HTML = true;
    
    /** 是否显示详细控制台输出（分组指标 + VM 明细表） */
    private static final boolean VERBOSE_CONSOLE = true;
    
    /** 输出目录（相对于项目根目录，为 null 则不保存） */
    private static final String OUTPUT_DIR = "output/my-experiments";
    
    /** 是否使用时间戳子目录（避免覆盖，推荐 true） */
    private static final boolean USE_TIMESTAMP_DIR = true;
    
    // ========================================================================
    // 🚀 主程序（通常不需要修改）
    // ========================================================================
    
    public static void main(String[] args) throws Exception {
        // 1. 禁用 CloudSim 冗长日志
        Log.disable();

        // 1.5 解析命令行覆盖（论文复现轮修复：此前本类完全忽略 args，
        //     传入的算法标签被静默丢弃，实际运行的是硬编码 ALGORITHM——
        //     这是一个可复现性陷阱。现在显式解析并 fail-fast。）
        // 用法：MyConfigurableExperiment [调度算法标签] [规划算法标签]
        //   例：MyConfigurableExperiment FCFS
        //       MyConfigurableExperiment STATIC PSO
        //   无参数时使用下方配置区的默认值。
        SchedulingAlgorithm scheduling = ALGORITHM;
        PlanningAlgorithm planning = PlanningAlgorithm.INVALID;
        if (args.length >= 1) {
            try {
                scheduling = SchedulingAlgorithm.valueOf(args[0].toUpperCase());
            } catch (IllegalArgumentException e) {
                System.err.println("未知调度算法标签: " + args[0]);
                System.err.println("有效标签: FCFS, READY_BATCH_MINMIN, READY_BATCH_MAXMIN, "
                        + "READY_BATCH_MCT, READY_BATCH_ROUNDROBIN, DATA, STATIC");
                System.exit(2);
            }
        }
        if (args.length >= 2) {
            try {
                planning = PlanningAlgorithm.valueOf(args[1].toUpperCase());
            } catch (IllegalArgumentException e) {
                System.err.println("未知规划算法标签: " + args[1]);
                System.err.println("有效标签: RANDOM, PSO, STATIC_OLB, STATIC_MET, STATIC_MCT, "
                        + "STATIC_MINMIN, STATIC_MAXMIN, STATIC_SUFFERAGE, STATIC_ROUND_ROBIN, "
                        + "SHARED_STORAGE_HEFT, SHARED_STORAGE_CPOP, SHARED_STORAGE_DLS, "
                        + "SHARED_STORAGE_ETF, SHARED_STORAGE_PEFT");
                System.exit(2);
            }
        }
        // 算法目录准入校验：只允许标准运行器支持的标签，避免静默退化。
        if (!AlgorithmCatalog.isSupportedBySimulationRunner(scheduling)
                || !AlgorithmCatalog.isSupportedBySimulationRunner(planning)) {
            System.err.println("算法标签不被标准运行器支持: " + scheduling.name()
                    + (planning != PlanningAlgorithm.INVALID ? " + " + planning.name() : ""));
            System.exit(2);
        }
        // 规划算法必须搭配 STATIC 派发（SimulationConfig 同样强制，这里提前报错更清晰）。
        if (planning != PlanningAlgorithm.INVALID && scheduling != SchedulingAlgorithm.STATIC) {
            System.err.println("规划算法 " + planning.name() + " 必须搭配 STATIC 调度派发");
            System.exit(2);
        }

        // 2. 解析路径（支持相对路径）
        Path projectRoot = Paths.get(System.getProperty("user.dir"));
        Path workflowPath = projectRoot.resolve(WORKFLOW_PATH);

        System.out.println("═══════════════════════════════════════════════════════");
        System.out.println("  实验名称: " + EXPERIMENT_NAME);
        System.out.println("═══════════════════════════════════════════════════════");
        System.out.println("工作流:   " + workflowPath.getFileName());
        System.out.println("算法:     " + scheduling.name()
                + (planning != PlanningAlgorithm.INVALID ? " + 规划 " + planning.name() : ""));
        System.out.println("VM 数量:  " + VM_COUNT);
        System.out.println("随机种子: " + RANDOM_SEED);
        if (DEADLINE > 0) {
            System.out.println("Deadline: " + DEADLINE + " 秒");
        }
        System.out.println("═══════════════════════════════════════════════════════");
        System.out.println();
        
        // 3. 配置仿真参数
        SimulationConfig.Builder configBuilder = SimulationConfig.builder(
                workflowPath.toString(), 
                VM_COUNT
            )
            .schedulingAlgorithm(scheduling)
            .planningAlgorithm(planning)
            .randomSeed(RANDOM_SEED)
            .fileSystem(FILE_SYSTEM);
        
        // 可选：添加 deadline
        if (DEADLINE > 0) {
            configBuilder.deadline(DEADLINE);
        }
        
        SimulationConfig config = configBuilder.build();
        
        // 4. 定义平台（同构 VM）
        PlatformProfile platform = PlatformProfiles.homogeneousLocal(
            EXPERIMENT_NAME + "-platform", 
            VM_COUNT
        );
        
        // 5. 运行仿真
        System.out.println("⏳ 开始仿真...");
        SimulationRunner runner = new SimulationRunner();
        SimulationReport report = runner.run(config, platform);
        
        // 6. 输出结果
        System.out.println();
        if (VERBOSE_CONSOLE) {
            // 详细输出：分组指标 + VM 明细表
            System.out.println(ExperimentConsoleSummary.format(report));
            System.out.println();
            System.out.println(ExperimentConsoleSummary.formatVmTable(report));
        } else {
            // 简洁输出：仅关键指标
            System.out.println("═══════════════════════════════════════════════════════");
            System.out.println("  仿真完成");
            System.out.println("═══════════════════════════════════════════════════════");
            System.out.println("Makespan:       " + String.format("%.2f", report.getMakespan()) + " 秒");
            System.out.println("成功 Job 数:    " + report.getSuccessfulJobs());
            System.out.println("失败 Job 数:    " + report.getFailedJobs());
            System.out.println("平均 VM 利用率: " + 
                String.format("%.2f%%", report.getMetrics().getMeanVmModeledIntervalUtilization() * 100));
            System.out.println("总 CPU 成本:    " + String.format("%.2f", report.getMetrics().getTotalModeledProcessingCost()));
            
            if (DEADLINE > 0) {
                boolean met = report.getMetrics().isDeadlineMet();
                double slack = report.getMetrics().getDeadlineSlackSeconds();
                System.out.println("Deadline 满足:  " + (met ? "是" : "否"));
                System.out.println("Deadline Slack: " + String.format("%.2f", slack) + " 秒");
            }
            
            System.out.println("═══════════════════════════════════════════════════════");
        }
        
        // 7. 可选：保存实验证据和报告
        if ((SAVE_ARTIFACTS || SAVE_CSV || SAVE_HTML) && OUTPUT_DIR != null) {
            Path outputPath = projectRoot.resolve(OUTPUT_DIR);
            
            // 使用时间戳子目录避免覆盖
            String runId = EXPERIMENT_NAME;
            if (USE_TIMESTAMP_DIR) {
                String timestamp = new java.text.SimpleDateFormat("yyyyMMdd-HHmmss")
                    .format(new java.util.Date());
                outputPath = outputPath.resolve("run-" + timestamp);
                runId = "result";  // 子目录内文件名简化
            }
            
            outputPath.toFile().mkdirs();
            
            System.out.println();
            System.out.println("📁 保存实验结果到: " + outputPath.toAbsolutePath());
            System.out.println();
            
            // JSON 证据包（manifest/metrics/events）
            if (SAVE_ARTIFACTS) {
                ExperimentArtifactWriter.ExperimentArtifacts artifacts = 
                    ExperimentArtifactWriter.write(report, outputPath, runId);
                System.out.println("✅ JSON 证据包:");
                System.out.println("   " + artifacts.getManifest().getFileName());
                System.out.println("   " + artifacts.getMetrics().getFileName());
                System.out.println("   " + artifacts.getEvents().getFileName());
            }
            
            // CSV 表格（jobs/tasks/vms）
            if (SAVE_CSV) {
                ExperimentCsvWriter.CsvArtifacts csvArtifacts = 
                    ExperimentCsvWriter.write(report, outputPath, runId);
                System.out.println("✅ CSV 详细表格:");
                System.out.println("   " + csvArtifacts.getJobs().getFileName() + 
                    " (" + report.getJobs().size() + " 个作业)");
                System.out.println("   " + csvArtifacts.getTasks().getFileName() + 
                    " (" + report.getTasks().size() + " 个任务)");
                System.out.println("   " + csvArtifacts.getVms().getFileName() + 
                    " (" + report.getMetrics().getVmMetrics().size() + " 个 VM)");
            }
            
            // HTML 可视化报告
            if (SAVE_HTML) {
                Path htmlPath = outputPath.resolve(runId + ".html");
                ExperimentHtmlReportWriter.write(report, htmlPath, EXPERIMENT_NAME);
                System.out.println("✅ HTML 可视化报告:");
                System.out.println("   " + htmlPath.getFileName());
                System.out.println("   🌐 在浏览器中打开查看交互式图表");
            }
        }
    }
}
