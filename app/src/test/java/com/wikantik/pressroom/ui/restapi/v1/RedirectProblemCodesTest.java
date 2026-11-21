package com.wikantik.pressroom.ui.restapi.v1;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import com.wikantik.pressroom.WebloggerException;
import com.wikantik.pressroom.business.WeblogRedirectManager;
import com.wikantik.pressroom.business.Weblogger;
import com.wikantik.pressroom.business.jpa.JPAPersistenceStrategy;
import com.wikantik.pressroom.business.jpa.JPAWeblogRedirectManagerImpl;
import com.wikantik.pressroom.pojos.Weblog;
import com.wikantik.pressroom.pojos.WeblogRedirect;
import com.wikantik.pressroom.ui.restapi.ApiExceptionHandler;
import jakarta.persistence.TypedQuery;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultMatcher;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import static com.wikantik.pressroom.ui.restapi.v1.ProblemAsserts.oneField;
import static com.wikantik.pressroom.ui.restapi.v1.ProblemAsserts.problem;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Every refusal of the redirect routes carries its own code, with the English
 * it always had (spec 2026-11-20-api-contract-design, Task 3). The rule
 * refusals come from the real manager (its queries answered by mocks), so the
 * English is the business tier's own: it passed before Task 3
 * (characterisation), and the code is what Task 3 adds.
 */
class RedirectProblemCodesTest {

    private static final String REDIRECTS = "/v1/weblogs/myblog/redirects";

    private final Map<String, List<WeblogRedirect>> rulesFrom = new HashMap<>();
    private final Map<String, List<WeblogRedirect>> rulesTo = new HashMap<>();
    private Weblog weblog;
    private MockMvc mvc;

    @BeforeEach
    void setUp() throws Exception {
        Weblogger weblogger = mock(Weblogger.class);
        JPAPersistenceStrategy strategy = mock(JPAPersistenceStrategy.class);
        TypedQuery<WeblogRedirect> bySource = query(rulesFrom);
        TypedQuery<WeblogRedirect> byTarget = query(rulesTo);
        when(strategy.getNamedQueryCommitFirst(eq("WeblogRedirect.getByWeblogAndSource"), eq(WeblogRedirect.class)))
                .thenReturn(bySource);
        when(strategy.getNamedQueryCommitFirst(eq("WeblogRedirect.getByWeblogAndTarget"), eq(WeblogRedirect.class)))
                .thenReturn(byTarget);
        WeblogRedirectManager manager = new JPAWeblogRedirectManagerImpl(strategy) { };
        when(weblogger.getWeblogRedirectManager()).thenReturn(manager);
        weblog = new Weblog();
        weblog.setId("myblog-id");
        weblog.setHandle("myblog");
        mvc = mvc(weblogger);
    }

    /** A named query answering, for the path bound as its second parameter, the rules {@code rows} holds. */
    @SuppressWarnings("unchecked")
    private static TypedQuery<WeblogRedirect> query(Map<String, List<WeblogRedirect>> rows) {
        TypedQuery<WeblogRedirect> query = mock(TypedQuery.class);
        String[] path = new String[1];
        when(query.setParameter(eq(2), any())).thenAnswer(inv -> {
            path[0] = inv.getArgument(1);
            return query;
        });
        when(query.getResultList()).thenAnswer(inv -> rows.getOrDefault(path[0], List.of()));
        return query;
    }

    private static MockMvc mvc(Weblogger weblogger) {
        RedirectsApi controller = new RedirectsApi();
        controller.weblogger = weblogger;
        return MockMvcBuilders.standaloneSetup(controller).setControllerAdvice(new ApiExceptionHandler()).build();
    }

    private JsonNode answer(MockMvc on, ResultMatcher status, MockHttpServletRequestBuilder request) throws Exception {
        String body = on.perform(request.requestAttr("actionWeblog", weblog).contentType(MediaType.APPLICATION_JSON))
                .andExpect(status).andReturn().getResponse().getContentAsString();
        return new ObjectMapper().readTree(body);
    }

    private JsonNode create(String source, String target) throws Exception {
        String body = new ObjectMapper().writeValueAsString(Map.of("source", source, "target", target));
        return answer(mvc, status().isBadRequest(), post(REDIRECTS).content(body));
    }

    private static WeblogRedirect rule(String id, String source, String target) {
        WeblogRedirect rule = new WeblogRedirect();
        rule.setId(id);
        rule.setSourcePath(source);
        rule.setTargetPath(target);
        return rule;
    }

    @Test
    void aMissingSourceOrTargetIsFieldRequired() throws Exception {
        oneField(answer(mvc, status().isBadRequest(), post(REDIRECTS).content("{\"target\":\"/b\"}")),
                "source", "field.required", "source is required.");
        oneField(answer(mvc, status().isBadRequest(), post(REDIRECTS).content("{\"source\":\"/a\"}")),
                "target", "field.required", "target is required.");
    }

    @Test
    void aPathOfTheWrongShapeHasItsCode() throws Exception {
        problem(create("/a", "//evil.example/x"), "redirect.pathNotRelative",
                "redirect target must be a weblog-relative path starting with '/': //evil.example/x");
        problem(create("/a?x=1", "/b"), "redirect.pathHasQuery",
                "redirect source may not carry a query string: /a?x=1");
        problem(create("/a\\b", "/b"), "redirect.pathHasBackslash",
                "redirect source may not contain a backslash: /a\\b");
        problem(create("/a", "/b\u0001c"), "redirect.pathHasControlCharacters",
                "redirect target may not contain control characters");
        problem(create("/a/", "/a"), "redirect.targetIsSource", "redirect target equals its source: /a");
    }

    @Test
    void aRuleThatWouldDuplicateOrChainHasItsCode() throws Exception {
        rulesFrom.put("/a", List.of(rule("r1", "/a", "/z")));
        problem(create("/a", "/b"), "redirect.sourceExists", "a redirect for /a already exists");

        rulesFrom.clear();
        rulesTo.put("/a", List.of(rule("r2", "/old", "/a")));
        problem(create("/a", "/b"), "redirect.sourceIsTarget", "redirect source /a is already the target of /old");

        rulesTo.clear();
        rulesFrom.put("/b", List.of(rule("r3", "/b", "/c")));
        problem(create("/a", "/b"), "redirect.targetRedirected", "redirect target /b is itself redirected (to /c)");
    }

    /**
     * A refusal the manager raises without a reason of its own is passed
     * through whole, as it always was: its text is not a finite set.
     */
    @Test
    void aRefusalWithNoReasonIsRedirectRefused() throws Exception {
        Weblogger mocked = mock(Weblogger.class);
        WeblogRedirectManager manager = mock(WeblogRedirectManager.class);
        when(mocked.getWeblogRedirectManager()).thenReturn(manager);
        doThrow(new WebloggerException("redirect must belong to a weblog")).when(manager).saveRedirect(any());

        problem(answer(mvc(mocked), status().isBadRequest(), post(REDIRECTS).content("{\"source\":\"/a\",\"target\":\"/b\"}")),
                "redirect.refused", "redirect must belong to a weblog");
    }

    @Test
    void anUnknownRuleIsRedirectNotFound() throws Exception {
        problem(answer(mvc, status().isNotFound(), delete(REDIRECTS + "/nope")), "redirect.notFound",
                "No such redirect.");
    }
}
