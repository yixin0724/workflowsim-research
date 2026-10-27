#!/usr/bin/env python3
"""Self-test for analyze-energy-portrait.py — statistical primitives on hand-computed
fixtures plus derivation invariants on the retained powered benchmark (BAT-E6, 2026-10-27).

Every statistical primitive is checked against hand arithmetic; the published numbers
(run count, conservation, pair census, the one Holm-significant finding) are locked on
the real evidence so any future drift breaks loudly.
"""
import json
import math
import sys
import tempfile
import unittest
from pathlib import Path

import importlib.util

HERE = Path(__file__).resolve().parent


def load_portrait_module():
    spec = importlib.util.spec_from_file_location('analyze_energy_portrait', HERE / 'analyze-energy-portrait.py')
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


_ap = load_portrait_module()
comparisons = _ap.comparisons
conditions = _ap.conditions
conservation = _ap.conservation
family_of = _ap.family_of
holm = _ap.holm
monotonicity = _ap.monotonicity
sign_test = _ap.sign_test


class StatisticalPrimitivesTest(unittest.TestCase):
    def test_sign_test_exact_two_sided(self):
        # n=10, k=0: 2 × C(10,0)/2^10 = 2/1024
        self.assertAlmostEqual(sign_test(10, 0), 2.0 / 1024.0)
        sign_test_kw = 2.0 * sum(math.comb(10, i) for i in range(0, 2)) / 1024.0
        self.assertAlmostEqual(sign_test(1, 9), sign_test_kw)  # symmetric k=1
        self.assertEqual(sign_test(0, 0), 1.0)  # no non-tied pairs
        self.assertEqual(sign_test(5, 5), 1.0)  # median extreme capped at 1

    def test_holm_hand_example(self):
        # p = [0.01, 0.04, 0.03], m = 3:
        # rank1 0.01×3 = 0.03; rank2 0.03×2 = 0.06; rank3 0.04×1 = 0.04 → running max 0.03/0.06/0.06
        adjusted = holm([0.01, 0.04, 0.03])
        self.assertAlmostEqual(adjusted[0], 0.03)
        self.assertAlmostEqual(adjusted[1], 0.06)
        self.assertAlmostEqual(adjusted[2], 0.06)

    def test_family_of_rejects_unknowns(self):
        self.assertEqual(family_of("SHARED_STORAGE_HEFT"), "SHARED")
        self.assertEqual(family_of("LOCAL_CPOP"), "LOCAL")
        self.assertEqual(family_of("FCFS"), "ONLINE")
        self.assertEqual(family_of("READY_BATCH_MCT"), "ONLINE")
        with self.assertRaises(Exception):
            family_of("SOMETHING_ELSE")


class DerivationInvariantsTest(unittest.TestCase):
    """Hand-built synthetic corpus locks the derivations piece by piece."""

    ROW_TEMPLATE = {
        "runId": "synthetic", "input": "X", "vmCount": 2,
        "policy": "SHARED_STORAGE_HEFT", "kind": "K",
        "makespan": 100.0, "busy": 50.0, "energy": 0.0,
        "edp": 0.0, "idleSum": 500.0, "deltaVm": 150.0, "status": "COMPLETED_SUCCESSFULLY",
    }

    def test_conservation_closes_and_rejects_drift(self):
        row = dict(self.ROW_TEMPLATE)
        closed = 100.0 * 500.0 + 150.0 * 50.0
        row["energy"] = closed
        row["edp"] = closed * 100.0
        rows = [row]
        self.assertEqual(conservation(rows), 1)
        row2 = dict(row)
        row2["busy"] = 50.001
        row2["energy"] = row["energy"]  # stale energy vs drifted busy -> must be caught
        rows2 = [row2]
        with self.assertRaises(Exception):
            conservation(rows2)  # closed form no longer holds

    def test_monotonicity_departure_is_attributed(self):
        def mk(policy, makespan, busy, idle, delta, edp_scale):
            energy = makespan * idle + delta * busy
            return {"runId": "s", "input": "X", "vmCount": 2, "policy": policy, "kind": "K",
                    "makespan": makespan, "busy": busy, "energy": energy,
                    "edp": energy * edp_scale, "idleSum": idle, "deltaVm": delta,
                    "status": "COMPLETED_SUCCESSFULLY"}
        # Fixed-busy pair: energy co-directional with makespan.
        rows = [mk("FCFS", 100.0, 50.0, 500.0, 150.0, 1.0), mk("READY_BATCH_MCT", 120.0, 50.0, 500.0, 150.0, 1.0)]
        table = conditions(rows)
        result = monotonicity(table)
        self.assertEqual(result["pairs"], 1)
        self.assertEqual(result["coDirectional"], 1)
        # Busy-drift departure: larger makespan but much smaller busy → energy falls.
        rows = [mk("FCFS", 100.0, 60.0, 500.0, 150.0, 1.0), mk("READY_BATCH_MAXMIN", 101.0, 40.0, 500.0, 150.0, 1.0)]
        table = conditions(rows)
        result = monotonicity(table)
        self.assertEqual(result["opposite"], 1)
        dep = result["departures"][0]
        self.assertGreater(dep["dominanceRatio"], 1.0)  # busy term dominates idle-dT term

    def test_comparison_tallies_and_holm_family(self):
        def mk(policy, input_id, edp):
            return {"runId": "s", "input": input_id, "vmCount": 2, "policy": policy, "kind": "K",
                    "makespan": 100.0, "busy": 1.0, "energy": 0.0, "edp": edp,
                    "idleSum": 1.0, "deltaVm": 1.0, "status": "COMPLETED_SUCCESSFULLY"}
        rows = []
        for input_id, other_edp in (("a", 1.0), ("b", 5.0), ("c", 5.0), ("d", 5.0)):
            rows.append(mk("FCFS", input_id, 3.0))
            rows.append(mk("READY_BATCH_MINMIN", input_id, other_edp))
        table = conditions(rows)
        result = comparisons(table)
        self.assertIn("ONLINE", result)
        entries = result["ONLINE"]
        self.assertEqual(len(entries), 1)
        entry = entries[0]
        # MINMIN better (lower EDP) on 1 condition, worse on 3: wins=1, losses=3.
        self.assertEqual((entry["wins"], entry["losses"], entry["ties"]), (1, 3, 0))
        self.assertAlmostEqual(entry["pValue"], sign_test(1, 3))
        # Only regrets Holm m=1: adjusted == raw
        self.assertAlmostEqual(entry["holmAdjustedP"], entry["pValue"])


class RetainedBenchmarkInvariantTest(unittest.TestCase):
    """The published portrait numbers lock on the retained powered benchmark."""

    def test_real_portrait_derives_the_published_numbers(self):
        root = HERE.parent / "output" / "benchmark-standard-power-v1"
        if not root.exists():
            self.skipTest("retained powered benchmark not present on this machine")
        ap = load_portrait_module()
        with tempfile.TemporaryDirectory() as tmp:
            out = Path(tmp) / "portrait-selftest.json"
            ap.main([str(root), str(out)])
            portrait = json.loads(out.read_text(encoding="utf-8"))
        self.assertEqual(portrait["runs"], 160)
        self.assertEqual(portrait["conservationExact"], 160)
        mono = portrait["monotonicity"]
        self.assertEqual((mono["pairs"], mono["coDirectional"], mono["opposite"]), (207, 201, 6))
        for departure in mono["departures"]:
            self.assertGreater(departure["dominanceRatio"], 0.999)
        # The one Holm-significant finding in the corpus: PEFT worse than family
        # reference HEFT on SHARED EDP (holm-adjusted p = 0.015625).
        shared = {item["policy"]: item for item in portrait["edpComparisons"]["SHARED"]}
        self.assertIn("SHARED_STORAGE_PEFT", shared)
        peft = shared["SHARED_STORAGE_PEFT"]
        self.assertAlmostEqual(peft["holmAdjustedP"], 0.015625, places=9)
        self.assertTrue(peft["significantAtHolm05"])
        self.assertEqual(peft["losses"], 9)  # worse than HEFT on 9 of 10 conditions
        # No other family has a Holm-significant label at this corpus size.
        for fam, items in portrait["edpComparisons"].items():
            for item in items:
                if fam != "SHARED" or item["policy"] != "SHARED_STORAGE_PEFT":
                    self.assertFalse(item["significantAtHolm05"])


if __name__ == "__main__":
    unittest.main(verbosity=2)
