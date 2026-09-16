# Fat-tree × 调度联合实验：实验设计（campaign 协议）

> 状态：Phase A（设计 + 探测验证）完成于 2026-09-15；2026-09-16 R8 审计轮
> 修订两步：① F1 链路容量单位修复（声明值 ÷8 被解释）后重录 campaign
> 工件——首轮重录实测对称供给（声明链路 1.0 == 端点 1.0 MB/s）下链路层
> 退化为精确 no-op（R6 ≡ R2、全轴恒等，审计报告 §3.3 N-2，降为边界物理）；
> ② 经用户授权**再标定**：链路基线带宽 0.125 MB/s（= 端点 1/8，8:1 接入
> 超收敛，恰为 F1 修复前声明 1.0 时的实际物理值）、A4 带宽比轴 1.25 MB/s
> （基线真 10×，> 端点 ⇒ 非束缚收敛对照），2026-09-16 13:42 全量重跑，
> 链路束缚与拓扑轴判别力恢复（见 §2.3/§2.4 与结果文档）。
> 前置能力：R1 配对显著性框架、R2 端点争用模型、R6 Fat-tree 拓扑感知链路争用模型
> （`docs/research/FAT_TREE_PRINCIPLES.md` / `FAT_TREE_DESIGN.md`）。

## 1. 研究问题

**网络争用是否改变调度算法的相对优劣？** 平台此前只有无争用的抽象带宽模型，
任何"算法 A 比 B 好"的结论都隐含"传输零代价"假设。R6 交付后，平台同时具备
三级数据移动模型，构成受控对照：

| 模型 | 争用域 | 语义 |
| --- | --- | --- |
| `preExecutionTransferDelayV1()`（V1） | 无 | 执行前传输延迟，互不干扰 |
| `preExecutionTransferDelayWithContentionV1()`（R2） | VM 端点 | 并发流公平共享 `vm.getBw()` |
| `fatTreeContentionV1()`（R6） | 端点 + Fat-tree 路径链路 | 确定性路由，链路级公平共享 |

约束域逐级扩大（R6 是 R2 的超集、R2 是 V1 的超集）：每条流的瞬时速率在
R6 下不超过 R2 下（min(端点份额, 链路份额) ≤ 端点份额）。注意这**不**推出
makespan 级弱单调性定理——完成时刻一旦分化，并发流格局随之漂移，可出现
噪声级交叉（R8 再标定重跑实测：120 组合中 5 处，最大相对偏差 7.47×10⁻⁵，
与 F1 修复前旧物理逐位一致）。因此弱单调性在本 campaign 中作为
**诊断**如实报告（JSON/MD 工件含违反清单与最大相对偏差），仅在已实证成立
的 HEFT 论文例子集上做严格断言（golden IT）。条件边界物理：当声明链路
带宽 ≥ 端点带宽时，min 恒等于端点份额，R6 与 R2 **逐位相等**、弱单调性以
等号平凡成立（R8 审计重录实测 10 DAG × 4 规划器全零差值；simulator 对称
探针 284.1 == R2 锁定）。现行再标定装置链路 0.125 < 端点 1.0 MB/s，束缚
生效，R6 > R2 真实成立。

### 探测实证（Phase A-1，2026-09-15）

`FatTreePlannerCompatibilityIntegrationTest`（simulator 模块，HEFT 论文例
fixture，3 主机 × k=4 满配 Fat-tree × 链路 1 MB/s，seed 91）实证：

1. **对照矩阵合法性**：4 规划器（LOCAL_HEFT / LOCAL_CPOP / RANDOM / PSO）×
   3 模型 = 12 组合全部配置合法且端到端健康（`COMPLETED_SUCCESSFULLY`）。
2. **弱单调性**（论文例 fixture）：每个规划器 V1 ≤ R2 ≤ R6 成立。
3. **已知拒绝组合**：`SHARED_STORAGE_*` 家族要求遗留模型；在线调度器
   （FCFS/DATA）要求 INVALID 规划层，与全部 preExecution 家族模型互斥——
   campaign 对照矩阵因此限定在静态映射轨道（STATIC 调度）。
4. **排名已经翻转**（论文例单点证据；R6 列为 R8 单位修复后重录值——链路
   1 MB/s == 端点带宽时 R6 与 R2 逐位相等，翻转由 R2 端点争用驱动）：

| 规划器 | V1 | R2 | R6 fat-tree |
| --- | --- | --- | --- |
| LOCAL_HEFT | **190.1**（第 1） | 284.1（第 2） | 284.1（第 2） |
| LOCAL_CPOP | 197.1（第 2） | **263.1**（第 1） | **263.1**（第 1） |
| RANDOM | 255.1（第 3） | 393.1（第 3） | 393.1（第 3） |
| PSO | 209.1（第 4） | 338.1（第 4） | 338.1（第 4） |

无争用时 HEFT 胜 CPOP；两个争用模型下 CPOP 反超 HEFT。campaign 要回答的是：
这个翻转在多 DAG、多拓扑参数下是否稳健，以及翻转幅度如何随网络配置变化。
（R8 前的 R6 列为 1032.1/919.1/1469.1/1556.35，产自链路 ÷8 旧物理，作废；
链路束缚时模型确实生效的证据见 `FatTreeContentionIntegrationTest` 慢链路
探针：链路 0.25 MB/s 时 makespan 588.1 > R2 284.1。）

`FatTreeCampaignDagCompatibilityIntegrationTest`（experiments 模块）实证 DAG
数据兼容性：原始 Montage/Sipht DAX 含同名文件的不一致尺寸声明（Montage
`fit.txt` 272.0 vs 282.0 等；Sipht `Seq_NC_0025AG05`），被 LOCAL 通信感知
规划族的严格副本校验拒绝——两家族全部规模不可用于本 campaign，排除声明
以该契约测试为实证依据。

## 2. 样本与矩阵设计

### 2.1 DAG 样本集（跨 DAG 配对统计的样本）

10 个核心 DAX：3 个科学工作流家族 × 3 个规模档 + 论文例：

| 家族 | 小 | 中 | 大 |
| --- | --- | --- | --- |
| Epigenomics | n24 | n46 | n100 |
| CyberShake | n30 | n50 | n100 |
| Inspiral | n30 | n50 | n100 |

加 `datasets/dax/heft/heft-paper-example.dax`（10 任务，与探测同 fixture）。
n=10 配对样本量落在 R1 `PairedWilcoxonSignificance` 精确分布路径（≤25）内。

**排除与不入选**：Montage/Sipht（数据兼容性，见上）；千任务级规模档
（Epigenomics_997 / CyberShake_1000 / Inspiral_1000）不入主矩阵——争用引擎
下的性能与统计设计另议，结果文档局限性中声明。

### 2.2 平台与放置

- 平台：3 主机 3 VM，同质（mips 1.0、每 VM 带宽 1 MB/s、SPACE_SHARED），
  与论文例 fixture 相同——计算/传输时间直接可比。R6 拓扑链路基线带宽
  0.125 MB/s = 端点带宽的 1/8（R8 再标定，§2.3）。
- 放置：默认轮转（host i → edge 索引 i mod k²/2）。3 主机落在
  (pod0,edge0)、(pod0,edge1)、(pod1,edge0)：同时覆盖同 Pod 跨 edge（4 链路
  路径）与跨 Pod（6 链路路径），跨全部 DAX 一致，保证配对可比。
- 不提供显式 TaskCostMatrix：规划器从 DAX runtime 与 VM MIPS 推导成本
  （与健康矩阵相同），避免人工成本矩阵对跨家族比较引入额外变量。
- seed 固定 91（全部运行同 seed）：配对沿 DAG 维度而非 seed 维度，seed 不是
  混杂变量；RANDOM/PSO 的 seed 条件性在局限性中声明。

### 2.3 主矩阵（120 次运行）

10 DAG × 4 规划器 × 3 模型。每次运行记录：makespan、Job 数、逻辑任务完成
状态。R6 运行使用 k=4 满配拓扑、链路 0.125 MB/s（= VM 端点带宽 1 MB/s 的
1/8，**R8 再标定**：8:1 接入超收敛的现实配置，恰恢复 F1 单位修复前声明
1.0 时的实际物理区间；执行器常量 `BASELINE_LINK_BANDWIDTH_MB`）。条件
边界：链路 ≥ 端点时链路层为精确 no-op、R6 ≡ R2（R8 审计重录实测；
simulator 对称探针锁定）；现行装置链路 < 端点，束缚生效——再标定重跑实测
R6 > R2（论文例 HEFT 5195.1 → 5738.1，结果文档 §0.1）。

### 2.4 敏感性轴（OFAT 扫描，120 + 120 次运行）

固定规划器 LOCAL_HEFT + LOCAL_CPOP、模型 R6，10 DAG，逐项改变一个轴
（其余保持基线 = k=4 满配、链路 0.125 MB/s、默认放置）：

| 轴 | 变体 | 研究含义 |
| --- | --- | --- |
| A1 超收敛 | k=4、m=2（2× 超收敛） | core 减量后跨 Pod 链路变热点，争用放大多少 |
| A2 拓扑规模 | k=8、m=16（满配） | 更多并行路径能否稀释争用 |
| A3 大规模超收敛 | k=8、m=8（2× 超收敛） | A1 与 A2 的交互 |
| A4 链路带宽 | k=4 满配、链路 1.25 MB/s | 链路远宽于端点时是否收敛回 R2 式端点主导。**R8 再标定注**：1.25 = 基线 0.125 的真 10×（执行器常量 `A4_LINK_BANDWIDTH_MB`）；> 端点 1.0 ⇒ 非束缚 ⇒ 再标定重跑实测精确收敛回主矩阵 R2（5195.1/5205.1），轴恢复设计语义的判别力。修复前"10 MB/s"标签被 ÷8 失真为实际 1.25 MB/s——恰与再标定后的诚实声明值相同 |
| A5 放置热点 | k=4 满配、host0/host1 同 edge0 + host2 跨 Pod | 同 edge 热点对（k=4 每 edge 上限 k/2=2 台主机，3 台无法全同 edge） |

**结构轴退化（两装置下均实测成立的物理现象）**：3 主机平台上任意两条并发
流必共享一个端点主机；路由变体（A1/A2/A3/A5）改变共享链路的集合，但不
改变每条链路上的流重叠集合，公平份额速率画像不变——再标定重跑实测结构轴
全部恒等（10 DAG × 2 规划器配对差值全为零），与审计重录版一致。R7 首轮
曾报告"只有带宽比轴（A4）有效"——R8 审计证明那来自 ÷8 旧物理；再标定
（诚实声明 0.125/1.25，与旧物理相同）后 **A4 有效性作为设计语义的必然
结果恢复**（链路 > 端点 ⇒ 非束缚 ⇒ 收敛回 R2），不再是巧合。为激活结构
轴判别力，campaign 保留 **4 主机结构块**：同 6 变体（A5 改为两个同 edge
主机对 {0→0,1→0,2→2,3→2}），4 主机允许不共享端点的交叉流（如 0→2 与
1→3），4 主机平台 = 4 VM，静态映射同构扩展——再标定重跑实测 4 主机结构轴
仍全恒等（交叉流从未并发经过差异链路），A4 在 8/10 DAG 上严格降低
makespan（结果文档 §3.2）。路由层契约由 `FatTreeTopologyTest` 结构轴测试
锁定：A1 下交叉流确实共享 core 链路、A2 单 Pod 化、A5 缩短同 edge 路径——
拓扑参数确实重塑路由与共享格局。

**R8 附注（历史与现状，2026-09-16）**：
- *历史（审计重录，对称供给退化）*：单位修复后首轮重录声明链路 1.0 MB/s
  == 端点带宽，链路层对任何流都不构成约束，min(端点份额, 链路份额) =
  端点份额恒成立——两个块全部 6 变体（含 A4）与基线逐位恒等，R6 ≡ R2。
  该退化降为边界物理事实，由 simulator 对称探针承担锁定
  （`FatTreeContentionIntegrationTest`：对称场景 R6 与 R2 逐位相等 284.1；
  慢链路 0.25 MB/s < 端点时 makespan 588.1 > R2 284.1 证明引擎在链路
  束缚时生效）。
- *现状（再标定，束缚恢复）*：链路基线 0.125 MB/s（端点 1/8）、A4 1.25
  MB/s。带宽轴判别力恢复（A4 精确收敛回 R2 / 结构块 A4 严格降低 makespan）；
  结构轴（A1/A2/A3/A5）在 3/4 主机两平台仍全 DAG 恒等——这是端点共享的
  平台物理而非带宽参数问题，恒等结果作为退化边界如实保留在工件中。

### 2.5 统计方案

- **配对 Wilcoxon**（`PairedWilcoxonSignificance.evaluate`）：对每个
  （模型 × 规划器对）计算 10 个 DAG 上的配对差值并检验；同模型内比较回答
  "该网络下谁显著更好"，跨模型比较（V1 vs R2 vs R6）回答"争用是否显著
  拉大/缩小差距"。
- **排名翻转汇总**：每个模型下按平均 makespan 排序 4 规划器，逐 DAG 记录
  第一名归属，输出翻转频率。
- 推断边界如实声明：配对沿 DAG 维度（同 DAG 同平台同 seed 才配对），不是
  event-keyed CRN；样本限定 10 个公开 DAX 族实例（3 家族）；结论限于本平台
  的抽象语义（流级流体模型、确定性路由），不外推至真实集群。

## 3. 执行器与工件

- `org.workflowsim.experiments.fattree.FatTreeSchedulingCampaignExecutor`
  （experiments 模块 main 类，参考 P7 执行器模式）：参数 `<datasets-root>
  <output-dir>`，无参数时用仓库根 `datasets` 与 `fattree-campaign-output/
  run-<时间戳>`。
- 工件：`campaign-results.json`（schema `workflowsim-fattree-campaign-v1`：
  主矩阵逐运行记录 + 3 主机敏感性 + 4 主机结构敏感性 + 弱单调性诊断 +
  排名汇总 + 配对统计）与 `campaign-results.md`（人读表格，直接嵌入结果
  文档）。正式运行工件入库保留于
  `experiments/studies/fattree-scheduling-campaign/`（含 RETENTION.md：
  来源身份、保留理由与复现验证命令）。
- 运行确定性：同配置复跑 makespan 逐位一致（由 IT 锁定抽样组合）。

## 4. 验收标准

1. 主矩阵 120 + 3 主机敏感性 120 + 4 主机结构敏感性 120 = 360 次运行全部
   `COMPLETED_SUCCESSFULLY`。
2. 弱单调性诊断（V1 ≤ R2 ≤ R6，逐 DAG 逐规划器）如实输出到工件；任何交叉
   必须为噪声级（实测最大相对偏差 ≤ 0.01%）且被解释；HEFT 论文例子集上
   严格成立（golden IT 断言）。
3. 确定性抽样：抽主矩阵配置双跑逐位一致。
4. 关键不变量与抽样黄金值由 IT 锁定：simulator 模块
   `FatTreePlannerCompatibilityIntegrationTest`（论文例 12 组合健康 +
   弱单调 + 拒绝组合，成本矩阵 fixture）、`FatTreeContentionIntegrationTest`
   （R8 重设计：对称供给黄金值 284.1 且与 R2 逐位相等 = 单位契约端到端锁；
   慢链路 0.25 MB/s 黄金值 588.1 > R2 = 链路束缚时模型有效性锁）与
   `FatTreeTopologyTest` 结构轴测试（路由共享格局契约；R8 按 ×1e6 单位
   契约重录容量断言）；experiments 模块
   `FatTreeCampaignDagCompatibilityIntegrationTest`（DAG 兼容性分类）与
   `FatTreeCampaignGoldenIntegrationTest`（6 IT，R8 按再标定重跑工件更新：
   论文例 12 组合黄金值（R6 列 5738.1/5854.1/7206.1/7262.1）、cybershake
   逐 DAG 换冠、弱单调诊断论文例严格 + 子集噪声级（≤ 10⁻⁴）、确定性双跑、
   3 主机敏感性结构轴恒等 + A4 == 主矩阵 R2、4 主机结构块结构轴恒等 +
   A4 < 基线（黄金值 5718.1/5574.1/5186.1/5168.1））。
5. 结果文档全部数字来自执行器实测输出（`docs/experiments/
   FATTREE_SCHEDULING_RESULTS.md`），结论含"排名翻转是否稳健"的明确回答。

## 5. 诚实边界

- 在线调度器轨道（FCFS/DATA/READY_BATCH_*）与全部争用模型互斥（INVALID
  规划层限制），campaign 不覆盖"动态调度 × 网络争用"——这是平台级缺口，
  单独立轮次。
- Montage/Sipht 家族因原始数据尺寸不一致被排除：两家族在遗留模型轨道
  （P7 基线）仍被覆盖，但在争用模型轨道无样本。
- seed 固定：RANDOM/PSO 结论条件于 seed 91；跨 seed 稳健性未测。
- 流级流体模型 + 确定性路由 + 均匀链路带宽（R6 边界，见设计文档 §7）。
- 同质 VM、无显式成本矩阵、静态映射轨道：结论不外推到异构定价或在线场景。
- 千任务级规模未纳入（性能与统计设计另议）。
