# WorkflowSim

WorkflowSim is a discrete-event simulator for research on scientific workflow scheduling. It covers task dependencies, compute resources, storage, data transfer, and scheduling decisions in a single declarative model, and emits auditable, replayable experiment results.

Simulation results support controlled, same-condition research comparisons; they are not substitutes for real-cluster monitoring, production-network prediction, or cloud-provider billing. The project provides the Java simulation environment and policy interfaces; it does not include deep-learning trainers.

**English** · [简体中文](<README.zh.md>)

## Quick Start

Requires **JDK 17+ and Maven 3.6.3+**. From the repository root:

```bash
# Validate inputs, parameters, and algorithm combinations
mvn -pl :workflowsim-workbench -am compile exec:java \
  -Dexec.mainClass=org.workflowsim.experiments.workbench.Workbench \
  -Dexec.args="validate experiments/configs/online-comparison.json"

# Run experiments and generate the offline interactive report
mvn -pl :workflowsim-workbench -am compile exec:java \
  -Dexec.mainClass=org.workflowsim.experiments.workbench.Workbench \
  -Dexec.args="run experiments/configs/online-comparison.json output/workbench"
```

Open `index.html` in the output root to browse experiment history, and `report.html` inside an experiment directory for scheme comparisons, task timelines, waiting time, and resource utilization. Each run uses a dedicated output directory; failure causes and completed results are both retained.

- Run from configuration and read reports: [Workbench guide](<docs/getting-started/WORKBENCH.md>)
- Build experiments with the Java API: [code-configuration guide](<docs/getting-started/CODE_CONFIG_EXPERIMENTS.md>)
- Build, test, and tooling commands: [build guide](<docs/getting-started/BUILD.md>)
- Browse by topic: [documentation center](<docs/README.md>)

## Choose an example

| Goal | Configuration |
|---|---|
| Compare online CPU scheduling policies | [Online scheduling comparison](<experiments/configs/online-comparison.json>) |
| Use network contention models | [Network comparison](<experiments/configs/network-comparison.json>) |
| Inspect per-file source, transfer, and CPU input gating | [Coherent file dataflow V2](<experiments/configs/coherent-file-dataflow-v2.json>) |
| Simulate bounded SOURCE storage and required output write-back | [Bounded storage dataflow V3](<experiments/configs/storage-dataflow-v3.json>) |
| Select a VM at control-ready time, then transfer inputs | [Online target binding with bounded storage](<experiments/configs/online-storage-dataflow-v1.json>) |

Examples use different support matrices; experiments cannot be constructed by arbitrarily mixing algorithms, file systems, and network options. The full scope is defined in the [dataflow capability matrix](<docs/advanced/DATAFLOW_CAPABILITY_MATRIX.md>) and the [algorithm catalog](<docs/algorithms/CATALOG.md>).

## Capability overview

| Area | Scope |
|---|---|
| Workflow inputs | Pegasus DAX XML and WfCommons WfFormat / WfInstances JSON; dependencies, work volume, and file declarations are read, while source-machine timelines are not replayed |
| Platform | Explicit Host/VM specifications, deterministic VM placement, capacity prechecks, and declared costs; CPU oversubscription, VM migration, and hardware monitoring are not simulated |
| Scheduling and planning | Online CPU scheduling, independent-task static planning, controlled SHARED-storage / LOCAL DAG planning, plus explicitly labeled random and search baselines |
| Coherent data transfer | V2 per-file source, path, merge, and visibility semantics; optional endpoint contention or actual Fat-tree path contention |
| Bounded storage | V3 storage with bounded read/write/NIC bandwidths, SOURCE attached to a host, asynchronous materialization of successful outputs, and terminal draining |
| Online target binding | Binds an actual compatible VM from submitted network observations and projected CPU reservation; STATIC dispatch then follows; NOOP retries reuse the binding |
| Experiment control | Staggered multi-workflow arrivals, failure/retry under supported combinations, post-hoc deadline observation, matrix-based computation cost |
| Policy integration | `RlEnvironment` / `RlPolicy` state, action, and reward interfaces; external policies must satisfy the corresponding model constraints |

The standard `SimulationRunner` uses SPACE_SHARED VMs without task clustering. Runs are serialized within a single JVM; batch-level parallelism requires separate processes. Online target binding and online CPU scheduling are distinct decision phases and must not be conflated into one algorithm.

## Execution pipeline and project layout

```text
workflow + SimulationConfig + PlatformProfile
    → input and combination validation → dependency parsing → optional static planning
    → dependency/arrival gating → fixed mapping or explicit online target binding
    → input preparation → CPU dispatch and event loop → required-output draining
    → SimulationReport → evidence validation → offline report / exact replay
```

- `simulator/` — the simulation core, platform, algorithms, network, policy interfaces, and tests.
- `experiments/` — the unified entry point, examples, experiment executors, and reporting; part of the default Maven reactor.
- `datasets/` — workflow inputs and the [dataset notes](<datasets/README.md>).
- `scripts/` — independent evidence validation, browser acceptance, and performance comparison tooling.
- `docs/` — current usage guides, model contracts, and maintenance notes (Chinese; a professional English translation pass is planned).

The core module does not depend on the experiment module.

## Experiment results and the research boundary

Standard artifacts comprise manifest v4, metrics v2, and events v1; network sidecars are engaged by an explicit recording mode. Hashes, sizes, event ordering, and declared context are cross-checked, and replay compares JSON values exactly — no tolerance is used to mask changes in scientific quantities.

- Lifecycle evidence proves source, path, input/output, and CPU causality within its declared scope; it is not automatically an interval-by-interval fluid-service accounting.
- Online-action evidence validates binding, computation claims, nominal-input bounds, and recorded scores; it does not prove exact real-time balances, future contention, or global optimality.
- File reference volume is not network traffic, abstract cost is not a bill, and runner wall-clock time is not simulated time. Exact statistical conventions are defined in the [reproducibility contract](<docs/experiments/REPRODUCIBILITY.md>) and the [replay contract](<docs/experiments/RERUN_DIFF_CONTRACT.md>).
- Paper fixtures and compatibility data validate only their versions and conditions; they cannot directly substitute for conclusions about the current code.

## Development and verification

```bash
mvn -Pjavadoc clean verify
```

The joint gate covers unit, semantic, and integration tests, coverage floors, and core-API Javadoc; independent Python and offline browser checks are described in the [build guide](<docs/getting-started/BUILD.md>). For performance comparisons, first check state, capture, and action-fingerprint equality, then report wall time and allocation deltas, following the [state-copy performance notes](<docs/advanced/DATAFLOW_STATE_COPY_PERFORMANCE.md>).
