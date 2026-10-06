package org.workflowsim.experiment;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import org.cloudbus.cloudsim.Cloudlet;
import org.workflowsim.data.NetworkEvidenceConfig;
import org.workflowsim.data.NetworkEvidenceConfigCodec;
import org.workflowsim.data.v2.FileLifecycleCodec;
import org.workflowsim.data.v2.FileLifecycleEvent;
import org.workflowsim.utils.Parameters;

/** Cross-file V2 certificate: a valid lifecycle must describe this exact final Kernel run. */
final class FileLifecycleContextValidator {
    private static final Gson JSON=new GsonBuilder().serializeNulls().create();
    private final Checked decoded;
    private final Map<Integer,Job> jobs=new LinkedHashMap<>();
    private final Map<Integer,JsonObject> taskOutcomes=new HashMap<>();
    private final Map<Integer,Integer> taskScopes=new HashMap<>();
    private final Map<Integer,Double> arrivals=new HashMap<>();
    private final Map<Integer,JsonObject> requested=new HashMap<>(),cpu=new HashMap<>(),finished=new HashMap<>();
    private final Map<Integer,Double> requestTime=new HashMap<>(),readyTime=new HashMap<>(),cpuTime=new HashMap<>(),finishTime=new HashMap<>();
    private final Map<String,Map<Integer,JsonObject>> main=new HashMap<>();
    private final Map<Integer,Integer> retries=new HashMap<>();
    private final Map<Integer,JsonObject> retryEvents=new HashMap<>();
    private final Map<Integer,ReferenceTotals> referenceTotals=new HashMap<>(),plannedReferenceTotals=new HashMap<>();
    private final Map<String,Double> fileBytes=new HashMap<>();
    private final Map<Long,Double> copySeconds=new HashMap<>();
    private final double simulationEnd;
    private double previousMainTime;
    private boolean storageFailureEnabled;
    private int storageRetryBudget;

    static void validateReport(SimulationReport report,FileLifecycleCodec.Decoded decoded)throws IOException{
        JsonObject root=JSON.toJsonTree(ExperimentManifestWriter.fileLifecycleContextSnapshot(report)).getAsJsonObject();
        FileLifecycleContextValidator check=new FileLifecycleContextValidator(root,decoded);for(SimulationEvent event:report.getEvents())check.acceptEvent(JSON.toJsonTree(event).getAsJsonObject());check.finish();
    }

    static void validateReport(SimulationReport report,org.workflowsim.data.v2.StorageLifecycleCodec.Decoded decoded)throws IOException{
        JsonObject root=JSON.toJsonTree(ExperimentManifestWriter.fileLifecycleContextSnapshot(report)).getAsJsonObject();FileLifecycleContextValidator check=new FileLifecycleContextValidator(root,decoded);for(SimulationEvent event:report.getEvents())check.acceptEvent(JSON.toJsonTree(event).getAsJsonObject());check.finish();
    }

    /** Shared coherent OFF/ON core-plan validation; no external input files are reopened. */
    static void validatePlanContext(JsonObject root)throws IOException{
        JsonObject config=obj(root,"configuration"),plan=obj(root,"dataflowPlan");String kind=text(obj(config,"dataMovementModel"),"kind");
        boolean storage=isStorage(kind);if(!isV2(kind)&&!storage)throw fail("dataflowPlan requires an explicitly supported coherent model");
        requireText(config,"planningAlgorithm","RANDOM");requireText(config,"schedulingAlgorithm","STATIC");String fileSystem=text(config,"fileSystem");if(!fileSystem.equals("LOCAL")&&!(storage&&fileSystem.equals("SHARED")))throw fail("Unsupported coherent input-access configuration");requireText(obj(config,"clustering"),"method","NONE");
        if(storage)validateStorageSpec(obj(root,"platform"));else if(obj(root,"platform").has("sourceStorage"))throw fail("V2 cannot declare bounded sourceStorage");
        JsonObject overhead=obj(config,"overheadModel");if(whole(need(overhead,"workflowEngineDelayInterval"))!=0||number(need(overhead,"bandwidth"))!=0)throw fail("V2 runtime overhead is unsupported");
        for(String key:Arrays.asList("workflowEngineDelays","queueDelays","postDelays","clusteringDelays"))if(obj(overhead,key).size()!=0)throw fail("V2 runtime overhead maps must be empty");
        JsonObject failure=obj(config,"failureModel");requireText(failure,"clusteringAlgorithm","FTCLUSTERING_NOOP");requireText(failure,"monitorMode","MONITOR_NONE");if(storage)validateStorageFailure(root);
        try{FileLifecycleCodec.validatePlanDocument(plan);}catch(IllegalArgumentException invalid){throw new IOException("Invalid V2 core file plan",invalid);}
        Map<Integer,JsonObject> tasks=rows(arr(plan,"tasks"),"taskId"),graph=rows(arr(root,"workflowGraph"),"taskId");if(!tasks.keySet().equals(graph.keySet()))throw fail("V2 plan Task identities differ from workflowGraph");
        for(int id:tasks.keySet())if(!ids(arr(tasks.get(id),"parents")).equals(ids(arr(graph.get(id),"parentIds"))))throw fail("V2 plan control parents differ from workflowGraph");
        JsonArray inputs=arr(config,"workflowArrivalSeconds"),outcomes=arr(obj(root,"result"),"workflowOutcomes");if(inputs.size()!=outcomes.size())throw fail("V2 workflow scope count differs");
        Set<Integer> covered=new HashSet<>();long first=1;
        for(int index=0;index<outcomes.size();index++){
            JsonObject outcome=object(outcomes.get(index));int count=integer(need(outcome,"taskCount"));long last=first+count-1;
            if(count<1||last>Integer.MAX_VALUE||integer(need(outcome,"index"))!=index||whole(need(outcome,"firstTaskId"))!=first||whole(need(outcome,"lastTaskId"))!=last)throw fail("Invalid V2 input Task ranges");
            if(number(inputs.get(index))!=number(need(outcome,"arrivalSecond")))throw fail("V2 workflow arrival differs");
            for(long id=first;id<=last;id++){JsonObject task=tasks.get((int)id);if(task==null||integer(need(task,"workflowInputIndex"))!=index||!covered.add((int)id))throw fail("V2 Task input scope differs from parsed ranges");}first=last+1;
        }
        if(!covered.equals(tasks.keySet()))throw fail("V2 input ranges do not cover the file plan");
    }

    FileLifecycleContextValidator(JsonObject root,FileLifecycleCodec.Decoded decoded)throws IOException{this(root,new Checked(decoded));}
    FileLifecycleContextValidator(JsonObject root,org.workflowsim.data.v2.StorageLifecycleCodec.Decoded decoded)throws IOException{this(root,new Checked(decoded));}
    private FileLifecycleContextValidator(JsonObject root,Checked decoded)throws IOException{
        this.decoded=decoded;validatePlanContext(root);
        JsonObject config=obj(root,"configuration"),result=obj(root,"result"),document=decoded.getDocument();String model=text(obj(config,"dataMovementModel"),"kind");
        NetworkEvidenceConfig option;try{option=NetworkEvidenceConfigCodec.decodeConfig(need(config,"networkEvidence"));}catch(IllegalArgumentException invalid){throw new IOException("Invalid V2 recording configuration",invalid);}
        if(option.getMode()!=(decoded.storage?NetworkEvidenceConfig.Mode.FILE_STORAGE_LIFECYCLE_V3:NetworkEvidenceConfig.Mode.FILE_LIFECYCLE_V2)||option.getMaxTraceRecords()!=decoded.getEvidence().getMaxRecords()||!model.equals(text(document,"modelKind"))||decoded.storage!=isStorage(model))throw fail("Versioned model/recording differs between manifest and lifecycle");
        if(decoded.storage){String expected=text(config,"fileSystem").equals("SHARED")?"COMMITTED_STORE_THEN_VM_READ_CACHE_V3":"LOCAL_VISIBLE_REPLICA_V3";requireText(obj(document,"policies"),"inputAccess",expected);JsonObject failure=obj(config,"failureModel");storageFailureEnabled=!text(failure,"generatorMode").equals("FAILURE_NONE");storageRetryBudget=integer(need(failure,"maxTotalRetryJobs"));}
        same(obj(root,"dataflowPlan"),decoded.getEvidence().getFilePlan(),"Core and lifecycle file plans differ");
        simulationEnd=number(need(result,"simulationEndSeconds"));if(decoded.getEvidence().getObservedThrough()>simulationEnd||!decoded.isQuiescent())throw fail("Final V2 bundle is not quiescent or exceeds simulation end");
        if(!bool(need(result,"workflowCompletedSuccessfully")))throw fail("Initial V2 bundle contract requires all logical Tasks completed");
        validateFabric(obj(root,"platform"),obj(result,"actualVmHostAssignments"),decoded.getEvidence().getFabric(),decoded.storage);
        for(JsonElement row:arr(obj(root,"dataflowPlan"),"tasks")){JsonObject task=object(row);taskScopes.put(integer(need(task,"taskId")),integer(need(task,"workflowInputIndex")));}
        for(JsonElement row:arr(obj(root,"dataflowPlan"),"files")){JsonObject file=object(row);fileBytes.put(fileKey(obj(file,"fileId")),number(need(file,"bytes")));}
        if(decoded.storage)for(JsonElement row:arr(obj(root,"dataflowPlan"),"tasks")){
            JsonObject task=object(row);ReferenceTotals totals=new ReferenceTotals();Map<String,JsonObject> ordered=new TreeMap<>();for(JsonElement input:arr(task,"inputs")){JsonObject ref=object(input);ordered.put(text(obj(ref,"fileId"),"name"),ref);}
            for(JsonObject ref:ordered.values()){long count=whole(need(ref,"referenceCount"));try{totals.references=Math.addExact(totals.references,count);}catch(ArithmeticException invalid){throw new IOException("V3 reference count overflow",invalid);}totals.bytes+=new BigDecimal(fileBytes.get(fileKey(obj(ref,"fileId")))).multiply(BigDecimal.valueOf(count)).doubleValue();}
            if(totals.references>Integer.MAX_VALUE||!Double.isFinite(totals.bytes))throw fail("V3 request aggregate exceeds supported core-event range");plannedReferenceTotals.put(integer(need(task,"taskId")),totals);
        }
        for(int i=0;i<arr(config,"workflowArrivalSeconds").size();i++)arrivals.put(i,number(arr(config,"workflowArrivalSeconds").get(i)));
        Set<Integer> allJobs=new HashSet<>();
        for(JsonElement row:arr(result,"jobs")){
            JsonObject value=object(row);int id=integer(need(value,"jobId"));if(!allJobs.add(id))throw fail("Duplicate Job outcome");if(integer(need(value,"classType"))!=Parameters.ClassType.COMPUTE.value)continue;
            List<Integer> ids=listIds(arr(value,"taskIds"));if(ids.size()!=1||integer(need(value,"taskCount"))!=1||!taskScopes.containsKey(ids.get(0)))throw fail("V2 Job must contain one declared logical Task");
            Job job=new Job(integer(need(value,"vmId")),ids.get(0),integer(need(value,"status")),number(need(value,"startTime")),number(need(value,"finishTime")));
            if(job.finish<job.start||job.finish>simulationEnd||(job.status!=Cloudlet.SUCCESS&&job.status!=Cloudlet.FAILED))throw fail("Invalid V2 Job outcome");jobs.put(id,job);
        }
        for(JsonElement row:arr(result,"tasks")){
            JsonObject task=object(row);int jobId=integer(need(task,"jobId"));Job job=jobs.get(jobId);
            if(job==null||integer(need(task,"taskId"))!=job.task||integer(need(task,"vmId"))!=job.vm||integer(need(task,"jobStatus"))!=job.status||taskOutcomes.put(jobId,task)!=null)throw fail("V2 Task attempt differs from Job outcome");
            int status=integer(need(task,"taskStatus"));if((status!=Cloudlet.SUCCESS&&status!=Cloudlet.FAILED)||status!=job.status)throw fail("Singleton Task outcome differs from its terminal Job status");
            double taskStart=number(need(task,"startTime")),taskFinish=number(need(task,"finishTime"));if(taskStart!=job.start||taskFinish<taskStart||taskFinish>job.finish+timeTolerance(job.finish))throw fail("Task model window differs from actual V2 Job bounds");
            if(task.has("exactJobTiming")&&bool(need(task,"exactJobTiming"))!=(taskStart==job.start&&taskFinish==job.finish))throw fail("Task exactJobTiming claim differs from its actual bounds");
        }
        if(!taskOutcomes.keySet().equals(jobs.keySet())||decoded.getRequestedJobCount()!=jobs.size()||decoded.getCompletedJobCount()!=jobs.size())throw fail("V2 lifecycle does not cover every compute attempt");
        if(!decoded.getSuccessfullyCompletedTaskIds().equals(taskScopes.keySet()))throw fail("V2 lifecycle lacks logical Task success");
        for(FileLifecycleEvent event:decoded.getEvidence().getEvents()){
            JsonObject p=event.getPayload();switch(event.getType()){
                case JOB_INPUT_REQUESTED:{int id=integer(need(p,"jobId"));Job job=requireJob(id);if(integer(need(p,"destinationVmId"))!=job.vm||!listIds(arr(p,"taskIds")).equals(Arrays.asList(job.task)))throw fail("V2 request membership/VM differs");put(requested,id,p);ReferenceTotals totals=new ReferenceTotals();if(decoded.storage){totals.references=plannedReferenceTotals.get(job.task).references;totals.bytes=plannedReferenceTotals.get(job.task).bytes;}referenceTotals.put(id,totals);requestTime.put(id,event.getObservedTime());if(event.getObservedTime()<arrivals.get(taskScopes.get(job.task)))throw fail("Input request precedes workflow arrival");break;}
                case COPY_ADMITTED:copySeconds.put(whole(need(p,"copyOrdinal")),number(need(p,"isolatedSeconds")));break;
                case INPUT_RESOLVED:{int id=integer(need(p,"jobId"));ReferenceTotals totals=referenceTotals.get(id);if(totals==null)throw fail("Input resolution precedes request");long count=whole(need(p,"referenceCount"));Double bytes=fileBytes.get(fileKey(obj(p,"fileId")));if(bytes==null)throw fail("Input resolution has unknown file");
                    if(!decoded.storage){try{totals.references=Math.addExact(totals.references,count);}catch(ArithmeticException overflow){throw new IOException("Reference counter overflow",overflow);}totals.bytes+=new BigDecimal(bytes).multiply(BigDecimal.valueOf(count)).doubleValue();}
                    String resolution=text(p,"resolution");
                    if(resolution.equals("NEW_COPY")||resolution.equals("JOIN_EXISTING")){Double seconds=copySeconds.get(whole(need(p,"copyOrdinal")));if(seconds==null)throw fail("Resolution references unknown copy estimate");totals.seconds+=seconds;if(resolution.equals("NEW_COPY"))totals.created++;else totals.joined++;}
                    if(!Double.isFinite(totals.bytes)||!Double.isFinite(totals.seconds))throw fail("Unrepresentable V2 input aggregate");break;}
                case JOB_DATA_READY:readyTime.put(integer(need(p,"jobId")),event.getObservedTime());break;
                case JOB_CPU_STARTED:{int id=integer(need(p,"jobId"));Job job=requireJob(id);if(integer(need(p,"vmId"))!=job.vm||event.getObservedTime()!=job.start)throw fail("V2 CPU start differs from Job outcome");put(cpu,id,p);cpuTime.put(id,event.getObservedTime());break;}
                case TASK_FINISHED:{int id=integer(need(p,"jobId"));Job job=requireJob(id);if(integer(need(p,"taskId"))!=job.task||integer(need(p,"vmId"))!=job.vm||bool(need(p,"success"))!=(integer(need(taskOutcomes.get(id),"taskStatus"))==Cloudlet.SUCCESS)||event.getObservedTime()<job.finish)throw fail("V2 terminal Task publication differs from result");put(finished,id,p);finishTime.put(id,event.getObservedTime());break;}
                default:break;
            }
        }
        if(!requested.keySet().equals(jobs.keySet())||!cpu.keySet().equals(jobs.keySet())||!finished.keySet().equals(jobs.keySet())||!readyTime.keySet().equals(jobs.keySet()))throw fail("Incomplete V2 final lifecycle Job coverage");
    }

    void acceptEvent(JsonObject event)throws IOException{
        double time=number(need(event,"simulationTime"));if(time<previousMainTime||time>simulationEnd)throw fail("V2 main event time is not monotonic within run");previousMainTime=time;
        String type=text(event,"type");if(!Arrays.asList("JOB_READY","DATA_STAGE_IN_MODELED","TASK_EXECUTION_MODELED","JOB_RETURNED","JOB_FAILED","SCHEDULING_DECISION","JOB_DISPATCHED","RETRY_JOB_CREATED").contains(type))return;
        if(integer(need(event,"classType"))!=Parameters.ClassType.COMPUTE.value)return;int id=integer(need(event,"jobId"));Job job=requireJob(id);
        if(integer(need(event,"vmId"))!=job.vm||!listIds(arr(event,"taskIds")).equals(Arrays.asList(job.task)))throw fail("V2 main event Job identity differs");
        if(type.equals("RETRY_JOB_CREATED")){int failed=integer(need(obj(event,"attributes"),"failedJobId"));Job previous=requireJob(failed);if(previous.status!=Cloudlet.FAILED||previous.task!=job.task||(decoded.storage&&previous.vm!=job.vm)||retries.put(id,failed)!=null||time>requestTime.get(id))throw fail("Invalid V2 retry provenance");retryEvents.put(id,event);return;}
        Map<Integer,JsonObject> values=main.get(type);if(values==null){values=new HashMap<>();main.put(type,values);}put(values,id,event);
    }
    void finish()throws IOException{
        if(decoded.storage&&(retries.size()>storageRetryBudget||(!storageFailureEnabled&&!retries.isEmpty())))throw fail("V3 retry history violates its failure mode or retry budget");
        Set<Integer> initialTasks=new HashSet<>(),retriedFailures=new HashSet<>(retries.values());if(retriedFailures.size()!=retries.size())throw fail("V2 NOOP failure produced multiple retries");
        for(Map.Entry<Integer,Job> entry:jobs.entrySet()){
            int id=entry.getKey();Job job=entry.getValue();JsonObject ready=main("JOB_READY",id),stage=main("DATA_STAGE_IN_MODELED",id),execution=main("TASK_EXECUTION_MODELED",id),returned=main("JOB_RETURNED",id),decision=main("SCHEDULING_DECISION",id),dispatch=main("JOB_DISPATCHED",id);
            if(number(need(ready,"simulationTime"))!=requestTime.get(id)||number(need(stage,"simulationTime"))!=(decoded.storage?readyTime.get(id):requestTime.get(id))||number(need(execution,"simulationTime"))!=cpuTime.get(id)||number(need(returned,"simulationTime"))!=finishTime.get(id))throw fail("V2 lifecycle observations differ from main event anchors");
            double decided=number(need(decision,"simulationTime"));if(decided<readyTime.get(id)||decided>job.start||number(need(dispatch,"simulationTime"))!=decided)throw fail("Dispatch precedes V2 data-ready or differs from scheduling decision");
            requireText(obj(decision,"attributes"),"schedulingAlgorithm","STATIC");
            if(number(need(obj(decision,"attributes"),"queueDelaySeconds"))!=0||number(need(obj(dispatch,"attributes"),"queueDelaySeconds"))!=0||number(need(obj(returned,"attributes"),"postDelaySeconds"))!=0)throw fail("Unsupported V2 dispatch/post overhead");
            JsonObject attrs=obj(execution,"attributes");if(integer(need(attrs,"taskId"))!=job.task||number(need(attrs,"taskStartTime"))!=job.start||number(need(attrs,"taskFinishTime"))!=number(need(taskOutcomes.get(id),"finishTime"))||number(need(attrs,"modeledStageInSecondsBeforeTask"))!=0||number(need(attrs,"requestedDataStageInSecondsForJob"))!=0)throw fail("V2 CPU envelope contains input transfer or mismatched Task timing");
            JsonObject stageAttrs=obj(stage,"attributes");requireText(stageAttrs,"transferUnit",decoded.storage?"LOGICAL_FILE_STORAGE_V3":"LOGICAL_FILE_V2");if(decoded.storage&&number(need(stageAttrs,"observedInputPreparationSeconds"))!=readyTime.get(id)-requestTime.get(id))throw fail("V3 observed preparation latency differs");requireText(stageAttrs,"dataMovementModel",text(decoded.getDocument(),"modelKind"));ReferenceTotals totals=referenceTotals.get(id);
            if(totals==null||whole(need(stageAttrs,"modeledTransferFileCount"))!=totals.references||number(need(stageAttrs,"requiredFileBytes"))!=totals.bytes||number(need(stageAttrs,"modeledTransferSeconds"))!=totals.seconds||integer(need(stageAttrs,"newFileCopies"))!=totals.created||integer(need(stageAttrs,"joinedFileCopies"))!=totals.joined||stageAttrs.has("contentionTransferGroupCount"))throw fail("V2 stage-in main quantities disagree with file lifecycle references");
            JsonObject readyAttrs=obj(ready,"attributes");if(retries.containsKey(id)){if(integer(need(readyAttrs,"retryOfFailedJobId"))!=retries.get(id))throw fail("V2 readiness retry origin differs");}else if(readyAttrs.has("retryOfFailedJobId"))throw fail("Initial V2 request falsely claims retry origin");
            if(integer(need(obj(returned,"attributes"),"jobStatus"))!=job.status)throw fail("V2 main return status differs");
            List<Integer> statuses=listIds(arr(obj(returned,"attributes"),"taskStatuses"));if(!statuses.equals(Arrays.asList(job.status)))throw fail("V2 returned Task statuses differ from singleton attempt");
            if(job.status==Cloudlet.FAILED){main("JOB_FAILED",id);if(!retriedFailures.contains(id))throw fail("V2 final run contains unrecovered failed attempt");}else if(main.containsKey("JOB_FAILED")&&main.get("JOB_FAILED").containsKey(id))throw fail("Success Job has failed event");
            if(!retries.containsKey(id)&&!initialTasks.add(job.task))throw fail("Multiple initial Job attempts for one logical Task");
        }
        for(Map.Entry<Integer,Integer> retry:retries.entrySet()){
            JsonObject created=retryEvents.get(retry.getKey()),returned=main("JOB_RETURNED",retry.getValue()),failed=main("JOB_FAILED",retry.getValue()),ready=main("JOB_READY",retry.getKey());
            long creation=whole(need(created,"sequence"));double time=number(need(created,"simulationTime"));
            if(whole(need(returned,"sequence"))>=whole(need(failed,"sequence"))||whole(need(returned,"sequence"))>=creation||whole(need(failed,"sequence"))>=creation||creation>=whole(need(ready,"sequence"))||number(need(returned,"simulationTime"))>time||number(need(failed,"simulationTime"))>time||time>number(need(ready,"simulationTime")))throw fail("Retry creation must follow failed return and precede retry readiness");
        }
        if(!initialTasks.equals(taskScopes.keySet()))throw fail("V2 initial attempts do not cover logical Tasks");
    }

    private static void validateFabric(JsonObject platform,JsonObject actual,JsonObject fabric,boolean storage)throws IOException{
        Map<Integer,JsonObject> vms=rows(arr(platform,"vms"),"id"),hosts=rows(arr(platform,"hosts"),"id");if(actual.size()!=vms.size())throw fail("Actual placement is incomplete");
        Map<String,Double> caps=new HashMap<>();for(JsonElement row:arr(fabric,"resources")){JsonObject r=object(row);caps.put(text(r,"key"),number(need(r,"capacityBytesPerSecond")));}
        Set<Integer> fabricVms=new HashSet<>();for(JsonElement row:arr(fabric,"locations")){JsonObject l=object(row);if(text(l,"kind").equals("VM"))fabricVms.add(integer(need(l,"vmId")));}
        if(!fabricVms.equals(vms.keySet()))throw fail("V2 fabric endpoints differ from platform");
        for(int id:vms.keySet()){
            JsonObject vm=vms.get(id);int host=integer(need(vm,"preflightHostId"));if(!hosts.containsKey(host)||integer(need(actual,Integer.toString(id)))!=host)throw fail("V2 actual host differs from platform placement");
            double capacity=whole(need(vm,"bandwidth"))*1_000_000.0;Double observed=caps.get("VM:"+id);if(!Double.isFinite(capacity)||capacity<Double.MIN_NORMAL||observed==null||observed!=capacity)throw fail("V2 VM capacity differs from platform");
        }
        if(storage){
            JsonObject spec=validateStorageSpec(platform);same(spec,obj(fabric,"sourceStorage"),"Storage fabric parameters differ from platform");Map<Integer,JsonObject> mapped=rows(arr(fabric,"vmHostAssignments"),"vmId");if(!mapped.keySet().equals(vms.keySet()))throw fail("Storage fabric actual placement coverage differs");for(int id:vms.keySet())if(integer(need(mapped.get(id),"hostId"))!=integer(need(actual,Integer.toString(id))))throw fail("Storage fabric uses a nonactual VM host");
            String[] fields={"readBandwidthMbPerSecond","writeBandwidthMbPerSecond","networkBandwidthMbPerSecond"},keys={"STORE:source:READ","STORE:source:WRITE","STORE:source:NIC"};for(int i=0;i<fields.length;i++){Double capacity=caps.get(keys[i]);if(capacity==null||capacity!=number(need(spec,fields[i]))*1_000_000.0)throw fail("Store capacity differs from platform");}
        }else if(platform.has("sourceStorage")||fabric.has("sourceStorage"))throw fail("V2 cannot declare bounded storage");
        JsonElement declared=need(platform,"networkTopology"),observed=need(fabric,"topology");if(declared.isJsonNull()!=observed.isJsonNull())throw fail("V2 topology presence differs");if(declared.isJsonNull())return;
        JsonObject topology=object(declared),snapshot=object(observed);int k=integer(need(topology,"k")),half=k/2;if(k<2||k>32||k%2!=0)throw fail("Unsupported V2 topology size");
        int cores=need(topology,"coreSwitchCount").isJsonNull()?k*k/4:integer(need(topology,"coreSwitchCount"));
        if(integer(need(snapshot,"k"))!=k||integer(need(snapshot,"coreSwitchCount"))!=cores||number(need(snapshot,"linkBandwidthBytesPerSecond"))!=number(need(topology,"linkBandwidthMbPerSecond"))*1_000_000.0)throw fail("V2 topology capacities differ");
        Map<Integer,JsonObject> placements=rows(arr(snapshot,"hostPlacements"),"hostId"),vmHosts=rows(arr(fabric,"vmHostAssignments"),"vmId");if(!placements.keySet().equals(hosts.keySet())||!vmHosts.keySet().equals(vms.keySet()))throw fail("V2 topology host coverage differs");
        for(int id:vms.keySet())if(integer(need(vmHosts.get(id),"hostId"))!=integer(need(actual,Integer.toString(id))))throw fail("V2 route uses a nonactual VM host");
        JsonElement explicit=need(topology,"hostEdgePlacements");int ordinal=0;
        for(int host:new TreeSet<Integer>(hosts.keySet())){int edge=explicit.isJsonNull()?ordinal++%(k*half):integer(need(object(explicit),Integer.toString(host)));JsonObject p=placements.get(host);if(integer(need(p,"pod"))!=edge/half||integer(need(p,"edge"))!=edge%half)throw fail("V2 Host edge placement differs");}
    }
    /** Necessary V3 policy/coverage checks only; no failure samples or RNG cursor is replayed. */
    private static void validateStorageFailure(JsonObject root)throws IOException{
        JsonObject failure=obj(obj(root,"configuration"),"failureModel");Set<String> expected=new HashSet<>(Arrays.asList("clusteringAlgorithm","monitorMode","generatorMode","distributionFamily","maxTotalRetryJobs","generatorAddressing","generators","generatorsByVmId"));if(!failure.keySet().equals(expected))throw fail("Invalid V3 failure model fields");
        String mode=text(failure,"generatorMode"),family=text(failure,"distributionFamily");if(!Arrays.asList("FAILURE_NONE","FAILURE_ALL","FAILURE_VM","FAILURE_JOB","FAILURE_VM_JOB").contains(mode)||!Arrays.asList("WEIBULL","GAMMA","LOGNORMAL","NORMAL").contains(family))throw fail("Unknown V3 failure mode/family");int budget=integer(need(failure,"maxTotalRetryJobs"));
        JsonArray dense=arr(failure,"generators");JsonObject keyed=obj(failure,"generatorsByVmId");boolean byVm=keyed.size()!=0,enabled=!mode.equals("FAILURE_NONE");requireText(failure,"generatorAddressing",byVm?"VM_ID_KEYED_ROWS":"DENSE_VM_ID_MATRIX");
        if(!enabled){if(budget!=0||dense.size()!=0||byVm)throw fail("Disabled V3 failure model must have zero budget and no generators");}
        else{
            if(budget==0||(dense.size()!=0)==byVm||family.equals("NORMAL")||(byVm&&!mode.equals("FAILURE_VM")&&!mode.equals("FAILURE_VM_JOB")))throw fail("Invalid enabled V3 failure layout or budget");
            Map<Integer,JsonArray> generatorRows=new HashMap<>();if(byVm){for(Map.Entry<String,JsonElement> entry:keyed.entrySet()){int id;try{id=Integer.parseInt(entry.getKey());}catch(NumberFormatException invalid){throw new IOException("Invalid failure VM row key",invalid);}if(id<0||!Integer.toString(id).equals(entry.getKey())||!entry.getValue().isJsonArray())throw fail("Invalid failure VM row");generatorRows.put(id,entry.getValue().getAsJsonArray());}}else for(int i=0;i<dense.size();i++){if(!dense.get(i).isJsonArray())throw fail("Invalid dense failure row");generatorRows.put(i,dense.get(i).getAsJsonArray());}
            for(JsonArray row:generatorRows.values()){if(row.size()==0)throw fail("Failure generator row is empty");for(JsonElement item:row){JsonObject spec=object(item);if(!spec.keySet().equals(new HashSet<>(Arrays.asList("family","scale","shape","priorShape","priorScale","likelihoodPrior"))))throw fail("Invalid failure distribution fields");requireText(spec,"family",family);if(number(need(spec,"scale"))<=0||number(need(spec,"shape"))<=0)throw fail("Failure scale/shape must be positive");int nulls=0;for(String key:Arrays.asList("priorShape","priorScale","likelihoodPrior")){JsonElement prior=need(spec,key);if(prior.isJsonNull())nulls++;else signedFinite(prior);}if(nulls!=0&&nulls!=3)throw fail("Failure priors must be all null or all finite");}}
            int maxDepth=0;for(JsonElement item:arr(root,"workflowGraph")){int depth=integer(need(object(item),"depth"));if(depth<1)throw fail("Failure coverage needs positive DAG depth");maxDepth=Math.max(maxDepth,depth);}boolean vmMode=mode.equals("FAILURE_VM")||mode.equals("FAILURE_VM_JOB");int requiredDepth=mode.equals("FAILURE_JOB")||mode.equals("FAILURE_VM_JOB")?maxDepth:0;Set<Integer> vmIds=vmMode?rows(arr(obj(root,"platform"),"vms"),"id").keySet():new HashSet<>(Arrays.asList(0));for(int vm:vmIds){JsonArray row=generatorRows.get(vm);if(row==null||row.size()<=requiredDepth)throw fail("V3 failure generators do not cover declared VM/depth scope");}
        }
        Map<Integer,Integer> taskVms=new HashMap<>();Set<Integer> attempts=new HashSet<>();long compute=0;
        for(JsonElement item:arr(obj(root,"result"),"jobs")){JsonObject job=object(item);if(integer(need(job,"classType"))!=Parameters.ClassType.COMPUTE.value)continue;int id=integer(need(job,"jobId")),status=integer(need(job,"status")),vm=integer(need(job,"vmId"));List<Integer> tasks=listIds(arr(job,"taskIds"));if(!attempts.add(id)||tasks.size()!=1)throw fail("Invalid V3 singleton attempt history");if(!enabled&&status!=Cloudlet.SUCCESS)throw fail("FAILURE_NONE cannot produce a failed V3 attempt");Integer previous=taskVms.putIfAbsent(tasks.get(0),vm);if(previous!=null&&previous!=vm)throw fail("STATIC NOOP attempts cannot change VM for a logical Task");compute++;}
        if(compute-taskVms.size()>budget)throw fail("V3 compute-attempt history exceeds the declared retry budget");
    }
    private static double signedFinite(JsonElement value)throws IOException{if(!value.isJsonPrimitive()||!value.getAsJsonPrimitive().isNumber())throw fail("Expected finite numeric prior");double result=value.getAsDouble();if(!Double.isFinite(result))throw fail("Nonfinite failure prior");return result;}
    private static JsonObject validateStorageSpec(JsonObject platform)throws IOException{
        JsonObject store=obj(platform,"sourceStorage");if(!store.keySet().equals(new HashSet<>(Arrays.asList("attachmentHostId","readBandwidthMbPerSecond","writeBandwidthMbPerSecond","networkBandwidthMbPerSecond"))))throw fail("Invalid sourceStorage fields");
        int host=integer(need(store,"attachmentHostId"));if(!rows(arr(platform,"hosts"),"id").containsKey(host))throw fail("Store attachment is outside the actual platform");
        for(String field:Arrays.asList("readBandwidthMbPerSecond","writeBandwidthMbPerSecond","networkBandwidthMbPerSecond")){double bytes=number(need(store,field))*1_000_000.0;if(!Double.isFinite(bytes)||bytes<Double.MIN_NORMAL)throw fail("Invalid converted store capacity");}return store;
    }
    private JsonObject main(String type,int id)throws IOException{Map<Integer,JsonObject> entries=main.get(type);if(entries==null||!entries.containsKey(id))throw fail("Missing V2 main event "+type+" for Job "+id);return entries.get(id);}
    private Job requireJob(int id)throws IOException{Job job=jobs.get(id);if(job==null)throw fail("Unknown V2 compute Job "+id);return job;}
    private static void put(Map<Integer,JsonObject> map,int id,JsonObject value)throws IOException{if(map.put(id,value)!=null)throw fail("Duplicate V2 event for Job "+id);}
    private static String fileKey(JsonObject file)throws IOException{return integer(need(file,"workflowInputIndex"))+":"+text(file,"name");}
    private static final class ReferenceTotals {long references;double bytes,seconds;int created,joined;}
    private static boolean isV2(String kind){return kind.equals("COHERENT_FILE_DATAFLOW_V2")||kind.equals("COHERENT_FILE_DATAFLOW_NO_CONTENTION_V2");}
    private static boolean isStorage(String kind){return kind.equals("COHERENT_STORAGE_DATAFLOW_V3")||kind.equals("COHERENT_STORAGE_DATAFLOW_NO_CONTENTION_V3");}
    private static final class Checked {
        final boolean storage;final org.workflowsim.data.v2.FileLifecycleEvidence evidence;final JsonObject document;final boolean quiescent;final int requested,completed;final Set<Integer> successful;
        Checked(FileLifecycleCodec.Decoded value)throws IOException{if(value==null)throw fail("V2 lifecycle capture is missing");storage=false;evidence=value.getEvidence();document=value.getDocument();quiescent=value.isQuiescent();requested=value.getRequestedJobCount();completed=value.getCompletedJobCount();successful=value.getSuccessfullyCompletedTaskIds();}
        Checked(org.workflowsim.data.v2.StorageLifecycleCodec.Decoded value)throws IOException{if(value==null)throw fail("V3 storage capture is missing");storage=true;evidence=value.getEvidence().getCapture();document=value.getDocument();quiescent=value.isQuiescent();requested=value.getRequestedJobCount();completed=value.getCompletedJobCount();successful=value.getSuccessfullyCompletedTaskIds();}
        org.workflowsim.data.v2.FileLifecycleEvidence getEvidence(){return evidence;}JsonObject getDocument(){return document;}boolean isQuiescent(){return quiescent;}int getRequestedJobCount(){return requested;}int getCompletedJobCount(){return completed;}Set<Integer> getSuccessfullyCompletedTaskIds(){return successful;}
    }
    private static Map<Integer,JsonObject> rows(JsonArray array,String key)throws IOException{Map<Integer,JsonObject> rows=new LinkedHashMap<>();for(JsonElement row:array){JsonObject value=object(row);if(rows.put(integer(need(value,key)),value)!=null)throw fail("Duplicate "+key);}return rows;}
    private static Set<Integer> ids(JsonArray array)throws IOException{return new HashSet<>(listIds(array));}
    private static List<Integer> listIds(JsonArray array)throws IOException{List<Integer> ids=new ArrayList<>();for(JsonElement item:array)ids.add(integer(item));if(new HashSet<>(ids).size()!=ids.size())throw fail("Duplicate identity");return ids;}
    private static JsonElement need(JsonObject object,String key)throws IOException{if(object==null||!object.has(key))throw fail("Missing V2 context field "+key);return object.get(key);}
    private static JsonObject obj(JsonObject object,String key)throws IOException{return object(need(object,key));}
    private static JsonObject object(JsonElement value)throws IOException{if(value==null||!value.isJsonObject())throw fail("Expected V2 context object");return value.getAsJsonObject();}
    private static JsonArray arr(JsonObject object,String key)throws IOException{JsonElement value=need(object,key);if(!value.isJsonArray())throw fail("Expected V2 context array "+key);return value.getAsJsonArray();}
    private static String text(JsonObject object,String key)throws IOException{JsonElement value=need(object,key);if(!value.isJsonPrimitive()||!value.getAsJsonPrimitive().isString())throw fail("Expected V2 context string "+key);return value.getAsString();}
    private static boolean bool(JsonElement value)throws IOException{if(!value.isJsonPrimitive()||!value.getAsJsonPrimitive().isBoolean())throw fail("Expected boolean");return value.getAsBoolean();}
    private static long whole(JsonElement value)throws IOException{try{if(!value.isJsonPrimitive()||!value.getAsJsonPrimitive().isNumber())throw fail("Expected exact integer");return value.getAsBigDecimal().longValueExact();}catch(ArithmeticException|NumberFormatException invalid){throw new IOException("Invalid exact integer",invalid);}}
    private static int integer(JsonElement value)throws IOException{long id=whole(value);if(id<0||id>Integer.MAX_VALUE)throw fail("Invalid nonnegative int32");return (int)id;}
    private static double number(JsonElement value)throws IOException{if(!value.isJsonPrimitive()||!value.getAsJsonPrimitive().isNumber())throw fail("Expected numeric scalar");double number=value.getAsDouble();if(!Double.isFinite(number)||number<0)throw fail("Invalid nonnegative scalar");return number;}
    private static void requireText(JsonObject object,String key,String expected)throws IOException{if(!text(object,key).equals(expected))throw fail("Unexpected "+key);}
    private static void same(JsonElement a,JsonElement b,String message)throws IOException{
        if(a==null||b==null)throw fail(message);
        if(a.isJsonObject()&&b.isJsonObject()){JsonObject x=a.getAsJsonObject(),y=b.getAsJsonObject();if(!x.keySet().equals(y.keySet()))throw fail(message);for(String key:x.keySet())same(x.get(key),y.get(key),message);return;}
        if(a.isJsonArray()&&b.isJsonArray()){JsonArray x=a.getAsJsonArray(),y=b.getAsJsonArray();if(x.size()!=y.size())throw fail(message);for(int i=0;i<x.size();i++)same(x.get(i),y.get(i),message);return;}
        if(a.isJsonPrimitive()&&b.isJsonPrimitive()&&a.getAsJsonPrimitive().isNumber()&&b.getAsJsonPrimitive().isNumber()){if(a.getAsBigDecimal().compareTo(b.getAsBigDecimal())==0)return;}else if(a.equals(b))return;throw fail(message);
    }
    private static double timeTolerance(double value){return Math.min(8*Math.ulp(value),value*1e-12);}
    private static IOException fail(String message){return new IOException(message);}
    private static final class Job {final int vm,task,status;final double start,finish;Job(int vm,int task,int status,double start,double finish){this.vm=vm;this.task=task;this.status=status;this.start=start;this.finish=finish;}}
}
