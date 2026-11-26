# Decision-log entry (draft, not posted): send-newsletter and the writer role grant join SA-18

Drafted 2026-11-25 for `SimpleAgilityDecisionLog` on Wikantik. **Not posted anywhere**: the SA number is the next
free one when it is posted, at Jake's instruction. Both decisions below were made as the owner's proxy overnight
(fail closed, reversible) while building wave 4 of the React admin migration; Jake confirms or reverses them when
he posts or discards this entry. Supersede SA-18's list, never rewrite it: SA-18's text stands, this entry extends it.

Date: 2026-11-25. Follows the format of `2026-11-21-session-only-policy-decision.md`.

**SA-<n> (draft): send-newsletter and the writer role grant join SA-18's session-only list.**

**Context.** SA-18 named the Pressroom API resources that only an interactive session may use, so that a stolen or
over-shared bearer token, or an agent acting on text written by strangers, cannot reach them. Two token-reachable
operations were left off that list when it was written: `POST /api/v1/weblogs/{handle}/entries/{id}/send-newsletter`
(added in wave 1 of the admin migration, at `POST` level) and the `writer` field on
`PATCH /api/v1/admin/users/{userName}` (the single-sign-on account's editor role, settable by an ADMIN token).
Wave 4 brought the rest of SA-18's resources behind `@SessionOnly` and the field-level session checks, and these
two were the only gaps left between the policy's reasoning and the code.

**Decision.**

1. **`send-newsletter` is session-only.** `POST /v1/weblogs/{handle}/entries/{id}/send-newsletter` refuses every
   token, password, anonymous and unrecognised caller with 403 `auth.sessionOnly` (after the weblog's 404 and the
   token pin), through the one `@SessionOnly` guard. It stays an MCP exclusion, now with the reason "session-only".
   The editor's "Send as newsletter" button, which calls it with the signed-in session, is unchanged.
2. **The `writer` role grant is session-only.** On `PATCH /v1/admin/users/{userName}` a non-session caller that sends
   a `writer` value differing from the stored one gets 403 `auth.sessionOnly` before anything is applied; resending
   the stored value passes (the pattern of the email rule, SA-18's "another user's email change"). A session grants
   and revokes it as before; only a single-sign-on account has the role to edit.

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
