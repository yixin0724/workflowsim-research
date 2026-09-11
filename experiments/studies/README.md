# 研究实验目录

本目录用于保存未来研究实验的协议材料，而不是另一个 Maven 模块。一个研究应使用唯一的 `<study-id>` 子目录，例如 `experiments/studies/<study-id>/`，并在开始实现前至少包含：

- `PROTOCOL.md`：研究问题、可主张范围和不可主张范围；
- `MATRIX.md`：输入哈希、平台、算法集合、随机种子/随机化设计与比较组；
- `METRICS.md`：主要与次要指标、单位、统计方法、停止条件；
- `RETENTION.md`：哪些工件保留、保留位置、验证器命令和清理范围。

研究专用 Java 驱动和测试必须位于 Maven 标准目录：

```text
experiments/src/main/java/org/workflowsim/experiments/studies/<study-id>/
experiments/src/test/java/org/workflowsim/experiments/studies/<study-id>/
experiments/studies/<study-id>/
```

稳定的模型能力、通用配置、通用指标或通用证据 API 应先进入 `simulator/`，并由核心测试覆盖；只有与特定研究问题、冻结矩阵或论文协议绑定的代码才应留在此模块。不要为了一个研究暂时方便而让核心依赖实验模块。

每个研究必须使用显式输入位置。对于 reference/study 驱动，推荐传入绝对数据集根目录并在其下使用受检查的逻辑相对路径；不要从当前工作目录猜测项目根。对输出目录采用“新建或显式为空”的契约，写入后使用核心只读验证器核查 manifest/index。

完成研究代码后，至少执行：

```bash
mvn -Pexperiments verify
mvn -Pexperiments clean
```

构建输出、未决定保留的 manifest、指标 JSON、事件日志与临时图表均不是版本化源码。只有在协议中明确保留理由、来源身份和验证命令后，才应把它们视为研究交付物。
