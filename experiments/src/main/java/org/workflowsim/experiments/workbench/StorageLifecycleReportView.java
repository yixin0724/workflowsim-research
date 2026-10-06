package org.workflowsim.experiments.workbench;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.workflowsim.data.v2.DataTransferFabric;
import org.workflowsim.data.v2.DataflowFilePlan;
import org.workflowsim.data.v2.StorageLifecycleCodec;

/**
 * Bounded display of a validated V3 storage-lifecycle certificate, not fluid-service accounting.
 * Only strings, structural booleans, nulls and deeply immutable maps/lists leave this class.
 */
final class StorageLifecycleReportView {
    static final int COPY_LIMIT = 64, RESOURCE_LIMIT = 64, EVENT_LIMIT = 128,
            JOB_LIMIT = 64, LIST_LIMIT = 12, LABEL_LIMIT = 256;

    private long labelsClipped, nestedItemsOmitted;

    private StorageLifecycleReportView() { }

    /** Null means OFF; only the caller can attest to validation against an enclosing run. */
    static Map<String, Object> fromDecoded(StorageLifecycleCodec.Decoded decoded, boolean contextValidated) {
        return decoded == null ? null : new StorageLifecycleReportView().project(decoded, contextValidated);
    }

    private Map<String, Object> project(StorageLifecycleCodec.Decoded decoded, boolean contextValidated) {
        // One defensive, already-validated snapshot: no I/O, redecoding, or raw history in the result.
        JsonObject document = decoded.getDocument();
        JsonObject plan = document.getAsJsonObject("filePlan");
        JsonObject capture = document.getAsJsonObject("capture");
        JsonObject policies = document.getAsJsonObject("policies");
        JsonObject fabric = document.getAsJsonObject("fabric");
        JsonArray rawEvents = document.getAsJsonArray("events");
        JsonArray rawResources = fabric.getAsJsonArray("resources");
        Map<DataflowFilePlan.FileId, BigDecimal> fileBytes = new HashMap<>();
        for (JsonElement value : plan.getAsJsonArray("files")) {
            JsonObject file = value.getAsJsonObject();
            fileBytes.put(fileKey(file.getAsJsonObject("fileId")), exact(file.get("bytes")));
        }

        Map<Long, JsonObject> admissions = new LinkedHashMap<>();
        Map<Long, JsonObject> settlements = new HashMap<>();
        Map<Integer, Job> jobs = new LinkedHashMap<>();
        Totals totals = new Totals();
        BigDecimal lastCpuFinish = null, lastStoreCommit = null;
        // Scan the whole capture before applying display caps, including late settlements/readiness.
        for (JsonElement value : rawEvents) {
            JsonObject event = value.getAsJsonObject();
            JsonObject payload = event.getAsJsonObject("payload");
            switch (event.get("type").getAsString()) {
                case "COPY_ADMITTED":
                    admissions.put(payload.get("copyOrdinal").getAsLong(), event);
                    if (isOutput(payload)) totals.admittedOutput = totals.admittedOutput.add(exact(payload.get("bytes")));
                    else totals.admittedInput = totals.admittedInput.add(exact(payload.get("bytes")));
                    break;
                case "COPY_SETTLED":
                    long ordinal = payload.get("copyOrdinal").getAsLong();
                    settlements.put(ordinal, event);
                    JsonObject admitted = admissions.get(ordinal).getAsJsonObject("payload");
                    // Declared whole-object payload, not rate * elapsed or size minus a numerical residual.
                    BigDecimal bytes = exact(admitted.get("bytes"));
                    if (isOutput(admitted)) {
                        totals.settledOutput = totals.settledOutput.add(bytes);
                        lastStoreCommit = maximum(lastStoreCommit, exact(event.get("observedTime")));
                    } else totals.settledInput = totals.settledInput.add(bytes);
                    totals.residual = totals.residual.add(exact(payload.get("remainingAfterService")));
                    break;
                case "JOB_INPUT_REQUESTED":
                    jobs.put(payload.get("jobId").getAsInt(), new Job(event));
                    break;
                case "INPUT_WAITING_FOR_STORE":
                    DataflowFilePlan.FileId waitingFile = fileKey(payload.getAsJsonObject("fileId"));
                    jobs.get(payload.get("jobId").getAsInt()).storeWaits.add(waitingFile);
                    BigDecimal waitingReferences = BigDecimal.valueOf(payload.get("referenceCount").getAsLong());
                    // Required references already exist at request, even before their source commits.
                    totals.references = totals.references.add(waitingReferences);
                    totals.requiredBytes = totals.requiredBytes.add(fileBytes.get(waitingFile).multiply(waitingReferences));
                    break;
                case "INPUT_RESOLVED":
                    DataflowFilePlan.FileId file = fileKey(payload.getAsJsonObject("fileId"));
                    Job job = jobs.get(payload.get("jobId").getAsInt());
                    boolean deferred = job.storeWaits.remove(file);
                    BigDecimal references = BigDecimal.valueOf(payload.get("referenceCount").getAsLong());
                    BigDecimal referenceBytes = fileBytes.get(file).multiply(references);
                    if (!deferred) {
                        totals.references = totals.references.add(references);
                        totals.requiredBytes = totals.requiredBytes.add(referenceBytes);
                    }
                    String resolution = payload.get("resolution").getAsString();
                    if ("LOCAL".equals(resolution)) {
                        totals.localReferences = totals.localReferences.add(references);
                        totals.localBytes = totals.localBytes.add(referenceBytes);
                    } else if ("JOIN_EXISTING".equals(resolution)) {
                        totals.joinedReferences = totals.joinedReferences.add(references);
                    }
                    if ("NEW_COPY".equals(resolution) || "JOIN_EXISTING".equals(resolution)) {
                        // Match the runtime's actual resolution-order binary64 accumulation, including
                        // a joined copy's full estimate even when its owner is another Job attempt.
                        job.nominalInputSeconds += admissions.get(payload.get("copyOrdinal").getAsLong())
                                .getAsJsonObject("payload").get("isolatedSeconds").getAsDouble();
                    }
                    if (deferred) {
                        // Every deferred STORE wait starts at request; overlapping waits are not additive.
                        job.storeGateWait = job.storeGateWait.max(exact(event.get("observedTime")).subtract(job.requestedAt));
                    }
                    break;
                case "JOB_DATA_READY":
                    jobs.get(payload.get("jobId").getAsInt()).dataReadyAt = exact(event.get("observedTime"));
                    break;
                case "JOB_CPU_STARTED":
                    jobs.get(payload.get("jobId").getAsInt()).cpuStartedAt = exact(event.get("observedTime"));
                    break;
                case "TASK_FINISHED":
                    BigDecimal finished = exact(event.get("observedTime"));
                    jobs.get(payload.get("jobId").getAsInt()).finishedAt = finished;
                    lastCpuFinish = maximum(lastCpuFinish, finished);
                    break;
                case "OUTPUT_RESOLVED":
                    // ZERO publishes now. NEW_COPY/JOIN_EXISTING publish at OUTPUT settlement;
                    // ALREADY_STORED is not a new commit, nor is any EXTERNAL_SEEDED observation.
                    if ("ZERO".equals(payload.get("resolution").getAsString())) {
                        lastStoreCommit = maximum(lastStoreCommit, exact(event.get("observedTime")));
                    }
                    break;
                default:
                    break;
            }
        }

        Map<String, Object> root = map();
        root.put("schema", "workflowsim-storage-lifecycle-display-v3");
        root.put("captureStatus", capture.get("status").getAsString());
        root.put("contextValidated", contextValidated);
        root.put("modelKind", document.get("modelKind").getAsString());
        root.put("sharingPolicy", policies.get("sharing").getAsString());
        root.put("inputAccess", policies.get("inputAccess").getAsString());
        root.put("outputCommit", policies.get("outputCommit").getAsString());
        root.put("certificateScope", document.get("certificateScope").getAsString());
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
        root.put("pendingOutputFileCount", integer(decoded.getPendingOutputFileCount()));
        root.put("waitingStoreInputCount", integer(decoded.getWaitingStoreInputCount()));
        root.put("quiescent", decoded.isQuiescent());
        root.put("store", store(fabric, rawResources));
        root.put("totals", totals.display());
        root.put("lastCpuFinishTime", decimal(lastCpuFinish));
        root.put("lastStoreCommitTime", decimal(lastStoreCommit));
        root.put("completedObservedOutputTailSeconds", decoded.isQuiescent() && lastCpuFinish != null && lastStoreCommit != null
                ? decimal(lastStoreCommit.subtract(lastCpuFinish).max(BigDecimal.ZERO)) : null);

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

        List<Map<String, Object>> jobRows = new ArrayList<>();
        for (Job job : jobs.values()) {
            if (jobRows.size() == JOB_LIMIT) break;
            jobRows.add(job.display());
        }
        root.put("jobs", preview(decoded.getRequestedJobCount(), JOB_LIMIT, jobRows));
        root.put("labelLimit", integer(LABEL_LIMIT));
        root.put("nestedListLimit", integer(LIST_LIMIT));
        root.put("labelsClipped", integer(labelsClipped));
        root.put("nestedItemsOmitted", integer(nestedItemsOmitted));
        return freeze(root);
    }

    private static Map<String, Object> store(JsonObject fabric, JsonArray resources) {
        Map<String, Object> store = map();
        store.put("attachmentHostId", integer(fabric.getAsJsonObject("sourceStorage").get("attachmentHostId")));
        // Display the validated physical inventory, not a fresh MB/s conversion or guessed bottleneck.
        for (JsonElement value : resources) {
            JsonObject resource = value.getAsJsonObject();
            String key;
            switch (resource.get("key").getAsString()) {
                case DataTransferFabric.STORE_READ: key = "readCapacityBytesPerSecond"; break;
                case DataTransferFabric.STORE_WRITE: key = "writeCapacityBytesPerSecond"; break;
                case DataTransferFabric.STORE_NIC: key = "networkCapacityBytesPerSecond"; break;
                default: continue;
            }
            store.put(key, quantity(resource.get("capacityBytesPerSecond")));
        }
        return freeze(store);
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
        row.put("destination", location(payload.getAsJsonObject("destination")));
        // The selected holder, original producer, and copy's owning Job are three separate facts.
        row.put("producerTaskId", integer(origin.get("producerTaskId")));
        row.put("producerJobAttemptId", integer(origin.get("jobAttemptId")));
        row.put("originLocation", location(origin.getAsJsonObject("location")));
        row.put("sourceVisibleAt", quantity(source.get("visibleAt")));
        row.put("admissionTime", decimal(admittedAt));
        row.put("standaloneRate", quantity(payload.get("standaloneRate")));
        row.put("isolatedSeconds", quantity(payload.get("isolatedSeconds")));
        row.put("effectiveCompletionTime", decimal(effectiveAt));
        row.put("observedCompletionTime", decimal(observedAt));
        row.put("effectiveFctSeconds", effectiveAt == null ? null : decimal(effectiveAt.subtract(admittedAt)));
        row.put("observedFctSeconds", observedAt == null ? null : decimal(observedAt.subtract(admittedAt)));
        row.put("notificationLagSeconds", observedAt == null ? null : decimal(observedAt.subtract(effectiveAt)));
        row.put("payloadBytes", quantity(payload.get("bytes")));
        row.put("completionResidualBytes", settled == null ? null : quantity(settled.get("remainingAfterService")));
        row.put("status", settled == null ? "IN_FLIGHT" : "SETTLED");
        row.put("resources", labels(path));
        row.put("resourcesOmitted", integer(omitted(path)));
        row.put("purpose", payload.get("purpose").getAsString());
        row.put("ownerJobId", integer(payload.get("ownerJobId")));
        return freeze(row);
    }

    private Map<String, Object> eventRow(JsonObject event, Map<Long, JsonObject> admissions) {
        String type = event.get("type").getAsString();
        JsonObject payload = event.getAsJsonObject("payload");
        JsonObject copy = "COPY_ADMITTED".equals(type) ? payload : "COPY_SETTLED".equals(type)
                ? admissions.get(payload.get("copyOrdinal").getAsLong()).getAsJsonObject("payload") : null;
        JsonObject file = copy != null ? copy.getAsJsonObject("fileId")
                : payload.has("fileId") ? payload.getAsJsonObject("fileId") : null;
        Map<String, Object> row = map();
        row.put("sequence", integer(event.get("sequence")));
        row.put("type", type);
        row.put("observedTime", quantity(event.get("observedTime")));
        // A copy event displays its admission owner, NOT a claim that it serves only that Job.
        row.put("jobId", integer(copy == null ? payload.get("jobId") : copy.get("ownerJobId")));
        row.put("taskId", "JOB_INPUT_REQUESTED".equals(type)
                ? integer(payload.getAsJsonArray("taskIds").get(0)) : integer(payload.get("taskId")));
        row.put("copyOrdinal", integer(payload.get("copyOrdinal")));
        row.put("fileId", file == null ? null : fileLabel(file));
        row.put("resolution", payload.has("resolution") ? payload.get("resolution").getAsString() : null);
        row.put("detail", detail(type, payload, copy));
        return freeze(row);
    }

    /** Bound individual labels/lists, never the whole detail: numeric text must remain exact. */
    private String detail(String type, JsonObject payload, JsonObject copy) {
        switch (type) {
            case "EXTERNAL_SEEDED":
                return "location=" + location(payload.getAsJsonObject("location"));
            case "TASK_FINISHED":
                return "location=" + vm(payload.get("vmId")) + "; success=" + payload.get("success").getAsBoolean();
            case "JOB_INPUT_REQUESTED":
                JsonArray tasks = payload.getAsJsonArray("taskIds");
                return "destination=" + vm(payload.get("destinationVmId")) + "; taskIds=" + ids(tasks)
                        + "; taskIdsOmitted=" + integer(omitted(tasks));
            case "INPUT_WAITING_FOR_STORE":
                return "waitingFor=SOURCE:source; referenceCount=" + integer(payload.get("referenceCount"));
            case "INPUT_RESOLVED":
                return "source=" + location(payload.getAsJsonObject("source"))
                        + "; referenceCount=" + integer(payload.get("referenceCount"));
            case "OUTPUT_RESOLVED":
                return "source=" + location(payload.getAsJsonObject("source"));
            case "COPY_ADMITTED":
                JsonObject source = payload.getAsJsonObject("sourceReplica");
                JsonObject origin = source.getAsJsonObject("origin");
                JsonArray path = payload.getAsJsonArray("resources");
                return copyOwner(copy) + "; source=" + location(source.getAsJsonObject("location"))
                        + "; destination=" + location(payload.getAsJsonObject("destination"))
                        + "; producerTaskId=" + integer(origin.get("producerTaskId"))
                        + "; producerJobAttemptId=" + integer(origin.get("jobAttemptId"))
                        + "; originLocation=" + location(origin.getAsJsonObject("location"))
                        + "; sourceVisibleAt=" + quantity(source.get("visibleAt"))
                        + "; payloadBytes=" + quantity(payload.get("bytes"))
                        + "; standaloneRate(B/s)=" + quantity(payload.get("standaloneRate"))
                        + "; isolatedSeconds=" + quantity(payload.get("isolatedSeconds"))
                        + "; resources=" + labels(path) + "; resourcesOmitted=" + integer(omitted(path));
            case "COPY_SETTLED":
                return copyOwner(copy) + "; effectiveTime=" + quantity(payload.get("effectiveTime"))
                        + "; completionResidualBytes=" + quantity(payload.get("remainingAfterService"));
            case "JOB_DATA_READY":
                return "jobId=" + integer(payload.get("jobId"));
            case "JOB_CPU_STARTED":
                return "location=" + vm(payload.get("vmId"));
            default:
                throw new IllegalArgumentException("Unsupported validated storage lifecycle event type: " + type);
        }
    }

    private static String copyOwner(JsonObject copy) {
        return "purpose=" + copy.get("purpose").getAsString() + "; ownerJobId=" + integer(copy.get("ownerJobId"));
    }

    private String fileLabel(JsonObject file) {
        return label("input[" + integer(file.get("workflowInputIndex")) + "]/" + file.get("name").getAsString());
    }

    private static DataflowFilePlan.FileId fileKey(JsonObject file) {
        // Clipped display labels must never become identity keys for references or deferred waits.
        return DataflowFilePlan.FileId.of(file.get("workflowInputIndex").getAsInt(), file.get("name").getAsString());
    }

    private String location(JsonObject location) {
        return "VM".equals(location.get("kind").getAsString()) ? vm(location.get("vmId"))
                : label("SOURCE:" + location.get("sourceId").getAsString());
    }

    private static String vm(JsonElement id) { return "VM:" + integer(id); }
    private static boolean isOutput(JsonObject payload) { return "OUTPUT".equals(payload.get("purpose").getAsString()); }

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
    private static BigDecimal maximum(BigDecimal previous, BigDecimal value) { return previous == null ? value : previous.max(value); }
    private static String decimal(BigDecimal value) {
        // Unabridged plain decimal, including subnormals and exact sums beyond binary64's range.
        return value == null ? null : value.stripTrailingZeros().toPlainString();
    }
    private static Map<String, Object> map() { return new LinkedHashMap<>(); }
    private static Map<String, Object> freeze(Map<String, Object> values) {
        return Collections.unmodifiableMap(new LinkedHashMap<>(values));
    }
    private static <T> List<T> immutableList(List<T> values) {
        return Collections.unmodifiableList(new ArrayList<>(values));
    }

    private static final class Job {
        final String jobId, taskId, vmId;
        final BigDecimal requestedAt;
        final Set<DataflowFilePlan.FileId> storeWaits = new HashSet<>();
        BigDecimal dataReadyAt, cpuStartedAt, finishedAt;
        BigDecimal storeGateWait = BigDecimal.ZERO;
        double nominalInputSeconds;

        Job(JsonObject request) {
            JsonObject payload = request.getAsJsonObject("payload");
            jobId = integer(payload.get("jobId"));
            taskId = integer(payload.getAsJsonArray("taskIds").get(0));
            vmId = integer(payload.get("destinationVmId"));
            requestedAt = exact(request.get("observedTime"));
        }

        Map<String, Object> display() {
            Map<String, Object> row = map();
            row.put("jobId", jobId);
            row.put("taskId", taskId);
            row.put("vmId", vmId);
            row.put("requestedAt", decimal(requestedAt));
            row.put("dataReadyAt", decimal(dataReadyAt));
            row.put("cpuStartedAt", decimal(cpuStartedAt));
            row.put("finishedAt", decimal(finishedAt));
            // A STORE-unresolved prefix has only a partial estimate. Do not present it as final.
            row.put("nominalInputSeconds", storeWaits.isEmpty() ? decimal(new BigDecimal(nominalInputSeconds)) : null);
            row.put("observedPreparationSeconds", dataReadyAt == null ? null : decimal(dataReadyAt.subtract(requestedAt)));
            row.put("storeGateWaitSeconds", storeWaits.isEmpty() ? decimal(storeGateWait) : null);
            return freeze(row);
        }
    }

    private static final class Totals {
        BigDecimal admittedInput = BigDecimal.ZERO, admittedOutput = BigDecimal.ZERO,
                settledInput = BigDecimal.ZERO, settledOutput = BigDecimal.ZERO, residual = BigDecimal.ZERO,
                references = BigDecimal.ZERO, localReferences = BigDecimal.ZERO, joinedReferences = BigDecimal.ZERO,
                requiredBytes = BigDecimal.ZERO, localBytes = BigDecimal.ZERO;

        Map<String, Object> display() {
            Map<String, Object> totals = map();
            totals.put("admittedInputPayloadBytes", decimal(admittedInput));
            totals.put("admittedOutputPayloadBytes", decimal(admittedOutput));
            totals.put("settledInputPayloadBytes", decimal(settledInput));
            totals.put("settledOutputPayloadBytes", decimal(settledOutput));
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
