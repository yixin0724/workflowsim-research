/*
 * 
 *   Copyright 2012-2013 University Of Southern California
 * 
 *   Licensed under the Apache License, Version 2.0 (the "License");
 *   you may not use this file except in compliance with the License.
 *   You may obtain a copy of the License at
 * 
 *   http://www.apache.org/licenses/LICENSE-2.0
 * 
 *   Unless required by applicable law or agreed to in writing,
 *   software distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 * 
 */
package org.workflowsim.utils;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.workflowsim.FileItem;

/**
 * 单次仿真中的文件副本目录。
 *
 * <p>目录保存文件元数据及其所在逻辑存储位置。它是由 {@link SimulationSession} 重置的
 * 全局运行期状态，不可跨会话复用，也不代表外部存储系统的一致性或复制协议。</p>
 *
 * @author Weiwei Chen
 * @since WorkflowSim Toolkit 1.0
 * @date Apr 9, 2013
 */
public class ReplicaCatalog {

    /** 支持的文件系统抽象。 */
    public enum FileSystem {
        SHARED, LOCAL
    }
    /** 文件名到文件元数据的映射。 */
    private static Map<String, FileItem> fileName2File;
    /** 当前会话选定的文件系统模型。 */
    private static FileSystem fileSystem;
    /** 文件名到持有该文件的逻辑存储位置列表的映射。 */
    private static Map<String, List<String>> dataReplicaCatalog;

    /**
     * 初始化本次仿真的副本目录。
     *
     * @param fs 共享或本地文件系统模型
     */
    public static void init(FileSystem fs) {
        if (fs == null) {
            throw new IllegalArgumentException("Replica catalog file system cannot be null");
        }
        fileSystem = fs;
        dataReplicaCatalog = new HashMap<>();
        fileName2File = new HashMap<>();
    }

    /** 清理本次运行的全部副本状态。 */
    public static void reset() {
        fileSystem = null;
        dataReplicaCatalog = null;
        fileName2File = null;
    }

    private static void requireInitialized() {
        if (fileSystem == null || dataReplicaCatalog == null || fileName2File == null) {
            throw new IllegalStateException("ReplicaCatalog is not initialized for this simulation run");
        }
    }

    /** @return 当前文件系统模型 */
    public static FileSystem getFileSystem() {
        requireInitialized();
        return fileSystem;
    }

    /**
     * 按文件名查询文件元数据。
     *
     * @param fileName 文件名
     * @return 文件元数据；目录中不存在时为 {@code null}
     */
    public static FileItem getFile(String fileName) {
        requireInitialized();
        return fileName2File.get(fileName);
    }

    /**
     * 写入文件名及其文件元数据。
     *
     * @param fileName 文件名
     * @param file 文件元数据
     */
    public static void setFile(String fileName, FileItem file) {
        requireInitialized();
        fileName2File.put(fileName, file);
    }

    /**
     * 判断目录中是否包含指定文件。
     *
     * @param fileName 文件名
     * @return 存在时为 {@code true}
     */
    public static boolean containsFile(String fileName) {
        requireInitialized();
        return fileName2File.containsKey(fileName);
    }

    /**
     * 查询持有指定文件的逻辑存储位置。
     *
     * @param file 文件名
     * @return 存储位置列表；尚未登记位置时为 {@code null}
     */
    public static List<String> getStorageList(String file) {
        requireInitialized();
        return dataReplicaCatalog.get(file);
    }

    /**
     * 将文件登记到一个逻辑存储位置。
     *
     * @param file 文件名
     * @param storage 持有该文件的存储位置
     */
    public static void addFileToStorage(String file, String storage) {
        requireInitialized();
        if (!dataReplicaCatalog.containsKey(file)) {
            dataReplicaCatalog.put(file, new ArrayList<>());
        }
        List<String> list = getStorageList(file);
        if (!list.contains(storage)) {
            list.add(storage);
        }
    }
}
