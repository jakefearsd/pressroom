# Reader sign-in (SSO)

A reader signs in with Google (or any OIDC provider) and gets a `reader`
account. Authors and admins keep password sign-in. Spec:
`docs/superpowers/specs/2026-10-11-reader-sign-in-design.md`. Production
setup: `docker_deployment.md`, "Reader sign-in (Google)". Code:
`ui/core/security/sso/`, `boot/SsoConfig.java`, `boot/SecurityConfig.java`.

## The flow

1. A link to `/roller-ui/sso/start?return=<path>` (the login page carries it).
   `SsoStartFilter` (GET only, inside the UI security chain) checks that the
   Absolute URL is set and the registration resolves, stores
   `ReturnPaths.sanitize(return)` in the session attribute `roller.sso.return`,
   and redirects to `/roller-ui/sso/authorize/oidc`. Otherwise it redirects to
   `/roller-ui/login.rol?sso=unavailable`. **A start on any host but the
   Absolute URL's** (scheme, host or port differ; a default port equals none)
   first redirects to `<Absolute URL>/roller-ui/sso/start?return=<sanitised
   return>`, before touching the session, and starts again there: the
   provider returns the browser to the Absolute URL's host, and a session on
   `http://docker2.lan:8080` is not that host's session, so the callback would
   find neither the return path nor Spring's authorization request
   (`authorization_request_not_found`, `sso=failed`). An Absolute URL with no
   scheme or host answers `unavailable`.
2. Spring's `oauth2Login()` redirects to the provider with `state`, `nonce`
   and PKCE. `RollerAuthorizationRequestResolver` sets the redirect URI.
3. The provider returns to `/roller-ui/sso/callback`. Spring validates `state`,
   exchanges the code and checks the ID token.
4. `ReaderOidcUserService` hands the claims to `ReaderAccounts.signIn`. The
   principal (`RollerOidcUser`) has the **Roller username** as its name and the
   account's Roller roles, read from the database, as authorities. Nothing in
   the token grants a role.
5. `SsoSuccessHandler` redirects to the stored return path (re-sanitised;
   fallback is the context path plus `/`). `SsoFailureHandler` redirects to
   the login page with `?sso=failed` or `?sso=refused`.

`ReturnPaths.sanitize` accepts only a path under `<context>/`, at most 2000
characters, not starting `//` or `/\`, with no control character.

**The callback comes from the Absolute URL**, `site.absoluteurl` (Global
Config) or `ROLLER_SITE_ABSOLUTEURL` (`SsoConfig.configuredAbsoluteUrl`, via
`getPropertyWithConfigFallback`), with the trailing `/` stripped. It is never
the value `InitFilter` latches from the first request: a visitor on
`http://docker2.lan:8080` would produce a callback the provider refuses.

Sign-out is the existing `logout.rol`; the provider session stays. There is
no remember-me for SSO sign-ins.

## Configuration

| Property | Env var | Default |
|---|---|---|
| `sso.oidc.client.id` | `ROLLER_SSO_OIDC_CLIENT_ID` | empty |
| `sso.oidc.client.secret` | `ROLLER_SSO_OIDC_CLIENT_SECRET` | empty |
| `sso.oidc.discovery.uri` | `ROLLER_SSO_OIDC_DISCOVERY_URI` | Google's `/.well-known/openid-configuration` |
| `sso.provider.label` | `ROLLER_SSO_PROVIDER_LABEL` | `Google` |

SSO is on when the id and the secret are both non-blank (`SsoSettings.enabled`).
The scope is fixed at `openid profile email`; client authentication is
`client_secret_basic`. `SsoSettings.issuer()` accepts either the issuer URL or
the full discovery URI. The registration id is `oidc`.

`SsoSettings` and `ReaderAccounts` are always beans (the login page reads the
settings; `ReaderAccounts` is component-scanned). Every OAuth2 bean lives in
`SsoConfig.SsoEnabledConfig`, under `@Conditional(SsoEnabledCondition)`. With
SSO off there is no `SsoLogin`, `SecurityConfig` adds nothing to the chain
(`ObjectProvider<SsoLogin>`), and the start, authorize and callback paths
answer 404.

**Discovery is lazy.** `LazyOidcClientRegistrationRepository` builds the
registration on first use: three attempts, a cached success, never at startup.
Each failed attempt logs one WARN naming the cause chain's classes
(`IllegalArgumentException<-...<-UnknownHostException`, no message; the same
`CauseChain` helper and guard as `SsoFailureHandler`), so a failed
resolution logs three, and the start filter then logs its own
`unavailable (registration_unresolved)` line; the sign-in answers
`sso=unavailable`, and the next sign-in retries. No failure is cached. `OidcDiscoverySelfCheck` (daemon
thread `roller-sso-selfcheck`, started on `ApplicationReadyEvent`, three
attempts, 2 s then 4 s apart) probes once and only logs: INFO "OIDC discovery
reachable" on success. It never fails startup.

Logger `roller.sso` goes to `roller.log` **only** (`additivity="false"` in
`log4j2.xml`): never the console, so `docker compose logs app` never shows
it. In the container the file is `/app/logs/roller.log` (the working
directory's `logs/`, not the `/data` volume, so a recreated container starts
a fresh one). One INFO per sign-in (username,
new or existing), one WARN per refusal or failure (code and reason). No
email, token or secret is ever logged; `WebloggerConfig` masks `secret` keys.

## Account binding

Migration `V030__user_sso_identity.sql` adds `sso_issuer` and `sso_subject` to
`roller_user` (null together or set together, partial unique index on the
pair).

- **The lookup is by `(iss, sub)` only** (`UserManager.getUserBySsoIdentity`),
  including disabled accounts. Never by username or email: an email claim is
  not an identity, and matching it would let a provider account take over a
  local author who shares the address. A local account is never adopted.
- A disabled bound account is refused (`sso=refused`); no replacement is made.
- **`addReader`, not `addUser`.** `addUser` grants `editor` to everyone and
  `admin` to the first user under `users.firstUserAdmin`. `addReader` gives the
  role `reader` only. The username is `reader-` plus 10 characters of
  `[a-z0-9]` from `SecureRandom` (redrawn on collision, 5 tries), never from a
  claim. The passphrase is the unusable marker `User.SSO_PASSWORD_MARKER`
  (`{bcrypt}!sso-bound`).
- Refused (`sso_refused`): email missing, blank or over 255 characters;
  `email_verified` not exactly boolean `true` (a string is not `true`).
- Claims go through `ReaderClaims.firstScalar` (first value of a list). The
  new reader's display name follows the account page's rule
  (`business/comments/DisplayNames`, 1 to 50 code points, not a weblog
  member's screen name; `docs/dev/comments.md`): the `name` claim, else the
  email's local part, else `Reader`, else the generated username. A
  candidate the rule refuses is skipped for the next.
- Two first sign-ins at once: the unique index decides. The loser's flush
  fails, it releases the persistence session and re-reads the winner's row.
- Later sign-ins refresh `email` and leave the name alone.
- **`getUserByEmail` is local-accounts-only.** Forgot-password uses it, so a
  reader sharing an author's email cannot capture the author's reset.

## The reader role

`role.action.reader=comment`: a reader holds the global `comment` action and
nothing else, and `comment` implies no other action (`GlobalPermission.implies`
fails closed; `docs/dev/comments.md`). It reaches the comment endpoints and the
account page (`/roller-ui/account.rol`) only. The interceptor's default
(`login`) refuses a reader on every other secured UI controller
(`ReaderRefusalSweepTest` sweeps all of them reflectively). The
API chain ignores session cookies; `TokensApi` refuses an account without the
`login` action. Members -> Invite refuses a reader-only account
(`inviteMember.error.readerAccount`). **A weblog permission ignores global
roles**, and unticking "Can write" revokes `editor` only, so a demoted reader
keeps the weblog memberships it was given; private media
(`MediaResourceServlet.requesterMayEditWeblog`) therefore needs the global
`login` action as well as the permission. The frontpage header shows "Main Menu"
only under `$utils.isUserAuthor()`, and the admin UI's top bar
(`tiles/bannerStatus.jsp`, on the login page and the site directory too)
only to an anonymous visitor or under `userIsAuthor`, which
`BaseController.populateCommonModel` sets from the same global `login` check. Page caches already key a signed-in
render by principal name, so a reader's render is never shared.

## Passwords and administration

A bound account never gets or uses a password: `RollerUserDetailsService`
refuses it, forgot-password and reset-password skip it, User Admin's
set-password link and password fields and Profile's password change are
hidden and refused server-side (`User.isSsoBound`). User Admin marks it
"Signs in with Google", can disable it, and can grant `editor` ("Can write");
granting `admin` is refused (`userAdmin.error.ssoAdmin`). A promoted reader
keeps `reader`. **A promoted reader must sign out and in again**: the
principal's authorities are the roles read at sign-in, `SecurityConfig`
checks them on `menu.rol` and `/roller-ui/authoring/**`, and promotion
invalidates no session, so until then the menu answers 403. **It writes in
the UI only**: it has no password, so Basic sign-in, and with it minting an
API token (`POST /api/v1/tokens`), is refused, and the API chain ignores
session cookies. `GET /api/v1/admin/users` reports `"sso"`.

## Session and cookies

`server.servlet.session.cookie.same-site=lax`, pinned by
`SessionCookiePolicyTest`. `Strict` drops the session on the return from the
provider. Sign-in changes the session id.

## Hazards found while building it

- **`SecurityConfig` sets `http.authenticationManager(...)` on the UI chain,
  which hides the providers `oauth2Login()` would register.** Every callback
  failed with `ProviderNotFoundException` until `SsoLogin` gave the
  `OAuth2LoginAuthenticationFilter` its own `ProviderManager` holding only the
  OIDC provider. Anyone touching the UI chain's authentication manager must
  keep that. The wrapper also turns a non-authentication `RuntimeException`
  into a failure the handler answers, instead of a 500.
- **Persistence-session release.** The security chain (order 40) runs before
  `PersistenceSessionFilter` (order 60). Anything here that ends a request
  inside the chain releases the persistence session itself, as
  `ApiTokenAuthFilter` does (`PersistenceSession.release`): the start filter,
  both handlers, and the resolver. Skip it and every sign-in leaves an
  EntityManager bound to a worker thread.
- **The resolver runs on every UI request**, so it matches the authorize path
  first and reads the Absolute URL (a database read) only there. It releases
  only when it returns a request: on null the chain continues to order 60, and
  on a throw `SsoFailureHandler` releases. A second release is not free (it
  opens a fresh EntityManager to close it), so every path releases exactly once.
  The customizer is set once in the constructor; the delegate is shared by
  all threads.
- **The start filter compares the request's own scheme, host and port** with
  the Absolute URL's. Behind a proxy those are what
  `server.forward-headers-strategy=framework` makes of `X-Forwarded-Proto`
  and `Host`; a proxy that sends no `X-Forwarded-Proto` makes the public host
  look like another host to itself, and the start redirects to itself until
  the browser gives up ("too many redirects"). Caddy and cloudflared both
  send it.
- **`ClientRegistration.toString()` prints the client secret**
  (`clientSecret='...'`). Never log a registration, or anything whose
  `toString` carries one. `SsoLoginWiringTest` captures every logger at every
  level (`SsoLogCapture.everyLogger()`) across a failed and a successful
  sign-in and fails on the secret, or its Basic credential, anywhere in a log
  line or a response (criterion 4).
- **remember-me is disabled on the OAuth2 login filter**
  (`NullRememberMeServices`), so a hand-edited callback cannot set a cookie.
- **`ssoStartFilter` has a disabled `FilterRegistrationBean`**, or Boot also
  registers it container-wide on `/*`. Same hazard as `ApiTokenAuthFilter`.
- The authorized-client repository discards the access token; the default
  in-memory one would hold every reader's token forever.
- **The principal is a `RollerOidcUser`, not a `UserDetails`.** Anything that
  reads the signed-in user off the security context must accept it (its
  `getName()` is the Roller username; a bare `OidcUser`'s is the provider's
  subject, never trusted). `RollerHandlerInterceptor.resolveAuthenticatedUser`
  handled only `UserDetails` and `String`, so a reader promoted to editor was
  sent back to log in from `menu.rol` and every other page requiring a user.
  The unit sweeps signed readers in with a `UserDetails`, so only
  `SsoSignInIT` (criterion 29) saw it.

## Tests

Unit tests sit beside each class; `SsoLoginWiringTest` drives the whole
start-authorize-callback round trip with a signed ID token.
Browser ITs: `SsoSignInIT`, against Keycloak 26 in `it-selenium`, at both
context paths; see `browser-its.md`, "The IT harness cleans up by identity".
