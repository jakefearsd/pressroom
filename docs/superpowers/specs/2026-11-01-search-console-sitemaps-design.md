# Search engines learn about changes: sitemaps that scale, and change notices to jakemon

Date: 2026-11-01. Status: draft for Jake's approval. Covers two repositories:
Pressroom (`~/source/pressroom`) and jakemon (`~/source/jakemon`).

## Intent

Search engines should find a Pressroom post soon after it goes live, learn when
it changes, and learn when it is gone, without Pressroom itself talking to any
search engine. jakemon is where all search-engine traffic for the stack is
centralised (Search Console, Bing, IndexNow), so Pressroom's job is to be a
cheap, accurate source: sitemaps a crawler can read at any scale, and a prompt
notice to jakemon each time a public address changes.

The mechanism must not become the limit later. Today there are five blogs on
one host; the design must hold at thousands of blogs and millions of posts, and
leave room for custom domains that each carry many blogs. **Simplicity is the
key goal for now** (Jake, 2026-11-01): the design is expected to evolve, and
above all the way notices travel (HTTP today) must be easy to swap for
something else (a message broker) later.

## Goals

1. Every host Pressroom serves has one `robots.txt` naming one sitemap index,
   and that index lists every indexable address the host serves, at any scale.
2. Each sitemap file and each index entry carries a `lastmod` that moves only
   when the content it describes moved.
3. When a post's public address appears, changes or disappears, Pressroom tells
   jakemon within about two minutes, without ever slowing or failing the save,
   and without losing the notice if jakemon or Pressroom is down or restarts.
4. jakemon accepts such notices on one general intake endpoint, built for any
   product and any future feed, answers at once, and acts later: IndexNow for
   Bing and the other IndexNow engines, a sitemap resubmit for Google.
5. Pressroom serves the IndexNow key file on every host it serves.

## Non-goals (deferred, each with the trigger that brings it back)

| Deferred | Why not now | Bring it back when |
|---|---|---|
| A message broker (RabbitMQ) | A broker does not remove Pressroom's outbox (publishing to it cannot join the database transaction), adds a stateful service to run, and the wiki's telemetry contract says "no event bus". Volume is tiny either way. | A second consumer wants the same events (fan-out), e.g. Wikantik cross-linking or social posting. The broker goes behind jakemon's intake; Pressroom does not change. |
| Paging the sitemap index | One index holds 50,000 sitemaps, i.e. about 500 million posts at 10,000 per page. | A host's index passes 40,000 entries. `robots.txt` may then name several indexes. |
| A "recent changes" sitemap | Notices cover speed; Google re-reads the newest entries page whose `lastmod` moved, which is the same signal. | Google is seen to ignore the resubmits. |
| URL Inspection of new addresses first | The sweep lives in another jakemon container with its own state file; sharing it is not simple. | The sweep's rotation is shown to leave new posts uninspected for too long. |
| Coalescing repeated edits of one post | jakemon de-duplicates per batch; extra rows cost nothing. | Notice volume becomes a cost. |
| Signing requests (HMAC) | Bearer token over the LAN, the same trust level as the existing shipper. | Notices cross a network jakemon's owner does not control. |
| Pressroom `/metrics` | No Micrometer in Pressroom yet; that is its own telemetry initiative. Heartbeats give the alert instead (below). | The telemetry adoption work lands. |
| Notices for whole-weblog changes (deactivated, hidden, deleted settings) | Rare, operator-driven. A deleted weblog's posts *are* covered (each removed entry is a "gone"). | A weblog is hidden in production and the stale addresses matter. |

## Anti-goals (would be a failure even if everything works)

- A publish, edit or delete that waits on jakemon, or fails because jakemon is
  down.
- Pressroom calling Google, Bing or IndexNow itself.
- A notice silently lost: every failure is retried and visible.
- A notice format tied to HTTP, so that changing transport means changing what
  the products or jakemon's worker do.

## Part 1: Pressroom sitemaps

### Facts it builds on

- `SeoController` serves `/robots.txt`, `/sitemap.xml`, `/sitemap-<handle>.xml`
  and `/sitemap-site-pages.xml`. The per-blog file is one `urlset` capped at
  `MAX_SITEMAP_URLS = 50_000` newest posts (older ones silently drop off),
  cached whole in `SitemapCache` keyed on `weblog.lastModified`.
- On a custom domain, `/sitemap.xml` is the weblog's `urlset` itself, not an
  index. The site host's index omits custom-domain weblogs.
- The index's `lastmod` per blog is `weblog.lastModified`, a cache-expiry stamp
  that also moves on settings saves and business-profile saves, so it is not a
  content date. Entry `lastmod` is `updateTime` (fallback `pubTime`).
- `getSitemapEntries` filters `status='PUBLISHED'` in SQL; `noindex` rows and
  rows whose canonical URL points elsewhere are dropped in Java after the
  `LIMIT`, so they still use up slots.

### New layout (per host)

| Path | Contents |
|---|---|
| `/robots.txt` | Unchanged: one `Sitemap:` line naming this host's `/sitemap.xml`. |
| `/sitemap.xml` | Always a `sitemapindex`, on the site host and on custom domains alike. Lists, for each weblog this host serves: its `/sitemap-<handle>.xml` and each of its entries pages. On the site host, also `/sitemap-site-pages.xml` as today. |
| `/sitemap-<handle>.xml` | Same address as today, smaller content: the weblog's home URL and its published, indexable weblog pages (what it holds today, minus entries). Keeping the address means nothing Google already knows starts failing. |
| `/sitemaps/<handle>/entries-<n>.xml` | Entries page `n` (1-based) of the weblog's indexable published entries, ordered **oldest first** (`pubtime ASC, id ASC`), `S` per page. |
| `/sitemap-site-pages.xml` | Unchanged. |

- "The weblogs this host serves" is computed in exactly one place (today: every
  visible, active weblog without a custom domain on the site host; the one
  weblog on a custom domain). When a domain can carry many blogs, only that
  function changes.
- `S` is the startup property `seo.sitemap.pageSize`, default 10,000, at most
  50,000. Oldest-first means a new post changes only the last page; older pages
  change only when one of their posts is edited or leaves. Unpublishing or
  deleting a post shifts later posts one slot down a page boundary; that is
  accepted (sitemaps are hints, and the affected pages' files change content,
  not addresses).
- "Indexable" moves into the SQL: `status = 'PUBLISHED' AND noindex = false`.
  A canonical pointing elsewhere is still dropped in Java (it needs the
  weblog's absolute URL), so a page may hold slightly fewer than `S` rows.
- A request for a weblog the host does not serve, an inactive or invisible
  weblog, or a page number past the last answers 404. A weblog with no
  indexable entries lists no entries pages.
- The new `/sitemaps/` entries paths must reach `SeoController` on custom domains too:
  add them to the protected URLs the same way `robots.txt` and `sitemap.xml`
  are, so `WeblogRequestMapper` never treats them as weblog content. Generated
  URLs come from the weblog (custom-domain aware, context path kept), never
  from the request.

### Truthful `lastmod`

- Entry `<lastmod>`: unchanged, `updateTime` falling back to `pubTime`. Known
  imprecision, accepted: a save that changes nothing still bumps `updateTime`.
- Index entry for an entries page: the greatest `updateTime` (fallback
  `pubTime`) among that page's rows.
- Index entry for `/sitemap-<handle>.xml`, and the home URL's own `<lastmod>`: the greatest
  of the weblog's published pages' `updated` and its newest indexable entry's
  `updateTime`.
- `weblog.lastModified` is never written into a sitemap again. It stays the
  cache-expiry stamp.

### Caching

- Each file (`/sitemap-<handle>.xml`, each `entries-<n>.xml`) is cached in `SitemapCache`
  under a key that includes the file name, still expiring on
  `weblog.lastModified` (anything that changes sitemap content already bumps
  it). The 2 MB per-document cap is raised or made per-file so a 10,000-row
  page fits (a full page is roughly 1.5 to 2.5 MB with image tags).
- The index is cached per host, keyed on the greatest `lastModified` among the
  weblogs that host serves (the list is loaded anyway).
- Builder's choice how the index's per-page maxima are queried; guidance: a
  fixed number of queries per host, not one per weblog.

## Part 2: Pressroom change notices (the outbox)

### What produces a notice

A notice is one row: an absolute public address, its host, a change kind
(`new`, `changed`, `gone`), and when. Only entries produce notices, and only
entries of a weblog that is visible and active. "Public" below means status
`PUBLISHED`.

| Event | Where (all already exist) | Notice |
|---|---|---|
| An entry becomes public: first publish, scheduled post going live, restore-then-publish, draft to published | `saveWeblogEntry`, which already knows `loadedStatus` | `new` |
| A public entry is saved again, same anchor | `saveWeblogEntry` | `changed` |
| A public entry is saved with a new anchor | `saveWeblogEntry` (`loadedAnchor` differs) | `gone` for the old address, `new` for the new |
| A public entry leaves public view: to draft, pending, scheduled or trash | `saveWeblogEntry` | `gone` |
| A public entry is deleted outright (delete forever, weblog deletion) | `removeWeblogEntry` | `gone` |
| A trashed entry is purged | `removeWeblogEntry` | none (it was already gone) |

- Entries marked `noindex` produce no `new` or `changed` notices; one leaving
  public view still produces `gone`. Toggling `noindex` produces nothing
  (Google reads the meta tag on its next crawl).
- `changed_at` is the entry's `updateTime` for `new` and `changed`, and the
  time of the save or delete for `gone`.
- Rows are written with `strategy.store` inside the same manager call, so they
  commit or roll back with the entry change (precedent:
  `JPAEventManagerImpl.record`).
- Rows are written only when the jakemon URL is configured; with it unset the
  whole feature is off and writes nothing.
- The feature also needs `site.absoluteurl` set. Without it Pressroom's
  absolute URLs come from whatever host the first request used, or are null
  before any request (the shape of the old docker1 `.lan` bug), and a notice
  would carry a wrong address. So with the jakemon URL set but no
  `site.absoluteurl`, the feature stays off and Pressroom logs one WARN at
  startup saying why.

### The table (migration V042)

`pressroom_change_notice`: `id uuid primary key` (the notice's identity
everywhere, so a database restore that resets sequences can never reuse one),
`weblog_id`, `url`, `host`, `change` (`new`/`changed`/`gone`), `changed_at`,
`created_at`, `sent_at` (null until jakemon accepted it), `attempts`,
`last_error`. Index on `(sent_at, created_at)` for the sender. Idempotent DDL,
as every migration.

### Sending

- A new leased task, `ChangeNoticeTask`, registered beside
  `ScheduledEntriesTask` in `pressroom.properties`, runs every minute (the
  scheduler's tick), so a notice reaches jakemon within about two minutes of
  the save. Leasing keeps a second instance from sending the same rows.
- Each run takes unsent rows oldest first, at most 1,000 per request, and keeps
  sending until none are left or a request fails.
- On a 2xx answer the rows are marked sent. On anything else (refused, timed
  out, unreachable, 4xx or 5xx) nothing is marked; `attempts` and
  `last_error` are updated, a WARN line is logged with
  `event.action=change_notice_failed`, and the next run tries again. A
  configuration error never drops a notice.
- When nothing has been sent for 10 minutes, the task sends a heartbeat: the
  same request with no rows. That lets jakemon tell "no posts lately" from "the
  feed is broken".
- Sent rows older than 7 days are deleted by the same task. Unsent rows are
  kept however long jakemon is away.
- The task talks to jakemon through one small interface (send one envelope,
  get accepted or failed). HTTP is its only implementation. Swapping transport
  means a second implementation of that interface; the outbox, the task and
  the envelope do not change.

### Configuration (startup properties, `PRESSROOM_*` environment overrides)

| Property | Meaning |
|---|---|
| `notices.jakemon.url` | jakemon's intake URL. Unset (the default) turns the feature off. |
| `notices.jakemon.token` | Bearer token jakemon issued for this Pressroom. Never logged. |
| `notices.source` | This instance's name per SA-5, default `pressroom-prod`. |

The HTTP client follows `ListmonkClient` (`java.net.http.HttpClient`, short
connect and request timeouts).

### The IndexNow key file

- New runtime property `seo.indexnow.key` (settable with `admin config set`).
  When set, `GET /<key>.txt` on every host Pressroom serves answers `200
  text/plain` with the key as the body. Any other `.txt` path behaves exactly
  as today. Unset, nothing is served.
- One key serves every Pressroom host; IndexNow allows one key on many hosts.
  The operator puts the same key in jakemon's key directory for each host. If
  a domain jakemon already holds a key for (`jakefear.com`, say) later moves
  to Pressroom, its key file is replaced with Pressroom's: the old site no
  longer serves that domain.
- IndexNow under a context path is out of scope. A key file at
  `/pressroom/<key>.txt` would cover only URLs beneath it, and jakemon sends
  `keyLocation` at the host root. Production runs at the root. Pressroom still
  serves the file at `<context>/<key>.txt`, so nothing breaks, but jakemon's
  submissions for such a deployment would be refused.
- Like the sitemap paths, this must work on custom domains and under a context
  path.

## Part 3: The intake contract (between products and jakemon)

### Envelope

The same envelope the wiki defines for jakemon's outgoing feeds, now used in
the other direction too:

```json
{
  "feed": "content_changes",
  "feed_version": 1,
  "source": "pressroom-prod",
  "sent_at": "2026-11-01T18:04:11Z",
  "rows": [
    { "id": "6f1c2a9e-…", "url": "https://blog.wikantik.com/wikantik/entry/some-post",
      "host": "blog.wikantik.com", "change": "new", "changed_at": "2026-11-01T18:03:52Z" }
  ]
}
```

- `id` is the row's identity; jakemon ignores an `id` it already holds. That
  makes every resend safe, whatever the transport, and needs no batch IDs.
- The feed-evolution rules from SimpleAgilityObservabilityPlane apply
  unchanged: unknown fields are ignored, `feed_version` moves only on a
  breaking change, an unknown version is refused with a reason, unusable rows
  are counted, never silently dropped.
- An empty `rows` is a heartbeat.

### HTTP binding (the only transport for now)

- `POST /intake` with `Authorization: Bearer <token>`, JSON body.
- `202` with `{"accepted": n, "duplicate": n, "skipped": n}` once the rows are
  durably stored.
- `401` bad or missing token (the token decides `source`; a body whose
  `source` disagrees is refused `400`). `400` malformed envelope or unknown
  `feed`/`feed_version`, with a reason. `413` more than 1,000 rows or a body
  over 1 MB.

### Decision-log entry to propose (written to the wiki after approval)

"SA-14 (proposed): products may push functional feeds into the plane through
one intake endpoint. Rows are requests for the plane to act (for example,
tell search engines an address changed), not analytics events, so the
'events are log lines' rule does not apply to them; products still log one
`event.action` line per notice. The intake serves the whole stack, so it falls
under SA-4's exception. Transport is HTTP; a broker may replace or sit behind
it when a second consumer needs the same feed, without changing the envelope."
The number is whatever is next in SimpleAgilityDecisionLog. The plane page
gains a short "In-contract: product feeds" section pointing at it.

## Part 4: jakemon intake

### Facts it builds on

jakemon runs on `inference`, deployed by `bin/deploy-central.sh`; its Python
is standard library only, on the pinned `python:3.12-slim` image; services are
`ThreadingHTTPServer` processes serving `/metrics` and `/healthz`, none of
which accepts a POST or publishes a port today. State lives as atomically
written JSON files in the `visibility-data` volume. IndexNow submission
(`visibility/actions.py`) and the GSC sitemap `PUT`
(`providers/google.GoogleProvider.submit_sitemap`) exist but are only run by
hand through `bin/visibility submit`. Keys live in `visibility/indexnow-keys/
<host>.txt`, mounted read-only. `blog.wikantik.com` is in no configuration yet.

### The service

- A new container, `intake`, in `docker-compose.yml`, on the same pinned
  Python image, code under `visibility/` (or a sibling directory; builder's
  choice), state under `/data/intake/`.
- Its port is published on the LAN only, never through cloudflared. It is the
  first inbound write endpoint in jakemon.
- Tokens: `INTAKE_TOKENS` in jakemon's `.env`, one `source=token` pair per
  sender. With none configured the service idles and serves `/healthz` (the
  shipper's pattern).
- Storage: one SQLite file (standard library `sqlite3`) under `/data/intake/`.
  A table of received rows keyed by `id`, with `feed`, `source`, the row JSON,
  `received_at` and `processed_at`; a request is accepted in one transaction
  (`INSERT OR IGNORE`, then `202`).
- Worker: a daemon thread in the same process, every 30 seconds:
  1. Takes unprocessed `content_changes` rows, groups them by `host`, and
     de-duplicates by `url` (latest `changed_at` wins).
  2. IndexNow: for each host that has a key file and is not marked
     `"indexnow": false`, submits the URLs (all three kinds; IndexNow takes
     deleted URLs too), up to 10,000 per request, reusing `actions.py`.
     A host without a key is skipped and counted.
  3. Google: for each host with `new` or `changed` rows, resubmits
     `https://<host>/sitemap.xml` through `submit_sitemap`, at most once per
     host per hour. The GSC property is found by a new, small resolver: the
     `sites.json` entry whose key equals the host, else the entry whose
     `google` value is `sc-domain:<d>` where the host is `<d>` or ends in
     `.<d>`. No match: skipped and counted. (Nothing maps a host to a covering
     property today; `property_for` is an exact key lookup.) Hosts are **not**
     added to `sites.json` for the intake's sake: every existing module loops
     over it, and a `blog.wikantik.com` entry under `sc-domain:wikantik.com`
     would make the exporter pull the domain's analytics twice.
  4. Marks the rows processed. A failed submission leaves its rows unprocessed
     for the next round.
  5. Deletes processed rows older than 30 days.
- Metrics on `/metrics` (labels bounded: `source`, `feed`, `engine` only):
  rows accepted, duplicate and skipped; last request time per source (heartbeats
  included); submissions succeeded and failed per engine; unprocessed rows.
- Alerts (each with an `Incident<Name>` runbook, as `validate.sh` requires):
  `IntakeSourceSilent` (a configured source has sent nothing, not even a
  heartbeat, for an hour), `IntakeSubmissionsFailing` (a search engine has
  refused every submission for 6 hours), `IntakeBacklog` (unprocessed rows
  older than an hour).
- The intake is not the place for product knowledge: it knows feeds, hosts and
  addresses, never weblogs or posts (the plane's 3a/3b rule).

## Production wiring (operator steps, done once, by Jake's leave)

1. Deploy jakemon's intake; issue a token for `pressroom-prod`.
2. Put the IndexNow key in jakemon's key directory as `blog.wikantik.com.txt`
   (no `sites.json` change; the resolver finds `sc-domain:wikantik.com`).
   Confirm the GSC token has the read-write `webmasters` scope and
   that Search Console accepts a sitemap on that host under the domain
   property. Confirm docker1's `site.absoluteurl` is
   `https://blog.wikantik.com`.
3. On docker1: set `seo.indexnow.key`, and add `notices.jakemon.url`,
   `notices.jakemon.token` to `~/pressroom/.env`; deploy.
4. Check: `/sitemap.xml` on blog.wikantik.com is an index of each blog's `/sitemap-<handle>.xml` and
   entries pages; `/<key>.txt` serves the key; jakemon's metrics show a
   heartbeat within 10 minutes; a test publish appears as an accepted row and
   an IndexNow submission.

## Acceptance criteria (each one testable)

Pressroom sitemaps:
1. `/sitemap.xml` on the site host is a `sitemapindex` listing, per visible,
   active, non-custom-domain weblog, `/sitemap-<handle>.xml` and one
   `entries-<n>.xml` per `S` indexable entries; plus `/sitemap-site-pages.xml`
   when the site space has published pages.
2. `/sitemap.xml` on a custom domain is a `sitemapindex` listing only that
   weblog's files, at that host, with no site pages.
3. With `S = 2` and five indexable entries, there are entries pages 1 to 3,
   page 1 holds the two oldest, page 3 the newest; page 4 is 404.
4. A `noindex` entry appears on no page and takes no slot on any page.
5. Publishing a new entry changes the content and index `lastmod` of only the
   last entries page.
6. Saving weblog settings changes no `lastmod` in any sitemap or index.
7. An index entry's `lastmod` equals the greatest entry `updateTime` on that
   page.
8. `/sitemap-<handle>.xml` still answers 200 and lists the home URL and the
   weblog's indexable pages, and no entries.
9. All of the above hold under a context path and on a custom domain (browser
   suite at `/` and `/pressroom`).

Pressroom notices:
10. With `notices.jakemon.url` unset, no save or delete writes a notice row.
    With it set but `site.absoluteurl` blank, none is written either, and one
    WARN is logged at startup.
11. Each row of the "What produces a notice" table produces exactly the notices
    it lists (one test per row), including the anchor change and the scheduled
    post going live through `ScheduledEntriesTask`.
12. A save that rolls back leaves no notice row.
13. A noindex entry produces no `new`/`changed`, and does produce `gone` when it
    leaves public view.
14. Against a fake intake: rows are sent oldest first, at most 1,000 per
    request, with the envelope above; on `202` they are marked sent; on `500`,
    `401` or a timeout none are marked and the next run resends the same ids.
15. With nothing sent for 10 minutes, the next run sends an empty heartbeat.
16. Sent rows older than 7 days are deleted; unsent rows are never deleted.
17. The token never appears in a log line or error message.
18. `/<key>.txt` serves the configured key as `text/plain` on the site host
    and a custom domain, at `/` and under a context path; with the key unset,
    or for any other `.txt` path, behaviour is unchanged.

jakemon intake (standard-library `unittest`, run by `bin/validate.sh`):
19. A valid envelope is stored and answered `202` with counts; resending it
    answers `202` with every row counted `duplicate`, and stores nothing new.
20. Missing or wrong token: `401`; `source` disagreeing with the token: `400`;
    unknown `feed_version`: `400` naming it; 1,001 rows: `413`. An unknown
    field in the envelope or a row is ignored, not refused.
21. An empty envelope updates the source's last-seen metric and stores nothing.
22. The property resolver maps `blog.wikantik.com` to `sc-domain:wikantik.com`,
    an exact `sites.json` key to itself, and `example.org` to nothing;
    `notwikantik.com` does not match `sc-domain:wikantik.com`.
    The worker, with injected fakes for IndexNow and GSC, submits each host's
    rows to IndexNow once (de-duplicated by URL), skips and counts a host with
    no key, resubmits the host's sitemap at most once an hour, and leaves rows
    unprocessed when a submission fails.
23. `promtool test rules` covers the three new alerts; each has its runbook.

## Constraints

- Pressroom: TDD, all gates (JaCoCo floors, PMD/CPD, SpotBugs, `-Werror`, diff
  coverage), one build at a time, migrations idempotent, no code assumes a
  context path, routing changes run the browser suite at both context paths.
- jakemon: standard-library Python only, pinned images, `bin/validate.sh`
  before every commit, every alert has a runbook, no secrets committed.
- Never commit or push without Jake asking, in either repository.
- Docs: `docs/dev/content-features.md` (SEO) and `docs/dev/configuration.md`
  in Pressroom; jakemon's CLAUDE.md/docs for the intake; the wiki's decision
  log and plane page after approval.

## Suggested order

1. Pressroom sitemaps (Part 1). Ships on its own and is useful alone.
2. Pressroom notices and key file (Part 2). Ships dark: nothing happens until
   the URL is configured.
3. jakemon intake (Part 4).
4. Production wiring, then the wiki updates.

## Left to the builder

- How the index's per-page maxima are queried, and whether entries pages use
  `OFFSET` or keyset paging (results must match criteria 3 to 7).
- Exactly how `/<key>.txt` and `/sitemaps/**` are routed to the controller, as
  long as custom domains and context paths work and protected-URL lists stay
  consistent.
- The intake's port number, module layout, and SQLite schema details.
- Log line wording, beyond `event.action=change_notice_failed`.
