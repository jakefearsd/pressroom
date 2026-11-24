# Getting started

This page takes you from sign-in to a published first post, and shows where
everything lives.

## The Main menu

After you sign in, the **Main menu** shows **Your weblogs**. Each weblog has
links for **New entry**, **Entries**, **Theme** and **Settings**, and shows
your level on it (Admin, Author or Limited). When comments are waiting, the
weblog shows how many are awaiting approval.

Other cards on the Main menu:

- **Create a weblog**, if the site lets you (see below).
- **Your profile**: change your name, email, password and time zone, and
  manage your own API tokens (**Your API tokens**).
- **Server administration**, for site admins only.
- **Site**, for site admins only: **Pages**, **Media** and **Theme** for the
  pages that belong to the site itself rather than to one weblog.

Once you belong to two or more weblogs, a weblog switcher appears in the top
bar so you can jump between them without going back to the Main menu.

## Create a weblog

You can create a weblog if you are a site admin, or if the administrator has
turned on **Allow users to create new weblogs**. Without group blogging, each
account may own only one weblog; with it, an account may own several.

1. On the Main menu, choose **Create a weblog**.
2. Fill in the form:

   | Field | Notes |
   |---|---|
   | Name | The weblog's title. Required. |
   | Handle | The short name used in the address. See the rules below. |
   | Email address | The owner's contact address. Required. |
   | Locale | Language and regional formats. |
   | Time zone | Used for publish times and dates on the blog. |
   | Theme | One of the shared themes; see [Themes and appearance](themes-and-appearance.md). |

3. Choose **Create a weblog**. You are taken to the new weblog's screens.

The form shows the weblog's address (**URL**) as you type the handle.

### Handle rules

The handle becomes part of every address on the blog (`https://your-site/handle/`),
and it cannot be changed later, so choose it with care.

- Letters and digits only, no spaces, hyphens or punctuation. (Operators can
  widen the allowed characters, but the default is letters and digits.)
- At most 255 characters.
- Not already used by another weblog, including a disabled one.
- Not a reserved word: names the application itself uses, such as `api` or
  `pressroom-ui`, are refused. The form says "That handle is reserved for
  pages of the site itself."
- Not the address of a site page. If a site page (even a draft) already uses
  `about` as its slug, no weblog can be called `about`.

A weblog can later be served from its own domain; see
[Site administration](site-administration.md).

## The admin screens

The left rail groups screens by task. What you see depends on your level on
the current weblog.

| Group | Screen | What it does | Needs |
|---|---|---|---|
| Create & edit | **New entry** | Opens the editor | Limited and up |
| | **Entries** | List, filter and bulk-edit entries | Author and up |
| | **Trash** | Restore or permanently delete removed entries | Author and up |
| | **Inquiries** | Messages sent through the contact form | Author and up |
| | **Comments** | Approve, hide, reply to and delete comments | Author and up |
| | **Categories** | Add, rename and delete categories | Author and up |
| | **Pages** | Static pages, their order, strands | Author and up |
| | **Media files** | Uploads and folders | Author and up |
| | **Traffic** | Visitor numbers | Admin |
| Design | **Theme** | Pick the look of the blog | Admin |
| | **Stylesheet** | Edit your copy of the theme's CSS | Admin, if the site allows custom themes |
| | **Templates** | Edit the page templates (advanced) | Admin, if the site allows custom themes |
| Preferences | **Settings** | Title, comments, newsletter, custom domain and more | Admin |
| | **Members** | Who can work on this weblog | Admin, if group blogging is on |
| Server admin | **Configuration**, **Users**, **Businesses**, **API tokens**, **Traffic**, **Maintenance** | Site-wide screens | Site admin |

## Publish your first post

1. Choose **New entry**.
2. Type a **Title** and write the post in the large text area. Writing is in
   Markdown; the toolbar covers the common formatting and **Writing guide**
   (or `Ctrl+/`) lists everything.
3. In the right-hand rail, pick a **Category** and add **Tags** if you want.
4. Choose **Publish**.

A confirmation says "Entry published" with a **View it** link. The full
editor is described in [Writing](writing.md).

## Weblog settings worth checking first

Open **Settings** and review these before you announce the blog.

- **Title**, **Tagline** and **About this weblog**.
- **Email address of weblog owner**.
- **Allow comments**: comments are accepted only from readers who signed in;
  see [Readers](readers.md).
- **Weblog is active (and should be included in community listings)**:
  untick it to keep the blog out of the site's front page and directory.
- **Language and time zone**.
- **Page width**: **Standard** or **Wide**; see
  [Themes and appearance](themes-and-appearance.md).

Choose **Save**. The page confirms with "Settings saved."

## Removing a weblog

At the bottom of **Settings**, **Remove weblog** deletes the weblog with all
its entries, media files and settings. It asks for confirmation and cannot be
undone.
