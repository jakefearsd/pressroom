# Rules out of the JSP controllers, screen data as view models, parity that outlives the forms (2026-11-20)

Tiers 3 and 6 (item 13) of the React-readiness groundwork (memory: React
readiness; the tier list is `.superpowers/react-readiness-tiers.md`). Two
goals, both worth having even if React never comes:

1. **One home per rule.** A validation or decision the JSP controller makes
   inline, and the API makes again in its own words, drifts. The sweep below
   found five families where it already has. Each rule moves into a shared
   class in `com.wikantik.pressroom.ui.controllers` (the existing pattern:
   `WeblogSettingsRules`, `CustomDomainRules`, `Memberships`,
   `EntryFieldRules`), and both surfaces call it. When the JSP controllers are
   eventually deleted, the tests on those classes keep counting toward
   coverage instead of disappearing with them.
2. **Screen data as plain values.** The data a composite admin screen shows
   is assembled into a record by a class that takes the user, the weblog and
   the filters and touches no servlet or Spring MVC type. The JSP renders the
   record; later, a JSON endpoint returns it.

Plus Tier 6 item 13: `FormBodyParityTest` is re-anchored so that deleting a
JSP form bean does not silently delete the parity guarantee.

No API resource is added in this tier (Tier 4 adds them). Where the API
already re-implements a rule, it is changed to call the moved rule, and where
the two surfaces disagree today the task says which answer wins.

## What the sweep found

Swept `ui/controllers/{editor,admin,core}` (80 classes, ~18k lines) for
inline `addError`/`addFieldError` decisions and composite model assembly, and
compared each with its `/api/v1` twin.

| Family | JSP site (inline rule code) | API twin | Drift today |
|---|---|---|---|
| Site settings | `GlobalConfigController` `accept`, `applyFrontDoorChoice`, `isPublishedSitePage`, `validatePrivacyPage`, `touchEveryWeblog` (~180 lines) | `AdminApi.patchConfig`, `requireValidType` | **API stores a front door that is no published page, the site space as front-door blog, an unpublished privacy page; untrimmed strings; never touches weblogs, so a changed site name or privacy link stays out of cached pages** |
| Accounts | `UserEditController.validateNew`/`validateEdit`, `UserNames.problem` | `AdminApi.createUser`/`patchUser`/`requireValidUserName`, `EMAIL_PATTERN` | Username rule restated (same logic, second copy); **email checked by commons `EmailValidator` in the JSP and by a hand-written regex in the API** |
| Memberships | `MembersController.refused` (admin count over the form), invite checks; `UserWeblogsController.isLastAdmin` and its level/reader/group-blogging checks | none (Tier 4 adds members) | "One admin required", "reader accounts cannot be members" and "group blogging off" each written twice, the first two differently |
| Categories | `CategoryEditController.myValidate`, `CategoryRemoveController` last-category guard | `CategoriesApi.create`/`update`/`delete` | **JSP refuses a name HTML-escaping would change (`&`, `<`, `>`, `"`, `'`); API accepts it.** API trims and checks column length; JSP does neither |
| Media | `MediaFileViewController.createNewDirectory`, `MediaFileEditController.save` duplicate-name check | `MediaApi.createDirectory`, `MediaDtos.applyPatch` | **JSP refuses `/` anywhere in a folder name, API strips one leading `/`; API reserves `default`, JSP leaves it to the manager. API rename has no duplicate-name check at all** |
| Pages | `PageEditController.checkSlugNotInTrash`, `checkUntick`, `saved` (maps the manager's exception *message text* to `slugReserved`/`slugInvalid`) | `PageDtos.requireUsableSlug`, `PagesApi.requireSlugAvailable`, `checkStrand` | Slug rules restated in the API (its javadoc says so: "the second place to update"); **a live duplicate slug is a 409 over the API and a generic error in the editor** |
| Strands, pins | `EntryEditController.checkedStrand`, `checkPinLimit` | `EntriesWriteApi.strandFor`, `requirePinRoom` | Same rule, two copies; no behavioural drift found |
| Entry list | `EntriesController.execute` (criteria, status, fetch-one-more paging, views column) | `EntriesApi.list` | Criteria and paging built twice; **an unknown `status` on `entries.rol` is an uncaught `IllegalArgumentException` (500)** |

Already one home (no work): comment moderation (`CommentService`, both
surfaces); weblog settings (`WeblogSettingsRules`); weblog creation
(`WeblogCreationRules`); theme switch (`SharedThemeRules`); entry titles,
times and canonical URL (`EntryFieldRules`); SEO fields (`SeoFields`);
ownership (`WeblogOwnership`). Composite screens: `TrafficView` and
`SiteTrafficView` are already records built by static factories from data,
period, locale and clock; the admin menu is already filtered by a pure
`MenuHelper.buildMenu` with injected collaborators; `AccountRows`,
`MainMenuController` and `EntriesController` write straight into the `Model`.

## Global constraints

- **Behaviour-preserving first.** Each task starts with characterisation
  tests, written first and passing on the old code, that pin both surfaces'
  current answers for every case the task moves (JSP: model errors, field ids,
  message keys and arguments, the view name; API: status, problem `detail`,
  `errors` entries). Their javadoc says they are characterisation tests
  (CLAUDE.md). Then the move. Where a task changes an answer on purpose, the
  characterisation test for that case is updated in a separate commit from
  the move, with the new expectation written first and seen to fail.
- **The JSP pages render identically.** Same model attribute names and
  values, same markup; `Routes` CSS markers untouched. Finish with
  `mvn clean install` green and the browser suite (`mvn verify -Pit`) green at
  `/` and at `-Dit.context.path=pressroom`.
- **Screen independence.** Every new rule class and view model imports no
  `jakarta.servlet.*`, `org.springframework.ui.*`, `org.springframework.web.*`,
  no `ui.restapi.*` type and no JSP form bean (`*Bean` under `ui.controllers`).
  Rules take plain values or records and return results; the JSP controller
  turns a result into `addFieldError`, the API into `FieldErrors` /
  `ApiException`. A source-scan test (Task 7 adds it; each earlier task adds its
  classes to its list as it lands, or Task 7 lists them all) pins this.
- **Result shape.** New rule classes return
  `com.wikantik.pressroom.ui.controllers.RuleViolation(String field, String
  messageKey, List<Object> args, String detail, Kind kind)`, `Kind` being
  `INVALID` (400 over the API) or `CONFLICT` (409). `field` is the API's field
  name; the JSP adapter maps it to its DOM id. `args` are raw values;
  `detail` is the API's English text, which must reproduce today's detail
  byte-for-byte wherever the answer is not changed on purpose. The first task
  to land creates `RuleViolation`; `WeblogSettingsRules.Violation` is left
  alone (see Out of scope).
- **Hazards (CLAUDE.md "Hazards that fail silently") that bite here:**
  - Escaping: `<spring:message arguments>` and `tiles/messages.jsp` do not
    escape. A rule's `args` are raw user input; every JSP adapter escapes them
    exactly as the code it replaces does (`StringEscapeUtils.escapeHtml4`),
    and `MessageArgumentEscapingTest` stays green with no new offender.
  - Velocity leniency: `weblog.vm`'s `showAuthorMenu`/`showAdminMenu` macros
    read `$menuModel.adminMenu.tabs`, `$menu.items`, `.key`, `.action`.
    Velocity resolves getters, **not record accessors**: turning `Menu`,
    `MenuTab` or `MenuTabItem` into records, or renaming a getter, renders the
    reference as literal text with no error (Task 6).
  - Name every `@RequestParam`/`@PathVariable` on any handler signature a task
    touches.
  - A check that could not run is not a check that passed: every lookup in a
    moved rule that can fail makes the rule refuse (as the JSP code it
    replaces does), never pass.
- **API docs move with behaviour.** Every intended API change is listed in
  `docs/api/README.md`'s change notes for 0.16.0 and in `docs/dev/api.md`;
  MCP descriptions are checked (`McpInstructions.TEXT` stays under 3,000
  characters). Pinned surfaces that may need updating: `ApiRouteTableTest`
  (no route is added, so it must not change), `ToolSchemasTest`,
  `McpDocsTest`.
- **Every CLAUDE.md gate applies** (JaCoCo floors including the
  security-boundary package, which covers `v1`; PMD/CPD/SpotBugs zero;
  `-Xlint:all` zero warnings; SLF4J throwable-last; i18n ratchets). A new
  message key goes into every bundle the ratchets require.
- **Concurrency with Tier 2.** The caller-kind work
  (`2026-11-20-caller-kind-and-auth-matrix-design.md`) edits `AdminApi`,
  `TokensApi`, `AdminAccountGuard` and the security chain. Tasks 1 and 2 edit
  `AdminApi`: run them after Tier 2's commits are on master, in the main tree
  (no worktree on an older base). Neither task calls or changes
  `AdminAccountGuard`; `patchConfig` keeps its existing guard call where it
  is. No task depends on how the API authenticates.
- Implementers share `app/target/`: one `mvn -pl app test` at a time per
  tree. Commit on master; never push.

- **Amendment (2026-11-21, controller, after Tier 5 landed).** Every API
  error now carries a stable `code` (`ProblemCode`, text in the bundle under
  `api.problem.*`; see docs/dev/api.md "Problem codes"), and `ApiException`
  has no public free-text factory. So `RuleViolation` carries a `ProblemCode`
  and its arguments in place of an English `detail`; the API adapter raises it
  through the keyed factories (`problem`/`problemOnField`). Where the API
  already answered a case, reuse the code it answers today, so `code` and the
  English `detail` stay byte-identical; a case the API newly refuses gets a
  new code (bundle entry, README code table, `ProblemCodeCatalogTest` pin).
  `ContentProblemSourceScanTest`, `ApiProblemSourceScanTest`,
  `ApiAuthorizationMatrixTest` and `CallerKindConfinementTest` stay green.
  The JSP side keeps its `messageKey`.

## Approaches considered

1. **Rule classes per family, called by both surfaces** (chosen). Extends the
   pattern four classes already follow; each task is one family and can be
   reviewed alone; the API keeps its problem+json shape and the JSP its field
   errors, through thin adapters.
2. **Bean Validation (`@Valid` with constraint annotations on shared
   records).** Uniform, but most of these rules need lookups (duplicate
   names, published pages, admin counts) that become custom validators with
   injected managers, the JSP's message keys and field ids would need a
   mapping layer, and the API's single-400-with-all-errors behaviour
   (`FieldErrors`) would have to be rebuilt on top. Rejected: more machinery
   than the rules it carries.
3. **Route the JSP controllers through the API controllers** (call
   `CategoriesApi.create` from `CategoryEditController`). One copy of
   everything, immediately. Rejected: it couples the JSP to the API's
   exception and authorization model (`ApiException`, 404-never-403,
   token scope), breaks the rule that `ui.controllers` imports nothing from
   `ui.restapi`, and is wasted once React calls the API directly.

## Task 1: Site settings rules (`GlobalConfigController` and `PATCH /admin/config`)

**Rule.** One class, `SiteSettingsRules`, judges a set of incoming runtime
property values against the stored ones and the property definitions, and one
class (or method), `SiteSettingsEffects.afterSave`, does what a save must do
afterwards. Both `GlobalConfigController.save` and `AdminApi.patchConfig` call
both. Moves out of `GlobalConfigController`: the per-type judgement in
`accept`/`checkNumber` (already backed by `PropertyType.check`), string
trimming, the front-door checks in `applyFrontDoorChoice` (the site space as
front-door blog; a changed front-door page must be a published site page, via
`isPublishedSitePage`), `validatePrivacyPage`/`publishedOrFalse`, `changed`,
`RENDERED_PAGE_INPUTS` and `touchEveryWeblog`, and the discovery-refresh
decision. Stays in the controller (form concerns): the minutes field for
`DiscoverySettings.REFRESH_SECONDS` (`acceptRefreshMinutes`), the unticked
checkbox meaning `false`, the `page:<slug>` encoding of the front-door
select, the "missing from the form" error, re-rendering typed values, and
`sayWhatTheNextSweepRemoves`. Stays in `AdminApi`: `requireRuntimeProperty`,
`AdminAccountGuard.requireApiManageable`, the null-value refusal.

**Acceptance criteria.**
1. Characterisation tests (old code) pin, for the JSP: a range error, an
   unparsable integer and float, a draft/unknown front-door page, the site
   space handle as front-door blog, an unchanged stale front-door page left
   alone, an unpublished privacy page, a privacy page that later became
   unpublished not blocking an unrelated save, string trimming, and that a
   change to each `RENDERED_PAGE_INPUTS` property moves every weblog's
   `lastModified` while a change to another property does not. For the API:
   today's answers for the same inputs (most are accepted today).
2. Unit tests on `SiteSettingsRules` cover every rule above with no servlet,
   `Model` or controller in the test, including that a front-door or
   privacy lookup that throws refuses (fail closed) and that only a changed
   reference is checked.
3. After the change the JSP answers are exactly the AC1 ones.
4. Intended API changes, each with a test seen to fail first:
   (a) `site.frontpage.page` changed to a slug that is not a published site
   page: 400, `errors` on `site.frontpage.page`; (b)
   `site.frontpage.weblog.handle` set to the site space's handle (any case):
   400 on that field; (c) a patch leaving both front-door properties
   non-empty: 400 on `site.frontpage.page` (the form can never produce this
   state; the API now cannot store it); (d) `site.privacy.page` changed to a
   reference that does not resolve to a published page: 400 on that field;
   (e) string values are stored trimmed; (f) a patch changing any rendered-page
   input moves every weblog's `lastModified` (so cached pages expire), and the
   discovery refresh still happens exactly when a `site.discovery.*` value
   changes. Several bad values in one patch are one 400 naming each
   (`FieldErrors`), and nothing is stored.
5. `GlobalConfigController` no longer contains `isPublishedSitePage`,
   `validatePrivacyPage`, `publishedOrFalse`, `touchEveryWeblog` or
   `RENDERED_PAGE_INPUTS`; `AdminApi` no longer decides discovery refresh
   itself.
6. `GlobalConfigMatrixIT` green at both context paths.

## Task 2: Account and membership rules

**Rule (accounts).** `AccountRules` (in `ui.controllers`, replacing the
package-private `admin.UserNames`) holds: the username rule (present, inside
`username.allowedChars` or `UIUtils.DEFAULT_ALLOWED_CHARS`, column length,
not taken in any case and any enabled state; a taken-check that cannot run
refuses), the email rule (commons `EmailValidator`, trimmed, column length),
the screen-name rule (present after each surface's defaulting, column
length), and the password-length rule (delegating to `PasswordRules`).
Callers: `UserEditController.validateNew`/`validateEdit`, `AgentController`'s
create path (today via `UserNames.problem`), `AdminApi.createUser`,
`patchUser` and its `requireValidUserName`. `AdminApi.EMAIL_PATTERN` is
deleted. The API's defaulting of an absent `screenName` to the username
stays in the API. The JSP's full-name requirement stays in the JSP (the API
has no full-name field).

**Rule (memberships).** `Memberships` gains the decisions both membership
screens make: `levelProblem(value)` (one of `LEVELS`, or the Members page's
removal value where the caller allows it), `mayHold(User account)` (a reader
account cannot be a member), `groupBloggingProblem()` (the
`groupblogging.enabled` check both screens make), and `leavesNoAdmin(current
memberships, proposed changes)`, which answers for both shapes: the Members
page's whole-table submission (`MembersController.refused` counts admins in
the posted rows) and the person page's single change
(`UserWeblogsController.isLastAdmin`). `MembersController`'s "no changes to
your own membership" rule moves too (`selfChangeProblem`).

**Acceptance criteria.**
1. Characterisation tests (old code) pin each refusal of
   `UserEditController` (new person, edit person, SSO-bound password),
   `AgentController` create, `MembersController` (bad level, self change, no
   admin left, reader invite, unknown user, group blogging off) and
   `UserWeblogsController` (bad level, reader account, not a member, last
   admin on change and on removal, group blogging off), and the API's
   username and email answers on `POST /admin/users` and `PATCH
   /admin/users/{userName}`.
2. Unit tests on `AccountRules` and the new `Memberships` methods cover every
   rule with no controller in the test; `leavesNoAdmin` has a test per shape,
   including a store failure while counting refusing the change.
3. After the change every JSP answer is exactly the AC1 one;
   `UserNames` is gone; `MembersController` and `UserWeblogsController` hold
   no admin-counting code.
4. Intended API change, test first: `emailAddress` is judged by
   `EmailValidator` on create and patch. Pin at least one address the regex
   accepted and the validator refuses (for example `a@b.c`, whose
   one-letter TLD the validator rejects), answered 400 on `emailAddress` with
   today's detail text. The username details are unchanged.
5. `UserAdminIT`, `AgentAccountsIT` and the members browser ITs green at both
   context paths.

## Task 3: Category and media-folder rules

**Rule.** `CategoryRules`: `nameProblem(Weblog, String name, String
excludingId, WeblogEntryManager)` (trimmed; non-blank; at most
`ColumnLimits.CATEGORY_NAME`; unchanged by HTML escaping; unique in the
weblog, comparing by the manager's by-name lookup and excluding the category
being renamed; a lookup that throws refuses), `descriptionProblem`, and
`removalProblem(Weblog, WeblogCategory, String moveToId)` (last category;
`moveTo` naming itself; in use with no `moveTo`). `MediaRules`:
`directoryNameProblem(Weblog, String)` (trim, strip one leading `/`, then
non-blank, not `default`, no `/`, at most `ColumnLimits.DIRECTORY_NAME`, not
taken) returning the normalised name with any violation, and
`fileNameProblem(MediaFileDirectory, String name, String excludingId)`
(non-blank, at most `ColumnLimits.MEDIA_NAME`, no other file of that name in
the directory). Callers: `CategoryEditController.myValidate`,
`CategoryRemoveController.remove`, `CategoriesApi.create`/`update`/`delete`;
`MediaFileViewController.createNewDirectory`, `MediaFileEditController.save`,
`MediaApi.createDirectory`, `MediaDtos.applyPatch` (or `MediaApi.update`,
since the duplicate check needs a lookup the pure mapper does not make).
`ColumnLimits` is in `ui.restapi`; the length limits the rules need move to,
or are re-exported from, a class in `ui.controllers` so the import rule
holds (builder's call; `ColumnLimits` may delegate).

**Acceptance criteria.**
1. Characterisation tests (old code) pin both surfaces for: blank, too long,
   HTML characters, duplicate on add, duplicate on rename, rename to its own
   name, last-category removal, in-use removal without `moveTo`, `moveTo`
   naming itself; folder names empty, `/x`, `a/b`, `default`, duplicate,
   too long; file rename to a taken name, blank, too long.
2. Unit tests on `CategoryRules` and `MediaRules` cover every rule, including
   fail-closed lookups.
3. Intended changes, each test first:
   (a) API: a category name that HTML escaping would change is 400 on `name`
   on create, and on rename when the name changes; a PATCH that resends an
   existing category's current name (even one created over the API with an
   `&`) is not refused for it.
   (b) JSP: a too-long category name is a field error on the name field
   (today it reaches the database).
   (c) Both: a folder name with a leading `/` is accepted as the name without
   it (the JSP refused it); `default` and a name with an inner `/` are field
   refusals (the API answered the inner `/` through the manager); the JSP
   gains the length refusal.
   (d) API: renaming a media file to a name another file in its directory
   holds is 409 on `name`, nothing changed. JSP: a blank or too-long file name
   is a field error.
4. Every other answer is exactly its AC1 answer, API details included.
5. `CategoryIT` and the media browser ITs green at both context paths.

## Task 4: Page, strand and pin rules

**Rule.** `PageRules`: `slugProblem(Weblog, String slug, String
excludingId, WeblogPageManager)` (reserved, a locale segment, contains `/`,
at most `ColumnLimits.PAGE_SLUG`, held by a live page, held by a trashed
page; a lookup that throws refuses), `defaultSlug(String title)` for a new
page with none (today `WeblogPage.slugFromTitle` at two call sites), and
`strandProblem(Weblog, WeblogPage stored, Boolean wanted, WeblogEntryManager)`
(a site page cannot be a strand; a strand with parts cannot be unset; a count
that cannot be read refuses). `StrandChoice.resolve(Weblog, WeblogEntry,
String strandId, ...)` (absent or already-joined is "no change"; otherwise
one of this blog's strand pages through `WeblogOwnership.page`, else a
violation on `strand`). `EntryPins.roomProblem(WeblogEntryManager, Weblog,
boolean wasPinned, boolean wanted)` wraps the count and
`EntryPins.wouldExceed` (a count that cannot be read refuses). Callers:
`PageEditController.checkSlugNotInTrash`, `checkUntick`, `saved`;
`PageDtos.requireUsableSlug`, `PagesApi.requireSlugAvailable`,
`checkStrand`; `EntryEditController.checkedStrand`, `checkPinLimit`;
`EntriesWriteApi.strandFor`, `requirePinRoom`. Each surface keeps its own
blank handling before calling (the editor's blank strand means "leave it",
the API's means "not a strand page"; the API leaves a strand with `clear`).
The page editor's `checkedUntick` "every member was on screen" allowance is a
form concern and stays in the controller.

**Acceptance criteria.**
1. Characterisation tests (old code) pin both surfaces for: reserved slug,
   locale slug, slug with `/`, too-long slug, slug held by a trashed page,
   slug held by a live page, a new page with no slug, site page made a strand,
   unsetting a strand with parts, a part count that throws; an entry joining
   another blog's page, a plain page, an unknown id, its current strand; a
   pin over the limit, re-saving a pinned entry, a pin count that throws.
2. Unit tests on `PageRules`, `StrandChoice` and the new `EntryPins` method
   cover every case, with no controller in the test.
3. `PageEditController.saved` no longer inspects `WebloggerException`
   message text for `"reserved"`/`"slug"`: the slug is judged by `PageRules`
   before the save. (`SiteSlugClashException` handling stays: it is the
   manager's typed refusal.)
4. Intended change, test first: in the page editor, a slug a live page of the
   same weblog holds is a field error on the slug (new key
   `pageEdit.error.slugTaken`, arity 0) instead of whatever AC1 found (expected:
   `generic.error.check.logs` from the unique constraint). The API's answers
   (409 with today's two details) are unchanged.
5. Every other answer is exactly its AC1 answer.
6. Strand and page browser ITs green at both context paths.

## Task 5: The entry list as a view model

**Rule.** `EntryListQuery` (in `ui.controllers`) is the one place that turns
list filters (weblog, status, category name, tags, text, locale, start/end
dates, sort, offset, limit) into `WeblogEntrySearchCriteria`, applies the
trash rule (trashed entries only when `TRASHED` is asked for by name), and
fetches one extra row to answer "more?" without a count query. It returns
`EntryListPage(List<WeblogEntry> items, int offset, int limit, boolean
hasMore)`. Both `EntriesController.execute` and `EntriesApi.list` call it;
each keeps its own parameter parsing (the API's `Paging.requireRange`,
`MAX_LIMIT` cap and strict status 400; the JSP's page number times `COUNT`).
`EntryListView` (new package `com.wikantik.pressroom.ui.view`, built by
`EntryListView.of(...)` from the page, the weblog, the analytics data the
views column needs, the locale and the filter values) carries what
`Entries.jsp` shows: rows (entry, plain title, views cell), first/last entry,
page number and `hasMore`, whether a filter is active, the views-column flag,
category and sort and status options, and the selected filters. It holds no
URLs: `EntriesController` builds `EntriesPager` and `statusChipUrls` from it,
since links are the JSP's business. The JSP keeps reading the same model
attribute names (`pager`, `entryTitles`, `entryViews`, `viewsColumn`,
`firstEntry`, `lastEntry`, `filtered`, `categories`, `sortByOptions`,
`statusOptions`, `statusChipUrls`); `EntriesController` sets them from the
view.

**Acceptance criteria.**
1. Characterisation tests (old code) pin, for a fixed fixture, every model
   attribute `entries.rol` sets (names and values) for: no filter, each
   status, a category, a text filter, a page past the first, exactly `COUNT`
   and `COUNT + 1` entries, analytics unconfigured / configured with no
   snapshot / with a complete snapshot / with capped paths; and the API's
   `GET .../entries` answers for status, `TRASHED`, offset/limit and `hasMore`.
2. `EntryListQuery` and `EntryListView` have unit tests with no servlet,
   `Model` or controller.
3. Every AC1 answer is unchanged after the move.
4. Intended change, test first: `entries.rol?bean.status=NOPE` renders the
   unfiltered list (the treatment the controller's own comment intends for a
   blank status) instead of a 500. The API's 400 for an unknown status is
   unchanged.
5. `EntriesController` contains no `WeblogEntrySearchCriteria` and
   `EntriesApi.list` builds none itself.
6. Entries browser ITs (list, filters, paging, bulk actions) green at both
   context paths.

## Task 6: Admin chrome as view models: menu, main menu, account list

**Rule (menu).** The admin rail stays computed by `MenuHelper.buildMenu`
(already pure, already tested by `MenuHelperTest`), with three changes:
`Menu`, `MenuTab` and `MenuTabItem` lose their public setters (built only by
`MenuHelper`; still JavaBeans with the same getter names, because
`weblog.vm`'s menu macros and the JSP's EL read them); `Menu` carries which
surface its actions belong to (`AUTHORING` for the `editor` menu, `ADMIN` for
`admin`), so a client can build a link without the JSP's `actionWeblog`
`c:choose`; and `MenuHelper.getMenu`'s parsed-menu map is read-only after
class initialisation (it is a mutable static `HashMap` today). The rendered
rail is unchanged.

**Rule (main menu).** `MainMenuView.of(User, Weblogger)` in `ui.view`
returns `(List<WeblogPermissionView> weblogs, boolean siteAdmin, Weblog
siteSpace)` with today's display-path behaviour (a failed lookup shows less
and is logged; the site space is fetched, and created on a site admin's
first visit, exactly as `MainMenuController.siteSpace` does now).
`MainMenuController` sets `existingPermissions`, `userIsAdmin` and
`siteSpace` from it.

**Rule (accounts list).** `AccountRows.showList` stops writing to the
`Model`: `AccountList.of(Weblogger, String kind, String text)` in `ui.view`
returns `(String kindFilter, String textFilter, List<AccountRow> accounts)`
with today's normalisation (unknown kind is "all", text trimmed, a store
failure is an empty list, logged). The three controllers that land on
`.UserAdmin` (`UserAdminController`, `UserEditController`,
`AgentController`) put its three values on the model under today's names.
`AccountRow` moves to `ui.view` if it touches no excluded type.

**Acceptance criteria.**
1. Characterisation tests (old code): the rendered output of the
   `showAuthorMenu` and `showAdminMenu` macros for an editor and an admin
   fixture (through the real Velocity engine, as the rendering tests do), the
   `menu`/`navMenu` structure for an author, an `EDIT_DRAFT` member, an admin,
   the site space and a custom-theme weblog, the main menu's three attributes
   for an admin and a non-admin including each failure path, and
   `.UserAdmin`'s three attributes for each tab, a filter and a store failure.
2. After the change all of AC1 is unchanged; the Velocity macro output is
   compared byte-for-byte (the guard against a getter rename printing as
   literal text).
3. `Menu.getSurface()` (or the builder's chosen name) is `AUTHORING` for the
   editor menu and `ADMIN` for the admin menu; `MenuTab`/`MenuTabItem`/`Menu`
   have no public setter (a reflection test).
4. `MainMenuView` and `AccountList` are unit-tested without a servlet or
   `Model`; `AccountRows.showList` is gone.
5. `RouteSweepIT` (including the admin-rail check), `UserAdminIT` and the
   main-menu ITs green at both context paths.

## Task 7: `FormBodyParityTest` survives the forms; screen independence pinned

**Rule (parity).** Today the gate's left side is the JSP form beans
(`EntryBean`, `WeblogConfigBean`, `PageBean`): delete a bean and its row must
be deleted to compile, and the guarantee goes with it. Re-anchor it on a
checked-in manifest of what each editing screen offers, in API vocabulary,
that names no bean: `EditableFieldManifest` (test code, `ui.restapi`), one
entry per resource (`entry`, `weblogSettings`, `page`), each a list of field
names plus, per resource, the API body fields no editing screen offers, each
with a reason (`edits`, `expectedContentHash`, `revisionNote`, `clear`, and
whatever else the body carries today). Three checks:

- (A, outlives the JSP) every manifest field is a component of the API body
  record (`EntryDtos.EntryWrite`, `AdminDtos.WeblogPatch`,
  `PageDtos.PageWrite`): an API field cannot be dropped silently;
- (B, outlives the JSP) every body component is a manifest field or an
  API-only field with a reason: an API field cannot be added without
  deciding whether the screen offers it (the reverse direction, unchecked
  today);
- (C, the JSP half, deleted bean by bean with the JSP) each form bean's
  properties map onto the manifest through today's renames and exclusions,
  and every manifest field is reached by some bean property (a stale
  manifest fails while the bean exists).

The existing renames and exclusions move into C unchanged. The self-tests
of the gate logic stay.

**Rule (independence).** `ScreenIndependenceTest` scans the sources of every
class in `ui.view` and of each rule class this spec creates or names
(`RuleViolation`, `SiteSettingsRules`, `SiteSettingsEffects`, `AccountRules`,
`Memberships`, `CategoryRules`, `MediaRules`, `PageRules`, `StrandChoice`,
`EntryListQuery`, plus the already-pure `TrafficView`, `SiteTrafficView`,
`AccountRow`) and fails on an import of `jakarta.servlet`,
`org.springframework.ui`, `org.springframework.web`, `ui.restapi`, or a class
whose simple name ends in `Bean` under `ui.controllers`. The list is
explicit, and a named class that no longer exists fails it.

**Acceptance criteria.**
1. With the manifest in place and before the old test is removed, A, B and C
   all pass against today's code, and C reports exactly what the old test
   reports for each self-test fixture (a characterisation of the gate).
2. A self-test per check: a manifest field missing from a body fails A; an
   unexplained body field fails B; a bean property outside the manifest, and
   a manifest field no bean property reaches, each fail C.
3. Deleting C's row for one resource (simulated in a self-test with a fake
   bean and body) leaves A and B for that resource running and passing.
4. `docs/dev/api.md` ("Adding a field to an admin form bean...") describes
   the manifest as the anchor: a new editing field goes into the manifest and
   the body; removing a JSP removes only its C row.
5. `ScreenIndependenceTest` passes, and fails on a planted fixture that
   imports `jakarta.servlet.http.HttpServletRequest` (self-test).
6. `docs/dev/api.md` says, in one line, that the manifest is what a future
   React form is held to (no React work here).

## Decisions made while drafting

- **Where surfaces disagree, the stricter answer wins, value by value**, with
  the exceptions named in the tasks (a leading `/` on a folder name is
  normalised on both, loosening the JSP; each surface keeps its own blank
  handling for strands and image ids). Fail-closed is the house rule, and the
  API is labelled unstable in 0.x. *Cost if wrong:* an agent that relied on
  a lenient API answer gets a 400 (category names with `&`, front-door values,
  emails the regex accepted); each is listed in the README change notes, so
  it is visible, and loosening a shared rule later is a one-line change with
  both surfaces following.
- **Category names: HTML characters refused over the API too** (Task 3).
  The least certain ruling. Themes escape category names, so this is not an
  XSS fix; it is parity with a JSP rule that predates the escaping. The
  alternative was loosening the JSP to the API's rule, which would let
  `Food & Drink` in everywhere and needs an audit of every place that emits a
  category name (URLs, feeds, JSPs) first. *Cost if wrong:* agents cannot
  create `Food & Drink`; reversing means deleting one check after that audit.
  Existing names are never refused on a PATCH that resends them.
- **Site settings over the API gain the form's checks and its cache touch**
  (Task 1), including a new "both front-door properties set" refusal the form
  cannot reach. *Cost if wrong:* an agent setting the front door must send
  `site.frontpage.weblog.handle: ""` alongside `site.frontpage.page`; the
  alternative (silently clearing the other) hides a write.
- **One email validator, the JSP's `EmailValidator`** (Task 2). It checks
  the TLD against a list, so an address on a brand-new TLD could be refused
  until commons-validator is bumped. *Cost if wrong:* an occasional false
  refusal on both surfaces, not just one.
- **No status counts in the entry list view** (Task 5). The tier list
  mentions "status counts", but no screen shows counts today; adding them is
  a new feature with a new query per page view. The view carries what the
  page shows. *Cost if wrong:* a React screen that wants counts adds one
  field and one query later.
- **View models in a new package `ui.view`; rules stay in `ui.controllers`**
  beside their siblings; existing `TrafficView`/`SiteTrafficView` are not
  moved (they are already pure; moving them churns `TrafficDtos` for no
  gain). *Cost if wrong:* a later package move, mechanical.
- **Menu classes stay JavaBeans, not records** (Task 6), because Velocity
  resolves getters and not record accessors, and the failure is silent.
- **A new `RuleViolation` type rather than reusing
  `WeblogSettingsRules.Violation`** (whose `labelKey`/`max` fields are
  specific to its form), and the old one is not migrated here. *Cost if
  wrong:* two violation shapes until the follow-up below.
- **`FormBodyParityTest`'s manifest lives in test code**, not in main code or
  a resource file: it is compile-checked and needs no runtime reader.
  Check B (body fields must be decided) is new strictness. *Cost if wrong:*
  each new API body field needs one manifest line with a reason; dropping B
  later is deleting one test.
- **The newly found JSP defects are fixed in passing only where they sit
  inside a moved rule** (the `entries.rol` 500 on a bad status; the editor's
  generic error on a live duplicate slug). Anything else the characterisation
  tests turn up is reported, not fixed, unless the fix is the shared rule's
  answer.

## Out of scope

- Any new API resource or route (Tier 4); session login on the API (Tier 2
  spec); error codes and localisation of API details (Tier 5).
- Rules with no API twin today and no Tier 4 consumer that needs them first:
  templates and stylesheets (`TemplatesController.myValidate`,
  `TemplateEditController.myValidate`), agent management, the newsletter-send
  preconditions in `EntryEditController`, the profile editor
  (`ProfileController.myValidate`). **`BusinessesController.validate`** is the
  first step of Tier 4's business-profile API task (compose `BusinessRules`'
  existing predicates into `BusinessRules.check`), not this tier: there is no
  second copy yet to drift from.
- `WeblogSettingsRules.check(WeblogConfigBean, ...)` and
  `WeblogCreationRules.check(CreateWeblogBean, ...)` take JSP form beans, and
  the settings PATCH lays the request over `WeblogConfigBean`. They are
  shared already, so there is no drift, but they will need a plain input
  type before the beans can be deleted. Follow-up: give both a record input
  and fold `WeblogSettingsRules.Violation` into `RuleViolation`.
- The picked-image check: already one call (`WeblogOwnership.publicImage`)
  behind `BaseController.checkPickedImage` and `BaseApiController.checkImageId`;
  the blank-versus-null difference follows each surface's clear semantics.
- Restyling or restructuring any JSP, its CSS or its inline script; new
  parity pairs in `FormBodyParityTest` beyond the three it has (category,
  media and user forms are candidates once Tier 4 settles their bodies).
