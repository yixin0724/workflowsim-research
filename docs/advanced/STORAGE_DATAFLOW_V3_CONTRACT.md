# 受限 SOURCE 与共享存储数据流 V3

V3 为一致文件数据流增加有限读/写/NIC 资源、实际存储接入路径和必要输出写回。运行、独立侧车、Java/Python 校验、配置重建、精确 rerun 与离线报告使用同一版本契约；不能把 V3 证书转换或贴标为 V2。

## 模型与配置

| 项目 | 支持值 |
|---|---|
| 共享模型 | `COHERENT_STORAGE_DATAFLOW_V3`；`DataMovementModel.coherentStorageDataflowV3()` |
| 无共享对照 | `COHERENT_STORAGE_DATAFLOW_NO_CONTENTION_V3`；`coherentStorageDataflowNoContentionV3()` |
| 默认目标绑定 | RANDOM 规划、STATIC 派发 |
| 可选在线绑定 | 显式 `CONTROL_READY_ONLINE_ASSIGNMENT_V1`、INVALID 规划、STATIC 派发；见[在线绑定契约](<ONLINE_DATAFLOW_ASSIGNMENT_V1_CONTRACT.md>) |
| 执行 | NONE 聚类，每 Job 一个逻辑 Task；SPACE_SHARED；无开销；受控 NOOP CPU 重试 |
| 文件系统 | LOCAL，或下文定义的 SHARED 提交后缓存读取 |
| 捕获 | `FILE_STORAGE_LIFECYCLE_V3`；`NetworkEvidenceConfig.storageLifecycleV3(B)`，`B` 为 `1..2147483647` 的记录预算 |

OFF 不保留事件历史，也不改变运行物理。普通非在线运行仍保存核心文件计划；在线认证另要求匹配、完整的生命周期捕获。CPU 在线调度器、RL_POLICY 和原 LOCAL 规划器不能直接替代这里的目标绑定规则。

可运行[共享容量示例](<../../experiments/configs/storage-dataflow-v3.json>)与[独立路径瓶颈对照](<../../experiments/configs/storage-dataflow-isolated-v3.json>)。它们保持输入、绑定配置、存储与可见性规则，仅改变跨流容量共享；不能据此保证整个 DAG 的后续来源选择或完成时间相同。

## 显式存储资源

[DataflowStorageSpec](<../../simulator/src/main/java/org/workflowsim/data/v2/DataflowStorageSpec.java>)固定逻辑位置 `SOURCE:source`，由 `PlatformProfile.Builder.sourceStorage(spec)` 声明。Workbench 对应 `platform.sourceStorage`，必须恰含四项：

| 字段 | 含义与约束 |
|---|---|
| `attachmentHostId` | 非负实际 Host ID，必须属于平台；有拓扑时该 Host 必须已连接 |
| `readBandwidthMbPerSecond` | 读取服务容量 |
| `writeBandwidthMbPerSecond` | 写入服务容量 |
| `networkBandwidthMbPerSecond` | 读写共同使用的存储 NIC 容量 |

三个带宽均为有限正数，单位是十进制 MB/s；按 binary64 乘 `1000000` 后必须为正 normal、有限 B/s，不做整数舍入。三者没有必须相等或大小排序的要求。未知资源不是无限资源。

Workbench 的接入 Host 从其已建的 `0..VM数−1` Host 中选择；Java 平台 API 可声明专用存储 Host。Java/codec 的 Fat-tree `k` 为偶数 `2..32`，Workbench 为偶数 `2..16`；配置还须满足对应 Host/edge 容量约束。

`sourceStorage` 必须且只能与存储 V3 模型一起出现，错误组合在会话创建前拒绝。旧 `PlatformProfile.StorageSpec`、Harddrive 估时和[一致 V2](<COHERENT_DATAFLOW_V2_CONTRACT.md>)的无限、绕过 fabric 的 SOURCE 含义均不改变。有限的是服务速率，**不是存储空间**；当前没有容量耗尽、淘汰、多存储一致性、存储故障、丢包重传或真实硬件校准。

## 实际资源路径

[DataTransferFabric](<../../simulator/src/main/java/org/workflowsim/data/v2/DataTransferFabric.java>)冻结实际 VM→Host 与存储接入 Host。以下次序及资源重数属于契约：

| 操作 | 有序约束资源 |
|---|---|
| SOURCE→VM 读取 | `STORE:source:READ`、`STORE:source:NIC`、实际路径有向链路、目标 `VM:id` |
| VM→SOURCE 写回 | 源 `VM:id`、实际路径有向链路、`STORE:source:NIC`、`STORE:source:WRITE` |
| LOCAL 的 VM→VM 读取 | 源 VM、实际路径有向链路、目标 VM |

同 Host 可以没有 fabric 链路，但 SOURCE 读写仍消耗 VM 端点及存储服务/NIC，不能视为本地零成本。无拓扑也保留存储/VM 资源和完整实际 VM→Host 映射。单流瓶颈为各资源容量除以路径重数后的最小值；共享模式采用受检查的 max-min 服务，无共享对照只取消跨流共享，不移除读写/NIC/路径瓶颈。反向链路分别计量，存储 NIC 双向共享。

## 成功输出、异步写回与终止

`ALL_SUCCESSFUL_OUTPUTS_TO_SOURCE_V3` 要求**全部成功 Task 输出**到 SOURCE，包括 unused/sink 和零字节输出，不能仅从被消费的输入边推导必要输出集。

- CPU 完成先经故障判断；失败 Task 不发布输出，也不产生写回义务。
- 成功输出先在实际执行 VM 观察可见，再于同一事务开始或加入 `(file,SOURCE)` 写回。已有 SOURCE 副本保留最早出处。
- 正字节输出经过服务资源；零字节输出提交元数据，副本获取方式为 `ZERO_BYTE_OUTPUT`，不生成正流或复制序号。
- `CLOUDLET_RETURN` 和 Task/Job 完成时刻仍是 CPU 结果，不改成存储提交时刻。VM 可在输出写回期间执行下一作业。
- 终止要求已请求 Job 均终态、无活动复制、无 SOURCE 等待者，且每个成功输出的 SOURCE 义务已履行。仅“活动流为零”不足以证明完成。
- 逻辑计算完成、整体仿真结束及输出尾部是不同时间边界，不虚构 I/O 失败重试或 TCP 确认。

## 输入可见性与等待

**LOCAL** 使用可见目标缓存优先，否则最大独立瓶颈速率、稳定位置打破并列的来源规则。必要输出仍写回，但下游可以在 SOURCE 提交前读取已发布的 VM 副本。

**SHARED** 使用 `COMMITTED_STORE_THEN_VM_READ_CACHE_V3`，不是旧 V1 的 SHARED 估时：

1. 控制依赖满足后，在当前观察发起固定目标 VM 的输入请求。
2. SOURCE 未提交时记录 `INPUT_WAITING_FOR_STORE`；即使目标就是生产者且已有本地输出，也必须等待提交。
3. SOURCE 提交后重新解析该文件：目标缓存已存在则 LOCAL，否则只从 SOURCE 读取，不能选更快 peer 绕过存储。
4. 到达目标后该输入才满足；全部输入满足时只释放一次 `JOB_DATA_READY`，随后才可进入 CPU。

等待 SOURCE 与等待目标复制是不同索引。SOURCE 提交在同一暂存事务内按确定的受影响请求顺序恢复 `(job,file)`，不重入公共 `requestJob/advance`，也不靠忙轮询。多个同目标读者可加入同一次在途读取。等待者、准备统计、输出义务、路径、复制序号与事件均随失败事务回滚。

## 主事件、指标与聚合顺序

`DATA_STAGE_IN_MODELED` 在**最终数据就绪**时记录，`transferUnit=LOGICAL_FILE_STORAGE_V3`；延迟解析之前的初始统计不完整。

- `modeledTransferSeconds` 仍为名义独立输入传输秒数之和，不是并行输入墙钟、输出时间或服务面积。`NEW_COPY` 和 `JOIN_EXISTING` 各计其票据的完整 `isolatedSeconds`，不是剩余时间。
- `requiredFileBytes`、`modeledTransferFileCount`、`newFileCopies`、`joinedFileCopies` 分别记录引用字节、引用次数、新建/加入输入复制数。输出复制不混入输入计数，也不加入 V1 的 `contentionTransferGroupCount`。
- `observedInputPreparationSeconds = dataReady - request` 是另行记录的观察准备时长；重叠 SOURCE 等待不能逐文件相加冒充墙钟分解。
- **引用字节按规范化 FileId 顺序聚合**：每文件的 binary64 字节值先精确乘引用数，再转为 binary64，按序累加；每 Job 总引用数不得超过 `2147483647`，聚合字节必须有限。
- **名义输入秒数按实际 `INPUT_RESOLVED` 事件顺序累加**；SHARED 延迟恢复可能改变这个顺序，不能换成引用字节的排序。
- 显示的精确引用总量直接累计每文件 binary64 值的精确十进制当量，不伪装成舍入后的主事件聚合。输出时间不塞进 CPU 或输入 stage-in 信封。

## 独立 V3 证据格式

[StorageLifecycleCodec](<../../simulator/src/main/java/org/workflowsim/data/v2/StorageLifecycleCodec.java>)固定 role `storage-lifecycle`、文件名 `<runId>.storage-lifecycle.json`（例如 `result.storage-lifecycle.json`）、schema `workflowsim-storage-lifecycle-v3`、记录模式 `FILE_STORAGE_LIFECYCLE_V3`。role 属于外层 artifact 清单，不是侧车根字段。

根必须恰含 `schema,modelKind,recording,certificateScope,policies,capture,filePlan,fabric,events`；`certificateScope=STORAGE_LIFECYCLE_NOT_FLUID_SERVICE_ACCOUNTING_V3`。`policies` 必须恰含八项：

| 字段 | 值 |
|---|---|
| `fileIdentity` | `SCOPED_RESOLVED_WRITE_ONCE_FILES_V2` |
| `release` | `DEPENDENCY_READY_AT_OBSERVATION_V2` |
| `visibility` | `PER_FILE_SETTLEMENT_OBSERVATION_V2` |
| `selection` | LOCAL：`VISIBLE_LOCAL_OR_MAX_ISOLATED_RATE_STABLE_LOCATION_V2`；SHARED：`COMMITTED_STORE_THEN_VM_READ_CACHE_V3` |
| `sourceAccess` | `BOUNDED_STORE_READ_WRITE_NIC_HOST_ATTACHMENT_V3` |
| `sharing` | 与 Kind 对应的 `SHARED_MAX_MIN` 或 `ISOLATED_PATH_BOTTLENECK` |
| `inputAccess` | `LOCAL_VISIBLE_REPLICA_V3` 或 `COMMITTED_STORE_THEN_VM_READ_CACHE_V3` |
| `outputCommit` | `ALL_SUCCESSFUL_OUTPUTS_TO_SOURCE_V3` |

`recording={mode,maxTraceRecords}`、`capture={status,observedThrough,retainedRecords,droppedRecords}`；导出要求 `COMPLETE`、零丢弃、事件数等于 retainedRecords 且不超过预算。完整表示未丢历史，不等于运行已结束。

`filePlan` 保留共同的逻辑文件/控制计划。`fabric` **恰为五项**：`locations,resources,vmHostAssignments,topology,sourceStorage`；存储对象恰含前述四参数，资源表必须含换算一致的 READ/WRITE/NIC，实际 VM 放置即使没有拓扑也必须完整。共享标志表示跨流容量共享，`storeBackedInputs`/`inputAccess` 表示访问政策，不能混同。

公共事件包络为 `sequence,observedTime,type,payload`，序号从 1 连续，观察时刻单调且不超过捕获水印。相对[文件 V2 格式](<FILE_LIFECYCLE_V2_FORMAT.md>)，V3 的独立语法差异如下：

| 事件 | V3 payload 字段 |
|---|---|
| `COPY_ADMITTED` | `copyOrdinal,fileId,bytes,sourceReplica,destination,purpose,ownerJobId,resources,standaloneRate,isolatedSeconds` |
| `INPUT_WAITING_FOR_STORE` | `jobId,fileId,referenceCount` |
| `OUTPUT_RESOLVED` | `jobId,taskId,fileId,resolution,copyOrdinal,source` |

`destination` 为类型化位置，`purpose=INPUT|OUTPUT`，`ownerJobId` 必须对应实际请求或成功输出拥有者。输出 resolution 为 `ALREADY_STORED`、`ZERO`、`NEW_COPY` 或 `JOIN_EXISTING`；前两者 copyOrdinal 为 null。输出加入旧票据时，该事件的 source 是本次成功完成的 VM，票据仍保留原拥有者和最早来源。不能把 V2 的 `destinationVmId` 准入字段或连续输入解析语法套入 V3。

共同的严格 JSON/数值、零与小数字节、不可表示时钟、复制残余上界及出处检查见[文件格式的数值契约](<FILE_LIFECYCLE_V2_FORMAT.md#数值与结算边界>)；封闭的 V2/V3 入口分别选择语法，输入文档不能自行扩展契约。

## 校验、重放与显示边界

[StorageLifecycleEvidence](<../../simulator/src/main/java/org/workflowsim/data/v2/StorageLifecycleEvidence.java>)只是不可变载体。Java 和[独立 Python 校验](<../../scripts/_storage_lifecycle_audit.py>)不调用生产路由器、分配器或协调器来生成预期；完整捕获也不能结束在半个请求/输出发布/SOURCE 恢复/遗漏即时就绪操作中。合法 standalone 前缀仍可含活动复制、输入等待或输出义务。

完整 bundle 则额外要求所有逻辑 Task 成功、覆盖每次 compute 尝试、与 Job/Task/主事件和实际平台相符、状态静止且每个必要输出已到 SOURCE。V3 上下文核查现代故障参数及 VM/depth 覆盖、关闭故障不能产生失败、实际/必要重试不得超预算、STATIC/NOOP 重试不得迁移 VM；ON/OFF 核心配置都受约束。这些是必要配置与因果检查，不重放随机抽样，standalone 也不冒称知道外层调度/故障配置。

截断、缺失或矛盾证据在工件 I/O 前拒绝；重新计算 hash 不能掩盖来源、目的、CPU、策略或写回矛盾。**认证不包括未记录的逐区间流体服务面积。** V2 的接受契约不因此改变。

重建保留模式、核心计划和物理存储；侧车全字段在 `/storageLifecycle/...` 精确比较，没有新易变量豁免。正常与独立报告复用同一已验证快照，使用 `getStorageLifecycle()/getDecodedStorageLifecycle()` 的独立路径，不复用 V1/V2 getter。显示数值为精确文本；复制/资源/Job/事件预览分别最多 **64/64/64/128** 条，嵌套列表最多 12 项、标签最多 256 字符。OFF、零正复制、完整运行前缀、缺失投影和无有效结果分别处理，不请求额外侧车。

参见[重放契约](<../experiments/RERUN_DIFF_CONTRACT.md>)、[Workbench](<../getting-started/WORKBENCH.md>)、[能力矩阵](<DATAFLOW_CAPABILITY_MATRIX.md>)及[构建与检查](<../getting-started/BUILD.md>)。
