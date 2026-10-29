package com.wikantik.pressroom.testsupport;

import java.util.List;
import com.wikantik.pressroom.ui.controllers.UISecurityEnforced;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ResponseBody;

/**
 * Test-only stand-in for a JSP-era admin controller. Used by
 * {@code EntriesApiDispatchTest} to prove {@code PressroomHandlerInterceptor}'s
 * redirect behaviour is unchanged for non-API handlers, now that the API
 * surface throws {@code ApiException} instead of redirecting (see that
 * class's own javadoc).
 *
 * <p>Deliberately lives OUTSIDE both {@code ui.restapi} (that package is the
 * discriminator {@code PressroomHandlerInterceptor} uses to decide whether to
 * redirect or throw -- a copy of this class placed there would exercise the
 * wrong branch entirely) AND {@code ui.controllers} (that package is
 * classpath-scanned by {@code RouteCoverageTest} for every real
 * {@code @GetMapping}, which would demand this fixture's throwaway route be
 * added to the it-selenium browser-sweep catalogue it was never meant to
 * join).
 */
@Controller
public class DispatchProbeController implements UISecurityEnforced {

    @GetMapping("/pressroom-ui/dispatchProbe")
    @ResponseBody
    public String probe() {
        return "ok";
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
        return List.of();
    }
}
