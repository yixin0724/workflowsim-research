# 实验模块

本模块的 Maven 坐标为 `org.workflowsim:workflowsim-experiments:1.0`，依赖核心模拟器。它承载 Workbench、教学示例、研究驱动及相应测试；[根 POM](<../pom.xml>)默认构建两个模块，无需额外 profile。

## 运行实验

从项目根目录运行维护中的 JSON 配置入口：

```bash
mvn -pl :workflowsim-experiments -am compile exec:java \
  -Dexec.mainClass=org.workflowsim.experiments.workbench.Workbench \
  -Dexec.args="run experiments/configs/online-comparison.json output/workbench"
```

每次实验使用独立输出目录，控制台打印离线报告位置。首次使用见[快速开始](<../docs/getting-started/QUICK_START.md>)；配置、预检、报告再生和失败处理见[Workbench 指南](<../docs/getting-started/WORKBENCH.md>)。

## 代码与材料放在哪里

| 内容 | 位置与边界 |
| --- | --- |
| Workbench 和 JSON 配置 | `src/main/java/org/workflowsim/experiments/workbench/`、`configs/`；面向日常实验与离线报告 |
| 示例和自建实验类 | `src/main/java/org/workflowsim/` 下的相应包；示例中的底层兼容 API 不自动具备标准运行器证据契约 |
| 研究专用驱动与测试 | Maven 标准源码、测试目录；比较矩阵和研究身份必须明确 |
| 研究协议与保留说明 | `studies/`；这里放文档，不直接放可编译 Java 文件 |

稳定、可复用的模拟语义、配置、指标和通用证据 API 属于核心模块，不应为了单个实验而让核心依赖实验模块。需要 Java 级定制时，使用[Java API 指南](<../docs/getting-started/CODE_CONFIG_EXPERIMENTS.md>)中的运行器和证据写器。

## 研究与验证

新研究先声明输入哈希、平台与模型、同一决策层的比较算法、随机化设计、指标、统计方法、停止条件和输出保留策略，见[研究目录约定](<studies/README.md>)。

```bash
mvn -Pjavadoc clean verify
```

该命令验证两个 Java 模块及核心 Javadoc。Python 独立检查和[离线报告浏览器验收](<../docs/getting-started/BUILD.md#离线报告浏览器验收>)另行执行，完整工具要求见[构建与验证](<../docs/getting-started/BUILD.md>)。

输入来源及标准检出范围见[数据集说明](<../datasets/README.md>)。构建输出和实验产物不是源码；只有按研究协议明确保留、记录来源和验证命令的证据才应作为长期交付物。
