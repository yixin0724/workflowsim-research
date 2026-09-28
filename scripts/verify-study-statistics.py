#!/usr/bin/env python3
"""Recompute registered study matrices/statistics independently, including per-DAG effects."""
from __future__ import annotations

import argparse
import collections
from decimal import Decimal
from fractions import Fraction
from itertools import combinations
import math
from pathlib import Path
import sys

from _study_audit import (Audit, CheckError, EXECUTION_SEMANTICS, MIPS_PATTERNS, NETWORK_BANDWIDTH,
                          RUN_METRICS, contained_file, digest, exact_equal, integer, mean, median,
                          needed, number, percentile, protect_output, read_json, registered_plan,
                          run_key, validate_registered_plan, write_report)


def exact_sign_test(wins, losses):
    n = wins + losses
    if not n:
        return None
    return min(Fraction(1), 2 * sum((Fraction(math.comb(n, k), 2 ** n)
                                   for k in range(min(wins, losses) + 1)), Fraction(0)))


def holm(family):
    previous = Fraction(0)
    for rank, row in enumerate(sorted((r for r in family if r["pExact"] is not None), key=lambda r: r["pExact"])):
        previous = max(previous, min(Fraction(1), (len(family) - rank) * row["pExact"]))
        row["holmExact"] = previous
        row["significantAfterHolm"] = previous < Fraction(1, 20)


def stratum(row):
    return (needed(row, "population"), integer(needed(row, "vmCount"), "VM count", True),
            needed(row, "network"), row.get("heterogeneity", "HOMOGENEOUS"))


def aggregate_key(row):
    return (needed(row, "workflowId"),) + stratum(row) + (needed(row, "planner"),)


def comparison_key(row):
    return stratum(row) + (needed(row, "candidate"),)


def require_exact_fields(actual, expected, audit, location):
    for key, value in expected.items():
        audit.ensure(key in actual and exact_equal(actual.get(key), value), "fixed-condition", location + "/" + key,
                     actual.get(key), value)


def check_run_binding(index_path, plan, row, historical, protocol_sha, audit):
    path = contained_file(index_path.parent, needed(row, "manifest"))
    doc = read_json(path)
    location = str(path)
    audit.ensure(needed(doc, "schema") == "workflowsim-experiment-manifest-v4", "manifest-schema", location)
    config, platform = needed(doc, "configuration"), needed(doc, "platform")
    count = integer(row["vmCount"], "VM count", True)
    network, heterogeneity = row["network"], row.get("heterogeneity", "HOMOGENEOUS")
    kind = "PRE_EXECUTION_TRANSFER_DELAY_WITH_CONTENTION_V1" if network == "endpoint" else "PRE_EXECUTION_TRANSFER_DELAY_WITH_FAT_TREE_CONTENTION_V1"
    fixed = {"vmCount": count, "rootSeed": row["seed"], "planningAlgorithm": row["planner"], "schedulingAlgorithm": "STATIC",
             "fileSystem": "LOCAL", "runtimeScale": 1, "runtimeReferenceMips": 1000, "cloudSimMinEventIntervalSeconds": 0.1,
             "deadline": 0, "taskCostMatrix": None, "reduceMethod": None, "costModel": "DATACENTER", "workflowArrivalSeconds": [0],
             "clustering": {"method": "NONE", "clustersNum": 0, "clustersSize": 0, "code": None},
             "overheadModel": {"workflowEngineDelayInterval": 0, "bandwidth": 0, "workflowEngineDelays": {},
                               "queueDelays": {}, "postDelays": {}, "clusteringDelays": {}}}
    require_exact_fields(config, fixed, audit, location + ":configuration")
    if historical:
        audit.ensure("executionSemantics" not in config, "historical-model-not-retagged", location)
    else:
        require_exact_fields(config, {"executionSemantics": EXECUTION_SEMANTICS}, audit, location)
    require_exact_fields(needed(config, "dataMovementModel"), {"kind": kind, "accessLinkBandwidthMbPerSecond": 0,
                         "accessLinkLatencySeconds": 0, "sourceEndpointBandwidthMbPerSecond": 0,
                         "transferStartSemantics": "ALL_GROUPS_START_AT_JOB_READY;NO_RETROACTIVE_PARENT_PROGRESS",
                         "bandwidthUnit": "DECIMAL_MB_PER_SECOND"}, audit, location + ":movement")
    require_exact_fields(needed(config, "failureModel"), {"generatorMode": "FAILURE_NONE", "clusteringAlgorithm": "FTCLUSTERING_NOOP",
                         "monitorMode": "MONITOR_NONE", "distributionFamily": "WEIBULL", "maxTotalRetryJobs": 0,
                         "generatorAddressing": "DENSE_VM_ID_MATRIX", "generators": [], "generatorsByVmId": {}}, audit, location + ":failure")
    contract = needed(config, "algorithmContract")
    require_exact_fields(needed(contract, "planner"), {"id": row["planner"]}, audit, location + ":planner-contract")
    require_exact_fields(needed(contract, "scheduler"), {"id": "STATIC"}, audit, location + ":scheduler-contract")
    pattern = MIPS_PATTERNS[heterogeneity]
    hosts = [{"id": i, "pes": 2, "mipsPerPe": 2000, "ramMb": 2048, "bandwidth": 10000, "storageMb": 1000000} for i in range(count)]
    vms = [{"id": i, "mips": pattern[i % len(pattern)], "pes": 1, "ramMb": 512, "bandwidth": 1, "imageSizeMb": 10000,
            "vmm": "Xen", "schedulerMode": "SPACE_SHARED", "pinnedHostId": i, "preflightHostId": i, "costs": None} for i in range(count)]
    topology = None if network == "endpoint" else {"kind": "FAT_TREE", "k": 4 if count <= 16 else 8,
                "linkBandwidthMbPerSecond": NETWORK_BANDWIDTH[network], "coreSwitchCount": None, "hostEdgePlacements": None,
                "defaultPlacementPolicy": "HOST_ID_ASCENDING_ROUND_ROBIN_OVER_EDGES",
                "routingPolicy": "DETERMINISTIC_AL_FARES_FAT_TREE_V1",
                "linkDirectionality": "INDEPENDENT_DIRECTED_LINKS",
                "externalSourceRouting": "BYPASS_TOPOLOGY_DESTINATION_ENDPOINT_ONLY"}
    require_exact_fields(platform, {"hostCount": count, "vmCount": count, "hosts": hosts, "vms": vms,
                         "storage": {"capacityMb": 1000000000000, "maxTransferRateMbPerSecond": 15},
                         "costs": {"cpuPerSecond": 3, "memory": 0.05, "storage": 0.1, "bandwidth": 0.1},
                         "networkTopology": topology}, audit, location + ":platform")
    outcome = needed(doc, "result")
    require_exact_fields(outcome, {"workflowCompletedSuccessfully": True, "logicalTaskCompletionStatus": "COMPLETED_SUCCESSFULLY",
                         "actualVmHostAssignments": {str(i): i for i in range(count)}}, audit, location + ":result")
    study = needed(needed(doc, "provenance"), "study")
    require_exact_fields(study, {"id": plan["protocol"]}, audit, location + ":study")
    require_exact_fields(needed(study, "protocol"), {"logicalId": plan["protocol"], "available": True, "sha256": protocol_sha}, audit, location + ":protocol")
    require_exact_fields(needed(study, "component"), {"groupId": "org.workflowsim", "artifactId": "network-study", "version": "1.0",
                         "anchorClass": "org.workflowsim.experiments.network.NetworkStudyExecutor"}, audit, location + ":driver")
    workflow = next(w for w in plan["workflows"] if w["id"] == row["workflowId"])
    inputs = needed(doc, "inputs")
    audit.ensure(len(inputs) == 1, "single-study-input", location)
    if len(inputs) == 1:
        require_exact_fields(inputs[0], {"path": workflow["path"], "sha256": workflow["sha256"], "taskCount": row["taskCount"]}, audit, location + ":input")
        require_exact_fields(config, {"workflowPaths": [workflow["path"]]}, audit, location)
    metrics = needed(doc, "metrics")
    require_exact_fields(metrics, {"logicalTaskCompletionStatus": "COMPLETED_SUCCESSFULLY", "allLogicalTasksCompletedSuccessfully": True}, audit, location + ":metrics")
    for rk, mk in RUN_METRICS.items():
        audit.ensure(rk in row and mk in metrics and exact_equal(row.get(rk), metrics.get(mk)), "index-manifest-metric", location + ":" + rk,
                     row.get(rk), metrics.get(mk))


def check_probability(saved, key, expected, audit, location):
    if key not in saved:
        audit.problem("missing-required-statistic", location + "/" + key)
        return
    if expected is None:
        audit.ensure(saved[key] is None, "exact-probability", location + "/" + key, saved[key], None)
    else:
        number(saved[key], key, nonnegative=True)
        audit.ensure(Fraction(saved[key]) == expected, "exact-probability", location + "/" + key, saved[key], expected)


def inspect_index(path, audit, historical=False):
    path = Path(path).resolve()
    before_checks, before_issues = audit.checks, audit.issue_count
    answer = {"source": str(path), "scope": "INDEX_ARITHMETIC_AND_REGISTERED_CONDITIONS_NOT_PHYSICS_OR_NETWORK_REPLAY"}
    keyed = {}
    try:
        doc = read_json(path)
        if needed(doc, "schema") != "workflowsim-network-study-v1":
            raise CheckError("statistics require a registered network study index, not a single manifest")
        plan = needed(doc, "plan")
        variant, old, expected_cells = validate_registered_plan(plan, audit, historical)
        answer.update(protocol=plan["protocol"], mode=plan["mode"], model="HISTORICAL_R10_EXECUTION" if old else EXECUTION_SEMANTICS)
        if old and variant in ("r12", "r13"):
            answer["historicalWarning"] = "The historical LOCAL_PEFT recurrence was nonstandard; arithmetic agreement does not validate standard PEFT/OCT claims."
        protocol_path = contained_file(path.parent, "protocol.json")
        audit.ensure(exact_equal(read_json(protocol_path), plan), "index-retained-protocol", str(path))
        protocol_sha, _ = digest(protocol_path)
        rows = needed(doc, "runs")
        audit.ensure(bool(rows), "nonempty-study", str(path))
        for row in rows:
            key = run_key(row)
            audit.ensure(key not in keyed, "duplicate-study-cell", str(path), key)
            keyed[key] = row
        audit.ensure(set(keyed) == expected_cells, "canonical-cell-matrix", str(path),
                     {"missing": sorted(expected_cells - set(keyed)), "extra": sorted(set(keyed) - expected_cells)}, {"expectedCount": len(expected_cells)})
        _, _, canonical, _ = registered_plan(plan["protocol"], plan["mode"])
        catalogue = {w["id"]: w for w in canonical["workflows"]}
        groups = collections.defaultdict(list)
        usable = True
        for key, row in keyed.items():
            workflow, count, network, heterogeneity, planner, seed = key
            if workflow not in catalogue:
                usable = False
                continue
            spec = catalogue[workflow]
            require_exact_fields(row, {"family": spec["family"], "population": spec["population"], "inputSha256": spec["sha256"],
                                 "status": "COMPLETED_SUCCESSFULLY"}, audit, str(path) + ":run:" + str(key))
            audit.ensure(("heterogeneity" in row) == (variant == "r13"), "heterogeneity-axis-identity", str(path), key)
            run_id = "{}-v{}-{}{}-{}-s{}".format(workflow, count, network, "-" + heterogeneity if variant == "r13" else "", planner, seed)
            require_exact_fields(row, {"runId": run_id}, audit, str(path) + ":run")
            try:
                for field in RUN_METRICS:
                    value = needed(row, field)
                    number(value, field, nonnegative=True, positive=field == "makespanSeconds")
                    if field == "meanVmUtilization" and Decimal(str(value)) > 1:
                        raise CheckError("meanVmUtilization exceeds one")
                if row["status"] != "COMPLETED_SUCCESSFULLY":
                    usable = False
                else:
                    groups[aggregate_key(row)].append(row)
                check_run_binding(path, plan, row, old, protocol_sha, audit)
            except (CheckError, OSError, ValueError, KeyError, TypeError, StopIteration) as error:
                audit.problem("run-binding-or-value", str(path) + ":" + run_id, str(error))
                if any(field not in row for field in RUN_METRICS):
                    usable = False
        answer.update(runCount=len(rows), canonicalExpected=len(expected_cells))
        if not usable or set(keyed) != expected_cells:
            answer["inference"] = "SUPPRESSED_INCOMPLETE_OR_INVALID_MATRIX"
            return answer, keyed
        means = {key: mean([number(r["makespanSeconds"]) for r in runs]) for key, runs in groups.items()}
        summary = needed(doc, "summary")
        require_exact_fields(summary, {"runCount": len(rows), "failedRunCount": 0, "status": "COMPLETE"}, audit, str(path) + ":summary")
        retained_aggregates = needed(summary, "aggregates")
        saved_groups = {aggregate_key(row): row for row in retained_aggregates}
        audit.ensure(len(saved_groups) == len(retained_aggregates) and set(saved_groups) == set(groups), "aggregate-key-set", str(path))
        derived_aggregates = []
        for key, runs in groups.items():
            values = [number(r["makespanSeconds"]) for r in runs]
            expected = {"observedRuns": len(values), "meanSeconds": means[key], "medianSeconds": median(values),
                        "minimumSeconds": min(values), "maximumSeconds": max(values)}
            saved = saved_groups.get(key, {})
            for field, value in expected.items():
                audit.metric(saved, field, value, str(path) + ":aggregate:" + str(key))
            derived_aggregates.append({"key": key, "seeds": [integer(r["seed"]) for r in runs], "seedValuesSeconds": values, **expected})
        comparisons = []
        per_dag = []
        for layer in sorted({stratum(row) for row in rows}):
            family = []
            for candidate in canonical["planners"][1:]:
                effects, wins, ties, losses = [], 0, 0, 0
                for workflow in canonical["workflows"]:
                    if workflow["population"] != layer[0]:
                        continue
                    bk, ck = (workflow["id"],) + layer + ("LOCAL_HEFT",), (workflow["id"],) + layer + (candidate,)
                    if bk not in means or ck not in means:
                        raise CheckError("missing paired DAG/algorithm aggregate")
                    baseline, value = means[bk], means[ck]
                    if baseline <= 0:
                        raise CheckError("baseline makespan must be positive")
                    effect = 100.0 * (baseline - value) / baseline
                    effects.append(effect)
                    tie_tolerance = 1e-9 * max(1.0, baseline, value)
                    label = "WIN" if value < baseline - tie_tolerance else ("LOSS" if value > baseline + tie_tolerance else "TIE")
                    wins += label == "WIN"; losses += label == "LOSS"; ties += label == "TIE"
                    per_dag.append({"workflowId": workflow["id"], "family": workflow["family"], "stratum": layer,
                                    "candidate": candidate, "baselineMeanSeconds": baseline, "candidateMeanSeconds": value,
                                    "candidateMinusBaselineSeconds": value - baseline, "improvementPercent": effect,
                                    "outcome": label, "baselineSeedCount": len(groups[bk]), "candidateSeedCount": len(groups[ck])})
                row = dict(zip(("population", "vmCount", "network", "heterogeneity"), layer))
                row.update(baseline="LOCAL_HEFT", candidate=candidate, dagPairs=len(effects), wins=wins, ties=ties, losses=losses,
                           medianImprovementPercent=median(effects), minimumImprovementPercent=min(effects), maximumImprovementPercent=max(effects),
                           p95ImprovementPercent=percentile(effects, 0.95), improvementsPercent=effects,
                           pExact=exact_sign_test(wins, losses), holmExact=None, significantAfterHolm=False)
                family.append(row)
            holm(family)
            comparisons.extend(family)
        retained_comparisons = needed(summary, "comparisons")
        saved_comparisons = {comparison_key(row): row for row in retained_comparisons}
        audit.ensure(len(saved_comparisons) == len(retained_comparisons)
                     and set(saved_comparisons) == {comparison_key(r) for r in comparisons}, "comparison-key-set", str(path))
        for row in comparisons:
            saved = saved_comparisons.get(comparison_key(row), {})
            location = str(path) + ":comparison:" + str(comparison_key(row))
            for field in ("baseline", "dagPairs", "wins", "ties", "losses", "medianImprovementPercent", "significantAfterHolm"):
                audit.metric(saved, field, row[field], location)
            check_probability(saved, "pValue", row["pExact"], audit, location)
            check_probability(saved, "holmAdjustedPValue", row["holmExact"], audit, location)
            audit.metric(saved, "test", "EXACT_TWO_SIDED_SIGN_TEST_ON_DAG_SEED_MEANS", location)
            audit.metric(saved, "inferenceScope", "EXPLORATORY_SELECTED_CORPUS_NOT_RANDOM_WORKLOAD_POPULATION", location)
        answer.update(aggregates=derived_aggregates, comparisons=comparisons, perDagEffects=per_dag,
                      inference="EXPLORATORY_SELECTED_CORPUS_NOT_IID_FAMILY_SAMPLES",
                      totalMipsByCondition=[{"vmCount": v, "heterogeneity": h, "totalMips": sum(MIPS_PATTERNS[h][i % len(MIPS_PATTERNS[h])] for i in range(v))}
                                            for v, h in sorted({(key[1], key[3]) for key in expected_cells})],
                      resolution={"classicMaxPairs": 5 if plan["mode"] == "full" else 1,
                                  "syntheticMaxPairs": 2 if plan["mode"] == "full" else 1,
                                  "classicMinimumTwoSidedP": Fraction(2, 2 ** (5 if plan["mode"] == "full" else 1)),
                                  "alpha": Fraction(1, 20), "sameRootSeedsAreEventKeyedCRN": False})
    except (CheckError, OSError, ValueError, KeyError, TypeError, ArithmeticError) as error:
        audit.problem("invalid-or-unsupported-study", str(path), str(error))
        answer["aborted"] = str(error)
    finally:
        answer["checks"] = audit.checks - before_checks
        answer["issueCount"] = audit.issue_count - before_issues
    return answer, keyed


def audit_sources(paths, audit, historical=False):
    results, run_maps = [], []
    for path in paths:
        result, rows = inspect_index(path, audit, historical)
        results.append(result); run_maps.append(rows)
    overlaps = []
    for left, right in combinations(range(len(results)), 2):
        a, b = results[left], results[right]
        if a.get("model") != b.get("model") or "model" not in a:
            overlaps.append({"left": a["source"], "right": b["source"], "status": "NOT_COMPARED_DIFFERENT_OR_UNKNOWN_EXECUTION_MODEL"})
            continue
        shared = set(run_maps[left]) & set(run_maps[right])
        differences = []
        for key in sorted(shared):
            for field in RUN_METRICS:
                x, y = run_maps[left][key].get(field), run_maps[right][key].get(field)
                matches = x is not None and y is not None and exact_equal(x, y)
                audit.ensure(matches, "shared-condition-numeric-difference", a["source"] + " <> " + b["source"],
                             {"cell": key, "metric": field, "left": x, "right": y})
                if not matches:
                    differences.append({"cell": key, "metric": field, "left": x, "right": y})
        overlaps.append({"left": a["source"], "right": b["source"], "sharedRunCount": len(shared), "differences": differences})
    return results, overlaps


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("inputs", nargs="+", type=Path, help="one or more explicit network-study.json paths")
    parser.add_argument("--output", required=True, type=Path, help="new JSON report outside every source study")
    parser.add_argument("--historical", action="store_true", help="explicitly permit frozen old protocol IDs; do not relabel them current")
    parser.add_argument("--max-issues", type=int, default=200)
    args = parser.parse_args(argv)
    try:
        if args.max_issues < 1:
            raise CheckError("max-issues must be positive")
        output = protect_output(args.output, args.inputs)
        audit = Audit(rel_tol=1e-11, abs_tol=1e-10, max_issues=args.max_issues)
        studies, overlaps = audit_sources(args.inputs, audit, args.historical)
        report = {"schema": "workflowsim-independent-statistics-audit-v1", "scope": "INDEPENDENT_REGISTERED_MATRIX_AND_STATISTICS_NOT_SIMULATION_REPLAY",
                  "studies": studies, "sharedConditions": overlaps, **audit.report()}
        write_report(output, report)
        print("{}: {} studies, {} issues; {}".format(report["status"], len(studies), audit.issue_count, output))
        return 1 if audit.issue_count else 0
    except (CheckError, OSError) as error:
        print("audit refused: " + str(error), file=sys.stderr)
        return 2


if __name__ == "__main__":
    sys.exit(main())
