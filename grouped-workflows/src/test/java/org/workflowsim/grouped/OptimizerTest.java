package org.workflowsim.grouped;

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;

import org.junit.jupiter.api.Test;

import com.google.gson.*;

class OptimizerTest {
    static JsonObject request(String algorithm, int evaluations, long seed) {
        JsonObject j = new JsonObject();
        j.add("scenario", GroupedModelTest.path(1));
        JsonObject c = new JsonObject();
        c.addProperty("algorithm", algorithm);
        c.addProperty("max_evaluations", evaluations);
        c.addProperty("max_seconds", 300);
        c.addProperty("seed", seed);
        c.addProperty("neighborhood_evaluations", 48);
        j.add("config", c);
        return j;
    }

    @Test
    void tinyCompleteReferenceChecksEveryAlgorithmAndBudget() {
        JsonObject exact = ExactSolver.solve(request("JG", 200, 0));
        assertTrue(exact.get("complete").getAsBoolean());
        double optimum =
                exact.getAsJsonObject("evaluation").get("makespan_seconds").getAsDouble();
        assertEquals(19, optimum, 1e-10);
        for (String method : new String[] {
            "SINGLE",
            "PATH",
            "STAGED-HGA",
            "ALT",
            "JG",
            "MS-DESCENT",
            "JOINT-ILS",
            "UNIFORM-ILS",
            "PLACE-ILS",
            "NO-RELATED-ILS",
            "POOLED-HGA",
            "JOINT-HGA",
            "UNIFORM-JOINT-HGA"
        }) {
            JsonObject result = SearchEngine.solve(request(method, 200, 9));
            double value =
                    result.getAsJsonObject("evaluation").get("makespan_seconds").getAsDouble();
            assertTrue(value >= optimum - 1e-8, method);
            JsonObject search = result.getAsJsonObject("search");
            assertTrue(search.get("evaluations").getAsInt() <= 200, method);
            assertTrue(value <= search.get("initial_makespan_seconds").getAsDouble() + 1e-8, method);
            assertEquals(
                    1,
                    search.getAsJsonObject("replays_by_operator")
                            .get("final_replay")
                            .getAsInt());
            assertEquals(0, search.get("invalid_evaluations").getAsInt());
        }
    }

    @Test
    void sameSeedAndEvaluationBudgetReproducePlanAndReplayCount() {
        for (String method : new String[] {"STAGED-HGA", "ALT", "JOINT-ILS", "UNIFORM-ILS", "JOINT-HGA"}) {
            JsonObject a = SearchEngine.solve(request(method, 120, 10)),
                    b = SearchEngine.solve(request(method, 120, 10));
            assertEquals(a.get("plan"), b.get("plan"), method);
            assertEquals(a.get("evaluation"), b.get("evaluation"), method);
            assertEquals(
                    a.getAsJsonObject("search").get("evaluations"),
                    b.getAsJsonObject("search").get("evaluations"),
                    method);
        }
    }

    @Test
    void aTwoReplayBudgetStillChargesInitializationAndFinalTrace() {
        JsonObject result = SearchEngine.solve(request("JOINT-ILS", 2, 0));
        assertEquals(2, result.getAsJsonObject("search").get("evaluations").getAsInt());
        assertEquals(
                2, result.getAsJsonObject("search").get("evaluation_requests").getAsInt());
    }

    @Test
    void cacheDoesNotAlterSearchOrGroupIdentities() {
        JsonObject cached = request("JOINT-ILS", 180, 7), uncached = request("JOINT-ILS", 180, 7);
        uncached.getAsJsonObject("config").addProperty("cache_entries", 0);
        for (JsonObject input : new JsonObject[] {cached, uncached}) {
            JsonObject result = SearchEngine.solve(input);
            Scenario s = new Scenario(input.getAsJsonObject("scenario"));
            PackagePlan p = PackagePlan.fromJson(s, result.getAsJsonObject("plan"));
            assertEquals(
                    result.getAsJsonObject("evaluation").get("makespan_seconds").getAsDouble(),
                    GroupedSimulator.evaluate(s, p, true).makespan,
                    0);
        }
    }
}
