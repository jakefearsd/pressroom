package com.wikantik.pressroom.ui.restapi.v1;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Objects;
import org.apache.commons.lang3.StringUtils;
import com.wikantik.pressroom.WebloggerException;
import com.wikantik.pressroom.business.UserManager;
import com.wikantik.pressroom.pojos.GlobalPermission;
import com.wikantik.pressroom.pojos.User;
import com.wikantik.pressroom.pojos.Weblog;
import com.wikantik.pressroom.pojos.WeblogPermission;
import com.wikantik.pressroom.ui.controllers.Memberships;
import com.wikantik.pressroom.ui.controllers.UISecurityEnforced;
import com.wikantik.pressroom.ui.restapi.ApiException;
import com.wikantik.pressroom.ui.restapi.FieldErrors;
import com.wikantik.pressroom.ui.restapi.ProblemCode;
import com.wikantik.pressroom.ui.restapi.auth.AdminScoped;
import com.wikantik.pressroom.ui.restapi.auth.SessionOnly;
import com.wikantik.pressroom.ui.restapi.dto.AdminDtos;
import com.wikantik.pressroom.ui.restapi.dto.MetaDtos;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * An account's memberships, written from its person or agent page (the Weblogs section): grant, change level,
 * remove. All {@code @SessionOnly} (SA-18): a role granted to another account is lasting access that revoking
 * a token does not remove. The rules are {@link Memberships}', the ones {@code UserWeblogsController} applies.
 * The weblog travels as {@code weblogHandle}, never {@code handle}: {@code PressroomHandlerInterceptor} reads
 * {@code {handle}} as the action weblog. The list is {@code AdminApi}'s, token-safe.
 */
@Tag(name = "User memberships", description = "Granting, changing and removing an account's roles on weblogs "
        + "(session only: the admin app, never a token).")
@RestController
@RequestMapping("/v1/admin/users/{userName}/weblogs")
@AdminScoped
public class UserWeblogsApi extends BaseApiController implements UISecurityEnforced {

    /**
     * Grants an account a role at once, as the person page's form does: group blogging must be on, the
     * weblog must exist (never the site space), the level must be one of the three, and a reader account may
     * not hold one. A member who already holds a role has the level added.
     */
    @Operation(operationId = "userWeblogsGrant", summary = "Give an account a role on a weblog (session only).")
    @ApiResponse(responseCode = "201", description = "Granted.")
    @SessionOnly
    @PostMapping("")
    public ResponseEntity<MetaDtos.WeblogMembership> grant(HttpServletRequest request,
            @PathVariable("userName") String userName, @RequestBody AdminDtos.UserWeblogGrant body)
            throws WebloggerException {
        User account = requireAccount(userName);
        User actor = requireAuthenticatedUser(request);
        if (Memberships.groupBloggingOff()) {
            throw ApiException.problem(ProblemCode.MEMBER_GROUP_BLOGGING_OFF);
        }
        UserManager users = weblogger.getUserManager();
        Weblog weblog = weblogFor(body.handle());
        FieldErrors errors = new FieldErrors();
        if (weblog == null) {
            errors.add("handle", ProblemCode.USER_WEBLOG_UNKNOWN);
        }
        String level = Memberships.actionFor(body.permission());
        if (Memberships.levelProblem(level, false) != null) {
            errors.add("permission", ProblemCode.MEMBER_LEVEL_INVALID);
        }
        errors.throwIfAny();
        // A reader holds no login action: a weblog permission would be dead weight and a promotion path.
        if (!Memberships.mayHold(users, account)) {
            throw ApiException.problem(ProblemCode.MEMBER_READER_ACCOUNT);
        }
        Memberships.grant(users, weblog, actor, account, level);
        weblogger.flush();
        return ResponseEntity.status(HttpStatus.CREATED).body(view(users, weblog, account));
    }

    /** Gives the account the one level asked for on the weblog; asking for the level it holds changes nothing. */
    @Operation(operationId = "userWeblogsChange", summary = "Change the level an account holds on a weblog "
            + "(session only).")
    @SessionOnly
    @PatchMapping("/{weblogHandle}")
    public MetaDtos.WeblogMembership change(HttpServletRequest request, @PathVariable("userName") String userName,
            @PathVariable("weblogHandle") String weblogHandle, @RequestBody AdminDtos.UserWeblogChange body)
            throws WebloggerException {
        User account = requireAccount(userName);
        User actor = requireAuthenticatedUser(request);
        UserManager users = weblogger.getUserManager();
        Weblog weblog = weblogFor(weblogHandle);
        String level = Memberships.actionFor(body.permission());
        FieldErrors errors = new FieldErrors();
        if (Memberships.levelProblem(level, false) != null) {
            errors.add("permission", ProblemCode.MEMBER_LEVEL_INVALID);
        }
        WeblogPermission current = weblog == null ? null : users.getWeblogPermission(weblog, account);
        if (current == null) {
            throw ApiException.problem(ProblemCode.MEMBER_NOT_FOUND);
        }
        errors.throwIfAny();
        if (Memberships.leavesNoAdmin(users, weblog, current, level)) {
            throw ApiException.problem(ProblemCode.MEMBER_LAST_ADMIN);
        }
        if (!Memberships.strongestLevel(current).equals(body.permission())) {
            Memberships.change(users, weblog, actor, account, level);
            weblogger.flush();
        }
        return view(users, weblog, account);
    }

    /** Removes the account's role on the weblog; a weblog keeps at least one admin. */
    @Operation(operationId = "userWeblogsRemove", summary = "Remove an account's role on a weblog (session only).")
    @ApiResponse(responseCode = "204", description = "Done; no body.")
    @SessionOnly
    @DeleteMapping("/{weblogHandle}")
    public ResponseEntity<Void> remove(HttpServletRequest request, @PathVariable("userName") String userName,
            @PathVariable("weblogHandle") String weblogHandle) throws WebloggerException {
        User account = requireAccount(userName);
        User actor = requireAuthenticatedUser(request);
        UserManager users = weblogger.getUserManager();
        Weblog weblog = weblogFor(weblogHandle);
        WeblogPermission current = weblog == null ? null : users.getWeblogPermission(weblog, account);
        if (current == null) {
            throw ApiException.problem(ProblemCode.MEMBER_NOT_FOUND);
        }
        if (Memberships.leavesNoAdmin(users, weblog, current, Memberships.REMOVE)) {
            throw ApiException.problem(ProblemCode.MEMBER_LAST_ADMIN);
        }
        Memberships.remove(users, weblog, actor, account);
        weblogger.flush();
        return ResponseEntity.noContent().build();
    }

    /**
     * The weblog a membership names, or null for none. The site space answers null too: it holds no
     * permission rows and must never gain one, which would list it among that person's blogs.
     */
    private Weblog weblogFor(String handle) throws WebloggerException {
        Weblog weblog = StringUtils.isBlank(handle)
                ? null : weblogger.getWeblogManager().getWeblogByHandle(handle, null);
        return weblog == null || weblog.isSiteSpace() ? null : weblog;
    }

    /** The account named, in any enabled state; none is a 404. */
    private User requireAccount(String userName) throws WebloggerException {
        User account = StringUtils.isBlank(userName) ? null
                : weblogger.getUserManager().getUserByUserName(userName, null);
        if (account == null) {
            throw ApiException.problem(ProblemCode.USER_NOT_FOUND);
        }
        return account;
    }

    private static MetaDtos.WeblogMembership view(UserManager users, Weblog weblog, User account)
            throws WebloggerException {
        WeblogPermission held = users.getWeblogPermission(weblog, account);
        if (held == null) {
            throw ApiException.problem(ProblemCode.MEMBER_NOT_FOUND);
        }
        return new MetaDtos.WeblogMembership(weblog.getHandle(), Objects.toString(weblog.getName(), ""),
                Memberships.strongestLevel(held));
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
