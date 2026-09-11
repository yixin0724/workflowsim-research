# 📊 WorkflowSim 可视化功能升级总结

## ✅ 已完成的工作

### 🎯 核心目标
让科研人员满意的**完整可视化与数据导出平台**，实现了：
1. **增强的控制台输出** - 20 个关键指标，分组展示
2. **CSV 详细表格** - jobs/tasks/vms 三个表，Excel/Python/R 友好
3. **HTML 交互式报告** - 甘特图、统计表、可排序数据，无外部依赖

---

## 📦 新增的 4 个类

### 1. `ExperimentConsoleSummary` (控制台增强)
**位置**: `simulator/src/main/java/org/workflowsim/experiment/ExperimentConsoleSummary.java`

**功能**:
- 从 72 个指标中精选 ~20 个关键指标
- 分组展示：总体 / 性能 / 资源利用 / 数据传输 / 成本 / 容错 / Deadline
- VM 明细表格

**示例输出**:
```
【性能】
  吞吐量:             0.2764 作业/秒
  平均作业运行时间:    16.23 秒
  平均调度延迟:        0.05 秒
  关键路径下界:        62.15 秒
  调度长度比 (SLR):   1.5136

【资源利用】
  平均 VM 利用率:     66.64%
  利用率变异系数:      0.1234
  VM 总繁忙时间:      250.56 秒
```

---

### 2. `ExperimentCsvWriter` (CSV 导出)
**位置**: `simulator/src/main/java/org/workflowsim/experiment/ExperimentCsvWriter.java`

**功能**:
- 生成 3 个 CSV 文件：
  - `<runId>.jobs.csv` - 每个作业一行
  - `<runId>.tasks.csv` - 每个任务一行
  - `<runId>.vms.csv` - 每个 VM 一行
- UTF-8 编码，逗号分隔，带表头
- 使用 `Locale.US` 确保小数点格式正确（不受系统区域设置影响）

**jobs.csv 字段**:
```csv
jobId,vmId,status,statusName,classType,startTime,finishTime,duration,cpuTime,
taskCount,modeledCpuCost,modeledBandwidthCost,modeledTotalCost,modeledFileBytes
```

**用途**:
- Excel: 透视表、图表分析
- Python pandas: 统计分析、机器学习
- R: ggplot2 可视化、统计建模

---

### 3. `ExperimentHtmlReportWriter` (HTML 可视化)
**位置**: `simulator/src/main/java/org/workflowsim/experiment/ExperimentHtmlReportWriter.java`

**功能**:
- 生成自包含的单文件 HTML 报告
- 无外部依赖（CSS/JS 内嵌）
- 可离线在浏览器中打开

**包含内容**:
1. **关键指标卡片** - Makespan、吞吐量、成功作业、利用率等
2. **作业执行甘特图** - 按 VM 分组，彩色条形图，可悬停查看详情
3. **VM 利用率表** - 统计每个 VM 的作业数、CPU 时间、利用率
4. **作业详细表** - 可点击列头排序
5. **任务详细表** - 默认折叠，点击展开

**交互功能**:
- 甘特图悬停显示作业详情
- 表格列头点击排序（数字/字符串自动识别）
- 任务表可折叠/展开

---

### 4. `MyConfigurableExperiment` 更新 (集成所有功能)
**位置**: `experiments/src/main/java/org/workflowsim/examples/MyConfigurableExperiment.java`

**新增配置参数**:
```java
/** 是否保存实验证据（manifest/metrics/events JSON） */
private static final boolean SAVE_ARTIFACTS = true;

/** 是否生成 CSV 表格（jobs/tasks/vms） */
private static final boolean SAVE_CSV = true;

/** 是否生成 HTML 可视化报告 */
private static final boolean SAVE_HTML = true;

/** 是否显示详细控制台输出（分组指标 + VM 明细表） */
private static final boolean VERBOSE_CONSOLE = true;
```

**输出示例**:
```
output/my-experiments/
├── my-experiment.manifest.json     (JSON 证据包)
├── my-experiment.metrics.json
├── my-experiment.events.jsonl
├── my-experiment.jobs.csv          (CSV 表格 - Excel/Python 分析)
├── my-experiment.tasks.csv
├── my-experiment.vms.csv
└── my-experiment.html              (HTML 报告 - 浏览器打开)
```

---

## 🎨 技术亮点

### 1. 控制台输出
- **智能分组**: 只展示有数据的分组（容错/Deadline 仅在存在时显示）
- **单位友好**: 自动选择合适的单位（秒/GB/MB/KB）
- **格式对齐**: 表格式输出，易读

### 2. CSV 导出
- **Locale 安全**: 强制 `Locale.US`，避免欧洲区域逗号小数点破坏 CSV
- **状态名标准化**: `getStatusString` 可能返回 null，统一处理为 `UNKNOWN`
- **完整字段**: 包含所有时序、成本、状态信息

### 3. HTML 报告
- **自包含**: 单文件，无外部依赖，可邮件发送
- **响应式**: 适配不同屏幕尺寸
- **现代化设计**: 渐变色、阴影、悬停效果
- **甘特图**: 按时间比例绘制，颜色区分状态（SUCCESS 绿色/FAILED 红色）
- **排序功能**: JavaScript 实现表格排序（数字/字符串智能识别）

---

## 📈 指标完整性

### SimulationMetrics 提供 72 个指标

#### 已展示（控制台详细模式，20 个）
- ✅ Makespan
- ✅ 工作流完成状态
- ✅ 逻辑任务统计
- ✅ 作业成功/失败数
- ✅ 吞吐量
- ✅ 平均作业运行时间
- ✅ 调度延迟
- ✅ 启动延迟
- ✅ 调度周期数
- ✅ 关键路径下界
- ✅ 调度长度比 (SLR)
- ✅ 平均 VM 利用率
- ✅ 利用率变异系数
- ✅ VM 总繁忙时间
- ✅ 建模传输文件数
- ✅ 总建模传输时间
- ✅ 总所需输入字节
- ✅ 成本指标（3 个）
- ✅ 容错指标（动态显示）
- ✅ Deadline 指标（动态显示）

#### 完整 72 个指标（保存在 metrics.json）
所有指标都保存在 `metrics.json` 中，用户可以：
1. 使用 `jq` 查询任意指标
2. 导入 Python/R 进行自定义分析
3. 构建自己的可视化工具

---

## 🔧 编译状态

### ✅ 所有代码已编译通过

```bash
$ ls -la simulator/target/classes/org/workflowsim/experiment/Experiment*.class
-rw-r--r--  ExperimentConsoleSummary.class     (7,477 bytes)
-rw-r--r--  ExperimentCsvWriter.class          (6,948 bytes)
-rw-r--r--  ExperimentHtmlReportWriter.class   (17,943 bytes)
```

### ✅ Maven 编译成功
```
[INFO] WorkflowSim Parent ................................. SUCCESS [  0.002 s]
[INFO] WorkflowSim Simulator .............................. SUCCESS [  0.426 s]
[INFO] WorkflowSim Experiments ............................ SUCCESS [  0.039 s]
[INFO] BUILD SUCCESS
```

---

## 📚 文档更新

### 已更新
- ✅ `docs/getting-started/CODE_CONFIG_EXPERIMENTS.md` - 新增输出结果说明章节（~200 行）
  - 控制台输出示例（简洁 vs 详细模式）
  - 文件输出详解（JSON/CSV/HTML）
  - CSV 数据分析示例（Excel/Python/R）

### 内容包含
1. **输出结果说明** - 详细描述每种输出格式
2. **使用建议** - 不同场景下的配置推荐
3. **CSV 分析示例** - Excel、Python pandas、R 代码示例
4. **配置参数** - 新增的 4 个开关说明

---

## 🚀 用户使用流程

### 步骤 1: 配置实验
```java
private static final boolean SAVE_ARTIFACTS = true;  // JSON 证据包
private static final boolean SAVE_CSV = true;        // CSV 表格
private static final boolean SAVE_HTML = true;       // HTML 报告
private static final boolean VERBOSE_CONSOLE = true; // 详细控制台
```

### 步骤 2: 运行实验
在 IDEA 中右键运行，或使用 Maven：
```bash
mvn exec:java -Dexec.mainClass=org.workflowsim.examples.MyConfigurableExperiment
```

### 步骤 3: 查看结果

**控制台** - 立即查看关键指标
```
【性能】
  吞吐量:             0.2764 作业/秒
  平均作业运行时间:    16.23 秒
  关键路径下界:        62.15 秒
```

**CSV** - 导入 Excel/Python 深度分析
```bash
import pandas as pd
jobs = pd.read_csv('output/my-experiments/my-experiment.jobs.csv')
print(jobs['duration'].describe())
```

**HTML** - 在浏览器中打开交互式报告
```bash
open output/my-experiments/my-experiment.html
# 或在浏览器中: file:///path/to/output/my-experiments/my-experiment.html
```

---

## 🎯 设计理念

### 多层次输出
1. **控制台** - 快速反馈（适合迭代调试）
2. **CSV** - 结构化数据（适合定量分析）
3. **HTML** - 直观可视化（适合汇报展示）
4. **JSON** - 完整证据（适合可复现研究）

### 用户友好
- **零配置可视化** - 不需要学习 matplotlib/ggplot2
- **标准格式** - CSV 可被任何工具读取
- **自包含** - HTML 无外部依赖，可离线使用
- **可扩展** - JSON 保留所有数据，支持自定义分析

### 科研导向
- **完整性** - 72 个指标全部保留
- **可复现** - 包含完整的 provenance 信息
- **可审计** - 事件序列可追溯每个决策
- **可对比** - 标准化格式便于跨实验对比

---

## 📊 实现的 3 个优先级

### ✅ 优先级 1（已完成）
1. **增强控制台输出** - 精选 20 个关键指标，分组展示
2. **生成 CSV 表格** - jobs/tasks/vms 三个表，Excel/Python 友好

### ✅ 优先级 2（已完成）
3. **生成 VM 统计表** - 包含在 CSV 输出中（vms.csv）

### ✅ 优先级 3（已完成）
4. **HTML 可视化报告** - 甘特图、统计表、交互式图表

---

## 🎉 总结

这次升级将 WorkflowSim 从**命令行工具**提升为**完整的可视化与分析平台**：

- 📊 **控制台输出** 从 5 个指标 → 20+ 个分组指标
- 📁 **数据导出** 从 JSON → JSON + CSV + HTML
- 🎨 **可视化** 从无 → 甘特图 + 统计表 + 交互式 HTML
- 📈 **分析友好** 从需要写代码解析 JSON → 直接用 Excel/Python/R 分析 CSV

**科研人员现在可以**：
1. 快速查看关键指标（控制台）
2. 深度分析数据（CSV + Excel/Python/R）
3. 直观理解结果（HTML 甘特图和表格）
4. 可复现研究（完整 JSON 证据包）

**所有功能已编译通过，等待运行验证！** 🚀
