package org.apache.roller.weblogger.ui.restapi.v1;

import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import java.util.List;
import org.apache.roller.weblogger.WebloggerException;
import org.apache.roller.weblogger.pojos.Weblog;
import org.apache.roller.weblogger.pojos.WeblogPage;
import org.apache.roller.weblogger.pojos.WeblogPermission;
import org.apache.roller.weblogger.ui.controllers.UISecurityEnforced;
import org.apache.roller.weblogger.ui.controllers.WeblogOwnership;
import org.apache.roller.weblogger.ui.restapi.ApiException;
import org.apache.roller.weblogger.ui.restapi.dto.PageDtos;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

/**
 * Static page CRUD. {@code UISecurityEnforced} declares
 * {@code WeblogPermission.POST} -- pages are blog-wide structure, like
 * categories, not a single draft -- so {@code RollerHandlerInterceptor} is
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

    @GetMapping("")
    public List<PageDtos.PageView> list(HttpServletRequest request) throws WebloggerException {
        Weblog weblog = requireActionWeblog(request);
        return weblogger.getWeblogPageManager().getPages(weblog).stream()
                .map(PageDtos::toView)
                .toList();
    }

    @GetMapping("/{id}")
    public PageDtos.PageView get(HttpServletRequest request, @PathVariable("id") String id) {
        return PageDtos.toView(requirePage(request, id));
    }

    @PostMapping("")
    public ResponseEntity<PageDtos.PageView> create(
            HttpServletRequest request, @RequestBody PageDtos.PageWrite body) throws WebloggerException {
        Weblog weblog = requireActionWeblog(request);

        // title is not a NOT NULL column the way an entry's is, but a page
        // with no title at all is not a resource an API caller meant to
        // create -- same reasoning as EntriesWriteApi.requireTitleAndText,
        // applied on create only; a PATCH omitting title leaves the stored
        // one alone per applyWrite's "null means absent" contract.
        if (body.title() == null || body.title().isBlank()) {
            throw ApiException.invalidField("title", "title is required.");
        }
        String slug = PageDtos.requireUsableSlug(body.slug());
        requireSlugAvailable(weblog, slug, null);

        WeblogPage page = new WeblogPage();
        page.setWeblog(weblog);
        page.setSlug(slug);
        PageDtos.applyWrite(page, body);

        weblogger.getWeblogPageManager().savePage(page);
        weblogger.flush();

        URI location = ServletUriComponentsBuilder.fromRequestUri(request)
                .path("/{id}")
                .buildAndExpand(page.getId())
                .toUri();
        return ResponseEntity.created(location).body(PageDtos.toView(page));
    }

    @PatchMapping("/{id}")
    public PageDtos.PageView update(
            HttpServletRequest request, @PathVariable("id") String id,
            @RequestBody PageDtos.PageWrite body) throws WebloggerException {
        WeblogPage page = requirePage(request, id);

        if (body.title() != null && body.title().isBlank()) {
            throw ApiException.invalidField("title", "title cannot be blank.");
        }
        if (body.slug() != null) {
            String slug = PageDtos.requireUsableSlug(body.slug());
            requireSlugAvailable(page.getWeblog(), slug, page.getId());
        }

        PageDtos.applyWrite(page, body);

        weblogger.getWeblogPageManager().savePage(page);
        weblogger.flush();

        return PageDtos.toView(page);
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
