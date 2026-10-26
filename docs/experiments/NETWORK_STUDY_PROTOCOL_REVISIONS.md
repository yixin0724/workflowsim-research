# Network Study Protocol Identity and Compatibility

Registered identity covers both the **protocol ID and the mode**. An identical parameter matrix is not an identical execution model; a read-only integrity check is not a re-execution under the current code.


## Current and Historical Identities

The strings below come from [NetworkStudyPlan](<../../experiments/src/main/java/org/workflowsim/experiments/network/NetworkStudyPlan.java>); they are the actual protocol identifiers and must not be renamed during documentation reorganization.

| CLI variant | Current execution protocol | Accepted historical protocol | full / smoke plan units |
| --- | --- | --- | ---: |
| `r10`, also the default variant | `network-limited-r10-v3` | `network-limited-r10-v2` | 504 / 36 |
| `peft-comparison` | `peft-comparison-r12-v2` | `peft-comparison-r12-v1` | 126 / 18 |
| `sensitivity-r13` | `sensitivity-response-r13-v2` | `sensitivity-response-r13-v1` | 546 / 30 |

The counts in the table are the plan sizes of the registered matrices, not numbers of completed runs or passing tests. smoke has its own inputs, conditions, and seeds; it is not an arbitrarily pruned full, and changing only the mode does not obtain the full-study identity.

The executor produces only the current protocols; there is no switch that disguises an old execution identity under current code. Both the current protocols and the run configurations must declare `WORK_CONSERVING_TASK_EXECUTION_V2`; the accepted older protocols keep their original v4 shape and must not adopt the current execution label and then keep impersonating a historical identity.

Run commands, the current matrix conditions, and the statistical scope are concentrated in the [campaign guide](<CAMPAIGNS.md>). The provenance of the original studies and their historical numbers is traced through the [studies directory](<../../experiments/studies/README.md>) and serves neither as an entry point for new users nor as current golden values.

## How the Registered Declaration Is Formed

[NetworkStudyExecutor](<../../experiments/src/main/java/org/workflowsim/experiments/network/NetworkStudyExecutor.java>) first constructs and checks the plan, saves the actual `NetworkStudyPlan.asMap()` as the output directory's `protocol.json`, and then produces the per-run evidence and the index. This generated JSON is the retained protocol artifact; the current run matrix is not decided by the body of old Markdown.

The registered declaration fixes the input IDs, the order, the sources, the hashes, the eligibility exclusions, the conditions, the planners, and the actual seeds. Synthetic inputs come from deterministic generators; input eligibility is checked uniformly for all planners before any algorithm effect is observed. Changing the input content, the candidates, the seeds, or the conditions requires a new registered protocol; the old ID must not be kept.

Pre-run input eligibility and read-only validation use different paths: the executor reads and parses the local inputs; the validator checks the retained records against the registered declaration, which involves no file I/O, and does not infer backwards "what should have been run" from the index under validation. The retained absolute input paths must correspond to the registered logical paths and the declared dataset root, but the integrity check does not require the original machine's input files to exist on the current machine; an actual rerun must still locate and verify the original inputs.

## Scope of the Integrity Check

[NetworkStudyValidator](<../../experiments/src/main/java/org/workflowsim/experiments/network/NetworkStudyValidator.java>) accepts both the current and the historical identities in the table above, and performs:

1. Checks that the index plan's values agree with the retained protocol JSON and that it matches the registered full matrix; rejects coordinated pruning, duplication, omission, undeclared conditions, or mode changes.
2. Computes SHA-256 over the **raw bytes** of `protocol.json`, binding the per-run study IDs, the protocol logical ID, the available flags, the protocol hash, and the driver artifact identity.
3. Validates each per-run bundle and checks the standard configuration, the platform resources/prices/storage/topology, and the pin/preflight/actual placements. The execution labels of new and old protocols are not interchangeable.
4. Checks the input identities, the algorithm labels, and the complete success states; a success mark in the index cannot override an unfinished workflow in the evidence.
5. Checks the published makespan, logical completion, mean/P95 waiting, and mean VM utilization, and recomputes the summaries. Metrics must be finite, non-negative JSON numbers, and this study's utilization must lie in `[0,1]`; signs and upper bounds are judged by exact decimal semantics, not waved through by double rounding.

The historical source/binary hashes are not required to equal the current code, and the algorithm-contract explanatory text is not certified verbatim against the current wording; the algorithm labels and the fixed conditions must still match. The protocol SHA is only a content-consistency binding, not a digital signature or an attestation of authorship.

A successful CLI output names the protocol, the mode, and `HISTORICAL_PROTOCOL_INTEGRITY` or `DECLARED_PROTOCOL_INTEGRITY`, and is explicitly marked `NOT_A_CURRENT_CODE_RERUN`. The Java validator recognizes compatible identities by protocol ID; for the standalone Python tool's `--historical` requirements see the [audit tooling note](<../../scripts/STUDY_AUDIT.md>) — do not confuse the two CLIs.

## Why Old Numbers Do Not Prove Current Algorithms

The LOCAL_PEFT used by the old `peft-comparison-r12-v1` and `sensitivity-response-r13-v1` counts the current task's own cost into OCT and sets the exit to the average computation cost. That is not the original PEFT's successor-cost, exit-zero recurrence; fixing only the ready-list or recomputing the statistics cannot turn the old numbers into standard-PEFT evidence.

The current protocols use the maintained execution and algorithm semantics, but a passing protocol ID, test, or integrity check does not automatically yield a new effect conclusion. The PEFT recurrence, its source fixtures, and the model boundaries are documented in the [algorithm contracts](<../algorithms/CONTRACTS.md>). Re-evaluate on the complete current matrix, keep the old evidence identities, and neither copy the old mechanistic explanations nor rename old results as new ones.

The execution model also changes the effective MI, the computation windows, the failure retries, the data readiness, and the termination instants. Even if the parameters are identical, or some values happen to coincide, that does not allow asserting that the old model has been reproduced bit for bit. When a comparison requires actual runs, use the [rerun difference contract](<RERUN_DIFF_CONTRACT.md>) and keep meaningful outcomes such as `DIVERGED` and rebuild refusals.

A handover should include the original bytes of the protocol, the index, all the referenced evidence, and the generated inputs; a summary table alone is not enough for re-verification. The original evidence stays read-only, new executions and audits use separate outputs, and historical studies are not rewritten to please the current validator.
