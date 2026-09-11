/*
 * WfCommons WfFormat JSON parser for WorkflowSim.
 *
 * Ported from https://github.com/yixin0724/workflowsim-fat-tree
 * (sources/org/workflowsim/WfCommonsJsonParser.java). The upstream
 * repository is licensed GPL-3.0-only; the author of both repositories
 * has approved reuse here.
 */
package org.workflowsim;

import com.google.gson.Gson;
import com.google.gson.JsonParseException;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.cloudbus.cloudsim.Log;
import org.workflowsim.utils.Parameters;
import org.workflowsim.utils.Parameters.FileType;
import org.workflowsim.utils.ReplicaCatalog;
import org.workflowsim.utils.SimulationConstants;

/**
 * WfCommons 工作流实例 JSON 解析器。
 *
 * <p>解析器将 WfCommons 的 JSON 表示转换为 WorkflowSim 既有的
 * {@link Task}、{@link FileItem} 和 DAG 模型，使后续聚类、规划和调度逻辑无需感知
 * 输入格式差异。</p>
 *
 * <p>单位约定（与 {@link WorkflowParser} 的 DAX 解析保持一致）：</p>
 * <ul>
 * <li>运行时间：WfFormat 使用 {@code runtimeInSeconds}。解析器通过
 * {@link Parameters#getRuntimeReferenceMips()} 将其转换为百万条指令（MI），再乘以
 * {@link Parameters#getRuntimeScale()}，并限制为至少 {@link SimulationConstants#MIN_TASK_LENGTH_MI}，
 * 因为 CloudSim 不接受
 * 更小的 Cloudlet 长度。</li>
 * <li>文件大小：WfFormat 使用 {@code sizeInBytes}，WorkflowSim 的 {@link FileItem}
 * 也以字节保存。{@link WorkflowDatacenter} 计算传输时间时会除以
 * {@code Consts.MILLION}。</li>
 * </ul>
 */
public final class WfCommonsJsonParser {

    /**
     * 任务的最小 Cloudlet 长度（MI）。
     * 
     * <p>使用 {@link SimulationConstants#MIN_TASK_LENGTH_MI} 确保数值稳定性。</p>
     */
    private static final long MIN_CLOUDLET_LENGTH = SimulationConstants.MIN_TASK_LENGTH_MI;

    private final Gson gson;

    /** 创建使用 Gson 解码的解析器。 */
    public WfCommonsJsonParser() {
        this.gson = new Gson();
    }

    /**
     * 解析一个 WfCommons JSON 实例并转换为 WorkflowSim 任务图。
     *
     * @param path JSON 文件路径
     * @param userId 所有生成任务归属的 CloudSim 用户编号
     * @param firstTaskId 本次转换可分配的第一个任务编号
     * @return 转换得到的任务、下一个可用任务编号和输入报告
     * @throws IOException 当文件无法读取或 JSON 语法不合法时
     * @throws WorkflowValidationException 当输入不能表示为合法 WorkflowSim 任务图时
     */
    public ParseResult parse(String path, int userId, int firstTaskId) throws IOException {
        return parse(path, userId, firstTaskId, null);
    }

    /**
     * 解析一个 WfCommons JSON 实例，并可为文件逻辑身份指定输入作用域。
     *
     * <p>该重载供 {@link WorkflowParser} 的多工作流提交使用。作用域仅改变
     * {@link FileItem}、任务文件需求和副本目录中的内部键；JSON 中声明的文件 ID、
     * 文件大小、任务 ID 和 DAG 关系保持原样。传入 {@code null} 时与历史三参数 API
     * 行为完全一致。</p>
     *
     * @param path JSON 文件路径
     * @param userId 所有生成任务归属的 CloudSim 用户编号
     * @param firstTaskId 本次转换可分配的第一个任务编号
     * @param fileNamespace 多输入提交中当前输入的稳定文件作用域；单输入时为 {@code null}
     * @return 转换得到的任务、下一个可用任务编号和输入报告
     * @throws IOException 当文件无法读取或 JSON 语法不合法时
     * @throws WorkflowValidationException 当输入不能表示为合法 WorkflowSim 任务图时
     */
    ParseResult parse(String path, int userId, int firstTaskId, String fileNamespace) throws IOException {
        if (path == null || path.trim().length() == 0) {
            throw new IllegalArgumentException("WfCommons JSON path cannot be empty");
        }
        if (firstTaskId < 0) {
            throw new IllegalArgumentException("firstTaskId cannot be negative");
        }

        File file = new File(path);
        if (!file.exists()) {
            throw new IOException("WfCommons JSON file does not exist: " + file.getAbsolutePath());
        }

        WfCommonsInstance instance;
        Reader reader = new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8);
        try {
            instance = gson.fromJson(reader, WfCommonsInstance.class);
        } catch (JsonParseException e) {
            throw new IOException("Invalid WfCommons JSON: " + file.getAbsolutePath(), e);
        } finally {
            reader.close();
        }

        validateInstance(instance, file);
        WorkflowInputReport report = new WorkflowInputReport(file.getPath(),
                WorkflowInputReport.Format.WFCOMMONS_JSON, instance.schemaVersion);
        Map<String, Double> fileSizes = indexFileSizes(instance.workflow.specification.files);
        Map<String, Double> runtimes = indexRuntimes(instance.workflow.execution.tasks);
        Map<String, SpecTask> specTasks = indexSpecTasks(instance.workflow.specification.tasks);
        Map<String, Task> tasksByWfCommonsId = new LinkedHashMap<>();
        Map<String, FileItem> fileItemsByNameAndType = new HashMap<>();
        List<Task> tasks = new ArrayList<>();

        int nextTaskId = firstTaskId;
        for (SpecTask specTask : instance.workflow.specification.tasks) {
            double runtimeSeconds = getRequiredRuntimeSeconds(runtimes, specTask.id);
            long length = toCloudletLength(runtimeSeconds, specTask.id, report);
            Task task = new Task(nextTaskId++, length);
            task.setType(specTask.name == null ? "" : specTask.name);
            task.setUserId(userId);

            List<FileItem> taskFiles = new ArrayList<>();
            addFiles(taskFiles, fileItemsByNameAndType, specTask.inputFiles, fileSizes,
                    FileType.INPUT, fileNamespace);
            addFiles(taskFiles, fileItemsByNameAndType, specTask.outputFiles, fileSizes,
                    FileType.OUTPUT, fileNamespace);

            for (FileItem taskFile : taskFiles) {
                task.addRequiredFile(taskFile.getName());
            }
            task.setFileList(taskFiles);

            tasksByWfCommonsId.put(specTask.id, task);
            tasks.add(task);
        }

        validateDeclaredDependencySymmetry(instance.workflow.specification.tasks, specTasks);
        connectDependencies(instance.workflow.specification.tasks, specTasks, tasksByWfCommonsId);
        WorkflowDagValidator.validateAndAssignDepths(tasks);

        report.setTaskCount(tasks.size());

        Log.printLine("Parsed WfCommons JSON " + file.getName() + " with "
                + tasks.size() + " tasks.");
        return new ParseResult(tasks, nextTaskId, report);
    }

    private static void validateInstance(WfCommonsInstance instance, File file) throws IOException {
        if (instance == null) {
            throw new IOException("WfCommons JSON is empty: " + file.getAbsolutePath());
        }
        if (instance.workflow == null) {
            throw new IOException("WfCommons JSON is missing workflow: " + file.getAbsolutePath());
        }
        if (instance.workflow.specification == null) {
            throw new IOException("WfCommons JSON is missing workflow.specification: " + file.getAbsolutePath());
        }
        if (instance.workflow.specification.tasks == null) {
            throw new IOException("WfCommons JSON is missing workflow.specification.tasks: "
                    + file.getAbsolutePath());
        }
        if (instance.workflow.specification.tasks.isEmpty()) {
            throw new IOException("WfCommons JSON workflow contains no specification tasks: "
                    + file.getAbsolutePath());
        }
        if (instance.workflow.specification.files == null) {
            throw new IOException("WfCommons JSON is missing workflow.specification.files: "
                    + file.getAbsolutePath());
        }
        if (instance.workflow.execution == null || instance.workflow.execution.tasks == null) {
            throw new IOException("WfCommons JSON is missing workflow.execution.tasks: "
                    + file.getAbsolutePath());
        }
    }

    private static Map<String, Double> indexFileSizes(List<SpecFile> files) {
        Map<String, Double> fileSizes = new HashMap<>();
        for (SpecFile file : files) {
            if (file.id == null || file.id.length() == 0) {
                continue;
            }
            if (file.sizeInBytes == null || Double.isNaN(file.sizeInBytes)
                    || Double.isInfinite(file.sizeInBytes) || file.sizeInBytes < 0.0) {
                throw new WorkflowValidationException("Invalid WfCommons file size for " + file.id);
            }
            fileSizes.put(file.id, file.sizeInBytes);
        }
        return fileSizes;
    }

    private static Map<String, Double> indexRuntimes(List<ExecutionTask> tasks) {
        Map<String, Double> runtimes = new HashMap<>();
        for (ExecutionTask task : tasks) {
            if (task.id == null || task.id.length() == 0) {
                continue;
            }
            if (task.runtimeInSeconds == null) {
                throw new WorkflowValidationException("Missing WfCommons execution runtime for task " + task.id);
            }
            runtimes.put(task.id, task.runtimeInSeconds);
        }
        return runtimes;
    }

    private static Map<String, SpecTask> indexSpecTasks(List<SpecTask> tasks) {
        Map<String, SpecTask> specTasks = new LinkedHashMap<>();
        for (SpecTask task : tasks) {
            if (task.id == null || task.id.length() == 0) {
                throw new IllegalArgumentException("WfCommons task id cannot be empty");
            }
            if (specTasks.containsKey(task.id)) {
                throw new IllegalArgumentException("Duplicate WfCommons task id: " + task.id);
            }
            specTasks.put(task.id, task);
        }
        return specTasks;
    }

    private static double getRequiredRuntimeSeconds(Map<String, Double> runtimes, String taskId) {
        Double runtime = runtimes.get(taskId);
        if (runtime == null) {
            throw new IllegalArgumentException("Missing WfCommons execution runtime for task " + taskId);
        }
        if (Double.isNaN(runtime.doubleValue()) || Double.isInfinite(runtime.doubleValue())
                || runtime.doubleValue() < 0.0) {
            throw new IllegalArgumentException("Negative WfCommons runtime for task " + taskId);
        }
        return runtime.doubleValue();
    }

    private static long toCloudletLength(double runtimeSeconds, String taskId,
            WorkflowInputReport report) {
        double length = Parameters.getRuntimeReferenceMips() * runtimeSeconds
                * Parameters.getRuntimeScale();
        if (length < MIN_CLOUDLET_LENGTH) {
            report.recordNormalization(
                    WorkflowInputReport.NormalizationKind.TASK_RUNTIME_FLOORED_TO_MINIMUM,
                    taskId, length, MIN_CLOUDLET_LENGTH);
            length = MIN_CLOUDLET_LENGTH;
        }
        return Math.max(MIN_CLOUDLET_LENGTH, (long) length);
    }

    private static void addFiles(
            List<FileItem> taskFiles,
            Map<String, FileItem> fileItemsByNameAndType,
            List<String> fileNames,
            Map<String, Double> fileSizes,
            FileType fileType,
            String fileNamespace) {
        if (fileNames == null) {
            return;
        }
        for (String fileName : fileNames) {
            if (fileName == null || fileName.length() == 0) {
                continue;
            }
            String scopedFileName = scopedFileName(fileNamespace, fileName);
            FileItem item = getOrCreateFileItem(fileItemsByNameAndType, fileName,
                    scopedFileName, fileSizes, fileType);
            taskFiles.add(item);
            if (fileType == FileType.INPUT && !ReplicaCatalog.containsFile(scopedFileName)) {
                ReplicaCatalog.setFile(scopedFileName, item);
            }
        }
    }

    private static FileItem getOrCreateFileItem(
            Map<String, FileItem> fileItemsByNameAndType,
            String originalFileName,
            String scopedFileName,
            Map<String, Double> fileSizes,
            FileType fileType) {
        String key = scopedFileName + "#" + fileType.name();
        FileItem item = fileItemsByNameAndType.get(key);
        if (item != null) {
            return item;
        }

        Double size = fileSizes.get(originalFileName);
        if (size == null) {
            throw new WorkflowValidationException("Task references an undeclared WfCommons file "
                    + originalFileName);
        }
        double sizeBytes = size.doubleValue();
        item = new FileItem(scopedFileName, sizeBytes);
        item.setType(fileType);
        fileItemsByNameAndType.put(key, item);
        return item;
    }

    /**
     * 生成多输入提交使用的内部逻辑文件身份。
     *
     * <p>该规则必须与 {@link WorkflowParser} 的 DAX 路径一致：单输入保留原文件名，
     * 多输入用输入作用域隔离相同的 WfCommons 文件 ID。</p>
     */
    private static String scopedFileName(String fileNamespace, String originalFileName) {
        if (fileNamespace == null) {
            return originalFileName;
        }
        return fileNamespace + "/" + originalFileName;
    }

    private static void connectDependencies(
            List<SpecTask> specTaskList,
            Map<String, SpecTask> specTasks,
            Map<String, Task> tasksByWfCommonsId) {
        for (SpecTask specTask : specTaskList) {
            Task task = tasksByWfCommonsId.get(specTask.id);
            addParentEdges(task, specTask.parents, specTasks, tasksByWfCommonsId, specTask.id);
            addChildEdges(task, specTask.children, specTasks, tasksByWfCommonsId, specTask.id);
        }
    }

    /**
     * 在将原始 WfFormat 边转换为双向 {@link Task} 图前，拒绝不一致的边声明。
     *
     * <p>不能通过取父边和子边的并集来“修复”输入，否则会掩盖研究实例自身的格式错误。</p>
     */
    private static void validateDeclaredDependencySymmetry(List<SpecTask> specTaskList,
            Map<String, SpecTask> specTasks) {
        for (SpecTask task : specTaskList) {
            validateDeclaredParents(task, specTasks);
            validateDeclaredChildren(task, specTasks);
        }
    }

    private static void validateDeclaredParents(SpecTask child,
            Map<String, SpecTask> specTasks) {
        if (child.parents == null) {
            return;
        }
        for (String parentId : child.parents) {
            SpecTask parent = specTasks.get(parentId);
            if (parent == null) {
                throw new IllegalArgumentException("Task " + child.id
                        + " references missing parent " + parentId);
            }
            if (parent.children == null || !parent.children.contains(child.id)) {
                throw new WorkflowValidationException("Inconsistent WfCommons dependency: task "
                        + child.id + " lists " + parentId + " as a parent, but task "
                        + parentId + " does not list " + child.id + " as a child");
            }
        }
    }

    private static void validateDeclaredChildren(SpecTask parent,
            Map<String, SpecTask> specTasks) {
        if (parent.children == null) {
            return;
        }
        for (String childId : parent.children) {
            SpecTask child = specTasks.get(childId);
            if (child == null) {
                throw new IllegalArgumentException("Task " + parent.id
                        + " references missing child " + childId);
            }
            if (child.parents == null || !child.parents.contains(parent.id)) {
                throw new WorkflowValidationException("Inconsistent WfCommons dependency: task "
                        + parent.id + " lists " + childId + " as a child, but task "
                        + childId + " does not list " + parent.id + " as a parent");
            }
        }
    }

    private static void addParentEdges(
            Task childTask,
            List<String> parentIds,
            Map<String, SpecTask> specTasks,
            Map<String, Task> tasksByWfCommonsId,
            String childId) {
        if (parentIds == null) {
            return;
        }
        for (String parentId : parentIds) {
            if (!specTasks.containsKey(parentId)) {
                throw new IllegalArgumentException("Task " + childId + " references missing parent " + parentId);
            }
            addEdge(tasksByWfCommonsId.get(parentId), childTask);
        }
    }

    private static void addChildEdges(
            Task parentTask,
            List<String> childIds,
            Map<String, SpecTask> specTasks,
            Map<String, Task> tasksByWfCommonsId,
            String parentId) {
        if (childIds == null) {
            return;
        }
        for (String childId : childIds) {
            if (!specTasks.containsKey(childId)) {
                throw new IllegalArgumentException("Task " + parentId + " references missing child " + childId);
            }
            addEdge(parentTask, tasksByWfCommonsId.get(childId));
        }
    }

    private static void addEdge(Task parentTask, Task childTask) {
        if (!parentTask.getChildList().contains(childTask)) {
            parentTask.addChild(childTask);
        }
        if (!childTask.getParentList().contains(parentTask)) {
            childTask.addParent(parentTask);
        }
    }

    /** WfCommons 解析的不可变结果。 */
    public static final class ParseResult {

        private final List<Task> tasks;
        private final int nextTaskId;
        private final WorkflowInputReport inputReport;

        private ParseResult(List<Task> tasks, int nextTaskId, WorkflowInputReport inputReport) {
            this.tasks = tasks;
            this.nextTaskId = nextTaskId;
            this.inputReport = inputReport;
        }

        /** @return 按 specification 中任务顺序转换出的任务 */
        public List<Task> getTasks() {
            return tasks;
        }

        /** @return 后续输入可使用的第一个任务编号 */
        public int getNextTaskId() {
            return nextTaskId;
        }

        /** @return 描述输入格式与归一化过程的报告 */
        public WorkflowInputReport getInputReport() {
            return inputReport;
        }
    }

    private static final class WfCommonsInstance {

        private Workflow workflow;
        private String schemaVersion;
    }

    private static final class Workflow {

        private Specification specification;
        private Execution execution;
    }

    private static final class Specification {

        private List<SpecTask> tasks;
        private List<SpecFile> files;
    }

    private static final class SpecTask {

        private String id;
        private String name;
        private List<String> parents;
        private List<String> children;
        private List<String> inputFiles;
        private List<String> outputFiles;
    }

    private static final class SpecFile {

        private String id;
        private Double sizeInBytes;
    }

    private static final class Execution {

        private List<ExecutionTask> tasks;
    }

    private static final class ExecutionTask {

        private String id;
        private Double runtimeInSeconds;
    }
}
