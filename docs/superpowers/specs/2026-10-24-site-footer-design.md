# Site footer (colophon): version, release date, copyright, privacy

Date: 2026-10-24. Status: design approved in brainstorm ("apply some
judgement and get it done"); calls marked **[my call]** were made by the
designer, not by Jake, and are open to his correction.

## Intent

One consistent block at the bottom of every page, blog and admin alike, that
carries compliance and version information: which Roller version is running,
when that version was released (Pacific time, Jake's local), the copyright
notice, and the privacy-policy link that services such as AdSense require.
It is "themed": each blog theme and the admin UI style it to fit, but the
facts and their wording come from one place.

## Goals

1. Every reader-facing HTML page rendered by the four shared themes
   (`journal`, `travel`, `portfolio`, `frontpage`) and every full-page admin
   screen shows the colophon.
2. The colophon shows: copyright line; privacy-policy link (when
   configured); `Roller <version>` and the release date.
3. Copyright is per blog, with a default for blogs that have not set one.
4. One site-wide privacy policy, served by Roller on its own domain as an
   ordinary Page, maintained in the normal page editor without a release.
5. A first draft of the blog privacy policy, forked from the wiki's
   [PrivacyPolicy](https://wiki.wikantik.com/wiki/PrivacyPolicy) and
   corrected to describe what Roller actually does.

## Non-goals

- AdSense (or any ad) code, a cookie-consent banner, a terms-of-service page.
  The privacy-link setting is the pattern a later "terms" link would copy.
- Blogs on a **custom** theme (imported templates): they do not get the
  macro call automatically; the owner adds it. `themes.customtheme.allowed`
  is off by default.
- Feeds, emails, API/MCP responses: no footer.
- Publishing the policy text on docker1. That is a production content change
  done after deploy, with Jake's go-ahead.

## Anti-goals (would be failure even if it "works")

- A footer that throws: on the install page, before bootstrap, or on the
  error page while the database is down. These are display paths; a failed
  lookup degrades to omitting that clause.
- A privacy link that is dead or wrong under a context path (`/roller`).
- Unescaped author text (the copyright field is user-typed).
- Renaming any CSS selector `Routes` pins (add, never rename).
- Two sources of truth for the facts: the JSP and the Velocity footers must
  render from one Java helper, so wording and dates cannot drift.

## What the colophon shows

```
© 2026 Wandering Pacific. All rights reserved.
Privacy policy · Roller 0.4.0 · released 2026-10-24
```

- **Copyright line**: the blog's copyright text if set, else
  `© <year> <blog name>. All rights reserved.` where `<year>` is the current
  year in `America/Los_Angeles`. On admin screens (no blog in context), the
  same default with `site.name`. **[my call]** No site-wide configurable
  default text; the blog name is the safe generic holder.
- **Privacy link**: shown only when the privacy setting is set.
- **Version**: `Roller <ro.version>`. Admin screens keep the short (7-char)
  revision they show today, `Roller 0.4.0 (abc1234)`; reader pages omit it.
  **[my call]**
- **Release date**: `ro.buildTime` (epoch millis, stamped by the
  buildnumber plugin; the release image is built by CI on the `v*` tag, so
  this is the release date), formatted `yyyy-MM-dd` in
  `America/Los_Angeles`. **[my call]** ISO date, locale-neutral; the zone is
  a single constant, not configuration. A local dev build shows its own
  build date, which is fine. Missing or unparsable `buildTime` (`UNKNOWN`,
  an unfiltered `${timestamp}`) omits the `· released …` clause.
- All visible words go through the message bundle (`footer.*` keys), for
  both JSP and Velocity.

## Copyright field

- New nullable column on the weblog table via `V038__weblog_copyright.sql`
  (idempotent: `ADD COLUMN IF NOT EXISTS`), max 255 characters, plain text.
- Edited on the blog Settings screen (`WeblogConfigController` /
  `WeblogConfigBean`), with a hint showing the default it falls back to.
  Blank or whitespace-only is stored as null (falls back).
- Exposed wherever `tagline` is in `/api/v1` (`AdminDtos.WeblogView`,
  `WeblogPatch`, and create if tagline is there), with the same length
  validation as a field error. MCP: mirror only if an existing MCP tool
  already edits weblog settings. **[my call]**
- Saving goes through `saveWeblog`, which bumps `lastModified`, so cached
  pages pick it up.

## Privacy-policy setting

- New runtime property `site.privacy.page` on Global Config: the **slug of a
  published Page on the front-page blog** (`site.frontpage.weblog.handle`).
  Blank means no privacy link anywhere.
- Validated at save: non-blank must resolve to a published page on the
  front-page blog; otherwise the save is refused with a field error and the
  old value kept (fail closed: a lookup that could not run is an error, not
  a pass). Also refused when no front-page blog is configured.
- The link URL is built through the URL strategy (never string-concatenated
  with `/`), so it is correct at `/` and under a context path.
- **[my call]** No per-request existence check. A page unpublished after the
  setting was saved leaves a dead link until the setting is changed; the
  Global Config hint says so.
- **Cache expiry**: Global Config save does not expire rendered pages today
  (see `docs/dev/themes-and-templates.md`, render-cache expiry). Changing
  this setting must reach every blog's already-cached pages; the mechanism
  is the builder's (touching every weblog's `lastModified` is acceptable at
  10-50 blogs).

## Where it renders

- **One helper** in Java produces the facts (version, short revision,
  formatted release date, copyright line for a weblog or the site, privacy
  URL or null). Exposed to Velocity (a model or a method on an existing
  site-level model) and used by `tiles/footer.jsp`. Naming is the
  builder's.
- **Velocity**: a macro in `WEB-INF/velocity/weblog.vm` (e.g. the
  showColophon macro) that each shared theme's footer calls as the last thing
  inside its existing `<footer>`. Existing footer content (search,
  subscribe, business card) stays. `frontpage`'s "Powered by Roller" is
  replaced by the colophon.
- **Admin**: `tiles/footer.jsp` renders the colophon; every full-page tiles
  layout that includes `tile_footer` gets it (main menu, tabbed, simple,
  login, error, install). Popup excluded. The install page and pre-bootstrap
  paths render version and date only, never touching the database.
- **Styling**: a shared class (e.g. `colophon`) styled small and muted in
  each theme's CSS and in the admin stylesheet, using that theme's existing
  tokens.

## Privacy-policy text

- Draft at `docs/legal/blog-privacy-policy.md`, Markdown ready to paste
  into a Page. Baseline: the wiki's PrivacyPolicy (effective 2026-10-14),
  same voice and structure, scope changed to blog.wikantik.com and every
  blog it hosts.
- Every factual claim is checked against the code and deploy config before
  it is written, the way the wiki's was: cookie names, attributes and
  lifetimes; Umami analytics (what it records); Google reader sign-in (what
  is read and stored); reader comments; newsletter sign-up via listmonk;
  outgoing mail provider; Cloudflare; Google Fonts or other third-party
  fetches by the themes; embeds (maps/OSM, video); logs and their
  retention; backups (`deploy/` config); account deletion paths. Anything
  that cannot be verified is marked `TODO(Jake):` rather than guessed.
- An "Advertising" section stating that the blogs currently show no ads,
  with a note of what AdSense will require the section to say when it is
  turned on.

## Acceptance criteria

1. With `ro.version=0.4.0` and `ro.buildTime` = 2026-10-25T03:00:00Z, the
   helper reports release date `2026-10-24` (Pacific, across UTC midnight).
   With `UNKNOWN` or `${timestamp}`, the date is absent and nothing throws.
2. A blog with copyright `© 2024–2026 Maiia <script>` renders it
   HTML-escaped exactly once. A blog with none renders
   `© <Pacific year> <blog name>. All rights reserved.` (name escaped once).
3. Every page-type template of the four shared themes calls the colophon
   macro (a test enumerates templates from each `theme.xml`, so a new
   template without it fails).
4. Every full-page tiles layout renders the colophon; a test enumerates the
   layouts and excludes only popup.
5. `site.privacy.page` blank: no privacy link on blog or admin pages. Set to
   a published page's slug: the link points at that page's URL on every
   blog page and admin page, at `/` and under `-Dit.context.path=roller`.
   Set to an unknown or draft slug, or with no front-page blog: save refused
   with a field error, previous value kept.
6. After the setting changes, a blog page cached before the change renders
   the new link on the next request, without restart.
7. The copyright field round-trips through the Settings screen and
   `/api/v1` (view and patch); >255 characters is a field error; blank
   stores null.
8. `V038` applies cleanly twice (idempotent) through the test harness.
9. The install page and the error page with the database unavailable render
   the version line without an exception.
10. A browser IT asserts the colophon (version text and privacy link) on a
    blog page and an admin page.
11. `docs/legal/blog-privacy-policy.md` exists; each cookie it names is
    found by grep in the code or config, and unverifiable claims carry
    `TODO(Jake):`.
12. All gates green: coverage floors, diff coverage, PMD/CPD/SpotBugs,
    `-Xlint`, i18n ratchets.

## Left to the builder

Helper and macro names, the cache-expiry mechanism for criterion 6, CSS
details per theme, the Settings-screen layout of the new field, and the
exact `footer.*` message keys.
