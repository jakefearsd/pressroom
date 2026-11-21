package com.wikantik.pressroom.ui.restapi;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.Logger;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Property;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Every problem carries a {@code code} (spec 2026-11-20-api-contract-design,
 * Task 1): a literal-detail factory the generic code for its type, a keyed
 * problem its own, a single field error lifting its code to the problem and
 * several giving {@code request.invalidFields}.
 */
class ApiExceptionCodeTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static String code(ApiException e) {
        return e.toProblem("/api/v1/x").code();
    }

    @Test
    void everyLiteralDetailFactoryCarriesTheGenericCodeForItsType() {
        Throwable cause = new IllegalStateException("x");
        assertEquals("request.invalid", code(ApiException.badRequest("b")));
        assertEquals("request.invalid", code(ApiException.badRequest("b", cause)));
        assertEquals("resource.notFound", code(ApiException.notFound("n")));
        assertEquals("auth.unauthorized", code(ApiException.unauthorized("u")));
        assertEquals("auth.forbidden", code(ApiException.forbidden("f")));
        assertEquals("resource.conflict", code(ApiException.conflict("c")));
        assertEquals("resource.conflict", code(ApiException.conflict("c", cause)));
        assertEquals("server.busy", code(ApiException.busy("b")));
        assertEquals("request.tooLarge", code(ApiException.tooLarge("t")));
        assertEquals("request.throttled", code(ApiException.throttled("t")));
        assertEquals("upstream.failure", code(ApiException.badGateway("g")));
        assertEquals("upstream.failure", code(ApiException.badGateway("g", cause)));
    }

    @Test
    void theFixedTextFactoriesCarryTheirOwnCodes() {
        assertEquals("server.internalError", code(ApiException.internalError()));
        assertEquals("request.notAcceptable", code(ApiException.notAcceptable()));
        assertEquals("request.unsupportedMediaType", code(ApiException.unsupportedMediaType()));
        assertEquals("entry.changed", code(ApiException.entryChanged("h")));
        assertEquals("page.changed", code(ApiException.pageChanged("h")));
    }

    /** RFC 9457's about:blank: the status phrase as the title, no detail, and the code http.error. */
    @Test
    void anHttpErrorIsAboutBlankWithNoDetail() {
        ApiProblem problem = ApiException.httpError(405, "Method Not Allowed").toProblem(null);
        assertEquals("about:blank", problem.type());
        assertEquals("Method Not Allowed", problem.title());
        assertEquals(405, problem.status());
        assertNull(problem.detail());
        assertEquals("http.error", problem.code());
    }

    @Test
    void aFieldErrorCarriesTheCodeOfItsProblemsType() {
        ApiProblem invalid = ApiException.invalidField("url", "bad").toProblem(null);
        assertEquals("request.invalid", invalid.code());
        assertEquals("request.invalid", invalid.errors().get(0).code());
        ApiProblem withCause = ApiException.invalidField("url", "bad", new IllegalStateException()).toProblem(null);
        assertEquals("request.invalid", withCause.errors().get(0).code());
        ApiProblem conflict = ApiException.conflictOnField("anchor", "taken").toProblem(null);
        assertEquals("resource.conflict", conflict.code());
        assertEquals("resource.conflict", conflict.errors().get(0).code());
    }

    @Test
    void aKeyedProblemTakesItsStatusTypeAndTitleFromItsCode() {
        ApiException e = ApiException.problem(ProblemCode.ENTRY_CHANGED);
        assertEquals(409, e.getStatus());
        ApiProblem problem = e.toProblem("/api/v1/x");
        assertEquals("https://pressroom.invalid/problems/entry-changed", problem.type());
        assertEquals("Entry changed", problem.title());
        assertEquals("entry.changed", problem.code());
        assertEquals("The entry's text changed after it was read; get the entry again and redo the change.",
                e.getMessage(), "arity 0 is not run through MessageFormat: the apostrophe stays single");
    }

    /** about:blank has no fixed status, so http.error cannot be raised as a keyed problem. */
    @Test
    void httpErrorIsNotAKeyedProblem() {
        assertThrows(IllegalArgumentException.class, () -> ApiException.problem(ProblemCode.HTTP_ERROR));
    }

    @Test
    void aKeyedFieldProblemNamesItsFieldAndLiftsItsCode() {
        ApiException e = ApiException.problemOnField("limit", ProblemCode.PARAMETER_NOT_WHOLE_NUMBER, "limit");
        ApiProblem problem = e.toProblem(null);
        assertEquals(400, problem.status());
        assertEquals("limit has an invalid value; it must be a whole number.", problem.detail());
        assertEquals("parameter.notWholeNumber", problem.code());
        assertEquals(List.of(new ApiProblem.FieldError("limit",
                        "limit has an invalid value; it must be a whole number.", "parameter.notWholeNumber")),
                problem.errors());
    }

    /** Criterion 5: arguments are strings, so a number is never grouped. */
    @Test
    void aNumberArgumentIsNeverGrouped() {
        assertEquals("The request body is over 1000000 bytes.",
                ApiException.problem(ProblemCode.REQUEST_TOO_LARGE, "1000000").getMessage());
    }

    /**
     * Criterion 5 as the controller ruled it (2026-11-20): a wrong argument
     * count is a display fault, never a decision one. The status is what the
     * caller branches on, so it stands; a WARN names the code; the text is
     * rendered with the arguments there are.
     */
    @Test
    void aWrongArgumentCountKeepsTheStatusAndLogsAWarningNamingTheCode() {
        List<LogEvent> events = captureWarnings(() -> {
            ApiException tooFew = ApiException.problem(ProblemCode.PARAMETER_NOT_WHOLE_NUMBER);
            assertEquals(400, tooFew.getStatus());
            assertEquals("parameter.notWholeNumber", code(tooFew));
            assertEquals("{0} has an invalid value; it must be a whole number.", tooFew.getMessage());

            ApiException tooMany = ApiException.problem(ProblemCode.SERVER_INTERNAL_ERROR, "extra");
            assertEquals(500, tooMany.getStatus());
            assertEquals("The request could not be completed.", tooMany.getMessage());

            ApiException onField = ApiException.problemOnField("limit", ProblemCode.PARAMETER_NOT_NUMBER, "a", "b");
            assertEquals("a has an invalid value; it must be a number.", onField.getMessage());
        });
        assertEquals(3, events.size(), events.toString());
        assertTrue(events.get(0).getMessage().getFormattedMessage().contains("parameter.notWholeNumber"));
        assertTrue(events.get(1).getMessage().getFormattedMessage().contains("server.internalError"));
        assertTrue(events.get(2).getMessage().getFormattedMessage().contains("parameter.notNumber"));
    }

    @Test
    void aRightArgumentCountLogsNothing() {
        assertEquals(List.of(), captureWarnings(() -> ApiException.problem(ProblemCode.REQUEST_TOO_LARGE, "5")));
    }

    @Test
    void severalFieldErrorsGiveInvalidFieldsAndJoinTheirMessages() {
        FieldErrors errors = new FieldErrors();
        errors.add("limit", ProblemCode.PARAMETER_NOT_WHOLE_NUMBER, "limit");
        errors.add("title", "title is required.");

        ApiProblem problem = assertThrows(ApiException.class, errors::throwIfAny).toProblem(null);

        assertEquals("request.invalidFields", problem.code());
        assertEquals("limit has an invalid value; it must be a whole number. title is required.", problem.detail());
        assertEquals("parameter.notWholeNumber", problem.errors().get(0).code());
        assertEquals("request.invalid", problem.errors().get(1).code());
    }

    @Test
    void aSingleKeyedFieldErrorLiftsItsCodeToTheProblem() {
        FieldErrors errors = new FieldErrors();
        errors.add("offset", ProblemCode.PAGING_INVALID_RANGE);

        ApiProblem problem = assertThrows(ApiException.class, errors::throwIfAny).toProblem(null);

        assertEquals("paging.invalidRange", problem.code());
        assertEquals("limit must be at least 1 and offset must not be negative.", problem.detail());
    }

    @Test
    void aKeyedProblemACheckThrowsKeepsItsCodeUnderTheCheckedField() {
        FieldErrors errors = new FieldErrors();
        errors.check("since", () -> {
            throw ApiException.problem(ProblemCode.PARAMETER_INVALID_VALUE, "since");
        });
        errors.value("enabled", () -> {
            throw ApiException.problem(ProblemCode.PARAMETER_NOT_BOOLEAN, "enabled");
        });

        ApiProblem problem = assertThrows(ApiException.class, errors::throwIfAny).toProblem(null);

        assertEquals(List.of(
                new ApiProblem.FieldError("since", "since has an invalid value.", "parameter.invalidValue"),
                new ApiProblem.FieldError("enabled", "enabled has an invalid value; it must be true or false.",
                        "parameter.notBoolean")), problem.errors());
    }

    @Test
    void addIfAbsentTakesAKeyedProblemToo() {
        FieldErrors errors = new FieldErrors();
        errors.add("limit", "limit is bad.");
        errors.addIfAbsent("limit", ProblemCode.PAGING_INVALID_RANGE);
        errors.addIfAbsent("offset", ProblemCode.PAGING_INVALID_RANGE);

        List<ApiProblem.FieldError> found = assertThrows(ApiException.class, errors::throwIfAny).getFieldErrors();

        assertEquals(List.of("limit", "offset"), found.stream().map(ApiProblem.FieldError::field).toList());
        assertEquals("paging.invalidRange", found.get(1).code());
    }

    /** A validation 400 listing several fields keeps its literal detail and gives request.invalidFields. */
    @Test
    void aValidationProblemWithSeveralFieldsIsInvalidFields() {
        ApiProblem problem = ApiException.validation("a b", List.of(
                new ApiProblem.FieldError("a", "a"), new ApiProblem.FieldError("b", "b"))).toProblem(null);
        assertEquals("request.invalidFields", problem.code());
        assertEquals("a b", problem.detail());
        ApiProblem one = ApiException.validation("a", List.of(new ApiProblem.FieldError("a", "a"))).toProblem(null);
        assertEquals("request.invalid", one.code());
        ApiProblem none = ApiException.validation("x", List.of()).toProblem(null);
        assertEquals("request.invalid", none.code());
        assertEquals(List.of(), none.errors());
    }

    /** {@code code} is the last member, so every earlier one serialises exactly as before. */
    @Test
    void codeIsTheLastMemberOfTheProblemAndOfEachFieldError() {
        JsonNode problem = JSON.readTree(JSON.writeValueAsString(
                ApiException.problemOnField("limit", ProblemCode.PAGING_INVALID_RANGE).toProblem("/api/v1/x")));
        assertEquals(List.of("type", "title", "status", "detail", "instance", "errors", "code"), names(problem));
        assertEquals(List.of("field", "message", "code"), names(problem.get("errors").get(0)));

        JsonNode changed = JSON.readTree(JSON.writeValueAsString(ApiException.entryChanged("h").toProblem("/i")));
        assertEquals(List.of("type", "title", "status", "detail", "instance", "currentContentHash", "code"),
                names(changed));
    }

    private static List<String> names(JsonNode node) {
        List<String> names = new ArrayList<>();
        Iterator<String> it = node.propertyNames().iterator();
        it.forEachRemaining(names::add);
        return names;
    }

    private static List<LogEvent> captureWarnings(Runnable action) {
        Logger logger = (Logger) LogManager.getLogger(ApiException.class);
        List<LogEvent> events = new CopyOnWriteArrayList<>();
        AbstractAppender appender = new AbstractAppender("problem-arity", null, null, true, Property.EMPTY_ARRAY) {
            @Override
            public void append(LogEvent event) {
                if (event.getLevel() == Level.WARN) {
                    events.add(event.toImmutable());
                }
            }
        };
        appender.start();
        Level before = logger.getLevel();
        logger.addAppender(appender);
        logger.setLevel(Level.WARN);
        try {
            action.run();
        } finally {
            logger.removeAppender(appender);
            logger.setLevel(before);
            appender.stop();
        }
        return events;
    }
}
