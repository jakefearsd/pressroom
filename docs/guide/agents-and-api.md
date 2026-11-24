# Agents and the API

Pressroom has an automation API and an MCP endpoint (`/api/mcp` on your site)
so scripts and AI assistants can do much of what you do in the browser: write
and edit posts, upload media, check SEO, moderate comments. This page covers
the admin screens that control that access. The technical reference (routes,
fields, errors, examples) is [`docs/api/README.md`](../api/README.md) and is not
repeated here.

## Two ways to give access

| You want | Use |
|---|---|
| A script or assistant that acts as you | Your own API token (**Your profile → Your API tokens**) |
| A script or assistant with its own identity, visible as such in history and revocable separately | An agent account with its own tokens |

An agent account is an account for software. It has no password, never signs
in and cannot reset anything. It works only through the tokens a site admin
creates for it.

## Create an agent account (site admins)

1. Open **Server admin → Users** and choose **New agent**.
2. Fill in:
   - **Username**: letters and digits; it cannot be changed later.
   - **Name**.
   - **Email (optional)**: left blank, it becomes the username at
     `agents.invalid`. Nothing is ever mailed to an agent.
   - **Notes**: what this agent is for. Shown only on this screen.
   - **Site administrator**: tick only if the agent must use the site-wide
     admin API. With it on, an **Admin** token from this agent can use that API.
3. Choose **Create agent**.

On the agent's page you can then:

- Add it to weblogs, with a level of **Admin**, **Author** or **Limited**, just
  as for a person. The agent can reach only the weblogs it belongs to, and a
  Limited agent's tokens write drafts, not published posts.
- **Disable** it. Every one of its tokens stops working on its next request.
  **Enable** brings them back.
- Create tokens for it with **New token**, and revoke them.

## API tokens

A token is a secret string that a script or assistant sends to prove who it is.

### Create one

1. For yourself: **Your profile → Your API tokens → New token**. For an agent:
   its page, **New token**.
2. Fill in:

   | Field | Choices |
   |---|---|
   | Label | A name you will recognise, such as "SEO audit script". |
   | Role | **Read** (can only look), **Write** (can change posts, media, categories and pages), **Admin** (also the site-wide admin API, if the account may use it). |
   | Weblog | **All its weblogs**, or one weblog. Pin a token to a single weblog whenever you can. |
   | Expires | **In 30 days**, **In 90 days** (the default), **In 365 days** or **Never**. |

3. Choose **Create token**.
4. Copy the secret now. It is shown **once**; reloading the page only says it
   was already shown. The page also offers an **MCP connection** snippet to
   paste into an AI assistant's configuration.

A token can never do more than the account that owns it. A Read token cannot
write, whatever the account may do, and a token pinned to one weblog cannot see
any other. If you lose the secret, revoke the token and create another.

Treat tokens like passwords: give each tool its own, use the narrowest role
and weblog that works, set an expiry, and do not paste them into chats or
commit them to files.

### Review and revoke

- **Your profile → Your API tokens** lists your live tokens with label, role,
  weblog, when created, when it expires and when it was **Last used**.
  **Revoke** stops a token at once (after a confirmation).
- **Server admin → API tokens** lists every live token on the site with its
  owner and who issued it. **Show revoked and expired** adds the old ones. A site
  admin can revoke any token there.

Reader accounts cannot create tokens. Disabling an account (person or agent)
stops all of its tokens.

## What to read next

- [`docs/api/README.md`](../api/README.md): the full reference, including
  token scopes, the error contract, the MCP tools and the command-line client
  `bin/pressroom-api`.
- The API is marked unstable while Pressroom is 0.x. If you automate against it,
  pin the Pressroom version you tested with.
