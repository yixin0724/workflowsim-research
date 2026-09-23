package org.workflowsim.utils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import org.junit.jupiter.api.Test;

class TaskCostMatrixTest {

    private TaskCostMatrix sample() {
        return TaskCostMatrix.builder()
                .put(1, 0, 14.0).put(1, 1, 13.0).put(1, 2, 11.0)
                .put(2, 0, 13.0).put(2, 1, 16.0).put(2, 2, 9.0)
                .build();
    }

    @Test
    void getCostSecondsReturnsRegisteredEntries() {
        TaskCostMatrix matrix = sample();
        assertEquals(14.0, matrix.getCostSeconds(1, 0), 0.0);
        assertEquals(13.0, matrix.getCostSeconds(1, 1), 0.0);
        assertEquals(11.0, matrix.getCostSeconds(1, 2), 0.0);
        assertEquals(9.0, matrix.getCostSeconds(2, 2), 0.0);
        assertEquals(2, matrix.getTaskIds().size());
    }

    @Test
    void missingEntriesFailFast() {
        TaskCostMatrix matrix = sample();
        IllegalArgumentException task = assertThrows(IllegalArgumentException.class,
                () -> matrix.getCostSeconds(3, 0));
        assertTrue(task.getMessage().contains("task 3"));
        IllegalArgumentException vm = assertThrows(IllegalArgumentException.class,
                () -> matrix.getCostSeconds(1, 9));
        assertTrue(vm.getMessage().contains("VM 9"));
        assertFalse(matrix.contains(1, 9));
        assertTrue(matrix.contains(1, 0));
    }

    @Test
    void builderRejectsNonPositiveAndNonFiniteSeconds() {
        assertThrows(IllegalArgumentException.class,
                () -> TaskCostMatrix.builder().put(1, 0, 0.0));
        assertThrows(IllegalArgumentException.class,
                () -> TaskCostMatrix.builder().put(1, 0, -1.0));
        assertThrows(IllegalArgumentException.class,
                () -> TaskCostMatrix.builder().put(1, 0, Double.NaN));
        assertThrows(IllegalArgumentException.class,
                () -> TaskCostMatrix.builder().put(1, 0, Double.POSITIVE_INFINITY));
    }

    @Test
    void builderRejectsDuplicatesAndEmptyBuild() {
        assertThrows(IllegalArgumentException.class,
                () -> TaskCostMatrix.builder().put(1, 0, 2.0).put(1, 0, 3.0));
        assertThrows(IllegalArgumentException.class, () -> TaskCostMatrix.builder().build());
    }

    @Test
    void evidenceSnapshotPreservesSparseEntriesAndCannotBeMutated() {
        TaskCostMatrix matrix = TaskCostMatrix.builder().put(9, 8, 2.25).put(9, 3, 4.5).build();
        assertEquals(2.25, matrix.asMap().get(9).get(8), 0.0);
        assertEquals(4.5, matrix.getCostsForTask(9).get(3), 0.0);
        assertThrows(UnsupportedOperationException.class, () -> matrix.asMap().clear());
        assertThrows(UnsupportedOperationException.class, () -> matrix.asMap().get(9).put(8, 5.0));
        assertThrows(UnsupportedOperationException.class, () -> matrix.getCostsForTask(9).clear());
        assertThrows(IllegalArgumentException.class, () -> matrix.getCostsForTask(10));
    }

    @Test
    void coversReflectsExactCoverage() {
        TaskCostMatrix matrix = sample();
        assertTrue(matrix.covers(Arrays.asList(1, 2), Arrays.asList(0, 1, 2)));
        assertFalse(matrix.covers(Arrays.asList(1, 3), Arrays.asList(0, 1, 2)));
        assertFalse(matrix.covers(Arrays.asList(1), Arrays.asList(0, 3)));
    }
}
