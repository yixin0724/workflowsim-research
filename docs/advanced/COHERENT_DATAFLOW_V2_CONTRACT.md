# Coherent File Dataflow V2

**English** · [简体中文](<COHERENT_DATAFLOW_V2_CONTRACT.zh.md>)

## Applicable Models and Configuration

`COHERENT_FILE_DATAFLOW_V2` and `COHERENT_FILE_DATAFLOW_NO_CONTENTION_V2` uniformly govern per-file demands, real sources, resource paths, replica visibility, and the CPU input barrier. The two share the same lifecycle and the same single-flow bottlenecks; they differ only in whether capacity is shared between concurrent flows.

| Configuration item | Supported range |
|---|---|
| File system | LOCAL |
| Default target assignment | RANDOM planning + STATIC dispatch |
| Optional target assignment | explicit `CONTROL_READY_ONLINE_ASSIGNMENT_V1` + INVALID planning + STATIC dispatch |
| Tasks and execution | NONE clustering, one logical Task per Job; SPACE_SHARED; no overheads; controlled NOOP retries |
| Network | endpoints, or Fat-tree paths over the physical VM→Host placement |
| SOURCE | an explicit unlimited sink that bypasses the fabric; not a bounded storage device |

Java's Fat-tree configuration restricts even `k=2..32`, and Workbench restricts even `k=2..16`. All combinations and numeric support domains are checked at the configuration/run boundary. A bounded SOURCE and required-output write-back use [storage V3](<STORAGE_DATAFLOW_V3_CONTRACT.md>) and must not silently change V2's meaning. Additional constraints for online assignment are in the [online assignment contract](<ONLINE_DATAFLOW_ASSIGNMENT_V1_CONTRACT.md>).

## Logical File Plan

The contract mark of [DataflowFilePlan](<../../simulator/src/main/java/org/workflowsim/data/v2/DataflowFilePlan.java>) is `SCOPED_RESOLVED_WRITE_ONCE_FILES_V2`.

- File identity is `(workflowInputIndex, existingResolvedFileKey)`, not the FileItem object, the Job attempt, or the VM holding a replica. The keys already formed by the resolver are retained; workflow ownership is not re-guessed by splitting file names.
- Each file has at most one logical Task producer; the producer must be a control ancestor of the consumer, not necessarily a direct parent. In-place read/write by the same Task and ambiguous multiple producers are rejected.
- Sizes use the finite non-negative binary64 byte values retained from the resolved results; retained descriptions of the same identity must agree, with no epsilon. A zero-byte file is still one input reference and cannot be treated as having no input.
- This is the **post-resolution file plan**, not a raw-declaration checker. If the DAX parser has already merged descriptions under the first-wins rule, the plan cannot recover the discarded raw size differences, nor does it claim they were validated.
- The plan freezes logical IDs, workflow ownership, dependencies, file metadata, and reference counts before clustering empties the resolved Task list, and it keeps no mutable Task/FileItem.
- `demandsFor(taskSubset)` deduplicates by logical file while retaining reference counts and the consumer set; inputs produced by producers within the selection are internal demands. A retry re-derives the demands from the original logical Task and does not scan historical parent-Job outputs.
- The exact referenced total can be expressed with BigDecimal, but it is not the unique transfer payload; the runner's per-Job binary64 aggregation and reference counts remain bound by their finite support domains, and overflow never defaults to zero.
- Graph and producer-ancestor validation use iterative traversal, without building an all-pairs ancestor matrix. The pure plan API allows empty collections, which does not mean the run entry allows empty workflows or arbitrary clustering modes.

## Replicas and Provenance

[DataReplicaState](<../../simulator/src/main/java/org/workflowsim/data/v2/DataReplicaState.java>) uses [typed locations](<../../simulator/src/main/java/org/workflowsim/data/v2/DataLocation.java>) and is independent of the legacy ReplicaCatalog.

- VM and SOURCE are distinct identity types; an unknown location cannot be taken by default as a valid holder.
- A replica keeps the earliest visible time, the acquisition mode, the root producing Task/Job attempt/location/observation time, and the direct copy origin with its ticket number. Later repeated publications retain the first replica's identity and provenance.
- A single Job attempt must correspond to one physical VM and one completion observation. A failed Task publishes no output, but failure does not delete inputs that have already arrived; conflicting callbacks are rejected before any state change.
- An active copy ticket binds the file, the selected visible source replica, the destination, and the admission time. It is not a promise that the destination replica is already visible, and it is not an independent proof of network-service correctness.
- There is at most one active copy per file and destination at a time. Overlapping requests first look up and join the existing ticket, without re-selecting a source or transferring twice; different destinations are still admitted separately.
- A positive copy settles at an observation time strictly later than its admission. A zero-byte file still needs a valid visible source, but it resolves immediately and consumes no positive copy ordinal.
- Snapshots and reads do not advance the clock. An illegal operation must not change the observation watermark, visible replicas, completion counts, or copy ordinals; a legitimate failure outcome is itself an observation that advances the watermark.

## Sources and Paths

[DataTransferFabric](<../../simulator/src/main/java/org/workflowsim/data/v2/DataTransferFabric.java>) and [ReplicaTransferSelector](<../../simulator/src/main/java/org/workflowsim/data/v2/ReplicaTransferSelector.java>) produce one immutable source/path/isolated-rate decision, shared by timing estimation, admission, and evidence.

1. A currently visible local replica wins; otherwise the visible source with the greatest isolated path-bottleneck rate is selected, with ties broken by the stable location order. Having no source is not zero-cost success.
2. A VM-to-VM path is charged on the source endpoint, the physical topology links, and the destination endpoint; two different VMs consume two endpoints even on the same Host. A genuinely same-VM local reference needs no network service.
3. The V2 external SOURCE does not go through the fabric and carries no sink capacity; a missing physical link/endpoint capacity and an explicitly unlimited SOURCE are distinct states. V2 offers no semantics for writing back to a bounded SOURCE.
4. Capacities must be positive normal finite binary64 values; for a positive transfer, the isolated seconds, the absolute completion time, and successor observations must be representable and must advance the clock. Underflow is not treated as a zero transfer, and a slower source is not silently re-selected over numeric trouble.
5. The single-flow path check is not a shared-solver certificate. The runner separately checks live allocations, finite service areas, settlement residuals, and future times; failures roll back through the staged transaction.

The Fat-tree structure, deterministic routing, and sharing mechanics are described in the [topology notes](<../research/FAT_TREE_DESIGN.md>). There is no adaptive routing and no source selection based on instantaneous queue states.

## Lifecycle and Compute Gating

- **Release:** `DEPENDENCY_READY_AT_OBSERVATION_V2`. Once the workflow has arrived, the control dependencies are satisfied, and the destination VM is fixed, input demands are initiated at the current observation; it does not retrace an earlier completion time of a parent task, and it performs no producer-eager prefetch.
- **Visibility:** `PER_FILE_SETTLEMENT_OBSERVATION_V2`. Each file becomes visible when the kernel processes its settlement, retaining the distinction between the internally valid completion and the externally observed time; run state is not derived from optional traces.
- **Merging:** overlapping inputs for the same logical file/destination share one copy, while waiters and reference counts are kept separately.
- **CPU barrier:** the CPU is committed only after every required file is visible at the fixed destination and the control dependencies are satisfied. A successful output is published at the actual completion observation, with the location taken from the actual Job VM; predicted Task completion times are not read.
- **Contention-free control:** it removes cross-flow capacity sharing, not the single-flow endpoint/link bottlenecks. Service-time changes may change later caching and source selection; neither a fixed path history for the complete DAG nor monotonic completion times can be guaranteed on that basis.

## Evidence and Re-verification

V2 uses the standalone `FILE_LIFECYCLE_V2` sidecar. It checks per-file identity, provenance, requests, copies, visibility, and CPU causality, and it **does not certify unrecorded per-interval fluid service accounting**. A V1 group-level ledger cannot be relabeled into per-file proof.

Complete artifact export requires a complete capture; an insufficient budget is rejected before artifact I/O but does not change the physical execution. With recording OFF in the default non-online assignment mode, the core file plan is still retained, which does not mean zero network. See the [lifecycle format](<FILE_LIFECYCLE_V2_FORMAT.md>), the [rerun difference contract](<../experiments/RERUN_DIFF_CONTRACT.md>), and the [capability matrix](<DATAFLOW_CAPABILITY_MATRIX.md>) for details.
