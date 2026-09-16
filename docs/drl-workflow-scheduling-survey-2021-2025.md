# 基于 ML/DRL 的科学工作流调度研究现状调研（2021–2025）

**方法与可信度说明**：本会话的 web_search 工具因 DeepSeek API key 缺失不可用，实际检索通过 web_fetch/curl 调用 OpenAlex API、Semantic Scholar API、Crossref 与出版商页面（Springer）完成；ScienceDirect 与 Bing/Mojeek 被拒，故 Elsevier 系论文（标注 ⚠）仅有题录级信息，其实验细节未逐一验证。所有引用均来自实际检索结果，附 DOI/arXiv URL。

---

## 1. 主要技术路线分类与代表论文

### (a) DQN / DDQN 系
- **Li, Huang & Wang (2021)** — weighted double DQN，双目标多工作流调度。*Cluster Computing*。https://doi.org/10.1007/s10586-021-03454-6
- **Zhang, Zhao & Liu (2023)** — 改进 DQN，多云数据密集型工作流（业务约束 + 数据传输优化）。*Journal of Cloud Computing*。https://doi.org/10.1186/s13677-023-00504-9
- **Dong, Xue & Tang (2022)** — RLFTWS：DDQN 自适应选择"重提交/副本"容错动作，最小化 makespan 与资源占用。*Applied Intelligence*。https://doi.org/10.1007/s10489-022-03963-w

### (b) Actor-Critic / PPO 系
- **Dong, Xue & Xiao (2021)** — Actor-Critic + 改进 Pointer Network 生成任务排序 + HEFT 分配，policy gradient 训练。*Journal of Ambient Intelligence and Humanized Computing*。https://doi.org/10.1007/s12652-020-02884-1
- **Jayanetti, Halgamuge & Buyya (2022)** — 分层动作空间（边缘/云分离）+ 混合 Actor-Critic + PPO，边缘-云先序约束任务的能耗-时间联合优化。*Future Generation Computer Systems*。https://doi.org/10.1016/j.future.2022.06.012
- **Zhu, Zhang & Zeadally (2024)** — GNN 任务嵌入 + PPO **在线**学习调度器 + intrinsic reward 即时纠偏，边缘-云持续到达负载。*IEEE Transactions on Cloud Computing*。https://doi.org/10.1109/tcc.2024.3408006
- **Chandrasiri & Meedeniya (IEEE Access，2026 卷)** ⚠ — 2SD-GAT：两阶段 PPO（先选任务、再选 VM）+ GAT 编码 + preference-based 多目标 reward，hypervolume 提升 26.8%。https://doi.org/10.1109/access.2026.3669772

### (c) A3C / 异步系
- **Mangalampalli, Karri & Mohanty (2024)** — 改进 A3C (IA3C) 多目标优先级任务调度器。*IEEE Access*。https://doi.org/10.1109/access.2024.3355092
- **Mounesan, Lemus & Yeddulapalli (2024)** — 事件驱动 A3C，志愿者边缘云数据密集科学工作流（含 testbed 验证）。*arXiv:2407.01428*。https://arxiv.org/abs/2407.01428

### (d) GNN + RL（DAG 结构编码）
- **Mao, Schwarzkopf et al. (2019, SIGCOMM)** — 奠基工作 Decima：GCN + RL 学习 Spark 作业调度。https://doi.org/10.1145/3341302.3342080
- **Lee, Cho & Jang (2021)** — GoSu：GCN + policy gradient 学习 DAG 优先级分配。*IEEE Access*。https://doi.org/10.1109/access.2021.3130407
- **Zhou, Li & Luo (2022)** — LACHESIS：GNN 感知依赖 + 任务复制规则，异构集群 DAG 调度。*IEEE MDM 2022*。https://doi.org/10.1109/mdm55031.2022.00040
- **Li, Li & Lv (2023)** — GASTO：GNN + seq2seq + **meta-RL**，快速适应新环境。*IEEE TNSM*。https://doi.org/10.1109/tnsm.2023.3250395
- **Liu, Huang & Gao (2024)** — GA-DRL：双向 GAT + 非均匀邻域采样 + DDQN，动态车载云，可泛化到未见拓扑。*IEEE TNSM*（arXiv: https://arxiv.org/abs/2307.00777）。https://doi.org/10.1109/tnsm.2024.3387707
- **Sun, Theile & Qin (2024)** — EGS：DRL + 图神经网络生成边以收缩 DAG 宽度，比 SOTA 启发式用更少处理器。*IEEE Transactions on Computers*。https://doi.org/10.1109/tc.2024.3350243
- **Wang, Hu & Min (2021)** — S2S 网络 + off-policy policy gradient（clipped surrogate）做依赖任务卸载。*IEEE Transactions on Computers*。https://doi.org/10.1109/tc.2021.3131040

### (e) 多智能体 RL (MARL)
- **Jayanetti, Halgamuge & Buyya (2024)** — MARL 框架，绿电感知的多云地理分布式工作流调度。*IEEE TPDS*。https://doi.org/10.1109/tpds.2024.3360448
- **Cheng, He & Gu (2024)** — MARS：MADRL 隐私保护混合云实时工作流调度（agents 部署在 VM 上协作学习）。*IEEE ICPADS 2024*。https://doi.org/10.1109/icpads63350.2024.00091
- **Zhadan, Allahverdyan & Kondratov (2023)** — MARL 决定每步用哪条启发式规则的自适应 DAG 调度。*ACM TIST*。https://doi.org/10.1145/3610300

### (f) 元 RL / 层次 RL / 混合
- **Xiu, Li & Long (2023)** — MRLCC：meta-RL 快速适应新云环境。*Journal of Cloud Computing*。https://doi.org/10.1186/s13677-023-00440-8
- **Liu, Chen & Ouyang (2023)** — MLR-TC-DRLS：量化 DRL 调度的鲁棒性（重训练时间），meta-DRL 保截止时间。*FGCS*。https://doi.org/10.1016/j.future.2023.03.029
- **Cui, Peng & Li (2025)** — 层次 DRL（先选 VM 簇再选 VM）。*PLoS ONE*。https://doi.org/10.1371/journal.pone.0329669
- **Wang, Rodriguez & Lipovetzky (2025)** — HeraSched：层次 RL 的 HPC 作业选择+分配。*The Journal of Supercomputing*。https://doi.org/10.1007/s11227-025-07396-3
- **Zhang, Cheng et al. (2023)** ⚠ — GA + DRL 混合，实时工作流成本感知。*Expert Systems with Applications*。https://doi.org/10.1016/j.eswa.2023.120972
- **He, Gu & Hu (2025)** ⚠ — DRL，隐私/安全约束混合云实时工作流。*ESWA*。https://doi.org/10.1016/j.eswa.2025.127376

### 综述（可直接引为分类学依据）
- **Zhou, Tian & Buyya (2024)** — DRL 云资源调度综述。*Artificial Intelligence Review*。https://doi.org/10.1007/s10462-024-10756-9
- **Sanjalawe et al. (2025)** — AI 驱动云作业调度综合综述。*Artificial Intelligence Review*。https://doi.org/10.1007/s10462-025-11208-8
- **Zabihi et al. (2023)** — 计算卸载 RL 方法系统综述。*ACM Computing Surveys*。https://doi.org/10.1145/3603703
- **Ismail et al. (2025)** — MEC 资源调度 DRL 综述。*Cluster Computing*。https://doi.org/10.1007/s10586-024-04893-7

## 2. 2023–2025 最新趋势
1. **单智能体 → 多智能体/层次化**：MARL（TPDS 2024、MARS ICPADS 2024）与层次 RL（PLoS ONE 2025、HeraSched 2025）成为应对可扩展性与去中心化控制的主流。
2. **GNN 成为 DAG 编码标配**：双向聚合 GAT（GA-DRL 2024）、两阶段 PPO+GAT（2SD-GAT）、GNN 嵌入 + PPO（Zhu TCC 2024），并显式追求"未见拓扑泛化"。
3. **Meta-RL 解泛化/鲁棒性**：GASTO (2023)、MRLCC (2023)、MLR-TC-DRLS (2023) 明确针对"换环境要重训"的痛点。
4. **目标多元化**：绿电/碳感知（TPDS 2024）、隐私保护（MARS 2024）、多目标 Pareto（2SD-GAT 用 hypervolume/IGD 评价）、容错（RLFTWS 2022；Cheng TSUSC 2023 https://doi.org/10.1109/tsusc.2023.3303898）。
5. **在线/实时调度**：从静态离线决策转向持续到达、在线纠偏（Zhu TCC 2024 的 intrinsic reward；ESWA 2024/2025 的 real-time workflow ⚠）。
6. **LLM-agent 做调度**：本轮检索未找到可靠的 LLM 调度工作流论文，该趋势在本领域尚不确定（仅有 NetLLM 等网络管理方向）。

## 3. 实验设置
- **模拟器**：WorkflowSim（Zhang JCC 2023、Mangalampalli IEEE Access 2024 明确使用）、CloudSim（Mangalampalli A3C IEEE Access 2024；新工具 CloudSim 7G, Andreoli & Cucinotta, *Software: Practice and Experience* 2025, https://doi.org/10.1002/spe.3413）；大量工作用**自研模拟器**（TC/TNSM 系、Buyya 组）。基准工具链方面，WfChef（Coleman, Casanova & da Silva, *FGCS* 2023, https://doi.org/10.1016/j.future.2023.04.031）与 WfBench（da Silva et al., ACM SC-W 2025, https://doi.org/10.1145/3776592.3777488）代表工作流生成/基准化的最新努力。
- **Benchmark 工作流**：Montage、CyberShake、Epigenomics、LIGO（Mangalampalli 2024 摘要明确列出，即 Pegasus 系实例）；合成随机 DAG（Wang TC 2021、GoSu、Zhadan 2023）；真实 trace（HeraSched 2025、2SD-GAT）；WfInstances 数据集被用作实例来源（见 WfChef/WfBench 引文脉络）。
- **基线**：list scheduling（HEFT、CPOP、EFT/HEFD）；元启发式（MOPSO、NSGA-II、ACO、GA、CSO）；朴素 DRL（如 DQN 对比改进 DQN）；MILP 最优解（EGS）；随机。

## 4. 公认的研究空白/挑战
1. **泛化性差**：策略绑定特定环境分布，换拓扑/负载即失效——这是 meta-RL 论文（MRLCC、GASTO、MLR-TC-DRLS）公认动机；MLR-TC-DRLS 甚至把"重训练时间"作为鲁棒性指标。
2. **Reward 设计**：稀疏/延迟 reward 与多目标权衡难表达；近期方案包括 intrinsic reward（Zhu TCC 2024）、preference-based 多目标 reward（2SD-GAT）、负 makespan 直接做 reward（Dong 2021）。
3. **与经典基线的公平比较**：各论文自选基线集不一致（HEFT 有时缺席、只比弱元启发式），跨论文结果不可比；Zhou et al. (AI Review 2024) 与 Zabihi et al. (CSUR 2023) 均指出评测标准化是开放方向（具体论述需读全文确认）。
4. **可复现性**：多为自研模拟器、未开源；Decima 开源模拟器与 WfChef/WfBench 是少数标准化尝试。训练超参、随机种子报告普遍不完整（此为行业观察，非单篇结论）。

## 5. 对 CloudSim 3/WorkflowSim 平台的启示
平台已有观测/动作/奖励契约 + RL_POLICY 在线调度器闭环，只缺学习算法。最自然的下一步按优先级：
1. **接 RL 算法库跑通基线**：把 RL 环境封装成 Gymnasium 兼容接口，用 Stable-Baselines3/RLlib 实现 DQN→PPO→A3C 基线。文献中 Zhang (JCC 2023) 与 Mangalampalli (IEEE Access 2024) 都基于 WorkflowSim，本平台的实验结果可直接与之对表。
2. **GNN 编码 + 两阶段 PPO**：DAG 用 GCN/GAT 编码，动作空间拆为"选任务→选资源"两阶段（对齐 2SD-GAT 与 Zhu TCC 2024 的 SOTA 形态）。
3. **标准化评测套件回应两大空白**：内置 Pegasus 工作流实例（Montage/CyberShake/Epigenomics/LIGO）+ HEFT/Min-Min/FIFO 经典基线 + 统一指标（makespan/cost/energy），即可直接产出"公平比较"与"可复现"导向的贡献。
4. **Meta-RL/分布泛化实验**：平台天然可参数化采样环境分布（到达率、异构度、故障率），适合复现 MRLCC/GASTO 式的跨环境快速适应评测。
