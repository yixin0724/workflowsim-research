package org.workflowsim.experiment;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;
import org.workflowsim.FileItem;
import org.workflowsim.Task;
import org.workflowsim.utils.Parameters.FileType;

/**
 * 已解析源工作流的不可变结构描述。
 *
 * <p>该画像刻意在聚类、调度、重试和执行之前生成。外部输入字段采用全图定义：
 * 只有当任一源任务均未声明同名 OUTPUT 文件时，INPUT 文件才是外部文件。因此它是
 * 输入工作负载描述，不是对遗留数据 stage-in 实现的断言。</p>
 */
public final class WorkflowProfile {

    private final int taskCount;
    private final int edgeCount;
    private final int rootTaskCount;
    private final int leafTaskCount;
    private final int maximumDepthEdges;
    private final int maximumWidthTasks;
    private final long totalTaskLengthMi;
    private final double meanTaskLengthMi;
    private final double taskLengthCoefficientOfVariation;
    private final int fileReferenceCount;
    private final int distinctFileCount;
    private final double totalDistinctFileBytes;
    private final int externalInputReferenceCount;
    private final int distinctExternalInputFileCount;
    private final double totalDistinctExternalInputBytes;

    private WorkflowProfile(int taskCount, int edgeCount, int rootTaskCount, int leafTaskCount,
            int maximumDepthEdges, int maximumWidthTasks, long totalTaskLengthMi,
            double meanTaskLengthMi, double taskLengthCoefficientOfVariation,
            int fileReferenceCount, int distinctFileCount, double totalDistinctFileBytes,
            int externalInputReferenceCount, int distinctExternalInputFileCount,
            double totalDistinctExternalInputBytes) {
        this.taskCount = taskCount;
        this.edgeCount = edgeCount;
        this.rootTaskCount = rootTaskCount;
        this.leafTaskCount = leafTaskCount;
        this.maximumDepthEdges = maximumDepthEdges;
        this.maximumWidthTasks = maximumWidthTasks;
        this.totalTaskLengthMi = totalTaskLengthMi;
        this.meanTaskLengthMi = meanTaskLengthMi;
        this.taskLengthCoefficientOfVariation = taskLengthCoefficientOfVariation;
        this.fileReferenceCount = fileReferenceCount;
        this.distinctFileCount = distinctFileCount;
        this.totalDistinctFileBytes = totalDistinctFileBytes;
        this.externalInputReferenceCount = externalInputReferenceCount;
        this.distinctExternalInputFileCount = distinctExternalInputFileCount;
        this.totalDistinctExternalInputBytes = totalDistinctExternalInputBytes;
    }

    /**
     * 为完整的已解析任务图生成画像；对不合法的图或文件数据直接拒绝，避免输出
     * 具有误导性的聚合值。
     *
     * @param sourceTasks 已解析的源任务图；可以为空，但不能为 {@code null}
     * @return 描述任务、边、深度、宽度、运行长度和文件引用的不可变画像
     * @throws IllegalArgumentException 当任务图、依赖关系或文件声明不合法时抛出
     */
    public static WorkflowProfile fromTasks(List<Task> sourceTasks) {
        if (sourceTasks == null) {
            throw new IllegalArgumentException("Source tasks are required");
        }
        if (sourceTasks.isEmpty()) {
            return new WorkflowProfile(0, 0, 0, 0, 0, 0, 0L, 0.0, 0.0,
                    0, 0, 0.0, 0, 0, 0.0);
        }

        List<Task> tasks = new ArrayList<Task>(sourceTasks);
        Collections.sort(tasks, new Comparator<Task>() {
            @Override
            public int compare(Task first, Task second) {
                return Integer.compare(first.getCloudletId(), second.getCloudletId());
            }
        });
        Map<Integer, Task> tasksById = new HashMap<Integer, Task>();
        for (Task task : tasks) {
            if (task == null || tasksById.put(task.getCloudletId(), task) != null) {
                throw new IllegalArgumentException("Source workflow has a null or duplicate task ID");
            }
        }

        Set<Task> taskSet = new HashSet<Task>(tasks);
        Map<Task, Integer> remainingParents = new HashMap<Task, Integer>();
        Map<Task, Integer> depthByTask = new HashMap<Task, Integer>();
        int edges = 0;
        int roots = 0;
        int leaves = 0;
        long totalLength = 0L;
        for (Task task : tasks) {
            List<Task> parents = requireTaskList(task.getParentList(), task, "parent");
            List<Task> children = requireTaskList(task.getChildList(), task, "child");
            validateUniqueNeighborIds(parents, task, "parent");
            validateUniqueNeighborIds(children, task, "child");
            for (Task parent : parents) {
                if (!taskSet.contains(parent) || !parent.getChildList().contains(task)) {
                    throw new IllegalArgumentException("Source workflow has an asymmetric parent edge for task "
                            + task.getCloudletId());
                }
            }
            for (Task child : children) {
                if (!taskSet.contains(child) || !child.getParentList().contains(task)) {
                    throw new IllegalArgumentException("Source workflow has an asymmetric child edge for task "
                            + task.getCloudletId());
                }
            }
            remainingParents.put(task, Integer.valueOf(parents.size()));
            if (parents.isEmpty()) {
                roots++;
                depthByTask.put(task, Integer.valueOf(0));
            }
            if (children.isEmpty()) {
                leaves++;
            }
            edges += children.size();
            try {
                totalLength = Math.addExact(totalLength, task.getCloudletTotalLength());
            } catch (ArithmeticException exception) {
                throw new IllegalArgumentException("Source workflow total task length overflows long", exception);
            }
        }

        PriorityQueue<Task> ready = new PriorityQueue<Task>(tasks.size(), new Comparator<Task>() {
            @Override
            public int compare(Task first, Task second) {
                return Integer.compare(first.getCloudletId(), second.getCloudletId());
            }
        });
        for (Task task : tasks) {
            if (remainingParents.get(task).intValue() == 0) {
                ready.add(task);
            }
        }
        Map<Integer, Integer> widthByDepth = new HashMap<Integer, Integer>();
        int maximumDepth = 0;
        int processed = 0;
        while (!ready.isEmpty()) {
            Task task = ready.remove();
            int depth = depthByTask.get(task).intValue();
            maximumDepth = Math.max(maximumDepth, depth);
            Integer width = widthByDepth.get(Integer.valueOf(depth));
            widthByDepth.put(Integer.valueOf(depth), Integer.valueOf(width == null ? 1 : width.intValue() + 1));
            processed++;
            for (Task child : task.getChildList()) {
                int childDepth = Math.max(depthByTask.containsKey(child)
                        ? depthByTask.get(child).intValue() : 0, depth + 1);
                depthByTask.put(child, Integer.valueOf(childDepth));
                int remaining = remainingParents.get(child).intValue() - 1;
                remainingParents.put(child, Integer.valueOf(remaining));
                if (remaining == 0) {
                    ready.add(child);
                }
            }
        }
        if (processed != tasks.size()) {
            throw new IllegalArgumentException("Source workflow graph contains a dependency cycle");
        }
        int maximumWidth = 0;
        for (Integer width : widthByDepth.values()) {
            maximumWidth = Math.max(maximumWidth, width.intValue());
        }

        FileSummary files = summarizeFiles(tasks);
        double meanLength = (double) totalLength / tasks.size();
        double variance = 0.0;
        for (Task task : tasks) {
            double difference = task.getCloudletTotalLength() - meanLength;
            variance += difference * difference;
        }
        double coefficientOfVariation = meanLength == 0.0 ? 0.0
                : Math.sqrt(variance / tasks.size()) / meanLength;
        return new WorkflowProfile(tasks.size(), edges, roots, leaves, maximumDepth, maximumWidth,
                totalLength, meanLength, coefficientOfVariation, files.references,
                files.filesByName.size(), files.totalDistinctBytes, files.externalReferences,
                files.externalFilesByName.size(), files.totalDistinctExternalBytes);
    }

    private static List<Task> requireTaskList(List<Task> values, Task task, String role) {
        if (values == null) {
            throw new IllegalArgumentException("Source workflow has a null " + role + " list for task "
                    + task.getCloudletId());
        }
        return values;
    }

    private static void validateUniqueNeighborIds(List<Task> neighbors, Task task, String role) {
        Set<Integer> ids = new HashSet<Integer>();
        for (Task neighbor : neighbors) {
            if (neighbor == null || !ids.add(Integer.valueOf(neighbor.getCloudletId()))) {
                throw new IllegalArgumentException("Source workflow has a null or duplicate " + role
                        + " edge for task " + task.getCloudletId());
            }
        }
    }

    private static FileSummary summarizeFiles(List<Task> tasks) {
        FileSummary summary = new FileSummary();
        Set<String> producedNames = new HashSet<String>();
        for (Task task : tasks) {
            for (FileItem file : requireFileList(task)) {
                validateFile(file, task);
                if (file.getType() == FileType.OUTPUT) {
                    producedNames.add(file.getName());
                }
            }
        }
        for (Task task : tasks) {
            for (FileItem file : requireFileList(task)) {
                summary.references++;
                register(summary.filesByName, file, "file");
                if (file.getType() == FileType.INPUT && !producedNames.contains(file.getName())) {
                    summary.externalReferences++;
                    register(summary.externalFilesByName, file, "external input");
                }
            }
        }
        summary.totalDistinctBytes = totalBytes(summary.filesByName);
        summary.totalDistinctExternalBytes = totalBytes(summary.externalFilesByName);
        return summary;
    }

    private static List<FileItem> requireFileList(Task task) {
        if (task.getFileList() == null) {
            throw new IllegalArgumentException("Source workflow has a null file list for task "
                    + task.getCloudletId());
        }
        return task.getFileList();
    }

    private static void validateFile(FileItem file, Task task) {
        if (file == null || file.getName() == null || file.getName().trim().isEmpty()
                || file.getType() == null || file.getSize() < 0.0
                || Double.isNaN(file.getSize()) || Double.isInfinite(file.getSize())) {
            throw new IllegalArgumentException("Source workflow has an invalid file declaration for task "
                    + task.getCloudletId());
        }
    }

    private static void register(Map<String, Double> filesByName, FileItem file, String kind) {
        Double previous = filesByName.get(file.getName());
        if (previous == null) {
            filesByName.put(file.getName(), Double.valueOf(file.getSize()));
        } else if (Double.compare(previous.doubleValue(), file.getSize()) != 0) {
            // 放宽校验：使用第一次声明的 size，记录警告。
            // 与 WorkflowParser 中的逻辑一致：真实工作流中同一文件的 size 估算可能不同。
            org.cloudbus.cloudsim.Log.printLine("WARNING: Workflow declares inconsistent " + kind
                    + " size for '" + file.getName() + "': "
                    + "first=" + previous.doubleValue() + ", current=" + file.getSize()
                    + " (using first declaration)");
            // 继续使用 previous，不抛异常
        }
    }

    private static double totalBytes(Map<String, Double> filesByName) {
        double total = 0.0;
        for (Double size : filesByName.values()) {
            total += size.doubleValue();
        }
        return total;
    }

    public int getTaskCount() { return taskCount; }
    public int getEdgeCount() { return edgeCount; }
    public int getRootTaskCount() { return rootTaskCount; }
    public int getLeafTaskCount() { return leafTaskCount; }
    public int getMaximumDepthEdges() { return maximumDepthEdges; }
    public int getMaximumWidthTasks() { return maximumWidthTasks; }
    public long getTotalTaskLengthMi() { return totalTaskLengthMi; }
    public double getMeanTaskLengthMi() { return meanTaskLengthMi; }
    public double getTaskLengthCoefficientOfVariation() { return taskLengthCoefficientOfVariation; }
    public int getFileReferenceCount() { return fileReferenceCount; }
    public int getDistinctFileCount() { return distinctFileCount; }
    public double getTotalDistinctFileBytes() { return totalDistinctFileBytes; }
    public int getExternalInputReferenceCount() { return externalInputReferenceCount; }
    public int getDistinctExternalInputFileCount() { return distinctExternalInputFileCount; }
    public double getTotalDistinctExternalInputBytes() { return totalDistinctExternalInputBytes; }

    private static final class FileSummary {
        private final Map<String, Double> filesByName = new HashMap<String, Double>();
        private final Map<String, Double> externalFilesByName = new HashMap<String, Double>();
        private int references;
        private int externalReferences;
        private double totalDistinctBytes;
        private double totalDistinctExternalBytes;
    }
}
