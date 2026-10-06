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
import org.workflowsim.data.v2.FileLifecycleCodec;
import org.workflowsim.network.FatTreeTopology;
import org.workflowsim.network.NetworkTopologySpec;
import org.workflowsim.utils.Parameters.FileType;

/** Real standalone runtime captures, not fabricated CPU-report contexts or service-area certificates. */
class FileLifecycleReportViewTest {
    private static final int BUDGET = 10000;
    private static final Gson JSON = new GsonBuilder().serializeNulls().create();

    @Test void absentOffAndEmptyCompleteCaptureStayDistinct() {
        assertNull(FileLifecycleReportView.fromDecoded(null, false));
        assertNull(FileLifecycleReportView.fromDecoded(null, true));
        CoherentDataflowRuntime off = runtime(true, 0, plan(), endpoints(10, 100, 100));
        assertNull(off.captureEvidence());

        JsonObject view = tree(decoded(runtime(true, plan())));
        assertEquals("workflowsim-file-lifecycle-display-v2", view.get("schema").getAsString());
        assertEquals("COMPLETE", view.get("captureStatus").getAsString());
        assertFalse(view.get("contextValidated").getAsBoolean());
        assertTrue(view.get("quiescent").getAsBoolean());
        for (String key : Arrays.asList("taskCount", "fileCount", "copyCount", "completedCopyCount", "activeCopyCount",
                "requestedJobCount", "completedJobCount", "retainedRecords", "observedThrough", "labelsClipped", "nestedItemsOmitted")) {
            assertEquals("0", view.get(key).getAsString(), key);
        }
        for (JsonElement value : view.getAsJsonObject("totals").asMap().values()) assertEquals("0", value.getAsString());
        assertEquals(Integer.toString(BUDGET), view.get("traceBudget").getAsString());
        assertEquals("256", view.get("labelLimit").getAsString());
        assertEquals("12", view.get("nestedListLimit").getAsString());
        checkPreview(view, "copies", 0, FileLifecycleReportView.COPY_LIMIT);
        checkPreview(view, "resources", 3, FileLifecycleReportView.RESOURCE_LIMIT);
        checkPreview(view, "events", 0, FileLifecycleReportView.EVENT_LIMIT);
        assertNoJsonNumbers(view);
    }

    @Test void liveSharedAndIsolatedCapturesPreserveCountsAndRootProvenance() {
        for (boolean shared : new boolean[]{true, false}) {
            FileLifecycleCodec.Decoded decoded = decoded(fanIn(shared));
            JsonObject view = tree(decoded), totals = view.getAsJsonObject("totals");
            assertEquals(decoded.getDocument().get("certificateScope").getAsString(), view.get("certificateScope").getAsString());
            assertEquals(shared ? "COHERENT_FILE_DATAFLOW_V2" : "COHERENT_FILE_DATAFLOW_NO_CONTENTION_V2",
                    view.get("modelKind").getAsString());
            assertEquals(shared ? "SHARED_MAX_MIN" : "ISOLATED_PATH_BOTTLENECK", view.get("sharingPolicy").getAsString());
            assertEquals("4", view.get("taskCount").getAsString());
            assertEquals("2", view.get("fileCount").getAsString());
            assertEquals("3", view.get("copyCount").getAsString());
            assertEquals("3", view.get("completedCopyCount").getAsString());
            assertEquals("0", view.get("activeCopyCount").getAsString());
            assertEquals("4", view.get("requestedJobCount").getAsString());
            assertEquals("4", view.get("completedJobCount").getAsString());
            assertEquals("26", view.get("retainedRecords").getAsString());
            assertEquals("13", view.get("observedThrough").getAsString());
            assertTrue(view.get("quiescent").getAsBoolean());
            assertEquals("120", totals.get("admittedPayloadBytes").getAsString());
            assertEquals("120", totals.get("settledPayloadBytes").getAsString());
            assertEquals("0", totals.get("completionResidualBytes").getAsString());
            assertEquals("5", totals.get("referenceCount").getAsString());
            assertEquals("1", totals.get("joinedReferenceCount").getAsString());
            assertEquals("0", totals.get("localReferenceCount").getAsString());
            assertEquals("140", totals.get("requiredReferenceBytes").getAsString());
            assertEquals("0", totals.get("localReferenceBytes").getAsString());

            JsonObject cached = row(view, "copies", 2);
            assertEquals("3", cached.get("copyOrdinal").getAsString());
            assertEquals("input[0]/fast", cached.get("fileId").getAsString());
            assertEquals("VM:99", cached.get("source").getAsString());
            assertEquals("VM:42", cached.get("destination").getAsString());
            assertEquals("1", cached.get("producerTaskId").getAsString());
            assertEquals("10", cached.get("producerJobAttemptId").getAsString());
            assertEquals("VM:7", cached.get("originLocation").getAsString());
            assertEquals("3", cached.get("sourceVisibleAt").getAsString());
            assertEquals("3", cached.get("admissionTime").getAsString());
            assertEquals("SETTLED", cached.get("status").getAsString());
            assertEquals(Arrays.asList("VM:99", "VM:42"), strings(cached.getAsJsonArray("resources")));
            String detail = event(rows(view, "events"), "COPY_ADMITTED", 2).get("detail").getAsString();
            assertTrue(detail.contains("source=VM:99"));
            assertTrue(detail.contains("producerTaskId=1; producerJobAttemptId=10; originLocation=VM:7"));
            assertEquals("JOIN_EXISTING", event(rows(view, "events"), "INPUT_RESOLVED", 2).get("resolution").getAsString());
            assertNoJsonNumbers(view);
        }
    }

    @Test void localZeroAndJoinedReferenceMultiplicitiesUseAuthoritativeFileBytes() {
        Task first = task(1, in("x", .1), in("x", .1), in("zero", 0));
        Task joined = task(2, in("x", .1), in("x", .1), in("x", .1), in("zero", 0), in("zero", 0));
        Task local = task(3, in("x", .1), in("x", .1), in("zero", 0));
        CoherentDataflowRuntime runtime = runtime(true, BUDGET, plan(first, joined, local), endpoints(1, 1, 1));
        runtime.requestJob(11, one(1), 7, 0);
        runtime.requestJob(22, one(2), 7, 0);
        runtime.advance(1);
        runtime.requestJob(33, one(3), 7, 1);
        JsonObject view = tree(decoded(runtime)), totals = view.getAsJsonObject("totals");
        assertEquals("1", view.get("copyCount").getAsString());
        assertEquals("1", view.get("completedCopyCount").getAsString());
        assertEquals("11", totals.get("referenceCount").getAsString());
        assertEquals("5", totals.get("localReferenceCount").getAsString());
        assertEquals("3", totals.get("joinedReferenceCount").getAsString());
        assertEquals(text(new BigDecimal(.1).multiply(BigDecimal.valueOf(7))), totals.get("requiredReferenceBytes").getAsString());
        assertEquals(text(new BigDecimal(.1).multiply(BigDecimal.valueOf(2))), totals.get("localReferenceBytes").getAsString());
        assertEquals(exact(.1), totals.get("admittedPayloadBytes").getAsString());
        assertEquals(exact(.1), totals.get("settledPayloadBytes").getAsString());
        String[] resolutions = {"NEW_COPY", "ZERO", "JOIN_EXISTING", "LOCAL", "LOCAL", "LOCAL"};
        String[] references = {"2", "1", "3", "2", "2", "1"};
        for (int i = 0; i < resolutions.length; i++) {
            JsonObject resolved = event(rows(view, "events"), "INPUT_RESOLVED", i);
            assertEquals(resolutions[i], resolved.get("resolution").getAsString());
            assertTrue(resolved.get("detail").getAsString().contains("referenceCount=" + references[i]));
        }
        assertNoJsonNumbers(view);
    }

    @Test void equalNamesInDifferentWorkflowScopesNeverAliasReferenceBytes() {
        String name = "shared::/part";
        Task a = task(1, in(name, .1), in(name, .1));
        Task b = task(2, in(name, .2), in(name, .2), in(name, .2));
        Map<Integer, Integer> scopes = new LinkedHashMap<>();
        scopes.put(1, 0); scopes.put(2, 1);
        CoherentDataflowRuntime runtime = runtime(true, DataflowFilePlan.capture(Arrays.asList(a, b), scopes));
        runtime.requestJob(11, one(1), 7, 0);
        runtime.requestJob(22, one(2), 42, 0);
        JsonObject view = tree(decoded(runtime));
        assertEquals("2", view.get("fileCount").getAsString());
        assertEquals("input[0]/" + name, row(view, "copies", 0).get("fileId").getAsString());
        assertEquals("input[1]/" + name, row(view, "copies", 1).get("fileId").getAsString());
        BigDecimal required = new BigDecimal(.1).multiply(BigDecimal.valueOf(2))
                .add(new BigDecimal(.2).multiply(BigDecimal.valueOf(3)));
        assertEquals(text(required), view.getAsJsonObject("totals").get("requiredReferenceBytes").getAsString());
        assertEquals("5", view.getAsJsonObject("totals").get("referenceCount").getAsString());
        assertEquals(text(new BigDecimal(.1).add(new BigDecimal(.2))),
                view.getAsJsonObject("totals").get("admittedPayloadBytes").getAsString());
    }

    @Test void completeActivePrefixesAndQuiescenceDoNotClaimRunCompletion() {
        CoherentDataflowRuntime runtime = runtime(true, plan(task(1, in("x", 10)), task(2, in("unrequested", 20))));
        JsonObject seeds = tree(decoded(runtime));
        assertTrue(seeds.get("quiescent").getAsBoolean());
        assertEquals("0", seeds.get("requestedJobCount").getAsString());
        assertEquals("2", seeds.get("taskCount").getAsString());

        runtime.requestJob(11, one(1), 7, 0);
        FileLifecycleCodec.Decoded prefix = decoded(runtime);
        JsonObject active = tree(prefix), copy = row(active, "copies", 0);
        assertEquals("COMPLETE", active.get("captureStatus").getAsString());
        assertFalse(active.get("quiescent").getAsBoolean());
        assertEquals("1", active.get("activeCopyCount").getAsString());
        assertEquals("0", active.get("completedJobCount").getAsString());
        assertEquals("IN_FLIGHT", copy.get("status").getAsString());
        for (String key : Arrays.asList("effectiveCompletionTime", "observedCompletionTime", "effectiveFctSeconds",
                "observedFctSeconds", "notificationLagSeconds", "completionResidualBytes")) assertTrue(copy.get(key).isJsonNull(), key);
        assertEquals("0", active.getAsJsonObject("totals").get("settledPayloadBytes").getAsString());
        assertEquals("10", active.getAsJsonObject("totals").get("requiredReferenceBytes").getAsString());

        runtime.advance(2);
        JsonObject ready = tree(decoded(runtime));
        assertEquals("0", ready.get("activeCopyCount").getAsString());
        assertEquals("1", ready.get("completedCopyCount").getAsString());
        assertEquals("0", ready.get("completedJobCount").getAsString());
        assertFalse(ready.get("quiescent").getAsBoolean());
        finish(runtime, 11, 1, 7, 2, 3, true);
        JsonObject terminal = tree(decoded(runtime));
        assertTrue(terminal.get("quiescent").getAsBoolean());
        assertEquals("1", terminal.get("requestedJobCount").getAsString());
        assertEquals("1", terminal.get("completedJobCount").getAsString());
        assertEquals("2", terminal.get("taskCount").getAsString(), "unrequested Tasks do not prevent standalone quiescence");
        assertFalse(terminal.has("runComplete"));
        assertEquals("IN_FLIGHT", row(tree(prefix), "copies", 0).get("status").getAsString());
    }

    @Test void standaloneContextValidationFlagIsOnlyTheCallersAttestation() {
        FileLifecycleCodec.Decoded decoded = decoded(fanIn(true));
        Map<String, Object> standalone = FileLifecycleReportView.fromDecoded(decoded, false);
        assertEquals(Boolean.FALSE, standalone.get("contextValidated"));
        assertEquals("COMPLETE", standalone.get("captureStatus"));
        // Exercise the flag contract without manufacturing a matching CPU-report artifact.
        Map<String, Object> callerAttested = FileLifecycleReportView.fromDecoded(decoded, true);
        Map<String, Object> expected = new LinkedHashMap<>(standalone);
        expected.put("contextValidated", true);
        assertEquals(expected, callerAttested);
    }

    @Test void projectionIsDeeplyImmutableDetachedAndContainsOnlyDisplayFields() {
        JsonObject source = FileLifecycleCodec.document(fanIn(true).captureEvidence());
        FileLifecycleCodec.Decoded decoded = FileLifecycleCodec.decodeDocument(source);
        String before = JSON.toJson(decoded.getDocument());
        Map<String, Object> view = FileLifecycleReportView.fromDecoded(decoded, false);
        String projected = JSON.toJson(view);
        assertDeepImmutable(view);
        source.addProperty("schema", "mutated");
        decoded.getDocument().getAsJsonObject("capture").addProperty("observedThrough", 999);
        decoded.getEvidence().getEvents().get(0).getPayload().addProperty("jobId", 999);
        assertEquals(before, JSON.toJson(decoded.getDocument()));
        assertEquals(projected, JSON.toJson(view));
        assertEquals(view, FileLifecycleReportView.fromDecoded(decoded, false));

        JsonObject tree = JSON.toJsonTree(view).getAsJsonObject();
        assertEquals(keys("schema", "captureStatus", "contextValidated", "certificateScope", "modelKind", "sharingPolicy",
                "traceBudget", "retainedRecords", "observedThrough", "taskCount", "fileCount", "copyCount", "completedCopyCount",
                "activeCopyCount", "requestedJobCount", "completedJobCount", "quiescent", "labelLimit", "nestedListLimit",
                "labelsClipped", "nestedItemsOmitted", "totals", "copies", "resources", "events"), tree.keySet());
        assertEquals(keys("admittedPayloadBytes", "settledPayloadBytes", "completionResidualBytes", "referenceCount",
                "localReferenceCount", "joinedReferenceCount", "requiredReferenceBytes", "localReferenceBytes"), tree.getAsJsonObject("totals").keySet());
        assertEquals(keys("copyOrdinal", "fileId", "source", "destination", "producerTaskId", "producerJobAttemptId", "originLocation",
                "sourceVisibleAt", "admissionTime", "standaloneRate", "isolatedSeconds", "effectiveCompletionTime", "observedCompletionTime",
                "effectiveFctSeconds", "observedFctSeconds", "notificationLagSeconds", "payloadBytes", "completionResidualBytes",
                "status", "resources", "resourcesOmitted"), row(tree, "copies", 0).keySet());
        for (JsonElement value : rows(tree, "resources")) {
            assertEquals(keys("resourceKey", "capacityBytesPerSecond"), value.getAsJsonObject().keySet());
        }
        for (JsonElement value : rows(tree, "events")) {
            assertEquals(keys("sequence", "type", "observedTime", "jobId", "taskId", "copyOrdinal", "fileId", "resolution", "detail"),
                    value.getAsJsonObject().keySet());
            assertFalse(value.getAsJsonObject().get("detail").getAsString().contains("\"payload\""));
        }
        assertNoJsonNumbers(tree);
    }

    @Test void settlementsJoinExactOrdinalsRatherThanFileNameOrCompletionOrder() {
        CoherentDataflowRuntime runtime = runtime(true, BUDGET,
                plan(task(1, in("same", 10)), task(2, in("same", 10))), endpoints(5, 10, 100));
        runtime.requestJob(11, one(1), 7, 0);
        runtime.requestJob(22, one(2), 42, 0);
        runtime.advance(3);
        FileLifecycleCodec.Decoded decoded = decoded(runtime);
        assertEquals(2, event(decoded.getDocument().getAsJsonArray("events"), "COPY_SETTLED", 0)
                .getAsJsonObject("payload").get("copyOrdinal").getAsLong());
        JsonObject view = tree(decoded), first = row(view, "copies", 0), second = row(view, "copies", 1);
        assertEquals("1", first.get("copyOrdinal").getAsString());
        assertEquals("VM:7", first.get("destination").getAsString());
        assertEquals("2", first.get("effectiveCompletionTime").getAsString());
        assertEquals("3", first.get("observedCompletionTime").getAsString());
        assertEquals("2", first.get("effectiveFctSeconds").getAsString());
        assertEquals("1", first.get("notificationLagSeconds").getAsString());
        assertEquals("2", second.get("copyOrdinal").getAsString());
        assertEquals("VM:42", second.get("destination").getAsString());
        assertEquals("1", second.get("effectiveCompletionTime").getAsString());
        assertEquals("2", second.get("notificationLagSeconds").getAsString());
        assertEquals("SOURCE:source", first.get("source").getAsString());
        assertEquals("SOURCE:source", first.get("originLocation").getAsString());
        assertTrue(first.get("producerTaskId").isJsonNull());
        assertTrue(first.get("producerJobAttemptId").isJsonNull());
        JsonObject settled = event(rows(view, "events"), "COPY_SETTLED", 0);
        assertEquals("input[0]/same", settled.get("fileId").getAsString());
        assertTrue(settled.get("jobId").isJsonNull());
        assertTrue(settled.get("taskId").isJsonNull());
    }

    @Test void fctAndNotificationLagSubtractExactBinary64Timestamps() {
        CoherentDataflowRuntime runtime = runtime(true, BUDGET, plan(task(1, in("x", 1))), endpoints(1, 1, 1));
        runtime.requestJob(11, one(1), 7, .1);
        runtime.advance(2.3);
        FileLifecycleCodec.Decoded decoded = decoded(runtime);
        JsonArray raw = decoded.getDocument().getAsJsonArray("events");
        double admitted = event(raw, "COPY_ADMITTED", 0).get("observedTime").getAsDouble();
        JsonObject settlement = event(raw, "COPY_SETTLED", 0);
        double effective = settlement.getAsJsonObject("payload").get("effectiveTime").getAsDouble();
        double observed = settlement.get("observedTime").getAsDouble();
        JsonObject copy = row(tree(decoded), "copies", 0);
        assertEquals(exact(admitted), copy.get("admissionTime").getAsString());
        assertEquals(exact(effective), copy.get("effectiveCompletionTime").getAsString());
        assertEquals(exact(observed), copy.get("observedCompletionTime").getAsString());
        assertEquals(difference(effective, admitted), copy.get("effectiveFctSeconds").getAsString());
        assertEquals(difference(observed, admitted), copy.get("observedFctSeconds").getAsString());
        assertEquals(difference(observed, effective), copy.get("notificationLagSeconds").getAsString());
        assertNotEquals(exact(effective - admitted), copy.get("effectiveFctSeconds").getAsString(),
                "subtracting in double first irreversibly rounds this FCT");
    }

    @Test void completionResidualIsNotPayloadServiceAreaOrJoinedReferenceSavings() {
        CoherentDataflowRuntime runtime = runtime(true, plan(task(1, in("a", 1), in("b", 1 + 1e-10))));
        runtime.requestJob(11, one(1), 7, 0);
        runtime.advance(1);
        FileLifecycleCodec.Decoded decoded = decoded(runtime);
        double residual = event(decoded.getDocument().getAsJsonArray("events"), "COPY_SETTLED", 1)
                .getAsJsonObject("payload").get("remainingAfterService").getAsDouble();
        assertTrue(residual > 0, "checked runtime completion residuals are available without optional service tracing");
        JsonObject view = tree(decoded), totals = view.getAsJsonObject("totals");
        String payload = text(BigDecimal.ONE.add(new BigDecimal(1 + 1e-10)));
        assertEquals(payload, totals.get("admittedPayloadBytes").getAsString());
        assertEquals(payload, totals.get("settledPayloadBytes").getAsString());
        assertEquals(payload, totals.get("requiredReferenceBytes").getAsString());
        assertEquals(exact(residual), totals.get("completionResidualBytes").getAsString());
        assertEquals("0", totals.get("joinedReferenceCount").getAsString());
        assertEquals("2", totals.get("referenceCount").getAsString());
        assertEquals(exact(1 + 1e-10), row(view, "copies", 1).get("payloadBytes").getAsString());
        assertEquals(exact(residual), row(view, "copies", 1).get("completionResidualBytes").getAsString());
        assertTrue(event(rows(view, "events"), "COPY_SETTLED", 1).get("detail").getAsString()
                .contains("completionResidualBytes=" + exact(residual)));
    }

    @Test void copyAndResourcePreviewCapsLeaveFullTotalsAndLateSettlementsIntact() {
        assertEquals(64, FileLifecycleReportView.COPY_LIMIT);
        assertEquals(64, FileLifecycleReportView.RESOURCE_LIMIT);
        assertEquals(128, FileLifecycleReportView.EVENT_LIMIT);
        for (int count : new int[]{64, 65}) {
            List<Task> tasks = new ArrayList<>();
            Map<Integer, Double> capacities = new LinkedHashMap<>();
            for (int i = 0; i < count; i++) {
                tasks.add(task(i, in("file-" + i, 1)));
                capacities.put(i, 1.0);
            }
            DataTransferFabric fabric = DataTransferFabric.endpoints(capacities, Collections.singletonList("source"));
            CoherentDataflowRuntime runtime = runtime(true, BUDGET, plan(tasks.toArray(new Task[0])), fabric);
            for (int i = 0; i < count; i++) runtime.requestJob(i, one(i), i, 0);
            runtime.advance(1);
            FileLifecycleCodec.Decoded decoded = decoded(runtime);
            JsonObject view = tree(decoded);
            checkPreview(view, "copies", count, 64);
            checkPreview(view, "resources", count, 64);
            checkPreview(view, "events", count * 6, 128);
            for (JsonElement value : rows(view, "copies")) {
                JsonObject copy = value.getAsJsonObject();
                assertEquals("SETTLED", copy.get("status").getAsString(), "settlements occur after the event preview ends");
                assertEquals("1", copy.get("effectiveFctSeconds").getAsString());
                assertTrue(copy.getAsJsonArray("resources").size() <= FileLifecycleReportView.LIST_LIMIT);
                assertEquals("0", copy.get("resourcesOmitted").getAsString());
            }
            for (String key : Arrays.asList("admittedPayloadBytes", "settledPayloadBytes", "requiredReferenceBytes", "referenceCount")) {
                assertEquals(Integer.toString(count), view.getAsJsonObject("totals").get(key).getAsString(), key);
            }
            assertEquals("0", view.get("nestedItemsOmitted").getAsString());
            assertEquals("COMPLETE", view.get("captureStatus").getAsString());
            assertNoJsonNumbers(view);
        }
    }

    @Test void eventPreviewBoundaryDoesNotTurnACompleteCaptureIntoTruncation() {
        for (int count : new int[]{128, 129}) {
            FileItem[] files = new FileItem[count];
            for (int i = 0; i < count; i++) files[i] = in("seed-" + i, 1);
            JsonObject view = tree(decoded(runtime(true, plan(task(1, files)))));
            checkPreview(view, "events", count, 128);
            assertEquals(Integer.toString(count), view.get("retainedRecords").getAsString());
            assertEquals(Integer.toString(count), view.get("fileCount").getAsString());
            assertEquals("0", view.getAsJsonObject("totals").get("referenceCount").getAsString());
            assertTrue(view.get("quiescent").getAsBoolean());
        }
    }

    @Test void hostileFileNamesRemainLiteralTextRatherThanHtmlOrRawPayloads() {
        String name = "</script><img src=x onerror=alert(1)>节点\"'\u2028\u2029@@DATA@@";
        CoherentDataflowRuntime runtime = runtime(true, plan(task(1, in(name, 10))));
        runtime.requestJob(11, one(1), 7, 0);
        Map<String, Object> projection = FileLifecycleReportView.fromDecoded(decoded(runtime), false);
        JsonObject view = JSON.toJsonTree(projection).getAsJsonObject();
        assertEquals("input[0]/" + name, row(view, "copies", 0).get("fileId").getAsString());
        assertEquals("input[0]/" + name, event(rows(view, "events"), "EXTERNAL_SEEDED", 0).get("fileId").getAsString());
        assertEquals("0", view.get("labelsClipped").getAsString());
        for (JsonElement value : rows(view, "events")) {
            assertFalse(value.getAsJsonObject().get("detail").getAsString().contains("\"fileId\":"));
        }
        String encoded = JSON.toJson(projection);
        assertFalse(encoded.contains("</script>"));
        assertFalse(encoded.contains("<img"));
        assertTrue(encoded.contains("\\u003c"));
        assertDeepImmutable(projection);
        assertNoJsonNumbers(view);
    }

    @Test void labelsClipAtUtf16BoundariesWithoutClippingExactNumericIds() {
        int id = Integer.MAX_VALUE;
        String prefix = "input[" + id + "]/";
        for (int remaining : new int[]{1, 2}) {
            String padding = repeat('x', FileLifecycleReportView.LABEL_LIMIT - prefix.length() - remaining);
            String name = padding + "😀tail";
            Task task = task(id, in(name, 1));
            DataflowFilePlan plan = DataflowFilePlan.capture(Collections.singletonList(task), Collections.singletonMap(id, id));
            DataTransferFabric fabric = DataTransferFabric.endpoints(Collections.singletonMap(id, 1.0), Collections.singletonList("source"));
            CoherentDataflowRuntime runtime = runtime(true, BUDGET, plan, fabric);
            runtime.requestJob(id, one(id), id, 0);
            JsonObject view = tree(decoded(runtime)), copy = row(view, "copies", 0);
            String shown = copy.get("fileId").getAsString();
            assertEquals(prefix + padding + (remaining == 1 ? "" : "😀") + "…", shown);
            assertTrue(shown.length() <= FileLifecycleReportView.LABEL_LIMIT + 1);
            assertWellFormedSurrogates(shown);
            assertEquals("VM:" + id, copy.get("destination").getAsString());
            JsonObject requested = event(rows(view, "events"), "JOB_INPUT_REQUESTED", 0);
            assertEquals(Integer.toString(id), requested.get("jobId").getAsString());
            assertEquals(Integer.toString(id), requested.get("taskId").getAsString());
            assertTrue(requested.get("detail").getAsString().contains("taskIds=[" + id + "]"));
            assertTrue(Long.parseLong(view.get("labelsClipped").getAsString()) > 0);
            assertNoJsonNumbers(view);
        }
        String boundaryName = repeat('b', FileLifecycleReportView.LABEL_LIMIT - "input[0]/".length());
        CoherentDataflowRuntime runtime = runtime(true, plan(task(1, in(boundaryName, 1))));
        runtime.requestJob(11, one(1), 7, 0);
        JsonObject boundary = tree(decoded(runtime));
        assertEquals("input[0]/" + boundaryName, row(boundary, "copies", 0).get("fileId").getAsString());
        assertEquals("0", boundary.get("labelsClipped").getAsString());
    }

    @Test void clippedDisplayLabelsNeverBecomeFileIdentityJoinKeys() {
        String common = repeat('c', FileLifecycleReportView.LABEL_LIMIT * 2);
        CoherentDataflowRuntime runtime = runtime(true, plan(task(1, in(common + "a", .1), in(common + "b", .2))));
        runtime.requestJob(11, one(1), 7, 0);
        JsonObject view = tree(decoded(runtime));
        assertEquals("2", view.get("fileCount").getAsString());
        assertEquals("2", view.get("copyCount").getAsString());
        assertEquals(row(view, "copies", 0).get("fileId").getAsString(), row(view, "copies", 1).get("fileId").getAsString());
        String bytes = text(new BigDecimal(.1).add(new BigDecimal(.2)));
        assertEquals(bytes, view.getAsJsonObject("totals").get("requiredReferenceBytes").getAsString());
        assertEquals(bytes, view.getAsJsonObject("totals").get("admittedPayloadBytes").getAsString());
        assertEquals("2", view.getAsJsonObject("totals").get("referenceCount").getAsString());
    }

    @Test void independentMaxPayloadsAndNormalCapacitiesProduceExactBeyondDoubleTotals() {
        CoherentDataflowRuntime runtime = runtime(true, BUDGET,
                plan(task(1, in("a", Double.MAX_VALUE)), task(2, in("b", Double.MAX_VALUE))),
                endpoints(Double.MAX_VALUE, Double.MAX_VALUE, Double.MAX_VALUE));
        runtime.requestJob(11, one(1), 7, 0);
        runtime.requestJob(22, one(2), 42, 0);
        runtime.advance(1);
        finish(runtime, 11, 1, 7, 1, 1, true);
        finish(runtime, 22, 2, 42, 1, 1, true);
        JsonObject view = tree(decoded(runtime)), totals = view.getAsJsonObject("totals");
        String maximum = exact(Double.MAX_VALUE);
        String sum = text(new BigDecimal(Double.MAX_VALUE).multiply(BigDecimal.valueOf(2)));
        assertTrue(Double.isInfinite(new BigDecimal(sum).doubleValue()));
        for (String key : Arrays.asList("admittedPayloadBytes", "settledPayloadBytes", "requiredReferenceBytes")) {
            assertEquals(sum, totals.get(key).getAsString(), key);
        }
        assertEquals("2", totals.get("referenceCount").getAsString());
        assertEquals("0", totals.get("completionResidualBytes").getAsString());
        assertTrue(view.get("quiescent").getAsBoolean());
        assertEquals("0", view.get("labelsClipped").getAsString());
        for (JsonElement value : rows(view, "copies")) assertEquals(maximum, value.getAsJsonObject().get("payloadBytes").getAsString());
        for (JsonElement value : rows(view, "resources")) assertEquals(maximum, value.getAsJsonObject().get("capacityBytesPerSecond").getAsString());
        assertTrue(event(rows(view, "events"), "COPY_ADMITTED", 0).get("detail").getAsString().contains("payloadBytes=" + maximum));
        assertNoJsonNumbers(view);
    }

    @Test void smallestPositiveDurationKeepsThousandCharacterExactNumericStrings() {
        CoherentDataflowRuntime runtime = runtime(true, BUDGET, plan(task(1, in("tiny", Double.MIN_VALUE))), endpoints(1, 1, 1));
        runtime.requestJob(11, one(1), 7, 0);
        runtime.advance(Double.MIN_VALUE);
        JsonObject view = tree(decoded(runtime)), copy = row(view, "copies", 0);
        String tiny = new BigDecimal(Double.MIN_VALUE).toPlainString();
        assertTrue(tiny.length() > 1000);
        for (String key : Arrays.asList("effectiveCompletionTime", "observedCompletionTime", "effectiveFctSeconds",
                "observedFctSeconds", "payloadBytes", "isolatedSeconds")) assertEquals(tiny, copy.get(key).getAsString(), key);
        assertEquals("0", copy.get("notificationLagSeconds").getAsString());
        assertEquals("0", copy.get("completionResidualBytes").getAsString());
        assertEquals("SETTLED", copy.get("status").getAsString());
        assertEquals(tiny, view.get("observedThrough").getAsString());
        for (String key : Arrays.asList("admittedPayloadBytes", "settledPayloadBytes", "requiredReferenceBytes")) {
            assertEquals(tiny, view.getAsJsonObject("totals").get(key).getAsString(), key);
        }
        String admitted = event(rows(view, "events"), "COPY_ADMITTED", 0).get("detail").getAsString();
        String settled = event(rows(view, "events"), "COPY_SETTLED", 0).get("detail").getAsString();
        assertTrue(admitted.contains("payloadBytes=" + tiny));
        assertTrue(admitted.contains("isolatedSeconds=" + tiny));
        assertTrue(settled.contains("effectiveTime=" + tiny));
        assertFalse(admitted.contains("…"));
        assertFalse(settled.contains("…"));
        assertEquals("0", view.get("labelsClipped").getAsString());
        assertNoJsonNumbers(view);
    }

    @Test void sourceVmThenLinksThenDestinationOrderAndCapacityOnlyResourcesArePreserved() {
        Map<Integer, Integer> placement = new LinkedHashMap<>();
        placement.put(10, 0); placement.put(40, 0); placement.put(30, 1); placement.put(20, 3);
        FatTreeTopology topology = FatTreeTopology.fromSpec(NetworkTopologySpec.fatTree(4, .00002, 3, placement), Arrays.asList(10, 20, 30, 40));
        Map<Integer, Integer> hosts = new LinkedHashMap<>();
        hosts.put(7, 10); hosts.put(42, 20); hosts.put(99, 30);
        DataTransferFabric fabric = DataTransferFabric.fatTree(capacities(100, 100, 100), hosts, topology, Collections.singletonList("source"));
        Task producer = task(1, out("f", 40)), consumer = task(2, in("f", 40));
        edge(producer, consumer);
        CoherentDataflowRuntime runtime = runtime(true, BUDGET, plan(producer, consumer), fabric);
        produce(runtime, 11, 1, 7, 0, 1, true);
        runtime.requestJob(22, one(2), 42, 1);
        JsonObject view = tree(decoded(runtime)), copy = row(view, "copies", 0);
        assertEquals(Arrays.asList("VM:7", "LINK:ACC:10->EDGE:0:0", "LINK:EDGE:0:0->AGG:0:0",
                "LINK:AGG:0:0->CORE:0", "LINK:CORE:0->AGG:1:0", "LINK:AGG:1:0->EDGE:1:1",
                "LINK:EDGE:1:1->ACC:20", "VM:42"), strings(copy.getAsJsonArray("resources")));
        assertEquals("0", copy.get("resourcesOmitted").getAsString());
        assertEquals("0", view.get("nestedItemsOmitted").getAsString());
        for (JsonElement value : rows(view, "resources")) {
            assertEquals(keys("resourceKey", "capacityBytesPerSecond"), value.getAsJsonObject().keySet());
        }
        assertNoJsonNumbers(view);
    }

    private static CoherentDataflowRuntime fanIn(boolean shared) {
        Task producer = task(1, out("fast", 10), out("slow", 100));
        Task both = task(2, in("fast", 10), in("fast", 10), in("slow", 100));
        Task other = task(3, in("fast", 10)), join = task(4, in("fast", 10));
        edge(producer, both); edge(producer, other); edge(producer, join);
        CoherentDataflowRuntime runtime = runtime(shared, plan(producer, both, other, join));
        produce(runtime, 10, 1, 7, 0, 1, true);
        runtime.requestJob(20, one(2), 99, 1);
        runtime.requestJob(40, one(4), 99, 1.5);
        runtime.advance(3);
        finish(runtime, 40, 4, 99, 3, 3, true);
        runtime.requestJob(30, one(3), 42, 3);
        runtime.advance(4);
        finish(runtime, 30, 3, 42, 4, 4, true);
        runtime.advance(12);
        finish(runtime, 20, 2, 99, 12, 13, true);
        return runtime;
    }

    private static CoherentDataflowRuntime runtime(boolean shared, DataflowFilePlan plan) {
        return runtime(shared, BUDGET, plan, endpoints(10, 100, 100));
    }
    private static CoherentDataflowRuntime runtime(boolean shared, int budget, DataflowFilePlan plan, DataTransferFabric fabric) {
        CoherentDataflowRuntime runtime = new CoherentDataflowRuntime(shared, budget);
        runtime.initializePlan(plan, fabric.getLocations());
        runtime.bindFabric(fabric);
        return runtime;
    }
    private static DataTransferFabric endpoints(double a, double b, double c) {
        return DataTransferFabric.endpoints(capacities(a, b, c), Collections.singletonList("source"));
    }
    private static Map<Integer, Double> capacities(double a, double b, double c) {
        Map<Integer, Double> result = new LinkedHashMap<>();
        result.put(7, a); result.put(42, b); result.put(99, c);
        return result;
    }
    private static DataflowFilePlan plan(Task... tasks) {
        Map<Integer, Integer> scopes = new LinkedHashMap<>();
        for (Task task : tasks) scopes.put(task.getCloudletId(), 0);
        return DataflowFilePlan.capture(Arrays.asList(tasks), scopes);
    }
    private static Task task(int id, FileItem... files) {
        Task task = new Task(id, 1000);
        for (FileItem file : files) task.addFile(file);
        return task;
    }
    private static FileItem in(String name, double bytes) { return file(name, bytes, FileType.INPUT); }
    private static FileItem out(String name, double bytes) { return file(name, bytes, FileType.OUTPUT); }
    private static FileItem file(String name, double bytes, FileType type) {
        FileItem file = new FileItem(name, bytes); file.setType(type); return file;
    }
    private static void edge(Task parent, Task child) { parent.addChild(child); child.addParent(parent); }
    private static List<Integer> one(int id) { return Collections.singletonList(id); }
    private static void produce(CoherentDataflowRuntime runtime, int job, int task, int vm, double start, double end, boolean success) {
        runtime.requestJob(job, one(task), vm, start);
        finish(runtime, job, task, vm, start, end, success);
    }
    private static void finish(CoherentDataflowRuntime runtime, int job, int task, int vm, double start, double end, boolean success) {
        runtime.drainReadyJobIds();
        runtime.cpuStarted(job, one(task), vm, start);
        runtime.jobFinished(job, one(task), vm, Collections.singletonList(success), end);
    }
    private static FileLifecycleCodec.Decoded decoded(CoherentDataflowRuntime runtime) {
        return FileLifecycleCodec.decode(FileLifecycleCodec.encode(runtime.captureEvidence()));
    }
    private static JsonObject tree(FileLifecycleCodec.Decoded decoded) {
        return JSON.toJsonTree(FileLifecycleReportView.fromDecoded(decoded, false)).getAsJsonObject();
    }
    private static JsonArray rows(JsonObject view, String key) { return view.getAsJsonObject(key).getAsJsonArray("rows"); }
    private static JsonObject row(JsonObject view, String key, int index) { return rows(view, key).get(index).getAsJsonObject(); }
    private static JsonObject event(JsonArray events, String type, int occurrence) {
        for (JsonElement value : events) {
            JsonObject event = value.getAsJsonObject();
            if (type.equals(event.get("type").getAsString()) && occurrence-- == 0) return event;
        }
        throw new AssertionError("Missing event " + type);
    }
    private static List<String> strings(JsonArray values) {
        List<String> strings = new ArrayList<>();
        for (JsonElement value : values) strings.add(value.getAsString());
        return strings;
    }
    private static Set<String> keys(String... keys) { return new HashSet<>(Arrays.asList(keys)); }
    private static String exact(double value) { return text(new BigDecimal(value)); }
    private static String difference(double end, double start) { return text(new BigDecimal(end).subtract(new BigDecimal(start))); }
    private static String text(BigDecimal value) { return value.stripTrailingZeros().toPlainString(); }
    private static String repeat(char value, int count) {
        char[] chars = new char[count]; Arrays.fill(chars, value); return new String(chars);
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
                    "Projection leaked a Number, JSON node, or another non-display object");
        }
    }
    private static void assertWellFormedSurrogates(String value) {
        for (int i = 0; i < value.length(); i++) {
            char current = value.charAt(i);
            if (Character.isHighSurrogate(current)) {
                assertTrue(i + 1 < value.length() && Character.isLowSurrogate(value.charAt(i + 1)));
                i++;
            } else {
                assertFalse(Character.isLowSurrogate(current));
            }
        }
    }
}
