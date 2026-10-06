package org.workflowsim.data.v2;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import java.util.Set;
import org.workflowsim.data.NetworkLedgerCodec;

/**
 * Strict standalone V3 storage-lifecycle certificate codec. Independently checks bounded
 * paths, observed provenance, deferred input access, successful output obligations and CPU
 * gating. It does not certify unrecorded fluid-service accounting. COMPLETE means no lost
 * history, not a final run: a valid capture may contain store waiters and active output copies.
 */
public final class StorageLifecycleCodec {
    /** Versioned standalone transport schema. */
    public static final String SCHEMA = "workflowsim-storage-lifecycle-v3";
    /** Enclosing artifact role; deliberately not a field of the standalone document. */
    public static final String ARTIFACT_ROLE = "storage-lifecycle";

    static final String SHARED_KIND = "COHERENT_STORAGE_DATAFLOW_V3";
    static final String ISOLATED_KIND = "COHERENT_STORAGE_DATAFLOW_NO_CONTENTION_V3";
    static final String MODE = "FILE_STORAGE_LIFECYCLE_V3";
    static final String SCOPE = "STORAGE_LIFECYCLE_NOT_FLUID_SERVICE_ACCOUNTING_V3";
    static final String SOURCE_ACCESS = "BOUNDED_STORE_READ_WRITE_NIC_HOST_ATTACHMENT_V3";
    static final String LOCAL_INPUT_ACCESS = "LOCAL_VISIBLE_REPLICA_V3";
    static final String STORE_INPUT_ACCESS = "COMMITTED_STORE_THEN_VM_READ_CACHE_V3";
    static final String OUTPUT_COMMIT = "ALL_SUCCESSFUL_OUTPUTS_TO_SOURCE_V3";
    private static final Gson JSON = new GsonBuilder().serializeNulls().setPrettyPrinting().create();

    private StorageLifecycleCodec() { }

    /** Immutable reconstructed evidence and independently recomputed lifecycle counts. */
    public static final class Decoded {
        private final FileLifecycleValidator.Result result;
        private final StorageLifecycleEvidence evidence;
        private final JsonObject document;

        private Decoded(FileLifecycleValidator.Result result, JsonObject document) {
            this.result = result;
            evidence = StorageLifecycleEvidence.capture(result.evidence, result.storeBackedInputs);
            this.document = document.deepCopy();
        }

        /** @return immutable reconstructed storage-version capture */
        public StorageLifecycleEvidence getEvidence() { return evidence; }
        /** @return detached validated transport tree */
        public JsonObject getDocument() { return document.deepCopy(); }
        /** @return terminal requested Jobs, no active copies/store waits, and all successful outputs at SOURCE */
        public boolean isQuiescent() { return result.quiescent; }
        /** @return distinct requested Job attempts, not logical Tasks */
        public int getRequestedJobCount() { return result.requestedJobs; }
        /** @return terminal Job attempts, including failures */
        public int getCompletedJobCount() { return result.completedJobs; }
        /** @return admitted positive input and output copies */
        public long getCopyCount() { return result.copies; }
        /** @return observed positive-copy settlements */
        public long getCompletedCopyCount() { return result.completedCopies; }
        /** @return admitted positive copies without a settlement */
        public int getActiveCopyCount() { return result.activeCopies; }
        /** @return immutable logical Task IDs with at least one successful terminal attempt */
        public Set<Integer> getSuccessfullyCompletedTaskIds() { return result.successfulTasks; }
        /** @return distinct successful output file IDs not yet visible at SOURCE, including unused outputs */
        public int getPendingOutputFileCount() { return result.pendingOutputFiles; }
        /** @return unresolved normalized (Job, file) store waits, not reference multiplicity or VM-copy waits */
        public int getWaitingStoreInputCount() { return result.waitingStoreInputs; }
    }

    /**
     * Construct and independently validate a fresh V3 document before publishing it.
     * @param evidence complete, nontruncated storage capture
     * @return detached mutable document
     * @throws IllegalArgumentException for malformed, unsupported or unverifiable evidence
     */
    public static JsonObject document(StorageLifecycleEvidence evidence) {
        if (evidence == null) throw new IllegalArgumentException("Storage lifecycle evidence is required");
        return FileLifecycleCodec.document(evidence.getCapture(), LifecycleContract.STORAGE_V3, evidence.isStoreBackedInputs());
    }

    /** @param evidence complete capture @return strict JSON with explicit nullable fields */
    public static String encode(StorageLifecycleEvidence evidence) { return JSON.toJson(document(evidence)); }

    /**
     * Parse strict JSON with duplicate-key, nesting and exact-number checks, then validate V3.
     * @param json complete standalone V3 document
     * @return immutable evidence and independently checked counts
     * @throws IllegalArgumentException for malformed, unsupported or unverifiable input
     */
    public static Decoded decode(String json) { return decodeDocument(NetworkLedgerCodec.parseDocument(json)); }

    /**
     * Validate a V3 tree before taking defensive snapshots; never populate private DTO fields.
     * @param document standalone V3 object
     * @return immutable evidence and a defensive document snapshot
     * @throws IllegalArgumentException for malformed, unsupported or unverifiable input
     */
    public static Decoded decodeDocument(JsonObject document) {
        if (document == null) throw new IllegalArgumentException("Storage lifecycle document is required");
        return new Decoded(FileLifecycleValidator.validate(document, LifecycleContract.STORAGE_V3), document);
    }

    /**
     * Validate the shared resolved write-once logical plan without fabric or event history.
     * @param plan standalone filePlan object, including scopes, writers and control ancestry
     * @throws IllegalArgumentException for malformed or unsupported logical plans
     */
    public static void validatePlanDocument(JsonObject plan) { FileLifecycleCodec.validatePlanDocument(plan); }
}
