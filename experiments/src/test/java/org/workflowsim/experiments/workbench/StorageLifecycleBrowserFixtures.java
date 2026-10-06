package org.workflowsim.experiments.workbench;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.workflowsim.FileItem;
import org.workflowsim.Task;
import org.workflowsim.data.v2.CoherentDataflowRuntime;
import org.workflowsim.data.v2.DataTransferFabric;
import org.workflowsim.data.v2.DataflowFilePlan;
import org.workflowsim.data.v2.DataflowStorageSpec;
import org.workflowsim.data.v2.StorageLifecycleCodec;
import org.workflowsim.experiment.ExperimentArtifactValidator;
import org.workflowsim.utils.Parameters.FileType;

/** Public V3 browser fixtures plus valid standalone captures explicitly not bound to the CPU snapshot. */
final class StorageLifecycleBrowserFixtures {
    private static final int BUDGET = 2000, COPY_PREVIEW_LIMIT = 64, LABEL_PREVIEW_LIMIT = 256;
    private static final String SHARED = "COHERENT_STORAGE_DATAFLOW_V3";
    private static final String ISOLATED = "COHERENT_STORAGE_DATAFLOW_NO_CONTENTION_V3";
    private static final String V2 = "COHERENT_FILE_DATAFLOW_V2";
    private static final String V1 = "PRE_EXECUTION_TRANSFER_DELAY_WITH_CONTENTION_V1";
    private static final String FULL_WORKFLOW = "<adag><job id=\"p\" runtime=\"1\">"
            + "<uses file=\"external-seed\" link=\"input\" size=\"0.5\"/>"
            + "<uses file=\"x\" link=\"output\" size=\"1000000\"/>"
            + "<uses file=\"unused-output\" link=\"output\" size=\"500000\"/>"
            + "<uses file=\"zero\" link=\"output\" size=\"0\"/></job>"
            + "<job id=\"c\" runtime=\"1\"><uses file=\"x\" link=\"input\" size=\"1000000\"/>"
            + "<uses file=\"zero\" link=\"input\" size=\"0\"/></job>"
            + "<child ref=\"c\"><parent ref=\"p\"/></child></adag>";
    private static final String ZERO_WORKFLOW = "<adag><job id=\"zero-output\" runtime=\"1\">"
            + "<uses file=\"unused-zero\" link=\"output\" size=\"0\"/></job></adag>";

    private StorageLifecycleBrowserFixtures() { }

    /** Append exactly 23 new report specifications without altering the preceding V1/V2/P0 entries. */
    static void append(Path output, List<Map<String, Object>> reports, Path offManifest) throws Exception {
        Path root = output.toAbsolutePath().normalize();
        int initialCount = reports.size();
        Map<String, Bundle> bundles = new LinkedHashMap<>();
        for (boolean storeInputs : new boolean[]{false, true}) {
            for (boolean shared : new boolean[]{true, false}) {
                for (boolean fat : new boolean[]{false, true}) {
                    String name = "storage-lifecycle-" + (storeInputs ? "shared-input-" : "local-")
                            + (shared ? "shared-" : "isolated-") + (fat ? "fat-" : "endpoint-") + "full";
                    Path experiment = run(root, name, storeInputs, shared ? SHARED : ISOLATED, fat, FULL_WORKFLOW, BUDGET);
                    Bundle bundle = new Bundle(manifest(experiment));
                    require(bundle.decoded != null && bundle.decoded.isQuiescent(), name + " needs a terminal V3 certificate");
                    require(bundle.decoded.getCopyCount() > 0 && bundle.decoded.getPendingOutputFileCount() == 0
                            && bundle.decoded.getWaitingStoreInputCount() == 0, name + " must drain successful outputs and input waits");
                    Map<String, Object> oracle = expect(bundle.decoded, true);
                    require("1500000".equals(values(oracle).get("storage-lifecycle-output-settled")),
                            name + " must commit both the consumed and unused positive output");
                    bundles.put(name, bundle);
                    pair(root, reports, name, experiment, bundle);
                }
            }
        }

        String zeroName = "storage-lifecycle-zero";
        Path zeroExperiment = run(root, zeroName, true, SHARED, false, ZERO_WORKFLOW, BUDGET);
        Bundle zero = new Bundle(manifest(zeroExperiment));
        require(zero.decoded != null && zero.decoded.isQuiescent() && zero.decoded.getCopyCount() == 0,
                "The actual SHARED-input zero-output run must have capture enabled and no positive copies");
        require(hasEvent(zero.decoded.getDocument(), "OUTPUT_RESOLVED", "ZERO"), "The zero fixture must publish an actual successful zero output");
        pair(root, reports, zeroName, zeroExperiment, zero);

        String offName = "storage-lifecycle-off";
        Path offExperiment = run(root, offName, true, SHARED, false, FULL_WORKFLOW, 0);
        Bundle off = new Bundle(manifest(offExperiment));
        require(off.decoded == null, "V3 OFF must not fabricate a storage certificate");
        pair(root, reports, offName, offExperiment, off);
        insufficientCapture(root, reports);

        // Extra actual bundles exist only for cross-version switching, not as additional report specs.
        Bundle file = new Bundle(manifest(run(root, "storage-lifecycle-mixed-v2-bundle", false, V2, false, FULL_WORKFLOW, BUDGET)));
        Bundle network = new Bundle(manifest(run(root, "storage-lifecycle-mixed-v1-bundle", false, V1, false, FULL_WORKFLOW, BUDGET)));
        switching(root, reports, bundles.get("storage-lifecycle-shared-input-shared-endpoint-full"), file, network, off, zero);
        typedStress(root, reports, new Bundle(offManifest));
        require(reports.size() - initialCount == 23, "Storage fixtures must append exactly 23 report specifications");
    }

    private static Path run(Path output, String name, boolean storeInputs, String model, boolean fat, String workflow, int budget) throws Exception {
        Path input = output.resolve(name + ".dax"), configPath = output.resolve(name + ".json"), runRoot = output.resolve(name);
        requireNew(input, configPath, runRoot);
        WorkbenchTestSupport.text(input, workflow);
        JsonObject config = WorkbenchTestSupport.localConfiguration(input.toString(), 2, fat);
        config.addProperty("name", "V3 storage public browser fixture · " + name);
        WorkbenchTestSupport.algorithms(config, WorkbenchTestSupport.algorithm("random", "STATIC", "RANDOM"));
        JsonArray seeds = new JsonArray(); seeds.add(1); config.add("seeds", seeds);
        JsonObject simulation = config.getAsJsonObject("simulation");
        simulation.addProperty("fileSystem", storeInputs ? "SHARED" : "LOCAL");
        simulation.addProperty("dataMovementModel", model);
        boolean storage = SHARED.equals(model) || ISOLATED.equals(model);
        if (storage) {
            // Workbench creates hosts 0..vmCount-1. Declare all four source fields explicitly.
            JsonObject source = new JsonObject();
            source.addProperty("attachmentHostId", 0);
            source.addProperty("readBandwidthMbPerSecond", 1);
            source.addProperty("writeBandwidthMbPerSecond", .5);
            source.addProperty("networkBandwidthMbPerSecond", 2);
            config.getAsJsonObject("platform").add("sourceStorage", source);
        }
        if (budget > 0) {
            JsonObject recording = new JsonObject();
            recording.addProperty("mode", storage ? "FILE_STORAGE_LIFECYCLE_V3" : V2.equals(model) ? "FILE_LIFECYCLE_V2" : "FLUID_GROUP_LEDGER_V1");
            recording.addProperty("maxTraceRecords", budget);
            simulation.add("networkEvidence", recording);
        }
        return Workbench.run(WorkbenchTestSupport.json(configPath, config), runRoot);
    }

    private static void pair(Path output, List<Map<String, Object>> reports, String name, Path experiment, Bundle bundle) throws Exception {
        Map<String, Object> expected = expect(bundle.decoded, true);
        reports.add(spec(name, experiment.resolve("report.html"), Collections.singletonList(bundle.row("random")), Collections.singletonList(expected)));
        Path standalone = output.resolve(name + "-standalone.html");
        requireNew(standalone);
        Workbench.main(new String[]{"report", bundle.path.toString(), standalone.toString()});
        JsonObject configuration = bundle.report.getManifest().getAsJsonObject("configuration");
        String planner = configuration.get("planningAlgorithm").getAsString();
        String candidate = "INVALID".equals(planner) ? configuration.get("schedulingAlgorithm").getAsString() : planner;
        reports.add(spec(name + "-standalone", standalone, Collections.singletonList(bundle.row(candidate)), Collections.singletonList(expected)));
    }

    private static void insufficientCapture(Path output, List<Map<String, Object>> reports) throws Exception {
        String name = "storage-lifecycle-insufficient-capture";
        Path experiment = run(output, name, true, SHARED, false, FULL_WORKFLOW, 1);
        JsonObject displayed = WorkbenchTestSupport.payload(experiment.resolve("report.html")).getAsJsonArray("runs").get(0).getAsJsonObject();
        require("FAILED".equals(displayed.get("status").getAsString()), "Budget-one V3 export must fail rather than look like zero copies");
        require(!displayed.has("manifest") && !displayed.has("storageLifecycle") && !displayed.has("fileLifecycle") && !displayed.has("networkEvidence"),
                "Failed V3 export must not publish a verified manifest or any evidence view");
        JsonObject retained = WorkbenchTestSupport.object(experiment.resolve("experiment.json")).getAsJsonArray("runs").get(0).getAsJsonObject();
        require(!retained.has("manifest"), "Failed V3 export must not advertise a verified manifest");
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("candidate", "random"); row.put("seed", "1"); row.put("status", "FAILED");
        reports.add(spec(name, experiment.resolve("report.html"), Collections.singletonList(row), Collections.singletonList(state("NO_RESULT"))));
    }

    private static void switching(Path output, List<Map<String, Object>> reports, Bundle full, Bundle file, Bundle network, Bundle off, Bundle zero) throws IOException {
        List<Map<String, Object>> rows = new ArrayList<>(), expected = new ArrayList<>();
        Map<String, Object> first = full.row("v3-full"); rows.add(first); expected.add(expect(full.decoded, true));
        Map<String, Object> v2 = file.row("v2-full");
        require(v2.containsKey("fileLifecycle") && !v2.containsKey("storageLifecycle") && !v2.containsKey("networkEvidence"), "Mixed V2 row must retain only its actual V2 view");
        rows.add(v2); expected.add(state("HIDDEN"));
        Map<String, Object> v1 = network.row("v1-full");
        require(v1.containsKey("networkEvidence") && !v1.containsKey("storageLifecycle") && !v1.containsKey("fileLifecycle"), "Mixed V1 row must retain only its actual V1 view");
        rows.add(v1); expected.add(state("HIDDEN"));
        rows.add(off.row("v3-off")); expected.add(state("OFF"));
        Map<String, Object> failed = new LinkedHashMap<>();
        failed.put("candidate", "failed-stale-v3"); failed.put("seed", "1"); failed.put("status", "FAILED");
        failed.put("error", "Deliberate no-validated-result control; stale V3 payload must remain hidden and every table cleared");
        failed.put("storageLifecycle", first.get("storageLifecycle"));
        rows.add(failed); expected.add(state("NO_RESULT"));
        rows.add(zero.row("v3-zero")); expected.add(expect(zero.decoded, true));
        rows.add(full.row("v3-full-again")); expected.add(expect(full.decoded, true));
        String name = "storage-lifecycle-state-switch";
        Path target = output.resolve(name + ".html"); requireNew(target);
        HtmlReports.experiment("V3 / V2 / V1 / OFF / 无验证结果 / 零正复制 状态切换", rows, target);
        reports.add(spec(name, target, rows, expected));
    }

    private static void typedStress(Path output, List<Map<String, Object>> reports, Bundle cpu) throws IOException {
        List<Map<String, Object>> rows = new ArrayList<>(), expected = new ArrayList<>();
        double tinyBytes = 3 * Double.MIN_VALUE;
        CoherentDataflowRuntime tiny = runtime(true, true, plan(task(1, in("tiny-in", tinyBytes), out("tiny-out", tinyBytes))), fabric(1, 1, 1, 1, 1, 1));
        tiny.requestJob(11, one(1), 7, 0); tiny.advance(tinyBytes); finish(tiny, 11, 1, 7, tinyBytes, tinyBytes); tiny.advance(2 * tinyBytes);
        typed(rows, expected, cpu, "typed-v3-minimum-duration", decode(tiny));

        for (boolean outputs : new boolean[]{false, true}) {
            // Separate files/Jobs keep each runtime aggregate finite. Ordinary rates avoid an invalid
            // Double.MAX_VALUE / 1e6 * 1e6 store conversion and still give sums beyond binary64.
            Task a = task(1, outputs ? out("huge-a", 1e308) : in("huge-a", 1e308));
            Task b = task(2, outputs ? out("huge-b", 1e308) : in("huge-b", 1e308));
            CoherentDataflowRuntime huge = runtime(true, true, plan(a, b), fabric(100, 100, 100, 100, 100, 100));
            if (outputs) { produce(huge, 1, 1, 7, 0, 0); produce(huge, 2, 2, 7, 0, 0); }
            else { huge.requestJob(1, one(1), 7, 0); huge.requestJob(2, one(2), 7, 0); }
            huge.advance(4e306);
            if (!outputs) { finish(huge, 1, 1, 7, 4e306, 4e306); finish(huge, 2, 2, 7, 4e306, 4e306); }
            typed(rows, expected, cpu, "typed-v3-beyond-double-" + (outputs ? "output" : "input") + "-sum", decode(huge));
        }

        String hostile = "</script><img src=x onerror=alert(1)>节点\"'\u2028\u2029@@DATA@@";
        List<Task> tasks = new ArrayList<>();
        Map<Integer, Double> capacities = new LinkedHashMap<>(); Map<Integer, Integer> hosts = new LinkedHashMap<>();
        for (int i = 0; i < 62; i++) { capacities.put(i, 1.0); hosts.put(i, i); }
        for (int i = 0; i < 65; i++) {
            int remaining = i % 2 + 1;
            String name = i == 0 ? hostile : hostile + repeat('x', LABEL_PREVIEW_LIMIT - "input[0]/".length() - hostile.length() - remaining) + "😀tail-" + i;
            tasks.add(task(i + 1, in(name, 1)));
        }
        DataTransferFabric cappedFabric = DataTransferFabric.withStorage(capacities, hosts, null, DataflowStorageSpec.of(0, .01, .01, .01));
        CoherentDataflowRuntime many = runtime(true, true, plan(tasks.toArray(new Task[0])), cappedFabric);
        for (int i = 0; i < 65; i++) many.requestJob(10000 - i, one(i + 1), i % 62, 0);
        many.advance(2);
        for (int i = 0; i < 65; i++) finish(many, 10000 - i, i + 1, i % 62, 2, 2);
        StorageLifecycleCodec.Decoded capped = decode(many);
        require(capped.getCopyCount() == 65 && capped.getRequestedJobCount() == 65
                && capped.getDocument().getAsJsonObject("fabric").getAsJsonArray("resources").size() == 65,
                "Typed cap fixture must have exactly 65 copies, resources and Jobs");
        typed(rows, expected, cpu, "typed-v3-hostile-capped", capped);

        FileItem[] seeds = new FileItem[129];
        for (int i = 0; i < seeds.length; i++) seeds[i] = in("seed-" + i, 1);
        StorageLifecycleCodec.Decoded eventCap = decode(runtime(true, true, plan(task(1, seeds)), fabric(1, 1, 1, 1, 1, 1)));
        require(eventCap.getDocument().getAsJsonArray("events").size() == 129, "Typed event cap fixture needs exactly 129 records");
        typed(rows, expected, cpu, "typed-v3-event-cap", eventCap);

        CoherentDataflowRuntime waiting = waiting(false);
        typed(rows, expected, cpu, "typed-v3-source-wait-prefix", decode(waiting));
        waiting.advance(11);
        require(waiting.drainReadyJobIds().isEmpty(), "SOURCE settlement must not create VM arrival");
        typed(rows, expected, cpu, "typed-v3-source-committed-vm-pending", decode(waiting));
        CoherentDataflowRuntime sameVm = waiting(true); sameVm.advance(11);
        typed(rows, expected, cpu, "typed-v3-same-vm-store-gate", decode(sameVm));

        CoherentDataflowRuntime tail = runtime(true, false, plan(task(1, out("unused-positive", 100))), fabric(100, 100, 100, 20, 10, 100));
        produce(tail, 10, 1, 7, 0, 1);
        typed(rows, expected, cpu, "typed-v3-output-tail-prefix", decode(tail));
        tail.advance(20);
        typed(rows, expected, cpu, "typed-v3-observed-output-tail", decode(tail));
        typed(rows, expected, cpu, "typed-v3-cached-holder-origin", decode(cachedHolder()));

        Task p = task(1, out("fractional", 1)), c = task(2, in("fractional", 1)); edge(p, c);
        CoherentDataflowRuntime fractional = runtime(true, true, plan(p, c), fabric(1, 1, 1, 1, 1, 1));
        produce(fractional, 10, 1, 7, .05, .1); fractional.requestJob(20, one(2), 42, .3);
        fractional.advance(2.3); fractional.advance(3.4);
        typed(rows, expected, cpu, "typed-v3-fct-observation-lag", decode(fractional));
        require(rows.size() == 12, "Typed storage stress fixture must retain its twelve distinct states");

        String name = "storage-lifecycle-typed-stress";
        Path target = output.resolve(name + ".html"); requireNew(target);
        HtmlReports.experiment("独立 V3 存储显示夹具 · 不绑定所附 CPU 运行上下文 · 非流体服务记账", rows, target);
        reports.add(spec(name, target, rows, expected));
    }

    private static void typed(List<Map<String, Object>> rows, List<Map<String, Object>> expected, Bundle cpu, String name, StorageLifecycleCodec.Decoded decoded) {
        Map<String, Object> row = cpu.row(name);
        // Keep the genuine cached CPU manifest unchanged. Only this display payload is standalone.
        row.remove("networkEvidence"); row.remove("fileLifecycle");
        row.put("storageLifecycle", StorageLifecycleReportView.fromDecoded(decoded, false));
        rows.add(row); expected.add(expect(decoded, false));
    }

    /** Independent decoded-certificate oracle: never inspect StorageLifecycleReportView output. */
    private static Map<String, Object> expect(StorageLifecycleCodec.Decoded decoded, boolean contextValidated) {
        if (decoded == null) return state("OFF");
        JsonObject document = decoded.getDocument(), plan = document.getAsJsonObject("filePlan"), fabric = document.getAsJsonObject("fabric");
        Map<DataflowFilePlan.FileId, BigDecimal> sizes = new LinkedHashMap<>();
        Map<Integer, JsonObject> tasks = new LinkedHashMap<>();
        for (JsonElement item : plan.getAsJsonArray("files")) {
            JsonObject file = item.getAsJsonObject(); sizes.put(fileId(file.getAsJsonObject("fileId")), exact(file.get("bytes")));
        }
        for (JsonElement item : plan.getAsJsonArray("tasks")) {
            JsonObject task = item.getAsJsonObject(); tasks.put(task.get("taskId").getAsInt(), task);
        }
        Map<String, BigDecimal> capacities = new LinkedHashMap<>();
        for (JsonElement item : fabric.getAsJsonArray("resources")) {
            JsonObject resource = item.getAsJsonObject(); capacities.put(resource.get("key").getAsString(), exact(resource.get("capacityBytesPerSecond")));
        }
        Map<Long, CopyFact> copies = new LinkedHashMap<>();
        List<String> sources = new ArrayList<>(), purposes = new ArrayList<>(), owners = new ArrayList<>();
        BigDecimal admittedInput = BigDecimal.ZERO, admittedOutput = BigDecimal.ZERO, settledInput = BigDecimal.ZERO,
                settledOutput = BigDecimal.ZERO, residual = BigDecimal.ZERO, requiredBytes = BigDecimal.ZERO, localBytes = BigDecimal.ZERO;
        BigInteger references = BigInteger.ZERO, localReferences = BigInteger.ZERO, joinedReferences = BigInteger.ZERO;
        BigDecimal lastCpu = null, lastCommit = null;
        for (JsonElement item : document.getAsJsonArray("events")) {
            JsonObject event = item.getAsJsonObject(), payload = event.getAsJsonObject("payload");
            switch (event.get("type").getAsString()) {
                case "JOB_INPUT_REQUESTED":
                    // Sum each actual request's declared demands once, independently of the view's
                    // initial RESOLVED/WAITING classification. A later deferred resolution adds no demand.
                    for (JsonElement taskId : payload.getAsJsonArray("taskIds")) {
                        for (JsonElement input : tasks.get(taskId.getAsInt()).getAsJsonArray("inputs")) {
                            JsonObject demand = input.getAsJsonObject();
                            BigInteger count = demand.get("referenceCount").getAsBigDecimal().toBigIntegerExact();
                            references = references.add(count);
                            requiredBytes = requiredBytes.add(sizes.get(fileId(demand.getAsJsonObject("fileId"))).multiply(new BigDecimal(count)));
                        }
                    }
                    break;
                case "COPY_ADMITTED":
                    BigDecimal bytes = sizes.get(fileId(payload.getAsJsonObject("fileId")));
                    String purpose = payload.get("purpose").getAsString();
                    boolean output = "OUTPUT".equals(purpose);
                    copies.put(payload.get("copyOrdinal").getAsLong(), new CopyFact(bytes, output));
                    if (output) admittedOutput = admittedOutput.add(bytes); else admittedInput = admittedInput.add(bytes);
                    if (sources.size() < COPY_PREVIEW_LIMIT) {
                        sources.add(location(payload.getAsJsonObject("sourceReplica").getAsJsonObject("location")));
                        purposes.add(purpose); owners.add(integer(payload.get("ownerJobId")));
                    }
                    break;
                case "COPY_SETTLED":
                    CopyFact copy = copies.get(payload.get("copyOrdinal").getAsLong());
                    if (copy.output) { settledOutput = settledOutput.add(copy.bytes); lastCommit = maximum(lastCommit, exact(event.get("observedTime"))); }
                    else settledInput = settledInput.add(copy.bytes);
                    residual = residual.add(exact(payload.get("remainingAfterService")));
                    break;
                case "INPUT_RESOLVED":
                    BigInteger count = payload.get("referenceCount").getAsBigDecimal().toBigIntegerExact();
                    String resolution = payload.get("resolution").getAsString();
                    if ("LOCAL".equals(resolution)) {
                        localReferences = localReferences.add(count);
                        localBytes = localBytes.add(sizes.get(fileId(payload.getAsJsonObject("fileId"))).multiply(new BigDecimal(count)));
                    } else if ("JOIN_EXISTING".equals(resolution)) joinedReferences = joinedReferences.add(count);
                    break;
                case "TASK_FINISHED":
                    lastCpu = maximum(lastCpu, exact(event.get("observedTime")));
                    break;
                case "OUTPUT_RESOLVED":
                    // Only first zero-byte publication is a new commit. ALREADY_STORED and seeds are not.
                    if ("ZERO".equals(payload.get("resolution").getAsString())) lastCommit = maximum(lastCommit, exact(event.get("observedTime")));
                    break;
                default:
                    break;
            }
        }
        String files = Integer.toString(plan.getAsJsonArray("files").size()), taskCount = Integer.toString(plan.getAsJsonArray("tasks").size());
        String count = Long.toString(decoded.getCopyCount()), completed = Long.toString(decoded.getCompletedCopyCount()), active = Integer.toString(decoded.getActiveCopyCount());
        String pending = Integer.toString(decoded.getPendingOutputFileCount()), waiting = Integer.toString(decoded.getWaitingStoreInputCount());
        String requestedJobs = Integer.toString(decoded.getRequestedJobCount()), completedJobs = Integer.toString(decoded.getCompletedJobCount());
        Map<String, Object> expected = state(decoded.getCopyCount() == 0 ? "ZERO" : "COMPLETE");
        expected.put("contextValidated", contextValidated); expected.put("quiescent", decoded.isQuiescent());
        expected.put("copyCount", count); expected.put("completedCopyCount", completed); expected.put("activeCopyCount", active);
        expected.put("pendingOutputFileCount", pending); expected.put("waitingStoreInputCount", waiting);
        expected.put("fileCount", files); expected.put("taskCount", taskCount);
        expected.put("requestedJobCount", requestedJobs); expected.put("completedJobCount", completedJobs);
        expected.put("resourceCount", Integer.toString(fabric.getAsJsonArray("resources").size()));
        expected.put("eventCount", Integer.toString(document.getAsJsonArray("events").size()));
        expected.put("copyPurposes", purposes); expected.put("copyOwners", owners); expected.put("copySources", sources);
        expected.put("modelKind", document.get("modelKind").getAsString());
        JsonObject policies = document.getAsJsonObject("policies");
        expected.put("sharingPolicy", policies.get("sharing").getAsString());
        expected.put("inputAccess", policies.get("inputAccess").getAsString());
        expected.put("outputCommit", policies.get("outputCommit").getAsString());
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("storage-lifecycle-host", integer(fabric.getAsJsonObject("sourceStorage").get("attachmentHostId")));
        values.put("storage-lifecycle-read-rate", text(capacities.get(DataTransferFabric.STORE_READ)));
        values.put("storage-lifecycle-write-rate", text(capacities.get(DataTransferFabric.STORE_WRITE)));
        values.put("storage-lifecycle-nic-rate", text(capacities.get(DataTransferFabric.STORE_NIC)));
        values.put("storage-lifecycle-plan-count", files + " / " + taskCount);
        values.put("storage-lifecycle-job-count", requestedJobs + " / " + completedJobs);
        values.put("storage-lifecycle-copy-count", count + " / " + completed + " / " + active);
        values.put("storage-lifecycle-obligations", pending + " / " + waiting);
        values.put("storage-lifecycle-input-admitted", text(admittedInput)); values.put("storage-lifecycle-output-admitted", text(admittedOutput));
        values.put("storage-lifecycle-input-settled", text(settledInput)); values.put("storage-lifecycle-output-settled", text(settledOutput));
        values.put("storage-lifecycle-reference-count", references + " / " + localReferences + " / " + joinedReferences);
        values.put("storage-lifecycle-reference-bytes", text(requiredBytes)); values.put("storage-lifecycle-local-bytes", text(localBytes));
        values.put("storage-lifecycle-residual", text(residual));
        values.put("storage-lifecycle-last-cpu", display(lastCpu)); values.put("storage-lifecycle-last-commit", display(lastCommit));
        values.put("storage-lifecycle-tail", display(decoded.isQuiescent() && lastCpu != null && lastCommit != null ? lastCommit.subtract(lastCpu).max(BigDecimal.ZERO) : null));
        values.put("storage-lifecycle-observed", text(exact(document.getAsJsonObject("capture").get("observedThrough"))));
        expected.put("values", values);
        return expected;
    }

    private static final class CopyFact {
        final BigDecimal bytes;
        final boolean output;
        CopyFact(BigDecimal bytes, boolean output) { this.bytes = bytes; this.output = output; }
    }
    private static BigDecimal exact(JsonElement value) { return new BigDecimal(value.getAsDouble()); }
    private static String integer(JsonElement value) { return value.getAsBigDecimal().toBigIntegerExact().toString(); }
    private static String text(BigDecimal value) { return value.stripTrailingZeros().toPlainString(); }
    private static String display(BigDecimal value) { return value == null ? null : text(value); }
    private static BigDecimal maximum(BigDecimal previous, BigDecimal value) { return previous == null ? value : previous.max(value); }
    private static String location(JsonObject location) {
        return "VM".equals(location.get("kind").getAsString()) ? "VM:" + integer(location.get("vmId")) : "SOURCE:" + location.get("sourceId").getAsString();
    }
    private static DataflowFilePlan.FileId fileId(JsonObject id) {
        return DataflowFilePlan.FileId.of(id.get("workflowInputIndex").getAsInt(), id.get("name").getAsString());
    }
    private static boolean hasEvent(JsonObject document, String type, String resolution) {
        for (JsonElement item : document.getAsJsonArray("events")) {
            JsonObject event = item.getAsJsonObject();
            if (type.equals(event.get("type").getAsString()) && resolution.equals(event.getAsJsonObject("payload").get("resolution").getAsString())) return true;
        }
        return false;
    }
    @SuppressWarnings("unchecked")
    private static Map<String, Object> values(Map<String, Object> expected) { return (Map<String, Object>) expected.get("values"); }
    private static Map<String, Object> state(String state) {
        Map<String, Object> result = new LinkedHashMap<>(); result.put("state", state); return result;
    }
    private static Map<String, Object> spec(String name, Path path, List<Map<String, Object>> rows, List<Map<String, Object>> lifecycle) {
        List<String> candidates = new ArrayList<>(), seeds = new ArrayList<>(), statuses = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            candidates.add((String) row.get("candidate")); seeds.add((String) row.get("seed")); statuses.add((String) row.get("status"));
        }
        Map<String, Object> spec = new LinkedHashMap<>();
        spec.put("name", name); spec.put("path", path.toString()); spec.put("candidates", candidates); spec.put("seeds", seeds); spec.put("statuses", statuses);
        spec.put("storageLifecycle", lifecycle); // No V1 network or V2 file-lifecycle expectations on these new report specs.
        return spec;
    }
    private static Path manifest(Path experiment) { return experiment.resolve("runs/random-s1/result.manifest.json"); }
    private static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
    private static void requireNew(Path... paths) throws IOException {
        for (Path path : paths) if (Files.exists(path)) throw new IOException("Refusing existing storage fixture path: " + path.toAbsolutePath().normalize());
    }

    /** Attach exactly one cached, actually validated manifest/view snapshot without reconstructing CPU evidence. */
    private static final class Bundle {
        final Path path;
        final HtmlReports.ValidatedReport report;
        final StorageLifecycleCodec.Decoded decoded;
        Bundle(Path path) throws IOException {
            this.path = path;
            ExperimentArtifactValidator.ValidationResult checked = ExperimentArtifactValidator.validate(path);
            report = HtmlReports.validatedReport(checked);
            decoded = checked.getDecodedStorageLifecycle();
            require("COMPLETED_SUCCESSFULLY".equals(report.getManifest().getAsJsonObject("result").get("logicalTaskCompletionStatus").getAsString()),
                    "Fixture CPU bundle must be an actual successful public run: " + path);
        }
        Map<String, Object> row(String candidate) {
            JsonObject manifest = report.getManifest();
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("candidate", candidate);
            row.put("seed", Long.toString(manifest.getAsJsonObject("configuration").get("rootSeed").getAsBigDecimal().longValueExact()));
            row.put("status", manifest.getAsJsonObject("result").get("logicalTaskCompletionStatus").getAsString());
            report.attachTo(row);
            return row;
        }
    }

    private static CoherentDataflowRuntime runtime(boolean shared, boolean storeInputs, DataflowFilePlan plan, DataTransferFabric fabric) {
        CoherentDataflowRuntime runtime = CoherentDataflowRuntime.withStorage(shared, BUDGET, storeInputs);
        runtime.initializePlan(plan, fabric.getLocations()); runtime.bindFabric(fabric); return runtime;
    }
    private static DataTransferFabric fabric(double a, double b, double c, double read, double write, double nic) {
        Map<Integer, Double> capacities = new LinkedHashMap<>(); capacities.put(7, a); capacities.put(42, b); capacities.put(99, c);
        Map<Integer, Integer> hosts = new LinkedHashMap<>(); hosts.put(7, 10); hosts.put(42, 20); hosts.put(99, 30);
        return DataTransferFabric.withStorage(capacities, hosts, null, DataflowStorageSpec.of(10, read / 1e6, write / 1e6, nic / 1e6));
    }
    private static StorageLifecycleCodec.Decoded decode(CoherentDataflowRuntime runtime) {
        return StorageLifecycleCodec.decode(StorageLifecycleCodec.encode(runtime.captureStorageEvidence()));
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
    private static List<Integer> one(int id) { return Collections.singletonList(id); }
    private static void edge(Task parent, Task child) { parent.addChild(child); child.addParent(parent); }
    private static void produce(CoherentDataflowRuntime runtime, int job, int task, int vm, double start, double end) {
        runtime.requestJob(job, one(task), vm, start); finish(runtime, job, task, vm, start, end);
    }
    private static void finish(CoherentDataflowRuntime runtime, int job, int task, int vm, double start, double end) {
        runtime.drainReadyJobIds(); runtime.cpuStarted(job, one(task), vm, start);
        runtime.jobFinished(job, one(task), vm, Collections.singletonList(true), end);
    }
    private static String repeat(char character, int count) {
        char[] chars = new char[count]; Arrays.fill(chars, character); return new String(chars);
    }
    private static CoherentDataflowRuntime waiting(boolean sameVm) {
        Task p = task(1, out("x", 100)), c = task(2, in("x", 100)); edge(p, c);
        CoherentDataflowRuntime runtime = runtime(true, true, plan(p, c), fabric(100, 100, 100, 20, 10, 100));
        produce(runtime, 10, 1, 7, 0, 1); runtime.requestJob(20, one(2), sameVm ? 7 : 42, 1); return runtime;
    }
    private static CoherentDataflowRuntime cachedHolder() {
        Task p = task(1, out("x", 100)), a = task(2, in("x", 100)), b = task(3, in("x", 100)); edge(p, a); edge(p, b);
        CoherentDataflowRuntime runtime = runtime(true, false, plan(p, a, b), fabric(10, 100, 100, 20, 10, 100));
        produce(runtime, 10, 1, 7, 0, 1); runtime.advance(11);
        runtime.requestJob(20, one(2), 99, 11); runtime.advance(16); finish(runtime, 20, 2, 99, 16, 17);
        runtime.requestJob(30, one(3), 42, 17); return runtime;
    }
}
