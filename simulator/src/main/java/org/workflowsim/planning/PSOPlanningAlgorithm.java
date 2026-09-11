package org.workflowsim.planning;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Random;
import org.cloudbus.cloudsim.Vm;
import org.workflowsim.Task;
import org.workflowsim.utils.SimulationRandom;

/**
 * PSO（粒子群优化）工作流规划算法——论文复现实现。
 *
 * <p>复现目标：<b>Pandey, Wu, Guru, Buyya, "A Particle Swarm Optimization-Based
 * Heuristic for Scheduling Workflow Applications in Cloud Computing Environments",
 * AINA 2010</b>；参考开源实现：{@code meysamhit/workflowsim-pso}（该仓库的
 * {@code PSOPlanningAlgorithm/Particle/FitnessFunction}）。</p>
 *
 * <h3>算法核心（与参考实现一致）</h3>
 * <ul>
 *   <li>每个粒子编码一个 Task→VM 映射；种群规模 30，迭代 100；</li>
 *   <li>适应度 = 0.8·总成本 + 0.2·makespan（成本模型见
 *       {@link PsoFitnessFunction}，VM 单价 = mips/1000）；</li>
 *   <li>标准 PSO 速度/位置更新：w=0.7，c1=c2=1.5，位置四舍五入到最近 VM；</li>
 *   <li>收敛后将全局最优映射写入 {@code task.setVmId(...)}，运行期由
 *       {@code SchedulingAlgorithm.STATIC} 执行（平台契约强制）。</li>
 * </ul>
 *
 * <h3>与参考实现的刻意差异（平台契约适配）</h3>
 * <ul>
 *   <li>随机数：使用 {@code SimulationRandom.newJavaRandom("planning.pso")} 命名
 *       组件流（根种子经 campaign 种子控制、可复现），替代参考实现硬编码的
 *       {@code new Random(42)}；</li>
 *   <li>VM 候选先按 VM ID 升序排序，位置值域为该有序列表的下标——确定性平局
 *       契约，与受支持算法族一致；</li>
 *   <li>Task 按 ID 升序排序后与位置维度对应，保证映射定义不依赖解析顺序；</li>
 *   <li>参考实验用 {@code planning=PSO + scheduling=ROUNDROBIN}——在当前平台
 *       上该组合被 {@code SimulationConfig} 拒绝（规划算法必须搭配 STATIC 派发），
 *       且 ROUNDROBIN 调度会覆盖规划映射；复现实验使用 {@code PSO + STATIC}。</li>
 * </ul>
 *
 * <h3>已知模型限制（忠实保留自参考实现）</h3>
 * <p>适应度采用<b>顺序执行 VM 负载模型</b>，忽略 DAG 依赖边——参考实现与论文的
 * 简化成本模型均如此。运行侧 makespan 以引擎依赖释放后的真实执行为准。</p>
 *
 * @since WorkflowSim Toolkit 1.0（论文复现轮）
 */
public class PSOPlanningAlgorithm extends BasePlanningAlgorithm {

    /** 种群规模（参考实现常量）。 */
    public static final int POPULATION_SIZE = 30;
    /** 最大迭代数（参考实现常量）。 */
    public static final int MAX_ITERATIONS = 100;
    /** 惯性权重（参考实现常量）。 */
    public static final double INERTIA_WEIGHT = 0.7;
    /** 认知系数（参考实现常量）。 */
    public static final double COGNITIVE_COEFFICIENT = 1.5;
    /** 社会系数（参考实现常量）。 */
    public static final double SOCIAL_COEFFICIENT = 1.5;
    /** 成本权重（参考实现常量：成本主导，makespan 为辅）。 */
    public static final double COST_WEIGHT = 0.8;

    /** 最近一次收敛的全局最优适应度（供回归测试与复现对账）。 */
    private double lastBestFitness = Double.NaN;
    /** 最近一次运行的实际迭代数。 */
    private int lastIterationCount = 0;
    /** 最近一次收敛的全局最优映射（Task ID → VM ID）。 */
    private java.util.Map<Integer, Integer> lastMapping =
            new java.util.LinkedHashMap<Integer, Integer>();

    @Override
    public void run() {
        List<Task> rawTasks = getTaskList();
        List<? extends Vm> rawVms = getVmList();
        if (rawTasks == null || rawTasks.isEmpty() || rawVms == null || rawVms.isEmpty()) {
            lastBestFitness = Double.NaN;
            lastIterationCount = 0;
            lastMapping.clear();
            return;
        }

        // 确定性顺序：Task 按 ID 升序、VM 按 ID 升序（位置值域 = VM 列表下标）。
        List<Task> tasks = new ArrayList<Task>(rawTasks);
        Collections.sort(tasks, new Comparator<Task>() {
            @Override
            public int compare(Task a, Task b) {
                return Integer.compare(a.getCloudletId(), b.getCloudletId());
            }
        });
        List<Vm> vms = new ArrayList<Vm>(rawVms);
        Collections.sort(vms, new Comparator<Vm>() {
            @Override
            public int compare(Vm a, Vm b) {
                return Integer.compare(a.getId(), b.getId());
            }
        });

        int taskCount = tasks.size();
        int vmCount = vms.size();
        Random random = SimulationRandom.newJavaRandom("planning.pso");

        List<PsoParticle> swarm = new ArrayList<PsoParticle>();
        for (int i = 0; i < POPULATION_SIZE; i++) {
            swarm.add(new PsoParticle(taskCount, vmCount, random));
        }

        int[] globalBestPosition = null;
        double globalBestFitness = Double.MAX_VALUE;

        for (int iter = 0; iter < MAX_ITERATIONS; iter++) {
            for (PsoParticle particle : swarm) {
                double fitness = PsoFitnessFunction.evaluate(particle.getPosition(), tasks, vms,
                        COST_WEIGHT);
                if (fitness < particle.getBestFitness()) {
                    particle.setBestPosition(particle.getPosition());
                    particle.setBestFitness(fitness);
                }
                if (fitness < globalBestFitness) {
                    globalBestPosition = particle.getPosition().clone();
                    globalBestFitness = fitness;
                }
            }
            for (PsoParticle particle : swarm) {
                particle.updateVelocity(globalBestPosition, INERTIA_WEIGHT,
                        COGNITIVE_COEFFICIENT, SOCIAL_COEFFICIENT, random);
                particle.updatePosition(vmCount);
            }
            lastIterationCount = iter + 1;
        }

        // 收敛后写入全局最优映射。
        lastMapping.clear();
        for (int i = 0; i < taskCount; i++) {
            Task task = tasks.get(i);
            Vm vm = vms.get(globalBestPosition[i]);
            task.setVmId(vm.getId());
            lastMapping.put(Integer.valueOf(task.getCloudletId()), Integer.valueOf(vm.getId()));
        }
        lastBestFitness = globalBestFitness;
    }

    /** @return 最近一次收敛的全局最优适应度；未运行或无输入时为 {@code NaN} */
    public double getLastBestFitness() { return lastBestFitness; }

    /** @return 最近一次运行的迭代数 */
    public int getLastIterationCount() { return lastIterationCount; }

    /** @return 最近一次收敛的映射（Task ID → VM ID），插入序 = Task ID 升序 */
    public java.util.Map<Integer, Integer> getLastMapping() {
        return Collections.unmodifiableMap(lastMapping);
    }
}
