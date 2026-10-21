package org.workflowsim.grouped;

import com.google.gson.*;
import java.util.*;

/** Validated, fixed-workload input. Units are seconds, bytes, and bytes/second. */
public final class Scenario {
    final String id;
    final Task[] tasks;
    final Host[] hosts;
    final DataFile[] files;
    final int[] topo, topoPosition;
    final int[][] children, inputsOfTask, outputsOfTask;
    final BitSet[] descendants, ancestors;
    final double storageBandwidth, jobSetup;
    final boolean sharedIo;
    final JsonObject original;
    final Map<String, Integer> taskIndex, hostIndex;

    static final class Task {
        final String id, domain;
        final int cores;
        final long memory;
        final double bootstrap;
        final double[] duration;
        final int[] parents;

        Task(String id, String domain, int cores, long memory, double bootstrap, double[] duration, int[] parents) {
            this.id = id;
            this.domain = domain;
            this.cores = cores;
            this.memory = memory;
            this.bootstrap = bootstrap;
            this.duration = duration;
            this.parents = parents;
        }
    }

    static final class Host {
        final String id;
        final int cores;
        final long memory;
        final double bandwidth;

        Host(String id, int cores, long memory, double bandwidth) {
            this.id = id;
            this.cores = cores;
            this.memory = memory;
            this.bandwidth = bandwidth;
        }
    }

    static final class DataFile {
        final String id;
        final int producer;
        final int[] consumers;
        final double bytes;
        final boolean retain;

        DataFile(String id, int producer, int[] consumers, double bytes, boolean retain) {
            this.id = id;
            this.producer = producer;
            this.consumers = consumers;
            this.bytes = bytes;
            this.retain = retain;
        }
    }

    public Scenario(JsonObject input) {
        original = input.deepCopy();
        require("r2-scenario-v1".equals(string(input, "schema")), "unsupported scenario schema");
        id = string(input, "id");
        storageBandwidth = positive(input, "storage_bandwidth_bytes_per_second");
        jobSetup = optionalNonnegative(input, "job_setup_seconds", 0);
        sharedIo = !input.has("shared_io") || input.get("shared_io").getAsBoolean();
        JsonArray hs = array(input, "hosts"), ts = array(input, "tasks"), fs = array(input, "files");
        require(hs.size() > 0 && ts.size() > 0, "nonempty hosts and tasks required");
        hostIndex = new LinkedHashMap<>();
        taskIndex = new LinkedHashMap<>();
        hosts = new Host[hs.size()];
        tasks = new Task[ts.size()];
        parseHosts(hs);
        indexTasks(ts);
        List<List<Integer>> childLists = newChildLists();
        parseTasks(ts, childLists);
        children = childrenOf(childLists);
        TopologicalOrder order = topologicalOrder();
        topo = order.order;
        topoPosition = order.position;
        descendants = new BitSet[tasks.length];
        ancestors = new BitSet[tasks.length];
        fillReachability();
        files = new DataFile[fs.size()];
        FileIndexing indexed = parseFiles(fs);
        inputsOfTask = new int[tasks.length][];
        outputsOfTask = new int[tasks.length][];
        for (int t = 0; t < tasks.length; t++) {
            inputsOfTask[t] = ints(indexed.taskInputs.get(t));
            outputsOfTask[t] = ints(indexed.taskOutputs.get(t));
        }
    }

    /** Parse and validate hosts; fills hosts[] and hostIndex. */
    private void parseHosts(JsonArray hs) {
        for (int i = 0; i < hs.size(); i++) {
            JsonObject h = hs.get(i).getAsJsonObject();
            String name = string(h, "id");
            require(hostIndex.put(name, i) == null, "duplicate host " + name);
            hosts[i] = new Host(
                    name,
                    positiveInt(h, "cores"),
                    positiveLong(h, "memory_bytes"),
                    positive(h, "bandwidth_bytes_per_second"));
        }
    }

    /** Index task ids. */
    private void indexTasks(JsonArray ts) {
        for (int i = 0; i < ts.size(); i++) {
            String name = string(ts.get(i).getAsJsonObject(), "id");
            require(taskIndex.put(name, i) == null, "duplicate task " + name);
        }
    }

    /** Empty child adjacency lists. */
    private List<List<Integer>> newChildLists() {
        List<List<Integer>> childLists = new ArrayList<>();
        for (int i = 0; i < tasks.length; i++) childLists.add(new ArrayList<Integer>());
        return childLists;
    }

    /** Parse and validate tasks; fills tasks[] and parent->child edges. */
    private void parseTasks(JsonArray ts, List<List<Integer>> childLists) {
        for (int i = 0; i < tasks.length; i++) {
            JsonObject t = ts.get(i).getAsJsonObject();
            String name = string(t, "id");
            int cores = positiveInt(t, "cores");
            long memory = nonnegativeLong(t, "memory_bytes");
            int[] parents = references(array(t, "parents"), taskIndex, "parent");
            for (int p : parents) {
                require(p != i, "self dependency " + name);
                childLists.get(p).add(i);
            }
            double[] durations = new double[hosts.length];
            Arrays.fill(durations, Double.NaN);
            JsonObject ds = t.getAsJsonObject("duration_seconds");
            require(ds != null, "duration_seconds required");
            boolean feasible = false;
            for (Map.Entry<String, JsonElement> entry : ds.entrySet()) {
                Integer h = hostIndex.get(entry.getKey());
                require(h != null, "unknown duration host " + entry.getKey());
                double d = entry.getValue().getAsDouble();
                finiteNonnegative(d, "duration");
                durations[h] = d;
                if (cores <= hosts[h].cores && memory <= hosts[h].memory) feasible = true;
            }
            require(feasible, "task cannot fit any compatible host: " + name);
            String domain = t.has("package_domain") ? string(t, "package_domain") : "default";
            tasks[i] = new Task(
                    name, domain, cores, memory, optionalNonnegative(t, "bootstrap_seconds", 0), durations, parents);
        }
    }

    /** Compact child adjacency. */
    private int[][] childrenOf(List<List<Integer>> childLists) {
        int[][] children = new int[tasks.length][];
        for (int i = 0; i < tasks.length; i++) children[i] = ints(childLists.get(i));
        return children;
    }

    /** Deterministic topological order (lexicographic tie-break). */
    private TopologicalOrder topologicalOrder() {
        int[] degrees = new int[tasks.length];
        PriorityQueue<Integer> ready = new PriorityQueue<>((a, b) -> tasks[a].id.compareTo(tasks[b].id));
        for (int i = 0; i < tasks.length; i++) {
            degrees[i] = tasks[i].parents.length;
            if (degrees[i] == 0) ready.add(i);
        }
        int[] topo = new int[tasks.length];
        int[] topoPosition = new int[tasks.length];
        int done = 0;
        while (!ready.isEmpty()) {
            int t = ready.remove();
            topoPosition[t] = done;
            topo[done++] = t;
            for (int c : children[t]) if (--degrees[c] == 0) ready.add(c);
        }
        require(done == tasks.length, "task dependency cycle");
        return new TopologicalOrder(topo, topoPosition);
    }

    /** Fill transitive descendants/ancestors bitsets in reverse topological order. */
    private void fillReachability() {
        for (int i = 0; i < tasks.length; i++) {
            descendants[i] = new BitSet(tasks.length);
            ancestors[i] = new BitSet(tasks.length);
        }
        for (int k = topo.length - 1; k >= 0; k--) {
            int t = topo[k];
            for (int c : children[t]) {
                descendants[t].set(c);
                descendants[t].or(descendants[c]);
            }
        }
        for (int t : topo)
            for (int p : tasks[t].parents) {
                ancestors[t].set(p);
                ancestors[t].or(ancestors[p]);
            }
    }

    /** Parse and validate files; returns per-task input/output file index lists. */
    private FileIndexing parseFiles(JsonArray fs) {
        Set<String> names = new HashSet<>();
        List<List<Integer>> taskInputs = new ArrayList<>(), taskOutputs = new ArrayList<>();
        for (int t = 0; t < tasks.length; t++) {
            taskInputs.add(new ArrayList<Integer>());
            taskOutputs.add(new ArrayList<Integer>());
        }
        for (int i = 0; i < files.length; i++) {
            JsonObject f = fs.get(i).getAsJsonObject();
            String name = string(f, "id");
            require(names.add(name), "duplicate file " + name);
            int producer = -1;
            if (f.has("producer") && !f.get("producer").isJsonNull()) {
                String p = f.get("producer").getAsString();
                require(taskIndex.containsKey(p), "unknown file producer " + p);
                producer = taskIndex.get(p);
            }
            int[] consumers = references(array(f, "consumers"), taskIndex, "consumer");
            double bytes = nonnegative(f, "size_bytes");
            boolean retain = f.has("retain") && f.get("retain").getAsBoolean();
            for (int c : consumers)
                if (producer >= 0)
                    require(descendants[producer].get(c), "file producer must precede consumer: " + name);
            files[i] = new DataFile(name, producer, consumers, bytes, retain);
            if (producer >= 0) taskOutputs.get(producer).add(i);
            for (int c : consumers) taskInputs.get(c).add(i);
        }
        return new FileIndexing(taskInputs, taskOutputs);
    }

    /** Ctor helper carrier: topological order arrays. */
    private static final class TopologicalOrder {
        final int[] order, position;

        TopologicalOrder(int[] order, int[] position) {
            this.order = order;
            this.position = position;
        }
    }

    /** Ctor helper carrier: per-task input/output file lists. */
    private static final class FileIndexing {
        final List<List<Integer>> taskInputs, taskOutputs;

        FileIndexing(List<List<Integer>> taskInputs, List<List<Integer>> taskOutputs) {
            this.taskInputs = taskInputs;
            this.taskOutputs = taskOutputs;
        }
    }

    public String getId() {
        return id;
    }

    public int taskCount() {
        return tasks.length;
    }

    public int hostCount() {
        return hosts.length;
    }

    public JsonObject toJson() {
        return original.deepCopy();
    }

    static int[] references(JsonArray a, Map<String, Integer> index, String role) {
        int[] result = new int[a.size()];
        Set<Integer> seen = new HashSet<>();
        for (int i = 0; i < a.size(); i++) {
            String name = a.get(i).getAsString();
            Integer value = index.get(name);
            require(value != null, "unknown " + role + " " + name);
            require(seen.add(value), "duplicate " + role + " " + name);
            result[i] = value;
        }
        return result;
    }

    static String string(JsonObject o, String key) {
        require(o.has(key) && !o.get(key).isJsonNull(), "missing " + key);
        String s = o.get(key).getAsString();
        require(!s.trim().isEmpty(), "empty " + key);
        return s;
    }

    static JsonArray array(JsonObject o, String key) {
        require(o.has(key) && o.get(key).isJsonArray(), "array required: " + key);
        return o.getAsJsonArray(key);
    }

    static void require(boolean condition, String message) {
        if (!condition) throw new IllegalArgumentException(message);
    }

    static double finiteNonnegative(double n, String label) {
        require(Double.isFinite(n) && n >= 0, "nonnegative finite " + label + " required");
        return n;
    }

    static double nonnegative(JsonObject o, String key) {
        require(o.has(key), "missing " + key);
        return finiteNonnegative(o.get(key).getAsDouble(), key);
    }

    static double positive(JsonObject o, String key) {
        double n = nonnegative(o, key);
        require(n > 0, "positive " + key + " required");
        return n;
    }

    static double optionalNonnegative(JsonObject o, String key, double fallback) {
        return o.has(key) ? nonnegative(o, key) : fallback;
    }

    static long nonnegativeLong(JsonObject o, String key) {
        require(o.has(key), "missing " + key);
        try {
            long n = o.get(key).getAsBigDecimal().longValueExact();
            require(n >= 0, "nonnegative " + key + " required");
            return n;
        } catch (ArithmeticException e) {
            throw new IllegalArgumentException("exact integer required: " + key, e);
        }
    }

    static long positiveLong(JsonObject o, String key) {
        long n = nonnegativeLong(o, key);
        require(n > 0, "positive " + key + " required");
        return n;
    }

    static int positiveInt(JsonObject o, String key) {
        long n = positiveLong(o, key);
        require(n <= Integer.MAX_VALUE, "integer overflow: " + key);
        return (int) n;
    }

    static int[] ints(Collection<Integer> values) {
        int[] a = new int[values.size()];
        int k = 0;
        for (int n : values) a[k++] = n;
        return a;
    }
}
