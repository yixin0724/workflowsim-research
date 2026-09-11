package org.workflowsim.failure;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.workflowsim.utils.DistributionGenerator;
import org.workflowsim.utils.DistributionSpec;
import org.workflowsim.utils.SimulationConfig;
import org.workflowsim.utils.SimulationSession;

class FailureModelConfigTest {

    @AfterEach
    void resetFailureState() {
        FailureParameters.reset();
        FailureMonitor.reset();
    }

    @Test
    void sessionRecreatesFailureGeneratorsFromTheRunSeed() {
        FailureModelConfig model = FailureModelConfig.builder()
                .monitorMode(FailureParameters.FTCMonitor.MONITOR_ALL)
                .generatorMode(FailureParameters.FTCFailure.FAILURE_ALL)
                .generatorSpecs(new DistributionSpec[][]{{
                    DistributionSpec.of(
                            DistributionGenerator.DistributionFamily.WEIBULL, 10.0, 2.0)
                }})
                .maxTotalRetryJobs(3)
                .build();
        SimulationConfig config = SimulationConfig.builder("fixture.dax", 1)
                .randomSeed(20260901L)
                .failureModel(model)
                .build();

        // 同一运行种子应使每次新会话的首个失效到达样本完全一致。
        double first;
        try (SimulationSession ignored = SimulationSession.open(config)) {
            assertEquals(3, FailureParameters.getMaxTotalRetryJobs());
            first = FailureParameters.getGenerator(0, 0).getNextSample();
        }
        try (SimulationSession ignored = SimulationSession.open(config)) {
            assertEquals(3, FailureParameters.getMaxTotalRetryJobs());
            assertEquals(first, FailureParameters.getGenerator(0, 0).getNextSample(), 0.0);
        }
    }

    @Test
    void rejectsIncompleteOrMismatchedFailureConfiguration() {
        assertThrows(IllegalArgumentException.class, () -> FailureModelConfig.builder()
                .generatorMode(FailureParameters.FTCFailure.FAILURE_ALL)
                .build());
        assertThrows(IllegalArgumentException.class, () -> FailureModelConfig.builder()
                .generatorMode(FailureParameters.FTCFailure.FAILURE_ALL)
                .generatorSpecs(new DistributionSpec[][]{{null}})
                .build());
        assertThrows(IllegalArgumentException.class, () -> FailureModelConfig.builder()
                .distributionFamily(DistributionGenerator.DistributionFamily.GAMMA)
                .generatorMode(FailureParameters.FTCFailure.FAILURE_ALL)
                .generatorSpecs(new DistributionSpec[][]{{DistributionSpec.of(
                    DistributionGenerator.DistributionFamily.WEIBULL, 10.0, 2.0)}})
                .build());
        assertThrows(IllegalArgumentException.class, () -> FailureModelConfig.builder()
                .distributionFamily(DistributionGenerator.DistributionFamily.NORMAL)
                .generatorMode(FailureParameters.FTCFailure.FAILURE_ALL)
                .generatorSpecs(new DistributionSpec[][]{{DistributionSpec.of(
                    DistributionGenerator.DistributionFamily.NORMAL, 10.0, 2.0)}})
                .build());
        assertThrows(IllegalArgumentException.class, () -> FailureModelConfig.builder()
                .generatorMode(FailureParameters.FTCFailure.FAILURE_ALL)
                .generatorSpecs(new DistributionSpec[][]{{DistributionSpec.of(
                    DistributionGenerator.DistributionFamily.WEIBULL, 10.0, 2.0)}})
                .build());
        assertThrows(IllegalArgumentException.class, () -> FailureModelConfig.builder()
                .generatorMode(FailureParameters.FTCFailure.FAILURE_ALL)
                .generatorSpecs(new DistributionSpec[][]{{DistributionSpec.of(
                    DistributionGenerator.DistributionFamily.WEIBULL, 10.0, 2.0)}})
                .maxTotalRetryJobs(0)
                .build());

        FailureModelConfig model = FailureModelConfig.builder()
                .generatorMode(FailureParameters.FTCFailure.FAILURE_ALL)
                .generatorSpecs(new DistributionSpec[][]{{
                    DistributionSpec.of(
                            DistributionGenerator.DistributionFamily.WEIBULL, 10.0, 2.0)
                }})
                .maxTotalRetryJobs(1)
                .build();
        try (SimulationSession ignored = SimulationSession.open(
                SimulationConfig.builder("fixture.dax", 1).failureModel(model).build())) {
            assertThrows(IllegalStateException.class, () -> FailureParameters.getGenerator(1, 0));
        }
    }

    @Test
    void distributionValidationRejectsInvalidParametersAndEmptyMean() {
        assertThrows(IllegalArgumentException.class, () -> new DistributionGenerator(
                DistributionGenerator.DistributionFamily.WEIBULL, 0.0, 1.0));
        DistributionGenerator generator = new DistributionGenerator(
                DistributionGenerator.DistributionFamily.WEIBULL, 1.0, 1.0);
        assertThrows(IllegalStateException.class, generator::getMean);
    }
}
