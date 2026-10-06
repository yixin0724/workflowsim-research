# 受限SOURCE/共享存储数据流V3（NF004）

## 当前实现状态

NF004A接通实际运行内核后，NF004B现已接通**独立V3侧车、Java/Python上下文校验、Workbench配置/专用显示及全字段rerun**。完整捕获先通过独立语义和本次运行上下文校验，才允许工件I/O；截断捕获或不一致数据明确拒绝，不能转换成V2证书。原V1/V2完整闭环及数值语义保留。

可运行[共享容量示例](<../../experiments/configs/storage-dataflow-v3.json>)与[同路径独立瓶颈对照](<../../experiments/configs/storage-dataflow-isolated-v3.json>)。两个配置使用相同输入、VM映射、存储与可见性规则，只改变跨流共享。Workbench使用`platform.sourceStorage`声明四个显式参数，接入Host从其0起编号的已建Host中选择；Java平台API也允许专用存储Host。

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
- [StorageLifecycleEvidence](<../../simulator/src/main/java/org/workflowsim/data/v2/StorageLifecycleEvidence.java>)自身只是不可变载体；必须经[StorageLifecycleCodec](<../../simulator/src/main/java/org/workflowsim/data/v2/StorageLifecycleCodec.java>)及完整bundle上下文校验后才有认证。其共享标志表示容量共享；`storeBackedInputs`另行表示输入访问政策，二者不可混淆。

## 独立V3证据契约

角色`storage-lifecycle`、侧车`result.storage-lifecycle.json`、schema `workflowsim-storage-lifecycle-v3`，记录模式`FILE_STORAGE_LIFECYCLE_V3`。根严格为九项：`schema,modelKind,recording,certificateScope,policies,capture,filePlan,fabric,events`；`certificateScope`固定`STORAGE_LIFECYCLE_NOT_FLUID_SERVICE_ACCOUNTING_V3`。

`policies`严格为八项：

| 字段 | 固定或受控值 |
|---|---|
| fileIdentity | `SCOPED_RESOLVED_WRITE_ONCE_FILES_V2` |
| release | `DEPENDENCY_READY_AT_OBSERVATION_V2` |
| visibility | `PER_FILE_SETTLEMENT_OBSERVATION_V2` |
| selection | LOCAL使用`VISIBLE_LOCAL_OR_MAX_ISOLATED_RATE_STABLE_LOCATION_V2`，SHARED使用`COMMITTED_STORE_THEN_VM_READ_CACHE_V3` |
| sourceAccess | `BOUNDED_STORE_READ_WRITE_NIC_HOST_ATTACHMENT_V3` |
| sharing | 按Kind为`SHARED_MAX_MIN`或`ISOLATED_PATH_BOTTLENECK` |
| inputAccess | `LOCAL_VISIBLE_REPLICA_V3`或`COMMITTED_STORE_THEN_VM_READ_CACHE_V3` |
| outputCommit | `ALL_SUCCESSFUL_OUTPUTS_TO_SOURCE_V3` |

`capture`沿用完整前缀的观察/记录预算形状；首次导出只接受COMPLETE且无丢弃。`filePlan`沿用逻辑文件身份与控制计划。`fabric`严格五项：`locations,resources,vmHostAssignments,topology,sourceStorage`；存储对象含前述四个参数，资源表必须包含一致的读/写/NIC换算容量，VM放置即使没有拓扑也必须完整。

与V2相比，`COPY_ADMITTED`改为typed `destination`，并包含`purpose=INPUT|OUTPUT`、`ownerJobId`；其余文件、来源、资源、速率、独立秒数仍显式保留。新`INPUT_WAITING_FOR_STORE`记录`jobId,fileId,referenceCount`；新`OUTPUT_RESOLVED`记录`jobId,taskId,fileId,resolution,copyOrdinal,source`，处理结果为ALREADY_STORED、ZERO、JOIN_EXISTING或NEW_COPY。旧V2的destinationVmId/输入连续解析语法只属于V2，两个入口由封闭契约固定分派。

Java/Python共享各自已有的独立逻辑计划、数值、来源与路径检查机制，但不调用生产路由器/分配器/协调器。完整前缀不可截在半个输入请求、输出发布、SOURCE恢复或缺失的即时就绪操作中；前缀仍可含运行中的复制/输入/输出义务。完整bundle另要求所有逻辑任务成功、每次compute尝试被覆盖、**每个必要输出已到SOURCE**。这不是没有记录的逐区间服务面积会计证明。

请求引用字节按规范化FileId顺序进行binary64聚合，而名义输入秒数按实际解析事件顺序聚合；延迟输入可能改变两者的顺序，校验不得混用。显示中的精确引用总量则直接累计每文件binary64值的精确十进制当量，因此不伪装成舍入后的主事件聚合。

V3上下文还检查现代故障参数/VM-depth覆盖、关闭故障不能产生失败、实际及必要重试数不能超预算，以及STATIC/NOOP各次尝试不得迁移VM；这些是必要配置/因果检查，不重放随机采样。ON/OFF的核心配置都受约束，独立standalone不冒称知道外层调度/故障配置。修复哈希不能掩盖CPU、来源、目的、策略或写回历史矛盾。V2接受契约没有借此放宽或重定义。

模式、核心计划和物理存储在重建时全部保留；侧车全部字段在`/storageLifecycle/...`作精确核心比较，没有新易变量豁免。正常run与standalone报告复用同一已验证快照，独立存储面板以精确字符串展示输入、写回、等待和输出尾部；复制/资源/Job/事件预览分别最多64/64/64/128条。OFF、零正复制、完整但未结束的前缀、缺失显示数据与无有效结果分别处理。

## NF004A验收记录

本阶段新增43项Java测试：17项资源声明、9项路径/容量、10项事务生命周期、7项真实Kernel。完整Java/Javadoc **1350项、0失败/错误/跳过**通过，原覆盖率门槛不变；既有Python95项通过。冻结的13个V2完整bundle重新执行均`IDENTICAL_CORE`，原五种模型再运行也全部`IDENTICAL_CORE`。该阶段新存储版独立侧车/重放认证尚未开放，不能用当时的旧版回归代替后续V3证据验证。

测试覆盖读写与NIC瓶颈、实际Host路径、同VM提交屏障、远程读取自动恢复、同目标合并、CPU与写回重叠、unused/零输出排空、真实失败重试、OFF/完整/截断物理一致，以及输出提交后触发不可表示读取时的整体回滚。数值反例使用合法可转换容量，未放宽生产数值域。

## NF004B闭环验收

新增98项Java测试：37项独立codec、11项工件、5项重放、34项投影、8项Workbench、3项额外故障/重试反例。完整Java/Javadoc **1448项、0失败/错误/跳过**通过，覆盖率门槛不变。Python原95＋V3独立34/上下文33共 **162项**通过。新的23个standalone和19个真实V3 bundle入口均通过独立Python；连同原V1/V2共88个跨语言入口通过。

冻结13个V2 bundle与19个V3 bundle共32次实际rerun均`IDENTICAL_CORE`，原五模型另行与冻结基线比较也全部IDENTICAL_CORE。65组离线桌面/390px报告及19个验证器反例通过，覆盖第三版本切换、精确数值、读取/写回拥有者、等待和尾部、预览上限、无外部或额外file请求。

额外复核发现的关闭故障却仍有失败重试、预算不足、STATIC/NOOP重试迁移VM问题，均先以会被错误接受的Java/Python反例复现，再加入V3必要配置/结果/事件联结。修复不改变生产模拟轨迹、旧V2接受契约或精确比较白名单。

独立检出feee972同样通过：1448个Java用例中1446执行通过，仅2项未携带历史归档的可选用例明确跳过；Python162项、88个重新生成的跨语言入口、65组浏览器和19组反例通过。父工作树保护清单18641个文件、6,924,492,498字节的大小和SHA256再次逐一核对不变。

每个后续环节立即测试；保留旧五模型与V2参考证据，不改任何历史输入、排名或覆盖率门槛。
