# Roller Design System — "Quiet Instrument"

This is the design spec used to build the admin UI's preview cards and, from
there, the shipped tokens/components.

**This repository is the source of record.** All seventeen preview cards are
committed under this directory (see [The cards](#the-cards-17) below) — spec
text and rendered cards travel together, so a checkout is enough to see the
whole system and nothing can drift somewhere the repo cannot see. The
companion claude.ai/design project "Roller Design System"
(`a80e9dad-2900-4ed8-a00f-c0331e247434`) renders the same files and is where
it is convenient to *iterate* a card visually; when it and this directory
disagree, this directory wins, and a card iterated there is not done until it
lands here.

Card files are self-contained HTML — inline `<style>`, no external requests —
so opening one in a browser needs no build step and no server.

---

# Roller Design System — "Quiet Instrument" — build spec for preview cards

Client decisions (locked): quiet tool personality; teal accent from the travel theme; light AND dark; one project.

## Tokens (exact values — every card derives from these, no other colors)

Light: --paper:#F7F9F9 --surface:#FFFFFF --ink:#17262A --ink-soft:#5A6E72 --line:#DCE4E4
       --accent:#0F6E68 --accent-quiet:#E3F0EE --good:#2F7D4F --warn:#9A6A1F --bad:#A33B2E --focus:#2AA198
Dark:  --paper:#131C1C --surface:#1A2626 --ink:#DCE7E5 --ink-soft:#8FA5A2 --line:#2A3838
       --accent:#4FB3AA --accent-quiet:#1E3230 --good:#6FBF8F --warn:#D0A45C --bad:#D0705F --focus:#2AA198

Radius: 6px (cards/inputs), 99px (pills). Shadows: none in light except overlay contexts; rely on --line borders. Spacing scale: 4/8/12/16/24/32.

**Documented divergences — deliberate, do not "fix" them.** A public theme may
hold its own identity on *neutrals* provided it keeps the family accent; the
test of whether a difference is identity or drift is whether someone can say
why. Two are on the record:

- `travel` keeps `--tg-paper: #fcfbf7` (warm cream, against the cool `#F7F9F9`)
  and `--tg-radius: 10px` (against 6px). Both belong to its guide-book
  character. Its accent and remaining neutrals were repointed to exact tokens
  in the 2026-08-10 pass — `--tg-accent` had drifted to `#0f6f63`, one hex
  digit off the very token it originated, which is what drift looks like next
  to identity.
- `portfolio` keeps its own near-black neutrals and takes the spec's *dark*
  `--accent` (`#4FB3AA`) verbatim. Dark is its identity; the accent is the
  family tie.

## Type

- UI + headings: "IBM Plex Sans", system-ui fallback. Weights: 450 body, 600 headings/labels.
- Data (slugs, dates, counts, ids): "IBM Plex Mono", ui-monospace fallback, font-variant-numeric: tabular-nums.
- Scale: 12px caps-labels (letter-spacing .08em, uppercase, --ink-soft), 14.5px/1.55 body, 16px section heads, 20px page titles (600), 26px the one oversized element, 28px reserved.
- **These six values are the whole scale. There is no support tier below 12px.**
  The editor rail originally shipped its own 11 / 11.5 / 12.5 / 13px micro-scale
  — and so did its approved card, which is how it survived review — and both were
  collapsed onto this scale in the 2026-08-10 consistency pass. The rule that
  settles any future case: a **label or help text is 12px**, a **value, control or
  body text is 14.5px**, and *quiet is expressed by color and weight, never by
  going smaller* (that is signature move 4 read in the other direction). Shrinking
  below 12px to make something recede is the mistake this line exists to block.
- **The one documented exception to the scale: Plex Mono inside the editor
  and guide sits at 13.5px** so it reads at the same optical size as 14.5px
  Plex Sans beside it; this is an optical-size correction, not a support
  tier — nothing else may use it.
- Card/section headers (`.card-header`, `.section-head` — accordion panel titles, settings-form "display group" headers) use the caps-label role, not an h3/h4's own default size — a heading label, not a second page title.
- Preview cards may load Plex from Google Fonts <link> ONLY IF the design pane allows external fetches — safer: use font-family:"IBM Plex Sans", system-ui and accept fallback rendering in cards; note in the tokens card that the implementation self-hosts Plex as a webjar.

## Signature moves

1. THE SPINE: active nav item in the rail = 2px inset-left rule in --accent + --accent-quiet background + 600 weight. Nothing else in the rail is colored.
2. THE RAIL (replaces the "Powered by Apache Roller" card): 232px sidebar; top block = weblog context (weblog name 600, handle in mono, small status dot --good "Live"); below: tool groups under caps-labels (CONTENT: Entries, Pages, Media, Comments, Inquiries / DESIGN: Theme, Stylesheet, Templates / SETTINGS: Weblog, Members, Maintenance). Footer of rail: user + Sign out, quiet.
3. EMPTY STATES AS INVITATIONS: icon-free; one 16px 600 line ("No entries yet."), one --ink-soft sentence ("Your first post starts the archive."), one primary button ("Write an entry"). Never a bare table strip.
4. WEIGHT, NOT SIZE: a page gets at most one piece of *layout* hierarchy allowed to sit outside the type scale (an entry title, a card's lead field) — everything else that needs emphasis reaches for font-weight (600, against 450 body) rather than a bigger size. The editor's title field is the canonical example: 26px serif, the one oversized element on the page, capped there on purpose; every other "this matters" cue on the same page — status pill, rail group labels, drawer headers — stays on-scale and leans on weight/color instead. Adding a second oversized element to compete with the title is the mistake this rule exists to block.

## Card format (every file)

- First line EXACTLY: <!-- @dsCard group="GROUP" name="NAME" subtitle="SUBTITLE" -->
- Self-contained HTML: inline <style>, no external requests (fonts get family names + fallbacks only).
- Body = two panels side by side (flex, gap 24, wrap): .panel--light and .panel--dark, each a rounded container painting its OWN --paper and carrying its full token set via scoped CSS custom properties (define both sets as classes; do NOT rely on prefers-color-scheme — the card must show both at once regardless of viewer theme).
- Each panel labeled "Light" / "Dark" in caps-label style.
- Realistic Roller content ONLY (weblog "Coastal Guides", handle coastal-guides; entries like "Field Notes from the Coast", "Harbor Cottage Guide"; pages About/Contact; inquiry from "Maren Vole <maren@example.com>"). No lorem.

## The cards (19)

All committed in this directory. The twelve below are the foundational set
(built before the JSPs, as the spec for them); the five listed under *Shipped
reference cards* further down were built alongside their templates. Paths are
grouped to match each card's `@dsCard group=`.

1. `foundations/`tokens-colors.html      group="Foundations" — both palettes as labeled swatch grids (name, hex, usage note per token).
2. `foundations/`tokens-type.html        group="Foundations" — type specimen: caps-label, body para, section head, page title, mono data row; the self-hosting note.
3. `foundations/`tokens-spacing.html     group="Foundations" — spacing/radius demo: a card anatomy diagram with measurements.
4. `shell/`shell-admin.html        group="Shell" — FULL admin frame at ~1100px wide per panel (allowed to stack panels vertically for room): top bar (product name left tiny; weblog switcher center-left; user right), the RAIL with spine on "Entries", content area with page header ("Entries" title + "Write an entry" primary button right) over a table placeholder. This is the hero card.
5. `components/`components-buttons.html group="Components" — primary (accent bg), secondary (line border, ink text), destructive (bad, outline until hover), disabled, small size; focus ring state shown on one.
6. `components/`components-pills.html   group="Components" — status pills: Published(good tint)/Draft(warn tint)/Pending(accent-quiet)/Scheduled(line tint + mono date); plus counts style.
7. `components/`components-nav-rail.html group="Components" — the rail alone, all three groups, spine on Pages, hover state on one item.
8. `forms/`forms-sections.html     group="Forms" — a Weblog Settings excerpt: two grouped sections ("General", "Newsletter") each with 2-3 fields; labels ABOVE inputs (kill the label-column gutter), help text under, one field showing an inline validation error (bad text + border, message "That is not a list UUID. Copy it exactly from Listmonk.").
9. `tables/`tables-list.html        group="Tables" — entries table: checkbox col, title (link, 600) + mono slug under, status pill, category, mono date, row hover; header row caps-labels; selection bar variant shown above (2 selected · Delete).
10. `tables/`tables-empty-states.html group="Tables" — three empty states per the signature: Entries, Inquiries ("No inquiries yet." / "Messages from your contact form land here." / no button), Media ("Add a photo" button).
11. `public/`public-travel-page.html  group="Public themes" — travel-theme _page treatment: teal guide-card header (site title, nav All/General/About), page title, prose, and the CONTACT FORM styled to travel's identity (teal accents, card fields). Single light panel only (travel is a light theme) — note says so.
12. `public/`public-portfolio.html    group="Public themes" — two stacked sections, dark only (portfolio is dark by identity): (a) _page treatment: header + prose + contact form on near-black; (b) the no-image entry card: grid card with generated placeholder (title set large in Plex over a subtle teal-to-transparent linear-gradient wash + mono date) next to the current empty-box for contrast, labeled "before / after".

## Shipped reference cards: journal + front door (Theme Wave)

The `journal` theme and the `frontpage` restyle carry reference cards under
`docs/design/journal/`. They differ from the twelve above only in *when* they
were drawn — alongside their theme templates rather than before them, since
a theme's identity is not derivable from the admin token set alone:

- `journal-home.html` — group="Quiet Journal" — the reading-first entry
  list: date marginalia, serif `qj-title`s, teal hover spine.
- `journal-permalink.html` — group="Quiet Journal" — the reading view: serif
  prose at reading measure, quiet comments.
- `journal-page.html` — group="Quiet Journal" — the `_page` treatment: About
  prose plus the contact form in journal dress.
- `frontpage-front-door.html` — group="Frontpage" — the aggregator: recent
  posts across weblogs plus the teal-wash weblog directory.

Same `@dsCard` header convention and token/type rules as the cards above;
treat these as the worked examples for any future theme's reference card.

## Shipped reference card: the editor (Editor Rebuild)

`docs/design/editor/editor-writing-surface.html` is the approved card the
admin-side `EntryEdit.jsp` rebuild was built against — drawn alongside the
JSP like the journal/frontpage cards above rather than ahead of it, because
the editor is admin chrome, not a public theme, and its rail is a shape
("writing surface
+ 252px publish rail": Publish/Organize boxes, an SEO drawer, quiet
newsletter/revisions boxes, a text-link delete) other admin edit screens with
a lot of secondary metadata may want to reuse. Same `@dsCard` header
convention as the twelve preview cards; treat it as the worked example for
"one writing surface, many settings" admin layouts, and see signature move 4
(WEIGHT, NOT SIZE) above for the rule its title field demonstrates.

## Shipped reference card: the Markdown surface (Editor wave)

`docs/design/editor/editor-markdown-surface.html` is the approved card for
the CodeMirror rebuild of the editor's writing surface itself (Package A of
the Editor wave) — drawn alongside the JSP/JS work rather than ahead of it,
the same convention as the editor rail card above. It depicts the toolbar row,
the Write/Split/Preview segmented control, a split pane whose right half is
the weblog's OWN theme rendering (not a generic markdown-to-HTML dump — the
whole reason the live preview posts through the server), the status line
(word count/reading time on the left, save state on the right), and the
writing guide docked as a peek along the panel's right edge, since a
Bootstrap offcanvas slides in over the same surface it documents rather than
living beside it. Same `@dsCard` header convention and light/dark panel pair
as every other card here; this is the companion piece to the editor rail
card above — that one is the rail's shape, this one is the surface it sits
next to.

## Cards drawn ahead of a rebuild (Consistency Pass, 2026-08-10)

Two screens were too far off-spec to fix by restyling, and the repo's rule is
that a card comes first. These are those cards; the JSPs are rebuilt against
them, not the other way round.

One of the two shipped. The other was overtaken by events and is recorded here
so nobody redraws it.

- `forms/`settings-with-rail.html — group="Forms" — **still pending.**
  `WeblogConfig.jsp` today carries `.form-stacked` from the Task 7 sweep, so
  its labels sit above its fields, but it has no rail: one Save at the bottom
  and no section index. The card applies the editor's two-column shape to a
  settings form — fields on the left, and a 252px rail holding a section index
  (the spine marks where you are, exactly as the nav rail does) plus a Save
  that is always reachable. The delete action is a quiet text link, matching
  the editor.
- **A comment-moderation table card once sat here, and was withdrawn.** It was
  drawn against `Comments.jsp`, and the entire comment subsystem was removed in
  W1 — servlet, manager, moderation screens, pojos, schema and all. It is not
  coming back (see CLAUDE.md's Comments section for why: the feature was
  unreachable by design, not unused by accident). The card file was deleted
  along with it; the reader-facing channels are the contact form and the
  newsletter now. Deliberately not naming the old filename here —
  `DesignCardsTest` fails the build when this spec names a card that is not on
  disk, and that check is worth more than the breadcrumb.

## Agent accounts and API tokens (2026-10-13)

Drawn ahead of the JSPs, per the cards-first rule
(`docs/superpowers/specs/2026-10-13-agent-accounts-and-token-ui-design.md`, section 7):

- `tables/`tables-users.html — group="Tables" — Admin → Users: New person (the one primary) and New agent, All / People / Agents tabs over a server-side filter, and rows with the name over its mono username, kind, role, an Enabled/Disabled pill, a weblog count and a mono last-active.
- `forms/`forms-agent-page.html — group="Forms" — an agent's page: Details (fixed username, name, notes, the Site administrator switch, Save), Status (one Disable or Enable that asks first), Weblogs (a level per membership with Change and Remove, then Add to weblog) and API tokens.
- `forms/`forms-token-reveal.html — group="Forms" — the token form (label; Read, Write or Admin; all its weblogs or one; 30, 90 or 365 days or never) and the one-time reveal: the secret and an MCP snippet, each with Copy, then Done; and the already-shown state.

## Quality bar

Focus states visible; text-wrap balance on headings; tabular-nums wherever digits column; no horizontal scroll inside a panel; every color from the token set; both panels legible standalone.
