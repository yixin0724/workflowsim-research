#!/usr/bin/env python3
"""Energy portrait of the algorithm corpus — the analysis behind the benchmark power variant.

Reads every run manifest of output/benchmark-standard-power-v1, derives the per-layer
energy/EDP statistics under a frozen homogeneous 250 W/400 W power model, and emits a
self-contained JSON document (the published analysis artifact). The companion self-test
scripts/test-energy-portrait.py verifies every statistical primitive on hand-computed
fixtures plus these derivations on the retained evidence.

Analysis conventions (CAMPAIGNS discipline):
- Same-layer comparisons only; each (input, tier) is one paired condition.
- Paired two-sided exact sign test on non-tied conditions; ties disclosed.
- Holm correction over the comparison family of each layer (m = 4 per layer).

Usage: python3 -B scripts/analyze-energy-portrait.py <benchmark-power-root> <output.json>
"""
import argparse
import json
import math
import sys
from collections import defaultdict
from pathlib import Path

FAMILY_REFERENCE = {
    "SHARED": "SHARED_STORAGE_HEFT",
    "ONLINE": "FCFS",
    "LOCAL": "LOCAL_HEFT",
}


class CheckError(ValueError):
    pass


def family_of(policy):
    if policy.startswith("SHARED"):
        return "SHARED"
    if policy.startswith("LOCAL"):
        return "LOCAL"
    if policy in ("FCFS", "READY_BATCH_MINMIN", "READY_BATCH_MAXMIN", "READY_BATCH_MCT", "READY_BATCH_ROUNDROBIN"):
        return "ONLINE"
    raise CheckError("unknown policy family: " + str(policy))


def read_rows(root):
    rows = []
    for manifest in sorted((Path(root) / "runs").glob("*/result.manifest.json")):
        doc = json.loads(manifest.read_bytes())
        cfg, res, met, energy, plat = (doc["configuration"], doc["result"], doc["metrics"],
                                       doc["energyMetrics"], doc["platform"])
        policy = cfg.get("planningAlgorithm")
        if not policy or policy == "INVALID":
            policy = cfg["schedulingAlgorithm"]
        vms = plat["vms"]
        idle = sum(float(v.get("idlePowerWatts", 0.0)) for v in vms)
        deltas = [float(v.get("maxPowerWatts", 0.0)) - float(v.get("idlePowerWatts", 0.0)) for v in vms]
        if min(deltas) != max(deltas):
            raise CheckError("portrait requires a homogeneous delta fleet: " + str(manifest))
        row = {
            "runId": manifest.parent.name,
            "input": manifest.parent.name.split("-v")[0],
            "vmCount": cfg["vmCount"],
            "policy": str(policy),
            "kind": cfg["dataMovementModel"]["kind"],
            "makespan": float(res["makespan"]),
            "busy": float(met["totalVmModeledBusyIntervalSeconds"]),
            "energy": float(energy["totalEnergyJoules"]),
            "edp": float(energy["energyMakespanProductJouleSeconds"]),
            "idleSum": idle,
            "deltaVm": deltas[0],
            "status": doc.get("status", "COMPLETED_SUCCESSFULLY"),
        }
        rows.append(row)
    if not rows:
        raise CheckError("no run manifests under " + str(root))
    return rows


def close_enough(computed, stored, magnitude):
    """Scale-aware conservation tolerance (64-bit float drift on 1e10-scale energies)."""
    return abs(computed - stored) <= 1e-9 * max(1.0, abs(magnitude))


def conservation(rows):
    exact = 0
    for row in rows:
        expect = row["makespan"] * row["idleSum"] + row["deltaVm"] * row["busy"]
        if close_enough(expect, row["energy"], expect):
            exact += 1
        else:
            raise CheckError("conservation violation in " + row["runId"])
    return exact


def sign_test(wins, losses):
    """Two-sided exact sign test p-value; ties excluded by the caller."""
    n = wins + losses
    if n == 0:
        return 1.0
    k = min(wins, losses)
    p = sum(math.comb(n, i) for i in range(0, k + 1)) / (2.0 ** n)
    return min(1.0, 2.0 * p)


def holm(pvalues):
    """Holm step-down adjusted p-values, order-stable."""
    m = len(pvalues)
    order = sorted(range(m), key=lambda i: pvalues[i])
    running = 0.0
    adjusted = [None] * m
    for rank, index in enumerate(order):
        value = (m - rank) * pvalues[index]
        running = max(running, value)
        adjusted[index] = min(1.0, running)
    return adjusted


def conditions(rows):
    table = defaultdict(dict)
    for row in rows:
        fam = family_of(row["policy"])
        key = (row["input"], row["vmCount"], fam, row["kind"] if fam == "LOCAL" else "-")
        table[key][row["policy"]] = row
    return table


def layer_rankings(rows):
    """Per-family RMS EDP table (paired conditions, equal weights by family)."""
    fam_edp = defaultdict(lambda: defaultdict(list))
    for row in rows:
        fam = family_of(row["policy"])
        fam_edp[fam][row["policy"]].append(row["edp"])
    summary = {}
    for fam, policies in fam_edp.items():
        entries = []
        for policy, edps in policies.items():
            entries.append({
                "policy": policy,
                "conditions": len(edps),
                "meanEuting": sum(edps) / len(edps),
                "rmsEuting": math.sqrt(sum(e * e for e in edps) / len(edps)),
                "medianEuting": sorted(edps)[len(edps) // 2] if len(edps) % 2 else 0.5 * (
                    sorted(edps)[len(edps) // 2 - 1] + sorted(edps)[len(edps) // 2]),
            })
        entries.sort(key=lambda e: e["rmsEuting"])
        summary[fam] = entries
    return summary


def comparisons(table):
    """Within-layer paired sign tests vs the family reference + Holm per layer."""
    by_family = defaultdict(list)
    for key in sorted(table):
        d = table[key]
        fam = key[2]
        reference = FAMILY_REFERENCE[fam]
        if reference not in d:
            raise CheckError("family reference missing in " + str(key))
        for policy, row in sorted(d.items()):
            if policy == reference:
                continue
            ref = d[reference]
            delta = row["edp"] - ref["edp"]
            by_family[fam].append({
                "conditionKey": list(key),
                "policy": policy,
                "reference": reference,
                "deltaEuting": delta,
                "win": 1 if delta < 0 else (0 if delta == 0 else -1),
            })
    result = {}
    for fam, pairs in sorted(by_family.items()):
        per_policy = defaultdict(lambda: {"s": 0, "f": 0, "t": 0})
        for pair in pairs:
            slot = per_policy[pair["policy"]]
            if pair["win"] == 1:
                slot["s"] += 1
            elif pair["win"] == -1:
                slot["f"] += 1
            else:
                slot["t"] += 1
        raw = []
        for policy in sorted(per_policy):
            slot = per_policy[policy]
            raw.append({
                "policy": policy,
                "reference": FAMILY_REFERENCE[fam],
                "wins": slot["s"],
                "losses": slot["f"],
                "ties": slot["t"],
                "pValue": sign_test(slot["s"], slot["f"]),
            })
        adjusted = holm([item["pValue"] for item in raw])
        for item, adj in zip(raw, adjusted):
            item["holmAdjustedP"] = adj
            item["significantAtHolm05"] = adj < 0.05
        result[fam] = raw
    return result


def monotonicity(table):
    """Within-condition policy pairs: does energy co-move with makespan; explain departures."""
    pairs = co = opposite = 0
    departures = []
    for key in sorted(table):
        d = table[key]
        items = sorted(d.items())
        for i, (pa, ra) in enumerate(items):
            for pb, rb in items[i + 1:]:
                d_t, d_e = rb["makespan"] - ra["makespan"], rb["energy"] - ra["energy"]
                if abs(d_t) < 1e-9:
                    continue
                pairs += 1
                if (d_t > 0) == (d_e > 0):
                    co += 1
                else:
                    opposite += 1
                    idle_dt = ra["idleSum"] * abs(d_t)
                    busy_term = ra["deltaVm"] * abs(rb["busy"] - ra["busy"])
                    departures.append({
                        "conditionKey": list(key),
                        "policyA": pa,
                        "policyB": pb,
                        "deltaMakespan": d_t,
                        "deltaEnergy": d_e,
                        "idlePowerTerm": idle_dt,
                        "busyDriftTerm": busy_term,
                        "dominanceRatio": busy_term / idle_dt if idle_dt > 0 else float("inf"),
                    })
    return {"pairs": pairs, "coDirectional": co, "opposite": opposite, "departures": departures}


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("benchmarkRoot", type=Path)
    parser.add_argument("output", type=Path)
    args = parser.parse_args(argv)
    rows = read_rows(args.benchmarkRoot)
    table = conditions(rows)
    portrait = {
        "schema": "workflowsim-energy-portrait-v1",
        "benchmark": "benchmark-standard-power-v1",
        "runs": len(rows),
        "conservationExact": conservation(rows),
        "layerRankings": layer_rankings(rows),
        "edpComparisons": comparisons(table),
        "monotonicity": monotonicity(table),
    }
    args.output.parent.mkdir(parents=True, exist_ok=True)
    if args.output.exists():
        raise CheckError("output already exists: " + str(args.output))
    args.output.write_text(json.dumps(portrait, indent=2, allow_nan=False) + "\n", encoding="utf-8")
    print("portrait:", portrait["runs"], "runs,", portrait["conservationExact"], "conservation-exact")
    return 0


if __name__ == "__main__":
    sys.exit(main())
