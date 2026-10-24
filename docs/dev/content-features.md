# SEO, travel, shortcodes and plugins

Developer notes moved out of `CLAUDE.md` (which is sent with every
request) so they are read only when this area is being changed. Section
names are unchanged, so an older "see CLAUDE.md, <section>" pointer
resolves through the index at the end of `CLAUDE.md`.

## SEO (Stage 2 Wave 1)
- Per-entry SEO fields on `WeblogEntry` (metaTitle, searchDescription,
  canonicalUrl, noindex, featuredImageId, ogImageId), edited in the entry
  editor's "SEO & Social Sharing" card with featured/social image pickers.
- `#showSeoHead` (`WEB-INF/velocity/weblog.vm`, called from every bundled
  theme head) emits meta description, canonical, robots noindex, Open
  Graph/Twitter card and JSON-LD; `#showResponsiveImage` is the theme-side
  `<picture>`/srcset emitter.
- `SeoController` serves `/robots.txt`, `/sitemap.xml` (index) and
  `/sitemap-<handle>.xml` (via `*.xml`; a middle-wildcard servlet pattern is
  illegal).
- **The per-weblog sitemap is cheap on purpose** (it was 42% of CPU and 59%
  of DB time under load, as ~3% of requests). Three layers, in order:
  `If-Modified-Since` at or after `weblog.lastModified` answers 304 before
  any read; `SitemapCache` (`cache.sitemap.*`) holds one finished document
  per weblog handle and absolute weblog url, valid only while its stamped
  `lastModified` still matches, and never one over `cache.sitemap.maxBytes`
  (2 MB; such a document is served but rebuilt each time); a rebuild reads entries through
  `WeblogEntryManager.getSitemapEntries` -- one native query of five columns
  with the featured image's privacy resolved by a join, never an entity or
  a per-image lookup. Anything that changes a sitemap's content must
  therefore bump `weblog.lastModified` (entry publish/update/unpublish/
  trash/delete, page save/delete, the directory privacy toggle all do);
  anything that changes its urls (`site.absoluteurl`, custom domain,
  context path) changes the cache key instead. Pinned byte for byte by
  `SeoControllerSitemapTest`.
- **Business profiles.** A site admin owns shared `Business` rows
  (`/roller-ui/admin/businesses.rol`); a blog picks one in Settings and may
  add a place (`placeType` LodgingBusiness, locality, region, country,
  lat/lng, its own booking URL). `BusinessJsonLd` (via
  `UtilitiesModel.businessJsonLd`/`businessPublisherJson`) emits, from
  `#showSeoHead`: on the home page a **second** `application/ld+json` block
  (the LodgingBusiness place node whose `parentOrganization` is the business
  node, or the business node alone when no place is set), and on an entry
  page `publisher` on the BlogPosting. JSON is built with `escapeJson` in
  Java; blank fields are omitted, never emitted as `""`.
- `#showBusinessCard` renders the footer `<aside class="business-card">`
  (name, locality, `tel:` link, booking link). `BookingLink` is the one place
  UTM parameters are added (before any `#fragment`, never duplicating an
  existing `utm_source`), shared by the card and `[cta]`. A booking URL must
  be a public absolute http(s) URL with a real TLD: localhost and intranet
  hosts are refused on purpose (the entry sanitizer deletes anchors that fail
  the same validator, so they could never work through `[cta]`), and both
  admin forms say so. A trailing telephone extension (`ext`, `ext.`,
  `extension`, `x`) becomes the RFC 3966 `;ext=` parameter of the `tel:` link.
- `[cta]` without `href` falls back to the weblog's booking URL, then the
  business's.
- **SEO checks are one rule set, `business/seo/EntrySeoChecks` (fields,
  duplicates) and `SeoBody` (rendered body)**, behind three surfaces: `audits
  seo` (field codes only, never renders a body: it covers the whole weblog),
  `audits seo_entry` (`GET .../audit/seo/entries/{id}`, everything) and the
  body sentences in `entries preview`'s `warnings` (spec
  `docs/superpowers/specs/2026-10-24-agent-editing-and-seo-design.md`, section
  6). A new rule goes there, not into a surface. Lengths read `SeoLimits`
  (the editor's counters do too), count code points, and `title_long`
  measures what the themes' `permalink.vm` prints: the meta title when
  non-empty, else the *unescaped* entry title, " — " and the weblog name; a
  theme that changes its `<title>` changes `EntrySeoChecks.pageTitle` with it.
  `SeoBody` reads HTML through the OWASP lexer like `Excerpts`, never a
  regex, and is pure: it says which entry anchors and image sources it needs
  resolved, and `ui.restapi.v1.SeoBodies` looks them up (published? which
  media file?) and lets a failed lookup propagate, since a check that could
  not run must not read as passed. `image_alt_missing` recognises the
  `[image]` fallback by `GalleryMarkup.fileNameAsAlt`, so the two cannot
  drift; `alt=""` is decorative and never reported.
- **Cache expiry.** Weblog pages and feeds expire only through
  `weblog.lastModified`, so saving a shared business touches `lastModified` of
  every weblog using it (`BusinessManager.saveBusiness`); otherwise their cards
  and JSON-LD would stay stale. The exception is `SiteWideCache` (the front
  page's blog), which ignores `lastModified` and is dropped only by
  `CacheManager.invalidate(...)`; `BusinessesController.save` therefore
  invalidates each weblog from `BusinessManager.getWeblogsUsing` after the
  flush. Unit-tested only (`BusinessManagerTest`, `BusinessesControllerTest`):
  `BusinessProfileIT` does not cover cache expiry.

## Travel (Stage 2 Wave 3)
- Three shortcodes in `business/shortcodes`, registered in
  `ShortcodeExpander.builtIn(...)` like `[image]`/`[gallery]`: `[map]` with
  `[pin lat lng label]` children (or `auto="<dir>"` mapping a directory's
  GPS-bearing photos, same private-directory refusal as `[gallery]`), `[faq]`
  with `[q]`/`[a]` pairs, and `[cta href label note]` (absolute http(s)
  only, UTM-tagged).
- `MapPins.parse` / `FaqBlocks.parse` are the single source of truth for both
  the shortcode renderers and the JSON-LD head emission, so map and itinerary
  cannot drift.
- Leaflet 1.9.4 (webjar, self-hosted) ships via `#showMapAssets`, the map
  twin of `#showGalleryAssets`; OSM tiles, no API key. Leaflet paints aborted
  tiles with a `data:` GIF, so every theme head's CSP carries
  `img-src * data:`, pinned byte-for-byte by
  `MapAssetsRenderingTest`/`PortfolioThemeRenderingTest`/`TravelThemeRenderingTest`.
- Per-entry structured-data type (`WeblogEntry.jsonLdType` + geo/event
  columns, V008): `EntryJsonLd` emits TouristAttraction/TouristTrip/Event/
  FAQPage as a SECOND `ld+json` block; the BlogPosting block is always
  emitted unchanged, so entries keep author/date/headline.

## Plugin System
- **The entry-plugin seam has nothing registered, on purpose.**
  `ConvertLineBreaksPlugin` (the only `WeblogEntryPlugin` ever shipped,
  `roller.properties` `plugins.page`), the "Plugins to apply" card and
  `WeblogConfig.jsp`'s per-weblog default are deleted;
  `weblogentry.plugins`/`weblog.defaultplugins` drop via V021 (idempotent),
  discarding live `"ConvertLineBreaks"` data. `PluginManagerImpl`,
  `WeblogEntryPlugin` and `Weblog.getInitializedPlugins()` **stay** as the seam
  `ShortcodeExpander` runs through (`applyWeblogEntryPlugins`); it and
  `WeblogEntry.render()` apply every registered plugin unconditionally. See
  Entry editing.

## Shortcodes
`org.apache.roller.weblogger.business.shortcodes` — `ShortcodeExpander` expands
`[name attr="v"]body[/name]` **unconditionally** at both render seams
(`WeblogEntry.render()` and `PluginManagerImpl.applyWeblogEntryPlugins`),
immediately before sanitization.

- `[image id=".." caption=".." alt=".."]` emits a responsive
  `<figure><picture>` (the media chooser pastes it).
- `[gallery dir=".." row=".." max=".."]` renders a media directory as a
  justified grid (`GalleryMarkup`, flex-grow `--ar` CSS from
  `#showGalleryGridStyles`) with a PhotoSwipe lightbox (`#showGalleryAssets`;
  EXIF overlay, captions); refuses private directories.
- `[video url=".." caption=".."]` (YouTube/Vimeo) matches the url against a
  provider allowlist and never fetches; it emits an inert placeholder `<div>`
  (`HTMLSanitizer` strips iframes) and `#showEmbedAssets` click-injects the
  real `<iframe>` once a reader opts in — nothing from the provider before that
  except the placeholder's thumbnail `<img>` (e.g. `i.ytimg.com`). Each theme
  CSP carries the provider's `frame-src`, pinned byte-for-byte by three
  rendering tests (as `img-src * data:` is).
- `[contact]`/`[subscribe]` use the same placeholder-div pattern (an inert slot
  `<div>`, never a `<form>`; `#showAudienceAssets` injects the real form — see
  Audience). `[contact]` carries a server-built `data-endpoint`; `[subscribe]`
  carries `data-list-uuid` and renders nothing without a uuid-shaped list uuid.
- `[[name ...]]` / `[[/name]]` escape a registered shortcode to literal text;
  unknown names and malformed input pass through byte-for-byte.
- New handlers implement `ShortcodeHandler` and register in
  `ShortcodeExpander.builtIn(...)`; the required `ShortcodeCard` (label + snippet) feeds the
  editor's Insert menu, so a new shortcode cannot ship undiscoverable.
- **A published entry's expanded HTML is kept** (`EntryHtmlCache`, see
  themes-and-templates.md, "Entry HTML cache") until its weblog's
  `lastModified` moves, so a handler's output may depend only on the entry,
  its own weblog's data, and the site/context url. A handler that reads
  anything else overrides the four-argument `render(..., ShortcodeReads)`
  and reports it: `[image]` reports its media file's weblog (the id is
  global, so it may be another weblog's, whose changes never move this
  stamp), and `[image]`/`[gallery]`/`[map]` report `readFrom(null)` when a
  lookup throws (the expander does too when a handler throws), so the
  degraded "left as written" output is never kept. A
  reported foreign or null read makes that render uncached.
