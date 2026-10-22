package org.workflowsim.grouped;

import java.util.ArrayList;
import java.util.List;

import com.google.gson.JsonObject;

/** Complete enumeration of the declared decoder family, restricted to tiny inputs. */
public final class ExactSolver {
    private final Scenario s;
    private PackagePlan best;
    private double optimum = Double.POSITIVE_INFINITY;
    private long evaluated, partitions;

    private ExactSolver(Scenario scenario) {
        s = scenario;
    }

    public static JsonObject solve(JsonObject request) {
        long started = System.nanoTime();
        Scenario s = new Scenario(request.getAsJsonObject("scenario"));
        Scenario.require(
                s.tasks.length <= 7 && s.hosts.length <= 3,
                "exact reference restricted to at most 7 tasks and 3 hosts");
        ExactSolver solver = new ExactSolver(s);
        solver.partitions(0, new ArrayList<>());
        GroupedSimulator.Result r = GroupedSimulator.evaluate(s, solver.best, true);
        JsonObject out = new JsonObject();
        out.add("plan", solver.best.toJson(s));
        out.add("evaluation", r.toJson(s, solver.best, true));
        out.addProperty("complete", true);
        out.addProperty(
                "scope",
                "all legal path partitions, compatible assignments and topological priorities in the fixed "
                        + "work-conserving decoder family");
        out.addProperty("legal_partitions", solver.partitions);
        out.addProperty("enumerated_plans", solver.evaluated);
        out.addProperty("solver_seconds", (System.nanoTime() - started) * 1e-9);
        return out;
    }

    private void partitions(int t, List<List<Integer>> groups) {
        if (t == s.tasks.length) {
            int[][] partition = new int[groups.size()][];
            for (int i = 0; i < partition.length; i++) {
                partition[i] = Scenario.ints(groups.get(i));
            }
            PackageCompiler.Compiled c;
            try {
                c = PackageCompiler.compile(s, partition);
            } catch (IllegalArgumentException infeasible) {
                return;
            }
            partitions++;
            assign(partition, c, 0, new int[partition.length]);
            return;
        }
        int existing = groups.size();
        for (int i = 0; i < existing; i++) {
            List<Integer> group = groups.get(i);
            group.add(t);
            partitions(t + 1, groups);
            group.remove(group.size() - 1);
        }
        List<Integer> singleton = new ArrayList<>();
        singleton.add(t);
        groups.add(singleton);
        partitions(t + 1, groups);
        groups.remove(groups.size() - 1);
    }

    private void assign(int[][] partition, PackageCompiler.Compiled c, int g, int[] hosts) {
        if (g == partition.length) {
            orders(partition, c, hosts, 0, new int[partition.length], new boolean[partition.length]);
            return;
        }
        for (int h = 0; h < s.hosts.length; h++) {
            if (c.groups[g].compatible[h]) {
                hosts[g] = h;
                assign(partition, c, g + 1, hosts);
            }
        }
    }

    private void orders(
            int[][] partition, PackageCompiler.Compiled c, int[] hosts, int pos, int[] order, boolean[] used) {
        if (pos == order.length) {
            PackagePlan p = new PackagePlan(partition, hosts, order);
            double value = GroupedSimulator.evaluate(s, p, false).makespan;
            evaluated++;
            if (value < optimum) {
                optimum = value;
                best = p;
            }
            return;
        }
        for (PackageCompiler.Group g : c.groups) {
            if (!used[g.id]) {
                boolean ready = true;
                for (int parent : g.parents) {
                    ready &= used[parent];
                }
                if (!ready) {
                    continue;
                }
                used[g.id] = true;
                order[pos] = g.id;
                orders(partition, c, hosts, pos + 1, order, used);
                used[g.id] = false;
            }
        }
    }
}
