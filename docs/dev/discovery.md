# Front page discovery

What `/` shows when no site page or blog is the front door, the "more from
the site" block on every blog page, and "places you've been". Spec:
`docs/superpowers/specs/2026-11-01-front-page-discovery-design.md`.

## Snapshot and generation

`JPADiscoveryManagerImpl` keeps one snapshot of eligible posts, scores and
the selections built from them. It rebuilds on `PopularityRefreshTask`
(`tasks.enabled`; interval `site.discovery.refreshSeconds`, default 3600) or
Maintenance "Refresh now", and once on first use. A page request never waits
on Umami except that one first build; a failed refresh keeps the last scores.

`DiscoveryGeneration` (util) is a process-wide, monotonic millisecond stamp
advanced on every rebuild. It is the cache hook: `RenderCaches.lastModifiedOf`
and `SiteWideRenderCache` floor a page's last-modified at the generation, so a
rendered blog page, search page or the front page expires exactly when the
selection reshuffles (and answers 304 until then). Rendered pages expire only
through `lastModified`; do not try to invalidate them any other way.

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
eligible post, never random.

## Eligibility

`PUBLISHED`, `noindex` not true, on blogs that are visible and active and not
the site space. Never pages, drafts, scheduled, pending or trashed; the
selection is re-checked at render time, so an entry unpublished between
rebuilds disappears. `pinnedToMain` is ignored. A blog opts out of the block
with `showMoreFromSite` (it stays in `/` and in other blogs' blocks).

## Popularity source

Blank `analytics.jdbc.connectionURL` means all-random, no error. Otherwise
`UmamiTrafficSource` reads `analytics_traffic` read-only (readOnlyMode=always,
connect 5 s, socket 20 s, query 15 s). That view is shared with Grafana; see
`audience-and-analytics.md`. Deploy notes: `docker_deployment.md`, "Front
page popularity (Umami)".

## The promotion seam

`JPADiscoveryManagerImpl.selectFrontPage` has a "promoted first" comment: the
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
behaviour; the Umami source is not configured in the IT stack, so no
assertion depends on a score.
