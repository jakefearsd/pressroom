# Site search, and the article as a landing page

2026-11-13. Brainstormed in chat; direction approved from mockups (results
layout A for a blog, B for the front page). Jake then handed the remaining
decisions over: everything under "Calls made for Jake" was the author's
choice. **Approved 2026-11-13** ("Keep all three, all blogs are English for
now, write the plan"): the three article-page changes and English stemming
are confirmed by Jake; the other calls stand unchallenged.

## Why

A reader arrives wanting a blog worth following: something to help them
plan, build, learn, or just read. They find it through individual articles.
As the site grows past a handful of blogs, the front page's curated sections
stop being enough and search becomes the way in.

Search exists today (one embedded Lucene 10 index over every blog, a search
page per theme) but it is a 2000s blog search:

- Results are sorted newest first, grouped by day. Relevance plays no part.
- Only `title` and `content` are searched. Tags, summary, category name and
  blog name are not.
- Only the first 1000 tokens of a post are indexed
  (`lucene.analyzer.maxTokenCount`), so the back half of a long guide is
  unfindable.
- `content` is the raw stored text: markdown syntax and shortcode source are
  indexed, the text a shortcode renders is not.
- `StandardAnalyzer`: "remodel" does not find "remodeling".
- No matching passage is shown, only the usual list excerpt.
- The discovery front page (`/`) has no search box at all. In the journal
  theme the blog's box is in the footer.
- A blog's search covers that blog only. Site-wide search exists only as the
  site space's search, and it does not apply `DiscoveryEligibility` (a
  `noindex` post of any blog can surface there).

And the page a result leads to, the article, says little about the blog
around it until after the last paragraph.

## Goals

1. **Best match first**, everywhere search is shown.
2. **From a blog, that blog first, then the rest of the site** (mockup A).
3. **From the front page, one list across all blogs** (mockup B), each
   result labelled with its blog.
4. **A visible search box** on the front page and on every blog's home page.
5. **Results are articles**, and each shows the passage that matched.
6. **An article makes its blog evident to a newcomer**, whatever brought
   them there: that this is one of many, that more is being published, and
   how to follow it. No special mode for arrivals from site search, so pages
   stay cacheable.

## Non-goals

- No new search engine or service. Lucene stays embedded.
- No typeahead, autocomplete or instant results. No JavaScript is required
  to search.
- No blogs, static pages, comments or media as results. Articles only.
- No facets or filters beyond the existing category filter.
- No search endpoint in `/api/v1` or MCP in this wave (the entries list's
  `?text=` stays as it is).
- No query logging or search analytics beyond two counters (see Metrics).
- No semantic/vector search, no "did you mean".
- No cross-blog "Keep reading" (the footer's "more from the site" block and
  cross-blog Nearby already carry other blogs).

## Anti-goals

- A blog's own matches buried under another blog's. On a blog, that blog's
  section always comes first, even when another blog's post scores higher.
- A blog that opted out of "more from the site" (`showMoreFromSite` off)
  showing other blogs' results on its search page.
- A post that discovery would not show (`DiscoveryEligibility`: noindex,
  hidden or inactive blog, site space) appearing in another blog's results
  or the front page's.
- Search pages indexed by search engines, or a sticky/floating search UI.
  The admin and reader chrome stay quiet.

## Design

Two parts sharing one index. Part 1 is search; part 2 is the article page.

### 1. The index

`IndexOperation.getDocument` changes; `FieldConstants` gains the names.

| Field | Indexed as | Stored | Source |
|---|---|---|---|
| `title` | text | yes | as now |
| `content` | text | **yes** | **rendered plain text**: shortcodes expanded, markdown rendered, sanitized, tags stripped, entities decoded |
| `summary` | text | no | entry summary, plain text |
| `tags` | text | no | tag names, space-joined |
| `cattext` | text | no | category name (the existing keyword `cat` stays for the filter) |
| `blog` | text | no | weblog name and tagline |
| `site` | keyword `1` | no | present only when `DiscoveryEligibility.isEligible(entry)` accepts the post at index time (the entry's half of the rule) |
| `pubnum` | long doc value | no | publish time, epoch millis, for the recency factor (`published` stays for the future-date filter) |

- **Token limit** rises from 1000 to 20000 (`lucene.analyzer.maxTokenCount`).
- **Analyzer** becomes `EnglishAnalyzer` (stemming, possessives, stop
  words), at index and query time alike.
- **Stored `content`** is what snippets are cut from, so a results page
  never renders a post. The index for 10-50 blogs stays in the tens of MB.
- **Rebuild on upgrade.** The index format changes, so the first start on
  the new version must rebuild in the background. `LuceneIndexManager`
  already rebuilds an index it finds outdated or unreadable; the builder
  checks whether a schema marker exists and adds one (a version stamp in
  the index commit data, compared at startup) if not. **As built:** the
  rebuild is one commit, so searches and Keep reading see an empty index
  until it lands (seconds), and the marker is written only when a full
  rebuild completes, so an interrupted rebuild is retried at the next start.
- **Blog-level eligibility is decided at query time, not in the index**
  (revised while planning, 2026-11-13). Site and other-blog queries filter
  on the set of handles `DiscoveryEligibility.isEligible(weblog)` accepts
  right now (10-50 blogs, one cheap lookup), so hiding or deactivating a
  blog takes effect on the next search with no reindex.
- **Keeping `blog` true.** It holds the weblog's name and tagline, so a
  change to either schedules `rebuildWeblogIndex(weblog)`. A change to an
  entry's `noindex` already reindexes the entry. The conversion step keeps
  its re-check against the database (published, not future) and adds the
  entry eligibility re-check for other-blog hits, so a stale `site` flag
  can cost a result slot but never show a post it should not.

### 2. The query

- **Fields and weights**: `title` 4, `tags` 3, `summary` 2, `blog` 2,
  `cattext` 1.5, `content` 1. `SearchOperation.SearchedFieldsOnly` keeps
  confining reader-typed `field:` syntax to these.
- **All words, then any.** The default operator stays AND. When that finds
  nothing, the same words run as OR and the page says so ("Nothing matched
  all of those words. Showing articles that match some of them.").
- **Order**: Lucene's score times a mild recency factor from `pubnum`:
  1.25 for a post published today, falling smoothly to 1.0 at two years and
  flat after. Recency breaks near-ties toward a blog that is still
  publishing; it never lifts a passing mention over a real match. The
  constants live in one place.
- **Two scopes**, chosen by where the search was made:
  - **Site** (`/search` on the site host, which the front page uses): every
    post with `site:1`, one ranked list, 10 per page.
  - **Blog** (a blog's search URL): two queries. *This blog*: posts with the
    blog's handle (its own noindex posts included, as today), top 8.
    *Other blogs*: posts with `site:1` and a different handle, top 5; not
    run when the blog has `showMoreFromSite` off.
- **Seeing everything from one blog**: `scope=blog` on a blog's search URL
  gives that blog's matches alone, 10 per page, paged as today. A `cat`
  parameter implies it (a category belongs to one blog).
- Existing limits stay: 255-character query, 500 hits considered, a query
  Lucene cannot run is the reader's error.

### 3. The results page

One macro in `WEB-INF/velocity/weblog.vm` renders a result list, with its
style inside the macro as the after-article block does, so the three article
themes, the frontpage theme and any fork show the same thing. Each theme's
`searchresults.vm` keeps its own header and footer and calls the macro.

A result (not grouped by day):

- thumbnail, by the same rule list pages use; none if the post has none
- title, linking to the post
- snippet: the best-matching passage of stored `content`, about 160
  characters, search words in `<mark>`; when only the title or tags
  matched, the usual list excerpt
- meta line: date, then the category (own blog) or the blog's name linking
  to the blog's home (other blog, and every result on the front page)

**From a blog (A):** heading "From *Blog name* · N articles", up to 8
results, "Show all N from this blog" (to `scope=blog`) when there are more;
then heading "From other blogs on the site", up to 5 results, and "Search
the whole site" (to the site search with the same words). When the blog has
no match, the first section says so in one line and the second still shows.
When neither has any, the existing empty message.

**From the front page (B):** "N articles across M blogs", ranked, paged. A
result whose blog has further matches among the hits carries "K more from
this blog", linking to that blog's `scope=blog` search.

**Escaping (silent-failure hazard).** A snippet is plain text: HTML-escape
it first, then insert `<mark>`. A post whose text contains `<script>` or an
unclosed tag must come out inert. Titles are stored escaped and are emitted
bare. Another blog's result links its absolute permalink, never
`$url.entry`. Blog names print through `WeblogWrapper`.

Results pages carry `<meta name="robots" content="noindex">` and are not
kept in the page cache per query.

### 4. Where the search box is

- **Front page (`discovery.vm`)**: in the masthead, a single field,
  submitting to the site space's search.
- **Blog home pages** (journal, travel, portfolio): in the header area,
  above the first post, submitting to the blog's search. The home page's
  footer box goes; one box per page.
- **Post and static pages** keep the footer box every theme already has
  there (found while planning; the earlier draft wrongly said only the
  journal home had one). **The site bar** gets no box: a blog search
  already reaches the whole site.
- Placeholder text says what will happen: "Search *Blog name* and the rest
  of *Site name*" on a blog, "Search all of *Site name*" on the front page.

### 5. The article as a landing page

Today, below the article: share row, Nearby, Keep reading, comments,
older/newer, and (in the footer) the subscribe form. Above it: the site bar
and the blog's header. Three changes:

1. **A blog line under the article's title block**, one quiet line:
   "From *Blog name* · 42 articles · latest 9 Nov 2026 · Subscribe". The
   name links to the blog's home; "Subscribe" jumps to the form on the same
   page and is absent when the blog's subscribe form is off. Absolute date,
   never "3 days ago": the page is cached. The count and date move only
   when the blog publishes, which moves `weblog.lastModified`, which
   already expires the page.
2. **Keep reading picks by content, not only by tags.** Candidates come from
   a Lucene `MoreLikeThis` query on the post's `title`, `tags` and
   `content`, same blog, published, top 3. The existing `RelatedPicker`
   score (shared tags, same category) stays as the fallback when the index
   is unavailable, is rebuilding, or returns fewer than 3. Nearby still
   takes precedence and a post is not listed twice.
3. **The subscribe form moves up on post pages**: directly after Keep
   reading, before comments, with one line of context ("New articles from
   *Blog name* by email"). The footer keeps the form on every other page;
   a post page has it once.

Freshness: an index write is asynchronous, so a post page re-rendered the
instant a sibling is published can cache a Keep reading list that misses
it. The next change to the blog corrects it. Accepted.

### Metrics

Deferred (found while planning): Pressroom has no `/metrics` endpoint or
meter registry yet (SA Phase 1). When it lands: two counters, searches
served by scope (site, blog) and searches that found nothing. No query text
is recorded. Nothing is built for this now.

## Calls made for Jake

Decide-or-accept list, most consequential first:

1. **English stemming for every blog.** Right if the blogs are in English;
   a blog in another language would search worse than today.
2. **No search box in the site bar.** Home pages get one in the header;
   post pages keep their existing footer box.
3. **Section sizes**: 8 from this blog, 5 from other blogs, 10 per page.
4. **Recency factor** up to 1.25 over two years.
5. **AND, then OR** when AND finds nothing.
6. **Article page**: the blog line, content-based Keep reading, and moving
   the subscribe form up. None of this was discussed beyond "make related
   content and continuous publishing clear".
7. ~~Reusing the site space's search URL for site search rather than a
   new `/search` route.~~ **Revised during the build (2026-11-13):** the
   site space has no search address (`/search` is a reserved word the
   request mapper leaves unhandled, and the site space answers only asset
   paths). Site-wide search now lives at `/search` on the site host,
   forwarded to the site space and rendered by a shared `site-search`
   template, as `/` is for discovery. `/search` on a custom domain stays
   that blog's search.
8. **No API/MCP search endpoint** in this wave.

## Acceptance criteria

Each is a test.

Index and query
- A word that occurs only after the 1000th token of a post finds it.
- A word that occurs only in a tag, the summary, the category name or the
  blog's name finds the post.
- Text produced by a shortcode is found; shortcode source and markdown
  punctuation are not indexed as words.
- "remodel" finds a post that only says "remodeling".
- Of two same-age posts, the one with the words in its title ranks above
  the one with them once in the body. An old post with the words in its
  title ranks above a new post with one passing mention.
- A two-word query that no post satisfies in full returns posts matching
  either word, and the model reports the fallback.
- `username:x`, `site:1` and `blog:x` typed by a reader are read as plain
  words (extends the `SearchedFieldsOnly` tests).
- An index built by the previous version is rebuilt at startup.

Scope
- Blog search returns that blog's matches first, at most 8, and reports the
  true total; `scope=blog` pages through all of them.
- Other-blog results never include: a noindex post, a post of a hidden or
  inactive blog, a site-space post, a future-dated post, a draft.
- A blog with `showMoreFromSite` off shows no other-blog section.
- Site search shows no ineligible post, and each result names its blog.
- Renaming a blog makes it findable under the new name; hiding a blog
  removes its posts from other blogs' and the front page's results.

Page
- Results render in score order in all three article themes and the
  frontpage theme, through the one macro (a rendering test per theme, as
  `AfterEntryRenderingTest` does).
- A post containing `<script>alert(1)</script>` near the matched words
  yields a snippet with the tag escaped and the words marked.
- Results pages carry robots noindex.
- The front page and each theme's blog home render a search form whose
  action is the right search URL, at `/` and under a context path
  (browser IT at both).
- An other-blog result's link is the post's absolute permalink.

Article
- A post page shows the blog line with the published-post count and the
  newest post's date; publishing another post changes both on the next
  render.
- With the subscribe form off, the line has no Subscribe link and the page
  has no form; with it on, the page has exactly one form.
- On an untagged blog, Keep reading for a post about one subject lists the
  blog's other posts on that subject ahead of unrelated newer ones. With
  the index empty, it lists what `RelatedPicker` picks today.

## Build order

1. Index and query (fields, analyzer, token limit, ranking, rebuild marker).
   Search already works better through the existing pages.
2. Scopes and the results macro; theme `searchresults.vm` files.
3. Search boxes on the front page and blog homes.
4. The article page.

Each step ships on its own. Steps 1-3 are "search"; step 4 could be a
separate release.

## Left to the builder

- How rendered plain text is produced for indexing (the content pipeline
  must not be re-implemented; reuse shortcodes, commonmark, `HTMLSanitizer`).
- Which Lucene highlighter cuts the snippet (`lucene-highlighter` is a new
  module dependency if not already present; pin it with the Lucene version).
- The recency function's exact shape, within "1.25 today, 1.0 at two years".
- Where the weblog-change hook for reindexing lives.
- Markup and class names of the results macro and the blog line, within
  `docs/design/design-system.md`; new reader strings go through the i18n
  ratchets.
- Docs: `docs/dev/architecture.md` ("Search Implementation"),
  `docs/dev/themes-and-templates.md` ("After the article"),
  `docs/dev/discovery.md` (eligibility now also gates search).
