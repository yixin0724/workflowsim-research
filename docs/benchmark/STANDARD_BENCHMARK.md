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

### DVFS variant benchmark-standard-dvfs-v1

The same frozen study conditions (identical inputs, VM tiers, seed 11 — but see the honest
sizing disclosure below) re-run on 2026-10-29 under the DVFS dimension (batch D5 of the
[DVFS dimension design](<../advanced/DVFS_DIMENSION_DESIGN.md>), protocol
`benchmark-standard-dvfs-v1`, index `benchmark-dvfs.json`) — the platform's first
**tri-dimensional** benchmark: every cell's platform declares the frozen 5-tier DVFS
P-state table *and* the uniform per-VM carbon intensity `carbonIntensityGramsPerKWh =
380.0` of the carbon variant, so `energyMetrics` follows the per-tier conservation law,
per-Job `tierMips` assignments ride the evidence chain, and `carbonMetrics` multiplies
the per-tier-conserved joules (see
[Command](<../../experiments/src/main/java/org/workflowsim/experiments/benchmark/BenchmarkExecutor.java>)).

**Frozen fleet.** Every VM (1000-MIPS base) carries the one uniform P-state table below
(first tier = declared default; `mips` strictly descending; declared values, not generator
output — a *declared* model):

| tier | mips | idlePowerWatts | maxPowerWatts | motivating law |
|---|---:|---:|---:|---|
| turbo (default) | 2000 | 250 | 4000 | overvolted above the efficient point (3750 W dynamic) |
| nominal | 1000 | 250 | 400 | = the power variant's Beloglazov envelope (150 W dynamic) |
| half | 500 | 100 | 118.75 | dynamic 150×(0.5)³ (exact cubic); idle 0.2 W/MIPS |
| quarter | 250 | 50 | 52.34375 | dynamic 150×(0.25)³; idle 0.2 W/MIPS |
| eighth | 125 | 25 | 25.29296875 | dynamic 150×(0.125)³; idle 0.2 W/MIPS |

The parked draw is the *default tier's* idle (250 W × vmCount — identical to the power
variant's fleet idle), while busy rectangles draw each job's *assigned* tier max — so
per-MI busy energy falls monotonically toward the deepest tier (2.0 / 0.4 / 0.2375 /
0.209 / 0.202 J/MI) while idle exposure scales with makespan: this is the two-sided
break-even the results below expose.

**Honest sizing disclosure (recorded verbatim in the plan JSON).** The full grid would be
480 cells (the standard matrix's 16 algorithm combinations × 3 frequency policies); at
that size the variant's wall-clock budget (minutes, not tens) is missed, so the frozen
variant is a **90-cell representative subset**: 5 inputs × 2 VM tiers × 3 policies × 3
layer-representative algorithms (`SHARED_STORAGE_HEFT` for SHARED_DAG, `LOCAL_HEFT` on the
endpoint contention network for LOCAL_DAG, `FCFS` for ONLINE). Every input family and both
VM tiers are covered in full; each decision layer contributes its canonical algorithm; the
policy × policy comparison stays same-layer-only. The full run took ~20 s and the Python
audit below ~12 s.

Per-policy medians (30 cells each policy; medians per VM tier are over that tier's 15 cells):

| Policy | 8-VM med makespan (s) | 8-VM med energy (J) | 32-VM med makespan (s) | 32-VM med energy (J) | 8-VM med EDP (J·s) | 32-VM med EDP (J·s) |
|---|---:|---:|---:|---:|---:|---:|
| DEFAULT (all jobs @ turbo 2000) | 42,894 | 8.31e8 | 25,940 | 1.23e9 | 4.25e13 | 3.77e13 |
| MIN_POWER (all jobs @ eighth 125) | 290,220 | 1.80e8 | 239,713 | 1.19e9 | 8.18e13 | 2.85e14 |
| EDP_HEURISTIC (per-job greedy) | 54,835 | 2.24e8 | 37,726 | 4.12e8 | 1.23e13 | 1.55e13 |

Across all 30 cells per policy (medians; min–max): makespan — DEFAULT 4.16e4 s
(675 … 4.17e5), MIN_POWER 2.45e5 s (4.96e3 … 4.10e6), EDP_HEURISTIC 4.19e4 s
(1.33e3 … 4.17e5); totalEnergyJoules — DEFAULT 8.85e8 (4.27e7 … 8.56e9), MIN_POWER
2.68e8 (2.29e6 … 3.29e9), EDP_HEURISTIC 3.34e8 (1.41e7 … 8.06e9); EDP (J·s) — DEFAULT
4.01e13 (3.08e10 … 3.36e15), MIN_POWER 9.35e13 (1.50e10 … 5.18e15), EDP_HEURISTIC
1.48e13 (1.88e10 … 3.36e15); totalCarbonGrams — DEFAULT 9.35e4 (4.51e3 … 9.03e5),
MIN_POWER 2.83e4 (2.41e2 … 3.47e5), EDP_HEURISTIC 3.52e4 (1.49e3 … 8.51e5) — carbon
ratios are exactly the energy ratios at the frozen single intensity. The three policies'
tier mixes are cleanly separated by the fleet per-tier busy ledger: DEFAULT = all
compute busy at 2000; MIN_POWER = all compute busy at 125 (bookkeeping envelopes stay at
2000); EDP_HEURISTIC = mixed 2000/1000 on 25 of 30 cells and all-2000 on the 5
LOCAL_DAG cells.

**The break-even ruling, honestly observed (not asserted):**

- **MIN_POWER is *worse* in total energy than DEFAULT on 6 of 30 cells** (up to
  ×1.322 on Epigenomics_100-v32-LOCAL_HEFT and ×1.301 on
  Epigenomics_100-v32-SHARED_STORAGE_HEFT; also Inspiral_100-v32 both layers and the two
  CyberShake LOCAL_HEFT cells). Energy's idle-exposure side (31 extra parked VMs at
  250 W over a ~15× stretched makespan) exceeded the deep-tier busy savings — precisely
  the pathological side of the design's §1.4 break-even law. On the other 24 cells
  MIN_POWER *saves* energy, down to ×0.007 (−99.3%) on CyberShake_100-v8-FCFS, the only
  ≥98%-utilization cell of the matrix — the slack/density side of the same law. The
  published 5–30% average savings range of the DVFS literature sits inside this observed
  [−99.3%, +32.2%] span, and the direction is decided by utilization, not by the policy.
- **Are EDP cells worse than DEFAULT? On energy: no (0 of 30 — 25 better, 5 equal). On
  the EDP product itself: one cell is worse** — Epigenomics_997-v8-SHARED_STORAGE_HEFT at
  ×1.0061 (makespan ×1.1424 while energy improves ×0.881): the per-job greedy can win
  energy and still lose the product when its makespan estimate misses the fleet idle
  exposure — exactly the honesty point of the design §3 ("exact EDP optimization"
  deliberately omitted; the greedy is dispatch-local). Its medians: energy ×0.377,
  EDP ×0.369 vs DEFAULT.
- MIN_POWER's medians show the *austerity trade*: −69.7% energy and carbon but ×5.9
  makespan — its median EDP product is ×2.33 worse than DEFAULT's.
- The EDP_HEURISTIC tier mix is *clock-dependent* (the greedy's makespan estimate charges
  each candidate tier's idle exposure at dispatch): early LONG jobs take the 1000 tier,
  late/short jobs stay at 2000 — and on the transfer-dominated LOCAL_DAG cells every
  dispatch clock is late enough that the greedy collapses to all-2000 (the 5 "equal"
  cells).

Relative verification (from the repository root, no Java required):

```bash
PYTHONDONTWRITEBYTECODE=1 python3 scripts/verify-study-evidence.py \
  --output /new-path/dvfs-audit.json \
  $(ls output/benchmark-standard-dvfs-v1/runs/*/result.manifest.json)
```

All 90 bundles audit with **PASS, 0 issues (2,761,384 automated checks)** — the per-tier
conservation law (per-Job `tierMips` groups, union per tier, default-tier idle seconds,
BAT-D2 slack), the tier-speed task-window recompute (BAT-D6), and the intensity grams
riding the per-tier joules are all recomputed from each manifest's own fields. Negative
controls in the Python self-test suite kill the v1-speed window forgery and per-tier
attribution corruptions. Evidence is retained at
`output/benchmark-standard-dvfs-v1/` and as
`output/benchmark-standard-dvfs-v1-evidence.tar.gz` (12 MB); joules and grams are
declared-model values at the frozen P-state table, not measurements.

Honest bounds of the DVFS variant, beyond the boundary statements of the standard, power,
and carbon benchmarks:

- **The tier table is a declared model.** The cubic dynamic / linear leakage laws motivate
  the frozen values but the numbers are declared constants (the deep tiers' busy max lies
  below the default tier's *idle* — a parked-turbo VM draws more than a deep-tier busy
  one; a real P-state table's deep idle would close this, and the declared form keeps the
  per-tier law deterministic and auditable either way).
- **Assignment is dispatch-local**: one tier per job at admission, no mid-job switching
  (design §3 deliberately omits it); MIN_POWER/EDP are fleet-uniform or per-job-greedy
  *policies*, not schedulers — the registered DVFS-aware track (EDP_GREEDY, batch D6)
  is the demonstrator for real co-scheduling, and its fixture ledger is honest about
  trading time for energy.
- No re-runnable tiered evidence yet: the manifest now records
  `configuration.frequencyPolicy`, but a tiered bundle's rerun still fails loud on the
  platform face (the tier-table rebuild is a documented forward contract, §2.6).

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
