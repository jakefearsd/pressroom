package org.apache.roller.weblogger.ui.restapi.v1;

import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import java.sql.Timestamp;
import java.util.List;
import org.apache.roller.weblogger.WebloggerException;
import org.apache.roller.weblogger.pojos.User;
import org.apache.roller.weblogger.pojos.Weblog;
import org.apache.roller.weblogger.pojos.WeblogCategory;
import org.apache.roller.weblogger.pojos.WeblogEntry;
import org.apache.roller.weblogger.pojos.WeblogPermission;
import org.apache.roller.weblogger.ui.controllers.EntryDeletion;
import org.apache.roller.weblogger.ui.controllers.UISecurityEnforced;
import org.apache.roller.weblogger.ui.restapi.ApiException;
import org.apache.roller.weblogger.ui.restapi.ColumnLimits;
import org.apache.roller.weblogger.ui.restapi.dto.EntryDtos;
import org.apache.roller.weblogger.util.Utilities;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
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
 */
@RestController
@RequestMapping("/v1/weblogs/{handle}/entries")
public class EntriesWriteApi extends BaseApiController implements UISecurityEnforced {

    @PostMapping("")
    public ResponseEntity<EntryDtos.EntryView> create(
            HttpServletRequest request, @RequestBody EntryDtos.EntryWrite body) throws WebloggerException {
        Weblog weblog = requireActionWeblog(request);
        User user = requireAuthenticatedUser(request);

        requireTitleAndText(body);

        WeblogEntry entry = new WeblogEntry();
        entry.setWebsite(weblog);
        entry.setCreatorUserName(user.getUserName());

        EntryDtos.applyWrite(entry, body, weblog);
        applyCategory(entry, weblog, body.category());
        applyTags(entry, body.tags());
        applyPublishNowDefault(entry);
        requireScheduledHasPubTime(entry);

        // No explicit category fallback needed here:
        // JPAWeblogEntryManagerImpl.saveWeblogEntry already defaults a
        // categoryless entry to the weblog's first category -- the same
        // fallback CategoriesApi.delete relies on to guarantee at least one
        // category always exists.
        weblogger.getWeblogEntryManager().saveWeblogEntry(entry);
        weblogger.flush();

        URI location = ServletUriComponentsBuilder.fromRequestUri(request)
                .path("/{id}")
                .buildAndExpand(entry.getId())
                .toUri();
        return ResponseEntity.created(location).body(EntryDtos.toView(entry, permalink(entry)));
    }

    @PatchMapping("/{id}")
    public EntryDtos.EntryView update(
            HttpServletRequest request, @PathVariable("id") String id,
            @RequestBody EntryDtos.EntryWrite body) throws WebloggerException {
        Weblog weblog = requireActionWeblog(request);
        // requireLiveEntry, not requireEntry: a trashed entry's id here
        // would otherwise reach saveWeblogEntry directly, resurrecting it
        // (to DRAFT or straight to PUBLISHED) by a side door that bypasses
        // restore's DRAFT-only rule entirely. See requireLiveEntry's javadoc.
        WeblogEntry entry = requireLiveEntry(request, id);

        EntryDtos.applyWrite(entry, body, weblog);
        applyCategory(entry, weblog, body.category());
        applyTags(entry, body.tags());
        applyPublishNowDefault(entry);
        requireScheduledHasPubTime(entry);

        weblogger.getWeblogEntryManager().saveWeblogEntry(entry);
        weblogger.flush();

        return EntryDtos.toView(entry, permalink(entry));
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
    public EntryDtos.EntryView restore(HttpServletRequest request,
                                       @PathVariable("id") String id) throws WebloggerException {
        WeblogEntry entry = requireEntry(request, id);
        if (entry.getStatus() != WeblogEntry.PubStatus.TRASHED) {
            throw ApiException.conflict("Entry is not trashed.");
        }
        weblogger.getWeblogEntryManager().restoreWeblogEntry(entry);
        weblogger.flush();
        return EntryDtos.toView(entry, permalink(entry));
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
     * Previews an existing entry's unsaved text. Reuses {@code
     * EntryEditController.entryEditPreview}'s scratch-entry approach: the
     * entry itself comes from the usual ownership-checked lookup (refusing a
     * TRASHED entry the same way {@code update} does -- previewing "as
     * though live" a trashed entry is the same side door, just read-only
     * instead of a resurrection), but its text is replaced with whatever the
     * request carries and never saved. Only the server can expand
     * shortcodes -- {@code [gallery]}, {@code [map]} -- so this is the only
     * way an agent can see what it is about to publish before actually
     * publishing it.
     */
    @PostMapping("/{id}/preview")
    public EntryDtos.PreviewView previewExisting(
            HttpServletRequest request, @PathVariable("id") String id,
            @RequestBody EntryDtos.PreviewRequest body) throws WebloggerException {
        WeblogEntry entry = requireLiveEntry(request, id);
        return renderPreview(entry, body);
    }

    /**
     * Previews text for an entry that does not exist yet -- a brand-new,
     * unsaved {@code WeblogEntry} owned by the action weblog, so its
     * shortcodes resolve that weblog's own media and nothing is persisted.
     * Same scratch-entry approach {@code EntryEditController} uses when its
     * preview call carries no {@code id}.
     */
    @PostMapping("/preview")
    public EntryDtos.PreviewView previewNew(
            HttpServletRequest request,
            @RequestBody EntryDtos.PreviewRequest body) throws WebloggerException {
        Weblog weblog = requireActionWeblog(request);
        User user = requireAuthenticatedUser(request);
        WeblogEntry entry = new WeblogEntry();
        entry.setWebsite(weblog);
        entry.setCreatorUserName(user.getUserName());
        return renderPreview(entry, body);
    }

    /**
     * Sets the scratch entry's text and renders it through the real
     * pipeline -- shortcode expansion, then Markdown, then sanitization --
     * exactly as {@code EntryRenderer.transformedText()} does for a
     * published entry, so a preview cannot disagree with what gets
     * published. A missing or blank {@code text} renders an empty draft
     * rather than failing: previewing a blank editor is a normal moment, not
     * an error.
     */
    private EntryDtos.PreviewView renderPreview(WeblogEntry entry, EntryDtos.PreviewRequest body) {
        // Mutates a JPA-managed entity (when entry came from requireLiveEntry
        // rather than the previewNew scratch path) without saving. Safe only
        // because nothing on this request path calls weblogger.flush() or
        // saveWeblogEntry -- a future change adding either here would
        // silently persist preview text over the stored entry.
        entry.setText(body == null || body.text() == null ? "" : body.text());
        return new EntryDtos.PreviewView(weblogger.getEntryRenderer().transformedText(entry));
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
            throw ApiException.badRequest("pubTime is required when status is SCHEDULED.");
        }
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
    private void requireTitleAndText(EntryDtos.EntryWrite body) {
        if (body.title() == null || body.title().isBlank()) {
            throw ApiException.badRequest("title is required.");
        }
        if (body.text() == null || body.text().isBlank()) {
            throw ApiException.badRequest("text is required.");
        }
    }

    /**
     * {@code category} names a category by NAME, matching {@code
     * EntryDtos.EntryView.category} -- the read side already exposes the
     * name rather than an internal id, so the write side accepts the same
     * thing back. {@code getWeblogCategoryByName} is scoped to {@code
     * weblog} in the query itself, the same lookup {@code CategoriesApi}
     * uses for its own rename-collision check, so an unknown name is simply
     * absent -- there is no separate ownership check to bypass, unlike an
     * by-id lookup.
     *
     * <p>Null (absent from the request) leaves the entry's category
     * untouched, per {@code applyWrite}'s "null means absent" contract.
     */
    private void applyCategory(WeblogEntry entry, Weblog weblog, String categoryName)
            throws WebloggerException {
        if (categoryName == null) {
            return;
        }
        String trimmed = categoryName.trim();
        if (trimmed.isBlank()) {
            throw ApiException.badRequest("category cannot be blank.");
        }
        WeblogCategory category = weblogger.getWeblogEntryManager().getWeblogCategoryByName(weblog, trimmed);
        if (category == null) {
            throw ApiException.badRequest("Unknown category '" + categoryName + "'.");
        }
        entry.setCategory(category);
    }

    /**
     * Tags go through the entry's own comma/space-tolerant tag-setting path
     * rather than being reimplemented here. Null (absent) leaves the
     * stored tags untouched; a non-null EMPTY list is a real instruction to
     * clear every tag, distinguishable from absence exactly as {@code
     * applyWrite} distinguishes them for every other field.
     *
     * <p>The length guard is checked AFTER splitting the joined string on
     * {@link Utilities#TAG_SPLIT_CHARS} -- the same split {@code
     * WeblogEntry.setTagsAsString} performs internally -- rather than per
     * list element. A single list element can itself carry several
     * space/comma-separated tags whose COMBINED length exceeds the column
     * even though every tag actually stored (one row per split token) is
     * short; checking before the split measured the wrong thing and
     * over-rejected that case.
     */
    private void applyTags(WeblogEntry entry, List<String> tags) throws WebloggerException {
        if (tags == null) {
            return;
        }
        String joined = String.join(" ", tags);
        for (String tag : Utilities.splitStringAsTags(joined)) {
            ColumnLimits.requireMaxLength("tag", tag, ColumnLimits.TAG_NAME);
        }
        entry.setTagsAsString(joined);
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
