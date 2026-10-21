package org.workflowsim.grouped;

import java.util.*;

/** Converts a legal path partition into jobs without changing task or file obligations. */
public final class PackageCompiler {
    private PackageCompiler() {}

    static final class Group {
        final int id;
        final int[] tasks;
        final int cores;
        final long memory;
        final boolean[] compatible;
        int[] parents, children, inputFiles, outputFiles;
        double inputBytes, outputBytes;

        Group(int id, int[] tasks, int cores, long memory, boolean[] compatible) {
            this.id = id;
            this.tasks = tasks;
            this.cores = cores;
            this.memory = memory;
            this.compatible = compatible;
        }
    }

    static final class Compiled {
        final Group[] groups;
        final int[] owner, topo;

        Compiled(Group[] groups, int[] owner, int[] topo) {
            this.groups = groups;
            this.owner = owner;
            this.topo = topo;
        }
    }

    static Compiled compile(Scenario s, int[][] partition) {
        Scenario.require(partition.length > 0, "empty partition");
        int[] owner = new int[s.tasks.length];
        Arrays.fill(owner, -1);
        Group[] groups = new Group[partition.length];
        for (int g = 0; g < groups.length; g++) {
            groups[g] = buildGroup(s, partition, g, owner);
        }
        for (int g : owner) Scenario.require(g >= 0, "partition omits a task");
        List<Set<Integer>> parents = new ArrayList<>(), children = new ArrayList<>();
        List<List<Integer>> inputs = new ArrayList<>(), outputs = new ArrayList<>();
        for (int g = 0; g < groups.length; g++) {
            parents.add(new TreeSet<Integer>());
            children.add(new TreeSet<Integer>());
            inputs.add(new ArrayList<Integer>());
            outputs.add(new ArrayList<Integer>());
        }
        buildGroupEdges(s, owner, parents, children);
        assignFileMembership(s, owner, groups, inputs, outputs);
        int[] topo = finalizeGroupsAndTopo(groups, parents, children, inputs, outputs);
        return new Compiled(groups, owner, topo);
    }

    /** Build one ordered, validated group. Mutates owner. */
    private static Group buildGroup(Scenario s, int[][] partition, int g, int[] owner) {
        Scenario.require(partition[g].length > 0, "empty group");
        Integer[] ordered = new Integer[partition[g].length];
        for (int k = 0; k < ordered.length; k++) {
            int t = partition[g][k];
            Scenario.require(t >= 0 && t < s.tasks.length, "unknown task index");
            Scenario.require(owner[t] < 0, "task appears more than once");
            owner[t] = g;
            ordered[k] = t;
        }
        Arrays.sort(ordered, (a, b) -> Integer.compare(s.topoPosition[a], s.topoPosition[b]));
        int[] tasks = new int[ordered.length];
        int cores = 0;
        long memory = 0;
        boolean[] compatible = new boolean[s.hosts.length];
        Arrays.fill(compatible, true);
        String domain = s.tasks[ordered[0]].domain;
        for (int k = 0; k < tasks.length; k++) {
            int t = ordered[k];
            tasks[k] = t;
            Scenario.Task task = s.tasks[t];
            Scenario.require(domain.equals(task.domain), "incompatible package domains");
            if (k > 0) Scenario.require(s.descendants[tasks[k - 1]].get(t), "group contains incomparable tasks");
            cores = Math.max(cores, task.cores);
            memory = Math.max(memory, task.memory);
            for (int h = 0; h < compatible.length; h++) compatible[h] &= Double.isFinite(task.duration[h]);
        }
        if (tasks.length > 1) {
            BitSet between = (BitSet) s.descendants[tasks[0]].clone();
            between.and(s.ancestors[tasks[tasks.length - 1]]);
            Scenario.require(between.cardinality() == tasks.length - 2, "group is not path convex");
        }
        boolean feasible = false;
        for (int h = 0; h < compatible.length; h++) {
            compatible[h] &= cores <= s.hosts[h].cores && memory <= s.hosts[h].memory;
            feasible |= compatible[h];
        }
        Scenario.require(feasible, "package has no feasible compatible host");
        return new Group(g, tasks, cores, memory, compatible);
    }

    /** Cross-group dependency edges. */
    private static void buildGroupEdges(
            Scenario s, int[] owner, List<Set<Integer>> parents, List<Set<Integer>> children) {
        for (int t = 0; t < s.tasks.length; t++)
            for (int p : s.tasks[t].parents)
                if (owner[t] != owner[p]) {
                    parents.get(owner[t]).add(owner[p]);
                    children.get(owner[p]).add(owner[t]);
                }
    }

    /** File membership and byte accounting per group. */
    private static void assignFileMembership(
            Scenario s, int[] owner, Group[] groups, List<List<Integer>> inputs, List<List<Integer>> outputs) {
        for (int f = 0; f < s.files.length; f++) {
            Scenario.DataFile file = s.files[f];
            int producing = file.producer < 0 ? -1 : owner[file.producer];
            Set<Integer> consuming = new TreeSet<>();
            for (int t : file.consumers) consuming.add(owner[t]);
            boolean external = false;
            for (int g : consuming)
                if (g != producing) {
                    inputs.get(g).add(f);
                    groups[g].inputBytes += file.bytes;
                    external = true;
                }
            if (producing >= 0 && (external || file.retain)) {
                outputs.get(producing).add(f);
                groups[producing].outputBytes += file.bytes;
            }
        }
    }

    /** Compact fields, validate sums, and produce a topological order. */
    private static int[] finalizeGroupsAndTopo(
            Group[] groups,
            List<Set<Integer>> parents,
            List<Set<Integer>> children,
            List<List<Integer>> inputs,
            List<List<Integer>> outputs) {
        int[] degrees = new int[groups.length], topo = new int[groups.length];
        PriorityQueue<Integer> ready = new PriorityQueue<>();
        for (int g = 0; g < groups.length; g++) {
            Group group = groups[g];
            group.parents = Scenario.ints(parents.get(g));
            group.children = Scenario.ints(children.get(g));
            group.inputFiles = Scenario.ints(inputs.get(g));
            group.outputFiles = Scenario.ints(outputs.get(g));
            Scenario.finiteNonnegative(group.inputBytes, "total input bytes");
            Scenario.finiteNonnegative(group.outputBytes, "total output bytes");
            degrees[g] = group.parents.length;
            if (degrees[g] == 0) ready.add(g);
        }
        int count = 0;
        while (!ready.isEmpty()) {
            int g = ready.remove();
            topo[count++] = g;
            for (int c : groups[g].children) if (--degrees[c] == 0) ready.add(c);
        }
        Scenario.require(count == groups.length, "contracted graph contains a cycle");
        return topo;
    }

    static Compiled validate(Scenario s, PackagePlan plan) {
        Compiled c = compile(s, plan.members);
        Scenario.require(
                plan.host.length == c.groups.length && plan.order.length == c.groups.length, "plan dimension mismatch");
        boolean[] seen = new boolean[c.groups.length];
        for (int g : plan.order) {
            Scenario.require(g >= 0 && g < c.groups.length && !seen[g], "priority is not a permutation");
            for (int p : c.groups[g].parents) Scenario.require(seen[p], "priority violates group dependencies");
            int h = plan.host[g];
            Scenario.require(h >= 0 && h < s.hosts.length && c.groups[g].compatible[h], "infeasible assignment");
            seen[g] = true;
        }
        return c;
    }
}
