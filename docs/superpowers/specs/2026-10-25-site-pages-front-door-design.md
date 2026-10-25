# Site pages and the front door — design

**Date:** 2026-10-25. **Status:** draft for review.

This is the first of three projects that came out of one conversation about
making the site easier to set up and to shape over time. The other two are
recorded at the end so their decisions are not lost; each gets its own design
before it is built.

## Why

Setting up blog.wikantik.com needed a guide at every step: the site's front
page is a blog setting, the privacy policy is a Page on whichever blog happens
to be the front page, its link is a slug typed into Global Config, and making a
blog the front page hid the only "Sign in" link (fixed in the colophon,
2026-10-25). The pieces work, but they only make sense once you know they
exist and how they depend on each other.

The site is also changing shape. Jake expects it to become a mix of
publishing and blogging: curated pages, written in Markdown, that introduce a
topic and point readers at blogs and posts, with the blog as one important
feature among others that may come later. The front page should be one of
those curated pages, not a blog standing in for the site.

## What we're building

**The site gets its own pages, separate from any blog.** An admin writes them
with the same Page editor, Markdown and shortcodes a blog's Pages use today.
One of them can be chosen as the front door, the page a visitor sees at `/`.
Others sit beside it: an About page, the privacy policy, topic introductions.

**The site picks its own theme** from the same themes blogs choose from. It is
a separate setting: changing the site's theme never changes a blog's, and the
reverse. When a reader clicks into a blog, that blog's own theme takes over.

## Goals

- A visitor at `/` can see a page Jake wrote, in a theme chosen for the site.
- Site pages exist independently of every blog, so no blog has to stand in for
  the site.
- The privacy policy can live at the site level, and its footer link keeps
  working through the upgrade without manual steps.
- Pages stay mixed: blogs keep their own Pages; site pages and blog Pages can
  link to each other by hand.

## Non-goals (this project)

- Cards and live lists (project 2). Until then a site page links to blogs and
  posts with ordinary Markdown links.
- The setup wizard (project 3).
- Any structure for multi-page guides (order, next/previous, contents). Jake:
  guides are linked by hand for now; structure may come once the use cases are
  known.
- Moving an existing blog Page to the site automatically. An admin recreates
  it as a site page (copy and paste); the old one can then be deleted or left.
- Automation API and MCP access to site pages. Wanted soon by the publishing
  agent, but a separate change (see Open follow-ups).

## Anti-goals

- A second page system. Site pages are Pages: same editor, same shortcodes,
  same sanitizer, same rendering path. Anything a blog Page can do, a site page
  can do, and a fix to one is a fix to both.
- A visible change on upgrade. Whatever `/` shows before the upgrade it shows
  after, until an admin chooses otherwise.
- Dead links on site pages. A theme's chrome must not offer a feed, archive,
  category list or search for "the site" as if it were a blog with posts.

## Decisions made with Jake

1. The front door is a site-level page, not part of a blog.
2. Its theme is chosen independently, from the same list blogs use. Entering a
   blog hands over to that blog's theme.
3. Curated pages are Markdown: mostly prose, with cards (project 2) mixed in.
4. Curated pages will be mixed: some at site level, some inside a particular
   blog, linking to each other where content spans them (a user guide, a
   how-to).
5. Multi-page guides are linked by hand for now.
6. Build order: this project, then cards, then the wizard.

## Calls I made (Jake to check)

- **The site space is a hidden, system-owned weblog.** It owns the site pages
  and carries the site's theme setting, which reuses Pages, the editor, themes,
  shortcodes, page caching and `lastModified` expiry instead of building them
  again. It never appears where blogs are listed: the site directory, the main
  menu's blog list, "create a blog" handle checks for users, the blog
  sitemaps' weblog list, newsletter and analytics per-blog settings. Its handle
  can never be taken by, or collide with, a user's blog. The handle is internal
  and never appears in a public page URL; asset URLs (the theme stylesheet,
  theme resources, media files) carry it, as `<ctx>/site-space/<context>/…`,
  because they reuse the per-weblog asset routes. `/site-space/` and
  `/site-space/<slug>` are 404.

- **Hidden by default, not hidden call by call.** The weblog queries leave the
  site space out unless a caller asks for it, so everything that enumerates
  weblogs (directory, menus, user and role lists, counts, search reindex,
  scheduled jobs, aggregation, API token scopes) skips it without each being
  told. Only the site Pages and Theme screens, the front-door and privacy
  lookups and page rendering ask for it.

- **The site space offers only Pages, Media and Theme.** Media is there so
  site pages can carry images. Every other blog screen (entries, comments,
  categories, blog settings, members) refuses it, even for a global admin, and
  only a global admin reaches the three it offers.

- **Site pages are served at `/<slug>`**, and the front door at `/`. So the
  privacy policy can be `/privacy` and an About page `/about`. Site page slugs,
  weblog handles and the app's reserved paths (`roller-ui`, `api`, `webjars`,
  `sitemap.xml`, …) share one namespace: a site page cannot take a slug that is
  a blog's handle or a reserved path, and a new blog cannot take a handle that
  is a site page's slug. A slug is taken whatever the page's status (a draft
  holds its slug). No clash can exist at upgrade, since no site pages exist
  before it; if one ever arises outside the editor (a direct database edit),
  the blog wins and the site page is not served at that slug.

- **The front door has one public address, `/`.** The page chosen as the front
  door answers at `/`; its own `/<slug>` 301s to `/`, and `sitemap.xml` and
  its canonical link name `/`.

- **Only global admins create, edit and delete site pages** and change the
  site theme. Blog authors see nothing new.

- **The site's name and description come from Global Config**
  (`site.name`, `site.description`), one source of truth, so a theme's header
  on a site page shows the site's name, and the colophon's default copyright
  holder on site pages is the site name.

- **The front door becomes one setting with three choices:**
  
  - **A site page** — that page renders at `/`.
  - **A blog** — today's `site.frontpage.weblog.handle` behaviour, unchanged.
  - **The site directory** — today's empty-handle behaviour, unchanged.
  
  On upgrade the choice is whichever of the last two is in effect now. The
  site-page choice is a new runtime property (the chosen slug) that, when set,
  wins over `site.frontpage.weblog.handle`; unset, today's logic runs exactly
  as before, so the upgrade needs no settings migration. If the chosen page is
  later deleted or unpublished, `/` falls back to the site directory: `/` is
  never a 404.
  The site directory stays reachable at `/roller-ui/siteDirectory.rol` whatever
  is chosen, so a landing page can link to it.

- **Themes for the site space:** journal, portfolio, travel and frontpage. The
  frontpage theme is the natural default: it already exists to render a
  site-wide page and is today barred from ordinary blogs for that reason.

- **The privacy setting becomes a page reference.** A bare slug (`privacy`)
  means a site page; `handle/slug` (`wikantik/privacy`) means a blog's Page.
  The same form is what project 2's cards will use (`page="about"`,
  `page="travel/start-here"`). A migration rewrites today's bare value to
  `<front-page handle>/<slug>` when a front-page blog is set, so the live link
  `/wikantik/privacy` keeps working untouched; Jake can later recreate it as a
  site page and point the setting at `privacy`. Validation in Global Config
  follows the same rule (the page must exist and be published). A reference
  that does not resolve at render time (no front-page blog, page deleted)
  omits the footer link, as an unset one does today.

- **Site nav:** site pages marked "show in nav" form the site's nav, exactly as
  a blog's Pages do in its theme today.

- **Custom domains are unaffected.** A blog on its own domain still serves that
  blog at its `/`; site pages live on the main site address only.

- **Site pages are in `sitemap.xml`.** They are not in the search index (blog
  Pages are not either).

## Admin experience

- The main menu gains a **Site** section for global admins, beside their blogs:
  **Pages** (the existing Pages screen and editor, acting on the site space),
  **Media** (the existing media manager, for site pages' images) and
  **Theme** (the existing theme picker, offering the four site themes).
- Global Config's front-page menu becomes the three-choice front-door setting.
  Choosing "A site page" offers the published site pages; if there are none it
  says so and links to creating one.
- The Page editor's preview, "view" link and permalink show the real public
  URL (`/<slug>`, or `/` for the front door).

## Acceptance criteria

Each is concrete enough to write a test against.

1. With the front door set to a site page, `GET /` returns that page's
   rendered content in the site theme, status 200, for an anonymous visitor.
2. A published site page with slug `about` answers at `/about`; a draft one is
   404, as a draft blog Page is today.
3. Changing the site theme changes how site pages render and changes no blog's
   rendering; changing a blog's theme changes no site page.
4. Saving a site page expires the cached front door and that page (the
   `lastModified` rule); a reader sees the change on the next request.
5. A site page slug equal to an existing blog handle or a reserved path is
   refused at save with a message naming the clash; creating a blog whose
   handle equals a site page slug is refused the same way.
6. The site space never appears in the site directory, in any user's blog list
   in the main menu, or as a selectable blog in the front-door blog choice.
7. A non-admin blog author cannot reach the site Pages or site Theme screens
   (the interceptor refuses, as for any weblog they have no role on), and the
   site space cannot be reached by its internal handle in a public URL (404).
8. After upgrade, with `site.frontpage.weblog.handle=wikantik` and
   `site.privacy.page=privacy`, the footer still links to `/wikantik/privacy`,
   and `/` renders exactly what it rendered before.
9. Setting the privacy reference to a bare slug links the footer to
   `/<slug>`; `handle/slug` links to that blog's Page; either is refused in
   Global Config when the page does not exist or is not published.
10. A site page rendered in each of the four site themes contains no link
    that 404s (no blog feed, archive, category or blog-search link for the site
    space), and shows the colophon with the Sign in / Main menu links.
11. Site pages appear in `sitemap.xml` with their `/<slug>` URLs.
12. With a site page `home` chosen as the front door, `/home` answers 301 to
    `/`; deleting or unpublishing `home` makes `/` show the site directory
    (200), never a 404.
13. A global admin opening any blog screen other than Pages, Media or Theme for the
    site space is refused; the site space is absent from every weblog list the
    admin UI and API show.
14. Both context paths: every URL above works under `/` and under
    `-Dit.context.path=roller` (CLAUDE.md, "No code may assume a context path").

## Left to the builder

- How the site space is marked (a column, a reserved handle, a flag) and how it
  is created (migration or first start), within the rules above.
- How the mapper tells `/<slug>` (site page) from `/<handle>` (blog), and the
  order of checks, provided criterion 5's namespace rule holds.
- Each theme's adjustments for criterion 10 (likely: chrome that tests whether
  the weblog has posts, or is the site space).
- Whether the frontpage theme's existing aggregated-entries mode is offered on
  the site space.
- Message keys and wording, following the existing i18n ratchets.

## Open follow-ups (not this project)

- Automation API and MCP access to site pages for the publishing agent, with
  the API's 404-not-403 rule for non-admin tokens.
- A "move this page to the site" action, if recreating by hand proves tedious.
- Retention clean-up for the privacy policy's promises (Umami two years,
  contact submissions one year), noted 2026-10-25.

---

## Later projects (decided with Jake, not yet designed)

### Project 2: cards and the live list

Shortcodes that place a card in a page's Markdown, between paragraphs of
prose.

- **Blog card:** what the blog is about, how often it posts, its latest post,
  a way to follow it.
- **Post or Page card:** one item picked by hand; image, title, summary.
- **Live list:** a blog, optionally a tag or category, and how many posts
  (`max`). Usually one, the most recent, so `max` defaults to 1; a list of one
  looks like a post card that keeps itself current.
- **Wiki and outside-link cards wait.** The card is built so a new kind of
  target can be added without reworking the others: the blog may become one of
  several features a card can point at.
- Cards for things inside Roller fill themselves in; a card never fetches from
  another site at render time. A card that reads another blog's data must use
  the uncached path (`ShortcodeReads`), or it goes stale when that blog
  changes.
- References use the form above: `page="about"`, `page="travel/start-here"`.

### Project 3: the setup wizard

- Step by step, in order. Each step says what the thing is, shows its current
  state, and has its form in place (or, for settings that live in the host's
  `.env` such as Google sign-in credentials, reports whether they are present
  and how to add them).
- Every step can be skipped except the few the site cannot run without (guess:
  site name and public address). A skipped step stays marked and offers itself
  again.
- Opens on an overview of every step and its state (done, skipped, not set),
  from which Jake can run through, resume, or jump straight to one step months
  later.
- Durable over time: a step edits the same setting as the ordinary admin
  screen, never a copy; steps are registered in one place; a test fails when a
  new site-level setting ships without a step (as `ShortcodeCard` makes every
  shortcode appear in the Insert menu).
- Likely steps: site identity; front door (theme, landing page, nav); legal
  (privacy page, copyright); blogs (description, theme); reader sign-in and
  comments; mail (with a test send), newsletter, analytics.
