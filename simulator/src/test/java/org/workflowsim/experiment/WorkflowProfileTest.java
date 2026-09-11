package org.workflowsim.experiment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Arrays;
import java.util.Collections;
import org.junit.jupiter.api.Test;
import org.workflowsim.FileItem;
import org.workflowsim.Task;
import org.workflowsim.utils.Parameters.FileType;

class WorkflowProfileTest {

    @Test
    void profilesTheParsedGraphAndDistinguishesExternalInputs() {
        Task root = task(1, 100L);
        Task firstChild = task(2, 200L);
        Task secondChild = task(3, 300L);
        connect(root, firstChild);
        connect(root, secondChild);

        root.addFile(file("generated.dat", 200.0, FileType.OUTPUT));
        firstChild.addFile(file("generated.dat", 200.0, FileType.INPUT));
        firstChild.addFile(file("external.dat", 50.0, FileType.INPUT));
        secondChild.addFile(file("external.dat", 50.0, FileType.INPUT));

        WorkflowProfile profile = WorkflowProfile.fromTasks(Arrays.asList(secondChild, root, firstChild));

        assertEquals(3, profile.getTaskCount());
        assertEquals(2, profile.getEdgeCount());
        assertEquals(1, profile.getRootTaskCount());
        assertEquals(2, profile.getLeafTaskCount());
        assertEquals(1, profile.getMaximumDepthEdges());
        assertEquals(2, profile.getMaximumWidthTasks());
        assertEquals(600L, profile.getTotalTaskLengthMi());
        assertEquals(200.0, profile.getMeanTaskLengthMi(), 0.0);
        assertEquals(Math.sqrt(1.0 / 6.0), profile.getTaskLengthCoefficientOfVariation(), 1.0e-12);
        assertEquals(4, profile.getFileReferenceCount());
        assertEquals(2, profile.getDistinctFileCount());
        assertEquals(250.0, profile.getTotalDistinctFileBytes(), 0.0);
        assertEquals(2, profile.getExternalInputReferenceCount());
        assertEquals(1, profile.getDistinctExternalInputFileCount());
        assertEquals(50.0, profile.getTotalDistinctExternalInputBytes(), 0.0);
    }

    @Test
    void rejectsAcyclicLookingButAsymmetricEdges() {
        Task first = task(1, 10L);
        Task second = task(2, 10L);
        first.addChild(second);

        assertThrows(IllegalArgumentException.class,
                () -> WorkflowProfile.fromTasks(Arrays.asList(first, second)));
    }

    @Test
    void profilesAnEmptyTaskListAsAnEmptyWorkflow() {
        WorkflowProfile profile = WorkflowProfile.fromTasks(Collections.<Task>emptyList());
        assertEquals(0, profile.getTaskCount());
        assertEquals(0, profile.getMaximumWidthTasks());
    }

    private static Task task(int id, long length) {
        return new Task(id, length);
    }

    private static void connect(Task parent, Task child) {
        parent.addChild(child);
        child.addParent(parent);
    }

    private static FileItem file(String name, double size, FileType type) {
        FileItem result = new FileItem(name, size);
        result.setType(type);
        return result;
    }
}
