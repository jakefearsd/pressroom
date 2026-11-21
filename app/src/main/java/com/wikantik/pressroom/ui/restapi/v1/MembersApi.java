package com.wikantik.pressroom.ui.restapi.v1;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import com.wikantik.pressroom.WebloggerException;
import com.wikantik.pressroom.business.UserManager;
import com.wikantik.pressroom.pojos.User;
import com.wikantik.pressroom.pojos.Weblog;
import com.wikantik.pressroom.pojos.WeblogPermission;
import com.wikantik.pressroom.ui.controllers.Memberships;
import com.wikantik.pressroom.ui.controllers.UISecurityEnforced;
import com.wikantik.pressroom.ui.restapi.Paging;
import com.wikantik.pressroom.ui.restapi.dto.MemberDtos;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * A weblog's members: who holds a role on it, as its Members page lists them (spec
 * {@code 2026-11-20-api-gap-resources-design}, Task 2). Read only: changing a membership is made in
 * the admin UI (the session-only policy), so a token can look but not touch.
 *
 * <p>The page's permission, {@code WeblogPermission.ADMIN} ({@code MembersController}'s, inherited from
 * {@code BaseController}), enforced by {@code PressroomHandlerInterceptor}; it is the permission
 * {@link TrafficApi} declares, so a {@code READ} token held by the weblog's admin may read it. The
 * site space is never offered.
 */
@Tag(name = "Members", description = "Who holds a role on a weblog. Read only; memberships are changed in the "
        + "admin UI.")
@RestController
@RequestMapping("/v1/weblogs/{handle}/members")
public class MembersApi extends BaseApiController implements UISecurityEnforced {

    private static final Logger log = LoggerFactory.getLogger(MembersApi.class);

    /**
     * The weblog's members sorted by user name, ignoring case. A membership whose account no longer
     * resolves (deleted or disabled) is left out, as the Members page shows no control for it; a lookup
     * that cannot run propagates as a 500, never as a shorter list. {@code total} counts every listed
     * member, not the page.
     */
    @Operation(operationId = "membersList", summary = "Who holds a role on the weblog.")
    @GetMapping("")
    public MemberDtos.MemberPage list(HttpServletRequest request,
            @Parameter(description = "How many members to skip. Default 0.")
            @RequestParam(value = "offset", defaultValue = "0") int offset,
            @Parameter(description = "The page size: default 50, at most 200.")
            @RequestParam(value = "limit", defaultValue = "50") int limit) throws WebloggerException {
        Paging.requireRange(limit, offset);
        Weblog weblog = requireActionWeblog(request);
        int bounded = Math.min(limit, Paging.MAX_LIMIT);

        UserManager users = weblogger.getUserManager();
        List<MemberDtos.MemberView> members = new ArrayList<>();
        for (WeblogPermission permission : users.getWeblogPermissions(weblog)) {
            User account = users.getUserByUserName(permission.getUserName());
            if (account == null) {
                log.warn("Leaving out the membership of {} on weblog {}: the account does not resolve",
                        permission.getUserName(), weblog.getHandle());
                continue;
            }
            members.add(MemberDtos.toView(account, Memberships.strongestLevel(permission)));
        }
        members.sort(Comparator.comparing(MemberDtos.MemberView::userName, String.CASE_INSENSITIVE_ORDER));

        int from = Math.min(offset, members.size());
        int to = (int) Math.min((long) from + bounded, members.size());
        return new MemberDtos.MemberPage(List.copyOf(members.subList(from, to)), offset, bounded, to < members.size(),
                members.size());
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
