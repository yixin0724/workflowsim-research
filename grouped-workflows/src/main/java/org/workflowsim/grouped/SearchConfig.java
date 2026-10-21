package org.workflowsim.grouped;

import com.google.gson.*;

/** Explicit bounded-search configuration; identical physical permissions for all methods. */
public final class SearchConfig {
    final int maxEvaluations, neighborhoodEvaluations, boundaryLimit, windowGroups, beamWidth;
    final int prioritySamples, partitionSamples, relatedLimit, restartAfter, population, maxSegment;
    final int cacheEntries;
    final double maxSeconds, mutation, packageMutation, refinementProbability;
    final int refinementEvaluations;
    final long seed;
    final String algorithm;

    public SearchConfig(JsonObject j) {
        algorithm = j.has("algorithm") ? j.get("algorithm").getAsString() : "JOINT-ILS";
        seed = j.has("seed") ? j.get("seed").getAsLong() : 0;
        maxEvaluations = integer(j, "max_evaluations", 1000, 2, 10000000);
        maxSeconds = number(j, "max_seconds", 30, 0.001, 86400);
        neighborhoodEvaluations = integer(j, "neighborhood_evaluations", 192, 4, 100000);
        boundaryLimit = integer(j, "boundary_limit", 3, 1, 10);
        windowGroups = integer(j, "window_groups", 3, 1, boundaryLimit + 1);
        beamWidth = integer(j, "beam_width", 2, 1, 32);
        prioritySamples = integer(j, "priority_samples", 3, 1, 100000);
        partitionSamples = integer(j, "partition_samples", 4, 1, 1 << boundaryLimit);
        relatedLimit = integer(j, "related_limit", 1, 0, 8);
        restartAfter = integer(j, "restart_after", 4, 1, 1000);
        population = integer(j, "population", 12, 2, 10000);
        maxSegment = integer(j, "dp_max_segment", 64, 1, 100000);
        cacheEntries = integer(j, "cache_entries", 32, 0, 10000);
        mutation = number(j, "mutation_probability", 0.1, 0, 1);
        packageMutation = number(j, "package_mutation_probability", 0.25, 0, 1);
        refinementProbability = number(j, "refinement_probability", 0, 0, 1);
        refinementEvaluations = integer(j, "refinement_evaluations", 24, 1, 10000);
    }

    private static int integer(JsonObject j, String k, int d, int lo, int hi) {
        double x = j.has(k) ? j.get(k).getAsDouble() : d;
        Scenario.require(Double.isFinite(x) && x == Math.rint(x) && x >= lo && x <= hi, "invalid " + k);
        return (int) x;
    }

    private static double number(JsonObject j, String k, double d, double lo, double hi) {
        double x = j.has(k) ? j.get(k).getAsDouble() : d;
        Scenario.require(Double.isFinite(x) && x >= lo && x <= hi, "invalid " + k);
        return x;
    }

    JsonObject json() {
        JsonObject j = new JsonObject();
        j.addProperty("algorithm", algorithm);
        j.addProperty("seed", seed);
        j.addProperty("max_evaluations", maxEvaluations);
        j.addProperty("max_seconds", maxSeconds);
        j.addProperty("neighborhood_evaluations", neighborhoodEvaluations);
        j.addProperty("boundary_limit", boundaryLimit);
        j.addProperty("window_groups", windowGroups);
        j.addProperty("beam_width", beamWidth);
        j.addProperty("priority_samples", prioritySamples);
        j.addProperty("partition_samples", partitionSamples);
        j.addProperty("related_limit", relatedLimit);
        j.addProperty("restart_after", restartAfter);
        j.addProperty("population", population);
        j.addProperty("dp_max_segment", maxSegment);
        j.addProperty("cache_entries", cacheEntries);
        j.addProperty("mutation_probability", mutation);
        j.addProperty("package_mutation_probability", packageMutation);
        j.addProperty("refinement_probability", refinementProbability);
        j.addProperty("refinement_evaluations", refinementEvaluations);
        return j;
    }
}
