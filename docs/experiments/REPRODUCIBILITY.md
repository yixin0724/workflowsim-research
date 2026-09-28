# WorkflowSim 可复现性契约

## 适用范围

本契约把一次可解释的仿真运行定义为：工作流输入、显式资源平台、不可变运行参数和根随机种子的组合。报告结果时必须记录这四类信息。可复现的运行记录不等于现实平台校准、WfInstances trace replay 或算法优越性证据。

核心模拟器位于 `simulator/`；示例、P7/reference 和特定研究驱动位于 `experiments/`。根 Reactor 默认构建并验证两个模块，不存在额外的 `experiments` profile。模块边界决定可复用 API 与研究驱动的组件身份，不能被解释为真实平台校准。

## 确定性与模型默认值

- `SimulationConfig` 拥有根随机种子，默认值为 `0`。研究代码应把每次运行输入放入此不可变配置并经 `SimulationRunner` 执行；`SimulationSession` 会在每次串行运行前后重置遗留静态门面。
- `SimulationRandom` 为规划、聚类、runtime 分布和故障分布派生独立且确定的流，不共享一个可变全局随机流。
- 失败分布由不可变 `FailureModelConfig` 声明，并在会话安装种子后实例化。VM/depth 矩阵不完整会 fail fast；标准 `SimulationRunner` 仅接纳经验证的 `FTCLUSTERING_NOOP` 重试路径。未校准的 `NORMAL` 故障/开销分布会被拒绝，而不是静默解释。
- 排队、工作流引擎、后处理与聚类开销由不可变 `OverheadModelConfig` 声明；每个 depth 特定延迟拥有独立命名随机流。WED interval=0 表示整批不拆分，正值为子批次最大作业数；每个非空释放批次按首个 Job 深度抽样一次，再累计应用到所有子批次（包括不足一批的尾部）。空批次不抽样，零数据传输不会单独改变此规则；不同实际数据到达仍可能形成不同批次，不能把同根种子误作 event-keyed CRN。
- WfCommons runtime 以秒输入，按 `runtimeReferenceMips * runtimeScale` 转为 MI。默认值是 `1000 MIPS` 与 `1.0`，属于必须记录的平台模型假设。
- `cloudSimMinEventIntervalSeconds` 是正的 CloudSim 内核 cadence 参数，默认 `0.1` 模拟秒。它影响事件释放与任务开始时间，必须写入工件；它不是网络时延校准。新任务加入前必须结清旧执行集合，cadence 不能让任务获得到达前的 CPU 工作。
- 计算量由 `TaskExecutionModel` 统一换算。显式矩阵使用 `round(seconds × vmMips)`；`TaskOutcome.lengthMi` 保留输入声明，`effectiveExecutionLengthMi` 记录当前尝试的实际计算 MI（不含 stage-in），Task 窗口和故障判定使用后者。`MI × PE × 1_000_000` 必须处于 signed long 可表示范围，非正舍入和溢出会明确失败。
- DAX 不允许 DOCTYPE/外部实体；未纳入输入哈希的 DTD 不能影响任务长度或访问外部资源。
- `SimulationConfig.deadline` 只是从模拟时间零点开始、以 `simulationEndSeconds`（历史 `makespan`）为观察基准的 SLA 阈值。`0` 表示未请求 deadline；正值记录 `MET`、`MISSED_LATE` 或 `MISSED_INCOMPLETE_WORKFLOW` 和相应 slack/tardiness，但不改变调度、准入、重试或故障行为。它不是最后一个逻辑 Task 成功完成时刻的隐式 deadline。阈值保留signed-long精度，与结束时刻的精确二进制double值比较后才把差值转回double，不使用epsilon；例如`T=2^63,D=Long.MAX_VALUE`必须晚1秒。
- 标准 `SimulationRunner` 仅接受 `ClusteringMethod.NONE` 与 `SPACE_SHARED` VM。其他聚类和 `TIME_SHARED` 仍可由历史低层 API 使用，但它们与 ready-Job 调度、Task outcome 和指标的端到端契约尚未认证。
- `PlatformProfile` 会预检确定的、容量可行的 VM-to-Host 映射。显式 pin 的 VM 固定到声明 Host；未 pin VM 使用兼容历史的最大剩余 PE 选择与声明 Host 顺序 tie-break。CloudSim 创建 VM 后，调度器冻结实际映射并与预检比较。这是放置可复现性证据，不是 Host CPU 争用模型。
- `JobOutcome` 是 CloudSim Job envelope，包含有效 stage-in 长度；`TaskOutcome` 是该延迟之后的逻辑 Task 计算窗口，仅当一 Task 窗口与 Job envelope 相同才标记为精确。重试会作为新的 Job attempt 保存，已失败 attempt 的状态与时间不会被改写。所有逻辑 Task 成功时，`logicalTaskCompletionSeconds` 是这些 Task 首次成功 Job-envelope 完成时间的最大值；否则该值不可用。`simulationEndSeconds` 与历史 `makespan` 相同，二者的差为可用时的 `terminalLifecycleTailSeconds`。
- 当前失败模型在一个 attempt 的 Job envelope 完成后判定成功或失败。只有成功 Task 的声明输出才会提交到副本目录；失败 Task 输出不会成为后继 Job 的可读副本。这是 fail-after-attempt 的模拟语义，不是中途宕机、部分工作量损失或真实存储提交协议。
- `CostModel.DATACENTER` 使用 CloudSim 数据中心成本模型；`CostModel.VM` 要求每个 VM 显式定价。两者都不是云服务商价格校准。当前 processing cost 包含 CPU-envelope 分量和按全部声明文件连续十进制 MB 计价的带宽分量，包含失败及重试 attempt；记录的 memory/storage 价格不等于已计费价格，也没有服务商计费舍入或实际网络账单语义。
- 事件工件包含显式规划和运行时调度决策的本地 JVM 纳秒耗时。这只支持同环境诊断，刻意排除在确定性事件 fingerprint 外，不能支撑跨机器算法运行时间比较。

## 构建与测试门禁

```bash
# 仅核心模拟器。
mvn -pl :workflowsim verify

# 核心 + 实验模块：默认单元、语义、集成和覆盖率门禁。
mvn verify

# 清理后联合验收，额外检查核心 Java API 文档。
mvn -Pjavadoc clean verify
```

`mvn test` 仅运行快速单元/语义测试；`mvn verify` 还通过 Maven Failsafe 运行所有 `*IntegrationTest`。默认 `mvn verify` 覆盖所有模块。核心 `SimulationRunnerIntegrationTest` 仍随默认核心门禁运行。

测试证明实现满足其断言的契约，例如输入校验、确定性、事件顺序或文件完整性。测试通过不证明仿真模型已经通过现实硬件、网络、价格、故障分布或 trace 数据验证。

## Evidence 工件与组件身份

一个完整 evidence bundle 包括 `manifest.json`、`metrics.json` 和 `events.jsonl`。`ExperimentArtifactValidator` 验证必需的 manifest 顶层结构、相对路径约束、sidecar 哈希/大小、manifest 与 metrics sidecar 的结构一致性、事件序列与计数，以及 v3 provenance 的显式可空字段。

当前写入 manifest v4、metrics v2 和 events v1，provenance 保持 v3。v4 完整记录输入顺序、到达时刻、任务成本矩阵和拓扑声明；修正后的运行还记录 `configuration.executionSemantics=WORK_CONSERVING_TASK_EXECUTION_V2`。核心组件和可选研究组件、协议逻辑标识/哈希、显式数据集根均被记录，不通过当前工作目录猜测项目根。源代码内容 hash 是本地组件身份的一部分，不是 Git 提交或发行签名。

验证器继续读取历史 manifest v2/v3/v4，但不补造旧版缺失的字段，也不把没有执行修订声明的旧记录冒认为新模型。历史证据完整性通过不意味着当前代码逐位复跑相同；研究协议的修订与认证范围见[协议修订说明](NETWORK_STUDY_PROTOCOL_REVISIONS.md)。

以下验证器只读目标工件；命令中的 Maven `compile` 仍会生成构建输出：

```bash
# 验证单个 evidence bundle。
mvn -Pcore-exec -pl :workflowsim -am \
  -Dexec.mainClass=org.workflowsim.experiment.ExperimentArtifactValidator \
  -Dexec.args="/absolute/output/run/manifest.json" \
  compile exec:java

# 验证保留的 P7 完整基线或开发性选择 index 及其引用 bundle。
mvn -pl :workflowsim-experiments -am \
  -Dexec.mainClass=org.workflowsim.experiments.reference.p7.P7EvidenceIndexValidator \
  -Dexec.args="/absolute/output/p7-baseline-index.json" \
  compile exec:java

# 验证 campaign index。
mvn -Pcore-exec -pl :workflowsim -am \
  -Dexec.mainClass=org.workflowsim.experiment.ExperimentCampaignValidator \
  -Dexec.args="/absolute/output/experiment-campaign-index.json" \
  compile exec:java
```

实验 P7 批量执行器本身位于 `experiments/`，并且只接受绝对 `datasets/` 根目录与绝对空输出目录。其命令、输入路径契约和保留规则见 [`P7_PROTOCOL.md`](../experiments/reference-baselines/P7_PROTOCOL.md)。

WfCommons 示例也是可运行的冒烟检查，但它位于实验模块：

```bash
mvn -pl :workflowsim-experiments -am \
  -Dexec.mainClass=org.workflowsim.examples.wfcommons.WfCommonsSimulationExample1 \
  compile exec:java
```

它的默认本地 `PlatformProfile` 只是一个受控同质平台，不表示重放 WfCommons 的来源执行硬件。

## 随机性、统计与外部边界

`ExperimentPlan` 区分单次确定性运行、独立重复和复用 root seed。复用 root seed 会明确记录为 `COMMON_ROOT_SEEDS_NOT_EVENT_KEYED_CRN`：调度决策可改变不同组件随机流的消耗顺序，因此相同 root seed 不是 event-keyed common random numbers，也不能自动支撑配对统计推断。campaign 会把逻辑 Task 完整成功的运行作为单独的 workflow-run 指示量；仅在显式 `INDEPENDENT_REPLICATIONS` 下才为该模型运行比例提供 Wilson 区间。它不构成现实平台可靠性或故障分布校准结论。

若研究显式使用随 CloudSim 引入、但不由 `SimulationRandom` 管理的随机类，必须独立配置种子并增加场景特定重放测试。进一步的可复现性还要求记录 JDK、Maven 依赖版本、输入文件哈希、完整平台规格、配置 manifest，以及任何外部资源 trace 或校准数据。

进行随机性研究前，应预先冻结工作流/平台矩阵、候选集、主要指标、最小有意义效应、最小/最大重复数、置信区间精度规则、多重比较方法与计算预算。详细的 campaign、统计和数据移动主张边界见 [`CAMPAIGNS.md`](../experiments/CAMPAIGNS.md)。

## 清理规则

Maven `target/`、JaCoCo 报告、临时 manifest、指标 JSON、事件日志、scratch 输出和未决定保留的结果图都不是源码。完成验证后：

```bash
# 默认清理核心与实验两个模块的构建输出；不会删除自定义研究目录。
mvn clean
```

只有研究协议明确了保留理由、组件/输入身份、输出位置和验证器命令的工件，才应作为研究交付物保留。
