# RETENTION — Fat-tree × 调度联合实验（R7）

- **研究问题 / 矩阵 / 指标与统计方法**（PROTOCOL/MATRIX/METRICS 等价物）：
  `docs/experiments/FATTREE_SCHEDULING_CAMPAIGN.md`（设计与验收）与
  `docs/experiments/FATTREE_SCHEDULING_RESULTS.md`（实测结论）。
- **保留工件**：
  - `campaign-results.json`（schema `workflowsim-fattree-campaign-v1`，
    360 次运行逐运行记录 + 弱单调诊断 + 排名汇总 + 配对统计）；
  - `campaign-results.md`（同数据的人读表格，结果文档表格的机器生成源）。
- **来源身份**：`FatTreeSchedulingCampaignExecutor`，2026-09-16 正式运行
  （JDK 17，`mvn -o -pl :workflowsim-experiments -am ... compile exec:java`，
  datasets 根 = 仓库 `datasets/`，seed 91，全部运行
  `COMPLETED_SUCCESSFULLY`）。
- **保留理由**：结果文档只嵌入汇总表格；逐运行原始记录是排名翻转、
  配对 Wilcoxon 与敏感性退化结论的审计依据，且黄金值 IT
  （`FatTreeCampaignGoldenIntegrationTest`）锁定的数值可与本工件逐位对照。
- **验证命令**（复现比对；`generatedAt` 字段除外应逐位一致）：

  ```bash
  mvn -o -pl :workflowsim-experiments -am \
    -Dexec.mainClass=org.workflowsim.experiments.fattree.FatTreeSchedulingCampaignExecutor \
    -Dexec.args="$PWD/datasets /tmp/fattree-verify" \
    -Dworkflowsim.experiments.exec.skip=false compile exec:java
  # 然后 diff /tmp/fattree-verify/campaign-results.json 与本目录工件
  #（除 generatedAt 外应无差异；makespan 全部逐位一致）。
  ```

- **清理范围**：临时输出目录（如 `/tmp/fattree-campaign-run*`、
  `/tmp/fattree-verify`）不入库；本目录两个工件长期保留。
