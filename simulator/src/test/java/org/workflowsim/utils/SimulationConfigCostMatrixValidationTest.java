package org.workflowsim.utils;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Collections;
import org.junit.jupiter.api.Test;
import org.workflowsim.utils.Parameters.PlanningAlgorithm;
import org.workflowsim.utils.Parameters.SchedulingAlgorithm;

/**
 * 回归测试（R8 审计，算法通道 P1-1）：任务成本矩阵 × SHARED_STORAGE_* 规划器
 * 的组合必须在配置层被拒绝。
 *
 * <p>背景：SharedStorageDagPlanner 的规划侧执行时间只用原始 DAX 长度、不消费
 * 成本矩阵，而 STATIC 派发路径会在运行时按矩阵折算 MI——两侧语义静默分叉会
 * 产生错误的规划顺序且无任何报错。合法组合（LOCAL_HEFT/LOCAL_CPOP + 矩阵）
 * 保持可用。</p>
 */
class SimulationConfigCostMatrixValidationTest {

    private static final PlanningAlgorithm[] SHARED_STORAGE_PLANNERS = {
            PlanningAlgorithm.SHARED_STORAGE_HEFT,
            PlanningAlgorithm.SHARED_STORAGE_CPOP,
            PlanningAlgorithm.SHARED_STORAGE_DLS,
            PlanningAlgorithm.SHARED_STORAGE_ETF,
            PlanningAlgorithm.SHARED_STORAGE_PEFT,
    };

    @Test
    void rejectsCostMatrixWithEverySharedStoragePlanner() {
        for (PlanningAlgorithm planner : SHARED_STORAGE_PLANNERS) {
            IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                    () -> SimulationConfig.builder(Collections.singletonList("workflow.dax"), 2)
                            .planningAlgorithm(planner)
                            .schedulingAlgorithm(SchedulingAlgorithm.STATIC)
                            .taskCostMatrix(matrix())
                            .build(),
                    planner + " + 成本矩阵必须被拒绝");
            assertTrue(failure.getMessage().contains(planner.toString()), failure.getMessage());
            assertTrue(failure.getMessage().contains("task cost matrix"), failure.getMessage());
        }
    }

    @Test
    void acceptsCostMatrixWithLocalHeftPlanner() {
        assertDoesNotThrow(() -> SimulationConfig.builder(Collections.singletonList("workflow.dax"), 2)
                .planningAlgorithm(PlanningAlgorithm.LOCAL_HEFT)
                .schedulingAlgorithm(SchedulingAlgorithm.STATIC)
                .fileSystem(ReplicaCatalog.FileSystem.LOCAL)
                .dataMovementModel(org.workflowsim.data.DataMovementModel.preExecutionTransferDelayV1())
                .taskCostMatrix(matrix())
                .build());
    }

    private static TaskCostMatrix matrix() {
        return TaskCostMatrix.builder().put(1, 0, 10.0).build();
    }
}
