package org.workflowsim;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 校验任务有向无环图（DAG）不变量，并以确定性顺序计算任务深度。
 *
 * <p>深度以根任务为 1，其他任务为最长父路径长度加 1。该计算既是输入校验的一部分，
 * 也为依赖层级相关的聚类和调度策略提供稳定的层级信息。</p>
 */
public final class WorkflowDagValidator {

    private WorkflowDagValidator() {
    }

    /**
     * 校验任务归属、父子边对称性、任务编号唯一性和无环性，并写入任务深度。
     *
     * <p>根任务深度为 1；其余任务深度为所有父任务中最大深度加 1。根和子任务均按
     * 任务编号排序处理，使同一输入在不同 JVM 运行中得到一致深度。</p>
     *
     * @param tasks 来自同一工作流输入的任务列表
     * @throws WorkflowValidationException 当任务列表不构成合法 DAG 时
     */
    public static void validateAndAssignDepths(List<Task> tasks) {
        if (tasks == null || tasks.isEmpty()) {
            throw new WorkflowValidationException("Workflow must contain at least one task");
        }

        Map<Integer, Task> taskById = new HashMap<>();
        Set<Task> taskSet = new HashSet<>();
        for (Task task : tasks) {
            if (task == null) {
                throw new WorkflowValidationException("Workflow cannot contain a null task");
            }
            if (task.getCloudletLength() <= 0L) {
                throw new WorkflowValidationException("Task " + task.getCloudletId()
                        + " has non-positive Cloudlet length");
            }
            if (taskById.put(task.getCloudletId(), task) != null) {
                throw new WorkflowValidationException("Duplicate task ID " + task.getCloudletId());
            }
            taskSet.add(task);
            task.setDepth(0);
        }

        Map<Task, Integer> remainingParents = new HashMap<>();
        for (Task task : tasks) {
            validateNeighbours(task, task.getParentList(), taskSet, true);
            validateNeighbours(task, task.getChildList(), taskSet, false);
            remainingParents.put(task, task.getParentList().size());
        }

        Deque<Task> ready = new ArrayDeque<>();
        List<Task> roots = new ArrayList<>();
        for (Task task : tasks) {
            if (remainingParents.get(task) == 0) {
                roots.add(task);
            }
        }
        roots.sort(Comparator.comparingInt(Task::getCloudletId));
        for (Task root : roots) {
            root.setDepth(1);
            ready.addLast(root);
        }

        int visited = 0;
        while (!ready.isEmpty()) {
            Task parent = ready.removeFirst();
            visited++;
            List<Task> children = new ArrayList<>(parent.getChildList());
            children.sort(Comparator.comparingInt(Task::getCloudletId));
            for (Task child : children) {
                // 以最长父路径定义深度，而非首次访问时的任意路径。
                child.setDepth(Math.max(child.getDepth(), parent.getDepth() + 1));
                int remaining = remainingParents.get(child) - 1;
                remainingParents.put(child, remaining);
                if (remaining == 0) {
                    ready.addLast(child);
                }
            }
        }

        if (visited != tasks.size()) {
            throw new WorkflowValidationException("Workflow graph contains a cycle");
        }
    }

    private static void validateNeighbours(Task task, List<Task> neighbours,
            Set<Task> taskSet, boolean parents) {
        Set<Task> unique = new HashSet<>();
        for (Task neighbour : neighbours) {
            String relation = parents ? "parent" : "child";
            if (neighbour == null || !taskSet.contains(neighbour)) {
                throw new WorkflowValidationException("Task " + task.getCloudletId()
                        + " references a task outside this workflow as " + relation);
            }
            if (neighbour == task) {
                throw new WorkflowValidationException("Task " + task.getCloudletId()
                        + " has a self dependency");
            }
            if (!unique.add(neighbour)) {
                throw new WorkflowValidationException("Task " + task.getCloudletId()
                        + " has a duplicate " + relation + " " + neighbour.getCloudletId());
            }
            boolean symmetric = parents
                    ? neighbour.getChildList().contains(task)
                    : neighbour.getParentList().contains(task);
            if (!symmetric) {
                throw new WorkflowValidationException("Task " + task.getCloudletId()
                        + " has an asymmetric " + relation + " relation with task "
                        + neighbour.getCloudletId());
            }
        }
    }
}
