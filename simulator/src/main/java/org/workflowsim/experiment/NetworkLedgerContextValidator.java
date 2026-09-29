package org.workflowsim.experiment;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.workflowsim.data.DataMovementModel;
import org.workflowsim.data.NetworkEvidenceConfig;
import org.workflowsim.data.NetworkFlowBinding;
import org.workflowsim.data.NetworkInputDemandSnapshot;
import org.workflowsim.data.NetworkLedgerCodec;
import org.workflowsim.data.NetworkRunEvidence;
import org.workflowsim.data.TransferTraceEvent;
import org.workflowsim.data.TransferTraceSnapshot;
import org.workflowsim.utils.Parameters;

/** Binds validated network evidence to the run's configuration, platform, Job outcomes and main events. */
final class NetworkLedgerContextValidator {
    private static final Gson JSON=new GsonBuilder().serializeNulls().create();
    private final NetworkRunEvidence evidence;
    private final String model,fileSystem;
    private final double simulationEnd;
    private final Map<Integer,JobView> jobs=new LinkedHashMap<Integer,JobView>();
    private final Map<Integer,VmView> vms=new LinkedHashMap<Integer,VmView>();
    private final Map<Integer,StageView> stages=new HashMap<Integer,StageView>();
    private final Map<Integer,Double> ready=new HashMap<Integer,Double>();
    private final Topology topology;
    private long references;
    private BigDecimal required=BigDecimal.ZERO,requiredError=BigDecimal.ZERO;

    static void validateReport(SimulationReport report)throws IOException{
        if(report.getNetworkEvidence()==null)throw fail("ON configuration is missing its captured network evidence");
        JsonObject context=JSON.toJsonTree(ExperimentManifestWriter.networkContextSnapshot(report)).getAsJsonObject();
        NetworkLedgerContextValidator check=new NetworkLedgerContextValidator(context,report.getNetworkEvidence());
        for(SimulationEvent event:report.getEvents())check.acceptEvent(JSON.toJsonTree(event).getAsJsonObject());
        check.finish();
    }

    NetworkLedgerContextValidator(JsonObject root,NetworkRunEvidence evidence)throws IOException{
        this.evidence=evidence;JsonObject config=obj(root,"configuration"),platform=obj(root,"platform"),result=obj(root,"result");
        NetworkEvidenceConfig option;
        try{option=NetworkLedgerCodec.decodeConfig(required(config,"networkEvidence"));}
        catch(IllegalArgumentException e){throw new IOException("Invalid manifest networkEvidence option",e);}
        if(option.getMode()!=evidence.getConfig().getMode()||option.getMaxTraceRecords()!=evidence.getConfig().getMaxTraceRecords())throw fail("Manifest and ledger capture configuration differ");
        model=text(obj(config,"dataMovementModel"),"kind");fileSystem=text(config,"fileSystem");
        if(!model.equals(evidence.getModelKind().name()))throw fail("Manifest and ledger data movement models differ");
        if(!"LOCAL".equals(fileSystem)&&!"SHARED".equals(fileSystem))throw fail("Unknown file system for network context");
        simulationEnd=nonnegative(result,"simulationEndSeconds");
        if(evidence.getTraceSnapshot().getEngineTime()>simulationEnd)throw fail("Network watermark exceeds simulation end");
        JsonObject assignments=obj(result,"actualVmHostAssignments");Set<Integer> hosts=new HashSet<Integer>();
        for(JsonElement h:arr(platform,"hosts"))if(!hosts.add(integer(object(h,"host"),"id")))throw fail("Duplicate host ID");
        for(JsonElement item:arr(platform,"vms")){
            JsonObject vm=object(item,"VM");int id=integer(vm,"id"),host=integer(vm,"preflightHostId");
            if(!hosts.contains(host)||!assignments.has(Integer.toString(id))||whole(assignments.get(Integer.toString(id)),"VM host")!=host)throw fail("VM host placement differs from actual run");
            long bandwidth=whole(required(vm,"bandwidth"),"VM bandwidth");double capacity=bandwidth*1_000_000.0;
            if(bandwidth<=0||!Double.isFinite(capacity)||vms.put(id,new VmView(host,capacity))!=null)throw fail("Invalid VM capacity or ID");
        }
        if(assignments.size()!=vms.size())throw fail("Actual VM placement contains extra identities");
        topology=evidence.getModelKind()==DataMovementModel.Kind.PRE_EXECUTION_TRANSFER_DELAY_WITH_FAT_TREE_CONTENTION_V1
                ?new Topology(object(required(platform,"networkTopology"),"networkTopology"),hosts):null;
        for(JsonElement item:arr(result,"jobs")){
            JsonObject job=object(item,"Job");int id=integer(job,"jobId"),vm=integer(job,"vmId"),type=integer(job,"classType");
            List<Integer> taskIds=new ArrayList<Integer>();for(JsonElement task:arr(job,"taskIds"))taskIds.add(integer(task,"taskId"));
            JobView view=new JobView(vm,type,nonnegative(job,"startTime"),nonnegative(job,"finishTime"),taskIds);
            if(view.finish<view.start||view.finish>simulationEnd||jobs.put(id,view)!=null)throw fail("Invalid Job outcome identity or times");
            if(type==Parameters.ClassType.COMPUTE.value&&!vms.containsKey(vm))throw fail("Compute Job has unknown VM");
        }
        if(evidence.getInputDemand()==null)throw fail("Run ledger is missing actual input-reference observations");
    }

    void acceptEvent(JsonObject event)throws IOException{
        String type=text(event,"type");
        if(!SimulationEventType.JOB_READY.name().equals(type)&&!SimulationEventType.DATA_STAGE_IN_MODELED.name().equals(type))return;
        if(integer(event,"classType")!=Parameters.ClassType.COMPUTE.value)return;
        int jobId=integer(event,"jobId");JobView job=jobs.get(jobId);
        if(job==null||job.type!=Parameters.ClassType.COMPUTE.value||integer(event,"vmId")!=job.vm)throw fail("Network main event has unknown Job/VM");
        List<Integer> tasks=new ArrayList<Integer>();for(JsonElement t:arr(event,"taskIds"))tasks.add(integer(t,"event taskId"));
        if(!tasks.equals(job.tasks))throw fail("Network main event Task IDs differ from Job outcome");
        double time=nonnegative(event,"simulationTime");if(time>job.start)throw fail("Input readiness/modeling occurs after Job starts");
        if(SimulationEventType.JOB_READY.name().equals(type)){
            if(ready.put(jobId,time)!=null)throw fail("Duplicate Job readiness event");return;
        }
        JsonObject attributes=obj(event,"attributes");
        if(!model.equals(text(attributes,"dataMovementModel")))throw fail("Stage-in event uses another data movement model");
        int files=integer(attributes,"modeledTransferFileCount"),groups=integer(attributes,"contentionTransferGroupCount");
        double bytes=nonnegative(attributes,"requiredFileBytes");
        if(files<0||groups<0||groups>files||(groups>0&&bytes==0))throw fail("Invalid modeled input reference/group counts");
        if(stages.put(jobId,new StageView(time,bytes,groups,files))!=null)throw fail("Duplicate modeled input event");
        try{references=Math.addExact(references,(long)files);}catch(ArithmeticException e){throw new IOException("Input reference count overflow",e);}
        required=required.add(exact(bytes));requiredError=requiredError.add(rounding(bytes,(long)files+groups+1));
    }

    void finish()throws IOException{
        boolean complete=evidence.getTraceSnapshot().getStatus()==TransferTraceSnapshot.Status.COMPLETE;
        for(Map.Entry<Integer,JobView> job:jobs.entrySet())if(job.getValue().type==Parameters.ClassType.COMPUTE.value){
            StageView stage=stages.get(job.getKey());Double clock=ready.get(job.getKey());
            if(stage==null||clock==null||clock.doubleValue()!=stage.time)throw fail("Missing/inconsistent Job readiness or stage-in event");
        }
        if(!evidence.isEngineCreated()&&!stages.isEmpty())throw fail("Modeled input stage requires a created fluid engine");
        NetworkInputDemandSnapshot input=evidence.getInputDemand();
        if(input.getReferenceCount()!=references)throw fail("Input reference counter differs from main events");
        if(required.subtract(input.getRequiredReferenceBytes()).abs().compareTo(requiredError)>0)throw fail("Input reference bytes differ beyond modeled summation rounding");
        Map<Long,NetworkFlowBinding> bindings=new HashMap<Long,NetworkFlowBinding>();
        for(NetworkFlowBinding b:evidence.getBindings()){
            JobView job=jobs.get(b.getJobId());
            if(job==null||job.type!=Parameters.ClassType.COMPUTE.value||!job.tasks.equals(b.getTaskIds())||!endpoint(job.vm).equals(b.getDestinationEndpoint()))throw fail("Network binding differs from consuming Job/Task/VM");
            if(bindings.put(b.getAdmissionOrdinal(),b)!=null)throw fail("Duplicate network binding ordinal");
            List<String> path=new ArrayList<String>();
            if(b.getGroupKind()==NetworkFlowBinding.GroupKind.EXTERNAL_GROUP_V1){
                if(!"source".equals(b.getSourceEndpoint())||b.getParentJobId()!=null)throw fail("External group has an invalid source");
                if(topology==null)path.add("source");path.add(endpoint(job.vm));
            }else{
                JobView parent=jobs.get(b.getParentJobId());
                if(parent==null||!vms.containsKey(parent.vm))throw fail("Parent group has an unknown producer Job/VM");
                String source="LOCAL".equals(fileSystem)?endpoint(parent.vm):"source";
                if(!source.equals(b.getSourceEndpoint()))throw fail("Constrained source differs from V1 producer/storage policy");
                path.add(source);path.add(endpoint(job.vm));
                if(topology!=null){if(!"LOCAL".equals(fileSystem))throw fail("Fat-tree capture requires LOCAL inputs");path.addAll(topology.route(vms.get(parent.vm).host,vms.get(job.vm).host));}
            }
            if(!path.equals(b.getOccupiedResources()))throw fail("Binding resource path differs from declared V1 routing");
        }
        Set<String> capacities=new HashSet<String>();Set<Long> completed=new HashSet<Long>();
        Map<Integer,Integer> counts=new HashMap<Integer,Integer>();Map<Integer,BigDecimal> payloads=new HashMap<Integer,BigDecimal>();
        BigDecimal admitted=BigDecimal.ZERO;double largestGroup=0;boolean started=false;long linkCount=0;
        for(TransferTraceEvent e:evidence.getTraceSnapshot().getEvents()){
            if(e.getType()==TransferTraceEvent.Type.CAPACITY){
                String key=e.getCapacity().getResourceKey();double expected;
                if(started||e.getEffectiveTime()!=0||!capacities.add(key))throw fail("Run capacities must be initialized once before admissions");
                if(key.startsWith("VM:")){
                    VmView vm=vms.get(canonicalInt(key.substring(3)));if(vm==null)throw fail("Unknown VM capacity resource");expected=vm.capacity;
                }else if(topology!=null&&topology.validLink(key)){expected=topology.capacity;linkCount++;}
                else throw fail("Unknown or unmodeled capacity resource: "+key);
                if(e.getCapacity().getCapacityBytesPerSecond()!=expected)throw fail("Capacity differs from platform bandwidth");
            }else if(e.getType()==TransferTraceEvent.Type.START){
                started=true;NetworkFlowBinding b=bindings.get(e.getAdmissionOrdinal());
                if(b==null)throw fail("Retained admission lacks a binding");StageView stage=stages.get(b.getJobId());
                if(stage==null||e.getObservedTime()!=stage.time)throw fail("Admission does not start at modeled Job-ready time");
                if(b.getParentJobId()!=null&&jobs.get(b.getParentJobId()).finish>e.getObservedTime())throw fail("Parent input starts before producer finishes");
                int n=counts.containsKey(b.getJobId())?counts.get(b.getJobId())+1:1;counts.put(b.getJobId(),n);
                BigDecimal amount=exact(e.getStart().getBytes());admitted=admitted.add(amount);largestGroup=Math.max(largestGroup,e.getStart().getBytes());
                payloads.put(b.getJobId(),(payloads.containsKey(b.getJobId())?payloads.get(b.getJobId()):BigDecimal.ZERO).add(amount));
            }else if(e.getType()==TransferTraceEvent.Type.COMPLETE){
                NetworkFlowBinding b=bindings.get(e.getAdmissionOrdinal());if(b==null)throw fail("Completion has no retained binding");
                if(e.getObservedTime()>jobs.get(b.getJobId()).start)throw fail("Job starts before its network inputs are observed complete");
                completed.add(e.getAdmissionOrdinal());
            }
        }
        if(evidence.isEngineCreated()&&(complete||started)){
            for(Integer vm:vms.keySet())if(!capacities.contains(endpoint(vm)))throw fail("Missing declared VM endpoint capacity");
            long expectedLinks=topology==null?0:topology.linkCount();
            if(linkCount!=expectedLinks||capacities.size()!=(long)vms.size()+expectedLinks)throw fail("Missing or extra declared network capacities");
        }
        for(Map.Entry<Integer,StageView> entry:stages.entrySet()){
            int actual=counts.containsKey(entry.getKey())?counts.get(entry.getKey()):0;
            if(actual>entry.getValue().groups||(complete&&actual!=entry.getValue().groups))throw fail("Ledger group count differs from main stage-in event");
            BigDecimal payload=payloads.containsKey(entry.getKey())?payloads.get(entry.getKey()):BigDecimal.ZERO;
            if(payload.subtract(exact(entry.getValue().bytes)).compareTo(rounding(entry.getValue().bytes,(long)entry.getValue().files+entry.getValue().groups+1))>0)throw fail("Admission payload exceeds modeled input demand");
        }
        if(complete){
            if(completed.size()!=bindings.size())throw fail("Completed run retains unfinished input transfers");
            if(admitted.signum()==0){if(input.getTransferableReferenceBytes().signum()!=0)throw fail("Nonlocal positive input has no admitted transfer");}
            else if(admitted.subtract(input.getTransferableReferenceBytes()).abs().compareTo(rounding(largestGroup,references+1))>0)
                throw fail("Admitted payload differs beyond legacy group summation rounding");
        }
    }

    private static final class JobView {
        final int vm,type;final double start,finish;final List<Integer> tasks;
        JobView(int vm,int type,double start,double finish,List<Integer> tasks){this.vm=vm;this.type=type;this.start=start;this.finish=finish;this.tasks=tasks;}
    }
    private static final class VmView {final int host;final double capacity;VmView(int host,double capacity){this.host=host;this.capacity=capacity;}}
    private static final class StageView {final double time,bytes;final int groups,files;StageView(double time,double bytes,int groups,int files){this.time=time;this.bytes=bytes;this.groups=groups;this.files=files;}}

    /** Arithmetic-only topology view: never allocate an untrusted k^3 link graph. */
    private static final class Topology {
        final int k,half,cores,available;final double capacity;final Map<Integer,int[]> placement=new HashMap<Integer,int[]>();
        Topology(JsonObject raw,Set<Integer> hosts)throws IOException{
            if(!"FAT_TREE".equals(text(raw,"kind")))throw fail("Unknown topology kind");k=integer(raw,"k");
            if(k<2||k%2!=0||(long)k*k/2>Integer.MAX_VALUE)throw fail("Invalid Fat-tree size");half=k/2;
            cores=required(raw,"coreSwitchCount").isJsonNull()?half*half:integer(raw,"coreSwitchCount");
            if(cores<1||cores>(long)half*half)throw fail("Invalid core count");available=(int)(((long)cores+half-1)/half);
            capacity=nonnegative(raw,"linkBandwidthMbPerSecond")*1_000_000.0;
            if(!(capacity>0)||!Double.isFinite(capacity))throw fail("Unrepresentable link capacity");
            JsonElement explicit=required(raw,"hostEdgePlacements");List<Integer> sorted=new ArrayList<Integer>(hosts);Collections.sort(sorted);
            if(explicit.isJsonNull())for(int i=0;i<sorted.size();i++){int edge=i%(k*half);placement.put(sorted.get(i),new int[]{edge/half,edge%half});}
            else{
                JsonObject entries=object(explicit,"hostEdgePlacements");if(entries.size()!=hosts.size())throw fail("Incomplete host placement");
                for(Integer host:hosts){int edge=integer(required(entries,Integer.toString(host)),"host edge");if(edge<0||edge>=(long)k*half)throw fail("Invalid host edge");placement.put(host,new int[]{edge/half,edge%half});}
            }
        }
        long linkCount(){return 2L*placement.size()+2L*k*half*half+2L*k*cores;}
        List<String> route(int source,int destination)throws IOException{
            int[] s=placement.get(source),d=placement.get(destination);if(s==null||d==null)throw fail("Unplaced VM host");
            List<String> out=new ArrayList<String>();if(source==destination)return out;
            String se="EDGE:"+s[0]+":"+s[1],de="EDGE:"+d[0]+":"+d[1];out.add("LINK:ACC:"+source+"->"+se);
            if(!se.equals(de)){
                int agg=s[1]%available;out.add("LINK:"+se+"->AGG:"+s[0]+":"+agg);
                if(s[0]!=d[0]){int uplinks=Math.min(half,cores-agg*half);int c=agg*half+(s[1]+d[1]+s[0]+d[0])%uplinks;
                    out.add("LINK:AGG:"+s[0]+":"+agg+"->CORE:"+c);out.add("LINK:CORE:"+c+"->AGG:"+d[0]+":"+agg);}
                out.add("LINK:AGG:"+d[0]+":"+agg+"->"+de);
            }
            out.add("LINK:"+de+"->ACC:"+destination);return out;
        }
        boolean validLink(String key){
            if(!key.startsWith("LINK:"))return false;
            try{
                String[] edge=key.substring(5).split("->",-1);if(edge.length!=2)return false;
                String[] a=edge[0].split(":",-1),b=edge[1].split(":",-1);
                return directed(a,b)||directed(b,a);
            }catch(IOException e){return false;}
        }
        boolean directed(String[] a,String[] b)throws IOException{
            if(a.length==2&&"ACC".equals(a[0])&&b.length==3&&"EDGE".equals(b[0])){
                int[] p=placement.get(canonicalInt(a[1]));return p!=null&&p[0]==canonicalInt(b[1])&&p[1]==canonicalInt(b[2]);
            }
            if(a.length==3&&"EDGE".equals(a[0])&&b.length==3&&"AGG".equals(b[0])){
                int p=canonicalInt(a[1]),e=canonicalInt(a[2]),q=canonicalInt(b[1]),g=canonicalInt(b[2]);return p<k&&p==q&&e<half&&g<half;
            }
            if(a.length==3&&"AGG".equals(a[0])&&b.length==2&&"CORE".equals(b[0])){
                int p=canonicalInt(a[1]),g=canonicalInt(a[2]),c=canonicalInt(b[1]);return p<k&&g<half&&c<cores&&g==c/half;
            }
            return false;
        }
    }
    private static String endpoint(int id){return "VM:"+id;}
    private static BigDecimal exact(double value){return new BigDecimal(value);}
    private static BigDecimal rounding(double scale,long terms){return scale<Double.MIN_NORMAL?BigDecimal.ZERO:
            exact(Math.ulp(scale)).multiply(BigDecimal.valueOf(2)).multiply(BigDecimal.valueOf(terms).add(BigDecimal.ONE));}
    private static JsonElement required(JsonObject o,String key)throws IOException{if(!o.has(key))throw fail("Missing "+key);return o.get(key);}
    private static JsonObject obj(JsonObject o,String key)throws IOException{return object(required(o,key),key);}
    private static JsonObject object(JsonElement e,String key)throws IOException{if(e==null||!e.isJsonObject())throw fail("Expected object "+key);return e.getAsJsonObject();}
    private static JsonArray arr(JsonObject o,String key)throws IOException{JsonElement e=required(o,key);if(!e.isJsonArray())throw fail("Expected array "+key);return e.getAsJsonArray();}
    private static String text(JsonObject o,String key)throws IOException{JsonElement e=required(o,key);if(!e.isJsonPrimitive()||!e.getAsJsonPrimitive().isString())throw fail("Expected text "+key);return e.getAsString();}
    private static BigDecimal decimal(JsonElement e,String name)throws IOException{
        if(e==null||!e.isJsonPrimitive()||!e.getAsJsonPrimitive().isNumber())throw fail("Expected numeric "+name);
        try{String token=e.getAsString();if(token.length()>4096)throw fail("Oversized number "+name);BigDecimal n=new BigDecimal(token);
            if(n.precision()>4096||Math.abs((long)n.scale())>4096)throw fail("Oversized numeric exponent "+name);return n;}
        catch(NumberFormatException x){throw new IOException("Invalid number "+name,x);}
    }
    private static long whole(JsonElement e,String name)throws IOException{try{return decimal(e,name).longValueExact();}catch(ArithmeticException x){throw new IOException("Expected exact integer "+name,x);}}
    private static int integer(JsonObject o,String key)throws IOException{return integer(required(o,key),key);}
    private static int integer(JsonElement e,String name)throws IOException{long n=whole(e,name);if(n<Integer.MIN_VALUE||n>Integer.MAX_VALUE)throw fail("Integer out of range "+name);return (int)n;}
    private static double nonnegative(JsonObject o,String key)throws IOException{BigDecimal n=decimal(required(o,key),key);double d=n.doubleValue();if(!Double.isFinite(d)||d<0||(d==0&&n.signum()!=0))throw fail("Invalid finite nonnegative "+key);return d;}
    private static int canonicalInt(String value)throws IOException{try{int n=Integer.parseInt(value);if(n<0||!Integer.toString(n).equals(value))throw fail("Invalid resource ID");return n;}catch(NumberFormatException e){throw new IOException("Invalid resource ID",e);}}
    private static IOException fail(String message){return new IOException("Network ledger context: "+message);}
}
