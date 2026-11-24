# Pressroom documentation

Choose the section for what you are doing.

## Using Pressroom (blog owners and site administrators)

- [`guide/`](guide/README.md): the user guide. Covers writing and publishing,
  pages and media, themes, comments, the newsletter, traffic, users and
  members, site settings, and agent accounts.
- [`../PressroomSSO.md`](../PressroomSSO.md): setting up Google sign-in for
  reader comments.
- [`legal/blog-privacy-policy.md`](legal/blog-privacy-policy.md): a privacy
  policy text for the blogs.

## Running the server (operators)

- [`../docker_deployment.md`](../docker_deployment.md): the production runbook.
  Covers a fresh host, `.env`, DNS and TLS, custom domains, analytics,
  newsletter, reader sign-in, backup and restore, upgrades, firewall and
  troubleshooting.
- [`dev/passwords.md`](dev/passwords.md), "The admin account tool": creating
  the first admin and recovering a lost password on a running host.

## Automating it (scripts and AI agents)

- [`api/README.md`](api/README.md): the `/api/v1` REST API, token scopes, the
  error contract, the MCP endpoint, the OpenAPI document and `bin/pressroom-api`.

## Changing the code (developers)

- [`../CONTRIBUTING.md`](../CONTRIBUTING.md): setup, the architecture in brief,
  the test-first workflow, the gates, releasing.
- [`dev/`](dev/README.md): per-subsystem developer notes. Read the relevant
  file before you change that area.
- [`design/`](design/README.md): the admin UI design system and approved
  component mockups.
- `superpowers/`: specs (`specs/`), implementation plans (`plans/`) and
  reports for past and current work. These are records of decisions at the
  time they were written. The code and `dev/` describe what is true now.
- `readme-images/`: screenshots from earlier revisions of the top-level README.
