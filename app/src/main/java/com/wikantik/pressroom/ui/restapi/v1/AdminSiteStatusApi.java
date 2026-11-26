package com.wikantik.pressroom.ui.restapi.v1;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import com.wikantik.pressroom.pojos.GlobalPermission;
import com.wikantik.pressroom.ui.controllers.UISecurityEnforced;
import com.wikantik.pressroom.ui.restapi.auth.AdminScoped;
import com.wikantik.pressroom.ui.restapi.dto.AdminDtos;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * What the Maintenance screen shows about front-page discovery and analytics, and the two buttons beside it:
 * the same managers {@code MaintenanceController} calls, so the screen and the API cannot drift. Site admins
 * only ({@code UISecurityEnforced} declares the permission, {@code AdminScoped} the token scope). Neither
 * status makes a call to the analytics service; both read the manager's memory.
 */
@Tag(name = "Site status", description = "What the Maintenance screen shows about front-page discovery and "
        + "analytics, and the two actions beside it (site admins).")
@RestController
@RequestMapping("/v1/admin")
@AdminScoped
public class AdminSiteStatusApi extends BaseApiController implements UISecurityEnforced {

    /** Whether front-page discovery has a popularity snapshot, when it last tried and how it went. */
    @Operation(operationId = "discoveryGetStatus")
    @GetMapping("/discovery")
    public AdminDtos.DiscoveryStatusView discovery() {
        return AdminDtos.toView(weblogger.getDiscoveryManager().getStatus());
    }

    /**
     * Rebuilds the popularity snapshot now and answers how it went. Synchronous, as the screen's button is: the
     * manager never throws, and a failed analytics read comes back as a FAILED status with its reason, not as
     * an error response.
     */
    @Operation(operationId = "discoveryRefresh")
    @PostMapping("/discovery/refresh")
    public AdminDtos.DiscoveryStatusView refreshDiscovery() {
        weblogger.getDiscoveryManager().refresh();
        return AdminDtos.toView(weblogger.getDiscoveryManager().getStatus());
    }

    /** Which weblogs are registered with the analytics service, when traffic was last read, and what failed. */
    @Operation(operationId = "analyticsGetStatus")
    @GetMapping("/analytics")
    public AdminDtos.AnalyticsStatusView analytics() {
        return AdminDtos.toView(weblogger.getAnalyticsManager().getStatus());
    }

    /**
     * Asks for an analytics sync and returns at once: it runs on the manager's background executor, so the
     * status in the answer is the one before it finishes.
     */
    @Operation(operationId = "analyticsSync")
    @ApiResponse(responseCode = "202", description = "Asked for; the sync finishes after the response.")
    @PostMapping("/analytics/sync")
    public ResponseEntity<AdminDtos.AnalyticsStatusView> syncAnalytics() {
        weblogger.getAnalyticsManager().requestSync();
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(AdminDtos.toView(weblogger.getAnalyticsManager().getStatus()));
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
