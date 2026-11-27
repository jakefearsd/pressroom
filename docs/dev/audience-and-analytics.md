# Audience and analytics

Developer notes moved out of `CLAUDE.md` (which is sent with every
request) so they are read only when this area is being changed. Section
names are unchanged, so an older "see CLAUDE.md, <section>" pointer
resolves through the index at the end of `CLAUDE.md`.

## Audience
Contact forms, newsletter subscribe, and account tokens. No CAPTCHA anywhere;
no CSP change anywhere — every endpoint is same-origin and `connect-src 'self'`
already allows the fetch.

- **Placeholder-div + `#showAudienceAssets` injection.**
  `[contact]`/`[subscribe]` emit an inert
  `<div class="...-slot" data-*="...">`, never a `<form>`: `HTMLSanitizer`
  strips `<form>` from authored content on purpose (phishing), and
  `#showAudienceAssets` (`weblog.vm`) builds the real forms client-side.
  Endpoints are **server-built** into `data-endpoint`:
  `ContactShortcode.render()` emits
  `WebloggerRuntimeConfig.getRelativeContextURL() + "/pressroom-ui/rendering/contact.rol"`;
  `SubscribeShortcode.render()` and `#showSubscribeForm` emit
  `WebloggerRuntimeConfig.getRelativeContextURL()` / `$url.site`, not a
  client-guessed `/newsletter/subscribe` — a client heuristic (a `<link>`
  containing `/pressroom-ui/`) posted to the site root under a context path.
  The script builds a form only around an endpoint that ends in its exact
  suffix AND resolves to the page's own origin (`endpointOk`): an author may
  write `data-endpoint` on a div, and a suffix check alone passed
  `https://evil.example/pressroom-ui/rendering/contact.rol`, held back only
  by a theme's `connect-src 'self'` (`AudienceEndpointScriptSourceTest`).
- **Persist-first, then notify.** `ContactController` writes the
  `pressroom_form_submission` row before any notification email, so the lead
  survives an SMTP outage. Defences in order: per-IP throttle (429); unknown
  weblog handle 404s; **a filled honeypot or a too-fast submit answers 204,
  identically to success, and stores nothing**, so automation learns nothing.
  Subscribe mirrors both.
- **Blog settings says whether the subscribe form is on** (0.9.1), beside the
  list field: `SubscribeFormStatus` is `Weblog.isSubscribeFormShown()` (the
  predicate the showSubscribeForm macro renders on) plus
  `ListmonkClient.isSiteConfigured()` (the base URL `NewsletterController`
  answers 503 without). Never restate either rule. With a list but no site
  service the placeholder still renders and sign-ups fail, which is why the
  status reads "off".
- **`/newsletter/subscribe` is served by the app, not Caddy.** Throttle and
  `pressroom_event` recording live in `NewsletterController`; the old Caddy
  `handle /newsletter/subscribe { rewrite ... reverse_proxy listmonk }` block
  bypassed both and **must never come back**. **Pressroom stores no subscriber
  data** — Listmonk owns the list, opt-in, sending and unsubscribe; Pressroom
  holds only `weblog.newsletter_list_uuid` and
  `weblogentry.newsletter_sent_at`. List uuids are **not** unique across
  weblogs: `getWeblogByNewsletterListUuid` orders by handle, so a shared uuid
  credits one weblog rather than throwing `NonUniqueResultException`.
- **`pressroom_event`** (V015) — `FORM_SUBMITTED` (`ContactController`),
  `NEWSLETTER_SUBSCRIBED` (`NewsletterController`, not on an already-subscribed
  409), `ENTRY_PUBLISHED` (`JPAWeblogEntryManagerImpl.saveWeblogEntry`, gated
  on `entry.getLoadedStatus() != PubStatus.PUBLISHED`, the revisions' post-load
  snapshot, see Entry editing; unpublish then republish records a **second**
  `ENTRY_PUBLISHED` because the reload resets `loadedStatus` away from
  `PUBLISHED`). Writes are best-effort and never fail the request. `metadata`
  (jsonb) stays unmapped in JPA until something writes it.
- **`pressroom_user_token`** (V015) stores a SHA-256 digest only, never the raw
  token — a database read must not yield working reset links. `consume` is an
  atomic rows-affected `UPDATE ... WHERE used_at IS NULL AND ...`, not
  validate-then-mark, closing the double-redemption race; tokens expire after
  `UserTokenManager.TOKEN_TTL_MS` (1 hour). Forgot-password and the admin
  set-password link share `PasswordLinkMailer.sendLink`, so the emailed URL
  shape cannot drift.
- **Forgot-password is enumeration-proof by construction.**
  `PasswordLinkMailer.isReady()` requires BOTH a mail transport
  (`MailUtil.isMailConfigured()`) AND a non-blank `site.adminemail` — transport
  alone would send nowhere. Token issuance + email run off-thread via
  `ThreadManager.executeInBackground`, with `weblogger.release()` in a
  `finally` on the worker (the `AddEntryOperation` convention; an unreleased
  `EntityManager` leaks a connection). Found and not-found paths share one
  timing shape and message.
- **"Send as newsletter" is manual, synchronous, and stamped-on-success** — no
  queue; the human who clicked IS the retry. `EntryEditController` calls
  `ListmonkClient.sendCampaign` in-request and stamps
  `weblogentry.newsletter_sent_at` only after it returns. A send whose
  stamp-save fails shows `newsletter.sentButNotRecorded`, so the editor is not
  invited to double-mail.

## Analytics
Automatic per-weblog Umami tracking, a local cache of its numbers, and a
read-only Grafana contract over two databases. Spec:
`docs/superpowers/specs/2026-11-01-analytics-without-admin-steps-design.md`.

- **The seam is `AnalyticsService`** (`business/analytics`): `isConfigured`,
  `ensureSite`, `read` and `ping`. `UmamiAnalyticsService` (over `UmamiClient`)
  is the only implementation, so Umami's location is configuration only
  (`analytics.umami.apiUrl`, `.username`, `.password`, `.dashboardUrl`; env
  `PRESSROOM_ANALYTICS_UMAMI_*`). A blank `apiUrl` makes `isConfigured` false
  and everything else inert: no tracker, no calls, "not configured" pages.
  Nothing outside the manager and the sync task depends on Umami's wire format.
- **Three channels, kept apart.** (1) *Reconcile*: `AnalyticsManager` registers
  every weblog (inactive and the site space too) through `ensureSite`, website
  ID = weblog ID. It runs at startup (backfill) and after a blog save only when
  the blog is unregistered or its name or domain changed; deleting a blog never
  touches Umami. (2) *Browser collection*: the tracker tag
  (`#showAnalyticsTrackingCode`) posts to Umami through Caddy's two allowed
  `/analytics/` paths; Pressroom is not in that path. (3) *The batch read*:
  `AnalyticsSyncTask` (`tasks.AnalyticsSyncTask.interval`, 15 min; also
  Maintenance "Sync now") calls `read` per site and period and replaces the
  local tables `analytics_site`, `analytics_summary`, `analytics_path_views`
  and `analytics_breakdown` (V043). No request path calls Umami; admin pages,
  the entries-list Views (30d) column, discovery and the API's
  `GET /v1/weblogs/{handle}/traffic` (`TrafficApi`, MCP `traffic get`) read
  the cache only.
- **Snapshots are selected, never summed.** Each period (24h, 7d, 30d, 90d,
  plus the discovery window) is its own snapshot, replaced whole. Visitors
  are unique counts per period, so adding two periods or two days double
  counts them; a view that needs another range reads that period's snapshot.
  Day boundaries follow the site space's time zone. A failed read keeps the old
  snapshot and marks it stale (shown, not hidden).
- **What the pages say when there is no number.** Global › Traffic lists
  every weblog, the site space included: one with no snapshot says "No
  numbers yet", or "Not registered with Umami yet" when its registration
  failed, and counts toward no total. A blog's Traffic page says "Not
  registered with Umami yet: <reason>" in that case. Paths are read 1000 per
  (blog, period); a read that fills the page is marked `paths_truncated`, and
  then the entries list's Views (30d) shows an em dash, not 0, for an entry
  outside it (ruling R23; `umami-api.md`). The site pages' row opens the
  Traffic page on the site space, for a global admin only (R25).
- **A website Umami lost is registered again.** A read answered 401 after a
  fresh login means Umami has no such website (`WebsiteMissingException`):
  the blog's registration is cleared and the next sync re-creates it under
  the same ID (R24; operator view in `docker_deployment.md`, "If Umami loses
  its websites").
- **The scheduled sync skips a tick while a sync runs** (`trySync`, a
  `ReentrantLock.tryLock`), so a slow Umami cannot pile pool threads up on
  the lock; requested syncs ("Sync now", a blog save) still queue behind the
  running one. AC 8 is proven with the real manager on a hanging service for
  page renders (`RenderingNeverCallsAnalyticsServiceTest`) and for the admin
  pages, the API's traffic route, Sync now and a blog save
  (`AdminPagesNeverCallAnalyticsServiceTest`).
- **The API's traffic is the Traffic page's, minus three things**
  (`TrafficDtos`): no `lastError` (internal text; `STALE` says enough), a NaN
  trend is null (JSON has no NaN), and an unknown `period` is a 400 rather
  than the page's fall-back to 30d. Same permission (`WeblogPermission.ADMIN`),
  but never the site space: the interceptor withholds it from every API
  handler (`TrafficApiSecurityTest`).
- **Cache expiry rides `weblog.lastModified`.** Whether a blog carries the
  tracker tag is part of its rendered pages, so each registration-state change
  advances `lastModified` (`JPAAnalyticsManagerImpl`; the new answer is
  published to memory first, then `lastModified` is bumped), the one hook that
  expires `WeblogPageCache`/`WeblogFeedCache`.

- **Umami's HTTP API, as the pinned 3.3.0 image really behaves, is in
  [`umami-api.md`](umami-api.md)**: the dedicated `pressroom` account
  (provisioned by the suite: `sql/umami-pressroom-account.sql` in the `backoffice` repo), registration with a
  supplied website ID, the read endpoints with sample bodies, and what a visit
  stores. `UmamiApiContractTest` pins part of it against the real image
  (`UmamiContainer`), and the rest was observed by hand: the opening paragraph
  of `umami-api.md` lists what is pinned and what is only observed. An image
  bump that breaks the contract test means re-checking that whole doc.

- **`analytics_traffic` is for Grafana only now.** The front page no longer
  reads it: `CachedTrafficSource` (see `discovery.md`) scores posts from the
  local cache (`UmamiTrafficSource` and `analytics.jdbc.*` are gone), so the
  view can change without touching `/`.

- **Structured injection is the only analytics path; no free-text fallback
  (W2).** The per-blog `analyticsSiteId` and `analyticsShareUrl` settings are
  gone (column drop `V044__drop_weblog_analytics_fields.sql`, form, API and
  messages): blogs are registered with Umami automatically and the website ID
  comes from `AnalyticsManager.websiteIdFor`. `#showAnalyticsTrackingCode`
  (`weblog.vm`) builds the
  `<script defer src="…" data-website-id="…" data-host-url="…">` tag from that
  ID plus `ConfigModel.getAnalyticsBasePath()`/`getAnalyticsScriptName()`
  (`analytics.umami.basePath`/`analytics.umami.scriptName` in
  `pressroom.properties`). The legacy free-text `analyticsCode` textarea (gated on
  *Allow analytics code override* **and** `weblogAdminsUntrusted` off, so never
  reachable here) is gone end to end — `Weblog`/`WeblogConfigBean` plumbing,
  JSP, `ConfigModel`/macro branches, and the `weblog.analyticscode` column
  (`V023__drop_w2_fossils.sql`).
- **Click attribution is two Umami custom events, no server code.**
  `[cta]` anchors carry `data-umami-event="cta-click"` and the footer card's
  booking link `business-card-click`; both add `data-umami-event-dest` (the
  destination host) and, when an entry is in context,
  `data-umami-event-entry` (its anchor). The tracker reads those attributes
  itself. `HTMLSanitizer` allowlists exactly those three names on `<a>`
  (not a `data-umami-*` wildcard, which would let authors invent properties).
  Read them in Umami under the site's **Events** tab, filtered by event name,
  with `dest` and `entry` as properties.
- **Reader sign-in clicks are the `sign-in` event** (0.18.0): every
  provider button carries `data-umami-event="sign-in"` and
  `data-umami-event-via` (`login-page`, `header`, `comments`, `places`; Part E
  adds `save`, `follow` (the masthead's Follow) and `article-end`). Template
  and script markup, not content, so the sanitizer allowlist is not involved.
  Read `via` as a property of the `sign-in` event.
- **Save and Follow clicks** (0.18.0, Part E) are the `save` event and the
  `follow` event with `via` `masthead` or `article-end`; "Also email me new
  posts" is the `newsletter` event with `via` `follow`. They count clicks by
  anyone, signed in or not (a signed-out click turns into the sign-in).
- **Same-origin, so the pinned CSPs never moved.** Served via Caddy's
  `/analytics/*` handle, the tracker runs under every theme's
  `script-src 'self'` / `connect-src 'self'`;
  `ThemeCspCoverageTest.everyPolicyStillAllowsSameOriginScriptsAndBeacons`
  fails otherwise.
- **The Grafana contract splits across two databases — Postgres cannot query
  across them.** `analytics_events` (outcomes from `pressroom_event`) and
  `analytics_weblog_sites` (handle ↔ Umami website-id) live in `pressroom`, via
  `bin/db/migrations/V017__analytics_contract.sql`. `analytics_traffic` (over
  Umami's `website_event`) lives in Umami's database, which belongs to the
  back-office suite: the suite's `sql/umami-views.sql`, applied by its own
  provisioning. Pressroom ships and applies only the `pressroom` half.
  Grafana joins the halves on `website_id`; no server query spans both.
  `page_slug`/`entry_anchor` on `FORM_SUBMITTED` rows are the contact form's
  reader-controlled `source` (untrusted), and `ENTRY_PUBLISHED` double-counts
  an unpublish/republish (see Audience).
- **`SQLScriptRunner` is dollar-quote-aware.** `V017`'s cluster-global
  `CREATE ROLE grafana_ro` needs a
  `DO $$ … EXCEPTION WHEN duplicate_object … END $$;` guard to survive
  re-application, which the install wizard's `SQLScriptRunner` (beside
  `migrate.sh` and the test harness) used to split on semicolons. It now tracks
  dollar-quote state (`\$[A-Za-z0-9_]*\$` delimiters, any tag including the
  empty `$$`) and suspends semicolon-splitting and `--`-comment-stripping
  inside one; `SqlScriptRunnerMigrationTest` runs the *actual* migration chain
  through it (`DatabaseInstaller`'s applier). One hazard stays: a trailing `--`
  comment after a closing delimiter on the **same line** (`END $$; -- done`) is
  not stripped (the stripper only sees the state *incoming* to the line) and
  silently swallows the next statement — keep them off the line of a
  terminating `;`.
- **The hitcount subsystem is gone; Umami replaced it.** Deleted:
  `HitCountQueue`, `HitCountProcessingJob`, `ResetHitCountsTask`,
  `ContinuousWorkerThread`/`WorkerThread`, `WeblogHitCount` (pojo +
  `.orm.xml`), the `roller_hitcounts` table (`V017`), `WeblogEntryManager`'s <!-- rebrand:keep -->
  `getHitCount`, `getHitCountByWeblog`, `getHotWeblogs`, `saveHitCount`,
  `removeHitCount`, `incrementHitCount`, `resetAllHitCounts`, `resetHitCount`,
  `Weblog.getTodaysHits()`/`WeblogWrapper`'s delegate, the Maintenance reset
  button, and the frontpage "Hot blogs" sidebar.
  `WeblogPageRequest.isWebsitePageHit()`/`isOtherPageHit()` **survive**
  (`PageServlet` still classifies request URLs with them); only the counting is
  gone.
- **`grafana_ro` ships `NOLOGIN`.** `V017` creates it with no password (a
  migration cannot carry a secret) and grants `SELECT` on exactly the contract
  views, never the tables. Enable with
  `ALTER ROLE grafana_ro LOGIN PASSWORD '...'` over
  `docker compose exec postgres psql` (`docker_deployment.md`); `provision.sh`
  grants it `CONNECT` on `pressroom`. Umami's database is on the suite's own
  server, where the suite creates its `grafana_ro`; that password is set there
  separately. No
  compose file publishes a Postgres host port — tunnel-only.

## Reader state

0.18.0, spec `docs/superpowers/specs/2026-11-26-reader-and-admin-experience-design.md`
Part E. What a signed-in reader keeps, and how the pages show it without
making anonymous pages per-visitor.

- **Tables** (`V056__reader_state.sql`): `pressroom_reader_save`,
  `pressroom_reader_follow`, `pressroom_reader_read`, each keyed by
  `(user_id, entry_id|weblog_id)`, each `ON DELETE CASCADE` to the user, entry
  and weblog. The history keeps the newest 500 per reader. Schema, service and
  endpoint details: `docs/dev/sso.md`, "Reader state".
- **The endpoint** `GET/POST /pressroom-ui/rendering/reader.rol`
  (`ReaderStateController`, `no-store`): the page script's only server call.
- **The visibility rule** (`ReaderVisibility`): published, `pubTime <= now`,
  not trashed, on an active, visible weblog. Nothing else is saved, followed
  or recorded, and every list repeats the rule.
- **The history switch**: "Keep my reading history" (`pressroom_user.keep_history`,
  V057, on by default) on the account page's Reading history section. Off
  clears the history and `ReaderStateService.recordRead` records nothing
  more; Continue reading, read marks and the signed-in Places then come up
  empty.
- **The pages** (weblog.vm; only with reader sign-in on, and never on a blog
  with a custom domain, whose host carries no site session). A signed-out page
  holds Save (the showSaveControl macro, in the share row, or its own
  `p.entry-save-row` when the row is off) and Follow (the showFollowControl
  macro, in each article theme's masthead and on the `p.reader-follow-line`
  above Keep reading) in their neutral state, the same bytes for every
  visitor. Everything personal is rendered only into a signed-in page (pages
  are cached per signed-in user) or filled in the browser: the "You" menu
  (the showYouMenu macro: initials, name, links to the account page's
  sections, Main Menu for an author, Sign out, in a `details`), the
  `data-signed-in` flag and beacon entry on the script's data element, the
  reader rows (the showReaderRows macro on a blog home; `fd-continue` and
  `fd-followed` on the front page, directly under the masthead), the
  `data-reader-entry` hooks for read marks, and `data-places-source="server"`
  (the browser's own Places script and recording are left out, so
  `localStorage["pressroom.places"]` is untouched and shows again after
  sign-out).
- **Anonymous pages stay small and shared** (the spec's anti-goal; fix round
  1). The showReaderScript macro (called by the colophon, so every bundled
  theme and the site's own pages get it) gives a **signed-out** page only an
  inline stub, `<script data-reader-stub>`, about 1.3 KB, with no inline
  reader style and no data element (`ReaderControlsRenderTest` holds it under
  a 2 KB budget and free of bundle code; it was about 20 KB of inline script,
  style and data before). The stub drops any stored intent (a cancelled
  sign-in), and a Save or Follow click stores
  `sessionStorage["pressroom.intent"] = {action, entry|weblog, via, at}` and
  puts `window.pressroomGoogleSignIn(via, label)` in the control's place, or a
  plain `sso/start` link on a forked head without the helper. The few
  signed-out control rules live in each article theme's stylesheet.
- **A signed-in page** (rendered and cached per user) gets the data element
  (endpoint, words, `data-signed-in`, the beacon's entry on a post), the
  reader stylesheet `pressroom-ui/styles/pressroom-reader.css?v=<version>`
  (head, the showGoogleSignInAssets macro) and the deferred bundle
  `pressroom-ui/scripts/pressroom-reader.js?v=<version>`, built from
  `app/frontend/src/reader.js` like `pressroom-content.js` and git-ignored.
  The bundle closes the "You" menu (so it loads on any signed-in page), then
  removes the intent **before** posting it (a reload cannot repeat it; one
  older than 30 minutes is dropped), asks `reader.rol` once for the page's
  state, completes the intent and confirms inline, offers "Also email me new
  posts" after a follow when `newsletter.available`, fills the rows, read
  marks and Places, and posts one `read` per page view after 10 s visible
  (`visibilitychange`-aware, `fetch` with `keepalive`, since `sendBeacon`
  cannot carry the CSRF header; a beacon due before the state answer waits
  for it). Tests: `ReaderControlsRenderTest`, `app/frontend/test/reader.test.mjs`
  (the bundle's source and the template's stub, run in jsdom), `ReaderStateIT`
  (both context paths).
- **No reader controls on a blog with a custom domain** (accepted ruling):
  that host does not carry the site's session cookie, and `/pressroom-ui` is
  cross-origin there, as for the comments slot. `UtilitiesModel.readerStateOn`
  is the one gate.
- **The "fresh browser" in `ReaderStateIT`** (accepted ruling) is the same
  driver with every cookie cleared (the provider's too) and the origin's
  local and session storage emptied: the feature's only client state.
  `BrowserHealth`'s DevTools session belongs to the driver, so a new one
  mid-test is not an option.
