# 可复现运行与证据记录

一次可解释的运行由**输入及顺序、平台、完整配置、随机种子和实际实现身份**共同定义。可复现不等于真实平台校准、WfInstances trace replay 或算法普遍优越。日常操作见 [Workbench](<../getting-started/WORKBENCH.md>)；本文说明必须记录什么，以及记录不能证明什么。

## 配置与默认值

研究代码通过不可变的 [SimulationConfig](<../../simulator/src/main/java/org/workflowsim/utils/SimulationConfig.java>)和 `PlatformProfile` 进入标准运行器。不要依赖上一次运行遗留的静态参数。

| 条件 | 当前约定 |
| --- | --- |
| 根种子 | Java 配置默认 0；Workbench 默认种子列表为 `[42]`。研究应显式记录每次实际种子 |
| 算法与文件系统 | Java 默认 FCFS、INVALID 规划、SHARED；其他组合须显式选择并通过校验 |
| 数据移动 | 默认 `LEGACY_WORKFLOWSIM_V1`；不能把切换模型当作不影响结果的实现细节 |
| runtime 转换 | 默认 `runtimeReferenceMips=1000.0`、`runtimeScale=1.0`；秒数转换为 `max(100, floor(runtime * referenceMips * scale))` MI |
| 内核节拍 | `cloudSimMinEventIntervalSeconds` 为正有限数，默认 0.1 模拟秒，影响事件释放和完成，不是网络延迟校准 |
| 到达时刻 | 输入与等长的到达表启动前已知，按模拟零时刻起算的秒数释放；默认全零 |
| deadline | 0 表示未请求；正 signed-long 阈值只观察 `simulationEndSeconds`，不改变调度、准入、重试或终止 |
| 开销与故障 | 默认关闭；启用时记录不可变分布规格、深度/VM 布局及重试预算，而不是复用已消耗的随机生成器 |
| 执行域 | 标准运行器要求 NONE 聚类、SPACE_SHARED、PE 兼容放置，每 VM 同时最多派一个 Job |

deadline 比较保留 signed-long 阈值与结束时刻精确 binary64 值，先比较再把 slack/tardiness 转成显示值，不使用 epsilon；未请求、超时和未完成工作流须分别解读。它不是逻辑任务完成时间的隐式 deadline。

输入格式、规范化和来源范围见[数据集说明](<../../datasets/README.md>)。DAX 不允许 DOCTYPE/外部实体；未纳入输入哈希的外部 DTD 不能改变模型。保存原始输入，不能只保留文件名或解析后的图。

## 种子、会话与重试

[SimulationRandom](<../../simulator/src/main/java/org/workflowsim/utils/SimulationRandom.java>)按稳定组件名称派生分离的确定性随机流；[SimulationSession](<../../simulator/src/main/java/org/workflowsim/utils/SimulationSession.java>)在串行运行前安装种子和模型，结束时清理所拥有的全局状态。同一 JVM 的并发会话会被拒绝。

相同代码、输入、平台和配置下，重复运行应重建相同的模拟随机过程；但组件内的抽样顺序仍可随调度、释放批次或重试变化。**同根种子不是事件键控共同随机数（CRN）**，也不自动构成独立重复或合法处理效应推断。批量运行的设计标签、实际种子与统计解释见 [campaign 指南](<CAMPAIGNS.md>)。

开销分布由深度键控；工作流引擎延迟的批次形成依赖实际释放事件。应保留配置以及事件中记录的队列/后处理等实际采样，不把不同算法下的同名随机流误作逐事件一一对应。若自定义扩展使用不受 `SimulationRandom` 管理的随机源，须单独声明种子、状态和复现测试。

标准故障恢复使用 `FTCLUSTERING_NOOP` 和 `MONITOR_NONE`；启用故障必须有正的 retry Job 总预算和有效分布布局。预算耗尽显式失败，不等于一个正常结束的工作流失败样本。每次重试有新 Job 身份，保留失败父尝试；失败输出不能成为后继的可读副本。示例及布局限制见[Java API 指南](<../getting-started/CODE_CONFIG_EXPERIMENTS.md>)。

## 工作量、平台和计时口径

[TaskExecutionModel](<../../simulator/src/main/java/org/workflowsim/utils/TaskExecutionModel.java>)将矩阵秒数按 `Math.round(seconds * vmMips)` 转为正整数单 PE MI。矩阵存在时不允许缺坐标后回退；MI×PE×1,000,000 必须可用 signed long 表示。`TaskOutcome.lengthMi` 保留原始归一化长度，`effectiveExecutionLengthMi` 是当前尝试的实际计算工作量，不含 stage-in。

Job 信封与 Task 计算窗口不能混用：legacy/固定端点可把有效输入延迟计入 Job 信封，pre-execution/coherent 模型在 CPU 开始前准备输入。Task 时间使用有效工作量，是否与整个 Job 窗口精确一致由相应字段说明。故障在尝试完成边界判定，不是经校准的中途宕机或部分写入模型。

`simulationEndSeconds` 与 `makespanSeconds` 是同一模拟结束时钟。只有全部逻辑 Task 成功时，`logicalTaskCompletionSeconds` 才可用，取各 Task 首次成功 Job 信封完成时刻的最大值；差值 `terminalLifecycleTailSeconds` 可以包含输出写回等尾部，不是纯 CPU 或调度器耗时。

[PlatformProfile](<../../simulator/src/main/java/org/workflowsim/platform/PlatformProfile.java>)记录 Host/VM 规格、可选 pin 和容量可行的预检映射；实际 VM 创建后的放置还须与预检一致。需要排除 Host 放置因素时使用一 Host 一 VM。共置是资源配置条件，不表示已经建模或校准 Host CPU 干扰、迁移或真实基础设施利用率。

`CostModel.DATACENTER` 与 `CostModel.VM` 是抽象计价范围；后者要求每 VM 显式定价。processing cost 包含每个已返回尝试的 CPU 信封成本和声明文件连续十进制 MB 的带宽成本，包括失败与重试；memory/storage 价格仅声明而不计费，没有服务商账单舍入或实际网络流量计费语义。完整指标定义见[算法、执行与指标契约](<../algorithms/CONTRACTS.md>)。

## 数据流与记录模式也是运行条件

当前物理模型包括五种 V1、两种 coherent 文件 V2、两种受限存储 V3。V1 pre-execution、coherent V2/V3 与普通在线调度的兼容性不同，完整组合见[模型能力表](<../advanced/DATAFLOW_CAPABILITY_MATRIX.md>)。

- V2 要求 LOCAL；V3 可用 LOCAL/SHARED，并必须声明受限 SOURCE 的位置、读/写/NIC 容量。
- coherent 未启用绑定时使用 RANDOM/STATIC；显式在线绑定使用 INVALID/STATIC 和版本化策略，控制就绪后绑定、随后准备输入，不能混同普通 CPU 在线调度。
- V3 的 SHARED 输入等待 SOURCE 提交，即使目标有缓存也不能绕过；所有成功输出包括 unused sink、零字节文件都必须履行提交义务。CPU 返回不等于存储完成。
- 捕获预算不改变物理运行，但决定证据是否足够。V1 组账本可明确保留截断前缀；V2/V3 生命周期导出要求完整捕获，在线动作认证也要求完整且匹配的生命周期。
- OFF 不表示没有网络。coherent 的核心 `dataflowPlan`、V3 存储条件以及已启用的绑定配置仍是运行身份，不能因关闭可选侧车而忽略。

原始 Java OFF 运行、完整生命周期证书、在线动作边界检查和逐区间流体服务证明是不同层次；不能把其中一个通过解释为全部通过。字段与输出规则见 [Workbench](<../getting-started/WORKBENCH.md>)。

## 证据包与组件身份

[ExperimentArtifactWriter](<../../simulator/src/main/java/org/workflowsim/experiment/ExperimentArtifactWriter.java>)写出 manifest v4、metrics v2、events v1，provenance 为 v3；开启记录时另有匹配的 `network-ledger`、`file-lifecycle` 或 `storage-lifecycle` 侧车。单独 manifest 不是完整 bundle。

[Manifest 写器](<../../simulator/src/main/java/org/workflowsim/experiment/ExperimentManifestWriter.java>)记录输入顺序/哈希、到达表、矩阵、算法标签与契约、数据移动、故障/开销、平台拓扑/存储、实际放置、Job/Task 结果及观察范围。当前执行标识是 `WORK_CONSERVING_TASK_EXECUTION_V2`；缺少这个字段的旧证据不能补造为当前执行记录。

[Provenance](<../../simulator/src/main/java/org/workflowsim/experiment/ExperimentProvenance.java>)区分核心组件与可选研究组件，并记录可获得的源树、模块/根 POM、二进制、classpath 字符串摘要和工作目录；JVM/OS 信息另保存在 manifest 的 `runtime` 对象中。组件根由类的 code source 推导，不从工作目录猜测；源码不可获得时明确保留缺失状态。

这些内容哈希不是 Git 提交号、作者认证或发行签名；classpath 字符串哈希也不是所有外部依赖内容的完整证明。研究交接还须保留构建依赖、外部策略或资源输入的版本信息。协议逻辑 ID 和协议原字节哈希用于绑定研究身份，当前与兼容身份见[网络研究协议说明](<NETWORK_STUDY_PROTOCOL_REVISIONS.md>)。

## 验证、复跑与保留

- **完整性校验**：检查 schema、相对引用、哈希、计数和上下文；校验器可读取受支持的旧 manifest，但不会自动补齐缺失语义。
- **实际复跑**：从已记录条件重建配置，在当前代码下重新执行并比较；不是恢复历史进程、缓存结果或用事件代替仿真。具体支持与精确白名单见[复跑差异契约](<RERUN_DIFF_CONTRACT.md>)。
- **身份差异**：JVM/OS、源码身份、路径与墙钟纳秒可能变化，必须记录。模拟时间、输入身份、算法标签、执行语义和非白名单字段不能因此获得容差或豁免。

所有 Java/Python/浏览器命令集中在[构建与验证](<../getting-started/BUILD.md>)。测试通过只说明相应断言成立，不说明现实校准或任意输入上的算法优越性。

保留输入、协议原字节、完整证据包、构建/组件身份和验证命令；原证据在校验/复跑期间不得被改写。新运行使用独立输出位置。构建目录清理与研究证据保留是不同操作，不能把未决定保留的输出或已归档的原始数据一概当作可删除缓存。
