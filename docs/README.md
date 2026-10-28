# WorkflowSim Documentation Center

This center maintains the current usage guides, model contracts, and verification rules.
First-time users should read quick start → Workbench in order; consult the code-configuration
guide only when custom Java experiments are needed. Development rounds, completion lists,
and superseded audit drafts are not long-term entry points.

## Getting started

| Need | Document |
|---|---|
| Run an existing configuration and open its report | [Quick start (EN)](<getting-started/QUICK_START.md>) · [快速开始（中文）](<getting-started/QUICK_START.md>) |
| Configure, validate, run, and rebuild offline reports | [Workbench guide](<getting-started/WORKBENCH.md>) |
| Build configurations, platforms, and experiments in Java | [Code-configuration guide](<getting-started/CODE_CONFIG_EXPERIMENTS.md>) |
| Maven, Python, browser, and Javadoc checks | [Build and verification](<getting-started/BUILD.md>) |
| Configure IDEA modules, classpaths, and working directories | [IDEA setup](<getting-started/IDEA_SETUP.md>) |

## Choosing algorithms, inputs, and models

- [Algorithm catalog](<algorithms/CATALOG.md>): decision layers, available labels, combination limits, and legacy-name migration.
- [Algorithm and metrics contracts](<algorithms/CONTRACTS.md>): computation volume, communication, ordering, primary-paper correspondence, metric conventions, and verification boundaries.
- [Paper-metrics conventions card](<algorithms/METRICS_CARD.md>): per-metric literature-convention differences and citation advice.
- [Workflow inputs and datasets](<../datasets/README.md>): repository-bundled inputs, optional corpora, provenance, conversion, and format constraints.
- [Dataflow and network capability matrix](<advanced/DATAFLOW_CAPABILITY_MATRIX.md>): physical models, storage, online assignment, and unimplemented capabilities.
- [Energy dimension design](<advanced/ENERGY_DIMENSION_DESIGN.md>): requirements analysis, power-model architecture, metric surface, and the honesty scoping of the planned energy extension.
- [Carbon dimension design](<advanced/CARBON_DIMENSION_DESIGN.md>): the two
  literature carbon accountings (intensity multiplier vs green-budget excess),
  the v1 architecture riding the audited energy chain, and the deliberate omissions.
- [Fat-tree topology and shared model](<advanced/FAT_TREE_DESIGN.md>): structure, placement, deterministic routing, and capacity-allocation boundaries.

## Dataflow and evidence reference

| Topic | Document |
|---|---|
| File identity, true sources, replica visibility, and the CPU input barrier | [Coherent file dataflow V2](<advanced/COHERENT_DATAFLOW_V2_CONTRACT.md>) |
| Bounded SOURCE, post-commit reads, and required output write-back | [Storage dataflow V3](<advanced/STORAGE_DATAFLOW_V3_CONTRACT.md>) |
| VM selection at control-ready, nominal scores, and action certification scope | [Online target assignment](<advanced/ONLINE_DATAFLOW_ASSIGNMENT_V1_CONTRACT.md>) |
| Exact fields and validation rules of the per-file sidecar | [File lifecycle format](<advanced/FILE_LIFECYCLE_V2_FORMAT.md>) |
| Format, roles, and strict reading of the V1 group ledger | [Network ledger format](<advanced/NETWORK_LEDGER_FORMAT.md>) |
| V1 stable-allocation certificates, exact accounting, and truncated prefixes | [Ledger validation and metrics](<advanced/NETWORK_LEDGER_VALIDATION.md>) |
| Java-independent V1/V2/V3 and online check entry points | [Independent Python evidence verification](<advanced/NETWORK_LEDGER_PYTHON.md>) |
| Same verified snapshot, exact text, and bounded previews | [Offline network and action reports](<advanced/NETWORK_REPORT_DISPLAY.md>) |

## Experiments, replay, and statistics

- [Reproducibility contract](<experiments/REPRODUCIBILITY.md>): randomness, time/work, cost, reference values, and deadline semantics.
- [Campaign experiments and network studies](<experiments/CAMPAIGNS.md>): scenario matrices, seeds, parameter control, execution, and statistical methods.
- [Evidence replay and difference comparison](<experiments/RERUN_DIFF_CONTRACT.md>): configuration rebuilding, verdicts, exact core quantities, and the small set of volatile fields.
- [Protocol identity and historical-evidence boundaries](<experiments/NETWORK_STUDY_PROTOCOL_REVISIONS.md>): which legacy identities remain readable and why legacy results are not conclusions about the current implementation.
- [Independent study-audit tooling](<../scripts/STUDY_AUDIT.md>): the actual scope of general evidence/statistics checks, distinguished from dataflow-specific certificates.

## Standard benchmark

- [Standard Benchmark `benchmark-standard-v1`](<benchmark/STANDARD_BENCHMARK.md>): the frozen
  public comparison of the maintained algorithm corpus — 160 audited runs, each verifiable
  by a third party with one Python command; includes the power variant
  `benchmark-standard-power-v1`, the carbon variant `benchmark-standard-carbon-v1`
  (intensity grid + budget-model demonstration family), and the power variant's statistical
  analysis
  [Energy Portrait of the Algorithm Corpus](<benchmark/ENERGY_PORTRAIT.md>) —
  the first auditable energy study of the platform's algorithm corpus
  (conservation on all 160 runs, one Holm-significant EDP finding).

## Platform history

- [Platform engineering history](<history/PLATFORM_ENGINEERING.md>): the single narrative record of the correctness, uplift, regularization, and observability waves.

## Research, positioning and quality

- [Science position statement](<SCIENCE-POSITION.md>): research identity, validity standard (internal validity), competitive positioning, and threats to validity.
- The durable record of those engineering rounds lives in the [platform engineering history](<history/PLATFORM_ENGINEERING.md>).

## Maintenance

- [Documentation and source-comment conventions](<advanced/CODE_STYLE.md>): content layering, link/example requirements, source protection, and the document check command.
- [Mutation gate](<advanced/MUTATION_GATE.md>): registered-mutant zero-survival gate, killer-test ledger, and growth rules.
- [Dataflow state and performance measurement](<advanced/DATAFLOW_STATE_COPY_PERFORMANCE.md>): immutable history/active working set, measurement conventions, and repeatable comparisons.
- [Experiment module notes](<../experiments/README.md>): module responsibilities and maintenance entry point.

## Retention policy for historical material

Original numbers, preregistrations, input provenance, and necessary compatibility protocols
of historical experiments are retained in their data/reference directories with explicit
versions and limitations; they are neither current user guidance nor new scientific
conclusions. To identify retained studies, see the
[study data notes](<../experiments/studies/README.md>); the rest of the development history
is available from Git and is not repeated in this navigation.
