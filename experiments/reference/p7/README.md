# P7 兼容参考与历史记录

> P7 是保留的冻结参考矩阵，供开发者核查兼容性与证据身份；**不是当前入门或平台能力展示入口**。当前实验操作见[Workbench 指南](<../../../docs/getting-started/WORKBENCH.md>)。

[参考协议](<../../../docs/experiments/reference-baselines/P7_PROTOCOL.md>)规定输入哈希、固定配置与比较边界。[矩阵实现](<../../src/main/java/org/workflowsim/experiments/reference/p7/P7BaselineMatrix.java>)仅含 Epigenomics n100/n997 两个输入，分别使用 10/50 VM；每个输入各有 H0/H1 两个平台变体和五个在线调度器，共 **20 个运行单元**。

## 显式运行与校验

从项目根目录执行。第一个参数必须是包含 `dax/` 的绝对数据集根；第二个是不存在或为空的绝对输出目录。实验模块默认随 reactor 构建，不需要额外 profile。

```bash
mvn -pl :workflowsim-experiments -am compile exec:java \
  -Dexec.mainClass=org.workflowsim.experiments.reference.p7.P7BaselineExecutor \
  -Dexec.args="/absolute/path/to/WorkflowSim-1.0/datasets /absolute/empty/p7-output"

mvn -pl :workflowsim-experiments -am compile exec:java \
  -Dexec.mainClass=org.workflowsim.experiments.reference.p7.P7EvidenceIndexValidator \
  -Dexec.args="/absolute/p7-output/p7-baseline-index.json"
```

CLI 零参数模式确实存在：它从 `user.dir` 推导 `datasets/` 和带时间戳的输出目录。研究运行仍推荐上面的显式路径，避免 IDE 工作目录造成歧义。验证器本身只读，Maven 编译可能生成构建输出。

新运行写 manifest v4、metrics v2、events v1、index/provenance v3，并记录当前执行语义。协议文档会被动态哈希；本次精简保持协议路径/逻辑 ID 不变，但改变未来文档指纹，不改写任何旧工件。完整基线与 `executeSelection(...)` 的部分选择索引不可混称。保留研究结果时必须保存完整索引及引用 bundle，勿覆盖旧证据。

## 历史 P7-C 数值（原记录保留）

下表来自 P8 执行时序修正后的那次 20 单元历史运行，早于双模块迁移和 provenance v3。原记录称 v2 bundles 与索引通过了当时的校验，随后其临时目录被清理；**这里只保留数值摘要，不能凭本表重新验证已删除的原始 bundle**。当时工作区不是 Git 仓库，也未提供独立源码归档/发行标识，因此不是发行级科学记录，更不是当前代码的黄金值。

当时配置为根种子 `20260901`、参考 MIPS `1000.0`、scale `1.0`、`SHARED`、无聚类/开销/故障、`INVALID` 规划、`SPACE_SHARED`、一 Host 一 VM。H0 全部 1000 MIPS；H1 为等量 500/1500 MIPS。两个输入及哈希继续列在[参考协议](<../../../docs/experiments/reference-baselines/P7_PROTOCOL.md>)中。

数值是包含 stage-in 的仿真结束 makespan，保留原表三位小数。原运行 20/20 单元完成；n100/n997 各运行分别为 101/998 个成功 Job（含一个 stage-in Job），失败 Job 均为 0。

| Scenario | FCFS | Ready-batch RR | Ready-batch MCT | Ready-batch Min-Min | Ready-batch Max-Min |
| --- | ---: | ---: | ---: | ---: | ---: |
| Epigenomics n100, H0 | 54460.681 | 54460.681 | 54460.681 | 60988.007 | 60662.621 |
| Epigenomics n100, H1 | 68995.307 | 72345.640 | 52076.997 | 79812.430 | 80717.935 |
| Epigenomics n997, H0 | 99294.929 | 99294.929 | 99294.929 | 101515.737 | 104594.926 |
| Epigenomics n997, H1 | 117362.463 | 136706.129 | 116997.487 | 120779.425 | 110696.902 |

这些是单一家族、固定抽象模型的描述性结果。确定性重复不提供独立方差样本，原记录没有显著性检验；不能外推跨家族优越性、真实 trace、网络局部性收益、故障恢复或云账单，也不能把历史结果改标为当前执行模型。
