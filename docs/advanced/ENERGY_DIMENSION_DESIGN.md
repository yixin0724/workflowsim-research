# Energy Dimension: Requirements Analysis and Architecture Design (v2)

This document presents the enriched design for the energy-dimension extension, grounded in
a primary-source literature survey. It supersedes v1 entirely; no code has been written yet.

## 1. What the field actually does — survey findings from primary sources

### 1.1 The canonical power model (Beloglazov et al.)

The linear power model — used by every downstream system we surveyed — was formalized in
[Beloglazov, Abawajy, Buyya, FGCS 28(5), 2012](<https://doi.org/10.1016/j.future.2011.04.017>)
(3790+ citations):
```
P(u) = P_idle + (P_max − P_idle) × u
E = ∫ P(u(t)) dt
```
In a discrete-event simulation where SPACE_SHARED VM-execution is binary (one Job at a time),
`u ∈ {0, 1}` — so the integral collapses to idle-and-active rectangles. This is the exact
same mathematics as our transfer byte-conservation law; our energy conservation law adds one
multiplication to fields the manifest already carries.

### 1.2 WorkflowSim-DVFS — the closest prior extension of WorkflowSim

[Cotes-Ruiz et al., PLOS ONE 12(1), 2017](<https://doi.org/10.1371/journal.pone.0169803>)
extended the original WorkflowSim with a computing-plus-communication power model, integrating
5 Linux DVFS governors (Performance, Powersave, Userspace, On-demand, Conservative). Their
power model per frequency level (`P = aC·V²·f`) is grounded in physical measurements of an
Intel Core 2 Quad Q6700. They added per-frequency MIPS, voltage, power tables; per-governor
state machines; intra-host DVFS reconfiguration costs; and network power. Their published
savings are 5-30%. **What they lack**: independent energy-audit (no Python recompute), no
energy conservation-law test, no frozen-benchmark evidence chain, no registry-based
energy-aware algorithm declaration. This is our differentiation surface.

### 1.3 Carbon-aware scheduling — the 2024/25 frontier

| Paper | Venue | Model | Reduction | Carbon source |
|---|---|---|---|---|
| [CASPER (Souza et al.)](<https://arxiv.org/abs/2403.14792>) | IGSC'23 | Spatial: multi-region LP optimization (K8s; 6 AWS regions) | up to 70% (web services) | Electricity Maps (hourly, real grids) |
| [CWM (Schweisgut, Benoit, Robert et al.)](<https://doi.org/10.1145/3754598.3754673>) | ICPP 2025 | Temporal: DAG fixed mapping + carbon-intensity(t); greedy + local search + DP-based mapping | 42% median over carbon-agnostic baseline | Declared green-energy windows per datacenter |

**Key structural finding**: all carbon-aware schedulers compute carbon as
`energy × carbon-intensity(location, time)`. The carbon intensity comes from external
(grid-operator) datasets. **No simulator has a carbon model built in** — it's always a
multiplier the researcher controls. Our declared-multipplier design is literature-aligned.

### 1.4 Energy-aware DAG/list scheduling variants

| Family | What they modify | Published range |
|---|---|---|
| EHEFT / GreenHEFT / IAEWS | rank formula → energy-weighted rank; insert → energy-cost insert | 10-25% energy reduction vs. HEFT, 0-15% makespan degradation |
| Multi-objective evolutionary (NSGA-II, GOA) | Pareto front (makespan, energy) | 15-30% Pareto-dominance |
| Energy-delay product (EDP) | `E × makespan` scalarized bi-objective | Standard |
| VM consolidation | pack onto fewer hosts, sleep remainder | 20-80% |

### 1.5 DVFS in workflow scheduling

- Published frequency-selection: slack-based (stretch non-critical tasks to lower frequency
  tier while keeping critical-path at max), governor-based (on-demand, conservative)
- **EDP = Energy × Makespan** is the standard combined objective
- Published savings: **5-30%**; reconfiguration overhead ~100 j (negligible)
- Conservative governor achieves the best energy-efficiency ratio under dependency slack

### 1.6 Competitive landscape

| Simulator | Power model | DVFS | Energy metrics | Independent audit | Carbon |
|---|---|---|---|---|---|
| WRENCH / SimGrid | pstates (per-freq V, f) + link power | Yes | per-host, per-link | No | No |
| CloudSim 3 | PowerModel hierarchy, avg per Host | No | Datacenter-level | No | No |
| WorkflowSim-DVFS (2017) | Eq(2) linear, per-frequency | Yes (5 governors) | Per-VM | No | No |
| **This platform (target)** | Eq(2) linear (Beloglazov anchor) | **v2 reserved** | Per-VM + total + EDP | **Python verifier** | **Declared multiplier** |

---

## 2. Architecture

### 2.1 The auditable energy extension

**Data model** (new VmSpec fields)
```java
+ double idlePowerWatts;
+ double maxPowerWatts;
```

**Energy conservation law** (this platform's unique enforced invariant)
```
E_vm = P_idle × T_idle + P_max × T_active
E_total = Σ E_vm = Σ P_idle × T_sim + (P_max − P_idle) × T_active
```
where `T_sim` = `makespanSeconds` and `T_active` per VM is already `vmIntervals` in
the metrics — all existing fields. The Python verifier recomputes this identity from the
manifest's own data.

**Metrics** (new SimulationMetrics fields)
| Metric | Formula |
|---|---|
| `totalEnergyJoules` | conservation-law result |
| `meanVmEnergyJoules` | `E_total / vmCount` |
| `peakPowerWatts` | `Σ P_max` (fleet peak) |
| `energyMakespanProductJouleSeconds` | `E_total × makespan` (EDP) |

**No new event types.** `JOB_EXECUTION_STARTED / TASK_EXECUTION_MODELED / JOB_RETURNED`
already pin every utilization-flip instant.

**Algorithm registry**
```java
AlgorithmDeclaration:
+ boolean energyAware (default false)
+ boolean powerModelRequired (default false)
+ double energyObjectiveWeight (default 0)
```

**Combination matrix**: if power fields are declared, energy metrics are computed;
if not, `totalEnergyJoules=0` is a legally declared run (backward-compatible).

**Python auditor**: recomputes `totalEnergyJoules` from vmIntervals + power fields;
or verifies `=0` when no power model is present.

### 2.3 v2 reserved schema (DVFS and carbon, not in v1)

**DVFS**: per-VM frequency tier table `(f_i, MIPS_i, P_idle_i, P_max_i)` — changes the
one-Job-per-VM PE discipline; deferred. v1 profile schema should include a `List<PowerTier>`
(default empty = one tier) so v2 code is additive.

**Carbon**: `PlatformProfile` gains `double carbonIntensityGramsPerKWh`.
`totalCarbonGrams = energy_at_each_VM × carbon_intensity`. Researchers who need
time-varying intensity use the registry's `energyObjectiveWeight`. We don't ship grid
datasets (external-data dependency; the researcher controls and declares their own,
consistent with CaWoSched/CWM's approach).

## 3. Honest scoping

| Exclusion | Reason |
|---|---|
| Thermal model | Adds thermodynamic simulation, not a declared model |
| Hardware calibration | Internal-validity tradition |
| Real carbon-intensity datasets | External-data dependency, can't be audited |
| Multi-frequency in v1 | Changes one-PE-one-Job semantic contract |
| Network/switch power | The platform simulates workflow execution, not infrastructure |
| VM migration energy | Platform placement discipline is preflight-pinned |

## 4. Effort estimation and batch plan

| Batch | Content | Est. |
|---|---|---|
| E1 | VmSpec power fields + engine energy integral + 4 metrics + conservation-law R→G test | 1-2 days |
| E2 | Manifest export + Python recompute + self-test | 1 day |
| E3 | AlgorithmDeclaration energy fields + registry validation | 0.5 day |
| E4 | Capability matrix + METRICS_CARD + benchmark rerun with power profiles | 1 day |
| E5 | (Optional) first carbon-aware result: a declared-carbon benchmark | 2-3 days |

## 5. Competitive positioning

After surveying WRENCH/SimGrid pstates, CloudSim 3 PowerModel classes, and Cotes-Ruiz's
WorkflowSim-DVFS: **no published energy-aware simulator makes energy metrics independently
recomputable by a third party with one command.** All either (a) compute energy and report
it without a conservation-law test, (b) have no energy dimension at all, or (c) rely on
external-data carbon intensity without making the energy integral itself auditable.

This platform will be the **first energy-aware workflow simulator with an evidence chain**:
every joule in the benchmark is backed by a conservation invariant recomputable from the
manifest alone. The fact that `totalVmModeledBusyIntervalSeconds` already exists means the
conservation law is testable from Day 1, not retrofitted.
