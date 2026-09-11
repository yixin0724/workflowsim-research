# 冻结参考基线结果（P7 历史记录）

## Evidence Status

This is an initial P7-C result record for the abstract WorkflowSim model. It is
not a real-cloud experiment, a WfCommons trace replay, a calibrated cost study,
or a cross-workflow-family conclusion.

The workspace was not a Git repository during this run and no separate source
archive identifier was supplied. The values below are therefore reproducible
execution evidence for this workspace state, but are not a release-grade
scientific record until the user selects a source archive or release identity.

The current 20-cell matrix was re-executed after the P8 execution-timing
correction. Its v2 evidence bundles (`manifest.json`, `metrics.json`, and
`events.jsonl`) and index passed the then-available P7 index validation before
their temporary directory was removed, in accordance with the workspace cleanup
rule. This summary retains the validated material facts rather than retaining
intermediate manifests and logs. The workspace is still not a Git repository,
so the local source-tree fingerprint in those bundles is not a release archive
or a release identifier.

This historical result record predates the two-module migration and the v3
artifact-identity contract. It remains evidence for the abstract model state
that produced it, but it must not be described as a new v3 P7/reference run.
New P7 runs use `org.workflowsim.experiments.reference.p7.P7BaselineExecutor`,
an explicit absolute dataset root, and v3 artifacts. The current
`P7EvidenceIndexValidator` retains read-only v2 compatibility for complete P7
matrices so this historical record remains auditable.

## Frozen Inputs and Conditions

| Input | Expected tasks | SHA-256 |
| --- | ---: | --- |
| `datasets/dax/epigenomics/n100/Epigenomics_100.dax` | 100 | `374521746417b18133682de21b654b84c32acde6332462c0ce916c4ba12c7f36` |
| `datasets/dax/epigenomics/n997/Epigenomics_997.dax` | 997 | `2ed853db24126750a1bf427b5731d5fb18b6d31baf3d2088c14b310478bed628` |

Every cell used the frozen P7 configuration from
`org.workflowsim.experiments.reference.p7.P7BaselineMatrix`: root seed
`20260901`, reference MIPS `1000.0`, runtime scale `1.0`, `SHARED` storage,
no clustering, no overhead, no failures, `PlanningAlgorithm.INVALID`, and
`SPACE_SHARED` VMs with one VM per Host. The paths in the table retain their
historical repository-relative notation; the current executor resolves the same
inputs as `dax/...` logical paths below an explicit absolute dataset root.

H0 uses all 1000 MIPS VMs. H1 uses equal counts of 500 and 1500 MIPS VMs,
retaining a mean VM capacity of 1000 MIPS. The n100 scenarios use 10 VMs; the
n997 scenarios use 50 VMs.

## Run Completeness

The executor completed 20 of 20 planned cells. Every n100 run returned 101
successful Jobs and every n997 run returned 998 successful Jobs: one more than
the parsed Task count because the model emits a stage-in Job. There were zero
failed Jobs in all cells.

## Makespan Results

Values are simulated makespan units. They are shown to three decimal places;
the executor retained full double precision in each manifest.

| Scenario | FCFS | Ready-batch RR | Ready-batch MCT | Ready-batch Min-Min | Ready-batch Max-Min |
| --- | ---: | ---: | ---: | ---: | ---: |
| Epigenomics n100, H0 | 54460.681 | 54460.681 | 54460.681 | 60988.007 | 60662.621 |
| Epigenomics n100, H1 | 68995.307 | 72345.640 | 52076.997 | 79812.430 | 80717.935 |
| Epigenomics n997, H0 | 99294.929 | 99294.929 | 99294.929 | 101515.737 | 104594.926 |
| Epigenomics n997, H1 | 117362.463 | 136706.129 | 116997.487 | 120779.425 | 110696.902 |

## Model-Bounded Reading

- On H0, FCFS, ready-batch Round Robin, and ready-batch MCT tie in both sizes.
  This is an observed result of the frozen homogeneous model, not proof that
  the algorithms are equivalent in a real scheduler.
- On Epigenomics n100/H1, ready-batch MCT is the lowest makespan in this matrix:
  52076.997 versus FCFS 68995.307, a 24.521 percent lower model makespan.
- On Epigenomics n997/H1, ready-batch Max-Min is the lowest makespan:
  110696.902 versus FCFS 117362.463, a 5.679 percent lower model makespan.
- No hypothesis test is reported. The primary matrix is deterministic, so
  repeating an identical cell would check reproducibility rather than estimate
  an independent performance distribution.

## What This Does Not Establish

The result does not establish cross-family superiority: under the current
strict DAX input contract, candidate CyberShake, Montage, Inspiral, and SIPHT
n100/n1000 inputs have conflicting size declarations for repeated input file
names and were excluded rather than normalized. It also does not establish
real-cloud performance, network/data-locality benefit, failure robustness,
provider cost, utilization, energy, or WfCommons trace fidelity.

The next admissible expansion is not to pool incompatible inputs. It is to
qualify a second independent workflow family through the same strict input
contract, or to obtain explicit approval for a provenance-preserving
normalization policy and its dedicated regression tests.
