# 网络研究协议身份与兼容性

注册身份同时包括 **protocol ID 和 mode**。相同参数矩阵不等于相同执行模型；只读完整性检查也不等于在当前代码下重新执行。

## 当前与历史身份

以下字符串来自 [NetworkStudyPlan](<../../experiments/src/main/java/org/workflowsim/experiments/network/NetworkStudyPlan.java>)，是实际协议标识，不应因文档整理而重命名。

| CLI 变体 | 当前执行协议 | 接受的历史协议 | full / smoke 计划单元 |
| --- | --- | --- | ---: |
| `r10`，也是默认变体 | `network-limited-r10-v3` | `network-limited-r10-v2` | 504 / 36 |
| `peft-comparison` | `peft-comparison-r12-v2` | `peft-comparison-r12-v1` | 126 / 18 |
| `sensitivity-r13` | `sensitivity-response-r13-v2` | `sensitivity-response-r13-v1` | 546 / 30 |

表中数量是注册矩阵的计划规模，不是已完成运行或测试通过数。smoke 有自己的输入、条件和种子，不是任意裁剪的 full；仅修改 mode 不能取得完整研究身份。

执行器只产生当前协议，没有用当前代码伪装旧执行身份的开关。当前协议和运行配置均须声明 `WORK_CONSERVING_TASK_EXECUTION_V2`；接受的旧协议保持其原 v4 形状，不得补上当前执行标识后继续冒认历史身份。

运行命令、当前矩阵条件与统计范围集中在 [campaign 指南](<CAMPAIGNS.md>)。原始研究及历史数字的来源通过[研究目录](<../../experiments/studies/README.md>)追溯，不作为新用户入口或当前黄金值。

## 注册声明如何形成

[NetworkStudyExecutor](<../../experiments/src/main/java/org/workflowsim/experiments/network/NetworkStudyExecutor.java>)先构造并核对计划，将实际 `NetworkStudyPlan.asMap()` 保存为输出目录的 `protocol.json`，随后生成逐运行证据和索引。这个生成的 JSON 是保留协议工件；不是以旧 Markdown 正文决定当前运行矩阵。

注册声明固定输入 ID、顺序、来源、哈希、资格排除、条件、规划器及实际种子。合成输入由确定性生成器产生；输入资格在观察算法效果之前对所有规划器一致检查。变更输入内容、候选、种子或条件需要新的注册协议，不能继续沿用旧 ID。

运行前的输入资格与只读验证使用不同路径：执行器读取并解析本地输入；验证器从无文件 I/O 的注册声明核对保留记录，不从待验证索引反推“应该执行什么”。保留的输入绝对路径须对应注册逻辑路径和声明的数据集根，但完整性检查不要求原机器输入文件在当前机器上存在；实际复跑仍必须定位并核对原始输入。

## 完整性检查的范围

[NetworkStudyValidator](<../../experiments/src/main/java/org/workflowsim/experiments/network/NetworkStudyValidator.java>)接受表中的当前和历史身份，并执行：

1. 核对索引 plan 与保留协议 JSON 的值一致，并匹配注册的完整矩阵；拒绝协调裁剪、重复、遗漏、未声明条件或模式变化。
2. 对 `protocol.json` 的**原始字节**计算 SHA-256，绑定逐运行 study ID、protocol logical ID、available 标志、协议哈希和驱动构件身份。
3. 校验逐运行 bundle，并核对标准配置、平台资源/价格/存储/拓扑、pin/preflight/actual 放置。新旧协议的执行标识不能互换。
4. 核对输入身份、算法标签及完整成功状态；索引的成功标记不能覆盖证据中的未完成工作流。
5. 核对发布的 makespan、逻辑完成、平均/P95 等待、平均 VM 利用率，并重新计算汇总。指标须是有限非负 JSON 数值，该研究的利用率须在 `[0,1]`；符号与上界按精确十进制判断，不靠 double 舍入放行。

历史 source/binary hashes 不要求等于当前代码，算法契约说明文本也不是按当前文案逐字认证；算法标签和固定条件仍须匹配。协议 SHA 只是内容一致性绑定，不是数字签名或作者身份认证。

CLI 成功输出包含协议、mode 和 `HISTORICAL_PROTOCOL_INTEGRITY` 或 `DECLARED_PROTOCOL_INTEGRITY`，并明确标记 `NOT_A_CURRENT_CODE_RERUN`。Java 验证器按协议 ID 识别兼容身份；独立 Python 工具的 `--historical` 要求见[审计工具说明](<../../scripts/STUDY_AUDIT.md>)，不要混淆两个 CLI。

## 为什么旧数字不能证明当前算法

旧 `peft-comparison-r12-v1` 与 `sensitivity-response-r13-v1` 使用的 LOCAL_PEFT 将当前任务自身成本计入 OCT，并把出口设为平均计算成本。它不是原 PEFT 的后继成本、出口零递推；仅修正 ready-list 或重算统计不能使旧数字成为标准 PEFT 证据。

当前协议使用维护中的执行与算法语义，但协议 ID、测试或完整性校验通过并不自动产生新的效果结论。PEFT 递推、来源夹具与模型边界见[算法契约](<../algorithms/CONTRACTS.md>)。应按完整当前矩阵重新评估，同时保留旧证据身份，不复制旧机制解释或把旧结果改名为新结果。

执行模型也会改变有效 MI、计算窗口、故障重试、数据就绪和终止时刻。即使参数相同或部分数值恰好一致，也不能据此断言旧模型被逐位重放。需要实际运行比较时使用[复跑差异契约](<RERUN_DIFF_CONTRACT.md>)，保留 `DIVERGED`、重建拒绝等有意义的结果。

交接应包含协议原字节、索引、全部引用证据和生成输入；只交汇总表不足以复核。原证据保持只读，新执行和审计使用独立输出，不为迎合当前验证器而重写历史研究。
