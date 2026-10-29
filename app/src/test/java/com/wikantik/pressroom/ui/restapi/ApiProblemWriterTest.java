package com.wikantik.pressroom.ui.restapi;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletResponse;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import static org.junit.jupiter.api.Assertions.*;

/**
 * The one place outside {@code ApiExceptionHandler} that builds an API error
 * body by hand -- see the class javadoc for why. Every caller (the throttle,
 * the auth entry point, the access-denied handler) goes through this so their
 * bodies cannot drift from {@code ApiExceptionHandler}'s or from each other.
 */
class ApiProblemWriterTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Test
    void writesStatusContentTypeAndABodyMatchingTheProblem() throws Exception {
        ApiProblemWriter writer = new ApiProblemWriter(OBJECT_MAPPER);
        ApiProblem problem = ApiException.forbidden("Access denied.")
                .toProblem("/api/v1/weblogs/x");
        MockHttpServletResponse response = new MockHttpServletResponse();

        writer.write(response, problem);

        assertEquals(403, response.getStatus());
        assertEquals("application/problem+json", response.getContentType());
        JsonNode body = OBJECT_MAPPER.readTree(response.getContentAsByteArray());
        assertEquals(403, body.get("status").asInt());
        assertEquals("Access denied.", body.get("detail").asString());
        assertEquals("/api/v1/weblogs/x", body.get("instance").asString());
    }
}
