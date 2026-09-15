package org.workflowsim.data;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 流体链路争用引擎的语义测试：公平共享速率、完成结算、速率随并发变化。
 */
public class TransferContentionEngineTest {

    /** 无争用时传输按名义速率完成。 */
    @Test
    public void uncongestedTransferCompletesAtNominalRate() {
        TransferContentionEngine engine = new TransferContentionEngine();
        TransferContentionEngine.AdvanceResult result =
                engine.addTransfer(1L, 1000L, "SOURCE", "VM:0", 100.0, 0.0);
        assertEquals(10.0, result.getNextCompletionTime(), 1.0e-9);
        TransferContentionEngine.AdvanceResult done = engine.advance(10.0);
        assertEquals(1, done.getCompletedTransferIds().size());
        assertEquals(Long.valueOf(1L), done.getCompletedTransferIds().get(0));
        assertNull(done.getNextCompletionTime());
        assertEquals(0, engine.activeTransferCount());
    }

    /** 同一目标端点两条并发传输公平共享容量：各得一半，完成时间翻倍。 */
    @Test
    public void twoConcurrentTransfersShareDestinationEndpoint() {
        TransferContentionEngine engine = new TransferContentionEngine();
        engine.setEndpointCapacity("VM:0", 100.0);
        engine.addTransfer(1L, 1000L, "SOURCE", "VM:0", 100.0, 0.0);
        TransferContentionEngine.AdvanceResult second =
                engine.addTransfer(2L, 1000L, "SOURCE", "VM:0", 100.0, 0.0);
        // 名义 10 秒；共享后各 50 字节/秒 → 20 秒。
        assertEquals(20.0, second.getNextCompletionTime(), 1.0e-9);
        assertEquals(50.0, engine.currentRateBytesPerSecond(1L), 1.0e-9);
        assertEquals(50.0, engine.currentRateBytesPerSecond(2L), 1.0e-9);
        // 中途结算：t=5 时各传 250 字节，均未完；速率不变。
        TransferContentionEngine.AdvanceResult midway = engine.advance(5.0);
        assertTrue(midway.getCompletedTransferIds().isEmpty());
        assertEquals(20.0, midway.getNextCompletionTime(), 1.0e-9);
        // t=20 双双完成。
        TransferContentionEngine.AdvanceResult done = engine.advance(20.0);
        assertEquals(2, done.getCompletedTransferIds().size());
        assertNull(done.getNextCompletionTime());
    }

    /** 一条传输完成后，幸存传输速率恢复到全额容量。 */
    @Test
    public void survivingTransferSpeedsUpWhenCompetitorFinishes() {
        TransferContentionEngine engine = new TransferContentionEngine();
        engine.setEndpointCapacity("VM:0", 100.0);
        // 传输 1 小（500 字节），传输 2 大（1000 字节），共享起步。
        engine.addTransfer(1L, 500L, "SOURCE", "VM:0", 100.0, 0.0);
        engine.addTransfer(2L, 1000L, "SOURCE", "VM:0", 100.0, 0.0);
        // 共享 50 字节/秒：传输 1 于 t=10 完成（500/50），传输 2 剩 500 字节。
        TransferContentionEngine.AdvanceResult done = engine.advance(10.0);
        assertEquals(1, done.getCompletedTransferIds().size());
        assertEquals(Long.valueOf(1L), done.getCompletedTransferIds().get(0));
        // 传输 2 恢复 100 字节/秒：剩 500 字节 → t=15 完成。
        assertEquals(100.0, engine.currentRateBytesPerSecond(2L), 1.0e-9);
        assertEquals(15.0, done.getNextCompletionTime(), 1.0e-9);
        TransferContentionEngine.AdvanceResult finalDone = engine.advance(15.0);
        assertEquals(1, finalDone.getCompletedTransferIds().size());
        assertEquals(Long.valueOf(2L), finalDone.getCompletedTransferIds().get(0));
    }

    /** 传输同时占用源与目标端点：VM→VM 双向占用，瓶颈端点决定速率。 */
    @Test
    public void vmToVmTransferLoadsBothEndpoints() {
        TransferContentionEngine engine = new TransferContentionEngine();
        engine.setEndpointCapacity("VM:0", 200.0);
        engine.setEndpointCapacity("VM:1", 100.0);
        // VM:0→VM:1 单独传输：名义 100，目标容量 100 → 速率 100。
        engine.addTransfer(1L, 1000L, "VM:0", "VM:1", 100.0, 0.0);
        // 再传 VM:0→VM:2（VM:2 无容量上限）：占用 VM:0 源端 → VM:0 负载 2。
        TransferContentionEngine.AdvanceResult second =
                engine.addTransfer(2L, 1000L, "VM:0", "VM:2", 100.0, 0.0);
        // 传输 1：min(100, VM:0 份额 100, VM:1 份额 100) = 100。
        // 传输 2：min(100, VM:0 份额 100, ∞) = 100。
        assertEquals(100.0, engine.currentRateBytesPerSecond(1L), 1.0e-9);
        assertEquals(100.0, engine.currentRateBytesPerSecond(2L), 1.0e-9);
        assertEquals(10.0, second.getNextCompletionTime(), 1.0e-9);
    }

    /** 新传输在既有传输中途加入：既有传输减速且不产生虚假完成。 */
    @Test
    public void lateArrivalSlowsExistingTransfer() {
        TransferContentionEngine engine = new TransferContentionEngine();
        engine.setEndpointCapacity("VM:0", 100.0);
        engine.addTransfer(1L, 1000L, "SOURCE", "VM:0", 100.0, 0.0);
        // t=4 加入传输 2：传输 1 已传 400 字节剩 600。
        TransferContentionEngine.AdvanceResult second =
                engine.addTransfer(2L, 1000L, "SOURCE", "VM:0", 100.0, 4.0);
        assertTrue(second.getCompletedTransferIds().isEmpty());
        // 共享 50 字节/秒：传输 1 剩 600 → 再需 12 秒（t=16 完成）；
        // 传输 2 剩 1000 → 再需 20 秒（t=24 完成）。最早预测 16。
        assertEquals(16.0, second.getNextCompletionTime(), 1.0e-9);
        TransferContentionEngine.AdvanceResult firstDone = engine.advance(16.0);
        assertEquals(1, firstDone.getCompletedTransferIds().size());
        assertEquals(Long.valueOf(1L), firstDone.getCompletedTransferIds().get(0));
        // 传输 2：t=4-16 以 50 传了 600 字节剩 400；恢复 100 后 t=20 完成。
        assertEquals(100.0, engine.currentRateBytesPerSecond(2L), 1.0e-9);
        assertEquals(20.0, firstDone.getNextCompletionTime(), 1.0e-9);
        TransferContentionEngine.AdvanceResult done = engine.advance(20.0);
        assertEquals(1, done.getCompletedTransferIds().size());
        assertEquals(Long.valueOf(2L), done.getCompletedTransferIds().get(0));
    }

    /** 三条传输同端点：三等分容量，速率与完成时间符合手算值。 */
    @Test
    public void threeConcurrentTransfersSplitCapacityEqually() {
        TransferContentionEngine engine = new TransferContentionEngine();
        engine.setEndpointCapacity("VM:0", 90.0);
        engine.addTransfer(1L, 900L, "SOURCE", "VM:0", 90.0, 0.0);
        engine.addTransfer(2L, 900L, "SOURCE", "VM:0", 90.0, 0.0);
        TransferContentionEngine.AdvanceResult third =
                engine.addTransfer(3L, 900L, "SOURCE", "VM:0", 90.0, 0.0);
        // 各 30 字节/秒 → 30 秒完成。
        assertEquals(30.0, third.getNextCompletionTime(), 1.0e-9);
        assertEquals(30.0, engine.currentRateBytesPerSecond(1L), 1.0e-9);
        TransferContentionEngine.AdvanceResult done = engine.advance(30.0);
        assertEquals(3, done.getCompletedTransferIds().size());
    }

    /** 名义速率低于端点份额时按名义速率传输（容量不构成瓶颈）。 */
    @Test
    public void nominalRateCapsTransferBelowEndpointShare() {
        TransferContentionEngine engine = new TransferContentionEngine();
        engine.setEndpointCapacity("VM:0", 1000.0);
        engine.addTransfer(1L, 500L, "SOURCE", "VM:0", 50.0, 0.0);
        assertEquals(50.0, engine.currentRateBytesPerSecond(1L), 1.0e-9);
    }

    /** 资源集重载：两条传输共享一条链路键时各得该链路容量的半速。 */
    @Test
    public void resourceSetTransfersShareSingleLink() {
        TransferContentionEngine engine = new TransferContentionEngine();
        engine.setEndpointCapacity("LINK:CORE:0", 100.0);
        engine.setEndpointCapacity("VM:0", 1000.0);
        engine.setEndpointCapacity("VM:1", 1000.0);
        engine.addTransfer(1L, 1000L,
                java.util.Arrays.asList("VM:0", "VM:1", "LINK:CORE:0"), 100.0, 0.0);
        TransferContentionEngine.AdvanceResult second = engine.addTransfer(2L, 1000L,
                java.util.Arrays.asList("VM:2", "VM:3", "LINK:CORE:0"), 100.0, 0.0);
        // 链路 100/2 = 50 字节/秒（端点份额 1000 不构成瓶颈）→ 各 20 秒。
        assertEquals(50.0, engine.currentRateBytesPerSecond(1L), 1.0e-9);
        assertEquals(50.0, engine.currentRateBytesPerSecond(2L), 1.0e-9);
        assertEquals(20.0, second.getNextCompletionTime(), 1.0e-9);
    }

    /** 资源集速率取全部占用资源份额最小值：端点与链路同时约束。 */
    @Test
    public void resourceSetRateIsMinAcrossEndpointsAndLinks() {
        TransferContentionEngine engine = new TransferContentionEngine();
        engine.setEndpointCapacity("VM:1", 60.0);
        engine.setEndpointCapacity("LINK:CORE:0", 100.0);
        engine.addTransfer(1L, 600L,
                java.util.Arrays.asList("VM:0", "VM:1", "LINK:CORE:0"), 100.0, 0.0);
        // min(名义 100, VM:0 ∞, VM:1 60, 链路 100) = 60 → 10 秒。
        assertEquals(60.0, engine.currentRateBytesPerSecond(1L), 1.0e-9);
        assertEquals(10.0, engine.advance(0.0).getNextCompletionTime(), 1.0e-9);
    }

    /** 资源集不相交的两条传输互不影响（无阻塞语义）。 */
    @Test
    public void disjointResourceSetsDoNotInterfere() {
        TransferContentionEngine engine = new TransferContentionEngine();
        engine.setEndpointCapacity("LINK:A", 100.0);
        engine.setEndpointCapacity("LINK:B", 100.0);
        engine.addTransfer(1L, 1000L,
                java.util.Collections.singletonList("LINK:A"), 100.0, 0.0);
        TransferContentionEngine.AdvanceResult second = engine.addTransfer(2L, 1000L,
                java.util.Collections.singletonList("LINK:B"), 100.0, 0.0);
        assertEquals(100.0, engine.currentRateBytesPerSecond(1L), 1.0e-9);
        assertEquals(100.0, engine.currentRateBytesPerSecond(2L), 1.0e-9);
        assertEquals(10.0, second.getNextCompletionTime(), 1.0e-9);
    }

    /** 空资源集 + 未注册资源键：无争用约束，按名义速率传输。 */
    @Test
    public void emptyOrUnregisteredResourcesLeaveNominalRate() {
        TransferContentionEngine engine = new TransferContentionEngine();
        engine.addTransfer(1L, 1000L, java.util.Collections.<String>emptyList(), 100.0, 0.0);
        engine.addTransfer(2L, 1000L,
                java.util.Collections.singletonList("LINK:UNREGISTERED"), 100.0, 0.0);
        assertEquals(100.0, engine.currentRateBytesPerSecond(1L), 1.0e-9);
        assertEquals(100.0, engine.currentRateBytesPerSecond(2L), 1.0e-9);
        assertThrows(IllegalArgumentException.class, () ->
                engine.addTransfer(3L, 1000L, (java.util.List<String>) null, 100.0, 0.0));
    }

    /** 参数校验：重复 ID、非正字节、时间倒流、非正速率、非正容量。 */
    @Test
    public void validationRejectsInvalidArguments() {
        TransferContentionEngine engine = new TransferContentionEngine();
        engine.setEndpointCapacity("VM:0", 100.0);
        engine.addTransfer(1L, 100L, "SOURCE", "VM:0", 100.0, 0.0);
        assertThrows(IllegalArgumentException.class, () ->
                engine.addTransfer(1L, 100L, "SOURCE", "VM:0", 100.0, 0.0));
        assertThrows(IllegalArgumentException.class, () ->
                engine.addTransfer(2L, 0L, "SOURCE", "VM:0", 100.0, 0.0));
        assertThrows(IllegalArgumentException.class, () ->
                engine.addTransfer(2L, 100L, "SOURCE", "VM:0", -1.0, 0.0));
        assertThrows(IllegalArgumentException.class, () -> engine.advance(-1.0));
        assertThrows(IllegalArgumentException.class, () ->
                engine.setEndpointCapacity("VM:1", 0.0));
        assertThrows(IllegalArgumentException.class, () ->
                engine.currentRateBytesPerSecond(999L));
    }
}
