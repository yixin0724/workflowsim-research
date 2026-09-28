# Rerun 与差异比对契约（D2）

状态：已实现，包含正确性整改中的迁址路径、精确整数比较及执行模型修订约定。契约与实现必须同步维护；补充决策见文末「实现备注」。

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
- 重建产物必须通过现有 `SimulationConfig`/`PlatformProfile` 的模型组合、容量和放置预检，即 rerun 走的是与首次运行相同的配置入口约束；证据字段的结构检查由读取器负责。
- 若当前代码已不兼容该 manifest 的组合（例如算法标签已被拒绝、契约版本变化），报告状态为 `RECONSTRUCTION_REJECTED`，附上拒绝原因。**这是合法结果，不是错误**：它说明历史证据与当前代码语义不兼容，正是需要暴露的信息。

## 比对规则：三类字段

### 核心量（必须逐位一致）

| 来源 | 字段 |
|---|---|
| manifest `configuration` | 全部字段（含 rootSeed、executionSemantics、fileSystem、dataMovementModel、overheadModel、clustering、failureModel 等）；仅 `workflowPaths[]` 的路径文本和 `algorithmContract` 身份声明按下文豁免，路径数组长度仍属核心量 |
| manifest `platform` | 全部字段（hosts、vms、storage、costs、networkTopology） |
| manifest `inputs` | sha256、sizeBytes、format、declaredVersion、taskCount、normalizations（**不含 path**，路径属易变量） |
| manifest `workflowProfile` | 全部字段 |
| manifest `workflowGraph` | 全部字段 |
| manifest `result` | 全部字段（makespan、jobs、tasks、vmSummaries、actualVmHostAssignments、workflowOutcomes），其中 `workflowOutcomes[].path` 除外 |
| metrics | 全部字段，**除** `totalSchedulingDecisionWallClockNanos`、`totalPlanningDecisionWallClockNanos` |
| events | 事件总数、每条事件的 `sequence`/`simulationTime`/`type`/`taskIds`/业务 attributes；**除** 各事件 attributes 中的本机耗时字段（如 `planningDecisionElapsedNanos`，REPRODUCIBILITY 契约已声明其排除在确定性 fingerprint 外） |

比对方式为**JSON 值的精确相等**，不是文本字节相等：对象键顺序无关，数组顺序保留；数值按十进制精确值比较，`1000`、`1000.0`、`1e3` 等值，但不先转换成 double，不设 epsilon 容差。因而大于 2^53 的相邻 long 种子仍不同，浮点相邻值也不能靠容差通过。输入 SHA、大小、格式、顺序和路径数组长度始终参与核心比较。

新证据包含 `configuration.executionSemantics=WORK_CONSERVING_TASK_EXECUTION_V2`。旧 v4 缺少此字段仍可读；重建后执行的是当前模型，不会补造“旧模型已被重放”的声明。即使部分数值恰好一致，执行模型声明或核心科学量变化仍应报告 `DIVERGED`。参见[网络研究协议修订](<NETWORK_STUDY_PROTOCOL_REVISIONS.md>)。

### 易变量（白名单豁免，仅记录）

- `provenance.*` 全部（codeSource、sourceTreeSha256、modulePomSha256、reactorPomSha256、javaClassPathSha256、workingDirectory）；
- `runtime.*` 全部（JVM 版本、OS）；
- metrics 与 events 中的本机 wall-clock 纳秒字段；
- `artifacts[].sha256`/`sizeBytes`（新 manifest 因 provenance 不同而哈希必然不同）；
- `inputs[].path`、`configuration.workflowPaths[]`、`result.workflowOutcomes[].path` 的路径文本；只在输入定位与 SHA/大小核对通过的复跑链路中应用，不豁免输入身份、输入顺序或数组长度。
- `configuration.algorithmContract` 作为身份信息单独报告；算法标签和执行模型版本不因此获得豁免。

白名单是**显式枚举**，实现为常量清单并配单元测试。新增易变字段必须走契约修订，不允许实现时随手加豁免——豁免清单每放宽一项，检出真实漂移的能力就弱一分。

对象成员名必须先按 RFC 6901 编码（先 `~`→`~0`，再 `/`→`~1`），再拼接指针并判定白名单；数组索引仍使用原有数字路径。根字面 key `configuration/algorithmContract` 的指针是 `/configuration~1algorithmContract`，其变化属于核心差异，不能冒充实际嵌套的 `/configuration/algorithmContract`；`provenance/extra` 同理。真实易变子树的差异记录也使用相同转义规则。

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

退出码：`IDENTICAL_CORE` 为 0；`DIVERGED` 为非零；其余失败状态各有独立非零码。这使得 rerun 可以直接作为 CI/门禁步骤使用。实现的具体分配：`IDENTICAL_CORE`=0、`DIVERGED`=1、`INPUT_UNRESOLVED`=2、`INPUT_HASH_MISMATCH`=3、`RECONSTRUCTION_REJECTED`=4、`EVIDENCE_INVALID`=5；命令行用法错误=64、意外异常=70（与 verdict 码不冲突）。

## 验收标准

1. **同模型复现（必跑）**：由当前代码生成小型 DAX 与合成负载证据，再执行 rerun，verdict 为 `IDENTICAL_CORE`；迁址同内容输入后也应如此，不依赖被忽略的历史输出目录；
2. **分歧检出**：对通过结构校验的受控核心漂移或旧执行模型声明，rerun 报告 `DIVERGED` 且精确指出字段。未通过结构/交叉约束的篡改应为 `EVIDENCE_INVALID`，不能为了进入差异阶段而放松证据校验；
3. **输入防护**：篡改输入文件内容后 rerun 报告 `INPUT_HASH_MISMATCH`；移走输入文件后报告 `INPUT_UNRESOLVED` 且列出候选路径；
4. **不兼容防护**：构造一个含已拒绝算法标签的 manifest，rerun 报告 `RECONSTRUCTION_REJECTED`；
5. **旧证据可读**：v2/v3 manifest 被明确拒绝并说明原因，不进入执行流程；
6. **回归与历史保留**：运行 `mvn clean verify` 并以本次报告为准。旧 v4 保持只读兼容，冻结工件不改写；核心执行模型修正后，不以旧数值充当当前黄金，也不承诺新旧产物不变。

## 测试要求

- **单元**：三类字段清单的枚举测试（核心量清单、易变量白名单、身份信息各自固定）；输入定位三级回退逻辑；verdict 判定逻辑；报告序列化。
- **语义**：篡改-检出配对测试（每类 verdict 至少一个手造样例，样例本身是小规模、可手算验证的输入）。
- **集成**：新鲜微型证据的端到端 rerun 与模型修订检出（验收标准 1、2）必须进入 `mvn verify`。历史证据的只读兼容另设可选测试，未提供本地归档时明确报告跳过，不影响必跑链路。

## 与后续任务的接口预留

- run 身份键（输入哈希 × 配置哈希 × 算法契约版本）在本任务中**只作为报告字段出现**，不实现缓存语义；后续 D-缓存任务直接消费本报告结构。
- study 级批量 rerun 复用本单 run 执行器，逐目录调用并汇总 verdict，不重新实现比对逻辑。

## 实现备注（实现期固化的决策）

- 实现代码集中在 `experiments/src/main/java/org/workflowsim/experiments/rerun/`：`RerunEvidenceReader`（读取+结构校验）、`RerunInputResolver`（三级定位）、`ManifestConfigRebuilder`（配置重建）、`RerunExecutor`（复跑流水线）、`EvidenceCoreDiffer`（核心量比对与易变量白名单）、`RerunReport`（双格式报告）、`RerunDiffExecutor`（CLI 入口）。
- **模拟执行阶段失败也映射 `RECONSTRUCTION_REJECTED`**：重建通过了但 `SimulationRunner` 拒绝执行（如历史故障模型参数在当前语义下必然耗尽重试预算），属于"历史证据与当前代码不兼容"的合法结果，与重建期拒绝同判，失败原因中标注"模拟执行阶段"。
- **metrics 分歧去重**：manifest 内嵌 `metrics` 与 metrics sidecar 是同一份核心量（工件校验器强制二者逐字相等），比对器对两份文档各记一条完全相同的分歧；实现按（指针, 旧值, 新值）精确去重，报告只出现一条。
- **一致性篡改的含义**：工件校验器强制 manifest 内嵌 metrics == sidecar metrics，且 sidecar 的 sha256/sizeBytes 记录在 `artifacts[]` 中。因此篡改核心量并期望通过结构校验到达 `DIVERGED`，必须同步改写三处（manifest metrics、sidecar 文件、artifacts 哈希/大小）；只改 manifest 会被 `EVIDENCE_INVALID` 提前拦截——这正是防线应有的行为。
- 测试分布：[夹具级验收](<../../experiments/src/test/java/org/workflowsim/experiments/rerun/RerunDiffExecutorTest.java>)覆盖 verdict 与迁址；[必跑集成](<../../experiments/src/test/java/org/workflowsim/experiments/rerun/RerunDiffExecutorIntegrationTest.java>)每次生成当前模型的微型证据，覆盖 tier 2/tier 1 和执行模型修订差异。[可选历史兼容](<../../experiments/src/test/java/org/workflowsim/experiments/rerun/HistoricalEvidenceCompatibilityIntegrationTest.java>)只读本次检出的归档，每个缺失用例明确跳过；不再向父目录搜索并借用另一检出的历史证据。
