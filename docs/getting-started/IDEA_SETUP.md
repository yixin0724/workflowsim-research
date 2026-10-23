# IntelliJ IDEA Setup

**English** · [简体中文](<IDEA_SETUP.zh.md>)

## Importing the Maven project

1. Open the repository root or the [root POM](<../../pom.xml>) and import it as a Maven project.
2. Run **Reload All Maven Projects** in the Maven tool window.
3. Confirm that the two modules `workflowsim` and `workflowsim-workbench` are present. They belong to the default reactor; no additional `experiments` profile is required.
4. Confirm that each module's `src/main/java` and `src/test/java` are recognized as source and test source roots.

Icon colors vary with IDEA version and theme; judge the import by Maven synchronization, dependency resolution, code completion, and Run actions — do not mark directories manually as a substitute for a correct import.

## Configuring the JDK and Maven

- The Project SDK, the Maven importer, and the Maven runner use **JDK 17+**.
- Maven uses a **3.6.3+** local installation or a bundled IDE version that satisfies this requirement.
- The POM's `release=8` means the build output is compatible with the Java 8 API; it does not mean the project can be built with JDK 8.

## Running the Workbench

Open [Workbench](<../../experiments/src/main/java/org/workflowsim/experiments/workbench/Workbench.java>) and configure __Run → Edit Configurations → Application__ as:

| Setting | Value |
| --- | --- |
| Main class | `org.workflowsim.experiments.workbench.Workbench` |
| Use classpath of module | `workflowsim-workbench` |
| Working directory | `$PROJECT_DIR$`, i.e. the repository root |
| JRE | JDK 17+ |
| Program arguments | `validate experiments/configs/online-comparison.json` |

After the validation succeeds, change Program arguments to:

```text
run experiments/configs/online-comparison.json output/workbench
```

The configuration and output paths in the command arguments are relative to the working
directory; the `workflowPaths` inside a configuration resolve relative to the directory
containing that configuration file. Run and report rules are described in the
[Workbench guide](<WORKBENCH.md>).

## Running your own classes and tests

The [Java API guide](<CODE_CONFIG_EXPERIMENTS.md>) provides complete examples of custom
classes. Once created, set the Application main class to
`org.workflowsim.mystudy.MyExperiment`, keeping the experiment module classpath and the
project-root working directory; you may also right-click `main` and run it.

JUnit tests live in the corresponding module's `src/test/java/` and run via JUnit
configurations. Helper entries in test sources are not on the default `compile exec:java`
classpath; see [build and verification](<BUILD.md>) for the command-line usage.

## Troubleshooting

- **The experiment module is missing**: confirm you imported the root POM, not just the
  [core POM](<../../simulator/pom.xml>); clear the module's Ignored marker in the Maven tool
  window, then reload.
- **Dependencies show red or classes cannot be found**: first check the Maven sync result,
  build JDK, source locations, and runtime classpath; only after these are correct consider
  rebuilding IDE indexes.
- **Inputs not found**: check the working directory and the relative-path base inside the
  configuration; available inputs are listed in the
  [dataset notes](<../../datasets/README.md>).
- **The command line succeeds but the IDE fails**: compare JDK, module, working directory,
  and Program arguments item by item against the same configuration used in the
  [Quick start](<QUICK_START.md>).
