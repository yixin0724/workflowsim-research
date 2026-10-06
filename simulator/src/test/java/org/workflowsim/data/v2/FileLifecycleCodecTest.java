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

/** Runtime captures are positive fixtures; literal paths, prefixes and tampering are independent oracles. */
class FileLifecycleCodecTest {
    private static final String LITERAL = "{"
            + "\"schema\":\"workflowsim-file-lifecycle-v2\",\"modelKind\":\"COHERENT_FILE_DATAFLOW_V2\","
            + "\"recording\":{\"mode\":\"FILE_LIFECYCLE_V2\",\"maxTraceRecords\":32},"
            + "\"certificateScope\":\"FILE_LIFECYCLE_NOT_FLUID_SERVICE_ACCOUNTING_V2\","
            + "\"policies\":{\"fileIdentity\":\"SCOPED_RESOLVED_WRITE_ONCE_FILES_V2\","
            + "\"release\":\"DEPENDENCY_READY_AT_OBSERVATION_V2\",\"visibility\":\"PER_FILE_SETTLEMENT_OBSERVATION_V2\","
            + "\"selection\":\"VISIBLE_LOCAL_OR_MAX_ISOLATED_RATE_STABLE_LOCATION_V2\","
            + "\"sourceAccess\":\"UNBOUNDED_OFF_FABRIC_SOURCE_INPUT_V2\",\"sharing\":\"SHARED_MAX_MIN\"},"
            + "\"capture\":{\"status\":\"COMPLETE\",\"observedThrough\":0,\"retainedRecords\":4,\"droppedRecords\":0},"
            + "\"filePlan\":{\"contractVersion\":\"SCOPED_RESOLVED_WRITE_ONCE_FILES_V2\",\"tasks\":[{"
            + "\"taskId\":1,\"workflowInputIndex\":0,\"parents\":[],\"inputs\":[{\"fileId\":{\"workflowInputIndex\":0,\"name\":\"x\"},\"referenceCount\":1}],\"outputs\":[]}],"
            + "\"files\":[{\"fileId\":{\"workflowInputIndex\":0,\"name\":\"x\"},\"bytes\":10,\"producerTaskId\":null}]},"
            + "\"fabric\":{\"locations\":[{\"kind\":\"VM\",\"vmId\":7,\"sourceId\":null},{\"kind\":\"SOURCE\",\"vmId\":null,\"sourceId\":\"source\"}],"
            + "\"resources\":[{\"key\":\"VM:7\",\"capacityBytesPerSecond\":10}],\"vmHostAssignments\":[],\"topology\":null},"
            + "\"events\":[{\"sequence\":1,\"observedTime\":0,\"type\":\"EXTERNAL_SEEDED\",\"payload\":{\"fileId\":{\"workflowInputIndex\":0,\"name\":\"x\"},\"location\":{\"kind\":\"SOURCE\",\"vmId\":null,\"sourceId\":\"source\"}}},"
            + "{\"sequence\":2,\"observedTime\":0,\"type\":\"JOB_INPUT_REQUESTED\",\"payload\":{\"jobId\":0,\"taskIds\":[1],\"destinationVmId\":7}},"
            + "{\"sequence\":3,\"observedTime\":0,\"type\":\"COPY_ADMITTED\",\"payload\":{\"copyOrdinal\":1,\"fileId\":{\"workflowInputIndex\":0,\"name\":\"x\"},\"bytes\":10,"
            + "\"sourceReplica\":{\"fileId\":{\"workflowInputIndex\":0,\"name\":\"x\"},\"location\":{\"kind\":\"SOURCE\",\"vmId\":null,\"sourceId\":\"source\"},\"visibleAt\":0,\"acquisition\":\"EXTERNAL_SEED\","
            + "\"origin\":{\"producerTaskId\":null,\"jobAttemptId\":null,\"location\":{\"kind\":\"SOURCE\",\"vmId\":null,\"sourceId\":\"source\"},\"observedAt\":0},\"copiedFrom\":null,\"copyOrdinal\":null},"
            + "\"destinationVmId\":7,\"resources\":[\"VM:7\"],\"standaloneRate\":10,\"isolatedSeconds\":1}},"
            + "{\"sequence\":4,\"observedTime\":0,\"type\":\"INPUT_RESOLVED\",\"payload\":{\"jobId\":0,\"fileId\":{\"workflowInputIndex\":0,\"name\":\"x\"},\"referenceCount\":1,\"resolution\":\"NEW_COPY\",\"copyOrdinal\":1,\"source\":{\"kind\":\"SOURCE\",\"vmId\":null,\"sourceId\":\"source\"}}}]}";

    @Test void actualSharedAndIsolatedFanInJoinCacheAndCountsRoundTrip() {
        for (boolean shared : new boolean[]{true, false}) {
            FileLifecycleEvidence evidence = fanIn(shared);
            String encoded = FileLifecycleCodec.encode(evidence);
            FileLifecycleCodec.Decoded decoded = FileLifecycleCodec.decode(encoded);
            assertEquals(encoded, FileLifecycleCodec.encode(decoded.getEvidence()));
            assertTrue(decoded.isQuiescent());
            assertEquals(4, decoded.getRequestedJobCount()); assertEquals(4, decoded.getCompletedJobCount());
            assertEquals(3, decoded.getCopyCount()); assertEquals(3, decoded.getCompletedCopyCount()); assertEquals(0, decoded.getActiveCopyCount());
            assertEquals(new TreeSet<>(Arrays.asList(1, 2, 3, 4)), decoded.getSuccessfullyCompletedTaskIds());
            JsonObject root = decoded.getDocument(), lastCopy = payload(root, "COPY_ADMITTED", 2);
            assertEquals(Arrays.asList("VM:99", "VM:42"), strings(lastCopy.getAsJsonArray("resources")));
            assertEquals(99, lastCopy.getAsJsonObject("sourceReplica").getAsJsonObject("location").get("vmId").getAsInt());
            assertEquals(7, lastCopy.getAsJsonObject("sourceReplica").getAsJsonObject("origin").getAsJsonObject("location").get("vmId").getAsInt());
            assertEquals("JOIN_EXISTING", payload(root, "INPUT_RESOLVED", 2).get("resolution").getAsString());
            assertEquals(2, payload(root, "INPUT_RESOLVED", 0).get("referenceCount").getAsInt());
        }
    }

    @Test void literalCompletePrefixNeedNotBeQuiescentOrRequestAllTasks() {
        FileLifecycleCodec.Decoded decoded = FileLifecycleCodec.decode(LITERAL);
        assertFalse(decoded.isQuiescent()); assertEquals(1, decoded.getRequestedJobCount());
        assertEquals(0, decoded.getCompletedJobCount()); assertEquals(1, decoded.getActiveCopyCount());
        JsonObject seedsOnly = literal();
        while (events(seedsOnly).size() > 1) events(seedsOnly).remove(events(seedsOnly).size() - 1);
        recount(seedsOnly);
        assertTrue(FileLifecycleCodec.decodeDocument(seedsOnly).isQuiescent(), "standalone quiescence does not require every logical Task");
        FileLifecycleEvidence empty = runtime(true, 100, plan()).captureEvidence();
        assertTrue(FileLifecycleCodec.decode(FileLifecycleCodec.encode(empty)).isQuiescent());
    }

    @Test void decoderAndEncoderRetainOnlyImmutableSnapshots() {
        JsonObject source = FileLifecycleCodec.document(fanIn(true));
        FileLifecycleCodec.Decoded decoded = FileLifecycleCodec.decodeDocument(source);
        String before = FileLifecycleCodec.encode(decoded.getEvidence());
        source.addProperty("schema", "changed");
        decoded.getDocument().getAsJsonObject("capture").addProperty("observedThrough", 999);
        decoded.getEvidence().getFilePlan().remove("tasks");
        decoded.getEvidence().getFabric().remove("locations");
        decoded.getEvidence().getEvents().get(0).getPayload().addProperty("jobId", 999);
        assertEquals(before, FileLifecycleCodec.encode(decoded.getEvidence()));
        assertEquals(FileLifecycleCodec.SCHEMA, decoded.getDocument().get("schema").getAsString());
        assertThrows(UnsupportedOperationException.class, () -> decoded.getEvidence().getEvents().clear());
        assertThrows(UnsupportedOperationException.class, () -> decoded.getSuccessfullyCompletedTaskIds().clear());
        CoherentDataflowRuntime runtime = runtime(true, 100, plan(task(1, in("x", 10))));
        runtime.requestJob(0, one(1), 7, 0);
        FileLifecycleEvidence frozen = runtime.captureEvidence();
        runtime.advance(1);
        assertEquals(4, frozen.getEvents().size());
        assertEquals(1, FileLifecycleCodec.decode(FileLifecycleCodec.encode(frozen)).getActiveCopyCount());
    }

    @Test void zeroLocalAndExactMultiWorkflowNamesNeverAlias() {
        String name = "input[0]/shared::名/<literal>";
        Task a = task(1, in(name, 0)), b = task(2, in(name, 0)), c = task(3, in(name, 0)), d = task(4, in(name, 0));
        Map<Integer, Integer> scopes = new LinkedHashMap<>(); scopes.put(1, 0); scopes.put(2, 0); scopes.put(3, 1); scopes.put(4, 0);
        CoherentDataflowRuntime r = runtime(true, 100, DataflowFilePlan.capture(Arrays.asList(a, b, c, d), scopes));
        produce(r, 10, 1, 7, 0, 1, true); produce(r, 20, 2, 7, 1, 2, true);
        produce(r, 30, 3, 7, 2, 3, true); produce(r, 40, 4, 42, 3, 4, true);
        JsonObject root = FileLifecycleCodec.document(r.captureEvidence());
        assertEquals("ZERO", payload(root, "INPUT_RESOLVED", 0).get("resolution").getAsString());
        assertEquals("LOCAL", payload(root, "INPUT_RESOLVED", 1).get("resolution").getAsString());
        assertEquals("ZERO", payload(root, "INPUT_RESOLVED", 2).get("resolution").getAsString());
        assertEquals(7, payload(root, "INPUT_RESOLVED", 3).getAsJsonObject("source").get("vmId").getAsInt());
        assertEquals(0, FileLifecycleCodec.decodeDocument(root).getCopyCount());
        JsonObject wrong = root.deepCopy();
        payload(wrong, "INPUT_RESOLVED", 2).getAsJsonObject("fileId").addProperty("workflowInputIndex", 0); bad(wrong);
        wrong = root.deepCopy(); payload(wrong, "INPUT_RESOLVED", 0).addProperty("resolution", "LOCAL"); bad(wrong);
        wrong = root.deepCopy(); payload(wrong, "INPUT_RESOLVED", 1).addProperty("copyOrdinal", 1); bad(wrong);
    }

    @Test void failedAttemptCannotPublishAndSuccessfulRetryKeepsActualRootOrigin() {
        Task p = task(1, out("f", 10)), c = task(2, in("f", 10)); edge(p, c);
        CoherentDataflowRuntime r = runtime(true, 100, plan(p, c));
        produce(r, 8, 1, 7, 0, 1, false); produce(r, 9, 1, 42, 1, 2, true);
        r.requestJob(10, one(2), 99, 2); r.advance(3); finishReady(r, 10, 2, 99, 3, 4);
        JsonObject root = FileLifecycleCodec.document(r.captureEvidence());
        assertEquals(3, FileLifecycleCodec.decodeDocument(root).getCompletedJobCount());
        assertEquals(9, payload(root, "COPY_ADMITTED", 0).getAsJsonObject("sourceReplica").getAsJsonObject("origin").get("jobAttemptId").getAsInt());
        JsonObject wrong = root.deepCopy(); payload(wrong, "COPY_ADMITTED", 0).getAsJsonObject("sourceReplica").getAsJsonObject("origin").addProperty("jobAttemptId", 8); bad(wrong);
        wrong = root.deepCopy(); payload(wrong, "TASK_FINISHED", 1).addProperty("success", false); bad(wrong);
        wrong = root.deepCopy(); payload(wrong, "TASK_FINISHED", 0).addProperty("vmId", 42); bad(wrong);
        wrong = root.deepCopy(); payload(wrong, "TASK_FINISHED", 0).addProperty("taskId", 2); bad(wrong);
        wrong = root.deepCopy(); payload(wrong, "TASK_FINISHED", 0).addProperty("success", "false"); bad(wrong);
    }

    @Test void outputMayReleaseWaiterBeforeCopyAndLaterSettlementPreservesEarlierReplica() {
        Task p = task(1, out("f", 100)), a = task(2, in("f", 100)), b = task(3, in("f", 100)); edge(p, a); edge(p, b);
        CoherentDataflowRuntime r = runtime(true, 100, plan(p, a, b));
        produce(r, 1, 1, 7, 0, 1, true); r.requestJob(2, one(2), 99, 1);
        produce(r, 3, 1, 99, 2, 3, true); finishReady(r, 2, 2, 99, 3, 4);
        r.advance(12); r.requestJob(4, one(3), 42, 12); r.advance(13); finishReady(r, 4, 3, 42, 13, 14);
        JsonObject root = FileLifecycleCodec.document(r.captureEvidence());
        JsonObject source = payload(root, "COPY_ADMITTED", 1).getAsJsonObject("sourceReplica");
        assertEquals("TASK_OUTPUT", source.get("acquisition").getAsString());
        assertEquals(3, source.getAsJsonObject("origin").get("jobAttemptId").getAsInt());
        assertEquals(3, source.get("visibleAt").getAsDouble(), 0); assertTrue(source.get("copyOrdinal").isJsonNull());
        JsonObject wrong = root.deepCopy(); removeEvent(wrong, "JOB_DATA_READY", 2); bad(wrong);
        wrong = root.deepCopy(); payload(wrong, "COPY_ADMITTED", 1).getAsJsonObject("sourceReplica").addProperty("acquisition", "COPY_SETTLEMENT"); bad(wrong);
    }

    @Test void bestSourceAndNumericVmTieOrderAreIndependentlyChecked() {
        CoherentDataflowRuntime r = runtime(true, 100, plan(task(1, in("x", 10)), task(2, in("x", 10)), task(3, in("x", 10))), endpoints(100, 100, 100));
        r.requestJob(1, one(1), 7, 0); r.advance(1); finishReady(r, 1, 1, 7, 1, 1);
        r.requestJob(2, one(2), 42, 1); r.advance(2); finishReady(r, 2, 2, 42, 2, 2);
        r.requestJob(3, one(3), 99, 2);
        JsonObject root = FileLifecycleCodec.document(r.captureEvidence());
        assertEquals(7, payload(root, "COPY_ADMITTED", 2).getAsJsonObject("sourceReplica").getAsJsonObject("location").get("vmId").getAsInt());
        JsonObject wrong = root.deepCopy(), admission = payload(wrong, "COPY_ADMITTED", 2), replica = admission.getAsJsonObject("sourceReplica");
        // VM:42 is visible and ties the rate, but numeric VM 7 precedes VM 42 (and both precede SOURCE).
        replica.add("location", vm(42)); replica.addProperty("visibleAt", 2); replica.addProperty("copyOrdinal", 2); replica.add("copiedFrom", vm(7));
        admission.add("resources", arrayOf("VM:42", "VM:99")); payload(wrong, "INPUT_RESOLVED", 2).add("source", vm(42)); bad(wrong);
        wrong = FileLifecycleCodec.document(fanIn(true));
        JsonObject firstSource = payload(wrong, "COPY_ADMITTED", 0).getAsJsonObject("sourceReplica").deepCopy();
        admission = payload(wrong, "COPY_ADMITTED", 2); admission.add("sourceReplica", firstSource);
        admission.add("resources", arrayOf("VM:7", "VM:42")); admission.addProperty("standaloneRate", 10); admission.addProperty("isolatedSeconds", 1);
        payload(wrong, "INPUT_RESOLVED", 3).add("source", vm(7)); bad(wrong);
    }

    @Test void literalFatTreePathsCoverSameHostEdgePodAndOversubscribedCrossPod() {
        String[][] middles = {
            {},
            {"LINK:ACC:10->EDGE:0:0", "LINK:EDGE:0:0->ACC:40"},
            {"LINK:ACC:10->EDGE:0:0", "LINK:EDGE:0:0->AGG:0:0", "LINK:AGG:0:0->EDGE:0:1", "LINK:EDGE:0:1->ACC:30"},
            {"LINK:ACC:10->EDGE:0:0", "LINK:EDGE:0:0->AGG:0:0", "LINK:AGG:0:0->CORE:0", "LINK:CORE:0->AGG:1:0", "LINK:AGG:1:0->EDGE:1:1", "LINK:EDGE:1:1->ACC:20"}
        };
        int[] targets = {10, 40, 30, 20};
        for (int i = 0; i < targets.length; i++) {
            JsonObject root = treeCapture(targets[i]);
            List<String> expected = new ArrayList<>(); expected.add("VM:7"); expected.addAll(Arrays.asList(middles[i])); expected.add("VM:42");
            assertEquals(expected, strings(payload(root, "COPY_ADMITTED", 0).getAsJsonArray("resources")));
            JsonObject wrong = root.deepCopy(); payload(wrong, "COPY_ADMITTED", 0).getAsJsonArray("resources").set(0, new JsonPrimitive("VM:42")); bad(wrong);
        }
    }

    @Test void fabricRequiresExactPhysicalInventoryPlacementsAndSafeTopologyBounds() {
        JsonObject source = treeCapture(20), wrong = source.deepCopy();
        wrong.getAsJsonObject("fabric").getAsJsonArray("resources").remove(0); bad(wrong);
        wrong = source.deepCopy(); wrong.getAsJsonObject("fabric").getAsJsonArray("resources").get(0).getAsJsonObject().addProperty("capacityBytesPerSecond", 7); bad(wrong);
        wrong = source.deepCopy(); wrong.getAsJsonObject("fabric").getAsJsonArray("vmHostAssignments").remove(0); bad(wrong);
        wrong = source.deepCopy(); wrong.getAsJsonObject("fabric").getAsJsonObject("topology").addProperty("k", Integer.MAX_VALUE); bad(wrong);
        wrong = source.deepCopy(); wrong.getAsJsonObject("fabric").getAsJsonObject("topology").addProperty("coreSwitchCount", 0); bad(wrong);
        wrong = source.deepCopy(); JsonArray hosts = wrong.getAsJsonObject("fabric").getAsJsonObject("topology").getAsJsonArray("hostPlacements");
        for (JsonElement host : hosts) { host.getAsJsonObject().addProperty("pod", 0); host.getAsJsonObject().addProperty("edge", 0); } bad(wrong);
        wrong = literal(); wrong.getAsJsonObject("fabric").getAsJsonArray("locations").add(vm(7)); bad(wrong);
        wrong = literal(); wrong.getAsJsonObject("fabric").getAsJsonArray("locations").get(1).getAsJsonObject().addProperty("sourceId", "other"); bad(wrong);
        wrong = literal(); wrong.getAsJsonObject("fabric").getAsJsonArray("vmHostAssignments").add(NetworkLedgerCodec.parseDocument("{\"vmId\":7,\"hostId\":0}")); bad(wrong);
    }

    @Test void plansAcceptIndirectAncestorsButRejectCyclesWritersScopesAndUnknownReferences() {
        Task p = task(1, out("f", 10)), middle = task(2), c = task(3, in("f", 10)); edge(p, middle); edge(middle, c);
        CoherentDataflowRuntime r = runtime(true, 100, plan(p, middle, c));
        produce(r, 1, 1, 7, 0, 1, true); produce(r, 2, 2, 42, 1, 2, true); r.requestJob(3, one(3), 99, 2);
        JsonObject valid = FileLifecycleCodec.document(r.captureEvidence()), plan = valid.getAsJsonObject("filePlan");
        JsonObject before = plan.deepCopy(); FileLifecycleCodec.validatePlanDocument(plan); assertEquals(before, plan);
        JsonObject wrong = valid.deepCopy(); taskRow(wrong, 1).add("parents", new JsonArray()); bad(wrong);
        wrong = valid.deepCopy(); taskRow(wrong, 0).getAsJsonArray("parents").add(3); bad(wrong);
        wrong = valid.deepCopy(); taskRow(wrong, 1).addProperty("workflowInputIndex", 1); bad(wrong);
        wrong = valid.deepCopy(); taskRow(wrong, 1).getAsJsonArray("outputs").add(taskRow(wrong, 0).getAsJsonArray("outputs").get(0).deepCopy()); bad(wrong);
        wrong = literal(); taskRow(wrong, 0).getAsJsonArray("outputs").add(taskRow(wrong, 0).getAsJsonArray("inputs").get(0).getAsJsonObject().get("fileId").deepCopy()); bad(wrong);
        wrong = literal(); wrong.getAsJsonObject("filePlan").getAsJsonArray("files").remove(0); bad(wrong);
        wrong = literal(); fileRow(wrong, 0).addProperty("producerTaskId", 1); bad(wrong);
    }

    @Test void planOnlyLongMultiplicitiesStayExactAndAreNeverMaterialized() {
        JsonObject root = literal(); while (events(root).size() > 1) events(root).remove(events(root).size() - 1); recount(root);
        JsonObject input = taskRow(root, 0).getAsJsonArray("inputs").get(0).getAsJsonObject(); input.addProperty("referenceCount", Long.MAX_VALUE);
        FileLifecycleCodec.validatePlanDocument(root.getAsJsonObject("filePlan"));
        assertEquals(Long.MAX_VALUE, FileLifecycleCodec.decodeDocument(root).getEvidence().getFilePlan().getAsJsonArray("tasks").get(0).getAsJsonObject().getAsJsonArray("inputs").get(0).getAsJsonObject().get("referenceCount").getAsLong());
        input.addProperty("referenceCount", new BigDecimal("9223372036854775807.1")); bad(root);
        JsonObject requested = literal(); taskRow(requested, 0).getAsJsonArray("inputs").get(0).getAsJsonObject().addProperty("referenceCount", 2147483648L);
        payload(requested, "INPUT_RESOLVED", 0).addProperty("referenceCount", 2147483648L);
        assertDoesNotThrow(() -> FileLifecycleCodec.validatePlanDocument(requested.getAsJsonObject("filePlan"))); bad(requested);
    }

    @Test void lexicalDuplicateKeysAndExactIntegerLimitsCannotBeLaunderedThroughDoubles() {
        assertThrows(IllegalArgumentException.class, () -> FileLifecycleCodec.decode(LITERAL.replace("\"schema\":", "\"schema\":\"duplicate\",\"schema\":")));
        assertThrows(IllegalArgumentException.class, () -> FileLifecycleCodec.decode(LITERAL.replaceFirst("\"jobId\":0", "\"jobId\":0,\"jobId\":0")));
        for (String text : Arrays.asList(LITERAL + "{}", LITERAL + " //comment", "{schema:'x'}", "{\"schema\":1,\"\\u0073chema\":2}"))
            assertThrows(IllegalArgumentException.class, () -> FileLifecycleCodec.decode(text));
        for (BigDecimal id : Arrays.asList(new BigDecimal("9007199254740993"), new BigDecimal("2147483648"), new BigDecimal("0.000000000000000000001"))) {
            JsonObject wrong = literal(); payload(wrong, "JOB_INPUT_REQUESTED", 0).addProperty("jobId", id); bad(wrong);
        }
        JsonObject wrong = literal(); payload(wrong, "INPUT_RESOLVED", 0).addProperty("copyOrdinal", new BigDecimal("1.0000000000000000000001")); bad(wrong);
        wrong = literal(); payload(wrong, "COPY_ADMITTED", 0).addProperty("copyOrdinal", new BigDecimal("9223372036854775808")); bad(wrong);
        JsonObject maximum = literal(); payload(maximum, "JOB_INPUT_REQUESTED", 0).addProperty("jobId", Integer.MAX_VALUE); payload(maximum, "INPUT_RESOLVED", 0).addProperty("jobId", Integer.MAX_VALUE);
        assertEquals(1, FileLifecycleCodec.decode(maximum.toString()).getRequestedJobCount());
    }

    @Test void missingOrInterleavedInputResolutionsAndAdmissionsAreNotValidPrefixes() {
        for (String type : Arrays.asList("JOB_INPUT_REQUESTED", "COPY_ADMITTED", "INPUT_RESOLVED")) {
            JsonObject wrong = literal(); removeEvent(wrong, type, 0); bad(wrong);
        }
        JsonObject wrong = literal(); event(wrong, "INPUT_RESOLVED", 0).addProperty("observedTime", 1); wrong.getAsJsonObject("capture").addProperty("observedThrough", 1); bad(wrong);
        wrong = literal(); payload(wrong, "INPUT_RESOLVED", 0).addProperty("referenceCount", 2); bad(wrong);
        wrong = literal(); payload(wrong, "INPUT_RESOLVED", 0).addProperty("resolution", "JOIN_EXISTING"); bad(wrong);
        wrong = literal(); events(wrong).add(event(wrong, "INPUT_RESOLVED", 0).deepCopy()); recount(wrong); bad(wrong);
        wrong = literal(); payload(wrong, "JOB_INPUT_REQUESTED", 0).getAsJsonArray("taskIds").add(1); bad(wrong);
        wrong = FileLifecycleCodec.document(fanIn(true)); payload(wrong, "INPUT_RESOLVED", 2).addProperty("copyOrdinal", 2); bad(wrong);
    }

    @Test void readinessIsImmediateIncludingEmptyJobsAndCpuCannotCreateItsPermission() {
        JsonObject root = settledLiteral(0, 1, 1, 0), wrong = root.deepCopy();
        removeEvent(wrong, "JOB_DATA_READY", 0); bad(wrong);
        wrong = root.deepCopy(); event(wrong, "JOB_DATA_READY", 0).addProperty("observedTime", 2); wrong.getAsJsonObject("capture").addProperty("observedThrough", 2); bad(wrong);
        wrong = literal(); append(wrong, "JOB_CPU_STARTED", 1, "{\"jobId\":0,\"vmId\":7}"); bad(wrong);
        wrong = literal(); append(wrong, "TASK_FINISHED", 1, "{\"jobId\":0,\"taskId\":1,\"vmId\":7,\"success\":true}"); bad(wrong);
        wrong = root.deepCopy(); append(wrong, "JOB_DATA_READY", 1, "{\"jobId\":0}"); bad(wrong);
        CoherentDataflowRuntime r = runtime(true, 100, plan(task(1))); r.requestJob(0, one(1), 7, 0);
        wrong = FileLifecycleCodec.document(r.captureEvidence()); removeEvent(wrong, "JOB_DATA_READY", 0); bad(wrong);
        wrong = root.deepCopy(); append(wrong, "JOB_CPU_STARTED", 1, "{\"jobId\":0,\"vmId\":7}");
        append(wrong, "JOB_CPU_STARTED", 1, "{\"jobId\":0,\"vmId\":7}"); bad(wrong);
    }

    @Test void initialSeedsAreEntireOnceAtZeroBeforeAnyRequests() {
        JsonObject wrong = literal(); removeEvent(wrong, "EXTERNAL_SEEDED", 0); bad(wrong);
        wrong = literal(); payload(wrong, "EXTERNAL_SEEDED", 0).add("location", vm(7)); bad(wrong);
        wrong = literal(); events(wrong).add(event(wrong, "EXTERNAL_SEEDED", 0).deepCopy()); recount(wrong); bad(wrong);
        wrong = literal(); for (JsonElement e : events(wrong)) e.getAsJsonObject().addProperty("observedTime", 1); wrong.getAsJsonObject("capture").addProperty("observedThrough", 1); bad(wrong);
        wrong = literal(); events(wrong).remove(0); events(wrong).add(event(literal(), "EXTERNAL_SEEDED", 0).deepCopy()); recount(wrong); bad(wrong);
    }

    @Test void settlementRejectsDuplicatesEarlyClocksAndExcessResidualButAcceptsBoundary() {
        JsonObject wrong = settledLiteral(0, 1, .5, 0); bad(wrong);
        wrong = settledLiteral(0, 1, 1, 0); append(wrong, "COPY_SETTLED", 1, "{\"copyOrdinal\":1,\"effectiveTime\":1,\"remainingAfterService\":0}"); bad(wrong);
        wrong = settledLiteral(0, 1, 0, 0); bad(wrong);
        wrong = settledLiteral(0, 0, 0, 0); bad(wrong);
        double bound = Math.min(10 * .5, Math.max(10 * 1e-9, 4 * Math.ulp(10.0)));
        double lower = (10 - bound) / 10;
        assertEquals(1, FileLifecycleCodec.decodeDocument(settledLiteral(0, 1, lower, bound)).getCompletedCopyCount());
        wrong = settledLiteral(0, 1, lower, Math.nextUp(bound)); bad(wrong);
        wrong = settledLiteral(0, 1, 2, 0); bad(wrong);
    }

    @Test void binary64ClockLowerBoundUsesBothUlpAndRelativeCapsExactly() {
        double release = 1e12, lower = release + 1, observed = release + 2;
        assertDoesNotThrow(() -> FileLifecycleCodec.decodeDocument(settledLiteral(release, observed, Math.nextDown(lower), 0)));
        bad(settledLiteral(release, observed, lower - 9 * Math.ulp(lower), 0));
        double tiny = 3 * Double.MIN_VALUE;
        JsonObject root = settledLiteral(0, tiny, tiny, 0);
        setBytesAndRate(root, tiny, 1);
        assertDoesNotThrow(() -> FileLifecycleCodec.decodeDocument(root));
        payload(root, "COPY_SETTLED", 0).addProperty("effectiveTime", 2 * Double.MIN_VALUE); bad(root);
        CoherentDataflowRuntime r = runtime(true, 100, plan(task(1, in("x", 10))));
        r.requestJob(0, one(1), 7, release); r.advance(observed);
        assertDoesNotThrow(() -> FileLifecycleCodec.encode(r.captureEvidence()));
    }

    @Test void actualResidualAndContendedOverflowOfStandaloneAreaRemainValidLifecycleEvidence() {
        CoherentDataflowRuntime r = runtime(true, 100, plan(task(1, in("a", 1), in("b", 1 + 1e-10))));
        r.requestJob(0, one(1), 7, 0); r.advance(1);
        JsonObject root = FileLifecycleCodec.document(r.captureEvidence());
        assertTrue(payload(root, "COPY_SETTLED", 1).get("remainingAfterService").getAsDouble() > 0);
        r = runtime(true, 100, plan(task(1, in("a", 1e308)), task(2, in("b", 1e308))), endpoints(100, 100, 100));
        r.requestJob(1, one(1), 7, 0); r.requestJob(2, one(2), 7, 0); r.advance(4e306);
        root = FileLifecycleCodec.document(r.captureEvidence());
        assertTrue(Double.isInfinite(100 * payload(root, "COPY_SETTLED", 0).get("effectiveTime").getAsDouble()));
        assertEquals(2, FileLifecycleCodec.decodeDocument(root).getCompletedCopyCount());
    }

    @Test void unsupportedNumericDomainsAndAtomicRequestAggregatesAreRejected() {
        JsonObject wrong = settledLiteral(1e16, 1e16 + 2, 1e16 + 2, 0); bad(wrong); // release + isolatedSeconds cannot advance
        for (double rate : new double[]{0, Double.MIN_VALUE, Double.POSITIVE_INFINITY}) {
            wrong = literal(); wrong.getAsJsonObject("fabric").getAsJsonArray("resources").get(0).getAsJsonObject().addProperty("capacityBytesPerSecond", rate); bad(wrong);
        }
        wrong = literal(); setBytesAndRate(wrong, Double.MIN_VALUE, 100); bad(wrong); // positive duration underflows
        wrong = literal(); setBytesAndRate(wrong, Double.MAX_VALUE, Double.MIN_NORMAL); bad(wrong);
        wrong = literal(); fileRow(wrong, 0).addProperty("bytes", new BigDecimal("1e-4000")); bad(wrong);
        wrong = literal(); setBytesAndRate(wrong, Double.MAX_VALUE, 10);
        taskRow(wrong, 0).getAsJsonArray("inputs").get(0).getAsJsonObject().addProperty("referenceCount", 2);
        payload(wrong, "INPUT_RESOLVED", 0).addProperty("referenceCount", 2); bad(wrong);
    }

    @Test void rootsPoliciesShapesCaptureStatusAndEncoderSemanticsAreStrict() {
        for (String field : Arrays.asList("schema", "modelKind", "certificateScope")) {
            JsonObject wrong = literal(); wrong.addProperty(field, "unsupported"); bad(wrong);
        }
        for (String field : Arrays.asList("fileIdentity", "release", "visibility", "selection", "sourceAccess", "sharing")) {
            JsonObject wrong = literal(); wrong.getAsJsonObject("policies").addProperty(field, "unsupported"); bad(wrong);
        }
        JsonObject wrong = literal(); wrong.addProperty("summary", 1); bad(wrong);
        wrong = literal(); payload(wrong, "COPY_ADMITTED", 0).getAsJsonObject("sourceReplica").remove("copiedFrom"); bad(wrong);
        wrong = literal(); payload(wrong, "INPUT_RESOLVED", 0).addProperty("extra", 1); bad(wrong);
        wrong = literal(); wrong.getAsJsonObject("recording").addProperty("mode", "OFF"); bad(wrong);
        for (JsonElement budget : Arrays.asList(JsonNull.INSTANCE, new JsonPrimitive("32"), new JsonPrimitive(0), new JsonPrimitive(.5), new JsonPrimitive(2147483648L))) {
            wrong = literal(); wrong.getAsJsonObject("recording").add("maxTraceRecords", budget); bad(wrong);
        }
        wrong = literal(); wrong.getAsJsonObject("capture").addProperty("status", "TRUNCATED"); bad(wrong);
        wrong = literal(); wrong.getAsJsonObject("capture").addProperty("droppedRecords", 1); bad(wrong);
        wrong = literal(); wrong.getAsJsonObject("capture").addProperty("retainedRecords", 3); bad(wrong);
        CoherentDataflowRuntime r = runtime(true, 1, plan(task(1, in("x", 10)))); r.requestJob(0, one(1), 7, 0);
        assertThrows(IllegalArgumentException.class, () -> FileLifecycleCodec.document(r.captureEvidence()));
        FileLifecycleEvidence good = FileLifecycleCodec.decode(LITERAL).getEvidence();
        List<FileLifecycleEvent> forged = new ArrayList<>(good.getEvents()); JsonObject p = forged.get(3).getPayload(); p.addProperty("referenceCount", 2);
        forged.set(3, FileLifecycleEvent.of(4, 0, FileLifecycleEvent.Type.INPUT_RESOLVED, p));
        FileLifecycleEvidence bad = FileLifecycleEvidence.capture(32, 0, 0, true, good.getFilePlan(), good.getFabric(), forged);
        assertThrows(IllegalArgumentException.class, () -> FileLifecycleCodec.encode(bad));
        assertThrows(IllegalArgumentException.class, () -> FileLifecycleCodec.decodeDocument(null));
        assertThrows(IllegalArgumentException.class, () -> FileLifecycleCodec.validatePlanDocument(null));
        assertThrows(IllegalArgumentException.class, () -> FileLifecycleCodec.document(null));
    }

    private static FileLifecycleEvidence fanIn(boolean shared) {
        Task p = task(1, out("fast", 10), out("slow", 100));
        Task both = task(2, in("fast", 10), in("fast", 10), in("slow", 100)), other = task(3, in("fast", 10)), join = task(4, in("fast", 10));
        edge(p, both); edge(p, other); edge(p, join);
        CoherentDataflowRuntime r = runtime(shared, 1000, plan(p, both, other, join));
        produce(r, 10, 1, 7, 0, 1, true); r.requestJob(20, one(2), 99, 1); r.requestJob(40, one(4), 99, 1.5);
        r.advance(3); finishReady(r, 40, 4, 99, 3, 3); r.requestJob(30, one(3), 42, 3);
        r.advance(4); finishReady(r, 30, 3, 42, 4, 4); r.advance(12); finishReady(r, 20, 2, 99, 12, 13);
        return r.captureEvidence();
    }
    private static JsonObject treeCapture(int targetHost) {
        Map<Integer, Integer> placement = new LinkedHashMap<>(); placement.put(10, 0); placement.put(40, 0); placement.put(30, 1); placement.put(20, 3);
        FatTreeTopology topology = FatTreeTopology.fromSpec(NetworkTopologySpec.fatTree(4, .00002, 3, placement), Arrays.asList(10, 20, 30, 40));
        Map<Integer, Integer> vmHosts = new LinkedHashMap<>(); vmHosts.put(7, 10); vmHosts.put(42, targetHost); vmHosts.put(99, 30);
        DataTransferFabric fabric = DataTransferFabric.fatTree(capacities(100, 100, 100), vmHosts, topology, Collections.singletonList("source"));
        Task p = task(1, out("f", 40)), c = task(2, in("f", 40)); edge(p, c);
        CoherentDataflowRuntime r = runtime(true, 100, plan(p, c), fabric); produce(r, 1, 1, 7, 0, 1, true); r.requestJob(2, one(2), 42, 1);
        return FileLifecycleCodec.document(r.captureEvidence());
    }
    private static CoherentDataflowRuntime runtime(boolean shared, int budget, DataflowFilePlan plan) { return runtime(shared, budget, plan, endpoints(10, 100, 100)); }
    private static CoherentDataflowRuntime runtime(boolean shared, int budget, DataflowFilePlan plan, DataTransferFabric fabric) {
        CoherentDataflowRuntime r = new CoherentDataflowRuntime(shared, budget); r.initializePlan(plan, fabric.getLocations()); r.bindFabric(fabric); return r;
    }
    private static DataTransferFabric endpoints(double a, double b, double c) { return DataTransferFabric.endpoints(capacities(a, b, c), Collections.singletonList("source")); }
    private static Map<Integer, Double> capacities(double a, double b, double c) { Map<Integer, Double> values = new LinkedHashMap<>(); values.put(7, a); values.put(42, b); values.put(99, c); return values; }
    private static void produce(CoherentDataflowRuntime r, int job, int task, int vm, double start, double finish, boolean success) {
        r.requestJob(job, one(task), vm, start); assertTrue(r.drainReadyJobIds().contains(job)); r.cpuStarted(job, one(task), vm, start); r.jobFinished(job, one(task), vm, Collections.singletonList(success), finish);
    }
    private static void finishReady(CoherentDataflowRuntime r, int job, int task, int vm, double start, double finish) {
        assertTrue(r.drainReadyJobIds().contains(job)); r.cpuStarted(job, one(task), vm, start); r.jobFinished(job, one(task), vm, Collections.singletonList(true), finish);
    }
    private static List<Integer> one(int id) { return Collections.singletonList(id); }
    private static DataflowFilePlan plan(Task... tasks) { Map<Integer, Integer> scopes = new LinkedHashMap<>(); for (Task task : tasks) scopes.put(task.getCloudletId(), 0); return DataflowFilePlan.capture(Arrays.asList(tasks), scopes); }
    private static Task task(int id, FileItem... files) { Task task = new Task(id, 1000); for (FileItem file : files) task.addFile(file); return task; }
    private static FileItem in(String name, double bytes) { return file(name, bytes, FileType.INPUT); }
    private static FileItem out(String name, double bytes) { return file(name, bytes, FileType.OUTPUT); }
    private static FileItem file(String name, double bytes, FileType type) { FileItem file = new FileItem(name, bytes); file.setType(type); return file; }
    private static void edge(Task parent, Task child) { parent.addChild(child); child.addParent(parent); }
    private static JsonObject literal() { return NetworkLedgerCodec.parseDocument(LITERAL); }
    private static JsonArray events(JsonObject root) { return root.getAsJsonArray("events"); }
    private static JsonObject event(JsonObject root, String type, int occurrence) {
        for (JsonElement value : events(root)) { JsonObject event = value.getAsJsonObject(); if (type.equals(event.get("type").getAsString()) && occurrence-- == 0) return event; }
        throw new AssertionError("Missing event " + type);
    }
    private static JsonObject payload(JsonObject root, String type, int occurrence) { return event(root, type, occurrence).getAsJsonObject("payload"); }
    private static JsonObject taskRow(JsonObject root, int index) { return root.getAsJsonObject("filePlan").getAsJsonArray("tasks").get(index).getAsJsonObject(); }
    private static JsonObject fileRow(JsonObject root, int index) { return root.getAsJsonObject("filePlan").getAsJsonArray("files").get(index).getAsJsonObject(); }
    private static JsonObject vm(int id) { return NetworkLedgerCodec.parseDocument("{\"kind\":\"VM\",\"vmId\":" + id + ",\"sourceId\":null}"); }
    private static JsonArray arrayOf(String... values) { JsonArray result = new JsonArray(); for (String value : values) result.add(value); return result; }
    private static List<String> strings(JsonArray values) { List<String> result = new ArrayList<>(); for (JsonElement value : values) result.add(value.getAsString()); return result; }
    private static void recount(JsonObject root) { JsonArray events = events(root); for (int i = 0; i < events.size(); i++) events.get(i).getAsJsonObject().addProperty("sequence", i + 1); root.getAsJsonObject("capture").addProperty("retainedRecords", events.size()); }
    private static void removeEvent(JsonObject root, String type, int occurrence) { JsonObject selected = event(root, type, occurrence); events(root).remove(selected); recount(root); }
    private static void append(JsonObject root, String type, double at, String payload) {
        JsonObject event = new JsonObject(); event.addProperty("type", type); event.addProperty("observedTime", at); event.add("payload", NetworkLedgerCodec.parseDocument(payload)); events(root).add(event); recount(root); root.getAsJsonObject("capture").addProperty("observedThrough", at);
    }
    private static JsonObject settledLiteral(double release, double observed, double effective, double residual) {
        JsonObject root = literal(); for (int i = 1; i < events(root).size(); i++) events(root).get(i).getAsJsonObject().addProperty("observedTime", release);
        append(root, "COPY_SETTLED", observed, "{\"copyOrdinal\":1,\"effectiveTime\":" + effective + ",\"remainingAfterService\":" + residual + "}");
        append(root, "JOB_DATA_READY", observed, "{\"jobId\":0}"); return root;
    }
    private static void setBytesAndRate(JsonObject root, double bytes, double rate) {
        fileRow(root, 0).addProperty("bytes", bytes); JsonObject admitted = payload(root, "COPY_ADMITTED", 0); admitted.addProperty("bytes", bytes); admitted.addProperty("standaloneRate", rate); admitted.addProperty("isolatedSeconds", bytes / rate);
        root.getAsJsonObject("fabric").getAsJsonArray("resources").get(0).getAsJsonObject().addProperty("capacityBytesPerSecond", rate);
    }
    private static void bad(JsonObject value) {
        assertThrows(IllegalArgumentException.class, () -> FileLifecycleCodec.decodeDocument(value));
        assertThrows(IllegalArgumentException.class, () -> FileLifecycleCodec.decode(value.toString()));
    }
}
