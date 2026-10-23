#!/usr/bin/env python3
"""Registered-mutant gate: verifies the maintained test battery still kills
every registered semantic mutation (mutation-survival = 0 gate).

Statuses
    KILLED        the registered killer tests (or the full module battery)
                  fail while this mutant is applied
    SURVIVED      mutant applied and the full simulator battery stayed green
    ANCHOR_STALE  the registered anchor no longer occurs exactly once in the
                  current tree (source drift; the ledger must be updated)
    INFRA_FAILURE build/test infrastructure failed without test failures

Exit codes
    0  all registered mutants KILLED
    1  at least one SURVIVED or INFRA_FAILURE
    2  at least one ANCHOR_STALE (takes precedence: ledger maintenance first)

Usage
    python3 -B scripts/mutation-gate.py --output <new-report.json>
    python3 -B scripts/mutation-gate.py --output <new-report.json> --dry-run
    python3 -B scripts/mutation-gate.py --output <new-report.json> --ids M5,M9

Safety: mutation is applied in place to the working tree; every file is
restored from an in-memory backup in a `finally` block and re-verified after
restore. Full mode refuses to start on a dirty Git tree. Run it exclusively
(no concurrent Maven builds in the same checkout).
"""

import argparse
import json
import pathlib
import re
import subprocess
import sys
import time

ROOT = pathlib.Path(__file__).resolve().parent.parent

TEST_STATS = re.compile(r"Tests run: (\d+), Failures: (\d+), Errors: (\d+)")

REGISTRY = [
    dict(
        id="M1",
        name="HEFT upward rank takes first child instead of maximum child",
        file="simulator/src/main/java/org/workflowsim/planning/SharedStorageDagPlanner.java",
        old="            childMaximum = Math.max(childMaximum, upwardRanks.get(child).doubleValue());",
        new="            childMaximum = upwardRanks.get(child).doubleValue();",
        killers="SharedStorageHeftPlanningAlgorithmTest,SharedStorageDlsPlanningAlgorithmTest,"
                "SharedStorageEtfPlanningAlgorithmTest,SharedStorageCpopPlanningAlgorithmTest"),
    dict(
        id="M2",
        name="MCT drops the availability term",
        file="simulator/src/main/java/org/workflowsim/planning/StaticIndependentPlanningSupport.java",
        old="            double finish = Double.isInfinite(duration) ? Double.POSITIVE_INFINITY : availability[index] + duration;",
        new="            double finish = Double.isInfinite(duration) ? Double.POSITIVE_INFINITY : duration;",
        killers="StaticIndependentPlanningAlgorithmsTest"),
    dict(
        id="M3",
        name="dependency gate inverted (only unsatisfied jobs pass)",
        file="simulator/src/main/java/org/workflowsim/WorkflowEngine.java",
        old="            if (!receivedJobIds.contains(job.getCloudletId())) {",
        new="            if (receivedJobIds.contains(job.getCloudletId())) {",
        killers="MetamorphicGapIntegrationTest,ReproducibilityTest"),
    dict(
        id="M4",
        name="fat-tree aggregation picks halfK instead of availableAggregateCount",
        file="simulator/src/main/java/org/workflowsim/network/FatTreeTopology.java",
        old="        int agg = src[1] % availableAggregateCount;",
        new="        int agg = src[1] % halfK;",
        killers="FatTreeTopologyTest"),
    dict(
        id="M5",
        name="fluid completion tolerance magnified x1000",
        file="simulator/src/main/java/org/workflowsim/data/TransferContentionEngine.java",
        old="            double tolerance = Math.min(bytes * 0.5, Math.max(1.0e-9 * bytes, 4.0 * Math.ulp(bytes)));",
        new="            double tolerance = Math.min(bytes * 0.5, Math.max(1.0e-6 * bytes, 4.0 * Math.ulp(bytes)));",
        killers="TransferContentionEngineTest,FractionalTransferContentionEngineTest,"
                "MaxMinTransferTest,TransferCompletionToleranceBoundaryTest"),
    dict(
        id="M6",
        name="MI conversion floor -> round at the parser boundary",
        file="simulator/src/main/java/org/workflowsim/WorkflowParser.java",
        old="""        if (convertedLength < MIN_CLOUDLET_LENGTH) {
            report.recordNormalization(
                    WorkflowInputReport.NormalizationKind.TASK_RUNTIME_FLOORED_TO_MINIMUM,
                    taskId,
                    convertedLength,
                    MIN_CLOUDLET_LENGTH);
            return MIN_CLOUDLET_LENGTH;
        }
        return (long) convertedLength;""",
        new="""        if (convertedLength < MIN_CLOUDLET_LENGTH) {
            report.recordNormalization(
                    WorkflowInputReport.NormalizationKind.TASK_RUNTIME_FLOORED_TO_MINIMUM,
                    taskId,
                    convertedLength,
                    MIN_CLOUDLET_LENGTH);
            return MIN_CLOUDLET_LENGTH;
        }
        return Math.round(convertedLength);""",
        killers="WorkflowInputValidationTest,WorkflowParserStrictValidationTest,"
                "WfCommonsJsonParserValidationTest,WorkflowDependencyParsingScalabilityTest"),
    dict(
        id="M7",
        name="balanced merge drops the child-side tail unlink",
        file="simulator/src/main/java/org/workflowsim/clustering/balancing/methods/BalancingMethod.java",
        old="""            taskSet.getParentList().remove(tail);
            if (!taskSet.getParentList().contains(head)) {
                taskSet.getParentList().add(head);
            }""",
        new="""            if (!taskSet.getParentList().contains(head)) {
                taskSet.getParentList().add(head);
            }""",
        killers="ClusteringConservationPropertyTest"),
    dict(
        id="M8",
        name="online assignment halves the compute estimate of the nominal score",
        file="simulator/src/main/java/org/workflowsim/data/v2/DataflowVmAssigner.java",
        old="""            double compute = TaskExecutionModel.executionSeconds(task, vm.id, vm.mips);
            InputEstimate inputs = estimateInputs(observation, demands, DataLocation.vm(vm.id));
            double cpu = cpuAvailable(observation, vm.id, now);
            double finish = addTime(Math.max(addTime(now, inputs.seconds), cpu), compute);""",
        new="""            double compute = TaskExecutionModel.executionSeconds(task, vm.id, vm.mips);
            InputEstimate inputs = estimateInputs(observation, demands, DataLocation.vm(vm.id));
            double cpu = cpuAvailable(observation, vm.id, now);
            double finish = addTime(Math.max(addTime(now, inputs.seconds), cpu), compute / 2.0);""",
        killers="DataflowVmAssignerTest,DataflowAssignmentValidatorTest"),
    dict(
        id="M9",
        name="task execution window globally offset by 1e-6 seconds",
        file="simulator/src/main/java/org/workflowsim/WorkflowDatacenter.java",
        old="        double startTime = job.getExecStartTime() + effectiveStageInSeconds;",
        new="        double startTime = job.getExecStartTime() + effectiveStageInSeconds + 1.0e-6;",
        killers="NetworkEvidenceDatacenterTest,SimulationRunnerFailureCoverageIntegrationTest,"
                "SimulationRunnerAlgorithmContractTest,EventTerminalAndJobStartContractTest"),
    dict(
        id="M10",
        name="SHARED DLS dynamic level flips the earliest-start subtraction into an addition",
        file="simulator/src/main/java/org/workflowsim/planning/SharedStorageDagPlanner.java",
        old="                        new DlsCandidate(task, vm, upwardRanks.get(task).doubleValue() - earliestStart);",
        new="                        new DlsCandidate(task, vm, upwardRanks.get(task).doubleValue() + earliestStart);",
        killers="SharedStoragePlannerRuntimeMirrorIntegrationTest,SharedStorageDlsPlanningAlgorithmTest,"
                "SharedStorageDlsEtfSlotSemanticsAuditTest,SharedStorageDlsEtfSlotVisitAuditTest"),
]


class AnchorError(ValueError):
    """The registered anchor does not occur exactly once in the target file."""


def parse_test_stats(output):
    """Return (runs, failures) from Surefire's summary line, else None."""
    match = TEST_STATS.search(output)
    if not match:
        return None
    runs = int(match.group(1))
    failures = int(match.group(2)) + int(match.group(3))
    return runs, failures


def classify_stage(exit_code, failures):
    if exit_code != 0 and failures:
        return "KILLED"
    if exit_code != 0:
        return "INFRA_FAILURE"
    return "NOT_KILLED_BY_STAGE"


def apply_mutation(text, old, new):
    if text.count(old) != 1:
        raise AnchorError("anchor occurs %d times (expected 1)" % text.count(old))
    return text.replace(old, new, 1)


def collect_anchor_states(registry=None):
    """Read-only liveness map: mutant id -> LIVE or ANCHOR_STALE."""
    states = {}
    for mutant in (registry or REGISTRY):
        path = ROOT / mutant["file"]
        try:
            text = path.read_text(encoding="utf-8")
        except OSError:
            states[mutant["id"]] = "ANCHOR_STALE"
            continue
        states[mutant["id"]] = "LIVE" if text.count(mutant["old"]) == 1 else "ANCHOR_STALE"
    return states


def summarize(results):
    killed = [r for r in results if r["status"] == "KILLED"]
    survivors = [r for r in results if r["status"] == "SURVIVED"]
    return {
        "total": len(results),
        "killed": len(killed),
        "survived": len(survivors),
        "survivorIds": [r["id"] for r in survivors],
        "staleIds": [r["id"] for r in results if r["status"] == "ANCHOR_STALE"],
        "infraFailureIds": [r["id"] for r in results if r["status"] == "INFRA_FAILURE"],
    }


def report_exit_code(results):
    if any(r["status"] == "ANCHOR_STALE" for r in results):
        return 2
    if any(r["status"] in ("SURVIVED", "INFRA_FAILURE") for r in results):
        return 1
    return 0


def write_report(path, data):
    path = pathlib.Path(path)
    if path.exists():
        raise OSError("gate refused: output must be a new file: %s" % path)
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(data, ensure_ascii=False, indent=1) + "\n", encoding="utf-8")


def run_maven(arguments):
    started = time.time()
    proc = subprocess.run(
        ["mvn"] + arguments, cwd=str(ROOT), capture_output=True, text=True)
    return proc.returncode, proc.stdout + proc.stderr, time.time() - started


def git_tree_dirty():
    try:
        proc = subprocess.run(
            ["git", "status", "--porcelain"], cwd=str(ROOT),
            capture_output=True, text=True)
        if proc.returncode != 0:
            return None  # git unavailable: caller decides how to treat this
        return bool(proc.stdout.strip())
    except OSError:
        return None


def execute_mutant(mutant):
    """Apply one registered mutant in place, run the battery, restore, and report."""
    path = ROOT / mutant["file"]
    original = path.read_text(encoding="utf-8")
    if original.count(mutant["old"]) != 1:
        return {"id": mutant["id"], "name": mutant["name"], "file": mutant["file"],
                "status": "ANCHOR_STALE", "stage": "preflight"}
    elapsed = 0.0
    stage = "killers"
    try:
        path.write_text(
            original.replace(mutant["old"], mutant["new"]), encoding="utf-8")
        code, out, secs = run_maven([
            "-pl", ":workflowsim", "test",
            "-Dtest=%s" % mutant["killers"],
            "-Dsurefire.failIfNoSpecifiedTests=false"])
        elapsed += secs
        stats = parse_test_stats(out)
        failures = stats[1] if stats else 0
        if code != 0 and failures:
            return {"id": mutant["id"], "name": mutant["name"], "file": mutant["file"],
                    "status": "KILLED", "stage": "killers", "testStats": stats,
                    "seconds": round(elapsed, 1)}
        code, out, secs = run_maven(["-pl", ":workflowsim", "test"])
        elapsed += secs
        stats = parse_test_stats(out)
        failures = stats[1] if stats else 0
        if code != 0 and failures:
            status = "KILLED"
        elif code != 0:
            status = "INFRA_FAILURE"
        else:
            status = "SURVIVED"
        return {"id": mutant["id"], "name": mutant["name"], "file": mutant["file"],
                "status": status, "stage": "full-module", "testStats": stats,
                "seconds": round(elapsed, 1)}
    finally:
        path.write_text(original, encoding="utf-8")
        restored = path.read_text(encoding="utf-8")
        if restored != original:
            raise RuntimeError("restore verification failed for %s" % mutant["file"])


def main(argv=None):
    parser = argparse.ArgumentParser(
        description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--output", required=True,
                        help="path for the new JSON report (must not exist)")
    parser.add_argument("--dry-run", action="store_true",
                        help="anchor-liveness and ledger check only; no mutation, no Maven")
    parser.add_argument("--ids", default=None,
                        help="comma-separated mutant ids to execute (default: all)")
    args = parser.parse_args(argv)

    if args.ids:
        wanted = {token.strip() for token in args.ids.split(",") if token.strip()}
        known = {mutant["id"] for mutant in REGISTRY}
        unknown = wanted - known
        if unknown:
            parser.error("unknown mutant ids: %s" % ", ".join(sorted(unknown)))
        registry = [mutant for mutant in REGISTRY if mutant["id"] in wanted]
    else:
        registry = list(REGISTRY)

    started = time.time()
    if args.dry_run:
        states = collect_anchor_states(registry)
        results = [{"id": mutant["id"], "name": mutant["name"], "file": mutant["file"],
                    "status": states[mutant["id"]], "stage": "dry-run"}
                   for mutant in registry]
    else:
        dirty = git_tree_dirty()
        if dirty:
            print("mutation-gate: refusing to run on a dirty Git tree; "
                  "commit or stash first", file=sys.stderr)
            return 2
        if dirty is None:
            print("mutation-gate: git unavailable; proceeding with in-place "
                  "backup/restore only", file=sys.stderr)
        results = [execute_mutant(mutant) for mutant in registry]

    report = {
        "schema": "workflowsim-mutation-gate-v1",
        "scope": "REGISTERED_MUTANT_SURVIVAL_GATE_NOT_A_TEST substitute_entry",
        "mode": "dry-run" if args.dry_run else "full",
        "summary": summarize(results),
        "results": results,
        "wallClockSeconds": round(time.time() - started, 1),
    }
    write_report(args.output, report)
    summary = report["summary"]
    print("mutation-gate: %d/%d KILLED; survivors=%s stale=%s infra=%s" % (
        summary["killed"], summary["total"],
        summary["survivorIds"] or "-", summary["staleIds"] or "-",
        summary["infraFailureIds"] or "-"))
    for result in results:
        print("  [%s] %s @ %s (%ss)" % (
            result["id"], result["status"], result["stage"], result.get("seconds", 0)))
    print("report: %s" % args.output)
    return report_exit_code(results)


if __name__ == "__main__":
    sys.exit(main())
