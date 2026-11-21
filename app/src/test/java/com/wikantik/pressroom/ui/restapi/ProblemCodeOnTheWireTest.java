package com.wikantik.pressroom.ui.restapi;

import java.util.Map;
import com.wikantik.pressroom.business.AnchorLockTimeoutException;
import com.wikantik.pressroom.ui.restapi.auth.ApiAccessDeniedHandler;
import com.wikantik.pressroom.ui.restapi.auth.ApiAuthenticationEntryPoint;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.InsufficientAuthenticationException;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Every problem body this API builds has a {@code code}, and so does each of
 * its field errors (spec 2026-11-20-api-contract-design, Task 1, criterion 1):
 * the ones {@code ApiExceptionHandler} writes, for every status the API
 * answers with a problem, and the ones written outside it through
 * {@code ApiProblemWriter}. The MCP {@code about:blank} path is pinned in
 * {@code McpToolExecutorTest}.
 */
class ProblemCodeOnTheWireTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    /** A handler in the restapi package, so ApiExceptionHandler advises it. */
    @RestController
    static class ProbeApi {
        @GetMapping("/v1/codes/{kind}")
        Map<String, String> raise(@PathVariable("kind") String kind) throws AnchorLockTimeoutException {
            if ("busy".equals(kind)) {
                throw new AnchorLockTimeoutException("myblog", 10);
            }
            throw switch (kind) {
                case "bad-request" -> ApiException.badRequest("Unknown status 'X'.");
                case "invalid-field" -> ApiException.invalidField("geoLatitude", "geoLatitude is out of range.");
                case "unauthorized" -> ApiException.unauthorized("MCP accepts API tokens only.");
                case "forbidden" -> ApiException.forbidden("This token is read-only.");
                case "not-found" -> ApiException.notFound("No such entry.");
                case "conflict" -> ApiException.conflict("The entry is already in the trash.");
                case "conflict-on-field" -> ApiException.conflictOnField("anchor", "anchor is taken.");
                case "too-large" -> ApiException.tooLarge("The request body is over 5 bytes.");
                case "throttled" -> ApiException.throttled("Too many requests. Slow down and retry.");
                case "bad-gateway" -> ApiException.badGateway("The mail could not be sent.");
                default -> new IllegalStateException(kind);
            };
        }

        @GetMapping("/v1/codes-ok")
        Map<String, String> ok() {
            return Map.of("ok", "yes");
        }

        @PostMapping("/v1/codes-body")
        Map<String, String> body(@RequestBody Map<String, String> body) {
            return body;
        }
    }

    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(new ProbeApi())
            .setControllerAdvice(new ApiExceptionHandler()).build();

    private JsonNode problem(MockHttpServletRequestBuilder request, int status) throws Exception {
        MockHttpServletResponse response = mvc.perform(request).andReturn().getResponse();
        assertEquals(status, response.getStatus(), response.getContentAsString());
        assertEquals("application/problem+json", response.getContentType());
        return JSON.readTree(response.getContentAsString());
    }

    private String code(String kind, int status) throws Exception {
        return problem(get("/v1/codes/" + kind), status).get("code").asString();
    }

    @Test
    void everyStatusTheAdviceAnswersCarriesACode() throws Exception {
        assertEquals("request.invalid", code("bad-request", 400));
        assertEquals("auth.unauthorized", code("unauthorized", 401));
        assertEquals("auth.forbidden", code("forbidden", 403));
        assertEquals("resource.notFound", code("not-found", 404));
        assertEquals("resource.conflict", code("conflict", 409));
        assertEquals("request.tooLarge", code("too-large", 413));
        assertEquals("request.throttled", code("throttled", 429));
        assertEquals("server.internalError", code("unexpected", 500));
        assertEquals("upstream.failure", code("bad-gateway", 502));
        assertEquals("server.busy", code("busy", 503));
        assertEquals("request.notAcceptable",
                problem(get("/v1/codes-ok").accept(MediaType.TEXT_HTML), 406).get("code").asString());
        assertEquals("request.unsupportedMediaType",
                problem(post("/v1/codes-body").contentType(MediaType.TEXT_PLAIN).content("x"), 415)
                        .get("code").asString());
        assertEquals("request.malformedBody",
                problem(post("/v1/codes-body").contentType(MediaType.APPLICATION_JSON).content("{"), 400)
                        .get("code").asString());
    }

    @Test
    void everyFieldErrorCarriesACode() throws Exception {
        JsonNode invalid = problem(get("/v1/codes/invalid-field"), 400);
        assertEquals("request.invalid", invalid.get("code").asString());
        assertEquals("request.invalid", invalid.get("errors").get(0).get("code").asString());

        JsonNode conflict = problem(get("/v1/codes/conflict-on-field"), 409);
        assertEquals("resource.conflict", conflict.get("code").asString());
        assertEquals("resource.conflict", conflict.get("errors").get(0).get("code").asString());
    }

    @Test
    void theEntryPointsProblemCarriesACode() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        ApiAuthenticationEntryPoint.refusingPasswords(new ApiProblemWriter(JSON)).commence(
                new MockHttpServletRequest("GET", "/api/v1/me"), response,
                new InsufficientAuthenticationException("anonymous"));

        assertEquals(401, response.getStatus());
        assertEquals("auth.unauthorized", JSON.readTree(response.getContentAsString()).get("code").asString());
    }

    @Test
    void theAccessDeniedProblemCarriesACode() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        new ApiAccessDeniedHandler(new ApiProblemWriter(JSON)).handle(
                new MockHttpServletRequest("GET", "/api/v1/admin/users"), response, new AccessDeniedException("no"));

        assertEquals(403, response.getStatus());
        assertEquals("auth.forbidden", JSON.readTree(response.getContentAsString()).get("code").asString());
    }

    @Test
    void theBodyLimitsProblemCarriesACode() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/weblogs/b/entries");
        request.setContentType("application/json");
        request.setContent(new byte[ApiBodyLimitFilter.MAX_BODY_BYTES + 1]);
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        new ApiBodyLimitFilter(new ApiProblemWriter(JSON)).doFilter(request, response, chain);

        assertEquals(413, response.getStatus());
        assertEquals("request.tooLarge", JSON.readTree(response.getContentAsString()).get("code").asString());
        verifyNoInteractions(chain);
    }
}
