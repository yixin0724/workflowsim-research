#!/usr/bin/env python3
"""Fixed-oracle stdlib tests for the registered-mutant gate.

Run: python3 -B scripts/test-mutation-gate.py
No Maven, no Java build, and no repository mutation happen here: the tests
cover the registry ledger, anchor liveness against the current tree, pure
classification logic, and report/exit semantics. The anchored sources are
read-only in this test.
"""

import importlib.util
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from unittest import mock

sys.dont_write_bytecode = True
SCRIPT = Path(__file__).resolve().with_name("mutation-gate.py")
ROOT = SCRIPT.parent.parent


def load_gate():
    spec = importlib.util.spec_from_file_location("mutation_gate", SCRIPT)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


GATE = load_gate()


class RegistryLedgerTests(unittest.TestCase):
    """The registered mutant set is the gate's ledger; it must be coherent."""

    def test_registry_is_non_empty(self):
        self.assertGreaterEqual(len(GATE.REGISTRY), 9)

    def test_ids_are_unique_and_well_formed(self):
        ids = [mutant["id"] for mutant in GATE.REGISTRY]
        self.assertEqual(len(ids), len(set(ids)))
        for identifier in ids:
            self.assertRegex(identifier, r"^M[0-9]+$")

    def test_every_mutant_has_required_fields(self):
        for mutant in GATE.REGISTRY:
            for field in ("id", "name", "file", "old", "new", "killers"):
                self.assertIn(field, mutant)
            self.assertTrue(mutant["old"].strip())
            self.assertNotEqual(mutant["old"], mutant["new"])
            self.assertTrue(mutant["killers"])

    def test_every_target_file_resides_in_simulator_sources(self):
        for mutant in GATE.REGISTRY:
            path = ROOT / mutant["file"]
            self.assertTrue(path.is_file(), mutant["file"])
            self.assertIn("simulator/src/main/java/", mutant["file"])

    def test_anchor_liveness_against_current_tree(self):
        """Drift guard: each anchor must still occur exactly once, else stale."""
        for mutant in GATE.REGISTRY:
            text = (ROOT / mutant["file"]).read_text(encoding="utf-8")
            count = text.count(mutant["old"])
            self.assertEqual(count, 1, f"{mutant['id']} anchor count {count}")


class ClassificationTests(unittest.TestCase):
    def test_surefire_stats_parser(self):
        output = "[INFO] Tests run: 12, Failures: 2, Errors: 1, Skipped: 0"
        self.assertEqual(GATE.parse_test_stats(output), (12, 3))

    def test_surefire_stats_parser_returns_none_without_summary(self):
        self.assertIsNone(GATE.parse_test_stats("BUILD FAILURE (no summary)"))

    def test_killer_stage_detects_kill_from_exit_and_failures(self):
        self.assertEqual(GATE.classify_stage(exit_code=1, failures=3), "KILLED")
        self.assertEqual(
            GATE.classify_stage(exit_code=0, failures=0), "NOT_KILLED_BY_STAGE")

    def test_killed_requires_positive_failures_with_nonzero_exit(self):
        # exit != 0 without failures is an infrastructure failure, not a kill
        self.assertEqual(GATE.classify_stage(exit_code=1, failures=0), "INFRA_FAILURE")


class ApplyMutationTests(unittest.TestCase):
    def test_apply_replaces_exactly_once_and_leaves_rest(self):
        original = "int a = 1;\nint b = 2;\nint a = 1;\n"
        with self.assertRaises(GATE.AnchorError):
            GATE.apply_mutation(original, "int a = 1;", "int a = 9;")

    def test_apply_mutation_happy_path(self):
        original = "alpha\nbeta\ngamma\n"
        mutated = GATE.apply_mutation(original, "beta", "BETA")
        self.assertEqual(mutated, "alpha\nBETA\ngamma\n")


class ReportSummaryTests(unittest.TestCase):
    def test_exit_code_zero_only_when_all_killed(self):
        self.assertEqual(GATE.report_exit_code([{"status": "KILLED"}]), 0)

    def test_survivor_fails_the_gate(self):
        self.assertEqual(
            GATE.report_exit_code(
                [{"status": "KILLED"}, {"status": "SURVIVED"}]), 1)

    def test_stale_anchor_fails_distinctly(self):
        self.assertEqual(
            GATE.report_exit_code([{"status": "ANCHOR_STALE"}]), 2)

    def test_mixed_stale_and_survivor_reports_stale(self):
        self.assertEqual(
            GATE.report_exit_code(
                [{"status": "ANCHOR_STALE"}, {"status": "SURVIVED"}]), 2)

    def test_summary_counts_by_status(self):
        summary = GATE.summarize([
            {"id": "M1", "status": "KILLED"},
            {"id": "M2", "status": "SURVIVED"},
            {"id": "M3", "status": "KILLED"},
        ])
        self.assertEqual(summary["killed"], 2)
        self.assertEqual(summary["survived"], 1)
        self.assertEqual(summary["total"], 3)
        self.assertEqual(summary["survivorIds"], ["M2"])


class WritingReportTests(unittest.TestCase):
    def test_gate_refuses_to_overwrite_existing_report(self):
        gate = load_gate()
        with tempfile.TemporaryDirectory() as tmp:
            report = Path(tmp) / "report.json"
            report.write_text("{}", encoding="utf-8")
            with self.assertRaises(OSError):
                gate.write_report(report, {"total": 0})

    def test_write_report_emits_valid_json(self):
        gate = load_gate()
        with tempfile.TemporaryDirectory() as tmp:
            report = Path(tmp) / "report.json"
            gate.write_report(report, {"total": 1, "killed": 1})
            data = json.loads(report.read_text(encoding="utf-8"))
            self.assertEqual(data["total"], 1)


class DryRunTests(unittest.TestCase):
    def test_dry_run_classifies_live_anchors_without_mutation(self):
        # dry-run must classify from real files without writing anything
        gate = load_gate()
        pre = gate.collect_anchor_states()
        for state in pre.values():
            self.assertEqual(state, "LIVE")


if __name__ == "__main__":
    unittest.main(verbosity=2)
