/*
 * 
 *   Copyright 2012-2013 University Of Southern California
 * 
 *   Licensed under the Apache License, Version 2.0 (the "License");
 *   you may not use this file except in compliance with the License.
 *   You may obtain a copy of the License at
 * 
 *   http://www.apache.org/licenses/LICENSE-2.0
 * 
 *   Unless required by applicable law or agreed to in writing,
 *   software distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 * 
 */
package org.workflowsim.utils;

import org.apache.commons.math3.distribution.RealDistribution;

/**
 * 按 {@link PeriodicalSignal} 在周期内切换参数的分布生成器。
 *
 * <p>它继承 {@link DistributionGenerator} 的预采样机制，并在每个样本位置根据周期信号
 * 选择上界或下界对应的分布。该机制是周期性开销的简化抽象。</p>
 *
 * @since WorkflowSim Toolkit 1.0
 * @date Apr 11, 2014
 * @author Weiwei Chen
 */
public class PeriodicalDistributionGenerator extends DistributionGenerator{
    
    /** 控制分布参数切换的周期性信号。 */
    protected PeriodicalSignal signal;
    
    /**
     * 创建不带先验知识的周期性分布生成器。
     *
     * @param dist 分布族
     * @param scale 尺度参数
     * @param shape 形状参数
     * @param signal 周期性信号
     */
    public PeriodicalDistributionGenerator(DistributionFamily dist, double scale, double shape, PeriodicalSignal signal){
        super(dist, scale, shape);
        this.signal = requireSignal(signal);
        // 初始样本必须按周期信号选择分布，而不是沿用父类的固定分布抽样。
        double currentTime = 0.0;
        samples = generatePeriodicalSamples(currentTime);
                
        updateCumulativeSamples();
        cursor = 0;
       
    }
    
    /**
     * 创建带先验知识的周期性分布生成器。
     *
     * @param dist 分布族
     * @param scale 尺度参数
     * @param shape 形状参数
     * @param a 先验形状参数
     * @param b 先验尺度参数
     * @param c 先验似然参数
     * @param signal 周期性信号
     */
    public PeriodicalDistributionGenerator(DistributionFamily dist, double scale, double shape, double a, double b, double c, PeriodicalSignal signal){
        super(dist, scale, shape, a, b, c);
        this.signal = requireSignal(signal);
        double currentTime = 0.0;
        samples = generatePeriodicalSamples(currentTime);
        updateCumulativeSamples();
        cursor = 0;
    }

    /**
     * 创建带先验知识和稳定随机流名称的周期性分布生成器。
     *
     * <p>显式随机流可避免历史示例在同一模型内因构造顺序共享默认流身份。</p>
     *
     * @param dist 分布族
     * @param scale 基础尺度参数
     * @param shape 形状参数
     * @param a 先验形状参数
     * @param b 先验尺度参数
     * @param c 先验似然参数
     * @param signal 周期性信号
     * @param randomStream 稳定随机组件名称
     */
    public PeriodicalDistributionGenerator(DistributionFamily dist, double scale, double shape,
            double a, double b, double c, PeriodicalSignal signal, String randomStream) {
        super(dist, scale, shape, a, b, c, randomStream);
        this.signal = requireSignal(signal);
        double currentTime = 0.0;
        samples = generatePeriodicalSamples(currentTime);
        updateCumulativeSamples();
        cursor = 0;
    }
    /** 按当前累计模拟时间扩展预采样序列。 */
    @Override
    public void extendSamples() {
        double currentTime = cumulativeSamples[cumulativeSamples.length - 1];
        double[] new_samples = generatePeriodicalSamples(currentTime);
        samples = concat(samples, new_samples);
        updateCumulativeSamples();
    }
    
    /**
     * 生成一批按周期信号切换的样本。
     *
     * @param currentTime 当前累计模拟时间
     * @return 新样本数组
     */
    private double[] generatePeriodicalSamples(double currentTime){
        RealDistribution distribution_upper = getDistribution(signal.getUpperBound(), shape);
        RealDistribution distribution_lower = getDistribution(signal.getLowerBound(), shape);
        RealDistribution distribution;
        double[] periodicalSamples = new double[SAMPLE_SIZE];
        boolean direction = signal.getDirection();
        for(int i = 0; i < SAMPLE_SIZE; i ++){
            if(currentTime % signal.getPeriod() < signal.getPeriod() * signal.getPortion()){
                if(direction){
                    distribution = distribution_upper;
                }else{
                    distribution = distribution_lower;
                }
            }else{
                if(direction){
                    distribution = distribution_lower;
                }else{
                    distribution = distribution_upper;
                }
            }
            periodicalSamples[i] = distribution.sample();
            currentTime += periodicalSamples[i];
            
        }
        return periodicalSamples;
    }

    private static PeriodicalSignal requireSignal(PeriodicalSignal value) {
        if (value == null) {
            throw new IllegalArgumentException("Periodical signal cannot be null");
        }
        return value;
    }
}
