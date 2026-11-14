# Static-analysis quality gates (PMD, CPD, SpotBugs) — Design

**Date:** 2026-08-18
**Status:** approved, not yet implemented
**Scope:** the build's quality gates and the code changes needed to pass them
at zero violations. **No change to runtime behaviour, schema, themes, or any
public surface.**

## Goal

Give the build two more gates alongside JaCoCo — a static-analysis gate (PMD +
SpotBugs) and a duplication gate (CPD) — tuned so that **a violation is always
worth fixing**, and set them at **zero tolerance** so a future violation is
unambiguously attributable to the change that introduced it.

The product is heading for a much larger feature set. The gates exist to make
the codebase's floor rise with it rather than erode under it, and to do so
without becoming the kind of pedantic checker people learn to route around.

## Non-goals

- **No ratcheted violation ceiling.** See Decision 1.
- **No `codestyle` rules.** 7,997 violations of naming/brace/format opinion,
  zero defects among them.
- **No JCL→SLF4J migration in this wave.** See Follow-up; it is the reason two
  PMD rules are excluded rather than fixed.
- **No gate on test sources or on `it-selenium`.** See Decision 4.
- **No new CI tier.** See Decision 3.
- **No behavioural refactor sold as deduplication.** See Decision 6.

## Measured baseline

Everything below is measured against the tree at `3669dac9a`, 433 main sources
/ 71,090 lines, not estimated. PMD 7.26 via `maven-pmd-plugin` 3.28.0; SpotBugs
4.9.8 at `effort=Max threshold=Low`.

**Corrected 2026-08-18, after Task 1 measured the live gate.** The design-time
figures below (307 PMD / 443 total) were probed with the maven-pmd-plugin's
*bundled* PMD **7.17.0**; the build pins **7.26.0**, which reports 55 more.
`CloseResource` alone goes 13 -> 42 as that detector improved, and five rules
did not exist in 7.17 (`OverrideBothEqualsAndHashCodeOnComparable`,
`LambdaCanBeMethodReference`, `UseStandardCharsets`,
`AvoidInstanceofChecksInCatchClause`, `AvoidCatchingGenericException`;
`AvoidCatchingThrowable` was absorbed into the last of these). Nothing about the
policy changes — but it is direct evidence that **pinning the PMD version was
load-bearing**: unpinned, this gate would drift silently whenever the plugin's
bundled PMD moved, failing builds nobody had changed. Live numbers:

| Tool | Raw | Gated | Excluded |
|---|---|---|---|
| PMD (`quickstart` + `security`) | 1049 | **362** | 687 across 7 rules |
| SpotBugs | 603 | **134** | 469 across 3 families |
| CPD @200 tokens | 4 | **4** | — |
| | | **500 total** | |

(Design-time figures, PMD 7.17: 307 / 132 / 443. Superseded by the row above.)

For reference, rule sets deliberately *not* used: `codestyle` 7,997,
`errorprone` 971, `design` 799, `performance` 748, `multithreading` 115.
`category/java/security.xml` reports **0** and is included precisely because it
is free to hold at zero forever.

## Decisions

Settled before design; recorded so a later reader does not relitigate them.

1. **Zero tolerance on a narrow rule set, not a ratcheted ceiling on a wide
   one.** A count ceiling (`pmd.max.violations=160`, lowered by hand after each
   cleanup) was rejected for the reason `pom.xml` already documents about
   `jacoco.line.minimum`: a floor measured to within a rounding error of the
   real number binds constantly, and unrelated changes fail the build for the
   one violation they happened to add. A ceiling is worse than a coverage floor,
   because it also needs a baseline that rots, and because "is 161 worse than
   160?" has no honest answer. Zero has exactly one meaning.

   The corollary is that the rule set must be **narrow enough that zero is
   reachable and stays reachable**. That is what every exclusion below is
   buying — not leniency, but the right to be absolute about what remains.

2. **A rule is excluded only when a violation of it is *systematically* not a
   defect in this architecture.** Not "there are a lot of them". The seven PMD
   rules and three SpotBugs families excluded below each carry a stated reason
   in the config file itself, and `UnnecessaryConstructor` is the clearest
   case: JPA entities are *required* to declare a no-arg constructor, so the
   rule is not noisy here, it is wrong here.

3. **The gates run in `verify`, in the default build — no new CI tier.** PMD is
   ~3s, CPD ~2s, SpotBugs ~11s: ~16 seconds on a ~3-minute build. The tiering
   argument in `.github/workflows/main.yml` — which keeps the 16-minute browser
   ITs off the push path — does not apply at 16 seconds, and the cost of the
   alternative is high: a gate that only runs nightly is a gate developers
   discover after the fact. CI's existing `mvn -V -ntp install` picks all three
   up with no workflow edit, and `mvn install` locally returns exactly CI's
   verdict.

4. **Main sources only.** Test code duplicates fixtures deliberately and CPD
   would punish it for that; `it-selenium` is test scaffolding throughout.
   Gating them would generate pressure to make test code less explicit, which
   is the opposite of what tests are for.

5. **Exclusions are visible in exactly two places, and each carries a reason.**
   Family-wide exclusions live in `config/pmd/ruleset.xml` and
   `config/spotbugs/exclude.xml`, commented. One-off suppressions live at the
   site (`@SuppressWarnings("PMD.Rule")`, `@SuppressFBWarnings`, `// CPD-OFF`).
   `QualityGatePomTest` fails the build on an exclusion with no justification
   comment, so silencing a rule is never a quiet act.

6. **CPD at 200 tokens, and duplication is not always a defect.** Of the four
   blocks at that threshold, two are the entry pagers — genuinely extractable
   into `AbstractWeblogEntriesPager`, which already exists — and two are the
   render caches. The caches are **not** collapsed into a shared base:
   CLAUDE.md records that `WeblogPageCache` has no CacheHandler and expires
   only against `weblog.lastModified`, unlike its siblings, so unifying them is
   a behavioural change wearing cleanup's clothes. They get `// CPD-OFF`
   markers carrying that reason.

   > **Threshold lowered to 110 (2026-08-22).** 200 was chosen because 100
   > found twenty blocks. The cost of that choice turned out to be real: two
   > rules duplicated across a routing/parsing boundary sat at roughly a
   > hundred tokens each and were invisible to the gate until someone read
   > them — `isLocale`, copied between `WeblogRequest` and
   > `WeblogRequestMapper` (one deciding how a url is routed, the other how it
   > is parsed), and the `<rendition>` parser copied inside
   > `ThemeMetadataParser` at 193 tokens. A triage pass at 110 found nine
   > blocks; four were genuinely extractable and were extracted, and the rest
   > now carry `CPD-OFF` with a stated reason. The gate runs at 110 and
   > `QualityGatePomTest` pins it. This is not a claim that duplication below
   > 110 is fine — only that below it the gate costs more than it returns.

   > **Correction (2026-08-21).** "Unlike its siblings" is wrong, and the
   > `CPD-OFF` markers inherited the error. `WeblogFeedCache` has no
   > CacheHandler either — it passes `constructCache(null, ...)` and expires
   > lazily just as the page cache does; only `SiteWideCache` is invalidated
   > through `CacheManager`. That matters here because it means the two
   > suppressed blocks are *not* the same situation:
   > `SiteWideCache.generateKey` ↔ `WeblogPageCache.generateKey` does span
   > differing contracts, while the `WeblogPageCache` ↔ `WeblogFeedCache`
   > accessor block duplicates two caches that are contract-identical, and is
   > suppressed on the narrower ground that collapsing two singletons to share
   > four accessors is a refactor nobody has taken yet. See `CLAUDE.md`,
   > Templates; `RenderCacheHandlerRegistrationTest` now enforces the split.

   The threshold is 200 rather than 100 (20 blocks) for the same why: at 100
   the gate starts demanding refactors whose risk exceeds the duplication's
   cost.

7. **`GuardLogStatement` and `ProperLogger` are deferred, not rejected.** They
   are 535 of PMD's 994 and both point at one real finding — 178 files on the
   commons-logging API with 377 string-concatenating calls and zero
   parameterized ones. That is worth fixing, and it is a ~550-site diff that
   must not ride along inside the commit that introduces the gates, where
   neither change would get read. The config marks them deferred with a pointer
   to the follow-up.

## What gets built

### Build wiring

Versions and rule-file paths in the parent `pom.xml` `pluginManagement`, so
there is one source of truth. Executions in `app/pom.xml` only, so the gate
applies to the app module and not to `it-selenium`.

| Plugin | Goal | Phase | Fails on |
|---|---|---|---|
| `maven-pmd-plugin` 3.28.0 (PMD 7.26 pinned) | `check` | `verify` | any violation |
| `maven-pmd-plugin` | `cpd-check` | `verify` | any duplication ≥200 tokens |
| `spotbugs-maven-plugin` 4.9.8.5 | `check` | `verify` | any bug after exclusions |

`failOnViolation`/`failOnError` true, `printFailingErrors` true so a red build
names what failed without a second command. XML and HTML reports land under
`app/target/` for inspection. Stock `-Dpmd.skip` / `-Dcpd.skip` /
`-Dspotbugs.skip` remain; a `quality.check.skip` property mirrors the existing
`jacoco.check.skip` for whole-gate bypass.

### `config/pmd/ruleset.xml`

`rulesets/java/quickstart.xml` + `category/java/security.xml`, minus:

| Rule | n | Reason |
|---|---|---|
| `GuardLogStatement` | 368 | commons-logging idiom; deferred to the SLF4J wave |
| `ProperLogger` | 167 | same |
| `UncommentedEmptyConstructor` | 47 | style opinion, no defect class behind it |
| `AssignmentInOperand` | 43 | `while ((line = r.readLine()) != null)` is correct Java |
| `UncommentedEmptyMethodBody` | 30 | style opinion |
| `UnnecessaryConstructor` | 23 | JPA entities are required to declare a no-arg constructor |
| `AvoidUsingVolatile` | 9 | contradicts SpotBugs `AT_STALE_THREAD_WRITE_OF_PRIMITIVE`, which wants more `volatile`, not less |

**Design-time figure, PMD 7.17.0. Superseded by the Measured baseline table
above, which reports 362 gated under the pinned 7.26.0 -- not 307.** Kept
because it is the record of what was estimated before the fix pass, not what
the fix pass found.

Leaves 307 violations across 31 rules. The largest remaining are
`UselessParentheses` 44, `UnnecessaryImport` 34,
`UnnecessaryFullyQualifiedName` 28, `PreserveStackTrace` 27,
`LiteralsFirstInComparisons` 25, `EmptyCatchBlock` 23,
`UseLocaleWithCaseConversions` 19, `ReturnEmptyCollectionRatherThanNull` 15,
`CloseResource` 13.

### `config/spotbugs/exclude.xml`

| Family | n | Reason |
|---|---|---|
| `EI_EXPOSE_REP`, `EI_EXPOSE_REP2`, `MS_EXPOSE_REP` | 333 | JPA pojo `Date`/array accessors; defensive copies fight EclipseLink change tracking, and the pojos are not shared across threads |
| `SE_TRANSIENT_FIELD_NOT_RESTORED`, `SE_COMPARATOR_SHOULD_BE_SERIALIZABLE`, `CT_CONSTRUCTOR_THROW` | 42 | entities are never Java-serialized; constructor-throw is the Spring bean-init idiom |
| `THROWS_METHOD_THROWS_*`, `REC_CATCH_EXCEPTION`, `MC_OVERRIDABLE_METHOD_CALL_IN_CONSTRUCTOR` | 94 | style opinions, pervasive and load-bearing in framework-shaped code |

2026-11-13: `REC_CATCH_EXCEPTION` (31 sites fixed) and both
`THROWS_METHOD_THROWS_*` detectors left this family at zero, leaving
`MC_OVERRIDABLE_METHOD_CALL_IN_CONSTRUCTOR` alone in it; see
`docs/dev/quality-gates.md`, "Generic catches" and the 2026-11-13 defect
rules.

**Design-time figure. Superseded by the Measured baseline table above, which
reports 134 gated -- not 132.** Kept because it is the record of what was
estimated before the fix pass, not what the fix pass found.

Leaves 132. That remainder is not filler — it includes
`SQL_NONCONSTANT_STRING_PASSED_TO_EXECUTE` (1),
`HRS_REQUEST_PARAMETER_TO_HTTP_HEADER` (1, response-splitting in
`ModDateHeaderUtil:96`), `OS_OPEN_STREAM` + `OBL_UNSATISFIED_OBLIGATION` (4
resource leaks), `NP_NULL_PARAM_DEREF` (2),
`AT_STALE_THREAD_WRITE_OF_PRIMITIVE` (5, in `ScheduledEntriesTask`,
`TrashPurgeTask`, `LuceneIndexManager`), `DM_DEFAULT_ENCODING` (14) and
`DM_CONVERT_CASE` (14).

### The 443 fixes, by risk

**Design-time figure, PMD 7.17.0 (307 + SpotBugs's 132 + CPD's 4 = 443).
Superseded by the Measured baseline table above, whose equivalent total is
500. The breakdown below was estimated before the fix pass started and was
not re-derived against the final 500; it is kept as the record of what was
estimated versus what the fix pass actually found, not as a description of
the finished wave.**

The count that matters is not 443 but its distribution:

- **~112 pure deletions** — unused imports, unnecessary fully-qualified names,
  useless parentheses, stray semicolons. No behavioural risk.
- **~200 local single-site edits** — `PreserveStackTrace`,
  `LiteralsFirstInComparisons`, `UseLocaleWithCaseConversions`,
  `DM_DEFAULT_ENCODING`, `DM_CONVERT_CASE`. Mechanical and individually
  reviewable.
- **~110 needing judgement** — `EmptyCatchBlock` 23, `CloseResource` 13,
  `BC_UNCONFIRMED_CAST` 15, and `ReturnEmptyCollectionRatherThanNull` 15, which
  is an API-contract change and needs its callers checked for null handling
  rather than a blind edit.
- **~20 genuinely interesting** — the SQL-injection candidate, the response
  splitting, the resource leaks, the stale-thread writes. These are findings,
  not chores, and each gets its own scrutiny.

`EmptyCatchBlock` and `DE_MIGHT_IGNORE` overlap; a catch block is fixed once
and clears both.

### Constraints during the fix pass

Two of this repo's standing hazards are live in a wave whose whole job is
deleting and tightening members:

- **Velocity is lenient, and a deleted member reachable from a template prints
  as literal text with no error and no log line** (CLAUDE.md, Templates). Any
  fix that removes or renames a member on a pojo, wrapper or model class — the
  `Unused*` rules, `UseUtilityClass`, `LooseCoupling`, and any signature change
  from `UnusedFormalParameter` — must be preceded by a grep of
  `app/src/main/webapp/themes` and `app/src/main/webapp/WEB-INF/velocity` for
  the member name. The rule is not "private members are safe": it is that the
  grep is the check, and it is cheap.
- **`ReturnEmptyCollectionRatherThanNull` changes an API contract**, so each of
  its 15 sites needs its callers examined for an existing `!= null` guard that
  becomes dead, or worse, an `else` branch that silently stops running.

**Empty catch blocks get one policy, not 23 improvisations.** In order of
preference: let the exception propagate if nothing depended on swallowing it;
log at the level the surrounding code already uses; or, when the throw is
genuinely expected and ignorable, name the variable `ignored` and add a
one-line comment saying why. A bare `catch (X e) {}` never survives, and a
comment that only says "ignore" is not a reason.

### `QualityGatePomTest`

In the fast suite, following `ItHarnessPomTest` / `ProductionComposeTest` /
`DesignTokenTest`. It pins the policy so it cannot rot silently:

1. All three checks bound to `verify` with failure-on-violation enabled.
2. Every `<exclude>`/excluded rule in both config files is preceded by a
   justification comment.
3. The excluded-rule set is **closed**: the test names the seven PMD rules and
   three SpotBugs families, so adding an eighth requires editing the test.
4. `GuardLogStatement`/`ProperLogger` are marked deferred with a pointer to the
   follow-up, distinguishing them from permanent exclusions.

## Acceptance criteria

1. `mvn -pl app verify` on a clean master tree passes all three gates with
   **zero** PMD violations, **zero** SpotBugs bugs, and **zero** CPD
   duplications at 200 tokens.
2. Introducing a violation of any gated rule — an unused import, a
   `String.toUpperCase()` with no `Locale`, a 200-token copy-paste — makes
   `mvn -pl app verify` fail, and the failure output names the file, line and
   rule.
3. `QualityGatePomTest` passes, and fails if an exclusion is added without a
   justification comment or without being declared in the test.
4. The unit suite, the diff-coverage gate, and `mvn verify -Pit` at **both**
   context paths are unaffected — no fix in this wave changes behaviour.
5. Total build time increase is under 30 seconds.
6. CLAUDE.md carries a section stating the policy, the exclusion rationale, and
   the rule for adding a new exclusion.

## Policy: defensive branches and the diff-coverage gate

Two tasks in this wave reshaped production code purely so a line could be
covered — Task 5 extracted a helper, Task 7 widened two methods to
package-private — and in both cases the branch was a defensive guard that no
real caller can reach today. That is the 90% diff-coverage gate steering the
design, and left unstated it would have repeated across the remaining tasks.

**The policy, decided rather than drifted into:** a defensive guard protecting
an invariant that is true today but **not compiler-enforced** is worth keeping
*and* worth testing — the invariant can break later, and the test is what
catches it. Widening a method to package-private to reach such a branch is
therefore permitted, but it must be **deliberate and labelled**: the helper
carries a comment saying it is package-private for test access to a defensive
branch, so a later reader does not mistake it for ordinary API.

What is **not** permitted is doing this reflexively to make a coverage number go
green. Where a branch is genuinely untestable without a disproportionate seam,
leave it uncovered and say why. The gate exists to stop untested code shipping,
not to dictate method visibility.

**A cautionary precedent from this codebase's own history, found during the
wave.** `ModDateHeaderUtil` echoed the client's `If-Modified-Since` back as
`Last-Modified`. Tracing it: the line arrived in 2006 as part of a
**mobile-device-detection** feature, where the "ETag" was a device-type name
used to force a re-render when a reader switched between standard and mobile.
This fork deleted that whole mechanism in `af48714fb` and **left the echo line
behind as orphaned residue**. A later coverage-raising pass (`856b35bae`) then
wrote a confident, plausible-sounding rationale for it — "keeps the value
byte-identical to the one the ETag was derived from... clients that compare
strings" — despite there being no ETag anywhere in the method by then, and
pinned it with a test named for the behaviour.

So a bug acquired a fabricated justification and a passing test, purely because
someone was raising coverage on code they had not traced. That is the strongest
argument in this document for the rule below: **a test written to cover a line
you have not understood will document whatever the line happens to do**, bug
included, and the next person to read it will believe the comment.

**The same pressure produces assertion-free tests, and it did here even after
being forbidden in writing.** A coverage top-up in this wave shipped two tests
that asserted a getter returns what a setter just set, on members the wave never
touched — inert padding that raises no real confidence and adds permanent
maintenance surface. They were caught in review and deleted. The rule, stated
plainly for the next person: **a test earns its place by being able to fail for
a reason someone cares about.** If you cannot name the breakage a test would
catch, it is not coverage, it is decoration — and the honest move when a line
resists testing is to leave it uncovered with a reason, never to pad the
denominator somewhere else.

## A suppression with a trigger condition, recorded because it has one

The 15 `BC_UNCONFIRMED_CAST` suppressions all rest on one justification: that
this application runs behind **embedded Tomcat's HTTP connector only**, with no
other protocol connector configured, so the cast to the HTTP-specific type
cannot fail. That is true of the deployment as built today, and it was verified
against `ServletRegistrationConfig`.

But it is a **deployment-configuration fact, not a language or API contract** —
unlike, say, a servlet-spec return type, which cannot change under you. Add an
AJP connector, or front the app with something that supplies a different
request implementation, and all 15 suppressions silently become
`ClassCastException` risks *while still claiming to be guaranteed*. The word
"guarantee" in those justifications is doing more work than the evidence
supports.

Nothing needs doing now. It is written down because a suppression whose validity
depends on a configuration nobody is currently thinking about is precisely the
kind of thing an audit two years from now will read as settled.

## Operational note for whoever deploys this

**The Lucene index may want a rebuild after this wave, on one specific
deployment.** Task 6 pinned `Locale.ROOT` on both sides of the search index's
category-term case folding — `IndexOperation` (write) and `SearchOperation`
(read) moved together, so the pair is symmetric from this build onward. But a
document written by an *earlier* build running under a non-English default
locale carries its category term folded under that locale's rules, and a
post-deploy `Locale.ROOT` query will not match it. The symptom is "search stops
finding entries in some category", which reads as a search bug rather than a
locale one.

This is near-zero risk on the shipped configuration — the Docker image runs
under a C/English default — and the fix is one click on the Maintenance page
(rebuild search index). Recorded because the symptom is so far removed from the
cause.

The same reasoning was checked and cleared for entry anchors: `saveWeblogEntry`
only derives an anchor when one is absent, so stored anchors and permalinks are
never re-derived.

## Follow-up (not this wave)

**Extract the shared boilerplate in `ScheduledEntriesTask` / `TrashPurgeTask`.**
The two classes carry near-verbatim duplicate getters and an identical
`init(String)` property-parsing block, sitting just under CPD's 200-token
threshold. Task 3 found that shortening one fully-qualified name in
`TrashPurgeTask.init()` merged two adjacent spans into one contiguous match and
tripped `cpd-check`, so that name is kept qualified behind a
`@SuppressWarnings("PMD.UnnecessaryFullyQualifiedName")`. **That suppression is
a scaffold, not the answer** — an artificially qualified name preserved so two
classes stay textually different is a smell in service of a metric. The honest
fix is hoisting the shared getters and parsing into `RollerTaskWithLeasing`,
which is a structural change to scheduled tasks and so was out of scope for a
deletions-only task. Doing it deletes the suppression.

**A theme-resource fallback that has never worked.**
`WeblogSharedTheme.getResource(String)` and `WeblogCustomTheme.getResource(String)`
are structured — and commented — as though they fall back to a weblog's own
media uploads when the shared theme has no such resource. They do not. Both look
up a `MediaFile` via `getMediaFileByOriginalPath` and then **never assign it into
the returned value**: `WeblogCustomTheme` returned `null` unconditionally, and
`WeblogSharedTheme` returned only the shared theme's own result. Task 4 found
this while removing the resulting dead stores, and correctly preserved the
existing (inert) behaviour rather than fixing a latent bug inside a cleanup
task — both reviewers verified the before/after outcome is byte-identical.

Fixing it is not a one-line dead-store repair: it needs a real `ThemeResource`
adapter over `MediaFile` to wrap the found file into, which is why it is a
follow-up rather than an incidental fix. Worth knowing before then: **no theme
resource has ever been served from weblog uploads by this path**, so anything
that appears to depend on that fallback today is actually being served some
other way.

**Delete `Utilities.encodePassword`, or make it unreachable.** Task 6 needed to
know whether pinning that method's charset could reinterpret stored password
hashes; the answer is no, because **it has no production caller at all** —
grep-confirmed independently, only its own unit test references it. Real hashing
goes through Spring Security's `DelegatingPasswordEncoder`.

That makes it worse than ordinary dead code. A future contributor looking for
"how does this project hash a password" will find a public, plausible-looking
`Utilities.encodePassword(password, algorithm)` and call it, bypassing the
configured encoder and the invariants CLAUDE.md's Passwords section exists to
protect. It is a fossil of the removed plaintext-password era and should be
deleted along with its test, not merely left uncalled. Neither PMD nor SpotBugs
will ever flag it — it is `public`, so no unused-member rule applies.

**A dead theme-resource import loop.** `ThemeManagerImpl`'s resource-import loop
cannot execute: **none** of the four bundled themes (`journal`, `portfolio`,
`travel`, `frontpage`) declares a `<resource>` element in its `theme.xml`, and
`getResources()` has no other population path. Found by Task 8 while chasing an
uncovered line, and confirmed independently by its reviewer — the line was not
untestable, it was unreachable. Either delete the loop or ship a theme that
uses it; leaving it is a third place where the theme-resource story is written
but not wired (see the `getResource()` fallback above).

**JCL→SLF4J migration — DONE.** Landed across seven batches (six for
`app/src/main`, a seventh for `app/src/test`): 178 main-source files + 17
test files, ~797 call sites, converted from `org.apache.commons.logging` to
`org.slf4j` with parameterized `{}` logging throughout. 6 `log.fatal` calls
were mapped to `log.error` (SLF4J has no fatal level); every hand-written
`isDebugEnabled`-style guard that existed only to gate string concatenation
was deleted, since a parameterized call already defers formatting until the
level is enabled and the guard was pure ceremony around it. The migration
resolved the two deferred rules differently, not identically: `ProperLogger`
came back genuinely clean (167 → 0) and is active now with no exclusion.
`GuardLogStatement` did not — reactivating it flagged 175 violations, not the
near-zero the design-time estimate above assumed — and it is excluded again,
permanently this time (see CLAUDE.md's Static analysis section for why: with
parameterized SLF4J the rule cannot distinguish a cheap accessor from
expensive work, and fires on idiomatic, correct code). The ruleset's
exclusion set went seven → five (both logging rules dropped) → **six**
(`GuardLogStatement` re-added on its new, permanent footing).

Kept here rather than deleted, because the record of what was deferred and
why it was worth doing is the useful part — including the parts that did not
go as the design-time estimate above assumed:

- **8 pre-existing stack-trace-loss bugs were found and fixed along the
  way**, all the same shape: JCL's `String`/`Object`-only overloads
  (`log.error(ex)`, `log.warn("msg " + ex)`) that stringify an exception
  with `Object.toString()` instead of attaching it as a `Throwable`, so the
  stack trace never reached the log at all. None of these compiled against
  SLF4J's API as written, which is exactly how each one surfaced — the
  migration didn't go looking for bugs, converting every call site to code
  that compiles found them for free.
- **`GuardLogStatement` was not, in fact, "at or near zero" once
  activated** — it came back with 175 violations against a design-time
  expectation of near-zero. The reason is a genuine gap in the earlier
  reasoning above, not a flaw in the migration: the rule doesn't just flag
  string concatenation, it flags *any* non-trivial argument expression
  (chiefly method calls) to a trace/debug/info/warn call, which is the
  ordinary shape of parameterized SLF4J logging with an accessor argument
  (`log.debug("entry {}", entry.getId())`). Three sites were genuine waste
  and got fixed outright (an eagerly-built `StringBuilder`/
  `MessageFormat.format()` result handed to a plain `log.info(String)`, and
  two `stream().collect()` calls that only needed to run when their level
  was enabled).
- **The remaining ~172 sites, across 74 classes, went through two rounds.**
  First cut: suppressed with `@SuppressWarnings("PMD.GuardLogStatement")` at
  the class declaration rather than the ruleset, reported as a genuine
  false-positive judgement call. That was overruled on review and replaced
  with a single `<exclude name="GuardLogStatement"/>` in
  `config/pmd/ruleset.xml` — all 74 class-level annotations removed. The
  reasoning: a class-level suppression is *broader* than a ruleset exclusion
  for those classes (it silences the rule for all current **and future**
  code in them, not just today's cheap accessors, so it is not the more
  conservative choice it looks like); 172 sites sharing one reason across 74
  classes is a single family-wide policy applied 74 times, which is exactly
  what this repo's own exclusion policy (config files for whole families,
  site-level suppressions for one-offs) reserves the config file for; and
  one exclusion reviewed once in one file beats 74 annotations nobody will
  ever audit as a set. See CLAUDE.md's Static analysis section for the full
  reasoning, and `config/pmd/ruleset.xml`'s own comment on the exclusion for
  the same four points in place.

**A `CPD-OFF` blind spot.** The `CPD-OFF`/`CPD-ON` markers around the render
caches (Decision 6) remove the bracketed region from CPD's token stream
entirely, not merely excuse the existing match — so a future fourth render
cache that copy-pastes the marked key-builder logic would be invisible to the
gate rather than flagged. Narrow and acceptable given how rarely a new render
cache is added, but worth recording rather than discovering by surprise.

**A pre-existing resource leak on the 304 path, recorded rather than fixed.**
`PreviewResourceServlet.java:164`, `ResourceServlet.java:163` and
`MediaResourceServlet.java:160` each `return` on the "not modified" branch of
`ModDateHeaderUtil.respondIfNotModified` *before* reaching the `finally {
resourceStream.close(); }` further down, leaving `resourceStream` open on
every 304 response. This predates this wave — it is not a regression the
static-analysis pass introduced — but `6fc0272a0`'s title ("close streams and
readers on every path") is the specific claim this path contradicts, and a
304 is the request shape a returning reader's browser produces most often for
theme CSS and images, i.e. not a rare corner of the traffic. Left unfixed here
deliberately: this is a behavioural change to a hot serving path at the tail
end of an already-large wave, and it deserves its own test (asserting the
stream is closed on the 304 branch, not just the 200 branch) and its own
review rather than riding along inside a documentation-accuracy pass.
