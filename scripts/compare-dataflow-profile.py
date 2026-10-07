#!/usr/bin/env python3
"""Compare DataflowStateProfile JSONL without a performance acceptance threshold.

Public APIs (no writes):
    compare_profiles(directory) -> detached, JSON-serializable summary
    compare_records(before_records, after_records) -> the same summary
Both raise ComparisonError on invalid or non-comparable input.

Input files are before-SCENARIO-CAPTURE.jsonl / after-SCENARIO-CAPTURE.jsonl.
Aggregate before.jsonl / after.jsonl and single-token shard names such as
before-shard1.jsonl are also accepted; case identity always comes from records.
A SCENARIO-CAPTURE filename must agree exactly with every record it contains.
Other files, including .log files and replica-only probes, are ignored.

Integer identities never pass through floating point. Integer medians remain
integers; half-integer medians must be exactly representable as finite floats.
Ratios are finite floating-point descriptions, not scientific identity checks.
Requires only the Python 3.8+ standard library.
"""

import argparse
from collections.abc import Mapping
from contextlib import ExitStack
from fractions import Fraction
import json
import math
import os
from pathlib import Path
import re
import sys
from typing import Any, Dict, Iterable, List, Optional, Tuple, Union


SCHEMA = "workflowsim-dataflow-profile-comparison-v1"
SCENARIOS = ("FILELESS", "FILES_V2", "STORE_V3", "ONLINE_V3", "WIDE_V2")
CAPTURES = ("OFF", "TRUNCATED", "FULL")
COPY_FACTORS = {
    "FILELESS": 0, "FILES_V2": 1, "STORE_V3": 2, "ONLINE_V3": 2, "WIDE_V2": 0,
}
SIGNATURE_FIELDS = ("stateSha256", "captureSha256", "actionsSha256")
REQUIRED_FIELDS = frozenset((
    "scenario", "tasks", "capture", "trial", "elapsedNanos",
    "threadAllocatedBytes", "heapBeforeBytes", "heapPoolPeakSumBytes",
    "admittedCopies", "stateSha256", "captureSha256", "actionsSha256",
    "javaVersion", "maxHeapBytes",
))
INTEGER_MINIMA = {
    "tasks": 1, "trial": 0, "elapsedNanos": 1, "heapBeforeBytes": 0,
    "heapPoolPeakSumBytes": 0, "admittedCopies": 0, "maxHeapBytes": 1,
}
NOTES = (
    "PASS means exact case, trial, JVM, admitted-copy and semantic-signature "
    "agreement, not passing a performance acceptance threshold.",
    "The controller-only transition interval excludes plan construction and "
    "post-run semantic hashing.",
    "threadAllocatedBytes is cumulative allocation by the current thread, "
    "not live RAM; MiB means 2^20 bytes.",
    "heapPoolPeakSumBytes is the sum of individual heap-pool peaks, not RSS "
    "or a simultaneous whole-heap peak. heapBeforeBytes is the pre-interval heap sample.",
    "WIDE_V2 retains active-work costs while many jobs remain active; these "
    "measurements do not establish that every workload is linear.",
    "Fixed samples are not statistical proof of a universal accelerator. "
    "Matching JVM version and maximum heap does not control every environmental variable.",
    "Ratios divide the before median by the after median. Allocation ratios "
    "are null with an explicit label when allocation is unavailable or the "
    "after median is zero; no Infinity is emitted.",
)
Number = Union[int, float]
CaseKey = Tuple[str, str, int]


class ComparisonError(ValueError):
    """An input or output validation failure; no comparison can be certified."""


def _case_name(key: CaseKey) -> str:
    return "{}/{}/tasks={}".format(*key)


def _validate_record(record: Any, source: str) -> Dict[str, Any]:
    if not isinstance(record, Mapping):
        raise ComparisonError("{}: each record must be a JSON object".format(source))
    if any(type(key) is not str for key in record):
        raise ComparisonError("{}: record keys must be strings".format(source))
    missing = REQUIRED_FIELDS.difference(record)
    extra = set(record).difference(REQUIRED_FIELDS)
    if missing or extra:
        raise ComparisonError("{}: missing fields {}; unexpected fields {}".format(
            source, sorted(missing), sorted(extra)))
    for field, allowed in (("scenario", SCENARIOS), ("capture", CAPTURES)):
        if type(record[field]) is not str or record[field] not in allowed:
            raise ComparisonError("{}: invalid {} {!r}".format(source, field, record[field]))
    for field, minimum in INTEGER_MINIMA.items():
        value = record[field]
        if type(value) is not int or value < minimum:
            raise ComparisonError("{}: {} must be an integer >= {} (not bool/float)".format(
                source, field, minimum))
    allocation = record["threadAllocatedBytes"]
    if allocation is not None and (type(allocation) is not int or allocation < 0):
        raise ComparisonError("{}: threadAllocatedBytes must be a nonnegative integer or null".format(source))
    for field in SIGNATURE_FIELDS:
        value = record[field]
        if type(value) is not str or re.fullmatch(r"[0-9a-f]{64}", value) is None:
            raise ComparisonError("{}: {} must be 64 lowercase hexadecimal characters".format(source, field))
    version = record["javaVersion"]
    if type(version) is not str or not version.strip():
        raise ComparisonError("{}: javaVersion must be a nonempty string".format(source))
    expected = COPY_FACTORS[record["scenario"]] * record["tasks"]
    if record["admittedCopies"] != expected:
        raise ComparisonError("{}: admittedCopies {} != expected {} for {}/tasks={}".format(
            source, record["admittedCopies"], expected, record["scenario"], record["tasks"]))
    # All validated values are immutable scalars. Do not retain the caller's mapping.
    return {field: record[field] for field in REQUIRED_FIELDS}


def _index_records(records: Iterable[Mapping], side: str) -> Dict[CaseKey, Dict[int, Dict[str, Any]]]:
    if isinstance(records, (str, bytes, Mapping)):
        raise ComparisonError("{} must be an iterable of records".format(side))
    try:
        iterator = iter(records)
    except TypeError as exc:
        raise ComparisonError("{} must be an iterable of records".format(side)) from exc
    result = {}  # type: Dict[CaseKey, Dict[int, Dict[str, Any]]]
    for index, raw in enumerate(iterator, 1):
        row = _validate_record(raw, "{} record {}".format(side, index))
        key = (row["scenario"], row["capture"], row["tasks"])
        trials = result.setdefault(key, {})
        if row["trial"] in trials:
            raise ComparisonError("{}: duplicate trial {} for {}".format(side, row["trial"], _case_name(key)))
        trials[row["trial"]] = row
    if not result:
        raise ComparisonError("{} has no profile records".format(side))
    return result


def _median(values: List[int]) -> Number:
    ordered = sorted(values)
    middle = len(ordered) // 2
    if len(ordered) % 2:
        return ordered[middle]
    total = ordered[middle - 1] + ordered[middle]
    if total % 2 == 0:
        return total // 2
    exact = Fraction(total, 2)
    try:
        value = float(exact)
    except OverflowError as exc:
        raise ComparisonError("half-integer median is too large for a finite JSON number") from exc
    if not math.isfinite(value) or Fraction(value) != exact:
        raise ComparisonError("half-integer median is not exactly representable as a finite JSON number")
    return value


def _stats(values: List[int]) -> Dict[str, Number]:
    return {"median": _median(values), "min": min(values), "max": max(values)}


def _finite_ratio(before: Number, after: Number) -> float:
    exact = Fraction(before) / Fraction(after)
    try:
        value = float(exact)
    except OverflowError as exc:
        raise ComparisonError("ratio is too large for a finite JSON number") from exc
    if not math.isfinite(value) or (exact != 0 and value == 0):
        raise ComparisonError("ratio cannot be represented as a finite, non-underflowed JSON number")
    return value


def _side_summary(rows: List[Dict[str, Any]]) -> Dict[str, Any]:
    result = {field: _stats([row[field] for row in rows]) for field in (
        "elapsedNanos", "heapBeforeBytes", "heapPoolPeakSumBytes")}
    result["threadAllocatedBytes"] = (
        None if rows[0]["threadAllocatedBytes"] is None else
        _stats([row["threadAllocatedBytes"] for row in rows]))
    return result


def compare_records(before_records: Iterable[Mapping], after_records: Iterable[Mapping]) -> Dict[str, Any]:
    """Validate records and return a detached summary; never write or mutate input.

    Cases are (scenario, capture, tasks), sorted lexicographically in the result.
    Each case needs matching, contiguous, zero-based trials, as emitted by the
    fixture. JVM version, max heap and allocation availability must be uniform
    across both complete inputs. A semantic triple may vary between cases but
    must be identical across every before/after trial of any one case.
    """
    before = _index_records(before_records, "before")
    after = _index_records(after_records, "after")
    if set(before) != set(after):
        raise ComparisonError("unmatched cases: missing after {}; missing before {}".format(
            [_case_name(key) for key in sorted(set(before) - set(after))],
            [_case_name(key) for key in sorted(set(after) - set(before))]))

    environment = None
    allocation_available = None
    for side, indexed in (("before", before), ("after", after)):
        for key in sorted(indexed):
            for trial, row in sorted(indexed[key].items()):
                observed = (row["javaVersion"], row["maxHeapBytes"])
                if environment is None:
                    environment = observed
                if observed != environment:
                    raise ComparisonError("{} {} trial {}: javaVersion/maxHeapBytes mismatch".format(
                        side, _case_name(key), trial))
                available = row["threadAllocatedBytes"] is not None
                if allocation_available is None:
                    allocation_available = available
                if available != allocation_available:
                    raise ComparisonError("threadAllocatedBytes must be all null or all nonnegative integers across both inputs")

    cases = []
    for key in sorted(before):
        before_trials, after_trials = set(before[key]), set(after[key])
        if before_trials != after_trials:
            raise ComparisonError("{}: unmatched trials; missing after {}; missing before {}".format(
                _case_name(key), sorted(before_trials - after_trials), sorted(after_trials - before_trials)))
        trials = sorted(before_trials)
        if trials != list(range(len(trials))):
            raise ComparisonError("{}: missing trials; trials must be contiguous starting at zero".format(_case_name(key)))
        reference = before[key][trials[0]]
        for side, indexed in (("before", before), ("after", after)):
            for trial in trials:
                for field in SIGNATURE_FIELDS:
                    if indexed[key][trial][field] != reference[field]:
                        raise ComparisonError("{} {} trial {}: semantic signature drift in {}".format(
                            side, _case_name(key), trial, field))
        before_stats = _side_summary([before[key][trial] for trial in trials])
        after_stats = _side_summary([after[key][trial] for trial in trials])
        allocation_ratio = None
        allocation_label = "unavailable"
        if allocation_available:
            baseline = before_stats["threadAllocatedBytes"]["median"]
            candidate = after_stats["threadAllocatedBytes"]["median"]
            if candidate == 0:
                allocation_label = "after-zero" if baseline > 0 else "both-zero"
            else:
                allocation_ratio = _finite_ratio(baseline, candidate)
                allocation_label = "zero-baseline" if baseline == 0 else "finite"
        case = {
            "scenario": key[0], "capture": key[1], "tasks": key[2],
            "sampleCounts": {"before": len(trials), "after": len(trials)},
            "trials": trials,
            "admittedCopies": reference["admittedCopies"],
            "before": before_stats, "after": after_stats,
            "timingSpeedup": _finite_ratio(before_stats["elapsedNanos"]["median"],
                                          after_stats["elapsedNanos"]["median"]),
            "allocationReductionRatio": allocation_ratio,
            "allocationReductionLabel": allocation_label,
        }
        case.update({field: reference[field] for field in SIGNATURE_FIELDS})
        cases.append(case)
    return {
        "schema": SCHEMA, "status": "PASS", "caseCount": len(cases),
        "sampleCounts": {
            "before": sum(len(rows) for rows in before.values()),
            "after": sum(len(rows) for rows in after.values()),
        },
        "javaVersion": environment[0], "maxHeapBytes": environment[1],
        "threadAllocationAvailable": allocation_available,
        "cases": cases, "notes": list(NOTES),
    }


def _unique_object(pairs: List[Tuple[str, Any]]) -> Dict[str, Any]:
    result = {}
    for key, value in pairs:
        if key in result:
            raise ComparisonError("duplicate JSON key {!r}".format(key))
        result[key] = value
    return result


def _reject_constant(token: str) -> Any:
    raise ComparisonError("nonfinite JSON constant {!r}".format(token))


def _parse_float(token: str) -> float:
    value = float(token)
    if not math.isfinite(value):
        raise ComparisonError("nonfinite JSON number {!r}".format(token))
    return value


def _filename_case(label: Optional[str], name: str) -> Optional[Tuple[str, str]]:
    if label is None or "-" not in label:
        return None
    parts = label.split("-")
    if len(parts) != 2 or parts[0] not in SCENARIOS or parts[1] not in CAPTURES:
        raise ComparisonError("{}: expected a SCENARIO-CAPTURE filename with allowed case-sensitive names".format(name))
    return parts[0], parts[1]


def compare_profiles(profile_directory: Union[str, Path]) -> Dict[str, Any]:
    """Read a directory of completed JSONL profiles and compare their records.

    This does not wait for a running producer or repair/guess missing cases.
    Only call it after all profile producers have finished successfully.
    """
    directory = Path(profile_directory)
    if not directory.is_dir():
        raise ComparisonError("profile directory does not exist or is not a directory: {}".format(directory))
    records = {"before": [], "after": []}  # type: Dict[str, List[Dict[str, Any]]]
    for path in sorted(directory.iterdir(), key=lambda item: item.name):
        match = re.fullmatch(r"(before|after)(?:-(.+))?\.jsonl", path.name)
        if match is None or re.search(r"(?:^|[-_])replica(?:[-_]|$)", path.stem, re.IGNORECASE):
            continue
        if not path.is_file():
            raise ComparisonError("profile input is not a regular file: {}".format(path))
        side, label = match.groups()
        named_case = _filename_case(label, path.name)
        count = 0
        try:
            with path.open("r", encoding="utf-8") as handle:
                for line_number, line in enumerate(handle, 1):
                    if not line.strip():
                        continue
                    source = "{}:{}".format(path.name, line_number)
                    try:
                        raw = json.loads(line, object_pairs_hook=_unique_object,
                                         parse_constant=_reject_constant, parse_float=_parse_float)
                        row = _validate_record(raw, source)
                    except ValueError as exc:
                        raise ComparisonError("{}: {}".format(source, exc)) from exc
                    if named_case is not None and (row["scenario"], row["capture"]) != named_case:
                        raise ComparisonError("{}: filename case does not match record scenario/capture".format(source))
                    records[side].append(row)
                    count += 1
        except (OSError, UnicodeError) as exc:
            raise ComparisonError("cannot read {}: {}".format(path, exc)) from exc
        if count == 0:
            raise ComparisonError("{}: empty profile file".format(path.name))
    return compare_records(records["before"], records["after"])


def _scaled(value: Number, divisor: int) -> str:
    # Six decimal places, rounded half-up without converting large integers to float.
    exact = Fraction(value) / divisor
    scaled = (2 * exact.numerator * 1000000 + exact.denominator) // (2 * exact.denominator)
    return "{}.{:06d}".format(scaled // 1000000, scaled % 1000000)


def render_markdown(summary: Mapping) -> str:
    """Render an already validated comparison summary as a readable report."""
    lines = [
        "# Dataflow controller profile comparison", "",
        "Status: **{}** — exact validation only; no performance threshold.".format(summary["status"]),
        "",
        "Cases: {}. Samples: {} before / {} after.".format(
            summary["caseCount"], summary["sampleCounts"]["before"], summary["sampleCounts"]["after"]),
        "", "All table measurements are medians; speedup is before / after.", "",
        "| Scenario | Capture | Tasks | Before ms | After ms | Speedup | Before allocated MiB | After allocated MiB |",
        "| --- | --- | ---: | ---: | ---: | ---: | ---: | ---: |",
    ]
    for case in summary["cases"]:
        before, after = case["before"], case["after"]
        allocated = []
        for side in (before, after):
            stats = side["threadAllocatedBytes"]
            allocated.append("n/a" if stats is None else _scaled(stats["median"], 1 << 20))
        lines.append("| {} | {} | {} | {} | {} | {:.4g}x | {} | {} |".format(
            case["scenario"], case["capture"], case["tasks"],
            _scaled(before["elapsedNanos"]["median"], 1000000),
            _scaled(after["elapsedNanos"]["median"], 1000000), case["timingSpeedup"], *allocated))
    lines.extend(["", "## Interpretation and limits", ""])
    lines.extend("- " + note for note in summary["notes"])
    lines.extend(["", "Allocation labels: `after-zero` = positive baseline, zero after; "
                  "`both-zero` = both medians zero; `unavailable` = all raw allocations null; "
                  "`zero-baseline` = zero before and positive after (ratio 0). "
                  "`finite` denotes an ordinary finite ratio.", ""])
    return "\n".join(lines)


def _check_output_paths(paths: List[Path]) -> None:
    resolved = [path.resolve() for path in paths]
    if len(set(resolved)) != len(resolved):
        raise ComparisonError("JSON and Markdown outputs must be distinct paths")
    for path in paths:
        if os.path.lexists(str(path)):
            raise ComparisonError("output already exists; refusing to overwrite: {}".format(path))
        if not path.parent.is_dir():
            raise ComparisonError("output parent directory does not exist: {}".format(path.parent))


def main(argv: Optional[List[str]] = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("profile_directory", type=Path)
    parser.add_argument("output_json", type=Path, help="new JSON output; must not exist")
    parser.add_argument("output_markdown", type=Path, nargs="?", help="optional new Markdown output; must not exist")
    args = parser.parse_args(argv)
    paths = [args.output_json] + ([args.output_markdown] if args.output_markdown is not None else [])
    try:
        _check_output_paths(paths)
        summary = compare_profiles(args.profile_directory)
        payloads = [json.dumps(summary, indent=2, sort_keys=True, allow_nan=False) + "\n"]
        if args.output_markdown is not None:
            payloads.append(render_markdown(summary))
        # Finish all validation and rendering before creating any output. Exclusive
        # opens also prevent clobbering a file created after the preflight check.
        _check_output_paths(paths)
        with ExitStack() as stack:
            handles = [stack.enter_context(path.open("x", encoding="utf-8", newline="\n")) for path in paths]
            for handle, payload in zip(handles, payloads):
                handle.write(payload)
    except (ComparisonError, OSError, UnicodeError, OverflowError) as exc:
        print("ERROR: {}".format(exc), file=sys.stderr)
        return 1
    print("PASS: {} cases, {} before / {} after samples; wrote {}".format(
        summary["caseCount"], summary["sampleCounts"]["before"], summary["sampleCounts"]["after"],
        ", ".join(str(path) for path in paths)))
    return 0


if __name__ == "__main__":
    sys.exit(main())
