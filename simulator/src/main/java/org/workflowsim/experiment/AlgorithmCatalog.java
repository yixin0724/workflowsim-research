package org.workflowsim.experiment;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.workflowsim.utils.Parameters.PlanningAlgorithm;
import org.workflowsim.utils.Parameters.SchedulingAlgorithm;
import org.workflowsim.utils.SimulationConfig;

/**
 * 可选算法的机器可读语义契约。
 *
 * <p>目录记录当前实现的决策范围和回归验证状态；它不构成现实平台校准或算法
 * 普遍优越性的主张。</p>
 */
public final class AlgorithmCatalog {

    private AlgorithmCatalog() {
    }

    /**
     * 判断调度器标签是否在标准 {@link SimulationRunner} 入口中具有已维护的执行契约。
     *
     * <p>该判断刻意独立于目录对历史标签的描述能力。将分类集中在这里，可以在
     * 新增枚举值时避免运行器与 manifest 契约悄然偏离。</p>
     *
     * @param algorithm 待判断的调度算法标签
     * @return 当该标签可经标准运行器生成研究证据时返回 {@code true}
     * @throws IllegalArgumentException 当 {@code algorithm} 为空时抛出
     */
    public static boolean isSupportedBySimulationRunner(SchedulingAlgorithm algorithm) {
        if (algorithm == null) {
            throw new IllegalArgumentException("Scheduling algorithm is required");
        }
        switch (algorithm) {
            case FCFS:
            case READY_BATCH_MINMIN:
            case READY_BATCH_MAXMIN:
            case READY_BATCH_MCT:
            case READY_BATCH_ROUNDROBIN:
            case DATA:
            case STATIC:
            case RL_POLICY:
                return true;
            case MINMIN:
            case MAXMIN:
            case MCT:
            case ROUNDROBIN:
            case INVALID:
            default:
                return false;
        }
    }

    /**
     * 判断规划器标签是否在标准 {@link SimulationRunner} 入口中具有已维护的执行契约。
     *
     * <p>在线调度配置有意不包含规划阶段，因此 {@code INVALID} 在这里属于支持状态。</p>
     *
     * @param algorithm 待判断的规划算法标签
     * @return 当该标签可经标准运行器使用时返回 {@code true}
     * @throws IllegalArgumentException 当 {@code algorithm} 为空时抛出
     */
    public static boolean isSupportedBySimulationRunner(PlanningAlgorithm algorithm) {
        if (algorithm == null) {
            throw new IllegalArgumentException("Planning algorithm is required");
        }
        switch (algorithm) {
            case INVALID:
            case RANDOM:
            case STATIC_OLB:
            case STATIC_MET:
            case STATIC_MCT:
            case STATIC_MINMIN:
            case STATIC_MAXMIN:
            case STATIC_SUFFERAGE:
            case STATIC_ROUND_ROBIN:
            case SHARED_STORAGE_HEFT:
            case SHARED_STORAGE_CPOP:
            case SHARED_STORAGE_DLS:
            case SHARED_STORAGE_ETF:
            case SHARED_STORAGE_PEFT:
            case PSO:
            case LOCAL_HEFT:
            case LOCAL_CPOP:
                return true;
            default:
                return false;
        }
    }

    /**
     * 返回会写入每个运行 manifest 的调度器和规划器契约。
     *
     * @param config 本次运行的不可变配置
     * @return 按固定字段顺序组织的机器可读算法契约
     * @throws IllegalArgumentException 当 {@code config} 为空时抛出
     */
    public static Map<String, Object> forConfiguration(SimulationConfig config) {
        if (config == null) {
            throw new IllegalArgumentException("Simulation configuration is required");
        }
        Map<String, Object> contract = new LinkedHashMap<String, Object>();
        contract.put("scheduler", scheduler(config.getSchedulingAlgorithm()));
        contract.put("planner", planner(config.getPlanningAlgorithm()));
        contract.put("scopeStatement", "Algorithm labels describe this WorkflowSim implementation "
                + "and its declared abstract-model scope; standard SimulationRunner evidence uses "
                + "SPACE_SHARED VMs and no task clustering, and does not establish real-platform calibration.");
        contract.put("reproductionStandard", "CORE_DECISION_SEMANTICS_UNDER_DECLARED_MODEL");
        return contract;
    }

    private static Map<String, Object> scheduler(SchedulingAlgorithm algorithm) {
        Map<String, Object> values = base(algorithm.name());
        switch (algorithm) {
            case FCFS:
                values.put("decisionLayer", "ONLINE_READY_JOB");
                values.put("inputDomain", "VALID_DAG_AFTER_ENGINE_DEPENDENCY_RELEASE");
                values.put("decisionRule", "First-ready-job, first-idle-VM dispatch");
                values.put("verification", "CONTROLLED_MODEL_REGRESSION_COVERED");
                values.put("limitations", limitations("Orders ready Jobs, not raw Tasks or an offline DAG schedule."));
                break;
            case READY_BATCH_ROUNDROBIN:
                values.put("decisionLayer", "ONLINE_READY_JOB");
                values.put("inputDomain", "VALID_DAG_AFTER_ENGINE_DEPENDENCY_RELEASE");
                values.put("decisionRule", "Retained round-robin cursor over idle VMs for each ready batch");
                values.put("verification", "CONTROLLED_MODEL_REGRESSION_COVERED");
                values.put("limitations", limitations("The batch is the runtime-ready Job set, not a static independent-task batch."));
                break;
            case READY_BATCH_MCT:
                values.put("decisionLayer", "ONLINE_READY_JOB");
                values.put("inputDomain", "VALID_DAG_AFTER_ENGINE_DEPENDENCY_RELEASE");
                values.put("decisionRule", "Ready-batch minimum estimated completion time under current idle-VM state");
                values.put("verification", "CONTROLLED_MODEL_REGRESSION_COVERED");
                values.put("limitations", limitations("Not the offline independent-task MCT heuristic."));
                break;
            case READY_BATCH_MINMIN:
                values.put("decisionLayer", "ONLINE_READY_JOB");
                values.put("inputDomain", "VALID_DAG_AFTER_ENGINE_DEPENDENCY_RELEASE");
                values.put("decisionRule", "Ready-batch iterative minimum completion-time selection");
                values.put("verification", "CONTROLLED_MODEL_REGRESSION_COVERED");
                values.put("limitations", limitations("Not the offline independent-task Min-Min heuristic."));
                break;
            case READY_BATCH_MAXMIN:
                values.put("decisionLayer", "ONLINE_READY_JOB");
                values.put("inputDomain", "VALID_DAG_AFTER_ENGINE_DEPENDENCY_RELEASE");
                values.put("decisionRule", "Ready-batch iterative maximum of each Job's minimum completion time");
                values.put("verification", "CONTROLLED_MODEL_REGRESSION_COVERED");
                values.put("limitations", limitations("Not the offline independent-task Max-Min heuristic."));
                break;
            case DATA:
                values.put("decisionLayer", "ONLINE_READY_JOB");
                values.put("inputDomain", "VALID_DAG_AFTER_ENGINE_DEPENDENCY_RELEASE");
                values.put("decisionRule", "Selects a compatible VM with the fewest required nonlocal input bytes; "
                        + "equal totals use lower VM ID");
                values.put("verification", "DATA_LOCALITY_BASELINE_REGRESSION_COVERED");
                values.put("limitations", limitations("Input byte counts are not transfer times; this scheduler does not "
                        + "consult DataMovementModel endpoint latency or transfer rates.",
                        "No calibrated network, replica, or bandwidth-contention model is implied."));
                break;
            case STATIC:
                values.put("decisionLayer", "STATIC_MAPPING_DISPATCH");
                values.put("inputDomain", "MAPPED_JOBS_ONLY");
                values.put("decisionRule", "Dispatches a Job to the VM assigned by the planning layer");
                values.put("verification", "REGRESSION_COVERED_WITH_STATIC_MCT");
                values.put("limitations", limitations("A SimulationConfig requires a non-INVALID planner; model-generated stage-in Jobs use the legacy fallback mapping."));
                break;
            case RL_POLICY:
                values.put("decisionLayer", "ONLINE_READY_JOB");
                values.put("inputDomain", "VALID_DAG_AFTER_ENGINE_DEPENDENCY_RELEASE");
                values.put("decisionRule", "Registered RlPolicy selects a VM index per ready Job "
                        + "(RlEnvironment episode; reward = negative makespan)");
                values.put("verification", "RL_EPISODE_ENVIRONMENT_REGRESSION_COVERED");
                values.put("limitations", limitations("The policy is an external decision function; the platform "
                        + "guarantees deterministic episodes and action validation only.",
                        "Actions on busy/incompatible/reserved VMs skip the Job for that update; no built-in "
                        + "learning algorithm is provided.",
                        "Must run through RlEnvironment.runEpisode; SimulationRunner alone fails without a "
                        + "registered policy."));
                break;
            case MINMIN:
                legacyScheduler(values, "SPT-fastest-idle ready-job variant", "READY_BATCH_MINMIN");
                break;
            case MAXMIN:
                legacyScheduler(values, "LJF-fastest-idle ready-job variant", "READY_BATCH_MAXMIN");
                break;
            case MCT:
                legacyScheduler(values, "Greedy fastest-idle-VM ready-job variant", "READY_BATCH_MCT");
                break;
            case ROUNDROBIN:
                legacyScheduler(values, "First-fit-idle ready-job variant", "READY_BATCH_ROUNDROBIN");
                break;
            case INVALID:
            default:
                values.put("decisionLayer", "NONE");
                values.put("inputDomain", "NOT_A_RUNNABLE_RESEARCH_CONFIGURATION");
                values.put("decisionRule", "No explicit scheduler selected");
                values.put("verification", "NOT_APPLICABLE");
                values.put("limitations", limitations("Use an explicit non-INVALID scheduler."));
                break;
        }
        return values;
    }

    private static void legacyScheduler(Map<String, Object> values, String rule, String replacement) {
        values.put("decisionLayer", "ONLINE_READY_JOB");
        values.put("inputDomain", "VALID_DAG_AFTER_ENGINE_DEPENDENCY_RELEASE");
        values.put("decisionRule", rule);
        values.put("verification", "LEGACY_COMPATIBILITY_ONLY_NOT_SUPPORTED_BY_SIMULATION_RUNNER");
        values.put("limitations", limitations("The standard SimulationRunner rejects this label because it is not "
                + "the classical offline heuristic; use " + replacement + " for the maintained online variant."));
    }

    private static Map<String, Object> planner(PlanningAlgorithm algorithm) {
        Map<String, Object> values = base(algorithm.name());
        switch (algorithm) {
            case RANDOM:
                values.put("decisionLayer", "STATIC_DAG_VM_MAPPING");
                values.put("inputDomain", "VALID_DAG");
                values.put("decisionRule", "Seeded random Task-to-compatible-VM mapping before clustering");
                values.put("requiredScheduler", SchedulingAlgorithm.STATIC.name());
                values.put("verification", "DETERMINISTIC_MAPPING_REGRESSION_COVERED");
                values.put("limitations", limitations("Runtime dependencies remain engine-enforced; this is not an offline timing schedule."));
                break;
            case SHARED_STORAGE_HEFT:
                values.put("decisionLayer", "STATIC_DAG_VM_MAPPING_AND_PER_VM_ORDER");
                values.put("inputDomain", "VALID_DAG_SHARED_STORAGE_NO_CLUSTERING");
                values.put("decisionRule", "HEFT upward rank with insertion-based earliest finish time; "
                        + "per-task shared-storage input delay is added for every VM");
                values.put("requiredScheduler", SchedulingAlgorithm.STATIC.name());
                values.put("verification", "CONTROLLED_MODEL_REGRESSION_COVERED");
                values.put("reproductionScope", "CORE_HEFT_DECISION_SEMANTICS_ADAPTED_TO_CONTROLLED_SHARED_STORAGE_MODEL");
                values.put("limitations", limitations("Requires SHARED storage, NONE clustering, disabled overhead/failure, and SPACE_SHARED VMs.",
                        "It is aligned to the current abstract shared-storage model, not calibrated to a real storage or network system."));
                break;
            case SHARED_STORAGE_CPOP:
                values.put("decisionLayer", "STATIC_DAG_VM_MAPPING_AND_PER_VM_ORDER");
                values.put("inputDomain", "VALID_DAG_SHARED_STORAGE_NO_CLUSTERING");
                values.put("decisionRule", "CPOP upward-plus-downward rank priority; an identified critical path is assigned to the minimum-cost processor");
                values.put("requiredScheduler", SchedulingAlgorithm.STATIC.name());
                values.put("verification", "CONTROLLED_MODEL_REGRESSION_COVERED");
                values.put("reproductionScope", "CORE_CPOP_DECISION_SEMANTICS_ADAPTED_TO_CONTROLLED_SHARED_STORAGE_MODEL");
                values.put("limitations", limitations("Requires SHARED storage, NONE clustering, disabled overhead/failure, and SPACE_SHARED VMs.",
                        "It is aligned to the current abstract shared-storage model, not calibrated to a real storage or network system."));
                break;
            case SHARED_STORAGE_DLS:
                values.put("decisionLayer", "STATIC_DAG_VM_MAPPING_AND_PER_VM_ORDER");
                values.put("inputDomain", "VALID_DAG_SHARED_STORAGE_NO_CLUSTERING");
                values.put("decisionRule", "Dynamic-level selection over dependency-ready Task-VM candidates: "
                        + "upward-rank static level minus insertion-based earliest start time; "
                        + "equal dynamic levels use lower Task ID, then lower VM ID");
                values.put("requiredScheduler", SchedulingAlgorithm.STATIC.name());
                values.put("verification", "CONTROLLED_MODEL_REGRESSION_COVERED");
                values.put("reproductionScope", "CORE_DLS_DYNAMIC_LEVEL_SEMANTICS_ADAPTED_TO_CONTROLLED_SHARED_STORAGE_MODEL");
                values.put("limitations", limitations("Requires SHARED storage, NONE clustering, disabled overhead/failure, and SPACE_SHARED VMs.",
                        "It adapts DLS to the current shared-storage model and does not reproduce irregular interconnect topology, routing, or communication contention.",
                        "It is not calibrated to a real storage or network system."));
                break;
            case SHARED_STORAGE_ETF:
                values.put("decisionLayer", "STATIC_DAG_VM_MAPPING_AND_PER_VM_ORDER");
                values.put("inputDomain", "VALID_DAG_SHARED_STORAGE_NO_CLUSTERING");
                values.put("decisionRule", "Earliest Task First selection over dependency-ready Task-VM candidates: "
                        + "choose the minimum insertion-based earliest start time; equal starts use higher "
                        + "upward-rank static b-level, then lower Task ID and VM ID");
                values.put("requiredScheduler", SchedulingAlgorithm.STATIC.name());
                values.put("verification", "CONTROLLED_MODEL_REGRESSION_COVERED");
                values.put("reproductionScope", "CORE_ETF_EARLIEST_START_SEMANTICS_ADAPTED_TO_CONTROLLED_SHARED_STORAGE_MODEL");
                values.put("limitations", limitations("Requires SHARED storage, NONE clustering, disabled overhead/failure, and SPACE_SHARED VMs.",
                        "It adapts ETF to the current shared-storage model and does not reproduce the original communication-delay or homogeneous-processor assumptions.",
                        "It is not calibrated to a real storage or network system."));
                break;
            case SHARED_STORAGE_PEFT:
                values.put("decisionLayer", "STATIC_DAG_VM_MAPPING_AND_PER_VM_ORDER");
                values.put("inputDomain", "VALID_DAG_SHARED_STORAGE_NO_CLUSTERING");
                values.put("decisionRule", "Predict Earliest Finish Time: dependency-ready Task with "
                        + "maximum compatible-VM average optimistic cost rank, followed by the VM "
                        + "that minimizes insertion-based earliest finish plus optimistic successor cost");
                values.put("requiredScheduler", SchedulingAlgorithm.STATIC.name());
                values.put("verification", "CONTROLLED_MODEL_REGRESSION_COVERED");
                values.put("reproductionScope", "CORE_PEFT_OCT_LOOKAHEAD_SEMANTICS_"
                        + "ADAPTED_TO_CONTROLLED_SHARED_STORAGE_MODEL");
                values.put("limitations", limitations("Requires SHARED storage, NONE clustering, disabled overhead/failure, and SPACE_SHARED VMs.",
                        "The controlled model has no interprocessor communication term, topology, routing, or link contention; OCT is therefore a successor look-ahead rather than a network prediction.",
                        "It is not calibrated to a real storage or network system."));
                break;
            case STATIC_OLB:
                independentPlanner(values, "Opportunistic Load Balancing based on least predicted VM availability");
                break;
            case STATIC_MET:
                independentPlanner(values, "Minimum Execution Time mapping ignoring predicted VM availability");
                break;
            case STATIC_MCT:
                independentPlanner(values, "Minimum Completion Time mapping with predicted VM availability");
                break;
            case STATIC_MINMIN:
                independentPlanner(values, "Classical iterative global minimum of per-task minimum completion times");
                break;
            case STATIC_MAXMIN:
                independentPlanner(values, "Classical iterative global maximum of per-task minimum completion times");
                break;
            case STATIC_SUFFERAGE:
                independentPlanner(values, "Iterative maximum loss between best and second-best completion times");
                break;
            case STATIC_ROUND_ROBIN:
                independentPlanner(values, "Deterministic ascending-VM-ID cyclic mapping");
                break;
            case PSO:
                values.put("decisionLayer", "STATIC_DAG_VM_MAPPING");
                values.put("inputDomain", "VALID_DAG");
                values.put("decisionRule", "Particle swarm optimization over Task-to-VM mappings: "
                        + "fitness = 0.8 * sequential-load execution cost (price = mips/1000) "
                        + "+ 0.2 * per-VM load makespan; population 30, 100 iterations, "
                        + "w=0.7, c1=c2=1.5, seeded named random stream");
                values.put("requiredScheduler", SchedulingAlgorithm.STATIC.name());
                values.put("verification", "PAPER_REPRODUCTION_REGRESSION_COVERED");
                values.put("reproductionScope", "CORE_PSO_DECISION_SEMANTICS_OF_PANDEY_AINA_2010_"
                        + "PER_OPENSOURCE_WORKFLOWSIM_PSO_REFERENCE");
                values.put("limitations", limitations(
                        "Fitness uses the reference implementation's sequential VM-load model and "
                                + "ignores DAG dependency edges; planning-side makespan estimates may differ "
                                + "from runtime makespan under engine-enforced dependencies.",
                        "The cost model is the abstract price = mips/1000 heuristic, not calibrated cloud pricing.",
                        "Random stream comes from the campaign root seed, not the reference's hardcoded seed 42."));
                break;
            case LOCAL_HEFT:
                values.put("decisionLayer", "STATIC_DAG_VM_MAPPING_AND_PER_VM_ORDER");
                values.put("inputDomain", "VALID_DAG_LOCAL_FILE_SYSTEM_NO_CLUSTERING");
                values.put("decisionRule", "HEFT upward rank with insertion-based earliest finish time; "
                        + "inter-task transfer is modeled per VM pair as bytes/(1e6 × min(bw_src, bw_dst)) "
                        + "with SOURCE-to-VM transfers bounded by destination bandwidth, replica-state "
                        + "evolution, and the paper AST semantics: transfers start when each parent "
                        + "finishes, may overlap VM busy time, and VMs are occupied by compute only");
                values.put("requiredScheduler", SchedulingAlgorithm.STATIC.name());
                values.put("verification", "PAPER_REPRODUCTION_REGRESSION_COVERED");
                values.put("reproductionScope", "CORE_HEFT_DECISION_SEMANTICS_OF_TOPCUOGLU_TPDS_2002_"
                        + "ADAPTED_TO_CONTROLLED_LOCAL_FILE_SYSTEM_MODEL");
                values.put("limitations", limitations(
                        "Requires LOCAL file system, NONE clustering, disabled overhead/failure, "
                                + "preExecutionTransferDelayV1 data movement model, and SPACE_SHARED VMs.",
                        "Communication is the controlled bandwidth model without link contention or network "
                                + "topology; every VM pair uses min(bw) regardless of physical path.",
                        "The model stage-in Job (110 MI) shifts the whole schedule by a constant bootstrap "
                                + "offset relative to paper schedules that assume zero-cost workflow entry.",
                        "Tasks shorter than the minimum event interval plus completion safety margin can drift "
                                + "from their planned completion under the runtime completion-event rule."));
                break;
            case LOCAL_CPOP:
                values.put("decisionLayer", "STATIC_DAG_VM_MAPPING_AND_PER_VM_ORDER");
                values.put("inputDomain", "VALID_DAG_LOCAL_FILE_SYSTEM_NO_CLUSTERING");
                values.put("decisionRule", "CPOP priority (upward rank + downward rank) with a ready "
                        + "queue; critical-path tasks are pinned to the VM minimizing total critical-path "
                        + "compute seconds, other tasks use insertion-based earliest finish time; the "
                        + "communication and pre-execution transfer delay model is identical to LOCAL_HEFT");
                values.put("requiredScheduler", SchedulingAlgorithm.STATIC.name());
                values.put("verification", "PAPER_REPRODUCTION_REGRESSION_COVERED");
                values.put("reproductionScope", "CORE_CPOP_DECISION_SEMANTICS_OF_TOPCUOGLU_TPDS_2002_"
                        + "ADAPTED_TO_CONTROLLED_LOCAL_FILE_SYSTEM_MODEL");
                values.put("limitations", limitations(
                        "Requires LOCAL file system, NONE clustering, disabled overhead/failure, "
                                + "preExecutionTransferDelayV1 data movement model, and SPACE_SHARED VMs.",
                        "Communication is the controlled bandwidth model without link contention or network "
                                + "topology; every VM pair uses min(bw) regardless of physical path.",
                        "The model stage-in Job (110 MI) shifts the whole schedule by a constant bootstrap "
                                + "offset relative to paper schedules that assume zero-cost workflow entry.",
                        "The paper overlaps transfers with busy processors; the envelope semantics serialize "
                                + "them, so individual EFT comparisons can flip relative to the paper schedule."));
                break;
            case INVALID:
            default:
                values.put("decisionLayer", "NONE");
                values.put("inputDomain", "NO_STATIC_VM_MAPPING");
                values.put("decisionRule", "No planning layer");
                values.put("verification", "NOT_APPLICABLE");
                values.put("limitations", limitations("Use an online scheduler or select a planner with STATIC dispatch."));
                break;
        }
        return values;
    }

    private static void independentPlanner(Map<String, Object> values, String rule) {
        values.put("decisionLayer", "STATIC_INDEPENDENT_TASK_VM_MAPPING");
        values.put("inputDomain", "INDEPENDENT_TASKS_ONLY");
        values.put("decisionRule", rule);
        values.put("requiredScheduler", SchedulingAlgorithm.STATIC.name());
        values.put("verification", "DETERMINISTIC_REGRESSION_COVERED");
        values.put("limitations", limitations("Fails fast when any parent or child dependency exists.",
                "Produces a VM mapping, not a complete offline execution trace or a communication model."));
    }

    private static Map<String, Object> base(String id) {
        Map<String, Object> values = new LinkedHashMap<String, Object>();
        values.put("id", id);
        return values;
    }

    private static List<String> limitations(String... values) {
        return new ArrayList<String>(Arrays.asList(values));
    }
}
