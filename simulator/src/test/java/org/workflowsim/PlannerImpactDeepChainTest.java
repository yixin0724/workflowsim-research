package org.workflowsim;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import org.cloudbus.cloudsim.Log;
import org.cloudbus.cloudsim.core.CloudSim;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * C03：影响度深链传播的规模安全边界（审查工程 Phase 1 机械可靠性）。
 *
 * <p>契约来源（守恒律，不抄实现输出）：
 * {@code WorkflowPlanner.processImpactFactors} 沿出口任务向父边均分回传影响度。
 * 对一条<b>纯链</b>（单出口、每任务恰一父）：出口获得 1/1=1.0，每次回传 impact/size=1.0，
 * 因此<b>每个任务的影响度必须恰好为 1.0</b>（二进制精确相等，无舍入路径）。</p>
 *
 * <p>规模契约：解析器/parser 层未声明任何 DAG 深度上限，扫描性测试
 * （WorkflowDagScalabilityTest）与 V2 计划（DataflowFilePlan 已显式改为迭代遍历）
 * 都表明深 DAG 属于支持范围。因此 50 000 深度的链上，影响度计算必须
 * <b>完成且不抛 StackOverflowError</b>——当前实现为递归（addImpact），本测试
 * 即对该边界给出实证结论。</p>
 */
class PlannerImpactDeepChainTest {

    private boolean logWasDisabled;

    @BeforeEach
    void kernelUp() {
        logWasDisabled = Log.isDisabled();
        Log.disable();
        CloudSim.init(1, Calendar.getInstance(), false);
    }

    @AfterEach
    void kernelDown() {
        CloudSim.reset();
        Log.setDisabled(logWasDisabled);
    }

    /** 构造一条 depth 长度的纯链任务（id 1..depth，长度与文件由最小默认充当）。 */
    private static List<Task> chain(int depth) {
        List<Task> tasks = new ArrayList<Task>(depth);
        Task previous = null;
        for (int id = 1; id <= depth; id++) {
            Task task = new Task(id, 1000L);
            if (previous != null) {
                previous.addChild(task);
                task.addParent(previous);
            }
            tasks.add(task);
            previous = task;
        }
        return tasks;
    }

    private static void runImpactPropagation(WorkflowPlanner planner, List<Task> tasks) throws Exception {
        Method method = WorkflowPlanner.class.getDeclaredMethod("processImpactFactors", List.class);
        method.setAccessible(true);
        try {
            method.invoke(planner, tasks);
        } catch (InvocationTargetException invocation) {
            // 保留原始控制流证据（StackOverflowError 等）而非吞成反射包装。
            Throwable cause = invocation.getCause();
            if (cause instanceof RuntimeException) {
                throw (RuntimeException) cause;
            }
            if (cause instanceof Error) {
                throw (Error) cause;
            }
            throw invocation;
        }
    }

    @Test
    void shallowChainPropagatesExactUnityImpact() throws Exception {
        WorkflowPlanner planner = new WorkflowPlanner("impact-shallow", 1);
        List<Task> tasks = chain(16);
        runImpactPropagation(planner, tasks);
        for (Task task : tasks) {
            assertEquals(1.0, task.getImpact(), 0.0, "链上任务 " + task.getCloudletId() + " 的影响度必须精确为 1.0");
        }
    }

    @Test
    void deepChainCompletesWithoutStackOverflow() throws Exception {
        final int depth = 50_000;
        WorkflowPlanner planner = new WorkflowPlanner("impact-deep", 1);
        List<Task> tasks = chain(depth);
        // 递归实现的失败形态即 StackOverflowError 直接上抛（JUnit 判红 = F-004 证据）；
        // 迭代化修复后本用例转绿，且不影响下方精确不变量。
        runImpactPropagation(planner, tasks);
        for (int i = 0; i < depth; i += (i < 4 || i >= depth - 4) ? 1 : 997) {
            assertEquals(1.0, tasks.get(i).getImpact(), 0.0, "深度 " + (i + 1) + " 处任务的影响度必须精确为 1.0");
        }
    }

    /**
     * 位序保持差分：随机小 DAG 上，制品（帧栈迭代）与"契约文档递归语义的直译参考
     * 实现"必须<b>逐位</b>一致。参考实现按类 Javadoc 描述编写（出口均分、沿父边
     * 声明顺序递归），与制品的显式帧栈结构无关，用以锁定浮点累加顺序不因
     * F-004 迭代化改写而漂移。
     */
    @Test
    void randomDagImpactsMatchDocumentedRecursiveSemanticsBitwise() throws Exception {
        java.util.Random random = new java.util.Random(20261019L);
        for (int graph = 0; graph < 40; graph++) {
            java.util.List<int[]> parentSpec = randomParentSpec(random, 30 + random.nextInt(170));
            List<Task> production = materialise(parentSpec);
            List<Task> reference = materialise(parentSpec);

            WorkflowPlanner planner = new WorkflowPlanner("impact-diff-" + graph, 1);
            runImpactPropagation(planner, production);
            runReferenceImpactPropagation(reference);

            for (int i = 0; i < production.size(); i++) {
                assertEquals(
                        Double.doubleToRawLongBits(reference.get(i).getImpact()),
                        Double.doubleToRawLongBits(production.get(i).getImpact()),
                        "DAG#" + graph + " 任务 " + production.get(i).getCloudletId() + " 的影响度必须与文档语义直译逐位一致");
            }
        }
    }

    /** 层次随机 DAG 的父表规格：节点 i 的父集合取自更小下标（保证无环），每点至多 3 父。 */
    private static java.util.List<int[]> randomParentSpec(java.util.Random random, int size) {
        java.util.List<int[]> spec = new ArrayList<int[]>(size);
        for (int i = 0; i < size; i++) {
            int parentCount = Math.min(i, random.nextInt(4));
            java.util.Set<Integer> parents = new java.util.TreeSet<Integer>();
            while (parents.size() < parentCount) {
                parents.add(random.nextInt(i));
            }
            int[] ids = new int[parents.size()];
            int index = 0;
            for (Integer parent : parents) {
                ids[index++] = parent.intValue();
            }
            spec.add(ids);
        }
        return spec;
    }

    /** 按父表规格构造新任务图（两套互不共享对象）。 */
    private static List<Task> materialise(java.util.List<int[]> parentSpec) {
        List<Task> tasks = new ArrayList<Task>(parentSpec.size());
        for (int i = 0; i < parentSpec.size(); i++) {
            Task task = new Task(i + 1, 1000L);
            tasks.add(task);
        }
        for (int i = 0; i < parentSpec.size(); i++) {
            for (int parentId : parentSpec.get(i)) {
                tasks.get(parentId).addChild(tasks.get(i));
                tasks.get(i).addParent(tasks.get(parentId));
            }
        }
        return tasks;
    }

    /** 契约 Javadoc 的直译参考实现（仅本测试内使用，声明顺序递归）。 */
    private static void runReferenceImpactPropagation(List<Task> tasks) {
        List<Task> exits = new ArrayList<Task>();
        for (Task task : tasks) {
            if (task.getChildList().isEmpty()) {
                exits.add(task);
            }
        }
        double avg = 1.0 / exits.size();
        for (Task exit : exits) {
            referenceAddImpact(exit, avg);
        }
    }

    private static void referenceAddImpact(Task task, double impact) {
        task.setImpact(task.getImpact() + impact);
        int size = task.getParentList().size();
        if (size > 0) {
            double avg = impact / size;
            for (Task parent : task.getParentList()) {
                referenceAddImpact(parent, avg);
            }
        }
    }
}
