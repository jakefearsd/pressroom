package com.wikantik.pressroom.ui.restapi.v1;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import java.util.ArrayList;
import java.util.List;
import com.wikantik.pressroom.WebloggerException;
import com.wikantik.pressroom.pojos.ApiToken;
import com.wikantik.pressroom.pojos.GlobalPermission;
import com.wikantik.pressroom.pojos.User;
import com.wikantik.pressroom.ui.controllers.TokenOps;
import com.wikantik.pressroom.ui.controllers.admin.TokenRow;
import com.wikantik.pressroom.ui.controllers.UISecurityEnforced;
import com.wikantik.pressroom.ui.restapi.ApiException;
import com.wikantik.pressroom.ui.restapi.ProblemCode;
import com.wikantik.pressroom.ui.restapi.auth.AdminScoped;
import com.wikantik.pressroom.ui.restapi.auth.SessionOnly;
import com.wikantik.pressroom.ui.restapi.dto.TokenDtos;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Admin, API tokens, for the admin app (SA-18, session only): every user's tokens, and revoking any of them
 * ({@code AdminTokensController}). It shows who holds access and lets one token revoke everyone else's, so a
 * token never reaches it; a person's own tokens stay on {@code /v1/tokens} under a password.
 */
@Tag(name = "Site tokens", description = "Every token on the site (session only: the admin app, never a token).")
@RestController
@RequestMapping("/v1/admin/tokens")
@AdminScoped
@SessionOnly
public class SiteTokensApi extends BaseApiController implements UISecurityEnforced {

    /**
     * Every live token on the site, people's and agents', newest first, with owner, kind and issuer; {@code
     * all} adds revoked and expired ones. A store that cannot list is an error, never a shorter list.
     */
    @Operation(operationId = "siteTokensList", summary = "Every user's tokens (session only).")
    @GetMapping("")
    public TokenDtos.SiteTokenPage list(
            @Parameter(description = "True adds revoked and expired tokens.")
            @RequestParam(value = "all", defaultValue = "false") boolean all) throws WebloggerException {
        List<TokenDtos.SiteTokenView> items = new ArrayList<>();
        for (TokenRow row : TokenRow.loadOrThrow(weblogger, all)) {
            ApiToken token = row.getToken();
            TokenDtos.TokenView view = TokenDtos.toView(token);
            items.add(new TokenDtos.SiteTokenView(view.id(), view.label(), view.scopeWeblog(), view.scopeRole(),
                    view.created(), view.lastUsedAt(), view.expiresAt(), view.revokedAt(),
                    token.getUser().getUserName(), token.getUser().getKind().name(), row.getIssuedBy(),
                    row.isLive()));
        }
        return new TokenDtos.SiteTokenPage(items);
    }

    /** A site admin revokes any token; an unknown id answers "no such token". Revoking a revoked one succeeds. */
    @Operation(operationId = "siteTokensRevoke", summary = "Revoke any token (session only).")
    @ApiResponse(responseCode = "204", description = "Done; no body.")
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> revoke(HttpServletRequest request, @PathVariable("id") String id)
            throws WebloggerException {
        User actor = requireAuthenticatedUser(request);
        if (TokenOps.revoke(weblogger, actor, id, null) == TokenOps.RevokeOutcome.NOT_FOUND) {
            throw ApiException.problem(ProblemCode.TOKEN_NOT_FOUND);
        }
        return ResponseEntity.noContent().build();
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
