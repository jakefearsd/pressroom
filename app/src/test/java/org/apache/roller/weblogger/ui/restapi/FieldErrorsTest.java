package org.apache.roller.weblogger.ui.restapi;

import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Several checks' 400s reported as one (spec 2026-10-13, section 1). */
class FieldErrorsTest {

    @Test
    void everyFailingCheckIsReportedInOneException() {
        FieldErrors errors = new FieldErrors();
        errors.check("category", () -> {
            throw ApiException.badRequest("Unknown category 'X'.");
        });
        errors.check("geoLatitude", () -> {
            throw ApiException.badRequest("geoLatitude must be between -90 and 90.");
        });
        errors.check("title", () -> { });

        ApiException ex = assertThrows(ApiException.class, errors::throwIfAny);

        assertEquals(400, ex.getStatus());
        assertEquals(List.of("category", "geoLatitude"),
                ex.getFieldErrors().stream().map(ApiProblem.FieldError::field).toList());
        assertEquals("Unknown category 'X'. geoLatitude must be between -90 and 90.", ex.getMessage());
    }

    @Test
    void aSingleProblemKeepsItsOwnMessageAsTheDetail() {
        FieldErrors errors = new FieldErrors();
        errors.check("status", () -> {
            throw ApiException.badRequest("Unknown status 'X'.");
        });

        ApiException ex = assertThrows(ApiException.class, errors::throwIfAny);

        assertEquals("Unknown status 'X'.", ex.getMessage());
        assertEquals("status", ex.getFieldErrors().get(0).field());
    }

    @Test
    void aFieldTheCheckAlreadyNamedIsKept() {
        FieldErrors errors = new FieldErrors();
        errors.check("tags", () -> {
            throw ApiException.invalidField("tag", "too long");
        });

        ApiException ex = assertThrows(ApiException.class, errors::throwIfAny);

        assertEquals("tag", ex.getFieldErrors().get(0).field());
    }

    @Test
    void aCheckWithoutAFieldContributesEveryErrorItNames() {
        FieldErrors errors = new FieldErrors();
        errors.check(() -> {
            throw ApiException.validation("a b", List.of(
                    new ApiProblem.FieldError("a", "a"), new ApiProblem.FieldError("b", "b")));
        });

        ApiException ex = assertThrows(ApiException.class, errors::throwIfAny);

        assertEquals(2, ex.getFieldErrors().size());
    }

    @Test
    void anythingButA400Propagates() {
        FieldErrors errors = new FieldErrors();

        ApiException conflict = assertThrows(ApiException.class,
                () -> errors.check("anchor", () -> {
                    throw ApiException.conflict("taken");
                }));

        assertEquals(409, conflict.getStatus());
        assertTrue(errors.isEmpty());
    }

    @Test
    void noProblemsMeansNoException() {
        FieldErrors errors = new FieldErrors();
        errors.check("title", () -> { });

        assertDoesNotThrow(errors::throwIfAny);
    }

    @Test
    void addRecordsAProblemDirectly() {
        FieldErrors errors = new FieldErrors();
        errors.add("anchor", "anchor cannot change.");

        ApiException ex = assertThrows(ApiException.class, errors::throwIfAny);

        assertEquals("anchor cannot change.", ex.getMessage());
    }

    @Test
    void theProblemBodyCarriesTheFieldsAndOmitsAMissingOne() {
        ApiException ex = ApiException.validation("x y", List.of(
                new ApiProblem.FieldError("category", "x"), new ApiProblem.FieldError(null, "y")));
        ObjectMapper json = new ObjectMapper();

        JsonNode problem = json.readTree(json.writeValueAsString(ex.toProblem("/api/v1/x")));

        assertEquals("category", problem.get("errors").get(0).get("field").asString());
        assertFalse(problem.get("errors").get(1).has("field"));
    }

    @Test
    void invalidFieldAndConflictOnFieldNameTheirField() {
        assertEquals("url", ApiException.invalidField("url", "bad", new IllegalStateException())
                .getFieldErrors().get(0).field());
        ApiException conflict = ApiException.conflictOnField("anchor", "taken");
        assertEquals(409, conflict.getStatus());
        assertEquals("anchor", conflict.getFieldErrors().get(0).field());
        assertTrue(ApiException.badRequest("plain").getFieldErrors().isEmpty());
    }
}
