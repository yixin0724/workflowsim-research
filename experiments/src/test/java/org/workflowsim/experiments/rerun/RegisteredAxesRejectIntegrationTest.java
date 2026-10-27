package org.workflowsim.experiments.rerun;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.workflowsim.data.DataMovementModel;
import org.workflowsim.experiment.registry.AlgorithmRegistry;
import org.workflowsim.failure.FailureModelConfig;
import org.workflowsim.utils.Parameters.PlanningAlgorithm;
import org.workflowsim.utils.ReplicaCatalog;

/**
 * BAT-R8（2026-10-27 第八轮审查）——缝×缝矩阵批次 S2：注册接口的合同条款
 * （拒绝腿）在缝组合下从未被测过。R7 证明了缝的组合才是真正的未审区。
 *
 * <p>期望值来源：AlgorithmRegistry.validate 的合同条款本身——每条拒绝消息必须
 * 指名约束（不是静默、不是实现输出）。直接驱动 validate()（比经完整 runner
 * 更精确地锁定条款），六个维度由 ValidationInput 显式给出。</p>
 */
class RegisteredAxesRejectIntegrationTest {

    private static final String LABEL = "ENERGY_LPT_FIRST";

    @Test
    void failureModelIsRejectedByTheProviderContract() {
        IllegalArgumentException rejection = assertThrows(
                IllegalArgumentException.class,
                () -> AlgorithmRegistry.validate(
                        LABEL,
                        new AlgorithmRegistry.ValidationInput(
                                ReplicaCatalog.FileSystem.LOCAL,
                                DataMovementModel.legacyWorkflowsimV1(),
                                false,
                                enabledFailureModel(),
                                PlanningAlgorithm.INVALID,
                                true)),
                "ENERGY_LPT_FIRST declares allowsFailureModel=false; an enabled failure model must be rejected");
        assertTrue(
                rejection.getMessage().contains("failure model"),
                "rejection must cite the failure-model clause: " + rejection.getMessage());
    }

    @Test
    void taskCostMatrixIsRejectedByTheProviderContract() {
        IllegalArgumentException rejection = assertThrows(
                IllegalArgumentException.class,
                () -> AlgorithmRegistry.validate(
                        LABEL,
                        new AlgorithmRegistry.ValidationInput(
                                ReplicaCatalog.FileSystem.LOCAL,
                                DataMovementModel.legacyWorkflowsimV1(),
                                true,
                                null,
                                PlanningAlgorithm.INVALID,
                                true)),
                "ENERGY_LPT_FIRST declares acceptsTaskCostMatrix=false; a declared matrix must be rejected");
        assertTrue(
                rejection.getMessage().contains("task-cost matrix"),
                "rejection must cite the cost-matrix clause: " + rejection.getMessage());
    }

    @Test
    void everyClauseGreenTogetherStillValidates() {
        // 反例的反面：六维全部对齐合同（legacy 模型、无矩阵、无失败、INVALID、带功率）
        // ——同一条链必须放行，证明拒绝腿不是一刀切。
        assertDoesNotThrow(() -> AlgorithmRegistry.validate(
                LABEL,
                new AlgorithmRegistry.ValidationInput(
                        ReplicaCatalog.FileSystem.LOCAL,
                        DataMovementModel.legacyWorkflowsimV1(),
                        false,
                        null,
                        PlanningAlgorithm.INVALID,
                        true)));
    }

    private static FailureModelConfig enabledFailureModel() {
        return new FailureModelConfig.Builder()
                .generatorMode(org.workflowsim.failure.FailureParameters.FTCFailure.FAILURE_ALL)
                .build();
    }
}
