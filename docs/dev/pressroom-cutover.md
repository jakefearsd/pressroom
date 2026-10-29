# The Pressroom cutover (0.4.x to 0.5.0, once per host)

0.5.0 renames the database, the role, the tables (V041), the images, the
volumes, the compose project and the deploy directory. A 0.4.x host therefore
does not upgrade: it cuts over, once. `upgrade.sh` refuses the move in either
direction and points here. Today there is one such host, docker1.

The cutover copies the old stack into a new project and renames the copy.
Nothing in the old directory and nothing in the old volumes is modified, so
until the cleanup the rollback puts the old stack back as it was at step 5.
The scripts are `deploy/cutover-pressroom.sh` and `deploy/rollback-pressroom.sh`,
and each one's header is its manual. They are tested against a stub `docker`
in `CutoverScriptTest` and `RollbackScriptTest`, and rehearsed for real on a
copy of production (plan Task 12).

## What the cutover does, in order

Every step stops the script on failure. Up to step 4, the old site keeps
serving throughout.

1. It refuses unless all of these hold: `.env` is on 0.4.x; `POSTGRES_USER`
   and `POSTGRES_DB` are the old defaults, given or absent; `~/pressroom` does
   not exist; every old volume exists; and no new volume exists.
2. It fetches the release's `docker-compose.prod.yml`, `deploy.sh`,
   `env.example` and `upgrade.sh` into `~/pressroom`.
3. It takes a backup through the old stack's `backup` service. It then copies
   that backup's files out of the backup volume into
   `~/pressroom-cutover-backup-<UTC stamp>/`: the app, umami and listmonk
   dumps and the volumes archive. It prints that path. Last, it records row
   counts into `~/pressroom/cutover-counts-before.txt`. The counts cover the
   app tables, umami's `website`/`website_event` and listmonk's
   `subscribers`.
4. It writes `~/pressroom/.env` from the old one:
   - `ROLLER_*` keys become `PRESSROOM_*`. <!-- rebrand:keep -->
   - The role, the database and the JDBC URL's database become `pressroom`.
   - `IMAGE_VERSION` becomes the new version.
   - Every other byte stays the same.

   It prints the *names* of keys whose value still mentions the old name, and
   no values. It then checks the result with `docker compose config` and
   pulls the new images.
5. It stops the old stack with `down`, which keeps the volumes. From here the
   site is down.
6. It copies each old volume into its new name with `cp -a`, then compares the
   two with `diff -r`. The source is mounted read-only.
7. It starts the new postgres alone.
8. It renames the database and the role. A temporary superuser does this,
   because a role cannot rename itself. If the rename cleared an MD5 password,
   it sets the password again from `.env`. It then proves that a password
   login over TCP works.
9. It runs `~/pressroom/deploy.sh`. The provision step applies V041, and the
   site is back up.
10. It recounts under the renamed tables into `cutover-counts-after.txt`. It
    fails if any count fell. A count that grew is reported, not failed: the
    old site served until step 5, and the new one serves from step 9.
11. It prints the manual steps below.

The downtime runs from step 5 to the end of step 9, and is mostly the volume
copy.

## Before

- **The 0.5.0 release is published:** both images
  (`ghcr.io/jakefearsd/pressroom`, `ghcr.io/jakefearsd/pressroom-caddy`), and
  the release files, `upgrade.sh` among them.
- **The GitHub repository is renamed** to `jakefearsd/pressroom` (plan ruling
  1). GitHub redirects the old URL, but `upgrade.sh` and the cutover
  download from the new one.
- **jakemon's alerts for the blog are silenced** for the window.
- **Jake knows the gap is coming:** the site is down from step 5 to step 9.
- **Open editor drafts are saved or published.** Their localStorage keys
  change with 0.5.0, so an unsaved draft is not offered back afterwards.
- **docker1 has room for a second copy of every volume.** Compare
  `docker system df -v` with `df -h /var/lib/docker`.
- **The ssh connection will hold.** A dropped session stops the script where
  it is. The old stack is still safe then, and the rollback below recovers.

## Run it

```bash
bin/deploy-host.sh docker1 0.5.0 --cutover --url https://blog.wikantik.com/
```

This copies both scripts into `~/roller` and starts the cutover there. <!-- rebrand:keep -->
The cutover runs detached from the ssh session, under `setsid nohup`, and
logs to `~/pressroom-cutover-<UTC stamp>.log`. `deploy-host.sh` follows that
log until the cutover exits, then reports the exit status. It then requires
the public URL to answer 200.

If the connection drops, the cutover keeps going. To reattach, using the log
name that `deploy-host.sh` printed:

```bash
ssh docker1 'tail -f ~/pressroom-cutover-<stamp>.log'
ssh docker1 'cat ~/pressroom-cutover-<stamp>.log.exit'   # the exit status, once it is done
```

## After the cutover

Checks:

- **Health.** On docker1, in `~/pressroom`, run
  `docker compose -f docker-compose.prod.yml ps`. Every service is up, and `app`
  is healthy on `ghcr.io/jakefearsd/pressroom:0.5.0`.
- **A weblog page** renders, with its images.
- **An admin login** works.
- **Old admin links redirect.**
  `curl -sI https://blog.wikantik.com/roller-ui/login.rol` <!-- rebrand:keep -->
  answers 301, with `Location: /pressroom-ui/login.rol`.
- **MCP works.** An MCP call with a newly minted `prs_` token succeeds.
- **umami and listmonk answer and hold their data.** Their counts in
  `cutover-counts-after.txt` are no lower than before.

Manual steps:

- **Google console:** add the redirect URI
  `https://blog.wikantik.com/pressroom-ui/sso/callback`. Remove the old one
  once sign-in works.
- **The agent token:** revoke the old agent token row, then mint a new `prs_`
  token for blogger-agent. Old-prefix tokens are refused from 0.5.0.
- **The CLI credentials:** on the machine that holds them, run
  `mv ~/.roller ~/.pressroom`. <!-- rebrand:keep -->
- **Prose:** the privacy page and the `site.description` prose are Jake's call
  (Phase 3).

Accepted side effects:

- **Users are signed out once.** They sign in again.
- **The old dev volumes are orphaned** on developer machines (dev only). The
  `./pressroom` script starts fresh ones, and the old ones can be removed by
  hand.
- **jakemon's container-name relabel goes dark.** The relabel
  (`roller-.+`) matches nothing until Phase 4. <!-- rebrand:keep -->
- **Grafana has no datasource on the app database yet** (Jake confirmed it is
  not wired up), so the renamed database, role and tables break nothing
  there.

## Rollback

```bash
ssh docker1 'cd roller && bash rollback-pressroom.sh --yes'   # rebrand:keep
```

Without `--yes` it refuses, so a stray run cannot take a healthy site back
to 0.4.x. The rollback:

1. stops the new stack, keeping its volumes;
2. starts the old directory's stack on its untouched `.env` (0.4.x) and its
   old volumes, with `docker compose up -d --pull never`. It uses the 0.4.2
   images already on the host, because the old `deploy.sh` would pull first,
   and a registry that cannot be reached must not block a rollback;
3. waits for the app to report healthy.

Anything written to the new stack since the cutover does not come back.
That covers posts, comments, subscribers and page views. The rollback works
for as long as the old directory and volumes are kept. A later `upgrade.sh`
on `~/pressroom` says so when it prints its own rollback.

## Trying again

A second cutover refuses while `~/pressroom` or any new volume exists. Fix
the cause first, then do the following.

1. Make sure the new stack is down. The rollback does this.
2. Remove the new directory and volumes. They hold only copies:

   ```bash
   rm -r ~/pressroom
   docker volume rm pressroom_pressroom-pgdata pressroom_pressroom-mediafiles \
       pressroom_pressroom-search-index pressroom_pressroom-uploads \
       pressroom_pressroom-backups pressroom_caddy-data pressroom_caddy-config
   ```

3. Run the cutover again.

## Cleanup, after 30 days

Keep the old directory, its volumes and the 0.4.2 images for 30 days.

**The pre-cutover backup lives in `~/pressroom-cutover-backup-<UTC stamp>/`
on docker1**, outside every volume. The old backup volume was also copied
into the new one, but the new stack's rotation deletes those copies once
they are older than `BACKUP_RETENTION_DAYS`. The host directory survives
the cleanup below. It stays until Jake deletes it.

Once the new stack has run for 30 days and its own daily backups have
rotated in, run:

```bash
rm -r ~/roller                                                    # rebrand:keep
for v in pgdata mediafiles search-index uploads backups; do docker volume rm "roller_roller-$v"; done  # rebrand:keep
docker volume rm roller_caddy-data roller_caddy-config            # rebrand:keep
docker image rm ghcr.io/jakefearsd/roller:0.4.2 ghcr.io/jakefearsd/roller-caddy:0.4.2  # rebrand:keep
```

After the cleanup, the only way back to 0.4.x is a restore from
`~/pressroom-cutover-backup-<UTC stamp>/`, onto a 0.4.2 stack.
