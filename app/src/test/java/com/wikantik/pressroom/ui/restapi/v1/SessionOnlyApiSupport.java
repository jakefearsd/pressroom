package com.wikantik.pressroom.ui.restapi.v1;

import com.wikantik.pressroom.business.MockWeblogger;
import com.wikantik.pressroom.pojos.User;
import com.wikantik.pressroom.pojos.Weblog;
import com.wikantik.pressroom.ui.restapi.ApiExceptionHandler;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * What the wave-3 handler tests share: a mocked business tier, the weblog {@code myblog} and its admin the
 * way {@code PressroomHandlerInterceptor} leaves them as request attributes, a standalone MockMvc over one
 * controller, and problem assertions. Who may call a route is the interceptors' and is swept elsewhere.
 */
final class SessionOnlyApiSupport {

    static final ObjectMapper JSON = new ObjectMapper();

    final MockWeblogger tier = MockWeblogger.attached();
    final Weblog weblog = new Weblog();
    final User admin = new User();
    final MockMvc mvc;

    SessionOnlyApiSupport(BaseApiController controller) {
        weblog.setId("myblog-id");
        weblog.setHandle("myblog");
        weblog.setName("My blog");
        weblog.setEditorTheme("journal");
        admin.setUserName("owen");
        controller.weblogger = tier.weblogger();
        mvc = MockMvcBuilders.standaloneSetup(controller).setControllerAdvice(new ApiExceptionHandler()).build();
    }

    ResultActions send(MockHttpServletRequestBuilder builder, String body) throws Exception {
        builder.requestAttr("actionWeblog", weblog).requestAttr("authenticatedUser", admin);
        if (body != null) {
            builder.contentType(MediaType.APPLICATION_JSON).content(body);
        }
        return mvc.perform(builder);
    }

    static JsonNode body(ResultActions result) throws Exception {
        return JSON.readTree(result.andReturn().getResponse().getContentAsString());
    }

    static int status(ResultActions result) {
        return result.andReturn().getResponse().getStatus();
    }

    /** The status and the problem code (the first field's, when several fields are named). */
    static void refused(ResultActions result, int status, String code) throws Exception {
        assertEquals(status, status(result), result.andReturn().getResponse().getContentAsString());
        JsonNode problem = body(result);
        String got = problem.path("code").asString("");
        if (got.equals("request.invalidFields")) {
            got = problem.path("errors").path(0).path("code").asString("");
        }
        assertEquals(code, got);
    }

    void detach() {
        tier.detach();
    }
}
