# Pictures by default (0.8.7)

2026-11-07. A light design: four changes to how a post gets its picture, and
the blog pages catching up with the front page.

## Why

Eight posts on production had pictures in their text and no featured image.
The front page, every entry list, `og:image` and the travel/portfolio heroes
show only a featured image, so those posts had a coloured placeholder tile
everywhere. The pictures were also hot-linked from raw.githubusercontent.com,
which can rate-limit, move or go private.

Blogger never had this problem: it has no featured-image field, and the first
picture in a post is its thumbnail. Pressroom keeps its explicit field (it is
what heroes, `og:image` and JSON-LD read) and gains Blogger's default.

Separately, the journal theme's entry list (Simple Investing, Market Updates)
is text only: no picture, no reading time, nothing the front page offers.

## Changes

1. **The first picture is the featured image by default.** A save through the
   API, MCP or the editor that leaves an entry with no featured image sets it
   to the first picture in the text that is a public image in this weblog's
   media library. A picture is an `[image id=...]` shortcode, an `<img src>` or
   a Markdown image whose URL is this weblog's `.../mediaresource/<id>`; never
   one inside code. A featured image that is set explicitly wins. On the API, a
   write that clears `featuredImageId` (`clear`) is left with none.
2. **Outside images are copied in by default.** An API or MCP write that sends
   `text` (or `edits`) and omits `importExternalImages` imports as though it
   were `true`. `false` opts out. A write that sends neither text nor edits
   does not import unless asked (a status change must not start fetching).
   A defaulted import is best effort: an image that cannot be copied (refused,
   failed, out of budget, an external `srcset`, past the first 10) stays
   linked as it was and the write saves, as do a site with uploads off and a
   private default media directory. A default must never make a write fail
   that would have saved without it: the publishing agent's posts embed
   Google static maps and badges whose hosts may refuse
   (`PressroomFrictionPoints.md`, section 5). Asked for with `true`, it stays
   all or nothing, 400s included.
3. **A hero never repeats the body.** Travel and portfolio print the featured
   image above the post only when the text does not already show it (by the
   same picture test as change 1). The `featured_image_repeated` finding is
   retired: the situation it warned about no longer renders twice.
4. **The missing-featured-image warning** (text shows a picture, no featured
   image) remains for what changes 1–2 cannot fix (an image the write opted
   out of importing), and no longer tells the author to take the picture out
   of the text.
5. **Blog pages catch up.** The journal entry list shows each post's featured
   image as a thumbnail beside its row. Every theme's entry list (journal
   rows, travel cards, portfolio cards) shows the reading time the front page
   shows ("5 min read").

## Acceptance criteria

1. A first picture: `LeadImages.mediaIds(text)` lists, in text order and each
   once, the media ids the text shows as pictures: `[image id="a"]`,
   `<img src=".../mediaresource/b?w=480">`, `![x](.../mediaresource/c)`. A
   picture in a code span or fence, a link to a media URL and an external
   image are not listed.
2. An API create with text whose first picture is this weblog's public image
   `m1`, and no featured image, stores `featuredImageId = m1`.
3. The same create with `featuredImageId = m2` stores `m2`.
4. An API update sending `clear: ["featuredImageId"]` stores none, though the
   text shows a picture.
5. A first picture from another weblog, or in a private directory, is skipped
   for the next one; with none usable, no featured image is set.
6. An editor save with no featured image picked and the text showing `m1`
   stores `m1`.
7. An API create with an external image in the text and no
   `importExternalImages` imports it and points the text at the copy; with the
   copy first, the featured image is that copy.
8. The same with `importExternalImages: false` leaves the text as sent.
9. An update sending only `status` scans and imports nothing.
10. Defaulted, uploads disabled: the write saves, text unchanged. Explicit
    `true`, uploads disabled: 400 on `importExternalImages` (unchanged).
11. Defaulted, an image that fails stays linked, the others are copied, the
    write saves (201); an external `srcset` or more than 10 images is no
    refusal, and only the first 10 are fetched.
12. Travel and portfolio permalinks: featured image `m1` and text showing `m1`
    renders no hero; text not showing it renders the hero.
13. `featured_image_repeated` is no longer reported.
14. The journal entry list renders a post's featured image (responsive
    `<img>`) in its row; a post without one renders no image element.
15. Journal, travel and portfolio entry lists show "N min read" per post.
