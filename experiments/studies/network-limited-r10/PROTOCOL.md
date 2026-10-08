# R10 历史研究来源与资格说明

> 本页只说明保留的 `network-limited-r10-v2` 研究，不是当前模型协议。当前执行器生成 `network-limited-r10-v3`；运行方式及认证范围见[协议修订](<../../../docs/experiments/NETWORK_STUDY_PROTOCOL_REVISIONS.md>)。旧数据不改写、不重新标记为新执行模型。

## 冻结研究的来源

驱动为 `org.workflowsim.experiments.network.NetworkStudyExecutor`。历史研究比较端点约束、路径链路约束与资源规模变化下的静态调度方案，使用当时已修正的 CPOP 前驱秩和 max-min/分段积分，但早于后续 CPU 工作量和 LOCAL 数据可达时间修正。

| 条件 | 历史声明 |
|---|---|
| 合格输入 | Epigenomics 100/997、CyberShake 100/1000、Inspiral 100；另有四层合成 DAG 32/128 任务，每条文件边 32 MB |
| 资源 | 4/16 VM；1000 MIPS、1 MB/s 端点；一 Host 一 VM、固定放置；Fat-tree k=4 |
| 网络 | 端点争用；0.125 MB/s 受限 Fat-tree；1.25 MB/s 宽链路 Fat-tree；均在 Job-ready 开始传输 |
| 算法/种子 | LOCAL_HEFT、LOCAL_CPOP 每条件一次（11）；RANDOM、PSO 各用 11/29/47/71/101 |
| 运行条件 | LOCAL、STATIC、SPACE_SHARED；无故障、无额外开销 |
| 完整矩阵 | `7 × 2 × 3 × (1 + 1 + 5 + 5) = 504` 个运行单元 |

主指标是仿真结束时间，辅助为逻辑完成、平均/P95 等待和 VM 利用率。随机重复先在同 DAG/资源/网络/算法内汇总，再按 DAG 配对；经典与合成来源分开，使用精确双侧符号检验和三候选比较族的 Holm 调整。样本固定且仅有 5 个经典/2 个合成 DAG，不显著不等价，不外推真实平台。LOCAL 列表规划器带每 VM 顺序，RANDOM/PSO 仅映射，比较的是完整策略流水线。

## 资格排除与失败试点

资格检查针对解析后的输入结构和文件声明，不观察算法效果后筛选。DAX 对重复 INPUT 尺寸采用“首次声明优先并警告”的兼容转换；CyberShake 含这种声明，因此不能把研究输入称为每处原始声明都无歧义。原始文件及其哈希不改动。

Inspiral1000 的 `H1-THINCA-782406919-2048.xml` 在解析后仍有 39368/46451 字节冲突，与 LOCAL 规划器文件模型不兼容。首次 576 单元试点中，两种 LOCAL 规划器 × 2 资源 × 3 网络共 12 次失败，验证器拒绝完整比较。正式 v2 对所有规划器一致排除整个输入，也不纳入随机方法在该输入上的成功结果。

## 可选保留位置与交接

以下路径相对于项目根，记录原工作区保留位置；**普通检出不附带这些文件，也不保证当前机器存在**：

- 失败试点：`output/network-study-r10/`。
- 正式研究：`output/network-study-r10-final/`，含原始 `protocol.json`、`network-study.json`、自动 `results.md`、合成输入及 504 份逐运行 v4 manifest/metrics/events。
- 完整交接包：`output/network-study-r10-evidence.tar.gz`；原交接记录 SHA-256 为 `38d9f8bfd42743706a1719f0942fa07733a3147b739a7332ebf39142edc5a2c3`，本次文档清理未重新计算。

已持有原证据时，可对其索引调用 `NetworkStudyValidator`；该检查证明已注册历史声明和工件的一致性，不证明当前代码重跑相同。当前执行器命令只会生成新协议，必须使用新输出目录；不得覆盖原数据或调整参数维持旧排名。完整交接须包括原始协议字节和全部引用工件，不能只交结果表。

后续修正模型的影响与保留结果见[研究影响复验](<../correctness-repair/RESULTS.md>)；其他历史证据见[研究目录](<../README.md>)。
