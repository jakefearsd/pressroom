# Reader sign-in with Google (suite SSO) — design

Date: 2026-10-11. Design approved in conversation the same day (Jake: "The
fundamentals are clear. Build it."); this spec states what "done" means. It
is the first half of step 3 of the MCP roadmap
(`2026-10-11-mcp-endpoint-design.md`, Roadmap): comments for signed-in
readers need readers who can sign in, and this delivers them. Comments
themselves are a separate spec.

## Problem

Jake wants readers to comment, signed in. Roller has no way for a reader to
get an account: public self-registration was removed long ago, and the old
comment subsystem died of exactly that (W1, `V022__drop_comments.sql`).

Jake's other public app, Wikantik (`wiki.wikantik.com`), already signs
people in with Google. He wants the same sign-in here, with a full test
suite, and the same model for every public app of the suite.

## Decisions

Made by Jake in conversation:

- **Suite model: Google directly, one OAuth client per public app.** All
  clients live in one Google Cloud project, which is what the wiki does
  today. A reader already signed in to Google gets in with one click on each
  app. There is no central identity provider, no shared session and no SCIM.
- **Spring Security's own OIDC client (`oauth2Login()`), with Wikantik's
  rules ported**, not pac4j. Roller's authentication is already one Spring
  Security filter chain, and a second security framework beside it would
  duplicate state handling and session bridging. The behaviour and the test
  suite are Wikantik's; the library is not.
- **A Google sign-in creates a `reader` account**, which can do nothing
  today beyond being signed in; comments will add its one permission.
  Authors and admins keep password sign-in. An admin may make a reader an
  `editor`. Nothing Google sends ever grants a role, and an account bound to
  Google can never hold `admin`.

Made by me, on Jake's "proceed with your best judgement":

- **OIDC only, no SAML.** Nothing in the suite uses SAML; Wikantik carries it
  because it is a distributable product.
- **Standard OIDC discovery, no Google-specific code.** Google is the default
  discovery URI. Pointing at another provider (Keycloak in tests, a central
  identity provider one day) is configuration.
- **Callback and start paths live under `/roller-ui/sso/`**, beside the rest
  of the UI's routes. Wikantik uses `/sso/callback`; the shape matches.
- **Plain Spring Security artifacts** (`spring-security-oauth2-client`,
  `spring-security-oauth2-jose`, versions from the Boot BOM), not the Boot
  starter. Roller builds its own client registration, and Boot's
  auto-configuration should have nothing to configure.

## Non-goals

These are out of scope; the comments spec or roadmap step 4 owns most of them:

- Comments, and anything showing "signed in as" on cached blog pages.
- A reader self-service page (display name, delete my account).
- RP-initiated or single logout.
- Linking a password account to a Google identity.
- A central identity provider, and SCIM.
- Managing readers through the API beyond what `/api/v1/admin/users` already
  does (step 4).

## Design

### Flow

1. A link to `/roller-ui/sso/start?return=<path>` starts sign-in. Today the
   login page carries it; comments will add it to blog pages.
2. `SsoStartController` accepts `return` only when it is a path on this site.
   It must:
   - start with the context path followed by `/`;
   - not start with `//` or `/\`;
   - contain no control characters;
   - be at most 2000 characters long.

   Otherwise it uses the context path plus `/`. It stores the result in the
   session and redirects to `/roller-ui/sso/authorize/oidc`, Spring's
   authorization endpoint with its base URI moved.
3. Spring redirects to the provider with `state`, `nonce` and PKCE (Proof
   Key for Code Exchange).
4. The provider returns to `/roller-ui/sso/callback`. Spring's
   `OAuth2LoginAuthenticationFilter` checks `state`, exchanges the code and
   validates the ID token (signature, issuer, audience, nonce, expiry).
5. `ReaderOidcUserService` resolves the Roller account (Account binding).
   The resulting principal's `getName()` is the **Roller username**, so every
   existing "who is signed in" lookup (`RollerSession`, the interceptor,
   `UtilitiesModel`) works unchanged. Its authorities are the account's
   Roller roles, read from the database, and never anything from the token.
6. On success, the session id changes (Spring's fixation defence) and
   `SsoSuccessHandler` redirects to the stored return path. On any failure,
   `SsoFailureHandler` redirects to the login page with `?sso=<code>` (see
   Errors).

**The callback address is built from the Global Config Absolute URL**
(`site.absoluteurl`, trailing `/` stripped, plus `/roller-ui/sso/callback`),
never from the request. A visitor on `http://docker2.lan:8080` would
otherwise produce a callback that Google refuses. With no Absolute URL set,
`/roller-ui/sso/start` fails closed with `sso=unavailable`.

Sign-out is the existing `logout.rol`, which invalidates the session. It is
local only, as in Wikantik: the Google session stays.

### Configuration

Roller's `ROLLER_*` environment variables become properties (`WebloggerConfig`:
the prefix is dropped, the name lowercased, and `_` becomes `.`). The new
properties are declared in `roller.properties`:

| Property | Environment variable | Default |
|---|---|---|
| `sso.oidc.client.id` | `ROLLER_SSO_OIDC_CLIENT_ID` | empty |
| `sso.oidc.client.secret` | `ROLLER_SSO_OIDC_CLIENT_SECRET` | empty |
| `sso.oidc.discovery.uri` | `ROLLER_SSO_OIDC_DISCOVERY_URI` | `https://accounts.google.com/.well-known/openid-configuration` |
| `sso.provider.label` | `ROLLER_SSO_PROVIDER_LABEL` | `Google` |

- **SSO is on when both the client id and the secret are non-blank.**
- **The scope is fixed at `openid profile email`.** The client
  authentication method is `client_secret_basic`, the same as Wikantik pins.
- **Discovery is lazy.** The client registration is resolved on first use,
  with three attempts and a cached success, never at startup. A failed
  resolution answers `sso=unavailable` and is retried on the next sign-in.
- **A startup self-check** (`OidcDiscoverySelfCheck`, a daemon thread, three
  attempts) fetches the discovery document and only logs. It never fails
  startup. Wikantik's production lost its egress once and SSO broke
  silently; the self-check exists so that shows up in the log.
- **The secret never appears in any log, page, API response or exception
  message.** `WebloggerConfig` already masks keys matching `secret`.

### Account binding

Migration `V030__user_sso_identity.sql` (idempotent) changes `roller_user`:

- It adds `sso_issuer varchar(255)` and `sso_subject varchar(255)`, both
  null on local accounts.
- A check constraint requires them to be null together or set together.
- A partial unique index covers `(sso_issuer, sso_subject)` where they are
  not null.

The rules:

- **The lookup is by `(iss, sub)` only**, never by username or email, and it
  includes disabled accounts.
  - A local account is never adopted, so the same email as `jake`
    produces a separate reader.
  - A disabled reader's next sign-in finds the disabled account and is
    refused. A new account is never created in its place.
- **First sign-in provisions the account** through a new
  `UserManager.addReader`. It must never use `addUser`, which grants
  `editor` to every new user and `admin` to the first one when
  `users.firstUserAdmin` is set. The new account gets:
  - username `reader-` plus 10 characters from `[a-z0-9]`, drawn from
    `SecureRandom` and redrawn on collision (at most 5 tries). It never
    comes from a claim;
  - screen name and full name from the `name` claim, with control
    characters removed, whitespace collapsed and trimmed, and the result
    cut to 100 characters. If that leaves nothing, the part of `email`
    before the `@`;
  - email from the `email` claim;
  - the role `reader` and no other;
  - a passphrase that is an unusable marker matching no password;
  - `sso_issuer` and `sso_subject` set.
- **Claims are normalised.** A multi-valued claim takes its first value,
  Wikantik's `firstScalar` rule.
- **Sign-in is refused (`sso=refused`) when:**
  - `email` is missing or blank, or longer than 255 characters;
  - `email_verified` is not exactly `true`;
  - the bound account is disabled.
- **Two first sign-ins at once** for the same subject end with one account.
  The unique index decides, and the loser re-reads the winner's row.
- **Later sign-ins** refresh `email` from the claim and leave the display
  name alone.

### The reader role and authorization

- **`reader` implies no global action.** `roller.properties` gets
  `role.action.reader=` (empty).
  - The interceptor's default requirement (`login`) refuses a reader on
    every secured UI controller.
  - The `admin`/`editor` URL rules refuse one too.
  - Comments will add the reader's permission explicitly.
- **The API is already closed to readers.** The API chain is stateless and
  ignores session cookies. Its Basic sign-in fails on the unusable password,
  so a reader can mint no token. `TokensApi` also refuses an account whose
  only global role is `reader`, as defence in depth.
- **Weblog membership:** Members → Invite refuses an account that is only a
  reader (`inviteMember.error.readerAccount`: "make them an editor in User
  Admin first").
- **Templates:** the frontpage header's "Main Menu" link shows only to a
  user who can author (new `$utils.isUserAuthor()`: holds the `login`
  global action). A reader sees "Logout". `weblog.vm` already shows a
  signed-in visitor only "Logout".
- **Render cache:** the page caches already key a signed-in visitor's
  render by the principal's name (`/user=<name>` in `WeblogPageCache` and
  `SiteWideCache`). A reader's principal name is the Roller username, so a
  reader's render is cached per reader and is never served to anyone else,
  the same as an author's.
- **Persistence sessions:** the security chain (order 40) runs before
  `PersistenceSessionFilter` (order 60). Anything SSO does there that ends
  the request without continuing the chain releases the persistence
  session itself, as `ApiTokenAuthFilter` does: the start filter and both
  handlers. Otherwise every sign-in leaves an EntityManager bound to a
  worker thread.

### Passwords and administration

**An account bound to Google never gets or uses a password.** Each of these
refuses it:

- **Password sign-in:** `RollerUserDetailsService` reports it with an
  unusable credential. Belt and braces: the unusable marker never matches.
- **Forgot-password:** answers the usual enumeration-proof page and sends
  nothing. Its lookup by email considers only local accounts, so a reader
  who shares an author's email never captures the author's reset.
- **Reset-password:** refuses to consume a token for it.
- **User Admin's set-password link:** refused.
- **The password fields on User Admin's edit page and on Profile** are
  hidden for it and refused server-side. A promoted reader can reach
  Profile.

**User Admin:**

- The user edit page marks a bound account "Signs in with Google". User
  Admin has no list page, only a search box.
- An admin can disable it, and can make it an `editor` with a new checkbox
  ("Can write").
- Granting it `admin` is refused server-side
  (`userAdmin.error.ssoAdmin`), and the admin checkbox is absent for it.
- A promoted reader keeps `reader` alongside `editor`, and signs in with
  Google.

**API:** `AdminDtos.UserView` gains `"sso": true|false`. Everything else in
`/api/v1/admin/users` is unchanged:

- `PATCH` can disable a reader.
- An email patched on a bound account is overwritten at that reader's next
  sign-in, which the API reference says.

### Errors

The login page reads `?sso=<code>` and shows a message for the three known
codes. Any other value is ignored.

| Code | When | Message key |
|---|---|---|
| `unavailable` | SSO off, Absolute URL unset, discovery unreachable | `login.sso.unavailable` |
| `failed` | bad or missing `state`, code exchange failed, ID token invalid, provider error response | `login.sso.failed` |
| `refused` | email missing, unverified or too long; account disabled | `login.sso.refused` |

With SSO off, `/roller-ui/sso/start`, `/roller-ui/sso/authorize/oidc` and
`/roller-ui/sso/callback` answer 404.

### Logging

Logger `roller.sso`, routed to `roller.log` like the application's own
loggers:

- one INFO line per successful sign-in: username, and whether the account
  is new or existing;
- one WARN line per refusal or failure: the code and the reason.

The lines never carry the email, the ID token, the access token or the
client secret.

### Session and cookies

- The session cookie is `SameSite=Lax`, set explicitly
  (`server.servlet.session.cookie.same-site=lax`) and pinned by a test.
  `Strict` drops the session on the return from Google, which cost Wikantik
  a run of random sign-outs.
- Sign-in changes the session id.
- A reader's session lasts `server.servlet.session.timeout`, 30 minutes.
  There is no remember-me for Google sign-ins; Google makes signing in
  again one click.

### Production setup (Jake's checklist)

This goes into `docker_deployment.md`, section "Reader sign-in (Google)":

1. **Create the OAuth client.** In the wiki's Google Cloud project, add an
   OAuth client of type "Web application" named for the blog. Give it one
   authorized redirect URI, matched exactly:
   `https://blog.wikantik.com/roller-ui/sso/callback`.
2. **Update the consent screen**, which is shared by the project. Give it a
   suite-level app name, and make its privacy policy cover the blog. The
   blog stores readers' names and email addresses.
3. **Set the Absolute URL** in Global Config to `https://blog.wikantik.com`.
4. **Add the credentials** to `~/roller/.env` on docker2 as
   `ROLLER_SSO_OIDC_CLIENT_ID` and `ROLLER_SSO_OIDC_CLIENT_SECRET`, then run
   `./deploy.sh`. The compose file does not change.
5. **Check it.** The login page shows "Sign in with Google", and
   `roller.log` has the self-check's success line.

`deploy/.env.example` lists the four variables, commented out.

## Testing

Wikantik's suite, ported, plus Roller's own hazards.

**Unit tests (`app`).** Each class below gets its own test class:

- configuration and on/off: `SsoSettings`;
- lazy discovery with retries: `LazyOidcClientRegistrationRepository`;
- the self-check: `OidcDiscoverySelfCheck`;
- return-path validation: `SsoStartController`;
- binding and provisioning: `ReaderAccounts`, `ReaderOidcUserService`;
- claim normalisation and display names;
- the success and failure handlers;
- the migration, against PostgreSQL.

Sweeps:

- every `UISecurityEnforced` controller refuses a reader;
- every password path refuses a bound account;
- `TokensApi` refuses a reader.

Plus the security-config wiring, the `SameSite` pin, and
`$utils.isUserAuthor()`.

**Browser integration tests (`it-selenium`).** A Keycloak 26 container
joins PostgreSQL under docker-maven-plugin. Its realm, `roller-it`, holds:

- an OIDC client `roller-it`;
- a verified user;
- an unverified user;
- a user with no `name`.

The tests run at both `/` and `/roller`.

## Acceptance criteria

Each criterion names its test layer: **U** for unit, **I** for browser
integration.

**Configuration and startup**

1. With the client id or the secret blank, SSO is off: the login page shows
   no provider button, and `/roller-ui/sso/start`,
   `/roller-ui/sso/authorize/oidc` and `/roller-ui/sso/callback` answer 404.
   (U, I)
2. With both set, the login page shows "Sign in with Google", or the
   configured label, linking to `<context>/roller-ui/sso/start`. (U, I)
3. The application starts, and the login page renders, while the discovery
   URI is unreachable. The self-check logs a WARN for each failed attempt
   (three in all) and never throws. A later sign-in with discovery still
   down answers `sso=unavailable`. Once discovery is reachable, the next
   sign-in succeeds without a restart. (U)
4. The client secret appears in no log line, page or exception message
   across a failed and a successful sign-in. (U, with a captured log
   appender)

**Flow**

5. A verified Keycloak user signs in from
   `/roller-ui/sso/start?return=<context>/<handle>/`. They land on that
   page, signed in. A new `roller_user` row exists with: (I)
   - a `reader-` username;
   - the `name` claim as screen name;
   - the email;
   - role `reader` only;
   - `sso_issuer` and `sso_subject` set.
6. `return` values that are not paths on this site fall back to the
   context path plus `/`: `https://evil.example/`, `//evil.example/`,
   `/\evil.example`, a path outside the context path, and one with a CR or
   LF. (U)
7. The redirect URI sent to the provider is the Absolute URL plus
   `/roller-ui/sso/callback`, whatever the request's host. With no Absolute
   URL set, start answers `sso=unavailable` and redirects to no provider.
   (U)
8. The session id changes across sign-in. (I)
9. The session cookie carries `SameSite=Lax`. (U, I)
10. A callback with a forged `state` answers `sso=failed` and signs no one
    in. (I)
11. A callback with a valid `state` and a garbage `code` answers
    `sso=failed`. A callback with neither answers `sso=failed`. (I)
12. Sign-out ends the reader's session. The next request is anonymous.
    (I)

**Binding and provisioning**

13. The same subject signing in twice resolves to the same account, and the
    second sign-in creates no row. (U, I)
14. A local account with the same email is not adopted. A Google sign-in
    with `jake`'s email creates a separate reader, and `jake` is unchanged.
    (U)
15. On an empty user table with `users.firstUserAdmin=true`, a Google
    sign-in creates a `reader` holding no `editor` and no `admin` role. (U)
16. A disabled bound account is refused (`sso=refused`), and no new
    account is created for that subject. (U, I)
17. Sign-in is refused (`sso=refused`) when `email` is missing, blank or
    over 255 characters, or when `email_verified` is false or absent. (U;
    I for the unverified user)
18. The display name follows the rule in Account binding:
    - control characters removed, whitespace collapsed and trimmed, cut at
      100 characters;
    - the part before the `@` when nothing remains;
    - the first value of a multi-valued claim.

    (U; I for the user with no `name`)
19. A later sign-in refreshes the email and keeps a screen name an admin
    has changed. (U)
20. Two concurrent first sign-ins for one subject end with exactly one
    account. Both succeed, or the second is retried into the first's
    account. (U)
21. A username collision is redrawn, and the username never derives from
    a claim. (U)

**Authorization**

22. A signed-in reader is refused by every `UISecurityEnforced` controller
    that requires a user. The sweep is reflective, so a new controller is
    covered automatically. (U)
23. A signed-in reader gets 403 or the access-denied page on
    `/roller-ui/menu.rol`, `/roller-ui/profile.rol`,
    `/roller-ui/createWeblog.rol`, an editor URL and an admin URL. (I)
24. A reader cannot mint an API token: Basic sign-in fails, and
    `TokensApi` refuses a reader principal. A reader's session cookie
    authenticates nothing under `/api/`, `/api/mcp` included. (U, I)
25. Members → Invite refuses an account that is only a reader. (U)
26. The frontpage header shows "Main Menu" to an author but not to a
    reader, who sees "Logout". (U)
27. A reader's rendered page is cached under a key carrying that reader's
    username, so it is never served to anyone else. The same rule applies
    to an author. (U)

**Passwords and administration**

28. A bound account is refused by:
    - password sign-in, even with the passphrase column hand-set;
    - forgot-password, which shows the usual page and sends no mail;
    - reset-password token consumption;
    - User Admin's password fields and set-password link;
    - Profile's password change.

    (U)
29. The user edit page marks a bound account "Signs in with Google". It refuses to grant such an
    account `admin`, and the checkbox is absent. It allows "Can write"
    (`editor`), after which that account signs in with Google and reaches
    the editor menu. (U; I for the promotion)
30. `GET /api/v1/admin/users` reports `"sso": true` for a bound account and
    `false` for a local one. `PATCH` can disable a reader. (U)

**Documentation**

31. Each of these is updated:
    - `docs/dev/` gets a new `sso.md`, indexed in `CLAUDE.md`'s table;
    - `docker_deployment.md` gains "Reader sign-in (Google)";
    - `deploy/.env.example` lists the four variables;
    - `docs/api/README.md` notes `sso` on user views;
    - `docs/dev/entries.md`'s "Comments … not coming back" sentence is
      replaced by a pointer to this work.

**Added after reading the code**

32. Every SSO step that ends a request inside the security chain releases
    the persistence session exactly once: the start filter and both
    handlers. (U)
33. Forgot-password by email reaches the local account when a reader
    shares its address. (U)
34. A sign-in started on a host other than the Absolute URL's (e.g. the LAN
    address) is redirected to the same start on the Absolute URL's host,
    before any session is created, with the return path preserved. The
    target is built only from the configured Absolute URL. (U)
