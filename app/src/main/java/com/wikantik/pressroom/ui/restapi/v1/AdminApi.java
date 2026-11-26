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
import java.util.function.Consumer;
import java.util.function.Function;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.wikantik.pressroom.WebloggerException;
import com.wikantik.pressroom.business.RevisionSweep;
import com.wikantik.pressroom.business.UserManager;
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
            views.add(AdminDtos.toView(user, roles(user)));
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
    public ResponseEntity<AdminDtos.UserView> createUser(@RequestBody AdminDtos.UserCreate body)
            throws WebloggerException {
        // The account rules the person screen applies (AccountRules); the trimming and the
        // screen name's default are the API's own.
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
        errors.throwIfAny();

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

        if (!PasswordLinkMailer.isReady()) {
            throw ApiException.problem(ProblemCode.USER_MAIL_NOT_CONFIGURED);
        }

        User user = new User();
        user.setUserName(userName);
        user.setScreenName(screenName);
        user.setFullName(fullName);
        user.setLocale(locale);
        user.setTimeZone(timeZone);
        user.setEmailAddress(emailAddress);
        user.setEnabled(Boolean.FALSE);
        user.setDateCreated(new Date());
        // Never accepted from the caller -- see class javadoc. A random
        // value nobody is ever told, matching CreateUserBean's own
        // blank-password branch; the account is unusable until the emailed
        // link sets a real one.
        user.resetPassword(TokenGenerator.newToken());

        mgr.addUser(user);
        weblogger.flush();

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

        return ResponseEntity.status(HttpStatus.CREATED).body(AdminDtos.toView(user, roles(user)));
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
        boolean enabledBefore = Boolean.TRUE.equals(user.getEnabled());

        // Each value is set only when it passes: the user is a managed entity.
        FieldErrors errors = new FieldErrors();
        applyField(errors, body.emailAddress(),
                v -> AccountRules.emailProblem(v, AccountRules.EmailUse.REPLACEMENT), user::setEmailAddress);
        applyField(errors, body.screenName(), AccountRules::screenNameProblem, user::setScreenName);
        applyField(errors, body.fullName(), AccountRules::fullNameProblem, user::setFullName);
        applyField(errors, body.locale(), AccountRules::localeProblem, user::setLocale);
        applyField(errors, body.timeZone(), AccountRules::timeZoneProblem, user::setTimeZone);
        // Only a single-sign-on account has an author role to edit (the person screen's own rule); any other
        // account's follows its memberships.
        if (body.writer() != null && !user.isSsoBound()) {
            errors.add("writer", ProblemCode.USER_WRITER_SSO_ONLY);
        }
        errors.throwIfAny();
        if (body.enabled() != null) {
            user.setEnabled(body.enabled());
        }

        mgr.saveUser(user);
        if (body.writer() != null) {
            AccountRoles.sync(mgr, user, AccountRoles.EDITOR, body.writer(), mgr.hasRole(AccountRoles.EDITOR, user));
        }
        weblogger.flush();
        boolean enabledNow = Boolean.TRUE.equals(user.getEnabled());
        if (enabledBefore != enabledNow) {
            AdminLog.record(enabledNow ? "account.enabled" : "account.disabled",
                    (User) request.getAttribute("authenticatedUser"), "account=" + user.getUserName());
        }
        return AdminDtos.toView(user, roles(user));
    }

    /**
     * One optional text field of a user patch: absent leaves it alone; present is trimmed, judged by its rule and
     * set only when it passes (the user is a managed entity), its problem recorded either way.
     */
    private static void applyField(FieldErrors errors, String value,
            Function<String, RuleViolation> rule, Consumer<String> setter) {
        if (value == null) {
            return;
        }
        String trimmed = value.trim();
        RuleViolation problem = rule.apply(trimmed);
        if (problem == null) {
            setter.accept(trimmed);
        }
        RuleProblems.add(errors, problem);
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
