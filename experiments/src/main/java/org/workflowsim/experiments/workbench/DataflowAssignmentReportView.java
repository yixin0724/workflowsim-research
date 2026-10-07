package org.workflowsim.experiments.workbench;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.workflowsim.data.v2.DataflowAssignmentValidator;

/**
 * Bounded display of validated online bindings and logged nominal scores, not actual input waits.
 * Only strings, structural booleans, nulls and deeply immutable maps/lists leave this class.
 * No lifecycle, live progress, filesystem access or further validation is needed here.
 */
final class DataflowAssignmentReportView {
    static final int ACTION_LIMIT = 64, CANDIDATE_LIMIT = 12;

    private static final String MODE = "CONTROL_READY_ONLINE_ASSIGNMENT_V1";
    private static final String POLICY = "NOMINAL_INPUT_EARLIEST_RESERVATION_V1";
    private static final String INPUT = "MAX_FILE_NOMINAL_REMAINING_CONDITIONAL_STORE_V1";
    private static final String CPU = "ACTIVE_COMPUTE_RESERVATION_FROM_OBSERVED_START_V1";

    private DataflowAssignmentReportView() { }

    /** Null means that the enclosing run has no validated online-assignment evidence. */
    static Map<String, Object> fromValidated(DataflowAssignmentValidator.Result checked) {
        if (checked == null) return null;
        // Exactly one defensive, already-validated snapshot; never expose its JSON nodes.
        JsonArray assignments = checked.getAssignments();
        long conditionalStoreCount = 0;
        BigDecimal joinedInputCopyCount = BigDecimal.ZERO;
        // Summaries cover every assignment and its actual selected candidate, before either cap.
        for (JsonElement value : assignments) {
            JsonObject selected = selected(value.getAsJsonObject().getAsJsonObject("attributes"));
            if (selected.get("conditionalStoreWait").getAsBoolean()) conditionalStoreCount++;
            joinedInputCopyCount = joinedInputCopyCount.add(exact(selected.get("joinedInputCopies")));
        }

        Map<String, Object> summary = map();
        summary.put("assignmentCount", integer(checked.getAssignmentCount()));
        summary.put("initialBindingCount", integer(checked.getInitialBindingCount()));
        summary.put("retryReuseCount", integer(checked.getRetryReuseCount()));
        summary.put("conditionalStoreCount", integer(conditionalStoreCount));
        summary.put("joinedInputCopyCount", decimal(joinedInputCopyCount));

        JsonObject first = assignments.size() == 0 ? null
                : assignments.get(0).getAsJsonObject().getAsJsonObject("attributes");
        Map<String, Object> root = map();
        root.put("schema", "workflowsim-dataflow-assignment-display-v1");
        root.put("contextValidated", true);
        root.put("auditScope", checked.getScope());
        // Bounded nominal estimates are not an exact replay or a contention/progress certificate.
        root.put("liveProgressReplayed", false);
        root.put("assignmentMode", metadata(first, "assignmentMode", MODE));
        root.put("assignmentPolicy", metadata(first, "assignmentPolicy", POLICY));
        root.put("inputEstimateSemantics", metadata(first, "inputEstimateSemantics", INPUT));
        root.put("cpuReservationSemantics", metadata(first, "cpuReservationSemantics", CPU));
        root.put("summary", freeze(summary));

        List<Map<String, Object>> rows = new ArrayList<>();
        for (int i = 0; i < Math.min(ACTION_LIMIT, assignments.size()); i++) {
            rows.add(action(assignments.get(i).getAsJsonObject()));
        }
        root.put("actions", preview(assignments.size(), ACTION_LIMIT, rows));
        return freeze(root);
    }

    private static Map<String, Object> action(JsonObject main) {
        JsonObject attrs = main.getAsJsonObject("attributes");
        JsonArray candidates = attrs.getAsJsonArray("candidates");
        // Resolve from the complete candidate domain, even when its winner is not displayed.
        JsonObject chosen = selected(attrs);
        Map<String, Object> row = map();
        row.put("sequence", scalar(attrs.get("assignmentSequence")));
        row.put("mainSequence", scalar(main.get("sequence")));
        row.put("jobId", scalar(main.get("jobId")));
        row.put("taskId", scalar(main.getAsJsonArray("taskIds").get(0)));
        row.put("vmId", scalar(main.get("vmId")));
        row.put("time", scalar(main.get("simulationTime")));
        row.put("binding", attrs.get("binding").getAsString());
        row.put("selected", fields(chosen, "inputSeconds", "cpuAvailableAt", "computeSeconds",
                "scoreFinishSeconds", "conditionalStoreWait", "joinedInputCopies"));
        row.put("observation", fields(attrs.getAsJsonObject("observation"), "contract", "status",
                "storageVersion", "storeBackedInputs", "interFlowSharing", "observedThrough", "serviceThrough",
                "activeCopyCount", "activeJobCount", "pendingOutputFileCount", "scopedFileCount"));

        List<Map<String, Object>> candidateRows = new ArrayList<>();
        for (int i = 0; i < Math.min(CANDIDATE_LIMIT, candidates.size()); i++) {
            candidateRows.add(fields(candidates.get(i).getAsJsonObject(), "vmId", "compatible", "inputSeconds",
                    "cpuAvailableAt", "computeSeconds", "scoreFinishSeconds", "conditionalStoreWait", "joinedInputCopies"));
        }
        row.put("candidates", preview(candidates.size(), CANDIDATE_LIMIT, candidateRows));
        return freeze(row);
    }

    private static JsonObject selected(JsonObject attrs) {
        int vm = attrs.get("selectedVmId").getAsInt();
        for (JsonElement value : attrs.getAsJsonArray("candidates")) {
            JsonObject candidate = value.getAsJsonObject();
            if (candidate.get("vmId").getAsInt() == vm) return candidate;
        }
        // The public validator's Result guarantees this invariant; this is not a second audit.
        throw new IllegalStateException("Validated assignment has no selected candidate");
    }

    /** Explicit field lists keep raw main/lifecycle history and workflow labels out of the view. */
    private static Map<String, Object> fields(JsonObject source, String... names) {
        Map<String, Object> row = map();
        for (String name : names) row.put(name, scalar(source.get(name)));
        return freeze(row);
    }

    private static Map<String, Object> preview(int total, int limit, List<Map<String, Object>> rows) {
        Map<String, Object> preview = map();
        preview.put("total", integer(total));
        preview.put("shown", integer(rows.size()));
        preview.put("omitted", integer(total - rows.size()));
        preview.put("limit", integer(limit));
        preview.put("rows", Collections.unmodifiableList(new ArrayList<>(rows)));
        return freeze(preview);
    }

    private static Object scalar(JsonElement value) {
        if (value.isJsonNull()) return null;
        JsonPrimitive primitive = value.getAsJsonPrimitive();
        if (primitive.isBoolean()) return primitive.getAsBoolean();
        if (primitive.isNumber()) return decimal(exact(primitive));
        return primitive.getAsString();
    }

    // Preserve the validated JSON decimal value, not a rounded binary64 approximation of it.
    // Numeric text is never clipped, epsilon-rounded, or formatted as a date/scientific notation.
    private static BigDecimal exact(JsonElement value) { return new BigDecimal(value.getAsString()); }
    private static String decimal(BigDecimal value) { return value.stripTrailingZeros().toPlainString(); }
    private static String integer(long value) { return Long.toString(value); }
    private static String metadata(JsonObject first, String name, String fallback) {
        return first == null ? fallback : first.get(name).getAsString();
    }
    private static Map<String, Object> map() { return new LinkedHashMap<>(); }
    private static Map<String, Object> freeze(Map<String, Object> values) {
        return Collections.unmodifiableMap(new LinkedHashMap<>(values));
    }
}
