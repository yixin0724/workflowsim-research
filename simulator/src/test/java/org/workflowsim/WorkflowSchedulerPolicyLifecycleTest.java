package org.workflowsim;

import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Calendar;
import org.cloudbus.cloudsim.core.CloudSim;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.workflowsim.scheduling.BaseSchedulingAlgorithm;
import org.workflowsim.scheduling.ReadyBatchMaxMinSchedulingAlgorithm;
import org.workflowsim.scheduling.ReadyBatchRoundRobinSchedulingAlgorithm;
import org.workflowsim.utils.ClusteringParameters;
import org.workflowsim.utils.OverheadParameters;
import org.workflowsim.utils.Parameters;

class WorkflowSchedulerPolicyLifecycleTest {

    @BeforeEach
    void setUp() {
        Parameters.setRandomSeed(20260901L);
        Parameters.init(1, (String) null, null, null,
                new OverheadParameters(0, null, null, null, null, 0),
                new ClusteringParameters(0, 0, ClusteringParameters.ClusteringMethod.NONE, null),
                Parameters.SchedulingAlgorithm.READY_BATCH_ROUNDROBIN,
                Parameters.PlanningAlgorithm.INVALID, null, 0);
        CloudSim.init(1, Calendar.getInstance(), false);
    }

    @Test
    void retainsStatefulPolicyUntilTheConfiguredTypeChanges() throws Exception {
        WorkflowScheduler scheduler = new WorkflowScheduler("policy-lifecycle-test");

        BaseSchedulingAlgorithm first = scheduler.getSchedulingPolicy();
        BaseSchedulingAlgorithm second = scheduler.getSchedulingPolicy();

        assertSame(first, second);
        assertTrue(first instanceof ReadyBatchRoundRobinSchedulingAlgorithm);

        // 同一个 Scheduler 在算法类型改变后必须丢弃已缓存的有状态策略实例。
        Parameters.init(1, (String) null, null, null,
                new OverheadParameters(0, null, null, null, null, 0),
                new ClusteringParameters(0, 0, ClusteringParameters.ClusteringMethod.NONE, null),
                Parameters.SchedulingAlgorithm.READY_BATCH_MAXMIN,
                Parameters.PlanningAlgorithm.INVALID, null, 0);

        BaseSchedulingAlgorithm replacement = scheduler.getSchedulingPolicy();
        assertNotSame(first, replacement);
        assertTrue(replacement instanceof ReadyBatchMaxMinSchedulingAlgorithm);
    }
}
