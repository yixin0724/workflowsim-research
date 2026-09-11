/*
 * WfCommons JSON 端到端仿真示例。
 *
 * 对一个 WfCommons WfFormat JSON 工作流实例运行标准 WorkflowSim 流程
 * （解析 -> 聚类 -> 规划 -> 调度 -> 执行），说明 JSON 数据集可以作为一等输入。
 */
package org.workflowsim.examples.wfcommons;

import java.text.DecimalFormat;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.cloudbus.cloudsim.Log;
import org.workflowsim.experiment.SimulationReport;
import org.workflowsim.experiment.SimulationRunner;
import org.workflowsim.platform.PlatformProfile;
import org.workflowsim.platform.PlatformProfiles;
import org.workflowsim.utils.Parameters;
import org.workflowsim.utils.SimulationConfig;

/**
 * 对 WfCommons JSON 工作流实例执行端到端标准仿真。
 *
 * <p>用法：{@code main [jsonPath]}；默认输入为
 * {@code datasets/wfformat/montage/n100/montage-100-000.json}。该示例使用
 * 显式命名的 ready-batch Min-Min 策略，不启用聚类或规划，使 JSON 输入能够进入与
 * DAX 相同的标准 WorkflowSim 流程。</p>
 */
public final class WfCommonsSimulationExample1 {

    private WfCommonsSimulationExample1() {
    }

    public static void main(String[] args) throws Exception {
        if (args != null && args.length > 1) {
            throw new IllegalArgumentException("Usage: WfCommonsSimulationExample1 [jsonPath]");
        }
        int vmNum = 20;
        String jsonPath = args != null && args.length > 0
                ? args[0]
                : "datasets/wfformat/montage/n100/montage-100-000.json";
        Path jsonFile = Paths.get(jsonPath).toAbsolutePath().normalize();
        if (!Files.isRegularFile(jsonFile)) {
            throw new IllegalArgumentException("Workflow JSON does not exist or is not a regular file: " + jsonFile);
        }

        SimulationConfig config = SimulationConfig.builder(jsonFile.toString(), vmNum)
                .schedulingAlgorithm(Parameters.SchedulingAlgorithm.READY_BATCH_MINMIN)
                .randomSeed(20260901L)
                .build();
        PlatformProfile platform = PlatformProfiles.homogeneousLocal(
                "wfcommons-homogeneous-local", vmNum);
        SimulationReport report = new SimulationRunner().run(config, platform);
        printReport(report);
    }

    /** 输出已完成运行的紧凑可复现性摘要。 */
    protected static void printReport(SimulationReport report) {
        Log.printLine();
        Log.printLine("========== OUTPUT ==========");
        DecimalFormat dft = new DecimalFormat("###.##");
        Log.printLine("WFJSON_SUMMARY input=" + report.getInputs().get(0).getPath()
                + " sha256=" + report.getInputs().get(0).getSha256()
                + " jobs=" + report.getTotalJobs()
                + " success=" + report.getSuccessfulJobs()
                + " failed=" + report.getFailedJobs()
                + " makespan=" + dft.format(report.getMakespan())
                + " seed=" + report.getConfig().getRandomSeed());
    }
}
