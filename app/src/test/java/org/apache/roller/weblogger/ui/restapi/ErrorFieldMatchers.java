package org.apache.roller.weblogger.ui.restapi;

import org.springframework.test.web.servlet.ResultMatcher;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Asserts the field a problem body's first {@code errors} entry names. Reads
 * the JSON with Jackson rather than {@code jsonPath}, which needs hamcrest on
 * the test classpath and this build does not carry it.
 */
public final class ErrorFieldMatchers {

    private ErrorFieldMatchers() {
    }

    public static ResultMatcher errorFieldIs(String field) {
        return result -> assertEquals(field, new tools.jackson.databind.ObjectMapper()
                .readTree(result.getResponse().getContentAsString())
                .get("errors").get(0).get("field").asString());
    }
}
