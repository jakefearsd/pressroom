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

    @GetMapping("")
    public List<PageDtos.PageView> list(HttpServletRequest request) throws WebloggerException {
        Weblog weblog = requireActionWeblog(request);
        List<PageDtos.PageView> views = new ArrayList<>();
        for (WeblogPage page : weblogger.getWeblogPageManager().getPages(weblog)) {
            views.add(view(page));
        }
        return views;
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
        String[] slug = new String[1];
        errors.check("slug", () -> slug[0] = PageDtos.requireUsableSlug(body.slug()));
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

        weblogger.getWeblogPageManager().savePage(page);
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
        WeblogPage page = requirePage(request, id);

        FieldErrors errors = new FieldErrors();
        if (body.title() != null && body.title().isBlank()) {
            errors.add("title", "title cannot be blank.");
        }
        String[] slug = new String[1];
        if (body.slug() != null) {
            errors.check("slug", () -> slug[0] = PageDtos.requireUsableSlug(body.slug()));
        }
        Set<String> cleared = new HashSet<>();
        errors.check("clear", () -> cleared.addAll(
                ClearFields.validate(body.clear(), body, PageDtos.PAGE_CLEARABLE, List.of())));
        checkWrite(errors, page.getWeblog(), body, cleared, page.getOgImageId());
        checkStrand(errors, page.getWeblog(), page, body.strand());
        errors.throwIfAny();
        if (slug[0] != null) {
            requireSlugAvailable(page.getWeblog(), slug[0], page.getId());
        }

        PageDtos.applyWrite(page, body, cleared);
        if (slug[0] != null) {
            page.setSlug(slug[0]);
        }

        weblogger.getWeblogPageManager().savePage(page);
        weblogger.flush();

        return view(page);
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
        WeblogPage page = requirePage(request, id);
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

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(HttpServletRequest request, @PathVariable("id") String id)
            throws WebloggerException {
        WeblogPage page = requirePage(request, id);
        weblogger.getWeblogPageManager().removePage(page);
        weblogger.flush();
        return ResponseEntity.noContent().build();
    }

    /**
     * The page with this id, but only when it belongs to the action weblog
     * -- delegates to {@link WeblogOwnership#page}, this codebase's one IDOR
     * defense for by-id page lookups, the fourth family member alongside
     * entry/category/template.
     */
    private WeblogPage requirePage(HttpServletRequest request, String id) {
        WeblogPage page = WeblogOwnership.page(weblogger, id, requireActionWeblog(request));
        if (page == null) {
            throw ApiException.notFound("No such page.");
        }
        return page;
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
            throw ApiException.conflictOnField("slug", "A page with slug '" + slug + "' already exists.");
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
