package org.workflowsim.experiments.workbench;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.cloudbus.cloudsim.Log;
import org.workflowsim.experiment.ExperimentArtifactValidator;
import org.workflowsim.experiment.ExperimentArtifactWriter;
import org.workflowsim.experiment.SimulationReport;
import org.workflowsim.experiment.SimulationRunner;
import org.workflowsim.failure.FailureModelConfig;
import org.workflowsim.failure.FailureParameters;
import org.workflowsim.utils.DistributionGenerator;
import org.workflowsim.utils.DistributionSpec;

/** Genuine, context-validated online-assignment bundles through the shared public report seam. */
final class DataflowAssignmentBrowserFixtures {
    private static final int BUDGET = 20000, ACTION_LIMIT = 64, CANDIDATE_LIMIT = 12;
    private static final int SPEC_COUNT = 34, STRESS_ROW_COUNT = 3;
    private static final String MODE = "CONTROL_READY_ONLINE_ASSIGNMENT_V1";
    private static final String POLICY = "NOMINAL_INPUT_EARLIEST_RESERVATION_V1";
    private static final String SCOPE = "BINDING_NOMINAL_BOUNDS_AND_LOGGED_SCORE_V1";
    private static final String V2_SHARED = "COHERENT_FILE_DATAFLOW_V2";
    private static final String V2_ISOLATED = "COHERENT_FILE_DATAFLOW_NO_CONTENTION_V2";
    private static final String V3_SHARED = "COHERENT_STORAGE_DATAFLOW_V3";
    private static final String V3_ISOLATED = "COHERENT_STORAGE_DATAFLOW_NO_CONTENTION_V3";
    private static final String FULL_WORKFLOW = "<adag><job id=\"p\" runtime=\"1\">"
            + "<uses file=\"external-seed\" link=\"input\" size=\"0.5\"/>"
            + "<uses file=\"x\" link=\"output\" size=\"1000000\"/>"
            + "<uses file=\"unused-output\" link=\"output\" size=\"500000\"/>"
            + "<uses file=\"zero\" link=\"output\" size=\"0\"/></job>"
            + "<job id=\"c\" runtime=\"1\"><uses file=\"x\" link=\"input\" size=\"1000000\"/>"
            + "<uses file=\"x\" link=\"input\" size=\"1000000\"/>"
            + "<uses file=\"zero\" link=\"input\" size=\"0\"/></job>"
            + "<child ref=\"c\"><parent ref=\"p\"/></child></adag>";
    private static final String ZERO_WORKFLOW = "<adag><job id=\"zero-output\" runtime=\"1\">"
            + "<uses file=\"unused-zero\" link=\"output\" size=\"0\"/></job></adag>";
    private static final String FILELESS_WORKFLOW = "<adag><job id=\"fileless\" runtime=\"1\"/></adag>";
    private static final String FRACTIONAL_WORKFLOW = "<adag><job id=\"tiny-fractional\" runtime=\"1\">"
            + "<uses file=\"tiny-external\" link=\"input\" size=\"0.5\"/>"
            + "<uses file=\"full-zero-output\" link=\"output\" size=\"0\"/></job></adag>";
    private static final String JOINED_WORKFLOW = "<adag><job id=\"pRoot\" runtime=\"1\">"
            + "<uses file=\"same-scoped-external\" link=\"input\" size=\"10000000\"/></job>"
            + "<job id=\"qRoot\" runtime=\"0.2\"/>"
            + "<job id=\"cChild\" runtime=\"1\">"
            + "<uses file=\"same-scoped-external\" link=\"input\" size=\"10000000\"/></job>"
            + "<child ref=\"cChild\"><parent ref=\"qRoot\"/></child></adag>";

    private DataflowAssignmentBrowserFixtures() { }

    /** Append 34 new specifications; leave every preceding fixture and its evidence untouched. */
    static void append(Path output, List<Map<String, Object>> reports, Path offManifest) throws Exception {
        Path root = output.toAbsolutePath().normalize();
        int initialCount = reports.size();
        Map<String, Bundle> physical = new LinkedHashMap<>();
        for (boolean storage : new boolean[]{false, true}) {
            for (boolean storeInputs : new boolean[]{false, true}) {
                if (!storage && storeInputs) continue; // V2 has LOCAL access only.
                for (boolean shared : new boolean[]{true, false}) {
                    for (boolean fat : new boolean[]{false, true}) {
                        String name = "dataflow-assignment-" + (storage ? "v3-" : "v2-")
                                + (storeInputs ? "shared-input-" : "local-")
                                + (shared ? "shared-" : "isolated-") + (fat ? "fat-" : "endpoint-") + "full";
                        Path experiment = run(root, name, FULL_WORKFLOW, storage, storeInputs, shared, fat, 2, BUDGET, true);
                        Bundle bundle = onlineBundle(manifest(experiment, "online"));
                        require(bundle.assignments.size() == 2, name + " must bind both real compute Tasks");
                        require(hasEvent(bundle.lifecycle, "COPY_ADMITTED"), name + " needs actual positive input copies");
                        assertObservationModes(bundle, storage, storeInputs, shared);
                        physical.put(name, bundle);
                        pair(root, reports, name, experiment.resolve("report.html"), bundle);
                    }
                }
            }
        }
        require(physical.size() == 12, "Online fixture matrix must cover exactly twelve supported combinations");

        String zeroName = "dataflow-assignment-zero-output-only";
        Path zeroExperiment = run(root, zeroName, ZERO_WORKFLOW, true, true, true, false, 2, BUDGET, true);
        Bundle zero = onlineBundle(manifest(zeroExperiment, "online"));
        require(zero.assignments.size() == 1 && !hasEvent(zero.lifecycle, "COPY_ADMITTED")
                && hasZeroOutput(zero.lifecycle), "Zero-output fixture needs a real binding and successful zero publication");
        pair(root, reports, zeroName, zeroExperiment.resolve("report.html"), zero);

        String filelessName = "dataflow-assignment-fileless";
        Path filelessExperiment = run(root, filelessName, FILELESS_WORKFLOW, false, false, true, false, 2, BUDGET, true);
        Bundle fileless = onlineBundle(manifest(filelessExperiment, "online"));
        require(fileless.assignments.size() == 1
                && fileless.lifecycle.getAsJsonObject("filePlan").getAsJsonArray("files").size() == 0
                && !hasEvent(fileless.lifecycle, "COPY_ADMITTED"), "Fileless fixture must still have genuine online evidence");
        pair(root, reports, filelessName, filelessExperiment.resolve("report.html"), fileless);

        retry(root, reports);
        typedStress(root, reports);
        Bundle v3 = physical.get("dataflow-assignment-v3-shared-input-shared-endpoint-full");
        Bundle v2 = physical.get("dataflow-assignment-v2-local-shared-endpoint-full");
        Path preplannedExperiment = run(root, "dataflow-assignment-switch-random-bundle", FULL_WORKFLOW,
                true, true, true, false, 2, BUDGET, false);
        Bundle preplanned = new Bundle(manifest(preplannedExperiment, "random"));
        switching(root, reports, v3, preplanned, v2, new Bundle(offManifest));
        missingProjection(root, reports, v3);
        insufficientCapture(root, reports);

        int appendedCount = reports.size() - initialCount;
        require(appendedCount == SPEC_COUNT, "Expected " + SPEC_COUNT + " new online specs, actually appended " + appendedCount);
        List<String> names = new ArrayList<>();
        Set<String> unique = new HashSet<>();
        for (Map<String, Object> report : reports) require(unique.add((String) report.get("name")), "Duplicate browser fixture name");
        for (int i = initialCount; i < reports.size(); i++) {
            Map<String, Object> spec = reports.get(i);
            require(!spec.containsKey("network") && !spec.containsKey("fileLifecycle") && !spec.containsKey("storageLifecycle"),
                    "New action specs must not replace the existing physical-pane expectations");
            names.add((String) spec.get("name"));
        }
        System.out.println("DATAFLOW_ASSIGNMENT_BROWSER_FIXTURES appended=" + appendedCount
                + " total=" + reports.size() + " stressRows=" + STRESS_ROW_COUNT + " names=" + names);
    }

    private static Path run(Path output, String name, String workflow, boolean storage, boolean storeInputs,
            boolean shared, boolean fat, int vmCount, int budget, boolean online) throws Exception {
        Path input = output.resolve(name + ".dax"), configPath = output.resolve(name + ".json"), runRoot = output.resolve(name);
        requireNew(input, configPath, runRoot);
        WorkbenchTestSupport.text(input, workflow);
        JsonObject config = configuration(input, name, storage, storeInputs, shared, fat, vmCount, budget, online);
        return Workbench.run(WorkbenchTestSupport.json(configPath, config), runRoot);
    }

    private static JsonObject configuration(Path input, String name, boolean storage, boolean storeInputs,
            boolean shared, boolean fat, int vmCount, int budget, boolean online) {
        require(storage || !storeInputs, "V2 online fixtures require LOCAL file access");
        JsonObject config = WorkbenchTestSupport.localConfiguration(input.toString(), vmCount, fat);
        config.addProperty("name", "Online assignment browser fixture · " + name);
        JsonObject algorithm;
        if (online) {
            // Endpoint cases also exercise the public omitted-scheduler default; FAT cases spell it out.
            algorithm = WorkbenchTestSupport.algorithm("online", fat ? "STATIC" : null, "INVALID");
            JsonObject assignment = new JsonObject();
            assignment.addProperty("mode", MODE); assignment.addProperty("policy", POLICY);
            algorithm.add("dataflowAssignment", assignment);
        } else {
            algorithm = WorkbenchTestSupport.algorithm("random", "STATIC", "RANDOM");
        }
        WorkbenchTestSupport.algorithms(config, algorithm);
        JsonArray seeds = new JsonArray(); seeds.add(1L); config.add("seeds", seeds);
        JsonObject platform = config.getAsJsonObject("platform");
        JsonArray mips = new JsonArray();
        for (int i = 0; i < vmCount; i++) mips.add(1000 * (i + 1));
        platform.add("vmMips", mips); platform.addProperty("bandwidthMbPerSecond", 2);
        if (storage) {
            JsonObject source = new JsonObject();
            source.addProperty("attachmentHostId", 0);
            source.addProperty("readBandwidthMbPerSecond", 1);
            source.addProperty("writeBandwidthMbPerSecond", .5);
            source.addProperty("networkBandwidthMbPerSecond", 2);
            platform.add("sourceStorage", source);
        }
        JsonObject simulation = config.getAsJsonObject("simulation");
        simulation.addProperty("fileSystem", storeInputs ? "SHARED" : "LOCAL");
        simulation.addProperty("dataMovementModel", storage ? (shared ? V3_SHARED : V3_ISOLATED) : (shared ? V2_SHARED : V2_ISOLATED));
        JsonObject recording = new JsonObject();
        recording.addProperty("mode", storage ? "FILE_STORAGE_LIFECYCLE_V3" : "FILE_LIFECYCLE_V2");
        recording.addProperty("maxTraceRecords", budget);
        simulation.add("networkEvidence", recording);
        return config;
    }

    private static void pair(Path output, List<Map<String, Object>> reports, String name, Path normal, Bundle bundle) throws Exception {
        Map<String, Object> expected = expect(bundle.assignments);
        reports.add(spec(name, normal, Collections.singletonList(bundle.row("online")), Collections.singletonList(expected)));
        Path standalone = output.resolve(name + "-standalone.html"); requireNew(standalone);
        Workbench.main(new String[]{"report", bundle.path.toString(), standalone.toString()});
        JsonObject config = bundle.report.getManifest().getAsJsonObject("configuration");
        String planner = config.get("planningAlgorithm").getAsString();
        String candidate = config.has("dataflowAssignment")?"DATAFLOW_BINDING_V1":"INVALID".equals(planner) ? config.get("schedulingAlgorithm").getAsString() : planner;
        reports.add(spec(name + "-standalone", standalone, Collections.singletonList(bundle.row(candidate)), Collections.singletonList(expected)));
    }

    /** Failure configuration is a Java Runner capability, not a fabricated public Workbench option. */
    private static void retry(Path output, List<Map<String, Object>> reports) throws Exception {
        String name = "dataflow-assignment-noop-retry";
        Path input = output.resolve(name + ".dax"), configPath = output.resolve(name + "-base.json");
        Path bundleRoot = output.resolve(name + "-bundle"), normal = output.resolve(name + ".html");
        requireNew(input, configPath, bundleRoot, normal);
        WorkbenchTestSupport.text(input, ZERO_WORKFLOW);
        JsonObject base = configuration(input, name, true, true, true, false, 2, BUDGET, true);
        JsonArray mips = new JsonArray(); mips.add(1000); mips.add(1000);
        base.getAsJsonObject("platform").add("vmMips", mips);
        base.getAsJsonObject("platform").addProperty("bandwidthMbPerSecond", 1);
        WorkbenchConfig parsed = WorkbenchConfig.read(WorkbenchTestSupport.json(configPath, base));
        parsed.validateInputs();
        FailureModelConfig failure = FailureModelConfig.builder()
                .clusteringAlgorithm(FailureParameters.FTCluteringAlgorithm.FTCLUSTERING_NOOP)
                .monitorMode(FailureParameters.FTCMonitor.MONITOR_NONE)
                .generatorMode(FailureParameters.FTCFailure.FAILURE_ALL)
                .generatorSpecs(new DistributionSpec[][]{{DistributionSpec.of(DistributionGenerator.DistributionFamily.WEIBULL, 2, 1)}})
                .maxTotalRetryJobs(64).build();
        SimulationReport retried = null;
        boolean logging = Log.isDisabled(); Log.disable();
        try {
            // The existing kernel retry corpus uses this bounded, deterministic seed domain.
            // Save only an actual successful run with a real retry, never an invented retry event.
            for (long seed = 1; seed <= 32; seed++) {
                SimulationReport candidate = new SimulationRunner().run(parsed.getCandidates().get(0).config.toBuilder()
                        .failureModel(failure).randomSeed(seed).build(), parsed.getPlatform());
                if (candidate.isWorkflowCompletedSuccessfully() && candidate.getMetrics().getRetryJobCreatedCount() > 0) {
                    retried = candidate; break;
                }
            }
        } finally { Log.setDisabled(logging); }
        require(retried != null, "Fixed seed domain did not produce a successful true NOOP retry fixture");
        Bundle bundle = onlineBundle(ExperimentArtifactWriter.write(retried, bundleRoot, "result").getManifest());
        require(bundle.assignments.size() > 1, "Retry bundle must contain more than its initial binding");
        JsonObject initial = bundle.assignments.get(0).getAsJsonObject();
        require("INITIAL".equals(initial.getAsJsonObject("attributes").get("binding").getAsString()), "Retry needs an initial anchor");
        for (int i = 1; i < bundle.assignments.size(); i++) {
            JsonObject main = bundle.assignments.get(i).getAsJsonObject(), attrs = main.getAsJsonObject("attributes");
            require("RETRY_REUSE".equals(attrs.get("binding").getAsString())
                    && attrs.getAsJsonArray("candidates").size() == 1
                    && number(main.get("vmId")).equals(number(initial.get("vmId")))
                    && main.getAsJsonArray("taskIds").equals(initial.getAsJsonArray("taskIds")),
                    "NOOP retry must reuse the actual logical Task binding and singleton VM domain");
        }
        HtmlReports.experiment("Actual Java Runner NOOP retry · validated online assignments", Collections.singletonList(bundle.row("online")), normal);
        pair(output, reports, name, normal, bundle);
    }

    /** All three rows retain their own real CPU, graph, file plan, lifecycle and checked actions. */
    private static void typedStress(Path output, List<Map<String, Object>> reports) throws Exception {
        List<Map<String, Object>> rows = new ArrayList<>(), expected = new ArrayList<>();
        Bundle fractional = onlineBundle(manifest(run(output, "dataflow-assignment-typed-fractional-bundle", FRACTIONAL_WORKFLOW,
                true, true, true, false, 2, BUDGET, true), "online"));
        JsonObject tinySelected = selected(fractional.assignments.get(0).getAsJsonObject());
        BigDecimal tiny = exact(tinySelected.get("inputSeconds"));
        require(fractional.assignments.size() == 1 && tiny.signum() > 0 && tiny.compareTo(BigDecimal.ONE) < 0
                && hasFileBytes(fractional.lifecycle, "0.5") && hasZeroOutput(fractional.lifecycle),
                "Fractional stress must use a genuine 0.5-byte external input and successful full zero output");
        add(rows, expected, fractional, "typed-online-fractional-zero-output");

        StringBuilder noFiles = new StringBuilder("<adag>");
        for (int i = 0; i < 65; i++) noFiles.append("<job id=\"cap-").append(i).append("\" runtime=\"1\"/>");
        noFiles.append("</adag>");
        Bundle capped = onlineBundle(manifest(run(output, "dataflow-assignment-typed-capped-bundle", noFiles.toString(),
                false, false, true, false, 13, BUDGET, true), "online"));
        JsonArray vms = capped.report.getManifest().getAsJsonObject("platform").getAsJsonArray("vms");
        require(capped.assignments.size() == 65 && vms.size() == 13
                && capped.lifecycle.getAsJsonObject("filePlan").getAsJsonArray("files").size() == 0,
                "Action/candidate cap fixture needs 65 real fileless Tasks and 13 real VMs");
        for (int i = 0; i < vms.size(); i++) {
            JsonObject vm = vms.get(i).getAsJsonObject();
            require(Integer.toString(i).equals(number(vm.get("id")))
                    && Integer.toString(1000 * (i + 1)).equals(number(vm.get("mips"))), "Cap VM capacities must remain strictly increasing");
        }
        JsonObject first = capped.assignments.get(0).getAsJsonObject();
        require("12".equals(number(first.get("vmId"))), "First cap action must select the thirteenth VM");
        for (JsonElement item : capped.assignments) require(item.getAsJsonObject().getAsJsonObject("attributes").getAsJsonArray("candidates").size() == 13,
                "Every initial cap action must score the full thirteen-VM domain");
        JsonArray candidates = first.getAsJsonObject("attributes").getAsJsonArray("candidates");
        for (int i = 0; i < CANDIDATE_LIMIT; i++) require(!"12".equals(number(candidates.get(i).getAsJsonObject().get("vmId"))),
                "The first selected candidate must lie beyond the candidate preview cap");
        add(rows, expected, capped, "typed-online-action-candidate-caps");

        Bundle joined = onlineBundle(manifest(run(output, "dataflow-assignment-typed-joined-bundle", JOINED_WORKFLOW,
                false, false, true, false, 2, BUDGET, true), "online"));
        require(joined.assignments.size() == 3
                && joined.lifecycle.getAsJsonObject("filePlan").getAsJsonArray("files").size() == 1
                && hasFileBytes(joined.lifecycle, "10000000"), "Joined input fixture must share one scoped external 10 MB file");
        int joinedActions = 0;
        for (JsonElement item : joined.assignments) {
            JsonObject main = item.getAsJsonObject(), chosen = selected(main);
            if (exact(chosen.get("joinedInputCopies")).signum() > 0) {
                joinedActions++;
                JsonObject observation = main.getAsJsonObject("attributes").getAsJsonObject("observation");
                require("3".equals(number(main.getAsJsonArray("taskIds").get(0)))
                        && "1".equals(number(chosen.get("joinedInputCopies")))
                        && exact(chosen.get("inputSeconds")).signum() > 0
                        && exact(chosen.get("inputSeconds")).compareTo(new BigDecimal("5")) < 0
                        && exact(observation.get("activeCopyCount")).signum() > 0,
                        "cChild must genuinely join pRoot's still-active input after qRoot finishes");
            }
        }
        require(joinedActions == 1, "Mid-copy stress must actually exercise a selected joined input copy");
        add(rows, expected, joined, "typed-online-mid-copy-joined-input");
        require(rows.size() == STRESS_ROW_COUNT, "Typed online stress must have exactly three genuine contexts");
        String name = "dataflow-assignment-typed-stress";
        Path target = output.resolve(name + ".html"); requireNew(target);
        HtmlReports.experiment("Genuine online assignment contexts · fractional / preview caps / mid-copy join", rows, target);
        reports.add(spec(name, target, rows, expected));
    }

    private static void switching(Path output, List<Map<String, Object>> reports, Bundle v3, Bundle preplanned,
            Bundle v2, Bundle legacy) throws IOException {
        require(preplanned.assignments == null && legacy.assignments == null, "Preplanned and legacy controls must have assignment disabled");
        require("RANDOM".equals(preplanned.report.getManifest().getAsJsonObject("configuration").get("planningAlgorithm").getAsString()),
                "V3 preplanned control must be a real RANDOM run");
        List<Map<String, Object>> rows = new ArrayList<>(), expected = new ArrayList<>();
        add(rows, expected, v3, "v3-online");
        Map<String, Object> random = preplanned.row("v3-random-preplanned");
        require(random.containsKey("storageLifecycle") && !random.containsKey("dataflowAssignment"), "RANDOM control must retain its real V3 pane");
        rows.add(random); expected.add(state("HIDDEN"));
        add(rows, expected, v2, "v2-online");
        rows.add(legacy.row("legacy-off")); expected.add(state("HIDDEN"));
        Map<String, Object> failed = new LinkedHashMap<>();
        failed.put("candidate", "failed-stale-action"); failed.put("seed", "1"); failed.put("status", "FAILED");
        failed.put("error", "Deliberate no-manifest control: stale assignment view must be hidden and cleared");
        failed.put("dataflowAssignment", rows.get(0).get("dataflowAssignment"));
        rows.add(failed); expected.add(state("NO_RESULT"));
        add(rows, expected, v3, "v3-online-again");
        require(rows.size() == 6, "Action state switching must retain its six ordered states");
        String name = "dataflow-assignment-state-switch";
        Path target = output.resolve(name + ".html"); requireNew(target);
        HtmlReports.experiment("V3 online / V3 preplanned / V2 online / legacy / failed / V3 online", rows, target);
        reports.add(spec(name, target, rows, expected));
    }

    private static void missingProjection(Path output, List<Map<String, Object>> reports, Bundle online) throws IOException {
        Map<String, Object> row = online.row("online");
        require(row.remove("dataflowAssignment") != null && row.containsKey("storageLifecycle")
                && online.report.getManifest().getAsJsonObject("configuration").has("dataflowAssignment"),
                "Missing projection control must keep its valid ON manifest and actual physical lifecycle");
        String name = "dataflow-assignment-missing-action-projection";
        Path target = output.resolve(name + ".html"); requireNew(target);
        HtmlReports.experiment("Valid ON manifest with deliberately missing action projection · INVALID, not OFF",
                Collections.singletonList(row), target);
        reports.add(spec(name, target, Collections.singletonList(row), Collections.singletonList(state("INVALID"))));
    }

    private static void insufficientCapture(Path output, List<Map<String, Object>> reports) throws Exception {
        String name = "dataflow-assignment-insufficient-capture";
        Path experiment = run(output, name, FULL_WORKFLOW, true, true, true, false, 2, 1, true);
        JsonObject displayed = WorkbenchTestSupport.payload(experiment.resolve("report.html")).getAsJsonArray("runs").get(0).getAsJsonObject();
        require("FAILED".equals(displayed.get("status").getAsString()), "Budget-one online export must fail through public Workbench");
        for (String field : Arrays.asList("manifest", "dataflowAssignment", "storageLifecycle", "fileLifecycle", "networkEvidence")) {
            require(!displayed.has(field), "Failed online export must not publish " + field);
        }
        JsonObject retained = WorkbenchTestSupport.object(experiment.resolve("experiment.json")).getAsJsonArray("runs").get(0).getAsJsonObject();
        require("FAILED".equals(retained.get("status").getAsString()) && !retained.has("manifest")
                && !Files.exists(manifest(experiment, "online")), "Insufficient capture must not advertise or write a verified manifest");
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("candidate", "online"); row.put("seed", "1"); row.put("status", "FAILED");
        reports.add(spec(name, experiment.resolve("report.html"), Collections.singletonList(row), Collections.singletonList(state("NO_RESULT"))));
    }

    private static void add(List<Map<String, Object>> rows, List<Map<String, Object>> expected, Bundle bundle, String candidate) {
        rows.add(bundle.row(candidate)); expected.add(expect(bundle.assignments));
    }

    /** Independent oracle over full checked main records; never read the bounded display projection. */
    private static Map<String, Object> expect(JsonArray assignments) {
        if (assignments == null) return state("HIDDEN");
        int initial = 0, retries = 0, conditional = 0;
        BigDecimal joined = BigDecimal.ZERO;
        List<String> selectedVms = new ArrayList<>();
        List<Map<String, Object>> rows = new ArrayList<>();
        for (JsonElement item : assignments) {
            JsonObject main = item.getAsJsonObject(), attrs = main.getAsJsonObject("attributes"), chosen = selected(main);
            String binding = attrs.get("binding").getAsString();
            if ("INITIAL".equals(binding)) initial++;
            else { require("RETRY_REUSE".equals(binding), "Unexpected checked binding kind"); retries++; }
            if (chosen.get("conditionalStoreWait").getAsBoolean()) conditional++;
            joined = joined.add(exact(chosen.get("joinedInputCopies")));
            if (rows.size() < ACTION_LIMIT) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("sequence", number(attrs.get("assignmentSequence")));
                row.put("mainSequence", number(main.get("sequence")));
                row.put("jobId", number(main.get("jobId")));
                row.put("taskId", number(main.getAsJsonArray("taskIds").get(0)));
                row.put("vmId", number(main.get("vmId")));
                row.put("time", number(main.get("simulationTime")));
                row.put("binding", binding);
                row.put("selectedInputSeconds", number(chosen.get("inputSeconds")));
                row.put("selectedCpuAvailableAt", number(chosen.get("cpuAvailableAt")));
                row.put("selectedComputeSeconds", number(chosen.get("computeSeconds")));
                row.put("selectedScoreFinishSeconds", number(chosen.get("scoreFinishSeconds")));
                row.put("conditionalStoreWait", chosen.get("conditionalStoreWait").getAsBoolean());
                row.put("joinedInputCopies", number(chosen.get("joinedInputCopies")));
                row.put("candidateCount", Integer.toString(attrs.getAsJsonArray("candidates").size()));
                selectedVms.add(number(main.get("vmId"))); rows.add(row);
            }
        }
        Map<String, Object> expected = state("VALIDATED");
        expected.put("values", Arrays.asList(Integer.toString(assignments.size()), Integer.toString(initial),
                Integer.toString(retries), Integer.toString(conditional), decimal(joined)));
        expected.put("actionCount", Integer.toString(assignments.size()));
        expected.put("selectedVms", selectedVms); expected.put("rows", rows);
        JsonObject first = assignments.size() == 0 ? null : assignments.get(0).getAsJsonObject().getAsJsonObject("attributes");
        expected.put("policy", first == null ? POLICY : first.get("assignmentPolicy").getAsString());
        expected.put("auditScope", first == null ? SCOPE : first.get("auditScope").getAsString());
        return expected;
    }

    private static JsonObject selected(JsonObject main) {
        JsonObject attrs = main.getAsJsonObject("attributes");
        BigDecimal selectedVm = exact(attrs.get("selectedVmId"));
        // Search the complete raw domain, including the winner beyond the first twelve candidates.
        for (JsonElement item : attrs.getAsJsonArray("candidates")) {
            JsonObject candidate = item.getAsJsonObject();
            if (exact(candidate.get("vmId")).compareTo(selectedVm) == 0) return candidate;
        }
        throw new AssertionError("Checked raw action has no selected candidate");
    }

    private static void assertObservationModes(Bundle bundle, boolean storage, boolean storeInputs, boolean shared) {
        for (JsonElement item : bundle.assignments) {
            JsonObject observation = item.getAsJsonObject().getAsJsonObject("attributes").getAsJsonObject("observation");
            require(observation.get("storageVersion").getAsBoolean() == storage
                    && observation.get("storeBackedInputs").getAsBoolean() == storeInputs
                    && observation.get("interFlowSharing").getAsBoolean() == shared,
                    "Raw assignment observation must retain physical/access/sharing distinctions");
        }
    }

    private static Bundle onlineBundle(Path path) throws IOException {
        Bundle bundle = new Bundle(path);
        JsonObject manifest = bundle.report.getManifest(), config = manifest.getAsJsonObject("configuration");
        require(bundle.assignments != null && bundle.lifecycle != null, "Online bundle needs checked actions and matching physical evidence: " + path);
        require("INVALID".equals(config.get("planningAlgorithm").getAsString())
                && "STATIC".equals(config.get("schedulingAlgorithm").getAsString())
                && config.getAsJsonObject("dataflowAssignment").size() == 2
                && MODE.equals(config.getAsJsonObject("dataflowAssignment").get("mode").getAsString())
                && POLICY.equals(config.getAsJsonObject("dataflowAssignment").get("policy").getAsString()),
                "Online bundle must retain its exact public opt-in and distinct binding track");
        require(Integer.toString(BUDGET).equals(number(config.getAsJsonObject("networkEvidence").get("maxTraceRecords")))
                && "COMPLETE".equals(bundle.lifecycle.getAsJsonObject("capture").get("status").getAsString())
                && manifest.getAsJsonArray("dataflowComputeRequests").size() == bundle.assignments.size(),
                "Online exports need complete budget-20000 capture and real compute-request coverage");
        return bundle;
    }

    private static boolean hasEvent(JsonObject lifecycle, String type) {
        for (JsonElement item : lifecycle.getAsJsonArray("events")) if (type.equals(item.getAsJsonObject().get("type").getAsString())) return true;
        return false;
    }
    private static boolean hasZeroOutput(JsonObject lifecycle) {
        for (JsonElement item : lifecycle.getAsJsonArray("events")) {
            JsonObject event = item.getAsJsonObject();
            if ("OUTPUT_RESOLVED".equals(event.get("type").getAsString())
                    && "ZERO".equals(event.getAsJsonObject("payload").get("resolution").getAsString())) return true;
        }
        return false;
    }
    private static boolean hasFileBytes(JsonObject lifecycle, String bytes) {
        for (JsonElement item : lifecycle.getAsJsonObject("filePlan").getAsJsonArray("files")) {
            if (exact(item.getAsJsonObject().get("bytes")).compareTo(new BigDecimal(bytes)) == 0) return true;
        }
        return false;
    }
    private static BigDecimal exact(JsonElement value) { return new BigDecimal(value.getAsString()); }
    private static String decimal(BigDecimal value) { return value.stripTrailingZeros().toPlainString(); }
    private static String number(JsonElement value) { return value == null || value.isJsonNull() ? null : decimal(exact(value)); }
    private static Map<String, Object> state(String value) {
        Map<String, Object> result = new LinkedHashMap<>(); result.put("state", value); return result;
    }
    private static Map<String, Object> spec(String name, Path path, List<Map<String, Object>> rows, List<Map<String, Object>> assignment) {
        require(rows.size() == assignment.size(), "One action expectation is required per report row");
        List<String> candidates = new ArrayList<>(), seeds = new ArrayList<>(), statuses = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            candidates.add((String) row.get("candidate")); seeds.add((String) row.get("seed")); statuses.add((String) row.get("status"));
        }
        Map<String, Object> spec = new LinkedHashMap<>();
        spec.put("name", name); spec.put("path", path.toAbsolutePath().normalize().toString());
        spec.put("candidates", candidates); spec.put("seeds", seeds); spec.put("statuses", statuses);
        spec.put("dataflowAssignment", assignment); // Physical panes are inspected from the untouched actual rows.
        return spec;
    }
    private static Path manifest(Path experiment, String candidate) { return experiment.resolve("runs/" + candidate + "-s1/result.manifest.json"); }
    private static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
    private static void requireNew(Path... paths) throws IOException {
        for (Path path : paths) if (Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Refusing existing online fixture path: " + path.toAbsolutePath().normalize());
        }
    }

    /** One validation result supplies both the raw oracle and every coexisting display view. */
    private static final class Bundle {
        final Path path;
        final HtmlReports.ValidatedReport report;
        final JsonArray assignments;
        final JsonObject lifecycle;
        Bundle(Path path) throws IOException {
            this.path = path.toAbsolutePath().normalize();
            ExperimentArtifactValidator.ValidationResult checked = ExperimentArtifactValidator.validate(this.path);
            report = HtmlReports.validatedReport(checked);
            assignments = checked.getDataflowAssignment() == null ? null : checked.getDataflowAssignment().getAssignments();
            lifecycle = checked.getDecodedStorageLifecycle() != null ? checked.getDecodedStorageLifecycle().getDocument()
                    : checked.getDecodedFileLifecycle() != null ? checked.getDecodedFileLifecycle().getDocument() : null;
            require("COMPLETED_SUCCESSFULLY".equals(report.getManifest().getAsJsonObject("result").get("logicalTaskCompletionStatus").getAsString()),
                    "Fixture bundle must be an actual successful run: " + path);
            require(report.getManifest().getAsJsonObject("configuration").has("dataflowAssignment") == (assignments != null),
                    "A checked ON bundle cannot be relabeled as OFF");
        }
        Map<String, Object> row(String candidate) {
            JsonObject manifest = report.getManifest();
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("candidate", candidate);
            row.put("seed", Long.toString(exact(manifest.getAsJsonObject("configuration").get("rootSeed")).longValueExact()));
            row.put("status", manifest.getAsJsonObject("result").get("logicalTaskCompletionStatus").getAsString());
            report.attachTo(row);
            if (assignments != null) require(row.containsKey("dataflowAssignment")
                    && (row.containsKey("fileLifecycle") || row.containsKey("storageLifecycle")),
                    "Action panel must coexist with its real physical lifecycle, not replace it");
            return row;
        }
    }
}
