package org.workflowsim.data.v2;

import java.util.List;

/**
 * Pure replica selection at one observed-state snapshot. It returns source, route and isolated
 * estimate together so callers never estimate one replica and charge a reconstructed producer
 * route. It does not admit/settle a copy, evaluate current network load or activate a run model.
 */
public final class ReplicaTransferSelector {
    /** Deterministic source rule; zero-byte candidates are tied because they need no service. */
    public static final String POLICY="VISIBLE_LOCAL_OR_MAX_ISOLATED_RATE_STABLE_LOCATION_V2";
    private ReplicaTransferSelector(){ }
    /** Distinct demand resolutions; lack of a source is not free/local data. */
    public enum Outcome { WAITING_FOR_SOURCE, LOCAL_PRESENT, ZERO_BYTE_REFERENCE, NETWORK_COPY }

    /** Immutable decision consumed by both estimator and later admission, without re-routing. */
    public static final class Decision {
        private final DataflowFilePlan.FileDefinition file;
        private final DataLocation destination;
        private final double selectedAt;
        private final Outcome outcome;
        private final DataReplicaState.Replica source;
        private final DataTransferFabric.Route route;
        private final Double isolatedSeconds,isolatedCompletionTime;
        private final String policy;
        private Decision(DataflowFilePlan.FileDefinition file,DataLocation destination,double at,Outcome outcome,DataReplicaState.Replica source,DataTransferFabric.Route route,Double seconds,Double finish){this(file,destination,at,outcome,source,route,seconds,finish,POLICY);}
        private Decision(DataflowFilePlan.FileDefinition file,DataLocation destination,double at,Outcome outcome,DataReplicaState.Replica source,DataTransferFabric.Route route,Double seconds,Double finish,String policy){
            this.file=file;this.destination=destination;selectedAt=at;this.outcome=outcome;this.source=source;this.route=route;isolatedSeconds=seconds;isolatedCompletionTime=finish;this.policy=policy;
        }
        /** @return exact selector policy version */ public String getPolicy(){return policy;}
        /** @return scoped immutable file metadata from the visibility snapshot */ public DataflowFilePlan.FileDefinition getFile(){return file;}
        /** @return fixed destination */ public DataLocation getDestination(){return destination;}
        /** @return observation at which this decision was made */ public double getSelectedAt(){return selectedAt;}
        /** @return waiting/local/zero/positive resolution */ public Outcome getOutcome(){return outcome;}
        /** @return selected immutable visible copy, null only when none exists */ public DataReplicaState.Replica getSourceReplica(){return source;}
        /** @return the actual charged path for NETWORK_COPY, otherwise null */ public DataTransferFabric.Route getRoute(){return route;}
        /** @return positive isolated transfer seconds, zero for local/zero, null for missing source */ public Double getIsolatedSeconds(){return isolatedSeconds;}
        /** @return isolated completion prediction, not contended completion or actual visibility */ public Double getIsolatedCompletionTime(){return isolatedCompletionTime;}
    }

    /**
     * Select local visible data first, otherwise the largest isolated resource bottleneck;
     * typed location order breaks ties. There is no live-load-based route adaptation. For a
     * positive selected transfer, duration and its absolute isolated finish must be finite and
     * strictly advance the binary64 clock; unsupported scales are rejected, not rounded to free
     * traffic or compensated by selecting a slower source. Shared-service numeric validity and
     * actual completion observations remain the driver's separate responsibility.
     * @param snapshot immutable authoritative visible-state snapshot
     * @param fabric immutable registered capacities and actual-placement routing view
     * @param fileId known logical file
     * @param destination known VM input destination
     * @return immutable resolution binding its one source/path/rate decision
     */
    public static Decision select(DataReplicaState.Snapshot snapshot,DataTransferFabric fabric,DataflowFilePlan.FileId fileId,DataLocation destination){
        if(snapshot==null||fabric==null)throw bad("Visibility snapshot and fabric are required");fabric.requireLocation(destination);
        if(destination.getKind()!=DataLocation.Kind.VM)throw bad("NF003 input destination must be a VM");
        DataflowFilePlan.FileDefinition file=snapshot.getFile(fileId);double now=snapshot.getObservedThrough();
        DataReplicaState.Replica local=snapshot.getReplica(fileId,destination);
        if(local!=null)return new Decision(file,destination,now,Outcome.LOCAL_PRESENT,local,null,0.0,now);
        List<DataReplicaState.Replica> candidates=snapshot.getReplicas(fileId);
        if(candidates.isEmpty())return new Decision(file,destination,now,Outcome.WAITING_FOR_SOURCE,null,null,null,null);
        DataReplicaState.Replica selected=null;DataTransferFabric.Route selectedRoute=null;
        for(DataReplicaState.Replica candidate:candidates){
            fabric.requireLocation(candidate.getLocation());
            if(candidate.getVisibleAt()>now)throw bad("Snapshot contains a future replica");
            if(file.getBytes()==0){if(selected==null||candidate.getLocation().compareTo(selected.getLocation())<0)selected=candidate;continue;}
            DataTransferFabric.Route route=fabric.route(candidate.getLocation(),destination);
            if(selectedRoute==null||route.getStandaloneRateBytesPerSecond()>selectedRoute.getStandaloneRateBytesPerSecond()
                    ||(route.getStandaloneRateBytesPerSecond()==selectedRoute.getStandaloneRateBytesPerSecond()&&candidate.getLocation().compareTo(selected.getLocation())<0)){
                selected=candidate;selectedRoute=route;
            }
        }
        if(file.getBytes()==0)return new Decision(file,destination,now,Outcome.ZERO_BYTE_REFERENCE,selected,null,0.0,now);
        double seconds=file.getBytes()/selectedRoute.getStandaloneRateBytesPerSecond();double finish=now+seconds;
        if(!Double.isFinite(seconds)||seconds<=0||!Double.isFinite(finish)||finish<=now)throw bad("Selected positive transfer duration/absolute finish is not representable in the supported binary64 domain");
        return new Decision(file,destination,now,Outcome.NETWORK_COPY,selected,selectedRoute,seconds,finish);
    }
    /**
     * V3 shared-store read-through: store commit precedes even a target-local cache hit.
     * @param snapshot observed replicas
     * @param fabric bounded store fabric
     * @param fileId logical input
     * @param destination target VM
     * @return deferred, local, zero or SOURCE read decision
     */
    public static Decision selectStoreBacked(DataReplicaState.Snapshot snapshot,DataTransferFabric fabric,DataflowFilePlan.FileId fileId,DataLocation destination){
        if(snapshot==null||fabric==null||fabric.getSourceStorage()==null)throw bad("Store-backed selection needs bounded storage");fabric.requireLocation(destination);if(destination.getKind()!=DataLocation.Kind.VM)throw bad("Input destination must be VM");
        DataflowFilePlan.FileDefinition file=snapshot.getFile(fileId);double now=snapshot.getObservedThrough();DataReplicaState.Replica store=snapshot.getReplica(fileId,CoherentDataflowRuntime.SOURCE);String policy="COMMITTED_STORE_THEN_VM_READ_CACHE_V3";
        if(store==null)return new Decision(file,destination,now,Outcome.WAITING_FOR_SOURCE,null,null,null,null,policy);
        DataReplicaState.Replica local=snapshot.getReplica(fileId,destination);if(local!=null)return new Decision(file,destination,now,Outcome.LOCAL_PRESENT,local,null,0.0,now,policy);
        return fixedSource(store,fabric,destination,now,policy);
    }
    /** @param source actual successful output copy @param fabric bounded store fabric @param now observation @return fixed-source materialization decision */
    public static Decision materializeToStore(DataReplicaState.Replica source,DataTransferFabric fabric,double now){
        if(source==null||source.getFile().isExternal()||source.getLocation().getKind()!=DataLocation.Kind.VM||fabric==null||fabric.getSourceStorage()==null)throw bad("Output materialization needs a produced VM replica and bounded store");
        return fixedSource(source,fabric,CoherentDataflowRuntime.SOURCE,now,"SUCCESSFUL_OUTPUT_MATERIALIZATION_V3");
    }
    private static Decision fixedSource(DataReplicaState.Replica source,DataTransferFabric fabric,DataLocation destination,double now,String policy){
        fabric.requireLocation(source.getLocation());fabric.requireLocation(destination);
        if(!Double.isFinite(now)||now<source.getVisibleAt())throw bad("Source is not visible at the requested observation");DataflowFilePlan.FileDefinition file=source.getFile();
        if(file.getBytes()==0)return new Decision(file,destination,now,Outcome.ZERO_BYTE_REFERENCE,source,null,0.0,now,policy);
        DataTransferFabric.Route route=fabric.route(source.getLocation(),destination);double seconds=file.getBytes()/route.getStandaloneRateBytesPerSecond(),finish=now+seconds;
        if(!Double.isFinite(seconds)||seconds<=0||!Double.isFinite(finish)||finish<=now)throw bad("Unrepresentable storage transfer time");return new Decision(file,destination,now,Outcome.NETWORK_COPY,source,route,seconds,finish,policy);
    }
    private static IllegalArgumentException bad(String message){return new IllegalArgumentException(message);}
}
