# Quality gates

Developer notes moved out of `CLAUDE.md` (which is sent with every
request) so they are read only when this area is being changed. Section
names are unchanged, so an older "see CLAUDE.md, <section>" pointer
resolves through the index at the end of `CLAUDE.md`.

## Coverage gates

- JaCoCo `check` runs at `verify` with floors in the parent `pom.xml`
  (`jacoco.line.minimum` / `jacoco.branch.minimum`, plus a PACKAGE rule for
  `ui.rendering.*` and one for the security boundary). Floors only ever move
  up; "raise" means raise where there is slack, not all three.
  **2026-11-26** (React-admin quality pass, `mvn -o -pl app verify` without
  `cwebp`): LINE 0.9767, BRANCH 0.9321 -- but CI's clean build measured
  BRANCH **0.9286** and failed the v0.17.0 release on a 0.9290 floor. Ratchet
  from a clean build's (or CI's) figure, never from an incremental
  `-pl app verify`. Floors are now **0.9720 / 0.9260**;
  the security-boundary rule is LINE 0.96 (sso 0.9725 binds) / BRANCH 0.90
  (mcp 0.9073 binds); the rendering rule was left at 0.90 / 0.82. The React
  admin's Vitest floors (`app/frontend/vitest.config.ts`) are lines 98.2 /
  branches 91.3 / functions 95.8 / statements 98.0 (measured 98.5 / 91.5 /
  96.1 / 98.2).
  Earlier:
  **2026-11-14** (maintenance-pass coverage wave, `mvn clean install`
  without `cwebp`): LINE 0.9709, BRANCH 0.9240 (from 0.9639 / 0.9158 at
  the start of the wave). Floors are now **0.9680 / 0.9210**; the
  rendering PACKAGE rule is LINE 0.90 (servlets 0.9254 binds) / BRANCH
  0.82 (velocity 0.8472 binds); the security-boundary rule stays at
  0.93 / 0.89 (auth L 0.9528 unchanged, mcp B 0.9054).
  Earlier:
  **2026-10-13** (after the post-0.1.17 coverage pass, two identical clean
  runs without `cwebp`): LINE 0.9621, BRANCH 0.9141. Floors are now
  **0.9590 / 0.9100**.
  - The rendering PACKAGE rule is LINE 0.86 (velocity 0.8815 binds) and gains
    BRANCH 0.78 (velocity 0.8033).
  - A new PACKAGE rule covers the code a stranger can reach: `ui.core.security.sso`,
    `business.comments`, `ui.restapi.auth`, `ui.restapi.v1` and
    `ui.restapi.mcp`, at LINE 0.93 / BRANCH 0.89. Its floors sit further under
    measured (auth 0.9528 / 0.9516, mcp B 0.9133) because the packages are
    small: in auth one branch is 1.6 points.
  Re-measured 2026-09-28 (after a test-only
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
**The PMD count is measured against the pinned PMD version (7.29.0 since 2026-11-14, 7.28.0 from
2026-10-17, 7.27.0 from 2026-09-14; the wave was measured on 7.26.0), and the pin is load-bearing.** The maven-pmd-plugin's bundled 7.17 measured **307** on the
same source and ruleset against **362** under 7.26.0: `CloseResource` alone
goes 13 -> 42 as the detector improved, and five rules did not exist in 7.17
(`OverrideBothEqualsAndHashCodeOnComparable`, `LambdaCanBeMethodReference`,
`UseStandardCharsets`, `AvoidInstanceofChecksInCatchClause`,
`AvoidCatchingGenericException`). An unpinned plugin drifts the count
silently on a routine bump; the `pmd-core`/`pmd-java` `7.29.0` override in
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

**14 hygiene rules added 2026-11-13 (the maintenance pass), each at zero**, so
the ruleset now names 35: performance `AppendCharacterWithChar`,
`ConsecutiveLiteralAppends`, `ConsecutiveAppendsShouldReuse`,
`InsufficientStringBufferDeclaration`, `AvoidFileStream`,
`RedundantFieldInitializer`; codestyle `CStyleArrayDeclaration`,
`UseUnderscoresInNumericLiterals`, `PrematureDeclaration`, `LinguisticNaming`;
errorprone `LongLiteralEndingWithLowercaseL`; bestpractices `OnDemandImport`,
`EnumComparison`; design `ImmutableField`. Baseline was 761 sites. Notes for
the next person who trips one:

- `AvoidFileStream`: open files with `Files.newInputStream(path)` /
  `Files.newOutputStream(path)`. They throw `NoSuchFileException`, not
  `FileNotFoundException`, and the message text differs. A side effect:
  find-sec-bugs does not flag `Files.newInputStream(Path.of(s))` as
  `PATH_TRAVERSAL_IN`, so the old site suppression became a
  `US_USELESS_SUPPRESSION` and was deleted (the input is still only ever a
  test script path).
- `ImmutableField` is held on the non-JPA classes only; a rule hit on a
  `pojos/` entity would mean the mapping is wrong, not that the field is final.
- `UseUnderscoresInNumericLiterals` runs with `acceptableDecimalLength` 7, so
  integers are grouped (`86_400_000`) but a published decimal constant
  (sRGB `0.0031308`) is left alone.
- `ConsecutiveAppendsShouldReuse` flags the FIRST of two consecutive appends,
  and `ConsecutiveLiteralAppends` also counts the constructor argument and a
  comment between appends. Write a long literal chain as one
  `append("a" + "b")` with the comments between the `+` lines.
- `EnumComparison` makes `entry.getStatus() == PubStatus.X` legal where the
  old `getStatus().equals(...)` threw on a null status.

**Defect rules added 2026-11-13 (same pass), each site read by hand**, so the
ruleset now names 43:

- `AvoidReassigningParameters` (42 sites). A parameter keeps the caller's
  value; a normalised copy gets its own name. Two sites were defects:
  `RegexUtil.obfuscateEmail` replaced the first `@` and the first look-alike
  of the domain anywhere in the text (a regex built from the match) while
  still matching the original, so `"@jake ... a@b.com"` mangled the handle
  and left the address; `GenericThrottle` repaired a negative cache size but
  let zero through, a cache that forgets every client, so nobody was ever
  throttled.
- `AvoidThrowingRawExceptionTypes` (15), `SignatureDeclareThrowsException`
  (11), `AvoidUncheckedExceptionsInSignatures` (1), `ExceptionAsFlowControl`
  (13). An unusable configuration throws `IllegalStateException`, an
  unreadable file `UncheckedIOException`; a signature names what it throws
  (`throws Exception` had made every caller catch everything). The two
  Spring Security filter-chain beans keep `throws Exception`, suppressed at
  the method, because `HttpSecurity` declares it. The rendering servlets
  threw `WebloggerException` inside their own try blocks to reach the 404 in
  the catch, and `ResourceServlet` reached it through a `NullPointerException`
  on a missing media file; each is now an `if` after the try. The resource
  loaders build a `Charset` instead of catching `UnsupportedEncodingException`.
  With the signatures narrowed, SpotBugs' `THROWS_METHOD_THROWS_*` detectors
  also left `exclude.xml` at zero; four sites carry `@SuppressFBWarnings`
  (the two beans, and two deliberate rethrows of a `RuntimeException` in
  `RemoteImageFetcher.exchange` and `WebloggerConfig.runAtClassLoad`).
- `ArrayIsStoredDirectly` (2), `MethodReturnsInternalArray` (2),
  `ImplicitFunctionalInterface` (7). `PressroomMessage` copies its argument
  array in and out (null still means "no arguments"); `CachedContent` hands
  a cached page to the response with `writeTo(OutputStream)` and
  `length()`, so the serving path copies nothing, and `getContent()` returns
  a copy. Five single-method interfaces are `@FunctionalInterface`;
  `UIActionPreparable` and `WeblogPinnedCredential` are roles found with
  `instanceof` and carry a reasoned suppression instead.

Considered and left out: `ReplaceJavaUtilDate` (376 sites; the JPA pojos are
`Date`-typed), `AvoidCatchingGenericException` (213; see "Generic catches"),
`UseConcurrentHashMap` (96), `AvoidLiteralsInIfCondition` and
`AvoidDuplicateLiterals`. They are opinion or noise here. Also tried in the
2026-11 pass and declined: `UseVarargs` (15 sites: a Spring `@RequestParam`
array, template-reachable `UtilitiesModel`/`I18nMessages`/`PressroomMessages`
methods, and a two-array signature cannot be varargs, so the rule could only
be held with suppressions) and `EmptyMethodInAbstractClassShouldBeAbstract`
(9 sites that are deliberate no-op hooks and null-returning defaults on
`BaseController` and the pager base; making them abstract forces an empty
override into every subclass). Declined in the same pass after
fixing the real sites: `AvoidFieldNameMatchingMethodName` (26; a field with
a same-named accessor is the record idiom, `extension()` for `extension`,
and the lazily built `throttle` fields behind `throttle()` are correct
double-checked locking), `AbstractClassWithoutAbstractMethod` (4; `DateUtil`
became a final utility class, the other three are bases nobody should
instantiate) and `PublicMemberInNonPublicType` (7; two nested classes were
narrowed, the other four are `LazyExpiringRenderCache` methods its public
subclasses expose to other packages). Triaged and left ungated: `NullAssignment` (57;
each is null meaning absent, or a buffer dropped on close) and
`ConstructorCallsOverridableMethod` (47; the only overrides reached from a
constructor return constants, as `WeblogResourceRequest.isLocale` does, and
the javac twin `this-escape` is off for the same reason).

Six PMD rules and five SpotBugs families are excluded, each with a reason
comment in the config file: three SpotBugs-core families, and two
find-sec-bugs families (below).
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

**find-sec-bugs 1.14.0 runs inside the SpotBugs gate** (the parent pom's
`<plugins>`, pinned by `findsecbugs.version`). `QualityGatePomTest` fails if
the plugin is removed or if the filter excludes by anything but pattern name.
Its first run, on 2026-10-12, found 793 findings. Two of its families are
excluded:

- **The seven source markers** (`SPRING_ENDPOINT`, `SERVLET_PARAMETER` and the
  `SERVLET_HEADER`/`SERVER_NAME`/`QUERY_STRING` kind). They only mark where
  untrusted input enters. The sink detectors are the gate.
- **`CRLF_INJECTION_LOGS`.** It is fixed once, by `%encode{%m}{CRLF}` in
  `log4j2.xml` (see `docs/dev/configuration.md`), and `QualityGatePomTest`
  refuses the exclusion without that pattern.

**`IMPROPER_UNICODE` is deliberately not excluded.**
- It fires on every case fold near an `equals`, every `equalsIgnoreCase` and
  every `Normalizer.normalize`, with `Locale.ROOT` or without.
- Its value is the prompt it gives on the next new site. Folding a
  person-typed name inside a security decision is exactly the bug it caught in
  `DisplayNames`. It also caught `AdminApi` storing "falſe" (with a long s) as
  a boolean config value.
- Each existing site carries a suppression saying why its fold is safe:
  - ASCII by construction;
  - Tomcat's ISO-8859-1 header decoding, under which no character in
    U+0080 to U+00FF folds into ASCII;
  - configuration;
  - display or author-content parsing;
  - the `DisplayNames` fold itself.
- `QualityGatePomTest` refuses the family in `exclude.xml`.
- For a new site:
  - Fold a person-typed name the way `DisplayNames.fold` does: NFKC, upper
    then lower in `Locale.ROOT`, then NFKC again.
  - Check an ASCII token with an ASCII-only pattern (`CASE_INSENSITIVE`
    without `UNICODE_CASE`, as `AdminApi`'s boolean check does).
  - Otherwise, suppress the site with its reason.

`QualityGatePomTest` also pins the ways findings could be dropped without
touching `exclude.xml`:
- The filter may contain only `<Match>` elements, each holding one
  `<Bug pattern=…>`. A `code=` attribute would widen a match, because
  SpotBugs ORs it with the pattern.
- The plugin configuration may not set `omitVisitors`/`visitors`,
  `onlyAnalyze`, `excludeBugsFile`, `includeFilterFile`, `maxRank`, or a second
  `excludeFilterFile`, in either pom or `.mvn/`.

Every other finding was traced from source to sink. Seven were real and are
fixed:

- the shortcode tag pattern overflowed the stack on long unclosed prose, which
  crashed the entry and the weblog front page;
- the user lookup answered with no content type;
- a commenter could take a member's display name in fullwidth or bold
  letters;
- the anonymous installer showed the JDBC URL, the username and a stack trace
  while the database was down. Its pages now point at `pressroom.log`, and
  `SQLScriptRunner` reports by statement position;
- `MediacastUtil`, a dead SSRF sink, was deleted;
- two checks compared HTTP methods case-insensitively; they are exact now;
- the admin API accepted a boolean config value spelled with non-ASCII look-alike
  letters; it now accepts ASCII only.

The rest carry `@SuppressFBWarnings` at the method that holds the sink, with a
reason that says why the input is not attacker-controlled and where it is
constrained.

**Triaging a new finding:**
- Trace it from source to sink. Fix it if the input can reach the sink;
  otherwise suppress it at the narrowest element, with that reason.
- Never add a pattern to `exclude.xml` for a version bump.
- A method-level suppression does not reach a lambda inside the method, and
  nothing can annotate a static initializer. Move such a sink into a named
  method. SpotBugs reports a suppression that matches nothing as
  `US_USELESS_SUPPRESSION_*`.

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

## Complexity

Gated 2026-11-14 (maintenance pass, agent q3): PMD `CognitiveComplexity` at
`reportLevel` 20 (a method at 20 or more fails) and `AvoidDeeplyNestedIfStmts`
at `problemDepth` 4. `QualityGatePomTest.complexityThresholdsArePinned` pins
both numbers: lowering one is welcome, raising one is a decision for this
section.

| PMD measure (default thresholds unless stated) | before | after |
|---|---|---|
| CognitiveComplexity, methods at 15 or more | 77 | 39 |
| CognitiveComplexity, at 20 or more (the gate) | 47 | 0 |
| largest method | 56 (`WeblogRequestMapper.handleRequest`) | 19 |
| AvoidDeeplyNestedIfStmts, depth 3 / depth 4 (the gate) | 52 / not measured | 17 / 0 |
| CyclomaticComplexity | 168 | 140 |
| NPathComplexity | 39 | 24 |
| NcssCount | 9 | 1 |
| GodClass | 50 | 50 |
| ExcessiveParameterList | 13 | 13 |

How the gain was made, and how to keep a new method under the gate:

- **Pin first.** Before a method was touched, its uncovered branches in
  JaCoCo were pinned with characterisation tests (javadoc says so; each was
  run against the old code). Several had none where it mattered: serving a
  theme resource, webp negotiation without cwebp, the preview servlet's
  order of themes.
- **Guard clauses, then one job per method.** An early return for the
  refusal, the not-found and the nothing-to-do case; the rest extracted as a
  method named for what it decides (`siteHostClaim`, `servableMediaFile`,
  `slashMisplaced`), never numbered fragments. The order of checks in the
  routing and security paths (`WeblogRequestMapper`, the resource servlets,
  `WeblogPermission.implies`) was kept exactly.
- **Repeated shapes become small helpers.** `JpqlParameters` (business/jpa)
  hands back each bound value's `?n` placeholder, replacing hand-kept
  counters; `ServableResource` is the resource servlets' shared lookup and
  reply; `CacheKeys.appendPageSegments` is the page key both page caches
  build.
- **Flag arguments split.** `myValidate(bean, user, isAdd, ...)` became
  `validateNew` and `validateEdit`.

Not gated, on purpose: Cyclomatic and NPath count every branch a flat switch
or a linear run of "if the field is present, set it" adds, which no reader
struggles with (`EntryDtos.applyWrite`, `AdminDtos.applyPatch`); GodClass
flags the managers and controllers whose size is their scope. Their counts
are above, for the record.

When a method trips the gate, refactor it as above; a suppression is for a
method that is genuinely clearer as one piece (a parser or a state machine),
with that reason. The pass needed none.

Left at depth 3 (17 ifs, not gated): `DatabaseProvider` (2),
`ExternalImages.ImgAttributes.accept`, `Period.of`, the cache-hit blocks of
`JPAUserManagerImpl.getUserByUserName` and `JPAWeblogManagerImpl.getWeblogByHandle`,
`SharedThemeFromDir.loadStylesheet`, `CategoryRemoveController`,
`MediaFileAddController.resolveDirectory`, `PressroomSession` (2),
`PageModel.getCanonicalUrl`, `WeblogEntriesListPager.getLastUpdated`,
`WeblogEntriesPreviewPager`, `PageServlet.selectTemplate` (2) and
`CategoriesApi`. Most are a two-line guard-clause fix; doing them would let
the gate move to the default depth of 3.

## Log-only catches

Moved from CLAUDE.md (2026-11-08); the rule stays there in short.

**A `catch` that logs and falls through is fine in a display path and a
bug in a decision path; the difference is what the caller does with the
answer.** ~170 in this tree, mostly correct (a sidebar that cannot load
renders empty). The wrong ones share a shape:

```java
private void myValidate(...) {
    try {
        if (weblogManager.getTemplateByName(weblog, bean.getName()) != null) {
            addError(model, "pagesForm.error.alreadyExists", ...);
        }
    } catch (WebloggerException ex) {
        log.error("Error checking page name uniqueness", ex);   // <-- no error added
    }
}
// caller:
myValidate(bean, template, request, model);
if (!hasErrors(model)) { ...save... }
```

The caller reads an empty error list as "passed", so an unreachable store
let the save through unchecked. **A check that could not run is not a
check that passed.** The old template controllers
both did this; `TemplatesApi` fails closed now, and its tests pin it.

When adding a `catch` that only logs, ask what the caller does next; if it
proceeds as though nothing was wrong, fail closed. Two sweeps to repeat:
methods named `*validate*`/`check*` with a log-only catch, and
`boolean` methods that swallow — what matters is the value they fall
through to (every one today falls to `false`, i.e. deny or degrade;
`PressroomHandlerInterceptor.preHandle` is fail-closed because a
swallowed weblog lookup leaves `actionWeblog` null and `enforceSecurity`
then refuses).

## Shared state (2026-11-13 audit)

The multithreading rules are not gated: `UseConcurrentHashMap` (152 sites)
fires on every local `HashMap`; `AvoidSynchronizedAtMethodLevel` (16) and
`AvoidSynchronizedStatement` (24) push `ReentrantLock` to avoid pinning
virtual threads, which `synchronized` no longer does on Java 24+, and the
blocks here already lock private objects (SpotBugs `USO_*`). Read by hand
instead, the shared mutable state found three real hazards:

- `CacheManager`'s registries were a `HashMap` and a `HashSet`, grown when a
  cache is built on first use (a throttle on its controller's first
  request) while another thread clears every cache or fans out an
  invalidation. Now a `ConcurrentHashMap` and a `CopyOnWriteArraySet`
  (`CacheManagerTest`, two re-entrant registration tests).
- `PluginManagerImpl` kept its plugin registry in a static map filled by
  every instance's constructor and cleared by none, so a plugin one manager
  loaded reached every later one. It is an instance field now
  (`PluginManagerPluginsTest.aManagerSeesOnlyThePluginsItLoaded`).
- `ThemeManagerImpl` swaps a theme on a request thread when
  `themes.reload.mode` is on, by `remove` then `put` on a `HashMap`: a reader
  between the two found no theme. A `ConcurrentHashMap` and one `put` (no
  deterministic test; a dev-mode-only race).

**PMD's `NonThreadSafeSingleton` (quickstart) throws on a record component
in a null check** and is then not applied to that file: 7.28's
`shouldConsiderField` asks for the component's `FieldDeclaration` ancestor
with `firstOrThrow`, and a record component has none. It printed "Exception
applying rule" for `PageReference` and `ReaderClaims` on every build. Those
checks now go through the accessor (`handle() == null`); the first named
reference in the condition is the one that matters. A processing error does
not fail the build, so read the PMD output for "Exception applying rule"
after adding a record.

## Generic catches

PMD's `AvoidCatchingGenericException` is not gated, and will not be: of its
247 sites (2026-11-13) most are boundaries where a broad catch is the
design, not an oversight. A controller action that answers "check the logs"
rather than a 500; a servlet that answers 404 for any request it cannot
parse; a background task loop; a plugin or shortcode callback; a best-effort
side effect after a commit (an event, a revision, a search-index note); a
display path that must render. 77 of them already wrote
`catch (WebloggerException | RuntimeException e)` on purpose. Holding the
rule would have meant a suppression at well over a third of its sites.

What is gated instead is SpotBugs `REC_CATCH_EXCEPTION`, which left the
`exclude.xml` family at zero on 2026-11-13. It flags a `catch (Exception)`
whose try block throws no `Exception` itself, so the catch silently takes
runtime faults too. The fix is to say what is caught:

- One failure mode: catch it (`Integer.parseInt` takes
  `NumberFormatException`, a session lookup `IllegalStateException`).
- Checked exceptions plus runtime faults on purpose: list them and write the
  `RuntimeException` out, with a comment when the reason is not obvious (a
  parser of untrusted bytes such as metadata-extractor fails on a malformed
  file with whatever its parser hit).
- SpotBugs flags only some shapes of `catch (Exception)`; it is a floor, not
  a review. Ask the questions below of any broad catch you write.

**An `InterruptedException` inside a broad catch is a defect.** `waitFor`,
`sleep` and `Future.get` clear the thread's interrupt flag when they throw,
so a `catch (Exception)` around them that does not call
`Thread.currentThread().interrupt()` loses the request to stop. Catch it
on its own, stop whatever the thread was waiting on, and restore the flag
(`CwebpEncoder.run`, pinned by `CwebpEncoderTest`).

**A broad catch in a decision path fails closed** (see "Log-only catches"
above). The 2026-11-13 sweep of every generic catch in a `validate`/`check`/
permission method found each one already did: `PressroomHandlerInterceptor`,
`ApiTokenAuthFilter`, `UtilitiesModel`'s permission checks, `WeblogEditors`
and the lease in `JPAThreadManagerImpl` all fall to deny or skip.

