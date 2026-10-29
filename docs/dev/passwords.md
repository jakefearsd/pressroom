# Passwords and the dev credential

Developer notes moved out of `CLAUDE.md` (which is sent with every
request) so they are read only when this area is being changed. Section
names are unchanged, so an older "see CLAUDE.md, <section>" pointer
resolves through the index at the end of `CLAUDE.md`.

## Passwords
- **Password encryption cannot be configured or turned off.** Gone, all
  four paths that put plaintext in `pressroom_user.passphrase`:
  `passwds.encryption.enabled=false` (`DelegatingPasswordEncoder` encoding
  id `noop`), the unconditional `noop` encoder (`{noop}` rows authenticated
  anyway), `lazyUpgradeFrom=plaintext` (null-prefix no-op encoder:
  unprefixed strings authenticated), and the `enabled=false` lines in
  `pressroom-boot-dev.properties` / `pressroom-custom.properties`. Only
  `passwds.encryption.algorithm` (bcrypt/pbkdf2/scrypt/argon2) remains. An
  explicit `passwds.encryption.enabled` — file or
  `PRESSROOM_PASSWDS_ENCRYPTION_ENABLED` — **throws at startup**, like an
  unsupported `authentication.method`. A `{noop}` row is refused outright.
  `PasswordEncodingTest` pins it.
- **`TestUtils.setupUser` stores a precomputed `{bcrypt}` constant**
  (`TestUtils.TEST_PASSWORD` / `TEST_PASSWORD_HASH`), not a live `encode()`
  — bcrypt is slow, ~106 call sites (the bare `"password"` it once stored
  never authenticated).
- **The dev admin credential is `.roller-dev-secret`** — git-ignored,
  generated (`umask 077`) on the first `./roller db|dev|reset`, printed once.
  `bin/db/seed-dev-data.sql` applies it via pgcrypto inside Postgres; **not**
  under `bin/db/migrations/`, so it never reaches production. Source of
  truth: the next seed reverts web-UI password changes.
- **The seed's `ON CONFLICT` guard must stay a `CASE`.** `crypt()` raises
  `ERROR: invalid salt` on an unusable salt and PostgreSQL does not
  short-circuit `OR`, so an `OR` chain aborts on exactly the
  `{noop}`/truncated row it repairs. `DevSeedTest` runs the *shipped file*
  over every row shape.
- **`./roller token`** mints an API token for that admin via
  `pressroom-api auth login --password-stdin`. In `./roller`, never generate
  the secret with `tr -dc … </dev/urandom | head -c N`: under
  `set -o pipefail` `head` exiting early SIGPIPEs `tr` and the script dies
  silently at 141.

## The admin account tool

`deploy/admin-account.sh` (`create`, `reset-password`, `status`; compose
service `admin`, profile `tools`) is how production gets its first admin and
recovers a lost password. Spec: `docs/superpowers/specs/2026-10-10-admin-bootstrap-and-recovery-design.md`.

- **Trust model.** Running it at all means controlling the host and its
  `.env`, which already holds every database credential, so it adds no
  authority. Nothing reachable over HTTP can do what it does, which is why
  there is no web bootstrap or recovery endpoint.
- **The password route (D4).** The password is never an argument and is never
  printed. It goes into the environment of one `psql`, which reads it with
  `\getenv`, then binds it as a parameter (`crypt($1, …)` + `\bind :pw \g`;
  UNQUOTED `:pw`, since `:'pw'` would bind the quoted literal). Never
  `:'pw'` in statement text: psql substitutes it client-side, so a failed
  statement is logged by the server with the password in it (log lesson;
  `AdminAccountScriptTest` forces a failure and greps the container log).
  The hash is `{bcrypt}` +
  pgcrypto `crypt(…, gen_salt('bf', 10))`. Every `psql` call reads its SQL
  from a heredoc and never `-c`: `-c` does not substitute `-v` variables, and
  a heredoc stops psql swallowing the script's own stdin, where the
  `--password-stdin` line is.
- **Locale lesson.** Bash bracket ranges follow `LC_ALL`/`LC_COLLATE`, and
  the image runs `en_US.UTF-8`, where `[A-Za-z]` matches `ä`. The username
  check scopes `LC_ALL=C` to itself; never export it globally (it would make
  `${#PASSWORD}` count bytes). Every `psql` call has `|| die`, so a SQL error
  exits 1 and `status` never reports "no admin" for a check that could not run.
- **`IFS= read -r`** reads the password, so leading/trailing spaces and
  backslashes survive.
- **Restart caveat.** The running app caches loaded accounts, so after
  `reset-password` it keeps accepting the old password until
  `docker compose -f docker-compose.prod.yml restart app`. The script prints
  that command; `AdminAccountScriptTest.aRunningTierKeepsTheOldPasswordUntilRestart`
  pins the behaviour.
- **Tests.** `AdminAccountScriptTest` runs the shipped script inside the
  PostgreSQL container with a psql argv shim (so a password in argv fails the
  test). `AdminRunbookTest` pins that `docker_deployment.md` documents every
  subcommand in the usage block.
