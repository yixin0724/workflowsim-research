package org.workflowsim.data.v2;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.Set;
import org.workflowsim.data.NetworkLedgerCodec;

/**
 * Strict standalone V2 file-lifecycle certificate codec. This is not a fluid-service ledger:
 * validation establishes observed provenance, selection, per-file visibility and CPU gating.
 * A complete capture may still describe a running prefix; final-run joins belong to the caller.
 */
public final class FileLifecycleCodec {
    /** Versioned standalone transport schema. */
    public static final String SCHEMA = "workflowsim-file-lifecycle-v2";
    /** Role in an enclosing artifact bundle (not a field in the standalone document). */
    public static final String ARTIFACT_ROLE = "file-lifecycle";

    static final String SHARED_KIND = "COHERENT_FILE_DATAFLOW_V2";
    static final String ISOLATED_KIND = "COHERENT_FILE_DATAFLOW_NO_CONTENTION_V2";
    static final String MODE = "FILE_LIFECYCLE_V2";
    static final String SCOPE = "FILE_LIFECYCLE_NOT_FLUID_SERVICE_ACCOUNTING_V2";
    static final String IDENTITY = "SCOPED_RESOLVED_WRITE_ONCE_FILES_V2";
    static final String RELEASE = "DEPENDENCY_READY_AT_OBSERVATION_V2";
    static final String VISIBILITY = "PER_FILE_SETTLEMENT_OBSERVATION_V2";
    static final String SELECTION = "VISIBLE_LOCAL_OR_MAX_ISOLATED_RATE_STABLE_LOCATION_V2";
    static final String SOURCE_ACCESS = "UNBOUNDED_OFF_FABRIC_SOURCE_INPUT_V2";
    private static final Gson JSON = new GsonBuilder().serializeNulls().setPrettyPrinting().create();

    private FileLifecycleCodec() { }

    /** Immutable evidence and independently recomputed lifecycle counts, never wire summaries. */
    public static final class Decoded {
        private final FileLifecycleValidator.Result result;
        private final JsonObject document;

        private Decoded(FileLifecycleValidator.Result result, JsonObject document) {
            this.result = result;
            this.document = document.deepCopy();
        }

        /** @return immutable reconstructed capture */
        public FileLifecycleEvidence getEvidence() { return result.evidence; }
        /** @return detached validated transport tree */
        public JsonObject getDocument() { return document.deepCopy(); }
        /** @return no active copies and all requested attempts terminal; unrequested Tasks are allowed */
        public boolean isQuiescent() { return result.quiescent; }
        /** @return distinct requested Job attempts */
        public int getRequestedJobCount() { return result.requestedJobs; }
        /** @return terminal Job attempts, including failures */
        public int getCompletedJobCount() { return result.completedJobs; }
        /** @return positive-copy admissions, not input references */
        public long getCopyCount() { return result.copies; }
        /** @return observed positive-copy settlements */
        public long getCompletedCopyCount() { return result.completedCopies; }
        /** @return admitted positive copies without a settlement */
        public int getActiveCopyCount() { return result.activeCopies; }
        /** @return immutable logical Task IDs with at least one successful terminal attempt */
        public Set<Integer> getSuccessfullyCompletedTaskIds() { return result.successfulTasks; }
    }

    /**
     * Construct and independently validate a fresh transport object before publishing it.
     * @param evidence complete capture; truncation is unsupported in the first V2 certificate
     * @return detached mutable document
     * @throws IllegalArgumentException for malformed, unsupported or unverifiable evidence
     */
    public static JsonObject document(FileLifecycleEvidence evidence) {
        return document(evidence, LifecycleContract.FILE_V2, false);
    }

    /** Shared explicit encoding only; callers supply a closed contract, never a wire-selected grammar. */
    static JsonObject document(FileLifecycleEvidence evidence, LifecycleContract contract, boolean storeBackedInputs) {
        if (evidence == null) throw new IllegalArgumentException("File lifecycle evidence is required");
        if (contract == null || (!contract.storage && storeBackedInputs)) throw new IllegalArgumentException("Invalid lifecycle contract");
        JsonObject root = new JsonObject();
        root.addProperty("schema", contract.schema);
        root.addProperty("modelKind", evidence.isShared() ? contract.sharedKind : contract.isolatedKind);
        JsonObject recording = new JsonObject();
        recording.addProperty("mode", contract.mode);
        recording.addProperty("maxTraceRecords", evidence.getMaxRecords());
        root.add("recording", recording);
        root.addProperty("certificateScope", contract.scope);
        JsonObject policies = new JsonObject();
        policies.addProperty("fileIdentity", IDENTITY);
        policies.addProperty("release", RELEASE);
        policies.addProperty("visibility", VISIBILITY);
        policies.addProperty("selection", contract.selection(storeBackedInputs));
        policies.addProperty("sourceAccess", contract.sourceAccess);
        policies.addProperty("sharing", evidence.isShared() ? "SHARED_MAX_MIN" : "ISOLATED_PATH_BOTTLENECK");
        if (contract.storage) {
            policies.addProperty("inputAccess", storeBackedInputs ? StorageLifecycleCodec.STORE_INPUT_ACCESS : StorageLifecycleCodec.LOCAL_INPUT_ACCESS);
            policies.addProperty("outputCommit", StorageLifecycleCodec.OUTPUT_COMMIT);
        }
        root.add("policies", policies);
        JsonObject capture = new JsonObject();
        capture.addProperty("status", evidence.getStatus().name());
        capture.addProperty("observedThrough", evidence.getObservedThrough());
        capture.addProperty("retainedRecords", evidence.getEvents().size());
        capture.addProperty("droppedRecords", evidence.getDroppedRecords());
        root.add("capture", capture);
        root.add("filePlan", evidence.getFilePlan());
        root.add("fabric", evidence.getFabric());
        JsonArray events = new JsonArray();
        for (FileLifecycleEvent event : evidence.getEvents()) {
            JsonObject row = new JsonObject();
            row.addProperty("sequence", event.getSequence());
            row.addProperty("observedTime", event.getObservedTime());
            row.addProperty("type", event.getType().name());
            row.add("payload", event.getPayload());
            events.add(row);
        }
        root.add("events", events);
        FileLifecycleValidator.validate(root, contract);
        return root;
    }

    /** @param evidence complete capture @return strict JSON with explicit nullable fields */
    public static String encode(FileLifecycleEvidence evidence) { return JSON.toJson(document(evidence)); }

    /**
     * Parse strict JSON using the existing lexical-only reader, without invoking V1 semantics.
     * @param json complete standalone document
     * @return independently validated immutable evidence and counts
     * @throws IllegalArgumentException for malformed, unsupported or unverifiable input
     */
    public static Decoded decode(String json) { return decodeDocument(NetworkLedgerCodec.parseDocument(json)); }

    /**
     * Validate only a resolved plan, including scopes, writers and control ancestry. This
     * nonmutating entry point also serves V2 manifests with lifecycle recording OFF; it does
     * not fabricate seeds, requests or a lifecycle certificate.
     * @param plan standalone filePlan object
     * @throws IllegalArgumentException for malformed or unsupported logical plans
     */
    public static void validatePlanDocument(JsonObject plan) { FileLifecycleValidator.validatePlanDocument(plan); }

    /**
     * Validate a transport tree and retain only defensive snapshots. No DTO private fields are
     * populated through Gson. Validate the fixed-depth schema before copying arbitrary input.
     * @param document standalone V2 object
     * @return independently validated immutable evidence and a defensive document snapshot
     * @throws IllegalArgumentException for malformed, unsupported or unverifiable input
     */
    public static Decoded decodeDocument(JsonObject document) {
        if (document == null) throw new IllegalArgumentException("File lifecycle document is required");
        return new Decoded(FileLifecycleValidator.validate(document), document);
    }
}
