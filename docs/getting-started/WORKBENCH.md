# 统一实验入口与离线报告

平台提供一个 Java 命令入口 `org.workflowsim.experiments.workbench.Workbench`。使用 JSON 配置选择输入、资源和同一决策层的算法，不需要新建 Java 实验类；报告是自包含 HTML，可双击离线查看，不启动服务、不加载 CDN。

## 从示例开始

在项目根目录，用 JDK 17+、Maven 3.6.3+：

```bash
# 预检：解析输入并验证算法/模型组合，尚不运行仿真
mvn -pl :workflowsim-experiments -am compile exec:java \
  -Dexec.mainClass=org.workflowsim.experiments.workbench.Workbench \
  -Dexec.args="validate experiments/configs/online-comparison.json"

# 运行：每次创建唯一子目录，保留以前的结果
mvn -pl :workflowsim-experiments -am compile exec:java \
  -Dexec.mainClass=org.workflowsim.experiments.workbench.Workbench \
  -Dexec.args="run experiments/configs/online-comparison.json output/workbench"

# 网络研究示例
mvn -pl :workflowsim-experiments -am compile exec:java \
  -Dexec.mainClass=org.workflowsim.experiments.workbench.Workbench \
  -Dexec.args="run experiments/configs/network-comparison.json output/workbench"
```

输出根目录中的 `index.html` 是实验历史；每个 `experiment-<UUID>/report.html` 是该实验的交互报告。可选择运行、筛选 VM/任务、悬停查看执行时刻。页面同时展示方案对比、等待时间、VM利用率和任务依赖图；依赖图概览最多80个节点，可以选择任务查看其直接前驱和后继。时间线最多同时绘制250个作业，表格保留全部计算作业。

这是新的维护入口；R9 删除的 `MyConfigurableExperiment`、旧 HTML/CSV 写器仍然不存在。新报告从经过校验的 v4 证据生成。

## 配置规则

示例在 `experiments/configs/`。路径相对于配置文件所在目录，支持绝对路径。

- `schema` 固定为 `workflowsim-workbench-v1`；`name` 是显示名称。
- `workflowPaths` 是1至32个DAX/JSON输入；可选 `workflowArrivalSeconds` 必须与输入等长，默认全零。
- `platform.vmMips` 声明每台 VM 的计算速度；每台固定到一个独立 Host，单核、SPACE_SHARED。VM数量为1至64。
- `platform.bandwidthMbPerSecond` 是正整数端点带宽。`networkTopology` 可声明 `k`、`linkBandwidthMbPerSecond`、`coreSwitchCount`、`hostEdgePlacements`。界面配置限制 k≤16，核心API仍有自己的模型范围。
- `simulation.fileSystem` 为 `SHARED` 或 `LOCAL`；`dataMovementModel` 使用完整枚举名。五种模型均支持，固定端点模型额外提供 `fixedEndpoint` 对象的 `accessBandwidth`、`latencySeconds`、`sourceBandwidth`。
- 可设置 `runtimeScale`、`runtimeReferenceMips`、`minEventIntervalSeconds`、`deadlineSeconds`；截止时间是事后观察。
- 可选 `taskCostMatrix` 是 `{taskId, vmId, executionSeconds}` 对象数组，须覆盖全部逻辑任务×VM，仅适用于允许该矩阵的静态轨道。
- `algorithms` 每项有唯一安全 `id`，以及 `scheduler` 或 `planner`。有 planner 时默认 STATIC；无 planner 时默认 FCFS。
- `seeds` 是1至100个互不重复的有符号64位整数，默认 `[42]`。总运行数≤500。确定性算法多种子结果仅作重复性观察，不计作独立统计样本。

HTML 内部显示行的 `seed` 使用精确十进制字符串，避免浏览器 `JSON.parse` 将大于 2^53 的相邻整数合并；运行选择、对比表和条件栏使用同一字符串。原配置、运行索引以及内嵌 manifest 的 `rootSeed` 仍保留原数值，显示适配不会改写模拟参数或证据。

未知字段、字符串冒充数字、小数整数、重复算法ID、跨决策层混比、无效模型组合、缺失输入、独立任务算法输入含边等会被拒绝。该入口当前针对无故障、无额外开销实验；故障/开销研究继续使用完整 Java API，不能用未知JSON字段悄然开启。RL_POLICY 也继续通过 Java `RlEnvironment` 接入外部策略；平台不训练模型。

## 可选V1组级网络账本

使用[启用记录的独立示例](<../../experiments/configs/network-ledger-comparison.json>)，或在已有配置的`simulation`中加入：

```json
"networkEvidence": {
  "mode": "FLUID_GROUP_LEDGER_V1",
  "maxTraceRecords": 20000
}
```

- **缺省是OFF**，不要用null、false、OFF对象或预算0代替省略。
- mode与预算必须同时存在，预算是1至2147483647的精确数值整数；字符串、布尔值、非整数小数和未知字段会在创建输出目录前被拒绝。
- 仅支持两种现有流体争用模型：`PRE_EXECUTION_TRANSFER_DELAY_WITH_CONTENTION_V1`、`PRE_EXECUTION_TRANSFER_DELAY_WITH_FAT_TREE_CONTENTION_V1`。现有静态mapper、拓扑、LOCAL/SHARED约束不变，不会自动替换模型或调度算法。
- 每个候选和每个seed都会保留该选项；ON在每个run下另写独立network-ledger工件，OFF继续保持原三件套和manifest字段集合。
- 记录预算耗尽只截断证据，不截断仿真；截断不是“零网络流量”。账本和派生指标可用[独立Python检查器](<../advanced/NETWORK_LEDGER_PYTHON.md>)校验，或通过[rerun精确比较](<../experiments/RERUN_DIFF_CONTRACT.md>)复验。

正常运行报告与独立报告现已显示专用网络摘要、流/FCT、资源占用和已保留事件预览；不是把原有VM利用率图改名为网络指标。OFF、完整捕获、开启记录但零流、截断与无有效结果分别提示。精确数值以字符串显示，流/资源/事件表分别最多64/64/128条；实际捕获截断与界面省略有独立计数，详细边界见[安全离线网络报告](<../advanced/NETWORK_REPORT_DISPLAY.md>)。

```bash
mvn -pl :workflowsim-experiments -am compile exec:java \
  -Dexec.mainClass=org.workflowsim.experiments.workbench.Workbench \
  -Dexec.args="run experiments/configs/network-ledger-comparison.json output/workbench"
```

## V2逐文件运行与生命周期证据

使用新的[共享资源配置](<../../experiments/configs/coherent-file-dataflow-v2.json>)或[独立路径瓶颈配置](<../../experiments/configs/coherent-file-dataflow-isolated-v2.json>)。它们使用同一[合成输入](<../../experiments/configs/inputs/coherent-file-dataflow.dax>)、相同资源/映射种子/释放与可见性规则，仅改变跨流共享；不替换任何旧研究输入或黄金结果。

```bash
mvn -pl :workflowsim-experiments -am compile exec:java \
  -Dexec.mainClass=org.workflowsim.experiments.workbench.Workbench \
  -Dexec.args="run experiments/configs/coherent-file-dataflow-v2.json output/workbench-v2"
```

- 模型标签为`COHERENT_FILE_DATAFLOW_V2`或`COHERENT_FILE_DATAFLOW_NO_CONTENTION_V2`。初始支持RANDOM＋STATIC、LOCAL、NONE聚类和无开销；现有LOCAL规划器不被自动认定为V2估计器。
- 记录选项为`{"mode":"FILE_LIFECYCLE_V2","maxTraceRecords":20000}`。省略是OFF，预算是精确正int32；模型与模式必须匹配，不能搭配V1组账本。
- V2记录实际源副本、原生产Task/Job尝试、所计路径、同目标合并、有效完成与观察可见，以及CPU输入屏障。它是**生命周期证书，不是逐区间服务面积/链路利用率证书**。
- 首次V2导出仅接受完整捕获。预算不足不改变模拟轨迹，但在创建/替换证据文件前拒绝导出；Workbench记录失败，不产生伪完整侧车。不要将此与V1可显示的截断前缀混淆。
- V2模型即使记录OFF，也在manifest保存核心`dataflowPlan`；旧模型OFF的原字段集合不变。V2 ON侧车角色是`file-lifecycle`，不会出现在旧`network-ledger`路径或旧getter中。
- 正常run与独立report使用相同验证快照。V2面板只以精确文本显示有限预览；与V1、OFF、失败运行切换时清空旧数据，不重新请求侧车。
- Java和[独立Python检查器](<../../scripts/verify-network-ledger.py>)均能验证V2完整bundle，rerun把`/fileLifecycle/...`全部作为核心量比较。V2的SOURCE仍显式无限汇聚/绕过fabric；有限存储与输出上传使用下述独立V3，在线目标绑定则使用后述显式动作策略。

详细数据形状、数值支持域与证书边界见[生命周期格式](<../advanced/FILE_LIFECYCLE_V2_FORMAT.md>)和[V2运行契约](<../advanced/COHERENT_DATAFLOW_V2_CONTRACT.md>)。

## V3受限SOURCE、共享存储和输出写回

使用[受限存储示例](<../../experiments/configs/storage-dataflow-v3.json>)或[相同路径/容量的独立瓶颈对照](<../../experiments/configs/storage-dataflow-isolated-v3.json>)：

```bash
mvn -pl :workflowsim-experiments -am compile exec:java \
  -Dexec.mainClass=org.workflowsim.experiments.workbench.Workbench \
  -Dexec.args="run experiments/configs/storage-dataflow-v3.json output/workbench-storage"
```

`platform.sourceStorage`必须同时提供`attachmentHostId`、`readBandwidthMbPerSecond`、`writeBandwidthMbPerSecond`和`networkBandwidthMbPerSecond`。Workbench的Host从0起编号，与`vmMips`条目对应。参数按十进制MB/s换算到正normal有限B/s；不接受null、字符串数字、缺省猜测容量或未知Host。只有V3模型可声明该对象，省略对象不能退回无限SOURCE。

模型为`COHERENT_STORAGE_DATAFLOW_V3`或`COHERENT_STORAGE_DATAFLOW_NO_CONTENTION_V3`，记录为`{"mode":"FILE_STORAGE_LIFECYCLE_V3","maxTraceRecords":20000}`。继续要求RANDOM/STATIC/NONE/无开销，文件系统可显式选：

- **LOCAL**：可读已可见VM副本，但全部成功输出仍异步写回SOURCE。
- **SHARED**：先等SOURCE提交，再使用目标VM缓存或只从SOURCE读取；同VM缓存也不能绕过提交。

成功输出包括unused sink和零字节，仿真结束需等写回义务完成；CPU返回不被推迟，VM可与写回重叠计算。报告独立展示输入/输出载荷、SOURCE等待、名义输入估时、观察准备延迟、CPU完成和输出尾部。以上不是未经记录的链路利用率或逐区间服务面积认证。

完整工件使用独立`storage-lifecycle`角色，Java/Python验证配置/来源/路径/输出义务/CPU及故障重试因果；预算不足在任何工件I/O前拒绝。记录OFF仍保留核心文件计划和存储配置，不意味着零网络。rerun在`/storageLifecycle/...`逐字段比较，没有新增内容豁免。专用离线面板不读取额外文件，数值始终为精确文本，复制/资源/Job/事件预览上限为64/64/64/128。边界及schema见[存储V3契约](<../advanced/STORAGE_DATAFLOW_V3_CONTRACT.md>)。

## 在线目标绑定：控制就绪后选VM，随后准备输入

使用[在线V2示例](<../../experiments/configs/online-file-dataflow-v1.json>)或[在线受限存储V3示例](<../../experiments/configs/online-storage-dataflow-v1.json>)。在每个算法对象上显式声明，而不是修改物理模型：

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

```bash
mvn -pl :workflowsim-experiments -am compile exec:java \
  -Dexec.mainClass=org.workflowsim.experiments.workbench.Workbench \
  -Dexec.args="run experiments/configs/online-storage-dataflow-v1.json output/workbench-online"
```

- 对V2仍要求LOCAL，对V3可LOCAL/SHARED；NONE聚类、SPACE_SHARED及无开销不变。该阶段绑定实际兼容VM，STATIC只派发已绑定、数据就绪的Job；NOOP重试不得换VM。
- Workbench在线工件要求显式、匹配的完整生命周期记录。缺少记录选项、错误模式、未知策略或与RANDOM规划并用会提前拒绝。原始Java运行仍能关闭捕获，且动作/物理不变，但OFF不导出动作认证。
- 此决策层不能与普通CPU在线调度或离线映射混排。独立报告标记`DATAFLOW_BINDING_V1`；条件行同时显示策略版本。
- 动作面板与物理V2/V3面板并存。它验证绑定、PE、MI、CPU预留、名义输入边界和已记录分数，不认证精确在途余额或未来争用；界面明确显示`liveProgressReplayed=false`。边界内估计变化仍会被实际rerun判为核心分歧。
- 动作预览最多64条，每条候选最多12条，选择来自完整候选集。所有数值是精确字符串，切换失败/普通运行时清理旧数据，不读取额外侧车。

详细状态、公式、认证范围和数值边界见[在线绑定契约](<../advanced/ONLINE_DATAFLOW_ASSIGNMENT_V1_CONTRACT.md>)。

## 历史与证据

每次实验包含：

```text
experiment-<UUID>/
  configuration.json       # 用户提交的配置
  experiment.json          # 运行状态、摘要、相对证据路径
  report.html              # 自包含报告
  runs/<algorithm>-s<seed>/
    result.manifest.json   # v4完整条件与结果
    result.metrics.json
    result.events.jsonl
    result.network-ledger.json  # 仅FLUID_GROUP_LEDGER_V1模式
    result.file-lifecycle.json  # 仅FILE_LIFECYCLE_V2模式
    result.storage-lifecycle.json  # 仅FILE_STORAGE_LIFECYCLE_V3；三个角色互斥
```

逐运行证据写出后即调用核心验证器。运行失败会记录根因并继续其他方案，失败不进入完成时间排行榜；命令完成后若存在失败会以非零退出。进程被强制中断时，已完成运行保留，状态可能仍为RUNNING，可通过历史页面识别。文件采用同目录临时写入后原子替换；三件套不是跨文件事务，因此必须以最终校验通过为完整证据标准。

重建历史与单独生成报告：

```bash
mvn -pl :workflowsim-experiments -am compile exec:java \
  -Dexec.mainClass=org.workflowsim.experiments.workbench.Workbench \
  -Dexec.args="history output/workbench"

mvn -pl :workflowsim-experiments -am compile exec:java \
  -Dexec.mainClass=org.workflowsim.experiments.workbench.Workbench \
  -Dexec.args="report /absolute/run/result.manifest.json /absolute/new-report.html"
```

单独报告命令拒绝覆盖现有目标；在线轨道以实际 scheduler 命名，静态轨道以 planner 命名，不把在线运行的占位规划器 `INVALID` 当成方案名。历史由各实验记录扫描重建，可见损坏记录而不会悄然略过；多进程写同一历史目录时需要在全部进程结束后执行一次 `history` 取得完整最新索引。同一JVM内实验串行。

## 验收

配置单元测试覆盖严格解析、组合边界和输入资格。入口集成测试覆盖配置→仿真→v4工件校验→HTML→历史、失败记录、重复实验隔离、HTML转义、算法标签及大整数种子载荷；最后运行全量 `mvn clean verify`。这些 Java 检查不执行浏览器 JavaScript，不能替代下面的显式浏览器验收；大种子报告还应核对运行选择器和条件栏的完整数字。

[浏览器验收脚本](<../../scripts/verify-report.cjs>)检查运行选择、VM/任务筛选、依赖图聚焦、图表、390px窄屏布局、网络精确文本和预览计数、无浏览器错误以及无HTTP/额外file读取。[验证器反例测试](<../../scripts/test-report-checker.cjs>)会破坏临时报告副本，确认这些闸门确实能失败。使用仓库固定版本的Playwright并复用已有Chrome：

```bash
npm ci --prefix scripts --ignore-scripts --no-audit --no-fund
WORKFLOWSIM_CHROME="/absolute/path/to/chrome" \
  node scripts/verify-report.cjs /absolute/experiment/report.html
```

macOS默认Chrome位置已在脚本配置；其他系统设置 `WORKFLOWSIM_CHROME`。Node/Chrome仅用于页面验收，不是Java仿真的运行依赖。
