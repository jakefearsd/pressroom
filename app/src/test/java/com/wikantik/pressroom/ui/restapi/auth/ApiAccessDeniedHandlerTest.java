package com.wikantik.pressroom.ui.restapi.auth;

import com.wikantik.pressroom.ui.restapi.ApiProblemWriter;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.access.AccessDeniedException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Same reasoning as ApiAuthenticationEntryPointTest: a 403 on /api/** must be
 * problem+json too, and Spring Security's default handling for a denied
 * request runs entirely outside DispatcherServlet's reach.
 */
class ApiAccessDeniedHandlerTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final ApiAccessDeniedHandler handler =
            new ApiAccessDeniedHandler(new ApiProblemWriter(OBJECT_MAPPER));

    @Test
    void handlingWritesA403ProblemJsonResponse() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/weblogs/x/entries");
        MockHttpServletResponse response = new MockHttpServletResponse();

        handler.handle(request, response, new AccessDeniedException("scope does not allow POST"));

        assertEquals(403, response.getStatus());
        assertEquals("application/problem+json", response.getContentType());
        JsonNode body = OBJECT_MAPPER.readTree(response.getContentAsByteArray());
        assertEquals(403, body.get("status").asInt());
        assertEquals("/api/v1/weblogs/x/entries", body.get("instance").asString());
    }

    /** A refusal by the CSRF filter (a session write with no or a wrong token) is csrf.invalid, still a 403. */
    @Test
    void aCsrfRefusalCarriesTheCsrfInvalidCode() throws Exception {
        for (AccessDeniedException refusal : new AccessDeniedException[] {
                new org.springframework.security.web.csrf.MissingCsrfTokenException("none"),
                new org.springframework.security.web.csrf.InvalidCsrfTokenException(
                        new org.springframework.security.web.csrf.DefaultCsrfToken("X-CSRF-TOKEN", "_csrf", "t"),
                        "wrong")}) {
            MockHttpServletResponse response = new MockHttpServletResponse();

            handler.handle(new MockHttpServletRequest("PATCH", "/api/v1/me"), response, refusal);

            assertEquals(403, response.getStatus());
            JsonNode body = OBJECT_MAPPER.readTree(response.getContentAsByteArray());
            assertEquals("csrf.invalid", body.get("code").asString());
            assertEquals("forbidden", body.get("type").asString().replaceAll(".*/", ""));
        }
    }
}
