package org.workflowsim;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.cloudbus.cloudsim.Cloudlet;
import org.junit.jupiter.api.Test;
import org.workflowsim.utils.Parameters.FileType;

class TaskProcessingCostTest {

    @Test
    void pricesSubMegabyteDeclaredFilesContinuouslyInsteadOfRoundingEachFileDown() throws Exception {
        Task task = new Task(1, 100L);
        task.setCloudletStatus(Cloudlet.SUCCESS);
        task.setResourceParameter(0, 0.0, 4.0);
        task.addFile(file("input", 500_000.0, FileType.INPUT));
        task.addFile(file("output", 250_000.0, FileType.OUTPUT));

        // 0.75 decimal MB multiplied by 4.0; the former integer-per-file implementation returned 0.
        assertEquals(3.0, task.getProcessingCost(), 1.0e-12);
    }

    private static FileItem file(String name, double bytes, FileType type) {
        FileItem file = new FileItem(name, bytes);
        file.setType(type);
        return file;
    }
}
