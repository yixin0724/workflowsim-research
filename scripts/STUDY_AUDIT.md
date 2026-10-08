# 独立研究证据与统计检查

以下工具仅依赖 **Python 3.9+ 标准库**，不调用 Java 生产算法，不启动仿真，也不重放网络。输入只读；审计报告必须写到新的、独立的位置。

| 工具 | 输入 | 检查范围 |
| --- | --- | --- |
| [证据检查器](<verify-study-evidence.py>) | 一个或多个研究索引、Workbench 实验索引或单个 manifest v4 | 原始输入、Job/Task 尝试、时序、工件哈希和可独立复算的指标 |
| [统计检查器](<verify-study-statistics.py>) | 一个或多个已注册的网络研究索引 | 注册矩阵、协议绑定、逐 DAG 效果、符号检验和 Holm 校正 |
| [网络与生命周期检查器](<verify-network-ledger.py>) | V1/V2/V3 侧车或对应 manifest v4 bundle | 版本化网络/生命周期语义和适用的在线动作上下文，范围由报告明确给出 |

前两个工具共享[独立解析与注册定义](<_study_audit.py>)。基础证据检查器即使核对了侧车的 SHA-256，也不等于验证了侧车内容；它和网络检查器不能互相替代。任何一个工具通过都不证明算法最优、标准论文复现或真实平台校准。

## 调用与输出保护

以下命令从项目根运行。也可在任意目录使用脚本和输入的绝对路径；相对输入路径以调用工作目录为基准，不猜测仓库位置。

```bash
# 单个完整运行包
PYTHONDONTWRITEBYTECODE=1 python3 scripts/verify-study-evidence.py \
  /absolute/experiment/runs/fcfs-s42/result.manifest.json \
  --output /absolute/new-audit/evidence-single.json

# 多个研究的基础证据与统计需要分别检查
PYTHONDONTWRITEBYTECODE=1 python3 scripts/verify-study-evidence.py \
  /absolute/study-a/network-study.json /absolute/study-b/network-study.json \
  --output /absolute/new-audit/evidence.json

PYTHONDONTWRITEBYTECODE=1 python3 scripts/verify-study-statistics.py \
  /absolute/study-a/network-study.json /absolute/study-b/network-study.json \
  --output /absolute/new-audit/statistics.json

# 另行检查物理生命周期及适用的在线动作；结构化结果写到标准输出
PYTHONDONTWRITEBYTECODE=1 python3 scripts/verify-network-ledger.py \
  /absolute/experiment/runs/fcfs-s42/result.manifest.json --json
```

替换占位路径；最后一条仅适用于对应网络/生命周期证据包。`--output` 是全新的 JSON **文件路径**，不是已有文件或目录，也不能位于任一输入的研究/bundle 根内。父目录可新建，已有报告不会被覆盖；输入、协议、索引和证据不会被改写。

前两个工具的退出码：

| 退出码 | 含义 |
| ---: | --- |
| 0 | 所请求、明确声明的检查范围内未发现问题 |
| 1 | 检查发现问题或无法完成所请求的覆盖；报告保留诊断 |
| 2 | 用法、输出保护或写报告失败 |

同时检查退出码、`status`、`issueCount` 和覆盖说明，不以“报告文件已生成”判断成功。`--max-issues` 默认 200，只限制保存的诊断明细，不减少问题总数或改变失败状态。网络检查器的状态与完整捕获解释见[独立网络校验说明](<../docs/advanced/NETWORK_LEDGER_PYTHON.md>)。

## 注册研究身份与兼容模式

统计检查器只支持明确注册的 `protocol` 和 `full/smoke` 矩阵，不从索引自述推断完整性，也不是通用任意实验统计器。

| 注册研究 | 当前身份 | 需要 `--historical` 的兼容身份 |
| --- | --- | --- |
| 网络受限比较 | `network-limited-r10-v3` | `network-limited-r10-v2` |
| 规划器比较 | `peft-comparison-r12-v2` | `peft-comparison-r12-v1` |
| 敏感性比较 | `sensitivity-response-r13-v2` | `sensitivity-response-r13-v1` |

这些字符串是[可执行注册表](<_study_audit.py>)的协议标识，不能为了整理文档而重命名保留证据。基础证据检查器要求 manifest v4；缺少当前执行语义声明的旧 v4 证据也须显式启用兼容模式，`--historical` 不会把 v2/v3 manifest 升级为 v4。

```bash
PYTHONDONTWRITEBYTECODE=1 python3 scripts/verify-study-evidence.py \
  /absolute/archive/network-study.json --historical \
  --input-root /absolute/restored-repository \
  --output /absolute/new-audit/historical-evidence.json
```

`--input-root` 仅用于证据检查器，可重复提供。原始输入依次尝试保留研究中的 `inputs/同名文件`、记录路径、依据原 workingDirectory 或 dataset root 的显式重定位。找到文件后仍检查 SHA/大小，不因哈希不符偷偷换输入。找不到或不能解析原始输入时报告覆盖缺口并非零退出。

允许读取兼容格式不表示认可旧物理/时序或非标准 PEFT 递推；统计数字重算一致也不是标准算法正确性背书。不同执行模型不作共享条件数值相等断言。

## 基础证据覆盖

支持标准运行器的 NONE 聚类、SPACE_SHARED、每 VM 一个活动 Job 的基础投影，包括已返回的成功、失败和重试尝试：

- 检查工件角色唯一、相对路径包含性、SHA-256/大小以及 manifest/metrics 一致性。
- 检查事件序号、有限非负时间、关键事件唯一性、Job/Task 身份和 retry 谱系。
- 从实际 DAX/WfFormat 输入重新解析 runtime、DAG 和文件声明；核对原始 MI、需求与计费字节。多输入保持独立命名空间，DAX 保留首次 INPUT 尺寸优先规则。
- 显式成本矩阵优先于原始 MI，使用独立 Java round 语义核对有效 MI、Task 窗口和 CPU 下界，不以源长度替代当前尝试的工作量。
- 复算所覆盖的完成/重试计数、等待、bounded slowdown、最近秩中位数/P95、吞吐、VM 繁忙区间并集、CV/Jain 和抽象成本；失败/重试按相应指标口径计入。

证据检查器的 `--rel-tol` 默认 `1e-10`、`--abs-tol` 默认 `1e-8`，相对容差只用于指标/成本汇总。物理时序使用 `abs_tol + 4 * ulp(最大绝对时间戳)`，CPU 下界另加 `1 / MIPS`；不会随绝对时钟按比例放宽缺失工作量。整数计数和 ID 必须精确。

每个 bundle 的 `coverage.checkedMetrics` 列出实际复算项，缺失必需项会报错；`outsideMetricScope` 列出未认证字段。墙钟性能、deadline/critical-path 参考值及未列出的新指标不能因整个报告成功而被视为已认证。

基础工具不独立重放 max-min 速率分段、Fat-tree 网络服务或在线策略选择。V2/V3 生命周期和在线动作需另用网络检查器；即使该检查器通过，仍须遵守其 `fluidServiceAccountingCertified`、`liveProgressReplayed` 等范围声明，不能宣称完整流体服务或未来争用已被证明。

## 统计覆盖

统计检查器独立构造注册矩阵，核查输入 ID/指纹、算法、条件、种子、完成状态，以及协议原字节 SHA 与逐运行 manifest 的绑定。

先汇总同 DAG 的种子，再按来源、VM、网络和异构度配对；不把种子当作独立 DAG，也不合并经典与合成负载总体。符号检验与 Holm 使用精确分数，全平局时 p 值未定义。报告保留逐 DAG 效果、完整向量、胜平负及尾部信息，不仅报告中位数。

多个索引还会核对同执行模型下共享条件的指标；不一致时非零退出。统计检查不是完整证据物理验证，应结合基础证据和所需生命周期报告。小样本、家族相关性和随机流可比性限制仍须在研究结论中说明。

## 自测与保留

```bash
PYTHONDONTWRITEBYTECODE=1 python3 scripts/test-study-verifiers.py
```

[自测](<test-study-verifiers.py>)使用手写小型夹具验证健康输入、缺字段、MI/区间伪造、输出保护、注册矩阵和统计反例，不代表真实 full 研究已通过。其余独立检查器套件与跨语言验证见[构建指南](<../docs/getting-started/BUILD.md>)。

报告应与输入索引、工具版本和命令一起保留。不要通过删除问题记录、重写原始研究或改变协议身份来迎合检查器。
