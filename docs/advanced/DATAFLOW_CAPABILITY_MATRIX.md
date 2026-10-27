# Dataflow/Network Capability Matrix and Research Boundary

This matrix states the supported scope of the current network, file dataflow, storage, and target assignment. All quantities belong to the declared simulation model and stand for no real-platform monitoring, cloud bills, or universal algorithmic superiority.

| Registered external scheduler (2026-10-26) | plain online track (INVALID/INVALID) through the
[algorithm registry](<../../simulator/src/main/java/org/workflowsim/experiment/registry/AlgorithmRegistry.java>) |
self-declared contract validated fail-fast; label in the manifest; see the
[registered-algorithms section](<../algorithms/CATALOG.md>) |

## Physical Models and Target Assignment

| Branch | Input/storage semantics | Copying and CPU | Optional target assignment |
|---|---|---|---|
| The five original V1 models | Keep their respective existing abstractions; the newer versions must not silently reinterpret them | Keep the existing group-level/staged input rules | Still follow the original support matrix; CPU online scheduling/RL must not be taken directly as a new network strategy |
| COHERENT_FILE_DATAFLOW_V2 and its no-sharing control | LOCAL; SOURCE aggregates explicitly without limit and bypasses the fabric; optional actual Fat-tree paths | Per-file real sources, frozen paths, same-destination merging, observed visibility, and a CPU input barrier | Default RANDOM/STATIC; or explicit control-ready assignment + STATIC |
| COHERENT_STORAGE_DATAFLOW_V3 and its no-sharing control | Bounded read/write/shared-NIC and access-Host resources; LOCAL, or SHARED cache reads after the SOURCE commit | Every successful output (including unused/zero-byte ones) commits to SOURCE, the CPU may overlap with the write-back, and the ending waits for the drain | Same as above; a NOOP retry of the same logical Task keeps the original VM |

Coherent V2/V3 require one Task per Job, NONE clustering, SPACE_SHARED, no overheads, and controlled NOOP retries. The actual Java topology and the Workbench scale caps are checked under the existing contracts; arbitrary mixing across algorithm tracks is not allowed.

Online target assignment is the independent configuration `CONTROL_READY_ONLINE_ASSIGNMENT_V1`, not a new physical Kind and not a CPU remapper. Passive observation reads the committed state; the nominal input/CPU reservation policy selects an actually compatible VM at control readiness, and only then does input preparation happen. For the complete specification see the [online assignment contract](<ONLINE_DATAFLOW_ASSIGNMENT_V1_CONTRACT.md>).

## Evidence and Validation Scope

| Evidence | What is verified | What is not claimed |
|---|---|---|
| V1 group ledger | Group/resource service, FCT, and the corresponding context under the declared model | Per-file protocols, TCP connections, or actual hardware throughput |
| FILE_LIFECYCLE_V2 | File identity, source, path, admission, merging, settlement/visibility, and CPU causality | Unrecorded per-interval fluid service areas |
| FILE_STORAGE_LIFECYCLE_V3 | All of the above, plus restricted SOURCE, input waiting, and the obligations of all successful outputs | Complex storage coherence, capacity/eviction/failure protocols |
| Online action main events | Assignment, PE/MI, CPU reservations, nominal input boundaries, logged scores, and the fixed retry VM | Exact active-flow balances, a full replay of future contention, or global optimality |

Both the Java side and the independent Python side have validators. Online attestation requires a matching full-lifecycle capture; a raw Java run may still disable recording with the physics and the actions unchanged, but a complete online attestation cannot be exported from it. Missing, truncated, and field-contradictory state is rejected; nominal progress drift within the allowed range still counts among the exact-rerun core quantities and gains no exemption through a hash update.

Regular Workbench and standalone reports use only the same verified snapshot. The physical and action panels coexist, numbers are displayed as exact strings; the action/candidate previews have explicit caps, and no extra sidecar is read. For the complete files and new configuration examples see the [Workbench guide](<../getting-started/WORKBENCH.md>).

## Energy dimension

- Per-VM power fields (`idlePowerWatts`/`maxPowerWatts` on `VmSpec`, `max ≥ idle ≥ 0`, both
  finite) are optional per VM; a platform without them is a legally declared unpowered run whose
  energy metrics are all zero. The linear power model is a declared simulation model, not a
  measured power envelope, and heterogeneous per-VM values (including mixed powered/unpowered
  VMs) remain per-VM explicit in each manifest.
- Every manifest carries a mandatory `energyMetrics` section (four summary fields plus the
  per-VM `vmEnergyJoules` map; all-zero is the legal unpowered case), and the platform section
  serializes each VM's power fields so the dimension is recomputable from the manifest alone.
- The independent Python
  [auditor](<../../scripts/verify-study-evidence.py>) recomputes the conservation law
  conditionally: powered runs must satisfy `totalEnergyJoules = Σ_vm P_idle × T_sim +
  (P_max − P_idle) × T_busy` recomputed from the manifest's own busy intervals; unpowered runs
  are skipped by the energy branch (backward compatible).
- [Algorithm declarations](<../../simulator/src/main/java/org/workflowsim/experiment/registry/AlgorithmDeclaration.java>)
  gain `energyAware`/`powerModelRequired`/`energyObjectiveWeight`; declaring
  `powerModelRequired` on a profile without power fields is rejected fail-fast at registration,
  not silently downgraded. See the [energy dimension design](<ENERGY_DIMENSION_DESIGN.md>)
  for the survey anchors and the v2 DVFS/carbon reservations.

## Performance Optimizations Do Not Change the Semantics Above

A transaction copies only the active working set and the changed replica rows; the immutable history index and the terminal summaries are shared, and neither the service allocator, the floating-point arithmetic, the sources/paths, the event order, nor the assignment policy is changed. Wide sets of simultaneously active tasks can still incur near-quadratic cost, and the history-copy optimizations must not be equated with linearizing every input. See [state structure and performance measurement](<DATAFLOW_STATE_COPY_PERFORMANCE.md>).

## Unimplemented Capabilities

Packet-level TCP/retransmission/ECN, switch queue implementations, adaptive routing, complex coherence/multi-storage protocols, bounded cache eviction, neural-network trainers, real-cluster calibration, and cloud-provider billing are outside the current implementation. Passing validation or improved performance within these boundaries must not be read as these capabilities being present.
