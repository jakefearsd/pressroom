package com.wikantik.pressroom.ui.restapi.v1;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import com.wikantik.pressroom.WebloggerException;
import com.wikantik.pressroom.business.themes.SharedTheme;
import com.wikantik.pressroom.pojos.GlobalPermission;
import com.wikantik.pressroom.pojos.User;
import com.wikantik.pressroom.pojos.Weblog;
import com.wikantik.pressroom.ui.controllers.SharedThemeRules;
import com.wikantik.pressroom.ui.controllers.UISecurityEnforced;
import com.wikantik.pressroom.ui.restapi.ApiException;
import com.wikantik.pressroom.ui.restapi.ProblemCode;
import com.wikantik.pressroom.ui.restapi.auth.AdminScoped;
import com.wikantik.pressroom.ui.restapi.auth.SessionOnly;
import com.wikantik.pressroom.ui.restapi.dto.ThemeDtos;
import com.wikantik.pressroom.util.cache.CacheManager;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The site's own theme: what the Theme screen shows and does on the site space, the hidden weblog that owns the
 * site's pages. {@code PressroomHandlerInterceptor} withholds the site space from every API handler, so the
 * weblog routes cannot reach it; these two routes name no handle, look it up themselves and are the only API
 * way to it. Site admins only, and session-only (SA-18): the admin screen's own call, never an agent's.
 * The rules are {@link SharedThemeRules}', the ones the old Theme page applied to the site space: the site
 * themes only, and never a custom theme.
 */
@Tag(name = "Site theme", description = "The theme of the site's own pages (site admins; the admin app only).")
@RestController
@RequestMapping("/v1/admin/site-theme")
@AdminScoped
@SessionOnly
public class AdminSiteThemeApi extends BaseApiController implements UISecurityEnforced {

    @Operation(operationId = "siteThemeGet", summary = "The site themes on offer and the one in use (session only).")
    @GetMapping("")
    public ThemeDtos.WeblogThemes get(HttpServletRequest request) throws WebloggerException {
        return WeblogThemesApi.view(weblogger, siteSpace(request));
    }

    @Operation(operationId = "siteThemeSet", summary = "Switch the site's own pages to a site theme (session only).")
    @PutMapping("")
    public ThemeDtos.WeblogThemes set(HttpServletRequest request, @RequestBody ThemeDtos.SiteThemeChange body)
            throws WebloggerException {
        Weblog site = siteSpace(request);
        String theme = body == null ? null : body.theme();
        if (theme == null || theme.isBlank()) {
            throw ApiException.problemOnField("theme", ProblemCode.FIELD_REQUIRED, "theme");
        }
        List<String> offered = SharedThemeRules.offeredThemes(weblogger.getThemeManager(), site,
                site.getEditorTheme()).stream().map(SharedTheme::getId).toList();
        if (!offered.contains(theme)) {
            throw ApiException.problemOnField("theme", ProblemCode.THEME_NOT_OFFERED, String.join(", ", offered));
        }
        SharedThemeRules.switchTo(weblogger.getWeblogManager(), site, theme,
                SharedThemeRules.currentTheme(weblogger.getThemeManager(), site));
        weblogger.getWeblogManager().saveWeblog(site);
        weblogger.flush();
        // WeblogPageCache expires only against weblog.lastModified; invalidate bumps it.
        CacheManager.invalidate(site);
        return WeblogThemesApi.view(weblogger, site);
    }

    /** The site space, created on first use as the main menu always did; the caller is a site admin. */
    private Weblog siteSpace(HttpServletRequest request) throws WebloggerException {
        User user = requireAuthenticatedUser(request);
        return weblogger.getWeblogManager().getOrCreateSiteSpace(user);
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
