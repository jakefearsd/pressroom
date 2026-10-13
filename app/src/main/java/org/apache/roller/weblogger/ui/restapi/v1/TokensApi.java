package org.apache.roller.weblogger.ui.restapi.v1;

import java.sql.Timestamp;
import java.util.List;
import java.util.Locale;
import org.apache.roller.weblogger.WebloggerException;
import org.apache.roller.weblogger.business.ApiTokenManager;
import org.apache.roller.weblogger.business.Weblogger;
import org.apache.roller.weblogger.pojos.ApiToken;
import org.apache.roller.weblogger.pojos.GlobalPermission;
import org.apache.roller.weblogger.pojos.User;
import org.apache.roller.weblogger.ui.restapi.ApiException;
import org.apache.roller.weblogger.ui.restapi.ColumnLimits;
import org.apache.roller.weblogger.ui.restapi.auth.ApiPrincipal;
import org.apache.roller.weblogger.ui.restapi.dto.TokenDtos;
import org.springframework.context.annotation.Lazy;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Issues and manages the automation API's own long-lived credentials.
 *
 * <p>Minting is Basic-auth-only, enforced here rather than in Spring Security
 * config -- see {@code SecurityConfig.apiSecurityFilterChain}'s javadoc for
 * why the chain cannot cleanly forbid a Bearer caller from reaching this
 * endpoint, and {@code TokensApiTest#aBearerAuthenticatedCallerCannotMintAToken}
 * for the test that would catch a regression.
 *
 * <p>{@code weblogger} is injected {@code @Lazy}, the same discipline every
 * other business-bean dependency in this codebase follows (see
 * {@code WebloggerBeanConfig} and {@code BaseApiController}): the manager
 * chain behind {@link Weblogger} is only safe to construct after {@code
 * WebloggerStartup.prepare()} has run, which happens on first request, not
 * during Spring context refresh. An eager constructor parameter here once
 * forced {@code DatabaseProvider} to instantiate during bean-graph wiring --
 * before {@code prepare()} had ever run -- which failed the whole
 * application context and meant the WAR never started at all;
 * {@code ApiIT} (a real-servlet-container test, the only layer that boots
 * the packaged WAR at all) is what caught it, since a MockMvc unit test
 * builds this controller with {@code new TokensApi(...)} and never goes
 * through Spring's bean graph in the first place.
 *
 * <p>{@link #issue} and {@link #revoke} each call {@code weblogger.flush()}
 * after their write, the same as every other write endpoint in {@code
 * ui.restapi.v1} ({@code CategoriesApi}, {@code PagesApi}, {@code
 * EntriesWriteApi}, {@code MediaApi}, {@code AdminApi}, {@code WeblogsApi}).
 * A JPA write here only begins a transaction ({@code
 * JPAPersistenceStrategy}'s {@code beginTransactionIfNeeded}); nothing
 * commits it automatically, and {@code PersistenceSessionFilter}'s
 * end-of-request {@code release()} rolls back whatever is still open rather
 * than committing it. Both methods here were missing the call: a token
 * mint returned 201 with a real-looking secret that had already been rolled
 * back by the time the response reached the caller, and a revoke answered
 * 204 while leaving the token fully usable. {@code ApiIT
 * .aTokenMintedThroughTheApiWorksOnASubsequentCall} is the end-to-end test
 * that caught the mint half -- a token minted through Basic auth immediately
 * failed to authenticate as a Bearer token on the very next request, which a
 * MockMvc test cannot see because it never runs a real transaction boundary
 * at all.
 */
@RestController
@RequestMapping("/v1/tokens")
public class TokensApi {

    private final Weblogger weblogger;

    public TokensApi(@Lazy Weblogger weblogger) {
        this.weblogger = weblogger;
    }

    @PostMapping("")
    public ResponseEntity<TokenDtos.IssuedToken> issue(@RequestBody TokenDtos.IssueRequest request)
            throws WebloggerException {
        // A token cannot be used to mint another token: that would turn any
        // leaked token into a permanent one, with a scope the thief picks.
        if (currentApiPrincipal() != null) {
            throw ApiException.forbidden("A token cannot be used to mint another token.");
        }

        ApiToken.Role role = parseRole(request.role());
        // roller_api_token.label is VARCHAR(255) NOT NULL (V026) and
        // issueToken copies it straight through -- parseRole above already
        // degrades a missing role to a 400; label never got the equivalent,
        // so a mint with no label reached the manager and died on the
        // constraint, an opaque 500 on the bootstrap endpoint (how every
        // token, including the very first one, comes into existence).
        requireLabel(request.label());
        ColumnLimits.requireMaxLength("label", request.label(), ColumnLimits.TOKEN_LABEL);
        ColumnLimits.requireMaxLength("weblog", request.weblog(), ColumnLimits.TOKEN_WEBLOG);
        User user = requireUser();
        Timestamp expiresAt = request.expiresAt() == null ? null : Timestamp.from(request.expiresAt());

        ApiTokenManager.Issued issued = weblogger.getApiTokenManager()
                .issueToken(user, request.label(), request.weblog(), role, expiresAt);
        weblogger.flush();

        TokenDtos.TokenView view = TokenDtos.toView(issued.token());
        return ResponseEntity.status(HttpStatus.CREATED).body(new TokenDtos.IssuedToken(issued.rawToken(), view));
    }

    @GetMapping("")
    public List<TokenDtos.TokenView> list() throws WebloggerException {
        // The whole /v1/tokens resource is Basic-only, the same as issue():
        // a leaked, merely-POST-scoped token must not be able to enumerate
        // every token its owner holds.
        if (currentApiPrincipal() != null) {
            throw ApiException.forbidden("Token management requires Basic authentication.");
        }
        User user = requireUser();
        return weblogger.getApiTokenManager().getTokens(user).stream()
                .map(TokenDtos::toView)
                .toList();
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> revoke(@PathVariable("id") String id) throws WebloggerException {
        // Same as list(): a leaked token must not be able to revoke any of
        // its owner's other tokens, including an ADMIN one.
        if (currentApiPrincipal() != null) {
            throw ApiException.forbidden("Token management requires Basic authentication.");
        }
        User user = requireUser();
        boolean revoked = weblogger.getApiTokenManager().revoke(user, id);
        if (!revoked) {
            // Not 403: that would confirm the id exists and let one user
            // enumerate another's tokens.
            throw ApiException.notFound("No such token.");
        }
        weblogger.flush();
        return ResponseEntity.noContent().build();
    }

    private static void requireLabel(String label) {
        if (label == null || label.isBlank()) {
            throw ApiException.invalidField("label", "label is required.");
        }
    }

    private static ApiToken.Role parseRole(String role) {
        try {
            return ApiToken.Role.valueOf(role.toUpperCase(Locale.ROOT));
        } catch (RuntimeException e) {
            throw ApiException.invalidField("role", "Unknown role: " + role, e);
        }
    }

    private User requireUser() throws WebloggerException {
        User user = currentUser();
        if (user == null) {
            throw ApiException.forbidden("Not authenticated.");
        }
        // A reader (SSO sign-in) holds no global login action: it may not use
        // the API, and so may not mint, list or revoke tokens.
        if (!weblogger.getUserManager().checkPermission(
                new GlobalPermission(List.of(GlobalPermission.LOGIN)), user)) {
            throw ApiException.forbidden("This account cannot use the API.");
        }
        return user;
    }

    private User currentUser() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null) {
            return null;
        }
        Object principal = auth.getPrincipal();
        String userName = null;
        if (principal instanceof UserDetails userDetails) {
            userName = userDetails.getUsername();
        } else if (principal instanceof String s && !"anonymousUser".equals(s)) {
            userName = s;
        }
        if (userName == null) {
            return null;
        }
        try {
            return weblogger.getUserManager().getUserByUserName(userName);
        } catch (WebloggerException e) {
            return null;
        }
    }

    private static ApiPrincipal currentApiPrincipal() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null) {
            return null;
        }
        return auth.getDetails() instanceof ApiPrincipal p ? p : null;
    }
}
