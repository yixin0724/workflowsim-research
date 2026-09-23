# 冻结参考基线实验（P7）

P7 是 WorkflowSim 抽象模型的冻结参考矩阵，用于核查既定 DAX 输入、平台变体和在线 ready-Job 调度算法在同一声明模型下的确定性行为。它不用于重放 WfInstances 生产执行、预测真实云网络，或推导云服务商账单。

完整的科学边界、算法集合、输入哈希、指标定义和停止规则在 [`docs/experiments/reference-baselines/P7_PROTOCOL.md`](../../../docs/experiments/reference-baselines/P7_PROTOCOL.md)；历史基线数值在 [`docs/experiments/reference-baselines/P7_RESULTS.md`](../../../docs/experiments/reference-baselines/P7_RESULTS.md)。

## 代码与数据边界

| 项目 | 位置 |
| --- | --- |
| 冻结目录/执行器源码 | `experiments/src/main/java/org/workflowsim/experiments/reference/p7/` |
| P7 测试 | `experiments/src/test/java/org/workflowsim/experiments/reference/p7/` |
| 数据集根目录 | 任意显式传入的绝对目录；其下必须有 `dax/`、`wfformat/` 或 `wfinstances/` 等子树 |
| P7 DAX 逻辑输入 | `dax/epigenomics/n100/Epigenomics_100.dax` 与 `dax/epigenomics/n997/Epigenomics_997.dax` |

P7 不接受项目根作为数据根。通常应传入 `/absolute/path/to/WorkflowSim-1.0/datasets`，而不是 `/absolute/path/to/WorkflowSim-1.0`。执行器不会从当前工作目录推断这个位置。

## 运行和验证

从项目根目录运行，输出目录必须是绝对且为空的目录：

```bash
mvn -pl :workflowsim-experiments -am \
  -Dexec.mainClass=org.workflowsim.experiments.reference.p7.P7BaselineExecutor \
  -Dexec.args="/absolute/path/to/WorkflowSim-1.0/datasets /absolute/empty/p7-output" \
  compile exec:java

# 只读验证已经保留的 P7 完整冻结基线 index。
mvn -pl :workflowsim-experiments -am \
  -Dexec.mainClass=org.workflowsim.experiments.reference.p7.P7EvidenceIndexValidator \
  -Dexec.args="/absolute/p7-output/p7-baseline-index.json" \
  compile exec:java
```

新生成的 P7 工件使用 manifest v4 和 index/provenance v3 身份契约，区分核心模拟器和 P7/reference 组件，并记录显式数据集根。`P7EvidenceIndexValidator` 继续可读取完整历史 v2/v3 P7 工件；这不表示旧工件自动补齐 v4 新增配置。

运行完成后，先验证输出并提取研究协议要求的结果；未明确作为研究交付物保留的输出目录、Maven `target/`、临时 manifest 和事件日志都必须清理。
