# Post page discovery: related, nearby, share, hero

2026-11-11. Approved in chat ("I agree ... get it done").

## Why

Search visitors land on a post, not on `/`. On 2026-11-11 a post ended in
Older/Newer by date and a footer block of other blogs' posts (two investing
posts under a foothills produce guide): nothing on the page led to the next
guide on the same subject or the place next door. The hero picture, the
page's largest paint, was lazy-loaded. Readers had no way to pass a post on.

## Goals

A post page in the three article themes (journal, travel, portfolio) gets,
between the article and the comments, in this order:

1. **Share row.** Plain links, in the article's last lines.
2. **Nearby.** For a post with coordinates: the same blog's nearest posts.
3. **Keep reading.** The same blog's most related posts.

and its hero picture loads eagerly.

## Non-goals

- No per-blog setting, no schema change, no API change.
- No cross-blog nearby or related (the footer's "More from" stays as it is).
- Category hubs, breadcrumbs, table of contents, bylines: later waves.
- Preview pages and static pages carry none of this.

## Anti-goals (sharing)

Sharing lets a reader who already wants to pass a post on do it in one click.
It does not make the site a social product: no third-party script, pixel or
iframe; no share counts; no floating or sticky bar; no pop-up or prompt; no
network icons or brand colours; no tracking parameters on the shared URL; no
"click to tweet" pull quotes; nothing written or titled for a social feed.
The rule is recorded in `docs/design/design-system.md`.

## Design

One macro, `showAfterEntry($entry)` in `weblog.vm`, called by each article
theme's `permalink.vm` after the entries pager and before the comments macro.
It carries its own `<style>` (currentColor and `color-mix`, as the content
assets do), so it needs no per-theme rule and a forked theme stylesheet
cannot leave it unstyled.

`$model.furtherReading` (`PageModel`) returns the two lists, computed once per
render from at most 200 of the blog's newest published posts plus one grouped
tag query (`WeblogEntryManager.getSharedTagCounts`). The picking is the pure
class `business.related.RelatedPicker`.

## Acceptance criteria

Share row
1. Every article theme's post page has one `nav.entry-share` holding links to
   email (`mailto:`), Facebook, X, LinkedIn, each carrying the post's absolute
   permalink URL-encoded, plus a copy-link button and a native-share button
   that are both `hidden` in the markup (script reveals them where supported).
2. A Pinterest link appears only when the post has a featured image, and
   carries that image's URL.
3. Outbound share links have `target="_blank"` and `rel="noopener"`. The page
   references no third-party script, image or frame for sharing.
4. A title with `&`, quotes or `<` is URL-encoded from its unescaped text
   (no `%26amp%3B` in any share link).

Keep reading
5. Shows at most 3 posts of the same blog, never the post itself, never a
   draft, each a link to the post with its title; a featured image when it
   has one.
6. Order: score descending, then newest. Score = 2 per shared tag + 3 for
   the same category. When fewer than 3 posts score above zero, the newest
   other posts fill the rest.
7. A blog with no other published post renders no "Keep reading" section.

Nearby
8. Only for a post with both coordinates. Up to 3 posts of the same blog
   with coordinates, within 80 km, nearest first, each with its distance.
9. Distance is whole miles for a blog whose locale country is US, GB, LR or
   MM, otherwise whole kilometres; under one unit it reads "under 1".
10. A post shown under Nearby is not repeated under Keep reading.
11. A post without coordinates, or with no post in range, renders no Nearby
    section.

Hero
12. On a post page the hero `img` of travel and portfolio (journal has no
    hero) has `loading="eager"` and `fetchpriority="high"`; list-page card
    images stay lazy.

Cost
13. `furtherReading` runs a bounded number of queries that does not grow with
    the number of posts in the blog (candidates, tag counts).
