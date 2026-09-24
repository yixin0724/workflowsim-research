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

## RL Environment Track (R4)

`RL_POLICY` is a policy-driven online scheduler over the same ready-Job domain:
each scheduling update asks a registered `RlPolicy` for a VM index per ready Job.
Episodes run through `RlEnvironment.runEpisode` (observation = ready-queue + VM
load views; action = Job→VM index; reward = −makespan). The deterministic
greedy baseline `EarliestFinishGreedyPolicy` pins environment behavior
(episode golden makespan 5116.1 on the HEFT paper fixture, online track); the
decision trace equals the report's final VM assignments Job-for-Job. Running
`RL_POLICY` without the environment facade fails explicitly. The track provides
the state/action/reward contract and deterministic episodes, not a learning
algorithm: external trainers need out-of-process bridging because the platform
is a single-process Java event loop.

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
shared-link model. It must not be presented as a network-aware PEFT result; the
communication-aware variant is `LOCAL_PEFT` in the LOCAL track (below).

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
Scheduling for Heterogeneous Computing*, IEEE TPDS 13(4), 2002, and
`LOCAL_PEFT` reproduces the optimistic-cost-table list scheduler of Arabnejad
and Barbosa, *List Scheduling Algorithm for Heterogeneous Systems by an
Optimistic Cost Table*, IEEE TPDS 25(3), 2014,
[DOI 10.1109/TPDS.2013.57](https://doi.org/10.1109/TPDS.2013.57), all adapted
to the controlled LOCAL-file-system execution model. They require `STATIC` dispatch,
the LOCAL file system, NONE clustering, disabled overhead/failure models,
a preExecution-family data movement model (`preExecutionTransferDelayV1()`,
`preExecutionTransferDelayWithContentionV1()`, or `fatTreeContentionV1()`),
and SPACE_SHARED VMs
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
fair-share each VM endpoint using max-min progressive filling with nominal-rate
caps and residual-capacity redistribution. R10 integrates across intermediate flow
completions before reallocating bandwidth. Unlike V1's parent-finish arrival estimate,
contention groups all start when the Job becomes ready. Therefore V1/R2 differences
mix start-policy and contention effects; the R10 study compares endpoint/Fat-tree
variants with the same start policy. Planning remains contention-free. On the HEFT
paper fixture, contention makespan is 284.1 versus the no-contention 190.1.

**Fat-tree contention variant (R6)**: both planners also accept
`DataMovementModel.fatTreeContentionV1()`. Planning again uses the
contention-free AST estimates; at runtime the contention domain extends from VM
endpoints to every shared link on a deterministic Al-Fares k-Pod fat-tree route
(`a = srcEdge mod availA` uplink choice; cross-pod core
`j = (srcEdge + dstEdge + srcPod + dstPod) mod jCount`), declared via
`PlatformProfile.Builder.networkTopology(NetworkTopologySpec.fatTree(k,
linkMbPerSecond[, coreSwitchCount][, hostEdgePlacements]))`; max-min progressive
filling jointly respects endpoint, link and nominal capacities, redistributing unused
shares. Additional constraints can accelerate some other flows through redistribution;
DAG makespan monotonicity across models is not a general theorem. The runner enforces a bidirectional contract: the
model requires a topology declaration and a declared topology requires the
model. Measured on the HEFT paper fixture (k=4 fully provisioned, hosts
round-robin placed): with symmetric provisioning (1 MB/s links == VM endpoint
bandwidth) the link layer never binds and fat-tree makespan 284.1 is
bit-identical to the R2 endpoint golden (> no-contention 190.1); with binding
links (0.25 MB/s slow-link probe) fat-tree makespan 588.1 > 284.1 proves the
link-contention model engages when links constrain. (R8 audit 2026-09-16:
the pre-fix golden 1032.1 was produced by a ÷8 unit bug that ran declared
links at 1/8 bandwidth. The scheduling campaign apparatus was recalibrated
in R8 to binding provisioning — baseline links 0.125 MB/s = endpoint/8, A4
axis 1.25 MB/s > endpoint — under which fat-tree contention measurably adds
to R2, e.g. paper example HEFT 5195.1 → 5738.1; the symmetric identity above
is the simulator fixture's boundary case.) Honest boundaries: flow-level fluid
model (no loss/queueing/ECN), deterministic shortest-path routing (no adaptive
routing), uniform link bandwidth, external SOURCE flows bypass the topology.
See `docs/research/FAT_TREE_PRINCIPLES.md` and `docs/research/FAT_TREE_DESIGN.md`.

- `LOCAL_HEFT`: upward rank `r_u = w̄ + max_child(c̄ + r_u(child))` descending
  priority (ties → lower task id), insertion-based earliest-finish-time VM
  choice (ties → lower VM id).
- `LOCAL_CPOP`: priority `r_u + r_d`, with entry `r_d=0` and
  `r_d(t)=max_parent(r_d(parent)+meanCompute(parent)+meanCommunication(parent,t))`.
  A critical path follows only edges satisfying the upward-rank recurrence and
  constant critical priority (ties → lower task ID); its tasks are pinned to the
  VM minimizing total critical-path compute seconds. Other tasks use insertion EFT.
- `LOCAL_PEFT`: optimistic cost table
  `OCT(t,p) = w(t,p) + max_child[ min_p'( OCT(child,p') + c(t,child,p,p') ) ]`
  with the paper's exit condition `OCT(t_exit,p) = w̄_exit` (the exit task's mean
  compute cost, uniform over VMs); priority is the mean OCT over all VMs
  (descending, ties → lower task id); VM choice minimizes `EFT + OCT(t,p)`
  (ties → lower VM id) via the same insertion-based search as LOCAL_HEFT.
  Because the OCT exit value shifts every entry of a single-exit DAG by the same
  constant `w̄_exit`, the alternative `OCT(exit,p)=0` convention yields identical
  priorities and schedules; only the published OCT/rank_o table distinguishes
  them, and the tests assert that table under the paper convention. Mean-OCT
  priority is not provably topological under extreme compute-cost spreads; a
  child outranking its parent fails fast with `IllegalStateException` (base
  `readyTime` guard) rather than producing an inconsistent schedule.

All three are regression-tested on the canonical ten-task fixture. HEFT mapping matches
10/10 and its absolute makespan is 190.1. R10 corrects the former LOCAL_CPOP
child-directed rank error: the critical path is {n1,n2,n9,n10}, the critical VM is
vm1, and the absolute makespan is 196.1. PEFT reproduces the paper's published
OCT and rank_o tables exactly (rank_o 61, 48, 44, 43, 40, 37.33, 31.33, 25.67,
24.67, 14.67), its selection order {n1,n2,n4,n5,n3,n6,n9,n7,n8,n10}, its mapping
10/10, and its absolute makespan 186.1 — 76 after the bootstrap, below HEFT's 80
as the paper reports. Subtracting the common 110.1 bootstrap
leaves 80, 86 and 76 respectively. Tests independently assert the complete rank/OCT tables,
path, mapping, task intervals, multiple critical paths and cross-branch shortcuts.
Earlier 197.1 / {n1,n3,n7,n10} statements describe the erroneous pre-R10 implementation.

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

## Legacy DAG Planners (Removed in R9)

The deprecated `HEFT` and `DHEFT` labels were removed in R9 (2026-09-16),
together with their `HEFTPlanningAlgorithm` / `DHEFTPlanningAlgorithm`
implementations, their `PlanningAlgorithm` enum entries, and their dispatch
branches in `WorkflowPlanner` and `AlgorithmCatalog`. Before removal they were
already rejected by `SimulationRunner` and flagged
`LEGACY_COMPATIBILITY_ONLY_NOT_SUPPORTED_BY_SIMULATION_RUNNER`: their
planning-side parent/child transfer estimate was never aligned with the current
shared-storage compute-stage-in execution path. They remain unusable for data
locality, bandwidth, network, or real-platform claims. Use the maintained static
DAG planners instead: `SHARED_STORAGE_HEFT`, `SHARED_STORAGE_CPOP`,
`SHARED_STORAGE_DLS`, `SHARED_STORAGE_ETF`, `SHARED_STORAGE_PEFT`, or the
communication-aware `LOCAL_HEFT` / `LOCAL_CPOP` / `LOCAL_PEFT`.

## Comparison Rules

Do not pool results across the three catalog sections as though they were the
same algorithm class. A valid comparison holds fixed the workflow input hash,
platform profile, storage/overhead/failure/clustering configuration, execution
layer, and metric scope. The P7 primary online-dispatch matrix remains its own
track. Static independent-task and controlled static-DAG tracks require
separate matrices and separate result statements.
