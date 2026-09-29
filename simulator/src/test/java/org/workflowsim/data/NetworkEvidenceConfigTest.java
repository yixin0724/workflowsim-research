package org.workflowsim.data;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.workflowsim.experiment.ExperimentManifestWriter;
import org.workflowsim.failure.FailureModelConfig;
import org.workflowsim.failure.FailureParameters;
import org.workflowsim.utils.ClusteringParameters;
import org.workflowsim.utils.DistributionGenerator;
import org.workflowsim.utils.DistributionSpec;
import org.workflowsim.utils.OverheadModelConfig;
import org.workflowsim.utils.Parameters;
import org.workflowsim.utils.ReplicaCatalog;
import org.workflowsim.utils.SimulationConfig;

/** NF002A: Java opt-in only; no new physics or model support policy. */
class NetworkEvidenceConfigTest {
    @Test
    void offIsASingletonAndEveryDefaultConfigurationUsesIt() {
        NetworkEvidenceConfig off = NetworkEvidenceConfig.off();
        assertSame(off, NetworkEvidenceConfig.off());
        assertEquals(NetworkEvidenceConfig.Mode.OFF, off.getMode());
        assertEquals(0, off.getMaxTraceRecords());
        assertFalse(off.isEnabled());
        assertSame(off, SimulationConfig.builder("unused.dax", 1).build().getNetworkEvidenceConfig());
        assertSame(off, configured(DataMovementModel.preExecutionTransferDelayWithContentionV1())
                .build().getNetworkEvidenceConfig());
    }

    @Test
    void fluidGroupLedgerRequiresAStrictlyPositiveRecordBudget() {
        for (int budget : Arrays.asList(1, 17, Integer.MAX_VALUE)) {
            NetworkEvidenceConfig option = NetworkEvidenceConfig.fluidGroupLedger(budget);
            assertEquals(NetworkEvidenceConfig.Mode.FLUID_GROUP_LEDGER_V1, option.getMode());
            assertEquals(budget, option.getMaxTraceRecords());
            assertTrue(option.isEnabled());
        }
        for (int budget : Arrays.asList(0, -1, Integer.MIN_VALUE)) {
            IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                    () -> NetworkEvidenceConfig.fluidGroupLedger(budget));
            assertTrue(failure.getMessage().toLowerCase(java.util.Locale.ROOT).contains("positive"));
        }
    }

    @Test
    void toBuilderPreservesTheImmutableOptInAndAllOtherConfigurationValues() {
        NetworkEvidenceConfig option = NetworkEvidenceConfig.fluidGroupLedger(31);
        SimulationConfig original = configured(DataMovementModel.preExecutionTransferDelayWithContentionV1())
                .networkEvidence(option).randomSeed(17L).deadline(71L)
                .runtimeScale(1.25).runtimeReferenceMips(500.0)
                .cloudSimMinEventIntervalSeconds(0.25).build();
        SimulationConfig copy = original.toBuilder().build();
        assertNotSame(original, copy);
        assertSame(option, copy.getNetworkEvidenceConfig());
        assertEquals(jsonConfiguration(original), jsonConfiguration(copy));
        SimulationConfig disabled = original.toBuilder().networkEvidence(NetworkEvidenceConfig.off()).build();
        assertFalse(disabled.getNetworkEvidenceConfig().isEnabled());
        assertSame(option, original.getNetworkEvidenceConfig());
    }

    @Test
    void withRandomSeedPreservesEnabledAndDisabledEvidenceOptions() {
        SimulationConfig enabled = configured(DataMovementModel.fatTreeContentionV1())
                .networkEvidence(NetworkEvidenceConfig.fluidGroupLedger(101)).randomSeed(2L).build();
        SimulationConfig copy = enabled.withRandomSeed(99L);
        assertSame(enabled.getNetworkEvidenceConfig(), copy.getNetworkEvidenceConfig());
        JsonObject before = jsonConfiguration(enabled);
        JsonObject after = jsonConfiguration(copy);
        assertEquals(2L, before.remove("rootSeed").getAsLong());
        assertEquals(99L, after.remove("rootSeed").getAsLong());
        assertEquals(before, after);
        SimulationConfig off = SimulationConfig.builder("unused.dax", 1).build().withRandomSeed(9L);
        assertSame(NetworkEvidenceConfig.off(), off.getNetworkEvidenceConfig());
    }

    @Test
    void nullEvidenceIsRejectedBeforeCrossModelDereferences() {
        assertThrows(IllegalArgumentException.class, () -> configured(
                DataMovementModel.preExecutionTransferDelayWithContentionV1()).networkEvidence(null).build());
        assertThrows(IllegalArgumentException.class, () -> configured(null)
                .networkEvidence(NetworkEvidenceConfig.fluidGroupLedger(1)).build());
    }

    @Test
    void enabledEvidenceAcceptsTheTwoExistingFluidContentionKinds() {
        for (DataMovementModel model : Arrays.asList(
                DataMovementModel.preExecutionTransferDelayWithContentionV1(), DataMovementModel.fatTreeContentionV1())) {
            SimulationConfig config = configured(model)
                    .networkEvidence(NetworkEvidenceConfig.fluidGroupLedger(20)).build();
            assertEquals(model.getKind(), config.getDataMovementModel().getKind());
            assertTrue(config.getNetworkEvidenceConfig().isEnabled());
        }
    }

    @Test
    void enabledEvidenceClearlyRejectsLegacyFixedAndNoContentionModels() {
        for (DataMovementModel model : Arrays.asList(DataMovementModel.legacyWorkflowsimV1(),
                DataMovementModel.fixedEndpointNoContention(1.0, 0.0, 1.0), DataMovementModel.preExecutionTransferDelayV1())) {
            IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                    () -> configured(model).networkEvidence(NetworkEvidenceConfig.fluidGroupLedger(20)).build());
            assertTrue(failure.getMessage().contains("network"), failure.getMessage());
            assertTrue(failure.getMessage().contains(model.getKind().name()), failure.getMessage());
        }
    }

    @Test
    void offAddsNoRestrictionsToAnyOfTheFiveExistingModelKinds() {
        for (DataMovementModel model : Arrays.asList(DataMovementModel.legacyWorkflowsimV1(),
                DataMovementModel.fixedEndpointNoContention(1.0, 0.0, 1.0), DataMovementModel.preExecutionTransferDelayV1(),
                DataMovementModel.preExecutionTransferDelayWithContentionV1(), DataMovementModel.fatTreeContentionV1())) {
            SimulationConfig config = configured(model).networkEvidence(NetworkEvidenceConfig.off()).build();
            assertFalse(config.getNetworkEvidenceConfig().isEnabled());
            assertFalse(jsonConfiguration(config).has("networkEvidence"), model.getKind().name());
        }
    }

    @Test
    void offSnapshotOmitsTheKeyEvenWhenNullsAreSerialized() {
        SimulationConfig config = configured(DataMovementModel.preExecutionTransferDelayWithContentionV1()).build();
        assertFalse(ExperimentManifestWriter.configurationSnapshot(config).containsKey("networkEvidence"));
        assertFalse(jsonConfiguration(config).has("networkEvidence"));
        assertEquals(jsonConfiguration(config), jsonConfiguration(config.toBuilder()
                .networkEvidence(NetworkEvidenceConfig.off()).build()));
    }

    @Test
    void onSnapshotAddsOnlyModeAndBudgetAndIsAFreshTransportMap() {
        SimulationConfig off = configured(DataMovementModel.preExecutionTransferDelayWithContentionV1()).build();
        SimulationConfig on = off.toBuilder().networkEvidence(NetworkEvidenceConfig.fluidGroupLedger(37)).build();
        JsonObject onJson = jsonConfiguration(on);
        JsonObject evidence = onJson.remove("networkEvidence").getAsJsonObject();
        assertEquals(2, evidence.size());
        assertEquals("FLUID_GROUP_LEDGER_V1", evidence.get("mode").getAsString());
        assertEquals(37, evidence.get("maxTraceRecords").getAsInt());
        assertEquals(jsonConfiguration(off), onJson, "No old configuration field may change");
        Map<String, Object> first = ExperimentManifestWriter.configurationSnapshot(on);
        @SuppressWarnings("unchecked")
        Map<String, Object> mutableOption = (Map<String, Object>) first.get("networkEvidence");
        mutableOption.put("maxTraceRecords", 1);
        assertEquals(37, on.getNetworkEvidenceConfig().getMaxTraceRecords());
        assertEquals(37, jsonConfiguration(on).getAsJsonObject("networkEvidence").get("maxTraceRecords").getAsInt());
    }

    @Test
    void endpointOptInDoesNotInventFailureOrOverheadRestrictionsForRandomPlanning() {
        SimulationConfig config = configured(DataMovementModel.preExecutionTransferDelayWithContentionV1())
                .networkEvidence(NetworkEvidenceConfig.fluidGroupLedger(40)).failureModel(enabledFailure())
                .overheadModel(OverheadModelConfig.builder().bandwidth(1.0).build()).build();
        assertTrue(config.getFailureModel().isEnabled());
        assertTrue(config.getNetworkEvidenceConfig().isEnabled());
    }

    @Test
    void optInDoesNotRelaxExistingFatTreeAndStaticMappingRestrictions() {
        NetworkEvidenceConfig option = NetworkEvidenceConfig.fluidGroupLedger(40);
        assertThrows(IllegalArgumentException.class, () -> SimulationConfig.builder("unused.dax", 1)
                .dataMovementModel(DataMovementModel.preExecutionTransferDelayWithContentionV1()).networkEvidence(option).build());
        assertThrows(IllegalArgumentException.class, () -> configured(DataMovementModel.fatTreeContentionV1())
                .networkEvidence(option).fileSystem(ReplicaCatalog.FileSystem.SHARED).build());
        assertThrows(IllegalArgumentException.class, () -> configured(DataMovementModel.fatTreeContentionV1())
                .networkEvidence(option).failureModel(enabledFailure()).build());
        assertThrows(IllegalArgumentException.class, () -> configured(DataMovementModel.fatTreeContentionV1())
                .networkEvidence(option).overheadModel(OverheadModelConfig.builder().bandwidth(1.0).build()).build());
        assertThrows(IllegalArgumentException.class, () -> configured(DataMovementModel.fatTreeContentionV1())
                .networkEvidence(option).clusteringParameters(new ClusteringParameters(2, 0,
                        ClusteringParameters.ClusteringMethod.HORIZONTAL, null)).build());
    }

    private static SimulationConfig.Builder configured(DataMovementModel model) {
        return SimulationConfig.builder("unused.dax", 1).planningAlgorithm(Parameters.PlanningAlgorithm.RANDOM)
                .schedulingAlgorithm(Parameters.SchedulingAlgorithm.STATIC)
                .fileSystem(ReplicaCatalog.FileSystem.LOCAL).dataMovementModel(model);
    }

    private static JsonObject jsonConfiguration(SimulationConfig config) {
        return new GsonBuilder().serializeNulls().create()
                .toJsonTree(ExperimentManifestWriter.configurationSnapshot(config)).getAsJsonObject();
    }

    private static FailureModelConfig enabledFailure() {
        Map<Integer, DistributionSpec[]> rows = new LinkedHashMap<Integer, DistributionSpec[]>();
        rows.put(0, new DistributionSpec[]{DistributionSpec.of(
                DistributionGenerator.DistributionFamily.WEIBULL, 1.0e9, 1.0)});
        return FailureModelConfig.builder().generatorMode(FailureParameters.FTCFailure.FAILURE_VM)
                .generatorSpecsByVmId(rows).maxTotalRetryJobs(1).build();
    }
}
