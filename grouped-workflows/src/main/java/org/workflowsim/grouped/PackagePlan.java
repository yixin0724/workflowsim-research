package org.workflowsim.grouped;

import com.google.gson.*;
import java.util.*;

/** Physical partition, fixed node assignment, and a topological priority order. */
public final class PackagePlan {
    final int[][] members;
    final int[] host, order;
    public PackagePlan(int[][] members,int[] host,int[] order) {
        this.members=new int[members.length][];
        for(int i=0;i<members.length;i++) this.members[i]=members[i].clone();
        this.host=host.clone(); this.order=order.clone();
    }
    public static PackagePlan fromJson(Scenario s,JsonObject input) {
        JsonArray gs=Scenario.array(input,"groups"); Map<String,Integer> ids=new LinkedHashMap<>();
        int[][] tasks=new int[gs.size()][]; int[] hosts=new int[gs.size()];
        for(int i=0;i<gs.size();i++) {
            JsonObject g=gs.get(i).getAsJsonObject(); String id=Scenario.string(g,"id");
            Scenario.require(ids.put(id,i)==null,"duplicate group id "+id);
            tasks[i]=Scenario.references(Scenario.array(g,"tasks"),s.taskIndex,"group task");
            Integer h=s.hostIndex.get(Scenario.string(g,"host")); Scenario.require(h!=null,"unknown group host"); hosts[i]=h;
        }
        return new PackagePlan(tasks,hosts,Scenario.references(Scenario.array(input,"priority"),ids,"priority group"));
    }
    public JsonObject toJson(Scenario s) {
        JsonObject result=new JsonObject(); JsonArray gs=new JsonArray(), priority=new JsonArray();
        for(int i=0;i<members.length;i++) {
            JsonObject g=new JsonObject(); g.addProperty("id","g"+i); g.addProperty("host",s.hosts[host[i]].id);
            JsonArray ts=new JsonArray(); for(int t:members[i]) ts.add(s.tasks[t].id); g.add("tasks",ts); gs.add(g);
        }
        for(int g:order) priority.add("g"+g);
        result.add("groups",gs); result.add("priority",priority); return result;
    }
    String canonical() {
        String[] groups=new String[members.length];
        for(int i=0;i<members.length;i++) { int[] ts=members[i].clone(); Arrays.sort(ts); groups[i]=Arrays.toString(ts)+"@"+host[i]; }
        StringBuilder key=new StringBuilder(); for(int g:order) key.append(groups[g]).append(';'); return key.toString();
    }
}
