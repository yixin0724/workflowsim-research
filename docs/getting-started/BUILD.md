# 构建、测试与验证

以下命令从仓库根目录执行。日常运行实验从[快速开始](<QUICK_START.md>)进入；本文集中说明开发与验证工具，不要求新用户先执行全部质量检查。

## 环境与模块

| 工具 | 要求 | 用途 |
| --- | --- | --- |
| JDK | 17+ | Maven 构建和 Java 仿真 |
| Maven | 3.6.3+ | 编译、测试、Javadoc、运行入口 |
| Python | 3.9+，标准库即可 | 独立证据检查器及自测；CI 使用 3.12 |
| Node.js | 20+ | 离线报告浏览器验收；CI 使用 22 |
| Chrome 或 Playwright Chromium | 已安装的兼容浏览器 | 浏览器验收，不是仿真依赖 |

[根 POM](<../../pom.xml>)默认包含两个模块，无需启用 `experiments` profile：

| 模块 | Maven 坐标 | 职责 |
| --- | --- | --- |
| 核心模拟器 | `org.workflowsim:workflowsim:1.0` | 模型、运行器、指标和通用证据 API |
| 实验模块 | `org.workflowsim:workflowsim-experiments:1.0` | Workbench、示例和研究驱动 |

编译使用 `--release 8`，产物保持 Java 8 API 兼容；这不降低构建 JDK 的要求。

```bash
java -version
mvn -v
```

## Java 构建与测试

| 任务 | 命令 |
| --- | --- |
| 编译两个模块 | `mvn compile` |
| 两个模块的单元/语义测试 | `mvn test` |
| 两个模块的单元、集成及覆盖率门禁 | `mvn verify` |
| 清理后验证，包含核心 API 文档 | `mvn -Pjavadoc clean verify`，或 `make audit` |
| 仅验证核心 | `mvn -pl :workflowsim verify` |
| 仅编译核心 | `mvn -pl :workflowsim compile` |
| 清理两个模块的构建输出 | `mvn clean` |

Surefire 在 `test` 阶段执行单元/语义测试，排除 `*IntegrationTest.java`；Failsafe 在 `integration-test`/`verify` 阶段执行集成测试。定向检查示例：

```bash
mvn -pl :workflowsim -Dtest=TaskCostMatrixTest test
mvn -pl :workflowsim \
  '-Dtest=TaskCostMatrixTest#getCostSecondsReturnsRegisteredEntries' test
mvn -pl :workflowsim -Dit.test=SimulationEvidenceIntegrationTest verify
```

最后一条命令仍执行核心单元测试与 `verify` 门禁，只限定集成测试选择。定向测试不能代替完整验证。

报告位于各模块的 `target/surefire-reports/`、`target/failsafe-reports/`、`target/site/jacoco/` 和 `target/site/jacoco-it/`。以实际退出状态和报告为准，不用固定测试数量判断成功。JaCoCo 门槛由[核心 POM](<../../simulator/pom.xml>)与[实验 POM](<../../experiments/pom.xml>)维护。

标准检出包含门禁必需的小型输入，缺失时测试失败；完整大型语料不是默认前提，见[数据集说明](<../../datasets/README.md>)。`mvn clean` 只清理构建目录，不清理自定义实验输出或保留证据。

## Python 独立检查器自测

这些测试不导入 Java 生产算法，也不以 Maven 测试替代。完整运行以下八个套件：

```bash
PYTHONDONTWRITEBYTECODE=1 python3 scripts/test-study-verifiers.py
PYTHONDONTWRITEBYTECODE=1 python3 scripts/test-network-ledger.py
PYTHONDONTWRITEBYTECODE=1 python3 scripts/test-file-lifecycle.py
PYTHONDONTWRITEBYTECODE=1 python3 scripts/test-file-lifecycle-context.py
PYTHONDONTWRITEBYTECODE=1 python3 scripts/test-storage-lifecycle.py
PYTHONDONTWRITEBYTECODE=1 python3 scripts/test-storage-lifecycle-context.py
PYTHONDONTWRITEBYTECODE=1 python3 scripts/test-dataflow-assignment.py
PYTHONDONTWRITEBYTECODE=1 python3 scripts/test-dataflow-profile.py
```

自测验证检查器的正例与反例，不证明任意真实研究已通过审计。[CI 配置](<../../.github/workflows/ci.yml>)另外生成 Java 的 V1 网络、V2 文件、V3 存储和在线绑定证据，再用 Python 检查实际产物；对应生产夹具和跨语言验收命令以该文件为准。`mvn verify` 只覆盖 Java 门禁，不包含这些 Python 检查。

## 文档检查

```bash
python3 -B scripts/test-check-docs.py
python3 -B scripts/check-docs.py
# 需要机器可读诊断时：
python3 -B scripts/check-docs.py --json
```

检查器通过Git元数据发现现有项目Markdown，排除依赖、构建输出和本地实验结果；验证本地目标、Markdown锚点及来源文本行号，不访问外网。缺失目标、错误锚点或不支持的引用形式会产生带文件/行号的诊断并以非零退出。语义和命令仍需对照源码，规范见[文档与源码注释要求](<../advanced/CODE_STYLE.md>)。

## 离线报告浏览器验收

检查器通过 `file://` 打开真实生成的报告，不启动服务。依赖版本固定在[浏览器依赖清单](<../../scripts/package.json>)与[锁文件](<../../scripts/package-lock.json>)。

```bash
npm ci --prefix scripts --ignore-scripts --no-audit --no-fund

# 已有可用 Chrome 时可省略；Linux CI 使用 install --with-deps chromium。
scripts/node_modules/.bin/playwright-core install chromium

mvn -pl :workflowsim-experiments -am test-compile exec:java \
  -Dexec.mainClass=org.workflowsim.experiments.workbench.WorkbenchBrowserFixtures \
  -Dexec.classpathScope=test \
  '-Dexec.args="/absolute/checkout" "/absolute/new-browser-output"'

node scripts/verify-report.cjs /absolute/new-browser-output/browser-fixtures.json
node scripts/test-report-checker.cjs /absolute/new-browser-output/browser-fixtures.json
```

替换两个绝对路径占位符，输出目录必须尚不存在。该测试入口在测试源码中，因此需要 `test-compile` 和 `-Dexec.classpathScope=test`。

矩阵覆盖算法/种子标签、多种子、单报告再生、失败状态、大图，以及 V1/V2/V3 和在线动作面板。检查运行选择、VM/任务筛选、依赖聚焦、图形截断、390px 布局、精确数字、无浏览器错误和无额外网络/文件读取；反例脚本确认这些检查确实能失败。Java 测试不执行浏览器 JavaScript，不能替代此步骤。

macOS 优先使用已安装的 Google Chrome；否则使用 Playwright Chromium。任意系统均可通过 `WORKFLOWSIM_CHROME` 指定浏览器可执行文件：

```bash
WORKFLOWSIM_CHROME="/absolute/path/to/chrome" \
  node scripts/verify-report.cjs /absolute/experiment/report.html
```

[报告检查器](<../../scripts/verify-report.cjs>)还接受可选截图参数：单报告传截图文件路径，矩阵传截图目录。Node 和浏览器只用于验收，不是生成报告的必需依赖；阅读报告只需普通浏览器。

## 运行 Java 入口

实验入口使用 `-pl :workflowsim-experiments -am compile exec:java`，由Reactor先构建当前核心依赖再执行。不要省略`compile`直接调用`exec:java`：它可能解析本地Maven仓库中的旧核心JAR，导致当前源码已有的模型枚举在运行时仍不被识别。遇到这种情况应重建正确的模块/类路径，而不是改写证据中的模型标签。Workbench的四个命令见[操作指南](<WORKBENCH.md>)；自建Java类见[代码指南](<CODE_CONFIG_EXPERIMENTS.md>)。

使用仓库自带 JSON 输入的示例：

```bash
mvn -pl :workflowsim-experiments -am compile exec:java \
  -Dexec.mainClass=org.workflowsim.examples.wfcommons.WfCommonsSimulationExample1 \
  -Dexec.args="datasets/wfformat/montage/n100/montage-100-000.json"
```

## 证据校验命令

核心模块默认跳过 `exec:java`；运行核心校验器时启用 `core-exec`：

```bash
# 单次运行证据包
mvn -Pcore-exec -pl :workflowsim -am compile exec:java \
  -Dexec.mainClass=org.workflowsim.experiment.ExperimentArtifactValidator \
  '-Dexec.args="/absolute/output/run.manifest.json"'

# 通用 campaign index
mvn -Pcore-exec -pl :workflowsim -am compile exec:java \
  -Dexec.mainClass=org.workflowsim.experiment.ExperimentCampaignValidator \
  '-Dexec.args="/absolute/output/experiment-campaign-index.json"'

# 独立检查 V1/V2/V3 证据及适用的在线绑定上下文
PYTHONDONTWRITEBYTECODE=1 python3 scripts/verify-network-ledger.py \
  /absolute/output/run.manifest.json --json
```

独立网络检查器用于已启用匹配网络/生命周期记录的证据包，不应把普通 OFF 证据包当作其完整捕获输入。校验器只读目标证据，但命令中的 Maven 编译仍会写构建输出。校验成功只证明声明范围内的结构、哈希、上下文或语义约束，不证明算法最优或真实平台校准。研究指标与统计检查另见[独立审计工具](<../../scripts/STUDY_AUDIT.md>)。

## API 文档与环境排查

```bash
mvn -pl :workflowsim -Pjavadoc verify
# macOS；其他系统在文件管理器中打开相同路径。
open simulator/target/reports/apidocs/index.html
```

Javadoc 检查维护中的 WorkflowSim API，不包含上游 CloudSim 注释。IDE 类路径或导入问题见[IDEA 配置](<IDEA_SETUP.md>)。输入不存在时，先区分 Java 入口的工作目录与 Workbench 配置文件的相对路径基准；不要通过切换未声明的模型或跳过校验来消除错误。
