# Search relevance: phrase proximity, most-words fallback, blog-name weight

Date: 2026-11-15. Size: a small change (patch release after local testing).

## Why

Reader search (`SearchOperation`) ranks with a plain multi-field BM25 query.
Jake's use of it says relevance could be better. Three lexical changes are
judged low-risk enough to ship without a judged query set; the riskier one
(BM25F / DisMax field combination) waits for that set, which Jake is growing
by publishing more posts on production.

## Approaches considered

1. **Lexical fixes in Lucene (chosen, three of four):** phrase/proximity boost,
   most-words fallback, lower blog-name weight. Cheap, local, measurable later.
2. **BM25F (`CombinedFieldQuery`) or DisMax:** deferred. It re-scores every
   multi-field match and needs the query-parser syntax rebuilt; decide on
   numbers from a judged query set.
3. **Embeddings / hybrid kNN:** deferred until lexical misses are shown to be
   vocabulary (meaning) misses rather than ranking misses.

## What changes

### 1. Phrase-proximity boost (scoring only)

When the reader's words analyse to two or more tokens, the ranked query becomes
`MUST(words) + SHOULD(phrase)`, the phrase clause being title (slop 2, boost 2)
or content (slop 3, boost 1), built with the index analyzer from the reader's
words with query syntax characters read as spaces. Constants in
`SearchOperation`, tunable later.

- Matching is unchanged: the phrase clause is optional, so the hit set and
  total for any query are exactly what they were.
- The query used to mark snippets (`getParsedQuery`) stays the words query.

### 2. Most-words fallback

Stages: **all words (AND)** -> **most words** -> **any word (OR)**. The most-
words stage runs only when AND found nothing and the OR query has at least
three optional (SHOULD) clauses; it requires `floor(0.75 n)` of them (3->2,
4->3, 5->3, 6->4). Only plain words get the stage: with a reader's `+w`/`-w`,
Lucene 10's parser nests the plain words in one clause, so such a query goes
from all words straight to any, as before (builder's call, 2026-11-15). Both fallback stages set `anyWordFallback`, so the page's
existing notice ("...Showing articles that match some of them") stays true;
partial matches are never shown as complete ones.

### 3. Blog-name weight 2 -> 0.5

The `blog` field (blog name + tagline) stays searched, so a post that relies on
its blog's name for a word ("tahoe hiking" in a Tahoe blog) is still found;
its weight drops from 2 to 0.5 so the blog name no longer lifts every post of
that blog above stronger matches elsewhere.

## Acceptance criteria (each a test)

1. Same age, same length: a post whose body has the query words adjacent
   outranks a post whose body has them far apart.
2. A post with all the query words in its title outranks a post with them only
   as a phrase in its body.
3. Adding the phrase clause changes no hit set or total (a query whose words
   are scattered still finds the same posts, same count).
4. A four-word query no post fully matches returns posts with three of the
   words and not posts with only one, and reports the fallback.
5. When no post has most of the words, any-word results are returned (fallback
   reported), as today.
6. A two-word query goes straight from AND to OR (no most-words stage).
7. Parsed query weights: title 4, tags 3, summary 2, blog 0.5, cattext 1.5,
   content 1.
8. Site scope: a post that mentions a word in its body outranks a post of
   another blog that matches it only through its blog's name.
9. A blog-name-only match is still found.
10. Docs: `docs/dev/architecture.md`, "Search Implementation", describes the
    three stages, the phrase boost and the new weight.

## Left to the builder

Exact slop/boost values may be adjusted if a criterion above cannot be met
with the stated ones; record the final values in the architecture doc.
