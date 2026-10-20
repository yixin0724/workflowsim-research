package org.workflowsim.clustering;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.cloudbus.cloudsim.Log;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.workflowsim.FileItem;
import org.workflowsim.Job;
import org.workflowsim.Task;
import org.workflowsim.WorkflowDagValidator;
import org.workflowsim.clustering.balancing.BalancedClustering;
import org.workflowsim.utils.ClusteringParameters;
import org.workflowsim.utils.OverheadParameters;
import org.workflowsim.utils.Parameters;
import org.workflowsim.utils.Parameters.SchedulingAlgorithm;
import org.workflowsim.utils.Parameters.PlanningAlgorithm;
import org.workflowsim.utils.Parameters.FileType;
import org.workflowsim.utils.SimulationRandom;

/**
 * C14：聚类引擎守恒律属性族（审查工程 Phase 2，针对约 60 个零覆盖方法）。
 *
 * <p>军规来源（守恒律，非实现镜像）：</p>
 * <ol>
 *   <li><b>分区性</b>：聚类产物 Job 的成员任务恰构成原任务集合的划分（不丢、不重、不造）。</li>
 *   <li><b>时长守恒</b>：零聚类开销下 Σ Job 长度 == Σ 原任务长度（MI）。</li>
 *   <li><b>依赖投影</b>：Job 间父子边当且仅当存在对应的跨簇任务级父子边；无自环、无重边。</li>
 *   <li><b>Job 图无环</b>（DAG 投影仍是 DAG）。</li>
 *   <li><b>文件并集守恒</b>：getTaskFiles 恰好携带每个外部输入一次与每个任务的输出。</li>
 *   <li><b>HORIZONTAL 语义</b>：每个 Job 的成员位于同一 depth 层（水平聚类按层组织）。</li>
 *   <li><b>VERTICAL 语义</b>：多成员 Job 的相邻成员间存在任务级父子边（沿 DAG 边合并）。</li>
 *   <li><b>BALANCED 恢复正确性</b>：remove/recover 机制执行后，任务级依赖图恢复原样。</li>
 * </ol>
 *
 * <p>随机 DAG 由固定种子分层生成（任务数/父数/长度可复现）；每个引擎使用同构重建副本，
 * 因聚类引擎会消费并清空传入的任务列表。</p>
 */
class ClusteringConservationPropertyTest {

    private static final long DAG_SEED = 20261019L;
    private static final long STREAM_SEED = 4242L;
    private static final int LEVELS = 4;
    private static final int MAX_PER_LEVEL = 6;

    private boolean logWasDisabled;

    @BeforeEach
    void quietLegacyGlobals() {
        logWasDisabled = Log.isDisabled();
        Log.disable();
        Parameters.reset();
        initLegacy(new ClusteringParameters(0, 0,
                ClusteringParameters.ClusteringMethod.NONE, null));
        SimulationRandom.reset(STREAM_SEED);
    }

    @AfterEach
    void restoreLegacyGlobals() {
        Parameters.reset();
        SimulationRandom.reset(0L);
        Log.setDisabled(logWasDisabled);
    }

    private static void initLegacy(ClusteringParameters parameters) {
        Parameters.init(2, "conservation.dax", null, null,
                new OverheadParameters(0, null, null, null, null, 0.0),
                parameters, SchedulingAlgorithm.FCFS, PlanningAlgorithm.INVALID, null, 0L);
        SimulationRandom.reset(STREAM_SEED);
    }

    // ---- 随机分层 DAG（每次同种子重建同构副本） ----

    private static final class Dag {
        final List<Task> tasks;
        final long totalLength;
        final Set<Long> taskEdges = new HashSet<Long>();
        final List<FileItem> sharedInputs = new ArrayList<FileItem>();

        Dag(List<Task> tasks, long totalLength) {
            this.tasks = tasks;
            this.totalLength = totalLength;
        }
    }

    private static Dag layeredDag(boolean withFiles) {
        java.util.Random random = new java.util.Random(DAG_SEED);
        List<Task> tasks = new ArrayList<Task>();
        List<FileItem> sharedInputs = new ArrayList<FileItem>();
        if (withFiles) {
            // 生产约定：同名输入跨任务共享同一 FileItem 实例（副本目录 + contains 同一性去重）。
            FileItem inputA = new FileItem("shared-input-a", 300_000.0);
            inputA.setType(FileType.INPUT);
            FileItem inputB = new FileItem("shared-input-b", 700_000.0);
            inputB.setType(FileType.INPUT);
            sharedInputs.add(inputA);
            sharedInputs.add(inputB);
        }
        List<Task> previousLevel = new ArrayList<Task>();
        long totalLength = 0L;
        int id = 1;
        for (int level = 0; level < LEVELS; level++) {
            List<Task> current = new ArrayList<Task>();
            int count = 2 + random.nextInt(MAX_PER_LEVEL - 1);
            for (int i = 0; i < count; i++) {
                Task task = createTask(id++, 500L + random.nextInt(4500), withFiles, sharedInputs);
                if (!previousLevel.isEmpty() && level > 0) {
                    int parents = 1 + random.nextInt(Math.min(3, previousLevel.size()));
                    for (int p = 0; p < parents; p++) {
                        Task parent = previousLevel.get(random.nextInt(previousLevel.size()));
                        if (task.getParentList().contains(parent)) {
                            continue;
                        }
                        parent.addChild(task);
                        task.addParent(parent);
                    }
                }
                current.add(task);
                tasks.add(task);
                totalLength += task.getCloudletLength();
            }
            previousLevel = current;
        }
        WorkflowDagValidator.validateAndAssignDepths(tasks);
        Dag dag = new Dag(tasks, totalLength);
        dag.sharedInputs.addAll(sharedInputs);
        for (Task task : tasks) {
            for (Object parent : task.getParentList()) {
                dag.taskEdges.add(edgeKey((Task) parent, task));
            }
        }
        return dag;
    }

    private static Task createTask(int id, long length, boolean withFiles, List<FileItem> sharedInputs) {
        Task task = new Task(id, length);
        if (withFiles) {
            List<FileItem> files = new ArrayList<FileItem>();
            for (FileItem input : sharedInputs) {
                // 直接复用共享实例：与 WorkflowParser 经 ReplicaCatalog 的共享语义一致。
                files.add(input);
                task.addRequiredFile(input.getName());
            }
            FileItem output = new FileItem("task-" + id + "-output", 100_000.0 + id);
            output.setType(FileType.OUTPUT);
            files.add(output);
            task.setFileList(files);
        } else {
            task.setFileList(new ArrayList<FileItem>());
        }
        return task;
    }

    private static long edgeKey(Task parent, Task child) {
        return ((long) parent.getCloudletId() << 32) | (child.getCloudletId() & 0xffffffffL);
    }

    // ---- 引擎驱动：同种子重建同构 DAG → 设置 → 运行 → 断言守恒律 ----

    private static List<Job> runEngine(BasicClustering engine, boolean withFiles) {
        Dag dag = layeredDag(withFiles);
        engine.setTaskList(new ArrayList<Task>(dag.tasks));
        engine.run();
        assertPartitionDependenciesFilesLength(engine.getJobList(), dag, engine.getTaskFiles());
        return engine.getJobList();
    }

    private static Map<Integer, Job> taskMembership(List<Job> jobs, Dag dag) {
        Map<Integer, Job> taskJob = new HashMap<Integer, Job>();
        Set<Integer> expectedIds = new HashSet<Integer>();
        for (Task task : dag.tasks) {
            expectedIds.add(Integer.valueOf(task.getCloudletId()));
        }
        for (Job job : jobs) {
            for (Task task : job.getTaskList()) {
                Integer id = Integer.valueOf(task.getCloudletId());
                assertTrue(expectedIds.contains(id), "聚类产物出现未知任务 " + id);
                assertTrue(!taskJob.containsKey(id), "聚类产物出现重复任务 " + id);
                taskJob.put(id, job);
            }
        }
        assertEquals(expectedIds, taskJob.keySet(), "聚类产物必须原划分覆盖全部任务");
        return taskJob;
    }

    @Test
    void basicClusteringKeepsOneJobPerTaskWithExactDependencies() {
        Dag dag = layeredDag(false);
        BasicClustering engine = new BasicClustering();
        engine.setTaskList(new ArrayList<Task>(dag.tasks));
        engine.run();

        List<Job> jobs = engine.getJobList();
        assertEquals(dag.tasks.size(), jobs.size(), "无聚类时 Job 数必须等于任务数");
        Map<Integer, Job> membership = taskMembership(jobs, dag);
        assertPartitionAndLength(jobs, dag, membership, engine.getTaskFiles());
    }

    @Test
    void horizontalClusterNumBalancesLayersAndConservesEverything() {
        horizontalConserves(3, 0);
    }

    @Test
    void horizontalClusterSizeCollapsesLayersAndConservesEverything() {
        horizontalConserves(0, 2);
    }

    private void horizontalConserves(int clusterNum, int clusterSize) {
        initLegacy(new ClusteringParameters(clusterNum, clusterSize,
                ClusteringParameters.ClusteringMethod.NONE, null));
        List<Job> jobs = runEngine(new HorizontalClustering(clusterNum, clusterSize), true);
        for (Job job : jobs) {
            int depth = Integer.MIN_VALUE;
            for (Task task : job.getTaskList()) {
                if (depth == Integer.MIN_VALUE) {
                    depth = task.getDepth();
                } else {
                    assertEquals(depth, task.getDepth(), "水平聚类 Job 必须只含同一层任务");
                }
            }
        }
        assertTrue(jobs.size() < layeredDag(true).tasks.size(),
                "启用聚类时应产生少于任务数的 Job");
    }

    @Test
    void verticalClusteringMergesAlongDependencyChainsAndConservesEverything() {
        List<Job> jobs = runEngine(new VerticalClustering(1), true);
        for (Job job : jobs) {
            List<Task> members = job.getTaskList();
            for (int i = 1; i < members.size(); i++) {
                Task left = members.get(i - 1);
                Task right = members.get(i);
                assertTrue(left.getChildList().contains(right) || left.getParentList().contains(right),
                        "垂直聚类相邻成员必须有任务级父子边: " + left.getCloudletId()
                                + " 与 " + right.getCloudletId());
            }
        }
    }

    @Test
    void blockClusteringConservesPartitionDependenciesAndFiles() throws Exception {
        runEngine(new BlockClustering(3, 2), true);
    }

    @Test
    void balancedVerticalCodeRestoresOriginalDependenciesAndConservesEverything() {
        balancedConserves("v");
    }

    @Test
    void balancedRandomCodeRestoresOriginalDependenciesAndConservesEverything() {
        balancedConserves("r");
    }

    private void balancedConserves(String code) {
        initLegacy(new ClusteringParameters(3, 0,
                ClusteringParameters.ClusteringMethod.BALANCED, code));
        Dag dag = layeredDag(true);
        Map<Integer, Set<Integer>> parentsBefore = dependencySnapshot(dag.tasks);
        Map<Integer, Set<Integer>> childrenBefore = dependencySnapshotChildren(dag.tasks);
        BalancedClustering engine = new BalancedClustering(3);
        engine.setTaskList(new ArrayList<Task>(dag.tasks));
        engine.run();

        assertPartitionDependenciesFilesLength(engine.getJobList(), dag, engine.getTaskFiles());
        // ⑧ remove/recover 恢复正确性：任务级依赖图必须恢复原样。
        for (Task task : dag.tasks) {
            Set<Integer> parents = dependencySnapshot(dag.tasks).get(task.getCloudletId());
            Set<Integer> children = dependencySnapshotChildren(dag.tasks).get(task.getCloudletId());
            assertEquals(parentsBefore.get(task.getCloudletId()), parents,
                    "BALANCED(" + code + ") 执行后父集合必须恢复: task " + task.getCloudletId());
            assertEquals(childrenBefore.get(task.getCloudletId()), children,
                    "BALANCED(" + code + ") 执行后子集合必须恢复: task " + task.getCloudletId());
        }
    }

    /** 对任意引擎的完整守恒断言：分区、依赖投影、无环、文件、长度。 */
    private static void assertPartitionDependenciesFilesLength(List<Job> jobs, Dag dag,
            List<FileItem> taskFiles) {
        Map<Integer, Job> membership = taskMembership(jobs, dag);
        // ② 时长守恒（零聚类开销）。
        long jobTotal = 0L;
        for (Job job : jobs) {
            jobTotal += job.getCloudletLength();
        }
        assertEquals(dag.totalLength, jobTotal, "聚类后 Job 长度总和必须守恒");

        // ③ 依赖投影 + 无自环/重边。
        Set<Long> expectedJobPairs = new HashSet<Long>();
        for (Task task : dag.tasks) {
            for (Object parentObject : task.getParentList()) {
                Task parent = (Task) parentObject;
                Job parentJob = membership.get(parent.getCloudletId());
                Job childJob = membership.get(task.getCloudletId());
                if (parentJob != childJob) {
                    expectedJobPairs.add(jobEdgeKey(parentJob, childJob));
                }
            }
        }
        Set<Long> actualJobPairs = new HashSet<Long>();
        for (Job job : jobs) {
            for (Object parentObject : job.getParentList()) {
                Job parentJob = (Job) parentObject;
                if (parentJob == job) {
                    throw new AssertionError("Job 图不允许自环: " + job.getCloudletId());
                }
                assertTrue(actualJobPairs.add(jobEdgeKey(parentJob, job)), "Job 图不允许重复边");
            }
        }
        assertEquals(expectedJobPairs, actualJobPairs, "Job 依赖边必须是任务跨簇边的投影");

        // ④ Job 图无环（迭代 DFS）。
        assertJobGraphAcyclic(jobs);

        // ⑤ 文件守恒：每个任务输入引用获得一份外部登记，输出至少出现一次。
        Set<String> inputNames = new HashSet<String>();
        Set<String> outputNames = new HashSet<String>();
        for (FileItem file : taskFiles) {
            if (file.getType() == FileType.INPUT) {
                assertTrue(inputNames.add(file.getName()), "输入文件重复登记: " + file.getName());
            } else if (file.getType() == FileType.OUTPUT) {
                outputNames.add(file.getName());
            }
        }
        Set<String> expectedInputs = new HashSet<String>();
        Set<String> expectedOutputs = new HashSet<String>();
        for (Task task : dag.tasks) {
            for (FileItem file : task.getFileList()) {
                if (file.getType() == FileType.INPUT) {
                    expectedInputs.add(file.getName());
                } else {
                    expectedOutputs.add(file.getName());
                }
            }
        }
        assertEquals(expectedInputs, inputNames, "getTaskFiles 必须恰好携带外部输入集合");
        assertTrue(outputNames.containsAll(expectedOutputs), "getTaskFiles 必须携带全部输出");
        // 输出引用至少登记一次（可能按引用重复，保底是并集语义）。
    }

    private static void assertPartitionAndLength(List<Job> jobs, Dag dag, Map<Integer, Job> membership,
            List<FileItem> taskFiles) {
        assertPartitionDependenciesFilesLength(jobs, dag, taskFiles);
    }

    private static long jobEdgeKey(Job parent, Job child) {
        return ((long) parent.getCloudletId() << 32) | (child.getCloudletId() & 0xffffffffL);
    }

    private static void assertJobGraphAcyclic(List<Job> jobs) {
        Map<Integer, Integer> state = new HashMap<Integer, Integer>();
        java.util.ArrayDeque<Job> stack = new java.util.ArrayDeque<Job>();
        for (Job job : jobs) {
            if (state.getOrDefault(Integer.valueOf(job.getCloudletId()), 0) == 0) {
                stack.push(job);
                while (!stack.isEmpty()) {
                    Job current = stack.peek();
                    Integer id = Integer.valueOf(current.getCloudletId());
                    int colour = state.getOrDefault(id, 0);
                    if (colour == 0) {
                        state.put(id, 1);
                        for (Object childObject : current.getChildList()) {
                            Job child = (Job) childObject;
                            if (state.getOrDefault(Integer.valueOf(child.getCloudletId()), 0) == 1) {
                                throw new AssertionError("Job 图出现环: " + current.getCloudletId()
                                        + " -> " + child.getCloudletId());
                            }
                            if (state.getOrDefault(Integer.valueOf(child.getCloudletId()), 0) == 0) {
                                stack.push(child);
                            }
                        }
                    } else {
                        state.put(id, 2);
                        stack.pop();
                    }
                }
            }
        }
    }

    private static Map<Integer, Set<Integer>> dependencySnapshot(List<Task> tasks) {
        Map<Integer, Set<Integer>> snapshot = new LinkedHashMap<Integer, Set<Integer>>();
        for (Task task : tasks) {
            Set<Integer> parents = new HashSet<Integer>();
            for (Object parentObject : task.getParentList()) {
                Task parent = (Task) parentObject;
                parents.add(Integer.valueOf(parent.getCloudletId()));
            }
            snapshot.put(Integer.valueOf(task.getCloudletId()), parents);
        }
        return snapshot;
    }

    private static Map<Integer, Set<Integer>> dependencySnapshotChildren(List<Task> tasks) {
        Map<Integer, Set<Integer>> snapshot = new LinkedHashMap<Integer, Set<Integer>>();
        for (Task task : tasks) {
            Set<Integer> children = new HashSet<Integer>();
            for (Object childObject : task.getChildList()) {
                Task child = (Task) childObject;
                children.add(Integer.valueOf(child.getCloudletId()));
            }
            snapshot.put(Integer.valueOf(task.getCloudletId()), children);
        }
        return snapshot;
    }
}
