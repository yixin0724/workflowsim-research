package org.workflowsim.experiment;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.workflowsim.Job;
import org.workflowsim.Task;

/**
 * 运行私有的有序事件记录器。
 *
 * <p>实例刻意不依赖 WorkflowSim 的遗留静态状态，以保证顺序会话之间相互隔离。</p>
 */
public final class SimulationEventRecorder {

    private static final SimulationEventRecorder DISABLED = new SimulationEventRecorder(false);

    private final boolean enabled;
    private final List<SimulationEvent> events;
    private long nextSequence;

    /** 创建仅服务于一次仿真运行的启用状态记录器。 */
    public SimulationEventRecorder() {
        this(true);
    }

    private SimulationEventRecorder(boolean enabled) {
        this.enabled = enabled;
        this.events = enabled ? new ArrayList<SimulationEvent>() : Collections.<SimulationEvent>emptyList();
    }

    /**
     * 返回供遗留入口使用的空操作记录器。
     *
     * @return 可安全接受记录调用但不保存事件的共享记录器
     */
    public static SimulationEventRecorder disabled() {
        return DISABLED;
    }

    public boolean isEnabled() {
        return enabled;
    }

    /**
     * 记录不关联 Job 的模拟事件。
     *
     * @param type 事件类型，不能为空
     * @param simulationTime 事件发生的有限模拟时间
     * @throws IllegalArgumentException 当事件类型为空或模拟时间非有限时抛出
     */
    public synchronized void record(SimulationEventType type, double simulationTime) {
        record(type, simulationTime, null, Collections.<String, Object>emptyMap());
    }

    /**
     * 记录可关联 Job 和附加属性的模拟事件。
     *
     * @param type 事件类型，不能为空
     * @param simulationTime 事件发生的有限模拟时间
     * @param job 可选关联 Job；为空时不会写入 Job、VM、类别或任务标识
     * @param attributes 可选附加属性，会在记录时复制
     * @throws IllegalArgumentException 当事件类型为空或模拟时间非有限时抛出
     */
    public synchronized void record(SimulationEventType type, double simulationTime,
            Job job, Map<String, ?> attributes) {
        if (!enabled) {
            return;
        }
        if (type == null || Double.isNaN(simulationTime) || Double.isInfinite(simulationTime)) {
            throw new IllegalArgumentException("Event type and finite simulation time are required");
        }
        List<Integer> taskIds = new ArrayList<Integer>();
        Integer jobId = null;
        Integer vmId = null;
        Integer classType = null;
        if (job != null) {
            jobId = job.getCloudletId();
            vmId = job.getVmId();
            classType = job.getClassType();
            for (Task task : job.getTaskList()) {
                taskIds.add(task.getCloudletId());
            }
        }
        Map<String, Object> copiedAttributes = new LinkedHashMap<String, Object>();
        if (attributes != null) {
            copiedAttributes.putAll(attributes);
        }
        events.add(new SimulationEvent(nextSequence++, simulationTime, type, jobId, vmId,
                classType, taskIds, copiedAttributes));
    }

    /**
     * 按原始事件顺序返回不可变快照。
     *
     * @return 当前已记录事件的不可变副本；禁用记录器返回空列表
     */
    public synchronized List<SimulationEvent> snapshot() {
        if (!enabled) {
            return Collections.emptyList();
        }
        return Collections.unmodifiableList(new ArrayList<SimulationEvent>(events));
    }

    /**
     * 从交替出现的键和值构造一个小型确定性事件属性映射。
     *
     * @param values 交替出现的 {@link String} 键和值；为空时返回空映射
     * @return 保持调用方给定顺序的属性映射
     * @throws IllegalArgumentException 当元素数量为奇数或键不是字符串时抛出
     */
    public static Map<String, Object> attributes(Object... values) {
        if (values == null || values.length == 0) {
            return Collections.emptyMap();
        }
        if (values.length % 2 != 0) {
            throw new IllegalArgumentException("Event attributes require key/value pairs");
        }
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        for (int index = 0; index < values.length; index += 2) {
            if (!(values[index] instanceof String)) {
                throw new IllegalArgumentException("Event attribute keys must be strings");
            }
            result.put((String) values[index], values[index + 1]);
        }
        return result;
    }
}
