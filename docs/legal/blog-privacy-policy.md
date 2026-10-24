# Privacy Policy

**Effective:** TODO(Jake): the date this goes live.

This is the privacy policy for **blog.wikantik.com** and every blog it hosts, including the blogs that have their own address (their own domain). I run it: Jacob Fear, [jakefear@gmail.com](mailto:jakefear@gmail.com). The software is Roller; this policy covers the sites I operate with it. If you run your own copy of Roller, this policy says nothing about your copy. The wiki at wiki.wikantik.com has its own policy.

## The short version

- I do not sell your data. The blogs show no ads today (see Advertising).
- You can read the blogs without an account and without any cookie. Even then, your IP address and browser details pass through Cloudflare, and a blog may count your visit with a self-hosted analytics tool that does not set cookies.
- Commenting and the reader account page need a sign-in with Google. Your comments are public under the display name you chose.
- You can delete your own reader account and your comments yourself.
- The newsletter and the contact form are optional; each stores only what you type into it.

## What the blogs collect

**Visiting.** Public traffic reaches the blogs through a Cloudflare tunnel, so Cloudflare sees your IP address and request headers. TODO(Jake): the production Caddy and app are not configured to write a per-request access log in this repository's config; confirm what the server keeps (Cloudflare analytics, any Caddy or host-level access log) and for how long. Application log lines record events such as sign-ins and comment actions with account names and ids, and not comment text, email addresses or tokens.

**Analytics.** A blog's owner can switch on page analytics, which I run on my own server (Umami). It is cookie-free and stores nothing on your device. The tracker script and its reports travel to my own server, not to a third party. A blog may also record clicks on its call-to-action links and business card, with the destination site's name and which post you were on. TODO(Jake): state exactly what Umami records per visit (IP handling, browser, country, referrer, page) and how long it keeps it, from your Umami configuration; and which blogs have analytics on.

**Reader account (only if you sign in).** You sign in with Google to comment. The blog reads three things from Google's answer: your Google account's unique ID (to recognise you next time), your email address and your name (it requires that Google has verified the email address). It creates an account from them. The account has a random login name that is not derived from your email, your email address, a display name, when it was created, and when you last signed in. Your display name starts as your Google name (or the first part of your email address, then "Reader", if the name is unusable) and you can change it on your account page. It does not read your contacts, mail, calendar or files, and I use the information only to sign you in and run the account. Google learns that you signed in to this site. The blog has no password for you and never sees your Google password. TODO(Jake): confirm Google sign-in is switched on at blog.wikantik.com (the Google client credentials are set in the production .env).

**Comments.** Where a blog owner has turned comments on, a signed-in reader can comment. A comment is stored with your account, the entry it belongs to, its text (up to 4,000 characters), its status and when it was written. Comments from readers the blog owner has not yet approved are held until the owner approves them, and a comment may be hidden or deleted by the blog's owner. Published comments are public, with your display name (never your login name or email address), and the blog owner can reply under them. Comments are not in the blog's search index. The blog's owners (the people who run that blog) can see held comments and the writer's display name. When email is set up and the owner has notification turned on, the entry title, your display name and the first 500 characters of a held comment are emailed to the blog's administrators. To prevent abuse there is a limit on how fast an account can comment.

**Contact form.** Some blogs have a contact form. Submitting it stores your name, email address, subject, message, the page and post it was sent from, your IP address and the time, and the blog's owner reads it in the blog's inbox. A form that appears to be filled in by a robot is silently discarded and stores nothing. I use what you send only to reply to you. TODO(Jake): whether a notification email of the submission goes to the owner (depends on the mail setup) and how long submissions are kept; the code has no automatic deletion.

**Newsletter.** Some blogs offer a newsletter sign-up. The blog passes your email address on to my own listmonk server, which holds the list, runs the opt-in, sends the mail and handles unsubscribing. The blog itself keeps no copy of your address; it records only that a sign-up happened (a count, with no address). TODO(Jake): confirm listmonk is running for blog.wikantik.com, what it stores (address, name, sign-up date, open or click tracking), and whether the outgoing mail goes through a provider that gets the address.

**Blog authors.** The people who write a blog have accounts with a login name, email address, screen name and a hashed password (never the password itself), and can create API tokens, of which only a hash is stored. This section matters only if you write for one of the blogs.

## Cookies and browser storage

| Name | Set when | What it does | Lifetime |
|---|---|---|---|
| `JSESSIONID` | You sign in (including Google sign-in), or use another feature that needs a session | Keeps your session, including being signed in | Until you close the browser; the server drops an idle session after 30 minutes. HttpOnly, SameSite=Lax. TODO(Jake): confirm the Secure attribute reaches the browser through Caddy and Cloudflare; the repository config does not set it. |

Reading a blog or the site directory as a visitor does not set `JSESSIONID`; the public pages are built not to start a session. There is no "stay signed in" cookie: that feature is off in the shipped configuration and Google sign-in never uses it (TODO(Jake): confirm no `ROLLER_REMEMBERME_ENABLED` setting is present in the production `.env`; if it is on, a 14-day "remember-me" cookie would need adding here). There are no analytics, advertising or cross-site tracking cookies today. TODO(Jake): the themes' scripts were not found to write cookies or browser storage (a search of the theme and template files found none); confirm no other cookies are set by the Cloudflare tunnel for the blog hostnames.

## Who else gets your data

**Cloudflare.** The blogs run on a server I operate myself, and public traffic reaches it through a Cloudflare tunnel. Cloudflare therefore processes every request, including your IP address and headers. Its practices are in [Cloudflare's privacy policy](https://www.cloudflare.com/privacypolicy/).

**Google sign-in (only if you choose it).** See above. Google's practices are in [Google's privacy policy](https://policies.google.com/privacy). If you never choose Google sign-in, nothing is sent to Google by the blog's own pages.

**Fonts.** The four blog themes do not fetch fonts from Google or any other font service. Each theme's content security policy lets the browser load fonts only from the blog itself (or none at all).

**Maps (OpenStreetMap).** Pages that show a map (the travel blogs) load map tiles from OpenStreetMap's tile server (tile.openstreetmap.org), so OpenStreetMap receives your IP address, browser details and the area you are viewing. See the [OpenStreetMap Foundation privacy policy](https://osmfoundation.org/wiki/Privacy_Policy). TODO(Jake): confirm whether production sets a different tile server (the runtime property for map tiles in Global Config).

**Videos.** A post can embed a YouTube or Vimeo video. The blog loads the player only after you click play, from youtube-nocookie.com (YouTube's privacy-enhanced mode) or player.vimeo.com. When it loads, that company receives your IP address and browser details and may set its own cookies. See [Google's privacy policy](https://policies.google.com/privacy) and [Vimeo's](https://vimeo.com/privacy).

**Images from other sites.** A post can include an image hosted elsewhere; your browser then fetches it from that site directly, and that site sees your IP address. Posts can also link to other websites, whose privacy practices are theirs.

**Email.** The blogs send email only in a few cases: a password-reset message to a blog author, and the held-comment notice to a blog's owners (and the newsletter, which listmonk sends). TODO(Jake): confirm whether outgoing mail is configured in production and which provider carries it (the deploy example names Brevo); that provider handles the recipient's address and the message. Name it here.

**Search engines and crawlers.** Public pages are meant to be found. Search engines and other crawlers can copy anything that is public, including published comments with their display names, and I cannot recall those copies. TODO(Jake): whether the blogs notify search engines of new posts (IndexNow or similar); the repository audit did not look for this.

**Backups.** Every night the server dumps the databases and archives the media, search-index and upload files. These contain everything described on this page. TODO(Jake): where copies are kept (the repository's backup service writes to a backup volume on the server and deletes files older than 14 days by default; whether the production setting differs, and whether copies go anywhere else, such as another device or cloud storage).

## Advertising

The blogs show no ads today and load no advertising code.

TODO(Jake): when Google AdSense is turned on for any blog, this section must be rewritten before it goes live. It must name Google as a third-party vendor that uses cookies to serve ads based on a visitor's prior visits to this and other sites, explain that Google's use of advertising cookies lets it and its partners serve ads based on those visits, link [Google's ad settings](https://adssettings.google.com) where visitors can opt out of personalised advertising, link [How Google uses data when you use our partners' sites or apps](https://policies.google.com/technologies/partner-sites), say which cookies and consent choices apply, and update the cookie table and the short version.

## How long things are kept

- **Logs:** the application log lives inside the application container and its Docker output is rotated, five files of 10 MB per service; a new release replaces the container and starts the logs afresh. TODO(Jake): Cloudflare's own retention, and any log kept on the host.
- **Reader account:** until you delete it.
- **Comments:** until you or the blog's owner delete them. A comment the owner hid and you then delete is emptied but its place is kept so the owner's decision stands; deleting your account removes everything.
- **Contact form submissions:** no automatic deletion. TODO(Jake): your retention intent.
- **Analytics:** TODO(Jake): Umami retention.
- **Backups:** TODO(Jake): see Backups above. Deleted data stays in older backups until they age out.

## Your choices

- **Change your name.** Sign in and open your account page (`/roller-ui/account.rol` on the site) to change your display name.
- **Delete a comment.** The account page lists your comments with a Delete button for each.
- **Delete your account.** The account page has a "Delete my account" button, confirmed in a dialog. It removes your comments and replies, your roles, tokens and the account itself, and signs you out. If you sign in with Google again, a new account is created. It is not available for accounts that write for a blog.
- **Unsubscribe.** Every newsletter has an unsubscribe link handled by listmonk.
- **What deletion does not remove.** Backups keep the old data until they age out, and anything search engines or others have already copied stays copied. Sign-in on Google's side is yours to manage with Google.
- **Ask me.** Write to me for a copy of what an account holds, to correct something, or to remove something that the page does not let you remove. I will look at each request and tell you what can be done.

## Security

The blogs are served over HTTPS. Passwords are stored only as hashes, and so are API tokens and password-reset links. The contact form, newsletter sign-up and commenting are rate limited per visitor or account. The server accepts no inbound connections from the internet; traffic arrives through the Cloudflare tunnel (TODO(Jake): confirm this holds for docker1). No system is perfectly secure, and I cannot promise otherwise.

## Children

These blogs are not directed at children under 13, and I do not knowingly collect their information. If you think a child has an account, write to me and I will remove it.

## Changes

When I change this policy I will update the effective date at the top. TODO(Jake): how change history is kept for this page.

## Contact

Jacob Fear, [jakefear@gmail.com](mailto:jakefear@gmail.com).
