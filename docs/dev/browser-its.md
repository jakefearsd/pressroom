# Browser integration tests

Developer notes moved out of `CLAUDE.md` (which is sent with every
request) so they are read only when this area is being changed. Section
names are unchanged, so an older "see CLAUDE.md, <section>" pointer
resolves through the index at the end of `CLAUDE.md`.

## The IT harness cleans up by identity, not by pidfile

Everything an IT run creates carries its id (`${it.run.id}` = build
timestamp + reserved HTTP port); the app JVM's command line adds
`-Dpressroom.it.run=<id>` and `-Dpressroom.it.owner=<pid>@<start time>` (owning
build). The pidfile scheme it replaced silently leaked an app JVM, a
chromedriver and a Docker volume per abnormal run
(`build-helper:reserve-network-port`'s free port meant leaks never collided).

Five mechanisms in `it-selenium/src/test/script/`:

- **`start-app.sh` kills what it started on every failure path** (`trap
  cleanup EXIT`; INT/TERM become exits). Its readiness timeout used to
  `exit 1` with the JVM running, skipping `post-integration-test` and so
  `app-stop`.
- **`sweep-stale.sh` runs first in `pre-integration-test`**, reaps what
  earlier runs left and **reports every pid and container it takes** —
  silence was the defect.
- **`supervise-run.sh` starts before `pg-start`, detaches, and outlives the
  build.** Failsafe protects cleanup from *test* failures only; an
  *infrastructure* failure in `pre-integration-test` (`pg-wait-ready`,
  `migrate.sh`, the seed, `app-start`) or a Ctrl-C skips
  `post-integration-test`. It kills the run's app, its chromedrivers
  (recorded while their JVM lived; unattributable after), and the
  container (`docker rm -f -v`, always `-v`).
- **Staleness is decided by the owning build, never by "an IT process
  exists"**: a concurrent `mvn verify -Pit` is legitimate and must survive
  another run's sweep.
- **The supervisor marks itself `-Dpressroom.it.supervisor=`, not
  `-Dpressroom.it.run=`.** A forked subshell (command substitution, pipeline
  halves) shows its *parent's* command line in `ps`; a supervisor in its
  own run would kill its own subshells (`ItHarnessLeakTest` pins it).
- **Every marker lives in a namespace, and a sweep sees only its own**
  (`IT_NAMESPACE`, default `pressroom.it`, so real runs share one and a real
  sweep still finds every real leftover). `ItHarnessLeakTest` gives each test
  a random one. Before that it passed alone and failed beside another build:
  a sweep is machine-wide, so a concurrent sweep (`mvn verify -Pit`, or this
  class in another checkout) reaped the test's deliberately orphaned fixtures
  before the test's own scripts could -- reproduced with a sweep looping
  beside it, which killed the supervisor under test so it never reaped its
  recorded chromedriver -- and the test's own sweeps reaped other builds'
  leftovers. Each test also gets its own `IT_CONTAINER_PREFIX`, and that half
  is not optional: from another namespace a LIVE real run looks unclaimed,
  so a test sweep on the real prefix would remove its container. Pinned by
  `aSweepNeverTouchesAStaleAppInAnotherNamespace`. Never set either variable
  for a real run: its leftovers would be invisible to the next real sweep.

Per run: containers `pressroom-it-postgres-<run id>` and
`pressroom-it-keycloak-<run id>` (no fixed-name 409 needing `docker rm`); the
supervisor is handed both, and the sweep looks under both prefixes (the
Keycloak one is derived from `IT_CONTAINER_PREFIX`, never defaulted on its
own, so a test's sweep cannot reach a real run's Keycloak);
`pg-stop` passes `<removeVolumes>true</removeVolumes>` (the
default leaked an anonymous volume even on success: `postgres:18` declares
`VOLUME /var/lib/postgresql`); pidfiles, logs and work dirs, so a leaked
run's `app.log`/`app.pid` survive; `it-work/app-latest.log` symlinks the
newest.

**Keycloak (reader sign-in).** `pg-start` starts a second image,
`quay.io/keycloak/keycloak:26.0` (`it.keycloak.image`), in dev mode on the
reserved port `it.kc.port`, importing realm `pressroom-it` from
`it-selenium/src/test/resources/keycloak/pressroom-it-realm.json`: a confidential
client `pressroom-it` and seven users (`reader-it`, `unverified-it`, `noname-it`,
plus `writer-it` and `disabled-it`, which only the promote and disable tests
change, and `commenter-it` and `commenter2-it`, which belong to `CommentsIT`
alone: it renames and deletes their Pressroom accounts). `noname-it`'s email is `nameless-reader@example.com`: a local part
that is not the username, so criterion 18 tells the email fallback from a
`preferred_username` one. "Not signed in" is asserted as a 302 **to the login
page** (`SsoSignInIT.assertSentToLogIn`), never a bare 302: a 302 to
access-denied is how a signed-in account is refused. The wait is the realm's discovery document, which answers only
after the import (about 15 s). `pressroom-it.properties` points `sso.oidc.*` at
`http://localhost:${it.kc.port}` (localhost, because Keycloak takes its
issuer from the Host it is asked on), so **SSO is on for the whole suite**
and every login page shows "Sign in with Keycloak". The client secret and
passwords are test-only values, kept in those two files. `VERIFY_PROFILE` is
disabled in the realm, or Keycloak 24+ stops a user with no name at an
"Update Account Information" form. `SsoSignInIT` drives it at both context
paths, and `CommentsIT` signs its readers in through it with the shared
`PressroomIT.signInWithKeycloak`. The sign-in callback is built from the Absolute URL, so the class
sets `site.absoluteurl` to `baseUrl()` (`PressroomIT.setSiteAbsoluteUrl`) once,
restores it in `@AfterAll`, and holds `GLOBAL_CONFIG` **write** for the
whole class, as `VirtualHostIT` does: anything that writes
`site.absoluteurl` takes that lock.

`PressroomPostgresContainer` (the unit suite's shared, never-stopped container)
adds a JVM shutdown hook behind Ryuk, one process that can be missing.

Tests (fast suite, not `-Pit`): `ItHarnessLeakTest` (real scripts, fake
processes, and a fake `docker` on `PATH` for the container passes),
`ItHarnessPomTest` (`removeVolumes`, every container named per run and handed
to the supervisor, plugin order putting `app-stop` before `pg-stop`),
`PressroomPostgresContainerTest`.

## Permuting global runtime flags

Browser tests permute global runtime properties via `PressroomIT.setGlobalFlag`
(`setGlobalFlags` for several in one save), which drives the real Admin
Settings page, returning the old value. One shared instance: every caller
must restore in a `finally`.

## Permutation coverage in the browser suite
Four classes carry the configuration matrix:

- `ThemeMatrixIT` — every bundled theme rendering one entry carrying
  `[image]`/`[gallery]`/`[map]`/`[faq]`, on the home page and the permalink
  (different templates). One looping test, since the fixture costs ~9s.
  `frontpage` is excluded — it renders through `$site`, which exists only
  for the weblog named by `site.frontpage.weblog.handle`.
- `WeblogConfigMatrixIT` — per-weblog settings: locale, `entryDisplayCount`,
  and `active` (which also withdraws the weblog from the sitemap index). Each
  test owns its weblog and touches no global state.
- `GlobalConfigMatrixIT` — three tests that mutate site-wide state: the
  feature-refusal switch (uploads/weblog-creation off, batched),
  `groupblogging.enabled`'s own refusal (a user who already owns a weblog is
  refused a second one — its own test, so the assertion cannot pass for the
  wrong reason), and the entry-URL word-separator. One of six classes that
  call `setGlobalFlag`; "Browser ITs run class-parallel" lists them.
- `ScheduledEntryIT` — a future-dated entry is withheld from pages, its Atom
  feed, and the sitemap.

Comment-related tests and their `postCommentDirectly`/`approveComment`
helpers were deleted with the comment subsystem in W1; the invite/accept
ceremony (`invite.rol`) went in W2 — `MembersController.grant()` adds a
collaborator directly and has no `groupblogging.enabled` check of its own
(only the menu entry is gated).

No reachable browser coverage, documented rather than silently skipped:
- `user.hideUserNames` — every bundled theme and feed uses
  `$entry.creator.screenName`, never `.userName`, so the flag changes nothing
  in shipped output. (Per-weblog `analyticsCode` was uncoverable too, gated
  on `weblogAdminsUntrusted` being off; W2 deleted it — see Analytics.)
- `ScheduledEntriesTask` promoting a scheduled entry: an entry is only
  `SCHEDULED` when its pubtime is >1 minute out and the task cadence is whole
  minutes, so observing it costs 1-3 minutes with real variance.

## Browser ITs run class-parallel

`it-selenium/src/test/resources/junit-platform.properties` runs test
**classes** concurrently (fixed parallelism 4) and **methods** on one thread.
Re-measured 2026-09-09 across 35 classes: 412s of test time in 3m18s of
module wall clock. The profile is flat — `ThemeIT` at 27.2s is the slowest,
`VirtualHostIT` 20.4s, nothing else over 25s. Performance numbers in this
file go stale; re-measure before quoting one.

Methods stay serial on purpose: an IT class is a narrative (create a weblog,
edit it, publish, assert the rendered page) whose methods share fixtures
built in `@BeforeAll`; the parallelism worth having is across classes.

Two resource locks, whose failure modes both look like something other than
a race:

- **`PressroomIT.GLOBAL_CONFIG`** — held **write** by the six classes that
  call `setGlobalFlag`: `GlobalConfigMatrixIT`, `ThemeIT`
  (`CUSTOM_THEMES_ALLOWED`), `ThemeMatrixIT` (`uploads.enabled`), `ApiIT`,
  `VirtualHostIT`, and `McpIT` (`uploads.enabled`, on its one import-refusal
  method only; its two weblog-creating methods hold it **read**, the rest
  none), and by `SsoSignInIT` and `CommentsIT`, which set `site.absoluteurl`
  through `setSiteAbsoluteUrl` as `VirtualHostIT` also does (both sign readers
  in, and the sign-in callback is built from it). One shared app instance holds
  one set of runtime properties, so two overlapping would each see the
  other's flag. **A seventh `setGlobalFlag` class must take this lock** —
  nothing enforces it,
  and the symptom is a wrong-looking assertion, not a concurrency error.
  Held **read** by `PublicSurfaceIT`'s site-root test (`/` is the site
  directory only while no front-page weblog is set, and `ColophonIT` sets
  one), and by `RouteSweepIT`, which visits every admin route and so is
  exposed to every site-wide flag (on a `/pressroom`-prefix run with
  `groupblogging.enabled` off, `createWeblog.rol` returned a healthy 200
  with no form — the `categoryEdit.rol` failure mode the sweep exists to
  catch — because `CreateWeblogController` answers `.GenericError` to an
  admin who already owns a weblog), and by the media classes, which need
  `uploads.enabled` true (`GlobalConfigMatrixIT` sets
  `uploads.enabled=false`; with uploads off the media page renders no
  buttons, and the failure reads
  `Element not found {button[formaction$='entryAddWithMediaFile.rol']}`).
  `MarkdownContentIT`'s publishing test holds it read too, as McpIT's
  weblog-creating methods do: it creates a weblog.
- **`PressroomIT.SHARED_MEDIA`** — held **write** by the five classes that
  upload, crop or delete media on the shared `WEBLOG_HANDLE` (`GalleryIT`,
  `MediaCropIT`, `MediaBulkUploadIT`, `EditorSeoIT`, `EditorUploadIT`); **read** by
  `ErrorCasesIT`, which only browses the media page.

The `GLOBAL_CONFIG` read modes preserve the speed: readers exclude the
mutators, not each other.

## BrowserHealth: three checks, and the third watches the watcher

Every check reports by finding something wrong in what it recorded, so
**every one passes vacuously when nothing is recorded**: a recorder that has
stopped seeing traffic turns the whole suite green at once. The listeners ride
a CDP binding pinned to one Chrome major (`selenium-devtools-v155`); Chrome
ships a new major every few weeks, and Selenium answers a mismatch by
silently falling back to its nearest binding and logging a line nobody reads.

- `blindnessReport` closes it: an http(s) page with zero recorded responses
  is a broken monitor, not a clean page, and fails saying so. The
  discriminator must come from outside CDP or it is circular — the `page`
  field is itself set by a CDP event, so a blind recorder also believes it
  is on `about:blank`; `getCurrentUrl()` goes over the WebDriver protocol,
  and the two disagreeing is the signal. A test that never navigates stays
  on `about:blank` and is correctly exempt. Proof: with the
  `Network.responseReceived` listener disabled, 29 of `RouteSweepIT`'s 31
  tests fail; before the check, all 31 passed.
- **Keep `selenium-devtools-vNNN` at the newest version the pinned Selenium
  ships.** It is pinned explicitly in `it-selenium/pom.xml`, not taken
  transitively, so a Selenium bump that drops that version breaks the build
  rather than degrading at runtime (4.49.0 drops v150, which 4.47.0
  shipped).
- `assertNoBrokenResources` catches any sub-resource that came back
  4xx/5xx.
- `assertNoFailedRequests` catches requests that produced no response at
  all, closing the first's blind spot: a stylesheet whose URL 404s gets an
  HTML error page, and Chrome, refusing the wrong content type, *aborts* the
  load — no `Network.responseReceived` is emitted, so a theme whose CSS had
  gone missing rendered unstyled and passed. Webfonts refused by the page's
  own CSP arrive the same way. The discriminator is what may legitimately be
  cancelled: page script starts `Image`/`XHR`/`Fetch` and may abandon them
  (Leaflet cancels ~48 tiles per map render; the live preview coalesces
  in-flight requests as the author types), and a `Document` navigation is
  cancelled by navigating again. A `Stylesheet`, `Script` or `Font` is
  declared by the document and nothing cancels it, so an abort there means
  the browser refused it. A **blocked** request is never excused, whatever
  its type.

## A click is not a barrier: wait for the answer

**Symptoms, all one defect** (the two direct 403 sightings were 2026-08-19
and 2026-09-14, on parallel runs; the "blind" and `#entry` symptoms are from the
nightlies of 2026-08-31 to 2026-10-01, CI running the suite serially, so nothing
else was in flight): a 403 on a form's POST target reported by `BrowserHealth`
(`ErrorCasesIT.aDuplicateUserNameIsRefused`, `createUser!save.rol`); "the browser is on <POST target> but this recorder
saw no network traffic at all" in the NEXT test of the class
(`ErrorCasesIT.anUnknownWeblogIsNotFound`, which never touches the browser);
`Element not found {#entry}` right after signing in as another user
(`MultiUserJourneyIT`, line 128 before 3b6ed5537).

**Mechanism.** Chrome runs a form submission (a click's, or a script's
`form.submit()`) as a task of its own after the click event, so the WebDriver
click can return before the request exists. On a loaded runner it does: the
"blind" failure leaves the browser on a POST target that only the PREVIOUS
test submits, which is possible only if that test ended with its POST still
unanswered. `BrowserHealth.settle()` waits for silence among events, and an unsent request
makes none, so it returns at once too. A test that then reads the page reads
the page it clicked on; one that signs out (`logout()` clears the cookie jar
and invalidates the session) sends the late POST with no session, and
`CsrfFilter` refuses it (`Invalid CSRF token found`) with a 403 whose
response also carries a fresh anonymous session cookie. Where that answer
lands is timing only: in the same test's health check; over the cookie
`signInAs` has just installed for the next user, so the next page bounces to
the login form (the `#entry` failure); or -- the submission still carries the
click's user activation -- as a navigation that overrides the next test's
`about:blank`, so ChromeDriver's `get` returns on the POST's answer before the
new recorder's listeners exist (the "blind" failure).

**Rule.** A click that navigates is followed by a wait only the NEW page can
satisfy. `PressroomIT.clickAndAwaitNewPage(element)` (and
`awaitNewPageAfter(action)` for a click plus `confirm()` or a scripted
submit) waits for the old `<html>` to go stale, which happens only when the
answer commits. Waits that look sufficient and are not: `#entry` after an
editor save (the editor you clicked on has one), `#messages` on a page that
already shows a flash, a username input on a search form you typed that
name into, the URL straight after clicking a link, an ABSENCE (no
`#messages`) read straight after the click, anything after `settle()` alone.
A click that cannot navigate (a `required` field left empty, say) must not
use it: the wait fails after 10 s saying no navigation started.

**Reproduction.** `IT_LATE_CLICK_MS=<ms> mvn verify -Pit` makes every trusted
click on a submit button or navigating link take effect that late
(`BrowserHealth.delayNavigatingClicksIfAsked`): the starved runner made
deterministic. At 150 ms, before the fix, running the 7 suspect classes
(run e1), 6 tests in 5 classes failed (`MultiUserJourneyIT` x2,
`EntryRevisionIT`, `UserAdminIT`, `AuthoringJourneyIT`, `MediaBulkUploadIT`),
every one a wait the old page satisfied; the whole suite ran under late-click
only after the fix (run f1), and all 140 passed. The CI failures themselves
need the POST to land in a narrower window, so they were reproduced by
repeating the exact sequence over a sweep of delays: the `ErrorCasesIT` pair,
144 repetitions at 20-135 ms, failed 14 times (13 times with the CI "blind"
message, once with the CI 403) with 70 `Invalid CSRF token` refusals in the
app log, then 0 failures and 0 refusals with `clickAndAwaitNewPage`; Bob's
delete then Alice's editor, 72 repetitions at 90-136 ms, failed 12 times with
the CI `Element not found {#entry}` (13 refusals), then 0 and 0. A fixed delay
cannot catch a test that asserts an ABSENCE and passes on the old page (that
is how `ErrorCasesIT` hid) nor a scripted `form.submit()`, so review those by
hand. Run `IT_LATE_CLICK_MS=150` after adding a test that clicks.

## Run the browser suite at BOTH context paths before shipping routing changes

`mvn verify -Pit` covers the root context; `mvn verify -Pit -Dit.context.path=pressroom`
alone exercises a servlet prefix end-to-end. The prefix pass found a defect
the root pass could not; `SeoController.robots()`'s test, already under
`/pressroom`, had hardcoded the buggy url. A fixture pinning a context path must
*derive* its expected url, never hardcode a shape.
