package com.wikantik.pressroom.ui.controllers.admin;

import jakarta.servlet.http.HttpServletRequest;

import com.wikantik.pressroom.business.MockWeblogger;
import com.wikantik.pressroom.pojos.GlobalPermission;
import com.wikantik.pressroom.pojos.User;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.csrf.DefaultCsrfToken;
import org.springframework.ui.ExtendedModelMap;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/** The React admin's shell: who is signed in, the CSRF token, and every URL from the context path. */
class AppShellControllerTest {

    private MockWeblogger weblogger;
    private AppShellController controller;
    private User jake;

    @BeforeEach
    void setUp() {
        weblogger = MockWeblogger.attached();
        ControllerTestFixture.useWeblogger(weblogger.weblogger());
        controller = ControllerTestFixture.withMessages(new AppShellController());
        jake = new User();
        jake.setUserName("jake");
        // User.setScreenName strips markup itself; AppBootstrapTest feeds the raw hostile string to the escaper.
        jake.setScreenName("Jake & <Co>");
        jake.setLocale("de");
    }

    @AfterEach
    void tearDown() {
        weblogger.detach();
        ControllerTestFixture.useDefaultWeblogger();
    }

    private JsonNode bootstrapFor(HttpServletRequest request) throws Exception {
        ExtendedModelMap model = new ExtendedModelMap();
        assertEquals("app/Shell", controller.shell(request, model));
        String json = (String) model.getAttribute("bootstrapJson");
        assertFalse(json.contains("<"), "no markup in a script block: " + json);
        return new ObjectMapper().readTree(json);
    }

    private static HttpServletRequest requestUnder(String contextPath, User user, String token) {
        HttpServletRequest request = ControllerTestFixture.requestFor(user);
        when(request.getContextPath()).thenReturn(contextPath);
        when(request.getAttribute(CsrfToken.class.getName()))
                .thenReturn(token == null ? null : new DefaultCsrfToken("X-CSRF-TOKEN", "_csrf", token));
        return request;
    }

    @Test
    void anEditorGetsTheirFactsTheTokenAndUrlsUnderTheContextPath() throws Exception {
        when(weblogger.userManager().checkPermission(any(GlobalPermission.class), any(User.class))).thenReturn(false);

        JsonNode boot = bootstrapFor(requestUnder("/pressroom", jake, "tok-1"));

        assertEquals("/pressroom", boot.get("contextPath").asString());
        assertEquals("X-CSRF-TOKEN", boot.get("csrf").get("headerName").asString());
        assertEquals("tok-1", boot.get("csrf").get("token").asString());
        assertEquals("jake", boot.get("user").get("userName").asString());
        assertEquals(jake.getScreenName(), boot.get("user").get("screenName").asString());
        assertEquals("de", boot.get("user").get("locale").asString());
        assertEquals("editor", boot.get("user").get("globalPermission").asString());
        assertEquals("en", boot.get("uiLocale").asString());
        String version = boot.get("version").asString();
        assertEquals("/pressroom/pressroom-ui/app-messages.rol?locale=en&v=" + version,
                boot.get("messagesUrl").asString());
    }

    @Test
    void theSiteNamesTheColophonFactsAndTheSiteSpaceRideAlong() throws Exception {
        when(weblogger.userManager().checkPermission(any(GlobalPermission.class), any(User.class))).thenReturn(false);

        JsonNode boot = bootstrapFor(requestUnder("/pressroom", jake, "tok-1"));

        assertEquals(com.wikantik.pressroom.pojos.Weblog.SITE_SPACE_HANDLE,
                boot.get("site").get("siteSpaceHandle").asString());
        assertFalse(boot.get("site").get("name").asString().isBlank());
        assertFalse(boot.get("site").get("shortName").asString().isBlank());
        assertTrue(boot.get("colophon").get("copyrightYear").asString().matches("\\d{4}"));
    }

    @Test
    void anAdminIsAnAdminAndTheRootContextHasNoPrefix() throws Exception {
        when(weblogger.userManager().checkPermission(any(GlobalPermission.class), any(User.class))).thenReturn(true);

        JsonNode boot = bootstrapFor(requestUnder("", jake, "tok-2"));

        assertEquals("admin", boot.get("user").get("globalPermission").asString());
        assertEquals("", boot.get("contextPath").asString());
        assertTrue(boot.get("messagesUrl").asString().startsWith("/pressroom-ui/app-messages.rol?locale=en&v="),
                boot.get("messagesUrl").asString());
    }

    @Test
    void withoutACsrfTokenOrLocaleTheBlockIsStillWellFormed() throws Exception {
        jake.setLocale(null);
        when(weblogger.userManager().checkPermission(any(GlobalPermission.class), any(User.class))).thenReturn(false);

        JsonNode boot = bootstrapFor(requestUnder("", jake, null));

        assertEquals("", boot.get("csrf").get("token").asString());
        assertEquals("", boot.get("user").get("locale").asString());
    }

    @Test
    void itIsAMenuLevelScreenForAnySignedInAuthor() {
        assertFalse(controller.isWeblogRequired());
        assertEquals(java.util.List.of(GlobalPermission.LOGIN), controller.requiredGlobalPermissionActions());
    }
}
