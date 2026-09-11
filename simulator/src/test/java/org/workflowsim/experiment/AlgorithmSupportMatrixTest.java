package org.workflowsim.experiment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.EnumSet;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.workflowsim.data.DataMovementModel;
import org.workflowsim.utils.Parameters.PlanningAlgorithm;
import org.workflowsim.utils.Parameters.SchedulingAlgorithm;
import org.workflowsim.utils.ReplicaCatalog;
import org.workflowsim.utils.SimulationConfig;

class AlgorithmSupportMatrixTest {

    @Test
    void everySchedulerLabelIsExplicitlyClassifiedAsMaintainedOrUnavailable() {
        EnumSet<SchedulingAlgorithm> supported = EnumSet.of(
                SchedulingAlgorithm.FCFS,
                SchedulingAlgorithm.READY_BATCH_MINMIN,
                SchedulingAlgorithm.READY_BATCH_MAXMIN,
                SchedulingAlgorithm.READY_BATCH_MCT,
                SchedulingAlgorithm.READY_BATCH_ROUNDROBIN,
                SchedulingAlgorithm.DATA,
                SchedulingAlgorithm.STATIC);
        EnumSet<SchedulingAlgorithm> unavailable = EnumSet.of(
                SchedulingAlgorithm.MINMIN, SchedulingAlgorithm.MAXMIN,
                SchedulingAlgorithm.MCT, SchedulingAlgorithm.ROUNDROBIN,
                SchedulingAlgorithm.INVALID);

        assertExactPartition(SchedulingAlgorithm.class, supported, unavailable);
        for (SchedulingAlgorithm algorithm : supported) {
            assertTrue(AlgorithmCatalog.isSupportedBySimulationRunner(algorithm), algorithm.name());
        }
        for (SchedulingAlgorithm algorithm : unavailable) {
            assertFalse(AlgorithmCatalog.isSupportedBySimulationRunner(algorithm), algorithm.name());
        }
    }

    @Test
    void everyPlannerLabelIsExplicitlyClassifiedAsMaintainedOrLegacy() {
        EnumSet<PlanningAlgorithm> supported = EnumSet.of(
                PlanningAlgorithm.INVALID,
                PlanningAlgorithm.RANDOM,
                PlanningAlgorithm.STATIC_OLB,
                PlanningAlgorithm.STATIC_MET,
                PlanningAlgorithm.STATIC_MCT,
                PlanningAlgorithm.STATIC_MINMIN,
                PlanningAlgorithm.STATIC_MAXMIN,
                PlanningAlgorithm.STATIC_SUFFERAGE,
                PlanningAlgorithm.STATIC_ROUND_ROBIN,
                PlanningAlgorithm.SHARED_STORAGE_HEFT,
                PlanningAlgorithm.SHARED_STORAGE_CPOP,
                PlanningAlgorithm.SHARED_STORAGE_DLS,
                PlanningAlgorithm.SHARED_STORAGE_ETF,
                PlanningAlgorithm.SHARED_STORAGE_PEFT,
                PlanningAlgorithm.PSO,
                PlanningAlgorithm.LOCAL_HEFT,
                PlanningAlgorithm.LOCAL_CPOP);
        EnumSet<PlanningAlgorithm> legacy = EnumSet.of(
                PlanningAlgorithm.HEFT, PlanningAlgorithm.DHEFT);

        assertExactPartition(PlanningAlgorithm.class, supported, legacy);
        for (PlanningAlgorithm algorithm : supported) {
            assertTrue(AlgorithmCatalog.isSupportedBySimulationRunner(algorithm), algorithm.name());
        }
        for (PlanningAlgorithm algorithm : legacy) {
            assertFalse(AlgorithmCatalog.isSupportedBySimulationRunner(algorithm), algorithm.name());
        }
    }

    @Test
    void everyCatalogEntryCarriesTheSameRunnerSupportClassification() {
        for (SchedulingAlgorithm algorithm : SchedulingAlgorithm.values()) {
            if (algorithm == SchedulingAlgorithm.INVALID) {
                continue;
            }
            SimulationConfig config = schedulingConfig(algorithm);
            Map<String, Object> scheduler = nested(AlgorithmCatalog.forConfiguration(config), "scheduler");
            String verification = String.valueOf(scheduler.get("verification"));
            if (AlgorithmCatalog.isSupportedBySimulationRunner(algorithm)) {
                assertFalse(verification.contains("NOT_SUPPORTED"), algorithm.name());
            } else {
                assertTrue(verification.contains("NOT_SUPPORTED")
                        || "NOT_APPLICABLE".equals(verification), algorithm.name());
            }
        }
        for (PlanningAlgorithm algorithm : PlanningAlgorithm.values()) {
            SimulationConfig config = planningConfig(algorithm);
            Map<String, Object> planner = nested(AlgorithmCatalog.forConfiguration(config), "planner");
            String verification = String.valueOf(planner.get("verification"));
            if (AlgorithmCatalog.isSupportedBySimulationRunner(algorithm)) {
                assertFalse(verification.contains("NOT_SUPPORTED"), algorithm.name());
            } else {
                assertTrue(verification.contains("NOT_SUPPORTED"), algorithm.name());
            }
        }
    }

    private static SimulationConfig schedulingConfig(SchedulingAlgorithm algorithm) {
        if (algorithm == SchedulingAlgorithm.STATIC) {
            return SimulationConfig.builder("workflow.dax", 1)
                    .planningAlgorithm(PlanningAlgorithm.RANDOM)
                    .schedulingAlgorithm(algorithm)
                    .build();
        }
        return SimulationConfig.builder("workflow.dax", 1)
                .schedulingAlgorithm(algorithm)
                // DATA 算法的局部性判定要求 LOCAL 文件系统模式（PLAT-7 校验）。
                .fileSystem(algorithm == SchedulingAlgorithm.DATA
                        ? ReplicaCatalog.FileSystem.LOCAL
                        : ReplicaCatalog.FileSystem.SHARED)
                .build();
    }

    private static SimulationConfig planningConfig(PlanningAlgorithm algorithm) {
        if (algorithm == PlanningAlgorithm.INVALID) {
            return SimulationConfig.builder("workflow.dax", 1).build();
        }
        boolean localComm = algorithm == PlanningAlgorithm.LOCAL_HEFT
                || algorithm == PlanningAlgorithm.LOCAL_CPOP;
        SimulationConfig.Builder builder = SimulationConfig.builder("workflow.dax", 1)
                .planningAlgorithm(algorithm)
                .schedulingAlgorithm(SchedulingAlgorithm.STATIC);
        if (localComm) {
            // LOCAL_HEFT/LOCAL_CPOP 的通信建模要求 LOCAL 文件系统模式与执行前
            // 传输延迟数据移动模型（配置层校验）。
            builder.fileSystem(ReplicaCatalog.FileSystem.LOCAL)
                    .dataMovementModel(DataMovementModel.preExecutionTransferDelayV1());
        } else {
            builder.fileSystem(ReplicaCatalog.FileSystem.SHARED);
        }
        return builder.build();
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static void assertExactPartition(Class<? extends Enum> enumType,
            EnumSet supported, EnumSet unavailable) {
        EnumSet combined = EnumSet.copyOf(supported);
        combined.addAll(unavailable);
        assertEquals(EnumSet.allOf(enumType), combined);
        EnumSet overlap = EnumSet.copyOf(supported);
        overlap.retainAll(unavailable);
        assertTrue(overlap.isEmpty());
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> nested(Map<String, Object> values, String key) {
        return (Map<String, Object>) values.get(key);
    }
}
