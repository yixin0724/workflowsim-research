package org.workflowsim.data.v2;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.workflowsim.data.TransferContentionEngine;

/**
 * Run-owned V2 coordinator: one transaction spans service, replicas, input waiters and evidence.
 * The Kernel adapter supplies actual observed times and destinations; optional capture is never
 * the authority for release or visibility. Initial executable scope is one logical Task per Job.
 */
public final class CoherentDataflowRuntime {
    /** Explicit NF003 external source; bounded storage/output attachment is a later contract. */
    public static final DataLocation SOURCE=DataLocation.source("source");
    /** Immutable preparation quantities; references are not positive-copy traffic. */
    public static final class Preparation {
        private final double requiredBytes,isolatedSeconds;
        private final long referenceCount;
        private final int newCopies,joinedCopies;
        private Preparation(double bytes,double seconds,long references,int copies,int joins){requiredBytes=bytes;isolatedSeconds=seconds;referenceCount=references;newCopies=copies;joinedCopies=joins;}
        /** @return modeled reference bytes, not served byte area */ public double getRequiredBytes(){return requiredBytes;}
        /** @return sum of isolated full-object estimates, not observed input waiting */ public double getIsolatedSeconds(){return isolatedSeconds;}
        /** @return input-reference multiplicity */ public long getReferenceCount(){return referenceCount;}
        /** @return newly admitted positive copies */ public int getNewCopies(){return newCopies;}
        /** @return joined active copies */ public int getJoinedCopies(){return joinedCopies;}
    }
    private final boolean shared;
    private final int budget;
    private final boolean storageVersion,storeBackedInputs;
    private final List<FileLifecycleEvent> events=new ArrayList<>();
    private long dropped;
    private DataflowFilePlan plan;
    private DataTransferFabric fabric;
    private JsonObject planDocument,fabricDocument;
    private Frame current;

    /** @param shared enable cross-flow sharing @param recordBudget zero disables capture; positive bounds lifecycle records */
    public CoherentDataflowRuntime(boolean shared,int recordBudget){this(shared,recordBudget,false,false);}
    private CoherentDataflowRuntime(boolean shared,int budget,boolean storage,boolean storeInputs){if(budget<0)throw new IllegalArgumentException("Lifecycle budget cannot be negative");this.shared=shared;this.budget=budget;storageVersion=storage;storeBackedInputs=storeInputs;}
    /** @param shared inter-flow sharing @param budget zero or positive capture budget @param storeBackedInputs store commitment gates input cache/read access @return explicitly versioned storage coordinator */
    public static CoherentDataflowRuntime withStorage(boolean shared,int budget,boolean storeBackedInputs){return new CoherentDataflowRuntime(shared,budget,true,storeBackedInputs);}
    /** @return whether bounded store/output semantics are enabled */ public boolean isStorageVersion(){return storageVersion;}
    /**
     * Called once before clustering clears parser Tasks. Seeds only external objects at observation zero.
     * @param filePlan deep-frozen parsed plan
     * @param locations known typed VM and SOURCE locations
     */
    public void initializePlan(DataflowFilePlan filePlan,Collection<DataLocation> locations){
        if(current!=null||filePlan==null)throw new IllegalStateException("File plan must be installed exactly once");
        DataReplicaState replicas=new DataReplicaState(filePlan,locations);if(!locations.contains(SOURCE))throw new IllegalArgumentException("NF003 SOURCE location is missing");
        Stage staged=new Stage(new Frame(replicas,null));JsonObject encoded=FileLifecycleFormat.plan(filePlan);
        for(DataflowFilePlan.FileDefinition file:filePlan.getFiles())if(file.isExternal()){
            replicas.seedExternal(file.getId(),SOURCE,0);JsonObject p=staged.event(FileLifecycleEvent.Type.EXTERNAL_SEEDED,0);if(p!=null){p.add("fileId",FileLifecycleFormat.file(file.getId()));p.add("location",FileLifecycleFormat.location(SOURCE));}
        }
        plan=filePlan;planDocument=encoded;commit(staged);
    }
    /**
     * Install capacities/routes only after actual VM placement is known.
     * @param actualFabric immutable actual-placement fabric
     */
    public void bindFabric(DataTransferFabric actualFabric){
        if(current==null||fabric!=null||actualFabric==null)throw new IllegalStateException("Plan must precede one fabric binding");
        if(storageVersion!=(actualFabric.getSourceStorage()!=null))throw new IllegalArgumentException("Bounded storage and runtime lifecycle versions must match");
        if(!new TreeSet<>(current.replicas.snapshot().getLocations()).equals(actualFabric.getLocations()))throw new IllegalArgumentException("Replica and fabric locations disagree");
        TransferContentionEngine service=TransferContentionEngine.coherentV2(shared);for(Map.Entry<String,Double> entry:actualFabric.getResourceCapacities().entrySet())service.setEndpointCapacity(entry.getKey(),entry.getValue());
        fabric=actualFabric;fabricDocument=FileLifecycleFormat.fabric(actualFabric);current.service=service;
    }
    /** @return whether actual routes/capacities have been installed */ public boolean isFabricBound(){return fabric!=null;}
    /** @return frozen plan */ public DataflowFilePlan getFilePlan(){if(plan==null)throw new IllegalStateException("No V2 file plan");return plan;}
    /** @return defensive plan document for the new model's core manifest */ public JsonObject getFilePlanDocument(){return planDocument==null?null:planDocument.deepCopy();}
    /** @return last committed observation */ public double getCurrentTime(){return current==null?0:current.replicas.getObservedThrough();}
    /** @return active positive copies */ public int getActiveCopyCount(){return current==null?0:current.copies.size();}
    /** @return positive admissions, separate from input references */ public long getAdmittedCopyCount(){return current==null?0:current.replicas.getAdmittedCopyCount();}
    /** @return earliest service finish, or null */ public Double getNextCompletionTime(){return current==null?null:current.nextTime;}
    /** @return immutable current replica state */ public DataReplicaState.Snapshot getReplicaSnapshot(){requireBound();return current.replicas.snapshot();}
    /** @return newly data-ready Job IDs exactly once */
    public List<Integer> drainReadyJobIds(){if(current==null)return Collections.emptyList();List<Integer> result=Collections.unmodifiableList(new ArrayList<>(current.ready));current.ready.clear();return result;}
    /** @return true after all requested Job attempts and positive copies have finished */
    public boolean isQuiescent(){if(current==null)return false;if(!current.copies.isEmpty()||!current.ready.isEmpty()||!current.outputObligations.isEmpty()||!current.storeWaiters.isEmpty())return false;for(JobState job:current.jobs.values())if(job.finishedAt==null)return false;return true;}

    /**
     * Request a fixed destination only after control dependencies and workflow arrival are released.
     * @param jobId nonnegative Job attempt ID
     * @param taskIds singleton logical Task membership (initial NONE-clustering scope)
     * @param vmId actual fixed target VM
     * @param now dependency-ready observation
     * @return initial preparation quantities, not a contended-arrival prediction; storage callers
     *         must read {@link #getPreparation(int)} after data-ready for final deferred-input totals
     */
    public Preparation requestJob(int jobId,List<Integer> taskIds,int vmId,double now){
        if(storageVersion)return requestStorageJob(jobId,taskIds,vmId,now);
        requireBound();List<Integer> members=members(taskIds);DataLocation destination=DataLocation.vm(vmId);fabric.requireLocation(destination);
        if(jobId<0||current.jobs.containsKey(jobId))throw new IllegalStateException("Job input request identity is invalid or already used");
        Stage staged=step(now);Frame frame=staged.frame;
        for(int task:members)for(int parent:plan.getParentTaskIds(task))if(!frame.successfulTasks.contains(parent))throw new IllegalStateException("Logical control dependency has not successfully completed: "+parent);
        JobState job=new JobState(members,vmId);job.requestedAt=now;frame.jobs.put(jobId,job);
        JsonObject requested=staged.event(FileLifecycleEvent.Type.JOB_INPUT_REQUESTED,now);if(requested!=null){requested.addProperty("jobId",jobId);JsonArray ids=new JsonArray();for(int id:members)ids.add(id);requested.add("taskIds",ids);requested.addProperty("destinationVmId",vmId);}
        double bytes=0,seconds=0;long references=0;int copies=0,joins=0;DataReplicaState.Snapshot snapshot=frame.replicas.snapshot();
        for(DataflowFilePlan.InputDemand demand:plan.demandsFor(members)){
            DataflowFilePlan.FileId file=demand.getFile().getId();bytes=finiteSum(bytes,demand.getReferenceBytes().doubleValue());references=Math.addExact(references,demand.getReferenceCount());
            String resolution;Long ordinal=null;DataLocation source;
            DataReplicaState.Replica local=frame.replicas.getReplica(file,destination);
            if(local!=null){resolution="LOCAL";source=destination;}
            else if(demand.getFile().getBytes()==0){
                ReplicaTransferSelector.Decision decision=ReplicaTransferSelector.select(snapshot,fabric,file,destination);requireSource(decision);
                source=decision.getSourceReplica().getLocation();frame.replicas.resolveZeroReference(file,source,destination,now);resolution="ZERO";
            }else{
                DataReplicaState.CopyTicket ticket=frame.replicas.getInFlight(file,destination);ActiveCopy active;
                if(ticket!=null){active=frame.copies.get(ticket.getOrdinal());if(active==null)throw new IllegalStateException("Replica ticket has no service flow");resolution="JOIN_EXISTING";joins++;}
                else{
                    ReplicaTransferSelector.Decision decision=ReplicaTransferSelector.select(snapshot,fabric,file,destination);requireSource(decision);
                    ticket=frame.replicas.admitCopy(file,decision.getSourceReplica().getLocation(),destination,now);
                    TransferContentionEngine.AdvanceResult result=frame.service.addTransfer(ticket.getOrdinal(),demand.getFile().getBytes(),decision.getRoute().getResources(),decision.getRoute().getStandaloneRateBytesPerSecond(),now);
                    if(!result.getCompletedTransferIds().isEmpty())throw new IllegalStateException("Same-observation admission unexpectedly completed old service");frame.nextTime=result.getNextCompletionTime();
                    active=new ActiveCopy(ticket,decision);frame.copies.put(ticket.getOrdinal(),active);resolution="NEW_COPY";copies++;
                    JsonObject p=staged.event(FileLifecycleEvent.Type.COPY_ADMITTED,now);if(p!=null){p.addProperty("copyOrdinal",ticket.getOrdinal());p.add("fileId",FileLifecycleFormat.file(file));p.addProperty("bytes",demand.getFile().getBytes());p.add("sourceReplica",FileLifecycleFormat.replica(ticket.getSourceReplica()));p.addProperty("destinationVmId",vmId);JsonArray path=new JsonArray();for(String key:decision.getRoute().getResources())path.add(key);p.add("resources",path);p.addProperty("standaloneRate",decision.getRoute().getStandaloneRateBytesPerSecond());p.addProperty("isolatedSeconds",decision.getIsolatedSeconds());}
                }
                source=ticket.getSourceReplica().getLocation();ordinal=ticket.getOrdinal();seconds=finiteSum(seconds,active.decision.getIsolatedSeconds());job.pending.add(file);Target target=new Target(file,destination);
                LinkedHashSet<Integer> waiters=frame.waiters.get(target);if(waiters==null){waiters=new LinkedHashSet<>();frame.waiters.put(target,waiters);}waiters.add(jobId);
            }
            JsonObject p=staged.event(FileLifecycleEvent.Type.INPUT_RESOLVED,now);if(p!=null){p.addProperty("jobId",jobId);p.add("fileId",FileLifecycleFormat.file(file));p.addProperty("referenceCount",demand.getReferenceCount());p.addProperty("resolution",resolution);p.addProperty("copyOrdinal",ordinal);p.add("source",FileLifecycleFormat.location(source));}
        }
        if(references>Integer.MAX_VALUE)throw new IllegalArgumentException("Reference count exceeds the supported core-event count range");
        job.preparation=new Preparation(bytes,seconds,references,copies,joins);if(job.pending.isEmpty())ready(staged,jobId,now);commit(staged);return job.preparation;
    }
    private Preparation requestStorageJob(int jobId,List<Integer> taskIds,int vmId,double now){
        requireBound();List<Integer> members=members(taskIds);DataLocation destination=DataLocation.vm(vmId);fabric.requireLocation(destination);
        if(jobId<0||current.jobs.containsKey(jobId))throw new IllegalStateException("Job input request identity is invalid or already used");Stage staged=step(now);Frame frame=staged.frame;
        for(int task:members)for(int parent:plan.getParentTaskIds(task))if(!frame.successfulTasks.contains(parent))throw new IllegalStateException("Logical control dependency has not successfully completed: "+parent);
        JobState job=new JobState(members,vmId);job.requestedAt=now;frame.jobs.put(jobId,job);JsonObject requested=staged.event(FileLifecycleEvent.Type.JOB_INPUT_REQUESTED,now);
        if(requested!=null){requested.addProperty("jobId",jobId);JsonArray ids=new JsonArray();for(int id:members)ids.add(id);requested.add("taskIds",ids);requested.addProperty("destinationVmId",vmId);}
        for(DataflowFilePlan.InputDemand demand:plan.demandsFor(members)){
            DataflowFilePlan.FileId file=demand.getFile().getId();job.referenceBytes=finiteSum(job.referenceBytes,demand.getReferenceBytes().doubleValue());job.referenceCount=Math.addExact(job.referenceCount,demand.getReferenceCount());job.pending.add(file);
            if(storeBackedInputs&&frame.replicas.getReplica(file,SOURCE)==null){
                job.unresolvedStore.put(file,demand.getReferenceCount());LinkedHashSet<Integer> waiting=frame.storeWaiters.get(file);if(waiting==null){waiting=new LinkedHashSet<>();frame.storeWaiters.put(file,waiting);}waiting.add(jobId);
                JsonObject p=staged.event(FileLifecycleEvent.Type.INPUT_WAITING_FOR_STORE,now);if(p!=null){p.addProperty("jobId",jobId);p.add("fileId",FileLifecycleFormat.file(file));p.addProperty("referenceCount",demand.getReferenceCount());}
            }else resolveStorageInput(staged,jobId,file,demand.getReferenceCount(),now,false);
        }
        if(job.referenceCount>Integer.MAX_VALUE)throw new IllegalArgumentException("Reference count exceeds core-event range");job.preparation=new Preparation(job.referenceBytes,job.isolatedInputSeconds,job.referenceCount,job.newCopies,job.joinedCopies);if(job.pending.isEmpty())ready(staged,jobId,now);commit(staged);return job.preparation;
    }
    private void resolveStorageInput(Stage staged,int jobId,DataflowFilePlan.FileId file,long references,double now,boolean release){
        Frame frame=staged.frame;JobState job=frame.jobs.get(jobId);DataLocation destination=DataLocation.vm(job.vm);String resolution;Long ordinal=null;DataLocation source;
        if(storeBackedInputs&&frame.replicas.getReplica(file,SOURCE)==null)throw new IllegalStateException("Store input resolution precedes store commitment");
        DataReplicaState.Replica local=frame.replicas.getReplica(file,destination);
        if(local!=null){resolution="LOCAL";source=destination;job.pending.remove(file);}
        else if(plan.getFile(file).getBytes()==0){
            ReplicaTransferSelector.Decision decision=storageInputDecision(frame,file,destination);requireSource(decision);source=decision.getSourceReplica().getLocation();frame.replicas.resolveZeroReference(file,source,destination,now);resolution="ZERO";job.pending.remove(file);
        }else{
            DataReplicaState.CopyTicket ticket=frame.replicas.getInFlight(file,destination);ActiveCopy copy;
            if(ticket!=null){copy=frame.copies.get(ticket.getOrdinal());if(copy==null||!copy.purpose.equals("INPUT"))throw new IllegalStateException("Input ticket has no input service flow");resolution="JOIN_EXISTING";job.joinedCopies++;}
            else{ReplicaTransferSelector.Decision decision=storageInputDecision(frame,file,destination);requireSource(decision);copy=admitStorageCopy(staged,decision,jobId,"INPUT",now);ticket=copy.ticket;resolution="NEW_COPY";job.newCopies++;}
            source=ticket.getSourceReplica().getLocation();ordinal=ticket.getOrdinal();job.isolatedInputSeconds=finiteSum(job.isolatedInputSeconds,copy.decision.getIsolatedSeconds());Target target=new Target(file,destination);LinkedHashSet<Integer> waiting=frame.waiters.get(target);if(waiting==null){waiting=new LinkedHashSet<>();frame.waiters.put(target,waiting);}waiting.add(jobId);
        }
        JsonObject p=staged.event(FileLifecycleEvent.Type.INPUT_RESOLVED,now);if(p!=null){p.addProperty("jobId",jobId);p.add("fileId",FileLifecycleFormat.file(file));p.addProperty("referenceCount",references);p.addProperty("resolution",resolution);p.addProperty("copyOrdinal",ordinal);p.add("source",FileLifecycleFormat.location(source));}
        if(release&&job.pending.isEmpty())ready(staged,jobId,now);
    }
    private ReplicaTransferSelector.Decision storageInputDecision(Frame frame,DataflowFilePlan.FileId file,DataLocation destination){DataReplicaState.Snapshot snapshot=frame.replicas.snapshot();return storeBackedInputs?ReplicaTransferSelector.selectStoreBacked(snapshot,fabric,file,destination):ReplicaTransferSelector.select(snapshot,fabric,file,destination);}
    private ActiveCopy admitStorageCopy(Stage staged,ReplicaTransferSelector.Decision decision,int owner,String purpose,double now){
        Frame frame=staged.frame;DataReplicaState.CopyTicket ticket=frame.replicas.admitCopy(decision.getFile().getId(),decision.getSourceReplica().getLocation(),decision.getDestination(),now);
        TransferContentionEngine.AdvanceResult added=frame.service.addTransfer(ticket.getOrdinal(),decision.getFile().getBytes(),decision.getRoute().getResources(),decision.getRoute().getStandaloneRateBytesPerSecond(),now);if(!added.getCompletedTransferIds().isEmpty())throw new IllegalStateException("Admission unexpectedly completed prior service");frame.nextTime=added.getNextCompletionTime();
        ActiveCopy copy=new ActiveCopy(ticket,decision,purpose,owner);frame.copies.put(ticket.getOrdinal(),copy);JsonObject p=staged.event(FileLifecycleEvent.Type.COPY_ADMITTED,now);
        if(p!=null){p.addProperty("copyOrdinal",ticket.getOrdinal());p.add("fileId",FileLifecycleFormat.file(ticket.getFile().getId()));p.addProperty("bytes",ticket.getFile().getBytes());p.add("sourceReplica",FileLifecycleFormat.replica(ticket.getSourceReplica()));p.add("destination",FileLifecycleFormat.location(ticket.getDestination()));p.addProperty("purpose",purpose);p.addProperty("ownerJobId",owner);JsonArray path=new JsonArray();for(String key:decision.getRoute().getResources())path.add(key);p.add("resources",path);p.addProperty("standaloneRate",decision.getRoute().getStandaloneRateBytesPerSecond());p.addProperty("isolatedSeconds",decision.getIsolatedSeconds());}
        return copy;
    }
    private void materializeOutput(Stage staged,int jobId,int taskId,DataReplicaState.Replica replica,double now){
        Frame frame=staged.frame;DataflowFilePlan.FileId file=replica.getFile().getId();frame.outputObligations.add(file);String resolution;Long ordinal=null;boolean published=false;
        if(frame.replicas.getReplica(file,SOURCE)!=null){resolution="ALREADY_STORED";frame.outputObligations.remove(file);}
        else if(replica.getFile().getBytes()==0){frame.replicas.commitZeroOutput(file,replica.getLocation(),SOURCE,now);resolution="ZERO";frame.outputObligations.remove(file);published=true;}
        else{DataReplicaState.CopyTicket existing=frame.replicas.getInFlight(file,SOURCE);if(existing==null){ActiveCopy copy=admitStorageCopy(staged,ReplicaTransferSelector.materializeToStore(replica,fabric,now),jobId,"OUTPUT",now);ordinal=copy.ticket.getOrdinal();resolution="NEW_COPY";}else{ActiveCopy copy=frame.copies.get(existing.getOrdinal());if(copy==null||!copy.purpose.equals("OUTPUT"))throw new IllegalStateException("Store ticket has no output flow");ordinal=existing.getOrdinal();resolution="JOIN_EXISTING";}}
        JsonObject p=staged.event(FileLifecycleEvent.Type.OUTPUT_RESOLVED,now);if(p!=null){p.addProperty("jobId",jobId);p.addProperty("taskId",taskId);p.add("fileId",FileLifecycleFormat.file(file));p.addProperty("resolution",resolution);p.addProperty("copyOrdinal",ordinal);p.add("source",FileLifecycleFormat.location(replica.getLocation()));}
        if(published)resumeStoreInputs(staged,file,now);
    }
    private void resumeStoreInputs(Stage staged,DataflowFilePlan.FileId file,double now){
        LinkedHashSet<Integer> waiting=staged.frame.storeWaiters.remove(file);if(waiting==null)return;
        for(int id:waiting){JobState job=staged.frame.jobs.get(id);Long count=job.unresolvedStore.remove(file);if(count==null||!job.pending.contains(file))throw new IllegalStateException("Deferred input index disagrees");resolveStorageInput(staged,id,file,count,now,true);}
    }
    /** @param now actual outer completion-check observation */ public void advance(double now){commit(step(now));}
    /**
     * Enforce a pre-existing data-ready release; a CPU submission cannot create its own permission.
     * @param jobId prepared Job attempt
     * @param taskIds actual singleton membership
     * @param vmId actual submitted VM
     * @param now CPU submission observation
     */
    public void cpuStarted(int jobId,List<Integer> taskIds,int vmId,double now){
        requireBound();JobState existing=job(jobId,taskIds,vmId,current);if(existing.readyAt==null||existing.startedAt!=null)throw new IllegalStateException("CPU submission requires one existing data-ready release");
        Stage staged=step(now);JobState job=job(jobId,taskIds,vmId,staged.frame);
        for(DataflowFilePlan.InputDemand input:plan.demandsFor(job.tasks))if(staged.frame.replicas.getReplica(input.getFile().getId(),DataLocation.vm(vmId))==null)throw new IllegalStateException("CPU input replica is not visible");
        job.startedAt=now;JsonObject p=staged.event(FileLifecycleEvent.Type.JOB_CPU_STARTED,now);if(p!=null){p.addProperty("jobId",jobId);p.addProperty("vmId",vmId);}commit(staged);
    }
    /**
     * Publish actual Task outcomes after failure determination, before delayed engine return.
     * @param jobId submitted attempt
     * @param taskIds actual singleton Task membership
     * @param vmId actual execution VM
     * @param successes terminal per-Task success flags
     * @param now observed datacenter Job completion
     */
    public void jobFinished(int jobId,List<Integer> taskIds,int vmId,List<Boolean> successes,double now){
        requireBound();JobState existing=job(jobId,taskIds,vmId,current);if(existing.startedAt==null||existing.finishedAt!=null||successes==null||successes.size()!=existing.tasks.size()||successes.contains(null))throw new IllegalStateException("Invalid terminal Job outcome");
        Stage staged=step(now);JobState job=job(jobId,taskIds,vmId,staged.frame);
        for(int i=0;i<job.tasks.size();i++){
            int task=job.tasks.get(i);boolean success=successes.get(i);List<DataReplicaState.Publication> publications=staged.frame.replicas.recordTaskCompletion(task,jobId,DataLocation.vm(vmId),success,now);
            JsonObject p=staged.event(FileLifecycleEvent.Type.TASK_FINISHED,now);if(p!=null){p.addProperty("taskId",task);p.addProperty("jobId",jobId);p.addProperty("vmId",vmId);p.addProperty("success",success);}
            if(success)staged.frame.successfulTasks.add(task);for(DataReplicaState.Publication publication:publications){visible(staged,publication.getReplica().getFile().getId(),publication.getReplica().getLocation(),now);if(storageVersion)materializeOutput(staged,jobId,task,publication.getReplica(),now);}
        }
        job.finishedAt=now;commit(staged);
    }
    /** @return frozen bounded capture, null when OFF; no service advancement occurs */
    public FileLifecycleEvidence captureEvidence(){return storageVersion?null:captureCommon();}
    /** @return storage-version capture, never a V2 certificate; null when OFF or original V2 */
    public StorageLifecycleEvidence captureStorageEvidence(){return !storageVersion||budget==0?null:StorageLifecycleEvidence.capture(captureCommon(),storeBackedInputs);}
    private FileLifecycleEvidence captureCommon(){if(budget==0)return null;requireBound();return FileLifecycleEvidence.capture(budget,dropped,getCurrentTime(),shared,planDocument,fabricDocument,events);}
    /** @param jobId input-ready attempt @return final nominal input quantities (may be deferred under storage policy) */
    public Preparation getPreparation(int jobId){JobState job=current.jobs.get(jobId);if(job==null||job.readyAt==null)throw new IllegalStateException("Input preparation is not final");return job.preparation;}
    /** @param jobId input-ready attempt @return observed preparation latency, not a sum of overlapping waits */
    public double getObservedInputPreparationSeconds(int jobId){JobState job=current.jobs.get(jobId);if(job==null||job.readyAt==null)throw new IllegalStateException("Input preparation is not final");return job.readyAt-job.requestedAt;}

    private Stage step(double now){
        requireBound();Stage staged=new Stage(new Frame(current));TransferContentionEngine.AdvanceResult result=staged.frame.service.advance(now);staged.frame.nextTime=result.getNextCompletionTime();staged.frame.replicas.advanceTo(now);
        if(result.getCompletedTransferIds().size()!=result.getCompletionObservations().size())throw new IllegalStateException("Checked service omitted completion observations");
        for(TransferContentionEngine.CompletionObservation completion:result.getCompletionObservations()){
            ActiveCopy copy=staged.frame.copies.remove(completion.getTransferId());if(copy==null)throw new IllegalStateException("Completed service has no file copy identity");
            staged.frame.replicas.settleCopy(copy.ticket,now);JsonObject p=staged.event(FileLifecycleEvent.Type.COPY_SETTLED,now);if(p!=null){p.addProperty("copyOrdinal",copy.ticket.getOrdinal());p.addProperty("effectiveTime",completion.getEffectiveTime());p.addProperty("remainingAfterService",completion.getRemainingAfterService());}
            visible(staged,copy.ticket.getFile().getId(),copy.ticket.getDestination(),now);
            if(storageVersion&&copy.ticket.getDestination().equals(SOURCE)){staged.frame.outputObligations.remove(copy.ticket.getFile().getId());resumeStoreInputs(staged,copy.ticket.getFile().getId(),now);}
        }
        return staged;
    }
    private void visible(Stage staged,DataflowFilePlan.FileId file,DataLocation location,double now){
        Set<Integer> waiters=staged.frame.waiters.remove(new Target(file,location));if(waiters==null)return;
        for(int id:waiters){JobState job=staged.frame.jobs.get(id);if(job==null||!job.pending.remove(file))throw new IllegalStateException("File waiter is inconsistent");if(job.pending.isEmpty())ready(staged,id,now);}
    }
    private void ready(Stage staged,int id,double now){JobState job=staged.frame.jobs.get(id);if(job.readyAt!=null)throw new IllegalStateException("Job data-ready release duplicated");if(!job.unresolvedStore.isEmpty())throw new IllegalStateException("Store visibility is not target readiness");if(storageVersion)job.preparation=new Preparation(job.referenceBytes,job.isolatedInputSeconds,job.referenceCount,job.newCopies,job.joinedCopies);job.readyAt=now;staged.frame.ready.add(id);JsonObject p=staged.event(FileLifecycleEvent.Type.JOB_DATA_READY,now);if(p!=null)p.addProperty("jobId",id);}
    private void commit(Stage staged){
        List<FileLifecycleEvent> additions=new ArrayList<>();long seq=events.size()+1L;for(PendingEvent event:staged.pending)additions.add(FileLifecycleEvent.of(seq++,event.time,event.type,event.payload));
        long lost=Math.addExact(dropped,staged.dropped);events.addAll(additions);dropped=lost;current=staged.frame;
    }
    private List<Integer> members(List<Integer> ids){if(ids==null||ids.size()!=1||ids.get(0)==null)throw new IllegalArgumentException("V2 runtime initially requires one logical Task per Job");plan.getInputReferences(ids.get(0));return Collections.unmodifiableList(new ArrayList<>(ids));}
    private JobState job(int id,List<Integer> tasks,int vm,Frame frame){JobState job=frame.jobs.get(id);if(job==null||job.vm!=vm||!job.tasks.equals(tasks))throw new IllegalStateException("Job membership or fixed VM changed after input request");return job;}
    private static void requireSource(ReplicaTransferSelector.Decision decision){if(decision.getSourceReplica()==null)throw new IllegalStateException("Dependency-ready input has no published source replica");}
    private static double finiteSum(double a,double b){double sum=a+b;if(!Double.isFinite(b)||b<0||!Double.isFinite(sum))throw new IllegalArgumentException("V2 core-event aggregate is not representable");return sum;}
    private void requireBound(){if(current==null||fabric==null||current.service==null)throw new IllegalStateException("V2 runtime plan and actual fabric are not initialized");}
    private final class Stage {
        final Frame frame;final List<PendingEvent> pending=new ArrayList<>();long dropped;
        Stage(Frame frame){this.frame=frame;}
        JsonObject event(FileLifecycleEvent.Type type,double time){if(budget==0)return null;if(events.size()+pending.size()==budget){dropped++;return null;}JsonObject payload=new JsonObject();pending.add(new PendingEvent(type,time,payload));return payload;}
    }
    private static final class PendingEvent {final FileLifecycleEvent.Type type;final double time;final JsonObject payload;PendingEvent(FileLifecycleEvent.Type type,double time,JsonObject payload){this.type=type;this.time=time;this.payload=payload;}}
    private static final class ActiveCopy {final DataReplicaState.CopyTicket ticket;final ReplicaTransferSelector.Decision decision;final String purpose;final int ownerJobId;ActiveCopy(DataReplicaState.CopyTicket ticket,ReplicaTransferSelector.Decision decision){this(ticket,decision,"INPUT",-1);}ActiveCopy(DataReplicaState.CopyTicket ticket,ReplicaTransferSelector.Decision decision,String purpose,int owner){this.ticket=ticket;this.decision=decision;this.purpose=purpose;ownerJobId=owner;}}
    private static final class JobState {
        final List<Integer> tasks;final int vm;final Set<DataflowFilePlan.FileId> pending=new TreeSet<>();final Map<DataflowFilePlan.FileId,Long> unresolvedStore=new LinkedHashMap<>();Double readyAt,startedAt,finishedAt;Preparation preparation;double requestedAt,referenceBytes,isolatedInputSeconds;long referenceCount;int newCopies,joinedCopies;
        JobState(List<Integer> tasks,int vm){this.tasks=tasks;this.vm=vm;}
        JobState(JobState from){tasks=from.tasks;vm=from.vm;pending.addAll(from.pending);readyAt=from.readyAt;startedAt=from.startedAt;finishedAt=from.finishedAt;preparation=from.preparation;requestedAt=from.requestedAt;referenceBytes=from.referenceBytes;referenceCount=from.referenceCount;isolatedInputSeconds=from.isolatedInputSeconds;newCopies=from.newCopies;joinedCopies=from.joinedCopies;unresolvedStore.putAll(from.unresolvedStore);}
    }
    private static final class Target {
        final DataflowFilePlan.FileId file;final DataLocation location;Target(DataflowFilePlan.FileId file,DataLocation location){this.file=file;this.location=location;}
        @Override public int hashCode(){return 31*file.hashCode()+location.hashCode();}
        @Override public boolean equals(Object value){if(!(value instanceof Target))return false;Target other=(Target)value;return file.equals(other.file)&&location.equals(other.location);}
    }
    private static final class Frame {
        final DataReplicaState replicas;TransferContentionEngine service;Double nextTime;
        final Map<DataflowFilePlan.FileId,LinkedHashSet<Integer>> storeWaiters=new LinkedHashMap<>();final Set<DataflowFilePlan.FileId> outputObligations=new TreeSet<>();
        final Map<Integer,JobState> jobs=new LinkedHashMap<>();final Map<Target,LinkedHashSet<Integer>> waiters=new HashMap<>();final Map<Long,ActiveCopy> copies=new LinkedHashMap<>();final Set<Integer> successfulTasks=new TreeSet<>();final List<Integer> ready=new ArrayList<>();
        Frame(DataReplicaState replicas,TransferContentionEngine service){this.replicas=replicas;this.service=service;}
        Frame(Frame from){replicas=from.replicas.fork();service=from.service==null?null:from.service.fork();nextTime=from.nextTime;for(Map.Entry<Integer,JobState> entry:from.jobs.entrySet())jobs.put(entry.getKey(),new JobState(entry.getValue()));for(Map.Entry<Target,LinkedHashSet<Integer>> entry:from.waiters.entrySet())waiters.put(entry.getKey(),new LinkedHashSet<>(entry.getValue()));copies.putAll(from.copies);successfulTasks.addAll(from.successfulTasks);ready.addAll(from.ready);outputObligations.addAll(from.outputObligations);for(Map.Entry<DataflowFilePlan.FileId,LinkedHashSet<Integer>> entry:from.storeWaiters.entrySet())storeWaiters.put(entry.getKey(),new LinkedHashSet<>(entry.getValue()));}
    }
}
