package com.wikantik.pressroom.ui.controllers.admin;

import java.util.Locale;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The resolved ApplicationResources bundle as JSON, for the React admin's {@code t()}. */
class AppMessagesControllerTest {

    private final AppMessagesController controller = new AppMessagesController();

    @Test
    void theGermanBundleIsMergedOverTheBase() {
        Map<String, String> base = controller.messages(Locale.ROOT).getBody();
        ResponseEntity<Map<String, String>> response = controller.messagesFor("de", "1.2.3");
        Map<String, String> german = response.getBody();

        assertEquals(base.keySet(), german.keySet(), "every base key, whatever the locale overrides");
        long overridden = base.keySet().stream().filter(k -> !base.get(k).equals(german.get(k))).count();
        assertTrue(overridden > 0, "de overrides some of the base");
        assertEquals("application/json;charset=UTF-8", response.getHeaders().getContentType().toString());
    }

    @Test
    void anUnknownLocaleIsTheBase() {
        assertEquals(controller.messages(Locale.ROOT).getBody(), controller.messagesFor("xx", "1").getBody());
        assertEquals(controller.messages(Locale.ROOT).getBody(), controller.messagesFor("", "1").getBody());
    }

    @Test
    void theUrlCarriesTheVersionSoTheAnswerIsCachedForAYear() {
        ResponseEntity<Map<String, String>> response = controller.messagesFor("de", "9.9.9");

        String cache = response.getHeaders().getCacheControl();
        assertEquals(CacheControl.maxAge(java.time.Duration.ofDays(365)).cachePublic().getHeaderValue(), cache);
        assertNotEquals(null, cache);
    }
}
