package com.wikantik.pressroom.ui.restapi.v1;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.apache.commons.lang3.StringUtils;
import com.wikantik.pressroom.WebloggerException;
import com.wikantik.pressroom.business.ApiTokenManager;
import com.wikantik.pressroom.business.UserManager;
import com.wikantik.pressroom.pojos.ApiToken;
import com.wikantik.pressroom.pojos.GlobalPermission;
import com.wikantik.pressroom.pojos.User;
import com.wikantik.pressroom.ui.controllers.AccountRules;
import com.wikantik.pressroom.ui.controllers.AgentAccounts;
import com.wikantik.pressroom.ui.controllers.RuleViolation;
import com.wikantik.pressroom.ui.controllers.TokenOps;
import com.wikantik.pressroom.ui.controllers.TokenPins;
import com.wikantik.pressroom.ui.controllers.UISecurityEnforced;
import com.wikantik.pressroom.ui.restapi.ApiException;
import com.wikantik.pressroom.ui.restapi.ColumnLimits;
import com.wikantik.pressroom.ui.restapi.FieldErrors;
import com.wikantik.pressroom.ui.restapi.Paging;
import com.wikantik.pressroom.ui.restapi.ProblemCode;
import com.wikantik.pressroom.ui.restapi.RuleProblems;
import com.wikantik.pressroom.ui.restapi.auth.AdminScoped;
import com.wikantik.pressroom.ui.restapi.auth.SessionOnly;
import com.wikantik.pressroom.ui.restapi.dto.AgentDtos;
import com.wikantik.pressroom.ui.restapi.dto.TokenDtos;
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
 * Agent accounts for the admin app (SA-18, session only): create, read, change (name, notes, the Site
 * administrator switch, enabled) and mint and revoke their tokens. An agent is an account of kind AGENT that
 * never signs in; creating one creates a new identity with its own credentials, so a stolen token could
 * outlive its own revocation. Every rule and write is {@code AgentAccounts}' and {@code TokenOps}', the ones
 * {@code AgentController} applies, and each change writes the {@code pressroom.admin} line the screen does.
 * Visibility comes first (a caller that is not a site admin never gets here), then the session-only guard.
 */
@Tag(name = "Agents", description = "Agent accounts and the tokens minted for them (session only: the admin app, "
        + "never a token).")
@RestController
@RequestMapping("/v1/admin/agents")
@AdminScoped
@SessionOnly
public class AgentsApi extends BaseApiController implements UISecurityEnforced {

    /** The agents, as the Users page's Agents tab lists them: by text, enabled or not, a page at a time. */
    @Operation(operationId = "agentsList", summary = "List agent accounts (session only).")
    @GetMapping("")
    public AgentDtos.AgentPage list(
            @Parameter(description = "Only agents whose user name, name or email contains this text, ignoring case.")
            @RequestParam(value = "q", required = false) String q,
            @RequestParam(value = "enabled", required = false) Boolean enabled,
            @RequestParam(value = "offset", defaultValue = "0") int offset,
            @RequestParam(value = "limit", defaultValue = "50") int limit) throws WebloggerException {
        Paging.requireRange(limit, offset);
        int bounded = Math.min(limit, Paging.MAX_LIMIT);
        UserManager users = weblogger.getUserManager();
        // One row past the page decides hasMore: no count query.
        List<User> rows = users.getAccounts(User.Kind.AGENT, StringUtils.trimToNull(q)).stream()
                .filter(agent -> enabled == null || enabled == Boolean.TRUE.equals(agent.getEnabled()))
                .skip(offset).limit((long) bounded + 1).toList();
        List<AgentDtos.AgentView> views = new ArrayList<>();
        for (User agent : rows.subList(0, Math.min(rows.size(), bounded))) {
            views.add(AgentDtos.toView(agent, AgentAccounts.isSiteAdmin(users, agent), null));
        }
        return new AgentDtos.AgentPage(views, offset, bounded, rows.size() > bounded);
    }

    /** The New agent form: every broken field is named at once, a taken user name is a 409. */
    @Operation(operationId = "agentsCreate", summary = "Create an agent account (session only).")
    @ApiResponse(responseCode = "201", description = "Created.")
    @PostMapping("")
    public ResponseEntity<AgentDtos.AgentView> create(HttpServletRequest request,
            @RequestBody AgentDtos.AgentCreate body) throws WebloggerException {
        User actor = requireAuthenticatedUser(request);
        FieldErrors errors = new FieldErrors();
        String userName = body.userName() == null ? null : body.userName().trim();
        if (StringUtils.isBlank(userName)) {
            errors.add(AccountRules.USER_NAME, ProblemCode.FIELD_REQUIRED, AccountRules.USER_NAME);
        } else {
            RuleProblems.add(errors, AccountRules.userNameProblem(userName));
        }
        String name = StringUtils.trimToNull(body.name());
        RuleProblems.add(errors, AgentAccounts.nameProblem(name));
        RuleProblems.add(errors, AccountRules.emailProblem(body.emailAddress(), AccountRules.EmailUse.OPTIONAL));
        String notes = StringUtils.trimToNull(body.notes());
        RuleProblems.add(errors, AgentAccounts.notesProblem(notes));
        errors.throwIfAny();
        // A taken-check that cannot run refuses the create (the opaque 500): it did not pass.
        RuleViolation taken = AccountRules.userNameTaken(userName, weblogger.getUserManager());
        if (taken != null) {
            throw RuleProblems.problem(taken, true);
        }

        boolean siteAdmin = Boolean.TRUE.equals(body.siteAdmin());
        User agent = AgentAccounts.create(weblogger, actor, userName, name,
                StringUtils.trimToNull(body.emailAddress()), notes, siteAdmin);
        return ResponseEntity.status(HttpStatus.CREATED).body(AgentDtos.toView(agent, siteAdmin, null));
    }

    /** One agent as its page shows it, with its live tokens. A role that cannot be read fails the read. */
    @Operation(operationId = "agentsGet", summary = "One agent, with its live tokens (session only).")
    @GetMapping("/{userName}")
    public AgentDtos.AgentView get(@PathVariable("userName") String userName) throws WebloggerException {
        User agent = requireAgent(userName);
        List<TokenDtos.TokenView> tokens = weblogger.getApiTokenManager().getLiveTokens(agent).stream()
                .map(TokenDtos::toView).toList();
        return AgentDtos.toView(agent, AgentAccounts.isSiteAdmin(weblogger.getUserManager(), agent), tokens);
    }

    /**
     * The Details form and the Enable or Disable button: name, notes, the Site administrator switch and
     * whether the agent may act. A field left out is left alone; empty notes clear them.
     */
    @Operation(operationId = "agentsUpdate", summary = "Change an agent (session only).")
    @PatchMapping("/{userName}")
    public AgentDtos.AgentView update(HttpServletRequest request, @PathVariable("userName") String userName,
            @RequestBody AgentDtos.AgentPatch body) throws WebloggerException {
        User actor = requireAuthenticatedUser(request);
        User agent = requireAgent(userName);
        UserManager users = weblogger.getUserManager();
        FieldErrors errors = new FieldErrors();
        String name = agent.getScreenName();
        if (body.name() != null) {
            name = StringUtils.trimToNull(body.name());
            RuleProblems.add(errors, AgentAccounts.nameProblem(name));
        }
        String notes = agent.getNotes();
        if (body.notes() != null) {
            notes = StringUtils.trimToNull(body.notes());
            RuleProblems.add(errors, AgentAccounts.notesProblem(notes));
        }
        errors.throwIfAny();

        boolean siteAdmin = body.siteAdmin() == null ? AgentAccounts.isSiteAdmin(users, agent) : body.siteAdmin();
        AgentAccounts.update(weblogger, actor, agent, name, notes, siteAdmin);
        if (body.enabled() != null) {
            AgentAccounts.changeEnabled(weblogger, actor, agent, body.enabled());
        }
        return AgentDtos.toView(agent, AgentAccounts.isSiteAdmin(users, agent), null);
    }

    /**
     * The token form on an agent's page: label, role, an optional pin to a weblog the agent belongs to (any
     * weblog for a site-admin agent) and an optional expiry. The secret is in this response only.
     */
    @Operation(operationId = "agentsMintToken", summary = "Mint a token for an agent (session only).")
    @ApiResponse(responseCode = "201", description = "Created; the secret is in this response only.")
    @PostMapping("/{userName}/tokens")
    public ResponseEntity<TokenDtos.IssuedToken> mintToken(HttpServletRequest request,
            @PathVariable("userName") String userName, @RequestBody TokenDtos.IssueRequest body)
            throws WebloggerException {
        User actor = requireAuthenticatedUser(request);
        User agent = requireAgent(userName);
        FieldErrors errors = new FieldErrors();
        String label = StringUtils.trimToEmpty(body.label());
        if (label.isEmpty()) {
            errors.add("label", ProblemCode.FIELD_REQUIRED, "label");
        } else if (label.length() > ColumnLimits.TOKEN_LABEL) {
            errors.add("label", ProblemCode.FIELD_TOO_LONG, "label", Integer.toString(ColumnLimits.TOKEN_LABEL));
        }
        ApiToken.Role role = TokenOps.roleIgnoringCase(body.role());
        if (role == null) {
            errors.add("role", ProblemCode.TOKEN_ROLE_UNKNOWN, String.valueOf(body.role()));
        }
        String pin = StringUtils.trimToNull(body.weblog());
        if (pin != null && pin.length() > ColumnLimits.TOKEN_WEBLOG) {
            errors.add("weblog", ProblemCode.FIELD_TOO_LONG, "weblog", Integer.toString(ColumnLimits.TOKEN_WEBLOG));
        } else if (!TokenPins.allows(weblogger, agent, pin)) {
            errors.add("weblog", ProblemCode.TOKEN_WEBLOG_NOT_MEMBER, pin);
        }
        if (body.expiresAt() != null && !body.expiresAt().isAfter(Instant.now())) {
            errors.add("expiresAt", ProblemCode.TOKEN_EXPIRY_NOT_IN_FUTURE);
        }
        errors.throwIfAny();

        Timestamp expiresAt = body.expiresAt() == null ? null : Timestamp.from(body.expiresAt());
        ApiTokenManager.Issued issued = TokenOps.mint(weblogger, agent, actor, label, pin, role, expiresAt);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(new TokenDtos.IssuedToken(issued.rawToken(), TokenDtos.toView(issued.token())));
    }

    /** From an agent's page only that agent's tokens; any other id answers "no such token". */
    @Operation(operationId = "agentsRevokeToken", summary = "Revoke one of an agent's tokens (session only).")
    @ApiResponse(responseCode = "204", description = "Done; no body.")
    @DeleteMapping("/{userName}/tokens/{id}")
    public ResponseEntity<Void> revokeToken(HttpServletRequest request, @PathVariable("userName") String userName,
            @PathVariable("id") String id) throws WebloggerException {
        User actor = requireAuthenticatedUser(request);
        User agent = requireAgent(userName);
        if (TokenOps.revoke(weblogger, actor, id, agent) == TokenOps.RevokeOutcome.NOT_FOUND) {
            throw ApiException.problem(ProblemCode.TOKEN_NOT_FOUND);
        }
        return ResponseEntity.noContent().build();
    }

    /** The agent named; a person's account, an unknown name and a blank one are all "no such agent". */
    private User requireAgent(String userName) throws WebloggerException {
        User agent = AgentAccounts.find(weblogger, userName);
        if (agent == null) {
            throw ApiException.problem(ProblemCode.AGENT_NOT_FOUND);
        }
        return agent;
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
