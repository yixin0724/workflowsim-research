# 敏感性响应面研究（R13）— 结果

- 协议：`sensitivity-response-r13-v1`（[PROTOCOL.md](PROTOCOL.md)，预注册内容在实验前写定）
- 证据：`output/sensitivity-r13/network-study.json` + `runs/`（打包 `output/sensitivity-r13-evidence.tar.gz`，gitignored）
- 规模：7 workflows × 26 conditions × 3 planners（seed 11）= **546 runs，0 failed**
- 验证：执行器内置校验 + 独立 Validator `NETWORK_STUDY_VALIDATION PASSED runs=546`；冻结证据复验 `runs=504` / `runs=126` 仍 PASSED
- 基线：LOCAL_HEFT；候选：LOCAL_CPOP、LOCAL_PEFT；改善% = (HEFT−候选)/HEFT×100，正值=更快
- 推断范围：每层为探索性选定语料（5 个经典 DAG 或 2 个合成 DAG），总体间不合并；DAG 配对符号检验 + Holm 校正（每层 family=2）

## 1. 首跑失败与规划器修复（过程记录）

全量首跑有 18/546 个 LOCAL_PEFT 格子失败，全部集中在
{epigenomics-100, epigenomics-997, inspiral-100} × vm{8,16} × {HET_MILD, HET_STRONG, HET_EXTREME}。
根因：异构 MIPS 下 PEFT 论文的 rank_o 降序不保证拓扑序（复现：epigenomics-100 vm8
HET_MILD 中 parent 56 排在 task 80 之后），基类 `readyTime` 按 R12 设计的边界声明快速失败。
深链经典 DAG 的 OCT 跨 VM 落差放大了该反转；cybershake 与合成 DAG 未触发。

修复（commit 37d3258）：LOCAL_PEFT 分配改为 HEFT 系标准**就绪表纪律**——每轮在“前驱均已分配”
的任务中取 rank_o 最高者（平局取较小任务 ID）。当 rank_o 序本身拓扑合法（全部同构条件与论文算例）
时，就绪表选择与字面 rank_o 序逐项一致、调度逐位不变：论文精确复现单测（OCT 表/调度/makespan=76）
与全部既有 PEFT 单测保持绿（10/10），另新增异构反转单测。修复后 546/546 全部成功。
此为研究能力扩展（异构 OCT 语义）而非既有结论修正：R10/S5 冻结证据（同构条件）未受影响，复验 PASSED。

## 2. 预注册机制检验判定

**预测（PROTOCOL.md 实验前写定）**：PEFT 的激活（相对 HEFT 的中位改善%明显偏离 0）应只出现在
异构条件下；全部同构条件中 PEFT 与 HEFT 的中位改善%应接近 0 且以平局为主。

**判定：部分证实，结论需收窄（见第 2.3 节）。**

### 2.1 CLASSIC_DAX：方向上证实

- 同构（20 主块条件）：vm≥8 时 PEFT 中位改善%全部 ≤0.03%，且以平局为主
  （vm8: 2/2/1，vm16: 0/3/2 为主，vm32: 1/3/1 为主）——预测成立。
  **vm4 例外**：endpoint/wide/fast 三档 PEFT +1.665%（3 胜 1 平 1 负），非平局为主——预测不完全成立。
- 异构（fat-tree-constrained 对照同构 vm8/16）：PEFT 出现明显**负向**偏离：
  vm8 HET_MILD −2.454%（**0/0/5 全负**）、vm8 HET_STRONG −2.215%（1/0/4）、
  vm16 HET_STRONG −1.605%（**0/0/5 全负**）；对应同构对照为 +0.028% / +0.000%（均以平局为主）。
  最小 Holm 校正 p = 0.125（vm8 HET_MILD、vm16 HET_STRONG），未达显著；但全负模式方向一致。

**机制解读（探索性）**：OCT 前瞻在异构 MIPS 下确实“激活”——决策不再退化为 HEFT 等价——
但激活表现为**系统性负偏离**：OCT 按 VM 对无争用带宽做乐观估计，异构落差使其过度偏好快 VM，
在本语料上劣于 HEFT 的实际放置。这为 S5 的退化发现补上了异构侧的对照证据：
退化（同构，vm≥8）与乐观偏差（异构）是同一前瞻结构的两面。

### 2.2 SYNTHETIC：预测不成立

PEFT 在同构 vm4/8 即有大幅偏离（−6.36% ~ −15.98%），异构 ±18% 无序；n=2 无推断力。
机制（探索性）：同构 VM 下 PEFT 的 rank_o 取同 VM 最小通信（=0），HEFT 的 rank_u 取跨 VM
平均通信（>0），两者排序本不相同；合成层级 DAG 通信占比高，排序差异直接转化为调度差异。
因此“同构即退化”只对经典 DAG 语料成立，不能推广。

### 2.3 收窄后的结论

> 在本研究的探索性语料上：(a) CLASSIC_DAX、vm≥8 时 PEFT 同构退化成立、异构激活成立，
> 但激活方向为负（乐观偏差），且 Holm 校正后不显著；(b) 预测的强形式（“只”在异构条件激活、
> “全部”同构接近 0 且平局为主）不成立——vm4 经典 DAG 与合成 DAG 均有同构偏离。
> OCT 前瞻的价值判断必须同时声明语料与 VM 数边界。

## 3. 响应面 I：同构主块（中位改善% 相对 LOCAL_HEFT）

### 3.1 CLASSIC_DAX（5 DAG；HEFT 中位 makespan：vm4≈39.9h → vm32≈11.6h，endpoint）

| VM | 候选 | endpoint | ft-constrained | ft-mid | ft-wide | ft-fast |
|---:|:--|---:|---:|---:|---:|---:|
| 4 | CPOP | +0.002 | +0.012 | +0.003 | +0.002 | +0.002 |
| 4 | PEFT | +1.665 | +0.037 | +0.068 | +1.665 | +1.665 |
| 8 | CPOP | −0.015 | −0.015 | −0.015 | −0.015 | −0.015 |
| 8 | PEFT | +0.000 | +0.028 | +0.000 | +0.000 | +0.000 |
| 16 | CPOP | −0.015 | +0.091 | −0.015 | −0.015 | −0.015 |
| 16 | PEFT | −0.000 | +0.000 | −0.000 | −0.000 | −0.000 |
| 32 | CPOP | +0.000 | +0.170 | −0.000 | +0.000 | +0.000 |
| 32 | PEFT | +0.000 | +0.000 | +0.000 | +0.000 | +0.000 |

### 3.2 SYNTHETIC（2 DAG，描述性；HEFT 中位 makespan：vm4 endpoint≈0.22h、ft-constrained≈0.95h）

| VM | 候选 | endpoint | ft-constrained | ft-mid | ft-wide | ft-fast |
|---:|:--|---:|---:|---:|---:|---:|
| 4 | CPOP | +14.911 | +15.066 | +14.967 | +14.911 | +14.911 |
| 4 | PEFT | −7.602 | −15.117 | −15.984 | −7.602 | −7.602 |
| 8 | CPOP | +2.368 | +5.224 | +5.620 | +2.369 | +2.368 |
| 8 | PEFT | −6.362 | −11.796 | −11.842 | −6.362 | −6.362 |
| 16 | CPOP | −12.852 | −15.244 | −14.781 | −12.857 | −12.852 |
| 16 | PEFT | 0（全平局） | 0（全平局） | 0（全平局） | 0（全平局） | 0（全平局） |
| 32 | CPOP | +0.127 | +0.019 | +0.077 | +0.127 | +0.127 |
| 32 | PEFT | 0（全平局） | 0（全平局） | 0（全平局） | 0（全平局） | 0（全平局） |

带宽轴读数（CLASSIC_DAX）：仅 **vm4** 对带宽敏感——PEFT 从 endpoint/wide/fast 的 +1.665%
降至 constrained 0.037%、mid 0.068%（慢链路抹平其排序收益）；vm≥8 在 0.125→5.0 MB/s 全带宽
范围内对 CPOP/PEFT 收益几乎无影响。VM=32 同时换用 k=8 fat-tree（见第 6 节保留说明）。

## 4. 响应面 II：异构块（fat-tree-constrained，中位改善%）

### 4.1 CLASSIC_DAX（同构对照取同 VM/网络的 HOMOGENEOUS 条件）

| VM | 异构度 | HEFT 中位 | CPOP med% (w/t/l) | PEFT med% (w/t/l) | PEFT Holm p |
|---:|:--|---:|:--|:--|---:|
| 8 | HOMOGENEOUS | 21.75h | −0.015 (2/0/3) | +0.028 (3/1/1) | 1.000 |
| 8 | HET_MILD | 27.39h | −0.070 (2/0/3) | **−2.454 (0/0/5)** | 0.125 |
| 8 | HET_STRONG | 24.46h | +0.000 (2/1/2) | −2.215 (1/0/4) | 0.750 |
| 8 | HET_EXTREME | 32.86h | −0.127 (2/0/3) | −0.358 (2/0/3) | 1.000 |
| 16 | HOMOGENEOUS | 14.69h | +0.091 (3/0/2) | +0.000 (1/2/2) | 1.000 |
| 16 | HET_MILD | 15.75h | −0.040 (1/1/3) | +0.000 (3/0/2) | 1.000 |
| 16 | HET_STRONG | 22.07h | −0.058 (2/0/3) | **−1.605 (0/0/5)** | 0.125 |
| 16 | HET_EXTREME | 16.67h | −0.030 (1/0/4) | −0.000 (1/2/2) | 1.000 |

CPOP 对异构度不敏感（全部 ≤0.13%）；PEFT 的负偏离集中在 HET_MILD/HET_STRONG，
HET_EXTREME 反而收敛（极端落差下快 VM 选择变得平凡，OCT 分歧缩小——描述性观察）。

### 4.2 SYNTHETIC（2 DAG，纯描述）

| VM | 异构度 | HEFT 中位 | CPOP med% (w/t/l) | PEFT med% (w/t/l) |
|---:|:--|---:|:--|:--|
| 8 | HOMOGENEOUS | 0.75h | +5.224 (1/1/0) | −11.796 (0/1/1) |
| 8 | HET_MILD | 1.13h | +5.368 (1/1/0) | +12.716 (1/0/1) |
| 8 | HET_STRONG | 1.18h | −4.955 (1/0/1) | −15.605 (1/0/1) |
| 8 | HET_EXTREME | 1.18h | +7.740 (2/0/0) | +18.604 (2/0/0) |
| 16 | HOMOGENEOUS | 0.65h | −15.244 (0/1/1) | 0 (0/2/0) |
| 16 | HET_MILD | 1.02h | +0.106 (1/1/0) | +5.744 (1/1/0) |
| 16 | HET_STRONG | 1.01h | +8.084 (1/0/1) | +14.247 (2/0/0) |
| 16 | HET_EXTREME | 0.99h | −3.356 (0/1/1) | −4.026 (0/1/1) |

异构对合成 DAG 的影响在 ±18% 内随条件无序摆动；n=2，符号检验无分辨力，不构成任何推断。

## 5. 描述性要点汇总

1. **带宽轴**：CLASSIC_DAX 同构下带宽仅影响 vm4 的 PEFT（+1.67% → +0.04~0.07%）；
   vm≥8 全部带宽档收益 ≈0。CPOP 全条件对带宽不敏感。
2. **VM 数轴**：CLASSIC_DAX 中 HEFT 中位 makespan 从 vm4 39.9h 单调降至 vm32 11.6h；
   PEFT 退化在 vm≥16 达极限（全平局/±0.00%）；CPOP 在 constrained 网络随 VM 数微升
   （vm16 +0.091%、vm32 +0.170%，3/2/0）——关键路径假设在大 VM 数下仍勉强可用。
3. **异构轴**：CLASSIC_DAX 上 PEFT 负偏离（MILD/STRONG，0/0/5 全负模式），CPOP 稳健；
   SYNTHETIC 无序（见 4.2）。
4. **SYNTHETIC 与 CLASSIC_DAX 行为不同源**：合成层级 DAG 的通信结构使 rank_o 与 rank_u
   差异在调度上可见（第 2.2 节），跨总体不可合并是硬要求而非形式。

## 6. 保留说明（与 PROTOCOL.md 一致）

- 本研究为**描述性响应面**，不做因果归因；所有推断为探索性选定语料推断，非随机负载总体推断。
- VM=32 条件同时把 fat-tree 从 k=4 换为 k=8（k³/4=16 < 32），拓扑与主机池随之改变，
  vm32 的响应面数值含拓扑混杂——只描述，不归因于 VM 数本身。
- SYNTHETIC 层每条件仅 2 个 DAG，符号检验无分辨力；p 值列为 "—" 的 10 行为全平局
  （SYNTHETIC vm16/32 PEFT 同构格，无非平局对，p 无定义），按“无证据”处理。
- 每格单次确定性运行（seed 11）：无重复方差估计；改善%为单种子点估计。
- OCT 的负向激活属语料内观察（5 经典 DAG），未经外部语料校准；就绪表纪律修复不改变
  同构与论文算例行为（冻结证据 504/126 复验 PASSED），但改变了异构条件下的 PEFT 语义，
  与 R12 冻结结论（同构退化）正交。

## 7. 复现

```bash
mvn -pl :workflowsim -am install -DskipTests -q
mvn -pl :workflowsim-experiments exec:java \
  -Dexec.mainClass=org.workflowsim.experiments.network.NetworkStudyExecutor \
  -Dexec.args="full $PWD/datasets $PWD/output/<新目录> sensitivity-r13"
mvn -pl :workflowsim-experiments exec:java \
  -Dexec.mainClass=org.workflowsim.experiments.network.NetworkStudyValidator \
  -Dexec.args="$PWD/output/<新目录>/network-study.json"
# 期望输出：NETWORK_STUDY_VALIDATION PASSED runs=546
```
