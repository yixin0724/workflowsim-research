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

import java.util.HashMap;
import java.util.Map;
import org.cloudbus.cloudsim.HarddriveStorage;
import org.cloudbus.cloudsim.ParameterException;

/**
 * 聚类或虚拟机本地存储模型。
 *
 * <p>该类在 {@link HarddriveStorage} 的容量模型之外，维护到其他存储位置的逻辑带宽。
 * 它是静态网络近似的一部分，不模拟链路队列、带宽争用或协议开销。</p>
 *
 * @author Weiwei Chen
 * @since WorkflowSim Toolkit 1.0
 * @date Apr 9, 2013
 */
public class ClusterStorage extends HarddriveStorage {

    /** 从当前存储位置到其他位置的逻辑带宽映射。 */
    Map<String, Double> bandwidthMap;

    /**
     * 创建一个存储实例。
     *
     * @param name 存储名称
     * @param capacity 存储容量
     * @throws ParameterException 当容量或存储参数无效时
     */
    public ClusterStorage(String name, double capacity) throws ParameterException {
        super(name, capacity);
    }

    /**
     * 设置到目标存储位置的逻辑带宽。
     *
     * @param name 目标存储名称
     * @param bandwidth 带宽；负值会被忽略
     */
    public final void setBandwidth(String name, double bandwidth) {
        if (bandwidth >= 0) {
            if (bandwidthMap == null) {
                bandwidthMap = new HashMap<>();
            }
            bandwidthMap.put(name, bandwidth);
        }
    }

    /**
     * 查询到目标存储位置的逻辑带宽。
     *
     * <p>未配置专用链路时回退到 {@code local} 带宽。</p>
     *
     * @param destination 目标存储名称
     * @return 配置的最大带宽
     */
    public double getMaxBandwidth(String destination) {
        if (bandwidthMap.containsKey(destination)) {
            return bandwidthMap.get(destination);
        } else {
            // 未配置专用链路时，使用虚拟机之间的本地带宽近似。
            return bandwidthMap.get("local");
        }
    }
}
