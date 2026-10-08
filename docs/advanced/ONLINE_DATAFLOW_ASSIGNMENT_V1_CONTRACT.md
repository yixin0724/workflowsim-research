# 控制就绪在线 VM 绑定 V1

在线绑定是在逻辑 Task 的控制依赖和工作流到达门控解除后选择固定目标 VM，再按[一致 V2](<COHERENT_DATAFLOW_V2_CONTRACT.md>)或[存储 V3](<STORAGE_DATAFLOW_V3_CONTRACT.md>)准备输入。它不是新的物理 Kind，也不是 CPU 派发时重新映射；STATIC 只派发已绑定、数据就绪且可执行的 Job。

## 显式配置与认证前提

[DataflowAssignmentConfig](<../../simulator/src/main/java/org/workflowsim/data/v2/DataflowAssignmentConfig.java>)默认 OFF；`onlineNominal()` 对应普通核心配置字段 `configuration.dataflowAssignment`：

```json
{"mode":"CONTROL_READY_ONLINE_ASSIGNMENT_V1","policy":"NOMINAL_INPUT_EARLIEST_RESERVATION_V1"}
```

对象必须恰含两个大小写敏感的字符串字段，不强制转换、不裁剪空白、不猜测策略。缺省由调用者表示 OFF；显式 null、false、OFF 对象、缺字段、额外字段或未知策略均拒绝。配置不能只写入可作为身份差异处理的 `algorithmContract`。

| 配置 | 支持范围 |
|---|---|
| 规划 / 派发 | **`planningAlgorithm=INVALID`、`schedulingAlgorithm=STATIC`** |
| 物理模型 | 一致文件 V2 或受限存储 V3，含各自无共享对照 |
| 文件系统 | V2 仅 LOCAL；V3 可 LOCAL 或 SHARED |
| 任务与 CPU | NONE 聚类，每 Job 一个逻辑 Task；SPACE_SHARED；无开销 |
| 重试 | `FTCLUSTERING_NOOP`、`MONITOR_NONE`；重试保留逻辑 Task 的原目标 VM |
| 工件认证 | 匹配物理版本、模式和预算的**完整且静止** V2/V3 生命周期捕获，以及完整运行上下文 |

记录开关与绑定配置独立。原始 Java 运行可 OFF，物理和动作不因记录开关改变；但 OFF 不能导出缺少生命周期支持的在线认证工件。缺失、截断或上下文不一致在工件 I/O 前拒绝。未启用在线绑定的一致模型保留 RANDOM/STATIC 默认分支；旧执行前传输、LOCAL 规划器、普通 CPU 在线调度及 RL 的约束没有被移除。

在线 manifest 另有核心 `dataflowComputeRequests` 数组，每项恰为 `jobId,taskId,taskPes,jobPes,lengthMi`，与每次实际 Job/Task outcome 联结；默认非在线模式不添加该字段。

可运行[在线 V2 示例](<../../experiments/configs/online-file-dataflow-v1.json>)或[在线存储 V3 示例](<../../experiments/configs/online-storage-dataflow-v1.json>)。Workbench 将其作为独立决策层，不能与普通 CPU 在线调度或离线映射在同一实验中混排；独立报告标为 `DATAFLOW_BINDING_V1`，不是仅显示 STATIC。

## 被动观测与已提交状态

- [TransferServiceSnapshot](<../../simulator/src/main/java/org/workflowsim/data/TransferServiceSnapshot.java>)来自 checked 服务的 `TransferContentionEngine.snapshotState()`：复制当前容量、活动流、剩余字节、速率、路径及服务水印，不推进时间、重分配、fork、记录或调用 CloudSim。旧未检查构造器拒绝该 API。
- [DataflowObservation](<../../simulator/src/main/java/org/workflowsim/data/v2/DataflowObservation.java>)的契约为 `OBSERVED_DATAFLOW_STATE_V1`，由 `observe()` 或 `observeFiles(scope)`读取同一个已提交 Frame，不消费 ready 队列，不依赖 OFF/完整/截断捕获。状态为 `UNINITIALIZED`、`PLAN_ONLY` 或 `BOUND`；未绑定时不编造实际 VM 放置、fabric 或容量。
- 文件视图只含当前可见副本；预测完成不会发布副本。活动复制保存实际来源、类型化目标、INPUT/OUTPUT 目的、拥有者与当前服务进度。未终结 Job 分为 `WAITING_FOR_STORE`、`WAITING_FOR_INPUTS`、`READY_FOR_CPU`、`RUNNING`。
- `observeFiles`仅限定文件持有者清单；全局活动复制、资源及未终结 Job 仍可见。范围外文件查询拒绝，不能当成“已知但无副本”。不可变静态元数据可共享，可变集合复制，读取不保留快照历史。
- 资源分配速率以每条路径的重数乘各 binary64 速率，再用 BigDecimal 精确累加。无共享模式下总分配可超过容量乃至 `Double.MAX_VALUE`，不钳制；它不是已完成流量或期间利用率。

控制闭环显式先 `advance(now)` 再读快照；读 API 自身不会推进到 `CloudSim.clock`。CPU 预留也不使用返回 stub 0 或会改变内部状态的 MIPS 查询作为实际遥测。

## 名义输入与 CPU 预留策略

[DataflowVmAssigner](<../../simulator/src/main/java/org/workflowsim/data/v2/DataflowVmAssigner.java>)持有每次运行独立的逻辑绑定和预留。VM 清单必须恰好覆盖实际 fabric VM，按数值 ID 排序；非连续 ID 不是数组下标。初次绑定为所有 VM 打分，Task/Job PE 均须兼容；重试仅保留原绑定 VM 的候选，不重新选择。

```text
inputDelay = max(每个唯一逻辑输入文件的名义剩余延迟)
cpuAvailable = now + sum(该 VM 仍活动的预计剩余 CPU 预留)
scoreFinish = max(now + inputDelay, cpuAvailable) + executableComputeSeconds
```

- 计算秒数通过 `TaskExecutionModel`使用与运行相同的 Task×VM 矩阵和整数 MI 舍入。缺必需坐标或不可表示成本不回落到原始 MI；正计算量还须满足 `MI × TaskPE × 1000000` 的 signed-long 指令域。
- 已开始 CPU 的预留为 `max(0, nominalComputeSeconds - (now - observedCpuStart))`；未开始者保留完整名义计算量。按**仍存活的请求提交顺序**逐项 binary64 累加后再加 now，不按 Job ID 或 CPU 开始顺序重排。终态尝试不参与有效预留，并在下次成功绑定提交时清理；未知活动 compute 不能当作空闲。
- 可选择忙 VM，让传输与计算重叠。绑定不设置 VM BUSY；实际 STATIC 仍遵守 data-ready/idle 门控，不承诺执行预留顺序。
- 允许的本地缓存延迟为 0。已有 `(file,destination)` 输入复制用观察剩余字节除以**冻结名义瓶颈**，不重计整份引用量或更换来源。其他可见输入使用最大独立瓶颈/稳定位置来源规则。
- 多个唯一输入文件的估计取 max；这不是主事件中名义独立输入秒数之和的工作量指标。
- V3 SHARED 未见 SOURCE 提交时必须找到其活动 OUTPUT 写回，估计 `writeRemaining/writeNominalRate + postCommitRead`。目标已有副本仅免去提交后的读取，不能免去 SOURCE 门控；该项为 `conditionalStoreWait=true`，不是 SOURCE 已可见或精确到达预测。
- 缺少来源/必要写回路径、非有限成本、正时长下溢或预测时钟不能推进均拒绝，不记零成本。分数相同时取较小实际 VM ID。

这是名义路径与预留的**短视估计**，不是共享服务完整重放、真实 CPU 监控或全局最优证明；后来流量、输入等待和 data-ready 队列会影响实际完成。

## 绑定事务、重试与 CPU 门控

首次绑定要求 Task/Job VM 均为 −1。NOOP 重试创建新的 Job/Task 尝试时必须继承原逻辑 Task 绑定，只增加新 CPU 预留。

候选打分不调用 `requestJob`、不添加模拟流。完整决策形成后，仅提交一次真实输入请求；只有事务成功才提交绑定、预留、策略序号及 Task/Job VM 字段。失败请求不留下这些状态；显式观察推进与纯读取是不同步骤。

每次成功准备的 compute 尝试记录 `DATAFLOW_VM_ASSIGNED`，在真实输入请求成功后、同观察时刻的 `JOB_READY`之前出现。生命周期与主事件按身份/观察时刻联结，不共享序号。`WORKFLOW_ARRIVED`可在尚未绑定时携带 VM=−1，不回写成提前绑定。

在线 STATIC 对 compute 启用 fail-fast：实际 VM 必须存在、Task/Job 绑定相同且 PE 兼容；禁止输入已传输后 fallback 到另一 VM。stage-in 的历史 fallback 保留。运行器 `cpuStarted`另检查原请求 VM 和预先存在的数据就绪/输入可见性，CPU 提交不能自身推进网络来取得许可。

## 主动作的严格字段

主事件沿用 `sequence,simulationTime,type,jobId,vmId,classType,taskIds,attributes`；compute 的 taskIds 为单元素。主流序号从 0 开始，动作 `assignmentSequence`从 1 连续递增。

`attributes`恰含 `assignmentMode,assignmentPolicy,inputEstimateSemantics,cpuReservationSemantics,auditScope,assignmentSequence,binding,selectedVmId,observation,candidates`。固定语义标签为：

| 字段 | 值 |
|---|---|
| `assignmentMode` | `CONTROL_READY_ONLINE_ASSIGNMENT_V1` |
| `assignmentPolicy` | `NOMINAL_INPUT_EARLIEST_RESERVATION_V1` |
| `inputEstimateSemantics` | `MAX_FILE_NOMINAL_REMAINING_CONDITIONAL_STORE_V1` |
| `cpuReservationSemantics` | `ACTIVE_COMPUTE_RESERVATION_FROM_OBSERVED_START_V1` |
| `auditScope` | `BINDING_NOMINAL_BOUNDS_AND_LOGGED_SCORE_V1` |
| `binding` | `INITIAL` 或 `RETRY_REUSE` |

`observation`恰含 `contract,status,storageVersion,storeBackedInputs,interFlowSharing,observedThrough,serviceThrough,activeCopyCount,activeJobCount,pendingOutputFileCount,scopedFileCount`；动作必须为 `OBSERVED_DATAFLOW_STATE_V1`/`BOUND`。`scopedFileCount`只数本 Task 的不同输入，其余计数是全局活动状态。

每项 candidate 恰含 `vmId,compatible,inputSeconds,cpuAvailableAt,computeSeconds,scoreFinishSeconds,conditionalStoreWait,joinedInputCopies`。初次动作覆盖全部实际 VM、按数值 ID 排序；重试只列原 VM。不兼容候选的后六个值必须显式 null。只保存有界于候选数的摘要，不把每次全活动网络快照放入历史。

## 独立动作认证的范围

[Java 校验器](<../../simulator/src/main/java/org/workflowsim/data/v2/DataflowAssignmentValidator.java>)和[独立 Python 审计](<../../scripts/_dataflow_assignment_audit.py>)使用 `BINDING_NOMINAL_BOUNDS_AND_LOGGED_SCORE_V1`。先完成 V2/V3 生命周期及运行上下文检查，再单遍重建**每次 `JOB_INPUT_REQUESTED`之前**的可见副本、复制、未提交输出和活动 Job：

- 每个 compute 尝试恰有一次动作和同观察的后续 JOB_READY；初次绑定、失败父谱系、NOOP 同 VM 复用、到达与 CPU 门控一致。关闭故障不能有失败/重试，重试数受预算约束。
- 候选及 SPACE_SHARED、Task/Job PE 均受检查。矩阵恰含 `unit,runtimeConversion,entries`，其中 `unit=EXECUTION_SECONDS`、`runtimeConversion=ROUND_SECONDS_TIMES_VM_MIPS_TO_POSITIVE_INTEGER_MI`，entry 为 `taskId,vmId,executionSeconds`；允许合法额外坐标，但必须覆盖实际 Task/VM。按 binary64 乘积的精确值舍入，不能先用 double 加 0.5；正 MI 上限为 `9223372036854 / TaskPE` 的整数域。
- CPU 预留按已观察开始、活动尝试和已校验计算量重建。主 CPU 事件的原始/有效 MI 及 `MODEL_DERIVED_COMPUTE_WINDOW`必须与 Task outcome 和动作声明一致；重新计算 hash 不能消除字段矛盾。
- 新读取、本地和零字节名义量可精确重算。在途输入只认证 `(0, fullBytes/nominalRate]`；未提交 SOURCE 的上界为完整写回名义秒数加提交后读取，下界为提交后读取。后读为 0 时等待必须严格为正；后读为正时，允许很小的正写回被 binary64 加法吸收。多文件以 max 聚合边界。
- 对已记录输入分数、重建 CPU 预留和计算秒数核对严格 binary64 公式、有限/可推进时钟、最小分数与 VM ID 平局规则。数字必须为真正 JSON 数值，整数按 int32/int64 域精确读取；数值 token 长度、十进制精度及绝对 scale 均不超过 4096，不把非零下溢为零接受。

**不认证精确活动流余额或完整未来争用过程。** 动作没有记录全部服务 epoch，名义边界内的进度改写可能仍满足有限认证；实际 rerun 仍逐字段比较并可返回 `DIVERGED`。原始配置、计算请求和主动作的科学字段没有新增易变量豁免；实际输出/CPU 因果由生命周期证书承担。

## 报告与复核

报告只读取同一个已验证快照。动作预览最多 **64** 条，每条候选最多 **12** 条；选中项即使不在预览内，也从完整候选集合提取。数值为精确文本，缺失投影、未配置、无有效结果分别处理；物理 V2/V3 面板同时保留，不重新读取侧车。

参见[重放契约](<../experiments/RERUN_DIFF_CONTRACT.md>)、[Workbench](<../getting-started/WORKBENCH.md>)、[能力矩阵](<DATAFLOW_CAPABILITY_MATRIX.md>)与[构建检查](<../getting-started/BUILD.md>)。
