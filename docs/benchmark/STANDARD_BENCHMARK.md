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

### Power variant benchmark-standard-power-v1

The same 160-cell matrix (same inputs, VM tiers, layers, seed 11) was re-run on 2026-10-27
with the registered-study platforms rebuilt with every VM carrying the Beloglazov Eq.(2)
linear power envelope — idle 250 W, max 400 W (protocol `benchmark-standard-power-v1`, index
`benchmark-power.json`; 160/160 completed successfully). Every manifest's `energyMetrics`
section reports `totalEnergyJoules`, `meanVmEnergyJoules`, `peakPowerWatts`
(= vmCount × 400 W), and `energyMakespanProductJouleSeconds`, and the Python auditor
recomputed the energy conservation law (`E = Σ P_idle×T_sim + (P_max−P_idle)×T_busy` from
each manifest's own fields) on all 160 bundles with zero issues — the same command as above,
substituting `benchmark-power.json`. Evidence is retained at
`output/benchmark-standard-power-v1/` and as
`output/benchmark-standard-power-v1-evidence.tar.gz` (21.7 MB); joules are declared-model
values, not measurements of real hardware.

### Carbon variant benchmark-standard-carbon-v1

The same 160-cell matrix (same inputs, VM tiers, layers, seed 11) was re-run on 2026-10-28
under the carbon dimension (job batch C4 of the
[Carbon dimension design](<../advanced/CARBON_DIMENSION_DESIGN.md>), protocol
`benchmark-standard-carbon-v1`, index `benchmark-carbon.json`; 160/160 intensity cells
plus 10 budget cells completed successfully). Every grid cell's platform carries the power
envelope of the power variant *and* the uniform per-VM carbon intensity
`carbonIntensityGramsPerKWh = 380.0` (the CASPER-family multiplier accounting); the 10-cell
demonstration family of the mutually exclusive CWM-family budget scenario declares instead
— and only — the windows `[0, 3600) @ green 300 W` and `[3600, 7200) @ green 600 W`
(see [Command](<../../experiments/src/main/java/org/workflowsim/experiments/benchmark/BenchmarkExecutor.java>)).
No bundle ever declares an intensity together with windows (the BAT-C1 exclusivity is
enforced both at report construction and by the auditor), and the `carbonMetrics` section
is present in a manifest exactly when a carbon model is declared.

Median grams per layer (uniform 380.0 gCO₂/kWh; per-VM idle 250 W / max 400 W; grams =
E[J]/3.6e6 × I, computed from the same conservation-locked per-VM joules):

| Layer | 8-VM median (gCO₂) | 32-VM median (gCO₂) | cells per median |
|---|---:|---:|---:|
| SHARED_DAG | 2454 | 4200 | 25 |
| LOCAL_DAG | 51862 | 196879 | 30 |
| ONLINE | 14048 | 40607 | 25 |

Cell totals span 900 g (Inspiral_100-v8, SHARED_DAG) to 1,851,335 g (CyberShake_1000-v32,
LOCAL_DAG fat-tree); the heaviest input family alone (Epigenomics_997 on SHARED_DAG)
has medians of ~168,488 g (8 VM) and ~175,064 g (32 VM), so the layer medians above are
not family extremes — carbon inherits the makespan-driven ordering of the energy chain.

Budget-model demonstration family (SHARED_DAG planning, `SHARED_STORAGE_HEFT`, one cell
per input family × VM tier; the greens sit far below both tiers' idle draw of 2,000/8,000
W, so **excess is positive in 10 of 10 cells** — window 1 only opens when the workflow
runs past 3600 s, and sub-hour inputs keep it shut):

| Cell (input × tier) | window 0 excess (W·s) | window 1 excess (W·s) | total excess (W·s) |
|---|---:|---:|---:|
| Epigenomics_100-v8 | 10386813 | 9360000 | 19746813 |
| Epigenomics_100-v32 | 40505316 | 39600000 | 80105316 |
| Epigenomics_997-v8 | 10433284 | 9360000 | 19793284 |
| Epigenomics_997-v32 | 44755651 | 43920000 | 88675651 |
| CyberShake_100-v8 | 9385906 | — | 9385906 |
| CyberShake_100-v32 | 26965905 | — | 26965905 |
| CyberShake_1000-v8 | 8802101 | 9360000 | 18162101 |
| CyberShake_1000-v32 | 33468752 | 4994440 | 38463192 |
| Inspiral_100-v8 | 7758755 | — | 7758755 |
| Inspiral_100-v32 | 13516388 | — | 13516388 |

(The `—` rows end inside window 0 — Inspiral_100 at 1,342/2,691 s and CyberShake_100 at
3,000/3,246 s — so window 1 never opens; the manifest records only positive windows.)

Relative `carbonMetrics` reproduction (from the repository root, no Java required):

```bash
PYTHONDONTWRITEBYTECODE=1 python3 scripts/verify-study-evidence.py \
  --output /new-path/carbon-audit.json \
  $(ls output/benchmark-standard-carbon-v1/runs/*/result.manifest.json)
```

All 170 bundles audit with zero issues (5,216,594 automated checks, PASS) — the intensity
grams are the audited per-VM joules times the declared intensity, and the budget excess is
the fleet-power timeline rebuilt from the manifest's own compute-job intervals and power
fields. A negative control that adds +1.0 g to a real bundle's
`carbonMetrics/totalCarbonGrams` is killed with `metric-mismatch` at exactly that path.
Evidence is retained at `output/benchmark-standard-carbon-v1/` and as
`output/benchmark-standard-carbon-v1-evidence.tar.gz` (22.1 MB).

Honest bounds of the carbon variant, beyond the boundary statements of the standard and
power benchmarks:

- **The spatial axis is not captured.** The platform is single-fleet: this benchmark
  freezes one uniform intensity (380.0) on every VM, and even heterogeneous per-VM
  intensities capture only the region heterogeneity of *assigned* work — never the
  geo-routing/shifting between regions that CASPER optimizes at the systems layer (the
  design doc's §3 scoping).
- The budget windows declare a renewable horizon `[0, 7200)` only: fleet power after the
  last window end contributes no excess (the Epigenomics cells run for days of simulated
  time). Greens of 300/600 W were chosen deliberately tight against the 2,000/8,000 W idle
  draw so the family demonstrates binding excess everywhere; under-budget zero-carbon
  behavior exists in the contract but is not exercised by this family.
- Grams are location-based, declared-model values at the frozen intensity — not
  measurements, not marginal-emission accounting (design doc v1 scoping).

## Retention

### Verifying the power variant

The power variant stores its index at `output/benchmark-standard-power-v1/benchmark-power.json`
and the same command pattern works per-manifest:

```bash
PYTHONDONTWRITEBYTECODE=1 python3 scripts/verify-study-evidence.py \
  --output /new-path/power-audit.json \
  $(ls output/benchmark-standard-power-v1/runs/*/result.manifest.json | head -5)
```

Each powered bundle carries the per-VM `idlePowerWatts`/`maxPowerWatts` in the platform
section and the `energyMetrics` block; the auditor recomputes the conservation law
`E = P_idle×T_sim + (P_max−P_idle)×T_busy` from the manifest's own busy intervals. All 160
bundles audit with zero issues, including a negative control that adds +500 J and is caught.


The full evidence bundle (160 runs × v4 evidence package) is retained at
`output/benchmark-standard-v1/` and as a tagged tar artifact
`output/benchmark-standard-v1-evidence.tar.gz` (21 MB). It is not modified or re-labeled.
