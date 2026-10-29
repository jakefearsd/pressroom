package com.wikantik.pressroom.ui.restapi.v1;

import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Map;
import com.wikantik.pressroom.WebloggerException;
import com.wikantik.pressroom.business.MaintenanceService;
import com.wikantik.pressroom.pojos.GlobalPermission;
import com.wikantik.pressroom.pojos.Weblog;
import com.wikantik.pressroom.ui.controllers.UISecurityEnforced;
import com.wikantik.pressroom.ui.restapi.auth.AdminScoped;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The three Maintenance actions (flush the page cache, rebuild the search
 * index, regenerate media renditions), reachable from automation the same
 * way an operator reaches them from the Maintenance screen. {@link
 * MaintenanceService} is the single implementation both callers share --
 * this controller does no work of its own beyond resolving the weblog and
 * shaping the response.
 *
 * <p>{@code UISecurityEnforced} declares {@code GlobalPermission.ADMIN}, the
 * same requirement {@code MaintenanceController} enforces for the JSP
 * screen, and {@code PressroomHandlerInterceptor} is what actually enforces it;
 * this controller adds no permission checking of its own. {@code
 * @AdminScoped} is the separate, token-scope half of the same story --
 * {@code ApiScopeInterceptor} reads it off this class to refuse a
 * non-admin-scoped token before the permission check even runs. Both are
 * required; neither substitutes for the other.
 *
 * <p>Every action answers {@code 202 Accepted} rather than {@code 200}:
 * rebuilding the index and regenerating renditions are asynchronous work
 * queued by the manager, not finished by the time this method returns, and
 * {@code 200} would tell a client the work was done when it was only
 * started. Flushing the cache is synchronous by comparison, but all three
 * actions share one status code so a caller does not have to special-case
 * one of the three -- and "started" is an honest description of a flush
 * too, since {@code CacheManager.invalidate} only marks the cache stale
 * rather than eagerly repopulating it.
 */
@RestController
@RequestMapping("/v1/admin/weblogs/{handle}/actions")
@AdminScoped
public class AdminActionsApi extends BaseApiController implements UISecurityEnforced {

    @PostMapping("/flush-cache")
    public ResponseEntity<Map<String, String>> flushCache(HttpServletRequest request)
            throws WebloggerException {
        Weblog weblog = requireActionWeblog(request);
        new MaintenanceService(weblogger).flushCache(weblog);
        return accepted("flush-cache", weblog);
    }

    @PostMapping("/rebuild-index")
    public ResponseEntity<Map<String, String>> rebuildIndex(HttpServletRequest request)
            throws WebloggerException {
        Weblog weblog = requireActionWeblog(request);
        new MaintenanceService(weblogger).rebuildIndex(weblog);
        return accepted("rebuild-index", weblog);
    }

    @PostMapping("/regenerate-renditions")
    public ResponseEntity<Map<String, String>> regenerateRenditions(HttpServletRequest request)
            throws WebloggerException {
        Weblog weblog = requireActionWeblog(request);
        new MaintenanceService(weblogger).regenerateRenditions(weblog);
        return accepted("regenerate-renditions", weblog);
    }

    private static ResponseEntity<Map<String, String>> accepted(String action, Weblog weblog) {
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(Map.of("action", action, "weblog", weblog.getHandle()));
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
        return List.of();
    }

    @Override
    public List<String> requiredGlobalPermissionActions() {
        return List.of(GlobalPermission.ADMIN);
    }
}
