package org.workflowsim.experiments.rerun;

import static org.junit.jupiter.api.Assertions.*;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import org.cloudbus.cloudsim.Log;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.workflowsim.data.DataMovementModel;
import org.workflowsim.data.NetworkEvidenceConfig;
import org.workflowsim.data.v2.DataflowAssignmentConfig;
import org.workflowsim.data.v2.DataflowStorageSpec;
import org.workflowsim.experiment.ExperimentArtifactWriter;
import org.workflowsim.experiment.SimulationRunner;
import org.workflowsim.network.NetworkTopologySpec;
import org.workflowsim.platform.PlatformProfile;
import org.workflowsim.utils.Parameters;
import org.workflowsim.utils.ReplicaCatalog;
import org.workflowsim.utils.SimulationConfig;

class DataflowAssignmentRerunIntegrationTest {
    @TempDir Path directory;private int serial;private boolean logging;
    @BeforeEach void quiet(){logging=Log.isDisabled();Log.disable();}
    @AfterEach void restore(){Log.setDisabled(logging);}
    @Test void rebuilderRetainsOrdinaryOnlinePolicyAndCompleteRecording()throws Exception{
        for(boolean storage:new boolean[]{false,true})for(boolean gate:new boolean[]{false,true}){if(!storage&&gate)continue;for(boolean shared:new boolean[]{false,true})for(boolean fat:new boolean[]{false,true}){RerunEvidence evidence=RerunEvidenceReader.read(fixture(storage,gate,shared,fat));ManifestConfigRebuilder.RebuiltConfiguration rebuilt=ManifestConfigRebuilder.rebuild(evidence,RerunInputResolver.resolve(evidence));assertEquals(DataflowAssignmentConfig.onlineNominal(),rebuilt.getConfig().getDataflowAssignmentConfig());assertEquals(Parameters.PlanningAlgorithm.INVALID,rebuilt.getConfig().getPlanningAlgorithm());assertEquals(Parameters.SchedulingAlgorithm.STATIC,rebuilt.getConfig().getSchedulingAlgorithm());assertTrue(rebuilt.getConfig().getNetworkEvidenceConfig().isEnabled());assertTrue(evidence.getManifest().getAsJsonObject("configuration").has("dataflowAssignment"));assertTrue(evidence.getManifest().has("dataflowComputeRequests"));}}
    }
    @Test void actualV2V3OnlineAccessSharingAndTopologyRunsRerunExactly()throws Exception{
        for(boolean storage:new boolean[]{false,true})for(boolean gate:new boolean[]{false,true}){if(!storage&&gate)continue;for(boolean shared:new boolean[]{false,true})for(boolean fat:new boolean[]{false,true}){Path run=fixture(storage,gate,shared,fat);RerunReport result=RerunDiffExecutor.execute(run,directory.resolve("rerun-"+(serial++)));assertEquals(RerunVerdict.IDENTICAL_CORE,result.getVerdict(),result.toJson());}}
    }
    @Test void validRehashedWithinBoundProgressDriftIsStillScientificCore()throws Exception{
        Path original=fixture(true,true,true,false),changed=directory.resolve("drift");RerunTestSupport.copyEvidenceFiles(original,changed);Path events=changed.resolve("result.events.jsonl");List<String> lines=new ArrayList<>();boolean edited=false;for(String line:Files.readAllLines(events,StandardCharsets.UTF_8)){JsonObject event=JsonParser.parseString(line).getAsJsonObject();if(!edited&&event.get("type").getAsString().equals("DATAFLOW_VM_ASSIGNED")){JsonObject attrs=event.getAsJsonObject("attributes");int selected=attrs.get("selectedVmId").getAsInt();for(JsonElement item:attrs.getAsJsonArray("candidates")){JsonObject candidate=item.getAsJsonObject();if(candidate.get("vmId").getAsInt()==selected&&candidate.get("conditionalStoreWait").getAsBoolean()){double delay=candidate.get("inputSeconds").getAsDouble()*.75;candidate.addProperty("inputSeconds",delay);candidate.addProperty("scoreFinishSeconds",Math.max(event.get("simulationTime").getAsDouble()+delay,candidate.get("cpuAvailableAt").getAsDouble())+candidate.get("computeSeconds").getAsDouble());edited=true;break;}}}lines.add(event.toString());}assertTrue(edited);Files.write(events,lines,StandardCharsets.UTF_8);rehash(changed,events,"events");
        RerunEvidence before=RerunEvidenceReader.read(original),after=RerunEvidenceReader.read(changed);EvidenceCoreDiffer.DiffResult diff=EvidenceCoreDiffer.compare(before,after);assertFalse(diff.isIdenticalCore());assertTrue(diff.getCoreDivergences().stream().anyMatch(d->d.getPointer().startsWith("/events/")&&d.getPointer().contains("/attributes/candidates/")));assertEquals(RerunVerdict.DIVERGED,RerunDiffExecutor.execute(changed,directory.resolve("drift-rerun")).getVerdict());
    }
    @Test void assignmentConfigAndComputeRequestsAreNotAlgorithmIdentityExemptions()throws Exception{
        RerunEvidence before=RerunEvidenceReader.read(fixture(true,true,true,false));JsonObject root=before.getManifest().deepCopy();root.getAsJsonObject("configuration").getAsJsonObject("dataflowAssignment").addProperty("policy","different");root.getAsJsonArray("dataflowComputeRequests").get(0).getAsJsonObject().addProperty("lengthMi",999999);
        // Comparator-only bypass: semantically invalid data must still be classified as core, not identity.
        RerunEvidence after=new RerunEvidence(before.getRunDirectory(),before.getManifestPath(),before.getMetricsPath(),before.getEventsPath(),before.getEventCount(),root,before.getNetworkLedgerPath(),before.getNetworkCaptureStatus(),before.getFileLifecyclePath(),before.getFileLifecycleCaptureStatus(),before.getStorageLifecyclePath(),before.getStorageLifecycleCaptureStatus());EvidenceCoreDiffer.DiffResult diff=EvidenceCoreDiffer.compare(before,after);assertTrue(diff.getCoreDivergences().stream().anyMatch(d->d.getPointer().equals("/configuration/dataflowAssignment/policy")));assertTrue(diff.getCoreDivergences().stream().anyMatch(d->d.getPointer().startsWith("/dataflowComputeRequests/")));
    }
    @Test void rebuildUsesTheValidatedManifestSnapshotRatherThanRereadingMutation()throws Exception{
        Path run=fixture(false,false,true,false);RerunEvidence before=RerunEvidenceReader.read(run);JsonObject changed=read(run.resolve("result.manifest.json"));changed.getAsJsonObject("configuration").getAsJsonObject("dataflowAssignment").addProperty("policy","after-validation-corruption");ExperimentArtifactWriter.writeJson(run.resolve("result.manifest.json"),changed);ManifestConfigRebuilder.RebuiltConfiguration rebuilt=ManifestConfigRebuilder.rebuild(before,RerunInputResolver.resolve(before));assertEquals(DataflowAssignmentConfig.onlineNominal(),rebuilt.getConfig().getDataflowAssignmentConfig());assertEquals(RerunVerdict.EVIDENCE_INVALID,assertThrows(RerunFailureException.class,()->RerunEvidenceReader.read(run)).getVerdict());
    }
    private Path fixture(boolean storage,boolean gate,boolean shared,boolean fat)throws Exception{
        Path root=directory.resolve("study-"+(serial++)),input=root.resolve("inputs/flow.dax"),run=root.resolve("runs/original");Files.createDirectories(input.getParent());Files.write(input,"<adag><job id=\"p\" runtime=\"1\"><uses file=\"external\" link=\"input\" size=\"0.5\"/><uses file=\"x\" link=\"output\" size=\"1000000\"/><uses file=\"zero\" link=\"output\" size=\"0\"/></job><job id=\"c\" runtime=\"1\"><uses file=\"x\" link=\"input\" size=\"1000000\"/><uses file=\"zero\" link=\"input\" size=\"0\"/></job><child ref=\"c\"><parent ref=\"p\"/></child></adag>".getBytes(StandardCharsets.UTF_8));
        DataMovementModel model=storage?(shared?DataMovementModel.coherentStorageDataflowV3():DataMovementModel.coherentStorageDataflowNoContentionV3()):(shared?DataMovementModel.coherentFileDataflowV2():DataMovementModel.coherentFileDataflowNoContentionV2());SimulationConfig c=SimulationConfig.builder(input.toString(),2).planningAlgorithm(Parameters.PlanningAlgorithm.INVALID).schedulingAlgorithm(Parameters.SchedulingAlgorithm.STATIC).fileSystem(gate?ReplicaCatalog.FileSystem.SHARED:ReplicaCatalog.FileSystem.LOCAL).dataflowAssignment(DataflowAssignmentConfig.onlineNominal()).randomSeed(1).dataMovementModel(model).networkEvidence(storage?NetworkEvidenceConfig.storageLifecycleV3(2000):NetworkEvidenceConfig.fileLifecycleV2(2000)).build();PlatformProfile.Builder p=PlatformProfile.builder("online-rerun");int[] ids={7,42};for(int i=0;i<2;i++){int host=10+i*10;p.addHost(new PlatformProfile.HostSpec(host,2,4000,4096,10000,1000000)).addVm(new PlatformProfile.VmSpec(ids[i],i==0?1000:2000,1,512,2,10000,"Xen",PlatformProfile.CloudletSchedulerMode.SPACE_SHARED)).pinVmToHost(ids[i],host);}p.addHost(new PlatformProfile.HostSpec(30,2,4000,4096,10000,1000000));if(storage)p.sourceStorage(DataflowStorageSpec.of(30,1,.5,1));if(fat)p.networkTopology(NetworkTopologySpec.fatTree(4,5));ExperimentArtifactWriter.write(new SimulationRunner().run(c,p.build()),run,"result");return run;
    }
    private static JsonObject read(Path path)throws Exception{return JsonParser.parseString(new String(Files.readAllBytes(path),StandardCharsets.UTF_8)).getAsJsonObject();}
    private static void rehash(Path run,Path sidecar,String role)throws Exception{byte[] bytes=Files.readAllBytes(sidecar);StringBuilder hash=new StringBuilder();for(byte b:MessageDigest.getInstance("SHA-256").digest(bytes))hash.append(String.format("%02x",b&255));Path path=run.resolve("result.manifest.json");JsonObject root=read(path);for(JsonElement item:root.getAsJsonArray("artifacts")){JsonObject entry=item.getAsJsonObject();if(entry.get("role").getAsString().equals(role)){entry.addProperty("sha256",hash.toString());entry.addProperty("sizeBytes",bytes.length);}}ExperimentArtifactWriter.writeJson(path,root);}
}
