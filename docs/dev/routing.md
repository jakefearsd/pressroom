# Routing: redirects and virtual hosts

Developer notes moved out of `CLAUDE.md` (which is sent with every
request) so they are read only when this area is being changed. Section
names are unchanged, so an older "see CLAUDE.md, <section>" pointer
resolves through the index at the end of `CLAUDE.md`.

## The site root

`/` reaches `index.jsp` (the welcome file; the dispatcher serves `*.rol`
only). With `site.frontpage.weblog.handle` set, it forwards to that weblog's
rendering. Empty, it forwards to `/roller-ui/siteDirectory.rol`
(`SiteDirectoryController`), a public page with the site description and
the enabled, active weblogs by name, plus a "choose a front page" link for
an admin. It used to forward to the setup page, which is admin-only, so on a
site with no front page every reader got the sign-in form.

Empty is a real choice, not a gap: Global Config's front-page menu starts
with "None", and a save never fails over that field. With no weblogs the
menu used to render with no options, which a browser does not submit, and
the save refused every other setting with "Property
site.frontpage.weblog.handle is null".

## Redirects (301s for URLs that would otherwise 404)

`roller_weblog_redirect` (V028) maps one weblog-relative path to another
(301). Spec: `docs/superpowers/specs/2026-08-24-url-redirects-design.md`;
`docs/api/README.md`'s API section is the admin surface (deliberately no JSP
screen).

- **A rule is consulted ONLY where a 404 is already decided.** Four seams,
  all via `RedirectResponder.answer`: `PageServlet`'s
  `selectTemplate == null` and `rejectionReason != null` exits,
  `WeblogRequestMapper`'s two `sendError(SC_NOT_FOUND)` sites and its
  `calculateForwardUrl == null` decline. So a rule cannot shadow live content
  (`RedirectServingTest.aRuleCanNeverShadowLiveContent`). **Never add a
  fifth call site that has not already decided to 404.** The mapper's decline
  holds only by argument, so it is pinned too.
- **Every failure around a redirect degrades to the decided 404, never a
  500.**
- **The Location derives from the WEBLOG, never the request**:
  `URLStrategy.getWeblogURL` root + target + the original query string (via
  `PageServlet`'s forward attributes). Custom domains and the context path
  are the strategy's, never reimplemented.
- **One hop, enforced from both ends at save time** (a rule may neither
  begin where one ends nor end where one begins), plus open-redirect
  refusals: `//` prefix, schemes, query strings, backslash, control chars,
  external targets. Paths are normalized alike at save and match
  (leading `/`, trailing slashes stripped): `/old` and `/old/` are one rule.
- **Renaming a page's slug mints a `SLUG_HISTORY` rule** via
  `WeblogRedirectManager.recordRename`, from `savePage` in the SAME
  transaction, in order: delete rules on either slug, re-point rules
  targeting the old slug at the new one (or one-hop breaks under repeated
  renames), then mint; A→B→A converges to one rule. The old slug is
  `WeblogPage.loadedSlug`, a JPA post-load snapshot.
- **Entry anchors are immutable after create, so there is no entry-side hook** —
  `EntryBean` has no anchor field; `createAnchor` fires only when unset. The API's
  `anchor` sets one at create only (`EntryFieldRules.isValidAnchor`).
  Migrated permalinks are manual rules.
- **Hit bookkeeping is best-effort**: `recordHit` is one JPQL
  `UPDATE ... hit_count = hit_count + 1` (no load-modify-store) in its own
  try/catch. `hitCount`/`lastHitAt` are on the API list.
- **Every served redirect and rule mutation logs one INFO line on logger
  `roller.redirects`** (`WeblogRedirectManager.LOG_NAME`): rule id, origin,
  requested URI + query string, target, referer, user-agent — the last
  two attacker-controlled: `{}` slots only, nothing after them.
- **JPA trap**: `getNamedQuery` sets `FlushModeType.COMMIT` (queries never
  see pending writes), so `recordRename`'s mint-after-remove validation reads
  through `getNamedQueryCommitFirst` (AUTO), or a legal rename is refused for
  colliding with a rule just deleted. The hot `resolve` path stays no-flush
  on purpose.
- `removeWeblog` cascades `WeblogRedirect.removeByWeblog`;
  `WeblogOwnership.redirect` is the fifth by-id ownership member, used by
  `RedirectsApi` (404 never 403, POST permission, no update verb — delete and
  recreate).

## Virtual hosts (per-weblog custom domains)

A weblog with `weblog.custom_domain` set is served at that hostname's root:
`https://berlin.thelocalwiki.com/entry/x`, not
`https://blog.example.com/berlin/entry/x`. Spec:
`docs/superpowers/specs/2026-08-18-virtual-host-support-design.md`.

- **Resolution is host-first in `WeblogRequestMapper`, and the forward url
  still carries the handle**, so `PageServlet`, `WeblogPageRequest`, pagers,
  models and caches never learn vhosts exist. `VirtualHostRegistry` holds the
  hostname→handle map in memory (invalidated in `saveWeblog`/`removeWeblog`),
  so it works before `PersistenceSessionFilter` and `ControlPlaneHostFilter`
  can sit at filter order **35**, ahead of Spring Security (40), whose login
  302 would otherwise mint a session and CSRF token on the custom domain.
- **Generated urls derive from the WEBLOG, never the request**:
  `WeblogPageCache` keys on the handle and `#showSeoHead` bakes absolute
  canonical/`og:url` values into those bytes, so a request-derived url would
  stamp one host's canonical onto the other. All ten weblog-content url
  methods root through `MultiWeblogURLStrategy.getWeblogURL`;
  `AbstractURLStrategy`'s six `/roller-ui/` methods are control plane and
  must **not** become domain-aware.
- **`appProtectedUrls` is a strict subset of `rollerProtectedUrls`**, which
  mixes application paths (`roller-ui`, `api`, `themes`, `webjars`,
  `robots.txt`, `sitemap.xml`, `newsletter`) with weblog request contexts
  (`page`, `search`, `resource`, legacy `flavor`/`rss`/`atom`/`language`). On
  the site host a context is the SECOND segment (`/<handle>/page/x`); on a
  custom domain it is the FIRST, so reserving it there declines
  `/page/<theme>.css` and renders every vhost page unstyled. Only
  `appProtectedUrls` applies in vhost mode; only the `isWeblog()` half of the
  guard is skippable.
- **A custom-domain url still carries the servlet context path**: under a
  prefix its root is `https://host/roller/`, not `https://host/`.
  `getWeblogURL`, the mapper's path-form 301 and `SeoController.robots()`
  got this wrong; `ControlPlaneHostFilter` is right because
  `site.absoluteurl` carries the context path by convention.
- **`/roller-ui/rendering/**` and `/newsletter/**` are exempt from the
  control-plane redirect.** `ContactController`
  (`/roller-ui/rendering/contact.rol`) and `NewsletterController`
  (`/newsletter/subscribe`) are `fetch`-posted under `connect-src 'self'`; a
  redirect to the site host is cross-origin (CSP-blocked; a POST 301 has no
  body), breaking every `[contact]`/`[subscribe]` on vhost weblogs.
- **`site.absoluteurl` is required once any weblog has a custom domain**,
  read directly (`getPropertyWithConfigFallback`: the row, then
  `ROLLER_SITE_ABSOLUTEURL`) — never `getAbsoluteContextURL()`, whose
  `InitFilter` fallback can itself be a custom domain. Unset, the filter
  serves rather than redirects: never a loop.
- The path form 301s (never `sendRedirect`, whose 302 tells crawlers not to
  transfer ranking). The site sitemap index **omits** custom-domain weblogs:
  an index may only reference its own host's sitemaps.
