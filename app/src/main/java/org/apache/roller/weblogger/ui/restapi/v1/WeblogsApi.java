package org.apache.roller.weblogger.ui.restapi.v1;

import jakarta.servlet.http.HttpServletRequest;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.apache.roller.weblogger.WebloggerException;
import org.apache.roller.weblogger.pojos.GlobalPermission;
import org.apache.roller.weblogger.pojos.Weblog;
import org.apache.roller.weblogger.ui.controllers.WeblogSettingsRules;
import org.apache.roller.weblogger.ui.controllers.UISecurityEnforced;
import org.apache.roller.weblogger.ui.restapi.ApiException;
import org.apache.roller.weblogger.ui.restapi.auth.AdminScoped;
import org.apache.roller.weblogger.ui.controllers.editor.WeblogConfigBean;
import org.apache.roller.weblogger.ui.restapi.dto.AdminDtos;
import org.apache.roller.weblogger.ui.restapi.dto.ClearFields;
import org.apache.roller.weblogger.util.cache.CacheManager;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Site-wide weblog administration: list every weblog on the site, and view
 * or edit any one of them by handle -- not the weblog-scoped content APIs
 * ({@code EntriesApi}, {@code CategoriesApi}, {@code MediaApi}), which act
 * only on the weblog a token is scoped to. {@code UISecurityEnforced}
 * declares {@code GlobalPermission.ADMIN} (not a {@code WeblogPermission}):
 * this reaches ANY weblog by handle regardless of who owns it, the same
 * capability {@code GET/PATCH /v1/admin/users} has over accounts, just
 * addressed as {@code /v1/weblogs/{handle}} rather than
 * {@code /v1/admin/weblogs/{handle}} because the weblog -- not "admin" -- is
 * the REST resource. {@code isWeblogRequired()} is {@code false}: the list
 * endpoint carries no {@code {handle}}, so the interceptor must never demand
 * one before dispatching there; {@code get}/{@code update} resolve their own
 * {@code {handle}} through {@link BaseApiController#requireActionWeblog}
 * regardless.
 *
 * <p>{@code update} lays the PATCH over the settings form's own bean and judges it with the
 * form's shared {@code WeblogSettingsRules}, so the two surfaces cannot drift.
 *
 * <p><b>{@code @AdminScoped} despite living outside {@code /v1/admin}.</b>
 * {@code AdminScopedCoverageTest} does not require it here -- it only scans
 * for the {@code /v1/admin} URL prefix -- but the capability is exactly as
 * sensitive as {@code AdminApi}'s: a token minted for routine, single-weblog
 * automation (POST-role, scoped to one weblog) must not be able to use this
 * controller to reconfigure or enumerate every weblog on the site just
 * because the user behind it happens to hold {@code GlobalPermission.ADMIN}.
 * {@code ApiScopeInterceptor} reads {@code @AdminScoped} off the class, not
 * the route, so it applies here the same as on {@code AdminApi}/{@code
 * AdminActionsApi}.
 */
@RestController
@RequestMapping("/v1/weblogs")
@AdminScoped
public class WeblogsApi extends BaseApiController implements UISecurityEnforced {

    private static final int MAX_LIMIT = 200;

    @GetMapping("")
    public List<AdminDtos.WeblogView> list(
            @RequestParam(value = "offset", defaultValue = "0") int offset,
            @RequestParam(value = "limit", defaultValue = "50") int limit) throws WebloggerException {
        // Same negative-limit/offset trap as every other list endpoint in
        // this API -- see EntriesApi.list and AdminApi.listUsers.
        if (limit < 1 || offset < 0) {
            throw ApiException.badRequest("limit must be at least 1 and offset must not be negative.");
        }
        int boundedLimit = Math.min(limit, MAX_LIMIT);

        List<AdminDtos.WeblogView> views = new ArrayList<>();
        for (Weblog weblog : weblogger.getWeblogManager()
                .getWeblogs(null, null, null, null, offset, boundedLimit)) {
            views.add(AdminDtos.toView(weblog));
        }
        return views;
    }

    @GetMapping("/{handle}")
    public AdminDtos.WeblogView get(HttpServletRequest request) {
        return AdminDtos.toView(requireActionWeblog(request));
    }

    @PatchMapping("/{handle}")
    public AdminDtos.WeblogView update(HttpServletRequest request, @RequestBody AdminDtos.WeblogPatch body)
            throws WebloggerException {
        Weblog weblog = requireActionWeblog(request);
        Set<String> cleared = ClearFields.validate(body.clear(), body,
                AdminDtos.WEBLOG_CLEARABLE, AdminDtos.WEBLOG_PAIRS);

        // The settings form's own bean carries the change, so the form's copyTo stores it:
        // one way to store each value, whichever surface changed it.
        WeblogConfigBean bean = new WeblogConfigBean();
        bean.copyFrom(weblog);
        Set<String> touched = AdminDtos.applyPatch(bean, body, cleared);

        List<WeblogSettingsRules.Violation> broken = WeblogSettingsRules.check(bean,
                WeblogSettingsRules.Context.current(weblog.getHandle(), weblogger)).stream()
                .filter(v -> v.involvesAny(touched))
                .toList();
        if (!broken.isEmpty()) {
            String detail = broken.stream().map(WeblogSettingsRules.Violation::detail)
                    .collect(Collectors.joining(" "));
            throw broken.stream().allMatch(WeblogSettingsRules.Violation::conflict)
                    ? ApiException.conflict(detail) : ApiException.badRequest(detail);
        }

        bean.copyTo(weblog, weblogger.getBusinessManager());
        weblogger.getWeblogManager().saveWeblog(weblog);
        weblogger.flush();
        // WeblogPageCache expires only against weblog.lastModified; invalidate bumps it.
        CacheManager.invalidate(weblog);
        return AdminDtos.toView(weblog);
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
