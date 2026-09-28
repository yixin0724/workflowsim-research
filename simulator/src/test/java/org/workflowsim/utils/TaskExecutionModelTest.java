package org.workflowsim.utils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Collections;
import org.junit.jupiter.api.Test;
import org.workflowsim.Task;

class TaskExecutionModelTest {
    @Test
    void matrixUsesTheSameRoundedMiForPlanningAndExecution() {
        Task task = new Task(1, 100L);
        task.setVmExecutionCostSeconds(Collections.singletonMap(7, 1.24));
        assertEquals(2L, TaskExecutionModel.executionLengthMi(task, 7, 2.0));
        assertEquals(1.0, TaskExecutionModel.executionSeconds(task, 7, 2.0), 0.0);
        assertEquals(100L, task.getCloudletLength());
    }

    @Test
    void presentProjectionNeverFallsBackForMissingCoordinates() {
        Task task = new Task(1, 100L);
        task.setVmExecutionCostSeconds(Collections.singletonMap(7, 1.0));
        assertThrows(IllegalArgumentException.class, () -> TaskExecutionModel.executionLengthMi(task, 8, 1000.0));
    }

    @Test
    void representabilityIncludesPesAndTheMillionInstructionsPerMi() {
        long limit = Long.MAX_VALUE / 1_000_000L;
        assertEquals(limit, TaskExecutionModel.requireRepresentableLength(limit, 1));
        assertEquals(limit / 2, TaskExecutionModel.requireRepresentableLength(limit / 2, 2));
        assertThrows(IllegalArgumentException.class, () -> TaskExecutionModel.requireRepresentableLength(limit + 1, 1));
        assertThrows(IllegalArgumentException.class, () -> TaskExecutionModel.requireRepresentableLength(limit / 2 + 1, 2));
        assertThrows(IllegalArgumentException.class, () -> TaskExecutionModel.requireRepresentableLength(0, 1));
        assertThrows(IllegalArgumentException.class, () -> TaskExecutionModel.requireRepresentableLength(1, 0));
    }

    @Test
    void matrixRejectsZeroRoundingAndUnrepresentableWork() {
        Task task = new Task(1, 100L);
        task.setVmExecutionCostSeconds(Collections.singletonMap(7, 0.1));
        assertThrows(IllegalArgumentException.class, () -> TaskExecutionModel.executionLengthMi(task, 7, 1.0));
        task.setVmExecutionCostSeconds(Collections.singletonMap(7, 10_000_000.0));
        assertThrows(IllegalArgumentException.class,
                () -> TaskExecutionModel.executionLengthMi(task, 7, 1_000_000.0));
        task.setVmExecutionCostSeconds(Collections.singletonMap(7, Double.MAX_VALUE));
        assertThrows(IllegalArgumentException.class, () -> TaskExecutionModel.executionLengthMi(task, 7, 1000.0));
    }

    @Test
    void rawSinglePeWorkIsNotMultipliedByTheTaskPeCount() {
        Task task = new Task(1, 100L);
        task.setNumberOfPes(2);
        assertEquals(50.0, TaskExecutionModel.executionSeconds(task, 7, 2.0), 0.0);
        assertThrows(IllegalArgumentException.class, () -> TaskExecutionModel.executionSeconds(task, 7, Double.NaN));
        assertThrows(IllegalArgumentException.class, () -> TaskExecutionModel.executionSeconds(task, 7, 0.0));
    }
}
