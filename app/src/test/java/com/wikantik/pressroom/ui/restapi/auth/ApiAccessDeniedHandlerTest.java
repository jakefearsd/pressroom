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
}
