package org.workflowsim;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.Gson;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.cloudbus.cloudsim.Log;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.workflowsim.data.DataMovementModel;
import org.workflowsim.data.NetworkEvidenceConfig;
import org.workflowsim.data.NetworkFlowBinding;
import org.workflowsim.data.NetworkRunEvidence;
import org.workflowsim.data.TransferContentionEngine;
import org.workflowsim.data.TransferTraceEvent;
import org.workflowsim.data.TransferTraceSnapshot;
import org.workflowsim.platform.PlatformFactory;
import org.workflowsim.platform.PlatformProfile;
import org.workflowsim.utils.Parameters;
import org.workflowsim.utils.ReplicaCatalog;
import org.workflowsim.utils.SimulationConfig;
import org.workflowsim.utils.SimulationSession;

/** NF002A runtime capture lifecycle without driving new CloudSim physics. */
class NetworkEvidenceDatacenterTest {
    private boolean loggingWasDisabled;
    @BeforeEach void silenceLogging() { loggingWasDisabled = Log.isDisabled(); Log.disable(); }
    @AfterEach void restoreLogging() { Log.setDisabled(loggingWasDisabled); }

    @Test
    void offCaptureDoesNotCreateAnEngineAndTheDefaultEngineRemainsHistoryFree() throws Exception {
        try (SimulationSession session = SimulationSession.open(config(NetworkEvidenceConfig.off(), 1))) {
            WorkflowDatacenter datacenter = datacenter(session, singleVmPlatform());
            assertSame(NetworkEvidenceConfig.off(), datacenter.getNetworkEvidenceConfig());
            assertNull(datacenter.captureNetworkEvidence());
            assertNull(existingEngine(datacenter));
            TransferContentionEngine engine = datacenter.getTransferContentionEngine();
            engine.addTransfer(7L, 100.0, "SOURCE", "VM:7", 100.0, 0.0);
            datacenter.recordNetworkFlowBinding(7L, job(91, 7, 3), null,
                    NetworkFlowBinding.GroupKind.EXTERNAL_GROUP_V1, "SOURCE", "VM:7", Arrays.asList("SOURCE", "VM:7"));
            assertEquals(TransferTraceSnapshot.Status.DISABLED, engine.getTraceSnapshot().getStatus());
            assertTrue(engine.getTraceSnapshot().getEvents().isEmpty());
            assertNull(datacenter.captureNetworkEvidence());
            assertNull(candidateBindings(datacenter), "OFF must allocate no binding history");
            datacenter.recordNetworkInputDemand(new FileItem("ignored-off",.5),true);
            Field demand=WorkflowDatacenter.class.getDeclaredField("inputDemandTracker");demand.setAccessible(true);
            assertNull(demand.get(datacenter),"OFF must allocate no input-demand tracker");
        }
    }

    @Test
    void enabledEmptyCaptureDoesNotInvokeTheLazyEngineGetterOrFabricateAnAdvance() throws Exception {
        NetworkEvidenceConfig option = NetworkEvidenceConfig.fluidGroupLedger(7);
        try (SimulationSession session = SimulationSession.open(config(option, 1))) {
            WorkflowDatacenter datacenter = datacenter(session, singleVmPlatform());
            datacenter.setNetworkEvidenceConfig(option);
            NetworkRunEvidence frozen = datacenter.captureNetworkEvidence();
            assertNull(existingEngine(datacenter));
            assertFalse(frozen.isEngineCreated());
            assertSame(option, frozen.getConfig());
            assertEquals(TransferTraceSnapshot.Status.COMPLETE, frozen.getTraceSnapshot().getStatus());
            assertEquals(0.0, frozen.getTraceSnapshot().getEngineTime(), 0.0);
            assertTrue(frozen.getTraceSnapshot().getEvents().isEmpty());
            assertTrue(frozen.getBindings().isEmpty());
            datacenter.getTransferContentionEngine();
            assertTrue(datacenter.captureNetworkEvidence().isEngineCreated());
            assertFalse(frozen.isEngineCreated(), "A prior empty snapshot cannot become live");
        }
    }

    @Test
    void activeCaptureCopiesJobMetadataAndResourcesWithoutAdvancingOrRetainingLiveState() throws Exception {
        NetworkEvidenceConfig option = NetworkEvidenceConfig.fluidGroupLedger(100);
        try (SimulationSession session = SimulationSession.open(config(option, 1))) {
            WorkflowDatacenter datacenter = datacenter(session, singleVmPlatform());
            datacenter.setNetworkEvidenceConfig(option);
            TransferContentionEngine engine = datacenter.getTransferContentionEngine();
            engine.setEndpointCapacity("VM:7", 100.0);
            List<String> resources = new ArrayList<String>(Arrays.asList("VM:7", "VM:42", "VM:7"));
            Job attempt = job(91, 42, 3, 6);
            engine.addTransfer(9001L, 1000.0, resources, 100.0, 0.0);
            datacenter.recordNetworkFlowBinding(9001L, attempt, 11,
                    NetworkFlowBinding.GroupKind.PARENT_GROUP_V1, "VM:7", "VM:42", resources);
            Double prediction = engine.advance(0.0).getNextCompletionTime();
            NetworkRunEvidence frozen = datacenter.captureNetworkEvidence();
            String original = new Gson().toJson(frozen);
            attempt.setVmId(7); attempt.getTaskList().clear(); resources.clear();
            ReplicaCatalog.addFileToStorage("unrelated-later-replica", "99");
            assertEquals(original, new Gson().toJson(datacenter.captureNetworkEvidence()));
            assertEquals(91, frozen.getBindings().get(0).getJobId());
            assertEquals(Arrays.asList(3, 6), frozen.getBindings().get(0).getTaskIds());
            assertEquals(Arrays.asList("VM:7", "VM:42", "VM:7"), frozen.getBindings().get(0).getOccupiedResources());
            engine.advance(2.0);
            NetworkRunEvidence later = datacenter.captureNetworkEvidence();
            assertEquals(2.0, later.getTraceSnapshot().getEngineTime(), 0.0);
            assertEquals(prediction, engine.advance(2.0).getNextCompletionTime());
            assertEquals(original, new Gson().toJson(frozen));
            assertEquals(0.0, frozen.getTraceSnapshot().getEngineTime(), 0.0);
        }
    }

    @Test
    void optionIsRequiredAndCannotBeInstalledOrChangedAfterEngineCreation() throws Exception {
        try (SimulationSession session = SimulationSession.open(config(NetworkEvidenceConfig.off(), 1))) {
            WorkflowDatacenter datacenter = datacenter(session, singleVmPlatform());
            assertThrows(IllegalArgumentException.class, () -> datacenter.setNetworkEvidenceConfig(null));
            NetworkEvidenceConfig option = NetworkEvidenceConfig.fluidGroupLedger(10);
            datacenter.setNetworkEvidenceConfig(option);
            assertSame(option, datacenter.getNetworkEvidenceConfig());
            datacenter.getTransferContentionEngine();
            assertThrows(IllegalStateException.class, () -> datacenter.setNetworkEvidenceConfig(NetworkEvidenceConfig.off()));
            assertThrows(IllegalStateException.class, () -> datacenter.setNetworkEvidenceConfig(NetworkEvidenceConfig.fluidGroupLedger(20)));
            assertSame(option, datacenter.getNetworkEvidenceConfig());
        }
    }

    @Test
    void candidateMetadataIsBoundedEvenWhenManyAdmissionsOutliveTheRetainedTracePrefix() throws Exception {
        NetworkEvidenceConfig option = NetworkEvidenceConfig.fluidGroupLedger(3);
        try (SimulationSession session = SimulationSession.open(config(option, 1))) {
            WorkflowDatacenter datacenter = datacenter(session, singleVmPlatform());
            datacenter.setNetworkEvidenceConfig(option);
            TransferContentionEngine engine = datacenter.getTransferContentionEngine();
            engine.setEndpointCapacity("VM:7", 10.0);
            List<String> resources = Arrays.asList("VM:7", "VM:42");
            for (int i = 0; i < 30; i++) {
                long externalId = 10_000L + i;
                engine.addTransfer(externalId, 1.0, resources, 10.0, i);
                datacenter.recordNetworkFlowBinding(externalId, job(91 + i, 42, 1 + i), 11,
                        NetworkFlowBinding.GroupKind.PARENT_GROUP_V1, "VM:7", "VM:42", resources);
                engine.advance(i + 0.25);
                assertTrue(candidateBindings(datacenter).size() <= 3);
            }
            assertEquals(3, candidateBindings(datacenter).size(), "No full admission history around a bounded trace");
            NetworkRunEvidence frozen = datacenter.captureNetworkEvidence();
            assertEquals(TransferTraceSnapshot.Status.TRUNCATED, frozen.getTraceSnapshot().getStatus());
            assertEquals(3, frozen.getTraceSnapshot().getEvents().size());
            assertTrue(frozen.getTraceSnapshot().getDroppedCount() > 0L);
            assertEquals(1, frozen.getBindings().size(), "Only the first START survived this record prefix");
            assertEquals(10_000L, frozen.getBindings().get(0).getExternalTransferId());
            assertEquals(1L, frozen.getBindings().get(0).getAdmissionOrdinal());
            for (Object candidate : candidateBindings(datacenter).values()) {
                assertTrue(candidate instanceof NetworkFlowBinding);
                assertTrue(((NetworkFlowBinding) candidate).getAdmissionOrdinal() <= 3L);
            }
        }
    }

    @Test
    void duplicateBindingCannotReplaceTheFirstAdmissionAttribution() throws Exception {
        NetworkEvidenceConfig option = NetworkEvidenceConfig.fluidGroupLedger(10);
        try (SimulationSession session = SimulationSession.open(config(option, 1))) {
            WorkflowDatacenter datacenter = datacenter(session, singleVmPlatform());
            datacenter.setNetworkEvidenceConfig(option);
            List<String> resources = Arrays.asList("SOURCE", "VM:7");
            datacenter.getTransferContentionEngine().addTransfer(77L, 100.0, resources, 100.0, 0.0);
            datacenter.recordNetworkFlowBinding(77L, job(91, 7, 3), null,
                    NetworkFlowBinding.GroupKind.EXTERNAL_GROUP_V1, "SOURCE", "VM:7", resources);
            assertThrows(IllegalStateException.class, () -> datacenter.recordNetworkFlowBinding(77L,
                    job(92, 7, 4), null, NetworkFlowBinding.GroupKind.EXTERNAL_GROUP_V1,
                    "SOURCE", "VM:7", resources));
            NetworkFlowBinding preserved = datacenter.captureNetworkEvidence().getBindings().get(0);
            assertEquals(91, preserved.getJobId());
            assertEquals(Collections.singletonList(3), preserved.getTaskIds());
        }
    }

    @Test
    void unsuccessfulAdmissionCannotCreateABinding() throws Exception {
        NetworkEvidenceConfig option = NetworkEvidenceConfig.fluidGroupLedger(10);
        try (SimulationSession session = SimulationSession.open(config(option, 1))) {
            WorkflowDatacenter datacenter = datacenter(session, singleVmPlatform());
            datacenter.setNetworkEvidenceConfig(option);
            TransferContentionEngine engine = datacenter.getTransferContentionEngine();
            assertThrows(IllegalArgumentException.class, () -> engine.addTransfer(7L, 0.0, "SOURCE", "VM:7", 1.0, 0.0));
            assertThrows(IllegalStateException.class, () -> datacenter.recordNetworkFlowBinding(7L,
                    job(91, 7, 3), null, NetworkFlowBinding.GroupKind.EXTERNAL_GROUP_V1,
                    "SOURCE", "VM:7", Arrays.asList("SOURCE", "VM:7")));
            assertNull(candidateBindings(datacenter));
            NetworkRunEvidence frozen = datacenter.captureNetworkEvidence();
            assertTrue(frozen.getBindings().isEmpty());
            assertTrue(frozen.getTraceSnapshot().getEvents().isEmpty());
            assertEquals(TransferTraceSnapshot.Status.COMPLETE, frozen.getTraceSnapshot().getStatus());
        }
    }

    @Test
    void existingRuntimeStillChargesTheProducerEvenWhenNominalEstimateUsesAFasterReplica() throws Exception {
        NetworkEvidenceConfig option = NetworkEvidenceConfig.fluidGroupLedger(1000);
        PlatformProfile platform = threeVmPlatform();
        try (SimulationSession session = SimulationSession.open(config(option, 3))) {
            WorkflowDatacenter datacenter = datacenter(session, platform);
            datacenter.setNetworkEvidenceConfig(option);
            WorkflowEngine workflow = new WorkflowEngine("network-evidence-engine");
            List<CondorVM> vms = PlatformFactory.createVms(platform, workflow.getSchedulerId(0));
            workflow.submitVmList(vms, 0);
            workflow.bindSchedulerDatacenter(datacenter.getId(), 0);
            for (CondorVM vm : vms) assertTrue(datacenter.getVmAllocationPolicy().allocateHostForVm(vm));
            ReplicaCatalog.addFileToStorage("replicated.dat", "7");
            ReplicaCatalog.addFileToStorage("replicated.dat", "42");
            Job producer = job(88, 7, 2);
            producer.setUserId(workflow.getSchedulerId(0));
            FileItem output = new FileItem("replicated.dat", 5_000_000.0);
            output.setType(Parameters.FileType.OUTPUT); producer.addFile(output);
            Job consumer = job(91, 99, 5);
            consumer.setUserId(workflow.getSchedulerId(0));
            FileItem input = new FileItem("replicated.dat", 5_000_000.0);
            input.setType(Parameters.FileType.INPUT); consumer.addFile(input); consumer.addParent(producer);
            Method start = WorkflowEngine.class.getDeclaredMethod("startContentionStageIn", Job.class);
            start.setAccessible(true);
            assertEquals(Boolean.TRUE, start.invoke(workflow, consumer));
            NetworkRunEvidence frozen = datacenter.captureNetworkEvidence();
            assertEquals(1, frozen.getBindings().size());
            NetworkFlowBinding binding = frozen.getBindings().get(0);
            assertEquals(91, binding.getJobId());
            assertEquals(Collections.singletonList(5), binding.getTaskIds());
            assertEquals(Integer.valueOf(88), binding.getParentJobId());
            assertEquals("VM:7", binding.getSourceEndpoint());
            assertEquals("VM:99", binding.getDestinationEndpoint());
            assertEquals(Arrays.asList("VM:7", "VM:99"), binding.getOccupiedResources());
            assertEquals(NetworkFlowBinding.SourceScope.MODELED_CONSTRAINED_SOURCE, binding.getSourceScope());
            TransferTraceEvent admission = null;
            for (TransferTraceEvent event : frozen.getTraceSnapshot().getEvents()) {
                if (event.getType() == TransferTraceEvent.Type.START) admission = event;
            }
            assertTrue(admission != null);
            assertEquals(10_000_000.0, admission.getStart().getNominalRateBytesPerSecond(), 0.0,
                    "V1 estimator still chooses the faster VM:42 replica");
            assertEquals(1_000_000.0, admission.getStart().getInitialRateBytesPerSecond(), 0.0,
                    "V1 constrained path still charges producer VM:7; NF002A must not repair it");
            assertEquals(binding.getOccupiedResources(), admission.getStart().getOccupiedResources());
        }
    }

    private static SimulationConfig config(NetworkEvidenceConfig option, int vmCount) {
        return SimulationConfig.builder("not-parsed.dax", vmCount)
                .planningAlgorithm(Parameters.PlanningAlgorithm.RANDOM)
                .schedulingAlgorithm(Parameters.SchedulingAlgorithm.STATIC)
                .fileSystem(ReplicaCatalog.FileSystem.LOCAL)
                .dataMovementModel(DataMovementModel.preExecutionTransferDelayWithContentionV1())
                .networkEvidence(option).build();
    }

    private static WorkflowDatacenter datacenter(SimulationSession session, PlatformProfile platform) throws Exception {
        session.initializeCloudSim(1, Calendar.getInstance(), false);
        WorkflowDatacenter result = PlatformFactory.createDatacenter("network-evidence-datacenter", platform);
        result.setDataMovementModel(session.getConfig().getDataMovementModel());
        return result;
    }
    private static Job job(int jobId, int vmId, int... taskIds) {
        Job result = new Job(jobId, 1000L); result.setVmId(vmId);
        result.setClassType(Parameters.ClassType.COMPUTE.value);
        for (int taskId : taskIds) result.getTaskList().add(new Task(taskId, 1000L));
        return result;
    }
    private static TransferContentionEngine existingEngine(WorkflowDatacenter datacenter) throws Exception {
        Field field = WorkflowDatacenter.class.getDeclaredField("transferContentionEngine"); field.setAccessible(true);
        return (TransferContentionEngine) field.get(datacenter);
    }
    private static Map<?, ?> candidateBindings(WorkflowDatacenter datacenter) throws Exception {
        Field field = WorkflowDatacenter.class.getDeclaredField("networkFlowBindings"); field.setAccessible(true);
        return (Map<?, ?>) field.get(datacenter);
    }
    private static PlatformProfile singleVmPlatform() {
        return PlatformProfile.builder("network-evidence-unit-platform")
                .addHost(new PlatformProfile.HostSpec(10, 2, 2000.0, 2048, 10_000L, 1_000_000L))
                .addVm(new PlatformProfile.VmSpec(7, 1000.0, 1, 512, 1L, 10_000L, "Xen",
                        PlatformProfile.CloudletSchedulerMode.SPACE_SHARED)).pinVmToHost(7, 10).build();
    }
    private static PlatformProfile threeVmPlatform() {
        PlatformProfile.Builder builder = PlatformProfile.builder("network-evidence-replica-platform");
        int[] vmIds = {7, 42, 99};
        for (int i = 0; i < vmIds.length; i++) {
            int hostId = 10 + i * 10;
            builder.addHost(new PlatformProfile.HostSpec(hostId, 2, 2000.0, 2048, 10_000L, 1_000_000L));
            builder.addVm(new PlatformProfile.VmSpec(vmIds[i], 1000.0, 1, 512, i == 0 ? 1L : 10L,
                    10_000L, "Xen", PlatformProfile.CloudletSchedulerMode.SPACE_SHARED));
            builder.pinVmToHost(vmIds[i], hostId);
        }
        return builder.build();
    }
}
