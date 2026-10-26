# Workbench: Configuration, Runs, and Offline Reports

Workbench is the maintained, unified experiment entry point. It reads JSON configuration, runs algorithms within the same decision layer, saves verifiable evidence, and generates self-contained HTML; it starts no web service and loads no CDN. For a first run, see [Quick Start](<QUICK_START.md>).

## Commands and Paths

Use JDK 17+ and Maven 3.6.3+, and run from the project root:

```bash
# 预检：解析输入并检查配置组合，不运行仿真
mvn -pl :workflowsim-workbench -am compile exec:java \
  -Dexec.mainClass=org.workflowsim.experiments.workbench.Workbench \
  -Dexec.args="validate experiments/configs/online-comparison.json"

# 运行：在输出根下创建唯一实验子目录
mvn -pl :workflowsim-workbench -am compile exec:java \
  -Dexec.mainClass=org.workflowsim.experiments.workbench.Workbench \
  -Dexec.args="run experiments/configs/online-comparison.json output/workbench"

# 扫描实验记录，重建历史索引
mvn -pl :workflowsim-workbench -am compile exec:java \
  -Dexec.mainClass=org.workflowsim.experiments.workbench.Workbench \
  -Dexec.args="history output/workbench"

# 从单次运行证据生成独立报告；目标文件必须尚不存在
mvn -pl :workflowsim-workbench -am compile exec:java \
  -Dexec.mainClass=org.workflowsim.experiments.workbench.Workbench \
  '-Dexec.args=report "/absolute/run/result.manifest.json" "/absolute/new-report.html"'
```

Relative paths in CLI arguments are resolved against the working directory; inside the configuration, `workflowPaths` is resolved relative to **the directory containing the configuration file**, and absolute paths are also accepted. Maven compilation writes build output, so `validate` is not a zero-write guarantee for the whole command.

## Choosing an Example

| Research subject | Configuration |
| --- | --- |
| Plain CPU online scheduling | [online comparison](<../../experiments/configs/online-comparison.json>) |
| V1 Fat-tree and static mapping | [network comparison](<../../experiments/configs/network-comparison.json>), [enabling the group-level ledger](<../../experiments/configs/network-ledger-comparison.json>) |
| V2 per-file dataflow | [shared resources](<../../experiments/configs/coherent-file-dataflow-v2.json>), [isolated-path bottleneck](<../../experiments/configs/coherent-file-dataflow-isolated-v2.json>) |
| V3 constrained storage and output write-back | [shared resources](<../../experiments/configs/storage-dataflow-v3.json>), [isolated-path bottleneck](<../../experiments/configs/storage-dataflow-isolated-v3.json>) |
| Online target assignment after control readiness | [V2 file](<../../experiments/configs/online-file-dataflow-v1.json>), [V3 storage](<../../experiments/configs/online-storage-dataflow-v1.json>) |

Simply replace the configuration argument of `run` with the chosen file. The paired V2/V3 examples fix the inputs, capacities, paths, and lifecycle rules and vary only cross-flow resource sharing; the isolated-path bottleneck does not mean that transfers are free.

## JSON Configuration

| Field | Constraints and defaults |
| --- | --- |
| `schema` | Fixed as `workflowsim-workbench-v1` |
| `name` | Display name, at most 128 characters; a default name is used when omitted |
| `workflowPaths` | 1–32 DAX/XML or WfFormat JSON inputs |
| `workflowArrivalSeconds` | Optional list of non-negative seconds, equal in length to the inputs, all zeros by default; counted from simulation time zero |
| `platform.vmMips` | 1–64 positive finite values, each not exceeding `1e12`; every VM is pinned to a dedicated Host, 1 PE, SPACE_SHARED |
| `platform.bandwidthMbPerSecond` | Positive integer endpoint bandwidth, default 1000; decimal MB/s |
| `platform.cpuCostPerSecond` | Non-negative abstract CPU unit price, default 3; not a calibrated cloud price |
| `platform.storageTransferMbPerSecond` | Positive integer shared-storage transfer rate, default 15; different data movement models interpret storage differently |
| `platform.networkTopology` | Optional Fat-tree declaration: `k`, `linkBandwidthMbPerSecond`, `coreSwitchCount`, `hostEdgePlacements`; Workbench supports even k in the range 2–16 |
| `platform.sourceStorage` | Constrained SOURCE, required only for V3; fields are described below |
| `simulation.fileSystem` | `SHARED` or `LOCAL`, default SHARED; model/algorithm constraints still apply |
| `simulation.dataMovementModel` | The exact enumeration name from the table below, default `LEGACY_WORKFLOWSIM_V1` |
| `simulation.runtimeScale` / `runtimeReferenceMips` | Positive finite values, defaults 1 / 1000; conversion parameters from input runtime to model MI |
| `simulation.minEventIntervalSeconds` | Positive finite value, default 0.1 |
| `simulation.deadlineSeconds` | Optional non-negative signed-long integer, 0 means not requested; post-hoc observation only, it changes neither scheduling nor termination |
| `simulation.taskCostMatrix` | Optional `{taskId, vmId, executionSeconds}` array covering all logical tasks × VMs; requires a STATIC path and cannot be combined with the SHARED_STORAGE planner |
| `simulation.networkEvidence` | Optional recording object; mode and budget must be present together, see the recording table |
| `algorithms` | Each entry carries a unique `id` matching `[A-Za-z0-9][A-Za-z0-9._-]{0,63}`; `scheduler`, `planner`, `dataflowAssignment` may be declared |
| `seeds` | 1–100 mutually distinct signed-long integers, default `[42]`; algorithms × seeds must not exceed 500 |

Without a planner the default is INVALID/FCFS; with a non-INVALID planner declared the default is STATIC; the default dispatcher for explicit online binding is also STATIC. See the [algorithm catalog](<../algorithms/CATALOG.md>) for each algorithm's specific prerequisites; the fact that the JSON fields parse does not by itself make every combination valid.

Unknown or duplicate fields, strings posing as numbers, invalid integers, duplicate algorithm IDs, cross-decision-layer mixed comparisons, missing inputs, and invalid model combinations are rejected; the independent task mapper does not accept inputs containing dependency edges. Workbench is fixed to no failures and no overhead; such research must use the [Java API](<CODE_CONFIG_EXPERIMENTS.md>). `RL_POLICY` also connects external policies through the Java `RlEnvironment`; Workbench does not train the model.

## The Nine Data Movement Models

All Workbench runs use NONE clustering and SPACE_SHARED VMs; the full algorithm constraints still apply.

| `dataMovementModel` | File system | Physical model and additional conditions |
| --- | --- | --- |
| `LEGACY_WORKFLOWSIM_V1` | SHARED / LOCAL | Preserves the legacy transfer computation; default model |
| `FIXED_ENDPOINT_NO_CONTENTION_V1` | SHARED / LOCAL | Fixed endpoints, per-file latency, no cross-flow contention; `simulation.fixedEndpoint` must be provided |
| `PRE_EXECUTION_TRANSFER_DELAY_V1` | SHARED / LOCAL | Static target mapping; contention-free input hold, transfers are not counted toward the computation envelope |
| `PRE_EXECUTION_TRANSFER_DELAY_WITH_CONTENTION_V1` | SHARED / LOCAL | Static target mapping; the transfer group starts when the Job becomes ready, VM endpoints use max-min sharing |
| `PRE_EXECUTION_TRANSFER_DELAY_WITH_FAT_TREE_CONTENTION_V1` | LOCAL | Static target mapping; endpoint and deterministic Fat-tree path sharing, the topology must be declared |
| `COHERENT_FILE_DATAFLOW_V2` | LOCAL | Per-file visibility, actual source replicas and paths, cross-flow shared capacity |
| `COHERENT_FILE_DATAFLOW_NO_CONTENTION_V2` | LOCAL | Same file lifecycle, each path independently constrained by the bottleneck capacity |
| `COHERENT_STORAGE_DATAFLOW_V3` | SHARED / LOCAL | Constrained SOURCE, shared-storage commit gating, and mandatory output write-back, cross-flow shared capacity |
| `COHERENT_STORAGE_DATAFLOW_NO_CONTENTION_V3` | SHARED / LOCAL | Same storage lifecycle, each path independently constrained by the bottleneck capacity |

The fixed-endpoint object uses `accessBandwidth`, `latencySeconds`, and `sourceBandwidth`; it may only be declared for the fixed-endpoint models. Bandwidth is a positive finite MB/s value, and latency a non-negative finite number of seconds.

coherent V2/V3 with online binding not enabled requires an explicit **RANDOM/STATIC**; a Fat-tree topology is optional. The existing LOCAL planner is not an estimator for the coherent models. Enabling a topology cannot be applied to other models that do not consume the topology. Capacity conversions must stay within the finite numeric domain supported by the model.

In V1 Fat-tree and V2, external SOURCE traffic bypasses the fabric, and no finite aggregation capacity is set for SOURCE. V3 does not carry over this assumption: `sourceStorage` must declare `attachmentHostId`, `readBandwidthMbPerSecond`, `writeBandwidthMbPerSecond`, and `networkBandwidthMbPerSecond` together. Host IDs start at 0 and correspond to the `vmMips` entries; unknown Hosts are not accepted, nor are capacities guessed as defaults, non-positive or non-finite capacities, or capacities that cannot be converted into a positive normal finite B/s.

V3 **LOCAL** can read VM replicas that have already become visible, but all successful outputs are still written back to SOURCE asynchronously; **SHARED** must first wait for the SOURCE commit before using the destination cache or reading from SOURCE, and a same-VM cache cannot bypass the commit either. unused sinks and zero-byte outputs carry the same commit obligation; CPU return does not wait for the write-back, but the simulation must not end before the obligations are completed. For the complete boundaries see the [V2 contract](<../advanced/COHERENT_DATAFLOW_V2_CONTRACT.md>) and the [V3 contract](<../advanced/STORAGE_DATAFLOW_V3_CONTRACT.md>).

## Online Target Binding

Declare it explicitly on an algorithm object:

```json
{
  "id": "online-dataflow-v1",
  "planner": "INVALID",
  "scheduler": "STATIC",
  "dataflowAssignment": {
    "mode": "CONTROL_READY_ONLINE_ASSIGNMENT_V1",
    "policy": "NOMINAL_INPUT_EARLIEST_RESERVATION_V1"
  }
}
```

The policy binds an actually compatible VM once the control dependencies are ready, and then prepares the inputs; STATIC dispatches only Jobs that are already bound and data-ready. V2 still requires LOCAL, V3 allows LOCAL/SHARED; it cannot be used together with RANDOM pre-planning, and it cannot be placed in the same comparison matrix as plain CPU online scheduling or offline mapping.

Workbench online evidence must enable the matching full-lifecycle recording. The Java API can disable capture while keeping the actions and the physical behavior, but it cannot export online-action attestations without recording; a NOOP retry reuses the original logical binding and does not reselect the VM.

The attestation checks the binding, the PE, the valid MI, the CPU reservation, the nominal input boundary, and the recorded score; it does not attest exact in-flight balances or future contention. The report explicitly displays `liveProgressReplayed=false`; estimate changes within the boundaries still surface as core differences in an actual rerun. For the complete states, formulas, and scope see the [online binding contract](<../advanced/ONLINE_DATAFLOW_ASSIGNMENT_V1_CONTRACT.md>).

## Evidence Recording and Integrity

Add a recording object inside `simulation`, for example:

```json
"networkEvidence": {"mode": "FILE_STORAGE_LIFECYCLE_V3", "maxTraceRecords": 20000}
```

The budget must be an exact numeric integer in the range 1–2147483647. Omitting the object means OFF; null, false, an explicit OFF object, or a budget of 0 cannot be used in its place.

| Mode | Applicable models | Sidecar role | When capture is incomplete |
| --- | --- | --- | --- |
| Omitted (OFF) | The physical models; Workbench online binding excepted | None | Provides no network/lifecycle capture; does not mean zero network |
| `FLUID_GROUP_LEDGER_V1` | The two V1 contention models | `network-ledger` | Can export a clearly marked truncated prefix; must not be passed off as a complete ledger |
| `FILE_LIFECYCLE_V2` | The two coherent file V2 models | `file-lifecycle` | Rejects export before artifact I/O; Workbench records the failure |
| `FILE_STORAGE_LIFECYCLE_V3` | The two coherent storage V3 models | `storage-lifecycle` | Rejects export before artifact I/O; Workbench records the failure |

Budget exhaustion does not truncate the simulation. The three sidecar roles are mutually exclusive; a V2/V3 sidecar must not be treated as the V1 ledger. Even when OFF, the manifest of a coherent model still retains the core `dataflowPlan`, and V3 additionally retains the storage configuration. V2/V3 are lifecycle certificates, not per-interval service areas, link utilization, or TCP protocol attestations.

For the formats and independent validation see the [V1 ledger](<../advanced/NETWORK_LEDGER_FORMAT.md>), the [V2 lifecycle](<../advanced/FILE_LIFECYCLE_V2_FORMAT.md>), [V3 storage](<../advanced/STORAGE_DATAFLOW_V3_CONTRACT.md>), and the [Python checker](<../../scripts/verify-network-ledger.py>). The rerun includes `/fileLifecycle/...`, `/storageLifecycle/...`, and the online-action-related core quantities in the comparison; see the [rerun difference contract](<../experiments/RERUN_DIFF_CONTRACT.md>).

## Output, Failures, and Recovery

```text
output-root/
  index.html
  history.json
  experiment-<UUID>/
    configuration.json
    experiment.json
    report.html
    runs/<algorithm>-s<seed>/
      result.manifest.json
      result.metrics.json
      result.events.jsonl
      result.network-ledger.json   # 仅 V1 组级记录
      result.file-lifecycle.json   # 仅 V2 文件记录
      result.storage-lifecycle.json # 仅 V3 存储记录；三种侧车互斥
```

The manifest is v4, provenance is v3, metrics are v2, and events are v1. After the files are written, the evidence is validated, and the report is then generated from that same validation snapshot. A single run failure records its root cause and the other schemes continue; failures do not enter the completion-time ranking; the `run` command exits non-zero when there are failed or unfinished runs.

A forced interruption can leave a RUNNING state together with completed evidence; check it through the history page and do not treat it as a success. `history` shows corrupted records and does not silently ignore them. When multiple processes write the same output root, you should rebuild the history only after all processes have ended; simulations inside the same JVM are serial.

Files are written as same-directory temporaries followed by replacement, using atomic replacement where supported; the evidence package as a whole is not a cross-file transaction, and passing the final validation is the criterion of completeness. The standalone `report` refuses to overwrite an existing target; ordinary online runs are named by scheduler, static runs by planner, and the binding track is labeled `DATAFLOW_BINDING_V1`.

## Report Display and Acceptance

The report supports runs, VM/task filtering, the execution timeline, and dependency focus. The dependency overview holds at most 80 nodes, the timeline draws at most 250 Jobs at once, and the tables retain all computational Jobs. In the network preview, V1 flows / V2-V3 replications and resources are each capped at 64 entries and events at 128; the V3 Job preview holds at most 64 entries. Online actions are capped at 64 entries with at most 12 candidates each; the actual selection still comes from the full candidate set.

Network and action numbers use exact text; seeds are displayed as full decimal strings, adjacent integers greater than 2^53 are not merged, and the values in the original configuration and the manifest are not rewritten. Capture truncation and interface-preview omission are counted separately; OFF, complete, zero-flow, and failed states are displayed separately; switching runs clears the old panels and does not request the sidecars again.

Multi-seed results of deterministic algorithms serve only as repeatability observations and are not treated as independent statistical samples. Metrics are not measurements of a real platform; the models contain no TCP, loss/ECN, packet-level queuing, or adaptive routing.

Browser acceptance is covered uniformly in the [build guide](<BUILD.md#offline-report-browser-acceptance>); for network display boundaries see the [offline network report notes](<../advanced/NETWORK_REPORT_DISPLAY.md>).
