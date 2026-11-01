# Front page discovery: popular first, random always

Status: design, awaiting approval (2026-11-01)

## Intent

Several blogs now live on one Pressroom site (blog.wikantik.com). Today `/`
is a site directory, a static list of blogs. The owner wants the root to work
as a shop window: show readers the best recent content from across every
blog, get them to commit to one blog and read deeper, and keep giving newer
or quieter content a real chance to be seen.

Two forces drive every slot on the page:

1. **Popularity**: what readers have actually been reading over the last X
   days, according to Umami. Popularity fades on purpose, so the page stays
   fresh.
2. **Discovery**: random picks, always present (about 20% of slots by default,
   configurable), favouring less-read content. They also fill every slot
   that popularity cannot fill.

Traffic today is near zero. This is built for the traffic the site expects to
have. The day-one page runs almost entirely on random picks and must still
look complete and intentional.

The same picker drives a second surface: a block on every blog's pages that
points readers at the *other* blogs and back to the front page, so a reader
who finishes a post keeps discovering instead of leaving.

## Goals

- `/` shows a hybrid layout (agreed against mockups): a headline post, a
  "popular right now" row, "explore the blogs" cards, a "something different"
  row, and "places you've been".
- Ranking is by Umami views over a rolling window. Ranked content gives way
  to random picks wherever data is missing.
- A configurable share of slots is always random, in the post rows and in the
  blog cards alike.
- Every blog shows a "more from Wikantik" block: popular posts from other
  blogs (with the same random share) plus a link home. A per-blog setting
  turns it off. It is on by default.
- "Places you've been": the blogs this reader has visited, kept only in their
  browser.
- The front page and the blog block use **one** backend picker and two
  different display components.

## Non-goals (this version)

- **A curated site-wide topic list.** "Places you've been" is per blog; a
  shared taxonomy across blogs and posts may come later if the shelf proves
  it is wanted.
- **Letting a blog leave the front page.** The per-blog setting only hides
  the block on that blog's own pages; an opted-out blog still appears on the
  front page and in other blogs' blocks. A separate "exclude from front page"
  setting is possible later, not now.
- **Cross-device history for signed-in readers.**
- **A view counter inside Pressroom.** The hit-count subsystem was removed
  deliberately (docs/dev/audience-and-analytics.md); Umami is the source.
- **Promotion.** The owner's next project is a global mechanism for
  promoting blogs and posts. This version ignores the existing per-entry
  `pinnedToMain` flag, which is Roller's old front-page pin and is not
  enough for that job. The checkbox is left as it is; the next project
  decides whether its storage is reused. The picker's slot-filling should
  have an obvious place where a "promoted first" step can slot in ahead of
  ranking later. Don't build that step now.

## Anti-goals (failure even if it "works")

- An empty, broken or error state on `/` because Umami is down, slow or has
  no data. Random picks always cover.
- A page that looks thin or unfinished on a quiet day.
- The same handful of posts monopolising every surface forever. The
  discovery share must be visible in practice, not just in config.
- Cached blog pages that show a stale or broken "more from Wikantik" block
  for days because the blog itself hasn't changed (see Constraints:
  caching).
- A server-side record of what an individual reader visited.

## The picker (shared backend)

One component answers "give me N posts / N blogs for this surface", with an
optional "exclude this blog" for the blog-side block.

**Popularity data.**
- Source: Umami. Pressroom reads it on a schedule and keeps a small local
  snapshot. Page requests never call Umami directly.
- *My call:* read the existing `analytics_traffic` view in the Umami database
  (`deploy/analytics/umami-views.sql`: `website_id`, `path`, `entry_anchor`,
  `day`, `views`, `sessions`) over a read-only connection. That view is
  already the versioned contract with Umami's schema, and the comment there
  says replacing Umami means rewriting only that file. The Umami REST API is
  the alternative if a second DB connection proves awkward. The builder may
  choose it, but must then keep Umami's schema knowledge out of Java.
- Mapping: `website_id` → weblog through `Weblog.analyticsSiteId`;
  `entry_anchor` → entry within that weblog. Rows that map to nothing
  (deleted, unpublished, or non-entry paths) are ignored.
- Post score = views over the last **X days** (rolling window). Blog score =
  the sum of its posts' scores in the same window (*my call*; sessions are
  available if views prove noisy).
- If the refresh fails, keep the last good snapshot. If there has never been
  one, everything is random. Log the failure, and never surface it to
  readers.

**Eligibility.** Published entries of active, public blogs only. Never site
pages, drafts, scheduled posts, trashed entries, or entries marked `noindex`
(*my call*: `SeoController` already keeps those out of the sitemap, and
promoting them with crawlable links would work against the author's
choice). The candidate set is all
eligible content, so a blog with zero traffic is still a candidate.

**Filling a surface of N slots.**
1. `round(N × discoveryShare)` slots are random. When the share is above 0,
   a row of 3 or more always gets at least 1 random slot (*my call*, so 20%
   of a 4-slot row is one slot, not zero). At 0% the floor does not apply.
2. The rest are filled in rank order.
3. Any ranked slot that cannot be filled (not enough posts with traffic) is
   filled randomly too.
4. No item appears twice on one page (front page: across all sections; blog
   block: also excluding the current entry).
5. Random picks lean toward less-read content (*my call*: weight inversely to
   score, plus something like a 2× boost for content published in the last
   30 days). Pure uniform random is acceptable if weighting complicates
   testing; the builder decides, noting it here.

**Reshuffle cadence** (owner's decision). Random picks stay the same for one
snapshot period and reshuffle when the snapshot refreshes (default hourly),
not on every request. This keeps pages cacheable at current throughput
(~2.8k req/s) and stops a reload from shuffling the page under a reader.

**Headline post** (*my call*). Always the top-ranked post, never random.
With no traffic data it is the most recently published eligible post.

## Settings

Site-wide (Global Config, `runtimeConfigDefs.xml`), with defaults that are
*my call*:

| Setting | Default |
|---|---|
| Popularity window (days) | 30 |
| Discovery share (% of slots that are random) | 20 |
| Snapshot refresh interval | 60 minutes: **stored in seconds** (3600), edited in **whole minutes** in the UI (owner's decision) |

Per blog (blog settings page; a new `weblog` column via a new
`V0NN__*.sql` migration, default on):

| Setting | Default |
|---|---|
| Show "more from Wikantik" on this blog | on |

## Front page `/`

Layout from the approved mockup
(`.superpowers/brainstorm/*/content/front-page-hybrid-v2.html`), top to
bottom:

1. **Headline post**: blog name, title, link.
2. **Popular right now**: 4 posts, each labelled with its blog.
3. **Explore the blogs**: 3 blog cards in blog-score order, each listing a
   few of that blog's own top posts (themselves picked with the same
   rank-then-random rule, scoped to the blog).
4. **Something different**: 3 posts, all random.
5. **Places you've been**: see below.
6. **All blogs** (*my call*): a plain list of every blog, so the site
   directory's job is not lost.

Section sizes are placeholders. The builder may tune them, but they must be
fixed numbers, not settings.

Readers are not told which slots are random. Only the "Something different"
section is labelled as such.

**Where it plugs in** (*my call*). `FrontDoor` already decides what `/`
shows. The new page replaces the site directory everywhere `FrontDoor` falls back
to it: the **default** (choice 3), and also when a configured front-page site
page has been deleted or unpublished (*my call*). An explicitly configured,
published front-page site page or front-page weblog still wins, exactly as
today. A site with no blogs shows today's empty-directory
behaviour. The `frontpage` theme (the `fd-*` front-door styles) is the natural
home for the display; the builder decides between a Velocity template there
and a JSP.

## "More from Wikantik" on blog pages

- Appears on entry pages and on the blog's home/list pages, in the footer
  area just above the colophon (*my call*, revised at planning: emitting it
  from the shared colophon macro is what lets forked themes inherit it).
- Contents: 3 posts from blogs other than the current one, from the same
  picker with the same discovery share, plus a link to `/`.
- Its display component is distinct from the front page's, styled to sit
  quietly inside each theme (`journal`, `travel`, `portfolio`).
- *My call:* it is emitted from the colophon macro the shared themes already
  call, not only from the bundled template files, so a
  blog with a forked (database-stored) theme still gets it as long as its
  templates call that macro. A forked theme that has dropped the macro gets
  no block; that is accepted. A blog whose
  setting is off renders nothing: no empty box.
- Never names or links the current blog's own posts.

## Places you've been

- Kept in the reader's browser (`localStorage`) only. Nothing is sent to the
  server and nothing is added to the privacy policy.
- Records a blog when the reader views any of its pages. It shows the most
  recent 5 blogs, newest first, as links (*my call*).
- Shown on the front page and inside the "more from Wikantik" block.
- Per origin (*my call*): browser storage is per host, so a blog served on its
  own custom domain (`weblog.custom_domain`) keeps its own list, and visits
  there do not appear on the site host's front page. This gap is accepted;
  no cross-origin mechanism is built.
- Hidden entirely when empty, when storage is unavailable, or when JavaScript
  is off. The page must not depend on it.
- Delivered as a small inline script, which the themes' pinned CSPs already
  allow (`script-src 'self' 'unsafe-inline'`), so no policy changes
  (`ThemeCspCoverageTest`). It writes the DOM with `textContent` only.

## Constraints

- **Caching.** Rendered blog pages expire only through
  `weblog.lastModified` (`WeblogPageCache`, CLAUDE.md hazards). A
  server-rendered block inside a cached blog page would otherwise stay as it
  was until that blog next changes. The block must refresh at least once per
  snapshot period. The builder chooses how: for example, folding the
  picker's snapshot generation into the page cache's freshness check, or
  fetching the block client-side from a same-origin endpoint. *My lean:*
  server-rendered links, because crawlable internal links across blogs are an
  SEO asset and a client-side fetch gives that up. The choice and its reason
  go in the plan. The front page has the same constraint.
- **Context path.** No code may assume `/`; links home and between blogs come
  from the URL strategy, and the browser suite runs at both context paths.
- **Escaping.** Entry titles are stored escaped (emit bare); blog names and
  any page titles go through the usual escaping. Velocity is lenient, so
  every new template reference needs a test that renders it.
- **Umami credentials.** The Umami connection is configured by three new
  properties (`analytics.jdbc.connectionURL/username/password`, set in
  production via `PRESSROOM_ANALYTICS_JDBC_*`). The app's own DB user can
  read Umami's database, which is on the same server, and the connection is
  opened read-only. `grafana_ro` is an alternative once its LOGIN is
  enabled. Deploy note in `docker_deployment.md`.
- **Umami is optional.** A dev or test install with no Umami database must
  run with an all-random picker and no errors at startup.
- **Quality gates** as usual (coverage floors, PMD/SpotBugs, `-Xlint`).

## Acceptance criteria

Each of these must be checkable by a test.

1. With no popularity snapshot, `/` renders every section full (subject to
   enough eligible content) with no error, and the headline is the newest
   eligible post.
2. With a snapshot, the headline is the top-scoring post. Ranked slots in
   "popular right now" follow score order. Blog cards follow blog score.
3. With discovery share 20% and 4 slots, exactly 1 slot in "popular right
   now" is a non-ranked pick. With 0%, none is, except gap-fill. With 100%,
   all are.
4. When fewer posts have traffic than ranked slots, the remainder are random
   and the section is still full.
5. No post appears twice on one front page. The blog block never shows the
   current blog's posts.
6. Views older than the window do not count: a post whose only views fall
   outside the window scores 0.
7. An Umami read failure keeps the previous snapshot. Readers see no change
   and an error is logged.
8. Drafts, scheduled, trashed, `noindex` and site-page content never
   appear.
9. A blog with the setting off renders no block. Its posts still appear on
   `/` and in other blogs' blocks.
10. A cached blog page shows a block no older than one snapshot period.
11. "Places you've been" lists visited blogs most-recent-first, capped at 5,
    and the page renders correctly with storage blocked.
12. An explicitly configured, published front-page site page or weblog still
    overrides the new page. A configured site page that is deleted or
    unpublished falls back to the new page.
13. The new settings appear in Global Config and on the blog settings page
    with the stated defaults, and invalid values (negative window, share
    outside 0–100) are rejected.

## Left to the builder

- Snapshot storage (in memory vs a table) and scheduler mechanics.
- The Umami view vs REST API, within the constraint above.
- The exact random weighting, and the cache-freshness mechanism.
- Velocity vs JSP for the front page; visual design within the Quiet
  Instrument / `fd-*` language.
- Exact section sizes.

## Calls made without the owner (check these)

- Headline post is always ranked, never random; newest post when no data.
- Blog score = sum of its posts' views in the window.
- Random picks lean toward less-read and recent content.
- At least one random slot per row of 3+ whenever the share is above 0.
- `noindex` entries are never eligible.
- Defaults: 30-day window, 20% share (the hourly refresh is the owner's).
- The new page also replaces the directory when a configured front page
  breaks; an "All blogs" list sits at the bottom of `/`.
- "Places you've been": last 5 blogs, per origin (custom domains keep their
  own list).
- The blog block is emitted from a shared theme macro, so forked themes get
  it if they call that macro.
