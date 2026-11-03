# Front page discovery

What `/` shows when no site page or blog is the front door, the "more from
the site" block on every blog page, and "places you've been". Specs:
`docs/superpowers/specs/2026-11-01-front-page-discovery-design.md` (the
picker) and `2026-11-03-front-page-redesign-design.md` (the page).

## The page (2026-11-03 redesign)

One template, `WEB-INF/velocity/templates/weblog/discovery.vm`, whatever
the site space's theme (themes-and-templates.md). Top to bottom: masthead
(site name is the one `h1`), blog pills, lead story with image beside the
numbered popular rail and a newsletter card, house slot A, Latest beside a
sticky sidebar (house slot B, every blog with its post count), shelves,
newsletter band, something different, house slot C, places, all blogs,
footer (site pages, directory, colophon).

- **Section sizes** (`JPADiscoveryManagerImpl`): headline 1, popular 5,
  three cards of 3, different 3, latest 8. **Latest is picked last**, from
  the newest posts no other section took, so it never changes the others;
  on a small site it can be short or empty (the template hides it).
- **Blog hues**: six, assigned by a blog's position in `allBlogs` (by name),
  `data-b="1".."6"`; the mark is `DiscoveryModel.initials`.
- **House slots** `data-slot="a|b|c"` hold the future ad positions. Until
  AdSense is wired (phase 2 of the spec) each spotlights a blog the shelves
  do not show (else any blog). No ad code renders.
- **Newsletter**: the rail card and the band render only when the site
  space has a `newsletterListUuid` (Weblog Settings of the site's pages);
  the form is the subscribe slot the audience script builds.
- **Excerpts** are `WeblogEntryWrapper.getExcerpt`, which passes over a
  search description that is only the post's opening cut mid-sentence
  (`Excerpts.isCutFrom`); the SEO audit reports those as
  `search_description_cut`, and meta titles cut mid-word as `meta_title_cut`.
- **Reading time** is `getReadingMinutes()` (rendered words / 230, rounded
  up). Dates are absolute: the page is cached until the next generation.

## Snapshot and generation

`JPADiscoveryManagerImpl` keeps one snapshot of eligible posts, scores and
the selections built from them. It rebuilds on `PopularityRefreshTask`
(`tasks.enabled`; interval `site.discovery.refreshSeconds`, default 3600,
rounded up to whole minutes, as Global Config also shows it), on Maintenance
"Refresh now", and when a Global Config save or `PATCH /v1/admin/config`
changes a `site.discovery.*` value.

A page request never waits on Umami (it never calls it). After a restart the snapshot is empty
and the scheduled refresh may be an interval away (`TaskLock.lastRun`), so the
first selection builds one on the request thread from candidates only (one
local query, no scores, status unchanged) and hands a full refresh to the
`ThreadManager` background pool; the scores arrive with that. Concurrent first
requests share the one build. The first build never takes the refresh lock,
so it does not queue behind a scheduled or admin refresh that is already
waiting on Umami; whichever swaps second loses only if it is the first build
(a refresh's snapshot is newer and scored, so it always stands). A failed read of the traffic cache keeps the last scores but
still builds a new snapshot (new candidates, so new posts appear, and the
random picks reshuffle); the failure is logged and shown in Maintenance.

`DiscoveryGeneration` (util) is a process-wide, monotonic millisecond stamp
advanced on every rebuild. It is stamped right before the swap, after every
read, never at the rebuild's start: a page rendered from the old snapshot
while a slow rebuild reads is dated after the start, and a generation older
than that page would leave it cached (and 304'd) a whole extra interval. The
snapshot is swapped in before the generation is published.

It is the cache hook: `RenderCaches.lastModifiedOfPage` and the pages-side
`SiteWideRenderCache` floor a page's last-modified at the generation, so a
rendered blog page, search page or the front page expires exactly when the
selection reshuffles (and answers 304 until then). Rendered pages expire only
through `lastModified`; do not try to invalidate them any other way. Feeds,
per-weblog and site-wide, carry no picks and do not follow the generation
(`RenderCaches.lastModifiedOf`, `forFeed`). Theme stylesheets are still served
by `PageServlet` through the page caches, so they do expire with each
generation (one refetch per interval; not split out).

**Testing rule (ruling 8).** The generation is process-wide and never goes
backwards. A test asserting an exact `lastModified` / `Last-Modified` / 304
must date its fixtures after `max(now, DiscoveryGeneration.current())`, and
must never advance the generation into the future (it cannot be undone for
the rest of the JVM).

## Seeding

Random picks are seeded by the snapshot generation, so they are stable for a
generation and reshuffle only when the snapshot rebuilds. At least one random
slot per row of 3 or more when `site.discovery.sharePercent` > 0; at 0 only
gap-fill is random. The headline is the top-scoring post, else the newest
eligible post, never random. It is labelled "Most read lately"
(`discovery.headline`) only when it has views of its own
(`FrontPageSelection.headlineRanked`), else "Newest"
(`discovery.headline.newest`); both templates choose the key.

## Eligibility

`PUBLISHED`, `noindex` not true, on blogs that are visible and active and not
the site space. Never pages, drafts, scheduled, pending or trashed. A
selection is memoized as ids per snapshot and re-resolved and re-checked
each time it is rendered, but a rendered page is cached: the re-check runs
only on a cache miss. So a post unpublished between rebuilds drops out of
whatever renders next, while a page already cached (another blog's block, or
`/`) can still link it until that page is rendered again, at the latest after
the next generation. `pinnedToMain` is ignored. A blog opts out of the other
blogs' posts with `showMoreFromSite` (it stays in `/` and in other blogs'
blocks); the block then keeps only the places list and the link home, as it
does when no other blog has an eligible post, because every blog links back
to the front page.

## Popularity source

`CachedTrafficSource` asks `AnalyticsManager.entryViewsAllBlogs(windowDays)`
for views per entry from the analytics cache (filled by `AnalyticsSyncTask`;
see `audience-and-analytics.md`): one local database read, never Umami. It
replaced `UmamiTrafficSource` and the `analytics.jdbc.*` settings, so there is
no connection, credential or query timeout to configure. `isConfigured` is the
analytics status's `configured` flag: with analytics off (blank
`analytics.umami.apiUrl`) the front page is all-random, no error.

Each blog is its own Umami website (website ID = weblog ID), so blogs no
longer share an ID and cannot credit each other's posts. Scores are as fresh
as the sync (15 minutes) plus the discovery refresh (hourly by default).
Deploy notes: `docker_deployment.md`, "Front page popularity".

## The promotion seam

`JPADiscoveryManagerImpl.pickFrontPage` has a "promoted first" comment: the
next project (hand-promoted posts) fills slots there, before ranking.
Nothing is built for it yet.

## Places you've been

`localStorage["pressroom.places"]` only: a JSON array of `{handle, name,
url}`, newest first, per origin; renders nothing without storage or
JavaScript. The recording element and inline script come from
`showPlacesYouveBeen` in `weblog.vm`; the markup contract is pinned by
`MoreFromSiteRenderingTest` and `DiscoveryIT`.

**Custom-domain gap.** Storage is per origin, so a blog served on its own
domain and the main site do not share places: each origin remembers only the
blogs visited on it.

**Previews.** Preview pages deliberately carry neither the block nor the
recording (their links would be preview urls of other blogs, and an author's
preview is not a reader's visit); `DiscoveryModel` is not in
`rendering.previewModels` and both macros guard on `$discovery`.

## Browser coverage

`DiscoveryIT` (read lock on `GLOBAL_CONFIG`: `/` is the discovery page only
while no site page or blog is the front door). It was written after the
behaviour; analytics is not configured in the IT stack, so no
assertion depends on a score. `frontPageShowsSections` and
`blogPageLinksHome` do depend on the seeded `it_weblog` entry being an
eligible post (the headline link; the block's posts from other blogs).
