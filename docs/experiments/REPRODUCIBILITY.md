# Reproducible Runs and Evidence Recording

An interpretable run is defined jointly by **its inputs and their order, the platform, the complete configuration, the random seed, and the actual implementation identity**. Reproducibility does not mean real-platform calibration, WfInstances trace replay, or universal algorithmic superiority. Day-to-day operation is covered in [Workbench](<../getting-started/WORKBENCH.md>); this document states what must be recorded, and what recording cannot prove.


## Configuration and Defaults

Research code enters the standard runner through the immutable [SimulationConfig](<../../simulator/src/main/java/org/workflowsim/utils/SimulationConfig.java>) and `PlatformProfile`. Do not rely on static parameters left behind by the previous run.

| Condition | Current conventions |
| --- | --- |
| Root seed | The Java configuration default is 0; the Workbench default seed list is `[42]`. Studies must explicitly record every actual seed |
| Algorithm and file system | Java defaults to FCFS, INVALID planning, and SHARED; other combinations must be selected explicitly and pass validation |
| Data movement | Defaults to `LEGACY_WORKFLOWSIM_V1`; switching the model must not be treated as an implementation detail that leaves results unchanged |
| Runtime conversion | Defaults to `runtimeReferenceMips=1000.0` and `runtimeScale=1.0`; seconds convert to MI as `max(100, floor(runtime * referenceMips * scale))` |
| Kernel tick | `cloudSimMinEventIntervalSeconds` is a positive finite value, defaults to 0.1 simulated seconds, and affects event release and completion; it is not a network-latency calibration |
| Arrival times | The inputs and the equally long arrival table are known before startup and released at seconds counted from simulated time zero; all zeros by default |
| Deadline | 0 means not requested; a positive signed-long threshold only observes `simulationEndSeconds` and does not change scheduling, admission, retries, or termination |
| Overheads and failures | Off by default; when enabled, record the immutable distribution specifications, depth/VM layout, and retry budget — never reuse an already-consumed random generator |
| Execution domain | The standard runner requires NONE clustering, SPACE_SHARED, and PE-compatible placement, dispatching at most one Job per VM at a time |

Deadline comparison keeps the signed-long threshold and the exact binary64 value of the end time: compare first, then convert slack/tardiness into display values, without epsilon; not-requested, timed-out, and unfinished workflows must each be interpreted on their own. It is not an implicit deadline on logical task completion times.

Input formats, normalization, and source scope are covered in the [datasets note](<../../datasets/README.md>). DAX permits no DOCTYPE/external entities; an external DTD not included in the input hash cannot change the model. Preserve the original input; retaining only the file name or the parsed graph is not enough.

## Seeds, Sessions, and Retries

[SimulationRandom](<../../simulator/src/main/java/org/workflowsim/utils/SimulationRandom.java>) derives separated deterministic random streams keyed by stable component names; [SimulationSession](<../../simulator/src/main/java/org/workflowsim/utils/SimulationSession.java>) installs the seeds and models before a serial run and cleans up the global state it owns at the end. Concurrent sessions in the same JVM are rejected.

With identical code, inputs, platform, and configuration, repeated runs should rebuild the same simulated random process; the sampling order inside a component can still vary with scheduling, release batches, or retries. **A shared root seed is not event-keyed common random numbers (CRN)**, nor does it by itself constitute independent replicates or a legitimate inference of treatment effects. Design labels, actual seeds, and the statistical interpretation of batch runs are covered in the [campaign guide](<CAMPAIGNS.md>).

Overhead distributions are keyed by depth; the formation of batches delayed by the workflow engine depends on the actual release events. Keep the configuration together with the actual samples recorded in the events, such as queueing/post-processing draws; do not mistake same-named random streams under different algorithms for a one-to-one per-event correspondence. Custom extensions that use random sources outside `SimulationRandom` must declare their seeds, state, and reproduction tests separately.

Standard failure recovery uses `FTCLUSTERING_NOOP` and `MONITOR_NONE`; enabling failures requires a positive total retry-Job budget and a valid distribution layout. Budget exhaustion fails explicitly and is not the same thing as a workflow-failure sample that ended normally. Every retry gets a new Job identity, and the failed parent attempt is retained; failed outputs cannot become a readable copy for successors. Examples and layout constraints are in the [Java API guide](<../getting-started/CODE_CONFIG_EXPERIMENTS.md>).

## Workload, Platform, and Timing Conventions

[TaskExecutionModel](<../../simulator/src/main/java/org/workflowsim/utils/TaskExecutionModel.java>) converts matrix seconds into positive-integer single-PE MI via `Math.round(seconds * vmMips)`. When a matrix exists, no fallback on missing coordinates is allowed; MI×PE×1,000,000 must be representable as a signed long. `TaskOutcome.lengthMi` keeps the original normalized length, while `effectiveExecutionLengthMi` is the actual computational workload of the current attempt, excluding stage-in.

The Job envelope and the Task computation window must not be conflated: legacy/fixed-endpoint models can count effective input latency into the Job envelope, while pre-execution/coherent models prepare the inputs before the CPU starts. Task timing uses the effective workload; whether it coincides exactly with the whole Job window is stated by the corresponding fields. Failures are decided at attempt-completion boundaries; they are not a calibrated mid-run crash or partial-write model.

`simulationEndSeconds` and `makespanSeconds` are the same simulation end clock. `logicalTaskCompletionSeconds` is available only when all logical Tasks succeed, taking the maximum over Tasks of each Task's first successful Job-envelope completion time; the difference `terminalLifecycleTailSeconds` may include tails such as output write-back and is not pure CPU or scheduler time.

**Event-stream recomputation boundary (2026-10-23 contract revision, implemented after user adjudication; the original 2026-10-19 statement remains valid for old evidence)**: events v1 is extended with two event types. The first is `SIMULATION_TERMINATED`: the natural-completion path records it exactly once after the watchdog and quiescence checks pass, with the event time and the `simulationEndSeconds` attribute both equal to the termination clock — `makespanSeconds`, `terminalLifecycleTailSeconds`, and the deadline-slack family can therefore be independently recomputed from the event stream; the abort/failure path deliberately omits this event, and its absence marks an incomplete execution. The second is `JOB_EXECUTION_STARTED`: each Job execution instance is recorded exactly once ahead of its first `TASK_EXECUTION_MODELED`, carrying `jobEnvelopeStartSeconds`, `effectiveDataStageSeconds`, `requestedDataStageSeconds`, and `modeledJobStartSeconds` (= envelope start + effective-stage seconds, with both exactly equal) — the decision-to-start and waiting-time families no longer depend on `exactJobTiming` coincidences. The independent auditors run the cross-checks: exact equality of the termination clock with the manifest end value, per-instance uniqueness, derived arithmetic, and full coverage of execution instances; pre-revision evidence (without the new events) keeps its originally declared boundary, treats the manifest result/metrics tables as authoritative, and is not judged as failed. The new events are at the same time core data of the exact rerun comparison; a rerun of pre-revision evidence against the new code legitimately differs in the event array — see the [rerun difference contract](<RERUN_DIFF_CONTRACT.md>).

[PlatformProfile](<../../simulator/src/main/java/org/workflowsim/platform/PlatformProfile.java>) records Host/VM specifications, optional pins, and the capacity-feasible pre-check mapping; placements after actual VM creation must also agree with the pre-check. Use one Host per VM when Host-placement factors must be excluded. Co-location is a resource-configuration condition and does not mean that Host CPU interference, migration, or real-infrastructure utilization has been modeled or calibrated.

`CostModel.DATACENTER` and `CostModel.VM` are abstract pricing scopes; the latter requires explicit per-VM pricing. Processing cost covers the CPU envelope cost of every returned attempt and the bandwidth cost of the declared files in continuous decimal MB, failures and retries included; memory/storage prices are declared but not billed, and there are no provider-bill rounding or real network-traffic accounting semantics. The full metric definitions are in the [algorithm, execution, and metrics contract](<../algorithms/CONTRACTS.md>).

## Dataflow and Recording Modes Are Also Run Conditions

The current physical models consist of five V1 kinds, two coherent file V2 kinds, and two bounded-storage V3 kinds. V1 pre-execution, coherent V2/V3, and ordinary online scheduling differ in compatibility; the full combinations are in the [model capability matrix](<../advanced/DATAFLOW_CAPABILITY_MATRIX.md>).

- V2 requires LOCAL; V3 can use LOCAL/SHARED and must declare the bounded SOURCE's location and its read/write/NIC capacities.
- Coherent without assignment enabled uses RANDOM/STATIC; explicit online assignment uses INVALID/STATIC with a versioned policy, binding once control-ready and preparing the inputs afterwards; it must not be conflated with ordinary CPU online scheduling.
- V3 SHARED input waits for the SOURCE commit and cannot bypass it even if the target holds a copy in cache; every successful output, including unused sinks and zero-byte files, must fulfill its commit obligation. A CPU return is not storage completion.
- Capture budgets do not change the physical run, but they decide whether the evidence is sufficient. The V1 group ledger may explicitly retain a truncated prefix; V2/V3 lifecycle exports require complete capture, and online-action attestation likewise requires complete and matching lifecycles.
- OFF does not mean there is no network. The coherent core `dataflowPlan`, the V3 storage conditions, and the enabled assignment configuration are still part of the run identity and must not be ignored because an optional sidecar is turned off.

The raw Java OFF run, the complete lifecycle certificates, the online-action boundary checks, and the per-interval fluid service proofs are different layers; passing one of them must not be interpreted as passing them all. Field and output rules are in [Workbench](<../getting-started/WORKBENCH.md>).

## Evidence Bundles and Component Identity

[ExperimentArtifactWriter](<../../simulator/src/main/java/org/workflowsim/experiment/ExperimentArtifactWriter.java>) writes manifest v4, metrics v2, and events v1, with provenance at v3; when recording is enabled, matching `network-ledger`, `file-lifecycle`, or `storage-lifecycle` sidecars are written as well. A manifest alone is not a complete bundle.

The [manifest writer](<../../simulator/src/main/java/org/workflowsim/experiment/ExperimentManifestWriter.java>) records input order/hashes, the arrival table, matrices, algorithm tags and contracts, data movement, failures/overheads, platform topology/storage, actual placement, Job/Task results, and the observation scope. The current execution identity is `WORK_CONSERVING_TASK_EXECUTION_V2`; old evidence lacking this field must not be back-filled as a current execution record.

[Provenance](<../../simulator/src/main/java/org/workflowsim/experiment/ExperimentProvenance.java>) distinguishes core components from optional research components and records the source tree, module/root POMs, binaries, classpath string digests, and working directory where obtainable; JVM/OS information is kept separately in the manifest's `runtime` object. Component roots are derived from the classes' code sources, not guessed from the working directory; when source code is unavailable, the missing state is recorded explicitly.

These content hashes are not Git commit numbers, author authentication, or release signatures; the classpath string hash is not complete proof of all external dependency contents either. A research handover must additionally retain version information for build dependencies and for external policies or resource inputs. The protocol logical ID and the hash of the protocol's original bytes bind the study identity; current and compatibility identities are in the [network study protocol notes](<NETWORK_STUDY_PROTOCOL_REVISIONS.md>).

## Validation, Rerun, and Retention

- **Integrity validation**: checks schema, relative references, hashes, counts, and context; the validators can read supported older manifests but do not automatically fill in missing semantics.
- **Actual rerun**: rebuilds the configuration from the recorded conditions, re-executes under the current code, and compares; it is not restoring a historical process, reusing cached results, or substituting events for the simulation. Concrete support and the precise whitelist are in the [rerun difference contract](<RERUN_DIFF_CONTRACT.md>).
- **Identity differences**: JVM/OS, source identity, paths, and wall-clock nanoseconds may change and must be recorded. Simulation time, input identity, algorithm tags, execution semantics, and non-whitelisted fields do not gain tolerance or exemptions on that account.

All Java/Python/browser commands are collected in [build and verification](<../getting-started/BUILD.md>). Passing tests only show that the corresponding assertions hold; they do not show real-world calibration or algorithmic superiority on arbitrary inputs.

Retain the inputs, protocol original bytes, complete evidence bundles, build/component identity, and the validation commands; the original evidence must not be rewritten during validation or a rerun. New runs use a separate output location. Build-directory cleanup and research-evidence retention are different operations; outputs whose retention has not been decided, and archived raw data, must not be treated wholesale as deletable cache.
