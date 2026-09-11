package org.workflowsim;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.Collections;
import org.cloudbus.cloudsim.Cloudlet;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.workflowsim.utils.Parameters.FileType;
import org.workflowsim.utils.ReplicaCatalog;

class WorkflowDatacenterOutputCommitTest {

    @AfterEach
    void resetReplicaCatalog() {
        ReplicaCatalog.reset();
    }

    @Test
    void commitsOnlySuccessfulTaskOutputsWhenAClusteredAttemptContainsMixedOutcomes() throws Exception {
        ReplicaCatalog.init(ReplicaCatalog.FileSystem.LOCAL);
        Task successful = task(1, "successful-output", Cloudlet.SUCCESS);
        Task failed = task(2, "failed-output", Cloudlet.FAILED);

        assertEquals(1, WorkflowDatacenter.registerSuccessfulTaskOutputs(successful, "7"));
        assertEquals(0, WorkflowDatacenter.registerSuccessfulTaskOutputs(failed, "7"));
        assertEquals(Collections.singletonList("7"),
                ReplicaCatalog.getStorageList("successful-output"));
        assertNull(ReplicaCatalog.getStorageList("failed-output"),
                "A failed Task output must not become a readable logical replica");
    }

    private static Task task(int id, String output, int status) throws Exception {
        Task task = new Task(id, 100L);
        task.setCloudletStatus(status);
        FileItem file = new FileItem(output, 1.0);
        file.setType(FileType.OUTPUT);
        task.addFile(file);
        return task;
    }
}
