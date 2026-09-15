# 算法目录与主张边界

This catalog names the algorithms implemented in this workspace by their
actual decision scope. A selectable label is not, by itself, evidence of a
real-platform calibration, a trace replay, or general algorithm superiority.
Each run manifest embeds the equivalent machine-readable `algorithmContract`.

## Reproduction Standard

In this workspace, an algorithm is considered reproduced when its documented
core decision semantics are implemented and tested under its declared
WorkflowSim model. This means that the maintained HEFT/CPOP/DLS/ETF/PEFT implementations
preserve their rank, priority, allocation, deterministic tie-break, and
per-VM-order ideas after adapting them to the shared-storage model. It does
not mean that every assumption of the original paper's target environment,
network, storage system, or runtime implementation has been recreated.

## Online DAG Dispatch: P7 Primary Track

These maintained schedulers decide over Jobs that the Workflow Engine has
already released after dependency completion. They are the only algorithms in
the frozen 20-cell P7 primary matrix.

| Label | Implemented decision | Scope |
| --- | --- | --- |
| `FCFS` | First ready Job on the first idle VM | Valid DAG after engine dependency release |
| `READY_BATCH_ROUNDROBIN` | Retained VM cursor over an observed ready batch | Valid DAG after engine dependency release |
| `READY_BATCH_MCT` | Minimum estimated completion time over currently idle VMs | Valid DAG after engine dependency release |
| `READY_BATCH_MINMIN` | Iterative minimum of each ready Job's minimum completion time | Valid DAG after engine dependency release |
| `READY_BATCH_MAXMIN` | Iterative maximum of each ready Job's minimum completion time | Valid DAG after engine dependency release |

The ready batch is a runtime Job set, not an offline independent-task batch.
The legacy labels `MINMIN`, `MAXMIN`, `MCT`, and `ROUNDROBIN` have distinct
legacy semantics and are not presented as canonical versions of the named
classical heuristics.

## Offline Independent-Task Mapping

The following planners are maintained deterministic implementations of common
independent-task baselines: `STATIC_OLB`, `STATIC_MET`, `STATIC_MCT`,
`STATIC_MINMIN`, `STATIC_MAXMIN`, `STATIC_SUFFERAGE`, and
`STATIC_ROUND_ROBIN`. They require `SchedulingAlgorithm.STATIC`, map Tasks to
VMs before execution, and fail fast if any Task has a parent or child. They do
not produce a network model, a communication schedule, or a complete offline
trace.

Their standard naming follows the independent-task mapping taxonomy described
by Maheswaran et al., *Dynamic Mapping of a Class of Independent Tasks onto
Heterogeneous Computing Systems*, JPDC 59(2), 1999,
[DOI 10.1006/jpdc.1999.1581](https://doi.org/10.1006/jpdc.1999.1581).

## Controlled Static DAG Mapping

`SHARED_STORAGE_HEFT`, `SHARED_STORAGE_CPOP`, `SHARED_STORAGE_DLS`,
`SHARED_STORAGE_ETF`, and `SHARED_STORAGE_PEFT` are maintained static DAG
planners. All require `STATIC` dispatch, `SHARED` storage, no clustering, no
overhead, no failures, and `SPACE_SHARED` VMs. They set both Task-to-VM mapping
and a complete per-VM Job order; the static dispatcher enforces that order.

| Label | Prioritization and allocation | Controlled model alignment |
| --- | --- | --- |
| `SHARED_STORAGE_HEFT` | HEFT upward rank, then insertion-based earliest finish time | Adds each Task's real shared-storage input delay to every candidate VM, exactly as the current compute-Job data-stage-in model does |
| `SHARED_STORAGE_CPOP` | CPOP `r_u + r_d` priority; a deterministically identified critical path is forced to the lowest-total-cost compatible processor | Uses the same execution-duration and stage-in assumptions as the maintained HEFT implementation |
| `SHARED_STORAGE_DLS` | DLS dynamic level: for every dependency-ready Task-VM pair, upward-rank static b-level minus insertion-based earliest start; select the maximum | Uses the same execution-duration, stage-in, and reservation assumptions; records the dynamic level and selection order for every Task |
| `SHARED_STORAGE_ETF` | ETF: choose the dependency-ready Task-VM pair with the minimum insertion-based earliest start; static b-level breaks equal starts | Uses the same execution-duration, stage-in, and reservation assumptions; records the selected earliest start and selection order for every Task |
| `SHARED_STORAGE_PEFT` | PEFT: select the dependency-ready Task with maximum compatible-VM average optimistic-cost rank; map it to the VM minimizing insertion `EFT + OCT` | Uses successor look-ahead with zero interprocessor communication term; records rank, selected OCT, objective, and selection order for every Task |

For all maintained static DAG planners, the model-generated 110 MI stage-in Job is not a Task and
is executed first through the static dispatcher on the lowest VM ID. The
planner accounts for the same execution rule as `WorkflowDatacenter`: its
completion is no earlier than `max(110 / lowestVmMips, minInterval + 0.01)`,
then the Workflow Engine releases root Jobs one further `minInterval` later.
Shared-storage input delay is `fileSize / 10^6 / storageRate` seconds and is
converted to MI using the candidate VM MIPS, matching the current
`WorkflowDatacenter` abstraction.

HEFT and CPOP are based on Topcuoglu, Hariri, and Wu, *Performance-Effective
and Low-Complexity Task Scheduling for Heterogeneous Computing*, IEEE TPDS
13(3), 2002, [DOI 10.1109/71.993206](https://doi.org/10.1109/71.993206).
The implementation is a controlled adaptation to this simulator's model; it
does not claim to reproduce every communication assumption in that paper.

DLS is based on Sih and Lee, *A Compile-Time Scheduling Heuristic for
Interconnection-Constrained Heterogeneous Processor Architectures*, IEEE TPDS
4(2), 1993, [DOI 10.1109/71.207593](https://doi.org/10.1109/71.207593). The
original method includes processor interconnection and communication-resource
modeling. `SHARED_STORAGE_DLS` deliberately reproduces only the dynamic-level
Task-VM selection core in this simulator's controlled shared-storage model; it
does not claim topology, routing, or link-contention semantics.

ETF is based on Hwang, Chow, Anger, and Lee, *Scheduling Precedence Graphs in
Systems with Interprocessor Communication Times*, SIAM Journal on Computing
18(2), 1989, [DOI 10.1137/0218016](https://doi.org/10.1137/0218016). The
original analysis assumes identical processors with interprocessor
communication delays. `SHARED_STORAGE_ETF` retains only its earliest-start
Task-VM selection rule and static-b-level tie-break under this simulator's
controlled shared-storage model.

PEFT is based on Arabnejad and Barbosa, *List Scheduling Algorithm for
Heterogeneous Systems by an Optimistic Cost Table*, IEEE TPDS 25(3), 2014,
[DOI 10.1109/TPDS.2013.57](https://doi.org/10.1109/TPDS.2013.57). The original
OCT formulation includes processor-to-processor communication costs.
`SHARED_STORAGE_PEFT` retains optimistic successor look-ahead, average OCT
priority, and `EFT + OCT` processor choice, but sets that communication term to
zero because the controlled shared-storage model has no topology, route, or
shared-link model. It must not be presented as a network-aware PEFT result.

## Metaheuristic Static DAG Mapping (Paper Reproduction)

`PSO` is a particle-swarm-optimization Task-to-VM mapper reproducing Pandey,
Wu, Guru, and Buyya, *A Particle Swarm Optimization-Based Heuristic for
Scheduling Workflow Applications in Cloud Computing Environments*, AINA 2010,
following the open-source WorkflowSim reference implementation
`meysamhit/workflowsim-pso`. It requires `STATIC` dispatch and produces only a
Task-to-VM mapping (no per-VM order; runtime dependencies remain
engine-enforced). Constants are faithful to the reference: population 30, 100
iterations, inertia 0.7, c1 = c2 = 1.5, fitness = 0.8 · cost + 0.2 · makespan
with `price = mips / 1000`.

Known reproduction limits (declared in the manifest contract):

- The fitness uses the reference's sequential VM-load model and **ignores DAG
  dependency edges**; planning-side makespan estimates may differ from runtime
  makespan.
- With `price = mips / 1000`, the cost term equals total MI / 1000 for every
  mapping — it is **mapping-invariant**, so the paper's cost-reduction claim is
  not reproducible under this model (the original paper uses real EC2 pricing
  that is nonlinear in capacity). PSO here effectively optimizes makespan only.
- Random draws come from the campaign root seed via a named
  `SimulationRandom` stream, not the reference's hardcoded seed 42.
- The reference experiment pairs PSO planning with ROUNDROBIN scheduling, which
  would silently override the plan; this platform's `SimulationConfig` forces
  `STATIC` dispatch for every planner and rejects that combination.

## Communication-Aware Static DAG Planners (Paper Reproduction)

`LOCAL_HEFT` and `LOCAL_CPOP` reproduce the two list-scheduling algorithms of
Topcuoglu, Hariri, and Wu, *Performance-Effective and Low-Complexity Task
Scheduling for Heterogeneous Computing*, IEEE TPDS 13(4), 2002, adapted to the
controlled LOCAL-file-system execution model. They require `STATIC` dispatch,
the LOCAL file system, NONE clustering, disabled overhead/failure models,
`DataMovementModel.preExecutionTransferDelayV1()`, and SPACE_SHARED VMs
(`SimulationConfig` rejects any other combination). Unlike the
`SHARED_STORAGE_*` family, these planners model inter-task communication with
the paper's AST semantics: each real input file transfers at
`bytes / (1e6 × rate)` with SOURCE→VM bounded by destination bandwidth, VM→VM
bounded by `min(bw_src, bw_dst)`, and zero cost when a replica already exists
on the destination VM. Transfers are pre-execution network delays, not VM work:
per-parent transfers start when the parent finishes (parallel across parents),
may overlap VM busy time, and a job becomes dispatchable at
`arrival = max_pred(parentFinish + Σ c_files_from_pred)`. VMs are occupied by
compute only; planned start = `max(VM free, arrival)`, matching the runtime
pre-execution hold (`JOB_STAGE_IN_COMPLETE` release) exactly. Replica state
evolves in scheduling order — mirroring the runtime LOCAL stage-in rules.

**Link-contention variant (R2)**: both planners also accept
`DataMovementModel.preExecutionTransferDelayWithContentionV1()`. Planning still
uses the contention-free AST estimates above; at runtime, concurrent transfers
fair-share each VM endpoint's bandwidth (`vm.getBw()` capacity, `capacity/n`
per active transfer, fluid model in `TransferContentionEngine`). Under
concurrent load the runtime therefore diverges from the plan in a documented,
explainable way (contention can only delay transfers). Measured on the HEFT
paper fixture: contention makespan 284.1 vs no-contention golden 190.1.

- `LOCAL_HEFT`: upward rank `r_u = w̄ + max_child(c̄ + r_u(child))` descending
  priority (ties → lower task id), insertion-based earliest-finish-time VM
  choice (ties → lower VM id).
- `LOCAL_CPOP`: priority `r_u + r_d` (r_d = `max_child(c̄ + r_d(child))`, zero
  at the exit), ready-queue selection, and critical-path tasks pinned to the
  VM minimizing total critical-path compute seconds (ties → lower VM id);
  other tasks use the same insertion-based EFT rule as HEFT.

Both were regression-tested end-to-end against the paper's canonical example
(`datasets/dax/heft/heft-paper-example.dax`, 3 VMs × mips 1.0 × 1 MB/s so
compute and transfer seconds equal the paper's table values digit-for-digit):
HEFT ranks reproduce the paper's rank table exactly; HEFT mapping matches
**10/10** and every task interval equals the paper interval plus the 0.1
bootstrap margin (relative makespan 80.1 ≈ paper 80); CPOP reproduces the
paper's critical path {n1, n3, n7, n10} and critical-path processor (vm1),
CPOP mapping matches 8/10 with relative makespan 87.1 ≈ paper 86; and
planning-time per-task VM/start/finish values reproduce runtime outcomes
bit-for-bit.

Known reproduction limits (declared in the manifest contract):

- The model stage-in Job (110 MI) adds a constant bootstrap offset to the
  whole schedule; comparisons with paper schedules subtract it.
- The controlled bandwidth model has no link contention or network topology;
  every VM pair uses `min(bw)` regardless of physical path.
- Transfer delays below the simulation's minimum event interval are clamped
  at runtime (the planner does not clamp); sub-second transfers can therefore
  drift slightly from their planned arrival.
- Tasks shorter than the minimum event interval plus the completion safety
  margin can drift from their planned completion under the runtime
  completion-event rule.
- The LOCAL planners model files in a flat global filename namespace and
  reject inputs where the same filename carries conflicting size declarations
  (`AbstractLocalCommPlanningAlgorithm` size guard). Stock Pegasus Montage DAX
  files rely on per-job directories for same-named outputs (e.g. each
  mDiffFit job emits its own `fit.txt`), so they collide in the flat namespace
  and are rejected by design; this boundary was surfaced by the 1000-task
  scale regression (`LargeWorkflowScaleRegressionTest` uses the
  collision-free Epigenomics_997 for the LOCAL track).

## Provisional Legacy DAG Planners

`HEFT` and `DHEFT` remain directly selectable legacy code, but their
planning-side parent/child transfer estimate is not aligned with the current
shared-storage compute-stage-in execution path. `SimulationRunner` rejects
both labels, and their manifest contract marks them
`LEGACY_COMPATIBILITY_ONLY_NOT_SUPPORTED_BY_SIMULATION_RUNNER`. Do not compare
them with the controlled static DAG algorithms for data locality, bandwidth,
network, or real-platform claims.

## Comparison Rules

Do not pool results across the three catalog sections as though they were the
same algorithm class. A valid comparison holds fixed the workflow input hash,
platform profile, storage/overhead/failure/clustering configuration, execution
layer, and metric scope. The P7 primary online-dispatch matrix remains its own
track. Static independent-task and controlled static-DAG tracks require
separate matrices and separate result statements.
