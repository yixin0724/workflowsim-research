# Workbench：配置、运行与离线报告

Workbench 是维护中的统一实验入口。它读取 JSON 配置，运行同一决策层的算法，保存可校验证据并生成自包含 HTML；不启动 Web 服务、不加载 CDN。首次运行见[快速开始](<QUICK_START.md>)。

## 命令与路径

使用 JDK 17+、Maven 3.6.3+，从项目根目录执行：

```bash
# 预检：解析输入并检查配置组合，不运行仿真
mvn -pl :workflowsim-experiments -am compile exec:java \
  -Dexec.mainClass=org.workflowsim.experiments.workbench.Workbench \
  -Dexec.args="validate experiments/configs/online-comparison.json"

# 运行：在输出根下创建唯一实验子目录
mvn -pl :workflowsim-experiments -am compile exec:java \
  -Dexec.mainClass=org.workflowsim.experiments.workbench.Workbench \
  -Dexec.args="run experiments/configs/online-comparison.json output/workbench"

# 扫描实验记录，重建历史索引
mvn -pl :workflowsim-experiments -am compile exec:java \
  -Dexec.mainClass=org.workflowsim.experiments.workbench.Workbench \
  -Dexec.args="history output/workbench"

# 从单次运行证据生成独立报告；目标文件必须尚不存在
mvn -pl :workflowsim-experiments -am compile exec:java \
  -Dexec.mainClass=org.workflowsim.experiments.workbench.Workbench \
  '-Dexec.args=report "/absolute/run/result.manifest.json" "/absolute/new-report.html"'
```

CLI 参数中的相对路径以工作目录为基准；配置内部的 `workflowPaths` 相对于**配置文件所在目录**，也接受绝对路径。Maven 编译会写构建输出，`validate` 不是整个命令的零写入保证。

## 选择示例

| 研究对象 | 配置 |
| --- | --- |
| 普通 CPU 在线调度 | [在线比较](<../../experiments/configs/online-comparison.json>) |
| V1 Fat-tree 与静态映射 | [网络比较](<../../experiments/configs/network-comparison.json>)、[启用组级账本](<../../experiments/configs/network-ledger-comparison.json>) |
| V2 逐文件数据流 | [共享资源](<../../experiments/configs/coherent-file-dataflow-v2.json>)、[独立路径瓶颈](<../../experiments/configs/coherent-file-dataflow-isolated-v2.json>) |
| V3 受限存储与输出写回 | [共享资源](<../../experiments/configs/storage-dataflow-v3.json>)、[独立路径瓶颈](<../../experiments/configs/storage-dataflow-isolated-v3.json>) |
| 控制就绪后的在线目标绑定 | [V2 文件](<../../experiments/configs/online-file-dataflow-v1.json>)、[V3 存储](<../../experiments/configs/online-storage-dataflow-v1.json>) |

把 `run` 的配置参数替换为所选文件即可。V2/V3 的成对示例固定输入、容量、路径和生命周期规则，只改变跨流资源共享；独立路径瓶颈并不表示传输免费。

## JSON 配置

| 字段 | 约束与默认值 |
| --- | --- |
| `schema` | 固定为 `workflowsim-workbench-v1` |
| `name` | 显示名称，最多 128 字符；省略时使用默认名称 |
| `workflowPaths` | 1–32 个 DAX/XML 或 WfFormat JSON 输入 |
| `workflowArrivalSeconds` | 可选非负秒数列表，与输入等长，默认全零；从仿真零时刻起算 |
| `platform.vmMips` | 1–64 个正有限值，每个不超过 `1e12`；每台 VM 固定到独立 Host，1 PE、SPACE_SHARED |
| `platform.bandwidthMbPerSecond` | 正整数端点带宽，默认 1000；十进制 MB/s |
| `platform.cpuCostPerSecond` | 非负抽象 CPU 单价，默认 3，不是校准后的云价格 |
| `platform.storageTransferMbPerSecond` | 正整数共享存储传输率，默认 15；不同数据移动模型对存储有不同解释 |
| `platform.networkTopology` | 可选 Fat-tree 声明：`k`、`linkBandwidthMbPerSecond`、`coreSwitchCount`、`hostEdgePlacements`；Workbench 的偶数 k 范围为 2–16 |
| `platform.sourceStorage` | 仅 V3 必须声明的受限 SOURCE，字段见下文 |
| `simulation.fileSystem` | `SHARED` 或 `LOCAL`，默认 SHARED；仍须满足模型/算法约束 |
| `simulation.dataMovementModel` | 下表完整枚举名，默认 `LEGACY_WORKFLOWSIM_V1` |
| `simulation.runtimeScale` / `runtimeReferenceMips` | 正有限值，默认 1 / 1000；输入 runtime 到模型 MI 的转换参数 |
| `simulation.minEventIntervalSeconds` | 正有限值，默认 0.1 |
| `simulation.deadlineSeconds` | 可选非负 signed-long 整数，0 为未请求；只作事后观察，不改变调度或终止 |
| `simulation.taskCostMatrix` | 可选 `{taskId, vmId, executionSeconds}` 数组，覆盖全部逻辑任务×VM；要求 STATIC 路径，且不能与 SHARED_STORAGE 规划器组合 |
| `simulation.networkEvidence` | 可选记录对象，模式和预算必须同时存在，见记录表 |
| `algorithms` | 每项唯一 `id`，满足 `[A-Za-z0-9][A-Za-z0-9._-]{0,63}`；可声明 `scheduler`、`planner`、`dataflowAssignment` |
| `seeds` | 1–100 个互不重复的 signed-long 整数，默认 `[42]`；算法数×种子数不超过 500 |

没有 planner 时默认 INVALID/FCFS；声明非 INVALID planner 时默认 STATIC；显式在线绑定的默认派发器也是 STATIC。算法具体前提见[算法目录](<../algorithms/CATALOG.md>)，不能仅凭 JSON 字段可解析就认定所有组合有效。

未知或重复字段、字符串冒充数字、不合法整数、重复算法 ID、跨决策层混比、缺失输入和无效模型组合会被拒绝；独立任务映射器不接受含依赖边的输入。Workbench 固定无故障、无额外开销；这类研究需使用[Java API](<CODE_CONFIG_EXPERIMENTS.md>)。`RL_POLICY` 也通过 Java `RlEnvironment` 接入外部策略，不由 Workbench 训练模型。

## 九种数据移动模型

所有 Workbench 运行均使用 NONE 聚类、SPACE_SHARED VM；完整算法约束仍适用。

| `dataMovementModel` | 文件系统 | 物理模型与额外条件 |
| --- | --- | --- |
| `LEGACY_WORKFLOWSIM_V1` | SHARED / LOCAL | 保留 legacy 传输计算；默认模型 |
| `FIXED_ENDPOINT_NO_CONTENTION_V1` | SHARED / LOCAL | 固定端点、逐文件延迟，无跨流争用；必须提供 `simulation.fixedEndpoint` |
| `PRE_EXECUTION_TRANSFER_DELAY_V1` | SHARED / LOCAL | 静态目标映射；无争用输入 hold，传输不计入计算信封 |
| `PRE_EXECUTION_TRANSFER_DELAY_WITH_CONTENTION_V1` | SHARED / LOCAL | 静态目标映射；Job 就绪时启动传输组，VM 端点 max-min 共享 |
| `PRE_EXECUTION_TRANSFER_DELAY_WITH_FAT_TREE_CONTENTION_V1` | LOCAL | 静态目标映射；端点与确定性 Fat-tree 路径共享，必须声明拓扑 |
| `COHERENT_FILE_DATAFLOW_V2` | LOCAL | 逐文件可见性、实际源副本与路径，跨流共享容量 |
| `COHERENT_FILE_DATAFLOW_NO_CONTENTION_V2` | LOCAL | 同一文件生命周期，每条路径独立受瓶颈容量约束 |
| `COHERENT_STORAGE_DATAFLOW_V3` | SHARED / LOCAL | 受限 SOURCE、共享存储提交门控和必需输出写回，跨流共享容量 |
| `COHERENT_STORAGE_DATAFLOW_NO_CONTENTION_V3` | SHARED / LOCAL | 同一存储生命周期，每条路径独立受瓶颈容量约束 |

固定端点对象使用 `accessBandwidth`、`latencySeconds`、`sourceBandwidth`；仅固定端点模型可声明。带宽为正有限 MB/s，延迟为非负有限秒数。

coherent V2/V3 在未启用在线绑定时要求显式 **RANDOM/STATIC**，可选 Fat-tree 拓扑；现有 LOCAL 规划器不是 coherent 模型的估计器。启用拓扑不能用于不消费拓扑的其他模型。容量换算须保持在模型支持的有限数值域内。

V1 Fat-tree 和 V2 的外部 SOURCE 流量绕过 fabric，SOURCE 不设有限汇聚容量。V3 不沿用这个假设：`sourceStorage` 必须同时声明 `attachmentHostId`、`readBandwidthMbPerSecond`、`writeBandwidthMbPerSecond`、`networkBandwidthMbPerSecond`。Host ID 从 0 起，与 `vmMips` 条目对应；不接受未知 Host、缺省猜测容量或非正/非有限及不能换算为正 normal 有限 B/s 的容量。

V3 的 **LOCAL** 可读取已可见 VM 副本，但所有成功输出仍异步写回 SOURCE；**SHARED** 必须先等 SOURCE 提交，再使用目标缓存或从 SOURCE 读取，同 VM 缓存也不能绕过提交。unused sink 和零字节输出同样有提交义务；CPU 返回不等待写回，但仿真结束须等义务完成。完整边界见[V2 契约](<../advanced/COHERENT_DATAFLOW_V2_CONTRACT.md>)与[V3 契约](<../advanced/STORAGE_DATAFLOW_V3_CONTRACT.md>)。

## 在线目标绑定

在算法对象上显式声明：

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

该策略在控制依赖就绪后绑定实际兼容 VM，随后准备输入；STATIC 只派发已绑定且数据就绪的 Job。V2 仍要求 LOCAL，V3 可选 LOCAL/SHARED；它不能与 RANDOM 预规划并用，也不能与普通 CPU 在线调度或离线映射放进同一比较矩阵。

Workbench 在线证据必须启用匹配的完整生命周期记录。Java API 可以关闭捕获而保持动作与物理行为，但不能导出无记录的在线动作认证；NOOP 重试复用原逻辑绑定，不重新选 VM。

认证检查绑定、PE、有效 MI、CPU 预留、名义输入边界和记录分数，不认证精确在途余额或未来争用。报告明确显示 `liveProgressReplayed=false`；边界内估计变化仍会在实际 rerun 中作为核心差异。完整状态、公式与范围见[在线绑定契约](<../advanced/ONLINE_DATAFLOW_ASSIGNMENT_V1_CONTRACT.md>)。

## 记录与证据完整性

在 `simulation` 中增加记录对象，例如：

```json
"networkEvidence": {"mode": "FILE_STORAGE_LIFECYCLE_V3", "maxTraceRecords": 20000}
```

预算必须是 1–2147483647 的精确数值整数。省略对象表示 OFF，不能用 null、false、显式 OFF 对象或预算 0 代替。

| 模式 | 适用模型 | 侧车角色 | 捕获不完整时 |
| --- | --- | --- | --- |
| 省略（OFF） | 各物理模型；Workbench 在线绑定除外 | 无 | 不提供网络/生命周期捕获；不代表零网络 |
| `FLUID_GROUP_LEDGER_V1` | 两种 V1 争用模型 | `network-ledger` | 可导出明确标记的截断前缀；不能冒充完整账本 |
| `FILE_LIFECYCLE_V2` | 两种 coherent 文件 V2 模型 | `file-lifecycle` | 在工件 I/O 前拒绝导出；Workbench 记录失败 |
| `FILE_STORAGE_LIFECYCLE_V3` | 两种 coherent 存储 V3 模型 | `storage-lifecycle` | 在工件 I/O 前拒绝导出；Workbench 记录失败 |

预算耗尽不截断仿真。三种侧车角色互斥，不能把 V2/V3 侧车当作 V1 账本。coherent 模型即使 OFF，manifest 仍保留核心 `dataflowPlan`，V3 还保留存储配置。V2/V3 是生命周期证书，不是逐区间服务面积、链路利用率或 TCP 协议认证。

格式与独立校验见[V1 账本](<../advanced/NETWORK_LEDGER_FORMAT.md>)、[V2 生命周期](<../advanced/FILE_LIFECYCLE_V2_FORMAT.md>)、[V3 存储](<../advanced/STORAGE_DATAFLOW_V3_CONTRACT.md>)及[Python 检查器](<../../scripts/verify-network-ledger.py>)。rerun 将 `/fileLifecycle/...`、`/storageLifecycle/...` 和在线动作相关核心量纳入比较，见[复跑差异契约](<../experiments/RERUN_DIFF_CONTRACT.md>)。

## 输出、失败与恢复

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

manifest 为 v4，provenance 为 v3，metrics 为 v2，events 为 v1。写出后校验证据，再据同一验证快照生成报告。单个运行失败会记录根因并继续其他方案，失败不进入完成时间排行榜；有失败或未完成运行时，`run` 命令非零退出。

强制中断可能留下 RUNNING 状态和已完成的证据；通过历史页检查，不把它当成成功。`history` 显示损坏记录，不静默忽略。多进程写同一输出根时，应等进程全部结束后重建历史；同一 JVM 内仿真串行。

文件采用同目录临时写入后替换，支持时使用原子替换；整个证据包不是跨文件事务，以最终校验通过为完整标准。独立 `report` 拒绝覆盖已有目标，普通在线运行以 scheduler 命名，静态运行以 planner 命名，绑定轨道标为 `DATAFLOW_BINDING_V1`。

## 报告显示与验收

报告支持运行、VM/任务筛选、执行时间线和依赖聚焦。依赖概览最多 80 个节点，时间线最多同时绘制 250 个 Job，表格保留全部计算 Job。网络预览中，V1 流/V2-V3 复制与资源各最多 64 条，事件最多 128 条；V3 Job 预览最多 64 条。在线动作最多 64 条、每条候选最多 12 条，实际选择仍来自完整候选集。

网络与动作数值使用精确文本；种子显示为完整十进制字符串，不把大于 2^53 的相邻整数合并，原配置与 manifest 数值不被改写。捕获截断与界面预览省略分别计数，OFF、完整、零流、失败状态分别显示；切换运行清空旧面板，不另行请求侧车。

确定性算法的多种子结果仅作重复性观察，不当作独立统计样本。指标不等于真实平台测量；模型不包含 TCP、丢包/ECN、包级队列或自适应路由。

浏览器验收统一见[构建指南](<BUILD.md#离线报告浏览器验收>)；网络显示边界见[离线网络报告说明](<../advanced/NETWORK_REPORT_DISPLAY.md>)。
