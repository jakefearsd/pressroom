package com.wikantik.pressroom.ui.restapi.v1;

import java.util.ArrayList;
import java.util.List;
import tools.jackson.databind.JsonNode;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Asserts a problem body's {@code code}, its English {@code detail} and each
 * field error's {@code code} and {@code message} (spec
 * 2026-11-20-api-contract-design, Task 2). The English is asserted beside the
 * code so a test also holds the text byte-for-byte to what it was before the
 * call site was keyed.
 */
final class ProblemAsserts {

    private ProblemAsserts() {
    }

    /** The problem's {@code code} and {@code detail}. */
    static void problem(JsonNode problem, String code, String detail) {
        assertEquals(code, problem.get("code").asString(), "code of " + problem);
        assertEquals(detail, problem.get("detail").asString(), "detail of " + problem);
    }

    /** The one field error naming {@code field}: its {@code code} and {@code message}. */
    static void field(JsonNode problem, String field, String code, String message) {
        for (JsonNode error : problem.get("errors")) {
            if (field.equals(error.get("field").asString())) {
                assertEquals(code, error.get("code").asString(), "code of the error on " + field);
                assertEquals(message, error.get("message").asString(), "message of the error on " + field);
                return;
            }
        }
        fail("no error on " + field + " in " + problem);
    }

    /** A problem about exactly one field: the problem's code is that field's. */
    static void oneField(JsonNode problem, String field, String code, String message) {
        assertEquals(1, problem.get("errors").size(), "errors of " + problem);
        problem(problem, code, message);
        field(problem, field, code, message);
    }

    /** Each field error as {@code field=code}, in order. */
    static List<String> fieldCodes(JsonNode problem) {
        List<String> codes = new ArrayList<>();
        for (JsonNode error : problem.get("errors")) {
            codes.add(error.get("field").asString() + "=" + error.get("code").asString());
        }
        return codes;
    }
}
