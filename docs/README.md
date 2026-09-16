# WorkflowSim 文档中心

按使用路径分层组织。新用户从 `getting-started/` 开始，研究者按需查阅 `algorithms/` 与 `experiments/`。

## 目录结构

```text
docs/
├── getting-started/            # 新用户入门（按阅读顺序）
│   ├── QUICK_START.md          # 5 分钟运行第一个仿真 ⭐
│   ├── CODE_CONFIG_EXPERIMENTS.md  # 代码配置式实验指南（推荐）⭐⭐⭐
│   ├── RUN_EXPERIMENTS.md      # 实验运行完整指南（参数/算法/IDEA 配置）⭐
│   ├── IDEA_SETUP.md           # IDEA 配置指南（解决 experiments 模块显示问题）🔧
│   ├── ALGORITHMS.md           # 三类算法的本质区别与底层原理 ⭐
│   ├── DATASETS.md             # 如何选择工作流输入数据集
│   └── BUILD.md                # Maven 构建、测试、示例与验证器命令
│
├── algorithms/                 # 算法参考（研究前必读）
│   ├── CATALOG.md              # 算法目录、决策层与主张边界
│   ├── CONTRACTS.md            # 算法与指标的语义契约（测试验证了什么）
│   └── LEGACY_MIGRATION.md     # 遗留算法弃用原因与迁移指南
│
├── experiments/                # 实验设计与协议
│   ├── REPRODUCIBILITY.md      # 可复现性契约（随机性/成本/deadline 语义）
│   ├── CAMPAIGNS.md            # 多场景/多重复实验与数据移动模型协议
│   ├── FATTREE_SCHEDULING_CAMPAIGN.md  # Fat-tree × 调度联合实验设计（R7）
│   ├── FATTREE_SCHEDULING_RESULTS.md   # R7 实测结果（R8 审计重录版：排名翻转结论 + 链路单位修复后的退化事实）
│   └── reference-baselines/
│       ├── P7_PROTOCOL.md      # 冻结参考基线 P7 的实验协议
│       └── P7_RESULTS.md       # P7 已记录结果（历史记录）
│
├── research/                   # 专题原理研读与设计记录
│   ├── FAT_TREE_PRINCIPLES.md  # Al-Fares k-Pod Fat-tree 原理研读（R6 Phase 0）
│   └── FAT_TREE_DESIGN.md      # Fat-tree 链路争用模型设计（R6 Phase 1）
│
└── advanced/                   # 专题与维护者文档
    ├── QUALITY_AUDIT.md        # 质量审计报告（算法正确性/指标准确性）
    ├── WFINSTANCES_PILOT.md    # WfInstances 1.5 输入转换试点
    ├── RESEARCH_ROADMAP.md     # 科研能力演进路线图（R1-R7 轮次规划）
    └── CODE_STYLE.md           # 源码注释规范
```

## 按问题查找

| 想了解的问题 | 阅读位置 |
| --- | --- |
| 如何快速上手？ | [`getting-started/QUICK_START.md`](getting-started/QUICK_START.md) |
| 如何在代码中配置参数并直接运行实验？ | [`getting-started/CODE_CONFIG_EXPERIMENTS.md`](getting-started/CODE_CONFIG_EXPERIMENTS.md) ⭐⭐⭐ |
| 如何配置参数、选择算法、在 IDEA 中运行实验？ | [`getting-started/RUN_EXPERIMENTS.md`](getting-started/RUN_EXPERIMENTS.md) ⭐ |
| experiments 模块在 IDEA 中显示橙色咖啡杯？ | [`getting-started/IDEA_SETUP.md`](getting-started/IDEA_SETUP.md) 🔧 |
| 三类算法的区别和原理？ | [`getting-started/ALGORITHMS.md`](getting-started/ALGORITHMS.md) |
| 如何选数据集？ | [`getting-started/DATASETS.md`](getting-started/DATASETS.md) |
| 如何构建、运行示例、执行 P7？ | [`getting-started/BUILD.md`](getting-started/BUILD.md) |
| 算法的决策层和可比较范围？ | [`algorithms/CATALOG.md`](algorithms/CATALOG.md) |
| 测试到底验证了什么？ | [`algorithms/CONTRACTS.md`](algorithms/CONTRACTS.md) |
| 旧算法标签为什么被拒绝？如何迁移？ | [`algorithms/LEGACY_MIGRATION.md`](algorithms/LEGACY_MIGRATION.md) |
| 随机性、成本和 deadline 的含义？ | [`experiments/REPRODUCIBILITY.md`](experiments/REPRODUCIBILITY.md) |
| 如何设计多场景/多重复实验？ | [`experiments/CAMPAIGNS.md`](experiments/CAMPAIGNS.md) |
| P7 冻结基线的矩阵和结果？ | [`experiments/reference-baselines/P7_PROTOCOL.md`](experiments/reference-baselines/P7_PROTOCOL.md)、[`P7_RESULTS.md`](experiments/reference-baselines/P7_RESULTS.md) |
| 代码质量是否可信？ | [`advanced/QUALITY_AUDIT.md`](advanced/QUALITY_AUDIT.md) |
| WfInstances 解析了哪些字段？ | [`advanced/WFINSTANCES_PILOT.md`](advanced/WFINSTANCES_PILOT.md) |
| 平台接下来要补全哪些科研能力？ | [`advanced/RESEARCH_ROADMAP.md`](advanced/RESEARCH_ROADMAP.md) |
| Fat-tree 网络拓扑的原理与设计？ | [`research/FAT_TREE_PRINCIPLES.md`](research/FAT_TREE_PRINCIPLES.md)、[`research/FAT_TREE_DESIGN.md`](research/FAT_TREE_DESIGN.md) |
| 网络争用如何改变调度算法的相对优劣？ | [`experiments/FATTREE_SCHEDULING_CAMPAIGN.md`](experiments/FATTREE_SCHEDULING_CAMPAIGN.md)（设计）、[`experiments/FATTREE_SCHEDULING_RESULTS.md`](experiments/FATTREE_SCHEDULING_RESULTS.md)（实测结论） |
| 源码注释怎么写？ | [`advanced/CODE_STYLE.md`](advanced/CODE_STYLE.md) |

## 推荐阅读路径

**新用户**：QUICK_START → CODE_CONFIG_EXPERIMENTS → ALGORITHMS → DATASETS → BUILD

**准备发论文的研究者**：algorithms/CATALOG → algorithms/CONTRACTS → experiments/REPRODUCIBILITY → experiments/CAMPAIGNS → advanced/QUALITY_AUDIT → advanced/RESEARCH_ROADMAP

**项目维护者**：advanced/CODE_STYLE → algorithms/LEGACY_MIGRATION → advanced/QUALITY_AUDIT
