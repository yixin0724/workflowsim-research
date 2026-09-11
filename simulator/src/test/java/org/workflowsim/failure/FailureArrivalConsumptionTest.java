package org.workflowsim.failure;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Collections;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.workflowsim.Job;
import org.workflowsim.Task;
import org.workflowsim.utils.DistributionGenerator;

/** 验证共享故障到达流不会把同一累计到达重复归因给多个执行窗口。 */
class FailureArrivalConsumptionTest {

    @AfterEach
    void resetFailureState() {
        FailureGenerator.reset();
        FailureMonitor.reset();
        FailureParameters.reset();
    }

    @Test
    void consumesExpiredAndMatchedArrivalsThroughTheirActualCumulativeIndices() {
        // 原始 intervals 为 [2, 3, 5]，累计 arrival 为 [2, 5, 10]。
        FixedDistributionGenerator generator = new FixedDistributionGenerator(2.0, 3.0, 5.0);
        FailureParameters.init(FailureParameters.FTCluteringAlgorithm.FTCLUSTERING_NOOP,
                FailureParameters.FTCMonitor.MONITOR_ALL,
                FailureParameters.FTCFailure.FAILURE_ALL,
                new DistributionGenerator[][]{{generator}});
        FailureMonitor.init();
        FailureGenerator.init();

        // 第一个窗口跳过 arrival=2，保留未来的 arrival=5。
        assertFalse(FailureGenerator.generate(jobWithWindow(1, 3.0, 4.0)));
        assertEquals(1, generator.getConsumedSampleCount());

        // 第二个窗口只消费 arrival=5，第三个窗口再消费 arrival=10。
        assertTrue(FailureGenerator.generate(jobWithWindow(2, 4.0, 6.0)));
        assertEquals(2, generator.getConsumedSampleCount());
        assertTrue(FailureGenerator.generate(jobWithWindow(3, 9.0, 11.0)));
        assertEquals(3, generator.getConsumedSampleCount());

        // DR 的历史估计读取同一游标；它现在覆盖真实经过的全部三段间隔，而非前两段。
        assertEquals(10.0 / 3.0, generator.getMean(), 0.0);
    }

    private static Job jobWithWindow(int id, double start, double finish) {
        Task task = new Task(id, 1L);
        task.setDepth(0);
        task.setExecStartTime(start);
        task.setTaskFinishTime(finish);
        Job job = new Job(id, 1L);
        job.setUserId(0);
        job.setVmId(0);
        job.addTaskList(Collections.singletonList(task));
        return job;
    }

    /** 固定 arrival 序列使游标语义不依赖随机采样。 */
    private static final class FixedDistributionGenerator extends DistributionGenerator {

        private FixedDistributionGenerator(double... values) {
            super(DistributionFamily.WEIBULL, 1.0, 1.0, "failure-arrival-consumption-test");
            this.samples = values.clone();
            updateCumulativeSamples();
            this.cursor = 0;
        }
    }
}
