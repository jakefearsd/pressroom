package com.wikantik.pressroom.ui.restapi.v1;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.apache.commons.lang3.StringUtils;
import com.wikantik.pressroom.WebloggerException;
import com.wikantik.pressroom.business.AnchorTakenException;
import com.wikantik.pressroom.business.EntryPins;
import com.wikantik.pressroom.business.fetch.RemoteImageFetcher;
import com.wikantik.pressroom.pojos.User;
import com.wikantik.pressroom.pojos.Weblog;
import com.wikantik.pressroom.pojos.WeblogCategory;
import com.wikantik.pressroom.pojos.WeblogEntry;
import com.wikantik.pressroom.pojos.WeblogEntryRevision;
import com.wikantik.pressroom.pojos.WeblogPage;
import com.wikantik.pressroom.pojos.WeblogPermission;
import com.wikantik.pressroom.ui.controllers.ContributorRules;
import com.wikantik.pressroom.ui.controllers.EntryDeletion;
import com.wikantik.pressroom.ui.controllers.EntryFieldRules;
import com.wikantik.pressroom.ui.controllers.EntryPinRules;
import com.wikantik.pressroom.ui.controllers.RuleViolation;
import com.wikantik.pressroom.ui.controllers.StrandChoice;
import com.wikantik.pressroom.ui.controllers.UISecurityEnforced;
import com.wikantik.pressroom.ui.controllers.WeblogOwnership;
import com.wikantik.pressroom.ui.restapi.ApiException;
import com.wikantik.pressroom.ui.restapi.ColumnLimits;
import com.wikantik.pressroom.ui.restapi.FieldErrors;
import com.wikantik.pressroom.ui.restapi.ProblemCode;
import com.wikantik.pressroom.ui.restapi.RuleProblems;
import com.wikantik.pressroom.ui.restapi.dto.EntryDtos;
import com.wikantik.pressroom.ui.restapi.dto.RevisionDtos;
import com.wikantik.pressroom.util.MailUtil;
import com.wikantik.pressroom.util.Utilities;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
 * Entry writes, as the JSP entry editor makes them. A separate {@code
 * @RestController} from {@link EntriesApi} on the same {@code
 * /v1/weblogs/{handle}/entries} path because reads and writes have different
 * tags and token ceilings; Spring MVC resolves by method plus path, so GET
 * reaches {@link EntriesApi} and POST/PATCH/DELETE reach this controller.
 *
 * <p><b>Who may write</b> (spec 2026-11-21 owner decisions, Task 3): the
 * editor's level, {@code EDIT_DRAFT}, which {@code PressroomHandlerInterceptor}
 * enforces like every declared permission, with a POST- or ADMIN-role token
 * ({@code ApiScopeInterceptor}). A caller without {@code POST} on the weblog
 * -- a contributor -- is then held here to the editor's rules, through
 * {@link ContributorRules}: a publish or a schedule is submitted for review
 * (PENDING, as the editor's Publish button does for them); an update, a trash,
 * or a revision restore or keep needs an entry that is their own draft or
 * pending entry ({@code entry.notChangeable}, 403); and pinning or a strand
 * stays a publisher's ({@code entry.needsPublishRights}, 403), as does importing
 * an image (no default import for them: external images stay linked). Preview renders
 * text and changes nothing, so it needs only the entry's weblog, as the
 * editor's preview does. Restore and delete-forever take {@code POST}, as the
 * Trash page does, so they live in {@link EntriesTrashApi}: the declared
 * permission is per controller.
 *
 * <p>The routes that return an entry take {@code fields} ({@link
 * EntryFields}), so their Java return type is {@code Object}; each one's
 * {@code @ApiResponse} keeps openapi.json naming the view a caller gets
 * without it.
 */
@Tag(name = "Entry writes", description = "Writing entries: create, update, trash, preview, and restore or keep "
        + "a revision. A member without publish rights writes drafts: a publish is submitted for review.")
@RestController
@RequestMapping("/v1/weblogs/{handle}/entries")
public class EntriesWriteApi extends BaseApiController implements UISecurityEnforced {

    private static final Logger log = LoggerFactory.getLogger(EntriesWriteApi.class);

    @Autowired
    RemoteImageFetcher imageFetcher;

    /** The import step's budget is measured on this; a test replaces it. */
    Clock clock = Clock.systemUTC();

    @Operation(operationId = "entriesCreate")
    @PostMapping("")
    @ApiResponse(responseCode = "201", content = @Content(schema = @Schema(implementation = EntryDtos.EntryView.class)))
    public ResponseEntity<Object> create(
            HttpServletRequest request, @RequestBody EntryDtos.EntryWrite body,
            @Parameter(description = EntryFields.DESCRIPTION)
            @RequestParam(value = "fields", required = false) List<String> fields) throws WebloggerException {
        Weblog weblog = requireActionWeblog(request);
        User user = requireAuthenticatedUser(request);
        boolean mayPublish = mayPublish(weblog, user);

        if (body.clear() != null && !body.clear().isEmpty()) {
            throw ApiException.problemOnField("clear", ProblemCode.FIELD_UPDATE_ONLY, "clear");
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
        checkWrite(errors, weblog, entry, body, Set.of(), mayPublish);
        EntryImports imports = new EntryImports(weblogger, imageFetcher, clock);
        EntryImports.Plan importPlan = imports.plan(errors, weblog, body, Set.of(), null, mayPublish);
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
        apply(entry, weblog, effective, Set.of(), mayPublish);
        defaultFeaturedImage(entry, weblog, Set.of(), imports.importedIds());
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
        notifyReviewers(entry, null);

        URI location = ServletUriComponentsBuilder.fromRequestUri(request)
                .path("/{id}")
                .buildAndExpand(entry.getId())
                .toUri();
        return ResponseEntity.created(location).body(shown.project(EntryDtos.toView(entry, permalink(entry))));
    }

    @Operation(operationId = "entriesUpdate")
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
        boolean mayPublish = requireChangeable(request, entry);
        // Before every other check, and so before the import step can fetch:
        // a caller whose view of the text is stale learns that first.
        entryContent().requireUnchanged(entry, sent.expectedContentHash());
        String read = entry.getText();
        WeblogEntry.PubStatus before = entry.getStatus();

        FieldErrors errors = new FieldErrors();
        EntryFields shown = EntryFields.of(fields, errors);
        // A blank note says nothing, so it is no note.
        String revisionNote = StringUtils.trimToNull(sent.revisionNote());
        errors.check("revisionNote", () -> ColumnLimits.requireMaxLength(
                "revisionNote", revisionNote, ColumnLimits.REVISION_NOTE));
        // From here on edits are text: what they make of the stored text is
        // checked, imported and saved exactly as though it had been sent.
        EntryDtos.EntryWrite body = EntryEdits.sent(sent.edits())
                ? sent.withText(EntryEdits.resolve(errors, sent.text(), sent.edits(), entry.getText()))
                : sent;
        Set<String> cleared = new HashSet<>();
        errors.check("clear", () -> cleared.addAll(EntryDtos.ENTRY_CLEAR.validate(body.clear(), body)));
        if (body.anchor() != null && !body.anchor().equals(entry.getAnchor())) {
            errors.add("anchor", ProblemCode.ANCHOR_IMMUTABLE);
        }
        checkWrite(errors, weblog, entry, body, cleared, mayPublish);
        EntryImports imports = new EntryImports(weblogger, imageFetcher, clock);
        EntryImports.Plan importPlan = imports.plan(errors, weblog, body, cleared, entry.getText(), mayPublish);
        errors.throwIfAny();

        // The import step commits each image it stores; nothing has been
        // applied to the managed entry yet, so nothing of the write goes with it.
        EntryDtos.EntryWrite effective = imports.run(importPlan, weblog, body);
        if (sent.expectedContentHash() != null || EntryEdits.sent(sent.edits())) {
            entryContent().requireUnchangedUnderLock(entry, read);
        }
        apply(entry, weblog, effective, cleared, mayPublish);
        defaultFeaturedImage(entry, weblog, cleared, imports.importedIds());
        // The token's user made this save, so a revision it leaves names them,
        // and carries the note when one was sent.
        weblogger.getWeblogEntryManager().saveWeblogEntry(entry, requireAuthenticatedUser(request), revisionNote);
        weblogger.flush();
        notifyReviewers(entry, before);

        return shown.project(EntryDtos.toView(entry, permalink(entry)));
    }

    /**
     * The editor's pending-review notice, for a write that submitted the
     * entry for review: it is pending now and was not before ({@code before}
     * is null for a create). A change to an entry already waiting is not a
     * second submission, so the reviewers hear once, not on every PATCH.
     * The save has committed, so a notice that cannot be sent is logged
     * rather than failing a write that did succeed.
     */
    private void notifyReviewers(WeblogEntry entry, WeblogEntry.PubStatus before) {
        if (before == WeblogEntry.PubStatus.PENDING) {
            return;
        }
        try {
            MailUtil.sendPendingEntryNoticeIfDue(weblogger, entry);
        } catch (WebloggerException e) {
            log.error("Pending notice for entry {} not sent", entry.getId(), e);
        }
    }

    /** Whether {@code user} may publish on {@code weblog}; a check that fails is a no ({@link ContributorRules}). */
    private boolean mayPublish(Weblog weblog, User user) {
        return ContributorRules.mayPublish(weblogger.getUserManager(), weblog, user);
    }

    /**
     * Refuses a change to {@code entry} the entry editor would refuse
     * ({@code BaseController.lookupChangeableEntry}): someone without publish
     * rights changes only their own drafts and pending entries. The editor
     * answers that like an unknown id; here the caller may read the entry
     * ({@link EntriesApi} takes EDIT_DRAFT), so hiding it would hide nothing,
     * and the answer says why: a 403 {@code entry.notChangeable}.
     *
     * @return whether the caller may publish, for the write's status ceiling
     */
    private boolean requireChangeable(HttpServletRequest request, WeblogEntry entry) {
        User user = requireAuthenticatedUser(request);
        boolean mayPublish = mayPublish(requireActionWeblog(request), user);
        if (!mayPublish && !ContributorRules.mayChange(entry, user)) {
            throw ApiException.problem(ProblemCode.ENTRY_NOT_CHANGEABLE);
        }
        return mayPublish;
    }

    /**
     * Puts a revision's title, summary and text back, as the editor's Restore
     * does (spec 2026-10-24, section 5): through the ordinary save, so the
     * state it replaces becomes a revision of its own, credited to the
     * token's user. A restore is therefore undone by restoring that revision.
     * Refused as an update is: a trashed entry is a 409 (restore it from the
     * trash first), and an {@code expectedContentHash} that is not the stored
     * text's is the 409 {@code entry-changed}, with nothing saved. Another
     * entry's revision is the 404 an unknown id gets.
     */
    @Operation(operationId = "entriesRestoreRevision")
    @PostMapping("/{id}/revisions/{revisionId}/restore")
    @ApiResponse(responseCode = "200", content = @Content(schema = @Schema(implementation = EntryDtos.EntryView.class)))
    public Object restoreRevision(
            HttpServletRequest request, @PathVariable("id") String id,
            @PathVariable("revisionId") String revisionId,
            @RequestBody(required = false) RevisionDtos.RevisionRestore body,
            @Parameter(description = EntryFields.DESCRIPTION)
            @RequestParam(value = "fields", required = false) List<String> fields) throws WebloggerException {
        WeblogEntry entry = requireLiveEntry(request, id);
        requireChangeable(request, entry);
        WeblogEntryRevision revision = entryContent().requireRevision(entry, revisionId);
        EntryFields shown = EntryFields.of(fields);
        // The manager's restore, not a plain save: a restore is never
        // coalesced, so what it replaces always becomes a revision.
        entryContent().restoreChecked(entry, revision, body == null ? null : body.expectedContentHash(),
                requireAuthenticatedUser(request));
        weblogger.flush();

        return shown.project(EntryDtos.toView(entry, permalink(entry)));
    }

    /**
     * Marks a revision as kept: exempt from the per-entry count, the age
     * limit and the site-wide ceiling until it is released. At most ten per
     * entry; an eleventh is a 409.
     */
    @Operation(operationId = "entriesKeepRevision")
    @PostMapping("/{id}/revisions/{revisionId}/keep")
    public RevisionDtos.RevisionSummary keepRevision(
            HttpServletRequest request, @PathVariable("id") String id,
            @PathVariable("revisionId") String revisionId) throws WebloggerException {
        return markKept(request, id, revisionId, true);
    }

    /** Releases a kept revision to the ordinary retention rules; nothing is removed by the call itself. */
    @Operation(operationId = "entriesUnkeepRevision")
    @PostMapping("/{id}/revisions/{revisionId}/unkeep")
    public RevisionDtos.RevisionSummary unkeepRevision(
            HttpServletRequest request, @PathVariable("id") String id,
            @PathVariable("revisionId") String revisionId) throws WebloggerException {
        return markKept(request, id, revisionId, false);
    }

    private RevisionDtos.RevisionSummary markKept(HttpServletRequest request, String id, String revisionId,
            boolean kept) throws WebloggerException {
        RevisionedContent<WeblogEntry, WeblogEntryRevision> content = entryContent();
        WeblogEntry entry = requireLiveEntry(request, id);
        requireChangeable(request, entry);
        WeblogEntryRevision revision = content.requireRevision(entry, revisionId);
        applyKeep(() -> content.setKept(revision, kept));
        return content.summary(revision);
    }

    /**
     * Trash, not delete. Goes through the same seam the authoring UI uses --
     * {@link EntryDeletion#trashEntry} -- because the index removal
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
    @Operation(operationId = "entriesTrash")
    @ApiResponse(responseCode = "204", description = "Done; no body.")
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> trash(HttpServletRequest request,
                                      @PathVariable("id") String id) throws WebloggerException {
        WeblogEntry entry = requireEntry(request, id);
        if (entry.getStatus() == WeblogEntry.PubStatus.TRASHED) {
            throw ApiException.problem(ProblemCode.ENTRY_ALREADY_TRASHED);
        }
        requireChangeable(request, entry);
        EntryDeletion.trashEntry(weblogger, entry);
        weblogger.flush();
        return ResponseEntity.noContent().build();
    }

    /**
     * {@code edits} and {@code expectedContentHash} act on stored text, which a
     * create has none of; {@code revisionNote} labels the revision an update
     * leaves, and a create replaces nothing, so it leaves none.
     */
    private static void refuseUpdateOnlyFields(FieldErrors errors, EntryDtos.EntryWrite body) {
        RevisionedContent.refuseRevisionNote(errors, body.revisionNote());
        if (EntryEdits.sent(body.edits())) {
            errors.add("edits", ProblemCode.EDITS_ON_CREATE);
        }
        RevisionedContent.refuseExpectedHash(errors, body.expectedContentHash());
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
    @Operation(operationId = "entriesPreview")
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
    @Operation(operationId = "entriesPreviewNew")
    @PostMapping("/preview")
    public EntryDtos.PreviewView previewNew(
            HttpServletRequest request,
            @RequestBody EntryDtos.PreviewRequest body) throws WebloggerException {
        if (EntryEdits.sent(body.edits())) {
            throw ApiException.problemOnField("edits", ProblemCode.EDITS_PREVIEW_NEEDS_ID);
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
     *
     * <p>The warnings are {@link PreviewLint}'s, then the SEO body checks'
     * ({@link SeoBodies}, the same ones {@code audit/seo/entries/{id}}
     * reports), each a sentence. Their lookups are reads whose queries never
     * flush ({@code FlushModeType.COMMIT}), so they cannot persist the text
     * set below either.
     */
    private EntryDtos.PreviewView renderPreview(WeblogEntry entry, String text) throws WebloggerException {
        // Mutates a JPA-managed entity (when entry came from requireLiveEntry
        // rather than the previewNew scratch path) without saving. Safe only
        // because nothing on this request path calls weblogger.flush() or
        // saveWeblogEntry -- a future change adding either here would
        // silently persist preview text over the stored entry.
        entry.setText(text == null ? "" : text);
        String html = weblogger.getEntryRenderer().transformedText(entry);
        List<String> warnings = new ArrayList<>(PreviewLint.warnings(html));
        warnings.addAll(SeoBodies.warnings(weblogger, entry.getWebsite(), entry, html));
        return new EntryDtos.PreviewView(html, warnings);
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
        entry.stampPubTimeIfPublished(new Timestamp(System.currentTimeMillis()));
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
        if (entry.lacksScheduleTime()) {
            throw ApiException.problemOnField("pubTime", ProblemCode.PUBTIME_REQUIRED);
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
                            EntryDtos.EntryWrite body, Set<String> cleared, boolean mayPublish)
            throws WebloggerException {
        if (!mayPublish) {
            refusePublishersFields(entry, body, cleared);
        }
        WeblogEntry scratch = EntryDtos.scratchOf(entry);
        errors.check(() -> EntryDtos.applyWrite(scratch, body, weblog, cleared));
        holdToCeiling(scratch, mayPublish);
        errors.check("category", () -> categoryFor(weblog, body.category()));
        errors.check("tags", () -> checkTags(body.tags()));
        applyPublishNowDefault(scratch);
        // A pubTime in the body either parsed or has its own error already.
        if (body.pubTime() == null) {
            errors.check("pubTime", () -> requireScheduledHasPubTime(scratch));
        }
        checkImageId(errors, weblog, "featuredImageId", body.featuredImageId(), entry.getFeaturedImageId());
        checkImageId(errors, weblog, "ogImageId", body.ogImageId(), entry.getOgImageId());
        if (Boolean.TRUE.equals(body.pinned()) && !entry.getPinned()) {
            errors.check("pinned", () -> requirePinRoom(weblog, entry.getPinned()));
        }
        errors.check("strand", () -> strandFor(weblog, entry, body.strand()));
    }

    /**
     * Pinning and strands are a publisher's: the editor shows neither to a
     * contributor, and keeps what is stored. Over the API a contributor's
     * write that would change either is refused, naming the field; one that
     * sends the stored value back (a view read and returned) changes nothing
     * and passes. So is asking to import an image ({@code featuredImageUrl},
     * {@code importExternalImages: true}): that adds media, which takes POST
     * on the JSP side, and the editor's save imports nothing; the import a
     * write gets without asking is a no-op for them instead
     * ({@link EntryImports#plan}). Thrown, not collected: it is a 403, not a
     * field problem, and it comes before anything is fetched.
     */
    private static void refusePublishersFields(WeblogEntry entry, EntryDtos.EntryWrite body, Set<String> cleared) {
        if (body.featuredImageUrl() != null) {
            throw ApiException.problemOnField("featuredImageUrl", ProblemCode.ENTRY_NEEDS_PUBLISH_RIGHTS,
                    "featuredImageUrl");
        }
        if (Boolean.TRUE.equals(body.importExternalImages())) {
            throw ApiException.problemOnField("importExternalImages", ProblemCode.ENTRY_NEEDS_PUBLISH_RIGHTS,
                    "importExternalImages");
        }
        if (body.pinned() != null && !body.pinned().equals(entry.getPinned())) {
            throw ApiException.problemOnField(EntryPinRules.PINNED, ProblemCode.ENTRY_NEEDS_PUBLISH_RIGHTS,
                    EntryPinRules.PINNED);
        }
        boolean joins = body.strand() != null && !body.strand().equals(entry.getStrandPageId());
        boolean leaves = cleared.contains("strand") && entry.getStrandPageId() != null;
        if (joins || leaves) {
            throw ApiException.problemOnField("strand", ProblemCode.ENTRY_NEEDS_PUBLISH_RIGHTS, "strand");
        }
    }

    /**
     * A write by someone without publish rights lands at most in PENDING
     * ({@link ContributorRules#ceiling}): asking for PUBLISHED or SCHEDULED
     * submits the entry for review, as the editor's Publish button does for
     * them. Applied after the write's own status, before the publish-now and
     * schedule checks read it.
     */
    private static void holdToCeiling(WeblogEntry entry, boolean mayPublish) {
        if (!mayPublish) {
            entry.setStatus(ContributorRules.ceiling(entry.getStatus()));
        }
    }

    /**
     * The strand page {@code strandId} names, for the entry to join as its
     * last part, or null when there is nothing to join: no strand sent, or
     * the one the entry is already in (re-sending it changes nothing, as in
     * the entry editor). Anything else must be one of this blog's strand
     * pages: another blog's page, an unknown id, a blank one and a plain page
     * are one 400 on {@code strand}. {@link WeblogOwnership#page} is the
     * ownership check, and a lookup that fails answers null, so it refuses.
     * Joining needs POST ({@link #refusePublishersFields}).
     */
    private WeblogPage strandFor(Weblog weblog, WeblogEntry entry, String strandId) {
        StrandChoice.Choice choice = StrandChoice.resolve(weblogger, weblog, entry, strandId);
        if (choice.violation() != null) {
            throw RuleProblems.problem(choice.violation(), true);
        }
        return choice.page();
    }

    /**
     * A new pin is refused once the blog has {@link EntryPins#MAX_PER_WEBLOG}
     * (the editor's rule, {@link EntryPinRules}). Pinning needs POST
     * ({@link #refusePublishersFields}), as publishing does.
     */
    private void requirePinRoom(Weblog weblog, boolean wasPinned) throws WebloggerException {
        RuleViolation full = EntryPinRules.roomProblem(weblogger.getWeblogEntryManager(), weblog, wasPinned, true);
        if (full != null) {
            throw RuleProblems.problem(full, true);
        }
    }

    /**
     * An entry left with no featured image gets the first picture its text
     * shows, as Blogger does (spec 2026-11-07, pictures by default), unless
     * this write cleared it: that is a caller saying "none".
     */
    private void defaultFeaturedImage(WeblogEntry entry, Weblog weblog, Set<String> cleared, Set<String> importedNow) {
        if (StringUtils.isBlank(entry.getFeaturedImageId()) && !cleared.contains("featuredImageId")) {
            entry.setFeaturedImageId(WeblogOwnership.firstPublicImage(weblogger, weblog, entry.getText(), importedNow));
        }
    }

    /**
     * Applies a write {@link #checkWrite} passed. Each step repeats a check
     * that already passed on identical input, so none of them refuses here.
     */
    private void apply(WeblogEntry entry, Weblog weblog, EntryDtos.EntryWrite body, Set<String> cleared,
                       boolean mayPublish) throws WebloggerException {
        EntryDtos.applyWrite(entry, body, weblog, cleared);
        holdToCeiling(entry, mayPublish);
        WeblogCategory category = categoryFor(weblog, body.category());
        if (category != null) {
            entry.setCategory(category);
        }
        applyTags(entry, body.tags());
        WeblogPage strand = strandFor(weblog, entry, body.strand());
        if (strand != null) {
            // Appends, and from another strand moves: one strand per post.
            weblogger.getWeblogEntryManager().appendToStrand(entry, strand);
        }
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
            throw ApiException.problemOnField(field, ProblemCode.FIELD_REQUIRED, field);
        }
    }

    private static void requireAnchorForm(String anchor) {
        if (!EntryFieldRules.isValidAnchor(anchor)) {
            throw ApiException.problemOnField("anchor", ProblemCode.ANCHOR_INVALID,
                    Integer.toString(EntryFieldRules.ANCHOR_MAX_LENGTH));
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
        return ApiException.problemOnField("anchor", holderStatus == WeblogEntry.PubStatus.TRASHED
                ? ProblemCode.ANCHOR_HELD_BY_TRASH : ProblemCode.ANCHOR_IN_USE, cause, anchor);
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
            throw ApiException.problemOnField("category", ProblemCode.FIELD_BLANK, "category");
        }
        WeblogCategory category = weblogger.getWeblogEntryManager().getWeblogCategoryByName(weblog, trimmed);
        if (category == null) {
            String names = weblogger.getWeblogEntryManager().getWeblogCategories(weblog).stream()
                    .map(WeblogCategory::getName)
                    .collect(Collectors.joining(", "));
            throw ApiException.problemOnField("category", ProblemCode.CATEGORY_UNKNOWN, categoryName, names);
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

    /** The entry editor's level; a contributor's limits are applied in the handler (see the class comment). */
    @Override
    public List<String> requiredWeblogPermissionActions() {
        return List.of(WeblogPermission.EDIT_DRAFT);
    }

    @Override
    public List<String> requiredGlobalPermissionActions() {
        return List.of();
    }
}
