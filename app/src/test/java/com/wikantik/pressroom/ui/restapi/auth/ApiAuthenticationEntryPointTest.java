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
                new BadCredentialsException("token rlr_xyz is expired"));

        assertArrayEquals(responseA.getContentAsByteArray(), responseB.getContentAsByteArray(),
                "a missing header and a bad token must produce byte-identical bodies");
    }
}
