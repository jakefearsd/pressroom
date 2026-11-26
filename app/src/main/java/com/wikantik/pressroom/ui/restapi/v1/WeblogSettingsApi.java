package com.wikantik.pressroom.ui.restapi.v1;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Set;
import com.wikantik.pressroom.WebloggerException;
import com.wikantik.pressroom.business.themes.SharedTheme;
import com.wikantik.pressroom.pojos.GlobalPermission;
import com.wikantik.pressroom.pojos.Weblog;
import com.wikantik.pressroom.pojos.WeblogPermission;
import com.wikantik.pressroom.pojos.WeblogTheme;
import com.wikantik.pressroom.ui.controllers.SharedThemeRules;
import com.wikantik.pressroom.ui.controllers.UISecurityEnforced;
import com.wikantik.pressroom.ui.controllers.WeblogRemoval;
import com.wikantik.pressroom.ui.controllers.WeblogSettingsRules;
import com.wikantik.pressroom.ui.controllers.editor.WeblogConfigBean;
import com.wikantik.pressroom.ui.restapi.ApiException;
import com.wikantik.pressroom.ui.restapi.ProblemCode;
import com.wikantik.pressroom.ui.restapi.RuleProblems;
import com.wikantik.pressroom.ui.restapi.auth.AdminScoped;
import com.wikantik.pressroom.ui.restapi.auth.SessionOnly;
import com.wikantik.pressroom.ui.restapi.dto.AdminDtos;
import com.wikantik.pressroom.util.cache.CacheManager;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * One weblog's settings and shared theme, by handle: the Weblog Settings
 * and Theme pages over the API. It declares what those pages declare
 * ({@code BaseController}'s defaults): {@code WeblogPermission.ADMIN} on the
 * {@code {handle}} weblog, with {@code GlobalPermission.LOGIN}, so the blog's
 * owner reaches it and a site admin reaches every blog through the implied
 * weblog permission. {@code isWeblogRequired()} is {@code true}:
 * {@code PressroomHandlerInterceptor} resolves the weblog from {@code {handle}}
 * alone and refuses a caller with no role there 404, a member below ADMIN
 * 403. The site-wide list stays in {@link WeblogsApi}, site admin only;
 * permission declarations are per controller, hence two classes. Reading the weblog is the contributor's
 * ({@link WeblogReadApi}, {@code EDIT_DRAFT}); this class is what changes it.
 *
 * <p>{@code @AdminScoped}: an {@code ADMIN}-role token, as before the split.
 * A token pinned to this weblog reaches it; one pinned elsewhere gets 404,
 * as on every weblog route.
 *
 * <p>{@code update} lays the PATCH over the settings form's own bean and judges it with the
 * form's shared {@code WeblogSettingsRules}, so the two surfaces cannot drift. Its {@code theme}
 * is the Theme page's switch, through the page's own {@code SharedThemeRules}. Neither page
 * restricts any field to site admins, so neither does this.
 */
@Tag(name = "Weblog settings", description = "A weblog's settings and theme, for its owner.")
@RestController
@RequestMapping("/v1/weblogs")
@AdminScoped
public class WeblogSettingsApi extends BaseApiController implements UISecurityEnforced {

    @Operation(operationId = "weblogsUpdate")
    @PatchMapping("/{handle}")
    public AdminDtos.WeblogView update(HttpServletRequest request, @RequestBody AdminDtos.WeblogPatch body)
            throws WebloggerException {
        Weblog weblog = requireActionWeblog(request);
        Set<String> cleared = AdminDtos.WEBLOG_CLEAR.validate(body.clear(), body);
        if (body.theme() != null) {
            requireOfferedTheme(weblog, body.theme());
        }

        // The settings form's own bean carries the change, so the form's copyTo stores it:
        // one way to store each value, whichever surface changed it.
        WeblogConfigBean bean = new WeblogConfigBean();
        bean.copyFrom(weblog);
        Set<String> touched = AdminDtos.applyPatch(bean, body, cleared);

        List<WeblogSettingsRules.Violation> broken = WeblogSettingsRules.check(bean,
                WeblogSettingsRules.Context.current(weblog, weblogger)).stream()
                .filter(v -> v.involvesAny(touched))
                .toList();
        RuleProblems.refuse(broken.stream().map(v -> new RuleProblems.Broken(apiField(v.field()),
                RuleProblems.code(v.reason()), v.args(), v.conflict())).toList());

        bean.copyTo(weblog, weblogger.getBusinessManager());
        if (body.theme() != null) {
            SharedThemeRules.switchTo(weblogger.getWeblogManager(), weblog, body.theme(),
                    SharedThemeRules.currentTheme(weblogger.getThemeManager(), weblog));
        }
        weblogger.getWeblogManager().saveWeblog(weblog);
        weblogger.flush();
        // WeblogPageCache expires only against weblog.lastModified; invalidate bumps it.
        CacheManager.invalidate(weblog);
        return weblogView(weblog);
    }

    /**
     * Deletes the weblog with everything on it: irreversible, there is no trash. Session-only (SA-18); the
     * permission is the Remove Weblog page's, the weblog's {@code ADMIN}. Confirming is the client's. The
     * site space is never reachable (the API withholds it).
     */
    @Operation(operationId = "weblogsDelete", summary = "Delete the weblog and everything on it (session only).")
    @ApiResponse(responseCode = "204", description = "Done; no body.")
    @SessionOnly
    @DeleteMapping("/{handle}")
    public ResponseEntity<Void> delete(HttpServletRequest request) throws WebloggerException {
        WeblogRemoval.remove(weblogger, requireActionWeblog(request));
        return ResponseEntity.noContent().build();
    }

    /** The API names the bean's {@code frontPageId} {@code frontPage}. */
    private static String apiField(String beanField) {
        return AdminDtos.FRONT_PAGE_BEAN_FIELD.equals(beanField) ? "frontPage" : beanField;
    }

    /**
     * The theme page's rule ({@link SharedThemeRules#offered}) over the
     * enabled themes, refused as {@code weblogs create} refuses a wrong theme:
     * a 400 on {@code theme} listing the ones that are offered. A custom theme
     * is imported and edited template by template, which only the admin UI
     * does. The site space never reaches here (the API withholds it).
     */
    private void requireOfferedTheme(Weblog weblog, String theme) {
        if (WeblogTheme.CUSTOM.equals(theme)) {
            throw ApiException.problemOnField("theme", ProblemCode.THEME_CUSTOM_NOT_ALLOWED);
        }
        List<String> offered = SharedThemeRules.offeredThemes(weblogger.getThemeManager(), weblog,
                weblog.getEditorTheme()).stream().map(SharedTheme::getId).toList();
        if (!offered.contains(theme)) {
            throw ApiException.problemOnField("theme", ProblemCode.THEME_NOT_OFFERED, String.join(", ", offered));
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
        return List.of(WeblogPermission.ADMIN);
    }

    @Override
    public List<String> requiredGlobalPermissionActions() {
        return List.of(GlobalPermission.LOGIN);
    }
}
