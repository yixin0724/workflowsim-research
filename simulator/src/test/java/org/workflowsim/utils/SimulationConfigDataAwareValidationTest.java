package org.workflowsim.utils;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Collections;
import org.junit.jupiter.api.Test;
import org.workflowsim.utils.Parameters.SchedulingAlgorithm;

/**
 * 回归测试（PLAT-7）：DATA 调度算法与 SHARED 文件系统模式的组合必须在配置层被拒绝。
 *
 * <p>背景：DATA 算法的局部性判定按 VM ID 匹配副本站点，而 SHARED 模式的副本按
 * 数据中心名注册，两者永不匹配——DATA 会静默退化为 first-fit-idle，产生误导性
 * 实验结果。修复后 {@link SimulationConfig.Builder#build()} 直接拒绝该组合。</p>
 */
class SimulationConfigDataAwareValidationTest {

    @Test
    void rejectsDataAwareWithSharedFileSystem() {
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> SimulationConfig.builder(Collections.singletonList("workflow.dax"), 2)
                        .schedulingAlgorithm(SchedulingAlgorithm.DATA)
                        .fileSystem(ReplicaCatalog.FileSystem.SHARED)
                        .build());
        assertTrue(exception.getMessage().contains("SchedulingAlgorithm.DATA"),
                "Rejection message must name the DATA scheduling algorithm: " + exception.getMessage());
        assertTrue(exception.getMessage().contains("LOCAL"),
                "Rejection message must state the LOCAL requirement: " + exception.getMessage());
    }

    @Test
    void acceptsDataAwareWithLocalFileSystem() {
        assertDoesNotThrow(() -> SimulationConfig.builder(Collections.singletonList("workflow.dax"), 2)
                .schedulingAlgorithm(SchedulingAlgorithm.DATA)
                .fileSystem(ReplicaCatalog.FileSystem.LOCAL)
                .build());
    }

    @Test
    void acceptsSharedFileSystemWithOtherAlgorithms() {
        // SHARED 模式对其他在线算法不受影响。
        assertDoesNotThrow(() -> SimulationConfig.builder(Collections.singletonList("workflow.dax"), 2)
                .schedulingAlgorithm(SchedulingAlgorithm.FCFS)
                .fileSystem(ReplicaCatalog.FileSystem.SHARED)
                .build());
    }
}
