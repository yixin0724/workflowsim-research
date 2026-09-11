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
package org.workflowsim.reclustering;

/**
 * 在给定抽象运行时间、开销和失效分布参数的前提下，估计任务重聚类大小。
 *
 * <p>该估计器使用旧版解析近似，适合作为兼容性恢复策略的一部分；其参数并非
 * 自动来自真实平台校准。</p>
 *
 * @author Weiwei Chen
 * @since WorkflowSim Toolkit 1.0
 * @date Mar 11, 2014
 */
public class ClusteringSizeEstimator {

    /**
     * 在 {@code n / k >> r} 的近似前提下计算目标函数。
     *
     * @param k 聚类大小
     * @param t 任务运行时间
     * @param s 系统开销
     * @param theta 到达间隔估计参数
     * @param phi_gamma 分布形状参数
     * @param phi_ts 时间缩放参数
     * @return 近似目标函数值
     */
    protected static double f(double k, double t, double s, double theta, double phi_gamma, double phi_ts) {
        double d = (k * t + s) * (phi_ts - 1);
        return d / k * Math.exp(Math.pow(d / theta, phi_gamma));
    }

    /**
     * 在 {@code n / k >> r} 的近似前提下计算目标函数的一阶导数。
     *
     * @param k 聚类大小
     * @param t 任务运行时间
     * @param s 系统开销
     * @param theta 到达间隔估计参数
     * @param phi Weibull 分布参数
     * @return 近似目标函数的一阶导数
     */
    protected static double fprime(double k, double t, double s, double theta, double phi) {
        double first_part = Math.exp(Math.pow((k * t + s) / theta, phi));
        double second_part = t * phi / k * Math.pow((k * t + s) / theta, phi) - s / (k * k);
        return first_part * second_part;
    }

    /**
     * 在 {@code n / k >> r} 的近似前提下枚举候选聚类大小。
     *
     * @param t 任务运行时间
     * @param s 系统开销
     * @param theta 到达间隔估计参数
     * @param phi_gamma 分布形状参数
     * @param phi_ts 时间缩放参数
     * @return 枚举范围内目标值最小的聚类大小
     */
    public static int estimateK(double t, double s, double theta, double phi_gamma, double phi_ts) {
        int optimalK = 0;
        double minM = Double.MAX_VALUE;
        for (int k = 1; k < 200; k++) {
            double M = f(k, t, s, theta, phi_gamma, phi_ts);
            if (M < minM) {
                minM = M;
                optimalK = k;
            }
            // 调试时可输出每个候选 k 的目标函数值。
        }
        return optimalK;
    }
}
