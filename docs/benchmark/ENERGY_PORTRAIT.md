The # Energy Portrait of the Algorithm Corpus

The The platform's first statistical energy study: the ten-algorithm corpus of
[`benchmark-standard-power-v1`](<STANDARD_BENCHMARK.md>) analyzed under the frozen
homogeneous power model (250 W idle / 400 W max per VM) across 160 audited runs —
5 inputs × 2 VM tiers × 3 decision layers × (network families where declared).
Every number below is independently regenerable with:

```bash
PYTHONDONTWRITEBYTECODE=1 python3 scripts/analyze-energy-portrait.py \\
  output/benchmark-standard-power-v1 /new-path/portrait.json
PYTHONDONTWRITEBYTECODE=1 python3 scripts/test-energy-portrait.py
```

## Headline findings

1. **Conservation law holds on every audited run** — 160/160 exact under the
   Beloglazov linear model (scale-aware tolerance for 10^10-scale doubles).
2. **One Holm-significant algorithmic fact**: at this corpus size,
   `SHARED_STORAGE_PEFT` is significantly **worse than the family reference
   `SHARED_STORAGE_HEFT` on EDP** (paired two-sided sign test, 9 worse / 1
   better, raw p = 0.0039, Holm p = 0.0156, m = 4). No other cross-label
   EDP difference in the corpus survives correction.
3. **Energy–makespan coupling is quantitatively understood**: in 201/207
   within-condition policy pairs, total energy co-moves with makespan exactly
   through the fleet-idle-power exchange rate. In all six departures (all
   with |ΔT| below ~10 s of 10^5–10^6 s schedules), the busy-interval drift
   term `ΔP·Δbusy` dominates the idle term `P_idle\u00b7ΔT`
   with an attributable dominance ratio of 1.0–9.0 — the analyzer reports
   the ratio for each departure, so the exceptions close.
4. **Two labels can be provably confounded**: `READY_BATCH_MCT` and `FCFS`
   produce *identical* assignment streams on uniform-VM fleets (10/10
   conditions tied, EDP equal to the bit). The sign test honestly discloses
   this as all-ties (no significance claim is possible or needed — it is an
   identity, not a similarity).

## Per-layer RMS-EDP Rankings (smaller is better; same-layer comparisons only)

| Layer · family reference | Rank order (RMS EDP, 10^14–10^15 scale) |
|---|---|
| ONLINE · ref FCFS | FCFS = MCT (2.711) < ROUNDROBIN (2.716) < MINMIN (2.738) < MAXMIN (2.773) |
| SHARED · ref HEFT | ETF (2.631) ≈ DLS (2.631) ≈ HEFT (2.631) < CPOP (2.631) < PEFT (2.654) |
| LOCAL · ref HEFT | **CPOP (36.6) < PEFT (83.7) < HEFT (88.1)** — n=20 spans two network families |

The LOCAL family spreads two orders of magnitude in RMS EDP (dominated by the
fat-tree-constrained cells at 0.125 MB/s where all planners schedule long
transfer-bound schedules).

## Acknowledged nonsignificance

- `SHARED_STORAGE_CPOP` vs HEFT shows 0 better / 6 worse / 4 ties (raw p = 0.031,
  Holm p = 0.094) — suggestive only, does not survive correction.
- All ONLINE labels vs FCFS: nonsignificant (p ≥ 0.109 before Holm).
- All LOCAL label pairs vs HEFT: strongly condition-dependent (CPOP wins/losses
  split 9/9 across inputs and tiers — the layer's energy behavior is
  input-dominated, not label-dominated, at the corpus size of this benchmark).

## Honest scope

- The power model is frozen and homogeneous (250 W/400 W); VMs are never
  destroyed, so the fleet idle power multiplies the entire makespan.
- Workload inputs, platforms, and network models are the frozen cells of the
  benchmark protocol — no cross-layer claim is made or implied.
- Paired conditions are (input × tier) cells; the sign test discards ties by
  definition and their counts are reported explicitly.
- This portrait is a **descriptive energy study** — the kind of result an
  auditing route makes defensible — not a general performance ranking.
- Busy intervals are *not* workload-fixed across policies (empirical drift up
  to ~0.2%); any future analysis that assumes otherwise must revisit
  the departure ratios reported here.

## Raw numbers

The retained artifact is regenerated to a fresh path on demand (the command
above); the retained evidence lives in `output/benchmark-standard-power-v1/`
plus its tar original. The analysis schema
(`workflowsim-energy-portrait-v1`) embeds the run rows, the sign-test
tallies, the Holm-adjusted p-values, and the monopoly attribution of all six
departures.

On a fresh clone without the retention tree, the analyzer reports "no run
manifests" and the self-test's retained-data case skips itself (CI runs the
statistical and synthetic branches only) — the published numbers require
the retained evidence or its tar original.
