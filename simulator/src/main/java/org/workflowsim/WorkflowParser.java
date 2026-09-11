/**
 * Copyright 2012-2013 University Of Southern California
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */
package org.workflowsim;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.jdom2.Document;
import org.jdom2.Element;
import org.jdom2.JDOMException;
import org.jdom2.input.SAXBuilder;
import org.cloudbus.cloudsim.Log;
import org.workflowsim.utils.Parameters;
import org.workflowsim.utils.Parameters.FileType;
import org.workflowsim.utils.ReplicaCatalog;

/**
 * 将工作流输入转换为 WorkflowSim {@link Task} DAG 的统一入口。
 *
 * <p>默认解析 DAX XML；扩展名为 {@code .json} 的输入委托给
 * {@link WfCommonsJsonParser} 解析 WfCommons WfFormat 实例。两条路径均将输入中的
 * 秒级运行时间按参考 MIPS 和运行时间缩放系数转换为百万条指令（MI），并对过小长度
 * 进行显式记录的归一化。</p>
 *
 * @author Weiwei Chen
 * @since WorkflowSim Toolkit 1.0
 * @date Aug 23, 2013
 * @date Nov 9, 2014
 */
public final class WorkflowParser {

    private static final long MIN_CLOUDLET_LENGTH = 100L;

    /** 单个 DAX 或 JSON 输入路径。 */
    private final String daxPath;
    /** 多个 DAX 或 JSON 输入路径。 */
    private final List<String> daxPaths;
    /** 最近一次解析产生的全部任务。 */
    private List<Task> taskList;

    /** 最近一次解析中每个已消费输入的转换报告。 */
    private final List<WorkflowInputReport> inputReports;
    /** 创建任务时写入的 CloudSim 用户编号。 */
    private final int userId;

    /** 下一个可分配任务编号，支持连续提交多个工作流。 */
    private int jobIdStartsFrom;

    /** @return 最近一次解析产生的任务列表 */
    @SuppressWarnings("unchecked")
    public List<Task> getTaskList() {
        return taskList;
    }

    /**
     * 替换当前解析结果任务列表。
     *
     * @param taskList 新的任务列表
     */
    protected void setTaskList(List<Task> taskList) {
        this.taskList = taskList;
    }
    /** DAX 任务名称到任务对象的临时索引。 */
    protected Map<String, Task> mName2Task;

    /**
     * 创建工作流解析器。
     *
     * @param userId 所有解析任务所属的 CloudSim 用户编号
     */
    public WorkflowParser(int userId) {
        this.userId = userId;
        this.mName2Task = new HashMap<>();
        this.daxPath = Parameters.getDaxPath();
        this.daxPaths = Parameters.getDAXPaths();
        this.jobIdStartsFrom = 1;

        setTaskList(new ArrayList<>());
        this.inputReports = new ArrayList<>();
    }

    /**
     * 解析已配置的一个或多个工作流输入。
     *
     * <p>DAX XML 仍是默认格式；以 {@code .json} 结尾的文件会按 WfCommons 工作流实例
     * 解析。每次调用先清除上次的任务、名称索引和输入报告，故结果不与前一次解析累积。</p>
     *
     * @throws WorkflowValidationException 当未配置输入或任一输入不合法时
     */
    public void parse() {
        setTaskList(new ArrayList<Task>());
        mName2Task.clear();
        inputReports.clear();
        jobIdStartsFrom = 1;
        if (this.daxPath != null) {
            parseWorkflowFile(this.daxPath, null);
        } else if (this.daxPaths != null) {
            if (this.daxPaths.isEmpty()) {
                throw new WorkflowValidationException("No workflow input path was configured");
            }
            boolean isolateInputFiles = this.daxPaths.size() > 1;
            for (int inputIndex = 0; inputIndex < this.daxPaths.size(); inputIndex++) {
                String fileNamespace = isolateInputFiles
                        ? "workflow-" + inputIndex
                        : null;
                parseWorkflowFile(this.daxPaths.get(inputIndex), fileNamespace);
            }
        } else {
            throw new WorkflowValidationException("No workflow input path was configured");
        }
    }

    /**
     * 获取最近一次解析所消费输入的不可变报告。
     *
     * @return 每个 DAX 或 WfCommons 输入对应一份报告
     */
    public List<WorkflowInputReport> getInputReports() {
        return Collections.unmodifiableList(inputReports);
    }

    /**
     * 按文件扩展名解析一个工作流输入。
     *
     * @param path 工作流文件路径；DAX 使用 {@code .dax}/{@code .xml}，WfCommons 使用
     * {@code .json}
     */
    private void parseWorkflowFile(String path, String fileNamespace) {
        if (path == null || path.trim().isEmpty()) {
            throw new WorkflowValidationException("Workflow input path cannot be empty");
        }
        String normalizedPath = path.trim();
        if (normalizedPath.toLowerCase().endsWith(".json")) {
            parseWfCommonsJsonFile(normalizedPath, fileNamespace);
            return;
        }
        if (normalizedPath.toLowerCase().endsWith(".dax")
                || normalizedPath.toLowerCase().endsWith(".xml")) {
            parseXmlFile(normalizedPath, fileNamespace);
            return;
        }
        throw new WorkflowValidationException("Unsupported workflow input format: " + path);
    }

    /**
     * 解析一个 WfCommons WfFormat JSON 工作流实例。
     *
     * @param path JSON 文件路径
     */
    private void parseWfCommonsJsonFile(String path, String fileNamespace) {
        try {
            WfCommonsJsonParser.ParseResult result = new WfCommonsJsonParser().parse(
                    path,
                    this.userId,
                    this.jobIdStartsFrom,
                    fileNamespace);
            this.getTaskList().addAll(result.getTasks());
            this.jobIdStartsFrom = result.getNextTaskId();
            this.inputReports.add(result.getInputReport());
        } catch (Exception e) {
            if (e instanceof WorkflowValidationException) {
                throw (WorkflowValidationException) e;
            }
            throw new WorkflowValidationException("Unable to parse WfCommons JSON workflow: " + path, e);
        }
    }

    /**
     * 使用 JDOM 解析一个 DAX XML 文件。
     *
     * <p>先建立全部任务定义，再解析父子关系，最后统一校验 DAG。不在依赖解析阶段
     * 隐式创建缺失任务，以免错误输入被静默接受。</p>
     *
     * @param path DAX XML 路径
     */
    private void parseXmlFile(String path, String fileNamespace) {
        try {
            SAXBuilder builder = new SAXBuilder();
            File source = new File(path);
            if (!source.isFile()) {
                throw new WorkflowValidationException("DAX input does not exist: " + source.getAbsolutePath());
            }
            Document dom = new SAXBuilder().build(source);
            Element root = dom.getRootElement();
            List<Element> list = root.getChildren();

            WorkflowInputReport report = new WorkflowInputReport(path,
                    WorkflowInputReport.Format.DAX_XML, root.getAttributeValue("version"));
            List<Task> parsedTasks = new ArrayList<>();
            mName2Task.clear();

            // 先建立全部任务定义，才能严格解析和验证后续的依赖引用。
            for (Element node : list) {
                if ("job".equalsIgnoreCase(node.getName())) {
                    parsedTasks.add(parseDaxJob(node, report, fileNamespace));
                }
            }

            for (Element node : list) {
                if ("child".equalsIgnoreCase(node.getName())) {
                    parseDaxDependencies(node);
                }
            }
            WorkflowDagValidator.validateAndAssignDepths(parsedTasks);
            report.setTaskCount(parsedTasks.size());
            this.getTaskList().addAll(parsedTasks);
            this.inputReports.add(report);
            this.mName2Task.clear();
        } catch (JDOMException jde) {
            throw new WorkflowValidationException("Invalid DAX XML: " + path, jde);
        } catch (IOException ioe) {
            throw new WorkflowValidationException("Unable to read DAX input: " + path, ioe);
        }
    }

    private Task parseDaxJob(Element node, WorkflowInputReport report, String fileNamespace) {
        String nodeName = requiredAttribute(node, "id", "DAX job");
        if (mName2Task.containsKey(nodeName)) {
            throw new WorkflowValidationException("Duplicate DAX job ID " + nodeName);
        }
        String runtimeAttribute = requiredAttribute(node, "runtime", "DAX job " + nodeName);
        double runtimeSeconds = parseNonNegativeFinite(runtimeAttribute,
                "runtime for DAX job " + nodeName);
        double convertedLength = runtimeSeconds * Parameters.getRuntimeReferenceMips()
                * Parameters.getRuntimeScale();
        long length = toCloudletLength(convertedLength, nodeName, report);

        List<FileItem> files = new ArrayList<>();
        for (Element file : node.getChildren()) {
            if ("uses".equalsIgnoreCase(file.getName())) {
                files.add(parseDaxFile(file, nodeName, fileNamespace));
            }
        }

        Task task = new Task(jobIdStartsFrom++, length);
        task.setType(node.getAttributeValue("name") == null ? "" : node.getAttributeValue("name"));
        task.setUserId(userId);
        for (FileItem file : files) {
            task.addRequiredFile(file.getName());
        }
        task.setFileList(files);
        mName2Task.put(nodeName, task);
        return task;
    }

    private FileItem parseDaxFile(Element file, String taskId, String fileNamespace) {
        String originalFileName = file.getAttributeValue("name");
        if (originalFileName == null || originalFileName.trim().isEmpty()) {
            originalFileName = file.getAttributeValue("file");
        }
        if (originalFileName == null || originalFileName.trim().isEmpty()) {
            throw new WorkflowValidationException("DAX job " + taskId + " contains a file without a name");
        }
        originalFileName = originalFileName.trim();
        String fileName = scopedFileName(fileNamespace, originalFileName);
        String link = requiredAttribute(file, "link", "DAX file " + originalFileName);
        FileType type;
        if ("input".equalsIgnoreCase(link)) {
            type = FileType.INPUT;
        } else if ("output".equalsIgnoreCase(link)) {
            type = FileType.OUTPUT;
        } else {
            throw new WorkflowValidationException("DAX file " + originalFileName
                    + " has unsupported link type " + link);
        }
        double size = parseNonNegativeFinite(
                requiredAttribute(file, "size", "DAX file " + originalFileName),
                "size for DAX file " + originalFileName);
        FileItem item;
        if (type == FileType.INPUT && ReplicaCatalog.containsFile(fileName)) {
            item = ReplicaCatalog.getFile(fileName);
            if (Double.compare(item.getSize(), size) != 0) {
                // 放宽校验：记录警告但继续使用第一次声明的 size。
                // 真实科学工作流中，同一文件在不同任务的 size 估算可能不同，
                // 这是 Pegasus DAX 生成的正常现象，不应阻止仿真执行。
                Log.printLine("WARNING: DAX file '" + originalFileName
                        + "' has inconsistent input size declarations: "
                        + "first=" + item.getSize() + ", current=" + size
                        + " (using first declaration)");
            }
        } else {
            item = new FileItem(fileName, size);
            if (type == FileType.INPUT) {
                ReplicaCatalog.setFile(fileName, item);
            }
        }
        item.setType(type);
        return item;
    }

    /**
     * 为一次多输入提交中的文件建立内部逻辑身份。
     *
     * <p>不同工作流中的同名文件默认互不共享；只有同一输入内重复引用的文件才代表同一
     * 逻辑数据对象。单输入场景保留原文件名，以维持历史 API 与已有实验的兼容性。</p>
     *
     * @param fileNamespace 当前输入的稳定作用域；单输入时为 {@code null}
     * @param originalFileName 输入格式中声明的原始文件名
     * @return 用于任务和副本目录的内部文件身份
     */
    private static String scopedFileName(String fileNamespace, String originalFileName) {
        if (fileNamespace == null) {
            return originalFileName;
        }
        return fileNamespace + "/" + originalFileName;
    }

    private void parseDaxDependencies(Element childNode) {
        String childName = requiredAttribute(childNode, "ref", "DAX child");
        Task childTask = mName2Task.get(childName);
        if (childTask == null) {
            throw new WorkflowValidationException("DAX dependency references missing child " + childName);
        }
        for (Element parentNode : childNode.getChildren()) {
            if (!"parent".equalsIgnoreCase(parentNode.getName())) {
                continue;
            }
            String parentName = requiredAttribute(parentNode, "ref", "DAX parent");
            Task parentTask = mName2Task.get(parentName);
            if (parentTask == null) {
                throw new WorkflowValidationException("DAX dependency references missing parent " + parentName);
            }
            if (!parentTask.getChildList().contains(childTask)) {
                parentTask.addChild(childTask);
            }
            if (!childTask.getParentList().contains(parentTask)) {
                childTask.addParent(parentTask);
            }
        }
    }

    private static String requiredAttribute(Element element, String attribute, String subject) {
        String value = element.getAttributeValue(attribute);
        if (value == null || value.trim().isEmpty()) {
            throw new WorkflowValidationException(subject + " is missing required attribute " + attribute);
        }
        return value.trim();
    }

    private static double parseNonNegativeFinite(String value, String subject) {
        try {
            double number = Double.parseDouble(value);
            if (Double.isNaN(number) || Double.isInfinite(number) || number < 0.0) {
                throw new WorkflowValidationException("Invalid " + subject + ": " + value);
            }
            return number;
        } catch (NumberFormatException exception) {
            throw new WorkflowValidationException("Invalid " + subject + ": " + value, exception);
        }
    }

    private static long toCloudletLength(double convertedLength, String taskId,
            WorkflowInputReport report) {
        if (Double.isNaN(convertedLength) || Double.isInfinite(convertedLength)
                || convertedLength > Long.MAX_VALUE) {
            throw new WorkflowValidationException("Invalid converted runtime for task " + taskId);
        }
        if (convertedLength < MIN_CLOUDLET_LENGTH) {
            report.recordNormalization(
                    WorkflowInputReport.NormalizationKind.TASK_RUNTIME_FLOORED_TO_MINIMUM,
                    taskId, convertedLength, MIN_CLOUDLET_LENGTH);
            return MIN_CLOUDLET_LENGTH;
        }
        return (long) convertedLength;
    }
}
