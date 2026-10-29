/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 *  contributor license agreements.  The ASF licenses this file to You
 * under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.  For additional information regarding
 * copyright in this work, please see the NOTICE file in the top level
 * directory of this distribution.
 */
package com.wikantik.pressroom.ui.controllers;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;
import com.wikantik.pressroom.WebloggerException;
import com.wikantik.pressroom.business.ApiTokenManager;
import com.wikantik.pressroom.config.WebloggerRuntimeConfig;
import com.wikantik.pressroom.pojos.ApiToken;
import com.wikantik.pressroom.pojos.User;
import com.wikantik.pressroom.pojos.Weblog;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ui.Model;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/**
 * The token form, its one-time reveal and revocation (spec 2026-10-13,
 * section 3), shared by Profile → API tokens, an agent's page and
 * Admin → API tokens so the three cannot drift.
 *
 * <p>The secret travels from the mint to the reveal page only as a one-read
 * flash attribute: never in a URL, and never in a log line -- the mint's
 * {@code pressroom.admin} line names the token by {@link AdminLog#tokenDetails}.
 */
public abstract class TokenFormController extends BaseController {

    private static final Logger log = LoggerFactory.getLogger(TokenFormController.class);

    /** Flash attribute carrying the raw secret to the reveal page, which reads it once. */
    public static final String REVEALED_TOKEN = "revealedToken";

    /** Flash attribute: the new token's label, shown beside the secret. */
    public static final String REVEALED_LABEL = "revealedLabel";

    /** Flash attribute: the agent a token was minted for, so Done returns to its page. */
    public static final String REVEALED_FOR_AGENT = "revealedForAgent";

    /** The reveal page, which {@code ProfileTokensController} serves to any signed-in author. */
    public static final String REVEAL_PATH = "/pressroom-ui/profileTokens!reveal.rol";

    /** pressroom_api_token.label is varchar(255): ColumnLimits.TOKEN_LABEL, which this package may not import. */
    public static final int MAX_LABEL_LENGTH = 255;

    /** The expiry choices besides "never", in days. */
    private static final List<Integer> EXPIRY_DAYS = List.of(30, 90, 365);

    /** An expiry choice, parsed: whether it was one of the four, and the instant (null for never). */
    private record ExpiryChoice(boolean valid, Timestamp expiresAt) { }

    /** Puts {@code owner}'s live tokens and the token form on the model. */
    protected void addTokenSection(Model model, User owner, TokenFormValues values,
                                   String createAction, String revokeAction, String ownerParam) {
        model.addAttribute("tokens", liveTokens(owner));
        model.addAttribute("pinChoices", pinChoices(owner));
        model.addAttribute("tokenFormValues", values);
        model.addAttribute("tokenFormAction", createAction);
        model.addAttribute("tokenRevokeAction", revokeAction);
        model.addAttribute("tokenFormOwner", ownerParam);
    }

    /**
     * Mints a token for {@code owner}, recording {@code actor} as its issuer,
     * and writes the {@code token.minted} line. Each refusal is a field error
     * on its control.
     *
     * @return the mint, or null when the form was refused (errors are on the model)
     */
    protected ApiTokenManager.Issued mintToken(User owner, User actor, TokenFormValues values,
                                               Model model, HttpServletRequest request) {
        String label = StringUtils.trimToEmpty(values.getLabel());
        if (label.isEmpty()) {
            addFieldError(model, "tokenLabel", "apiTokens.error.labelRequired", request);
        } else if (label.length() > MAX_LABEL_LENGTH) {
            addFieldError(model, "tokenLabel", "apiTokens.error.labelTooLong", request);
        }
        ApiToken.Role role = roleOf(values.getRole());
        if (role == null) {
            addFieldError(model, "tokenRole", "apiTokens.error.role", request);
        }
        String pin = StringUtils.trimToNull(values.getPin());
        if (!pinAllowed(owner, pin)) {
            addFieldError(model, "tokenPin", "apiTokens.error.pin", request);
        }
        ExpiryChoice expiry = expiryFor(values.getExpiry(), Instant.now());
        if (!expiry.valid()) {
            addFieldError(model, "tokenExpiry", "apiTokens.error.expiry", request);
        }
        if (hasErrors(model)) {
            return null;
        }
        try {
            ApiTokenManager.Issued issued = weblogger.getApiTokenManager()
                    .issueToken(owner, label, pin, role, expiry.expiresAt(), actor);
            weblogger.flush();
            AdminLog.record("token.minted", actor, AdminLog.tokenDetails(issued.token()));
            return issued;
        } catch (WebloggerException e) {
            log.error("Could not mint a token for {}", owner.getUserName(), e);
            addError(model, "generic.error.check.logs", request);
            return null;
        }
    }

    /** Sends the new secret to the reveal page in flash scope only. */
    protected String revealRedirect(ApiTokenManager.Issued issued, String forAgent,
                                    RedirectAttributes redirectAttributes) {
        redirectAttributes.addFlashAttribute(REVEALED_TOKEN, issued.rawToken());
        redirectAttributes.addFlashAttribute(REVEALED_LABEL, issued.token().getLabel());
        if (forAgent != null) {
            redirectAttributes.addFlashAttribute(REVEALED_FOR_AGENT, forAgent);
        }
        return "redirect:" + REVEAL_PATH;
    }

    /**
     * The reveal page. The secret is on the model only on the visit right
     * after the mint (the flash attribute); a second visit finds nothing and
     * the page says the token was already shown. Never cached either way.
     */
    protected String revealPage(HttpServletResponse response, Model model) {
        // A shared machine's back button must never bring the secret back.
        response.setHeader("Cache-Control", "no-store");
        if (model.getAttribute(REVEALED_TOKEN) instanceof String secret) {
            String mcpUrl = Strings.CS.removeEnd(WebloggerRuntimeConfig.getAbsoluteContextURL(), "/") + "/api/mcp";
            model.addAttribute("mcpUrl", mcpUrl);
            model.addAttribute("mcpSnippet", "claude mcp add --transport http pressroom " + mcpUrl
                    + " --header \"Authorization: Bearer " + secret + "\"");
        }
        return ".TokenReveal";
    }

    /** What {@link #revokeToken} did, so a caller can answer each case differently. */
    protected enum RevokeOutcome {
        /** The token is revoked (now, or already was). */
        REVOKED,
        /** No such id, or a token outside the required owner: "no such token". */
        NOT_FOUND,
        /** The store failed; the token may still be live, so this is never "no such token". */
        FAILED
    }

    /**
     * Revokes a token, writing the {@code token.revoked} line the first time.
     * Revoking an already-revoked token succeeds and changes nothing. Revoke
     * is the safety action, so a store failure is its own outcome: an admin
     * is never told a token that may still be live does not exist.
     *
     * @param requiredOwner the account the token must belong to, or null for
     *                      a site admin, who may revoke any token
     */
    protected RevokeOutcome revokeToken(User actor, String tokenId, User requiredOwner) {
        try {
            ApiTokenManager tokens = weblogger.getApiTokenManager();
            ApiToken token = tokens.getToken(tokenId);
            if (token == null
                    || (requiredOwner != null && !token.getUser().getId().equals(requiredOwner.getId()))) {
                return RevokeOutcome.NOT_FOUND;
            }
            boolean wasLive = token.getRevokedAt() == null;
            tokens.revoke(token);
            weblogger.flush();
            if (wasLive) {
                AdminLog.record("token.revoked", actor, AdminLog.tokenDetails(token));
            }
            return RevokeOutcome.REVOKED;
        } catch (WebloggerException e) {
            log.error("Could not revoke token {}", tokenId, e);
            return RevokeOutcome.FAILED;
        }
    }

    /** A display path: a store that cannot list shows no tokens. */
    private List<ApiToken> liveTokens(User owner) {
        try {
            return weblogger.getApiTokenManager().getLiveTokens(owner);
        } catch (WebloggerException e) {
            log.error("Could not list the tokens of {}", owner.getUserName(), e);
            return List.of();
        }
    }

    /** A display path: a store that cannot answer offers only "All its weblogs". */
    private List<Weblog> pinChoices(User owner) {
        try {
            return TokenPins.choices(weblogger, owner);
        } catch (WebloggerException e) {
            log.error("Could not list the weblogs {} may pin", owner.getUserName(), e);
            return List.of();
        }
    }

    /** A decision path: a pin check that could not run refuses the pin. */
    private boolean pinAllowed(User owner, String pin) {
        try {
            return TokenPins.allows(weblogger, owner, pin);
        } catch (WebloggerException e) {
            log.error("Could not check the pin {} for {}", pin, owner.getUserName(), e);
            return false;
        }
    }

    private static ApiToken.Role roleOf(String role) {
        for (ApiToken.Role candidate : ApiToken.Role.values()) {
            if (candidate.name().equals(role)) {
                return candidate;
            }
        }
        return null;
    }

    private static ExpiryChoice expiryFor(String choice, Instant now) {
        String value = StringUtils.defaultIfBlank(choice, TokenFormValues.DEFAULT_EXPIRY);
        if (TokenFormValues.NEVER.equals(value)) {
            return new ExpiryChoice(true, null);
        }
        for (int days : EXPIRY_DAYS) {
            if (Integer.toString(days).equals(value)) {
                return new ExpiryChoice(true, Timestamp.from(now.plus(Duration.ofDays(days))));
            }
        }
        return new ExpiryChoice(false, null);
    }
}
