# Sensitivity Response-Surface Study (R13) — Results (v2, current layer)

> **Re-certified current evidence.** 546/546 runs completed under `sensitivity-response-r13-v2`
> at HEAD `f00ff69` on 2026-10-23, with the standard PEFT recursion
> (`PEFT_SUCCESSOR_COST_OCT_EXIT_ZERO_V2`) and `WORK_CONSERVING_TASK_EXECUTION_V2`.
> The [v1 archive](<../sensitivity-r13/RESULTS.md>) (non-standard PEFT) stays untouched;
> its interpretations were decertified and are **not** inherited here. The archived
> `8681f9c` execution layer ([correctness-repair impact study](<../correctness-repair/RESULTS.md>))
> is cross-checked below. Protocol and matrix: [PROTOCOL.md](<PROTOCOL.md>).

## 1. Certification chain (all checks executed on this layer)

| Check | Result |
|---|---|
| Execution | 546/546 `COMPLETED_SUCCESSFULLY` (7 inputs × 26 conditions × 3 planners, seed 11) |
| Fresh-process production validator | `NETWORK_STUDY_VALIDATION PASSED runs=546 protocol=sensitivity-response-r13-v2 mode=full scope=DECLARED_PROTOCOL_INTEGRITY;NOT_A_CURRENT_CODE_RERUN` |
| Independent Python evidence audit | PASS — 546 bundles, 0 issues (report: `output/r13-v2-audit-evidence`) |
| Independent Python statistics audit | PASS — 1 study, 0 issues, full per-DAG effects recomputed (report: `output/r13-v2-audit-statistics`) |
| Cross-layer diff vs archived `8681f9c` run | **546/546 cells bit-exact** on the five core metrics (simulation end, logical completion, mean/P95 waiting, mean VM utilization) plus status, task count, and input SHA-256; recorded plan differs only in absolute input root paths (legacy `WorkflowSim-1.0`-era layout vs current `WorkflowSim-yixin` layout) — environment identity, not semantics |
| Archived-layer audits (this machine) | Java: PASSED · Python statistics: PASS · Python evidence: PASS with the declared `--input-root datasets` relocation (classic DAXs relicated; hash and size still verified) |
| Value anchor | HEFT CyberShake-100 / vm16 / endpoint = 283759.323274 s, bit-equal to the value recorded in the correctness-repair impact study |

The bit-exact cross-layer equality additionally certifies that the ~50 repository commits
between `8681f9c` and `f00ff69` (code-regularization and style phases) are behavior-neutral on
this 546-cell study — a stronger statement than the unit-gate alone.

## 2. Adjudication of the v1 preregistered prediction

Prediction carried over from v1: *PEFT's activation (median improvement vs HEFT clearly
deviating from 0) should appear only under heterogeneous MIPS; all homogeneous conditions
should be ≈ 0 and tie-dominant.*

**Verdict: REJECTED in both directions under the current algorithm identity.**

1. **"Homogeneous ≈ 0, tie-dominant" fails.** Classic-DAX homogeneous medians are −0.41% /
   −1.55% / −0.93% (PEFT, endpoint, vm 4/8/16) with exact ties now rare (2 wins vs 3 losses
   and analogous splits). Mechanism (exploratory): the standard successor-cost OCT is not a
   constant across identical VMs — v1's homogeneous ties were an artifact of the non-standard
   self-cost recursion, which degenerated on uniform MIPS.
2. **"Activation only under heterogeneity" fails as a discriminator.** The deviation pattern
   exists already in homogeneous blocks at comparable magnitude; the heterogeneity axis
   modulates per-DAG magnitude without a corpus-consistent sign (all eight classic
   heterogeneity rows split 2–3 / 3–2; exact two-sided sign test raw p = 0.375 everywhere,
   Holm-adjusted p = 0.75 with family of two; at n = 5 the smallest achievable raw p is
   0.0625, so this layer cannot reach 0.05 by design — consistent with v1's power boundary).
3. What the heterogeneity axis does show (descriptive): Inspiral-100's PEFT losses escalate
   with disparity at vm16 (−9.66% homogeneous → −23.58% / −22.95% at STRONG/EXTREME), while
   CyberShake stays mildly positive.

## 3. Response surface I — homogeneous block (median improvement % vs LOCAL_HEFT)

### 3.1 CLASSIC_DAX (5 DAGs; HEFT medians: endpoint 78.82 h at vm 4–16 → 49.97 h at vm 32)

| VM | Candidate | endpoint | ft-constrained | ft-mid | ft-wide | ft-fast |
|---:|---|---:|---:|---:|---:|---:|
| 4 | CPOP | +0.042 | +0.056 | +0.027 | +0.042 | +0.042 |
| 4 | PEFT | −0.410 | −0.593 | −0.410 | −0.410 | −0.410 |
| 8 | CPOP | −0.001 | −0.014 | −0.003 | −0.001 | −0.001 |
| 8 | PEFT | −1.546 | −1.737 | −1.567 | −1.546 | −1.546 |
| 16 | CPOP | **+2.934** | **+1.574** | **+2.741** | **+2.934** | **+2.934** |
| 16 | PEFT | −0.925 | −1.127 | −0.936 | −0.925 | −0.925 |
| 32 | CPOP | +0.000 | +0.147 | +0.000 | +0.000 | +0.000 |
| 32 | PEFT | +0.248 | +0.343 | +0.343 | +0.248 | +0.248 |

vm32 rows carry the k = 8 fat-tree topology confound (see protocol). The vm16 CPOP jump is
driven by two DAGs (§ 5) and is network-independent (identical at endpoint).

### 3.2 SYNTHETIC (2 DAGs, descriptive; HEFT medians: vm4 endpoint ≈ 0.22 h, ft-constrained ≈ 0.95 h)

| VM | Candidate | endpoint | ft-constrained | ft-mid | ft-wide | ft-fast |
|---:|---|---:|---:|---:|---:|---:|
| 4 | CPOP | +14.911 | +15.066 | +14.967 | +14.911 | +14.911 |
| 4 | PEFT | −1.577 | +0.506 | −0.473 | −1.577 | −1.577 |
| 8 | CPOP | +2.368 | +5.224 | +5.620 | +2.369 | +2.368 |
| 8 | PEFT | −5.856 | −5.861 | −5.643 | −5.856 | −5.856 |
| 16 | CPOP | −12.850 | −15.245 | −14.779 | −12.856 | −12.850 |
| 16 | PEFT | −11.049 | **−27.365** | −26.832 | −11.537 | −11.049 |
| 32 | CPOP | +0.127 | +0.019 | +0.077 | +0.127 | +0.127 |
| 32 | PEFT | +0.295 | +4.988 | +4.952 | +0.295 | +0.295 |

## 4. Response surface II — heterogeneity block (fat-tree-constrained)

### 4.1 CLASSIC_DAX

| VM | Heterogeneity | HEFT median (h) | CPOP med% (w/t/l) | PEFT med% (w/t/l) | PEFT raw p / Holm p |
|---:|---|---:|---|---|---|
| 8 | HOMOGENEOUS | 86.70 | −0.014 (2/0/3) | −1.737 (2/0/3) | 0.375 / 0.75 |
| 8 | MILD | 86.70 | +0.063 (4/0/1) | +0.093 (3/0/2) | 0.375 / 0.75 |
| 8 | STRONG | 88.56 | +0.070 (3/1/1) | +0.000 (3/0/2) | 0.375 / 0.75 |
| 8 | EXTREME | 88.56 | +0.109 (4/0/1) | −0.558 (2/0/3) | 0.375 / 0.75 |
| 16 | HOMOGENEOUS | 86.70 | +1.574 (4/0/1) | −1.127 (2/0/3) | 0.375 / 0.75 |
| 16 | MILD | 86.70 | +0.373 (3/1/1) | −0.896 (2/0/3) | 0.375 / 0.75 |
| 16 | STRONG | 88.56 | +0.009 (3/0/2) | −0.581 (2/0/3) | 0.375 / 0.75 |
| 16 | EXTREME | 88.56 | −0.035 (2/0/3) | −1.350 (2/0/3) | 0.375 / 0.75 |

No row approaches significance; the v1-style
concentrated "MILD/STRONG all-negative" pattern is absent — losses and gains interleave.

### 4.2 SYNTHETIC (n = 2, purely descriptive, no inference)

| VM | Heterogeneity | HEFT median (h) | CPOP med% (w/t/l) | PEFT med% (w/t/l) |
|---:|---|---:|---|---|
| 8 | HOMOGENEOUS | 0.75 | +5.224 (1/1/0) | −5.861 (0/1/1) |
| 8 | MILD | 1.13 | +5.368 (1/1/0) | −17.200 (1/0/1) |
| 8 | STRONG | 1.18 | −4.955 (1/0/1) | −20.917 (0/0/2) |
| 8 | EXTREME | 1.18 | +7.740 (2/0/0) | +8.932 (1/0/1) |
| 16 | HOMOGENEOUS | 0.65 | −15.245 (0/1/1) | −27.365 (0/1/1) |
| 16 | MILD | 1.02 | +0.106 (1/1/0) | −6.540 (1/0/1) |
| 16 | STRONG | 1.01 | +8.084 (1/0/1) | +15.744 (1/0/1) |
| 16 | EXTREME | 0.99 | −3.356 (0/1/1) | −13.133 (1/0/1) |

## 5. Per-DAG structure behind the medians

Improvement % vs HEFT, CLASSIC_DAX, endpoint homogeneous (left) and fat-tree-constrained
heterogeneity axis (right):

| DAG | vm4 PEFT | vm8 PEFT | vm16 PEFT | vm16 CPOP | vm32 PEFT |
|---|---:|---:|---:|---:|---:|
| cybershake-100 | +0.248 | +0.248 | +0.248 | **+43.392** | +0.248 |
| cybershake-1000 | +1.827 | +1.101 | +1.473 | **+48.604** | +1.690 |
| epigenomics-100 | −2.377 | −9.415 | **−14.398** | −0.001 | +0.342 |
| epigenomics-997 | −0.410 | −1.546 | −0.925 | +0.194 | −4.067 |
| inspiral-100 | −3.580 | −5.415 | −10.652 | +2.934 | +0.000 |

| DAG | v8 HOMO | v8 MILD | v8 STRONG | v8 EXT | v16 HOMO | v16 MILD | v16 STRONG | v16 EXT |
|---|---:|---:|---:|---:|---:|---:|---:|---:|
| cybershake-100 | +1.797 | +1.797 | +0.000 | +0.000 | +1.797 | +1.797 | +0.000 | +0.000 |
| cybershake-1000 | +1.586 | +0.093 | +4.569 | +5.124 | +2.228 | +1.661 | +5.719 | +5.804 |
| epigenomics-100 | −9.078 | −3.468 | −6.675 | −4.757 | −12.991 | −9.690 | −12.152 | −4.799 |
| epigenomics-997 | −1.737 | +1.252 | +2.460 | −0.558 | −1.127 | −0.896 | −0.581 | −1.350 |
| inspiral-100 | −5.476 | −9.232 | −9.110 | −12.682 | −9.664 | −13.126 | **−23.578** | −22.952 |

Readings (descriptive, corpus-bound):

- The vm16 CPOP median (+2.93%) is a two-DAG phenomenon (CyberShake ±43–49% single-VM
  critical-path consolidation; Epigenomics ~0). Medians alone are not a corpus summary —
  per-DAG spread is mandatory reporting here.
- PEFT's homogeneous losses on Epigenomics-100 grow with VM count (−2.4 → −9.4 → −14.4 at
  vm 4/8/16, endpoint) and fall back to near-tie at the topology-confounded vm32 tier; on
  Inspiral-100 the same escalation continues into the strong/extreme heterogeneous tiers
  (−23.6% / −23.0%).
- The bandwidth axis is essentially dead for both candidates in the classic layer (v1's vm4
  PEFT bandwidth sensitivity, +1.67% → +0.04–0.07%, does not reappear; the sign is now
  negative throughout).

## 6. Retention and boundary statements

1. This is a **descriptive response surface under a declared simulation model** — no causal
   attribution, no claim about real platforms or other corpora. Heterogeneity also changes
   total MIPS; vm32 mixes topology (k = 4 → k = 8).
2. Single deterministic run per cell (seed 11): improvements are point estimates without
   variance. Deterministic planners do not gain independent samples from seed replication
   (repetition is not sampling).
3. The two populations are never pooled; SYNTHETIC (n = 2) carries no inferential weight.
4. Non-significance is not equivalence; at n = 5 the exact two-sided sign test cannot reach
   0.05 regardless of effect (raw floor 0.0625, Holm family-of-two floor 0.125).
5. The v1 archive, the `8681f9c` impact layer, and all their artifacts remain untouched;
   this layer's evidence occupies `output/sensitivity-r13-v2/` and
   `output/sensitivity-r13-v2-evidence.tar.gz` (local retention, not part of a normal
   checkout). The v1 interpretations ("homogeneous degeneration", "heterogeneous
   activation", "optimistic bias as two faces") describe the non-standard PEFT only and are
   **superseded by the verdict in § 2** for the current algorithm.

## 7. Reproduction

```bash
mvn -pl :workflowsim -am install -DskipTests -q
mvn -pl :workflowsim-workbench exec:java \
  -Dexec.mainClass=org.workflowsim.experiments.network.NetworkStudyExecutor \
  -Dexec.args="full $PWD/datasets $PWD/output/<new-directory> sensitivity-r13"
# a fresh output directory is mandatory; rerunning into an existing one is refused
mvn -pl :workflowsim-workbench exec:java \
  -Dexec.mainClass=org.workflowsim.experiments.network.NetworkStudyValidator \
  -Dexec.args="$PWD/output/<new-directory>/network-study.json"
# expected: NETWORK_STUDY_VALIDATION PASSED runs=546 protocol=sensitivity-response-r13-v2 mode=full scope=DECLARED_PROTOCOL_INTEGRITY;NOT_A_CURRENT_CODE_RERUN
PYTHONDONTWRITEBYTECODE=1 python3 -B scripts/verify-study-evidence.py \
  --output <new-report.json> output/<new-directory>/network-study.json   # PASS: 546 bundles, 0 issues
PYTHONDONTWRITEBYTECODE=1 python3 -B scripts/verify-study-statistics.py \
  --output <new-report.json> output/<new-directory>/network-study.json   # PASS: 1 studies, 0 issues
```
