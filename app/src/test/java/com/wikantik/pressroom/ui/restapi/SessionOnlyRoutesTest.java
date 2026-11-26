package com.wikantik.pressroom.ui.restapi;

import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import com.wikantik.pressroom.pojos.ApiToken;
import com.wikantik.pressroom.ui.restapi.auth.ApiPrincipal;
import com.wikantik.pressroom.ui.restapi.auth.ApiScopeInterceptor;
import com.wikantik.pressroom.ui.restapi.auth.PasswordCallerDetails;
import com.wikantik.pressroom.ui.restapi.auth.SessionOnlyGuard;
import com.wikantik.pressroom.ui.restapi.mcp.ApiRoute;
import com.wikantik.pressroom.ui.restapi.mcp.ApiRouteTables;
import com.wikantik.pressroom.ui.restapi.mcp.McpToolCatalog;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetails;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The sweep that keeps SA-18 from depending on anyone remembering (session-only policy, rule 1 and 3): every
 * route the code marks {@code @SessionOnly} refuses a token of every role, a password, an anonymous caller and
 * an unrecognised mechanism through the one guard, passes a session, is an MCP exclusion with the reason
 * "session-only", and has a {@code SESSION_ONLY} row in the authorization matrix; and the other way round, so
 * a route cannot be declared session-only in the catalog or the matrix without being enforced. A future route
 * that carries the mark is swept without touching this class.
 */
class SessionOnlyRoutesTest {

    private static final List<ApiRoute> MARKED = ApiRouteTables.real().all().stream()
            .filter(route -> SessionOnlyGuard.isMarked(route.handler())).toList();

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    private static Set<String> keys(List<ApiRoute> routes) {
        return routes.stream().map(ApiRoute::key).collect(Collectors.toCollection(TreeSet::new));
    }

    @Test
    void theMarkIsNotVacuous() {
        assertTrue(MARKED.size() >= 31, "the SA-18 routes of waves 3 and 4 are marked: " + keys(MARKED));
    }

    @Test
    void everyMarkedRouteIsAnMcpExclusionWithTheReasonSessionOnlyAndNoOtherIs() {
        Set<String> excluded = McpToolCatalog.EXCLUDED.stream()
                .filter(e -> McpToolCatalog.SESSION_ONLY.equals(e.reason()))
                .map(McpToolCatalog.Exclusion::route).collect(Collectors.toCollection(TreeSet::new));
        assertEquals(keys(MARKED), excluded);
    }

    @Test
    void everyMarkedRouteHasASessionOnlyMatrixRowAndNoOtherDoes() {
        Set<String> rows = ApiAuthorizationMatrixTest.ROWS.stream()
                .filter(r -> r.has(ApiAuthorizationMatrixTest.Flag.SESSION_ONLY))
                .map(ApiAuthorizationMatrixTest.Row::key).collect(Collectors.toCollection(TreeSet::new));
        assertEquals(keys(MARKED), rows);
    }

    private static void signIn(Object details) {
        var auth = UsernamePasswordAuthenticationToken.authenticated("someone", "x", List.of());
        auth.setDetails(details);
        SecurityContextHolder.getContext().setAuthentication(auth);
    }

    private static MockHttpServletRequest request(ApiRoute route) {
        return new MockHttpServletRequest(route.method(), "/v1/probe");
    }

    private static void refused(ApiRoute route, String who) {
        ApiException refusal = assertThrows(ApiException.class, () -> new ApiScopeInterceptor()
                .preHandle(request(route), new MockHttpServletResponse(), route.handler()), route.key() + " | " + who);
        assertEquals(403, refusal.getStatus(), route.key() + " | " + who);
        assertEquals("auth.sessionOnly", refusal.toProblem("/x").code(), route.key() + " | " + who);
    }

    @Test
    void everyMarkedRouteRefusesEveryTokenAndPasswordAndAnonymousAndUnrecognisedCaller() {
        for (ApiRoute route : MARKED) {
            for (ApiToken.Role role : ApiToken.Role.values()) {
                signIn(new ApiPrincipal("agent", null, role, "token-1"));
                refused(route, role + " token");
            }
            signIn(PasswordCallerDetails.SOURCE.buildDetails(new MockHttpServletRequest()));
            refused(route, "password");
            SecurityContextHolder.getContext().setAuthentication(new AnonymousAuthenticationToken("k", "anon",
                    AuthorityUtils.createAuthorityList("ROLE_ANONYMOUS")));
            refused(route, "anonymous");
            SecurityContextHolder.clearContext();
            refused(route, "nobody");
            signIn("some future login mechanism");
            refused(route, "unrecognised");
        }
    }

    @Test
    void everyMarkedRoutePassesASession() throws Exception {
        for (ApiRoute route : MARKED) {
            signIn(new WebAuthenticationDetails(new MockHttpServletRequest()));
            assertTrue(new ApiScopeInterceptor().preHandle(request(route), new MockHttpServletResponse(),
                    route.handler()), route.key());
        }
    }

    @Test
    void anUnmarkedRouteIsNotRefusedToATokenByTheGuard() {
        ApiRoute plain = ApiRouteTables.real().all().stream()
                .filter(route -> !SessionOnlyGuard.isMarked(route.handler())).findFirst().orElseThrow();
        assertFalse(SessionOnlyGuard.isMarked(plain.handler()));
        signIn(new ApiPrincipal("agent", null, ApiToken.Role.READ, "token-1"));
        SessionOnlyGuard.check(com.wikantik.pressroom.ui.restapi.auth.Caller.current(), plain.handler());
    }
}
