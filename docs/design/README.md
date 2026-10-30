# Pressroom Design System — "Quiet Instrument"

The spec is [design-system.md](design-system.md). This directory also holds
every preview card it describes, so the repository — not any external tool —
is the source of record for how Pressroom is supposed to look.

Cards are self-contained HTML with inline `<style>` and no external requests.
Open one directly in a browser; there is no build step and no server.

| Group | Cards |
|---|---|
| [`foundations/`](foundations) | colors, typography, spacing & radius |
| [`shell/`](shell) | the admin frame — top bar, rail, content (the hero card) |
| [`components/`](components) | buttons, status pills, navigation rail |
| [`forms/`](forms) | settings form — labels above fields, inline error |
| [`tables/`](tables) | entries table, empty states |
| [`editor/`](editor) | entry editor — writing surface & publish rail |
| [`public/`](public) | travel and portfolio theme treatments |
| [`journal/`](journal) | journal theme (home, permalink, page) and the front door |

Every card's first line is a `@dsCard` marker naming its group, name and
subtitle. `DesignCardsTest` enforces that marker, and that this directory and
the spec's card list agree — so adding a card without listing it, or listing
one that does not exist, fails the build rather than rotting quietly.

The companion claude.ai/design project "Pressroom Design System" renders these
same files and is a convenient place to iterate a card visually. It is a
mirror, not the origin: a change made there is not done until it lands here.
