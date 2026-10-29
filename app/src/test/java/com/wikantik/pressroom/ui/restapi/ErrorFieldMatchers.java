package com.wikantik.pressroom.ui.restapi;

import java.util.ArrayList;
import java.util.List;
import org.springframework.test.web.servlet.ResultMatcher;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Asserts the field names a problem body's {@code errors} array carries. Reads
 * the JSON with Jackson rather than {@code jsonPath}, which needs hamcrest on
 * the test classpath and this build does not carry it.
 */
public final class ErrorFieldMatchers {

    private static final ObjectMapper JSON = new ObjectMapper();

    private ErrorFieldMatchers() {
    }

    /** The first {@code errors} entry names {@code field}. */
    public static ResultMatcher errorFieldIs(String field) {
        return result -> assertEquals(field, fieldsOf(result.getResponse().getContentAsString()).get(0));
    }

    /** The {@code errors} entries name exactly {@code fields}, in order. */
    public static ResultMatcher errorFieldsAre(String... fields) {
        return result -> assertEquals(List.of(fields), fieldsOf(result.getResponse().getContentAsString()));
    }

    private static List<String> fieldsOf(String body) {
        List<String> names = new ArrayList<>();
        JsonNode errors = JSON.readTree(body).get("errors");
        for (JsonNode error : errors) {
            names.add(error.get("field").asString());
        }
        return names;
    }
}
