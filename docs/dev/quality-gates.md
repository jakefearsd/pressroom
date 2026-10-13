# Quality gates

Developer notes moved out of `CLAUDE.md` (which is sent with every
request) so they are read only when this area is being changed. Section
names are unchanged, so an older "see CLAUDE.md, <section>" pointer
resolves through the index at the end of `CLAUDE.md`.

## Coverage gates

- JaCoCo `check` runs at `verify` with floors in the parent `pom.xml`
  (`jacoco.line.minimum` / `jacoco.branch.minimum`, plus a PACKAGE rule for
  `ui.rendering.*`). Floors only ever move up; "raise" means raise where
  there is slack, not all three. Re-measured 2026-09-28 (after a test-only
  coverage wave, without `cwebp`): LINE 0.9377, BRANCH 0.8722, PACKAGE
  velocity 0.8819 / servlets 0.8551 / rendering 0.9447; floors stand at
  **0.9330 / 0.8670 / 0.80** (raised from 0.9100 / 0.8400, measured
  0.9142 / 0.8458 on 2026-09-08). Set a floor a few tenths
  under measured, never at it — the margin separates a ratchet from a
  tripwire an unrelated change sets off. The PACKAGE rule's binding package
  is whatever its `<includes>` names, not the lowest number in the report:
  `ui.rendering.filters` measures lower (0.8333) but is not included and at
  18 lines is too volatile (one line = 5.6 points); the real constraint is
  `servlets` at 0.8551. New work's coverage is the diff
  gate's job.
  `cwebp` caveat: the same tree measures differently with and without
  `cwebp` on `PATH` (three WebP tests skip without it) — 2026-09-09,
  without: 0.9142 / 0.8458; with: 0.9158 / 0.8487. Floors are set against
  the **without** numbers on purpose — a floor raised from a CI-measured
  number can pass CI while failing a laptop that has no `cwebp`.
- Changed lines need ~90% coverage: `bin/check-diff-coverage.sh [base-ref]`
  (default `HEAD~1`; needs `pip install diff_cover` and a fresh
  `mvn -pl app jacoco:report`). CI enforces this on every push/PR. Measure
  it against the range you will actually push, not per commit — CI takes
  its base from `github.event.before`, so a batch is judged as one diff.
- **A large mechanical sweep will fail this gate for a reason that is not
  real, and that is expected.** Changing `log.debug("x " + y)` to
  `log.debug("x {}", y)` re-marks a long-uncovered error-path line as new;
  coverage has not dropped. The JCL→SLF4J migration (pushed at `586d0458b`,
  37 commits) measured 83% over 1,199 changed lines where the
  static-analysis wave scored 91–92%. Accept the one red run and say so; do
  not fix it — tests for error-path log statements are the assertion-free
  coverage theatre the static-analysis spec bans ("Policy: defensive
  branches and the diff-coverage gate"), and exempting a category of line
  buys a permanent hole for a one-off.
- **Three i18n ratchets guard the message bundle**, each answering a
  question the others cannot. `MessageKeyTest`: a JSP arm (every
  `<spring:message code>` resolves), a Java arm (every key in a
  `getText`/`addError`/`addMessage` literal exists), and an orphan arm
  matching on a word boundary, so `error` is not "used" because
  `error.upload` exists. `MessagePlaceholderContractTest` pins arity: a
  value's highest `{n}` must match what every call site passes, catching a
  message that renders a literal `{1}`. `MessageFormatRegressionTest` runs
  both apostrophe directions over every translation — a value with a
  placeholder may not carry a bare `'`, one without may not carry `''`. All
  three assert against `Set.of()`; a ratchet with a populated offender set
  is green and means nothing, so empty the set rather than adding to it.
- Browser ITs run in CI (`mvn verify -Pit`) — see `it-selenium/`, and CI
  below for *when*.

## Compiler warnings

javac runs `-Xlint:all` with `failOnWarning` (parent pom,
`maven-compiler-plugin`), so a new warning fails `compile` and `test-compile`
in both modules: a deprecation, a removal, an unused try resource, a dangling
doc comment. Since 2026-10, when 171 warnings (73 main, 98 test) were
cleared. Four lints are off, a closed set that `QualityGatePomTest` pins, each
with its reason beside it in the pom:

- `options`, `processing`: advisory noise.
- `path`: classpath housekeeping, not a code defect. `it-selenium` has no main
  sources, so its `target/classes` never exists, and a clean `-Pit` build
  failed on "bad path element" before one browser test ran.
- `this-escape`: the javac twin of the excluded SpotBugs
  `MC_OVERRIDABLE_METHOD_CALL_IN_CONSTRUCTOR`. Template methods called from
  constructors are how the servlet and manager hierarchies are written, and
  JPA entities may not be final.

How to fix a warning:
- Move to the replacement API, for example `Locale.of`, commons-lang3
  `Strings.CS`, or `URI.toURL()` for the deprecated `new URL(String)`. That
  last one is stricter: a raw space is refused as malformed.
- Suppress at the narrowest element, with a reason, only when no replacement
  exists or a framework contract requires the deprecated member.
- In tests, an unused try-with-resources variable is written `_`.

## Static-analysis gates

PMD, CPD and SpotBugs run at `verify` in the `app` module and fail the build
on **any** violation. Config: `config/pmd/ruleset.xml`,
`config/spotbugs/exclude.xml`; wiring in the parent `pluginManagement`,
executions in `app/pom.xml`. `bin/quality-report.sh` prints current counts
and sites; `bin/quality-report.sh <RuleName>` lists one rule's sites. It
derives the CPD token threshold from the pom, and `QualityGatePomTest` pins
that (it printed `CPD @200` against a gate of 110 for eighteen days).

The wave took the tree from 362 PMD / 134 SpotBugs / 4 CPD violations to
zero; the temporary `pmd.max.violations` / `spotbugs.max.violations`
ceilings and `maxAllowedViolations` wiring were deleted once it held there.
**The PMD count is measured against the pinned PMD version (7.27.0 since
2026-09-14; the wave was measured on 7.26.0), and the pin is load-bearing.** The maven-pmd-plugin's bundled 7.17 measured **307** on the
same source and ruleset against **362** under 7.26.0: `CloseResource` alone
goes 13 -> 42 as the detector improved, and five rules did not exist in 7.17
(`OverrideBothEqualsAndHashCodeOnComparable`, `LambdaCanBeMethodReference`,
`UseStandardCharsets`, `AvoidInstanceofChecksInCatchClause`,
`AvoidCatchingGenericException`). An unpinned plugin drifts the count
silently on a routine bump; the `pmd-core`/`pmd-java` `7.27.0` override in
the parent `pluginManagement` fixes it.

Zero tolerance is affordable only because the rule set is narrow: **a rule
is excluded only when violating it is systematically not a defect in this
architecture**, never because there are a lot of them.
`UnnecessaryConstructor` is the clearest case — JPA entities must declare a
no-arg constructor, so the rule is wrong here, not noisy. PMD's `codestyle`
category as a whole (7,997 violations of format opinion) is not used; four of
its rules are, by name (next paragraph).

**21 rules named one by one (2026-10), each at zero.** Beyond quickstart and
`security`, the ruleset lists rules picked for the defect class they catch,
from a trial of every category against 0.1.17: errorprone
`SimpleDateFormatNeedsLocale`, `UseProperClassLoader`, `MoreThanOneLogger`;
bestpractices `UnusedAssignment`, `UseTryWithResources`, `SystemPrintln`,
`ExhaustiveSwitchHasDefault`, `UnnecessaryVarargsArrayCreation`,
`UnnecessaryWarningSuppression`, `AvoidPrintStackTrace`,
`AvoidReassigningLoopVariables`; performance `AddEmptyString`,
`UseStringBufferForStringAppends`, `InefficientStringBuffering`; codestyle
`ModifierOrder`, `UnnecessaryCast`, `UnnecessaryBoxing`, `UseDiamondOperator`;
design `CollapsibleIfStatements`, `MutableStaticState`,
`AvoidThrowingNullPointerException`. They found 152 sites, all fixed or
suppressed at the site; `QualityGatePomTest.pmdRulesetScopeIsPinned` pins the
list. What they now enforce:

- `UnusedAssignment` is the one that can find bugs (a value meant to be used
  and never read). All 61 sites were dead initialisers.
- `SimpleDateFormatNeedsLocale`: a wire format (permalink stamps, ISO-8601,
  feeds) takes `Locale.ROOT`, or `Locale.US` where English names are needed.
  Under a Thai default locale the old code wrote Buddhist-calendar years into
  permalinks. A display format takes the weblog's or viewer's locale. Note
  that `Locale.ROOT` abbreviates full month and day names (`MMMM` renders
  "Dec"), so it is only for numeric or abbreviated patterns.
- `ExhaustiveSwitchHasDefault`: an enum switch that covers every constant is
  written as a switch expression with no `default`, so a new constant is a
  compile error. A switch statement is not checked for exhaustiveness, which is
  why `EntryEditController.addStatusMessage` became an expression whose arms
  are lambdas (that also keeps each `addMessage` call scannable by
  `MessageArgumentEscapingTest`).
- `UnnecessaryWarningSuppression` keeps the suppressions honest: a
  `@SuppressWarnings("PMD.X")` that no longer suppresses anything fails the
  build. The 35 older reason comments were re-read against the code at the
  same time; seven had gone stale and were corrected.

Considered and left out: `ReplaceJavaUtilDate` (376 sites; the JPA pojos are
`Date`-typed), `AvoidCatchingGenericException` (213),
`UseConcurrentHashMap` (96), `AvoidLiteralsInIfCondition`,
`AvoidDuplicateLiterals` and `RedundantFieldInitializer` (450). They are
opinion or noise here.

Six PMD rules and three SpotBugs families (469 of 603 raw SpotBugs findings)
are excluded, each with a reason comment in the config file.
`QualityGatePomTest` fails the build if an exclusion lacks a justification
comment or the excluded set differs from the list it names — a rule outside
`PERMITTED_PMD_EXCLUSIONS`, a `<Match>` block beyond the three families, or
a bug pattern outside `PERMITTED_SPOTBUGS_PATTERNS` — so widening either set
is a spec change, not an implementation decision.

The JCL→SLF4J migration is done (~797 call sites, all on `org.slf4j` with
parameterized `{}` logging; 6 `log.fatal` calls mapped to `log.error`).
SLF4J only formats when the level is enabled, so every
`if (log.isDebugEnabled()) { log.debug("x " + y); }` guard whose job was
gating concatenation was deleted as ceremony. `ProperLogger` came back
clean (167 → 0) and is active with no exclusion. `GuardLogStatement`
flagged **175** at activation: PMD treats any non-trivial argument (a
method call, a ternary) as unprovably cheap, so it fires on the idiomatic
`{}` fed by `entry.getId()` / `weblog.getHandle()`. Three sites were real
waste (an eagerly-built `StringBuilder`/`MessageFormat.format()` handed to
`log.info(String)`, two `stream().collect()` calls at startup) and were
fixed. The other 172 were first suppressed with
`@SuppressWarnings("PMD.GuardLogStatement")` on 74 class declarations;
overruled — a class-level suppression also silences future code, and 74
classes sharing one reason is a family, which belongs in the config file.
`GuardLogStatement` is excluded in `config/pmd/ruleset.xml` permanently:
with parameterized SLF4J, violating it is systematically not a defect. Full
accounting: the Follow-up section of
`docs/superpowers/specs/2026-08-18-static-analysis-quality-gates-design.md`.

SpotBugs 4.10.4 (2026-09-14) added two detectors that found 19 sites; both
were fixed in code rather than excluded: `IAOM_DO_NOT_INCREASE_METHOD_ACCESSIBILITY`
(sixteen `doGet`/`doPost`/`doRun` overrides declared `public` over a
`protected` parent, now `protected` — test callers sit in the same package)
and `USO_UNSAFE_*_METHOD_SYNCHRONIZATION` (three `synchronized` methods on
publicly reachable objects, now private lock objects). A tool bump that
adds detectors is expected to find things; fix them or justify a site-level
suppression, never widen `exclude.xml` for a version bump.

**On SLF4J's varargs form a `Throwable` must stay the LAST argument, and no
`{}` may consume it.** `log.error("x {}", a, e)` preserves the stack trace;
`log.error("x {} {}", a, e)` binds `e` to the second placeholder via
`String.valueOf(e)` and the trace is silently gone. JCL's `String`/`Object`
single-argument overloads (`log.error(ex)`, `log.warn("msg " + ex)`) were
the most common pre-existing bug the migration surfaced. There is no
compiler check; read the argument list.

CPD runs at **110 tokens**, lowered from 200 on 2026-08-22 after triage. The
old threshold missed real duplicates across a routing/parsing boundary at
roughly a hundred tokens each:

- `isLocale`, byte-for-byte identical in `WeblogRequest` (routing) and
  `WeblogRequestMapper` (parsing) — a divergence would have made the two
  halves read the same url as different requests. Now `LocaleSegment`.
- The `<rendition>` parser, copied inside `ThemeMetadataParser` at 193 tokens.

The triage at 110 found nine blocks; four were extracted, the rest carry
`CPD-OFF` with a stated reason — two (the day/month pagers) marked deferred
rather than excused. The threshold is part of the gate's spec:
`QualityGatePomTest` pins it, and changing it means updating the design doc
and that test, not just the pom. Below 110 the gate costs more than it
returns; that is not a claim that such duplication is fine.

**Editing `config/pmd/ruleset.xml` has two parse-time traps.** A literal
`{}` anywhere in the file — including inside a comment — breaks PMD's
ruleset merge, because the file is run through `MessageFormat`. A bare `--`
inside an XML comment is illegal XML, so the ASCII dash convention (fine in
`.java` and Markdown) cannot be used there; the same rule bites
`runtimeConfigDefs.xml`, where it fails silently.

One-off suppressions go at the call site (`@SuppressWarnings("PMD.Rule")`,
`@SuppressFBWarnings`, `// CPD-OFF`) **with a reason** that does more than
restate the rule name; the two config files are for whole families only. A
genuinely repeating pattern within one class may carry the suppression on
the class declaration — still "at the site". A pattern repeating across many
classes is a family and belongs in the config file (`GuardLogStatement`
above: 74 classes, one reason).

Proof the gate bites: seeding one violation per tool (an unused import for
PMD, an unchecked `String.getBytes()` for SpotBugs's `DM_DEFAULT_ENCODING`,
a 200+-token method copied into a second class for CPD) each turned
`mvn -pl app verify` into a `BUILD FAILURE` naming file, line and rule — see
`docs/superpowers/specs/2026-08-18-static-analysis-quality-gates-design.md`.
Cost: PMD+CPD+SpotBugs add ~10 seconds to a warm `verify` (inside the
30-second budget); a cold full `verify` runs in under 20 seconds.
