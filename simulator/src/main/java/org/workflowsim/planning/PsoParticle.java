package org.workflowsim.planning;

import java.util.Random;

/**
 * PSO 粒子：位置 = Task→VM 映射（每个 Task 一维，值为 VM 列表下标）。
 *
 * <p>忠实移植自 {@code meysamhit/workflowsim-pso}（Pandey et al., AINA 2010 的
 * WorkflowSim 开源实现）的 {@code Particle} 类。速度初始化、更新公式与位置
 * 取整规则与参考实现一致：</p>
 * <ul>
 *   <li>初始化：位置在 [0, vmCount) 均匀随机；速度在
 *       {@code random.nextDouble()*vmCount - vmCount/2.0} 范围初始化；</li>
 *   <li>速度更新：{@code v = w·v + c1·r1·(pBest - x) + c2·r2·(gBest - x)}；</li>
 *   <li>位置更新：{@code x' = round(x + v)}，并钳制到 [0, vmCount-1]。</li>
 * </ul>
 *
 * <p>与参考实现的差异：随机数由 {@code SimulationRandom} 命名组件流提供
 * （根种子可复现），而非硬编码 {@code new Random(42)}。</p>
 *
 * @since WorkflowSim Toolkit 1.0（论文复现轮）
 */
public class PsoParticle {

    private int[] position;
    private double[] velocity;
    private int[] bestPosition;
    private double bestFitness = Double.MAX_VALUE;

    /**
     * 创建随机初始化的粒子。
     *
     * @param taskCount Task 维度数
     * @param vmCount VM 候选数（位置值域）
     * @param random 命名组件随机流
     */
    public PsoParticle(int taskCount, int vmCount, Random random) {
        position = new int[taskCount];
        velocity = new double[taskCount];
        for (int i = 0; i < taskCount; i++) {
            position[i] = random.nextInt(vmCount);
            velocity[i] = random.nextDouble() * vmCount - vmCount / 2.0;
        }
        bestPosition = position.clone();
    }

    /** 拷贝构造（记录全局最优时快照用）。 */
    public PsoParticle(PsoParticle other) {
        this.position = other.position.clone();
        this.velocity = other.velocity.clone();
        this.bestPosition = other.bestPosition.clone();
        this.bestFitness = other.bestFitness;
    }

    /** @return 当前位置（Task→VM 下标映射） */
    public int[] getPosition() { return position; }
    /** @return 粒子历史最优位置 */
    public int[] getBestPosition() { return bestPosition; }
    /** 记录粒子历史最优位置。 */
    public void setBestPosition(int[] p) { this.bestPosition = p.clone(); }
    /** @return 粒子历史最优适应度 */
    public double getBestFitness() { return bestFitness; }
    /** 记录粒子历史最优适应度。 */
    public void setBestFitness(double f) { this.bestFitness = f; }

    /**
     * 标准 PSO 速度更新。
     *
     * @param globalBest 全局最优位置
     * @param w 惯性权重
     * @param c1 认知系数
     * @param c2 社会系数
     * @param random 命名组件随机流
     */
    public void updateVelocity(int[] globalBest, double w, double c1, double c2, Random random) {
        for (int i = 0; i < velocity.length; i++) {
            double r1 = random.nextDouble();
            double r2 = random.nextDouble();
            velocity[i] = w * velocity[i]
                    + c1 * r1 * (bestPosition[i] - position[i])
                    + c2 * r2 * (globalBest[i] - position[i]);
        }
    }

    /**
     * 位置更新：新位置四舍五入到最近 VM 下标并钳制在值域内。
     *
     * @param vmCount VM 候选数
     */
    public void updatePosition(int vmCount) {
        for (int i = 0; i < position.length; i++) {
            double newPos = position[i] + velocity[i];
            int rounded = (int) Math.round(newPos);
            rounded = Math.max(0, Math.min(vmCount - 1, rounded));
            position[i] = rounded;
        }
    }
}
