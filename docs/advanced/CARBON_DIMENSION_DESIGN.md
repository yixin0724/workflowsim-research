# Carbon Dimension: Requirements Analysis and Architecture Design (v1)

This document designs the carbon-dimension extension following the platform's proven
literature-grounded arc. No code has been written yet — this is the contract-first design.
It builds directly on the audited energy dimension (conservation-locked per-VM joules).

## 1. Primary-source survey: the two carbon accounting models in the literature

The carbon-aware scheduling field does **not** agree on one carbon model — it uses two
structurally different accountings, and our design must choose explicitly.

### 1.1 The intensity-multiplier model (CASPER family)

[CASPER (Souza, Jasoria, Chakrabarty, Irwin, Shenoy + Chalmers, IGSC'23)](<https://arxiv.org/abs/2403.14792>)
optimizes **spatially**: six AWS regions, hourly LP re-provisioning (PuLP), Traefik
hourly-weight load balancing, multi-objective cost
`α·(carbon) + (1-α)·(latency)` with capacity `c_j` and fleet cap `K`. Their carbon
accounting is a **multiplier on energy**:
```
carbon(request) = service_time × carbon_intensity(region, hour)
```
Sources: Electricity Maps hourly data (2022), workload from Wikimedia traces, latency from
Cloudping. Results: **25% reduction at 20 ms latency budget, 37% at 100 ms, up to 70%**
at 500 ms — spatial shifting trades latency for carbon monotonically.
The multiplier model is the accounting used by essentially all grid-data tools
(gCO₂/kWh × kWh = gCO₂).

### 1.2 The green-budget-excess model (CWM / CaWoSched family)

[CWM (Schweisgut, Benoit, Robert, Meyerhenke, ICPP 2025)](<https://arxiv.org/abs/2605.27652>)
optimizes **temporally** with a fundamentally different accounting. The platform is
declared a **green power budget per time interval**: horizon `[0,T)` divided into J
intervals `I_j=[b_j,e_j)`, each with green power budget `G_j` per time unit. The carbon
cost is the **excess fleet power above the budget**:
```
carbon(t) = max(0, P_total(t) − G_j(t))    for t ∈ I_j
CC = ∫₀ᵀ carbon(t) dt
```
With `P_total(t) = Σ_p P_idle^p + P_work^p·[p busy at t]`. Under-budget execution is
**zero-carbon** (renewables cover it); over-budget execution emits. The problem is
strongly NP-complete with **no constant-factor approximation** (single processor suffices);
CWM = per-interval processor-subset knapsack (DP) + HEFT-SL adaptation + local search;
**42% median carbon reduction over the best CaWoSched variant** at deadline = 2× the
carbon-agnostic makespan (CaWoSched itself −36% vs baseline; CaWoSched = the earlier
fixed-mapping+ordering shifter of [25] — our earlier survey cited this paper's 42/36%
correctly).
Key algorithmic semantics from the paper we adopt as design constraints:
- Deadlines interplay directly (soft tasks stretch across intervals to cheap windows)
- Heterogeneous per-processor `P_idle`/`P_work` are first-class inputs
- The budget model treats carbon as an **abstract excess-cost integral**, not gCO₂

### 1.3 Metrics conventions

Grid-data tools express intensity in gCO₂/kWh (location-based; marginal exists but is
an accounting-policy choice — out of v1 scope). The two models are **not** interchangeable:
a low-intensity grid and a renewable-budgeted platform can disagree about which schedule
is "greener" (e.g., a huge always-on fleet under a generous budget is zero-carbon in CWM
terms yet large in CASPER grams). Our platform will implement **both, explicitly named**,
because conflating them would be a modeling error.

## 2. Architecture v1

### 2.1 The core decision: two named carbon models, both auditable

Following §1's finding, the platform declares two carbon accounting contracts. Both ride
on the energy dimension's per-VM busy/idle windows — **no new physics, only new accounting
on already-conserved quantities**:

**Intensity model (`CARBON_INTENSITY_V1`)** — multiplier accounting:
```
VmSpec:  + double carbonIntensityGramsPerKWh        // declared per VM (region proxy)
Metric:    totalCarbonGrams = Σ_vm  3.6 × E_vm(J) / 3600 × intensity_vm(g/kWh)
           perVmCarbonGrams = {vm: 3.6 × E_vm × intensity_vm / 3600}
           meanVmCarbonGrams, carbonMakespanProduct (g·s)
```
(E in J → Wh = E/3600; ×3.6 converts g/Wh to g/kWh scale: grams = E[J]/3600 × I[g/kWh].)
This is the **CASPER-family accounting**; heterogeneous per-VM intensity models
geo-distributed fleets. Energy either must be powered (E>0 interval means carbon>0 means
powered) or intensity-with-unpowered-E is silently zero — rejected (below).

**Budget model (`CARBON_BUDGET_V1`)** — excess accounting:
```
PlatformProfile:  + List<CarbonWindow> carbonWindows      // [startSeconds, endSeconds, greenWatts]
Metric:    totalCarbonExcessWattSeconds = ∫₀ᵀ max(0, P_fleet(t) − green(t)) dt
           perVM shares = vm's contribution at over-budget instants
```
The **P_fleet(t) curve is exactly derivable from per-VM busy windows + the linear power
model** (P_idle constant + P_work active windows) — the data the auditor already
recomputes. This is the **CWM-family accounting**; window arrays are the researcher's
declared renewable-availability scenario (like CaWoSched's `G_j` intervals). Windows must
cover [0, T_sim) exactly, be sorted, non-overlapping: fail-fast otherwise.

### 2.2 Manifest + auditor (all three parties, energy-discipline style)

- `energyMetrics` unchanged; new sibling `carbonMetrics` section emitted when either
  carbon model is declared (else absent — legal)
- Auditor recompute: intensity model = multiply audited E (already component-verified)
  by declared intensities, compare; budget model = rebuild the fleet-power timeline from
  the same busy intervals + power fields, integrate excess per window, compare. **Both
  carbon metrics therefore rest on quantities that already passed the energy
  conservation law** — carbon can't silently drift while energy is locked
- Same conditional-audit pattern (declared → required; undeclared → skipped; legacy
  manifests unaffected)

### 2.3 Workbench JSON surface
```
"platform": { "carbonIntensityGramsPerKWh": 380.0 }        // uniform, intensity model
"platform": { "carbonWindows": [ {"startSeconds":0,"endSeconds":3600,"greenWatts":500}, ... ] }
```
Validation: both together rejected (one model per run — the contract is the analysis);
budget windows well-formed; intensity ≥ 0 finite.

### 2.4 Interplay with energy (the honest decision)

- **Intensity model requires a powered profile**: carbon multiplies *energy*; an
  unpowered profile makes it vacuous. Fail-fast with the power-model message (mirrors
  E3's registry gate).
- **Budget model also requires powered**: the fleet-power curve needs P_idle/P_work.
- Algorithm declarations: `carbonAware`/`carbonModelRequired`/`carbonObjectiveWeight`
  fields mirroring the E3 triple with the same implication lattice.

### 2.5 Registry + first carbon-aware scheduling comparison (E5-style end)

A registered `CARBON_DEFER_FIRST` demonstrator: among ready jobs, defer non-critical-tail
work into declared cheap windows (budget model) — the CWM temporal-shift spirit in an
online label. First result: carbon grams/excess vs carbon-agnostic baseline, statistical
signs per layering discipline.

## 3. Honest scoping (v1 deliberate omissions)

| Omitted | Reason |
|---|---|
| Grid datasets / forecasts (Electricity Maps feeds) | External-data dependency (unauditable provenance); researchers declare windows/intensities — same candor as CWM's synthetic power profiles |
| Marginal vs location-based accounting | Accounting-policy dispute; v1 is location-based only, stated in the metric contract |
| Spatial multi-region execution | Our platform is single-fleet; per-VM intensity captures heterogeneity, not geo-routing (CASPER systems work, different layer) |
| CWM-class DP mapping algorithm | v1 ships the accounting + registry hooks + a demonstrator label; the NP-hard joint mapping is the algorithm-research follow-up |
| Carbon in grouped-workflows module | Independent lifecycle (unchanged) |

## 4. Competitive positioning

No surveyed simulator implements carbon accounting with an independent recompute chain.
CWM/CaWoSched evaluate algorithms on their own accounting; CASPER measures with live
data — neither publishes a conservation-law-style invariant a third party can verify from
the artifacts alone. Our carbon metrics will inherit the energy chain's discipline:
**every gram (and every excess watt-second) recomputable by a Python command.**

## 5. Effort estimate

| Batch | Content | Est. |
|---|---|---|
| C1 | Model fields + both accountings + metrics + conservation/identity tests red→green | 1.5 d |
| C2 | Manifest export + Python recompute (both models) + deferred-window recompute + self-tests | 1 d |
| C3 | Registry carbon fields + workbench JSON + validation | 0.5 d |
| C4 | Docs + frozen carbon benchmark variant (workflow-aware intensity-window scenarios) | 1 d |
| C5 | CARBON_DEFER_FIRST demonstrator + first audited carbon result | 1–2 d |
