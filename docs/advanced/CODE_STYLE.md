# Documentation and Source-Comment Standards

## Documentation Layering

- The [project front page](<../../README.md>) keeps only the project's purpose, run entry points, main capabilities, and research boundaries; phase reports, old rankings, and provisional acceptance records are not piled onto it.
- The [documentation hub](<../README.md>) is navigated by usage questions; one operation or definition should have a single maintained location wherever possible, with other pages linking to it.
- Getting-started guides explain how to use things, algorithm/model contracts state the implemented semantics, and the build guide states the verification commands. Development-process records, completion checklists, and superseded drafts are not kept as long-term usage documents; use the Git history when the past is needed.
- Protocols bound by runner fingerprints, paper-input attributions, and raw experiment numbers must not be deleted by mistake as ordinary old drafts. Historical research descriptions stay in their data directories, stating their version, evidence-retention status, and non-generalizable scope, and are not treated as present guides.

## Markdown Conventions

1. Each page uses one descriptive H1 title; titles carry no development-round, delivery-phase, star-rating, or "latest completed" labels.
2. The current implementation is authoritative. Interfaces, enums, format versions, and protocol IDs keep their actual names; implementation class names, expired drafts, and future ideas must not be written up as runnable options.
3. Local links use relative paths and readable labels, with the targets in angle brackets. Do not link absolute paths on a developer's machine or locally generated results that do not exist in a standard checkout; example/optional output paths go inside code with their origin stated.
4. Tag code blocks with a language. Fully runnable examples and fragments meant to be embedded in an existing program are clearly distinguished; commands state the working directory, the required dependencies, and any new output-directory requirement.
5. Keep the units, the value domains, the state meanings, the failure behavior, and the proof scope. Do not treat recording OFF as zero traffic, lifecycle proof as fluid accounting, or abstract costs as a cloud bill.
6. Do not copy quickly changing test counts, current branch names, one-off execution times, or session tool logs into the standards. When performance results are given, state the inputs, environment, measurement scope, sampling, and limitations together.
7. External references keep their authors, original titles, and stable sources; unchecked research suggestions are not implementation capabilities or experimental conclusions.

After changing documentation, run:

```bash
python3 -B scripts/test-check-docs.py
python3 -B scripts/check-docs.py
```

The checker validates the local links and resolvable anchors of the project's Markdown, accesses no external network, and replaces neither a content review of the commands and cited originals nor a source review of the semantics. When pages are added, moved, or deleted, the path references in Java comments, example outputs, tests, scripts, and protocol identities should also be searched.

## Scope of Source Comments

- `simulator/src/main/java/org/workflowsim/` and tests: the general simulation API, algorithms, models, and verification rules.
- `experiments/src/main/java/org/workflowsim/` and tests: examples, reference matrices, research identity, input provenance, and non-generalizable scope.
- `simulator/src/main/java/org/cloudbus/` is upstream CloudSim source; its copyright, license, authors, and origin are not rewritten for this project's Chinese localization. Other existing legal/provenance headers are likewise retained.

New maintenance notes are written primarily in clear Chinese, and existing accurate English contracts may be kept; paper titles, standard terminology, and upstream names keep their original wording. Package names, types, methods, enums, JSON fields, and machine-readable protocols stay in English and are not renamed for the sake of the documentation.

## Java Source Format and Naming (Tool-Enforced)

- The single point of maintenance for the rule ledger and the tier definitions is the [checkstyle configuration](<../../config/checkstyle/checkstyle.xml>); the test exemption surface is listed in the [exemption list](<../../config/checkstyle/suppressions.xml>).
- Tiers: **Tier-A (error level)** carries the semantic/correctness rules that veto the build outright (naming contracts, duplicate imports, empty statements, the `equals/hashCode` contract, string `==`, `default` not placed last), enforced by the `checkstyle:check` gate starting from `mvn validate`; **Tier-B (warning level)** carries the format/length/complexity advisory rules, whose backlog is on record in the baseline report.
- Ratchet discipline: rules grow only. Once a Tier-B rule's backlog reaches zero, the rule is promoted to error (thresholds and exemption surfaces may only be tightened; any relaxation counts as a contract change and requires human review); formatter normalization and backlog-reduction records live in the Git history, and this page does not duplicate the standard's landing progress (to keep volatile numbers from becoming documentation debt).
- Contract-revision record (2026-10-23, approved by user adjudication): after WhitespaceAround gained the empty-body family properties `allowEmptyConstructors` / `allowEmptyMethods` / `allowEmptyLambdas`, the whole tree cleared and the rule was promoted to Tier-A. Its nature is a boundary alignment with the Palantir canonical formatter (which always emits `{}` for an empty body), in line with the same-family precedent `allowEmptyTypes`; whitespace around non-empty bodies remains zero-tolerance. This threshold relaxation went through human review under the discipline above (adjudication package 2026-10-23, approved as a whole).
- Layered exemption: the `org.cloudbus` upstream code exits the gates entirely (no formatting debt is introduced on its behalf); test code is exempt from the volume/complexity metrics but **not from the semantic-protection classes**.
- Zone-policy institutionalization (2026-10-26, user-adjudicated): the `examples` teaching tree and the `clustering`/`reclustering` legacy fence are exempt from the style-class rules (naming/volume/complexity/documentation) as a matter of zone policy — neither zone ever participates in the Tier-B-to-A promotion ladder, so retained warnings there had only snapshot value; semantic-protection (Tier-A) rules are **not** exempt for these zones. After exemption, every remaining maintained-tree warning is a genuine follow-up target; the residual baseline is 109 (79 complexity, 15 legacy-compatibility parameter names, 11 member names, 3 documented MethodLength precedents, 1 planned-workbench parser candidate).
- Complexity close-out (2026-10-26, batch C): all 80 maintained-tree cyclomatic warnings cleared through batched behavior-preserving extraction (five concurrent refactoring groups; statement order verbatim; guarded by the zero-tolerance mirrors and the full test battery). Post-campaign complexity residuals are **5, all in exempt/fenced zones**: 4 in the clustering legacy fence (registered before the fence extension) plus 1 flat enum-dispatch precedent in WorkflowPlanner (a case-table with no cohesive seam — any split merely relocates case labels without reducing the counted branches).
- Fix-commit discipline: format/import/naming cleanups are always committed with the `format-only:` prefix, never in the same commit as a logic change; giant-method splits are submitted only after the full test suite is green.
- Independent verification: `mvn -q validate` (the gates); `make audit` or the CI `mvn -B -Pjavadoc verify` puts the full chain into effect.

## Javadoc and Line Comments

- Type and package descriptions explain duties, inputs and outputs, state ownership, units, and research boundaries first; public parsers, builders, runners, validators, and other effectful entry points document their parameters, returns, and failure conditions.
- No template prose is piled onto simple getters/setters; where the overridden contract is sufficient, `@inheritDoc` is used and only new restrictions, ordering, or side effects are added.
- Line comments explain formulas, stable tie-breaks, time/resource boundaries, compatibility reasons, failure atomicity, and non-obvious test oracles, without restating obvious assignments and loops.
- `/** ... */` is not used inside method bodies to fake Javadoc; the result of one experimental setup is not written as a guarantee for all models.
- When correcting documentation, check the arithmetic and the actual configuration branches so that getter descriptions, formal contracts, and the implementation do not contradict one another.

## Verification and Identity

Run the relevant build and Javadoc checks even for comment-only changes; the full commands are in the [build guide](<../getting-started/BUILD.md>). The algorithm, randomness, inputs, artifact fields, and test assertions are never modified to fit a document.

Provenance v3 records the component source-tree identity, and contract documents may also be hashed individually; comment or protocol tidy-ups change the corresponding identity of artifacts generated in the future. Do not rewrite the hashes of retained historical artifacts or label a new source as an old version. The source-tree hash is a local identity, not a Git commit and not a release signature.
