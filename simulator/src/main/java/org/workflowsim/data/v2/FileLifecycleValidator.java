package org.workflowsim.data.v2;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.math.BigDecimal;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Independent read-side state machine. Deliberately does not use the runtime, replica catalog,
 * selector, topology router or service allocator. All state below is rebuilt from checked wire
 * facts; only the public immutable event/evidence factories are shared with the write side.
 */
final class FileLifecycleValidator {
    private static final int MAX_NUMBER_DIGITS = 4096;
    private static final Location SOURCE = new Location(-1);

    private FileLifecycleValidator() { }

    /** Read-only route adapter for independent action audits; never uses the runtime router. */
    static final class ReadFabric {
        private final Fabric fabric;

        ReadFabric(JsonObject document, boolean storage) {
            fabric = new Fabric(document, storage ? LifecycleContract.STORAGE_V3 : LifecycleContract.FILE_V2);
        }

        double rate(DataLocation source, DataLocation destination) {
            return fabric.route(known(source), known(destination)).rate;
        }

        private Location known(DataLocation value) {
            require(value != null, "A typed route endpoint is required");
            Location location;
            if (value.getKind() == DataLocation.Kind.VM) location = new Location(value.getVmId());
            else {
                require("source".equals(value.getSourceId()), "Unsupported SOURCE route endpoint");
                location = SOURCE;
            }
            require(fabric.locations.contains(location), "Unknown typed route endpoint");
            return location;
        }
    }

    static final class Result {
        final FileLifecycleEvidence evidence;
        final int requestedJobs, completedJobs, activeCopies, pendingOutputFiles, waitingStoreInputs;
        final long copies, completedCopies;
        final boolean quiescent, storeBackedInputs;
        final Set<Integer> successfulTasks;

        Result(FileLifecycleEvidence evidence, Replay replay) {
            this.evidence = evidence;
            requestedJobs = replay.jobs.size();
            completedJobs = replay.completedJobs;
            copies = replay.copyCount;
            completedCopies = replay.completedCopies;
            activeCopies = replay.active.size();
            pendingOutputFiles = replay.pendingOutputs.size();
            waitingStoreInputs = replay.waitingStoreInputs;
            storeBackedInputs = replay.storeBackedInputs;
            quiescent = activeCopies == 0 && requestedJobs == completedJobs && pendingOutputFiles == 0 && waitingStoreInputs == 0;
            successfulTasks = Collections.unmodifiableSet(new TreeSet<>(replay.successfulTasks));
        }
    }

    static void validatePlanDocument(JsonObject plan) { new Plan(object(plan, "filePlan")); }

    /** The original entry point remains pinned to V2, even though the event carrier is shared. */
    static Result validate(JsonObject root) { return validate(root, LifecycleContract.FILE_V2); }

    static Result validate(JsonObject root, LifecycleContract contract) {
        require(contract != null, "A trusted lifecycle contract is required");
        keys(root, "schema", "modelKind", "recording", "certificateScope", "policies", "capture", "filePlan", "fabric", "events");
        equal(root, "schema", contract.schema);
        equal(root, "certificateScope", contract.scope);
        String kind = text(root.get("modelKind"), "modelKind");
        boolean shared = contract.sharedKind.equals(kind);
        require(shared || contract.isolatedKind.equals(kind), "Unsupported modelKind");
        JsonObject recording = object(root.get("recording"), "recording");
        keys(recording, "mode", "maxTraceRecords");
        equal(recording, "mode", contract.mode);
        int budget = nonnegativeInt(recording.get("maxTraceRecords"), "maxTraceRecords");
        require(budget > 0, "maxTraceRecords must be positive");
        JsonObject policies = object(root.get("policies"), "policies");
        keys(policies, contract.policyFields);
        boolean storeBackedInputs = false;
        if (contract.storage) {
            String access = text(policies.get("inputAccess"), "inputAccess");
            storeBackedInputs = StorageLifecycleCodec.STORE_INPUT_ACCESS.equals(access);
            require(storeBackedInputs || StorageLifecycleCodec.LOCAL_INPUT_ACCESS.equals(access), "Unsupported inputAccess");
            equal(policies, "outputCommit", StorageLifecycleCodec.OUTPUT_COMMIT);
        }
        equal(policies, "fileIdentity", FileLifecycleCodec.IDENTITY);
        equal(policies, "release", FileLifecycleCodec.RELEASE);
        equal(policies, "visibility", FileLifecycleCodec.VISIBILITY);
        equal(policies, "selection", contract.selection(storeBackedInputs));
        equal(policies, "sourceAccess", contract.sourceAccess);
        equal(policies, "sharing", shared ? "SHARED_MAX_MIN" : "ISOLATED_PATH_BOTTLENECK");
        JsonObject capture = object(root.get("capture"), "capture");
        keys(capture, "status", "observedThrough", "retainedRecords", "droppedRecords");
        equal(capture, "status", "COMPLETE"); // TRUNCATED is not a first-version certificate.
        double watermark = number(capture.get("observedThrough"), "observedThrough");
        JsonArray rows = array(root.get("events"), "events");
        require(whole(capture.get("droppedRecords"), "droppedRecords") == 0, "Complete capture lost history");
        require(whole(capture.get("retainedRecords"), "retainedRecords") == rows.size() && rows.size() <= budget,
                "Retained event count or budget differs");
        JsonObject rawPlan = object(root.get("filePlan"), "filePlan");
        JsonObject rawFabric = object(root.get("fabric"), "fabric");
        Replay replay = new Replay(new Plan(rawPlan), new Fabric(rawFabric, contract), contract, storeBackedInputs);
        List<FileLifecycleEvent> events = new ArrayList<>();
        double previous = 0;
        for (int index = 0; index < rows.size(); index++) {
            JsonObject row = object(rows.get(index), "event");
            keys(row, "sequence", "observedTime", "type", "payload");
            long sequence = whole(row.get("sequence"), "sequence");
            require(sequence == index + 1L, "Event sequence must be consecutive from one");
            double at = number(row.get("observedTime"), "observedTime");
            require(at >= previous && at <= watermark, "Event observations must be monotonic and within capture");
            FileLifecycleEvent.Type type;
            try {
                type = FileLifecycleEvent.Type.valueOf(text(row.get("type"), "event type"));
            } catch (IllegalArgumentException invalid) {
                throw new IllegalArgumentException("Unsupported file lifecycle event type", invalid);
            }
            require(contract.accepts(type), "Event type is not supported by this lifecycle version");
            JsonObject payload = object(row.get("payload"), "payload");
            replay.event(type, payload, at);
            events.add(FileLifecycleEvent.of(sequence, at, type, payload));
            previous = at;
        }
        replay.finish();
        FileLifecycleEvidence evidence = FileLifecycleEvidence.capture(budget, 0, watermark, shared, rawPlan, rawFabric, events);
        return new Result(evidence, replay);
    }

    private static final class FileKey implements Comparable<FileKey> {
        final int workflow;
        final String name;
        FileKey(int workflow, String name) { this.workflow = workflow; this.name = name; }
        @Override public int compareTo(FileKey other) {
            int order = Integer.compare(workflow, other.workflow);
            return order == 0 ? name.compareTo(other.name) : order;
        }
        @Override public boolean equals(Object other) {
            return other instanceof FileKey && workflow == ((FileKey) other).workflow && name.equals(((FileKey) other).name);
        }
        @Override public int hashCode() { return 31 * workflow + name.hashCode(); }
        @Override public String toString() { return "input[" + workflow + "]/" + name; }
    }

    /** -1 denotes the sole supported typed SOURCE, not a VM/resource string alias. */
    private static final class Location implements Comparable<Location> {
        final int vm;
        Location(int vm) { this.vm = vm; }
        boolean isVm() { return vm >= 0; }
        String resource() { return "VM:" + vm; }
        @Override public int compareTo(Location other) {
            if (isVm() != other.isVm()) return isVm() ? -1 : 1;
            return Integer.compare(vm, other.vm);
        }
        @Override public boolean equals(Object other) { return other instanceof Location && vm == ((Location) other).vm; }
        @Override public int hashCode() { return vm; }
    }

    private static final class FileRow {
        final FileKey id;
        final double bytes;
        final Integer producer;
        FileRow(FileKey id, double bytes, Integer producer) { this.id = id; this.bytes = bytes; this.producer = producer; }
    }

    private static final class TaskRow {
        final int id, workflow;
        final Set<Integer> parents = new LinkedHashSet<>();
        final Map<FileKey, Long> inputs = new TreeMap<>();
        final Set<FileKey> outputs = new TreeSet<>();
        TaskRow(int id, int workflow) { this.id = id; this.workflow = workflow; }
    }

    private static final class Plan {
        final Map<Integer, TaskRow> tasks = new TreeMap<>();
        final Map<FileKey, FileRow> files = new TreeMap<>();

        Plan(JsonObject raw) {
            keys(raw, "contractVersion", "tasks", "files");
            equal(raw, "contractVersion", FileLifecycleCodec.IDENTITY);
            for (JsonElement value : array(raw.get("tasks"), "tasks")) {
                JsonObject row = object(value, "task");
                keys(row, "taskId", "workflowInputIndex", "parents", "inputs", "outputs");
                TaskRow task = new TaskRow(nonnegativeInt(row.get("taskId"), "taskId"),
                        nonnegativeInt(row.get("workflowInputIndex"), "workflowInputIndex"));
                require(tasks.put(task.id, task) == null, "Duplicate Task ID");
                for (JsonElement parent : array(row.get("parents"), "parents")) {
                    int id = nonnegativeInt(parent, "parent Task ID");
                    require(id != task.id && task.parents.add(id), "Self or duplicate control parent");
                }
                for (JsonElement input : array(row.get("inputs"), "inputs")) {
                    JsonObject reference = object(input, "input");
                    keys(reference, "fileId", "referenceCount");
                    FileKey id = fileKey(reference.get("fileId"));
                    long count = positiveLong(reference.get("referenceCount"), "referenceCount");
                    require(id.workflow == task.workflow && task.inputs.put(id, count) == null, "Duplicate or cross-scope input");
                }
                for (JsonElement output : array(row.get("outputs"), "outputs")) {
                    FileKey id = fileKey(output);
                    require(id.workflow == task.workflow && task.outputs.add(id), "Duplicate or cross-scope output");
                    require(!task.inputs.containsKey(id), "In-place files are not write-once");
                }
            }
            for (JsonElement value : array(raw.get("files"), "files")) {
                JsonObject row = object(value, "file");
                keys(row, "fileId", "bytes", "producerTaskId");
                FileKey id = fileKey(row.get("fileId"));
                FileRow file = new FileRow(id, number(row.get("bytes"), "file bytes"),
                        nullableInt(row.get("producerTaskId"), "producerTaskId"));
                require(files.put(id, file) == null, "Duplicate scoped file definition");
            }
            validateGraphAndFiles();
        }

        private void validateGraphAndFiles() {
            Map<Integer, List<Integer>> children = new HashMap<>();
            Map<Integer, Integer> indegrees = new HashMap<>();
            Deque<Integer> ready = new ArrayDeque<>();
            Map<FileKey, Integer> writers = new HashMap<>();
            Set<FileKey> referenced = new HashSet<>();
            for (TaskRow task : tasks.values()) {
                indegrees.put(task.id, task.parents.size());
                if (task.parents.isEmpty()) ready.add(task.id);
                for (int parentId : task.parents) {
                    TaskRow parent = tasks.get(parentId);
                    require(parent != null && parent.workflow == task.workflow, "Unknown or cross-scope control parent");
                    children.computeIfAbsent(parentId, ignored -> new ArrayList<>()).add(task.id);
                }
                for (FileKey id : task.inputs.keySet()) { file(id); referenced.add(id); }
                for (FileKey id : task.outputs) {
                    file(id); referenced.add(id);
                    require(writers.put(id, task.id) == null, "A file has multiple logical writers");
                }
            }
            int visited = 0;
            while (!ready.isEmpty()) {
                int id = ready.removeFirst(); visited++;
                for (int child : children.getOrDefault(id, Collections.emptyList())) {
                    int remaining = indegrees.get(child) - 1;
                    indegrees.put(child, remaining);
                    if (remaining == 0) ready.addLast(child);
                }
            }
            require(visited == tasks.size(), "Control graph contains a cycle");
            require(referenced.equals(files.keySet()), "File definitions must match surviving Task declarations");
            for (FileRow file : files.values()) {
                require(Objects.equals(file.producer, writers.get(file.id)), "File producer differs from its unique writer");
                if (file.producer != null) {
                    TaskRow producer = tasks.get(file.producer);
                    require(producer != null && producer.workflow == file.id.workflow, "Unknown or cross-scope producer");
                }
            }
            // Sparse iterative walks permit ANY control ancestor, not only a direct parent.
            for (TaskRow task : tasks.values()) {
                Set<Integer> required = new HashSet<>();
                for (FileKey id : task.inputs.keySet()) if (file(id).producer != null) required.add(file(id).producer);
                Set<Integer> seen = new HashSet<>();
                Deque<Integer> ancestors = new ArrayDeque<>(task.parents);
                while (!required.isEmpty() && !ancestors.isEmpty()) {
                    int id = ancestors.removeFirst();
                    if (!seen.add(id)) continue;
                    required.remove(id);
                    ancestors.addAll(tasks.get(id).parents);
                }
                require(required.isEmpty(), "Input producer is not a control ancestor");
            }
        }

        FileRow file(FileKey id) {
            FileRow file = files.get(id);
            require(file != null, "Unknown scoped file: " + id);
            return file;
        }
    }

    private static final class Placement {
        final int pod, edge;
        Placement(int pod, int edge) { this.pod = pod; this.edge = edge; }
        String edgeName() { return "EDGE:" + pod + ":" + edge; }
    }

    private static final class Route {
        final List<String> resources;
        final double rate;
        Route(List<String> resources, Map<String, Double> capacities) {
            this.resources = resources;
            Map<String, Integer> multiplicities = new HashMap<>();
            for (String resource : resources) multiplicities.put(resource, multiplicities.getOrDefault(resource, 0) + 1);
            double minimum = Double.MAX_VALUE;
            for (Map.Entry<String, Integer> item : multiplicities.entrySet()) {
                Double capacity = capacities.get(item.getKey());
                require(capacity != null, "Route names an unknown physical resource");
                minimum = Math.min(minimum, capacity / item.getValue());
            }
            require(!resources.isEmpty() && minimum >= Double.MIN_NORMAL && Double.isFinite(minimum), "Unsupported standalone bottleneck");
            rate = minimum;
        }
    }

    private static final class Fabric {
        private static final String STORE_READ = "STORE:source:READ", STORE_WRITE = "STORE:source:WRITE", STORE_NIC = "STORE:source:NIC";
        final Set<Location> locations = new TreeSet<>();
        final Map<String, Double> capacities = new HashMap<>();
        final Map<Integer, Integer> vmHosts = new HashMap<>();
        final Map<Integer, Placement> placements = new HashMap<>();
        final boolean storage;
        final int attachmentHost;
        int k, half, cores;

        Fabric(JsonObject raw, LifecycleContract contract) {
            storage = contract.storage;
            keys(raw, contract.fabricFields);
            Set<Integer> vms = new HashSet<>();
            for (JsonElement row : array(raw.get("locations"), "locations")) {
                Location location = location(row);
                require(locations.add(location), "Duplicate typed location");
                if (location.isVm()) vms.add(location.vm);
            }
            require(locations.contains(SOURCE), "Exactly one default SOURCE is required");
            for (JsonElement value : array(raw.get("resources"), "resources")) {
                JsonObject row = object(value, "physical resource");
                keys(row, "key", "capacityBytesPerSecond");
                String key = text(row.get("key"), "resource key");
                double capacity = capacity(row.get("capacityBytesPerSecond"), "capacityBytesPerSecond");
                require(capacities.put(key, capacity) == null, "Duplicate physical resource");
            }
            Set<String> expected = new HashSet<>();
            for (int vm : vms) expected.add("VM:" + vm);
            if (storage) {
                JsonObject store = object(raw.get("sourceStorage"), "sourceStorage");
                keys(store, "attachmentHostId", "readBandwidthMbPerSecond", "writeBandwidthMbPerSecond", "networkBandwidthMbPerSecond");
                attachmentHost = nonnegativeInt(store.get("attachmentHostId"), "attachmentHostId");
                storeResource(store, "readBandwidthMbPerSecond", STORE_READ);
                storeResource(store, "writeBandwidthMbPerSecond", STORE_WRITE);
                storeResource(store, "networkBandwidthMbPerSecond", STORE_NIC);
                expected.add(STORE_READ); expected.add(STORE_WRITE); expected.add(STORE_NIC);
            } else attachmentHost = -1;
            JsonArray assignments = array(raw.get("vmHostAssignments"), "vmHostAssignments");
            if (isNull(raw.get("topology"))) {
                require(storage || assignments.size() == 0, "V2 endpoint-only fabric cannot carry host assignments");
                // V3 freezes actual hosts even without topology. Platform membership of this
                // attachment and these hosts belongs to the caller's final platform join.
            } else {
                JsonObject topology = object(raw.get("topology"), "topology");
                keys(topology, "kind", "k", "coreSwitchCount", "linkBandwidthBytesPerSecond", "hostPlacements");
                equal(topology, "kind", "FAT_TREE");
                k = nonnegativeInt(topology.get("k"), "k");
                require(k >= 2 && k <= 32 && k % 2 == 0, "Unsupported FatTree size; k must be even in [2,32]");
                half = k / 2;
                cores = nonnegativeInt(topology.get("coreSwitchCount"), "coreSwitchCount");
                require(cores >= 1 && cores <= half * half, "Invalid FatTree core count");
                double bandwidth = capacity(topology.get("linkBandwidthBytesPerSecond"), "link bandwidth");
                JsonArray hosts = array(topology.get("hostPlacements"), "hostPlacements");
                require(hosts.size() > 0 && hosts.size() <= k * half * half, "Invalid FatTree host count");
                int[][] perEdge = new int[k][half];
                for (JsonElement value : hosts) {
                    JsonObject row = object(value, "host placement");
                    keys(row, "hostId", "pod", "edge");
                    int host = nonnegativeInt(row.get("hostId"), "hostId");
                    int pod = nonnegativeInt(row.get("pod"), "pod"), edge = nonnegativeInt(row.get("edge"), "edge");
                    require(pod < k && edge < half, "Host placement lies outside topology");
                    require(++perEdge[pod][edge] <= half, "FatTree edge host capacity exceeded");
                    require(placements.put(host, new Placement(pod, edge)) == null, "Duplicate host placement");
                }
                require(!storage || placements.containsKey(attachmentHost), "Store attachment host is not placed in topology");
                Set<String> links = new HashSet<>();
                for (Map.Entry<Integer, Placement> host : placements.entrySet())
                    pair(links, "ACC:" + host.getKey(), host.getValue().edgeName());
                for (int pod = 0; pod < k; pod++) {
                    for (int edge = 0; edge < half; edge++)
                        for (int agg = 0; agg < half; agg++) pair(links, "EDGE:" + pod + ":" + edge, "AGG:" + pod + ":" + agg);
                    for (int core = 0; core < cores; core++) pair(links, "AGG:" + pod + ":" + (core / half), "CORE:" + core);
                }
                for (String link : links) require(capacities.containsKey(link) && capacities.get(link) == bandwidth,
                        "Missing or inconsistent directed physical link");
                expected.addAll(links);
            }
            if (storage || k != 0) {
                for (JsonElement value : assignments) {
                    JsonObject row = object(value, "VM host assignment");
                    keys(row, "vmId", "hostId");
                    int vm = nonnegativeInt(row.get("vmId"), "vmId"), host = nonnegativeInt(row.get("hostId"), "hostId");
                    require(vms.contains(vm) && (k == 0 || placements.containsKey(host)) && vmHosts.put(vm, host) == null,
                            "Unknown or duplicate VM/host assignment");
                }
                require(vmHosts.keySet().equals(vms), "Actual host assignments must cover exactly the VMs");
            }
            require(capacities.keySet().equals(expected), "Physical resources must exactly match the versioned endpoint, link and store inventory");
        }

        private void storeResource(JsonObject store, String field, String resource) {
            double mb = number(store.get(field), field);
            double bytes = mb * 1_000_000.0; // Same specified binary64 conversion, never integer rounding.
            require(mb > 0 && Double.isFinite(bytes) && bytes >= Double.MIN_NORMAL, "Storage bandwidth must convert to positive normal finite bytes/s");
            require(capacities.containsKey(resource) && capacities.get(resource) == bytes, "Storage resource differs from its exact MB/s conversion");
        }

        Location vm(JsonElement value) {
            Location location = new Location(nonnegativeInt(value, "vmId"));
            require(locations.contains(location), "Unknown VM location");
            return location;
        }

        Location known(JsonElement value) {
            Location location = location(value);
            require(locations.contains(location), "Unknown typed location");
            return location;
        }

        Route route(Location source, Location destination) {
            require(!source.equals(destination) && (storage || destination.isVm()), "A positive route cannot be local or have an unsupported destination");
            List<String> resources = new ArrayList<>();
            if (storage && !source.isVm()) { resources.add(STORE_READ); resources.add(STORE_NIC); }
            else if (source.isVm()) resources.add(source.resource());
            if (k != 0 && (storage || source.isVm())) {
                int srcHost = source.isVm() ? vmHosts.get(source.vm) : attachmentHost;
                int dstHost = destination.isVm() ? vmHosts.get(destination.vm) : attachmentHost;
                appendLinks(resources, srcHost, dstHost);
            }
            if (destination.isVm()) resources.add(destination.resource());
            else { resources.add(STORE_NIC); resources.add(STORE_WRITE); }
            // Only the pinned V2 contract omits SOURCE service and attachment links.
            return new Route(resources, capacities);
        }

        private void appendLinks(List<String> resources, int srcHost, int dstHost) {
            if (srcHost == dstHost) return;
            Placement src = placements.get(srcHost), dst = placements.get(dstHost);
            resources.add(link("ACC:" + srcHost, src.edgeName()));
            if (src.pod != dst.pod || src.edge != dst.edge) {
                int availableAggregates = (cores + half - 1) / half;
                int agg = src.edge % availableAggregates;
                resources.add(link(src.edgeName(), "AGG:" + src.pod + ":" + agg));
                if (src.pod != dst.pod) {
                    int uplinks = Math.min(half, cores - agg * half);
                    int core = agg * half + (src.edge + dst.edge + src.pod + dst.pod) % uplinks;
                    resources.add(link("AGG:" + src.pod + ":" + agg, "CORE:" + core));
                    resources.add(link("CORE:" + core, "AGG:" + dst.pod + ":" + agg));
                }
                resources.add(link("AGG:" + dst.pod + ":" + agg, dst.edgeName()));
            }
            resources.add(link(dst.edgeName(), "ACC:" + dstHost));
        }

        private static String link(String from, String to) { return "LINK:" + from + "->" + to; }
        private static void pair(Set<String> links, String a, String b) { links.add(link(a, b)); links.add(link(b, a)); }
    }

    private static final class Origin {
        final Integer task;
        final Long job;
        final Location location;
        final double at;
        Origin(Integer task, Long job, Location location, double at) { this.task = task; this.job = job; this.location = location; this.at = at; }
        boolean same(Origin other) {
            return Objects.equals(task, other.task) && Objects.equals(job, other.job) && location.equals(other.location) && at == other.at;
        }
    }

    private static final class Replica {
        final FileKey file;
        final Location location, copiedFrom;
        final double at;
        final String acquisition;
        final Origin origin;
        final Long ordinal;
        Replica(FileKey file, Location location, double at, String acquisition, Origin origin, Location copiedFrom, Long ordinal) {
            this.file = file; this.location = location; this.at = at; this.acquisition = acquisition;
            this.origin = origin; this.copiedFrom = copiedFrom; this.ordinal = ordinal;
        }
        boolean same(Replica other) {
            return file.equals(other.file) && location.equals(other.location) && at == other.at && acquisition.equals(other.acquisition)
                    && origin.same(other.origin) && Objects.equals(copiedFrom, other.copiedFrom) && Objects.equals(ordinal, other.ordinal);
        }
    }

    private static final class Target {
        final FileKey file;
        final Location location;
        Target(FileKey file, Location location) { this.file = file; this.location = location; }
        @Override public boolean equals(Object other) {
            return other instanceof Target && file.equals(((Target) other).file) && location.equals(((Target) other).location);
        }
        @Override public int hashCode() { return 31 * file.hashCode() + location.hashCode(); }
    }

    private static final class Copy {
        final long ordinal;
        final FileRow file;
        final Replica source;
        final Location destination;
        final double release, rate, seconds;
        final Job owner;
        final boolean output;
        Copy(long ordinal, FileRow file, Replica source, Location destination, double release, double rate, double seconds, Job owner, boolean output) {
            this.ordinal = ordinal; this.file = file; this.source = source; this.destination = destination;
            this.release = release; this.rate = rate; this.seconds = seconds; this.owner = owner; this.output = output;
        }
        Target target() { return new Target(file.id, destination); }
    }

    private static final class Job {
        final int id;
        final TaskRow task;
        final Location destination;
        final double requestedAt;
        // Initial unclassified references, active target-copy waits, and uncommitted store
        // waits are disjoint. SOURCE visibility alone never satisfies a remote VM input.
        final Set<FileKey> unresolved, pending = new HashSet<>(), waitingStore = new HashSet<>();
        double isolatedTotal;
        boolean ready, started, finished, success;
        Job(int id, TaskRow task, Location destination, double at) {
            this.id = id; this.task = task; this.destination = destination; requestedAt = at;
            unresolved = new HashSet<>(task.inputs.keySet());
        }
    }

    private static final class Choice {
        final Replica replica;
        final Route route;
        Choice(Replica replica, Route route) { this.replica = replica; this.route = route; }
    }

    /** A successful completion publishes every VM output first, then resolves each store obligation. */
    private static final class OutputOperation {
        final Job job;
        final double at;
        final Deque<FileKey> remaining;
        boolean visibleNotified;
        OutputOperation(Job job, double at) {
            this.job = job; this.at = at; remaining = new ArrayDeque<>(job.task.outputs);
        }
    }

    /** Deferred inputs are per Job and file, separate from the same-target copy waiter index. */
    private static final class DeferredInput {
        final Job job;
        final FileKey file;
        DeferredInput(Job job, FileKey file) { this.job = job; this.file = file; }
    }

    private static final class Replay {
        final Plan plan;
        final Fabric fabric;
        final LifecycleContract contract;
        final boolean storeBackedInputs;
        final Map<FileKey, Map<Location, Replica>> replicas = new HashMap<>();
        final Set<FileKey> unseeded = new HashSet<>();
        final Map<Integer, Job> jobs = new LinkedHashMap<>();
        final Set<Integer> successfulTasks = new TreeSet<>();
        final Map<Long, Copy> active = new HashMap<>();
        final Map<Target, Copy> byTarget = new HashMap<>();
        final Map<Target, Set<Integer>> waiters = new HashMap<>();
        final Set<Integer> dueReady = new LinkedHashSet<>();
        final Set<FileKey> pendingOutputs = new HashSet<>();
        final Map<FileKey, Set<Integer>> storeWaiters = new HashMap<>();
        final Deque<DeferredInput> resumes = new ArrayDeque<>();
        boolean runtimeEventSeen;
        int completedJobs, waitingStoreInputs;
        long copyCount, completedCopies;
        double dueAt, resumeAt;
        Job request;
        Copy needsResolution;
        OutputOperation outputOperation;

        Replay(Plan plan, Fabric fabric, LifecycleContract contract, boolean storeBackedInputs) {
            this.plan = plan; this.fabric = fabric; this.contract = contract; this.storeBackedInputs = storeBackedInputs;
            for (FileRow file : plan.files.values()) if (file.producer == null) unseeded.add(file.id);
        }

        void event(FileLifecycleEvent.Type type, JsonObject p, double at) {
            // These are atomic observed operations, not an attempt to infer missing service intervals.
            // READY takes priority inside an output operation or between deferred resolvers.
            if (!dueReady.isEmpty()) {
                require(type == FileLifecycleEvent.Type.JOB_DATA_READY && at == dueAt,
                        "A now-due JOB_DATA_READY must immediately follow its visibility operation");
            } else if (needsResolution != null) {
                require(type == (needsResolution.output ? FileLifecycleEvent.Type.OUTPUT_RESOLVED : FileLifecycleEvent.Type.INPUT_RESOLVED)
                                && at == needsResolution.release,
                        "COPY_ADMITTED must immediately precede its corresponding NEW_COPY resolution");
            } else if (request != null) {
                require(at == request.requestedAt && (type == FileLifecycleEvent.Type.INPUT_RESOLVED
                                || type == FileLifecycleEvent.Type.COPY_ADMITTED
                                || (contract.storage && type == FileLifecycleEvent.Type.INPUT_WAITING_FOR_STORE)),
                        "Every initial input must be resolved or classified waiting at its request observation");
            } else if (!resumes.isEmpty()) {
                require(at == resumeAt && (type == FileLifecycleEvent.Type.INPUT_RESOLVED || type == FileLifecycleEvent.Type.COPY_ADMITTED),
                        "SOURCE visibility must immediately resume its affected inputs at the same observation");
            } else if (outputOperation != null) {
                require(at == outputOperation.at && (type == FileLifecycleEvent.Type.OUTPUT_RESOLVED || type == FileLifecycleEvent.Type.COPY_ADMITTED),
                        "Successful output obligations must resolve within their completion operation");
            }
            if (type != FileLifecycleEvent.Type.EXTERNAL_SEEDED) {
                require(unseeded.isEmpty(), "All external files must be seeded before runtime operations");
                runtimeEventSeen = true;
            }
            switch (type) {
                case EXTERNAL_SEEDED: seed(p, at); break;
                case JOB_INPUT_REQUESTED: request(p, at); break;
                case COPY_ADMITTED: admit(p, at); break;
                case INPUT_RESOLVED: resolve(p, at); break;
                case INPUT_WAITING_FOR_STORE: waitForStore(p, at); break;
                case OUTPUT_RESOLVED: resolveOutput(p, at); break;
                case COPY_SETTLED: settle(p, at); break;
                case JOB_DATA_READY: ready(p, at); break;
                case JOB_CPU_STARTED: start(p); break;
                case TASK_FINISHED: complete(p, at); break;
                default: throw bad("Unsupported lifecycle event");
            }
        }

        private void seed(JsonObject p, double at) {
            keys(p, "fileId", "location");
            FileKey id = fileKey(p.get("fileId"));
            FileRow file = plan.file(id);
            Location location = fabric.known(p.get("location"));
            require(!runtimeEventSeen && at == 0 && file.producer == null && location.equals(SOURCE) && unseeded.remove(id),
                    "External seeding must occur exactly once at SOURCE at time zero");
            publish(new Replica(id, SOURCE, 0, "EXTERNAL_SEED", new Origin(null, null, SOURCE, 0), null, null));
        }

        private void request(JsonObject p, double at) {
            keys(p, "jobId", "taskIds", "destinationVmId");
            int id = nonnegativeInt(p.get("jobId"), "jobId");
            JsonArray members = array(p.get("taskIds"), "taskIds");
            require(members.size() == 1, "Initial NONE clustering requires a singleton logical Task");
            TaskRow task = plan.tasks.get(nonnegativeInt(members.get(0), "taskId"));
            require(task != null && !jobs.containsKey(id), "Unknown Task or reused Job attempt");
            for (int parent : task.parents) require(successfulTasks.contains(parent), "Control parent has no successful logical outcome");
            Location destination = fabric.vm(p.get("destinationVmId"));
            // Canonical plan order, not deferred-resolution arrival order. Never expand multiplicity.
            long references = 0;
            double referenceBytes = 0;
            for (Map.Entry<FileKey, Long> input : task.inputs.entrySet()) {
                require(input.getValue() <= Integer.MAX_VALUE - references, "Requested Job reference count exceeds int32");
                references += input.getValue();
                double bytes = new BigDecimal(plan.file(input.getKey()).bytes).multiply(BigDecimal.valueOf(input.getValue())).doubleValue();
                referenceBytes = finiteSum(referenceBytes, bytes);
            }
            request = new Job(id, task, destination, at);
            jobs.put(id, request);
            endRequestIfResolved(at);
        }

        private void waitForStore(JsonObject p, double at) {
            keys(p, "jobId", "fileId", "referenceCount");
            require(contract.storage && storeBackedInputs && request != null && at == request.requestedAt
                            && nonnegativeInt(p.get("jobId"), "jobId") == request.id,
                    "Store waiting must classify an initial store-backed input request");
            FileRow file = plan.file(fileKey(p.get("fileId")));
            require(request.unresolved.contains(file.id) && visible(file.id, SOURCE) == null,
                    "Store waiting requires an unresolved declared input without committed SOURCE visibility");
            require(positiveLong(p.get("referenceCount"), "referenceCount") == request.task.inputs.get(file.id), "Input reference multiplicity differs");
            request.unresolved.remove(file.id);
            require(request.waitingStore.add(file.id), "Duplicate store waiter");
            storeWaiters.computeIfAbsent(file.id, ignored -> new LinkedHashSet<>()).add(request.id);
            waitingStoreInputs++;
            endRequestIfResolved(at);
        }

        /** Locate the one input operation allowed now; no public runtime/selector is called. */
        private Job inputOwner(FileKey file, double at) {
            Job owner;
            if (request != null) {
                require(at == request.requestedAt && request.unresolved.contains(file), "Input operation is not an unresolved request demand");
                owner = request;
            } else {
                require(contract.storage && !resumes.isEmpty() && at == resumeAt && resumes.peekFirst().file.equals(file),
                        "Deferred input resolution must follow SOURCE visibility in affected-request order");
                owner = resumes.peekFirst().job;
                require(owner.waitingStore.contains(file), "Deferred input is not waiting for SOURCE");
            }
            require(!owner.ready && !owner.started && !owner.finished, "Input copy owner is not pending input preparation");
            require(!storeBackedInputs || visible(file, SOURCE) != null, "SOURCE commitment gates even a target-local cache hit");
            return owner;
        }

        private Job outputOwner(FileKey file, double at) {
            require(contract.storage && outputOperation != null && at == outputOperation.at && outputOperation.visibleNotified
                            && !outputOperation.remaining.isEmpty() && outputOperation.remaining.peekFirst().equals(file),
                    "Output resolution must identify the next declared output of this successful completion");
            Job owner = outputOperation.job;
            require(owner.finished && owner.success && owner.task.outputs.contains(file), "Output owner is not a successful declared producer");
            return owner;
        }

        private void admit(JsonObject p, double at) {
            keys(p, contract.admissionFields);
            require(needsResolution == null, "Admission has an unresolved preceding copy");
            FileRow file = plan.file(fileKey(p.get("fileId")));
            boolean output = false;
            if (contract.storage) {
                String purpose = text(p.get("purpose"), "purpose");
                output = "OUTPUT".equals(purpose);
                require(output || "INPUT".equals(purpose), "Unsupported copy purpose");
            }
            Job owner = output ? outputOwner(file.id, at) : inputOwner(file.id, at);
            if (contract.storage) require(nonnegativeInt(p.get("ownerJobId"), "ownerJobId") == owner.id, "Copy has the wrong input/output owner");
            long ordinal = positiveLong(p.get("copyOrdinal"), "copyOrdinal");
            require(ordinal == copyCount + 1, "Copy ordinals must be consecutive positive integers");
            require(file.bytes > 0 && number(p.get("bytes"), "copy bytes") == file.bytes, "Positive copy bytes differ from file plan");
            Location destination = contract.storage ? fabric.known(p.get("destination")) : fabric.vm(p.get("destinationVmId"));
            Target target = new Target(file.id, destination);
            require(destination.equals(output ? SOURCE : owner.destination) && visible(file.id, destination) == null && !byTarget.containsKey(target),
                    "Copy target differs, is local, or already has an active copy");
            Choice best;
            if (output) {
                Replica producer = visible(file.id, owner.destination);
                require(producer != null, "Output admission lacks the actual producing VM replica");
                best = new Choice(producer, fabric.route(owner.destination, SOURCE));
            } else best = chooseInput(file, destination);
            require(best.replica.same(replica(p.get("sourceReplica"))), "Selected source replica/provenance differs from independent catalog");
            require(best.replica.at <= at, "Copy source is not yet visible");
            List<String> resources = strings(p.get("resources"), "copy resources");
            double rate = capacity(p.get("standaloneRate"), "standaloneRate");
            require(resources.equals(best.route.resources) && rate == best.route.rate, "Selected ordered path or standalone rate differs");
            double seconds = file.bytes / rate;
            double finish = at + seconds;
            require(Double.isFinite(seconds) && seconds > 0 && Double.isFinite(finish) && finish > at,
                    "Positive isolated duration/finish cannot advance the finite binary64 clock");
            require(number(p.get("isolatedSeconds"), "isolatedSeconds") == seconds, "Isolated duration differs from bytes/bottleneck");
            Copy copy = new Copy(ordinal, file, best.replica, destination, at, rate, seconds, owner, output);
            active.put(ordinal, copy); byTarget.put(target, copy); copyCount++;
            needsResolution = copy;
        }

        private void resolve(JsonObject p, double at) {
            keys(p, "jobId", "fileId", "referenceCount", "resolution", "copyOrdinal", "source");
            FileRow file = plan.file(fileKey(p.get("fileId")));
            Job owner = inputOwner(file.id, at);
            require(nonnegativeInt(p.get("jobId"), "jobId") == owner.id, "Input resolution has the wrong request/resume owner");
            require(positiveLong(p.get("referenceCount"), "referenceCount") == owner.task.inputs.get(file.id), "Input reference multiplicity differs");
            Location source = fabric.known(p.get("source"));
            String resolution = text(p.get("resolution"), "resolution");
            Replica local = visible(file.id, owner.destination);
            Copy copy = null;
            if (needsResolution != null) require(!needsResolution.output && "NEW_COPY".equals(resolution)
                            && needsResolution.file.id.equals(file.id) && needsResolution.owner == owner,
                    "Admission must resolve its corresponding NEW_COPY input demand immediately");
            switch (resolution) {
                case "LOCAL":
                    require(isNull(p.get("copyOrdinal")) && local != null && source.equals(owner.destination), "LOCAL requires the visible target and no copy ordinal");
                    break;
                case "ZERO":
                    require(isNull(p.get("copyOrdinal")) && file.bytes == 0 && local == null, "ZERO is only a nonlocal zero-byte acquisition");
                    Replica selected = chooseInput(file, owner.destination).replica;
                    require(source.equals(selected.location), "Zero-byte source differs from the versioned visible-source selection");
                    publish(new Replica(file.id, owner.destination, at, "ZERO_BYTE_REFERENCE", selected.origin, source, null));
                    break;
                case "NEW_COPY":
                    long newOrdinal = positiveLong(p.get("copyOrdinal"), "copyOrdinal");
                    copy = needsResolution;
                    require(copy != null && !copy.output && copy.ordinal == newOrdinal && copy.file.id.equals(file.id) && copy.owner == owner
                                    && copy.destination.equals(owner.destination) && source.equals(copy.source.location) && local == null,
                            "NEW_COPY must identify the immediately preceding frozen input admission");
                    needsResolution = null;
                    break;
                case "JOIN_EXISTING":
                    long joinedOrdinal = positiveLong(p.get("copyOrdinal"), "copyOrdinal");
                    copy = active.get(joinedOrdinal);
                    require(file.bytes > 0 && local == null && copy != null && !copy.output
                                    && copy == byTarget.get(new Target(file.id, owner.destination)) && source.equals(copy.source.location),
                            "JOIN_EXISTING must reuse the active same-target input ticket and its frozen source");
                    break;
                default: throw bad("Unsupported input resolution");
            }
            if (copy != null) {
                owner.pending.add(file.id);
                // Nominal input seconds follow actual resolution order, unlike reference-byte totals.
                owner.isolatedTotal = finiteSum(owner.isolatedTotal, copy.seconds);
                waiters.computeIfAbsent(copy.target(), ignored -> new LinkedHashSet<>()).add(owner.id);
            }
            if (request == owner) {
                owner.unresolved.remove(file.id);
                endRequestIfResolved(at);
            } else {
                require(owner.waitingStore.remove(file.id), "Deferred input waiter is inconsistent");
                resumes.removeFirst(); waitingStoreInputs--;
                if (inputsSatisfied(owner)) makeReadyDue(owner, at);
                continueOutputs();
            }
        }

        private void endRequestIfResolved(double at) {
            if (!request.unresolved.isEmpty()) return;
            Job job = request; request = null;
            if (inputsSatisfied(job)) makeReadyDue(job, at);
        }

        private void resolveOutput(JsonObject p, double at) {
            keys(p, "jobId", "taskId", "fileId", "resolution", "copyOrdinal", "source");
            FileRow file = plan.file(fileKey(p.get("fileId")));
            Job owner = outputOwner(file.id, at);
            require(nonnegativeInt(p.get("jobId"), "jobId") == owner.id && nonnegativeInt(p.get("taskId"), "taskId") == owner.task.id,
                    "Output resolution has the wrong successful Job/Task owner");
            Location source = fabric.known(p.get("source"));
            Replica producer = visible(file.id, owner.destination), stored = visible(file.id, SOURCE);
            require(source.equals(owner.destination) && producer != null, "Output source must be the actual successful producing VM");
            String resolution = text(p.get("resolution"), "resolution");
            if (needsResolution != null) require(needsResolution.output && "NEW_COPY".equals(resolution)
                            && needsResolution.file.id.equals(file.id) && needsResolution.owner == owner,
                    "Output admission must immediately resolve its corresponding NEW_COPY obligation");
            boolean published = false;
            switch (resolution) {
                case "ALREADY_STORED":
                    require(isNull(p.get("copyOrdinal")) && stored != null, "ALREADY_STORED needs an actual committed SOURCE replica and no ordinal");
                    pendingOutputs.remove(file.id);
                    break;
                case "ZERO":
                    require(isNull(p.get("copyOrdinal")) && file.bytes == 0 && stored == null, "ZERO output must be an uncommitted zero-byte file");
                    publish(new Replica(file.id, SOURCE, at, "ZERO_BYTE_OUTPUT", producer.origin, source, null));
                    pendingOutputs.remove(file.id); published = true;
                    break;
                case "NEW_COPY":
                    long ordinal = positiveLong(p.get("copyOrdinal"), "copyOrdinal");
                    Copy copy = needsResolution;
                    require(stored == null && copy != null && copy.output && copy.ordinal == ordinal && copy.owner == owner
                                    && copy.file.id.equals(file.id) && copy.destination.equals(SOURCE) && copy.source.location.equals(source),
                            "Output NEW_COPY must identify its immediately preceding owned upload");
                    needsResolution = null;
                    break;
                case "JOIN_EXISTING":
                    long joined = positiveLong(p.get("copyOrdinal"), "copyOrdinal");
                    Copy existing = active.get(joined);
                    require(file.bytes > 0 && stored == null && existing != null && existing.output
                                    && existing == byTarget.get(new Target(file.id, SOURCE)),
                            "Output JOIN_EXISTING must reuse the active same-file SOURCE upload");
                    // The event's source is THIS completion's VM; the joined ticket retains its
                    // original owner's VM and first source provenance, which may be different.
                    break;
                default: throw bad("Unsupported output resolution");
            }
            outputOperation.remaining.removeFirst(); outputOperation.visibleNotified = false;
            if (published) resumeStoreInputs(file.id, at);
            continueOutputs();
        }

        private void settle(JsonObject p, double at) {
            keys(p, "copyOrdinal", "effectiveTime", "remainingAfterService");
            Copy copy = active.get(positiveLong(p.get("copyOrdinal"), "copyOrdinal"));
            require(copy != null, "Settlement must identify a unique active copy");
            double effective = number(p.get("effectiveTime"), "effectiveTime");
            double residual = number(p.get("remainingAfterService"), "remainingAfterService");
            require(at > copy.release && effective > copy.release && effective <= at, "Settlement clocks must follow release and precede observation");
            double tolerance = Math.min(copy.file.bytes * .5, Math.max(copy.file.bytes * 1e-9, 4 * Math.ulp(copy.file.bytes)));
            require(residual <= tolerance, "Settlement residual exceeds the scalar numerical completion bound");
            // Necessary isolated lower bound, NOT a replay of contended service area. In
            // particular, standaloneRate * elapsed need not be finite for contended service.
            double lower = copy.release + ((copy.file.bytes - residual) / copy.rate);
            double allowed = Math.min(8 * Math.max(Math.ulp(copy.release), Math.max(Math.ulp(effective), Math.ulp(lower))),
                    1e-12 * Math.max(copy.release, Math.max(effective, lower)));
            require(Double.isFinite(lower) && (lower <= effective || lower - effective <= allowed), "Settlement is earlier than the checked binary64 lower bound");
            active.remove(copy.ordinal); byTarget.remove(copy.target()); completedCopies++;
            publish(new Replica(copy.file.id, copy.destination, at, "COPY_SETTLEMENT", copy.source.origin, copy.source.location, copy.ordinal));
            visibleToWaiters(copy.file.id, copy.destination, at);
            if (contract.storage && copy.destination.equals(SOURCE)) {
                require(copy.output && pendingOutputs.remove(copy.file.id), "SOURCE settlement must discharge an actual successful output obligation");
                resumeStoreInputs(copy.file.id, at);
            }
        }

        private void resumeStoreInputs(FileKey file, double at) {
            require(resumes.isEmpty() && visible(file, SOURCE) != null, "Inconsistent SOURCE resume operation");
            Set<Integer> ids = storeWaiters.remove(file);
            if (ids == null) return;
            resumeAt = at;
            for (int id : ids) {
                Job job = jobs.get(id);
                require(storeBackedInputs && job.waitingStore.contains(file), "Inconsistent deferred file waiter");
                resumes.addLast(new DeferredInput(job, file));
            }
        }

        private void ready(JsonObject p, double at) {
            keys(p, "jobId");
            Job job = job(p.get("jobId"));
            if (contract.storage) require(!dueReady.isEmpty() && dueReady.iterator().next() == job.id,
                    "Storage readiness must retain deterministic affected-waiter order");
            require(dueReady.remove(job.id) && at == dueAt && !job.ready && inputsSatisfied(job) && allVisible(job),
                    "JOB_DATA_READY must record exactly a now-due all-input visibility release");
            job.ready = true;
            continueOutputs();
        }

        private void start(JsonObject p) {
            keys(p, "jobId", "vmId");
            Job job = job(p.get("jobId"));
            require(job.destination.equals(fabric.vm(p.get("vmId"))) && job.ready && !job.started && !job.finished && allVisible(job),
                    "CPU start requires an already-recorded READY, visible inputs and the fixed VM");
            job.started = true;
        }

        private void complete(JsonObject p, double at) {
            keys(p, "taskId", "jobId", "vmId", "success");
            Job job = job(p.get("jobId"));
            int taskId = nonnegativeInt(p.get("taskId"), "taskId");
            Location location = fabric.vm(p.get("vmId"));
            boolean success = bool(p.get("success"), "success");
            require(job.started && !job.finished && taskId == job.task.id && location.equals(job.destination),
                    "Task completion must match its submitted singleton Job attempt and actual VM");
            job.finished = true; job.success = success; completedJobs++;
            if (!success) return;
            successfulTasks.add(taskId);
            Origin origin = new Origin(taskId, (long) job.id, location, at);
            for (FileKey file : job.task.outputs) publish(new Replica(file, location, at, "TASK_OUTPUT", origin, null, null));
            if (contract.storage) {
                // Establish ALL obligations now, including zero and unused outputs. An omitted
                // output operation is invalid even when it would have admitted no positive copy.
                for (FileKey file : job.task.outputs) if (visible(file, SOURCE) == null) pendingOutputs.add(file);
                outputOperation = new OutputOperation(job, at);
                continueOutputs();
            } else {
                for (FileKey file : job.task.outputs) visibleToWaiters(file, location, at);
            }
        }

        private void continueOutputs() {
            if (outputOperation == null || !resumes.isEmpty() || !dueReady.isEmpty() || needsResolution != null) return;
            if (outputOperation.remaining.isEmpty()) { outputOperation = null; return; }
            if (!outputOperation.visibleNotified) {
                outputOperation.visibleNotified = true;
                visibleToWaiters(outputOperation.remaining.peekFirst(), outputOperation.job.destination, outputOperation.at);
            }
        }

        private Job job(JsonElement value) {
            Job job = jobs.get(nonnegativeInt(value, "jobId"));
            require(job != null, "Unknown Job attempt");
            return job;
        }

        private Replica visible(FileKey file, Location location) {
            Map<Location, Replica> holders = replicas.get(file);
            return holders == null ? null : holders.get(location);
        }

        private void publish(Replica proposal) {
            Map<Location, Replica> holders = replicas.computeIfAbsent(proposal.file, ignored -> new TreeMap<>());
            // A later output/settlement cannot rewrite the first visible acquisition or root origin.
            if (!holders.containsKey(proposal.location)) holders.put(proposal.location, proposal);
        }

        private void visibleToWaiters(FileKey file, Location location, double at) {
            Set<Integer> ids = waiters.remove(new Target(file, location));
            if (ids == null) return;
            for (int id : ids) {
                Job job = jobs.get(id);
                require(job.pending.remove(file), "Inconsistent file waiter");
                if (inputsSatisfied(job)) makeReadyDue(job, at);
            }
        }

        private boolean inputsSatisfied(Job job) { return job.pending.isEmpty() && job.unresolved.isEmpty() && job.waitingStore.isEmpty(); }

        private void makeReadyDue(Job job, double at) {
            require(!job.ready && !dueReady.contains(job.id) && allVisible(job), "Inconsistent all-input readiness");
            require(dueReady.isEmpty() || dueAt == at, "Readiness observations differ within one operation");
            dueReady.add(job.id); dueAt = at;
        }

        private boolean allVisible(Job job) {
            for (FileKey file : job.task.inputs.keySet())
                if (visible(file, job.destination) == null || (storeBackedInputs && visible(file, SOURCE) == null)) return false;
            return true;
        }

        private Choice chooseInput(FileRow file, Location destination) {
            if (storeBackedInputs) {
                Replica store = visible(file.id, SOURCE);
                require(store != null, "Store-backed input has no committed SOURCE replica");
                return new Choice(store, file.bytes == 0 ? null : fabric.route(SOURCE, destination));
            }
            Map<Location, Replica> holders = replicas.get(file.id);
            require(holders != null && !holders.isEmpty(), "Input has no published source replica");
            Choice best = null;
            for (Replica replica : holders.values()) {
                Route route = file.bytes == 0 ? null : fabric.route(replica.location, destination);
                if (best == null || (file.bytes > 0 && route.rate > best.route.rate)
                        || ((file.bytes == 0 || route.rate == best.route.rate) && replica.location.compareTo(best.replica.location) < 0))
                    best = new Choice(replica, route);
            }
            return best;
        }

        private Replica replica(JsonElement value) {
            JsonObject row = object(value, "sourceReplica");
            keys(row, "fileId", "location", "visibleAt", "acquisition", "origin", "copiedFrom", "copyOrdinal");
            FileKey file = fileKey(row.get("fileId")); plan.file(file);
            Location location = fabric.known(row.get("location"));
            double at = number(row.get("visibleAt"), "visibleAt");
            String acquisition = text(row.get("acquisition"), "acquisition");
            require(contract.acceptsAcquisition(acquisition), "Unknown replica acquisition for this lifecycle version");
            JsonObject root = object(row.get("origin"), "replica origin");
            keys(root, "producerTaskId", "jobAttemptId", "location", "observedAt");
            Integer task = nullableInt(root.get("producerTaskId"), "origin producerTaskId");
            Long job = isNull(root.get("jobAttemptId")) ? null : nonnegativeLong(root.get("jobAttemptId"), "origin jobAttemptId");
            Origin origin = new Origin(task, job, fabric.known(root.get("location")), number(root.get("observedAt"), "origin observedAt"));
            Location copiedFrom = isNull(row.get("copiedFrom")) ? null : fabric.known(row.get("copiedFrom"));
            Long ordinal = isNull(row.get("copyOrdinal")) ? null : positiveLong(row.get("copyOrdinal"), "replica copyOrdinal");
            return new Replica(file, location, at, acquisition, origin, copiedFrom, ordinal);
        }

        void finish() {
            require(unseeded.isEmpty(), "Complete capture omits initial external seeding");
            require(request == null && needsResolution == null, "Capture ends inside an atomic input request or copy admission");
            require(outputOperation == null, "Capture ends inside a successful output operation");
            require(resumes.isEmpty(), "Capture omits SOURCE-triggered deferred input resolutions");
            require(dueReady.isEmpty(), "Capture omits an immediately due JOB_DATA_READY");
        }
    }

    private static FileKey fileKey(JsonElement value) {
        JsonObject row = object(value, "fileId");
        keys(row, "workflowInputIndex", "name");
        int workflow = nonnegativeInt(row.get("workflowInputIndex"), "workflowInputIndex");
        String name = text(row.get("name"), "file name");
        require(!name.isEmpty(), "File name must be nonempty (and is never split or renamed)");
        return new FileKey(workflow, name);
    }

    private static Location location(JsonElement value) {
        JsonObject row = object(value, "location");
        keys(row, "kind", "vmId", "sourceId");
        String kind = text(row.get("kind"), "location kind");
        if ("VM".equals(kind)) {
            require(isNull(row.get("sourceId")), "VM sourceId must be null");
            return new Location(nonnegativeInt(row.get("vmId"), "location vmId"));
        }
        require("SOURCE".equals(kind) && isNull(row.get("vmId")), "Unsupported typed location");
        equal(row, "sourceId", "source");
        return SOURCE;
    }

    private static JsonObject object(JsonElement value, String name) {
        require(value != null && value.isJsonObject(), "Expected object: " + name);
        return value.getAsJsonObject();
    }
    private static JsonArray array(JsonElement value, String name) {
        require(value != null && value.isJsonArray(), "Expected array: " + name);
        return value.getAsJsonArray();
    }
    private static String text(JsonElement value, String name) {
        require(value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isString(), "Expected string: " + name);
        return value.getAsString();
    }
    private static boolean bool(JsonElement value, String name) {
        require(value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isBoolean(), "Expected boolean: " + name);
        return value.getAsBoolean();
    }
    private static boolean isNull(JsonElement value) {
        require(value != null, "Required nullable field is missing");
        return value.isJsonNull();
    }
    private static BigDecimal decimal(JsonElement value, String name) {
        require(value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isNumber(), "Expected number: " + name);
        String token = value.getAsString();
        require(token.length() <= MAX_NUMBER_DIGITS, "Numeric token is too long: " + name);
        try {
            BigDecimal number = new BigDecimal(token);
            require(number.precision() <= MAX_NUMBER_DIGITS && Math.abs((long) number.scale()) <= MAX_NUMBER_DIGITS, "Numeric precision/exponent exceeds limit: " + name);
            return number;
        } catch (NumberFormatException invalid) {
            throw new IllegalArgumentException("Invalid numeric token: " + name, invalid);
        }
    }
    private static double number(JsonElement value, String name) {
        BigDecimal number = decimal(value, name);
        double result = number.doubleValue();
        require(Double.isFinite(result) && result >= 0 && (result != 0 || number.signum() == 0), "Unsupported nonnegative binary64 value: " + name);
        return result == 0 ? 0 : result;
    }
    private static double capacity(JsonElement value, String name) {
        double result = number(value, name);
        require(result >= Double.MIN_NORMAL, "Physical capacities/rates must be positive normal binary64 values: " + name);
        return result;
    }
    private static long whole(JsonElement value, String name) {
        try { return decimal(value, name).longValueExact(); }
        catch (ArithmeticException invalid) { throw new IllegalArgumentException("Expected exact int64: " + name, invalid); }
    }
    private static long nonnegativeLong(JsonElement value, String name) {
        long result = whole(value, name); require(result >= 0, "Expected nonnegative int64: " + name); return result;
    }
    private static long positiveLong(JsonElement value, String name) {
        long result = whole(value, name); require(result > 0, "Expected positive int64: " + name); return result;
    }
    private static int nonnegativeInt(JsonElement value, String name) {
        try {
            int result = decimal(value, name).intValueExact();
            require(result >= 0, "Expected nonnegative int32: " + name); return result;
        } catch (ArithmeticException invalid) { throw new IllegalArgumentException("Expected exact int32: " + name, invalid); }
    }
    private static Integer nullableInt(JsonElement value, String name) { return isNull(value) ? null : nonnegativeInt(value, name); }
    private static List<String> strings(JsonElement value, String name) {
        List<String> result = new ArrayList<>();
        for (JsonElement item : array(value, name)) result.add(text(item, name));
        return result;
    }
    private static double finiteSum(double a, double b) {
        double sum = a + b;
        require(Double.isFinite(b) && b >= 0 && Double.isFinite(sum), "Requested Job aggregate is not representable");
        return sum;
    }
    private static void keys(JsonObject object, String... names) {
        require(object != null && object.keySet().equals(new HashSet<>(Arrays.asList(names))), "Missing/unknown object fields; expected " + Arrays.toString(names));
    }
    private static void keys(JsonObject object, Set<String> names) {
        require(object != null && object.keySet().equals(names), "Missing/unknown object fields; expected " + names);
    }
    private static void equal(JsonObject object, String field, String expected) {
        require(expected.equals(text(object.get(field), field)), "Unsupported " + field);
    }
    private static void require(boolean condition, String message) { if (!condition) throw bad(message); }
    private static IllegalArgumentException bad(String message) { return new IllegalArgumentException("Invalid file lifecycle: " + message); }
}
