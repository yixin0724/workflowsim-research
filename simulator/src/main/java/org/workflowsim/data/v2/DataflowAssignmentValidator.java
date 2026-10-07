package org.workflowsim.data.v2;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import org.cloudbus.cloudsim.Cloudlet;
import org.workflowsim.utils.Parameters;

/**
 * Independent read-side audit of online destination bindings and logged nominal scores.
 *
 * <p>The caller must also perform the ordinary lifecycle-to-manifest/main-event context joins.
 * This additional audit accepts only a matching, complete, quiescent V2/V3 capture. It does not
 * replay live byte balances, the fluid allocator, or counterfactual execution. In-flight input
 * estimates are checked against necessary nominal bounds, not certified as exact progress.
 * No runtime, assigner, production route selector, or CPU telemetry is consulted.</p>
 *
 * <p>Main events are supplied in actual stream order. At {@link #finish()} the already validated
 * lifecycle is traversed once, checking each action immediately before its input request. Only
 * current visibility, copies, active reservations and terminal retry anchors are reconstructed;
 * there is no per-request observation-state history.</p>
 */
public final class DataflowAssignmentValidator {
    /** Precisely the claim made by a successful audit, not a fluid-service certificate. */
    public static final String SCOPE = "BINDING_NOMINAL_BOUNDS_AND_LOGGED_SCORE_V1";
    private static final String MODE = "CONTROL_READY_ONLINE_ASSIGNMENT_V1";
    private static final String POLICY = "NOMINAL_INPUT_EARLIEST_RESERVATION_V1";
    private static final String INPUT = "MAX_FILE_NOMINAL_REMAINING_CONDITIONAL_STORE_V1";
    private static final String CPU = "ACTIVE_COMPUTE_RESERVATION_FROM_OBSERVED_START_V1";
    private static final long MAX_TOTAL_MI = 9223372036854L;
    private static final int MAX_NUMBER_DIGITS = 4096;
    private static final DataLocation SOURCE = DataLocation.source("source");
    private static final String[] CANDIDATE_VALUES = {"inputSeconds", "cpuAvailableAt", "computeSeconds",
        "scoreFinishSeconds", "conditionalStoreWait", "joinedInputCopies"};

    private final JsonArray lifecycle;
    private final boolean storage, storeInputs, shared, failureEnabled;
    private final int retryBudget;
    private final double simulationEnd;
    private final FileLifecycleValidator.ReadFabric fabric;
    private final Map<Integer, Vm> vms = new TreeMap<>();
    private final Map<FileKey, File> files = new HashMap<>();
    private final Map<Integer, TaskPlan> tasks = new TreeMap<>();
    private final Map<Integer, Attempt> attempts = new LinkedHashMap<>();
    private final Map<Integer, Map<Integer, Double>> matrix;
    private final Map<Integer, Action> assignments = new LinkedHashMap<>();
    private final Map<Integer, Anchor> ready = new HashMap<>(), returned = new HashMap<>(), failed = new HashMap<>();
    private final Map<Integer, Retry> retries = new HashMap<>();
    private final Set<Integer> retriedParents = new HashSet<>();
    private long previousMainSequence=-1;
    private double previousMainTime;
    private boolean closed;
    private Result result;

    /**
     * Create a V2 action audit; the decoded lifecycle must have no missing or active history.
     * @param manifest enclosing manifest, including online compute requests and final outcomes
     * @param life independently decoded V2 lifecycle
     * @throws IllegalArgumentException if the context is absent, incomplete or inconsistent
     */
    public DataflowAssignmentValidator(JsonObject manifest, FileLifecycleCodec.Decoded life) {
        this(manifest, new Checked(life));
    }

    /**
     * Create a V3 action audit, retaining its LOCAL/SHARED input-access distinction.
     * @param manifest enclosing manifest, including online compute requests and final outcomes
     * @param life independently decoded V3 storage lifecycle
     * @throws IllegalArgumentException if the context is absent, incomplete or inconsistent
     */
    public DataflowAssignmentValidator(JsonObject manifest, StorageLifecycleCodec.Decoded life) {
        this(manifest, new Checked(life));
    }

    private DataflowAssignmentValidator(JsonObject manifest, Checked checked) {
        require(manifest != null, "Manifest is required");
        storage = checked.storage;
        JsonObject document = checked.document;
        JsonObject config = object(manifest.get("configuration"), "configuration");
        JsonObject option = object(config.get("dataflowAssignment"), "dataflowAssignment");
        keys(option, "mode", "policy");
        equal(option, "mode", MODE); equal(option, "policy", POLICY);
        equal(config, "planningAlgorithm", "INVALID"); equal(config, "schedulingAlgorithm", "STATIC");
        equal(object(config.get("clustering"), "clustering"), "method", "NONE");
        String kind = text(object(config.get("dataMovementModel"), "dataMovementModel").get("kind"), "kind");
        require(kind.equals(text(document.get("modelKind"), "modelKind")), "Lifecycle physical kind differs");
        shared = kind.equals(storage ? StorageLifecycleCodec.SHARED_KIND : FileLifecycleCodec.SHARED_KIND);
        require(shared || kind.equals(storage ? StorageLifecycleCodec.ISOLATED_KIND : FileLifecycleCodec.ISOLATED_KIND),
                "Online assignment requires matching coherent V2/V3 physics");
        String fs = text(config.get("fileSystem"), "fileSystem");
        storeInputs = storage && "SHARED".equals(fs);
        require("LOCAL".equals(fs) || storeInputs, "Unsupported online file system");
        if (storage) equal(object(document.get("policies"), "policies"), "inputAccess",
                storeInputs ? StorageLifecycleCodec.STORE_INPUT_ACCESS : StorageLifecycleCodec.LOCAL_INPUT_ACCESS);
        JsonObject recording = object(config.get("networkEvidence"), "networkEvidence");
        keys(recording, "mode", "maxTraceRecords");
        equal(recording, "mode", storage ? StorageLifecycleCodec.MODE : FileLifecycleCodec.MODE);
        require(positiveInt(recording.get("maxTraceRecords"), "maxTraceRecords")
                == positiveInt(object(document.get("recording"), "recording").get("maxTraceRecords"), "maxTraceRecords"),
                "Lifecycle recording budget differs");
        JsonObject capture = object(document.get("capture"), "capture");
        equal(capture, "status", "COMPLETE");
        require(whole(capture.get("droppedRecords"), "droppedRecords") == 0 && checked.quiescent,
                "Full online action export requires complete, quiescent lifecycle capture");
        JsonObject overhead = object(config.get("overheadModel"), "overheadModel");
        require(whole(overhead.get("workflowEngineDelayInterval"), "workflowEngineDelayInterval") == 0
                && number(overhead.get("bandwidth"), "bandwidth") == 0, "Online overhead is unsupported");
        for (String name : Arrays.asList("workflowEngineDelays", "queueDelays", "postDelays", "clusteringDelays"))
            require(object(overhead.get(name), name).size() == 0, "Online overhead maps must be empty");
        JsonObject failure = object(config.get("failureModel"), "failureModel");
        equal(failure, "clusteringAlgorithm", "FTCLUSTERING_NOOP"); equal(failure, "monitorMode", "MONITOR_NONE");
        String generator = text(failure.get("generatorMode"), "generatorMode");
        require(Arrays.asList("FAILURE_NONE", "FAILURE_ALL", "FAILURE_VM", "FAILURE_JOB", "FAILURE_VM_JOB").contains(generator),
                "Unsupported failure generator mode");
        failureEnabled = !"FAILURE_NONE".equals(generator);
        retryBudget = integer(failure.get("maxTotalRetryJobs"), "maxTotalRetryJobs");
        require(failureEnabled || retryBudget == 0, "Disabled failure model cannot reserve retries");
        JsonObject outcomes = object(manifest.get("result"), "result");
        simulationEnd = number(outcomes.get("simulationEndSeconds"), "simulationEndSeconds");
        require(number(capture.get("observedThrough"), "observedThrough") <= simulationEnd,
                "Lifecycle exceeds final simulation clock");
        JsonObject plan = object(document.get("filePlan"), "filePlan");
        same(object(manifest.get("dataflowPlan"), "dataflowPlan"), plan, "Core and lifecycle plans differ");
        loadPlan(plan);
        JsonObject rawFabric = object(document.get("fabric"), "fabric");
        loadVms(object(manifest.get("platform"), "platform"), rawFabric);
        fabric = new FileLifecycleValidator.ReadFabric(rawFabric, storage);
        loadAttempts(manifest, outcomes);
        require(attempts.size() == checked.requested && checked.requested == checked.completed,
                "Lifecycle must cover exactly all final compute attempts");
        matrix = loadMatrix(config);
        lifecycle = array(document.get("events"), "lifecycle events");
    }

    /**
     * Consume one full main event in stream order. Unrelated event kinds are not retained.
     * @param main full main-event record, not just its attributes
     * @throws IllegalArgumentException for malformed actions, identity, order or retry provenance
     * @throws IllegalStateException after finish has been called
     */
    public void acceptEvent(JsonObject main) {
        requireOpen();
        require(main != null, "Main event is required");
        long sequence = whole(main.get("sequence"), "main sequence");require(sequence>=0,"Expected nonnegative int64: main sequence");
        double at = number(main.get("simulationTime"), "simulationTime");
        require(sequence > previousMainSequence && at >= previousMainTime && at <= simulationEnd,
                "Main records must retain actual sequence and monotonic run clocks");
        previousMainSequence = sequence; previousMainTime = at;
        String type = text(main.get("type"), "main type");
        if ("DATAFLOW_VM_ASSIGNED".equals(type)) {
            keys(main, "sequence", "simulationTime", "type", "jobId", "vmId", "classType", "taskIds", "attributes");
            Attempt attempt = identity(main);
            require(!assignments.containsKey(attempt.id) && !ready.containsKey(attempt.id), "Duplicate or late assignment");
            Action action = new Action(main, attempt, at, assignments.size() + 1L);
            assignments.put(attempt.id, action);
            return;
        }
        if("TASK_EXECUTION_MODELED".equals(type)){
            JsonElement compute=main.get("classType");if(compute==null||compute.isJsonNull()||integer(compute,"classType")!=Parameters.ClassType.COMPUTE.value)return;Attempt attempt=identity(main);JsonObject attrs=object(main.get("attributes"),"execution attributes");require(integer(attrs.get("taskId"),"execution taskId")==attempt.task&&positiveLong(attrs.get("taskLengthMi"),"execution taskLengthMi")==attempt.lengthMi&&positiveLong(attrs.get("effectiveExecutionLengthMi"),"execution effectiveExecutionLengthMi")==attempt.effectiveMi,"Main execution MI differs from certified compute declarations");equal(attrs,"taskTimingScope","MODEL_DERIVED_COMPUTE_WINDOW");return;
        }
        if (!Arrays.asList("JOB_READY", "RETRY_JOB_CREATED", "JOB_RETURNED", "JOB_FAILED").contains(type)) return;
        JsonElement classType = main.get("classType");
        if (classType == null || classType.isJsonNull() || integer(classType, "classType") != Parameters.ClassType.COMPUTE.value) return;
        Attempt attempt = identity(main);
        Anchor anchor = new Anchor(sequence, at);
        if ("JOB_READY".equals(type)) {
            Action action = assignments.get(attempt.id);
            require(action != null && action.at == at && !ready.containsKey(attempt.id),
                    "Every compute JOB_READY needs one preceding same-clock assignment");
            JsonObject attrs = object(main.get("attributes"), "JOB_READY attributes");
            Retry retry = retries.get(attempt.id);
            if (retry == null) require(!attrs.has("retryOfFailedJobId"), "Initial readiness claims a retry parent");
            else require(integer(attrs.get("retryOfFailedJobId"), "retryOfFailedJobId") == retry.parent,
                    "Readiness retry parent differs");
            ready.put(attempt.id, anchor);
        } else if ("JOB_RETURNED".equals(type)) {
            require(assignments.containsKey(attempt.id) && ready.containsKey(attempt.id),
                    "Compute return precedes its binding/readiness");
            require(!returned.containsKey(attempt.id), "Duplicate main return");
            require(integer(object(main.get("attributes"), "return attributes").get("jobStatus"), "jobStatus") == attempt.status,
                    "Returned Job status differs from final attempt");
            returned.put(attempt.id, anchor);
        } else if ("JOB_FAILED".equals(type)) {
            require(attempt.status == Cloudlet.FAILED && !failed.containsKey(attempt.id), "Unexpected or duplicate main failure");
            Anchor returnEvent = returned.get(attempt.id);
            require(returnEvent != null && returnEvent.sequence < sequence && returnEvent.at <= at,
                    "Failure must follow its failed return");
            failed.put(attempt.id, anchor);
        } else {
            int parentId = integer(object(main.get("attributes"), "retry attributes").get("failedJobId"), "failedJobId");
            Attempt parent = attempts.get(parentId);
            Anchor failure = failed.get(parentId);
            require(failureEnabled && !assignments.containsKey(attempt.id) && !retries.containsKey(attempt.id)
                    && parent != null && parent.id != attempt.id && parent.status == Cloudlet.FAILED
                    && parent.task == attempt.task && parent.vm == attempt.vm && assignments.containsKey(parent.id)
                    && failure != null && failure.sequence < sequence && failure.at <= at && retriedParents.add(parentId),
                    "NOOP retry must follow one failed bound parent on the same Task and VM");
            retries.put(attempt.id, new Retry(parentId, at));
            require(retries.size() <= retryBudget, "Online retry history exceeds its budget");
        }
    }

    /**
     * Validate action scores against lifecycle prefixes and freeze the complete audited records.
     * Repeated successful calls return the same immutable result.
     * @return immutable counts, scope and defensively accessible main assignment records
     * @throws IllegalArgumentException if any final join, bound, reservation or score is invalid
     */
    public Result finish() {
        if (result != null) return result;
        requireOpen(); closed = true;
        require(assignments.keySet().equals(attempts.keySet()) && ready.keySet().equals(attempts.keySet()),
                "Missing or extra assignment/readiness for final compute attempts");
        Prefix prefix = new Prefix();
        for (JsonElement item : lifecycle) {
            JsonObject event = object(item, "validated lifecycle event");
            JsonObject p = object(event.get("payload"), "payload");
            double at = number(event.get("observedTime"), "observedTime");
            switch (text(event.get("type"), "lifecycle type")) {
                case "EXTERNAL_SEEDED": prefix.publish(fileKey(p.get("fileId")), location(p.get("location"))); break;
                case "JOB_INPUT_REQUESTED": prefix.request(p, at); break;
                case "COPY_ADMITTED": prefix.admit(p); break;
                case "COPY_SETTLED": prefix.settle(p); break;
                case "INPUT_RESOLVED":
                    if ("ZERO".equals(text(p.get("resolution"), "resolution"))) {
                        LiveJob job = prefix.live(integer(p.get("jobId"), "jobId"));
                        prefix.publish(fileKey(p.get("fileId")), DataLocation.vm(job.attempt.vm));
                    }
                    break;
                case "OUTPUT_RESOLVED": prefix.output(p); break;
                case "JOB_CPU_STARTED": {
                    LiveJob job = prefix.live(integer(p.get("jobId"), "jobId"));
                    require(job.start == null && integer(p.get("vmId"), "vmId") == job.attempt.vm, "CPU start binding differs");
                    job.start = at;
                    break;
                }
                case "TASK_FINISHED": prefix.complete(p, at); break;
                case "JOB_DATA_READY": case "INPUT_WAITING_FOR_STORE": break;
                default: throw bad("Unexpected decoded lifecycle operation");
            }
        }
        require(prefix.requested.equals(attempts.keySet()) && prefix.terminal.keySet().equals(attempts.keySet())
                && prefix.active.isEmpty() && prefix.copies.isEmpty() && prefix.pendingOutputs.isEmpty(),
                "Final lifecycle/action coverage is not complete and quiescent");
        require(prefix.retryCount == retries.size(), "Retry creation has no corresponding reused action");
        JsonArray records = new JsonArray();
        for (Action action : assignments.values()) records.add(action.record);
        result = new Result(records, prefix.initialCount, prefix.retryCount);
        return result;
    }

    /** Immutable result. The returned records are full validated main events, not wire summaries. */
    public static final class Result {
        private final JsonArray records;
        private final int initialCount, retryCount;
        private Result(JsonArray records, int initialCount, int retryCount) {
            this.records = records.deepCopy(); this.initialCount = initialCount; this.retryCount = retryCount;
        }
        /** @return the bounded nominal audit scope */ public String getScope() { return SCOPE; }
        /** @return all compute-attempt assignments */ public int getAssignmentCount() { return records.size(); }
        /** @return first bindings of distinct logical Tasks */ public int getInitialBindingCount() { return initialCount; }
        /** @return fixed-VM NOOP retry bindings */ public int getRetryReuseCount() { return retryCount; }
        /** @return a deep defensive copy of full validated assignment records */
        public JsonArray getAssignments() { return records.deepCopy(); }
    }

    private final class Prefix {
        final Map<FileKey, Set<DataLocation>> visible = new HashMap<>();
        final Map<Long, Copy> copies = new HashMap<>();
        final Map<Target, Copy> targets = new HashMap<>();
        // Iteration order is surviving request order, exactly the policy's reservation sum order.
        final Map<Integer, LiveJob> active = new LinkedHashMap<>();
        final Set<FileKey> pendingOutputs = new HashSet<>();
        final Set<Integer> requested = new HashSet<>();
        final Map<Integer, Terminal> terminal = new HashMap<>();
        final Map<Integer, Integer> bindings = new HashMap<>();
        int initialCount, retryCount;

        void request(JsonObject p, double now) {
            int id = integer(p.get("jobId"), "jobId");
            Attempt attempt = attempts.get(id); Action action = assignments.get(id);
            require(attempt != null && action != null && !requested.contains(id), "Input request has no unique final action");
            require(singleton(p.get("taskIds"), "taskIds") == attempt.task
                    && integer(p.get("destinationVmId"), "destinationVmId") == attempt.vm && action.at == now
                    && action.ordinal == requested.size() + 1L, "Assignment order, clock or actual input binding differs");
            Integer bound = bindings.get(attempt.task); Retry retry = retries.get(id);
            if (bound == null) {
                require(!action.reuse && retry == null, "First logical binding must be INITIAL, not a retry");
            } else {
                Terminal parent = retry == null ? null : terminal.get(retry.parent);
                require(action.reuse && bound == attempt.vm && parent != null && !parent.success
                        && parent.at <= retry.at && retry.at <= now, "RETRY_REUSE lacks an already failed fixed-VM parent");
            }
            Summary summary = action.summary;
            require(summary.storage == storage && summary.storeInputs == storeInputs && summary.shared == shared
                    && summary.observed == now && summary.service == now && summary.copies == copies.size()
                    && summary.jobs == active.size() && summary.pending == pendingOutputs.size()
                    && summary.scoped == tasks.get(attempt.task).inputs.size(), "Observation differs from immediately pre-request state");
            List<Integer> expectedVms = bound == null ? new ArrayList<>(vms.keySet()) : Arrays.asList(bound);
            require(action.candidates.size() == expectedVms.size(), "Candidate inventory must exactly cover eligible binding domain");
            Candidate selected = null; double best = 0; int bestVm = -1;
            for (int index = 0; index < expectedVms.size(); index++) {
                Candidate candidate = action.candidates.get(index); Vm vm = vms.get(expectedVms.get(index));
                require(candidate.vm == vm.id, "Candidates must be exact numeric VM-ID order");
                boolean compatible = vm.pes >= attempt.taskPes && vm.pes >= attempt.jobPes;
                require(candidate.compatible == compatible, "Candidate PE compatibility differs from actual Task/Job requests");
                if (!compatible) continue;
                long mi = executionMi(attempt, vm);
                double compute = ratio(mi, vm.mips, "computeSeconds");
                require(candidate.compute == compute, "Candidate executable compute cost differs");
                if (candidate.vm == attempt.vm) require(mi == attempt.effectiveMi, "Selected effective MI differs from Task outcome");
                Bounds inputs = bounds(tasks.get(attempt.task), DataLocation.vm(vm.id));
                require(candidate.input >= inputs.lower && candidate.input <= inputs.upper
                        && (inputs.lower != 0 || !inputs.positiveUnknown || candidate.input > 0),
                        "Input estimate lies outside nominal bounds (not an exact progress replay)");
                require(candidate.conditional == inputs.conditional && candidate.joined == inputs.joined,
                        "Conditional SOURCE gate or joined-copy count differs");
                double cpu = cpuAvailable(vm.id, now);
                require(candidate.cpu == cpu, "Active observed-start CPU reservation differs");
                double score = addTime(Math.max(addTime(now, candidate.input), cpu), compute);
                require(candidate.score == score, "Logged score differs from specified binary64 formula");
                if (bestVm < 0 || score < best || (score == best && vm.id < bestVm)) { bestVm = vm.id; best = score; }
                if (vm.id == attempt.vm) selected = candidate;
            }
            require(selected != null && bestVm == attempt.vm && action.selected == attempt.vm,
                    "Selected actual VM does not minimize validated logged score with numeric-ID ties");
            // All candidate checks precede the actual request's visibility/admission operations.
            requested.add(id);
            active.put(id, new LiveJob(attempt, selected.compute));
            if (bound == null) { bindings.put(attempt.task, attempt.vm); initialCount++; }
            else retryCount++;
        }

        double cpuAvailable(int vm, double now) {
            double work = 0;
            for (LiveJob job : active.values()) {
                if (job.attempt.vm != vm) continue;
                double remaining = job.compute;
                if (job.start != null) {
                    require(job.start <= now, "Future observed CPU start");
                    remaining = Math.max(0, remaining - (now - job.start));
                }
                work = addFinite(work, remaining);
            }
            return addTime(now, work);
        }

        Bounds bounds(TaskPlan task, DataLocation destination) {
            Bounds result = new Bounds();
            for (FileKey id : task.inputs) {
                File file = files.get(id); double lower = 0, upper = 0;
                if (storeInputs && !has(id, SOURCE)) {
                    Copy output = targets.get(new Target(id, SOURCE));
                    require(output != null && output.output, "Uncommitted SOURCE input needs its active OUTPUT copy");
                    double write = ratio(file.bytes, output.rate, "full nominal output duration");
                    double read = has(id, destination) || file.bytes == 0 ? 0
                            : ratio(file.bytes, fabric.rate(SOURCE, destination), "post-commit input read");
                    lower = read; upper = addFinite(write, read);
                    // The positive write can be absorbed by binary64 addition when read > 0.
                    result.positiveUnknown = true; result.conditional = true;
                } else if (!has(id, destination)) {
                    Copy input = targets.get(new Target(id, destination));
                    if (input != null) {
                        require(!input.output, "Input target is occupied by a non-input copy");
                        upper = ratio(file.bytes, input.rate, "frozen full nominal input duration");
                        result.positiveUnknown = true; result.joined++;
                    } else {
                        Set<DataLocation> sources = visible.get(id);
                        require(sources != null && !sources.isEmpty() && (!storeInputs || sources.contains(SOURCE)),
                                "Input has no currently visible eligible source");
                        if (file.bytes > 0) {
                            double rate = 0;
                            if (storeInputs) rate = fabric.rate(SOURCE, destination);
                            else for (DataLocation source : sources) rate = Math.max(rate, fabric.rate(source, destination));
                            lower = upper = ratio(file.bytes, rate, "new nonlocal input duration");
                        }
                    }
                }
                result.lower = Math.max(result.lower, lower); result.upper = Math.max(result.upper, upper);
            }
            return result;
        }

        void admit(JsonObject p) {
            FileKey file = fileKey(p.get("fileId"));
            DataLocation destination = storage ? location(p.get("destination"))
                    : DataLocation.vm(integer(p.get("destinationVmId"), "destinationVmId"));
            boolean output = storage && "OUTPUT".equals(text(p.get("purpose"), "purpose"));
            Copy copy = new Copy(file, location(object(p.get("sourceReplica"), "sourceReplica").get("location")), destination,
                    number(p.get("standaloneRate"), "standaloneRate"), output);
            long ordinal = positiveLong(p.get("copyOrdinal"), "copyOrdinal");
            require(has(file, copy.source) && copy.rate > 0 && copies.put(ordinal, copy) == null
                    && targets.put(new Target(file, destination), copy) == null, "Invalid decoded active copy");
        }

        void settle(JsonObject p) {
            Copy copy = copies.remove(positiveLong(p.get("copyOrdinal"), "copyOrdinal"));
            require(copy != null, "Decoded settlement has no active copy");
            targets.remove(new Target(copy.file, copy.destination)); publish(copy.file, copy.destination);
            if (copy.output) pendingOutputs.remove(copy.file);
        }

        void output(JsonObject p) {
            FileKey file = fileKey(p.get("fileId")); String resolution = text(p.get("resolution"), "resolution");
            if ("ZERO".equals(resolution)) { publish(file, SOURCE); pendingOutputs.remove(file); }
            else if ("ALREADY_STORED".equals(resolution)) {
                require(has(file, SOURCE), "ALREADY_STORED cannot invent SOURCE visibility"); pendingOutputs.remove(file);
            }
        }

        void complete(JsonObject p, double at) {
            int id = integer(p.get("jobId"), "jobId"); LiveJob job = live(id);
            boolean success = bool(p.get("success"), "success");
            require(job.start != null && integer(p.get("taskId"), "taskId") == job.attempt.task
                    && integer(p.get("vmId"), "vmId") == job.attempt.vm
                    && success == (job.attempt.status == Cloudlet.SUCCESS), "Terminal lifecycle differs from final compute outcome");
            active.remove(id); terminal.put(id, new Terminal(at, success));
            if (!success) return;
            for (FileKey file : tasks.get(job.attempt.task).outputs) {
                publish(file, DataLocation.vm(job.attempt.vm));
                if (storage && !has(file, SOURCE)) pendingOutputs.add(file);
            }
        }

        LiveJob live(int id) { LiveJob job = active.get(id); require(job != null, "Unknown active compute attempt"); return job; }
        boolean has(FileKey file, DataLocation location) { Set<DataLocation> locations = visible.get(file); return locations != null && locations.contains(location); }
        void publish(FileKey file, DataLocation location) { visible.computeIfAbsent(file, ignored -> new HashSet<>()).add(location); }
    }

    private void loadPlan(JsonObject plan) {
        for (JsonElement value : array(plan.get("files"), "files")) {
            JsonObject row = object(value, "file"); FileKey id = fileKey(row.get("fileId"));
            files.put(id, new File(number(row.get("bytes"), "file bytes")));
        }
        for (JsonElement value : array(plan.get("tasks"), "tasks")) {
            JsonObject row = object(value, "Task"); TaskPlan task = new TaskPlan();
            for (JsonElement input : array(row.get("inputs"), "inputs")) task.inputs.add(fileKey(object(input, "input").get("fileId")));
            for (JsonElement output : array(row.get("outputs"), "outputs")) task.outputs.add(fileKey(output));
            tasks.put(integer(row.get("taskId"), "taskId"), task);
        }
    }

    private void loadVms(JsonObject platform, JsonObject rawFabric) {
        for (JsonElement value : array(platform.get("vms"), "platform.vms")) {
            JsonObject row = object(value, "VM");
            int id = integer(row.get("id"), "VM id"); int pes = positiveInt(row.get("pes"), "VM pes");
            double mips = positive(row.get("mips"), "VM mips"); equal(row, "schedulerMode", "SPACE_SHARED");
            require(vms.put(id, new Vm(id, pes, mips)) == null, "Duplicate platform VM");
        }
        Set<Integer> endpoints = new TreeSet<>();
        for (JsonElement value : array(rawFabric.get("locations"), "locations")) {
            DataLocation location = location(value);
            if (location.getKind() == DataLocation.Kind.VM) endpoints.add(location.getVmId());
        }
        require(!vms.isEmpty() && endpoints.equals(vms.keySet()), "Actual fabric VM inventory differs from platform");
    }

    private void loadAttempts(JsonObject manifest, JsonObject result) {
        Map<Integer, JsonObject> jobs = new LinkedHashMap<>(), outcomes = new HashMap<>();
        Set<Integer> allJobIds = new HashSet<>();
        for (JsonElement value : array(result.get("jobs"), "result.jobs")) {
            JsonObject row = object(value, "Job outcome"); int id = integer(row.get("jobId"), "jobId");
            require(allJobIds.add(id), "Duplicate Job outcome");
            if (integer(row.get("classType"), "classType") == Parameters.ClassType.COMPUTE.value) jobs.put(id, row);
        }
        for (JsonElement value : array(result.get("tasks"), "result.tasks")) {
            JsonObject row = object(value, "Task outcome"); int id = integer(row.get("jobId"), "jobId");
            require(jobs.containsKey(id) && outcomes.put(id, row) == null, "Every compute attempt needs a unique Task outcome");
        }
        Map<Integer, Attempt> logical = new HashMap<>();
        for (JsonElement value : array(manifest.get("dataflowComputeRequests"), "dataflowComputeRequests")) {
            JsonObject row = object(value, "compute request"); keys(row, "jobId", "taskId", "taskPes", "jobPes", "lengthMi");
            int id = integer(row.get("jobId"), "jobId"), task = integer(row.get("taskId"), "taskId");
            int taskPes = positiveInt(row.get("taskPes"), "taskPes"), jobPes = positiveInt(row.get("jobPes"), "jobPes");
            long length = positiveLong(row.get("lengthMi"), "lengthMi");
            JsonObject job = jobs.get(id), outcome = outcomes.get(id);
            require(job != null && outcome != null && tasks.containsKey(task), "Compute request has no declared Task/final attempt");
            int vm = integer(job.get("vmId"), "vmId"), status = integer(job.get("status"), "status");
            require(vms.containsKey(vm) && (status == Cloudlet.SUCCESS || status == Cloudlet.FAILED)
                    && singleton(job.get("taskIds"), "Job taskIds") == task && integer(job.get("taskCount"), "taskCount") == 1
                    && integer(outcome.get("taskId"), "Task taskId") == task && integer(outcome.get("vmId"), "Task vmId") == vm
                    && integer(outcome.get("jobStatus"), "jobStatus") == status && integer(outcome.get("taskStatus"), "taskStatus") == status
                    && positiveLong(outcome.get("lengthMi"), "Task lengthMi") == length, "Compute metadata differs from Job/Task outcomes");
            Attempt attempt = new Attempt(id, task, vm, status, taskPes, jobPes, length,
                    positiveLong(outcome.get("effectiveExecutionLengthMi"), "effectiveExecutionLengthMi"));
            require(attempts.put(id, attempt) == null, "Duplicate compute request");
            Attempt prior = logical.putIfAbsent(task, attempt);
            require(prior == null || (prior.lengthMi == length && prior.taskPes == taskPes && prior.jobPes == jobPes),
                    "NOOP logical Task length/PE requirements changed across attempts");
        }
        require(attempts.keySet().equals(jobs.keySet()) && outcomes.keySet().equals(jobs.keySet()),
                "Compute requests and Task outcomes must exactly cover compute Jobs");
    }

    private Map<Integer, Map<Integer, Double>> loadMatrix(JsonObject config) {
        JsonElement value = required(config, "taskCostMatrix"); if (value.isJsonNull()) return null;
        JsonObject raw = object(value, "taskCostMatrix"); keys(raw, "unit", "runtimeConversion", "entries");
        equal(raw, "unit", "EXECUTION_SECONDS"); equal(raw, "runtimeConversion", "ROUND_SECONDS_TIMES_VM_MIPS_TO_POSITIVE_INTEGER_MI");
        Map<Integer, Map<Integer, Double>> values = new HashMap<>();
        JsonArray entries = array(raw.get("entries"), "matrix entries"); require(entries.size() > 0, "Empty task cost matrix");
        for (JsonElement item : entries) {
            JsonObject row = object(item, "matrix entry"); keys(row, "taskId", "vmId", "executionSeconds");
            int task = integer(row.get("taskId"), "matrix taskId"), vm = integer(row.get("vmId"), "matrix vmId");
            double seconds = positive(row.get("executionSeconds"), "matrix executionSeconds");
            require(values.computeIfAbsent(task, ignored -> new HashMap<>()).put(vm, seconds) == null, "Duplicate matrix coordinate");
        }
        for (int task : tasks.keySet()) require(values.containsKey(task) && values.get(task).keySet().containsAll(vms.keySet()),
                "Matrix must cover actual Task/VM coordinates without MI fallback");
        return values;
    }

    private long executionMi(Attempt attempt, Vm vm) {
        long mi = attempt.lengthMi;
        if (matrix != null) {
            double converted = matrix.get(attempt.task).get(vm.id) * vm.mips;
            require(Double.isFinite(converted) && converted > 0, "Matrix conversion is not positive finite binary64");
            try {
                // BigDecimal(double), not valueOf(double): round the exact binary64 product.
                // Adding 0.5 in binary64 first is wrong near half-integers and at large magnitudes.
                mi = new BigDecimal(converted).setScale(0, RoundingMode.HALF_UP).longValueExact();
            } catch (ArithmeticException invalid) { throw bad("Matrix conversion exceeds exact integer MI"); }
        }
        require(mi > 0 && mi <= MAX_TOTAL_MI / attempt.taskPes, "Compute MI is not positive signed-long-instruction representable");
        return mi;
    }

    private Attempt identity(JsonObject main) {
        require(integer(main.get("classType"), "classType") == Parameters.ClassType.COMPUTE.value, "Assignment must identify a compute Job");
        Attempt attempt = attempts.get(integer(main.get("jobId"), "jobId"));
        require(attempt != null && integer(main.get("vmId"), "vmId") == attempt.vm
                && singleton(main.get("taskIds"), "taskIds") == attempt.task, "Main assignment/anchor Job, Task or VM differs");
        return attempt;
    }

    private static final class Action {
        final JsonObject record; final long ordinal; final double at; final int selected;
        final boolean reuse; final Summary summary; final List<Candidate> candidates = new ArrayList<>();
        Action(JsonObject main, Attempt attempt, double at, long ordinal) {
            this.at = at; this.ordinal = ordinal;
            JsonObject attrs = object(main.get("attributes"), "assignment attributes");
            keys(attrs, "assignmentMode", "assignmentPolicy", "inputEstimateSemantics", "cpuReservationSemantics", "auditScope",
                    "assignmentSequence", "binding", "selectedVmId", "observation", "candidates");
            equal(attrs, "assignmentMode", MODE); equal(attrs, "assignmentPolicy", POLICY);
            equal(attrs, "inputEstimateSemantics", INPUT); equal(attrs, "cpuReservationSemantics", CPU); equal(attrs, "auditScope", SCOPE);
            require(positiveLong(attrs.get("assignmentSequence"), "assignmentSequence") == ordinal,
                    "Assignment sequences must be contiguous from one in actual main order");
            String binding = text(attrs.get("binding"), "binding"); reuse = "RETRY_REUSE".equals(binding);
            require(reuse || "INITIAL".equals(binding), "Unknown binding kind");
            selected = integer(attrs.get("selectedVmId"), "selectedVmId");
            require(selected == attempt.vm, "Selected VM differs from full main/final Job binding");
            summary = new Summary(object(attrs.get("observation"), "observation"));
            for (JsonElement row : array(attrs.get("candidates"), "candidates")) candidates.add(new Candidate(object(row, "candidate")));
            record = main.deepCopy();
        }
    }

    private static final class Summary {
        final boolean storage, storeInputs, shared; final double observed, service;
        final int copies, jobs, pending, scoped;
        Summary(JsonObject row) {
            keys(row, "contract", "status", "storageVersion", "storeBackedInputs", "interFlowSharing", "observedThrough", "serviceThrough",
                    "activeCopyCount", "activeJobCount", "pendingOutputFileCount", "scopedFileCount");
            equal(row, "contract", "OBSERVED_DATAFLOW_STATE_V1"); equal(row, "status", "BOUND");
            storage = bool(row.get("storageVersion"), "storageVersion"); storeInputs = bool(row.get("storeBackedInputs"), "storeBackedInputs");
            shared = bool(row.get("interFlowSharing"), "interFlowSharing");
            observed = number(row.get("observedThrough"), "observedThrough"); service = number(row.get("serviceThrough"), "serviceThrough");
            copies = integer(row.get("activeCopyCount"), "activeCopyCount"); jobs = integer(row.get("activeJobCount"), "activeJobCount");
            pending = integer(row.get("pendingOutputFileCount"), "pendingOutputFileCount"); scoped = integer(row.get("scopedFileCount"), "scopedFileCount");
        }
    }

    private static final class Candidate {
        final int vm, joined; final boolean compatible, conditional;
        final double input, cpu, compute, score;
        Candidate(JsonObject row) {
            keys(row, "vmId", "compatible", "inputSeconds", "cpuAvailableAt", "computeSeconds", "scoreFinishSeconds", "conditionalStoreWait", "joinedInputCopies");
            vm = integer(row.get("vmId"), "candidate vmId"); compatible = bool(row.get("compatible"), "compatible");
            if (!compatible) {
                for (String name : CANDIDATE_VALUES) require(required(row, name).isJsonNull(), "Incompatible candidate values must all be null");
                input = cpu = compute = score = 0; conditional = false; joined = 0;
            } else {
                input = number(row.get("inputSeconds"), "inputSeconds"); cpu = number(row.get("cpuAvailableAt"), "cpuAvailableAt");
                compute = positive(row.get("computeSeconds"), "computeSeconds"); score = positive(row.get("scoreFinishSeconds"), "scoreFinishSeconds");
                conditional = bool(row.get("conditionalStoreWait"), "conditionalStoreWait"); joined = integer(row.get("joinedInputCopies"), "joinedInputCopies");
            }
        }
    }

    private static final class Checked {
        final JsonObject document; final boolean storage, quiescent; final int requested, completed;
        Checked(FileLifecycleCodec.Decoded life) {
            require(life != null, "Complete V2 lifecycle is required for online action export");
            document = life.getDocument(); storage = false; quiescent = life.isQuiescent();
            requested = life.getRequestedJobCount(); completed = life.getCompletedJobCount();
        }
        Checked(StorageLifecycleCodec.Decoded life) {
            require(life != null, "Complete V3 lifecycle is required for online action export");
            document = life.getDocument(); storage = true; quiescent = life.isQuiescent();
            requested = life.getRequestedJobCount(); completed = life.getCompletedJobCount();
        }
    }
    private static final class Vm { final int id, pes; final double mips; Vm(int id, int pes, double mips) { this.id = id; this.pes = pes; this.mips = mips; } }
    private static final class Attempt {
        final int id, task, vm, status, taskPes, jobPes; final long lengthMi, effectiveMi;
        Attempt(int id, int task, int vm, int status, int taskPes, int jobPes, long lengthMi, long effectiveMi) {
            this.id = id; this.task = task; this.vm = vm; this.status = status; this.taskPes = taskPes; this.jobPes = jobPes;
            this.lengthMi = lengthMi; this.effectiveMi = effectiveMi;
        }
    }
    private static final class TaskPlan { final Set<FileKey> inputs = new TreeSet<>(), outputs = new TreeSet<>(); }
    private static final class File { final double bytes; File(double bytes) { this.bytes = bytes; } }
    private static final class FileKey implements Comparable<FileKey> {
        final int workflow; final String name;
        FileKey(int workflow, String name) { this.workflow = workflow; this.name = name; }
        @Override public int compareTo(FileKey other) { int order = Integer.compare(workflow, other.workflow); return order == 0 ? name.compareTo(other.name) : order; }
        @Override public boolean equals(Object other) { return other instanceof FileKey && compareTo((FileKey) other) == 0; }
        @Override public int hashCode() { return 31 * workflow + name.hashCode(); }
    }
    private static final class Target {
        final FileKey file; final DataLocation location;
        Target(FileKey file, DataLocation location) { this.file = file; this.location = location; }
        @Override public boolean equals(Object other) { return other instanceof Target && file.equals(((Target) other).file) && location.equals(((Target) other).location); }
        @Override public int hashCode() { return 31 * file.hashCode() + location.hashCode(); }
    }
    private static final class Copy {
        final FileKey file; final DataLocation source, destination; final double rate; final boolean output;
        Copy(FileKey file, DataLocation source, DataLocation destination, double rate, boolean output) {
            this.file = file; this.source = source; this.destination = destination; this.rate = rate; this.output = output;
        }
    }
    private static final class LiveJob {
        final Attempt attempt; final double compute; Double start;
        LiveJob(Attempt attempt, double compute) { this.attempt = attempt; this.compute = compute; }
    }
    private static final class Bounds { double lower, upper; boolean positiveUnknown, conditional; int joined; }
    private static final class Anchor { final long sequence; final double at; Anchor(long sequence, double at) { this.sequence = sequence; this.at = at; } }
    private static final class Retry { final int parent; final double at; Retry(int parent, double at) { this.parent = parent; this.at = at; } }
    private static final class Terminal { final double at; final boolean success; Terminal(double at, boolean success) { this.at = at; this.success = success; } }

    private static FileKey fileKey(JsonElement value) {
        JsonObject row = object(value, "fileId"); keys(row, "workflowInputIndex", "name");
        return new FileKey(integer(row.get("workflowInputIndex"), "workflowInputIndex"), text(row.get("name"), "file name"));
    }
    private static DataLocation location(JsonElement value) {
        JsonObject row = object(value, "location"); keys(row, "kind", "vmId", "sourceId");
        if ("VM".equals(text(row.get("kind"), "location kind"))) {
            require(required(row, "sourceId").isJsonNull(), "VM cannot have a SOURCE identity");
            return DataLocation.vm(integer(row.get("vmId"), "location vmId"));
        }
        equal(row, "kind", "SOURCE"); equal(row, "sourceId", "source");
        require(required(row, "vmId").isJsonNull(), "SOURCE cannot have a VM identity"); return SOURCE;
    }
    private static int singleton(JsonElement value, String name) {
        JsonArray ids = array(value, name); require(ids.size() == 1, "Compute membership must be singleton: " + name);
        return integer(ids.get(0), name);
    }
    private static JsonElement required(JsonObject row, String name) { require(row != null && row.has(name), "Missing field: " + name); return row.get(name); }
    private static JsonObject object(JsonElement value, String name) { require(value != null && value.isJsonObject(), "Expected object: " + name); return value.getAsJsonObject(); }
    private static JsonArray array(JsonElement value, String name) { require(value != null && value.isJsonArray(), "Expected array: " + name); return value.getAsJsonArray(); }
    private static String text(JsonElement value, String name) { require(value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isString(), "Expected string: " + name); return value.getAsString(); }
    private static boolean bool(JsonElement value, String name) { require(value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isBoolean(), "Expected boolean: " + name); return value.getAsBoolean(); }
    private static BigDecimal decimal(JsonElement value, String name) {
        require(value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isNumber(), "Expected raw JSON number: " + name);
        String token = value.getAsString(); require(token.length() <= MAX_NUMBER_DIGITS, "Numeric token too long: " + name);
        try {
            BigDecimal result = new BigDecimal(token);
            require(result.precision() <= MAX_NUMBER_DIGITS && Math.abs((long) result.scale()) <= MAX_NUMBER_DIGITS, "Numeric exponent/precision exceeds limit: " + name);
            return result;
        } catch (NumberFormatException invalid) { throw bad("Invalid finite numeric token: " + name); }
    }
    private static double number(JsonElement value, String name) {
        BigDecimal decimal = decimal(value, name); double result = decimal.doubleValue();
        require(decimal.signum() >= 0 && Double.isFinite(result) && (result != 0 || decimal.signum() == 0), "Unsupported nonnegative binary64 value: " + name);
        return result == 0 ? 0 : result;
    }
    private static double positive(JsonElement value, String name) { double result = number(value, name); require(result > 0, "Expected positive scalar: " + name); return result; }
    private static long whole(JsonElement value, String name) {
        try { return decimal(value, name).longValueExact(); }
        catch (ArithmeticException invalid) { throw bad("Expected exact int64: " + name); }
    }
    private static long positiveLong(JsonElement value, String name) { long result = whole(value, name); require(result > 0, "Expected positive int64: " + name); return result; }
    private static int integer(JsonElement value, String name) { long result = whole(value, name); require(result >= 0 && result <= Integer.MAX_VALUE, "Expected nonnegative int32: " + name); return (int) result; }
    private static int positiveInt(JsonElement value, String name) { int result = integer(value, name); require(result > 0, "Expected positive int32: " + name); return result; }
    private static double ratio(double work, double rate, String name) { double result = work / rate; require(work > 0 && rate > 0 && Double.isFinite(result) && result > 0, "Unrepresentable positive duration: " + name); return result; }
    private static double addFinite(double a, double b) { double result = a + b; require(a >= 0 && b >= 0 && Double.isFinite(result), "Nominal aggregate overflows binary64"); return result; }
    private static double addTime(double at, double delay) { double result = addFinite(at, delay); require(delay == 0 || result > at, "Positive nominal duration cannot advance binary64 clock"); return result; }
    private static void equal(JsonObject row, String name, String expected) { require(expected.equals(text(row.get(name), name)), "Unsupported " + name); }
    private static void keys(JsonObject row, String... names) { require(row.keySet().equals(new HashSet<>(Arrays.asList(names))), "Missing/unknown fields; expected " + Arrays.toString(names)); }
    private static void same(JsonElement a, JsonElement b, String message) {
        if (a != null && b != null && a.isJsonObject() && b.isJsonObject()) {
            JsonObject x = a.getAsJsonObject(), y = b.getAsJsonObject(); require(x.keySet().equals(y.keySet()), message);
            for (String key : y.keySet()) same(x.get(key), y.get(key), message);
        } else if (a != null && b != null && a.isJsonArray() && b.isJsonArray()) {
            JsonArray x = a.getAsJsonArray(), y = b.getAsJsonArray(); require(x.size() == y.size(), message);
            for (int i = 0; i < y.size(); i++) same(x.get(i), y.get(i), message);
        } else if (a != null && b != null && a.isJsonPrimitive() && b.isJsonPrimitive()
                && a.getAsJsonPrimitive().isNumber() && b.getAsJsonPrimitive().isNumber()) require(decimal(a, message).compareTo(decimal(b, message)) == 0, message);
        else require(a != null && a.equals(b), message);
    }
    private void requireOpen() { if (closed) throw new IllegalStateException("Assignment audit is already finished"); }
    private static void require(boolean condition, String message) { if (!condition) throw bad(message); }
    private static IllegalArgumentException bad(String message) { return new IllegalArgumentException("Invalid dataflow assignment: " + message); }
}
