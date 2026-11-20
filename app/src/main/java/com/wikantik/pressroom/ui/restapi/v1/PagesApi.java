package com.wikantik.pressroom.ui.restapi.v1;

import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import com.wikantik.pressroom.WebloggerException;
import com.wikantik.pressroom.business.StrandRules;
import com.wikantik.pressroom.pojos.Weblog;
import com.wikantik.pressroom.pojos.WeblogEntry;
import com.wikantik.pressroom.pojos.WeblogPage;
import com.wikantik.pressroom.pojos.WeblogPermission;
import com.wikantik.pressroom.ui.controllers.UISecurityEnforced;
import com.wikantik.pressroom.ui.controllers.WeblogOwnership;
import com.wikantik.pressroom.ui.restapi.ApiException;
import com.wikantik.pressroom.ui.restapi.FieldErrors;
import com.wikantik.pressroom.ui.restapi.dto.ClearFields;
import com.wikantik.pressroom.ui.restapi.dto.PageDtos;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;
import org.springframework.web.bind.annotation.RequestParam;
import io.swagger.v3.oas.annotations.Parameter;
import java.util.Locale;
import com.wikantik.pressroom.ui.restapi.dto.RevisionDtos;
import com.wikantik.pressroom.pojos.WeblogPageRevision;
import com.wikantik.pressroom.ui.restapi.ColumnLimits;
import org.apache.commons.lang3.StringUtils;

/**
 * Static page CRUD. {@code UISecurityEnforced} declares
 * {@code WeblogPermission.POST} -- pages are blog-wide structure, like
 * categories, not a single draft -- so {@code PressroomHandlerInterceptor} is
 * what actually enforces it; this controller adds no permission checking of
 * its own.
 *
 * <p>Every write goes through {@code WeblogPageManager.savePage}/{@code
 * removePage} rather than saving the entity directly -- both bump {@code
 * weblog.lastModified}, the only thing that expires a rendered page in
 * {@code WeblogPageCache} (it has no CacheHandler). A slug is validated with
 * {@link PageDtos#requireUsableSlug} and checked for a duplicate against the
 * weblog before ever reaching the manager, because {@code savePage} answers
 * both a blank/reserved slug and a duplicate-primary-key row with a bare
 * {@code WebloggerException} that the generic handler can only render as an
 * opaque 500.
 */
@RestController
@RequestMapping("/v1/weblogs/{handle}/pages")
public class PagesApi extends BaseApiController implements UISecurityEnforced {

    /** The longest parts list {@code set_parts} accepts. */
    static final int MAX_PARTS = 500;

    /**
     * The weblog's pages, without the trash; {@code status=TRASHED} lists
     * the trash instead, and is the only way to read a trashed page (each
     * view is complete, {@code trashedAt} included). No other status filters
     * the list, so any other value is refused, not ignored.
     */
    @GetMapping("")
    public List<PageDtos.PageView> list(
            HttpServletRequest request,
            @Parameter(description = "TRASHED lists the page trash; omit it for the pages themselves.")
            @RequestParam(value = "status", required = false) String status) throws WebloggerException {
        Weblog weblog = requireActionWeblog(request);
        boolean trash = status != null;
        if (trash && !namesTheTrash(status)) {
            throw ApiException.invalidField("status", "status accepts only TRASHED, which lists the page trash.");
        }
        List<WeblogPage> pages = trash ? weblogger.getWeblogPageManager().getTrashedPages(weblog)
                : weblogger.getWeblogPageManager().getPages(weblog);
        List<PageDtos.PageView> views = new ArrayList<>();
        for (WeblogPage page : pages) {
            views.add(view(page));
        }
        return views;
    }

    /** Whether a {@code status} filter is TRASHED, in any case; parsed as the write's status is. */
    private static boolean namesTheTrash(String status) {
        try {
            return WeblogPage.PubStatus.valueOf(status.trim().toUpperCase(Locale.ROOT)) == WeblogPage.PubStatus.TRASHED;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    @GetMapping("/{id}")
    public PageDtos.PageView get(HttpServletRequest request, @PathVariable("id") String id)
            throws WebloggerException {
        return view(requirePage(request, id));
    }

    @PostMapping("")
    public ResponseEntity<PageDtos.PageView> create(
            HttpServletRequest request, @RequestBody PageDtos.PageWrite body) throws WebloggerException {
        Weblog weblog = requireActionWeblog(request);

        // Every field problem is collected and answered in one 400, as an
        // entry write's are. title is not a NOT NULL column the way an
        // entry's is, but a page with no title at all is not a resource an
        // API caller meant to create -- same reasoning as
        // EntriesWriteApi.requireTitleAndText, applied on create only; a PATCH
        // omitting title leaves the stored one alone per applyWrite's "null
        // means absent" contract. clear is refused on create, as on entries.
        FieldErrors errors = new FieldErrors();
        if (body.title() == null || body.title().isBlank()) {
            errors.add("title", "title is required.");
        }
        if (body.clear() != null && !body.clear().isEmpty()) {
            errors.add("clear", "clear applies only to updates.");
        }
        // Both act on stored content, which a create has none of.
        RevisionedContent.refuseRevisionNote(errors, body.revisionNote());
        RevisionedContent.refuseExpectedHash(errors, body.expectedContentHash());
        String[] slug = new String[1];
        // No slug named: the title's, held to the rules a typed one is.
        String wanted = body.slug() == null || body.slug().isBlank()
                ? WeblogPage.slugFromTitle(body.title()) : body.slug();
        errors.check("slug", () -> slug[0] = PageDtos.requireUsableSlug(wanted));
        checkWrite(errors, weblog, body, Set.of(), null);
        checkStrand(errors, weblog, null, body.strand());
        errors.throwIfAny();
        // A clash with stored state, not a field format problem: a 409,
        // reported only once every field passed (as an entry's anchor is).
        requireSlugAvailable(weblog, slug[0], null);

        WeblogPage page = new WeblogPage();
        page.setWeblog(weblog);
        PageDtos.applyWrite(page, body, Set.of());
        page.setSlug(slug[0]);

        weblogger.getWeblogPageManager().savePage(page, requireAuthenticatedUser(request), null);
        weblogger.flush();

        URI location = ServletUriComponentsBuilder.fromRequestUri(request)
                .path("/{id}")
                .buildAndExpand(page.getId())
                .toUri();
        return ResponseEntity.created(location).body(view(page));
    }

    @PatchMapping("/{id}")
    public PageDtos.PageView update(
            HttpServletRequest request, @PathVariable("id") String id,
            @RequestBody PageDtos.PageWrite body) throws WebloggerException {
        WeblogPage page = requireLivePageForWrite(request, id);
        // Before any field is looked at: a caller working from stale text
        // learns that first, and nothing below is applied.
        pageContent().requireUnchanged(page, body.expectedContentHash());

        FieldErrors errors = new FieldErrors();
        String revisionNote = StringUtils.trimToNull(body.revisionNote());
        errors.check("revisionNote", () -> ColumnLimits.requireMaxLength(
                "revisionNote", revisionNote, ColumnLimits.REVISION_NOTE));
        if (body.title() != null && body.title().isBlank()) {
            errors.add("title", "title cannot be blank.");
        }
        String[] slug = new String[1];
        if (body.slug() != null) {
            errors.check("slug", () -> slug[0] = PageDtos.requireUsableSlug(body.slug()));
        }
        Set<String> cleared = new HashSet<>();
        errors.check("clear", () -> cleared.addAll(
                PageDtos.PAGE_CLEAR.validate(body.clear(), body)));
        checkWrite(errors, page.getWeblog(), body, cleared, page.getOgImageId());
        checkStrand(errors, page.getWeblog(), page, body.strand());
        errors.throwIfAny();
        if (slug[0] != null) {
            requireSlugAvailable(page.getWeblog(), slug[0], page.getId());
        }

        // Read before anything is applied; checked again under the row lock,
        // so two guarded updates cannot both pass on the same stale read.
        String read = page.getContent();
        if (body.expectedContentHash() != null) {
            pageContent().requireUnchangedUnderLock(page, read);
        }

        PageDtos.applyWrite(page, body, cleared);
        if (slug[0] != null) {
            page.setSlug(slug[0]);
        }

        weblogger.getWeblogPageManager().savePage(page, requireAuthenticatedUser(request), revisionNote);
        weblogger.flush();

        return view(page);
    }

    /**
     * The page's revisions, newest first: each is the title and content one
     * save replaced, with who saved, when and its note. No content here;
     * {@link #revision} has it. As readable as the page itself.
     */
    @GetMapping("/{id}/revisions")
    public List<RevisionDtos.RevisionSummary> revisions(HttpServletRequest request, @PathVariable("id") String id)
            throws WebloggerException {
        WeblogPage page = requirePage(request, id);
        return weblogger.getWeblogPageManager().getRevisions(page).stream()
                .map(RevisionDtos::toSummary)
                .toList();
    }

    /**
     * One revision with its content and a unified diff from it to the page's
     * current content, or to another revision of the same page named by
     * {@code against}. Either id naming another page's revision is the 404
     * an unknown id gets.
     */
    @GetMapping("/{id}/revisions/{revisionId}")
    public RevisionDtos.RevisionView revision(
            HttpServletRequest request, @PathVariable("id") String id,
            @PathVariable("revisionId") String revisionId,
            @Parameter(description = "Another revision of the same page to diff against, in place of the "
                    + "page's current text.")
            @RequestParam(value = "against", required = false) String against) {
        return pageContent().diff(requirePage(request, id), revisionId, against);
    }

    /**
     * Puts a revision's title and content back, through the ordinary save,
     * so what it replaces becomes a revision of its own and a restore can be
     * undone. A revision holds no settings: the slug, status and navigation
     * stay as they are. Refused on a trashed page (409), and with an
     * {@code expectedContentHash} that is not the stored content's (409
     * page-changed).
     */
    @PostMapping("/{id}/revisions/{revisionId}/restore")
    public PageDtos.PageView restoreRevision(
            HttpServletRequest request, @PathVariable("id") String id,
            @PathVariable("revisionId") String revisionId,
            @RequestBody(required = false) PageDtos.RevisionRestore body) throws WebloggerException {
        WeblogPage page = requireLivePageForWrite(request, id);
        WeblogPageRevision revision = pageContent().requireRevision(page, revisionId);
        // The manager's restore, not a plain save: a restore is never
        // coalesced, so what it replaces always becomes a revision.
        pageContent().restoreChecked(page, revision, body == null ? null : body.expectedContentHash(),
                requireAuthenticatedUser(request));
        weblogger.flush();
        return view(page);
    }

    /**
     * Marks a revision as kept: exempt from the per-page count, the age
     * limit and the site-wide ceiling until it is released. At most ten per
     * page; an eleventh is a 409.
     */
    @PostMapping("/{id}/revisions/{revisionId}/keep")
    public RevisionDtos.RevisionSummary keepPageRevision(
            HttpServletRequest request, @PathVariable("id") String pageId,
            @PathVariable("revisionId") String revisionId) throws WebloggerException {
        return markPageRevisionKept(request, pageId, revisionId, true);
    }

    /** Releases a kept revision to the ordinary retention rules; nothing is removed by the call itself. */
    @PostMapping("/{id}/revisions/{revisionId}/unkeep")
    public RevisionDtos.RevisionSummary unkeepPageRevision(
            HttpServletRequest request, @PathVariable("id") String pageId,
            @PathVariable("revisionId") String revisionId) throws WebloggerException {
        return markPageRevisionKept(request, pageId, revisionId, false);
    }

    private RevisionDtos.RevisionSummary markPageRevisionKept(HttpServletRequest request, String id, String revisionId,
            boolean kept) throws WebloggerException {
        RevisionedContent<WeblogPage, WeblogPageRevision> content = pageContent();
        WeblogPageRevision pageRevision = content.requireRevision(requireLivePageForWrite(request, id), revisionId);
        applyKeep(() -> content.setKept(pageRevision, kept));
        return content.summary(pageRevision);
    }

    /**
     * Makes exactly {@code body.entries} the strand's parts, in that order,
     * in one step: posts not listed leave it, and a refused list changes
     * nothing. Each id is looked up through {@link WeblogOwnership#entry}, so
     * another blog's post is refused exactly as an unknown id is, never told
     * apart from one; {@link StrandRules} then refuses a post already in
     * another strand and an id listed twice. Each refused id is its own
     * {@code errors} entry on {@code entries}, naming it. The manager bumps
     * {@code weblog.lastModified}, which expires the rendered strand page and
     * its parts.
     */
    @PutMapping("/{id}/parts")
    public PageDtos.PageView replaceParts(
            HttpServletRequest request, @PathVariable("id") String id,
            @RequestBody PageDtos.PartsWrite body) throws WebloggerException {
        WeblogPage page = requireLivePageForWrite(request, id);
        List<String> ids = body.entries();
        if (ids == null) {
            throw ApiException.invalidField("entries", "entries is required: the strand's post ids in reading "
                    + "order. An empty list removes every part.");
        }
        if (!Boolean.TRUE.equals(page.getStrand())) {
            throw ApiException.invalidField("entries", "This page is not a strand; set strand true on it first.");
        }
        // One lookup per id follows: a list longer than any strand is refused
        // whole, before the first.
        if (ids.size() > MAX_PARTS) {
            throw ApiException.invalidField("entries", "At most " + MAX_PARTS + " parts.");
        }
        List<WeblogEntry> requested = new ArrayList<>(ids.size());
        for (String entryId : ids) {
            requested.add(WeblogOwnership.entry(weblogger, entryId, page.getWeblog()));
        }
        FieldErrors errors = new FieldErrors();
        StrandRules.refusals(page, requested, ids)
                .forEach((entryId, reason) -> errors.add("entries", refusal(entryId, reason)));
        errors.throwIfAny();

        weblogger.getWeblogEntryManager().setStrandParts(page, requested);
        weblogger.flush();
        return view(page);
    }

    private static String refusal(String entryId, String reason) {
        return switch (reason) {
            case "strand.error.inAnotherStrand" -> "Post '" + entryId + "' is already in another strand; "
                    + "move it with entries update strand, or take it out with clear: [\"strand\"].";
            case "strand.error.duplicate" -> "Post '" + entryId + "' is listed twice.";
            // unknownPost, and otherBlog, which the ownership lookup above
            // already turns into unknown: what another blog holds is not said.
            default -> "No post of this blog has id '" + entryId + "'.";
        };
    }

    /**
     * The page as the API shows it: a strand's parts are every member, any
     * status, in order. A page that is not a strand has none and costs no
     * query.
     */
    private PageDtos.PageView view(WeblogPage page) throws WebloggerException {
        List<WeblogEntry> parts = Boolean.TRUE.equals(page.getStrand())
                ? weblogger.getWeblogEntryManager().getStrandParts(page, false)
                : List.of();
        return PageDtos.toView(page, parts);
    }

    /**
     * The page editor's two strand rules: a site page is never a strand, and
     * a strand that still has parts (any status, the trash included) cannot
     * be unset. A count that cannot be read throws rather than passing.
     *
     * @param page the stored page, or null on create (which has no parts)
     */
    private void checkStrand(FieldErrors errors, Weblog weblog, WeblogPage page, Boolean strand)
            throws WebloggerException {
        if (Boolean.TRUE.equals(strand) && weblog.isSiteSpace()) {
            errors.add("strand", "A site page cannot be a strand.");
        }
        if (Boolean.FALSE.equals(strand) && page != null && Boolean.TRUE.equals(page.getStrand())) {
            long parts = weblogger.getWeblogEntryManager().getStrandPartCount(page);
            if (parts > 0) {
                errors.add("strand", "This strand has " + parts + " posts. Take them out first: "
                        + "PUT .../pages/{id}/parts with an empty entries list.");
            }
        }
    }

    /**
     * Every field check of a write, before anything is applied: the mapper's
     * own checks run on a scratch page, so a refused write leaves the managed
     * page exactly as it was, and {@code ogImageId} must name a public image
     * of this weblog (the entry API's rule, and the page editor's).
     */
    private void checkWrite(FieldErrors errors, Weblog weblog, PageDtos.PageWrite body, Set<String> cleared,
                            String storedOgImageId) {
        errors.check(() -> PageDtos.applyWrite(new WeblogPage(), body, cleared));
        checkImageId(errors, weblog, "ogImageId", body.ogImageId(), storedOgImageId);
    }

    /**
     * Moves the page to the trash, where it stays restorable for
     * {@code page.trash.retention.days}. It stops being served and stops
     * being the front door; it keeps its slug and, for a strand, its parts.
     * Deleting for good is {@code delete-forever}, from the trash only. A
     * page already in the trash is a 409, so a retried DELETE does not move
     * the purge clock.
     */
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(HttpServletRequest request, @PathVariable("id") String id)
            throws WebloggerException {
        WeblogPage page = requirePage(request, id, "Page is already in the trash.");
        weblogger.getWeblogPageManager().trashPage(page);
        weblogger.flush();
        return ResponseEntity.noContent().build();
    }

    /**
     * Brings a trashed page back, always as a DRAFT: restoring republishes
     * nothing, and a page that was the front door is not made the front door
     * again. A page that is not trashed is a 409.
     */
    @PostMapping("/{id}/restore")
    public PageDtos.PageView restore(HttpServletRequest request, @PathVariable("id") String id)
            throws WebloggerException {
        WeblogPage page = requireTrashedPage(request, id, "Page is not trashed.");
        weblogger.getWeblogPageManager().restorePage(page);
        weblogger.flush();
        return view(page);
    }

    /**
     * Permanently deletes an already-trashed page; a strand's parts stay
     * published, outside any strand. A second, deliberate step after
     * trashing, never a way around it: a live page's id is a 409.
     */
    @PostMapping("/{id}/delete-forever")
    public ResponseEntity<Void> deleteForever(HttpServletRequest request, @PathVariable("id") String id)
            throws WebloggerException {
        WeblogPage page = requireTrashedPage(request, id, "Page is not trashed; trash it first.");
        weblogger.getWeblogPageManager().removePage(page);
        weblogger.flush();
        return ResponseEntity.noContent().build();
    }

    /**
     * The page with this id, but only when it belongs to the action weblog
     * and is not in the trash -- delegates to {@link WeblogOwnership#page},
     * this codebase's one IDOR defense for by-id page lookups, the fourth
     * family member alongside entry/category/template. A trashed page is a
     * 404 here, as an unknown one is: it is read from the trash list.
     */
    private WeblogPage requirePage(HttpServletRequest request, String id) {
        WeblogPage page = WeblogOwnership.page(weblogger, id, requireActionWeblog(request));
        if (page == null) {
            throw ApiException.notFound("No such page.");
        }
        return page;
    }

    /** A write's lookup: as {@link #requirePage}, but a page of this weblog that is in the trash is a 409 saying so. */
    private WeblogPage requireLivePageForWrite(HttpServletRequest request, String id) {
        return requirePage(request, id, "Page is in the trash; restore it first.");
    }

    private WeblogPage requirePage(HttpServletRequest request, String id, String whenTrashed) {
        Weblog weblog = requireActionWeblog(request);
        WeblogPage page = WeblogOwnership.page(weblogger, id, weblog);
        if (page != null) {
            return page;
        }
        if (WeblogOwnership.trashedPage(weblogger, id, weblog) != null) {
            throw ApiException.conflict(whenTrashed);
        }
        throw ApiException.notFound("No such page.");
    }

    /**
     * The trash's lookup ({@link WeblogOwnership#trashedPage}): a page of
     * this weblog that is not trashed is a 409 with {@code whenLive}; another
     * weblog's page and an unknown id are one 404.
     */
    private WeblogPage requireTrashedPage(HttpServletRequest request, String id, String whenLive) {
        Weblog weblog = requireActionWeblog(request);
        WeblogPage page = WeblogOwnership.trashedPage(weblogger, id, weblog);
        if (page != null) {
            return page;
        }
        if (WeblogOwnership.page(weblogger, id, weblog) != null) {
            throw ApiException.conflict(whenLive);
        }
        throw ApiException.notFound("No such page.");
    }

    /**
     * {@code savePage} does not itself check for a duplicate slug -- unlike
     * the blank/reserved/contains-'/' checks, a colliding slug would reach
     * the database's unique constraint and bubble up as a bare {@code
     * WebloggerException}, the same opaque-500 shape {@code CategoriesApi}
     * guards duplicate names against. {@code excludingId} is null on create
     * and the page's own id on update, so a PATCH that leaves the slug
     * unchanged is not refused as colliding with itself.
     */
    private void requireSlugAvailable(Weblog weblog, String slug, String excludingId) throws WebloggerException {
        WeblogPage existing = weblogger.getWeblogPageManager().getPageBySlug(weblog, slug);
        if (existing != null && !existing.getId().equals(excludingId)) {
            // A trashed page keeps its address until it is restored or
            // deleted for good; say so, since the pages list will not show it.
            throw ApiException.conflictOnField("slug", existing.getStatus() == WeblogPage.PubStatus.TRASHED
                    ? "A page in the trash holds slug '" + slug + "'. Restore it or delete it forever first."
                    : "A page with slug '" + slug + "' already exists.");
        }
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
