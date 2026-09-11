# WorkflowSim 等待时间指标实施总结

**实施日期**: 2024-09-04  
**版本**: WorkflowSim 1.0  
**状态**: ✅ 完成并测试通过

---

## 📋 实施概述

本次更新解决了两个关键问题：

1. **文件覆盖风险** - 每次实验运行会覆盖上一次结果，破坏实验对比能力
2. **缺少核心指标** - 缺少作业等待时间、响应时间、减速比等调度系统核心指标

## 🎯 解决方案

### 问题 1：文件覆盖风险

**方案**: 时间戳子目录隔离

- 新增配置开关：`USE_TIMESTAMP_DIR = true`（默认开启）
- 输出目录结构变更：
  ```
  output/my-experiments/
  ├── run-20260904-150830/
  │   ├── result.manifest.json
  │   ├── result.metrics.json
  │   ├── result.events.jsonl
  │   ├── result.jobs.csv
  │   ├── result.tasks.csv
  │   ├── result.vms.csv
  │   └── result.html
  └── run-20260904-151045/
      └── (另一次运行的结果)
  ```

**优点**:
- ✅ 每次运行独立目录，永不覆盖
- ✅ 便于批量对比历史实验
- ✅ 符合科研实验管理习惯
- ✅ 时间戳精确到秒，避免冲突

---

### 问题 2：缺少等待时间核心指标

**理论基础**: 调度系统经典时间模型

```
submissionTime          execStartTime               finishTime
     |                        |                           |
     |<--- waitingTime ------>|<--- executionTime ------->|
     |                                                     |
     |<--------------- responseTime -------------------->|
```

**新增字段**:

#### JobOutcome（作业级数据）
- `submissionTime` - 作业提交时间（从 CloudSim 获取）
- `waitingTime` - 等待时间 = execStartTime - submissionTime
- `executionTime` - 执行时间 = finishTime - execStartTime
- `responseTime` - 响应时间 = finishTime - submissionTime

#### SimulationMetrics（统计级数据）
- `meanJobWaitingTimeSeconds` - 平均作业等待时间
- `meanJobResponseTimeSeconds` - 平均作业响应时间
- `meanJobSlowdown` - 平均减速比（responseTime / executionTime）

**边界保护**:
- 负值裁剪为 0（失败作业可能时间戳异常）
- 减速比排除 executionTime ≤ 0.001 秒的作业（避免除零）

---

## 📊 输出格式更新

### 1. 控制台输出

**【性能】分组新增 3 行**:
```
平均等待时间:        2.35 秒
平均响应时间:        18.58 秒
平均减速比:          1.15
```

### 2. CSV 表格 (jobs.csv)

**新增 6 列**:
```csv
submissionTime,startTime,finishTime,waitingTime,executionTime,responseTime
```

示例数据：
```csv
0,0.0,2.0,8.5,2.0,6.5,8.5,3,...
1,1.0,5.0,12.3,4.0,7.3,11.3,2,...
```

### 3. HTML 可视化报告

**指标卡片新增 2 个**:
- 平均等待时间（秒）
- 平均响应时间（秒）

**作业详细表新增 6 列**:
- 提交时间、开始时间、结束时间
- 等待时间、执行时间、响应时间

### 4. JSON 输出 (metrics.json)

自动序列化新增的 3 个 getter 方法：
```json
{
  "meanJobWaitingTimeSeconds": 2.35,
  "meanJobResponseTimeSeconds": 18.58,
  "meanJobSlowdown": 1.15
}
```

---

## 📁 修改的文件

### 核心数据模型（2 个）

1. **SimulationReport.java** - JobOutcome 扩展
   - 新增 6 个字段（submission + 3 derived + 2 existing renamed）
   - 更新构造器和 fromJob() 方法
   - 边界保护：Math.max(0.0, ...)

2. **SimulationMetrics.java** - 统计指标扩展
   - 新增 3 个字段和对应 getter
   - calculate() 方法新增累加逻辑
   - 边界保护：slowdown 排除零执行时间作业

### 输出通道（3 个）

3. **ExperimentConsoleSummary.java** - 控制台输出
   - 【性能】分组新增 3 行

4. **ExperimentCsvWriter.java** - CSV 输出
   - jobs.csv 表头和数据行更新

5. **ExperimentHtmlReportWriter.java** - HTML 报告
   - 指标卡片新增 2 个
   - 作业表新增 6 列

### 实验模板（1 个）

6. **MyConfigurableExperiment.java** - 实验模板
   - 新增 USE_TIMESTAMP_DIR 配置
   - 输出逻辑改为 run-<timestamp> 子目录

### 测试文件（2 个）

7. **SimulationMetricsTest.java** - 更新构造器调用
   - 新增 submissionTime 参数

8. **JobWaitingTimeMetricsTest.java** - 新增测试（5 个用例）
   - ✅ 基础派生计算验证
   - ✅ 负值裁剪验证
   - ✅ 零提交时间验证
   - ✅ 统计聚合验证
   - ✅ 减速比边界验证

---

## ✅ 测试结果

### 单元测试
```
Tests run: 164, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

**新增测试用例**: 5/5 通过
- jobOutcomeDerivesWaitingExecutionAndResponseTimes
- negativeIntervalsAreClampedToZeroForIncompleteJobs
- zeroSubmissionTimeYieldsWaitingEqualToStartTime
- metricsAggregateMeanWaitingResponseAndSlowdown
- slowdownExcludesJobsWithNearZeroExecutionTime

### 编译验证
```
[INFO] BUILD SUCCESS
```

### 回归测试
✅ 现有 164 个测试全部通过，无回归

---

## 🔬 科研价值

### 新增分析能力

1. **队列等待分析**
   - 可评估调度器的队列管理效率
   - 对比不同算法的等待时间差异

2. **响应时间分析**
   - 用户视角的作业完成时间
   - 评估系统整体性能

3. **调度效率评估**
   - 减速比 = 1 表示无等待（理想状态）
   - 减速比越大表示调度开销越大

### 与现有指标的互补性

| 指标类型 | 现有指标 | 新增指标 | 覆盖范围 |
|---------|---------|---------|---------|
| 任务级 | meanComputeReadyToDecisionDelaySeconds | - | 调度决策延迟 |
| 作业级 | - | meanJobWaitingTimeSeconds | 总等待时间 |
| 作业级 | - | meanJobResponseTimeSeconds | 用户感知时间 |
| 作业级 | - | meanJobSlowdown | 调度效率 |

**关系**: 任务级延迟 + 作业级等待 = 完整延迟分析体系

---

## 🚀 使用指南

### 运行实验

```bash
cd /Users/yixin/project/WorkflowSim-1.0

# 方式 1: Maven
mvn exec:java -pl experiments \
  -Dexec.mainClass="org.workflowsim.examples.MyConfigurableExperiment"

# 方式 2: IDEA
右键 MyConfigurableExperiment.java → Run
```

### 查看结果

```bash
# 输出目录
ls -la output/my-experiments/

# 示例输出
output/my-experiments/
├── run-20260904-150830/
│   ├── result.manifest.json   # 实验元数据
│   ├── result.metrics.json    # 统计指标（含新增指标）
│   ├── result.events.jsonl    # 事件日志
│   ├── result.jobs.csv        # 作业详细数据（含等待时间列）
│   ├── result.tasks.csv       # 任务详细数据
│   ├── result.vms.csv         # VM 详细数据
│   └── result.html            # 可视化报告（含新增卡片）

# 在浏览器打开 HTML 报告
open output/my-experiments/run-*/result.html
```

### CSV 数据分析示例

**Python 示例**:
```python
import pandas as pd

# 读取作业数据
df = pd.read_csv('result.jobs.csv')

# 分析等待时间分布
print(df['waitingTime'].describe())

# 对比不同 VM 的平均等待时间
print(df.groupby('vmId')['waitingTime'].mean())

# 计算减速比（手动）
df['slowdown'] = df['responseTime'] / df['executionTime']
print(df['slowdown'].describe())
```

### 配置选项

在 `MyConfigurableExperiment.java` 中：

```java
// 控制是否使用时间戳子目录
private static final boolean USE_TIMESTAMP_DIR = true;  // 推荐

// USE_TIMESTAMP_DIR = false 时的输出
// output/my-experiments/my-experiment.html（会覆盖）

// USE_TIMESTAMP_DIR = true 时的输出
// output/my-experiments/run-20260904-150830/result.html（不覆盖）
```

---

## 📝 设计文档

完整设计文档：`/tmp/waiting_time_design.md`

包含：
- 概念定义（waitingTime/responseTime/slowdown）
- 与 WorkflowSim 特性的兼容性分析
- 边界情况处理策略
- 实施计划和预期影响

---

## 🔍 技术细节

### 数据来源验证

**CloudSim 继承链**:
```
Job extends Task extends Cloudlet
```

**可用方法**:
- `job.getSubmissionTime()` - 继承自 Cloudlet
- `job.getExecStartTime()` - 继承自 Cloudlet
- `job.getFinishTime()` - 继承自 Cloudlet

**设置时机**:
```java
// ResCloudlet 构造函数
arrivalTime = CloudSim.clock();
cloudlet.setSubmissionTime(arrivalTime);
```

### WorkflowSim 特性兼容性

1. **聚类（Clustering）** ✅
   - 多个 Task 聚类成一个 Job
   - waitingTime 记录的是整个 Job 的等待
   - 符合作业级指标定义

2. **重试机制** ✅
   - 失败 Job 被重试时创建新 Job
   - 每个 Job 独立记录 submissionTime
   - 统计包含所有尝试

3. **Stage-in 任务** ✅
   - executionTime 可能为 0
   - slowdown 计算时排除（避免除零）

4. **静态 vs 动态调度** ✅
   - 直接读取 CloudSim 实际记录的时间
   - 适用于所有调度算法

---

## 🎉 总结

### 核心改进

1. **文件管理** - 时间戳子目录避免结果覆盖
2. **指标完整性** - 新增等待时间、响应时间、减速比
3. **科研价值** - 支持完整的调度性能分析
4. **测试覆盖** - 5 个新增测试，164 个测试全部通过

### 后续工作

- ✅ 核心功能实现完成
- ✅ 单元测试完成
- ✅ 文档更新完成
- ⏳ 建议运行完整工作流实验验证（用户操作）
- ⏳ 建议更新用户手册添加新指标说明（可选）

---

**实施者**: Kiro AI  
**审核者**: 待定  
**批准者**: 待定
