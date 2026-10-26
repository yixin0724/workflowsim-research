# V2 File Lifecycle Evidence Format

The V2 certificate describes logical files, the actually visible sources, ordered resource paths, same-destination merges, settlement observations, and CPU input causality. It is **not a per-interval fluid service/rate-area accounting certificate**, and it must not be used to relabel the old V1 group ledger as per-file proof.


## Version, Recording, and Run Scope

| Item | Value |
|---|---|
| Shared Kind | `COHERENT_FILE_DATAFLOW_V2` |
| No-sharing Kind | `COHERENT_FILE_DATAFLOW_NO_CONTENTION_V2` |
| Recording mode / Java config | `FILE_LIFECYCLE_V2` / `NetworkEvidenceConfig.fileLifecycleV2(B)` |
| artifact role | `file-lifecycle`, part of the outer artifact manifest, not a sidecar root field |
| File name | `<runId>.file-lifecycle.json` |
| schema | `workflowsim-file-lifecycle-v2` |
| certificateScope | `FILE_LIFECYCLE_NOT_FLUID_SERVICE_ACCOUNTING_V2` |

Runs use LOCAL, NONE clustering (one logical Task per Job), SPACE_SHARED, no overheads, a single bound datacenter, and controlled NOOP retries. The default is RANDOM mapping + STATIC dispatch; explicit online binding uses INVALID + STATIC together with the [online assignment contract](<ONLINE_DATAFLOW_ASSIGNMENT_V1_CONTRACT.md>). Once the workflow arrival/control dependencies are satisfied and the target is fixed, inputs are requested at the current observation; no transfers are made retroactively.

The V2 SOURCE is explicitly an unbounded sink and bypasses the fabric; required output uploads and finite storage resources belong to the separate [Storage V3](<STORAGE_DATAFLOW_V3_CONTRACT.md>) and must not be written into this version's certificate. For run physics and source rules see the [Coherent V2 contract](<COHERENT_DATAFLOW_V2_CONTRACT.md>).

Export accepts only complete captures. Insufficient budget, truncation, or context contradictions are rejected before artifacts are created/replaced, without changing the simulation physics. In ordinary non-online modes, OFF still retains the core `dataflowPlan` and generates no lifecycle sidecar; online attestation cannot be exported from an OFF run alone. This format does not change the metrics, truncation support, or OFF shape of the original V1 group ledger.

## JSON Root and Policies

[FileLifecycleCodec](<../../simulator/src/main/java/org/workflowsim/data/v2/FileLifecycleCodec.java>) requires the root to contain exactly nine members:

`schema,modelKind,recording,certificateScope,policies,capture,filePlan,fabric,events`

- `schema` and `certificateScope` are fixed to the values in the table above; `modelKind` can only take the two Kinds in the table above.
- `recording` contains exactly `mode,maxTraceRecords`; the mode is `FILE_LIFECYCLE_V2`, and the budget B is an exact integer in `1..2147483647`, counted in records, not in bytes.
- `capture` contains exactly `status,observedThrough,retainedRecords,droppedRecords`. A valid certificate requires `status=COMPLETE` and `droppedRecords=0`; retainedRecords equals the events length and does not exceed B; observedThrough is a finite, non-negative observation watermark.
- COMPLETE means no history was lost, not that flows or Jobs have finished. The standalone codec may accept a valid prefix of the complete run; a complete bundle additionally requires consistency with the final run and quiescence. `TRUNCATED` is not a valid V2 certificate.

`policies` contains exactly the following six members:

| Field | Fixed/controlled value |
|---|---|
| `fileIdentity` | `SCOPED_RESOLVED_WRITE_ONCE_FILES_V2` |
| `release` | `DEPENDENCY_READY_AT_OBSERVATION_V2` |
| `visibility` | `PER_FILE_SETTLEMENT_OBSERVATION_V2` |
| `selection` | `VISIBLE_LOCAL_OR_MAX_ISOLATED_RATE_STABLE_LOCATION_V2` |
| `sourceAccess` | `UNBOUNDED_OFF_FABRIC_SOURCE_INPUT_V2` |
| `sharing` | `SHARED_MAX_MIN` or `ISOLATED_PATH_BOTTLENECK`, consistent with the Kind |

Strict reading rejects duplicate keys, extra/missing fields, wrong types, and trailing JSON content; required nullable fields must be present and explicitly null, and must not be omitted. JSON parsing depth is capped at 64; number token length, decimal precision, and absolute scale are each at most 4096. Integers are read exactly within their int32/int64 domains, with no coercive string/float rounding.

## Logical File Plan and the Actual Fabric

`filePlan` contains exactly `contractVersion,tasks,files`; contractVersion is `SCOPED_RESOLVED_WRITE_ONCE_FILES_V2`.

| Object | Fields |
|---|---|
| Task | `taskId,workflowInputIndex,parents,inputs,outputs` |
| Reference in inputs | `fileId,referenceCount` |
| File | `fileId,bytes,producerTaskId`; the producerTaskId of an external file is null |
| FileId | `workflowInputIndex,name`; the name is a non-empty, already-resolved key and is never split or renamed |

File sizes keep the finite, non-negative binary64 bytes as resolved; conflict comparisons use no epsilon; the plan cannot recover declarations already discarded by DAX first-wins. Zero bytes is still a reference. Reference counts are positive exact integers and must not be expanded into an enormous FileItem list for decoding; the total reference count of a single Job must not exceed `2147483647`, and reference-byte aggregation must be finite.

`fabric` contains exactly `locations,resources,vmHostAssignments,topology`:

- A typed location is exactly `kind,vmId,sourceId`. A VM uses a non-negative vmId and a null sourceId; the unique SOURCE uses `kind=SOURCE,vmId=null,sourceId=source`.
- A resource row is `key,capacityBytesPerSecond`, where capacity is a positive, normal, finite binary64 B/s; the resource set must exactly match the VM endpoints and the declared links, and missing keys are not treated as infinite capacity.
- With no topology, `topology=null` and V2's vmHostAssignments is empty; with a topology, each entry is `vmId,hostId` and must fully cover the actual VMs.
- The topology contains exactly `kind,k,coreSwitchCount,linkBandwidthBytesPerSecond,hostPlacements`, and its kind is `FAT_TREE`. Java/codec requires an even `k=2..32`, a core count `1..k²/4`, and a Host count `1..k³/4`, with no more than `k/2` Hosts per edge; a placement is `hostId,pod,edge`, with pod below k and edge below k/2. Safety size checks precede construction of the topology object. Workbench additionally limits the even `k=2..16`.

VM endpoints are shared by send and receive alike; directed topology links distinguish directions. The V2 inter-VM path order is source VM, links, destination VM, preserving resource multiplicity and never mixing in the V1 group path order; a SOURCE read occupies only the destination endpoint and does not traverse the fabric. V2 fabric does not allow `sourceStorage`.

## Ordered Events and Replica Provenance

Events contain exactly `sequence,observedTime,type,payload`. sequence is consecutive from 1; observedTime is finite, non-negative, monotonic, and never exceeds the capture watermark. Operations at the same instant are still distinguished by sequence number.

| type | payload fields |
|---|---|
| `EXTERNAL_SEEDED` | `fileId,location` |
| `TASK_FINISHED` | `taskId,jobId,vmId,success` |
| `JOB_INPUT_REQUESTED` | `jobId,taskIds,destinationVmId` |
| `INPUT_RESOLVED` | `jobId,fileId,referenceCount,resolution,copyOrdinal,source` |
| `COPY_ADMITTED` | `copyOrdinal,fileId,bytes,sourceReplica,destinationVmId,resources,standaloneRate,isolatedSeconds` |
| `COPY_SETTLED` | `copyOrdinal,effectiveTime,remainingAfterService` |
| `JOB_DATA_READY` | `jobId` |
| `JOB_CPU_STARTED` | `jobId,vmId` |

Each external file must be SOURCE-seeded exactly once, at time 0 and before the run's events. The request's taskIds is a single element and cannot repeat a Job attempt ID; all control-parent Tasks must already have successful logical results.

`resolution` is `LOCAL`, `ZERO`, `NEW_COPY`, or `JOIN_EXISTING`. LOCAL/ZERO have a null copyOrdinal; NEW/JOIN point to the actual ticket, and positive copy ordinals are consecutive from 1. When no valid source has been published, a SOURCE must not be invented and success must not be claimed at zero cost. An existing local replica takes precedence; at most one active copy exists at a time for the same `(file,destination)`, and JOIN keeps the ticket's original source and path instead of starting another transfer.

A replica contains exactly `fileId,location,visibleAt,acquisition,origin,copiedFrom,copyOrdinal`; origin is `producerTaskId,jobAttemptId,location,observedAt`, preserving the root producer and its attempt, the actual publish location, and the observation instant. Acquisition is `EXTERNAL_SEED`, `TASK_OUTPUT`, `COPY_SETTLEMENT`, or `ZERO_BYTE_REFERENCE`; the holder is not the original producer. Later publishes keep the earliest visible provenance; failures do not delete caches that have already arrived; Task success status must be checked against its own attempt evidence and must not be substituted by an unrelated Job's state.

CPU start must rest on **recorded data readiness**, all inputs being visible, and a fixed VM; permission must not be granted retroactively after a CPU commit has advanced the network. A complete prefix must not be truncated in the middle of an input request/copy admission, nor omit a JOB_DATA_READY that should have been emitted immediately.

## Numeric and Settlement Boundaries

- File bytes may be 0 or a representable fraction and are not truncated to a long. A non-zero JSON number that underflows to 0 after conversion to binary64 is rejected; negative values, NaN/Infinity, and non-finite aggregations are not accepted.
- Physical capacities and standaloneRate are at least `Double.MIN_NORMAL`. The isolated positive transfer seconds `bytes / standaloneRate` must be finite and positive; the admission instant plus that duration must be finite and must strictly advance the clock; underflow must not be treated as a zero transfer, and errors must not be masked by choosing a different source.
- Single-flow path checks are not a shared-solver proof. The running service additionally checks the normally allocated rates, the exact resource capacities, the finite service area, and future instants; a failed transaction is rolled back atomically; legacy unchecked constructors acquire no new guarantees from this.
- COPY_SETTLED requires `release < effectiveTime <= observedTime`, and observedTime is strictly later than release. A replica becomes visible only at observedTime and cannot be published early based on an internal completion prediction.

`remainingAfterService` is the non-negative residual after service deduction and before tolerance/forced settlement; it is not traffic. The scalar binary64 rules are:

```text
residualLimit = min(bytes * 0.5, max(bytes * 1e-9, 4 * ulp(bytes)))
lower = release + ((bytes - remainingAfterService) / standaloneRate)
allowed = min(8 * max(ulp(release), ulp(effectiveTime), ulp(lower)),
              1e-12 * max(release, effectiveTime, lower))
```

The residual must not exceed residualLimit; lower must be finite and no later than effectiveTime, or the difference must not exceed allowed. This necessary lower bound accounts for the settlement residual and clock rounding and **does not require the full declared payload to have been integrated as service area, nor does it independently replay shared allocation**.

## Artifact Context, Rerun, and Display

Modes, models, DTOs, roles, and getters are dispatched by the fixed version. `getNetworkEvidence()/getDecodedNetworkLedger()` keeps its V1 meaning, V2 uses `getFileLifecycle()/getDecodedFileLifecycle()`, and V3 uses its separate storage path; a missing V2 certificate must not be defaulted to V1/OFF.

[Full artifact validation](<../../simulator/src/main/java/org/workflowsim/experiment/FileLifecycleContextValidator.java>) additionally checks the core plan, input ownership/arrival, the actual platform, the request/ready/CPU/completion of every compute attempt, Task/Job windows, and the retry lineage; it requires all logical Tasks to have succeeded and the already-requested state to be at rest. The main `DATA_STAGE_IN_MODELED` has `transferUnit=LOGICAL_FILE_V2`; its reference counts and its new/joined copy counts agree with the lifecycle; `modeledTransferSeconds` is the sum of nominal input seconds, not actual waiting. An ordinary OFF plan can be checked separately, but that does not by itself claim full lifecycle attestation.

The Java codec, the [Python lifecycle audit](<../../scripts/_file_lifecycle_audit.py>), and the [Python context check](<../../scripts/_file_lifecycle_context.py>) each independently execute their own explicitly defined scope and call no production coordinator/assigner to generate expectations. Recomputing hashes cannot mask CPU causality, provenance, path, or configuration contradictions.

Every sidecar field enters the exact rerun comparison under `/fileLifecycle/...`, with no new volatile-field exemptions. The report reuses the same verified snapshot and renders numbers as exact text; copy/resource/event previews are capped at **64/64/128**, with nested lists at 12 items and labels at 256 characters. OFF, insufficient budget, zero positive copies, legal run prefixes, and the absence of a verified result are handled as distinct cases, without reading additional sidecars.

See the [rerun difference contract](<../experiments/RERUN_DIFF_CONTRACT.md>), [Workbench](<../getting-started/WORKBENCH.md>), the [capability matrix](<DATAFLOW_CAPABILITY_MATRIX.md>), [state-copy performance](<DATAFLOW_STATE_COPY_PERFORMANCE.md>), and [build checks](<../getting-started/BUILD.md>).
