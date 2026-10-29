package com.wikantik.pressroom.ui.restapi.v1;

import java.util.Map;
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
 * The entry routes return {@code Object} since they take {@code fields}
 * ({@link EntryFields}), so springdoc can no longer read the response type
 * from the signature. This pins what openapi.json says about them, as
 * springdoc itself generates it: each still answers an {@code EntryView} (a
 * list an {@code EntryPage}), and each documents {@code fields} as an array
 * of strings.
 */
class EntriesOpenApiTest {

    private static AnnotationConfigWebApplicationContext context;
    private static JsonNode paths;

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
        EntriesWriteApi entriesWriteApi() {
            return new EntriesWriteApi();
        }

        @Bean
        Weblogger weblogger() {
            return mock(Weblogger.class);
        }

        @Bean
        RemoteImageFetcher remoteImageFetcher() {
            return mock(RemoteImageFetcher.class);
        }
    }

    @BeforeAll
    static void generate() throws Exception {
        context = new AnnotationConfigWebApplicationContext();
        context.setServletContext(new MockServletContext());
        // as application.properties serves it, relative to the stripped /api prefix
        context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("test",
                Map.of("springdoc.api-docs.path", "/v1/openapi.json")));
        context.register(Config.class);
        context.refresh();
        String document = webAppContextSetup(context).build().perform(get("/v1/openapi.json"))
                .andReturn().getResponse().getContentAsString();
        paths = new ObjectMapper().readTree(document).get("paths");
    }

    @AfterAll
    static void close() {
        context.close();
    }

    @Test
    void everyEntryRouteTakingFieldsStillDocumentsTheViewItAnswers() {
        String entries = "/v1/weblogs/{handle}/entries";
        String[][] routes = {
            {"get", entries, "200", "EntryPage"},
            {"get", entries + "/{id}", "200", "EntryView"},
            {"get", entries + "/by-anchor/{anchor}", "200", "EntryView"},
            {"post", entries, "201", "EntryView"},
            {"patch", entries + "/{id}", "200", "EntryView"},
            {"post", entries + "/{id}/restore", "200", "EntryView"},
            {"post", entries + "/{id}/revisions/{revisionId}/restore", "200", "EntryView"},
        };
        for (String[] route : routes) {
            String name = route[0].toUpperCase(java.util.Locale.ROOT) + " " + route[1];
            JsonNode operation = paths.get(route[1]).get(route[0]);
            JsonNode response = operation.get("responses").get(route[2]);
            assertTrue(response != null, name + " documents a " + route[2] + ": " + operation.get("responses"));
            JsonNode schema = response.path("content").path("*/*").path("schema");
            assertEquals("#/components/schemas/" + route[3], schema.path("$ref").asString(), name + ": " + schema);

            JsonNode fields = null;
            for (JsonNode parameter : operation.get("parameters")) {
                if ("fields".equals(parameter.get("name").asString())) {
                    fields = parameter;
                }
            }
            assertTrue(fields != null, name + " documents fields");
            assertEquals("query", fields.get("in").asString(), name);
            assertEquals("array", fields.get("schema").get("type").asString(), name);
            assertEquals("string", fields.get("schema").get("items").get("type").asString(), name);
            assertEquals(EntryFields.DESCRIPTION, fields.get("description").asString(), name);
        }
    }

    /**
     * The revision routes (spec 2026-10-24, section 5) name their views, and
     * a restore's body is optional: a bare POST restores without a hash check.
     */
    @Test
    void theRevisionRoutesNameTheirViewsAndTheRestoreBodyIsOptional() {
        String revisions = "/v1/weblogs/{handle}/entries/{id}/revisions";
        JsonNode list = paths.get(revisions).get("get").get("responses").get("200").path("content").path("*/*")
                .path("schema");
        assertEquals("array", list.path("type").asString(), list.toString());
        assertEquals("#/components/schemas/RevisionSummary", list.path("items").path("$ref").asString());
        JsonNode one = paths.get(revisions + "/{revisionId}").get("get").get("responses").get("200")
                .path("content").path("*/*").path("schema");
        assertEquals("#/components/schemas/RevisionView", one.path("$ref").asString(), one.toString());
        JsonNode body = paths.get(revisions + "/{revisionId}/restore").get("post").get("requestBody");
        assertFalse(body.path("required").asBoolean(false), body.toString());
    }
}
