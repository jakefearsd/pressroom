package com.wikantik.pressroom.ui.restapi.v1;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Objects;
import com.wikantik.pressroom.WebloggerException;
import com.wikantik.pressroom.business.Weblogger;
import com.wikantik.pressroom.pojos.User;
import com.wikantik.pressroom.ui.controllers.AccountRules;
import com.wikantik.pressroom.ui.controllers.PasswordRules;
import com.wikantik.pressroom.ui.core.security.CredentialStampFilter;
import com.wikantik.pressroom.ui.restapi.ApiException;
import com.wikantik.pressroom.ui.restapi.FieldErrors;
import com.wikantik.pressroom.ui.restapi.ProblemCode;
import com.wikantik.pressroom.ui.restapi.RuleProblems;
import com.wikantik.pressroom.business.comments.CommentService;
import com.wikantik.pressroom.ui.restapi.auth.SessionOnly;
import com.wikantik.pressroom.ui.restapi.dto.MetaDtos;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The caller's own account writes (SA-18, session only): the Profile page's fields and its password change.
 * The read, {@code GET /v1/me/profile}, is {@link MetaApi}'s and token-safe.
 */
@Tag(name = "Profile writes", description = "Changing the caller's own account and password (session only: the "
        + "admin app, never a token).")
@RestController
@RequestMapping("/v1/me")
@SessionOnly
public class MeProfileApi {

    @Autowired
    @Lazy
    Weblogger weblogger;

    @Autowired
    @Lazy
    CommentService commentService;

    /**
     * The Profile page's account fields for the signed-in caller; a field left out is left alone. The rules
     * are {@code AccountRules}', the ones the admin user patch applies; a rename expires the weblogs showing
     * the account's comments, as the page does. The email address is where password-reset links go, so the
     * route is session-only for every field (SA-18).
     */
    @Operation(operationId = "meProfileUpdate", summary = "Change the caller's own account details (session only).")
    @PatchMapping("/profile")
    public ResponseEntity<MetaDtos.ProfileView> update(HttpServletRequest request,
            @RequestBody MetaDtos.ProfilePatch body) throws WebloggerException {
        User user = requireUser(request);
        String screenNameBefore = user.getScreenName();
        FieldErrors errors = new FieldErrors();
        AccountFieldPatch.apply(errors, body.screenName(), AccountRules::screenNameProblem, user::setScreenName);
        AccountFieldPatch.apply(errors, body.fullName(), AccountRules::fullNameProblem, user::setFullName);
        AccountFieldPatch.apply(errors, body.emailAddress(),
                v -> AccountRules.emailProblem(v, AccountRules.EmailUse.REPLACEMENT), user::setEmailAddress);
        AccountFieldPatch.apply(errors, body.locale(), AccountRules::localeProblem, user::setLocale);
        AccountFieldPatch.apply(errors, body.timeZone(), AccountRules::timeZoneProblem, user::setTimeZone);
        errors.throwIfAny();

        weblogger.getUserManager().saveUser(user);
        weblogger.flush();
        // The screen name signs this account's comments and replies, and rendered pages expire only through
        // their weblog.
        if (!Objects.equals(screenNameBefore, user.getScreenName())) {
            commentService.expireWhereShown(user);
        }
        return ResponseEntity.ok(new MetaDtos.ProfileView(user.getUserName(), user.getScreenName(),
                user.getFullName(), user.getEmailAddress(), user.getLocale(), user.getTimeZone(),
                user.getKind().name()));
    }

    /**
     * The Profile page's password change: the current password must be the caller's (a session alone must not
     * be enough to take the account over for good), the new one meets the floor, and a single-sign-on account
     * or an agent has no password to change. The account's other sessions end on their next request
     * ({@code CredentialStampFilter}); this one stays signed in.
     */
    @Operation(operationId = "mePasswordChange", summary = "Change the caller's own password (session only).")
    @ApiResponse(responseCode = "204", description = "Done; no body.")
    @PutMapping("/password")
    public ResponseEntity<Void> changePassword(HttpServletRequest request,
            @RequestBody MetaDtos.PasswordChange body) throws WebloggerException {
        User user = requireUser(request);
        FieldErrors errors = new FieldErrors();
        if (user.isSsoBound()) {
            errors.add("newPassword", ProblemCode.USER_SSO_BOUND);
        } else if (user.isAgent()) {
            errors.add("newPassword", ProblemCode.USER_NOT_A_PERSON);
        } else {
            if (!PasswordRules.matchesCurrent(user, body.currentPassword())) {
                errors.add("currentPassword", ProblemCode.PROFILE_CURRENT_PASSWORD_WRONG);
            }
            RuleProblems.add(errors, AccountRules.passwordViolation("newPassword", body.newPassword(), true));
        }
        errors.throwIfAny();

        user.resetPassword(body.newPassword());
        weblogger.getUserManager().saveUser(user);
        weblogger.flush();
        CredentialStampFilter.restamp(request, user);
        return ResponseEntity.noContent().build();
    }

    private static User requireUser(HttpServletRequest request) {
        if (request.getAttribute("authenticatedUser") instanceof User user) {
            return user;
        }
        throw ApiException.authenticationRequired();
    }
}
