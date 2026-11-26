package com.wikantik.pressroom.ui.restapi.v1;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.mail.MessagingException;
import jakarta.servlet.http.HttpServletRequest;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.wikantik.pressroom.WebloggerException;
import com.wikantik.pressroom.business.RevisionSweep;
import com.wikantik.pressroom.business.UserManager;
import com.wikantik.pressroom.business.comments.CommentService;
import com.wikantik.pressroom.config.WebloggerRuntimeConfig;
import com.wikantik.pressroom.config.runtime.ConfigDef;
import com.wikantik.pressroom.config.runtime.DisplayGroup;
import com.wikantik.pressroom.config.runtime.PropertyDef;
import com.wikantik.pressroom.pojos.GlobalPermission;
import com.wikantik.pressroom.pojos.RuntimeConfigProperty;
import com.wikantik.pressroom.pojos.User;
import com.wikantik.pressroom.ui.controllers.AccountRoles;
import com.wikantik.pressroom.ui.controllers.AccountRules;
import com.wikantik.pressroom.ui.controllers.Memberships;
import com.wikantik.pressroom.ui.controllers.AdminLog;
import com.wikantik.pressroom.ui.controllers.RuleViolation;
import com.wikantik.pressroom.ui.controllers.SiteSettingsEffects;
import com.wikantik.pressroom.ui.controllers.SiteSettingsRules;
import com.wikantik.pressroom.ui.controllers.UISecurityEnforced;
import com.wikantik.pressroom.ui.controllers.core.PasswordLinkMailer;
import com.wikantik.pressroom.ui.core.PressroomLoginSessionManager;
import com.wikantik.pressroom.ui.controllers.core.PasswordLinkService;
import com.wikantik.pressroom.ui.restapi.AdminAccountGuard;
import com.wikantik.pressroom.ui.restapi.ApiException;
import com.wikantik.pressroom.ui.restapi.FieldErrors;
import com.wikantik.pressroom.ui.restapi.Paging;
import com.wikantik.pressroom.ui.restapi.ProblemCode;
import com.wikantik.pressroom.ui.restapi.RuleProblems;
import com.wikantik.pressroom.ui.restapi.auth.AdminScoped;
import com.wikantik.pressroom.ui.restapi.auth.Caller;
import com.wikantik.pressroom.ui.restapi.dto.AdminDtos;
import com.wikantik.pressroom.ui.restapi.dto.MetaDtos;
import com.wikantik.pressroom.util.TokenGenerator;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import java.time.Instant;
import com.wikantik.pressroom.pojos.ApiToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Site-wide user administration and runtime configuration -- {@code
 * UISecurityEnforced} declares {@code GlobalPermission.ADMIN}, the same
 * requirement {@code UserAdminController}/{@code UserEditController}/{@code
 * GlobalConfigController} enforce for the JSP admin screens; {@code
 * PressroomHandlerInterceptor} is what actually enforces it, and this
 * controller adds no permission checking of its own. Never weblog-scoped
 * ({@code isWeblogRequired() == false}) -- these actions have no per-weblog
 * meaning at all.
 *
 * <p><b>{@code POST /users} never accepts a password.</b> The account is
 * created disabled with a random, never-disclosed password, and a {@code
 * PASSWORD_SET} token is issued and emailed through the same {@link
 * PasswordLinkMailer#sendLink} path {@code UserEditController}'s "send
 * set-password link" admin action uses -- no plaintext password ever
 * crosses this API. Mail must already be configured
 * ({@link PasswordLinkMailer#isReady()}) or the request is refused before
 * any account is created, matching {@code UserEditController}'s own rule
 * that a blank password is only safe to accept when a link can actually be
 * delivered.
 */
@Tag(name = "Site administration", description = "Site-wide administration (site admins): user accounts, revision "
        + "retention, runtime properties.")
@RestController
@RequestMapping("/v1/admin")
@AdminScoped
public class AdminApi extends BaseApiController implements UISecurityEnforced {

    private static final Logger log = LoggerFactory.getLogger(AdminApi.class);

    private static final int MAX_LIMIT = Paging.MAX_LIMIT;

    /** Expires the weblogs showing an account's comments when it is renamed (as the person screen does). */
    @Autowired
    @Lazy
    CommentService commentService;

    // ------------------------------------------------------------- users

    @Operation(operationId = "usersList")
    @GetMapping("/users")
    public AdminDtos.UserPage listUsers(
            @RequestParam(value = "enabled", required = false) Boolean enabled,
            @Parameter(description = "Only accounts whose user name, screen name, full name or email contains this "
                    + "text, ignoring case.")
            @RequestParam(value = "q", required = false) String q,
            @Parameter(description = "people, agents or all (the default): the admin screen's tabs.",
                    schema = @Schema(allowableValues = {"all", "people", "agents"}))
            @RequestParam(value = "kind", required = false) String kind,
            @RequestParam(value = "offset", defaultValue = "0") int offset,
            @RequestParam(value = "limit", defaultValue = "50") int limit) throws WebloggerException {
        // Same trap as EntriesApi.list: a negative offset reaches JPA's
        // setFirstResult and throws; UserManager.getUsers treats length==-1
        // as NO LIMIT (JPAUserManagerImpl only calls setMaxResults when
        // length != -1), so limit=-1 would silently run an unbounded query
        // instead of failing.
        Paging.requireRange(limit, offset);
        int boundedLimit = Math.min(limit, MAX_LIMIT);
        User.Kind accountKind = accountKind(kind);
        String text = StringUtils.trimToNull(q);

        UserManager mgr = weblogger.getUserManager();
        // One row past the page decides hasMore: no count query.
        List<User> rows;
        if (accountKind == null && text == null) {
            rows = mgr.getUsers(enabled, null, null, offset, boundedLimit + 1);
        } else {
            // The screen's own search (UserManager.getAccounts, a few dozen rows filtered in memory), then the
            // enabled filter and the page applied to what it found.
            rows = mgr.getAccounts(accountKind, text).stream()
                    .filter(user -> enabled == null || enabled == Boolean.TRUE.equals(user.getEnabled()))
                    .skip(offset).limit((long) boundedLimit + 1).toList();
        }
        List<AdminDtos.UserView> views = new ArrayList<>();
        for (User user : rows.subList(0, Math.min(rows.size(), boundedLimit))) {
            views.add(view(user));
        }
        return new AdminDtos.UserPage(views, offset, boundedLimit, rows.size() > boundedLimit);
    }

    /** The screen's tab for {@code kind}: null for all. Anything but people, agents or all (lower case) is refused. */
    private static User.Kind accountKind(String kind) {
        if (kind == null || "all".equals(kind)) {
            return null;
        }
        if ("people".equals(kind)) {
            return User.Kind.PERSON;
        }
        if ("agents".equals(kind)) {
            return User.Kind.AGENT;
        }
        throw ApiException.problemOnField("kind", ProblemCode.PARAMETER_INVALID_VALUE, "kind");
    }

    @Operation(operationId = "usersCreate")
    @ApiResponse(responseCode = "201", description = "Created.")
    @PostMapping("/users")
    public ResponseEntity<AdminDtos.UserView> createUser(HttpServletRequest request,
            @RequestBody AdminDtos.UserCreate body) throws WebloggerException {
        // A typed password and the administrator role are the admin app's alone (SA-18): refused to any other
        // caller before the body is judged, so nothing about the request is learnt from its other fields.
        AdminAccountGuard.requireSessionToSetPassword(Caller.current(), body.password());
        AdminAccountGuard.requireSessionToChange(Caller.current(), body.administrator(), false);
        boolean typedPassword = StringUtils.isNotEmpty(body.password());
        User user = judgedNewAccount(body);
        String userName = user.getUserName();

        UserManager mgr = weblogger.getUserManager();
        // Checked with enabled=null (any status) rather than relying on
        // UserManager.addUser's own duplicate check, which only looks among
        // ENABLED users -- a same-named disabled account would otherwise
        // reach addUser and fail as a bare, unguarded WebloggerException.
        // A lookup that fails throws: the opaque 500.
        RuleViolation taken = AccountRules.userNameTaken(userName, mgr);
        if (taken != null) {
            throw RuleProblems.problem(taken, true);
        }

        // A typed password is handed over in person, so no link and no mail; without one the account could
        // never be used unless a link can be delivered.
        if (!typedPassword && !PasswordLinkMailer.isReady()) {
            throw ApiException.problem(ProblemCode.USER_MAIL_NOT_CONFIGURED);
        }

        if (typedPassword) {
            // Session only: the person screen's typed-password create, enabled at once unless the form's
            // Enabled box was cleared.
            user.setEnabled(body.enabled() == null || body.enabled());
            user.resetPassword(body.password());
        } else {
            // Never accepted from a token -- see class javadoc. A random value nobody is ever told, matching
            // CreateUserBean's own blank-password branch; the account is unusable until the emailed link
            // sets a real one.
            user.setEnabled(Boolean.TRUE.equals(body.enabled()));
            user.resetPassword(TokenGenerator.newToken());
        }

        mgr.addUser(user);
        boolean administrator = Boolean.TRUE.equals(body.administrator());
        if (administrator) {
            mgr.grantRole("admin", user);
        }
        weblogger.flush();
        if (administrator) {
            AdminLog.record("siteAdmin.granted", (User) request.getAttribute("authenticatedUser"),
                    "account=" + user.getUserName());
        }
        if (typedPassword) {
            return ResponseEntity.status(HttpStatus.CREATED).body(view(user));
        }

        try {
            PasswordLinkService.send(weblogger, user, PasswordLinkService.DEFAULT_SUBJECT);
        } catch (MessagingException e) {
            // The account exists either way -- disabled and resendable from
            // the JSP UserAdmin screen's "send set-password link" action --
            // so this is caught and logged rather than left to bubble as an
            // opaque 500, the same convention CLAUDE.md's Audience section
            // describes for best-effort side work. Unlike that convention,
            // though, the request is NOT allowed to read as an ordinary
            // success: 502, not 400 or 201, because a 400 reads as "your
            // request was malformed" (inviting a retry that then 409s on the
            // account that already exists) and a bare 201 would silently
            // hide that the one thing this endpoint exists to do -- get a
            // usable link to the new account -- did not happen.
            log.error("Set-password link could not be sent for new user {}", userName, e);
            throw ApiException.problem(ProblemCode.USER_PASSWORD_LINK_NOT_SENT, e);
        }

        return ResponseEntity.status(HttpStatus.CREATED).body(view(user));
    }

    /**
     * The account rules the person screen applies ({@link AccountRules}); the trimming and the screen name's
     * default are the API's own. Every broken field is named in one 400; what passes is the new, unsaved account
     * (no password, not yet enabled).
     */
    private static User judgedNewAccount(AdminDtos.UserCreate body) {
        FieldErrors errors = new FieldErrors();
        String userName = body.userName() == null ? null : body.userName().trim();
        if (StringUtils.isBlank(userName)) {
            // Blank as isBlank judges it: trim() leaves Unicode whitespace (an em space) in place,
            // and such a name is missing, not one with disallowed characters (the screens keep
            // AccountRules' isEmpty: what was typed is judged as typed).
            errors.add(AccountRules.USER_NAME, ProblemCode.FIELD_REQUIRED, AccountRules.USER_NAME);
        } else {
            RuleProblems.add(errors, AccountRules.userNameProblem(userName));
        }

        String emailAddress = body.emailAddress() == null ? null : body.emailAddress().trim();
        RuleProblems.add(errors, AccountRules.emailProblem(emailAddress, AccountRules.EmailUse.REQUIRED));

        String screenName = StringUtils.isBlank(body.screenName()) ? userName : body.screenName().trim();
        // Blank only when the username it defaulted to is, which is refused on its own field.
        if (StringUtils.isNotBlank(screenName)) {
            RuleProblems.add(errors, AccountRules.screenNameProblem(screenName));
        }
        // pressroom_user.fullname is NOT NULL: it defaults to the screen name, as the screen name does to the
        // user name, when not sent.
        String fullName = body.fullName() == null ? screenName : body.fullName().trim();
        if (body.fullName() != null) {
            RuleProblems.add(errors, AccountRules.fullNameProblem(fullName));
        }
        String locale = body.locale() == null ? null : body.locale().trim();
        if (locale != null) {
            RuleProblems.add(errors, AccountRules.localeProblem(locale));
        }
        String timeZone = body.timeZone() == null ? null : body.timeZone().trim();
        if (timeZone != null) {
            RuleProblems.add(errors, AccountRules.timeZoneProblem(timeZone));
        }
        RuleProblems.add(errors, AccountRules.passwordViolation("password", body.password(), false));
        errors.throwIfAny();

        User user = new User();
        user.setUserName(userName);
        user.setScreenName(screenName);
        user.setFullName(fullName);
        user.setLocale(locale);
        user.setTimeZone(timeZone);
        user.setEmailAddress(emailAddress);
        user.setDateCreated(new Date());
        return user;
    }

    @Operation(operationId = "usersUpdate")
    @PatchMapping("/users/{userName}")
    public AdminDtos.UserView patchUser(HttpServletRequest request,
            @PathVariable("userName") String userName, @RequestBody AdminDtos.UserPatch body)
            throws WebloggerException {
        User user = requireUser(userName);
        UserManager mgr = weblogger.getUserManager();
        Caller caller = Caller.current();
        AdminAccountGuard.requireNotAgent(caller, user);
        AdminAccountGuard.requireNotAdmin(caller, mgr, user);
        AdminAccountGuard.requireEmailChangeAllowed(caller, user, body.emailAddress());
        // Lasting access and credentials are the admin app's alone (SA-18), refused before anything is judged.
        requireSessionForPrivileged(caller, mgr, user, body);
        User actor = (User) request.getAttribute("authenticatedUser");
        boolean self = actor != null && user.getUserName().equals(actor.getUserName());
        boolean enabledBefore = Boolean.TRUE.equals(user.getEnabled());
        String screenNameBefore = user.getScreenName();

        // Your own password changes only through PUT /v1/me/password, which asks for the current one: a signed-in
        // session alone must not be enough to take the account over for good.
        if (self && StringUtils.isNotEmpty(body.password())) {
            throw ApiException.problem(ProblemCode.USER_OWN_PASSWORD_USE_PROFILE);
        }
        // What the person screen refuses before applying anything: an admin locking themselves out.
        if (self) {
            boolean droppingOwnAdmin = Boolean.FALSE.equals(body.administrator()) && holdsAdmin(mgr, user);
            RuleProblems.refuseViolations(AccountRules.selfLockoutProblems(Boolean.FALSE.equals(body.enabled()),
                    droppingOwnAdmin));
        }

        FieldErrors errors = new FieldErrors();
        judgeAccountFields(errors, user, body);
        boolean passwordChanged = StringUtils.isNotEmpty(body.password());
        errors.throwIfAny();
        if (body.enabled() != null) {
            user.setEnabled(body.enabled());
        }
        if (passwordChanged) {
            user.resetPassword(body.password());
        }
        // For another account, a new password or a disable drops its cached login session now
        // (PressroomLoginSessionManager.invalidate). That is only a cache drop: a changed password ends its other
        // sessions through the password stamp (CredentialStampFilter), but a disabled account's existing sessions
        // are refused by the enabled checks downstream (the API's session filter, the UI's user lookup).
        if (!self && (passwordChanged || !Boolean.TRUE.equals(user.getEnabled()))) {
            PressroomLoginSessionManager.getInstance().invalidate(user.getUserName());
        }

        mgr.saveUser(user);
        if (body.writer() != null) {
            AccountRoles.sync(mgr, user, AccountRoles.EDITOR, body.writer(), mgr.hasRole(AccountRoles.EDITOR, user));
        }
        String roleEvent = body.administrator() == null ? null
                : AccountRoles.sync(mgr, user, "admin", body.administrator(), holdsAdmin(mgr, user));
        weblogger.flush();
        boolean enabledNow = Boolean.TRUE.equals(user.getEnabled());
        if (enabledBefore != enabledNow) {
            AdminLog.record(enabledNow ? "account.enabled" : "account.disabled", actor,
                    "account=" + user.getUserName());
        }
        if (roleEvent != null) {
            AdminLog.record(roleEvent, actor, "account=" + user.getUserName());
        }
        // The screen name signs the account's comments, and rendered pages expire only through their weblog.
        if (!Objects.equals(screenNameBefore, user.getScreenName())) {
            commentService.expireWhereShown(user);
        }
        return view(user);
    }

    /** Whether the account holds the site administrator role; a lookup that fails throws, so the caller refuses. */
    private static boolean holdsAdmin(UserManager mgr, User user) throws WebloggerException {
        return mgr.checkPermission(new GlobalPermission(List.of(GlobalPermission.ADMIN)), user);
    }

    /** The administrator flag, the editor role and a typed password: a non-session caller may only resend stored values. */
    private void requireSessionForPrivileged(Caller caller, UserManager mgr, User user, AdminDtos.UserPatch body)
            throws WebloggerException {
        AdminAccountGuard.requireSessionToChange(caller, body.writer(),
                body.writer() != null && mgr.hasRole(AccountRoles.EDITOR, user));
        AdminAccountGuard.requireSessionToChange(caller, body.administrator(),
                body.administrator() != null && holdsAdmin(mgr, user));
        AdminAccountGuard.requireSessionToSetPassword(caller, body.password());
    }

    /**
     * Judges every field of a user patch, recording each broken one on {@code errors}: each value is set only
     * when it passes (the user is a managed entity). The writer role is a single-sign-on account's only; the
     * administrator role is never one's; a typed password belongs to a person with a password of their own.
     */
    private static void judgeAccountFields(FieldErrors errors, User user, AdminDtos.UserPatch body) {
        AccountFieldPatch.apply(errors, body.emailAddress(),
                v -> AccountRules.emailProblem(v, AccountRules.EmailUse.REPLACEMENT), user::setEmailAddress);
        AccountFieldPatch.apply(errors, body.screenName(), AccountRules::screenNameProblem, user::setScreenName);
        AccountFieldPatch.apply(errors, body.fullName(), AccountRules::fullNameProblem, user::setFullName);
        AccountFieldPatch.apply(errors, body.locale(), AccountRules::localeProblem, user::setLocale);
        AccountFieldPatch.apply(errors, body.timeZone(), AccountRules::timeZoneProblem, user::setTimeZone);
        // Only a single-sign-on account has an author role to edit (the person screen's own rule); any other
        // account's follows its memberships.
        if (body.writer() != null && !user.isSsoBound()) {
            errors.add("writer", ProblemCode.USER_WRITER_SSO_ONLY);
        }
        if (user.isSsoBound() && Boolean.TRUE.equals(body.administrator())) {
            RuleProblems.add(errors, AccountRules.ssoAdminProblem());
        }
        if (StringUtils.isNotEmpty(body.password())) {
            if (user.isSsoBound()) {
                errors.add("password", ProblemCode.USER_SSO_BOUND);
            } else if (user.isAgent()) {
                errors.add("password", ProblemCode.USER_NOT_A_PERSON);
            } else {
                RuleProblems.add(errors, AccountRules.passwordViolation("password", body.password(), false));
            }
        }
    }

    /**
     * Emails the person a set-password link, as the person screen's "send set-password link" does
     * ({@link PasswordLinkService}). The link goes to the address on the account, never to the caller. A token
     * may not do this for an agent or an admin account (the account guard); neither screen nor API sends one
     * to a single-sign-on account.
     */
    @Operation(operationId = "usersSendPasswordLink")
    @ApiResponse(responseCode = "204", description = "The link was mailed to the account's address.")
    @PostMapping("/users/{userName}/password-link")
    public ResponseEntity<Void> sendPasswordLink(@PathVariable("userName") String userName)
            throws WebloggerException {
        User user = requireUser(userName);
        Caller caller = Caller.current();
        AdminAccountGuard.requireNotAgent(caller, user);
        AdminAccountGuard.requireNotAdmin(caller, weblogger.getUserManager(), user);
        PasswordLinkService.Refusal refusal = PasswordLinkService.refusal(user);
        if (refusal != null) {
            throw ApiException.problem(switch (refusal) {
                case NOT_A_PERSON -> ProblemCode.USER_NOT_A_PERSON;
                case SSO_BOUND -> ProblemCode.USER_SSO_BOUND;
                case MAIL_NOT_CONFIGURED -> ProblemCode.USER_MAIL_NOT_CONFIGURED;
            });
        }
        try {
            PasswordLinkService.send(weblogger, user, PasswordLinkService.DEFAULT_SUBJECT);
        } catch (MessagingException e) {
            log.error("Set-password link could not be sent to {}", userName, e);
            throw ApiException.problem(ProblemCode.USER_PASSWORD_LINK_FAILED, e);
        }
        return ResponseEntity.noContent().build();
    }

    /** The weblogs an account belongs to and its level on each: the person page's Weblogs list, read only. */
    @Operation(operationId = "usersListWeblogs")
    @GetMapping("/users/{userName}/weblogs")
    public AdminDtos.UserWeblogsPage userWeblogs(@PathVariable("userName") String userName)
            throws WebloggerException {
        List<MetaDtos.WeblogMembership> items = new ArrayList<>();
        for (Memberships.Row row : Memberships.of(weblogger, requireUser(userName))) {
            items.add(new MetaDtos.WeblogMembership(row.handle(), row.name(), row.level()));
        }
        return new AdminDtos.UserWeblogsPage(items);
    }

    /**
     * The site space's pages: the Global Config front-door choices. The site space is withheld from every route
     * that names it ({@code PressroomHandlerInterceptor}), so the pages come from here, for a site admin; an empty
     * list while no site space exists (it is created by the first admin read of the main menu).
     */
    @Operation(operationId = "sitePagesList", summary = "The site space's pages, for the front-door choices.")
    @GetMapping("/site-pages")
    public List<AdminDtos.SitePageChoice> sitePages() throws WebloggerException {
        com.wikantik.pressroom.pojos.Weblog site = weblogger.getWeblogManager().getSiteSpace();
        List<AdminDtos.SitePageChoice> choices = new ArrayList<>();
        if (site != null) {
            for (com.wikantik.pressroom.pojos.WeblogPage page : weblogger.getWeblogPageManager().getPages(site)) {
                choices.add(new AdminDtos.SitePageChoice(page.getSlug(), page.getTitle(), page.getStatus().name()));
            }
        }
        return choices;
    }

    /** One account, as the list shows it: the person page loads it by name instead of searching. */
    @Operation(operationId = "usersGet")
    @GetMapping("/users/{userName}")
    public AdminDtos.UserView getUser(@PathVariable("userName") String userName) throws WebloggerException {
        return view(requireUser(userName));
    }

    /** The view with what the list prints per row: the weblog count and the last sign-in (an agent's last token use). */
    private AdminDtos.UserView view(User user) throws WebloggerException {
        UserManager mgr = weblogger.getUserManager();
        return AdminDtos.toView(user, roles(user), mgr.getWeblogPermissions(user).size(), lastActive(user));
    }

    private Instant lastActive(User user) throws WebloggerException {
        if (!user.isAgent()) {
            return user.getLastLogin() == null ? null : user.getLastLogin().toInstant();
        }
        Instant newest = null;
        for (ApiToken token : weblogger.getApiTokenManager().getTokens(user)) {
            Instant used = token.getLastUsedAt() == null ? null : token.getLastUsedAt().toInstant();
            if (used != null && (newest == null || used.isAfter(newest))) {
                newest = used;
            }
        }
        return newest;
    }

    private User requireUser(String userName) throws WebloggerException {
        User user = StringUtils.isBlank(userName) ? null : weblogger.getUserManager().getUserByUserName(userName, null);
        if (user == null) {
            throw ApiException.problem(ProblemCode.USER_NOT_FOUND);
        }
        return user;
    }

    private List<String> roles(User user) throws WebloggerException {
        return weblogger.getUserManager().getRoles(user);
    }

    // ---------------------------------------------------------- config

    /** The optional body of a revision sweep. */
    public record RevisionSweepRequest(
            @Schema(description = "False runs the sweep and removes revisions. True, or no body at all, only "
                    + "reports what it would remove.")
            Boolean dryRun) {
    }

    /**
     * What the revision store holds, computed now: totals for posts and
     * pages, kept revisions, the largest items, the last sweep, and what the
     * next sweep would remove at the current limits.
     */
    @Operation(operationId = "revisionsGetStats")
    @GetMapping("/revisions")
    public RevisionSweep.Stats revisions() throws WebloggerException {
        return weblogger.getWeblogEntryManager().revisionStats();
    }

    /**
     * Runs the revision sweep, or by default only reports what it would
     * remove: removal is asked for in so many words ({@code "dryRun":
     * false}), never by leaving the body out.
     */
    @Operation(operationId = "revisionsSweep")
    @PostMapping("/revisions/sweep")
    public RevisionSweep.Report sweepRevisions(@RequestBody(required = false) RevisionSweepRequest body) {
        boolean dryRun = body == null || !Boolean.FALSE.equals(body.dryRun());
        return weblogger.getWeblogEntryManager().sweepRevisions(dryRun);
    }

    /**
     * Every runtime-settable property, current value first, falling back to
     * its declared default when no row has ever been saved for it.
     */
    @Operation(operationId = "configList")
    @GetMapping("/config")
    public List<AdminDtos.ConfigEntry> listConfig() throws WebloggerException {
        List<AdminDtos.ConfigEntry> entries = new ArrayList<>();
        for (ConfigDef configDef : WebloggerRuntimeConfig.getRuntimeConfigDefs().getConfigDefs()) {
            for (DisplayGroup group : configDef.getDisplayGroups()) {
                for (PropertyDef def : group.getPropertyDefs()) {
                    entries.add(new AdminDtos.ConfigEntry(def.getName(), currentValue(def), def.getType()));
                }
            }
        }
        return entries;
    }

    /**
     * What the Global Config screen draws: the display groups of {@code runtimeConfigDefs.xml} in their declared
     * order, each property with its message key, type and default. Definitions only; values come from
     * {@code GET /config}.
     */
    @Operation(operationId = "configGetDefinitions")
    @GetMapping("/config/definitions")
    public List<AdminDtos.ConfigGroup> configDefinitions() {
        List<AdminDtos.ConfigGroup> groups = new ArrayList<>();
        for (ConfigDef configDef : WebloggerRuntimeConfig.getRuntimeConfigDefs().getConfigDefs()) {
            for (DisplayGroup group : configDef.getDisplayGroups()) {
                List<AdminDtos.ConfigPropertyDefinition> properties = new ArrayList<>();
                for (PropertyDef def : group.getPropertyDefs()) {
                    properties.add(new AdminDtos.ConfigPropertyDefinition(def.getName(), def.getKey(),
                            def.getType(), def.getDefaultValue(), def.getRows(), def.getCols()));
                }
                groups.add(new AdminDtos.ConfigGroup(group.getName(), group.getKey(), properties));
            }
        }
        return groups;
    }

    /**
     * Body is a flat name-to-new-value map, not a list of {@code
     * ConfigEntry} -- the {@code type} is declared by {@code
     * runtimeConfigDefs.xml}, not something a caller gets to assert, and a
     * map is the natural shape for "set these properties" either way. Every
     * name is checked against {@link AdminDtos#requireRuntimeProperty} --
     * the allowlist -- and a null value is refused, each at once; then
     * {@link SiteSettingsRules}, the rules Global Config applies, judges the
     * values (type and range, the front door, the privacy page; strings are
     * stored trimmed), and every value it refuses is named in one 400. A
     * refused request never leaves a partial write behind. After the save,
     * {@link SiteSettingsEffects#afterSave} expires cached pages and
     * refreshes discovery as the screen's save does.
     */
    @Operation(operationId = "configUpdate")
    @PatchMapping("/config")
    public List<AdminDtos.ConfigEntry> patchConfig(@RequestBody Map<String, String> body)
            throws WebloggerException {
        if (body == null || body.isEmpty()) {
            throw ApiException.problem(ProblemCode.CONFIG_PROPERTIES_REQUIRED);
        }
        AdminAccountGuard.requireApiManageable(Caller.current(), body.keySet());

        for (Map.Entry<String, String> entry : body.entrySet()) {
            AdminDtos.requireRuntimeProperty(entry.getKey());
            if (entry.getValue() == null) {
                throw ApiException.problemOnField(entry.getKey(), ProblemCode.CONFIG_VALUE_NULL, entry.getKey());
            }
        }

        // Every runtime property is pre-seeded into pressroom_properties at
        // startup (JPAPropertiesManagerImpl.initializeMissingProps), so this
        // is (almost) always an update, never an insert.
        // JPAPersistenceStrategy.store() calls em.persist() outright for any
        // not-yet-managed instance -- it does not upsert -- so handing it a
        // bare `new RuntimeConfigProperty(name, value)` for a name that
        // already has a row throws a raw, unguarded duplicate-key
        // DatabaseException. Loading the existing managed row and mutating it
        // in place is the same pattern GlobalConfigController's save uses for
        // the JSP admin screen. The front door's two rows are read too: the
        // rules judge them as a pair.
        Map<String, RuntimeConfigProperty> rows = new LinkedHashMap<>();
        for (String name : body.keySet()) {
            rows.put(name, weblogger.getPropertiesManager().getProperty(name));
        }
        for (String name : List.of(SiteSettingsRules.FRONT_DOOR_BLOG, SiteSettingsRules.FRONT_DOOR_PAGE)) {
            if (!rows.containsKey(name)) {
                rows.put(name, weblogger.getPropertiesManager().getProperty(name));
            }
        }
        Map<String, String> before = new LinkedHashMap<>();
        rows.forEach((name, row) -> before.put(name, row == null ? null : row.getValue()));

        SiteSettingsRules.Judgement judgement = SiteSettingsRules.judge(body, AdminDtos::findPropertyDef, before,
                SiteSettingsRules.publishedPages(weblogger));
        RuleProblems.refuseViolations(judgement.violations());

        Map<String, RuntimeConfigProperty> toSave = new LinkedHashMap<>();
        List<AdminDtos.ConfigEntry> result = new ArrayList<>();
        for (Map.Entry<String, String> entry : judgement.values().entrySet()) {
            String name = entry.getKey();
            RuntimeConfigProperty property = rows.get(name);
            if (property != null) {
                property.setValue(entry.getValue());
            } else {
                property = new RuntimeConfigProperty(name, entry.getValue());
            }
            toSave.put(name, property);
            result.add(new AdminDtos.ConfigEntry(name, entry.getValue(), AdminDtos.findPropertyDef(name).getType()));
        }

        weblogger.getPropertiesManager().saveProperties(toSave);
        weblogger.flush();
        SiteSettingsEffects.afterSave(weblogger, judgement.values(), before);
        return result;
    }

    private String currentValue(PropertyDef def) throws WebloggerException {
        RuntimeConfigProperty stored = weblogger.getPropertiesManager().getProperty(def.getName());
        if (stored != null && stored.getValue() != null) {
            return stored.getValue();
        }
        return def.getDefaultValue();
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
