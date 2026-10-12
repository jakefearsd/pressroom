package org.apache.roller.weblogger.ui.restapi.v1;

import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import java.util.List;
import java.util.stream.Collectors;
import org.apache.roller.weblogger.WebloggerException;
import org.apache.roller.weblogger.pojos.GlobalPermission;
import org.apache.roller.weblogger.pojos.User;
import org.apache.roller.weblogger.pojos.Weblog;
import org.apache.roller.weblogger.ui.controllers.UISecurityEnforced;
import org.apache.roller.weblogger.ui.controllers.WeblogCreationRules;
import org.apache.roller.weblogger.ui.controllers.core.CreateWeblogBean;
import org.apache.roller.weblogger.ui.restapi.ApiException;
import org.apache.roller.weblogger.ui.restapi.ColumnLimits;
import org.apache.roller.weblogger.ui.restapi.dto.AdminDtos;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

/**
 * POST /v1/weblogs: create a weblog, by the same rules as the Create Weblog
 * page (WeblogCreationRules). Not {@code @AdminScoped}, unlike WeblogsApi:
 * any user the page lets create a weblog may do it with a writing token. A
 * token limited to one weblog meets ApiScopeInterceptor's default (no weblog
 * on this route, so 404), and a READ token its 403.
 */
@RestController
@RequestMapping("/v1/weblogs")
public class WeblogCreateApi extends BaseApiController implements UISecurityEnforced {

    @PostMapping("")
    public ResponseEntity<AdminDtos.WeblogView> create(HttpServletRequest request,
            @RequestBody AdminDtos.WeblogCreate body) throws WebloggerException {
        User user = requireAuthenticatedUser(request);
        WeblogCreationRules.Context context = WeblogCreationRules.Context.current(weblogger);
        WeblogCreationRules.Violation refusal = WeblogCreationRules.mayCreate(user, context);
        if (refusal != null) {
            throw ApiException.forbidden(refusal.detail());
        }
        ColumnLimits.requireMaxLength("emailAddress", body.emailAddress(), ColumnLimits.WEBLOG_EMAIL_ADDRESS);
        CreateWeblogBean bean = AdminDtos.toBean(body, user);
        List<WeblogCreationRules.Violation> broken = WeblogCreationRules.check(bean, context);
        if (!broken.isEmpty()) {
            String detail = broken.stream().map(WeblogCreationRules.Violation::detail)
                    .collect(Collectors.joining(" "));
            throw broken.stream().allMatch(v -> v.status() == 409)
                    ? ApiException.conflict(detail) : ApiException.badRequest(detail);
        }
        Weblog weblog = WeblogCreationRules.create(bean, user, weblogger.getWeblogManager());
        weblogger.flush();
        URI location = ServletUriComponentsBuilder.fromRequestUri(request)
                .path("/{handle}").buildAndExpand(weblog.getHandle()).toUri();
        return ResponseEntity.created(location).body(AdminDtos.toView(weblog));
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
        return List.of(GlobalPermission.LOGIN);
    }
}
