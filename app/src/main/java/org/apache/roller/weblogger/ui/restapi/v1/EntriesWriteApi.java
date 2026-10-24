package org.apache.roller.weblogger.ui.restapi.v1;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import java.time.Clock;
import java.sql.Timestamp;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.apache.roller.weblogger.WebloggerException;
import org.apache.roller.weblogger.business.AnchorTakenException;
import org.apache.roller.weblogger.business.fetch.RemoteImageFetcher;
import org.apache.roller.weblogger.pojos.MediaFile;
import org.apache.roller.weblogger.pojos.User;
import org.apache.roller.weblogger.pojos.Weblog;
import org.apache.roller.weblogger.pojos.WeblogCategory;
import org.apache.roller.weblogger.pojos.WeblogEntry;
import org.apache.roller.weblogger.pojos.WeblogPermission;
import org.apache.roller.weblogger.ui.controllers.EntryDeletion;
import org.apache.roller.weblogger.ui.controllers.EntryFieldRules;
import org.apache.roller.weblogger.ui.controllers.WeblogOwnership;
import org.apache.roller.weblogger.ui.controllers.UISecurityEnforced;
import org.apache.roller.weblogger.ui.restapi.ApiException;
import org.apache.roller.weblogger.ui.restapi.FieldErrors;
import org.apache.roller.weblogger.ui.restapi.dto.ClearFields;
import org.apache.roller.weblogger.ui.restapi.ColumnLimits;
import org.apache.roller.weblogger.ui.restapi.dto.EntryDtos;
import org.apache.roller.weblogger.util.Utilities;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

/**
 * Entry create and patch. A separate {@code @RestController} from {@link
 * EntriesApi} on the same {@code /v1/weblogs/{handle}/entries} path --
 * {@code UISecurityEnforced} declares its required {@code WeblogPermission}
 * per controller, not per method, and reads (EDIT_DRAFT) and writes (POST)
 * need different ones. Spring MVC resolves by method plus path, so GET
 * reaches {@link EntriesApi} and POST/PATCH reach this controller with no
 * ambiguity; {@code RollerHandlerInterceptor} is what actually enforces the
 * declared permission, so this controller adds no permission checking of
 * its own.
 *
 * <p>The routes that return an entry take {@code fields} ({@link
 * EntryFields}), so their Java return type is {@code Object}; each one's
 * {@code @ApiResponse} keeps openapi.json naming the view a caller gets
 * without it.
 */
@RestController
@RequestMapping("/v1/weblogs/{handle}/entries")
public class EntriesWriteApi extends BaseApiController implements UISecurityEnforced {

    @Autowired
    RemoteImageFetcher imageFetcher;

    /** An entry's contentHash: SHA-256 as hex digits, either case accepted. */
    private static final Pattern CONTENT_HASH = Pattern.compile("[0-9a-fA-F]{64}");

    /** The import step's budget is measured on this; a test replaces it. */
    Clock clock = Clock.systemUTC();

    @PostMapping("")
    @ApiResponse(responseCode = "201", content = @Content(schema = @Schema(implementation = EntryDtos.EntryView.class)))
    public ResponseEntity<Object> create(
            HttpServletRequest request, @RequestBody EntryDtos.EntryWrite body,
            @Parameter(description = EntryFields.DESCRIPTION)
            @RequestParam(value = "fields", required = false) List<String> fields) throws WebloggerException {
        Weblog weblog = requireActionWeblog(request);
        User user = requireAuthenticatedUser(request);

        if (body.clear() != null && !body.clear().isEmpty()) {
            throw ApiException.invalidField("clear", "clear applies only to updates.");
        }
        WeblogEntry entry = new WeblogEntry();
        entry.setWebsite(weblog);
        entry.setCreatorUserName(user.getUserName());

        FieldErrors errors = new FieldErrors();
        EntryFields shown = EntryFields.of(fields, errors);
        errors.check("title", () -> requireNotBlank("title", body.title()));
        errors.check("text", () -> requireNotBlank("text", body.text()));
        refuseUpdateOnlyFields(errors, body);
        if (body.anchor() != null) {
            errors.check("anchor", () -> requireAnchorForm(body.anchor()));
        }
        checkWrite(errors, weblog, entry, body, Set.of());
        EntryImports imports = new EntryImports(weblogger, imageFetcher, clock);
        EntryImports.Plan importPlan = imports.plan(errors, weblog, body, Set.of(), null);
        errors.throwIfAny();

        if (body.anchor() != null) {
            requireAnchorFree(weblog, body.anchor());
        }
        EntryDtos.EntryWrite effective = imports.run(importPlan, weblog, body);
        if (body.anchor() != null) {
            // Again, immediately before the save: the import step can take a
            // minute, and a client that timed out may have retried meanwhile.
            requireAnchorFree(weblog, body.anchor());
            // saveWeblogEntry generates an anchor only when none is set
            entry.setAnchor(body.anchor());
        }
        apply(entry, weblog, effective, Set.of());
        // No explicit category fallback needed here:
        // JPAWeblogEntryManagerImpl.saveWeblogEntry already defaults a
        // categoryless entry to the weblog's first category -- the same
        // fallback CategoriesApi.delete relies on to guarantee at least one
        // category always exists.
        try {
            // Those checks are unlocked, so two creates choosing one anchor
            // can both pass them. The save checks again under the weblog's
            // anchor lock, held until the commit below, and refuses the loser.
            weblogger.getWeblogEntryManager().saveWeblogEntry(entry);
        } catch (AnchorTakenException taken) {
            throw anchorConflict(taken.getAnchor(), taken.getHolderStatus(), taken);
        }
        weblogger.flush();
        if (entry.isPublished()) {
            weblogger.getIndexManager().addEntryReIndexOperation(entry);
        }

        URI location = ServletUriComponentsBuilder.fromRequestUri(request)
                .path("/{id}")
                .buildAndExpand(entry.getId())
                .toUri();
        return ResponseEntity.created(location).body(shown.project(EntryDtos.toView(entry, permalink(entry))));
    }

    @PatchMapping("/{id}")
    @ApiResponse(responseCode = "200", content = @Content(schema = @Schema(implementation = EntryDtos.EntryView.class)))
    public Object update(
            HttpServletRequest request, @PathVariable("id") String id,
            @RequestBody EntryDtos.EntryWrite sent,
            @Parameter(description = EntryFields.DESCRIPTION)
            @RequestParam(value = "fields", required = false) List<String> fields) throws WebloggerException {
        Weblog weblog = requireActionWeblog(request);
        // requireLiveEntry, not requireEntry: a trashed entry's id here
        // would otherwise reach saveWeblogEntry directly, resurrecting it
        // (to DRAFT or straight to PUBLISHED) by a side door that bypasses
        // restore's DRAFT-only rule entirely. See requireLiveEntry's javadoc.
        WeblogEntry entry = requireLiveEntry(request, id);
        // Before every other check, and so before the import step can fetch:
        // a caller whose view of the text is stale learns that first.
        requireUnchanged(entry, sent.expectedContentHash());

        FieldErrors errors = new FieldErrors();
        EntryFields shown = EntryFields.of(fields, errors);
        // From here on edits are text: what they make of the stored text is
        // checked, imported and saved exactly as though it had been sent.
        EntryDtos.EntryWrite body = EntryEdits.sent(sent.edits())
                ? sent.withText(EntryEdits.resolve(errors, sent.text(), sent.edits(), entry.getText()))
                : sent;
        Set<String> cleared = new HashSet<>();
        errors.check("clear", () -> cleared.addAll(ClearFields.validate(body.clear(), body,
                EntryDtos.ENTRY_CLEARABLE, EntryDtos.ENTRY_PAIRS)));
        if (body.anchor() != null && !body.anchor().equals(entry.getAnchor())) {
            errors.add("anchor", "anchor is set when an entry is created and cannot change.");
        }
        checkWrite(errors, weblog, entry, body, cleared);
        EntryImports imports = new EntryImports(weblogger, imageFetcher, clock);
        EntryImports.Plan importPlan = imports.plan(errors, weblog, body, cleared, entry.getText());
        errors.throwIfAny();

        // The import step commits each image it stores; nothing has been
        // applied to the managed entry yet, so nothing of the write goes with it.
        apply(entry, weblog, imports.run(importPlan, weblog, body), cleared);
        // The token's user made this save, so a revision it leaves names them.
        weblogger.getWeblogEntryManager().saveWeblogEntry(entry, requireAuthenticatedUser(request));
        weblogger.flush();
        // As EntryEditController's save: an entry that is no longer published
        // leaves search, or search keeps offering a permalink that 404s.
        if (entry.isPublished()) {
            weblogger.getIndexManager().addEntryReIndexOperation(entry);
        } else {
            weblogger.getIndexManager().removeEntryIndexOperation(entry);
        }

        return shown.project(EntryDtos.toView(entry, permalink(entry)));
    }

    /**
     * Trash, not delete. Goes through the same seam the authoring UI uses --
     * {@link EntryDeletion#trashEntryWithIndex} -- because the index removal
     * and the {@code weblog.lastModified} bump are not optional now the row
     * survives: a TRASHED entry left in Lucene is findable by site search
     * and links to a permalink that 404s, and {@code WeblogPageCache} has no
     * CacheHandler so {@code lastModified} is the only thing that expires
     * the cached home page (see CLAUDE.md's Trash section). Both of those
     * steps live in {@code WeblogEntryManager.trashWeblogEntry} and {@code
     * EntryDeletion}, never reimplemented here.
     *
     * <p>Trashing an already-trashed entry is refused with 409 rather than
     * silently re-running the trash dance -- {@code trashWeblogEntry} has no
     * such guard of its own (it would just re-save the same TRASHED status),
     * but a caller asking to trash something already trashed almost always
     * means the caller's view of the entry is stale.
     */
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> trash(HttpServletRequest request,
                                      @PathVariable("id") String id) throws WebloggerException {
        WeblogEntry entry = requireEntry(request, id);
        if (entry.getStatus() == WeblogEntry.PubStatus.TRASHED) {
            throw ApiException.conflict("Entry is already trashed.");
        }
        EntryDeletion.trashEntryWithIndex(weblogger, entry);
        weblogger.flush();
        return ResponseEntity.noContent().build();
    }

    /**
     * Brings a trashed entry back -- always as a DRAFT, never back to
     * PUBLISHED. {@code WeblogEntryManager.restoreWeblogEntry} enforces that
     * itself and remembers no pre-trash status to restore instead, precisely
     * so an undelete can never silently republish to feeds, the sitemap and
     * every subscriber.
     *
     * <p>Restoring an entry that is not currently trashed is refused with
     * 409: {@code restoreWeblogEntry} has no status guard of its own, and
     * calling it on a live PUBLISHED entry would silently unpublish it to
     * DRAFT -- exactly the side-door resurrection/demotion hazard {@code
     * TrashController.trashedEntry} exists to close off on the JSP side.
     */
    @PostMapping("/{id}/restore")
    @ApiResponse(responseCode = "200", content = @Content(schema = @Schema(implementation = EntryDtos.EntryView.class)))
    public Object restore(HttpServletRequest request,
                          @PathVariable("id") String id,
                          @Parameter(description = EntryFields.DESCRIPTION)
                          @RequestParam(value = "fields", required = false) List<String> fields)
            throws WebloggerException {
        EntryFields shown = EntryFields.of(fields);
        WeblogEntry entry = requireEntry(request, id);
        if (entry.getStatus() != WeblogEntry.PubStatus.TRASHED) {
            throw ApiException.conflict("Entry is not trashed.");
        }
        weblogger.getWeblogEntryManager().restoreWeblogEntry(entry);
        weblogger.flush();
        return shown.project(EntryDtos.toView(entry, permalink(entry)));
    }

    /**
     * Permanently deletes an already-trashed entry. Only reachable from the
     * trash, the same way the JSP {@code trash!delete.rol} row action is --
     * deleting forever is a second, deliberate step after trashing, never a
     * shortcut around it, so a live entry's id here is a 409 rather than an
     * undocumented hard-delete endpoint.
     */
    @PostMapping("/{id}/delete-forever")
    public ResponseEntity<Void> deleteForever(HttpServletRequest request,
                                              @PathVariable("id") String id) throws WebloggerException {
        WeblogEntry entry = requireEntry(request, id);
        if (entry.getStatus() != WeblogEntry.PubStatus.TRASHED) {
            throw ApiException.conflict("Entry is not trashed; trash it first.");
        }
        EntryDeletion.deleteEntryForeverWithIndex(weblogger, entry);
        weblogger.flush();
        return ResponseEntity.noContent().build();
    }

    /**
     * An update's {@code expectedContentHash}, when sent, must be the hash of
     * the text now stored (spec 2026-10-24, section 4): otherwise someone
     * changed the entry after the caller read it, and saving would overwrite
     * that change unseen. Either case of hex matches. A value that is not a
     * hash at all is the caller's mistake, not a sign the entry changed, so it
     * is a 400 rather than the 409.
     */
    @SuppressFBWarnings(
            value = "IMPROPER_UNICODE",
            justification = "Compares case-insensitively only after CONTENT_HASH has confined the value to "
                    + "ASCII hex digits, which no case mapping can turn into anything else.")
    private static void requireUnchanged(WeblogEntry entry, String expected) {
        if (expected == null) {
            return;
        }
        if (!CONTENT_HASH.matcher(expected).matches()) {
            throw ApiException.invalidField("expectedContentHash",
                    "expectedContentHash must be the contentHash an entry is read with: 64 hex digits.");
        }
        String current = EntryDtos.contentHash(entry.getText());
        if (!expected.equalsIgnoreCase(current)) {
            throw ApiException.entryChanged(current);
        }
    }

    /** {@code edits} and {@code expectedContentHash} act on stored text, which a create has none of. */
    private static void refuseUpdateOnlyFields(FieldErrors errors, EntryDtos.EntryWrite body) {
        if (EntryEdits.sent(body.edits())) {
            errors.add("edits", "edits apply only to updates; a create sends text.");
        }
        if (body.expectedContentHash() != null) {
            errors.add("expectedContentHash", "expectedContentHash applies only to updates.");
        }
    }

    /**
     * Previews an existing entry's unsaved text. Reuses {@code
     * EntryEditController.entryEditPreview}'s scratch-entry approach: the
     * entry itself comes from the usual ownership-checked lookup (refusing a
     * TRASHED entry the same way {@code update} does -- previewing "as
     * though live" a trashed entry is the same side door, just read-only
     * instead of a resurrection), but its text is replaced with whatever the
     * request carries and never saved. Only the server can expand
     * shortcodes -- {@code [gallery]}, {@code [map]} -- so this is the only
     * way an agent can see what it is about to publish before actually
     * publishing it. With {@code edits} in place of {@code text}, it renders
     * what an update with those edits would store.
     */
    @PostMapping("/{id}/preview")
    public EntryDtos.PreviewView previewExisting(
            HttpServletRequest request, @PathVariable("id") String id,
            @RequestBody EntryDtos.PreviewRequest body) throws WebloggerException {
        WeblogEntry entry = requireLiveEntry(request, id);
        FieldErrors errors = new FieldErrors();
        String text = EntryEdits.resolve(errors, body.text(), body.edits(), entry.getText());
        errors.throwIfAny();
        return renderPreview(entry, text);
    }

    /**
     * Previews text for an entry that does not exist yet -- a brand-new,
     * unsaved {@code WeblogEntry} owned by the action weblog, so its
     * shortcodes resolve that weblog's own media and nothing is persisted.
     * Same scratch-entry approach {@code EntryEditController} uses when its
     * preview call carries no {@code id}. {@code edits} need a stored text to
     * apply to, so here they are a 400.
     */
    @PostMapping("/preview")
    public EntryDtos.PreviewView previewNew(
            HttpServletRequest request,
            @RequestBody EntryDtos.PreviewRequest body) throws WebloggerException {
        if (EntryEdits.sent(body.edits())) {
            throw ApiException.invalidField("edits", "edits change an existing entry's text; preview with its id, "
                    + "or send text.");
        }
        Weblog weblog = requireActionWeblog(request);
        User user = requireAuthenticatedUser(request);
        WeblogEntry entry = new WeblogEntry();
        entry.setWebsite(weblog);
        entry.setCreatorUserName(user.getUserName());
        return renderPreview(entry, body.text());
    }

    /**
     * Sets the scratch entry's text and renders it through the real
     * pipeline -- shortcode expansion, then Markdown, then sanitization --
     * exactly as {@code EntryRenderer.transformedText()} does for a
     * published entry, so a preview cannot disagree with what gets
     * published. A missing or blank {@code text} renders an empty draft
     * rather than failing: previewing a blank editor is a normal moment, not
     * an error. (A missing body never gets here: {@code ApiExceptionHandler}
     * answers it with a 400.)
     */
    private EntryDtos.PreviewView renderPreview(WeblogEntry entry, String text) {
        // Mutates a JPA-managed entity (when entry came from requireLiveEntry
        // rather than the previewNew scratch path) without saving. Safe only
        // because nothing on this request path calls weblogger.flush() or
        // saveWeblogEntry -- a future change adding either here would
        // silently persist preview text over the stored entry.
        entry.setText(text == null ? "" : text);
        String html = weblogger.getEntryRenderer().transformedText(entry);
        return new EntryDtos.PreviewView(html, PreviewLint.warnings(html));
    }

    /**
     * "Publish this now" -- an entry going to PUBLISHED with no pubTime
     * supplied -- must mean now, not a null that reaches
     * {@code JPAWeblogEntryManagerImpl.saveWeblogEntry}'s unconditional
     * {@code entry.getPubTime().after(...)} and NPEs. The JSP editor already
     * carries this exact guard, unconditionally, immediately before every
     * save ({@code EntryEditController.doSave}); this ports it rather than
     * inventing a new rule. Applied to both create and update, after
     * {@code applyWrite} has had its chance to set an explicit pubTime and
     * before the manager is ever called.
     */
    private void applyPublishNowDefault(WeblogEntry entry) {
        if (entry.isPublished() && entry.getPubTime() == null) {
            entry.setPubTime(new Timestamp(System.currentTimeMillis()));
        }
    }

    /**
     * The sibling of {@link #applyPublishNowDefault}: PUBLISHED with no
     * pubTime means "now" and gets one, but SCHEDULED with no pubTime has no
     * sensible default -- "schedule this for right now" is not what a caller
     * asking for SCHEDULED meant. Left alone, the entry would save with a
     * NULL pubtime and {@code ScheduledEntriesTask} (which promotes a
     * SCHEDULED entry once {@code pubtime <= now}) can never satisfy that
     * comparison against NULL, so the entry would stay SCHEDULED forever --
     * stuck, and invisible to every listing that does not explicitly ask for
     * that status. Checked after {@code applyWrite} has had its chance to set
     * an explicit pubTime, so an update that leaves a previously-set pubTime
     * untouched is never refused.
     */
    private void requireScheduledHasPubTime(WeblogEntry entry) {
        if (entry.getStatus() == WeblogEntry.PubStatus.SCHEDULED && entry.getPubTime() == null) {
            throw ApiException.invalidField("pubTime", "pubTime is required when status is SCHEDULED.");
        }
    }

    /**
     * Every field check of a write, before anything is applied. The checks
     * run against a scratch copy of {@code entry} ({@link EntryDtos#scratchOf}),
     * so a refused write leaves the real entry exactly as it was, and the
     * import step that runs between checking and applying (it commits) can
     * never commit half a write. Problems land in {@code errors}.
     */
    private void checkWrite(FieldErrors errors, Weblog weblog, WeblogEntry entry,
                            EntryDtos.EntryWrite body, Set<String> cleared) throws WebloggerException {
        WeblogEntry scratch = EntryDtos.scratchOf(entry);
        errors.check(() -> EntryDtos.applyWrite(scratch, body, weblog, cleared));
        errors.check("category", () -> categoryFor(weblog, body.category()));
        errors.check("tags", () -> checkTags(body.tags()));
        applyPublishNowDefault(scratch);
        // A pubTime in the body either parsed or has its own error already.
        if (body.pubTime() == null) {
            errors.check("pubTime", () -> requireScheduledHasPubTime(scratch));
        }
        checkImageId(errors, weblog, "featuredImageId", body.featuredImageId(), entry.getFeaturedImageId());
        checkImageId(errors, weblog, "ogImageId", body.ogImageId(), entry.getOgImageId());
    }

    /**
     * featuredImageId and ogImageId must name an image readers can load: a
     * media file of this weblog, an image, outside every private directory.
     * They were only length-checked, so a typo or a foreign id was stored and
     * the post silently had no featured image. A value equal to the stored
     * one is not re-checked, so an entry sent back exactly as it was read
     * never fails on a file deleted since.
     */
    private void checkImageId(FieldErrors errors, Weblog weblog, String field, String id, String stored) {
        if (id == null || id.equals(stored)) {
            return;
        }
        MediaFile file = WeblogOwnership.mediaFile(weblogger, id, weblog);
        boolean hidden = file != null && file.getDirectory() != null && file.getDirectory().isPrivate();
        if (file == null || !file.isImageFile() || hidden) {
            errors.add(field, field + " must be the id of an image in this weblog's media, "
                    + "outside any private directory.");
        }
    }

    /**
     * Applies a write {@link #checkWrite} passed. Each step repeats a check
     * that already passed on identical input, so none of them refuses here.
     */
    private void apply(WeblogEntry entry, Weblog weblog, EntryDtos.EntryWrite body, Set<String> cleared)
            throws WebloggerException {
        EntryDtos.applyWrite(entry, body, weblog, cleared);
        WeblogCategory category = categoryFor(weblog, body.category());
        if (category != null) {
            entry.setCategory(category);
        }
        applyTags(entry, body.tags());
        applyPublishNowDefault(entry);
        requireScheduledHasPubTime(entry);
    }

    /**
     * {@code title} and {@code text} are NOT NULL columns
     * (V002__baseline_schema.sql) and a brand-new WeblogEntry defaults both
     * to null, so an omitted one used to reach weblogger.flush() and die on
     * the constraint -- an opaque 500 from the generic handler. Checked only
     * on create: an existing entry already satisfies the constraint, and a
     * PATCH omitting either must leave the stored value alone per
     * applyWrite's "null means absent" contract, not suddenly demand one.
     */
    private static void requireNotBlank(String field, String value) {
        if (value == null || value.isBlank()) {
            throw ApiException.invalidField(field, field + " is required.");
        }
    }

    private static void requireAnchorForm(String anchor) {
        if (!EntryFieldRules.isValidAnchor(anchor)) {
            throw ApiException.invalidField("anchor", "anchor must be lower-case letters and digits in words "
                    + "joined by one '-' or '_', at most " + EntryFieldRules.ANCHOR_MAX_LENGTH
                    + " characters, for example torrey-pines.");
        }
    }

    /**
     * A chosen anchor no entry of this weblog holds. A trashed entry still
     * holds its anchor -- it can be restored to the same permalink -- so the
     * message says how to free it. Not a field-format problem but a clash
     * with stored state, hence 409, reported only once every other field
     * passed: there is no point telling a caller about a clash in a write
     * that would be refused anyway.
     */
    private void requireAnchorFree(Weblog weblog, String anchor) throws WebloggerException {
        WeblogEntry holder = weblogger.getWeblogEntryManager().getWeblogEntryByAnchor(weblog, anchor);
        if (holder == null) {
            return;
        }
        throw anchorConflict(anchor, holder.getStatus(), null);
    }

    private static ApiException anchorConflict(String anchor, WeblogEntry.PubStatus holderStatus,
                                               Throwable cause) {
        return ApiException.conflictOnField("anchor", holderStatus == WeblogEntry.PubStatus.TRASHED
                ? "anchor '" + anchor + "' is held by an entry in the trash; delete it forever (delete_forever) to free it."
                : "anchor '" + anchor + "' is already used by another entry.", cause);
    }

    /**
     * The category named by {@code categoryName}, or null when the write
     * names none (absent leaves the entry's category untouched, per
     * {@code applyWrite}'s "null means absent" contract). {@code category}
     * names a category by NAME, matching {@code EntryDtos.EntryView.category}.
     * {@code getWeblogCategoryByName} is scoped to {@code weblog} in the
     * query itself, so an unknown name is simply absent -- there is no
     * separate ownership check to bypass. An unknown name is answered with
     * the weblog's names, so a caller can correct itself in one round trip
     * instead of listing categories before every write.
     */
    private WeblogCategory categoryFor(Weblog weblog, String categoryName) throws WebloggerException {
        if (categoryName == null) {
            return null;
        }
        String trimmed = categoryName.trim();
        if (trimmed.isBlank()) {
            throw ApiException.invalidField("category", "category cannot be blank.");
        }
        WeblogCategory category = weblogger.getWeblogEntryManager().getWeblogCategoryByName(weblog, trimmed);
        if (category == null) {
            String names = weblogger.getWeblogEntryManager().getWeblogCategories(weblog).stream()
                    .map(WeblogCategory::getName)
                    .collect(Collectors.joining(", "));
            throw ApiException.invalidField("category",
                    "Unknown category '" + categoryName + "'; it is one of: " + names + ".");
        }
        return category;
    }

    /**
     * The length guard is checked AFTER splitting the joined string on
     * {@link Utilities#TAG_SPLIT_CHARS} -- the same split {@code
     * WeblogEntry.setTagsAsString} performs internally -- rather than per
     * list element. A single list element can itself carry several
     * space/comma-separated tags whose COMBINED length exceeds the column
     * even though every tag actually stored (one row per split token) is
     * short; checking before the split measured the wrong thing and
     * over-rejected that case.
     */
    private static void checkTags(List<String> tags) {
        if (tags == null) {
            return;
        }
        for (String tag : Utilities.splitStringAsTags(String.join(" ", tags))) {
            ColumnLimits.requireMaxLength("tags", tag, ColumnLimits.TAG_NAME);
        }
    }

    /**
     * Tags go through the entry's own comma/space-tolerant tag-setting path
     * rather than being reimplemented here. Null (absent) leaves the
     * stored tags untouched; a non-null EMPTY list is a real instruction to
     * clear every tag, distinguishable from absence exactly as {@code
     * applyWrite} distinguishes them for every other field.
     */
    private void applyTags(WeblogEntry entry, List<String> tags) throws WebloggerException {
        if (tags != null) {
            entry.setTagsAsString(String.join(" ", tags));
        }
    }

    private String permalink(WeblogEntry entry) {
        return weblogger.getUrlStrategy()
                .getWeblogEntryURL(entry.getWebsite(), null, entry.getAnchor(), true);
    }

    @Override
    public boolean isUserRequired() {
        return true;
    }

    @Override
    public boolean isWeblogRequired() {
        return true;
    }

    @Override
    public List<String> requiredWeblogPermissionActions() {
        return List.of(WeblogPermission.POST);
    }

    @Override
    public List<String> requiredGlobalPermissionActions() {
        return List.of();
    }
}
