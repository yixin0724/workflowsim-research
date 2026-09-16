# RETENTION — Fat-tree × 调度联合实验（R7）

- **研究问题 / 矩阵 / 指标与统计方法**（PROTOCOL/MATRIX/METRICS 等价物）：
  `docs/experiments/FATTREE_SCHEDULING_CAMPAIGN.md`（设计与验收）与
  `docs/experiments/FATTREE_SCHEDULING_RESULTS.md`（实测结论）。
- **保留工件**：
  - `campaign-results.json`（schema `workflowsim-fattree-campaign-v1`，
    360 次运行逐运行记录 + 弱单调诊断 + 排名汇总 + 配对统计）；
  - `campaign-results.md`（同数据的人读表格，结果文档表格的机器生成源）。
- **来源身份**：`FatTreeSchedulingCampaignExecutor`，2026-09-16 R8 全面审计
  重录运行（JDK 17，`mvn -o -pl :workflowsim-experiments -am ... compile exec:java`，
  datasets 根 = 仓库 `datasets/`，seed 91，全部运行
  `COMPLETED_SUCCESSFULLY`）。
- **R8 重录原因**（取代 R7 原始运行工件）：审计修复了
  ① FatTree 链路带宽 ÷8 单位 bug（F1，修复后链路容量按声明值全量注册）；
  ② 共享文件本地判定 bug（F2）；③ R5 到达时刻归属 bug（P0-1）；
  ④ Wilcoxon 配对统计的零差值/小样本缺陷。R7 原始工件产生于 bug 生效的
  旧物理（链路实际只有声明值的 1/8、Wilcoxon 含零差值、R5 到达归属错误），
  不再可作证据。**重录后核心现象**：链路声明带宽（1.0 MB/s）与 VM 端点
  带宽相等，链路层永不束缚单流，R6 ≡ R2 逐位相等、结构/带宽敏感性轴全退化；
  n50/n100 翻转至 PSO 的事实保留（根因为 R2 端点争用，非 fat-tree 拓扑）。
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
