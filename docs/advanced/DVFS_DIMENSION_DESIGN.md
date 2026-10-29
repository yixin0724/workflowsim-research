# DVFS Dimension: Requirements Analysis and Architecture Design (v2)

This document designs the DVFS (Dynamic Voltage and Frequency Scaling) extension —
the third and final axis of the platform's energy trilogy (measure → price → control).
No code has been written yet — this is the contract-first design, following the
proven energy/carbon arc: survey → design → implementation batches → adversarial rounds.

## 1. Primary-source survey: how the field models frequency control

### 1.1 The canonical power-frequency law

The dynamic power dissipated by a CMOS processing element is:
```
P_dynamic = a · C · V² · f
```
where V is the supply voltage, f the clock frequency, and a·C a technology constant
([Cotes-Ruiz et al., PLOS ONE 12(1), 2017](<https://doi.org/10.1371/journal.pone.0169803>)).
Since V scales with f (lower voltage implies lower selectable frequency), dynamic power
is effectively **cubic in frequency** — a halved frequency draws roughly 1/8 the dynamic
power. This nonlinearity is the entire physics of why DVFS saves energy at all.

### 1.2 WorkflowSim-DVFS (Cotes-Ruiz et al.) — the closest prior extension

The nearest prior work extends the ORIGINAL WorkflowSim with five Linux DVFS
governors (Performance, Powersave, Userspace, On-demand, Conservative) organized as:

| Governor | Strategy | Realism claim |
|---|---|---|
| Performance | Static max frequency | Upper bound on speed, lower bound on efficiency |
| Powersave | Static min frequency | Lower bound on speed, upper on efficiency per Job |
| Userspace | User program selects | Research extension point |
| On-demand | Utilization threshold | Real production default (most Linux distros) |
| Conservative | Gradual threshold-based | Less aggressive than On-demand |

Their power model is "computing-plus-communication": per-Job computing cost uses the
P(aCV²f) law evaluated at the governor-chosen frequency; reconfiguration and network
costs are added; validated on real Intel Core 2 Quad Q6700 measurements and
Montage/Inspiral/Sipht traces at 25–1000 Jobs. Their published savings range is
**5–30%** vs the Performance governor baseline.

**What they lack — our differentiation surface**: independent energy audit
(no Python recompute), no conservation-law invariant test (no identity that a
third party can verify), no carbon dimensionride-through, no evidence-chain
manifests with per-Job frequency assignments, no frozen benchmark with per-tier
EDP accounting. This is the SAME dark-void the energy and carbon dimensions
exploited: audit and conservation are our unique moat.

### 1.3 The broader DVFS-workflow literature

| Paper | Venue | Model semantics |
|---|---|---|
| Mei et al. (arrXiv 2104.00486) | Energy-aware, deadline-constrained | DVFS-enabled heterogeneous clusters; slack-based stretching: tasks with schedule slack can be stretched to lower frequencies without extending the makespan; EDP is the optimization target |
| Cao et al. (referenced in Cotes-Ruiz) | DVFS DAG optimum-frequency | No governor disclosure, no source available |
| Guérout et al. | CloudSim-DVFS | DVFS into CloudSim with DAG support — but no failure model, no reconfiguration cost, no network cost |
| DEFT (arXiv 2608.02122, 2025/26) | Multi-GPU runtimes | Joint task placement and DVFS — GPU-specific, but the frequency-tier table is the same structure |
| Liu et al. (PMC 2025) | Heterogeneous multicore edge | Frequency tiers as per-core P-states; the multi-core aspect beyond v1 scope |

The **P-state table** (frequency, voltage → MIPS, static-idle power, dynamic-max power)
is the universal schema across all these works. Typical tier counts in practice:
3–5 discrete frequencies per processor (Intel SpeedStep, AMD PowerNow\!).

### 1.4 The PE-semantic decision (the v1→v2 architectural crux)

Our v1 platform runs SPACE_SHARED one-Job-at-a-time per VM with a binary
utilization bit (u∈{0,1}). DVFS means each Job gets an **assigned frequency tier**
that slows it (work_work/f_i takes longer) while drawing less dynamic power.
The time-power duality:

- **Lower f**: Job takes longer, draws less dynamic power, but exposes the fleet to
  MORE seconds of idle energy (P_idle × extended makespan).
- **Higher f**: Job finishes faster, draws more dynamic power (cubic), but idle
  energy accrues for fewer seconds.
- **Break-even ruling**: stretching pays only when the dynamic savings
  (ΔP_dynamic × original_T) EXCEED the idle extension cost
  (P_idle × ΔT_extension). This is where slack-based stretching finds its liberty:
  if the Job has schedule slack (its original completion is well before the
  workflow's critical-path makespan), the extension costs NOTHING (other VMs keep
  working) — the stretched Job lands in the slack pocket and only the dynamic
  savings count.

**Our v2 semantic contract**, made explicit:

1. **Assignment at Admission**: each compute Job receives exactly ONE frequency
   tier at the moment it is dispatched to a VM — the tier is fixed for the Job's
   full duration. NO dynamic mid-Job frequency switching. This preserves the
   one-Job-per-VM discipline and makes each Job's [start, finish) interval a
   constant-power rectangle — the auditable structure the energy conservation
   law already expects.
2. **Default tier**: the FIRST tier in the VM's list is the default (unassigned
   Jobs run at the declared default). We do NOT silently "pick the fastest."
3. **The extended conservation law**:
   ```
   E_vm = Σ_tiers [ P_idle(t) × T_idle(t) + (P_max(t) − P_idle(t)) × T_busy(t) ]
   ```
   where T_busy(t) is the total time VM spent executing at tier t (each busy
   interval is attributed to its assigned tier). When the platform has a single
   tier (v1 compatibility mode) this degenerates identically to the existing law.
4. **Idle power at the default tier**: when the VM is NOT executing, it draws
   the DEFAULT tier's idle power (not a mixed-frequency average). This is honest:
   idle power is measured at the parked frequency.

### 1.5 The carbon interplay

Both carbon models (intensity multiplier and green-budget excess) ride on the
energy chain with NO new physics:
- Intensity: grams per tier = E(tier)/3.6e6 × I — same multiplication, now on
  per-tier-conserved energy.
- Budget: fleet timeline becomes a per-tier fleet-power curve — P_fleet(t) now
  sums each active VM's ASSIGNED tier power rather than a uniform dynamic term.
  The integration is identical; only the per-instant fleet power changes.

The end-to-end verdict on "is lower frequency always greener carbon?": NOT
necessarily — this is exactly the break-even ruling above, now with two
multipliers (energy AND carbon) both following the same conserved energy.
If stretching extends the makespan, the carbon budget model may see MORE total
excess if the longer timeline crosses into browner windows. The published
savings range (5–30%) is for AVERAGE workloads with slack; always-positive
DVFS savings is NOT a law of nature and our design will not pretend it is.

## 2. Architecture v2

### 2.1 Data model

```java
PlatformProfile.VmSpec:
    + List<FrequencyTier> frequencyTiers    // default empty = v1 single-tier mode

FrequencyTier:
    + double mips              // speed at this frequency (MIPS, must be > 0)
    + double idlePowerWatts    // static draw at this P-state (≥0, finite)
    + double maxPowerWatts     // fully active draw at this P-state (≥ idle, finite)
```

The tier list is the P-state table. Its FIRST entry is the default.
Validation: tiers MUST be sorted by descending mips (fastest first — the
workstation-native convention); power values must satisfy the same
idle ≤ max constraint as the v1 power fields.

### 2.2 Assignment model

- **Config-level**: SimulationConfig gains an optional `frequencyPolicy` —
  the built-in policies are `DEFAULT` (every Job runs at the first tier —
  v1-degenerate), `MIN_POWER` (every Job runs at the LAST/smallest tier),
  `EDP_HEURISTIC` (each Job selects the tier minimizing EDP = E_tier ×
  estimated_T_tier, computed at dispatch with the Job's declared MI work).
- **Registry-level**: registered DVFS-aware algorithms declare `dvfsAware=true`
  and can assign tiers per-Job through a first-class scheduling-time API
  (the declaration model's powerModelRequired gate extends to require declared
  frequency territories).
- **Manifest-level**: each JobOutcome records its assigned tier mips + the tier's
  power pair per Job — the evidence chain carries the full per-Job assignment.

### 2.3 Extended conservation law (the invariant)

```
E_vm = Σ_tiers [ P_idle(tier) × T_sim + (P_max(tier) − P_idle(tier)) × T_busy(tier) ]

Wait — CORRECTION from the literature semantics (§1.4 point 4): idle power is
NOT uniform across tiers when the VM parks at the default. The correct form:
E_vm = P_idle(default) × T_idle
     + Σ_tiers [ P_max(tier) × T_busy(tier) ]   // busy = full draw at assigned tier
```
When there is only one tier (list size 1 or empty), every busy second draws
P_max(delta) exactly as in v1. When tiers exist, the VM's idle seconds draw the
default tier's idle power and each busy interval draws the FULL max power of
its assigned tier (idle+dynamic are already combined in P_max). This maintains
the rectangle-per-Job auditability and degenerates to v1 exactly.

### 2.4 Audit chain recompute

The Python auditor extends the energy conservation block: reconstruct per-tier
busy time from the manifest's per-Job tier assignments (the JobOutcome carries
tierMips; the identity of tiers is derivable from the platform's
frequencyTiers by mips match — tiers are unique by mips), verify per-VM and
total energy. The carbon models follow with NO additional recompute logic
(their multipliers operate on the already-verified energy).

### 2.5 Workbench JSON surface

```json
"platform": {
  "vmMips": [...],
  "frequencyTiers": [
    {"mips": 2000, "idlePowerWatts": 250, "maxPowerWatts": 400},
    {"mips": 1000, "idlePowerWatts": 220, "maxPowerWatts": 280},
    {"mips": 500, "idlePowerWatts": 210, "maxPowerWatts": 240}
  ]
}
```
Validation: mips strictly descending; power pairs valid; when frequencyTiers
declared, the plain idlePowerWatts/maxPowerWatts fields MUST NOT also be
declared (the first tier REPLACES them — one source of truth).

### 2.6 Registry declaration surface

```java
AlgorithmDeclaration:
    + boolean dvfsAware (default false)
    + boolean frequencyTiersRequired (default false)
```
Implication lattice: frequencyTiersRequired ⇒ dvfsAware (same as the energy
and carbon lattices). Registry gate: a frequencyTiersRequired declaration
on a platform with no declared frequencyTiers → fail-fast citing the clause.

## 3. Honest scoping (v2 deliberate omissions)

| Omitted | Reason |
|---|---|
| **Dynamic mid-Job frequency switching** | Breaks the one-rectangle-per-Job auditability; the literature's intra-Job P-states are a hardware reality but our evidence-chain discipline can't see mid-Job power transitions — we choose assignment-at-admission |
| **Heterogeneous per-VM non-uniform voltage tables** | Real per-VM voltages add a dimension without changing the per-Job rectangle; deferred to v3 |
| **Multi-core DVFS** | Our SPACE_SHARED discipline is one-Job-per-VM single-PE; multi-core DVFS is a different execution model |
| **Thermal / green scheduling beyond carbon** | Thermal simulation is a separate physics; carbon IS our environmental dimension |
| **Reconfiguration cost** (Cotes-Ruiz's transition energy) | An honest v2 simplification — transition energy (\~50μJ level) is 6+ orders of magnitude below the per-Job energy scale and below our simulator's time granularity; documented as excluded with the magnitude justifying it |
| **Exact EDP optimization** | The EDP_HEURISTIC policy is per-Job greedy (each Job independently picks its minimum-EDP tier at dispatch); the global-optimal EDP is an NP-hard cross-Job optimization — delegated to registered DVFS-aware algorithms |

## 4. Effort estimate and batch plan

| Batch | Content | Est. |
|---|---|---|
| D1 | FrequencyTier value class + VmSpec tier list + PlatformProfile builder surface + tier validation + extended energy conservation law + red→green unit-law tests | 1.5 d |
| D2 | Per-Job assignment storage (JobOutcome tierMips) + config-level frequencyPolicy + manifest per-Job tier identity +台账 metrics (per-tier busy seconds, per-VM energy at each tier) + conservation identity at multi-tier fixtures | 1 d |
| D3 | Python auditor: per-tier busy reconstruction + conservation recompute + carbon both models under per-tier fleet + self-tests | 1 d |
| D4 | Workbench JSON tiers surface + validation + policy selection + registry dvfsAware/frequencyTiersRequired fields + rejection tests | 0.5–1 d |
| D5 | Frozen DVFS benchmark variant (multi-tier grid under DEFAULT/MIN_POWER/EDP policies) + tar + audit + docs + comparison table honestly framed | 1.5 d |
| D6 | Registered DVFS-aware demonstrator label (EDP_HEURISTIC through the registry track) + first DVFS vs DEFAULT energy/EDP result + carbon comparison | 1 d |

## 5. Competitive positioning

| Simulator/Extension | DVFS | Energy chain | Carbon chain | Independent audit |
|---|---|---|---|---|
| WorkflowSim-DVFS 2017 | 5 governors | per-Job computing + reconfig + network | No | No |
| CloudSim-DVFS (Guérout) | DVFS into CloudSim | computing only | No | No |
| DEFT 2025/26 | Joint placement + DVFS | multi-GPU runtimes | No | No |
| **This platform (v2 target)** | **tier table + admission-assignment + 3 policies** | **per-tier conservation law** | **both models ride the chain** | **Python per-tier recompute** |

No surveyed DVFS extension of ANY workflow simulator carries a conservation-law
invariant or an independent recompute chain. The v2 target: the first DVFS
extension where every per-tier joule — and therefore every gram of carbon — is
independently verifiable by a third party with one Python command.
