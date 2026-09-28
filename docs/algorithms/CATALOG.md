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

## Common Runner and Cost Boundaries

The standard `SimulationRunner` requires `NONE` clustering and `SPACE_SHARED`
VMs for every algorithm. A non-`INVALID` planner requires `STATIC` dispatch;
`STATIC` also requires a planner. Candidate placements must meet the Task's PE
requirement. The scheduler admits at most one Job per VM at a time even when a
VM has multiple PEs; PE compatibility is not a promise of concurrent Jobs.

For time-aware independent planners, PSO, and LOCAL planners, compute estimates
share `TaskExecutionModel` with execution: without a cost matrix, seconds are
raw per-PE Task length / VM MIPS; with a matrix, they are
`round(matrixSeconds(task, vm) * vmMips) / vmMips`. A present matrix is
authoritative: missing coordinates, non-positive rounded work, or work outside
the signed-long instruction range fail explicitly. Raw source Task length is
not rewritten. These are compute estimates, not full Job-envelope or network
predictions. The shared-storage DAG family deliberately rejects cost matrices.

A complete static plan enforces **per-VM Job order**, not absolute planned
start times. Its timings remain model estimates; runtime event ordering and
completion rules still matter. Regression labels in the manifest identify
maintained evidence, not a claim that every configuration was just tested.

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
ECT is raw Job length / VM MIPS over the currently idle, PE-compatible
candidates; it excludes transfer time and future availability. With fixed
positive length and the same candidates, MCT's minimum ECT is equivalent to
maximum MIPS. New and legacy labels can therefore coincide on some inputs;
different names do not prove different outcomes. The legacy labels `MINMIN`,
`MAXMIN`, `MCT`, and `ROUNDROBIN` are rejected by the standard runner rather
than presented as classical offline heuristics.

`DATA` is an additional maintained online baseline, outside the P7 five-policy
matrix. It requires `LOCAL` storage and minimizes nonlocal real-input bytes
among idle compatible VMs, breaking ties by VM ID. It does not estimate
transfer time, endpoint latency, or contention.

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

Time-aware strategies consume the effective compute costs described above.
OLB selects by availability but updates that availability with the selected
Task's effective cost; MET ignores availability; MCT, Min-Min, Max-Min and
Sufferage use completion estimates. With only one compatible VM, Sufferage's
alternative-loss score is defined as zero. `STATIC_ROUND_ROBIN` intentionally
ignores costs and loads. None of these planners promises an offline network or
runtime queue schedule.

Their standard naming follows the independent-task mapping taxonomy described
by Maheswaran et al., *Dynamic Mapping of a Class of Independent Tasks onto
Heterogeneous Computing Systems*, JPDC 59(2), 1999,
[DOI 10.1006/jpdc.1999.1581](https://doi.org/10.1006/jpdc.1999.1581).

## Controlled Static DAG Mapping

`SHARED_STORAGE_HEFT`, `SHARED_STORAGE_CPOP`, `SHARED_STORAGE_DLS`,
`SHARED_STORAGE_ETF`, and `SHARED_STORAGE_PEFT` are maintained static DAG
planners. All require `STATIC` dispatch, `SHARED` storage, no clustering, no
overhead, no failures, and `SPACE_SHARED` VMs. This family additionally requires
`legacyWorkflowsimV1()` and rejects task cost matrices. It sets both Task-to-VM
mapping and a complete per-VM Job order; the static dispatcher enforces that
order, not absolute planned timestamps.

| Label | Prioritization and allocation | Controlled model alignment |
| --- | --- | --- |
| `SHARED_STORAGE_HEFT` | HEFT upward rank, then insertion-based earliest finish time | Estimates each Task's shared-storage input delay with the same summed-delay-to-MI rule; runtime event timing is not replayed |
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

## Seeded Mapping-Only DAG Baseline

`RANDOM` requires `STATIC`, draws from each Task's PE-compatible VM set using
the named `planning.random` seed stream, and leaves dependency release to the
engine. VM candidates are sorted by ID; draws follow the input Task-list order.
It neither optimizes a cost matrix nor emits per-VM execution order. A supplied
matrix still controls actual compute duration after the random mapping.

## Metaheuristic Static DAG Mapping (Paper Reproduction)

`PSO` is a particle-swarm-optimization Task-to-VM mapper reproducing Pandey,
Wu, Guru, and Buyya, *A Particle Swarm Optimization-Based Heuristic for
Scheduling Workflow Applications in Cloud Computing Environments*, AINA 2010,
following the open-source WorkflowSim reference implementation
`meysamhit/workflowsim-pso`. It requires `STATIC` dispatch and produces only a
Task-to-VM mapping (no per-VM order; runtime dependencies remain
engine-enforced). Effective compute time comes from `TaskExecutionModel`, so a
supplied matrix is consumed rather than ignored. Every particle position is
PE-compatible: initialization and updates project an incompatible VM index to
the nearest compatible index (ties use lower VM ID), without extra random
draws; all-compatible inputs keep the original coordinate and random streams.
Constants are faithful to the reference: population 30, 100
iterations, inertia 0.7, c1 = c2 = 1.5, fitness = 0.8 · cost + 0.2 · makespan
with `price = mips / 1000`.

Known reproduction limits (declared in the manifest contract):

- The fitness uses the reference's sequential VM-load model and **ignores DAG
  dependency edges**; planning-side makespan estimates may differ from runtime
  makespan.
- **Only without a cost matrix**, `price = mips / 1000` cancels the MIPS in
  `rawPerPeLength / mips`, so cost is mathematically
  `sum(rawPerPeLength) / 1000`, independent of mapping. This raw-MI case
  effectively optimizes the VM-load makespan term, aside from floating-point
  rounding. **With a matrix**, cost is
  `sum(effectiveSeconds(task, assignedVm) * assignedVmMips / 1000)` and can
  depend on mapping. Neither case constitutes calibrated cloud pricing or
  evidence for real-platform cost reductions.
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
and SPACE_SHARED VMs. Every placement must be PE-compatible; mean compute
costs and PEFT ranks average only compatible candidates, and CPOP's pinned VM
must support every Task on its critical path.

The no-contention estimate uses `bytes / (1e6 × rate)`: SOURCE→VM is bounded by
destination bandwidth; VM→VM by `min(bw_src, bw_dst)`. At a Task's dependency-ready
time, only already available replicas from the current partial plan may be
used, including zero-transfer local hits. For each parent, sum its file delays
from that parent's finish; different parent groups overlap. External inputs,
**including root inputs**, transfer from the consuming Job's dependency-ready
time. Platform-level stage-in registration is not a preloaded VM replica.

The input-ready candidate is the maximum of dependency readiness, the parent
arrival estimates, and `dependencyReady + externalTransferSeconds`. A positive
hold is clamped to the configured minimum event interval. Inputs are registered
at the **whole hold's end**, independently of VM queueing; outputs at planned
compute finish. Timestamps prevent an earlier planning selection from exposing
a future replica to a later-selected Task inserted into an earlier gap. VMs
reserve compute only, so transfers can overlap VM busy time.

This is a **partial-plan estimate, not full runtime event replay**. Same-time
event ordering, replicas produced by later-planned Tasks, short compute
completion rules, and contention can cause planning/runtime differences.
The canonical paper fixture does not establish exact alignment on arbitrary
file-sharing DAGs or configurations.

**Link-contention variant (R2)**: all three LOCAL planners also accept
`DataMovementModel.preExecutionTransferDelayWithContentionV1()`. Planning still
uses the contention-free AST estimates above; at runtime, concurrent transfers
fair-share each VM endpoint using max-min progressive filling with nominal-rate
caps and residual-capacity redistribution. R10 integrates across intermediate flow
completions before reallocating bandwidth. Unlike V1's parent-finish arrival estimate,
contention groups all start when the Job becomes ready. Therefore V1/R2 differences
mix start-policy and contention effects; the R10 study compares endpoint/Fat-tree
variants with the same start policy. Planning remains contention-free. On the HEFT
paper fixture, contention makespan is 284.1 versus the no-contention 190.1.

**Fat-tree contention variant (R6)**: all three LOCAL planners also accept
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
- `LOCAL_PEFT`: the primary-source recurrence is
  `OCT(t,p) = max_child[ min_p'( OCT(child,p') + w(child,p') + c(t,child,p,p') ) ]`,
  with `OCT(exit,p) = 0`. The table contains successor costs, not current-task
  compute cost. Only compatible placements participate; same-VM communication is zero.
  Each step selects the **dependency-ready Task with highest compatible-VM mean
  OCT**, breaking ties by Task ID, then minimizes insertion `EFT + OCT(t,p)`
  over compatible VMs, breaking ties by VM ID. Mean-OCT need not be topological:
  a child outranking its parent is handled by the ready list, not rejected.
  The successor-cost coordinate and exit-zero condition now match the original
  PEFT equation; SHARED_STORAGE_PEFT removes its communication term under a
  different execution-duration model. The two complete runtime tracks remain
  distinct.

**Primary-source verification**: the author's
[open Porto thesis](https://repositorio-aberto.up.pt/handle/10216/92290)
explicitly reproduces the DOI 10.1109/TPDS.2013.57 article as Chapter 3.
Printed p71 Eq. (7) specifies successor compute and exit zero; p73 Eq. (9)
and Algorithm 1 specify OEFT and ready-list insertion scheduling. Figure 1
and Tables 5/6 have been independently cross-checked; the
[fixture provenance](<../../simulator/src/test/resources/dax/peft-paper-example.SOURCE.md>)
records exact author-version pages, data and scope. This is not a claim of
byte-identical publisher VOR text.

Earlier LOCAL_PEFT used **current-task compute and an exit mean**, not the
original PEFT recurrence. It was not merely a different bootstrap or network
model. Changing only a uniform single-exit constant is a mathematical shift
within the same recurrence; it cannot justify replacing successor costs by
current-task costs. New manifests identify the corrected algorithm as
`PEFT_SUCCESSOR_COST_OCT_EXIT_ZERO_V2`. Historical R12/R13 results using the
old variant are retained as historical evidence, not relabeled as original PEFT.

There are now **two distinct ten-task fixtures**, not one interchangeable
paper example:

- The actual PEFT article Figure 1 begins with compute costs `[22,21,36]`.
  Table 5 begins with OCT `[64,68,86]`, and its exit row is `[0,0,0]`.
  Table 6 selects `{1,4,6,2,3,5,8,7,9,10}` and has makespan **122**;
  the same-input HEFT control has makespan **133**. With the declared 110.1
  bootstrap, the runtime regression expectations are **232.1 / 243.1**.
  [Primary-source regression](<../../simulator/src/test/java/org/workflowsim/planning/LocalPeftPrimarySourcePaperTest.java>)
  checks full OCT/rank, selection, mapping, Job intervals and TaskOutcome
  effective MI/interval agreement.
- The retained HEFT-origin fixture begins with `[14,16,9]`. Its HEFT/CPOP
  reference makespans remain 80/86 before bootstrap (190.1/196.1 absolute),
  and CPOP's path is `{1,2,9,10}` on VM1. Correct Eq. (7) independently
  yields **85** for PEFT on this separate input (195.1 absolute), with total
  modeled transfer seconds 124. The
  [HEFT-origin PEFT regression](<../../simulator/src/test/java/org/workflowsim/experiment/LocalPeftHeftOriginRegressionTest.java>)
  retains mapping, timing, communication and repeat-run coverage. Its older
  76/186.1 value and exit-mean table came from the nonstandard implementation;
  they were not published PEFT Figure 1/Table 5 results.

Earlier CPOP 197.1 / `{1,3,7,10}` statements likewise describe the erroneous
pre-R10 implementation. None of these fixed-input comparisons establishes
universal algorithm superiority.

Known reproduction limits (declared in the manifest contract):

- Bootstrap includes the 110-MI stage-in Job and the root-release interval.
  The canonical fixture's common offset is 110.1 seconds. Root external inputs
  still have their own transfer holds, so that offset is not a substitute for
  modeling data arrival on other inputs.
- The controlled bandwidth model has no link contention or network topology;
  every VM pair uses `min(bw)` regardless of physical path.
- Positive pre-execution holds are clamped to the minimum event interval in
  both the current LOCAL estimate and runtime. This does not reproduce every
  runtime event-order or short-compute completion effect.
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

Do not pool results across these decision layers and model tracks as though they were the
same algorithm class. A valid comparison holds fixed the workflow input hash,
platform profile, storage/overhead/failure/clustering configuration, execution
layer, and metric scope. Also hold the task-cost model and execution-semantics revision
fixed; new evidence identifies `WORK_CONSERVING_TASK_EXECUTION_V2`, while older
artifacts remain historical rather than being silently relabeled. The P7
primary online-dispatch matrix remains its own
track. Static independent-task and controlled static-DAG tracks require
separate matrices and separate result statements.
