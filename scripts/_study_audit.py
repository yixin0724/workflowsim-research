"""Shared stdlib-only primitives for independent study audits (no Java imports)."""
from __future__ import annotations

import collections
import hashlib
import json
import math
from decimal import Decimal
from fractions import Fraction
from pathlib import Path
import xml.etree.ElementTree as ET

EXECUTION_SEMANTICS = "WORK_CONSERVING_TASK_EXECUTION_V2"
LONG_MAX = (1 << 63) - 1
PROTOCOLS = {
    "network-limited-r10-v3": ("r10", False),
    "network-limited-r10-v2": ("r10", True),
    "peft-comparison-r12-v2": ("r12", False),
    "peft-comparison-r12-v1": ("r12", True),
    "sensitivity-response-r13-v2": ("r13", False),
    "sensitivity-response-r13-v1": ("r13", True),
}
CLASSIC_INPUTS = [
    ("epigenomics-100", "epigenomics", "dax/epigenomics/n100/Epigenomics_100.dax", "374521746417b18133682de21b654b84c32acde6332462c0ce916c4ba12c7f36"),
    ("epigenomics-997", "epigenomics", "dax/epigenomics/n997/Epigenomics_997.dax", "2ed853db24126750a1bf427b5731d5fb18b6d31baf3d2088c14b310478bed628"),
    ("cybershake-100", "cybershake", "dax/cybershake/n100/CyberShake_100.dax", "183ac79c4ee80a6293adff71d8386e7538d31a9bd1b609b176d94b131b9fdb52"),
    ("cybershake-1000", "cybershake", "dax/cybershake/n1000/CyberShake_1000.dax", "4314ae0e6bb43c3f74818306b600151c0614b62184295438350837217f1df95b"),
    ("inspiral-100", "inspiral", "dax/inspiral/n100/Inspiral_100.dax", "64e2bc60893d65f8347c436986841741d7ced1bd05435f67081707d4346decfa"),
]
PAPER_INPUT = ("paper-10", "paper-fixture", "dax/heft/heft-paper-example.dax", "5e64adc375e0249dafed5cfaba75f63332d3793fe017e57ea6c8da68e9d86fbd")
NETWORK_BANDWIDTH = {"fat-tree-constrained": 0.125, "fat-tree-mid": 0.5, "fat-tree-wide": 1.25, "fat-tree-fast": 5.0}
MIPS_PATTERNS = {"HOMOGENEOUS": [1000], "HET_MILD": [1000, 500], "HET_STRONG": [2000, 1000, 500], "HET_EXTREME": [2000, 500]}
RUN_METRICS = {
    "makespanSeconds": "makespanSeconds",
    "logicalCompletionSeconds": "logicalTaskCompletionSeconds",
    "meanWaitingSeconds": "meanComputeTotalWaitingTimeSeconds",
    "p95WaitingSeconds": "p95ComputeTotalWaitingTimeSeconds",
    "meanVmUtilization": "meanVmModeledIntervalUtilization",
}


class CheckError(ValueError):
    """An invalid or unsupported input, never an implicit skipped check."""


def _pairs(pairs):
    result = {}
    for key, value in pairs:
        if key in result:
            raise CheckError("duplicate JSON member: " + key)
        result[key] = value
    return result


def _constant(value):
    raise CheckError("non-standard JSON numeric constant: " + value)


def decode_json(raw):
    try:
        return json.loads(raw, parse_float=Decimal, parse_constant=_constant, object_pairs_hook=_pairs)
    except (ValueError, UnicodeError) as error:
        raise CheckError(str(error)) from error


def read_json(path):
    return decode_json(Path(path).read_bytes())


def digest(path):
    h = hashlib.sha256()
    size = 0
    with Path(path).open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            h.update(chunk)
            size += len(chunk)
    return h.hexdigest(), size


def needed(obj, key):
    if not isinstance(obj, dict) or key not in obj:
        raise CheckError("missing required field: " + key)
    return obj[key]


def number(value, field="number", nonnegative=False, positive=False):
    if isinstance(value, bool) or not isinstance(value, (int, float, Decimal)):
        raise CheckError(field + " must be a JSON number")
    result = float(value)
    if not math.isfinite(result):
        raise CheckError(field + " must be finite")
    exact = Decimal(str(value))
    if (nonnegative and exact < 0) or (positive and exact <= 0):
        raise CheckError(field + " is outside its nonnegative/positive domain")
    return result


def integer(value, field="integer", nonnegative=False):
    number(value, field, nonnegative=nonnegative)
    exact = Decimal(str(value))
    if exact != exact.to_integral_value():
        raise CheckError(field + " must be an exact integer")
    return int(exact)


def java_round(value):
    """Math.round's nearest-integer/ties-toward-positive-infinity on the input binary64.

    Do not use floor(value + .5): that addition itself can round a just-below-half value up.
    """
    value = number(value, "Java round input")
    ratio = Fraction.from_float(value)
    result = (2 * ratio.numerator + ratio.denominator) // (2 * ratio.denominator)
    return max(-(1 << 63), min(LONG_MAX, result))


def ordered_sum(values):
    result = 0.0
    for value in values:
        result += float(value)
    return result


def mean(values):
    return math.fsum(values) / len(values) if values else 0.0


def median(values):
    values = sorted(values)
    if not values:
        return 0.0
    middle = len(values) // 2
    return values[middle] if len(values) % 2 else (values[middle - 1] + values[middle]) / 2.0


def percentile(values, q):
    return sorted(values)[max(1, math.ceil(q * len(values))) - 1] if values else 0.0


def union_length(intervals):
    if not intervals:
        return 0.0
    ordered = sorted(intervals)
    start, finish = ordered[0]
    total = 0.0
    for a, b in ordered[1:]:
        if a > finish:
            total += max(0.0, finish - start)
            start, finish = a, b
        else:
            finish = max(finish, b)
    return total + max(0.0, finish - start)


def exact_equal(a, b):
    if isinstance(a, bool) or isinstance(b, bool):
        return type(a) is type(b) and a == b
    if isinstance(a, dict) and isinstance(b, dict):
        return a.keys() == b.keys() and all(exact_equal(a[k], b[k]) for k in a)
    if isinstance(a, list) and isinstance(b, list):
        return len(a) == len(b) and all(exact_equal(x, y) for x, y in zip(a, b))
    numeric = (int, float, Decimal)
    if isinstance(a, numeric) and isinstance(b, numeric):
        return Decimal(str(a)) == Decimal(str(b))
    return type(a) is type(b) and a == b


def safe_json(value):
    if isinstance(value, Path):
        return str(value)
    if isinstance(value, Decimal):
        return str(value)
    if isinstance(value, Fraction):
        return {"numerator": value.numerator, "denominator": value.denominator, "decimal": float(value)}
    if isinstance(value, float) and not math.isfinite(value):
        return repr(value)
    if isinstance(value, dict):
        return {str(k): safe_json(v) for k, v in value.items()}
    if isinstance(value, (list, tuple, set)):
        return [safe_json(v) for v in value]
    return value


class Audit:
    def __init__(self, rel_tol=1e-10, abs_tol=1e-8, max_issues=200):
        self.rel_tol, self.abs_tol, self.max_issues = rel_tol, abs_tol, max_issues
        self.checks = 0
        self.issue_count = 0
        self.counts = collections.Counter()
        self.issues = []
        self.notes = []

    def ensure(self, ok, code, location="", actual=None, expected=None):
        self.checks += 1
        if not ok:
            self.issue_count += 1
            self.counts[code] += 1
            if len(self.issues) < self.max_issues:
                self.issues.append(safe_json({"code": code, "location": location, "actual": actual, "expected": expected}))
        return bool(ok)

    def problem(self, code, location="", actual=None, expected=None):
        return self.ensure(False, code, location, actual, expected)

    def close(self, actual, expected):
        if actual is None or expected is None:
            return actual is expected
        if isinstance(expected, bool):
            return isinstance(actual, bool) and actual == expected
        if isinstance(expected, str):
            return isinstance(actual, str) and actual == expected
        try:
            a, b = number(actual), number(expected)
            return math.isclose(a, b, rel_tol=self.rel_tol, abs_tol=self.abs_tol)
        except CheckError:
            return False

    def metric(self, metrics, key, expected, location="metrics"):
        if key not in metrics:
            self.problem("missing-required-metric", location + "/" + key)
            return
        if isinstance(expected, int) and not isinstance(expected, bool):
            try:
                matches = integer(metrics[key], key) == expected
            except CheckError:
                matches = False
        else:
            matches = self.close(metrics[key], expected)
        self.ensure(matches, "metric-mismatch", location + "/" + key, metrics[key], expected)

    def report(self):
        return {"status": "PASS" if not self.issue_count else "FAIL", "checks": self.checks,
                "issueCount": self.issue_count, "issueCounts": dict(self.counts), "issues": self.issues,
                "omittedIssueDetails": max(0, self.issue_count - len(self.issues)), "notes": self.notes}


def contained_file(root, relative):
    if not isinstance(relative, str) or not relative or Path(relative).is_absolute():
        raise CheckError("evidence references must be relative paths")
    root = Path(root).resolve()
    result = (root / relative).resolve()
    try:
        result.relative_to(root)
    except ValueError as error:
        raise CheckError("evidence path escapes its root: " + relative) from error
    if not result.is_file():
        raise CheckError("missing evidence file: " + str(result))
    return result


def study_root(path):
    path = Path(path).resolve()
    if path.name.endswith(".manifest.json") and path.parent.parent.name == "runs":
        return path.parent.parent.parent
    return path.parent


def protect_output(output, sources):
    raw_output = Path(output)
    if raw_output.exists() or raw_output.is_symlink():
        raise CheckError("output must be a new file: " + str(raw_output))
    output = raw_output.resolve()
    if output.exists():
        raise CheckError("output must be a new file: " + str(output))
    for source in sources:
        root = study_root(source)
        try:
            output.relative_to(root)
        except ValueError:
            continue
        raise CheckError("output must be outside each source study/bundle: " + str(root))
    return output


def write_report(path, report):
    path.parent.mkdir(parents=True, exist_ok=True)
    # Exclusive creation is intentional: checks must never replace an earlier report.
    with path.open("x", encoding="utf-8") as stream:
        json.dump(safe_json(report), stream, ensure_ascii=False, indent=2, allow_nan=False)
        stream.write("\n")


def layered_xml(width):
    """Independent spelling of the frozen four-layer workload specification, not Java execution."""
    parts = ['<adag version="2.1">\n']
    def file(name, direction):
        return '<uses file="{}" link="{}" size="32000000"/>'.format(name, direction)
    for layer in range(4):
        for column in range(width):
            task = layer * width + column
            parts.append('<job id="t{}" name="layered" runtime="{}">'.format(task, 1 + column % 5))
            if layer:
                parts.extend([file('e{}a'.format((layer - 1) * width + column), "input"),
                              file('e{}b'.format((layer - 1) * width + (column + width - 1) % width), "input")])
            if layer < 3:
                parts.extend([file('e{}a'.format(task), "output"), file('e{}b'.format(task), "output")])
            parts.append('</job>\n')
    for layer in range(1, 4):
        for column in range(width):
            parts.append('<child ref="t{}"><parent ref="t{}"/><parent ref="t{}"/></child>\n'.format(
                layer * width + column, (layer - 1) * width + column,
                (layer - 1) * width + (column + width - 1) % width))
    return ''.join(parts) + '</adag>\n'


def registered_plan(protocol, mode):
    if protocol not in PROTOCOLS or mode not in ("full", "smoke"):
        raise CheckError("unregistered protocol/mode: {}/{}".format(protocol, mode))
    variant, historical = PROTOCOLS[protocol]
    full = mode == "full"
    vms = [4, 8, 16, 32] if variant == "r13" and full else ([4, 16] if full else [4])
    networks = ["endpoint", "fat-tree-constrained", "fat-tree-wide"]
    if variant == "r13":
        networks = ["endpoint", "fat-tree-constrained", "fat-tree-mid", "fat-tree-wide", "fat-tree-fast"]
    conditions = [(v, n, "HOMOGENEOUS") for v in vms
                  for n in (networks if full or variant != "r13" else networks[:2])]
    if variant == "r13":
        conditions += [(v, "fat-tree-constrained", h) for v in ([8, 16] if full else [4])
                       for h in ("HET_MILD", "HET_STRONG", "HET_EXTREME")]
    planners = ["LOCAL_HEFT", "LOCAL_CPOP"] + (["RANDOM", "PSO"] if variant == "r10" else ["LOCAL_PEFT"])
    seeds = [11, 29, 47, 71, 101] if full else [11, 29]
    workflows = [dict(zip(("id", "family", "path", "sha256"), row), population="CLASSIC_DAX")
                 for row in (CLASSIC_INPUTS if full else [PAPER_INPUT])]
    for width in ([8, 32] if full else [4]):
        name = "layered-" + str(width * 4)
        workflows.append({"id": name, "family": "layered", "population": "SYNTHETIC",
                          "path": "inputs/" + name + ".dax", "sha256": hashlib.sha256(layered_xml(width).encode()).hexdigest()})
    cells = {(w["id"], v, n, h, p, seed) for w in workflows for v, n, h in conditions for p in planners
             for seed in (seeds if p in ("RANDOM", "PSO") else [11])}
    plan = {"protocol": protocol, "mode": mode, "vmCounts": vms, "randomSeeds": seeds,
            "deterministicSeed": 11, "planners": planners, "networks": networks, "runCount": len(cells),
            "workflows": workflows, "excludedInputs": [], "vmMips": 1000, "endpointMbPerSecond": 1,
            "fatTreeK": 4, "constrainedLinkMbPerSecond": 0.125, "wideLinkMbPerSecond": 1.25,
            "inference": "DAG_PAIRED_AFTER_SEED_MEAN;SEPARATE_POPULATIONS;HOLM_{}_PLANNERS;DESCRIPTIVE_SELECTED_CORPUS".format("THREE" if variant == "r10" else "TWO"),
            "transferStart": "ALL_GROUPS_START_AT_JOB_READY"}
    if full:
        plan["excludedInputs"] = [{"id": "inspiral-1000", "path": "dax/inspiral/n1000/Inspiral_1000.dax",
            "reason": "CONFLICTING_FILE_SIZE: H1-THINCA-782406919-2048.xml (39368.0 vs 46451.0)",
            "scope": "EXCLUDED_FROM_ALL_PLANNERS_BEFORE_COMPARISON"}]
    if variant == "r13":
        plan.update(midLinkMbPerSecond=0.5, fastLinkMbPerSecond=5.0,
                    fatTreeKRule="k=4 for vmCount<=16; k=8 for vmCount=32", heterogeneityMipsPatterns=MIPS_PATTERNS,
                    conditions=[dict(zip(("vmCount", "network", "heterogeneity"), c)) for c in conditions])
    if not historical:
        plan["executionSemantics"] = EXECUTION_SEMANTICS
    return variant, historical, plan, cells


def validate_registered_plan(plan, audit, allow_historical=False):
    variant, historical, expected, cells = registered_plan(needed(plan, "protocol"), needed(plan, "mode"))
    audit.ensure(not historical or allow_historical, "historical-mode-required", "plan/protocol", plan["protocol"])
    for section in ("workflows", "excludedInputs"):
        saved = needed(plan, section)
        if not isinstance(saved, list) or len(saved) != len(expected[section]):
            audit.problem("canonical-workload-catalogue", "plan/" + section, len(saved) if isinstance(saved, list) else saved, len(expected[section]))
            continue
        for spec, record in zip(expected[section], saved):
            path = needed(record, "path")
            normalized = str(path).replace("\\", "/")
            audit.ensure(normalized.endswith("/" + spec["path"]), "logical-input-path", section, path, spec["path"])
            spec["path"] = path
    audit.ensure(exact_equal(plan, expected), "canonical-plan-parameters", "plan")
    return variant, historical, cells


def run_key(row):
    return (needed(row, "workflowId"), integer(needed(row, "vmCount"), "vmCount", True),
            needed(row, "network"), row.get("heterogeneity", "HOMOGENEOUS"), needed(row, "planner"),
            integer(needed(row, "seed"), "seed"))


def resolve_input(manifest, manifest_path, entry, input_roots):
    recorded = Path(needed(entry, "path"))
    root = study_root(manifest_path)
    candidates = [root / "inputs" / recorded.name, recorded]
    workdir = manifest.get("provenance", {}).get("execution", {}).get("workingDirectory")
    for replacement in input_roots:
        if workdir:
            try:
                candidates.append(Path(replacement) / recorded.relative_to(Path(workdir)))
            except ValueError:
                pass
        study = manifest.get("provenance", {}).get("study") or {}
        dataset = study.get("dataset", {}).get("root")
        if dataset:
            try:
                candidates.append(Path(replacement) / recorded.relative_to(Path(dataset)))
            except ValueError:
                pass
    seen = set()
    for candidate in candidates:
        candidate = candidate.resolve()
        if candidate in seen:
            continue
        seen.add(candidate)
        if candidate.is_file():
            return candidate, [str(p) for p in seen]
    raise CheckError("input could not be located (no substitution): " + ', '.join(map(str, candidates)))


def parse_workflow(path, first_id, namespace, config):
    """Reparse the supported raw formats, including first-input-wins DAX compatibility.

    Task IDs follow input/specification order. Input aliases are deduplicated as Job file objects;
    output occurrences remain separate in DAX, as distinct FileItem objects do.
    """
    raw = Path(path).read_bytes()
    records, dependencies, declared_version = [], [], None
    warnings = []
    input_sizes = {}
    if Path(path).suffix.lower() in (".dax", ".xml"):
        if b"<!DOCTYPE" in raw.upper() or b"<!ENTITY" in raw.upper():
            raise CheckError("DTD/entity declarations are outside the supported DAX contract")
        try:
            root = ET.fromstring(raw)
        except ET.ParseError as error:
            raise CheckError("invalid DAX XML: " + str(error)) from error
        local = lambda tag: tag.rsplit("}", 1)[-1].lower()
        if local(root.tag) != "adag":
            raise CheckError("DAX root must be adag")
        declared_version = root.get("version")
        for node in root:
            if local(node.tag) == "job":
                name = node.get("id")
                runtime = number(Decimal(node.attrib["runtime"]), "DAX runtime", nonnegative=True)
                files = []
                seen_inputs = set()
                for file in node:
                    if local(file.tag) != "uses":
                        continue
                    filename = (file.get("name") or file.get("file") or "").strip()
                    kind = file.attrib["link"].lower()
                    size = number(Decimal(file.attrib["size"]), "DAX file bytes", nonnegative=True)
                    if not filename or kind not in ("input", "output"):
                        raise CheckError("invalid DAX file declaration")
                    if kind == "input":
                        if filename in input_sizes and input_sizes[filename] != size:
                            warnings.append("DAX first-input-size retained: " + filename)
                        size = input_sizes.setdefault(filename, size)
                        if filename in seen_inputs:
                            continue
                        seen_inputs.add(filename)
                    files.append((filename, kind, size))
                records.append((name, node.get("name", ""), runtime, files))
            elif local(node.tag) == "child":
                dependencies += [(parent.get("ref"), node.get("ref")) for parent in node if local(parent.tag) == "parent"]
        kind = "DAX_XML"
    elif Path(path).suffix.lower() == ".json":
        doc = decode_json(raw)
        declared_version = doc.get("schemaVersion")
        specification = needed(needed(doc, "workflow"), "specification")
        sizes = {}
        for file in needed(specification, "files"):
            name = needed(file, "id")
            size = number(needed(file, "sizeInBytes"), "JSON file bytes", nonnegative=True)
            if name in sizes and sizes[name] != size:
                raise CheckError("conflicting JSON file sizes: " + name)
            sizes[name] = size
        runtimes = {}
        for task in needed(needed(doc["workflow"], "execution"), "tasks"):
            name = needed(task, "id")
            runtime = number(needed(task, "runtimeInSeconds"), "JSON runtime", nonnegative=True)
            if name in runtimes and runtimes[name] != runtime:
                raise CheckError("conflicting JSON task runtimes: " + name)
            runtimes[name] = runtime
        declared = needed(specification, "tasks")
        by_name = {needed(t, "id"): t for t in declared}
        for task in declared:
            name = needed(task, "id")
            files = []
            for direction, key in (("input", "inputFiles"), ("output", "outputFiles")):
                for filename in dict.fromkeys(task.get(key, [])):
                    if filename not in sizes:
                        raise CheckError("undeclared JSON file: " + filename)
                    files.append((filename, direction, sizes[filename]))
            if name not in runtimes:
                raise CheckError("missing JSON runtime: " + name)
            records.append((name, task.get("name", ""), runtimes[name], files))
            for parent in task.get("parents", []):
                if parent not in by_name or name not in by_name[parent].get("children", []):
                    raise CheckError("asymmetric/missing JSON dependency")
                dependencies.append((parent, name))
            for child in task.get("children", []):
                if child not in by_name or name not in by_name[child].get("parents", []):
                    raise CheckError("asymmetric/missing JSON dependency")
        kind = "WFCOMMONS_JSON"
    else:
        raise CheckError("unsupported raw input format: " + str(path))
    if not records or any(not r[0] for r in records) or len({r[0] for r in records}) != len(records):
        raise CheckError("empty or duplicate raw task IDs")
    names = {r[0]: first_id + i for i, r in enumerate(records)}
    result = {}
    reference = number(needed(config, "runtimeReferenceMips"), "runtimeReferenceMips", positive=True)
    scale = number(needed(config, "runtimeScale"), "runtimeScale", positive=True)
    for i, (name, label, runtime, files) in enumerate(records):
        converted = runtime * reference * scale
        if not math.isfinite(converted) or converted > float(LONG_MAX):
            raise CheckError("raw runtime conversion overflows")
        length = max(100, min(LONG_MAX, int(converted)))
        scoped = [(namespace + "/" + n if namespace else n, direction, size) for n, direction, size in files]
        result[first_id + i] = {"taskId": first_id + i, "name": name, "type": label,
                                "lengthMi": length, "files": scoped, "parents": set(), "children": set()}
    for parent, child in dependencies:
        if parent not in names or child not in names:
            raise CheckError("dependency references an unknown task")
        result[names[child]]["parents"].add(names[parent])
        result[names[parent]]["children"].add(names[child])
    degrees = {tid: len(t["parents"]) for tid, t in result.items()}
    queue = collections.deque(tid for tid, count in degrees.items() if not count)
    visited = 0
    while queue:
        tid = queue.popleft()
        visited += 1
        for child in result[tid]["children"]:
            degrees[child] -= 1
            if not degrees[child]:
                queue.append(child)
    if visited != len(result):
        raise CheckError("raw input contains a dependency cycle")
    return result, kind, declared_version, warnings
