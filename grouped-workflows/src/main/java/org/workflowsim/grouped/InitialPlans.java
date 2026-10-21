package org.workflowsim.grouped;

import java.util.*;

/** Shared nominal constructors. Final solution quality is always evaluated by the event model. */
public final class InitialPlans {
    private InitialPlans() {}

    static int[][] singleton(Scenario s) {
        int[][] result = new int[s.tasks.length][1];
        for (int i = 0; i < result.length; i++) result[i][0] = i;
        return result;
    }

    static List<int[]> pureChains(Scenario s) {
        List<int[]> chains = new ArrayList<>();
        boolean[] used = new boolean[s.tasks.length];
        for (int t : s.topo)
            if (!used[t]) {
                List<Integer> chain = new ArrayList<>();
                int current = t;
                while (true) {
                    chain.add(current);
                    used[current] = true;
                    if (s.children[current].length != 1) break;
                    int next = s.children[current][0];
                    if (s.tasks[next].parents.length != 1 || used[next]) break;
                    current = next;
                }
                chains.add(Scenario.ints(chain));
            }
        return chains;
    }

    static int[][] maximalChains(Scenario s) {
        List<int[]> groups = new ArrayList<>();
        for (int[] chain : pureChains(s)) {
            int begin = 0;
            while (begin < chain.length) {
                int end = begin + 1;
                while (end < chain.length
                        && Double.isFinite(isolatedCost(s, Arrays.copyOfRange(chain, begin, end + 1)))) end++;
                groups.add(Arrays.copyOfRange(chain, begin, end));
                begin = end;
            }
        }
        return groups.toArray(new int[0][]);
    }

    static int[][] pathDp(Scenario s, int maxSegment) {
        List<int[]> result = new ArrayList<>();
        for (int[] chain : pureChains(s)) {
            double[] cost = new double[chain.length + 1];
            int[] cut = new int[chain.length + 1];
            Arrays.fill(cost, Double.POSITIVE_INFINITY);
            cost[0] = 0;
            for (int end = 1; end <= chain.length; end++)
                for (int begin = Math.max(0, end - maxSegment); begin < end; begin++) {
                    double value = cost[begin] + isolatedCost(s, Arrays.copyOfRange(chain, begin, end));
                    if (value < cost[end]) {
                        cost[end] = value;
                        cut[end] = begin;
                    }
                }
            List<int[]> reversed = new ArrayList<>();
            int end = chain.length;
            while (end > 0) {
                int begin = cut[end];
                reversed.add(Arrays.copyOfRange(chain, begin, end));
                end = begin;
            }
            Collections.reverse(reversed);
            result.addAll(reversed);
        }
        return result.toArray(new int[0][]);
    }

    static double isolatedCost(Scenario s, int[] members) {
        BitSet set = new BitSet(s.tasks.length),
                input = new BitSet(s.files.length),
                output = new BitSet(s.files.length);
        int cores = 0;
        long memory = 0;
        String domain = s.tasks[members[0]].domain;
        for (int t : members) {
            set.set(t);
            cores = Math.max(cores, s.tasks[t].cores);
            memory = Math.max(memory, s.tasks[t].memory);
            if (!domain.equals(s.tasks[t].domain)) return Double.POSITIVE_INFINITY;
        }
        for (int t : members) {
            for (int f : s.inputsOfTask[t]) if (s.files[f].producer < 0 || !set.get(s.files[f].producer)) input.set(f);
            for (int f : s.outputsOfTask[t]) {
                boolean required = s.files[f].retain;
                for (int consumer : s.files[f].consumers) if (!set.get(consumer)) required = true;
                if (required) output.set(f);
            }
        }
        double bytes = 0;
        for (int f = input.nextSetBit(0); f >= 0; f = input.nextSetBit(f + 1)) bytes += s.files[f].bytes;
        for (int f = output.nextSetBit(0); f >= 0; f = output.nextSetBit(f + 1)) bytes += s.files[f].bytes;
        double best = Double.POSITIVE_INFINITY;
        for (int h = 0; h < s.hosts.length; h++)
            if (cores <= s.hosts[h].cores && memory <= s.hosts[h].memory) {
                double time = s.jobSetup + bytes / Math.min(s.storageBandwidth, s.hosts[h].bandwidth);
                for (int t : members) {
                    if (!Double.isFinite(s.tasks[t].duration[h])) {
                        time = Double.POSITIVE_INFINITY;
                        break;
                    }
                    time += s.tasks[t].bootstrap + s.tasks[t].duration[h];
                }
                best = Math.min(best, time);
            }
        return best;
    }

    static double nominal(Scenario s, PackageCompiler.Group g, int h) {
        if (!g.compatible[h]) return Double.POSITIVE_INFINITY;
        double d = s.jobSetup + (g.inputBytes + g.outputBytes) / Math.min(s.storageBandwidth, s.hosts[h].bandwidth);
        for (int t : g.tasks) d += s.tasks[t].bootstrap + s.tasks[t].duration[h];
        return Scenario.finiteNonnegative(d, "nominal group duration");
    }

    static int[] priority(Scenario s, PackageCompiler.Compiled c, String mode, Random random) {
        double[] rank = new double[c.groups.length];
        for (int k = c.topo.length - 1; k >= 0; k--) {
            int g = c.topo[k];
            PackageCompiler.Group group = c.groups[g];
            if (mode.equals("downstream")) {
                BitSet descendants = new BitSet(s.tasks.length);
                for (int t : group.tasks) descendants.or(s.descendants[t]);
                rank[g] = descendants.cardinality();
            } else if (mode.equals("random")) rank[g] = random.nextDouble();
            else {
                double duration = 0;
                int compatible = 0;
                for (int h = 0; h < s.hosts.length; h++)
                    if (group.compatible[h]) {
                        duration += nominal(s, group, h);
                        compatible++;
                    }
                double tail = 0;
                for (int child : group.children) tail = Math.max(tail, rank[child]);
                rank[g] = duration / compatible + tail;
            }
        }
        int[] degree = new int[c.groups.length], order = new int[c.groups.length];
        PriorityQueue<Integer> ready = new PriorityQueue<>((a, b) -> {
            int v = Double.compare(rank[b], rank[a]);
            return v == 0 ? Integer.compare(a, b) : v;
        });
        for (PackageCompiler.Group g : c.groups) {
            degree[g.id] = g.parents.length;
            if (degree[g.id] == 0) ready.add(g.id);
        }
        int k = 0;
        while (!ready.isEmpty()) {
            int g = ready.remove();
            order[k++] = g;
            for (int child : c.groups[g].children) if (--degree[child] == 0) ready.add(child);
        }
        return order;
    }

    static PackagePlan construct(Scenario s, int[][] partition, String mode, Random random) {
        PackageCompiler.Compiled c = PackageCompiler.compile(s, partition);
        return assign(s, partition, c, priority(s, c, mode, random));
    }

    static PackagePlan assign(Scenario s, int[][] partition, PackageCompiler.Compiled c, int[] order) {
        Calendar[] calendars = new Calendar[s.hosts.length];
        for (int h = 0; h < calendars.length; h++) calendars[h] = new Calendar(s.hosts[h]);
        double[] finish = new double[c.groups.length];
        int[] hosts = new int[c.groups.length];
        for (int id : order) {
            PackageCompiler.Group g = c.groups[id];
            double release = 0;
            for (int p : g.parents) release = Math.max(release, finish[p]);
            int best = -1;
            double bestFinish = Double.POSITIVE_INFINITY, bestStart = Double.NaN;
            for (int h = 0; h < s.hosts.length; h++)
                if (g.compatible[h]) {
                    double duration = nominal(s, g, h),
                            start = calendars[h].firstFit(release, duration, g.cores, g.memory),
                            end = start + duration;
                    if (end < bestFinish) {
                        best = h;
                        bestFinish = end;
                        bestStart = start;
                    }
                }
            if (best < 0) throw new IllegalArgumentException("no representable nominal assignment");
            hosts[id] = best;
            finish[id] = bestFinish;
            calendars[best].add(bestStart, bestFinish, g.cores, g.memory);
        }
        return new PackagePlan(partition, hosts, order);
    }
    /** A cumulative resource calendar, including insertion into earlier feasible gaps. */
    private static final class Calendar {
        final Scenario.Host host;
        final TreeMap<Double, long[]> changes = new TreeMap<>();

        Calendar(Scenario.Host host) {
            this.host = host;
        }

        void add(double start, double end, int cores, long memory) {
            if (end == start) return;
            long[] a = changes.computeIfAbsent(start, k -> new long[2]),
                    b = changes.computeIfAbsent(end, k -> new long[2]);
            a[0] += cores;
            a[1] += memory;
            b[0] -= cores;
            b[1] -= memory;
        }

        double firstFit(double release, double duration, int cores, long memory) {
            long cpu = 0, ram = 0;
            double point = release, candidate = Double.NaN;
            for (Map.Entry<Double, long[]> e : changes.headMap(release, true).entrySet()) {
                cpu += e.getValue()[0];
                ram += e.getValue()[1];
            }
            for (Map.Entry<Double, long[]> e : changes.tailMap(release, false).entrySet()) {
                if (cores <= host.cores - cpu && memory <= host.memory - ram) {
                    if (Double.isNaN(candidate)) candidate = point;
                    if (candidate + duration <= e.getKey()) return candidate;
                } else candidate = Double.NaN;
                cpu += e.getValue()[0];
                ram += e.getValue()[1];
                point = e.getKey();
            }
            if (cores > host.cores - cpu || memory > host.memory - ram)
                throw new IllegalStateException("unbalanced nominal calendar");
            return Double.isNaN(candidate) ? point : candidate;
        }
    }
}
