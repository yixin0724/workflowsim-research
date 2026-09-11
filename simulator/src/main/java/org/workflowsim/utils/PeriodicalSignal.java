/*
 * 
 *   Copyright 2013-2014 University Of Southern California
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

/**
 * 周期性变化的两级信号模型。
 *
 * <p>在一个周期内，信号按 {@code portion} 保持上界或下界，再切换为另一个值。该类可用
 * 于周期性故障或开销实验的抽象输入，不模拟连续时间物理过程。</p>
 *
 * @author Weiwei Chen
 * @since WorkflowSim Toolkit 1.0
 * @date Apr 9, 2014
 */
public class PeriodicalSignal {

    /** 信号周期，单位为模拟秒。 */
    private double period;
    /** 信号上界。 */
    private double upperbound;
    /** 信号下界。 */
    private double lowerbound;
    /** 一个周期内先出现的信号状态所占比例。 */
    private double portion;
    /** {@code true} 时周期起始于上界；否则起始于下界。 */
    private boolean direction;

    /**
     * 创建周期性两级信号。
     *
     * @param period 周期，单位为模拟秒
     * @param upperbound 信号上界
     * @param lowerbound 信号下界
     * @param portion 起始状态在一个周期中所占比例
     * @param direction {@code true} 时起始状态为上界
     */
    public PeriodicalSignal(double period, double upperbound, double lowerbound, double portion,
            boolean direction) {
        validatePositiveFinite("Period", period);
        validatePositiveFinite("Upper bound", upperbound);
        validatePositiveFinite("Lower bound", lowerbound);
        if (Double.isNaN(portion) || Double.isInfinite(portion)
                || portion < 0.0 || portion > 1.0) {
            throw new IllegalArgumentException("Period portion must be finite and between zero and one");
        }
        this.lowerbound = lowerbound;
        this.upperbound = upperbound;
        this.period = period;
        this.portion = portion;
        this.direction = direction;
    }

    /**
     * 创建以上界为起始状态的周期性两级信号。
     *
     * @param period 周期，单位为模拟秒
     * @param upperbound 信号上界
     * @param lowerbound 信号下界
     * @param portion 起始状态在一个周期中所占比例
     */
    public PeriodicalSignal(double period, double upperbound, double lowerbound, double portion) {
        this(period, upperbound, lowerbound, portion, true);
    }

    /**
     * 创建以上界为起始状态且两个状态各占半个周期的信号。
     *
     * @param period 周期，单位为模拟秒
     * @param upperbound 信号上界
     * @param lowerbound 信号下界
     */
    public PeriodicalSignal(double period, double upperbound, double lowerbound) {
        this(period, upperbound, lowerbound, 0.5);
    }

    /**
     * 获取指定模拟时间的信号值。
     *
     * @param currentTime 模拟时间
     * @return 上界或下界；负时间返回 {@code 0.0}
     */
    public double getCurrentSignal(double currentTime) {
        if (currentTime < 0.0) {
            return 0.0;
        }
        currentTime = currentTime % period;
        if (currentTime <= period * portion) {
            if (direction) {
                return upperbound;
            } else {
                return lowerbound;
            }
        } else {
            if (direction) {
                return lowerbound;
            } else {
                return upperbound;
            }
        }
    }

    /** @return 信号上界 */
    public double getUpperBound() {
        return upperbound;
    }

    /** @return 信号下界 */
    public double getLowerBound() {
        return lowerbound;
    }

    /** @return 周期，单位为模拟秒 */
    public double getPeriod() {
        return period;
    }

    /** @return 起始状态占一个周期的比例 */
    public double getPortion() {
        return portion;
    }
    
    /** @return {@code true} 表示周期起始于上界 */
    public boolean getDirection(){
        return direction;
    }

    private static void validatePositiveFinite(String parameter, double value) {
        if (Double.isNaN(value) || Double.isInfinite(value) || value <= 0.0) {
            throw new IllegalArgumentException(parameter + " must be finite and greater than zero");
        }
    }
}
