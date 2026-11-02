package org.workflowsim.experiments.rerun;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.workflowsim.data.DataMovementModel;
import org.workflowsim.experiment.ExperimentArtifactWriter;
import org.workflowsim.experiment.SimulationReport;
import org.workflowsim.experiment.SimulationRunner;
import org.workflowsim.failure.FailureModelConfig;
import org.workflowsim.failure.FailureParameters;
import org.workflowsim.platform.PlatformProfiles;
import org.workflowsim.utils.DistributionGenerator.DistributionFamily;
import org.workflowsim.utils.DistributionSpec;
import org.workflowsim.utils.Parameters;
import org.workflowsim.utils.ReplicaCatalog;
import org.workflowsim.utils.SimulationConfig;

/** 临时：随机失败重试场景下的 seed 敏感性（用后即删）。 */
public final class TmpSeedSensitivity {
    private TmpSeedSensitivity() {}

    public static void main(String[] args) throws Exception {
        Path root = Paths.get(args[1]).toAbsolutePath();
        Files.createDirectories(root);
        Path workflow = root.resolve("seed.dax");
        Files.write(
                workflow,
                ("<adag version=\"3.3\" count=\"4\">"
                                + "<job id=\"a\" name=\"a\" runtime=\"30\"/>"
                                + "<job id=\"b\" name=\"b\" runtime=\"20\"/>"
                                + "<job id=\"c\" name=\"c\" runtime=\"15\"/>"
                                + "<job id=\"d\" name=\"d\" runtime=\"25\"/>"
                                + "<child ref=\"b\"><parent ref=\"a\"/></child>"
                                + "<child ref=\"c\"><parent ref=\"b\"/></child>"
                                + "<child ref=\"d\"><parent ref=\"c\"/></child></adag>")
                        .getBytes(StandardCharsets.UTF_8));
        for (long seed : new long[] {20261107L, 20261108L}) {
            SimulationReport report =
                    new SimulationRunner().run(config(workflow, seed), PlatformProfiles.homogeneousLocal("seed", 2));
            Path run = root.resolve("seed-" + seed);
            ExperimentArtifactWriter.write(report, run, "result");
            System.out.println("SEED " + seed + " makespan=" + report.getMakespan()
                    + " retried=" + report.getMetrics().getRetriedLogicalTaskCount()
                    + " failedAttempts=" + report.getMetrics().getFailedJobOutcomeCount());
        }
    }

    private static SimulationConfig config(Path workflow, long seed) {
        DistributionSpec perDepth = DistributionSpec.of(DistributionFamily.WEIBULL, 20.0, 1.0);
        DistributionSpec[] byDepth = new DistributionSpec[8];
        java.util.Arrays.fill(byDepth, perDepth);
        FailureModelConfig failure = FailureModelConfig.builder()
                .clusteringAlgorithm(FailureParameters.FTCluteringAlgorithm.FTCLUSTERING_NOOP)
                .monitorMode(FailureParameters.FTCMonitor.MONITOR_NONE)
                .generatorMode(FailureParameters.FTCFailure.FAILURE_JOB)
                .generatorSpecs(new DistributionSpec[][] {byDepth})
                .maxTotalRetryJobs(200)
                .build();
        return SimulationConfig.builder(workflow.toString(), 2)
                .schedulingAlgorithm(Parameters.SchedulingAlgorithm.FCFS)
                .fileSystem(ReplicaCatalog.FileSystem.SHARED)
                .dataMovementModel(DataMovementModel.legacyWorkflowsimV1())
                .failureModel(failure)
                .randomSeed(seed)
                .build();
    }
}
