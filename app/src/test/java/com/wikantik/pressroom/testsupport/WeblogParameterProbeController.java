package com.wikantik.pressroom.testsupport;

import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import com.wikantik.pressroom.pojos.Weblog;
import com.wikantik.pressroom.ui.controllers.UISecurityEnforced;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ResponseBody;

/**
 * Test-only stand-in for a JSP-era admin controller that needs a weblog,
 * named the way every JSP route names it: a {@code weblog} request
 * parameter. It answers with the handle {@code PressroomHandlerInterceptor}
 * resolved, so a dispatch test can see the JSP half of the resolver at
 * work. Outside {@code ui.restapi} and {@code ui.controllers} for the
 * reasons {@link DispatchProbeController} gives.
 */
@Controller
public class WeblogParameterProbeController implements UISecurityEnforced {

    @GetMapping("/roller-ui/weblogParameterProbe")
    @ResponseBody
    public String probe(HttpServletRequest request) {
        return request.getAttribute("actionWeblog") instanceof Weblog weblog
                ? "weblog:" + weblog.getHandle() : "weblog:none";
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
        return List.of("post");
    }

    @Override
    public List<String> requiredGlobalPermissionActions() {
        return List.of();
    }
}
