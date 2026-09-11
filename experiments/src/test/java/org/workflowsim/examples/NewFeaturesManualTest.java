package org.workflowsim.examples;

import org.junit.jupiter.api.Test;
import org.cloudbus.cloudsim.Log;
import org.workflowsim.experiment.*;
import org.workflowsim.platform.*;
import org.workflowsim.utils.*;
import java.nio.file.*;

/** 手动测试新增的可视化功能 */
public class NewFeaturesManualTest {
    
    @Test
    public void manualTestNewVisualizations() throws Exception {
        Log.disable();
        
        Path projectRoot = Paths.get(System.getProperty("user.dir"));
        Path workflowPath = projectRoot.resolve("datasets/dax/epigenomics/n24/Epigenomics_24.dax");
        
        SimulationConfig config = SimulationConfig.builder(workflowPath.toString(), 2)
            .schedulingAlgorithm(Parameters.SchedulingAlgorithm.READY_BATCH_MINMIN)
            .randomSeed(42L)
            .fileSystem(ReplicaCatalog.FileSystem.SHARED)
            .build();
        
        PlatformProfile platform = PlatformProfiles.homogeneousLocal("test-platform", 2);
        
        SimulationRunner runner = new SimulationRunner();
        SimulationReport report = runner.run(config, platform);
        
        // 测试控制台摘要
        System.out.println("\n=== 控制台摘要测试 ===");
        System.out.println(ExperimentConsoleSummary.format(report));
        System.out.println(ExperimentConsoleSummary.formatVmTable(report));
        
        // 测试 CSV 写入
        Path outputDir = projectRoot.resolve("output/test-new-features");
        Files.createDirectories(outputDir);
        
        ExperimentCsvWriter.CsvArtifacts csv = ExperimentCsvWriter.write(report, outputDir, "test-run");
        System.out.println("\n=== CSV 文件生成 ===");
        System.out.println("Jobs CSV: " + csv.getJobs());
        System.out.println("Tasks CSV: " + csv.getTasks());
        System.out.println("VMs CSV: " + csv.getVms());
        
        // 测试 HTML 写入
        Path htmlPath = outputDir.resolve("test-run.html");
        ExperimentHtmlReportWriter.write(report, htmlPath, "测试报告");
        System.out.println("\n=== HTML 报告生成 ===");
        System.out.println("HTML: " + htmlPath);
        System.out.println("在浏览器打开查看: file://" + htmlPath.toAbsolutePath());
        
        System.out.println("\n✅ 所有新功能测试通过！");
    }
}
