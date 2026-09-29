package org.workflowsim.experiments.rerun;

import static org.junit.jupiter.api.Assertions.*;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashSet;
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
import org.workflowsim.experiment.ExperimentArtifactValidator;
import org.workflowsim.experiment.ExperimentArtifactWriter;
import org.workflowsim.experiment.SimulationRunner;
import org.workflowsim.network.NetworkTopologySpec;
import org.workflowsim.platform.PlatformProfile;
import org.workflowsim.utils.Parameters;
import org.workflowsim.utils.ReplicaCatalog;
import org.workflowsim.utils.SimulationConfig;

/** NF002C: valid network content is core evidence, not only an exempted artifact hash. */
class NetworkRerunIntegrationTest {
    @TempDir Path temporary;
    private int serial;
    private boolean logDisabled;
    @BeforeEach void quiet(){logDisabled=Log.isDisabled();Log.disable();}
    @AfterEach void restore(){Log.setDisabled(logDisabled);}

    @Test void readerRetainsValidatedOptionalLedgerAndRebuilderPreservesModeAndBudget()throws Exception{
        for(boolean fat:new boolean[]{false,true})for(int budget:new int[]{1,2000}){
            Path run=fixture(fat,budget);RerunEvidence evidence=RerunEvidenceReader.read(run);
            assertEquals(run.resolve("result.network-ledger.json"),evidence.getNetworkLedgerPath());
            assertEquals(budget==1?TransferTraceSnapshot.Status.TRUNCATED:TransferTraceSnapshot.Status.COMPLETE,evidence.getNetworkCaptureStatus());
            ManifestConfigRebuilder.RebuiltConfiguration rebuilt=ManifestConfigRebuilder.rebuild(evidence,RerunInputResolver.resolve(evidence));
            assertEquals(NetworkEvidenceConfig.Mode.FLUID_GROUP_LEDGER_V1,rebuilt.getConfig().getNetworkEvidenceConfig().getMode());
            assertEquals(budget,rebuilt.getConfig().getNetworkEvidenceConfig().getMaxTraceRecords());
            assertEquals(fat,rebuilt.getConfig().getDataMovementModel().isFatTreeContentionV1());
        }
    }

    @Test void fullEndpointAndFatTreeRerunsReproduceNetworkCore()throws Exception{
        for(boolean fat:new boolean[]{false,true}){
            Path run=fixture(fat,2000),output=temporary.resolve("rerun-"+(serial++));
            RerunReport report=RerunDiffExecutor.execute(run,output);
            assertEquals(RerunVerdict.IDENTICAL_CORE,report.getVerdict(),report.toJson());
            assertTrue(Files.isRegularFile(output.resolve("rerun/result.network-ledger.json")));
            assertEquals(read(run.resolve("result.network-ledger.json")),read(output.resolve("rerun/result.network-ledger.json")));
            assertNull(report.getNetworkEvidenceCoverageNote());
            assertFalse(JsonParser.parseString(report.toJson()).getAsJsonObject().has("networkEvidenceCoverageNote"));
        }
    }

    @Test void truncatedIdenticalRerunHasAnExplicitConditionalCoverageWarning()throws Exception{
        Path run=fixture(true,1);RerunReport report=RerunDiffExecutor.execute(run,temporary.resolve("prefix-output"));
        assertEquals(RerunVerdict.IDENTICAL_CORE,report.getVerdict(),report.toJson());
        assertNotNull(report.getNetworkEvidenceCoverageNote());
        assertTrue(report.getNetworkEvidenceCoverageNote().contains("截断"));
        assertTrue(report.getNetworkEvidenceCoverageNote().contains("IDENTICAL_CORE"));
        assertTrue(JsonParser.parseString(report.toJson()).getAsJsonObject().has("networkEvidenceCoverageNote"));
        assertTrue(report.toMarkdown().contains("网络证据覆盖"));
    }

    @Test void relocatedBundleAndInputStillReproduceIdenticalNetworkCore()throws Exception{
        Path run=fixture(false,2000),study=run.getParent().getParent(),relocated=temporary.resolve("relocated-study");
        Path copy=relocated.resolve("runs/original");RerunTestSupport.copyEvidenceFiles(run,copy);
        Files.createDirectories(relocated.resolve("inputs"));Files.copy(study.resolve("inputs/flow.dax"),relocated.resolve("inputs/flow.dax"));
        RerunReport report=RerunDiffExecutor.execute(copy,temporary.resolve("relocated-result"));
        assertEquals(RerunVerdict.IDENTICAL_CORE,report.getVerdict(),report.toJson());
        assertEquals(1,report.getInputResolution().get(0).getTier());
        assertTrue(report.getInputResolution().get(0).getResolvedPath().startsWith(relocated.toString()));
    }

    @Test void rehashedValidNetworkOnlyDriftIsNotHiddenByArtifactHashExemptions()throws Exception{
        Path original=fixture(false,2000),changed=temporary.resolve("changed");RerunTestSupport.copyEvidenceFiles(original,changed);
        changeExternalId(changed,9007199254740993L);
        RerunEvidence first=RerunEvidenceReader.read(original),second=RerunEvidenceReader.read(changed);
        EvidenceCoreDiffer.DiffResult diff=EvidenceCoreDiffer.compare(first,second);
        assertFalse(diff.isIdenticalCore());
        assertTrue(diff.getCoreDivergences().stream().allMatch(d->d.getPointer().startsWith("/networkLedger/")),diff.getCoreDivergences().toString());
        assertTrue(diff.getCoreDivergences().stream().anyMatch(d->d.getPointer().endsWith("/externalTransferId")));
        RerunReport report=RerunDiffExecutor.execute(changed,temporary.resolve("drift-rerun"));
        assertEquals(RerunVerdict.DIVERGED,report.getVerdict(),report.toJson());
    }

    @Test void adjacentLongIdsAboveBinary64PrecisionRemainDifferent()throws Exception{
        Path original=fixture(false,2000),first=temporary.resolve("large-id-first"),second=temporary.resolve("large-id-second");
        RerunTestSupport.copyEvidenceFiles(original,first);RerunTestSupport.copyEvidenceFiles(original,second);
        changeExternalId(first,9007199254740992L);changeExternalId(second,9007199254740993L);
        EvidenceCoreDiffer.DiffResult diff=EvidenceCoreDiffer.compare(RerunEvidenceReader.read(first),RerunEvidenceReader.read(second));
        assertFalse(diff.isIdenticalCore());
        assertTrue(diff.getCoreDivergences().stream().anyMatch(d->"9007199254740992".equals(d.getOriginalValue())&&"9007199254740993".equals(d.getRerunValue())));
    }

    @Test void pureDifferPreservesExactDecimalsAndDoesNotApplyRootWhitelistInsideLedger()throws Exception{
        Path run=fixture(false,2000);RerunEvidence evidence=RerunEvidenceReader.read(run);
        JsonObject ledger=read(evidence.getNetworkLedgerPath());
        JsonObject metrics=ledger.getAsJsonObject("metrics").getAsJsonObject("transferMetrics");
        metrics.addProperty("admittedPayloadBytes",metrics.get("admittedPayloadBytes").getAsBigDecimal().add(new BigDecimal("0.00000000000000000001")));
        JsonObject runtime=new JsonObject();runtime.addProperty("note","must be core here");ledger.add("runtime",runtime);
        Path changed=temporary.resolve("comparison-only.json");ExperimentArtifactWriter.writeJson(changed,ledger);
        // Deliberately bypass the reader only to test the comparator's numeric/namespace contract.
        RerunEvidence comparison=new RerunEvidence(evidence.getRunDirectory(),evidence.getManifestPath(),evidence.getMetricsPath(),evidence.getEventsPath(),
                evidence.getEventCount(),evidence.getManifest(),changed,evidence.getNetworkCaptureStatus());
        EvidenceCoreDiffer.DiffResult diff=EvidenceCoreDiffer.compare(evidence,comparison);
        assertTrue(diff.getCoreDivergences().stream().anyMatch(d->"/networkLedger/metrics/transferMetrics/admittedPayloadBytes".equals(d.getPointer())));
        assertTrue(diff.getCoreDivergences().stream().anyMatch(d->d.getPointer().startsWith("/networkLedger/runtime")));
    }

    @Test void corruptOrMissingDeclaredLedgerIsEvidenceInvalidRatherThanExecutionDrift()throws Exception{
        for(boolean missing:new boolean[]{false,true}){
            Path run=fixture(false,2000);Path ledger=run.resolve("result.network-ledger.json");
            if(missing)Files.delete(ledger);else{
                JsonObject value=read(ledger);value.getAsJsonObject("metrics").getAsJsonObject("transferMetrics").addProperty("servicedBalanceDeltaBytes",123);
                ExperimentArtifactWriter.writeJson(ledger,value);rehash(run);
            }
            RerunFailureException failure=assertThrows(RerunFailureException.class,()->RerunEvidenceReader.read(run));
            assertEquals(RerunVerdict.EVIDENCE_INVALID,failure.getVerdict());
            RerunReport report=RerunDiffExecutor.execute(run,temporary.resolve("invalid-"+(serial++)));
            assertEquals(RerunVerdict.EVIDENCE_INVALID,report.getVerdict());
        }
    }

    @Test void offAbsenceRemainsOffAndAddsNoNullCoverageFieldToOldReportJson()throws Exception{
        Path run=fixture(false,0);RerunEvidence evidence=RerunEvidenceReader.read(run);
        assertNull(evidence.getNetworkLedgerPath());assertNull(evidence.getNetworkCaptureStatus());
        assertFalse(ManifestConfigRebuilder.rebuild(evidence,RerunInputResolver.resolve(evidence)).getConfig().getNetworkEvidenceConfig().isEnabled());
        RerunReport report=RerunDiffExecutor.execute(run,temporary.resolve("off-output"));
        assertEquals(RerunVerdict.IDENTICAL_CORE,report.getVerdict());assertNull(report.getNetworkEvidenceCoverageNote());
        assertEquals(new HashSet<String>(Arrays.asList("schema","verdict","exitCode","runDirectory","outputDirectory","failureReason","failureDetails",
                "inputResolution","coreDivergences","volatileFieldsNoted","codeIdentityNote","originalSourceTreeSha256","rerunSourceTreeSha256",
                "originalAlgorithmContract","rerunAlgorithmContract")),JsonParser.parseString(report.toJson()).getAsJsonObject().keySet());
        assertFalse(report.toMarkdown().contains("网络证据覆盖"));
    }

    @Test void oneMissingLedgerSideIsCoreDivergenceAndCannotBeConstructedAsOnWithoutPath()throws Exception{
        Path on=fixture(false,2000),off=fixture(false,0);
        EvidenceCoreDiffer.DiffResult diff=EvidenceCoreDiffer.compare(RerunEvidenceReader.read(on),RerunEvidenceReader.read(off));
        assertTrue(diff.getCoreDivergences().stream().anyMatch(d->"/networkLedger".equals(d.getPointer())));
        RerunEvidence e=RerunEvidenceReader.read(on);
        assertThrows(IllegalArgumentException.class,()->new RerunEvidence(e.getRunDirectory(),e.getManifestPath(),e.getMetricsPath(),e.getEventsPath(),e.getEventCount(),e.getManifest()));
    }

    private Path fixture(boolean fat,int budget)throws Exception{
        Path study=temporary.resolve("study-"+(serial++)),input=study.resolve("inputs/flow.dax");Files.createDirectories(input.getParent());
        Files.write(input,("<adag><job id=\"p\" runtime=\"1\"><uses file=\"external\" link=\"input\" size=\"0.5\"/>"
                +"<uses file=\"a\" link=\"output\" size=\"5000000\"/></job><job id=\"c\" runtime=\"2\">"
                +"<uses file=\"a\" link=\"input\" size=\"5000000\"/></job><child ref=\"c\"><parent ref=\"p\"/></child></adag>").getBytes(StandardCharsets.UTF_8));
        PlatformProfile.Builder platform=PlatformProfile.builder("network-rerun");int[] ids={7,42};
        for(int i=0;i<2;i++)platform.addHost(new PlatformProfile.HostSpec(10+i*10,2,2000,2048,10000,1000000))
                .addVm(new PlatformProfile.VmSpec(ids[i],1000,1,512,1,10000,"Xen",PlatformProfile.CloudletSchedulerMode.SPACE_SHARED)).pinVmToHost(ids[i],10+i*10);
        if(fat)platform.networkTopology(NetworkTopologySpec.fatTree(4,1));
        SimulationConfig config=SimulationConfig.builder(input.toString(),2).planningAlgorithm(Parameters.PlanningAlgorithm.RANDOM)
                .schedulingAlgorithm(Parameters.SchedulingAlgorithm.STATIC).fileSystem(ReplicaCatalog.FileSystem.LOCAL).randomSeed(1)
                .dataMovementModel(fat?DataMovementModel.fatTreeContentionV1():DataMovementModel.preExecutionTransferDelayWithContentionV1())
                .networkEvidence(budget==0?NetworkEvidenceConfig.off():NetworkEvidenceConfig.fluidGroupLedger(budget)).build();
        Path run=study.resolve("runs/original");ExperimentArtifactWriter.write(new SimulationRunner().run(config,platform.build()),run,"result");return run;
    }
    private static JsonObject read(Path path)throws Exception{return JsonParser.parseString(new String(Files.readAllBytes(path),StandardCharsets.UTF_8)).getAsJsonObject();}
    private static void changeExternalId(Path run,long value)throws Exception{
        Path file=run.resolve("result.network-ledger.json");JsonObject doc=read(file),evidence=doc.getAsJsonObject("evidence");
        for(com.google.gson.JsonElement element:evidence.getAsJsonObject("traceSnapshot").getAsJsonArray("events")){
            JsonObject row=element.getAsJsonObject();if(!row.get("admissionOrdinal").isJsonNull()&&row.get("admissionOrdinal").getAsLong()==1)row.addProperty("transferId",value);
        }
        evidence.getAsJsonArray("bindings").get(0).getAsJsonObject().addProperty("externalTransferId",value);
        NetworkRunEvidence changed=new Gson().fromJson(evidence,NetworkRunEvidence.class);JsonObject valid=NetworkLedgerCodec.document(changed);
        NetworkLedgerCodec.decode(valid.toString());ExperimentArtifactWriter.writeJson(file,valid);rehash(run);
        ExperimentArtifactValidator.validate(run.resolve("result.manifest.json"));
    }
    private static String hash(Path path)throws Exception{
        byte[] digest=java.security.MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path));StringBuilder text=new StringBuilder();
        for(byte value:digest)text.append(String.format(java.util.Locale.ROOT,"%02x",value&0xff));return text.toString();
    }
    private static void rehash(Path run)throws Exception{
        JsonObject manifest=read(run.resolve("result.manifest.json"));Path ledger=run.resolve("result.network-ledger.json");
        for(com.google.gson.JsonElement value:manifest.getAsJsonArray("artifacts"))if("network-ledger".equals(value.getAsJsonObject().get("role").getAsString())){
            value.getAsJsonObject().addProperty("sha256",hash(ledger));value.getAsJsonObject().addProperty("sizeBytes",Files.size(ledger));
        }
        ExperimentArtifactWriter.writeJson(run.resolve("result.manifest.json"),manifest);
    }
}
