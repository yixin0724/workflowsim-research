# DVFS Dimension: Requirements Analysis and Architecture Design (v2)

This document designs the DVFS (Dynamic Voltage and Frequency Scaling) extension —
the third and final axis of the platform's energy trilogy (measure → price → control).
**AS BUILT (D1–D6, 2026-10-29):** the batches D1–D6 have all landed (D1 data model and
conservation law; D2 per-Job assignment + per-tier ledger; D3 Python per-tier auditor;
D4 Workbench JSON + registry surface + carbon ride-through; D5 the frozen tri-dimensional
benchmark `benchmark-standard-dvfs-v1`; D6 the registered `EDP_GREEDY` demonstrator and
the first DVFS-aware scheduling comparison). The original contract text is kept verbatim
below, with the as-built notes inline (§2.3/§2.4/§2.6) recording where the build pinned,
extended, or honestly bounded the contract — following the proven energy/carbon arc:
survey → design → implementation batches → adversarial rounds.

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

**As-built (D3, 2026-10-29).** The prerequisite export landed with it: the
manifest platform section serializes per-VM `frequencyTiers` as
`{mips, idlePowerWatts, maxPowerWatts}` triples in declared (descending)
order, the key omitted when the table is empty — v1 manifests stay
byte-identical (same omit-when-undeclared contract as `carbonWindows`).
Two consequences pinned by tests:

- A tiered VM keeps its flat `idlePowerWatts`/`maxPowerWatts` at `0.0`
  (single source of truth, BAT-D1), and tiered platforms count as *powered*
  on both sides — the Java power gate (`hasPowerModel() || hasFrequencyTiers()`,
  BAT-D2) and the Python `powered` predicate — so tiered runs can never again
  present themselves to the auditor as unpowered.
- The audit recompute confirms the attribution documented in §2.2: per-Job
  `tierMips` groups the audit's own [start, finish) intervals per tier (union
  per tier), jobs without a `tierMips` key run at the VM's default (first)
  tier. This includes the stage-in bookkeeping envelope — the same
  bookkeeping-counts-as-busy envelope that computed jobs partition, mirrored
  from the Java per-tier ledger.

Honest boundaries recorded with the batch:

- **Observation, not assertion**: when any busy second lands on a non-default
  tier, the per-tier law and D1's all-at-default formula differ; when the run
  is all-at-default (DEFAULT policy or unassigned), the two laws coincide
  exactly. The auditor records both numbers plus a `nonDefaultTierBusyObserved`
  flag in `notes` (`dvfs-tier-accounting-observation`) and asserts only the
  per-tier law — the discriminator pair (a split-tier fixture passing at the
  per-tier value while an all-at-default forgery is killed) is pinned in the
  Python self-tests.
- **Budget carbon under tiers is a forward contract**: the Java
  `CarbonAccounting` models still require flat power fields, so a tiered
  platform cannot yet reach a real `carbonMetrics` bundle; the Python tiered
  budget branch (per-instant fleet power = Σ parked VMs at their default-tier
  idle + each active Job at its tier's dynamic share) is implemented and
  self-tested from the tier ledger, awaiting the Java-side tiering mirror.
  The intensity model needs no change (it rides the verified energy).
  (D4 follow-up, 2026-10-29: the intensity half of this gap is now closed —
  see the as-built note in §2.6; the budget half remains a forward contract.)
- **Tiered manifests are not yet rerunnable**: `ManifestConfigRebuilder`
  rebuilds neither `frequencyTiers` nor `frequencyPolicy`, so rerunning a
  tiered manifest degrades to a v1 platform today. The rerun diff then goes
  red (platform/energy leaf differences are not whitelisted), so the failure
  is loud; the proper rebuild belongs to a later batch and should fail fast
  until then.

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

**As-built (D4, 2026-10-29).** Both §2.5/§2.6 surfaces landed as this section
specifies, with the following pins and one forward contract closed:

- **Workbench platform surface**: `frequencyTiers` is one uniform table applied
  to every VM (`vmMips`-style uniformity, "at most one declaration per config"
  enforced by the strict single-key reader); absent = v1 single-tier platform.
  Domain validation is REUSED, not duplicated: each entry constructs a
  `PlatformProfile.FrequencyTier` (mips>0 finite, idle≥0, max≥idle) and the
  strict-descending order is rejected by the `VmSpec` BAT-D1 constructor —
  the workbench surfaces the same contracts, it does not re-implement them.
  A declared-but-empty tier array is rejected (mirroring the Python BAT-D3
  "non-empty when declared" face; `null` JSON and absence are the v1 spelling).
  The one-source-of-truth rejection (tiers + flat power) and the
  policy-without-tiers rejection both cite both field faces up front.
  `frequencyPolicy` resolves to the `SimulationConfig.FrequencyPolicy` enum
  (DEFAULT absent-default; unknown labels rejected with the legal set) and
  flows to every candidate's SimulationConfig; the non-DEFAULT-requires-tiers
  rule is the read-time mirror of the runner's BAT-D2 gate.
- **Registry surface**: builder implication lattice
  (`frequencyTiersRequired ⇒ dvfsAware`, setter pulls the aware flag up like
  `powerModelRequired ⇒ energyAware`), registry validate clause citing the
  clause + platform field, `ValidationInput` 7-arg master carrying
  `platformFrequencyTiersDeclared` with the 6-arg/5-arg legacy delegates
  preserved (tiers default false), and `SimulationRunner` passing
  `anyVmHasFrequencyTiers(platform)` on the registry track.
- **Intensity carbon ride-through closed (the forward gap inverted)**: the
  D3 as-built recorded that a tiered platform "cannot yet reach a real
  `carbonMetrics` bundle" because both Java carbon models required flat
  power fields. `CarbonAccounting.intensityModel` now multiplies the frozen
  per-VM conserved energy (`EnergyAccounting` output — flat law for flat
  platforms, per-tier law for tiered ones) exactly as the Python auditor
  does (`grams = audited E / 3.6e6 × I`); a tiered profile counts as
  powered. Flat-platform results are bit-identical to the previous inline
  formula (same expression, same summation order — pinned by the existing
  unit-law test); the tiered branch is pinned by a §2.3-law red→green test.
- **Honest boundaries unchanged**: `ManifestConfigRebuilder` still rebuilds
  neither `frequencyTiers` nor `frequencyPolicy` (tiered manifests remain
  non-rerunnable — the diff fails loud); the registered DVFS-aware
  demonstrator and the first DVFS-vs-DEFAULT comparison belong to D5/D6;
  `carbonWindows` stay outside the v1 workbench surface.

**As-built (D5+D6, 2026-10-29).** Both closing batches landed; the contract sections
above held, with the following pins, one seam closed, and one honest demonstrator
ledger:

- **D5 — the frozen tri-dimensional benchmark `benchmark-standard-dvfs-v1`**
  (index `benchmark-dvfs.json`; see the
  [benchmark doc](<../benchmark/STANDARD_BENCHMARK.md>) for the full tables and
  how-to-verify). 90/90 cells completed in ~20 s wall clock (the honest sizing
  disclosure — the 480-cell full grid would miss the minutes budget — is recorded in
  the plan JSON and the doc); the Python auditor passed all 90 bundles with zero
  issues (per-tier conservation law, tier-speed windows, intensity grams riding the
  per-tier joules). The 5-tier fleet table is frozen in `BenchmarkExecutor.DVFS_TIERS`
  (turbo 2000 = the declared overvolted state; nominal 1000 = the power-v1 envelope
  anchor; the deep tiers follow the exact cubic dynamic and the 0.2 W/MIPS leakage
  idle). Break-even honesty, observed not asserted: MIN_POWER is *worse* in energy on
  6/30 cells (up to ×1.32, the idle-exposure side) and saves up to −99.3% on the one
  ≥98%-utilization cell (the slack side); EDP_HEURISTIC places the per-cell energy
  trade at 25 better / 5 equal / 0 worse, but its *EDP product* loses on 1/30 cells
  (×1.006) — the greedy's dispatch-local blindness, exactly §3's deliberate omission.
- **D6 — the scheduling-time per-Job tier API and the EDP_GREEDY demonstrator.** The
  registry face of §2.2 is realized as
  `org.workflowsim.scheduling.FrequencyTierSelector`: the active scheduling policy
  instance elects a per-Job tier at dispatch, the session policy stays the fallback
  (selector output is validated against the declared table by mips identity; null or
  undeclared tiers fail fast citing the admission/evidence-chain clauses).
  `EDP_GREEDY` (experiments test registry, SPI-registered) declares the full
  implication chain `dvfsAware + frequencyTiersRequired + powerModelRequired` — which
  surfaced and closed one real seam: the registry track's power fact now follows the
  energy chain's BAT-D2 "powered" predicate (`flat fields OR a tier table`), so a
  tiered fleet can no longer be misread as unpowered at the registry gate. The
  demonstrator's heuristic is the documented length-based tier split (long jobs to the
  last tier, short jobs to the default tier) on inherited FCFS placement — a
  demonstrator, not an optimizer: its honest ledger on the 3-tier tri-dimensional
  fixture trades **energy −32.4% (406,768 J → 274,955 J) for makespan ×3.27
  (62.71 s → 205.21 s), losing the EDP product ×2.21** — the first DVFS-aware
  scheduling result, tier mix 3×2000+1×500 against FCFS's all-2000, with the manifest
  evidence audited clean by the Python auditor inside the integration test.
- **One Java/Python seam closed under D5/D6 discipline (BAT-D6)**: the task-level
  model windows were still computed at VM base speed while Jobs execute at assigned
  tier speed (task windows landing *outside* their job envelope under any tier ≠
  base). Both sides now use the tier speed —
  `WorkflowDatacenter.updateTaskExecTime` and the auditor's
  `effective-task-window`/`cpu-lower-bound` recompute — pinned by a paired Python
  self-test (the v1-speed forgery next to a slow-tier claim is killed).
- **Manifest face**: every new manifest serializes `configuration.frequencyPolicy`
  (following the registered-label pattern); the tier table and per-Job `tierMips`
  serialize as specified in §2.4. Tiered bundles remain honestly non-rerunnable
  (the loud failure is the design's pinned behavior, not a regression).

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
