# 平台工程编年史

[English](<PLATFORM_ENGINEERING.md>) · **简体中文**

本页是塑造当前平台历次工程的单页叙事记录。它吸收审查轮次的耐久事实，使契约文档保持纯契约：定位在[科学定位声明](<../SCIENCE-POSITION.zh.md>)、行为边界在各契约文档；本页只讲"平台如何被加固"。过程细节留在工程档案（git 忽略工作区）与 Git 历史；本页不覆盖任何契约。

## 第一波——承载正确性的缺陷修复（2026-10-19）

- 红先行修复 6 个缺陷：白名单正则派生（F-001）、深链栈溢出（F-004）、比较器 2^32 回绕（F-005）、聚类自环（F-008）、解析器半提交污染（F-009），及 R13 依赖门控缺陷。
- 2 项围栏为有意契约（F-002/F-003）；M5/M7 测试盲区以新边界/守恒杀手关闭：10 个新测试类、25 个用例。
- 变异试点：9 只定制语义变异全杀，验证测试集杀伤力。

## 第二波——科学仪器升级（2026-10-20）

- Python 独立复算覆盖达 78/78（deadline 族三分支、controlledSharedStorage 4 项含矩阵语义与 (long) 截断对齐）。
- 文献锚封边：独立任务映射族 Braun et al. 2001 黄金表 6/6；HEFT/CPOP/PEFT 零容差规划-执行镜像；DLS/ETF DOI 锚定语义锁定。
- 六链审计闭合，交付科学定位声明与论文指标口径卡。

## 第三波——代码规范化战役（2026-10-22/23）

- 全仓统一正典格式器；Checkstyle Tier-A 扩至 15 条 error 规则、全树零尾欠：RightCurly、LineLength、ImportOrder、AvoidStarImport、NeedBraces、WhitespaceAround 相继晋级。
- 12 项实质 MethodLength 候选行为保持提取；SimulationMetrics 登记为"文件级不可约指标账本"判例。
- grouped-workflows 模块补 BUNDLE 覆盖率棘轮；变异门禁常规化为零存活工具（`scripts/mutation-gate.py`、`make mutation-gate`）。

## 第四波——证据可观测性与现任重认证（2026-10-23）

- events v1 增补 `SIMULATION_TERMINATED` 与 `JOB_EXEC execution_STARTED`：makespan/终端尾/deadline-slack 与决策→开始族实现事件级独立重算；`terminal-clock-ittentity`、`job-start-derivation` 交叉检查内置于独立审计器。
- SHARED_STORAGE 全五规划器锁入零容差规划-执行镜像（309 集成测试）。
- R13 v2 以 546 次运行现任重认证并与影响层逐位一致，直接确认规范化战役的行为中立性。
- 注册算轨从 SPI 发现经 fail-fast 矩阵到验证过的证据包——"研究生作业"验收通过。

## 开放记录

- F-001..F-009 全部在工程档案（BUGS 账本）有处置；无悬置项。金丝雀台账（11 只注册变异）见[变异门禁](<../advanced/MUTATION_GATE.zh.md>)。
