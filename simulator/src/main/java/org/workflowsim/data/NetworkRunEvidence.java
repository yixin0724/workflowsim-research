package org.workflowsim.data;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Immutable run-local observation of the existing grouped fluid model.
 * Only retained START bindings are exposed. COMPLETE means capture completeness,
 * not flow completion; TRUNCATED must never imply complete network statistics.
 * Aggregate input-reference observations, when supplied, do not imply per-file completion.
 * Derived metrics remain a separate operation on the frozen capture.
 */
public final class NetworkRunEvidence {
    private final NetworkEvidenceConfig config;
    private final DataMovementModel.Kind modelKind;
    private final TransferTraceSnapshot traceSnapshot;
    private final List<NetworkFlowBinding> bindings;
    private final boolean engineCreated;
    private final NetworkInputDemandSnapshot inputDemand;

    private NetworkRunEvidence(NetworkEvidenceConfig config, DataMovementModel.Kind modelKind,
            TransferTraceSnapshot trace, List<NetworkFlowBinding> bindings, boolean engineCreated,
            NetworkInputDemandSnapshot inputDemand) {
        this.config = config; this.modelKind = modelKind; this.traceSnapshot = trace;
        this.bindings = bindings.isEmpty() ? Collections.<NetworkFlowBinding>emptyList()
                : Collections.unmodifiableList(new ArrayList<NetworkFlowBinding>(bindings));
        this.engineCreated = engineCreated;
        this.inputDemand = inputDemand;
    }

    /**
     * Describe an enabled run that never created a fluid engine, without fabricating an advance.
     * @param config enabled recording option
     * @param modelKind existing supported fluid model
     * @return empty COMPLETE capture at engine time zero, with engineCreated false
     */
    public static NetworkRunEvidence empty(NetworkEvidenceConfig config, DataMovementModel.Kind modelKind) {
        requireEnabledContention(config, modelKind);
        return new NetworkRunEvidence(config, modelKind,
                new TransferTraceSnapshot(TransferTraceSnapshot.Status.COMPLETE,
                        Collections.<TransferTraceEvent>emptyList(), 0L, 0.0),
                Collections.<NetworkFlowBinding>emptyList(), false, NetworkInputDemandSnapshot.empty());
    }

    /**
     * Join frozen trace and bounded candidate metadata in O(records + candidates).
     * This method never accesses or advances a live engine.
     * @param config enabled option and retention budget
     * @param modelKind supported existing fluid model
     * @param trace deeply immutable NF001 snapshot
     * @param candidates at most budget early successful-admission bindings
     * @return immutable retained-START bindings in trace order
     * @throws IllegalArgumentException for invalid options or bounded metadata
     * @throws IllegalStateException for a missing/mismatched START binding or surplus complete-capture metadata
     */
    public static NetworkRunEvidence capture(NetworkEvidenceConfig config, DataMovementModel.Kind modelKind,
            TransferTraceSnapshot trace, List<NetworkFlowBinding> candidates) {
        return capture(config, modelKind, trace, candidates, null);
    }

    /**
     * Freeze trace/bindings together with optional actual input-reference observations.
     * @param config enabled recording option
     * @param modelKind supported data model
     * @param trace immutable engine snapshot
     * @param candidates bounded admission bindings
     * @param inputDemand immutable observations; null means unavailable, never inferred zero
     * @return immutable joined capture
     */
    public static NetworkRunEvidence capture(NetworkEvidenceConfig config, DataMovementModel.Kind modelKind,
            TransferTraceSnapshot trace, List<NetworkFlowBinding> candidates, NetworkInputDemandSnapshot inputDemand) {
        requireEnabledContention(config, modelKind);
        if (trace == null || trace.getStatus() == TransferTraceSnapshot.Status.DISABLED || candidates == null)
            throw new IllegalArgumentException("Enabled network evidence requires an enabled trace and candidate bindings");
        int budget = config.getMaxTraceRecords();
        if (trace.getEvents().size() > budget || candidates.size() > budget)
            throw new IllegalArgumentException("Network trace or candidate binding count exceeds the record budget");
        Map<Long, NetworkFlowBinding> byOrdinal = new LinkedHashMap<Long, NetworkFlowBinding>();
        for (NetworkFlowBinding binding : candidates) {
            if (binding == null || binding.getAdmissionOrdinal() > budget)
                throw new IllegalArgumentException("Network binding lies outside the bounded admission prefix");
            if (byOrdinal.put(binding.getAdmissionOrdinal(), binding) != null)
                throw new IllegalArgumentException("Duplicate network binding admission ordinal: " + binding.getAdmissionOrdinal());
        }
        List<NetworkFlowBinding> retained = new ArrayList<NetworkFlowBinding>();
        for (TransferTraceEvent event : trace.getEvents()) {
            if (event.getType() != TransferTraceEvent.Type.START) continue;
            NetworkFlowBinding binding = byOrdinal.remove(event.getAdmissionOrdinal());
            if (binding == null || binding.getExternalTransferId() != event.getTransferId().longValue()
                    || !binding.getOccupiedResources().equals(event.getStart().getOccupiedResources()))
                throw new IllegalStateException("Retained START admission " + event.getAdmissionOrdinal()
                        + " has no matching network flow binding (external ID and ordered resources must match)");
            retained.add(binding);
        }
        if (trace.getStatus() == TransferTraceSnapshot.Status.COMPLETE && !byOrdinal.isEmpty())
            throw new IllegalStateException("Complete network trace has candidate bindings without retained START records");
        return new NetworkRunEvidence(config, modelKind, trace, retained, true, inputDemand);
    }

    private static void requireEnabledContention(NetworkEvidenceConfig config, DataMovementModel.Kind kind) {
        if (config == null || !config.isEnabled()) throw new IllegalArgumentException("Network run evidence requires an enabled capture option");
        if (kind != DataMovementModel.Kind.PRE_EXECUTION_TRANSFER_DELAY_WITH_CONTENTION_V1
                && kind != DataMovementModel.Kind.PRE_EXECUTION_TRANSFER_DELAY_WITH_FAT_TREE_CONTENTION_V1)
            throw new IllegalArgumentException("Grouped network evidence requires an existing fluid contention model: " + kind);
    }
    /** @return immutable capture option */ public NetworkEvidenceConfig getConfig() { return config; }
    /** @return existing model kind */ public DataMovementModel.Kind getModelKind() { return modelKind; }
    /** @return immutable NF001 snapshot with original coverage */ public TransferTraceSnapshot getTraceSnapshot() { return traceSnapshot; }
    /** @return immutable retained START bindings */ public List<NetworkFlowBinding> getBindings() { return bindings; }
    /** @return whether a real run-local fluid engine was created */ public boolean isEngineCreated() { return engineCreated; }
    /** @return actual immutable grouped-input observations, or null if not supplied */
    public NetworkInputDemandSnapshot getInputDemand() { return inputDemand; }
}
