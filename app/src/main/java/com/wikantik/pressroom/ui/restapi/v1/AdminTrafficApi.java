package com.wikantik.pressroom.ui.restapi.v1;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import com.wikantik.pressroom.pojos.GlobalPermission;
import com.wikantik.pressroom.ui.controllers.UISecurityEnforced;
import com.wikantik.pressroom.ui.controllers.editor.TrafficView;
import com.wikantik.pressroom.ui.restapi.ApiException;
import com.wikantik.pressroom.ui.restapi.ProblemCode;
import com.wikantik.pressroom.ui.restapi.auth.AdminScoped;
import com.wikantik.pressroom.ui.restapi.dto.TrafficDtos;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Every blog's traffic: the numbers the server admin's Site Traffic page shows (spec
 * {@code 2026-11-20-api-gap-resources-design}, Task 3), read from the local snapshots through
 * {@code AnalyticsManager.getSiteTraffic}; no request here calls Umami.
 *
 * <p>The page's permission, {@code GlobalPermission.ADMIN} with no weblog ({@code SiteTrafficController}'s),
 * enforced by {@code PressroomHandlerInterceptor}. Its own controller because declarations are per class
 * and {@link TrafficApi} declares a weblog. Like {@link TrafficApi}, an unknown {@code period} is refused
 * rather than shown as 30d.
 */
@Tag(name = "Site traffic", description = "Every blog's visitor figures, from the site's analytics (site admins).")
@RestController
@RequestMapping("/v1/admin/traffic")
@AdminScoped
public class AdminTrafficApi extends BaseApiController implements UISecurityEnforced {

    @Operation(operationId = "siteTrafficGet", summary = "Every blog's views and visits, and the site's top posts and sources.")
    @GetMapping("")
    public TrafficDtos.SiteTrafficView get(
            @Parameter(description = "One of 24h, 7d, 30d (the default) or 90d.")
            @RequestParam(value = "period", defaultValue = "30d") String period) {
        if (!TrafficView.PERIODS.contains(period)) {
            throw ApiException.problemOnField("period", ProblemCode.TRAFFIC_PERIOD_UNKNOWN,
                    String.join(", ", TrafficView.PERIODS));
        }
        return TrafficDtos.toSiteView(weblogger.getAnalyticsManager().getSiteTraffic(period), period);
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
