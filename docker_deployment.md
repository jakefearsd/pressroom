# Deploying Roller with Docker

A from-scratch runbook for taking Roller from a fresh VPS to a TLS-terminated,
backed-up, one-command-upgradeable deployment using `docker-compose.prod.yml`.
This describes the actual production stack shipped in this tree, not a
hypothetical one — every file it references exists in the repo.

If you're looking for the **local development** stack instead (Postgres
only, run the app via `./roller dev`), see the root `docker-compose.yml` and
`CLAUDE.md`. This document is for a real deployment on a server with a
public IP.

## Contents

1. [Prerequisites](#prerequisites)
2. [Get the deploy files onto the host](#get-the-deploy-files-onto-the-host)
3. [Configure `.env`](#configure-env)
4. [DNS](#dns)
   - [Behind a tunnel or a proxy on another host](#behind-a-tunnel-or-a-proxy-on-another-host)
5. [First run](#first-run)
   - [Mail and account recovery](#mail-and-account-recovery)
6. [TLS](#tls)
7. [Health monitoring](#health-monitoring)
8. [Analytics](#analytics)
9. [Newsletter](#newsletter)
10. [Reader sign-in (Google)](#reader-sign-in-google)
11. [Backup and restore](#backup-and-restore)
12. [Test a release locally before deploying it](#test-a-release-locally-before-deploying-it)
13. [Upgrades](#upgrades)
14. [Firewall](#firewall)
15. [Troubleshooting](#troubleshooting)

## Prerequisites

- A host with a public IP and a Docker Engine with the Compose plugin,
  **v2.17 or newer** (`docker compose version`) — the compose file uses
  `depends_on: condition: service_completed_successfully`, which older
  Compose rejects with a schema error rather than misbehaving quietly. Any
  VPS-sized box works otherwise; Roller plus Postgres is comfortable on
  1 vCPU / 2GB RAM for a handful of low-traffic blogs.
- A domain name (e.g. `blog.example.com`) with an A/AAAA record you control,
  **or** none at all if you only want to run over plain HTTP on a LAN (see
  [DNS](#dns) — Caddy supports both modes).
- Ports 80 and 443 reachable from the internet (Caddy needs 80 for the ACME
  HTTP-01 challenge even if you only ever browse over HTTPS), **or** a tunnel
  connector such as `cloudflared` on another machine that forwards to this
  host (see [Behind a tunnel or a proxy on another
  host](#behind-a-tunnel-or-a-proxy-on-another-host)).
- Outbound SMTP relay credentials if you want password-reset mail to work
  (`mail.hostname`/`mail.port`/optional `mail.username`/`mail.password`).
  Roller runs fine without mail configured; that feature just won't send
  anything.
- Nothing needs to be pre-installed on the host besides Docker: the app image
  bakes in its own JRE, themes, and migration SQL (see `Dockerfile`); Caddy
  and Postgres run from their own images. The app image also bakes in
  `cwebp` (the `webp` package, Debian's name for libwebp-tools), so WebP
  renditions of uploaded images are generated in production automatically.
  If you ever run the WAR outside this image, install libwebp-tools/webp
  yourself — Roller feature-detects the binary at startup and quietly falls
  back to a JPEG/PNG-only rendition ladder when it is missing.

## Get the deploy files onto the host

There is no git checkout on the deploy host at all. Everything the stack
needs lives in two published images (`ghcr.io/jakefearsd/roller` and
`ghcr.io/jakefearsd/roller-caddy`) — nothing is bind-mounted from a working
tree. The host needs exactly three files, all attached to the GitHub Release
for the version you're deploying: `docker-compose.prod.yml`, `env.example`,
and `deploy.sh`. Download them into one directory, e.g. `/opt/roller`:

```bash
mkdir -p /opt/roller && cd /opt/roller
curl -LO https://github.com/jakefearsd/roller/releases/download/v0.1.0/docker-compose.prod.yml
curl -LO https://github.com/jakefearsd/roller/releases/download/v0.1.0/env.example
curl -LO https://github.com/jakefearsd/roller/releases/download/v0.1.0/deploy.sh
chmod +x deploy.sh
mv env.example .env
```

(The release asset is named `env.example`, not `.env.example` — GitHub
renames any release asset whose filename starts with a dot, so a link built
from the dotted name would 404.)

(If you maintain a different fork, the `IMAGE_VERSION` tag in your `.env`
still resolves against `ghcr.io/jakefearsd/roller` unless you also edit the
image names in `docker-compose.prod.yml` to point at your fork's GHCR path.)

## Configure `.env`

`docker-compose.prod.yml` reads all of its configuration — infrastructure
settings **and** the app's own runtime properties — from a `.env` file next
to it (gitignored — it holds secrets). You already renamed the downloaded
`env.example` to `.env` above; edit it in place.

The `app` service loads `.env` wholesale via `env_file: .env`, not just the
`ROLLER_*` keys `WebloggerConfig` actually reads — every variable in the
file, including `POSTGRES_PASSWORD`, `UMAMI_APP_SECRET` and
`LISTMONK_ADMIN_PASSWORD`, ends up in the `app` container's environment and
is visible to anything that can read it: `docker inspect`, `/proc/1/environ`
inside the container, `docker compose config`. This is a deliberate
tradeoff (one file, one place to look, matching what a bare-metal deploy
would keep in environment variables anyway), not an oversight — but it does
mean `.env`'s file permissions and who has shell access to the host are the
actual secret boundary, same as they would be for any other
environment-variable-configured container.

`.env` has four parts:

- **Images.** `IMAGE_VERSION` is the tag shared by both
  `ghcr.io/jakefearsd/roller` and `ghcr.io/jakefearsd/roller-caddy` — they are
  published together from one release job and must move together. Published
  only when a `v*.*.*` tag is pushed (`.github/workflows/release.yml`), so
  this is always a specific release, never "whatever was on master." Pin it
  to the version you intend to run rather than floating; see
  [Upgrades](#upgrades) for how to move it forward deliberately.

  **After the first tagged release:** GHCR packages are private by default
  the first time they're published, so an anonymous `docker compose pull`
  will fail with "denied"/"unauthorized" until you do one of the following:
  on GitHub, go to the package's own page (under your account/org's Packages
  tab) → Package settings → Change visibility → Public; or keep it private
  and instead `docker login ghcr.io` on the VPS with a PAT that has
  `read:packages`.
- **`SITE_DOMAIN`** — your real domain (e.g. `blog.example.com`) for
  auto-TLS, or `:80` for a domain-less LAN/testing deployment with plain
  HTTP only (see [TLS](#tls)). Deliberately not named `ROLLER_DOMAIN`: that
  prefix is reserved for app configuration (below), and a stray
  `ROLLER_DOMAIN` would be overlaid onto the app's properties as a junk key
  named `domain`.
- **The `ROLLER_*` block — the app's entire runtime configuration.** There is
  no properties file in the image; every property the app reads comes from
  `ROLLER_*` variables in this file, overlaid onto the image's built-in
  defaults by `WebloggerConfig.applyEnvironmentOverrides` at startup: strip
  the `ROLLER_` prefix, lowercase the remainder, turn `_` into `.` to get the
  property name — so `ROLLER_DATABASE_JDBC_USERNAME` sets
  `database.jdbc.username`, `ROLLER_MAIL_HOSTNAME` sets `mail.hostname`, and
  so on for any key documented in
  `app/src/main/resources/org/apache/roller/weblogger/config/roller.properties`.
  The variables `.env.example` ships (and their meaning) are:
  - `ROLLER_INSTALLATION_TYPE=auto` — checks the schema at startup; by then
    the `provision` service has already applied every pending migration (see
    [First run](#first-run)).
  - `ROLLER_DATABASE_CONFIGURATIONTYPE`, `ROLLER_DATABASE_JDBC_DRIVERCLASS`,
    `ROLLER_DATABASE_JDBC_CONNECTIONURL` — fixed by the image's layout and the
    compose network (the connection URL's host is the `postgres` service
    name); normally leave these as shipped.
  - `ROLLER_DATABASE_JDBC_USERNAME` / `ROLLER_DATABASE_JDBC_PASSWORD` — **must
    match** `POSTGRES_USER` / `POSTGRES_PASSWORD` below. The two are not
    templated against each other; keep them in sync by hand. The same is true
    of the database **name**: `ROLLER_DATABASE_JDBC_CONNECTIONURL`'s trailing
    path segment (`.../rollerdb`) and `POSTGRES_DB` below are two independent
    strings, not one templated value. Changing `POSTGRES_DB` alone makes
    `provision` create and migrate a database under the new name while the
    app's connection URL still points at the old one — the app fails to
    bootstrap against a database that was never migrated, rather than
    against a missing one, which reads like a permissions problem rather
    than the name mismatch it actually is. Change both together.
  - `ROLLER_THEMES_DIR`, `ROLLER_MEDIAFILES_STORAGE_DIR`,
    `ROLLER_SEARCH_INDEX_DIR`, `ROLLER_UPLOADS_DIR` — match the image's own
    layout and the volumes `docker-compose.prod.yml` mounts; no reason to
    change them.
  - `ROLLER_MAIL_CONFIGURATIONTYPE`, `ROLLER_MAIL_HOSTNAME`,
    `ROLLER_MAIL_PORT`, and optionally `ROLLER_MAIL_USERNAME` /
    `ROLLER_MAIL_PASSWORD` — your SMTP relay, for password-reset and
    notification mail. Roller runs fine without these set; those two
    features just won't send anything. `ROLLER_MAIL_SECURITY` defaults to
    `starttls` (required, not just offered, once username/password are set),
    use `ssl` for a port-465 relay or `none` only for a local relay with no
    TLS.
  - `ROLLER_NEWSLETTER_LISTMONK_BASEURL` — where `/newsletter/subscribe`
    forwards to. Blank makes that endpoint return 503; see
    [Newsletter](#newsletter).
- **Postgres credentials, backup schedule, Analytics and Newsletter
  settings** — `POSTGRES_DB`/`POSTGRES_USER`/`POSTGRES_PASSWORD` (shared by
  `postgres`, `provision`, `admin`, and `backup` — generate a real password with
  `openssl rand -base64 33 | tr '+/' '-_'`: 44 characters, no padding, only
  URL-safe ones, because the compose file splices `POSTGRES_PASSWORD` into
  Umami's `DATABASE_URL` and a `/` from plain base64 breaks that URL),
  `BACKUP_HOUR`/`BACKUP_RETENTION_DAYS`
  (defaults `3`, `14` are reasonable starting points), `JAVA_OPTS` (optional
  extra JVM flags, e.g. `-Xmx1g`), and the `UMAMI_*`/`LISTMONK_*` variables
  covered in their own sections below ([Analytics](#analytics),
  [Newsletter](#newsletter)).

## DNS

Point your domain's A (and AAAA, if you have an IPv6 address) record at the
host's public IP **before** starting the stack. Caddy requests a Let's
Encrypt certificate on first request to the domain and needs port 80
reachable from the public internet to complete the ACME HTTP-01 challenge;
if DNS isn't live yet, certificate issuance fails and Caddy retries with
backoff until it is.

If you want the analytics dashboard (see [Analytics](#analytics)) or the
newsletter (see [Newsletter](#newsletter)), point a second and third name —
`analytics.example.com` and `newsletter.example.com`, say — at the same IP and
set `UMAMI_DOMAIN` / `LISTMONK_DOMAIN` to them. Caddy obtains a certificate for it the same way. Leave
it unset and the dashboard is simply unreachable; the blog still works and
still collects nothing.

If you don't have a domain (LAN deployment, quick local test of the
production stack), set `SITE_DOMAIN=:80` in `.env` instead — Caddy then
serves plain HTTP on port 80 only and never attempts ACME/TLS (a bare port
with no hostname isn't a certificate-able name, so automatic HTTPS simply
doesn't activate for it). See `deploy/caddy/Caddyfile` for the exact logic.
`SITE_HTTP_PORT` in `.env` moves the host side of that port (for example to
8080); Caddy itself still listens on 80 inside its container.

### Behind a tunnel or a proxy on another host

Some hosts never face the internet. A connector on another machine (the
Cloudflare Tunnel daemon `cloudflared`, or a reverse proxy) receives public
traffic, holds the public certificate, and forwards plain HTTP to this host
over the LAN. For that layout:

1. **In `.env`**, keep Caddy on plain HTTP, name the connector's machine as
   the proxy whose forwarded headers Caddy keeps and, if 80 and 443 belong to
   something else on this host, move the published ports:

   ```bash
   SITE_DOMAIN=:80
   TRUSTED_PROXIES=192.168.5.50   # the connector's address; separate several with spaces
   SITE_HTTP_PORT=8080     # host side only; Caddy still listens on 80 in the container
   SITE_HTTPS_PORT=8443    # unused in this layout, but published; moved off 443
   ```

   Don't set `SITE_DOMAIN` to the public hostname. Caddy would then redirect
   port 80 to HTTPS, and a connector that forwards to port 80 gets that
   redirect back for every request, which loops. (`http://blog.example.com`
   also works, if you want Caddy to answer only that name.)
2. **Apply it** with `./deploy.sh`, or `docker compose -f docker-compose.prod.yml up -d`.
   The app takes its whole configuration from `.env`, so any `.env` change
   recreates the app as well as Caddy: expect a short restart.
3. **Point the connector** at `http://<this host>:<SITE_HTTP_PORT>`, for
   example `http://docker2.lan:8080`, and let it pass the request's `Host`
   header through unchanged. `cloudflared` does by default.
4. **Set the site's address.** In Admin -> Global Config, set **Absolute URL
   to site (if required)** to the public `https://` address. Feed ids, the
   sitemap and password-reset links are built from it. A value saved there
   wins over `ROLLER_SITE_ABSOLUTEURL`.

**Why `TRUSTED_PROXIES`.** Caddy discards `X-Forwarded-For` and
`X-Forwarded-Proto` from any peer it doesn't trust, writing the peer's own
address and `http` in their place. With it unset behind a connector, the
app's per-client throttles (password reset, contact form, newsletter sign-up)
count every reader as the connector, and the app takes every request for
plain HTTP, so redirects it builds point at `http://`. With the connector
named, Caddy keeps both headers and appends the connector's address; the app
reads `X-Forwarded-For` right to left and stops at the first address that
isn't private, which is the reader's. Name that one machine, never a private
range: where Docker's userland proxy carries a connection, an internet client
arrives from a private address and could then write its own
`X-Forwarded-For`. A malformed value stops Caddy, and the site with it.
The setting needs the `roller-caddy` image from 0.1.12 on;
older images ignore it. Umami is unaffected either way behind `cloudflared`:
it reads `CF-Connecting-IP` first, which Caddy passes through.

## First run

With `.env` filled in:

```bash
docker compose -f docker-compose.prod.yml up -d
```

There is no separate migration step and no ordering to get right: compose
runs the one-shot `provision` service first (creates the `umami`/`listmonk`
databases, applies the migration chain via `migrate.sh`, grants
`grafana_ro` — see `deploy/provision.sh`), and `app`, `umami` and `listmonk`
all declare `depends_on: { provision: { condition: service_completed_successfully } }`,
so none of them starts until provisioning has exited successfully. `deploy.sh`
(see [Upgrades](#upgrades)) wraps this same `up -d` with a health-check wait
and is safe to use for first run too, but the plain compose command above is
equally correct.

A second one-shot, `analytics-views`, installs the Grafana analytics
contract's traffic view (`deploy/analytics-views.sh`) — but it runs *after*
`umami` has started rather than as part of `provision`, and it gates nothing.
The view it installs, `analytics_traffic`, is defined over `website_event`, a
table Umami creates on its own first boot; applying it from `provision`
(which runs entirely before `umami` is allowed to start) deadlocked every
fresh install, since neither service could go first. `analytics-views` waits
for `website_event` to appear, applies the view, and exits — if it fails or
times out, the blog, Umami and Listmonk are all still up and serving; only
the Grafana traffic view is missing, which is an operator dashboard concern,
not an outage. Check `docker compose -f docker-compose.prod.yml logs
analytics-views` if the Grafana dashboard's traffic panel comes up empty; the
same `up -d analytics-views` re-run mentioned in its own log output is safe.

When it finishes, browse to `https://<your-domain>/` (or `http://<host>/` in
`:80` mode). A fresh install has no accounts and no registration page, so
the first administrator is made from the host, in this order:

1. **Create the admin.** `./deploy.sh` ends by checking for one and, if there
   is none, prints this command:

   ```bash
   docker compose -f docker-compose.prod.yml run --rm admin create --username NAME --email ADDRESS
   ```

   It prompts for the password twice. For a script, pipe one line in and use
   `-T`: `docker compose -f docker-compose.prod.yml run --rm -T admin create
   --username NAME --email ADDRESS --password-stdin`. The username is ASCII
   letters and digits only; the email (at most 255 characters) must be a real address you read, because
   password-reset links are sent there; the password is at least 8
   characters. The `admin` service is in the `tools` profile, so `up -d`
   never starts it. Being able to run it at all means controlling this host
   and its `.env`, which is the trust it relies on.
2. **Log in** at `/roller-ui/login.rol`, then in Admin -> Global Config set
   **Absolute URL to site (if required)** (`ROLLER_SITE_ABSOLUTEURL`) and
   **Site Administrator's email address** (`site.adminemail`). That address is the From address of every mail Roller sends, and
   password-reset mail cannot go out without it.
3. **Set up mail**, next section: [Mail and account recovery](#mail-and-account-recovery).
4. **Check it.** `docker compose -f docker-compose.prod.yml run --rm admin status`
   lists the enabled admin accounts (and exits 3 with the create command if
   there are none).

Then create a weblog.

### Mail and account recovery

Roller sends password-reset and notification mail through an SMTP relay.
Worked example with Brevo, in `.env`:

```bash
ROLLER_MAIL_CONFIGURATIONTYPE=properties
ROLLER_MAIL_HOSTNAME=smtp-relay.brevo.com
ROLLER_MAIL_PORT=587
ROLLER_MAIL_USERNAME=<Brevo SMTP login>
ROLLER_MAIL_PASSWORD=<Brevo SMTP key>
```

`mail.security` defaults to `starttls`, which is what port 587 wants (`ssl`
for a port-465 relay, `none` only for a local relay). The From address is the
Site Administrator's email address, so it must be a sender or domain you have verified in Brevo
or the relay will refuse the mail.

Reset mail needs **both** halves: the SMTP settings above **and** the site
admin email (`site.adminemail`, labelled **Site Administrator's email address**
in Admin -> Global Config). Until both are set, the forgot-password page says
the server has no outgoing mail configured (or no site email address set). The reset link
is built from **Absolute URL to site (if required)** (`ROLLER_SITE_ABSOLUTEURL`), lasts 1 hour, works
once, and goes to the email address on the account.

**Applying changes.** A change to `.env` needs
`docker compose -f docker-compose.prod.yml up -d --force-recreate app`
(a plain `restart` does not re-read `.env`). Roller caches runtime settings
and loaded accounts, so a setting or account changed directly in the database
needs `docker compose -f docker-compose.prod.yml restart app`.

**Recovering a lost password**, in order:

1. "Forgot your password?" on the login page. Enter the username or the email
   address.
2. Last resort, when mail is broken, from the host:

   ```bash
   docker compose -f docker-compose.prod.yml run --rm admin reset-password --username NAME
   docker compose -f docker-compose.prod.yml restart app
   ```

   The restart is required: the running app keeps the accounts it has loaded,
   so it still holds the old password until it restarts.

### The context path

Roller serves from the **root**: a weblog is at `https://<domain>/<handle>/`,
not `https://<domain>/roller/<handle>/`. That is the shipped default
(`server.servlet.context-path=/`), and it is deliberate — the segment would
otherwise end up in every canonical url, `og:url`, sitemap `<loc>`, feed id and
inbound link, where removing it later costs a redirect map rather than a config
edit.

To deploy under a prefix instead — sharing a hostname with another app, or
matching an existing installation's urls — set it in `.env`:

```bash
SERVER_SERVLET_CONTEXT_PATH=/roller
```

`env_file` passes the whole `.env` into the app container and Spring Boot's
relaxed binding maps that variable onto `server.servlet.context-path`, so no
image change is involved. Nothing else needs adjusting: Caddy already proxies
`/*` wholesale, and the app builds every url it emits — links, redirects,
canonicals — from the context path the request actually arrived on.

**Upgrading a site that was already crawled at `/roller`** (Roller ≤ 0.1.3
defaulted there): either keep the old urls with the variable above, or move to
the root and add a permanent redirect ahead of the `reverse_proxy` in the
Caddyfile, so existing inbound links and the search index survive the move:

```caddyfile
# Inside the {$SITE_DOMAIN} block, BEFORE `handle { reverse_proxy app:8080 }`.
# The bare /roller needs its own line: the capture group in the regexp below
# would be empty for it, and an empty redirect target is not a url.
redir /roller / permanent
@rollerlegacy path_regexp rollerlegacy ^/roller(/.+)$
redir @rollerlegacy {re.rollerlegacy.1} permanent
```

### Custom domains (virtual hosting)

A weblog can serve from its own domain — `blog.example.org` instead of
`https://<SITE_DOMAIN>/<handle>/` — with no per-blog deploy step. This works
by putting a **wildcard** certificate on the proxy for a whole DNS zone, so
adding another weblog's custom domain later needs only a DNS record and a
Weblog Settings edit, not a new certificate or a Caddy change.

A wildcard can only be proven with the ACME **DNS-01** challenge (unlike
`SITE_DOMAIN` above, which uses ordinary HTTP-01) — `*.example.org` has no
single URL an HTTP challenge could be served from. That's why
`deploy/caddy/Dockerfile` builds Caddy with `xcaddy` and the
`github.com/caddy-dns/cloudflare` module baked in, rather than shipping stock
`caddy:*`: DNS-01 needs a provider-specific module compiled into the binary,
and `ProductionComposeTest` fails the build if that stops being true.

Five steps, in order:

0. **Set `site.absoluteurl`** (`ROLLER_SITE_ABSOLUTEURL` in `.env`, or
   Admin → Global Config → "Absolute URL to site"; a value saved on that page
   wins over the variable) to the site's own address, e.g.
   `https://blog.example.com`. This becomes required the moment ANY weblog
   has a custom domain — with it blank, `InitFilter` latches the absolute
   context URL from whichever request happens to arrive first after boot,
   and if that is a custom domain, every weblog WITHOUT one inherits that
   domain's hostname in its own canonical url, `og:url`, feed id, sitemap,
   `robots.txt` and password-reset links. It is also what lets
   `ControlPlaneHostFilter` send `/roller-ui/**` and `/api/**` requests that
   arrive on a custom domain back to the site host, rather than serving the
   admin UI/API on the weblog's own domain; startup logs a warning if it
   finds a weblog with a domain and this still unset.
1. **Tell the app which domains are covered**, so a mistyped custom domain is
   caught on Weblog Settings instead of surfacing later as a browser TLS
   error. `vhost.cert.zones` is a startup property (`roller.properties`), not
   a runtime one — it isn't on Admin Settings and a change needs a restart —
   so set it via the environment override in `.env`:
   ```bash
   ROLLER_VHOST_CERT_ZONES=example.org
   ```
   (comma-separated apex names, e.g. `example.org` for a `*.example.org`
   cert). This is advisory only: nothing refuses to save a domain outside
   these zones, it only warns.
2. **Point the proxy at the same zone**, in `.env`:
   ```bash
   VHOST_ZONE=example.org
   VHOST_DNS_API_TOKEN=<a Cloudflare API token scoped to Zone:DNS:Edit on example.org>
   ```
   See `deploy/.env.example` for how to scope the token narrowly. Restart
   Caddy (`docker compose -f docker-compose.prod.yml up -d caddy`) after
   changing either value — Caddy reads them once, at startup.
3. **Add a wildcard DNS record** at your registrar/DNS provider:
   `*.example.org` → the host's public IP (A/AAAA, same as `SITE_DOMAIN`'s
   own record). Caddy requests the certificate the first time it needs it and
   retries with backoff if the record isn't live yet, the same as any other
   domain in this stack.
4. **Set the domain on the weblog**: Weblog Settings → Custom domain
   (`blog.example.org`) → Save. The weblog is then reachable at that host
   immediately — `VirtualHostRegistry` resolves the `Host` header to a weblog
   on every request, so there is nothing else to deploy.

**Both `VHOST_ZONE` and `VHOST_DNS_API_TOKEN` are optional.** Left unset,
`docker-compose.prod.yml` substitutes safe placeholders (an unresolvable
`.invalid` zone and a token-shaped-but-fake string) so the wildcard site
block still parses and Caddy still starts — `SITE_DOMAIN` and everything else
keep working exactly as before. Only `SITE_DOMAIN`'s own certificate is real
in that configuration; the placeholder wildcard simply fails to obtain a
certificate in the background, forever, the same harmless way `UMAMI_DOMAIN`/
`LISTMONK_DOMAIN` already do when left at their own defaults (see
[DNS](#dns)).

**Not verified by this repo's automated tests**: actually obtaining a
certificate needs a real DNS zone and real provider credentials, neither of
which exist in CI or in a local checkout. `docker build -f deploy/caddy/Dockerfile .`
(from the repo root) proves the `xcaddy` stage actually compiles with the DNS
module linked in — that's what `ProductionComposeTest` pins — but the DNS-01
handshake against a real Cloudflare zone can only be proven on a real deploy
host. After step 3 above, confirm it actually worked with:
```bash
docker compose -f docker-compose.prod.yml logs caddy | grep -i "vhost\|certificate obtained\|<your-domain>"
curl -vI https://blog.example.org/ 2>&1 | grep -i "SSL certificate\|subject"
```

## TLS

Handled entirely by Caddy (`deploy/caddy/Caddyfile`, baked into the
`ghcr.io/jakefearsd/roller-caddy` image) — there is nothing to configure
beyond setting `SITE_DOMAIN` to a real domain in `.env`. Caddy
obtains a Let's Encrypt certificate for that domain on first use, renews it
automatically well before expiry, and redirects HTTP to HTTPS. Certificate
state lives in the `caddy-data` named volume, so it survives
`docker compose down` (but not `down -v`).

The app honors `X-Forwarded-Proto`/`X-Forwarded-For` from the reverse proxy
(`server.forward-headers-strategy=framework` in `application.properties`), so
absolute URLs the app generates — redirects, sitemap entries — use `https://`
even though Caddy talks plain HTTP to the container. Caddy
sets these headers automatically for proxied requests; there is nothing to
configure, and existing deployments pick this up on their next image update
with no action needed.

That header-forwarding filter only affects `getScheme()`/`isSecure()`/
`getRequestURL()` — it does **not** change what `HttpServletRequest
.getRemoteAddr()` reports. Without a second piece of configuration, every
request the app tier sees, from any reader anywhere on the internet, would
report Caddy's own container IP as its remote address, because Caddy — not
the reader — is the TCP peer. That collapses every per-client throttle
(contact form, newsletter subscribe, password-reset) onto one shared key,
so `server.tomcat.remoteip.remote-ip-header=x-forwarded-for` and
`server.tomcat.remoteip.protocol-header=x-forwarded-proto` (also in
`application.properties`) install Tomcat's `RemoteIpValve`, which rewrites
`getRemoteAddr()`/`getScheme()` from those headers before the request ever
reaches the app. Caddy is this stack's only ingress, and the valve only
trusts `X-Forwarded-For` from its default internal-proxies address ranges
(private/loopback), so an external client cannot spoof its way past a
throttle by forging the header itself — Caddy's own hop is the only one the
valve believes. The security-relevant case is the password-reset throttle,
which suppresses a flood of reset requests per source address; without the
valve it would suppress (or fail to suppress) based on Caddy's address
instead of the reader's.

## Health monitoring

The app's health endpoint (`/actuator/health`, Spring Boot Actuator) lives
on a separate **management port, 8090**, which — per the runtime contract —
is **never published to the host**. `docker-compose.prod.yml` doesn't map
it, and it shouldn't be added to an override file for a production
deployment either: it's meant to be reached only from inside the Docker
network (the `app` service's own healthcheck) or via `docker exec`, never
from the internet.

To check health from the host:

```bash
# Compose's own view (uses the same internal healthcheck):
docker compose -f docker-compose.prod.yml ps

# Or query the endpoint directly via exec:
docker compose -f docker-compose.prod.yml exec app \
    curl -sf http://localhost:8090/actuator/health

# Or inspect Docker's healthcheck state/history directly:
docker inspect --format '{{json .State.Health}}' \
    $(docker compose -f docker-compose.prod.yml ps -q app) | python3 -m json.tool
```

`docker compose ps` reporting `app` as `healthy` is normally enough for
day-to-day monitoring; wire up an external uptime check against the public
`https://<domain>/roller-ui/login.rol` URL (200 OK) for outside-in
monitoring, since that's the only thing actually exposed to the internet.

Logs:

```bash
docker compose -f docker-compose.prod.yml logs -f app
docker compose -f docker-compose.prod.yml logs -f caddy
docker compose -f docker-compose.prod.yml logs -f postgres
docker compose -f docker-compose.prod.yml logs -f backup
```

## Analytics

The stack ships [Umami](https://umami.is): self-hosted, cookie-free page
analytics. It stores nothing on the reader's device, which is why no consent
banner is needed and why it is worth having at all.

### Why the tracker is served from your blog's own domain

Every bundled theme sends a Content-Security-Policy of `default-src 'none'`
with `script-src 'self'` and `connect-src 'self'`. A tracker loaded from
another hostname is therefore blocked by the reader's browser, and its beacon
never sends — you would see an empty dashboard and no error anywhere.

So Caddy serves the two pieces the policy governs from the blog's own origin:

| URL | What it is |
| --- | --- |
| `https://<your blog>/analytics/script.js` | the tracker |
| `https://<your blog>/analytics/api/send` | where it reports |

The dashboard is a separate hostname (`UMAMI_DOMAIN`), because Umami's UI
assumes it lives at a domain root — its `BASE_PATH` is set when the image is
built, and the prebuilt image does not carry one. No theme CSP applies to the
dashboard, so this costs nothing.

### Turning it on

1. Set `UMAMI_APP_SECRET` (`openssl rand -hex 32`) and, if you want the
   dashboard, `UMAMI_DOMAIN` in `.env`.
2. Run `./deploy.sh` (or `docker compose -f docker-compose.prod.yml up -d`).
   The `provision` service creates the `umami` database inside the existing
   Postgres instance if it is missing, and the nightly backup starts dumping
   it alongside the blog's own.
3. Open the dashboard and sign in with `admin` / `umami`. **Change that
   password immediately** — it is the documented default and the dashboard is
   on the public internet.
4. Add a website in Umami for each weblog and copy its website ID.

### Pointing a weblog at it

Analytics are per-weblog and opt-in, through a field dedicated to it —
**not** the free-text "Analytics tracking code" textarea that has always
lived on the same Settings page. That textarea only renders when *Server
Administration → Configuration → Allow analytics code override* is on **and**
`weblogAdminsUntrusted` is off — and this fork keeps `weblogAdminsUntrusted`
on everywhere (see `CLAUDE.md`), so the textarea has never actually been
reachable. If an earlier revision of this document told you to paste a
`<script>` snippet into it, that procedure never worked; disregard it.

The real steps, once [Turning it on](#turning-it-on) above has a website
registered in Umami:

1. In Umami, open the website and copy its **Website ID** — the bare UUID
   Umami shows under the website's settings, not the `<script>` snippet
   Umami also offers on the same screen (that snippet is the impossible
   textarea path above; ignore it).
2. In Roller, as the weblog's owner or a site admin: *Settings → Weblog
   Settings → Analytics website ID*, paste the UUID, save. Roller validates
   it as a UUID and rejects anything else.
3. Optionally also set *Analytics share URL* to Umami's public share link
   for the site. This is display-only — a convenience link shown back to the
   editor on the Settings page — and plays no part in tracking.

`#showAnalyticsTrackingCode` (called from every bundled theme's `<head>`)
builds the `<script defer src="…" data-website-id="…" data-host-url="…">`
tag itself from the validated UUID plus two startup properties
(`analytics.umami.basePath`, default `/analytics`; `analytics.umami.scriptName`,
default `script.js`) — nothing an operator or weblog admin types is ever
emitted as raw HTML into the page head. That is precisely what lets this
coexist with `weblogAdminsUntrusted` staying on: there is no admin-authored
markup anywhere in the path. If you
set `UMAMI_SCRIPT_NAME` to something other than `script.js` when deploying
(the cheapest defence against content blockers that match the default
path), also set `ROLLER_ANALYTICS_UMAMI_SCRIPTNAME` to the same value in
`.env` or the tag will
point at a script Caddy never serves.

Nothing is emitted for a weblog whose Analytics website ID is blank, so this
stays opt-in per blog and off until an admin sets it.

### The Grafana contract

Roller and Umami both expose their data to Grafana as small, versioned SQL
**views** — never raw tables, and Grafana is never granted anything beyond
`SELECT` on those views.

**Two databases, two view halves, and why.** `rollerdb` and Umami's own
database (`${UMAMI_DB:-umami}`) both live inside the one shared Postgres
instance, but PostgreSQL has no cross-database queries, so the contract is
split down that seam — each half lives with the data it reads:

| View | Lives in | Ships via | What it holds |
| --- | --- | --- | --- |
| `analytics_events` | `rollerdb` | `bin/db/migrations/V017__analytics_contract.sql` (the migration chain) | First-party outcomes from `roller_event` — form submissions, newsletter subscriptions, entry publishes — grouped by weblog handle, event type, day |
| `analytics_weblog_sites` | `rollerdb` | same migration | The join key: which Umami website UUID belongs to which weblog handle |
| `analytics_traffic` | `${UMAMI_DB:-umami}` | `deploy/analytics/umami-views.sql` (baked into the app image as `/app/umami-views.sql`), applied by the separate `analytics-views` one-shot (`deploy/analytics-views.sh`), not `provision` and not the migration chain (it can only reach `rollerdb`) — see [First run](#first-run) for why it runs after `umami` starts rather than before | Umami's raw `website_event` rolled up to sessions/views by website, path and day |

Grafana joins the two halves itself: two PostgreSQL datasources (one per
database, both authenticating as `grafana_ro`), with `analytics_traffic.website_id`
joined to `analytics_weblog_sites.website_id` using a panel-level join
transformation (Grafana's "Outer join" / "Join by field" transform) —
Postgres cannot do this join server-side because it spans two databases.

**`grafana_ro`: created `NOLOGIN`, enabled out of band.** `V017` creates the
role with `CREATE ROLE grafana_ro NOLOGIN` (guarded by a `DO $$ … EXCEPTION
WHEN duplicate_object …` block, since roles are cluster-global and the
migration chain re-applies on every deploy) and grants it `SELECT` on
exactly the contract views — never the underlying tables, never
`roller_user`'s privileges. A migration cannot carry a secret, so the role
ships with no password and cannot log in until you set one:

```bash
docker compose -f docker-compose.prod.yml exec postgres \
    psql -U "${POSTGRES_USER:-roller}" -d "${POSTGRES_DB:-rollerdb}" \
    -c "ALTER ROLE grafana_ro LOGIN PASSWORD 'choose-a-strong-password-here';"
```

The `provision` service already grants `grafana_ro` `CONNECT` on both
databases (see `deploy/provision.sh`), so the one role/password pair you set
above works for both Grafana datasources — just point one at `rollerdb` and
the other at `${UMAMI_DB:-umami}`, same credentials.

**Access is tunnel-only.** Postgres never gets a published host port in this
stack (see [Firewall](#firewall) — port 5432 is reachable only on the
internal Docker network). Point Grafana's datasources at an SSH tunnel or a
bastion into the host, never at a directly exposed port; do not add a host
port mapping for `postgres` to make this easier.

**Two labels on `analytics_events` are untrusted display text, not
metadata.** `page_slug` and `entry_anchor` on `FORM_SUBMITTED` rows are
copied from the contact form's reader-controlled `source` field — a visitor
chooses that text, not Roller. Treat them as display strings in any
dashboard, never as something safe to interpolate elsewhere. Separately,
`ENTRY_PUBLISHED` counts publish **events**, not distinct published entries:
unpublishing and republishing an entry records a second event, so a
"posts published" panel built naively from this view will over-count
republished entries.

## Newsletter

The stack ships [listmonk](https://listmonk.app): a self-hosted mailing list
manager. It owns the subscriber list, the double opt-in flow, the sending and
the unsubscribe links. **Roller stores no subscriber data at all**, which is
what keeps consent and retention obligations out of the blog software.

### Turning it on

1. Set `LISTMONK_ADMIN_PASSWORD`, and — if you want subscribers to be able to
   confirm — `LISTMONK_DOMAIN` and `LISTMONK_ROOT_URL` in `.env`. The two must
   agree: listmonk builds its confirmation and unsubscribe links from
   `LISTMONK_ROOT_URL`, and those links are the only way a subscriber can
   confirm or leave.
2. Run `./deploy.sh` (or `docker compose -f docker-compose.prod.yml up -d`).
   The `provision` service creates the `listmonk` database, and listmonk
   creates its own schema on first start.
3. Sign in at `https://<LISTMONK_DOMAIN>/admin` with `admin` and the password
   you set.
4. **Set up SMTP** under *Settings → SMTP*. Nothing sends until you do; those
   credentials live in listmonk's own database, not in `.env`.
5. Create a list, set it to **double opt-in**, and copy its UUID.

`.env.example` already sets `ROLLER_NEWSLETTER_LISTMONK_BASEURL=http://listmonk:9000`
— that's the compose **service name**, reached over the internal Docker
network, not `LISTMONK_DOMAIN` (which is for a browser reaching the
opt-in/unsubscribe pages, not for Roller reaching the API). You normally
don't need to touch it; blank (the `roller.properties` built-in default,
what you'd have if the variable were unset entirely) is what makes
`/newsletter/subscribe` return 503 in local dev, where no listmonk service
exists — that's the intended fallback, not a bug.

### Putting a subscribe form on a blog

Two ways, both feeding the same weblog field (Weblog Settings → Newsletter
list UUID) and the same client-side injection
(`#showAudienceAssets`/`data-list-uuid`):

- The `[subscribe]` shortcode, dropped into any entry or page body.
- The shared template library's macro, for a fixed spot in a theme (a
  sidebar, a footer):

  ```velocity
  #showSubscribeForm($model.weblog "Get new guides by email")
  ```

  It takes the weblog, not a raw UUID — the macro reads
  `$weblog.newsletterListUuid` itself and renders nothing at all when that
  field is blank, so a theme can call it unconditionally on every page.

The form posts to `/newsletter/subscribe` **on the blog's own domain**, which
is required, not decorative: every bundled theme sends `connect-src 'self'`,
so a form posting to `newsletter.example.com` would be blocked by the
reader's browser and nothing would happen. **Roller itself serves that
endpoint** — `NewsletterController` throttles it, checks the honeypot,
refuses to forward a list uuid this install never configured, records a
first-party `NEWSLETTER_SUBSCRIBED` event on success, and only then forwards
to listmonk over the Docker network using `newsletter.listmonk.baseurl` from
above. This used to be a Caddy `rewrite` straight to listmonk's public API;
it is not anymore — `deploy/caddy/Caddyfile` now carries only a comment where
that rewrite used to be (`/newsletter/subscribe is served by the app itself
(throttle + events); do not re-add a rewrite here.`), because a path-specific
proxy rule in front of the app would silently skip both the throttle and the
event write with no error anywhere. The opt-in and unsubscribe pages still
stay on `LISTMONK_DOMAIN` — those are followed from an email client, where no
theme CSP applies, so routing them through the app buys nothing.

> **Deployments running an older Caddyfile:** the Caddyfile is baked into the
> `ghcr.io/jakefearsd/roller-caddy` image now, not read from a checkout, so
> picking up the current one means pulling a newer `IMAGE_VERSION` (see
> [Upgrades](#upgrades)) — bump it in `.env` and run `./deploy.sh`. Until you
> do, an old rewrite from before this design keeps forwarding subscribe
> requests straight to listmonk — Roller's own `/newsletter/subscribe` route
> is registered and correct underneath, but Caddy never lets a request reach
> it, so the throttle and the `roller_event` write are silently bypassed the
> whole time the stale image is running.

A subscriber who is already on the list gets the same "check your email"
message as a new one. That is deliberate: a different message would let anyone
use the form to test whether a given address is subscribed.

### Sending

Compose and send one-off campaigns from listmonk's own console, or use the
entry editor's **"Send as newsletter"** button — a synchronous, one-shot send
to the weblog's configured list, stamping `newsletterSentAt` on the entry so
it cannot be sent twice. The button needs a *second*, higher-privilege
credential than the public subscribe endpoint above: a listmonk **API user**.

1. In the listmonk console: **Admin → Users → + New**.
2. Give it the **Super Admin** role (or a custom role with campaign
   create/manage permissions) and set **Type** to *API*.
3. Generate a token and copy both the username and the token immediately —
   listmonk shows the token exactly once.
4. Put both in `.env`:

   ```bash
   ROLLER_NEWSLETTER_LISTMONK_APIUSER=your-api-username
   ROLLER_NEWSLETTER_LISTMONK_APITOKEN=the-generated-token
   ```

5. Redeploy (`./deploy.sh`). Leaving either blank disables only the "Send as newsletter"
   button (`newsletter.notConfigured`) — the public subscribe form keeps
   working regardless, since `ListmonkClient` checks the two credential tiers
   (`isUnconfigured()` for subscribe, `isCampaignConfigured()` for sending)
   independently.

Sending is deliberately manual and synchronous, not queued: an editor clicks
the button, the campaign sends in that same request, and
`weblogentry.newsletter_sent_at` is stamped only once listmonk confirms — the
editor who clicked IS the retry mechanism, since there is no background queue
to retry on their behalf.

> **Not built:** triggering a campaign automatically when a post is
> published. The per-blog list mapping and the API client both exist now
> (above); what's still missing is a retry queue and a hook on *both* publish
> paths (the editor's and the scheduler's — a hook on only the first silently
> skips every scheduled post). Until that exists, publishing a post and
> sending it as a newsletter stay two deliberate, separate acts.

## Reader sign-in (Google)

Readers can sign in with Google and get a `reader` account (no author
rights; comments will use it). It is off until both credentials are set.
Developer notes: `docs/dev/sso.md`.

1. **Create the OAuth client.** In the wiki's Google Cloud project, add an
   OAuth client of type "Web application" named for the blog. Give it one
   authorized redirect URI, matched exactly:
   `https://blog.wikantik.com/roller-ui/sso/callback`. The URI must match
   character for character, scheme and host included, or Google refuses the
   sign-in.
2. **Update the consent screen**, which is shared by the project. Give it a
   suite-level app name, and make its privacy policy cover the blog. The
   blog stores readers' names and email addresses.
3. **Set the Absolute URL** in Global Config to `https://blog.wikantik.com`.
   Setting `ROLLER_SITE_ABSOLUTEURL` in `.env` does the same; the Global
   Config value wins when both are set. The callback is built from this
   address, never from the visitor's host, and with none set sign-in answers
   "unavailable".
4. **Add the credentials** to `~/roller/.env` on docker2 as
   `ROLLER_SSO_OIDC_CLIENT_ID` and `ROLLER_SSO_OIDC_CLIENT_SECRET`, then run
   `./deploy.sh`. The compose file does not change. The client secret goes
   only into `.env`: never into Global Config, a commit, a chat or a ticket.
5. **Check it.** The login page shows "Sign in with Google", and
   `roller.log` has the self-check's success line ("OIDC discovery
   reachable"). The sign-in lines go only to that file, never to the
   console, so `docker compose logs app` does not show them; read the file
   inside the container:

   ```bash
   docker compose -f docker-compose.prod.yml exec app grep "OIDC discovery" /app/logs/roller.log
   ```

   `/app/logs` is in the container, not the `/data` volume, so a recreated
   container starts a fresh `roller.log`. Then test the sign-in itself at
   the public URL, `https://blog.wikantik.com/roller-ui/login.rol`. A
   sign-in started on the LAN address (`http://docker2.lan:8080`) redirects
   there first, because Google returns the browser to the Absolute URL's
   host.

`ROLLER_SSO_OIDC_DISCOVERY_URI` and `ROLLER_SSO_PROVIDER_LABEL` are optional;
the defaults are Google's discovery document and the label "Google".

**Letting a reader write.** User Admin, open the account (marked "Signs in
with Google"), tick "Can write", save. The reader must then **sign out and
sign in again**: their roles are read when they sign in, so until then the
menu still refuses them. They write in the admin UI only: with no password
they cannot mint an API token, so the `roller-api` CLI and the MCP endpoint
are not for them.

## Backup and restore

The `backup` service (the app image, which bakes in `postgresql-client` for
`pg_dump`/`psql` alongside `tar` from the base image) runs
`/app/backup/loop.sh` (baked in from `deploy/backup/loop.sh`), a cron-less
scheduler that wakes hourly and, once a day at `BACKUP_HOUR` (UTC), runs
`/app/backup/backup.sh` (`deploy/backup/backup.sh`). It runs as root (`user:
"0:0"` in `docker-compose.prod.yml`), unlike every other use of the image —
the `roller-backups` volume is root-owned, and the image's unprivileged
`roller` user cannot write into it. Each cycle:

1. `pg_dump -Fc` the database to `/backups/rollerdb-<timestamp>.dump`.
2. `tar czf` the `mediafiles`/`search-index`/`uploads` volumes to
   `/backups/volumes-<timestamp>.tar.gz`.
3. Deletes anything in `/backups` older than `BACKUP_RETENTION_DAYS`.

Both artifacts are written atomically: each is built under a `.tmp` suffix
and only `mv`'d to its final name once the write completes, so a backup
killed mid-write (OOM, container restart, disk full) never leaves a
truncated file sitting under a name a restore would trust — it leaves an
orphaned `.tmp` file instead, which rotation also cleans up once it ages
past the retention window. Backups land in the `roller-backups` named
volume.

These dumps contain the full database, including user password hashes —
treat them as sensitive. Copy nightly dumps off-host regularly (e.g. an
`rclone`/`rsync` cron job to remote storage) and restrict filesystem access
to wherever they land, both on this host and off it.

Run a backup by hand at any time:

```bash
docker compose -f docker-compose.prod.yml exec backup /app/backup/backup.sh
docker compose -f docker-compose.prod.yml exec backup ls -la /backups
```

### Restore

Full restore commands (with exact flags) live in the header comment of
`deploy/backup/backup.sh` (baked into the image at `/app/backup/backup.sh`)
— read that before restoring for real. Summary:

**Database** (app must be stopped first):

The restore command must be run as a single-quoted `bash -c '...'` INSIDE
the postgres container, exactly like below — `$POSTGRES_USER` /
`$POSTGRES_PASSWORD` / `$POSTGRES_DB` only exist in the postgres container's
own environment (set by `docker-compose.prod.yml`). A double-quoted or
unquoted command would instead expand them in your host shell, where
they're unset, and `pg_restore` would silently get empty values (this is
the same in-container pattern `provision.sh` uses for migrations):

```bash
docker compose -f docker-compose.prod.yml stop app

docker compose -f docker-compose.prod.yml exec -T postgres bash -c '
    set -euo pipefail
    export PGHOST=localhost
    export PGUSER="${POSTGRES_USER}"
    export PGPASSWORD="${POSTGRES_PASSWORD}"
    pg_restore -d "${POSTGRES_DB}" --clean --if-exists
' < /path/on/host/to/rollerdb-<timestamp>.dump

docker compose -f docker-compose.prod.yml start app
```

(Get the dump onto the host first with
`docker compose -f docker-compose.prod.yml cp backup:/backups/rollerdb-<timestamp>.dump .`
if restoring from a different machine than the one that made it.)

**The client that restores must be at least as new as the client that
dumped.** `pg_restore` refuses an archive written by a newer `pg_dump`, with
`unsupported version (1.16) in file header` and nothing else. The command
above restores with the client *inside the postgres container*, which is the
server's own major — correct as long as the dump came from a client no newer
than the server. The dumps are taken by the app image's client, so if a base
image bump ever moves that client ahead of the server (it has happened: the
base moved to Ubuntu 26.04 and took the client from 16 to 18), restore with
the app image instead, which always has the client that wrote the file:

```bash
docker compose -f docker-compose.prod.yml run --rm --entrypoint bash backup -c '
    set -euo pipefail
    export PGHOST=postgres
    export PGUSER="${POSTGRES_USER}"
    export PGPASSWORD="${POSTGRES_PASSWORD}"
    pg_restore -d "${POSTGRES_DB}" --clean --if-exists /backups/rollerdb-<timestamp>.dump
'
```

`PostgresMajorPinTest` keeps the image's declared client floor
(`PG_CLIENT_MIN_MAJOR` in the `Dockerfile`) at or above the server major the
compose file runs, so the ordinary path stays the one above.

**Media/search-index/uploads volumes** (stack must be fully down — the
archive extracts to `/data/...` paths matching the volume mount points):

The volume names below are Compose's default PROJECT-PREFIXED names, not
the bare names declared in `docker-compose.prod.yml` — the project name is
derived from the directory the compose file lives in (`roller` if you put
the deploy files in `/opt/roller` per [Get the deploy files onto the
host](#get-the-deploy-files-onto-the-host)), giving e.g.
`roller_roller-mediafiles`. Run `docker volume ls` FIRST to confirm the
exact names on your host and substitute them below if they differ:

```bash
docker compose -f docker-compose.prod.yml down
docker run --rm \
    -v roller_roller-mediafiles:/data/mediafiles \
    -v roller_roller-search-index:/data/search-index \
    -v roller_roller-uploads:/data/uploads \
    -v roller_roller-backups:/backups \
    postgres:18@sha256:4ef4dbc939d61acea57712655ddb4b4ab27419c913f94cca0cd57cb3ea3c2280 \
    tar xzf /backups/volumes-<timestamp>.tar.gz -C /
docker compose -f docker-compose.prod.yml up -d
```

Test your restore procedure at least once before you need it for real —
against a scratch copy of the stack, not production.

## Test a release locally before deploying it

The deploy files and images are the same in both places; only `.env` differs.
On your workstation, in a directory holding the same two files:

```bash
IMAGE_VERSION=0.1.1 docker compose -f docker-compose.prod.yml pull
IMAGE_VERSION=0.1.1 docker compose -f docker-compose.prod.yml up -d
```

(The prefix has to be on **both** lines, or `export`ed first — an
`IMAGE_VERSION=0.1.1` prefix on the `pull` line alone only scopes the
override to that one command, so `up -d` would fall back to whatever
`IMAGE_VERSION` is already in `.env`, silently defeating the point of this
snippet.)

with a local `.env` carrying `SITE_DOMAIN=:80`, `UMAMI_DOMAIN=:8081`,
`LISTMONK_DOMAIN=:8082`, `LISTMONK_ROOT_URL=http://localhost:8082` and
throwaway secrets. The blog is at `http://localhost/`, the analytics
console at `http://localhost:8081`, the newsletter admin at
`http://localhost:8082`.

This pulls the exact images the server will pull, so it is the only form of
local testing that is byte-identical to production. Building locally
(`docker build -t ghcr.io/jakefearsd/roller:test .`) is faster and catches
nearly everything, but CI runs its own build, so those bytes are merely
equivalent rather than identical.

Building the image needs outbound network access, and not only for the
Maven/apt steps already implied by "a build": the builder stage's
`generate-resources` phase runs `frontend-maven-plugin`, which fetches Node
`v22.23.2` from `nodejs.org` and then the editor bundle's npm packages from
`registry.npmjs.org`, fresh, every time, because the builder stage starts
from a clean `maven:3.9-eclipse-temurin-25` image with no cached
`app/frontend/node/` or `node_modules/` on it. An air-gapped `docker build`
fails at that step. (This is the image-build case specifically; see
"Frontend build" in CLAUDE.md for how a persistent checkout avoids re-paying
that cost on every local `mvn` run.)

## Upgrades

Re-download `docker-compose.prod.yml` and `deploy.sh` from the new release
alongside bumping `IMAGE_VERSION` in `.env`. The compose file and the script
are **not** guaranteed to be unchanged between releases, so always re-download
both rather than bumping `IMAGE_VERSION` alone. Keeping an older `backup`
service definition against a newer app image, for instance, would point its
entrypoint at a path the new image does not carry:

```bash
curl -LO https://github.com/jakefearsd/roller/releases/download/v<new-version>/docker-compose.prod.yml
curl -LO https://github.com/jakefearsd/roller/releases/download/v<new-version>/deploy.sh
chmod +x deploy.sh
```

`.env` is yours — re-downloading `env.example` and diffing it against your
`.env` is worth doing on any release whose notes mention new `.env` keys, but
never overwrite `.env` itself; it holds your secrets.

**`umami` moved from a floating tag to a pinned digest this release.** The
previous value was `postgresql-latest`, which — unlike every other image in
this stack — was not digest-pinned, so a host that had recently pulled
`latest` could already be running a newer Umami than the digest this release
now pins, and applying this upgrade would *downgrade* it. Umami's own schema
migrations are forward-only (they do not support a downgrade), so the
symptom of that is a container that refuses to start — check `docker compose
logs umami` — never silent data loss; if you hit it, stay on the newer image
or restore Umami's database from a backup taken before the downgrade rather
than trying to force the older image to run against the newer schema.

Once the two files are in place:

```bash
./deploy.sh           # pull the images and deploy, or:
./deploy.sh --prune   # the above, plus `docker image prune -f` after
```

(equivalently, `docker compose -f docker-compose.prod.yml pull && docker
compose -f docker-compose.prod.yml up -d`.) There is no `git pull` — the host
holds no checkout to update.

This is the same script used for [first run](#first-run) — running it again
is safe and idempotent. Each run: pulls the images, brings the stack up (which
re-runs the now-current `provision` service — a no-op if every migration is
already applied, since `migrate.sh` tracks what's applied in
`schema_migrations`), and waits for `app` to report healthy (up to 120s,
exits non-zero if it doesn't). Nothing is silently swallowed: a failed pull
or an app that never goes healthy both cause `deploy.sh` to exit non-zero
rather than leaving the stack in a half-upgraded state without telling you.

Always have a recent, verified backup before upgrading across a schema
change (see [Backup and restore](#backup-and-restore)).

### Upgrading PostgreSQL across a major version

The server is pinned by digest, so a major upgrade is a deliberate edit to
the compose file, never something a `deploy.sh` run does on its own. Two
things move with it, and `PostgresMajorPinTest` fails the build if they
disagree: the mount path (18 and later keep the cluster in
`/var/lib/postgresql/<major>/docker` and declare the parent as the volume,
so the parent is what gets mounted) and the app image's client floor
(`PG_CLIENT_MIN_MAJOR` in the `Dockerfile`, because that image also runs the
nightly `pg_dump`).

The data does not upgrade in place just because a newer image starts: a
cluster written by the old major is refused by the new image's entrypoint,
which is the safe outcome rather than a silent re-initialisation. Pick one:

- **Dump and restore**, the simplest and the right choice at this size. Take
  a fresh backup, plus a `pg_dumpall --globals-only` for the roles, which the
  nightly backup does not cover. Stop everything but postgres, restore into a
  new empty volume under the new image, then bring the stack up. Re-run the
  `grafana_ro` password step from the Grafana section afterwards if the roles
  were not restored.
- **`pg_upgrade --link`**, fast on a large database. It needs both majors'
  binaries, so it runs from a purpose-built image rather than this stack, but
  it does not have to copy data between volumes: one volume already holds
  each major in its own directory.

Rehearse either against a scratch copy of the stack first, and keep the old
volume until the new cluster has served real traffic.

### Upgrading a deployment made before the image-only layout

If this host has been running Roller since before the `provision.sh`/
`analytics-views.sh`/image-only rework (i.e. it still has a
`deploy/config/roller-production.properties` bind-mounted in, or a
`ROLLER_DOMAIN`/`ROLLER_IMAGE` pair in `.env`), moving it onto a current
release needs three changes, not just a version bump:

1. **Replace `docker-compose.prod.yml`** with the current release's copy —
   the old file bind-mounts a properties file and a Caddyfile that no longer
   exist as separate artifacts; both are baked into the images now (see
   "Development vs Production" in `CLAUDE.md` and the top of this document).
2. **Rename two `.env` keys**: `ROLLER_DOMAIN` becomes `SITE_DOMAIN`, and
   `ROLLER_IMAGE` becomes `IMAGE_VERSION` — and `IMAGE_VERSION` is a **bare
   version tag** (e.g. `0.1.0`), not a full image reference the way
   `ROLLER_IMAGE` used to be. Both were deliberately renamed off the
   `ROLLER_` prefix: that prefix is now reserved for the app's own
   configuration, and a stray `ROLLER_DOMAIN` would be overlaid onto the
   app's properties as a junk key named `domain`.
3. **Transcribe every setting from the old
   `deploy/config/roller-production.properties`** into the `ROLLER_*` block
   in `.env` — there is no properties file in the new image, so anything
   that file used to set has to become a `ROLLER_*` environment variable or
   it silently reverts to the app's built-in default. The mapping (old
   property → new variable) is:

   | Property | Environment variable |
   |---|---|
   | `database.jdbc.connectionURL` | `ROLLER_DATABASE_JDBC_CONNECTIONURL` |
   | `database.jdbc.username` | `ROLLER_DATABASE_JDBC_USERNAME` |
   | `database.jdbc.password` | `ROLLER_DATABASE_JDBC_PASSWORD` |
   | `database.jdbc.driverClass` | `ROLLER_DATABASE_JDBC_DRIVERCLASS` |
   | `database.configurationType` | `ROLLER_DATABASE_CONFIGURATIONTYPE` |
   | `installation.type` | `ROLLER_INSTALLATION_TYPE` |
   | `themes.dir` | `ROLLER_THEMES_DIR` |
   | `mediafiles.storage.dir` | `ROLLER_MEDIAFILES_STORAGE_DIR` |
   | `search.index.dir` | `ROLLER_SEARCH_INDEX_DIR` |
   | `uploads.dir` | `ROLLER_UPLOADS_DIR` |
   | `mail.configurationType` | `ROLLER_MAIL_CONFIGURATIONTYPE` |
   | `mail.hostname` | `ROLLER_MAIL_HOSTNAME` |
   | `mail.port` | `ROLLER_MAIL_PORT` |
   | `mail.username` | `ROLLER_MAIL_USERNAME` |
   | `mail.password` | `ROLLER_MAIL_PASSWORD` |
   | `newsletter.listmonk.baseurl` | `ROLLER_NEWSLETTER_LISTMONK_BASEURL` |

   (Same table as `docs/superpowers/specs/2026-08-14-container-push-deployment-design.md`
   — reuse it rather than re-deriving it; it is the single source of truth
   for this mapping.) The values fixed by the image's own layout
   (`ROLLER_THEMES_DIR=/app/themes`, the three `/data/*` directories,
   `ROLLER_INSTALLATION_TYPE=auto`, `ROLLER_DATABASE_CONFIGURATIONTYPE=jdbc`,
   `ROLLER_DATABASE_JDBC_DRIVERCLASS`) already ship as defaults in
   `env.example`, so you only need to add the ones that carry a real,
   host-specific value.

Every failure mode here is loud, not silent: compose refuses to start at all
if `SITE_DOMAIN` or `IMAGE_VERSION` is unset (`${SITE_DOMAIN:?...}` /
`${IMAGE_VERSION:?...}` in `docker-compose.prod.yml`), and a missing or wrong
`ROLLER_DATABASE_*` block leaves the app unable to bootstrap — its health
check never turns green and `deploy.sh` exits non-zero rather than reporting
success. A mistake in this migration will not corrupt data; it will stop the
stack from coming up, which is the failure mode you want while getting it
right.

## Firewall

Only 80 and 443 need to be open to the internet, or the ports
`SITE_HTTP_PORT`/`SITE_HTTPS_PORT` moved them to. Behind a tunnel connector
(see [Behind a tunnel or a proxy on another
host](#behind-a-tunnel-or-a-proxy-on-another-host)) nothing needs to be open
to the internet; only the connector's host needs to reach `SITE_HTTP_PORT`.
Caddy also publishes
`127.0.0.1:8081` and `127.0.0.1:8082` (the local-testing path to the Umami
and listmonk consoles — see [Test a release locally before deploying
it](#test-a-release-locally-before-deploying-it)), but those are
loopback-bound, unreachable from outside the host by construction, and need
no firewall rule either way. Their host side moves with `UMAMI_CONSOLE_PORT` /
`LISTMONK_CONSOLE_PORT` in `.env` when a host already uses either port (see
[Troubleshooting](#troubleshooting)). Nothing else in `docker-compose.prod.yml`
publishes a host port by default (`postgres` and the app's `8080`/`8090` are
reachable only on the internal Docker network), so there's nothing else to
explicitly block on a default-deny host firewall, but locking it down
explicitly is still good practice:

```bash
ufw default deny incoming
ufw allow 22/tcp    # SSH — or your actual admin access port
ufw allow 80/tcp
ufw allow 443/tcp
ufw enable
```

Do **not** add host port mappings for `postgres` (5432) or the app's
`8080`/`8090` in a compose override file for a real deployment; if you need
one-off DB access for debugging, use `docker compose exec postgres psql
...` instead of exposing the port.

## Troubleshooting

**The forgot-password page says the server has no outgoing mail configured**

Reset mail needs both the `ROLLER_MAIL_*` SMTP settings in `.env` (applied
with `up -d --force-recreate app`) and the **Site Administrator's email address** in Admin -> Global
Config (`site.adminemail`). Check both; see [Mail and account recovery](#mail-and-account-recovery).

**The new password from `reset-password` doesn't work**

The running app caches accounts it has loaded. Run
`docker compose -f docker-compose.prod.yml restart app`, then log in again.

**A container won't start / keeps restarting**

```bash
docker compose -f docker-compose.prod.yml ps
docker compose -f docker-compose.prod.yml logs provision
docker compose -f docker-compose.prod.yml logs app
docker compose -f docker-compose.prod.yml logs postgres
```

**Caddy won't start: `bind: address already in use`**

Something else on the host already listens on 80, 443, 8081 or 8082. The
last two are only the loopback console ports, but a listener on *any*
address (`0.0.0.0:8082`, say) blocks the `127.0.0.1:8082` bind just the same,
and caddy is the stack's only ingress, so the blog goes dark with it. Find
the owner with `ss -ltn` / `docker ps`, then move the console port in `.env`
rather than editing the compose file (a re-download on the next upgrade would
silently put the edit back):

```bash
LISTMONK_CONSOLE_PORT=18082   # and/or UMAMI_CONSOLE_PORT=18081
```

In the local `:8081`/`:8082` mode the console is then at
`http://localhost:18082`, and `LISTMONK_ROOT_URL` moves with it. 80 and 443
move the same way, with `SITE_HTTP_PORT` and `SITE_HTTPS_PORT`. Do that only
when a connector on another host forwards to them (see [Behind a tunnel or a
proxy on another host](#behind-a-tunnel-or-a-proxy-on-another-host)): a host
serving its own domain needs 80 and 443 reachable from the internet.

**App container is up but `/roller` isn't reachable through Caddy**

Check Caddy's own logs for ACME/certificate errors (common cause: DNS isn't
pointed at the host yet, or port 80 isn't reachable from the internet):

```bash
docker compose -f docker-compose.prod.yml logs caddy
```

**Caddy's logs are full of certificate errors for `analytics.invalid` /
`newsletter.invalid`**

Harmless if you never set `UMAMI_DOMAIN`/`LISTMONK_DOMAIN` — those are the
`.invalid` defaults (deliberately unresolvable, so the consoles are
unreachable rather than accidentally published on your blog's domain; see
[Analytics](#analytics)/[Newsletter](#newsletter)), and Caddy still attempts
and fails ACME issuance for each site block on every retry interval, forever,
which fills the log with certificate errors that read like a
misconfiguration. It is not one — set the corresponding `*_DOMAIN` variable
(and point DNS at it) if you actually want that console reachable, or ignore
the log noise if you don't.

**App health check never turns green**

The app needs a reachable, migrated Postgres to bootstrap
(`WebloggerFactory.bootstrap()`), and it won't even start until the
`provision` service has exited successfully — check
`docker compose -f docker-compose.prod.yml logs provision` first. If
provisioning succeeded, confirm `.env`'s
`ROLLER_DATABASE_JDBC_USERNAME`/`ROLLER_DATABASE_JDBC_PASSWORD` match
`POSTGRES_USER`/`POSTGRES_PASSWORD` exactly — the two are not templated
against each other. Re-running `./deploy.sh` (or `docker compose up -d`) is
safe and idempotent if you want to retry provisioning from scratch.

**App crash-loops with `EclipseLink-7360` "Database password was encrypted by
deprecated algorithm"**

Images up to and including 0.1.10 hand the database password to EclipseLink,
which treats any even-length, all-hex value (what `openssl rand -hex N`
prints) as one of its own encrypted passwords, fails to decrypt it and never
bootstraps. Nothing was encrypted and `passwordUpdate.sh` is not the fix: give
the role a password containing at least one non-hex character (the generator
in [Configure `.env`](#configure-env) does), then update `.env` to match:

```bash
NEW='...'   # e.g. $(openssl rand -base64 33 | tr '+/' '-_')
docker compose -f docker-compose.prod.yml exec postgres \
    psql -U roller -d rollerdb -c "ALTER ROLE roller PASSWORD '$NEW'"
# then set BOTH POSTGRES_PASSWORD and ROLLER_DATABASE_JDBC_PASSWORD in .env
# to $NEW, and re-run ./deploy.sh (or docker compose up -d)
```

(`roller`/`rollerdb` are the `.env.example` defaults for `POSTGRES_USER` and
`POSTGRES_DB`. `POSTGRES_PASSWORD` only takes effect when the data volume is first
initialised, so changing `.env` alone does not change the role's password.)
Fixed in 0.1.11, which passes the password through as
plaintext whatever it looks like.

**Disk filling up**

Backups accumulate in the `roller-backups` volume until
`BACKUP_RETENTION_DAYS` rotates them out; lower the retention window in
`.env` (and restart the `backup` service) if backups are consuming more
disk than expected, or check for orphaned `*.tmp` files from a backup that
was killed mid-write (see [Backup and restore](#backup-and-restore) —
rotation cleans these up too, just on the same delay).

**Starting over on this host**

`docker compose -f docker-compose.prod.yml down -v` removes every named
volume (`roller-pgdata`, `roller-mediafiles`, `roller-search-index`,
`roller-uploads`, `roller-backups`, `caddy-data`, `caddy-config`) —
irreversible without a backup. Only use it when you actually intend to
destroy all data on this host.
