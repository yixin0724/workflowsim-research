package org.workflowsim.data.v2;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Closed, trusted codec contracts. A document never chooses or extends its own grammar:
 * the public V2 and V3 entry points supply one of these constants before validation.
 */
enum LifecycleContract {
    FILE_V2(false, FileLifecycleCodec.SCHEMA, FileLifecycleCodec.SHARED_KIND,
            FileLifecycleCodec.ISOLATED_KIND, FileLifecycleCodec.MODE, FileLifecycleCodec.SCOPE,
            FileLifecycleCodec.SOURCE_ACCESS),
    STORAGE_V3(true, StorageLifecycleCodec.SCHEMA, StorageLifecycleCodec.SHARED_KIND,
            StorageLifecycleCodec.ISOLATED_KIND, StorageLifecycleCodec.MODE, StorageLifecycleCodec.SCOPE,
            StorageLifecycleCodec.SOURCE_ACCESS);

    final boolean storage;
    final String schema, sharedKind, isolatedKind, mode, scope, sourceAccess;
    final Set<String> policyFields, fabricFields, admissionFields;

    LifecycleContract(boolean storage, String schema, String sharedKind, String isolatedKind,
            String mode, String scope, String sourceAccess) {
        this.storage = storage; this.schema = schema; this.sharedKind = sharedKind;
        this.isolatedKind = isolatedKind; this.mode = mode; this.scope = scope; this.sourceAccess = sourceAccess;
        policyFields = storage
                ? fields("fileIdentity", "release", "visibility", "selection", "sourceAccess", "sharing", "inputAccess", "outputCommit")
                : fields("fileIdentity", "release", "visibility", "selection", "sourceAccess", "sharing");
        fabricFields = storage
                ? fields("locations", "resources", "vmHostAssignments", "topology", "sourceStorage")
                : fields("locations", "resources", "vmHostAssignments", "topology");
        admissionFields = storage
                ? fields("copyOrdinal", "fileId", "bytes", "sourceReplica", "destination", "purpose", "ownerJobId", "resources", "standaloneRate", "isolatedSeconds")
                : fields("copyOrdinal", "fileId", "bytes", "sourceReplica", "destinationVmId", "resources", "standaloneRate", "isolatedSeconds");
    }

    String selection(boolean storeBackedInputs) {
        return storage && storeBackedInputs ? StorageLifecycleCodec.STORE_INPUT_ACCESS : FileLifecycleCodec.SELECTION;
    }

    boolean accepts(FileLifecycleEvent.Type type) {
        switch (type) {
            case EXTERNAL_SEEDED: case TASK_FINISHED: case JOB_INPUT_REQUESTED: case INPUT_RESOLVED:
            case COPY_ADMITTED: case COPY_SETTLED: case JOB_DATA_READY: case JOB_CPU_STARTED:
                return true;
            case INPUT_WAITING_FOR_STORE: case OUTPUT_RESOLVED:
                return storage;
            default: return false;
        }
    }

    boolean acceptsAcquisition(String acquisition) {
        switch (acquisition) {
            case "EXTERNAL_SEED": case "TASK_OUTPUT": case "COPY_SETTLEMENT": case "ZERO_BYTE_REFERENCE":
                return true;
            case "ZERO_BYTE_OUTPUT": return storage;
            default: return false;
        }
    }

    private static Set<String> fields(String... names) {
        return Collections.unmodifiableSet(new LinkedHashSet<>(Arrays.asList(names)));
    }
}
