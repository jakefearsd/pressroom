# Privacy Policy

**Effective:** October 25, 2026

This is the privacy policy for **blog.wikantik.com** and every blog it hosts, including the blogs that have their own address (their own domain). I run it: Jacob Fear, [jakefear@gmail.com](mailto:jakefear@gmail.com). The software is Pressroom; this policy covers the sites I operate with it. If you run your own copy of Pressroom, this policy says nothing about your copy. The wiki at wiki.wikantik.com has its own policy.

## The short version

- I do not sell your data for money. The blogs show ads from Google AdSense, and Google uses cookies to choose them. Some US state laws call that "sharing" your data, and you can opt out (see Advertising).
- You can read the blogs without an account. Even then, your IP address and browser details pass through Cloudflare, Google's ad code runs on the page, and a blog may count your visit with a self-hosted analytics tool that does not set cookies.
- Where a blog offers comments, you sign in with Google to comment. Your comments are public under the display name you chose.
- You can delete your own reader account and your comments yourself.
- The newsletter and the contact form are optional; each stores only what you type into it.

## What the blogs collect

**Visiting.** Public traffic reaches the blogs through a Cloudflare tunnel, so Cloudflare sees your IP address and request headers and keeps them under its own policy. The blog's own web server does not write a log of each request. Application log lines record events such as sign-ins and comment actions with account names and ids, and not comment text, email addresses or tokens.

**Analytics.** A blog's owner can switch on page analytics, which I run on my own server (Umami). It is cookie-free and stores nothing on your device. The tracker script and its reports travel to my own server, not to a third party. For each page view it records the page address, the site you came from, your browser, operating system, device type, screen size and language, and an approximate location (country, region and city) looked up from your IP address. It does not store your IP address: it groups one visit's page views under a one-way code made from your IP address, your browser and a value that changes every month, so it cannot recognise you from one month to the next. A blog may also record clicks on its call-to-action links and business card, with the destination site's name and which post you were on.

**Reader account (only if you sign in).** Where a blog offers comments, you sign in with Google to comment. The blog reads three things from Google's answer: your Google account's unique ID (to recognise you next time), your email address and your name (it requires that Google has verified the email address). It creates an account from them. The account has a random login name that is not derived from your email, your email address, a display name, when it was created, and when you last signed in. Your display name starts as your Google name (or the first part of your email address, then "Reader", if the name is unusable) and you can change it on your account page. It does not read your contacts, mail, calendar or files, and I use the information only to sign you in and run the account. Google learns that you signed in to this site. The blog has no password for you and never sees your Google password.

**Comments.** Where a blog owner has turned comments on, a signed-in reader can comment. A comment is stored with your account, the entry it belongs to, its text (up to 4,000 characters), its status and when it was written. Comments from readers the blog owner has not yet approved are held until the owner approves them, and a comment may be hidden or deleted by the blog's owner. Published comments are public, with your display name (never your login name or email address), and the blog owner can reply under them. Comments are not in the blog's search index. The blog's owners (the people who run that blog) can see held comments and the writer's display name. When the owner has notification turned on, the entry title, your display name and the first 500 characters of a held comment are emailed to the blog's administrators. To prevent abuse there is a limit on how fast an account can comment.

**Contact form.** Some blogs have a contact form. Submitting it stores your name, email address, subject, message, the page and post it was sent from, your IP address and the time, and the blog's owner reads it in the blog's inbox. A copy of your name, email address, subject and message is also emailed to the blog's owner. A form that appears to be filled in by a robot is silently discarded and stores nothing. I use what you send only to reply to you.

**Newsletter.** Some blogs offer a newsletter sign-up. The blog passes your email address on to my own listmonk server, which holds the list, runs the opt-in, sends the mail and handles unsubscribing. The blog itself keeps no copy of your address; it records only that a sign-up happened (a count, with no address). listmonk keeps your address, any name you give, when you signed up and whether you confirmed, and records when you open a newsletter and which of its links you click. Its mail goes out through Brevo.

**Blog authors.** The people who write a blog have accounts with a login name, email address, screen name and a hashed password (never the password itself), and can create API tokens, of which only a hash is stored. This section matters only if you write for one of the blogs.

## Cookies and browser storage

**Advertising cookies.** Google AdSense sets cookies and uses similar browser storage on the blogs, and on its own domains (such as doubleclick.net), to show ads, limit how often you see the same one, measure them and prevent fraud. Where you have agreed to personalised ads, it also uses them to choose ads based on your visits to this and other sites. The main ones on the blog's own address are `__gads` and `__gpi` (about 13 months) and `__eoi` (about 6 months); where you are asked for consent, `FCCDCF` and `FCNEC` remember your answer (about 13 months). Google keeps the full, current list at [Cookies used by Google Advertising products](https://business.safety.google/adscookies/).

The blog's own cookies:

| Name | Set when | What it does | Lifetime |
|---|---|---|---|
| `JSESSIONID` | You sign in (including Google sign-in), or use another feature that needs a session (a signed-in reader loading the comment box) | Keeps your session, including being signed in | Until you close the browser; the server drops an idle session after 30 minutes. HttpOnly, Secure, SameSite=Lax |

Reading a blog or the site directory as a visitor does not set `JSESSIONID`; the public pages are built not to start a session.

Blog authors, who sign in with a password on the login page, get a few more first-party cookies. Readers who sign in with Google do not.

| Name | Set when | What it does | Lifetime |
|---|---|---|---|
| `username` | An author submits the password login form | Remembers the login name typed, so the form can be filled in next time | About 30 days. Set by a script in your browser; Secure, SameSite=Strict |
| `favorite_authentication_method` | Same | Remembers that you used the username form | Until you close the browser. Set by a script in your browser; Secure, SameSite=Strict |
| `rfolder-<id>` and `control_<id>` | An author expands or collapses a folder or panel in the author screens | Remembers that preference | About 365 days. Set by a script in your browser; Secure, SameSite=Strict, path `/` |

There is no "stay signed in" cookie. Cloudflare adds no cookies of its own to the blogs. Besides cookies, the author screens keep unsaved drafts and the chosen editor mode in your browser's own storage (localStorage), which is not sent to the server. The blog's own code writes nothing to browser storage on readers' pages; Google's ad code may.

## Who else gets your data

**Cloudflare.** The blogs run on a server I operate myself, and public traffic reaches it through a Cloudflare tunnel. Cloudflare therefore processes every request, including your IP address and headers. Its practices are in [Cloudflare's privacy policy](https://www.cloudflare.com/privacypolicy/).

**Google AdSense.** Every page with ads loads Google's ad code, so Google receives your IP address, browser details, the page you are reading and its cookies on your device. See Advertising below.

**Google sign-in (only if you choose it).** See above. Google's practices are in [Google's privacy policy](https://policies.google.com/privacy).

**Fonts.** The four blog themes do not fetch fonts from Google or any other font service. Each theme's content security policy lets the browser load fonts only from the blog itself (or none at all).

**Maps (OpenStreetMap).** Pages that show a map (the travel blogs) load map tiles from OpenStreetMap's tile server (tile.openstreetmap.org), so OpenStreetMap receives your IP address, browser details and the area you are viewing. See the [OpenStreetMap Foundation privacy policy](https://osmfoundation.org/wiki/Privacy_Policy).

**Videos.** A post can embed a YouTube or Vimeo video. The blog loads the player only after you click play, from youtube-nocookie.com (YouTube's privacy-enhanced mode) or player.vimeo.com. When it loads, that company receives your IP address and browser details and may set its own cookies. See [Google's privacy policy](https://policies.google.com/privacy) and [Vimeo's](https://vimeo.com/privacy).

**Images from other sites.** A post can include an image hosted elsewhere; your browser then fetches it from that site directly, and that site sees your IP address. Posts can also link to other websites, whose privacy practices are theirs.

**Email.** The blogs send email only in a few cases: a password-reset message to a blog author, the held-comment notice and contact-form copies to a blog's owners, and the newsletter, which listmonk sends. All of this mail goes out through Brevo, which handles the recipient's address and the message; see [Brevo's privacy policy](https://www.brevo.com/legal/privacypolicy/).

**Search engines and crawlers.** Public pages are meant to be found. Search engines and other crawlers can copy anything that is public, including published comments with their display names, and I cannot recall those copies.

**Backups.** Every night the server dumps the databases and archives the media, search-index and upload files. These contain everything described on this page. They are kept on the server for 14 days and then deleted. Copies also go to separate storage I control (a network storage device or cloud storage), where none is kept longer than one year.

## Advertising

The blogs show ads served by Google AdSense. Third-party vendors, including Google, use cookies to serve ads based on your prior visits to this website or other websites. Google's use of advertising cookies enables it and its partners to serve ads to you based on your visits to this site and/or other sites on the Internet.

- **Opting out of personalised ads.** You can turn off personalised advertising in [Google's Ads Settings](https://adssettings.google.com). You can opt out of other participating vendors' use of cookies for personalised advertising at [www.aboutads.info](https://www.aboutads.info/choices/) (in Europe, [www.youronlinechoices.eu](https://www.youronlinechoices.eu/)). You still see ads after opting out; they are just not based on your browsing.
- **How Google uses the data.** [How Google uses information from sites or apps that use our services](https://policies.google.com/technologies/partner-sites).
- **Consent.** Visitors in the European Economic Area, the UK and Switzerland are asked before any advertising cookies are used for personalised ads, through Google's consent message, and can change their answer at any time from the "Privacy and cookie settings" link on the page.
- **US state privacy rights.** Under some US state laws (such as California's), letting Google use cookies to show you ads based on your browsing counts as "selling" or "sharing" personal information, even though no money changes hands for your data. You can opt out with the "Do not sell or share my personal information" link where it appears. Opting out means you are shown ads that are not based on your browsing.

## How long things are kept

- **Logs:** the application log lives inside the application container and its Docker output is rotated, five files of 10 MB per service; a new release replaces the container and starts the logs afresh. Cloudflare's own records are kept under Cloudflare's policy.
- **Reader account:** until you delete it.
- **Comments:** until you or the blog's owner delete them. A comment the owner hid and you then delete is emptied but its place is kept so the owner's decision stands; deleting your account removes everything.
- **Contact form submissions:** one year after the last reply in the conversation, then deleted.
- **Analytics:** two years, then deleted.
- **Advertising data:** kept by Google under its own policy; the cookie lifetimes are under Cookies above.
- **Backups:** 14 days on the server, and no more than one year in the separate copies. Deleted data stays in older backups until they age out.

## Your choices

- **Change your name.** Sign in and open your account page (`/pressroom-ui/account.rol` on the site) to change your display name.
- **Delete a comment.** The account page lists your comments with a Delete button for each.
- **Delete your account.** The account page has a "Delete my account" button, confirmed in a dialog. It removes your comments and replies, your roles, tokens and the account itself, and signs you out. If you sign in with Google again, a new account is created. It is not available for accounts that write for a blog.
- **Unsubscribe.** Every newsletter has an unsubscribe link handled by listmonk.
- **Ads.** See Advertising for opting out of personalised ads and, where it applies, changing your consent. You can also block or delete cookies in your browser.
- **What deletion does not remove.** Backups keep the old data until they age out, and anything search engines or others have already copied stays copied. Sign-in on Google's side is yours to manage with Google.
- **Ask me.** Write to me for a copy of what an account holds, to correct something, or to remove something that the page does not let you remove. I will look at each request and tell you what can be done.

## Security

The blogs are served over HTTPS. Passwords are stored only as hashes, and so are API tokens and password-reset links. The contact form, newsletter sign-up and commenting are rate limited per visitor or account. The server accepts no inbound connections from the internet; traffic arrives through the Cloudflare tunnel. No system is perfectly secure, and I cannot promise otherwise.

## Children

These blogs are not directed at children under 13, and I do not knowingly collect their information. If you think a child has an account, write to me and I will remove it.

## Changes

When I change this policy I will update the effective date at the top, and I will send you an earlier version on request.

## Contact

Jacob Fear, [jakefear@gmail.com](mailto:jakefear@gmail.com).
