# Roller

Roller is a Java-based, multi-user blog server, substantially simplified: PostgreSQL only, Markdown only, no Planet aggregator, and a Spring Boot executable WAR in place of the old servlet-container deployment. It adds what a small business's blogs need today: reader comments behind Google sign-in, a newsletter, privacy-friendly analytics, and an API and MCP endpoint that let scripts and AI agents publish.

It is an independent fork of [Apache Roller](https://roller.apache.org), which was created in 2002 and is maintained by the Apache Software Foundation. This project is not affiliated with or endorsed by the ASF.

**Releases:** [GitHub Releases](https://github.com/jakefearsd/roller/releases) | **License:** Apache 2.0 | **Java:** 25

---

## Key Capabilities

### Multi-User Blogging
- Host unlimited weblogs on a single installation, each with its own URL, theme, and settings
- Role-based permissions per weblog — grant an existing account `edit_draft`, `post`, or `admin` access (`WeblogPermission`)
- Designate any weblog as the site-wide front page (`WebloggerRuntimeConfig.isFrontPageWeblog`)
- Admin-managed user accounts: create, edit, disable, and assign global roles (`UserAdmin.jsp`)

### Content Authoring
- Markdown editing (CodeMirror 6) with a live preview, revisions, and draft, pending, and published states
- Schedule entries for future publication
- Organize content with categories and tags
- Upload and manage media files (images, podcasts, attachments) in folder hierarchies (`MediaFileManager`)

### Themes and Templates
- Three built-in shared themes — **journal**, **portfolio**, and **travel** — plus **frontpage**, the multi-weblog aggregator theme (`themes/`)
- Apache Velocity template engine with a rich set of page models for full layout control
- In-app template editor for per-weblog customization
- Separate templates for main page, single-entry permalink, day archive, search results, and sidebar

### Readers
- Comments from readers signed in with Google (`docs/dev/comments.md`, [`RollerSSO.md`](RollerSSO.md)); no anonymous comments
- Newsletter sign-up and sending through a bundled [listmonk](https://listmonk.app)
- Cookie-free page analytics through a bundled [Umami](https://umami.is), served from the blog's own domain
- SEO: sitemaps, canonical URLs, and JSON-LD structured data

### Automation
- REST API under `/api/v1` with per-account API tokens, and an MCP endpoint at `/api/mcp` for AI agents ([API reference](docs/api/README.md))
- `bin/pressroom-api`, a command-line client for the API
- Agent accounts, whose tokens are managed in the admin UI

### Search
- Full-text search powered by Apache Lucene with background indexing
- Search across entries with category and locale filtering

### Feeds
- Atom 1.0 feeds for weblog entries, hand-rendered by Velocity templates (`WEB-INF/velocity/templates/feeds/`)

### Security and Authentication
- Database-backed authentication only; the enum kept for LDAP/OpenID/container-managed values now fails loudly at startup instead of silently degrading (`AuthMethod`)
- Spring Security 7 with role-based access control at global, weblog, and object levels
- Password hashing is mandatory and cannot be switched off: the algorithm is
  selectable (`passwds.encryption.algorithm` — bcrypt by default, or pbkdf2 /
  scrypt / argon2), but there is no setting, in any file or environment
  variable, that stores a password in plain text
- Spring Security's built-in CSRF protection on all POST forms

### Administration
- Global configuration dashboard for site-wide settings
- User administration: create, edit, disable, and assign global roles
- Built-in installation wizard with automatic database schema creation and migration

### Internationalization
- UI message bundle in 8 languages: English (source of truth), German, Spanish, French, Japanese, Korean, Russian, and Simplified Chinese. Coverage varies by language; missing keys fall back to English at runtime (`MessageKeyTest`).

---

## Supported Databases

| Database   | Use Case |
|------------|----------|
| PostgreSQL | Development, testing, and production |

Roller is PostgreSQL-only. Earlier upstream releases generated vendor-specific
DDL for DB2, Derby, HSQLDB, SQL Server, MySQL and Oracle; that layer has been
removed in favour of a single, tested schema.

---

## Quick Start

### Option 1: Dev server (Spring Boot, embedded Tomcat)

```bash
git clone https://github.com/jakefearsd/roller.git
cd roller
mvn -DskipTests=true install
./pressroom dev     # starts PostgreSQL, applies migrations, seeds an admin,
                 # runs spring-boot:run
```

Browse to http://localhost:8083/ and sign in as `admin`.

The password is generated on that first run, printed once, and stored in
`.pressroom-dev-secret` (mode 0600, git-ignored) — `cat` it any time. Edit that
file to choose your own; the next `./pressroom db|dev|reset` applies it. Nothing
is ever committed, and no configuration can make Roller store a password in
plain text.

To drive the REST API locally:

```bash
./pressroom token   # mints a bearer token for that admin
```

Optional: install `cwebp` (package `webp` on Debian/Ubuntu, `libwebp-tools`
on Fedora, `brew install webp` on macOS) to get WebP renditions of uploaded
images locally. It is feature-detected — without it Roller serves a
JPEG/PNG-only rendition ladder and everything else works the same.

### Option 2: Run the packaged executable WAR directly

```bash
mvn -DskipTests=true -pl app install
java -Dpressroom.custom.config=app/target/test-classes/pressroom-boot-dev.properties \
    -jar app/target/pressroom.war --server.port=8083
```

`-Dpressroom.custom.config` is a JVM system property, so it goes before `-jar`.
After it, it is a program argument that nothing reads, and startup fails with
"No custom properties file found".

No Maven plugin or IDE involved — the WAR embeds Tomcat and runs standalone
(requires a running, migrated PostgreSQL; `./pressroom db` starts one).

### Option 3: PostgreSQL via compose

`./pressroom dev` starts PostgreSQL for you. To run it yourself instead:

```bash
docker compose up -d   # starts PostgreSQL only, for local dev
./pressroom migrate       # applies the migrations
```

### Production

Pre-built images are published to GHCR only when a `v*.*.*` tag is pushed —
pushing to master publishes nothing. For a real
deployment — the app itself containerized behind TLS, with automated backups
and one-command upgrades — see the production stack
(`docker-compose.prod.yml`, `deploy/deploy.sh`) and its runbook,
[`docker_deployment.md`](docker_deployment.md).

---

## Project Structure

| Path               | Description |
|---------------------|-------------|
| `app/`             | Main web application (executable WAR) — Spring MVC controllers, JSP admin pages, Velocity blog-rendering templates, business logic |
| `docs/`            | Design system documentation, example configuration, and committed specs/implementation plans (`superpowers/`) |
| `bin/db/`          | Schema migrations and the migrate/install scripts |
| `deploy/`          | Production deploy script, Caddy/backup config for `docker-compose.prod.yml` |
| `it-selenium/`     | Browser-driven integration tests, run against the packaged WAR via `mvn verify -Pit` |

---

## Documentation

- **[Production Deployment Runbook](docker_deployment.md)** — Fresh-VPS Docker deployment, TLS, backups, upgrades
- **[API reference](docs/api/README.md)** — the REST API, the MCP endpoint and `bin/pressroom-api`
- **[Reader sign-in](RollerSSO.md)** — setting up Google sign-in for reader comments
- **[Design System](docs/design/design-system.md)** — the admin UI's tokens, type scale and layout rules

---

## Technology Stack

- **Runtime:** Spring Boot 4.1 executable WAR on embedded Tomcat 11, targeting Java 25
- **Web Framework:** Spring MVC
- **Security:** Spring Security 7
- **Persistence:** JPA (EclipseLink 5)
- **Database:** PostgreSQL only
- **Templating:** Apache Velocity (blog rendering), JSP/JSTL (admin UI)
- **Search:** Apache Lucene
- **DI:** Single Spring container (business beans in `WebloggerBeanConfig`, wired via the `Weblogger` facade)

---

## Testing

- Several thousand JUnit tests run against a real PostgreSQL container
  (Testcontainers), with no mocked persistence layer. `mvn test` (needs Docker).
- JaCoCo coverage floors are enforced at `verify` and only ever move up; see
  [`docs/dev/quality-gates.md`](docs/dev/quality-gates.md) for the coverage and
  static-analysis gates.
- Browser integration tests (`it-selenium/`) drive the packaged executable WAR
  end to end; run them with `mvn verify -Pit`.

## Contributing

See [`CONTRIBUTING.md`](CONTRIBUTING.md). This is a personal fork, maintained
independently and not affiliated with or endorsed by the Apache Software
Foundation — upstream's mailing lists and wiki serve
[Apache Roller](https://roller.apache.org), not this project, so questions about
this fork do not belong there.
