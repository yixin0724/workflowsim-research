# Standard Benchmark `benchmark-standard-v1`

The platform's first frozen public benchmark: a standard comparison of the maintained
algorithm corpus under frozen conditions, with every number backed by a manifest-v4 bundle
that any third party can independently re-verify with the repository's Python auditor.

Run matrix: 5 qualified inputs (Epigenomics-100/997, CyberShake-100/1000, Inspiral-100) ×
2 VM tiers (8, 32) × 3 decision layers (13 algorithm policies), frozen at seed 11 —
**160 runs, 160 completed successfully**, all audited.

## Standard results (median makespan seconds, by policy × tier)

| Layer | Policy | 8-VM median (s) | 32-VM median (s) | runs/condition |
|---|---|---:|---:|---:|
| SHARED_DAG | SHARED_STORAGE_HEFT | 7780 | 4012 | 5 |
| SHARED_DAG | SHARED_STORAGE_CPOP | 7863 | 4156 | 5 |
| SHARED_DAG | SHARED_STORAGE_DLS | 7780 | 4003 | 5 |
| SHARED_DAG | SHARED_STORAGE_ETF | 7779 | 4003 | 5 |
| SHARED_DAG | SHARED_STORAGE_PEFT | 7813 | 4077 | 5 |
| LOCAL_DAG | LOCAL_HEFT | 297949 | 180467 | 10 |
| LOCAL_DAG | LOCAL_CPOP | 160629 | 160629 | 10 |
| LOCAL_DAG | LOCAL_PEFT | 294792 | 188109 | 10 |
| ONLINE | FCFS | 44682 | 41747 | 5 |
| ONLINE | READY_BATCH_MINMIN | 44718 | 41741 | 5 |
| ONLINE | READY_BATCH_MAXMIN | 44678 | 41747 | 5 |
| ONLINE | READY_BATCH_MCT | 44682 | 41747 | 5 |
| ONLINE | READY_BATCH_ROUNDROBIN | 44682 | 41747 | 5 |

LOCAL_DAG medians span two network families (endpoint contention + fat-tree constrained at
0.125 MB/s), 5 inputs × 2 models each; all other layers use one declared model per layer
(exactly the CATALOG[v] combination matrix rows). The numbers describe the declared model
families only — see the boundary statement below.

## How a third party verifies (no Java required)

```bash
cd output/benchmark-standard-v1
PYTHONDONTWRITEBYTECODE=1 python3 ../../scripts/verify-study-evidence.py \
  --output /new-path/benchmark-audit.json \
  $(python3 -c "import json; print(' '.join('runs/' + r['runId'] + '/' + r['manifest'].split('/')[-1] for r in json.load(open('benchmark.json'))['runs']))")
```

or any subset. Each run carries a complete v4 evidence bundle (manifest/metrics/events),
the ones rewritten with the standard v4 checksums, validated, and independently audited —
160/160 pass with zero issues.

## Frozen matrix definition

- **Inputs** (see [Command](<../../experiments/src/main/java/org/workflowsim/experiments/benchmark/BenchmarkExecutor.java>))
- **VM tiers**: 8 (fat-tree k=4, 16 hosts), 32 (fat-tree k=8, 128 hosts); homogeneous 1000 MIPS, 1 PE, SPACE_SHARED
- **Protocol**: `benchmark-standard-v1`, seed 11, single-seed-point-per-condition (deterministic algorithms)
- **Same-layer comparison only** (the three decision layers are independent family universes)
- **LOCAL_DAG cells** use `PRE_EXECUTION_TRANSFER_DELAY_WITH_CONTENTION_V1` (endpoint) and
  `PRE_EXECUTION_TRANSFER_DELAY_WITH_FAT_TREE_CONTENTION_V1` (fat-tree)
- **SHARED_DAG cells** use `LEGACY_WORKFLOWSIM_V1` + `SHARED` replica catalog
- **ONLINE cells** use `LEGACY_WORKFLOWSIM_V1` + `FCFS/READY_BATCH_*` scheduling labels

## Boundaries (what this benchmark does NOT prove)

1. The numbers describe the **declared simulation models** on the specific frozen
   platform profiles — not predictions about production clusters, not interpolated
   results on other hardware.
2. The layers are **superficially incomparable** (different decision phases, different
   model families), and the table does not rank them against each other.
3. These 160 cells measure the frozen-law [SHARED-legacy / LOCAL-fattree] scope. No claim
   about other network models, no multi-seed statistical inference (deterministic
   algorithms at one condition seed produce repeats, not independent samples).
4. LOCAL v32 uses k=8 fat-tree (necessary for 32 hosts) — VM count and topology tier are
   simultaneously changed (confound documented in the study protocol).

## Retention

The full evidence bundle (160 runs × v4 evidence package) is retained at
`output/benchmark-standard-v1/` and as a tagged tar artifact
`output/benchmark-standard-v1-evidence.tar.gz` (21 MB). It is not modified or re-labeled.
