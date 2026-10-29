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
- **JNDI Name**: `jdbc/pressroom`

**Four files name the server major, and `PostgresMajorPinTest` fails the build
when they disagree**: both compose files, `PressroomPostgresContainer`
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

### Shared cache sizes

EclipseLink's shared cache is sized in `pressroom.properties`
(`eclipselink.cache.*`, passed to the persistence unit with every other
`eclipselink.` key; `persistence.xml` properties are **not** read, since
`PressroomPersistenceUnitInfo.getProperties()` is empty). Every type is
`SoftWeak`: the N most recently cached objects are held softly, the rest
weakly. **Entries 5000, revisions 100, everything else 1000.** Until
2026-10-22 nothing was configured, so every type had EclipseLink's 100.

- **Why it matters: a permalink is a find by id.**
  `getWeblogEntryByAnchor` maps the anchor to an id
  (`entryAnchorToIdMap`) and calls `em.find`, which costs no SQL while the
  entry is cached. At 100, entries past the most recent 100 went at every GC
  and the next permalink re-read the row: a profiling run (10 weblogs x ~3k
  entries) ran `SELECT ... FROM weblogentry WHERE (id = $1)` 207,012 times
  for 207,005 permalinks, and each rebuilt entry then lazily re-read its tags.
  `EntryCacheSizeTest` reads 300 entries, collects garbage, reads them again:
  200 row reads at 100, none now.
- **Memory bound.** Measured per cached entry: ~4.4 KB with three tags,
  plus the text (1 byte a character, 2 once it holds anything outside
  Latin-1). 5000 entries is ~25 MB at 500-character posts and ~72 MB at
  5,000. The other types that carry text or hold an entry:
  - *Revisions* are full copies of a post, written on every save (which
    also reads all of that entry's revisions) and read again only by the
    revisions screen, so they keep 100 (~1.4 MB at 5,000 characters).
  - *Tags, attributes and reader comments* keep the 1000 default, and each
    holds its entry strongly, so up to ~3,000 entries beyond the 5000 can
    stay live: a worst case of ~117 MB at 5,000-character posts. In practice
    tags are cached with their own entry and overlap it. Comments are short
    and are read on every permalink of their entry.
  - The small types (weblogs, users, categories, templates, permissions,
    media metadata) are at most a few MB at 1000.

  All of it is soft or weak, so it is cleared under memory pressure before
  an `OutOfMemoryError`.
- **Never stale, by construction.** Roller is one JVM and writes through
  JPA, which merges into the cache at commit (nothing on rollback); JPQL
  bulk updates and deletes invalidate their class. The only native writes go
  to `pressroom_weblogentrytagagg`, which is `cacheable="false"`. A row changed
  with SQL behind the app's back (the admin account tool, a hand edit) stays
  stale until a restart; a bigger cache makes that window longer, not new.
- **The anchor map checks the anchor.** A hit whose entry now has another
  anchor (or is gone) is dropped and the anchor looked up afresh, so a
  renamed entry's old permalink 404s instead of serving it.
- **The anchor map is bounded by the corpus:** one key per anchor that was
  found (misses are not stored, `removeWeblogEntry` drops its key). Only a
  deleted weblog's keys linger, each until its anchor is looked up again.
- **Tuning** is a startup property: `eclipselink.cache.size.WeblogEntry` in
  `pressroom-custom.properties` or `PRESSROOM_ECLIPSELINK_CACHE_SIZE_WEBLOGENTRY`.
  **A `PRESSROOM_*` variable reaches only a key already in the loaded properties**
  (it then takes that key's spelling). Any other arrives lowercased
  (`eclipselink.cache.size.readercomment`), and EclipseLink matches entity
  names case-sensitively, so it is ignored without an error: add the key to
  `pressroom.properties` first, or set it in `pressroom-custom.properties`.

### Schema changes

**Every schema-changing commit MUST add a numbered migration** under
`bin/db/migrations/`: next `V<NNN>__description.sql`, idempotent DDL; never
edit a migration already applied anywhere but local dev — fix with a
follow-up. Convention: `bin/db/migrations/README.md`; `SchemaMigrationTest`
enforces discoverability, shape and idempotency. Three appliers read the same
files: `bin/db/migrate.sh` (deploy), `DatabaseInstaller` (install wizard),
and the test harness.

Recent examples: `V039__weblog_site_space.sql` adds `weblog.site_space boolean
NOT NULL DEFAULT false` and a partial unique index `weblog_one_site_space ON
weblog (site_space) WHERE site_space` (at most one site space).
`V040__privacy_page_reference.sql` rewrites `site.privacy.page` from a bare slug
to `handle/slug` using the front-page blog's handle (guarded `UPDATE`: once
the value contains `/` it no longer matches, and a blank value or an install
with no front-page blog is left alone, where a bare slug now means a site page).

`V041__pressroom_rename.sql` renames every `roller_*` relation and constraint <!-- rebrand:keep -->
in `public` to `pressroom_*` (catalog only: rows, grants, foreign keys and the
`analytics_*` views follow by oid) and rewrites stored `/pressroom-ui/` links in <!-- rebrand:keep -->
pages and entry text/summary (revisions keep what was written). It refuses,
before renaming anything, when an old name and its new name both exist or a
new name would pass 63 bytes. **After it, re-running an earlier migration is
no longer harmless**: V002's `CREATE TABLE IF NOT EXISTS` would create the old
tables again, empty, beside the renamed ones. So `SchemaMigrationTest` and
`SqlScriptRunnerMigrationTest` check idempotency by applying each migration
twice in a row at its own place in the chain, and a test of an older
migration builds its database with `ScratchDatabase.migratedThrough(name,
40)`, not the whole chain. `DatabaseInstaller`'s upstream probe still looks
for `roller_user` (upstream Apache Roller's name; `rebrand:keep`).

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

### Entry tags and the shared cache

EclipseLink's shared cache keeps an entry's loaded tags with it, but only
for the entries it still holds (see "Shared cache sizes" above; at the old
default of 100 an entry beyond that was weakly held and dropped at the next
GC). Every list query
still runs its SQL; a dropped entry is rebuilt from the row with its tags
unloaded, and feeds and API lists then read each one's tags alone (418,547
reads in a 4.5-minute profiling run, with concurrent renders queued on each
entry's synchronized lazy load). **`getWeblogEntries` with `maxResults > 0`
(a page: feeds, the API list, theme pagers) asks for an IN batch of
`e.tags` by query hint and loads the tags before returning**: one
`entryid IN (...)` read per page, nothing per entry. Theme list pages, which
show no tags, now pay that one read when their entries were rebuilt.

**Why not `<batch-fetch>` on the mapping** (tried, reverted): a batch value
holder keeps the query that built its entry, and that query every row it
read, text included, until the entry's tags are touched. A search reindex
reads every published entry unbounded and touches no tag, so each entry
left in the cache pinned the whole site's rows, and a permalink of one of
them read the whole list's tags in a single IN. Loading at once leaves no
holder pointing at the query; unbounded reads stay lazy, so a cached entry
keeps at most its own row. Not JOIN or EXISTS batching either: those re-run
the list's selection without its LIMIT/OFFSET. `EntryTagsFetchRenderingTest`
counts the reads and pins edit, create and delete freshness;
`EntryTagsRetentionTest` pins what a cached entry retains and what a later
permalink reads. A permalink still reads its one entry's tags when the
entry was dropped from the cache.

### Tag aggregates (`pressroom_weblogentrytagagg`)

One row per (weblog, tag) and one site-wide row per tag (`websiteid IS
NULL`), each the number of PUBLISHED entries carrying the tag. **Only
`TagAggregateStore` writes it.** A save only names the tags it may have
touched; just before the transaction commits (`JPAPersistenceStrategy.
beforeCommit`), each touched row is locked with `INSERT ... ON CONFLICT ON
CONSTRAINT weta_weblog_tag_uq DO UPDATE` and then, in a second statement
(a fresh READ COMMITTED snapshot, so it sees whoever held the lock before),
recomputed: a weblog row from that weblog's entries, the site-wide row as
the **sum of the tag's weblog rows** (each entry has one weblog, so it is the
same number; until 2026-10-22 it rescanned every entry carrying the tag,
~20 ms a save and the top database cost under load). A row at zero is
deleted, after the sum. The sum is exact because every transaction that
changes a weblog row takes the site-wide lock afterwards and holds it to
commit, so the last to sum sees every other weblog row committed; summing
inside the locking statement would use a pre-wait snapshot and lose the
lock holder's entries (`TagAggregateConcurrencyTest`, the queued-saves
test). The weblog recount narrows on the tag row's own `websiteid`
(`wet_weblog_name_entry_idx`, V036).
Locks are taken once per transaction, by tag name (weblog rows, then the
site-wide row), after all the transaction's other writes are flushed, so
multi-entry transactions (ScheduledEntriesTask, bulk publish) cannot
deadlock against single saves on these rows. **Weblog deletion**
(`JPAWeblogManagerImpl.removeWeblogContents`) recounts, in the same hook,
every tag whose tag rows it removes (named from those rows, so a draft
published concurrently is recounted after it) and every tag the weblog
still has a row for at commit; each falls to zero and leaves the sums.
From its first tag to its commit it holds the site-wide rows of all those
tags, so saves on any weblog touching them wait that long (a few
statements per tag).
Until 2026-10-22 it bulk-DELETEd the weblog's rows mid-transaction, out of
lock order, and recounted only the names its rows had when it started, so
a draft published in that window left its new tag's site-wide row
counting a deleted entry. Weblog deletion is one transaction (until
2026-10-22 it committed three times on the way, so a failure part-way left
a weblog without its media, or without its entries); its tag rows,
aggregates, entries and categories go in the one commit, and its media
files leave the disk only after it (`WeblogDeletionAtomicityTest`).
**The recount depends on READ COMMITTED** (a fresh
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
  statement, then put back. A save that waits longer fails with an
  `AnchorLockTimeoutException` naming the weblog and the bound (WARN log)
  and rolls back; it is transient, so a client may simply retry.
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
