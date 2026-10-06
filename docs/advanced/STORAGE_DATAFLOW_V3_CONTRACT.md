# 受限SOURCE/共享存储数据流V3（NF004）

## 当前实现状态

NF004A已接通Java运行内核、不可变内存捕获和定向测试；**独立V3侧车codec、Java/Python上下文校验、Workbench配置/显示及rerun尚待后续步骤接线**。在这些环节完成前，工件与独立manifest写出入口在任何I/O前明确拒绝存储V3，不把其数据丢弃或转换成V2证书。原V1/V2完整闭环仍可使用。

新模型为`COHERENT_STORAGE_DATAFLOW_V3`和`COHERENT_STORAGE_DATAFLOW_NO_CONTENTION_V3`，通过`DataMovementModel.coherentStorageDataflowV3()`或`coherentStorageDataflowNoContentionV3()`构建。捕获模式为`FILE_STORAGE_LIFECYCLE_V3`，`NetworkEvidenceConfig.storageLifecycleV3(B)`启用；OFF不保留事件历史。不得复用V2的Kind、模式、旧getter或无限SOURCE声明。

初始执行矩阵仍为RANDOM映射、STATIC派发、NONE聚类（每Job一个Task）、无开销、受控NOOP CPU重试；LOCAL与SHARED有下述明确不同的输入规则。在线/RL分配尚属NF005，不通过简单移除约束开放。

## 一份显式存储资源声明

[DataflowStorageSpec](<../../simulator/src/main/java/org/workflowsim/data/v2/DataflowStorageSpec.java>)提供一个固定逻辑位置`SOURCE:source`，通过`PlatformProfile.Builder.sourceStorage(spec)`声明：

- `attachmentHostId`：必须属于平台；拓扑开启时该Host必须已连接。
- `readBandwidthMbPerSecond`：存储读取服务容量。
- `writeBandwidthMbPerSecond`：存储写入服务容量。
- `networkBandwidthMbPerSecond`：读写共同使用的存储NIC容量。

均为十进制MB/s，乘1,000,000后必须是正normal有限binary64 B/s。不做整数舍入、不因未知资源而视为无限。原`PlatformProfile.StorageSpec`及旧Harddrive估时含义不变；新资源与非存储模型组合会在会话创建前拒绝。

当前存储空间仍无限，不含容量耗尽、淘汰、存储故障、复制一致性或网络丢包重传。有限的是读、写和NIC服务资源，不能据此声称真实磁盘或云存储校准。

## 资源路径

[存储fabric](<../../simulator/src/main/java/org/workflowsim/data/v2/DataTransferFabric.java>)冻结实际VM→Host与存储接入Host：

| 操作 | 有序约束资源 |
|---|---|
| SOURCE→VM读取 | `STORE:source:READ`、`STORE:source:NIC`、实际接入路径有向链路、目标`VM:id` |
| VM→SOURCE写回 | 源`VM:id`、实际接入路径有向链路、`STORE:source:NIC`、`STORE:source:WRITE` |
| LOCAL策略的VM→VM读取 | 源VM、实际有向链路、目标VM |

存储与VM同Host时可以没有fabric链路，但仍消耗VM端点与存储服务/NIC，不得当作本地文件零成本。无拓扑时仍计存储与VM资源。所有正复制沿用已验证的受限max-min或独立路径瓶颈服务；反向链路各自计量，NIC双向共享。无共享对照仅关闭跨流容量共享，不移除单流读写/NIC/路径瓶颈。

## 成功输出的必要写回与完成

本版本固定采用**全部成功Task输出持久化到SOURCE**的输出策略：包括未被下游消费的sink输出。它是本模型的必要输出集，不是按照图中出现的输入边猜测网络流量。

- CPU完成先经故障判断；失败Task不发布输出，不产生写回义务。
- 成功输出先在实际执行VM观察可见，随后在同一事务中开始或加入(file,SOURCE)写回；已有SOURCE对象保持最早出处。
- 正字节写回共享服务资源；零字节输出只提交元数据，标记`ZERO_BYTE_OUTPUT`，不生成正流或复制序号。
- CPU的`CLOUDLET_RETURN`、任务/Job完成时刻不被改成存储提交时刻。VM可以在写回期间运行下一作业。
- 仿真终止要求已请求Job终态、没有活动复制、没有SOURCE等待者，且所有成功输出的SOURCE义务已经履行。仅“活动流为零”不足以证明完整输出。
- CPU逻辑完成指标与整体仿真结束/存储尾部不同；本版本不虚构I/O失败重试或TCP确认。

## 输入可见性与两类等待

### LOCAL

沿用可见目标缓存优先、否则最大独立瓶颈速率/稳定位置打破并列的来源规则。必要写回仍发生，但下游可以在SOURCE尚未提交时读取已发布的VM副本。

### SHARED：提交后读穿透缓存

明确策略为`COMMITTED_STORE_THEN_VM_READ_CACHE_V3`，不是旧V1 SHARED估时：

1. 输入请求仍在控制依赖完成的当前观察发出，固定目标VM。
2. 若对应SOURCE对象未提交，记录`INPUT_WAITING_FOR_STORE`；**即使目标VM正是生产者、已有本地输出，也必须等待SOURCE提交**。
3. SOURCE提交仅使该文件允许重新解析：已有目标VM缓存则LOCAL，否则只从SOURCE读取，不能选其他更快peer绕过共享存储规则。
4. 读取到达目标后才使该输入满足；所有目标输入满足后发出一次数据就绪，才能进入CPU。

等待SOURCE与等待目标复制是不同索引。SOURCE提交在同一暂存事务内，按确定顺序恢复受影响的(job,file)，不能重入公共requestJob/advance，也不通过调度器忙轮询。多个同目标读者可以加入同一次在途读取。所有新增等待者、准备统计、输出义务、路径、复制编号和事件都随失败一起回滚。

## 观察与指标

- 新存储版`DATA_STAGE_IN_MODELED`在最终数据就绪时记录，因为延迟解析前的初始统计不完整。
- `modeledTransferSeconds`继续表示**名义独立输入传输秒数之和**，不改写既有指标含义；`requiredFileBytes`、引用数、新建/加入输入复制数仍分别计数。
- 新属性`observedInputPreparationSeconds = dataReady - request`单独表示真实观察准备时长；不能把重叠的逐文件SOURCE等待简单相加冒充墙钟分解。
- 输出写回不塞进CPU或输入stage-in信封。输入/输出目的、复制拥有者和typed destination在新内存事件中明确分开。
- 新[StorageLifecycleEvidence](<../../simulator/src/main/java/org/workflowsim/data/v2/StorageLifecycleEvidence.java>)仅为不可变捕获载体，目前还不是独立认证。其共享标志表示容量共享；`storeBackedInputs`另行表示输入访问政策，二者不可混淆。

## 下一步证据契约

计划采用独立`storage-lifecycle`角色与V3 schema，复用已有独立文件计划、数值、来源和路径验证机制，但由封闭契约描述选择严格的V2/V3字段和事件语法：V2入口必须固定V2；V3允许带purpose/ownerJobId/typed destination的输入或输出复制、延迟输入解析和成功输出义务。完整运行校验必须证明每个必要输出已到SOURCE，且仍区分生命周期证明与没有记录的逐区间服务面积会计。不能仅修复哈希就接受不一致的CPU、来源、目的或写回历史。

## NF004A验收记录

本阶段新增43项Java测试：17项资源声明、9项路径/容量、10项事务生命周期、7项真实Kernel。完整Java/Javadoc **1350项、0失败/错误/跳过**通过，原覆盖率门槛不变；既有Python95项通过。冻结的13个V2完整bundle重新执行均`IDENTICAL_CORE`，原五种模型再运行也全部`IDENTICAL_CORE`。新存储版的独立侧车/重放认证仍未开放，不能用这些旧版回归代替新证据验证。

测试覆盖读写与NIC瓶颈、实际Host路径、同VM提交屏障、远程读取自动恢复、同目标合并、CPU与写回重叠、unused/零输出排空、真实失败重试、OFF/完整/截断物理一致，以及输出提交后触发不可表示读取时的整体回滚。数值反例使用合法可转换容量，未放宽生产数值域。

每个后续环节立即测试；保留旧五模型与V2参考证据，不改任何历史输入、排名或覆盖率门槛。
