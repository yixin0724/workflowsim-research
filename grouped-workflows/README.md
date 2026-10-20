# Execution-package research module

This module extends WorkflowSim-yixin with fixed-request, sequential scientific-workflow execution packages. It shares the CloudSim event kernel and has a separate resource and file lifecycle.

Entry point: `org.workflowsim.grouped.GroupedWorkflowCli`. Commands are `evaluate`, `construct`, `optimize`, and `enumerate`, each taking an input JSON file and output JSON file; `server` accepts one JSON request per input line. The normalized schema is `r2-scenario-v1`.

Research contracts, normalized datasets, driver scripts, parameter specifications, independent verification, and the English manuscript are maintained at:

`/Users/yixin/project/workflow-schduleing/docs/second-paper/R2-grouping-placement`

Build from the simulator root using JDK 17 and Maven: `mvn -o verify`. The module targets Java 8 bytecode. `GroupedModelTest` verifies physical semantics and `OptimizerTest` checks optimization budgets and exact tiny references.

The output includes a full feasible plan, task/package timestamps, I/O service intervals, recorded objective and resource metrics, and search-budget accounting. The standard simulator runner retains its existing behavior.
