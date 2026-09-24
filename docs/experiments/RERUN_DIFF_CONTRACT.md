# Rerun 与差异比对契约（D2）

状态：待评审。评审通过后才开始实现；实现偏离本契约时，先改契约再改代码。

## 目标与非目标

本能力回答一个问题：**一份历史 v4 证据，在当前代码下重跑，核心结果是否一致？**

它服务于两个真实场景：

1. 算法语义修正后，批量重验历史研究结论（R10 修正 CPOP 后人工复跑 360 次的教训）；
2. 对任一证据目录快速自证"该结果可复现"，把确定性声明变成机械化检查。

**非目标**（本任务明确不做）：

- 不做断点续跑或事件级 checkpoint——单次仿真是分钟级以内的工作负载，中断恢复没有实际收益；
- 不做结果复用缓存——身份键设计留待后续任务，且依赖本契约中"run 身份"的稳定定义；
- 不做跨机器容差比对——不同机器/不同 JVM 的差异不在本能力范围内解释；
- 不修改模拟器任何语义——rerun 是只读消费者加一条新执行路径，不触碰现有运行链。

## 命令与输入约定

新增统一入口子命令（与 Workbench 并列的独立执行器，入口类 `org.workflowsim.experiments.rerun.RerunDiffExecutor`）：

```bash
mvn -pl :workflowsim-experiments -am compile exec:java \
  -Dexec.mainClass=org.workflowsim.experiments.rerun.RerunDiffExecutor \
  -Dexec.args="<run-dir> <output-dir>"
```

- `<run-dir>`：单个 run 的证据目录，必须包含 `result.manifest.json`（schema 为 `workflowsim-experiment-manifest-v4`）、`result.metrics.json`、`result.events.jsonl` 三件套。
- v1 只接受**单个 run 目录**。study 级批量（遍历 `runs/` 下全部目录并汇总）是后续独立任务，不在本轮验收内。
- v1 只接受 manifest v4。历史 v2/v3 证据可读不可 rerun，遇到时明确拒绝并说明原因，不静默降级。
- `<output-dir>`：必须不存在或为空。rerun 产出写入该目录，沿用现有"证据文件逐个原子替换"的写盘纪律。不覆盖任何已有文件。

## 执行流程

```text
读取三件套 → 结构校验（复用 ExperimentArtifactValidator/ManifestV4Validator）
    ↓
输入定位：按 sha256 核对本地文件 → 任一输入不可解析或哈希不符 ⇒ 终止
    ↓
配置重建：manifest.configuration + manifest.platform → SimulationConfig/PlatformProfile
    ↓
经 SimulationRunner 标准路径重新执行 → 新三件套写入 output-dir
    ↓
核心量比对（旧 vs 新）→ 生成 rerun-report.json + rerun-report.md
```

每一步失败都必须 fail fast 并给出机器可读的失败原因，不允许"尽力比到哪儿算哪儿"。

## 输入定位规则

manifest 中 `inputs[].path` 是**原机器的绝对路径**，跨环境不可靠。定位顺序：

1. 若 `<run-dir>` 所属 study 输出目录内存在同名文件（如 `inputs/` 下的生成负载），优先使用并核对 sha256；
2. 否则尝试记录的绝对路径原样；
3. 否则把绝对路径按原 `provenance.execution.workingDirectory` 相对化后，重定位到当前仓库根。

**任何一步命中后都必须核对 sha256 与 sizeBytes；不符即终止**，报告 `INPUT_HASH_MISMATCH`，绝不静默替换输入。定位失败的报告状态为 `INPUT_UNRESOLVED`，并列出尝试过的全部候选路径。

## 配置重建规则

- 重建仅使用 manifest 中已记录的字段：`configuration`、`platform`、`inputs`。不引入 manifest 之外的默认值；manifest 中为 `null` 的字段按其 schema 语义处理（显式空值 ≠ 缺省值）。
- 重建产物必须通过现有 `SimulationConfig`/`PlatformProfile` 的全部校验（未知字段拒绝、容量预检等），即 rerun 走的是与首次运行完全相同的入口约束。
- 若当前代码已不兼容该 manifest 的组合（例如算法标签已被拒绝、契约版本变化），报告状态为 `RECONSTRUCTION_REJECTED`，附上拒绝原因。**这是合法结果，不是错误**：它说明历史证据与当前代码语义不兼容，正是需要暴露的信息。

## 比对规则：三类字段

### 核心量（必须逐位一致）

| 来源 | 字段 |
|---|---|
| manifest `configuration` | 全部字段（算法标签、种子、fileSystem、dataMovementModel、overheadModel、clustering、failureModel 等） |
| manifest `platform` | 全部字段（hosts、vms、storage、costs、networkTopology） |
| manifest `inputs` | sha256、sizeBytes、format、declaredVersion、taskCount、normalizations（**不含 path**，路径属易变量） |
| manifest `workflowProfile` | 全部字段 |
| manifest `workflowGraph` | 全部字段 |
| manifest `result` | 全部字段（makespan、jobs、tasks、vmSummaries、actualVmHostAssignments、workflowOutcomes），其中 `workflowOutcomes[].path` 除外 |
| metrics | 全部字段，**除** `totalSchedulingDecisionWallClockNanos`、`totalPlanningDecisionWallClockNanos` |
| events | 事件总数、每条事件的 `sequence`/`simulationTime`/`type`/`taskIds`/业务 attributes；**除** 各事件 attributes 中的本机耗时字段（如 `planningDecisionElapsedNanos`，REPRODUCIBILITY 契约已声明其排除在确定性 fingerprint 外） |

比对方式为**序列化值的精确相等**：浮点数不设 epsilon 容差。理由：项目已声明"相同数据集根下复跑产出逐位一致的 makespan"，任何需要容差才能通过的情形都是未修复的非确定性来源，应修源头而不是放宽比较。

### 易变量（白名单豁免，仅记录）

- `provenance.*` 全部（codeSource、sourceTreeSha256、modulePomSha256、reactorPomSha256、javaClassPathSha256、workingDirectory）；
- `runtime.*` 全部（JVM 版本、OS）；
- metrics 与 events 中的本机 wall-clock 纳秒字段；
- `artifacts[].sha256`/`sizeBytes`（新 manifest 因 provenance 不同而哈希必然不同）；
- 输入与 workflowOutcomes 的绝对路径。

白名单是**显式枚举**，实现为常量清单并配单元测试。新增易变字段必须走契约修订，不允许实现时随手加豁免——豁免清单每放宽一项，检出真实漂移的能力就弱一分。

### 身份信息（不参与判定，但必须醒目报告）

- `provenance.core.sourceTreeSha256` 与当前源码树哈希**不一致**时，报告必须显著标注"代码版本与原始运行不同"。核心量出现分歧时，这是第一解释线索；核心量一致时，这证明该证据对代码演化稳健（或改动与本 run 语义无关）。
- 原 `configuration.algorithmContract` 与当前代码的契约声明若可比较，一并报告差异。

## 报告格式

输出目录内生成：

- `rerun/`：新三件套（`result.manifest.json`、`result.metrics.json`、`result.events.jsonl`）；
- `rerun-report.json`：机器可读结论，字段包括 `verdict`、`inputResolution`（每个输入的命中路径与哈希核对结果）、`coreDivergences`（分歧字段的 JSON 指针、旧值、新值；单字段值过长时截断并记录截断标记）、`volatileFieldsNoted`、`codeIdentityNote`、两侧 `sourceTreeSha256`；
- `rerun-report.md`：人类可读摘要。

`verdict` 取值（穷举）：

| verdict | 含义 |
|---|---|
| `IDENTICAL_CORE` | 全部核心量逐位一致 |
| `DIVERGED` | 至少一个核心量不一致，分歧清单非空 |
| `INPUT_UNRESOLVED` | 输入无法定位 |
| `INPUT_HASH_MISMATCH` | 输入定位成功但哈希/大小不符 |
| `RECONSTRUCTION_REJECTED` | 配置重建被当前代码校验拒绝 |
| `EVIDENCE_INVALID` | 原三件套自身未通过结构校验 |

退出码：`IDENTICAL_CORE` 为 0；`DIVERGED` 为非零；其余失败状态各有独立非零码。这使得 rerun 可以直接作为 CI/门禁步骤使用。

## 验收标准

1. **历史证据复现**：对 `output/network-study-r10-final/runs/` 中至少一个经典 DAX run 和一个合成负载 run 执行 rerun，verdict 为 `IDENTICAL_CORE`；
2. **分歧检出**：复制一份历史证据、篡改 manifest 中一个核心量（如 makespan），rerun 报告 `DIVERGED` 且分歧清单精确指出被篡改字段；
3. **输入防护**：篡改输入文件内容后 rerun 报告 `INPUT_HASH_MISMATCH`；移走输入文件后报告 `INPUT_UNRESOLVED` 且列出候选路径；
4. **不兼容防护**：构造一个含已拒绝算法标签的 manifest，rerun 报告 `RECONSTRUCTION_REJECTED`；
5. **旧证据可读**：v2/v3 manifest 被明确拒绝并说明原因，不进入执行流程；
6. **回归**：`mvn clean verify` 全绿；现有 Workbench、NetworkStudy、campaign 路径行为与产物无任何变化（rerun 是纯增量能力）。

## 测试要求

- **单元**：三类字段清单的枚举测试（核心量清单、易变量白名单、身份信息各自固定）；输入定位三级回退逻辑；verdict 判定逻辑；报告序列化。
- **语义**：篡改-检出配对测试（每类 verdict 至少一个手造样例，样例本身是小规模、可手算验证的输入）。
- **集成**：真实历史证据目录的端到端 rerun（验收标准 1、2），按现有惯例命名 `*IntegrationTest`，进入 `mvn verify` 门禁。

## 与后续任务的接口预留

- run 身份键（输入哈希 × 配置哈希 × 算法契约版本）在本任务中**只作为报告字段出现**，不实现缓存语义；后续 D-缓存任务直接消费本报告结构。
- study 级批量 rerun 复用本单 run 执行器，逐目录调用并汇总 verdict，不重新实现比对逻辑。
