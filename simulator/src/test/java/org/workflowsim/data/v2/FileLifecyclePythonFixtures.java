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
import org.workflowsim.experiment.SimulationReport;
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

/** Fresh actual V2 bundles and explicit generic complete-prefix cases for independent Python gates. */
public final class FileLifecyclePythonFixtures {
    private static final int BUDGET=20000;
    private static final String FLOW="<adag><job id=\"p\" runtime=\"1\"><uses file=\"external-a\" link=\"input\" size=\"0.125\"/><uses file=\"external-b\" link=\"input\" size=\"0.375\"/><uses file=\"a\" link=\"output\" size=\"2000000\"/><uses file=\"b\" link=\"output\" size=\"3000000\"/></job><job id=\"c\" runtime=\"1\"><uses file=\"a\" link=\"input\" size=\"2000000\"/><uses file=\"b\" link=\"input\" size=\"3000000\"/></job><child ref=\"c\"><parent ref=\"p\"/></child></adag>";
    private FileLifecyclePythonFixtures(){ }
    /** @param args fresh output root */
    public static void main(String[] args)throws Exception{
        if(args.length!=1)throw new IllegalArgumentException("Usage: FileLifecyclePythonFixtures <fresh-root>");Path root=Paths.get(args[0]).toAbsolutePath().normalize();if(Files.exists(root))throw new IllegalArgumentException("Fixture root must not exist: "+root);Files.createDirectories(root);Log.disable();
        JsonArray standalone=new JsonArray(),manifests=new JsonArray();Path input=text(root.resolve("inputs/flow.dax"),FLOW);
        for(boolean shared:new boolean[]{true,false})for(boolean fat:new boolean[]{false,true})saveRun(root,(shared?"shared-":"isolated-")+(fat?"fat":"endpoint"),config(input,shared,BUDGET,2),platform(fat,2,1),standalone,manifests);
        Path zero=text(root.resolve("inputs/zero.dax"),"<adag><job id=\"z\" runtime=\"1\"><uses file=\"zero\" link=\"input\" size=\"0\"/><uses file=\"zero\" link=\"input\" size=\"0\"/></job></adag>");
        Path noInput=text(root.resolve("inputs/no-input.dax"),"<adag><job id=\"z\" runtime=\"1\"/></adag>");
        saveRun(root,"zero-reference",config(zero,true,BUDGET,2),platform(false,2,1),standalone,manifests);saveRun(root,"no-input",config(noInput,true,BUDGET,2),platform(false,2,1),standalone,manifests);
        Path later=text(root.resolve("inputs/later.dax"),FLOW);SimulationConfig late=SimulationConfig.builder(Arrays.asList(input.toString(),later.toString()),2).workflowArrivalSeconds(Arrays.asList(0.0,20.0)).planningAlgorithm(Parameters.PlanningAlgorithm.RANDOM).schedulingAlgorithm(Parameters.SchedulingAlgorithm.STATIC).fileSystem(ReplicaCatalog.FileSystem.LOCAL).randomSeed(1)
                .dataMovementModel(DataMovementModel.coherentFileDataflowV2()).networkEvidence(NetworkEvidenceConfig.fileLifecycleV2(BUDGET)).build();saveRun(root,"staggered-scopes",late,platform(true,2,1),standalone,manifests);
        saveRun(root,"off-shared",config(input,true,0,2),platform(false,2,1),standalone,manifests);saveRun(root,"off-isolated",config(input,false,0,2),platform(true,2,1),standalone,manifests);
        Path retry=text(root.resolve("inputs/retry.dax"),"<adag><job id=\"p\" runtime=\"1\"><uses file=\"external\" link=\"input\" size=\"20000000\"/><uses file=\"out\" link=\"output\" size=\"1000000\"/></job><job id=\"c\" runtime=\"1\"><uses file=\"out\" link=\"input\" size=\"1000000\"/></job><child ref=\"c\"><parent ref=\"p\"/></child></adag>");
        FailureModelConfig failure=FailureModelConfig.builder().clusteringAlgorithm(FailureParameters.FTCluteringAlgorithm.FTCLUSTERING_NOOP).monitorMode(FailureParameters.FTCMonitor.MONITOR_NONE).generatorMode(FailureParameters.FTCFailure.FAILURE_ALL).generatorSpecs(new DistributionSpec[][]{{DistributionSpec.of(DistributionGenerator.DistributionFamily.WEIBULL,2,1)}}).maxTotalRetryJobs(64).build();
        for(long seed:new long[]{1,5,17,22})saveRun(root,"retry-"+seed,config(retry,true,BUDGET,1).toBuilder().failureModel(failure).randomSeed(seed).build(),platform(false,1,20),standalone,manifests);
        for(String mode:Arrays.asList("active","tiny","huge")){
            double bytes=mode.equals("tiny")?Double.MIN_VALUE:mode.equals("huge")?Double.MAX_VALUE:100;double rate=mode.equals("huge")?Double.MAX_VALUE:1;
            Task a=task(1,in("a",bytes)),b=task(2,in("b",bytes));DataflowFilePlan plan=mode.equals("huge")?plan(a,b):plan(a);Map<Integer,Double> caps=new LinkedHashMap<>();caps.put(7,rate);caps.put(42,rate);
            CoherentDataflowRuntime runtime=runtime(plan,DataTransferFabric.endpoints(caps,Arrays.asList("source")));runtime.requestJob(0,Arrays.asList(1),7,0);if(mode.equals("huge"))runtime.requestJob(1,Arrays.asList(2),42,0);
            runtime.advance(mode.equals("tiny")?Double.MIN_VALUE:mode.equals("huge")?1:.1);saveStandalone(root,"generic-"+mode,runtime.captureEvidence(),standalone);
        }
        Task producer=task(1,out("fast",10),out("slow",100)),fanin=task(2,in("fast",10),in("slow",100)),reuse=task(3,in("fast",10));for(Task child:Arrays.asList(fanin,reuse)){producer.addChild(child);child.addParent(producer);}Map<Integer,Double> caps=new LinkedHashMap<>();caps.put(7,10.0);caps.put(42,100.0);caps.put(99,100.0);
        CoherentDataflowRuntime cache=runtime(plan(producer,fanin,reuse),DataTransferFabric.endpoints(caps,Arrays.asList("source")));cache.requestJob(0,Arrays.asList(1),7,0);cache.drainReadyJobIds();cache.cpuStarted(0,Arrays.asList(1),7,0);cache.jobFinished(0,Arrays.asList(1),7,Arrays.asList(true),1);cache.requestJob(1,Arrays.asList(2),99,1);cache.advance(3);cache.requestJob(2,Arrays.asList(3),42,3);cache.advance(4);saveStandalone(root,"generic-cache-source",cache.captureEvidence(),standalone);
        JsonObject index=new JsonObject();index.addProperty("schema","workflowsim-file-lifecycle-fixtures-v2");index.add("standalone",standalone);index.add("manifests",manifests);ExperimentArtifactWriter.writeJson(root.resolve("fixtures.json"),index);System.out.println("FILE_LIFECYCLE_FIXTURES standalone="+standalone.size()+" manifests="+manifests.size()+" root="+root);
    }
    private static void saveRun(Path root,String name,SimulationConfig config,PlatformProfile platform,JsonArray standalone,JsonArray manifests)throws Exception{
        SimulationReport report=new SimulationRunner().run(config,platform);if(!report.isWorkflowCompletedSuccessfully())throw new IllegalStateException("Fixture did not complete: "+name);
        ExperimentArtifactWriter.ExperimentArtifacts out=ExperimentArtifactWriter.write(report,root.resolve(name),"result");ExperimentArtifactValidator.validate(out.getManifest());manifests.add(root.relativize(out.getManifest()).toString());
        if(out.getFileLifecycle()!=null){FileLifecycleCodec.decode(new String(Files.readAllBytes(out.getFileLifecycle()),StandardCharsets.UTF_8));standalone.add(root.relativize(out.getFileLifecycle()).toString());}
    }
    private static void saveStandalone(Path root,String name,FileLifecycleEvidence evidence,JsonArray inputs)throws Exception{String json=FileLifecycleCodec.encode(evidence);FileLifecycleCodec.decode(json);Path out=text(root.resolve(name+".file-lifecycle.json"),json);inputs.add(root.relativize(out).toString());}
    private static SimulationConfig config(Path input,boolean shared,int budget,int vms){return SimulationConfig.builder(input.toString(),vms).planningAlgorithm(Parameters.PlanningAlgorithm.RANDOM).schedulingAlgorithm(Parameters.SchedulingAlgorithm.STATIC).fileSystem(ReplicaCatalog.FileSystem.LOCAL).randomSeed(1).cloudSimMinEventIntervalSeconds(.1).dataMovementModel(shared?DataMovementModel.coherentFileDataflowV2():DataMovementModel.coherentFileDataflowNoContentionV2()).networkEvidence(budget==0?NetworkEvidenceConfig.off():NetworkEvidenceConfig.fileLifecycleV2(budget)).build();}
    private static PlatformProfile platform(boolean fat,int count,long bandwidth){PlatformProfile.Builder p=PlatformProfile.builder("file-lifecycle-corpus");int[] ids={7,42};for(int i=0;i<count;i++){int host=10+i*10;p.addHost(new PlatformProfile.HostSpec(host,2,2000,4096,10000L,1000000L)).addVm(new PlatformProfile.VmSpec(ids[i],1000,1,512,bandwidth,10000L,"Xen",PlatformProfile.CloudletSchedulerMode.SPACE_SHARED)).pinVmToHost(ids[i],host);}if(fat)p.networkTopology(NetworkTopologySpec.fatTree(4,1));return p.build();}
    private static CoherentDataflowRuntime runtime(DataflowFilePlan plan,DataTransferFabric fabric){CoherentDataflowRuntime r=new CoherentDataflowRuntime(true,BUDGET);r.initializePlan(plan,fabric.getLocations());r.bindFabric(fabric);return r;}
    private static Path text(Path path,String value)throws Exception{Files.createDirectories(path.getParent());Files.write(path,value.getBytes(StandardCharsets.UTF_8));return path;}
    private static Task task(int id,FileItem...files){Task t=new Task(id,1000);for(FileItem f:files)t.addFile(f);return t;}
    private static FileItem in(String name,double bytes){return file(name,bytes,Parameters.FileType.INPUT);}
    private static FileItem out(String name,double bytes){return file(name,bytes,Parameters.FileType.OUTPUT);}
    private static FileItem file(String name,double bytes,Parameters.FileType type){FileItem f=new FileItem(name,bytes);f.setType(type);return f;}
    private static DataflowFilePlan plan(Task...tasks){Map<Integer,Integer> scopes=new LinkedHashMap<>();for(Task task:tasks)scopes.put(task.getCloudletId(),0);return DataflowFilePlan.capture(Arrays.asList(tasks),scopes);}
}
