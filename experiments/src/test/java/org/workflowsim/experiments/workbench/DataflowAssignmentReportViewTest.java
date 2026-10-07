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
import org.cloudbus.cloudsim.Cloudlet;
import org.junit.jupiter.api.Test;
import org.workflowsim.FileItem;
import org.workflowsim.Job;
import org.workflowsim.Task;
import org.workflowsim.data.v2.CoherentDataflowRuntime;
import org.workflowsim.data.v2.DataTransferFabric;
import org.workflowsim.data.v2.DataflowAssignmentValidator;
import org.workflowsim.data.v2.DataflowFilePlan;
import org.workflowsim.data.v2.DataflowStorageSpec;
import org.workflowsim.data.v2.DataflowVmAssigner;
import org.workflowsim.data.v2.DataflowVmAssigner.VmOption;
import org.workflowsim.data.v2.FileLifecycleCodec;
import org.workflowsim.data.v2.StorageLifecycleCodec;
import org.workflowsim.utils.Parameters;
import org.workflowsim.utils.TaskExecutionModel;

/** Public runtime/codec/validator fixtures only; no reflection, files or fabricated Result objects. */
class DataflowAssignmentReportViewTest {
    private static final Gson JSON = new GsonBuilder().serializeNulls().create();
    private static final String SCOPE = "BINDING_NOMINAL_BOUNDS_AND_LOGGED_SCORE_V1";

    @Test void nullMeansNoOnlineAssignment() {
        assertNull(DataflowAssignmentReportView.fromValidated(null));
    }

    @Test void emptyValidatedRunKeepsVersionedConstantsAndZeroSummary() {
        for (boolean storage : new boolean[]{false, true}) {
            JsonObject view = tree(new Run(storage, true, storage).fixture());
            assertMetadata(view);
            assertSummary(view, 0, 0, 0, 0, 0);
            checkPreview(view, "actions", 0, 64);
        }
    }

    @Test void exactAllowlistedShapeKeepsPolicyAndMainSequencesSeparate() {
        String unsafeName = "nf005b-unsafe-</script><img src=x onerror=alert(1)>";
        Task task = task(9, in(unsafeName, 100));
        Run run = new Run(false, true, false, task);
        Job job = run.prepare(10, task, .25); run.drain(); run.completeReady(job, true);
        Fixture fixture = run.fixture();
        for (int i = 0; i < fixture.main.size(); i++) fixture.main.get(i).getAsJsonObject().addProperty("sequence", 100L + i);
        JsonObject view = tree(fixture), action = action(view, 0);
        assertMetadata(view);
        assertSummary(view, 1, 1, 0, 0, 0);
        assertEquals(keys("sequence", "mainSequence", "jobId", "taskId", "vmId", "time", "binding",
                "selected", "observation", "candidates"), action.keySet());
        assertEquals("1", action.get("sequence").getAsString());
        assertEquals("100", action.get("mainSequence").getAsString());
        assertEquals("10", action.get("jobId").getAsString());
        assertEquals("9", action.get("taskId").getAsString());
        assertEquals("7", action.get("vmId").getAsString());
        assertEquals("0.25", action.get("time").getAsString());
        assertEquals("INITIAL", action.get("binding").getAsString());
        assertEquals(keys("inputSeconds", "cpuAvailableAt", "computeSeconds", "scoreFinishSeconds",
                "conditionalStoreWait", "joinedInputCopies"), action.getAsJsonObject("selected").keySet());
        assertEquals(keys("contract", "status", "storageVersion", "storeBackedInputs", "interFlowSharing",
                "observedThrough", "serviceThrough", "activeCopyCount", "activeJobCount", "pendingOutputFileCount",
                "scopedFileCount"), action.getAsJsonObject("observation").keySet());
        for (JsonElement value : rows(action, "candidates")) {
            assertEquals(keys("vmId", "compatible", "inputSeconds", "cpuAvailableAt", "computeSeconds",
                    "scoreFinishSeconds", "conditionalStoreWait", "joinedInputCopies"), value.getAsJsonObject().keySet());
        }
        checkPreview(view, "actions", 1, 64);
        checkPreview(action, "candidates", 2, 12);
        assertTrue(JSON.toJson(fixture.life).contains("nf005b-unsafe-"));
        assertFalse(JSON.toJson(view).contains("nf005b-unsafe-"));
        for (String raw : Arrays.asList("attributes", "payload", "events", "filePlan", "dataflowComputeRequests")) {
            assertFalse(JSON.toJson(view).contains("\"" + raw + "\""), raw);
        }
    }

    @Test void actionAndCandidateCapsDoNotLoseTheSelectedThirteenthVm() {
        Task[] tasks = tasks(65);
        List<VmOption> vms = new ArrayList<>();
        for (int i = 13; i >= 1; i--) vms.add(new VmOption(i * 10, i == 13 ? 2000 : 1000, 1));
        Run run = new Run(false, true, false, vms, tasks);
        completePrefix(run, tasks, tasks.length);
        JsonObject view = tree(run.fixture());
        assertSummary(view, 65, 65, 0, 0, 0);
        checkPreview(view, "actions", 65, 64);
        assertEquals("64", action(view, 63).get("sequence").getAsString());
        for (JsonElement value : rows(view, "actions")) {
            JsonObject action = value.getAsJsonObject();
            assertEquals("130", action.get("vmId").getAsString());
            assertEquals("0.5", action.getAsJsonObject("selected").get("computeSeconds").getAsString());
            checkPreview(action, "candidates", 13, 12);
            for (int i = 0; i < 12; i++) {
                JsonObject candidate = rows(action, "candidates").get(i).getAsJsonObject();
                assertEquals(Integer.toString((i + 1) * 10), candidate.get("vmId").getAsString());
                assertEquals("1", candidate.get("computeSeconds").getAsString());
                assertNotEquals(action.get("vmId"), candidate.get("vmId"));
            }
        }
    }

    @Test void conditionalStoreSummaryIncludesTheOmittedAction() {
        Task[] tasks = tasks(65);
        tasks[63] = task(64, out("x", 100)); tasks[64] = task(65, in("x", 100), in("x", 100));
        edge(tasks[63], tasks[64]);
        Run run = new Run(true, true, true, tasks);
        completePrefix(run, tasks, 64);
        Job consumer = run.prepare(65, tasks[64], run.now()); run.drain(); run.completeReady(consumer, true);
        JsonObject view = tree(run.fixture());
        assertSummary(view, 65, 65, 0, 1, 0);
        checkPreview(view, "actions", 65, 64);
        for (JsonElement value : rows(view, "actions")) {
            assertFalse(value.getAsJsonObject().getAsJsonObject("selected").get("conditionalStoreWait").getAsBoolean());
        }
    }

    @Test void joinedCopySummaryIncludesEveryJoinedFileOfTheOmittedAction() {
        Task[] tasks = tasks(65);
        tasks[63] = task(64, in("x", 100), in("y", 200));
        tasks[64] = task(65, in("x", 100), in("x", 100), in("y", 200));
        Run run = new Run(false, true, false, tasks);
        completePrefix(run, tasks, 63);
        Job first = run.prepare(64, tasks[63], run.now());
        Job second = run.prepare(65, tasks[64], run.now() + 5);
        run.drain(); run.completeReady(first, true); run.completeReady(second, true);
        JsonObject view = tree(run.fixture());
        assertSummary(view, 65, 65, 0, 0, 2);
        checkPreview(view, "actions", 65, 64);
        for (JsonElement value : rows(view, "actions")) {
            assertEquals("0", value.getAsJsonObject().getAsJsonObject("selected").get("joinedInputCopies").getAsString());
        }
    }

    @Test void retryCountsAndSingleBoundCandidateSurviveTheActionCap() {
        for (int padding : new int[]{0, 63}) {
            Task[] tasks = tasks(padding + 1);
            Run run = new Run(false, true, false, tasks);
            completePrefix(run, tasks, padding);
            int parentId = padding + 1, retryId = padding + 2;
            Job failed = run.prepare(parentId, tasks[padding], run.now()); run.completeReady(failed, false);
            Job retry = run.retry(retryId, parentId, task(parentId), run.now()); run.completeReady(retry, true);
            JsonObject view = tree(run.fixture());
            assertSummary(view, padding + 2, padding + 1, 1, 0, 0);
            checkPreview(view, "actions", padding + 2, 64);
            if (padding == 0) {
                JsonObject reused = action(view, 1);
                assertEquals("RETRY_REUSE", reused.get("binding").getAsString());
                assertEquals(action(view, 0).get("vmId"), reused.get("vmId"));
                checkPreview(reused, "candidates", 1, 12);
                assertEquals(reused.get("vmId"), rows(reused, "candidates").get(0).getAsJsonObject().get("vmId"));
            } else {
                for (JsonElement value : rows(view, "actions")) {
                    assertEquals("INITIAL", value.getAsJsonObject().get("binding").getAsString());
                }
            }
        }
    }

    @Test void observationFlagsKeepStorageAccessAndSharingIndependent() {
        for (boolean storage : new boolean[]{false, true}) for (boolean shared : new boolean[]{false, true}) {
            for (boolean gate : storage ? new boolean[]{false, true} : new boolean[]{false}) {
                Task task = task(1); Run run = new Run(storage, shared, gate, task);
                run.completeReady(run.prepare(10, task, 0), true);
                JsonObject view = tree(run.fixture()), observation = action(view, 0).getAsJsonObject("observation");
                assertMetadata(view);
                assertEquals("OBSERVED_DATAFLOW_STATE_V1", observation.get("contract").getAsString());
                assertEquals("BOUND", observation.get("status").getAsString());
                assertEquals(storage, observation.get("storageVersion").getAsBoolean());
                assertEquals(gate, observation.get("storeBackedInputs").getAsBoolean());
                assertEquals(shared, observation.get("interFlowSharing").getAsBoolean());
                for (String field : Arrays.asList("observedThrough", "serviceThrough", "activeCopyCount", "activeJobCount",
                        "pendingOutputFileCount", "scopedFileCount")) assertEquals("0", observation.get(field).getAsString(), field);
            }
        }
    }

    @Test void conditionalStoreCountsSelectedActionsRatherThanAllCandidates() {
        JsonObject view = tree(conditional()), consumer = action(view, 1);
        assertSummary(view, 2, 2, 0, 1, 0);
        JsonObject selected = consumer.getAsJsonObject("selected"), observation = consumer.getAsJsonObject("observation");
        assertTrue(selected.get("conditionalStoreWait").getAsBoolean());
        assertEquals("10", selected.get("inputSeconds").getAsString());
        assertEquals("0", selected.get("joinedInputCopies").getAsString());
        assertEquals("1", observation.get("activeCopyCount").getAsString());
        assertEquals("0", observation.get("activeJobCount").getAsString());
        assertEquals("1", observation.get("pendingOutputFileCount").getAsString());
        assertEquals("1", observation.get("scopedFileCount").getAsString());
        for (JsonElement value : rows(consumer, "candidates")) {
            assertTrue(value.getAsJsonObject().get("conditionalStoreWait").getAsBoolean());
        }
        assertFalse(view.get("liveProgressReplayed").getAsBoolean());
    }

    @Test void fractionalLoggedBoundsAreNotRoundedOrReplayedAsActualWaits() {
        Fixture fixture = joined();
        BigDecimal time = new BigDecimal("5.0000000000000000000000001");
        setActionClock(fixture, 20, time);
        JsonObject chosen = candidate(fixture, 20, 7);
        chosen.addProperty("inputSeconds", new BigDecimal("8.0000000000000000000000001"));
        chosen.addProperty("cpuAvailableAt", new BigDecimal("6.0000000000000000000000002"));
        chosen.addProperty("computeSeconds", new BigDecimal("1.0000000000000000000000004"));
        chosen.addProperty("scoreFinishSeconds", new BigDecimal("14.0000000000000000000000003"));
        // The real runtime logged five seconds remaining. Eight is within the independent nominal
        // bounds, and the public validator accepts it without certifying exact service progress.
        JsonObject view = tree(fixture), action = action(view, 1), selected = action.getAsJsonObject("selected");
        assertSummary(view, 2, 2, 0, 0, 1);
        assertEquals(time.toPlainString(), action.get("time").getAsString());
        assertEquals(time.toPlainString(), action.getAsJsonObject("observation").get("observedThrough").getAsString());
        assertEquals(time.toPlainString(), action.getAsJsonObject("observation").get("serviceThrough").getAsString());
        for (String field : Arrays.asList("inputSeconds", "cpuAvailableAt", "computeSeconds", "scoreFinishSeconds")) {
            String expected = chosen.get(field).getAsBigDecimal().toPlainString();
            assertEquals(expected, selected.get(field).getAsString(), field);
            assertEquals(expected, rows(action, "candidates").get(0).getAsJsonObject().get(field).getAsString(), field);
        }
        assertFalse(view.get("liveProgressReplayed").getAsBoolean());
        assertEquals(SCOPE, view.get("auditScope").getAsString());
    }

    @Test void nearMinimumPositiveTimeKeepsItsFullLexicalDecimal() {
        Task task = task(1); Run run = new Run(false, true, false, task);
        run.completeReady(run.prepare(10, task, Double.MIN_VALUE), true);
        Fixture fixture = run.fixture();
        BigDecimal tiny = new BigDecimal("4.9406564584124654417656879286822137236505980261432476442558568250067550727020875e-324");
        setActionClock(fixture, 10, tiny);
        for (JsonElement value : attributes(fixture, 10).getAsJsonArray("candidates")) {
            value.getAsJsonObject().addProperty("cpuAvailableAt", tiny);
        }
        JsonObject action = action(tree(fixture), 0);
        String expected = tiny.toPlainString();
        assertTrue(expected.length() > 324);
        assertEquals(expected, action.get("time").getAsString());
        assertEquals(expected, action.getAsJsonObject("observation").get("observedThrough").getAsString());
        assertEquals(expected, action.getAsJsonObject("observation").get("serviceThrough").getAsString());
        assertEquals(expected, action.getAsJsonObject("selected").get("cpuAvailableAt").getAsString());
        assertNotEquals("0", action.get("time").getAsString());
        assertFalse(action.get("time").getAsString().contains("E"));
    }

    @Test void hugeTimesAndInt64MainSequencesAreExactAndNeverClipped() {
        Task task = task(1);
        Run run = new Run(false, true, false, Collections.singletonList(new VmOption(7, 1e-297, 1)), task);
        Job job = run.prepare(10, task, 1e300); run.start(job, 1e300); run.finish(job, 3e300, true);
        Fixture fixture = run.fixture();
        BigDecimal at = new BigDecimal("1e300").add(new BigDecimal("0.125"));
        setActionClock(fixture, 10, at);
        JsonObject chosen = candidate(fixture, 10, 7);
        chosen.addProperty("cpuAvailableAt", at);
        BigDecimal compute = chosen.get("computeSeconds").getAsBigDecimal().add(new BigDecimal("0.0625"));
        BigDecimal score = chosen.get("scoreFinishSeconds").getAsBigDecimal().add(new BigDecimal("0.25"));
        chosen.addProperty("computeSeconds", compute); chosen.addProperty("scoreFinishSeconds", score);
        for (int i = 0; i < fixture.main.size(); i++) {
            fixture.main.get(i).getAsJsonObject().addProperty("sequence", Long.MAX_VALUE - fixture.main.size() + 1L + i);
        }
        JsonObject action = action(tree(fixture), 0), selected = action.getAsJsonObject("selected");
        assertEquals("1", action.get("sequence").getAsString());
        assertEquals("9223372036854775805", action.get("mainSequence").getAsString());
        assertTrue(at.toPlainString().length() > 256);
        assertEquals(at.toPlainString(), action.get("time").getAsString());
        assertEquals(at.toPlainString(), selected.get("cpuAvailableAt").getAsString());
        assertEquals(compute.toPlainString(), selected.get("computeSeconds").getAsString());
        assertEquals(score.toPlainString(), selected.get("scoreFinishSeconds").getAsString());
        assertEquals(at.toPlainString(), action.getAsJsonObject("observation").get("observedThrough").getAsString());
        assertFalse(selected.get("scoreFinishSeconds").getAsString().contains("E"));
    }

    @Test void incompatibleCandidatesKeepAllSixNullableValues() {
        Task task = task(1); task.setNumberOfPes(2);
        Run run = new Run(false, true, false, Arrays.asList(new VmOption(7, 1000, 1), new VmOption(42, 1000, 2)), task);
        run.completeReady(run.prepare(10, task, 0), true);
        JsonObject action = action(tree(run.fixture()), 0);
        assertEquals("42", action.get("vmId").getAsString());
        JsonObject incompatible = rows(action, "candidates").get(0).getAsJsonObject();
        assertEquals("7", incompatible.get("vmId").getAsString());
        assertFalse(incompatible.get("compatible").getAsBoolean());
        for (String field : Arrays.asList("inputSeconds", "cpuAvailableAt", "computeSeconds", "scoreFinishSeconds",
                "conditionalStoreWait", "joinedInputCopies")) {
            assertTrue(incompatible.has(field), field);
            assertTrue(incompatible.get(field).isJsonNull(), field);
        }
        assertTrue(rows(action, "candidates").get(1).getAsJsonObject().get("compatible").getAsBoolean());
        assertFalse(action.getAsJsonObject("selected").get("conditionalStoreWait").getAsBoolean());
    }

    @Test void projectionAndRetainedResultAreDetachedFromEveryMutableSnapshot() {
        Fixture fixture = conditional(); DataflowAssignmentValidator.Result checked = fixture.validate();
        JsonArray snapshot = checked.getAssignments(); String retained = JSON.toJson(snapshot);
        Map<String, Object> projection = DataflowAssignmentReportView.fromValidated(checked);
        String displayed = JSON.toJson(projection);
        assertDeepImmutable(projection);
        assertEquals(retained, JSON.toJson(checked.getAssignments()), "projection must not mutate the validated snapshot");
        attributes(fixture, 10).addProperty("assignmentMode", "untrusted replacement");
        fixture.life.addProperty("schema", "untrusted replacement");
        JsonObject mutable = snapshot.get(1).getAsJsonObject();
        mutable.getAsJsonArray("taskIds").set(0, JSON.toJsonTree(999));
        mutable.getAsJsonObject("attributes").getAsJsonObject("observation").addProperty("observedThrough", 999);
        mutable.getAsJsonObject("attributes").getAsJsonArray("candidates").get(0).getAsJsonObject().addProperty("inputSeconds", 999);
        snapshot.add(new JsonObject());
        checked.getAssignments().get(0).getAsJsonObject().addProperty("vmId", 999);
        assertEquals(retained, JSON.toJson(checked.getAssignments()));
        assertEquals(displayed, JSON.toJson(projection));
        Map<String, Object> again = DataflowAssignmentReportView.fromValidated(checked);
        assertNotSame(projection, again);
        assertEquals(projection, again);
        assertDeepImmutable(again);
    }

    private static Fixture conditional() {
        Task producer = task(1, out("x", 100)), consumer = task(2, in("x", 100), in("x", 100));
        edge(producer, consumer);
        Run run = new Run(true, true, true, producer, consumer);
        run.completeReady(run.prepare(10, producer, 0), true);
        Job job = run.prepare(20, consumer, run.now()); run.drain(); run.completeReady(job, true);
        return run.fixture();
    }

    private static Fixture joined() {
        Task first = task(1, in("x", 100)), second = task(2, in("x", 100));
        Run run = new Run(false, true, false, first, second);
        Job a = run.prepare(10, first, 0), b = run.prepare(20, second, 5);
        run.drain(); run.completeReady(a, true); run.completeReady(b, true);
        return run.fixture();
    }

    private static final class Fixture {
        final JsonObject manifest, life;
        final JsonArray main;
        final boolean storage;
        Fixture(JsonObject manifest, JsonObject life, JsonArray main, boolean storage) {
            this.manifest = manifest; this.life = life; this.main = main; this.storage = storage;
        }
        DataflowAssignmentValidator.Result validate() {
            DataflowAssignmentValidator validator = storage
                    ? new DataflowAssignmentValidator(manifest, StorageLifecycleCodec.decodeDocument(life))
                    : new DataflowAssignmentValidator(manifest, FileLifecycleCodec.decodeDocument(life));
            for (JsonElement event : main) validator.acceptEvent(event.getAsJsonObject());
            return validator.finish();
        }
    }

    /** Real action/lifecycle capture plus the minimal enclosing metadata consumed by the public audit. */
    private static final class Run {
        final boolean storage, gate;
        final CoherentDataflowRuntime runtime;
        final DataflowVmAssigner assigner;
        final List<VmOption> vms;
        final JsonArray main = new JsonArray(), vmRows = new JsonArray();
        final Map<Integer, Info> jobs = new LinkedHashMap<>();
        final Map<Integer, Integer> retryParents = new LinkedHashMap<>();

        Run(boolean storage, boolean shared, boolean gate, Task... tasks) {
            this(storage, shared, gate, Arrays.asList(new VmOption(42, 1000, 1), new VmOption(7, 1000, 1)), tasks);
        }
        Run(boolean storage, boolean shared, boolean gate, List<VmOption> vms, Task... tasks) {
            this.storage = storage; this.gate = gate; this.vms = vms;
            Map<Integer, Double> capacities = new LinkedHashMap<>();
            Map<Integer, Integer> hosts = new LinkedHashMap<>();
            for (VmOption vm : vms) {
                capacities.put(vm.getId(), 10.0); hosts.put(vm.getId(), 10 + hosts.size());
                vmRows.add(object("id", vm.getId(), "mips", vm.getMips(), "pes", vm.getPes(), "schedulerMode", "SPACE_SHARED"));
            }
            DataTransferFabric fabric = storage
                    ? DataTransferFabric.withStorage(capacities, hosts, null, DataflowStorageSpec.of(10, .00002, .00001, .0001))
                    : DataTransferFabric.endpoints(capacities, Collections.singletonList("source"));
            runtime = storage ? CoherentDataflowRuntime.withStorage(shared, 4096, gate) : new CoherentDataflowRuntime(shared, 4096);
            Map<Integer, Integer> scopes = new LinkedHashMap<>();
            for (Task task : tasks) scopes.put(task.getCloudletId(), 0);
            runtime.initializePlan(DataflowFilePlan.capture(Arrays.asList(tasks), scopes), fabric.getLocations());
            runtime.bindFabric(fabric); assigner = new DataflowVmAssigner(runtime, vms);
        }
        Job prepare(int id, Task task, double at) {
            Job job = new Job(id, task.getCloudletLength()); job.setClassType(Parameters.ClassType.COMPUTE.value);
            job.setTaskList(Collections.singletonList(task)); job.setNumberOfPes(task.getNumberOfPes());
            if (retryParents.containsKey(id)) job.setVmId(task.getVmId());
            DataflowVmAssigner.Decision decision = assigner.prepare(job, at).getDecision();
            jobs.put(id, new Info(job, task));
            emit("DATAFLOW_VM_ASSIGNED", job, at, JSON.toJsonTree(decision.getAttributes()).getAsJsonObject());
            JsonObject ready = object();
            if (retryParents.containsKey(id)) ready.addProperty("retryOfFailedJobId", retryParents.get(id));
            emit("JOB_READY", job, at, ready);
            return job;
        }
        Job retry(int id, int parentId, Task task, double at) {
            task.setVmId(jobs.get(parentId).job.getVmId()); retryParents.put(id, parentId);
            main.add(mainEvent(main.size() + 1L, "RETRY_JOB_CREATED", id, task.getCloudletId(), task.getVmId(), at,
                    object("failedJobId", parentId)));
            return prepare(id, task, at);
        }
        void start(Job job, double at) {
            runtime.drainReadyJobIds();
            runtime.cpuStarted(job.getCloudletId(), Collections.singletonList(jobs.get(job.getCloudletId()).task.getCloudletId()), job.getVmId(), at);
        }
        void finish(Job job, double at, boolean success) {
            Info info = jobs.get(job.getCloudletId());
            runtime.jobFinished(job.getCloudletId(), Collections.singletonList(info.task.getCloudletId()), job.getVmId(), Collections.singletonList(success), at);
            info.status = success ? Cloudlet.SUCCESS : Cloudlet.FAILED;
            emit("JOB_RETURNED", job, at, object("jobStatus", info.status));
            if (!success) emit("JOB_FAILED", job, at, object());
        }
        void completeReady(Job job, boolean success) { double at = now(); start(job, at); finish(job, at + 1, success); }
        void drain() {
            int steps = 0;
            while (runtime.getNextCompletionTime() != null) {
                assertTrue(++steps < 100, "fixture must make progress"); runtime.advance(runtime.getNextCompletionTime());
            }
        }
        double now() { return runtime.getCurrentTime(); }
        void emit(String type, Job job, double at, JsonObject attrs) {
            main.add(mainEvent(main.size() + 1L, type, job.getCloudletId(), jobs.get(job.getCloudletId()).task.getCloudletId(), job.getVmId(), at, attrs));
        }
        Fixture fixture() {
            JsonObject life = storage ? StorageLifecycleCodec.document(runtime.captureStorageEvidence()) : FileLifecycleCodec.document(runtime.captureEvidence());
            JsonArray requests = new JsonArray(), outcomes = new JsonArray(), taskOutcomes = new JsonArray();
            for (Info info : jobs.values()) {
                int job = info.job.getCloudletId(), task = info.task.getCloudletId(), vm = info.job.getVmId();
                long length = info.task.getCloudletLength();
                double mips = 0;
                for (VmOption option : vms) if (option.getId() == vm) mips = option.getMips();
                long effective = TaskExecutionModel.executionLengthMi(info.task, vm, mips);
                requests.add(object("jobId", job, "taskId", task, "taskPes", info.task.getNumberOfPes(), "jobPes", info.job.getNumberOfPes(), "lengthMi", length));
                outcomes.add(object("jobId", job, "classType", Parameters.ClassType.COMPUTE.value, "taskIds", array(task), "taskCount", 1, "vmId", vm, "status", info.status));
                taskOutcomes.add(object("jobId", job, "taskId", task, "vmId", vm, "jobStatus", info.status, "taskStatus", info.status,
                        "lengthMi", length, "effectiveExecutionLengthMi", effective));
            }
            JsonObject configuration = object("dataflowAssignment", object("mode", "CONTROL_READY_ONLINE_ASSIGNMENT_V1", "policy", "NOMINAL_INPUT_EARLIEST_RESERVATION_V1"),
                    "planningAlgorithm", "INVALID", "schedulingAlgorithm", "STATIC", "clustering", object("method", "NONE"),
                    "dataMovementModel", object("kind", life.get("modelKind")), "fileSystem", gate ? "SHARED" : "LOCAL", "networkEvidence", life.get("recording").deepCopy(),
                    "overheadModel", object("workflowEngineDelayInterval", 0, "bandwidth", 0, "workflowEngineDelays", object(), "queueDelays", object(), "postDelays", object(), "clusteringDelays", object()),
                    "failureModel", object("clusteringAlgorithm", "FTCLUSTERING_NOOP", "monitorMode", "MONITOR_NONE", "generatorMode", retryParents.isEmpty() ? "FAILURE_NONE" : "FAILURE_ALL",
                            "maxTotalRetryJobs", retryParents.isEmpty() ? 0 : 10), "taskCostMatrix", null);
            JsonObject manifest = object("configuration", configuration, "platform", object("vms", vmRows), "dataflowPlan", life.get("filePlan").deepCopy(),
                    "dataflowComputeRequests", requests, "result", object("simulationEndSeconds", life.getAsJsonObject("capture").get("observedThrough"), "jobs", outcomes, "tasks", taskOutcomes));
            return new Fixture(manifest, life, main.deepCopy(), storage);
        }
    }

    private static final class Info {
        final Job job; final Task task; int status = Cloudlet.SUCCESS;
        Info(Job job, Task task) { this.job = job; this.task = task; }
    }
    private static void completePrefix(Run run, Task[] tasks, int count) {
        for (int i = 0; i < count; i++) run.completeReady(run.prepare(i + 1, tasks[i], run.now()), true);
    }
    private static Task[] tasks(int count) {
        Task[] tasks = new Task[count]; for (int i = 0; i < count; i++) tasks[i] = task(i + 1); return tasks;
    }
    private static Task task(int id, FileItem... files) {
        Task task = new Task(id, 1000); for (FileItem file : files) task.addFile(file); return task;
    }
    private static FileItem in(String name, double bytes) { FileItem file = new FileItem(name, bytes); file.setType(Parameters.FileType.INPUT); return file; }
    private static FileItem out(String name, double bytes) { FileItem file = new FileItem(name, bytes); file.setType(Parameters.FileType.OUTPUT); return file; }
    private static void edge(Task parent, Task child) { parent.addChild(child); child.addParent(parent); }
    private static JsonObject mainEvent(long sequence, String type, int job, int task, int vm, double at, JsonObject attrs) {
        return object("sequence", sequence, "simulationTime", at, "type", type, "jobId", job, "vmId", vm,
                "classType", Parameters.ClassType.COMPUTE.value, "taskIds", array(task), "attributes", attrs);
    }
    private static JsonObject object(Object... pairs) {
        JsonObject object = new JsonObject();
        for (int i = 0; i < pairs.length; i += 2) object.add((String) pairs[i], pairs[i + 1] instanceof JsonElement ? (JsonElement) pairs[i + 1] : JSON.toJsonTree(pairs[i + 1]));
        return object;
    }
    private static JsonArray array(Object... values) {
        JsonArray array = new JsonArray();
        for (Object value : values) array.add(value instanceof JsonElement ? (JsonElement) value : JSON.toJsonTree(value));
        return array;
    }
    private static JsonObject rawAction(Fixture fixture, int job) {
        for (JsonElement value : fixture.main) {
            JsonObject event = value.getAsJsonObject();
            if (event.get("type").getAsString().equals("DATAFLOW_VM_ASSIGNED") && event.get("jobId").getAsInt() == job) return event;
        }
        throw new AssertionError("Missing assignment for Job " + job);
    }
    private static JsonObject attributes(Fixture fixture, int job) { return rawAction(fixture, job).getAsJsonObject("attributes"); }
    private static JsonObject candidate(Fixture fixture, int job, int vm) {
        for (JsonElement value : attributes(fixture, job).getAsJsonArray("candidates")) {
            if (value.getAsJsonObject().get("vmId").getAsInt() == vm) return value.getAsJsonObject();
        }
        throw new AssertionError("Missing candidate VM " + vm);
    }
    private static void setActionClock(Fixture fixture, int job, BigDecimal at) {
        rawAction(fixture, job).addProperty("simulationTime", at);
        JsonObject observation = attributes(fixture, job).getAsJsonObject("observation");
        observation.addProperty("observedThrough", at); observation.addProperty("serviceThrough", at);
    }
    private static JsonObject tree(Fixture fixture) {
        Map<String, Object> view = DataflowAssignmentReportView.fromValidated(fixture.validate());
        assertDeepImmutable(view);
        JsonObject tree = JSON.toJsonTree(view).getAsJsonObject(); assertNoJsonNumbers(tree); return tree;
    }
    private static JsonArray rows(JsonObject object, String key) { return object.getAsJsonObject(key).getAsJsonArray("rows"); }
    private static JsonObject action(JsonObject view, int index) { return rows(view, "actions").get(index).getAsJsonObject(); }
    private static Set<String> keys(String... values) { return new HashSet<>(Arrays.asList(values)); }
    private static void assertMetadata(JsonObject view) {
        assertEquals(keys("schema", "contextValidated", "auditScope", "liveProgressReplayed", "assignmentMode", "assignmentPolicy",
                "inputEstimateSemantics", "cpuReservationSemantics", "summary", "actions"), view.keySet());
        assertEquals("workflowsim-dataflow-assignment-display-v1", view.get("schema").getAsString());
        assertEquals(SCOPE, view.get("auditScope").getAsString());
        assertTrue(view.get("contextValidated").getAsBoolean());
        assertFalse(view.get("liveProgressReplayed").getAsBoolean());
        assertEquals("CONTROL_READY_ONLINE_ASSIGNMENT_V1", view.get("assignmentMode").getAsString());
        assertEquals("NOMINAL_INPUT_EARLIEST_RESERVATION_V1", view.get("assignmentPolicy").getAsString());
        assertEquals("MAX_FILE_NOMINAL_REMAINING_CONDITIONAL_STORE_V1", view.get("inputEstimateSemantics").getAsString());
        assertEquals("ACTIVE_COMPUTE_RESERVATION_FROM_OBSERVED_START_V1", view.get("cpuReservationSemantics").getAsString());
    }
    private static void assertSummary(JsonObject view, int assignments, int initial, int retries, int conditional, int joined) {
        JsonObject summary = view.getAsJsonObject("summary");
        assertEquals(keys("assignmentCount", "initialBindingCount", "retryReuseCount", "conditionalStoreCount", "joinedInputCopyCount"), summary.keySet());
        assertEquals(Integer.toString(assignments), summary.get("assignmentCount").getAsString());
        assertEquals(Integer.toString(initial), summary.get("initialBindingCount").getAsString());
        assertEquals(Integer.toString(retries), summary.get("retryReuseCount").getAsString());
        assertEquals(Integer.toString(conditional), summary.get("conditionalStoreCount").getAsString());
        assertEquals(Integer.toString(joined), summary.get("joinedInputCopyCount").getAsString());
    }
    private static void checkPreview(JsonObject view, String key, int total, int limit) {
        JsonObject preview = view.getAsJsonObject(key);
        assertEquals(keys("total", "shown", "omitted", "limit", "rows"), preview.keySet());
        assertEquals(Integer.toString(total), preview.get("total").getAsString());
        assertEquals(Integer.toString(Math.min(total, limit)), preview.get("shown").getAsString());
        assertEquals(Integer.toString(Math.max(0, total - limit)), preview.get("omitted").getAsString());
        assertEquals(Integer.toString(limit), preview.get("limit").getAsString());
        assertEquals(Math.min(total, limit), rows(view, key).size());
    }
    private static void assertNoJsonNumbers(JsonElement value) {
        if (value.isJsonObject()) {
            for (JsonElement child : value.getAsJsonObject().asMap().values()) assertNoJsonNumbers(child);
        } else if (value.isJsonArray()) {
            for (JsonElement child : value.getAsJsonArray()) assertNoJsonNumbers(child);
        } else if (value.isJsonPrimitive()) assertFalse(value.getAsJsonPrimitive().isNumber(), value.toString());
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
            for (Object child : map.values()) assertDeepImmutable(child);
        } else if (value instanceof List) {
            List<Object> list = (List<Object>) value;
            assertThrows(UnsupportedOperationException.class, () -> list.add("not allowed"));
            if (!list.isEmpty()) assertThrows(UnsupportedOperationException.class, () -> list.set(0, "not allowed"));
            for (Object child : list) assertDeepImmutable(child);
        } else {
            assertTrue(value == null || value instanceof String || value instanceof Boolean,
                    "Projection leaked a Number, JSON node, date or another non-display object");
        }
    }
}
