package com.wikantik.pressroom.ui.restapi;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * The problems of several independent checks, reported together: a write
 * with three bad fields answers one 400 naming all three, not three round
 * trips. Only a 400 {@link ApiException} is collected. A 404, a 409 or an
 * unexpected failure is not a field problem, and propagates at once.
 */
public final class FieldErrors {

    /** One check. It reports a problem by throwing a 400 {@link ApiException}. */
    @FunctionalInterface
    public interface Check<E extends Exception> {
        void run() throws E;
    }

    private final List<ApiProblem.FieldError> errors = new ArrayList<>();

    /**
     * Runs {@code check} and records a 400 it throws. Errors the exception
     * names are kept as they are; one naming none is recorded against
     * {@code field}, so a check deep in a parser needs no field of its own.
     */
    public <E extends Exception> void check(String field, Check<E> check) throws E {
        try {
            check.run();
        } catch (ApiException e) {
            if (e.getStatus() != 400) {
                throw e;
            }
            List<ApiProblem.FieldError> named = e.getFieldErrors();
            errors.addAll(named.isEmpty() ? List.of(new ApiProblem.FieldError(field, e.getMessage())) : named);
        }
    }

    /** As above, for a check whose 400s name their own fields. */
    public <E extends Exception> void check(Check<E> check) throws E {
        check(null, check);
    }

    public void add(String field, String message) {
        errors.add(new ApiProblem.FieldError(field, message));
    }

    public boolean isEmpty() {
        return errors.isEmpty();
    }

    /** One 400 carrying every recorded problem, its detail their messages joined; nothing when there are none. */
    public void throwIfAny() {
        if (!errors.isEmpty()) {
            throw ApiException.validation(
                    errors.stream().map(ApiProblem.FieldError::message).collect(Collectors.joining(" ")),
                    List.copyOf(errors));
        }
    }
}
