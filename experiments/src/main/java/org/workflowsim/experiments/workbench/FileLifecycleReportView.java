package org.workflowsim.experiments.workbench;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.workflowsim.data.v2.DataflowFilePlan;
import org.workflowsim.data.v2.FileLifecycleCodec;

/**
 * Bounded display of a validated V2 file-lifecycle certificate, not fluid-service accounting.
 * Only strings, structural booleans, nulls and deeply immutable maps/lists leave this class.
 */
final class FileLifecycleReportView {
    static final int COPY_LIMIT = 64, RESOURCE_LIMIT = 64, EVENT_LIMIT = 128,
            LIST_LIMIT = 12, LABEL_LIMIT = 256;

    private long labelsClipped, nestedItemsOmitted;

    private FileLifecycleReportView() { }

    /** Null means OFF; only the caller can attest to validation against an enclosing run. */
    static Map<String, Object> fromDecoded(FileLifecycleCodec.Decoded decoded, boolean contextValidated) {
        return decoded == null ? null : new FileLifecycleReportView().project(decoded, contextValidated);
    }

    private Map<String, Object> project(FileLifecycleCodec.Decoded decoded, boolean contextValidated) {
        // One defensive, already-validated snapshot; never read files or invoke a codec here.
        JsonObject document = decoded.getDocument();
        JsonObject plan = document.getAsJsonObject("filePlan");
        JsonObject capture = document.getAsJsonObject("capture");
        JsonArray rawEvents = document.getAsJsonArray("events");
        JsonArray rawResources = document.getAsJsonObject("fabric").getAsJsonArray("resources");
        Map<DataflowFilePlan.FileId, BigDecimal> fileBytes = new HashMap<>();
        for (JsonElement value : plan.getAsJsonArray("files")) {
            JsonObject file = value.getAsJsonObject();
            fileBytes.put(fileKey(file.getAsJsonObject("fileId")), exact(file.get("bytes")));
        }

        Map<Long, JsonObject> admissions = new LinkedHashMap<>();
        Map<Long, JsonObject> settlements = new HashMap<>();
        Totals totals = new Totals();
        for (JsonElement value : rawEvents) {
            JsonObject event = value.getAsJsonObject();
            JsonObject payload = event.getAsJsonObject("payload");
            switch (event.get("type").getAsString()) {
                case "COPY_ADMITTED":
                    admissions.put(payload.get("copyOrdinal").getAsLong(), event);
                    totals.admitted = totals.admitted.add(exact(payload.get("bytes")));
                    break;
                case "COPY_SETTLED":
                    long ordinal = payload.get("copyOrdinal").getAsLong();
                    settlements.put(ordinal, event);
                    // Settled payload is the declared object size, not rate * elapsed or size - residual.
                    totals.settled = totals.settled.add(exact(admissions.get(ordinal)
                            .getAsJsonObject("payload").get("bytes")));
                    totals.residual = totals.residual.add(exact(payload.get("remainingAfterService")));
                    break;
                case "INPUT_RESOLVED":
                    BigDecimal references = BigDecimal.valueOf(payload.get("referenceCount").getAsLong());
                    BigDecimal bytes = fileBytes.get(fileKey(payload.getAsJsonObject("fileId")))
                            .multiply(references);
                    totals.references = totals.references.add(references);
                    totals.requiredBytes = totals.requiredBytes.add(bytes);
                    String resolution = payload.get("resolution").getAsString();
                    if ("LOCAL".equals(resolution)) {
                        totals.localReferences = totals.localReferences.add(references);
                        totals.localBytes = totals.localBytes.add(bytes);
                    } else if ("JOIN_EXISTING".equals(resolution)) {
                        // Count the joined reference multiplicity, not an inferred number of saved copies.
                        totals.joinedReferences = totals.joinedReferences.add(references);
                    }
                    break;
                default:
                    break;
            }
        }

        Map<String, Object> root = map();
        root.put("schema", "workflowsim-file-lifecycle-display-v2");
        // TRUNCATED cannot be represented by a valid first-version Decoded certificate.
        root.put("captureStatus", "COMPLETE");
        root.put("contextValidated", contextValidated);
        root.put("certificateScope", document.get("certificateScope").getAsString());
        root.put("modelKind", document.get("modelKind").getAsString());
        root.put("sharingPolicy", document.getAsJsonObject("policies").get("sharing").getAsString());
        root.put("traceBudget", integer(document.getAsJsonObject("recording").get("maxTraceRecords")));
        root.put("retainedRecords", integer(capture.get("retainedRecords")));
        root.put("observedThrough", quantity(capture.get("observedThrough")));
        root.put("taskCount", integer(plan.getAsJsonArray("tasks").size()));
        root.put("fileCount", integer(plan.getAsJsonArray("files").size()));
        root.put("copyCount", integer(decoded.getCopyCount()));
        root.put("completedCopyCount", integer(decoded.getCompletedCopyCount()));
        root.put("activeCopyCount", integer(decoded.getActiveCopyCount()));
        root.put("requestedJobCount", integer(decoded.getRequestedJobCount()));
        root.put("completedJobCount", integer(decoded.getCompletedJobCount()));
        root.put("quiescent", decoded.isQuiescent());
        root.put("totals", totals.display());

        List<Map<String, Object>> copies = new ArrayList<>();
        for (Map.Entry<Long, JsonObject> entry : admissions.entrySet()) {
            if (copies.size() == COPY_LIMIT) break;
            copies.add(copyRow(entry.getValue(), settlements.get(entry.getKey())));
        }
        root.put("copies", preview(decoded.getCopyCount(), COPY_LIMIT, copies));

        List<Map<String, Object>> resources = new ArrayList<>();
        for (int i = 0; i < Math.min(RESOURCE_LIMIT, rawResources.size()); i++) {
            JsonObject resource = rawResources.get(i).getAsJsonObject();
            Map<String, Object> row = map();
            row.put("resourceKey", label(resource.get("key").getAsString()));
            row.put("capacityBytesPerSecond", quantity(resource.get("capacityBytesPerSecond")));
            resources.add(freeze(row));
        }
        root.put("resources", preview(rawResources.size(), RESOURCE_LIMIT, resources));

        List<Map<String, Object>> events = new ArrayList<>();
        for (int i = 0; i < Math.min(EVENT_LIMIT, rawEvents.size()); i++) {
            events.add(eventRow(rawEvents.get(i).getAsJsonObject(), admissions));
        }
        root.put("events", preview(rawEvents.size(), EVENT_LIMIT, events));
        root.put("labelLimit", integer(LABEL_LIMIT));
        root.put("nestedListLimit", integer(LIST_LIMIT));
        root.put("labelsClipped", integer(labelsClipped));
        root.put("nestedItemsOmitted", integer(nestedItemsOmitted));
        return freeze(root);
    }

    private Map<String, Object> copyRow(JsonObject admission, JsonObject settlement) {
        JsonObject payload = admission.getAsJsonObject("payload");
        JsonObject source = payload.getAsJsonObject("sourceReplica");
        JsonObject origin = source.getAsJsonObject("origin");
        JsonObject settled = settlement == null ? null : settlement.getAsJsonObject("payload");
        BigDecimal admittedAt = exact(admission.get("observedTime"));
        BigDecimal effectiveAt = settled == null ? null : exact(settled.get("effectiveTime"));
        BigDecimal observedAt = settlement == null ? null : exact(settlement.get("observedTime"));
        JsonArray path = payload.getAsJsonArray("resources");

        Map<String, Object> row = map();
        row.put("copyOrdinal", integer(payload.get("copyOrdinal")));
        row.put("fileId", fileLabel(payload.getAsJsonObject("fileId")));
        row.put("source", location(source.getAsJsonObject("location")));
        row.put("destination", vm(payload.get("destinationVmId")));
        // The selected holder may be a cache. Its root producer is a separate, immutable fact.
        row.put("producerTaskId", integer(origin.get("producerTaskId")));
        row.put("producerJobAttemptId", integer(origin.get("jobAttemptId")));
        row.put("originLocation", location(origin.getAsJsonObject("location")));
        row.put("sourceVisibleAt", quantity(source.get("visibleAt")));
        row.put("admissionTime", decimal(admittedAt));
        row.put("standaloneRate", quantity(payload.get("standaloneRate")));
        row.put("isolatedSeconds", quantity(payload.get("isolatedSeconds")));
        row.put("effectiveCompletionTime", decimal(effectiveAt));
        row.put("observedCompletionTime", decimal(observedAt));
        // Subtract exact binary64 values, never already-rounded double differences.
        row.put("effectiveFctSeconds", effectiveAt == null ? null : decimal(effectiveAt.subtract(admittedAt)));
        row.put("observedFctSeconds", observedAt == null ? null : decimal(observedAt.subtract(admittedAt)));
        row.put("notificationLagSeconds", observedAt == null ? null : decimal(observedAt.subtract(effectiveAt)));
        row.put("payloadBytes", quantity(payload.get("bytes")));
        row.put("completionResidualBytes", settled == null ? null : quantity(settled.get("remainingAfterService")));
        row.put("status", settled == null ? "IN_FLIGHT" : "SETTLED");
        row.put("resources", labels(path));
        row.put("resourcesOmitted", integer(omitted(path)));
        return freeze(row);
    }

    private Map<String, Object> eventRow(JsonObject event, Map<Long, JsonObject> admissions) {
        String type = event.get("type").getAsString();
        JsonObject payload = event.getAsJsonObject("payload");
        JsonObject file = payload.has("fileId") ? payload.getAsJsonObject("fileId") : null;
        if ("COPY_SETTLED".equals(type)) {
            file = admissions.get(payload.get("copyOrdinal").getAsLong())
                    .getAsJsonObject("payload").getAsJsonObject("fileId");
        }
        Map<String, Object> row = map();
        row.put("sequence", integer(event.get("sequence")));
        row.put("type", type);
        row.put("observedTime", quantity(event.get("observedTime")));
        row.put("jobId", integer(payload.get("jobId")));
        // Other job-scoped records need not repeat their logical Task; do not invent a copy owner.
        row.put("taskId", "JOB_INPUT_REQUESTED".equals(type)
                ? integer(payload.getAsJsonArray("taskIds").get(0)) : integer(payload.get("taskId")));
        row.put("copyOrdinal", integer(payload.get("copyOrdinal")));
        row.put("fileId", file == null ? null : fileLabel(file));
        row.put("resolution", payload.has("resolution") ? payload.get("resolution").getAsString() : null);
        row.put("detail", detail(type, payload));
        return freeze(row);
    }

    /** Bound individual labels/lists, not the whole detail: its numeric text must remain exact. */
    private String detail(String type, JsonObject payload) {
        switch (type) {
            case "EXTERNAL_SEEDED":
                return "location=" + location(payload.getAsJsonObject("location"));
            case "TASK_FINISHED":
                return "location=" + vm(payload.get("vmId")) + "; success=" + payload.get("success").getAsBoolean();
            case "JOB_INPUT_REQUESTED":
                JsonArray tasks = payload.getAsJsonArray("taskIds");
                return "destination=" + vm(payload.get("destinationVmId")) + "; taskIds=" + ids(tasks)
                        + "; taskIdsOmitted=" + integer(omitted(tasks));
            case "INPUT_RESOLVED":
                return "source=" + location(payload.getAsJsonObject("source"))
                        + "; referenceCount=" + integer(payload.get("referenceCount"));
            case "COPY_ADMITTED":
                JsonObject source = payload.getAsJsonObject("sourceReplica");
                JsonObject origin = source.getAsJsonObject("origin");
                JsonArray path = payload.getAsJsonArray("resources");
                return "source=" + location(source.getAsJsonObject("location"))
                        + "; destination=" + vm(payload.get("destinationVmId"))
                        + "; producerTaskId=" + integer(origin.get("producerTaskId"))
                        + "; producerJobAttemptId=" + integer(origin.get("jobAttemptId"))
                        + "; originLocation=" + location(origin.getAsJsonObject("location"))
                        + "; sourceVisibleAt=" + quantity(source.get("visibleAt"))
                        + "; payloadBytes=" + quantity(payload.get("bytes"))
                        + "; standaloneRate(B/s)=" + quantity(payload.get("standaloneRate"))
                        + "; isolatedSeconds=" + quantity(payload.get("isolatedSeconds"))
                        + "; resources=" + labels(path) + "; resourcesOmitted=" + integer(omitted(path));
            case "COPY_SETTLED":
                return "effectiveTime=" + quantity(payload.get("effectiveTime"))
                        + "; completionResidualBytes=" + quantity(payload.get("remainingAfterService"));
            case "JOB_DATA_READY":
                return "jobId=" + integer(payload.get("jobId"));
            case "JOB_CPU_STARTED":
                return "location=" + vm(payload.get("vmId"));
            default:
                throw new IllegalArgumentException("Unsupported validated file lifecycle event type: " + type);
        }
    }

    private String fileLabel(JsonObject file) {
        return label("input[" + integer(file.get("workflowInputIndex")) + "]/" + file.get("name").getAsString());
    }

    private static DataflowFilePlan.FileId fileKey(JsonObject file) {
        // Display clipping is never used as identity when joining file-plan bytes to references.
        return DataflowFilePlan.FileId.of(file.get("workflowInputIndex").getAsInt(), file.get("name").getAsString());
    }

    private String location(JsonObject location) {
        return "VM".equals(location.get("kind").getAsString()) ? vm(location.get("vmId"))
                : label("SOURCE:" + location.get("sourceId").getAsString());
    }

    private static String vm(JsonElement id) { return "VM:" + integer(id); }

    private String label(String value) {
        if (value.length() <= LABEL_LIMIT) return value;
        labelsClipped++;
        int end = LABEL_LIMIT;
        if (Character.isHighSurrogate(value.charAt(end - 1))) end--;
        return value.substring(0, end) + "…";
    }

    private List<String> labels(JsonArray values) {
        List<String> shown = new ArrayList<>();
        for (int i = 0; i < Math.min(LIST_LIMIT, values.size()); i++) shown.add(label(values.get(i).getAsString()));
        nestedItemsOmitted += omitted(values);
        return immutableList(shown);
    }

    private List<String> ids(JsonArray values) {
        List<String> shown = new ArrayList<>();
        for (int i = 0; i < Math.min(LIST_LIMIT, values.size()); i++) shown.add(integer(values.get(i)));
        nestedItemsOmitted += omitted(values);
        return immutableList(shown);
    }

    private static int omitted(JsonArray values) { return Math.max(0, values.size() - LIST_LIMIT); }

    private static Map<String, Object> preview(long total, int limit, List<Map<String, Object>> rows) {
        Map<String, Object> preview = map();
        preview.put("total", integer(total));
        preview.put("shown", integer(rows.size()));
        preview.put("omitted", integer(total - rows.size()));
        preview.put("limit", integer(limit));
        preview.put("rows", immutableList(rows));
        return freeze(preview);
    }

    private static String integer(long value) { return Long.toString(value); }
    private static String integer(JsonElement value) { return absent(value) ? null : integer(value.getAsLong()); }
    private static boolean absent(JsonElement value) { return value == null || value.isJsonNull(); }
    private static BigDecimal exact(JsonElement value) { return new BigDecimal(value.getAsDouble()); }
    private static String quantity(JsonElement value) { return absent(value) ? null : decimal(exact(value)); }
    private static String decimal(BigDecimal value) {
        // Plain decimal is deliberately unabridged even for subnormals and sums beyond binary64.
        return value == null ? null : value.stripTrailingZeros().toPlainString();
    }
    private static Map<String, Object> map() { return new LinkedHashMap<>(); }
    private static Map<String, Object> freeze(Map<String, Object> values) {
        return Collections.unmodifiableMap(new LinkedHashMap<>(values));
    }
    private static <T> List<T> immutableList(List<T> values) {
        return Collections.unmodifiableList(new ArrayList<>(values));
    }

    private static final class Totals {
        BigDecimal admitted = BigDecimal.ZERO, settled = BigDecimal.ZERO, residual = BigDecimal.ZERO,
                references = BigDecimal.ZERO, localReferences = BigDecimal.ZERO, joinedReferences = BigDecimal.ZERO,
                requiredBytes = BigDecimal.ZERO, localBytes = BigDecimal.ZERO;

        Map<String, Object> display() {
            Map<String, Object> totals = map();
            totals.put("admittedPayloadBytes", decimal(admitted));
            totals.put("settledPayloadBytes", decimal(settled));
            totals.put("completionResidualBytes", decimal(residual));
            totals.put("referenceCount", decimal(references));
            totals.put("localReferenceCount", decimal(localReferences));
            totals.put("joinedReferenceCount", decimal(joinedReferences));
            totals.put("requiredReferenceBytes", decimal(requiredBytes));
            totals.put("localReferenceBytes", decimal(localBytes));
            return freeze(totals);
        }
    }
}
