package org.workflowsim.experiments.workbench;

import static org.junit.jupiter.api.Assertions.*;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.workflowsim.FileItem;
import org.workflowsim.Task;
import org.workflowsim.data.v2.CoherentDataflowRuntime;
import org.workflowsim.data.v2.DataTransferFabric;
import org.workflowsim.data.v2.DataflowFilePlan;
import org.workflowsim.data.v2.DataflowStorageSpec;
import org.workflowsim.data.v2.StorageLifecycleCodec;
import org.workflowsim.network.FatTreeTopology;
import org.workflowsim.network.NetworkTopologySpec;
import org.workflowsim.utils.Parameters.FileType;

/** Real runtime -> independently verified V3 captures; no fabricated report context or service ledger. */
class StorageLifecycleReportViewTest {
    private static final int BUDGET = 10000;
    private static final Gson JSON = new GsonBuilder().serializeNulls().create();

    @Test void absentOffAndEmptyCompleteCaptureStayDistinct() {
        assertNull(StorageLifecycleReportView.fromDecoded(null, false));
        assertNull(StorageLifecycleReportView.fromDecoded(null, true));
        CoherentDataflowRuntime off = runtime(true, true, 0, plan(), fabric(false, 100, 100, 100, 20, 10, 100));
        assertNull(off.captureStorageEvidence());
        JsonObject view = tree(runtime(true, true, plan()));
        assertEquals("workflowsim-storage-lifecycle-display-v3", view.get("schema").getAsString());
        assertEquals("COMPLETE", view.get("captureStatus").getAsString());
        assertFalse(view.get("contextValidated").getAsBoolean());
        assertTrue(view.get("quiescent").getAsBoolean());
        for (String key : Arrays.asList("taskCount", "fileCount", "copyCount", "completedCopyCount", "activeCopyCount",
                "requestedJobCount", "completedJobCount", "pendingOutputFileCount", "waitingStoreInputCount",
                "retainedRecords", "observedThrough", "labelsClipped", "nestedItemsOmitted")) {
            assertEquals("0", view.get(key).getAsString(), key);
        }
        for (JsonElement value : view.getAsJsonObject("totals").asMap().values()) assertEquals("0", value.getAsString());
        assertNulls(view, "lastCpuFinishTime", "lastStoreCommitTime", "completedObservedOutputTailSeconds");
        assertEquals(Integer.toString(BUDGET), view.get("traceBudget").getAsString());
        assertEquals("256", view.get("labelLimit").getAsString());
        assertEquals("12", view.get("nestedListLimit").getAsString());
        checkPreview(view, "copies", 0, 64);
        checkPreview(view, "resources", 6, 64);
        checkPreview(view, "events", 0, 128);
        checkPreview(view, "jobs", 0, 64);
    }

    @Test void projectionIsDeeplyImmutableDetachedAndHasOnlyTheExactDisplaySchema() {
        CoherentDataflowRuntime r = waiting(false);
        JsonObject source = StorageLifecycleCodec.document(r.captureStorageEvidence());
        StorageLifecycleCodec.Decoded decoded = StorageLifecycleCodec.decodeDocument(source);
        String before = JSON.toJson(decoded.getDocument());
        Map<String, Object> projection = StorageLifecycleReportView.fromDecoded(decoded, false);
        String displayed = JSON.toJson(projection);
        assertDeepImmutable(projection);
        source.addProperty("schema", "changed");
        decoded.getDocument().getAsJsonObject("capture").addProperty("observedThrough", 999);
        decoded.getEvidence().getCapture().getEvents().get(0).getPayload().addProperty("jobId", 999);
        r.advance(11);
        assertEquals(before, JSON.toJson(decoded.getDocument()));
        assertEquals(displayed, JSON.toJson(projection));
        assertEquals(projection, StorageLifecycleReportView.fromDecoded(decoded, false));
        JsonObject view = tree(decoded);
        assertEquals(keys("schema", "captureStatus", "contextValidated", "modelKind", "sharingPolicy", "inputAccess",
                "outputCommit", "certificateScope", "traceBudget", "retainedRecords", "observedThrough", "taskCount", "fileCount",
                "copyCount", "completedCopyCount", "activeCopyCount", "requestedJobCount", "completedJobCount",
                "pendingOutputFileCount", "waitingStoreInputCount", "quiescent", "labelLimit", "nestedListLimit", "labelsClipped",
                "nestedItemsOmitted", "store", "totals", "lastCpuFinishTime", "lastStoreCommitTime", "completedObservedOutputTailSeconds",
                "copies", "resources", "events", "jobs"), view.keySet());
        assertEquals(keys("attachmentHostId", "readCapacityBytesPerSecond", "writeCapacityBytesPerSecond", "networkCapacityBytesPerSecond"),
                view.getAsJsonObject("store").keySet());
        assertEquals(keys("admittedInputPayloadBytes", "admittedOutputPayloadBytes", "settledInputPayloadBytes", "settledOutputPayloadBytes",
                "completionResidualBytes", "referenceCount", "localReferenceCount", "joinedReferenceCount", "requiredReferenceBytes", "localReferenceBytes"),
                view.getAsJsonObject("totals").keySet());
        assertEquals(keys("copyOrdinal", "fileId", "source", "destination", "producerTaskId", "producerJobAttemptId", "originLocation",
                "sourceVisibleAt", "admissionTime", "standaloneRate", "isolatedSeconds", "effectiveCompletionTime", "observedCompletionTime",
                "effectiveFctSeconds", "observedFctSeconds", "notificationLagSeconds", "payloadBytes", "completionResidualBytes",
                "status", "resources", "resourcesOmitted", "purpose", "ownerJobId"), row(view, "copies", 0).keySet());
        for (JsonElement value : rows(view, "resources")) {
            assertEquals(keys("resourceKey", "capacityBytesPerSecond"), value.getAsJsonObject().keySet());
        }
        for (JsonElement value : rows(view, "events")) {
            assertEquals(keys("sequence", "type", "observedTime", "jobId", "taskId", "copyOrdinal", "fileId", "resolution", "detail"),
                    value.getAsJsonObject().keySet());
            assertFalse(value.getAsJsonObject().get("detail").getAsString().contains("\"payload\""));
        }
        for (JsonElement value : rows(view, "jobs")) {
            assertEquals(keys("jobId", "taskId", "vmId", "requestedAt", "dataReadyAt", "cpuStartedAt", "finishedAt",
                    "nominalInputSeconds", "observedPreparationSeconds", "storeGateWaitSeconds"), value.getAsJsonObject().keySet());
        }
    }

    @Test void localVersusStoreInputPolicyIsIndependentOfCapacitySharing() {
        for (boolean shared : new boolean[]{false, true}) for (boolean storeInputs : new boolean[]{false, true}) {
            Task p = task(1, out("x", 100)), c = task(2, in("x", 100)); edge(p, c);
            CoherentDataflowRuntime r = runtime(shared, storeInputs, plan(p, c));
            produce(r, 10, 1, 7, 0, 1, true);
            r.requestJob(20, one(2), 7, 1);
            StorageLifecycleCodec.Decoded decoded = decoded(r);
            JsonObject view = tree(decoded);
            assertEquals(shared ? "COHERENT_STORAGE_DATAFLOW_V3" : "COHERENT_STORAGE_DATAFLOW_NO_CONTENTION_V3",
                    view.get("modelKind").getAsString());
            assertEquals(shared ? "SHARED_MAX_MIN" : "ISOLATED_PATH_BOTTLENECK", view.get("sharingPolicy").getAsString());
            assertEquals(storeInputs ? "COMMITTED_STORE_THEN_VM_READ_CACHE_V3" : "LOCAL_VISIBLE_REPLICA_V3",
                    view.get("inputAccess").getAsString());
            assertEquals("ALL_SUCCESSFUL_OUTPUTS_TO_SOURCE_V3", view.get("outputCommit").getAsString());
            assertEquals(decoded.getDocument().get("certificateScope").getAsString(), view.get("certificateScope").getAsString());
            assertEquals("1", view.get("pendingOutputFileCount").getAsString());
            assertEquals(storeInputs ? "1" : "0", view.get("waitingStoreInputCount").getAsString());
            assertEquals("1", view.getAsJsonObject("totals").get("referenceCount").getAsString());
            assertEquals("100", view.getAsJsonObject("totals").get("requiredReferenceBytes").getAsString());
            if (storeInputs) assertNulls(job(view, 20), "dataReadyAt", "nominalInputSeconds", "storeGateWaitSeconds");
            else {
                assertEquals("1", job(view, 20).get("dataReadyAt").getAsString());
                assertEquals("0", job(view, 20).get("nominalInputSeconds").getAsString());
                assertEquals("0", job(view, 20).get("storeGateWaitSeconds").getAsString());
            }
        }
    }

    @Test void successfulUnusedPositiveAndZeroOutputsContributeOnlyObservedCommits() {
        CoherentDataflowRuntime r = runtime(true, true, plan(task(1, out("unused", 100), out("zero-unused", 0))));
        produce(r, 10, 1, 7, 0, 1, true);
        JsonObject active = tree(r);
        assertEquals("1", active.get("completedJobCount").getAsString());
        assertEquals("1", active.get("pendingOutputFileCount").getAsString());
        assertEquals("1", active.get("activeCopyCount").getAsString());
        assertEquals("1", active.get("lastCpuFinishTime").getAsString());
        assertEquals("1", active.get("lastStoreCommitTime").getAsString(), "ZERO publishes at OUTPUT_RESOLVED");
        assertNulls(active, "completedObservedOutputTailSeconds");
        assertFalse(active.get("quiescent").getAsBoolean());
        assertEquals("ZERO", event(rows(active, "events"), "OUTPUT_RESOLVED", 1).get("resolution").getAsString());
        assertEquals("OUTPUT", row(active, "copies", 0).get("purpose").getAsString());
        assertEquals("SOURCE:source", row(active, "copies", 0).get("destination").getAsString());
        assertEquals("VM:7", row(active, "copies", 0).get("source").getAsString());
        assertEquals("10", row(active, "copies", 0).get("ownerJobId").getAsString());
        r.advance(20);
        JsonObject view = tree(r), copy = row(view, "copies", 0), totals = view.getAsJsonObject("totals");
        assertTrue(view.get("quiescent").getAsBoolean());
        assertEquals("0", view.get("pendingOutputFileCount").getAsString());
        assertEquals("20", view.get("lastStoreCommitTime").getAsString());
        assertEquals("19", view.get("completedObservedOutputTailSeconds").getAsString());
        assertEquals("11", copy.get("effectiveCompletionTime").getAsString());
        assertEquals("20", copy.get("observedCompletionTime").getAsString());
        assertEquals("9", copy.get("notificationLagSeconds").getAsString());
        assertEquals("100", totals.get("admittedOutputPayloadBytes").getAsString());
        assertEquals("100", totals.get("settledOutputPayloadBytes").getAsString());
        assertEquals("0", totals.get("admittedInputPayloadBytes").getAsString());
        assertEquals("0", totals.get("referenceCount").getAsString());
    }

    @Test void anUnusedZeroOutputNeedsNoPositiveCopyAndHasAZeroObservedTail() {
        CoherentDataflowRuntime r = runtime(false, false, plan(task(1, out("zero", 0))));
        produce(r, 1, 1, 7, .1, .3, true);
        JsonObject view = tree(r);
        assertTrue(view.get("quiescent").getAsBoolean());
        assertEquals("0", view.get("copyCount").getAsString());
        assertEquals("0", view.get("pendingOutputFileCount").getAsString());
        assertEquals(exact(.3), view.get("lastCpuFinishTime").getAsString());
        assertEquals(exact(.3), view.get("lastStoreCommitTime").getAsString());
        assertEquals("0", view.get("completedObservedOutputTailSeconds").getAsString());
        assertEquals("ZERO", event(rows(view, "events"), "OUTPUT_RESOLVED", 0).get("resolution").getAsString());
    }

    @Test void externalSeedsAndFailedOutputsAreNotSuccessfulStoreCommits() {
        CoherentDataflowRuntime r = runtime(true, true, plan(task(1, in("external", 40))));
        JsonObject seeds = tree(r);
        assertTrue(seeds.get("quiescent").getAsBoolean());
        assertNulls(seeds, "lastCpuFinishTime", "lastStoreCommitTime", "completedObservedOutputTailSeconds");
        r.requestJob(10, one(1), 7, 0); r.advance(4); finish(r, 10, 1, 7, 4, 5, true);
        JsonObject finished = tree(r);
        assertEquals("5", finished.get("lastCpuFinishTime").getAsString());
        assertNulls(finished, "lastStoreCommitTime", "completedObservedOutputTailSeconds");
        assertNulls(row(finished, "copies", 0), "producerTaskId", "producerJobAttemptId");
        assertEquals("SOURCE:source", row(finished, "copies", 0).get("originLocation").getAsString());
        CoherentDataflowRuntime failed = runtime(true, true, plan(task(1, out("x", 100), out("zero", 0))));
        produce(failed, 8, 1, 7, 0, 1, false);
        JsonObject failure = tree(failed);
        assertTrue(failure.get("quiescent").getAsBoolean());
        assertEquals("0", failure.get("copyCount").getAsString());
        assertEquals("0", failure.get("pendingOutputFileCount").getAsString());
        assertEquals("1", failure.get("lastCpuFinishTime").getAsString());
        assertNulls(failure, "lastStoreCommitTime", "completedObservedOutputTailSeconds");
        assertTrue(event(rows(failure, "events"), "TASK_FINISHED", 0).get("detail").getAsString().contains("success=false"));
    }

    @Test void localConsumersMayFinishCpuBeforeTheAsynchronousUploadTail() {
        Task p = task(1, out("x", 100)), c = task(2, in("x", 100)); edge(p, c);
        CoherentDataflowRuntime r = runtime(true, false, BUDGET, plan(p, c), fabric(false, 100, 100, 100, 100, 1, 100));
        produce(r, 1, 1, 7, 0, 1, true); r.requestJob(2, one(2), 42, 1);
        r.advance(3); finish(r, 2, 2, 42, 3, 4, true);
        JsonObject prefix = tree(r);
        assertEquals("2", prefix.get("completedJobCount").getAsString());
        assertEquals("1", prefix.get("pendingOutputFileCount").getAsString());
        assertEquals("0", prefix.get("waitingStoreInputCount").getAsString());
        assertEquals("4", prefix.get("lastCpuFinishTime").getAsString());
        assertNulls(prefix, "lastStoreCommitTime", "completedObservedOutputTailSeconds");
        assertEquals("VM:7", row(prefix, "copies", 1).get("source").getAsString());
        assertEquals("0", job(prefix, 2).get("storeGateWaitSeconds").getAsString());
        r.advance(200);
        JsonObject view = tree(r);
        assertTrue(view.get("quiescent").getAsBoolean());
        assertEquals("196", view.get("completedObservedOutputTailSeconds").getAsString(), "tail starts at the last CPU, not just the producer");
    }

    @Test void sourceCommitEndsTheStoreGateButIsNotVmDataReadiness() {
        CoherentDataflowRuntime r = waiting(false);
        JsonObject waiting = tree(r), before = job(waiting, 20);
        assertEquals("1", waiting.get("waitingStoreInputCount").getAsString());
        assertEquals("100", waiting.getAsJsonObject("totals").get("requiredReferenceBytes").getAsString());
        assertNulls(before, "dataReadyAt", "cpuStartedAt", "finishedAt", "nominalInputSeconds", "observedPreparationSeconds", "storeGateWaitSeconds");
        r.advance(11);
        assertTrue(r.drainReadyJobIds().isEmpty());
        JsonObject committed = tree(r), reading = job(committed, 20);
        assertEquals("0", committed.get("waitingStoreInputCount").getAsString());
        assertEquals("0", committed.get("pendingOutputFileCount").getAsString());
        assertEquals("1", committed.get("activeCopyCount").getAsString());
        assertEquals("10", reading.get("storeGateWaitSeconds").getAsString());
        assertEquals("5", reading.get("nominalInputSeconds").getAsString());
        assertNulls(reading, "dataReadyAt", "observedPreparationSeconds");
        assertEquals("SOURCE:source", row(committed, "copies", 1).get("source").getAsString());
        assertEquals("VM:42", row(committed, "copies", 1).get("destination").getAsString());
        assertEquals("11", committed.get("lastStoreCommitTime").getAsString());
        assertNulls(committed, "completedObservedOutputTailSeconds");
        r.advance(16); finish(r, 20, 2, 42, 16, 17, true);
        JsonObject view = tree(r), ready = job(view, 20);
        assertEquals("1", ready.get("requestedAt").getAsString());
        assertEquals("16", ready.get("dataReadyAt").getAsString());
        assertEquals("16", ready.get("cpuStartedAt").getAsString());
        assertEquals("17", ready.get("finishedAt").getAsString());
        assertEquals("15", ready.get("observedPreparationSeconds").getAsString());
        assertEquals("10", ready.get("storeGateWaitSeconds").getAsString());
        assertEquals(exact(r.getPreparation(20).getIsolatedSeconds()), ready.get("nominalInputSeconds").getAsString());
        assertEquals("1", view.getAsJsonObject("totals").get("referenceCount").getAsString(), "deferred resolution is not another reference");
        assertEquals("11", view.get("lastStoreCommitTime").getAsString(), "later INPUT settlement is not a store commit");
        assertEquals("17", view.get("lastCpuFinishTime").getAsString());
        assertEquals("0", view.get("completedObservedOutputTailSeconds").getAsString());
    }

    @Test void producerLocalCacheHitStillWaitsForStoreCommitUnderStoreInputPolicy() {
        CoherentDataflowRuntime r = waiting(true);
        assertNulls(job(tree(r), 20), "dataReadyAt", "nominalInputSeconds", "storeGateWaitSeconds");
        r.advance(11);
        JsonObject view = tree(r), job = job(view, 20), totals = view.getAsJsonObject("totals");
        assertEquals("1", view.get("copyCount").getAsString());
        assertEquals("0", view.get("activeCopyCount").getAsString());
        assertEquals("0", view.get("waitingStoreInputCount").getAsString());
        assertEquals("11", job.get("dataReadyAt").getAsString());
        assertEquals("10", job.get("observedPreparationSeconds").getAsString());
        assertEquals("10", job.get("storeGateWaitSeconds").getAsString());
        assertEquals("0", job.get("nominalInputSeconds").getAsString());
        assertEquals("LOCAL", event(rows(view, "events"), "INPUT_RESOLVED", 0).get("resolution").getAsString());
        assertEquals("1", totals.get("localReferenceCount").getAsString());
        assertEquals("100", totals.get("localReferenceBytes").getAsString());
        assertEquals("100", totals.get("requiredReferenceBytes").getAsString());
        assertEquals("0", totals.get("admittedInputPayloadBytes").getAsString());
    }

    @Test void overlappingDeferredFilesUseMaximumGateWaitAndRemainNullUntilAllResolve() {
        Task p = task(1, out("a", 200), out("b", 20));
        Task c = task(2, in("a", 200), in("a", 200), in("b", 20), in("b", 20)); edge(p, c);
        CoherentDataflowRuntime r = runtime(true, true, plan(p, c));
        produce(r, 10, 1, 7, 0, 1, true); r.requestJob(20, one(2), 42, 1);
        JsonObject initial = tree(r);
        assertEquals("2", initial.get("waitingStoreInputCount").getAsString());
        assertEquals("4", initial.getAsJsonObject("totals").get("referenceCount").getAsString());
        assertEquals("440", initial.getAsJsonObject("totals").get("requiredReferenceBytes").getAsString());
        r.advance(5);
        JsonObject partial = tree(r);
        assertEquals("1", partial.get("waitingStoreInputCount").getAsString());
        assertEquals("input[0]/b", event(rows(partial, "events"), "INPUT_RESOLVED", 0).get("fileId").getAsString());
        assertNulls(job(partial, 20), "nominalInputSeconds", "storeGateWaitSeconds", "observedPreparationSeconds");
        r.advance(24);
        JsonObject resolved = tree(r);
        assertEquals("input[0]/a", event(rows(resolved, "events"), "INPUT_RESOLVED", 1).get("fileId").getAsString());
        assertEquals("23", job(resolved, 20).get("storeGateWaitSeconds").getAsString(), "max(5-1,24-1), not the overlapping sum 27");
        assertEquals("11", job(resolved, 20).get("nominalInputSeconds").getAsString(), "full copies, not multiplied references");
        assertEquals("4", resolved.getAsJsonObject("totals").get("referenceCount").getAsString());
        assertEquals("440", resolved.getAsJsonObject("totals").get("requiredReferenceBytes").getAsString());
        r.advance(34);
        JsonObject ready = tree(r);
        assertEquals("33", job(ready, 20).get("observedPreparationSeconds").getAsString());
        assertEquals("23", job(ready, 20).get("storeGateWaitSeconds").getAsString());
        assertEquals(exact(r.getPreparation(20).getIsolatedSeconds()), job(ready, 20).get("nominalInputSeconds").getAsString());
    }

    @Test void copyFctPreparationAndStoreGateSubtractExactBinary64Timestamps() {
        Task p = task(1, out("x", 1)), c = task(2, in("x", 1)); edge(p, c);
        CoherentDataflowRuntime r = runtime(true, true, BUDGET, plan(p, c), fabric(false, 1, 1, 1, 1, 1, 1));
        produce(r, 10, 1, 7, .05, .1, true); r.requestJob(20, one(2), 42, .3);
        r.advance(2.3); r.advance(3.4);
        StorageLifecycleCodec.Decoded decoded = decoded(r);
        JsonArray raw = decoded.getDocument().getAsJsonArray("events");
        JsonObject view = tree(decoded), job = job(view, 20);
        assertEquals(exact(.3), job.get("requestedAt").getAsString());
        assertEquals(exact(3.4), job.get("dataReadyAt").getAsString());
        assertEquals(difference(2.3, .3), job.get("storeGateWaitSeconds").getAsString());
        assertEquals(difference(3.4, .3), job.get("observedPreparationSeconds").getAsString());
        assertNotEquals(exact(2.3 - .3), job.get("storeGateWaitSeconds").getAsString());
        for (int i = 0; i < 2; i++) {
            double admission = event(raw, "COPY_ADMITTED", i).get("observedTime").getAsDouble();
            JsonObject settled = event(raw, "COPY_SETTLED", i);
            double effective = settled.getAsJsonObject("payload").get("effectiveTime").getAsDouble();
            double observed = settled.get("observedTime").getAsDouble();
            JsonObject copy = row(view, "copies", i);
            assertEquals(exact(admission), copy.get("admissionTime").getAsString());
            assertEquals(exact(effective), copy.get("effectiveCompletionTime").getAsString());
            assertEquals(exact(observed), copy.get("observedCompletionTime").getAsString());
            assertEquals(difference(effective, admission), copy.get("effectiveFctSeconds").getAsString());
            assertEquals(difference(observed, admission), copy.get("observedFctSeconds").getAsString());
            assertEquals(difference(observed, effective), copy.get("notificationLagSeconds").getAsString());
        }
    }

    @Test void nominalInputSecondsUseOrderedRuntimeBinary64SumRatherThanExactAddition() {
        double large = Math.scalb(1.0, 53);
        CoherentDataflowRuntime r = runtime(true, true, BUDGET,
                plan(task(1, in("a-large", large), in("b-small", 1), in("c-small", 1))),
                fabric(false, 1e15, 1e15, 1e15, 1, 1e15, 1e15));
        CoherentDataflowRuntime.Preparation preparation = r.requestJob(10, one(1), 7, 0);
        JsonObject view = tree(r), job = job(view, 10);
        double ordered = large; ordered += 1; ordered += 1;
        assertEquals(exact(ordered), job.get("nominalInputSeconds").getAsString());
        assertEquals(exact(preparation.getIsolatedSeconds()), job.get("nominalInputSeconds").getAsString());
        assertNotEquals(text(new BigDecimal(large).add(BigDecimal.valueOf(2))), job.get("nominalInputSeconds").getAsString());
        assertEquals(text(new BigDecimal(large).add(BigDecimal.valueOf(2))), view.getAsJsonObject("totals").get("requiredReferenceBytes").getAsString(),
                "reference bytes are exact even though runtime nominal seconds intentionally use binary64 accumulation");
        assertNulls(job, "dataReadyAt", "observedPreparationSeconds");
        assertEquals("0", job.get("storeGateWaitSeconds").getAsString());
    }

    @Test void deferredNominalAdditionFollowsResolutionOrderNotCanonicalFileOrder() {
        double large = Math.scalb(1.0, 53);
        Task p = task(1, out("a-large", large));
        Task c = task(2, in("a-large", large), in("b-small", 1), in("c-small", 1)); edge(p, c);
        CoherentDataflowRuntime r = runtime(true, true, BUDGET, plan(p, c), fabric(false, 1e15, 1e15, 1e15, 1, 1e15, 1e15));
        produce(r, 10, 1, 7, 0, 1, true); r.requestJob(20, one(2), 42, 1);
        assertNulls(job(tree(r), 20), "nominalInputSeconds", "storeGateWaitSeconds");
        r.advance(20); drainCopies(r);
        JsonObject view = tree(r);
        String[] order = {"input[0]/b-small", "input[0]/c-small", "input[0]/a-large"};
        for (int i = 0; i < order.length; i++) assertEquals(order[i], event(rows(view, "events"), "INPUT_RESOLVED", i).get("fileId").getAsString());
        double actualOrder = 0; actualOrder += 1; actualOrder += 1; actualOrder += large;
        double canonicalOrder = large; canonicalOrder += 1; canonicalOrder += 1;
        assertNotEquals(actualOrder, canonicalOrder);
        assertEquals(exact(actualOrder), job(view, 20).get("nominalInputSeconds").getAsString());
        assertEquals(exact(r.getPreparation(20).getIsolatedSeconds()), job(view, 20).get("nominalInputSeconds").getAsString());
        assertEquals("19", job(view, 20).get("storeGateWaitSeconds").getAsString());
    }

    @Test void holderProducerAndCopyOwnerRemainDistinctAcrossStoreAndVmCaches() {
        Task p = task(1, out("x", 100)), a = task(2, in("x", 100)), b = task(3, in("x", 100)); edge(p, a); edge(p, b);
        CoherentDataflowRuntime r = runtime(true, false, BUDGET, plan(p, a, b), fabric(false, 10, 100, 100, 20, 10, 100));
        produce(r, 10, 1, 7, 0, 1, true); r.advance(11);
        r.requestJob(20, one(2), 99, 11); r.advance(16); finish(r, 20, 2, 99, 16, 17, true);
        r.requestJob(30, one(3), 42, 17);
        JsonObject view = tree(r), storeRead = row(view, "copies", 1), cacheRead = row(view, "copies", 2);
        assertEquals("SOURCE:source", storeRead.get("source").getAsString());
        assertEquals("11", storeRead.get("sourceVisibleAt").getAsString());
        assertEquals("20", storeRead.get("ownerJobId").getAsString());
        assertEquals("VM:99", cacheRead.get("source").getAsString());
        assertEquals("VM:42", cacheRead.get("destination").getAsString());
        assertEquals("16", cacheRead.get("sourceVisibleAt").getAsString());
        assertEquals("30", cacheRead.get("ownerJobId").getAsString());
        assertEquals("100", cacheRead.get("standaloneRate").getAsString());
        for (JsonObject copy : Arrays.asList(storeRead, cacheRead)) {
            assertEquals("1", copy.get("producerTaskId").getAsString());
            assertEquals("10", copy.get("producerJobAttemptId").getAsString());
            assertEquals("VM:7", copy.get("originLocation").getAsString());
            assertEquals("INPUT", copy.get("purpose").getAsString());
        }
        String detail = event(rows(view, "events"), "COPY_ADMITTED", 2).get("detail").getAsString();
        assertTrue(detail.contains("ownerJobId=30; source=VM:99"));
        assertTrue(detail.contains("producerTaskId=1; producerJobAttemptId=10; originLocation=VM:7"));
    }

    @Test void aLateJoiningReaderGetsTheFullNominalNotTheRemainingCopyDuration() {
        CoherentDataflowRuntime r = runtime(true, true, plan(task(1, in("x", 100)), task(2, in("x", 100))));
        r.requestJob(20, one(1), 42, 0); r.requestJob(10, one(2), 42, 2); r.advance(6);
        JsonObject view = tree(r), totals = view.getAsJsonObject("totals");
        assertEquals("1", view.get("copyCount").getAsString());
        assertEquals("20", row(view, "copies", 0).get("ownerJobId").getAsString());
        assertEquals("5", job(view, 20).get("nominalInputSeconds").getAsString());
        assertEquals("5", job(view, 10).get("nominalInputSeconds").getAsString(), "joining at t=2 does not replace the full estimate by three remaining seconds");
        assertEquals(exact(r.getPreparation(10).getIsolatedSeconds()), job(view, 10).get("nominalInputSeconds").getAsString());
        assertEquals("6", job(view, 20).get("observedPreparationSeconds").getAsString());
        assertEquals("4", job(view, 10).get("observedPreparationSeconds").getAsString());
        assertEquals("0", job(view, 10).get("storeGateWaitSeconds").getAsString());
        assertEquals("JOIN_EXISTING", event(rows(view, "events"), "INPUT_RESOLVED", 1).get("resolution").getAsString());
        assertEquals("1", totals.get("joinedReferenceCount").getAsString());
        assertEquals("200", totals.get("requiredReferenceBytes").getAsString());
        assertEquals("100", totals.get("settledInputPayloadBytes").getAsString());
    }

    @Test void settlementsJoinExactOrdinalsRatherThanFileNameOrCompletionOrder() {
        CoherentDataflowRuntime r = runtime(true, true, BUDGET, plan(task(1, in("same", 10)), task(2, in("same", 10))),
                fabric(false, 5, 10, 100, 100, 100, 100));
        r.requestJob(11, one(1), 7, 0); r.requestJob(22, one(2), 42, 0); r.advance(3);
        JsonObject view = tree(r), first = row(view, "copies", 0), second = row(view, "copies", 1);
        assertEquals("1", first.get("copyOrdinal").getAsString());
        assertEquals("VM:7", first.get("destination").getAsString());
        assertEquals("2", first.get("effectiveCompletionTime").getAsString());
        assertEquals("1", first.get("notificationLagSeconds").getAsString());
        assertEquals("2", second.get("copyOrdinal").getAsString());
        assertEquals("VM:42", second.get("destination").getAsString());
        assertEquals("1", second.get("effectiveCompletionTime").getAsString());
        assertEquals("2", second.get("notificationLagSeconds").getAsString());
        JsonObject settled = event(rows(view, "events"), "COPY_SETTLED", 0);
        assertEquals("2", settled.get("copyOrdinal").getAsString());
        assertEquals("22", settled.get("jobId").getAsString());
        assertEquals("input[0]/same", settled.get("fileId").getAsString());
        assertTrue(settled.get("detail").getAsString().contains("purpose=INPUT; ownerJobId=22"));
        assertNulls(view, "lastStoreCommitTime");
    }

    @Test void deferredReadersCoalesceInRequestOrderAndEachGetsTheFullCopyNominal() {
        Task p = task(1, out("x", 100));
        Task a = task(2, in("x", 100), in("x", 100)), b = task(3, in("x", 100), in("x", 100), in("x", 100)); edge(p, a); edge(p, b);
        CoherentDataflowRuntime r = runtime(true, true, plan(p, a, b));
        produce(r, 1, 1, 7, 0, 1, true); r.requestJob(20, one(2), 42, 1); r.requestJob(10, one(3), 42, 1);
        JsonObject prefix = tree(r);
        assertEquals("2", prefix.get("waitingStoreInputCount").getAsString(), "normalized (Job,file), not five references");
        assertEquals("5", prefix.getAsJsonObject("totals").get("referenceCount").getAsString());
        assertEquals("500", prefix.getAsJsonObject("totals").get("requiredReferenceBytes").getAsString());
        r.advance(11); r.advance(16);
        JsonObject view = tree(r), input = row(view, "copies", 1), totals = view.getAsJsonObject("totals");
        assertEquals("2", view.get("copyCount").getAsString());
        assertEquals("20", input.get("ownerJobId").getAsString());
        assertEquals("1", input.get("producerJobAttemptId").getAsString());
        assertEquals("5", totals.get("referenceCount").getAsString());
        assertEquals("3", totals.get("joinedReferenceCount").getAsString());
        assertEquals("500", totals.get("requiredReferenceBytes").getAsString());
        assertEquals("100", totals.get("admittedInputPayloadBytes").getAsString());
        assertEquals("100", totals.get("settledInputPayloadBytes").getAsString());
        assertEquals("JOIN_EXISTING", event(rows(view, "events"), "INPUT_RESOLVED", 1).get("resolution").getAsString());
        for (int id : new int[]{20, 10}) {
            assertEquals("5", job(view, id).get("nominalInputSeconds").getAsString());
            assertEquals(exact(r.getPreparation(id).getIsolatedSeconds()), job(view, id).get("nominalInputSeconds").getAsString());
            assertEquals("10", job(view, id).get("storeGateWaitSeconds").getAsString());
            assertEquals("15", job(view, id).get("observedPreparationSeconds").getAsString());
        }
        assertEquals("20", row(view, "jobs", 1).get("jobId").getAsString());
        assertEquals("10", row(view, "jobs", 2).get("jobId").getAsString());
        JsonObject settled = event(rows(view, "events"), "COPY_SETTLED", 1);
        assertEquals("20", settled.get("jobId").getAsString());
        assertNulls(settled, "taskId");
        assertTrue(settled.get("detail").getAsString().contains("purpose=INPUT; ownerJobId=20"));
    }

    @Test void failedRetryAndRepeatedOutputsPreserveFirstCommitAndOriginalProducer() {
        Task p = task(1, out("x", 100), out("zero", 0)), c = task(2, in("x", 100)); edge(p, c);
        CoherentDataflowRuntime r = runtime(true, true, plan(p, c));
        produce(r, 8, 1, 7, 0, 1, false);
        produce(r, 9, 1, 42, 2, 3, true);
        produce(r, 10, 1, 7, 4, 5, true);
        JsonObject joined = tree(r);
        assertEquals("1", joined.get("copyCount").getAsString());
        assertEquals("9", row(joined, "copies", 0).get("ownerJobId").getAsString());
        assertEquals("9", row(joined, "copies", 0).get("producerJobAttemptId").getAsString());
        assertEquals("JOIN_EXISTING", event(rows(joined, "events"), "OUTPUT_RESOLVED", 2).get("resolution").getAsString());
        assertTrue(event(rows(joined, "events"), "OUTPUT_RESOLVED", 2).get("detail").getAsString().contains("source=VM:7"));
        drainCopies(r);
        JsonObject committed = tree(r);
        assertEquals("13", committed.get("lastStoreCommitTime").getAsString());
        assertEquals("8", committed.get("completedObservedOutputTailSeconds").getAsString());
        produce(r, 11, 1, 7, 14, 15, true);
        JsonObject repeated = tree(r);
        assertEquals("ALREADY_STORED", event(rows(repeated, "events"), "OUTPUT_RESOLVED", 4).get("resolution").getAsString());
        assertEquals("13", repeated.get("lastStoreCommitTime").getAsString(), "ALREADY_STORED must not shift the successful commit observation");
        assertEquals("15", repeated.get("lastCpuFinishTime").getAsString());
        assertEquals("0", repeated.get("completedObservedOutputTailSeconds").getAsString());
        assertEquals("100", repeated.getAsJsonObject("totals").get("admittedOutputPayloadBytes").getAsString());
        assertEquals("100", repeated.getAsJsonObject("totals").get("settledOutputPayloadBytes").getAsString());
        assertEquals("1", job(repeated, 8).get("finishedAt").getAsString());
        r.requestJob(12, one(2), 99, 15);
        JsonObject read = row(tree(r), "copies", 1);
        assertEquals("12", read.get("ownerJobId").getAsString());
        assertEquals("9", read.get("producerJobAttemptId").getAsString());
        assertEquals("VM:42", read.get("originLocation").getAsString());
        assertEquals("SOURCE:source", read.get("source").getAsString());
        assertEquals("13", read.get("sourceVisibleAt").getAsString());
    }

    @Test void localZeroAndJoinedReferenceMultiplicitiesUseExactDeclaredFileBytes() {
        Task a = task(1, in("x", .1), in("x", .1), in("zero", 0));
        Task b = task(2, in("x", .1), in("x", .1), in("x", .1), in("zero", 0), in("zero", 0));
        Task c = task(3, in("x", .1), in("x", .1), in("zero", 0));
        CoherentDataflowRuntime r = runtime(true, true, BUDGET, plan(a, b, c), fabric(false, 1, 1, 1, 1, 1, 1));
        r.requestJob(11, one(1), 7, 0); r.requestJob(22, one(2), 7, 0); r.advance(1); r.requestJob(33, one(3), 7, 1);
        JsonObject view = tree(r), totals = view.getAsJsonObject("totals");
        assertEquals("1", view.get("copyCount").getAsString());
        assertEquals("11", totals.get("referenceCount").getAsString());
        assertEquals("5", totals.get("localReferenceCount").getAsString());
        assertEquals("3", totals.get("joinedReferenceCount").getAsString());
        assertEquals(text(new BigDecimal(.1).multiply(BigDecimal.valueOf(7))), totals.get("requiredReferenceBytes").getAsString());
        assertEquals(text(new BigDecimal(.1).multiply(BigDecimal.valueOf(2))), totals.get("localReferenceBytes").getAsString());
        assertEquals(exact(.1), totals.get("admittedInputPayloadBytes").getAsString());
        assertEquals(exact(.1), totals.get("settledInputPayloadBytes").getAsString());
        assertEquals(exact(.1), job(view, 11).get("nominalInputSeconds").getAsString());
        assertEquals(exact(.1), job(view, 22).get("nominalInputSeconds").getAsString());
        assertEquals("0", job(view, 33).get("nominalInputSeconds").getAsString());
        String[] resolutions = {"NEW_COPY", "ZERO", "JOIN_EXISTING", "LOCAL", "LOCAL", "LOCAL"};
        for (int i = 0; i < resolutions.length; i++) assertEquals(resolutions[i], event(rows(view, "events"), "INPUT_RESOLVED", i).get("resolution").getAsString());
    }

    @Test void storeInventoryAndDirectedPathsExposeReadWriteAndNicWithoutInventedUsage() {
        CoherentDataflowRuntime r = runtime(true, true, BUDGET, plan(task(1, in("input", 40), out("output", 40))),
                fabric(true, 100, 100, 100, 30, 10, 100));
        r.requestJob(8, one(1), 42, 0); drainCopies(r);
        double at = r.getCurrentTime(); finish(r, 8, 1, 42, at, at + 1, true);
        StorageLifecycleCodec.Decoded decoded = decoded(r);
        JsonObject view = tree(decoded), store = view.getAsJsonObject("store");
        assertEquals("10", store.get("attachmentHostId").getAsString());
        assertEquals("30", store.get("readCapacityBytesPerSecond").getAsString());
        assertEquals("10", store.get("writeCapacityBytesPerSecond").getAsString());
        assertEquals("100", store.get("networkCapacityBytesPerSecond").getAsString());
        assertEquals(Arrays.asList("STORE:source:READ", "STORE:source:NIC", "LINK:ACC:10->EDGE:0:0",
                "LINK:EDGE:0:0->AGG:0:0", "LINK:AGG:0:0->CORE:0", "LINK:CORE:0->AGG:1:0",
                "LINK:AGG:1:0->EDGE:1:1", "LINK:EDGE:1:1->ACC:20", "VM:42"), strings(row(view, "copies", 0).getAsJsonArray("resources")));
        assertEquals(Arrays.asList("VM:42", "LINK:ACC:20->EDGE:1:1", "LINK:EDGE:1:1->AGG:1:1",
                "LINK:AGG:1:1->CORE:2", "LINK:CORE:2->AGG:0:1", "LINK:AGG:0:1->EDGE:0:0",
                "LINK:EDGE:0:0->ACC:10", "STORE:source:NIC", "STORE:source:WRITE"), strings(row(view, "copies", 1).getAsJsonArray("resources")));
        assertEquals("20", row(view, "copies", 0).get("standaloneRate").getAsString());
        assertEquals("10", row(view, "copies", 1).get("standaloneRate").getAsString());
        for (JsonElement value : rows(view, "resources")) {
            JsonObject resource = value.getAsJsonObject();
            assertEquals(keys("resourceKey", "capacityBytesPerSecond"), resource.keySet());
            assertEquals(exact(rawResource(decoded.getDocument(), resource.get("resourceKey").getAsString()).get("capacityBytesPerSecond").getAsDouble()),
                    resource.get("capacityBytesPerSecond").getAsString());
        }
        assertEquals("0", view.get("nestedItemsOmitted").getAsString());
        assertEquals(12, StorageLifecycleReportView.LIST_LIMIT);
        for (JsonElement copy : rows(view, "copies")) assertTrue(copy.getAsJsonObject().getAsJsonArray("resources").size() <= 12);
    }

    @Test void sameHostAndEndpointOnlyTransfersStillUseAllThreePhysicalConstraints() {
        for (boolean tree : new boolean[]{false, true}) {
            CoherentDataflowRuntime r = runtime(false, false, BUDGET, plan(task(1, in("i", 40), out("o", 40))),
                    fabric(tree, 100, 100, 100, 30, 20, 10));
            r.requestJob(1, one(1), 7, 0); drainCopies(r);
            double at = r.getCurrentTime(); finish(r, 1, 1, 7, at, at + 1, true);
            JsonObject view = tree(r);
            assertEquals(Arrays.asList("STORE:source:READ", "STORE:source:NIC", "VM:7"), strings(row(view, "copies", 0).getAsJsonArray("resources")));
            assertEquals(Arrays.asList("VM:7", "STORE:source:NIC", "STORE:source:WRITE"), strings(row(view, "copies", 1).getAsJsonArray("resources")));
            assertEquals("10", row(view, "copies", 0).get("standaloneRate").getAsString());
            assertEquals("10", row(view, "copies", 1).get("standaloneRate").getAsString());
        }
    }

    @Test void storeCapacityStringsComeFromRecordedBinary64InventoryNotRoundedMbLabels() {
        double mb = Math.nextUp(Double.MIN_NORMAL / 1e6), capacity = mb * 1e6;
        DataflowStorageSpec spec = DataflowStorageSpec.of(10, mb, .0000125, .00000325);
        DataTransferFabric fabric = DataTransferFabric.withStorage(capacities(capacity, capacity, capacity), hosts(), null, spec);
        JsonObject view = tree(runtime(false, true, BUDGET, plan(), fabric)), store = view.getAsJsonObject("store");
        assertEquals(exact(capacity), store.get("readCapacityBytesPerSecond").getAsString());
        assertEquals(exact(spec.getWriteCapacityBytesPerSecond()), store.get("writeCapacityBytesPerSecond").getAsString());
        assertEquals(exact(spec.getNetworkCapacityBytesPerSecond()), store.get("networkCapacityBytesPerSecond").getAsString());
        assertTrue(store.get("readCapacityBytesPerSecond").getAsString().length() > StorageLifecycleReportView.LABEL_LIMIT);
        assertEquals("0", view.get("labelsClipped").getAsString());
    }

    @Test void completePrefixesAndUnrequestedTasksDoNotBecomeClaimsAboutThisRun() {
        Task p = task(1, out("x", 100)), c = task(2, in("x", 100)), unrequested = task(3, out("not-run", 100)); edge(p, c);
        CoherentDataflowRuntime r = runtime(true, true, plan(p, c, unrequested));
        JsonObject initial = tree(r);
        assertTrue(initial.get("quiescent").getAsBoolean());
        assertEquals("0", initial.get("requestedJobCount").getAsString());
        assertEquals("0", initial.get("pendingOutputFileCount").getAsString());
        checkPreview(initial, "jobs", 0, 64);
        produce(r, 10, 1, 7, 0, 1, true); r.requestJob(20, one(2), 42, 1);
        StorageLifecycleCodec.Decoded decoded = decoded(r);
        JsonObject prefix = tree(decoded);
        assertEquals("COMPLETE", prefix.get("captureStatus").getAsString());
        assertFalse(prefix.get("contextValidated").getAsBoolean());
        assertFalse(prefix.get("quiescent").getAsBoolean());
        assertEquals("3", prefix.get("taskCount").getAsString());
        assertEquals("2", prefix.get("requestedJobCount").getAsString());
        assertEquals("1", prefix.get("completedJobCount").getAsString());
        assertEquals("IN_FLIGHT", row(prefix, "copies", 0).get("status").getAsString());
        assertNulls(row(prefix, "copies", 0), "effectiveCompletionTime", "observedCompletionTime", "effectiveFctSeconds",
                "observedFctSeconds", "notificationLagSeconds", "completionResidualBytes");
        assertNulls(prefix, "completedObservedOutputTailSeconds");
        r.advance(11); r.advance(16); finish(r, 20, 2, 42, 16, 17, true);
        JsonObject terminal = tree(r);
        assertTrue(terminal.get("quiescent").getAsBoolean());
        assertEquals("3", terminal.get("taskCount").getAsString());
        checkPreview(terminal, "jobs", 2, 64);
        assertFalse(terminal.has("runComplete"));
        assertFalse(terminal.has("thisRun"));
        assertEquals("IN_FLIGHT", row(tree(decoded), "copies", 0).get("status").getAsString());
    }

    @Test void contextValidationIsOnlyTheCallersAttestation() {
        StorageLifecycleCodec.Decoded decoded = decoded(waiting(false));
        Map<String, Object> standalone = StorageLifecycleReportView.fromDecoded(decoded, false);
        Map<String, Object> expected = new LinkedHashMap<>(standalone);
        expected.put("contextValidated", true);
        assertEquals(expected, StorageLifecycleReportView.fromDecoded(decoded, true));
        assertEquals(Boolean.FALSE, standalone.get("contextValidated"));
        assertEquals("COMPLETE", standalone.get("captureStatus"));
    }

    @Test void completionResidualIsNeitherDeclaredPayloadNorServiceArea() {
        CoherentDataflowRuntime r = runtime(true, true, plan(task(1, in("a", 1), in("b", 1 + 1e-10))));
        r.requestJob(1, one(1), 7, 0); r.advance(1);
        StorageLifecycleCodec.Decoded decoded = decoded(r);
        double residual = event(decoded.getDocument().getAsJsonArray("events"), "COPY_SETTLED", 1)
                .getAsJsonObject("payload").get("remainingAfterService").getAsDouble();
        assertTrue(residual > 0);
        JsonObject view = tree(decoded), totals = view.getAsJsonObject("totals");
        String declared = text(BigDecimal.ONE.add(new BigDecimal(1 + 1e-10)));
        for (String key : Arrays.asList("admittedInputPayloadBytes", "settledInputPayloadBytes", "requiredReferenceBytes")) assertEquals(declared, totals.get(key).getAsString());
        assertEquals(exact(residual), totals.get("completionResidualBytes").getAsString());
        assertEquals(exact(1 + 1e-10), row(view, "copies", 1).get("payloadBytes").getAsString());
        assertEquals(exact(residual), row(view, "copies", 1).get("completionResidualBytes").getAsString());
        assertTrue(event(rows(view, "events"), "COPY_SETTLED", 1).get("detail").getAsString().contains("completionResidualBytes=" + exact(residual)));
    }

    @Test void independentHugeFilesProduceExactInputAndOutputTotalsBeyondDoubleMaximum() {
        for (boolean outputs : new boolean[]{false, true}) {
            // Legal ordinary store rates avoid MAX/1e6*1e6 overflow in the fixture's MB/s conversion.
            Task a = task(1, outputs ? out("a", 1e308) : in("a", 1e308));
            Task b = task(2, outputs ? out("b", 1e308) : in("b", 1e308));
            CoherentDataflowRuntime r = runtime(true, true, BUDGET, plan(a, b), fabric(false, 100, 100, 100, 100, 100, 100));
            if (outputs) {
                produce(r, 1, 1, 7, 0, 0, true); produce(r, 2, 2, 7, 0, 0, true);
            } else {
                r.requestJob(1, one(1), 7, 0); r.requestJob(2, one(2), 7, 0);
            }
            r.advance(4e306);
            if (!outputs) {
                finish(r, 1, 1, 7, 4e306, 4e306, true); finish(r, 2, 2, 7, 4e306, 4e306, true);
            }
            JsonObject view = tree(r), totals = view.getAsJsonObject("totals");
            String sum = text(new BigDecimal(1e308).multiply(BigDecimal.valueOf(2)));
            assertTrue(Double.isInfinite(new BigDecimal(sum).doubleValue()));
            String purpose = outputs ? "Output" : "Input", other = outputs ? "Input" : "Output";
            assertEquals(sum, totals.get("admitted" + purpose + "PayloadBytes").getAsString());
            assertEquals(sum, totals.get("settled" + purpose + "PayloadBytes").getAsString());
            assertEquals("0", totals.get("admitted" + other + "PayloadBytes").getAsString());
            assertEquals(outputs ? "0" : sum, totals.get("requiredReferenceBytes").getAsString());
            assertTrue(view.get("quiescent").getAsBoolean());
            for (JsonElement copy : rows(view, "copies")) assertEquals(exact(1e308), copy.getAsJsonObject().get("payloadBytes").getAsString());
            String detail = event(rows(view, "events"), "COPY_ADMITTED", 0).get("detail").getAsString();
            assertTrue(detail.contains("payloadBytes=" + exact(1e308)));
            assertFalse(detail.contains("…"));
        }
    }

    @Test void tinyPositiveClocksAndPayloadsKeepThousandCharacterExactNumericStrings() {
        double tiny = 3 * Double.MIN_VALUE;
        CoherentDataflowRuntime r = runtime(true, true, BUDGET, plan(task(1, in("tiny-in", tiny), out("tiny-out", tiny))),
                fabric(false, 1, 1, 1, 1, 1, 1));
        r.requestJob(11, one(1), 7, 0); r.advance(tiny); finish(r, 11, 1, 7, tiny, tiny, true); r.advance(2 * tiny);
        JsonObject view = tree(r);
        String text = exact(tiny);
        assertTrue(text.length() > 1000);
        assertEquals(exact(2 * tiny), view.get("observedThrough").getAsString());
        assertEquals(text, view.get("lastCpuFinishTime").getAsString());
        assertEquals(exact(2 * tiny), view.get("lastStoreCommitTime").getAsString());
        assertEquals(text, view.get("completedObservedOutputTailSeconds").getAsString());
        assertEquals(text, job(view, 11).get("nominalInputSeconds").getAsString());
        assertEquals(text, job(view, 11).get("observedPreparationSeconds").getAsString());
        for (JsonElement value : rows(view, "copies")) {
            JsonObject copy = value.getAsJsonObject();
            for (String key : Arrays.asList("payloadBytes", "isolatedSeconds", "effectiveFctSeconds", "observedFctSeconds")) assertEquals(text, copy.get(key).getAsString(), key);
            assertEquals("0", copy.get("notificationLagSeconds").getAsString());
            assertEquals("0", copy.get("completionResidualBytes").getAsString());
        }
        for (String key : Arrays.asList("admittedInputPayloadBytes", "settledInputPayloadBytes", "admittedOutputPayloadBytes", "settledOutputPayloadBytes", "requiredReferenceBytes")) {
            assertEquals(text, view.getAsJsonObject("totals").get(key).getAsString());
        }
        String admission = event(rows(view, "events"), "COPY_ADMITTED", 0).get("detail").getAsString();
        assertTrue(admission.contains("payloadBytes=" + text));
        assertTrue(admission.contains("isolatedSeconds=" + text));
        assertFalse(admission.contains("…"));
        assertEquals("0", view.get("labelsClipped").getAsString());
    }

    @Test void copyAndJobCapsPreserveFullTotalsLateSettlementsAndRequestOrder() {
        assertEquals(64, StorageLifecycleReportView.COPY_LIMIT);
        assertEquals(64, StorageLifecycleReportView.JOB_LIMIT);
        for (int count : new int[]{64, 65}) {
            List<Task> tasks = new ArrayList<>();
            Map<Integer, Double> capacities = new LinkedHashMap<>();
            Map<Integer, Integer> hosts = new LinkedHashMap<>();
            for (int i = 0; i < count; i++) { tasks.add(task(i, in("file-" + i, 1))); capacities.put(i, 1.0); hosts.put(i, i); }
            DataTransferFabric fabric = DataTransferFabric.withStorage(capacities, hosts, null, DataflowStorageSpec.of(0, .01, .01, .01));
            CoherentDataflowRuntime r = runtime(true, true, BUDGET, plan(tasks.toArray(new Task[0])), fabric);
            for (int i = 0; i < count; i++) r.requestJob(10000 - i, one(i), i, 0);
            r.advance(1);
            for (int i = 0; i < count; i++) finish(r, 10000 - i, i, i, 1, 1, true);
            JsonObject view = tree(r);
            checkPreview(view, "copies", count, 64);
            checkPreview(view, "jobs", count, 64);
            checkPreview(view, "resources", count + 3, 64);
            checkPreview(view, "events", count * 8, 128);
            for (JsonElement value : rows(view, "copies")) {
                assertEquals("SETTLED", value.getAsJsonObject().get("status").getAsString());
                assertEquals("1", value.getAsJsonObject().get("effectiveFctSeconds").getAsString());
            }
            for (int i = 0; i < Math.min(64, count); i++) {
                JsonObject job = row(view, "jobs", i);
                assertEquals(Integer.toString(10000 - i), job.get("jobId").getAsString());
                assertEquals("1", job.get("finishedAt").getAsString());
                assertEquals("1", job.get("observedPreparationSeconds").getAsString());
                assertEquals("1", job.get("nominalInputSeconds").getAsString());
            }
            for (String key : Arrays.asList("admittedInputPayloadBytes", "settledInputPayloadBytes", "requiredReferenceBytes", "referenceCount")) {
                assertEquals(Integer.toString(count), view.getAsJsonObject("totals").get(key).getAsString());
            }
            assertEquals(Integer.toString(count), view.get("completedJobCount").getAsString());
            assertEquals("0", view.get("nestedItemsOmitted").getAsString());
            assertTrue(view.get("quiescent").getAsBoolean());
        }
    }

    @Test void resourcePreviewBoundaryIncludesStoreResourcesInItsExactCounts() {
        assertEquals(64, StorageLifecycleReportView.RESOURCE_LIMIT);
        for (int count : new int[]{64, 65}) {
            Map<Integer, Double> capacities = new LinkedHashMap<>();
            Map<Integer, Integer> hosts = new LinkedHashMap<>();
            for (int i = 0; i < count - 3; i++) { capacities.put(i, 1.0); hosts.put(i, i); }
            DataTransferFabric fabric = DataTransferFabric.withStorage(capacities, hosts, null, DataflowStorageSpec.of(0, .00002, .00001, .0001));
            JsonObject view = tree(runtime(false, true, BUDGET, plan(), fabric));
            checkPreview(view, "resources", count, 64);
            assertEquals("20", view.getAsJsonObject("store").get("readCapacityBytesPerSecond").getAsString());
            assertEquals("10", view.getAsJsonObject("store").get("writeCapacityBytesPerSecond").getAsString());
            assertEquals("100", view.getAsJsonObject("store").get("networkCapacityBytesPerSecond").getAsString());
            assertTrue(view.get("quiescent").getAsBoolean());
        }
    }

    @Test void eventPreview128And129AreDisplayCapsNotCaptureTruncation() {
        assertEquals(128, StorageLifecycleReportView.EVENT_LIMIT);
        for (int count : new int[]{128, 129}) {
            FileItem[] files = new FileItem[count];
            for (int i = 0; i < count; i++) files[i] = in("seed-" + i, 1);
            JsonObject view = tree(runtime(true, true, plan(task(1, files))));
            checkPreview(view, "events", count, 128);
            assertEquals(Integer.toString(count), view.get("retainedRecords").getAsString());
            assertEquals(Integer.toString(count), view.get("fileCount").getAsString());
            assertEquals("0", view.getAsJsonObject("totals").get("referenceCount").getAsString());
            assertEquals("0", view.get("requestedJobCount").getAsString());
            assertNulls(view, "lastStoreCommitTime", "completedObservedOutputTailSeconds");
            assertTrue(view.get("quiescent").getAsBoolean());
        }
    }

    @Test void hostileFileNamesStayLiteralPlainTextWithoutEmbeddingRawPayloads() {
        String name = "</script><img src=x onerror=alert(1)>节点\"'\u2028\u2029@@DATA@@";
        CoherentDataflowRuntime r = runtime(true, true, plan(task(1, in(name, 10))));
        r.requestJob(11, one(1), 7, 0);
        Map<String, Object> projection = StorageLifecycleReportView.fromDecoded(decoded(r), false);
        JsonObject view = JSON.toJsonTree(projection).getAsJsonObject();
        assertEquals("input[0]/" + name, row(view, "copies", 0).get("fileId").getAsString());
        assertEquals("input[0]/" + name, event(rows(view, "events"), "EXTERNAL_SEEDED", 0).get("fileId").getAsString());
        for (JsonElement value : rows(view, "events")) assertFalse(value.getAsJsonObject().get("detail").getAsString().contains("\"fileId\":"));
        String encoded = JSON.toJson(projection);
        assertFalse(encoded.contains("</script>"));
        assertFalse(encoded.contains("<img"));
        assertTrue(encoded.contains("\\u003c"));
        assertEquals("0", view.get("labelsClipped").getAsString());
        assertDeepImmutable(projection);
        assertNoJsonNumbers(view);
    }

    @Test void labelsClipSafelyAtUtf16BoundariesWithoutTruncatingAnyNumericId() {
        int id = Integer.MAX_VALUE;
        String prefix = "input[" + id + "]/";
        for (int remaining : new int[]{1, 2}) {
            String padding = repeat('x', StorageLifecycleReportView.LABEL_LIMIT - prefix.length() - remaining);
            String name = padding + "😀tail";
            Task task = task(id, in(name, 1));
            DataflowFilePlan plan = DataflowFilePlan.capture(Collections.singletonList(task), Collections.singletonMap(id, id));
            DataTransferFabric fabric = DataTransferFabric.withStorage(Collections.singletonMap(id, 1.0), Collections.singletonMap(id, id),
                    null, DataflowStorageSpec.of(id, .000001, .000001, .000001));
            CoherentDataflowRuntime r = runtime(true, true, BUDGET, plan, fabric); r.requestJob(id, one(id), id, 0);
            JsonObject view = tree(r), copy = row(view, "copies", 0), job = job(view, id);
            String label = copy.get("fileId").getAsString();
            assertEquals(prefix + padding + (remaining == 1 ? "" : "😀") + "…", label);
            assertTrue(label.length() <= StorageLifecycleReportView.LABEL_LIMIT + 1);
            assertWellFormedSurrogates(label);
            assertEquals("VM:" + id, copy.get("destination").getAsString());
            assertEquals(Integer.toString(id), copy.get("ownerJobId").getAsString());
            for (String key : Arrays.asList("jobId", "taskId", "vmId")) assertEquals(Integer.toString(id), job.get(key).getAsString());
            assertEquals(Integer.toString(id), view.getAsJsonObject("store").get("attachmentHostId").getAsString());
            assertTrue(event(rows(view, "events"), "JOB_INPUT_REQUESTED", 0).get("detail").getAsString().contains("taskIds=[" + id + "]"));
            assertTrue(Long.parseLong(view.get("labelsClipped").getAsString()) > 0);
        }
        String boundaryName = repeat('b', StorageLifecycleReportView.LABEL_LIMIT - "input[0]/".length());
        CoherentDataflowRuntime r = runtime(true, true, plan(task(1, in(boundaryName, 1)))); r.requestJob(1, one(1), 7, 0);
        JsonObject view = tree(r);
        assertEquals("input[0]/" + boundaryName, row(view, "copies", 0).get("fileId").getAsString());
        assertEquals("0", view.get("labelsClipped").getAsString());
    }

    @Test void clippedLabelsNeverBecomeReferenceOrDeferredStoreIdentityKeys() {
        String common = repeat('c', StorageLifecycleReportView.LABEL_LIMIT * 2);
        Task p = task(1, out(common + "a", .1), out(common + "b", .2));
        Task c = task(2, in(common + "a", .1), in(common + "a", .1), in(common + "b", .2), in(common + "b", .2), in(common + "b", .2)); edge(p, c);
        CoherentDataflowRuntime r = runtime(true, true, plan(p, c));
        produce(r, 10, 1, 7, 0, 1, true); r.requestJob(20, one(2), 42, 1);
        String required = text(new BigDecimal(.1).multiply(BigDecimal.valueOf(2)).add(new BigDecimal(.2).multiply(BigDecimal.valueOf(3))));
        JsonObject waiting = tree(r);
        assertEquals("2", waiting.get("waitingStoreInputCount").getAsString());
        assertEquals(required, waiting.getAsJsonObject("totals").get("requiredReferenceBytes").getAsString());
        drainCopies(r);
        JsonObject view = tree(r);
        assertEquals("0", view.get("waitingStoreInputCount").getAsString());
        assertEquals("4", view.get("copyCount").getAsString());
        assertEquals(row(view, "copies", 0).get("fileId").getAsString(), row(view, "copies", 1).get("fileId").getAsString());
        assertEquals("5", view.getAsJsonObject("totals").get("referenceCount").getAsString());
        assertEquals(required, view.getAsJsonObject("totals").get("requiredReferenceBytes").getAsString());
        assertEquals(text(new BigDecimal(.1).add(new BigDecimal(.2))), view.getAsJsonObject("totals").get("admittedInputPayloadBytes").getAsString());
    }

    @Test void equalNamesAcrossWorkflowScopesRemainDifferentFilesForExactReferenceSums() {
        String name = "shared::/part";
        Task a = task(1, in(name, .1), in(name, .1)), b = task(2, in(name, .2), in(name, .2), in(name, .2));
        Map<Integer, Integer> scopes = new LinkedHashMap<>(); scopes.put(1, 0); scopes.put(2, 1);
        CoherentDataflowRuntime r = runtime(true, true, DataflowFilePlan.capture(Arrays.asList(a, b), scopes));
        r.requestJob(11, one(1), 7, 0); r.requestJob(22, one(2), 42, 0);
        JsonObject view = tree(r);
        assertEquals("input[0]/" + name, row(view, "copies", 0).get("fileId").getAsString());
        assertEquals("input[1]/" + name, row(view, "copies", 1).get("fileId").getAsString());
        assertEquals(text(new BigDecimal(.1).multiply(BigDecimal.valueOf(2)).add(new BigDecimal(.2).multiply(BigDecimal.valueOf(3)))),
                view.getAsJsonObject("totals").get("requiredReferenceBytes").getAsString());
    }

    private static CoherentDataflowRuntime waiting(boolean sameVm) {
        Task p = task(1, out("x", 100)), c = task(2, in("x", 100)); edge(p, c);
        CoherentDataflowRuntime r = runtime(true, true, plan(p, c));
        produce(r, 10, 1, 7, 0, 1, true); r.requestJob(20, one(2), sameVm ? 7 : 42, 1); return r;
    }
    private static CoherentDataflowRuntime runtime(boolean shared, boolean storeInputs, DataflowFilePlan plan) {
        return runtime(shared, storeInputs, BUDGET, plan, fabric(false, 100, 100, 100, 20, 10, 100));
    }
    private static CoherentDataflowRuntime runtime(boolean shared, boolean storeInputs, int budget, DataflowFilePlan plan, DataTransferFabric fabric) {
        CoherentDataflowRuntime r = CoherentDataflowRuntime.withStorage(shared, budget, storeInputs);
        r.initializePlan(plan, fabric.getLocations()); r.bindFabric(fabric); return r;
    }
    private static DataTransferFabric fabric(boolean tree, double a, double b, double c, double read, double write, double nic) {
        Map<Integer, Integer> placement = new LinkedHashMap<>(); placement.put(10, 0); placement.put(40, 0); placement.put(30, 1); placement.put(20, 3);
        FatTreeTopology topology = tree ? FatTreeTopology.fromSpec(NetworkTopologySpec.fatTree(4, .00002, 3, placement), Arrays.asList(10, 20, 30, 40)) : null;
        return DataTransferFabric.withStorage(capacities(a, b, c), hosts(), topology, DataflowStorageSpec.of(10, read / 1e6, write / 1e6, nic / 1e6));
    }
    private static Map<Integer, Integer> hosts() {
        Map<Integer, Integer> values = new LinkedHashMap<>(); values.put(7, 10); values.put(42, 20); values.put(99, 30); return values;
    }
    private static Map<Integer, Double> capacities(double a, double b, double c) {
        Map<Integer, Double> values = new LinkedHashMap<>(); values.put(7, a); values.put(42, b); values.put(99, c); return values;
    }
    private static DataflowFilePlan plan(Task... tasks) {
        Map<Integer, Integer> scopes = new LinkedHashMap<>(); for (Task task : tasks) scopes.put(task.getCloudletId(), 0);
        return DataflowFilePlan.capture(Arrays.asList(tasks), scopes);
    }
    private static Task task(int id, FileItem... files) {
        Task task = new Task(id, 1000); for (FileItem file : files) task.addFile(file); return task;
    }
    private static FileItem in(String name, double bytes) { return file(name, bytes, FileType.INPUT); }
    private static FileItem out(String name, double bytes) { return file(name, bytes, FileType.OUTPUT); }
    private static FileItem file(String name, double bytes, FileType type) {
        FileItem file = new FileItem(name, bytes); file.setType(type); return file;
    }
    private static void edge(Task parent, Task child) { parent.addChild(child); child.addParent(parent); }
    private static List<Integer> one(int id) { return Collections.singletonList(id); }
    private static void produce(CoherentDataflowRuntime r, int job, int task, int vm, double start, double end, boolean success) {
        r.requestJob(job, one(task), vm, start); finish(r, job, task, vm, start, end, success);
    }
    private static void finish(CoherentDataflowRuntime r, int job, int task, int vm, double start, double end, boolean success) {
        r.drainReadyJobIds(); r.cpuStarted(job, one(task), vm, start); r.jobFinished(job, one(task), vm, Collections.singletonList(success), end);
    }
    private static void drainCopies(CoherentDataflowRuntime r) {
        int steps = 0;
        while (r.getNextCompletionTime() != null) {
            assertTrue(++steps < 200, "bounded fixture must make progress"); r.advance(r.getNextCompletionTime());
        }
    }
    private static StorageLifecycleCodec.Decoded decoded(CoherentDataflowRuntime r) {
        return StorageLifecycleCodec.decode(StorageLifecycleCodec.encode(r.captureStorageEvidence()));
    }
    private static JsonObject tree(CoherentDataflowRuntime r) { return tree(decoded(r)); }
    private static JsonObject tree(StorageLifecycleCodec.Decoded decoded) {
        JsonObject view = JSON.toJsonTree(StorageLifecycleReportView.fromDecoded(decoded, false)).getAsJsonObject();
        assertNoJsonNumbers(view); return view;
    }
    private static JsonArray rows(JsonObject view, String key) { return view.getAsJsonObject(key).getAsJsonArray("rows"); }
    private static JsonObject row(JsonObject view, String key, int index) { return rows(view, key).get(index).getAsJsonObject(); }
    private static JsonObject job(JsonObject view, int id) {
        for (JsonElement value : rows(view, "jobs")) if (Integer.toString(id).equals(value.getAsJsonObject().get("jobId").getAsString())) return value.getAsJsonObject();
        throw new AssertionError("Missing display Job " + id);
    }
    private static JsonObject event(JsonArray events, String type, int occurrence) {
        for (JsonElement value : events) {
            JsonObject event = value.getAsJsonObject();
            if (type.equals(event.get("type").getAsString()) && occurrence-- == 0) return event;
        }
        throw new AssertionError("Missing event " + type);
    }
    private static JsonObject rawResource(JsonObject document, String key) {
        for (JsonElement value : document.getAsJsonObject("fabric").getAsJsonArray("resources")) {
            if (key.equals(value.getAsJsonObject().get("key").getAsString())) return value.getAsJsonObject();
        }
        throw new AssertionError("Missing resource " + key);
    }
    private static List<String> strings(JsonArray values) {
        List<String> result = new ArrayList<>(); for (JsonElement value : values) result.add(value.getAsString()); return result;
    }
    private static Set<String> keys(String... values) { return new HashSet<>(Arrays.asList(values)); }
    private static String exact(double value) { return text(new BigDecimal(value)); }
    private static String difference(double end, double start) { return text(new BigDecimal(end).subtract(new BigDecimal(start))); }
    private static String text(BigDecimal value) { return value.stripTrailingZeros().toPlainString(); }
    private static String repeat(char value, int count) { char[] chars = new char[count]; Arrays.fill(chars, value); return new String(chars); }
    private static void assertNulls(JsonObject object, String... keys) {
        for (String key : keys) { assertTrue(object.has(key), key); assertTrue(object.get(key).isJsonNull(), key); }
    }
    private static void checkPreview(JsonObject view, String key, int total, int limit) {
        JsonObject preview = view.getAsJsonObject(key);
        assertEquals(keys("total", "shown", "omitted", "limit", "rows"), preview.keySet());
        assertEquals(Integer.toString(total), preview.get("total").getAsString());
        assertEquals(Integer.toString(Math.min(total, limit)), preview.get("shown").getAsString());
        assertEquals(Integer.toString(Math.max(0, total - limit)), preview.get("omitted").getAsString());
        assertEquals(Integer.toString(limit), preview.get("limit").getAsString());
        assertEquals(Math.min(total, limit), rows(view, key).size());
        assertEquals("COMPLETE", view.get("captureStatus").getAsString());
    }
    private static void assertNoJsonNumbers(JsonElement value) {
        if (value.isJsonObject()) {
            for (Map.Entry<String, JsonElement> entry : value.getAsJsonObject().entrySet()) assertNoJsonNumbers(entry.getValue());
        } else if (value.isJsonArray()) {
            for (JsonElement item : value.getAsJsonArray()) assertNoJsonNumbers(item);
        } else if (value.isJsonPrimitive()) {
            assertFalse(value.getAsJsonPrimitive().isNumber(), value.toString());
        }
    }
    @SuppressWarnings("unchecked")
    private static void assertDeepImmutable(Object value) {
        if (value instanceof Map) {
            Map<String, Object> map = (Map<String, Object>) value;
            assertThrows(UnsupportedOperationException.class, () -> map.put("mutation", "not allowed"));
            if (!map.isEmpty()) {
                Map.Entry<String, Object> entry = map.entrySet().iterator().next();
                assertThrows(UnsupportedOperationException.class, () -> entry.setValue("not allowed"));
            }
            for (Object item : map.values()) assertDeepImmutable(item);
        } else if (value instanceof List) {
            List<Object> list = (List<Object>) value;
            assertThrows(UnsupportedOperationException.class, () -> list.add("not allowed"));
            if (!list.isEmpty()) assertThrows(UnsupportedOperationException.class, () -> list.set(0, "not allowed"));
            for (Object item : list) assertDeepImmutable(item);
        } else {
            assertTrue(value == null || value instanceof String || value instanceof Boolean,
                    "Projection leaked a Java Number, JSON node, or another non-display object");
        }
    }
    private static void assertWellFormedSurrogates(String value) {
        for (int i = 0; i < value.length(); i++) {
            char current = value.charAt(i);
            if (Character.isHighSurrogate(current)) {
                assertTrue(i + 1 < value.length() && Character.isLowSurrogate(value.charAt(i + 1))); i++;
            } else assertFalse(Character.isLowSurrogate(current));
        }
    }
}
