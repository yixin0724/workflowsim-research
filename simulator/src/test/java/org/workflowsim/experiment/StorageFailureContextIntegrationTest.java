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
import java.util.List;
import org.cloudbus.cloudsim.Cloudlet;
import org.cloudbus.cloudsim.Log;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.workflowsim.data.DataMovementModel;
import org.workflowsim.data.NetworkEvidenceConfig;
import org.workflowsim.data.v2.DataflowStorageSpec;
import org.workflowsim.data.v2.StorageLifecycleCodec;
import org.workflowsim.failure.FailureModelConfig;
import org.workflowsim.failure.FailureParameters;
import org.workflowsim.platform.PlatformProfile;
import org.workflowsim.utils.DistributionGenerator;
import org.workflowsim.utils.DistributionSpec;
import org.workflowsim.utils.Parameters;
import org.workflowsim.utils.ReplicaCatalog;
import org.workflowsim.utils.SimulationConfig;

/** Necessary final-run joins: observed retries must be possible under the declared failure/STATIC policy. */
class StorageFailureContextIntegrationTest {
    @TempDir Path directory;
    private boolean logging;
    @BeforeEach void quiet(){logging=Log.isDisabled();Log.disable();}
    @AfterEach void restore(){Log.setDisabled(logging);}
    @Test void disabledFailurePolicyCannotCertifyARecordedFailedRetryChain()throws Exception{
        ExperimentArtifactWriter.ExperimentArtifacts out=fixture();JsonObject root=read(out.getManifest()),failure=root.getAsJsonObject("configuration").getAsJsonObject("failureModel");failure.addProperty("generatorMode","FAILURE_NONE");failure.addProperty("maxTotalRetryJobs",0);failure.add("generators",new JsonArray());failure.add("generatorsByVmId",new JsonObject());save(out.getManifest(),root);
        assertThrows(java.io.IOException.class,()->ExperimentArtifactValidator.validate(out.getManifest()));
        convertOff(out);assertThrows(java.io.IOException.class,()->ExperimentArtifactValidator.validate(out.getManifest()));
    }
    @Test void declaredRetryBudgetMustCoverEveryCreatedAttempt()throws Exception{
        ExperimentArtifactWriter.ExperimentArtifacts out=fixture();JsonObject root=read(out.getManifest());long count=root.getAsJsonObject("metrics").get("retryJobCreatedCount").getAsLong();assertTrue(count>=2);root.getAsJsonObject("configuration").getAsJsonObject("failureModel").addProperty("maxTotalRetryJobs",count);save(out.getManifest(),root);assertDoesNotThrow(()->ExperimentArtifactValidator.validate(out.getManifest()));root.getAsJsonObject("configuration").getAsJsonObject("failureModel").addProperty("maxTotalRetryJobs",1);save(out.getManifest(),root);
        assertThrows(java.io.IOException.class,()->ExperimentArtifactValidator.validate(out.getManifest()));
        convertOff(out);assertThrows(java.io.IOException.class,()->ExperimentArtifactValidator.validate(out.getManifest()));
    }
    @Test void staticNoopRetryCannotMigrateFromItsFailedVmEvenWithRepairedHashes()throws Exception{
        ExperimentArtifactWriter.ExperimentArtifacts out=fixture();JsonObject root=read(out.getManifest());int failed=-1,original=-1;for(JsonElement row:root.getAsJsonObject("result").getAsJsonArray("jobs")){JsonObject job=row.getAsJsonObject();if(job.get("status").getAsInt()==Cloudlet.FAILED){failed=job.get("jobId").getAsInt();original=job.get("vmId").getAsInt();break;}}assertTrue(failed>=0);int other=original==7?42:7;
        for(JsonElement row:root.getAsJsonObject("result").getAsJsonArray("jobs"))if(row.getAsJsonObject().get("jobId").getAsInt()==failed)row.getAsJsonObject().addProperty("vmId",other);
        for(JsonElement row:root.getAsJsonObject("result").getAsJsonArray("tasks"))if(row.getAsJsonObject().get("jobId").getAsInt()==failed)row.getAsJsonObject().addProperty("vmId",other);save(out.getManifest(),root);
        JsonObject doc=read(out.getStorageLifecycle());for(JsonElement row:doc.getAsJsonArray("events")){JsonObject e=row.getAsJsonObject(),p=e.getAsJsonObject("payload");if(p.has("jobId")&&p.get("jobId").getAsInt()==failed){if(p.has("vmId"))p.addProperty("vmId",other);if(p.has("destinationVmId"))p.addProperty("destinationVmId",other);}}
        assertDoesNotThrow(()->StorageLifecycleCodec.decodeDocument(doc),"standalone does not declare a scheduling retry policy");save(out.getStorageLifecycle(),doc);
        List<String> changed=new ArrayList<>();for(String line:Files.readAllLines(out.getEvents(),StandardCharsets.UTF_8)){JsonObject e=JsonParser.parseString(line).getAsJsonObject();if(e.has("jobId")&&!e.get("jobId").isJsonNull()&&e.get("jobId").getAsInt()==failed){e.addProperty("vmId",other);JsonObject attrs=e.getAsJsonObject("attributes");for(String key:new String[]{"vmId","selectedVmId","destinationVmId"})if(attrs.has(key))attrs.addProperty(key,other);}changed.add(e.toString());}Files.write(out.getEvents(),changed,StandardCharsets.UTF_8);rehash(out,"events",out.getEvents());rehash(out,"storage-lifecycle",out.getStorageLifecycle());
        assertThrows(java.io.IOException.class,()->ExperimentArtifactValidator.validate(out.getManifest()));
        convertOff(out);assertThrows(java.io.IOException.class,()->ExperimentArtifactValidator.validate(out.getManifest()));
    }
    private static void convertOff(ExperimentArtifactWriter.ExperimentArtifacts out)throws Exception{JsonObject root=read(out.getManifest());root.getAsJsonObject("configuration").remove("networkEvidence");JsonArray keep=new JsonArray();for(JsonElement row:root.getAsJsonArray("artifacts"))if(!row.getAsJsonObject().get("role").getAsString().equals("storage-lifecycle"))keep.add(row);root.add("artifacts",keep);save(out.getManifest(),root);}
    private ExperimentArtifactWriter.ExperimentArtifacts fixture()throws Exception{
        Path input=directory.resolve("retry.dax");Files.write(input,"<adag><job id=\"t\" runtime=\"1\"><uses file=\"z\" link=\"output\" size=\"0\"/></job></adag>".getBytes(StandardCharsets.UTF_8));FailureModelConfig failure=FailureModelConfig.builder().clusteringAlgorithm(FailureParameters.FTCluteringAlgorithm.FTCLUSTERING_NOOP).monitorMode(FailureParameters.FTCMonitor.MONITOR_NONE).generatorMode(FailureParameters.FTCFailure.FAILURE_ALL).generatorSpecs(new DistributionSpec[][]{{DistributionSpec.of(DistributionGenerator.DistributionFamily.WEIBULL,2,1)}}).maxTotalRetryJobs(64).build();PlatformProfile.Builder p=PlatformProfile.builder("failure-context");int[] ids={7,42};for(int i=0;i<2;i++){int host=10+i*10;p.addHost(new PlatformProfile.HostSpec(host,2,2000,4096,10000,1000000)).addVm(new PlatformProfile.VmSpec(ids[i],1000,1,512,10,10000,"Xen",PlatformProfile.CloudletSchedulerMode.SPACE_SHARED)).pinVmToHost(ids[i],host);}p.addHost(new PlatformProfile.HostSpec(30,2,2000,4096,10000,1000000));p.sourceStorage(DataflowStorageSpec.of(30,1,.5,2));PlatformProfile platform=p.build();
        // Bounded deterministic fixture selection exercises at least two retries; this is not a study ranking search.
        for(long seed=1;seed<=64;seed++){SimulationConfig c=SimulationConfig.builder(input.toString(),2).planningAlgorithm(Parameters.PlanningAlgorithm.RANDOM).schedulingAlgorithm(Parameters.SchedulingAlgorithm.STATIC).fileSystem(ReplicaCatalog.FileSystem.SHARED).randomSeed(seed).dataMovementModel(DataMovementModel.coherentStorageDataflowV3()).networkEvidence(NetworkEvidenceConfig.storageLifecycleV3(2000)).failureModel(failure).build();SimulationReport report=new SimulationRunner().run(c,platform);if(report.getMetrics().getRetryJobCreatedCount()>=2){ExperimentArtifactWriter.ExperimentArtifacts out=ExperimentArtifactWriter.write(report,directory.resolve("bundle"),"result");ExperimentArtifactValidator.validate(out.getManifest());return out;}}
        throw new AssertionError("fixed seed domain did not exercise the required retry chain");
    }
    private static JsonObject read(Path p)throws Exception{return JsonParser.parseString(new String(Files.readAllBytes(p),StandardCharsets.UTF_8)).getAsJsonObject();}
    private static void save(Path p,JsonObject v)throws Exception{Files.write(p,v.toString().getBytes(StandardCharsets.UTF_8));}
    private static void rehash(ExperimentArtifactWriter.ExperimentArtifacts out,String role,Path path)throws Exception{byte[] bytes=Files.readAllBytes(path);StringBuilder sha=new StringBuilder();for(byte b:MessageDigest.getInstance("SHA-256").digest(bytes))sha.append(String.format("%02x",b&255));JsonObject root=read(out.getManifest());for(JsonElement row:root.getAsJsonArray("artifacts")){JsonObject entry=row.getAsJsonObject();if(entry.get("role").getAsString().equals(role)){entry.addProperty("sha256",sha.toString());entry.addProperty("sizeBytes",bytes.length);}}save(out.getManifest(),root);}
}
