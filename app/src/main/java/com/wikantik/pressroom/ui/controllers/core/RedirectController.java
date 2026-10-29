package com.wikantik.pressroom.ui.controllers.core;

import com.wikantik.pressroom.ui.controllers.BaseController;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;

import java.util.Collections;
import java.util.List;

/**
 * Handles simple redirect actions that had no Struts action class —
 * they were just result mappings to JSP pages.
 */
@Controller
@RequestMapping("/pressroom-ui")
public class RedirectController extends BaseController {

    @Override
    public boolean isUserRequired() { return false; }

    @Override
    public boolean isWeblogRequired() { return false; }

    @Override
    public List<String> requiredGlobalPermissionActions() {
        return Collections.emptyList();
    }

    @GetMapping("/login-redirect.rol")
    public String loginRedirect() {
        return "forward:/pressroom-ui/login-redirect.jsp";
    }

    @GetMapping("/home.rol")
    public String home() {
        return "redirect:/";
    }

    @GetMapping("/logout.rol")
    public String logout() {
        return "forward:/pressroom-ui/logout-redirect.jsp";
    }

    /**
     * Landing page for authorization failures.
     *
     * <p>PressroomHandlerInterceptor redirects here whenever a request fails a
     * permission check, which includes the very common case of hitting an
     * /authoring/ route without a resolvable {@code weblog} parameter. The target
     * previously had no handler and no {@code .rol} suffix, so the DispatcherServlet
     * never saw it and every denial produced a bare 404 instead of the
     * denied page that has existed all along.
     *
     * <p>Returns the {@code .denied} view name -- resolved by
     * {@link com.wikantik.pressroom.ui.controllers.PressroomViewResolver}
     * to the {@code .tiles-errorpage} layout with {@code denied.jsp} as its
     * content -- rather than forwarding to the raw JSP fragment directly.
     * The raw forward used to skip that layout entirely, serving denied.jsp
     * with no head/banner/footer chrome and no design-system CSS at all.
     */
    @GetMapping("/access-denied.rol")
    public String accessDenied(Model model) {
        // The errorpage layout falls back to the generic `error` key when no
        // pageTitle is set, which put "Error" in the tab for what is really a
        // permission answer. Base-bundle-only key on purpose: every locale
        // falls back to it, so the title can never disagree in language with
        // denied.jsp's deliberately hardcoded English body.
        model.addAttribute("pageTitle", "denied.title");
        return ".denied";
    }
}
