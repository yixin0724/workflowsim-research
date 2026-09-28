# RETENTION — Fat-tree × 调度联合实验（R7，R8 再标定重录）

> **历史保留说明**：本目录保留的是当时模型的记录，不是当前执行语义的认证数据。后续 CPOP、网络、CPU 工作量及 LOCAL 数据可达时间修正已改变部分结果；当前状态见[正确性整改记录](../../../docs/advanced/SIMULATION_CORRECTNESS_REPAIR.md)。不得为维持逐位一致而覆盖本目录历史工件或调整新模型。

- **研究问题 / 矩阵 / 指标与统计方法**（PROTOCOL/MATRIX/METRICS 等价物）：
  `docs/experiments/FATTREE_SCHEDULING_CAMPAIGN.md`（设计与验收）与
  `docs/experiments/FATTREE_SCHEDULING_RESULTS.md`（实测结论）。
- **保留工件**：
  - `campaign-results.json`（schema `workflowsim-fattree-campaign-v1`，
    360 次运行逐运行记录 + 弱单调诊断 + 排名汇总 + 配对统计）；
  - `campaign-results.md`（同数据的人读表格，结果文档表格的机器生成源）。
- **来源身份**：`FatTreeSchedulingCampaignExecutor`，2026-09-16 R8 再标定
  重录运行（13:42，JDK 17，`mvn -o -pl :workflowsim-experiments -am ...
  compile exec:java`，datasets 根 = 仓库 `datasets/`，seed 91，全部运行
  `COMPLETED_SUCCESSFULLY`；链路带宽基线 0.125 MB/s = VM 端点 1/8、
  A4 = 1.25 MB/s，执行器常量 `BASELINE_LINK_BANDWIDTH_MB`）。
- **工件演化（三代，后代取代前代）**：
  1. R7 原始运行：F1 ÷8 单位 bug 生效的旧物理（声明 1.0 MB/s 实际 0.125）、
     F2/P0-1/Wilcoxon 缺陷在场——不再可作证据；
  2. R8 审计重录（2026-09-16 上午）：F1/F2 修复后、声明 1.0 == 端点的对称
     供给——实测 R6 ≡ R2 逐位相等、拓扑轴全退化（审计报告 §3.3 N-2 的
     证据运行，工件已被本代取代，退化事实由审计报告与文档历史节承载）；
  3. **R8 再标定重录（本工件）**：经用户授权把声明链路带宽降到端点以下
     （0.125 = 恰为修复前实际物理），链路束缚恢复、R6 &gt; R2 与 A4 带宽轴
     判别力恢复。交叉验证：heft 论文例 R6 列与修复前 R7 黄金值逐位相等
     （5738.1/5854.1/7206.1/7262.1）——同时验证 F1 修复语义与再标定等价性。
- **保留理由**：逐运行原始记录是当时排名、统计和敏感性结论的审计依据。
  当前黄金值 IT 锁定修正后的模型，不能再要求与这份历史工件整体逐位相同。
- **差异分析命令**（按历史参数在当前代码下重新运行；输出必须使用新目录）：

  ```bash
  mvn -o -pl :workflowsim-experiments -am \
    -Dexec.mainClass=org.workflowsim.experiments.fattree.FatTreeSchedulingCampaignExecutor \
    -Dexec.args="$PWD/datasets /tmp/fattree-verify" \
    -Dworkflowsim.experiments.exec.skip=false compile exec:java
  # 对比新输出与本目录历史工件并记录差异。
  # 数值变化需解释模型原因，不要求逐位相同，也不要覆盖历史结果。
  ```

- **清理范围**：临时输出目录（如 `/tmp/fattree-campaign-run*`、
  `/tmp/fattree-verify`）不入库；本目录两个工件长期保留。
