package com.wikantik.pressroom.ui.restapi.v1;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import java.util.ArrayList;
import java.util.List;
import com.wikantik.pressroom.WebloggerException;
import com.wikantik.pressroom.business.DuplicateCategoryNameException;
import com.wikantik.pressroom.business.WeblogEntryManager;
import com.wikantik.pressroom.pojos.TagStat;
import com.wikantik.pressroom.pojos.Weblog;
import com.wikantik.pressroom.pojos.WeblogCategory;
import com.wikantik.pressroom.pojos.WeblogPermission;
import com.wikantik.pressroom.ui.controllers.UISecurityEnforced;
import com.wikantik.pressroom.ui.controllers.WeblogOwnership;
import com.wikantik.pressroom.ui.restapi.ApiException;
import com.wikantik.pressroom.ui.restapi.ColumnLimits;
import com.wikantik.pressroom.ui.restapi.dto.CategoryDtos;
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
import com.wikantik.pressroom.ui.restapi.ProblemCode;

/**
 * Category CRUD and the tag list, both blog-wide structure rather than a
 * single draft -- {@code UISecurityEnforced} declares {@code
 * WeblogPermission.POST}, the same level {@code CategoryEditController}/
 * {@code CategoryRemoveController} require for the JSP admin UI (see
 * {@code ControllerMetadataTest}), not the looser {@code EDIT_DRAFT} entry
 * reads use. {@code PressroomHandlerInterceptor} enforces it; this controller
 * adds no permission checking of its own.
 *
 * <p>Mapped at the weblog root rather than at {@code .../categories} so a
 * sibling {@code GET .../tags} can live on the same controller without a
 * second file -- {@code /categories/**} and {@code /tags} are two independent
 * suffixes under the same {@code {handle}} prefix, not a parent/child pair.
 */
@Tag(name = "Categories and tags", description = "A weblog's categories, and the tags its entries carry.")
@RestController
@RequestMapping("/v1/weblogs/{handle}")
public class CategoriesApi extends BaseApiController implements UISecurityEnforced {

    @Operation(operationId = "categoriesList")
    @GetMapping("/categories")
    public List<CategoryDtos.CategoryView> list(HttpServletRequest request) throws WebloggerException {
        Weblog weblog = requireActionWeblog(request);
        List<CategoryDtos.CategoryView> views = new ArrayList<>();
        for (WeblogCategory category : weblogger.getWeblogEntryManager().getWeblogCategories(weblog)) {
            views.add(CategoryDtos.toView(category, entryCount(category)));
        }
        return views;
    }

    @Operation(operationId = "categoriesCreate")
    @ApiResponse(responseCode = "201", description = "Created.")
    @PostMapping("/categories")
    public ResponseEntity<CategoryDtos.CategoryView> create(
            HttpServletRequest request, @RequestBody CategoryDtos.CategoryWrite body) throws WebloggerException {
        Weblog weblog = requireActionWeblog(request);
        String name = body.name() == null ? null : body.name().trim();
        if (name == null || name.isBlank()) {
            throw ApiException.problemOnField("name", ProblemCode.FIELD_REQUIRED, "name");
        }
        ColumnLimits.requireMaxLength("name", name, ColumnLimits.CATEGORY_NAME);
        if (body.description() != null) {
            ColumnLimits.requireMaxLength("description", body.description(), ColumnLimits.CATEGORY_DESCRIPTION);
        }
        if (weblog.hasCategory(name)) {
            throw ApiException.problemOnField("name", ProblemCode.CATEGORY_NAME_IN_USE, name);
        }

        WeblogCategory category = new WeblogCategory(weblog, name, body.description(), null);
        try {
            weblogger.getWeblogEntryManager().saveWeblogCategory(category);
        } catch (DuplicateCategoryNameException e) {
            // An overlapping create took the name after the check above.
            throw ApiException.problemOnField("name", ProblemCode.CATEGORY_NAME_IN_USE, e, name);
        }
        weblogger.flush();

        return ResponseEntity.status(HttpStatus.CREATED).body(CategoryDtos.toView(category, 0));
    }

    @Operation(operationId = "categoriesUpdate")
    @PatchMapping("/categories/{id}")
    public CategoryDtos.CategoryView update(
            HttpServletRequest request, @PathVariable("id") String id,
            @RequestBody CategoryDtos.CategoryWrite body) throws WebloggerException {
        Weblog weblog = requireActionWeblog(request);
        WeblogCategory category = WeblogOwnership.category(weblogger, id, weblog);
        if (category == null) {
            throw ApiException.problem(ProblemCode.CATEGORY_NOT_FOUND);
        }

        if (body.name() != null) {
            String name = body.name().trim();
            if (name.isBlank()) {
                throw ApiException.problemOnField("name", ProblemCode.FIELD_BLANK, "name");
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
                    throw ApiException.problemOnField("name", ProblemCode.CATEGORY_NAME_IN_USE, name);
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
    @Operation(operationId = "categoriesDelete")
    @ApiResponse(responseCode = "204", description = "Done; no body.")
    @DeleteMapping("/categories/{id}")
    public ResponseEntity<Void> delete(
            HttpServletRequest request, @PathVariable("id") String id,
            @RequestParam(value = "moveTo", required = false) String moveTo) throws WebloggerException {
        Weblog weblog = requireActionWeblog(request);
        WeblogCategory category = WeblogOwnership.category(weblogger, id, weblog);
        if (category == null) {
            throw ApiException.problem(ProblemCode.CATEGORY_NOT_FOUND);
        }

        if (weblog.getWeblogCategories().size() <= 1) {
            // saveWeblogEntry() falls back to "the first category found" for
            // an entry with none, so a weblog left with zero categories can
            // no longer accept a save at all -- same guard
            // CategoryRemoveController applies before removeWeblogCategory
            // (which would otherwise refuse with a bare WebloggerException).
            throw ApiException.problem(ProblemCode.CATEGORY_LAST_ONE);
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
                throw ApiException.problemOnField("moveTo", ProblemCode.CATEGORY_MOVE_TO_SELF);
            }
            WeblogCategory target = WeblogOwnership.category(weblogger, moveTo, weblog);
            if (target == null) {
                throw ApiException.problem(ProblemCode.CATEGORY_MOVE_TARGET_NOT_FOUND);
            }
            // One unit of work: a removal that fails rolls the move back too.
            wmgr.removeWeblogCategory(category, target);
        } else if (wmgr.isWeblogCategoryInUse(category)) {
            throw ApiException.problem(ProblemCode.CATEGORY_HAS_ENTRIES);
        } else {
            wmgr.removeWeblogCategory(category);
        }
        weblogger.flush();
        return ResponseEntity.noContent().build();
    }

    /**
     * The weblog's tags with counts, from the same {@code getTags} query the
     * tag cloud uses -- no offset/limit, since a per-weblog tag cloud is
     * small enough that pagination has never been worth the API surface.
     */
    @Operation(operationId = "tagsList")
    @GetMapping("/tags")
    public List<CategoryDtos.TagView> tags(HttpServletRequest request) throws WebloggerException {
        Weblog weblog = requireActionWeblog(request);
        List<TagStat> stats = weblogger.getWeblogEntryManager().getTags(weblog, null, null, 0, -1);
        return stats.stream().map(CategoryDtos::toView).toList();
    }

    /**
     * Not a stored column -- counted per request, trash excluded as every
     * filtered read in this API excludes it by default. A count query: this
     * once loaded every entry of the category, text included, on every list.
     */
    private int entryCount(WeblogCategory category) throws WebloggerException {
        return Math.toIntExact(weblogger.getWeblogEntryManager().getLiveEntryCount(category));
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
