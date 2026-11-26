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

    /** What the guard answers this caller on this route: "refused" or why it was not. */
    private static String answer(ApiRoute route) {
        try {
            new ApiScopeInterceptor().preHandle(request(route), new MockHttpServletResponse(), route.handler());
            return "let through";
        } catch (ApiException refusal) {
            return refusal.getStatus() == 403 && "auth.sessionOnly".equals(refusal.toProblem("/x").code())
                    ? "refused" : "refused with " + refusal.getStatus();
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Collects every route the caller got through (or refused wrongly), so one miss does not hide the rest. */
    private static void refusedEverywhere(List<String> offenders, String who) {
        for (ApiRoute route : MARKED) {
            String answer = answer(route);
            if (!"refused".equals(answer)) {
                offenders.add(who + " | " + route.key() + " | " + answer);
            }
        }
    }

    @Test
    void everyMarkedRouteRefusesEveryTokenAndPasswordAndAnonymousAndUnrecognisedCaller() {
        List<String> offenders = new java.util.ArrayList<>();
        for (ApiToken.Role role : ApiToken.Role.values()) {
            signIn(new ApiPrincipal("agent", null, role, "token-1"));
            refusedEverywhere(offenders, role + " token");
        }
        signIn(PasswordCallerDetails.SOURCE.buildDetails(new MockHttpServletRequest()));
        refusedEverywhere(offenders, "password");
        SecurityContextHolder.getContext().setAuthentication(new AnonymousAuthenticationToken("k", "anon",
                AuthorityUtils.createAuthorityList("ROLE_ANONYMOUS")));
        refusedEverywhere(offenders, "anonymous");
        SecurityContextHolder.clearContext();
        refusedEverywhere(offenders, "nobody");
        signIn("some future login mechanism");
        refusedEverywhere(offenders, "unrecognised");
        assertEquals(List.of(), offenders);
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
