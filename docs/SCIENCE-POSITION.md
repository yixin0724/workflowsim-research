# Science Position Statement

> This statement tells researchers, reviewers, and developers the research identity, the
> validity standard, and the evidence system of WorkflowSim-yixin.
> Basis: literature survey and two review-engineering rounds (2026-10-19/20).

## 1. Research object and methodological identity (Who we are)

The platform takes the **workflow-scheduling problem** as its research object: mapping and
ordering decisions for a single workflow DAG, and resource contention under staggered
multi-workflow arrivals. Its methodological identity is:

> **A faithful carrier and verifier of workflow-scheduling algorithms — an experimental
> platform whose conclusions are reproducible, traceable, and evidence-complete.**

Three inseparable elements make up this identity:
1. **Algorithm faithfulness**: every maintained algorithm's semantics are anchored to
   primary literature or textbooks (hand-derived golden tables plus explicitly declared
   deviations), eliminating the semantic gap between "algorithm X in the paper" and "X as
   run on the platform" at the root.
2. **Experimental controllability**: decision layers (planning / online scheduling /
   assignment) are separated, configuration fails fast instead of silently switching
   meaning, and mixed algorithm-layer tracks must not be compared in one pool — following
   the controlled-experiment position of Hooker 1995 rather than horse-race comparisons.
3. **Evidence completeness**: bit-exact rerun contracts, whitelisting governance,
   Java/Python dual-language independent audits, and bit-exact replay — producing evidence
   bundles a third party can independently re-verify.

## 2. Validity standard: internal validity first (What "valid" means here)

The platform follows the **internal-validity** route: numbers are the **exact product** of
the declared simulation model, and third parties can recompute them bit-exactly. The
physical models are declarative abstractions consistent with the domain literature (the
NC-family simplifications: source-end bandwidth, two-end min, zero local replica transfer,
plus the controlled extensions of max-min contention and deterministic fat-tree routing),
and deliberately do **not** pursue external fidelity to any production cluster — that route
belongs to the WRENCH/SimGrid family and requires multi-year physical-model validation
campaigns, which are out of scope here.

Two declarations that differ from common misunderstandings:
- Mathematical rigor is already strong (e.g., the LOCAL-family zero-tolerance planning to
  execution mirror upgrades estimation accuracy from a metric to an invariant, beyond the
  field's usual practice of measuring accuracy as a metric);
- Physical simplification of the simulation model is the literature-standard setting (DNC
  vs NC), not a defect — but **which layer a deviation lives in must always be
  inspectable** (the capability matrix declares "unimplemented capabilities").

## 3. Competitive positioning (Where we stand)

- WfCommons (Coleman et al.): input realism and format standards — **we are a model
  consumer**, not a competitor, and we further separate the evidence boundaries of its
  standard (three-source separation).
- WRENCH (Casanova et al., FGCS 2020): an external-fidelity simulation framework on the
  validation-campaign route, contrasted directly with original WorkflowSim — this fork
  acknowledges WRENCH's critique of the original WorkflowSim, and responds squarely on
  the **evidence-completeness** route rather than by following its physical-calibration
  route.
- CloudSim Plus: engineering correctness and feature breadth — this platform declines the
  feature race; it inherits stability from a deeply maintained CloudSim 3 kernel instead
  of chasing new releases.
- The field's pain point: Hooker's 1995 thirty-year critique plus the 2024/25 "SOTA
  replication" and "adversarial comparison" crises (PISA, IPDPS 2025) point at the same
  trust collapse — **that is exactly where this platform's moat is anchored**: an
  independently auditable evidence chain for algorithm comparison.

## 4. Statistics and comparison discipline

- Paired Wilcoxon + Holm, preregistered protocols, fixed per-DAG seeds — aligned with the
  field's canon.
- Cross-paper metric-comparison discipline follows the paper-metrics conventions card
  ([METRICS_CARD.md](<algorithms/METRICS_CARD.md>)): every metric cited in a paper carries
  its formula, sample domain, window baseline, and floor; no cross-convention merging.
- Statistical comparisons of platform output follow the established CAMPAIGNS contract
  (same-layer comparison, pairing, within-DAG averaging before stratification, two-sided
  sign tests + Holm).

## 5. Threats to validity (quotable as a whole section in papers)

- Internal validity: all metrics are exact products of the declared model; the bit-exact
  rerun contract guarantees same inputs + same configuration + same code tree ⇒
  IDENTICAL_CORE (the whitelist covers only identity/environment/wall-clock; semantic
  exemptions are rejected outright).
- Statistical validity: multiple seeds of a deterministic algorithm are repetitions, not
  samples; randomized comparisons run under preregistered stratified designs with Holm
  correction; same-layer comparison is enforced (mixing decision layers is treated as a
  construct-validity defect, not a feature).
- External validity: **no claim whatsoever** of calibration to any production platform or
  real trace; boundary declarations precede any "close to real" generalization narrative
  (unlike the vague claims common in this field).
- Structural validity: every maintained algorithm carries a literature or textbook anchor
  (deviations explicitly declared); the legacy clustering/reclustering paths are
  explicitly rejected by SimulationRunner, preventing "historical compatibility code"
  from leaking into research semantics.
- Construct validity: per-metric differences between our metric definitions and the
  closest literature conventions (denominator floors, ready-baseline windows,
  nearest-rank quantiles) are declared item by item in the conventions card.

## 6. Open boundaries (currently declared as-is)

- F-006/F-007 (event observability gaps) → **closed (2026-10-23 contract revision,
  user-adjudicated)**: `SIMULATION_TERMINATED` and `JOB_EXECUTION_STARTED` entered
  events v1; the makespan/terminal-tail/deadline-slack and decision-to-start/waiting
  families are now independently recomputable at the event level; pre-revision evidence
  keeps its declared boundary. The recompute boundary change is documented in
  RERUN_DIFF_CONTRACT.
- Independent Python recomputation covers **78/78 scalars (100%)**: chain E (2026-10-20)
  closed the deadline family — six fields (exact recompute across the
  MET/MISSED_LATE/NOT_REQUESTED branches) — and the controlledSharedStorage four
  (including all UNAVAILABLE branches, matrix-authoritative semantics, and (long)
  truncation rounding) under a three-state disposition. The 2026-10-23 F-006/F-007
  events contract revision further extends recomputation from the manifest level to the
  event level (terminal-clock identity, job-execution-start coverage, and derived
  cross-checks are built into the independent auditors).
- Legacy defects inherited from original WorkflowSim / CloudSim 3 code paths (6 fixed:
  F-001/004/005/008/009 + the M5/M7 test blind spots); historical details live in the
  review-engineering archive.

---
**Conclusion sentence (single paragraph, quotable standalone)**: WorkflowSim-yixin is a
simulation-experiment platform for research on workflow-scheduling algorithms whose
validity standard is internal validity and evidence completeness; the evidence for its
trustworthy claims is not physical fidelity but "output-as-evidence — numbers a third
party can recompute bit-exactly, rerun to equality, audit in two languages, and read under
per-metric conventions declarations."
