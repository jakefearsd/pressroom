# GoF refactoring wave (2026-11-19)

A design review of `app/src/main/java` (five read-only reviewers, one per
subsystem, claims spot-checked) found eleven latent bugs and ~30 places where
a Gang of Four pattern removes duplication that has already drifted. This
spec is the checklist for fixing the bugs first and then working through
Tier 1 and Tier 2. Tier 3 (borderline) is out of scope for this wave.

Line numbers are as of `9ef6b4c5c` (release 0.13.0) and will drift.

## Working rules for every implementer

- **Base check first.** `git -C <worktree> rev-parse HEAD` must equal the sha
  in the brief; stop and report if not.
- Work only inside the assigned worktree. Commit your own work on its branch
  (one commit per item, message ends with the attribution trailer the brief
  gives). **Never push.** Never edit this spec.
- **TDD.** Bug fixes: the failing test first, seen to fail for the expected
  reason. Refactors: characterisation tests where coverage is thin, written
  first and passing against the old code, labelled as such in their javadoc.
- Respect the hazards in CLAUDE.md: Velocity reaches members by name (grep
  `app/src/main/webapp/themes` and `app/src/main/webapp/WEB-INF/velocity`
  before renaming/removing any public member of a pojo, wrapper or model);
  `@Lazy Weblogger` via constructor; name every `@RequestParam`; SLF4J
  throwable last; the one authorization path (`WeblogOwnership` /
  `BaseController.lookup*`) is never bypassed; the API answers 404 not 403.
- Every Maven command runs in the foreground with a Bash `timeout`. Iterate
  with `mvn -ntp -q -pl app test -Dtest=...` (timeout 600000). Finish with
  `mvn -ntp -pl app verify` (timeout 600000) — zero PMD/CPD/SpotBugs/javac
  warnings and JaCoCo floors held — then `mvn -ntp -pl app jacoco:report` and
  `bin/check-diff-coverage.sh <base-sha>`.
- A refactor that turns out not to pay (more code, less clear) is dropped
  with a one-line reason in the report, not forced.
- Report in under 20 lines: commits (short sha + subject), decisions taken,
  anything deferred, the verify and diff-coverage results.

## Bugs (wave 1: minimal fixes, test-first)

- **B1 Preview clones drop fields.** `WeblogEntry.setData` omits tags,
  attributes, `jsonLdType`, geo and event fields, `newsletterSentAt`;
  `Weblog.setData` omits siteSpace, customDomain, about, iconPath, business,
  place fields, bookingUrl, newsletterListUuid. *Done when:* `copy()` on both
  copies every persistent field into a new instance without aliasing the
  source's mutable collections; a reflection test fails when a newly added
  field is neither copied nor on an explicit exclusion list; `setData`
  delegates; `copyAsDraft` is copy-then-clear and keeps the travel fields;
  the preview pager's clone keeps tags/geo/jsonLd (Prototype, T-item folded in).
- **B2 Entry trash purge races a restore.** `JPAWeblogEntryManagerImpl.purgeTrash`
  removes without re-reading under lock. *Done when:* each candidate is
  re-read under `PESSIMISTIC_WRITE` and removed only if still trashed and
  past cutoff (as `JPAWeblogPageManagerImpl.removeIfStillTrashed`); the
  cutoff rule is the same for entries and pages (`trashedAt <= cutoff`).
- **B3 `RemoveEntryOperation` NPE.** *Done when:* the id is captured before
  the re-fetch and a hard-deleted entry is still removed from the index.
- **B4 Pending-review notice only from the JSP editor.** *Done when:* bulk
  publish and the API send `MailUtil.sendPendingEntryNotice` under the same
  condition as `EntryEditController.afterSave` (entry pending, mail configured).
- **B5 Rail never highlights sub-actions.** Multi-action controllers overwrite
  `actionName` after `populateCommonModel` built the menu with a null action.
  *Done when:* `populateCommonModel(request, model, Screen)` with
  `record Screen(String actionName, String pageTitleKey)` builds menu,
  switcher and model from one value; the post-hoc overwrites are gone; a test
  shows `entryEdit`/`entryAdd`/`categoryEdit`/`createUser`/`modifyUser` select
  their rail item.
- **B6 `MapPins.PIN_TAG` stack overflow.** *Done when:* the pattern uses the
  expander's possessive form and a ~5,000-character unclosed `[pin` inside a
  `[map]` returns without `StackOverflowError` (test).
- **B7 `[faq attr="x"]` renders but emits no FAQPage.** *Done when:*
  `FaqBlocks.parse` accepts the same open-tag grammar as the page side (test).
- **B8 Pages store whitespace-only SEO fields.** *Done when:* `PageBean.copyTo`
  trims metaTitle/searchDescription/canonicalUrl to null like `EntryBean`.
- **B9 Null lock text: entries 404, pages 409.** A null lock means the row is
  gone. *Done when:* the page API answers 404 page-not-found, like entries.
- **B10 Task settings validate inconsistently; double release.** *Done when:*
  every task rejects a non-positive or unparsable interval/lease (default +
  warning), and each run releases the Weblogger exactly once.
- **B11 Preview by-tag pager is the live pager.** *Done when:* every
  `PageModel` pager overload routes through one protected
  `newEntriesPager(EntriesPagerQuery)` that `PreviewPageModel` overrides
  (Factory Method); a test shows the preview by-tag pager shows drafts.

## Tier 1

- **T1.1 Revisioned content, three layers.** (a) business/jpa: one
  package-private `RevisionLog<O,R>` behind entry and page record/keep/list.
  (b) JSP: `RevisionActions` behind keep/restore/diff/preview in
  `EntryEditController` and `PageEditController`, taking only owners already
  resolved through `lookup*`. (c) API: `RevisionedContent` + `ContentGuard`
  behind `EntriesWriteApi`/`PagesApi` hash guard and revision endpoints;
  `@*Mapping` methods stay so openapi and MCP routes are unchanged; problem
  types stay distinct. *Done when:* each layer has one implementation of the
  shared steps and the existing endpoint/controller/manager tests pass.
- **T1.2 Trash lifecycle.** `TrashLifecycle<T>` owns state guards, the
  trashedAt stamp, cutoff and purge-under-lock for entries and pages; entries
  gain the "already in the trash" / "not in the trash" refusals pages have.
- **T1.3 Entry-changed Observer.** Managers publish an after-commit
  `EntryChanged` event; index and cache subscribe; the ~26 hand-written
  reindex/invalidate calls in controllers, API, `ScheduledEntriesTask` and
  `EntryDeletion` go. *Done when:* no caller outside the subscribers calls
  `addEntryReIndexOperation`/`removeEntryIndexOperation`/
  `CacheManager.invalidate(entry)` for a save, and no entry is indexed twice.
- **T1.4 Lucene write operations.** `WriteToIndexOperation.doRun()` final, with
  `prepare()`/`write(IndexWriter)`/`afterWrite()`/`onFailure()` hooks and one
  release point; document building moves to an `IndexDocuments` factory.
- **T1.5 Rendering servlets.** `WeblogRequestServlet<R>` (parse, 404 policy,
  site-space rule, guarded error sending) under all seven rendering servlets;
  `CachedRenderingServlet<R>` for Page and Feed, preserving step order.
- **T1.7 One shortcode grammar.** `ShortcodeExpander.walk(ctx, text,
  ShortcodeVisitor)` + shared `ShortcodeSyntax`; `MapPins`, `FaqBlocks` and
  `MapShortcode.pinsInEntry` use it.
- **T1.9 Editor modes.** `enum EditorMode {ADD, EDIT}` and `enum SaveIntent
  {DRAFT, PUBLISH}` (`status(pubTime, mayPost)`, also used by bulk publish);
  the four save handlers fold into one.
- **T1.10 Task settings.** `TaskSettings` record parsed once in
  `PressroomTaskWithLeasing`; per-task copies and CPD workarounds go.
- **T1.11 Period pagers.** `WeblogEntriesPeriodPager` + `enum Period`; Day and
  Month remain as thin subclasses; the CPD-OFF block goes.

(T1.6 Prototype and T1.8 Screen are folded into B1 and B5.)

## Tier 2

- **T2.1** `ImageDerivatives.rebuild` behind thumbnail/regenerate/crop, one
  failure policy (a failed admin thumbnail is best-effort everywhere).
- **T2.2** All hand-counted JPQL parameter building moves to `JpqlParameters`.
- **T2.3** `IdLookupCache<K,E>` replaces the anchor/handle/username id maps,
  validating every hit.
- **T2.4** Transition methods on `WeblogEntry` (`publish(now)`,
  `schedule(at)`, `moveToTrash(now)`, `restoreFromTrash()`), used by callers.
- **T2.5** `ClearPolicy<T>` replaces each `*_CLEARABLE` list + switch pair.
- **T2.6** `FieldErrors` used by media, weblog-settings and user writes;
  `FieldErrors.value()` replaces the `String[1]` workaround.
- **T2.7** Delete `PluginManager.applyWeblogEntryPlugins` (test-only caller).
- **T2.8** `PropertyType` enum shared by `AdminApi` and `GlobalConfigController`.
- **T2.9** `enum DocumentKind` replaces `SearchOperation`'s `boolean pages`.
- **T2.10** `enum StartWindow` for tasks; one time-zone rule.
- **T2.11** `ThreadManager.background()` + `UnitOfWork.releasing(...)` replace
  the hand-written release-when-done wrappers.
- **T2.12** `ConfigSource` (Static/Runtime/Layered) typed accessors; existing
  static methods stay as wrappers; an invalid value in a decision path fails
  closed rather than becoming 0/-1 (audit callers first).
- **T2.13** Builders: `DiscoverySlots` fill, `PressroomViewResolver`
  definitions, `SitemapXml` (escaping in one place), `ModelInitData` +
  `ModelLoader.requireRequest`, `CacheKeys.appendFeedSegments`.
- **T2.15** `ResourceSource` chain for Resource/PreviewResource servlets;
  `TemplateLookup.namedOr/byAction`.
- **T2.16** `BaseController.flashAction(...)` Command for flash/redirect/catch.
- **T2.17** `SeoFields` interface + `SeoForm.applyTo` shared by entry and page beans.
- **Cleanup:** delete the `Job` interface, the unused 11-argument
  `WeblogEntry` constructor, and `DateUtil`/`Utilities` members with no
  callers in main, tests or templates.

(T2.14 is folded into B11.)
