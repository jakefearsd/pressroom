package com.wikantik.pressroom.ui.restapi;

import java.net.URL;
import java.net.URLClassLoader;
import java.util.Locale;
import java.util.MissingResourceException;
import org.junit.jupiter.api.Test;
import org.springframework.context.MessageSource;
import org.springframework.context.support.AbstractMessageSource;
import org.springframework.mock.web.MockHttpServletRequest;
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

    /**
     * The 0.16.0 CI failure. {@code ApiScopeInterceptor} holds a static writer
     * built from {@link ApiProblemWriter#ApiProblemWriter(ObjectMapper)}; its
     * message source resolved {@code ApplicationResources} through the context
     * class loader of whichever thread first built it. When that was an
     * embedded Tomcat's web-app loader (a test booting the real application
     * initialised the interceptor on a container thread), the loader was
     * stopped when that Tomcat closed and every later refusal through the
     * writer threw {@code MissingResourceException}: a 404 became a 500. The
     * loader here sees nothing at all, which fails the same way. The source
     * itself must resolve the key, not merely the renderer fall back.
     */
    @Test
    void aWriterBuiltUnderAContextLoaderThatCannotSeeTheBundleStillWritesTheEnglish() throws Exception {
        ApiProblemWriter writer;
        MessageSource source;
        Thread thread = Thread.currentThread();
        ClassLoader original = thread.getContextClassLoader();
        try (URLClassLoader blind = new URLClassLoader(new URL[0], null)) {
            thread.setContextClassLoader(blind);
            writer = new ApiProblemWriter(OBJECT_MAPPER);
            source = ProblemMessages.applicationResources();
        } finally {
            thread.setContextClassLoader(original);
        }

        assertEquals("No such weblog.", source.getMessage("api.problem.weblog.notFound", null, Locale.ROOT));
        JsonNode body = writeWeblogNotFound(writer);

        assertEquals(404, body.get("status").asInt());
        assertEquals("No such weblog.", body.get("detail").asString());
    }

    /**
     * Whatever else goes wrong, a bundle the message source cannot load is
     * display trouble, not a reason to answer 500 instead of the decided
     * status: the detail falls back to the English the base bundle carries.
     */
    @Test
    void aMessageSourceThatCannotLoadItsBundleStillYieldsTheDecidedStatusInEnglish() throws Exception {
        MessageSource unloadable = new AbstractMessageSource() {
            @Override
            protected java.text.MessageFormat resolveCode(String code, Locale locale) {
                throw new MissingResourceException("Can't find bundle", "ApplicationResources", "");
            }
        };

        JsonNode body = writeWeblogNotFound(new ApiProblemWriter(OBJECT_MAPPER, unloadable));

        assertEquals(404, body.get("status").asInt());
        assertEquals("No such weblog.", body.get("detail").asString());
    }

    private static JsonNode writeWeblogNotFound(ApiProblemWriter writer) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/weblogs/x");
        MockHttpServletResponse response = new MockHttpServletResponse();
        writer.write(request, response, ApiException.weblogNotFound());
        assertEquals(404, response.getStatus());
        return OBJECT_MAPPER.readTree(response.getContentAsByteArray());
    }
}
