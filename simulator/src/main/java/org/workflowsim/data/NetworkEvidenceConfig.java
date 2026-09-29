package org.workflowsim.data;

/** Immutable, explicitly opt-in recording policy; it does not change data movement physics. */
public final class NetworkEvidenceConfig {
    /** The versioned observation contract, separate from the transfer model. */
    public enum Mode {
        /** Preserve legacy wire output with no network evidence member or history. */
        OFF,
        /** Observe the existing parent/external-group fluid models. */
        FLUID_GROUP_LEDGER_V1
    }

    private static final NetworkEvidenceConfig OFF = new NetworkEvidenceConfig(Mode.OFF, 0);
    private final Mode mode;
    private final int maxTraceRecords;

    private NetworkEvidenceConfig(Mode mode, int maxTraceRecords) {
        this.mode = mode;
        this.maxTraceRecords = maxTraceRecords;
    }

    /** @return the shared history-free recording policy */
    public static NetworkEvidenceConfig off() { return OFF; }

    /**
     * Enable a bounded prefix of group-flow observations. The bound counts records,
     * not bytes, and truncated capture must never be reported as complete network data.
     *
     * @param maxTraceRecords positive retained-record budget
     * @return a new immutable observation policy
     * @throws IllegalArgumentException for a non-positive budget
     */
    public static NetworkEvidenceConfig fluidGroupLedger(int maxTraceRecords) {
        if (maxTraceRecords <= 0) {
            throw new IllegalArgumentException("Network trace record budget must be positive");
        }
        return new NetworkEvidenceConfig(Mode.FLUID_GROUP_LEDGER_V1, maxTraceRecords);
    }

    /** @return versioned recording mode */
    public Mode getMode() { return mode; }
    /** @return positive enabled budget, or zero when OFF */
    public int getMaxTraceRecords() { return maxTraceRecords; }
    /** @return whether group-flow recording is explicitly enabled */
    public boolean isEnabled() { return mode != Mode.OFF; }
}
