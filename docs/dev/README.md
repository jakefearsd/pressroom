# Developer notes

Each file here holds the rules, history and known traps for one subsystem.
Read the relevant file before you change that area. When you learn something
the hard way, add it to the matching file. Most files started as sections of
`CLAUDE.md` and kept those section names, so a code comment that says
"see CLAUDE.md, <section>" resolves through the table below.

New to the code? Start with [`../../CONTRIBUTING.md`](../../CONTRIBUTING.md),
then [architecture.md](architecture.md).

## Foundations

| File | Covers |
|---|---|
| [architecture.md](architecture.md) | Runtime, the DI rules (`@Lazy Weblogger`, no service locator), packages, security architecture, search |
| [build-and-ci.md](build-and-ci.md) | Frontend bundles, test commands and fixture conventions, known flakes, the three CI tiers, parallel work in worktrees |
| [quality-gates.md](quality-gates.md) | Coverage floors, compiler warnings, PMD/CPD/SpotBugs, complexity, log-only and generic catches, shared state |
| [browser-its.md](browser-its.md) | The Selenium suite: harness, permutation coverage, class-parallel runs, both context paths |
| [database.md](database.md) | PostgreSQL setup, cache sizes, schema changes, entity ids, tags, anchors, uniqueness rules |
| [configuration.md](configuration.md) | Configuration files, development compared with production, configuration scope |

## Features

| File | Covers |
|---|---|
| [entries.md](entries.md) | Entry editing, the entry list, tag counts, trash, order and pins, strands, pages |
| [themes-and-templates.md](themes-and-templates.md) | Theme system, the built-in themes, templates and render-cache expiry, colophon, byline, search results, content assets |
| [media.md](media.md) | Uploads, renditions, `cwebp`, galleries |
| [content-features.md](content-features.md) | SEO and structured data, breadcrumbs, travel, plugins, shortcodes |
| [admin-ui.md](admin-ui.md) | Admin UI conventions, route markers, categories |
| [comments.md](comments.md) | Reader comments, moderation, the rulings that amend the spec |
| [sso.md](sso.md) | Reader sign-in with Google/OIDC |
| [passwords.md](passwords.md) | Password hashing, the dev credential, the admin account tool |
| [routing.md](routing.md) | Site root, blog front doors, redirects, virtual hosts |
| [discovery.md](discovery.md) | The discovery front page, "more from the site", places |
| [audience-and-analytics.md](audience-and-analytics.md) | Newsletter (listmonk), analytics (Umami), Traffic pages |
| [api.md](api.md) | `/api/v1` internals, problem codes, MCP, agent accounts. The public reference is [`../api/README.md`](../api/README.md). |
| [umami-api.md](umami-api.md) | Umami's HTTP API as observed against the pinned image |

## History

| File | Covers |
|---|---|
| [pressroom-cutover.md](pressroom-cutover.md) | The one-time 0.4.x to 0.5.0 rename cutover, per host |
