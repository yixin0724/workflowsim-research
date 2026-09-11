/**
 * Copyright 2014-2015 University Of Southern California
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

import java.util.List;
import org.workflowsim.utils.Parameters.FileType;

/**
 * WorkflowSim 的工作流文件描述。
 *
 * <p>CloudSim 已提供 {@code File}，但工作流的数据依赖还需要以 {@code double} 保存
 * 文件大小并区分输入、输出和中间文件语义，因此使用独立的 {@code FileItem} 表示。
 * 文件大小单位由输入格式约定；WfCommons 和 DAX 路径均以字节传入。</p>
 *
 * @author weiweich
 */
public class FileItem {

    private String name;

    private double size;

    private FileType type;

    public FileItem(String name, double size) {
        this.name = name;
        this.size = size;
    }

    public void setName(String name) {
        this.name = name;
    }

    public void setSize(double size) {
        this.size = size;
    }

    public void setType(FileType type) {
        this.type = type;
    }

    public String getName() {
        return this.name;
    }

    public double getSize() {
        return this.size;
    }

    public FileType getType() {
        return this.type;
    }
    
    /**
     * 判断输入文件是否需要从作业外部执行 stage-in。
     *
     * <p>工作流采用“文件写入一次、可被多次读取”的约定。若同一作业内存在同名输出文件，
     * 则该输入由作业内任务产生，不应重复计入 stage-in。该判断主要服务于水平聚类后的
     * 文件传输估算。</p>
     *
     * @param list 当前作业关联的全部文件
     * @return 当前对象是没有同名作业内输出文件的真实外部输入时返回 {@code true}
     */
    public boolean isRealInputFile(List<FileItem> list) {
        if (this.getType() == FileType.INPUT) {
            for (FileItem another : list) {
                // 同名输出表明该数据在当前作业内产生，无需额外 stage-in。
                if (another.getName().equals(this.getName())
                        && another.getType() == FileType.OUTPUT) {
                    return false;
                }
            }
            return true;
        }
        return false;
    }
}
