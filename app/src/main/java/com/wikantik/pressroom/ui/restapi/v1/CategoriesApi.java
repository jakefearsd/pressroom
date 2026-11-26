package com.wikantik.pressroom.ui.restapi.v1;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import org.apache.commons.lang3.StringUtils;
import com.wikantik.pressroom.WebloggerException;
import com.wikantik.pressroom.business.DuplicateCategoryNameException;
import com.wikantik.pressroom.business.WeblogEntryManager;
import com.wikantik.pressroom.pojos.Weblog;
import com.wikantik.pressroom.pojos.WeblogCategory;
import com.wikantik.pressroom.pojos.WeblogPermission;
import com.wikantik.pressroom.ui.controllers.CategoryRules;
import com.wikantik.pressroom.ui.controllers.RuleViolation;
import com.wikantik.pressroom.ui.controllers.UISecurityEnforced;
import com.wikantik.pressroom.ui.controllers.WeblogOwnership;
import com.wikantik.pressroom.ui.restapi.ApiException;
import com.wikantik.pressroom.ui.restapi.ColumnLimits;
import com.wikantik.pressroom.ui.restapi.ProblemCode;
import com.wikantik.pressroom.ui.restapi.RuleProblems;
import com.wikantik.pressroom.ui.restapi.dto.CategoryDtos;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Category changes: create, rename or describe, delete. Blog-wide structure
 * rather than a single draft -- {@code UISecurityEnforced} declares {@code
 * WeblogPermission.POST}, the same level {@code CategoryEditController}/
 * {@code CategoryRemoveController} require for the JSP admin UI (see
 * {@code ControllerMetadataTest}). {@code PressroomHandlerInterceptor}
 * enforces it; this controller adds no permission checking of its own.
 * Reading the categories and tags is a contributor's too, as the entry
 * editor shows them, so those reads live in {@link CategoriesReadApi}.
 */
@Tag(name = "Category writes", description = "Changing a weblog's categories: create, rename or describe, and "
        + "delete. Needs publish rights, as the Categories page does.")
@RestController
@RequestMapping("/v1/weblogs/{handle}")
public class CategoriesApi extends BaseApiController implements UISecurityEnforced {

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
        refuse(CategoryRules.nameShapeProblem(name));
        refuse(CategoryRules.descriptionProblem(body.description()));
        String image = body.image() == null ? null : imageOf(body.image());
        refuse(CategoryRules.nameTaken(weblog, name, null, weblogger.getWeblogEntryManager()));

        WeblogCategory category = new WeblogCategory(weblog, name, body.description(), image);
        try {
            weblogger.getWeblogEntryManager().saveWeblogCategory(category);
        } catch (DuplicateCategoryNameException e) {
            // An overlapping create took the name after the check above.
            throw ApiException.problemOnField("name", ProblemCode.CATEGORY_NAME_IN_USE, e, name);
        }
        weblogger.flush();

        return ResponseEntity.status(HttpStatus.CREATED).body(CategoryDtos.toView(category, 0));
    }

    /**
     * The image to store: null for a blank (removing it is a blank, as on the form), else the trimmed URL once it
     * fits the column and is an absolute http(s) URL, or a 400 on {@code image}.
     */
    private static String imageOf(String raw) {
        String image = StringUtils.trimToNull(raw);
        ColumnLimits.requireMaxLength("image", image, ColumnLimits.CATEGORY_IMAGE);
        if (image != null && !CategoryRules.isValidImageUrl(image)) {
            throw ApiException.problemOnField("image", ProblemCode.CATEGORY_IMAGE_INVALID);
        }
        return image;
    }

    /** Raises {@code violation}, when there is one, on its field. */
    private static void refuse(RuleViolation violation) {
        if (violation != null) {
            throw RuleProblems.problem(violation, true);
        }
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

        // Checked before anything is applied to the managed category: a refused write changes nothing.
        String image = body.image() == null ? null : imageOf(body.image());
        if (body.name() != null) {
            String name = body.name().trim();
            if (name.isBlank()) {
                throw ApiException.problemOnField("name", ProblemCode.FIELD_BLANK, "name");
            }
            if (!CategoryRules.keepsItsName(category, name)) {
                refuse(CategoryRules.nameShapeProblem(name));
                // The entity has no category-by-name lookup of its own (it would need the manager, which
                // entities do not hold), so the rule goes through this controller's injected manager.
                refuse(CategoryRules.nameTaken(weblog, name, category.getId(), weblogger.getWeblogEntryManager()));
                category.setName(name);
            }
        }
        if (body.description() != null) {
            refuse(CategoryRules.descriptionProblem(body.description()));
            category.setDescription(body.description());
        }
        if (body.image() != null) {
            category.setImage(image);
        }

        weblogger.getWeblogEntryManager().saveWeblogCategory(category);
        weblogger.flush();

        return CategoryDtos.toView(category, CategoriesReadApi.entryCount(weblogger, category));
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

        WeblogEntryManager wmgr = weblogger.getWeblogEntryManager();
        // The last category (a weblog with none can no longer save an entry), moveTo naming the category
        // itself (a self-move is a no-op and the removal would then fail as a bare 500) and entries left
        // behind with no moveTo are the rule's; only moveTo's own lookup follows it.
        RuleViolation refusal = CategoryRules.removalProblem(weblog, category, moveTo, wmgr);
        if (refusal != null) {
            throw RuleProblems.problem(refusal, refusal.code() == ProblemCode.CATEGORY_MOVE_TO_SELF);
        }
        if (StringUtils.isNotBlank(moveTo)) {
            WeblogCategory target = WeblogOwnership.category(weblogger, moveTo, weblog);
            if (target == null) {
                throw ApiException.problem(ProblemCode.CATEGORY_MOVE_TARGET_NOT_FOUND);
            }
            // One unit of work: a removal that fails rolls the move back too.
            wmgr.removeWeblogCategory(category, target);
        } else {
            wmgr.removeWeblogCategory(category);
        }
        weblogger.flush();
        return ResponseEntity.noContent().build();
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
