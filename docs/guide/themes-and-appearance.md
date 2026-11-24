# Themes and appearance

A theme sets how a weblog looks: layout, type, colours. Each weblog picks one.

## The shared themes

| Theme | Character | Good for |
|---|---|---|
| **Quiet Journal** (`journal`) | Reading-first list of posts with dates in the margin, serif titles, neutral. The default. | Writing-led blogs |
| **Portfolio** (`portfolio`) | Dark and image-first: a full-width grid of featured images on the front page, large images on post pages. Built for `[gallery]`. | Photography |
| **Travel Guide** (`travel`) | Light, readable cards with a hero image and excerpt, and a wide reading column on posts. Styles `[map]`, `[faq]` and `[cta]` as itinerary, question and booking blocks. | Travel and place guides |
| **Frontpage** (`frontpage`) | For the aggregated front page of a multi-blog site. Offered for the site's own pages, not for ordinary weblogs. | The site space |

Shared themes are
maintained by Pressroom: when Pressroom is upgraded, a weblog on a shared theme
gets the improvements.

## Change the theme

1. Open **Theme** (under Design). It needs the Admin level on the weblog.
2. Under **Shared theme**, use **Choose a shared theme** to pick one.
3. Choose **Preview** to open the weblog with that theme in a new window,
   without changing anything.
4. Choose **Use this theme**. "Theme set to ..." confirms it, and **View your
   weblog** opens the result.

The same screen shows which theme the weblog uses now and whether its
stylesheet is your own edited copy.

## Page width

**Settings** has a **Page width** choice:

- **Standard**: the narrower column.
- **Wide**: a wider page and longer lines of text on large screens. Phones look
  the same either way.

New and existing blogs default to **Wide**. A customised stylesheet keeps its
own widths, so the setting has no effect on a theme you have edited.

## Other look-and-feel settings

All in **Settings**:

- **Title**, **Tagline**, **About this weblog** and **Icon**.
- **Copyright notice**: shown in the footer of every page. Leave it blank to
  get "(c) year, blog name. All rights reserved."
- **Entries shown per page**.
- **Show the share row under posts**: a row of copy-link, email and network
  buttons under each post. Untick it to remove it from every post.
- **Show popular posts from the site's other blogs on this blog**: untick it to
  hide other blogs' posts here. Your posts still appear on the front page and on
  other blogs.

The blog's footer carries a notice with the Pressroom version and, when the
administrator has set one, a link to the privacy policy.

## Advanced: your own stylesheet and templates

These screens are for people comfortable with CSS and template code. A mistake
here can break a page. They appear only when the administrator has turned on
**Allow custom themes** in **Configuration**; if they are missing, that setting
is off.

**Stylesheet.** On a shared theme that supports it, **Stylesheet** offers
**Copy stylesheet**. You then edit your own copy of the theme's CSS, and the
changes show on every page that uses it.

- **Revert** resets your copy to the theme's current stylesheet.
- **Delete** removes your copy, so the weblog goes back to the shared one.

A weblog with its own stylesheet keeps its own widths, so **Page width** does
not change it.

**Custom theme and Templates.** On the **Theme** screen, **Custom theme**
copies the shared theme's templates into the weblog so you can edit them under
**Templates**. This is one way: from then on the weblog no longer gets updates
to the shared theme. If you later switch back to a shared theme, Pressroom asks
whether to switch back to your custom theme or overwrite it with the shared
one, and overwriting replaces your edits.

Under **Templates** you can edit the templates that make up the pages, and add
a new template (**Add a new template**) to create an entirely new page. Some
templates are required and cannot be deleted. Templates are written in
Velocity. A reference to something that no longer exists in a Velocity template
prints as plain text instead of an error, so check the page after you edit.

Developer notes for theme authors are in
[`docs/dev/themes-and-templates.md`](../dev/themes-and-templates.md).

## Site pages and the front page theme

Site admins choose the theme of the site's own pages from **Site → Theme** on
the Main menu. It offers `journal`, `portfolio`, `travel` and `frontpage`, and
never a custom theme.
