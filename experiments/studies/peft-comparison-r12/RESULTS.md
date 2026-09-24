# S5 PEFT 对比研究：结果解读

正式协议 `peft-comparison-r12-v1` 共126次运行，全部成功并通过逐运行证据、完整矩阵与统计重算校验。完整自动结果见 [结果表](../../../output/peft-comparison-r12/results.md)，条件与平台限制见 [协议](PROTOCOL.md)。R10 的504次证据保持冻结，未重跑。

## 可以支持的结论

1. **在本同构平台上，LOCAL_PEFT 与 LOCAL_HEFT 接近等价，无 Holm 调整后显著性。** 经典DAX 4VM：PEFT 3胜1平1负，中位改善 endpoint/宽链路 +1.664889%、受限 +0.037442%；16VM：endpoint/宽链路 0胜3平2负（中位 ≈0，即多数DAG产生与HEFT完全相同的makespan），受限 1胜2平2负。所有比较族调整后 p ≥ 0.75。
2. **同条件下的 CPOP 与 R10 结论一致：接近但非等价。** 经典 4VM CPOP 3胜2负（中位 +0.001542%～+0.012393%），16VM endpoint/宽链路 1胜4负（−0.014509%），受限 3胜2负（+0.090991%）。S5 的 CPOP 胜平负、中位改善与原始 p 值与 R10 冻结结果逐条件完全一致（同矩阵、同种子、确定性规划器；仅 Holm 调整值因比较族从三候选变两候选而不同），这本身是研究管线可复现性的交叉验证。
3. **合成通信密集负载上 PEFT 不占优。** 4VM：PEFT 1胜1负，中位 endpoint/宽链路 −7.601517%、受限 −15.116550%；同条件 CPOP 2胜0负（约 +15%）。16VM：PEFT 两个DAG均与HEFT精确打平，CPOP 0胜1平1负（约 −13%～−15%）。
4. **大量精确平局印证平台限制。** VM同构（1000 MIPS、1 PE）使 PEFT 的 EFT+OCT 前瞻项在所有候选VM上相同，前瞻退化为纯排序差异（rank_o 对 rank_u）；当两种排序导出相同调度时 makespan 完全一致。因此本研究度量的是"PEFT排序在同构平台上的代价/收益"，不是论文中异构平台上 OCT 前瞻的完整价值。

## 不可主张的范围

- 不能据此声称 PEFT 劣于或优于 HEFT/CPOP：样本小（经典5、合成2），无显著性，且平台同构使 PEFT 的核心机制未被激活。
- 不能外推到真实云、异构VM或事件键控随机数比较。
- LOCAL_PEFT 的论文正确性由独立单测保证（论文fixture精确OCT表、映射 {2,2,0,1,0,2,0,0,1,1}、相对makespan 76 < HEFT 80），与本研究的行为结论互不替代。

## 后续

异构平台或 R13 敏感性响应面才能公平评估 OCT 前瞻项的价值；本研究把 S5 的定位固定为"方法落地后在既有冻结矩阵上的行为记录与管线交叉验证"。

## 证据交接

- 正式运行索引：[network-study.json](../../../output/peft-comparison-r12/network-study.json)。
- 全部工件目录：项目 `output/peft-comparison-r12/`，包括冻结SHA-256协议、每次v4 manifest/metrics/events和生成输入。
- 可交接压缩包：[完整证据包](../../../output/peft-comparison-r12-evidence.tar.gz)。
- 对照的 R10 冻结研究：[R10 协议](../network-limited-r10/PROTOCOL.md)、[R10 结果](../network-limited-r10/RESULTS.md)。

时间、带宽和资源配置均为抽象模型；尚未进行现实环境校准。本轮不建设训练器。
