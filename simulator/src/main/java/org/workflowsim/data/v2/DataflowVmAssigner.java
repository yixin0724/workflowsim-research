package org.workflowsim.data.v2;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.workflowsim.Job;
import org.workflowsim.Task;
import org.workflowsim.utils.Parameters;
import org.workflowsim.utils.TaskExecutionModel;

/**
 * Run-owned control-ready destination binding. The myopic score uses nominal remaining input
 * paths and estimated CPU reservations, not a contention-exact completion forecast. Binding and
 * reservations commit only after the real input request succeeds. No candidate admits traffic.
 */
public final class DataflowVmAssigner {
    public static final String AUDIT_SCOPE="BINDING_NOMINAL_BOUNDS_AND_LOGGED_SCORE_V1";
    public static final String INPUT_ESTIMATE="MAX_FILE_NOMINAL_REMAINING_CONDITIONAL_STORE_V1";
    public static final String CPU_ESTIMATE="ACTIVE_COMPUTE_RESERVATION_FROM_OBSERVED_START_V1";
    /** Frozen actual-created VM identity and compute capability, never a mutable VM handle. */
    public static final class VmOption {
        private final int id,pes;private final double mips;
        public VmOption(int id,double mips,int pes){if(id<0||!Double.isFinite(mips)||mips<=0||pes<=0)throw new IllegalArgumentException("Invalid actual VM option");this.id=id;this.mips=mips;this.pes=pes;}
        public int getId(){return id;}public int getPes(){return pes;}public double getMips(){return mips;}
    }
    /** Immutable committed action and compact reproducible decision input summaries. */
    public static final class Decision {
        private final long sequence;private final int taskId,jobId,vmId;private final boolean reused;private final double observedThrough;
        private final Map<String,Object> attributes;
        Decision(long sequence,int taskId,int jobId,int vmId,boolean reused,double time,List<Map<String,Object>> scores,DataflowObservation observation){
            this.sequence=sequence;this.taskId=taskId;this.jobId=jobId;this.vmId=vmId;this.reused=reused;observedThrough=time;
            Map<String,Object> summary=map();summary.put("contract",observation.getContract());summary.put("status",observation.getStatus().name());summary.put("storageVersion",observation.isStorageVersion());summary.put("storeBackedInputs",observation.isStoreBackedInputs());summary.put("interFlowSharing",observation.isShared());summary.put("observedThrough",time);summary.put("serviceThrough",observation.getService().getObservedThrough());summary.put("activeCopyCount",observation.getActiveCopies().size());summary.put("activeJobCount",observation.getActiveJobs().size());summary.put("pendingOutputFileCount",observation.getPendingOutputFiles().size());summary.put("scopedFileCount",observation.getFiles().size());
            Map<String,Object> values=map();values.put("auditScope",AUDIT_SCOPE);values.put("assignmentMode",DataflowAssignmentConfig.Mode.CONTROL_READY_ONLINE_ASSIGNMENT_V1.name());values.put("assignmentPolicy",DataflowAssignmentConfig.POLICY);values.put("inputEstimateSemantics",INPUT_ESTIMATE);values.put("cpuReservationSemantics",CPU_ESTIMATE);values.put("assignmentSequence",sequence);values.put("binding",reused?"RETRY_REUSE":"INITIAL");values.put("selectedVmId",vmId);values.put("observation",freeze(summary));values.put("candidates",Collections.unmodifiableList(new ArrayList<>(scores)));attributes=freeze(values);
        }
        public long getSequence(){return sequence;}public int getTaskId(){return taskId;}public int getJobId(){return jobId;}public int getVmId(){return vmId;}public boolean isReusedBinding(){return reused;}public double getObservedThrough(){return observedThrough;}public Map<String,Object> getAttributes(){return attributes;}
    }
    public static final class Prepared {
        private final Decision decision;private final CoherentDataflowRuntime.Preparation preparation;
        Prepared(Decision decision,CoherentDataflowRuntime.Preparation preparation){this.decision=decision;this.preparation=preparation;}
        public Decision getDecision(){return decision;}public CoherentDataflowRuntime.Preparation getPreparation(){return preparation;}
    }
    private static final class Reservation {final int vm;final double seconds;Reservation(int vm,double seconds){this.vm=vm;this.seconds=seconds;}}
    private static final class InputEstimate {double seconds;boolean conditionalStore;int joined;}
    private final CoherentDataflowRuntime runtime;
    private final Map<Integer,VmOption> vms;
    private final Map<Integer,Integer> bindings=new LinkedHashMap<>();
    private final Map<Integer,Reservation> reservations=new LinkedHashMap<>();
    private long sequence;
    public DataflowVmAssigner(CoherentDataflowRuntime runtime,Collection<VmOption> options){
        if(runtime==null||options==null||options.isEmpty())throw new IllegalArgumentException("Runtime and actual VM options are required");this.runtime=runtime;Map<Integer,VmOption> copied=new TreeMap<>();for(VmOption vm:options)if(vm==null||copied.put(vm.id,vm)!=null)throw new IllegalArgumentException("Duplicate/null actual VM option");vms=Collections.unmodifiableMap(copied);
    }
    /** @return immutable logical-Task bindings; retry attempt IDs do not create new bindings */
    public Map<Integer,Integer> getBindings(){return Collections.unmodifiableMap(new LinkedHashMap<>(bindings));}
    /**
     * Explicit closure event: settle at now, passively observe, score, then prepare actual inputs.
     * @param job singleton compute attempt, initially unbound or a correctly inherited retry
     * @param now current control-ready observation
     * @return committed action and runtime preparation; not a CPU dispatch permission
     */
    public Prepared prepare(Job job,double now){
        if(job==null||job.getClassType()!=Parameters.ClassType.COMPUTE.value||job.getTaskList()==null||job.getTaskList().size()!=1)throw new IllegalArgumentException("Online dataflow binding requires a singleton compute Job");
        Task task=job.getTaskList().get(0);int taskId=task.getCloudletId(),jobId=job.getCloudletId();Integer bound=bindings.get(taskId);
        if(jobId<0||reservations.containsKey(jobId))throw new IllegalArgumentException("Attempt identity is invalid or already reserved");
        if(bound==null){if(job.getVmId()!=-1||task.getVmId()!=-1)throw new IllegalStateException("Initial online dataflow Task/Job must be unbound");}
        else if(job.getVmId()!=bound||task.getVmId()!=bound)throw new IllegalStateException("NOOP retry must inherit its logical Task VM binding");
        List<DataflowFilePlan.InputDemand> demands=runtime.getFilePlan().demandsFor(Collections.singletonList(taskId));List<DataflowFilePlan.FileId> files=new ArrayList<>();for(DataflowFilePlan.InputDemand demand:demands)files.add(demand.getFile().getId());
        long next=Math.addExact(sequence,1);runtime.advance(now);DataflowObservation observation=runtime.observeFiles(files);if(!observation.isBound())throw new IllegalStateException("Online binding requires actual VM/fabric creation");
        if(observation.getObservedThrough()!=now)throw new IllegalStateException("Observation watermark differs from decision boundary");
        for(int active:observation.getActiveJobs().keySet())if(!reservations.containsKey(active))throw new IllegalStateException("Active compute attempt has no policy-owned CPU reservation: "+active);
        java.util.Set<Integer> actualIds=new java.util.TreeSet<>();for(DataLocation location:observation.getFabric().getLocations())if(location.getKind()==DataLocation.Kind.VM)actualIds.add(location.getVmId());if(!actualIds.equals(vms.keySet()))throw new IllegalArgumentException("Assignment candidates must cover exactly the actual fabric VMs");
        for(VmOption vm:vms.values())observation.getFabric().requireLocation(DataLocation.vm(vm.id));
        List<Map<String,Object>> scores=new ArrayList<>();VmOption chosen=null;double chosenScore=0,chosenCompute=0;
        for(VmOption vm:vms.values()){
            if(bound!=null&&bound!=vm.id)continue;Map<String,Object> score=map();score.put("vmId",vm.id);boolean compatible=vm.pes>=task.getNumberOfPes()&&vm.pes>=job.getNumberOfPes();score.put("compatible",compatible);
            if(!compatible){score.put("inputSeconds",null);score.put("cpuAvailableAt",null);score.put("computeSeconds",null);score.put("scoreFinishSeconds",null);score.put("conditionalStoreWait",null);score.put("joinedInputCopies",null);scores.add(freeze(score));continue;}
            double compute=TaskExecutionModel.executionSeconds(task,vm.id,vm.mips);InputEstimate inputs=estimateInputs(observation,demands,DataLocation.vm(vm.id));double cpu=cpuAvailable(observation,vm.id,now);double finish=addTime(Math.max(addTime(now,inputs.seconds),cpu),compute);
            score.put("inputSeconds",inputs.seconds);score.put("cpuAvailableAt",cpu);score.put("computeSeconds",compute);score.put("scoreFinishSeconds",finish);score.put("conditionalStoreWait",inputs.conditionalStore);score.put("joinedInputCopies",inputs.joined);scores.add(freeze(score));
            if(chosen==null||finish<chosenScore||(finish==chosenScore&&vm.id<chosen.id)){chosen=vm;chosenScore=finish;chosenCompute=compute;}
        }
        if(chosen==null)throw new IllegalArgumentException("No actual VM has compatible PEs and a finite dataflow score");Decision decision=new Decision(next,taskId,jobId,chosen.id,bound!=null,now,scores,observation);
        CoherentDataflowRuntime.Preparation prepared=runtime.requestJob(jobId,Collections.singletonList(taskId),chosen.id,now);
        // No potentially failing numerical work remains after the runtime transaction succeeds.
        for(Iterator<Map.Entry<Integer,Reservation>> it=reservations.entrySet().iterator();it.hasNext();)if(!observation.getActiveJobs().containsKey(it.next().getKey()))it.remove();
        reservations.put(jobId,new Reservation(chosen.id,chosenCompute));if(bound==null)bindings.put(taskId,chosen.id);sequence=next;job.setVmId(chosen.id);task.setVmId(chosen.id);return new Prepared(decision,prepared);
    }
    private double cpuAvailable(DataflowObservation observation,int vm,double now){
        double work=0;for(Map.Entry<Integer,Reservation> entry:reservations.entrySet()){Reservation reservation=entry.getValue();if(reservation.vm!=vm)continue;DataflowObservation.JobView job=observation.getActiveJobs().get(entry.getKey());if(job==null)continue;double remaining=reservation.seconds;if(job.getCpuStartedAt()!=null)remaining=Math.max(0,remaining-(now-job.getCpuStartedAt()));work=addFinite(work,remaining);}return addTime(now,work);
    }
    private static InputEstimate estimateInputs(DataflowObservation observation,List<DataflowFilePlan.InputDemand> demands,DataLocation destination){
        InputEstimate estimate=new InputEstimate();for(DataflowFilePlan.InputDemand demand:demands){DataflowObservation.FileView file=observation.getFile(demand.getFile().getId());double delay=0;
            if(observation.isStoreBackedInputs()&&file.getReplica(CoherentDataflowRuntime.SOURCE)==null){
                DataflowObservation.CopyView output=observation.getActiveCopy(demand.getFile().getId(),CoherentDataflowRuntime.SOURCE);if(output==null||!output.getPurpose().equals("OUTPUT"))throw new IllegalStateException("Required SOURCE commit is not visible and has no active materialization");
                delay=ratio(output.getService().getRemainingBytes(),output.getService().getNominalRateBytesPerSecond());estimate.conditionalStore=true;
                if(file.getReplica(destination)==null&&demand.getFile().getBytes()>0)delay=addFinite(delay,ratio(demand.getFile().getBytes(),observation.getFabric().route(CoherentDataflowRuntime.SOURCE,destination).getStandaloneRateBytesPerSecond()));
            }else if(file.getReplica(destination)!=null){delay=0;}
            else{
                DataflowObservation.CopyView existing=observation.getActiveCopy(demand.getFile().getId(),destination);
                if(existing!=null){if(!existing.getPurpose().equals("INPUT"))throw new IllegalStateException("Input target has a non-input copy");delay=ratio(existing.getService().getRemainingBytes(),existing.getService().getNominalRateBytesPerSecond());estimate.joined++;}
                else{
                    List<DataReplicaState.Replica> sources=observation.isStoreBackedInputs()?Collections.singletonList(file.getReplica(CoherentDataflowRuntime.SOURCE)):file.getVisibleReplicas();if(sources.isEmpty()||sources.get(0)==null)throw new IllegalStateException("No observed replica for required input");double rate=0;DataLocation selected=null;
                    for(DataReplicaState.Replica source:sources){if(source.getVisibleAt()>observation.getObservedThrough())throw new IllegalStateException("Future replica in observation");if(demand.getFile().getBytes()==0)continue;double candidate=observation.getFabric().route(source.getLocation(),destination).getStandaloneRateBytesPerSecond();if(selected==null||candidate>rate||(candidate==rate&&source.getLocation().compareTo(selected)<0)){selected=source.getLocation();rate=candidate;}}
                    if(demand.getFile().getBytes()>0)delay=ratio(demand.getFile().getBytes(),rate);
                }
            }
            estimate.seconds=Math.max(estimate.seconds,delay);
        }return estimate;
    }
    private static double ratio(double bytes,double rate){double seconds=bytes/rate;if(!Double.isFinite(seconds)||seconds<=0)throw new IllegalArgumentException("Nominal remaining input duration is not representable");return seconds;}
    private static double addFinite(double a,double b){double sum=a+b;if(!Double.isFinite(sum)||a<0||b<0)throw new IllegalArgumentException("Dataflow estimate overflow");return sum;}
    private static double addTime(double time,double delay){double result=addFinite(time,delay);if(delay>0&&result<=time)throw new IllegalArgumentException("Dataflow estimate cannot advance the binary64 clock");return result;}
    private static Map<String,Object> map(){return new LinkedHashMap<>();}
    private static Map<String,Object> freeze(Map<String,Object> values){return Collections.unmodifiableMap(values);}
}
