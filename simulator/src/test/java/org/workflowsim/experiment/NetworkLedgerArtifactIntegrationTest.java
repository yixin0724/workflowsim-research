package org.workflowsim.experiment;

import static org.junit.jupiter.api.Assertions.*;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.Map;
import org.cloudbus.cloudsim.Log;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.workflowsim.data.DataMovementModel;
import org.workflowsim.data.NetworkEvidenceConfig;
import org.workflowsim.data.NetworkLedgerCodec;
import org.workflowsim.data.NetworkRunEvidence;
import org.workflowsim.data.TransferTraceSnapshot;
import org.workflowsim.network.NetworkTopologySpec;
import org.workflowsim.platform.PlatformProfile;
import org.workflowsim.utils.Parameters;
import org.workflowsim.utils.ReplicaCatalog;
import org.workflowsim.utils.SimulationConfig;

/** Actual ON bundles and coherently rehashed semantic attacks; no historical outputs are edited. */
class NetworkLedgerArtifactIntegrationTest {
    @TempDir Path temporary;
    private boolean logDisabled;
    private int serial;
    @BeforeEach void quiet(){logDisabled=Log.isDisabled();Log.disable();}
    @AfterEach void restore(){Log.setDisabled(logDisabled);}

    @Test void fullEndpointAndFatTreeBundlesHaveValidatedLedgerRoles() throws Exception {
        for(boolean fat:Arrays.asList(false,true)){
            SimulationReport report=report(fat,2000,true);
            ExperimentArtifactWriter.ExperimentArtifacts files=write(report);
            assertNotNull(files.getNetworkLedger());assertTrue(Files.isRegularFile(files.getNetworkLedger()));
            ExperimentArtifactValidator.ValidationResult checked=ExperimentArtifactValidator.validate(files.getManifest());
            assertEquals(files.getNetworkLedger(),checked.getNetworkLedger());
            assertEquals(TransferTraceSnapshot.Status.COMPLETE,checked.getNetworkCaptureStatus());
            JsonObject manifest=read(files.getManifest());assertEquals(3,manifest.getAsJsonArray("artifacts").size());
            JsonObject descriptor=role(manifest,"network-ledger");
            assertEquals(ExperimentProvenance.fingerprint(files.getNetworkLedger()),descriptor.get("sha256").getAsString());
            assertEquals(Files.size(files.getNetworkLedger()),descriptor.get("sizeBytes").getAsLong());
            assertEquals(NetworkLedgerCodec.encode(report.getNetworkEvidence()),new String(Files.readAllBytes(files.getNetworkLedger()),StandardCharsets.UTF_8));
        }
    }

    @Test void offHasNoNewRoleOrConfigMemberEvenWhenAnOldUnreferencedLedgerExists() throws Exception {
        SimulationReport on=report(false,2000,true);Path dir=temporary.resolve("reuse");
        ExperimentArtifactWriter.ExperimentArtifacts first=ExperimentArtifactWriter.write(on,dir,"run");
        assertTrue(Files.exists(first.getNetworkLedger()));
        SimulationReport off=new SimulationRunner().run(on.getConfig().toBuilder().networkEvidence(NetworkEvidenceConfig.off()).build(),on.getPlatform());
        ExperimentArtifactWriter.ExperimentArtifacts second=ExperimentArtifactWriter.write(off,dir,"run");
        assertNull(second.getNetworkLedger());JsonObject manifest=read(second.getManifest());
        assertFalse(manifest.getAsJsonObject("configuration").has("networkEvidence"));assertEquals(2,manifest.getAsJsonArray("artifacts").size());
        ExperimentArtifactValidator.ValidationResult checked=ExperimentArtifactValidator.validate(second.getManifest());
        assertNull(checked.getNetworkLedger());assertNull(checked.getNetworkCaptureStatus());
    }

    @Test void explicitPlacementsReducedCoresAndCrossPodRoutesValidateWithoutRebuildingLinks() throws Exception {
        Map<Integer,Integer> sameEdge=new java.util.LinkedHashMap<Integer,Integer>();sameEdge.put(10,0);sameEdge.put(20,0);
        Map<Integer,Integer> crossPod=new java.util.LinkedHashMap<Integer,Integer>();crossPod.put(10,1);crossPod.put(20,2);
        for(NetworkTopologySpec spec:Arrays.asList(NetworkTopologySpec.fatTree(4,1,1,sameEdge),
                NetworkTopologySpec.fatTree(4,1,1,crossPod),NetworkTopologySpec.fatTree(4,1,3,crossPod),NetworkTopologySpec.fatTree(2,1))){
            ExperimentArtifactWriter.ExperimentArtifacts files=write(report(true,2000,true,spec));
            assertEquals(TransferTraceSnapshot.Status.COMPLETE,ExperimentArtifactValidator.validate(files.getManifest()).getNetworkCaptureStatus());
        }
    }

    @Test void truncatedAndZeroFlowBundlesRemainDifferentFromOff() throws Exception {
        for(boolean fat:Arrays.asList(false,true)){
            ExperimentArtifactWriter.ExperimentArtifacts small=write(report(fat,1,true));
            assertEquals(TransferTraceSnapshot.Status.TRUNCATED,ExperimentArtifactValidator.validate(small.getManifest()).getNetworkCaptureStatus());
            assertFalse(NetworkLedgerCodec.decode(text(small.getNetworkLedger())).getMetrics().getTransferMetrics().isAvailable());
            ExperimentArtifactWriter.ExperimentArtifacts zero=write(report(fat,2000,false));
            assertEquals(TransferTraceSnapshot.Status.COMPLETE,ExperimentArtifactValidator.validate(zero.getManifest()).getNetworkCaptureStatus());
            assertTrue(NetworkLedgerCodec.decode(text(zero.getNetworkLedger())).getMetrics().getTransferMetrics().getFlows().isEmpty());
        }
    }

    @Test void networkPreflightRejectsMismatchedCaptureBeforeCreatingOrReplacingFiles() throws Exception {
        SimulationReport report=report(false,2000,true);
        Field field=SimulationReport.class.getDeclaredField("networkEvidence");field.setAccessible(true);
        field.set(report,NetworkRunEvidence.empty(NetworkEvidenceConfig.fluidGroupLedger(1),report.getConfig().getDataMovementModel().getKind()));
        Path absent=temporary.resolve("must-not-create");assertThrows(IOException.class,()->ExperimentArtifactWriter.write(report,absent,"run"));
        assertFalse(Files.exists(absent));Path existing=Files.createDirectory(temporary.resolve("sentinels"));
        byte[] sentinel="existing evidence must survive invalid export".getBytes(StandardCharsets.UTF_8);
        for(String suffix:Arrays.asList("manifest.json","metrics.json","events.jsonl","network-ledger.json"))Files.write(existing.resolve("run."+suffix),sentinel);
        assertThrows(IOException.class,()->ExperimentArtifactWriter.write(report,existing,"run"));
        for(String suffix:Arrays.asList("manifest.json","metrics.json","events.jsonl","network-ledger.json"))assertArrayEquals(sentinel,Files.readAllBytes(existing.resolve("run."+suffix)));
    }

    @Test void standaloneOnManifestStillRequiresTheCompleteBundleWriter() throws Exception {
        SimulationReport report=report(false,2000,true);Path absent=temporary.resolve("standalone").resolve("run.json");
        assertThrows(UnsupportedOperationException.class,()->ExperimentManifestWriter.writeJson(report,absent));
        assertFalse(Files.exists(absent.getParent()));
        assertThrows(UnsupportedOperationException.class,()->ExperimentManifestWriter.writeJson(report,absent,Collections.<Map<String,Object>>emptyList()));
    }

    @Test void rehashedMetricForgeryIsRejectedByContentNotOnlyHash() throws Exception {
        ExperimentArtifactWriter.ExperimentArtifacts files=write(report(false,2000,true));JsonObject ledger=read(files.getNetworkLedger());
        ledger.getAsJsonObject("metrics").getAsJsonObject("transferMetrics").addProperty("admittedPayloadBytes",1);
        replaceLedger(files,ledger);assertThrows(IOException.class,()->ExperimentArtifactValidator.validate(files.getManifest()));
    }

    @Test void rehashedMissingServiceWithRepairedSequenceIsRejected() throws Exception {
        ExperimentArtifactWriter.ExperimentArtifacts files=write(report(false,2000,true));JsonObject ledger=read(files.getNetworkLedger());
        JsonArray rows=ledger.getAsJsonObject("evidence").getAsJsonObject("traceSnapshot").getAsJsonArray("events");
        for(int i=0;i<rows.size();i++)if("SERVICE_SEGMENT".equals(rows.get(i).getAsJsonObject().get("type").getAsString())){rows.remove(i);break;}
        renumber(rows);replaceLedger(files,ledger);assertThrows(IOException.class,()->ExperimentArtifactValidator.validate(files.getManifest()));
    }

    @Test void configurationAndLedgerModeBudgetAreBidirectionallyBound() throws Exception {
        for(boolean remove:Arrays.asList(false,true)){
            ExperimentArtifactWriter.ExperimentArtifacts files=write(report(false,2000,true));JsonObject manifest=read(files.getManifest());
            if(remove)manifest.getAsJsonObject("configuration").remove("networkEvidence");
            else manifest.getAsJsonObject("configuration").getAsJsonObject("networkEvidence").addProperty("maxTraceRecords",2001);
            ExperimentArtifactWriter.writeJson(files.getManifest(),manifest);
            assertThrows(IOException.class,()->ExperimentArtifactValidator.validate(files.getManifest()));
        }
        ExperimentArtifactWriter.ExperimentArtifacts files=write(report(false,2000,true));JsonObject manifest=read(files.getManifest());
        JsonArray roles=manifest.getAsJsonArray("artifacts");roles.remove(2);ExperimentArtifactWriter.writeJson(files.getManifest(),manifest);
        assertThrows(IOException.class,()->ExperimentArtifactValidator.validate(files.getManifest()));
    }

    @Test void invalidNetworkConfigurationObjectsAreNotTreatedAsOff() throws Exception {
        for(JsonElement invalid:Arrays.asList(JsonNull.INSTANCE,new com.google.gson.JsonPrimitive(false),
                JsonParser.parseString("{\"mode\":\"FLUID_GROUP_LEDGER_V1\",\"maxTraceRecords\":0.5}"),
                JsonParser.parseString("{\"mode\":\"OFF\",\"maxTraceRecords\":0}"))){
            ExperimentArtifactWriter.ExperimentArtifacts files=write(report(false,2000,true));JsonObject manifest=read(files.getManifest());
            manifest.getAsJsonObject("configuration").add("networkEvidence",invalid);ExperimentArtifactWriter.writeJson(files.getManifest(),manifest);
            assertThrows(IOException.class,()->ExperimentArtifactValidator.validate(files.getManifest()));
        }
    }

    @Test void rehashedValidCodecSourceForgeryIsRejectedAgainstJobVmPlacement() throws Exception {
        ExperimentArtifactWriter.ExperimentArtifacts files=write(report(false,2000,true));JsonObject ledger=read(files.getNetworkLedger());
        for(JsonElement entry:ledger.getAsJsonObject("evidence").getAsJsonArray("bindings")){
            JsonObject binding=entry.getAsJsonObject();if("PARENT_GROUP_V1".equals(binding.get("groupKind").getAsString()))binding.addProperty("sourceEndpoint","VM:999");
        }
        ledger=recompute(ledger);replaceLedger(files,ledger);assertThrows(IOException.class,()->ExperimentArtifactValidator.validate(files.getManifest()));
    }

    @Test void alternativeRegisteredFatTreePathIsNotTheDeclaredDeterministicRoute() throws Exception {
        ExperimentArtifactWriter.ExperimentArtifacts files=write(report(true,2000,true));JsonObject ledger=read(files.getNetworkLedger());
        JsonObject evidence=ledger.getAsJsonObject("evidence");
        for(JsonElement b:evidence.getAsJsonArray("bindings")){
            JsonObject binding=b.getAsJsonObject();if(!"PARENT_GROUP_V1".equals(binding.get("groupKind").getAsString()))continue;
            long ordinal=binding.get("admissionOrdinal").getAsLong();JsonArray resources=binding.getAsJsonArray("occupiedResources");
            for(int i=0;i<resources.size();i++)resources.set(i,new com.google.gson.JsonPrimitive(resources.get(i).getAsString().replace("AGG:0:0","AGG:0:1")));
            for(JsonElement r:evidence.getAsJsonObject("traceSnapshot").getAsJsonArray("events")){
                JsonObject row=r.getAsJsonObject();if("START".equals(row.get("type").getAsString())&&row.get("admissionOrdinal").getAsLong()==ordinal)
                    row.getAsJsonObject("start").add("occupiedResources",resources.deepCopy());
            }
        }
        ledger=recompute(ledger);replaceLedger(files,ledger);assertThrows(IOException.class,()->ExperimentArtifactValidator.validate(files.getManifest()));
    }

    @Test void delayedObservationCannotReleaseAJobBeforeItsInputsArrive() throws Exception {
        ExperimentArtifactWriter.ExperimentArtifacts files=write(report(false,2000,true));JsonObject ledger=read(files.getNetworkLedger());
        JsonObject trace=ledger.getAsJsonObject("evidence").getAsJsonObject("traceSnapshot");
        for(JsonElement element:trace.getAsJsonArray("events")){
            JsonObject row=element.getAsJsonObject();String type=row.get("type").getAsString();
            if("SERVICE_SEGMENT".equals(type)||"COMPLETE".equals(type)||"RATE_CHANGE".equals(type))row.addProperty("observedTime",row.get("observedTime").getAsDouble()+.2);
        }
        trace.addProperty("engineTime",trace.get("engineTime").getAsDouble()+.2);
        ledger=recompute(ledger);replaceLedger(files,ledger);assertThrows(IOException.class,()->ExperimentArtifactValidator.validate(files.getManifest()));
    }

    @Test void inventedSourceCapacityIsRejectedEvenWithConsistentFlowRatesAndMetrics() throws Exception {
        ExperimentArtifactWriter.ExperimentArtifacts files=write(report(false,2000,true));JsonObject ledger=read(files.getNetworkLedger());
        JsonArray rows=ledger.getAsJsonObject("evidence").getAsJsonObject("traceSnapshot").getAsJsonArray("events");
        JsonObject extra=rows.get(0).getAsJsonObject().deepCopy();extra.getAsJsonObject("capacity").addProperty("resourceKey","source");
        JsonArray changed=new JsonArray();changed.add(extra);for(JsonElement row:rows)changed.add(row);renumber(changed);
        ledger.getAsJsonObject("evidence").getAsJsonObject("traceSnapshot").add("events",changed);
        ledger=recompute(ledger);replaceLedger(files,ledger);assertThrows(IOException.class,()->ExperimentArtifactValidator.validate(files.getManifest()));
    }

    @Test void localityCannotInventSavedBytesWhileKeepingTheAdmittedPayload() throws Exception {
        ExperimentArtifactWriter.ExperimentArtifacts files=write(report(false,2000,true));JsonObject ledger=read(files.getNetworkLedger());
        JsonObject input=ledger.getAsJsonObject("evidence").getAsJsonObject("inputDemand");
        input.addProperty("localReferenceCount",1);input.addProperty("localReferenceBytes",.5);
        input.addProperty("transferableReferenceBytes",input.get("requiredReferenceBytes").getAsBigDecimal().subtract(new BigDecimal(".5")));
        ledger=recompute(ledger);replaceLedger(files,ledger);assertThrows(IOException.class,()->ExperimentArtifactValidator.validate(files.getManifest()));
    }

    @Test void coherentNegativeHostIdentityIsNotAValidDeclaredPlatform() throws Exception {
        ExperimentArtifactWriter.ExperimentArtifacts files=write(report(false,2000,true));JsonObject manifest=read(files.getManifest());
        for(JsonElement host:manifest.getAsJsonObject("platform").getAsJsonArray("hosts"))
            if(host.getAsJsonObject().get("id").getAsInt()==10)host.getAsJsonObject().addProperty("id",-10);
        for(JsonElement vm:manifest.getAsJsonObject("platform").getAsJsonArray("vms")){
            JsonObject value=vm.getAsJsonObject();if(value.get("preflightHostId").getAsInt()==10){value.addProperty("preflightHostId",-10);value.addProperty("pinnedHostId",-10);}
        }
        manifest.getAsJsonObject("result").getAsJsonObject("actualVmHostAssignments").addProperty("7",-10);
        ExperimentArtifactWriter.writeJson(files.getManifest(),manifest);
        assertThrows(IOException.class,()->ExperimentArtifactValidator.validate(files.getManifest()));
    }

    @Test void descriptorSizesMustBeExactIntegers() throws Exception {
        ExperimentArtifactWriter.ExperimentArtifacts files=write(report(false,2000,true));JsonObject manifest=read(files.getManifest());
        role(manifest,"network-ledger").addProperty("sizeBytes",new BigDecimal(Files.size(files.getNetworkLedger())).add(new BigDecimal(".5")));
        ExperimentArtifactWriter.writeJson(files.getManifest(),manifest);assertThrows(IOException.class,()->ExperimentArtifactValidator.validate(files.getManifest()));
    }

    @Test void rehashedMalformedUtf8LedgerIsRejectedWithoutReplacementDecoding() throws Exception {
        ExperimentArtifactWriter.ExperimentArtifacts files=write(report(false,2000,true));
        Files.write(files.getNetworkLedger(),new byte[]{(byte)0xC3,(byte)0x28});
        JsonObject manifest=read(files.getManifest());JsonObject descriptor=role(manifest,"network-ledger");
        descriptor.addProperty("sha256",ExperimentProvenance.fingerprint(files.getNetworkLedger()));
        descriptor.addProperty("sizeBytes",Files.size(files.getNetworkLedger()));ExperimentArtifactWriter.writeJson(files.getManifest(),manifest);
        assertThrows(IOException.class,()->ExperimentArtifactValidator.validate(files.getManifest()));
    }

    @Test void relocationPreservesValidatedEvidenceWithoutAbsoluteSidecarPaths() throws Exception {
        ExperimentArtifactWriter.ExperimentArtifacts files=write(report(true,2000,true));Path relocated=Files.createDirectory(temporary.resolve("relocated"));
        for(Path file:Arrays.asList(files.getManifest(),files.getMetrics(),files.getEvents(),files.getNetworkLedger()))Files.copy(file,relocated.resolve(file.getFileName()));
        ExperimentArtifactValidator.ValidationResult result=ExperimentArtifactValidator.validate(relocated.resolve(files.getManifest().getFileName()));
        assertEquals(relocated.resolve(files.getNetworkLedger().getFileName()),result.getNetworkLedger());
    }

    private SimulationReport report(boolean fat,int budget,boolean input)throws Exception{
        return report(fat,budget,input,fat?NetworkTopologySpec.fatTree(4,1):null);
    }
    private SimulationReport report(boolean fat,int budget,boolean input,NetworkTopologySpec topology)throws Exception{
        Path dax=temporary.resolve("workflow-"+(serial++)+".dax");
        String data=input?"<job id=\"p\" runtime=\"1\"><uses file=\"external\" link=\"input\" size=\"0.5\"/><uses file=\"a\" link=\"output\" size=\"5000000\"/></job>"
                +"<job id=\"c\" runtime=\"2\"><uses file=\"a\" link=\"input\" size=\"5000000\"/></job><child ref=\"c\"><parent ref=\"p\"/></child>"
                :"<job id=\"p\" runtime=\"1\"/>";
        Files.write(dax,("<adag>"+data+"</adag>").getBytes(StandardCharsets.UTF_8));
        PlatformProfile.Builder platform=PlatformProfile.builder("ledger-artifact-fixture");int[] ids={7,42};
        for(int i=0;i<2;i++)platform.addHost(new PlatformProfile.HostSpec(10+i*10,2,2000,2048,10000,1000000))
                .addVm(new PlatformProfile.VmSpec(ids[i],1000,1,512,1,10000,"Xen",PlatformProfile.CloudletSchedulerMode.SPACE_SHARED)).pinVmToHost(ids[i],10+i*10);
        if(fat)platform.networkTopology(topology);
        SimulationConfig cfg=SimulationConfig.builder(dax.toString(),2).planningAlgorithm(Parameters.PlanningAlgorithm.RANDOM)
                .schedulingAlgorithm(Parameters.SchedulingAlgorithm.STATIC).fileSystem(ReplicaCatalog.FileSystem.LOCAL).randomSeed(1)
                .dataMovementModel(fat?DataMovementModel.fatTreeContentionV1():DataMovementModel.preExecutionTransferDelayWithContentionV1())
                .networkEvidence(NetworkEvidenceConfig.fluidGroupLedger(budget)).build();
        return new SimulationRunner().run(cfg,platform.build());
    }
    private ExperimentArtifactWriter.ExperimentArtifacts write(SimulationReport report)throws Exception{return ExperimentArtifactWriter.write(report,temporary.resolve("bundle-"+(serial++)),"run");}
    private static JsonObject read(Path path)throws Exception{return JsonParser.parseString(text(path)).getAsJsonObject();}
    private static String text(Path path)throws Exception{return new String(Files.readAllBytes(path),StandardCharsets.UTF_8);}
    private static JsonObject role(JsonObject manifest,String role){for(JsonElement item:manifest.getAsJsonArray("artifacts"))if(role.equals(item.getAsJsonObject().get("role").getAsString()))return item.getAsJsonObject();throw new AssertionError("Missing role "+role);}
    private static void renumber(JsonArray rows){for(int i=0;i<rows.size();i++)rows.get(i).getAsJsonObject().addProperty("sequence",i+1);}
    private static JsonObject recompute(JsonObject document){
        NetworkRunEvidence untrustedFixture=new Gson().fromJson(document.get("evidence"),NetworkRunEvidence.class);
        JsonObject coherent=NetworkLedgerCodec.document(untrustedFixture);NetworkLedgerCodec.decode(coherent.toString());return coherent;
    }
    private static void replaceLedger(ExperimentArtifactWriter.ExperimentArtifacts files,JsonObject ledger)throws Exception{
        ExperimentArtifactWriter.writeJson(files.getNetworkLedger(),ledger);JsonObject manifest=read(files.getManifest());JsonObject descriptor=role(manifest,"network-ledger");
        descriptor.addProperty("sha256",ExperimentProvenance.fingerprint(files.getNetworkLedger()));descriptor.addProperty("sizeBytes",Files.size(files.getNetworkLedger()));
        ExperimentArtifactWriter.writeJson(files.getManifest(),manifest);
    }
}
