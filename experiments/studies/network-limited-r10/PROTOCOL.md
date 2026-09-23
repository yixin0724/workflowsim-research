# 网络受限调度研究 R10：协议与保留说明

协议ID：`network-limited-r10-v2`。Java驱动位于 `org.workflowsim.experiments.network`；资格检查仅根据输入结构及文件声明，不观察算法效果再挑选样本。

资格检查作用于解析后的模型：DAX解析器对重复INPUT的不同尺寸沿用“首次声明优先并警告”的兼容规则，CyberShake含这类声明；原始文件不修改，哈希保留原始内容。解析后仍存在的INPUT/OUTPUT尺寸冲突会被LOCAL规划器拒绝。该研究比较的是上述明确转换规则下的抽象输入，不是对原始文件每一处声明均无歧义的认证。

## 问题与矩阵

研究问题：在相同的传输起点下，增加路径链路约束、增加资源规模后，各静态调度方案的表现和相对顺序如何变化？

- 经典候选：Epigenomics 100/997、CyberShake 100/1000、Inspiral 100/1000。资格检查发现 Inspiral 1000 的 `H1-THINCA-782406919-2048.xml` 同时被声明为39368和46451字节，与LOCAL规划器平坦文件命名空间不兼容，因此整个输入对所有规划器一致排除。
- 合格输入：5个经典DAX，加4层通信密集的32/128任务合成DAG，合计7个。合成输入按确定性规则生成，每条文件边32MB，不能解释为真实业务测量。
- 资源：4或16台VM，1000 MIPS、1MB/s端点、一Host一VM，固定放置；Fat-tree k=4。
- 网络：端点争用、0.125MB/s受限Fat-tree、1.25MB/s宽链路Fat-tree。三者都在Job-ready统一开始传输，采用相同max-min求解及分段积分。
- 算法：LOCAL_HEFT、LOCAL_CPOP、RANDOM、PSO。前两者每条件一次（seed11），随机方法各使用11/29/47/71/101五个种子。无故障、无额外开销、LOCAL、STATIC、SPACE_SHARED。
- 正式运行量：7输入 × 2规模 × 3网络 × (1+1+5+5) = **504次**。
- CI smoke：论文十任务fixture + 16任务合成DAG，4VM，3网络，随机方法2种子，共36次；不在每次CI执行504次研究。

## 指标与统计

主指标为仿真结束时间，辅以逻辑任务完成时刻、平均/P95等待与VM利用率。每次运行必须逻辑任务全部成功；失败记录保留，完整研究验证器拒绝失败、缺项、重复项或不一致的工件。

随机种子先在同DAG、VM、网络、算法内汇总均值/中位/最小/最大；再把DAG作为配对单位。经典和合成两种来源分别统计。报告相对HEFT的中位改善%、胜平负，精确双侧符号检验，以及同来源/VM/网络三候选比较族的Holm调整。平局容差为相对1e-9；种子复用不称为事件键控共同随机数。

这是固定选定语料的探索性描述。经典组只有5个DAG且不同规模属于相同家族，合成组只有2个，无法主张总体独立随机抽样；p值不支撑对生产平台或全部工作流的外推。无统计显著性也不代表算法等价。不要混合不同平台/网络的绝对时间计算一个总排名。

## 工件与复现

完整证据保存在项目 `output/network-study-r10-final/`。每次运行具有v4 manifest、metrics、events；研究根含protocol、504行运行索引、自动生成结果表、合成输入。验证器核查完整矩阵、每份证据与关键指标，并独立重算汇总统计。

```bash
mvn -pl :workflowsim-experiments -am compile exec:java \
  -Dexec.mainClass=org.workflowsim.experiments.network.NetworkStudyExecutor \
  -Dexec.args="full /absolute/datasets /absolute/new-output"
mvn -pl :workflowsim-experiments -am compile exec:java \
  -Dexec.mainClass=org.workflowsim.experiments.network.NetworkStudyValidator \
  -Dexec.args="/absolute/new-output/network-study.json"
```

输出目录必须不存在。研究未使用训练器。所有物理参数是抽象模型声明，未与真实云网络校准；LOCAL规划器规划时不考虑争用，PSO评估器不使用DAG通信，这些也是比较条件。

## 试点与历史迁移

第一次576次试点保留在 `output/network-study-r10/`：Inspiral1000在两种LOCAL规划器×2资源×3网络下12次失败，验证器正确拒绝完整性并不产生比较推断。资格检查在正式协议v2中加入，原始输入未修改，随机方法在该不兼容输入上的成功数据也不混入公平比较。

旧R8的360次结果仍保留为历史。R10在相同旧参数下重跑成功，但CPOP及网络修正使换冠从3/10变为1/10，因此旧结论不能直接引用为当前证据。

本目录保留协议、解读与摘要；大体积逐运行工件在上述本地输出目录中保留并由 `.gitignore` 排除。需要交接时应打包完整目录，不能只拿汇总JSON冒充完整可校验证据。
