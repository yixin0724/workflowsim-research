package org.workflowsim.grouped;

import com.google.gson.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** File-only research interface. Input command fields are never executed. */
public final class GroupedWorkflowCli {
    private static final Gson GSON =
            new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    private GroupedWorkflowCli() {}

    public static void main(String[] args) throws Exception {
        if (args.length == 1 && args[0].equals("server")) {
            Gson compact = new GsonBuilder().disableHtmlEscaping().create();
            BufferedReader reader = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
            String line;
            while ((line = reader.readLine()) != null) {
                JsonObject output;
                try {
                    JsonObject request = JsonParser.parseString(line).getAsJsonObject();
                    String command =
                            request.has("command") ? request.get("command").getAsString() : "optimize";
                    output = run(command, request);
                } catch (Exception error) {
                    output = new JsonObject();
                    output.addProperty("error", error.toString());
                    StringWriter trace = new StringWriter();
                    error.printStackTrace(new PrintWriter(trace));
                    output.addProperty("stack", trace.toString());
                }
                System.out.println(compact.toJson(output));
                System.out.flush();
            }
            return;
        }
        if (args.length != 3
                || !Arrays.asList("evaluate", "construct", "optimize", "enumerate")
                        .contains(args[0]))
            throw new IllegalArgumentException(
                    "Usage: evaluate|construct|optimize|enumerate <request.json> <result.json>");
        JsonElement input =
                JsonParser.parseString(new String(Files.readAllBytes(Paths.get(args[1])), StandardCharsets.UTF_8));
        JsonElement output;
        if (input.isJsonArray()) {
            JsonArray results = new JsonArray();
            for (JsonElement e : input.getAsJsonArray()) results.add(run(args[0], e.getAsJsonObject()));
            output = results;
        } else output = run(args[0], input.getAsJsonObject());
        Path path = Paths.get(args[2]);
        if (path.toAbsolutePath().getParent() != null)
            Files.createDirectories(path.toAbsolutePath().getParent());
        Files.write(path, (GSON.toJson(output) + "\n").getBytes(StandardCharsets.UTF_8));
    }

    static JsonObject run(String command, JsonObject request) {
        if (command.equals("optimize")) return SearchEngine.solve(request);
        if (command.equals("enumerate")) return ExactSolver.solve(request);
        if (command.equals("evaluate")) return evaluate(request);
        Scenario s = new Scenario(request.getAsJsonObject("scenario"));
        String mode = request.has("partition") ? request.get("partition").getAsString() : "singleton";
        int[][] partition;
        if (mode.equals("singleton")) partition = InitialPlans.singleton(s);
        else if (mode.equals("maximal")) partition = InitialPlans.maximalChains(s);
        else if (mode.equals("dp")) partition = InitialPlans.pathDp(s, 64);
        else throw new IllegalArgumentException("Unknown partition constructor: " + mode);
        String priority =
                request.has("priority_mode") ? request.get("priority_mode").getAsString() : "rank";
        PackagePlan plan = InitialPlans.construct(s, partition, priority, new Random(0));
        JsonObject result = new JsonObject();
        result.add("plan", plan.toJson(s));
        result.add("evaluation", GroupedSimulator.evaluate(s, plan, true).toJson(s, plan, true));
        return result;
    }

    static JsonObject evaluate(JsonObject request) {
        Scenario s = new Scenario(request.getAsJsonObject("scenario"));
        PackagePlan p = PackagePlan.fromJson(s, request.getAsJsonObject("plan"));
        JsonObject result = new JsonObject();
        result.add("plan", p.toJson(s));
        result.add("evaluation", GroupedSimulator.evaluate(s, p, true).toJson(s, p, true));
        return result;
    }
}
