package org.workflowsim.experiment;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 一次 WorkflowSim 运行产生的不可变、有序观测事件。 */
public final class SimulationEvent {

    private final long sequence;
    private final double simulationTime;
    private final SimulationEventType type;
    private final Integer jobId;
    private final Integer vmId;
    private final Integer classType;
    private final List<Integer> taskIds;
    private final Map<String, Object> attributes;

    SimulationEvent(long sequence, double simulationTime, SimulationEventType type,
            Integer jobId, Integer vmId, Integer classType, List<Integer> taskIds,
            Map<String, ?> attributes) {
        this.sequence = sequence;
        this.simulationTime = simulationTime;
        this.type = type;
        this.jobId = jobId;
        this.vmId = vmId;
        this.classType = classType;
        this.taskIds = Collections.unmodifiableList(new ArrayList<Integer>(taskIds));
        this.attributes = Collections.unmodifiableMap(new LinkedHashMap<String, Object>(attributes));
    }

    public long getSequence() { return sequence; }
    public double getSimulationTime() { return simulationTime; }
    public SimulationEventType getType() { return type; }
    public Integer getJobId() { return jobId; }
    public Integer getVmId() { return vmId; }
    public Integer getClassType() { return classType; }
    public List<Integer> getTaskIds() { return taskIds; }
    public Map<String, Object> getAttributes() { return attributes; }
}
