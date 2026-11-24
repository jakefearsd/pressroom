# Pressroom

Pressroom is a multi-user blog server for a small business that runs a
handful to a few dozen blogs from one installation. You write in Markdown,
each blog gets its own theme, domain and settings, and readers get comments,
a newsletter and site search, with no tracking cookies. Scripts and AI agents
publish through the same rules as people, over a REST API and an MCP endpoint.

It runs as one Spring Boot executable WAR on PostgreSQL, and production
deploys from a published container image.

**Releases:** [GitHub Releases](https://github.com/jakefearsd/pressroom/releases) ·
**License:** Apache 2.0 · **Java:** 25 · **Database:** PostgreSQL 18

**Origins.** Pressroom is an independent fork of [Apache Roller](https://roller.apache.org), <!-- rebrand:keep -->
which was created in 2002 and is maintained by the Apache Software Foundation.
Pressroom is not affiliated with or endorsed by the ASF. Since the fork it has
been cut down a long way: PostgreSQL only, Markdown only, one Spring container,
no Planet aggregator, no LDAP. It has also gained the features listed below.

---

## Start here

| You are... | Read |
|---|---|
| **Writing or running a blog** on an existing Pressroom | [User guide](docs/guide/README.md): writing, pages, media, themes, comments, newsletter, users, settings |
| **Running the server** (install, upgrade, back up, TLS, domains) | [Deployment runbook](docker_deployment.md) |
| **Changing the code** | [CONTRIBUTING.md](CONTRIBUTING.md), then the [developer notes](docs/dev/README.md) |
| **Publishing from a script or AI agent** | [API and MCP reference](docs/api/README.md) |
| **Changing the admin UI's look** | [Design system](docs/design/design-system.md) |

The full map of the documentation is [docs/README.md](docs/README.md).

---

## What it does

### Writing
- Markdown editor (CodeMirror 6) with live preview, syntax-highlighted code
  blocks, GitHub-style alerts and heading anchors
- Drafts, review (pending), scheduled and published entries; revision history
  with restore; a trash you can recover from
- Categories, tags, pinned posts, a hand-set post order, and **strands**
  (series of posts, each part linked to the others)
- Site pages and per-blog pages with their own order, revisions and trash
- Shortcodes for galleries and other embeds; featured images; per-entry SEO
  fields with built-in checks

### Media
- Uploads in folders; responsive image renditions (WebP when `cwebp` is
  installed), cropping, and image import from a URL

### Look and feel
- Built-in themes: **journal**, **portfolio** and **travel**, plus
  **frontpage** for the site-wide front page
- Standard or wide page width per blog; editable Velocity templates and
  stylesheet for the brave
- A front page that is either a chosen blog, a site page, or a discovery page
  that surfaces posts from every blog

### Readers
- Comments from readers signed in with Google; there are no anonymous comments
- Contact form, with messages collected under **Inquiries**
- Newsletter sign-up and sending through a bundled [listmonk](https://listmonk.app)
- Cookie-free analytics through a bundled [Umami](https://umami.is), served
  from the blog's own domain, with **Traffic** pages in the admin UI
- Full-text site search (Apache Lucene), across one blog or all of them
- Atom feeds, sitemaps, canonical URLs, breadcrumbs, and JSON-LD for
  articles, businesses and places

### Running a site
- Many blogs on one install, each with its own members as Admin, Author or
  Limited (drafts only)
- Custom domains per blog (virtual hosts) and 301 redirects for moved URLs (automatic on a page rename, others via the API)
- Admin-managed accounts; passwords are always hashed (bcrypt by default),
  with no setting anywhere that stores one in plain text
- Production stack: the app container behind Caddy (automatic TLS), nightly
  backups, one-command upgrades

### Automation
- REST API under `/api/v1` with scoped per-account tokens, an OpenAPI
  document, and an MCP endpoint at `/api/mcp` for AI agents
- **Agent accounts**, whose tokens are issued and revoked in the admin UI
- `bin/pressroom-api`, a command-line client

The admin UI ships in English, German, Spanish, French, Japanese, Korean,
Russian and Simplified Chinese. English is the source; a missing key in
another language falls back to English.

---

## Quick start (local)

You need **JDK 25**, **Maven 3.9+** and **Docker** (for PostgreSQL). Maven
downloads its own Node for the editor bundle, so you do not install Node.

```bash
git clone https://github.com/jakefearsd/pressroom.git
cd pressroom
mvn -DskipTests=true install   # first build: a few minutes
./pressroom dev                # PostgreSQL + migrations + an admin account + the app
```

Open http://localhost:8083/ and sign in as `admin`. The password is generated
on the first run, printed once, and kept in `.pressroom-dev-secret` (mode
0600, git-ignored), so you can `cat` it at any time. Change the file to
choose your own password; the next `./pressroom db|dev|reset` applies it.

Other commands of the `./pressroom` script:

| Command | Does |
|---|---|
| `./pressroom db` | start PostgreSQL and apply migrations, without the app |
| `./pressroom migrate` / `status` | apply pending migrations / list the applied ones |
| `./pressroom token` | mint an API token for the dev admin (`dev` must be running) |
| `./pressroom stop` | stop the dev database (data kept) |
| `./pressroom reset` | **delete** the dev database volume and rebuild it |

Optional: install `cwebp` (package `webp` on Debian/Ubuntu, `libwebp-tools` on
Fedora, `brew install webp` on macOS) for WebP image renditions. Without it,
everything works, with JPEG/PNG renditions only.

To run the packaged WAR without Maven:

```bash
java -Dpressroom.custom.config=app/target/test-classes/pressroom-boot-dev.properties \
    -jar app/target/pressroom.war --server.port=8083
```

`-Dpressroom.custom.config` must come **before** `-jar`. After `-jar` it is a
program argument that nothing reads, and startup fails with "No custom
properties file found". The WAR needs a running, migrated database
(`./pressroom db`).

## Production

Container images are published to GHCR only when a `v*.*.*` tag is pushed;
pushing to `master` publishes nothing. The production host keeps only
`docker-compose.prod.yml` and an `.env` file and runs the published image.
[`docker_deployment.md`](docker_deployment.md) covers a fresh host end to end:
DNS, TLS, first admin, mail, custom domains, analytics, newsletter, Google
sign-in, backup and restore, upgrades and troubleshooting.

---

## Project layout

| Path | Contents |
|---|---|
| `app/` | The web application: Spring MVC controllers, JSP admin UI, Velocity themes, business tier, `/api/v1` and MCP, and its tests |
| `app/frontend/` | Editor and reader JavaScript bundles (esbuild, built by Maven) |
| `bin/db/migrations/` | Schema migrations `V<NNN>__*.sql`, the only way the schema changes |
| `bin/` | `release.sh`, `deploy-host.sh`, `pressroom-api`, coverage and quality-report scripts |
| `deploy/` | Production deploy/upgrade scripts, Caddy, backup and analytics config |
| `it-selenium/` | Browser integration tests against the packaged WAR (`mvn verify -Pit`) |
| `config/` | PMD and SpotBugs rule sets used by the build gates |
| `docs/` | User guide, developer notes, API reference, design system, specs |

## Technology

Spring Boot 4.1 (executable WAR, embedded Tomcat 11) · Java 25 LTS · Spring
MVC and Spring Security 7 · JPA with EclipseLink 5 · PostgreSQL 18 · Apache
Velocity for blog pages, JSP/JSTL for the admin UI · Apache Lucene 10 ·
commonmark-java with the OWASP HTML sanitizer · CodeMirror 6.

## Quality

Several thousand JUnit tests run against a real PostgreSQL in Testcontainers;
the persistence layer is never mocked. Selenium browser tests drive the
packaged WAR end to end. The build fails on any of these: coverage below the
JaCoCo floors (about 97% of lines), a PMD, CPD or SpotBugs finding, or a javac
warning. Details are in [`docs/dev/quality-gates.md`](docs/dev/quality-gates.md).

## Contributing

See [CONTRIBUTING.md](CONTRIBUTING.md). Pressroom is maintained independently
of Apache Roller, so questions about it do not belong on the upstream mailing <!-- rebrand:keep -->
lists or wiki.
