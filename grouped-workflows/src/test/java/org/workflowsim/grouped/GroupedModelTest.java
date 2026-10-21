package org.workflowsim.grouped;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.*;
import java.util.*;
import org.junit.jupiter.api.Test;

/** Hand-computable examples and rejection tests for physical package semantics. */
class GroupedModelTest {
    static JsonObject host(String id, int cores, long memory, double bandwidth) {
        JsonObject h = new JsonObject();
        h.addProperty("id", id);
        h.addProperty("cores", cores);
        h.addProperty("memory_bytes", memory);
        h.addProperty("bandwidth_bytes_per_second", bandwidth);
        return h;
    }

    static JsonArray strings(String... values) {
        JsonArray a = new JsonArray();
        for (String s : values) a.add(s);
        return a;
    }

    static JsonObject task(String id, int cores, long memory, double duration, String... parents) {
        JsonObject t = new JsonObject();
        t.addProperty("id", id);
        t.addProperty("cores", cores);
        t.addProperty("memory_bytes", memory);
        JsonObject ds = new JsonObject();
        ds.addProperty("H", duration);
        ds.addProperty("L", duration);
        t.add("duration_seconds", ds);
        t.add("parents", strings(parents));
        return t;
    }

    static JsonObject file(String id, String producer, double bytes, boolean retain, String... consumers) {
        JsonObject f = new JsonObject();
        f.addProperty("id", id);
        f.addProperty("producer", producer);
        f.addProperty("size_bytes", bytes);
        f.addProperty("retain", retain);
        f.add("consumers", strings(consumers));
        return f;
    }

    static JsonObject scenario(JsonObject... tasks) {
        JsonObject s = new JsonObject();
        s.addProperty("schema", "r2-scenario-v1");
        s.addProperty("id", "test");
        s.addProperty("storage_bandwidth_bytes_per_second", 1);
        s.addProperty("job_setup_seconds", 0);
        JsonArray hs = new JsonArray();
        hs.add(host("H", 8, 16, 100));
        hs.add(host("L", 2, 16, 100));
        s.add("hosts", hs);
        JsonArray ts = new JsonArray();
        for (JsonObject t : tasks) ts.add(t);
        s.add("tasks", ts);
        s.add("files", new JsonArray());
        return s;
    }

    static JsonObject path(double bytes) {
        JsonObject s =
                scenario(task("A", 8, 1, 2), task("B", 2, 1, 8, "A"), task("C", 4, 1, 2, "B"), task("D", 8, 1, 10));
        s.addProperty("job_setup_seconds", 1);
        s.getAsJsonArray("files").add(file("ab", "A", bytes, false, "B"));
        s.getAsJsonArray("files").add(file("bc", "B", bytes, false, "C"));
        return s;
    }

    static PackagePlan plan(int[][] groups, int... hosts) {
        int[] order = new int[groups.length];
        for (int i = 0; i < order.length; i++) order[i] = i;
        return new PackagePlan(groups, hosts, order);
    }

    static GroupedSimulator.Result run(JsonObject s, PackagePlan p) {
        return GroupedSimulator.evaluate(new Scenario(s), p, true);
    }

    @Test
    void multiBoundaryPlacementWitnessAndControls() {
        assertEquals(24, run(path(1), plan(new int[][] {{0, 1, 2}, {3}}, 0, 0)).makespan, 1e-10);
        assertEquals(27, run(path(1), plan(new int[][] {{0}, {1, 2}, {3}}, 0, 0, 0)).makespan, 1e-10);
        assertEquals(27, run(path(1), plan(new int[][] {{0, 1}, {2}, {3}}, 0, 0, 0)).makespan, 1e-10);
        PackagePlan split = plan(new int[][] {{0}, {1}, {2}, {3}}, 0, 1, 0, 0);
        GroupedSimulator.Result best = run(path(1), split);
        assertEquals(19, best.makespan, 1e-10);
        assertEquals(4, best.start[3], 1e-10);
        assertEquals(15, best.finish[1], 1e-10);
        assertEquals(27, run(path(3), split).makespan, 1e-10);
        JsonObject noCompetitor = path(1);
        noCompetitor.getAsJsonArray("tasks").remove(3);
        assertEquals(13, run(noCompetitor, plan(new int[][] {{0, 1, 2}}, 0)).makespan, 1e-10);
        assertEquals(19, run(noCompetitor, plan(new int[][] {{0}, {1}, {2}}, 0, 1, 0)).makespan, 1e-10);
    }

    @Test
    void peakMemoryIsHeldThroughOutput() {
        JsonObject s = scenario(task("A", 2, 6, 4), task("B", 2, 6, 3));
        s.getAsJsonArray("hosts").get(0).getAsJsonObject().addProperty("memory_bytes", 8);
        s.addProperty("storage_bandwidth_bytes_per_second", 2);
        s.getAsJsonArray("files").add(file("out", "A", 2, true));
        GroupedSimulator.Result r = run(s, plan(new int[][] {{0}, {1}}, 0, 0));
        assertEquals(5, r.start[1], 1e-10);
        assertEquals(8, r.makespan, 1e-10);
    }

    @Test
    void severalJobsShareCoresAndRam() {
        JsonObject s = scenario(task("A", 4, 4, 3), task("B", 4, 4, 5), task("C", 2, 2, 2));
        GroupedSimulator.Result r = run(s, plan(new int[][] {{0}, {1}, {2}}, 0, 0, 0));
        assertArrayEquals(new double[] {0, 0, 3}, r.start, 1e-10);
        assertEquals(5, r.makespan, 1e-10);
    }

    @Test
    void sharedServiceHasEndpointBottlenecks() {
        JsonObject s = scenario(task("A", 1, 1, 1), task("B", 1, 1, 1));
        s.addProperty("storage_bandwidth_bytes_per_second", 8);
        s.getAsJsonArray("hosts").get(0).getAsJsonObject().addProperty("bandwidth_bytes_per_second", 2);
        s.getAsJsonArray("hosts").get(1).getAsJsonObject().addProperty("bandwidth_bytes_per_second", 10);
        s.getAsJsonArray("files").add(file("a", null, 8, false, "A"));
        s.getAsJsonArray("files").add(file("b", null, 8, false, "B"));
        GroupedSimulator.Result r = run(s, plan(new int[][] {{0}, {1}}, 0, 1));
        assertEquals(5, r.makespan, 1e-10);
        assertEquals(4, r.inputEnd[0], 1e-10);
        assertEquals(4.0 / 3, r.inputEnd[1], 1e-10);
        assertEquals(7.0 / 3, r.finish[1], 1e-10);
    }

    @Test
    void internalRetainedOutputsRemainObligations() {
        JsonObject s = scenario(task("A", 1, 1, 2), task("B", 1, 1, 3, "A"));
        s.addProperty("storage_bandwidth_bytes_per_second", 2);
        s.getAsJsonArray("files").add(file("ab", "A", 4, true, "B"));
        GroupedSimulator.Result r = run(s, plan(new int[][] {{0, 1}}, 0));
        assertEquals(7, r.makespan, 1e-10);
        assertEquals(4, r.outputBytes, 1e-10);
        assertEquals(0, r.inputBytes, 1e-10);
        assertEquals(2, r.taskFinish[0], 1e-10);
        assertEquals(5, r.taskFinish[1], 1e-10);
    }

    @Test
    void sharedInputIsCountedOnceWithinAPackage() {
        JsonObject s = scenario(task("A", 1, 1, 1), task("B", 1, 1, 1, "A"));
        s.addProperty("storage_bandwidth_bytes_per_second", 8);
        s.getAsJsonArray("files").add(file("shared", null, 8, false, "A", "B"));
        GroupedSimulator.Result r = run(s, plan(new int[][] {{0, 1}}, 0));
        assertEquals(3, r.makespan, 1e-10);
        assertEquals(8, r.inputBytes, 1e-10);
    }

    @Test
    void sameNodeStillUsesPrivateScratchHandoff() {
        JsonObject s = scenario(task("A", 1, 1, 1), task("B", 1, 1, 1, "A"));
        s.addProperty("storage_bandwidth_bytes_per_second", 8);
        s.getAsJsonArray("files").add(file("ab", "A", 8, false, "B"));
        assertEquals(4, run(s, plan(new int[][] {{0}, {1}}, 0, 0)).makespan, 1e-10);
        assertEquals(2, run(s, plan(new int[][] {{0, 1}}, 0)).makespan, 1e-10);
    }

    @Test
    void outsideConsumerWaitsForEntirePackage() {
        JsonObject s = scenario(task("A", 1, 1, 1), task("B", 1, 1, 4, "A"), task("C", 1, 1, 1, "A"));
        s.getAsJsonArray("files").add(file("a", "A", 1, false, "B", "C"));
        GroupedSimulator.Result r = run(s, plan(new int[][] {{0, 1}, {2}}, 0, 1));
        assertEquals(6, r.start[1], 1e-10);
        assertEquals(8, r.makespan, 1e-10);
    }

    @Test
    void bootstrapDoesNotDisappearAfterGrouping() {
        JsonObject s = scenario(task("A", 1, 1, 0), task("B", 1, 1, 0, "A"));
        s.getAsJsonArray("tasks").get(0).getAsJsonObject().addProperty("bootstrap_seconds", 2);
        s.getAsJsonArray("tasks").get(1).getAsJsonObject().addProperty("bootstrap_seconds", 3);
        assertEquals(5, run(s, plan(new int[][] {{0, 1}}, 0)).makespan, 1e-10);
    }

    @Test
    void zeroDurationsPreserveDependenciesAndNoArtificialTime() {
        JsonObject s = scenario(task("A", 1, 1, 0), task("B", 1, 1, 0, "A"), task("C", 1, 1, 1, "B"));
        assertEquals(1, run(s, plan(new int[][] {{0}, {1}, {2}}, 0, 0, 0)).makespan, 0);
        s.getAsJsonArray("tasks")
                .get(2)
                .getAsJsonObject()
                .getAsJsonObject("duration_seconds")
                .addProperty("H", 0);
        assertEquals(0, run(s, plan(new int[][] {{0}, {1}, {2}}, 0, 0, 0)).makespan, 0);
    }

    @Test
    void rejectsInvalidPartitionsAndPriority() {
        Scenario s = new Scenario(path(1));
        assertThrows(IllegalArgumentException.class, () -> PackageCompiler.compile(s, new int[][] {{0, 2}, {1}, {3}}));
        assertThrows(IllegalArgumentException.class, () -> PackageCompiler.compile(s, new int[][] {{0, 3}, {1}, {2}}));
        assertThrows(
                IllegalArgumentException.class, () -> PackageCompiler.compile(s, new int[][] {{0}, {1}, {1, 2, 3}}));
        assertThrows(IllegalArgumentException.class, () -> PackageCompiler.compile(s, new int[][] {{0}, {1}, {2}}));
        assertThrows(
                IllegalArgumentException.class,
                () -> GroupedSimulator.evaluate(
                        s,
                        new PackagePlan(
                                new int[][] {{0}, {1}, {2}, {3}}, new int[] {0, 1, 0, 0}, new int[] {1, 0, 2, 3}),
                        false));
        assertThrows(IllegalArgumentException.class, () -> run(path(1), plan(new int[][] {{0, 1, 2}, {3}}, 1, 0)));
    }

    @Test
    void contractionCycleIsCheckedSeparately() {
        JsonObject s = scenario(
                task("X1", 1, 1, 1),
                task("Y1", 1, 1, 1),
                task("X2", 1, 1, 1, "X1", "Y1"),
                task("Y2", 1, 1, 1, "X1", "Y1"));
        assertThrows(
                IllegalArgumentException.class,
                () -> PackageCompiler.compile(new Scenario(s), new int[][] {{0, 2}, {1, 3}}));
    }

    @Test
    void rejectsInvalidFileCausalityAndExactResourceFields() {
        JsonObject s = scenario(task("A", 1, 1, 1), task("B", 1, 1, 1));
        s.getAsJsonArray("files").add(file("f", "A", 1, false, "B"));
        assertThrows(IllegalArgumentException.class, () -> new Scenario(s));
        JsonObject bad = scenario(task("A", 1, 1, 1));
        bad.getAsJsonArray("tasks").get(0).getAsJsonObject().addProperty("memory_bytes", 1.5);
        assertThrows(IllegalArgumentException.class, () -> new Scenario(bad));
    }

    @Test
    void repeatedEvaluationsDoNotShareRuntimeState() {
        Scenario s = new Scenario(path(1));
        PackagePlan p = plan(new int[][] {{0}, {1}, {2}, {3}}, 0, 1, 0, 0);
        for (int i = 0; i < 20; i++) assertEquals(19, GroupedSimulator.evaluate(s, p, i % 2 == 0).makespan, 0);
    }

    @Test
    void commonConstructorsPreserveTheOriginalWorkAndFindFeasibleIntervals() {
        Scenario s = new Scenario(path(1));
        for (int[][] partition :
                new int[][][] {InitialPlans.singleton(s), InitialPlans.maximalChains(s), InitialPlans.pathDp(s, 64)}) {
            for (String mode : new String[] {"rank", "downstream", "random"}) {
                PackagePlan p = InitialPlans.construct(s, partition, mode, new Random(7));
                PackageCompiler.validate(s, p);
                assertTrue(GroupedSimulator.evaluate(s, p, true).makespan >= 0);
            }
        }
        JsonObject input = scenario(task("A", 4, 8, 5), task("B", 4, 8, 3), task("C", 4, 8, 2));
        Scenario independent = new Scenario(input);
        PackagePlan p = InitialPlans.construct(independent, InitialPlans.singleton(independent), "rank", new Random(0));
        assertEquals(5, GroupedSimulator.evaluate(independent, p, true).makespan, 1e-10);
    }

    @Test
    void fullHostsStillRecordBoundedBlockingObservations() {
        GroupedSimulator.Result r = run(path(1), plan(new int[][] {{0, 1, 2}, {3}}, 0, 0));
        assertTrue(r.blockers[1].contains(0));
    }

    @Test
    void smallTransfersAfterLongComputationUseClockAwarePrecision() {
        JsonObject input = scenario(task("A", 8, 4, 100000.125), task("B", 2, 4, .25, "A"));
        input.addProperty("storage_bandwidth_bytes_per_second", 1e9);
        for (JsonElement h : input.getAsJsonArray("hosts"))
            h.getAsJsonObject().addProperty("bandwidth_bytes_per_second", 1e9);
        input.getAsJsonArray("files").add(file("tiny", "A", 3, true, "B"));
        GroupedSimulator.Result r = run(input, plan(new int[][] {{0}, {1}}, 0, 1));
        assertEquals(100000.375 + 6e-9, r.makespan, 2 * Math.ulp(r.makespan));
        assertEquals(3, r.inputBytes);
        assertEquals(3, r.outputBytes);
    }
}
