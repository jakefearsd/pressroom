# Media pipeline

Developer notes moved out of `CLAUDE.md` (which is sent with every
request) so they are read only when this area is being changed. Section
names are unchanged, so an older "see CLAUDE.md, <section>" pointer
resolves through the index at the end of `CLAUDE.md`.

## Media Pipeline (Stage 2 Wave 1)
- Rendition ladder (480/960/1600/2400px, JPEG/PNG only, never upscaled) via
  `RenditionSupport`; WebP siblings when `cwebp` is present (feature-detected
  `CwebpEncoder`; the prod Docker image installs it, dev works without).
  `MediaResourceServlet` serves them via `?w=<width>` + `Accept: image/webp`
  negotiation. Renditions are excluded from upload quotas.
- Upload extracts EXIF (`ExifSupport`) and a BlurHash placeholder onto
  `MediaFile`; `uploads.exif.stripGps` (default on) nulls GPS coordinates
  before persist. The original file on disk is never modified.
- Backfill for pre-pipeline uploads: Maintenance page →
  `MediaFileManager.regenerateRenditions(weblog)`.
- Crop (Stage 2 Wave 2): `MediaFileManager.cropMediaFile` destructively
  re-encodes the original (orientation composed first, atomic temp+move
  write) and regenerates the whole ladder + thumbnail + blurhash; stored
  EXIF fields are kept. Focal point (`MediaFile.focalX/Y`,
  set on MediaFileEdit) emits `object-position` via `#showResponsiveImage`
  only — never into entry content.
- Private directories (`MediaFileDirectory.isPrivate()`, toggled on
  MediaFileView): 404 on the base media path except for logged-in editors of
  the owning weblog (a permission on it **and** the global `login` action:
  a reader demoted from "Can write" keeps its weblog membership but holds no
  `login`), excluded from sitemaps, refused by `[gallery]`. The toggle
  saves the weblog, bumping `lastModified`: that, not
  `CacheManager.invalidate`, is what expires cached galleries and the
  cached sitemap. A pure
  visibility flag with no bypass of any kind. The share-link feature that
  once punched a tokened hole through it (`ShareController`,
  `roller_share_link`) is gone and not coming back.
- **Alt text (W4): `MediaFile.altText`.** Before W4 every alt fell back to
  `MediaFile.getName()`, the filename. The chain:
  - `ImageShortcode`: an `alt` attribute **present** on the shortcode wins
    verbatim, *including empty* (`[image id=".." alt=""]` means decorative).
    Only an **absent** attribute falls to `altText`, then the filename.
  - `GalleryMarkup`: `altText` → filename (no per-image override attribute).
  - At the `altText` link, **blank counts as absent** at both sites — an
    author who clears the edit field did not declare the image decorative,
    and the field cannot express that. Deliberately unlike the
    shortcode-attribute link.
  - `firstNonBlank` returns `""`, never null (null once rendered a literal
    `alt="null"` in `GalleryMarkup`).
  - **The filename stays the last fallback rather than `alt=""`**: empty alt
    asserts "decorative", wrong for a photograph, and would hide undescribed
    images from the marker.
- **`MediaFileView.jsp` renders the "no alt text" marker from TWO `c:forEach`
  loops**: `childFiles` (primary — the unpaged folder-browse view an author
  sees on every visit) and `pager.items` (`MediaFileViewController` sets
  `pager` only in `mediaFileView!search.rol`, so only after a search). Its
  gate uses `fn:trim` so whitespace-only alt text counts as missing,
  matching the renderer's `isNotBlank`, not EL's `empty`.
- **Alt text is deliberately absent from the upload form**: it is per-image;
  one shared box across a thirty-file batch writes thirty wrong descriptions
  the marker then reports as done.
- **`#showResponsiveImage`'s `$alt` stays caller-supplied.** Theme callers
  pass `$entry.title` for a featured image — right in a card context, not
  the file's own description. Do not "unify" it.
- **Bulk upload (W4) was a form change only.** `MediaFileAddController.save`
  always bound `MultipartFile[]` and looped; the five-file ceiling was five
  `<input type="file">` elements. Now one `multiple` input plus a drop zone;
  `spring.servlet.multipart.max-request-size` is 1GB. The add form's inert
  Name field went too (overwritten by the uploaded filename right after
  `bean.copyTo`).
  **A batch is not a transaction**: `createMediaFile` reports quota and
  forbidden-extension refusals per file *without throwing*, so the
  controller snapshots `RollerMessages.getErrorCount()` around each call,
  and a partly-failed batch shows both what landed and what did not.
- **`MediaFile.sharedForGallery` / `roller_mediafile.is_public` are gone** (`V024`).
- `EntryAddWithMediaFileController` (entry from selected files) seeds the
  draft with `[image id=".."]`, not hand-built `<img>` markup, so alt text
  and the rendition ladder arrive through the normal path.
- **`MediaFile.contentHash` (V032)** is SHA-256 lower-case hex, set from the bytes as they stream
  to disk in `persistNewMediaFile` (a `DigestInputStream`, so no upload is held in memory). It is
  cleared by `updateMediaFile(…, InputStream)` and `cropMediaFile`, since the file is no longer the
  bytes it was imported as. `MediaImports` matches per directory by it, and a file without one
  (from before V032) is simply not matched. There is no unique constraint, so two concurrent
  identical imports can both store.
