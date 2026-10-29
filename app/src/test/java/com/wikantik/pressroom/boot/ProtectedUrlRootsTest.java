package com.wikantik.pressroom.boot;

import com.wikantik.pressroom.config.WebloggerConfig;
import org.junit.jupiter.api.Test;
import java.util.Arrays;
import java.util.List;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * WeblogRequestMapper forwards any single-segment path to the weblog
 * renderer, so an application path root that is not in this list can be
 * shadowed by a weblog whose handle matches it.
 *
 * `newsletter` is here because ServletRegistrationConfig's javadoc has
 * always claimed it was reserved while it actually was not -- a weblog with
 * the handle `newsletter` would have shadowed the subscribe endpoint.
 */
class ProtectedUrlRootsTest {

    private static List<String> roots() {
        return Arrays.asList(WebloggerConfig
                .getProperty("rendering.weblogMapper.rollerProtectedUrls").split(","));
    }

    @Test
    void apiRootIsReserved() {
        assertTrue(roots().contains("api"),
                "a weblog handled 'api' would shadow the entire automation API");
    }

    @Test
    void newsletterRootIsReserved() {
        assertTrue(roots().contains("newsletter"),
                "a weblog handled 'newsletter' would shadow /newsletter/subscribe");
    }
}
