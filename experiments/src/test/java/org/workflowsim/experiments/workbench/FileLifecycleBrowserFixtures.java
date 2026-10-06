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
import org.workflowsim.data.v2.FileLifecycleCodec;
import org.workflowsim.experiment.ExperimentArtifactValidator;
import org.workflowsim.utils.Parameters.FileType;

/** Public V2 entry points and explicitly non-contextual, valid lifecycle display stress cases. */
final class FileLifecycleBrowserFixtures {
    private static final int BUDGET = 2000, COPY_PREVIEW_LIMIT = 64, LABEL_PREVIEW_LIMIT = 256;
    private static final String SHARED = "COHERENT_FILE_DATAFLOW_V2";
    private static final String ISOLATED = "COHERENT_FILE_DATAFLOW_NO_CONTENTION_V2";
    private static final String V1 = "PRE_EXECUTION_TRANSFER_DELAY_WITH_CONTENTION_V1";
    private static final String FULL_WORKFLOW = "<adag><job id=\"a\" runtime=\"1\">"
            + "<uses file=\"external-seed\" link=\"input\" size=\"0.5\"/>"
            + "<uses file=\"handoff\" link=\"output\" size=\"1000000\"/></job>"
            + "<job id=\"b\" runtime=\"1\"><uses file=\"handoff\" link=\"input\" size=\"1000000\"/></job>"
            + "<child ref=\"b\"><parent ref=\"a\"/></child></adag>";
    private static final String ZERO_WORKFLOW = "<adag><job id=\"no-input\" runtime=\"1\"/></adag>";

    private FileLifecycleBrowserFixtures() { }

    /** Append 21 new report specifications; never change the existing V1/P0 fixture entries. */
    static void append(Path output, List<Map<String, Object>> reports, Path offManifest) throws Exception {
        Path root = output.toAbsolutePath().normalize();
        Map<String, Bundle> bundles = new LinkedHashMap<>();
        for (boolean shared : new boolean[]{true, false}) {
            for (boolean fat : new boolean[]{false, true}) {
                for (boolean full : new boolean[]{true, false}) {
                    String name = "file-lifecycle-" + (shared ? "shared-" : "isolated-")
                            + (fat ? "fat-" : "endpoint-") + (full ? "full" : "zero");
                    Path experiment = run(root, name, shared ? SHARED : ISOLATED, fat, full, BUDGET);
                    Bundle bundle = new Bundle(manifest(experiment));
                    require(bundle.decoded != null && bundle.decoded.isQuiescent(), name + " needs a complete terminal certificate");
                    require(full ? bundle.decoded.getCopyCount() > 0 : bundle.decoded.getCopyCount() == 0,
                            name + " has the wrong positive-copy state");
                    bundles.put(name, bundle);
                    pair(root, reports, name, experiment, bundle);
                }
            }
        }

        String offName = "file-lifecycle-off";
        Path offExperiment = run(root, offName, SHARED, false, true, 0);
        Bundle off = new Bundle(manifest(offExperiment));
        require(off.decoded == null, "V2 OFF must not fabricate a lifecycle certificate");
        pair(root, reports, offName, offExperiment, off);
        insufficientCapture(root, reports);

        // This extra actual V1 bundle exists solely to exercise the mixed report, not to replace a P0 case.
        Path legacyExperiment = run(root, "file-lifecycle-mixed-v1-bundle", V1, false, true, BUDGET);
        Bundle legacy = new Bundle(manifest(legacyExperiment));
        Bundle full = bundles.get("file-lifecycle-shared-endpoint-full");
        Bundle zero = bundles.get("file-lifecycle-shared-endpoint-zero");
        switching(root, reports, full, legacy, off, zero);
        typedStress(root, reports, new Bundle(offManifest));
    }

    private static Path run(Path output, String name, String model, boolean fat, boolean full, int budget) throws Exception {
        Path input = output.resolve(name + ".dax"), configPath = output.resolve(name + ".json"), runRoot = output.resolve(name);
        if (Files.exists(input) || Files.exists(configPath) || Files.exists(runRoot)) {
            throw new IOException("Refusing existing lifecycle fixture inputs/output: " + name);
        }
        WorkbenchTestSupport.text(input, full ? FULL_WORKFLOW : ZERO_WORKFLOW);
        JsonObject config = WorkbenchTestSupport.localConfiguration(input.toString(), 2, fat);
        config.addProperty("name", "V2 lifecycle public browser fixture · " + name);
        WorkbenchTestSupport.algorithms(config, WorkbenchTestSupport.algorithm("random", "STATIC", "RANDOM"));
        JsonArray seeds = new JsonArray(); seeds.add(1); config.add("seeds", seeds);
        JsonObject simulation = config.getAsJsonObject("simulation");
        simulation.addProperty("dataMovementModel", model);
        if (budget > 0) {
            JsonObject recording = new JsonObject();
            recording.addProperty("mode", V1.equals(model) ? "FLUID_GROUP_LEDGER_V1" : "FILE_LIFECYCLE_V2");
            recording.addProperty("maxTraceRecords", budget);
            simulation.add("networkEvidence", recording);
        }
        return Workbench.run(WorkbenchTestSupport.json(configPath, config), runRoot);
    }

    private static void pair(Path output, List<Map<String, Object>> reports, String name, Path experiment, Bundle bundle) throws Exception {
        Map<String, Object> expected = expect(bundle.decoded, true);
        reports.add(spec(name, experiment.resolve("report.html"), Collections.singletonList(bundle.row("random")), Collections.singletonList(expected)));
        Path standalone = output.resolve(name + "-standalone.html");
        Workbench.main(new String[]{"report", bundle.path.toString(), standalone.toString()});
        JsonObject configuration = bundle.report.getManifest().getAsJsonObject("configuration");
        String planner = configuration.get("planningAlgorithm").getAsString();
        String candidate = "INVALID".equals(planner) ? configuration.get("schedulingAlgorithm").getAsString() : planner;
        reports.add(spec(name + "-standalone", standalone, Collections.singletonList(bundle.row(candidate)), Collections.singletonList(expected)));
    }

    private static void insufficientCapture(Path output, List<Map<String, Object>> reports) throws Exception {
        String name = "file-lifecycle-insufficient-capture";
        Path experiment = run(output, name, SHARED, false, true, 1);
        JsonObject displayed = WorkbenchTestSupport.payload(experiment.resolve("report.html"))
                .getAsJsonArray("runs").get(0).getAsJsonObject();
        require("FAILED".equals(displayed.get("status").getAsString()), "Budget-one lifecycle export must fail");
        require(!displayed.has("manifest") && !displayed.has("fileLifecycle") && !displayed.has("networkEvidence"),
                "Failed lifecycle export must not publish a verified manifest or either evidence view");
        JsonObject retained = WorkbenchTestSupport.object(experiment.resolve("experiment.json"))
                .getAsJsonArray("runs").get(0).getAsJsonObject();
        require(!retained.has("manifest"), "Failed lifecycle export must not advertise a verified manifest");
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("candidate", "random"); row.put("seed", "1"); row.put("status", "FAILED");
        reports.add(spec(name, experiment.resolve("report.html"), Collections.singletonList(row), Collections.singletonList(state("NO_RESULT"))));
    }

    private static void switching(Path output, List<Map<String, Object>> reports, Bundle full, Bundle legacy, Bundle off, Bundle zero) throws IOException {
        List<Map<String, Object>> rows = new ArrayList<>(), expected = new ArrayList<>();
        Map<String, Object> first = full.row("v2-full"); rows.add(first); expected.add(expect(full.decoded, true));
        Map<String, Object> old = legacy.row("v1-full");
        require(old.containsKey("networkEvidence") && !old.containsKey("fileLifecycle"), "Mixed V1 row must retain only its actual V1 evidence");
        rows.add(old); expected.add(state("HIDDEN"));
        rows.add(off.row("v2-off")); expected.add(state("OFF"));
        Map<String, Object> failed = new LinkedHashMap<>();
        failed.put("candidate", "failed-stale-v2"); failed.put("seed", "1"); failed.put("status", "FAILED");
        failed.put("error", "Deliberate no-validated-result control; stale lifecycle payload must remain hidden");
        failed.put("fileLifecycle", first.get("fileLifecycle"));
        rows.add(failed); expected.add(state("NO_RESULT"));
        rows.add(zero.row("v2-zero")); expected.add(expect(zero.decoded, true));
        rows.add(full.row("v2-full-again")); expected.add(expect(full.decoded, true));
        String name = "file-lifecycle-state-switch";
        Path target = output.resolve(name + ".html");
        HtmlReports.experiment("V2 / V1 / OFF / 无验证结果 / 零正字节拷贝 状态切换", rows, target);
        reports.add(spec(name, target, rows, expected));
    }

    private static void typedStress(Path output, List<Map<String, Object>> reports, Bundle cpu) throws IOException {
        List<Map<String, Object>> rows = new ArrayList<>(), expected = new ArrayList<>();
        CoherentDataflowRuntime tiny = runtime(plan(task(1, in("tiny", Double.MIN_VALUE))), Collections.singletonMap(7, 1.0));
        tiny.requestJob(11, one(1), 7, 0); tiny.advance(Double.MIN_VALUE);
        typed(rows, expected, cpu, "typed-v2-minimum-duration", decode(tiny));

        Map<Integer, Double> maximum = new LinkedHashMap<>(); maximum.put(7, Double.MAX_VALUE); maximum.put(42, Double.MAX_VALUE);
        CoherentDataflowRuntime huge = runtime(plan(task(1, in("maximum-left", Double.MAX_VALUE)), task(2, in("maximum-right", Double.MAX_VALUE))), maximum);
        huge.requestJob(11, one(1), 7, 0); huge.requestJob(22, one(2), 42, 0); huge.advance(1);
        finish(huge, 11, 1, 7, 1, 1); finish(huge, 22, 2, 42, 1, 1);
        typed(rows, expected, cpu, "typed-v2-beyond-double-sum", decode(huge));

        String hostile = "</script><img src=x onerror=alert(1)>节点\"'\u2028\u2029@@DATA@@";
        String longName = hostile + repeat('x', LABEL_PREVIEW_LIMIT - "input[0]/".length() - hostile.length() - 1) + "😀tail";
        List<Task> tasks = new ArrayList<>(); Map<Integer, Double> capacities = new LinkedHashMap<>();
        for (int i = 0; i < 65; i++) {
            tasks.add(task(i + 1, in(i == 0 ? hostile : longName + "-" + i, 1)));
            capacities.put(i, 1.0);
        }
        CoherentDataflowRuntime many = runtime(plan(tasks.toArray(new Task[0])), capacities);
        for (int i = 0; i < 65; i++) many.requestJob(i + 1, one(i + 1), i, 0);
        many.advance(1);
        typed(rows, expected, cpu, "typed-v2-hostile-capped", decode(many));

        Map<Integer, Double> resourceCapacities = new LinkedHashMap<>();
        for (int i = 0; i < 65; i++) resourceCapacities.put(i, i + 1.0);
        typed(rows, expected, cpu, "typed-v2-resource-cap", decode(runtime(plan(), resourceCapacities)));

        CoherentDataflowRuntime active = runtime(plan(task(1, in("active", 10)), task(2, in("unrequested", 20))), Collections.singletonMap(7, 10.0));
        active.requestJob(11, one(1), 7, .125);
        typed(rows, expected, cpu, "typed-v2-active-prefix", decode(active));
        typed(rows, expected, cpu, "typed-v2-cached-holder-origin", decode(cachedHolder()));

        String name = "file-lifecycle-typed-stress";
        Path target = output.resolve(name + ".html");
        HtmlReports.experiment("独立 V2 生命周期显示夹具 · 与所附 CPU 运行上下文不绑定 · 不是流体服务记账", rows, target);
        reports.add(spec(name, target, rows, expected));
    }

    private static void typed(List<Map<String, Object>> rows, List<Map<String, Object>> expected, Bundle cpu, String name, FileLifecycleCodec.Decoded decoded) {
        Map<String, Object> row = cpu.row(name);
        // The cached CPU manifest remains genuine. This standalone certificate makes no context claim.
        row.remove("networkEvidence");
        row.put("fileLifecycle", FileLifecycleReportView.fromDecoded(decoded, false));
        rows.add(row); expected.add(expect(decoded, false));
    }

    /** Independent certificate-derived expectations: never read the display projection as an oracle. */
    private static Map<String, Object> expect(FileLifecycleCodec.Decoded decoded, boolean contextValidated) {
        if (decoded == null) return state("OFF");
        JsonObject document = decoded.getDocument(), plan = document.getAsJsonObject("filePlan");
        Map<DataflowFilePlan.FileId, BigDecimal> sizes = new LinkedHashMap<>();
        for (JsonElement item : plan.getAsJsonArray("files")) {
            JsonObject file = item.getAsJsonObject();
            sizes.put(fileId(file.getAsJsonObject("fileId")), new BigDecimal(file.get("bytes").getAsDouble()));
        }
        Map<Long, BigDecimal> payloads = new LinkedHashMap<>();
        List<String> sources = new ArrayList<>();
        BigDecimal admitted = BigDecimal.ZERO, settled = BigDecimal.ZERO, residual = BigDecimal.ZERO;
        BigDecimal requiredBytes = BigDecimal.ZERO, localBytes = BigDecimal.ZERO;
        BigInteger references = BigInteger.ZERO, localReferences = BigInteger.ZERO, joinedReferences = BigInteger.ZERO;
        for (JsonElement item : document.getAsJsonArray("events")) {
            JsonObject event = item.getAsJsonObject(), payload = event.getAsJsonObject("payload");
            switch (event.get("type").getAsString()) {
                case "COPY_ADMITTED":
                    BigDecimal bytes = new BigDecimal(payload.get("bytes").getAsDouble());
                    payloads.put(payload.get("copyOrdinal").getAsLong(), bytes);
                    admitted = admitted.add(bytes);
                    if (sources.size() < COPY_PREVIEW_LIMIT) sources.add(sourceLabel(payload.getAsJsonObject("sourceReplica").getAsJsonObject("location")));
                    break;
                case "COPY_SETTLED":
                    settled = settled.add(payloads.get(payload.get("copyOrdinal").getAsLong()));
                    residual = residual.add(new BigDecimal(payload.get("remainingAfterService").getAsDouble()));
                    break;
                case "INPUT_RESOLVED":
                    BigInteger count = BigInteger.valueOf(payload.get("referenceCount").getAsLong());
                    BigDecimal amount = sizes.get(fileId(payload.getAsJsonObject("fileId"))).multiply(new BigDecimal(count));
                    references = references.add(count); requiredBytes = requiredBytes.add(amount);
                    if ("LOCAL".equals(payload.get("resolution").getAsString())) {
                        localReferences = localReferences.add(count); localBytes = localBytes.add(amount);
                    } else if ("JOIN_EXISTING".equals(payload.get("resolution").getAsString())) {
                        joinedReferences = joinedReferences.add(count);
                    }
                    break;
                default:
                    break;
            }
        }
        String files = Integer.toString(plan.getAsJsonArray("files").size()), tasks = Integer.toString(plan.getAsJsonArray("tasks").size());
        String copies = Long.toString(decoded.getCopyCount()), completed = Long.toString(decoded.getCompletedCopyCount()), active = Integer.toString(decoded.getActiveCopyCount());
        Map<String, Object> expected = state(decoded.getCopyCount() == 0 ? "ZERO" : "COMPLETE");
        expected.put("contextValidated", contextValidated);
        expected.put("copyCount", copies); expected.put("completedCopyCount", completed); expected.put("activeCopyCount", active);
        expected.put("fileCount", files); expected.put("taskCount", tasks); expected.put("copySources", sources);
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("file-lifecycle-plan-count", files + " / " + tasks);
        values.put("file-lifecycle-job-count", decoded.getRequestedJobCount() + " / " + decoded.getCompletedJobCount());
        values.put("file-lifecycle-copy-count", copies + " / " + completed + " / " + active);
        values.put("file-lifecycle-reference-count", references.toString() + " / " + localReferences + " / " + joinedReferences);
        values.put("file-lifecycle-reference-bytes", text(requiredBytes)); values.put("file-lifecycle-local-bytes", text(localBytes));
        values.put("file-lifecycle-admitted", text(admitted)); values.put("file-lifecycle-settled", text(settled));
        values.put("file-lifecycle-residual", text(residual));
        values.put("file-lifecycle-observed", new BigDecimal(document.getAsJsonObject("capture").get("observedThrough").getAsDouble()).toPlainString());
        expected.put("values", values);
        return expected;
    }

    private static String sourceLabel(JsonObject location) {
        return "VM".equals(location.get("kind").getAsString())
                ? "VM:" + location.get("vmId").getAsBigDecimal().toBigIntegerExact().toString()
                : "SOURCE:" + location.get("sourceId").getAsString();
    }
    private static DataflowFilePlan.FileId fileId(JsonObject id) {
        return DataflowFilePlan.FileId.of(id.get("workflowInputIndex").getAsInt(), id.get("name").getAsString());
    }
    private static String text(BigDecimal value) { return value.stripTrailingZeros().toPlainString(); }
    private static Map<String, Object> state(String state) {
        Map<String, Object> value = new LinkedHashMap<>(); value.put("state", state); return value;
    }
    private static Map<String, Object> spec(String name, Path path, List<Map<String, Object>> rows, List<Map<String, Object>> lifecycle) {
        List<String> candidates = new ArrayList<>(), seeds = new ArrayList<>(), statuses = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            candidates.add((String) row.get("candidate")); seeds.add((String) row.get("seed")); statuses.add((String) row.get("status"));
        }
        Map<String, Object> spec = new LinkedHashMap<>();
        spec.put("name", name); spec.put("path", path.toString()); spec.put("candidates", candidates); spec.put("seeds", seeds); spec.put("statuses", statuses);
        spec.put("fileLifecycle", lifecycle); // No V1 network expectations on the new reports.
        return spec;
    }
    private static Path manifest(Path experiment) { return experiment.resolve("runs/random-s1/result.manifest.json"); }
    private static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }

    /** Keep the exact validated manifest/view snapshot attached, rather than reconstructing CPU evidence. */
    private static final class Bundle {
        final Path path;
        final HtmlReports.ValidatedReport report;
        final FileLifecycleCodec.Decoded decoded;
        Bundle(Path path) throws IOException {
            this.path = path;
            report = HtmlReports.validatedReport(path);
            decoded = ExperimentArtifactValidator.validate(path).getDecodedFileLifecycle();
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

    private static CoherentDataflowRuntime runtime(DataflowFilePlan plan, Map<Integer, Double> capacities) {
        DataTransferFabric fabric = DataTransferFabric.endpoints(capacities, Collections.singletonList("source"));
        CoherentDataflowRuntime runtime = new CoherentDataflowRuntime(true, BUDGET);
        runtime.initializePlan(plan, fabric.getLocations()); runtime.bindFabric(fabric); return runtime;
    }
    private static FileLifecycleCodec.Decoded decode(CoherentDataflowRuntime runtime) {
        return FileLifecycleCodec.decode(FileLifecycleCodec.encode(runtime.captureEvidence()));
    }
    private static DataflowFilePlan plan(Task... tasks) {
        Map<Integer, Integer> scopes = new LinkedHashMap<>();
        for (Task task : tasks) scopes.put(task.getCloudletId(), 0);
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
    private static void finish(CoherentDataflowRuntime runtime, int job, int task, int vm, double start, double end) {
        runtime.drainReadyJobIds(); runtime.cpuStarted(job, one(task), vm, start);
        runtime.jobFinished(job, one(task), vm, Collections.singletonList(true), end);
    }
    private static String repeat(char character, int count) {
        char[] chars = new char[count]; Arrays.fill(chars, character); return new String(chars);
    }
    private static CoherentDataflowRuntime cachedHolder() {
        Task producer = task(1, out("fast", 10), out("slow", 100));
        Task both = task(2, in("fast", 10), in("fast", 10), in("slow", 100));
        Task other = task(3, in("fast", 10)), join = task(4, in("fast", 10));
        edge(producer, both); edge(producer, other); edge(producer, join);
        Map<Integer, Double> capacities = new LinkedHashMap<>(); capacities.put(7, 10.0); capacities.put(42, 100.0); capacities.put(99, 100.0);
        CoherentDataflowRuntime runtime = runtime(plan(producer, both, other, join), capacities);
        runtime.requestJob(10, one(1), 7, 0); finish(runtime, 10, 1, 7, 0, 1);
        runtime.requestJob(20, one(2), 99, 1); runtime.requestJob(40, one(4), 99, 1.5);
        runtime.advance(3); finish(runtime, 40, 4, 99, 3, 3); runtime.requestJob(30, one(3), 42, 3);
        runtime.advance(4); finish(runtime, 30, 3, 42, 4, 4); runtime.advance(12); finish(runtime, 20, 2, 99, 12, 13);
        return runtime;
    }
}
