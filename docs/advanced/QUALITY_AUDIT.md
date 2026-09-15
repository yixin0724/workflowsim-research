# WorkflowSim 质量审计报告

日期：2026-09-04
范围：算法正确性、指标准确性、测试覆盖、配置矩阵可运行性
方法：4 路并行深度代码审查（对照经典文献定义）+ 配置矩阵实测 + 数据集完整性校验

## 一、审计总结

| 审查领域 | 对象数量 | 结论 | 需修复项 |
|---|---|---|---|
| 静态独立任务规划器 | 7 个算法 | 全部与 Braun et al. 2001 一致 | 0 |
| 在线 ready-batch 调度器 | 8 个（含 DATA/STATIC） | 核心逻辑全部正确 | 0（3 项小瑕疵已修复） |
| 受控共享存储 DAG 规划器 | 5 个算法 | 核心与经典文献一致 | 1（多 PE 模型不一致，已修复） |
| 实验指标（SimulationMetrics） | 10 组指标 | 无会导致实验数字错误的 bug | 1（NaN/Infinity 校验缺口，已修复） |
| 数据集完整性 | 20 DAX + 42 wfformat + 180 wfinstances | INDEX 与文件一一对应 | 0 |
| 配置矩阵可运行性 | 调度器×数据模型×文件系统×故障×deadline | 全部按声明语义运行 | 0 |

## 二、本轮修复（4 项）

### 1. 多 PE 任务执行时间模型不一致（最重要）
- **问题**：规划器按串行模型估算 `(length×pes + transferMi)/mips`，而 CloudSim
  `CloudletSchedulerSpaceShared` 对多 PE Cloudlet 并行执行（进度 = capacity×pes，
  完成对 totalLength），实际墙钟时间 = `(length + stageIn)/capacity`，与 PE 数无关。
  pes=1 时两者一致；pes=k>1 时规划器高估约 `(k-1)×length/mips` 秒。
- **影响**：多 PE 任务的 rank/EFT/关键路径/SLR 下界系统性偏差；单 PE 工作流（当前全部数据集）无影响。
- **修复**：以下三处统一改用单 PE 长度 `getCloudletLength()`：
  - `SharedStorageDagPlanner.populateExecutionTimes`（影响 HEFT/CPOP/DLS/ETF/PEFT）
  - `WorkflowModelReference.fastestCompatibleDuration`（SLR 下界，串行高估会破坏"下界"性质）
  - `StaticIndependentPlanningSupport.executionTime`（影响 7 个独立任务规划器）
- **保留**：`HEFTPlanningAlgorithm`（@Deprecated 遗留类）忠实保留旧行为，不改。
  `WorkflowProfile` 的 totalLength 属工作量统计（非墙钟），不改。
- **测试更新**：`SharedStorageHeftPlanningAlgorithmTest` 断言从串行模型（rank 21.0）
  更新为并行模型（rank 11.0），注释同步修正。

### 2. 指标计算 NaN/Infinity 校验缺口
- **问题**：`SimulationMetrics.calculate` 对 makespan 与 JobOutcome 的
  start/finish/成本字段无有限性校验，NaN 会静默传播进均值、区间并集排序、成本总和。
- **修复**：入口处新增 `requireFinite()` 校验，非有限值抛 `IllegalStateException`（fail-fast）。

### 3. 在线调度器小瑕疵（行为中性加固）
- `ReadyBatchMinMinSchedulingAlgorithm`：删除永假的死代码 tie-break 条件；
  全局比较补 `bestVmForTask != -1` 守卫（消除对归纳推理的依赖）。
- `ReadyBatchMaxMinSchedulingAlgorithm`：删除同款死代码 tie-break 条件。
- `ReadyBatchRoundRobinSchedulingAlgorithm`：`run()` 开头补游标取模归一化，
  防 VM 列表规模变化时越界（当前不可达，防御性）。

### 4. 新增配置矩阵集成测试
`ConfigurationMatrixIntegrationTest`（6 测试，Failsafe 阶段运行）：
- ready-batch 调度器 × 两种数据移动模型（legacy / fixedEndpointNoContention）
- 静态独立规划器 × SHARED/LOCAL 文件系统 + SHARED_STORAGE_HEFT × SHARED
- 故障模型 + 重试预算语义（含 RetryLimitExceededException 路径）
- runtimeScale 单调性（0.5/1.0/2.0 → makespan 反比）
- deadline 观测（宽松→正 slack 零 tardiness；紧张→负 slack 正 tardiness）
- DATA 调度器数据本地性

## 三、审查确认的正确性（无需修改）

### 静态独立任务规划器（Braun et al. 2001）
OLB/MET/MCT/Min-Min/Max-Min/Sufferage/Round-Robin 全部与经典定义一致：
availability 维护、tie-breaking 完全确定（Task/VM ID 升序）、异构时间估计正确、
DAG 依赖防御完善（带依赖任务 fail-fast 拒绝）。

### 在线 ready-batch 调度器
- FCFS：严格到达顺序、无 backfill、VM ID 升序 tie-break。
- ReadyBatchMinMin/MaxMin：批内迭代重算（每周期每 VM ≤1 任务），与经典批式差异已在类注释声明。
- ReadyBatchMCT/RoundRobin：核心正确；PE 不兼容导致的 `break` 会推迟批内后续任务到下轮
 （不丢任务，仅延迟）——**行为性改进建议未应用**，改 `continue` 会改变调度结果，留待决策。
- DataAware：按非本地真实输入字节数选 VM，正确。
- Static：严格执行 planner vmId、顺序 enforcing、未规划计算 Job fail-fast，全程确定。
- 新旧命名映射（MinMin→SptFastestIdle 等 4 对）逐字节一致，@Deprecated 壳无覆写。

### DAG 规划器（受控共享存储模型，c=0）
- HEFT：upward rank / EFT / insertion gap-filling 全部符合 Topcuoglu 2002。
- CPOP：rank_u+rank_d、关键路径识别、CP 处理器绑定符合经典。
- DLS：静态层仅用 b-level + 插入式开始时间，属已文档化的可接受变体。
- ETF：最早开始时间选择正确；插入式为已文档化轻微偏差。
- PEFT：OCT 递推正确；**注意**：c=0 使 OCT 与候选 VM 无关，处理器选择退化为纯 EFT
 （与 HEFT 同），仅剩任务排序差异（rank_oct vs rank_u）。属零通信模型的必然结果，非 bug。

### 实验指标
- makespan 三值分离（simulation end / 逻辑完成 / 生命周期尾部）语义清晰。
- SLR scope 门控诚实（不适用 → UNAVAILABLE 而非错标）；下界为声明的乐观下界。
- 成本恒等式 total = cpuEnvelope + bandwidth 构造期结构性保证。
- retry 证据链强校验（缺口即抛异常）；deadline slack/tardiness 符号自洽。
- 除零保护全面（ratio/mean/CV/利用率/吞吐/SLR）。

## 四、已知语义警示（论文口径需声明）

1. **SLR 分子含引擎生命周期尾部**（simulation end 而非逻辑完成时间）→ SLR 系统性略偏高。
2. **吞吐率分子是全部 compute outcome（含失败与 retry）**，不是 goodput。
3. **MISSED_INCOMPLETE_WORKFLOW 时 slack 可为正**（工作流失败但提前结束），
   消费方必须查 outcome/met 标志而非只看 slack 符号。
4. **PEFT 在 c=0 模型下处理器选择与 HEFT 等价**，对比实验解读需注意。
5. **利用率 CV 用总体标准差（除 N）**，非样本标准差。

## 五、遗留改进建议（未应用，需决策）

| 建议 | 理由未应用 |
|---|---|
| MCT/RR 的 PE 不兼容 `break` → `continue` | 改变调度行为，影响可复现实验结果 |
| FirstFitIdle 排序前复制列表（旧壳副作用） | @Deprecated 遗留类，忠实保留旧行为 |
| SCHEDULING_CYCLE 与 PLANNING_COMPLETED 属性校验策略统一 | 低风险，涉及事件契约变更 |
| rank/OCT 递归加栈保护 | 实际 DAG 深度有限，风险极低 |
| `retryJobCreatedCount` 与 `completedRetryComputeJobOutcomeCount` 恒等声明 | 文档性改进 |

## 六、测试覆盖缺口（后续可补）

单元层未覆盖（多为防御分支）：
1. SLR 数值本身（仅集成测试断言 ≥1.0）
2. `unionLength` 边界（零长/乱序区间）
3. CV 均值=0 分支、`terminalLifecycleTail` 钳制分支
4. `modeledApproximateTaskTimingObservationCount` 非零分支
5. `logicalTaskIds` 空 sourceTasks 回退路径语义
6. 多 PE 任务的规划-执行端到端一致性（修复后建议补一个 2-PE 任务全链路测试）

## 七、验证状态

- `mvn test`：159/159 通过
- `mvn verify -pl simulator`：159 单元 + 42 集成全部通过（含新增 6 个配置矩阵测试）
- `mvn verify`：全模块 BUILD SUCCESS（71 组测试全绿，
  含 P7 基线 7 测试、示例冒烟 56+5 测试）
- 数据集校验：wfformat INDEX 42 实例 × (json+manifest) 84 引用文件全部存在

> **2026-09-11 更新**：上述计数为本审计日（2026-09-04）快照。此后新增了通信建模、
> HEFT/CPOP 论文复现（LOCAL_HEFT/LOCAL_CPOP）、PSO 复现、COMM-1 修复与千任务级规模
> 回归（Montage_1000/Epigenomics_997）等测试。
>
> **2026-09-14 更新**：R1 多 seed 统计框架落地（`PairedWilcoxonSignificance` 配对
> Wilcoxon 符号秩检验 + 端到端验收测试）；R2 链路争用带宽模型落地
> （`TransferContentionEngine` 流体公平共享 + 争用版数据移动模型 + 端到端语义验收）；
> R3 故障分布 KS 检验（8 个，含效力对照）与 JaCoCo 覆盖率棘轮门禁落地
> （simulator instruction ≥ 0.48 / branch ≥ 0.43，experiments ≥ 0.23 / ≥ 0.25）。
> 核心单元测试现为 **237 个**、核心集成 **47 个**、完整门禁 **347 个**
> （以 `mvn verify` 实际输出为准）。
