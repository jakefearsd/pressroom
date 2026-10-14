# Agent accounts and API tokens in the admin UI — design

Date: 2026-10-13. Design approved in conversation the same day: the model, the screens, and the API side, in that order. This spec defines what "done" means.

Goal: give Jake the right balance of UI and agent administration to bootstrap blog.wikantik.com end to end.
- **People, in the admin UI:** identity and credentials. That means people, agent accounts, weblog memberships and tokens.
- **Agents, over MCP:** content and day-to-day operations.

## Problem

Checked against the code (release 0.1.19):

| Area | Today |
|---|---|
| Tokens | No UI at all. `POST /api/v1/tokens` (HTTP Basic only) mints a token for the signed-in account itself, so an admin cannot mint one for another account. Listing and revoking are limited to one's own tokens, and a listing cannot tell revoked tokens from live ones (`TokenView` has no `revokedAt`). The mint check never verifies that the `weblog` pin exists or that the owner belongs to it, or that `expiresAt` is in the future. |
| Agent identity | None. An agent can only be an ordinary login account with a password. |
| Creating a person | Create User starts the account disabled and always grants `editor`; the only choice is an admin checkbox. The required name and email are enforced only in the browser. The set-password link does not enable the account. |
| Memberships | Granted only on each weblog's Members page. The admin user page shows them read-only. |
| User list | The Users page is a search box. There is no list. |
| Disabled owners | No check in `ApiTokenAuthFilter` itself. Today it holds only because the user lookup returns enabled users. |

## Decisions

- **An agent is a `roller_user` row with `kind = AGENT`.** Membership, permissions and tokens reuse the existing machinery. A separate agent table was rejected because it would need a second permission system.
- **An agent never signs in.** It has no password and works only through tokens that a site admin mints for it.
- **Site admin is an opt-in switch per agent** (Jake's choice). With it on, an ADMIN-role token from that agent can use the site-wide admin API.
- **Identity and credentials stay with people.** No API route can create, change or mint tokens for an agent account, nor touch an admin account (as today).
- **Site admins manage everything under Admin, and anyone can manage their own tokens.** Under Admin: agents, their memberships and tokens, and a site-wide token list. Every signed-in person also gets their own tokens on their Profile (Jake's choice).

## 1. Model (migration V033)

`V033__agent_accounts.sql` is idempotent. It changes two tables.

**`roller_user`:**
- `kind varchar(16) NOT NULL DEFAULT 'PERSON'`, with a check constraint `kind IN ('PERSON','AGENT')`.
- `notes varchar(1000)`: nullable, an agent's purpose, shown only in the admin UI.
- `last_login timestamp`: nullable, the last successful interactive sign-in.

**`roller_api_token`:**
- `issued_by varchar(48)`: nullable. It holds the id of the user who minted the token, or null when it predates V033.

**What an agent account holds:**
- **Username:** follows the same rule as a person's (`username.allowedChars`, by default letters and digits). It is fixed after creation.
- **Name:** required. It is stored as both screen name and full name.
- **Email:** optional. When blank it is stored as `<username>@agents.invalid`, and nothing is ever mailed to an agent.
- **Password:** none (NULL).
- **Global roles:** always `editor` (login, weblog, comment). It also has `admin` when the site-admin switch is on.
- **Status:** enabled on creation.

## 2. Sign-in refusals

Each refusal is explicit, checked on `kind`:
- **Form login and remember-me** refuse an agent (`RollerUserDetailsService`). The failure is the same generic one as a wrong password.
- **HTTP Basic on `/api/**`** refuses an agent.
- **Password reset** treats an agent like an unknown account: the same response, and no mail. A password-set link is never issued for an agent, and one presented for an agent account is refused.
- **SSO** never signs in as an agent and never binds to one.
- **The admin user page** shows no "send password link" control for an agent.

**Disabled accounts and tokens:** `ApiTokenAuthFilter` refuses a token whose owner is disabled (401), for people and agents alike. The check is explicit and does not depend on the enabled-only lookup.

**Last sign-in:** recorded in `last_login` on every successful form, remember-me or SSO sign-in.

## 3. Tokens

### Minting (UI)

The same rules apply on the agent page and on the Profile. `ApiTokenManager.issueToken` records `issued_by`.

- **Label:** required, at most `ColumnLimits.TOKEN_LABEL` characters.
- **Role:** Read / Write / Admin (stored as `READ`/`POST`/`ADMIN`).
- **Weblog:**
  - "All its weblogs" (no pin), or one weblog.
  - The choices are the weblogs the account is a member of. For a site-admin account, every weblog is a choice.
  - A pin outside those choices is refused.
- **Expiry:** 30, 90 or 365 days, or never. The default is 90 days.

### One-time reveal

A successful create redirects to a reveal page.
- **Where the secret lives:** only in a one-read flash attribute. It never appears in a URL or a log; logs name the token's id and label only.
- **Caching:** the reveal page sends `Cache-Control: no-store`.
- **What the page shows:**
  - the secret, with a Copy button;
  - an MCP connection snippet with a Copy button: the site's `/api/mcp` URL from the configured absolute URL, plus `Authorization: Bearer <secret>`;
  - a "Done" link back to where the token was created.
- **Second visit:** loading the reveal page again (no flash) says the token was already shown and that a new one means revoking and creating again.

### Listing and revoking

- **Agent page and Profile:** list the account's live tokens (not revoked, not expired). Each shows label, role, weblog, created, last used and expires.
- **Admin → API tokens:** lists every live token on the site, with owner (and kind), label, role, weblog, issued by, created, last used, expires and Revoke. A "Show revoked and expired" toggle includes the rest, with their revoked time.
- **Revoke:** a POST behind `data-confirm`.
  - A site admin can revoke any token; a person can revoke their own.
  - Revoking an already-revoked token succeeds and changes nothing.
  - A token id the caller may not revoke answers as "no such token".

### API (`/api/v1/tokens`, Basic only, unchanged otherwise)

- A `weblog` the caller is not a member of (or that does not exist) is a 400 on `weblog`. A site admin may pin any existing weblog.
- An `expiresAt` that is not in the future is a 400 on `expiresAt`.
- `TokenView` gains `revokedAt`.

## 4. People and memberships

### Create a person

Changes to the existing form:
- **Required on the server too:** username, name (screen name), full name and a valid email.
- **Role:** Author or Site admin. Author is the default.
- **Enabled:** checked by default.
- **Password:** one of two options.
  - "Email a set-password link", offered and the default only when mail is configured.
  - "Set a password now", at least 8 characters.

### Weblogs section on a user's page

The user page (person or agent) gets an editable Weblogs section:
- each membership's level (Admin / Author / Draft, the same levels and labels as the weblog's Members page), with Remove;
- an "Add to weblog" control listing weblogs the account is not in.

The rules are the Members page's own:
- a weblog keeps at least one Admin member;
- granting is refused when group blogging is off, and the section says so instead of offering the control;
- a reader-only account cannot be granted.

### Users list (Admin → Users)

- **Accounts shown:** every account, with All / People / Agents tabs and a text filter. The filter matches username, name or email, server-side.
- **Columns:**
  - name and username;
  - kind;
  - role: Reader / Author / Site admin;
  - status: an Enabled or Disabled pill;
  - weblog count;
  - last active: a person's `last_login`, or an agent's newest token use.
- **Actions:** "New person" and "New agent". Each row opens the account's page.

### Agent page

- **Details:** username (fixed), name, notes, and the Site administrator switch.
- **Enable/Disable:** one button behind `data-confirm`. Disabling ends every token on the next request.
- **Weblogs** section.
- **API tokens** section, with "New token".

## 5. API and MCP

- **`AdminAccountGuard` refuses agent accounts:** "Agent accounts are managed in the admin UI." (403). `PATCH /api/v1/admin/users/{userName}` applies it, so the MCP `users` tool does too.
- **`POST /api/v1/admin/users`** creates people only. It is unchanged.
- **`kind` is reported:** `AdminDtos.UserView` and `GET /api/v1/me` gain `kind` (`PERSON`/`AGENT`).
- **The MCP instructions gain one line:** agent accounts, weblog memberships and tokens are managed by a person in the admin UI, so ask your person for access to another weblog.
- **No new `/api/v1` route.** The MCP catalog is unchanged apart from the instructions.

## 6. Logging

One INFO line on logger `roller.admin` for each event below, naming the target and the acting account (`by=`):
- token minted (id, label, owner, role, weblog, expiry) and token revoked;
- agent created;
- account enabled or disabled;
- site-admin granted or removed;
- membership granted, changed or removed.

No secret is ever logged.

## 7. Design and documentation

- **Design cards first** (the repo's rule), listed in `docs/design/design-system.md` so that `DesignCardsTest` sees them. One card each for:
  - the Users list;
  - the agent page;
  - the token form with the one-time reveal.
- **`docs/api/README.md`:** "Getting a token" leads with the UI (Profile, or an agent's page). It explains agent accounts and the new mint checks, and adds `kind` to `/me` and the users list.
- **`docs/dev/admin-ui.md` and `docs/dev/api.md`:** the agent model, sign-in refusals, the reveal pattern and the guard.

## Acceptance criteria

Each is a test.

1. V033 applies twice without error. Existing users read back as `PERSON`, and existing tokens have a null `issued_by`.
2. Creating an agent stores `kind = AGENT`, no password, roles `editor` (plus `admin` only when the switch is on), enabled, and `<username>@agents.invalid` when the email is blank.
3. An agent's username follows `username.allowedChars`. A taken username is a field error on the username.
4. Form login as an agent fails with the same message as a wrong password. HTTP Basic as an agent gets a 401 on `/api/v1/me`.
5. A password-reset request naming an agent behaves like an unknown account and sends no mail. A password-set link for an agent is refused.
6. The admin page of an agent shows no send-password-link control.
7. A token whose owner is disabled gets a 401, for a person and for an agent. Re-enabling the owner makes the same token work again.
8. Disabling an agent from its page records `enabled = false` and logs `roller.admin` with `by=`.
9. Minting from the agent page stores the label, role, pin, expiry (default 90 days) and `issued_by`, then redirects to the reveal page. The reveal page shows the secret once, with `Cache-Control: no-store`. Its MCP snippet carries the site's `/api/mcp` URL and the bearer header.
10. Reloading the reveal page shows "already shown", not the secret. No log line written by a mint contains the secret.
11. A pin to a weblog the account is not a member of is refused (UI field error). For a site-admin account, any existing weblog is accepted.
12. `POST /api/v1/tokens` with a foreign or unknown `weblog` gets a 400 with `errors[0].field == "weblog"`. A past `expiresAt` gets a 400 on `expiresAt`. `GET /api/v1/tokens` shows `revokedAt` for a revoked token.
13. Admin → API tokens lists live tokens of people and agents with owner, kind and issued-by. The toggle adds revoked and expired ones.
14. A site admin can revoke any token. A person can revoke only their own; another's id answers "no such token". Revoking twice succeeds and changes nothing. A revoked token gets a 401 on its next use.
15. A person's Profile tokens page mints with pin choices limited to their own weblogs (all weblogs for a site admin), lists their live tokens and revokes them.
16. Create person refuses a missing name, full name or email, and an invalid email, server-side with field errors. "Set a password now" refuses fewer than 8 characters. The new account is enabled with role `editor`, plus `admin` for Site admin.
17. "Email a set-password link" is offered only when mail is configured. Choosing it creates an enabled account and sends the link.
18. The Weblogs section grants, changes and removes memberships. It refuses removing or demoting a weblog's last Admin. It refuses granting while group blogging is off, and says so. Each change logs `roller.admin`.
19. Admin → Users lists every account, with kind, role, status, weblog count and last active. The People and Agents tabs and the text filter narrow the list.
20. A successful form sign-in sets `last_login`, and the Users list shows it.
21. `PATCH /api/v1/admin/users/{agent}` gets a 403 "Agent accounts are managed in the admin UI." The `users` tool through MCP gets the same.
22. `GET /api/v1/me` and `GET /api/v1/admin/users` report `kind`.
23. An agent with Site administrator on and an ADMIN token can call `GET /api/v1/admin/config`. With it off, the call gets a 403.
24. **Bootstrap, as a browser IT at `/` and `/roller`:**
    - An admin creates an agent, adds it to a weblog as Author, and mints a Write token pinned to that weblog.
    - The token answers `whoami` with `kind: AGENT` and that weblog, and creates an entry there.
    - After the admin revokes the token, the next call gets a 401.
25. Every new GET route has a `Routes` marker. Every new message key is in all 8 bundles. Every destructive control uses `data-confirm`. Every POST form carries `<sec:csrfInput/>`.

## Out of scope

- Deleting accounts (disable instead).
- An audit table.
- Granting membership through the API.
- Weblog owners managing agents.
- Renaming or extending a token (revoke and mint a new one).
- `POST /api/v1/admin/users` creating enabled accounts.

## Release

**0.2.0**, after these all pass:
- the full unit verify with every gate;
- diff coverage;
- `mvn verify -Pit` at both context paths.

Then deploy it to blog.wikantik.com.
