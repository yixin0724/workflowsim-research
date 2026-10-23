# Java API Experiment Guide

**English** · [简体中文](<CODE_CONFIG_EXPERIMENTS.zh.md>)

When you only need to change inputs, resources, or built-in algorithms, prefer the
[Workbench](<WORKBENCH.md>). This guide is for custom Java experiments, failure models, or
external policies; see the [algorithm catalog](<../algorithms/CATALOG.md>) for algorithm
selection and the [dataset notes](<../../datasets/README.md>) for input selection.

## A complete example

Create the following file in the experiment module. It is your own entry point, not a
repository-provided class:

```text
experiments/src/main/java/org/workflowsim/mystudy/MyExperiment.java
```

```java
package org.workflowsim.mystudy;

import java.nio.file.Path;
import java.nio.file.Paths;
import org.cloudbus.cloudsim.Log;
import org.workflowsim.data.DataMovementModel;
import org.workflowsim.experiment.ExperimentArtifactValidator;
import org.workflowsim.experiment.ExperimentArtifactWriter;
import org.workflowsim.experiment.SimulationReport;
import org.workflowsim.experiment.SimulationRunner;
import org.workflowsim.platform.PlatformProfile;
import org.workflowsim.platform.PlatformProfiles;
import org.workflowsim.utils.Parameters.SchedulingAlgorithm;
import org.workflowsim.utils.ReplicaCatalog;
import org.workflowsim.utils.SimulationConfig;

public final class MyExperiment {
    private MyExperiment() {
    }

    public static void main(String[] args) throws Exception {
        String workflow = "datasets/dax/epigenomics/n100/Epigenomics_100.dax";
        int vmCount = 4;
        long seed = 42L;

        SimulationConfig config = SimulationConfig.builder(workflow, vmCount)
                .schedulingAlgorithm(SchedulingAlgorithm.FCFS)
                .fileSystem(ReplicaCatalog.FileSystem.SHARED)
                .dataMovementModel(DataMovementModel.legacyWorkflowsimV1())
                .randomSeed(seed)
                .build();
        PlatformProfile platform = PlatformProfiles.homogeneousLocal("example", vmCount);

        Log.disable();
        SimulationReport report = new SimulationRunner().run(config, platform);
        System.out.println("Simulation end (s): " + report.getMakespan());
        System.out.println("Logical completion (s): " + report.getLogicalTaskCompletionSeconds());
        System.out.println("Successful/failed Job attempts: "
                + report.getSuccessfulJobs() + "/" + report.getFailedJobs());
        System.out.println("Mean VM utilization: "
                + report.getMetrics().getMeanVmModeledIntervalUtilization());

        Path output = Paths.get("output/my-study");
        String runId = "fcfs-seed" + seed;
        ExperimentArtifactWriter.ExperimentArtifacts artifacts =
                ExperimentArtifactWriter.write(report, output, runId);
        ExperimentArtifactValidator.validate(artifacts.getManifest());
        System.out.println("Validated manifest: " + artifacts.getManifest());
    }
}
```

Run from the repository root:

```bash
mvn -pl :workflowsim-workbench -am compile exec:java \
  -Dexec.mainClass=org.workflowsim.mystudy.MyExperiment
```

In IDEA, choose the experiment-module classpath with working directory `$PROJECT_DIR$`; see
the [IDEA setup](<IDEA_SETUP.md>). The Java API's relative input/output paths resolve against
the process working directory; they do not follow Workbench's
relative-to-configuration-file rule.

## Configuration and model boundaries

- [SimulationConfig](<../../simulator/src/main/java/org/workflowsim/utils/SimulationConfig.java>)
  is an immutable configuration; `build()` validates the combination, and the runner
  continues to validate inputs, platform, cost coverage, and execution prerequisites.
  Defaults are FCFS, no planning, SHARED, and legacy data movement with failure and overhead
  disabled; the seed should be declared explicitly.
- The standard runner requires NONE clustering, SPACE_SHARED VMs, and PE-compatible
  placement; each VM dispatches at most one Job at a time, which does not mean every task
  forever uses a single PE.
- `PlatformProfiles.homogeneousLocal` creates a platform with one VM per Host, each VM at
  1000 MIPS / 1 PE / 512 MB; the `Local` in the name does not configure the file system. The
  VM count in the configuration must match the platform.
- Offline planning requires STATIC dispatch. The explicit online assignment of coherent
  V2/V3 is a separate decision layer that may use INVALID/STATIC and must not be conflated
  with ordinary online CPU scheduling.
- V1, coherent V2, and bounded-storage V3 differ in data readiness, output commit, and
  recording semantics. Do not swap one enum while ignoring the file-system, topology, SOURCE
  storage, or evidence-mode requirements; see the
  [capability and combination matrix](<../advanced/DATAFLOW_CAPABILITY_MATRIX.md>).

## Common recipes

The recipes below apply to the base example independently and must not be stacked
unconditionally. The snippets are not standalone programs; put the shown imports at the file
head and the remaining code inside `main`, before invoking the runner.

### Shared-storage static planning

Add the import and replace the base example's `config` definition:

```java
import org.workflowsim.utils.Parameters.PlanningAlgorithm;

SimulationConfig config = SimulationConfig.builder(workflow, vmCount)
        .planningAlgorithm(PlanningAlgorithm.SHARED_STORAGE_HEFT)
        .schedulingAlgorithm(SchedulingAlgorithm.STATIC)
        .fileSystem(ReplicaCatalog.FileSystem.SHARED)
        .dataMovementModel(DataMovementModel.legacyWorkflowsimV1())
        .randomSeed(seed)
        .build();
```

Shared-storage planners require no failure/overhead, NONE clustering, and SPACE_SHARED, and
they reject task-cost matrices. LOCAL planners use a different V1 pre-execution model and
are not estimators for coherent V2/V3. See the
[algorithm contracts](<../algorithms/CONTRACTS.md>) for complete combinations and paper
adaptation scope.

### Heterogeneous resources, arrivals, and deadlines

Replace the platform definition to declare four heterogeneous VMs with different compute
capabilities:

```java
PlatformProfile platform = PlatformProfiles.heterogeneousLocal("heterogeneous",
        java.util.Arrays.asList(500.0, 1000.0, 2000.0, 1000.0));
```

Finer platforms, VM-to-Host binding, and abstract pricing use
[PlatformProfile](<../../simulator/src/main/java/org/workflowsim/platform/PlatformProfile.java>).
The VM-level cost mode requires an explicit price per VM; the cost is not a calibrated cloud
currency price.

Multiple workflows are declared through `SimulationConfig.builder(List<String>, vmCount)`
followed by `workflowArrivalSeconds(List<Double>)` with an equally long list of non-negative
seconds. Inputs and the arrival table are known before the run starts and released at their
declared times during execution.

`deadline(long)` counts from simulation time zero, with 0 meaning not requested; it only
feeds post-hoc SLA observation and does not alter scheduling, retries, or termination.
`config.withRandomSeed(...)` produces a new configuration differing only in the seed;
repeating deterministic algorithms over multiple seeds does not automatically produce
independent statistical samples.

### Explicit failure and retry budget

The following configuration declares abstract failure streams keyed by the actual VM IDs 0
through 3 of the base platform. When rows are keyed by VM ID, the generator mode must be
`FAILURE_VM` or `FAILURE_VM_JOB`, not `FAILURE_ALL`.

```java
import org.workflowsim.failure.FailureModelConfig;
import org.workflowsim.failure.FailureParameters;
import org.workflowsim.utils.DistributionGenerator.DistributionFamily;
import org.workflowsim.utils.DistributionSpec;

java.util.Map<Integer, DistributionSpec[]> rows =
        new java.util.LinkedHashMap<Integer, DistributionSpec[]>();
for (int vmId = 0; vmId < vmCount; vmId++) {
    rows.put(vmId, new DistributionSpec[]{
        DistributionSpec.of(DistributionFamily.WEIBULL, 1000.0, 1.0)
    });
}
FailureModelConfig failure = FailureModelConfig.builder()
        .clusteringAlgorithm(FailureParameters.FTCluteringAlgorithm.FTCLUSTERING_NOOP)
        .monitorMode(FailureParameters.FTCMonitor.MONITOR_NONE)
        .generatorMode(FailureParameters.FTCFailure.FAILURE_VM)
        .generatorSpecsByVmId(rows)
        .maxTotalRetryJobs(50)
        .build();
config = config.toBuilder().failureModel(failure).build();
```

The parameters are model inputs, not calibrated production failures; a single run is also
not guaranteed to contain any failure. The standard runner supports NOOP retries; the budget
must be positive and exhaustion fails explicitly. Failures are decided at attempt-completion
boundaries, and failed outputs do not become readable replicas. This recipe applies only to
model/algorithm combinations that allow failure; the authoritative constraints are
[FailureModelConfig](<../../simulator/src/main/java/org/workflowsim/failure/FailureModelConfig.java>)
and the [algorithm contracts](<../algorithms/CONTRACTS.md>).

### Plugging in an external policy

`RL_POLICY` registers a policy and runs an episode via
[RlEnvironment](<../../simulator/src/main/java/org/workflowsim/rl/RlEnvironment.java>)'s
`runEpisode(config, platform, policy)`, providing observations, actions, trajectories, and
rewards — not a learning algorithm. The online target assignment of coherent models is a
different interface from this RL hook; see the
[online assignment contract](<../advanced/ONLINE_DATAFLOW_ASSIGNMENT_V1_CONTRACT.md>).

## Reading and saving results

- `getMakespan()` is the simulation end time; `getLogicalTaskCompletionSeconds()` is only
  available when every logical task succeeds, and the two can differ by lifecycle tails such
  as output write-back.
- The successful/failed Job counts count attempts, including modeled stage-in Jobs; do not
  read them as logical-task counts. Waiting, slowdown, cost, and utilization each carry
  their own observation scope; see the [metrics contracts](<../algorithms/CONTRACTS.md>) and
  [SimulationMetrics](<../../simulator/src/main/java/org/workflowsim/experiment/SimulationMetrics.java>).
- `ExperimentArtifactWriter.write(...)` writes manifest v4, metrics v2, and events v1, with
  v3 provenance inside the manifest; matching network/lifecycle sidecars are written when
  recording is enabled. A manifest alone is not a complete evidence bundle.
- The same directory and runId replace artifacts of the same name. Individual files are
  written via temporary-then-replace, atomic where supported; the whole evidence bundle is
  not a cross-file transaction — validate after writing. Use distinct runIds or directories
  to retain multiple runs.
- Workbench can produce self-contained HTML from a validated manifest; the Java writer
  itself owns the evidence, not the reporting interface. Commands are in the
  [Workbench guide](<WORKBENCH.md>).

Record input hashes, configuration, platform, seed, and implementation version; do not treat
wall-clock durations as bit-reproducible simulation time. Whether experiment evidence is
retained must be decided explicitly; the [ignore rules](<../../.gitignore>) do not ignore
the entire `output/`. Build and independent verification commands are consolidated in the
[build guide](<BUILD.md>).
