package com.wikantik.pressroom.ui.controllers.admin;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.List;

import jakarta.servlet.http.HttpServletRequest;

import com.wikantik.pressroom.business.Colophon;
import com.wikantik.pressroom.config.WebloggerRuntimeConfig;
import com.wikantik.pressroom.pojos.GlobalPermission;
import com.wikantik.pressroom.pojos.Weblog;
import com.wikantik.pressroom.pojos.User;
import com.wikantik.pressroom.ui.controllers.BaseController;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;

/**
 * The React admin's shell ({@code /pressroom-ui/app.rol}): a minimal page that
 * links the admin's stylesheets and bundle and carries one JSON bootstrap
 * block (see {@link AppBootstrap}). {@code AppShellForwardFilter} sends every
 * deep link under {@code /pressroom-ui/app/} here. A signed-in author may see
 * it; the interceptor turns anyone else away as it does for any admin screen.
 */
@Controller
@RequestMapping("/pressroom-ui")
public class AppShellController extends BaseController {

    @Override
    public boolean isWeblogRequired() {
        return false;
    }

    @Override
    public List<String> requiredGlobalPermissionActions() {
        return List.of(GlobalPermission.LOGIN);
    }

    @Override
    public String getActionName() {
        return "app";
    }

    /** A site setting as the tiles layouts print it; the product name when it is not set. */
    private static String siteProperty(String name) {
        String value = WebloggerRuntimeConfig.getProperty(name);
        return value == null || value.isBlank() ? "Pressroom" : value;
    }

    @GetMapping("/app.rol")
    public String shell(HttpServletRequest request, Model model) {
        User user = getAuthenticatedUser(request);
        String contextPath = request.getContextPath() == null ? "" : request.getContextPath();
        String version = Colophon.of(null, null, Clock.systemDefaultZone()).version();
        String uiLocale = request.getLocale().toLanguageTag();
        CsrfToken csrf = (CsrfToken) request.getAttribute(CsrfToken.class.getName());
        String messagesUrl = contextPath + "/pressroom-ui/app-messages.rol?locale="
                + URLEncoder.encode(uiLocale, StandardCharsets.UTF_8)
                + "&v=" + URLEncoder.encode(version, StandardCharsets.UTF_8);
        AppBootstrap bootstrap = new AppBootstrap(contextPath, version,
                csrf == null ? "" : csrf.getHeaderName(), csrf == null ? "" : csrf.getToken(),
                user.getUserName(), user.getScreenName() == null ? "" : user.getScreenName(),
                user.getLocale() == null ? "" : user.getLocale(),
                isGlobalAdmin(user) ? "admin" : "editor", uiLocale, messagesUrl,
                new AppBootstrap.Site(siteProperty("site.name"), siteProperty("site.shortName"),
                        Weblog.SITE_SPACE_HANDLE),
                Colophon.of(weblogger, null, Clock.systemDefaultZone()));
        model.addAttribute("bootstrapJson", bootstrap.toJson());
        model.addAttribute("appVersion", version);
        return "app/Shell";
    }
}
