# 统一实验入口与离线报告

平台提供一个 Java 命令入口 `org.workflowsim.experiments.workbench.Workbench`。使用 JSON 配置选择输入、资源和同一决策层的算法，不需要新建 Java 实验类；报告是自包含 HTML，可双击离线查看，不启动服务、不加载 CDN。

## 从示例开始

在项目根目录，用 JDK 17+、Maven 3.6.3+：

```bash
# 预检：解析输入并验证算法/模型组合，尚不运行仿真
mvn -pl :workflowsim-experiments -am compile exec:java \
  -Dexec.mainClass=org.workflowsim.experiments.workbench.Workbench \
  -Dexec.args="validate experiments/configs/online-comparison.json"

# 运行：每次创建唯一子目录，保留以前的结果
mvn -pl :workflowsim-experiments -am compile exec:java \
  -Dexec.mainClass=org.workflowsim.experiments.workbench.Workbench \
  -Dexec.args="run experiments/configs/online-comparison.json output/workbench"

# 网络研究示例
mvn -pl :workflowsim-experiments -am compile exec:java \
  -Dexec.mainClass=org.workflowsim.experiments.workbench.Workbench \
  -Dexec.args="run experiments/configs/network-comparison.json output/workbench"
```

输出根目录中的 `index.html` 是实验历史；每个 `experiment-<UUID>/report.html` 是该实验的交互报告。可选择运行、筛选 VM/任务、悬停查看执行时刻。页面同时展示方案对比、等待时间、VM利用率和任务依赖图；依赖图概览最多80个节点，可以选择任务查看其直接前驱和后继。时间线最多同时绘制250个作业，表格保留全部计算作业。

这是新的维护入口；R9 删除的 `MyConfigurableExperiment`、旧 HTML/CSV 写器仍然不存在。新报告从经过校验的 v4 证据生成。

## 配置规则

示例在 `experiments/configs/`。路径相对于配置文件所在目录，支持绝对路径。

- `schema` 固定为 `workflowsim-workbench-v1`；`name` 是显示名称。
- `workflowPaths` 是1至32个DAX/JSON输入；可选 `workflowArrivalSeconds` 必须与输入等长，默认全零。
- `platform.vmMips` 声明每台 VM 的计算速度；每台固定到一个独立 Host，单核、SPACE_SHARED。VM数量为1至64。
- `platform.bandwidthMbPerSecond` 是正整数端点带宽。`networkTopology` 可声明 `k`、`linkBandwidthMbPerSecond`、`coreSwitchCount`、`hostEdgePlacements`。界面配置限制 k≤16，核心API仍有自己的模型范围。
- `simulation.fileSystem` 为 `SHARED` 或 `LOCAL`；`dataMovementModel` 使用完整枚举名。五种模型均支持，固定端点模型额外提供 `fixedEndpoint` 对象的 `accessBandwidth`、`latencySeconds`、`sourceBandwidth`。
- 可设置 `runtimeScale`、`runtimeReferenceMips`、`minEventIntervalSeconds`、`deadlineSeconds`；截止时间是事后观察。
- 可选 `taskCostMatrix` 是 `{taskId, vmId, executionSeconds}` 对象数组，须覆盖全部逻辑任务×VM，仅适用于允许该矩阵的静态轨道。
- `algorithms` 每项有唯一安全 `id`，以及 `scheduler` 或 `planner`。有 planner 时默认 STATIC；无 planner 时默认 FCFS。
- `seeds` 是1至100个互不重复的整数，默认 `[42]`。总运行数≤500。确定性算法多种子结果仅作重复性观察，不计作独立统计样本。

未知字段、字符串冒充数字、小数整数、重复算法ID、跨决策层混比、无效模型组合、缺失输入、独立任务算法输入含边等会被拒绝。该入口当前针对无故障、无额外开销实验；故障/开销研究继续使用完整 Java API，不能用未知JSON字段悄然开启。RL_POLICY 也继续通过 Java `RlEnvironment` 接入外部策略；平台不训练模型。

## 历史与证据

每次实验包含：

```text
experiment-<UUID>/
  configuration.json       # 用户提交的配置
  experiment.json          # 运行状态、摘要、相对证据路径
  report.html              # 自包含报告
  runs/<algorithm>-s<seed>/
    result.manifest.json   # v4完整条件与结果
    result.metrics.json
    result.events.jsonl
```

逐运行证据写出后即调用核心验证器。运行失败会记录根因并继续其他方案，失败不进入完成时间排行榜；命令完成后若存在失败会以非零退出。进程被强制中断时，已完成运行保留，状态可能仍为RUNNING，可通过历史页面识别。文件采用同目录临时写入后原子替换；三件套不是跨文件事务，因此必须以最终校验通过为完整证据标准。

重建历史与单独生成报告：

```bash
mvn -pl :workflowsim-experiments -am compile exec:java \
  -Dexec.mainClass=org.workflowsim.experiments.workbench.Workbench \
  -Dexec.args="history output/workbench"

mvn -pl :workflowsim-experiments -am compile exec:java \
  -Dexec.mainClass=org.workflowsim.experiments.workbench.Workbench \
  -Dexec.args="report /absolute/run/result.manifest.json /absolute/new-report.html"
```

单独报告命令拒绝覆盖现有目标。历史由各实验记录扫描重建，可见损坏记录而不会悄然略过；多进程写同一历史目录时需要在全部进程结束后执行一次 `history` 取得完整最新索引。同一JVM内实验串行。

## 验收

配置单元测试覆盖严格解析、组合边界和输入资格。入口集成测试覆盖配置→仿真→v4工件校验→HTML→历史、失败记录、重复实验隔离和HTML转义；最后运行全量 `mvn clean verify`。

浏览器验收脚本为 `scripts/verify-report.cjs`，检查运行选择、VM/任务筛选、依赖图聚焦、图表、390px窄屏布局、无浏览器错误和无外部HTTP请求。测试工具只需临时安装 `playwright-core` 并复用已有Chrome：

```bash
npm install --prefix /tmp/workflowsim-browser-check --no-audit --no-fund playwright-core
NODE_PATH=/tmp/workflowsim-browser-check/node_modules \
  WORKFLOWSIM_CHROME="/absolute/path/to/chrome" \
  node scripts/verify-report.cjs /absolute/experiment/report.html
```

macOS默认Chrome位置已在脚本配置；其他系统设置 `WORKFLOWSIM_CHROME`。Node/Chrome仅用于页面验收，不是Java仿真的运行依赖。
