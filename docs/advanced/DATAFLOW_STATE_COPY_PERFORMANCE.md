# 数据流状态结构与性能测量

## 事务状态的复制范围

一致数据流运行器使用不可变历史与活动工作集分离，减少每次事务对已完成记录和全部文件缓存的重复复制。该结构不改变物理模型、来源/路由选择、分配器、VM绑定策略、浮点聚合顺序或记录预算。

- [历史索引](<../../simulator/src/main/java/org/workflowsim/data/v2/PersistentHistoryMap.java>)以不可变二进制归并块存储记录。层级按写入次数而不是去重后的map大小计算；新写覆盖旧值，从较新层查找。调用者提供不可变键和值，不对外暴露迭代/删除操作。
- [副本状态](<../../simulator/src/main/java/org/workflowsim/data/v2/DataReplicaState.java>)只复制新发布副本所在文件的有序位置行。快照保留不可变根及捕获时的水印/活动票据；fork仍独立复制活动索引，保留分支独立的时钟、序号和票据身份。
- [运行协调器](<../../simulator/src/main/java/org/workflowsim/data/v2/CoherentDataflowRuntime.java>)在事务中复制活动Job，全部输出处理成功后才归档不可变终态摘要。历史准备结果保持同一对象，准备时差仍按原始时刻相减，重复Job ID检查覆盖活动和终态两侧。
- 观测和静止判断不遍历已结束Job；就绪队列、输入等待、SOURCE等待、输出义务及活动复制的顺序与屏障仍独立保留。

历史查询检查O(log W)个块，假设块内哈希查询预期为常数时间；更新沿单条历史摊销复制O(log W)项，但一次大归并仍可能O(W)，重复分叉的大归并不共享摊销。不要把这描述为最坏情况下的对数更新。

活动Job集合仍需隔离；大量同时活动的宽任务批次可能保留近二次的复制成本。生命周期日志采用事务后的增量追加，checked服务也仍保留自身的事务副本。本结构不承诺所有DAG、解析、求解或报告阶段都有相同加速。

## 可重复基准

[DataflowStateProfile](<../../simulator/src/test/java/org/workflowsim/data/v2/DataflowStateProfile.java>)是控制器层探针，不是算法排名研究或CI耗时阈值。比较时使用同一个编译后的基准类，分别加载明确版本的基线与候选核心实现，固定JDK、堆、输入、时刻、预热和采样方法。

| 场景 | 工作负载 |
|---|---|
| FILELESS | 无文件链，含重复静止和空文件范围观察 |
| FILES_V2 | 每任务独立外部输入/输出、缓存集合持续增长的链，不是高命中率负载 |
| STORE_V3 | 文件链加受限读写与必要输出落库 |
| ONLINE_V3 | 存储链加控制就绪绑定、观察和预留打分 |
| WIDE_V2 | 大量同时活动的无文件任务，观察活动工作集成本 |

捕获参数为`OFF`、`TRUNCATED`（预算1）或`FULL`。每组先预热三次100任务；命令最后一个参数是采样次数。规模、捕获和采样必须在两侧一致。

## 指标口径

- `elapsedNanos`只测协调器状态转换；不包括文件计划构造、初始化、最终语义编码和指纹计算，不是整次SimulationRunner端到端耗时。
- `threadAllocatedBytes`是测量线程的**累计分配字节**，不是常驻RAM、峰值RSS或需要的堆容量；不可用时保留null。
- `heapPoolPeakSumBytes`是各堆内存池峰值之和，不冒称同一时刻的进程峰值。
- 状态、捕获和在线动作SHA256用于逐位比较语义。时间或分配改善不能替代这些比较，也不构成真实平台校准或统计显著性证明。

## 运行与比较

先按[构建指南](<../getting-started/BUILD.md>)构建两个待比较版本。使用当前基准源码编译一次；`BASELINE_CP`、`CANDIDATE_CP`分别指向相应核心JAR及Gson/JDOM/Commons Math依赖，`PROFILE_CLASSES`和`PROFILE_DIR`为新建的基准类/输出目录。

```bash
javac --release 8 -cp "$BASELINE_CP" -d "$PROFILE_CLASSES" \
  simulator/src/test/java/org/workflowsim/data/v2/DataflowStateProfile.java

java -Xms512m -Xmx512m -cp "$PROFILE_CLASSES:$BASELINE_CP" \
  org.workflowsim.data.v2.DataflowStateProfile ONLINE_V3 250,500,1000,2000 OFF 3 \
  > "$PROFILE_DIR/before-ONLINE_V3-OFF.jsonl"

java -Xms512m -Xmx512m -cp "$PROFILE_CLASSES:$CANDIDATE_CP" \
  org.workflowsim.data.v2.DataflowStateProfile ONLINE_V3 250,500,1000,2000 OFF 3 \
  > "$PROFILE_DIR/after-ONLINE_V3-OFF.jsonl"

python3 -B scripts/compare-dataflow-profile.py "$PROFILE_DIR" \
  "$PROFILE_DIR/comparison.json" "$PROFILE_DIR/comparison.md"
```

[比较工具](<../../scripts/compare-dataflow-profile.py>)先检查场景、试次、JVM/堆、实际复制数和每组全部语义指纹，再输出有限的统计量。缺失、类型矛盾、指纹漂移或输出路径已存在会失败；不设置容易波动的速度通过门槛。

原始JSONL、环境与版本身份、统计报告应一起保留到各次测量自己的目录，不把一次机器上的数字写成长期接口保证。修改状态结构还必须验证父子/兄弟分支隔离、旧快照、票据身份、部分输出/延迟读取失败回滚、终态查询、等待者顺序与不同捕获模式；完整构建、证据重放及浏览器门禁不能省略。
