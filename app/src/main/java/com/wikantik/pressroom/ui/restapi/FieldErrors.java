package com.wikantik.pressroom.ui.restapi;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

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

    private final List<ApiException.FieldProblem> errors = new ArrayList<>();

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
            record(field, e);
        }
    }

    /** As above, for a check whose 400s name their own fields. */
    public <E extends Exception> void check(Check<E> check) throws E {
        check(null, check);
    }

    /**
     * As {@link #check(String, Check)} for a check that yields a value: the
     * value when it passes, empty (and the problem recorded) when it does not.
     */
    public <T> Optional<T> value(String field, Supplier<T> check) {
        try {
            return Optional.ofNullable(check.get());
        } catch (ApiException e) {
            if (e.getStatus() != 400) {
                throw e;
            }
            record(field, e);
            return Optional.empty();
        }
    }

    /** Keeps the problems a 400 names, or the 400 itself against {@code field} when it names none. */
    private void record(String field, ApiException e) {
        List<ApiException.FieldProblem> named = e.fieldProblems();
        errors.addAll(named.isEmpty() ? List.of(e.asFieldProblem(field)) : named);
    }

    /** An unkeyed problem: a literal message, under the generic {@code request.invalid} code. */
    public void add(String field, String message) {
        errors.add(ApiException.FieldProblem.literal(field, ProblemCode.REQUEST_INVALID, message));
    }

    /** A keyed problem: {@code code}'s text with {@code args}, rendered in the caller's language. */
    public void add(String field, ProblemCode code, String... args) {
        errors.add(ApiException.FieldProblem.keyed(field, code, args));
    }

    /**
     * As {@link #add}, unless {@code field} already has a problem: the first
     * wins, so a rule layered over a field's own check (the focal-point pair
     * rule over the range check) never lists the field twice. {@link #add}
     * itself repeats a field on purpose (one error per bad id in a list).
     */
    public void addIfAbsent(String field, String message) {
        if (absent(field)) {
            add(field, message);
        }
    }

    /** As {@link #addIfAbsent(String, String)}, for a keyed problem. */
    public void addIfAbsent(String field, ProblemCode code, String... args) {
        if (absent(field)) {
            add(field, code, args);
        }
    }

    private boolean absent(String field) {
        return errors.stream().noneMatch(known -> field.equals(known.field()));
    }

    public boolean isEmpty() {
        return errors.isEmpty();
    }

    /**
     * One 400 carrying every recorded problem, its detail their messages
     * joined by one space, each rendered in the caller's language; nothing
     * when there are none. Its code is the one problem's code, or {@code
     * request.invalidFields} for several.
     */
    public void throwIfAny() {
        if (!errors.isEmpty()) {
            throw ApiException.fieldsProblem(errors);
        }
    }
}
