package com.wikantik.pressroom.ui.restapi.dto;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

/**
 * The dashboard link behind "Open in Umami" (it was the JSP view model's, tested there until the Traffic
 * page moved to React): offered only for a registered weblog and an http(s) dashboard, never a script link.
 */
class TrafficDtosTest {

    @Test
    void theDashboardLinkNeedsAnHttpBaseAndAWebsite() {
        assertNull(TrafficDtos.umamiUrl(null, "w1"));
        assertNull(TrafficDtos.umamiUrl("https://stats.example.com", null));
        assertNull(TrafficDtos.umamiUrl("https://stats.example.com", " "));
        assertNull(TrafficDtos.umamiUrl("javascript:alert(1)//", "w1"), "never a script link");
        assertNull(TrafficDtos.umamiUrl("ftp://stats.example.com", "w1"));
        assertEquals("HTTP://stats.example.com/websites/w1", TrafficDtos.umamiUrl(" HTTP://stats.example.com// ", "w1"),
                "the scheme is matched in any case, the base trimmed of space and trailing slashes");
    }

    @Test
    void theWebsiteIdIsOneEncodedSegment() {
        assertEquals("https://stats.example.com/websites/a%20b%2Fc",
                TrafficDtos.umamiUrl("https://stats.example.com", "a b/c"));
    }

    @Test
    void theOfferedPeriodsAreTheFourTheSwitchShows() {
        assertEquals(java.util.List.of("24h", "7d", "30d", "90d"), TrafficDtos.PERIODS);
    }
}
