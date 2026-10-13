# Database

Developer notes moved out of `CLAUDE.md` (which is sent with every
request) so they are read only when this area is being changed. Section
names are unchanged, so an older "see CLAUDE.md, <section>" pointer
resolves through the index at the end of `CLAUDE.md`.

## Database

Roller is **PostgreSQL-only** — one engine for dev, test and production
(Derby and the Velocity/Texen DDL generator are gone).

- **Development**: PostgreSQL 18 via `docker-compose.yml` (named volume; persists)
- **Testing**: PostgreSQL 18 via Testcontainers, schema from the migration chain
- **Production**: PostgreSQL 18 via `docker-compose.prod.yml`
- **JNDI Name**: `jdbc/rollerdb`

**Four files name the server major, and `PostgresMajorPinTest` fails the build
when they disagree**: both compose files, `RollerPostgresContainer`
(Testcontainers) and `it-selenium/pom.xml`. They had already drifted — the
compose files pinned a digest while both harnesses floated on a bare tag —
and nothing went red, which is the point: a suite green on a different major
than production proves nothing about production.

**The 18 image mounts its data one directory higher.** `PGDATA` is
`/var/lib/postgresql/18/docker` and the declared `VOLUME` is the parent,
`/var/lib/postgresql`, so both compose files mount *that*, not
`/var/lib/postgresql/data`. One volume then holds successive majors side by
side, which is what makes a later in-place `pg_upgrade --link` possible. A
pre-18 cluster mounted at the new path is refused by the entrypoint rather
than silently re-initialised — loud, but it does not say "your compose file
is a major behind", which is why the test pins the path against the image.
Production keeps the plain `roller-pgdata` name, never having been deployed
on 16; **dev** renamed its volume to `roller-pgdata18` because the 16 cluster
still occupies `roller_roller-pgdata` and is the dev rollback.

**`pg_dump` refuses a server newer than itself, and the app image is also the
backup runner**, so its client floor is declared in the `Dockerfile` as
`ARG PG_CLIENT_MIN_MAJOR` and checked against the production server major by
the same test. It is not decoration: `eclipse-temurin:25-jre` became Ubuntu
26.04 at the pinned digest (`724cd0129`, 2026-08-19), moving the client from
16 to 18 with no signal. While the server was still 16, every nightly dump
was unrestorable by the `pg_restore` *inside* the postgres container
(`unsupported version (1.16) in file header`) — backups sound, restores
broken, nothing red. Raise the floor and the server image together.

**EclipseLink decrypts the JDBC password unless told not to.** It passes
`jakarta.persistence.jdbc.password` through its login encryptor, and the
default one (`JCEEncryptor`) takes any value that parses as hex for one of
its own AES-encrypted passwords. An even-length all-hex plaintext (`openssl
rand -hex 24`) then fails decryption and the unit never deploys:
`EclipseLink-7360 ... encrypted by deprecated algorithm`, a message that
points everywhere but the cause. Plain JDBC with the same credentials works,
so `DatabaseProvider`'s startup check passes. The 0.1.10 production deploy
crash-looped on it. `JPAPersistenceStrategy` now sets
`eclipselink.login.encryptor` to `PlaintextPasswordEncryptor` (identity both
ways); `JPAPersistenceStrategyPasswordTest` bootstraps against a real role
with a hex password. Anything else that builds an EMF must do the same.

### Schema changes

**Every schema-changing commit MUST add a numbered migration** under
`bin/db/migrations/`: next `V<NNN>__description.sql`, idempotent DDL; never
edit a migration already applied anywhere but local dev — fix with a
follow-up. Convention: `bin/db/migrations/README.md`; `SchemaMigrationTest`
enforces discoverability, shape and idempotency. Three appliers read the same
files: `bin/db/migrate.sh` (deploy), `DatabaseInstaller` (install wizard),
and the test harness.

The install wizard is anonymous, and with `installation.type=auto` every
public request reaches it while Roller cannot start. So its pages carry no
diagnostics: the error pages say only that the database cannot be reached or
prepared and point at `roller.log`, and `SQLScriptRunner` reports a failure by
statement position ("statement 3 of 12"). The startup log (JDBC URL and user),
the SQL text (which carries the expanded `:app_user`) and every exception are
logged at the point of failure instead (`InstallControllerTest`,
`SQLScriptRunnerTest`).
