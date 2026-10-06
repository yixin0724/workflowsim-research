package org.workflowsim.data.v2;

import static org.junit.jupiter.api.Assertions.*;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.workflowsim.FileItem;
import org.workflowsim.Job;
import org.workflowsim.Task;
import org.workflowsim.utils.Parameters;

class DataflowVmAssignerTest {
    private static final Gson JSON=new GsonBuilder().serializeNulls().create();
    @Test void controlReadyTaskBindsByActualComputeCostNotVmIndex(){
        Task t=task(1,1000);CoherentDataflowRuntime r=runtime(false,false,2000,10,100,t);DataflowVmAssigner a=assigner(r,100,1000,1,1);Job j=job(10,t);assertEquals(42,a.prepare(j,0).getDecision().getVmId());assertEquals(42,j.getVmId());assertEquals(42,t.getVmId());assertEquals(Integer.valueOf(42),a.getBindings().get(1));assertEquals(DataflowObservation.JobPhase.READY_FOR_CPU,r.observe().getActiveJobs().get(10).getPhase());
    }
    @Test void observedInputBandwidthChangesDestination(){
        Task t=task(1,1000,in("x",1000));CoherentDataflowRuntime r=runtime(false,false,2000,10,100,t);DataflowVmAssigner.Prepared result=assigner(r,1000,1000,1,1).prepare(job(10,t),0);assertEquals(42,result.getDecision().getVmId());assertEquals(10.0,candidate(result.getDecision(),42).get("inputSeconds"));assertEquals(100.0,candidate(result.getDecision(),7).get("inputSeconds"));
    }
    @Test void cacheLocalityCanBeatTheFasterCpu(){
        Task p=task(1,1000,out("x",10000)),c=task(2,100,in("x",10000));edge(p,c);CoherentDataflowRuntime r=runtime(false,false,2000,10,100,p,c);produce(r,10,p,7,0,1,true);DataflowVmAssigner.Prepared result=assigner(r,100,1000,1,1).prepare(job(20,c),1);assertEquals(7,result.getDecision().getVmId());assertEquals(0.0,candidate(result.getDecision(),7).get("inputSeconds"));assertEquals(0,result.getPreparation().getNewCopies());
    }
    @Test void virtualCpuReservationsAffectLaterOnlineAssignments(){
        Task a=task(1,1000),b=task(2,1000),c=task(3,1000);CoherentDataflowRuntime r=runtime(false,false,2000,10,10,a,b,c);DataflowVmAssigner policy=assigner(r,1000,1000,1,1);assertEquals(7,policy.prepare(job(10,a),0).getDecision().getVmId());assertEquals(42,policy.prepare(job(20,b),0).getDecision().getVmId());DataflowVmAssigner.Decision last=policy.prepare(job(30,c),0).getDecision();assertEquals(7,last.getVmId());assertEquals(1.0,candidate(last,7).get("cpuAvailableAt"));assertEquals(2.0,candidate(last,7).get("scoreFinishSeconds"));
    }
    @Test void startedReservationUsesObservedElapsedTimeAndNeverCpuStubTelemetry(){
        Task a=task(1,10000),b=task(2,10000),c=task(3,1000);CoherentDataflowRuntime r=runtime(false,false,2000,10,10,a,b,c);DataflowVmAssigner policy=assigner(r,1000,1000,1,1);policy.prepare(job(10,a),0);r.drainReadyJobIds();r.cpuStarted(10,one(1),7,0);policy.prepare(job(20,b),0);r.drainReadyJobIds();r.cpuStarted(20,one(2),42,0);DataflowVmAssigner.Decision decision=policy.prepare(job(30,c),9.5).getDecision();assertEquals(10.0,candidate(decision,7).get("cpuAvailableAt"));assertEquals(10.0,candidate(decision,42).get("cpuAvailableAt"));
    }
    @Test void completedAttemptsDoNotLeaveStaleCpuReservations(){
        Task a=task(1,1000),b=task(2,1000);CoherentDataflowRuntime r=runtime(false,false,2000,10,10,a,b);DataflowVmAssigner policy=assigner(r,1000,1000,1,1);policy.prepare(job(10,a),0);r.drainReadyJobIds();r.cpuStarted(10,one(1),7,0);r.jobFinished(10,one(1),7,oneSuccess(true),1);DataflowVmAssigner.Decision next=policy.prepare(job(20,b),1).getDecision();assertEquals(7,next.getVmId());assertEquals(1.0,candidate(next,7).get("cpuAvailableAt"));
    }
    @Test void sharedStoreGateIsConditionalAndNeverFreeForProducerVmCache(){
        Task p=task(1,1000,out("x",100)),c=task(2,1000,in("x",100));edge(p,c);CoherentDataflowRuntime r=runtime(true,true,2000,100,100,p,c);produce(r,10,p,7,0,1,true);DataflowVmAssigner.Decision decision=assigner(r,1000,1000,1,1).prepare(job(20,c),1).getDecision();assertEquals(7,decision.getVmId());assertEquals(10.0,candidate(decision,7).get("inputSeconds"));assertEquals(15.0,candidate(decision,42).get("inputSeconds"));assertEquals(Boolean.TRUE,candidate(decision,7).get("conditionalStoreWait"));assertEquals(DataflowObservation.JobPhase.WAITING_FOR_STORE,r.observe().getActiveJobs().get(20).getPhase());assertNull(r.observe().getFile(id("x")).getReplica(CoherentDataflowRuntime.SOURCE));
    }
    @Test void joinsUseObservedRemainingNominalServiceAndDoNotCreateDuplicates(){
        Task a=task(1,1000,in("x",100)),b=task(2,1000,in("x",100));CoherentDataflowRuntime r=runtime(false,false,2000,10,10,a,b);DataflowVmAssigner policy=assigner(r,1000,1000,1,1);policy.prepare(job(10,a),0);DataflowVmAssigner.Prepared second=policy.prepare(job(20,b),5);assertEquals(7,second.getDecision().getVmId());assertEquals(5.0,candidate(second.getDecision(),7).get("inputSeconds"));assertEquals(1,candidate(second.getDecision(),7).get("joinedInputCopies"));assertEquals(1,second.getPreparation().getJoinedCopies());assertEquals(1,r.getAdmittedCopyCount());
    }
    @Test void retryReusesLogicalBindingEvenWhenAnotherVmWouldNowScoreBetter(){
        Task t=task(1,1000),longTask=task(2,100000);CoherentDataflowRuntime r=runtime(false,false,2000,10,10,t,longTask);DataflowVmAssigner policy=assigner(r,100,1000,1,1);policy.prepare(job(10,t),0);r.drainReadyJobIds();r.cpuStarted(10,one(1),42,0);r.jobFinished(10,one(1),42,oneSuccess(false),1);policy.prepare(job(20,longTask),1);Task copy=task(1,1000);copy.setVmId(42);Job retry=job(30,copy);retry.setVmId(42);DataflowVmAssigner.Decision decision=policy.prepare(retry,1).getDecision();assertTrue(decision.isReusedBinding());assertEquals(42,decision.getVmId());assertEquals(2,policy.getBindings().size());
    }
    @Test void incompatiblePesAreExcludedAndNoFallbackVmIsInvented(){
        Task t=task(1,1000);t.setNumberOfPes(2);Job j=job(10,t);j.setNumberOfPes(2);CoherentDataflowRuntime r=runtime(false,false,2000,10,10,t);DataflowVmAssigner.Decision d=assigner(r,1000,100,1,2).prepare(j,0).getDecision();assertEquals(42,d.getVmId());assertEquals(Boolean.FALSE,candidate(d,7).get("compatible"));assertNull(candidate(d,7).get("scoreFinishSeconds"));
    }
    @Test void matrixRoundingUsesTheSameExecutableMiAsRuntime(){
        Task t=task(1,1000);Map<Integer,Double> matrix=new LinkedHashMap<>();matrix.put(7,.00149);matrix.put(42,.0014);t.setVmExecutionCostSeconds(matrix);CoherentDataflowRuntime r=runtime(false,false,2000,10,10,t);DataflowVmAssigner.Decision d=assigner(r,1000,1000,1,1).prepare(job(10,t),0).getDecision();assertEquals(7,d.getVmId());assertEquals(.001,candidate(d,7).get("computeSeconds"));assertEquals(.001,candidate(d,42).get("computeSeconds"));
    }
    @Test void invalidInitialHintOrUnknownSourceDoesNotCreateBinding(){
        Task t=task(1,1000);CoherentDataflowRuntime r=runtime(false,false,2000,10,10,t);DataflowVmAssigner policy=assigner(r,1000,1000,1,1);Job j=job(10,t);j.setVmId(7);assertThrows(IllegalStateException.class,()->policy.prepare(j,1));assertTrue(policy.getBindings().isEmpty());assertEquals(0,r.getCurrentTime(),0);
        Task p=task(1,1000,out("x",100)),c=task(2,1000,in("x",100));edge(p,c);CoherentDataflowRuntime unseen=runtime(false,false,2000,10,10,p,c);DataflowVmAssigner other=assigner(unseen,1000,1000,1,1);assertThrows(IllegalStateException.class,()->other.prepare(job(20,c),0));assertTrue(other.getBindings().isEmpty());
    }
    @Test void failedRuntimeRequestCannotCommitVmBindingReservationOrDecision(){
        Task t=task(1,1000,in("a",Double.MAX_VALUE),in("b",Double.MAX_VALUE));CoherentDataflowRuntime r=runtime(false,false,2000,1e308,1e308,t);DataflowVmAssigner policy=assigner(r,1000,1000,1,1);Job j=job(10,t);String before=JSON.toJson(r.observe());assertThrows(IllegalArgumentException.class,()->policy.prepare(j,0));assertTrue(policy.getBindings().isEmpty());assertEquals(-1,j.getVmId());assertEquals(-1,t.getVmId());assertEquals(0,r.getAdmittedCopyCount());assertEquals(before,JSON.toJson(r.observe()));
    }
    @Test void decisionTreesAndBindingsAreImmutableAndCaptureIndependent(){
        String expected=null;for(int budget:new int[]{0,1,2000}){Task t=task(1,1000,in("x",100));CoherentDataflowRuntime r=runtime(false,false,budget,10,100,t);DataflowVmAssigner policy=assigner(r,1000,1000,1,1);DataflowVmAssigner.Decision d=policy.prepare(job(10,t),0).getDecision();assertThrows(UnsupportedOperationException.class,()->d.getAttributes().clear());assertThrows(UnsupportedOperationException.class,()->candidate(d,42).clear());assertThrows(UnsupportedOperationException.class,()->policy.getBindings().clear());String actual=JSON.toJson(d.getAttributes());if(expected==null)expected=actual;else assertEquals(expected,actual);}
    }
    @Test void foreignActiveComputeCannotBeMistakenForAnEmptyReservationQueue(){
        Task a=task(1,1000),b=task(2,1000);CoherentDataflowRuntime r=runtime(false,false,2000,10,10,a,b);r.requestJob(10,one(1),7,0);DataflowVmAssigner policy=assigner(r,1000,1000,1,1);assertThrows(IllegalStateException.class,()->policy.prepare(job(20,b),0));assertTrue(policy.getBindings().isEmpty());
    }
    @Test void candidateInventoryMustMatchActualFabric(){
        Task t=task(1,1000);CoherentDataflowRuntime r=runtime(false,false,2000,10,10,t);DataflowVmAssigner incomplete=new DataflowVmAssigner(r,Collections.singletonList(new DataflowVmAssigner.VmOption(7,1000,1)));assertThrows(IllegalArgumentException.class,()->incomplete.prepare(job(10,t),0));assertTrue(incomplete.getBindings().isEmpty());
    }
    @SuppressWarnings("unchecked") private static Map<String,Object> candidate(DataflowVmAssigner.Decision decision,int vm){for(Map<String,Object> value:(List<Map<String,Object>>)decision.getAttributes().get("candidates"))if(((Integer)value.get("vmId"))==vm)return value;throw new AssertionError("missing candidate");}
    private static DataflowVmAssigner assigner(CoherentDataflowRuntime r,double a,double b,int pa,int pb){return new DataflowVmAssigner(r,Arrays.asList(new DataflowVmAssigner.VmOption(42,b,pb),new DataflowVmAssigner.VmOption(7,a,pa)));}
    private static CoherentDataflowRuntime runtime(boolean storage,boolean gate,int budget,double a,double b,Task...tasks){Map<Integer,Double> caps=new LinkedHashMap<>();caps.put(7,a);caps.put(42,b);DataTransferFabric f;if(storage){Map<Integer,Integer> hosts=new LinkedHashMap<>();hosts.put(7,10);hosts.put(42,20);f=DataTransferFabric.withStorage(caps,hosts,null,DataflowStorageSpec.of(30,.00002,.00001,.0001));}else f=DataTransferFabric.endpoints(caps,Collections.singletonList("source"));CoherentDataflowRuntime r=storage?CoherentDataflowRuntime.withStorage(true,budget,gate):new CoherentDataflowRuntime(true,budget);Map<Integer,Integer> scopes=new LinkedHashMap<>();for(Task t:tasks)scopes.put(t.getCloudletId(),0);r.initializePlan(DataflowFilePlan.capture(Arrays.asList(tasks),scopes),f.getLocations());r.bindFabric(f);return r;}
    private static Job job(int id,Task task){Job j=new Job(id,task.getCloudletLength());j.setClassType(Parameters.ClassType.COMPUTE.value);j.setTaskList(Collections.singletonList(task));return j;}
    private static void produce(CoherentDataflowRuntime r,int job,Task task,int vm,double start,double finish,boolean success){r.requestJob(job,one(task.getCloudletId()),vm,start);r.drainReadyJobIds();r.cpuStarted(job,one(task.getCloudletId()),vm,start);r.jobFinished(job,one(task.getCloudletId()),vm,oneSuccess(success),finish);}
    private static List<Integer> one(int value){return Collections.singletonList(value);}
    private static List<Boolean> oneSuccess(boolean value){return Collections.singletonList(value);}
    private static DataflowFilePlan.FileId id(String name){return DataflowFilePlan.FileId.of(0,name);}
    private static Task task(int id,long length,FileItem...files){Task t=new Task(id,length);for(FileItem f:files)t.addFile(f);return t;}
    private static FileItem in(String name,double size){return file(name,size,Parameters.FileType.INPUT);}
    private static FileItem out(String name,double size){return file(name,size,Parameters.FileType.OUTPUT);}
    private static FileItem file(String name,double size,Parameters.FileType type){FileItem f=new FileItem(name,size);f.setType(type);return f;}
    private static void edge(Task a,Task b){a.addChild(b);b.addParent(a);}
}
