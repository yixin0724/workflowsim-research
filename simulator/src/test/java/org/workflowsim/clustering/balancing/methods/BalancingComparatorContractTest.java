package org.workflowsim.clustering.balancing.methods;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.workflowsim.Task;
import org.workflowsim.clustering.TaskSet;

/**
 * F-005（审查工程 C15）：平衡聚类运行长度比较器的序守恒契约。
 *
 * <p>军规来源（守恒律）：升序比较器必须满足——按该比较器排序后，列表
 * <b>相邻元素比较结果非降</b>；任何两个不同运行时长的 TaskSet 不得被报告为
 * "相等"。历史实现 {@code (int)(long 差) } 窄化取低 32 位，差恰为 2^32 倍数时
 * 判"相等"。真实影响案例：大型聚类 runtime（MIPS 缩放 MI）超越 int 域时比较塌缩、
 * LPT 装箱退化为输入序。</p>
 */
class BalancingComparatorContractTest {

    private static TaskSet runtime(long cloudletLength) {
        TaskSet set = new TaskSet();
        set.addTask(new Task((int) cloudletLength, cloudletLength));
        return set;
    }

    @Test
    void runtimeIncreasingSortsInExactAscendingOrder() {
        List<TaskSet> list =
                new ArrayList<TaskSet>(Arrays.asList(runtime(1_000L), runtime(3000L), runtime(500L), runtime(2_000L)));
        // 先注入"输入序与目标序相反"的排布，防止比较器全零时靠稳定性侥幸通过。
        Collections.reverse(list);
        List<TaskSet> sorted = new ArrayList<TaskSet>(list);
        Collections.sort(sorted, HorizontalRuntimeBalancing.RUNTIME_INCREASING);
        assertEquals(Arrays.asList(500L, 1_000L, 2_000L, 3_000L), runtimes(sorted), "升序排序结果必须严格按运行时长排列");
    }

    @Test
    void runtimeIncreasingDistinguishesIntWrapAroundDifferences() {
        // F-005 红用例：(int)(1 - 2^32) 取低 32 位 == 1（方向恰好正确纯属巧合），
        // 而 (int)(2^32 - 1) == -1 方向错误。守恒律要求：任何可区分的运行时长
        // 顺序都必须正确。用 1 而非 0 作为基线：Cloudlet 对 ≤0 长度静默保持默认 1。
        List<TaskSet> pair = new ArrayList<TaskSet>(Arrays.asList(runtime(4294967296L), runtime(1L)));
        Collections.sort(pair, HorizontalRuntimeBalancing.RUNTIME_INCREASING);
        assertEquals(Arrays.asList(1L, 4294967296L), runtimes(pair), "差为 2^32 的两个运行时长必须可区分（红测 F-005：窄化回绕）");

        // 偏差不对称性探针：0 与 2^32+1 的差窄化为 1（方向碰巧正确），
        // 但 0 与 2^32-1 的差窄化为 -1（方向错误）——同一缺陷的另一面。
        List<TaskSet> asymmetric = new ArrayList<TaskSet>(Arrays.asList(runtime(4294967295L), runtime(1L)));
        Collections.sort(asymmetric, HorizontalRuntimeBalancing.RUNTIME_INCREASING);
        assertEquals(Arrays.asList(1L, 4294967295L), runtimes(asymmetric), "0 与 2^32-1 的比较方向也必须正确（窄化回绕的另一面）");
    }

    @Test
    void runtimeDecreasingSortsInExactDescendingOrderAndMirrorsWrapAround() {
        List<TaskSet> list = new ArrayList<TaskSet>(Arrays.asList(runtime(4294967296L), runtime(1L), runtime(1_000L)));
        Collections.sort(list, HorizontalRuntimeBalancing.RUNTIME_DECREASING);
        assertEquals(Arrays.asList(4294967296L, 1_000L, 1L), runtimes(list), "降序排序必须在含大运行时长时保持正确（含 2^32 回绕红测）");
    }

    private static List<Long> runtimes(List<TaskSet> sets) {
        List<Long> values = new ArrayList<Long>(sets.size());
        for (TaskSet set : sets) {
            values.add(Long.valueOf(set.getJobRuntime()));
        }
        return values;
    }
}
