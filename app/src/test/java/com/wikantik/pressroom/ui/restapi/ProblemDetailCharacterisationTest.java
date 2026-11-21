package com.wikantik.pressroom.ui.restapi;

import java.time.Instant;
import java.util.Map;
import com.wikantik.pressroom.business.AnchorLockTimeoutException;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * The exact English {@code detail} (and, for a field problem, the field and
 * its {@code message}) of every problem whose text is fixed, each raised
 * through its real producer (spec 2026-11-20-api-contract-design, Task 1,
 * criterion 4).
 *
 * <p>Characterisation tests: written first, against the code as it stood
 * before problem codes existed, and passing immediately. They must pass
 * unchanged once these problems are keyed and rendered through the message
 * bundle, both without an {@code Accept-Language} header and with one naming
 * English ({@code en-GB}): an English caller sees byte-for-byte what it saw
 * before.
 */
class ProblemDetailCharacterisationTest {

    /** A handler in the restapi package, so ApiExceptionHandler advises it. */
    @RestController
    static class ProbeApi {
        @GetMapping("/v1/probe/boom")
        Map<String, String> boom() {
            throw new IllegalStateException("connection pool exhausted at com.example.Secret");
        }

        @GetMapping("/v1/probe/ok")
        Map<String, String> ok() {
            return Map.of("ok", "yes");
        }

        @PostMapping("/v1/probe/body")
        Map<String, String> body(@RequestBody Map<String, String> body) {
            return body;
        }

        @GetMapping("/v1/probe/busy")
        Map<String, String> busy() throws AnchorLockTimeoutException {
            throw new AnchorLockTimeoutException("myblog", 10);
        }

        @GetMapping("/v1/probe/entry-changed")
        Map<String, String> entryChanged() {
            throw ApiException.entryChanged("abc");
        }

        @GetMapping("/v1/probe/page-changed")
        Map<String, String> pageChanged() {
            throw ApiException.pageChanged("abc");
        }

        @GetMapping("/v1/probe/whole")
        Map<String, String> whole(@RequestParam("limit") int limit) {
            return Map.of("limit", Integer.toString(limit));
        }

        @GetMapping("/v1/probe/number")
        Map<String, String> number(@RequestParam("focalX") double focalX) {
            return Map.of("focalX", Double.toString(focalX));
        }

        @GetMapping("/v1/probe/flag")
        Map<String, String> flag(@RequestParam("enabled") boolean enabled) {
            return Map.of("enabled", Boolean.toString(enabled));
        }

        @GetMapping("/v1/probe/instant")
        Map<String, String> instant(@RequestParam("since") Instant since) {
            return Map.of("since", since.toString());
        }

        @GetMapping("/v1/probe/page")
        Map<String, String> page(@RequestParam("limit") int limit, @RequestParam("offset") int offset) {
            Paging.requireRange(limit, offset);
            return Map.of();
        }
    }

    private static final ObjectMapper JSON = new ObjectMapper();

    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(new ProbeApi())
            .setControllerAdvice(new ApiExceptionHandler()).build();

    private JsonNode problem(MockHttpServletRequestBuilder request, String acceptLanguage, int status)
            throws Exception {
        if (acceptLanguage != null) {
            request.header("Accept-Language", acceptLanguage);
        }
        var response = mvc.perform(request).andReturn().getResponse();
        assertEquals(status, response.getStatus(), response.getContentAsString());
        return JSON.readTree(response.getContentAsString());
    }

    private static void assertFieldProblem(JsonNode body, String field, String message) {
        assertEquals(message, body.get("detail").asString());
        assertEquals(1, body.get("errors").size(), body.toString());
        assertEquals(field, body.get("errors").get(0).get("field").asString());
        assertEquals(message, body.get("errors").get(0).get("message").asString());
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = "en-GB")
    void theOpaque500(String acceptLanguage) throws Exception {
        assertEquals("The request could not be completed.",
                problem(get("/v1/probe/boom"), acceptLanguage, 500).get("detail").asString());
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = "en-GB")
    void theNotAcceptable406(String acceptLanguage) throws Exception {
        assertEquals("The API answers application/json; accept it.",
                problem(get("/v1/probe/ok").accept(MediaType.TEXT_HTML), acceptLanguage, 406)
                        .get("detail").asString());
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = "en-GB")
    void theUnsupportedMediaType415(String acceptLanguage) throws Exception {
        assertEquals("Send the body as application/json.",
                problem(post("/v1/probe/body").contentType(MediaType.TEXT_PLAIN).content("x"),
                        acceptLanguage, 415).get("detail").asString());
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = "en-GB")
    void theBusy503(String acceptLanguage) throws Exception {
        assertEquals("Another save of this weblog is in progress; retry shortly.",
                problem(get("/v1/probe/busy"), acceptLanguage, 503).get("detail").asString());
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = "en-GB")
    void entryChanged(String acceptLanguage) throws Exception {
        JsonNode body = problem(get("/v1/probe/entry-changed"), acceptLanguage, 409);
        assertEquals("The entry's text changed after it was read; get the entry again and redo the change.",
                body.get("detail").asString());
        assertEquals("abc", body.get("currentContentHash").asString());
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = "en-GB")
    void pageChanged(String acceptLanguage) throws Exception {
        JsonNode body = problem(get("/v1/probe/page-changed"), acceptLanguage, 409);
        assertEquals("The page's text changed after it was read; get the page again and redo the change.",
                body.get("detail").asString());
        assertEquals("abc", body.get("currentContentHash").asString());
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = "en-GB")
    void theMalformedBody400(String acceptLanguage) throws Exception {
        assertEquals("The request body could not be parsed as JSON.",
                problem(post("/v1/probe/body").contentType(MediaType.APPLICATION_JSON).content("{\"a\":"),
                        acceptLanguage, 400).get("detail").asString());
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = "en-GB")
    void theFourTypeMismatchSentences(String acceptLanguage) throws Exception {
        assertFieldProblem(problem(get("/v1/probe/whole").param("limit", "abc"), acceptLanguage, 400),
                "limit", "limit has an invalid value; it must be a whole number.");
        assertFieldProblem(problem(get("/v1/probe/number").param("focalX", "abc"), acceptLanguage, 400),
                "focalX", "focalX has an invalid value; it must be a number.");
        assertFieldProblem(problem(get("/v1/probe/flag").param("enabled", "abc"), acceptLanguage, 400),
                "enabled", "enabled has an invalid value; it must be true or false.");
        assertFieldProblem(problem(get("/v1/probe/instant").param("since", "abc"), acceptLanguage, 400),
                "since", "since has an invalid value.");
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = "en-GB")
    void thePagingRangeSentence(String acceptLanguage) throws Exception {
        String sentence = "limit must be at least 1 and offset must not be negative.";
        assertFieldProblem(problem(get("/v1/probe/page").param("limit", "0").param("offset", "0"),
                acceptLanguage, 400), "limit", sentence);
        assertFieldProblem(problem(get("/v1/probe/page").param("limit", "5").param("offset", "-1"),
                acceptLanguage, 400), "offset", sentence);
    }
}
