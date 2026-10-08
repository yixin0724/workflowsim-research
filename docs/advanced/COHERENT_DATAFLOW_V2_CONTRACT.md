# 一致文件数据流V2

## 适用模型与配置

`COHERENT_FILE_DATAFLOW_V2`与`COHERENT_FILE_DATAFLOW_NO_CONTENTION_V2`统一管理逐文件需求、真实来源、资源路径、副本可见性和CPU输入屏障。两者具有相同生命周期与单流瓶颈，区别仅为是否在并发流之间共享容量。

| 配置项 | 支持范围 |
|---|---|
| 文件系统 | LOCAL |
| 默认目标绑定 | RANDOM规划＋STATIC派发 |
| 可选目标绑定 | 显式`CONTROL_READY_ONLINE_ASSIGNMENT_V1`＋INVALID规划＋STATIC派发 |
| 任务与执行 | NONE聚类，每Job一个逻辑Task；SPACE_SHARED；无开销；受控NOOP重试 |
| 网络 | 端点，或实际VM→Host放置上的Fat-tree路径 |
| SOURCE | 显式无限汇聚、绕过fabric；不是受限存储设备 |

Java的Fat-tree配置限定偶数`k=2..32`，Workbench限定`k=2..16`。所有组合与数值支持域在配置/运行边界检查。受限SOURCE和必要输出写回使用[存储V3](<STORAGE_DATAFLOW_V3_CONTRACT.md>)，不能偷偷改变V2含义。在线绑定的额外约束见[在线绑定契约](<ONLINE_DATAFLOW_ASSIGNMENT_V1_CONTRACT.md>)。

## 逻辑文件计划

[DataflowFilePlan](<../../simulator/src/main/java/org/workflowsim/data/v2/DataflowFilePlan.java>)的契约标记为`SCOPED_RESOLVED_WRITE_ONCE_FILES_V2`。

- 文件身份是`(workflowInputIndex, existingResolvedFileKey)`，不是FileItem对象、Job尝试或持有副本的VM。保留解析器已形成的键，不通过拆分文件名重新猜测工作流归属。
- 每个文件至多有一个逻辑Task生产者；生产者必须是消费者的控制祖先，不限直接父节点。同Task原地读写和多生产者歧义会拒绝。
- 尺寸使用解析结果保留下来的有限非负binary64字节值；相同身份的保留描述必须一致，不设置epsilon。零字节仍是一项输入引用，不能视为无输入。
- 这是**解析后文件计划**，不是原始声明检查器。若DAX解析器已经按first-wins规则合并描述，计划不能恢复被丢弃的原始尺寸差异，也不声称它们已被校验。
- 计划在聚类清空解析Task列表前冻结逻辑ID、工作流归属、依赖、文件元数据和引用计数，不保留可变Task/FileItem。
- `demandsFor(taskSubset)`按逻辑文件去重，同时保留引用次数和消费者集合；选择内生产者形成的输入属于内部需求。重试按原逻辑Task再次取需求，不扫描历史父Job输出。
- 精确引用总量可用BigDecimal表达，但不等于唯一传输载荷；运行器每Job的binary64聚合与引用计数仍受其有限支持域约束，溢出不会默认为零。
- 图和生产者祖先验证采用迭代遍历，不构造全对祖先矩阵。纯计划API允许空集合，不代表运行入口允许空工作流或任意聚类方式。

## 副本与出处

[DataReplicaState](<../../simulator/src/main/java/org/workflowsim/data/v2/DataReplicaState.java>)使用[类型化位置](<../../simulator/src/main/java/org/workflowsim/data/v2/DataLocation.java>)，独立于旧ReplicaCatalog。

- VM和SOURCE是不同身份类型，未知位置不能被默认当作有效持有者。
- 副本保存最早可见时刻、获取方式、根生产Task/Job尝试/位置/观察时刻，以及直接复制来源与票据号。后续重复发布保留首个副本身份和出处。
- 同一Job尝试必须对应一个实际VM和完成观察。失败Task不发布输出，但失败不删除已经到达的输入；冲突回调在状态改变前拒绝。
- 活动复制票据绑定文件、所选可见源副本、目标和准入时刻。它不是目标副本已经可见的承诺，也不是网络服务正确性的独立证明。
- 同文件、同目标同时至多一项活动复制。重叠请求先查询并加入已有票据，不重新选源或重复传输；不同目标仍分别准入。
- 正复制在严格晚于准入的观察时刻结算。零字节仍需有效可见来源，但可立即解析，不消耗正复制编号。
- 快照和读取不推进时钟。非法操作不得改变观察水印、可见副本、完成计数或复制编号；合法失败结果本身仍是一项推进水印的观察。

## 来源与路径

[DataTransferFabric](<../../simulator/src/main/java/org/workflowsim/data/v2/DataTransferFabric.java>)与[ReplicaTransferSelector](<../../simulator/src/main/java/org/workflowsim/data/v2/ReplicaTransferSelector.java>)产生同一份不可变来源/路径/独立速率决策，供估时、准入和证据使用。

1. 当前可见本地副本优先；否则选择独立路径瓶颈速率最大的可见源，按稳定位置顺序打破平局。没有来源不是零成本成功。
2. VM间路径按源端点、实际拓扑链路、目标端点计费；不同VM即使同Host也消耗两个端点。真正同VM的本地引用不需要网络服务。
3. V2外部SOURCE不经过fabric且无汇聚容量；缺失物理链路/端点容量与明确无限的SOURCE是不同状态。V2不提供向受限SOURCE写回的语义。
4. 容量必须为正normal有限binary64值；正传输的独立秒数、绝对完成时刻和后继观察必须可表示并推进时钟。不把下溢当成零传输，也不因数值问题悄悄改选更慢来源。
5. 单流路径检查不是共享求解器证书。运行器另外检查活动分配、有限服务面积、结算残余和未来时刻，失败通过事务暂存回滚。

Fat-tree结构、确定性路由和共享机制见[拓扑说明](<../research/FAT_TREE_DESIGN.md>)。没有自适应路由或基于瞬时队列的源选择。

## 生命周期与计算门控

- **释放：**`DEPENDENCY_READY_AT_OBSERVATION_V2`。工作流已到达、控制依赖满足且目标VM固定后，在当前观察发起输入需求；不回溯父任务较早的完成时刻，不做producer-eager预取。
- **可见性：**`PER_FILE_SETTLEMENT_OBSERVATION_V2`。每个文件在内核处理其结算时可见，保留内部有效完成与外部观察时刻的区别；不从可选trace推导运行状态。
- **合并：**同逻辑文件/目标的重叠输入共用一次复制，等待者与引用计数分别保留。
- **CPU屏障：**全部所需文件在固定目标可见且控制依赖满足后才提交CPU。成功输出发布在实际完成观察，位置取实际Job VM，不读取预测Task完成时刻。
- **无共享对照：**取消跨流容量共享，不取消单流端点/链路瓶颈。服务时刻变化可能改变后续缓存和源选择；不能据此保证完整DAG路径历史固定或完成时间单调。

## 证据与复核

V2使用独立`FILE_LIFECYCLE_V2`侧车。它检查逐文件身份、出处、请求、复制、可见性及CPU因果，**不认证未记录的逐区间流体服务会计**。V1组级账本不能重新贴标成为逐文件证明。

完整工件导出需要完整捕获；预算不足会在工件I/O前拒绝，但不改变物理执行。默认非在线绑定模式的记录OFF仍保留核心文件计划，不意味着零网络。详见[生命周期格式](<FILE_LIFECYCLE_V2_FORMAT.md>)、[重放契约](<../experiments/RERUN_DIFF_CONTRACT.md>)及[能力矩阵](<DATAFLOW_CAPABILITY_MATRIX.md>)。
