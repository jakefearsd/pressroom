# Writing

Posts are written in Markdown in a browser editor. This page covers the
editor, publishing states, organising posts, search and sharing fields,
revisions, the trash, and the tools for ordering posts.

## The editor

**New entry** (or opening a post from **Entries**) shows a writing surface on
the left and a rail of controls on the right.

- **Title** sits above the text. Press Enter to move into the body.
- The permalink appears under the title once the entry has an address, with a
  **Copy** button.
- The **Formatting** toolbar offers bold, italic, heading, quote, bulleted and
  numbered lists, link, code and table, an **Insert** menu for shortcodes, and
  **Insert media file** to pick from your uploads.
- The mode switch has three settings: **Write**, **Split** (text beside a live
  preview) and **Preview**. The preview uses your blog's real theme.
  **Full preview** shows the whole page as readers will see it.
- **Writing guide** (or `Ctrl+/`, `Cmd+/` on a Mac) opens a cheat sheet of
  Markdown, shortcodes and keyboard shortcuts. `Ctrl+B`, `Ctrl+I` and `Ctrl+K`
  make bold, italic and links; `Ctrl+S` saves; `Esc` then `Tab` leaves the
  editor if you need to Tab to another control.
- The status line shows a word count and reading time, and whether your work
  is **Unsaved changes**, **Draft saved locally** or **Saved**.
- Paste or drop an image into the text to upload it and insert an `[image]`
  shortcode.

### If you lose your place

The editor keeps a copy of what you are typing in your own browser (not on the
server). If the tab closes or the session ends before you save, the next time
you open the same entry you are offered **Restore** or **Discard** for the
unsaved changes. The copy belongs to your account in that browser, and signing
out clears it.

## Markdown and extras

Standard Markdown works: headings, emphasis, lists, links, quotes, code,
tables. Pressroom adds:

- **Alerts.** A quote that starts with `> [!NOTE]` becomes a coloured callout.
  The kinds are `NOTE`, `TIP`, `IMPORTANT`, `WARNING` and `CAUTION`.
- **Fenced code blocks** with a language name, for example three backticks and
  `python`.
- **Heading links.** Every heading gets an address so readers can link to a
  section.
- **Safe HTML.** Content is cleaned before it is shown: scripts, forms and
  embedded frames are removed.

### Shortcodes

A shortcode is a bracketed tag that expands into richer content when the page
is shown. Use the editor's **Insert** menu to place one with the right
attributes.

| Shortcode | Produces |
|---|---|
| `[image]` | A responsive picture with optional caption and alt text. |
| `[gallery]` | A grid of the photos in a media folder, with a lightbox. Private folders are refused. |
| `[video]` | A YouTube or Vimeo video. Nothing loads from the provider until the reader clicks. |
| `[map]` with `[pin]` | A map with pins; or `auto` to map a folder's geotagged photos. |
| `[cta]` | A call-to-action button. |
| `[faq]` with `[q]` and `[a]` | Questions and answers (also reported to search engines). |
| `[contact]` | A contact form. Messages arrive under **Inquiries**. Not available on site pages. |
| `[subscribe]` | A newsletter sign-up form. Shows only when the blog has a newsletter list. |

To show a shortcode literally, double the brackets: `[[image]]`.

## Publication states

| Status | Meaning |
|---|---|
| Draft | Saved, not visible to readers. |
| Pending | Submitted for review by a Limited member; waits for an Author or Admin to publish it. |
| Scheduled | Will publish by itself at the **Publish time**. |
| Published | Live. |
| Trashed | Removed to the Trash; see below. |

The buttons in the **Publish** group of the rail change with the entry's
state:

- A new entry shows **Save draft** and **Publish**. A Limited member sees
  **Save draft** and **Submit for review** instead.
- A published entry shows **Update** (saves and stays live) and **Unpublish**
  (back to draft). `Ctrl+S` on a live entry means **Update**.

To schedule a post, set **Publish time** to a future date and time (in the
blog's time zone), then choose **Publish**. The status becomes Scheduled and
the post goes live within about a minute of that time. Leaving **Publish
time** blank publishes now.

The **Duplicate** control in the **Entries** list makes a draft copy ("Copy of ...").
The copy is not pinned and not in a strand.

## Organising a post

The **Organize** group in the rail holds:

- **Category.** A post has one category. Manage the list under **Categories**
  (**Add category**). Deleting a category that has posts asks where to move
  them first.
- **Tags.** Separate tags with spaces or commas.
- **Strand**, and **Pin to the top of this blog** (Author and Admin only); see
  "Order, pins and strands" below.

Under the writing surface, **Summary (optional)** is the line shown under the
title in lists and in feeds. Leave it empty and lists show the post's first
couple of sentences.

**Comments closed** stops new comments on this post only.

## SEO and sharing

The collapsed **SEO and sharing** drawer holds fields for search engines and
social networks. All are optional; the blog supplies sensible defaults.

| Field | What it does |
|---|---|
| Meta title | The title shown in search results and the browser tab. A counter warns when it is long. |
| Meta description | The description shown in search results. |
| Search preview | Shows roughly how the result will look. |
| Canonical URL | A full `http://` or `https://` address, if this post is a copy of one published elsewhere. |
| Hide from search engines (noindex) | Asks search engines not to list the post. Also keeps it off the site's front page. |
| Featured image | The post's lead image, chosen with **Choose image**; **Remove image** clears it. |
| Social share image | The picture shown when the post is shared. |
| Structured data type | What search engines are told the page is: **Weblog post (default)**, **Place to visit (TouristAttraction)**, **Trip or itinerary (TouristTrip)**, **Event**, **FAQ page** or **Technical article (TechArticle)**. |

Both images must be pictures from this weblog's media files, outside any
private folder. The place and event types add fields for latitude and
longitude, event start and end, and venue name. An event needs a start time;
without one the page keeps ordinary post markup.

## Revisions

Every save that changes a post's title, summary or text keeps a copy of what it
replaced. The **Revisions** box in the rail lists them:

- **Compare** shows the differences against the current text.
- **Restore** puts that version back. The version you replaced becomes a
  revision itself, so a restore can be undone.
- **Keep** protects a revision from automatic clean-up; **Release** removes
  the protection. One item can have at most ten kept revisions.

Repeated saves by the same person within a few minutes share one revision.
How many revisions the site holds is set under **Configuration** (see
[Site administration](site-administration.md)).

## Entries list

**Entries** lists posts, 30 to a page, with status, category, publish and
update times and **Views (30d)** when traffic numbers are available. Filters
let you narrow by start and end date, **Category**, **Tags**, **Search text**
and status (**All entries**, **Drafts**, **Published**, **Pending**,
**Scheduled**).

Tick rows to show the selection bar: **Publish selected**, **Submit selected
for review**, **Add tag**, **Delete selected**.

## Trash

Deleting an entry (**Delete entry** in the editor, **Delete selected** in the
list) moves it to **Trash**. Nothing is lost yet.

- **Restore** brings it back as a **draft**, never as published, so it cannot
  reappear on the site by surprise.
- **Delete forever** removes it for good, after a confirmation.
- **Empty trash** removes everything in it.
- The screen states how long trashed entries are kept before they are deleted
  automatically. The site administrator sets this (30 days unless changed).

Media files are not trashed: deleting a media file is permanent.

## Order, pins and strands

**Order.** In **Settings**, **Order posts** is **Newest first** or **Oldest
first**. It applies to the blog's home, category, tag and archive lists. Feeds
always list newest first.

**Pins.** Tick **Pin to the top of this blog** in the editor to hold a
published post above the others on the first page of the blog's home list. A
blog may pin at most three posts, counting drafts and scheduled ones;
unpin one first if you hit the limit. Pinned rows say "Pinned" in the
**Entries** list.

**Strands.** A strand is a page that introduces a topic and lists an ordered
series of posts beneath it. Each post in a strand shows "Part N of M" and links
to the parts before and after.

1. Under **Pages**, create a page and switch on **This page is a strand**.
2. In the **Parts** panel, choose **Add posts**, then order them with **Move
   up** and **Move down** (or drag), and **Remove from strand** to take one
   out.
3. Publish the page.

A post can also join a strand from its own **Strand** select in the editor.
A post is in at most one strand, and only posts from the same blog can join.
A strand with posts cannot be turned back into a plain page until its posts
are removed. Deleting the strand page leaves its posts published.
