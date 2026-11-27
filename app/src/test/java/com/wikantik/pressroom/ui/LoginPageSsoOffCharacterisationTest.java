package com.wikantik.pressroom.ui;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * <b>Characterisation test</b>: written before the 0.18.0 reader-first sign-in
 * page (Part D) and passing against the 0.17.1 {@code Login.jsp} unchanged.
 * With reader sign-in off the page must render exactly as it did then: the
 * same form, the same ids and names in the same order, the same message codes
 * and the same script hooks. The browser suite runs with SSO on throughout
 * (its Keycloak realm), so this is the SSO-off half's only guard;
 * {@code docs/dev/browser-its.md} says why.
 *
 * <p>Read as source through {@link LoginJsp}, which keeps or drops each
 * {@code ssoEnabled} block as an SSO-off render would.
 */
class LoginPageSsoOffCharacterisationTest {

    private static final Path LOGIN_TILE = Path.of("src/main/webapp/WEB-INF/jsps/tiles/tiles-loginpage.jsp");

    @Test
    void withSsoOffTheFormIdsAndOrderAreThoseOf0171() {
        assertEquals(List.of(
                "<form", "id=loginForm",
                "<h2", "code=loginPage.prompt",
                "<label", "for=j_username", "code=loginPage.userName",
                "<input", "name=j_username", "id=j_username",
                "<label", "for=j_password", "code=loginPage.password",
                "<input", "name=j_password", "id=j_password",
                "<input", "name=_spring_security_remember_me", "id=_spring_security_remember_me",
                "<label", "for=_spring_security_remember_me", "code=loginPage.rememberMe",
                "<input", "name=return",
                "<button", "name=login", "id=login", "code=loginPage.login",
                "<a", "code=loginPage.forgotPassword",
                "getElementById=j_username", "getElementById=j_password", "getElementById=j_username",
                "getElementById=loginForm"),
                LoginJsp.tokens(LoginJsp.rendered(false)));
    }

    @Test
    void withSsoOffNoReaderCopyButtonOrStorageFlagRenders() {
        String page = LoginJsp.rendered(false);
        for (String absent : List.of("ssoSignIn", "authorSignIn", "<details", "login.reader.", "localStorage",
                "data-umami-event", "ssoMark")) {
            assertFalse(page.contains(absent), absent + " renders with SSO off:\n" + page);
        }
    }

    /** The page heading comes from the controller's title; SSO off, it has no argument to substitute. */
    @Test
    void theHeadingIsThePageTitleMessage() throws IOException {
        String tile = Files.readString(LOGIN_TILE, StandardCharsets.UTF_8);
        assertTrue(tile.contains("<h1 class=\"pressroom-page-title\"><spring:message code=\"${pageTitle}\""), tile);
    }
}
