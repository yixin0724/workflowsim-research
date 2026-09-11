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

## Shared-Storage Static DAG Track

The controlled model is `SHARED` storage, no clustering, no overhead, disabled
failures, capacity-feasible single-PE `SPACE_SHARED` VMs with a deterministic
profile VM-to-Host placement, and a model-generated 110 MI stage-in Job on the
lowest VM ID. A real input file contributes
`floor(vmMips * fileSize / 1e6 / storageRate) / vmMips` seconds to a candidate
Task duration. Compute duration uses CloudSim's `cloudletTotalLength`, which is
the Task length multiplied by its requested PE count. Parent-to-child data
movement is represented by DAG release plus this per-Task storage delay, not by
a calibrated link-level network model. Every Cloudlet completion is scheduled
no earlier than the current time plus `cloudSimMinEventIntervalSeconds + 0.01`.
Root compute Tasks begin only after one further
`cloudSimMinEventIntervalSeconds` event-kernel release interval after the
stage-in Job returns.

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
represent topology, routing, or link contention.

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

## Metric Contract

Metrics are simulator-derived quantities, not production observability data.

| Metric family | Counting rule | Required edge cases |
| --- | --- | --- |
| Job outcome rate and throughput | Compute Job outcomes only; stage-in is reported separately. A compute outcome is an attempt, so retry attempts remain in this denominator. | Empty run, failed Job, retry Job |
| Logical Task completion | A source logical Task is completed only when it appears in at least one successful compute Job. | Retry after failure, clustering, no source Task snapshot |
| Simulation end and logical workflow completion | Historical `makespanSeconds` and explicit `simulationEndSeconds` are the same CloudSim end clock. `logicalTaskCompletionSeconds` is available only when every source logical Task has a successful compute Job; it is the latest among those Tasks' first successful Job-envelope finish times. | Incomplete workflow and no-logical-Task runs have no logical completion time; `terminalLifecycleTailSeconds` is available only for complete workflows. |
| Attempt, retry, and failure evidence | One completed Job outcome is one Job attempt. Retry attempts are identified by `RETRY_JOB_CREATED` evidence and its failed parent; failed compute envelope/cost totals include complete failed attempts. | Missing, duplicate, self-referential, or non-failed retry-parent evidence fails metric derivation rather than silently changing counts. |
| Delay metrics | Only Jobs with ordered `JOB_READY` and `SCHEDULING_DECISION` observations contribute | Missing observations and decision recorded after start |
| VM busy/utilization | Union of completed Job intervals per VM; not host utilization | Overlap, gaps, zero makespan, idle VM |
| Data-stage-in demand | File count and bytes are the compute Jobs' modeled logical external-input demand. Stage-in seconds are model-produced delays. | A local replica can make modeled delay zero; neither field is a physical network/storage traffic ledger. |
| Modeled processing cost | Sum every completed Job attempt's CPU-envelope component and declared-file bandwidth component. Declared file bytes are summed continuously in decimal MB (`1,000,000` bytes) with no per-file billing rounding. | Failed and retry attempts remain included; effective stage-in MI can affect the CPU envelope; memory/storage price fields are not charged by this model. |
| Algorithm decision overhead | Recorded `System.nanoTime` around explicit static planner runs and runtime scheduling cycles; never simulated time | Missing/non-numeric planner event, online run with no explicit planner, and wall-clock exclusion from deterministic fingerprints |
| SLR reference | Available only in the controlled shared-storage scope | Unsupported storage/overhead/failure scope and invalid DAG |
| Deadline SLA observation | Positive deadline compares `simulationEndSeconds` from simulated time zero; it never alters dispatch, admission, retry, or failure behavior. | No deadline requested, incomplete workflow, and late completion |
| Task timing accuracy | Job timing is the CloudSim envelope including effective integral-MI stage-in; logical-Task timing is the compute window after that delay and is exact only when the two coincide | Retry attempt, clustered Job, requested-versus-effective stage-in, and failed Job |

The current failure model determines outcome after an attempt has reached its
Job envelope completion boundary. Only successful Tasks commit their declared
output files to the replica catalog; a failed Task output is not a readable
input replica for a dependent Job. This is a simulator fail-after-attempt and
output-commit contract, not a calibrated mid-execution outage, transactional
storage, or distributed-filesystem model.

The test oracle records its arithmetic inputs directly and never treats a
passing test as evidence of real cloud, storage, network, price, failure, or
trace calibration.
