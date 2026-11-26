package com.wikantik.pressroom.ui.controllers.admin;

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
        AppBootstrap bootstrap = new AppBootstrap("/pressroom", "1.2.3", "X-CSRF-TOKEN", "tok",
                "jake", hostile, "en", "admin", "en", "/pressroom/pressroom-ui/app-messages.rol?locale=en&v=1.2.3");

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
        assertEquals("admin", parsed.get("user").get("globalPermission").asString());
        assertEquals("jake", parsed.get("user").get("userName").asString());
        assertEquals("en", parsed.get("user").get("locale").asString());
        assertEquals("en", parsed.get("uiLocale").asString());
        assertEquals("/pressroom/pressroom-ui/app-messages.rol?locale=en&v=1.2.3",
                parsed.get("messagesUrl").asString());
    }
}
