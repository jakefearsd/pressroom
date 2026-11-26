package com.wikantik.pressroom.ui.restapi.v1;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
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
import com.wikantik.pressroom.ui.restapi.ApiException;
import com.wikantik.pressroom.ui.restapi.FieldErrors;
import com.wikantik.pressroom.ui.restapi.Paging;
import com.wikantik.pressroom.ui.restapi.ProblemCode;
import com.wikantik.pressroom.ui.restapi.auth.SessionOnly;
import com.wikantik.pressroom.ui.restapi.dto.MemberDtos;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * A weblog's members: who holds a role on it, as its Members page lists them (spec
 * {@code 2026-11-20-api-gap-resources-design}, Task 2). The list is token-safe: a token can look. Changing a
 * membership (grant, change level, remove) is {@code @SessionOnly} (SA-18): a role granted to another account
 * is lasting access that revoking a token does not remove, and a removal can lock the owner out. The rules
 * are {@link Memberships}, the Members page's own: one level per member from {@code ADMIN}/{@code POST}/
 * {@code EDIT_DRAFT}; only a reader-free, enabled account; nobody new while group blogging is off; nobody
 * lowers or removes their own role; and a weblog keeps at least one admin.
 *
 * <p>The page's permission, {@code WeblogPermission.ADMIN} ({@code MembersController}'s, inherited from
 * {@code BaseController}), enforced by {@code PressroomHandlerInterceptor}; it is the permission
 * {@link TrafficApi} declares, so a {@code READ} token held by the weblog's admin may read it. The
 * site space is never offered.
 */
@Tag(name = "Members", description = "Who holds a role on a weblog. Reading is open to tokens; granting, "
        + "changing and removing are session only (the admin app).")
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
                log.debug("Leaving out the membership of {} on weblog {}: the account does not resolve",
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

    /** Gives an existing account a role at once; a member who already holds one has the level added. */
    @Operation(operationId = "membersGrant", summary = "Grant an existing account a role on the weblog (session only).")
    @ApiResponse(responseCode = "201", description = "Granted.")
    @SessionOnly
    @PostMapping("")
    public ResponseEntity<MemberDtos.MemberView> grant(HttpServletRequest request,
            @RequestBody MemberDtos.MemberGrant body) throws WebloggerException {
        Weblog weblog = requireActionWeblog(request);
        User actor = requireAuthenticatedUser(request);
        if (Memberships.groupBloggingOff()) {
            throw ApiException.problem(ProblemCode.MEMBER_GROUP_BLOGGING_OFF);
        }
        UserManager users = weblogger.getUserManager();
        FieldErrors errors = new FieldErrors();
        User target = body.userName() == null || body.userName().isBlank() ? null
                : users.getUserByUserName(body.userName());
        if (target == null) {
            errors.add("userName", ProblemCode.MEMBER_USER_UNKNOWN);
        } else if (!Memberships.mayHold(users, target)) {
            errors.add("userName", ProblemCode.MEMBER_READER_ACCOUNT);
        }
        String action = Memberships.actionFor(body.permission());
        requireLevel(errors, action);
        errors.throwIfAny();

        Memberships.grant(users, weblog, actor, target, action);
        weblogger.flush();
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(MemberDtos.toView(target, strongest(users, weblog, target)));
    }

    /** Gives a member the one level asked for. Asking for the level they hold changes nothing. */
    @Operation(operationId = "membersChange", summary = "Change a member's level (session only).")
    @SessionOnly
    @PatchMapping("/{userName}")
    public MemberDtos.MemberView change(HttpServletRequest request, @PathVariable("userName") String userName,
            @RequestBody MemberDtos.MemberChange body) throws WebloggerException {
        Weblog weblog = requireActionWeblog(request);
        User actor = requireAuthenticatedUser(request);
        UserManager users = weblogger.getUserManager();
        WeblogPermission current = requireMembership(users, weblog, userName);
        User target = users.getUserByUserName(userName);

        FieldErrors errors = new FieldErrors();
        String action = Memberships.actionFor(body.permission());
        requireLevel(errors, action);
        errors.throwIfAny();
        refuseLockOut(users, weblog, actor, current, action);

        if (!current.hasAction(action)) {
            Memberships.change(users, weblog, actor, target, action);
            weblogger.flush();
        }
        return MemberDtos.toView(target, strongest(users, weblog, target));
    }

    /** Removes a member's role. */
    @Operation(operationId = "membersRemove", summary = "Remove a member from the weblog (session only).")
    @ApiResponse(responseCode = "204", description = "Done; no body.")
    @SessionOnly
    @DeleteMapping("/{userName}")
    public ResponseEntity<Void> remove(HttpServletRequest request, @PathVariable("userName") String userName)
            throws WebloggerException {
        Weblog weblog = requireActionWeblog(request);
        User actor = requireAuthenticatedUser(request);
        UserManager users = weblogger.getUserManager();
        WeblogPermission current = requireMembership(users, weblog, userName);
        refuseLockOut(users, weblog, actor, current, Memberships.REMOVE);

        Memberships.remove(users, weblog, actor, users.getUserByUserName(userName));
        weblogger.flush();
        return ResponseEntity.noContent().build();
    }

    /** The stored action must be one of the three levels (the screen's own rule); null is not a level. */
    private static void requireLevel(FieldErrors errors, String action) {
        if (Memberships.levelProblem(action, false) != null) {
            errors.add("permission", ProblemCode.MEMBER_LEVEL_INVALID);
        }
    }

    /** The member's row on the weblog; an account that does not resolve, or holds no role here, reads as absent. */
    private static WeblogPermission requireMembership(UserManager users, Weblog weblog, String userName)
            throws WebloggerException {
        if (users.getUserByUserName(userName) != null) {
            for (WeblogPermission permission : users.getWeblogPermissions(weblog)) {
                if (userName.equals(permission.getUserName())) {
                    return permission;
                }
            }
        }
        throw ApiException.problem(ProblemCode.MEMBER_NOT_FOUND);
    }

    /** The Members page's two guards: nobody lowers or removes their own role, and one admin always remains. */
    private static void refuseLockOut(UserManager users, Weblog weblog, User actor, WeblogPermission current,
            String newLevel) throws WebloggerException {
        if (Memberships.selfChangeProblem(actor.getUserName(), current.getUserName(), newLevel) != null) {
            throw ApiException.problem(ProblemCode.MEMBER_SELF_CHANGE);
        }
        if (Memberships.leavesNoAdmin(users, weblog, current, newLevel)) {
            throw ApiException.problem(ProblemCode.MEMBER_LAST_ADMIN);
        }
    }

    private static String strongest(UserManager users, Weblog weblog, User target) throws WebloggerException {
        for (WeblogPermission permission : users.getWeblogPermissions(weblog)) {
            if (target.getUserName().equals(permission.getUserName())) {
                return Memberships.strongestLevel(permission);
            }
        }
        throw ApiException.problem(ProblemCode.MEMBER_NOT_FOUND);
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
