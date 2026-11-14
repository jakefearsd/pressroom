# Site Search Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make search rank by relevance over richer fields, scope it "this blog first, then the site" from a blog and site-wide from the front page, put a search box at the top of the front page and blog homes, and make a post page show the blog around it.

**Architecture:** The existing embedded Lucene index gets a second schema (rendered plain text, tags, summary, category, blog name, an eligibility flag, a numeric publish time) and an English analyzer; a schema marker file forces one background rebuild on upgrade. `IndexManager.search` is replaced by `find(SearchSpec, URLStrategy)`, which runs a weighted, recency-adjusted query in one of three scopes and returns hits with ready-made escaped snippets. `SearchResultsModel` composes one or two `find` calls into what a single Velocity macro renders in every theme. The same index answers `moreLikeThis` for the post page's Keep reading.

**Tech Stack:** Java 25, Spring Boot 4.1, Lucene 10.5.2 (adds `lucene-highlighter`, `lucene-queries`), Velocity, JUnit 5 + Testcontainers PostgreSQL 18, Selenium ITs.

**Spec:** `docs/superpowers/specs/2026-11-13-site-search-design.md` — read it first. Its "Acceptance criteria" are the tests below; where this plan and the spec differ, the spec wins and the difference is a plan bug to report.

## Global Constraints

- Field weights: `title` 4, `tags` 3, `summary` 2, `blog` 2, `cattext` 1.5, `content` 1.
- Token limit `lucene.analyzer.maxTokenCount` = **20000**; analyzer `org.apache.lucene.analysis.en.EnglishAnalyzer` at index and query time.
- Recency factor: **1.25** for a post published now, linear down to **1.0** at **730** days, 1.0 after.
- Sizes: this blog **8**, other blogs **5**, paged views **10** per page, **500** hits considered, query max **255** chars, snippet about **160** chars.
- Default operator AND; when AND finds nothing, the same words as OR, and the page says so.
- Other-blog and site results: entry has `site:1` **and** its handle is in the set `DiscoveryEligibility.isEligible(weblog)` accepts at query time **and** the entry passes `DiscoveryEligibility.isEligible(entry)` again at conversion. A blog's own section includes its own noindex posts.
- A blog with `showMoreFromSite` off never runs the other-blogs query.
- A snippet is plain text, HTML-escaped first, then `<mark>` inserted. Titles are stored escaped and emitted bare. Other-blog links are absolute permalinks. Blog names print through `WeblogWrapper`.
- Results pages stay `noindex` (already true via `$model.searchResults`); nothing is added to the page cache per query.
- One new route (`/search` on the site host, Task 5b; added during the build), no schema migration, no `/api/v1` or MCP change, no metrics (deferred in the spec), no JavaScript required to search.
- Dates in rendered, cached pages are absolute, never relative.
- Repo rules (CLAUDE.md): TDD, watch each test fail for the expected reason; `@Lazy Weblogger` in constructors; SLF4J throwable last; i18n ratchets stay empty (every new reader string gets a key in every bundle); no context-path assumptions; no `#` or `$` inside `<style>`/`<script>` in a macro; never write a zero-argument macro's `#name` bare in a comment; deleting a Java member a `.vm` can reach means grepping `app/src/main/webapp/themes` and `WEB-INF/velocity`; one `mvn -pl app test` at a time per worktree (busy check in CLAUDE.md); a decision-path `catch` fails closed. **Commit your own task; never push.**
- Test command shape: `mvn -pl app test -Dtest=ClassName` (needs Docker).

## Review Focus

1. **A query that analyzes to nothing** (`the`, `!!!`, `"`, a lone `*`): the page shows the ordinary "nothing matched" state, never an error or a 500. Task 2, `queryOfOnlyStopWordsFindsNothingWithoutError`.
2. **A post whose text cannot be rendered at index time** (a shortcode that throws, null text): it is still indexed by title, tags and summary, and a rebuild does not stop at it. Task 1, `postThatFailsToRenderIsStillIndexedByTitle`.
3. **Entities and markup in a snippet**: content `Fish &amp; chips <b>remodel</b>` must show `Fish &amp; chips` once-escaped (never `&amp;amp;`), no raw tag, and a title-only match gets the list excerpt, not an empty snippet. Task 3, `snippetEscapesOnceAndFallsBackToExcerpt`.
4. **Keep reading on a thin index** (blog with one post, post not yet indexed, index rebuilding): never lists the current post, never fails the page, falls back to today's picks. Task 8, `moreLikeThisNeverReturnsTheEntryItself` and `fallsBackWhenIndexHasFewerThanThree`.
5. **A site with one blog, or a blog searched while the index is rebuilding after upgrade**: no empty "From other blogs" heading and no "Search the whole site" link that leads to the same results. Task 5, `noOtherBlogsSectionWhenNoOtherBlogHasAMatch`.

---

## File map

| File | Responsibility |
|---|---|
| `app/pom.xml` | add `lucene-highlighter`, `lucene-queries` at `${lucene.version}` |
| `app/src/main/resources/com/wikantik/pressroom/config/pressroom.properties` | analyzer class, token limit |
| `business/search/SearchSpec.java` (new) | what to search: words, scope, blog, category, paging |
| `business/search/SearchHit.java`, `SearchHits.java` (new) | one result with its snippet; a result set with totals |
| `business/search/IndexManager.java` | `find`, `moreLikeThis`, `noteWeblog`; `search` removed |
| `business/search/lucene/SearchableText.java` (new) | entry to plain text for indexing |
| `business/search/lucene/RecencyBoost.java` (new) | the recency factor, pure |
| `business/search/lucene/Snippets.java` (new) | cut and escape a snippet, pure |
| `business/search/lucene/FieldConstants.java`, `IndexOperation.java` | schema v2 document |
| `business/search/lucene/SearchOperation.java` | weighted query, scopes, fallback |
| `business/search/lucene/LuceneIndexManager.java` | schema marker, conversion, `find`, `moreLikeThis`, `noteWeblog` |
| `business/jpa/JPAWeblogManagerImpl.java` | calls `noteWeblog` on save |
| `ui/rendering/util/WeblogSearchRequest.java`, `business/URLStrategy.java` + implementers, `ui/rendering/pagers/SearchResultsPager.java` | `scope=blog` |
| `ui/rendering/model/SearchResultsModel.java` | the two shapes of results page |
| `ui/rendering/model/PageModel.java`, `ui/rendering/model/BlogStanding.java` (new) | post count and newest date for the blog line |
| `ui/rendering/model/FurtherReading.java` | Keep reading from `moreLikeThis` |
| `WEB-INF/velocity/weblog.vm` | macros: results list, search box, blog line; after-entry subscribe |
| `themes/{journal,travel,portfolio}/{searchresults,weblog,permalink}.vm`, `themes/frontpage/weblog.vm`, `WEB-INF/velocity/templates/weblog/discovery.vm` | call the macros |
| `docs/dev/architecture.md`, `themes-and-templates.md`, `discovery.md` | updated in the task that changes the behaviour |

Paths under `business/`, `ui/`, `pojos/` are relative to `app/src/main/java/com/wikantik/pressroom/`; tests mirror them under `app/src/test/java/com/wikantik/pressroom/`.

---

### Task 1: Index schema v2

**Files:**
- Create: `business/search/lucene/SearchableText.java`
- Modify: `business/search/lucene/FieldConstants.java`, `IndexOperation.java` (`getDocument`, `beginWriting`), `LuceneIndexManager.java` (`initialize`, the constructor's marker setup), `pressroom.properties` (lines ~577-578), `app/pom.xml`
- Test: `business/search/lucene/SearchableTextTest.java` (new), `AddEntryOperationTest.java`, `LuceneIndexManagerStartupTest.java`, `business/search/SearchIndexQueryTest.java`

**Interfaces:**
- Produces: `FieldConstants.SUMMARY = "summary"`, `TAGS = "tags"`, `CATEGORY_TEXT = "cattext"`, `BLOG = "blog"`, `SITE = "site"`, `SITE_V = "1"`, `PUBLISHED_NUM = "pubnum"`; `CONTENT` is now stored (drop the unused `CONTENT_STORED`).
- Produces: `static String SearchableText.of(WeblogEntry entry, EntryRenderer renderer)` — rendered, tag-free, entity-decoded plain text; `""` on null text; on a render exception logs WARN (throwable last) and returns `""`.
- Produces: `static final int LuceneIndexManager.SCHEMA_VERSION = 2`, stored as text in the file `.index-schema` in the index directory.

- [ ] **Step 1: Failing tests for `SearchableText`** in `SearchableTextTest`:

```java
@Test void markdownAndShortcodeSourceAreNotInTheText()   // "## Heading\n**bold** [gallery id=3]" -> contains "Heading", "bold"; no "#", "*", "[gallery"
@Test void shortcodeOutputTextIsInTheText()              // an alert/callout shortcode's body words are present
@Test void entitiesAreDecoded()                          // "Fish &amp; chips" source -> "Fish & chips"
@Test void postThatFailsToRenderIsStillIndexedByTitle()  // renderer throws -> of() returns ""; (in AddEntryOperationTest) the doc exists and title query finds it
```

Use a real `EntryRenderer` where the existing rendering tests do; a stub that throws for the last.

- [ ] **Step 2: Run** `mvn -pl app test -Dtest=SearchableTextTest` — FAIL, class missing.
- [ ] **Step 3: Implement `SearchableText.of`**: `renderer.transformedText(entry)` (uncached; the indexer must not fill the render cache), then `Utilities.removeHTML(html, true)`, then `StringEscapeUtils.unescapeHtml4`, whitespace collapsed.
- [ ] **Step 4: Failing tests for the document** in `AddEntryOperationTest` / `SearchIndexQueryTest`:

```java
@Test void wordAfterThe1000thTokenIsFound()        // body = 1500 filler words + "zanzibar"; content:zanzibar hits
@Test void tagSummaryCategoryAndBlogNameAreIndexed() // one TermQuery per new text field hits the entry
@Test void siteFlagFollowsEntryEligibility()       // noindex entry: no SITE field; plain published entry: SITE=1
@Test void stemmingMatchesWordForms()              // body "remodeling"; analyzed query "remodel" hits
@Test void contentIsStoredAsPlainText()            // stored CONTENT of a markdown post has no "**"
```

- [ ] **Step 5: Run them** — FAIL (fields absent, token limit, analyzer).
- [ ] **Step 6: Implement.** `getDocument` adds the fields per the spec's table (`blog` = name + space + tagline; `tags` = tag names space-joined; `pubnum` as `NumericDocValuesField`; `site` only when `DiscoveryEligibility.isEligible(entry)`). `IndexOperation` needs the renderer: take it from `manager` (add a package-private accessor on `LuceneIndexManager` backed by its `Weblogger`). Properties: analyzer class `org.apache.lucene.analysis.en.EnglishAnalyzer`, `maxTokenCount=20000`; `instantiateDefaultAnalyzer` returns `EnglishAnalyzer` too. Add both Lucene modules to `app/pom.xml` beside `lucene-queryparser`.
- [ ] **Step 7: Failing startup tests** in `LuceneIndexManagerStartupTest`:

```java
@Test void indexWithoutSchemaMarkerIsRebuiltAtStartup()   // existing index, no .index-schema -> isInconsistentAtStartup() true, index emptied, rebuild scheduled
@Test void indexWithOlderSchemaIsRebuiltAtStartup()       // .index-schema contains "1"
@Test void indexWithCurrentSchemaIsLeftAlone()            // contains "2" -> not inconsistent
@Test void markerIsWrittenWhenAnIndexIsCreated()
@Test void unreadableMarkerCountsAsOutdated()             // fail closed
```

- [ ] **Step 8: Implement** in `initialize()`: before `indexExists()`, read the marker; anything but `"2"` sets `inconsistentAtStartup`, `deleteIndex()`, `createIndex(...)`; write the marker wherever an index is created. `deleteIndex` must not leave a stale marker.
- [ ] **Step 9: Run** `mvn -pl app test -Dtest='SearchableTextTest,AddEntryOperationTest,LuceneIndexManagerStartupTest,SearchIndexQueryTest,LuceneIndexManagerTest,ReIndexEntryOperationTest'` — PASS. Existing search tests that asserted `StandardAnalyzer` behaviour are updated, not deleted.
- [ ] **Step 10: Update** `docs/dev/architecture.md` "Search Implementation" (fields, analyzer, limit, schema marker). **Commit:** `Search index: rendered text, tags, summary, blog name; English stemming; schema marker`.

---

### Task 2: Ranking, fallback, and the `find` API

**Files:**
- Create: `business/search/SearchSpec.java`, `SearchHit.java`, `SearchHits.java`, `business/search/lucene/RecencyBoost.java`
- Modify: `business/search/IndexManager.java`, `lucene/SearchOperation.java`, `lucene/LuceneIndexManager.java` (`search` → `find`, `convertHitsToEntryList`), `ui/rendering/model/SearchResultsModel.java` (compile against `find`, behaviour otherwise unchanged in this task)
- Delete: `business/search/SearchResultList.java`, `SearchResultMap.java` if then unused
- Test: `lucene/RecencyBoostTest.java` (new), `lucene/LuceneIndexManagerSearchTest.java`, `business/search/SearchIndexQueryTest.java`

**Interfaces:**
- Produces:

```java
public record SearchSpec(String words, Scope scope, String weblogHandle, String category, int offset, int limit) {
    public enum Scope { SITE, BLOG, OTHER_BLOGS }   // OTHER_BLOGS is implemented in Task 3
}
public record SearchHit(WeblogEntryWrapper entry, String snippetHtml, boolean otherBlog) { }  // snippetHtml "" until Task 3
public record SearchHits(List<SearchHit> hits, int total, boolean anyWordFallback,
                         Map<String, Integer> perBlog, Set<String> categories) {
    public static final SearchHits NONE = ...;   // empty, total 0
}
// IndexManager
SearchHits find(SearchSpec spec, URLStrategy urls) throws WebloggerException;
// lucene
static double RecencyBoost.factor(long publishedMillis, long nowMillis);
```

- `perBlog`: handle → number of matches among the (up to 500) hits considered; `total` excludes what conversion dropped, as today.
- `IndexManager.search(...)` is removed; its only caller is `SearchResultsModel`.

- [ ] **Step 1: Failing `RecencyBoostTest`:**

```java
assertEquals(1.25, RecencyBoost.factor(now, now), 1e-9);
assertEquals(1.125, RecencyBoost.factor(now - days(365), now), 1e-9);
assertEquals(1.0, RecencyBoost.factor(now - days(730), now), 1e-9);
assertEquals(1.0, RecencyBoost.factor(now - days(5000), now), 1e-9);
assertEquals(1.25, RecencyBoost.factor(now + days(3), now), 1e-9);   // clamped
```

- [ ] **Step 2: Run** — FAIL; **implement** (`1 + 0.25 * clamp(1 - ageDays/730, 0, 1)`); run — PASS.
- [ ] **Step 3: Failing ranking tests** in `LuceneIndexManagerSearchTest` (fixture posts through the real index, as the class already does):

```java
@Test void titleMatchOutranksBodyMentionOfTheSameAge()
@Test void oldTitleMatchOutranksNewPassingMention()        // 3-year-old title hit above yesterday's single body mention
@Test void newerOfTwoEqualMatchesComesFirst()
@Test void tagOnlyAndBlogNameOnlyMatchesAreFound()
@Test void allWordsThenAnyWord()                           // "kitchen zebra": no post has both -> hits non-empty, anyWordFallback true
@Test void allWordsMatchDoesNotReportFallback()
@Test void readerCannotQueryNonSearchedFields()            // "site:1", "blog:x", "handle:x", "username:x" read as plain words
@Test void queryOfOnlyStopWordsFindsNothingWithoutError()  // "the", "!!!", "\"", "*" -> SearchHits with total 0, no exception
@Test void perBlogCountsTheHitsOfEachBlog()
@Test void offsetAndLimitPageThroughHits()
```

- [ ] **Step 4: Run** — FAIL.
- [ ] **Step 5: Implement.** `SearchOperation`: `SEARCH_FIELDS` becomes the six fields with the weights above (the `MultiFieldQueryParser` boosts map); `SearchedFieldsOnly.SEARCHED` follows. Wrap the parsed query in `FunctionScoreQuery.boostByValue` with a `DoubleValuesSource` reading `pubnum` through `RecencyBoost.factor`; search with relevance order (drop `SORTER`), still 500. On zero hits with the AND parser, re-parse with OR and record the fallback. A query that parses to an empty `BooleanQuery` is zero hits, not an error. Scope `BLOG` = handle clause as today; `SITE` = no handle clause (eligibility arrives in Task 3). `LuceneIndexManager.find` replaces `search`, keeping the try-with-resources reader discipline and the published/not-future re-check exactly as they are.
- [ ] **Step 6: Run** `mvn -pl app test -Dtest='RecencyBoostTest,LuceneIndexManagerSearchTest,SearchIndexQueryTest,SearchResultsModelTest,SearchDuringWriteTest,SharedReaderLifecycleTest'` — PASS.
- [ ] **Step 7: Commit:** `Search: rank by relevance with weighted fields and a recency factor; all words, then any`.

---

### Task 3: Scopes, eligibility, snippets, blog-rename reindex

**Files:**
- Create: `business/search/lucene/Snippets.java`
- Modify: `lucene/SearchOperation.java`, `lucene/LuceneIndexManager.java`, `business/search/IndexManager.java`, `business/jpa/JPAWeblogManagerImpl.java` (`saveWeblog`)
- Test: `lucene/SnippetsTest.java` (new), `lucene/LuceneIndexManagerSearchTest.java`, `lucene/LuceneIndexManagerTest.java`

**Interfaces:**
- Consumes: Task 2's `SearchSpec`, `SearchHit`, `SearchHits`, `find`.
- Produces: `static String Snippets.html(String plainContent, Query query, Analyzer analyzer, int maxChars)` — escaped text with `<mark>…</mark>` around matched words, `""` when the content has no match.
- Produces: `void IndexManager.noteWeblog(Weblog weblog)` — remembers the blog's name+tagline per handle; an unknown handle is recorded without a rebuild; a known handle whose value changed schedules `rebuildWeblogIndex(weblog)`.
- `SearchHit.snippetHtml` is now `Snippets.html(...)` over stored `content`, or, when that is `""`, the entry's list excerpt (`Excerpts`, the helper list pages use) HTML-escaped. `SearchHit.otherBlog` is true for every `OTHER_BLOGS` and `SITE` hit.

- [ ] **Step 1: Failing `SnippetsTest`:**

```java
@Test void marksTheMatchedWords()                 // "... our kitchen remodel cost ..." -> contains "<mark>kitchen</mark>" and "<mark>remodel</mark>"
@Test void marksStemmedForms()                    // query remodel, text "remodeling" -> "<mark>remodeling</mark>"
@Test void escapesMarkupInTheText()               // text "<script>alert(1)</script> remodel" -> contains "&lt;script&gt;", no "<script"
@Test void escapesAmpersandOnce()                 // text "Fish & chips remodel" -> "Fish &amp; chips", never "&amp;amp;"
@Test void staysNearTheLimit()                    // 5000-char text, limit 160 -> visible text <= 200 chars, cut at word boundaries
@Test void noMatchIsEmpty()
```

- [ ] **Step 2: Run** — FAIL; **implement** with `UnifiedHighlighter` (or `Highlighter` + `QueryScorer`) in re-analysis mode over the given string, using an encoder that HTML-escapes and a formatter that emits `<mark>`; run — PASS.
- [ ] **Step 3: Failing scope tests** in `LuceneIndexManagerSearchTest` (fixture: blogs A, B, C; C hidden; one noindex post on B; one site-space post):

```java
@Test void otherBlogsScopeLeavesOutTheNamedBlog()
@Test void otherBlogsAndSiteScopesLeaveOutNoindexPosts()
@Test void otherBlogsAndSiteScopesLeaveOutHiddenInactiveAndSiteSpaceBlogs()
@Test void hidingABlogTakesEffectWithoutReindex()          // flip visible=false, search again, no index write in between
@Test void blogScopeStillFindsItsOwnNoindexPost()
@Test void staleSiteFlagIsCaughtAtConversion()             // index says site:1, DB entry now noindex -> not returned, total reduced
@Test void snippetEscapesOnceAndFallsBackToExcerpt()       // title-only match -> snippetHtml equals the escaped list excerpt, not ""
@Test void siteAndOtherBlogHitsAreMarkedOtherBlog()
```

- [ ] **Step 4: Run** — FAIL.
- [ ] **Step 5: Implement.** `SITE` and `OTHER_BLOGS` add two FILTER clauses: `TermQuery(site:1)` and a `TermInSetQuery` on `handle` over the handles of weblogs passing `DiscoveryEligibility.isEligible(weblog)` (asked of `WeblogManager` per search; an empty set means zero hits, and a failure to load the set fails closed to zero hits with an ERROR log). `OTHER_BLOGS` also adds MUST_NOT `handle:<spec.weblogHandle>`. Conversion re-checks `DiscoveryEligibility.isEligible(entry)` for these two scopes.
- [ ] **Step 6: Failing rename tests** in `LuceneIndexManagerTest`:

```java
@Test void firstSightOfABlogSchedulesNothing()
@Test void renamedBlogIsReindexed()               // noteWeblog(A), rename, noteWeblog(A) -> a RebuildWebsiteIndexOperation for A is scheduled; then blog:newname finds A's posts
@Test void unchangedBlogSchedulesNothing()
```

- [ ] **Step 7: Implement `noteWeblog`** (a `ConcurrentHashMap<String,String>` in `LuceneIndexManager`) and call it at the end of `JPAWeblogManagerImpl.saveWeblog`. A failure there is logged and does not fail the save.
- [ ] **Step 8: Run** `mvn -pl app test -Dtest='SnippetsTest,LuceneIndexManagerSearchTest,LuceneIndexManagerTest'` — PASS.
- [ ] **Step 9: Update** `docs/dev/discovery.md` "Eligibility" (search now applies the rule; blog half at query time). **Commit:** `Search: site and other-blog scopes honour discovery eligibility; highlighted snippets`.

---

### Task 4: `scope=blog` and the results model

**Files:**
- Modify: `ui/rendering/util/WeblogSearchRequest.java`, `business/URLStrategy.java` and every implementer (find with `grep -rn "getWeblogSearchURL" app/src/main/java`), `ui/rendering/pagers/SearchResultsPager.java`, `ui/rendering/model/SearchResultsModel.java`
- Test: `ui/rendering/util/WeblogSearchRequestTest.java`, `ui/rendering/pagers/SearchResultsPagerTest.java`, `ui/rendering/model/SearchResultsModelTest.java`

**Interfaces:**
- Consumes: `IndexManager.find`, `SearchSpec.Scope`, `SearchHits`, `SearchHit`.
- Produces: `boolean WeblogSearchRequest.isBlogOnly()` — true for `scope=blog` or any `cat`.
- Produces: `String URLStrategy.getWeblogSearchURL(Weblog weblog, String locale, String query, String category, int pageNum, boolean blogOnly, boolean absolute)`; the old six-argument method stays and passes `false`.
- Produces on `SearchResultsModel` (Velocity names in brackets):

```java
boolean isSiteScope()            // [$model.siteScope] the searched weblog is the site space
boolean isBlogOnly()             // [$model.blogOnly]
List<SearchHit> getOwnHits()     // blog view: this blog's top 8; blogOnly and site views: the current page of 10
int getOwnTotal()                // true total for that list
List<SearchHit> getOtherHits()   // blog view only, up to 5; empty otherwise
boolean isAnyWordFallback()
int getBlogCount()               // site view: distinct blogs among the hits
int moreFromBlog(String handle)  // site view: that blog's hit count minus one, never negative
String getShowAllUrl()           // blog view: this blog's search, blogOnly, same words
String getSiteSearchUrl()        // blog view: the site space's search, same words, absolute
String blogSearchUrl(WeblogWrapper blog)   // site view: that blog's blogOnly search, same words, absolute
```

- `getHits()`, `getTerm()`, `getRawTerm()`, `getErrorMessage()`, `getWeblogEntriesPager()` keep working. `getResults()` (the day-grouped map) is removed only after grepping themes and `WEB-INF/velocity` for `$model.results`; the frontpage theme's comment explains why it must not be used as a test.

- [ ] **Step 1: Failing tests:**

```java
// WeblogSearchRequestTest
@Test void scopeBlogIsBlogOnly()   @Test void categoryImpliesBlogOnly()   @Test void unknownScopeValueIsIgnored()
// SearchResultsPagerTest
@Test void nextLinkCarriesScopeBlog()
// SearchResultsModelTest (IndexManager mocked; verify the SearchSpec each find receives)
@Test void blogViewAsksForEightOwnThenFiveOthers()        // specs: (BLOG, handle, 0, 8) then (OTHER_BLOGS, handle, 0, 5)
@Test void blogWithMoreFromSiteOffAsksNoOtherBlog()       // exactly one find call; getOtherHits() empty
@Test void blogOnlyViewPagesTenAndAsksNoOtherBlog()       // (BLOG, handle, page*10, 10)
@Test void siteViewAsksSiteScopePagedByTen()
@Test void fallbackFlagIsTrueWhenEitherQueryFellBack()
@Test void moreFromBlogIsCountMinusOne()
@Test void indexFailureSetsErrorMessageAndEmptyLists()
@Test void noQueryRunsNoSearch()
```

- [ ] **Step 2: Run** — FAIL. **Step 3: Implement.** `pageNum` applies to the paged views only; the blog view ignores it. The pager's "has next" reads `ownTotal > offset + 10`.
- [ ] **Step 4: Run** `mvn -pl app test -Dtest='WeblogSearchRequestTest,SearchResultsPagerTest,SearchResultsModelTest,SearchServletDecisionTest'` — PASS.
- [ ] **Step 5: Commit:** `Search results model: this blog first, then other blogs; scope=blog pages one blog`.

---

### Task 5: The results macro and the four results pages

**Files:**
- Modify: `app/src/main/webapp/WEB-INF/velocity/weblog.vm` (new macro `showSearchResults`, one argument `$model`; its `<style>` inside, as the showAfterEntry macro does), `themes/journal/searchresults.vm`, `themes/travel/searchresults.vm`, `themes/portfolio/searchresults.vm`, `themes/frontpage/weblog.vm` (search branch), every `ApplicationResources*.properties` bundle
- Test: `ui/rendering/servlets/SearchServletRenderingTest.java` (extend; parameterize over the four themes as `AfterEntryRenderingTest` does)

**Interfaces:**
- Consumes: Task 4's `SearchResultsModel` getters.
- Produces (markup contract, pinned by the test):

```
section.search-results
  p.search-fallback                       (only when anyWordFallback)
  h2.search-group  +  ol.search-list > li.search-hit
      a.search-thumb > img                (only when the post has a list thumbnail)
      h3.search-title > a                 (title bare; href: $url.entry for own hits, entry permalink absolute for other-blog hits)
      p.search-snippet                    ($hit.snippetHtml bare: it is already escaped)
      p.search-meta                       (date; own hit: category; other-blog hit: a.search-blog -> blog home)
  a.search-all                            (blog view, when ownTotal > 8)
  h2.search-group.search-group-other + ol ... + a.search-site
```

- New message keys (English copy; `{0}` is a count or name, escaped by the caller where user-typed):
  - `search.fromBlog` = `From {0} · {1} articles`
  - `search.fromBlogNone` = `Nothing in {0} matched.`
  - `search.fromOthers` = `From other blogs on the site`
  - `search.showAll` = `Show all {0} from this blog`
  - `search.wholeSite` = `Search the whole site`
  - `search.siteCount` = `{0} articles across {1} blogs`
  - `search.moreFromBlog` = `{0} more from this blog`
  - `search.anyWord` = `Nothing matched all of those words. Showing articles that match some of them.`

- [ ] **Step 1: Failing rendering tests** (each for journal, travel, portfolio; the last two for frontpage):

```java
@Test void ownHitsRenderInScoreOrderBeforeOtherBlogs()
@Test void otherBlogHitLinksAbsolutePermalinkAndNamesItsBlog()
@Test void snippetMarkupIsEmittedAndScriptInContentIsInert()   // post body "<script>alert(1)</script> remodel": page has "&lt;script&gt;" and "<mark>", no "<script>alert"
@Test void showAllLinkOnlyWhenMoreThanEight()
@Test void noOtherBlogsSectionWhenNoOtherBlogHasAMatch()       // no h2.search-group-other, no a.search-site
@Test void ownSectionSaysNothingMatchedWhenOnlyOthersDo()
@Test void fallbackLineShownOnlyOnFallback()
@Test void resultsPageIsNoindex()
@Test void siteViewNamesEachBlogAndShowsMoreFromBlog()         // frontpage theme
@Test void emptyAndErrorStatesStillRender()                    // existing copy kept
```

- [ ] **Step 2: Run** — FAIL. **Step 3: Implement** the macro and replace each theme's `#showWeblogEntriesPager($pager)` inside the results `<main>` with the macro call; keep each theme's search-again form, empty/error lines and next/prev control (shown for the paged views only). Thumbnail: reuse the macro or accessor the theme's list cards use; do not add a second rule. Style: `currentColor`/`color-mix` only, selectors doubled, no `#` or `$` in it.
- [ ] **Step 4: Run** `mvn -pl app test -Dtest='SearchServletRenderingTest,MessageKeyTest,MessagePlaceholderContractTest,MessageFormatRegressionTest,ThemeChromeConsistencyTest,JournalThemeRenderingTest'` — PASS.
- [ ] **Step 5: Look at it.** `./pressroom dev`, search from a blog and from the site space; check a phone-width viewport. Fix what the tests could not see.
- [ ] **Step 6: Update** `docs/dev/themes-and-templates.md` (new section "Search results"). **Commit:** `Search results page: one macro, matching passages, this blog first`.

---

### Task 5b: A reachable address for site-wide search

**Why this task exists.** The spec assumed the site space's search URL already worked ("no new route"). It does not: `WeblogRequestMapper` treats `search` as a reserved first segment and returns unhandled for `/search`, and the site space (handle `Weblog.SITE_SPACE_HANDLE`) answers only the asset contexts in `SITE_ASSET_CONTEXTS`. So the "Search the whole site" link built by `SearchResultsModel.getSiteSearchUrl()` (`<context>/search?q=...`) is a 404, and the front page (Task 6) would have nowhere to post. The discovery front page solves the same problem for `/` with `FrontDoor` + `PageServlet.discoveryTemplate`; mirror that.

**Files:**
- Modify: `ui/rendering/WeblogRequestMapper.java`, `ui/rendering/servlets/SearchServlet.java`, `ui/rendering/model/SearchResultsModel.java`
- Create: `app/src/main/webapp/WEB-INF/velocity/templates/weblog/site-search.vm`
- Test: the existing `WeblogRequestMapper` test class(es) (find with `grep -rl WeblogRequestMapper app/src/test`), `ui/rendering/servlets/SearchServletRenderingTest.java`, `ui/rendering/model/SearchResultsModelScopesTest.java`
- Docs: `docs/dev/routing.md`, `docs/dev/discovery.md`, `docs/dev/themes-and-templates.md` ("Search results")

**Interfaces:**
- Produces: on the site host (no custom-domain match), `GET <context>/search` (exactly that path: no further segment, with or without `?q=`) forwards to the search servlet for the site space weblog. It is the ONE address of site-wide search.
- Produces: `SearchResultsModel.isSiteScope()` is true when the searched weblog `isSiteSpace()` or `WebloggerRuntimeConfig.isSiteWideWeblog(handle)` (the legacy aggregated front-page blog keeps working). Use one helper for "is this the site" inside the model; the other-blogs query is skipped by the same helper.
- Produces: `getSiteSearchUrl()` returns the address above (absolute, context path included, same words), and null when there is no site space weblog.
- Produces: the site space's search renders with its theme's template named `site-search` if the theme has one, else the shared `templates/weblog/site-search.vm` (exactly as `PageServlet.discoveryTemplate` chooses `discovery`).
- Task 6 consumes: the front page's search form posts to this address; the template has `form.site-search[role=search][method=get]` with `input[type=search][name=q][maxlength=255][required]` prefilled with the escaped query.

**Unchanged, and pinned by tests:**
- `/search/<anything>`, `/site-space/search` and `/search` when no site space weblog exists stay 404 (unhandled by the mapper).
- On a custom domain, `/search` is that blog's own search, as today.
- `search` stays in the protected-URL list, so no blog can take the handle.
- A blog's `/<handle>/search` is untouched.

- [ ] **Step 1: Failing mapper tests**

```java
@Test void siteSearchForwardsToTheSearchServletForTheSiteSpace()   // GET /search?q=x on the site host -> forward target is the search servlet path for Weblog.SITE_SPACE_HANDLE
@Test void siteSearchWorksUnderAContextPath()                      // context path /pressroom, GET /pressroom/search -> same forward
@Test void searchWithAFurtherSegmentIsNotHandled()                 // /search/x -> false
@Test void siteSpaceHandleSearchIsNotAnAddress()                   // /site-space/search -> not forwarded
@Test void noSiteSpaceMeansNoSiteSearch()                          // site space weblog absent or tier not bootstrapped -> false
@Test void searchOnACustomDomainIsThatBlogsSearch()                // characterisation; say so in its javadoc
```

- [ ] **Step 2: Run** — FAIL (first two, fifth as applicable). **Step 3: Implement** the branch before the `reserved.contains(firstSegment)` skip, guarded by `vhostHandle == null`, bootstrapped, and the site space weblog existing; reuse `calculateForwardUrl`.
- [ ] **Step 4: Failing rendering and model tests**

```java
// SearchServletRenderingTest
@Test void siteSpaceSearchRendersTheSharedTemplateWithResultsOfSeveralBlogs()  // h1 is the site name; two blogs' posts, each a.search-blog; section.search-results present
@Test void siteSpaceSearchPageIsNoindexAndHasTheSearchFormPrefilled()          // query with a quote and "<" comes back escaped in the input value
@Test void siteSpaceSearchWithNoQueryShowsTheFormAndNoResults()
@Test void siteSpaceSearchEmptyAndFallbackStatesRender()
@Test void aSiteSpaceThemeWithItsOwnSiteSearchTemplateIsUsed()                 // only if cheap with the existing theme fixtures; otherwise unit-test the template choice
// SearchResultsModelScopesTest
@Test void theSiteSpaceIsSiteScope()                 // searched weblog isSiteSpace() -> one find with Scope.SITE, paged by 10
@Test void siteSearchUrlIsTheRootSearchAddress()     // ends with "/search?q=..." after the context path; null without a site space
```

- [ ] **Step 5: Implement.** `site-search.vm`: the discovery page's head and masthead conventions (site name as the one `h1` linking home, `<meta name="robots" content="noindex">`, the site footer/colophon the discovery page uses), the search form, then the showSearchResults macro, then next/prev for the paged view and the same empty and error lines the themes show. Share chrome with `discovery.vm` through existing macros or a parsed partial where that is clean; do not copy its page-specific sections or stylesheet wholesale: this is a plain page. Page title "Search: <words>" escaped (page titles are raw: always `$utils.escapeHTML`).
- [ ] **Step 6: Run** the mapper test class, `SearchServletRenderingTest`, `SearchResultsModelScopesTest`, `SearchServletDecisionTest`, the i18n ratchets if you added keys, then the full unit suite once — PASS.
- [ ] **Step 7: Look at it** in `./pressroom dev`: `http://localhost:8083/search?q=<a word in the dev data>` and the "Search the whole site" link from a blog's results. Stop the server afterwards.
- [ ] **Step 8: Docs** (routing.md: the new address and what stays 404; discovery.md and themes-and-templates.md: the template and how it is chosen). **Commit:** `Site-wide search has an address: /search renders the site space's results`.

---

### Task 6: Search boxes on the front page and blog homes

**Files:**
- Modify: `WEB-INF/velocity/weblog.vm` (new macro `showSearchBox`, arguments `$weblog $placeholder`), `WEB-INF/velocity/templates/weblog/discovery.vm` (masthead, near line 369), `themes/journal/weblog.vm`, `themes/travel/weblog.vm`, `themes/portfolio/weblog.vm` (header box added, footer `form.*-search` removed), message bundles
- Test: `ui/rendering/servlets/` rendering tests for discovery and each theme's home (extend the existing classes that render them), `it-selenium/.../SearchIT.java`

**Interfaces:**
- Produces: `form.site-search[role=search][method=get]` with `input[type=search][name=q][maxlength=255][required]`, action `$url.search` of the weblog passed. On discovery the weblog is the site space.
- Keys: `search.placeholderBlog` = `Search {0} and the rest of {1}` (blog name, site name; when the site name is empty use `search.placeholderBlogOnly` = `Search {0}`), `search.placeholderSite` = `Search all of {0}` (empty site name: `search.placeholderSiteOnly` = `Search every blog`).
- `permalink.vm`, `page.vm` and `searchresults.vm` footers are **not** touched.

- [ ] **Step 1: Failing rendering tests:**

```java
@Test void discoveryMastheadHasOneSearchFormPostingToTheSiteSearch()
@Test void blogHomeHasExactlyOneSearchFormAndItIsInTheHeader()   // per theme; none inside <footer>
@Test void postPageStillHasItsFooterSearchForm()                 // per theme; characterisation, passes immediately: say so in its javadoc
@Test void placeholderFallsBackWhenSiteNameIsEmpty()
```

- [ ] **Step 2: Run** — FAIL (all but the characterisation). **Step 3: Implement.**
- [ ] **Step 4: Failing browser test** in `SearchIT`:

```java
@Test void searchFromBlogHomeShowsThisBlogThenOtherBlogs()   // type in header box on blog A; first group heading names A; second group lists a post of B with B's name
@Test void searchFromFrontPageListsPostsOfSeveralBlogs()
@Test void showAllLinkPagesOneBlog()
```

- [ ] **Step 5: Run** `mvn -pl app test -Dtest=<the rendering classes>` then `mvn verify -Pit -Dit.test=SearchIT` and again with `-Dit.context.path=pressroom` — PASS at both.
- [ ] **Step 6: Commit:** `Search box at the top of the front page and every blog home`.

---

### Task 7: The blog line and the subscribe form on a post page

**Files:**
- Create: `ui/rendering/model/BlogStanding.java`
- Modify: `ui/rendering/model/PageModel.java`, `WEB-INF/velocity/weblog.vm` (new macro `showBlogLine`, argument `$weblog`; the showAfterEntry macro gains the subscribe block after Keep reading), `themes/{journal,travel,portfolio}/permalink.vm` (call `showBlogLine` under the title block; drop the footer `showSubscribeForm` call), message bundles
- Test: `ui/rendering/model/PageModelTest.java`, `ui/rendering/servlets/AfterEntryRenderingTest.java`

**Interfaces:**
- Produces: `public record BlogStanding(long articles, Date latest)`; `BlogStanding PageModel.getBlogStanding()` — published, not-future posts of `$model.weblog` (`WeblogEntryManager.getEntryCount(Weblog)` if it counts exactly those, else one count query) and the newest one's pub time; `latest` null on an empty blog. Built once per render.
- Markup: `p.blog-line` containing `a.blog-line-name` (blog home), the count, the date formatted with the blog's locale and time zone as `d MMM yyyy`, and `a.blog-line-subscribe[href="#subscribe"]` only when `$weblog.subscribeFormShown`. The subscribe block in the after-entry macro is wrapped in `div#subscribe.entry-subscribe`.
- Keys: `blogLine.from` = `From {0}`, `blogLine.articles` = `{0} articles`, `blogLine.latest` = `latest {0}`, `blogLine.subscribe` = `Subscribe`, `afterEntry.subscribePrompt` = `New articles from {0} by email`.
- The line is omitted entirely when `articles < 2` (a one-post blog has nothing to stand on).

- [ ] **Step 1: Failing tests:**

```java
// PageModelTest
@Test void blogStandingCountsPublishedPostsAndFindsTheNewest()   // drafts, scheduled, trashed not counted
// AfterEntryRenderingTest, per theme
@Test void blogLineShowsCountAndAbsoluteLatestDate()             // contains "42 articles" and "9 Nov 2026"; no "ago"
@Test void blogLineChangesAfterAnotherPostIsPublished()          // publish, render again (cache expired by lastModified) -> "43 articles", new date
@Test void blogLineOmittedOnAOnePostBlog()
@Test void subscribeLinkAndFormOnlyWhenTheFormIsShown()          // off: no a.blog-line-subscribe, no .subscribe-form-slot
@Test void postPageHasExactlyOneSubscribeFormAndItPrecedesComments()
@Test void previewAndStaticPagesCarryNoBlogLine()
```

- [ ] **Step 2: Run** — FAIL. **Step 3: Implement.** Order inside the after-entry macro: share, Nearby, Keep reading, subscribe. A blog with the share row off still gets the subscribe block.
- [ ] **Step 4: Run** `mvn -pl app test -Dtest='PageModelTest,AfterEntryRenderingTest,ThemeChromeConsistencyTest,MessageKeyTest,MessagePlaceholderContractTest'` — PASS. **Step 5:** look at a post in all three themes in `./pressroom dev`.
- [ ] **Step 6: Update** `docs/dev/themes-and-templates.md` "After the article". **Commit:** `Post page: blog line under the title, subscribe form after Keep reading`.

---

### Task 8: Keep reading from the index

**Files:**
- Modify: `business/search/IndexManager.java`, `lucene/LuceneIndexManager.java`, `ui/rendering/model/FurtherReading.java`
- Test: `lucene/LuceneIndexManagerSearchTest.java`, a `FurtherReadingTest` (extend the existing test of `FurtherReading`; find it with `grep -rl FurtherReading app/src/test`)

**Interfaces:**
- Produces: `List<String> IndexManager.moreLikeThis(WeblogEntry entry, int max)` — ids of the most similar published, not-future posts **of the same blog**, best first, never the entry's own id; an empty list when search is disabled, the entry is not in the index, or anything fails (logged at WARN, throwable last: this is a display path).
- `FurtherReading.of`: related = the `moreLikeThis(entry, 3 + nearbyShown)` ids resolved against the already-loaded `candidates` (ids outside the newest 200 are skipped), minus posts shown under Nearby, first 3; when fewer than 3 remain, the rest is filled from `RelatedPicker.related(...)` as today, without duplicates.

- [ ] **Step 1: Failing tests:**

```java
// LuceneIndexManagerSearchTest
@Test void moreLikeThisFindsPostsOnTheSameSubjectWithoutTags()   // three untagged kitchen posts + five unrelated newer ones; for one kitchen post the other two come first
@Test void moreLikeThisNeverReturnsTheEntryItself()
@Test void moreLikeThisStaysInsideTheBlog()
@Test void moreLikeThisIsEmptyForAnEntryNotInTheIndex()
// FurtherReadingTest (IndexManager mocked)
@Test void relatedFollowsTheIndexOrder()
@Test void fallsBackWhenIndexHasFewerThanThree()                 // index returns 1 id -> that one, then RelatedPicker's picks, 3 in all, no duplicate
@Test void emptyIndexGivesExactlyTodaysPicks()                   // characterisation of the fallback
@Test void postUnderNearbyIsNotRepeated()
```

- [ ] **Step 2: Run** — FAIL. **Step 3: Implement** with `org.apache.lucene.queries.mlt.MoreLikeThis` over `title`, `tags`, `content` (find the entry's doc by `id`, `like(docNum)`; `minTermFreq` 1, `minDocFreq` 2), filtered to the handle, excluding the id, same searcher acquire/release discipline as `find`.
- [ ] **Step 4: Run** `mvn -pl app test -Dtest='LuceneIndexManagerSearchTest,FurtherReadingTest,AfterEntryRenderingTest,SearchDuringWriteTest'` — PASS.
- [ ] **Step 5: Update** `docs/dev/themes-and-templates.md` ("The picks": content first, tag rule as fallback, the accepted staleness from the spec). **Commit:** `Keep reading picks by content through the search index`.

---

### Task 9: Whole-build verification

- [ ] **Step 1:** `mvn clean install` — every gate green (JaCoCo floors, PMD, CPD, SpotBugs, `-Xlint`). Fix findings; do not widen an exclusion. If `bin/check-diff-coverage.sh` is under 90% on changed lines, add tests that assert behaviour.
- [ ] **Step 2:** `mvn verify -Pit`, then `mvn verify -Pit -Dit.context.path=pressroom` — both green.
- [ ] **Step 3: Upgrade rehearsal.** Start the previous release's WAR against a scratch index directory so it builds a v1 index, stop it, start this build on the same directory: the log says the index is rebuilding, and a search afterwards finds a word from deep in a long post. Record the rebuild time in `docs/dev/architecture.md`.
- [ ] **Step 4:** Read the spec's acceptance criteria one by one and name the test for each in the report. Report anything pushed back on or left out. Do not push.
