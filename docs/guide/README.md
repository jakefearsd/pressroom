# Pressroom user guide

This guide is for people who run a Pressroom site from the web admin screens:
site administrators and blog owners. It describes what you see and click. It
does not cover installing or upgrading the server (see
[`docker_deployment.md`](../../docker_deployment.md)) or changing the code (see
[`docs/dev/`](../dev/)).

The guide matches Pressroom 0.16.5.

## Contents

| Read | When you want to |
|---|---|
| [Getting started](getting-started.md) | Sign in, create your first weblog, find your way around the admin screens |
| [Writing](writing.md) | Write, schedule and publish posts; categories, tags, SEO fields, revisions, trash, pins and strands |
| [Pages and media](pages-and-media.md) | Add static pages, order the navigation, upload and organise images |
| [Themes and appearance](themes-and-appearance.md) | Pick a theme, set the page width, edit the stylesheet or templates |
| [Readers](readers.md) | Moderate comments, run a newsletter, read traffic numbers, handle contact inquiries |
| [Site administration](site-administration.md) | Site-wide settings, users, the front page, custom domains, the privacy page, maintenance |
| [Agents and the API](agents-and-api.md) | Create agent accounts and API tokens for scripts and AI assistants |

## Who can do what

Pressroom has two layers of permission: what an account can do on the whole
site, and what it can do on each weblog.

### Site-wide roles

| Role | What it is |
|---|---|
| Site admin | Runs the server. Sees the **Server admin** screens (Configuration, Users, Businesses, API tokens, Traffic, Maintenance) and the **Site** section (site pages, site media, site theme). |
| Author | A normal signed-in account. Can sign in, edit its profile, and work on the weblogs it belongs to. Can create a weblog if the site allows it. |
| Reader | An account made automatically when a visitor signs in with Google to comment. A reader can comment and manage its own comments on an account page. A reader cannot open the admin screens. |
| Agent | An account for software. It has no password, never signs in, and works only through API tokens. |

An administrator can promote a reader to author with **Can write (editor)** on
the user's edit screen; the person must sign out and in again afterwards.

### Roles on one weblog

A weblog's **Members** screen (under Preferences) gives each person one of
three levels.

| Level | Can do |
|---|---|
| Admin | Everything on that weblog: write and publish, moderate comments, manage categories, pages, media, theme, settings and members, and see Traffic. |
| Author | Write and publish entries, moderate comments, manage categories, pages and media. Cannot change settings, theme or members. |
| Limited | Write drafts and submit them for review. Can edit only their own drafts and pending entries. |

The person who creates a weblog is its Admin. A weblog always keeps at least
one Admin.

The **Members** screen appears only when the administrator has turned on
**Allow group blogging** (see [Site administration](site-administration.md)).

## First sign-in

1. Open `/pressroom-ui/login.rol` on your site, or use the **Sign in** link in
   the footer of any blog.
2. Enter your username and password. **Remember me (up to two weeks)** keeps
   you signed in across browser restarts.
3. You land on the **Main menu**. It lists your weblogs, each with shortcuts
   for **New entry**, **Entries**, **Theme** and **Settings**.

Forgot your password? Choose **Forgot your password?** on the sign-in page,
enter your username or email, and follow the link in the email. The link is
valid for one hour. This needs the server to be able to send email; if it
cannot, the page says so and an operator has to reset the password.

The very first administrator account is created on the server, not in the
browser, by whoever runs the site. If you are that person, the steps are in
[`docs/dev/passwords.md`](../dev/passwords.md) under "The admin account tool".

Readers who sign in with Google use the **Sign in with Google to comment**
link under a post. Setting that up is covered in
[`PressroomSSO.md`](../../PressroomSSO.md).

## Conventions in this guide

- Button, menu and field names are written in **bold** exactly as the screen
  shows them.
- "The menu" means the left rail of the admin screens, which groups screens
  under Create & edit, Design and Preferences (and Server admin for site
  admins).
- Handles, slugs and addresses are shown in `code` style.
