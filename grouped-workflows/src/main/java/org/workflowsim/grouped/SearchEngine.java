package org.workflowsim.grouped;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Random;
import java.util.Set;
import java.util.TreeSet;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

/** Bounded, reproducible non-learning search over legal physical package schedules. */
public final class SearchEngine {
    static final class Value {
        final PackagePlan plan;
        final GroupedSimulator.Result result;
        final String key;

        Value(PackagePlan p, GroupedSimulator.Result r, String k) {
            plan = p;
            result = r;
            key = k;
        }
    }

    private final Scenario s;
    private final SearchConfig cfg;
    private final Random random;
    private final long started;
    private final LinkedHashMap<String, Value> cache = new LinkedHashMap<>(16, 0.75f, true);
    private final List<JsonObject> history = new ArrayList<>();
    private final Map<String, Integer> byOperator = new LinkedHashMap<>();
    private final List<Value> seeds = new ArrayList<>();
    private final List<int[][]> partitions = new ArrayList<>();
    private Value best;
    private int evaluations, requests, hits, invalid, iterations, restarts, accepted, anchorCursor;
    private int packageMutationAttempts, packageMutationChanges, packageMutationRejected, offspringRefinements;
    private double replaySeconds, initialSeconds;
    private String operator = "initialization";
    private final boolean uniform;

    private SearchEngine(Scenario s, SearchConfig cfg, long started) {
        this.s = s;
        this.cfg = cfg;
        this.started = started;
        random = new Random(cfg.seed);
        uniform = cfg.algorithm.equals("UNIFORM-ILS") || cfg.algorithm.equals("UNIFORM-JOINT-HGA");
    }

    public static JsonObject solve(JsonObject request) {
        long start = System.nanoTime();
        Scenario s = new Scenario(request.getAsJsonObject("scenario"));
        SearchConfig c = new SearchConfig(request.has("config") ? request.getAsJsonObject("config") : new JsonObject());
        return new SearchEngine(s, c, start).run();
    }

    private double seconds() {
        return (System.nanoTime() - started) * 1e-9;
    }

    private boolean available() {
        // One replay is reserved for the returned full trace. The time guard is
        // checked at candidate boundaries, so a single in-flight operation can overrun.
        double reserve = evaluations == 0 ? 0 : 2 * replaySeconds / evaluations;
        return evaluations < cfg.maxEvaluations - 1
                && requests < (long) cfg.maxEvaluations * 40
                && seconds() + reserve < cfg.maxSeconds;
    }

    private boolean within(int limit, int requestLimit) {
        return available() && evaluations < limit && requests < requestLimit;
    }

    private static boolean improves(Value a, Value b) {
        return a != null
                && (b == null || a.result.makespan < b.result.makespan - 1e-9 * Math.max(1, b.result.makespan));
    }

    private static int compare(Value a, Value b) {
        int c = Double.compare(a.result.makespan, b.result.makespan);
        if (c == 0)
            c = Double.compare(a.result.inputBytes + a.result.outputBytes, b.result.inputBytes + b.result.outputBytes);
        return c == 0 ? a.key.compareTo(b.key) : c;
    }

    private Value evaluate(PackagePlan p) {
        if (!available()) return null;
        requests++;
        String key = p.canonical();
        Value value = cache.get(key);
        if (value != null
                && Arrays.deepEquals(p.members, value.plan.members)
                && Arrays.equals(p.host, value.plan.host)
                && Arrays.equals(p.order, value.plan.order)) {
            hits++;
            return value;
        }
        long begin = System.nanoTime();
        GroupedSimulator.Result result;
        try {
            result = GroupedSimulator.evaluate(s, p, false);
        } catch (IllegalArgumentException bad) {
            invalid++;
            throw bad;
        }
        replaySeconds += (System.nanoTime() - begin) * 1e-9;
        evaluations++;
        byOperator.put(operator, byOperator.getOrDefault(operator, 0) + 1);
        value = new Value(p, result, key);
        if (cfg.cacheEntries > 0) {
            cache.put(key, value);
            while (cache.size() > cfg.cacheEntries)
                cache.remove(cache.keySet().iterator().next());
        }
        if (best == null || compare(value, best) < 0) {
            best = value;
            JsonObject h = new JsonObject();
            h.addProperty("evaluations", evaluations);
            h.addProperty("solver_seconds", seconds());
            h.addProperty("makespan_seconds", result.makespan);
            h.addProperty("operator", operator);
            h.addProperty("group_count", p.members.length);
            history.add(h);
        }
        return value;
    }

    private JsonObject run() {
        requireKnownAlgorithm();
        seedInitialSolutions();
        initialSeconds = seconds();
        if (best == null) throw new IllegalStateException("budget insufficient for a feasible initial solution");
        double initialMakespan = best.result.makespan;
        runSearchPhases();
        GroupedSimulator.Result verified = finalReplay();
        return searchReport(verified, initialMakespan);
    }

    /** Validate the algorithm label. */
    private void requireKnownAlgorithm() {
        Set<String> algorithms = new HashSet<>(Arrays.asList(
                "SINGLE",
                "PATH",
                "STAGED-HGA",
                "ALT",
                "JG",
                "MS-DESCENT",
                "UNIFORM-ILS",
                "JOINT-ILS",
                "PLACE-ILS",
                "NO-RELATED-ILS",
                "POOLED-HGA",
                "JOINT-HGA",
                "UNIFORM-JOINT-HGA"));
        Scenario.require(algorithms.contains(cfg.algorithm), "unknown algorithm: " + cfg.algorithm);
    }

    /** Lazily construct and evaluate initial partitions into seeds. */
    private void seedInitialSolutions() {
        // Initialize lazily so preprocessing/constructors cannot run free past a budget.
        for (int k = 0; k < 3 && available(); k++) {
            int[][] partition = k == 0
                    ? InitialPlans.singleton(s)
                    : k == 1 ? InitialPlans.maximalChains(s) : InitialPlans.pathDp(s, cfg.maxSegment);
            boolean fresh = true;
            for (int[][] previous : partitions)
                if (partitionKey(previous).equals(partitionKey(partition))) fresh = false;
            if (fresh) partitions.add(partition);
            if (cfg.algorithm.equals("PATH") && k == 0) continue;
            for (String mode : new String[] {"rank", "downstream"}) {
                if (!available()) break;
                Value v = evaluate(InitialPlans.construct(s, partition, mode, random));
                if (v != null) seeds.add(v);
            }
            if (cfg.algorithm.equals("SINGLE")) break;
        }
    }

    /** Dispatch the configured search phases. */
    private void runSearchPhases() {
        if (cfg.algorithm.equals("STAGED-HGA")) genetic();
        else if (cfg.algorithm.endsWith("HGA")) jointGenetic();
        else if (!cfg.algorithm.equals("SINGLE") && !cfg.algorithm.equals("PATH")) {
            Value current = descend(best);
            int stale = 0;
            while (available() && !cfg.algorithm.equals("JG")) {
                iterations++;
                Value trial;
                if (cfg.algorithm.equals("MS-DESCENT") || cfg.algorithm.equals("ALT") || stale >= cfg.restartAfter) {
                    trial = randomStart();
                    stale = 0;
                    restarts++;
                } else trial = perturb(current);
                if (trial == null) break;
                trial = descend(trial);
                if (improves(trial, current)) {
                    current = trial;
                    stale = 0;
                } else {
                    stale++;
                    if (cfg.algorithm.equals("MS-DESCENT")) current = trial;
                }
            }
        }
    }

    /** Re-run the best plan and verify consistency. */
    private GroupedSimulator.Result finalReplay() {
        operator = "final_replay";
        requests++;
        long begin = System.nanoTime();
        GroupedSimulator.Result verified = GroupedSimulator.evaluate(s, best.plan, true);
        replaySeconds += (System.nanoTime() - begin) * 1e-9;
        evaluations++;
        byOperator.put(operator, 1);
        if (Double.compare(verified.makespan, best.result.makespan) != 0)
            throw new IllegalStateException("final replay is inconsistent");
        return verified;
    }

    /** Assemble the result JSON with search statistics. */
    private JsonObject searchReport(GroupedSimulator.Result verified, double initialMakespan) {
        JsonObject out = new JsonObject();
        out.add("plan", best.plan.toJson(s));
        out.add("evaluation", verified.toJson(s, best.plan, true));
        out.add("config", cfg.json());
        JsonObject stats = new JsonObject();
        stats.addProperty("evaluations", evaluations);
        stats.addProperty("evaluation_requests", requests);
        stats.addProperty("cache_hits", hits);
        stats.addProperty("invalid_evaluations", invalid);
        stats.addProperty("solver_seconds", seconds());
        stats.addProperty("initialization_seconds", initialSeconds);
        stats.addProperty("replay_seconds", replaySeconds);
        stats.addProperty("initial_makespan_seconds", initialMakespan);
        stats.addProperty("iterations", iterations);
        stats.addProperty("restarts", restarts);
        stats.addProperty("accepted_descent_steps", accepted);
        stats.addProperty("package_mutation_attempts", packageMutationAttempts);
        stats.addProperty("package_mutation_changes", packageMutationChanges);
        stats.addProperty("package_mutation_rejected", packageMutationRejected);
        stats.addProperty("offspring_refinements", offspringRefinements);
        stats.addProperty("wall_limit_exceeded", seconds() > cfg.maxSeconds);
        stats.addProperty(
                "stop_reason",
                evaluations >= cfg.maxEvaluations
                        ? "evaluation_limit"
                        : seconds() >= cfg.maxSeconds
                                ? "wall_guard"
                                : requests >= (long) cfg.maxEvaluations * 40
                                        ? "request_guard"
                                        : cfg.algorithm.equals("JG")
                                                ? "sampled_descent_finished"
                                                : cfg.algorithm.equals("SINGLE") || cfg.algorithm.equals("PATH")
                                                        ? "constructor_finished"
                                                        : "wall_reserve_or_no_trial");
        JsonObject ops = new JsonObject();
        for (Map.Entry<String, Integer> e : byOperator.entrySet()) ops.addProperty(e.getKey(), e.getValue());
        stats.add("replays_by_operator", ops);
        out.add("search", stats);
        JsonArray hs = new JsonArray();
        for (JsonObject h : history) hs.add(h);
        out.add("anytime", hs);
        return out;
    }

    private Value descend(Value current) {
        if (current == null) return null;
        int failed = 0, kind = 0;
        int types = cfg.algorithm.equals("PLACE-ILS")
                ? 1
                : cfg.algorithm.equals("ALT") || cfg.algorithm.equals("NO-RELATED-ILS") ? 2 : 3;
        while (available() && failed < types) {
            int limit = Math.min(cfg.maxEvaluations - 1, evaluations + cfg.neighborhoodEvaluations);
            int requestLimit = requests + cfg.neighborhoodEvaluations * 12;
            Value candidate;
            if (kind == 0) {
                operator = "placement";
                candidate = place(current, limit, requestLimit);
            } else {
                operator = kind == 1 ? "path" : "related";
                candidate = path(current, kind == 2, cfg.algorithm.equals("ALT"), limit, requestLimit);
            }
            if (improves(candidate, current)) {
                current = candidate;
                accepted++;
                failed = 0;
            } else failed++;
            // Rotate after every bounded visit. This prevents an enormous placement
            // neighborhood from starving package changes under a finite replay budget.
            kind = (kind + 1) % types;
        }
        return current;
    }

    private int anchor(Value v) {
        int q = v.plan.members.length;
        if (uniform || random.nextDouble() < 0.25) return random.nextInt(q);
        Integer[] cpu = new Integer[q], memory = new Integer[q], lag = new Integer[q];
        double[] cr = new double[q], mr = new double[q], lr = new double[q];
        for (int g = 0; g < q; g++) {
            cpu[g] = memory[g] = lag[g] = g;
            PackageCompiler.Group group = v.result.compiled.groups[g];
            cr[g] = group.cores * (v.result.finish[g] - v.result.start[g]);
            mr[g] = group.memory * (v.result.finish[g] - v.result.start[g]);
            for (int t : group.tasks) {
                double d = s.tasks[t].duration[v.plan.host[g]];
                cr[g] -= s.tasks[t].cores * d;
                mr[g] -= s.tasks[t].memory * d;
                boolean outside = false;
                for (int child : s.children[t]) if (v.result.compiled.owner[child] != g) outside = true;
                if (outside) lr[g] = Math.max(lr[g], v.result.finish[g] - v.result.taskFinish[t]);
            }
        }
        Arrays.sort(cpu, descending(cr));
        Arrays.sort(memory, descending(mr));
        Arrays.sort(lag, descending(lr));
        LinkedHashSet<Integer> ranking = new LinkedHashSet<>();
        for (int i = 0; i < q; i++) {
            ranking.add(cpu[i]);
            ranking.add(memory[i]);
            ranking.add(lag[i]);
        }
        return new ArrayList<>(ranking).get((anchorCursor++) % q);
    }

    private static Comparator<Integer> descending(double[] score) {
        return (a, b) -> {
            int c = Double.compare(score[b], score[a]);
            return c == 0 ? Integer.compare(a, b) : c;
        };
    }

    private Value place(Value current, int limit, int requestLimit) {
        int g = anchor(current);
        return improveMovable(current, Collections.singletonList(g), limit, requestLimit);
    }

    private int[] window(Value v, int anchor) {
        LinkedHashSet<Integer> groups = new LinkedHashSet<>();
        groups.add(anchor);
        while (groups.size() < cfg.windowGroups) {
            Set<Integer> choices = new TreeSet<>();
            for (int g : groups) {
                for (int p : v.result.compiled.groups[g].parents) if (!groups.contains(p)) choices.add(p);
                for (int c : v.result.compiled.groups[g].children) if (!groups.contains(c)) choices.add(c);
            }
            List<Integer> valid = new ArrayList<>();
            for (int g : choices) {
                List<Integer> all = new ArrayList<>(groups);
                all.add(g);
                if (legalPath(tasksOf(v, all))) valid.add(g);
            }
            if (valid.isEmpty()) break;
            groups.add(valid.get(random.nextInt(valid.size())));
            if (random.nextDouble() < 0.2) break;
        }
        return tasksOf(v, groups);
    }

    private int[] tasksOf(Value v, Collection<Integer> groups) {
        List<Integer> tasks = new ArrayList<>();
        for (int g : groups) for (int t : v.result.compiled.groups[g].tasks) tasks.add(t);
        tasks.sort((a, b) -> Integer.compare(s.topoPosition[a], s.topoPosition[b]));
        return Scenario.ints(tasks);
    }

    private boolean legalPath(int[] tasks) {
        for (int i = 1; i < tasks.length; i++) if (!s.descendants[tasks[i - 1]].get(tasks[i])) return false;
        if (tasks.length > 1) {
            BitSet between = (BitSet) s.descendants[tasks[0]].clone();
            between.and(s.ancestors[tasks[tasks.length - 1]]);
            if (between.cardinality() != tasks.length - 2) return false;
        }
        return Double.isFinite(InitialPlans.isolatedCost(s, tasks));
    }

    private List<Integer> cuts(Value v, int[] window) {
        LinkedHashSet<Integer> selected = new LinkedHashSet<>();
        List<Integer> other = new ArrayList<>();
        for (int i = 1; i < window.length; i++) {
            if (v.result.compiled.owner[window[i - 1]] != v.result.compiled.owner[window[i]]) selected.add(i);
            else other.add(i);
        }
        if (uniform) Collections.shuffle(other, random);
        else {
            double[] resource = new double[window.length],
                    handoff = new double[window.length],
                    release = new double[window.length];
            for (int i : other) {
                Scenario.Task a = s.tasks[window[i - 1]], b = s.tasks[window[i]];
                resource[i] = Math.abs(Math.log((double) a.cores / b.cores))
                        + Math.abs(Math.log((a.memory + 1.0) / (b.memory + 1.0)));
                for (int f : s.outputsOfTask[window[i - 1]]) handoff[i] -= s.files[f].bytes;
                for (int c : s.children[window[i - 1]]) if (c != window[i]) release[i]++;
            }
            List<Integer> r = new ArrayList<>(other),
                    h = new ArrayList<>(other),
                    l = new ArrayList<>(other),
                    u = new ArrayList<>(other);
            r.sort(descending(resource));
            h.sort(descending(handoff));
            l.sort(descending(release));
            Collections.shuffle(u, random);
            LinkedHashSet<Integer> mixed = new LinkedHashSet<>();
            int offset = random.nextInt(4);
            List<List<Integer>> lists = Arrays.asList(r, h, l, u);
            for (int i = 0; i < other.size(); i++)
                for (int k = 0; k < 4; k++)
                    mixed.add(lists.get((k + offset) % 4).get(i));
            other = new ArrayList<>(mixed);
        }
        for (int c : other) {
            if (selected.size() >= cfg.boundaryLimit) break;
            selected.add(c);
        }
        List<Integer> result = new ArrayList<>(selected);
        Collections.sort(result);
        return result;
    }

    private Value path(Value current, boolean withRelated, boolean fixedPlacement, int limit, int requestLimit) {
        int[] w = window(current, anchor(current));
        if (w.length < 2) return current;
        List<Integer> candidates = cuts(current, w);
        int all = (1 << candidates.size()) - 1, currentMask = 0;
        for (int i = 0; i < candidates.size(); i++)
            if (current.result.compiled.owner[w[candidates.get(i) - 1]]
                    != current.result.compiled.owner[w[candidates.get(i)]]) currentMask |= 1 << i;
        List<Integer> masks = new ArrayList<>();
        for (int mask = 0; mask <= all; mask++) masks.add(mask);
        Collections.shuffle(masks, random);
        LinkedHashSet<Integer> selected = new LinkedHashSet<>();
        selected.add(all);
        selected.add(0);
        selected.add(currentMask);
        for (int mask : masks) if (selected.size() < cfg.partitionSamples) selected.add(mask);
        masks = new ArrayList<>(selected);
        Collections.shuffle(masks, random);
        if (masks.size() > cfg.partitionSamples) masks = new ArrayList<>(masks.subList(0, cfg.partitionSamples));
        List<Integer> related = withRelated ? related(current, w) : Collections.emptyList();
        Value result = current;
        for (int i = 0; i < masks.size() && within(limit, requestLimit); i++) {
            int mask = masks.get(i);
            List<int[]> pieces = new ArrayList<>();
            int begin = 0;
            for (int k = 0; k < candidates.size(); k++)
                if ((mask & (1 << k)) != 0) {
                    int end = candidates.get(k);
                    pieces.add(Arrays.copyOfRange(w, begin, end));
                    begin = end;
                }
            pieces.add(Arrays.copyOfRange(w, begin, w.length));
            int remaining = masks.size() - i;
            int partLimit = Math.min(limit, evaluations + Math.max(1, (limit - evaluations) / remaining));
            int partRequests = Math.min(requestLimit, requests + Math.max(8, (requestLimit - requests) / remaining));
            Repartition rp = repartition(
                    current, w, pieces, related.isEmpty() ? -1 : related.get(i % related.size()), fixedPlacement);
            if (rp == null) continue;
            Value seed = evaluate(rp.plan);
            if (seed == null) break;
            Value found = fixedPlacement ? seed : improveMovable(seed, rp.movable, partLimit, partRequests);
            if (compare(found, result) < 0) result = found;
        }
        return result;
    }

    private List<Integer> related(Value v, int[] window) {
        if (cfg.relatedLimit == 0) return Collections.emptyList();
        Set<Integer> own = new HashSet<>();
        for (int t : window) own.add(v.result.compiled.owner[t]);
        LinkedHashSet<Integer> choices = new LinkedHashSet<>();
        for (int g : own)
            for (int child : v.result.compiled.groups[g].children) if (!own.contains(child)) choices.add(child);
        for (int g = 0; g < v.plan.members.length; g++)
            if (!own.contains(g)) for (int blocker : v.result.blockers[g]) if (own.contains(blocker)) choices.add(g);
        List<Integer> outside = new ArrayList<>();
        for (int g = 0; g < v.plan.members.length; g++) if (!own.contains(g)) outside.add(g);
        if (outside.isEmpty()) return Collections.emptyList();
        if (uniform || random.nextDouble() < 0.25) {
            Collections.shuffle(outside, random);
            return outside.subList(0, Math.min(cfg.relatedLimit, outside.size()));
        }
        List<Integer> result = new ArrayList<>(choices);
        result.sort((a, b) -> {
            int c = Double.compare(v.result.start[b] - v.result.ready[b], v.result.start[a] - v.result.ready[a]);
            return c == 0 ? Integer.compare(a, b) : c;
        });
        if (result.isEmpty()) result.add(outside.get(random.nextInt(outside.size())));
        return result.subList(0, Math.min(cfg.relatedLimit, result.size()));
    }

    private static final class Repartition {
        final PackagePlan plan;
        final List<Integer> movable;

        Repartition(PackagePlan p, List<Integer> m) {
            plan = p;
            movable = m;
        }
    }

    private Repartition repartition(Value v, int[] window, List<int[]> pieces, int related, boolean fixedPlacement) {
        Set<Integer> touched = new HashSet<>();
        for (int t : window) touched.add(v.result.compiled.owner[t]);
        List<int[]> partition = new ArrayList<>();
        List<Integer> fixedOrder = new ArrayList<>(), movable = new ArrayList<>();
        int[] oldToNew = new int[v.plan.members.length];
        Arrays.fill(oldToNew, -1);
        for (int g = 0; g < v.plan.members.length; g++)
            if (!touched.contains(g)) {
                oldToNew[g] = partition.size();
                partition.add(v.plan.members[g]);
            }
        for (int[] piece : pieces) {
            movable.add(partition.size());
            partition.add(piece);
        }
        for (int g : v.plan.order) if (oldToNew[g] >= 0 && g != related) fixedOrder.add(oldToNew[g]);
        if (related >= 0) movable.add(oldToNew[related]);
        int[][] members = partition.toArray(new int[0][]);
        PackageCompiler.Compiled compiled;
        try {
            compiled = PackageCompiler.compile(s, members);
        } catch (IllegalArgumentException infeasible) {
            return null;
        }
        int[] hosts = new int[members.length],
                oldPosition = new int[v.plan.members.length],
                rank = new int[members.length];
        for (int i = 0; i < v.plan.order.length; i++) oldPosition[v.plan.order[i]] = i;
        for (PackageCompiler.Group g : compiled.groups) {
            int old = v.result.compiled.owner[g.tasks[0]], h = v.plan.host[old];
            boolean same = true;
            rank[g.id] = oldPosition[old];
            for (int t : g.tasks) {
                same &= v.plan.host[v.result.compiled.owner[t]] == h;
                rank[g.id] = Math.min(rank[g.id], oldPosition[v.result.compiled.owner[t]]);
            }
            if (fixedPlacement && !same) return null;
            if (!same || !g.compatible[h]) {
                if (fixedPlacement) return null;
                h = -1;
                double time = Double.POSITIVE_INFINITY;
                for (int k = 0; k < s.hosts.length; k++)
                    if (g.compatible[k]) {
                        double d = InitialPlans.nominal(s, g, k);
                        if (d < time) {
                            h = k;
                            time = d;
                        }
                    }
            }
            hosts[g.id] = h;
        }
        int[] order = stableOrder(compiled, fixedOrder, rank);
        if (order == null) return null;
        return new Repartition(new PackagePlan(members, hosts, order), movable);
    }

    private int[] stableOrder(PackageCompiler.Compiled c, List<Integer> fixed, int[] rank) {
        List<Set<Integer>> children = new ArrayList<>();
        int[] degree = new int[c.groups.length];
        for (PackageCompiler.Group g : c.groups) {
            Set<Integer> set = new TreeSet<>();
            for (int child : g.children) set.add(child);
            children.add(set);
        }
        for (int i = 1; i < fixed.size(); i++) children.get(fixed.get(i - 1)).add(fixed.get(i));
        for (Set<Integer> cs : children) for (int child : cs) degree[child]++;
        PriorityQueue<Integer> ready = new PriorityQueue<>(
                (a, b) -> rank[a] == rank[b] ? Integer.compare(a, b) : Integer.compare(rank[a], rank[b]));
        for (int g = 0; g < degree.length; g++) if (degree[g] == 0) ready.add(g);
        int[] order = new int[degree.length];
        int n = 0;
        while (!ready.isEmpty()) {
            int g = ready.remove();
            order[n++] = g;
            for (int child : children.get(g)) if (--degree[child] == 0) ready.add(child);
        }
        return n == degree.length ? order : null;
    }

    private Value improveMovable(Value seed, List<Integer> movable, int limit, int requestLimit) {
        List<Value> beam = new ArrayList<>();
        beam.add(seed);
        Value result = seed;
        // Per-group slices ensure a first group cannot consume the entire repair budget.
        for (int step = 0; step < movable.size() && within(limit, requestLimit); step++) {
            int g = movable.get(step), left = movable.size() - step;
            int groupLimit = Math.min(limit, evaluations + Math.max(1, (limit - evaluations) / left));
            int groupRequests = Math.min(requestLimit, requests + Math.max(4, (requestLimit - requests) / left));
            List<Value> expanded = new ArrayList<>(beam);
            for (Value parent : beam) {
                List<PackagePlan> moves = moves(parent, g);
                Collections.shuffle(moves, random);
                for (PackagePlan p : moves) {
                    if (!within(groupLimit, groupRequests)) break;
                    Value child = evaluate(p);
                    if (child != null) {
                        expanded.add(child);
                        if (compare(child, result) < 0) result = child;
                    }
                }
                if (!within(groupLimit, groupRequests)) break;
            }
            expanded.sort(SearchEngine::compare);
            beam = new ArrayList<>();
            Set<String> seen = new HashSet<>();
            for (Value v : expanded)
                if (seen.add(v.key)) {
                    beam.add(v);
                    if (beam.size() == cfg.beamWidth) break;
                }
        }
        return result;
    }

    private List<PackagePlan> moves(Value v, int g) {
        List<Integer> positions = positions(v.result.compiled, v.plan.order, g, cfg.prioritySamples);
        List<PackagePlan> moves = new ArrayList<>();
        for (int h = 0; h < s.hosts.length; h++)
            if (v.result.compiled.groups[g].compatible[h])
                for (int pos : positions) {
                    int[] hosts = v.plan.host.clone();
                    hosts[g] = h;
                    moves.add(new PackagePlan(v.plan.members, hosts, insert(v.plan.order, g, pos)));
                }
        return moves;
    }

    private List<Integer> positions(PackageCompiler.Compiled c, int[] order, int g, int samples) {
        int[] other = new int[order.length - 1], location = new int[order.length];
        int n = 0, original = 0;
        for (int i = 0; i < order.length; i++)
            if (order[i] != g) {
                other[n] = order[i];
                location[order[i]] = n++;
            } else original = i;
        int lo = 0, hi = other.length;
        for (int p : c.groups[g].parents) lo = Math.max(lo, location[p] + 1);
        for (int child : c.groups[g].children) hi = Math.min(hi, location[child]);
        if (samples >= hi - lo + 1) {
            List<Integer> all = new ArrayList<>();
            for (int i = lo; i <= hi; i++) all.add(i);
            return all;
        }
        LinkedHashSet<Integer> selected = new LinkedHashSet<>();
        selected.add(original);
        if (samples > 1) selected.add(random.nextBoolean() ? lo : hi);
        int wanted = Math.min(samples, hi - lo + 1);
        while (selected.size() < wanted) selected.add(lo + random.nextInt(hi - lo + 1));
        return new ArrayList<>(selected);
    }

    private static int[] insert(int[] order, int g, int pos) {
        int[] result = new int[order.length];
        int i = 0;
        for (int x : order)
            if (x != g) {
                if (i == pos) result[i++] = g;
                result[i++] = x;
            }
        if (i == pos) result[i] = g;
        return result;
    }

    private Value randomStart() {
        if (!available()) return null;
        operator = "restart";
        int[][] base = partitions.get(random.nextInt(partitions.size()));
        return evaluate(InitialPlans.construct(s, base, "random", random));
    }

    private Value perturb(Value current) {
        operator = "perturbation";
        for (int attempt = 0; attempt < 8 && available(); attempt++) {
            int[] w = window(current, random.nextInt(current.plan.members.length));
            List<Integer> cs = cuts(current, w);
            List<int[]> pieces = new ArrayList<>();
            int begin = 0;
            for (int c : cs)
                if (random.nextBoolean()) {
                    pieces.add(Arrays.copyOfRange(w, begin, c));
                    begin = c;
                }
            pieces.add(Arrays.copyOfRange(w, begin, w.length));
            Repartition rp = repartition(current, w, pieces, -1, cfg.algorithm.equals("ALT"));
            if (rp == null) continue;
            PackagePlan p = cfg.algorithm.equals("PLACE-ILS") ? current.plan : rp.plan;
            PackageCompiler.Compiled c = PackageCompiler.validate(s, p);
            int g = random.nextInt(p.members.length);
            List<Integer> hosts = new ArrayList<>();
            for (int h = 0; h < s.hosts.length; h++) if (c.groups[g].compatible[h]) hosts.add(h);
            int[] assignment = p.host.clone();
            assignment[g] = hosts.get(random.nextInt(hosts.size()));
            List<Integer> positions = positions(c, p.order, g, cfg.prioritySamples);
            p = new PackagePlan(
                    p.members, assignment, insert(p.order, g, positions.get(random.nextInt(positions.size()))));
            if (p.canonical().equals(current.key)) continue;
            return evaluate(p);
        }
        return randomStart();
    }

    private void genetic() {
        operator = "genetic";
        List<List<Value>> populations = new ArrayList<>();
        for (int[][] partition : partitions) {
            List<Value> pop = new ArrayList<>();
            PackageCompiler.Compiled c = PackageCompiler.compile(s, partition);
            for (String mode : new String[] {"rank", "downstream"})
                if (available()) {
                    Value v = evaluate(InitialPlans.assign(s, partition, c, InitialPlans.priority(s, c, mode, random)));
                    if (v != null) pop.add(v);
                }
            populations.add(pop);
        }
        for (int i = 2; i < cfg.population && available(); i++)
            for (int k = 0; k < populations.size() && available(); k++) {
                Value v = evaluate(InitialPlans.construct(s, partitions.get(k), "random", random));
                if (v != null) populations.get(k).add(v);
            }
        int generation = 0;
        while (available()) {
            int k = generation++ % populations.size();
            List<Value> pop = populations.get(k);
            if (pop.size() < 2) break;
            int ia = tournament(pop), ib = tournament(pop);
            if (ia == ib) ib = (ib + 1) % pop.size();
            Value a = pop.get(ia), b = pop.get(ib);
            List<Value> family = new ArrayList<>(Arrays.asList(a, b));
            for (int direction = 0; direction < 2 && available(); direction++) {
                int[] order = crossover(
                        direction == 0 ? a.plan.order : b.plan.order, direction == 0 ? b.plan.order : a.plan.order);
                if (random.nextDouble() < cfg.mutation) {
                    int g = order[random.nextInt(order.length)];
                    List<Integer> ps = positions(a.result.compiled, order, g, Integer.MAX_VALUE);
                    order = insert(order, g, ps.get(random.nextInt(ps.size())));
                }
                Value child = evaluate(InitialPlans.assign(s, a.plan.members, a.result.compiled, order));
                if (child != null) family.add(child);
            }
            family.sort(SearchEngine::compare);
            pop.set(ia, family.get(0));
            pop.set(ib, family.get(1));
            iterations++;
        }
    }
    /** A shared genetic population whose members may have different legal packages.
     * Parent priorities are transported through original task identities before a
     * precedence-safe crossover. Package mutation is followed by global construction.
     */
    private void jointGenetic() {
        operator = "pooled_initialization";
        List<Value> population = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (Value seed : seeds) if (seen.add(seed.key)) population.add(seed);
        while (population.size() < cfg.population && available()) {
            int[][] partition = partitions.get(random.nextInt(partitions.size()));
            Value value = evaluate(InitialPlans.construct(s, partition, "random", random));
            if (value == null) break;
            population.add(value);
        }
        if (population.size() < 2) return;
        while (available()) {
            operator = "order_evolution";
            int ia = tournament(population), ib = tournament(population);
            if (ia == ib) ib = (ib + 1) % population.size();
            Value a = population.get(ia), b = population.get(ib);
            List<Value> family = new ArrayList<>(Arrays.asList(a, b));
            for (int direction = 0; direction < 2 && available(); direction++) {
                Value parent = direction == 0 ? a : b, other = direction == 0 ? b : a;
                PackageCompiler.Compiled c = parent.result.compiled;
                int[] transported = transport(other, c), order = crossover(parent.plan.order, transported);
                if (random.nextDouble() < cfg.mutation) {
                    int g = order[random.nextInt(order.length)];
                    List<Integer> positions = positions(c, order, g, Integer.MAX_VALUE);
                    order = insert(order, g, positions.get(random.nextInt(positions.size())));
                }
                int[][] partition = parent.plan.members;
                if (!cfg.algorithm.equals("POOLED-HGA") && random.nextDouble() < cfg.packageMutation) {
                    packageMutationAttempts++;
                    operator = "joint_package_mutation";
                    int[] window = window(parent, anchor(parent));
                    if (window.length > 1) {
                        List<Integer> choices = cuts(parent, window);
                        List<int[]> pieces = new ArrayList<>();
                        int start = 0;
                        int all = (1 << choices.size()) - 1, mask = random.nextInt(all + 1);
                        for (int i = 0; i < choices.size(); i++)
                            if ((mask & (1 << i)) != 0) {
                                int end = choices.get(i);
                                pieces.add(Arrays.copyOfRange(window, start, end));
                                start = end;
                            }
                        pieces.add(Arrays.copyOfRange(window, start, window.length));
                        Repartition changed = repartition(parent, window, pieces, -1, false);
                        if (changed != null) {
                            partition = changed.plan.members;
                            c = PackageCompiler.compile(s, partition);
                            if (!partitionKey(partition).equals(partitionKey(parent.plan.members)))
                                packageMutationChanges++;
                            // Preserve the evolved precedence information at original-task level.
                            PackagePlan evolved = new PackagePlan(parent.plan.members, parent.plan.host, order);
                            Value ordering = new Value(evolved, parent.result, evolved.canonical());
                            order = transport(ordering, c);
                        } else packageMutationRejected++;
                    }
                }
                Value child = evaluate(InitialPlans.assign(s, partition, c, order));
                if (child == null) break;
                if (!cfg.algorithm.equals("POOLED-HGA")
                        && cfg.refinementProbability > 0
                        && random.nextDouble() < cfg.refinementProbability
                        && available()) {
                    offspringRefinements++;
                    operator = "offspring_refinement";
                    int limit = Math.min(cfg.maxEvaluations - 1, evaluations + cfg.refinementEvaluations);
                    Value refined = path(child, true, false, limit, requests + cfg.refinementEvaluations * 12);
                    if (compare(refined, child) < 0) child = refined;
                }
                family.add(child);
                operator = "order_evolution";
            }
            family.sort(SearchEngine::compare);
            population.set(ia, family.get(0));
            population.set(ib, family.get(1));
            iterations++;
        }
    }

    private int[] transport(Value from, PackageCompiler.Compiled to) {
        int[] taskPosition = new int[s.tasks.length];
        int position = 0;
        for (int g : from.plan.order) for (int t : from.result.compiled.groups[g].tasks) taskPosition[t] = position++;
        int[] rank = new int[to.groups.length];
        for (PackageCompiler.Group g : to.groups) {
            rank[g.id] = Integer.MAX_VALUE;
            for (int t : g.tasks) rank[g.id] = Math.min(rank[g.id], taskPosition[t]);
        }
        return stableOrder(to, Collections.emptyList(), rank);
    }

    private int tournament(List<Value> pop) {
        int a = random.nextInt(pop.size()), b = random.nextInt(pop.size());
        return compare(pop.get(a), pop.get(b)) <= 0 ? a : b;
    }

    private static String partitionKey(int[][] partition) {
        List<String> groups = new ArrayList<>();
        for (int[] group : partition) {
            int[] sorted = group.clone();
            Arrays.sort(sorted);
            groups.add(Arrays.toString(sorted));
        }
        Collections.sort(groups);
        return groups.toString();
    }

    private int[] crossover(int[] a, int[] b) {
        int cut = random.nextInt(a.length + 1), n = cut;
        int[] child = new int[a.length];
        boolean[] used = new boolean[a.length];
        for (int i = 0; i < cut; i++) {
            child[i] = a[i];
            used[a[i]] = true;
        }
        for (int g : b) if (!used[g]) child[n++] = g;
        return child;
    }
}
