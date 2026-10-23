package org.apache.roller.weblogger.ui.restapi.v1;

import jakarta.servlet.http.HttpServletRequest;
import java.util.ArrayList;
import java.util.List;
import org.apache.roller.weblogger.WebloggerException;
import org.apache.roller.weblogger.business.WeblogEntryManager;
import org.apache.roller.weblogger.pojos.TagStat;
import org.apache.roller.weblogger.pojos.Weblog;
import org.apache.roller.weblogger.pojos.WeblogCategory;
import org.apache.roller.weblogger.pojos.WeblogEntrySearchCriteria;
import org.apache.roller.weblogger.pojos.WeblogPermission;
import org.apache.roller.weblogger.ui.controllers.UISecurityEnforced;
import org.apache.roller.weblogger.ui.controllers.WeblogOwnership;
import org.apache.roller.weblogger.ui.restapi.ApiException;
import org.apache.roller.weblogger.ui.restapi.ColumnLimits;
import org.apache.roller.weblogger.ui.restapi.dto.CategoryDtos;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Category CRUD and the tag list, both blog-wide structure rather than a
 * single draft -- {@code UISecurityEnforced} declares {@code
 * WeblogPermission.POST}, the same level {@code CategoryEditController}/
 * {@code CategoryRemoveController} require for the JSP admin UI (see
 * {@code ControllerMetadataTest}), not the looser {@code EDIT_DRAFT} entry
 * reads use. {@code RollerHandlerInterceptor} enforces it; this controller
 * adds no permission checking of its own.
 *
 * <p>Mapped at the weblog root rather than at {@code .../categories} so a
 * sibling {@code GET .../tags} can live on the same controller without a
 * second file -- {@code /categories/**} and {@code /tags} are two independent
 * suffixes under the same {@code {handle}} prefix, not a parent/child pair.
 */
@RestController
@RequestMapping("/v1/weblogs/{handle}")
public class CategoriesApi extends BaseApiController implements UISecurityEnforced {

    @GetMapping("/categories")
    public List<CategoryDtos.CategoryView> list(HttpServletRequest request) throws WebloggerException {
        Weblog weblog = requireActionWeblog(request);
        List<CategoryDtos.CategoryView> views = new ArrayList<>();
        for (WeblogCategory category : weblogger.getWeblogEntryManager().getWeblogCategories(weblog)) {
            views.add(CategoryDtos.toView(category, entryCount(category)));
        }
        return views;
    }

    @PostMapping("/categories")
    public ResponseEntity<CategoryDtos.CategoryView> create(
            HttpServletRequest request, @RequestBody CategoryDtos.CategoryWrite body) throws WebloggerException {
        Weblog weblog = requireActionWeblog(request);
        String name = body.name() == null ? null : body.name().trim();
        if (name == null || name.isBlank()) {
            throw ApiException.invalidField("name", "name is required.");
        }
        ColumnLimits.requireMaxLength("name", name, ColumnLimits.CATEGORY_NAME);
        if (body.description() != null) {
            ColumnLimits.requireMaxLength("description", body.description(), ColumnLimits.CATEGORY_DESCRIPTION);
        }
        if (weblog.hasCategory(name)) {
            throw ApiException.conflictOnField("name", "A category named '" + name + "' already exists.");
        }

        WeblogCategory category = new WeblogCategory(weblog, name, body.description(), null);
        weblogger.getWeblogEntryManager().saveWeblogCategory(category);
        weblogger.flush();

        return ResponseEntity.status(HttpStatus.CREATED).body(CategoryDtos.toView(category, 0));
    }

    @PatchMapping("/categories/{id}")
    public CategoryDtos.CategoryView update(
            HttpServletRequest request, @PathVariable("id") String id,
            @RequestBody CategoryDtos.CategoryWrite body) throws WebloggerException {
        Weblog weblog = requireActionWeblog(request);
        WeblogCategory category = WeblogOwnership.category(weblogger, id, weblog);
        if (category == null) {
            throw ApiException.notFound("No such category.");
        }

        if (body.name() != null) {
            String name = body.name().trim();
            if (name.isBlank()) {
                throw ApiException.invalidField("name", "name cannot be blank.");
            }
            ColumnLimits.requireMaxLength("name", name, ColumnLimits.CATEGORY_NAME);
            if (!name.equals(category.getName())) {
                // The entity has no category-by-name lookup of its own (it
                // would need the manager, which entities do not hold), so go
                // straight through this controller's injected manager
                // instead, same as every other lookup in this controller.
                WeblogCategory existing =
                        weblogger.getWeblogEntryManager().getWeblogCategoryByName(weblog, name);
                if (existing != null && !existing.getId().equals(category.getId())) {
                    throw ApiException.conflictOnField("name", "A category named '" + name + "' already exists.");
                }
                category.setName(name);
            }
        }
        if (body.description() != null) {
            ColumnLimits.requireMaxLength("description", body.description(), ColumnLimits.CATEGORY_DESCRIPTION);
            category.setDescription(body.description());
        }

        weblogger.getWeblogEntryManager().saveWeblogCategory(category);
        weblogger.flush();

        return CategoryDtos.toView(category, entryCount(category));
    }

    /**
     * Both {@code id} and {@code moveTo} are client input, and {@code
     * WeblogEntryManager.getWeblogCategory} is a global by-id lookup -- an
     * ownership check on {@code id} alone would still let a foreign {@code
     * moveTo} silently re-file this weblog's entries into another weblog's
     * category. Both go through {@link WeblogOwnership#category}, and a null
     * result on either is 404.
     */
    @DeleteMapping("/categories/{id}")
    public ResponseEntity<Void> delete(
            HttpServletRequest request, @PathVariable("id") String id,
            @RequestParam(value = "moveTo", required = false) String moveTo) throws WebloggerException {
        Weblog weblog = requireActionWeblog(request);
        WeblogCategory category = WeblogOwnership.category(weblogger, id, weblog);
        if (category == null) {
            throw ApiException.notFound("No such category.");
        }

        if (weblog.getWeblogCategories().size() <= 1) {
            // saveWeblogEntry() falls back to "the first category found" for
            // an entry with none, so a weblog left with zero categories can
            // no longer accept a save at all -- same guard
            // CategoryRemoveController applies before removeWeblogCategory
            // (which would otherwise refuse with a bare WebloggerException).
            throw ApiException.conflict("A weblog must keep at least one category.");
        }

        WeblogEntryManager wmgr = weblogger.getWeblogEntryManager();
        if (moveTo != null && !moveTo.isBlank()) {
            // moveTo naming the SAME category being deleted passes
            // WeblogOwnership.category (it genuinely belongs to this
            // weblog), so without this check moveWeblogCategoryContents
            // would be a self-move no-op and removeWeblogCategory would
            // then throw a bare WebloggerException -- the same opaque-500
            // shape the last-category and in-use guards above exist to
            // intercept.
            if (moveTo.equals(id)) {
                throw ApiException.invalidField("moveTo", "moveTo cannot name the category being deleted.");
            }
            WeblogCategory target = WeblogOwnership.category(weblogger, moveTo, weblog);
            if (target == null) {
                throw ApiException.notFound("No such category to move entries to.");
            }
            wmgr.moveWeblogCategoryContents(category, target);
            weblogger.flush();
        } else if (wmgr.isWeblogCategoryInUse(category)) {
            throw ApiException.conflict(
                    "Category has entries; pass moveTo to move them to another category first.");
        }

        wmgr.removeWeblogCategory(category);
        weblogger.flush();
        return ResponseEntity.noContent().build();
    }

    /**
     * The weblog's tags with counts, from the same {@code getTags} query the
     * tag cloud uses -- no offset/limit, since a per-weblog tag cloud is
     * small enough that pagination has never been worth the API surface.
     */
    @GetMapping("/tags")
    public List<CategoryDtos.TagView> tags(HttpServletRequest request) throws WebloggerException {
        Weblog weblog = requireActionWeblog(request);
        List<TagStat> stats = weblogger.getWeblogEntryManager().getTags(weblog, null, null, 0, -1);
        return stats.stream().map(CategoryDtos::toView).toList();
    }

    /**
     * Not a stored column -- counted per request from the same {@code
     * WeblogEntrySearchCriteria} path every other filtered read in this API
     * uses, so it inherits that path's default trash exclusion rather than
     * counting entries a reader could never see.
     */
    private int entryCount(WeblogCategory category) throws WebloggerException {
        WeblogEntrySearchCriteria criteria = new WeblogEntrySearchCriteria();
        criteria.setWeblog(category.getWeblog());
        criteria.setCatName(category.getName());
        return weblogger.getWeblogEntryManager().getWeblogEntries(criteria).size();
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
