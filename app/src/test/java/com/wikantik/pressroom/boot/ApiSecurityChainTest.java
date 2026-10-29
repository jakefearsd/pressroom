package com.wikantik.pressroom.boot;

import java.lang.reflect.Method;
import org.junit.jupiter.api.Test;
import org.springframework.core.annotation.Order;
import static org.junit.jupiter.api.Assertions.*;

/**
 * The API gets its own SecurityFilterChain rather than new rules inside the
 * UI's. The UI chain declares no securityMatcher, so it matches everything
 * and MUST be ordered after the API chain or it would swallow /api/**.
 */
class ApiSecurityChainTest {

    private static Method chain(String name) throws Exception {
        for (Method m : SecurityConfig.class.getDeclaredMethods()) {
            if (m.getName().equals(name)) {
                return m;
            }
        }
        throw new AssertionError("no method named " + name);
    }

    @Test
    void theApiChainIsOrderedAheadOfTheUiChain() throws Exception {
        Order api = chain("apiSecurityFilterChain").getAnnotation(Order.class);
        Order ui = chain("securityFilterChain").getAnnotation(Order.class);
        assertNotNull(api, "the API chain must declare an explicit order");
        assertNotNull(ui, "the UI chain must declare an explicit order once a second chain exists");
        assertTrue(api.value() < ui.value(),
                "the catch-all UI chain would otherwise match /api/** first");
    }
}
