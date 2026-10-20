package org.workflowsim.clustering.balancing.methods;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.workflowsim.Task;
import org.workflowsim.clustering.TaskSet;

/**
 * M7 杀手（审查工程 Phase 3：变异试点存活的针对性回归）。
 *
 * <p>变异试点实证：{@code BalancingMethod.addTaskSet2TaskSet} 子侧重接循环丢失
 * {@code taskSet.getParentList().remove(tail)} 后，<b>全部 1024 个既有测试无一失败</b>
 * ——合并原语的邻接解除契约此前被下游 {@code updateTaskSetDependencies()} 的整体重建
 * 掩护（表现为"污垢被后续阶段洗白"），单调用语义无任何测试锚。</p>
 *
 * <p>本测试直接对合并原语建立<b>邻接闭包契约</b>（守恒律，不抄实现）：
 * tail 并入 head 后，所有原 tail 邻居的邻接表必须<b>只指向 head 而不再指向
 * tail</b>；tail 自身三表清空。</p>
 */
class AddTaskSet2TaskSetAdjacencyTest {

    @Test
    void mergingTailRewiresEveryNeighbourAdjacencyExclusivelyToHead() {
        Map levelMap = new HashMap();
        Map<Task, TaskSet> taskMap = new HashMap<>();
        BalancingMethod method = new BalancingMethod(levelMap, taskMap, 4);

        TaskSet grandparent = new TaskSet();
        TaskSet tail = new TaskSet();
        TaskSet head = new TaskSet();
        TaskSet child = new TaskSet();
        Task tailTask = new Task(1, 100L);
        tail.addTask(tailTask);
        taskMap.put(tailTask, tail);

        // 图：grandparent → tail → child（tail 两侧各一邻居）。
        grandparent.getChildList().add(tail);
        tail.getParentList().add(grandparent);
        tail.getChildList().add(child);
        child.getParentList().add(tail);

        method.addTaskSet2TaskSet(tail, head);

        // head 接管 tail 的全部邻接。
        assertTrue(head.getParentList().contains(grandparent), "head 必须接管父侧邻接");
        assertTrue(head.getChildList().contains(child), "head 必须接管子侧邻接");
        assertTrue(head.getTaskList().contains(tailTask), "head 必须接收任务成员");
        assertTrue(taskMap.get(tailTask) == head, "任务映射必须改指 head");

        // 邻居必须解除对 tail 的引用（M7 变异丢失该解除——本组断言即杀手）。
        assertFalse(child.getParentList().contains(tail),
                "child 的父表不得残留 tail（丢失 remove(tail) 的变异在此暴露）");
        assertFalse(grandparent.getChildList().contains(tail),
                "grandparent 的子表不得残留 tail");
        assertTrue(child.getParentList().contains(head));
        assertTrue(grandparent.getChildList().contains(head));

        // tail 三表清空。
        assertTrue(tail.getTaskList().isEmpty() && tail.getParentList().isEmpty()
                && tail.getChildList().isEmpty(), "被并集合的三张表必须清空");
    }

    @Test
    void mergingIntoDirectChildKeepsHeadFreeOfSelfLoops() {
        // F-008 回归隔壁位：tail → head 是父子关系时（VerticalBalancing 场景），
        // head 不得获得自环。
        Map levelMap = new HashMap();
        Map<Task, TaskSet> taskMap = new HashMap<>();
        BalancingMethod method = new BalancingMethod(levelMap, taskMap, 4);

        TaskSet tail = new TaskSet();
        TaskSet head = new TaskSet();
        TaskSet downstream = new TaskSet();
        tail.getChildList().add(head);
        tail.getChildList().add(downstream);
        head.getParentList().add(tail);
        downstream.getParentList().add(tail);

        method.addTaskSet2TaskSet(tail, head);

        assertFalse(head.getParentList().contains(head), "head 不得成为自身的父");
        assertFalse(head.getChildList().contains(head), "head 不得成为自身的子");
        assertTrue(head.getChildList().contains(downstream), "第二个子侧邻居必须正常重接");
        assertFalse(downstream.getParentList().contains(tail));
    }
}
