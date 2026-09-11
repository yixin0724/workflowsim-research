package org.workflowsim.examples;

import org.cloudbus.cloudsim.Log;
import org.workflowsim.experiment.SimulationReport;
import org.workflowsim.experiment.SimulationRunner;
import org.workflowsim.platform.PlatformProfile;
import org.workflowsim.platform.PlatformProfiles;
import org.workflowsim.utils.Parameters.PlanningAlgorithm;
import org.workflowsim.utils.Parameters.SchedulingAlgorithm;
import org.workflowsim.utils.ReplicaCatalog;
import org.workflowsim.utils.SimulationConfig;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * PSO 论文复现实验——对照 Pandey et al. (AINA 2010) 的 PSO vs 朴素基线结论。
 *
 * <p>论文场景：异构云实例（不同 MIPS 与单价），工作流为 Pegasus 真实工作流
 * （此处 Montage_25）。论文结论（方向性）：PSO 相比 round-robin 朴素映射可显著
 * 降低执行成本（论文报告最高约 26%）。</p>
 *
 * <p><b>平台适配声明</b>：</p>
 * <ul>
 *   <li>本平台 VM 单价模型 = 论文的简化计价（price = mips/1000），非 EC2 真实定价；</li>
 *   <li>本平台"论文成本"= Σ(COMPUTE 作业执行时间 × 所在 VM 单价)，只统计计算作业
 *       （classType==2，对应 DAX 任务），不含 stage-in 作业（平台特有的输入延迟建模）；</li>
 *   <li>PSO 适应度忽略依赖边（忠实自参考实现），运行侧 makespan 以引擎执行为准；</li>
 *   <li>朴素基线用 RANDOM+STATIC（多种子均值）代替论文的 RR：本平台 STATIC_ROUND_ROBIN
 *       等 STATIC_* 族按设计拒绝含依赖的 DAG 工作流——这本身是复现轮发现的平台差异；</li>
 *   <li>强基线补充 SHARED_STORAGE_HEFT（平台受控 DAG 列表调度），用于定位 PSO 在
 *       当前模型下的相对位置。</li>
 * </ul>
 */
public class PsoReproductionExperiment {

    /** 异构 VM MIPS（单价 = mips/1000：0.5 / 0.8 / 1.0 / 1.2）。 */
    private static final List<Double> VM_MIPS = Arrays.asList(500.0, 800.0, 1000.0, 1200.0);
    private static final String WORKFLOW_PATH = "datasets/dax/montage/n25/Montage_25.dax";
    private static final int VM_COUNT = 4;

    public static void main(String[] args) throws Exception {
        Log.disable();

        PlatformProfile platform = PlatformProfiles.heterogeneousLocal(
                "pso-reproduction-platform", VM_MIPS);
        Map<Integer, Double> vmMips = new HashMap<Integer, Double>();
        for (PlatformProfile.VmSpec spec : platform.getVms()) {
            vmMips.put(Integer.valueOf(spec.getId()), Double.valueOf(spec.getMips()));
        }

        System.out.println("═══════════════════════════════════════════════════════════");
        System.out.println("  PSO 论文复现：Pandey et al., AINA 2010（异构 VM 平台）");
        System.out.println("  工作流: Montage_25  VM: " + VM_MIPS + " MIPS (price=mips/1000)");
        System.out.println("═══════════════════════════════════════════════════════════");

        List<RunResult> results = new ArrayList<RunResult>();
        // 论文算法：PSO（种子 42 与平台默认一致）
        results.add(run("PSO", PlanningAlgorithm.PSO, 42L, platform, vmMips));
        // 朴素基线（代替论文 RR）：RANDOM 多种子
        results.add(run("RANDOM(seed42)", PlanningAlgorithm.RANDOM, 42L, platform, vmMips));
        results.add(run("RANDOM(seed43)", PlanningAlgorithm.RANDOM, 43L, platform, vmMips));
        results.add(run("RANDOM(seed44)", PlanningAlgorithm.RANDOM, 44L, platform, vmMips));
        // 平台强基线：受控 HEFT
        results.add(run("SHARED_STORAGE_HEFT", PlanningAlgorithm.SHARED_STORAGE_HEFT, 42L,
                platform, vmMips));

        System.out.println();
        System.out.println(String.format("%-24s %10s %10s %10s %8s",
                "配置", "Makespan", "论文成本", "建模成本", "作业"));
        for (RunResult r : results) {
            System.out.println(String.format("%-24s %10.3f %10.3f %10.3f %5d/%d",
                    r.label, r.makespan, r.paperCost, r.modeledCost,
                    r.successful, r.failed));
        }

        // 论文方向性结论核对：PSO 成本 ≤ RANDOM 基线均值。
        double randomMeanCost = 0.0;
        double randomMeanMakespan = 0.0;
        int randomCount = 0;
        for (RunResult r : results) {
            if (r.label.startsWith("RANDOM")) {
                randomMeanCost += r.paperCost;
                randomMeanMakespan += r.makespan;
                randomCount++;
            }
        }
        randomMeanCost /= randomCount;
        randomMeanMakespan /= randomCount;
        RunResult pso = results.get(0);
        double costDeltaPercent = 100.0 * (pso.paperCost - randomMeanCost) / randomMeanCost;
        double makespanDeltaPercent = 100.0 * (pso.makespan - randomMeanMakespan)
                / randomMeanMakespan;
        System.out.println();
        System.out.println(String.format(
                "论文方向性核对：PSO 相对 RANDOM 基线均值  成本 %+.2f%%   makespan %+.2f%%",
                costDeltaPercent, makespanDeltaPercent));
        System.out.println("（论文报告 PSO 相对 RR 降低成本最高约 26%；负值 = PSO 更优）");
    }

    private static RunResult run(String label, PlanningAlgorithm planning, long seed,
            PlatformProfile platform, Map<Integer, Double> vmMips)
            throws org.workflowsim.exception.SimulationExecutionException {
        SimulationConfig config = SimulationConfig.builder(WORKFLOW_PATH, VM_COUNT)
                .schedulingAlgorithm(SchedulingAlgorithm.STATIC)
                .planningAlgorithm(planning)
                .randomSeed(seed)
                .fileSystem(ReplicaCatalog.FileSystem.SHARED)
                .build();
        SimulationReport report = new SimulationRunner().run(config, platform);

        double paperCost = 0.0;
        for (SimulationReport.JobOutcome job : report.getJobs()) {
            if (job.getClassType() == 2) { // COMPUTE only
                paperCost += job.getExecutionTime() * vmMips.get(job.getVmId()) / 1000.0;
            }
        }
        return new RunResult(label, report.getMakespan(), paperCost,
                report.getMetrics().getTotalModeledProcessingCost(),
                report.getSuccessfulJobs(), report.getFailedJobs());
    }

    private static final class RunResult {
        final String label;
        final double makespan;
        final double paperCost;
        final double modeledCost;
        final int successful;
        final int failed;

        RunResult(String label, double makespan, double paperCost, double modeledCost,
                int successful, int failed) {
            this.label = label;
            this.makespan = makespan;
            this.paperCost = paperCost;
            this.modeledCost = modeledCost;
            this.successful = successful;
            this.failed = failed;
        }
    }
}
