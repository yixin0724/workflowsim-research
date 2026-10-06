# V2文件生命周期证据（NF003B实施中）

当前契约已接入真实Kernel、统一运行入口、独立导出、Java/Python校验、rerun和离线报告。共享模型Kind为`COHERENT_FILE_DATAFLOW_V2`，独立路径瓶颈对照为`COHERENT_FILE_DATAFLOW_NO_CONTENTION_V2`；记录模式为`FILE_LIFECYCLE_V2`，sidecar角色为`file-lifecycle`，schema为`workflowsim-file-lifecycle-v2`。共享策略名分别为`SHARED_MAX_MIN`与`ISOLATED_PATH_BOTTLENECK`。

V2生命周期证书证明逻辑文件、实际可见来源、所计路径、同目标合并、结算观察与计算门控；它**不是逐区间流体服务/速率会计证书**。旧V1组账本及其精确服务指标、截断支持、OFF形状保持不变。首次V2导出只接受完整生命周期捕获；预算不足必须在创建/替换工件之前拒绝，而不是冒称完整。

## 运行策略

- 文件：`SCOPED_RESOLVED_WRITE_ONCE_FILES_V2`，身份为`{workflowInputIndex,name}`，保留解析后尺寸；不能恢复DAX已first-wins丢弃的声明。
- 来源：`VISIBLE_LOCAL_OR_MAX_ISOLATED_RATE_STABLE_LOCATION_V2`，选择和计费使用相同不可变来源/资源路径。
- 释放：`DEPENDENCY_READY_AT_OBSERVATION_V2`，固定目标、工作流到达、逻辑控制父任务成功后在当前时刻请求，不回溯传输。
- 可见性：`PER_FILE_SETTLEMENT_OBSERVATION_V2`，内部流体有效完成和实际观察分离，副本在观察时才可用。
- 初始运行：LOCAL、NONE聚类（每Job一个逻辑Task）、静态映射、单绑定数据中心；SOURCE明确不受汇聚容量限制且绕过fabric；输出上传属于NF004。
- 正normal有限容量和孤立估计只是第一层检查；V2服务每次重分配/推进检查正常速率、精确容量和未来时刻可表示性，错误必须原子回滚。旧构造器保留原行为。

## JSON根结构

根字段严格为`schema, modelKind, recording, certificateScope, policies, capture, filePlan, fabric, events`。

- `schema`固定`workflowsim-file-lifecycle-v2`。
- `modelKind`为上面两个Kind之一。
- `recording`严格为`{mode:"FILE_LIFECYCLE_V2",maxTraceRecords:B}`，B为1..2147483647的精确整数。
- `certificateScope`固定`FILE_LIFECYCLE_NOT_FLUID_SERVICE_ACCOUNTING_V2`。
- `policies`字段为`fileIdentity,release,visibility,selection,sourceAccess,sharing`；前四项使用下述固定策略名，sourceAccess固定`UNBOUNDED_OFF_FABRIC_SOURCE_INPUT_V2`，sharing与Kind相符。
- `capture`严格为`{status,observedThrough,retainedRecords,droppedRecords}`。完整证书要求status=COMPLETE、droppedRecords=0、retainedRecords=events长度且不超过B；observedThrough为有限非负最终观察。
- 完整指没有丢失历史，不自动代表所有流或Job完成。独立codec可验证完整的运行前缀；完整运行工件的上下文校验另外要求与最终Job/Task/主事件一致且静止。首次版本不接受TRUNCATED文档作为有效证书。

## 文件计划与实际fabric

`filePlan`保存contractVersion、tasks与files。每个Task记录taskId、workflowInputIndex、parents、inputs（fileId+referenceCount）和outputs；每个文件记录fileId、bytes、producerTaskId（external为null）。引用数不能通过展开成巨大FileItem列表来解码。

`fabric`保存类型化locations、B/s资源表、实际VM→Host映射和可选Fat-tree k/core/链路带宽/Host→pod-edge。VM端点仍是发送与接收共同使用的资源；拓扑链路区分方向。V2路径次序为源VM、链路、目标VM，与V1组路径顺序不混用。拓扑声明的安全尺寸检查必须在构造旧拓扑对象之前执行。

## 有序事件

每条事件包含`sequence`（从1连续）、`observedTime`（有限非负且单调）、`type`和严格的`payload`。事件顺序区分相同时间的不同操作。

| type | payload字段 |
|---|---|
| EXTERNAL_SEEDED | fileId, location |
| TASK_FINISHED | taskId, jobId, vmId, success |
| JOB_INPUT_REQUESTED | jobId, taskIds, destinationVmId |
| INPUT_RESOLVED | jobId, fileId, referenceCount, resolution, copyOrdinal, source |
| COPY_ADMITTED | copyOrdinal, fileId, bytes, sourceReplica, destinationVmId, resources, standaloneRate, isolatedSeconds |
| COPY_SETTLED | copyOrdinal, effectiveTime, remainingAfterService |
| JOB_DATA_READY | jobId |
| JOB_CPU_STARTED | jobId, vmId |

`remainingAfterService`是有效服务扣减后、完成容差/强制结算前的非负剩余量，不是流量。完成时允许的残余上界与所用标量积分一致：`min(bytes*0.5, max(bytes*1e-9, 4*ulp(bytes)))`；完成时间下界校验必须考虑这个数值结算和时钟运算误差，不能要求全部声明载荷都已作为真实服务面积积分。

`resolution`为LOCAL、ZERO、NEW_COPY或JOIN_EXISTING。LOCAL/ZERO的copyOrdinal为null；NEW/JOIN指向实际票据。输入未出现已发布来源时不能虚构SOURCE或记零成本成功。副本含fileId、location、visibleAt、acquisition、origin、copiedFrom及copyOrdinal。origin保留根生产Task/Job尝试、实际位置与发布观察；当前持有者不等于原生产者。

同一Job尝试的VM和完成观察必须一致，不同Task成功状态不能被整体Job状态替代（初始运行仅单Task Job）。同(file,destination)最多一个活动复制；后来目标已有副本时直接LOCAL。新副本发布只保留最早可见出处；失败不删除已到达缓存。CPU开始必须基于**预先存在**的数据就绪释放，不能靠CPU提交本身推进网络而获得许可。

## 接线与验收门禁

模式→模型/DTO/角色的统一分派、manifest上下文交叉校验、独立Python生命周期回放、rerun全字段比较和V2专用报告分支已接通。旧getNetworkEvidence/getDecodedNetworkLedger保持V1含义；新getFileLifecycle/getDecodedFileLifecycle路径互斥。V2不会在报告中被默认为V1/OFF；记录OFF、不足预算、零正复制和没有已验证结果分别处理。尚未支持的组合继续显式拒绝。

真实Kernel样例已覆盖fanin快文件早可见、同目标合并、后续缓存来源、失败原尝试/成功重试、零与本地输入、错峰工作流和粗观察间隔与有效完成分离。完整Java/Javadoc1307项、Python原41＋新54项、15个独立V2文件＋13个bundle入口、42组桌面/390px离线报告和12组反例通过；其中已包含实际Kernel缓存/合并两项；原18个V1跨语言输入也重新生成并通过。旧五模型轨迹保持IDENTICAL_CORE，旧研究不覆盖。

独立检出a58b32f也通过门禁：1307个Java用例中1305执行通过，仅2项未携带归档的可选历史用例跳过；Python95项、V1/V2合计46个跨语言入口、42组浏览器和12组反例通过。父工作树保护清单中的18641个既有文件大小与SHA256全部保持不变。

## 后续性能工作

当前事务分支复制累积Job/副本/完成历史，最坏情况具有二次工作量；不能把“无记录”理解为运行状态复制没有成本。一次性无文件、无复制、记录OFF的链式协调基准（5N状态操作、512MB堆、三次中位数）在本机观察到1000任务约0.243秒、2000任务约0.942秒。该量是实现墙钟开销，不是模拟时间或算法效果。下一步应按此基准缩小事务复制范围，再重跑完整证据等价门禁，而不改变数值/调度参数。
