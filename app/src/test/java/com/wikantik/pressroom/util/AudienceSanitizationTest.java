package com.wikantik.pressroom.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AudienceSanitizationTest {

    @Test
    void thePlaceholderDataAttributesSurvive() {
        String clean = HTMLSanitizer.conditionallySanitize(
                "<div class=\"contact-form-slot\" data-weblog=\"myblog\" "
                + "data-endpoint=\"/roller/roller-ui/rendering/contact.rol\"></div>"
                + "<div class=\"subscribe-form-slot\" data-list-uuid=\"2f0f1b0c-1111-2222-3333-444455556666\" "
                + "data-endpoint=\"/roller/newsletter/subscribe\"></div>");

        assertTrue(clean.contains("data-weblog=\"myblog\""), clean);
        assertTrue(clean.contains("data-endpoint=\"/roller/roller-ui/rendering/contact.rol\""), clean);
        assertTrue(clean.contains("data-list-uuid="), clean);
        assertTrue(clean.contains("data-endpoint=\"/roller/newsletter/subscribe\""), clean);
    }

    @Test
    void formsAreStillStrippedFromAuthoredContent() {
        String clean = HTMLSanitizer.conditionallySanitize(
                "<form action=\"https://evil.example\"><input name=\"password\"></form>");

        assertFalse(clean.contains("<form"), "authored forms are a phishing kit: " + clean);
        assertFalse(clean.contains("<input"), clean);
    }

    @Test
    void theAttributesAreNotGrantedGlobally() {
        String clean = HTMLSanitizer.conditionallySanitize(
                "<a data-weblog=\"x\" data-endpoint=\"/x\">link</a>");

        assertFalse(clean.contains("data-weblog"), clean);
        assertFalse(clean.contains("data-endpoint"), clean);
    }
}
