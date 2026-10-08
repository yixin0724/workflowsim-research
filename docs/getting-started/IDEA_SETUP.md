# IntelliJ IDEA 配置

## 导入 Maven 工程

1. 打开仓库根目录或[根 POM](<../../pom.xml>)，以 Maven 工程导入。
2. 在 Maven 工具窗口执行 **Reload All Maven Projects**。
3. 确认存在 `workflowsim` 与 `workflowsim-experiments` 两个模块。它们属于默认 Reactor，无需额外的 `experiments` profile。
4. 确认各模块的 `src/main/java`、`src/test/java` 被识别为源码和测试源码目录。

图标颜色因 IDEA 版本和主题而异；以 Maven 同步、依赖解析、代码补全和 Run 操作为准，不手动标记目录来替代正确导入。

## 设置 JDK 与 Maven

- Project SDK、Maven importer 和 Maven runner 使用 **JDK 17+**。
- Maven 使用 **3.6.3+** 的本地安装或满足要求的 IDE 内置版本。
- POM 的 `release=8` 表示编译产物兼容 Java 8 API，不表示可以用 JDK 8 构建。

## 运行 Workbench

打开 [Workbench](<../../experiments/src/main/java/org/workflowsim/experiments/workbench/Workbench.java>)，在 **Run → Edit Configurations → Application** 中设置：

| 配置项 | 值 |
| --- | --- |
| Main class | `org.workflowsim.experiments.workbench.Workbench` |
| Use classpath of module | `workflowsim-experiments` |
| Working directory | `$PROJECT_DIR$`，即仓库根目录 |
| JRE | JDK 17+ |
| Program arguments | `validate experiments/configs/online-comparison.json` |

预检成功后，把 Program arguments 改为：

```text
run experiments/configs/online-comparison.json output/workbench
```

命令参数中的配置和输出路径相对于工作目录；配置内部的 `workflowPaths` 相对于配置文件所在目录。运行及报告规则见[Workbench 指南](<WORKBENCH.md>)。

## 运行自建类与测试

[Java API 指南](<CODE_CONFIG_EXPERIMENTS.md>)提供完整自建类示例。创建后，Application 的主类改为 `org.workflowsim.mystudy.MyExperiment`，仍选择实验模块 classpath 和项目根工作目录；也可右键 `main` 运行。

JUnit 测试放在对应模块的 `src/test/java/`，用 JUnit 配置运行。测试源码中的辅助入口不在 `compile exec:java` 默认类路径内，命令行使用方式见[构建与验证](<BUILD.md>)。

## 排查

- **实验模块未出现**：确认导入根 POM，而非仅导入[核心 POM](<../../simulator/pom.xml>)；取消 Maven 工具窗口中的 Ignored 标记，再重新加载。
- **依赖报红或找不到类**：先核对 Maven 同步结果、构建 JDK、源码位置和运行 classpath；这些均正确后才考虑重建 IDE 索引。
- **找不到输入**：核对 Working directory，以及配置内部的相对路径基准；可用输入见[数据集说明](<../../datasets/README.md>)。
- **命令行成功但 IDE 失败**：逐项比较 JDK、模块、工作目录和 Program arguments；用[快速开始](<QUICK_START.md>)中的相同配置对照。
