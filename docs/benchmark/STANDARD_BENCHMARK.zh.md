# 标准基准 `benchmark-standard-v1`

[English](<STANDARD_BENCHMARK.md>) · **简体中文**

本平台首个公开冻结基准：在冻结条件下对维护中算轨族群做一次标准对照，每个数字背后都有逐位的 manifest-v4 证据包，任何第三方都能用本仓库的 Python 审计器独立复算验证。

运行矩阵：5 个合格输入(Epigenomics-100/997、CyberShake-100/1000、Inspiral-100)×
2 个资源档(8/32 VM)× 3 个决策层(13 个算法策略)，seed 11——**160 次运行，160 次全部完成**，全部通过审计。

## 标准结果(各策略 × 各档 makespan 中位数，秒)

| 层 | 策略 | 8-VM 中位(s) | 32-VM 中位(s) | 条件样本 |
|---|---|---:|---:|---:|
| SHARED_DAG | SHARED_STORAGE_HEFT | 7780 | 4012 | 5 |
| SHARED_DAG | SHARED_STORAGE_CPOP | 7863 | 4156 | 5 |
| SHARED_DAG | SHARED_STORAGE_DLS | 7780 | 4003 | 5 |
| SHARED_DAG | SHARED_STORAGE_ETF | 7779 | 4003 | 5 |
| SHARED_DAG | SHARED_STORAGE_PEFT | 7813 | 4077 | 5 |
| LOCAL_DAG | LOCAL_HEFT | 297949 | 180467 | 10 |
| LOCAL_DAG | LOCAL_CPOP | 160629 | 160629 | 10 |
| LOCAL_DAG | LOCAL_PEFT | 294792 | 188109 | 10 |
| ONLINE | FCFS | 44682 | 41747 | 5 |
| ONLINE | READY_BATCH_MINMIN | 44718 | 41741 | 5 |
| ONLINE | READY_BATCH_MAXMIN | 44678 | 41747 | 5 |
| ONLINE | READY_BATCH_MCT | 44682 | 41747 | 5 |
| ONLINE | READY_BATCH_ROUNDROBIN | 44682 | 41747 | 5 |

LOCAL_DAG 中位数跨越两种网络族(端点争用 + 0.125 MB/s 受限 fat-tree)，每条 5 输入 × 2 模型；其余层每层只用一种已声明模型（精确对应 CATALOG 的组合矩阵行）。数字只描述已声明的模型族——边界声明见下。

## 第三方验证方法(无需 Java)

```bash
cd output/benchmark-standard-v1
PYTHONDONTWRITEBYTECODE=1 python3 ../../scripts/verify-study-evidence.py \
  --output /新路径/benchmark-audit.json \
  $(python3 -c "import json; print(' '.join('runs/' + r['runId'] + '/' + r['manifest'].split('/')[-1] for r in json.load(open('benchmark.json'))['runs']))")
```

或任意子集。每个运行都携带完整的 v4 证据包(manifest/metrics/events)，已通过标准校验与独立审计——160/160 零 issue。

## 冻结矩阵定义

- **输入**（见 [BenchmarkExecutor](<../../experiments/src/main/java/org/workflowsim/experiments/benchmark/BenchmarkExecutor.java>)）
- **VM 档**：8(fat-tree k=4, 16主机)、32(fat-tree k=8, 128主机)；同构 1000 MIPS、1 PE、SPACE_SHARED
- **协议**：`benchmark-standard-v1`，seed 11，确定性算法单种子点估计（多种子=重复，不是独立样本）
- **仅同层可比较**（三个决策层是各自独立的族群宇宙）
- **LOCAL_DAG 胞元**用 `PRE_EXECUTION_TRANSFER_DELAY_WITH_CONTENTION_V1`（端点）与 `PRE_EXECUTION_TRANSFER_DELAY_WITH_FAT_TREE_CONTENTION_V1`（fat-tree）
- **SHARED_DAG 胞元**用 `LEGACY_WORKFLOWSIM_V1` + `SHARED` 副本目录
- **ONLINE 胞元**用 `LEGACY_WORKFLOWSIM_V1` + FCFS/READY_BATCH_* 在线标签

## 边界声明（本基准不证明什么）

1. 数字描述**已声明的模拟模型**在特定冻结平台上的行为——不是预测生产集群，也不可外推到其他硬件。
2. 三个决策层**跨层不可比**（不同决策阶段、不同模型族），本表不对它们互相排名。
3. 这 160 胞元度量的是 SHARED-legacy / LOCAL-fat-tree 冻结法域范围；不作其他网络模型的主张，也无多种子统计推断。
4. LOCAL v32 使用 k=8 fat-tree（32 主机必需）——VM 数与拓扑档同步变化（协议中已文档化的混杂）。

## 保留

完整证据包（160 运行 × v4 证据三件套）保留在 `output/benchmark-standard-v1/`
并打包为 `output/benchmark-standard-v1-evidence.tar.gz`（21 MB），永不改写、永不重贴标签。
