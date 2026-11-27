# Readers

This page covers the ways readers reach you and the ways you see them:
comments, contact messages, the newsletter, and traffic numbers.

## Comments

### Who can comment

Only readers who have signed in. Pressroom has no anonymous comments and no
CAPTCHA. A reader signs in with Google from the **Sign in with Google to
comment** link under a post; the first sign-in creates a reader account. If
Google sign-in has not been set up on your site, nobody can comment. The
server operator sets it up (see [`PressroomSSO.md`](../../PressroomSSO.md)).

Comments are not available on a weblog that is served from its own custom
domain: readers cannot sign in there. Existing published comments still show.

### Turn comments on or off

- **Settings → Allow comments** turns comments on or off for the whole weblog.
  Turning them off keeps existing comments; moderation still works.
- **Comments closed** in a post's editor stops new comments on that post only.
- **Settings → Email me when a comment needs approval** emails the weblog's
  Admins when a comment is held. It needs the server to be able to send mail.

### What is held for approval

| Who comments | Result |
|---|---|
| A weblog member (Admin, Author or Limited) | Published at once, with an **Author** badge. |
| A reader who already has a published comment on this weblog and has never been hidden | Published at once, unless the comment has more than two links. |
| Any other reader | Held as **Awaiting approval**. |

Trust is per weblog. A reader you **Hide** loses trust and goes back to
approval. Deleting a spam comment does not remove trust.

Comments are limited to 4000 characters. A reader who posts too fast is asked
to wait (by default, 5 comments in 10 minutes and 30 a day).

### Moderate

Open **Comments** (Author level and up). The three tabs are **Awaiting
approval** (the default), **Published** and **Hidden**; you can filter to one
post.

- **Approve** publishes a held or hidden comment.
- **Hide** takes a comment down without deleting it, and untrusts the reader.
- **Delete** removes it for good, with any reply of yours. A confirmation
  appears first.
- **Reply** writes your answer under a published comment; **Post reply**
  publishes it, marked as the author's. Each comment can have one reply.
- Tick several rows to approve, hide or delete in bulk.

The Main menu and the entry editor show how many comments are waiting.

### A reader's own account page

A signed-in reader has an account page ("Your account", reached from the
"You" menu in each blog's header) with their saved articles, the blogs they
follow (with "Email me new posts" where the blog has a newsletter), their
reading history (with "Clear history" and a "Keep my reading history"
switch), their own comments (marked "Answered by" when you replied), the
reply-email switch, their display name, and "Delete my account". A display
name may not copy the name of a weblog member. An account that holds any weblog
membership, or has authored posts, cannot delete itself.

### Save, Follow and reading history

With reader sign-in on, every post offers **Save** in its share row and
**Follow** in the blog's header and at the end of the post. A signed-out
reader who clicks either is offered the sign-in, and the save or follow
completes when they come back. A post counts as read after ten seconds on
the page; blog home pages and the front page then show **Continue reading**
and **From blogs you follow** to that reader, with a small **Read** label on
posts they have read. Signed-out pages stay the same for every visitor (apart from the Save and
Follow buttons themselves). A
blog on its own custom domain does not offer these yet.

## Contact messages (Inquiries)

Put a `[contact]` shortcode in a post or page to show a contact form. Messages
land under **Inquiries**, newest first, with who sent them, subject, message
and the page they came from. Select rows to delete them. The form stores every
message before anything else happens, so a mail outage does not lose one.
Spam bots that fill a hidden field are silently dropped.

Contact forms are not available on site pages.

## Newsletter

Pressroom connects to a Listmonk server for newsletters. The site operator
connects the server; you connect each weblog to one list.

1. In Listmonk, open **Lists**, then your list, and copy its UUID.
2. In Pressroom, open **Settings** and find **Newsletter**.
3. Paste it into **Newsletter list UUID** and **Save**.

The Newsletter section then tells you the form's state: "Subscribe form: on",
"off. No newsletter list is set for this blog" or "off. This site has no
newsletter service configured". A blank UUID turns the form off.

When it is on:

- Put `[subscribe]` in a post or page to show the sign-up form.
- To mail a post, open the published entry and use the **Newsletter** box in
  the editor's rail: **Send as newsletter**. A confirmation warns that this
  emails every subscriber. Only published entries can be sent, and each entry
  can be sent once.

Subscribers, bounces and unsubscribes are managed in Listmonk, not in Pressroom.

## Traffic

Pressroom reads visitor numbers from Umami, the analytics service the operator
connects. Pressroom registers each weblog with Umami automatically, so there
is nothing to set up per blog. Visitors are counted without cookies.

### A weblog's Traffic page

Open **Traffic** (Admin level only, because the numbers are the blog's whole
audience). Choose a **Time period**: **24h**, **7d**, **30d** or **90d**.

- The tiles show **Visitors**, **Visits**, **Views**, **Bounce rate** and
  **Visit time**, each compared with the previous period.
- A chart shows views over the period.
- Tabs break the numbers down: **Posts**, **Sources**, **Places**,
  **Devices**, **Events** and **Campaigns**.

The numbers are a cache that refreshes about every 15 minutes, and the page
says when they were taken. If you see "Not registered with Umami yet" or "No
numbers yet", the blog has not been picked up; ask the site admin to check
**Maintenance**.

The **Entries** list also shows **Views (30d)** for each post.

### Site-wide traffic (site admins)

**Server admin → Traffic** lists every blog with its views, the change from the
previous period, and its most-read post, plus the site's most-read posts and
sources. Visitor counts are per blog and are not added together, because one
reader of two blogs would count twice. A row for **Site pages (front page)**
covers the site's own pages.

On **Server admin → Maintenance**, the **Analytics** block shows whether Umami
is reachable, how many blogs are registered, and a **Sync now** button.
