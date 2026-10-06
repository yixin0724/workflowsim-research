package org.workflowsim.experiments.rerun;

import static org.junit.jupiter.api.Assertions.*;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import org.cloudbus.cloudsim.Log;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.workflowsim.data.DataMovementModel;
import org.workflowsim.data.NetworkEvidenceConfig;
import org.workflowsim.data.v2.DataflowStorageSpec;
import org.workflowsim.data.v2.FileLifecycleEvidence;
import org.workflowsim.experiment.ExperimentArtifactWriter;
import org.workflowsim.experiment.SimulationRunner;
import org.workflowsim.network.NetworkTopologySpec;
import org.workflowsim.platform.PlatformProfile;
import org.workflowsim.utils.Parameters;
import org.workflowsim.utils.ReplicaCatalog;
import org.workflowsim.utils.SimulationConfig;

class StorageLifecycleRerunIntegrationTest {
    @TempDir Path directory;
    private int serial;
    private boolean logging;
    @BeforeEach void quiet(){logging=Log.isDisabled();Log.disable();}
    @AfterEach void restore(){Log.setDisabled(logging);}
    @Test void readerAndRebuilderRetainStorageDimensionsAndVersionedPath()throws Exception{
        for(boolean inputs:new boolean[]{false,true})for(boolean shared:new boolean[]{false,true})for(boolean fat:new boolean[]{false,true}){
            Path run=fixture(inputs,shared,fat,2000);RerunEvidence evidence=RerunEvidenceReader.read(run);assertEquals(run.resolve("result.storage-lifecycle.json"),evidence.getStorageLifecyclePath());assertNull(evidence.getFileLifecyclePath());assertNull(evidence.getNetworkLedgerPath());assertEquals(FileLifecycleEvidence.Status.COMPLETE,evidence.getStorageLifecycleCaptureStatus());
            ManifestConfigRebuilder.RebuiltConfiguration rebuilt=ManifestConfigRebuilder.rebuild(evidence,RerunInputResolver.resolve(evidence));assertEquals(inputs?ReplicaCatalog.FileSystem.SHARED:ReplicaCatalog.FileSystem.LOCAL,rebuilt.getConfig().getFileSystem());assertEquals(shared,rebuilt.getConfig().getDataMovementModel().usesSharedDataflowResources());assertEquals(DataflowStorageSpec.of(30,1,.5,2),rebuilt.getPlatform().getSourceStorage());assertEquals(fat,rebuilt.getPlatform().getNetworkTopology()!=null);assertEquals(NetworkEvidenceConfig.Mode.FILE_STORAGE_LIFECYCLE_V3,rebuilt.getConfig().getNetworkEvidenceConfig().getMode());assertEquals(2000,rebuilt.getConfig().getNetworkEvidenceConfig().getMaxTraceRecords());
        }
    }
    @Test void allStorageRuntimeVariantsRerunIdentically()throws Exception{
        for(boolean inputs:new boolean[]{false,true})for(boolean shared:new boolean[]{false,true})for(boolean fat:new boolean[]{false,true}){
            Path run=fixture(inputs,shared,fat,2000),output=directory.resolve("rerun-"+(serial++));RerunReport result=RerunDiffExecutor.execute(run,output);assertEquals(RerunVerdict.IDENTICAL_CORE,result.getVerdict(),result.toJson());assertEquals(read(run.resolve("result.storage-lifecycle.json")),read(output.resolve("rerun/result.storage-lifecycle.json")));
        }
    }
    @Test void offPreservesPhysicalStorageWithoutInventingCapturedTraffic()throws Exception{
        Path run=fixture(true,true,true,0),output=directory.resolve("off-rerun");RerunEvidence evidence=RerunEvidenceReader.read(run);assertNull(evidence.getStorageLifecyclePath());assertTrue(evidence.getManifest().getAsJsonObject("platform").has("sourceStorage"));assertTrue(evidence.getManifest().has("dataflowPlan"));assertEquals(RerunVerdict.IDENTICAL_CORE,RerunDiffExecutor.execute(run,output).getVerdict());assertFalse(Files.exists(output.resolve("rerun/result.storage-lifecycle.json")));
    }
    @Test void validRehashedLifecycleDriftIsCoreNotAnExemptArtifactHash()throws Exception{
        Path original=fixture(true,true,false,2000),changed=directory.resolve("drift");RerunTestSupport.copyEvidenceFiles(original,changed);Path sidecar=changed.resolve("result.storage-lifecycle.json");JsonObject doc=read(sidecar);boolean altered=false;
        for(JsonElement row:doc.getAsJsonArray("events")){JsonObject e=row.getAsJsonObject();if(e.get("type").getAsString().equals("COPY_SETTLED")){JsonObject p=e.getAsJsonObject("payload");double effective=p.get("effectiveTime").getAsDouble();if(Math.nextUp(effective)<e.get("observedTime").getAsDouble()){p.addProperty("effectiveTime",Math.nextUp(effective));altered=true;break;}}}assertTrue(altered);ExperimentArtifactWriter.writeJson(sidecar,doc);rehash(changed,sidecar);
        EvidenceCoreDiffer.DiffResult diff=EvidenceCoreDiffer.compare(RerunEvidenceReader.read(original),RerunEvidenceReader.read(changed));assertFalse(diff.isIdenticalCore());assertTrue(diff.getCoreDivergences().stream().allMatch(d->d.getPointer().startsWith("/storageLifecycle/")));assertEquals(RerunVerdict.DIVERGED,RerunDiffExecutor.execute(changed,directory.resolve("drift-rerun")).getVerdict());
    }
    @Test void rootRuntimeExemptionsDoNotLeakIntoStorageNamespace()throws Exception{
        RerunEvidence evidence=RerunEvidenceReader.read(fixture(true,true,false,2000));JsonObject doc=read(evidence.getStorageLifecyclePath()),runtime=new JsonObject();runtime.addProperty("marker",9007199254740993L);doc.add("runtime",runtime);Path changed=directory.resolve("comparison-only.json");ExperimentArtifactWriter.writeJson(changed,doc);
        // Bypass semantic reader only to test the independent comparator namespace.
        RerunEvidence comparison=new RerunEvidence(evidence.getRunDirectory(),evidence.getManifestPath(),evidence.getMetricsPath(),evidence.getEventsPath(),evidence.getEventCount(),evidence.getManifest(),null,null,null,null,changed,FileLifecycleEvidence.Status.COMPLETE);
        assertTrue(EvidenceCoreDiffer.compare(evidence,comparison).getCoreDivergences().stream().anyMatch(d->d.getPointer().startsWith("/storageLifecycle/runtime")));assertThrows(IllegalArgumentException.class,()->new RerunEvidence(evidence.getRunDirectory(),evidence.getManifestPath(),evidence.getMetricsPath(),evidence.getEventsPath(),evidence.getEventCount(),evidence.getManifest()));
    }
    private Path fixture(boolean inputs,boolean shared,boolean fat,int budget)throws Exception{
        Path root=directory.resolve("study-"+(serial++)),input=root.resolve("inputs/flow.dax"),run=root.resolve("runs/original");Files.createDirectories(input.getParent());Files.write(input,"<adag><job id=\"p\" runtime=\"1\"><uses file=\"external\" link=\"input\" size=\"0.5\"/><uses file=\"x\" link=\"output\" size=\"1000000\"/><uses file=\"zero\" link=\"output\" size=\"0\"/></job><job id=\"c\" runtime=\"1\"><uses file=\"x\" link=\"input\" size=\"1000000\"/><uses file=\"zero\" link=\"input\" size=\"0\"/></job><child ref=\"c\"><parent ref=\"p\"/></child></adag>".getBytes(StandardCharsets.UTF_8));
        SimulationConfig c=SimulationConfig.builder(input.toString(),2).planningAlgorithm(Parameters.PlanningAlgorithm.RANDOM).schedulingAlgorithm(Parameters.SchedulingAlgorithm.STATIC).fileSystem(inputs?ReplicaCatalog.FileSystem.SHARED:ReplicaCatalog.FileSystem.LOCAL).randomSeed(1).cloudSimMinEventIntervalSeconds(.1).dataMovementModel(shared?DataMovementModel.coherentStorageDataflowV3():DataMovementModel.coherentStorageDataflowNoContentionV3()).networkEvidence(budget==0?NetworkEvidenceConfig.off():NetworkEvidenceConfig.storageLifecycleV3(budget)).build();PlatformProfile.Builder p=PlatformProfile.builder("storage-rerun");int[] ids={7,42};for(int i=0;i<2;i++){int host=10+i*10;p.addHost(new PlatformProfile.HostSpec(host,2,2000,4096,10000,1000000)).addVm(new PlatformProfile.VmSpec(ids[i],1000,1,512,10,10000,"Xen",PlatformProfile.CloudletSchedulerMode.SPACE_SHARED)).pinVmToHost(ids[i],host);}p.addHost(new PlatformProfile.HostSpec(30,2,2000,4096,10000,1000000));p.sourceStorage(DataflowStorageSpec.of(30,1,.5,2));if(fat)p.networkTopology(NetworkTopologySpec.fatTree(4,5));ExperimentArtifactWriter.write(new SimulationRunner().run(c,p.build()),run,"result");return run;
    }
    private static JsonObject read(Path p)throws Exception{return JsonParser.parseString(new String(Files.readAllBytes(p),StandardCharsets.UTF_8)).getAsJsonObject();}
    private static void rehash(Path run,Path sidecar)throws Exception{byte[] bytes=Files.readAllBytes(sidecar);StringBuilder sha=new StringBuilder();for(byte b:MessageDigest.getInstance("SHA-256").digest(bytes))sha.append(String.format("%02x",b&255));Path path=run.resolve("result.manifest.json");JsonObject manifest=read(path);for(JsonElement value:manifest.getAsJsonArray("artifacts")){JsonObject a=value.getAsJsonObject();if(a.get("role").getAsString().equals("storage-lifecycle")){a.addProperty("sha256",sha.toString());a.addProperty("sizeBytes",bytes.length);}}ExperimentArtifactWriter.writeJson(path,manifest);}
}
