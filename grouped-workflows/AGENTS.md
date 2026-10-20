# grouped-workflows 子项目守则

- 本模块服务**第二篇论文**（执行包调度研究）。研究契约、数据集、英文稿件维护在仓库外部：
  `/Users/yixin/project/workflow-schduleing/docs/second-paper/R2-grouping-placement`——改算法语义前先读该处的方法规格。
- **风格例外**（本目录覆盖根 AGENTS.md 的语言规则）：紧凑英文命名与注释；不写中文 javadoc。
- 与主仓关系：依赖 `simulator` 核心，但资源与文件生命周期独立；**不得反向被核心/实验模块依赖**；不得改变标准 `SimulationRunner` 行为。
- 构建与测试：`mvn -o verify`（离线优先）；核心测试 = `GroupedModelTest`（物理语义 17 用例）、`OptimizerTest`（预算与复现 4 用例）。
- 输入/输出：CLI `GroupedWorkflowCli`（`evaluate|construct|optimize|enumerate|server`），schema 固定 `r2-scenario-v1`；搜索配置走 `SearchConfig` 显式预算（2..10^7 评估数或秒数），禁止隐式无界搜索。
- 修改搜索算法（`SearchEngine`）时必须保持：同种子+同预算 ⇒ 逐位同计划（有既有测试锁定）；算法标签集合是外部契约，新增需同步外部研究契约目录。
