# Study Protocols and Retained Evidence

**English** · [简体中文](<README.zh.md>)

This directory holds study-specific protocols and provenance notes; it is not another Maven
module and not a platform capability list. Research drivers and tests live in the
experiment module's standard source directories; general run operations are described in
[Workbench](<../../docs/getting-started/WORKBENCH.md>).

## Currently executable protocols

[Protocol revisions and certification scope](<../../docs/experiments/NETWORK_STUDY_PROTOCOL_REVISIONS.md>)
is the maintained entry point for runs, registered matrices, and validation rules.
[NetworkStudyPlan](<../src/main/java/org/workflowsim/experiments/network/NetworkStudyPlan.java>)
registers distinct identities for the three variants below; an identical parameter matrix
does not mean an identical execution model.

| CLI variant | Current protocol ID | full / smoke run units |
|---|---|---:|
| `r10` (default) | `network-limited-r10-v3` | 504 / 36 |
| `peft-comparison` | `peft-comparison-r12-v2` | 126 / 18 |
| `sensitivity-r13` | `sensitivity-response-r13-v2` | 546 / 30 |

Current execution uses `WORK_CONSERVING_TASK_EXECUTION_V2`; the two PEFT studies use the
standard recurrence with successor costs and a zero exit OCT. A complete study must be
executed explicitly into a new directory — renaming a smoke run is not a study; old
protocol identities serve only read-only historical integrity checks. The retained
post-correction studies and impact analysis are documented in the
[correctness-repair results](<correctness-repair/RESULTS.md>) and are not a
re-certification of every later code change.

## Historical material and optional evidence locations

The records below describe their respective implementations at the time. **Passing an
integrity check does not mean the current model produces the same numbers, and it is not a
proof of algorithmic correctness or of real-platform calibration.** The `output/...` entries
are original workspace retention locations relative to the project root; they are not
provided with a normal checkout, are not guaranteed to exist on the current machine, and
must never be overwritten by new runs.

| Historical record | Identity and scope | Retained material / optional location |
|---|---|---|
| Fat-tree campaign | R8 recalibrated version; 360 cells, seed 91 | [sources and parameters](<fattree-scheduling-campaign/RETENTION.md>); the repository retains the [JSON](<fattree-scheduling-campaign/campaign-results.json>) and the [numeric tables](<fattree-scheduling-campaign/campaign-results.md>) |
| R10 first pilot | 576 cells; 12 incompatible runs failed, not certified as a complete comparison | `output/network-study-r10-pilot.tar.gz` (compressed tar original); [exclusion notes](<network-limited-r10/PROTOCOL.md>) |
| R10 qualified matrix | `network-limited-r10-v2`; 504 cells | [historical provenance](<network-limited-r10/PROTOCOL.md>); `output/network-study-r10-final/`; `output/network-study-r10-evidence.tar.gz` |
| R12 / S5 PEFT comparison | `peft-comparison-r12-v1`; 126 cells; legacy PEFT non-standard | `output/peft-comparison-r12/`; `output/peft-comparison-r12-evidence.tar.gz`; corrected identity is `peft-comparison-r12-v2` |
| R13 sensitivity response surface | `sensitivity-response-r13-v1`; 546 cells; legacy PEFT non-standard | original [preregistered protocol](<sensitivity-r13/PROTOCOL.md>) and [assessment record](<sensitivity-r13/RESULTS.md>) archived in place; `output/sensitivity-r13/`; `output/sensitivity-r13-evidence.tar.gz` |
| Post-correctness-repair recheck | frozen-implementation study of the current three protocols at Java commit `8681f9c` | [sources, numeric changes, and retention notes](<correctness-repair/RESULTS.md>); `output/workbench-correctness-repair-97c66a0/studies/` |
| R13 v2 current recertification | `sensitivity-response-r13-v2`; 546 cells; executed at HEAD `f00ff69` (2026-10-23), bit-identical to the `8681f9c` impact layer | [protocol](<sensitivity-r13-v2/PROTOCOL.md>) and [results](<sensitivity-r13-v2/RESULTS.md>) (English); `output/sensitivity-r13-v2/`; `output/sensitivity-r13-v2-evidence.tar.gz` |

### R12 provenance and comparison boundary

Legacy R12 used the same seven qualified inputs as R10 (five classic DAXs, two synthetic
DAGs), 4/16 homogeneous 1000-MIPS VMs, and three networks — endpoint contention plus
0.125/1.25 MB/s Fat-tree — with the three list planners `LOCAL_HEFT`, `LOCAL_CPOP`, and
`LOCAL_PEFT` once per condition, seed 11: `7 × 2 × 3 × 3 = 126`. It applies the same
planner-uniform Inspiral1000 exclusion rule, LOCAL/STATIC/SPACE_SHARED, no
failure/overhead, one VM per Host; R10's RANDOM/PSO were rerun separately and never mixed
in.

Legacy R12/R13's `LOCAL_PEFT` incorrectly counted the task's own compute cost inside OCT
and set the exit to the average compute cost; the ready-list fix did not correct that
formula. The legacy numbers, the "homogeneous degeneration / heterogeneous activation"
narratives, and the correctness account of the time record only that non-standard
implementation and cannot directly evaluate standard PEFT. How the current protocol relates
to these records is defined in the
[protocol revisions](<../../docs/experiments/NETWORK_STUDY_PROTOCOL_REVISIONS.md>); the
primary-source fixture note for the standard algorithm is
[here](<../../simulator/src/test/resources/dax/peft-paper-example.SOURCE.md>).

The three historical network studies' original output directories each preserve
`protocol.json`, `network-study.json`, the automated result tables, generated inputs, and
per-run manifest/metrics/events. R12's legacy result table is at the optional path
`output/peft-comparison-r12/results.md` and is not the current numeric baseline. The
retained tars and original protocol bytes stay as-is; deleting duplicate explanatory
documents must not delete or relabel this evidence.

**Language policy (added 2026-10-23):** the archive documents under
`network-limited-r10/`, `sensitivity-r13/`, `fattree-scheduling-campaign/`,
`correctness-repair/`, and `peft-comparison` results are **historical originals preserved
in Chinese and deliberately not translated** — their value is being period-accurate
records, and translation is a rewriting act. The current-layer study (sensitivity-r13-v2)
is maintained in English. Retention semantics and integrity checks are
language-independent.

## Requirements for new studies and handover

- Before execution, declare the research question, the same-decision-layer algorithm set,
  input hashes, platform/model, seed design, metrics, statistical unit, stopping
  conditions, and retention rules; one protocol document suffices — no mechanical
  splitting into duplicated files.
- Input locations must be explicit, and outputs must use a new directory. Retain failures
  and exclusion reasons; do not select inputs after observing algorithm outcomes; sharing
  a root seed does not automatically constitute event-keyed common random numbers.
- Hand over the complete protocol bytes, the index, every referenced bundle, and the
  generated inputs — a summary table alone is not a handover. Original experiment data,
  archives, fixtures, and golden values are not disposable files to clean up with
  documentation passes.
- Aggregate random seeds within a DAG first, then compare across declared sources and
  conditions; repeated conditions are not independent samples, and non-significance does
  not imply equivalence. General statistical conventions are in the
  [campaign guide](<../../docs/experiments/CAMPAIGNS.md>); independent checking tools are
  described in the [audit tooling notes](<../../scripts/STUDY_AUDIT.md>).

Historical material is traceable only from the local study directory and is not part of
current onboarding navigation.
