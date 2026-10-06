package org.workflowsim.data;

/** Immutable, explicitly opt-in recording policy; it does not change data movement physics. */
public final class NetworkEvidenceConfig {
    /** The versioned observation contract, separate from the transfer model. */
    public enum Mode {
        /** Preserve legacy wire output with no network evidence member or history. */
        OFF,
        /** Observe the existing parent/external-group fluid models. */
        FLUID_GROUP_LEDGER_V1,
        /** Observe the dedicated per-file publication/request/settlement lifecycle, not V1 groups. */
        FILE_LIFECYCLE_V2,
        /** Bounded storage reads, successful-output materialization and input waits. */
        FILE_STORAGE_LIFECYCLE_V3
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

    /**
     * Enable bounded V2 lifecycle capture. The first export contract requires a complete capture;
     * this is not a fluid service/rate accounting certificate.
     * @param maxTraceRecords positive lifecycle-record budget
     * @return immutable V2 recording policy
     */
    public static NetworkEvidenceConfig fileLifecycleV2(int maxTraceRecords){if(maxTraceRecords<=0)throw new IllegalArgumentException("File lifecycle record budget must be positive");return new NetworkEvidenceConfig(Mode.FILE_LIFECYCLE_V2,maxTraceRecords);}

    /** @param budget positive record budget @return explicit storage-version capture option */
    public static NetworkEvidenceConfig storageLifecycleV3(int budget){if(budget<=0)throw new IllegalArgumentException("Storage lifecycle record budget must be positive");return new NetworkEvidenceConfig(Mode.FILE_STORAGE_LIFECYCLE_V3,budget);}

    /** @return versioned recording mode */
    public Mode getMode() { return mode; }
    /** @return positive enabled budget, or zero when OFF */
    public int getMaxTraceRecords() { return maxTraceRecords; }
    /** @return whether group-flow recording is explicitly enabled */
    public boolean isEnabled() { return mode != Mode.OFF; }
}
