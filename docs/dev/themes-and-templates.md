# Themes and templates

Developer notes moved out of `CLAUDE.md` (which is sent with every
request) so they are read only when this area is being changed. Section
names are unchanged, so an older "see CLAUDE.md, <section>" pointer
resolves through the index at the end of `CLAUDE.md`.

## Theme System
- **Shared themes** live under `/themes/`: `portfolio` (dark photo grid),
  `travel` (light travel guide cards) and `journal` (the default: `qj-*`
  vocabulary, light+dark, self-hosted IBM Plex — `ibm__plex-serif` joins
  the Sans/Mono webjars in `app/pom.xml`; the CSS `url()`s carry the webjar
  version, so a bump edits three stylesheets and `WebjarReferenceTest`). A theme
  with its own webfont must add `font-src 'self'` to its CSP on top of
  `CSP_STANDARD`, or the browser refuses every `@font-face`;
  `JournalThemeRenderingTest` pins the string byte-for-byte.
  `ThemeCspCoverageTest` enforces it for any theme CSS referencing
  `@font-face`, checking `/webjars/` fonts via
  `getResource("META-INF/resources/webjars/...")`, not the filesystem (Plex
  is classpath-served, not under `webapp/`). Every shared theme, `frontpage`
  included, ships a `_page` template (see Themes); `journal`'s is covered for
  the `[contact]` slot and the draft-404 case.
- **Retired**: `basic`, `fauxcoly`, `gaurav` — directories deleted;
  `V018__retire_legacy_themes.sql` moves any weblog still on one to `journal`
  (idempotent; a custom-theme weblog, `'custom'` in `editortheme`, is
  untouched). The fixture theme (`TestUtils`, seeded IT weblog) is
  `journal`; `ThemeMatrixIT` lists `journal`/`portfolio`/`travel`. Never
  reintroduce a theme directory or a migration writing `editortheme` back
  to one of these ids.
- `frontpage` (the multi-weblog aggregator theme) uses the `fd-*` front-door
  design, same `font-src` addition and self-hosted Plex as `journal`.
  `_blogprofile.vm` and `_blogs.vm` are deleted with their `theme.xml`
  registrations; directory cards link to the weblog.
- **Entry titles are stored HTML-escaped; page titles are stored raw.**
  `EntryBean.copyTo` runs `StringEscapeUtils.escapeHtml4` on the title once
  at save, so `WeblogEntry.getTitle()` is already escaped and every theme
  must emit `$entry.title` bare — `$utils.escapeHTML($entry.title)`
  double-encodes to `&amp;amp;`. `PageBean.copyTo` copies `title` through
  unescaped, so `WeblogPage.getTitle()` is raw and every page template must
  call `$utils.escapeHTML($model.page.title)` (or `#showPageTitle`) itself,
  as all three themes' `page.vm` do — skipping it is stored XSS once a title
  carries `<script>`. `EditorJspEscapingTest` pins the admin-JSP side:
  every author-controlled EL expression, `entry.title` included, goes through
  `fn:escapeXml` (JSP fields get no save-time escaping).
- **The `bean.*` form expressions are the dangerous twins of the display
  expressions**: `value="${bean.name}"`, `<textarea>${bean.text}</...>`,
  inline-JS `'${bean.link}'` are stored/reflected XSS. Two traps:
  `EntryBean.copyFrom` runs `StringEscapeUtils.unescapeHtml4` on the title
  (the author edits what they typed), so `bean.title` is raw though
  `entry.title` is stored escaped; and a wrapper that escapes does not make
  the pojo safe — `Weblog.getName()` is raw, `WeblogWrapper.getName()`
  escapes on read, `WeblogConfig.jsp` reads the bean. Guard a field by adding
  it to `EditorJspEscapingTest`'s `AUTHOR_CONTROLLED` list in
  **both** spellings. `<spring:message arguments="...">` does **not** escape
  (`defaultHtmlEscape` is unset), so an argument is an injection point too.
- Custom themes: per-weblog (see Themes); template types `.vm`, stylesheets,
  resources; hot reload in dev.

## Themes
- A weblog runs either a **shared** theme (id from `themes/<id>/theme.xml`) or
  `WeblogTheme.CUSTOM`. Switching to custom **imports** the shared theme's
  templates as the weblog's own rows and is one-way (it stops tracking the
  shared theme). `ThemeIT` therefore works on weblogs it creates itself;
  never switch the seeded IT weblog.
- `ThemeEdit.jsp` hides its Save buttons until its JS sees a change; pick
  the theme (or the radio) first to reveal the right one.
- A theme switch reaches readers via `saveWeblog` bumping `lastModified`, not
  `CacheManager.invalidate` — see Templates on `WeblogPageCache`.
- **`themes.customtheme.allowed` (default `false`) gates only two of the
  Design tab's three items.** `themeEdit` (shared-theme selection, safe and
  reversible) is ungated; only `stylesheetEdit` and `templates` carry
  `enabledProperty="themes.customtheme.allowed"` in `editor-menu.xml` (the
  whole `tabbedmenu.design` group used to). `MainMenu.jsp` splits its theme
  button the same way: shared-theme link unconditional, custom-theme link
  behind the flag.
- **`themes.customtheme.allowed` is enforced in `ThemeEditController`, not
  just the menu** — a hidden menu entry stops nobody posting to
  `themeEdit!save.rol` directly. Only the `WeblogTheme.CUSTOM` branch of
  `ThemeEditController.save` checks it; the shared-theme branch never has. A
  weblog already on a custom theme is grandfathered (disabling the option
  must not strand it).
- `journal`, `travel`, `portfolio` and `frontpage` each ship a `_page` template
  (`themes/<id>/page.vm`; frontpage's is built on its `_header`/`_footer`,
  `article.fd-page.roller-content` with `.fd-prose`, and keeps the shared
  header's embed assets so a `[video]` still plays); a `WeblogPage` reaches it via the
  `StaticThemeTemplate` fallback, so a static page renders in the theme's
  own chrome (travel's `tg-header`, portfolio's dark frame), not the fallback
  template's bare `<h1>`. `TravelThemeRenderingTest`/
  `PortfolioThemeRenderingTest` pin this: a page carrying `[contact]` must
  render through the theme's header/prose classes and ship the audience
  assets in the head, with an `assertFalse` on the fallback `<h1>`.
- **The `<title>` rule, every theme.** The separator is " — " (an em dash,
  the one the admin layouts use): "Entry — Weblog", "Page — Weblog",
  "Search: q — Site". On a permalink or a page, a meta title set in the
  editor's SEO drawer is the WHOLE `<title>`, with no weblog name appended,
  because the editor's search preview shows it alone. The shared fallback
  `templates/weblog/page.vm` follows the same rule.
- **The heading rule, every theme.** A list page (home, category, tag,
  date archive, search) is titled by the site name, an `h1`; each entry
  title in the list is an `h2`. A reading page (permalink, page, frontpage's
  `_page`) is titled by the entry or page title, an `h1`, and the site name
  becomes a `<p>` with the same class, so it looks the same and a body's
  Markdown `##` lands one level under the title. A category or tag view
  adds an `xx-view` caps label plus an `h2.xx-view-name`.
  `ThemeChromeConsistencyTest`/`ThemeChromeRenderingTest` pin both rules.
- **Theme copy stays English on purpose.** Chrome words that already have
  bundle keys use them (`pager.newer`/`pager.older`, `generic.edit`,
  `macro.weblog.searchbutton`, `mainPage.category`, and the frontpage
  header's `navigationBar.login`/`navigationBar.logout`/`mainPage.mainMenu`,
  shared with `bannerStatus.jsp`). The theme's own voice — empty states,
  prompts, step titles, "Tagged", "Skip to content", "Pinned" — is written
  into the templates in English, a deliberate decision rather than a gap;
  `ThemeChromeConsistencyTest` pins the hard-coded words.

- **Titles, headings and voice (all bundled themes).** A `<title>` names
  the view first and the weblog second, joined by an em dash: "Entry —
  Weblog", "Category — Weblog", "Tagged x — Weblog", "October 2026 —
  Weblog" (date archive, from `$model.weblogDateLabel`); an entry's or
  page's own meta title stands alone. On a reading page (permalink, page)
  the entry or page title is the `h1` and the site name steps down to a `p`
  with the same class; on a list the site name is the `h1` and the view
  heading (`xx-view-name`) an `h2`. A list view (category, tag, date
  archive) says what it is in an `xx-view` block and has its own empty
  sentence. The themes' fixed reader copy ("Tagged", "Archive", the empty
  sentences) is English by decision, not bundle keys.
- **The line under a title in an entry list is `$entry.excerpt`, every
  theme** (`WeblogEntryWrapper#getExcerpt`): the author's Summary (editor
  drawer, API `summary`) rendered and shown as plain text, else the search
  description, else the post's opening (`Excerpts.opening`) — the first two
  sentences of the writer's own paragraphs, a third when two come to under
  100 characters, at most 300 (a sentence that would run past that is left
  out, or, while the opening is still under 100, cut at a word with an
  ellipsis), never a heading, code block, caption,
  quotation, list item or shortcode/callout output (any `div` with a class).
  It is plain text, so a template emits it as `$utils.escapeHTML($excerpt)`,
  once — the old fallback, `removeHTML` over `displayContent`, returned
  already-escaped text that had to be emitted bare. journal's `qj-sum`,
  travel's `tg-card-excerpt` and portfolio's `pf-row-sum` all read it.
- **Portfolio lists in runs.** An entry with a featured image is a card in
  a justified `.pf-grid`; one without is a row (`article.pf-row`: an `h2`
  link, a mono date and category line, the excerpt) in a `.pf-list` in the
  reading measure. `_day.vm` opens a new run whenever the kind changes,
  carrying the open one across days in `$pfRun`; `weblog.vm` and
  `searchresults.vm` empty it before the pager and close the last run after,
  inside one `div.pf-entries`. A text-heavy weblog on portfolio is a list;
  the old no-image tile showed a title and nothing else.
- The search pager macro emits its own labelled `<nav class="search-pager">`
  and the hit line `p.search-hits`; a theme wraps it in a `div`, never a
  second `nav`, and styles both (`ThemePolishTest`).

## Templates
- Add/edit/remove live in `TemplatesController` and `TemplateEditController`;
  both resolve client ids through `BaseController.lookupTemplate` (pinned by
  their `*ControllerTest`s).
- A CUSTOM template gets `link = name` and is served publicly at
  `/<handle>/page/<link>` — including on a weblog running a *shared* theme,
  via `WeblogSharedTheme.getTemplateByLink`'s fallback to the weblog's own
  templates. `TemplateIT` asserts that end to end.
- **`weblog.lastModified` only moves forward.** Every bump goes through
  `Weblog.touchLastModified()` (`saveWeblog`, the business-profile touch):
  now, or 1 ms past the stored value when the clock has not moved past it.
  Two saves in one millisecond once stamped the same value, and a sitemap
  cached between them was served after the second (the column is
  `timestamp(3)`, so the 1 ms step survives storage;
  `WeblogLastModifiedTest`). Never `setLastModified(new Date())` directly.
  Not covered: two concurrent transactions that both read the same old
  stamp in the same millisecond still write equal values.
- **Render-cache expiry.** `saveTemplate`/`removeTemplate` bump
  `weblog.lastModified`, the only thing that expires a rendered page:
  `WeblogPageCache` and `WeblogFeedCache` both pass
  `constructCache(null, ...)`, register no CacheHandler, and are never reached
  by `CacheManager.invalidate(...)` — they expire only lazily against
  `weblog.lastModified`. `SiteWideCache` is the sole eager render cache: it
  registers itself (`constructCache(this, ...)`) and `CacheManager` drops it
  wholesale. `RenderCacheHandlerRegistrationTest` pins the split. The two lazy
  caches share `LazyExpiringRenderCache` (config read, backing `Cache`,
  `get`/`put`/`remove`/`clear`); each subclass keeps its `CACHE_ID`, singleton
  and `generateKey`. `SiteWideCache` is deliberately outside that hierarchy —
  its `get` takes no timestamp, having no per-weblog expiry.
- **A Global Config save expires nothing by itself**, so a setting that
  reaches rendered pages must touch weblogs. The footer's privacy link is
  built from `site.privacy.page` (a `PageReference`: `slug` is a site page,
  `handle/slug` a blog's) and `site.absoluteurl`, and the front door is
  `site.frontpage.weblog.handle` / `site.frontpage.page`: when any of the four
  changes, `GlobalConfigController`
  touches and saves every weblog, and the site space, via `touchLastModified()` and then calls
  `CacheManager.invalidate(weblog)` for each, so every blog's cached pages and
  feeds pick the link up on the next request. The invalidate is what clears
  `SiteWideCache`, which serves the aggregated front page and ignores
  `lastModified`. Side effects, accepted: every blog's `Last-Modified` and
  sitemap `lastmod` move forward and each misses one conditional GET. The
  copyright year is baked into cached pages, so for up to an hour after New
  Year they show the old year.
- **A deploy is a change too.** `Last-Modified` (and the 304 check) for a
  weblog's pages, feeds and stylesheet is `weblog.lastModified` or this
  server's start, whichever is later (`ServingSince`,
  `RenderCaches.lastModifiedOf`). Dated by the weblog alone, a reader who had
  the page before a deploy got 304 and kept the old markup; 0.3.1's list rows
  reached nobody who had seen 0.3.0's tiles. `$model.weblog.stylesheet`
  carries `?v=<that time in seconds>`: Cloudflare adds `max-age=14400` to a
  `.css` the origin sends without `Cache-Control`, so a fixed address kept the
  old rules for four hours, on browsers and at the edge alike. The `v`
  parameter is a custom param, so it is part of the page-cache key; old
  values expire with the stamp they carried.
- **Remembered lookups ride the same stamp.** Every render asks
  `getTemplateByAction` for the stylesheet override (a custom theme for its
  templates too) and `getPublishedPages` for the nav; both answers change only
  when an author saves, and an absent override was never remembered (a
  profiled reader load ran ~786k template and ~278k page selects). Both
  managers now hold each weblog's answer as **ids** in a `WeblogStampedCache`
  stamped with `weblog.lastModified` and resolve them with `em.find` in the
  caller's own EntityManager (the shared cache answers; no entity outlives its
  unit of work). Two rules keep it right. Every change to a template's
  action/weblog or a page's status/order/existence must move the stamp
  (`saveTemplate`, `removeTemplate`, `savePage`, `removePage`, `saveWeblog` do;
  `WeblogLookupCacheTest` pins each path, theme switch included). And a unit
  of work with `JPAPersistenceStrategy.hasUncommittedWork()` (an open
  transaction, or a failed commit not yet released) neither reads nor fills:
  `importTheme` saves a template and then looks templates up in the same
  transaction, and its no-flush queries cannot see the new row while the
  weblog already carries the stamp about to commit. A remembered id that no
  longer resolves, a template whose action or weblog changed, or a page no
  longer published, is a miss: a bulk JPQL statement changes rows without
  moving the stamp (`removePages` is one, and also drops the slot after its
  commit, but a reader can land between the two). The stale-id tests in
  `WeblogLookupCacheTest` reach each check with the slot still in place. A
  deleted weblog's slots are dropped after its commit.
- **Entry HTML cache.** `EntryRenderer`'s `cached*` methods -- what
  `WeblogEntryWrapper` calls on every live render -- keep each published
  entry's text, summary and display content in `EntryHtmlCache`
  (`cache.entryhtml.*`: 16 MB total, one slot at most 1/16 of it, one hour;
  reads take no lock -- a cache-wide monitor convoyed every render under
  load -- and eviction is approximate LRU by one `tryLock` holder),
  so a page-cache miss no longer re-runs shortcodes + Markdown + sanitizer
  for every entry on a list page. A slot is keyed by entry id, sanitizer
  on/off, the weblog's absolute url (site url, captured address, custom
  domain) and the relative context url, and is valid only while the entry's
  text and summary (by value), `updateTime` and its weblog's `lastModified`
  all match. Not kept: drafts (their saves do not bump `lastModified`), a
  weblog with no stamp, any render while an entry plugin is registered, and
  a render whose shortcodes read another weblog's data or failed a lookup
  (`ShortcodeReads`, content-features.md, Shortcodes). **Previews never touch
  it**: the plain `transformedText`/`displayContent` methods (editor and API
  preview, newsletter) and a wrapper built with `PreviewURLStrategy` (the
  preview servlet) always render. `CacheManager` does not reach it, by
  design; a change that reaches entry output without moving `lastModified`
  is a stale-content bug for the page cache and this one alike (rendition
  regeneration was one; it now bumps). Off in `roller-boot-dev.properties`
  with the other render caches.
- **A Velocity resource loader that cannot report modification may not be
  cached; the two settings only ever change together.** With `cache=true`
  Velocity reuses a parse tree whenever `isSourceModified` says "not stale",
  so a loader answering a constant `false` pins every template for the life of
  the JVM — edits do nothing until a restart, with no error.
  `ThemeResourceLoader` used to answer that constant (hence
  `resource.loader.theme.cache` false); it now reports the theme's real disk
  timestamp, `0` → "modified" when it cannot tell, and is cached.
  `RollerResourceLoader` serves CUSTOM templates from database rows with no
  timestamp and still answers a constant `false`, so
  `resource.loader.roller.cache` must stay `false` until it reports a real
  timestamp. `LoaderCachingContractTest` probes every *cached* loader with a
  maximally stale resource and fails if one answers "unmodified"; loaders that
  need a collaborator to answer (`webapp`, which asks the `ServletContext` for
  a real file) are named there with a reason, the `QualityGatePomTest`
  convention.
- **Velocity is lenient, so deleting a Java member is a live hazard.**
  `velocity.properties` sets no `runtime.references.strict` and turns off
  `runtime.log.invalid.reference`: a reference to a deleted field or getter
  neither throws nor logs — it prints as literal text (e.g.
  `$entry.commentCount`) into the page (W1 shipped `journal/_day.vm`'s
  `$entry.commentCount` and `feeds.vm`'s
  `<comments>$url.comments(...)</comments>` this way). Any deletion of a Java
  member a `.vm` can reach (pojo, wrapper, model) must `grep`
  `app/src/main/webapp/themes` and `app/src/main/webapp/WEB-INF/velocity` for
  it before being called done.
- **A zero-argument macro name written bare inside a comment is INVOKED, not
  printed.** `#name` with no parentheses is a valid velocimacro call when
  `name` takes no arguments, inside a `//` JavaScript comment or a `/* */` CSS
  one alike (it bit `weblog.vm`'s live-preview script via a
  `#showPreviewShellScript` mention, and three themes' `*-custom.css` via
  `#showAudienceAssets`/`#showWeblogCategoryLinksList`). Write "the showX
  macro" in a comment, never `#showX` — `ThemeStylesheetTest` scans every
  theme stylesheet for the bare form and fails the build.

## The colophon (footer)

- Every blog page ends with the showColophon macro (`weblog.vm`), called as
  the LAST thing inside each theme's `<footer>` (after search, subscribe and
  the business card): `#showColophon($model.weblog)`. It renders the
  `Colophon` record from `$config.getColophon($model.weblog)` -- the same
  record `tiles/footer.jsp` renders -- with classes `colophon`,
  `colophon-copyright`, `colophon-meta`, `colophon-privacy`,
  `colophon-version`, `colophon-released`. Frontpage has no per-template
  footer: its `_footer.vm` carries the call and `weblog/page/directory`
  `#includeTemplate` it ("Powered by Roller" is gone).
- **Escaping**: every `Colophon` string is raw (the blog name is stored with
  markup stripped but `&` intact), so the macro escapes each once with
  `$utils.escapeHTML`. `$text.get` does not escape and `MessageFormat` does
  not re-parse arguments, so escape before passing; the year goes in as the
  string `"$colophonFacts.copyrightYear"` or it prints as `2,026`. Readers
  see `Roller <version>` only -- never the revision.
- **A new page template needs the call.** `ThemeColophonTest` reads each
  `themes/*/theme.xml`, and every `text/html` template not named in its
  `NOT_A_PAGE` set (day partial, preview shell, frontpage partials) must call
  the macro directly or `#includeTemplate` a partial that does. It evaluates
  the real macro with a stub `$config` for the omission rules, and
  `ThemeColophonRenderingTest` renders the three blog themes end to end.
- Styling is per theme (`body.qj/tg/pf/fd .colophon`), small and muted from
  that theme's own tokens.

## Content assets (callouts, code blocks, heading anchors)

The `showContentAssets` macro in `WEB-INF/velocity/weblog.vm` styles and
enhances Markdown content for readers. It is called beside the map assets in
all 18 heads that call those (each article theme's `weblog`, `permalink`,
`searchresults`, `page` and `preview` templates, frontpage's `_header`, and the
shared `templates/weblog/page.vm` and `preview.vm`). `ContentAssetsRenderingTest`
renders every one and also scans the sources, so a new head that calls the
map assets without this macro fails the build.

- **The markup it relies on** is `MarkdownRenderer`'s, kept by
  `HTMLSanitizer`. `> [!NOTE]` (and TIP, IMPORTANT, WARNING, CAUTION) becomes
  `div.markdown-alert.markdown-alert-note` with a `p.markdown-alert-title`.
  A fenced block becomes `pre > code.language-x`, or a bare `pre > code`.
  Every heading gets an `id`.
- **Scope.** Everything except the callouts and the `hljs-*` token classes
  applies inside `$rollerContentScope` only: `.qj-prose`,
  `.tg-entry-content`, `.pf-entry-content`, `.roller-preview-prose` (shared
  preview) and `.roller-content` (the shared page fallback's `<article>`).
  A custom theme opts in by putting `roller-content` on its content
  container. The scope sits in `:where()`, so a theme's own rule for the same
  element still wins. The heading anchor is the exception: it is specific
  enough to beat a theme's content-link colour.
- **Colour.** Each palette value is a `light-dark()` pair, resolved by the
  theme's own `color-scheme`. Each such declaration follows a fallback for the
  same property that is legible on any canvas: `inherit` for text, a
  `color-mix(in srgb, currentColor N%, transparent)` tint for backgrounds.
  Tints follow the text colour, so they suit any canvas. The only opaque
  backgrounds are the diff lines, and those set their text colour from the same
  scheme in the same rule. Both rules are pinned by
  `ContentAssetsRenderingTest`. The light titles of the callouts are one Primer
  step darker than their borders, because the GitHub base colours fall just
  under 4.5:1 on a tinted cream or paper canvas.
- **Contrast, measured** (WCAG ratio, worst case across portfolio, travel,
  journal light/dark and the UA canvas the frontpage fallback pages use):
  code text 12.2, line numbers 5.2, language label 5.8, copy button 11.0, inline-code chip
  10.7, table header 11.9. Token colours: light keyword #cf222e is the lowest
  at 4.6, every other token is 5.0 or better. Callout titles: light 6.1 or
  better, dark 4.8 (caution on journal dark) or better. Callout body text
  12.1 or better, heading `#` at its hover opacity 5.4. Portfolio: content
  link 7.7, hover 10.3, captions and quotes (`--pf-muted`) 6.0. Journal
  links: 5.8 light, 6.9 dark.
- **Script.** A classic bootstrap, shaped like the map assets. It injects
  `$url.site/roller-ui/scripts/roller-content.js` only after finding an
  unprocessed `pre > code` or content `h2`-`h4` with an id. Each call hands
  the bundle the selectors and the weblog's labels (`content.code.*`,
  `content.heading.anchor`, escaped into data attributes). Blocks and
  headings are marked once done (`data-roller-code`, `data-roller-anchor`),
  which makes it re-runnable. It chains onto `window.rollerPreviewInit`, so
  the editor's preview is enhanced after each swap. Highlighting never
  auto-detects: an unknown or absent language keeps its copy button and line
  numbers, with no colours. The gutter sits outside the `pre`, and the copy
  button copies `code.textContent` minus the trailing newline.
- **Custom domains.** `ControlPlaneHostFilter` 301s `/roller-ui/**` to the
  site host. A redirected script is cross-origin and every theme's CSP is
  `script-src 'self'`, so the filter exempts exactly
  `/roller-ui/scripts/roller-content.js`. The editor bundle beside it is
  still control plane.
- **Theme fixes that came with it.** Portfolio's `body.pf a` (inherit, no
  underline) stays for the chrome. Content links (`body.pf .pf-entry-content
  a`) get the accent colour, an underline and a hover state, and captions
  and quotes use `--pf-muted`. Journal's `.qj-prose a` (0,1,1) had never beaten
  `body.qj a` (0,1,2), so post links rendered in the ink colour. The fix
  prefixes it with `body.qj`. Both theme rules exclude `.cta-card` through
  `:where(:not(...))`, which adds no specificity, so the heading anchor still
  out-ranks them. Travel needed nothing. Frontpage's static pages render
  under `body.fd` through its own `_page` (`.fd-prose`); its previews still
  go through the shared fallback, whose body carries no `fd` class, so links
  there keep the browser default.
- Hex colours inside a Velocity template are safe only because no macro is
  named like one (`#d29922` is literal text). The same goes for the theme
  stylesheets, which Velocity renders too.
