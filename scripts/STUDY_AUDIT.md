# Independent Study Evidence and Statistics Checks

The tools below depend only on the **Python 3.9+ standard library**; they invoke no Java production algorithm, launch no simulation, and replay no network. Inputs are read-only; audit reports must be written to a new, independent location.

**English** · [简体中文](<STUDY_AUDIT.zh.md>)

| Tool | Inputs | Scope of checks |
| --- | --- | --- |
| [Evidence checker](<verify-study-evidence.py>) | one or more study indexes, Workbench experiment indexes, or a single manifest v4 | raw inputs, Job/Task attempts, timing, artifact hashes, and independently recomputable metrics |
| [Statistics checker](<verify-study-statistics.py>) | one or more registered network study indexes | the registered matrix, protocol bindings, per-DAG effects, sign tests, and Holm correction |
| [Network and lifecycle checker](<verify-network-ledger.py>) | V1/V2/V3 sidecars or the corresponding manifest v4 bundles | versioned network/lifecycle semantics and the applicable online-action context, with the scope stated explicitly by the report |

The first two tools share [independent parsing and registration definitions](<_study_audit.py>). Even when the basic evidence checker verifies a sidecar's SHA-256, that does not amount to validating the sidecar's content; it and the network checker cannot substitute for one another. No tool passing proves algorithmic optimality, reproduction of the canonical paper, or calibration on a real platform.

## Invocation and Output Protection

The commands below run from the project root. You can also use absolute paths for the scripts and inputs from any directory; relative input paths resolve against the invoking working directory, without guessing the repository location.

```bash
# 单个完整运行包
PYTHONDONTWRITEBYTECODE=1 python3 scripts/verify-study-evidence.py \
  /absolute/experiment/runs/fcfs-s42/result.manifest.json \
  --output /absolute/new-audit/evidence-single.json

# 多个研究的基础证据与统计需要分别检查
PYTHONDONTWRITEBYTECODE=1 python3 scripts/verify-study-evidence.py \
  /absolute/study-a/network-study.json /absolute/study-b/network-study.json \
  --output /absolute/new-audit/evidence.json

PYTHONDONTWRITEBYTECODE=1 python3 scripts/verify-study-statistics.py \
  /absolute/study-a/network-study.json /absolute/study-b/network-study.json \
  --output /absolute/new-audit/statistics.json

# 另行检查物理生命周期及适用的在线动作；结构化结果写到标准输出
PYTHONDONTWRITEBYTECODE=1 python3 scripts/verify-network-ledger.py \
  /absolute/experiment/runs/fcfs-s42/result.manifest.json --json
```

Replace the placeholder paths; the last command applies only to the corresponding network/lifecycle evidence bundles. `--output` is a brand-new JSON **file path**, not an existing file or directory, and it may not lie inside the study/bundle root of any input. Parent directories may be created, existing reports are never overwritten, and inputs, protocols, indexes, and evidence are never rewritten.

Exit codes of the first two tools:

| Exit code | Meaning |
| ---: | --- |
| 0 | No problems found within the requested, explicitly declared scope of checks |
| 1 | The checks found problems or could not complete the requested coverage; the report keeps the diagnostics |
| 2 | Usage, output-protection, or report-writing failure |

Check the exit code, `status`, `issueCount`, and the coverage statement together; do not treat "the report file was generated" as success. `--max-issues` defaults to 200 and only caps the saved diagnostic details; it does not reduce the issue count or change the failure status. For the network checker's statuses and the full interpretation of what it captures, see the [independent network verification notes](<../docs/advanced/NETWORK_LEDGER_PYTHON.md>).

## Registered Study Identities and Compatibility Modes

The statistics checker supports only explicitly registered `protocol` and `full/smoke` matrices; it infers no completeness from an index's self-description and is not a general statistics engine for arbitrary experiments.

| Registered study | Current identity | Compatibility identities that require `--historical` |
| --- | --- | --- |
| Network-limited comparison | `network-limited-r10-v3` | `network-limited-r10-v2` |
| Planner comparison | `peft-comparison-r12-v2` | `peft-comparison-r12-v1` |
| Sensitivity comparison | `sensitivity-response-r13-v2` | `sensitivity-response-r13-v1` |

These strings are protocol identifiers in the [executable registry](<_study_audit.py>); retained evidence cannot be renamed just to tidy the documentation. The basic evidence checker requires manifest v4; older v4 evidence that lacks the current execution-semantics declarations must also explicitly enable the compatibility mode, and `--historical` does not upgrade v2/v3 manifests into v4.

```bash
PYTHONDONTWRITEBYTECODE=1 python3 scripts/verify-study-evidence.py \
  /absolute/archive/network-study.json --historical \
  --input-root /absolute/restored-repository \
  --output /absolute/new-audit/historical-evidence.json
```

`--input-root` serves the evidence checker only and may be supplied repeatedly. For each raw input it tries, in order, the same-named file under the study's retained `inputs/` tree, the recorded path, and explicit relocation based on the original workingDirectory or dataset root. When a file is found, its SHA/size are still checked; inputs are never silently swapped because a hash mismatches. A raw input that cannot be found or parsed is reported as a coverage gap with a nonzero exit, not a silent pass.

Reading compatibility formats is permitted without endorsing the older physics/timing or the non-standard PEFT recursion; recomputed statistics agreeing is not an endorsement of standard-algorithm correctness either. Across different execution models, no assertion is made that values are numerically equal under shared conditions.

## Basic Evidence Coverage

Supports the standard runner's basic projection — NONE clustering, SPACE_SHARED, and one active Job per VM — including returned success, failure, and retry attempts:

- Checks artifact-role uniqueness, relative-path containment, SHA-256/size, and manifest/metrics consistency.
- Checks event sequence numbers, finite non-negative times, key-event uniqueness, Job/Task identity, and retry lineage.
- Re-parses runtimes, DAGs, and file declarations from the actual DAX/WfFormat inputs; cross-checks raw MI, requirements, and billed bytes. Multiple inputs keep separate namespaces, and DAX keeps the first-INPUT-size precedence rule.
- The explicit cost matrix takes precedence over raw MI; effective MI, Task windows, and CPU lower bounds are checked under independent Java round semantics, and the current attempt's work is never replaced by the source length.
- Recomputes the covered completion/retry counts, waiting, bounded slowdown, nearest-rank median/P95, throughput, VM busy-interval unions, CV/Jain, and abstract cost; failures/retries are counted per the corresponding metric's rules.

The evidence checker defaults to `1e-10` for `--rel-tol` and `1e-8` for `--abs-tol`; the relative tolerance applies only to metric/cost aggregates. Physical timing uses `abs_tol + 4 * ulp(largest absolute timestamp)`, and CPU lower bounds add a further `1 / MIPS`; missing work is never relaxed in proportion to the absolute clock. Integer counts and IDs must be exact.

Each bundle's `coverage.checkedMetrics` lists the items actually recomputed, and a missing required item is an error; `outsideMetricScope` lists uncertified fields. Wall-clock performance, deadline/critical-path reference values, and new metrics not listed there cannot be treated as certified just because the whole report succeeded.

The basic tools do not independently replay max-min rate segmentation, Fat-tree network service, or online policy selection. V2/V3 lifecycle and online actions require the network checker separately; even when that checker passes, its scope declarations — `fluidServiceAccountingCertified`, `liveProgressReplayed`, and the like — still bind, and complete fluid service or future contention cannot be declared proven.

## Statistics Coverage

The statistics checker constructs the registered matrix independently, verifying input IDs/fingerprints, algorithms, conditions, seeds, completion statuses, and the binding of the protocol's verbatim-byte SHA to each run's manifest.

Seeds are pooled per DAG first and then paired by source, VM, network, and heterogeneity; a seed is never treated as an independent DAG, and the classic and synthetic workload populations are never merged. Sign tests and Holm use exact fractions; the p value is undefined when all results tie. The report retains per-DAG effects, the complete vectors, win/tie/loss and tail information, not just the reported medians.

With multiple indexes, the checker also cross-checks metrics for shared conditions under the same execution model; any inconsistency exits nonzero. A statistics check is not a full verification of the evidence physics; combine it with basic evidence and the needed lifecycle reports. The small-sample, family-correlation, and random-stream-comparability limitations must still be stated in the study conclusions.

## Self-Test and Retention

```bash
PYTHONDONTWRITEBYTECODE=1 python3 scripts/test-study-verifiers.py
```

The [self-test](<test-study-verifiers.py>) uses small hand-written fixtures to verify healthy inputs, missing fields, MI/interval forgery, output protection, registered matrices, and statistics counterexamples; it does not show that any real full study has passed. The remaining independent checker suites and cross-language verification are described in the [build guide](<../docs/getting-started/BUILD.md>).

Keep reports together with their input indexes, tool versions, and commands. Do not appease the checkers by deleting issue records, rewriting the original studies, or changing protocol identities.
