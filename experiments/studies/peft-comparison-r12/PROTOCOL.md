# PEFT 对比研究（S5）：协议与保留说明

协议ID：`peft-comparison-r12-v1`。Java驱动复用 `org.workflowsim.experiments.network`（NetworkStudyPlan/Executor/Validator 的 peft-comparison 变体）；这是 R12 方法贡献（LOCAL_PEFT）落地后的第一次正式对比。

## 与 R10 的关系

本研究不改动 R10 协议 `network-limited-r10-v2` 及其 504 次冻结证据（`output/network-study-r10-final/`），也不重跑 RANDOM/PSO。它在完全相同的冻结矩阵（工作流、平台、网络、种子）上只替换算法集合为三个确定性列表规划器 LOCAL_HEFT、LOCAL_CPOP、LOCAL_PEFT，各自每条件运行一次（seed 11）。输入资格检查、排除规则（Inspiral 1000 的 CONFLICTING_FILE_SIZE 全量排除）与 R10 一致。

## 问题与矩阵

研究问题：在同一冻结矩阵与同构平台上，R12 新增的通信感知 PEFT（LOCAL_PEFT）相对 HEFT/CPOP 的 makespan 表现如何？

- 合格输入：与 R10 相同的5个经典DAX（Epigenomics 100/997、CyberShake 100/1000、Inspiral 100）加2个确定性合成DAG（layered-32/128），合计7个。
- 资源：4或16台VM，1000 MIPS、1MB/s端点、一Host一VM，固定放置；Fat-tree k=4。
- 网络：端点争用、0.125MB/s受限Fat-tree、1.25MB/s宽链路Fat-tree；三者都在Job-ready统一开始传输，采用相同max-min求解及分段积分。
- 算法：LOCAL_HEFT、LOCAL_CPOP、LOCAL_PEFT，均确定性，每条件一次（seed 11）。无故障、无额外开销、LOCAL、STATIC、SPACE_SHARED。
- 正式运行量：7输入 × 2规模 × 3网络 × 3算法 × 1种子 = **126次**。
- CI smoke：论文十任务fixture + 16任务合成DAG，4VM，3网络，3算法，共18次。

## 已知平台限制（必须与结果一同呈现）

平台VM同构（全部1000 MIPS、1 PE）。PEFT 的 EFT+OCT 前瞻项因此对所有候选VM取相同值，前瞻无法区分VM；LOCAL_PEFT 与 LOCAL_HEFT 的差异只来自任务排序（rank_o 对 rank_u），不来自插入式映射。本研究结论不能用于论证 OCT 前瞻在异构平台上的价值；那需要 R13 敏感性响应面或异构平台实验。LOCAL_PEFT 的论文复现保证由独立单测维持（论文fixture精确OCT表与映射）。

## 指标与统计

主指标为仿真结束时间，辅以逻辑任务完成时刻、平均/P95等待与VM利用率。每次运行必须逻辑任务全部成功；失败记录保留，验证器拒绝失败、缺项、重复项或不一致的工件。

三个规划器均确定性，每条件单次运行；把DAG作为配对单位（无种子重复可汇总）。经典与合成两种来源分别统计。报告相对LOCAL_HEFT的中位改善%、胜平负，精确双侧符号检验，以及同来源/VM/网络两候选比较族的Holm调整。平局容差为相对1e-9。

这是固定选定语料的探索性描述：经典组5个DAG且不同规模属相同家族，合成组2个，无法主张总体独立随机抽样；p值不支撑对生产平台或全部工作流的外推；无统计显著性也不代表算法等价。

## 工件与复现

完整证据保存在项目 `output/peft-comparison-r12/`（`.gitignore` 排除，不入库；交接用压缩包）。每次运行具有v4 manifest、metrics、events；研究根含protocol、126行运行索引、自动生成结果表、合成输入。验证器核查完整矩阵、每份证据与关键指标，并独立重算汇总统计。

```bash
mvn -pl :workflowsim-experiments -am compile exec:java \
  -Dexec.mainClass=org.workflowsim.experiments.network.NetworkStudyExecutor \
  -Dexec.args="full /absolute/datasets /absolute/new-output peft-comparison"
mvn -pl :workflowsim-experiments -am compile exec:java \
  -Dexec.mainClass=org.workflowsim.experiments.network.NetworkStudyValidator \
  -Dexec.args="/absolute/new-output/network-study.json"
```

输出目录必须不存在。研究未使用训练器。所有物理参数是抽象模型声明，未与真实云网络校准。
