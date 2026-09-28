# 正确性修复后的研究影响复验

状态：三套新协议矩阵已实际执行完成，生产验证器及独立数学复核均通过；指定大幅变化case的隔离控制已完成。本文是**实现修复影响复验**，不是事后补写的预注册实验，也不重新解释旧模型为当前标准算法。

## 执行身份与保护

- 起点：R13 合并提交 `97c66a03101d5bd6e75b67127c5a894b6e656342`。
- 修正后 Java 实现：`8681f9c`，执行声明 `WORK_CONSERVING_TASK_EXECUTION_V2`；LOCAL_PEFT 使用 `PEFT_SUCCESSOR_COST_OCT_EXIT_ZERO_V2`。
- 全部运行使用同样的输入哈希、条件键及原参数矩阵，仅新协议身份和修正后的实现不同。
- 旧 R10/R12/R13 的协议、索引和压缩证据包保持原样；三个旧研究共1176份工件经当前历史完整性校验通过。这不表示旧计算或旧PEFT定义正确。
- 新输出独立保留在 `output/workbench-correctness-repair-97c66a0/studies/`，不覆盖旧目录。完整运行协议、输入、逐运行v4三件套和机器结果表一并保留；源码中的本页仅是解读与保留说明。

## 矩阵完成情况

| 参数矩阵 | 历史协议 | 新协议 | 运行数 | 生产验证状态 |
|---|---|---|---:|---|
| 网络受限 | network-limited-r10-v2 | network-limited-r10-v3 | 504 | COMPLETE / PASSED |
| PEFT比较 | peft-comparison-r12-v1 | peft-comparison-r12-v2 | 126 | COMPLETE / PASSED |
| 敏感性响应面 | sensitivity-response-r13-v1 | sensitivity-response-r13-v2 | 546 | COMPLETE / PASSED |

三个新研究合计1176次。每个条件内输入、资源、模型和种子保持相同；不同来源、VM数、网络和异构条件仍分层，不能把重复条件当独立DAG。

## 与历史数值的逐条件对照

按 `(workflowId, VM数, network, heterogeneity, planner标签, seed)` 对齐，并检查输入SHA一致。下表只描述记录的变化，**不是新的正确性证明或显著性检验**；尤其旧 `LOCAL_PEFT` 标签对应的是已证实非标准递推。

| 矩阵 | 对齐运行 | 仿真结束时间改变 | 五个主要字段任一改变 |
|---|---:|---:|---:|
| R10 | 504 | 161 | 217 |
| R12 | 126 | 97 | 101 |
| R13 | 546 | 414 | 440 |

五字段为仿真结束、逻辑完成、平均/P95等待及平均VM区间利用率。数值逐值变化不等于具有实际意义的差异，也不能把一项变化直接归因于某一个修复；本轮同时纠正了CPU进度、LOCAL数据可达估计及PEFT递推。

观察到部分LOCAL规划场景有较大变化。例如Cybershake100、16VM、endpoint、HEFT从41629.930933秒变为283759.323274秒；在较窄Fat-tree链路下从43362.237990秒变为312137.928208秒。不能因新门禁通过就声称更优，也不能为维持旧赢家调参数。

### Cybershake100 / 16VM / HEFT 的隔离控制

只在单独进程的审计类路径中替换规划基类，不修改已提交源码、带宽或原始研究：

| 控制组合 | endpoint结束秒数 |
|---|---:|
| 原基线完整核心 | 41629.930933 |
| 新执行核心＋旧规划基类 | 41629.930933 |
| 新执行核心＋旧规划仅补根外部输入估计 | 283137.050000 |
| 新执行核心＋完整新规划 | 283759.323274 |

完整新规划在**无争用运行模型**下为41616.562860秒，与它自己的计划终点一致；不能把该无争用预测直接当成端点争用执行时间。证据表明这个case的大幅变化主要来自根输入就绪估计改变后的放置，而不是CPU执行修复把单任务工作量扩大了数倍。

该输入有8个约39–41GB外部输入的ExtractSGT根。旧规划分到8台VM；新规划将其中7个放到VM0，7组共278.145799951GB，在1MB/s入口上的服务下界即278145.799951秒。Task23的输入到达时刻从38738.009440变成271164.806080，后者精确等于 `0.21 + 7 × 38737.79944`。

固定每VM顺序还产生可直接观察的队头阻塞：VM1上的Task3数据在40305.069834已就绪，事件却记录16个空闲VM、0派发；它必须等待被VM0慢根阻塞的计划前缀，直到278543.895748才执行。这238238.825914秒等待中，VM1只忙了115.56秒。因此VM内部queue waiting为0不等于没有调度等待。新旧总计算工作同为3215.75秒，未扩大数倍。完整逐任务及事件证据见[隔离控制报告](../../../output/workbench-correctness-repair-97c66a0/impact-analysis/REPORT.md)。

这是对指定case的控制，不是对所有变化的统一因果归因；模型修正也不保证每个启发式在每个工作负载上变快。R10中的LOCAL策略带每VM顺序，而RANDOM/PSO仅映射、运行时可选择已就绪作业，因此它比较的是完整策略流水线，不能仅解释为映射优化优劣。R12/R13三种LOCAL列表规划器使用相同的顺序执行纪律。

## PEFT 的算法身份

作者公开博士论文第3章明确收录 DOI `10.1109/TPDS.2013.57` 对应文章。印刷p71 Eq7使用后继成本、出口OCT=0，p73 Algorithm1使用ready-list和插入EFT；[一级来源说明](../../../simulator/src/test/resources/dax/peft-paper-example.SOURCE.md)记录了公开来源和转录核对方法。

真正论文夹具已验证PEFT122、HEFT133（计入110.1引导后232.1/243.1）。原HEFT-origin夹具在正确PEFT递推下为85，不是旧76。旧R12/R13关于“同构退化”“异构激活”或PEFT优劣的解释，不能被直接继承为标准PEFT结论。

## 统计与模型解释边界

- 经典来源只有5个DAG、合成来源只有2个，不合并总体。双侧精确符号检验在5对时最小原始p为0.0625，不能达到0.05门槛；不显著不表示等价。
- 随机算法先在每个DAG内部汇总种子，再以DAG配对；相同root seed不自动成为event-keyed CRN。
- 异构轴同时改变总MIPS；VM32还改变Fat-tree的k，跨条件不能作单因素因果归因。
- 必须看逐DAG分布，不能由接近零的中位数推断逐DAG稳定，也不能由makespan单独证明OCT的机制。
- 模型没有真实硬件/网络/价格校准。静态LOCAL规划仍采用无争用估计，不是完整网络事件重放。

## 独立复核与保留位置

[独立工具](../../../scripts/STUDY_AUDIT.md)不导入Java生产函数。最终实际执行结果：

| 独立检查 | 规模 | 结果 |
|---|---:|---|
| 原始输入、Job/Task/事件、CPU下界与指标 | 1176份bundle，25,772,522项规则检查 | 0问题 |
| 规范矩阵、种子聚合、逐DAG效果、精确符号检验/Holm | 3项研究，89,848项检查 | 0问题 |
| 同模型共享条件核心字段 | R10/R12交集84、R10/R13交集84、R12/R13交集126 | 全部一致 |
| checker小型正/负例自测 | 11项 | 全通过 |

检查计数不等于独立样本数。CPU物理容差为1/MIPS量化加绝对/ULP误差，不按绝对时钟比例放宽；汇总数字另用明确的相对/绝对容差。每个bundle列出实际复算的指标与范围外项，逐流网络分配不因缺少完整账本而被冒称已重放。

本轮保留证据（本地生成物，不随普通Git检出提供）：

- [新R10索引](../../../output/workbench-correctness-repair-97c66a0/studies/network-limited-r10-v3/network-study.json)、[R12索引](../../../output/workbench-correctness-repair-97c66a0/studies/peft-comparison-r12-v2/network-study.json)、[R13索引](../../../output/workbench-correctness-repair-97c66a0/studies/sensitivity-response-r13-v2/network-study.json)，各自同目录保留protocol、输入、结果表和全部run三件套。
- [独立证据报告](../../../output/workbench-correctness-repair-97c66a0/independent-evidence-new-v2.json)、[独立统计与逐DAG分布](../../../output/workbench-correctness-repair-97c66a0/independent-statistics-new-v2.json)、[新旧条件对照](../../../output/workbench-correctness-repair-97c66a0/research-impact-deltas.json)。
- [大变化隔离控制](../../../output/workbench-correctness-repair-97c66a0/impact-analysis/REPORT.md)及同目录探针、命令、逐任务CSV和控制JSON。

生产校验器通过、独立算术一致、原文算法复现和真实硬件校准是不同证据层次。本轮完成前三者的明确范围，没有把它们当作真实网络或所有工作负载上的普遍结论。
