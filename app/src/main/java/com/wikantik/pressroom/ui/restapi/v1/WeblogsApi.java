package com.wikantik.pressroom.ui.restapi.v1;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.ArrayList;
import java.util.List;
import com.wikantik.pressroom.WebloggerException;
import com.wikantik.pressroom.pojos.GlobalPermission;
import com.wikantik.pressroom.pojos.Weblog;
import com.wikantik.pressroom.ui.controllers.UISecurityEnforced;
import com.wikantik.pressroom.ui.restapi.Paging;
import com.wikantik.pressroom.ui.restapi.auth.AdminScoped;
import com.wikantik.pressroom.ui.restapi.dto.AdminDtos;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The site-wide weblog list, for site admins: {@code UISecurityEnforced}
 * declares {@code GlobalPermission.ADMIN}, and {@code isWeblogRequired()} is
 * {@code false} because the route carries no {@code {handle}}. One weblog's
 * settings, by handle, are {@link WeblogSettingsApi}, which takes the
 * Weblog Settings page's own permission (the blog's owner); permission
 * declarations are per controller class, hence two.
 *
 * <p><b>{@code @AdminScoped} despite living outside {@code /v1/admin}.</b>
 * {@code AdminScopedCoverageTest} does not require it here -- it only scans
 * for the {@code /v1/admin} URL prefix -- but enumerating every weblog on the
 * site is as sensitive as {@code AdminApi}'s lists: a token minted for
 * routine, single-weblog automation (POST-role) must not use it just because
 * the user behind it happens to hold {@code GlobalPermission.ADMIN}. A
 * pinned token gets 404 here, since the route names no weblog.
 * {@code ApiScopeInterceptor} reads {@code @AdminScoped} off the class, not
 * the route.
 */
@Tag(name = "Weblogs", description = "Every weblog on the site (site admins).")
@RestController
@RequestMapping("/v1/weblogs")
@AdminScoped
public class WeblogsApi extends BaseApiController implements UISecurityEnforced {

    // 200 is the documented cap for every list endpoint (docs/api/README.md, Pagination); the cap is
    // repeated in each controller, so change all of them together.
    private static final int MAX_LIMIT = Paging.MAX_LIMIT;

    @Operation(operationId = "weblogsList")
    @GetMapping("")
    public AdminDtos.WeblogListPage list(
            @RequestParam(value = "offset", defaultValue = "0") int offset,
            @RequestParam(value = "limit", defaultValue = "50") int limit) throws WebloggerException {
        // Same negative-limit/offset trap as every other list endpoint in
        // this API -- see EntriesApi.list and AdminApi.listUsers.
        Paging.requireRange(limit, offset);
        int boundedLimit = Math.min(limit, MAX_LIMIT);

        // One row past the page decides hasMore: no count query.
        List<Weblog> rows = weblogger.getWeblogManager()
                .getWeblogs(null, null, null, null, offset, boundedLimit + 1);
        List<AdminDtos.WeblogView> views = new ArrayList<>();
        for (Weblog weblog : rows.subList(0, Math.min(rows.size(), boundedLimit))) {
            views.add(AdminDtos.toView(weblog));
        }
        return new AdminDtos.WeblogListPage(views, offset, boundedLimit, rows.size() > boundedLimit);
    }

    @Override
    public boolean isUserRequired() {
        return true;
    }

    @Override
    public boolean isWeblogRequired() {
        return false;
    }

    @Override
    public List<String> requiredWeblogPermissionActions() {
        return List.of();
    }

    @Override
    public List<String> requiredGlobalPermissionActions() {
        return List.of(GlobalPermission.ADMIN);
    }
}
