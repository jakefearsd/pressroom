package com.wikantik.pressroom.ui.controllers.admin;

import com.wikantik.pressroom.business.Colophon;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/** The shell's JSON block sits inside a script element, so nothing a user typed may end it. */
class AppBootstrapTest {

    @Test
    void aScreenNameCannotBreakOutOfTheScriptElement() throws Exception {
        String hostile = "</script><b>x</b> & <!--   ";
        AppBootstrap.Site site = new AppBootstrap.Site(hostile, "Short", "site-space");
        AppBootstrap bootstrap = new AppBootstrap("/pressroom", "1.2.3", "X-CSRF-TOKEN", "tok", 1800,
                "jake", hostile, "en", "admin", "en", "/pressroom/pressroom-ui/app-messages.rol?locale=en&v=1.2.3",
                site, new Colophon("1.2.3", "abc1234", "2026-11-20", null, hostile, 2026, "https://x.test/privacy"));

        String json = bootstrap.toJson();

        assertFalse(json.contains("<"), json);
        assertFalse(json.contains(">"), json);
        assertFalse(json.contains("&"), json);
        assertFalse(json.contains(" ") || json.contains(" "), json);
        JsonNode parsed = new ObjectMapper().readTree(json);
        assertEquals(hostile, parsed.get("user").get("screenName").asString());
        assertEquals("/pressroom", parsed.get("contextPath").asString());
        assertEquals("1.2.3", parsed.get("version").asString());
        assertEquals("X-CSRF-TOKEN", parsed.get("csrf").get("headerName").asString());
        assertEquals("tok", parsed.get("csrf").get("token").asString());
        assertEquals(1800, parsed.get("sessionTimeoutSeconds").asInt());
        assertEquals("admin", parsed.get("user").get("globalPermission").asString());
        assertEquals("jake", parsed.get("user").get("userName").asString());
        assertEquals("en", parsed.get("user").get("locale").asString());
        assertEquals("en", parsed.get("uiLocale").asString());
        assertEquals("/pressroom/pressroom-ui/app-messages.rol?locale=en&v=1.2.3",
                parsed.get("messagesUrl").asString());
        assertEquals(hostile, parsed.get("site").get("name").asString());
        assertEquals("Short", parsed.get("site").get("shortName").asString());
        assertEquals("site-space", parsed.get("site").get("siteSpaceHandle").asString());
        JsonNode colophon = parsed.get("colophon");
        assertEquals(hostile, colophon.get("copyrightHolder").asString());
        assertEquals("2026", colophon.get("copyrightYear").asString());
        assertEquals("https://x.test/privacy", colophon.get("privacyUrl").asString());
        assertEquals("abc1234", colophon.get("shortRevision").asString());
        assertEquals("2026-11-20", colophon.get("releaseDate").asString());
    }

    @Test
    void aColophonFactThatIsNotKnownIsLeftOutNotWrittenEmpty() throws Exception {
        AppBootstrap bootstrap = new AppBootstrap("", "1", "H", "t", 0, "u", "U", "en", "editor", "en", "/m",
                new AppBootstrap.Site("Pressroom", "Pressroom", "site-space"),
                new Colophon("", "", null, null, "", 2026, null));

        JsonNode colophon = new ObjectMapper().readTree(bootstrap.toJson()).get("colophon");

        assertEquals(java.util.List.of("copyrightYear"), colophon.propertyNames().stream().toList());
    }
}
