package com.wikantik.pressroom.ui.restapi;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.Logger;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Property;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * A body in a media type the API does not read, or an {@code Accept} it
 * cannot answer, is the client's doing: 415 and 406 as problem+json, logged
 * below ERROR. Both are raised once the request has reached a handler method
 * (reading the {@code @RequestBody}, writing the return value), so they fell
 * to {@code handleUnexpected}: a 500 and an ERROR line with a stack trace for
 * a form-encoded POST or a browser's {@code Accept: text/html}.
 */
class ApiMediaTypeRefusalTest {

    /** A handler in the restapi package, so ApiExceptionHandler advises it. */
    @RestController
    static class ProbeApi {
        @PostMapping("/v1/probe")
        Map<String, String> write(@RequestBody Map<String, String> body) {
            return body;
        }

        @GetMapping("/v1/probe")
        Map<String, String> read() {
            return Map.of("ok", "yes");
        }
    }

    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(new ProbeApi())
            .setControllerAdvice(new ApiExceptionHandler()).build();
    private final List<LogEvent> errors = new CopyOnWriteArrayList<>();
    private final Logger logger = (Logger) LogManager.getLogger(ApiExceptionHandler.class);
    private AbstractAppender appender;

    @BeforeEach
    void captureErrors() {
        appender = new AbstractAppender("media-type-errors", null, null, true, Property.EMPTY_ARRAY) {
            @Override
            public void append(LogEvent event) {
                if (event.getLevel().isMoreSpecificThan(Level.ERROR)) {
                    errors.add(event.toImmutable());
                }
            }
        };
        appender.start();
        logger.addAppender(appender);
    }

    @AfterEach
    void stopCapturing() {
        logger.removeAppender(appender);
        appender.stop();
    }

    @Test
    void aFormEncodedBodyIsA415ProblemNotA500() throws Exception {
        MvcResult result = mvc.perform(post("/v1/probe")
                .contentType(MediaType.APPLICATION_FORM_URLENCODED).content("a=b")).andReturn();

        JsonNode problem = problem(result, 415);
        assertTrue(problem.get("type").asString().endsWith("/unsupported-media-type"), problem.toString());
        assertEquals("Send the body as application/json.", problem.get("detail").asString());
        assertEquals(List.of(), errors, "a client mistake is not logged as an ERROR");
    }

    @Test
    void anAcceptTheApiCannotAnswerIsA406ProblemNotA500() throws Exception {
        MvcResult result = mvc.perform(get("/v1/probe").accept(MediaType.TEXT_HTML)).andReturn();

        JsonNode problem = problem(result, 406);
        assertTrue(problem.get("type").asString().endsWith("/not-acceptable"), problem.toString());
        assertEquals("The API answers application/json; accept it.", problem.get("detail").asString());
        assertEquals(List.of(), errors, "a client mistake is not logged as an ERROR");
    }

    private static JsonNode problem(MvcResult result, int status) throws Exception {
        assertEquals(status, result.getResponse().getStatus(), result.getResponse().getContentAsString());
        assertEquals(MediaType.APPLICATION_PROBLEM_JSON_VALUE, result.getResponse().getContentType());
        return new ObjectMapper().readTree(result.getResponse().getContentAsString());
    }
}
