package org.workflowsim.utils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.cloudbus.cloudsim.Log;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.workflowsim.Job;

/**
 * R9 运行时路径探测：{@link OverheadParameters} 深度采样分支的最小确定性 fixture。
 *
 * <p>该类此前只有队列延迟通道被测试 JVM 内的集成测试覆盖（16.2% 指令覆盖）；
 * 后处理/聚类/引擎延迟通道的深度命中、深度 0 默认、无条目、空映射、空批次与空作业
 * 分支只被独立 JVM 的遗留示例间接执行，对 jacoco 不可见。本测试以最小 Job fixture
 * 在测试 JVM 内逐一锁定每个分支的语义。</p>
 */
class OverheadParametersProbeTest {

    @AfterEach
    void restoreLogging() {
        Log.enable();
    }

    private static Job jobWithDepth(int depth) {
        Job job = new Job(1, 1000L);
        job.setDepth(depth);
        return job;
    }

    private static Map<Integer, DistributionGenerator> delayMap(int key) {
        Map<Integer, DistributionGenerator> map = new HashMap<Integer, DistributionGenerator>();
        map.put(key, new DistributionGenerator(
                DistributionGenerator.DistributionFamily.WEIBULL, 1.0, 100.0));
        return map;
    }

    @Test
    void depthKeyMatchesSamplePositiveDelaysForEveryChannel() {
        OverheadParameters op = new OverheadParameters(7,
                delayMap(2), delayMap(2), delayMap(2), delayMap(2), 42.0);
        Job job = jobWithDepth(2);

        assertTrue(op.getClustDelay(job) > 0.0, "深度命中的聚类延迟必须为正采样");
        assertTrue(op.getQueueDelay(job) > 0.0, "深度命中的队列延迟必须为正采样");
        assertTrue(op.getPostDelay(job) > 0.0, "深度命中的后处理延迟必须为正采样");

        List<Job> batch = new ArrayList<Job>();
        batch.add(job);
        assertTrue(op.getWEDDelay(batch) > 0.0, "深度命中的引擎延迟必须为正采样");
    }

    @Test
    void depthZeroServesAsDefaultForUnmappedDepths() {
        OverheadParameters op = new OverheadParameters(0,
                delayMap(0), delayMap(0), delayMap(0), delayMap(0), 0.0);
        Job job = jobWithDepth(3);

        assertTrue(op.getClustDelay(job) > 0.0, "缺少深度项时应回退到深度 0 默认项");
        assertTrue(op.getQueueDelay(job) > 0.0, "缺少深度项时应回退到深度 0 默认项");
        assertTrue(op.getPostDelay(job) > 0.0, "缺少深度项时应回退到深度 0 默认项");

        List<Job> batch = new ArrayList<Job>();
        batch.add(job);
        assertTrue(op.getWEDDelay(batch) > 0.0, "缺少深度项时应回退到深度 0 默认项");
    }

    @Test
    void unmappedDepthWithoutDefaultYieldsZero() {
        OverheadParameters op = new OverheadParameters(0,
                delayMap(5), delayMap(5), delayMap(5), delayMap(5), 0.0);
        Job job = jobWithDepth(2);

        assertEquals(0.0, op.getClustDelay(job), 0.0);
        assertEquals(0.0, op.getQueueDelay(job), 0.0);
        assertEquals(0.0, op.getPostDelay(job), 0.0);

        List<Job> batch = new ArrayList<Job>();
        batch.add(job);
        assertEquals(0.0, op.getWEDDelay(batch), 0.0);
    }

    @Test
    void nullMapsYieldZeroWithoutSampling() {
        OverheadParameters op = new OverheadParameters(0, null, null, null, null, 0.0);
        Job job = jobWithDepth(0);

        assertEquals(0.0, op.getClustDelay(job), 0.0);
        assertEquals(0.0, op.getQueueDelay(job), 0.0);
        assertEquals(0.0, op.getPostDelay(job), 0.0);
        List<Job> batch = new ArrayList<Job>();
        batch.add(job);
        assertEquals(0.0, op.getWEDDelay(batch), 0.0);
    }

    @Test
    void nullJobAndEmptyBatchLogUnsupportedAndYieldZero() {
        OverheadParameters op = new OverheadParameters(0,
                delayMap(0), delayMap(0), delayMap(0), delayMap(0), 0.0);

        // 空作业/空批次的历史语义是打印 "Not yet supported" 并返回 0；静音控制台。
        Log.disable();
        assertEquals(0.0, op.getClustDelay(null), 0.0);
        assertEquals(0.0, op.getQueueDelay(null), 0.0);
        assertEquals(0.0, op.getPostDelay(null), 0.0);
        assertEquals(0.0, op.getWEDDelay(new ArrayList<Job>()), 0.0);
    }

    @Test
    void accessorsExposeConstructorValues() {
        Map<Integer, DistributionGenerator> wed = delayMap(1);
        Map<Integer, DistributionGenerator> queue = delayMap(2);
        Map<Integer, DistributionGenerator> post = delayMap(3);
        Map<Integer, DistributionGenerator> clust = delayMap(4);
        OverheadParameters op = new OverheadParameters(7, wed, queue, post, clust, 42.0);

        assertEquals(42.0, op.getBandwidth(), 0.0);
        assertEquals(7, op.getWEDInterval());
        assertSame(wed, op.getWEDDelay());
        assertSame(queue, op.getQueueDelay());
        assertSame(post, op.getPostDelay());
        assertSame(clust, op.getClustDelay());
    }
}
