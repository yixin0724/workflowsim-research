# 控制就绪在线VM绑定V1（NF005）

## 当前交付边界

NF005A已实现并测试只读结构化网络观测、版本化配置、受控绑定策略和真实Kernel的绑定→输入准备→CPU闭环。**专用动作上下文校验、manifest/重放及Workbench接线属于NF005B**；在这些环节完成前，开启在线绑定的工件与独立manifest导出均在I/O前明确拒绝，读取器也不忽略未解释的绑定配置。原V1/V2/V3默认运行和已完成证据闭环不变。

在线绑定不是另一个物理网络版本：它复用一致数据流V2或存储V3，在每个逻辑Task控制依赖和工作流到达门控解除后，选择固定目标VM，随后由既有逐文件协议准备输入。STATIC只派发已绑定且数据就绪的Job，不重新选择VM。

## 显式配置与支持矩阵

[DataflowAssignmentConfig](<../../simulator/src/main/java/org/workflowsim/data/v2/DataflowAssignmentConfig.java>)默认OFF，`onlineNominal()`选择：

```json
{"mode":"CONTROL_READY_ONLINE_ASSIGNMENT_V1","policy":"NOMINAL_INPUT_EARLIEST_RESERVATION_V1"}
```

启用时要求`planningAlgorithm=INVALID`、`schedulingAlgorithm=STATIC`、一致V2/V3物理模型、NONE聚类（每Job一个Task）、SPACE_SHARED和无开销。V2仍限LOCAL，V3可LOCAL或SHARED；NOOP故障重试规则保留。未启用时仍按原RANDOM预映射规则运行；旧执行前传输、LOCAL规划器或CPU在线调度约束没有被简单删除。

该配置独立于网络记录，未来在普通`configuration.dataflowAssignment`字段保存，而不只放在被rerun视作身份易变量的`algorithmContract`中。严格解码只接受上述两个已知字符串字段；缺省由调用者表示OFF，显式null/false/OFF对象、缺字段或未知策略都拒绝。

## 被观察的是已提交状态

- [TransferServiceSnapshot](<../../simulator/src/main/java/org/workflowsim/data/TransferServiceSnapshot.java>)由checked流服务的`TransferContentionEngine.snapshotState()`生成，直接复制当前容量、活动流、余额、速率、路径及服务水印。不推进、重分配、fork、记录或调用CloudSim；旧未检查构造器明确拒绝该API。
- [DataflowObservation](<../../simulator/src/main/java/org/workflowsim/data/v2/DataflowObservation.java>)由`CoherentDataflowRuntime.observe()`或`observeFiles(scope)`生成。数据来自同一个已提交Frame，不消费ready队列，不依赖OFF/完整/截断捕获。初始化前标为UNINITIALIZED，计划已知但实际fabric未绑定为PLAN_ONLY，不编造VM放置和容量。
- 文件视图包含已声明尺寸和**当前可见**副本；预测完成不会产生副本。活动复制包含实际来源、typed destination、INPUT/OUTPUT目的、拥有者及当前服务进度。Job视图只列未终结尝试，分别表示等待SOURCE、等待目标复制、就绪待CPU和RUNNING。
- `observeFiles`只限定文件持有者清单；全局活动复制、资源与未终结Job仍可见。范围外文件查询会拒绝，不伪装成“已知但无副本”。不可变静态元数据可以共享，mutable集合均被复制，不保留每次查询历史。
- 资源分配速率按路径重数精确累加为BigDecimal。独立无共享模式的总分配可以超过资源容量，甚至超过Double.MAX_VALUE；不截断为容量，也不把它称作有界期间利用率。

控制闭环显式在当前观察执行`advance(now)`，再读取快照。读接口本身不会自动推进到CloudSim.clock。没有使用CPU调度器中返回stub 0或会改变内部状态的MIPS查询作为真实遥测。

## 确定性的myopic策略

[DataflowVmAssigner](<../../simulator/src/main/java/org/workflowsim/data/v2/DataflowVmAssigner.java>)持有每次运行独立的逻辑Task绑定和CPU预留。候选必须恰好覆盖实际创建的fabric VM ID，按数值ID排序，Task/Job PE均须兼容。非连续ID不是数组下标。

对候选VM计算：

```text
inputDelay = max(每个唯一逻辑输入文件的名义剩余延迟)
cpuAvailable = now + sum(该VM仍活动的预计剩余CPU预留)
scoreFinish = max(now + inputDelay, cpuAvailable) + executableComputeSeconds
```

- `executableComputeSeconds`通过既有TaskExecutionModel计算，使用与运行相同的Task×VM矩阵和整数MI舍入；缺失矩阵坐标或不可表示成本不回落到原始MI。
- 已开始CPU的预留按`max(0, nominalComputeSeconds - (now - observedCpuStart))`估计；尚未开始的保留完整名义计算量。终结尝试从有效预留中排除，下一次成功绑定提交时清理。所有活动compute必须属于该受控策略的预留，未知活动负载不能被当作空闲。
- 允许选择当前忙VM：输入传输可与既有计算重叠。绑定不设置VM BUSY，实际STATIC仍按data-ready/idle队列派发，未承诺遵守预留顺序。
- 允许的本地缓存输入延迟为0；非本地已在途(file,destination)复制使用观察剩余字节/其冻结名义瓶颈，不能重复计整份引用量或换一个更快来源。
- 普通可见输入采用与物理策略一致的最大独立瓶颈/稳定位置规则。多个文件可并行，因此这里用max；这不同于主事件中“名义独立输入秒数之和”的工作量指标。
- V3 SHARED在SOURCE尚未提交时，必须找到其活动OUTPUT写回，估计`writeRemaining/writeNominalRate + postCommitRead`；目标已有VM副本时只能省略提交后的读取，不能省略SOURCE门控。该项明确标记`conditionalStoreWait=true`，不是当前SOURCE可见或精确到达预测。
- 缺少当前来源/必要SOURCE写回路径、非有限成本或无法推进binary64预测时钟时明确拒绝，不赋零成本。并列分数选择较小的实际VM ID。

这是名义路径/预留的**短视估计**，不是共享服务完整重放、真实CPU监控或全局最优调度证明。后续流、输入等待和data-ready队列可能改变实际完成时间。

## 绑定、重试与提交次序

逻辑Task ID和Job尝试ID分开。第一次绑定要求Task/Job VM均为-1；NOOP创建新的Job/Task副本时必须继承既有逻辑绑定，重试只增加新的CPU工作预留，不重新选VM。

候选打分不调用requestJob、不添加任何模拟流。先计算完整有效决策，再向一致数据流运行器提交**一次**真实输入请求；只有该事务成功，才提交绑定、预留、策略序号及Task/Job VM字段。观察时钟推进是显式闭环步骤，与纯读取分开。失败的真实请求不会留下绑定或预留。

新主事件`DATAFLOW_VM_ASSIGNED`对每次成功准备的compute尝试记录，`binding`区分INITIAL与RETRY_REUSE；包含策略/估计版本、实际目标、策略序号、紧凑已提交观测摘要和候选分数。它在现有JOB_READY之前、真实输入请求成功后记录，跨流以身份/观察时刻联结，不假定共享序号。WORKFLOW_ARRIVED可以在尚未绑定时携带VM=-1，不能回写成提前绑定。

仅保存有界于VM候选数的决策摘要，不把每次全活动网络快照塞入历史；不新增墙钟耗时字段或rerun豁免。

## CPU门控与后续验收

在线模式下STATIC对compute启用fail-fast：实际VM必须存在、Task与Job绑定相同、PE兼容，否则禁止在数据已传输后fallback到其他VM。stage-in的历史fallback仍保留。运行器的cpuStarted另行验证原请求VM与输入可见，形成第二道约束。

定向控制包含手算两条100B流共享10B/s在t=3的85B余额/5B/s速率、不可变/不推进观测、Source-vs-VM双等待、缓存/矩阵舍入/预留、忙VM I/O重叠、错峰工作流、真实NOOP重试、记录预算不影响动作与轨迹，以及不完整候选/未知负载/失败请求的拒绝。

NF005A新增67项Java测试：15项服务快照、12项数据流观测、13项配置、16项策略、7项真实Kernel及4项STATIC保护。完整Java/Javadoc **1515项、0失败/错误/跳过**通过，原覆盖率门槛不变；既有Python162项通过。冻结的13个V2及19个V3 bundle共32次重放均IDENTICAL_CORE，原五模型也再次与冻结基线比对一致。无输入重试夹具初始固定四个种子未覆盖故障，因此改用固定有限种子域直到实际触发一次重试；未修改生产故障参数或比较规则。

NF005B还需接通独立动作校验、重放和报告，不能把当前运行内核等同于完整新研究工件交付。
