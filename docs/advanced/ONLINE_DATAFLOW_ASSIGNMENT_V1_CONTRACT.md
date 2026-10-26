# Control-Ready Online VM Assignment V1

Online assignment selects a fixed target VM once the control dependencies of the logical Task and the workflow-arrival gate have been released, and then prepares the inputs per [Coherent V2](<COHERENT_DATAFLOW_V2_CONTRACT.md>) or [Storage V3](<STORAGE_DATAFLOW_V3_CONTRACT.md>). It is not a new physical Kind and not a re-mapping at CPU dispatch; STATIC dispatches only Jobs that are already assigned, data-ready, and executable.

## Explicit Configuration and Attestation Prerequisites

[DataflowAssignmentConfig](<../../simulator/src/main/java/org/workflowsim/data/v2/DataflowAssignmentConfig.java>) is OFF by default; `onlineNominal()` corresponds to the ordinary core configuration field `configuration.dataflowAssignment`:

```json
{"mode":"CONTROL_READY_ONLINE_ASSIGNMENT_V1","policy":"NOMINAL_INPUT_EARLIEST_RESERVATION_V1"}
```

The object must contain exactly two case-sensitive string fields; values are not coerced, whitespace is not trimmed, and policies are not guessed. An absent value is how the caller expresses OFF; explicit null, false, an OFF object, missing fields, extra fields, and unknown policies are all rejected. The configuration must not end up only in the `algorithmContract`, which is handled as an identity difference.

| Configuration | Supported scope |
|---|---|
| Planning / dispatch | **`planningAlgorithm=INVALID`, `schedulingAlgorithm=STATIC`** |
| Physical model | Coherent file V2 or restricted storage V3, including their no-sharing controls |
| File system | V2 LOCAL only; V3 LOCAL or SHARED |
| Tasks and CPU | NONE clustering with one logical Task per Job; SPACE_SHARED; no overheads |
| Retry | `FTCLUSTERING_NOOP`, `MONITOR_NONE`; a retry keeps the logical Task's original target VM |
| Artifact attestation | A **complete and quiescent** V2/V3 lifecycle capture matching the physical version, mode, and budget, plus the full run context |

The recording switch and the assignment configuration are independent. A raw Java run may be OFF — the physics and the actions do not change with the recording switch — but OFF cannot export an online attestation artifact lacking lifecycle support. Missing, truncated, or context-inconsistent state is rejected before artifact I/O. Coherent models without online assignment keep the RANDOM/STATIC default branches; the constraints of the legacy pre-execution transfers, the LOCAL planners, plain CPU online scheduling, and RL have not been removed.

The online manifest additionally carries the core `dataflowComputeRequests` array, each element holding exactly `jobId,taskId,taskPes,jobPes,lengthMi` and joined with the actual per-Job/Task outcomes; the default non-online modes do not add this field.

You can run the [online V2 example](<../../experiments/configs/online-file-dataflow-v1.json>) or the [online storage V3 example](<../../experiments/configs/online-storage-dataflow-v1.json>). Workbench treats this as a separate decision layer that cannot be mixed with plain CPU online scheduling or offline mapping within one experiment; standalone reports are labeled `DATAFLOW_BINDING_V1`, not merely STATIC.

## Passive Observation and Committed State

- [TransferServiceSnapshot](<../../simulator/src/main/java/org/workflowsim/data/TransferServiceSnapshot.java>) comes from `TransferContentionEngine.snapshotState()` of a checked service: it copies the current capacities, active flows, remaining bytes, rates, paths, and service watermarks, without advancing time, reallocating, forking, recording, or calling into CloudSim. The legacy unchecked constructor rejects this API.
- The contract of [DataflowObservation](<../../simulator/src/main/java/org/workflowsim/data/v2/DataflowObservation.java>) is `OBSERVED_DATAFLOW_STATE_V1`; `observe()` or `observeFiles(scope)` reads the same committed Frame, does not consume the ready queue, and does not depend on OFF/full/truncated capture. The status is `UNINITIALIZED`, `PLAN_ONLY`, or `BOUND`; when nothing is assigned, no actual VM placement, fabric, or capacity is fabricated.
- The file view contains only the currently visible replicas; predicted completions publish no replica. An active copy records the actual source, typed destination, INPUT/OUTPUT purpose, owner, and current service progress. Unterminated Jobs are classified as `WAITING_FOR_STORE`, `WAITING_FOR_INPUTS`, `READY_FOR_CPU`, or `RUNNING`.
- `observeFiles` scopes only the file-holder inventory; the global active copies, resources, and unterminated Jobs remain visible. Queries for files outside the scope are rejected and must not be taken as "known, currently without a replica". Immutable static metadata may be shared, mutable collections are copied, and reads retain no snapshot history.
- Resource allocation rates multiply each path's multiplicity by its binary64 rate and then accumulate exactly with BigDecimal. Under no sharing, the total allocation may exceed the capacity, even up to `Double.MAX_VALUE`, without clamping; it is neither completed traffic nor an interval utilization.

The control loop explicitly calls `advance(now)` before reading a snapshot; the read APIs themselves never advance to `CloudSim.clock`. CPU reservations likewise never treat MIPS queries that return a stub 0 or mutate internal state as actual telemetry.

## Nominal Input and CPU Reservation Policy

[DataflowVmAssigner](<../../simulator/src/main/java/org/workflowsim/data/v2/DataflowVmAssigner.java>) holds per-run logical assignments and reservations. The VM inventory must exactly cover the actual fabric VMs, ordered by numeric ID; non-contiguous IDs are not array indices. The initial assignment scores every VM, and both Task and Job PEs must be compatible; a retry keeps only the original assigned VM as its candidate and does not reselect.

```text
inputDelay = max(每个唯一逻辑输入文件的名义剩余延迟)
cpuAvailable = now + sum(该 VM 仍活动的预计剩余 CPU 预留)
scoreFinish = max(now + inputDelay, cpuAvailable) + executableComputeSeconds
```

- Compute seconds come from the `TaskExecutionModel`, using the same Task×VM matrix and integer MI rounding as the run itself. A missing required coordinate or an unrepresentable cost does not fall back to the raw MI; a positive compute demand must additionally fit the signed-long instruction domain of `MI × TaskPE × 1000000`.
- A reservation for already-started CPU is `max(0, nominalComputeSeconds - (now - observedCpuStart))`; one not yet started retains the full nominal compute. Terms are accumulated one by one in binary64 in **the still-alive request commit order** and only then added to now — not reordered by Job ID or by CPU start. Terminal attempts contribute no effective reservation and are cleaned up at the next successful assignment commit; unknown active compute is not idle.
- A busy VM may be selected so that transfers and computation overlap. The assignment does not set VM BUSY; actual STATIC still obeys the data-ready/idle gating and does not promise the reservation order as an execution order.
- The allowed local-cache delay is 0. An input copy that already exists for `(file,destination)` divides the observed remaining bytes by the **frozen nominal bottleneck**, without recounting the full reference volume or switching sources. Other visible inputs follow the maximum independent bottleneck/stable-location source rule.
- Estimates over multiple unique input files take the max; this is not the workload metric defined in the main events as the sum of the nominal independent input seconds.
- For V3 SHARED, when no SOURCE commit has been observed, its active OUTPUT write-back must be found, giving the estimate `writeRemaining/writeNominalRate + postCommitRead`. A replica already present at the destination removes only the post-commit read, never the SOURCE gate itself; the term is `conditionalStoreWait=true`, which is not a claim that the SOURCE is already visible or a precise arrival prediction.
- A missing source or required write-back path, a non-finite cost, a positive duration that underflows, or a prediction whose clock cannot advance is rejected rather than recorded as zero cost. Score ties take the smaller actual VM ID.

This is a **myopic estimate** over nominal paths and reservations, not a full shared-service replay, real CPU monitoring, or a proof of global optimality; later traffic, input waiting, and the data-ready queue affect the actual completion.

## Assignment Transactions, Retries, and CPU Gating

The first assignment requires both the Task VM and the Job VM to be −1. A NOOP retry creating new Job/Task attempts must inherit the original logical Task's assignment, adding only a new CPU reservation.

Candidate scoring calls no `requestJob` and adds no simulated flows. Once the decision is complete, exactly one real input request is submitted; only a successful transaction commits the assignment, the reservations, the policy sequence number, and the Task/Job VM fields. A failed request leaves none of that state behind; explicitly advancing observation and purely reading are distinct steps.

Every successfully prepared compute attempt records `DATAFLOW_VM_ASSIGNED`, after the real input request succeeds and before the `JOB_READY` at the same observation instant. The lifecycle and main events join by identity/observation instant and share no sequence numbers. `WORKFLOW_ARRIVED` may carry VM=−1 while nothing is assigned yet and is not written back as an assignment made early.

Online STATIC enables fail-fast for compute: the actual VM must exist, the Task/Job assignments must agree, and the PEs must be compatible; falling back to a different VM after the inputs were already transferred is forbidden. The legacy stage-in fallback is retained. The runner's `cpuStarted` additionally checks the originally requested VM and the pre-existing data readiness/input visibility; a CPU commit may not advance the network itself to obtain permission.

## Strict Fields of the Main Action

The main events keep `sequence,simulationTime,type,jobId,vmId,classType,taskIds,attributes`; for compute, taskIds holds exactly one element. Main-stream sequence numbers start at 0, while the action `assignmentSequence` increments consecutively from 1.

`attributes` contains exactly `assignmentMode,assignmentPolicy,inputEstimateSemantics,cpuReservationSemantics,auditScope,assignmentSequence,binding,selectedVmId,observation,candidates`. The fixed semantic labels are:

| Field | Value |
|---|---|
| `assignmentMode` | `CONTROL_READY_ONLINE_ASSIGNMENT_V1` |
| `assignmentPolicy` | `NOMINAL_INPUT_EARLIEST_RESERVATION_V1` |
| `inputEstimateSemantics` | `MAX_FILE_NOMINAL_REMAINING_CONDITIONAL_STORE_V1` |
| `cpuReservationSemantics` | `ACTIVE_COMPUTE_RESERVATION_FROM_OBSERVED_START_V1` |
| `auditScope` | `BINDING_NOMINAL_BOUNDS_AND_LOGGED_SCORE_V1` |
| `binding` | `INITIAL` or `RETRY_REUSE` |

`observation` contains exactly `contract,status,storageVersion,storeBackedInputs,interFlowSharing,observedThrough,serviceThrough,activeCopyCount,activeJobCount,pendingOutputFileCount,scopedFileCount`; the action must carry `OBSERVED_DATAFLOW_STATE_V1`/`BOUND`. `scopedFileCount` counts only the distinct inputs of this Task; the other counters are global active state.

Each candidate contains exactly `vmId,compatible,inputSeconds,cpuAvailableAt,computeSeconds,scoreFinishSeconds,conditionalStoreWait,joinedInputCopies`. The initial action covers all actual VMs in numeric-ID order; a retry lists only the original VM. The last six values of an incompatible candidate must be explicitly null. Only a summary bounded by the candidate count is stored; per-observation full active-network snapshots are not written into the history.

## Scope of the Independent Action Attestation

The [Java validator](<../../simulator/src/main/java/org/workflowsim/data/v2/DataflowAssignmentValidator.java>) and the [independent Python audit](<../../scripts/_dataflow_assignment_audit.py>) use `BINDING_NOMINAL_BOUNDS_AND_LOGGED_SCORE_V1`. The V2/V3 lifecycle and run-context checks are completed first; a single pass then rebuilds the visible replicas, copies, uncommitted outputs, and active Jobs **before each `JOB_INPUT_REQUESTED`**:

- Each compute attempt has exactly one action and a subsequent JOB_READY at the same observation; the initial assignment, the failed-parent lineage, the NOOP same-VM reuse, and the arrival and CPU gating stay consistent. With failures disabled there must be no failures/retries, and the retry count is bounded by the budget.
- Candidates, SPACE_SHARED, and the Task/Job PEs are all checked. The matrix contains exactly `unit,runtimeConversion,entries`, with `unit=EXECUTION_SECONDS`, `runtimeConversion=ROUND_SECONDS_TIMES_VM_MIPS_TO_POSITIVE_INTEGER_MI`, and entries of `taskId,vmId,executionSeconds`; legal extra coordinates are allowed provided the actual Task/VM is covered. Rounding follows the exact value of the binary64 product, never a double-plus-0.5, and the positive MI is capped by the integer domain of `9223372036854 / TaskPE`.
- CPU reservations are rebuilt from the observed starts, the active attempts, and the validated compute amounts. The raw/effective MI of the main CPU event and `MODEL_DERIVED_COMPUTE_WINDOW` must agree with the Task outcome and the action's computation claim; recomputing the hash cannot dissolve a field contradiction.
- Newly read, local, and zero-byte nominal amounts are recomputed exactly. In-flight inputs attest only `(0, fullBytes/nominalRate]`; with an uncommitted SOURCE, the upper bound is the full write-back nominal seconds plus the post-commit read, and the lower bound is the post-commit read. When the post-commit read is 0, the wait must be strictly positive; when it is positive, a very small positive write-back may be absorbed by the binary64 addition. Multi-file boundaries aggregate as the max.
- The recorded input scores, the rebuilt CPU reservations, and the compute seconds are checked against the exact binary64 formulas, finite and advancing clocks, and the minimum-score and VM-ID tie rules. Numbers must be true JSON numbers, with integers read exactly in the int32/int64 domains; numeric token lengths, decimal precision, and absolute scale must not exceed 4096, and a nonzero value underflowing to zero is not accepted.

**The attestation does not certify exact active-flow balances or the full future contention process.** The action does not log every service epoch, and progress rewritten within the nominal boundaries may still satisfy the bounded attestation; an actual rerun still compares field by field and may return `DIVERGED`. The raw configuration, the compute requests, and the main action's scientific fields gain no new mutable exemptions; the actual output/CPU causality is carried by the lifecycle certificate.

## Reporting and Review

Reports read only the same verified snapshot. At most **64** actions are previewed, with at most **12** candidates each; the selected entry is extracted from the full candidate set even when it is not among the previewed ones. Numbers are exact text; missing projection, not configured, and no valid result are each handled separately. The physical V2/V3 panels are retained alongside, and the sidecar is not read again.

See the [rerun difference contract](<../experiments/RERUN_DIFF_CONTRACT.md>), [Workbench](<../getting-started/WORKBENCH.md>), the [capability matrix](<DATAFLOW_CAPABILITY_MATRIX.md>), and [build verification](<../getting-started/BUILD.md>).
