# Architecture

Developer notes moved out of `CLAUDE.md` (which is sent with every
request) so they are read only when this area is being changed. Section
names are unchanged, so an older "see CLAUDE.md, <section>" pointer
resolves through the index at the end of `CLAUDE.md`.

## Architecture Overview

Roller is a multi-user blog server built with:
- **Runtime**: Spring Boot 4.1 executable WAR (`java -jar app/target/roller.war`
  or an external servlet container), embedded Tomcat 11, **Java 25 — an LTS
  release, and a decision (2026-09-15) to stay on it until Java 29 (LTS,
  September 2027)**. Never bump to a six-month feature release (26/27/28).
  Seven pins move together when 29 arrives: the enforcer range `[25,26)`,
  the compiler `<release>`, PMD's `<targetJdk>`, all three workflows'
  `java-version`, and both `temurin` images in `Dockerfile`.
  Servlets/filters are Java config (`ServletRegistrationConfig`, transcribed
  from the retired `web.xml`); no `web.xml` in the artifact.
- **Web Framework**: Spring MVC, `@Controller` classes, `*.rol` mappings
- **Security**: Spring Security, role-based access control, built-in CSRF
- **Persistence**: JPA/EclipseLink on PostgreSQL
- **Templating**: Velocity for blog rendering, JSP/JSTL for admin UI
- **Entry content**: Markdown, always; no per-entry format flag or column
  (V009 dropped `content_type`/`content_src`). commonmark-java runs in
  `WeblogEntry.render()` **after** shortcode expansion, before sanitization
  (load-bearing: markdown-first escapes the quotes in `[gallery dir="x"]`).
  Raw HTML passes through commonmark by design; `HTMLSanitizer` (OWASP
  policy) is the only boundary.
- **Search**: Apache Lucene 10, embedded, index on local disk
- **DI Container**: one Spring container, **no static service locator**
  (`WebloggerFactory` deleted 2026-08-22; spec
  `docs/superpowers/specs/2026-08-22-retire-static-service-locator-design.md`).
  Beans: `WebloggerBeanConfig` (`@Configuration @Lazy`,
  `org.apache.roller.weblogger.business.jpa`), built lazily by
  `SpringWebloggerProvider.bootstrap()` (`@Component` implementing
  `WebloggerProvider`: `isBootstrapped()`/`bootstrap()`/`getWeblogger()`)
  after `WebloggerStartup.prepare()`, from `RollerLifecycle` (phase 0) or
  the install wizard. **How a class reaches the tier depends only on how it
  is constructed:**
  - Container-managed (controllers, the nine rendering/ajax servlets, filters,
    interceptors, business beans): `@Lazy Weblogger` in the constructor. The
    `@Lazy` is load-bearing (beans are created at context refresh, before
    `prepare()`); `ContextRefreshDoesNotBootstrapTest` fails the build on a
    forgotten one.
  - `WebloggerProvider` only where "is the tier up?" is a runtime question:
    `BootstrapFilter`, `PersistenceSessionFilter`, `ControlPlaneHostFilter`,
    `InitFilter`, `RequestMappingFilter`/`WeblogRequestMapper`,
    `RollerHandlerInterceptor`, `RollerUserDetailsService`,
    `ApiTokenAuthFilter`, `RollerSession`.
  - Models: `weblogger` (and `urlStrategy`) from `initData` (`ModelLoader`
    requires it). Pagers, `*Request` objects, wrappers, shortcode
    handlers, theme subclasses, background tasks: constructor / `init`.
    Velocity resource loaders: the engine's application attribute, set by
    `RollerVelocity.initialize(servletContext, weblogger)`.
  - **Entities under `pojos/` are data plus invariants and import no
    business-tier type.** URLs: `URLStrategy` (JSPs via `BaseController`'s
    `urls` helper, `AdminUrls`; themes via the wrappers, which hold the
    facade). Rendering: `EntryRenderer` (`Weblogger.getEntryRenderer()`).
    Queries/identity/authorisation: managers and wrappers.
  - `StaticServiceLocatorTest` pins this as a source scan: nothing names
    `WebloggerFactory`; no main-source `static` field has a business-tier
    type except `WebloggerRuntimeConfig`'s attached `PropertiesManager`
    (until the config wave retires it) and `RollerVelocity`'s engine; no
    entity references one.
  - **JSP scriptlets are callers** (`footer.jsp`/`login-redirect.jsp` use
    `WebApplicationContextUtils`); only a clean build's `jspc-validate`
    catches a broken one.
  - **An `.orm.xml` `<transient>` row must be deleted with the getter it
    names**, or EclipseLink's validation fails bootstrap.

### Core Package Structure
```
org.apache.roller.weblogger.
├── boot/            # Boot entrypoint, Java config (servlets, security, MVC)
├── business/        # Service layer
│   ├── jpa/
│   ├── plugins/     # Content plugins
│   ├── themes/
│   └── search/      # Lucene
├── pojos/           # Domain entities
├── config/          # WebloggerConfig, WebloggerRuntimeConfig
├── ui/controllers/  # Spring MVC controllers
│   ├── admin/
│   ├── ajax/        # the user/theme data servlets
│   ├── core/        # Login, profile, reader account and comments
│   └── editor/
├── ui/core/         # filters, security (incl. sso/), RollerSession
├── ui/rendering/    # blog rendering: servlets, models, Velocity
├── ui/restapi/      # /api/v1 and the MCP endpoint
└── util/
```

### Key Architecture Patterns

**Service Layer Pattern**: the `Weblogger` interface is the facade over every manager:
```java
getUserManager() / getWeblogManager() / getWeblogEntryManager() / getThemeManager() / getIndexManager() / ...
```

**Manager Pattern**: business logic lives in `UserManager`, `WeblogManager`,
`WeblogEntryManager`, `ThemeManager`, `IndexManager`, `MediaFileManager`.

### Security Architecture
- **Authentication**: database only (`AuthMethod`; LDAP/OpenID/container-managed
  removed); an unsupported `authentication.method` fails loudly at startup
  rather than silently behaving like `db`.
- **Authorization**: `GlobalPermission`, `WeblogPermission`, `ObjectPermission`
- **Custom Interceptor**: `RollerHandlerInterceptor` enforces access controls
- **CSRF Protection**: Spring Security built-in, automatic on all POST forms

### Database Schema
Key domain entities: `Weblog` (blog instances, settings), `WeblogEntry`
(posts, content, publishing status), `User` (accounts, roles, permissions),
`WeblogCategory`, `MediaFile` (attachments, media), `WeblogTemplate` (custom
templates).

### Search Implementation
- **Engine**: Apache Lucene, background indexing; asynchronous
  add/remove/rebuild operations
- **Scope**: full-text across entries with category and locale filtering
- **Index Location**: configurable work directory
- **Reader lifecycle** (`LuceneIndexManager`): searches share one cached
  `IndexReader`; every index write retires it with `resetSharedReader()`,
  called *before* the write lock is released (or a search queued behind the
  write answers from the stale reader — `SharedReaderHandoffTest`). The
  reader is reference-counted: the manager owns one reference and drops it
  on reset or `shutdown()`; a search takes its own with
  `acquireSharedIndexReader()` and must hand it to
  `releaseSharedIndexReader()` exactly once, in a `finally`. That is
  required, not tidy-up: a search reads its hits' stored fields
  (`convertHitsToEntryList`) *after* the read lock is gone, so a write can
  retire the reader mid-search; closing it at reset fails that search with
  `AlreadyClosedException`, and never closing it (the old behaviour) leaked
  every superseded reader's segment file handles until GC.
  `SearchOperation` is `AutoCloseable` and holds the reference until
  closed — `search()` runs it in try-with-resources, and anything else that
  runs one must close it. `getSharedIndexReader()` lends the reader without
  a reference; it is safe only while the caller holds the read lock.
  `SharedReaderLifecycleTest` pins all of this.
