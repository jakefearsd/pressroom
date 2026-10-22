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

### Entity ids (`EntityIds`)

Every entity's id field is initialised from `EntityIds.newId()`, so `new X()`
has a random UUID before anything reads it (id-keyed maps, URLs, anchor
claims). EclipseLink builds loaded rows, unit-of-work clones and backup
clones through the same constructor; `MaterialisingInstantiationCustomizer`
(set as `eclipselink.session.customizer` in `JPAPersistenceStrategy`) has it
construct them inside `EntityIds.materialise`, where `newId()` answers null
and the stored id is set straight after through `setId` (all mappings are
PROPERTY access). Before this, each loaded entry drew ~3 UUIDs under the
JVM-wide `SecureRandom` lock (~7% CPU under reader load). An entry's
post-load revision snapshot is built id-less too and gets its id in
`contentBeingReplaced`, only when it is about to be stored.
`EntityLoadIdGenerationTest` pins zero generations per load across every
mapped descriptor. A new entity needs nothing extra: a public class with a
public no-argument constructor is picked up automatically; anything else keeps
EclipseLink's default (generating) instantiation, which that test's
`everyMappedEntityIsMaterialisedWithoutGeneratingAnId` fails on. An operator-set
`eclipselink.session.customizer` replaces this one, and the JNDI
(`jpa.configurationType=jndi`) configuration never registers it: either
silently loses the speed-up, never correctness.

### Tag aggregates (`roller_weblogentrytagagg`)

One row per (weblog, tag) and one site-wide row per tag (`websiteid IS
NULL`), each the number of PUBLISHED entries carrying the tag. **Only
`TagAggregateStore` writes it.** A save only names the tags it may have
touched; just before the transaction commits (`JPAPersistenceStrategy.
beforeCommit`), each touched row is locked with `INSERT ... ON CONFLICT ON
CONSTRAINT weta_weblog_tag_uq DO UPDATE` and then, in a second statement
(a fresh READ COMMITTED snapshot, so it sees whoever held the lock before),
set to the count recomputed from the entries; a row at zero is deleted.
Locks are taken once per transaction, by tag name (weblog rows, then the
site-wide row), after all the transaction's other writes are flushed, so
multi-entry transactions (ScheduledEntriesTask, bulk publish) cannot
deadlock against single saves on these rows. **One exception: weblog
deletion** (`JPAWeblogManagerImpl.removeWeblogContents`) runs the bulk
`WeblogEntryTagAggregate.removeByWeblog` DELETE mid-transaction, outside
the hook, locking that weblog's aggregate rows in no particular order, so
a save into the weblog being deleted can deadlock with it (PostgreSQL
aborts one side). **The recount depends on READ COMMITTED** (a fresh
snapshot per statement), which nothing pins; under REPEATABLE READ the lock
statement would fail with serialization errors instead (fails closed).
**`lastused`** (behind popular-tags-in-the-last-N-days) moves only when a
row is created or its count actually changes, not on every save that
touches the tag, as it did before 2026-10-22. Two earlier designs failed:
an unlocked read-modify-write (duplicate keys and lost increments: a
2026-10-22 profiling run had 21 HTTP 500s in 1,500 creates, 152 of 210 rows
low), then atomic per-save deltas taken from the entry as loaded (two saves
of the same entry, both loaded as a draft, each added the tag: a double-
clicked publish counted 2). Tag reads inside the saving transaction see the
old counts until it commits. V034 made the constraint `UNIQUE NULLS NOT
DISTINCT`, so it also guards the site-wide rows a plain `UNIQUE` let
duplicate, and recomputed every total. The entity is `cacheable="false"`:
EclipseLink's shared cache never sees the native writes.
`TagAggregateConcurrencyTest` reproduces each failure.

### Entry anchors (`weblogentry.anchor`)

An entry's anchor (its permalink slug) is unique within its weblog, and
since V035 the database says so: `we_website_anchor_uq (websiteid, anchor)`,
which also serves every lookup by anchor (permalinks, `createAnchor`'s check;
before, a scan of the weblog's entries). **`saveWeblogEntry` claims an anchor
under a lock** (`JPAWeblogEntryManagerImpl.claimAnchor`): whenever it
generates one, or the entry's anchor differs from the one it was loaded with
(`WeblogEntry.getLoadedAnchor`, null for a new entry), it first takes
`pg_advisory_xact_lock` on `weblogentry.anchor:<weblog id>`
(`JPAPersistenceStrategy.lockUntilCommit`), held until the transaction ends,
then checks. A concurrent create waits, and its check (a fresh READ
COMMITTED snapshot) sees the committed entry, so a generated anchor takes the
next suffix (`title`, `title1`, ...) and a chosen one is refused with
`AnchorTakenException` (the API's 409 on `anchor`). Per weblog, not per
title: `foo1` generated from "foo" (with `foo` taken), a chosen `foo1` and
one generated from the one-word title "foo1" are one anchor ("foo 1" gives
`foo-1`; `createAnchorBase` joins words with the separator).
Creates of a weblog are therefore serialised from the save to the commit;
edits keep their anchor and take no lock. Before the lock, 8 concurrent
same-titled creates committed 8 entries with one anchor.

- **Claims within one transaction:** the check's query does not flush, so
  it cannot see an entry saved earlier in the same transaction. The
  transaction's claims are kept in `ClaimedAnchors`, a no-op before-commit
  action, so the strategy discards them on commit or release and a pooled
  thread never carries them into another request. Two same-titled creates
  in one transaction get `foo` and `foo1`.
- **The wait is bounded:** `weblogentry.anchor.lockTimeoutSeconds` (startup,
  default 10) is set as a transaction-local `lock_timeout` just for the lock
  statement, then put back. A save that waits longer fails with a
  `WebloggerException` naming the weblog (WARN log) and rolls back.
- **Generated anchors fit the column:** the base is cut so base + suffix is
  at most 255 (never inside a surrogate pair); V035 cuts the same way.
- **Do not call `beginEarlyTransaction` before the lock.** EclipseLink begins
  the database transaction for a native query in an active transaction
  (once), which is what puts the lock on the connection that commits. A
  second `beginEarlyTransaction` nests it; the commit then ends only the
  inner level, and the connection stays idle in transaction holding the lock
  (a two-entry transaction stalled the next create of the weblog for 100 s).
- **Lock order with the tag recount:** the lock is the first lock a create
  takes (nothing is flushed first), and `TagAggregateStore` takes its row
  locks only at commit, after every save, so a waiter here holds no tag row.
  A transaction that had already flushed a write before creating an entry
  could still wait here holding a row lock; nothing in Roller does that, and
  PostgreSQL would detect the cycle and abort one side.
- **V035 resolved existing duplicates.** None of them was reachable before
  (`getWeblogEntryByAnchor`'s `getSingleResult` threw
  `NonUniqueResultException` on a duplicated anchor), so it chose what the
  URL serves for readers: the PUBLISHED entry keeps the anchor, then the
  newest pubtime (NULL last), then the lower id; the rest took the first
  free `<anchor>N`. No redirect was added:
  the old anchor serves the entry that kept it, and a rule fires only on a
  404.
