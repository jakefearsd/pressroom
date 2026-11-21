package com.wikantik.pressroom.ui.restapi.v1;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import com.wikantik.pressroom.business.Weblogger;
import com.wikantik.pressroom.business.fetch.RemoteImageFetcher;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.http.converter.autoconfigure.HttpMessageConvertersAutoConfiguration;
import org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration;
import org.springframework.boot.webmvc.autoconfigure.WebMvcAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.MapPropertySource;
import org.springframework.mock.web.MockServletContext;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springdoc.core.configuration.SpringDocConfiguration;
import org.springdoc.core.properties.SpringDocConfigProperties;
import org.springdoc.webmvc.core.configuration.MultipleOpenApiSupportConfiguration;
import org.springdoc.webmvc.core.configuration.SpringDocWebMvcConfiguration;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.webAppContextSetup;

/**
 * Every list operation answers the one paging shape. This generates the
 * document as springdoc does and asserts that every operation taking a
 * {@code limit} query parameter answers a schema with {@code items} (or
 * {@code entries} for the audit report, which carries a tally), {@code offset},
 * {@code limit} and {@code hasMore}, all required.
 *
 * <p>Task 4 of the API contract spec folds this into OpenApiContractTest.
 */
class ListShapeOpenApiTest {

    /**
     * HELD for the owner (2026-11-20): these three still answer a bare array,
     * and the wrap to an envelope is a breaking change to live agents. The
     * held wrap would empty this set; when it lands, delete it.
     */
    private static final Set<String> HELD_BARE_ARRAYS = Set.of(
            "GET /v1/weblogs",
            "GET /v1/admin/users",
            "GET /v1/weblogs/{handle}/media");

    private static final Set<String> PAGING = Set.of("offset", "limit", "hasMore");

    private static AnnotationConfigWebApplicationContext context;
    private static JsonNode document;

    @Configuration
    @ImportAutoConfiguration({JacksonAutoConfiguration.class, HttpMessageConvertersAutoConfiguration.class,
            WebMvcAutoConfiguration.class, SpringDocConfigProperties.class, SpringDocConfiguration.class,
            SpringDocWebMvcConfiguration.class, MultipleOpenApiSupportConfiguration.class})
    static class Config {
        @Bean
        EntriesApi entriesApi() {
            return new EntriesApi();
        }

        @Bean
        CommentsApi commentsApi() {
            return new CommentsApi();
        }

        @Bean
        AuditApi auditApi() {
            return new AuditApi();
        }

        @Bean
        MediaApi mediaApi() {
            return new MediaApi();
        }

        @Bean
        WeblogsApi weblogsApi() {
            return new WeblogsApi();
        }

        @Bean
        AdminApi adminApi() {
            return new AdminApi();
        }

        @Bean
        com.wikantik.pressroom.business.fetch.RemoteImageFetcher remoteImageFetcher() {
            return mock(com.wikantik.pressroom.business.fetch.RemoteImageFetcher.class);
        }

        @Bean
        Weblogger weblogger() {
            return mock(Weblogger.class);
        }
    }

    @BeforeAll
    static void generate() throws Exception {
        context = new AnnotationConfigWebApplicationContext();
        context.setServletContext(new MockServletContext());
        context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("test",
                Map.of("springdoc.api-docs.path", "/v1/openapi.json")));
        context.register(Config.class);
        context.refresh();
        String json = webAppContextSetup(context).build().perform(get("/v1/openapi.json"))
                .andReturn().getResponse().getContentAsString();
        document = new ObjectMapper().readTree(json);
    }

    @AfterAll
    static void close() {
        context.close();
    }

    @Test
    void everyPagedOperationAnswersTheOneShapeExceptTheHeldBareArrays() {
        Set<String> seen = new HashSet<>();
        Set<String> bare = new HashSet<>();
        for (String path : document.get("paths").propertyNames()) {
            JsonNode item = document.get("paths").get(path);
            for (String verb : item.propertyNames()) {
                JsonNode operation = item.get(verb);
                if (!takesLimit(operation)) {
                    continue;
                }
                String name = verb.toUpperCase(java.util.Locale.ROOT) + " " + path;
                seen.add(name);
                JsonNode schema = resolve(operation.get("responses").get("200").path("content").path("*/*")
                        .path("schema"));
                if ("array".equals(schema.path("type").asString())) {
                    bare.add(name);
                    continue;
                }
                JsonNode properties = schema.path("properties");
                String list = properties.has("items") ? "items" : "entries";
                Set<String> required = new HashSet<>();
                schema.path("required").forEach(r -> required.add(r.asString()));
                Set<String> wanted = new HashSet<>(PAGING);
                wanted.add(list);
                for (String member : wanted) {
                    assertTrue(properties.has(member), name + " answers " + member + ": " + schema);
                    assertTrue(required.contains(member), name + " marks " + member + " required: " + schema);
                }
                if (path.endsWith("/audit/seo")) {
                    assertTrue(properties.has("entries"), name + " keeps entries, a report not a list");
                }
            }
        }
        assertEquals(HELD_BARE_ARRAYS, bare, "exactly the held operations answer a bare array");
        assertTrue(seen.containsAll(Set.of("GET /v1/weblogs/{handle}/entries", "GET /v1/weblogs/{handle}/comments",
                "GET /v1/weblogs/{handle}/audit/seo")), "the paged operations were found: " + seen);
    }

    private static boolean takesLimit(JsonNode operation) {
        for (JsonNode parameter : operation.path("parameters")) {
            if ("limit".equals(parameter.path("name").asString()) && "query".equals(parameter.path("in").asString())) {
                return true;
            }
        }
        return false;
    }

    private static JsonNode resolve(JsonNode schema) {
        String ref = schema.path("$ref").asString();
        if (ref == null || ref.isEmpty()) {
            return schema;
        }
        return document.get("components").get("schemas").get(ref.substring(ref.lastIndexOf('/') + 1));
    }
}
