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
- **Persist-first, then notify.** `ContactController` writes the
  `pressroom_form_submission` row before any notification email, so the lead
  survives an SMTP outage. Defences in order: per-IP throttle (429); unknown
  weblog handle 404s; **a filled honeypot or a too-fast submit answers 204,
  identically to success, and stores nothing**, so automation learns nothing.
  Subscribe mirrors both.
- **`/newsletter/subscribe` is served by the app, not Caddy.** Throttle and
  `pressroom_event` recording live in `NewsletterController`; the old Caddy
  `handle /newsletter/subscribe { rewrite ... reverse_proxy listmonk }` block
  bypassed both and **must never come back**. **Roller stores no subscriber
  data** — Listmonk owns the list, opt-in, sending and unsubscribe; Roller
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
Per-weblog Umami tracking plus a read-only Grafana contract over two databases.

- **Structured injection is the only analytics path; no free-text fallback
  (W2).** `Weblog.analyticsSiteId` is a validated UUID
  (`WeblogConfigController.myValidate`). `#showAnalyticsTrackingCode`
  (`weblog.vm`) builds the
  `<script defer src="…" data-website-id="…" data-host-url="…">` tag from that
  UUID plus `ConfigModel.getAnalyticsBasePath()`/`getAnalyticsScriptName()`
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
- **Same-origin, so the pinned CSPs never moved.** Served via Caddy's
  `/analytics/*` handle, the tracker runs under every theme's
  `script-src 'self'` / `connect-src 'self'`;
  `ThemeCspCoverageTest.everyPolicyStillAllowsSameOriginScriptsAndBeacons`
  fails otherwise.
- **The Grafana contract splits across two databases — Postgres cannot query
  across them.** `analytics_events` (outcomes from `pressroom_event`) and
  `analytics_weblog_sites` (handle ↔ Umami website-id) live in `pressroom`, via
  `bin/db/migrations/V017__analytics_contract.sql`. `analytics_traffic` (over
  Umami's `website_event`) lives in Umami's database:
  `deploy/analytics/umami-views.sql` (in the image at `/app/umami-views.sql`),
  applied by the **separate** one-shot `analytics-views`
  (`deploy/analytics-views.sh`), never by `provision.sh` or the migration chain
  (which only touches `pressroom`). `website_event` exists only after Umami's
  first boot and `provision` runs before `umami` starts, so applying the view
  from `provision` deadlocked every fresh install; `analytics-views` gates
  nothing — a failure costs only the Grafana traffic panel. Grafana joins the
  halves on `website_id`; no server query spans both.
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
  `.orm.xml`), the `roller_hitcounts` table (`V017`), `WeblogEntryManager`'s
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
  grants it `CONNECT` on both databases for both Grafana datasources. No
  compose file publishes a Postgres host port — tunnel-only.
