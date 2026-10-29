# Rerun and Exact Difference Comparison

This capability answers: **for a valid piece of manifest v4 evidence, once it has been re-run under the current code, do the non-exempt JSON core quantities agree?** It is the rebuilding, re-simulation, and diff checking of a single run — not event replay, checkpoint resumption, a cache hit, or the automatic re-execution of an entire study.

Report regeneration is also distinct from the rerun: Workbench's `report` only generates HTML from validated evidence and does not execute the simulator, while the rerun here re-executes the standard `SimulationRunner`. Model applicability is covered in the [algorithm contract](<../algorithms/CONTRACTS.md>) and the recording requirements in the [reproducibility guide](<REPRODUCIBILITY.md>).


## Command and Input Conventions

Run from the project root, replacing the placeholder paths:

```bash
mvn -pl :workflowsim-workbench -am compile exec:java \
  -Dexec.mainClass=org.workflowsim.experiments.rerun.RerunDiffExecutor \
  '-Dexec.args="/absolute/original-run" "/absolute/new-rerun-output"'
```

The [CLI](<../../experiments/src/main/java/org/workflowsim/experiments/rerun/RerunDiffExecutor.java>) accepts, and only accepts, the original run directory and an output directory. The output must not exist or must be empty and should be independent of the original evidence; no new artifacts are written back into the original run.

The [evidence reader](<../../experiments/src/main/java/org/workflowsim/experiments/rerun/RerunEvidenceReader.java>) prefers `result.manifest.json`; if that is absent, it accepts the single `*.manifest.json` in the directory and refuses when no candidate or more than one candidate exists. Metrics, events, and the declared optional sidecars are located through manifest references, not by guessing file names to fill the gaps.

Only manifest v4 is supported. v2/v3 can be read by the corresponding integrity validators but cannot be silently upgraded here for a rerun. A standalone manifest does not replace a complete bundle; a declared sidecar that is missing or invalid results in `EVIDENCE_INVALID`.

## Execution and Snapshot Boundaries

```text
读取原包并校验
  → 按输入身份定位文件
  → 从验证后的 manifest 重建配置和平台
  → 用当前标准运行器重新仿真
  → 写出并校验新的证据
  → 精确比较核心量
  → 输出 JSON 与 Markdown 判定
```

The reader uses the manifest snapshot returned by the validator; the unvalidated pre-read tree is not taken as the basis for rebuilding. This snapshot is not a CloudSim checkpoint and not a filesystem snapshot: the manifest tree of `RerunEvidence` is used under a read-only convention, while metrics/events/sidecars are still read from their paths at comparison time. Callers must not modify the tree, and the contents of the original directory must also remain unchanged during validation and the rerun; this tool provides no concurrent-write isolation or archival attestation.

## Input Resolution Rules

[RerunInputResolver](<../../experiments/src/main/java/org/workflowsim/experiments/rerun/RerunInputResolver.java>) preserves the following order:

1. If the run sits at `<study>/runs/<run>`, try `<study>/inputs/<original file name>`.
2. Try the input path recorded in the manifest.
3. If the path can be re-based on the original `provenance.execution.workingDirectory`, it is relocated to the **current process working directory**. This is not automatic discovery of the repository root; that is why this guide requires launching from the project root.

As soon as a regular file is found at any level, its SHA-256 and byte count are verified immediately; on mismatch the result is `INPUT_HASH_MISMATCH`, and no other candidate file may be probed to paper over the inconsistency. If every candidate misses, the result is `INPUT_UNRESOLVED`, and the report keeps the candidates that were tried. This CLI has no additional `--input-root` option; the options of the standalone Python auditors cannot be carried over.

Input order, hashes, sizes, formats, versions, and normalization information still take part in the core comparison. The path-text exemption can only be applied after this identity-verification chain; it is not a license to substitute inputs at will.

## Configuration Rebuilding Rules

[ManifestConfigRebuilder](<../../experiments/src/main/java/org/workflowsim/experiments/rerun/ManifestConfigRebuilder.java>) translates the preserved configuration and platform: algorithms, file systems, seeds, runtime conversion, the kernel tick, the arrival table, deadline, matrices, failures/overheads, Host/VM/prices, topology, and storage. Input paths switch to the identity-verified resolution result; nulls and optional fields follow the schema/compatibility rules, and no new study parameters are guessed from the current user environment.

- The nine physical models are rebuilt from their recorded kind and parameters; V2/V3 must not be rewritten as V1, and an unrecorded run must not replace an enabled capture.
- Absent `networkEvidence` explicitly means OFF; when present, its mode and budget are restored. A present `dataflowAssignment` is likewise restored in full, not ignored on the grounds that the algorithm contract declares it identity information.
- The V3 `sourceStorage` must be present and match the model; a coherent topology must additionally satisfy the numeric domain. The rebuilt VM→Host pre-check mapping must equal the recorded values.
- When a parameter, enum, or combination is no longer accepted by the current code, the report is `RECONSTRUCTION_REJECTED`; algorithms, models, or capacities are never swapped in silently. Unrecorded external-policy processes or arbitrary Java objects are not restored from the manifest.
- The current execution generates the current `executionSemantics`. An old v4 that lacks this identifier may still enter rebuilding, but this is not restoring an old engine; differing old/new declarations are reported as core divergences even when some numeric values happen to coincide.

Input, rebuilding, or run failures must not be treated as consistency success. Classified simulation-execution exceptions and exhausted retry budgets also map to `RECONSTRUCTION_REJECTED`, with the cause noting the execution phase; this is an incompatible result that needs to be exposed. Run-phase law rejections follow the same mapping (BAT-V4.1, 2026-10-29): when the rebuilt platform violates a current construction-time law — for example a tiered-and-carbon evidence bundle rebuilt as a power-stripped platform with surviving per-VM carbon intensities, which the report constructor rejects as a half declaration — `IllegalArgumentException`, `IllegalStateException`, and `SimulationConfigurationException` raised from the execution phase are reported as `RECONSTRUCTION_REJECTED` with both report files written, never as an uncaught usage-class exit that writes no report at all.

## Network, Lifecycle, and Online Actions

| Recording mode | Validation and capture requirements | Exact-comparison namespace |
| --- | --- | --- |
| OFF | No optional sidecars; the coherent file plan and the V3 storage declarations still remain | The corresponding manifest core fields |
| `FLUID_GROUP_LEDGER_V1` | The roles, hashes, contents, and run context of the V1 group ledger; a truncated prefix may be explicitly marked | Everything under `/networkLedger/...` |
| `FILE_LIFECYCLE_V2` | The V2 file lifecycle and run context; only complete captures are accepted | Everything under `/fileLifecycle/...` |
| `FILE_STORAGE_LIFECYCLE_V3` | The V3 storage lifecycle, commit/output obligations, and run context; only complete captures are accepted | Everything under `/storageLifecycle/...` |

The three sidecar roles are mutually exclusive and cannot substitute for one another. The `dataflowPlan` of V2/V3-OFF runs and V3's `platform.sourceStorage` are still core quantities. Storage validation distinguishes the CPU return from the commitment of all outputs, including zero-byte files and unused sinks, and checks the applicable failure/retry causality; it does not thereby claim that the probabilistic failure process or the random numbers have been independently replayed.

The online-assignment configuration, the `dataflowComputeRequests`, the `DATAFLOW_VM_ASSIGNED` events, and all their action attributes are core quantities. Online attestation requires the complete matching V2/V3 lifecycles; its scope is the assignment, the nominal bounds, and the recorded scores — not exact live-flow balances, future contention, or global optimality. Even if a changed score would still pass the boundary checks, the actual rerun must still compare exactly and report the diff.

When the V1 ledger on either side is TRUNCATED, the report adds `networkEvidenceCoverageNote`: even an `IDENTICAL_CORE` only says that the retained records and the aggregated observations agree; an unknown suffix cannot be attested. When both sides are OFF or the capture is complete, this placeholder field is not added. The complete V2/V3 lifecycle is likewise not a per-interval fluid service-area certificate.

Format and certification scope are in the [V1 ledger](<../advanced/NETWORK_LEDGER_FORMAT.md>), [V2 lifecycle](<../advanced/FILE_LIFECYCLE_V2_FORMAT.md>), [V3 storage](<../advanced/STORAGE_DATAFLOW_V3_CONTRACT.md>), and [online assignment](<../advanced/ONLINE_DATAFLOW_ASSIGNMENT_V1_CONTRACT.md>) documents.

## Comparison Rules

[EvidenceCoreDiffer](<../../experiments/src/main/java/org/workflowsim/experiments/rerun/EvidenceCoreDiffer.java>) treats **all non-whitelisted fields** as core by default; it does not merely compare makespan:

- The manifest's configuration, platform, input identity, workflow profile/graph, Job/Task/VM/workflow results, actual placement, and the file plan and online compute requests where present.
- All non-exempt metrics fields; the entry count and order of events, all event fields, and the non-exempt attributes.
- Artifact roles, reference paths, array order, and integrity declarations; the complete contents of the optional sidecars.

What is compared is the **exact equality of JSON values**, not byte equality of the file text: object key order is irrelevant, while array order and length are preserved; numbers are compared by their exact decimal values, so `1000`, `1000.0`, and `1e3` are equal, but values are not first converted to double and no epsilon is used. Strings, booleans, null, and missing members remain distinct. The implementation is [ExactJsonValues](<../../experiments/src/main/java/org/workflowsim/experiments/common/ExactJsonValues.java>).

Network/lifecycle content preserves numeric types through strict lexical reading; long numbers must not degrade into strings or lose diffs to double rounding. Adjacent integers above 2^53, genuinely tiny decimal changes, and adjacent floating-point values receive no tolerance. Tie thresholds from statistical analysis must not be borrowed to loosen the comparison here.

### Fixed Volatile-Field Whitelist

Only the following actual paths are exempted and have their diffs recorded; `<i>` denotes an array index:

| Scope | Exempted fields |
| --- | --- |
| Root identity/environment subtrees | All subtrees of `/provenance` and `/runtime` |
| Algorithm-description identity | `/configuration/algorithmContract` |
| Input path text | `/configuration/workflowPaths/<i>`, `/inputs/<i>/path` |
| Workflow-result path text | `/result/workflowOutcomes/<i>/path` |
| Artifact byte digests | `/artifacts/<i>/sha256`, `/artifacts/<i>/sizeBytes` |
| Metrics wall-clock totals | `/metrics/totalSchedulingDecisionWallClockNanos`, `/metrics/totalPlanningDecisionWallClockNanos` |
| Event wall-clock attributes | `/events/<i>/attributes/decisionElapsedNanos`, `/events/<i>/attributes/planningDecisionElapsedNanos` |

The lengths of core arrays such as inputs and results, the input identity, the `schedulingAlgorithm` / `planningAlgorithm` in the configuration, `executionSemantics`, the physical parameters, and the recorded budgets are not whitelisted. Sidecar namespaces do not inherit the root `/runtime` and `/provenance` exemptions; the artifact hash/size exemption does not let sidecar contents escape comparison either. New fields are core by default and cannot be added to the volatile rules casually.

The bit-exact comparison defined by this contract takes the manifest/metrics artifacts as its domain; exact equality of fields such as makespan within that domain already constitutes rerun consistency. The coverage boundary of event-level independent recomputation has been closed by the additive 2026-10-23 revision of events v1 (`SIMULATION_TERMINATED` and `JOB_EXECUTION_STARTED`; see the [reproducibility contract](<REPRODUCIBILITY.md>)), and this must not be used to introduce new exemptions in any direction. The new event types are core data: a rerun on the same code tree carries the new events on both sides and the equality verdict is unaffected; a **rerun across the revision boundary** (pre-revision evidence × post-revision code) legitimately differs in the event array — event counts, order, and fields all report diffs under the core comparison. The correct disposition is to regenerate the evidence under the new tree, not to add a whitelist for the old event shapes.

Object member names are first encoded per RFC 6901 (`~`→`~0`, `/`→`~1`); the pointers are then assembled and classified against the whitelist. The literal key `configuration/algorithmContract` has the path `/configuration~1algorithmContract`, not a nested identity object; `provenance/extra` works the same way — names must not be used to sneak past the classification.

The reader may accept an original bundle not named `result`, but the new rerun always writes `result.*`; artifact reference paths of the original bundle can therefore produce a legitimate core divergence. **Artifact paths carry no additional exemption**, and such diffs must not be ignored to obtain `IDENTICAL_CORE`.

### Identity Information Reported Separately

The report retains both sides' `sourceTreeSha256` and `algorithmContract`. When the source-tree hashes are known to differ, a prominent notice flags the code-identity change, but this does not substitute for the input/configuration/result comparison. An identical core only says that this piece of evidence is consistent within the scope of this comparison; it does not prove that other inputs, platforms, or future versions agree as well, and consistency must not be presumed when identity is unavailable.

## Report and Exit Codes

```text
rerun-output/
  rerun-report.json
  rerun-report.md
  rerun/
    result.manifest.json
    result.metrics.json
    result.events.jsonl
    ...已声明的匹配侧车
```

The report contains the `verdict`, the input resolution, JSON pointers and both-side values for the core divergences, `volatileFieldsNoted`, identity information, and applicable coverage notes. Divergences are compared on their full values; displayed values longer than 120 characters are truncated and annotated with the original length, without turning a diff into equality. Metric divergences duplicated between the manifest and the metrics sidecar are deduplicated.

| verdict | CLI exit code | Meaning |
| --- | ---: | --- |
### Registered-algorithm evidence (BAT-R7, 2026-10-27)

Rerunning evidence produced by a registry-labeled algorithm resolves
`configuration.registeredSchedulingAlgorithm` through the SPI registry and fails fast when the
label is not registered on the rerunning classpath: the algorithm must be present where you
rerun it — an honest diagnostic, not a silent skip. Behavior anchors: the
[registered powered rerun chain](<../../experiments/src/test/java/org/workflowsim/experiments/rerun/RegisteredPoweredRerunIntegrationTest.java>)
and the [axis-reject locks](<../../experiments/src/test/java/org/workflowsim/experiments/rerun/RegisteredAxesRejectIntegrationTest.java>).
A registered run with a failure model or task-cost matrix the provider's declaration rejects is
refused at the registry gate before any simulation happens.

### Energy-dimension leaves (BAT-E4.1, 2026-10-27)

The energy extension added per-VM power leaves (`/platform/vms/N/idlePowerWatts`, `/platform/vms/N/maxPowerWatts`) and the `/energyMetrics` section to every fresh manifest. Retained pre-energy evidence has neither. A rerun of pre-energy evidence therefore compares an *absent* key on the original side against a **canonical zero** on the rerun side. These two states are declared **semantically equal**:

- the equivalence applies only to the two pointers above and the whole `/energyMetrics` section, only when one side is absent — **an explicit JSON `null` token reads as absent** (BAT-R3, 2026-10-27: absent, JSON-null, and canonical `0.0` are the three unified "undeclared" forms; the rebuilder already read null as absent, and the differ now agrees) — and only when every number on the present side is exactly `0.0` (verified recursively, including the `vmEnergyJoules` map);
- **nonzero energy is never exempt**: powered originals that lose energy on rerun stay `DIVERGED`, and any nonzero-vs-nonzero mismatch stays a core divergence (the conservation-law defense is not weakened);
- this is deliberately *not* a volatility-whitelist expansion — the values remain core quantities; only the absent-vs-zero state pair was unified.

Configuration rebuilding forwards both power fields from the manifest into the rebuilt `VmSpec` (missing keys read as 0.0; negative or non-numeric values are rejected explicitly). Reruns of powered evidence therefore reproduce the energy metrics bit-exactly: same engine, same seed, identical busy intervals, identical joules. Behavior anchors: [energy rerun compatibility tests](<../../experiments/src/test/java/org/workflowsim/experiments/rerun/EnergyRerunCompatibilityIntegrationTest.java>) and the rebuilder round-trip test.

| `IDENTICAL_CORE` | 0 | All non-exempt JSON core quantities are exactly identical, still subject to capture/model scope limits |
| `DIVERGED` | 1 | At least one core diff; the divergence list is non-empty |
| `INPUT_UNRESOLVED` | 2 | The input cannot be resolved |
| `INPUT_HASH_MISMATCH` | 3 | An input matched but its SHA/size does not |
| `RECONSTRUCTION_REJECTED` | 4 | The current code rejects the rebuilding or a classified simulation execution |
| `EVIDENCE_INVALID` | 5 | The original bundle is invalid, a declared sidecar is invalid, or it is not v4 |

Usage/output-directory errors are 64, unexpected exceptions are 70. Classified failures still write a diagnostic report but yield no successful new-run evidence; usage errors or unexpected I/O failures do not guarantee a complete report, and any files left behind must not be taken as a valid bundle. A failure of the Maven compile phase means this CLI verdict was never reached.

Validation coverage of the current chain is in the [core comparison tests](<../../experiments/src/test/java/org/workflowsim/experiments/rerun/EvidenceCoreDifferTest.java>), the [rerun integration tests](<../../experiments/src/test/java/org/workflowsim/experiments/rerun/RerunDiffExecutorIntegrationTest.java>), and the [online-action rerun tests](<../../experiments/src/test/java/org/workflowsim/experiments/rerun/DataflowAssignmentRerunIntegrationTest.java>). They verify behavior with explicit fixtures and do not take optional local historical outputs as proof that the current run succeeded.

Study-protocol integrity and the actual rerun are different checks; see the [protocol identity notes](<NETWORK_STUDY_PROTOCOL_REVISIONS.md>). Retain the original evidence, the new run, and the diff report; old fields, protocol identity, or golden values must not be modified to erase a genuine divergence.
