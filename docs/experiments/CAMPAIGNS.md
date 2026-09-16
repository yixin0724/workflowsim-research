# 实验 Campaign 与数据移动协议

## Status and Claim Boundary

P9 adds an executable, immutable experiment-campaign declaration and an
auditable campaign index. P10-A adds one optional, topology-free data-movement
model. Together they make experiment inputs, repetition intent, transfer
assumptions, and basic descriptive statistics explicit. They do not turn a
WorkflowSim result into a replay of a WfInstances trace, a cloud-network
prediction, a provider-billing estimate, or a calibrated storage benchmark.

The frozen P7 matrix remains governed by `P7_PROTOCOL.md`. This
protocol is an additional capability and must not be used to retroactively
change P7's deterministic result interpretation.

模块边界：`ExperimentPlan`、campaign executor、指标和只读验证器是可复用核心 API，
位于 `simulator/`；具体研究的矩阵构造、CLI 驱动和研究专用测试应位于
`experiments/src/main/java/org/workflowsim/experiments/studies/<study-id>/`，相应协议材料位于
`experiments/studies/<study-id>/`。研究模块通过 `mvn verify` 验证，核心只读
验证器通过 `-Pcore-exec -pl :workflowsim -am` 启动。

## Campaign Contract

`ExperimentPlan` is the source of truth for a campaign. A cell declares its
identifier, comparison group, candidate identifier, baseline role,
`SimulationConfig`, `PlatformProfile`, `SeedPlan`, and optional string tags.
The builder rejects a malformed study before simulation:

| Invariant | Reason |
| --- | --- |
| Every comparison group has exactly one baseline. | A delta otherwise has no declared reference. |
| A candidate identifier occurs once per comparison group. | Avoids an accidental duplicate treatment cell. |
| Cells in one group use the same randomization design. | Keeps the summary's inference label meaningful. |
| A configuration's VM count equals its platform VM specification count. | Prevents an apparent algorithm comparison from silently using a different resource count. |
| Seed-plan count, per-run seed, report seed, and cell/replication pair agree. | Keeps replication provenance auditable. |

`ExperimentCampaignExecutor` deliberately runs one cell and one replication at
a time through `SimulationRunner`. WorkflowSim retains legacy static CloudSim
state, so concurrent JVM execution is outside this contract. Parallel studies
must use separate JVM processes and must record their process-level isolation
and artifact merge procedure separately.

The executor is deliberately fail-fast: a parsing, configuration, simulator, or
retry-limit exception prevents a complete campaign result from being produced.
Such an exception is not silently converted into a workflow-failure sample.

`WorkflowProfile` is included in every report and campaign-run record. It
describes the parsed, pre-clustering logical graph: task/edge/root/leaf counts,
depth and width, runtime-MI distribution, and declared file-reference/input
size summaries. It supports scenario stratification and audit. It is not a
measurement of a real execution environment.

Every `PlatformProfile` also contains a capacity-feasible VM-to-Host preflight.
Researchers may pin individual VMs, while the default mirrors the legacy
greatest-remaining-PE selection with declared Host order as its tie-break. The
runner compares that map to the allocation observed at CloudSim VM creation and
records both in each evidence bundle. Keep one VM per Host when Host placement
must be excluded as a factor; co-location is a resource-capacity scenario, not
a Host CPU-contention model or a proxy for real infrastructure utilization.

## Randomness and Statistics

`SeedPlan` stores every actual root seed, either explicitly or by a
deterministic SplitMix64 derivation from a recorded derivation root. It offers
three interpretations:

| `RandomizationDesign` | Permitted result interpretation |
| --- | --- |
| `DETERMINISTIC` | One reproducible model result. Report exact values, a workflow-run completion fact, and descriptive deltas only; no confidence interval. |
| `INDEPENDENT_REPLICATIONS` | Per-cell sample mean, sample standard deviation, min/max, and two-sided 95% Student-t mean interval when at least two replications exist. Eligible workflow-run completion additionally has a cell-local Wilson score interval. |
| `COMMON_ROOT_SEEDS_NOT_EVENT_KEYED_CRN` | Per-cell descriptive summaries, descriptive workflow-run completion rate, equal-root-seed matched simulation-end deltas, and a paired Wilcoxon signed-rank test on the matched makespan deltas (see below). No paired confidence interval and no CRN-paired treatment-effect claim. |

The important distinction is that equal root seeds do not establish common
random numbers. Scheduling choices can alter which component stream is
consumed next. A true CRN comparison needs a future event-keyed stochastic
contract: each logical random event must be assigned a stable key independent
of algorithm control flow, and that property must receive dedicated regression
tests.

Since R1 (paired significance framework), `ComparisonSummary` additionally
exposes `getPairedSignificance()` — a two-sided Wilcoxon signed-rank test
(`PairedWilcoxonSignificance`) over the matched-seed makespan deltas
(candidate minus baseline). The test is non-parametric, needs no normality
assumption, and is computed on the same matched-root-seed pairs as the
descriptive deltas. Machine-readable statuses:

| Status | Meaning |
| --- | --- |
| `AVAILABLE_WILCOXON_SIGNED_RANK_EXACT` | Exact distribution p-value (effective nonzero-difference count ≤ 25). |
| `AVAILABLE_WILCOXON_SIGNED_RANK_NORMAL_APPROXIMATION` | Normal-approximation p-value with continuity correction (effective count > 25). |
| `UNAVAILABLE_ALL_PAIRED_DIFFERENCES_ARE_ZERO` | Every paired makespan delta is zero (e.g. algorithms converge on identical schedules); no test is fabricated. |
| `UNAVAILABLE_SINGLE_NONZERO_PAIRED_DIFFERENCE` | Only one nonzero delta; the rank test is undefined. |
| `UNAVAILABLE_FEWER_THAN_TWO_PAIRED_SAMPLES` | Not enough matched seeds. |
| `UNAVAILABLE_INDEPENDENT_REPLICATIONS_ARE_NOT_PAIRED` | Independent-replication cells share no seeds; no paired test exists. |

Interpretation boundary: because the stochastic model is not event-keyed, this
test answers "do the two paired seed series differ in distribution", not a
variance-reduced CRN treatment-effect claim. Zero deltas are reported honestly
rather than silently dropped, and `medianDifference` provides the direction
and typical magnitude of the candidate-minus-baseline delta. The acceptance
regression `MultiSeedSignificanceIntegrationTest` demonstrates the full path
on a heterogeneous 16-VM platform with Montage_1000 and a failure model
(FCFS vs READY_BATCH_MCT, 10 shared root seeds, exact p-value reported).

The Wilson interval is mathematically defined with one eligible observation,
but one replication is not sufficient research evidence. It is a per-cell
proportion interval only: the simulator supplies no success-rate-difference
interval, odds/risk ratio, McNemar test, paired test, or automatic algorithm
superiority claim.

The campaign summary reports `simulationEndSeconds` (the historical
`makespanSeconds`), modeled processing cost, and logical-task completion-rate
samples per cell. It also reports a distinct workflow-run completion summary:
an eligible run has at least one logical Task, and it succeeds only if every
logical Task completed successfully. A Wilson 95% proportion interval is
available only for an explicitly declared `INDEPENDENT_REPLICATIONS` design.
That interval is an interval for the declared simulator randomization process,
not a real-cloud reliability, availability, or failure-calibration claim.

`successfulWorkflowLogicalCompletionSeconds` is deliberately a conditional
summary over successful workflow runs only. Failed or incomplete runs do not
contribute their simulation-end time as a surrogate completion time. When no
workflow run succeeds, this summary is explicitly unavailable. Consequently,
this conditional completion-time sample must be read beside the workflow-run
completion rate; it is not by itself a fair unconditional time comparison when
algorithms have different completion rates.

Processing cost sums all completed Job attempts. It is split into a CPU-envelope
component (which can include integral-MI stage-in) and a continuous decimal-MB
declared-file bandwidth component; it has no internal billing-rounding rule and
does not charge declared memory/storage price fields. It is therefore an
abstract model quantity, not a provider-price replay or separately priced
network cost. The summary reports candidate/baseline mean ratios for
simulation-end time and cost when the baseline mean is non-zero, plus
declared-baseline relative simulation-end speedup (`baseline mean / candidate
mean`) and improvement percentage when their denominators are non-zero. It
also reports candidate-minus-baseline logical-task completion-rate and
workflow-run completion-rate differences, both descriptively only. The speedup
is a descriptive comparison to the declared baseline, not serial speedup,
parallel efficiency, or an inferential effect estimate. Its comparison label
is deliberately machine-readable:

| Label | Meaning |
| --- | --- |
| `DESCRIPTIVE_ONLY_DETERMINISTIC` | Exact deterministic comparison, not sampling inference. |
| `UNPAIRED_INDEPENDENT_REPLICATIONS` | Independent-cell samples; the simulator computes per-cell continuous-metric mean intervals and workflow-run Wilson intervals, but no treatment-effect interval or test. A treatment-effect analysis requires preregistration. |
| `DESCRIPTIVE_ONLY_COMMON_ROOT_SEEDS_ARE_NOT_EVENT_KEYED_CRN` | Same root seeds occur in both cells; matched deltas are descriptive and the paired Wilcoxon signed-rank test (`getPairedSignificance()`) is available, but CRN-paired treatment-effect inference remains unavailable. |

Before a stochastic paper run, freeze the workload/platform matrix, candidate
set, primary metric, effect size of practical interest, minimum and maximum
replication counts, confidence-interval precision rule, multiple-comparison
method, and compute budget. The present code intentionally has no adaptive
stopping rule: do not stop based on a favorable interim result. A defensible
minimum is to continue until the preregistered precision target is met or the
maximum budget is reached, then report which condition occurred and all
executed seeds.

## Campaign Evidence

`ExperimentCampaignArtifactWriter.write(result, outputDirectory)` accepts only
a new or empty output directory. For every cell/replication it writes the
normal evidence bundle (`manifest.json`, `metrics.json`, `events.jsonl`) under
the campaign root, then creates `experiment-campaign-index.json` with only
relative paths, the complete plan seed declaration, per-run result summaries,
and workflow profiles. It validates that complete index before returning.

The campaign artifact is valid only after a read-only validation succeeds:

```sh
mvn -Pcore-exec -pl :workflowsim -am \
  -Dexec.mainClass=org.workflowsim.experiment.ExperimentCampaignValidator \
  -Dexec.args="/absolute/output/experiment-campaign-index.json" \
  compile exec:java
```

The validator checks index schema, path containment, duplicate cell/replication
records, every evidence sidecar/hash/event sequence through
`ExperimentArtifactValidator`, seed agreement with each manifest, workflow
profile task/edge counts, and reported job/simulation-end summary agreement.
The campaign `summary` is a writer-derived convenience aggregation over those
validated run reports; the current validator does not independently recompute
every aggregate field. It does not prove that a scenario is scientifically
representative, a platform is calibrated, or a selected algorithm is
semantically appropriate for the study.

Unretained campaign output, Maven `target`, generated manifests, result logs,
and scratch directories are intermediate files and must be removed after the
result has been inspected. Retained evidence needs a documented retention
decision and the exact validation command above.

## Data-Movement Contract

`SimulationConfig` carries one immutable `DataMovementModel` and records it in
each manifest. The compatibility default is
`LEGACY_WORKFLOWSIM_V1`; it retains historical transfer behavior exactly. The
new optional `FIXED_ENDPOINT_NO_CONTENTION_V1` requires three finite values:

| Parameter | Meaning |
| --- | --- |
| `accessLinkBandwidthMbPerSecond` | Per-transfer upper bound on the endpoint access link. |
| `accessLinkLatencySeconds` | Additive latency for each non-local real-input file. |
| `sourceEndpointBandwidthMbPerSecond` | Bandwidth cap for the external source endpoint. |

For a real input file of `B` bytes, its fixed-model duration is
`latency + B / 1,000,000 / bottleneckRate`. For `SHARED` storage the bottleneck
is the minimum of the configured access-link bandwidth and the maximum transfer
rate of the modeled shared storage. For `LOCAL` storage the simulator chooses
the fastest already registered replica: same-destination-VM copies cost zero;
the external source is bounded by source endpoint and destination VM bandwidth;
the shared endpoint is bounded by shared storage and destination VM bandwidth;
and a VM replica is bounded by source/destination VM bandwidth. Every candidate
is additionally bounded by the configured access link. The chosen input is
registered as a destination-VM replica.

Files required by one compute Job are summed serially. Different Jobs never
compete for storage capacity, network links, VM NIC queues, or packet paths.
The model has no topology, route selection, TCP behavior, data striping,
concurrent read/write contention, cache-eviction policy, or measured service
trace. Its fields are therefore abstract parameters, not hardware calibration.

The preExecution family (used by the LOCAL static-DAG tracks such as
`LOCAL_HEFT`/`LOCAL_CPOP`) models input transfers as pre-execution network
delays instead of extending the VM execution envelope: each real input file
transfers at `bytes / (1e6 × rate)` starting when its producer finishes
(SOURCE inputs start at dispatch), may overlap destination-VM busy time, and
VMs are occupied by compute only. Two contention variants share the same
window semantics while adding fluid max-min fair sharing over a wider resource
domain: `PRE_EXECUTION_TRANSFER_DELAY_WITH_CONTENTION_V1` (R2) fair-shares
each VM endpoint's bandwidth; `PRE_EXECUTION_TRANSFER_DELAY_WITH_FAT_TREE_CONTENTION_V1`
(R6) additionally fair-shares every shared link on a deterministic Al-Fares
k-Pod fat-tree route declared through `PlatformProfile.networkTopology`
(rate = minimum over endpoint and path-link shares). Under concurrent load the
runtime diverges from the contention-free plan in a documented, explainable
way (contention can only delay transfers). These models require static VM
mapping; the fat-tree model additionally requires the LOCAL file system, NONE
clustering, and disabled failure/overhead models, and the runner enforces a
bidirectional contract between the model and the topology declaration
(`SimulationConfig`/`SimulationRunner` reject other combinations).

The model decides a compute-attempt outcome at its Job-envelope completion
boundary. A declared output is committed to the replica catalog only for a
successful Task; an output from a failed Task never becomes a local or shared
input candidate for a dependent Job. This prevents a failed retry parent from
creating a false local-data hit, but it does not model partial writes,
transactional storage, mid-attempt failures, or real recovery I/O.

`DATA` remains an online byte-locality heuristic: it chooses a compatible VM
with the fewest required non-local input bytes and does not use fixed-model
latency or rate. It must not be described as a bandwidth-aware/network-aware
algorithm. `SHARED_STORAGE_HEFT`, `SHARED_STORAGE_CPOP`, `SHARED_STORAGE_DLS`, and
`SHARED_STORAGE_ETF`, and `SHARED_STORAGE_PEFT` currently require
the legacy movement model because their planning estimates are aligned only to
that execution model. Configuring any of these planners with the fixed endpoint model
fails before a run; this is intentional rather than an unsupported silent
approximation.

Each `DATA_STAGE_IN_MODELED` event and `SimulationMetrics` record the transfer
model observations: model-event count, modeled real-input demand file count,
total required input-demand bytes, total modeled transfer seconds, and mean
modeled transfer seconds. The demand count/bytes do not mean that that amount
crossed a physical link: a local replica can give a zero modeled transfer
delay, and the simulator has no link-level traffic ledger (the R6 fat-tree
contention events additionally record `fatTreePathLinkCount`, the number of
routed path links per stage-in group). These are model
observations. They are not measured I/O throughput, network utilization,
storage utilization, or a trace-validation result.

The CloudSim `JobOutcome` is an end-to-end Job envelope: the Cloudlet scheduler
converts the requested stage-in duration to an integral MI addition. The
corresponding `TASK_EXECUTION_MODELED` event records a logical Task compute
window after that effective delay; its first Task carries
`modeledStageInSecondsBeforeTask`, while
`requestedDataStageInSecondsForJob` retains the raw data-model value and later
clustered Tasks carry zero effective delay. A `TaskOutcome` is marked exact only
if its one-Task compute window equals the completed Job envelope. This prevents
a task-level CPU window from being mistaken for the broader data-plus-compute
Job timing and makes CloudSim's quantization visible rather than hidden.

`simulationEndSeconds` (the historical `makespanSeconds`) is the CloudSim
simulation-end clock. By contrast, `logicalTaskCompletionSeconds` is available
only for a fully successful logical workflow and is the maximum first-successful
Job-envelope finish among its source Tasks. The non-negative difference is
`terminalLifecycleTailSeconds`; it can include modeled post-delay or other
terminal lifecycle events and is not a measured scheduler-overhead quantity.

## Minimal Study Matrix

For a first use of the new capability, keep the question narrow and use a
single algorithm decision layer. A sufficient non-energy scheduling study has:

1. Two frozen workflow inputs with recorded hashes and profiles, covering a
   small and a larger graph from the same input contract.
2. One homogeneous and one heterogeneous platform profile with equal VM count,
   one VM per host unless host contention is the question under study.
3. One baseline plus the eligible algorithms from the same decision layer.
   Do not pool online ready-job schedulers, independent-task static planners,
   and controlled shared-storage DAG planners.
4. One fixed data-movement model per comparison group. Vary the model only as
   an explicit sensitivity factor, never as an unrecorded scheduler difference.
5. Primary simulation-end time; secondary workflow-run completion, conditional
   successful-workflow logical completion time, completed/retry/failed attempt
   evidence, CPU-envelope and declared-file cost components, modeled input
   demand and stage-in seconds, logical workflow profile, and explicit failure
   state. Keep wall-clock decision time, provider cost, resource utilization,
   energy, and real-cloud prediction out of the main claim unless separately
   designed and calibrated.
6. Deterministic exact comparisons for no-randomness scenarios, or the frozen
   independent-replication protocol above for stochastic scenarios.

This is sufficient to establish behavior under declared abstract models. It is
not sufficient to claim algorithm superiority across all workflows, real
systems, cloud providers, or network conditions.

## Fat-tree × Scheduling Campaign (R7)

The first joint contention/scheduling campaign answers "how does network
contention change the relative ranking of scheduling algorithms?" across the
three preExecution movement models (V1 / R2 / R6 fat-tree) and four static
planners (LOCAL_HEFT / LOCAL_CPOP / PSO / RANDOM) on 10 Pegasus DAX instances
(montage/sipht excluded: their raw DAX files contain inconsistent same-name
file sizes that the LOCAL communication planning family rejects).

It uses a dedicated executor
(`org.workflowsim.experiments.fattree.FatTreeSchedulingCampaignExecutor`,
360 runs: 120 main matrix + 120 three-host sensitivity + 120 four-host
structural sensitivity, fixed seed 91, derived costs without an explicit cost
matrix) rather than `ExperimentPlan`, because pairing is defined across DAG
instances rather than over seed-keyed replications. Its measured results and
ranking-flip conclusions live in `FATTREE_SCHEDULING_RESULTS.md`; the design
(fixtures, DAG partition, sensitivity axes, acceptance) in
`FATTREE_SCHEDULING_CAMPAIGN.md`. Contracts are locked by
`FatTreeCampaignGoldenIntegrationTest`,
`FatTreeCampaignDagCompatibilityIntegrationTest`, and the simulator-side
`FatTreePlannerCompatibilityIntegrationTest`.

**R8 re-record (2026-09-16).** The R8 audit fixed a fat-tree link-capacity
unit bug (declared MB/s were divided by 8, so links physically ran at 1/8 of
their declared bandwidth), a contention-payload accounting bug (bytes of
already-local files were charged to contention transfers), and the paired
Wilcoxon zero-difference handling. All 360 runs were re-executed and the
frozen artifacts in `experiments/studies/fattree-scheduling-campaign/` were
regenerated; the original R7 artifacts remain in git history (PR #12 merge)
and every R6 figure quoted from them is void. Key re-recorded facts: with the
declared link bandwidth (1.0 MB/s) equal to the VM endpoint bandwidth, the
fat-tree layer never binds, R6 is bit-for-bit identical to R2 across all
10 DAGs × 4 planners, and every sensitivity/structural axis (including the
A4 link-bandwidth axis) degenerates to identity; the per-DAG winner flips
(cybershake-n50 HEFT→PSO, cybershake-n100 CPOP→PSO, and newly
inspiral-n50 HEFT→CPOP) survive but are endpoint-contention (R2) phenomena.
Model effectiveness under binding links is locked by the simulator slow-link
probe (0.25 MB/s links → makespan 588.1 > R2's 284.1). See
`FATTREE_SCHEDULING_RESULTS.md` §0.1/§0.7/§0.8 and
`docs/advanced/COMPREHENSIVE_AUDIT_R8.md`.

## Deferred Work

The next network layer should be designed as a separate model family, not
bolted onto this endpoint fallback. It needs explicit topology/link objects,
transfer requests, concurrent link/storage arbitration, deterministic event
ordering, queueing semantics, trace/calibration provenance, and a compatible
candidate-VM communication-cost interface for static DAG planners. Only after
that contract exists should predictive static algorithms be extended to their
original candidate-VM-dependent communication-cost semantics. The maintained
`SHARED_STORAGE_PEFT` is instead an explicit controlled-model adaptation whose
communication term is zero; it must not be relabelled as a topology-aware PEFT
implementation.
