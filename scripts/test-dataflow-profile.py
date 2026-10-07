#!/usr/bin/env python3
"""Synthetic, fixed-oracle tests for compare-dataflow-profile.py (stdlib only).

Run: python3 -B scripts/test-dataflow-profile.py
All profile data and CLI outputs live in test-owned TemporaryDirectory trees.
No benchmark directory is read, and no wall-clock performance threshold is used.
"""

import copy
import hashlib
import importlib.util
import json
import math
import os
from pathlib import Path
import random
import subprocess
import sys
import tempfile
import unittest


# Loading the sibling module must not create a repository __pycache__ directory.
sys.dont_write_bytecode = True
SCRIPT = Path(__file__).resolve().with_name("compare-dataflow-profile.py")
SPEC = importlib.util.spec_from_file_location("dataflow_profile_comparison", str(SCRIPT))
comparison = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(comparison)

SCENARIOS = ("FILELESS", "FILES_V2", "STORE_V3", "ONLINE_V3", "WIDE_V2")
CAPTURES = ("OFF", "TRUNCATED", "FULL")
FACTORS = {"FILELESS": 0, "FILES_V2": 1, "STORE_V3": 2, "ONLINE_V3": 2, "WIDE_V2": 0}
HASH_FIELDS = ("stateSha256", "captureSha256", "actionsSha256")


def record(scenario="FILELESS", capture="OFF", tasks=7, trial=0, **overrides):
    result = {
        "scenario": scenario, "capture": capture, "tasks": tasks, "trial": trial,
        "elapsedNanos": 100, "threadAllocatedBytes": 1000,
        "heapBeforeBytes": 2000, "heapPoolPeakSumBytes": 3000,
        "admittedCopies": FACTORS[scenario] * tasks,
        "javaVersion": "17.0.13", "maxHeapBytes": 536870912,
    }
    for field in HASH_FIELDS:
        text = "{}|{}|{}|{}".format(scenario, capture, tasks, field)
        result[field] = hashlib.sha256(text.encode("ascii")).hexdigest()
    result.update(overrides)
    return result


def series(elapsed, allocated=None, **case):
    if allocated is None:
        allocated = [1000] * len(elapsed)
    return [record(trial=index, elapsedNanos=value, threadAllocatedBytes=allocated[index], **case)
            for index, value in enumerate(elapsed)]


def write_rows(path, rows):
    path.write_text("".join(json.dumps(row, allow_nan=False) + "\n" for row in rows), encoding="utf-8")


class ProfileComparisonTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(prefix="dataflow-profile-tests-")
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.profiles = self.root / "profiles"
        self.profiles.mkdir()

    def write_pair(self, before, after, directory=None):
        directory = self.profiles if directory is None else directory
        for side, rows in (("before", before), ("after", after)):
            groups = {}
            for row in rows:
                groups.setdefault((row["scenario"], row["capture"]), []).append(row)
            for (scenario, capture), grouped in groups.items():
                write_rows(directory / "{}-{}-{}.jsonl".format(side, scenario, capture), grouped)
        return directory

    def cli(self, directory=None, output_json=None, output_markdown=None):
        directory = self.profiles if directory is None else directory
        output_json = self.root / "comparison.json" if output_json is None else output_json
        command = [sys.executable, "-B", str(SCRIPT), str(directory), str(output_json)]
        if output_markdown is not None:
            command.append(str(output_markdown))
        environment = dict(os.environ, PYTHONDONTWRITEBYTECODE="1")
        return subprocess.run(command, stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                              universal_newlines=True, env=environment, timeout=30)

    def assert_invalid(self, before, after, message=None):
        if message is None:
            with self.assertRaises(comparison.ComparisonError):
                comparison.compare_records(before, after)
        else:
            with self.assertRaisesRegex(comparison.ComparisonError, message):
                comparison.compare_records(before, after)

    def assert_cli_failure(self, result):
        self.assertNotEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertNotIn("PASS", result.stdout)
        self.assertNotIn("PASS", result.stderr)
        self.assertIn("ERROR", result.stderr)

    def test_fixed_odd_median_min_max_and_ratio_oracles(self):
        before = series([9, 1, 5], [100, 300, 200])
        after = series([3, 1, 2], [25, 75, 50])
        for rows, heaps, peaks in ((before, [2, 6, 4], [10, 40, 20]),
                                   (after, [1, 3, 2], [5, 20, 10])):
            for row, heap, peak in zip(rows, heaps, peaks):
                row["heapBeforeBytes"], row["heapPoolPeakSumBytes"] = heap, peak
        summary = comparison.compare_records(before, after)
        case = summary["cases"][0]
        self.assertEqual(summary["schema"], "workflowsim-dataflow-profile-comparison-v1")
        self.assertEqual(summary["status"], "PASS")
        self.assertEqual(summary["sampleCounts"], {"before": 3, "after": 3})
        self.assertEqual(case["sampleCounts"], {"before": 3, "after": 3})
        self.assertEqual(case["trials"], [0, 1, 2])
        self.assertEqual(case["before"]["elapsedNanos"], {"median": 5, "min": 1, "max": 9})
        self.assertEqual(case["after"]["elapsedNanos"], {"median": 2, "min": 1, "max": 3})
        self.assertEqual(case["before"]["threadAllocatedBytes"], {"median": 200, "min": 100, "max": 300})
        self.assertEqual(case["after"]["threadAllocatedBytes"], {"median": 50, "min": 25, "max": 75})
        self.assertEqual(case["before"]["heapBeforeBytes"]["median"], 4)
        self.assertEqual(case["after"]["heapBeforeBytes"]["median"], 2)
        self.assertEqual(case["before"]["heapPoolPeakSumBytes"]["median"], 20)
        self.assertEqual(case["after"]["heapPoolPeakSumBytes"]["median"], 10)
        self.assertEqual(case["timingSpeedup"], 2.5)
        self.assertEqual(case["allocationReductionRatio"], 4.0)
        self.assertEqual(case["allocationReductionLabel"], "finite")
        for field in HASH_FIELDS:
            self.assertEqual(case[field], before[0][field])

    def test_fixed_even_half_integer_medians_and_ratios(self):
        before = series([21, 10, 20, 11], [201, 100, 200, 101])
        after = series([5, 2, 4, 3], [21, 10, 20, 11])
        case = comparison.compare_records(before, after)["cases"][0]
        self.assertEqual(case["before"]["elapsedNanos"], {"median": 15.5, "min": 10, "max": 21})
        self.assertEqual(case["after"]["elapsedNanos"], {"median": 3.5, "min": 2, "max": 5})
        self.assertEqual(case["before"]["threadAllocatedBytes"]["median"], 150.5)
        self.assertEqual(case["after"]["threadAllocatedBytes"]["median"], 15.5)
        self.assertEqual(case["timingSpeedup"], 31 / 7)
        self.assertEqual(case["allocationReductionRatio"], 301 / 31)

    def test_large_integer_identities_counts_and_medians_are_exact(self):
        tasks, elapsed = 2 ** 53 + 1, 2 ** 60
        before = series([elapsed, elapsed + 2], scenario="STORE_V3", tasks=tasks,
                        maxHeapBytes=2 ** 63 - 1)
        summary = comparison.compare_records(before, copy.deepcopy(before))
        case = summary["cases"][0]
        self.assertEqual(case["tasks"], 9007199254740993)
        self.assertEqual(case["admittedCopies"], 18014398509481986)
        self.assertEqual(summary["maxHeapBytes"], 9223372036854775807)
        self.assertIs(type(case["before"]["elapsedNanos"]["median"]), int)
        self.assertEqual(case["before"]["elapsedNanos"]["median"], 1152921504606846977)
        self.assertEqual(case["timingSpeedup"], 1.0)
        different = series([elapsed, elapsed + 2], scenario="STORE_V3", tasks=tasks + 1,
                           maxHeapBytes=2 ** 63 - 1)
        self.assert_invalid(before, different, "unmatched cases")
        different = copy.deepcopy(before)
        for row in different:
            row["maxHeapBytes"] -= 1
        self.assert_invalid(before, different, "maxHeapBytes mismatch")

    def test_known_same_signature_matrix_and_sorted_cases(self):
        before, after, expected_keys = [], [], []
        for scenario in SCENARIOS:
            for capture in CAPTURES:
                for tasks in (2, 11):
                    expected_keys.append((scenario, capture, tasks))
                    case = {"scenario": scenario, "capture": capture, "tasks": tasks}
                    before.extend(series([100, 300, 200], [200, 600, 400], **case))
                    after.extend(series([50, 150, 100], [100, 300, 200], **case))
        random.Random(1729).shuffle(before)
        random.Random(85846).shuffle(after)
        self.write_pair(before, after)
        summary = comparison.compare_profiles(self.profiles)
        self.assertEqual(summary["caseCount"], 30)
        self.assertEqual(summary["sampleCounts"], {"before": 90, "after": 90})
        self.assertEqual([(case["scenario"], case["capture"], case["tasks"])
                          for case in summary["cases"]], sorted(expected_keys))
        for case in summary["cases"]:
            self.assertEqual(case["timingSpeedup"], 2.0)
            self.assertEqual(case["allocationReductionRatio"], 2.0)
            self.assertEqual(case["admittedCopies"], FACTORS[case["scenario"]] * case["tasks"])
        self.assertEqual(summary, comparison.compare_records(before, after))

    def test_summary_is_detached_finite_json_and_does_not_mutate_inputs(self):
        before, after = series([1, 2]), series([2, 4])
        frozen = copy.deepcopy((before, after))
        summary = comparison.compare_records(iter(before), iter(after))
        self.assertEqual((before, after), frozen)
        decoded = json.loads(json.dumps(summary, allow_nan=False))
        self.assertEqual(summary, decoded)
        before[0]["stateSha256"] = "f" * 64
        before[0]["elapsedNanos"] = 999
        summary["cases"][0]["trials"].append(900)
        summary["notes"].append("test-owned change")
        self.assertEqual(summary["cases"][0]["stateSha256"], frozen[0][0]["stateSha256"])
        self.assertEqual(summary["cases"][0]["before"]["elapsedNanos"]["median"], 1.5)
        fresh = comparison.compare_records(*frozen)
        self.assertEqual(fresh["cases"][0]["trials"], [0, 1])
        self.assertNotIn("test-owned change", fresh["notes"])

    def test_raw_null_allocation_and_ignored_probe_files(self):
        rows = series([10, 20], [None, None])
        self.write_pair(rows, rows)
        text = (self.profiles / "before-FILELESS-OFF.jsonl").read_text(encoding="utf-8")
        self.assertIn('"threadAllocatedBytes": null', text)
        for filename in ("before-probe.log", "after-FILELESS-OFF.log", "replica-only.jsonl",
                         "before-replica-only.jsonl", "after-replica_only.jsonl", "unrelated.jsonl"):
            (self.profiles / filename).write_text("not profile JSON at all\n", encoding="utf-8")
        summary = comparison.compare_profiles(self.profiles)
        self.assertFalse(summary["threadAllocationAvailable"])
        case = summary["cases"][0]
        self.assertIsNone(case["before"]["threadAllocatedBytes"])
        self.assertIsNone(case["after"]["threadAllocatedBytes"])
        self.assertIsNone(case["allocationReductionRatio"])
        self.assertEqual(case["allocationReductionLabel"], "unavailable")
        self.assertIn("| n/a | n/a |", comparison.render_markdown(summary))

    def test_all_zero_denominator_and_zero_baseline_allocation_ratios(self):
        for baseline, candidate, ratio, label in (
                (100, 0, None, "after-zero"), (0, 0, None, "both-zero"),
                (0, 100, 0.0, "zero-baseline"), (100, 25, 4.0, "finite")):
            with self.subTest(baseline=baseline, candidate=candidate):
                summary = comparison.compare_records(
                    [record(threadAllocatedBytes=baseline)], [record(threadAllocatedBytes=candidate)])
                case = summary["cases"][0]
                self.assertEqual(case["allocationReductionRatio"], ratio)
                self.assertEqual(case["allocationReductionLabel"], label)
                json.dumps(summary, allow_nan=False)
        # Denominator handling is based on medians, not just one zero sample.
        case = comparison.compare_records(series([10, 20], [100, 100]),
                                          series([10, 20], [0, 1]))["cases"][0]
        self.assertEqual(case["allocationReductionRatio"], 200.0)

    def test_slower_candidate_still_passes_without_performance_threshold(self):
        summary = comparison.compare_records([record(elapsedNanos=1, threadAllocatedBytes=1)],
                                             [record(elapsedNanos=1000000, threadAllocatedBytes=1000000)])
        self.assertEqual(summary["status"], "PASS")
        self.assertEqual(summary["cases"][0]["timingSpeedup"], 0.000001)
        self.assertEqual(summary["cases"][0]["allocationReductionRatio"], 0.000001)

    def test_empty_and_missing_cases_rejected_without_guessing(self):
        self.assert_invalid([], [])
        self.assert_invalid([record()], [])
        self.assert_invalid([], [record()])
        for different in (record(tasks=8), record(capture="FULL"), record(scenario="WIDE_V2")):
            self.assert_invalid([record()], [different], "unmatched cases")
            self.assert_invalid([record(), different], [record()], "unmatched cases")
        for invalid in (None, "records", record(), 1):
            self.assert_invalid(invalid, [record()])
        with self.assertRaises(comparison.ComparisonError):
            comparison.compare_profiles(self.profiles)

    def test_duplicate_missing_noncontiguous_and_nonzero_start_trials(self):
        rows = series([10, 20, 30])
        self.assert_invalid(rows + [rows[0]], rows, "duplicate trial")
        self.assert_invalid(rows, rows + [rows[0]], "duplicate trial")
        self.assert_invalid(rows, rows[:2], "unmatched trials")
        self.assert_invalid(rows[:2], rows, "unmatched trials")
        for incomplete in ([rows[0], rows[2]], [rows[1]], [rows[1], rows[2]]):
            self.assert_invalid(incomplete, incomplete, "missing trials")
        self.write_pair(rows, rows)
        write_rows(self.profiles / "before-shard1.jsonl", [rows[0]])
        with self.assertRaisesRegex(comparison.ComparisonError, "duplicate trial"):
            comparison.compare_profiles(self.profiles)

    def test_strict_integer_types_and_nonnegative_or_positive_ranges(self):
        integer_fields = ("tasks", "trial", "elapsedNanos", "threadAllocatedBytes", "heapBeforeBytes",
                          "heapPoolPeakSumBytes", "admittedCopies", "maxHeapBytes")
        for field in integer_fields:
            for bad in (True, False, 1.0, "1", float("nan"), float("inf"), -1):
                with self.subTest(field=field, bad=bad):
                    row = record()
                    row[field] = bad
                    self.assert_invalid([row], [row])
            if field != "threadAllocatedBytes":
                row = record()
                row[field] = None
                self.assert_invalid([row], [row])
        for field in ("tasks", "elapsedNanos", "maxHeapBytes"):
            row = record()
            row[field] = 0
            self.assert_invalid([row], [row])
        zeros = record(threadAllocatedBytes=0, heapBeforeBytes=0, heapPoolPeakSumBytes=0)
        self.assertEqual(comparison.compare_records([zeros], [zeros])["status"], "PASS")

    def test_allowed_enums_lowercase_sha_and_exact_record_fields(self):
        for field, invalids in (("scenario", ("fileless", "FILES_V3", "", None, 1)),
                                ("capture", ("off", "PARTIAL", "", None, 1)),
                                ("javaVersion", ("", "  ", None, 17, False))):
            for bad in invalids:
                row = record()
                row[field] = bad
                self.assert_invalid([row], [row])
        for field in HASH_FIELDS:
            for bad in ("a" * 63, "a" * 65, "A" * 64, "g" * 64, "a" * 63 + "\n", 1, None):
                with self.subTest(field=field, bad=bad):
                    row = record()
                    row[field] = bad
                    self.assert_invalid([row], [row])
        for field in record():
            row = record()
            row.pop(field)
            self.assert_invalid([row], [row], "missing fields")
        row = record(unexpected=1)
        self.assert_invalid([row], [row], "unexpected fields")
        row = record()
        row[1] = "not a string key"
        self.assert_invalid([row], [row], "keys must be strings")
        for row in (None, [], "not an object", 1):
            self.assert_invalid([row], [row], "JSON object")

    def test_expected_admitted_copy_contract_not_just_before_after_equality(self):
        for scenario, expected in (("FILELESS", 0), ("WIDE_V2", 0), ("FILES_V2", 7),
                                   ("STORE_V3", 14), ("ONLINE_V3", 14)):
            for capture in CAPTURES:
                with self.subTest(scenario=scenario, capture=capture):
                    row = record(scenario=scenario, capture=capture)
                    self.assertEqual(comparison.compare_records([row], [row])["cases"][0]["admittedCopies"], expected)
                    row["admittedCopies"] = expected + 1
                    self.assert_invalid([row], [row], "admittedCopies.*expected")

    def test_each_signature_must_be_one_exact_triple_within_and_across_sides(self):
        for field in HASH_FIELDS:
            for location in ("before-one", "after-one", "after-all"):
                with self.subTest(field=field, location=location):
                    before, after = series([10, 20]), series([5, 10])
                    target = before if location.startswith("before") else after
                    changed = target if location.endswith("all") else [target[1]]
                    for row in changed:
                        original = row[field]
                        row[field] = ("0" if original[0] != "0" else "1") + original[1:]
                    self.assert_invalid(before, after, "semantic signature drift")

    def test_jvm_and_heap_must_match_every_record_and_case_exactly(self):
        for field, value in (("javaVersion", "17.0.14"), ("maxHeapBytes", 536870913)):
            before, after = series([10, 20]), series([5, 10])
            after[1][field] = value
            self.assert_invalid(before, after, "javaVersion/maxHeapBytes mismatch")
            before, after = series([10, 20]), series([5, 10])
            before[1][field] = value
            self.assert_invalid(before, after, "javaVersion/maxHeapBytes mismatch")
            rows = [record(), record(scenario="WIDE_V2")]
            rows[1][field] = value
            self.assert_invalid(rows, copy.deepcopy(rows), "javaVersion/maxHeapBytes mismatch")

    def test_allocation_availability_is_all_null_or_all_available(self):
        self.assert_invalid(series([10, 20], [None, 1]), series([5, 10], [None, 1]), "all null")
        self.assert_invalid(series([10, 20], [None, None]), series([5, 10], [1, 1]), "all null")
        rows = [record(), record(scenario="WIDE_V2", threadAllocatedBytes=None)]
        self.assert_invalid(rows, rows, "all null")

    def test_strict_json_duplicate_keys_nonfinite_and_invalid_shapes(self):
        valid = json.dumps(record())
        variants = [
            (valid.replace('"trial": 0', '"trial": 0, "trial": 0'), "duplicate JSON key"),
            (valid[:-1] + ', "extra": {"x": 1, "x": 1}}', "duplicate JSON key"),
            ("[]", "JSON object"), ("null", "JSON object"), ("{", None),
            (valid + valid, None), (valid.replace('"trial": 0', '"trial": 0.0'), "integer"),
            (valid.replace('"trial": 0', '"trial": 0e0'), "integer"),
        ]
        for token in ("NaN", "Infinity", "-Infinity", "1e9999", "-1e9999"):
            variants.append((valid.replace('"elapsedNanos": 100', '"elapsedNanos": ' + token), "nonfinite"))
        for index, (raw, message) in enumerate(variants):
            with self.subTest(index=index):
                directory = self.root / "raw-{}".format(index)
                directory.mkdir()
                (directory / "before-FILELESS-OFF.jsonl").write_text(raw + "\n", encoding="utf-8")
                write_rows(directory / "after-FILELESS-OFF.jsonl", [record()])
                with self.assertRaisesRegex(comparison.ComparisonError, message or ".+"):
                    comparison.compare_profiles(directory)

    def test_filename_case_mismatches_are_not_silently_accepted(self):
        cases = (
            ("before-FILELESS-OFF.jsonl", record(scenario="WIDE_V2")),
            ("before-FILELESS-FULL.jsonl", record()),
            ("before-fileless-OFF.jsonl", record()),
            ("before-FILELESS-BOGUS.jsonl", record()),
            ("after-STORE_V3-OFF.jsonl", record(scenario="ONLINE_V3")),
        )
        for index, (name, row) in enumerate(cases):
            with self.subTest(filename=name):
                directory = self.root / "name-{}".format(index)
                directory.mkdir()
                write_rows(directory / name, [row])
                with self.assertRaisesRegex(comparison.ComparisonError, "filename"):
                    comparison.compare_profiles(directory)

    def test_aggregate_filenames_pair_by_record_case_not_guessed_filename(self):
        rows = [record(), record(scenario="ONLINE_V3", capture="FULL", tasks=3)]
        write_rows(self.profiles / "before.jsonl", rows)
        self.write_pair([], rows)
        self.assertEqual(comparison.compare_profiles(self.profiles)["caseCount"], 2)

    def test_empty_files_and_invalid_directories_are_rejected(self):
        self.write_pair([record()], [record()])
        (self.profiles / "before-shard1.jsonl").write_text(" \n\n", encoding="utf-8")
        with self.assertRaisesRegex(comparison.ComparisonError, "empty profile file"):
            comparison.compare_profiles(self.profiles)
        for path in (self.root / "does-not-exist", self.profiles / "before-FILELESS-OFF.jsonl"):
            with self.assertRaisesRegex(comparison.ComparisonError, "not a directory"):
                comparison.compare_profiles(path)
        directory = self.root / "not-file-input"
        directory.mkdir()
        (directory / "before-FILELESS-OFF.jsonl").mkdir()
        with self.assertRaisesRegex(comparison.ComparisonError, "not a regular file"):
            comparison.compare_profiles(directory)

    def test_unrepresentable_half_medians_and_nonfinite_or_underflowed_ratios_rejected(self):
        rows = series([2 ** 54, 2 ** 54 + 1])
        self.assert_invalid(rows, rows, "half-integer median")
        self.assert_invalid([record(elapsedNanos=10 ** 400)], [record(elapsedNanos=1)], "ratio")
        self.assert_invalid([record(elapsedNanos=1)], [record(elapsedNanos=10 ** 400)], "ratio")
        rows = series([2 ** 52 - 1, 2 ** 52])
        case = comparison.compare_records(rows, rows)["cases"][0]
        self.assertEqual(case["before"]["elapsedNanos"]["median"], 4503599627370495.5)
        self.assertTrue(math.isfinite(case["timingSpeedup"]))

    def test_cli_success_json_markdown_fixed_units_and_interpretation(self):
        before = series([1000000, 3000000, 2000000], [2 ** 20, 3 * 2 ** 20, 2 * 2 ** 20])
        after = series([500000, 1500000, 1000000], [2 ** 19, 3 * 2 ** 19, 2 ** 20])
        self.write_pair(before, after)
        json_path, markdown_path = self.root / "comparison.json", self.root / "comparison.md"
        result = self.cli(output_markdown=markdown_path)
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn("PASS: 1 cases, 3 before / 3 after samples", result.stdout)
        self.assertEqual(json.loads(json_path.read_text(encoding="utf-8")), comparison.compare_records(before, after))
        markdown = markdown_path.read_text(encoding="utf-8")
        self.assertIn("| FILELESS | OFF | 7 | 2.000000 | 1.000000 | 2x | 2.000000 | 1.000000 |", markdown)
        for phrase in ("cumulative allocation by the current thread", "not live RAM", "not RSS",
                       "controller-only", "excludes plan construction", "post-run semantic hashing",
                       "WIDE_V2 retains active-work costs", "not statistical proof", "universal accelerator",
                       "not passing a performance acceptance threshold"):
            self.assertIn(phrase, markdown)

    def test_cli_optional_markdown_and_slower_candidate_success(self):
        self.write_pair([record(elapsedNanos=1)], [record(elapsedNanos=100)])
        result = self.cli()
        self.assertEqual(result.returncode, 0, result.stderr)
        summary = json.loads((self.root / "comparison.json").read_text(encoding="utf-8"))
        self.assertEqual(summary["cases"][0]["timingSpeedup"], 0.01)
        self.assertFalse((self.root / "comparison.md").exists())

    def test_cli_mismatches_emit_neither_pass_nor_output(self):
        for mode in ("signature", "missing-case", "missing-trial", "duplicate", "jvm", "typed-integer", "nan"):
            with self.subTest(mode=mode):
                directory = self.root / mode
                directory.mkdir()
                before, after = series([10, 20]), series([5, 10])
                if mode == "signature":
                    after[0]["actionsSha256"] = "f" * 64
                elif mode == "missing-case":
                    after = []
                elif mode == "missing-trial":
                    after = after[:1]
                elif mode == "duplicate":
                    after.append(copy.deepcopy(after[0]))
                elif mode == "jvm":
                    after[0]["javaVersion"] = "different"
                elif mode == "typed-integer":
                    after[0]["elapsedNanos"] = True
                self.write_pair(before, after, directory)
                if mode == "nan":
                    raw = json.dumps(record()).replace('"elapsedNanos": 100', '"elapsedNanos": NaN')
                    (directory / "after-FILELESS-OFF.jsonl").write_text(raw + "\n", encoding="utf-8")
                output_json, output_md = directory / "new.json", directory / "new.md"
                self.assert_cli_failure(self.cli(directory, output_json, output_md))
                self.assertFalse(output_json.exists())
                self.assertFalse(output_md.exists())

    def test_cli_exclusive_create_rejects_existing_alias_and_invalid_paths(self):
        self.write_pair([record()], [record()])
        for mode in ("json-exists", "markdown-exists", "both-exist", "same-path", "parent-missing",
                     "directory-exists", "input-exists", "broken-symlink"):
            with self.subTest(mode=mode):
                directory = self.root / mode
                directory.mkdir()
                output_json, output_md = directory / "new.json", directory / "new.md"
                preserved = {}
                if mode in ("json-exists", "both-exist"):
                    output_json.write_text("JSON SENTINEL\n", encoding="utf-8")
                    preserved[output_json] = "JSON SENTINEL\n"
                if mode in ("markdown-exists", "both-exist"):
                    output_md.write_text("MARKDOWN SENTINEL\n", encoding="utf-8")
                    preserved[output_md] = "MARKDOWN SENTINEL\n"
                if mode == "same-path":
                    output_md = output_json
                if mode == "parent-missing":
                    output_md = directory / "missing" / "new.md"
                if mode == "directory-exists":
                    output_json.mkdir()
                if mode == "input-exists":
                    output_json = self.profiles / "before-FILELESS-OFF.jsonl"
                    preserved[output_json] = output_json.read_text(encoding="utf-8")
                if mode == "broken-symlink":
                    output_json.symlink_to(directory / "absent-target")
                self.assert_cli_failure(self.cli(output_json=output_json, output_markdown=output_md))
                for path, expected in preserved.items():
                    self.assertEqual(path.read_text(encoding="utf-8"), expected)
                for path in (output_json, output_md):
                    if path not in preserved and not path.is_symlink() and not path.is_dir():
                        self.assertFalse(path.exists())
                if mode == "broken-symlink":
                    self.assertTrue(output_json.is_symlink())
                    self.assertFalse((directory / "absent-target").exists())


if __name__ == "__main__":
    unittest.main(verbosity=2)
