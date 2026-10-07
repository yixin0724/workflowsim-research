package org.workflowsim.data.v2;

import com.google.gson.Gson;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryPoolMXBean;
import java.lang.management.MemoryType;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.workflowsim.FileItem;
import org.workflowsim.Job;
import org.workflowsim.Task;
import org.workflowsim.utils.Parameters;

/**
 * Reproducible controller-only profile, not a scheduling study or a wall-clock test threshold.
 * Run this same class against frozen baseline and candidate simulator JARs with the same JVM.
 * Plan construction and post-run semantic hashing are outside the measured transition interval.
 */
public final class DataflowStateProfile {
    private static final Gson JSON=new Gson();
    private DataflowStateProfile(){ }
    /** @param args scenario, comma-separated sizes, capture mode OFF/TRUNCATED/FULL, repetitions */
    public static void main(String[] args)throws Exception{
        if(args.length!=4)throw new IllegalArgumentException("Usage: DataflowStateProfile <FILELESS|FILES_V2|STORE_V3|ONLINE_V3|WIDE_V2> <sizes> <OFF|TRUNCATED|FULL> <repetitions>");
        String scenario=args[0],capture=args[2];int repetitions=Integer.parseInt(args[3]);if(repetitions<1||repetitions>20)throw new IllegalArgumentException("Invalid repetitions");
        for(int warm=0;warm<3;warm++)sample(scenario,100,capture,false);
        for(String size:args[1].split(",")){int n=Integer.parseInt(size);if(n<1||n>20000)throw new IllegalArgumentException("Bounded profile task count required");for(int trial=0;trial<repetitions;trial++){Map<String,Object> row=sample(scenario,n,capture,true);row.put("trial",trial);row.put("javaVersion",System.getProperty("java.version"));row.put("maxHeapBytes",Runtime.getRuntime().maxMemory());System.out.println(JSON.toJson(row));}}
    }
    private static Map<String,Object> sample(String scenario,int n,String capture,boolean measure)throws Exception{
        boolean files=scenario.equals("FILES_V2")||scenario.equals("STORE_V3")||scenario.equals("ONLINE_V3"),storage=scenario.equals("STORE_V3")||scenario.equals("ONLINE_V3"),online=scenario.equals("ONLINE_V3"),wide=scenario.equals("WIDE_V2");
        if(!files&&!wide&&!scenario.equals("FILELESS"))throw new IllegalArgumentException("Unknown scenario");int budget=capture.equals("OFF")?0:capture.equals("TRUNCATED")?1:capture.equals("FULL")?Math.addExact(Math.multiplyExact(n,20),100):-1;if(budget<0)throw new IllegalArgumentException("Unknown capture mode");
        List<Task> tasks=new ArrayList<>();Map<Integer,Integer> scopes=new LinkedHashMap<>();for(int i=1;i<=n;i++){Task task=new Task(i,1000);if(files){task.addFile(file("in-"+i,100,Parameters.FileType.INPUT));task.addFile(file("out-"+i,100,Parameters.FileType.OUTPUT));}tasks.add(task);scopes.put(i,0);if(i>1&&!wide){tasks.get(i-2).addChild(task);task.addParent(tasks.get(i-2));}}
        Map<Integer,Double> capacities=new LinkedHashMap<>();capacities.put(0,1000.0);if(online)capacities.put(7,1000.0);DataTransferFabric fabric;if(storage){Map<Integer,Integer> hosts=new LinkedHashMap<>();hosts.put(0,0);if(online)hosts.put(7,7);fabric=DataTransferFabric.withStorage(capacities,hosts,null,DataflowStorageSpec.of(20,.001,.001,.001));}else fabric=DataTransferFabric.endpoints(capacities,Collections.singletonList("source"));
        CoherentDataflowRuntime runtime=storage?CoherentDataflowRuntime.withStorage(true,budget,true):new CoherentDataflowRuntime(true,budget);runtime.initializePlan(DataflowFilePlan.capture(tasks,scopes),fabric.getLocations());runtime.bindFabric(fabric);
        DataflowVmAssigner assigner=online?new DataflowVmAssigner(runtime,Arrays.asList(new DataflowVmAssigner.VmOption(0,1000,1),new DataflowVmAssigner.VmOption(7,2000,1))):null;
        List<Map<String,Object>> actions=new ArrayList<>();com.sun.management.ThreadMXBean allocation=null;if(ManagementFactory.getThreadMXBean() instanceof com.sun.management.ThreadMXBean){allocation=(com.sun.management.ThreadMXBean)ManagementFactory.getThreadMXBean();if(allocation.isThreadAllocatedMemorySupported()&&!allocation.isThreadAllocatedMemoryEnabled())allocation.setThreadAllocatedMemoryEnabled(true);if(!allocation.isThreadAllocatedMemoryEnabled())allocation=null;}
        if(measure)System.gc();long heapBefore=ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed();for(MemoryPoolMXBean pool:ManagementFactory.getMemoryPoolMXBeans())if(pool.getType()==MemoryType.HEAP)pool.resetPeakUsage();long thread=Thread.currentThread().getId(),allocatedBefore=allocation==null?0:allocation.getThreadAllocatedBytes(thread),start=System.nanoTime();
        if(wide){for(int i=0;i<n;i++)runtime.requestJob(i,one(i+1),0,0);runtime.drainReadyJobIds();for(int i=0;i<n;i++){runtime.cpuStarted(i,one(i+1),0,i);runtime.jobFinished(i,one(i+1),0,Collections.singletonList(true),i+1);runtime.observeFiles(Collections.<DataflowFilePlan.FileId>emptyList());runtime.isQuiescent();}}
        else for(int i=0;i<n;i++){
            double now=4.0*i;int vm=0;if(online){Job job=new Job(i,1000);job.setClassType(Parameters.ClassType.COMPUTE.value);job.setTaskList(Collections.singletonList(tasks.get(i)));DataflowVmAssigner.Prepared prepared=assigner.prepare(job,now);vm=prepared.getDecision().getVmId();actions.add(prepared.getDecision().getAttributes());}else runtime.requestJob(i,one(i+1),vm,now);
            runtime.advance(now+1);List<Integer> ready=runtime.drainReadyJobIds();if(!ready.equals(Collections.singletonList(i)))throw new AssertionError("Readiness trajectory changed");runtime.cpuStarted(i,one(i+1),vm,now+1);runtime.jobFinished(i,one(i+1),vm,Collections.singletonList(true),now+2);runtime.advance(now+3);if(!runtime.isQuiescent()||!runtime.observeFiles(Collections.<DataflowFilePlan.FileId>emptyList()).getActiveJobs().isEmpty())throw new AssertionError("Terminal state changed");
        }
        long elapsed=System.nanoTime()-start;Long allocated=allocation==null?null:allocation.getThreadAllocatedBytes(thread)-allocatedBefore;long peak=0;for(MemoryPoolMXBean pool:ManagementFactory.getMemoryPoolMXBeans())if(pool.getType()==MemoryType.HEAP)peak=Math.addExact(peak,pool.getPeakUsage().getUsed());
        if(!runtime.isQuiescent()||runtime.getActiveCopyCount()!=0||runtime.getAdmittedCopyCount()!=(files?(storage?2L*n:n):0))throw new AssertionError("Final copy/state contract changed");MessageDigest state=MessageDigest.getInstance("SHA-256");update(state,Double.toHexString(runtime.getCurrentTime()));for(int i=0;i<n;i++){CoherentDataflowRuntime.Preparation p=runtime.getPreparation(i);update(state,Double.toHexString(p.getRequiredBytes()),Double.toHexString(p.getIsolatedSeconds()),Long.toString(p.getReferenceCount()),Integer.toString(p.getNewCopies()),Integer.toString(p.getJoinedCopies()),Double.toHexString(runtime.getObservedInputPreparationSeconds(i)));}
        DataReplicaState.Snapshot snapshot=runtime.getReplicaSnapshot();for(DataflowFilePlan.FileDefinition f:runtime.getFilePlan().getFiles())for(DataReplicaState.Replica replica:snapshot.getReplicas(f.getId()))update(state,JSON.toJson(replica));Object evidence=storage?runtime.captureStorageEvidence():runtime.captureEvidence();Map<String,Object> row=new LinkedHashMap<>();row.put("scenario",scenario);row.put("tasks",n);row.put("capture",capture);row.put("elapsedNanos",elapsed);row.put("threadAllocatedBytes",allocated);row.put("heapBeforeBytes",heapBefore);row.put("heapPoolPeakSumBytes",peak);row.put("admittedCopies",runtime.getAdmittedCopyCount());row.put("stateSha256",hex(state.digest()));row.put("captureSha256",sha(JSON.toJson(evidence)));row.put("actionsSha256",sha(JSON.toJson(actions)));return row;
    }
    private static FileItem file(String name,double bytes,Parameters.FileType type){FileItem file=new FileItem(name,bytes);file.setType(type);return file;}
    private static List<Integer> one(int value){return Collections.singletonList(value);}
    private static void update(MessageDigest hash,String...values){for(String value:values){hash.update(value.getBytes(StandardCharsets.UTF_8));hash.update((byte)'\n');}}
    private static String sha(String value)throws Exception{return hex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}
    private static String hex(byte[] value){StringBuilder result=new StringBuilder();for(byte b:value)result.append(String.format("%02x",b&255));return result.toString();}
}
