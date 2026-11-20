package com.wikantik.pressroom.ui.restapi;

import java.io.InputStream;
import java.util.Properties;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Every write path's transport limit is derived from the entry column cap,
 * so the longest entry the cap accepts, in 3-byte characters, is never
 * refused for size first (docs/superpowers/specs/2026-11-20-api-repairs-design.md).
 */
class EntryWriteLimitsTest {

    @Test
    void theApiBodyLimitIsThreeBytesACharacterPlusHeadroom() {
        assertEquals(3 * ColumnLimits.ENTRY_TEXT + ApiBodyLimitFilter.HEADROOM_BYTES,
                ApiBodyLimitFilter.MAX_BODY_BYTES);
    }

    /**
     * The JSP entry editor posts urlencoded (its form has no enctype), where
     * a 3-byte character is nine bytes of percent-escapes; Tomcat's form-post
     * limit, which Boot defaults to 2 MB, must carry the longest entry.
     */
    @Test
    void theFormPostLimitCarriesAMaximumLengthEntryUrlEncoded() throws Exception {
        Properties properties = new Properties();
        try (InputStream in = getClass().getResourceAsStream("/application.properties")) {
            assertNotNull(in);
            properties.load(in);
        }

        long expected = 9L * ColumnLimits.ENTRY_TEXT + ApiBodyLimitFilter.HEADROOM_BYTES;
        assertEquals(expected + "B", properties.getProperty("server.tomcat.max-http-form-post-size"));
    }
}
