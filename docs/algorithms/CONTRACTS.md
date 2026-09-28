# 算法与指标语义契约

## Purpose

P8 treats an algorithm as reproduced when WorkflowSim implements its core
decision semantics in a declared simulator model and regression-tests those
semantics. This is intentionally stronger than "the run completes" and
intentionally narrower than a claim of bit-for-bit or real-environment
equivalence with a paper implementation.

Every maintained algorithm must have a decision contract, an explicit model
adaptation statement, deterministic tie-break rules, and a test oracle that
does not call the production decision helper being checked.

## Common Execution and Decision-Layer Contract

The standard runner supports `NONE` clustering and `SPACE_SHARED` VMs only.
Every non-empty planner requires `STATIC`, and `STATIC` requires a planner.
Placements must be PE-compatible; dispatch still allows at most one Job per VM
at a time. A complete static plan enforces per-VM order, not exact timestamps.

Time-aware independent planners, PSO, LOCAL planners and execution share
`TaskExecutionModel`: compute seconds are raw per-PE length / MIPS without a
matrix, or `round(matrixSeconds * MIPS) / MIPS` with one. A matrix is
authoritative, so missing coordinates cannot fall back to raw MI. Rounded work
must be positive and representable as signed-long instructions, including PE
multiplicity. Source Task length and per-attempt effective execution MI are
separate; planning does not overwrite the source length.

These are compute-cost estimates, not total runtime Job envelopes. New
manifests identify corrected execution with
`executionSemantics = WORK_CONSERVING_TASK_EXECUTION_V2`; historical artifacts
are not rewritten to appear as current evidence. Regression coverage is not a
claim that every supported configuration or original-paper assumption has been
independently verified.

## Shared-Storage Static DAG Track

The controlled model is `SHARED` storage, `NONE` clustering, no overhead,
disabled failures, `legacyWorkflowsimV1()` data movement, and capacity-feasible
`SPACE_SHARED` VMs with deterministic VM-to-Host placement. Task cost matrices
are rejected on this track. Candidates must have at least the Task's requested
PE count; multi-PE compatibility is supported, while dispatch still permits
only one Job per VM at a time.

A model-generated 110 MI stage-in Job runs on the lowest VM ID. For a compute
Task, sum all real input file delays first:
`transferSeconds = sum(fileSize / 1e6 / storageRate)`. The candidate duration is
`(rawPerPeLengthMi + floor(vmMips * transferSeconds)) / vmMips`.
Rounding is once per Task's summed delay, not once per file. Requested PEs
execute in parallel, so multiplying the per-PE length by PE count is not the
wall-clock duration formula. Parent-to-child movement is represented by DAG
release plus this storage delay, not by a calibrated network schedule.

Completion-event scheduling uses the minimum-event interval and 0.01-second
safety margin; it does not license starting new work with stale CPU progress.
Root Jobs also wait one kernel release interval after stage-in returns. Planned
timings do not replay the complete event loop, and the static dispatcher
enforces per-VM order rather than exact planned timestamps.

A one-VM-per-Host layout remains the recommended reference layout because it
removes Host placement as an experimental factor. Profiles may co-locate VMs
only when their RAM, bandwidth, storage, PE, and MIPS reservations are
preflighted. The planner reserves VM timelines and assumes each declared VM
receives its declared MIPS; it does not model Host CPU scheduling, Host
utilization, VM migration, or cross-VM Host contention. The manifest records
optional pins, the deterministic preflight map, and the actual map frozen on
CloudSim VM creation.

| Algorithm | Core decision semantics reproduced | Deterministic adaptation | Required oracle evidence |
| --- | --- | --- | --- |
| `SHARED_STORAGE_HEFT` | Upward rank priority followed by insertion-based earliest finish allocation | Ranks use compatible-VM average model duration; equal ranks and equal finishes use lower Task/VM ID | Rank values, selected VM, planned start/finish, a reservation-gap insertion, and incompatible-PE exclusion |
| `SHARED_STORAGE_CPOP` | Upward-plus-downward priority; one critical path is placed on its lowest-total-duration compatible VM | Equal entry/path/processor choices use lower IDs; noncritical Tasks use the same insertion allocation as HEFT | `r_u`, `r_d`, combined priority, chosen critical path/VM, path tie-break, and planned allocation |
| `SHARED_STORAGE_DLS` | At every selection, choose the dependency-ready Task-VM pair with maximum `upward rank - earliest insertion start` | Static b-level uses the compatible-VM average model duration; equal dynamic levels use lower Task ID then VM ID | Static b-level, independently derived dynamic-level values, selection order, task/VM tie-break, and full execution-plan alignment |
| `SHARED_STORAGE_ETF` | At every selection, choose the dependency-ready Task-VM pair with the minimum earliest insertion start | Equal starts use higher static b-level, then lower Task ID and VM ID | Independently derived earliest-start values, b-level tie-break, selection order, task/VM tie-break, and full execution-plan alignment |
| `SHARED_STORAGE_PEFT` | Select the dependency-ready Task with maximum compatible-VM average OCT, then its VM with minimum insertion `EFT + OCT` | OCT omits the original communication term because this model has none; equal task priorities and VM scores use lower IDs | Independently derived OCT/rank/objective values, selection order, task/VM tie-break, and full execution-plan alignment |

The source method is Topcuoglu, Hariri, and Wu, *Performance-Effective and
Low-Complexity Task Scheduling for Heterogeneous Computing*, IEEE TPDS 13(3),
2002, DOI [10.1109/71.993206](https://doi.org/10.1109/71.993206). The contract
above is an explicit model adaptation, not a claim that WorkflowSim recreates
that paper's communication-cost environment.

DLS follows Sih and Lee, *A Compile-Time Scheduling Heuristic for
Interconnection-Constrained Heterogeneous Processor Architectures*, IEEE TPDS
4(2), 1993, DOI [10.1109/71.207593](https://doi.org/10.1109/71.207593). Its
original dynamic-level method models communication and interconnection
constraints. This contract retains the Task-VM dynamic-level core but excludes
topology, routing, and shared-link contention from the controlled model.

ETF follows Hwang, Chow, Anger, and Lee, *Scheduling Precedence Graphs in
Systems with Interprocessor Communication Times*, SIAM Journal on Computing
18(2), 1989, DOI [10.1137/0218016](https://doi.org/10.1137/0218016). The
source method is analyzed for identical processors with communication delays.
This contract retains earliest-start selection and static-b-level tie-breaking,
while excluding the original hardware and communication assumptions.

PEFT follows Arabnejad and Barbosa, *List Scheduling Algorithm for
Heterogeneous Systems by an Optimistic Cost Table*, IEEE TPDS 25(3), 2014, DOI
[10.1109/TPDS.2013.57](https://doi.org/10.1109/TPDS.2013.57). Its original OCT
includes interprocessor communication costs. This contract retains the OCT
successor look-ahead, average-OCT priority, and `EFT + OCT` selection core, but
sets the communication term to zero because the controlled model does not
represent topology, routing, or link contention. The LOCAL track also maintains
`LOCAL_PEFT`, with pairwise communication
`bytes / (1e6 × min(bw_p, bw_p'))` for different VMs and zero for the same VM.
Both maintained PEFT tracks now use successor-cost OCT and exit zero. The
LOCAL equation is `max_child min_p'[OCT(child,p') + w(child,p') + c(t,child,p,p')]`;
SHARED removes communication but uses its shared-storage execution-duration
model, so their full runtime assumptions still differ.

This coordinate and exit condition were verified against the author's
[open article chapter](https://repositorio-aberto.up.pt/handle/10216/92290),
Chapter 3 printed p71 Eq. (7), p72 Table 5 and p73 Algorithm 1. Its actual
Figure 1 has PEFT/HEFT makespans 122/133, not the older HEFT-origin input's
claimed76/80. The former LOCAL current-task-cost/exit-mean version was a
nonstandard algorithm, not an equivalent exit-constant convention. Its old
results must not be relabeled as original PEFT. New machine contracts carry
`PEFT_SUCCESSOR_COST_OCT_EXIT_ZERO_V2`. See the
[algorithm catalog](<CATALOG.md>) and [source provenance](<../../simulator/src/test/resources/dax/peft-paper-example.SOURCE.md>).

## Independent-Task Static Track

`STATIC_OLB`, `STATIC_MET`, `STATIC_MCT`, `STATIC_MINMIN`, `STATIC_MAXMIN`,
`STATIC_SUFFERAGE`, and `STATIC_ROUND_ROBIN` only accept a bag of independent
Tasks. Their core contract is VM mapping under predicted model execution time;
they do not create a DAG schedule, network schedule, or real queue replay.

The maintained tests cover sorted-ID tie-breaks, PE compatibility, availability
updates, Min-Min/Max-Min opposing selection, and Sufferage's best-versus-
second-best completion-time loss. The taxonomy source is Maheswaran et al.,
*Dynamic Mapping of a Class of Independent Tasks onto Heterogeneous Computing
Systems*, JPDC 59(2), 1999, DOI [10.1006/jpdc.1999.1581](https://doi.org/10.1006/jpdc.1999.1581).

## LOCAL Data-Availability and Ready-List Contract

LOCAL_HEFT/CPOP/PEFT require LOCAL storage, STATIC dispatch, NONE clustering,
no modeled overhead/failure, SPACE_SHARED VMs and a preExecution-family data
model. Planning stays contention-free even when runtime uses endpoint or
Fat-tree contention. Fat-tree also requires the matching topology declaration.

A candidate reads only replicas whose recorded availability is no later than
the Task's dependency-ready time. Parent-file groups use the parent's planned
finish plus the sum of that parent's file delays; different parents overlap.
External inputs, including root inputs, begin at the consuming Job's
**dependency-ready time**, not simulated zero. Bootstrap's datacenter replica
does not make a destination-VM local copy.

Positive input holds use the configured minimum event interval. All real input
replicas are registered at the **whole hold's completion**, before any possible
VM queue wait; output replicas at planned compute finish. Recording a future
placement must not grant an earlier local hit. Valid earlier gap insertions
remain allowed when their own inputs can arrive in time.

The planner does not replay the complete runtime event queue. Same-time
ordering, replicas produced by later-planned Tasks, short compute completion
rules and contention remain explicit sources of prediction differences.
Required independent oracles include root and non-root external inputs,
shared-input future replicas, valid gap insertion, and input availability
before consumer compute starts; see the
[LOCAL data-availability tests](<../../simulator/src/test/java/org/workflowsim/planning/LocalDataAvailabilityPlanningTest.java>).

PE-compatible costs are used for rank averages. CPOP's one critical processor
must support its entire selected path. LOCAL_PEFT selects the highest mean OCT
**among dependency-ready Tasks**, then minimizes EFT+OCT over compatible VMs;
incompatible OCT entries never enter its rank average. A child with higher
rank than its parent is handled by this ready list, not rejected. The primary
source explicitly uses this ready-list discipline. The corrected implementation
is guarded by [independent Eq. (7) counterexamples](<../../simulator/src/test/java/org/workflowsim/planning/LocalPeftSuccessorCostContractTest.java>)
and the [actual paper fixture](<../../simulator/src/test/java/org/workflowsim/planning/LocalPeftPrimarySourcePaperTest.java>),
including TaskOutcome effective MI and compute-window agreement with Job timing.

## Mapping-Only Cost Contract

Independent time-aware strategies use the common effective compute cost.
OLB updates availability with that cost; MET ignores availability; MCT,
Min-Min, Max-Min and Sufferage use the corresponding completion estimates.
Sufferage defines loss as zero when there is only one compatible VM.
STATIC_ROUND_ROBIN and RANDOM intentionally do not optimize those costs.

PSO also consumes effective matrix costs but keeps its sequential VM-load
objective and ignores DAG/network timing. With `price = MIPS/1000`, cost is
mathematically mapping-invariant **only in the no-matrix raw-MI case**:
`sum(rawPerPeLength)/1000`. With a matrix, it is
`sum(effectiveSeconds(task,assignedVm) * assignedVmMips/1000)` and may vary by
mapping. The price remains an abstract heuristic. Incompatible particle
positions are projected to the nearest compatible VM index, ties by lower VM
ID, without additional random draws. The whole compatible domain is checked
before sampling; missing matrix coordinates do not depend on which particles
happen to visit them. Independent expectations and cost-oblivious controls
live in the [matrix regression tests](<../../simulator/src/test/java/org/workflowsim/planning/PlanningCostMatrixRegressionTest.java>)
and [PE-domain tests](<../../simulator/src/test/java/org/workflowsim/planning/PlanningPeCompatibilityTest.java>).

## Metric Contract

Metrics are simulator-derived quantities, not production observability data.

| Metric family | Counting rule | Required edge cases |
| --- | --- | --- |
| Job outcome rate and throughput | Compute Job outcomes only; stage-in is reported separately. A compute outcome is an attempt, so retry attempts remain in this denominator. | Empty run, failed Job, retry Job |
| Logical Task completion | A source logical Task is completed only when it appears in at least one successful compute Job. | Retry after failure, clustering, no source Task snapshot |
| Simulation end and logical workflow completion | Historical `makespanSeconds` and explicit `simulationEndSeconds` are the same CloudSim end clock. `logicalTaskCompletionSeconds` is available only when every source logical Task has a successful compute Job; it is the latest among those Tasks' first successful Job-envelope finish times. | Incomplete workflow and no-logical-Task runs have no logical completion time; `terminalLifecycleTailSeconds` is available only for complete workflows. |
| Attempt, retry, and failure evidence | One completed Job outcome is one Job attempt. Retry attempts are identified by `RETRY_JOB_CREATED` evidence and its failed parent; failed compute envelope/cost totals include complete failed attempts. | Missing, duplicate, self-referential, or non-failed retry-parent evidence fails metric derivation rather than silently changing counts. |
| Delay metrics | Total waiting/bounded slowdown require an ordered ready-to-start observation; ready-to-decision requires both corresponding events, while decision-to-start uses its own observation count. VM-queue waiting starts at VM submission, not Job readiness. | Missing events, decision after start, and distinct sample counts; preExecution waiting includes data preparation, unlike legacy in-envelope stage-in. |
| Bounded slowdown / waiting percentiles | Per-attempt `max((wait+execution)/max(execution,10s),1)` then arithmetic mean; waiting median/P95 use the documented nearest-rank convention. | A value of 1 does not prove zero waiting; empty samples, short jobs, retry attempts, and percentile tails. |
| VM busy/utilization | Union of completed Job intervals per VM; not host utilization | Overlap, gaps, zero makespan, idle VM |
| Data-stage-in demand | Count/bytes describe inputs external to each compute Job, including files produced by workflow parents; they are not limited to SOURCE inputs. Stage-in seconds sum nominal estimates, including in contention variants. | Local hits can give zero delay; overlapping groups mean the sum is not network wall time, and it is not an actual contention-duration or link-traffic ledger. |
| Modeled processing cost | Sum every completed Job attempt's CPU-envelope component and declared-file bandwidth component. Declared file bytes are summed continuously in decimal MB (`1,000,000` bytes) with no per-file billing rounding. | Failed and retry attempts remain included; effective stage-in MI can affect the CPU envelope; memory/storage price fields are not charged by this model. |
| Algorithm decision overhead | Recorded `System.nanoTime` around explicit static planner runs and runtime scheduling cycles; never simulated time | Missing/non-numeric planner event, online run with no explicit planner, and wall-clock exclusion from deterministic fingerprints |
| SLR reference | Controlled SHARED, NONE clustering, no failure/overhead, SPACE_SHARED, legacy or fixed-endpoint transfer only. Minimize effective per-PE integer work over compatible VMs, including a legal mapping-only matrix; fixed-endpoint retains an optimistic storage-only bound. | PreExecution parallel input arrivals cannot use the serial-input bound. If any required candidate/envelope is not representable, this optional reference is unavailable rather than turning a completed selected execution into failure; actual execution/planner validation is unchanged. Check raw/matrix differences, PE feasibility, MI rounding and scope. |
| Deadline SLA observation | Compare the exact signed-long threshold with the exact binary64 `simulationEndSeconds` value from simulated time zero before rounding slack/tardiness. It never alters dispatch, admission, retry, or failure behavior. | No deadline requested, incomplete precedence, nonzero arrivals, lifecycle tail, replayed fault/overhead samples, and values around 2^53/Long.MAX_VALUE. No comparison epsilon. |
| Task timing accuracy | `lengthMi` retains parsed/normalized source work; `effectiveExecutionLengthMi` is the current attempt's compute work after matrix rounding. Task windows use effective work. Legacy/fixed stage-in may enlarge the Job envelope, while preExecution transfers precede it; exact timing requires one Task and matching Task/Job windows. | Matrix shorter/longer than source, retry copying, requested/effective stage-in, quantization and failed attempts; do not substitute the source length for effective work. |

The current failure model determines outcome after an attempt has reached its
Job envelope completion boundary. Only successful Tasks commit their declared
output files to the replica catalog; a failed Task output is not a readable
input replica for a dependent Job. This is a simulator fail-after-attempt and
output-commit contract, not a calibrated mid-execution outage, transactional
storage, or distributed-filesystem model.

The test oracle records its arithmetic inputs directly and never treats a
passing test as evidence of real cloud, storage, network, price, failure, or
trace calibration.
