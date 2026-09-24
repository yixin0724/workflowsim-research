# 敏感性响应面研究（R13）：协议与保留说明

协议ID：`sensitivity-response-r13-v1`。Java驱动复用 `org.workflowsim.experiments.network`（NetworkStudyPlan/Executor/Summary/Validator 的 sensitivity-r13 变体）。本研究是**描述性敏感性响应面**：回答"规划器相对收益如何随 VM 数量、链路带宽、VM 异构度三个轴变化"，不做参数拟合或外推。

## 与冻结证据的关系

本研究不改动 R10 协议 `network-limited-r10-v2`（504 次，`output/network-study-r10-final/`）与 S5 协议 `peft-comparison-r12-v1`（126 次，`output/peft-comparison-r12/`）的任何工件；扩展全部为条件性代码路径，冻结索引重验证逐字节不变（本次已复核，两者均 `NETWORK_STUDY_VALIDATION PASSED`）。

## 矩阵

- 合格输入：与 R10/S5 相同的7个输入（5 个经典DAX：Epigenomics 100/997、CyberShake 100/1000、Inspiral 100；2 个确定性合成DAG：layered-32/128）。Inspiral 1000 因 CONFLICTING_FILE_SIZE 全量排除。
- 算法：LOCAL_HEFT、LOCAL_CPOP、LOCAL_PEFT，均确定性，每条件一次（seed 11）。无故障、LOCAL、STATIC、SPACE_SHARED、一Host一VM、固定放置（id↔id）。
- **主块（同构响应面）**：VM ∈ {4, 8, 16, 32} × 网络 ∈ {endpoint 1.0, fat-tree-constrained 0.125, fat-tree-mid 0.5, fat-tree-wide 1.25, fat-tree-fast 5.0 MB/s}，平台全部同构 1000 MIPS。共 20 条件。
- **异构块（OCT 机制检验）**：VM ∈ {8, 16} × fat-tree-constrained × 异构度 ∈ {HET_MILD, HET_STRONG, HET_EXTREME}。MIPS 模式为确定性公式（按VM序号 i）：MILD = 1000(i偶)/500(i奇)；STRONG = [2000,1000,500][i%3]；EXTREME = 2000(i偶)/500(i奇)。共 6 条件。
- 正式运行量：7输入 × 26条件 × 3算法 × 1种子 = **546次**。
- CI smoke：论文十任务fixture + layered-16，主块 4VM×{endpoint, constrained}、异构块 4VM×constrained×3级，共 5 条件 × 3 算法 = 30 次。

### fat-tree k 的 VM 数依赖（必须随结果呈现）

fat-tree k=4 仅承载 16 台主机；VM=32 的条件必须用 k=8（128 台主机）。因此 VM 轴的最后一档同时改变了拓扑层级与主机池大小，**VM=32 与其他档位的对比包含拓扑混杂**，只能描述、不能把差异单独归因于"VM更多"。其余网络/异构度轴不受此影响。

## 预注册机制检验（S5 发现的延伸）

S5 在同构平台上发现 PEFT 相对 HEFT 无优势、大量精确平局，原因是 OCT 前瞻项对全部同构VM取相同值，前瞻退化为常数。R13 据此**预先注册**预测：

> LOCAL_PEFT 的激活（相对 LOCAL_HEFT 的中位改善%明显偏离 0）应**只**出现在异构条件下（HET_MILD/HET_STRONG/HET_EXTREME）；全部同构条件（主块 20 条件）中 PEFT 与 HEFT 的中位改善%应接近 0 且以平局为主。异构条件的同构对照取自主块中相同 VM 数与网络的 constrained 条件。

判定规则（与 S5 同构）：DAG 配对的中位改善%与精确符号检验 p 值、Holm 调整（每 来源/VM/网络/异构度 族，族大小 2）。平局容差为相对 1e-9。预测被认定为"激活"需同时满足：中位改善%明显偏离 0，且至少一条异构条件的 Holm 调整后 p 值给出方向一致的证据（仍按探索性解读，见下）。

## 指标与统计

主指标为仿真结束时间，辅以逻辑任务完成时刻、平均/P95等待与VM利用率。每次运行必须逻辑任务全部成功；失败记录保留，验证器拒绝失败、缺项、重复项或不一致的工件。验证器按条件（含异构度）核查完整矩阵、每份证据的 VM MIPS/拓扑 k/链路带宽与声明一致，并独立重算汇总统计。

确定性规划器每条件单次运行，以 DAG 为配对单位。经典（5）与合成（2）两来源**永不合并**，分别统计。

## 保留说明（必须随结果一同呈现）

1. **描述性，非因果**：响应面只做条件内对比与跨条件描述，不拟合参数模型、不外推到未模拟的配置。
2. **VM=32 拓扑混杂**：见上。
3. **探索性选定语料**：7 个输入是固定选定的，不是随机抽样；p 值不支撑对生产平台或全部工作流的外推；不显著不代表算法等价。
4. **小样本功效**：经典组 5 个 DAG 配对，符号检验功效有限；合成组仅 2 个 DAG，任何 p 值都是钝的。
5. **无外部校准**：所有带宽/MIPS 是抽象模型声明，未与真实云网络校准。
6. 研究未使用训练器。

## 工件与复现

完整证据保存在项目 `output/sensitivity-r13/`（`.gitignore` 排除，不入库；交接用压缩包 `output/sensitivity-r13-evidence.tar.gz`）。每次运行具有v4 manifest、metrics、events；研究根含 protocol、546 行运行索引、自动生成结果表、合成输入。

```bash
mvn -pl :workflowsim-experiments -am compile exec:java \
  -Dexec.mainClass=org.workflowsim.experiments.network.NetworkStudyExecutor \
  -Dexec.args="full /absolute/datasets /absolute/new-output sensitivity-r13"
mvn -pl :workflowsim-experiments -am compile exec:java \
  -Dexec.mainClass=org.workflowsim.experiments.network.NetworkStudyValidator \
  -Dexec.args="/absolute/new-output/network-study.json"
```

输出目录必须不存在。验证器打印 `NETWORK_STUDY_VALIDATION PASSED runs=546` 即为通过。
