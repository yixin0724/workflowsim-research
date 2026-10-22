package org.workflowsim.grouped;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.TimeZone;
import java.util.TreeMap;
import java.util.TreeSet;

import org.cloudbus.cloudsim.Log;
import org.cloudbus.cloudsim.core.CloudSim;
import org.cloudbus.cloudsim.core.SimEntity;
import org.cloudbus.cloudsim.core.SimEvent;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

/**
 * Fixed-request, sequential file packages on the CloudSim event kernel.
 * Runs must be serialized within a JVM; independent processes may run concurrently.
 * The standard WorkflowSim task-level lifecycle is not used by this execution model.
 */
public final class GroupedSimulator {
    private GroupedSimulator() {}

    private static final int TICK = 43101, SETUP_DONE = 1, TASK_DONE = 2;
    private static final int WAIT = 0, SETUP = 1, READ = 2, COMPUTE = 3, WRITE = 4, DONE = 5;

    public static final class Result {
        public final double makespan;
        public final double inputBytes, outputBytes, reservedCoreSeconds, reservedMemoryByteSeconds;
        public final double declaredComputeCoreSeconds, declaredComputeMemoryByteSeconds;
        final double[] start, finish, ready, inputEnd, outputStart, taskStart, taskFinish;
        final Set<Integer>[] blockers;
        final List<JsonObject> services;
        final PackageCompiler.Compiled compiled;

        Result(Engine e) {
            makespan = e.now;
            compiled = e.compiled;
            start = e.start;
            finish = e.finish;
            ready = e.ready;
            inputEnd = e.inputEnd;
            outputStart = e.outputStart;
            taskStart = e.taskStart;
            taskFinish = e.taskFinish;
            blockers = e.blockers;
            services = e.services;
            double input = 0, output = 0, cpu = 0, memory = 0, actualCpu = 0, actualMemory = 0;
            for (PackageCompiler.Group g : compiled.groups) {
                input += g.inputBytes;
                output += g.outputBytes;
                cpu += g.cores * (finish[g.id] - start[g.id]);
                memory += g.memory * (finish[g.id] - start[g.id]);
                for (int t : g.tasks) {
                    double duration = e.scenario.tasks[t].duration[e.plan.host[g.id]];
                    actualCpu += e.scenario.tasks[t].cores * duration;
                    actualMemory += e.scenario.tasks[t].memory * duration;
                }
            }
            for (double value : new double[] {input, output, cpu, memory, actualCpu, actualMemory})
                Scenario.finiteNonnegative(value, "result aggregate");
            inputBytes = input;
            outputBytes = output;
            reservedCoreSeconds = cpu;
            reservedMemoryByteSeconds = memory;
            declaredComputeCoreSeconds = actualCpu;
            declaredComputeMemoryByteSeconds = actualMemory;
        }

        public JsonObject toJson(Scenario s, PackagePlan plan, boolean full) {
            JsonObject r = new JsonObject();
            r.addProperty("schema", "r2-evaluation-v1");
            r.addProperty("scenario_id", s.id);
            r.addProperty("makespan_seconds", makespan);
            r.addProperty("input_bytes", inputBytes);
            r.addProperty("output_bytes", outputBytes);
            r.addProperty("reserved_core_seconds", reservedCoreSeconds);
            r.addProperty("reserved_memory_byte_seconds", reservedMemoryByteSeconds);
            r.addProperty("declared_compute_core_seconds", declaredComputeCoreSeconds);
            r.addProperty("declared_compute_memory_byte_seconds", declaredComputeMemoryByteSeconds);
            r.addProperty("group_count", compiled.groups.length);
            if (full) {
                JsonArray groups = new JsonArray(), tasks = new JsonArray();
                for (PackageCompiler.Group g : compiled.groups) {
                    JsonObject j = new JsonObject();
                    int id = g.id;
                    j.addProperty("id", "g" + id);
                    j.addProperty("host", s.hosts[plan.host[id]].id);
                    j.addProperty("cores", g.cores);
                    j.addProperty("memory_bytes", g.memory);
                    j.addProperty("ready", ready[id]);
                    j.addProperty("start", start[id]);
                    j.addProperty("input_end", inputEnd[id]);
                    j.addProperty("output_start", outputStart[id]);
                    j.addProperty("finish", finish[id]);
                    j.addProperty("input_bytes", g.inputBytes);
                    j.addProperty("output_bytes", g.outputBytes);
                    JsonArray ins = new JsonArray(), outs = new JsonArray(), bs = new JsonArray(), ps = new JsonArray();
                    for (int f : g.inputFiles) ins.add(s.files[f].id);
                    for (int f : g.outputFiles) outs.add(s.files[f].id);
                    for (int b : blockers[id]) bs.add("g" + b);
                    for (int p : g.parents) ps.add("g" + p);
                    j.add("input_files", ins);
                    j.add("output_files", outs);
                    j.add("observed_blockers", bs);
                    j.add("parents", ps);
                    groups.add(j);
                }
                for (int t = 0; t < s.tasks.length; t++) {
                    JsonObject j = new JsonObject();
                    j.addProperty("id", s.tasks[t].id);
                    j.addProperty("group", "g" + compiled.owner[t]);
                    j.addProperty("compute_start", taskStart[t]);
                    j.addProperty("compute_finish", taskFinish[t]);
                    tasks.add(j);
                }
                r.add("groups", groups);
                r.add("tasks", tasks);
                JsonArray spans = new JsonArray();
                for (JsonObject span : services) spans.add(span);
                r.add("service_intervals", spans);
            }
            return r;
        }
    }

    /** Evaluate a fully specified solution, including all task/output obligations. */
    public static synchronized Result evaluate(Scenario scenario, PackagePlan plan, boolean fullTrace) {
        PackageCompiler.Compiled compiled = PackageCompiler.validate(scenario, plan);
        Log.disable();
        Calendar epoch = Calendar.getInstance(TimeZone.getTimeZone("UTC"));
        epoch.setTimeInMillis(0);
        CloudSim.init(0, epoch, false, 1e-12);
        Engine engine = new Engine(scenario, plan, compiled, fullTrace);
        CloudSim.startSimulation();
        if (engine.completed != compiled.groups.length) throw new IllegalStateException("incomplete simulation");
        return new Result(engine);
    }

    private static final class Timer implements Comparable<Timer> {
        final double time;
        final int group, type, taskPosition;

        Timer(double time, int group, int type, int taskPosition) {
            this.time = time;
            this.group = group;
            this.type = type;
            this.taskPosition = taskPosition;
        }

        public int compareTo(Timer other) {
            int c = Double.compare(time, other.time);
            if (c == 0) c = Integer.compare(group, other.group);
            if (c == 0) c = Integer.compare(type, other.type);
            return c;
        }
    }

    private static final class Flow {
        final int group, host, kind;
        final double total;
        double remaining, rate, end;

        Flow(int group, int host, int kind, double bytes) {
            this.group = group;
            this.host = host;
            this.kind = kind;
            total = bytes;
            remaining = bytes;
        }
    }

    private static final class Engine extends SimEntity {
        final Scenario scenario;
        final PackagePlan plan;
        final PackageCompiler.Compiled compiled;
        final boolean full;
        final int[] phase, remainingParents, usedCores, priority, minimumCores;
        final long[] usedMemory, minimumMemory;
        final double[] ready, start, finish, inputEnd, outputStart, taskStart, taskFinish;
        final TreeSet<Integer>[] readyOnHost;
        final Set<Integer>[] runningOnHost, blockers;
        final PriorityQueue<Timer> timers = new PriorityQueue<>();
        final Map<Integer, Flow> flows = new TreeMap<>();
        final List<JsonObject> services = new ArrayList<>();
        int completed;
        double now, last;

        @SuppressWarnings("unchecked")
        Engine(Scenario s, PackagePlan p, PackageCompiler.Compiled c, boolean full) {
            super("FixedRequestPackages");
            scenario = s;
            plan = p;
            compiled = c;
            this.full = full;
            int q = c.groups.length, m = s.hosts.length;
            phase = new int[q];
            remainingParents = new int[q];
            priority = new int[q];
            usedCores = new int[m];
            usedMemory = new long[m];
            minimumCores = new int[m];
            minimumMemory = new long[m];
            Arrays.fill(minimumCores, Integer.MAX_VALUE);
            Arrays.fill(minimumMemory, Long.MAX_VALUE);
            ready = new double[q];
            start = new double[q];
            finish = new double[q];
            inputEnd = new double[q];
            outputStart = new double[q];
            taskStart = new double[s.tasks.length];
            taskFinish = new double[s.tasks.length];
            Arrays.fill(start, Double.NaN);
            Arrays.fill(finish, Double.NaN);
            Arrays.fill(taskFinish, Double.NaN);
            for (int i = 0; i < q; i++) priority[p.order[i]] = i;
            readyOnHost = (TreeSet<Integer>[]) new TreeSet<?>[m];
            runningOnHost = (Set<Integer>[]) new Set<?>[m];
            blockers = (Set<Integer>[]) new Set<?>[q];
            for (int h = 0; h < m; h++) {
                readyOnHost[h] = new TreeSet<>((a, b) -> Integer.compare(priority[a], priority[b]));
                runningOnHost[h] = new TreeSet<>();
            }
            for (PackageCompiler.Group g : c.groups) {
                int h = p.host[g.id];
                blockers[g.id] = new LinkedHashSet<>();
                remainingParents[g.id] = g.parents.length;
                minimumCores[h] = Math.min(minimumCores[h], g.cores);
                minimumMemory[h] = Math.min(minimumMemory[h], g.memory);
                if (g.parents.length == 0) readyOnHost[h].add(g.id);
            }
        }

        @Override
        public void startEntity() {
            schedule(getId(), 0, TICK);
        }

        @Override
        public void shutdownEntity() {}

        private double after(double delay) {
            Scenario.finiteNonnegative(delay, "stage duration");
            double result = now + delay;
            if (!Double.isFinite(result) || (delay > 0 && !(result > now)))
                throw new IllegalArgumentException("stage duration cannot advance the represented clock");
            return result;
        }

        private void beginTask(int g, int position) {
            PackageCompiler.Group group = compiled.groups[g];
            if (position == group.tasks.length) {
                outputStart[g] = now;
                beginFlow(g, WRITE, group.outputBytes);
                return;
            }
            int t = group.tasks[position];
            Scenario.Task task = scenario.tasks[t];
            phase[g] = COMPUTE;
            double computeStart = after(task.bootstrap);
            double finishTime = computeStart + task.duration[plan.host[g]];
            if (!Double.isFinite(finishTime) || (task.duration[plan.host[g]] > 0 && !(finishTime > computeStart)))
                throw new IllegalArgumentException("compute duration cannot advance clock");
            taskStart[t] = computeStart;
            timers.add(new Timer(finishTime, g, TASK_DONE, position));
        }

        private void beginFlow(int g, int kind, double bytes) {
            phase[g] = kind;
            if (bytes == 0) {
                if (kind == READ) {
                    inputEnd[g] = now;
                    beginTask(g, 0);
                } else complete(g);
            } else flows.put(g, new Flow(g, plan.host[g], kind, bytes));
        }

        private void complete(int g) {
            PackageCompiler.Group group = compiled.groups[g];
            int h = plan.host[g];
            phase[g] = DONE;
            finish[g] = now;
            completed++;
            usedCores[h] -= group.cores;
            usedMemory[h] -= group.memory;
            runningOnHost[h].remove(g);
            if (usedCores[h] < 0 || usedMemory[h] < 0) throw new IllegalStateException("negative resource balance");
            for (int child : group.children) {
                ready[child] = Math.max(ready[child], now);
                if (--remainingParents[child] == 0) readyOnHost[plan.host[child]].add(child);
            }
        }

        private void settleServices() {
            double elapsed = now - last;
            if (elapsed < 0) throw new IllegalStateException("clock reversal");
            JsonObject span = full && elapsed > 0 && !flows.isEmpty() ? new JsonObject() : null;
            JsonArray records = span == null ? null : new JsonArray();
            List<Integer> done = new ArrayList<>();
            for (Flow flow : flows.values()) {
                double delivered = elapsed * flow.rate, previous = flow.remaining;
                // Subtracting two large timestamps loses absolute precision. Bound
                // that loss in bytes as well as arithmetic on the byte counters.
                double clockError = 4 * flow.rate * (Math.ulp(now) + Math.ulp(last));
                double tolerance = Math.max(
                        clockError, Math.max(1e-8, Math.max(Math.ulp(flow.total) * 32, Math.ulp(delivered) * 32)));
                if (!Double.isFinite(delivered) || delivered > previous + tolerance)
                    throw new IllegalStateException("flow over-service beyond numerical tolerance");
                flow.remaining = Math.max(0, previous - delivered);
                if (span != null) {
                    JsonObject r = new JsonObject();
                    r.addProperty("group", "g" + flow.group);
                    r.addProperty("host", scenario.hosts[flow.host].id);
                    r.addProperty("direction", flow.kind == READ ? "read" : "write");
                    r.addProperty("rate", flow.rate);
                    records.add(r);
                }
                if (flow.end <= now || (flow.end <= Math.nextUp(now) && flow.remaining <= tolerance)) {
                    if (flow.remaining > tolerance)
                        throw new IllegalStateException("scheduled flow has unexplained residual bytes");
                    done.add(flow.group);
                }
            }
            if (span != null) {
                span.addProperty("start", last);
                span.addProperty("end", now);
                span.add("flows", records);
                services.add(span);
            }
            for (int g : done) {
                Flow flow = flows.remove(g);
                if (flow.kind == READ) {
                    inputEnd[g] = now;
                    beginTask(g, 0);
                } else complete(g);
            }
        }

        private boolean admit() {
            boolean changed = false;
            // With fixed assignments, per-host priority scans admit the same set as a global scan.
            for (int h = 0; h < scenario.hosts.length; h++) {
                Scenario.Host host = scenario.hosts[h];
                if (host.cores - usedCores[h] < minimumCores[h] || host.memory - usedMemory[h] < minimumMemory[h]) {
                    observeBlocked(h);
                    continue;
                }
                Iterator<Integer> it = readyOnHost[h].iterator();
                while (it.hasNext()) {
                    int g = it.next();
                    PackageCompiler.Group group = compiled.groups[g];
                    if (group.cores <= host.cores - usedCores[h] && group.memory <= host.memory - usedMemory[h]) {
                        it.remove();
                        usedCores[h] += group.cores;
                        usedMemory[h] += group.memory;
                        runningOnHost[h].add(g);
                        phase[g] = SETUP;
                        start[g] = now;
                        timers.add(new Timer(after(scenario.jobSetup), g, SETUP_DONE, -1));
                        changed = true;
                    } else if (blockers[g].size() < 32) {
                        for (int running : runningOnHost[h]) {
                            if (blockers[g].size() >= 32) break;
                            blockers[g].add(running);
                        }
                    }
                    if (host.cores - usedCores[h] < minimumCores[h] || host.memory - usedMemory[h] < minimumMemory[h])
                        break;
                }
                observeBlocked(h);
            }
            return changed;
        }
        // A bounded observational sample, including events at which the host is full.
        // These associations guide search; they do not establish counterfactual gains.
        private void observeBlocked(int h) {
            int inspected = 0;
            for (int g : readyOnHost[h]) {
                if (inspected++ == 32) break;
                PackageCompiler.Group group = compiled.groups[g];
                Scenario.Host host = scenario.hosts[h];
                if (group.cores <= host.cores - usedCores[h] && group.memory <= host.memory - usedMemory[h]) continue;
                for (int running : runningOnHost[h]) {
                    if (blockers[g].size() >= 32) break;
                    blockers[g].add(running);
                }
            }
        }

        private void settleInstant() {
            while (true) {
                while (!timers.isEmpty() && timers.peek().time <= now) {
                    Timer t = timers.remove();
                    if (t.type == SETUP_DONE) beginFlow(t.group, READ, compiled.groups[t.group].inputBytes);
                    else {
                        int task = compiled.groups[t.group].tasks[t.taskPosition];
                        taskFinish[task] = now;
                        beginTask(t.group, t.taskPosition + 1);
                    }
                }
                boolean admitted = admit();
                if (!admitted || timers.isEmpty() || timers.peek().time > now) break;
            }
        }

        private void rates() {
            if (flows.isEmpty()) return;
            if (!scenario.sharedIo) {
                for (Flow f : flows.values())
                    f.rate = Math.min(scenario.storageBandwidth, scenario.hosts[f.host].bandwidth);
            } else {
                List<Flow> active = new ArrayList<>(flows.values());
                double store = scenario.storageBandwidth;
                double[] remaining = new double[scenario.hosts.length];
                for (int h = 0; h < remaining.length; h++) remaining[h] = scenario.hosts[h].bandwidth;
                for (Flow f : active) f.rate = 0;
                while (!active.isEmpty()) {
                    int[] count = new int[remaining.length];
                    for (Flow f : active) count[f.host]++;
                    double storeStep = store / active.size(), step = storeStep;
                    double[] hostStep = new double[count.length];
                    for (int h = 0; h < count.length; h++) {
                        hostStep[h] = count[h] == 0 ? Double.POSITIVE_INFINITY : remaining[h] / count[h];
                        step = Math.min(step, hostStep[h]);
                    }
                    if (!(step >= 0) || !Double.isFinite(step))
                        throw new IllegalStateException("invalid bandwidth allocation");
                    for (Flow f : active) f.rate += step;
                    store = Math.max(0, store - step * active.size());
                    for (int h = 0; h < count.length; h++) remaining[h] = Math.max(0, remaining[h] - step * count[h]);
                    boolean storeFull = step == storeStep;
                    int before = active.size();
                    Iterator<Flow> it = active.iterator();
                    while (it.hasNext()) {
                        Flow f = it.next();
                        if (storeFull || hostStep[f.host] == step) it.remove();
                    }
                    if (active.size() == before) throw new IllegalStateException("water filling did not progress");
                }
            }
            for (Flow f : flows.values()) {
                if (!(f.rate > 0) || !Double.isFinite(f.rate))
                    throw new IllegalStateException("nonpositive flow service");
                f.end = after(f.remaining / f.rate);
            }
        }

        @Override
        public void processEvent(SimEvent event) {
            if (event.getTag() != TICK) throw new IllegalArgumentException("unknown grouped event");
            now = CloudSim.clock();
            settleServices();
            last = now;
            settleInstant();
            if (completed == compiled.groups.length) {
                if (!flows.isEmpty() || !timers.isEmpty())
                    throw new IllegalStateException("unfinished obligations at termination");
                for (int h = 0; h < usedCores.length; h++)
                    if (usedCores[h] != 0 || usedMemory[h] != 0) throw new IllegalStateException("leaked reservation");
                CloudSim.terminateSimulation();
                return;
            }
            rates();
            double next = timers.isEmpty() ? Double.POSITIVE_INFINITY : timers.peek().time;
            for (Flow flow : flows.values()) next = Math.min(next, flow.end);
            if (!Double.isFinite(next) || !(next > now)) throw new IllegalStateException("stalled event model");
            schedule(getId(), next - now, TICK);
        }
    }
}
