# 独立研究证据与统计审计工具

这两个工具仅依赖 Python 标准库，不导入 Java、不调用模拟器的生产 helper，也不启动仿真。
它们从显式给出的文件独立检查证据和统计。需要 Python 3.9+，无需 pip 安装。

- [verify-study-evidence.py](<verify-study-evidence.py>)：输入一个或多个研究索引、Workbench 实验索引或单个 manifest。
- [verify-study-statistics.py](<verify-study-statistics.py>)：输入一个或多个注册网络研究索引。
- [共享基础](<_study_audit.py>)：严格 JSON、数值/路径检查、独立注册矩阵和原始输入解析。
- [小型自测](<test-study-verifiers.py>)：临时手写夹具，不使用真实研究目录、Java 或 Maven。

这些报告**不是新仿真结果、网络流重放、论文算法正确性证明或真实平台校准**。
生产验证器与这两个独立工具的检查范围不同，不能相互冒充。

## 调用与输出保护

从任意目录均可使用绝对路径；相对路径以调用工作目录为基准。不猜测仓库位置，也不硬编码某个本地研究根。
`--output` 是**全新的 JSON 文件路径**，不是已有报告或目录。工具会拒绝覆盖已有文件，并拒绝把报告写入任一输入的研究/bundle 根目录。
输出父目录可以新建；原始输入、协议、索引和三件套均只读。

```bash
# 先用一个健康 bundle 验证工具接入
PYTHONDONTWRITEBYTECODE=1 python3 scripts/verify-study-evidence.py \
  /absolute/experiment/runs/fcfs-s42/result.manifest.json \
  --output /absolute/new-audit/evidence-single.json

# 多个完整新研究：两个独立检查应分别执行
PYTHONDONTWRITEBYTECODE=1 python3 scripts/verify-study-evidence.py \
  /absolute/r10-v3/network-study.json \
  /absolute/r12-v2/network-study.json \
  /absolute/r13-v2/network-study.json \
  --output /absolute/new-audit/evidence.json

PYTHONDONTWRITEBYTECODE=1 python3 scripts/verify-study-statistics.py \
  /absolute/r10-v3/network-study.json \
  /absolute/r12-v2/network-study.json \
  /absolute/r13-v2/network-study.json \
  --output /absolute/new-audit/statistics.json
```

退出码：

| 退出码 | 含义 |
|---:|---|
| 0 | 所请求、明确声明的检查范围内没有问题 |
| 1 | 存在检查问题或所请求检查无法完成；JSON 报告保留诊断 |
| 2 | 用法、输出保护或文件系统写报告失败；不覆盖旧报告 |

任何问题均不能因诊断截断而变成成功。`--max-issues` 仅限制保存的明细数量，`issueCount`、问题分类计数和退出码仍计入全部问题。
不要像旧观测脚本那样仅以“脚本正常结束/写出报告”判断通过，应同时看退出码、`status`、`issueCount` 和覆盖说明。

## 历史与输入迁移

新研究身份为 `network-limited-r10-v3`、`peft-comparison-r12-v2`、`sensitivity-response-r13-v2`。
旧 `-v2/-v1/-v1` 以及缺少新执行语义字段的 v4 bundle 必须显式加 `--historical`：

```bash
python3 scripts/verify-study-evidence.py /archive/study/network-study.json \
  --historical --input-root /restored/repository \
  --output /absolute/new-audit/historical-evidence.json
```

原始输入定位顺序为保留研究的 `inputs/同名文件`、记录路径、显式 `--input-root` 重定位。
重定位根据保留的 workingDirectory 或 dataset root，相应参数可重复提供；命中后检查 SHA/大小，不会因哈希不符偷偷换另一份文件。
原始输入不能定位、不能解析或模型超出支持范围时，报告覆盖缺口并非零退出，不能降级成“完整验证成功”。

历史格式允许读取不表示旧物理/时序正确；证据脚本仍会如实报告历史 CPU 下界、区间或需求量问题。
历史 R12/R13 的非标准 LOCAL_PEFT 记录也不会因统计重算一致而取得标准 PEFT 的正确性背书。
不同执行模型的研究不做共享条件数值等同断言，报告中明确列为不可跨版本比较。

## 证据侧覆盖

当前支持标准 Runner 的 NONE 聚类、SPACE_SHARED、每 VM 一个活动 Job 的证据，包含已返回成功/失败尝试和重试记录。

- 三件套角色唯一、相对路径包含性、SHA-256/size、manifest 与 metrics 一致。
- 事件序号、有限非负时间、时钟顺序、事件总数，关键 Job 事件唯一与存在性。
- Job ID 唯一、提交/开始/结束区间、ready→decision→start、VM 无重叠与繁忙区间并集。
- 从实际 DAX/WfCommons JSON 重解析任务顺序、runtime、DAG、文件声明，核对原始 MI、需求字节、文件数及 stage-in 初始化字节。
- DAX 保留既有首次 INPUT 尺寸优先规则；多输入使用独立文件命名空间。原始任务工作量按声明 reference MIPS/scale 和至少100 MI规则重算。
- 显式成本矩阵优先于原始 MI；独立实现 Java round 的二进制输入、最近整数及半值向正无穷语义，不使用容易二次舍入的 `floor(x+.5)`。
- 核对有效 MI、新模型 Task/event 有效长度声明和有效窗口；CPU 下界容差为 `1 / 所选 VM MIPS` 加声明的数值容差。
- 独立复算计数、逻辑完成、等待、最近秩中位/P95/max、bounded slowdown、VM 级 slowdown、重试统计、吞吐、忙碌并集、CV/Jain。
- 依据平台/VM 价格与 Job CPU 信封计算成本；声明文件字节从原始输入投影复核，带宽价格按十进制 MB，不当作实际网络流量或云账单。

`--rel-tol` 默认 `1e-10`，`--abs-tol` 默认 `1e-8`，相对/绝对 close 仅用于指标汇总及成本数字。事件、区间、依赖和 Task 时间对应采用 `abs_tol + 4 * ulp(参与比较的最大绝对时间戳)`；CPU 下界再加 `1 / MIPS`，绝不随绝对时钟按比例放宽缺工作。计数和编号要求精确整数；原始需求字节另采用绝对数值检查，不能把正需求归零。
每个 bundle 的 `coverage.checkedMetrics` 明确列出复算指标，这些字段缺失会报错，**不使用 `if key in metrics` 静默略过**。
`outsideMetricScope` 列出未认证项，包括本机 wall-clock、deadline/critical-path 引用等；不暗示所有 v4 字段均已验证。

### 明确不覆盖

事件没有完整逐流字节、路由、速率分段账本，因此不能独立重放 max-min 分配、Fat-tree 拥塞或真实网络完成时刻。
本工具验证可从保留字段和原始输入重算的工作量、时序与指标，不验证算法选择是否最优，也不进行标准 PEFT 论文复现。
聚类和非标准调度执行路径不会被当作受支持模型悄悄通过。极端数值或未支持输入表达式会给出明确失败/覆盖缺口。

## 统计侧覆盖

- 以独立注册的 `protocol + full/smoke` 矩阵为 expected，不从输入 plan 自述反推完整性；核查输入 ID/指纹、算法、条件、种子和固定声明。
- 绑定保留协议原字节 SHA、逐运行 manifest 的配置/平台、输入、模型身份、完成状态和索引指标。
- 先汇总同 DAG 种子，再按来源/VM/网络/异构度配对；不把种子当独立 DAG，不合并经典和合成总体。
- 用 `Fraction` 精确计算双侧符号检验与 Holm，平局容差遵守注册规则；全平局保持 p 未定义。
- 输出 `perDagEffects` 完整逐 DAG 效果、种子数、绝对差、改善百分比与胜平负；比较行还列完整向量及最小/最大/P95，不能仅看 median 推“稳健”。
- 多索引输入会核对同执行模型下共享条件的五个数值字段。模型不同明确不比较；同条件差异会非零退出。

统计侧不是完整三件套物理验证，必须结合证据侧报告。小样本、家族相关性及无事件键控 CRN 的边界保留；不输出普遍最优或总体因果结论。

## 自测

```bash
PYTHONDONTWRITEBYTECODE=1 python3 scripts/test-study-verifiers.py
```

自测包含健康单 bundle、矩阵覆盖原始长度、Java round 临界值、协调删除必需指标后的非零退出、VM 重叠/原始 MI 伪造、输出保护、精确符号检验/Holm、注册计数和裁剪矩阵拒绝。
它只证明工具在这些小夹具上的行为，不等于任何真实 full 研究已经通过独立审计。
报告建议与输入索引、工具版本及执行命令一起归档；不要删除或重写历史研究来迎合新检查。
