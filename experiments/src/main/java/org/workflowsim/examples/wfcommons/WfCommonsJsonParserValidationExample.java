/*
 * WfCommons JSON parser validation example.
 *
 * Adapted from https://github.com/yixin0724/workflowsim-fat-tree
 * (examples/org/workflowsim/examples/wfcommons/
 * WfCommonsJsonParserValidationExample.java), which is GPL-3.0-only.
 * This adaptation defaults to datasets/wfformat/ and skips the
 * INDEX.json / *.manifest.json metadata files that live there.
 */
package org.workflowsim.examples.wfcommons;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import org.workflowsim.Task;
import org.workflowsim.WfCommonsJsonParser;
import org.workflowsim.utils.ReplicaCatalog;

/**
 * 在不运行仿真的前提下校验 WfCommons JSON 工作流实例。
 *
 * <p>用法：{@code main [directory]}；默认目录为 {@code datasets/wfformat}。
 * 程序递归查找 {@code *.json} 工作流实例（跳过 {@code INDEX.json} 与
 * {@code *.manifest.json} 元数据），逐个解析并检查生成的任务图：任务长度和深度为正、
 * 父子依赖对称且任务集合非空。</p>
 */
public final class WfCommonsJsonParserValidationExample {

    private WfCommonsJsonParserValidationExample() {
    }

    public static void main(String[] args) throws Exception {
        if (args != null && args.length > 1) {
            throw new IllegalArgumentException("Usage: WfCommonsJsonParserValidationExample [path]");
        }
        String rootPath = args != null && args.length > 0 ? args[0] : "datasets/wfformat";
        List<File> jsonFiles = findJsonFiles(new File(rootPath));
        if (jsonFiles.isEmpty()) {
            throw new IllegalArgumentException("No WfCommons JSON files found under " + rootPath);
        }

        int totalTasks = 0;
        int totalEdges = 0;
        long startMillis = System.currentTimeMillis();
        WfCommonsJsonParser parser = new WfCommonsJsonParser();

        for (File jsonFile : jsonFiles) {
            // ReplicaCatalog 是跨文件共享的历史静态状态；每个文件前重新初始化，
            // 防止文件副本登记泄漏到下一个实例。
            ReplicaCatalog.init(ReplicaCatalog.FileSystem.LOCAL);
            WfCommonsJsonParser.ParseResult result = parser.parse(jsonFile.getPath(), 0, 1);
            List<Task> tasks = result.getTasks();
            int edgeCount = countEdges(tasks);
            validateTaskGraph(jsonFile, tasks);

            totalTasks += tasks.size();
            totalEdges += edgeCount;
            System.out.println(String.format(
                    "WFJSON file=%s tasks=%d edges=%d roots=%d leaves=%d",
                    jsonFile.getPath(),
                    tasks.size(),
                    edgeCount,
                    countRoots(tasks),
                    countLeaves(tasks)));
        }

        long elapsedMillis = System.currentTimeMillis() - startMillis;
        System.out.println(String.format(
                "WFJSON_VALIDATION PASSED files=%d tasks=%d edges=%d elapsed_ms=%d",
                jsonFiles.size(),
                totalTasks,
                totalEdges,
                elapsedMillis));
    }

    private static List<File> findJsonFiles(File root) throws IOException {
        if (!root.exists()) {
            throw new IOException("Path does not exist: " + root.getAbsolutePath());
        }

        List<File> jsonFiles = new ArrayList<>();
        collectJsonFiles(root, jsonFiles);
        Collections.sort(jsonFiles, new Comparator<File>() {
            @Override
            public int compare(File first, File second) {
                return first.getPath().compareTo(second.getPath());
            }
        });
        return jsonFiles;
    }

    private static void collectJsonFiles(File file, List<File> jsonFiles) {
        if (file.isFile()) {
            String name = file.getName().toLowerCase();
            // 跳过 datasets/wfformat 中同样以 .json 结尾的数据集元数据。
            if (name.endsWith(".json") && !"index.json".equals(name) && !name.endsWith(".manifest.json")) {
                jsonFiles.add(file);
            }
            return;
        }

        File[] children = file.listFiles();
        if (children == null) {
            return;
        }
        for (File child : children) {
            collectJsonFiles(child, jsonFiles);
        }
    }

    private static void validateTaskGraph(File jsonFile, List<Task> tasks) {
        if (tasks.isEmpty()) {
            throw new IllegalStateException("Parsed zero tasks from " + jsonFile.getPath());
        }
        for (Task task : tasks) {
            if (task.getCloudletLength() <= 0L) {
                throw new IllegalStateException("Task " + task.getCloudletId()
                        + " has non-positive length in " + jsonFile.getPath());
            }
            if (task.getDepth() <= 0) {
                throw new IllegalStateException("Task " + task.getCloudletId()
                        + " has non-positive depth in " + jsonFile.getPath());
            }
            for (Task child : task.getChildList()) {
                if (!child.getParentList().contains(task)) {
                    throw new IllegalStateException("Dependency is not symmetric in " + jsonFile.getPath());
                }
            }
            for (Task parent : task.getParentList()) {
                if (!parent.getChildList().contains(task)) {
                    throw new IllegalStateException("Dependency is not symmetric in " + jsonFile.getPath());
                }
            }
        }
    }

    private static int countEdges(List<Task> tasks) {
        int edgeCount = 0;
        for (Task task : tasks) {
            edgeCount += task.getChildList().size();
        }
        return edgeCount;
    }

    private static int countRoots(List<Task> tasks) {
        int rootCount = 0;
        for (Task task : tasks) {
            if (task.getParentList().isEmpty()) {
                rootCount++;
            }
        }
        return rootCount;
    }

    private static int countLeaves(List<Task> tasks) {
        int leafCount = 0;
        for (Task task : tasks) {
            if (task.getChildList().isEmpty()) {
                leafCount++;
            }
        }
        return leafCount;
    }
}
