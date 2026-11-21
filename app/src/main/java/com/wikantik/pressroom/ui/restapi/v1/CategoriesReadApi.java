package com.wikantik.pressroom.ui.restapi.v1;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import java.util.ArrayList;
import java.util.List;
import com.wikantik.pressroom.WebloggerException;
import com.wikantik.pressroom.business.Weblogger;
import com.wikantik.pressroom.pojos.TagStat;
import com.wikantik.pressroom.pojos.Weblog;
import com.wikantik.pressroom.pojos.WeblogCategory;
import com.wikantik.pressroom.pojos.WeblogPermission;
import com.wikantik.pressroom.ui.controllers.UISecurityEnforced;
import com.wikantik.pressroom.ui.restapi.dto.CategoryDtos;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Reading a weblog's categories and tags. {@code UISecurityEnforced} declares
 * {@code WeblogPermission.EDIT_DRAFT}: the entry editor shows a contributor
 * both (its category picker and tag field), so a contributor's token reads
 * them too (owner decisions, editor parity). Changing a category stays a
 * publisher's, in {@link CategoriesApi}, as on the Categories page; the
 * declared permission is per controller, which is why the reads live here.
 * {@code PressroomHandlerInterceptor} enforces it; this controller adds no
 * permission checking of its own.
 */
@Tag(name = "Categories and tags", description = "Reading a weblog's categories and the tags its entries carry. A "
        + "contributor may, as the entry editor shows them.")
@RestController
@RequestMapping("/v1/weblogs/{handle}")
public class CategoriesReadApi extends BaseApiController implements UISecurityEnforced {

    @Operation(operationId = "categoriesList")
    @GetMapping("/categories")
    public List<CategoryDtos.CategoryView> list(HttpServletRequest request) throws WebloggerException {
        Weblog weblog = requireActionWeblog(request);
        List<CategoryDtos.CategoryView> views = new ArrayList<>();
        for (WeblogCategory category : weblogger.getWeblogEntryManager().getWeblogCategories(weblog)) {
            views.add(CategoryDtos.toView(category, entryCount(weblogger, category)));
        }
        return views;
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
     * {@link CategoriesApi} answers a change with the same count.
     */
    static int entryCount(Weblogger weblogger, WeblogCategory category) throws WebloggerException {
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
        return List.of(WeblogPermission.EDIT_DRAFT);
    }

    @Override
    public List<String> requiredGlobalPermissionActions() {
        return List.of();
    }
}
