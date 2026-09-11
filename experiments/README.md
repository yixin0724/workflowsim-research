# WorkflowSim 实验模块

`experiments/` 是 `org.workflowsim:workflowsim-experiments:1.0` 模块。它依赖 `simulator/`，用于承载会随教学、参考协议或具体研究问题变化的代码与材料。默认 `mvn verify` 不构建它；需要完整验证时从项目根目录执行：

```bash
mvn -Pexperiments verify
```

这不是第三个产品模块，也不是核心 API 的替代位置。稳定、可复用、需要被下游程序依赖的模拟语义和通用运行/工件 API 应留在 `simulator/`；只有研究或教学特定的驱动程序、固定矩阵和其测试才放在这里。

## 内容分类

| 分类 | 位置 | 目的与边界 |
| --- | --- | --- |
| 历史示例 | `src/main/java/org/workflowsim/examples/**` | 保留原始示例 FQCN，便于教学和兼容性探索。它们可能使用历史 API/相对路径，不能自动作为已认证研究入口。 |
| 教程与交叉校验 | `src/main/java/org/workflowsim/examples/**`、`src/test/java/org/workflowsim/experiments/tutorials/**` | 展示 DAX/WfCommons 输入、解析器交叉校验和端到端冒烟路径。教程结果不是冻结基线。 |
| 参考实验 | `src/main/java/org/workflowsim/experiments/reference/**` | 已冻结配置、输入哈希、算法集合和证据规则的可复核参考实现。P7 位于其 `p7` 子包。 |
| 研究实验 | `src/main/java/org/workflowsim/experiments/studies/<study-id>/**` 与 `studies/<study-id>/` | 面向一个明确研究问题的驱动代码、测试以及协议/矩阵/结果保留说明。每项研究必须有自己的标识与工件边界。 |

`reference/` 和 `studies/` 根目录下的 Markdown 是面向研究者的协议材料；相应 Java 源码仍必须放进 Maven 标准 `src/main/java/` 或 `src/test/java/`。不要把可编译 Java 文件直接放入 `experiments/studies/` 文档目录。

## 运行方式

实验模块从根 Reactor 启动，以便 Maven 先构建其核心依赖：

```bash
# 运行一个历史或教程示例
mvn -Pexperiments -pl :workflowsim-experiments -am \
  -Dexec.mainClass=org.workflowsim.examples.wfcommons.WfCommonsSimulationExample1 \
  compile exec:java

# 运行 P7 冻结参考矩阵；第一个参数必须是绝对 datasets 根目录。
mvn -Pexperiments -pl :workflowsim-experiments -am \
  -Dexec.mainClass=org.workflowsim.experiments.reference.p7.P7BaselineExecutor \
  -Dexec.args="/absolute/path/to/WorkflowSim-1.0/datasets /absolute/empty/output" \
  compile exec:java
```

P7 的详细执行与验证规则见 [`reference/p7/README.md`](reference/p7/README.md)。历史示例保留以项目根为当前工作目录的相对 `datasets/...` 路径契约；P7/reference 不依赖此契约，而是通过显式数据集根目录解析逻辑路径。

## 新建研究的最低要求

每个新研究应在写代码前冻结以下内容：研究问题、比较算法的同一决策层、工作流输入哈希、平台配置、随机化设计、主要/次要指标、统计方法、停止条件和输出保留决策。详细模板和目录约定见 [`studies/README.md`](studies/README.md) 与 [`../docs/experiments/CAMPAIGNS.md`](../docs/experiments/CAMPAIGNS.md)。

实现后至少执行 `mvn -Pexperiments verify`。实验输出、`target/`、临时 manifest 和日志都不是源码；除非研究协议明确要求保留且已通过只读验证器，否则应在检查后清理。
