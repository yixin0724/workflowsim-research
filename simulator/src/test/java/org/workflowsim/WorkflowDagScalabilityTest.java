package org.workflowsim;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;

/** Count the original linear reciprocal-list membership cost, not machine-dependent wall time. */
class WorkflowDagScalabilityTest {
    @Test
    void fanOutReciprocalValidationAvoidsQuadraticListMembership() {
        for (int width : new int[] {256, 1024}) {
            Graph graph = star(width, true);
            WorkflowDagValidator.validateAndAssignDepths(graph.tasks);
            assertEquals(1, graph.tasks.get(0).getDepth());
            for (int i = 1; i < graph.tasks.size(); i++) assertEquals(2, graph.tasks.get(i).getDepth());
            assertLinearMembershipBudget(graph, width);
        }
    }

    @Test
    void fanInReciprocalValidationAvoidsQuadraticListMembership() {
        for (int width : new int[] {256, 1024}) {
            Graph graph = star(width, false);
            WorkflowDagValidator.validateAndAssignDepths(graph.tasks);
            assertEquals(2, graph.tasks.get(0).getDepth());
            for (int i = 1; i < graph.tasks.size(); i++) assertEquals(1, graph.tasks.get(i).getDepth());
            assertLinearMembershipBudget(graph, width);
        }
    }

    @Test
    void denseTwoLayerGraphRetainsDepthsWithoutRepeatedReverseScans() {
        int width = 64;
        Graph graph = new Graph();
        for (int i = 0; i < 2 * width; i++) graph.add(i + 1);
        for (int left = 0; left < width; left++) for (int right = width; right < 2 * width; right++) {
            connect(graph.tasks.get(left), graph.tasks.get(right));
        }
        WorkflowDagValidator.validateAndAssignDepths(graph.tasks);
        for (int i = 0; i < graph.tasks.size(); i++) assertEquals(i < width ? 1 : 2, graph.tasks.get(i).getDepth());
        assertLinearMembershipBudget(graph, width * width);
    }

    @Test
    void everyValidationObservesCurrentAdjacencyRatherThanAPreviousIndex() {
        Graph graph = star(8, true);
        WorkflowDagValidator.validateAndAssignDepths(graph.tasks);
        Task root = graph.tasks.get(0), changed = graph.tasks.get(1);
        root.getChildList().remove(changed);
        WorkflowValidationException error = assertThrows(WorkflowValidationException.class,
                () -> WorkflowDagValidator.validateAndAssignDepths(graph.tasks));
        assertTrue(error.getMessage().contains("asymmetric parent"), error.getMessage());
        changed.getParentList().clear();
        WorkflowDagValidator.validateAndAssignDepths(graph.tasks);
        assertEquals(1, changed.getDepth());
        assertEquals(2, graph.tasks.get(2).getDepth());
    }

    @Test
    void neighbourErrorKindsAndTraversalOrderRemainExplicit() {
        Graph duplicateParent = star(1, true);
        duplicateParent.tasks.get(1).addParent(duplicateParent.tasks.get(0));
        reject(duplicateParent, "duplicate parent");
        Graph duplicateChild = star(1, true);
        duplicateChild.tasks.get(0).addChild(duplicateChild.tasks.get(1));
        reject(duplicateChild, "duplicate child");
        Graph asymmetric = star(1, true);
        asymmetric.tasks.get(1).getParentList().clear();
        reject(asymmetric, "asymmetric child");
        Graph self = star(1, true);
        self.tasks.get(0).getParentList().add(self.tasks.get(0));
        reject(self, "self dependency");
        Graph foreign = star(1, true);
        foreign.tasks.get(1).addParent(new Task(999, 1000));
        reject(foreign, "outside this workflow as parent");
        Graph nullNeighbour = star(1, true);
        nullNeighbour.tasks.get(1).getParentList().add(null);
        reject(nullNeighbour, "outside this workflow as parent");
        Graph cycle = star(1, true);
        connect(cycle.tasks.get(1), cycle.tasks.get(0));
        reject(cycle, "contains a cycle");
    }

    @Test
    void malformedNullAdjacencyIsAWorkflowValidationErrorNotAnInternalNullPointer() {
        Graph nullParents = star(1, true);
        nullParents.tasks.get(1).setParentList(null);
        assertThrows(WorkflowValidationException.class,
                () -> WorkflowDagValidator.validateAndAssignDepths(nullParents.tasks));
        Graph nullChildren = star(1, true);
        nullChildren.tasks.get(0).setChildList(null);
        assertThrows(WorkflowValidationException.class,
                () -> WorkflowDagValidator.validateAndAssignDepths(nullChildren.tasks));
    }

    @Test
    void seededDagDepthsMatchAnIndependentIndexOrderOracleAfterPermutation() {
        for (int seed = 0; seed < 32; seed++) {
            Random random = new Random(seed);
            int count = 24;
            Graph graph = new Graph();
            List<Integer> ids = new ArrayList<>();
            for (int i = 0; i < count; i++) ids.add(7 + i * 31);
            Collections.shuffle(ids, random);
            for (int id : ids) graph.add(id);
            int[] expectedDepth = new int[count];
            for (int child = 0; child < count; child++) {
                expectedDepth[child] = 1;
                for (int parent = 0; parent < child; parent++) {
                    if (random.nextInt(5) == 0) {
                        connect(graph.tasks.get(parent), graph.tasks.get(child));
                        expectedDepth[child] = Math.max(expectedDepth[child], expectedDepth[parent] + 1);
                    }
                }
            }
            List<Task> permuted = new ArrayList<>(graph.tasks);
            Collections.shuffle(permuted, random);
            WorkflowDagValidator.validateAndAssignDepths(permuted);
            for (int i = 0; i < count; i++) assertEquals(expectedDepth[i], graph.tasks.get(i).getDepth(), "seed=" + seed + " node=" + i);
        }
    }

    private static void reject(Graph graph, String message) {
        WorkflowValidationException error = assertThrows(WorkflowValidationException.class,
                () -> WorkflowDagValidator.validateAndAssignDepths(graph.tasks));
        assertTrue(error.getMessage().contains(message), error.getMessage());
    }

    private static Graph star(int width, boolean fanOut) {
        Graph graph = new Graph();
        Task centre = graph.add(1);
        for (int i = 0; i < width; i++) {
            Task leaf = graph.add(2 + i);
            if (fanOut) connect(centre, leaf); else connect(leaf, centre);
        }
        return graph;
    }

    private static void connect(Task parent, Task child) { parent.addChild(child); child.addParent(parent); }

    private static void assertLinearMembershipBudget(Graph graph, int edges) {
        long budget = 16L * (graph.tasks.size() + edges);
        System.out.println("DAG_MEMBERSHIP_AUDIT nodes=" + graph.tasks.size() + " edges=" + edges
                + " sourceListComparisons=" + graph.counter.comparisons + " budget=" + budget);
        assertTrue(graph.counter.comparisons <= budget,
                "Reciprocal adjacency must not repeatedly linearly scan high-degree lists: nodes="
                        + graph.tasks.size() + " edges=" + edges + " comparisons=" + graph.counter.comparisons
                        + " linear budget=" + budget);
    }

    private static final class Counter { private long comparisons; }
    private static final class CountingList extends ArrayList<Task> {
        private final Counter counter;
        CountingList(Counter counter) { this.counter = counter; }
        @Override public boolean contains(Object sought) {
            for (Task task : this) {
                counter.comparisons++;
                if (sought == null ? task == null : sought.equals(task)) return true;
            }
            return false;
        }
    }
    private static final class Graph {
        private final Counter counter = new Counter();
        private final List<Task> tasks = new ArrayList<>();
        Task add(int id) {
            Task task = new Task(id, 1000L);
            task.setParentList(new CountingList(counter)); task.setChildList(new CountingList(counter));
            tasks.add(task); return task;
        }
    }
}
