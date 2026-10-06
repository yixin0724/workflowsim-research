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
import org.workflowsim.data.v2.FileLifecycleEvidence;
import org.workflowsim.experiment.ExperimentArtifactWriter;
import org.workflowsim.experiment.SimulationReport;
import org.workflowsim.experiment.SimulationRunner;
import org.workflowsim.network.NetworkTopologySpec;
import org.workflowsim.platform.PlatformProfile;
import org.workflowsim.utils.Parameters;
import org.workflowsim.utils.ReplicaCatalog;
import org.workflowsim.utils.SimulationConfig;

/** V2 lifecycle content is scientific core, independent of artifact hash/provenance exemptions. */
class FileLifecycleRerunIntegrationTest {
    @TempDir Path directory;
    private int serial;
    private boolean logging;
    @BeforeEach void quiet(){logging=Log.isDisabled();Log.disable();}
    @AfterEach void restore(){Log.setDisabled(logging);}
    @Test void readerAndRebuilderPreserveNewKindsModeBudgetAndDistinctPath()throws Exception{
        for(boolean shared:new boolean[]{true,false})for(boolean fat:new boolean[]{false,true}){
            Path run=fixture(shared,fat,2000);RerunEvidence evidence=RerunEvidenceReader.read(run);assertNull(evidence.getNetworkLedgerPath());assertNull(evidence.getNetworkCaptureStatus());
            assertEquals(run.resolve("result.file-lifecycle.json"),evidence.getFileLifecyclePath());assertEquals(FileLifecycleEvidence.Status.COMPLETE,evidence.getFileLifecycleCaptureStatus());
            ManifestConfigRebuilder.RebuiltConfiguration rebuilt=ManifestConfigRebuilder.rebuild(evidence,RerunInputResolver.resolve(evidence));
            assertEquals(shared,rebuilt.getConfig().getDataMovementModel().isCoherentShared());assertEquals(NetworkEvidenceConfig.Mode.FILE_LIFECYCLE_V2,rebuilt.getConfig().getNetworkEvidenceConfig().getMode());assertEquals(2000,rebuilt.getConfig().getNetworkEvidenceConfig().getMaxTraceRecords());assertEquals(fat,rebuilt.getPlatform().getNetworkTopology()!=null);
        }
    }
    @Test void realEndpointAndFatSharedAndIsolatedRerunsAreIdentical()throws Exception{
        for(boolean shared:new boolean[]{true,false})for(boolean fat:new boolean[]{false,true}){
            Path run=fixture(shared,fat,2000),output=directory.resolve("repeat-"+(serial++));RerunReport result=RerunDiffExecutor.execute(run,output);
            assertEquals(RerunVerdict.IDENTICAL_CORE,result.getVerdict(),result.toJson());assertEquals(read(run.resolve("result.file-lifecycle.json")),read(output.resolve("rerun/result.file-lifecycle.json")));
        }
    }
    @Test void offV2RerunPreservesCorePlanWithoutInventingEitherSidecar()throws Exception{
        Path run=fixture(true,false,0),output=directory.resolve("off-repeat");RerunEvidence evidence=RerunEvidenceReader.read(run);assertNull(evidence.getFileLifecyclePath());assertTrue(evidence.getManifest().has("dataflowPlan"));
        assertEquals(RerunVerdict.IDENTICAL_CORE,RerunDiffExecutor.execute(run,output).getVerdict());assertFalse(Files.exists(output.resolve("rerun/result.file-lifecycle.json")));assertFalse(Files.exists(output.resolve("rerun/result.network-ledger.json")));
    }
    @Test void rehashedValidLifecycleOnlyDriftIsNotHiddenByArtifactHashes()throws Exception{
        Path original=fixture(true,false,2000),changed=directory.resolve("changed");RerunTestSupport.copyEvidenceFiles(original,changed);Path path=changed.resolve("result.file-lifecycle.json");JsonObject document=read(path);boolean modified=false;
        for(JsonElement value:document.getAsJsonArray("events")){JsonObject event=value.getAsJsonObject();if("COPY_SETTLED".equals(event.get("type").getAsString())){JsonObject p=event.getAsJsonObject("payload");double effective=p.get("effectiveTime").getAsDouble(),observed=event.get("observedTime").getAsDouble();if(Math.nextUp(effective)<observed){p.addProperty("effectiveTime",Math.nextUp(effective));modified=true;break;}}}
        assertTrue(modified);ExperimentArtifactWriter.writeJson(path,document);rehash(changed,path);RerunEvidence a=RerunEvidenceReader.read(original),b=RerunEvidenceReader.read(changed);EvidenceCoreDiffer.DiffResult diff=EvidenceCoreDiffer.compare(a,b);
        assertFalse(diff.isIdenticalCore());assertTrue(diff.getCoreDivergences().stream().allMatch(d->d.getPointer().startsWith("/fileLifecycle/")),diff.getCoreDivergences().toString());
        assertEquals(RerunVerdict.DIVERGED,RerunDiffExecutor.execute(changed,directory.resolve("changed-rerun")).getVerdict());
    }
    @Test void comparatorDoesNotApplyRootRuntimeExemptionInsideLifecycle()throws Exception{
        Path run=fixture(true,false,2000);RerunEvidence evidence=RerunEvidenceReader.read(run);JsonObject changed=read(evidence.getFileLifecyclePath()),runtime=new JsonObject();runtime.addProperty("id",9007199254740993L);changed.add("runtime",runtime);Path sidecar=directory.resolve("comparison-only.json");ExperimentArtifactWriter.writeJson(sidecar,changed);
        // Deliberately bypass semantic reader only for the comparator's namespace/exact-number contract.
        RerunEvidence comparison=new RerunEvidence(evidence.getRunDirectory(),evidence.getManifestPath(),evidence.getMetricsPath(),evidence.getEventsPath(),evidence.getEventCount(),evidence.getManifest(),null,null,sidecar,FileLifecycleEvidence.Status.COMPLETE);
        assertTrue(EvidenceCoreDiffer.compare(evidence,comparison).getCoreDivergences().stream().anyMatch(d->d.getPointer().startsWith("/fileLifecycle/runtime")));
        assertThrows(IllegalArgumentException.class,()->new RerunEvidence(evidence.getRunDirectory(),evidence.getManifestPath(),evidence.getMetricsPath(),evidence.getEventsPath(),evidence.getEventCount(),evidence.getManifest()));
    }
    private Path fixture(boolean shared,boolean fat,int budget)throws Exception{
        Path base=directory.resolve("study-"+(serial++)),input=base.resolve("inputs/flow.dax"),run=base.resolve("runs/original");Files.createDirectories(input.getParent());
        Files.write(input,"<adag><job id=\"p\" runtime=\"1\"><uses file=\"input\" link=\"input\" size=\"0.5\"/><uses file=\"a\" link=\"output\" size=\"2000000\"/><uses file=\"b\" link=\"output\" size=\"3000000\"/></job><job id=\"c\" runtime=\"1\"><uses file=\"a\" link=\"input\" size=\"2000000\"/><uses file=\"b\" link=\"input\" size=\"3000000\"/></job><child ref=\"c\"><parent ref=\"p\"/></child></adag>".getBytes(StandardCharsets.UTF_8));
        SimulationConfig config=SimulationConfig.builder(input.toString(),2).planningAlgorithm(Parameters.PlanningAlgorithm.RANDOM).schedulingAlgorithm(Parameters.SchedulingAlgorithm.STATIC).fileSystem(ReplicaCatalog.FileSystem.LOCAL).randomSeed(1).cloudSimMinEventIntervalSeconds(.1)
                .dataMovementModel(shared?DataMovementModel.coherentFileDataflowV2():DataMovementModel.coherentFileDataflowNoContentionV2()).networkEvidence(budget==0?NetworkEvidenceConfig.off():NetworkEvidenceConfig.fileLifecycleV2(budget)).build();
        PlatformProfile.Builder p=PlatformProfile.builder("file-rerun");int[] ids={7,42};for(int i=0;i<2;i++){int host=10+i*10;p.addHost(new PlatformProfile.HostSpec(host,2,2000,4096,10000L,1000000L)).addVm(new PlatformProfile.VmSpec(ids[i],1000,1,512,1L,10000L,"Xen",PlatformProfile.CloudletSchedulerMode.SPACE_SHARED)).pinVmToHost(ids[i],host);}if(fat)p.networkTopology(NetworkTopologySpec.fatTree(4,1));
        SimulationReport report=new SimulationRunner().run(config,p.build());ExperimentArtifactWriter.write(report,run,"result");return run;
    }
    private static JsonObject read(Path path)throws Exception{return JsonParser.parseString(new String(Files.readAllBytes(path),StandardCharsets.UTF_8)).getAsJsonObject();}
    private static void rehash(Path run,Path path)throws Exception{byte[] bytes=Files.readAllBytes(path);StringBuilder sha=new StringBuilder();for(byte b:MessageDigest.getInstance("SHA-256").digest(bytes))sha.append(String.format("%02x",b&255));Path manifest=run.resolve("result.manifest.json");JsonObject root=read(manifest);for(JsonElement value:root.getAsJsonArray("artifacts")){JsonObject artifact=value.getAsJsonObject();if("file-lifecycle".equals(artifact.get("role").getAsString())){artifact.addProperty("sha256",sha.toString());artifact.addProperty("sizeBytes",bytes.length);}}ExperimentArtifactWriter.writeJson(manifest,root);}
}
