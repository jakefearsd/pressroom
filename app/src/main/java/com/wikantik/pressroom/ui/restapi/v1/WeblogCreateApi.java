package com.wikantik.pressroom.ui.restapi.v1;

import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import java.util.List;
import java.util.stream.Collectors;
import org.apache.commons.lang3.StringUtils;
import com.wikantik.pressroom.WebloggerException;
import com.wikantik.pressroom.config.WebloggerRuntimeConfig;
import com.wikantik.pressroom.pojos.GlobalPermission;
import com.wikantik.pressroom.pojos.User;
import com.wikantik.pressroom.pojos.Weblog;
import com.wikantik.pressroom.ui.controllers.UISecurityEnforced;
import com.wikantik.pressroom.ui.controllers.WeblogCreationRules;
import com.wikantik.pressroom.ui.controllers.core.CreateWeblogBean;
import com.wikantik.pressroom.ui.restapi.ApiException;
import com.wikantik.pressroom.ui.restapi.ApiProblem;
import com.wikantik.pressroom.ui.restapi.ColumnLimits;
import com.wikantik.pressroom.ui.restapi.dto.AdminDtos;
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
        if (user.isAgent() && StringUtils.isBlank(body.emailAddress())) {
            // An agent's @agents.invalid address is no mailbox: readers' contact
            // messages are mailed to the weblog's address, so the site's address
            // stands in, and with none to give the agent must say where mail goes.
            String siteAddress = WebloggerRuntimeConfig.getProperty("site.adminemail");
            if (StringUtils.isBlank(siteAddress)) {
                throw ApiException.invalidField("emailAddress",
                        "emailAddress is required: an agent has no address of its own and this site has no default.");
            }
            bean.setEmailAddress(siteAddress.trim());
        }
        List<WeblogCreationRules.Violation> broken = WeblogCreationRules.check(bean, context);
        if (!broken.isEmpty()) {
            String detail = broken.stream().map(WeblogCreationRules.Violation::detail)
                    .collect(Collectors.joining(" "));
            if (broken.stream().allMatch(v -> v.status() == 409)) {
                throw broken.size() == 1
                        ? ApiException.conflictOnField(broken.get(0).field(), detail)
                        : ApiException.conflict(detail);
            }
            throw ApiException.validation(detail, broken.stream()
                    .map(v -> new ApiProblem.FieldError(v.field(), v.detail())).toList());
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
