# Energy Dimension: Requirements Analysis and Architecture Design

This document analyzes what the workflow-scheduling research community needs from an energy
dimension, what this platform should build, what it costs, and what it honestly does not
cover. No code changes are proposed yet — this is the contract-first design.

## 1. Literature landscape: what the field publishes about energy

The energy-aware workflow-scheduling field is structurally stratified into **six active
axes** (with representative citations in the paper that will cite this capability):

| Axis | What it does | Hotness |
|---|---|---|
| **DVFS (Dynamic Voltage Frequency Scaling)** | Adjusts processor voltage/frequency per task; energy = power × time at the selected frequency; classic but still heavily published ([Ahmad et al. 2026](<https://www.nature.com/articles/s41598-026-49785-5>)) | Active since 2012, always present |
| **Multi-objective makespan-energy** | Pareto trade-off between wall-clock and joules; typically evolutionary heuristics (NSGA-II, MOEA/D, GOA); the energy cost is computed per mapping and per sequencing decision | Very active (Guzek 2014 → Xing 2023 → Cao 2023) |
| **Power-model-based scheduling** | The linear power model `P(u) = P_idle + (P_max − P_idle) × u` (Beloglazov et al. 2012) is the community-standard; energy = `∫ P(u) dt` over the active window | Standard-built, not competitive |
| **VM-consolidation** | Reduces the energy by packing workload onto fewer machines and shutting down / sleeping the idle remainder | Active (Medra 2021, consolidation) |
| **Energy-aware HEFT variants** | Modified rank or insert formulas that use energy cost alongside or above EFT (e.g. EHEFT, greenHEFT, IAEWS) | Steady trickle |
| **Carbon-aware scheduling** | The newest axis (2024-25): shifts workloads **temporally** (to clean-energy hours) and/or **spatially** (across regions with different grid carbon intensity); publishes 10-70% reductions (CASPER, GreenCourier, CASA, [Carbon-Aware Scheduling overview](<https://www.emergentmind.com/topics/carbon-aware-scheduling>)) | **The hottest direction** |

**The power model** the community overwhelmingly uses is the **Beloglazov linear model**:
```
P_j(u_j) = P_idle_j + (P_max_j − P_idle_j) × u_j
E = Σ_j ∫_0^T P_j(u_j(t)) dt
```
where `u_j(t)` is instantaneous utilization, and in a discrete-event context `u` changes only
at task boundaries (piecewise-constant), so the integral becomes a sum of rectangles —
exactly the same mathematical structure as this platform's byte-conservation and
rate-area accounting for the transfer domain (a deep architectural disanalogy we
should exploit, not reinvent).

## 2. Gap analysis: what this platform lacks today

| Layer | Current state | What energy research needs |
|---|---|---|
| `PlatformProfile.VmSpec` | no power fields | `idlePowerWatts` + `maxPowerWatts` per VM (heterogeneous machines differ) |
| `WorkflowDatacenter` | tracks MI consumption only | must accumulate an energy integral per VM per run |
| `SimulationMetrics` | zero energy metrics | per-VM and total energy joules / peak watts / energy-makespan product |
| Events | no energy events | a `VM_ENERGY_ACCRUED` or just a field on the existing dispatch/settlement events? → **decision point** |
| Python auditor | no energy recompute | energy = per-VM piecewise integration, exactly the same mathematical class as `rate-area` |
| Algorithm registry | no energy-aware hooks | an energy-aware planner declaration surface (optional energy-cost scoring term) |
| Combination matrix | no energy-related row | a row declaring which configurations carry a power model and which must reject one |

## 3. Proposed architecture (contract-first)

### 3.1 Data model

```java
PlatformProfile.VmSpec:
    + double idlePowerWatts;   // required when the energy dimension is enabled
    + double maxPowerWatts;    // required; must satisfy maxPower >= idlePower >= 0
```

No DVFS in v1: the platform's declared-model tradition requires phantom-like exactness; a
multi-frequency model adds a state variable per host that the current code never touches.
We declare the VM runs at the constant nominal frequency (its `mips`), and the linear
power model accounts for the idle→active range. DVFS is deferred to v2 (see §4).

### 3.2 Energy equation (the conservation law we will enforce)

At any simulated time, the platform knows each VM's instantaneous active fraction:
`u(vm) = (the VM is currently executing a Job ? 1 : 0)` — because the PE discipline
(SPACE_SHARED, one Job at a time) makes this binary, not fractional. So:

```
E_vm = Σ_k P_idle × Δt_idle_k + P_max × Δt_active_k
E_total = Σ_vm E_vm
```

where the intervals are exactly the VM busy/sleep windows already tracked by the
engine and report classes. The **energy conservation law**:
**`E_total = Σ_vm P_idle × T_sim + (P_max − P_idle) × totalVmModelledBusyIntervalSeconds`**
— the same `totalVmModeledBusyIntervalSeconds` field already in every manifest. The
Python auditor recomputes this identity from the declared profile: zero new physics,
one new multiplication.

### 3.3 Metric surface

| New metric | Semantics |
|---|---|
| `totalEnergyJoules` | `Σ_vm E_vm` |
| `meanVmEnergyJoules` | arithmetic mean across VMs |
| `peakPowerWatts` | `Σ_vm P_max_vm` (the fleet peak if all simultaneously active) |
| `energyMakespanProductJouleSeconds` | `totalEnergy × makespan` (the EDP metric for multi-objective comparisons) |
| per-VM `vmEnergyJoules` fields | in the `vmMetrics` block, alongside utilization |

### 3.4 Events schema implications

**Decision: no new event types.** The existing `JOB_EXECUTION_STARTED` / `TASK_EXECUTION_MODELED` /
`JOB_RETURNED` events already pin the exact instants when each VM's utilization bit flips. The
auditor can derive `E_vm` from the existing event timeline plus the platform profile's power
fields — no schema revision (which would require user adjudication per the events-v1
contract discipline). The power model lives in `PlatformProfile` and is exported to the
manifest, not to the per-event stream.

### 3.5 Algorithm registry

`AlgorithmDeclaration` grows three optional fields:
- `energyAware` (boolean, default false) — declares the algorithm's scoring uses energy cost
- `powerModelRequired` (boolean, default false) — rejects configurations lacking VM power fields
- `energyObjectiveWeight` (double, default 0) — a declared trade-off weight, either singular or
  per-configurable

### 3.6 Combination-matrix additions

| New row | Energy-dimension-enabled |
|---|---|
| Any existing valid combination | if `PlatformProfile` declares per-VM power fields, energy metrics are computed; if not, all energy metrics are zero and `totalEnergyJoules=0` is a legally declared run (backward-compatible) |

### 3.7 Python auditor recompute

`verify-study-evidence.py` gains: if the manifest's platform profile carries power fields,
recompute `totalEnergyJoules` from the busy/sleep windows and compare to the published
metric (exact decimal, same as byte-conservation). If absent, verify `=0`.

## 4. Honest scoping: what we will NOT build

| Excluded | Reason |
|---|---|
| **Thermal model** | Would require heat-dissipation simulation that the declared-model tradition does not support |
| **Real-hardware calibration** | The platform's internal-validity route explicitly eschews production-fidelity claims |
| **Carbon-intensity time series** | Requires real-world grid data feeds and forecasting models — an external-data dependency we can't audit; researchers should treat carbon as a **declared per-VM multiplier** they control (e.g. "VM in region X has carbon intensity function gCO2/kWh at time t") |
| **DVFS multi-pstate** | Adds state variables that change PE semantics mid-run; the current SPACE_SHARED one-Job contract doesn't have room for a frequency switch point |
| **Switch/host power** | The platform simulates workflow execution, not infrastructure; leaving host/switch power out keeps the model domain-pure |

## 5. Effort estimation and batch plan

| Batch | Content | Est. |
|---|---|---|
| E1 | VmSpec power fields + engine energy integral + 5 new metrics + unit tests (conservation-law earlier Red-to-green) | 1-2 days |
| E2 | Manifest export + Python auditor recompute + self-tests | 1 day |
| E3 | AlgorithmDeclaration energy fields + registry validation + algorithm catalog doc rows | 0.5-1 day |
| E4 | Capability matrix doc + METRICS_CARD new rows + benchmark re-run with power fields | 1 day |
| E5 | An energy-aware IAEWS-style (energy + makespan-aware) baseline algorithm via the registry + first energy benchmark | 2-3 days (optional) |

## 6. Competitive positioning

WRENCH (on SimGrid) already has [pstates and energy plugins](<https://wrench-project.org/>) for host/link power —
it is the incumbent for external-fidelity energy simulation. What this platform uniquely
offers is: **auditable energy** — energy numbers that a third party can recompute from the
manifest's own busy/sleep timeline plus the declared power model, with the same
Python-auditor-by-one-command simplicity as the standard benchmark. This is the first
"energy evidence chain" in the workflow-scheduling simulation literature, to our knowledge.
