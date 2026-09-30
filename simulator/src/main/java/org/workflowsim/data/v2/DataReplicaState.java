package org.workflowsim.data.v2;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Serial, run-local authoritative state for write-once V2 replicas and active copy identities.
 * Publications occur at explicit settlement observations, never predicted Task finish times
 * or optional trace records. This component neither advances a network solver nor enables
 * a SimulationConfig model. Input waiters/routing and CPU dispatch are separate consumers.
 */
public final class DataReplicaState {
    /** Per-file observed settlement policy, separate from effective fluid completion time. */
    public static final String VISIBILITY_POLICY="PER_FILE_SETTLEMENT_OBSERVATION_V2";
    /** How a particular location first acquired this logical file. */
    public enum Acquisition { EXTERNAL_SEED, TASK_OUTPUT, COPY_SETTLEMENT, ZERO_BYTE_REFERENCE }

    /** Immutable root origin; copies retain it rather than claiming to be the producing attempt. */
    public static final class Origin {
        private final Integer producerTaskId;
        private final Long jobAttemptId;
        private final DataLocation location;
        private final double observedAt;
        private Origin(Integer task,Long attempt,DataLocation location,double observedAt){producerTaskId=task;jobAttemptId=attempt;this.location=location;this.observedAt=observedAt;}
        /** @return true for an external source seed */ public boolean isExternal(){return producerTaskId==null;}
        /** @return logical producer, or null for external data */ public Integer getProducerTaskId(){return producerTaskId;}
        /** @return successful producing Job attempt, or null for external data */ public Long getJobAttemptId(){return jobAttemptId;}
        /** @return original publication location */ public DataLocation getLocation(){return location;}
        /** @return original publication observation, not a predicted finish */ public double getObservedAt(){return observedAt;}
    }

    /** First-visible immutable copy at a location; write-once re-publication does not replace it. */
    public static final class Replica {
        private final DataflowFilePlan.FileDefinition file;
        private final DataLocation location,copiedFrom;
        private final double visibleAt;
        private final Acquisition acquisition;
        private final Origin origin;
        private final Long copyOrdinal;
        private Replica(DataflowFilePlan.FileDefinition file,DataLocation location,double at,Acquisition acquisition,Origin origin,DataLocation copiedFrom,Long ordinal){
            this.file=file;this.location=location;visibleAt=at;this.acquisition=acquisition;this.origin=origin;this.copiedFrom=copiedFrom;copyOrdinal=ordinal;
        }
        /** @return scoped immutable logical object */ public DataflowFilePlan.FileDefinition getFile(){return file;}
        /** @return this copy's location */ public DataLocation getLocation(){return location;}
        /** @return actual publication/settlement observation */ public double getVisibleAt(){return visibleAt;}
        /** @return immediate acquisition type */ public Acquisition getAcquisition(){return acquisition;}
        /** @return external or successful Task origin, inherited through copies */ public Origin getOrigin(){return origin;}
        /** @return immediate copy source, null for seeds/Task outputs */ public DataLocation getCopiedFrom(){return copiedFrom;}
        /** @return settled positive-copy ordinal, null for non-network publication */ public Long getCopyOrdinal(){return copyOrdinal;}
    }

    /** A publication can observe an already-present copy without overwriting its first visibility. */
    public static final class Publication {
        private final Replica replica;
        private final boolean fresh;
        private Publication(Replica replica,boolean fresh){this.replica=replica;this.fresh=fresh;}
        /** @return first-visible copy at this location */ public Replica getReplica(){return replica;}
        /** @return whether a new location copy was created */ public boolean isNewReplica(){return fresh;}
    }

    /** Opaque run-owned identity, independent of Job IDs, reference multiplicity or optional tracing. */
    public static final class CopyTicket {
        private final Object owner;
        private final long ordinal;
        private final Replica source;
        private final DataLocation destination;
        private final double releaseTime;
        private CopyTicket(Object owner,long ordinal,Replica source,DataLocation destination,double release){this.owner=owner;this.ordinal=ordinal;this.source=source;this.destination=destination;releaseTime=release;}
        /** @return unique copy identity within this state */ public long getOrdinal(){return ordinal;}
        /** @return immutable selected source copy */ public Replica getSourceReplica(){return source;}
        /** @return fixed target */ public DataLocation getDestination(){return destination;}
        /** @return scoped object and unique positive payload */ public DataflowFilePlan.FileDefinition getFile(){return source.file;}
        /** @return admission observation; never backdated */ public double getReleaseTime(){return releaseTime;}
    }

    /** Deeply frozen visible-state and active-copy view. No mutable state or observer callback escapes. */
    public static final class Snapshot {
        private final DataflowFilePlan plan;
        private final Set<DataLocation> known;
        private final List<DataLocation> locations;
        private final Map<DataflowFilePlan.FileId,Map<DataLocation,Replica>> replicas;
        private final List<CopyTicket> active;
        private final double observedThrough;
        private Snapshot(DataReplicaState state){
            plan=state.plan;known=state.locations;locations=immutableList(known);observedThrough=state.observedThrough;
            Map<DataflowFilePlan.FileId,Map<DataLocation,Replica>> copy=new TreeMap<>();
            for(Map.Entry<DataflowFilePlan.FileId,Map<DataLocation,Replica>> entry:state.replicas.entrySet())copy.put(entry.getKey(),Collections.unmodifiableMap(new TreeMap<>(entry.getValue())));
            replicas=Collections.unmodifiableMap(copy);active=immutableList(state.active.values());
        }
        /** @return observed-state watermark */ public double getObservedThrough(){return observedThrough;}
        /** @return typed known locations in stable order */ public List<DataLocation> getLocations(){return locations;}
        /** @return immutable active tickets in ordinal order */ public List<CopyTicket> getActiveCopies(){return active;}
        /**
         * @param file known logical identity
         * @return immutable file definition even when no copy is yet visible
         */
        public DataflowFilePlan.FileDefinition getFile(DataflowFilePlan.FileId file){return plan.getFile(file);}
        /**
         * @param file known file identity
         * @return currently visible replicas, in stable location order
         */
        public List<Replica> getReplicas(DataflowFilePlan.FileId file){plan.getFile(file);Map<DataLocation,Replica> copies=replicas.get(file);return copies==null?Collections.<Replica>emptyList():immutableList(copies.values());}
        /**
         * @param file known file identity
         * @param location registered location
         * @return visible replica or null, never an in-flight promise
         */
        public Replica getReplica(DataflowFilePlan.FileId file,DataLocation location){plan.getFile(file);requireKnown(known,location);Map<DataLocation,Replica> copies=replicas.get(file);return copies==null?null:copies.get(location);}
    }

    private final DataflowFilePlan plan;
    private final Set<DataLocation> locations;
    private final Object ticketOwner=new Object();
    private final Map<DataflowFilePlan.FileId,Map<DataLocation,Replica>> replicas=new TreeMap<>();
    private final Map<Long,CopyTicket> active=new TreeMap<>();
    private final Map<Target,CopyTicket> byTarget=new HashMap<>();
    private final Map<Attempt,Completion> completions=new HashMap<>();
    private final Map<Long,JobObservation> jobObservations=new HashMap<>();
    private long nextOrdinal=1;
    private double observedThrough;

    /**
     * @param plan frozen logical file plan
     * @param knownLocations distinct immutable typed locations; no capacity is implied
     */
    public DataReplicaState(DataflowFilePlan plan,Collection<DataLocation> knownLocations){
        if(plan==null||knownLocations==null)throw bad("File plan and known locations are required");this.plan=plan;Set<DataLocation> copied=new TreeSet<>();
        for(DataLocation location:knownLocations)if(location==null||!copied.add(location))throw bad("Locations must be nonnull and distinct");locations=Collections.unmodifiableSet(copied);
    }
    /** @return last accepted observation */ public double getObservedThrough(){return observedThrough;}
    /** @return accepted positive-copy count, not input-reference count */ public long getAdmittedCopyCount(){return nextOrdinal-1;}
    /** @return unique observed logical Task/Job-attempt pairs */ public long getCompletedTaskAttemptCount(){return completions.size();}
    /** @return currently active positive-copy count */ public int getActiveCopyCount(){return active.size();}
    /** @return immutable observation snapshot */ public Snapshot snapshot(){return new Snapshot(this);}
    /** @param now finite monotonic observation time; this operation performs no network service */
    public void advanceTo(double now){observedThrough=time(now);}
    /**
     * @param file known file identity
     * @param location registered location
     * @return visible copy or null
     */
    public Replica getReplica(DataflowFilePlan.FileId file,DataLocation location){plan.getFile(file);requireKnown(locations,location);return visible(file,location);}
    /**
     * @param file known file identity
     * @param destination registered target
     * @return existing active identity that a coordinator can join, or null
     */
    public CopyTicket getInFlight(DataflowFilePlan.FileId file,DataLocation destination){plan.getFile(file);requireKnown(locations,destination);return byTarget.get(new Target(file,destination));}

    /**
     * @param file externally supplied logical object
     * @param source registered SOURCE location
     * @param now actual availability observation
     * @return new or already-present publication
     */
    public Publication seedExternal(DataflowFilePlan.FileId file,DataLocation source,double now){
        DataflowFilePlan.FileDefinition definition=plan.getFile(file);requireKnown(locations,source);
        if(!definition.isExternal()||source.getKind()!=DataLocation.Kind.SOURCE)throw bad("Only external logical files can be seeded at SOURCE locations");
        double at=time(now);Replica proposal=new Replica(definition,source,at,Acquisition.EXTERNAL_SEED,new Origin(null,null,source,at),null,null);
        Publication result=publish(proposal);observedThrough=at;return result;
    }

    /**
     * Record a terminal Task outcome using its completed Job's actual placement. A failed Task
     * publishes no outputs even if siblings in the same Job succeeded. Invalid/conflicting calls
     * leave all state and clocks unchanged. A Job attempt has one actual VM and one observed
     * completion time, while constituent Task success flags may differ. Repeating an identical
     * outcome is idempotent subject to the same non-backdated observation-time rule.
     * @param taskId logical Task ID
     * @param jobAttemptId nonnegative Job attempt identity (separate from logical Task ID)
     * @param actualVm actual registered VM location, not a planner hint
     * @param succeeded terminal success of this Task, not aggregate Job success
     * @param now datacenter completion observation, not a predicted Task finish
     * @return first-observation output publications; empty for failure or identical repeated outcome
     */
    public List<Publication> recordTaskCompletion(int taskId,long jobAttemptId,DataLocation actualVm,boolean succeeded,double now){
        List<DataflowFilePlan.FileId> outputIds=plan.getOutputFiles(taskId);requireKnown(locations,actualVm);
        if(jobAttemptId<0||actualVm.getKind()!=DataLocation.Kind.VM)throw bad("Task outcome needs nonnegative attempt ID and an actual VM location");
        double at=time(now);JobObservation job=jobObservations.get(jobAttemptId);
        if(job!=null&&!job.matches(actualVm,at))throw new IllegalStateException("A Job attempt has one actual VM and one completion observation");
        Attempt key=new Attempt(taskId,jobAttemptId);Completion previous=completions.get(key);
        if(previous!=null){if(!previous.matches(actualVm,succeeded,at))throw new IllegalStateException("A Task/Job attempt outcome cannot be rewritten");return Collections.emptyList();}
        List<Replica> proposals=new ArrayList<>();
        if(succeeded){Origin origin=new Origin(taskId,jobAttemptId,actualVm,at);for(DataflowFilePlan.FileId file:outputIds)proposals.add(new Replica(plan.getFile(file),actualVm,at,Acquisition.TASK_OUTPUT,origin,null,null));}
        List<Publication> published=new ArrayList<>();for(Replica proposal:proposals)published.add(publish(proposal));
        if(job==null)jobObservations.put(jobAttemptId,new JobObservation(actualVm,at));
        completions.put(key,new Completion(actualVm,succeeded,at));observedThrough=at;return Collections.unmodifiableList(published);
    }

    /**
     * Admit one positive copy from a currently visible source. A coordinator must query and join
     * an existing in-flight ticket before selecting/admitting another route for the same target.
     * @param file known positive-byte object
     * @param source registered visible holder
     * @param destination fixed registered destination without an existing visible copy
     * @param now admission observation
     * @return immutable run-owned copy identity with its frozen source
     */
    public CopyTicket admitCopy(DataflowFilePlan.FileId file,DataLocation source,DataLocation destination,double now){
        DataflowFilePlan.FileDefinition definition=plan.getFile(file);requireKnown(locations,source);requireKnown(locations,destination);double at=time(now);
        if(definition.getBytes()==0)throw bad("Zero-byte references do not admit positive copies");
        if(!Double.isFinite(Math.nextUp(at)))throw bad("Positive copy admission has no finite future observation");
        Replica replica=requireVisible(file,source);
        if(visible(file,destination)!=null)throw new IllegalStateException("Destination already has a visible replica; no copy is needed");
        Target target=new Target(file,destination);if(byTarget.containsKey(target))throw new IllegalStateException("An active copy already exists; join its ticket instead");
        if(nextOrdinal==Long.MAX_VALUE)throw new IllegalStateException("Copy identity range exhausted");
        CopyTicket ticket=new CopyTicket(ticketOwner,nextOrdinal,replica,destination,at);
        active.put(ticket.ordinal,ticket);byTarget.put(target,ticket);nextOrdinal++;observedThrough=at;return ticket;
    }

    /**
     * Settle a positive copy at its actual outer observation. This method does not assert an
     * internal fluid finish time or validate transferred service; the driver supplies completion.
     * @param ticket active identity owned by this run
     * @param now finite observation strictly later than positive-copy admission
     * @return new or already-present destination replica, retaining earliest visibility
     */
    public Publication settleCopy(CopyTicket ticket,double now){
        if(ticket==null||ticket.owner!=ticketOwner)throw bad("Copy ticket belongs to another run or is null");
        if(active.get(ticket.ordinal)!=ticket)throw new IllegalStateException("Copy is not active");double at=time(now);
        if(at<=ticket.releaseTime)throw bad("Positive copy settlement must be observed after its admission");
        Replica proposal=new Replica(ticket.source.file,ticket.destination,at,Acquisition.COPY_SETTLEMENT,ticket.source.origin,ticket.source.location,ticket.ordinal);
        Publication result=publish(proposal);active.remove(ticket.ordinal);byTarget.remove(new Target(ticket.source.file.getId(),ticket.destination));observedThrough=at;return result;
    }

    /**
     * Resolve a zero-byte reference using a real visible source, without consuming a copy ID.
     * @param file known zero-byte object
     * @param source registered visible holder
     * @param destination registered target
     * @param now current observation; immediate zero-byte visibility is allowed
     * @return new or already-present destination replica
     */
    public Publication resolveZeroReference(DataflowFilePlan.FileId file,DataLocation source,DataLocation destination,double now){
        DataflowFilePlan.FileDefinition definition=plan.getFile(file);requireKnown(locations,source);requireKnown(locations,destination);double at=time(now);
        if(definition.getBytes()!=0)throw bad("Only zero-byte references can be resolved without positive copy service");Replica replica=requireVisible(file,source);
        Publication result=publish(new Replica(definition,destination,at,Acquisition.ZERO_BYTE_REFERENCE,replica.origin,source,null));observedThrough=at;return result;
    }

    private Replica requireVisible(DataflowFilePlan.FileId file,DataLocation source){Replica replica=visible(file,source);if(replica==null)throw new IllegalStateException("Source does not hold a visible replica: "+file+" at "+source);return replica;}
    private Replica visible(DataflowFilePlan.FileId file,DataLocation location){Map<DataLocation,Replica> copies=replicas.get(file);return copies==null?null:copies.get(location);}
    private Publication publish(Replica proposal){
        Map<DataLocation,Replica> copies=replicas.get(proposal.file.getId());if(copies==null){copies=new TreeMap<>();replicas.put(proposal.file.getId(),copies);}
        Replica existing=copies.get(proposal.location);if(existing!=null)return new Publication(existing,false);copies.put(proposal.location,proposal);return new Publication(proposal,true);
    }
    private double time(double now){if(!Double.isFinite(now)||now<0||now<observedThrough)throw bad("Observation time must be finite, nonnegative and monotonic");return now==0?0:now;}
    private static void requireKnown(Set<DataLocation> locations,DataLocation location){if(location==null||!locations.contains(location))throw bad("Unknown data location: "+location);}
    private static <T> List<T> immutableList(Collection<T> values){return Collections.unmodifiableList(new ArrayList<>(values));}
    private static IllegalArgumentException bad(String message){return new IllegalArgumentException(message);}
    private static final class Target {
        final DataflowFilePlan.FileId file;final DataLocation location;
        Target(DataflowFilePlan.FileId file,DataLocation location){this.file=file;this.location=location;}
        @Override public int hashCode(){return 31*file.hashCode()+location.hashCode();}
        @Override public boolean equals(Object other){if(!(other instanceof Target))return false;Target target=(Target)other;return file.equals(target.file)&&location.equals(target.location);}
    }
    private static final class Attempt {
        final int task;final long job;
        Attempt(int task,long job){this.task=task;this.job=job;}
        @Override public int hashCode(){return 31*task+Long.hashCode(job);}
        @Override public boolean equals(Object other){if(!(other instanceof Attempt))return false;Attempt attempt=(Attempt)other;return task==attempt.task&&job==attempt.job;}
    }
    private static final class JobObservation {
        final DataLocation location;final double time;
        JobObservation(DataLocation location,double time){this.location=location;this.time=time;}
        boolean matches(DataLocation location,double time){return this.location.equals(location)&&this.time==time;}
    }
    private static final class Completion {
        final DataLocation location;final boolean success;final double time;
        Completion(DataLocation location,boolean success,double time){this.location=location;this.success=success;this.time=time;}
        boolean matches(DataLocation location,boolean success,double time){return this.location.equals(location)&&this.success==success&&this.time==time;}
    }
}
