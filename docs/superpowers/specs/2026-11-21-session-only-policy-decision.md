# Decision-log entry: session-only API resources

Posted to `SimpleAgilityDecisionLog` on Wikantik as **SA-18** on 2026-11-21, at
Jake's instruction. This file is the drafting record; the wiki entry is the
decision. Confirmed by Jake 2026-11-21: membership writes are session-only, and
another user's email change is session-only.

Date: 2026-11-21 (drafted 2026-11-20).

**SA-18: Pressroom API resources that only an interactive session may use.**

**Context.** Pressroom's `/api/v1` and its MCP façade authenticate with
bearer tokens; a token can be pinned to one weblog and limited to a role,
and the API knows the caller's kind (`TOKEN`, `PASSWORD`, `SESSION`) in one
place. A browser admin UI will later call the same API with a signed-in
session. Some admin operations must not be reachable by a token at all,
because a stolen or over-shared token is the API's most likely compromise,
and agents act on text written by other people.

**Decision.** The following are built only for `SESSION` callers, when
session login on `/api` exists. Until then they stay in the JSP admin UI and
have no API route.

| Resource | Why a token may never use it |
|---|---|
| Agent accounts (create, change, site-admin switch, their tokens) | Creates new identities with their own credentials: a stolen token could outlive its own revocation. |
| Admin accounts and the admin role (any change) | Already refused to tokens (`AdminAccountGuard`). Taking over or adding an admin is the whole site. |
| Weblog memberships (grant, change level, remove; tokens read them only) | Granting a role to another account gives lasting access that revoking the token does not remove; removal can lock the owner out. |
| Profile changes (email, password, names, locale) | The email address is where password-reset links go: changing it is an account takeover. One rule for the whole profile keeps it simple. |
| The site-wide token list (every user's tokens, revoke any) | Shows who holds access and lets one token revoke everyone else's. A person's own tokens stay on `/v1/tokens` under a password, as today. |
| Weblog deletion | Irreversible and total (no trash). |
| Custom templates and stylesheets | Template output is not sanitized: it bypasses `HTMLSanitizer`, the one content boundary, so a template write is script on every page of the blog. |
| The contact-form inbox (read or delete) | Readers' personal details and messages: third-party personal data, and text written by strangers handed to agents. |

**Not on this list.** A site-admin token can still create ordinary accounts, enable or disable them and
edit another ordinary user's screen name through `POST`/`PATCH /v1/admin/users`: the account guard keeps
admin and agent accounts out of those routes and creation sends a set-password link rather than choosing a
password.

**Decided (owner, 2026-11-21): another user's email change is session-only.** The address is where
password-reset links go, so changing it is an account takeover, the same reasoning as the profile row. The
route stays, but `AdminAccountGuard` refuses any non-session caller whose `PATCH` carries an `emailAddress`
differing from the stored one (403 `user.emailUiOnly`, before anything is applied); resending the stored
address passes.

Also unchanged and token-refused: minting, listing and revoking tokens with
a token, and writing `site.absoluteurl`.

**Rules for building them.**

1. Each route refuses `TOKEN` and `PASSWORD` callers with 403, through the
   one caller-kind resolver; `ANONYMOUS` and any unrecognised caller are
   refused (fail closed). The visibility rules run first: a caller who may
   not see the weblog still gets 404.
2. Session callers on `/api` need CSRF protection on every write before
   any of these routes ships (the API chains are CSRF-free today because
   they are stateless and bearer-only).
3. Each route is an MCP `Exclusion` with the reason "session-only", and
   its row in the API authorization matrix shows 403 for every token
   caller.
4. Moving a resource off this list, in either direction, is a new decision
   that names its threat, not a code change alone.

**Consequences.** Agents cannot manage people, access or templates; their
person does it in the admin UI (the MCP instructions already say so for
accounts, memberships and tokens). The React admin needs session login on
`/api` before it can replace these screens; every other screen it can build
on token-safe routes.
