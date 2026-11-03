package com.wikantik.pressroom.ui.restapi.auth;

import java.util.List;
import java.util.Map;
import com.wikantik.pressroom.boot.WebMvcConfig;
import com.wikantik.pressroom.business.MockWeblogger;
import com.wikantik.pressroom.business.WebloggerProvider;
import com.wikantik.pressroom.pojos.ApiToken;
import com.wikantik.pressroom.ui.controllers.PressroomHandlerInterceptor;
import com.wikantik.pressroom.ui.restapi.ApiExceptionHandler;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springdoc.core.configuration.SpringDocConfiguration;
import org.springdoc.core.properties.SpringDocConfigProperties;
import org.springdoc.webmvc.core.configuration.MultipleOpenApiSupportConfiguration;
import org.springdoc.webmvc.core.configuration.SpringDocWebMvcConfiguration;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.http.converter.autoconfigure.HttpMessageConvertersAutoConfiguration;
import org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration;
import org.springframework.boot.webmvc.autoconfigure.WebMvcAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.MapPropertySource;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockServletContext;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.webAppContextSetup;

/**
 * springdoc's OpenAPI document, served under the API mount, asked for with a
 * token pinned to one weblog. ApiScopeInterceptor refuses it, as it refuses
 * every route that is not part of one weblog; but the handler is springdoc's,
 * outside {@code ApiExceptionHandler}'s package, so the refusal escaped the
 * dispatcher and the client got the container's 500 HTML error page. It must
 * answer as the rest of the API does: 404, problem+json.
 *
 * <p>The real springdoc controller, the real {@code WebMvcConfig}
 * registration, and a request shaped as the {@code /api/*} prefix mapping
 * shapes it.
 */
class OpenApiPinnedTokenDispatchTest {

    private static AnnotationConfigWebApplicationContext context;
    private static MockMvc mockMvc;
    private static MockWeblogger weblogger;

    @Configuration
    @ImportAutoConfiguration({JacksonAutoConfiguration.class, HttpMessageConvertersAutoConfiguration.class,
            WebMvcAutoConfiguration.class, SpringDocConfigProperties.class, SpringDocConfiguration.class,
            SpringDocWebMvcConfiguration.class, MultipleOpenApiSupportConfiguration.class})
    static class Config {
        @Bean
        WebMvcConfig webMvcConfig(PressroomHandlerInterceptor pressroomHandlerInterceptor) {
            return new WebMvcConfig(pressroomHandlerInterceptor);
        }

        @Bean
        PressroomHandlerInterceptor pressroomHandlerInterceptor() {
            WebloggerProvider provider = mock(WebloggerProvider.class);
            when(provider.isBootstrapped()).thenReturn(true);
            return new PressroomHandlerInterceptor(provider, weblogger.weblogger());
        }

        @Bean
        ApiExceptionHandler apiExceptionHandler() {
            return new ApiExceptionHandler();
        }
    }

    @BeforeAll
    static void setUpContext() {
        weblogger = MockWeblogger.attached();
        context = new AnnotationConfigWebApplicationContext();
        context.setServletContext(new MockServletContext());
        context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("test",
                Map.of("springdoc.api-docs.path", "/v1/openapi.json")));
        context.register(Config.class);
        context.refresh();
        mockMvc = webAppContextSetup(context).build();
    }

    @AfterAll
    static void tearDownContext() {
        if (context != null) {
            context.close();
        }
        weblogger.detach();
    }

    @AfterEach
    void clearAuthentication() {
        SecurityContextHolder.clearContext();
    }

    private static void authenticate(String scopeWeblog) {
        var auth = new UsernamePasswordAuthenticationToken("agent", null, List.of());
        auth.setDetails(new ApiPrincipal("agent", scopeWeblog, ApiToken.Role.ADMIN, "token-1"));
        SecurityContextHolder.getContext().setAuthentication(auth);
    }

    private static MvcResult fetchDocument() throws Exception {
        return mockMvc.perform(get("/api/v1/openapi.json").servletPath("/api").pathInfo("/v1/openapi.json"))
                .andReturn();
    }

    @Test
    void aPinnedTokenIsRefusedWithTheApis404Problem() throws Exception {
        authenticate("myblog");

        MvcResult result = fetchDocument();

        assertEquals(404, result.getResponse().getStatus());
        assertEquals(MediaType.APPLICATION_PROBLEM_JSON_VALUE, result.getResponse().getContentType());
        JsonNode problem = new ObjectMapper().readTree(result.getResponse().getContentAsString());
        assertTrue(problem.get("type").asString().endsWith("/not-found"), problem.toString());
        assertTrue(problem.get("detail").asString().contains("limited to the weblog 'myblog'"), problem.toString());
        assertEquals("/api/v1/openapi.json", problem.get("instance").asString());
    }

    /** Control: an unpinned token still reads the document. */
    @Test
    void anUnpinnedTokenReadsTheDocument() throws Exception {
        authenticate(null);

        MvcResult result = fetchDocument();

        assertEquals(200, result.getResponse().getStatus());
        assertTrue(result.getResponse().getContentAsString().contains("\"openapi\""));
    }
}
