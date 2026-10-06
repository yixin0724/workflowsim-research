package org.workflowsim.experiment;

import static org.junit.jupiter.api.Assertions.*;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.cloudbus.cloudsim.Log;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.workflowsim.data.DataMovementModel;
import org.workflowsim.data.NetworkEvidenceConfig;
import org.workflowsim.data.v2.DataflowStorageSpec;
import org.workflowsim.data.v2.StorageLifecycleCodec;
import org.workflowsim.network.NetworkTopologySpec;
import org.workflowsim.platform.PlatformProfile;
import org.workflowsim.utils.Parameters;
import org.workflowsim.utils.ReplicaCatalog;
import org.workflowsim.utils.SimulationConfig;

/** Repaired hashes cannot make a valid foreign or incomplete storage lifecycle belong to this run. */
class StorageLifecycleArtifactIntegrationTest {
    @TempDir Path directory;
    private boolean logging;
    @BeforeEach void quiet(){logging=Log.isDisabled();Log.disable();}
    @AfterEach void restore(){Log.setDisabled(logging);}
    @Test void allStoragePolicySharingAndTopologyBundlesValidateTheirOwnRole()throws Exception{
        Path input=input();for(boolean storeInputs:new boolean[]{false,true})for(boolean shared:new boolean[]{false,true})for(boolean fat:new boolean[]{false,true}){
            SimulationReport report=run(input,storeInputs,shared,fat,2000,.5);ExperimentArtifactWriter.ExperimentArtifacts out=ExperimentArtifactWriter.write(report,directory.resolve("case-"+storeInputs+shared+fat),"result");ExperimentArtifactValidator.ValidationResult checked=ExperimentArtifactValidator.validate(out.getManifest());
            assertEquals(out.getStorageLifecycle(),checked.getStorageLifecycle());assertNull(checked.getFileLifecycle());assertNull(checked.getNetworkLedger());assertTrue(checked.getDecodedStorageLifecycle().isQuiescent());assertEquals(0,checked.getDecodedStorageLifecycle().getPendingOutputFileCount());assertEquals(0,checked.getDecodedStorageLifecycle().getWaitingStoreInputCount());
            JsonObject root=read(out.getManifest());assertEquals(report.getDataflowPlan(),root.get("dataflowPlan"));assertTrue(root.getAsJsonObject("platform").has("sourceStorage"));assertEquals(storeInputs,checked.getDecodedStorageLifecycle().getEvidence().isStoreBackedInputs());assertEquals(shared,checked.getDecodedStorageLifecycle().getEvidence().getCapture().isShared());
        }
    }
    @Test void offStillChecksSourceStorageAndCorePlanWithoutInventingEvidence()throws Exception{
        ExperimentArtifactWriter.ExperimentArtifacts out=ExperimentArtifactWriter.write(run(input(),true,true,false,0,.5),directory.resolve("off"),"result");ExperimentArtifactValidator.ValidationResult checked=ExperimentArtifactValidator.validate(out.getManifest());assertNull(checked.getStorageLifecycle());assertNull(checked.getDecodedStorageLifecycle());
        JsonObject root=read(out.getManifest());root.getAsJsonObject("platform").getAsJsonObject("sourceStorage").addProperty("attachmentHostId",999);save(out.getManifest(),root);assertThrows(java.io.IOException.class,()->ExperimentArtifactValidator.validate(out.getManifest()));
    }
    @Test void incompleteCaptureRejectsBeforeCreatingOrReplacingFiles()throws Exception{
        SimulationReport report=run(input(),true,true,false,1,.5);Path fresh=directory.resolve("fresh");assertThrows(java.io.IOException.class,()->ExperimentArtifactWriter.write(report,fresh,"result"));assertFalse(Files.exists(fresh));
        Path keep=directory.resolve("keep");Files.createDirectories(keep);byte[] sentinel="keep-original".getBytes(StandardCharsets.UTF_8);for(String suffix:Arrays.asList("manifest.json","metrics.json","events.jsonl","storage-lifecycle.json"))Files.write(keep.resolve("result."+suffix),sentinel);assertThrows(java.io.IOException.class,()->ExperimentArtifactWriter.write(report,keep,"result"));for(String suffix:Arrays.asList("manifest.json","metrics.json","events.jsonl","storage-lifecycle.json"))assertArrayEquals(sentinel,Files.readAllBytes(keep.resolve("result."+suffix)));
    }
    @Test void standaloneOnCannotDropItsStorageSidecar()throws Exception{
        SimulationReport report=run(input(),true,true,false,2000,.5);Path target=directory.resolve("missing/manifest.json");assertThrows(UnsupportedOperationException.class,()->ExperimentManifestWriter.writeJson(report,target));assertFalse(Files.exists(target.getParent()));
    }
    @Test void versionedKnownRolesCannotBeMixedOrHiddenAsOff()throws Exception{
        ExperimentArtifactWriter.ExperimentArtifacts out=bundle("roles");JsonObject original=read(out.getManifest());for(String wrongRole:Arrays.asList("file-lifecycle","network-ledger")){JsonObject root=original.deepCopy();role(root,"storage-lifecycle").addProperty("role",wrongRole);save(out.getManifest(),root);assertThrows(java.io.IOException.class,()->ExperimentArtifactValidator.validate(out.getManifest()));}
        JsonObject root=original.deepCopy();root.getAsJsonObject("configuration").remove("networkEvidence");save(out.getManifest(),root);assertThrows(java.io.IOException.class,()->ExperimentArtifactValidator.validate(out.getManifest()));
    }
    @Test void selfConsistentOtherStoreCannotBeSubstitutedWithRepairedHashes()throws Exception{
        Path input=input();SimulationReport a=run(input,true,true,true,2000,.5),b=run(input,true,true,true,2000,.25);JsonObject foreign=StorageLifecycleCodec.document(b.getStorageLifecycleEvidence());assertDoesNotThrow(()->StorageLifecycleCodec.decodeDocument(foreign));
        ExperimentArtifactWriter.ExperimentArtifacts out=ExperimentArtifactWriter.write(a,directory.resolve("foreign"),"result");save(out.getStorageLifecycle(),foreign);rehash(out,"storage-lifecycle",out.getStorageLifecycle());assertThrows(java.io.IOException.class,()->ExperimentArtifactValidator.validate(out.getManifest()));
    }
    @Test void zeroAndUnusedOutputObligationsCannotDisappearFromCompleteCapture()throws Exception{
        for(String name:Arrays.asList("zero","unused")){ExperimentArtifactWriter.ExperimentArtifacts out=bundle("omit-"+name);JsonObject doc=read(out.getStorageLifecycle());JsonArray changed=new JsonArray();for(JsonElement row:doc.getAsJsonArray("events")){JsonObject e=row.getAsJsonObject();if(e.get("type").getAsString().equals("OUTPUT_RESOLVED")&&e.getAsJsonObject("payload").getAsJsonObject("fileId").get("name").getAsString().equals(name))continue;e.addProperty("sequence",changed.size()+1);changed.add(e);}doc.add("events",changed);doc.getAsJsonObject("capture").addProperty("retainedRecords",changed.size());save(out.getStorageLifecycle(),doc);rehash(out,"storage-lifecycle",out.getStorageLifecycle());assertThrows(java.io.IOException.class,()->ExperimentArtifactValidator.validate(out.getManifest()));}
    }
    @Test void rehashedOwnerPurposeAndTypedDestinationForgeryStillFails()throws Exception{
        for(String field:Arrays.asList("ownerJobId","purpose","destination")){ExperimentArtifactWriter.ExperimentArtifacts out=bundle(field);JsonObject doc=read(out.getStorageLifecycle());for(JsonElement row:doc.getAsJsonArray("events")){JsonObject e=row.getAsJsonObject();if(e.get("type").getAsString().equals("COPY_ADMITTED")&&e.getAsJsonObject("payload").get("purpose").getAsString().equals("OUTPUT")){JsonObject p=e.getAsJsonObject("payload");if(field.equals("ownerJobId"))p.addProperty(field,999);else if(field.equals("purpose"))p.addProperty(field,"INPUT");else{JsonObject location=new JsonObject();location.addProperty("kind","VM");location.addProperty("vmId",42);location.add("sourceId",com.google.gson.JsonNull.INSTANCE);p.add(field,location);}break;}}save(out.getStorageLifecycle(),doc);rehash(out,"storage-lifecycle",out.getStorageLifecycle());assertThrows(java.io.IOException.class,()->ExperimentArtifactValidator.validate(out.getManifest()));}
    }
    @Test void finalReadyAnchorObservedWaitAndReferenceQuantitiesAreCrossChecked()throws Exception{
        for(String field:Arrays.asList("observedInputPreparationSeconds","requiredFileBytes","simulationTime")){ExperimentArtifactWriter.ExperimentArtifacts out=bundle("main-"+field);JsonObject doc=read(out.getStorageLifecycle());int child=-1;double requested=0;for(JsonElement row:doc.getAsJsonArray("events")){JsonObject e=row.getAsJsonObject(),p=e.getAsJsonObject("payload");if(e.get("type").getAsString().equals("JOB_INPUT_REQUESTED")&&p.getAsJsonArray("taskIds").get(0).getAsInt()==2){child=p.get("jobId").getAsInt();requested=e.get("observedTime").getAsDouble();}}
            List<String> changed=new ArrayList<>();boolean altered=false;for(String line:Files.readAllLines(out.getEvents(),StandardCharsets.UTF_8)){JsonObject e=JsonParser.parseString(line).getAsJsonObject();if(e.get("type").getAsString().equals("DATA_STAGE_IN_MODELED")&&e.get("jobId").getAsInt()==child){if(field.equals("simulationTime"))e.addProperty(field,requested);else e.getAsJsonObject("attributes").addProperty(field,123);altered=true;}changed.add(e.toString());}assertTrue(altered);Files.write(out.getEvents(),changed,StandardCharsets.UTF_8);rehash(out,"events",out.getEvents());assertThrows(java.io.IOException.class,()->ExperimentArtifactValidator.validate(out.getManifest()));}
    }
    @Test void storagePolicyBudgetAndSchemaCannotBeChangedOnlyInManifest()throws Exception{
        ExperimentArtifactWriter.ExperimentArtifacts out=bundle("config");JsonObject original=read(out.getManifest());JsonObject changed=original.deepCopy();changed.getAsJsonObject("configuration").addProperty("fileSystem","LOCAL");save(out.getManifest(),changed);assertThrows(java.io.IOException.class,()->ExperimentArtifactValidator.validate(out.getManifest()));
        changed=original.deepCopy();changed.getAsJsonObject("configuration").getAsJsonObject("networkEvidence").addProperty("maxTraceRecords",3000);save(out.getManifest(),changed);assertThrows(java.io.IOException.class,()->ExperimentArtifactValidator.validate(out.getManifest()));
        changed=original.deepCopy();changed.addProperty("schema","workflowsim-experiment-manifest-v3");save(out.getManifest(),changed);assertThrows(java.io.IOException.class,()->ExperimentArtifactValidator.validate(out.getManifest()));
    }
    @Test void validatedStorageAndManifestSnapshotsStayPaired()throws Exception{
        ExperimentArtifactWriter.ExperimentArtifacts out=bundle("snapshot");ExperimentArtifactValidator.ValidationResult checked=ExperimentArtifactValidator.validate(out.getManifest());JsonObject expected=checked.getManifestSnapshot(),document=checked.getDecodedStorageLifecycle().getDocument();Files.write(out.getManifest(),"{}".getBytes(StandardCharsets.UTF_8));Files.write(out.getStorageLifecycle(),"invalid".getBytes(StandardCharsets.UTF_8));JsonObject caller=checked.getDecodedStorageLifecycle().getDocument();caller.remove("filePlan");assertEquals(document,checked.getDecodedStorageLifecycle().getDocument());assertEquals(expected,checked.getManifestSnapshot());
    }
    private ExperimentArtifactWriter.ExperimentArtifacts bundle(String name)throws Exception{return ExperimentArtifactWriter.write(run(input(),true,true,false,2000,.5),directory.resolve(name),"result");}
    private Path input()throws Exception{Path p=directory.resolve("workflow.dax");if(!Files.exists(p))Files.write(p,("<adag><job id=\"p\" runtime=\"1\"><uses file=\"external\" link=\"input\" size=\"0.5\"/><uses file=\"x\" link=\"output\" size=\"1000000\"/><uses file=\"unused\" link=\"output\" size=\"500000\"/><uses file=\"zero\" link=\"output\" size=\"0\"/></job><job id=\"c\" runtime=\"1\"><uses file=\"x\" link=\"input\" size=\"1000000\"/><uses file=\"x\" link=\"input\" size=\"1000000\"/><uses file=\"zero\" link=\"input\" size=\"0\"/></job><child ref=\"c\"><parent ref=\"p\"/></child></adag>").getBytes(StandardCharsets.UTF_8));return p;}
    private static SimulationReport run(Path input,boolean storeInputs,boolean shared,boolean fat,int budget,double write)throws Exception{SimulationConfig c=SimulationConfig.builder(input.toString(),2).planningAlgorithm(Parameters.PlanningAlgorithm.RANDOM).schedulingAlgorithm(Parameters.SchedulingAlgorithm.STATIC).fileSystem(storeInputs?ReplicaCatalog.FileSystem.SHARED:ReplicaCatalog.FileSystem.LOCAL).randomSeed(1).cloudSimMinEventIntervalSeconds(.1).dataMovementModel(shared?DataMovementModel.coherentStorageDataflowV3():DataMovementModel.coherentStorageDataflowNoContentionV3()).networkEvidence(budget==0?NetworkEvidenceConfig.off():NetworkEvidenceConfig.storageLifecycleV3(budget)).build();PlatformProfile.Builder p=PlatformProfile.builder("storage-artifact");int[] ids={7,42};for(int i=0;i<2;i++){int host=10+i*10;p.addHost(new PlatformProfile.HostSpec(host,2,2000,4096,10000,1000000)).addVm(new PlatformProfile.VmSpec(ids[i],1000,1,512,10,10000,"Xen",PlatformProfile.CloudletSchedulerMode.SPACE_SHARED)).pinVmToHost(ids[i],host);}p.addHost(new PlatformProfile.HostSpec(30,2,2000,4096,10000,1000000));p.sourceStorage(DataflowStorageSpec.of(30,1,write,2));if(fat)p.networkTopology(NetworkTopologySpec.fatTree(4,5));return new SimulationRunner().run(c,p.build());}
    private static JsonObject read(Path p)throws Exception{return JsonParser.parseString(new String(Files.readAllBytes(p),StandardCharsets.UTF_8)).getAsJsonObject();}
    private static void save(Path p,JsonObject value)throws Exception{Files.write(p,value.toString().getBytes(StandardCharsets.UTF_8));}
    private static JsonObject role(JsonObject root,String role){for(JsonElement e:root.getAsJsonArray("artifacts"))if(e.getAsJsonObject().get("role").getAsString().equals(role))return e.getAsJsonObject();throw new AssertionError("missing role");}
    private static void rehash(ExperimentArtifactWriter.ExperimentArtifacts out,String role,Path path)throws Exception{byte[] bytes=Files.readAllBytes(path);StringBuilder sha=new StringBuilder();for(byte b:MessageDigest.getInstance("SHA-256").digest(bytes))sha.append(String.format("%02x",b&255));JsonObject root=read(out.getManifest()),entry=role(root,role);entry.addProperty("sha256",sha.toString());entry.addProperty("sizeBytes",bytes.length);save(out.getManifest(),root);}
}
