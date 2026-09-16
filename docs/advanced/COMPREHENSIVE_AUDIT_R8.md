# R8 全面排查审计报告（测试工程师视角）

> 状态：**完成**（4 个只读审计通道已返回并经主线逐条对码核实；P0/P1 修复、测试补齐、campaign 重录与文档重写见 §4/§5）
>
> 方法：全量 clean 门禁基线（416 测试绿）+ JaCoCo 覆盖率盲区定位 + 分域只读代码审计 + 对照原始论文语义复核 + 实测运行验证。所有结论必须有代码/运行证据。

## 1. 排查范围

| 域 | 内容 | 审计通道 |
| --- | --- | --- |
| 规划算法 | HEFT/CPOP/DLS/ETF/PEFT/RANDOM/PSO、SHARED_STORAGE_* 改编 | 算法×论文 |
| 在线调度器 | FCFS/ROUNDROBIN/READY_BATCH_*/DATA/MC、RL 轨道 | 算法×论文 + 架构 |
| 数据移动 | 遗留 STORAGE、preExecution V1/R2/R6、TransferContentionEngine | 数据模型与网络 |
| 网络拓扑 | FatTreeTopology 结构/路由/容量注册、单位一致性 | 数据模型与网络 |
| 指标与工件 | SimulationMetrics、等待时间、成本、Wilcoxon/Wilson/KS、manifest/events/validator | 指标与工件 |
| 故障/开销 | 分布生成器、故障注入、重试语义 | 指标与工件 + 架构 |
| 契约与架构 | 配置拒绝矩阵、静态状态泄漏、种子链、解析校验 | 架构与确定性 |
| 覆盖率盲区 | JaCoCo 按类/分支定位 | 本轮直接分析 |

## 2. 覆盖率盲区分析（已完成，实测 JaCoCo XML）

单位：单元测试（surefire）指令/分支覆盖率；IT 覆盖进入 `jacoco-it.exec` 不计入此表。

### 2.1 研究路径低覆盖类（simulator）

| 类 | 指令 | 分支 | 判定 |
| --- | --- | --- | --- |
| `scheduling/MaxMinSchedulingAlgorithm` | 0% | n/a | **可接受**：`@Deprecated MAXMIN` 遗留标签；`SimulationRunner` 第 123-129 行显式拒绝全部遗留标签（"legacy compatibility label"），标准研究入口不可达。仅遗留 API 可触达。 |
| `scheduling/MCTSchedulingAlgorithm` | 0% | n/a | 同上（`@Deprecated MCT`），待架构通道确认拒绝路径完整 |
| `ClusterStorage`、`clustering/*` | 0% | n/a | 遗留聚类族；文档已声明聚类不属于已认证研究入口（README「标准研究入口只接受无任务聚类」）。待确认研究入口确实全部拒绝 |
| `rl/RlEpisodeResult` | 0% | n/a | IT 覆盖（RL episode 端到端验收），单元级为数据载体，低风险 |
| `exception/*` | 0% | n/a | 异常类型声明，无逻辑 |

### 2.2 分支薄弱类（指令覆盖尚可、分支 <50%）

| 类 | 指令 | 分支 | 风险 |
| --- | --- | --- | --- |
| `utils/DistributionSpec` | 51.8% | 40% | 中：故障分布声明解析/校验的分支多，需补边界用例 |
| `utils/OverheadModelConfig` | 63.5% | 46% | 中：开销模型配置校验分支 |
| `experiment/SimulationReport$MutableVmSummary` | 84.4% | 25% | 低-中：报告聚合分支 |
| `experiment/ExperimentEvidenceContext` | 91.3% | 33% | 中：证据上下文条件分支 |

### 2.3 experiments 模块（排除 examples）

| 类 | 指令 | 分支 | 判定 |
| --- | --- | --- | --- |
| `experiments/fattree/FatTreeSchedulingCampaignExecutor` | 37.9% | 19% | **符合设计**：仿真主路径由 6+2 IT 覆盖（jacoco-it），单元层只测纯逻辑；棘轮门槛（0.23/0.25）通过 |
| `experiments/reference/p7/ReferenceDatasetRoot` | 54.9% | 62% | 低：P7 数据集根探测的防御分支 |
| 其余 P7 类 | 71-100% | 50-94% | 低风险 |
| `examples/*`（0%） | — | — | CLI 主类由 `ExamplesCliSmokeIntegrationTest`（40 IT）冒烟覆盖，单元 0% 属预期 |

## 3. 分域审计发现

严重级：P0 = 结论级错误（已发表结论受影响）；P1 = 静默失真/崩溃（特定配置下错误无提示）；P2 = 契约缺口/可解释偏差；P3 = 文档与可读性。每条含处置（✅ 已修复 / 📄 记录接受 / ⏭ 延后）。

### 3.1 算法 × 论文一致性

| # | 级 | 发现 | 证据 | 处置 |
| --- | --- | --- | --- | --- |
| A-1 | P1 | 任务成本矩阵 × `SHARED_STORAGE_*` 规划器组合静默分叉：规划侧用原始 DAX 长度排序、STATIC 派发路径运行时按矩阵折算 MI，两侧语义不一致且无报错 | `SimulationConfig.build()` 原无此拒绝；`SharedStorageDagPlanner` 不消费矩阵 | ✅ 配置层拒绝（`SimulationConfig` P1-1）+ `SimulationConfigCostMatrixValidationTest`（2 测试，含合法组合 LOCAL_HEFT+矩阵保持可用） |
| A-2 | P2 | 全部规划器测试只锁黄金值/确定性，无任何"启发式不劣于无信息基线"的方向性契约——算法被改坏到劣于随机时失败信息无法指向根因 | 测试清单核查 | ✅ 新增 `PlannerDirectionalityIntegrationTest`（论文例 fixture 上 LOCAL_HEFT 190.1 ≤ RANDOM、LOCAL_CPOP ≤ RANDOM，种子 91 实测）+ `PSOPlanningAlgorithmTest.psoFitnessIsNoWorseThanSameSeedRandomFitness`（同种子 PSO 适应度 ≤ RANDOM 映射适应度，实测成立） |
| A-3 | P2 | PSO 对非正/非有限 VM MIPS 无入口校验：`length/mips` 产生 Inf/NaN 适应度污染整个粒子群，最终以 `globalBestPosition` NPE 崩溃、无法定位根因 | `PSOPlanningAlgorithm.run()` + `PsoFitnessFunction:72` | ✅ 入口 fail-fast（含 VM id 定位）+ 收敛后 null 防御 + `nonPositiveVmMipsFailsFastAtEntry` 回归 |
| A-4 | P2 | PSO 是参考实现（meysamhit/workflowsim-pso 公式：fitness=0.8·cost+0.2·makespan）的复现，非 Pandey et al. 2010 原论文的完整 PSO-DAG 语义（无依赖感知传输成本项） | `PSOPlanningAlgorithm` 类 javadoc 已声明来源 | 📄 记录接受：来源已在 javadoc/目录声明，适应度公式有逐位手算单测锁定；升级为原论文语义属新研究项 |
| A-5 | P3 | 遗留 `@Deprecated` 调度标签（MAXMIN/MCT 等）单元覆盖为 0 | §2.1；`SimulationRunner:123-129` 显式拒绝全部遗留标签进入研究入口 | 📄 记录接受：不可达路径，拒绝逻辑本身有测试 |

### 3.2 指标、统计与工件管线

| # | 级 | 发现 | 证据 | 处置 |
| --- | --- | --- | --- | --- |
| M-1 | P1 | 配对 Wilcoxon 三缺陷：① 零差值对未按 Pratt/常规约定排除，稀释显著性；② N>30 大样本路径数组越界崩溃；③ 正态近似的 p 值未钳位到 [0,1] | `PairedWilcoxonSignificance`（修复前） | ✅ 三处修复 + `PairedWilcoxonSignificanceTest` 12/12（含 R 对照值、零差值、N=40、极端 p 边界）；campaign 配对统计随 R8 重录全部再生 |
| M-2 | P1 | `SimulationReport.VmSummary` 覆写 `equals` 未覆写 `hashCode`（对象放入哈希容器时契约破坏） | 类定义核查 | ✅ 补 `hashCode` + 回归断言 |
| M-3 | P2 | 覆盖盲区：`DistributionSpec`（分支 40%）、`OverheadModelConfig`（46%）、`ExperimentEvidenceContext`（33%）的校验分支无边界用例 | §2.2 JaCoCo 实测 | ✅ 新增 `DistributionSpecTest`(4)、`OverheadModelConfigTest`(7)、`ExperimentEvidenceContextTest`(6) |
| M-4 | P2 | 成本模型为线性简化（processingCost=按秒计价、无计费粒度/最小计费单位），与商业云计费语义有已知距离 | `SimulationMetrics` | 📄 记录接受：文档已声明为研究型线性成本；引入计费粒度属新特性 |
| M-5 | P2 | 跨工作流指标（R5 多工作流）以工作流索引归属；到达时刻语义修复前存在归属错位（见 AR-1） | `MultiWorkflowArrivalIntegrationTest` | ✅ 随 AR-1 修复并锁黄金值 8118.1/8068.1 + 双向归属断言 |
| M-6 | P3 | events/manifest 工件 schema 字段文档与实现个别字段描述不同步 | validator 通过、字段核查 | 📄 记录接受：schema 校验器为准，文档差异不影响机器可读性 |

### 3.3 数据移动模型与网络拓扑

| # | 级 | 发现 | 证据 | 处置 |
| --- | --- | --- | --- | --- |
| N-1 | **P0** | **FatTree 链路带宽单位 bug（F1）**：声明 MB/s 转字节/秒时多除以 8（混入比特语义），全部 fat-tree 链路实际容量 = 声明值的 1/8。**R7 campaign 全部 R6 结论产生于此失真物理** | `FatTreeTopology.fromSpec`（修复前 `×1e6/8`）；物理探针实测：0.125 MB/s→1032.1、0.25→588.1、1.0→284.1（单调 ✓）；修复前 R6 黄金 1032.1 == 修复后 0.125 显式声明值 | ✅ 修复为 `×1e6`（与 VM 端点 `bw×1e6` 同一惯例）+ `FatTreeTopologyTest` 单位契约断言 + `FatTreeContentionIntegrationTest` 重写（对称供给 284.1==R2 锁单位契约；慢链路 0.25 MB/s→588.1 锁模型有效性）+ **campaign 全量重录**（见 §5） |
| N-2 | P1 | 修复 F1 后的结论级发现：声明链路带宽（1.0 MB/s）== VM 端点带宽时链路层永不束缚单流 ⇒ **R6 ≡ R2 逐位相等、结构/带宽敏感性轴（含 A4 10×）全退化**；R7"拓扑轴有效"叙事不成立，flip-to-PSO 保留但根因为 R2 端点争用 | 重录 campaign 实测：全部 120 R6 运行 == R2；结构块 6 变体逐 DAG 恒等（如 LOCAL_HEFT heft 例 5186.1 × 6 变体） | ✅ 黄金 IT 按实测重录（含退化恒等锁定）+ 结果文档重写 + 再标定建议（链路 < 端点带宽）作为用户可决策后续项 |
| N-3 | P1 | `transferredBytes` 把副本本地（零传输）文件计入传输字节数，污染数据移动指标（F2） | `WorkflowDatacenter` | ✅ 经 `isFileLocalForJob` 跳过本地文件 + 回归测试 |
| N-4 | P2 | 路由命名与容量注册两侧无闭包契约：路由若产生未注册链路键，争用引擎视为无限容量、静默失真（F8①）；两个争用工厂无直接单测（F8②） | `FatTreeTopology.route`/`registerCapacities` | ✅ 新增 `getLinkIds()` 只读视图 + `everyRouteLinkIsARegisteredCapacityKey`（3 类配置全主机对闭包断言）+ `DataMovementModelTest.contentionFactoriesExposeConsistentKindPredicates` |
| N-5 | P2 | R2/R6 规划侧 AST 按无争用估计、运行期并发传输公平共享——并发负载下规划/执行有可解释偏差 | `SimulationConfig` 注释已声明 | 📄 记录接受：偏差方向与量级由文档声明，属模型设计而非缺陷 |

### 3.4 架构、契约与确定性

| # | 级 | 发现 | 证据 | 处置 |
| --- | --- | --- | --- | --- |
| AR-1 | **P0** | R5 多工作流到达时刻归属 bug：提交时刻按扁平任务列表索引归属工作流，动态到达场景下后续工作流的到达延迟错误落到前一工作流（P0-1） | `WorkflowEngine`（修复前）；修复后黄金 8118.1/8068.1 与手算一致 | ✅ `workflowIndexOf` 归属修复 + `MultiWorkflowArrivalIntegrationTest` 4/4（双向归属断言） |
| AR-2 | P1 | 配置类异常被吞成通用失败：`SimulationConfigurationException` 在 `WorkflowScheduler`/`SimulationRunner`/CloudSim 事件循环被包装或吞掉，用户看到的是无关堆栈（P1-7） | `RlEnvironmentEpisodeIntegrationTest.rlPolicyWithoutEnvironmentFailsExplicitly` 修复前需两层解包 | ✅ 三处贯通（CloudSim IAE 重抛、Runner catch 直通、Scheduler 直通）；RL IT 改为直接断言 SCE |
| AR-3 | P1→📄 | VM 创建失败仅记日志继续运行，疑似静默失真（P1-6）——**修复尝试被撤销**：fail-fast 使 `WorkflowSimMultipleClusterExample1` 退出码 1；多集群示例把 2 个数据中心绑定到同一调度器、依赖失败 ACK 作为合法试探性放置流程 | `ExamplesCliSmokeIntegrationTest.catalogDefaultsCompleteInIsolatedJvm` 实测回归 | 📄 记录接受：恢复 log-only 并在 `WorkflowScheduler` VM_CREATE_ACK 分支留注释说明多集群试探放置的合法性；标准研究入口（单数据中心 PlatformProfile）不受影响 |
| AR-4 | P1 | 解析层四洞（P1-4）：① WfCommons runtime×MIPS 溢出经 `(long)` 静默钳位；② WfCommons 重复文件 id / execution task id last-wins（字节数/长度取决于声明顺序）；③ DAX OUTPUT 与已注册同名 INPUT 尺寸不一致完全静默；④ DAX/WfCommons 版本无校验 | `WfCommonsJsonParser`、`WorkflowParser:319-338` | ✅ ①溢出 fail-fast（镜像 DAX 路径）②值冲突 fail-fast（同值去重保持兼容）③对齐 INPUT 分支记录警告 ④版本作为记录接受：已存证于 `WorkflowInputReport`，单位语义契约（DAX size 按字节、名义 KB 差 1024×）写入 `WorkflowParser` javadoc + 回归测试 `WfCommonsJsonParserValidationTest` +2 |
| AR-5 | P1 | 静态状态卫生：`SimulationSession` 异常路径不恢复 `Log` 状态、初始化断言缺失；`WorkflowPlanner` 不透传配置；`SharedStorageDagPlanner` 用 HashMap 引入遍历不确定性 | 各类核查 | ✅ 三处修复 + `CrossConfigurationContaminationIntegrationTest`（背靠背异构配置无污染） |
| AR-6 | P2 | 种子链核查：`SimulationRandom` 命名子流 + campaign 固定 seed 91 全链路确定性 | 双跑逐位一致 IT | 📄 无缺陷：确定性契约已有测试锁定 |

## 4. 处置汇总

- **修复（代码 + 回归测试）**：N-1（F1 单位）、N-3（F2 字节数）、AR-1（P0-1 到达归属）、M-1（Wilcoxon×3）、M-2（hashCode）、AR-2（P1-7 异常贯通）、AR-4①②③（解析）、AR-5（状态卫生×3）、A-1（矩阵×SHARED_STORAGE 拒绝）、A-3（PSO 校验）、N-4（F8 闭包/工厂）。
- **测试缺口补齐**：方向性契约（A-2）、覆盖盲区三类（M-3）、慢链路物理探针、跨配置污染 IT、退化恒等黄金锁。
- **记录接受（含理由）**：AR-3（多集群试探放置合法、修复反致回归——撤销并注释）、A-4（PSO 参考实现来源已声明）、A-5（遗留标签不可达）、M-4（线性成本模型）、M-6（schema 文档措辞）、N-5（规划/执行争用偏差已声明）、AR-4④（版本存证+单位契约文档化）。
- **延后（用户可决策）**：campaign 链路带宽再标定（恢复拓扑轴判别力需链路 < 端点带宽，如 0.125/0.25 MB/s——本轮忠实记录退化实测，不擅自改实验设计）。
- **结论级影响声明**：R7 的"fat-tree 拓扑改变调度排名"叙事在修复后物理下不成立（N-2）；受影响文档已全部重写并保留历史警示。

## 5. 验证

### 5.1 黄金值重录对照（原因：F1/F2/P0-1/Wilcoxon 修复，2026-09-16 重录 campaign）

| 位置 | 旧值 | 新值（实测） | 根因 |
| --- | --- | --- | --- |
| `FatTreeContentionIntegrationTest` fat-tree 黄金 | 1032.1 | 284.1（== R2 端点黄金，单位契约锁） | F1 |
| 同上新增慢链路探针（0.25 MB/s） | — | 588.1（> R2 284.1，模型有效性锁） | 新增 |
| 黄金 IT heft 例 R6（HEFT/CPOP/RANDOM/PSO） | 5738.1 / 5854.1 / 7206.1 / 7262.1 | 5195.1 / 5205.1 / 6281.1 / 6254.1（== R2 列） | F1 |
| 黄金 IT n50 PSO R6 | 587921.6099 | 587924.0247（== R2） | F1 |
| 黄金 IT 结构块 baseline（HEFT/CPOP） | 5718.1 / 5574.1，A4 严格更小 | 5186.1 / 5168.1，全 6 变体恒等（旧 A4 值 == 新恒等值，交叉验证 ✓） | F1 |
| R5 多工作流黄金 | （归属错位值） | 8118.1 / 8068.1 | P0-1 |
| campaign 弱单调违规 | 3 处（max 1.76e-4） | 1 处（cybershake-n30 RANDOM，3.7e-6，噪声级） | F1/F2 |

### 5.2 全量门禁（clean 控制台实录，2026-09-16）

```
J17=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home
JAVA_HOME=$J17 mvn -o clean verify
```

| 模块 | Surefire | Failsafe IT | 小计 |
| --- | --- | --- | --- |
| simulator | 290 | 75 | 365 |
| experiments | 27 | 54 | 81 |
| **总计** | | | **446（基线 416，+30）** |

- 全部 0 失败 0 错误 0 跳过；`BUILD SUCCESS`（exit 0，1:07 min）。
- 覆盖率棘轮：simulator 实测 instruction 49.90% / branch 44.78%（棘轮随本轮上调至 0.49/0.44）；experiments 32.85%/34.91%（棘轮 0.23/0.25 不动，因大型语料缺失时单测自动跳过）；两模块 `All coverage checks have been met`。
- 本轮新增测试（+30 净增）：`CrossConfigurationContaminationIntegrationTest`、`DistributionSpecTest`(4)、`OverheadModelConfigTest`(7)、`ExperimentEvidenceContextTest`(6)、`SimulationConfigCostMatrixValidationTest`(2)、`PlannerDirectionalityIntegrationTest`(1)、`PSOPlanningAlgorithmTest`(+2)、`WfCommonsJsonParserValidationTest`(+2)、`FatTreeTopologyTest`(+1 闭包契约)、`DataMovementModelTest`(+1 争用工厂)、`FatTreeContentionIntegrationTest` 重写(+1 慢链路探针)、`PairedWilcoxonSignificanceTest` 扩充等。
