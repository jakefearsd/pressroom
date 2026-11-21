package com.wikantik.pressroom.ui.restapi.auth;

import com.wikantik.pressroom.ui.restapi.ApiProblemWriter;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.InsufficientAuthenticationException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Every unauthenticated request on /api/** must get problem+json, not Spring
 * Security's default (a Basic challenge, or Boot's own /error page) -- both
 * of which run entirely outside DispatcherServlet's reach, so
 * ApiExceptionHandler's @RestControllerAdvice can never see them.
 */
class ApiAuthenticationEntryPointTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final ApiAuthenticationEntryPoint entryPoint =
            new ApiAuthenticationEntryPoint(new ApiProblemWriter(OBJECT_MAPPER));

    @Test
    void commencingWritesA401ProblemJsonResponse() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/weblogs/x");
        MockHttpServletResponse response = new MockHttpServletResponse();

        entryPoint.commence(request, response,
                new InsufficientAuthenticationException("missing bearer token"));

        assertEquals(401, response.getStatus());
        assertEquals("application/problem+json", response.getContentType());
        JsonNode body = OBJECT_MAPPER.readTree(response.getContentAsByteArray());
        assertEquals(401, body.get("status").asInt());
        assertEquals("/api/v1/weblogs/x", body.get("instance").asString());
    }

    /**
     * The response must never distinguish "no token" from "expired token"
     * from "revoked token" -- that would tell an attacker which of several
     * stolen tokens used to be real. The filter never surfaces which of those
     * happened (see ApiTokenAuthFilter), but this entry point is the last
     * line of defence even if that ever changed: the detail text is fixed,
     * not derived from the AuthenticationException.
     */
    @Test
    void theResponseBodyNeverVariesWithTheAuthenticationFailureReason() throws Exception {
        MockHttpServletRequest requestA = new MockHttpServletRequest("GET", "/api/v1/ping");
        MockHttpServletResponse responseA = new MockHttpServletResponse();
        entryPoint.commence(requestA, responseA,
                new InsufficientAuthenticationException("no Authorization header at all"));

        MockHttpServletRequest requestB = new MockHttpServletRequest("GET", "/api/v1/ping");
        MockHttpServletResponse responseB = new MockHttpServletResponse();
        entryPoint.commence(requestB, responseB,
                new BadCredentialsException("token prs_xyz is expired"));

        assertArrayEquals(responseA.getContentAsByteArray(), responseB.getContentAsByteArray(),
                "a missing header and a bad token must produce byte-identical bodies");
    }

    /**
     * Off the token resource (spec 2026-11-20, Task 2) a request that sent a
     * password is told where a password is accepted. Only the scheme is read,
     * never the credential, so a right and a wrong password read alike.
     */
    @Test
    void offTheTokenResourceAPasswordIsToldWhereItIsAccepted() throws Exception {
        ApiAuthenticationEntryPoint refusing =
                ApiAuthenticationEntryPoint.refusingPasswords(new ApiProblemWriter(OBJECT_MAPPER));
        for (String authorization : new String[] {"Basic YWxpY2U6cGFzc3dvcmQ=", "basic eA==", "  BASIC eA=="}) {
            MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/me");
            request.addHeader("Authorization", authorization);
            MockHttpServletResponse response = new MockHttpServletResponse();

            refusing.commence(request, response, new InsufficientAuthenticationException("anonymous"));

            assertEquals(401, response.getStatus());
            assertEquals("application/problem+json", response.getContentType());
            assertEquals("Password authentication is accepted only on /api/v1/tokens. "
                            + "Mint a token there and send it as a bearer token.",
                    OBJECT_MAPPER.readTree(response.getContentAsByteArray()).get("detail").asString(),
                    authorization);
        }
    }

    /** A request with no password, or a token, keeps the one fixed detail. */
    @Test
    void withoutAPasswordTheRefusingEntryPointKeepsTheUsualDetail() throws Exception {
        ApiAuthenticationEntryPoint refusing =
                ApiAuthenticationEntryPoint.refusingPasswords(new ApiProblemWriter(OBJECT_MAPPER));
        for (String authorization : new String[] {null, "Bearer prs_x", "Digest x"}) {
            MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/me");
            if (authorization != null) {
                request.addHeader("Authorization", authorization);
            }
            MockHttpServletResponse response = new MockHttpServletResponse();

            refusing.commence(request, response, new InsufficientAuthenticationException("anonymous"));

            assertEquals("Authentication required.",
                    OBJECT_MAPPER.readTree(response.getContentAsByteArray()).get("detail").asString(),
                    String.valueOf(authorization));
        }
    }

    /** The token resource's own entry point never says a password is the wrong credential. */
    @Test
    void thePlainEntryPointNeverRefersAPasswordElsewhere() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/tokens");
        request.addHeader("Authorization", "Basic YWxpY2U6d3Jvbmc=");
        MockHttpServletResponse response = new MockHttpServletResponse();

        entryPoint.commence(request, response, new BadCredentialsException("bad"));

        assertEquals("Authentication required.",
                OBJECT_MAPPER.readTree(response.getContentAsByteArray()).get("detail").asString());
    }
}
