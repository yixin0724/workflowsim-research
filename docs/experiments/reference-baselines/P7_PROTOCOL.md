# P7 Compatible Reference Protocol

> **Purpose: frozen reference and historical compatibility, not a current getting-started guide.** P7 preserves the comparison matrix of specific inputs, configurations, and online schedulers; it does not summarize the platform's current capabilities, nor does it guarantee that new code reproduces the old implementation's numbers. How to run it and the retained historical values are documented in the [P7 local notes](<../../../experiments/reference/p7/README.md>).


## Frozen Matrix

The [matrix implementation](<../../../experiments/src/main/java/org/workflowsim/experiments/reference/p7/P7BaselineMatrix.java>) fixes **2 inputs × 2 platform variants × 5 schedulers = 20 run units** — not 20 inputs. Logical paths are relative to the absolute dataset root passed in.

| Input | Logical path | Tasks | VMs | SHA-256 |
|---|---|---:|---:|---|
| Epigenomics n100 | `dax/epigenomics/n100/Epigenomics_100.dax` | 100 | 10 | `374521746417b18133682de21b654b84c32acde6332462c0ce916c4ba12c7f36` |
| Epigenomics n997 | `dax/epigenomics/n997/Epigenomics_997.dax` | 997 | 50 | `2ed853db24126750a1bf427b5731d5fb18b6d31baf3d2088c14b310478bed628` |

The scheduler set is exactly `FCFS`, `READY_BATCH_ROUNDROBIN`, `READY_BATCH_MCT`, `READY_BATCH_MINMIN`, `READY_BATCH_MAXMIN`. The planning layer is `INVALID`; other algorithms, other workflow families, and WfCommons/WfInstances inputs do not join this matrix automatically.

| Platform variant | VM MIPS | Placement |
|---|---|---|
| H0 / `H0_HOMOGENEOUS` | all 1000 | one Host per VM |
| H1 / `H1_HETEROGENEOUS` | even VM IDs 500, odd 1500; equal counts, mean 1000 | one Host per VM |

Host/VM IDs are numbered consecutively from 0. Each Host has 2 PEs at 2000 MIPS each, 2048 MB RAM, bandwidth parameter 10000, and 1000000 MB of storage; each VM has 1 PE, 512 MB RAM, bandwidth parameter 1000, a 10000 MB image, Xen, and `SPACE_SHARED`. No explicit pin or per-VM pricing is set; the preflight and actual VM–Host assignments both enter the evidence and are checked. Shared storage uses the platform defaults of 1000000000000 MB capacity and a 15 MB/s transfer rate; the datacenter cost parameters are, in order, 3.0 per CPU-second, 0.05 for memory, 0.1 for storage, and 0.1 for bandwidth. These are abstract configuration values, not a calibration of hardware or of cloud bills.

## Fixed Configuration and the Meaning of Quantities

| Configuration | Fixed value |
|---|---|
| File system / data movement | `SHARED` / `LEGACY_WORKFLOWSIM_V1` |
| Clustering / overhead / failure | `NONE` / `OverheadModelConfig.none()` / disabled |
| Root seed | `20260901` |
| runtime reference MIPS / scale | `1000.0` / `1.0` |
| CloudSim minimum event interval | `0.1` simulated seconds |
| Arrival / deadline / cost matrix / topology | zero arrival for the single input / not requested (0) / none / none |
| Cost model | `DATACENTER`; recorded but not a P7 comparison metric |

A DAX's non-negative runtime is converted to `max(100, floor(runtimeSeconds * referenceMips * runtimeScale))` MI and then passes the current kernel's representability check. The event interval is the kernel's tick, not a physical link delay.

The primary metric `makespan` keeps its **simulation-end instant** meaning, stage-in included for this model; it must not be relabeled the same metric after being switched to logical task completion time. Secondary evidence includes Job success/failure, start/end/CPU times, categories, Task outcomes, and per-VM job summaries. A Job execution envelope may contain the stage-in expressed as integer MI; Task windows are model observations; VM summaries are not Host monitoring. Cost, utilization, energy, and the decision wall-clock time of the local machine are not P7 primary or secondary comparison metrics.

This matrix supports only descriptive comparison within a single family under a fixed model. Deterministic repetitions serve reproduction checks; they are not independent random samples, and no significance tests may be based on them. Identical root seeds do not mean event-keyed common random numbers. No cross-family superiority, real-trace reproduction, network/storage contention gains, failure robustness, or production-platform performance may be claimed on this basis.

## Execution, Index, and Validation

The P7 driver belongs to the experiments module of the default reactor build; the core module does not depend on it. The recommended path is the [executor](<../../../experiments/src/main/java/org/workflowsim/experiments/reference/p7/P7BaselineExecutor.java>) with an explicitly provided absolute dataset root and absolute empty output directory; the dataset root is the directory containing `dax/`, not the project root above it. A non-empty output directory is rejected. The CLI also supports a zero-argument mode that derives the dataset and a timestamped output directory from `user.dir`; that convenience must not replace explicitly recording the input/output locations of a study.

| Entry point | Index / identity |
|---|---|
| `executeBaseline(...)` or the CLI | `p7-baseline-index.json`; schema `workflowsim-p7-baseline-index-v3`; kind `FROZEN_FULL_BASELINE` |
| `executeSelection(...)` | `p7-reference-selection-index.json`; schema `workflowsim-p7-reference-selection-index-v1`; kind `PARTIAL_SELECTION_NOT_P7_BASELINE` |

Even if a selection run happens to cover every unit, the selection index is still not a formal baseline. The executor checks each run's input hash, Task count, and successful completion, and writes out and validates the evidence bundles; only after every run has succeeded does it write the index and validate it immediately.

New artifacts use manifest v4, metrics v2, events v1, and provenance v3, and record the current `WORK_CONSERVING_TASK_EXECUTION_V2` execution semantics. New fields or an identical parameter matrix do not automatically turn old results into current-model results. The [index validator](<../../../experiments/src/main/java/org/workflowsim/experiments/reference/p7/P7EvidenceIndexValidator.java>) checks the complete 4×5 matrix, the input fingerprints, the fixed configuration, the platform and actual placements, the result summaries, the per-run artifacts, and the study identity; historical v2/v3 identities are read-only compatible, with missing fields not fabricated.

Before generating comparable results, one should pass the full gate that includes the integration tests. The matrix/executor tests, the core semantic tests, and the artifact checks jointly constrain this reference track; validating the index structure alone proves neither accuracy on a real platform nor that the historical numbers are unchanged under the current code. When retaining results, hand over the complete index and all referenced artifacts, not just the summary table.

## Protocol Identity and Document Fingerprint

[P7ReferenceIdentity](<../../../experiments/src/main/java/org/workflowsim/experiments/reference/p7/P7ReferenceIdentity.java>) uses the study ID `p7-baseline`, the component `org.workflowsim:workflowsim-workbench:1.0`, the driver `org.workflowsim.experiments.reference.p7.P7BaselineExecutor`, and the fixed logical ID `docs/experiments/reference-baselines/P7_PROTOCOL.md`.

The SHA-256 of the **actual bytes of this very file** under that logical ID is computed and written into the provenance of new runs; the file is located through the driver's code source and does not depend on the CLI's current directory. A standalone JAR without a colocated protocol records `available=false` and an empty hash instead of forging a fingerprint. The historical logical identity of the old `P7_EXPERIMENT_PROTOCOL.md` is still accepted by the validator.

This slimming keeps the original path and the protocol identity, but it does change the document fingerprint that future new runs will record; **no historical bundle, index, or original hash has been modified**. The protocol hash is a record of consistency and provenance, not a digital signature, a release certification, or golden values of run results.

General notes on the algorithms are in the [algorithm catalog](<../../algorithms/CATALOG.md>), and protocol management for new studies is described in the [studies directory](<../../../experiments/studies/README.md>).
