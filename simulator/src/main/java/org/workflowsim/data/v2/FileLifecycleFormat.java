package org.workflowsim.data.v2;

import com.google.gson.JsonArray;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import java.util.Map;
import org.workflowsim.network.FatTreeTopology;

/** Stable explicit write-side shapes; read-side validation must not Gson-populate private fields. */
final class FileLifecycleFormat {
    private FileLifecycleFormat(){ }
    static JsonObject file(DataflowFilePlan.FileId id){JsonObject o=new JsonObject();o.addProperty("workflowInputIndex",id.getWorkflowInputIndex());o.addProperty("name",id.getName());return o;}
    static JsonObject location(DataLocation location){
        JsonObject o=new JsonObject();o.addProperty("kind",location.getKind().name());o.add("vmId",JsonNull.INSTANCE);o.add("sourceId",JsonNull.INSTANCE);
        if(location.getKind()==DataLocation.Kind.VM)o.addProperty("vmId",location.getVmId());else o.addProperty("sourceId",location.getSourceId());return o;
    }
    static JsonObject replica(DataReplicaState.Replica replica){
        JsonObject o=new JsonObject();o.add("fileId",file(replica.getFile().getId()));o.add("location",location(replica.getLocation()));o.addProperty("visibleAt",replica.getVisibleAt());o.addProperty("acquisition",replica.getAcquisition().name());
        JsonObject origin=new JsonObject();origin.addProperty("producerTaskId",replica.getOrigin().getProducerTaskId());origin.addProperty("jobAttemptId",replica.getOrigin().getJobAttemptId());origin.add("location",location(replica.getOrigin().getLocation()));origin.addProperty("observedAt",replica.getOrigin().getObservedAt());o.add("origin",origin);
        o.add("copiedFrom",replica.getCopiedFrom()==null?JsonNull.INSTANCE:location(replica.getCopiedFrom()));o.addProperty("copyOrdinal",replica.getCopyOrdinal());return o;
    }
    static JsonObject plan(DataflowFilePlan plan){
        JsonObject o=new JsonObject();o.addProperty("contractVersion",plan.getContractVersion());JsonArray tasks=new JsonArray(),files=new JsonArray();
        for(int id:plan.getTaskIds()){
            JsonObject task=new JsonObject();task.addProperty("taskId",id);task.addProperty("workflowInputIndex",plan.getTaskWorkflowIndices().get(id));JsonArray parents=new JsonArray(),inputs=new JsonArray(),outputs=new JsonArray();
            for(int parent:plan.getParentTaskIds(id))parents.add(parent);
            for(Map.Entry<DataflowFilePlan.FileId,Long> input:plan.getInputReferences(id).entrySet()){JsonObject item=new JsonObject();item.add("fileId",file(input.getKey()));item.addProperty("referenceCount",input.getValue());inputs.add(item);}
            for(DataflowFilePlan.FileId output:plan.getOutputFiles(id))outputs.add(file(output));task.add("parents",parents);task.add("inputs",inputs);task.add("outputs",outputs);tasks.add(task);
        }
        for(DataflowFilePlan.FileDefinition file:plan.getFiles()){JsonObject row=new JsonObject();row.add("fileId",file(file.getId()));row.addProperty("bytes",file.getBytes());row.addProperty("producerTaskId",file.getProducerTaskId());files.add(row);}
        o.add("tasks",tasks);o.add("files",files);return o;
    }
    static JsonObject fabric(DataTransferFabric fabric){
        JsonObject o=new JsonObject();JsonArray locations=new JsonArray(),resources=new JsonArray(),hosts=new JsonArray();
        for(DataLocation location:fabric.getLocations())locations.add(location(location));
        for(Map.Entry<String,Double> entry:fabric.getResourceCapacities().entrySet()){JsonObject row=new JsonObject();row.addProperty("key",entry.getKey());row.addProperty("capacityBytesPerSecond",entry.getValue());resources.add(row);}
        for(Map.Entry<Integer,Integer> entry:fabric.getActualVmHosts().entrySet()){JsonObject row=new JsonObject();row.addProperty("vmId",entry.getKey());row.addProperty("hostId",entry.getValue());hosts.add(row);}
        o.add("locations",locations);o.add("resources",resources);o.add("vmHostAssignments",hosts);o.add("topology",JsonNull.INSTANCE);
        FatTreeTopology topology=fabric.getTopology();if(topology!=null){JsonObject t=new JsonObject();t.addProperty("kind","FAT_TREE");t.addProperty("k",topology.getK());t.addProperty("coreSwitchCount",topology.getCoreSwitchCount());t.addProperty("linkBandwidthBytesPerSecond",topology.getLinkBandwidthBytesPerSecond());JsonArray placements=new JsonArray();
            for(Map.Entry<Integer,int[]> entry:topology.getHostPlacements().entrySet()){JsonObject row=new JsonObject();row.addProperty("hostId",entry.getKey());row.addProperty("pod",entry.getValue()[0]);row.addProperty("edge",entry.getValue()[1]);placements.add(row);}t.add("hostPlacements",placements);o.add("topology",t);}
        return o;
    }
}
