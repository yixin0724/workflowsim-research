# Quick Start: Run an Experiment and Read Its Report

With **JDK 17+ and Maven 3.6.3+**, run the following commands from the repository root.
Workbench reads the JSON configuration directly — no new Java class is required, and viewing
the report needs no Node, Python, or web server.

**English** · [简体中文](<QUICK_START.zh.md>)

## 1. Validate the example configuration

The [online scheduling example](<../../experiments/configs/online-comparison.json>) uses the
repository's bundled Epigenomics inputs and compares schedulers within one decision layer.

```bash
mvn -pl :workflowsim-workbench -am compile exec:java \
  -Dexec.mainClass=org.workflowsim.experiments.workbench.Workbench \
  -Dexec.args="validate experiments/configs/online-comparison.json"
```

The preflight parses the inputs and checks the configuration combination; it does not run any
simulation. On success it prints `WORKBENCH_VALIDATED`. The Maven compile itself produces
build output.

## 2. Run the experiment

```bash
mvn -pl :workflowsim-workbench -am compile exec:java \
  -Dexec.mainClass=org.workflowsim.experiments.workbench.Workbench \
  -Dexec.args="run experiments/configs/online-comparison.json output/workbench"
```

Each run creates its own `experiment-<UUID>` subdirectory and never overwrites a previous
experiment. On completion the console prints `WORKBENCH_REPORT` with the report path; if any
run failed, the command exits non-zero while the report retains the diagnostics.

## 3. Open the report

Open the `report.html` path printed by the console in a file manager. The report is
self-contained and offline-viewable — algorithms, seeds, task timelines, VM utilization, and
the dependency graph — with no CDN loading.

The `index.html` in the output root browses the experiment history. On macOS:

```bash
open output/workbench/index.html
```

When editing configurations, note that `workflowPaths` resolve relative to **the directory
containing the configuration file**. If you copy a configuration elsewhere, adjust the input
paths accordingly.

## Next steps

- [Workbench guide](<WORKBENCH.md>) (Chinese; English translation pass planned): model and
  evidence-mode selection, report regeneration, history.
- [Java API guide](<CODE_CONFIG_EXPERIMENTS.md>) (Chinese; English translation pass planned):
  custom platforms, failure models, or external policies.
- [Algorithm catalog](<../algorithms/CATALOG.md>) (Chinese) and
  [dataset notes](<../../datasets/README.md>) (Chinese): choosing comparable algorithms and inputs.
- [Build and verification](<BUILD.md>), [IDEA setup](<IDEA_SETUP.md>): development environment
  and quality checks.

Simulation metrics describe the declared abstract model; completing this example is not
evidence of real-platform calibration or of general algorithm superiority.
