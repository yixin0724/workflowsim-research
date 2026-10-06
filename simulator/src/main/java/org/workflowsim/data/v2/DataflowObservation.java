package org.workflowsim.data.v2;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.workflowsim.data.TransferServiceSnapshot;

/**
 * Passive, immutable view of one committed dataflow state. No getter advances time, publishes a
 * forecast, drains readiness, or depends on lifecycle recording. This is a policy observation,
 * not a counterfactual service replay or another evidence certificate.
 */
public final class DataflowObservation {
    public static final String CONTRACT="OBSERVED_DATAFLOW_STATE_V1";
    /** Actual initialization state; unbound metadata is not an actual placement. */
    public enum Status { UNINITIALIZED, PLAN_ONLY, BOUND }
    /** Requested attempts only; terminal attempts are not retained in the active-job view. */
    public enum JobPhase { WAITING_FOR_STORE, WAITING_FOR_INPUTS, READY_FOR_CPU, RUNNING }
    /** A file declaration plus only replicas visible at the observation watermark. */
    public static final class FileView {
        private final DataflowFilePlan.FileDefinition file;
        private final List<DataReplicaState.Replica> replicas;
        FileView(DataflowFilePlan.FileDefinition file,List<DataReplicaState.Replica> replicas){this.file=file;this.replicas=immutable(replicas);}
        public DataflowFilePlan.FileDefinition getFile(){return file;}
        public List<DataReplicaState.Replica> getVisibleReplicas(){return replicas;}
        public DataReplicaState.Replica getReplica(DataLocation location){for(DataReplicaState.Replica replica:replicas)if(replica.getLocation().equals(location))return replica;return null;}
    }
    /** Observed active-copy progress joined to its immutable actual source and route. */
    public static final class CopyView {
        private final long ordinal;
        private final int ownerJobId;
        private final String purpose;
        private final DataflowFilePlan.FileDefinition file;
        private final DataReplicaState.Replica source;
        private final DataLocation destination;
        private final TransferServiceSnapshot.Flow service;
        CopyView(long ordinal,int ownerJobId,String purpose,DataflowFilePlan.FileDefinition file,DataReplicaState.Replica source,DataLocation destination,TransferServiceSnapshot.Flow service){this.ordinal=ordinal;this.ownerJobId=ownerJobId;this.purpose=purpose;this.file=file;this.source=source;this.destination=destination;this.service=service;}
        public long getOrdinal(){return ordinal;}
        public int getOwnerJobId(){return ownerJobId;}
        public String getPurpose(){return purpose;}
        public DataflowFilePlan.FileDefinition getFile(){return file;}
        public DataReplicaState.Replica getSourceReplica(){return source;}
        public DataLocation getDestination(){return destination;}
        public TransferServiceSnapshot.Flow getService(){return service;}
    }
    /** Immutable active attempt state, separate from any heuristic CPU reservation. */
    public static final class JobView {
        private final int jobId,taskId,vmId;
        private final JobPhase phase;
        private final double requestedAt;
        private final Double dataReadyAt,cpuStartedAt;
        private final Set<DataflowFilePlan.FileId> pendingInputs,waitingStoreInputs;
        JobView(int jobId,int taskId,int vmId,JobPhase phase,double requestedAt,Double ready,Double started,Set<DataflowFilePlan.FileId> pending,Set<DataflowFilePlan.FileId> store){this.jobId=jobId;this.taskId=taskId;this.vmId=vmId;this.phase=phase;this.requestedAt=requestedAt;dataReadyAt=ready;cpuStartedAt=started;pendingInputs=immutableSet(pending);waitingStoreInputs=immutableSet(store);}
        public int getJobId(){return jobId;}
        public int getTaskId(){return taskId;}
        public int getVmId(){return vmId;}
        public JobPhase getPhase(){return phase;}
        public double getRequestedAt(){return requestedAt;}
        public Double getDataReadyAt(){return dataReadyAt;}
        public Double getCpuStartedAt(){return cpuStartedAt;}
        public Set<DataflowFilePlan.FileId> getPendingInputs(){return pendingInputs;}
        public Set<DataflowFilePlan.FileId> getWaitingStoreInputs(){return waitingStoreInputs;}
    }
    private final Status status;
    private final boolean shared,storageVersion,storeBackedInputs,completeFileView;
    private final double observedThrough;
    private final TransferServiceSnapshot service;
    private final DataTransferFabric fabric;
    private final Map<DataflowFilePlan.FileId,FileView> files;
    private final Map<Long,CopyView> copies;
    private final Map<Integer,JobView> jobs;
    private final Set<DataflowFilePlan.FileId> pendingOutputs;
    DataflowObservation(Status status,boolean shared,boolean storageVersion,boolean storeBackedInputs,boolean completeFileView,double observedThrough,TransferServiceSnapshot service,DataTransferFabric fabric,Map<DataflowFilePlan.FileId,FileView> files,Map<Long,CopyView> copies,Map<Integer,JobView> jobs,Set<DataflowFilePlan.FileId> pendingOutputs){
        this.status=status;this.shared=shared;this.storageVersion=storageVersion;this.storeBackedInputs=storeBackedInputs;this.completeFileView=completeFileView;this.observedThrough=observedThrough;this.service=service;this.fabric=fabric;this.files=immutableMap(files);this.copies=immutableMap(copies);this.jobs=immutableMap(jobs);this.pendingOutputs=immutableSet(pendingOutputs);
    }
    public String getContract(){return CONTRACT;}
    public Status getStatus(){return status;}
    public boolean isBound(){return status==Status.BOUND;}
    public boolean isShared(){return shared;}
    public boolean isStorageVersion(){return storageVersion;}
    public boolean isStoreBackedInputs(){return storeBackedInputs;}
    /** @return whether all declared files are included, rather than a requested input scope */
    public boolean isCompleteFileView(){return completeFileView;}
    public double getObservedThrough(){return observedThrough;}
    /** @return actual service snapshot, null before binding */
    public TransferServiceSnapshot getService(){return service;}
    /** @return immutable actual fabric, null before binding */
    public DataTransferFabric getFabric(){return fabric;}
    public Map<DataflowFilePlan.FileId,FileView> getFiles(){return files;}
    /** @param id included logical identity @return immutable file view; missing scope is not an empty replica set */
    public FileView getFile(DataflowFilePlan.FileId id){FileView result=files.get(id);if(result==null)throw new IllegalArgumentException("File is unknown or outside observation scope: "+id);return result;}
    public Map<Long,CopyView> getActiveCopies(){return copies;}
    public Map<Integer,JobView> getActiveJobs(){return jobs;}
    public Set<DataflowFilePlan.FileId> getPendingOutputFiles(){return pendingOutputs;}
    /** @param file scoped file identity @param destination typed actual target @return active copy or null, not a visible replica */
    public CopyView getActiveCopy(DataflowFilePlan.FileId file,DataLocation destination){for(CopyView copy:copies.values())if(copy.file.getId().equals(file)&&copy.destination.equals(destination))return copy;return null;}
    private static <T> List<T> immutable(List<T> values){return Collections.unmodifiableList(new ArrayList<>(values));}
    private static <K,V> Map<K,V> immutableMap(Map<K,V> values){return Collections.unmodifiableMap(new LinkedHashMap<>(values));}
    private static <T extends Comparable<? super T>> Set<T> immutableSet(Set<T> values){return Collections.unmodifiableSet(new TreeSet<>(values));}
}
