package com.wikantik.pressroom.ui.restapi.dto;

import java.util.List;
import java.util.Set;
import com.wikantik.pressroom.ui.restapi.ApiException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ClearPolicyTest {

    record Body(String a, String b, List<String> clear) { }

    static final class Target {
        String a = "x";
        String b = "y";
    }

    private final ClearPolicy<Target> policy = ClearPolicy.<Target>builder()
            .field("b", t -> t.b = null)
            .field("a", t -> t.a = null)
            .pair("a", "b")
            .build();

    @Test
    void namesKeepDeclarationOrderAndTheMessageUsesIt() {
        assertEquals(List.of("b", "a"), policy.names());
        ApiException e = assertThrows(ApiException.class,
                () -> policy.validate(List.of("zzz"), new Body(null, null, List.of("zzz"))));
        assertTrue(e.getMessage().endsWith("Clearable: b, a."), e.getMessage());
    }

    @Test
    void validateEnforcesPairs() {
        assertThrows(ApiException.class, () -> policy.validate(List.of("a"), new Body(null, null, List.of("a"))));
        assertEquals(Set.of("a", "b"),
                policy.validate(List.of("a", "b"), new Body(null, null, List.of("a", "b"))));
    }

    @Test
    void applyClearsEachNamedFieldAndRefusesAnUndeclaredOne() {
        Target t = new Target();
        policy.apply(t, Set.of("a"));
        assertNull(t.a);
        assertEquals("y", t.b);
        assertThrows(IllegalArgumentException.class, () -> policy.apply(t, Set.of("c")));
    }
}
