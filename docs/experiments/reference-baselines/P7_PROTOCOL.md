# 冻结参考基线实验协议

## Status and Scope

本协议冻结第一条 P7 比较轨道。它认证并比较已实现的抽象 WorkflowSim 模型行为，
不主张重放 WfCommons 执行 trace、预测生产云环境或估算服务商账单。

P7 的可执行代码属于可选实验模块：
`experiments/src/main/java/org/workflowsim/experiments/reference/p7/`。它依赖
核心 `simulator/`，但核心模块不依赖 P7。P7 执行器只接受明确传入的**绝对数据集根目录**；
该根目录通常是项目的 `datasets/`，而不是项目根目录。

The P7 primary track uses DAX inputs, one data center, one VM per host, the
shared-storage model, no clustering, no overhead, no failures, no planning
algorithm, and a single fixed root seed. WfCommons JSON remains a separately
certified input-conversion track and is not pooled with DAX results.

The initial DAX corpus is deliberately limited to Epigenomics. Under the
current fail-fast input contract, the candidate CyberShake, Montage, Inspiral,
and SIPHT n100/n1000 DAX inputs contain at least one repeated input filename
with conflicting size declarations. Those files are excluded rather than
rewritten or accepted permissively. This makes P7-B a single-family pilot; it
cannot support cross-workflow-family conclusions.

`WFINSTANCES_PILOT.md` defines a separate four-input WfInstances 1.5
conversion/certification slice spanning Makeflow, Nextflow, and Pegasus. It is
not pooled with the DAX primary matrix and has no production-trace or platform
replay claim.

## Frozen Matrix

可执行目录为
`org.workflowsim.experiments.reference.p7.P7BaselineMatrix`。它包含四个场景单元和五个
调度算法，共 20 次确定性算法运行。目录内的输入逻辑路径均相对于显式数据集根目录，
例如 `dax/epigenomics/n100/Epigenomics_100.dax`；SHA-256 摘要也属于目录契约，
在解释结果前由 `P7BaselineMatrixTest` 检查。

| DAX family | Small input | Large input | VM count |
| --- | --- | --- | --- |
| Epigenomics | `epigenomics/n100/Epigenomics_100.dax` (100 tasks) | `epigenomics/n997/Epigenomics_997.dax` (997 tasks) | 10 / 50 |

Each workload is evaluated on both platform variants:

| Variant | VM MIPS | Host layout | Purpose |
| --- | --- | --- | --- |
| H0 | Every VM is 1000 MIPS | One VM on one host | Controlled homogeneous reference |
| H1 | 500 and 1500 MIPS in equal counts | One VM on one host | Heterogeneous scheduler sensitivity with mean VM capacity of 1000 MIPS |

All hosts have 2 PEs at 2000 MIPS per PE. All VMs are single-PE, 512 MB,
1000 bandwidth, and `SPACE_SHARED`. A one-VM-per-host layout deliberately
excludes host-level VM CPU contention from this first result set.

The approved algorithm labels are exactly:

1. `FCFS`
2. `READY_BATCH_ROUNDROBIN`
3. `READY_BATCH_MCT`
4. `READY_BATCH_MINMIN`
5. `READY_BATCH_MAXMIN`

Deprecated legacy labels are not called classical Min-Min, Max-Min, MCT, or
Round Robin in this protocol. HEFT and DHEFT are excluded because their
planning-side transfer estimate has not been aligned with the execution
data-staging model; the standard `SimulationRunner` rejects those legacy
labels.

## Maintained Algorithm Tracks Outside the Primary Matrix

`CATALOG.md` is the normative human-readable catalog; every run
manifest also contains the machine-readable `algorithmContract`. The frozen
primary matrix above remains online dispatch only. It must not be expanded or
retroactively reinterpreted merely because further algorithms are available.

The maintained independent-task planners (`STATIC_OLB`, `STATIC_MET`,
`STATIC_MCT`, `STATIC_MINMIN`, `STATIC_MAXMIN`, `STATIC_SUFFERAGE`, and
`STATIC_ROUND_ROBIN`) are a separate offline bag-of-tasks track. They reject
every DAG edge and therefore cannot be used for the primary workflow-DAG
matrix.

`SHARED_STORAGE_HEFT`, `SHARED_STORAGE_CPOP`, `SHARED_STORAGE_DLS`,
`SHARED_STORAGE_ETF`, and `SHARED_STORAGE_PEFT` are a separate controlled
static-DAG track. They use the current shared-storage data-stage-in abstraction,
produce VM mappings and complete per-VM orders, and require `STATIC` dispatch,
`SHARED` storage, `NONE` clustering, no overhead, disabled failures, and
`SPACE_SHARED` VMs. Their permissible claim is limited to a comparison of
these implementations under that exact abstract model. DLS records its
Task-VM dynamic level and selection order, but does not model the original
algorithm's interconnect topology, routing, or link contention. ETF records
its selected earliest start and selection order, but does not reproduce the
original algorithm's homogeneous-processor or communication-delay assumptions.
PEFT records its optimistic-cost rank, selected `EFT + OCT` objective, and
selection order, but has a zero interprocessor-communication term because this
model contains no topology, route, or link-contention semantics.
Each run also records optional VM-Host pins, a deterministic capacity preflight,
and the allocation map observed when CloudSim confirms VM creation. This is
placement evidence only; it does not add a Host-contention model. The P7
reference matrix continues to require one VM per Host so placement cannot
confound its algorithm comparison.
The legacy `HEFT` and
`DHEFT` labels remain excluded from all P7 comparison tracks.

## Fixed Configuration

Every P7 primary run must use the following values:

| Setting | Value |
| --- | --- |
| File system | `SHARED` |
| Planning algorithm | `INVALID` |
| Clustering | `NONE` |
| Failure model | Disabled |
| Overhead model | None |
| Root seed | `20260901` |
| Runtime reference MIPS | `1000.0` |
| Runtime scale | `1.0` |
| CloudSim minimum event interval | `0.1` simulated seconds |
| Cost model | `DATACENTER`, recorded only; cost is not a P7 metric |
| Data movement | Explicit `LEGACY_WORKFLOWSIM_V1`; no endpoint model or shared-link contention is implied |

The task conversion is `max(100, floor(runtimeSeconds * referenceMips *
runtimeScale))` MI. On a 1000 MIPS VM this approximates the input runtime only
for the CPU component. Stage-in, queueing, data-transfer delay, and scheduler
behavior can change the final simulation-end makespan.

The minimum event interval is a CloudSim kernel-cadence parameter rather than
a physical network or scheduler delay. It is frozen because it changes modeled
event release and therefore task start times. It must be recorded and matched
with the same rigor as the seed and runtime conversion values.

## Semantic Certification Gates

P7 comparison results may be produced only after all of the following pass:

1. Parser/DAG tests confirm task, edge, file, runtime-normalization, and input
   hash contracts.
2. `SimulationSemanticContractIntegrationTest` confirms that no-clustering output
   retains each compute task ID and that the fixture's child jobs start only
   after their recorded parents finish.
3. The same test confirms that the implemented shared-storage model is
   monotonic for a controlled 30 MB input: at 10 MB/s rather than 20 MB/s, the
   makespan increases by the 1.5 simulated seconds predicted by the model.
4. The P7 matrix test verifies that every catalog cell has an existing frozen
   DAX input, an expected SHA-256 digest, approved algorithms, one VM per host,
   `SPACE_SHARED`, no failure, no overhead, no clustering, and `SHARED` storage.
5. A selected deterministic control run is repeated in the same JVM and its
   report fingerprint is identical. Repetition is a reproducibility check, not
   an estimate of scientific variance.
6. `SimulationEvidenceIntegrationTest` verifies the ordered event vocabulary,
   task/Job observation boundary, metric scopes, JSONL one-event-per-line
   contract, and artifact-bundle cross references on a controlled DAG fixture.
7. `ExperimentArtifactValidator` validates every artifact bundle by required top-level
   shape, sidecar hash/size, manifest/metrics consistency, event sequence, event count,
   and v3 provenance structure. `P7EvidenceIndexValidator` validates the P7 index against
   every referenced bundle, frozen input and task count, scheduler, complete fixed
   configuration, platform and actual VM-Host placement, runtime conversion, CloudSim
   event cadence, result summary, and the exact 4 x 5 scenario/algorithm matrix.

## Measurements and Evidence

The primary metric is model makespan. It must be reported as a makespan of this
specific abstract model, including its stage-in and enabled data-transfer
behavior.

For this frozen deterministic P7 matrix, published `makespan` retains the
historical simulation-end meaning. Later additive fields such as
`logicalTaskCompletionSeconds`, retry/attempt evidence, and split cost
components do not redefine, recompute, or replace the P7 primary metric.

Secondary evidence is successful/failed Job-outcome count, Job start/finish/CPU
time, Job class type, Task outcomes, and per-VM completed-Job CPU-time and
last-finish-time summaries. A Job outcome is the CloudSim Job envelope,
including its effective integral-MI stage-in length. A Task outcome is its
modeled compute window after that effective delay and is exact only where that
one-Task window equals the Job envelope; the requested transfer duration is
recorded separately. Clustered, stage-in, retry, and failure Task timing are
explicitly model-derived observations. The
metric sidecar additionally counts logical
Tasks from the parsed pre-clustering graph. A logical Task is counted completed
only when it appears in at least one successful compute Job; this is the
reliable completion boundary because legacy Task status is not always
propagated from the containing successful Job. `allLogicalTasksCompletedSuccessfully`
is therefore an explicit model outcome, while Job success/failure rates remain
*outcome rates*, not workflow-completion probabilities. VM summaries are not
Host utilization measurements.

`ExperimentArtifactWriter` 写出三文件 evidence bundle：`manifest.json`、
`metrics.json` 与 `events.jsonl`。新写入的 v3 manifest 记录输入路径/hash/size、解析
归一化、完整配置、平台、Job/Task outcome、模型指标、事件流摘要、JVM/OS 信息、可选
VM-Host pin、预检放置和 CloudSim VM 创建后冻结的实际映射；它还记录 metric/event
sidecar 的 SHA-256 与 size。

v3 不再从当前工作目录猜测项目根。它记录核心 `workflowsim` 组件身份；P7/reference
运行还会记录实验组件身份、协议逻辑标识及其可用 hash，以及显式绝对数据集根。源树 hash
只是非 Git 工作区的本地组件身份，不是归档签名或发行身份。核心 artifact 验证器保留对
历史 v2 bundle 的只读兼容，`P7EvidenceIndexValidator` 保留对完整历史 v2 P7 index 的
只读兼容；这不代表 v2 工件自动具有 v3 的研究组件身份。

The v1 metric sidecar includes model makespan; compute-Job outcome rate and
throughput; logical-Task completion count/rate; mean compute-Job runtime;
ready-to-decision and decision-to-start delays when the corresponding events
exist; total and mean per-VM union-of-Job-interval busy time/utilization;
modeled busy-time coefficient of variation; explicit planner-decision count and
local JVM wall-clock planning time; and modeled processing cost. Runtime
scheduling-cycle wall-clock time is recorded separately. Neither wall-clock
field is simulated time, deterministic evidence, or a cross-machine algorithm
benchmark; reproducibility fingerprints exclude their varying elapsed values.
The metric sidecar defines compute-Job runtime as the end-to-end Job envelope,
including effective stage-in. CloudSim processing cost is derived from the same envelope and may
therefore include the integral-MI representation of stage-in; it is neither a
pure CPU-time measurement nor a provider-tariff/network-charge replay. It
additionally exposes an abstract CPU-envelope versus declared-file
bandwidth split and attempt/retry evidence. P7 disables failures, so these
failure/retry fields are not P7 research outcomes, and recorded cost remains
outside the P7 primary/secondary metric set. The declared-file byte component
is not a physical transfer ledger or provider bill. It also records an optimistic
shared-storage critical-path lower bound and SLR
only when `controlledSharedStorageCriticalPathReferenceAvailable` is true. That
reference includes the 110 MI stage-in Job, each Task's own modeled real-input
delay, completion no earlier than `minInterval + 0.01`, the additional
minimum-event release interval after stage-in, fastest compatible VM execution,
and no VM-capacity contention. It is a
model lower bound, not an optimal schedule, real critical-path measurement, or
cross-model metric. These are simulator-derived quantities with their exact
field names and must not be relabelled as real platform measurements.

The event stream covers parsing, planning completion, clustering, stage-in Job
creation, Job readiness, scheduling cycles and decisions, dispatch, modeled
data stage-in, modeled Task execution, Job return/failure, and retry creation.
`decisionElapsedNanos` is a host wall-clock measurement: it is intentionally
excluded from deterministic replay fingerprints and must not be used as a P7
algorithm-quality metric without a separately controlled benchmarking protocol.

No cost, energy, availability, utilization, cloud billing, or wall-clock metric
is a P7 primary or secondary outcome.

## Execution and Artifact Discipline

`org.workflowsim.experiments.reference.p7.P7BaselineExecutor` 是唯一的 P7-C 批量
入口。它要求一个绝对数据集根目录和一个绝对空输出目录，按顺序运行矩阵；每次运行后
验证实际消费的输入 hash/Task 数和成功 Job 完成情况，写出一个完整 v3 evidence bundle，
仅在全部运行成功后写入 `p7-baseline-index.json`。该 index 的 schema/kind 只接受完整的
4 x 5 冻结矩阵；验证器会拒绝缺失、重复或额外单元。它不会选择默认输出位置，也拒绝覆盖
非空目录或从当前工作目录推断数据集位置。

Example invocation after a successful Maven build:

```bash
# /absolute/.../datasets 下必须存在 dax/ 等数据子树；第二个参数必须为空目录。
mvn -pl :workflowsim-experiments -am \
    -Dexec.mainClass=org.workflowsim.experiments.reference.p7.P7BaselineExecutor \
    -Dexec.args="/absolute/path/to/WorkflowSim-1.0/datasets /absolute/empty/output" \
    compile exec:java

# 对保留输出进行只读验证。
mvn -pl :workflowsim-experiments -am \
    -Dexec.mainClass=org.workflowsim.experiments.reference.p7.P7EvidenceIndexValidator \
    -Dexec.args="/absolute/output/p7-baseline-index.json" \
    compile exec:java
```

`executeSelection(...)` 只用于开发和冒烟验证：它写入单独的
`p7-reference-selection-index.json` 与 `PARTIAL_SELECTION_NOT_P7_BASELINE` 标记，绝不能被
称为 P7 基线。v3 index 只包含相对 manifest、metric、event 路径、结果摘要和
P7/reference 身份信息；它不记录墙钟创建时间，避免生成时间掩盖确定性证据内容。执行器
只在所有 run 完成后写入 index，并在写入后立即验证。输出目录只有在明确决定保留时才是
实验工件；否则它是临时结果，检查后必须删除。

## Statistical Rules and Stopping Conditions

The primary matrix is deterministic. Report exact per-cell makespans, absolute
and relative deltas, and wins/losses/ties. Do not use a significance test on
repeated identical deterministic runs.

A later stochastic extension requires a separately approved event-keyed common
random-numbers design. Matching only `SimulationConfig` root seeds is not proof
of common random numbers because different scheduling decisions can consume
random streams in different orders.

That extension must preregister a pilot size, smallest meaningful effect,
confidence-interval precision target, maximum run count, budget, comparison
family, and multiple-comparison adjustment before it is executed.

P7-B 在全部语义门禁和 `mvn verify` 通过时停止。仅 `mvn test` 不充分，
因为端到端 `*IntegrationTest` 类在 Maven Failsafe 生命周期运行，且 P7/reference 测试
位于可选实验模块。P7-C 开始
only after that gate, and stops when every planned deterministic cell has a
manifest and no configuration/input-hash mismatch. Maven `target`, temporary
manifests, and logs are intermediate artifacts and must be removed after
verification unless explicitly retained as an approved experiment deliverable.

Before any cross-family P7-C interpretation, a second independently auditable
input family must either satisfy the strict input contract or have a separately
approved, provenance-preserving normalization policy with dedicated regression
tests. That decision is not part of this protocol.

## Explicit Exclusions

The following remain outside the P7 primary claim boundary until separately
implemented, tested, and calibrated: `LOCAL` storage, network topology and
contention, concurrent storage I/O, HEFT/DHEFT transfer-cost comparisons,
failure/recovery/retry claims, measured overhead distributions, calibrated
cloud prices, multi-core task replay, WfCommons trace-makespan replay, and
real-platform performance prediction.
