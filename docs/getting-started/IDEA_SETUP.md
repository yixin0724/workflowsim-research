# IDEA 配置指南

## 导入默认双模块工程

[根 POM](../../pom.xml) 已将 `simulator/` 与 `experiments/` 同时列入默认 Reactor。
**不需要启用 `experiments` profile；当前 POM 没有这个 profile。**

1. 在 IDEA 中打开仓库根目录或根 POM，以 Maven 工程导入。
2. 在 Maven 工具窗口执行 **Reload All Maven Projects**。
3. 确认模块列表包含 `workflowsim` 与 `workflowsim-experiments`。
4. 确认各模块的 `src/main/java`、`src/test/java` 分别识别为源码与测试源码目录。

图标颜色会随 IDEA 版本和主题变化。判断导入是否成功，应检查源码目录、依赖解析、代码补全
以及 `main` 方法是否提供 Run 操作。

## JDK 与 Maven

构建要求 **JDK 17+、Maven 3.6.3+**；POM 的 `release=8` 控制编译产物的 Java 8 API 兼容性。

- Project SDK 选择 JDK 17 或更高版本。
- Maven importer 与 Maven runner 的 JDK 同样选择 17+。
- Maven 使用满足最低版本要求的本地安装或 IDEA 自带版本。

不要因为产物兼容 Java 8 而把 Maven 构建 JDK 改为 8；构建前的 Enforcer 会拒绝该配置。

## 运行仓库示例

打开 [WorkflowSimBasicExample1.java](../../experiments/src/main/java/org/workflowsim/examples/WorkflowSimBasicExample1.java)，
右键其 `main` 方法运行，或通过 **Run → Edit Configurations → Application** 配置：

| 配置项 | 值 |
| --- | --- |
| Main class | `org.workflowsim.examples.WorkflowSimBasicExample1` |
| Use classpath of module | `workflowsim-experiments` |
| Working directory | `$PROJECT_DIR$`，即包含根 POM 与 `datasets/` 的仓库根目录 |
| JRE | JDK 17+ |

历史示例使用从项目根解析的 `datasets/...` 相对路径。P7/reference 驱动则使用显式的绝对
数据集根目录，参数说明见[构建指南](BUILD.md)。

## 运行自己的实验类

在 `experiments/src/main/java/org/workflowsim/mystudy/` 下新建实验类，使用
[快速上手](QUICK_START.md)或[完整运行指南](RUN_EXPERIMENTS.md)中的模板。
运行配置仍选择实验模块 classpath 和项目根工作目录。

如果写的是核心 JUnit 测试，将其放在 `simulator/src/test/java/`，通过 JUnit 配置运行。
不要把测试源码目录中的类当作 `compile exec:java` 默认运行时类路径的一部分。

## 导入与运行排查

### experiments 模块没有出现

确认导入的是根 POM，而不是仅导入 [simulator POM](../../simulator/pom.xml)。检查 Maven
工具窗口中是否将实验模块标记为 Ignored；取消忽略后重新加载。移除旧运行配置中遗留的
`experiments` profile 选择。

### Java 文件不能运行或依赖报红

先检查 Maven 同步是否成功、构建 JDK 是否正确、模块是否存在。必要时重新打开根 POM；
只有模块与依赖均正确但索引仍异常时，再使用 **Invalidate Caches / Restart**。
手动标记 Sources Root 不能替代正确的 Maven 模块导入，重新加载时还可能被覆盖。

### 找不到输入文件

检查 Application 的 Working directory。运行命令及完整路径示例见
[数据集说明](../../datasets/README.md)。标准检出只带质量门禁使用的部分 JSON 语料，
完整语料路径是否存在需单独核对。

### 命令行正常，IDEA 运行失败

核对 IDEA 的 JDK、模块 classpath、工作目录和程序参数是否与命令行一致。
可从项目根运行以下已存在的入口进行对照：

```bash
mvn -pl :workflowsim-experiments -am \
  -Dexec.mainClass=org.workflowsim.examples.WorkflowSimBasicExample1 \
  compile exec:java
```

## 相关文档

- [构建与测试命令](BUILD.md)
- [实验代码模板](RUN_EXPERIMENTS.md)
- [代码配置与证据工件](CODE_CONFIG_EXPERIMENTS.md)
- [算法选择](ALGORITHMS.md)
