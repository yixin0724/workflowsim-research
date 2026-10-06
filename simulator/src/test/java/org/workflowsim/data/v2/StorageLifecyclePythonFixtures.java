package org.workflowsim.data.v2;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import org.cloudbus.cloudsim.Log;
import org.workflowsim.FileItem;
import org.workflowsim.Task;
import org.workflowsim.data.DataMovementModel;
import org.workflowsim.data.NetworkEvidenceConfig;
import org.workflowsim.experiment.ExperimentArtifactValidator;
import org.workflowsim.experiment.ExperimentArtifactWriter;
import org.workflowsim.experiment.SimulationRunner;
import org.workflowsim.failure.FailureModelConfig;
import org.workflowsim.failure.FailureParameters;
import org.workflowsim.network.NetworkTopologySpec;
import org.workflowsim.platform.PlatformProfile;
import org.workflowsim.utils.DistributionGenerator;
import org.workflowsim.utils.DistributionSpec;
import org.workflowsim.utils.Parameters;
import org.workflowsim.utils.ReplicaCatalog;
import org.workflowsim.utils.SimulationConfig;

/** Fresh storage V3 Kernel bundles and explicit standalone prefixes for independent Python validation. */
public final class StorageLifecyclePythonFixtures {
    private static final int BUDGET=20000;
    private static final String FLOW="<adag><job id=\"p\" runtime=\"1\"><uses file=\"external\" link=\"input\" size=\"0.5\"/><uses file=\"x\" link=\"output\" size=\"1000000\"/><uses file=\"unused\" link=\"output\" size=\"500000\"/><uses file=\"zero\" link=\"output\" size=\"0\"/></job><job id=\"c\" runtime=\"1\"><uses file=\"x\" link=\"input\" size=\"1000000\"/><uses file=\"x\" link=\"input\" size=\"1000000\"/><uses file=\"zero\" link=\"input\" size=\"0\"/></job><child ref=\"c\"><parent ref=\"p\"/></child></adag>";
    private StorageLifecyclePythonFixtures(){ }
    /** @param args fresh output root */
    public static void main(String[] args)throws Exception{
        if(args.length!=1)throw new IllegalArgumentException("Usage: StorageLifecyclePythonFixtures <fresh-root>");Path root=Paths.get(args[0]).toAbsolutePath().normalize();if(Files.exists(root))throw new IllegalArgumentException("Fixture root already exists: "+root);Files.createDirectories(root);Log.disable();JsonArray files=new JsonArray(),manifests=new JsonArray();Path input=text(root.resolve("inputs/flow.dax"),FLOW);
        for(boolean storeInputs:new boolean[]{false,true})for(boolean shared:new boolean[]{false,true})for(boolean fat:new boolean[]{false,true})saveRun(root,(storeInputs?"shared-input-":"local-")+(shared?"shared-":"isolated-")+(fat?"fat":"endpoint"),configuration(input,storeInputs,shared,2,BUDGET),platform(2,fat),files,manifests);
        saveRun(root,"same-vm-gate",configuration(input,true,true,1,BUDGET),platform(1,false),files,manifests);
        Path zero=text(root.resolve("inputs/zero.dax"),"<adag><job id=\"z\" runtime=\"1\"><uses file=\"z\" link=\"output\" size=\"0\"/></job></adag>"),empty=text(root.resolve("inputs/empty.dax"),"<adag><job id=\"e\" runtime=\"1\"/></adag>");
        saveRun(root,"zero-output",configuration(zero,true,true,1,BUDGET),platform(1,false),files,manifests);saveRun(root,"no-files",configuration(empty,false,false,1,BUDGET),platform(1,true),files,manifests);
        for(boolean storeInputs:new boolean[]{false,true})saveRun(root,"off-"+storeInputs,configuration(input,storeInputs,true,2,0),platform(2,true),files,manifests);
        Path later=text(root.resolve("inputs/later.dax"),FLOW);SimulationConfig staggered=SimulationConfig.builder(Arrays.asList(input.toString(),later.toString()),2).workflowArrivalSeconds(Arrays.asList(0.0,20.0)).planningAlgorithm(Parameters.PlanningAlgorithm.RANDOM).schedulingAlgorithm(Parameters.SchedulingAlgorithm.STATIC).fileSystem(ReplicaCatalog.FileSystem.SHARED).randomSeed(1).dataMovementModel(DataMovementModel.coherentStorageDataflowV3()).networkEvidence(NetworkEvidenceConfig.storageLifecycleV3(BUDGET)).build();saveRun(root,"staggered-scopes",staggered,platform(2,true),files,manifests);
        FailureModelConfig failure=FailureModelConfig.builder().clusteringAlgorithm(FailureParameters.FTCluteringAlgorithm.FTCLUSTERING_NOOP).monitorMode(FailureParameters.FTCMonitor.MONITOR_NONE).generatorMode(FailureParameters.FTCFailure.FAILURE_ALL).generatorSpecs(new DistributionSpec[][]{{DistributionSpec.of(DistributionGenerator.DistributionFamily.WEIBULL,2,1)}}).maxTotalRetryJobs(64).build();for(long seed:new long[]{1,5,17,22})saveRun(root,"retry-"+seed,configuration(input,true,true,1,BUDGET).toBuilder().randomSeed(seed).failureModel(failure).build(),platform(1,false),files,manifests);
        StringBuilder joined=new StringBuilder("<adag><job id=\"p\" runtime=\"1\"><uses file=\"x\" link=\"output\" size=\"1000000\"/></job>");for(int i=0;i<3;i++)joined.append("<job id=\"c").append(i).append("\" runtime=\"1\"><uses file=\"x\" link=\"input\" size=\"1000000\"/></job><child ref=\"c").append(i).append("\"><parent ref=\"p\"/></child>");joined.append("</adag>");Path join=text(root.resolve("inputs/join.dax"),joined.toString());saveRun(root,"deferred-joined-read",configuration(join,true,true,2,BUDGET),platform(2,false),files,manifests);
        Task p=task(1,out("x",100)),c=task(2,in("x",100));edge(p,c);CoherentDataflowRuntime r=runtime(true,100,20,10,100,p,c);r.requestJob(0,one(1),7,0);r.drainReadyJobIds();r.cpuStarted(0,one(1),7,0);r.jobFinished(0,one(1),7,Collections.singletonList(true),1);r.requestJob(1,one(2),42,1);savePrefix(root,"generic-source-wait",r,files);r.advance(11);savePrefix(root,"generic-target-wait",r,files);r.advance(16);savePrefix(root,"generic-ready-not-started",r,files);
        Task tiny=task(1,in("tiny",Double.MIN_VALUE));r=runtime(false,1,1,1,1,tiny);r.requestJob(0,one(1),7,0);r.advance(Double.MIN_VALUE);savePrefix(root,"generic-tiny-clock",r,files);
        Task a=task(1,out("a",1e308)),b=task(2,out("b",1e308));r=runtime(false,4,4,4,4,a,b);r.requestJob(0,one(1),7,0);r.drainReadyJobIds();r.cpuStarted(0,one(1),7,0);r.requestJob(1,one(2),42,0);r.drainReadyJobIds();r.cpuStarted(1,one(2),42,0);r.jobFinished(0,one(1),7,Collections.singletonList(true),1);r.jobFinished(1,one(2),42,Collections.singletonList(true),1);drain(r);savePrefix(root,"generic-huge-output-total",r,files);
        Task big=task(1,out("a",0x1.0p53)),consumer=task(2,in("a",0x1.0p53),in("b",1),in("c",1));edge(big,consumer);r=runtime(true,1e16,1,0x1.0p53,1e16,big,consumer);r.requestJob(0,one(1),7,0);r.drainReadyJobIds();r.cpuStarted(0,one(1),7,0);r.jobFinished(0,one(1),7,Collections.singletonList(true),1);r.requestJob(1,one(2),42,1);drain(r);if(r.getPreparation(1).getRequiredBytes()!=0x1.0p53||r.getPreparation(1).getIsolatedSeconds()!=0x1.0p53+2)throw new AssertionError("Canonical request and resolution-order aggregates lost distinction");savePrefix(root,"generic-deferred-number-order",r,files);
        JsonObject index=new JsonObject();index.addProperty("schema","workflowsim-storage-lifecycle-fixtures-v3");index.add("standalone",files);index.add("manifests",manifests);ExperimentArtifactWriter.writeJson(root.resolve("fixtures.json"),index);System.out.println("STORAGE_LIFECYCLE_FIXTURES standalone="+files.size()+" manifests="+manifests.size()+" root="+root);
    }
    private static void saveRun(Path root,String name,SimulationConfig config,PlatformProfile platform,JsonArray files,JsonArray manifests)throws Exception{org.workflowsim.experiment.SimulationReport report=new SimulationRunner().run(config,platform);if(!report.isWorkflowCompletedSuccessfully())throw new IllegalStateException("Incomplete storage fixture "+name);ExperimentArtifactWriter.ExperimentArtifacts out=ExperimentArtifactWriter.write(report,root.resolve(name),"result");ExperimentArtifactValidator.validate(out.getManifest());manifests.add(root.relativize(out.getManifest()).toString());if(out.getStorageLifecycle()!=null){StorageLifecycleCodec.decode(new String(Files.readAllBytes(out.getStorageLifecycle()),StandardCharsets.UTF_8));files.add(root.relativize(out.getStorageLifecycle()).toString());}}
    private static void savePrefix(Path root,String name,CoherentDataflowRuntime runtime,JsonArray inputs)throws Exception{String encoded=StorageLifecycleCodec.encode(runtime.captureStorageEvidence());StorageLifecycleCodec.decode(encoded);Path path=text(root.resolve(name+".storage-lifecycle.json"),encoded);inputs.add(root.relativize(path).toString());}
    private static SimulationConfig configuration(Path input,boolean storeInputs,boolean shared,int vms,int budget){return SimulationConfig.builder(input.toString(),vms).planningAlgorithm(Parameters.PlanningAlgorithm.RANDOM).schedulingAlgorithm(Parameters.SchedulingAlgorithm.STATIC).fileSystem(storeInputs?ReplicaCatalog.FileSystem.SHARED:ReplicaCatalog.FileSystem.LOCAL).randomSeed(1).cloudSimMinEventIntervalSeconds(.1).dataMovementModel(shared?DataMovementModel.coherentStorageDataflowV3():DataMovementModel.coherentStorageDataflowNoContentionV3()).networkEvidence(budget==0?NetworkEvidenceConfig.off():NetworkEvidenceConfig.storageLifecycleV3(budget)).build();}
    private static PlatformProfile platform(int count,boolean fat){PlatformProfile.Builder p=PlatformProfile.builder("storage-corpus");int[] ids={7,42};for(int i=0;i<count;i++){int host=10+i*10;p.addHost(new PlatformProfile.HostSpec(host,2,2000,4096,10000,1000000)).addVm(new PlatformProfile.VmSpec(ids[i],1000,1,512,10,10000,"Xen",PlatformProfile.CloudletSchedulerMode.SPACE_SHARED)).pinVmToHost(ids[i],host);}p.addHost(new PlatformProfile.HostSpec(30,2,2000,4096,10000,1000000));p.sourceStorage(DataflowStorageSpec.of(30,1,.5,2));if(fat)p.networkTopology(NetworkTopologySpec.fatTree(4,5));return p.build();}
    private static CoherentDataflowRuntime runtime(boolean store,double vm,double read,double write,double nic,Task...tasks){Map<Integer,Double> caps=new LinkedHashMap<>();Map<Integer,Integer> hosts=new LinkedHashMap<>(),scopes=new LinkedHashMap<>();caps.put(7,vm);caps.put(42,vm);hosts.put(7,10);hosts.put(42,20);for(Task t:tasks)scopes.put(t.getCloudletId(),0);DataTransferFabric f=DataTransferFabric.withStorage(caps,hosts,null,DataflowStorageSpec.of(30,read/1e6,write/1e6,nic/1e6));CoherentDataflowRuntime r=CoherentDataflowRuntime.withStorage(true,BUDGET,store);r.initializePlan(DataflowFilePlan.capture(Arrays.asList(tasks),scopes),f.getLocations());r.bindFabric(f);return r;}
    private static void drain(CoherentDataflowRuntime r){while(r.getNextCompletionTime()!=null)r.advance(r.getNextCompletionTime());}
    private static java.util.List<Integer> one(int id){return Collections.singletonList(id);}
    private static Path text(Path path,String value)throws Exception{Files.createDirectories(path.getParent());Files.write(path,value.getBytes(StandardCharsets.UTF_8));return path;}
    private static Task task(int id,FileItem...files){Task t=new Task(id,1000);for(FileItem f:files)t.addFile(f);return t;}
    private static FileItem in(String name,double bytes){return file(name,bytes,Parameters.FileType.INPUT);}
    private static FileItem out(String name,double bytes){return file(name,bytes,Parameters.FileType.OUTPUT);}
    private static FileItem file(String name,double bytes,Parameters.FileType type){FileItem f=new FileItem(name,bytes);f.setType(type);return f;}
    private static void edge(Task a,Task b){a.addChild(b);b.addParent(a);}
}
