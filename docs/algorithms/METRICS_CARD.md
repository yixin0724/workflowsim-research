# Paper-Metrics Conventions Card (Metrics Writing Card)

> A one-page reference for citing this platform's metrics correctly when writing a paper or a report. Each row does three things: our conventions (formula / sample domain) → a one-sentence difference from the closest conventions in the literature → writing advice.
> Authoritative definitions are those of the [algorithm, execution, and metrics contract](<CONTRACTS.md>); this card concentrates only on "citation differences" and introduces no new semantics.
> Review-project provenance: scientific-validity audit chain A (2026-10-20).


## Time Family

| Metric | Our conventions | Difference from the closest conventions in the literature | Writing advice |
|---|---|---|---|
| `makespanSeconds` / `simulationEndSeconds` | The simulation termination clock | Literature makespan usually refers to the workflow completion span; our positive terminal-state tail (post-delay, V3 write-back) is folded into this value | State the "terminal-state clock" conventions; when comparing executor overhead, also report `terminalLifecycleTailSeconds` |
| `logicalTaskCompletionSeconds` | When all logical Tasks succeed, the maximum of the first successful envelope completion times | Differs from the previous conventions only by the tail; this metric is closer to the literature's makespan | Report both conventions and their difference; do not use the two names interchangeably |
| `terminalLifecycleTailSeconds` | Terminal state − logical completion | No direct counterpart in the literature | Cite it as the "write-back/wrap-up tail", not CPU |
| `computeJobOutcomeThroughputPerSecond` | Compute attempt count / terminal-state clock | The denominator includes the tail | It is enough to state the conventions |

## Waiting and Slowdown Family

| Metric | Our conventions | Difference from the closest conventions in the literature | Writing advice |
|---|---|---|---|
| `wait_j` (precursor of the waiting mean) | `JOB_READY → start` (waiting after dependencies are ready) | The literature commonly reports pure submit→start queueing | In papers, write "waiting after ready"; for VM-queueing conventions, use `meanJobVmQueueWaitingTimeSeconds` |
| `meanComputeTrueSlowdown` (including median/quantile/maximum/success-sample variants) | `max((wait+exec)/max(exec,10),1)`, on ready-baseline samples | The main literature family of "bounded slowdown" also uses a denominator floor (there is precedent for `1+Q/max(R,10)`, with the same floor of 10); cross-paper differences lie mainly in the floor value (1 s / 0.1 s variants) and in the numerator window (the literature mostly uses a submit baseline) | When citing, give the formula, the floor, and the window; do not read the "True" in the field name as unbounded stretch; compare across papers only under identical conventions |
| p95 / median (each variant) | Nearest-rank `ceil(qn)` on ascending samples | A systematic difference exists between interpolation-based quantiles and nearest-rank (p95 of a small sample degenerates to the maximum) | State the conventions and keep them fixed; do not merge quantile values across papers |

## Counts, Utilization, and Fairness Family

| Metric | Our conventions | Difference from the closest conventions in the literature | Writing advice |
|---|---|---|---|
| `logicalTaskAttemptCount` | Includes retry attempts | The literature may count per task instead | Give the definition when citing |
| `retryAmplificationRatio` | Attempt count / logical Task count | Not a failure rate | Include the formula when reporting |
| Success-rate family | A compute-attempt base rate and a logical-task rate coexist | The denominator choice is easy to confuse | State the denominator |
| `vmMetrics` | The union of completed Job intervals per VM / the terminal-state clock | Consistent with the busy/end common in the literature | Declare the interval composition, including stage-in/failures/retries |
| `vmUtilizationJainFairnessIndex` | `(Σu)²/(nΣu²)`; empty/all-zero → 1.0 | Consistent with the literature's Jain formula | Cite after declaring the degenerate semantics |

## Data Transfer Family

| Metric | Our conventions | Difference from the closest conventions in the literature | Writing advice |
|---|---|---|---|
| DATA_STAGE_IN_MODELED family | Nominal estimates (contention-free isolated seconds, referenced bytes, real external inputs) | The literature often loosely calls this "traffic" | Distinguish nominal estimates from actual contended wall-clock; state that these are referenced bytes, not transferred bytes |
| Contended FCT / utilization | Precise accounting from the sidecar ledger | See the NETWORK_LEDGER_VALIDATION contract for details | Cite the conventions of that contract |

## Energy Family

| Metric | Our conventions | Difference from the closest conventions in the literature | Writing advice |
|---|---|---|---|
| `totalEnergyJoules` | `Σ_vm [P_idle × T_sim + (P_max − P_idle) × T_busy,vm]` — per-VM rectangles of the linear utilization model, Beloglazov et al. FGCS 2012 Eq.(2), specialized to binary SPACE_SHARED (`u ∈ {0,1}`); `T_sim` = `makespanSeconds`, `T_busy` = that VM's `vmMetrics` modeled busy-interval seconds | The literature integrates `P(u) = P_idle + (P_max − P_idle) × u` over continuous utilization; with one-Job-per-VM execution the integral collapses to idle-and-active rectangles, so the same Eq.(2) yields exact, not approximate, accounting | State the binary specialization and the busy-interval domain together; the identity is recomputed from the manifest alone by the repository's Python auditor |
| `meanVmEnergyJoules` | `totalEnergyJoules / vmCount` (arithmetic mean over all declared VMs, idle-only VMs included) | The literature usually averages only serving hosts; ours includes every declared VM | State the denominator explicitly (fleet size, not busy-VM count) |
| `peakPowerWatts` | `Σ_vm P_max` — the fleet's nameplate peak, a declared platform constant | For an observed instantaneous maximum, it is an upper bound, not a measurement | Do not present it as a time-series power trace; it is `vmCount × P_max` by construction |
| `energyMakespanProductJouleSeconds` | `totalEnergyJoules × makespanSeconds` | The standard energy-delay product (EDP) of the DVFS literature; some papers use `E × D²` or weighted combinations | Name the exact product when comparing: EDP values under different objective forms are not interchangeable |

## Carbon Family

Conditional section: these metrics appear in a manifest's `carbonMetrics` only when a carbon model is declared; exactly one of the two models may be declared per run (mutual exclusion is enforced fail-fast). Both families ride the energy family's audited quantities — they add accounting, not physics.

| Metric | Our conventions | Difference from the closest conventions in the literature | Writing advice |
|---|---|---|---|
| `totalCarbonGrams` | Intensity model: `Σ_vm E_vm[J] / 3.6e6 × I_vm[g/kWh]` — hand formula: each VM's linear-model joules (see `totalEnergyJoules` for the rectangle law) converted to kWh (`J / 3.6e6`) and multiplied by that VM's declared grid intensity (location-based, gCO₂/kWh) | CASPER-family spatial tools (Souza et al., IGSC'23) compute `service_time × carbon_intensity(region, hour)` from live hourly grid data; ours is a static per-VM declared intensity, an explicitly stated proxy | Name the intensity basis as declared-location-based; state that no live grid feed enters the simulation. Zero under the budget model |
| `meanVmCarbonGrams` | `totalCarbonGrams / vmCount` (arithmetic mean over all declared VMs, idle-only VMs included) | The literature usually reports region-level totals; ours keeps the per-VM denominator explicit | State the denominator explicitly (fleet size, not busy-VM count); zero under the budget model |
| `carbonMakespanProductGramSeconds` | `totalCarbonGrams × makespanSeconds` | A carbon-delay product in the spirit of EDP; no single standard form exists in the literature | Name the exact product when comparing; zero under the budget model |
| `totalCarbonExcessWattSeconds` | Budget model: `Σ_windows ∫ max(0, P_fleet(t) − greenWatts) dt` over the window∩segment, with `P_fleet(t) = Σ_vm P_idle + concurrent × (P_max − P_idle)` rebuilt from the compute-job intervals (v1 assumes a homogeneous active delta across the fleet) | CWM/CaWoSched-family temporal scheduling (Schweisgut et al., ICPP 2025) defines carbon as an abstract over-budget excess integral, not gCO₂; both agree that under-budget execution is zero-carbon | State the unit honestly: watt-seconds of excess power, not grams; zero under the intensity model. Under-budget execution counting as zero-carbon must be stated when comparing with gram-based works |
| `windowExcessWattSeconds` | The budget family's per-window sub-map of `totalCarbonExcessWattSeconds`, keyed by window index; only windows with positive excess appear, and the values sum to the total | The literature reports a single aggregate objective; ours keeps the per-window breakdown so a third party can re-verify each window | Cite the window-declaration basis (declared renewable-availability scenario, not a forecast feed); the sum identity is recomputed by the repository's Python auditor |

## Citation Discipline (Template Sentences for the Performance Evaluation Subsection)

1. Together with every reported metric, give the conventions formula (or cite this card) and the sample domain (whether retries/failures/stage-in are counted).
2. For slowdown/waiting: declare the window baseline (ready vs submit) and the denominator floor (10 seconds).
3. Cross-paper comparisons are limited to identical conventions and identical denominators; when citing "bounded slowdown", state the floor value.
4. Always state in the threats-to-validity section: all metrics are products of the declared simulation model, not measurements of a production system.
