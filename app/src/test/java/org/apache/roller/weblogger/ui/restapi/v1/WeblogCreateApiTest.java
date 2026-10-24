package org.apache.roller.weblogger.ui.restapi.v1;

import java.util.List;
import org.apache.roller.weblogger.business.UserManager;
import org.apache.roller.weblogger.business.WeblogManager;
import org.apache.roller.weblogger.business.Weblogger;
import org.apache.roller.weblogger.business.themes.SharedTheme;
import org.apache.roller.weblogger.business.themes.ThemeManager;
import org.apache.roller.weblogger.config.WebloggerConfig;
import org.apache.roller.weblogger.config.WebloggerRuntimeConfig;
import org.apache.roller.weblogger.pojos.User;
import org.apache.roller.weblogger.pojos.Weblog;
import org.apache.roller.weblogger.ui.restapi.ApiExceptionHandler;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.apache.roller.weblogger.ui.restapi.ErrorFieldMatchers.errorFieldIs;
import static org.apache.roller.weblogger.ui.restapi.ErrorFieldMatchers.errorFieldsAre;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Standalone tests for {@link WeblogCreateApi}: the rules themselves are WeblogCreationRules'. */
class WeblogCreateApiTest {

    private final WeblogManager weblogManager = mock(WeblogManager.class);
    private final Weblogger weblogger = mock(Weblogger.class);

    WeblogCreateApiTest() {
        ThemeManager themes = mock(ThemeManager.class);
        SharedTheme journal = theme("journal");
        SharedTheme frontpage = theme("frontpage");
        when(themes.getEnabledThemesList()).thenReturn(List.of(journal, frontpage));
        when(weblogger.getThemeManager()).thenReturn(themes);
        when(weblogger.getWeblogManager()).thenReturn(weblogManager);
        when(weblogger.getUserManager()).thenReturn(mock(UserManager.class));
    }

    private static SharedTheme theme(String id) {
        SharedTheme theme = mock(SharedTheme.class);
        when(theme.getId()).thenReturn(id);
        return theme;
    }

    private static User jake() {
        User user = new User();
        user.setUserName("jake");
        user.setEmailAddress("jake@example.com");
        user.setLocale("en_US");
        user.setTimeZone("America/New_York");
        return user;
    }

    private ResultActions send(String json, boolean creationAllowed) throws Exception {
        return send(json, creationAllowed, jake(), "admin@example.com");
    }

    private ResultActions send(String json, boolean creationAllowed, User creator, String siteEmail)
            throws Exception {
        WeblogCreateApi controller = new WeblogCreateApi();
        controller.weblogger = weblogger;
        MockMvc mvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new ApiExceptionHandler()).build();
        try (MockedStatic<WebloggerRuntimeConfig> runtime = mockStatic(WebloggerRuntimeConfig.class);
             MockedStatic<WebloggerConfig> config = mockStatic(WebloggerConfig.class)) {
            runtime.when(() -> WebloggerRuntimeConfig.getBooleanProperty("site.allowUserWeblogCreation"))
                    .thenReturn(creationAllowed);
            runtime.when(() -> WebloggerRuntimeConfig.getBooleanProperty("groupblogging.enabled"))
                    .thenReturn(true);
            runtime.when(() -> WebloggerRuntimeConfig.getProperty("site.adminemail")).thenReturn(siteEmail);
            config.when(() -> WebloggerConfig.getProperty("username.allowedChars")).thenReturn("A-Za-z0-9");
            config.when(() -> WebloggerConfig.getProperty("rendering.weblogMapper.rollerProtectedUrls"))
                    .thenReturn("api,page,search");
            config.when(() -> WebloggerConfig.getProperty("rendering.weblogMapper.userProtectedUrls"))
                    .thenReturn("");
            return mvc.perform(post("/v1/weblogs").requestAttr("authenticatedUser", creator)
                    .contentType(MediaType.APPLICATION_JSON).content(json));
        }
    }

    private String detailOf(ResultActions result) throws Exception {
        String body = result.andReturn().getResponse().getContentAsString();
        return new tools.jackson.databind.ObjectMapper().readTree(body).path("detail").asString();
    }

    @Test
    void createsTheWeblogWithTheUserAsCreatorAndAnswers201() throws Exception {
        send("{\"handle\":\"travels\",\"name\":\"Travels\",\"theme\":\"journal\","
                + "\"timeZone\":\"Europe/Berlin\"}", true)
                .andExpect(status().isCreated())
                .andExpect(r -> assertTrue(r.getResponse().getHeader("Location").endsWith("/v1/weblogs/travels")))
                .andExpect(r -> assertEquals("travels", new tools.jackson.databind.ObjectMapper()
                        .readTree(r.getResponse().getContentAsString()).path("handle").asString()));
        ArgumentCaptor<Weblog> added = ArgumentCaptor.forClass(Weblog.class);
        verify(weblogManager).addWeblog(added.capture());
        Weblog weblog = added.getValue();
        assertEquals("travels", weblog.getHandle());
        assertEquals("jake", weblog.getCreatorUserName());
        assertEquals("journal", weblog.getEditorTheme());
        assertEquals("Europe/Berlin", weblog.getTimeZone());
        assertEquals("en_US", weblog.getLocale());
        assertEquals("jake@example.com", weblog.getEmailAddress());
    }

    @Test
    void createWithCopyrightStoresItAndTheViewShowsIt() throws Exception {
        send("{\"handle\":\"travels\",\"name\":\"Travels\",\"theme\":\"journal\","
                + "\"copyright\":\"  \u00a9 2024\u20132026 Maiia \"}", true)
                .andExpect(status().isCreated())
                .andExpect(r -> assertEquals("\u00a9 2024\u20132026 Maiia", new tools.jackson.databind.ObjectMapper()
                        .readTree(r.getResponse().getContentAsString()).path("copyright").asString()));
        ArgumentCaptor<Weblog> added = ArgumentCaptor.forClass(Weblog.class);
        verify(weblogManager).addWeblog(added.capture());
        assertEquals("\u00a9 2024\u20132026 Maiia", added.getValue().getCopyright());
    }

    @Test
    void createWithBlankCopyrightStoresNull() throws Exception {
        send("{\"handle\":\"travels\",\"name\":\"Travels\",\"theme\":\"journal\",\"copyright\":\"   \"}", true)
                .andExpect(status().isCreated());
        ArgumentCaptor<Weblog> added = ArgumentCaptor.forClass(Weblog.class);
        verify(weblogManager).addWeblog(added.capture());
        assertNull(added.getValue().getCopyright());
    }

    @Test
    void createWithCopyrightOver255IsAFieldError() throws Exception {
        send("{\"handle\":\"travels\",\"name\":\"Travels\",\"theme\":\"journal\",\"copyright\":\""
                + "c".repeat(256) + "\"}", true)
                .andExpect(status().isBadRequest())
                .andExpect(errorFieldIs("copyright"));
        verify(weblogManager, never()).addWeblog(any());
    }

    private static User agent() {
        User agent = jake();
        agent.setUserName("bot1");
        agent.setKind(User.Kind.AGENT);
        agent.setEmailAddress("bot1@agents.invalid");
        return agent;
    }

    private static final String BODY = "{\"handle\":\"travels\",\"name\":\"Travels\",\"theme\":\"journal\"}";

    /** Final review I2: contact messages are mailed to the weblog's address, never to an agent. */
    @Test
    void aWeblogAnAgentCreatesTakesTheSitesAddressNotTheAgents() throws Exception {
        send(BODY, true, agent(), " admin@example.com ").andExpect(status().isCreated());

        ArgumentCaptor<Weblog> added = ArgumentCaptor.forClass(Weblog.class);
        verify(weblogManager).addWeblog(added.capture());
        assertEquals("admin@example.com", added.getValue().getEmailAddress());
    }

    @Test
    void anAgentWithNoSiteAddressToFallBackOnMustGiveOne() throws Exception {
        ResultActions result = send(BODY, true, agent(), "  ")
                .andExpect(status().isBadRequest())
                .andExpect(errorFieldIs("emailAddress"));
        assertTrue(detailOf(result).contains("emailAddress is required"), detailOf(result));
        verify(weblogManager, never()).addWeblog(any());
    }

    @Test
    void anAddressTheAgentGivesIsKept() throws Exception {
        send("{\"handle\":\"travels\",\"name\":\"Travels\",\"theme\":\"journal\","
                + "\"emailAddress\":\"desk@example.com\"}", true, agent(), "").andExpect(status().isCreated());

        ArgumentCaptor<Weblog> added = ArgumentCaptor.forClass(Weblog.class);
        verify(weblogManager).addWeblog(added.capture());
        assertEquals("desk@example.com", added.getValue().getEmailAddress());
    }

    @Test
    void aReservedHandleIsA400() throws Exception {
        ResultActions result = send("{\"handle\":\"API\",\"name\":\"x\",\"theme\":\"journal\"}", true)
                .andExpect(status().isBadRequest());
        assertTrue(detailOf(result).contains("reserved"));
        verify(weblogManager, never()).addWeblog(any());
    }

    @Test
    void aTakenHandleIsA409() throws Exception {
        when(weblogManager.getWeblogByHandle("travels", null)).thenReturn(new Weblog());
        send("{\"handle\":\"travels\",\"name\":\"Travels\",\"theme\":\"journal\"}", true)
                .andExpect(status().isConflict());
        verify(weblogManager, never()).addWeblog(any());
    }

    /** A disabled weblog's handle is as taken as any other: 409, not addWeblog meeting ws_handle_uq. */
    @Test
    void aDisabledWeblogsHandleIsA409() throws Exception {
        Weblog disabled = new Weblog();
        disabled.setVisible(Boolean.FALSE);
        when(weblogManager.getWeblogByHandle("travels", null)).thenReturn(disabled);
        ResultActions result = send("{\"handle\":\"travels\",\"name\":\"Travels\",\"theme\":\"journal\"}", true)
                .andExpect(status().isConflict());
        assertEquals("A weblog with that handle already exists.", detailOf(result));
        verify(weblogManager, never()).addWeblog(any());
    }

    @Test
    void theSitewideThemeIsRefusedWithTheValidIds() throws Exception {
        ResultActions result = send("{\"handle\":\"travels\",\"name\":\"Travels\",\"theme\":\"frontpage\"}", true)
                .andExpect(status().isBadRequest());
        assertEquals("theme must be one of: journal.", detailOf(result));
    }

    @Test
    void missingFieldsAreA400NamingThem() throws Exception {
        ResultActions result = send("{}", true).andExpect(status().isBadRequest());
        String detail = detailOf(result);
        assertTrue(detail.contains("handle is required."), detail);
        assertTrue(detail.contains("name is required."), detail);
        assertTrue(detail.contains("theme must be one of"), detail);
    }

    @Test
    void creationTurnedOffIsA403() throws Exception {
        ResultActions result = send("{\"handle\":\"travels\",\"name\":\"Travels\",\"theme\":\"journal\"}", false)
                .andExpect(status().isForbidden());
        assertTrue(detailOf(result).contains("Weblog creation is turned off on this site."));
        verify(weblogManager, never()).addWeblog(any());
    }

    @Test
    void aBlankHandleNamesTheHandleField() throws Exception {
        send("{\"handle\":\"\",\"name\":\"Travels\",\"theme\":\"journal\"}", true)
                .andExpect(status().isBadRequest())
                .andExpect(errorFieldIs("handle"));
    }

    @Test
    void aReservedHandleNamesTheHandleField() throws Exception {
        send("{\"handle\":\"API\",\"name\":\"x\",\"theme\":\"journal\"}", true)
                .andExpect(status().isBadRequest())
                .andExpect(errorFieldIs("handle"));
    }

    @Test
    void aTakenHandleNamesTheHandleField() throws Exception {
        when(weblogManager.getWeblogByHandle("travels", null)).thenReturn(new Weblog());
        send("{\"handle\":\"travels\",\"name\":\"Travels\",\"theme\":\"journal\"}", true)
                .andExpect(status().isConflict())
                .andExpect(errorFieldIs("handle"));
    }

    /** Two 400 violations at once: both are reported, in the order WeblogCreationRules.check runs its rules (handle, then theme). */
    @Test
    void twoBadFieldsAreReportedInRuleOrder() throws Exception {
        send("{\"handle\":\"API\",\"name\":\"x\",\"theme\":\"frontpage\"}", true)
                .andExpect(status().isBadRequest())
                .andExpect(errorFieldsAre("handle", "theme"));
    }
}
