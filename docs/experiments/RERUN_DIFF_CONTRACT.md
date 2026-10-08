# 复跑与精确差异比对

本能力回答：**一份有效的 manifest v4 证据，在当前代码下重新运行后，非豁免 JSON 核心量是否一致？** 它是单次运行的重建、重新仿真和差异检查，不是事件回放、断点续跑、缓存命中或整项研究的自动重跑。

报告再生与复跑也不同：Workbench 的 `report` 只从校验证据生成 HTML，不执行模拟器；这里的复跑会重新执行标准 `SimulationRunner`。模型适用性见[算法契约](<../algorithms/CONTRACTS.md>)，记录要求见[可复现性指南](<REPRODUCIBILITY.md>)。

## 命令与输入约定

从项目根执行，替换占位路径：

```bash
mvn -pl :workflowsim-experiments -am compile exec:java \
  -Dexec.mainClass=org.workflowsim.experiments.rerun.RerunDiffExecutor \
  '-Dexec.args="/absolute/original-run" "/absolute/new-rerun-output"'
```

[CLI](<../../experiments/src/main/java/org/workflowsim/experiments/rerun/RerunDiffExecutor.java>)接受且仅接受原 run 目录和输出目录。输出必须不存在或为空，应独立于原始证据；不把新产物写回原 run。

[证据读取器](<../../experiments/src/main/java/org/workflowsim/experiments/rerun/RerunEvidenceReader.java>)优先使用 `result.manifest.json`；若不存在，则接受目录内唯一的 `*.manifest.json`，没有或有多个候选时拒绝。metrics、events 和已声明的可选侧车按 manifest 引用定位，不靠猜文件名补齐。

只支持 manifest v4。v2/v3 可以由相应完整性校验器读取，但不能在这里静默升级后复跑。独立 manifest 不能代替完整 bundle，已声明侧车缺失或无效会导致 `EVIDENCE_INVALID`。

## 执行与快照边界

```text
读取原包并校验
  → 按输入身份定位文件
  → 从验证后的 manifest 重建配置和平台
  → 用当前标准运行器重新仿真
  → 写出并校验新的证据
  → 精确比较核心量
  → 输出 JSON 与 Markdown 判定
```

读取器使用校验器返回的 manifest 快照，不把未校验的预读树当作重建依据。此快照不是 CloudSim checkpoint，也不是文件系统快照：`RerunEvidence` 的 manifest 树按只读约定使用，metrics/events/侧车在后续比较时仍会从路径读取。调用方不得修改树，校验和复跑期间原目录内容也必须保持不变；本工具不提供并发写入隔离或归档认证。

## 输入定位规则

[RerunInputResolver](<../../experiments/src/main/java/org/workflowsim/experiments/rerun/RerunInputResolver.java>)保持以下顺序：

1. 若 run 位于 `<study>/runs/<run>`，尝试 `<study>/inputs/<原文件名>`。
2. 尝试 manifest 记录的输入路径。
3. 若路径能按原 `provenance.execution.workingDirectory` 相对化，将其重定位到**当前进程工作目录**。这不是自动发现仓库根，因此本指南要求从项目根启动。

任一级找到普通文件后，立即核对 SHA-256 和字节数；不符即 `INPUT_HASH_MISMATCH`，不得继续尝试其他文件来掩盖不一致。全部候选未命中时为 `INPUT_UNRESOLVED`，报告保留尝试过的候选。此 CLI 没有额外的 `--input-root` 参数，不能照搬独立 Python 审计器的选项。

输入顺序、哈希、大小、格式、版本和规范化信息仍参与核心比较。路径文本豁免只能在这条身份核对链路之后使用，不是任意替换输入的许可。

## 配置重建规则

[ManifestConfigRebuilder](<../../experiments/src/main/java/org/workflowsim/experiments/rerun/ManifestConfigRebuilder.java>)翻译保留的配置和平台：算法、文件系统、种子、runtime 转换、内核节拍、到达表、deadline、矩阵、故障/开销、Host/VM/价格、拓扑和存储。输入路径改用已核对身份的定位结果；空值和可选字段按 schema/兼容规则处理，不凭当前用户环境猜新的研究参数。

- 九种物理模型按其记录的 kind 和参数重建，不能把 V2/V3 改为 V1 或用无记录运行替代已启用捕获。
- 缺省 `networkEvidence` 明确表示 OFF；存在时恢复 mode 与预算。存在的 `dataflowAssignment` 也完整恢复，不因算法契约说明属于身份信息而忽略它。
- V3 的 `sourceStorage` 必须存在且与模型匹配；coherent 拓扑还须满足数值域。重建后的 VM→Host 预检映射须等于记录值。
- 参数、枚举或组合不再被当前代码接受时，报告 `RECONSTRUCTION_REJECTED`，不偷偷换算法、模型或容量。manifest 不会恢复未记录的外部策略进程或任意 Java 对象。
- 当前执行会生成当前 `executionSemantics`。旧 v4 缺少这个标识仍可能进入重建，但不是恢复旧引擎；新旧声明不同会作为核心分歧，即使部分数值碰巧相同。

输入、重建或运行失败不能被当作一致性成功。已分类的模拟执行异常和重试预算耗尽也映射为 `RECONSTRUCTION_REJECTED`，原因注明执行阶段；它是需要暴露的不兼容结果。

## 网络、生命周期与在线动作

| 记录模式 | 验证与捕获要求 | 精确比较命名空间 |
| --- | --- | --- |
| OFF | 无可选侧车；coherent 的文件计划及 V3 存储声明仍保留 | 相应 manifest 核心字段 |
| `FLUID_GROUP_LEDGER_V1` | V1 组账本的角色、哈希、内容和运行上下文；可明确标记截断前缀 | `/networkLedger/...` 全部内容 |
| `FILE_LIFECYCLE_V2` | V2 文件生命周期与运行上下文；只接受完整捕获 | `/fileLifecycle/...` 全部内容 |
| `FILE_STORAGE_LIFECYCLE_V3` | V3 存储生命周期、提交/输出义务及运行上下文；只接受完整捕获 | `/storageLifecycle/...` 全部内容 |

三种侧车角色互斥，不能互相替代。V2/V3 OFF 的 `dataflowPlan`、V3 的 `platform.sourceStorage` 仍属核心量。存储验证区分 CPU 返回与全部输出提交，包含零字节及 unused sink，并检查适用的故障/重试因果，不据此声称概率故障过程或随机数已被独立重放。

在线绑定的配置、`dataflowComputeRequests`、`DATAFLOW_VM_ASSIGNED` 事件及其全部动作属性都是核心量。在线认证要求完整的匹配 V2/V3 生命周期；其范围是绑定、名义边界和记录分数，不是精确活动流余额、未来争用或全局最优。即使一次分数改动仍能通过边界校验，实际复跑仍须精确比较并报告差异。

任一侧 V1 账本为 TRUNCATED 时，报告加入 `networkEvidenceCoverageNote`：即使 `IDENTICAL_CORE`，也只说明保留记录及聚合观察一致，不能认证未知后缀。两侧 OFF 或完整捕获不增加此占位字段。V2/V3 的完整生命周期同样不是逐区间流体服务面积证书。

格式与认证范围见[V1 账本](<../advanced/NETWORK_LEDGER_FORMAT.md>)、[V2 生命周期](<../advanced/FILE_LIFECYCLE_V2_FORMAT.md>)、[V3 存储](<../advanced/STORAGE_DATAFLOW_V3_CONTRACT.md>)和[在线绑定](<../advanced/ONLINE_DATAFLOW_ASSIGNMENT_V1_CONTRACT.md>)。

## 比对规则

[EvidenceCoreDiffer](<../../experiments/src/main/java/org/workflowsim/experiments/rerun/EvidenceCoreDiffer.java>)默认把**所有非白名单字段**视为核心，不仅比较 makespan：

- manifest 的配置、平台、输入身份、工作流 profile/graph、Job/Task/VM/工作流结果、实际放置，以及存在的文件计划和在线计算请求。
- metrics 的全部非豁免字段；events 的条数、顺序、全部事件字段和非豁免 attributes。
- 工件角色、引用路径、数组顺序与完整性声明；可选侧车的全部内容。

比较的是 **JSON 值的精确相等**，不是文件文本字节相等：对象键顺序无关，数组顺序和长度保留；数值按精确十进制值比较，`1000`、`1000.0`、`1e3` 等值，但不先转换成 double，也不使用 epsilon。字符串、布尔值、null 和缺失成员保持区别。实现见 [ExactJsonValues](<../../experiments/src/main/java/org/workflowsim/experiments/common/ExactJsonValues.java>)。

网络/生命周期内容通过严格词法读取保留数值类型，长数值不能退化为字符串或因 double 舍入丢失差异。大于 2^53 的相邻整数、真实的极小十进制变化和浮点相邻值不获得容差。统计分析中的平局阈值不能拿来放宽这里的比较。

### 固定易变量白名单

仅以下真实路径被豁免并记录差异；`<i>` 表示数组索引：

| 范围 | 豁免字段 |
| --- | --- |
| 根身份/环境子树 | `/provenance`、`/runtime` 的全部子树 |
| 算法说明身份 | `/configuration/algorithmContract` |
| 输入路径文本 | `/configuration/workflowPaths/<i>`、`/inputs/<i>/path` |
| 工作流结果路径文本 | `/result/workflowOutcomes/<i>/path` |
| 工件字节摘要 | `/artifacts/<i>/sha256`、`/artifacts/<i>/sizeBytes` |
| metrics 墙钟总量 | `/metrics/totalSchedulingDecisionWallClockNanos`、`/metrics/totalPlanningDecisionWallClockNanos` |
| 事件墙钟属性 | `/events/<i>/attributes/decisionElapsedNanos`、`/events/<i>/attributes/planningDecisionElapsedNanos` |

输入/结果等核心数组的长度、输入身份、配置中的 `schedulingAlgorithm` / `planningAlgorithm`、`executionSemantics`、物理参数和记录预算不在白名单内。侧车命名空间不继承根 `/runtime`、`/provenance` 豁免；工件哈希/大小豁免也不能使侧车内容漏比。新增字段默认核心，不能随手加入易变量规则。

对象成员名先按 RFC 6901 编码（`~`→`~0`，`/`→`~1`），再拼接指针、判定白名单。字面键 `configuration/algorithmContract` 的路径为 `/configuration~1algorithmContract`，不是嵌套的身份对象；`provenance/extra` 同理，不能借名称骗过分类。

读取器可接受非 `result` 命名的原包，但新复跑固定写 `result.*`；因此原包的工件引用路径可能产生合法核心分歧。**工件 path 没有额外豁免**，不能为获得 `IDENTICAL_CORE` 而忽略这种差异。

### 身份信息单独报告

报告保留两侧 `sourceTreeSha256` 和 `algorithmContract`。已知源树哈希不同时醒目提示代码身份变化，但这不代替输入/配置/结果比较。核心一致只说明该次证据在本比较范围内一致，不证明其他输入、平台或未来版本也相同；身份不可获得时不能推定一致。

## 报告与退出码

```text
rerun-output/
  rerun-report.json
  rerun-report.md
  rerun/
    result.manifest.json
    result.metrics.json
    result.events.jsonl
    ...已声明的匹配侧车
```

报告包含 `verdict`、输入定位、核心分歧的 JSON 指针和双侧值、`volatileFieldsNoted`、身份信息，以及适用的覆盖提示。分歧比较使用完整值；显示值超过 120 字符时截断并标注原长度，不因此把差异改为相等。manifest 与 metrics 侧车的重复指标分歧会去重。

| verdict | CLI 退出码 | 含义 |
| --- | ---: | --- |
| `IDENTICAL_CORE` | 0 | 全部非豁免 JSON 核心量精确一致，仍受捕获/模型范围限制 |
| `DIVERGED` | 1 | 至少一个核心差异，分歧清单非空 |
| `INPUT_UNRESOLVED` | 2 | 输入无法定位 |
| `INPUT_HASH_MISMATCH` | 3 | 命中输入但 SHA/大小不符 |
| `RECONSTRUCTION_REJECTED` | 4 | 当前代码拒绝重建或已分类的模拟执行 |
| `EVIDENCE_INVALID` | 5 | 原包无效、已声明侧车无效或不是 v4 |

用法/输出目录错误为 64，未预期异常为 70。已分类失败仍写诊断报告，但没有成功的新运行证据；用法错误或意外 I/O 失败不保证报告完整，也不能把留下的部分文件当作有效 bundle。Maven 编译阶段失败则尚未进入该 CLI 判定。

当前链路的验证覆盖见[核心比较测试](<../../experiments/src/test/java/org/workflowsim/experiments/rerun/EvidenceCoreDifferTest.java>)、[复跑集成测试](<../../experiments/src/test/java/org/workflowsim/experiments/rerun/RerunDiffExecutorIntegrationTest.java>)及[在线动作复跑测试](<../../experiments/src/test/java/org/workflowsim/experiments/rerun/DataflowAssignmentRerunIntegrationTest.java>)。它们使用明确夹具验证行为，不把可选本地历史输出作为当前运行已成功的证明。

研究协议完整性与实际复跑是不同检查，见[协议身份说明](<NETWORK_STUDY_PROTOCOL_REVISIONS.md>)。保留原证据、新运行和差异报告，不修改旧字段、协议身份或黄金值来消除真实分歧。
