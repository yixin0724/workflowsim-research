/*
 * Copyright 2026 WorkflowSim maintainers
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.workflowsim.utils;

import java.util.HashMap;
import java.util.Map;
import java.util.Random;
import org.apache.commons.math3.random.RandomGenerator;
import org.apache.commons.math3.random.Well19937c;

/**
 * 管理单次 WorkflowSim 配置的可复现伪随机流。
 *
 * <p>调用方按稳定的组件名称获取新的生成器。同一组件的生成器获得不同但确定性的种子；
 * 因此一个组件增加抽样次数不会扰动其他组件的随机序列。该隔离是可复现实验和
 * common-random-numbers 比较的基础，但不等同于统计有效性证明。</p>
 */
public final class SimulationRandom {

    private static final Map<String, Random> seedStreams = new HashMap<>();
    private static long rootSeed = 0L;

    private SimulationRandom() {
    }

    /**
     * 为下一次仿真启动新的确定性随机上下文。
     *
     * @param seed 要记录在实验配置中的根种子
     */
    public static synchronized void reset(long seed) {
        rootSeed = seed;
        seedStreams.clear();
        DistributionGenerator.resetStreamAllocation();
    }

    /**
     * 为指定模型组件创建 Java 随机数生成器。
     *
     * @param component 稳定的组件名称，例如 {@code planning.random}
     * @return 新建的确定性生成器
     */
    public static synchronized Random newJavaRandom(String component) {
        return new Random(nextSeed(component));
    }

    /**
     * 为指定模型组件创建 Apache Commons Math 随机数生成器。
     *
     * @param component 稳定的组件名称
     * @return 新建的确定性生成器
     */
    public static synchronized RandomGenerator newApacheRandom(String component) {
        return new Well19937c(nextSeed(component));
    }

    private static long nextSeed(String component) {
        if (component == null || component.isEmpty()) {
            throw new IllegalArgumentException("Random component name must not be empty");
        }
        Random stream = seedStreams.get(component);
        if (stream == null) {
            stream = new Random(mix64(rootSeed ^ stableHash(component)));
            seedStreams.put(component, stream);
        }
        return stream.nextLong();
    }

    private static long stableHash(String value) {
        long hash = 0xcbf29ce484222325L;
        for (int i = 0; i < value.length(); i++) {
            hash ^= value.charAt(i);
            hash *= 0x100000001b3L;
        }
        return hash;
    }

    private static long mix64(long value) {
        value = (value ^ (value >>> 30)) * 0xbf58476d1ce4e5b9L;
        value = (value ^ (value >>> 27)) * 0x94d049bb133111ebL;
        return value ^ (value >>> 31);
    }
}
