package org.workflowsim.experiment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import java.util.Arrays;
import org.junit.jupiter.api.Test;
import org.workflowsim.utils.Parameters;
import org.workflowsim.utils.ReplicaCatalog;
import org.workflowsim.utils.SimulationConfig;

class AlgorithmCatalogTest {

    @Test
    void independentTaskPlannerContractIsExplicitAndMachineReadable() {
        SimulationConfig config = SimulationConfig.builder("workflow.dax", 2)
                .planningAlgorithm(Parameters.PlanningAlgorithm.STATIC_MINMIN)
                .schedulingAlgorithm(Parameters.SchedulingAlgorithm.STATIC)
                .build();

        Map<String, Object> contract = AlgorithmCatalog.forConfiguration(config);
        Map<String, Object> scheduler = nested(contract, "scheduler");
        Map<String, Object> planner = nested(contract, "planner");

        assertEquals("STATIC_MAPPING_DISPATCH", scheduler.get("decisionLayer"));
        assertEquals("STATIC_MINMIN", planner.get("id"));
        assertEquals("STATIC_INDEPENDENT_TASK_VM_MAPPING", planner.get("decisionLayer"));
        assertEquals("INDEPENDENT_TASKS_ONLY", planner.get("inputDomain"));
        assertEquals("STATIC", planner.get("requiredScheduler"));
        assertEquals("DETERMINISTIC_REGRESSION_COVERED", planner.get("verification"));
        assertEquals("CORE_DECISION_SEMANTICS_UNDER_DECLARED_MODEL",
                contract.get("reproductionStandard"));
    }

    // R9：历史 HEFT/DHEFT 标签与其 provisional 契约条目已一并移除，
    // 原 manifestContractPreservesExistingHeftQualificationBoundary 不再适用。

    @Test
    void sharedStorageHeftDeclaresItsControlledExecutionDomain() {
        SimulationConfig config = SimulationConfig.builder("workflow.dax", 2)
                .planningAlgorithm(Parameters.PlanningAlgorithm.SHARED_STORAGE_HEFT)
                .schedulingAlgorithm(Parameters.SchedulingAlgorithm.STATIC)
                .build();

        Map<String, Object> planner = nested(AlgorithmCatalog.forConfiguration(config), "planner");

        assertEquals("VALID_DAG_SHARED_STORAGE_NO_CLUSTERING", planner.get("inputDomain"));
        assertEquals("CONTROLLED_MODEL_REGRESSION_COVERED", planner.get("verification"));
        assertEquals("CORE_HEFT_DECISION_SEMANTICS_ADAPTED_TO_CONTROLLED_SHARED_STORAGE_MODEL",
                planner.get("reproductionScope"));
    }

    @Test
    void sharedStorageCpopDeclaresItsCriticalProcessorRule() {
        SimulationConfig config = SimulationConfig.builder("workflow.dax", 2)
                .planningAlgorithm(Parameters.PlanningAlgorithm.SHARED_STORAGE_CPOP)
                .schedulingAlgorithm(Parameters.SchedulingAlgorithm.STATIC)
                .build();

        Map<String, Object> planner = nested(AlgorithmCatalog.forConfiguration(config), "planner");

        assertEquals("VALID_DAG_SHARED_STORAGE_NO_CLUSTERING", planner.get("inputDomain"));
        assertTrue(planner.get("decisionRule").toString().contains("critical path"));
        assertEquals("CONTROLLED_MODEL_REGRESSION_COVERED", planner.get("verification"));
        assertEquals("CORE_CPOP_DECISION_SEMANTICS_ADAPTED_TO_CONTROLLED_SHARED_STORAGE_MODEL",
                planner.get("reproductionScope"));
    }

    @Test
    void sharedStorageDlsDeclaresDynamicLevelAndItsNetworkBoundary() {
        SimulationConfig config = SimulationConfig.builder("workflow.dax", 2)
                .planningAlgorithm(Parameters.PlanningAlgorithm.SHARED_STORAGE_DLS)
                .schedulingAlgorithm(Parameters.SchedulingAlgorithm.STATIC)
                .build();

        Map<String, Object> planner = nested(AlgorithmCatalog.forConfiguration(config), "planner");

        assertEquals("VALID_DAG_SHARED_STORAGE_NO_CLUSTERING", planner.get("inputDomain"));
        assertTrue(planner.get("decisionRule").toString().contains("Dynamic-level"));
        assertEquals("CONTROLLED_MODEL_REGRESSION_COVERED", planner.get("verification"));
        assertEquals("CORE_DLS_DYNAMIC_LEVEL_SEMANTICS_ADAPTED_TO_CONTROLLED_SHARED_STORAGE_MODEL",
                planner.get("reproductionScope"));
        assertTrue(planner.get("limitations").toString().contains("interconnect topology"));
    }

    @Test
    void sharedStorageEtfDeclaresEarliestStartAndItsAdaptationBoundary() {
        SimulationConfig config = SimulationConfig.builder("workflow.dax", 2)
                .planningAlgorithm(Parameters.PlanningAlgorithm.SHARED_STORAGE_ETF)
                .schedulingAlgorithm(Parameters.SchedulingAlgorithm.STATIC)
                .build();

        Map<String, Object> planner = nested(AlgorithmCatalog.forConfiguration(config), "planner");

        assertEquals("VALID_DAG_SHARED_STORAGE_NO_CLUSTERING", planner.get("inputDomain"));
        assertTrue(planner.get("decisionRule").toString().contains("Earliest Task First"));
        assertEquals("CONTROLLED_MODEL_REGRESSION_COVERED", planner.get("verification"));
        assertEquals("CORE_ETF_EARLIEST_START_SEMANTICS_ADAPTED_TO_CONTROLLED_SHARED_STORAGE_MODEL",
                planner.get("reproductionScope"));
        assertTrue(planner.get("limitations").toString().contains("homogeneous-processor"));
    }

    @Test
    void sharedStoragePeftDeclaresOptimisticCostLookAheadAndItsNetworkBoundary() {
        SimulationConfig config = SimulationConfig.builder("workflow.dax", 2)
                .planningAlgorithm(Parameters.PlanningAlgorithm.SHARED_STORAGE_PEFT)
                .schedulingAlgorithm(Parameters.SchedulingAlgorithm.STATIC)
                .build();

        Map<String, Object> planner = nested(AlgorithmCatalog.forConfiguration(config), "planner");

        assertEquals("VALID_DAG_SHARED_STORAGE_NO_CLUSTERING", planner.get("inputDomain"));
        assertTrue(planner.get("decisionRule").toString().contains("Predict Earliest Finish Time"));
        assertEquals("CONTROLLED_MODEL_REGRESSION_COVERED", planner.get("verification"));
        assertEquals("CORE_PEFT_OCT_LOOKAHEAD_SEMANTICS_ADAPTED_TO_CONTROLLED_SHARED_STORAGE_MODEL",
                planner.get("reproductionScope"));
        assertTrue(planner.get("limitations").toString().contains("no interprocessor communication term"));
    }

    @Test
    void dataSchedulerDoesNotClaimEndpointRateOrLatencyAwareness() {
        SimulationConfig config = SimulationConfig.builder("workflow.dax", 2)
                .schedulingAlgorithm(Parameters.SchedulingAlgorithm.DATA)
                // DATA 算法的局部性判定要求 LOCAL 文件系统模式（PLAT-7 校验）。
                .fileSystem(ReplicaCatalog.FileSystem.LOCAL)
                .build();

        Map<String, Object> scheduler = nested(AlgorithmCatalog.forConfiguration(config), "scheduler");

        assertEquals("DATA_LOCALITY_BASELINE_REGRESSION_COVERED", scheduler.get("verification"));
        assertTrue(scheduler.get("limitations").toString()
                .contains("does not consult DataMovementModel endpoint latency or transfer rates"));
    }

    @Test
    void maintainedOnlineSchedulersUseCoreRatherThanReferenceStudyVerificationLabels() {
        for (Parameters.SchedulingAlgorithm algorithm : Arrays.asList(
                Parameters.SchedulingAlgorithm.FCFS,
                Parameters.SchedulingAlgorithm.READY_BATCH_ROUNDROBIN,
                Parameters.SchedulingAlgorithm.READY_BATCH_MCT,
                Parameters.SchedulingAlgorithm.READY_BATCH_MINMIN,
                Parameters.SchedulingAlgorithm.READY_BATCH_MAXMIN)) {
            SimulationConfig config = SimulationConfig.builder("workflow.dax", 2)
                    .schedulingAlgorithm(algorithm)
                    .build();
            Map<String, Object> scheduler = nested(AlgorithmCatalog.forConfiguration(config), "scheduler");
            assertEquals("CONTROLLED_MODEL_REGRESSION_COVERED", scheduler.get("verification"));
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> nested(Map<String, Object> values, String key) {
        return (Map<String, Object>) values.get(key);
    }
}
