# Algorithm Catalog and Selection

This page lists the labels and combinations actually supported by the standard runner. The
authoritative support set is
[AlgorithmCatalog](<../../simulator/src/main/java/org/workflowsim/experiment/AlgorithmCatalog.java>)'s
`isSupportedBySimulationRunner(...)`;
[SimulationConfig](<../../simulator/src/main/java/org/workflowsim/utils/SimulationConfig.java>)
and the [runner](<../../simulator/src/main/java/org/workflowsim/experiment/SimulationRunner.java>)
continue to check model, input, and platform prerequisites.

"Has an implementation", "the label is accepted", and "any combination is legal" are three
different concepts. An algorithm name is not evidence of real-platform calibration, of
reproducing all of a paper's environmental assumptions, or of general superiority. Formulas,
execution sequencing, and metric conventions are specified in the
[semantic contracts](<CONTRACTS.md>); how to run them is covered by
[Workbench](<../getting-started/WORKBENCH.md>) and the
[Java API guide](<../getting-started/CODE_CONFIG_EXPERIMENTS.md>).

## Choose the decision layer first

| Question to answer | Decision layer | Output and boundary |
| --- | --- | --- |
| Which idle VM receives the currently ready Job | ordinary online scheduling | one dispatch at a time, no reservation of future machine availability |
| How an external policy controls the same ready domain | RL environment | Job→VM list-index actions; the platform trains no policy |
| How dependency-free tasks share the compute load | static independent-task mapping | Task→VM; any dependency edge is rejected |
| A DAG baseline that only needs a precomputed mapping | RANDOM / PSO | Task→VM, without a full per-VM order |
| How the DAG is ordered and placed into VM gaps | SHARED / LOCAL static planning | mapping plus per-VM order; plan times are estimates, not event replays |
| Select a target at control-ready, then prepare inputs | coherent online assignment | independent assignment policy + STATIC dispatch of already-bound Jobs; not to be conflated with ordinary CPU online scheduling |

## Current scheduler labels

The table below is the complete set of labels accepted at the standard entry point of
`Parameters.SchedulingAlgorithm`; specific combinations remain constrained below.

| Label | Actual decision |
| --- | --- |
| `FCFS` | ready-arrival order; picks the idle compatible VM with the smallest VM ID |
| `READY_BATCH_MINMIN` | every unassigned Job first computes its minimum ECT, then the global minimum is chosen; recomputed after each assignment |
| `READY_BATCH_MAXMIN` | every unassigned Job first computes its minimum ECT, then the maximum of those is chosen; recomputed after each assignment |
| `READY_BATCH_MCT` | ready-arrival order; among currently idle compatible VMs picks the smallest `length/MIPS` |
| `READY_BATCH_ROUNDROBIN` | a persistent cursor ordered by VM ID rotates, skipping busy or incompatible VMs |
| `DATA` | chooses the idle compatible VM with the fewest non-local real input bytes; requires LOCAL |
| `RL_POLICY` | the policy registered via `RlEnvironment.runEpisode(...)` selects the VM list index |
| `STATIC` | executes an existing mapping/binding; with a full static plan it additionally enforces the per-VM order — it is not an independent optimization algorithm |

The ready-batch ECT comparison quantity is `length/MIPS`; it excludes future availability,
input transfer, or contention. For fixed positive lengths and identical candidate sets, the
minimum ECT is equivalent to the maximum MIPS; different labels alone do not imply different
mappings. `DATA` compares bytes, not transfer seconds.

Ordinary online policies and RL dispatch at most one Job per VM per cycle; remaining Jobs
wait for later updates. `RL_POLICY` cannot be an ordinary Workbench JSON candidate; it must
go through [RlEnvironment](<../../simulator/src/main/java/org/workflowsim/rl/RlEnvironment.java>),
which provides the environment, trajectories, and rewards but no DQN/PPO-style learner.

## Current planner labels

The table lists all supported values of `Parameters.PlanningAlgorithm`. Except for
`INVALID`, all require `SchedulingAlgorithm.STATIC`.

| Label | Family | Decision summary |
| --- | --- | --- |
| `INVALID` | no planning | ordinary online scheduling or explicit coherent online assignment; not an optimization algorithm |
| `RANDOM` | DAG mapping only | samples among compatible VMs with a named random stream; optimizes neither cost nor per-VM order |
| `PSO` | DAG mapping only | optimizes an abstract cost and VM-sequential load objective, ignoring DAG edges and network timing |
| `STATIC_OLB` | independent tasks | minimum tentative availability, updating load with effective compute seconds |
| `STATIC_MET` | independent tasks | minimum effective compute seconds, availability not considered |
| `STATIC_MCT` | independent tasks | minimum `availability + effectiveSeconds` |
| `STATIC_MINMIN` | independent tasks | each round selects the smallest of each task's minimum tentative completion times |
| `STATIC_MAXMIN` | independent tasks | each round selects the largest of each task's minimum tentative completion times |
| `STATIC_SUFFERAGE` | independent tasks | maximum "second-best completion time − best completion time" loss; the loss is 0 with a single candidate |
| `STATIC_ROUND_ROBIN` | independent tasks | rotates compatible VMs by Task ID; the choice rule ignores cost |
| `SHARED_STORAGE_HEFT` | SHARED DAG | upward-rank priority with insertion-based earliest-finish assignment |
| `SHARED_STORAGE_CPOP` | SHARED DAG | `r_u + r_d` priority; one critical path pinned to the best all-path-compatible VM |
| `SHARED_STORAGE_DLS` | SHARED DAG | picks the largest `b-level − earliest insertion start` among dependency-ready Task-VM pairs |
| `SHARED_STORAGE_ETF` | SHARED DAG | earliest insertion start first; equal starts tie-break with b-level and further rules |
| `SHARED_STORAGE_PEFT` | SHARED DAG | ready task with the largest average OCT, VM with the smallest `EFT + OCT`; the communication term is 0 |
| `LOCAL_HEFT` | LOCAL DAG | upward rank including average communication, insertion-based EFT |
| `LOCAL_CPOP` | LOCAL DAG | predecessor-direction downward rank together with upward rank; the critical path is pinned, other tasks are inserted |
| `LOCAL_PEFT` | LOCAL DAG | successor-cost OCT, zero at exits, ready-list selection, and insertion-based `EFT + OCT` |

## Combination rules

The standard runner uniformly requires **NONE clustering, SPACE_SHARED VMs, and
PE-compatible placement**. Each VM dispatches at most one Job at a time; multiple PEs do not
mean multiple Jobs can be dispatched simultaneously.

| Track | planner / scheduler | file system and data movement | task-cost matrix |
| --- | --- | --- | --- |
| Ordinary online and RL | INVALID / matching online label | legacy or fixed-endpoint no-contention; DATA only with LOCAL; RL additionally needs an environment-registered policy | rejected |
| Independent-task mapping | STATIC independent-task planner / STATIC | inputs must have no edges; a V1 model satisfying its own prerequisites | usable |
| DAG mapping only | RANDOM or PSO / STATIC | a V1 model with satisfied prerequisites; see the coherent rows for coherent combinations | usable, but RANDOM does not optimize on it |
| SHARED DAG | SHARED_STORAGE family / STATIC | SHARED + `legacyWorkflowsimV1()`; no failure, no overhead | rejected |
| LOCAL DAG | LOCAL family / STATIC | LOCAL + the V1 pre-execution family; no failure, no overhead | usable |
| coherent pre-mapping | RANDOM / STATIC | V2 requires LOCAL; V3 allows LOCAL/SHARED; no overhead, optional Fat-tree, V3 must declare SOURCE storage | usable |
| coherent online assignment | INVALID / STATIC with assignment explicitly enabled | same physical prerequisites as the row above; bind at control-ready, then prepare inputs | usable |

The V1 pre-execution family is `preExecutionTransferDelayV1()`,
`preExecutionTransferDelayWithContentionV1()`, and `fatTreeContentionV1()`. The last
requires LOCAL, no failure/overhead, and a declared platform topology; LOCAL planners still
use no-contention estimates under these run models. Topology and routing are described in
the [Fat-tree model](<../advanced/FAT_TREE_DESIGN.md>).

Coherent online assignment uses `CONTROL_READY_ONLINE_ASSIGNMENT_V1` with the policy
`NOMINAL_INPUT_EARLIEST_RESERVATION_V1`. It is an **explicit exception** to STATIC normally
requiring a non-INVALID planner — not a waiver granted to arbitrary STATIC configurations.
It cannot be combined with RANDOM pre-planning; a NOOP retry reuses the logical binding.
Workbench requires matching full-lifecycle capture; Java OFF runs provide no online-action
export attestation. Details are in the
[online assignment contract](<../advanced/ONLINE_DATAFLOW_ASSIGNMENT_V1_CONTRACT.md>) and
the [capability matrix](<../advanced/DATAFLOW_CAPABILITY_MATRIX.md>).

Matrix availability does not mean every planner optimizes on the matrix: time-aware
independent planners, PSO, and LOCAL planning and execution share effective integer MI;
RANDOM and STATIC_ROUND_ROBIN keep their respective cost-oblivious choices. Failures use the
standard NOOP retry contract only in combinations that allow them; Workbench exposes no
failure or extra-overhead configuration.

## Registered external algorithms (extension surface, 2026-10-26)

The standard runner additionally accepts algorithms provided by third-party code through the
Java ServiceLoader SPI, with **zero modifications to the repository's core tree**:
implement [SchedulingAlgorithmProvider](<../../simulator/src/main/java/org/workflowsim/experiment/registry/SchedulingAlgorithmProvider.java>),
declare the service file, and reference the provider's label in the configuration. The
self-declared [AlgorithmDeclaration](<../../simulator/src/main/java/org/workflowsim/experiment/registry/AlgorithmDeclaration.java>)
— file-system scope, accepted data-movement kinds, task-cost-matrix and failure-model
acceptance — is consumed by the runner's fail-fast combination matrix via
[AlgorithmRegistry.validate](<../../simulator/src/main/java/org/workflowsim/experiment/registry/AlgorithmRegistry.java>);
[the acceptance exercise](<../../experiments/src/test/java/org/workflowsim/experiments/registry/GradStudentSchedulerExerciseIntegrationTest.java>)
takes a novel bandwidth-first scheduler from SPI discovery to a validated v4 evidence bundle.

Scope and boundaries:

- Registered algorithms occupy the **plain online scheduling layer** only: both
  `schedulingAlgorithm` and `planningAlgorithm` must stay `INVALID`; the label is recorded in
  the manifest as `registeredSchedulingAlgorithm` next to the enum, and the evidence remains
  contract-verifiable.
- A registered label names exactly the declared class and no more: it carries **no literature
  anchoring, no clustering/provenance contract, and no real-platform calibration claim**.
- Incompatible declarations fail fast with the violated contract clause; a label colliding with
  a built-in enum name is rejected at discovery rather than shadowed.

## Legacy label migration

| Legacy label | Current status and retained behavior | Maintenance entry |
| --- | --- | --- |
| `MINMIN` | enum retained but rejected by the standard runner; the [SPT-fastest-idle implementation](<../../simulator/src/main/java/org/workflowsim/scheduling/SptFastestIdleSchedulingAlgorithm.java>) picks the shorter Job by raw length first | online: `READY_BATCH_MINMIN`; independent global mapping: `STATIC_MINMIN` |
| `MAXMIN` | enum retained but rejected by the standard runner; the [LJF-fastest-idle implementation](<../../simulator/src/main/java/org/workflowsim/scheduling/LjfFastestIdleSchedulingAlgorithm.java>) picks the longer Job first | online: `READY_BATCH_MAXMIN`; independent-task mapping: `STATIC_MAXMIN` |
| `MCT` | enum retained but rejected by the standard runner; the [fastest-idle-VM implementation](<../../simulator/src/main/java/org/workflowsim/scheduling/FastestVmSchedulingAlgorithm.java>) maintains no offline availability | online: `READY_BATCH_MCT`; independent-task mapping: `STATIC_MCT` |
| `ROUNDROBIN` | enum retained but rejected by the standard runner; the [first-fit-idle implementation](<../../simulator/src/main/java/org/workflowsim/scheduling/FirstFitIdleSchedulingAlgorithm.java>) is not a persistent-cursor rotation | online: `READY_BATCH_ROUNDROBIN`; independent-task mapping: `STATIC_ROUND_ROBIN` |
| `HEFT` / `DHEFT` | not in the current planning enum; legacy code does not compile unchanged | choose the SHARED_STORAGE or LOCAL family by model — not an unconditional synonym |
| `SchedulingAlgorithm.INVALID` | not runnable | choose an explicit online label or a legal STATIC combination |

`SPT`, `LJF`, `FASTEST_VM`, and `FIRST_FIT_IDLE` are not current scheduling enum names;
retained implementation classes must not be treated as new runnable labels on that basis.
The rejection behavior of legacy labels is covered by the
[standard entry-point test](<../../simulator/src/test/java/org/workflowsim/experiment/SimulationRunnerAlgorithmContractTest.java>).
Migration must declare the decision layer and model; do not relabel old results as
evidence about another algorithm.

## What to hold fixed when comparing results

Hold fixed the inputs and hashes, platform and VM→Host placement, file system, data
movement, cost matrix, overhead/failure, arrival table, seed design, execution semantics,
and metric sample scope. Full static DAG planning enforces the per-VM order, while
RANDOM/PSO map only and let STATIC dispatch ready Jobs afterwards; the comparison is between
complete policy pipelines, not merely VM-mapping quality.

Transfer contention can make an ordered plan exhibit head-of-line waiting even when VMs are
idle; adding link constraints does not guarantee monotonic changes in total completion time
for arbitrary DAGs. Repeating a deterministic algorithm across seeds does not produce
independent workflow samples. Collections from different sources must not be merged into one
population; see the [dataset notes](<../../datasets/README.md>).
