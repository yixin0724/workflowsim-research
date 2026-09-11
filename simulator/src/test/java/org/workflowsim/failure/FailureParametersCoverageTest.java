package org.workflowsim.failure;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.workflowsim.Task;
import org.workflowsim.utils.DistributionGenerator;
import org.workflowsim.utils.DistributionSpec;
import org.workflowsim.utils.SimulationConfig;
import org.workflowsim.utils.SimulationSession;

/** 验证失效模式在任务派发前按真实 depth 与 VM ID 检查生成器覆盖。 */
class FailureParametersCoverageTest {

    @AfterEach
    void resetFailureState() {
        FailureParameters.reset();
        FailureMonitor.reset();
    }

    @Test
    void jobScopedCoverageUsesActualDagDepthsWithoutSampling() {
        DistributionGenerator first = generator("coverage-job-0");
        DistributionGenerator second = generator("coverage-job-1");
        DistributionGenerator third = generator("coverage-job-2");
        FailureParameters.init(FailureParameters.FTCluteringAlgorithm.FTCLUSTERING_NOOP,
                FailureParameters.FTCMonitor.MONITOR_NONE,
                FailureParameters.FTCFailure.FAILURE_JOB,
                new DistributionGenerator[][]{{first, second, third}});

        double expectedFirstSample = first.getSamples()[0];
        FailureParameters.validateRuntimeCoverage(Arrays.asList(task(1), task(2)),
                Arrays.asList(Integer.valueOf(0), Integer.valueOf(1)));

        assertEquals(expectedFirstSample, first.getNextSample(), 0.0,
                "Coverage validation must not advance a generator cursor");
    }

    @Test
    void vmJobCoverageReportsTheExactMissingCoordinateBeforeDispatch() {
        FailureParameters.init(FailureParameters.FTCluteringAlgorithm.FTCLUSTERING_NOOP,
                FailureParameters.FTCMonitor.MONITOR_NONE,
                FailureParameters.FTCFailure.FAILURE_VM_JOB,
                new DistributionGenerator[][]{
                    {generator("coverage-vm-job-00"), generator("coverage-vm-job-01"),
                        generator("coverage-vm-job-02")},
                    {generator("coverage-vm-job-10"), generator("coverage-vm-job-11")}
                });

        IllegalStateException exception = assertThrows(IllegalStateException.class,
                () -> FailureParameters.validateRuntimeCoverage(Arrays.asList(task(1), task(2)),
                        Arrays.asList(Integer.valueOf(0), Integer.valueOf(1))));

        assertTrue(exception.getMessage().contains("before job dispatch"));
        assertTrue(exception.getMessage().contains("required=[vm=1, depth=2]"));
        assertTrue(exception.getMessage().contains("declaredVmIds=[0, 1]"));
        assertTrue(exception.getMessage().contains("observedDepths=[1, 2]"));
    }

    @Test
    void vmIdKeyedRowsSupportNonContiguousVmIdsAndRemainImmutable() {
        Map<Integer, DistributionSpec[]> rows = new LinkedHashMap<Integer, DistributionSpec[]>();
        rows.put(Integer.valueOf(20), new DistributionSpec[]{spec()});
        rows.put(Integer.valueOf(10), new DistributionSpec[]{spec()});
        FailureModelConfig model = FailureModelConfig.builder()
                .generatorMode(FailureParameters.FTCFailure.FAILURE_VM)
                .generatorSpecsByVmId(rows)
                .maxTotalRetryJobs(1)
                .build();
        rows.clear();

        try (SimulationSession ignored = SimulationSession.open(
                SimulationConfig.builder("fixture.dax", 2).failureModel(model).build())) {
            FailureParameters.validateRuntimeCoverage(Arrays.asList(task(1), task(2)),
                    Arrays.asList(Integer.valueOf(10), Integer.valueOf(20)));
            assertTrue(FailureParameters.getGeneratorForFailureScope(10, 2) != null);
            assertTrue(FailureParameters.getGeneratorForFailureScope(20, 1) != null);
            assertThrows(IllegalStateException.class, FailureParameters::getFailureGenerators);
        }
        assertEquals(2, model.getGeneratorSpecsByVmId().size());
    }

    @Test
    void keyedRowsAreRejectedForNonVmModesAndWhenMixedWithDenseRows() {
        Map<Integer, DistributionSpec[]> rows = new LinkedHashMap<Integer, DistributionSpec[]>();
        rows.put(Integer.valueOf(0), new DistributionSpec[]{spec()});
        assertThrows(IllegalArgumentException.class, () -> FailureModelConfig.builder()
                .generatorMode(FailureParameters.FTCFailure.FAILURE_ALL)
                .generatorSpecsByVmId(rows)
                .maxTotalRetryJobs(1)
                .build());
        assertThrows(IllegalArgumentException.class, () -> FailureModelConfig.builder()
                .generatorMode(FailureParameters.FTCFailure.FAILURE_VM)
                .generatorSpecs(new DistributionSpec[][]{{spec()}})
                .generatorSpecsByVmId(rows)
                .maxTotalRetryJobs(1)
                .build());
    }

    private static Task task(int depth) {
        Task task = new Task(depth, 1L);
        task.setDepth(depth);
        return task;
    }

    private static DistributionGenerator generator(String stream) {
        return new DistributionGenerator(DistributionGenerator.DistributionFamily.WEIBULL,
                10.0, 2.0, stream);
    }

    private static DistributionSpec spec() {
        return DistributionSpec.of(DistributionGenerator.DistributionFamily.WEIBULL, 1.0e9, 1.0);
    }
}
