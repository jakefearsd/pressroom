# Analytics without admin steps

Date: 2026-11-01. Status: draft for the owner's review.

## Why

Setting up popularity for the discovery front page (0.6.0) exposed how much
hand work analytics takes. Each blog needs a website made in Umami's UI, then
its ID copied into the blog's settings, and Umami's dashboard turned out to be
both clumsy and partly exposed on the public internet. The owner's ruling:
**creating a blog must handle all of it.** Nobody should open Umami to get a
blog counted, and the numbers people want should be visible in Pressroom's
own admin.

This is the first of two designs. The second one moves Umami out of
Pressroom's deployment into a back-office suite with jakemon (to be renamed),
Grafana and Loki, which matures as its own piece of infrastructure. This
design must not stand in that one's way. The rule that follows: **Pressroom
treats Umami as a separate, internal service it talks to only over Umami's
HTTP API, at an address that is configuration.** Moving Umami later changes
settings and a proxy target, not Pressroom code.

The stack's contract pages on the wiki agree (SimpleAgilityStackHub,
SimpleAgilityObservabilityPlane, SimpleAgilityFeedbackLoopPattern): capture
belongs outside the product, and the product keeps its own tables for
whatever drives its behaviour or appears on its own admin screens.

## Goals

1. Every blog is registered with Umami automatically, under **its own weblog
   ID** (blog IDs are already UUIDs, and Umami 3.3's `POST /api/websites`
   accepts a caller-chosen `id`). That covers new blogs, existing blogs (the
   backfill) and renames. No admin step anywhere.
2. Every registered blog carries the full Umami tracker with no per-blog
   configuration. It collects page views, visits, referrers, UTM campaigns,
   custom events (the existing `cta-click` and `business-card-click`) and page
   speed (the tracker's `data-performance`).
3. A blog's admin gets a **Traffic** page (layout B below), Global admin gets
   a site-wide one, and the entries list gets a views column.
4. The discovery ranking reads the same local numbers instead of Umami's
   database.
5. Umami's admin surface is **not reachable from the public internet**. The
   public surface is exactly the tracker endpoints for the features that are
   switched on.

## Non-goals

- Moving Umami to another host or repository (design 2).
- Session replay and heatmaps. They stay **off**, but must not be designed
  out: switching them on later should mean opening their public endpoints and
  setting each website's replay config, with no rework of this design. The
  owner is not yet sure how he would use that data.
- Tracking the wiki or any other non-Pressroom property (design 2; this design
  only has to leave the door open).
- Rebuilding Umami's reports in full. Pressroom shows the useful summary, and
  the deep dives stay in Umami, reached internally.

## Anti-goals (failure even if it works)

- **Any page request that waits on Umami**, whether a blog page, an admin page
  or the front page. Every interaction with Umami runs in the background.
- **Blog creation that fails or stalls because Umami is down.**
- **A new dependency on Umami's database or on Umami living in Pressroom's
  compose project.** The direct JDBC read added for 0.6.0 goes away.
- **Numbers that look current when they are not.** Every screen says how old
  its numbers are and where they came from.
- A public route to Umami's login, API or dashboard.

## Decided (owner)

- Registration is automatic for every blog. Renames carry over. **Deleting a
  blog leaves its website and history in Umami.**
- Stats live in Pressroom admin. Umami stays in the background and becomes a
  first-class back-office component later, never "in between".
- Interactions are **asynchronous and loosely coupled**. Reads are **batched**
  into a **local cache** shaped for how Pressroom uses the data.
- Umami is internal only. If the owner opens it, he goes through zero-trust
  access, as with Grafana. Its tracker endpoints may be public, and nothing
  else may be.
- Full collection, with replay and heatmaps off but kept possible.
- The Traffic page uses **layout B**: summary and trend always on top, then one
  report at a time by tab. "LGTM until I know better", so details may move.

## Current state (for the builder)

- `Weblog.analyticsSiteId` and `Weblog.analyticsShareUrl` (columns
  `analytics_site_id` and `analytics_share_url`) are set by hand in
  `WeblogConfig.jsp` and validated in `WeblogSettingsRules`. They are also
  exposed in `AdminDtos` and `WeblogWrapper`.
- The tracker tag comes from the `showAnalyticsTrackingCode` macro in
  `WEB-INF/velocity/weblog.vm`. It renders only when the site ID is set, and
  every theme calls it. The path comes from `analytics.umami.basePath` and
  `analytics.umami.scriptName` (`pressroom.properties`).
- Caddy (`deploy/caddy/Caddyfile`) forwards **everything** under
  `/analytics/*` to Umami, so `/analytics/api/auth/login` and the rest of
  Umami's API are public today. The dashboard has its own Caddy site on
  `:8081`, published on docker1's loopback only.
- Discovery popularity: `TrafficSource` / `UmamiTrafficSource` read the
  `analytics_traffic` view in Umami's database over `analytics.jdbc.*`
  (`PRESSROOM_ANALYTICS_JDBC_*`, using the `grafana_ro` role). That view and
  `analytics_weblog_sites` in Pressroom's database also back the SA-12
  attribution dashboards in Grafana. `deploy/analytics-views.sh` installs the
  Umami-side view after Umami's first boot.
- docker1 runs Umami 3.3.0 inside the `pressroom` compose project, with **no
  websites and no events** (2026-11-01), so nothing needs migrating. One Umami
  user exists (`admin`, password changed by the owner on 2026-11-01).
- Blog creation goes through `WeblogManager.addWeblog`, reached from
  `CreateWeblogController`. Renames go through `saveWeblog`, deletion through
  `removeWeblog`. A blog may have a `customDomain`.
- Privacy wording: `docs/legal/blog-privacy-policy.md`, published as the
  site's privacy page. It currently says a blog's owner "can switch on page
  analytics".

## The design

### One seam

Everything Umami-specific sits in one package behind one Pressroom interface,
an *analytics service*: register or update a site, read numbers for a site
and period, report health. The rest of Pressroom (admin pages, discovery,
templates) sees only that interface and Pressroom's own cache tables. A
second implementation (another analytics engine, or the back-office suite's
own API) must be possible without touching callers.

Configuration: the Umami API base URL (internal, default
`http://umami:3000`), the dedicated account's username and password, an
optional **internal dashboard URL** used only for "Open in Umami" links (unset
means no link), and the refresh intervals. With no API URL or credential
configured, analytics is off cleanly: no tracker tags, Traffic pages say
analytics isn't configured, and discovery falls back to random as it does
today.

### Three background channels, none in a request path

1. **Registration (reconciler).** At startup and on a schedule, Pressroom
   compares its blogs with what it last confirmed in Umami. It creates any
   missing website (`id` = weblog ID, `name` = blog title, `domain` = the
   blog's custom domain or else the site's host) and updates any whose name or
   domain changed. Creating or saving a blog **requests** a reconcile soon
   after commit but never waits for it. This reconciler *is* the backfill: on
   the first start after the upgrade it registers every existing blog. A
   registration that already exists counts as success. Failures are recorded
   per blog with the error and retried. Deleted blogs are left alone in Umami.
2. **Collection (browser to Umami).** For each blog Pressroom has confirmed
   as registered, the tracker tag renders automatically. It carries
   `data-website-id` (the weblog ID), `data-host-url`, `data-performance`, and
   `data-domains` set to the blog's real host, so local dev and test copies
   don't count. Unregistered blogs get no tag, because Umami rejects events
   for unknown websites. The tag stays same-origin (`/analytics/...`), so the
   theme CSPs keep `'self'`.
3. **Numbers in (batch reader).** On a schedule (default 15 minutes), for each
   registered blog, Pressroom reads Umami's API for each supported period
   (24h, 7d, 30d, 90d, plus the discovery window if different):
   - summary stats for the period and for the previous period;
   - the daily series;
   - per-path, referrer, country, device and browser metrics;
   - events and UTM campaigns.

   It writes them to its own tables, **replacing** that blog-and-period
   snapshot whole. Snapshots are **selected, never summed** (unique visitors
   don't add across days or periods). Each snapshot records `as_of` and its
   source. A failed read keeps the previous snapshot and records the failure.
   Reads use the API's own time-zone parameter with the site's time zone.

Paths map to entries in Pressroom (Pressroom knows its own URL shapes,
including custom domains and the context path), so the Posts tab and
discovery can show titles and link to entries. A path that maps to no entry
still counts toward the blog's totals and appears with the raw path.

### The dedicated Umami account

Pressroom never uses `admin`. It uses its own Umami account, which can manage
websites and read their data and nothing else. Creating it needs **no human
step**. The deploy makes the account and its password if they are missing,
keeps the password in `.env` and never prints it. The owner's `admin` must
still see every Pressroom-made website in Umami's admin screens. Ownership
details (a user-owned website or a team) are the builder's call, provided that
holds. *(Builder's call: the post-boot one-shot that already runs against
Umami's database, `analytics-views.sh`, is the natural place to make the
account. Umami's user table holds a bcrypt hash, which `pgcrypto` can produce.
If a cleaner route through Umami's own API exists without the admin
password, prefer it.)*

### Public surface (Caddy)

`/analytics/*` narrows to an **allowlist**: the tracker script and the event
endpoint (`/script.js` and `/api/send` after the prefix is stripped).
Everything else under `/analytics/` answers 404 without reaching Umami.
Replay's endpoints (`/recorder.js`, `POST /api/record`, and
`GET /api/websites/{id}/recorder`) are listed in the Caddyfile but commented
out, so switching replay on later is a visible one-line change. The
custom-domain block gets the same allowlist.

**The dashboard is reached through zero-trust, never a public hostname.**
*(My call, from the owner's "via zero-trust as I do with grafana today".)*
cloudflared runs on a separate host (`cloudflare`) and is the only ingress, so
it can't reach a port bound to docker1's loopback. The `:8081` dashboard site
is therefore published on docker1's **LAN** address: a plain-HTTP origin on a
high port, the fleet convention in HomeLabFleetAndIngress. Its public face is a
Cloudflare Access-protected hostname, an ops step done on the `cloudflare`
host, outside this repo. That hostname is the internal dashboard URL setting.

The **`UMAMI_DOMAIN` public-hostname mode is removed**: the Caddyfile's
`{$UMAMI_DOMAIN}` site, the compose and `.env.example` lines, and the docs that
describe it. Keeping it would put the whole dashboard and API on the internet,
which contradicts Goal 5. `ProductionComposeTest` and the docs change with it.

### Admin surfaces

- **Blog › Traffic (layout B).**
  - A period switch: 24h, 7d, 30d, 90d.
  - Five headline numbers with change against the previous period: visitors,
    visits, views, bounce rate, visit time.
  - A daily trend chart.
  - Tabs: **Posts** (every post, sortable), **Sources**, **Places**,
    **Devices**, **Events**, **Campaigns**, **Speed**.
  - A freshness line ("Numbers from Umami as of 14:05, refreshed every 15
    minutes"), and "Open in Umami" when the internal dashboard URL is set.
  - Tab columns show only what Umami's API actually reports. A column the API
    can't support is dropped, never estimated. Umami 3.3.0 has the reports
    the tabs need: its API includes `reports/performance` (LCP, INP, CLS, FCP,
    TTFB) for **Speed** and `reports/utm` for **Campaigns** (checked on
    docker1, 2026-11-01).
- **Global › Traffic.** One row per blog (visitors, views, change, top post),
  plus site-wide top posts and sources. The total row sums **views and visits
  only**. *(My call.)* Each blog is its own Umami website, Umami has no
  cross-website unique-visitor count, and a summed visitor total would
  double-count readers of two blogs. So the visitors column has no total, and
  it says why on hover. Each row opens that
  blog's Traffic page. Who may see what follows the existing permission
  model: blog pages for that blog's members with admin rights to it, the
  global page for global admins.
- **Entries list.** A "Views (30d)" column from the cache.
- **Maintenance › Analytics.** Blogs registered (n of m, with the failing ones
  named and their errors shown), last read time and result, whether Umami is
  reachable, and a "Sync now" button that requests a reconcile and a read
  (it doesn't wait for them).
- **Blog settings** lose the analytics site ID and share URL fields.

Every surface distinguishes *no data yet* (registered recently, nothing
counted), *not configured* and *stale* (last read failed; shows its age) from
*zero*. That is the stack's suppression-is-an-output rule.

### Discovery

`TrafficSource` gets an implementation over the local cache: views per entry
over the discovery window. `UmamiTrafficSource` is removed, along with the
`analytics.jdbc.*` settings and their `.env` lines. Discovery's refresh never
calls Umami; it reads Pressroom's tables.

### Data and schema

- A new migration adds the cache tables (registration state per blog;
  snapshots per blog, period and kind) and drops `analytics_site_id` and
  `analytics_share_url`.
- `analytics_weblog_sites` (used by Grafana) is redefined so it keeps working:
  each handle maps to its weblog ID as the website ID.
- The Umami-side `analytics_traffic` view and `analytics-views.sh` remain for
  Grafana, since they are back-office concerns. Pressroom no longer reads them.
- The `grafana_ro` login the owner enabled on 2026-11-01 is no longer needed
  by Pressroom. Whether Grafana keeps using it is design 2's business.
- All tables follow the existing migration rules (idempotent `V<NNN>__*.sql`;
  three appliers).

### Privacy page

Rewrite the analytics paragraph in `docs/legal/blog-privacy-policy.md` to
say that analytics is on for every blog and to list **what Umami actually
stores**, checked against Umami 3.3.0 and not against this paragraph:
- page views, referrers and campaign tags;
- the two click events and page-load timing;
- country, device, browser, OS, language and screen size;
- visits, which Umami keeps as sessions identified by a salted, rotating
  hash. There are no cookies and nothing is stored on the device.

Session *replay* is not recorded. **The owner approves the final wording
before release**, since it is legal-facing. Updating the live page is part of
the release checklist.

## Acceptance criteria

1. Creating a blog with Umami reachable registers a Umami website whose ID
   equals the weblog's ID, with the blog's title as name and its host as
   domain, within one reconcile interval and with no admin step.
2. Creating a blog with Umami **unreachable** succeeds as fast as it does
   with analytics unconfigured. The blog shows as unregistered with the error
   on Maintenance, and is registered automatically once Umami returns.
3. On first start after the upgrade, every existing blog is registered
   (docker1: all 7), with no manual step.
4. Renaming a blog, or changing its custom domain, updates the Umami website
   within one reconcile interval.
5. Deleting a blog leaves its Umami website and events in place.
6. A registered blog's pages carry the tracker tag with its weblog ID,
   `data-domains` and `data-performance`. An unregistered blog's pages carry
   none. Neither case changes the CSP.
7. Through Caddy, `/analytics/script.js` and `POST /analytics/api/send`
   reach Umami. `/analytics/login`, `/analytics/api/auth/login`,
   `/analytics/api/websites` and every other `/analytics/` path answer 404
   without reaching Umami. This holds on the custom-domain block too.
8. No page request (blog, admin, front page) makes a call to Umami. A test
   with Umami stubbed to hang proves every one of those pages renders within
   its normal time.
9. The Traffic page shows layout B with the five headline numbers and their
   change, the daily trend and all seven tabs, from the local cache only. It
   shows the as-of time, and states *no data yet*, *not configured* and
   *stale* distinctly from zero.
10. Global › Traffic lists every blog with visitors, views, change and top
    post, plus a total row for views and visits (none for visitors). Only
    global admins can open it, and a blog's Traffic
    page opens only for those with admin rights to that blog.
11. A failed batch read keeps the previous snapshot, shows *stale* with its
    age, and records the error on Maintenance.
12. Unique-visitor figures are never produced by summing days or periods.
    Each figure is one that Umami reported for that period.
13. Discovery popularity comes from the local cache. With no configuration
    under `analytics.jdbc.*` it still ranks by popularity once the cache has
    data.
14. With no Umami API configuration, Pressroom runs as before: no tracker
    tags, Traffic pages say *not configured*, and discovery is random-only.
15. Pressroom's Umami credential never appears in logs, admin pages, API
    responses or error messages.
16. Blog settings no longer offer the analytics site ID or share URL, and the
    automation API no longer accepts or returns them.
17. Switching replay on later needs only the Caddy lines uncommented and a
    per-website setting. Nothing in this design's code forbids it. (Reviewed,
    not tested.)
18. The privacy policy text matches what Umami 3.3.0 stores, and the owner
    has approved the wording.
19. Nothing in the repo can put Umami's dashboard or API on a public
    hostname: the `UMAMI_DOMAIN` mode is gone, and the dashboard port is
    published only on the LAN.

## Verify first

**Caller-chosen website IDs.** Umami 3.3.0's create-website schema accepts an
optional `id` (UUID), but nobody has yet seen a website created with one.
Prove it against a throwaway Umami 3.3.0 before building on it. If the
supplied `id` is ignored, a stored mapping (a website-ID column in the
registration table) is an acceptable fallback, and so is the matching
`analytics_weblog_sites` definition. *(My call: it changes plumbing, not what
anyone sees.)* AC1's "ID equals the weblog's ID" then becomes "ID recorded
against the weblog".

## Left to the builder

- Package and class names, cache table shapes, intervals (defaults above),
  and how "reconcile soon after commit" is triggered (event, queue or a short
  timer).
- Exactly which Umami endpoints and parameters back each tab, verified
  against Umami 3.3.0. Drop any column the API can't support rather than
  estimating it.
- Token handling for Umami's API: log in, reuse the token, and re-login on
  401.
- How the dedicated account is created and how `admin` keeps visibility
  (constraints above).
- The chart rendering (the admin already has Quiet Instrument styling; no new
  charting library unless one is needed).
- Which weblog context the front page and other site-level pages are counted
  under (the frontpage theme's header already calls the tracker macro).

## Open for design 2 (recorded, not built here)

- Umami's new home in the back-office suite, its zero-trust route, and TLS
  or the trust boundary for Pressroom's API calls across the LAN.
- The tracker for wiki.wikantik.com and other properties: a same-origin proxy
  per site, or one public collector hostname whose tunnel ingress allows only
  the tracker paths.
- Whether Grafana keeps reading Umami's database or moves to a feed.
- A decision-log entry (SA-15) for Umami's place in the stack.
