# Bounded SOURCE and Shared-Storage Dataflow V3

V3 adds bounded read/write/NIC resources, a physical storage attachment path, and required-output write-back to the coherent file dataflow. Execution, the standalone sidecar, Java/Python validation, configuration rebuild, exact rerun, and offline reporting all use the same versioned contract; a V3 certificate cannot be converted into, or relabeled as, V2.


## Model and Configuration

| Item | Supported values |
|---|---|
| Sharing model | `COHERENT_STORAGE_DATAFLOW_V3`; `DataMovementModel.coherentStorageDataflowV3()` |
| Contention-free control | `COHERENT_STORAGE_DATAFLOW_NO_CONTENTION_V3`; `coherentStorageDataflowNoContentionV3()` |
| Default target assignment | RANDOM planning, STATIC dispatch |
| Optional online assignment | explicit `CONTROL_READY_ONLINE_ASSIGNMENT_V1`, INVALID planning, STATIC dispatch; see the [online assignment contract](<ONLINE_DATAFLOW_ASSIGNMENT_V1_CONTRACT.md>) |
| Execution | NONE clustering, one logical Task per Job; SPACE_SHARED; no overheads; controlled NOOP CPU retries |
| File system | LOCAL, or the SHARED post-commit cached read defined below |
| Capture | `FILE_STORAGE_LIFECYCLE_V3`; `NetworkEvidenceConfig.storageLifecycleV3(B)`, where `B` is a record budget in `1..2147483647` |

OFF retains no event history and does not change the physics of the run. Ordinary non-online runs still keep the core file plan; online certification additionally requires a matching, complete lifecycle capture. The CPU online scheduler, RL_POLICY, and the original LOCAL planner cannot directly substitute for the target-assignment rules here.

Runnable examples are the [shared-capacity example](<../../experiments/configs/storage-dataflow-v3.json>) and the [isolated path-bottleneck control](<../../experiments/configs/storage-dataflow-isolated-v3.json>). They hold the inputs, the assignment configuration, and the storage and visibility rules fixed, varying only cross-flow capacity sharing; identical downstream source selection or completion times across the whole DAG cannot be guaranteed on that basis.

## Explicit Storage Resources

[DataflowStorageSpec](<../../simulator/src/main/java/org/workflowsim/data/v2/DataflowStorageSpec.java>) fixes the logical location `SOURCE:source`, declared via `PlatformProfile.Builder.sourceStorage(spec)`. The Workbench counterpart is `platform.sourceStorage`, which must contain exactly four fields:

| Field | Meaning and constraints |
|---|---|
| `attachmentHostId` | non-negative physical Host ID that must belong to the platform; when a topology is present, that Host must already be connected |
| `readBandwidthMbPerSecond` | read service capacity |
| `writeBandwidthMbPerSecond` | write service capacity |
| `networkBandwidthMbPerSecond` | storage NIC capacity shared by reads and writes |

All three bandwidths are finite positive numbers in decimal MB/s; after multiplication by `1000000` in binary64 they must be positive normal, finite B/s, with no integer rounding. The three are not required to be equal or ordered by magnitude. An unspecified resource is not an unlimited resource.

The Workbench attachment Host is chosen among its already-created `0..VM count−1` hosts; the Java platform API may declare a dedicated storage Host. Fat-tree `k` is even `2..32` for Java/the codec and even `2..16` for Workbench; the configuration must additionally satisfy the corresponding Host/edge capacity constraints.

`sourceStorage` must, and must only, appear together with a storage V3 model; invalid combinations are rejected before session creation. The legacy `PlatformProfile.StorageSpec`, the Harddrive timing estimate, and the [coherent V2](<COHERENT_DATAFLOW_V2_CONTRACT.md>) SOURCE meaning of an unlimited, fabric-bypassing sink are all unchanged. What is bounded is the service rate, **not storage space**; there is currently no capacity exhaustion, eviction, multi-store consistency, storage failure, packet-loss retransmission, or real-hardware calibration.

## Physical Resource Paths

[DataTransferFabric](<../../simulator/src/main/java/org/workflowsim/data/v2/DataTransferFabric.java>) freezes the physical VM→Host mapping and the storage attachment Host. The following orderings and resource multiplicities are part of the contract:

| Operation | Constrained resources in order |
|---|---|
| SOURCE→VM read | `STORE:source:READ`, `STORE:source:NIC`, the physical path's directed links, the destination `VM:id` |
| VM→SOURCE write-back | the source `VM:id`, the physical path's directed links, `STORE:source:NIC`, `STORE:source:WRITE` |
| LOCAL VM→VM read | the source VM, the physical path's directed links, the destination VM |

Endpoints on the same Host may have no fabric link between them, yet a SOURCE read/write still consumes VM endpoints and the storage service/NIC and cannot be treated as free local access. Even without a topology, the storage/VM resources and the complete physical VM→Host mapping are retained. The single-flow bottleneck is the minimum, across resources, of each resource capacity divided by its path multiplicity; the shared mode serves with checked max-min, while the contention-free control only removes cross-flow sharing and does not remove the read/write/NIC/path bottlenecks. Opposite-direction links are metered separately; the storage NIC is shared in both directions.

## Successful Outputs, Asynchronous Write-back, and Termination

`ALL_SUCCESSFUL_OUTPUTS_TO_SOURCE_V3` requires **all successful Task outputs** to reach the SOURCE, including unused/sink and zero-byte outputs; the required-output set must not be derived solely from consumed input edges.

- CPU completion is subject to the failure check first; a failed Task publishes no output and creates no write-back obligation.
- A successful output is first observed as visible on the actually executing VM, and the `(file,SOURCE)` write-back is started or joined within the same transaction. An existing SOURCE replica retains the earliest provenance.
- Positive-byte outputs traverse the service resources; zero-byte outputs commit metadata, with replica acquisition mode `ZERO_BYTE_OUTPUT`, and generate no positive flow or copy ordinal.
- `CLOUDLET_RETURN` and the Task/Job completion instants remain CPU outcomes and are not redefined as storage-commit instants. A VM may execute its next job while its outputs are being written back.
- Termination requires every requested Job in a final state, no active copies, no SOURCE waiters, and every successful output's SOURCE obligation fulfilled. A “zero active flows” count alone does not prove completion.
- Logical computation completion, the end of the whole simulation, and the output tail are distinct time boundaries; no I/O failure retries or TCP acknowledgments are invented.

## Input Visibility and Waiting

**LOCAL** uses the source rule of visible destination cache first, otherwise maximum isolated bottleneck rate, with stable location breaking ties. Required outputs are still written back, but downstream consumers may read a published VM replica before the SOURCE commit.

**SHARED** uses `COMMITTED_STORE_THEN_VM_READ_CACHE_V3`, not the legacy V1 SHARED timing estimate:

1. Once the control dependencies are satisfied, an input request for the fixed destination VM is initiated at the current observation.
2. While the SOURCE is not yet committed, `INPUT_WAITING_FOR_STORE` is recorded; even when the destination is the producer itself and the local output already exists, the commit must still be awaited.
3. After the SOURCE commits, the file is re-resolved: if a destination cache entry already exists, LOCAL; otherwise it is read from the SOURCE only, and a faster peer must not be selected to bypass storage.
4. The input is satisfied only once it has arrived at the destination; when all inputs are satisfied, `JOB_DATA_READY` is released exactly once, and only then may the CPU be entered.

Waiting for the SOURCE and waiting for a destination copy are distinct indices. A SOURCE commit resumes the affected `(job,file)` pairs within the same staged transaction, in a deterministic order over the affected requests, without re-entering the public `requestJob/advance` and without busy-polling. Multiple readers with the same destination may join one in-flight read. Waiters, preparation statistics, output obligations, paths, copy ordinals, and events all roll back with the failed transaction.

## Main Events, Metrics, and Aggregation Order

`DATA_STAGE_IN_MODELED` is recorded at **final data readiness**, with `transferUnit=LOGICAL_FILE_STORAGE_V3`; the initial statistics emitted before deferred resolution are incomplete.

- `modeledTransferSeconds` remains the sum of nominal isolated input-transfer seconds, not parallel-input wall time, output time, or service area. `NEW_COPY` and `JOIN_EXISTING` each count their ticket's full `isolatedSeconds`, not the remaining time.
- `requiredFileBytes`, `modeledTransferFileCount`, `newFileCopies`, and `joinedFileCopies` record referenced bytes, reference counts, and the numbers of newly created/joined input copies, respectively. Output copies are not mixed into the input counts, and they do not feed V1's `contentionTransferGroupCount`.
- `observedInputPreparationSeconds = dataReady - request` is a separately recorded observed preparation duration; overlapping SOURCE waits must not be added up file by file and passed off as a wall-clock breakdown.
- **Referenced bytes are aggregated in normalized FileId order**: each file's binary64 byte value is first multiplied exactly by its reference count, then converted to binary64, then accumulated in order; the total reference count per Job must not exceed `2147483647`, and the aggregated bytes must be finite.
- **Nominal input seconds accumulate in the order of the actual `INPUT_RESOLVED` events**; deferred resumption under SHARED may change that order, which must not be replaced by the referenced-byte ordering.
- The displayed exact referenced total directly accumulates the exact decimal equivalent of each file's binary64 value and does not masquerade as the rounded main-event aggregation. Output time is not squeezed into the CPU or input stage-in envelopes.

## Standalone V3 Evidence Format

[StorageLifecycleCodec](<../../simulator/src/main/java/org/workflowsim/data/v2/StorageLifecycleCodec.java>) fixes the role `storage-lifecycle`, the file name `<runId>.storage-lifecycle.json` (e.g. `result.storage-lifecycle.json`), the schema `workflowsim-storage-lifecycle-v3`, and the recording mode `FILE_STORAGE_LIFECYCLE_V3`. The role belongs to the outer artifact manifest; it is not a sidecar root field.

The root must contain exactly `schema,modelKind,recording,certificateScope,policies,capture,filePlan,fabric,events`, with `certificateScope=STORAGE_LIFECYCLE_NOT_FLUID_SERVICE_ACCOUNTING_V3`. `policies` must contain exactly eight fields:

| Field | Value |
|---|---|
| `fileIdentity` | `SCOPED_RESOLVED_WRITE_ONCE_FILES_V2` |
| `release` | `DEPENDENCY_READY_AT_OBSERVATION_V2` |
| `visibility` | `PER_FILE_SETTLEMENT_OBSERVATION_V2` |
| `selection` | LOCAL: `VISIBLE_LOCAL_OR_MAX_ISOLATED_RATE_STABLE_LOCATION_V2`; SHARED: `COMMITTED_STORE_THEN_VM_READ_CACHE_V3` |
| `sourceAccess` | `BOUNDED_STORE_READ_WRITE_NIC_HOST_ATTACHMENT_V3` |
| `sharing` | `SHARED_MAX_MIN` or `ISOLATED_PATH_BOTTLENECK`, matching the Kind |
| `inputAccess` | `LOCAL_VISIBLE_REPLICA_V3` or `COMMITTED_STORE_THEN_VM_READ_CACHE_V3` |
| `outputCommit` | `ALL_SUCCESSFUL_OUTPUTS_TO_SOURCE_V3` |

`recording={mode,maxTraceRecords}` and `capture={status,observedThrough,retainedRecords,droppedRecords}`; export requires `COMPLETE`, zero dropped records, an event count equal to retainedRecords, and no more than the budget. Complete means no history was dropped, not that the run has finished.

`filePlan` keeps the shared logical file/control plan. `fabric` holds **exactly five entries**: `locations,resources,vmHostAssignments,topology,sourceStorage`; the storage object contains exactly the four parameters above, the resource table must contain READ/WRITE/NIC consistent under conversion, and the physical VM placement must be complete even without a topology. The sharing flag denotes cross-flow capacity sharing, while `storeBackedInputs`/`inputAccess` denote the access policy; they must not be conflated.

The common event envelope is `sequence,observedTime,type,payload`; sequences are contiguous from 1, and observed times are monotonic and never exceed the capture watermark. Relative to the [file V2 format](<FILE_LIFECYCLE_V2_FORMAT.md>), V3's standalone syntax differences are:

| Event | V3 payload fields |
|---|---|
| `COPY_ADMITTED` | `copyOrdinal,fileId,bytes,sourceReplica,destination,purpose,ownerJobId,resources,standaloneRate,isolatedSeconds` |
| `INPUT_WAITING_FOR_STORE` | `jobId,fileId,referenceCount` |
| `OUTPUT_RESOLVED` | `jobId,taskId,fileId,resolution,copyOrdinal,source` |

`destination` is a typed location, `purpose=INPUT|OUTPUT`, and `ownerJobId` must correspond to the actual requester or the owner of a successful output. Output resolution is `ALREADY_STORED`, `ZERO`, `NEW_COPY`, or `JOIN_EXISTING`; the first two carry a null copyOrdinal. When an output joins an existing ticket, that event's source is the VM that completed successfully this time, while the ticket retains the original owner and the earliest origin. V2's `destinationVmId` admission field and continuous input-resolution syntax must not be grafted onto V3.

The shared strict JSON/numeric rules, zero and fractional bytes, unrepresentable clocks, copy-residual upper bounds, and provenance checks are defined in the [file format's numeric contract](<FILE_LIFECYCLE_V2_FORMAT.md#numeric-and-settlement-boundaries>); the closed V2/V3 entrances each select their own syntax, and an input document cannot extend the contract on its own.

## Validation, Replay, and Display Boundaries

[StorageLifecycleEvidence](<../../simulator/src/main/java/org/workflowsim/data/v2/StorageLifecycleEvidence.java>) is only an immutable carrier. Neither Java nor the [standalone Python validator](<../../scripts/_storage_lifecycle_audit.py>) invokes the production router, allocator, or coordinator to generate expectations; a complete capture may also not end in a half request, a half output publication, a half SOURCE resumption, or an omitted immediate-readiness operation. A legal standalone prefix may still contain active copies, input waits, or output obligations.

A complete bundle additionally requires all logical Tasks successful, every compute attempt covered, agreement with the Jobs/Tasks, main events, and the physical platform, a quiescent state, and every required output arrived at the SOURCE. The V3 context check verifies the modern failure parameters and VM/depth coverage, that turning failure off cannot produce a failure, that actual/required retries stay within budget, and that STATIC/NOOP retries do not migrate the VM; both the ON and OFF core configurations are constrained. These are necessary-configuration and causal checks; no random sampling is replayed, and the standalone audit does not pretend to know the outer scheduling/failure configuration.

Truncated, missing, or contradictory evidence is rejected before artifact I/O; recomputing a hash cannot conceal source, destination, CPU, policy, or write-back contradictions. **Certification does not include the unrecorded per-interval fluid service area.** V2's acceptance contract does not change because of this.

Rebuild retains the mode, the core plan, and the physical storage; every sidecar field is compared exactly under `/storageLifecycle/...`, with no new exemption for mutable variables. Normal and standalone reports reuse the same validated snapshot through the dedicated `getStorageLifecycle()/getDecodedStorageLifecycle()` paths, not the V1/V2 getters. Displayed numbers are exact text; copy/resource/Job/event previews are capped at **64/64/64/128** entries respectively, nested lists at 12 items, and labels at 256 characters. OFF, zero positive copies, a complete run prefix, a missing projection, and no valid result are each handled separately, without requesting an additional sidecar.

See also the [rerun difference contract](<../experiments/RERUN_DIFF_CONTRACT.md>), [Workbench](<../getting-started/WORKBENCH.md>), the [capability matrix](<DATAFLOW_CAPABILITY_MATRIX.md>), and [build and checks](<../getting-started/BUILD.md>).
