package org.workflowsim.model;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.workflowsim.Task;

/**
 * 手算微型 DAG 结构测试:验证 Task 的父子关系、深度语义。
 * 这些测试不启动 CloudSim 引擎,只验证模型层行为。
 */
class TaskDagTest {

    private Task taskA;
    private Task taskB;
    private Task taskC;

    @BeforeEach
    void setUp() {
        // 3 个任务:A(1000 MI), B(2000 MI), C(3000 MI)
        taskA = new Task(1, 1000);
        taskB = new Task(2, 2000);
        taskC = new Task(3, 3000);
    }

    @Test
    void singleTaskHasNoParentsOrChildren() {
        assertTrue(taskA.getParentList().isEmpty());
        assertTrue(taskA.getChildList().isEmpty());
    }

    @Test
    void chainDependency() {
        // A -> B -> C
        taskB.addParent(taskA);
        taskA.addChild(taskB);
        taskC.addParent(taskB);
        taskB.addChild(taskC);

        assertEquals(1, taskB.getParentList().size());
        assertEquals(taskA, taskB.getParentList().get(0));
        assertEquals(1, taskB.getChildList().size());
        assertEquals(taskC, taskB.getChildList().get(0));

        assertTrue(taskA.getParentList().isEmpty());
        assertTrue(taskC.getChildList().isEmpty());
    }

    @Test
    void forkJoinDag() {
        // A -> B, A -> C (fork); B -> D, C -> D (join)
        Task taskD = new Task(4, 4000);

        taskA.addChild(taskB);
        taskA.addChild(taskC);
        taskB.addParent(taskA);
        taskC.addParent(taskA);

        taskB.addChild(taskD);
        taskC.addChild(taskD);
        taskD.addParent(taskB);
        taskD.addParent(taskC);

        assertEquals(2, taskA.getChildList().size());
        assertEquals(2, taskD.getParentList().size());
    }

    @Test
    void taskLengthIsPreserved() {
        assertEquals(1000, taskA.getCloudletLength());
        assertEquals(2000, taskB.getCloudletLength());
        assertEquals(3000, taskC.getCloudletLength());
    }

    @Test
    void taskIdIsPreserved() {
        assertEquals(1, taskA.getCloudletId());
        assertEquals(2, taskB.getCloudletId());
    }

    @Test
    void depthCanBeSet() {
        taskA.setDepth(1);
        taskB.setDepth(2);
        assertEquals(1, taskA.getDepth());
        assertEquals(2, taskB.getDepth());
    }

    @Test
    void vmIdAssignment() {
        taskA.setVmId(5);
        assertEquals(5, taskA.getVmId());
    }
}
