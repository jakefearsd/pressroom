package com.wikantik.pressroom.business;

import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** {@link RedirectRefusedException} carries its reason and arguments, whatever a caller passes for them. */
class RedirectRefusedExceptionTest {

    @Test
    void argumentsAreCopiedAndReadOnly() {
        String[] args = {"source"};
        RedirectRefusedException refusal = new RedirectRefusedException("m",
                RedirectRefusedException.Reason.PATH_REQUIRED, args);
        args[0] = "changed";

        assertEquals(List.of("source"), refusal.args());
        assertEquals(RedirectRefusedException.Reason.PATH_REQUIRED, refusal.reason());
        assertThrows(UnsupportedOperationException.class, () -> refusal.args().add("x"));
    }

    /** A null varargs array is no arguments, not a NullPointerException thrown in place of the refusal. */
    @Test
    void aNullArgumentArrayIsNoArguments() {
        RedirectRefusedException refusal = new RedirectRefusedException("m",
                RedirectRefusedException.Reason.WEBLOG_REQUIRED, (String[]) null);

        assertEquals(List.of(), refusal.args());
    }

    @Test
    void aNullArgumentIsKept() {
        RedirectRefusedException refusal = new RedirectRefusedException("m",
                RedirectRefusedException.Reason.PATH_REQUIRED, "a", null);

        assertEquals(Arrays.asList("a", null), refusal.args());
    }
}
