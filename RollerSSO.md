# Roller reader sign-in (SSO) setup

Readers sign in to Roller with their Google account, the same way they sign
in to the wiki at wiki.wikantik.com. Their first sign-in creates a **reader**
account. A reader cannot use the admin or authoring UI, the API, MCP or
private media. They can comment on entries, and manage their account page.

**Current state:** Roller 0.1.16 on blog.wikantik.com contains the feature,
but sign-in is **off**. It turns on when both Google credentials are in
`.env`. Until then nothing changes for anyone.

Further reading:
- `docker_deployment.md`, section "Reader sign-in (Google)": the deployment
  runbook.
- `docs/dev/sso.md`: how the code works.
- `docs/superpowers/specs/2026-10-11-reader-sign-in-design.md`: the design.

## One-time setup

### 1. Create the OAuth client in Google Cloud

Use the **same Google Cloud project as the wiki**: one project, one client
per public app.

1. Open the Google Cloud console and select the wiki's project.
2. Go to **APIs & Services → Credentials**. Newer consoles also show this as
   **Google Auth Platform → Clients**.
3. Choose **Create credentials → OAuth client ID**, application type **Web
   application**. Name it for the blog, for example "Roller blog".
4. Under **Authorized redirect URIs** add exactly one entry:

   ```
   https://blog.wikantik.com/roller-ui/sso/callback
   ```

   It must match character for character: `https`, this host, this path, no
   trailing slash and no query string. Any difference makes Google show
   `redirect_uri_mismatch`. **Authorized JavaScript origins** stays empty.
5. Create the client and copy its **Client ID** and **Client secret**. The
   secret goes only into `.env` on docker2 (step 4). Never put it in Global
   Config, a commit, a chat or a ticket.

### 2. Check the consent screen

The wiki's project already has a consent screen, and every client in the
project shares it. Readers see its app name and links.

- **App name:** use a name that fits both the wiki and the blog, since blog
  readers will see it too.
- **Authorized domains:** `wikantik.com` should already be listed, because
  of the wiki.
- **Privacy policy:** the linked policy must also cover the blog. The blog
  stores each reader's name and email address.
- **Scopes:** Roller asks only for `openid`, `profile` and `email`. These are
  not sensitive scopes, so no extra Google verification is needed.
- **Publishing status** must be **In production**. It already is, for the
  wiki. In "Testing" mode only listed test users can sign in.

### 3. Set the site's Absolute URL

In Roller, go to **Admin → Global Config** and set **Absolute URL** to
`https://blog.wikantik.com`. Alternatively, set `ROLLER_SITE_ABSOLUTEURL` in
`.env`. When both are set, Global Config wins.

The sign-in callback is built from this address, never from the visitor's
host. If it is not set, sign-in answers "unavailable".

### 4. Add the credentials and deploy

On docker2:

```bash
cd ~/roller
cp -p .env .env.bak-$(date -u +%Y%m%d%H%M%S)
nano .env        # add the two lines below; the client secret stays in this file only
./deploy.sh
```

```bash
ROLLER_SSO_OIDC_CLIENT_ID=<client id from step 1>
ROLLER_SSO_OIDC_CLIENT_SECRET=<client secret from step 1>
```

The compose file does not change, because `.env` is already passed to the
app. Two settings are optional and rarely needed:

| Variable | Default |
|---|---|
| `ROLLER_SSO_OIDC_DISCOVERY_URI` | `https://accounts.google.com/.well-known/openid-configuration` |
| `ROLLER_SSO_PROVIDER_LABEL` | `Google` (the button reads "Sign in with Google") |

### 5. Check it

1. **The start redirects onward, not to itself.**

   ```bash
   curl -sI https://blog.wikantik.com/roller-ui/sso/start | grep -i '^location'
   ```

   Expect `location: /roller-ui/sso/authorize/oidc`. If it points back at
   `https://blog.wikantik.com/roller-ui/sso/start`, the proxy is not
   reporting `https` and the public host. Check `TRUSTED_PROXIES` in `.env`;
   it must be the `cloudflare` host, 192.168.5.50.
2. **Google was reachable at startup.** Roller probes Google's discovery
   document once at startup. Its log lines go only to `roller.log` inside the
   container, not to `docker compose logs`:

   ```bash
   docker compose -f docker-compose.prod.yml exec app grep "OIDC discovery" /app/logs/roller.log
   ```

   Expect `OIDC discovery reachable`. A recreated container starts a fresh
   `roller.log`.
3. **The login page shows the button.** Open
   `https://blog.wikantik.com/roller-ui/login.rol`; it shows "Sign in with
   Google".
4. **A real sign-in works.** Use a Google account that is not one of the
   admins, starting from the public URL. A sign-in started on
   `http://docker2.lan:8080` is sent to the public host first, because Google
   always returns the browser there. You should land back where you started,
   signed in. In User Admin the new account appears as `reader-…`, marked
   "Signs in with Google".

## Running it

- **What a reader can do today:** sign in and out, comment on entries of
  weblogs that have comments on, and manage an account page.
  - **Commenting.** A reader's first comment on a weblog waits for an owner's
    approval. Once one is approved, their later comments there publish at once;
    a comment with more than two links, or any comment after one is hidden,
    waits again. Limits: 5 comments per 10 minutes and 30 per day. A reader can
    delete their own comments. Commenting is not offered on custom-domain
    weblogs.
  - **The account page** (`/roller-ui/account.rol`, linked from the comment
    form): set a display name (not one that matches an author's screen name),
    list and delete their comments, or delete the account, which removes its
    comments. An account that holds a weblog role or admin cannot delete itself
    this way.
- **Letting a reader write:**
  1. In **User Admin**, open the account, tick **Can write (editor)** and
     save.
  2. The person must **sign out and sign in again**, because roles are read
     at sign-in.

  They then write in the admin UI only. With no password they cannot mint an
  API token, so the `roller-api` CLI and MCP are not for them.
- **Admin is never possible:** an account that signs in with Google can never
  be made an administrator. Admins keep password accounts.
- **Disabling a reader:** in User Admin, untick **Enabled**. Their next
  sign-in is refused, and no new account is created in its place.
- **Same email as an author:** if you sign in with Google using the email of
  your `jake` account, you get a separate reader account. Google sign-ins
  never take over a password account.
- **Passwords:** a Google-bound account never gets one. Forgot-password sends
  it nothing, and User Admin hides its password fields.
- **Turning sign-in off:** remove the two `ROLLER_SSO_OIDC_*` lines from
  `.env` and run `./deploy.sh`. Existing reader accounts stay, but they
  cannot sign in.

## When something goes wrong

A failed sign-in lands on the login page with a message. Each attempt also
writes one line to `roller.log`:
- `Reader sign-in refused or failed: <code> (<reason>)` for a failure;
- `Reader <name> signed in (new account|existing account)` for a success.

| What you see | Meaning | Fix |
|---|---|---|
| Google shows `redirect_uri_mismatch` | The redirect URI in the Google client is not exactly `https://blog.wikantik.com/roller-ui/sso/callback`. | Correct it in the Google console (step 1). |
| "Sign-in with your account provider is not available right now" (`sso=unavailable`) | No Absolute URL is set, or Google's discovery document could not be fetched. The log then shows `OIDC discovery for … failed`. | Set the Absolute URL (step 3); check docker2's outbound network. The next sign-in retries; no restart needed. |
| "This account cannot sign in here…" (`sso=refused`) | The account is disabled, or Google did not confirm the email address. The log line names the reason. | Re-enable it in User Admin, or ask the reader to verify their email with Google. |
| "Sign-in did not complete" (`sso=failed`) | The return from Google was invalid: an expired or forged request, a cancelled consent, or a token exchange failure. | Try again from the public URL. If it repeats, check the log line's reason and that the client secret in `.env` is current. |
| The browser keeps redirecting to `/roller-ui/sso/start` | The proxy reports the wrong scheme or host (see step 5.1). | Fix `TRUSTED_PROXIES`. |
| Signed out after returning from Google | The session cookie did not survive the return. Roller sets it `SameSite=Lax` for exactly this reason. | Check that nothing in front of Roller rewrites the cookie to `SameSite=Strict`. |

## Reference

| Item | Value |
|---|---|
| Redirect URI to register | `https://blog.wikantik.com/roller-ui/sso/callback` |
| Start (the button's link) | `/roller-ui/sso/start?return=<path on this site>` |
| Required `.env` lines | `ROLLER_SSO_OIDC_CLIENT_ID`, `ROLLER_SSO_OIDC_CLIENT_SECRET` |
| Scopes requested | `openid profile email` |
| Account binding | Google's issuer plus subject (`sub`), stored in `roller_user.sso_issuer`/`sso_subject` (migration V030) |
| Reader username | `reader-` plus 10 random characters |
| Log file | `/app/logs/roller.log` in the app container (logger `roller.sso`) |
| Released in | Roller 0.1.16 |
