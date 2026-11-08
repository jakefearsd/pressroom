package com.wikantik.pressroom.ui.restapi.v1;

import io.swagger.v3.oas.annotations.Parameter;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import com.wikantik.pressroom.business.analytics.BlogTraffic;
import com.wikantik.pressroom.pojos.Weblog;
import com.wikantik.pressroom.pojos.WeblogPermission;
import com.wikantik.pressroom.ui.controllers.UISecurityEnforced;
import com.wikantik.pressroom.ui.controllers.editor.TrafficView;
import com.wikantik.pressroom.ui.restapi.ApiException;
import com.wikantik.pressroom.ui.restapi.dto.TrafficDtos;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * A blog's traffic: the numbers its Traffic page shows, for an agent deciding which posts to update or
 * write next (spec {@code 2026-11-08-agent-api-parity-design}, Traffic). Read from the local snapshot
 * through {@code AnalyticsManager.getBlogTraffic}, like the page: no request here calls Umami, and
 * {@code asOf}/{@code state} say how fresh the numbers are.
 *
 * <p>The page's permission, {@code WeblogPermission.ADMIN}, enforced by
 * {@code PressroomHandlerInterceptor}. Unlike the page, the site space is never offered: the
 * interceptor withholds it from every API handler, so it answers as an unknown weblog. And unlike the
 * page, an unknown {@code period} is refused rather than shown as 30d.
 */
@RestController
@RequestMapping("/v1/weblogs/{handle}/traffic")
public class TrafficApi extends BaseApiController implements UISecurityEnforced {

    @GetMapping("")
    public TrafficDtos.TrafficView get(HttpServletRequest request,
            @Parameter(description = "One of 24h, 7d, 30d (the default) or 90d.")
            @RequestParam(value = "period", defaultValue = "30d") String period) {
        if (!TrafficView.PERIODS.contains(period)) {
            throw ApiException.invalidField("period",
                    "period must be one of " + String.join(", ", TrafficView.PERIODS) + ".");
        }
        Weblog weblog = requireActionWeblog(request);
        BlogTraffic traffic = weblogger.getAnalyticsManager().getBlogTraffic(weblog.getId(), period);
        return TrafficDtos.toView(traffic, period);
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
        return List.of();
    }
}
