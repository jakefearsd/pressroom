# Site administration

This page is for site admins. Everything here is under **Server admin** in the
menu, or in the **Site** section of the Main menu. Installing, upgrading,
email, DNS and TLS are the server operator's job; see
[`docker_deployment.md`](../../docker_deployment.md).

## Configuration

**Server admin → Configuration** holds settings for the whole site. Changes
apply when you choose **Save**. A few settings live in the server's
configuration file instead; your systems administrator changes those.

| Group | Setting | What it does |
|---|---|---|
| Site settings | Site name, Short name, Site description | Used on the front page, in feeds and in browser tab titles. |
| | Bing Webmaster Tools verification code | Optional. |
| | What to show at the site front | The discovery page, a site page, or one weblog. See "The front page". |
| | Privacy policy page | See "Privacy page and footer". |
| | Site administrator's email address | Used for the site's emails, such as password links. Set it, or email features report they cannot send. |
| | Absolute URL of the site | The site's public address, for example `https://blog.example.com`. Required for Google sign-in and for custom domains. |
| Front page discovery | Popularity window, Share of front-page slots filled at random, How often popularity refreshes | Tuning for the discovery page. |
| User settings | Allow users to create new weblogs | Lets any author create a weblog. |
| | Allow group blogging | Lets a weblog have several members and an author own several weblogs; turns on the **Members** screen. |
| | Keep usernames private in templates | Themes see screen names instead. |
| Weblog rendering | Most entries allowed per page, Number of entries to provide in feeds, underscores in entry URLs | Defaults. |
| Revisions and trash | Entry revisions to keep, Revisions to keep for each page, minutes within which repeated saves share a revision, Remove revisions older than, Most revisions the site keeps, Days to keep trashed entries / pages | How much history to keep and how long the trash holds things. `-1` means keep forever; `0` means none or purge at the next sweep. |
| File upload settings | Enable file uploads, Allowed and Forbidden extensions, Maximum file size, Maximum folder size, Strip GPS location data from uploaded photos | Limits for media uploads. Enable uploads only if you trust every author. |
| Theme settings | Allow custom themes | Shows **Stylesheet** and **Templates** to blog admins. |
| Automation API settings | Throttle automation API requests | Rate-limits API tokens. |

Changing the site name, front page, privacy page or Absolute URL refreshes
every blog's cached pages. Changing a value that is out of range shows an error
and saves nothing.

## Users

**Server admin → Users** lists every account, with tabs **All**, **People** and
**Agents** and a filter on username, name or email. Columns show kind, role,
status, last active and how many weblogs the account belongs to.

### Add a person

1. Choose **New person**.
2. Fill in the username (short, one word, cannot be changed later), name,
   email and time zone.
3. Pick a **Role**: **Author** (the default) or **Site admin**.
4. Choose how they get a password: **Email a set-password link** (the person
   sets their own) or **Set a password now** (at least 8 characters).
5. Save.

To add the person to weblogs, open the account and use **Add to weblog**,
choosing **Admin**, **Author** or **Limited**. This needs group blogging to be
on. Weblog Admins can also add members themselves from **Members**.

### Manage an account

Open an account from the list.

- **Enabled**: unticking it signs the person out immediately and blocks the
  next sign-in.
- **Administrator** gives site-wide rights.
- **Send set-password link** emails a reset link.
- You cannot remove your own Administrator right or disable your own account,
  so the site is never left without an admin.
- Accounts that sign in with Google show "Signs in with Google (single sign-on).
  This account has no password." Their role can be raised to editor with **Can
  write (editor)**, but they cannot be made administrators.

Agent accounts are covered in [Agents and the API](agents-and-api.md).

### Lost admin password

If the only administrator forgets their password and email reset is not
working, the server operator resets it from the server using the admin account
tool. See [`docs/dev/passwords.md`](../dev/passwords.md), "The admin account
tool". There is no way to do this from the browser, on purpose.

## Businesses

A business is a record (name, type, address, contact details, links) stored
once and shared by the weblogs that run it. Its details appear in the weblog's
footer card and in structured data for search engines.

- **Server admin → Businesses**: **New business**, edit or **Delete**. A
  business used by any weblog cannot be deleted.
- Weblog Admins pick a business in **Settings → Business**, and can describe
  the weblog as a place (**Place type** **Lodging**, **Town or city**,
  **Region**, **Country code**, **Latitude**, **Longitude**) with a **Booking
  link**.

## The front page

**What to show at the site front** (in **Configuration**) chooses what visitors
see at the site's root address:

- **Discovery page**, the default: a front page assembled from the blogs'
  popular and new posts, with a list of all blogs.
- A **site page** you wrote under **Site → Pages**. Only published site pages are
  offered; with none, the select links to **Write one**.
- One **weblog**, which then serves as the front page.

The discovery page picks posts that are published, not marked noindex, and on
active, visible blogs. Ranking uses recent traffic when Umami is connected;
otherwise it uses random picks. A blog opts its posts out of other blogs'
"popular posts" blocks with **Show popular posts from the site's other blogs on
this blog**, and out of everything with **Weblog is active**.

The three discovery settings in **Configuration** set how many days of
traffic count as recent, what share of slots are filled at random, and how
often the choices refresh. **Maintenance → Front page discovery** shows the
state and has **Refresh now**.

Site pages are served at `https://your-site/slug`. A site page's own slug
redirects to the root when that page is the front page.

## Privacy page and footer

Every page footer shows the weblog's copyright, the Pressroom version and
release date, and a **Privacy policy** link when you set one.

To set it, publish a page (for example named `privacy`) and put its address in
**Privacy policy page** in **Configuration**: the slug of a published site
page, or `blog-handle/slug` for a published page of one weblog. The setting is
checked when you save; an unknown or draft page is refused. If you unpublish
the page later, the link goes dead until you change the setting.
[`docs/legal/blog-privacy-policy.md`](../legal/blog-privacy-policy.md) holds a
policy text you can adapt.

## Custom domains

A weblog can be served at its own hostname instead of `/handle/` on the site
address.

1. Make sure **Absolute URL of the site** is set in **Configuration**. It is
   required once any weblog has a custom domain.
2. Point the hostname's DNS at the server, and make sure the operator has set up
   the proxy and TLS certificate for it.
3. In the weblog's **Settings**, enter the hostname in **Custom domain**
   (for example `berlin.example.com`) and **Save**.

Leave it blank to go back to `/handle/`. A hostname may not be used by another
weblog or be the site's own address. If the server has no certificate covering
the name, Pressroom saves the setting and warns that it will not work over
HTTPS until the proxy is configured.

Things that change for a custom-domain blog:

- The old `/handle/` address redirects permanently to the custom domain.
- Reader comments cannot be posted there.
- The site's sitemap does not list it; the blog has its own.

## Redirects

Pressroom adds a permanent redirect automatically when you rename a page's
slug. Other redirects (for example from an old blog's URLs after a migration) can
be created only through the API; there is no admin screen. See the Redirects
section of [`docs/api/README.md`](../api/README.md).

## Search

Blogs have a search box that searches the blog and the rest of the site.
The whole site is also searchable at `/search`, which shows posts across all
blogs and matching pages. Posts marked noindex, drafts and inactive blogs do not
appear in site-wide results.

If search results look stale, rebuild the index for one weblog in
**Maintenance**.

## Maintenance

**Server admin → Maintenance** has tools for one weblog at a time (pick it in
**Weblog**):

| Tool | Use it to |
|---|---|
| **Flush cache** | Drop the blog's cached pages. |
| **Rebuild search index** | Rebuild search for that blog. Can take a while on a large blog. |
| **Regenerate image renditions** | Re-encode every uploaded photo's smaller copies, for example after an upgrade. |

And site-wide blocks:

- **Revisions**: sizes, the biggest items, what the next clean-up would
  remove, and **Sweep now**.
- **Front page discovery**: popularity state and **Refresh now**.
- **Analytics**: whether Umami is reachable, how many blogs are registered, and
  **Sync now**.

## Site section

Under **Site** on the Main menu you manage the site's own pages (**Pages**),
their images (**Media**) and their look (**Theme**). These work like the
weblog screens of the same names. See [Pages and media](pages-and-media.md) and
[Themes and appearance](themes-and-appearance.md).
