package org.workflowsim.data;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.Gson;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;

/** NF002A immutable group bindings and bounded run-level snapshots, independent of CloudSim. */
class NetworkRunEvidenceTest {
    private static final DataMovementModel.Kind ENDPOINT =
            DataMovementModel.Kind.PRE_EXECUTION_TRANSFER_DELAY_WITH_CONTENTION_V1;
    private static final List<String> RESOURCES = Arrays.asList("VM:7", "VM:42");

    @Test
    void bindingCopiesTaskIdsAndPreservesOrderedDuplicateResources() {
        List<Integer> taskIds = new ArrayList<Integer>(Arrays.asList(3, 6));
        List<String> resources = new ArrayList<String>(Arrays.asList("VM:7", "VM:42", "VM:7"));
        NetworkFlowBinding binding = NetworkFlowBinding.of(9001L, 2L, 91, taskIds, 11,
                NetworkFlowBinding.GroupKind.PARENT_GROUP_V1, "VM:7", "VM:42", resources);
        taskIds.set(0, 99);
        resources.clear();
        assertEquals(9001L, binding.getExternalTransferId());
        assertEquals(2L, binding.getAdmissionOrdinal());
        assertEquals(91, binding.getJobId());
        assertEquals(Arrays.asList(3, 6), binding.getTaskIds());
        assertEquals(Integer.valueOf(11), binding.getParentJobId());
        assertEquals(NetworkFlowBinding.GroupKind.PARENT_GROUP_V1, binding.getGroupKind());
        assertEquals(NetworkFlowBinding.SourceScope.MODELED_CONSTRAINED_SOURCE, binding.getSourceScope());
        assertEquals("VM:7", binding.getSourceEndpoint());
        assertEquals("VM:42", binding.getDestinationEndpoint());
        assertEquals(Arrays.asList("VM:7", "VM:42", "VM:7"), binding.getOccupiedResources());
        assertThrows(UnsupportedOperationException.class, () -> binding.getTaskIds().add(8));
        assertThrows(UnsupportedOperationException.class, () -> binding.getOccupiedResources().clear());
    }

    @Test
    void externalBindingAllowsANullParentAndDestinationOnlyConstrainedResources() {
        NetworkFlowBinding binding = NetworkFlowBinding.of(-5L, 1L, 0, Collections.singletonList(1), null,
                NetworkFlowBinding.GroupKind.EXTERNAL_GROUP_V1, "SOURCE", "VM:42", Collections.singletonList("VM:42"));
        assertEquals(-5L, binding.getExternalTransferId(), "Do not impose a new external-ID policy");
        assertNull(binding.getParentJobId());
        assertEquals("SOURCE", binding.getSourceEndpoint());
        assertEquals(Collections.singletonList("VM:42"), binding.getOccupiedResources());
        assertEquals(NetworkFlowBinding.SourceScope.MODELED_CONSTRAINED_SOURCE, binding.getSourceScope());
    }

    @Test
    void bindingRejectsMissingRequiredValuesAndInconsistentGroupParents() {
        assertThrows(IllegalArgumentException.class, () -> binding(7L, 0L, RESOURCES));
        assertThrows(IllegalArgumentException.class, () -> NetworkFlowBinding.of(7L, 1L, 91, null, 11,
                NetworkFlowBinding.GroupKind.PARENT_GROUP_V1, "VM:7", "VM:42", RESOURCES));
        assertThrows(IllegalArgumentException.class, () -> NetworkFlowBinding.of(7L, 1L, 91,
                Arrays.asList(1, null), 11, NetworkFlowBinding.GroupKind.PARENT_GROUP_V1,
                "VM:7", "VM:42", RESOURCES));
        assertThrows(IllegalArgumentException.class, () -> binding(7L, 1L, null));
        assertThrows(IllegalArgumentException.class, () -> binding(7L, 1L, Arrays.asList("VM:7", null)));
        assertThrows(IllegalArgumentException.class, () -> NetworkFlowBinding.of(7L, 1L, 91,
                Collections.singletonList(1), 11, null, "VM:7", "VM:42", RESOURCES));
        assertThrows(IllegalArgumentException.class, () -> NetworkFlowBinding.of(7L, 1L, 91,
                Collections.singletonList(1), 11, NetworkFlowBinding.GroupKind.PARENT_GROUP_V1,
                null, "VM:42", RESOURCES));
        assertThrows(IllegalArgumentException.class, () -> NetworkFlowBinding.of(7L, 1L, 91,
                Collections.singletonList(1), 11, NetworkFlowBinding.GroupKind.PARENT_GROUP_V1,
                "VM:7", null, RESOURCES));
        assertThrows(IllegalArgumentException.class, () -> NetworkFlowBinding.of(7L, 1L, 91,
                Collections.singletonList(1), null, NetworkFlowBinding.GroupKind.PARENT_GROUP_V1,
                "VM:7", "VM:42", RESOURCES));
        assertThrows(IllegalArgumentException.class, () -> NetworkFlowBinding.of(7L, 1L, 91,
                Collections.singletonList(1), 11, NetworkFlowBinding.GroupKind.EXTERNAL_GROUP_V1,
                "SOURCE", "VM:42", RESOURCES));
    }

    @Test
    void emptyEnabledEvidenceIsCompleteAtZeroWithoutAnEngine() {
        NetworkEvidenceConfig option = NetworkEvidenceConfig.fluidGroupLedger(7);
        for (DataMovementModel.Kind kind : Arrays.asList(ENDPOINT,
                DataMovementModel.Kind.PRE_EXECUTION_TRANSFER_DELAY_WITH_FAT_TREE_CONTENTION_V1)) {
            NetworkRunEvidence evidence = NetworkRunEvidence.empty(option, kind);
            assertSame(option, evidence.getConfig());
            assertEquals(kind, evidence.getModelKind());
            assertFalse(evidence.isEngineCreated());
            assertEquals(TransferTraceSnapshot.Status.COMPLETE, evidence.getTraceSnapshot().getStatus());
            assertEquals(0.0, evidence.getTraceSnapshot().getEngineTime(), 0.0);
            assertEquals(0L, evidence.getTraceSnapshot().getDroppedCount());
            assertTrue(evidence.getTraceSnapshot().getEvents().isEmpty());
            assertTrue(evidence.getBindings().isEmpty());
            assertThrows(UnsupportedOperationException.class,
                    () -> evidence.getBindings().add(binding(1L, 1L, RESOURCES)));
        }
    }

    @Test
    void snapshotRequiresAnEnabledOptionSupportedKindAndEnabledTrace() {
        NetworkEvidenceConfig option = NetworkEvidenceConfig.fluidGroupLedger(10);
        TransferTraceSnapshot trace = new TransferContentionEngine(10).getTraceSnapshot();
        List<NetworkFlowBinding> none = Collections.emptyList();
        assertThrows(IllegalArgumentException.class, () -> NetworkRunEvidence.empty(null, ENDPOINT));
        assertThrows(IllegalArgumentException.class, () -> NetworkRunEvidence.empty(option, null));
        assertThrows(IllegalArgumentException.class,
                () -> NetworkRunEvidence.empty(NetworkEvidenceConfig.off(), ENDPOINT));
        assertThrows(IllegalArgumentException.class, () -> NetworkRunEvidence.empty(option,
                DataMovementModel.Kind.LEGACY_WORKFLOWSIM_V1));
        assertThrows(IllegalArgumentException.class,
                () -> NetworkRunEvidence.capture(NetworkEvidenceConfig.off(), ENDPOINT, trace, none));
        assertThrows(IllegalArgumentException.class, () -> NetworkRunEvidence.capture(option,
                DataMovementModel.Kind.PRE_EXECUTION_TRANSFER_DELAY_V1, trace, none));
        assertThrows(IllegalArgumentException.class,
                () -> NetworkRunEvidence.capture(option, ENDPOINT, null, none));
        assertThrows(IllegalArgumentException.class, () -> NetworkRunEvidence.capture(option,
                ENDPOINT, new TransferContentionEngine().getTraceSnapshot(), none));
        assertThrows(IllegalArgumentException.class,
                () -> NetworkRunEvidence.capture(option, ENDPOINT, trace, null));
    }

    @Test
    void truncatedCaptureExportsOnlyBindingsForTheRetainedStartPrefix() {
        TransferContentionEngine engine = new TransferContentionEngine(4);
        engine.setEndpointCapacity("VM:7", 100.0);
        engine.addTransfer(77L, 100.0, RESOURCES, 100.0, 0.0);
        engine.advance(1.0);
        engine.addTransfer(9001L, 100.0, RESOURCES, 100.0, 1.0);
        NetworkFlowBinding first = binding(77L, 1L, RESOURCES);
        NetworkFlowBinding unretained = binding(9001L, 2L, RESOURCES);
        TransferTraceSnapshot trace = engine.getTraceSnapshot();
        NetworkRunEvidence evidence = NetworkRunEvidence.capture(NetworkEvidenceConfig.fluidGroupLedger(4),
                ENDPOINT, trace, Arrays.asList(first, unretained));
        assertSame(trace, evidence.getTraceSnapshot(), "The NF001 snapshot is already deeply immutable");
        assertTrue(evidence.isEngineCreated());
        assertEquals(TransferTraceSnapshot.Status.TRUNCATED, trace.getStatus());
        assertEquals(1L, trace.getDroppedCount());
        assertEquals(Collections.singletonList(first), evidence.getBindings());
        assertEquals(77L, evidence.getBindings().get(0).getExternalTransferId());
        assertEquals(1L, evidence.getBindings().get(0).getAdmissionOrdinal());
    }

    @Test
    void completeTraceCannotBeReportedWithAMissingRetainedStartBinding() {
        TransferContentionEngine engine = new TransferContentionEngine(10);
        engine.addTransfer(77L, 100.0, RESOURCES, 100.0, 0.0);
        assertEquals(TransferTraceSnapshot.Status.COMPLETE, engine.getTraceSnapshot().getStatus());
        assertThrows(IllegalStateException.class, () -> NetworkRunEvidence.capture(
                NetworkEvidenceConfig.fluidGroupLedger(10), ENDPOINT, engine.getTraceSnapshot(), Collections.<NetworkFlowBinding>emptyList()));
    }

    @Test
    void completeCaptureCannotSilentlyDiscardCandidateBindingsWithNoStart() {
        TransferTraceSnapshot trace = new TransferContentionEngine(10).getTraceSnapshot();
        assertThrows(IllegalStateException.class, () -> NetworkRunEvidence.capture(
                NetworkEvidenceConfig.fluidGroupLedger(10), ENDPOINT, trace,
                Collections.singletonList(binding(77L, 1L, RESOURCES))));
    }

    @Test
    void truncatedTraceAlsoRequiresEveryRetainedStartToBeBound() {
        TransferContentionEngine engine = new TransferContentionEngine(1);
        engine.addTransfer(77L, 100.0, RESOURCES, 100.0, 0.0);
        engine.advance(1.0);
        assertEquals(TransferTraceSnapshot.Status.TRUNCATED, engine.getTraceSnapshot().getStatus());
        assertThrows(IllegalStateException.class, () -> NetworkRunEvidence.capture(
                NetworkEvidenceConfig.fluidGroupLedger(1), ENDPOINT, engine.getTraceSnapshot(), Collections.<NetworkFlowBinding>emptyList()));
    }

    @Test
    void retainedStartJoinChecksExternalIdOrdinalAndExactResourceList() {
        List<String> repeated = Arrays.asList("VM:7", "VM:42", "VM:7");
        TransferContentionEngine engine = new TransferContentionEngine(10);
        engine.addTransfer(77L, 100.0, repeated, 100.0, 0.0);
        for (NetworkFlowBinding wrong : Arrays.asList(binding(78L, 1L, repeated), binding(77L, 2L, repeated),
                binding(77L, 1L, RESOURCES), binding(77L, 1L, Arrays.asList("VM:7", "VM:7", "VM:42")))) {
            assertThrows(IllegalStateException.class, () -> NetworkRunEvidence.capture(
                    NetworkEvidenceConfig.fluidGroupLedger(10), ENDPOINT, engine.getTraceSnapshot(), Collections.singletonList(wrong)));
        }
        NetworkRunEvidence valid = NetworkRunEvidence.capture(NetworkEvidenceConfig.fluidGroupLedger(10),
                ENDPOINT, engine.getTraceSnapshot(), Collections.singletonList(binding(77L, 1L, repeated)));
        assertEquals(repeated, valid.getBindings().get(0).getOccupiedResources());
    }

    @Test
    void snapshotOutlivesMutableCandidatesAndLaterEngineActivity() {
        TransferContentionEngine engine = new TransferContentionEngine(20);
        engine.addTransfer(77L, 100.0, RESOURCES, 100.0, 0.0);
        List<NetworkFlowBinding> candidates = new ArrayList<NetworkFlowBinding>();
        candidates.add(binding(77L, 1L, RESOURCES));
        NetworkRunEvidence frozen = NetworkRunEvidence.capture(NetworkEvidenceConfig.fluidGroupLedger(20),
                ENDPOINT, engine.getTraceSnapshot(), candidates);
        String before = new Gson().toJson(frozen);
        candidates.clear();
        engine.advance(1.0);
        engine.addTransfer(77L, 100.0, RESOURCES, 100.0, 1.0);
        assertEquals(before, new Gson().toJson(frozen));
        assertEquals(0.0, frozen.getTraceSnapshot().getEngineTime(), 0.0);
        assertEquals(TransferTraceSnapshot.Status.COMPLETE, frozen.getTraceSnapshot().getStatus(),
                "COMPLETE describes capture, not whether active flows have finished");
        assertThrows(UnsupportedOperationException.class, () -> frozen.getBindings().clear());
        assertThrows(UnsupportedOperationException.class, () -> frozen.getTraceSnapshot().getEvents().clear());
    }

    @Test
    void reusedExternalIdsJoinByOrdinalAndExportInTraceNotCandidateOrder() {
        TransferContentionEngine engine = new TransferContentionEngine(20);
        engine.addTransfer(77L, 100.0, RESOURCES, 100.0, 0.0);
        engine.advance(1.0);
        engine.addTransfer(77L, 100.0, RESOURCES, 100.0, 1.0);
        NetworkFlowBinding first = binding(77L, 1L, RESOURCES);
        NetworkFlowBinding second = NetworkFlowBinding.of(77L, 2L, 92, Collections.singletonList(5), 11,
                NetworkFlowBinding.GroupKind.PARENT_GROUP_V1, "VM:7", "VM:42", RESOURCES);
        NetworkRunEvidence evidence = NetworkRunEvidence.capture(NetworkEvidenceConfig.fluidGroupLedger(20),
                ENDPOINT, engine.getTraceSnapshot(), Arrays.asList(second, first));
        assertEquals(Arrays.asList(first, second), evidence.getBindings());
        assertEquals(91, evidence.getBindings().get(0).getJobId());
        assertEquals(92, evidence.getBindings().get(1).getJobId());
    }

    @Test
    void snapshotRejectsOversizedOrDuplicateCandidateMetadataAndOversizedTrace() {
        TransferTraceSnapshot empty = new TransferContentionEngine(1).getTraceSnapshot();
        NetworkFlowBinding first = binding(77L, 1L, RESOURCES);
        NetworkFlowBinding second = binding(88L, 2L, RESOURCES);
        assertThrows(IllegalArgumentException.class, () -> NetworkRunEvidence.capture(
                NetworkEvidenceConfig.fluidGroupLedger(1), ENDPOINT, empty, Arrays.asList(first, second)));
        assertThrows(IllegalArgumentException.class, () -> NetworkRunEvidence.capture(
                NetworkEvidenceConfig.fluidGroupLedger(1), ENDPOINT, empty, Collections.singletonList(second)));
        assertThrows(IllegalArgumentException.class, () -> NetworkRunEvidence.capture(
                NetworkEvidenceConfig.fluidGroupLedger(10), ENDPOINT, empty, Arrays.asList(first, first)));
        assertThrows(IllegalArgumentException.class, () -> NetworkRunEvidence.capture(
                NetworkEvidenceConfig.fluidGroupLedger(10), ENDPOINT, empty, Collections.<NetworkFlowBinding>singletonList(null)));
        TransferContentionEngine engine = new TransferContentionEngine(10);
        engine.setEndpointCapacity("A", 1.0);
        engine.setEndpointCapacity("B", 1.0);
        assertThrows(IllegalArgumentException.class, () -> NetworkRunEvidence.capture(
                NetworkEvidenceConfig.fluidGroupLedger(1), ENDPOINT, engine.getTraceSnapshot(), Collections.<NetworkFlowBinding>emptyList()));
    }

    private static NetworkFlowBinding binding(long externalId, long ordinal, List<String> resources) {
        return NetworkFlowBinding.of(externalId, ordinal, 91, Collections.singletonList(5), 11,
                NetworkFlowBinding.GroupKind.PARENT_GROUP_V1, "VM:7", "VM:42", resources);
    }
}
