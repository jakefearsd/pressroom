# Blog ordering, strands, and first-class pages

Date: 2026-11-15. Size: a project, shipped as three releases (ordering and
pins; strands; pages made first class). One design, so the three agree.

## Why

Every blog lists newest first. That is the right default, but it is the only
option, and it fails one use case Jake has now: a tutorial sequence, whose
first posts are what readers should meet first for the long term. Behind that
sits a wider aim: blogs that can **frame a complex topic** with continuous
publishing and discussion. That needs posts that can be read in a deliberate
order, and pages that describe what a blog is for and where to start.

Blogs stay the beating heart of Pressroom. This is not a general CMS.

## Goals

- **Simple ordering** for blogs that only need it: newest first (default) or
  oldest first, per blog, plus pinned posts.
- **Strands** as a first-class concept for advanced use: a named, ordered
  sequence of posts inside a blog, with its own address and listing, and
  "Part N of M" navigation on each post in it. Built for topics that keep
  growing: appending new parts is the normal case.
- **Pages** as a first-class concept: any number per blog (most have zero or
  one), able to serve as a blog's front door, ordered in its nav, and found
  by search.
- **Agent parity**: every capability here is available through `/api/v1` and
  the MCP endpoint, not only the admin UI.

## Non-goals

- Arbitrary content types or a general CMS. Pressroom has posts, pages, and
  strands (which are pages; see below).
- A post in more than one strand (zero or one; Jake, 2026-11-15: a two-way
  door, revisit only if it bites).
- Manual (hand-arranged) order for a whole blog. Hand-arranged order lives in
  strands.
- Strands spanning blogs.
- A comment thread on a strand itself. Discussion stays on posts.
- Other blog orders (recently updated, alphabetical, popular). Considered and
  not chosen.
- Placing a strand's parts list mid-page with a shortcode. A natural later
  step ("content stays in content"), not in this design.

## Anti-goals (would be failure even if it works)

- Any change to existing blogs' behaviour. Every new column defaults to
  today's behaviour; no backfill.
- A reader landing on a 404 or a broken link because of an author's setting
  (a draft strand page, a deleted front-door page). Fall back, never break.
- Two near-identical "landing page" concepts that drift apart (why strands
  are pages, below).

## Approaches considered

**Strand as its own entity (not chosen).** A `weblog_strand` table (title,
slug, introduction, blog), its own route `/<handle>/strand/<slug>`, admin
screen, template, cache key, sitemap entry, search document, API resource.
Clearest separation, but every capability pages already have (address,
template, draft/published, nav, sitemap, front door, search) gets built a
second time, and a strand's introduction duplicates a framing page's.

**Strand as a page with parts (chosen, Jake 2026-11-15).** Any page can be
marked a strand and then carries an ordered list of posts. The page's text is
the framing; the parts list renders beneath it. Everything pages get, strands
get. The database delta is a flag on the page and two columns on the post;
the content itself stays in content. Jake: "keeping content elements in
content, vs. shoving them into the database."

**Category as strand (dropped).** Categories already order and route, but a
post's category is its topic; using it for a sequence collides with that.

## How it works today (what this touches)

- Listings: `WeblogEntrySearchCriteria` has `SortBy`/`SortOrder`
  (`ASCENDING`/`DESCENDING`) already; `JPAWeblogEntryManagerImpl` orders by
  `e.pubTime` and has fixed `pubTime DESC` queries elsewhere (`NEWEST_FIRST`).
  `WeblogEntriesLatestPager` groups entries by day in a
  `TreeMap(Collections.reverseOrder())`; themes print day headings.
- Previous/next on a post: `WeblogEntriesPermalinkPager.getNextEntry` /
  `getPreviousEntry` -> `getNextPrevEntries` (publish time, same blog,
  published).
- Pins: `weblogentry.pinnedtomain` is Roller's **site-wide** pin, editable
  only by a global admin (`EntryEdit.jsp`), read only by the `frontpage`
  theme via `SiteModel.getPinnedWeblogEntries`. Not per blog.
- Pages: `WeblogPage` / `roller_weblogpage` (V014), per blog, any number,
  served at `/<handle>/<slug>`, with `show_in_nav` and `nav_order` columns
  already. `ReservedSlugs` guards the slug namespace. Site pages live in the
  hidden `site-space` blog. The site front door (`FrontDoor`) can be a page;
  a blog cannot choose one.
- Search: indexes posts only (`IndexOperation.getDocument(WeblogEntry)`).
- API/MCP: `McpToolCatalog` maps MCP tool actions onto `/v1` routes; tools
  `weblogs`, `entries`, `pages` exist. The MCP instructions text is near its
  budget (about 2,300 of 3,000 characters): new descriptions must be terse.
- Render cache: rendered pages expire only through `weblog.lastModified`.
  Every write below that changes what a reader sees must bump it.

## Release 1: ordering and pins

### Schema (one migration)

```
weblog       + entry_order  varchar(10) NOT NULL DEFAULT 'NEWEST'  -- NEWEST | OLDEST
weblogentry  + pinned       boolean     NOT NULL DEFAULT false
```

`pinnedtomain` is left exactly as it is (it is the site-wide pin; a
retirement candidate, not this work).

### Blog order

- Setting on the blog's settings page (`WeblogConfig.jsp`): "Order posts:
  Newest first / Oldest first".
- Applies to every listing of that blog: home, category, tag, and date
  archives. Oldest first groups days ascending; day headings stay.
- Previous/next on a post (outside a strand) are **unchanged** in either
  order: themes show "Older" on the left (`rel=prev`) and "Newer" on the
  right (`rel=next`), labelled by time, so they stay true. In an
  oldest-first blog, the right-hand "Newer" link is already the reading
  direction. (Builder check, 2026-11-15: an earlier draft flipped the slots,
  which would have changed every existing blog.)
- **Not** affected: feeds (always newest first; that is what a feed is for),
  the site front page and site-wide lists, site search, sitemaps.

### Pins

- A post has a "Pin to the top of this blog" setting, available to anyone
  who may publish in that blog.
- At most **3** pinned posts per blog (my call); pinning a fourth is refused
  with a field error naming the limit, never silently unpins another. The
  limit counts every pinned post that is not trashed, drafts and scheduled
  included, so publishing can never push a blog past 3 (my call); the admin
  entries list shows pinned drafts, so the author can see what holds a pin.
- With a front door (release 3), "the home listing" is `/<handle>/posts`.
- Pinned **published** posts lead page 1 of the blog's home listing, in the
  blog's order among themselves, marked "Pinned". They are excluded from the
  rest of the home listing (all pages), so they never appear twice. They are
  extra to the page size. A pin has no effect on category, tag, date
  listings, or feeds, where the post appears in its normal place.
- A draft, scheduled or trashed pinned post shows nowhere until published;
  its flag is kept. Restoring a trashed pinned post while 3 others are
  pinned clears its pin, so a restore can't push past 3 either (plan's call).
- Admin entries list shows a pin marker.

### API and MCP

- Weblog resource gains `entryOrder` (`NEWEST` | `OLDEST`) on get and update.
- Entry resource gains `pinned` (boolean) on get, create, update; the limit
  is a 400 field error (the API's convention for field errors).
- MCP `weblogs.update` and `entries.create`/`update` accept these through
  the existing actions; no new tool.

### Acceptance criteria (release 1)

1. A blog with no setting lists newest first; every existing blog renders
   the same after migration.
2. Set to oldest first, home, category, tag and date listings run oldest
   first; the feed still runs newest first.
3. A post's Older/Newer links are the same in either order (existing
   permalink tests keep passing unchanged).
4. A pinned published post appears first on page 1 of the home listing and
   nowhere else in the home listing; it appears in its normal place in its
   category listing and in the feed.
5. A fourth pin is refused (UI field error; API 400) and the other three
   stay pinned.
6. A pinned draft is not shown; publishing it shows it pinned.
7. Changing the order or a pin changes the rendered page immediately (the
   change bumps `weblog.lastModified`).
8. API: `entryOrder` and `pinned` round-trip; MCP `weblogs.update` and
   `entries.update` set them.

## Release 2: strands

### Schema (one migration)

```
roller_weblogpage + is_strand       boolean NOT NULL DEFAULT false
weblogentry       + strand_page_id  varchar(48) NULL
                      REFERENCES roller_weblogpage(id) ON DELETE SET NULL
                  + strand_position integer NULL
                  index (strand_page_id, strand_position)
```

The position lives on the post, so "zero or one strand" holds by
construction. Moving to several strands later would be a join table: a
contained migration.

### What a strand is

- A page with "This page is a strand" ticked. Its text frames the topic; its
  parts are an ordered list of posts from the **same blog**.
- A post is in zero or one strands.
- Adding a post appends it at the end: position = max + 1 over **all**
  members of any status, trashed included. Parts can be reordered by drag
  (with up/down buttons for keyboard use). Positions need not be contiguous;
  numbering is computed. Moving a post to another strand does not renumber
  the old one. Trashing keeps a post's strand and position (my calls).
- **Who**: changing membership or order, from either the page or the entry
  side, needs `WeblogPermission.POST` on the blog: the level the page
  editor (`PagesController`/`PageEditController`) and publishing already
  require. An author with only `EDIT_DRAFT` cannot place a post in a strand.
- The parts list shows **all** published parts, no paging.
- Strand posts are ordinary posts: they still appear in the blog's listings
  and feed in the blog's order. Listings are unchanged in this release.

### Reader view

- **Strand page** (`/<handle>/<slug>`, as any page): the page body, then the
  parts list: numbered, title linked, with each part's summary (or opening
  sentences, as lists do today).
- **Post in a strand**: near the top, "Part N of M in *Strand title*"
  linking to the strand page. At the foot, previous/next **part** (by title)
  replace the blog's previous/next. The first part has no previous; the last
  part's next slot links back to the strand page.
- **Counting**: only PUBLISHED posts are parts to a reader. N is the post's
  rank among published parts by position; M is the count of published
  parts. A draft or scheduled part is invisible until published; a trashed
  part drops out but keeps its strand and position, so restoring returns it
  to its place.
- **Strand page not published** (draft): its posts render with no strand
  bar and the blog's normal previous/next. Never a link to a 404.
- Every theme with a page template renders the parts list and the strand
  bar, through shared macros (journal, portfolio, travel; frontpage if it
  renders posts).

### Authoring

- **Page editor**: a "This page is a strand" checkbox and, when ticked, a
  Parts panel: the ordered parts (drafts shown with their status), reorder,
  remove from strand, and "add posts" (a picker of this blog's posts that are
  in no strand).
- **Entry editor**: a "Strand" select: none, or one of this blog's strand
  pages (draft strand pages included). Choosing one appends the post at the
  end; changing it appends to the new strand; none removes it.
- **Unticking** "is a strand" while it has parts (any status, trashed
  included) is refused (fail closed), with a message naming the count.
- **Deleting** a strand page detaches its parts (posts stay, strand cleared)
  after a `data-confirm` that names how many posts leave the strand. The FK's
  `ON DELETE SET NULL` is the backstop, not the mechanism.
- Every strand change bumps `weblog.lastModified` (it changes every part's
  "of M" and navigation).

### API and MCP

- Page resource gains `strand` (boolean) and, read-only, `parts`: ordered
  `{id, anchor, title, status, position}`.
- New action: `PUT /v1/weblogs/{handle}/pages/{id}/parts` with
  `{"entries": [id, ...]}` replaces the strand's whole ordered membership
  atomically (add, remove, reorder in one call). An id from another blog,
  unknown, or already in a different strand is a 400 field error (the API's convention for field errors) naming it;
  nothing is changed. (Moving a post between strands is done from the entry
  side, explicitly.)
- Entry resource gains `strand` (page id or `null`) on get, create, update:
  setting it appends; `null` removes. A non-strand or other-blog page is a
  400 field error.
- MCP: the `pages` tool gains a `set_parts` action; `entries` accept
  `strand`. Descriptions kept within the instructions budget.
- Ownership: every page and entry id goes through the existing by-id
  ownership lookups (`WeblogOwnership` / `BaseController.lookup*`); the API
  answers 404 for anything the caller may not see.

### Acceptance criteria (release 2)

9. Ticking "is a strand" on a page and adding three posts shows them on the
   page, in order, numbered 1-3, beneath the page body.
10. Reordering (UI drag, UI buttons, API `set_parts`) changes the order on
    the strand page and every part's "Part N of M" and navigation, at once.
11. A post in a strand shows "Part N of M" linking to the strand page, and
    previous/next parts; the last part's next links to the strand page.
12. A draft part is not counted (M excludes it) and not linked; publishing
    it appends its number.
13. A trashed part drops out; restoring returns it to its old position.
14. With the strand page in draft, its posts show no strand bar and use the
    blog's previous/next.
15. Assigning a post to a strand from the entry editor or API appends it;
    a post can be in only one strand (assigning to another moves it).
16. `set_parts` with a post from another blog, or one in another strand, is
    a 400 and changes nothing.
17. Unticking a strand with parts is refused; deleting it detaches its
    parts, which remain published posts.
18. MCP `pages.set_parts` and `entries.update {strand}` work end to end.

## Release 3: pages made first class

### Schema (one migration)

```
weblog + front_page_id varchar(48) NULL
           REFERENCES roller_weblogpage(id) ON DELETE SET NULL
```

`nav_order` already exists.

### Front door

- Blog setting: "Front door: Post list (default) / *a published page*".
- With a front door, `/<handle>/` renders that page (a strand page shows its
  parts: a tutorial blog's "start here"), and the post listing moves to
  `/<handle>/posts`, with its paging. Category, tag, date and post URLs are
  unchanged. The blog nav gains a "Posts" link.
- "Blog root" means wherever the blog's root is served: `/<handle>/`, or a
  virtual host's root for a blog mapped to its own host (`routing.md`,
  "Virtual hosts"). The **site** root `/` stays governed by the site front
  door (`FrontDoor`), unchanged. The builder enumerates every URL builder
  that means "this blog's post listing" (home pager links, the nav's
  "Posts" link, the listing's canonical) and points them at `/posts` when a
  front door is set; feed and post URLs do not move.
- `posts` joins `ReservedSlugs`. Before deploying release 3, check
  production for a page slugged `posts` in any blog; if one exists, rename
  it by hand and add a rule in the existing redirects table (redirects
  UI/API) for its old path. No migration branch for this (my call: a
  one-off operational step, not code).
- The page's own URL (`/<handle>/<slug>`) keeps working; its canonical link
  points to the blog root while it is the front door (my call: no redirect,
  so links survive a change of front door).
- If the front-door page becomes a draft or is deleted, the blog root falls
  back to the post list. Never a 404 at a blog root.
- Feeds, site front page, and site-wide lists are unaffected.

### Nav order

- Pages are ordered in the blog's nav by `nav_order`; the Pages screen lets
  the author reorder them (drag plus up/down). The API exposes `navOrder` on
  pages (builder: check what `PageDtos` and `Pages.jsp` already expose and
  finish only what is missing).

### Pages in search

- Published pages that are not `noindex` are indexed (blog pages and site
  pages), as their own document kind. The index schema version bumps, so
  the existing rebuild-on-schema-change path reindexes.
- Scope: a blog's search shows that blog's pages; the site-wide search
  (`/search`) shows blog pages and site pages. Site pages appear only in
  the site-wide search.
- Results: matching pages appear as a small "Pages" group (at most 3) above
  the post results, not mixed into the post ranking. Pages are few (most
  blogs have zero or one), so a separate group keeps them findable without
  disturbing post relevance. Tuning how they rank against posts belongs to
  the paused search-relevance work (judged query set), not here.

### API and MCP

- Weblog resource gains `frontPage` (page id or `null`) on get and update;
  a draft or other-blog page is a 400 field error (the API's convention for field errors).
- Pages: `navOrder` as above. MCP through existing actions.

### Acceptance criteria (release 3)

19. With no front door, every blog behaves as before.
20. With a front door, `/<handle>/` renders the page and `/<handle>/posts`
    lists posts with paging; category, tag, date and post URLs unchanged;
    the nav shows "Posts".
21. Making the front-door page a draft, or deleting it, returns the root to
    the post list.
22. A page slugged `posts` is refused at save (`ReservedSlugs`).
23. Reordering pages changes the nav order on the blog.
24. A blog search for words on a published page shows it in the "Pages"
    group; a `noindex` or draft page is not shown; post results are
    unchanged.
25. API/MCP set and read `frontPage` and `navOrder`.
26. Routing changes pass the browser suite at both `/` and
    `-Dit.context.path=pressroom`.

## Docs

Each release updates the matching `docs/dev/` files: `entries.md` (pins,
strands, pages), `routing.md` (front door, `/posts`), `themes-and-templates.md`
(macros), `api.md` and `docs/api/README.md` (new fields and `set_parts`),
`discovery.md`/`architecture.md` search sections (release 3).

## Left to the builder

- Exact column names and the macro names, within the shapes above.
- How the parts picker and drag reorder are built in the admin UI, within
  the admin design system.
- Query shape for "pins first, rest excluded" and for strand neighbours.
- Whether JSON-LD gains `isPartOf` for strand posts.
