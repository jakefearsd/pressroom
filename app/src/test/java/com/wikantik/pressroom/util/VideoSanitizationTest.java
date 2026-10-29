package com.wikantik.pressroom.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The sanitizer must keep [video]'s data attributes and must still refuse
 * iframes. Both halves matter: without the grant the placeholder arrives
 * stripped of the only information the macro needs, and if iframes were ever
 * allowed the click-to-play facade would stop being a boundary at all.
 */
class VideoSanitizationTest {

    @Test
    void theVideoPlaceholdersDataAttributesSurvive() {
        String clean = HTMLSanitizer.conditionallySanitize(
                "<div class=\"video-embed\" data-provider=\"youtube\" "
                        + "data-video-id=\"dQw4w9WgXcQ\"></div>");

        assertTrue(clean.contains("data-provider=\"youtube\""), clean);
        assertTrue(clean.contains("data-video-id=\"dQw4w9WgXcQ\""), clean);
    }

    @Test
    void anIframeIsStillStripped() {
        String clean = HTMLSanitizer.conditionallySanitize(
                "<iframe src=\"https://evil.example/x\"></iframe>");

        assertFalse(clean.contains("<iframe"),
                "the facade is only a boundary while this holds: " + clean);
    }

    @Test
    void theseAttributesAreNotGrantedGlobally() {
        String clean = HTMLSanitizer.conditionallySanitize(
                "<a data-video-id=\"x\">link</a>");

        assertFalse(clean.contains("data-video-id"),
                "the grant is scoped to div, like data-pins: " + clean);
    }
}
