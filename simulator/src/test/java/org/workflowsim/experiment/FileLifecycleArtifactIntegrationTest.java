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
import org.workflowsim.data.v2.FileLifecycleCodec;
import org.workflowsim.network.NetworkTopologySpec;
import org.workflowsim.platform.PlatformProfile;
import org.workflowsim.utils.Parameters;
import org.workflowsim.utils.ReplicaCatalog;
import org.workflowsim.utils.SimulationConfig;

/** Full V2 bundle must prove that its independently valid lifecycle belongs to this Kernel run. */
class FileLifecycleArtifactIntegrationTest {
    @TempDir Path directory;
    private boolean logging;
    @BeforeEach void quiet(){logging=Log.isDisabled();Log.disable();}
    @AfterEach void restore(){Log.setDisabled(logging);}

    @Test void allNewModelAndTopologyVariantsWriteAndValidateTheirOwnRole()throws Exception{
        Path workflow=input();for(boolean shared:new boolean[]{true,false})for(boolean fat:new boolean[]{false,true}){
            SimulationReport report=run(workflow,shared,fat,1000,1);ExperimentArtifactWriter.ExperimentArtifacts out=ExperimentArtifactWriter.write(report,directory.resolve("bundle-"+shared+"-"+fat),"run");
            ExperimentArtifactValidator.ValidationResult checked=ExperimentArtifactValidator.validate(out.getManifest());assertEquals(out.getFileLifecycle(),checked.getFileLifecycle());assertNull(checked.getNetworkLedger());assertNull(checked.getDecodedNetworkLedger());
            assertTrue(checked.getDecodedFileLifecycle().isQuiescent());assertEquals(1,checked.getDecodedFileLifecycle().getCopyCount());assertEquals("workflowsim-file-lifecycle-v2",read(out.getFileLifecycle()).get("schema").getAsString());
            JsonObject manifest=read(out.getManifest());assertEquals(report.getDataflowPlan(),manifest.get("dataflowPlan"));assertEquals("FILE_LIFECYCLE_V2",manifest.getAsJsonObject("configuration").getAsJsonObject("networkEvidence").get("mode").getAsString());
        }
    }
    @Test void offV2KeepsThreeFilesButRetainsItsVersionedCorePlan()throws Exception{
        SimulationReport report=run(input(),true,false,0,1);ExperimentArtifactWriter.ExperimentArtifacts out=ExperimentArtifactWriter.write(report,directory.resolve("off"),"run");
        assertNull(out.getFileLifecycle());assertNull(out.getNetworkLedger());ExperimentArtifactValidator.ValidationResult checked=ExperimentArtifactValidator.validate(out.getManifest());assertNull(checked.getDecodedFileLifecycle());assertTrue(checked.getManifestSnapshot().has("dataflowPlan"));
        JsonObject corrupted=read(out.getManifest());corrupted.getAsJsonObject("dataflowPlan").getAsJsonArray("tasks").get(0).getAsJsonObject().addProperty("workflowInputIndex",1);save(out.getManifest(),corrupted);
        assertThrows(java.io.IOException.class,()->ExperimentArtifactValidator.validate(out.getManifest()));
    }
    @Test void truncatedCaptureRejectsBeforeCreatingOrReplacingAnyArtifact()throws Exception{
        SimulationReport report=run(input(),true,false,1,1);Path fresh=directory.resolve("must-not-exist");assertThrows(java.io.IOException.class,()->ExperimentArtifactWriter.write(report,fresh,"run"));assertFalse(Files.exists(fresh));
        Path existing=directory.resolve("keep");Files.createDirectories(existing);byte[] sentinel="preserve".getBytes(StandardCharsets.UTF_8);for(String suffix:Arrays.asList("metrics.json","events.jsonl","manifest.json","file-lifecycle.json"))Files.write(existing.resolve("run."+suffix),sentinel);
        assertThrows(java.io.IOException.class,()->ExperimentArtifactWriter.write(report,existing,"run"));for(String suffix:Arrays.asList("metrics.json","events.jsonl","manifest.json","file-lifecycle.json"))assertArrayEquals(sentinel,Files.readAllBytes(existing.resolve("run."+suffix)));
    }
    @Test void standaloneEnabledManifestCannotLoseLifecycleEvidence()throws Exception{
        SimulationReport report=run(input(),true,false,1000,1);Path target=directory.resolve("absent/sub/manifest.json");assertThrows(UnsupportedOperationException.class,()->ExperimentManifestWriter.writeJson(report,target));assertFalse(Files.exists(target.getParent()));
    }
    @Test void modeAndKnownArtifactRolesCannotBeSwappedOrHidden()throws Exception{
        SimulationReport report=run(input(),true,false,1000,1);ExperimentArtifactWriter.ExperimentArtifacts out=ExperimentArtifactWriter.write(report,directory.resolve("roles"),"run");JsonObject original=read(out.getManifest());
        JsonObject wrong=original.deepCopy();role(wrong,"file-lifecycle").addProperty("role","network-ledger");save(out.getManifest(),wrong);assertThrows(java.io.IOException.class,()->ExperimentArtifactValidator.validate(out.getManifest()));
        wrong=original.deepCopy();wrong.getAsJsonObject("configuration").remove("networkEvidence");save(out.getManifest(),wrong);assertThrows(java.io.IOException.class,()->ExperimentArtifactValidator.validate(out.getManifest()));
    }
    @Test void rehashedWrongRouteStillFailsSemanticValidation()throws Exception{
        ExperimentArtifactWriter.ExperimentArtifacts out=ExperimentArtifactWriter.write(run(input(),true,false,1000,1),directory.resolve("route"),"run");JsonObject ledger=read(out.getFileLifecycle());
        for(JsonElement event:ledger.getAsJsonArray("events"))if(event.getAsJsonObject().get("type").getAsString().equals("COPY_ADMITTED")){event.getAsJsonObject().getAsJsonObject("payload").getAsJsonArray("resources").set(0,new com.google.gson.JsonPrimitive("VM:42"));break;}
        save(out.getFileLifecycle(),ledger);rehash(out,"file-lifecycle",out.getFileLifecycle());assertThrows(java.io.IOException.class,()->ExperimentArtifactValidator.validate(out.getManifest()));
    }
    @Test void selfConsistentOtherRunCannotBeSubstitutedByRepairingHashes()throws Exception{
        Path workflow=input();SimulationReport a=run(workflow,true,false,1000,1),b=run(workflow,true,false,1000,2);assertDoesNotThrow(()->FileLifecycleCodec.encode(b.getFileLifecycleEvidence()));
        ExperimentArtifactWriter.ExperimentArtifacts out=ExperimentArtifactWriter.write(a,directory.resolve("foreign"),"run");save(out.getFileLifecycle(),FileLifecycleCodec.document(b.getFileLifecycleEvidence()));rehash(out,"file-lifecycle",out.getFileLifecycle());
        assertThrows(java.io.IOException.class,()->ExperimentArtifactValidator.validate(out.getManifest()));
    }
    @Test void rehashedMainQuantitiesAndCpuEnvelopeMustAgreeWithLifecycle()throws Exception{
        SimulationReport report=run(input(),true,false,1000,1);
        for(String field:Arrays.asList("requiredFileBytes","requestedDataStageInSecondsForJob")){
            ExperimentArtifactWriter.ExperimentArtifacts out=ExperimentArtifactWriter.write(report,directory.resolve(field),"run");List<String> lines=Files.readAllLines(out.getEvents(),StandardCharsets.UTF_8);List<String> changed=new ArrayList<>();boolean altered=false;
            for(String line:lines){JsonObject event=JsonParser.parseString(line).getAsJsonObject();JsonObject attributes=event.getAsJsonObject("attributes");if(!altered&&attributes.has(field)){attributes.addProperty(field,123);altered=true;}changed.add(event.toString());}
            assertTrue(altered);Files.write(out.getEvents(),changed,StandardCharsets.UTF_8);rehash(out,"events",out.getEvents());assertThrows(java.io.IOException.class,()->ExperimentArtifactValidator.validate(out.getManifest()));
        }
    }
    @Test void newManifestAndMainEventsRejectDuplicateKeysAndHistoricalSchemaDowngrade()throws Exception{
        SimulationReport report=run(input(),true,false,1000,1);ExperimentArtifactWriter.ExperimentArtifacts manifestOut=ExperimentArtifactWriter.write(report,directory.resolve("duplicate-manifest"),"run");
        String raw=new String(Files.readAllBytes(manifestOut.getManifest()),StandardCharsets.UTF_8);Files.write(manifestOut.getManifest(),("{\"schema\":\"workflowsim-experiment-manifest-v4\","+raw.substring(raw.indexOf('{')+1)).getBytes(StandardCharsets.UTF_8));
        assertThrows(java.io.IOException.class,()->ExperimentArtifactValidator.validate(manifestOut.getManifest()));
        ExperimentArtifactWriter.ExperimentArtifacts eventOut=ExperimentArtifactWriter.write(report,directory.resolve("duplicate-event"),"run");List<String> lines=Files.readAllLines(eventOut.getEvents(),StandardCharsets.UTF_8);lines.set(0,"{\"sequence\":0,"+lines.get(0).substring(1));Files.write(eventOut.getEvents(),lines,StandardCharsets.UTF_8);rehash(eventOut,"events",eventOut.getEvents());
        assertThrows(java.io.IOException.class,()->ExperimentArtifactValidator.validate(eventOut.getManifest()));
        ExperimentArtifactWriter.ExperimentArtifacts old=ExperimentArtifactWriter.write(run(input(),true,false,0,1),directory.resolve("downgrade"),"run");JsonObject downgraded=read(old.getManifest());downgraded.addProperty("schema","workflowsim-experiment-manifest-v3");save(old.getManifest(),downgraded);
        assertThrows(java.io.IOException.class,()->ExperimentArtifactValidator.validate(old.getManifest()));
    }
    @Test void coherentlyRehashedNegativeTaskWindowIsRejected()throws Exception{
        ExperimentArtifactWriter.ExperimentArtifacts out=ExperimentArtifactWriter.write(run(input(),true,false,1000,1),directory.resolve("negative-window"),"run");JsonObject manifest=read(out.getManifest());JsonObject task=manifest.getAsJsonObject("result").getAsJsonArray("tasks").get(0).getAsJsonObject();int job=task.get("jobId").getAsInt();task.addProperty("finishTime",0);save(out.getManifest(),manifest);
        List<String> changed=new ArrayList<>();for(String line:Files.readAllLines(out.getEvents(),StandardCharsets.UTF_8)){JsonObject event=JsonParser.parseString(line).getAsJsonObject();if(event.get("type").getAsString().equals("TASK_EXECUTION_MODELED")&&event.get("jobId").getAsInt()==job)event.getAsJsonObject("attributes").addProperty("taskFinishTime",0);changed.add(event.toString());}Files.write(out.getEvents(),changed,StandardCharsets.UTF_8);rehash(out,"events",out.getEvents());
        java.io.IOException failure=assertThrows(java.io.IOException.class,()->ExperimentArtifactValidator.validate(out.getManifest()));assertTrue(failure.getMessage().contains("window"),failure.getMessage());
    }
    @Test void retryCreationMustFollowTheActualFailedReturnSequence()throws Exception{
        org.workflowsim.failure.FailureModelConfig failures=org.workflowsim.failure.FailureModelConfig.builder().clusteringAlgorithm(org.workflowsim.failure.FailureParameters.FTCluteringAlgorithm.FTCLUSTERING_NOOP).monitorMode(org.workflowsim.failure.FailureParameters.FTCMonitor.MONITOR_NONE).generatorMode(org.workflowsim.failure.FailureParameters.FTCFailure.FAILURE_ALL)
                .generatorSpecs(new org.workflowsim.utils.DistributionSpec[][]{{org.workflowsim.utils.DistributionSpec.of(org.workflowsim.utils.DistributionGenerator.DistributionFamily.WEIBULL,2,1)}}).maxTotalRetryJobs(64).build();
        PlatformProfile p=PlatformProfile.builder("retry-context").addHost(new PlatformProfile.HostSpec(10,2,1000,4096,10000L,1000000L)).addVm(new PlatformProfile.VmSpec(7,1000,1,512,20L,10000L,"Xen",PlatformProfile.CloudletSchedulerMode.SPACE_SHARED)).pinVmToHost(7,10).build();SimulationReport report=null;
        for(long seed:new long[]{1,5,17,22}){SimulationConfig config=SimulationConfig.builder(input().toString(),1).planningAlgorithm(Parameters.PlanningAlgorithm.RANDOM).schedulingAlgorithm(Parameters.SchedulingAlgorithm.STATIC).fileSystem(ReplicaCatalog.FileSystem.LOCAL).randomSeed(seed).failureModel(failures).dataMovementModel(DataMovementModel.coherentFileDataflowV2()).networkEvidence(NetworkEvidenceConfig.fileLifecycleV2(10000)).build();SimulationReport candidate=new SimulationRunner().run(config,p);if(candidate.getMetrics().getRetryJobCreatedCount()>0){report=candidate;break;}}
        assertNotNull(report,"fixed seed set must exercise actual retry provenance");ExperimentArtifactWriter.ExperimentArtifacts out=ExperimentArtifactWriter.write(report,directory.resolve("retry-order"),"run");List<JsonObject> events=new ArrayList<>();for(String line:Files.readAllLines(out.getEvents(),StandardCharsets.UTF_8))events.add(JsonParser.parseString(line).getAsJsonObject());int created=-1,failed=-1;
        for(int i=0;i<events.size();i++)if(events.get(i).get("type").getAsString().equals("RETRY_JOB_CREATED")){created=i;int previous=events.get(i).getAsJsonObject("attributes").get("failedJobId").getAsInt();for(int j=0;j<i;j++)if(events.get(j).get("type").getAsString().equals("JOB_FAILED")&&events.get(j).get("jobId").getAsInt()==previous)failed=j;break;}
        assertTrue(created>failed&&failed>=0);assertEquals(events.get(created).get("simulationTime"),events.get(failed).get("simulationTime"));java.util.Collections.swap(events,created,failed);List<String> lines=new ArrayList<>();for(int i=0;i<events.size();i++){events.get(i).addProperty("sequence",i);lines.add(events.get(i).toString());}Files.write(out.getEvents(),lines,StandardCharsets.UTF_8);rehash(out,"events",out.getEvents());
        java.io.IOException failure=assertThrows(java.io.IOException.class,()->ExperimentArtifactValidator.validate(out.getManifest()));assertTrue(failure.getMessage().contains("Retry creation"),failure.getMessage());
    }
    @Test void validatedSnapshotsRemainPairedAfterDiskReplacement()throws Exception{
        ExperimentArtifactWriter.ExperimentArtifacts out=ExperimentArtifactWriter.write(run(input(),true,false,1000,1),directory.resolve("snapshot"),"run");ExperimentArtifactValidator.ValidationResult validated=ExperimentArtifactValidator.validate(out.getManifest());JsonObject expected=validated.getManifestSnapshot(),changed=expected.deepCopy();changed.getAsJsonObject("configuration").addProperty("rootSeed",99);save(out.getManifest(),changed);
        JsonObject caller=validated.getManifestSnapshot();caller.remove("dataflowPlan");assertEquals(expected,validated.getManifestSnapshot());assertTrue(validated.getDecodedFileLifecycle().isQuiescent());
    }
    private Path input()throws Exception{Path path=directory.resolve("workflow.dax");if(!Files.exists(path))Files.write(path,"<adag><job id=\"p\" runtime=\"1\"><uses file=\"data\" link=\"output\" size=\"1000000\"/></job><job id=\"c\" runtime=\"1\"><uses file=\"data\" link=\"input\" size=\"1000000\"/></job><child ref=\"c\"><parent ref=\"p\"/></child></adag>".getBytes(StandardCharsets.UTF_8));return path;}
    private static SimulationReport run(Path path,boolean shared,boolean fat,int budget,long bandwidth)throws Exception{
        SimulationConfig config=SimulationConfig.builder(path.toString(),2).planningAlgorithm(Parameters.PlanningAlgorithm.RANDOM).schedulingAlgorithm(Parameters.SchedulingAlgorithm.STATIC).fileSystem(ReplicaCatalog.FileSystem.LOCAL).randomSeed(1)
                .dataMovementModel(shared?DataMovementModel.coherentFileDataflowV2():DataMovementModel.coherentFileDataflowNoContentionV2()).networkEvidence(budget==0?NetworkEvidenceConfig.off():NetworkEvidenceConfig.fileLifecycleV2(budget)).build();
        PlatformProfile.Builder p=PlatformProfile.builder("lifecycle-artifact");int[] ids={7,42};for(int i=0;i<2;i++){int host=10+i*10;p.addHost(new PlatformProfile.HostSpec(host,2,2000,4096,10000L,1000000L)).addVm(new PlatformProfile.VmSpec(ids[i],1000,1,512,bandwidth,10000L,"Xen",PlatformProfile.CloudletSchedulerMode.SPACE_SHARED)).pinVmToHost(ids[i],host);}if(fat)p.networkTopology(NetworkTopologySpec.fatTree(4,1));return new SimulationRunner().run(config,p.build());
    }
    private static JsonObject read(Path path)throws Exception{return JsonParser.parseString(new String(Files.readAllBytes(path),StandardCharsets.UTF_8)).getAsJsonObject();}
    private static void save(Path path,JsonObject value)throws Exception{Files.write(path,value.toString().getBytes(StandardCharsets.UTF_8));}
    private static JsonObject role(JsonObject manifest,String role){for(JsonElement value:manifest.getAsJsonArray("artifacts"))if(value.getAsJsonObject().get("role").getAsString().equals(role))return value.getAsJsonObject();throw new AssertionError("Missing role "+role);}
    private static void rehash(ExperimentArtifactWriter.ExperimentArtifacts out,String role,Path path)throws Exception{byte[] bytes=Files.readAllBytes(path);StringBuilder sha=new StringBuilder();for(byte b:MessageDigest.getInstance("SHA-256").digest(bytes))sha.append(String.format("%02x",b&255));JsonObject manifest=read(out.getManifest());JsonObject entry=role(manifest,role);entry.addProperty("sha256",sha.toString());entry.addProperty("sizeBytes",bytes.length);save(out.getManifest(),manifest);}
}
