# 快速开始：运行并查看实验

使用 **JDK 17+、Maven 3.6.3+**，在仓库根目录执行以下命令。Workbench 直接读取 JSON 配置，不需要新建 Java 类；查看报告也不需要 Node、Python 或 Web 服务。

## 1. 验证示例配置

[在线调度示例](<../../experiments/configs/online-comparison.json>)使用仓库自带的 Epigenomics 输入，对比同一决策层的调度器。

```bash
mvn -pl :workflowsim-experiments -am compile exec:java \
  -Dexec.mainClass=org.workflowsim.experiments.workbench.Workbench \
  -Dexec.args="validate experiments/configs/online-comparison.json"
```

预检解析输入并检查配置组合，不运行仿真；成功时打印 `WORKBENCH_VALIDATED`。Maven 编译本身会生成构建输出。

## 2. 运行实验

```bash
mvn -pl :workflowsim-experiments -am compile exec:java \
  -Dexec.mainClass=org.workflowsim.experiments.workbench.Workbench \
  -Dexec.args="run experiments/configs/online-comparison.json output/workbench"
```

每次运行创建独立的 `experiment-<UUID>` 子目录，不覆盖上一次实验。结束时控制台打印 `WORKBENCH_REPORT` 和报告路径；若包含失败运行，命令会非零退出，报告保留诊断。

## 3. 打开报告

在文件管理器中打开控制台给出的 `report.html`。报告自包含，可离线查看算法、种子、任务时间线、VM 利用率和依赖图，不加载 CDN。

也可打开输出根目录下的 `index.html` 查看实验历史。macOS 示例：

```bash
open output/workbench/index.html
```

修改配置时注意：`workflowPaths` 相对于**配置文件所在目录**解析。复制配置到其他目录后，应同步调整输入路径。

## 下一步

- [Workbench 配置与报告](<WORKBENCH.md>)：选择模型、证据模式，重建报告或历史。
- [Java API 实验指南](<CODE_CONFIG_EXPERIMENTS.md>)：需要自定义平台、故障或外部策略时使用。
- [算法目录](<../algorithms/CATALOG.md>)与[数据集说明](<../../datasets/README.md>)：选择可比较的算法和输入。
- [构建与验证](<BUILD.md>)、[IDEA 配置](<IDEA_SETUP.md>)：开发环境和质量检查。

仿真指标描述声明的抽象模型；示例完成不代表真实平台校准或算法普遍优越。
