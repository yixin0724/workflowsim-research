# Experiment Module

**English** · [简体中文](<README.zh.md>)

This module's Maven coordinate is `org.workflowsim:workflowsim-workbench:1.0`, depending on
the core simulator. It carries the Workbench, teaching examples, research drivers, and their
tests; the [root POM](<../pom.xml>) builds the two modules by default with no extra
profile.

## Running experiments

Run the maintained JSON configuration entry point from the project root:

```bash
mvn -pl :workflowsim-workbench -am compile exec:java \
  -Dexec.mainClass=org.workflowsim.experiments.workbench.Workbench \
  -Dexec.args="run experiments/configs/online-comparison.json output/workbench"
```

Every experiment uses a dedicated output directory, and the console prints the offline
report location. For a first run, see the [Quick start](<../docs/getting-started/QUICK_START.md>);
configuration, preflight, report regeneration, and failure handling are covered in the
[Workbench guide](<../docs/getting-started/WORKBENCH.md>).

## Where code and material belong

| Content | Location and boundary |
| --- | --- |
| Workbench and JSON configurations | `src/main/java/org/workflowsim/experiments/workbench/`, `configs/`; for day-to-day experiments and offline reports |
| Examples and custom experiment classes | the corresponding packages under `src/main/java/org/workflowsim/`; the low-level compatibility APIs used by examples do not automatically carry the standard runner's evidence contract |
| Study-specific drivers and tests | Maven standard source and test directories; comparison matrices and study identity must be explicit |
| Study protocols and retention notes | `studies/`; documents only — no directly compilable Java files |

Stable, reusable simulation semantics, configuration, metrics, and the common evidence API
belong to the core module; the core must not depend on the experiment module for a single
study's convenience. For Java-level customization, use the runner and evidence writers
described in the [Java API guide](<../docs/getting-started/CODE_CONFIG_EXPERIMENTS.md>).

## Research and verification

A new study first declares input hashes, platform and model, same-decision-layer
comparison algorithms, randomization design, metrics, statistical methods, stopping
conditions, and an output retention policy; see the
[study directory conventions](<studies/README.md>).

```bash
mvn -Pjavadoc clean verify
```

That command verifies the two Java modules and the core Javadoc. The independent Python
checks and the [offline report browser acceptance](<../docs/getting-started/BUILD.md#offline-report-browser-acceptance>)
run separately; the full tool requirements are in
[build and verification](<../docs/getting-started/BUILD.md>).

Input provenance and the standard checkout scope are defined in the
[dataset notes](<../datasets/README.md>). Build output and experiment artifacts are not
source; only evidence retained under an explicit study protocol — with recorded
provenance and verification commands — qualifies as a long-term deliverable.
