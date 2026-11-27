# Decision-log entry (draft, not posted): send-newsletter, the writer role grant and the site-space routes join SA-18

Drafted 2026-11-25 for `SimpleAgilityDecisionLog` on Wikantik. **Not posted anywhere**: the SA number is the next
free one when it is posted, at Jake's instruction. Both decisions below were made as the owner's proxy overnight
(fail closed, reversible) while building wave 4 of the React admin migration; Jake confirms or reverses them when
he posts or discards this entry. Supersede SA-18's list, never rewrite it: SA-18's text stands, this entry extends it.

Date: 2026-11-25. Follows the format of `2026-11-21-session-only-policy-decision.md`.

**SA-<n> (draft): send-newsletter, the writer role grant and the site-space routes join SA-18's session-only list.**

**Context.** SA-18 named the Pressroom API resources that only an interactive session may use, so that a stolen or
over-shared bearer token, or an agent acting on text written by strangers, cannot reach them. Two token-reachable
operations were left off that list when it was written: `POST /api/v1/weblogs/{handle}/entries/{id}/send-newsletter`
(added in wave 1 of the admin migration, at `POST` level) and the `writer` field on
`PATCH /api/v1/admin/users/{userName}` (the single-sign-on account's editor role, settable by an ADMIN token).
Wave 4 brought the rest of SA-18's resources behind `@SessionOnly` and the field-level session checks, and these
two were the only gaps left between the policy's reasoning and the code. Wave 5 (the retirement of the server-rendered
admin) adds the routes the React admin needs for the **site space**, the hidden weblog that owns the site's pages;
they are recorded below as built, so that the entry states what the code does and Jake can reverse any of it.

**Decision.**

1. **`send-newsletter` is session-only.** `POST /v1/weblogs/{handle}/entries/{id}/send-newsletter` refuses every
   token, password, anonymous and unrecognised caller with 403 `auth.sessionOnly` (after the weblog's 404 and the
   token pin), through the one `@SessionOnly` guard. It stays an MCP exclusion, now with the reason "session-only".
   The editor's "Send as newsletter" button, which calls it with the signed-in session, is unchanged.
2. **The `writer` role grant is session-only.** On `PATCH /v1/admin/users/{userName}` a non-session caller that sends
   a `writer` value differing from the stored one gets 403 `auth.sessionOnly` before anything is applied; resending
   the stored value passes (the pattern of the email rule, SA-18's "another user's email change"). A session grants
   and revokes it as before; only a single-sign-on account has the role to edit.
3. **`GET` and `PUT /v1/admin/site-theme` are session-only, as built.** The class carries `@SessionOnly` (and the
   site-admin requirement): every token, password, anonymous and unrecognised caller gets 403 `auth.sessionOnly`.
   The React Theme screen reads and switches the site space's theme through these two routes because the weblog
   routes withhold the site space. They are MCP exclusions (session-only).
4. **`GET /v1/admin/site-pages` is NOT session-only, as built.** It sits on `AdminApi`, which is `@AdminScoped` and
   not `@SessionOnly`: an ADMIN-scope token reads it, a session reads it, a non-admin gets 403. It is read-only and
   returns each site page's slug, title and status (the Global Config front-door choices), nothing a token cannot
   already learn from the public site except drafts' titles. **Decision needed from Jake**: leave it (reversible
   cost: an admin token sees draft site-page titles) or add `@SessionOnly` to this one method (a one-line change plus
   the matrix row's flag); this draft keeps it as built.
5. **The site space is offered to a global admin's session only, on the Pages and Media API routes.**
   `PressroomHandlerInterceptor.siteSpaceOffered` withholds the site space (answering 404, as for an unknown weblog)
   from every token, HTTP Basic password, anonymous and unrecognised caller, and from a non-admin session, on every
   route; it offers it to a signed-in global admin's interactive session (form login, remember-me or reader SSO
   sign-in, which all resolve to `Caller.Kind.SESSION`) only on `/v1/weblogs/{handle}/pages` and `/media`, reads
   and writes alike (create, update, trash and restore pages; create and delete folders; uploads). Its entries,
   settings, comments and traffic routes still withhold it; its traffic is read through the admin route
   `GET /v1/admin/traffic/{handle}`. Pinned by `SiteSpaceAccessTest`.

**Why.**

- *send-newsletter.* The threat is **irreversible mail to every subscriber**. A send emails the weblog's whole list as
  a Listmonk campaign, stamps the entry, and cannot be recalled or retried: it reaches third parties' inboxes under
  the site's name. An agent holding a POST token acts on text strangers wrote (comments, imported pages, an entry's
  own body), so a prompt injection or a leaked token would be enough to mail every subscriber. Publishing a post is
  reversible (unpublish, edit, trash); sending one is not, so it is a person's decision, made in the admin app.
- *writer.* The threat is **lasting access that revoking a token does not remove**. The editor role is an
  account-level grant: a site-admin token that gives an attacker-controlled single-sign-on account `writer` leaves
  that account able to author posts after the token is revoked, the same reasoning that put memberships and the
  admin flag on SA-18's list. A role granted to another account is not undone by revoking the credential that
  granted it.

- *the site-space routes.* The site space holds the pages that front the whole site (the home page, the privacy
  page): an automation token or agent that could write them could rewrite what every visitor sees under the site's
  name, from text strangers wrote. Pages and media are edited by a person in the admin app, so the same
  session-only reasoning applies; reading `site-pages` stays a token-reachable admin read because it exposes
  nothing a visitor cannot see beyond draft titles (decision 4 flags that).

**Consequences.**

- Agents and CLI scripts that sent a newsletter with a token must have the person do it in the admin app. The
  public reference (`docs/api/README.md`) and the dev guide say so; the MCP exclusion reason changes from "a person's
  decision" to "session-only" with no change in what MCP offers (it never offered it).
- A site-admin token can still toggle `enabled` and the profile fields of an ordinary account through
  `PATCH /v1/admin/users/{userName}`, and can resend a stored `writer` or `administrator` value; nothing else about
  that route changed for tokens.
- Reversible: moving either operation back off the list is a new decision naming its threat (SA-18 rule 4), plus
  removing the mark and the matrix flag, not a code change alone. Supersede, never rewrite: this entry supersedes
  only SA-18's list, adding two rows.
- Binds no other repository: no API or event shape another product reads changed for a token caller that was
  supported (the wiki-content and blogger agents use neither). If one does, it learns of it through the 403
  `auth.sessionOnly` problem body.

## Further item (draft, 2026-11-26): the weblog read opened to members

Not a session-only addition but a route change that binds other callers, so it belongs in the same SA entry
if one is posted. `GET /v1/weblogs/{handle}` was weblog-`ADMIN` and `ADMIN`-role-token only; it is now readable by
any weblog member (`EDIT_DRAFT` and up) and by `READ` tokens, because the admin frame and entry editor call it
as every member (an Author's editor stayed blank on the 403). To keep least privilege, a caller below weblog
admin, or holding a token below the `ADMIN` role, gets `emailAddress`, `creatorUserName`, `businessId`, the
`place*` fields and `bookingUrl` absent. Consequence for other repositories: an agent that read those fields with
a `READ` or `POST` token (it could not before: the route refused it) still cannot; one with a member's session
or token newly can read the rest of the view. Draft only; not posted to `SimpleAgilityDecisionLog`.

## Further item (draft, 2026-11-27): submissions mark-read joins the session-only list

`POST /v1/weblogs/{handle}/submissions/read` (0.18.0, operationId `submissionsMarkRead`, body `{ids:[...]}`, 204) is
`@SessionOnly` like the rest of `SubmissionsApi`: it changes the shared inbox's read state, and the inbox is readers'
personal details that no token or agent may touch. Ids of other weblogs are ignored. It is an MCP exclusion
(session-only). `GET /v1/me/weblogs` also gains `newInquiries`, `drafts`, `scheduled` and `lastPublished`
(additive fields; `newInquiries` is 0 without weblog POST). Binds no other repository.
