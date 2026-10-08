# V2 文件生命周期证据格式

V2 证书描述逻辑文件、实际可见来源、有序资源路径、同目标合并、结算观察及 CPU 输入因果。它**不是逐区间流体服务/速率面积会计证书**，也不能把旧 V1 组级账本贴标为逐文件证明。

## 版本、记录与运行范围

| 项目 | 值 |
|---|---|
| 共享 Kind | `COHERENT_FILE_DATAFLOW_V2` |
| 无共享 Kind | `COHERENT_FILE_DATAFLOW_NO_CONTENTION_V2` |
| 记录模式 / Java 配置 | `FILE_LIFECYCLE_V2` / `NetworkEvidenceConfig.fileLifecycleV2(B)` |
| artifact role | `file-lifecycle`，属于外层 artifact 清单，不是侧车根字段 |
| 文件名 | `<runId>.file-lifecycle.json` |
| schema | `workflowsim-file-lifecycle-v2` |
| certificateScope | `FILE_LIFECYCLE_NOT_FLUID_SERVICE_ACCOUNTING_V2` |

运行使用 LOCAL、NONE 聚类（每 Job 一个逻辑 Task）、SPACE_SHARED、无开销、单绑定数据中心和受控 NOOP 重试。默认 RANDOM 映射＋STATIC 派发；显式在线绑定使用 INVALID＋STATIC 和[在线策略契约](<ONLINE_DATAFLOW_ASSIGNMENT_V1_CONTRACT.md>)。工作流到达/控制依赖满足且目标固定后，在当前观察请求输入，不回溯传输。

V2 的 SOURCE 明确无限汇聚且绕过 fabric；必要输出上传与有限存储资源属于独立的[存储 V3](<STORAGE_DATAFLOW_V3_CONTRACT.md>)，不能写入本版证书。运行物理和来源规则见[一致 V2 契约](<COHERENT_DATAFLOW_V2_CONTRACT.md>)。

导出只接受完整捕获。预算不足、截断或上下文矛盾在创建/替换工件之前拒绝，不改变模拟物理。普通非在线模式 OFF 仍保留核心 `dataflowPlan`，不生成生命周期侧车；在线认证不能仅凭 OFF 运行导出。原 V1 组账本的指标、截断支持及 OFF 形状不受此格式改变。

## JSON 根与策略

[FileLifecycleCodec](<../../simulator/src/main/java/org/workflowsim/data/v2/FileLifecycleCodec.java>)要求根恰含九项：

`schema,modelKind,recording,certificateScope,policies,capture,filePlan,fabric,events`

- `schema`、`certificateScope`固定为上表值；`modelKind`只能取上表两个 Kind。
- `recording`恰为 `mode,maxTraceRecords`；mode 为 `FILE_LIFECYCLE_V2`，预算 B 是 `1..2147483647` 的精确整数，单位为记录数，不是字节数。
- `capture`恰为 `status,observedThrough,retainedRecords,droppedRecords`。有效证书要求 `status=COMPLETE`、`droppedRecords=0`、retainedRecords 等于 events 长度且不超过 B；observedThrough 为有限非负观察水印。
- COMPLETE 表示没有丢失历史，不代表流或 Job 已结束。standalone codec 可接受合法的完整运行前缀；完整 bundle 另要求与最终运行一致且静止。`TRUNCATED`不是有效 V2 证书。

`policies`恰含以下六项：

| 字段 | 固定/受控值 |
|---|---|
| `fileIdentity` | `SCOPED_RESOLVED_WRITE_ONCE_FILES_V2` |
| `release` | `DEPENDENCY_READY_AT_OBSERVATION_V2` |
| `visibility` | `PER_FILE_SETTLEMENT_OBSERVATION_V2` |
| `selection` | `VISIBLE_LOCAL_OR_MAX_ISOLATED_RATE_STABLE_LOCATION_V2` |
| `sourceAccess` | `UNBOUNDED_OFF_FABRIC_SOURCE_INPUT_V2` |
| `sharing` | 与 Kind 一致的 `SHARED_MAX_MIN` 或 `ISOLATED_PATH_BOTTLENECK` |

严格读取拒绝重复键、额外/缺失字段、错误类型及尾随 JSON 内容；需要的 nullable 字段必须出现并显式为 null，不能省略。JSON 解析深度上限为 64；数值 token 长度、十进制精度及绝对 scale 均不超过 4096。整数按对应 int32/int64 域精确读取，不做字符串/浮点强制取整。

## 逻辑文件计划与实际 fabric

`filePlan`恰含 `contractVersion,tasks,files`；contractVersion 为 `SCOPED_RESOLVED_WRITE_ONCE_FILES_V2`。

| 对象 | 字段 |
|---|---|
| Task | `taskId,workflowInputIndex,parents,inputs,outputs` |
| inputs 中的引用 | `fileId,referenceCount` |
| 文件 | `fileId,bytes,producerTaskId`；external 的 producerTaskId 为 null |
| FileId | `workflowInputIndex,name`；name 是非空、已有解析键，不拆分重命名 |

文件尺寸保留解析后的有限非负 binary64 字节，冲突比较不使用 epsilon；计划不能恢复 DAX first-wins 已丢弃的声明。零字节仍是引用。引用次数为正精确整数，不能展开成巨大的 FileItem 列表解码；单 Job 总引用数不得超过 `2147483647`，引用字节聚合必须有限。

`fabric`恰含 `locations,resources,vmHostAssignments,topology`：

- 类型化位置恰为 `kind,vmId,sourceId`。VM 使用非负 vmId 和 null sourceId；唯一 SOURCE 使用 `kind=SOURCE,vmId=null,sourceId=source`。
- 资源行是 `key,capacityBytesPerSecond`，容量为正 normal、有限 binary64 B/s；资源集合必须精确匹配 VM 端点及声明链路，不把缺失键当作无限容量。
- 无拓扑时 `topology=null` 且 V2 的 vmHostAssignments 为空；有拓扑时每项为 `vmId,hostId`，必须完整覆盖实际 VM。
- 拓扑恰含 `kind,k,coreSwitchCount,linkBandwidthBytesPerSecond,hostPlacements`，kind 为 `FAT_TREE`。Java/codec 要求偶数 `k=2..32`、core 数 `1..k²/4`、Host 数 `1..k³/4`，每 edge 不超过 `k/2` 个 Host；placement 为 `hostId,pod,edge`，pod 小于 k、edge 小于 k/2。安全尺寸检查先于构造拓扑对象。Workbench 另限偶数 `k=2..16`。

VM 端点由发送/接收共同使用，有向拓扑链路区分方向。V2 VM 间路径次序为源 VM、链路、目标 VM，保留资源重数，不混用 V1 组路径顺序；SOURCE 读取只占用目标端点，不经过 fabric。V2 fabric 不允许 `sourceStorage`。

## 有序事件与副本出处

事件恰含 `sequence,observedTime,type,payload`。sequence 从 1 连续；observedTime 有限、非负、单调且不超过捕获水印。同一时刻仍以事件序号区分操作。

| type | payload 字段 |
|---|---|
| `EXTERNAL_SEEDED` | `fileId,location` |
| `TASK_FINISHED` | `taskId,jobId,vmId,success` |
| `JOB_INPUT_REQUESTED` | `jobId,taskIds,destinationVmId` |
| `INPUT_RESOLVED` | `jobId,fileId,referenceCount,resolution,copyOrdinal,source` |
| `COPY_ADMITTED` | `copyOrdinal,fileId,bytes,sourceReplica,destinationVmId,resources,standaloneRate,isolatedSeconds` |
| `COPY_SETTLED` | `copyOrdinal,effectiveTime,remainingAfterService` |
| `JOB_DATA_READY` | `jobId` |
| `JOB_CPU_STARTED` | `jobId,vmId` |

外部文件必须在时间 0、运行事件之前各自恰好 SOURCE seeded 一次。请求 taskIds 为单元素，不能重复 Job 尝试 ID；所有控制父 Task 必须已有成功逻辑结果。

`resolution`为 `LOCAL`、`ZERO`、`NEW_COPY` 或 `JOIN_EXISTING`。LOCAL/ZERO 的 copyOrdinal 为 null；NEW/JOIN 指向实际票据，正复制编号从 1 连续。没有已发布的合法来源时，不能虚构 SOURCE 或以零成本成功。已有本地副本优先；同 `(file,destination)`同时最多一个活动复制，JOIN 保留票据原来源与路径，不另起传输。

副本恰含 `fileId,location,visibleAt,acquisition,origin,copiedFrom,copyOrdinal`；origin 为 `producerTaskId,jobAttemptId,location,observedAt`，保留根生产者及尝试、实际发布位置与观察时刻。获取方式为 `EXTERNAL_SEED`、`TASK_OUTPUT`、`COPY_SETTLEMENT` 或 `ZERO_BYTE_REFERENCE`；持有者不等于原生产者。后续发布保留最早可见出处，失败不删除已到达缓存；Task 成功状态必须与其尝试证据核对，不能由无关 Job 状态替代。

CPU 开始须基于**已记录的数据就绪**、全部输入可见及固定 VM，不能靠 CPU 提交推进网络后追认许可。完整前缀不能截在半个输入请求/复制准入或遗漏应即时发出的 JOB_DATA_READY 中。

## 数值与结算边界

- 文件字节可为 0 或可表示的小数，不截断为 long。非零 JSON 数转换为 binary64 后下溢成 0 会被拒绝；负数、NaN/Infinity 和非有限聚合不接受。
- 物理容量和 standaloneRate 至少为 `Double.MIN_NORMAL`。独立正传输秒数 `bytes / standaloneRate`必须为有限正数，准入时刻加该时长必须有限且严格推进时钟；不能把下溢当零传输或改选来源掩盖错误。
- 单流路径检查不是共享求解器证明。运行服务另检查正常分配速率、精确资源容量、有限服务面积和未来时刻，失败事务原子回滚；旧未检查构造器不因此获得新保证。
- COPY_SETTLED 要求 `release < effectiveTime <= observedTime`且 observedTime 严格晚于 release。副本只在 observedTime 可见，不能用内部完成预测提前发布。

`remainingAfterService`为服务扣减后、容差/强制结算前的非负残余，不是流量。标量 binary64 规则为：

```text
residualLimit = min(bytes * 0.5, max(bytes * 1e-9, 4 * ulp(bytes)))
lower = release + ((bytes - remainingAfterService) / standaloneRate)
allowed = min(8 * max(ulp(release), ulp(effectiveTime), ulp(lower)),
              1e-12 * max(release, effectiveTime, lower))
```

残余不得超过 residualLimit；lower 必须有限，并且不晚于 effectiveTime，或差值不超过 allowed。这个必要下界考虑结算残余与时钟舍入，**不要求全部声明载荷已作为服务面积积分，也不独立重放共享分配**。

## 工件上下文、重放与显示

模式、模型、DTO、role 和 getter 由固定版本分派。`getNetworkEvidence()/getDecodedNetworkLedger()`保持 V1 含义，V2 使用 `getFileLifecycle()/getDecodedFileLifecycle()`，V3 使用独立存储路径；不能把缺失 V2 证书默认为 V1/OFF。

[完整工件校验](<../../simulator/src/main/java/org/workflowsim/experiment/FileLifecycleContextValidator.java>)另外核对核心计划、输入归属/到达、实际平台、每个 compute 尝试的请求/就绪/CPU/完成、Task/Job 窗口和重试谱系；要求所有逻辑 Task 成功且已请求状态静止。主 `DATA_STAGE_IN_MODELED`的 `transferUnit=LOGICAL_FILE_V2`，引用量及新建/加入复制数与生命周期一致；`modeledTransferSeconds`是名义输入秒数之和，不是实际等待。普通 OFF 计划可被单独检查，但不能据此宣称完整生命周期认证。

Java codec、[Python 生命周期检查](<../../scripts/_file_lifecycle_audit.py>)与[Python 上下文检查](<../../scripts/_file_lifecycle_context.py>)独立执行各自明确范围，不调用生产协调器/分配器生成预期。重新计算 hash 不能掩盖 CPU 因果、出处、路径或配置矛盾。

侧车全字段在 `/fileLifecycle/...`进入精确 rerun 比较，无新增易变量豁免。报告复用同一个已验证快照，以精确文本显示数值；复制/资源/事件预览上限为 **64/64/128**，嵌套列表 12 项、标签 256 字符。OFF、预算不足、零正复制、合法运行前缀及无已验证结果分别处理，不读取额外侧车。

参见[重放契约](<../experiments/RERUN_DIFF_CONTRACT.md>)、[Workbench](<../getting-started/WORKBENCH.md>)、[能力矩阵](<DATAFLOW_CAPABILITY_MATRIX.md>)、[状态复制性能](<DATAFLOW_STATE_COPY_PERFORMANCE.md>)及[构建检查](<../getting-started/BUILD.md>)。
