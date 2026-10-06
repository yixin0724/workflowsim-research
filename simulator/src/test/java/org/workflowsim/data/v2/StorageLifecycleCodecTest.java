package org.workflowsim.data.v2;

import static org.junit.jupiter.api.Assertions.*;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;
import org.workflowsim.FileItem;
import org.workflowsim.Task;
import org.workflowsim.data.NetworkLedgerCodec;
import org.workflowsim.network.FatTreeTopology;
import org.workflowsim.network.NetworkTopologySpec;
import org.workflowsim.utils.Parameters.FileType;

/** Real captures exercise the writer; literal paths and adversarial documents are independent oracles. */
class StorageLifecycleCodecTest {
    private static final String LITERAL = "{"
            + "\"schema\":\"workflowsim-storage-lifecycle-v3\",\"modelKind\":\"COHERENT_STORAGE_DATAFLOW_V3\","
            + "\"recording\":{\"mode\":\"FILE_STORAGE_LIFECYCLE_V3\",\"maxTraceRecords\":64},"
            + "\"certificateScope\":\"STORAGE_LIFECYCLE_NOT_FLUID_SERVICE_ACCOUNTING_V3\","
            + "\"policies\":{\"fileIdentity\":\"SCOPED_RESOLVED_WRITE_ONCE_FILES_V2\","
            + "\"release\":\"DEPENDENCY_READY_AT_OBSERVATION_V2\",\"visibility\":\"PER_FILE_SETTLEMENT_OBSERVATION_V2\","
            + "\"selection\":\"VISIBLE_LOCAL_OR_MAX_ISOLATED_RATE_STABLE_LOCATION_V2\","
            + "\"sourceAccess\":\"BOUNDED_STORE_READ_WRITE_NIC_HOST_ATTACHMENT_V3\",\"sharing\":\"SHARED_MAX_MIN\","
            + "\"inputAccess\":\"LOCAL_VISIBLE_REPLICA_V3\",\"outputCommit\":\"ALL_SUCCESSFUL_OUTPUTS_TO_SOURCE_V3\"},"
            + "\"capture\":{\"status\":\"COMPLETE\",\"observedThrough\":0,\"retainedRecords\":4,\"droppedRecords\":0},"
            + "\"filePlan\":{\"contractVersion\":\"SCOPED_RESOLVED_WRITE_ONCE_FILES_V2\",\"tasks\":[{"
            + "\"taskId\":1,\"workflowInputIndex\":0,\"parents\":[],\"inputs\":[{\"fileId\":{\"workflowInputIndex\":0,\"name\":\"x\"},\"referenceCount\":1}],\"outputs\":[]}],"
            + "\"files\":[{\"fileId\":{\"workflowInputIndex\":0,\"name\":\"x\"},\"bytes\":10,\"producerTaskId\":null}]},"
            + "\"fabric\":{\"locations\":[{\"kind\":\"VM\",\"vmId\":7,\"sourceId\":null},{\"kind\":\"SOURCE\",\"vmId\":null,\"sourceId\":\"source\"}],"
            + "\"resources\":[{\"key\":\"VM:7\",\"capacityBytesPerSecond\":100},{\"key\":\"STORE:source:READ\",\"capacityBytesPerSecond\":10},"
            + "{\"key\":\"STORE:source:WRITE\",\"capacityBytesPerSecond\":20},{\"key\":\"STORE:source:NIC\",\"capacityBytesPerSecond\":100}],"
            + "\"vmHostAssignments\":[{\"vmId\":7,\"hostId\":50}],\"topology\":null,"
            + "\"sourceStorage\":{\"attachmentHostId\":99,\"readBandwidthMbPerSecond\":0.00001,\"writeBandwidthMbPerSecond\":0.00002,\"networkBandwidthMbPerSecond\":0.0001}},"
            + "\"events\":[{\"sequence\":1,\"observedTime\":0,\"type\":\"EXTERNAL_SEEDED\",\"payload\":{\"fileId\":{\"workflowInputIndex\":0,\"name\":\"x\"},\"location\":{\"kind\":\"SOURCE\",\"vmId\":null,\"sourceId\":\"source\"}}},"
            + "{\"sequence\":2,\"observedTime\":0,\"type\":\"JOB_INPUT_REQUESTED\",\"payload\":{\"jobId\":0,\"taskIds\":[1],\"destinationVmId\":7}},"
            + "{\"sequence\":3,\"observedTime\":0,\"type\":\"COPY_ADMITTED\",\"payload\":{\"copyOrdinal\":1,\"fileId\":{\"workflowInputIndex\":0,\"name\":\"x\"},\"bytes\":10,"
            + "\"sourceReplica\":{\"fileId\":{\"workflowInputIndex\":0,\"name\":\"x\"},\"location\":{\"kind\":\"SOURCE\",\"vmId\":null,\"sourceId\":\"source\"},\"visibleAt\":0,\"acquisition\":\"EXTERNAL_SEED\","
            + "\"origin\":{\"producerTaskId\":null,\"jobAttemptId\":null,\"location\":{\"kind\":\"SOURCE\",\"vmId\":null,\"sourceId\":\"source\"},\"observedAt\":0},\"copiedFrom\":null,\"copyOrdinal\":null},"
            + "\"destination\":{\"kind\":\"VM\",\"vmId\":7,\"sourceId\":null},\"purpose\":\"INPUT\",\"ownerJobId\":0,"
            + "\"resources\":[\"STORE:source:READ\",\"STORE:source:NIC\",\"VM:7\"],\"standaloneRate\":10,\"isolatedSeconds\":1}},"
            + "{\"sequence\":4,\"observedTime\":0,\"type\":\"INPUT_RESOLVED\",\"payload\":{\"jobId\":0,\"fileId\":{\"workflowInputIndex\":0,\"name\":\"x\"},\"referenceCount\":1,\"resolution\":\"NEW_COPY\",\"copyOrdinal\":1,\"source\":{\"kind\":\"SOURCE\",\"vmId\":null,\"sourceId\":\"source\"}}}]}";

    @Test void runtimeMatrixRoundTripsInputPoliciesIndependentlyOfSharingAndTopology() {
        for (boolean storeInputs : new boolean[]{false, true}) for (boolean shared : new boolean[]{false, true})
            for (boolean tree : new boolean[]{false, true}) {
                Task p = task(1, in("external", 100), out("f", 100), out("unused", 50), out("zero", 0));
                Task c = task(2, in("f", 100), in("f", 100), in("zero", 0)); edge(p, c);
                CoherentDataflowRuntime r = runtime(shared, storeInputs, 2000, plan(p, c), fabric(tree, 10, 100, 100, 100, 20, 10, 40));
                r.requestJob(10, one(1), 7, 0); drainCopies(r);
                double start = r.getCurrentTime(); finishReady(r, 10, 1, 7, start, start + 1);
                assertFalse(r.isQuiescent(), "successful CPU completion does not drain its outputs");
                r.requestJob(20, one(2), 42, r.getCurrentTime()); drainCopies(r);
                start = r.getCurrentTime(); finishReady(r, 20, 2, 42, start, start + 1);
                String encoded = StorageLifecycleCodec.encode(r.captureStorageEvidence());
                StorageLifecycleCodec.Decoded decoded = StorageLifecycleCodec.decode(encoded);
                assertEquals(org.workflowsim.data.NetworkLedgerCodec.parseDocument(encoded).toString(),
                        org.workflowsim.data.NetworkLedgerCodec.parseDocument(StorageLifecycleCodec.encode(decoded.getEvidence())).toString());
                assertEquals(storeInputs, decoded.getEvidence().isStoreBackedInputs());
                assertEquals(shared, decoded.getEvidence().getCapture().isShared());
                assertEquals(2, decoded.getRequestedJobCount()); assertEquals(2, decoded.getCompletedJobCount());
                assertEquals(4, decoded.getCopyCount()); assertEquals(4, decoded.getCompletedCopyCount());
                assertEquals(0, decoded.getActiveCopyCount()); assertEquals(0, decoded.getPendingOutputFileCount());
                assertEquals(0, decoded.getWaitingStoreInputCount()); assertTrue(decoded.isQuiescent());
                assertEquals(new TreeSet<>(Arrays.asList(1, 2)), decoded.getSuccessfullyCompletedTaskIds());
                JsonObject root = decoded.getDocument();
                assertEquals(9, root.size()); assertEquals(8, root.getAsJsonObject("policies").size());
                assertEquals(5, root.getAsJsonObject("fabric").size());
                assertEquals("storage-lifecycle", StorageLifecycleCodec.ARTIFACT_ROLE);
                assertEquals(storeInputs ? "COMMITTED_STORE_THEN_VM_READ_CACHE_V3" : "LOCAL_VISIBLE_REPLICA_V3",
                        root.getAsJsonObject("policies").get("inputAccess").getAsString());
                assertEquals(shared ? "SHARED_MAX_MIN" : "ISOLATED_PATH_BOTTLENECK",
                        root.getAsJsonObject("policies").get("sharing").getAsString());
                for (JsonObject admission : payloads(root, "COPY_ADMITTED")) {
                    assertFalse(admission.has("destinationVmId"));
                    assertTrue(admission.has("ownerJobId")); assertTrue(admission.has("purpose"));
                }
            }
    }

    @Test void boundedSourceAndWritebackHaveLiteralDirectedPathsAndActualOwnerProvenance() {
        Task t = task(1, in("input", 40), out("output", 40));
        CoherentDataflowRuntime r = runtime(true, true, 1000, plan(t), fabric(true, 10, 100, 100, 100, 30, 10, 100));
        r.requestJob(8, one(1), 42, 0); drainCopies(r);
        double at = r.getCurrentTime(); finishReady(r, 8, 1, 42, at, at + 1);
        JsonObject root = StorageLifecycleCodec.document(r.captureStorageEvidence());
        JsonObject input = payload(root, "COPY_ADMITTED", 0), output = payload(root, "COPY_ADMITTED", 1);
        assertEquals(Arrays.asList("STORE:source:READ", "STORE:source:NIC", "LINK:ACC:10->EDGE:0:0",
                "LINK:EDGE:0:0->AGG:0:0", "LINK:AGG:0:0->CORE:0", "LINK:CORE:0->AGG:1:0",
                "LINK:AGG:1:0->EDGE:1:1", "LINK:EDGE:1:1->ACC:20", "VM:42"), strings(input.getAsJsonArray("resources")));
        assertEquals(Arrays.asList("VM:42", "LINK:ACC:20->EDGE:1:1", "LINK:EDGE:1:1->AGG:1:1",
                "LINK:AGG:1:1->CORE:2", "LINK:CORE:2->AGG:0:1", "LINK:AGG:0:1->EDGE:0:0",
                "LINK:EDGE:0:0->ACC:10", "STORE:source:NIC", "STORE:source:WRITE"), strings(output.getAsJsonArray("resources")));
        assertEquals(20, input.get("standaloneRate").getAsDouble(), 0);
        assertEquals(10, output.get("standaloneRate").getAsDouble(), 0);
        assertEquals("OUTPUT", output.get("purpose").getAsString()); assertEquals(8, output.get("ownerJobId").getAsInt());
        assertEquals(source(), output.get("destination"));
        assertEquals(vm(42), output.getAsJsonObject("sourceReplica").get("location"));
        assertEquals(8, output.getAsJsonObject("sourceReplica").getAsJsonObject("origin").get("jobAttemptId").getAsInt());
        assertEquals(1, StorageLifecycleCodec.decodeDocument(root).getPendingOutputFileCount());
        drainCopies(r); assertTrue(StorageLifecycleCodec.decode(StorageLifecycleCodec.encode(r.captureStorageEvidence())).isQuiescent());
    }

    @Test void sameHostAndEndpointOnlyCopiesStillChargeVmDiskAndNic() {
        for (boolean tree : new boolean[]{false, true}) {
            CoherentDataflowRuntime r = runtime(true, false, 1000, plan(task(1, in("i", 40), out("o", 40))),
                    fabric(tree, 10, 100, 100, 100, 30, 20, 10));
            r.requestJob(1, one(1), 7, 0); drainCopies(r);
            double at = r.getCurrentTime(); finishReady(r, 1, 1, 7, at, at + 1);
            JsonObject root = StorageLifecycleCodec.document(r.captureStorageEvidence());
            assertEquals(Arrays.asList("STORE:source:READ", "STORE:source:NIC", "VM:7"), strings(payload(root, "COPY_ADMITTED", 0).getAsJsonArray("resources")));
            assertEquals(Arrays.asList("VM:7", "STORE:source:NIC", "STORE:source:WRITE"), strings(payload(root, "COPY_ADMITTED", 1).getAsJsonArray("resources")));
            assertEquals(10, payload(root, "COPY_ADMITTED", 1).get("standaloneRate").getAsDouble(), 0);
            JsonObject wrong = root.deepCopy(); payload(wrong, "COPY_ADMITTED", 0).add("resources", arrayOf("VM:7")); bad(wrong);
            wrong = root.deepCopy(); payload(wrong, "COPY_ADMITTED", 1).add("resources", arrayOf("VM:7", "STORE:source:WRITE")); bad(wrong);
        }
    }

    @Test void runningPrefixesExposeDistinctStoreWaitAndOutputObligations() {
        CoherentDataflowRuntime r = waiting(false);
        StorageLifecycleCodec.Decoded before = StorageLifecycleCodec.decode(StorageLifecycleCodec.encode(r.captureStorageEvidence()));
        assertEquals(2, before.getRequestedJobCount()); assertEquals(1, before.getCompletedJobCount());
        assertEquals(1, before.getPendingOutputFileCount()); assertEquals(1, before.getWaitingStoreInputCount());
        assertEquals(1, before.getActiveCopyCount()); assertFalse(before.isQuiescent());
        r.advance(11);
        StorageLifecycleCodec.Decoded committed = StorageLifecycleCodec.decode(StorageLifecycleCodec.encode(r.captureStorageEvidence()));
        assertEquals(0, committed.getPendingOutputFileCount()); assertEquals(0, committed.getWaitingStoreInputCount());
        assertEquals(1, committed.getActiveCopyCount()); assertFalse(committed.isQuiescent());
        assertTrue(r.drainReadyJobIds().isEmpty(), "SOURCE settlement is not a VM arrival");
        r.advance(16); finishReady(r, 20, 2, 42, 16, 17);
        assertTrue(StorageLifecycleCodec.decode(StorageLifecycleCodec.encode(r.captureStorageEvidence())).isQuiescent());
    }

    @Test void sharedStoreGatePrecedesEvenProducerLocalCacheHit() {
        CoherentDataflowRuntime r = waiting(true);
        JsonObject waiting = StorageLifecycleCodec.document(r.captureStorageEvidence());
        assertEquals(1, payloads(waiting, "INPUT_WAITING_FOR_STORE").size());
        assertEquals(0, payloads(waiting, "INPUT_RESOLVED").size());
        r.advance(11); JsonObject committed = StorageLifecycleCodec.document(r.captureStorageEvidence());
        assertEquals("LOCAL", payload(committed, "INPUT_RESOLVED", 0).get("resolution").getAsString());
        assertEquals(1, StorageLifecycleCodec.decodeDocument(committed).getCopyCount());
        assertEquals(11, event(committed, "INPUT_RESOLVED", 0).get("observedTime").getAsDouble(), 0);
        JsonObject wrong = waiting.deepCopy(); JsonObject row = event(wrong, "INPUT_WAITING_FOR_STORE", 0);
        row.addProperty("type", "INPUT_RESOLVED"); JsonObject p = row.getAsJsonObject("payload");
        p.addProperty("resolution", "LOCAL"); p.add("copyOrdinal", JsonNull.INSTANCE); p.add("source", vm(7)); bad(wrong);
        wrong = waiting.deepCopy(); wrong.getAsJsonObject("policies").addProperty("inputAccess", "LOCAL_VISIBLE_REPLICA_V3");
        wrong.getAsJsonObject("policies").addProperty("selection", FileLifecycleCodec.SELECTION); bad(wrong);
    }

    @Test void sameTargetDeferredReadersJoinInRequestOrderNotNumericJobOrder() {
        CoherentDataflowRuntime r = joinedWaiting(false);
        StorageLifecycleCodec.Decoded waiting = StorageLifecycleCodec.decode(StorageLifecycleCodec.encode(r.captureStorageEvidence()));
        assertEquals(2, waiting.getWaitingStoreInputCount(), "normalized Job/file pairs, not distinct files");
        assertEquals(1, waiting.getPendingOutputFileCount());
        r.advance(11);
        JsonObject root = StorageLifecycleCodec.document(r.captureStorageEvidence());
        assertEquals(20, payload(root, "COPY_ADMITTED", 1).get("ownerJobId").getAsInt());
        assertEquals("NEW_COPY", payload(root, "INPUT_RESOLVED", 0).get("resolution").getAsString());
        assertEquals(20, payload(root, "INPUT_RESOLVED", 0).get("jobId").getAsInt());
        assertEquals("JOIN_EXISTING", payload(root, "INPUT_RESOLVED", 1).get("resolution").getAsString());
        assertEquals(10, payload(root, "INPUT_RESOLVED", 1).get("jobId").getAsInt());
        assertEquals(2, StorageLifecycleCodec.decodeDocument(root).getCopyCount());
        r.advance(16); assertEquals(Arrays.asList(20, 10), r.drainReadyJobIds());
        root = StorageLifecycleCodec.document(r.captureStorageEvidence());
        JsonObject wrong = root.deepCopy(); payload(wrong, "INPUT_RESOLVED", 1).addProperty("copyOrdinal", 1); bad(wrong);
        wrong = root.deepCopy(); payload(wrong, "COPY_ADMITTED", 1).addProperty("ownerJobId", 10); bad(wrong);
    }

    @Test void resumedLocalReadersAndReadinessCannotBeReorderedOrDeferred() {
        CoherentDataflowRuntime r = joinedWaiting(true); r.advance(11);
        JsonObject root = StorageLifecycleCodec.document(r.captureStorageEvidence());
        assertEquals(20, payload(root, "INPUT_RESOLVED", 0).get("jobId").getAsInt());
        assertEquals(10, payload(root, "INPUT_RESOLVED", 1).get("jobId").getAsInt());
        JsonObject wrong = root.deepCopy();
        payload(wrong, "INPUT_RESOLVED", 0).addProperty("jobId", 10); payload(wrong, "JOB_DATA_READY", 1).addProperty("jobId", 10);
        payload(wrong, "INPUT_RESOLVED", 1).addProperty("jobId", 20); payload(wrong, "JOB_DATA_READY", 2).addProperty("jobId", 20); bad(wrong);
        wrong = root.deepCopy(); event(wrong, "INPUT_RESOLVED", 1).addProperty("observedTime", 12);
        event(wrong, "JOB_DATA_READY", 2).addProperty("observedTime", 12); wrong.getAsJsonObject("capture").addProperty("observedThrough", 12); bad(wrong);
    }

    @Test void multipleSameObservationSettlementsResumeOnlyTheirFilesAndMayAdmitReadsBetweenThem() {
        Task p = task(1, out("a", 100), out("b", 100)), c = task(2, in("a", 100), in("b", 100)); edge(p, c);
        CoherentDataflowRuntime r = runtime(true, true, 1000, plan(p, c));
        produce(r, 1, 1, 7, 0, 1, true); r.requestJob(2, one(2), 42, 1); r.advance(100);
        JsonObject root = StorageLifecycleCodec.document(r.captureStorageEvidence());
        assertEquals(2, payloads(root, "COPY_SETTLED").size()); assertEquals(4, payloads(root, "COPY_ADMITTED").size());
        assertEquals(0, StorageLifecycleCodec.decodeDocument(root).getWaitingStoreInputCount());
        assertEquals(2, StorageLifecycleCodec.decodeDocument(root).getActiveCopyCount());
        for (int i = 2; i < 4; i++) {
            JsonObject replica = payload(root, "COPY_ADMITTED", i).getAsJsonObject("sourceReplica");
            assertEquals(100, replica.get("visibleAt").getAsDouble(), 0); assertEquals("COPY_SETTLEMENT", replica.get("acquisition").getAsString());
            assertEquals(vm(7), replica.get("copiedFrom")); assertEquals(1, replica.getAsJsonObject("origin").get("jobAttemptId").getAsInt());
        }
        assertTrue(index(root, "COPY_ADMITTED", 2) < index(root, "COPY_SETTLED", 1));
        assertTrue(r.drainReadyJobIds().isEmpty());
        JsonObject wrong = root.deepCopy(); removeEvent(wrong, "INPUT_RESOLVED", 0); bad(wrong);
        wrong = root.deepCopy(); payload(wrong, "COPY_ADMITTED", 2).getAsJsonObject("sourceReplica").addProperty("visibleAt", 21); bad(wrong);
        drainCopies(r); double at = r.getCurrentTime(); finishReady(r, 2, 2, 42, at, at + 1);
        assertTrue(StorageLifecycleCodec.decode(StorageLifecycleCodec.encode(r.captureStorageEvidence())).isQuiescent());
    }

    @Test void zeroAndUnusedOutputsAreObligationsWithoutInventingPositiveCopies() {
        for (boolean storeInputs : new boolean[]{false, true}) {
            Task p = task(1, out("zero", 0)), c = task(2, in("zero", 0)); edge(p, c);
            CoherentDataflowRuntime r = runtime(true, storeInputs, 1000, plan(p, c)); produce(r, 1, 1, 7, 0, 1, true);
            StorageLifecycleCodec.Decoded first = StorageLifecycleCodec.decode(StorageLifecycleCodec.encode(r.captureStorageEvidence()));
            assertTrue(first.isQuiescent()); assertEquals(0, first.getCopyCount()); assertEquals(0, first.getPendingOutputFileCount());
            assertEquals(DataReplicaState.Acquisition.ZERO_BYTE_OUTPUT,
                    r.getReplicaSnapshot().getReplica(DataflowFilePlan.FileId.of(0, "zero"), CoherentDataflowRuntime.SOURCE).getAcquisition());
            r.requestJob(2, one(2), 42, 1); JsonObject root = StorageLifecycleCodec.document(r.captureStorageEvidence());
            assertEquals("ZERO", payload(root, "OUTPUT_RESOLVED", 0).get("resolution").getAsString());
            assertTrue(payload(root, "OUTPUT_RESOLVED", 0).get("copyOrdinal").isJsonNull());
            assertEquals(storeInputs ? source() : vm(7), payload(root, "INPUT_RESOLVED", 0).get("source"));
            JsonObject wrong = first.getDocument(); removeEvent(wrong, "OUTPUT_RESOLVED", 0); bad(wrong);
            wrong = root.deepCopy(); payload(wrong, "OUTPUT_RESOLVED", 0).addProperty("resolution", "ALREADY_STORED"); bad(wrong);
        }
        CoherentDataflowRuntime unused = runtime(true, true, 1000, plan(task(1, out("unused", 100))));
        produce(unused, 1, 1, 7, 0, 1, true);
        JsonObject wrong = StorageLifecycleCodec.document(unused.captureStorageEvidence());
        removeEvent(wrong, "COPY_ADMITTED", 0); removeEvent(wrong, "OUTPUT_RESOLVED", 0); bad(wrong);
    }

    @Test void failedAttemptHasNoOutputsAndRetryUsesItsActualVmAndOrigin() {
        Task p = task(1, out("x", 100)), c = task(2, in("x", 100)); edge(p, c);
        CoherentDataflowRuntime r = runtime(true, true, 1000, plan(p, c)); produce(r, 8, 1, 7, 0, 1, false);
        StorageLifecycleCodec.Decoded failure = StorageLifecycleCodec.decode(StorageLifecycleCodec.encode(r.captureStorageEvidence()));
        assertTrue(failure.isQuiescent()); assertEquals(0, failure.getCopyCount()); assertTrue(failure.getSuccessfullyCompletedTaskIds().isEmpty());
        produce(r, 9, 1, 42, 2, 3, true); r.requestJob(10, one(2), 99, 3); drainCopies(r);
        double at = r.getCurrentTime(); finishReady(r, 10, 2, 99, at, at + 1);
        JsonObject root = StorageLifecycleCodec.document(r.captureStorageEvidence());
        assertEquals(9, payload(root, "COPY_ADMITTED", 0).get("ownerJobId").getAsInt());
        JsonObject replica = payload(root, "COPY_ADMITTED", 1).getAsJsonObject("sourceReplica");
        assertEquals(9, replica.getAsJsonObject("origin").get("jobAttemptId").getAsInt());
        assertEquals(vm(42), replica.getAsJsonObject("origin").get("location"));
        JsonObject wrong = root.deepCopy(); payload(wrong, "COPY_ADMITTED", 0).addProperty("ownerJobId", 8); bad(wrong);
        wrong = root.deepCopy(); payload(wrong, "TASK_FINISHED", 1).addProperty("success", false); bad(wrong);
        wrong = root.deepCopy(); payload(wrong, "TASK_FINISHED", 0).addProperty("success", true); bad(wrong);
        wrong = root.deepCopy(); payload(wrong, "COPY_ADMITTED", 1).getAsJsonObject("sourceReplica").getAsJsonObject("origin").addProperty("jobAttemptId", 8); bad(wrong);
    }

    @Test void repeatedSuccessfulOutputsJoinOrObserveStoredStateWithoutRewritingFirstProvenance() {
        Task p = task(1, out("x", 100), out("zero", 0)), c = task(2, in("x", 100)); edge(p, c);
        CoherentDataflowRuntime r = runtime(true, true, 1000, plan(p, c));
        produce(r, 1, 1, 7, 0, 1, true); produce(r, 2, 1, 42, 2, 3, true); drainCopies(r);
        double at = r.getCurrentTime(); produce(r, 3, 1, 7, at + 1, at + 2, true);
        r.requestJob(4, one(2), 99, r.getCurrentTime());
        JsonObject root = StorageLifecycleCodec.document(r.captureStorageEvidence());
        assertEquals("JOIN_EXISTING", payload(root, "OUTPUT_RESOLVED", 2).get("resolution").getAsString());
        assertEquals(vm(42), payload(root, "OUTPUT_RESOLVED", 2).get("source"), "joining output source is its producing VM, not ticket source");
        assertEquals("ALREADY_STORED", payload(root, "OUTPUT_RESOLVED", 4).get("resolution").getAsString());
        assertEquals(1, payload(root, "COPY_ADMITTED", 1).getAsJsonObject("sourceReplica").getAsJsonObject("origin").get("jobAttemptId").getAsInt());
        JsonObject wrong = root.deepCopy(); payload(wrong, "OUTPUT_RESOLVED", 0).addProperty("resolution", "JOIN_EXISTING"); bad(wrong);
        wrong = root.deepCopy(); payload(wrong, "OUTPUT_RESOLVED", 2).add("source", vm(7)); bad(wrong);
        wrong = root.deepCopy(); payload(wrong, "OUTPUT_RESOLVED", 4).addProperty("resolution", "ZERO"); bad(wrong);
        wrong = root.deepCopy(); payload(wrong, "OUTPUT_RESOLVED", 4).addProperty("copyOrdinal", 1); bad(wrong);
    }

    @Test void completeCaptureCannotEndInsideRequestOutputOrResumeOperations() {
        JsonObject input = literal(); bad(prefix(input, 2)); bad(prefix(input, 3));
        assertFalse(StorageLifecycleCodec.decodeDocument(input).isQuiescent());
        CoherentDataflowRuntime r = waiting(false); JsonObject waiting = StorageLifecycleCodec.document(r.captureStorageEvidence());
        bad(prefix(waiting, index(waiting, "TASK_FINISHED", 0) + 1));
        bad(prefix(waiting, index(waiting, "COPY_ADMITTED", 0) + 1));
        assertEquals(1, StorageLifecycleCodec.decodeDocument(waiting).getWaitingStoreInputCount());
        r.advance(11); JsonObject committed = StorageLifecycleCodec.document(r.captureStorageEvidence());
        bad(prefix(committed, index(committed, "COPY_SETTLED", 0) + 1));
        bad(prefix(committed, index(committed, "COPY_ADMITTED", 1) + 1));
        assertEquals(1, StorageLifecycleCodec.decodeDocument(committed).getActiveCopyCount());
        r.advance(16); JsonObject arrived = StorageLifecycleCodec.document(r.captureStorageEvidence());
        bad(prefix(arrived, index(arrived, "COPY_SETTLED", 1) + 1));
        assertFalse(StorageLifecycleCodec.decodeDocument(arrived).isQuiescent(), "READY is not terminal CPU completion");
        CoherentDataflowRuntime joined = joinedWaiting(false); joined.advance(11);
        JsonObject two = StorageLifecycleCodec.document(joined.captureStorageEvidence());
        bad(prefix(two, index(two, "INPUT_RESOLVED", 0) + 1));
    }

    @Test void completeStandalonePrefixesNeedNotRequestEveryLogicalTask() {
        StorageLifecycleCodec.Decoded literal = StorageLifecycleCodec.decode(LITERAL);
        assertEquals(1, literal.getActiveCopyCount()); assertFalse(literal.isQuiescent());
        assertTrue(StorageLifecycleCodec.decodeDocument(prefix(literal.getDocument(), 1)).isQuiescent());
        CoherentDataflowRuntime r = runtime(true, true, 100, plan(task(1, out("unrequested", 100))));
        StorageLifecycleCodec.Decoded unrequested = StorageLifecycleCodec.decode(StorageLifecycleCodec.encode(r.captureStorageEvidence()));
        assertTrue(unrequested.isQuiescent()); assertEquals(0, unrequested.getPendingOutputFileCount());
        r = runtime(false, false, 100, plan());
        assertTrue(StorageLifecycleCodec.decode(StorageLifecycleCodec.encode(r.captureStorageEvidence())).isQuiescent());
    }

    @Test void immutableDecodedEvidenceAndDocumentsNeverRetainMutableTransportTrees() {
        CoherentDataflowRuntime r = waiting(false); JsonObject source = StorageLifecycleCodec.document(r.captureStorageEvidence());
        StorageLifecycleCodec.Decoded decoded = StorageLifecycleCodec.decodeDocument(source);
        String before = StorageLifecycleCodec.encode(decoded.getEvidence());
        source.addProperty("schema", "changed"); decoded.getDocument().getAsJsonObject("policies").remove("inputAccess");
        FileLifecycleEvidence capture = decoded.getEvidence().getCapture();
        capture.getFilePlan().remove("tasks"); capture.getFabric().getAsJsonObject("sourceStorage").addProperty("attachmentHostId", 999);
        capture.getEvents().get(0).getPayload().addProperty("jobId", 999);
        assertThrows(UnsupportedOperationException.class, () -> capture.getEvents().clear());
        assertThrows(UnsupportedOperationException.class, () -> decoded.getSuccessfullyCompletedTaskIds().clear());
        r.advance(11); assertEquals(before, StorageLifecycleCodec.encode(decoded.getEvidence()));
        assertEquals(StorageLifecycleCodec.SCHEMA, decoded.getDocument().get("schema").getAsString());
        assertEquals(1, decoded.getWaitingStoreInputCount()); assertEquals(1, decoded.getPendingOutputFileCount());
    }

    @Test void rootsPoliciesRolesAndVersionSpecificShapesAreStrict() {
        for (String field : Arrays.asList("schema", "modelKind", "certificateScope")) {
            JsonObject wrong = literal(); wrong.addProperty(field, "unsupported"); bad(wrong);
        }
        for (String field : Arrays.asList("fileIdentity", "release", "visibility", "selection", "sourceAccess", "sharing", "inputAccess", "outputCommit")) {
            JsonObject wrong = literal(); wrong.getAsJsonObject("policies").addProperty(field, "unsupported"); bad(wrong);
        }
        JsonObject wrong = literal(); wrong.addProperty("artifactRole", "storage-lifecycle"); bad(wrong);
        wrong = literal(); wrong.getAsJsonObject("recording").addProperty("mode", "FILE_LIFECYCLE_V2"); bad(wrong);
        wrong = literal(); wrong.getAsJsonObject("policies").addProperty("inputAccess", "COMMITTED_STORE_THEN_VM_READ_CACHE_V3"); bad(wrong);
        wrong = literal(); payload(wrong, "COPY_ADMITTED", 0).addProperty("destinationVmId", 7); bad(wrong);
        wrong = literal(); payload(wrong, "COPY_ADMITTED", 0).remove("purpose"); bad(wrong);
        wrong = literal(); payload(wrong, "INPUT_RESOLVED", 0).remove("copyOrdinal"); bad(wrong);
        wrong = literal(); payload(wrong, "COPY_ADMITTED", 0).getAsJsonObject("sourceReplica").remove("copiedFrom"); bad(wrong);
        wrong = literal(); event(wrong, "INPUT_RESOLVED", 0).addProperty("extra", 1); bad(wrong);
        wrong = literal(); payload(wrong, "COPY_ADMITTED", 0).getAsJsonObject("destination").addProperty("sourceId", "source"); bad(wrong);
        wrong = literal(); payload(wrong, "COPY_ADMITTED", 0).getAsJsonObject("sourceReplica").getAsJsonObject("origin").addProperty("extra", 1); bad(wrong);
    }

    @Test void v2PublicEntryPointsCannotAcceptStorageFieldsEventsOrEvidence() {
        CoherentDataflowRuntime storage = waiting(false);
        assertThrows(IllegalArgumentException.class, () -> FileLifecycleCodec.document(storage.captureStorageEvidence().getCapture()));
        assertThrows(IllegalArgumentException.class, () -> FileLifecycleCodec.decode(LITERAL));
        DataTransferFabric fabric = DataTransferFabric.endpoints(capacities(100, 100, 100), Collections.singletonList("source"));
        CoherentDataflowRuntime old = new CoherentDataflowRuntime(true, 1000);
        old.initializePlan(plan(task(1, in("x", 10))), fabric.getLocations()); old.bindFabric(fabric); old.requestJob(1, one(1), 7, 0);
        JsonObject v2 = FileLifecycleCodec.document(old.captureEvidence());
        assertThrows(IllegalArgumentException.class, () -> StorageLifecycleCodec.decodeDocument(v2));
        JsonObject withStore = v2.deepCopy(); withStore.getAsJsonObject("fabric").add("sourceStorage", literal().getAsJsonObject("fabric").get("sourceStorage").deepCopy());
        assertThrows(IllegalArgumentException.class, () -> FileLifecycleCodec.decodeDocument(withStore));
        for (String type : Arrays.asList("OUTPUT_RESOLVED", "INPUT_WAITING_FOR_STORE")) {
            JsonObject changed = v2.deepCopy(); event(changed, "INPUT_RESOLVED", 0).addProperty("type", type);
            assertThrows(IllegalArgumentException.class, () -> FileLifecycleCodec.decodeDocument(changed));
        }
        JsonObject withPurpose = v2.deepCopy(); payload(withPurpose, "COPY_ADMITTED", 0).addProperty("purpose", "INPUT");
        assertThrows(IllegalArgumentException.class, () -> FileLifecycleCodec.decodeDocument(withPurpose));
        JsonObject withAcquisition = v2.deepCopy(); payload(withAcquisition, "COPY_ADMITTED", 0).getAsJsonObject("sourceReplica").addProperty("acquisition", "ZERO_BYTE_OUTPUT");
        assertThrows(IllegalArgumentException.class, () -> FileLifecycleCodec.decodeDocument(withAcquisition));
        JsonObject withPolicy = v2.deepCopy(); withPolicy.getAsJsonObject("policies").addProperty("inputAccess", "LOCAL_VISIBLE_REPLICA_V3");
        assertThrows(IllegalArgumentException.class, () -> FileLifecycleCodec.decodeDocument(withPolicy));
    }

    @Test void typedPurposeOwnerAndSourceMutationsCannotTurnInputsIntoUploads() {
        for (String purpose : Arrays.asList("OUTPUT", "input", "OTHER")) {
            JsonObject wrong = literal(); payload(wrong, "COPY_ADMITTED", 0).addProperty("purpose", purpose); bad(wrong);
        }
        JsonObject wrong = literal(); payload(wrong, "COPY_ADMITTED", 0).addProperty("ownerJobId", 1); bad(wrong);
        wrong = literal(); payload(wrong, "COPY_ADMITTED", 0).add("destination", source()); bad(wrong);
        wrong = literal(); payload(wrong, "COPY_ADMITTED", 0).getAsJsonObject("destination").addProperty("vmId", 42); bad(wrong);
        wrong = literal(); payload(wrong, "COPY_ADMITTED", 0).getAsJsonObject("sourceReplica").add("location", vm(7)); bad(wrong);
        wrong = literal(); payload(wrong, "INPUT_RESOLVED", 0).add("source", vm(7)); bad(wrong);
        wrong = literal(); payload(wrong, "INPUT_RESOLVED", 0).addProperty("referenceCount", 2); bad(wrong);
        JsonObject output = StorageLifecycleCodec.document(waiting(false).captureStorageEvidence());
        wrong = output.deepCopy(); payload(wrong, "COPY_ADMITTED", 0).addProperty("purpose", "INPUT"); bad(wrong);
        wrong = output.deepCopy(); payload(wrong, "COPY_ADMITTED", 0).addProperty("ownerJobId", 20); bad(wrong);
        wrong = output.deepCopy(); payload(wrong, "COPY_ADMITTED", 0).add("destination", vm(42)); bad(wrong);
        wrong = output.deepCopy(); payload(wrong, "OUTPUT_RESOLVED", 0).addProperty("taskId", 2); bad(wrong);
        wrong = output.deepCopy(); payload(wrong, "OUTPUT_RESOLVED", 0).add("source", source()); bad(wrong);
        wrong = output.deepCopy(); payload(wrong, "COPY_ADMITTED", 0).getAsJsonObject("sourceReplica").getAsJsonObject("origin").addProperty("observedAt", 0); bad(wrong);
        wrong = output.deepCopy(); payload(wrong, "OUTPUT_RESOLVED", 0).getAsJsonObject("fileId").addProperty("name", "undeclared"); bad(wrong);
    }

    @Test void storeWaitRecordsMustBeInitialCompleteAndMatchDeclaredReferences() {
        JsonObject root = StorageLifecycleCodec.document(waiting(false).captureStorageEvidence());
        JsonObject wrong = root.deepCopy(); removeEvent(wrong, "INPUT_WAITING_FOR_STORE", 0); bad(wrong);
        wrong = root.deepCopy(); payload(wrong, "INPUT_WAITING_FOR_STORE", 0).addProperty("referenceCount", 2); bad(wrong);
        wrong = root.deepCopy(); payload(wrong, "INPUT_WAITING_FOR_STORE", 0).addProperty("jobId", 10); bad(wrong);
        wrong = root.deepCopy(); event(wrong, "INPUT_WAITING_FOR_STORE", 0).addProperty("observedTime", 2); wrong.getAsJsonObject("capture").addProperty("observedThrough", 2); bad(wrong);
        wrong = root.deepCopy(); events(wrong).add(event(wrong, "INPUT_WAITING_FOR_STORE", 0).deepCopy()); recount(wrong); bad(wrong);
        wrong = root.deepCopy(); payload(wrong, "INPUT_WAITING_FOR_STORE", 0).add("source", source()); bad(wrong);
        wrong = literal(); JsonObject row = event(wrong, "INPUT_RESOLVED", 0); row.addProperty("type", "INPUT_WAITING_FOR_STORE");
        JsonObject p = row.getAsJsonObject("payload"); p.remove("source"); p.remove("copyOrdinal"); p.remove("resolution"); bad(wrong);
    }

    @Test void exactFabricInventoryAndCompleteActualVmHostsAreMandatoryEvenWithoutTopology() {
        JsonObject wrong = literal(); wrong.getAsJsonObject("fabric").getAsJsonArray("vmHostAssignments").remove(0); bad(wrong);
        wrong = literal(); wrong.getAsJsonObject("fabric").getAsJsonArray("vmHostAssignments").add(NetworkLedgerCodec.parseDocument("{\"vmId\":7,\"hostId\":50}")); bad(wrong);
        wrong = literal(); wrong.getAsJsonObject("fabric").getAsJsonArray("vmHostAssignments").get(0).getAsJsonObject().addProperty("vmId", 99); bad(wrong);
        wrong = literal(); wrong.getAsJsonObject("fabric").getAsJsonArray("resources").remove(3); bad(wrong);
        wrong = literal(); wrong.getAsJsonObject("fabric").getAsJsonArray("locations").add(source()); bad(wrong);
        wrong = literal(); wrong.getAsJsonObject("fabric").getAsJsonArray("locations").get(1).getAsJsonObject().addProperty("sourceId", "other"); bad(wrong);
        wrong = literal(); wrong.getAsJsonObject("fabric").getAsJsonObject("sourceStorage").addProperty("sourceId", "source"); bad(wrong);
        wrong = literal(); wrong.getAsJsonObject("fabric").getAsJsonObject("sourceStorage").addProperty("attachmentHostId", -1); bad(wrong);
        wrong = literal(); resource(wrong, "STORE:source:NIC").addProperty("key", "SOURCE:source"); bad(wrong);
        wrong = literal(); wrong.getAsJsonObject("fabric").remove("sourceStorage"); bad(wrong);
        assertDoesNotThrow(() -> StorageLifecycleCodec.decode(LITERAL), "endpoint-only attachment host membership belongs to final platform context");
    }

    @Test void storageBandwidthsUseExactNormalBinary64MbConversionsWithoutRounding() {
        for (String field : Arrays.asList("readBandwidthMbPerSecond", "writeBandwidthMbPerSecond", "networkBandwidthMbPerSecond")) {
            for (double value : new double[]{0, -1, Double.MIN_VALUE, Double.MAX_VALUE, Double.NaN, Double.POSITIVE_INFINITY}) {
                JsonObject wrong = literal(); wrong.getAsJsonObject("fabric").getAsJsonObject("sourceStorage").addProperty(field, value);
                assertThrows(IllegalArgumentException.class, () -> StorageLifecycleCodec.decodeDocument(wrong));
            }
            JsonObject wrong = literal(); JsonObject store = wrong.getAsJsonObject("fabric").getAsJsonObject("sourceStorage");
            store.addProperty(field, store.get(field).getAsDouble() * 1.01); bad(wrong);
        }
        CoherentDataflowRuntime r = runtime(false, false, 100, plan(), fabric(false, 40, 100, 100, 100, 12.5, 3.25, 7.75));
        assertDoesNotThrow(() -> StorageLifecycleCodec.encode(r.captureStorageEvidence()));
        // MB/s itself may be subnormal, provided the specified conversion is normal.
        double mb = Math.nextUp(Double.MIN_NORMAL / 1e6), bytes = mb * 1e6;
        DataTransferFabric tiny = DataTransferFabric.withStorage(capacities(bytes, bytes, bytes), hosts(), null, DataflowStorageSpec.of(40, mb, mb, mb));
        CoherentDataflowRuntime small = runtime(false, true, 100, plan(), tiny);
        assertDoesNotThrow(() -> StorageLifecycleCodec.encode(small.captureStorageEvidence()));
    }

    @Test void topologyChecksStorePlacementBoundsAndEveryDirectedPhysicalLink() {
        CoherentDataflowRuntime r = runtime(true, true, 100, plan(), fabric(true, 10, 100, 100, 100, 30, 10, 100));
        JsonObject root = StorageLifecycleCodec.document(r.captureStorageEvidence());
        JsonObject wrong = root.deepCopy(); wrong.getAsJsonObject("fabric").getAsJsonObject("sourceStorage").addProperty("attachmentHostId", 99); bad(wrong);
        wrong = root.deepCopy(); wrong.getAsJsonObject("fabric").getAsJsonObject("topology").addProperty("k", Integer.MAX_VALUE); bad(wrong);
        wrong = root.deepCopy(); wrong.getAsJsonObject("fabric").getAsJsonObject("topology").addProperty("coreSwitchCount", 0); bad(wrong);
        wrong = root.deepCopy(); resource(wrong, "LINK:ACC:10->EDGE:0:0").addProperty("capacityBytesPerSecond", 21); bad(wrong);
        wrong = root.deepCopy(); wrong.getAsJsonObject("fabric").getAsJsonArray("resources").add(resource(wrong, "VM:7").deepCopy()); bad(wrong);
        wrong = root.deepCopy(); wrong.getAsJsonObject("fabric").getAsJsonArray("vmHostAssignments").get(0).getAsJsonObject().addProperty("hostId", 99); bad(wrong);
        wrong = root.deepCopy(); JsonArray placed = wrong.getAsJsonObject("fabric").getAsJsonObject("topology").getAsJsonArray("hostPlacements");
        for (JsonElement host : placed) { host.getAsJsonObject().addProperty("pod", 0); host.getAsJsonObject().addProperty("edge", 0); } bad(wrong);
    }

    @Test void standalonePlanValidationSharesExactScopedWriterAndAncestryRules() {
        Task p = task(1, out("x", 10)), m = task(2), c = task(3, in("x", 10)); edge(p, m); edge(m, c);
        JsonObject root = StorageLifecycleCodec.document(runtime(true, true, 100, plan(p, m, c)).captureStorageEvidence());
        JsonObject logical = root.getAsJsonObject("filePlan"), before = logical.deepCopy();
        StorageLifecycleCodec.validatePlanDocument(logical); FileLifecycleCodec.validatePlanDocument(logical); assertEquals(before, logical);
        JsonObject wrong = logical.deepCopy(); wrong.getAsJsonArray("tasks").get(1).getAsJsonObject().add("parents", new JsonArray());
        assertThrows(IllegalArgumentException.class, () -> StorageLifecycleCodec.validatePlanDocument(wrong));
        JsonObject large = literal().getAsJsonObject("filePlan");
        large.getAsJsonArray("tasks").get(0).getAsJsonObject().getAsJsonArray("inputs").get(0).getAsJsonObject().addProperty("referenceCount", Long.MAX_VALUE);
        assertDoesNotThrow(() -> StorageLifecycleCodec.validatePlanDocument(large));
        JsonObject seeds = prefix(literal(), 1); seeds.add("filePlan", large);
        assertEquals(Long.MAX_VALUE, StorageLifecycleCodec.decodeDocument(seeds).getEvidence().getCapture().getFilePlan()
                .getAsJsonArray("tasks").get(0).getAsJsonObject().getAsJsonArray("inputs").get(0).getAsJsonObject().get("referenceCount").getAsLong());
        assertThrows(IllegalArgumentException.class, () -> StorageLifecycleCodec.validatePlanDocument(null));
    }

    @Test void lexicalDuplicatesDeepNestingAndNonExactIntegersAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> StorageLifecycleCodec.decode(LITERAL.replace("\"schema\":", "\"schema\":\"duplicate\",\"schema\":")));
        assertThrows(IllegalArgumentException.class, () -> StorageLifecycleCodec.decode(LITERAL.replaceFirst("\"ownerJobId\":0", "\"ownerJobId\":0,\"ownerJobId\":0")));
        for (String text : Arrays.asList(LITERAL + "{}", LITERAL + " //comment", "{schema:'x'}", "{\"schema\":1,\"\\u0073chema\":2}"))
            assertThrows(IllegalArgumentException.class, () -> StorageLifecycleCodec.decode(text));
        String nested = "0"; for (int i = 0; i < 100; i++) nested = "[" + nested + "]";
        final String deep = "{\"schema\":" + nested + "}";
        assertThrows(IllegalArgumentException.class, () -> StorageLifecycleCodec.decode(deep));
        for (BigDecimal id : Arrays.asList(new BigDecimal("9007199254740993"), new BigDecimal("2147483648"), new BigDecimal("0.000000000000000000001"))) {
            JsonObject wrong = literal(); payload(wrong, "COPY_ADMITTED", 0).addProperty("ownerJobId", id); bad(wrong);
        }
        JsonObject wrong = literal(); payload(wrong, "COPY_ADMITTED", 0).addProperty("copyOrdinal", new BigDecimal("9223372036854775808")); bad(wrong);
        wrong = literal(); payload(wrong, "INPUT_RESOLVED", 0).addProperty("copyOrdinal", new BigDecimal("1.0000000000000000000001")); bad(wrong);
        wrong = literal(); wrong.getAsJsonObject("fabric").getAsJsonObject("sourceStorage").addProperty("attachmentHostId", .5); bad(wrong);
        wrong = literal(); fileRow(wrong, 0).addProperty("bytes", new BigDecimal("1e-4097")); bad(wrong);
        JsonObject maximum = literal(); payload(maximum, "JOB_INPUT_REQUESTED", 0).addProperty("jobId", Integer.MAX_VALUE);
        payload(maximum, "COPY_ADMITTED", 0).addProperty("ownerJobId", Integer.MAX_VALUE); payload(maximum, "INPUT_RESOLVED", 0).addProperty("jobId", Integer.MAX_VALUE);
        assertEquals(1, StorageLifecycleCodec.decode(maximum.toString()).getRequestedJobCount());
    }

    @Test void settlementUsesObservedVisibilityAndExactResidualAndLowerTimeBounds() {
        double bound = Math.min(10 * .5, Math.max(10 * 1e-9, 4 * Math.ulp(10.0))), lower = (10 - bound) / 10;
        assertEquals(1, StorageLifecycleCodec.decodeDocument(settledLiteral(0, 1, lower, bound)).getCompletedCopyCount());
        bad(settledLiteral(0, 1, lower, Math.nextUp(bound))); bad(settledLiteral(0, 1, .5, 0));
        bad(settledLiteral(0, 1, 2, 0)); bad(settledLiteral(0, 0, 0, 0));
        JsonObject wrong = settledLiteral(0, 1, 1, 0); append(wrong, "COPY_SETTLED", 1, "{\"copyOrdinal\":1,\"effectiveTime\":1,\"remainingAfterService\":0}"); bad(wrong);
        double release = 1e12;
        assertDoesNotThrow(() -> StorageLifecycleCodec.decodeDocument(settledLiteral(release, release + 2, Math.nextDown(release + 1), 0)));
        bad(settledLiteral(release, release + 2, release + 1 - 9 * Math.ulp(release + 1), 0));
        CoherentDataflowRuntime r = waiting(false); r.advance(20); JsonObject output = StorageLifecycleCodec.document(r.captureStorageEvidence());
        wrong = output.deepCopy(); payload(wrong, "COPY_SETTLED", 0).addProperty("effectiveTime", 2); bad(wrong);
        wrong = output.deepCopy(); payload(wrong, "COPY_SETTLED", 0).addProperty("remainingAfterService", 1); bad(wrong);
    }

    @Test void unsupportedDurationsRatesAndRequestAggregatesCannotBeLaundered() {
        JsonObject wrong = settledLiteral(1e16, 1e16 + 2, 1e16 + 2, 0); bad(wrong);
        for (double rate : new double[]{0, Double.MIN_VALUE, Double.POSITIVE_INFINITY}) {
            JsonObject invalid = literal(); resource(invalid, "VM:7").addProperty("capacityBytesPerSecond", rate);
            assertThrows(IllegalArgumentException.class, () -> StorageLifecycleCodec.decodeDocument(invalid));
        }
        wrong = literal(); fileRow(wrong, 0).addProperty("bytes", new BigDecimal("1e-4000")); bad(wrong);
        wrong = literal(); payload(wrong, "COPY_ADMITTED", 0).addProperty("standaloneRate", Math.nextUp(10.0)); bad(wrong);
        wrong = literal(); payload(wrong, "COPY_ADMITTED", 0).addProperty("isolatedSeconds", Math.nextUp(1.0)); bad(wrong);
        wrong = literal(); taskRow(wrong, 0).getAsJsonArray("inputs").get(0).getAsJsonObject().addProperty("referenceCount", 2147483648L);
        payload(wrong, "INPUT_RESOLVED", 0).addProperty("referenceCount", 2147483648L); bad(wrong);
        wrong = literal(); setBytesAndReadRate(wrong, Double.MAX_VALUE, 10);
        taskRow(wrong, 0).getAsJsonArray("inputs").get(0).getAsJsonObject().addProperty("referenceCount", 2);
        payload(wrong, "INPUT_RESOLVED", 0).addProperty("referenceCount", 2); bad(wrong);
        wrong = literal(); setBytesAndReadRate(wrong, Double.MIN_VALUE, 100); bad(wrong);
    }

    @Test void completeOnlyCaptureAndEncoderValidateSemanticsRatherThanTrustingCarrier() {
        for (JsonElement budget : Arrays.asList(JsonNull.INSTANCE, new JsonPrimitive("64"), new JsonPrimitive(0), new JsonPrimitive(.5), new JsonPrimitive(2147483648L))) {
            JsonObject wrong = literal(); wrong.getAsJsonObject("recording").add("maxTraceRecords", budget); bad(wrong);
        }
        JsonObject wrong = literal(); wrong.getAsJsonObject("capture").addProperty("status", "TRUNCATED"); bad(wrong);
        wrong = literal(); wrong.getAsJsonObject("capture").addProperty("droppedRecords", 1); bad(wrong);
        wrong = literal(); wrong.getAsJsonObject("capture").addProperty("retainedRecords", 3); bad(wrong);
        CoherentDataflowRuntime r = runtime(true, true, 1, plan(task(1, in("x", 10)))); r.requestJob(1, one(1), 7, 0);
        assertThrows(IllegalArgumentException.class, () -> StorageLifecycleCodec.document(r.captureStorageEvidence()));
        StorageLifecycleEvidence good = StorageLifecycleCodec.decode(LITERAL).getEvidence(); FileLifecycleEvidence common = good.getCapture();
        List<FileLifecycleEvent> forged = new ArrayList<>(common.getEvents()); JsonObject p = forged.get(2).getPayload(); p.addProperty("purpose", "OUTPUT");
        forged.set(2, FileLifecycleEvent.of(3, 0, FileLifecycleEvent.Type.COPY_ADMITTED, p));
        StorageLifecycleEvidence unvalidated = StorageLifecycleEvidence.capture(FileLifecycleEvidence.capture(64, 0, 0, true, common.getFilePlan(), common.getFabric(), forged), false);
        assertThrows(IllegalArgumentException.class, () -> StorageLifecycleCodec.encode(unvalidated));
        assertThrows(IllegalArgumentException.class, () -> StorageLifecycleCodec.document(null));
        assertThrows(IllegalArgumentException.class, () -> StorageLifecycleCodec.decodeDocument(null));
        assertThrows(IllegalArgumentException.class, () -> StorageLifecycleCodec.decode(null));
    }

    @Test void localPolicyRanksBoundedRoutesButSharedInputsNeverChooseFasterPeer() {
        for (boolean storeInputs : new boolean[]{false, true}) {
            Task p = task(1, out("x", 100)), a = task(2, in("x", 100)), b = task(3, in("x", 100)); edge(p, a); edge(p, b);
            CoherentDataflowRuntime r = runtime(true, storeInputs, 1000, plan(p, a, b), fabric(false, 10, 10, 100, 100, 20, 10, 100));
            produce(r, 1, 1, 7, 0, 1, true); drainCopies(r);
            r.requestJob(2, one(2), 99, r.getCurrentTime()); drainCopies(r);
            double at = r.getCurrentTime(); finishReady(r, 2, 2, 99, at, at + 1);
            r.requestJob(3, one(3), 42, r.getCurrentTime());
            JsonObject root = StorageLifecycleCodec.document(r.captureStorageEvidence());
            JsonObject admission = payload(root, "COPY_ADMITTED", 2);
            assertEquals(storeInputs ? source() : vm(99), admission.getAsJsonObject("sourceReplica").get("location"));
            assertEquals(storeInputs ? 20 : 100, admission.get("standaloneRate").getAsDouble(), 0);
            JsonObject wrong = root.deepCopy(), wrongAdmission = payload(wrong, "COPY_ADMITTED", 2);
            JsonObject other = payload(root, "COPY_ADMITTED", 1).getAsJsonObject("sourceReplica").deepCopy();
            if (storeInputs) {
                other.add("location", vm(99)); other.addProperty("visibleAt", at); other.addProperty("acquisition", "COPY_SETTLEMENT");
                other.add("copiedFrom", source()); other.addProperty("copyOrdinal", 2);
                wrongAdmission.add("resources", arrayOf("VM:99", "VM:42")); wrongAdmission.addProperty("standaloneRate", 100); wrongAdmission.addProperty("isolatedSeconds", 1);
            } else {
                wrongAdmission.add("resources", arrayOf("STORE:source:READ", "STORE:source:NIC", "VM:42")); wrongAdmission.addProperty("standaloneRate", 20); wrongAdmission.addProperty("isolatedSeconds", 5);
            }
            wrongAdmission.add("sourceReplica", other); payload(wrong, "INPUT_RESOLVED", 1).add("source", other.get("location").deepCopy()); bad(wrong);
        }
    }

    @Test void localInputsCanFinishCpuBeforeRequiredSlowUploadDrains() {
        Task p = task(1, out("x", 100)), c = task(2, in("x", 100)); edge(p, c);
        CoherentDataflowRuntime r = runtime(true, false, 1000, plan(p, c), fabric(false, 10, 100, 100, 100, 100, 1, 100));
        produce(r, 1, 1, 7, 0, 1, true); r.requestJob(2, one(2), 42, 1); r.advance(3); finishReady(r, 2, 2, 42, 3, 4);
        StorageLifecycleCodec.Decoded running = StorageLifecycleCodec.decode(StorageLifecycleCodec.encode(r.captureStorageEvidence()));
        assertEquals(2, running.getCompletedJobCount()); assertEquals(1, running.getPendingOutputFileCount());
        assertEquals(0, running.getWaitingStoreInputCount()); assertEquals(1, running.getActiveCopyCount()); assertFalse(running.isQuiescent());
        assertEquals(vm(7), payload(running.getDocument(), "COPY_ADMITTED", 1).getAsJsonObject("sourceReplica").get("location"));
        drainCopies(r); assertTrue(StorageLifecycleCodec.decode(StorageLifecycleCodec.encode(r.captureStorageEvidence())).isQuiescent());
    }

    @Test void localNumericVmTieOrderIsCheckedIndependentlyOfWirePaths() {
        CoherentDataflowRuntime r = runtime(true, false, 1000, plan(task(1, in("x", 10)), task(2, in("x", 10)), task(3, in("x", 10))),
                fabric(false, 10, 100, 100, 100, 100, 100, 100));
        r.requestJob(1, one(1), 7, 0); r.advance(1); finishReady(r, 1, 1, 7, 1, 1);
        r.requestJob(2, one(2), 42, 1); r.advance(2); finishReady(r, 2, 2, 42, 2, 2);
        r.requestJob(3, one(3), 99, 2);
        JsonObject root = StorageLifecycleCodec.document(r.captureStorageEvidence());
        assertEquals(vm(7), payload(root, "COPY_ADMITTED", 2).getAsJsonObject("sourceReplica").get("location"));
        JsonObject wrong = root.deepCopy(), admission = payload(wrong, "COPY_ADMITTED", 2), replica = admission.getAsJsonObject("sourceReplica");
        replica.add("location", vm(42)); replica.addProperty("visibleAt", 2); replica.addProperty("copyOrdinal", 2); replica.add("copiedFrom", vm(7));
        admission.add("resources", arrayOf("VM:42", "VM:99")); payload(wrong, "INPUT_RESOLVED", 2).add("source", vm(42)); bad(wrong);
    }

    @Test void exactMultiWorkflowNamesAndZeroReferencesNeverAlias() {
        String name = "input[0]/shared::名/<literal>";
        Task a = task(1, in(name, 0)), b = task(2, in(name, 0)), c = task(3, in(name, 0));
        Map<Integer, Integer> scopes = new LinkedHashMap<>(); scopes.put(1, 0); scopes.put(2, 1); scopes.put(3, 0);
        CoherentDataflowRuntime r = runtime(true, false, 1000, DataflowFilePlan.capture(Arrays.asList(a, b, c), scopes));
        produce(r, 1, 1, 7, 0, 1, true); produce(r, 2, 2, 7, 1, 2, true); produce(r, 3, 3, 42, 2, 3, true);
        JsonObject root = StorageLifecycleCodec.document(r.captureStorageEvidence());
        assertEquals(2, payloads(root, "EXTERNAL_SEEDED").size()); assertEquals(0, StorageLifecycleCodec.decodeDocument(root).getCopyCount());
        assertEquals(source(), payload(root, "INPUT_RESOLVED", 1).get("source"));
        assertEquals(vm(7), payload(root, "INPUT_RESOLVED", 2).get("source"));
        JsonObject wrong = root.deepCopy(); payload(wrong, "INPUT_RESOLVED", 1).getAsJsonObject("fileId").addProperty("workflowInputIndex", 0); bad(wrong);
        wrong = root.deepCopy(); payload(wrong, "INPUT_RESOLVED", 2).getAsJsonObject("fileId").addProperty("name", "shared::名/<literal>"); bad(wrong);
        wrong = root.deepCopy(); payload(wrong, "INPUT_RESOLVED", 0).addProperty("resolution", "LOCAL"); bad(wrong);
    }

    @Test void mixedInitialStoreAndVmCopyWaitsAreSeparateUntilEveryInputIsVisible() {
        Task p = task(1, out("a", 100)), c = task(2, in("a", 100), in("b", 40), in("zero", 0)); edge(p, c);
        CoherentDataflowRuntime r = runtime(true, true, 1000, plan(p, c));
        produce(r, 1, 1, 7, 0, 1, true); r.requestJob(2, one(2), 42, 1);
        JsonObject requested = StorageLifecycleCodec.document(r.captureStorageEvidence());
        assertEquals(1, payloads(requested, "INPUT_WAITING_FOR_STORE").size()); assertEquals(2, payloads(requested, "INPUT_RESOLVED").size());
        assertEquals(1, StorageLifecycleCodec.decodeDocument(requested).getWaitingStoreInputCount());
        r.advance(3); assertTrue(r.drainReadyJobIds().isEmpty());
        StorageLifecycleCodec.Decoded partial = StorageLifecycleCodec.decode(StorageLifecycleCodec.encode(r.captureStorageEvidence()));
        assertEquals(1, partial.getWaitingStoreInputCount()); assertEquals(1, partial.getActiveCopyCount());
        r.advance(11); JsonObject resumed = StorageLifecycleCodec.document(r.captureStorageEvidence());
        assertEquals("a", payload(resumed, "INPUT_RESOLVED", 2).getAsJsonObject("fileId").get("name").getAsString());
        JsonObject wrong = resumed.deepCopy(); payload(wrong, "INPUT_RESOLVED", 2).getAsJsonObject("fileId").addProperty("name", "b"); bad(wrong);
        wrong = requested.deepCopy(); removeEvent(wrong, "INPUT_RESOLVED", 1); bad(wrong);
        r.advance(16); finishReady(r, 2, 2, 42, 16, 17);
        assertTrue(StorageLifecycleCodec.decode(StorageLifecycleCodec.encode(r.captureStorageEvidence())).isQuiescent());
    }

    @Test void deferredFilesMayResolveInDifferentOrderFromCanonicalInitialDemands() {
        Task p = task(1, out("a", 200), out("b", 20)), c = task(2, in("a", 200), in("b", 20)); edge(p, c);
        CoherentDataflowRuntime r = runtime(true, true, 1000, plan(p, c));
        produce(r, 1, 1, 7, 0, 1, true); r.requestJob(2, one(2), 42, 1); r.advance(5);
        JsonObject first = StorageLifecycleCodec.document(r.captureStorageEvidence());
        assertEquals("a", payload(first, "INPUT_WAITING_FOR_STORE", 0).getAsJsonObject("fileId").get("name").getAsString());
        assertEquals("b", payload(first, "INPUT_RESOLVED", 0).getAsJsonObject("fileId").get("name").getAsString());
        assertEquals(1, StorageLifecycleCodec.decodeDocument(first).getWaitingStoreInputCount());
        JsonObject wrong = first.deepCopy(); payload(wrong, "COPY_ADMITTED", 2).getAsJsonObject("fileId").addProperty("name", "a"); bad(wrong);
        drainCopies(r); double at = r.getCurrentTime(); finishReady(r, 2, 2, 42, at, at + 1);
        JsonObject root = StorageLifecycleCodec.document(r.captureStorageEvidence());
        assertEquals("a", payload(root, "INPUT_RESOLVED", 1).getAsJsonObject("fileId").get("name").getAsString());
        assertEquals(11, r.getPreparation(2).getIsolatedSeconds(), 0);
    }

    @Test void vmPublicationsCanReleaseWaitersBetweenOutputResolutionsAndSurviveLaterCopySettlements() {
        Task p = task(1, out("a", 100), out("b", 100)), a = task(2, in("a", 100)), b = task(3, in("b", 100)), later = task(4, in("a", 100));
        edge(p, a); edge(p, b); edge(p, later);
        CoherentDataflowRuntime r = runtime(true, false, 2000, plan(p, a, b, later), fabric(false, 10, 10, 100, 100, 20, 1, 100));
        produce(r, 1, 1, 7, 0, 1, true); r.requestJob(2, one(2), 99, 1); r.requestJob(3, one(3), 99, 1);
        produce(r, 4, 1, 99, 2, 3, true);
        assertEquals(Arrays.asList(2, 3), r.drainReadyJobIds());
        JsonObject published = StorageLifecycleCodec.document(r.captureStorageEvidence());
        assertTrue(index(published, "JOB_DATA_READY", 2) < index(published, "OUTPUT_RESOLVED", 2));
        assertTrue(index(published, "OUTPUT_RESOLVED", 2) < index(published, "JOB_DATA_READY", 3));
        assertTrue(index(published, "JOB_DATA_READY", 3) < index(published, "OUTPUT_RESOLVED", 3));
        JsonObject wrong = published.deepCopy(); removeEvent(wrong, "JOB_DATA_READY", 2); bad(wrong);
        bad(prefix(published, index(published, "OUTPUT_RESOLVED", 2) + 1));
        r.cpuStarted(2, one(2), 99, 3); r.jobFinished(2, one(2), 99, Collections.singletonList(true), 4);
        r.cpuStarted(3, one(3), 99, 4); r.jobFinished(3, one(3), 99, Collections.singletonList(true), 5);
        drainCopies(r); r.requestJob(5, one(4), 42, r.getCurrentTime());
        JsonObject root = StorageLifecycleCodec.document(r.captureStorageEvidence());
        JsonObject replica = payload(root, "COPY_ADMITTED", 4).getAsJsonObject("sourceReplica");
        assertEquals(vm(99), replica.get("location")); assertEquals("TASK_OUTPUT", replica.get("acquisition").getAsString());
        assertEquals(3, replica.get("visibleAt").getAsDouble(), 0); assertEquals(4, replica.getAsJsonObject("origin").get("jobAttemptId").getAsInt());
        assertTrue(replica.get("copyOrdinal").isJsonNull());
        wrong = root.deepCopy(); payload(wrong, "COPY_ADMITTED", 4).getAsJsonObject("sourceReplica").addProperty("acquisition", "COPY_SETTLEMENT"); bad(wrong);
    }

    @Test void cpuCannotCreateReadinessChangeFixedVmOrRewriteTerminalOutcome() {
        JsonObject wrong = literal(); append(wrong, "JOB_CPU_STARTED", 1, "{\"jobId\":0,\"vmId\":7}"); bad(wrong);
        wrong = literal(); append(wrong, "TASK_FINISHED", 1, "{\"jobId\":0,\"taskId\":1,\"vmId\":7,\"success\":true}"); bad(wrong);
        wrong = settledLiteral(0, 1, 1, 0); removeEvent(wrong, "JOB_DATA_READY", 0); bad(wrong);
        wrong = settledLiteral(0, 1, 1, 0); append(wrong, "JOB_DATA_READY", 1, "{\"jobId\":0}"); bad(wrong);
        JsonObject finished = StorageLifecycleCodec.document(waiting(false).captureStorageEvidence());
        wrong = finished.deepCopy(); payload(wrong, "JOB_CPU_STARTED", 0).addProperty("vmId", 42); bad(wrong);
        wrong = finished.deepCopy(); payload(wrong, "TASK_FINISHED", 0).addProperty("vmId", 42); bad(wrong);
        wrong = finished.deepCopy(); payload(wrong, "TASK_FINISHED", 0).addProperty("taskId", 2); bad(wrong);
        wrong = finished.deepCopy(); payload(wrong, "TASK_FINISHED", 0).addProperty("success", "true"); bad(wrong);
        wrong = finished.deepCopy(); append(wrong, "JOB_CPU_STARTED", 2, "{\"jobId\":10,\"vmId\":7}"); bad(wrong);
        wrong = finished.deepCopy(); append(wrong, "TASK_FINISHED", 2, "{\"jobId\":10,\"taskId\":1,\"vmId\":7,\"success\":true}"); bad(wrong);
        wrong = finished.deepCopy(); payload(wrong, "JOB_INPUT_REQUESTED", 1).getAsJsonArray("taskIds").add(2); bad(wrong);
        CoherentDataflowRuntime empty = runtime(true, true, 100, plan(task(1))); empty.requestJob(1, one(1), 7, 0);
        wrong = StorageLifecycleCodec.document(empty.captureStorageEvidence()); removeEvent(wrong, "JOB_DATA_READY", 0); bad(wrong);
    }

    @Test void seedsObservationsSequencesAndDuplicateResolutionsCannotBeForged() {
        JsonObject wrong = literal(); removeEvent(wrong, "EXTERNAL_SEEDED", 0); bad(wrong);
        wrong = literal(); payload(wrong, "EXTERNAL_SEEDED", 0).add("location", vm(7)); bad(wrong);
        wrong = literal(); events(wrong).add(event(wrong, "EXTERNAL_SEEDED", 0).deepCopy()); recount(wrong); bad(wrong);
        wrong = literal(); event(wrong, "EXTERNAL_SEEDED", 0).addProperty("observedTime", 1); bad(wrong);
        wrong = literal(); event(wrong, "COPY_ADMITTED", 0).addProperty("sequence", 99); bad(wrong);
        wrong = literal(); event(wrong, "INPUT_RESOLVED", 0).addProperty("observedTime", 1); wrong.getAsJsonObject("capture").addProperty("observedThrough", 1); bad(wrong);
        wrong = literal(); events(wrong).add(event(wrong, "INPUT_RESOLVED", 0).deepCopy()); recount(wrong); bad(wrong);
        wrong = literal(); payload(wrong, "INPUT_RESOLVED", 0).addProperty("resolution", "JOIN_EXISTING"); bad(wrong);
        wrong = literal(); wrong.getAsJsonObject("capture").addProperty("observedThrough", -1); bad(wrong);
        wrong = literal(); event(wrong, "COPY_ADMITTED", 0).addProperty("observedTime", "0"); bad(wrong);
    }

    @Test void actualResidualAndOverflowingStandaloneAreaRemainValidNonAccountingEvidence() {
        CoherentDataflowRuntime r = runtime(true, true, 1000, plan(task(1, in("a", 1), in("b", 1 + 1e-10))));
        r.requestJob(1, one(1), 7, 0); r.advance(1);
        JsonObject root = StorageLifecycleCodec.document(r.captureStorageEvidence());
        assertTrue(payload(root, "COPY_SETTLED", 1).get("remainingAfterService").getAsDouble() > 0);
        r = runtime(true, true, 1000, plan(task(1, in("a", 1e308)), task(2, in("b", 1e308))),
                fabric(false, 10, 100, 100, 100, 100, 100, 100));
        r.requestJob(1, one(1), 7, 0); r.requestJob(2, one(2), 7, 0); r.advance(4e306);
        root = StorageLifecycleCodec.document(r.captureStorageEvidence());
        assertTrue(Double.isInfinite(100 * payload(root, "COPY_SETTLED", 0).get("effectiveTime").getAsDouble()));
        assertEquals(2, StorageLifecycleCodec.decodeDocument(root).getCompletedCopyCount());
        assertEquals("STORAGE_LIFECYCLE_NOT_FLUID_SERVICE_ACCOUNTING_V3", root.get("certificateScope").getAsString());
    }

    @Test void tinyClockLowerBoundKeepsRelativeCapInsteadOfGrantingUnboundedUlpSlack() {
        double tiny = 3 * Double.MIN_VALUE;
        JsonObject valid = settledLiteral(0, tiny, tiny, 0); setBytesAndReadRate(valid, tiny, 1);
        assertDoesNotThrow(() -> StorageLifecycleCodec.decodeDocument(valid));
        JsonObject wrong = valid.deepCopy(); payload(wrong, "COPY_SETTLED", 0).addProperty("effectiveTime", 2 * Double.MIN_VALUE); bad(wrong);
        wrong = literal(); payload(wrong, "COPY_ADMITTED", 0).getAsJsonObject("sourceReplica").addProperty("visibleAt", new BigDecimal("1e-4000")); bad(wrong);
    }

    private static CoherentDataflowRuntime waiting(boolean local) {
        Task p = task(1, out("x", 100)), c = task(2, in("x", 100)); edge(p, c);
        CoherentDataflowRuntime r = runtime(true, true, 1000, plan(p, c));
        produce(r, 10, 1, 7, 0, 1, true); r.requestJob(20, one(2), local ? 7 : 42, 1); return r;
    }
    private static CoherentDataflowRuntime joinedWaiting(boolean local) {
        Task p = task(1, out("x", 100)), a = task(2, in("x", 100)), b = task(3, in("x", 100)); edge(p, a); edge(p, b);
        CoherentDataflowRuntime r = runtime(true, true, 1000, plan(p, a, b));
        produce(r, 1, 1, 7, 0, 1, true); r.requestJob(20, one(2), local ? 7 : 42, 1); r.requestJob(10, one(3), local ? 7 : 42, 1); return r;
    }
    private static CoherentDataflowRuntime runtime(boolean shared, boolean storeInputs, int budget, DataflowFilePlan plan) {
        return runtime(shared, storeInputs, budget, plan, fabric(false, 10, 100, 100, 100, 20, 10, 100));
    }
    private static CoherentDataflowRuntime runtime(boolean shared, boolean storeInputs, int budget, DataflowFilePlan plan, DataTransferFabric fabric) {
        CoherentDataflowRuntime r = CoherentDataflowRuntime.withStorage(shared, budget, storeInputs);
        r.initializePlan(plan, fabric.getLocations()); r.bindFabric(fabric); return r;
    }
    private static DataTransferFabric fabric(boolean tree, int storeHost, double a, double b, double c, double read, double write, double nic) {
        Map<Integer, Integer> placement = new LinkedHashMap<>(); placement.put(10, 0); placement.put(40, 0); placement.put(30, 1); placement.put(20, 3);
        FatTreeTopology topology = tree ? FatTreeTopology.fromSpec(NetworkTopologySpec.fatTree(4, .00002, 3, placement), Arrays.asList(10, 20, 30, 40)) : null;
        Map<Integer, Integer> hosts = new LinkedHashMap<>(); hosts.put(7, 10); hosts.put(42, 20); hosts.put(99, 30);
        return DataTransferFabric.withStorage(capacities(a, b, c), hosts, topology, DataflowStorageSpec.of(storeHost, read / 1e6, write / 1e6, nic / 1e6));
    }
    private static Map<Integer, Integer> hosts() {
        Map<Integer, Integer> hosts = new LinkedHashMap<>(); hosts.put(7, 10); hosts.put(42, 20); hosts.put(99, 30); return hosts;
    }
    private static Map<Integer, Double> capacities(double a, double b, double c) {
        Map<Integer, Double> values = new LinkedHashMap<>(); values.put(7, a); values.put(42, b); values.put(99, c); return values;
    }
    private static void drainCopies(CoherentDataflowRuntime r) {
        int steps = 0; while (r.getNextCompletionTime() != null) { assertTrue(++steps < 100, "bounded fixture must make progress"); r.advance(r.getNextCompletionTime()); }
    }
    private static void produce(CoherentDataflowRuntime r, int job, int task, int vm, double start, double finish, boolean success) {
        r.requestJob(job, one(task), vm, start); assertTrue(r.drainReadyJobIds().contains(job));
        r.cpuStarted(job, one(task), vm, start); r.jobFinished(job, one(task), vm, Collections.singletonList(success), finish);
    }
    private static void finishReady(CoherentDataflowRuntime r, int job, int task, int vm, double start, double finish) {
        assertTrue(r.drainReadyJobIds().contains(job)); r.cpuStarted(job, one(task), vm, start); r.jobFinished(job, one(task), vm, Collections.singletonList(true), finish);
    }
    private static List<Integer> one(int id) { return Collections.singletonList(id); }
    private static DataflowFilePlan plan(Task... tasks) {
        Map<Integer, Integer> scopes = new LinkedHashMap<>(); for (Task task : tasks) scopes.put(task.getCloudletId(), 0); return DataflowFilePlan.capture(Arrays.asList(tasks), scopes);
    }
    private static Task task(int id, FileItem... files) { Task task = new Task(id, 1000); for (FileItem file : files) task.addFile(file); return task; }
    private static FileItem in(String name, double bytes) { return file(name, bytes, FileType.INPUT); }
    private static FileItem out(String name, double bytes) { return file(name, bytes, FileType.OUTPUT); }
    private static FileItem file(String name, double bytes, FileType type) { FileItem file = new FileItem(name, bytes); file.setType(type); return file; }
    private static void edge(Task parent, Task child) { parent.addChild(child); child.addParent(parent); }
    private static JsonObject literal() { return NetworkLedgerCodec.parseDocument(LITERAL); }
    private static JsonArray events(JsonObject root) { return root.getAsJsonArray("events"); }
    private static int index(JsonObject root, String type, int occurrence) {
        for (int i = 0; i < events(root).size(); i++) if (type.equals(events(root).get(i).getAsJsonObject().get("type").getAsString()) && occurrence-- == 0) return i;
        throw new AssertionError("Missing event " + type);
    }
    private static JsonObject event(JsonObject root, String type, int occurrence) { return events(root).get(index(root, type, occurrence)).getAsJsonObject(); }
    private static JsonObject payload(JsonObject root, String type, int occurrence) { return event(root, type, occurrence).getAsJsonObject("payload"); }
    private static List<JsonObject> payloads(JsonObject root, String type) {
        List<JsonObject> result = new ArrayList<>(); for (JsonElement e : events(root)) if (type.equals(e.getAsJsonObject().get("type").getAsString())) result.add(e.getAsJsonObject().getAsJsonObject("payload")); return result;
    }
    private static JsonObject taskRow(JsonObject root, int index) { return root.getAsJsonObject("filePlan").getAsJsonArray("tasks").get(index).getAsJsonObject(); }
    private static JsonObject fileRow(JsonObject root, int index) { return root.getAsJsonObject("filePlan").getAsJsonArray("files").get(index).getAsJsonObject(); }
    private static JsonObject resource(JsonObject root, String key) {
        for (JsonElement row : root.getAsJsonObject("fabric").getAsJsonArray("resources")) if (key.equals(row.getAsJsonObject().get("key").getAsString())) return row.getAsJsonObject();
        throw new AssertionError("Missing resource " + key);
    }
    private static JsonObject vm(int id) { return NetworkLedgerCodec.parseDocument("{\"kind\":\"VM\",\"vmId\":" + id + ",\"sourceId\":null}"); }
    private static JsonObject source() { return NetworkLedgerCodec.parseDocument("{\"kind\":\"SOURCE\",\"vmId\":null,\"sourceId\":\"source\"}"); }
    private static JsonArray arrayOf(String... values) { JsonArray result = new JsonArray(); for (String value : values) result.add(value); return result; }
    private static List<String> strings(JsonArray values) { List<String> result = new ArrayList<>(); for (JsonElement value : values) result.add(value.getAsString()); return result; }
    private static void recount(JsonObject root) {
        for (int i = 0; i < events(root).size(); i++) events(root).get(i).getAsJsonObject().addProperty("sequence", i + 1);
        root.getAsJsonObject("capture").addProperty("retainedRecords", events(root).size());
    }
    private static void removeEvent(JsonObject root, String type, int occurrence) { events(root).remove(event(root, type, occurrence)); recount(root); }
    private static JsonObject prefix(JsonObject root, int count) {
        JsonObject result = root.deepCopy(); while (events(result).size() > count) events(result).remove(events(result).size() - 1); recount(result);
        result.getAsJsonObject("capture").addProperty("observedThrough", count == 0 ? 0 : events(result).get(count - 1).getAsJsonObject().get("observedTime").getAsDouble()); return result;
    }
    private static void append(JsonObject root, String type, double at, String payload) {
        JsonObject event = new JsonObject(); event.addProperty("type", type); event.addProperty("observedTime", at); event.add("payload", NetworkLedgerCodec.parseDocument(payload));
        events(root).add(event); recount(root); root.getAsJsonObject("capture").addProperty("observedThrough", at);
    }
    private static JsonObject settledLiteral(double release, double observed, double effective, double residual) {
        JsonObject root = literal(); for (int i = 1; i < events(root).size(); i++) events(root).get(i).getAsJsonObject().addProperty("observedTime", release);
        append(root, "COPY_SETTLED", observed, "{\"copyOrdinal\":1,\"effectiveTime\":" + effective + ",\"remainingAfterService\":" + residual + "}");
        append(root, "JOB_DATA_READY", observed, "{\"jobId\":0}"); return root;
    }
    private static void setBytesAndReadRate(JsonObject root, double bytes, double rate) {
        fileRow(root, 0).addProperty("bytes", bytes); JsonObject admitted = payload(root, "COPY_ADMITTED", 0);
        admitted.addProperty("bytes", bytes); admitted.addProperty("standaloneRate", rate); admitted.addProperty("isolatedSeconds", bytes / rate);
        resource(root, "STORE:source:READ").addProperty("capacityBytesPerSecond", rate);
        root.getAsJsonObject("fabric").getAsJsonObject("sourceStorage").addProperty("readBandwidthMbPerSecond", rate / 1e6);
    }
    private static void bad(JsonObject root) {
        assertThrows(IllegalArgumentException.class, () -> StorageLifecycleCodec.decodeDocument(root));
        assertThrows(IllegalArgumentException.class, () -> StorageLifecycleCodec.decode(root.toString()));
    }
}
