package org.workflowsim.data;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Immutable attribution of one admitted V1 transfer group to a Job attempt.
 * A group may aggregate files: its constrained source is not a per-file replica
 * selection or completion observation. V1's nominal estimator and charged path
 * may use different source policies; recording does not repair that distinction.
 */
public final class NetworkFlowBinding {
    /** Existing grouping semantics, not per-file transfers. */
    public enum GroupKind {
        /** Files attributed to one parent Job's outputs. */ PARENT_GROUP_V1,
        /** Real inputs not attributed to a parent output group. */ EXTERNAL_GROUP_V1
    }
    /** The precise interpretation of a recorded source label. */
    public enum SourceScope {
        /** V1's constrained source, not an actual selected per-file replica. */ MODELED_CONSTRAINED_SOURCE
    }
    private final long externalTransferId;
    private final long admissionOrdinal;
    private final int jobId;
    private final List<Integer> taskIds;
    private final Integer parentJobId;
    private final GroupKind groupKind;
    private final SourceScope sourceScope;
    private final String sourceEndpoint;
    private final String destinationEndpoint;
    private final List<String> occupiedResources;

    private NetworkFlowBinding(long externalTransferId, long admissionOrdinal, int jobId,
            List<Integer> taskIds, Integer parentJobId, GroupKind groupKind,
            String sourceEndpoint, String destinationEndpoint, List<String> resources) {
        this.externalTransferId = externalTransferId; this.admissionOrdinal = admissionOrdinal; this.jobId = jobId;
        this.taskIds = Collections.unmodifiableList(new ArrayList<Integer>(taskIds));
        this.parentJobId = parentJobId; this.groupKind = groupKind;
        this.sourceScope = SourceScope.MODELED_CONSTRAINED_SOURCE;
        this.sourceEndpoint = sourceEndpoint; this.destinationEndpoint = destinationEndpoint;
        this.occupiedResources = Collections.unmodifiableList(new ArrayList<String>(resources));
    }

    /**
     * Copy admission attribution without retaining mutable Job, Task or catalog objects.
     * @param externalTransferId caller's transfer ID
     * @param admissionOrdinal actual positive engine admission ordinal
     * @param jobId consuming Job attempt ID
     * @param taskIds logical Task IDs in Job-list order
     * @param parentJobId parent attempt ID, null for an external group
     * @param groupKind V1 parent/external grouping kind
     * @param sourceEndpoint modeled constrained source label
     * @param destinationEndpoint consuming VM endpoint
     * @param resources exact admitted resource sequence, including duplicate weights
     * @return deeply immutable binding
     * @throws IllegalArgumentException for missing or inconsistent metadata
     */
    public static NetworkFlowBinding of(long externalTransferId, long admissionOrdinal, int jobId,
            List<Integer> taskIds, Integer parentJobId, GroupKind groupKind,
            String sourceEndpoint, String destinationEndpoint, List<String> resources) {
        if (admissionOrdinal <= 0) throw new IllegalArgumentException("Admission ordinal must be positive");
        if (taskIds == null || groupKind == null || sourceEndpoint == null || destinationEndpoint == null || resources == null)
            throw new IllegalArgumentException("Network flow binding contains a required null value");
        if ((groupKind == GroupKind.PARENT_GROUP_V1 && parentJobId == null)
                || (groupKind == GroupKind.EXTERNAL_GROUP_V1 && parentJobId != null))
            throw new IllegalArgumentException("Parent Job ID must match the network group kind");
        for (Integer taskId : taskIds) if (taskId == null) throw new IllegalArgumentException("Task IDs cannot contain null");
        for (String resource : resources) if (resource == null) throw new IllegalArgumentException("Occupied resources cannot contain null");
        return new NetworkFlowBinding(externalTransferId, admissionOrdinal, jobId, taskIds, parentJobId,
                groupKind, sourceEndpoint, destinationEndpoint, resources);
    }
    /** @return caller's transfer ID */ public long getExternalTransferId() { return externalTransferId; }
    /** @return actual engine admission ordinal */ public long getAdmissionOrdinal() { return admissionOrdinal; }
    /** @return consuming Job attempt ID */ public int getJobId() { return jobId; }
    /** @return immutable logical Task IDs */ public List<Integer> getTaskIds() { return taskIds; }
    /** @return parent attempt ID, or null for an external group */ public Integer getParentJobId() { return parentJobId; }
    /** @return grouping kind */ public GroupKind getGroupKind() { return groupKind; }
    /** @return modeled constrained-source scope */ public SourceScope getSourceScope() { return sourceScope; }
    /** @return modeled source label */ public String getSourceEndpoint() { return sourceEndpoint; }
    /** @return consuming VM endpoint */ public String getDestinationEndpoint() { return destinationEndpoint; }
    /** @return immutable ordered resource list */ public List<String> getOccupiedResources() { return occupiedResources; }
}
