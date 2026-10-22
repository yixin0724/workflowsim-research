package org.workflowsim.data;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;

import org.junit.jupiter.api.Test;

/**
 * M5 杀手（审查工程 Phase 3：变异试点存活的针对性回归）。
 *
 * <p>变异试点实证：将 {@code TransferContentionEngine} 完成判定的字节数容差从
 * {@code 1e-9*bytes} 放大到 {@code 1e-6*bytes}（放大 1000 倍）后，<b>全部 1024
 * 个既有测试无一失败</b>——完成判定边界此前是测试盲区。</p>
 *
 * <p>契约锚定（不抄实现镜像）：完成判定必须把"还剩可观字节"的流视为未完成，
 * 迟到完成会压缩 FCT 并偷走争用容量。本测试选用 10⁶ 字节流，推导边界：
 * 合同容差 = max(1e-9×bytes, 4×ulp(bytes)) = 10⁻³ 字节；推到<b>剩余 0.5 字节</b>
 * 时点（介于 10⁻³ 与变异容差 1.0 之间）：合法实现必须仍判定未完成并在
 * 下次推进结算；变异实现会提前判完成——两条路在时序上可观测分离。</p>
 */
class TransferCompletionToleranceBoundaryTest {

    @Test
    void halfByteRemainingIsNotCompleteButOneFullMillibyteToleranceIs() {
        TransferContentionEngine engine = new TransferContentionEngine();
        engine.setEndpointCapacity("VM:0", 1000.0);
        double bytes = 1_000_000.0;
        double rate = 1000.0;

        TransferContentionEngine.AdvanceResult admit = engine.addTransfer(1L, bytes, Arrays.asList("VM:0"), rate, 0.0);
        assertTrue(admit.getCompletedTransferIds().isEmpty(), "满容量单流不得在准入时刻完成");

        // 推进到剩余恰为 0.5 字节（> 合同容差 10⁻³，< 变异容差 1.0）。
        double partialSeconds = (bytes - 0.5) / rate;
        TransferContentionEngine.AdvanceResult partial = engine.advance(partialSeconds);
        assertTrue(
                partial.getCompletedTransferIds().isEmpty(),
                "剩余 0.5 字节远超合同容差 max(1e-9*bytes, 4*ulp) = 1e-3 字节，" + "不得提前判定完成（变异 M5 会在此判完成）");
        Double next = partial.getNextCompletionTime();
        assertNotNull(next, "未完成的流必须报告下一次完成时刻");
        assertTrue(next > partialSeconds, "下一次完成时刻必须晚于当前推进点");

        TransferContentionEngine.AdvanceResult settled = engine.advance(next);
        assertFalse(settled.getCompletedTransferIds().isEmpty(), "到达真实完成时刻后必须结算");
    }

    @Test
    void completionJudgeNeverTreatsAnUntouchedPositiveFlowAsComplete() {
        TransferContentionEngine engine = new TransferContentionEngine();
        engine.setEndpointCapacity("VM:0", 1.0);
        // 小负载流：bytes*0.5 分支主导容差（min(0.05, max(1e-4,4ulp)) = 1e-4）。
        double bytes = 10.0;
        TransferContentionEngine.AdvanceResult admit = engine.addTransfer(2L, bytes, Arrays.asList("VM:0"), 1.0, 0.0);
        assertTrue(admit.getCompletedTransferIds().isEmpty(), "未服务过任何时间的正负载流不得被判完成（容差是相对舍入防护，不是免费额度）");
        Double next = admit.getNextCompletionTime();
        assertNotNull(next);
        assertTrue(next > 0.0, "10 字节 / 1 B/s 的完成时刻必须是正时刻");
    }
}
