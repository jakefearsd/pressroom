package com.wikantik.pressroom.ui.controllers;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import com.wikantik.pressroom.ui.restapi.ProblemCode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** {@link RuleViolation}: the shape every rule class returns. */
class RuleViolationTest {

    @Test
    void theFactoriesSayWhichAnswerTheApiGives() {
        RuleViolation invalid = RuleViolation.invalid("f", "k", List.of("a"), ProblemCode.FIELD_REQUIRED, "f");
        RuleViolation conflict = RuleViolation.conflict("f", "k", List.of(), ProblemCode.HANDLE_IN_USE);

        assertEquals(RuleViolation.Kind.INVALID, invalid.kind());
        assertEquals(List.of("f"), invalid.codeArgs());
        assertEquals(RuleViolation.Kind.CONFLICT, conflict.kind());
        assertEquals(List.of(), conflict.codeArgs());
    }

    @Test
    void theArgumentsAreCopiedAndMayHoldANullRawValue() {
        List<Object> args = new ArrayList<>(Arrays.asList("typed", null));
        RuleViolation violation = RuleViolation.invalid("f", "k", args, ProblemCode.FIELD_REQUIRED, "f");
        args.clear();

        assertEquals(Arrays.asList("typed", null), violation.args());
        assertThrows(UnsupportedOperationException.class, () -> violation.args().add("x"));
    }

    @Test
    void everyPartButTheArgumentsIsRequired() {
        assertThrows(NullPointerException.class,
                () -> RuleViolation.invalid(null, "k", List.of(), ProblemCode.FIELD_REQUIRED));
        assertThrows(NullPointerException.class,
                () -> RuleViolation.invalid("f", null, List.of(), ProblemCode.FIELD_REQUIRED));
        assertThrows(NullPointerException.class, () -> RuleViolation.invalid("f", "k", List.of(), null));
        assertThrows(NullPointerException.class,
                () -> new RuleViolation("f", "k", List.of(), ProblemCode.FIELD_REQUIRED, List.of(), null));
    }
}
