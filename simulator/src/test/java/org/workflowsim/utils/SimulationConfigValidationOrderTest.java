package org.workflowsim.utils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Collections;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

/** Required-model validation must not depend on whether an arrival offset is nonzero. */
class SimulationConfigValidationOrderTest {
    private enum RequiredModel {
        OVERHEAD, CLUSTERING, SCHEDULER, PLANNER, FILE_SYSTEM, COST, FAILURE, DATA_MOVEMENT;

        void removeFrom(SimulationConfig.Builder builder) {
            switch (this) {
                case OVERHEAD: builder.overheadModel(null); break;
                case CLUSTERING: builder.clusteringParameters(null); break;
                case SCHEDULER: builder.schedulingAlgorithm(null); break;
                case PLANNER: builder.planningAlgorithm(null); break;
                case FILE_SYSTEM: builder.fileSystem(null); break;
                case COST: builder.costModel(null); break;
                case FAILURE: builder.failureModel(null); break;
                case DATA_MOVEMENT: builder.dataMovementModel(null); break;
                default: throw new AssertionError(this);
            }
        }
    }

    static Stream<Arguments> requiredModels() {
        return Stream.of(RequiredModel.values()).flatMap(model ->
                Stream.of(0.0, 1.0).map(arrival -> Arguments.of(model, arrival)));
    }

    @ParameterizedTest(name = "required {0}, arrival={1}")
    @MethodSource("requiredModels")
    void missingRequiredModelIsRejectedByThePublicConfigurationContract(RequiredModel model, double arrival) {
        SimulationConfig.Builder builder = SimulationConfig.builder("not-parsed.dax", 1)
                .workflowArrivalSeconds(Collections.singletonList(arrival));
        model.removeFrom(builder);
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, builder::build);
        assertTrue(error.getMessage().contains("required null value"), error.getMessage());
    }

    @ParameterizedTest(name = "valid NONE clustering, arrival={0}")
    @ValueSource(doubles = {0.0, 1.0})
    void validRequiredModelsStillAllowDefaultAndStaggeredArrival(double arrival) {
        SimulationConfig config = SimulationConfig.builder("not-parsed.dax", 1)
                .workflowArrivalSeconds(Collections.singletonList(arrival)).build();
        assertEquals(arrival, config.getWorkflowArrivalSeconds().get(0).doubleValue(), 0.0);
        assertEquals(ClusteringParameters.ClusteringMethod.NONE,
                config.getClusteringParameters().getClusteringMethod());
    }
}
