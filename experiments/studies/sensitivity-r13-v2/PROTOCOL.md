# Sensitivity Response-Surface Study (R13): Re-Certification Protocol (v2)

> **Current layer.** This page registers the re-certification study `sensitivity-response-r13-v2`
> executed on 2026-10-23 at repository HEAD `f00ff69`. The original preregistered study
> ([v1 archive](<../sensitivity-r13/PROTOCOL.md>)) used a non-standard LOCAL_PEFT recursion
> (self cost inside OCT, averaged exit) and was archived without certifying standard PEFT; the
> archived v1 page remains untouched. This protocol is a **re-analysis under the corrected
> algorithm identity**, not a fresh preregistration: the parameter matrix and the preregistered
> mechanism prediction are carried over from v1 verbatim and re-adjudicated.

## Identity and execution model

- Protocol ID: `sensitivity-response-r13-v2` (registered by
  [NetworkStudyPlan](<../../src/main/java/org/workflowsim/experiments/network/NetworkStudyPlan.java>);
  identity semantics governed by
  [NETWORK_STUDY_PROTOCOL_REVISIONS](<../../../docs/experiments/NETWORK_STUDY_PROTOCOL_REVISIONS.md>)).
- Execution declaration: `WORK_CONSERVING_TASK_EXECUTION_V2`.
- LOCAL_PEFT algorithm identity: `PEFT_SUCCESSOR_COST_OCT_EXIT_ZERO_V2` (successor cost
  `w(child, p')`, exit `OCT = 0`; ready-list discipline per commit 37d3258).
- Executed at HEAD `f00ff69` (main). A prior execution of the same protocol at Java `8681f9c`
  is retained as the implementation-repair impact layer
  ([correctness-repair RESULTS](<../correctness-repair/RESULTS.md>)); the two layers are
  cross-checked cell-by-cell in the [results](<RESULTS.md>).

## Matrix (inherited from v1, unchanged)

- Qualified inputs: the 7 inputs of R10/S5 — five classic DAXs
  (Epigenomics 100/997, CyberShake 100/1000, Inspiral 100) and two deterministic synthetic
  DAGs (layered-32/128). Inspiral 1000 remains excluded (CONFLICTING_FILE_SIZE).
- Algorithms: LOCAL_HEFT, LOCAL_CPOP, LOCAL_PEFT — all deterministic, one run per condition
  (seed 11). No failures, LOCAL file system, STATIC dispatch, SPACE_SHARED, one VM per Host,
  fixed placement (id ↔ id).
- **Homogeneous main block** (20 conditions): VM ∈ {4, 8, 16, 32} × network ∈ {endpoint 1.0,
  fat-tree-constrained 0.125, fat-tree-mid 0.5, fat-tree-wide 1.25, fat-tree-fast 5.0 MB/s};
  all VMs at 1000 MIPS.
- **Heterogeneity block** (6 conditions): VM ∈ {8, 16} × fat-tree-constrained ×
  {HET_MILD, HET_STRONG, HET_EXTREME}, deterministic MIPS patterns by VM index i:
  MILD = `1000(i even)/500(i odd)`; STRONG = `[2000,1000,500][i%3]`;
  EXTREME = `2000(i even)/500(i odd)`.
- Total: 7 inputs × 26 conditions × 3 planners × 1 seed = **546 runs**.
- CI smoke matrix: paper ten-task fixture + layered-16, 30 units (not a truncation of full).

### Fat-tree k caveat (must accompany results)

fat-tree k = 4 hosts 16 machines; the VM = 32 tier necessarily uses k = 8. The VM axis's last
tier therefore mixes topology tier and host-pool size; cross-tier differences are descriptive
only and must not be attributed to VM count alone. vm32 rows carry this confound.

## Adjudication object (carried over from v1, verbatim)

The v1 preregistration predicted: PEFT's *activation* (median improvement vs HEFT clearly
deviating from 0) should **only** appear under heterogeneous MIPS; across all homogeneous
conditions PEFT's median improvement should be ≈ 0 and tie-dominant. Re-adjudication under the
current algorithm identity judges exactly this prediction against the v2 response surface; no
new prediction is registered here (disclosed as re-analysis, not prospective registration).

## Evidence-chain requirements

1. Execution via `NetworkStudyExecutor` into a fresh output directory (never an existing one).
2. Fresh-process `NetworkStudyValidator` pass over the saved index.
3. Independent Python audits: `verify-study-evidence.py` (bundle-level) and
   `verify-study-statistics.py` (matrix/seed aggregation) over the new index — both must report
   zero issues; audit reports are retained as separate files outside the bundle.
4. Cell-by-cell cross-check against the archived `8681f9c` execution layer of the same protocol;
   only identity class differences (recorded input root paths) are acceptable.
5. Original v1 artifacts and the correctness-repair layer are never overwritten; new evidence
   occupies a new directory and an evidence tar.

## Retention

- Active evidence: `output/sensitivity-r13-v2/` (protocol.json, network-study.json, generated
  inputs, per-run v4 manifest/metrics/events, automated result table).
- Evidence tar: `output/sensitivity-r13-v2-evidence.tar.gz` (study directory plus the two
  independent Python audit reports).
- `output/...` paths are relative to the project root; they are local retention locations not
  provided with a normal checkout.
- Results and adjudication: [RESULTS.md](<RESULTS.md>).
