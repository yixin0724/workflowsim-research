# Build, Test, and Verification

The commands below run from the repository root. For day-to-day experiment runs, start from [Quick start](<QUICK_START.md>); this document concentrates on the development and verification tooling and does not require new users to run every quality check first.

**English** · [简体中文](<BUILD.zh.md>)

## Environment and modules

| Tool | Requirement | Purpose |
| --- | --- | --- |
| JDK | 17+ | Maven builds and the Java simulation |
| Maven | 3.6.3+ | Compilation, tests, Javadoc, and run entry points |
| Python | 3.9+, standard library only | Independent evidence checkers and their self-tests; CI uses 3.12 |
| Node.js | 20+ | Offline report browser acceptance; CI uses 22 |
| Chrome or Playwright Chromium | an installed compatible browser | Browser acceptance, not a simulation dependency |

The [root POM](<../../pom.xml>) includes both modules by default; no `experiments` profile needs to be enabled:

| Module | Maven coordinates | Responsibility |
| --- | --- | --- |
| core simulator | `org.workflowsim:workflowsim:1.0` | Models, runners, metrics, and the common evidence API |
| experiments module | `org.workflowsim:workflowsim-workbench:1.0` | Workbench, examples, and research drivers |

Compilation uses `--release 8`, so artifacts stay compatible with the Java 8 API; this does not lower the JDK requirement for building.

```bash
java -version
mvn -v
```

## Java build and tests

| Task | Command |
| --- | --- |
| Compile both modules | `mvn compile` |
| Unit/semantic tests of both modules | `mvn test` |
| Unit, integration, and coverage gates of both modules | `mvn verify` |
| Verify after a clean build, including core API documentation | `mvn -Pjavadoc clean verify`, or `make audit` |
| Verify the core only | `mvn -pl :workflowsim verify` |
| Compile the core only | `mvn -pl :workflowsim compile` |
| Clean the build output of both modules | `mvn clean` |

Surefire runs the unit/semantic tests in the `test` phase and excludes `*IntegrationTest.java`; Failsafe runs the integration tests in the `integration-test`/`verify` phases. Examples of targeted checks:

```bash
mvn -pl :workflowsim -Dtest=TaskCostMatrixTest test
mvn -pl :workflowsim \
  '-Dtest=TaskCostMatrixTest#getCostSecondsReturnsRegisteredEntries' test
mvn -pl :workflowsim -Dit.test=SimulationEvidenceIntegrationTest verify
```

The last command still runs the core unit tests and the `verify` gate; it only restricts the integration test selection. Targeted tests are not a substitute for full verification.

Reports are located in each module's `target/surefire-reports/`, `target/failsafe-reports/`, `target/site/jacoco/`, and `target/site/jacoco-it/`. Judge success by the actual exit status and the reports, not by a fixed test count. The JaCoCo thresholds are maintained in the [core POM](<../../simulator/pom.xml>) and the [experiments POM](<../../experiments/pom.xml>).

A standard checkout contains the small inputs the gates require; tests fail when they are missing. The full large corpora are not a default prerequisite — see the [dataset notes](<../../datasets/README.md>). `mvn clean` removes only build directories; it does not remove custom experiment outputs or retained evidence.

## Python independent checker self-tests

These tests do not import the Java production algorithms and are not a substitute for the Maven tests. Run all eight suites in full:

```bash
PYTHONDONTWRITEBYTECODE=1 python3 scripts/test-study-verifiers.py
PYTHONDONTWRITEBYTECODE=1 python3 scripts/test-network-ledger.py
PYTHONDONTWRITEBYTECODE=1 python3 scripts/test-file-lifecycle.py
PYTHONDONTWRITEBYTECODE=1 python3 scripts/test-file-lifecycle-context.py
PYTHONDONTWRITEBYTECODE=1 python3 scripts/test-storage-lifecycle.py
PYTHONDONTWRITEBYTECODE=1 python3 scripts/test-storage-lifecycle-context.py
PYTHONDONTWRITEBYTECODE=1 python3 scripts/test-dataflow-assignment.py
PYTHONDONTWRITEBYTECODE=1 python3 scripts/test-dataflow-profile.py
```

The self-tests verify the checkers on positive and negative cases; they do not prove that any real study has passed an audit. The [CI configuration](<../../.github/workflows/ci.yml>) additionally generates Java V1 network, V2 file, V3 storage, and online assignment evidence, then checks the actual artifacts with Python; that file is authoritative for the corresponding production fixtures and cross-language acceptance commands. `mvn verify` covers the Java gates only and does not include these Python checks.

## Mutation gate

The registered-mutation zero-survival gate verifies the test battery's killing power against known semantic breaks (the registry, how to run it, and the rules for extending the table are documented in [Mutation gate](<../advanced/MUTATION_GATE.md>), in English). A full run takes about 3–5 minutes, requires a clean working tree, and must run exclusively (not concurrently with other Maven builds):

```bash
python3 -B scripts/mutation-gate.py --output <新报告.json>
python3 -B scripts/mutation-gate.py --output <新报告.json> --dry-run
PYTHONDONTWRITEBYTECODE=1 python3 scripts/test-mutation-gate.py
```

Exit codes: 0, all mutants killed; 1, survivors or a build failure; 2, anchor drift (the ledger must be fixed first). Dry-run mode and the self-test do not invoke Maven.

## Documentation checks

```bash
python3 -B scripts/test-check-docs.py
python3 -B scripts/check-docs.py
# 需要机器可读诊断时：
python3 -B scripts/check-docs.py --json
```

The checker discovers existing project Markdown through Git metadata, excluding dependencies, build outputs, and local experiment results; it validates local targets, Markdown anchors, and source-text line numbers, and it does not access the network. Missing targets, wrong anchors, or unsupported reference forms produce diagnostics with file/line positions and a non-zero exit. Semantics and commands must still be checked against the source code; the rules are in [documentation and source-comment requirements](<../advanced/CODE_STYLE.md>).

## Offline report browser acceptance

The checker opens genuinely generated reports via `file://`; no server is started. Dependency versions are pinned in the [browser dependency manifest](<../../scripts/package.json>) and the [lock file](<../../scripts/package-lock.json>).

```bash
npm ci --prefix scripts --ignore-scripts --no-audit --no-fund

# 已有可用 Chrome 时可省略；Linux CI 使用 install --with-deps chromium。
scripts/node_modules/.bin/playwright-core install chromium

mvn -pl :workflowsim-workbench -am test-compile exec:java \
  -Dexec.mainClass=org.workflowsim.experiments.workbench.WorkbenchBrowserFixtures \
  -Dexec.classpathScope=test \
  '-Dexec.args="/absolute/checkout" "/absolute/new-browser-output"'

node scripts/verify-report.cjs /absolute/new-browser-output/browser-fixtures.json
node scripts/test-report-checker.cjs /absolute/new-browser-output/browser-fixtures.json
```

Replace the two absolute-path placeholders; the output directory must not already exist. This test entry point lives in test sources, so it needs `test-compile` and `-Dexec.classpathScope=test`.

The matrix covers algorithm/seed tags, multiple seeds, single-report regeneration, failure states, and large graphs, plus the V1/V2/V3 and online action panels. It checks run selection, VM/task filtering, dependency focus, graph truncation, the 390px layout, exact numbers, no browser errors, and no extra network or file reads; counterexample scripts confirm that these checks can actually fail. Java tests do not execute browser JavaScript and cannot replace this step.

On macOS, an installed Google Chrome is preferred; otherwise Playwright Chromium is used. On any system, the browser executable can be specified via `WORKFLOWSIM_CHROME`:

```bash
WORKFLOWSIM_CHROME="/absolute/path/to/chrome" \
  node scripts/verify-report.cjs /absolute/experiment/report.html
```

The [report checker](<../../scripts/verify-report.cjs>) also accepts an optional screenshot argument: a screenshot file path for a single report, or a screenshot directory for the matrix. Node and the browser are used only for acceptance; they are not required for generating reports, and reading a report needs only an ordinary browser.

## Running Java entry points

Experiment entry points use `-pl :workflowsim-workbench -am compile exec:java`, with the Reactor building the current core dependency first and then executing. Do not omit `compile` and call `exec:java` directly: it may resolve a stale core JAR from the local Maven repository, leaving model enums that already exist in the current source unrecognized at run time. If that happens, rebuild the correct module/classpath instead of rewriting model labels in the evidence. For the four Workbench commands, see the [Workbench guide](<WORKBENCH.md>); for writing your own Java classes, see the [Java API guide](<CODE_CONFIG_EXPERIMENTS.md>).

An example using one of the JSON inputs bundled with the repository:

```bash
mvn -pl :workflowsim-workbench -am compile exec:java \
  -Dexec.mainClass=org.workflowsim.examples.wfcommons.WfCommonsSimulationExample1 \
  -Dexec.args="datasets/wfformat/montage/n100/montage-100-000.json"
```

## Evidence verification commands

The core module skips `exec:java` by default; enable `core-exec` to run the core validators:

```bash
# 单次运行证据包
mvn -Pcore-exec -pl :workflowsim -am compile exec:java \
  -Dexec.mainClass=org.workflowsim.experiment.ExperimentArtifactValidator \
  '-Dexec.args="/absolute/output/run.manifest.json"'

# 通用 campaign index
mvn -Pcore-exec -pl :workflowsim -am compile exec:java \
  -Dexec.mainClass=org.workflowsim.experiment.ExperimentCampaignValidator \
  '-Dexec.args="/absolute/output/experiment-campaign-index.json"'

# 独立检查 V1/V2/V3 证据及适用的在线绑定上下文
PYTHONDONTWRITEBYTECODE=1 python3 scripts/verify-network-ledger.py \
  /absolute/output/run.manifest.json --json
```

The independent network checker is meant for evidence packages with matching network/lifecycle records enabled; a plain OFF evidence package should not be treated as a complete capture input for it. The validators only read the target evidence, but the Maven compilation in these commands still writes build output. A successful verification proves only the structural, hash, context, or semantic constraints within its declared scope; it does not prove algorithmic optimality or calibration to a real platform. For study metrics and statistical checks, see also the [independent audit tooling](<../../scripts/STUDY_AUDIT.md>).

## API documentation and environment troubleshooting

```bash
mvn -pl :workflowsim -Pjavadoc verify
# macOS；其他系统在文件管理器中打开相同路径。
open simulator/target/reports/apidocs/index.html
```

Javadoc checks the maintained WorkflowSim API and does not include upstream CloudSim comments. For IDE classpath or import problems, see the [IDEA setup](<IDEA_SETUP.md>). When an input does not exist, first distinguish the Java entry point's working directory from the relative-path base of the Workbench configuration file; do not silence the error by switching to an undeclared model or skipping validation.
