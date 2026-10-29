# Roller Database Migrations

This directory holds the canonical, ordered schema definition for the Roller
PostgreSQL database. Every schema change ships as a numbered, idempotent
migration so databases can be brought forward safely and repeatably.

Roller is **PostgreSQL-only**. The earlier scheme — Velocity
templates rendered by Texen into vendor-specific DDL for seven databases, with
a hardcoded `upgradeToNNN()` chain in `DatabaseInstaller` keyed off a
schema-version row in the runtime properties table — has been removed.

## Files

- `V001__schema_migrations.sql` — tracking table used by `migrate.sh`
- `V002__baseline_schema.sql` — the complete baseline schema (20 tables)

The companion scripts live one directory up (`bin/db/`):

- `../migrate.sh` — apply any pending migrations to an existing database
- `../install-fresh.sh` — create a new DB + application role, then migrate
- `../seed-dev-data.sql` — the local dev admin row. **Deliberately not in this
  directory**, because everything here is applied to production: by
  `migrate.sh`, by `DatabaseInstaller`, and by the test harness. A file that
  carries a credential must stay outside that set. `./roller db|dev|reset`
  applies it separately, and `PasswordEncodingTest` fails the build if any
  `V*.sql` here grows a password.

## Naming convention

```
V<NNN>__<snake_case_description>.sql
```

- `NNN` is a monotonically increasing zero-padded integer (`V003`, `V004`, …).
- Descriptions are lowercase snake_case.
- A double underscore separates the version from the description.
- One migration = one logical change. Don't bundle unrelated schema work.

## Rules for writing a migration

1. **Must be idempotent.** Use `CREATE TABLE IF NOT EXISTS`,
   `CREATE INDEX IF NOT EXISTS`, `ALTER TABLE … ADD COLUMN IF NOT EXISTS`,
   and `INSERT … ON CONFLICT DO NOTHING`. Running the same migration twice
   against the same database must be a no-op.

   PostgreSQL has no `ALTER TABLE … ADD CONSTRAINT IF NOT EXISTS`. Prefer
   declaring constraints inline in `CREATE TABLE IF NOT EXISTS` (order your
   tables by foreign-key dependency so this is possible, as `V002` does). When
   you must add a constraint to an existing table, wrap it:

   ```sql
   DO $$ BEGIN
       ALTER TABLE weblogentry ADD CONSTRAINT we_foo_fk FOREIGN KEY …;
   EXCEPTION WHEN duplicate_object THEN NULL;
   END $$;
   ```

2. **Must not hard-code role names for grants.** Use the `:app_user` psql
   variable — `migrate.sh` sets it from `DB_APP_USER` (default `pressroom`).

3. **Must not be edited after it has been applied anywhere but local dev.**
   Fix mistakes with a follow-up migration. The `schema_migrations` table
   assumes history is append-only.

4. **Keep migrations fast and focused.** Long-running data backfills belong in
   application code with a progress indicator, not in DDL that holds locks.

5. **Document prerequisites** at the top of the file when a migration depends
   on an earlier one.

6. **Keep the JPA mappings in step.** Roller maps entities through
   `app/src/main/resources/META-INF/*.orm.xml`. A migration that adds or
   renames a column without the matching mapping change will pass
   `migrate.sh` and fail at runtime. `SchemaMigrationTest` applies the full
   chain to a throwaway container and checks the result against what JPA
   expects, so run the test suite after any schema change.

## Adding a new migration

1. Pick the next version number by looking at the highest `V*.sql` here.
2. Create `V<NNN>__<description>.sql`.
3. Write the DDL using the idempotent patterns above.
4. Run `../migrate.sh` against your local dev database to verify it applies.
5. Run `../migrate.sh` a second time to verify it is a no-op.
6. Run `mvn -pl app test` — `SchemaMigrationTest` replays the whole chain.
7. Commit the migration with the code that depends on it.

## Running migrations

```bash
# Against the default local dev database (docker-compose postgres)
bin/db/migrate.sh

# Check what has been applied
bin/db/migrate.sh --status

# Production: set connection vars and run
DB_NAME=pressroom PGHOST=db.example.com PGUSER=postgres \
    PGPASSWORD='…' bin/db/migrate.sh
```

## How the application applies migrations

There are two entry points and they share this directory:

- **`migrate.sh`** — build, deploy, and CI. `./roller dev` runs it, and the
  Maven build runs it against the test container.
- **`DatabaseInstaller`** — Roller's web install wizard, for a first boot
  against an empty database. It reads the same `V*.sql` files from the
  classpath and records into the same `schema_migrations` table.

Because both paths key off `schema_migrations`, it does not matter which runs
first, and neither will re-apply a migration the other already ran.
