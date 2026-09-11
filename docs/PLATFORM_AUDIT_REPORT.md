# WorkflowSim 平台整体审查报告

**审查日期**：2026-09-04
**审查方式**：三路并行深度审查（调度层 / 指标体系 / 引擎与事件流）+ 主线交叉验证
**审查范围**：311 个 Java 源文件，重点覆盖 WorkflowScheduler、19 个调度算法、WorkflowEngine、WorkflowDatacenter、SimulationMetrics、四个输出通道

---

## 一、已完成工作（等待时间 + 真实减速比指标）的评估

**结论：✅ 全部合格，无需返工**

| 检查项 | 结果 |
|--------|------|
| 指标计算正确性（累加器/分母逐一核对） | ✅ 全部匹配，无分母错配 |
| mean()/ratio() 除零保护 | ✅ 齐备 |
| bounded slowdown 公式与 Javadoc/测试一致性 | ✅ 一致 |
| JOB_READY/SCHEDULING_DECISION 事件采集完整性 | ✅ 每作业恰一次（含 stage-in 与 retry） |
| retry 重复计入检查 | ✅ 无重复（jobOutcomesById 对重复 ID 抛异常） |
| 旧 API 残留（全项目 grep） | ✅ 无残留 |
| 四输出通道（控制台/CSV/JSON/HTML）一致性 | ✅ 列名与指标同步 |
| 单元测试 | ✅ 169 个全部通过 |
| 实测验证 | ✅ 数值与理论预期完全匹配 |

**审查中对我们工作的两个改进建议**（非缺陷，属增强）：
1. jobs.csv 缺 `readyTime` / `totalWaitingTime` 列——新核心指标无法从 CSV 复算，需 join events.jsonl（低优先级）
2. HTML 卡片在无观测时显示 0.0000（与"下界 1.0"语义冲突），建议与控制台一样做门控（低优先级）

---

## 二、平台自身问题清单（按优先级排序）

### 🔴 P0 - 高严重性（建议尽快修复）

#### PLAT-1. ✅已修复  Legacy SPT/LJF/FastestVm 用"总 MIPS"选 VM，作业固定 1 PE —— 多 PE 异构环境系统性选错 VM
- **证据**：`FastestVmSchedulingAlgorithm.java:69`、`SptFastestIdleSchedulingAlgorithm.java:99`、`LjfFastestIdleSchedulingAlgorithm.java:100` 用 `getCurrentRequestedTotalMips()`（= mips × PE 数）；而 `ReadyBatchMCTSchedulingAlgorithm.java:37` 用 `vm.getMips()`（正确）。
- **后果**：2PE@500MIPS（总 1000）胜过 1PE@800MIPS（总 800），但作业实际只以 500 MIPS 运行。同一仓库两套"最快 VM"度量，跨算法对比结论失真。
- **缓解**：三个 legacy 类已 @Deprecated；标准研究入口应拒绝这些标签（Parameters.java:44-51）。
- **修复**：统一改为 `vm.getMips()`，或文档标注"legacy 结果在多 PE 配置下不可比"。**工作量小**。

#### PLAT-2. ✅已修复  忙碌 VM 提交路径 → 任务时间窗锚定 0.0 → 时间倒挂 + 错误故障归因（静默）
- **证据**：忙碌 VM 时 cloudletSubmit 返回 0.0、QUEUED 且不设 execStartTime（CloudletSchedulerSpaceShared.java:363-378）；WorkflowDatacenter.updateTaskExecTime（:293）在提交时刻固化窗口 ≈[0,…]；FailureGenerator（:116-124）用该窗口判失效 → 错误归因。仅打印警告，未 fail-fast。
- **触发条件**：标准 SimulationRunner 路径（IDLE 门控）难触发；legacy 静态派发/同周期多次派发可触发。
- **修复**：updateTaskExecTime 断言 execStartTime ≈ 当前 clock，或 estimatedFinishTime==0.0 时 fail-fast。**工作量小**。

#### PLAT-3. ✅已修复  引擎首次释放依赖 t=0 事件 FIFO 顺序 —— 顺序被打破则静默空跑
- **证据**：processJobSubmit 只替换 jobsList 不触发 submitJobs（WorkflowEngine.java:282-285）；若调度器的 CLOUDLET_SUBMIT 先于 JOB_SUBMIT 被处理，submitJobs 空跑后无人再触发释放 → 工作流完全不执行、CloudSim 静默终止、报告只有 INCOMPLETE 标记不抛异常。
- **修复**：processJobSubmit 末尾幂等调用 submitJobs()；或报告捕获时断言引擎终止条件。**工作量小**。

### 🟡 P1 - 中严重性（建议排期修复）

#### PLAT-4. ✅已修复  metrics.json 破坏性契约变更但 schema 版本未升级
- 字段重命名（waitingTime→vmQueueWaitingTime 等）直接改了 Gson 反射序列化的字段名，schema 仍为 `workflowsim-simulation-metrics-v1`；validator 只校验 schema 字符串不校验字段名 → 按旧字段名解析的下游会静默缺列。
- **修复**：升 v2 + validator 校验关键字段存在性。**工作量小，收益大**。

#### PLAT-5. ✅已修复  NaN 校验缺口：submissionTime/cpuTime 未校验
- `Math.max(0.0, NaN)` 返回 NaN，会经 waitingTime/responseTime 静默污染三个均值指标。
- **修复**：校验循环补两行 `requireFinite(...)`。**工作量极小**。

#### PLAT-6. ✅已修复  FirstFitIdle 就地排序 broker 真实就绪列表 —— 跨调度周期状态泄漏
- `BaseSchedulingAlgorithm.setCloudletList` 不做防御性复制（setVmList 有），FirstFitIdle 的 `Collections.sort(getCloudletList())` 直接改写 broker 队列，排序残留破坏 FCFS 到达序。
- **修复**：setCloudletList 防御性复制。**工作量小**。

#### PLAT-7. ✅已修复  DataAware 在 SHARED 文件系统模式下退化为 first-fit-idle（无告警）
- 局部性判断用 vmId 匹配，但 SHARED 模式副本只按数据中心名注册 → 所有 VM 得分相同，恒选最小 ID 空闲 VM。
- **修复**：SHARED 模式下显式告警或抛错。**工作量小**。

#### PLAT-8. ✅已修复  meanJobResponseTimeSeconds 的 Javadoc 语义误导
- "从提交到完成"实为"从 VM 到达（submissionTime）到完成"，不含调度器队列等待；与"平均总等待时间"并列展示会误导读者。
- **修复**：改 Javadoc + 控制台行标注口径。**纯文档**。

#### PLAT-9. ✅已修复  优先级字段存在但完全未被调度层消费
- Task.priority 存在，ClusteringEngine 统一置 0，19 个算法无一读取 → 误导性字段。
- **修复**：删除字段或文档明确"调度层不消费优先级"。

#### PLAT-10. campaign 层未聚合新增核心调度指标
- CellSummary 只聚合 makespan/cost/completionRate —— meanComputeTrueSlowdown 无法在批量实验中做跨算法统计比较（含置信区间）。
- **修复**：CellSummary 增加新指标的 MetricSummary。

### 🟢 P2 - 低严重性 / 建模限制说明（按需处理）

| 编号 | 问题 | 说明 |
|------|------|------|
| PLAT-11 ✅ | "1 VM = 1 slot" 建模限制 | 刻意设计（多处 javadoc 声明），VM 队列等待恒 0、多 PE VM 只用 1 PE。**建议写入实验文档**，避免误读 |
| PLAT-12 | SCHEDULING_CYCLE 空转噪声 | 每作业返回必触发调度周期，多数 readyJobCount=0，扭曲周期类指标 |
| PLAT-13 | retry Task 复用原 task ID，下游事件无 attempt 标识 | 事件流中不同 jobId 下重复同组 taskIds |
| PLAT-14 | 无停滞看门狗 | jobsList 非空且无就绪作业时静默停滞 |
| PLAT-15 | StaticSchedulingAlgorithm fallback 改写与计划校验冲突 | 错误信息指向表象而非根因 |
| PLAT-16 | readyTimes/decisionTimes 重复事件静默覆盖 | 当前无触发路径，属演进风险 |
| PLAT-17 ✅ | 等待/减速比均值含失败与 retry 尝试，Javadoc 未声明口径 | 跨失败率方案比较时均值有偏 |
| PLAT-18 | 依赖门控 O(n²) 扫描 | 大工作流性能隐患 |
| PLAT-19 | JOB_DISPATCHED 与 SCHEDULING_DECISION 冗余双记录 | 存储浪费 |
| PLAT-20 | processCloudletSubmitHasShown 死代码 | 置位后无任何效果 |

### 📈 指标体系增强建议（非缺陷）

1. Jain's 公平性指数（VM 利用率）——与 CV 互补，CV 无法区分"两台各 50%"与"一台 100% 一台 0%"
2. 等待时间/减速比的**分布统计**：中位数、p95、最大值（现仅均值；调度算法差异常体现在尾部）
3. success-only 等待/减速比变体（剔除失败尝试）
4. 重试放大率（attemptCount/taskCount）
5. 单位成功逻辑任务成本

---

## 三、总体结论

1. **我们完成的指标工作质量合格**：计算正确、事件采集完整、无重复计入、四通道一致、测试覆盖充分。
2. **平台核心路径健康**：单线程事件驱动无竞态、fail-fast 异常处理、就绪 AND 门控正确、返回闭环无死锁。
3. **三个高优先级问题都是"边界路径静默出错"**：共同特点是错误不抛异常而是静默产生错误数据——这与平台的 fail-fast 风格不一致，建议统一补防御断言。
4. **P1 中多数问题工作量小**（一两行到几十行），可以一次性批量修复。

---

## 四、建议修复顺序

**第一批（小工作量、高收益）**：
- PLAT-4（schema v2）
- PLAT-5（NaN 校验两行）
- PLAT-1（legacy MIPS 统一）
- PLAT-2/PLAT-3（fail-fast 断言）

**第二批（语义与文档）**：
- PLAT-8（responseTime Javadoc）
- PLAT-11（建模限制写入文档）
- PLAT-17（口径声明）

**第三批（功能扩展）**：
- PLAT-6/PLAT-7（算法修复）
- PLAT-10（campaign 聚合）
- 分布统计指标（中位数/p95/最大值）

---

## 五、修复记录（2026-09-10）

### 第一批（高优先级缺陷，全部完成）

| 编号 | 修复内容 | 关键文件 | 回归测试 |
|------|----------|----------|----------|
| PLAT-4 ✅ | schema 升 `workflowsim-simulation-metrics-v2`；validator 拒绝旧 schema 并校验 6 个关键字段存在性 | ExperimentArtifactWriter / ExperimentArtifactValidator | `rejectsOutdatedMetricsSchemaAndMissingRequiredMetricFields` |
| PLAT-5 ✅ | 校验循环补充 `requireFinite(submissionTime)` 与 `requireFinite(cpuTime)` | SimulationMetrics | 现有 NaN 测试覆盖 |
| PLAT-1 ✅ | FastestVm / SptFastestIdle / LjfFastestIdle 统一改用单 PE `getMips()` 选 VM；Javadoc 说明度量口径 | 3 个 legacy 算法 + MCTSchedulingAlgorithm javadoc | `FastestVmMipsSelectionTest`（4 个用例，含 busy VM 契约） |
| PLAT-2 ✅ | 忙碌 VM 提交从静默警告改为 fail-fast：`estimatedFinishTime <= 0` 或非有限时抛 IllegalStateException（在 updateTaskExecTime 之前拦截，避免时间窗锚定 0.0） | WorkflowDatacenter.processCloudletSubmit | 标准路径不受影响（169→179 测试全绿） |
| PLAT-3 ✅ | 引擎 `processJobSubmit` 末尾幂等调用 `submitJobs()`，不再依赖 t=0 事件 FIFO 顺序；配套调度器侧守卫：VM 未全部创建时跳过调度周期（processCloudletUpdate），VM 全部创建后若有早到就绪作业补发 CLOUDLET_UPDATE 冲刷 | WorkflowEngine / WorkflowScheduler | 全部集成测试隐式覆盖新路径 |

### 第二批（算法与语义修复，全部完成）

| 编号 | 修复内容 | 关键文件 | 回归测试 |
|------|----------|----------|----------|
| PLAT-6 ✅ | `setCloudletList` 防御性复制（与 `setVmList` 一致），算法内排序不再泄漏回调度器就绪队列 | BaseSchedulingAlgorithm | `SchedulingListIsolationTest`（2 个用例） |
| PLAT-7 ✅ | 双层防护：SimulationConfig 校验拒绝 DATA + SHARED 组合（fail-fast）；算法内 SHARED 模式一次性告警（直接 API 兜底）；4 处现有测试用法同步显式指定 LOCAL | SimulationConfig / DataAwareSchedulingAlgorithm | `SimulationConfigDataAwareValidationTest`（3 个用例） |
| PLAT-8 ✅ | responseTime Javadoc 明确"VM 到达→完成"口径；控制台行与 HTML 卡片标注同步 | SimulationMetrics / ExperimentConsoleSummary / ExperimentHtmlReportWriter | — |
| PLAT-9 ✅ | Task.priority 字段 Javadoc 明确"内置调度算法均不读取，聚类层统一置 0，仅供研究型扩展" | Task | — |
| PLAT-11/17 ✅ | 建模限制（1 VM=1 槽位、多 PE 只用 1 PE）与指标口径（含失败/retry 尝试、有界减速比钳制、responseTime 起点）写入实验文档 | CODE_CONFIG_EXPERIMENTS.md 新增"平台建模限制与指标口径（必读）"章节 | — |

### 验证结果

- **单元测试**：179 个全部通过（修复前 169 个 + 新增 10 个回归测试）
- **端到端实验**：FCFS/Montage_25 输出与修复前完全一致（makespan 94.086s、totalWaiting 1.92508s、trueSlowdown 1.1562），证明标准路径行为无变化
- **metrics.json**：schema 已为 `workflowsim-simulation-metrics-v2`

### 第三批（引擎/调度器健壮性 + 指标体系增强，全部完成）

| 编号 | 修复内容 | 关键文件 | 回归测试 |
|------|----------|----------|----------|
| PLAT-16 ✅ | SimulationMetrics 对重复 JOB_READY / SCHEDULING_DECISION 事件 fail-fast（IllegalStateException），事件流破坏不再静默吞掉 | SimulationMetrics | 现有事件测试覆盖正常路径 |
| PLAT-18 ✅ | 依赖门控 O(n²) 线性扫描改为 `receivedJobIds` HashSet O(1) 查找；`setJobsReceivedList` 同步重建索引 | WorkflowEngine | 集成测试隐式覆盖 |
| PLAT-13 ✅ | retry 的 JOB_READY 事件新增 `retryOfFailedJobId` 属性，证据链可追溯重试谱系 | WorkflowEngine | — |
| PLAT-14 ✅ | 静默停滞看门狗：仿真结束后断言引擎无未释放/在途作业，否则抛 SimulationExecutionException（deadline 未作为终止条件，看门狗不会误报） | SimulationRunner + WorkflowEngine.getInFlightJobCount | 全量集成测试通过即证明无误报 |
| PLAT-12 ✅ | 消除空转调度周期：作业返回与空批次提交不再无条件触发 CLOUDLET_UPDATE；就绪队列非空才触发（后续到达自带触发，无停滞风险） | WorkflowScheduler.processJobReturn / processCloudletSubmit | 185 测试全绿 + 端到端数字不变 |
| PLAT-20 + L3 ✅ | 删除死代码标记 `processCloudletSubmitHasShown`；`ev.getData()` null/空批次防御 | WorkflowScheduler | — |
| L2（RR 游标）✅ | 已验证非缺陷：`getSchedulingPolicy()` 缓存 `activeSchedulingPolicy`，仅配置类型变更时重建（刻意设计） | WorkflowScheduler | — |
| PLAT-15 ✅ | fallback VM 改写与强制顺序计划校验区分根因：改写过的作业计划校验失败时错误信息指明"缺少规划器映射"而非表象 | StaticSchedulingAlgorithm | — |
| PLAT-19 ✅ | 决策：SCHEDULING_DECISION（指标消费）与 JOB_DISPATCHED（证据链审计）分工保留不合并，enum Javadoc 明确职责 | SimulationEventType | — |
| 指标增强 ✅ | 新增 12 个指标：等待/减速比 中位数/P95/最大值（最近秩法）、success-only 等待/减速比均值+观测数、总等待观测数、VM 利用率 Jain 公平性指数、重试放大率；Gson 序列化自动进入 metrics.json | SimulationMetrics | `JobWaitingTimeMetricsTest` 新增 6 个用例（分布统计/空观测/success-only/Jain/放大率×2） |
| 指标 L1 ✅ | HTML 无观测显示 "—" 而非 0.0000；jobs.csv 新增 readyTime/totalWaitingTime 列（无观测留空） | ExperimentHtmlReportWriter / ExperimentCsvWriter | 端到端工件核对 |
| 指标 L2 ✅ | `totalWaitingTimeObservationCount` 显式暴露（与 trueSlowdown 观测同集合） | SimulationMetrics | — |
| PLAT-10 ✅ | campaign CellSummary 聚合新增 4 个核心维度：等待时间/真实减速比/VM 利用率/Jain 指数（MetricSummary 均值+置信区间） | ExperimentCampaignSummary | 现有 campaign 测试通过 |
| 展示层 ✅ | 控制台新增：等待分布行、减速比分布行、success-only 行（仅口径有差异时）、Jain 指数行、重试放大率行 | ExperimentConsoleSummary | — |

### 第三批验证结果

- **单元测试**：185 个全部通过（第二批 179 个 + 新增 6 个指标用例）
- **端到端实验**：FCFS/Montage_25 参考数字精确不变——makespan **94.086s**、totalWaiting **1.92508s**、trueSlowdown **1.156203682854177**（n=25）、vmQueueWaiting 0.0、vmLevelSlowdown 1.0、利用率 66.64%
- **新指标合理性**：中位数等待 0.0s / P95 11.701s / 最大 13.641s（分布统计成功暴露均值 1.93s 掩盖的尾部）；减速比中位数 1.0 / P95 1.9757；Jain 指数 0.9207；无失败运行 success-only == 总体、放大率 1.0
- **工件核对**：metrics.json 含全部新字段；jobs.csv 表头含 readyTime/totalWaitingTime

### 未修复项（待后续排期）

- ~~PLAT-10、PLAT-12～16、PLAT-18～20、指标体系增强~~ —— **第三批已全部完成**。
- 截至本记录，审计发现的全部缺陷（PLAT-1～PLAT-20）与调度器/指标评审项（L1/L2/L3、M4/M5）均已修复、验证或明确记录为刻意设计。

## 六、复核轮（2026-09-10）

**方法**：两轮独立双重审计（指标口径逐公式复核 + 算法核心思想对照教科书/原始论文），外加 18+ 项数值交叉验证（从 jobs.csv/metrics.json 手工重算并与平台输出逐位比对）。

**结论**：指标体系无公式级缺陷；17 个受支持算法均忠实于其核心思想。修复项 R1～R11：

| 编号 | 修复 | 位置 |
| --- | --- | --- |
| R1 | campaign 聚合排除 trueSlowdown 无观测的运行（此前把 0 计入均值）；新增 `trueSlowdownObservationRunCount` | ExperimentCampaignSummary |
| R2 | 控制台 deadline 块三分支：满足→Slack / 未完成→判定依据说明 / 超时→Tardiness | ExperimentConsoleSummary |
| R3 | jobs.csv readyTime 留空语义注释补全（含 classType==2 过滤提示） | ExperimentCsvWriter |
| R4 | `declaredFileBytes` 纳入 requireFinite 校验 | SimulationMetrics |
| R5 | 新增 `vmLevelSlowdownObservationCount` 显式暴露 | SimulationMetrics |
| R6 | retryAmplificationRatio Javadoc 软化（无逻辑任务时 0.0 属无含义分支） | SimulationMetrics |
| R7 | HTML Gantt makespan=0 除零守卫 | ExperimentHtmlReportWriter |
| R9 | HTML 作业表头"提交时间"→"到达时间(VM)"（语义准确） | ExperimentHtmlReportWriter |
| R10 | runTime/VmMetrics 样本口径 Javadoc（含失败与 retry 尝试） | SimulationMetrics |
| R11 | 文档漂移修正（88 指标计数、控制台真实示例、jobs.csv 字段表） | CODE_CONFIG_EXPERIMENTS |
| R8 | DHEFT 已知限制 Javadoc（VM ID/列表下标混用，隔离不修复）；遗留三件套平局说明 | DHEFTPlanningAlgorithm 等 |

**刻意设计清单**（非缺陷，已文档化）：idle-only 派发、作业固定 1 PE、bounded slowdown 10s 钳制、submissionTime=VM 到达、vmQueueWaiting≈0、样本含失败/retry 尝试、最近秩百分位、Jain 空集→1.0、deadline 仅观测、无逻辑任务时放大率 0.0。

## 七、论文复现轮（2026-09-10）

**目标**：内部评审已穷尽，改用外部 ground truth——实现一篇基于 WorkflowSim 的开源调度算法论文并复现其实验，以暴露更多问题。

**选定论文**：Pandey, Wu, Guru, Buyya, *"A Particle Swarm Optimization-Based Heuristic for Scheduling Workflow Applications in Cloud Computing Environments"*, AINA 2010；参考开源实现 `meysamhit/workflowsim-pso`（自带 Montage_25 配置）。

**实现**：`PSOPlanningAlgorithm` + `PsoParticle` + `PsoFitnessFunction`（种群 30、迭代 100、w=0.7、c1=c2=1.5、fitness=0.8·成本+0.2·makespan、price=mips/1000，忠实移植）；注册进 Parameters 枚举、WorkflowPlanner 工厂、AlgorithmCatalog（含 manifest 契约）；新增 `PlatformProfiles.heterogeneousLocal`；专用入口 `PsoReproductionExperiment`；单测 5 个（手算适应度对账、收敛到全局最优、确定性、映射有效性、空输入）。全量测试 **190/190 绿**。

**复现结果**（Montage_25，异构 VM [500,800,1000,1200] MIPS，全部 26/0 成功）：

| 配置 | Makespan | 论文成本 |
| --- | --- | --- |
| PSO+STATIC | 129.75 | 247.20 |
| RANDOM+STATIC（3 种子均值） | 190.71 | 246.29 |
| SHARED_STORAGE_HEFT+STATIC | 96.14 | 251.66 |

**复现轮发现的问题**：

| 编号 | 发现 | 处置 |
| --- | --- | --- |
| REPRO-1 ✅ | `MyConfigurableExperiment` 完全忽略命令行参数——此前传入的"FCFS"实际运行的是硬编码 READY_BATCH_MINMIN（参考数字 94.086 属 READY_BATCH_MINMIN；真 FCFS makespan=91.46，此前从未产出过）。可复现性陷阱 | 已修复：显式解析 [调度] [规划] 两参 + AlgorithmCatalog 准入校验 + fail-fast；READY_BATCH_MINMIN 参考数字精确不变 |
| REPRO-2 📋 | 参考实现计价 price=mips/1000 使成本项 = ΣMI/1000 与映射无关（常数）——PSO 的成本维度退化，论文"PSO 降成本"结论在该成本模型下结构性不可复现（论文用真实 EC2 非线性定价）。复现实测 PSO 成本与 RANDOM 持平（+0.37%），makespan −32% | 已文档化（PsoFitnessFunction Javadoc + manifest 限制声明）；如需复现成本结论须引入非线性价格表 |
| REPRO-3 📋 | 平台 STATIC_* 规划族按设计拒绝含依赖 DAG——论文的 RR 基线无法直接使用，复现用 RANDOM+STATIC 多种子代替朴素基线 | 已文档化（PsoReproductionExperiment Javadoc） |
| REPRO-4 📋 | 参考实现 PSO+ROUNDROBIN 调度组合自相矛盾（RR 调度会覆盖规划映射）——本平台 SimulationConfig 强制规划算法搭配 STATIC 派发，属平台加固优于原始 WorkflowSim | 已文档化（PSOPlanningAlgorithm Javadoc 差异声明） |
| REPRO-5 ✅ | AlgorithmSupportMatrixTest 防漂移守卫按预期拦截了未分类的新枚举 PSO——设计正确性得到实战验证 | 已将 PSO 纳入 supported 分区 |

**方向性结论核对**：论文声称 PSO 优于朴素映射——本复现在当前模型下证实 makespan 维度（−32%），成本维度因 REPRO-2 结构性原因不可复现；依赖感知的 HEFT 仍显著优于 PSO（96.14 vs 129.75），与"PSO 适应度忽略依赖边"的已知限制一致。PSO 端到端确定性（两次运行逐位一致）已验证。


## 八、通信建模轮（2026-09-11）：LOCAL 传输模型 + HEFT/CPOP 论文复现

**目标**（用户指令）：不考虑 DVFS，做通信相关工作流调度（启发式/元启发式），并用开源真实论文做验证，找出模拟器的问题。

### 8.1 通信建模能力

- **任务×VM 异构执行成本矩阵**（`TaskCostMatrix`）：`SimulationConfig.builder(...).taskCostMatrix(...)` 在 LOCAL + NONE 聚类下按矩阵秒数 × VM MIPS 折算每任务计算 MI（`Math.round`，镜像 STATIC 派发）。
- **任务间数据传输**：LOCAL 文件系统 stage-in 路径按 `size / (1e6 × maxBwth)` 逐文件累加；SOURCE→VM 取目标 VM 带宽，VM→VM 取 `min(bw)`，文件已在目标 VM 上零传输。legacy 模型下传输折算 MI 计入作业执行信封（`totalMi = computeMi + mips × transferSeconds`，自派发起占 VM）；新增 `DataMovementModel.preExecutionTransferDelayV1()` 模型按论文语义将传输建模为执行前网络延迟：每父任务完成即并行启动传输、可与 VM 忙碌期重叠，作业在 `arrival = max_pred(parentFinish + Σc)` 时刻才可派发，VM 只被计算占用（`DATA_STAGE_IN_MODELED` 事件改由 engine 在任务就绪时记录，含 `dataArrivalDelaySeconds`）。新增 `DATA_STAGE_IN_MODELED` 事件与 `totalModeledDataTransferSeconds` 指标。
- **验证**：`TaskCostMatrixIntegrationTest`（4 个）确认矩阵折算与 LOCAL 传输数值逐位一致；参考数字（READY_BATCH_MINMIN 94.086 等）全部精确不变。

### 8.2 HEFT/CPOP 复现（Topcuoglu, Hariri & Wu, IEEE TPDS 2002，图 2/表 2 规范算例）

- **数据核实**：社区转录存在两个互斥变体；本复现采用与论文 rank 表（108/77/80/80/69/63.33/42.67/35.67/44.33/14.67）及论文 HEFT makespan=80 完全自洽的变体（与开源参考实现 `mackncheesiest/heft` 的矩阵与测试断言一致）。编码：3 VM × mips=1.0 × 带宽 1 MB/s，使成本/传输秒数逐位等于论文表值；fixture `datasets/dax/heft/heft-paper-example.dax`（测试副本同名 resources）。
- **新增规划器**：`LOCAL_HEFT`（向上 rank + 插入式 EFT + 副本演进）、`LOCAL_CPOP`（r_u + r_d 优先级、就绪队列、关键路径任务绑定 p_CP）；共享 `AbstractLocalCommPlanningAlgorithm` 执行模型镜像。注册进 Parameters/WorkflowPlanner/SimulationConfig 校验/AlgorithmCatalog manifest。

**复现结果**（COMM-1 修复后，执行前传输延迟语义；相对引导偏移 110.0；随机基线 RANDOM+STATIC 三种子均值）：

| 配置 | 相对 Makespan | 映射 vs 论文 | 传输总秒 |
| --- | --- | --- | --- |
| LOCAL_HEFT | **80.1** | **10/10（调度区间逐位等于论文 + 0.1）** | 140.0 |
| LOCAL_CPOP | 87.1 | 8/10（关键路径 4/4 + p_CP 同 vm1；n6/n8 平局翻转） | 125.0 |
| RANDOM 均值 | 119.8 | — | 155.7 |
| 论文 HEFT/CPOP | 80 / 86 | 基准 | — |

HEFT rank 与论文逐一相同；修复后 HEFT 映射与每任务区间 10/10 逐位复现（旧信封语义下为 9/10、相对 makespan 129.1）；CPOP 关键路径 {n1,n3,n7,n10} 与 p_CP=vm1（计算总秒 51 < vm0 53 < vm2 55）均与论文一致，相对 makespan 87.1 vs 论文 86（旧信封语义 121.1）。全量测试 **202/202 绿**。

**复现轮发现的问题（COMM-1 已修复）**：

| 编号 | 发现 | 处置 |
| --- | --- | --- |
| COMM-1 ✅ | 信封传输语义：原平台将 stage-in 传输折算 MI 计入执行信封、仅在 VM 空闲派发后占 VM；论文将传输建模为执行前网络延迟、可与处理器忙碌期重叠。二者 EFT 比较在个别任务上翻转（HEFT 例 t6）——旧结果 HEFT 9/10、相对 makespan 129.1/121.1 vs 80/86 | **已修复**：新增 `DataMovementModel.preExecutionTransferDelayV1()`（engine 在任务就绪时按父任务估计传输延迟、传输完成后派发；规划器 AST 镜像 `max{VM 空闲, 就绪+传输}`；VM 预留仅覆盖计算）。修复后 HEFT 10/10 逐位复现（80.1 ≈ 80）、CPOP 87.1 ≈ 86；legacy 模型路径逐位不变，参考数字（94.086 等）全部精确不变 |
| COMM-2 📋 | 模型 stage-in Job（110 MI）为整表调度引入恒定引导偏移（相对论文 +110.1）——比较时以绝对值 −110.0 对齐 | 已文档化；属平台入口成本建模，非缺陷 |
| COMM-3 ✅ | AlgorithmSupportMatrixTest 再次拦截未分类新枚举（LOCAL_HEFT、LOCAL_CPOP）——防漂移守卫第二次实战验证 | 已纳入 supported 分区 |

**方向性结论核对**：通信感知规划器显著优于随机映射（80.1/87.1 vs 119.8，−33%/−27%）；LOCAL_HEFT 优于 LOCAL_CPOP（80.1 vs 87.1），与论文在该算例上的方向一致（80 vs 86）——COMM-1 修复消除了信封语义对 EFT 比较的扭曲，两模型的"最优"方向现在与论文一致。
