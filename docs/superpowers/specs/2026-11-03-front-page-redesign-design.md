# Front page redesign: from link list to magazine front

Date: 2026-11-03. Status: **built** (decisions D1–D6 taken, see the end;
ads are phase 2).
Mockup (visual source of truth): `docs/superpowers/specs/2026-11-03-front-page-redesign-mockup.html`,
built from the real posts and photos on blog.wikantik.com that day.
Scope: the discovery front page at `/` only, now one template,
`WEB-INF/velocity/templates/weblog/discovery.vm`, whatever the site space's
theme (D4). Every blog page, post page and admin screen is unchanged, except
that excerpts everywhere pass over a cut search description (D6).

## Why

The site is six blogs and roughly 80 posts: Exploring Berlin!, Berlin Local
Wiki, San Diego Local Wiki, The California Foothills, Simple Investing and
Wikantik. Nearly every travel and local post has a strong featured image and a
search description. The front page shows none of that. Today it is a 46rem
column of titles in bordered boxes: no images, no excerpts, no dates, and a
tagline that describes the Pressroom platform rather than anything a reader
came for.

The goals, in order:

1. **Turn a first visit into a second.** Show at a glance what the site is
   (local guides to Berlin, San Diego and the Gold Country) and give every
   kind of visitor a next click: the lead story, what is popular, what is new,
   each blog.
2. **Grow an owned audience.** The newsletter (listmonk) is the asset that
   compounds; the front page should ask for the email twice without nagging.
3. **Be ready for AdSense without being shaped by it.** Reserve ad slots in
   places that do not push content down or shift the layout, and render
   nothing at all while ads are off.

A note on expectations. Most AdSense revenue on a content site comes from
post pages, because that is where search traffic lands. The front page earns
through what it leads to: pages per session, return visits and sign-ups.
Measure it on those, not on its own ad revenue.

## What we take from other sites

| Pattern | Seen on | What we take |
|---|---|---|
| One lead story with a large image, beside a numbered "most read" rail | The Guardian, NYT, The Verge | Lead + rail above the fold. The rail is `popular`, numbered because rank is real information. |
| Section nav as a row of colored pills | The Verge, Atlas Obscura | One pill per blog, each with its own hue, scrolls sideways on phones. |
| Chronological "Latest" list with thumbnail, kicker, excerpt | Wirecutter, Eater, most news apps | A new `latest` section: the freshness signal returning readers and crawlers both look for. |
| Main column + sticky sidebar holding an ad and a nav list | Many AdSense blogs, Smitten Kitchen | Sidebar with the 300×600 slot and "Browse the blogs"; it stacks inline on tablets and phones. |
| Publication "shelves" with an identity and a few top posts | Medium publications, Substack discover | "Explore the blogs" becomes shelves: initials mark, tagline, top image, three posts. |
| Newsletter ask in a full-width band mid-page, plus a small one high up | Morning Brew, Atlas Obscura | A small card in the rail, a band between shelves and "Something different". |
| Serendipity | Atlas Obscura's "random place" | Keep "Something different"; add a "Surprise me" link later (out of scope here). |
| Footer that makes About, Contact and Privacy easy to find | Every AdSense-approved site | Footer columns: the site, follow, legal (privacy, consent settings, US opt-out). |

What we leave behind: autoplay video, infinite scroll (it buries the footer
AdSense reviewers look for), pop-up email modals, and ads above the lead.

## The page, top to bottom

Widths: desktop ≥1020px is a 75rem two-column grid (2fr : 1fr). Tablet
stacks the columns and shows the rail and sidebar as two-up rows. Phone
(≤640px) is one column; the lead image runs full bleed.

1. **Utility line.** Date and "6 blogs" in mono; sign-in on the right.
2. **Masthead.** Site mark, site name (the page's one `h1`, as today), the
   site description and a Subscribe button that jumps to the newsletter
   band. *Built without the search field:* Pressroom's search covers every
   blog only when the site space is also the site-wide weblog, which this
   page cannot assume; a site-wide search box is a follow-up.
3. **Blog nav.** "Front page" plus one pill per eligible blog in its hue.
4. **Lead + rail.**
   - Lead (`fd-discovery-headline`): featured image 16:9, eyebrow ("Most
     read lately" or "Newest", the existing rule), blog kicker, title in the
     serif at `clamp(28px, 3.4vw, 40px)`, excerpt, date and reading time.
     With no featured image the lead becomes a tinted panel and the title
     carries it.
   - Rail: `fd-discovery-popular` as a numbered list of **5** (was 4), each
     with its blog kicker; then the small newsletter card.
5. **Slot A** (future leaderboard, 728×90 desktop and 320×100 phone).
   Until ads are on, each of the three slots is a house spotlight of a blog
   the shelves do not show (D5).
6. **Latest + sidebar.**
   - `fd-discovery-latest` (new): the 8 newest eligible posts not already
     shown above, thumbnail 4:3 (square 96px on phones, on the right), kicker,
     title, two-line excerpt, date, reading time. "All recent posts" links to
     the existing site-wide recent list.
   - Sidebar (desktop; stacks after Latest on tablets and phones): sticky
     slot B (future 300×600), then "Recently updated": at most 5 blogs, the
     one with the newest post first, each with its post count, and "All N
     blogs ↓" down to item 12 when it leaves any out (amended 2026-11-06:
     a list of every blog outgrew the sticky sidebar at 10+ blogs and
     repeated item 12). The
     phone-only in-feed slot of the first draft is dropped: three slots, the
     same three at every width.
7. **Explore the blogs** (`fd-discovery-blogs`): three shelves, each with
   its hue as a top rule, initials mark, name, post count, tagline, the
   first top post's image (or a hue tile with the initials), three top posts
   and a "Visit" link. Phones scroll the shelves sideways with snap.
8. **Newsletter band:** "Get the Sunday roundup", email field and Subscribe,
   a privacy policy link.
9. **Something different** (`fd-discovery-different`): three image cards.
10. **Slot C** (future responsive in-feed).
11. **Places you've been:** unchanged contract (hidden until the browser
    has places), restyled as chips.
12. **All blogs** (`fd-discovery-all`): a grid of marks, names and taglines
    (the post count for a blog with no tagline): the one complete list.
13. **Footer:** site description, the site's own nav pages and the blog
    directory, then the existing colophon (privacy link, sign-in, version).
    Consent settings and the US opt-out link arrive with ads (phase 2).

### Visual language

The Quiet Instrument tokens are unchanged: paper, ink, the teal accent, IBM
Plex Serif/Sans/Mono (already self-hosted, so no CSP change). Three
additions, each a decision below:

- **The lead title is the page's one oversized element** at up to 40px.
  This is signature move 4 as written (one element above the scale); the
  site name drops to the 28px tier to give it room. (D1)
- **Blog hues.** Six marks taken from the existing token set (accent, warn,
  good, bad) plus two to match (slate `#3F5F8A`, plum `#7A4F7A`), each with a
  dark-mode pair. A blog's hue is assigned from its position by name
  (`allBlogs` order, modulo 6), so it is stable and needs no setting. (D2)
- **Width** goes from 46rem to 75rem on this page only.

## Data and model changes

Most of what the page needs is already on the wrappers:
`WeblogEntryWrapper.excerpt`, `featuredImage`, `pubTime`;
`WeblogWrapper.tagline`, `lastModified`; `#showResponsiveImage` for srcset.
Missing:

1. **`latest`** on `FrontPageSelection` / `DiscoveryModel.FrontPage`: the 8
   newest eligible posts, excluding entries already picked for the headline,
   popular, card or different sections (the record's "no entry in two
   sections" invariant). Chosen in `pickFrontPage` after the other sections,
   memoized as ids per snapshot like the rest, and counted among the
   sections a dropped headline's replacement must not repeat.
2. **`POPULAR_SLOTS` 4 → 5.**
3. **Reading time:** `WeblogEntryWrapper.getReadingMinutes()`, words of the
   transformed text / 230, rounded up, minimum 1. Shown as "N min read".
4. **No relative dates** ("2 hours ago"): rendered pages are cached until the
   next discovery generation (up to an hour), so a relative date would go
   stale. Dates are absolute ("Oct 29"), in `<time datetime>`.
5. **Blog hue index:** computed in the template from `allBlogs` order, so no
   model change. A blog's index must not change when another blog's posts
   change, only when blogs are added or removed. The mark's letters are
   `DiscoveryModel.initials` (first letter of the first two words, a leading
   article skipped, letters and digits only).

## Ads: ready, not on

This spec builds only the **slots**. Turning AdSense on is a separate spec
(phase 2) because it changes things site-wide.

- **Built now:** three house slots, `data-slot="a|b|c"`, each a blog
  spotlight, so the page has its final shape before any ad exists.
- **Phase 2:** slots render ads only when a new runtime property `site.ads.adsense.client`
  (a `ca-pub-` id) is set and the slot's own id
  (`site.ads.frontpage.slotA|B|C`) is set. Blank (the default) means no ad
  markup and no extra request: the house spotlight stays.
- Each slot reserves its height with `min-height` (90/100, 600, 250) so an
  arriving ad never shifts the layout (CLS). Each carries an
  "Advertisement" label. At most three slots on the page, never above the
  lead, never directly beside the nav (accidental-click policy).
- Phase 2, for the record: `ads.txt` at the site root (Pressroom does not
  serve one today), the AdSense script hosts added to the CSP of every theme
  that shows ads (today's `script-src 'self'` blocks it), Google's certified
  consent message for EEA/UK/CH (the privacy policy already promises one),
  and the "Privacy and cookie settings" and US opt-out links the policy
  names. The privacy policy (2026-11-02) already describes AdSense.

## Content and settings (no code)

Done by an admin in Global Config and the editor, not by this change:

- **Site description.** Replace the current Pressroom paragraph with a reader-facing line, e.g. "Local guides to Berlin, San Diego and California's Gold Country, plus simple investing and notes from building the software behind it." Move the platform story to an About page.
- **Search descriptions and meta titles.** The publishing agent cut many
  descriptions at about 150 characters and added a full stop ("…to bring
  hygienic,."), and cut meta titles at 100 characters ("…Placer Citr").
  Pressroom now passes over such a description for excerpts
  (`Excerpts.isCutFrom`) and the SEO audit reports both
  (`search_description_cut`, `meta_title_cut`), so the agent or the author
  can rewrite the stored values. The agent's own truncation lives outside
  this repository.
- **A site-wide newsletter list** for the roundup (D3).

## Acceptance criteria

Each is checked by `DiscoveryFrontPageRenderingTest` (unit, both themes where
it applies) or `DiscoveryIT` (browser).

1. The five existing section markers keep their names and their item counts,
   except popular, which has 5. `fd-discovery-latest` is added with 8 items
   when at least that many eligible posts remain. No entry appears in two
   sections.
2. Exactly one `h1`, still the site name linking home.
3. A post with a featured image renders it through `showResponsiveImage`
   (`srcset`, `width`/`height`, `loading="lazy"`) with empty `alt`, inside a
   link hidden from assistive tech (the title link is the accessible one). A
   post without one renders no `<img>` and the hue tile (`fd-media fd-tile`)
   instead. (The first draft's eager, high-priority lead image needs a macro
   option; deferred.)
4. Every post item shows its blog name, a `<time datetime="YYYY-MM-DD">`, its
   title (bare, as stored) and its excerpt through `$utils.escapeHTML` when it
   has one. An escaping fixture with `"` and `&` in title, excerpt and blog
   name renders each exactly once escaped.
5. "N min read" equals `ceil(words / 230)`, minimum 1, for a fixture of known
   length.
6. Exactly three house slots (`data-slot` a, b, c), each spotlighting a
   blog no shelf shows; the body contains no `adsbygoogle` and no
   `googlesyndication`; the CSP is unchanged. (Phase 2 adds: with the AdSense
   properties set, each slot an ad with an "Advertisement" label and its
   `min-height`, none before the headline section.)
7. The newsletter band and rail card render only when the site space has a
   newsletter list (`newsletterListUuid`); otherwise neither does.
8. Every href is absolute or context-path-neutral (the existing test at
   `/pressroom`), and the browser suite passes at both `/` and
   `-Dit.context.path=pressroom`.
9. Browser: at 360px and 1280px widths `document.documentElement.scrollWidth`
   equals the viewport width (no sideways scroll), in light and dark.
10. `StrictReferences`: the new references are resolved or added to
    `EXPECTED_REFERENCES` with a reason; no Velocity reference prints
    literally.
11. The page is the same whatever the site space's theme: both rendering
    themes produce `<body class="fd-front">` and the same markers.
12. Every eligible post on a small site appears exactly once on the page.

## Out of scope

Post pages and blog pages; turning ads on (phase 2); hand-promoted posts
(the "promoted first" seam in `pickFrontPage`); "Surprise me"; topic hubs or
tag pages; relative dates.

## Decisions (owner, 2026-11-03)

- **D1, yes.** The lead title goes to 40px and the site name to 28px; the
  design may take further careful exceptions as it evolves.
- **D2, yes.** Per-blog hues as in the mockup.
- **D3, the site space's list.** The sign-up uses the newsletter list set
  in the site space's Weblog Settings, and is hidden until one is set.
- **D4, one page.** The shared template is the redesign; frontpage's own
  copy is gone, so the front page no longer depends on the site's theme.
- **D5, all three slots**, showing house content until AdSense approval.
- **D6, fixed** as above.
