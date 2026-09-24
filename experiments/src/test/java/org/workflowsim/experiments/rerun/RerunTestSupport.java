package org.workflowsim.experiments.rerun;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.cloudbus.cloudsim.Log;
import org.workflowsim.experiment.ExperimentArtifactWriter;
import org.workflowsim.experiment.SimulationReport;
import org.workflowsim.experiment.SimulationRunner;
import org.workflowsim.platform.PlatformProfiles;
import org.workflowsim.utils.Parameters.SchedulingAlgorithm;
import org.workflowsim.utils.SimulationConfig;

/**
 * rerun 测试共享夹具：用一次真实小规模仿真（5 任务 DAX × 3 同构 VM）写出
 * 标准 v4 三件套，供各阶段测试复制后按需篡改。
 */
final class RerunTestSupport {

    static final String FIXTURE_RUN_ID = "result";

    private RerunTestSupport() {
    }

    /** 在 {@code directory} 中生成一套完整的 v4 证据（runId 为 result）。 */
    static Path generateEvidence(Path directory) throws Exception {
        Log.disable();
        String dax = resourcePath("/dax/reproducibility-workflow.dax");
        SimulationConfig config = SimulationConfig.builder(dax, 3)
                .schedulingAlgorithm(SchedulingAlgorithm.FCFS)
                .randomSeed(91L)
                .build();
        SimulationReport report = new SimulationRunner().run(config,
                PlatformProfiles.homogeneousLocal("rerun-fixture", 3));
        if (!report.getMetrics().isAllLogicalTasksCompletedSuccessfully()) {
            throw new IllegalStateException("Fixture simulation did not complete all tasks");
        }
        ExperimentArtifactWriter.write(report, directory, FIXTURE_RUN_ID);
        return directory;
    }

    /** 测试资源在磁盘上的绝对路径。 */
    static String resourcePath(String resource) throws Exception {
        return Paths.get(RerunTestSupport.class.getResource(resource).toURI()).toString();
    }

    /** 递归复制目录内全部常规文件（单层，不含子目录）。 */
    static void copyEvidenceFiles(Path source, Path target) throws Exception {
        Files.createDirectories(target);
        try (java.util.stream.Stream<Path> stream = Files.list(source)) {
            for (Path file : (Iterable<Path>) stream.filter(Files::isRegularFile)::iterator) {
                Files.copy(file, target.resolve(file.getFileName()));
            }
        }
    }
}
