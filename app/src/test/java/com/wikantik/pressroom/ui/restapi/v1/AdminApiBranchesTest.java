package com.wikantik.pressroom.ui.restapi.v1;

import java.util.Map;
import com.wikantik.pressroom.business.PropertiesManager;
import com.wikantik.pressroom.business.UserManager;
import com.wikantik.pressroom.business.Weblogger;
import com.wikantik.pressroom.business.discovery.DiscoveryManager;
import com.wikantik.pressroom.config.WebloggerConfig;
import com.wikantik.pressroom.pojos.RuntimeConfigProperty;
import com.wikantik.pressroom.pojos.User;
import com.wikantik.pressroom.ui.restapi.ApiExceptionHandler;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The refusal and fail-closed branches of {@link AdminApi} that
 * {@link AdminApiTest} leaves out: missing or null request fields, the
 * case-insensitive duplicate check, the allowed-characters fallback, and the
 * value checks a config PATCH makes before it saves anything.
 */
class AdminApiBranchesTest {

    private static MockMvc mvc(Weblogger weblogger) {
        AdminApi controller = new AdminApi();
        controller.weblogger = weblogger;
        return MockMvcBuilders.standaloneSetup(controller).setControllerAdvice(new ApiExceptionHandler()).build();
    }

    private static Weblogger weblogger() {
        Weblogger weblogger = mock(Weblogger.class);
        when(weblogger.getUserManager()).thenReturn(mock(UserManager.class));
        when(weblogger.getPropertiesManager()).thenReturn(mock(PropertiesManager.class));
        when(weblogger.getDiscoveryManager()).thenReturn(mock(DiscoveryManager.class));
        return weblogger;
    }

    private static String postUser(MockMvc mvc, String json, int expectedStatus) throws Exception {
        return mvc.perform(post("/v1/admin/users").contentType(MediaType.APPLICATION_JSON).content(json))
                .andExpect(r -> assertEquals(expectedStatus, r.getResponse().getStatus()))
                .andReturn().getResponse().getContentAsString();
    }

    @Test
    void createWithNoUserNameAtAllIsBadRequest() throws Exception {
        Weblogger weblogger = weblogger();

        String body = postUser(mvc(weblogger), "{\"emailAddress\":\"a@example.test\"}", 400);

        assertTrue(body.contains("userName is required"), body);
        verify(weblogger.getUserManager(), never()).addUser(any());
    }

    @Test
    void createWithNoEmailAddressAtAllIsBadRequest() throws Exception {
        Weblogger weblogger = weblogger();

        String body = postUser(mvc(weblogger), "{\"userName\":\"alice\"}", 400);

        assertTrue(body.contains("valid emailAddress"), body);
        verify(weblogger.getUserManager(), never()).addUser(any());
    }

    /** UserManager.addUser compares case-sensitively, so the API checks the lower-cased name too. */
    @Test
    void createRefusesANameThatDiffersFromAnExistingOneOnlyByCase() throws Exception {
        Weblogger weblogger = weblogger();
        User existing = new User();
        existing.setUserName("alice");
        when(weblogger.getUserManager().getUserByUserName("alice", null)).thenReturn(existing);

        String body = postUser(mvc(weblogger), "{\"userName\":\"ALICE\",\"emailAddress\":\"a@example.test\"}", 409);

        assertTrue(body.contains("already exists"), body);
        verify(weblogger.getUserManager(), never()).addUser(any());
    }

    @Test
    void createFallsBackToTheBuiltInAllowedCharactersWhenTheSettingIsBlank() throws Exception {
        try (var config = mockStatic(WebloggerConfig.class)) {
            config.when(() -> WebloggerConfig.getProperty("username.allowedChars")).thenReturn("  ");
            Weblogger weblogger = weblogger();

            String body = postUser(mvc(weblogger), "{\"userName\":\"al ice!\",\"emailAddress\":\"a@example.test\"}", 400);

            assertTrue(body.contains("characters that are not allowed"), body);
        }
    }

    @Test
    void patchingAUserWhoseNameIsBlankIsNotFound() throws Exception {
        Weblogger weblogger = weblogger();

        mvc(weblogger).perform(patch("/v1/admin/users/{userName}", " ")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"enabled\":false}"))
                .andExpect(status().isNotFound());

        verify(weblogger.getUserManager(), never()).saveUser(any());
    }

    @Test
    void patchLeavesAnOmittedEnabledFlagAlone() throws Exception {
        Weblogger weblogger = weblogger();
        User user = new User();
        user.setUserName("alice");
        user.setEnabled(Boolean.TRUE);
        user.setScreenName("Old");
        when(weblogger.getUserManager().getUserByUserName("alice", null)).thenReturn(user);

        mvc(weblogger).perform(patch("/v1/admin/users/{userName}", "alice")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"screenName\":\"New\"}"))
                .andExpect(status().isOk());

        assertEquals(Boolean.TRUE, user.getEnabled());
        assertEquals("New", user.getScreenName());
        verify(weblogger.getUserManager()).saveUser(user);
    }

    @Test
    void listConfigReportsAStoredValueInPreferenceToTheDefault() throws Exception {
        Weblogger weblogger = weblogger();
        when(weblogger.getPropertiesManager().getProperty(anyString())).thenReturn(null);
        when(weblogger.getPropertiesManager().getProperty("site.pages.maxEntries"))
                .thenReturn(new RuntimeConfigProperty("site.pages.maxEntries", "77"));

        String body = mvc(weblogger).perform(get("/v1/admin/config")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        tools.jackson.databind.JsonNode json = new tools.jackson.databind.ObjectMapper().readTree(body);
        String stored = null;
        for (int i = 0; i < json.size(); i++) {
            if ("site.pages.maxEntries".equals(json.get(i).get("name").asString())) {
                stored = json.get(i).get("value").asString();
            }
        }
        assertEquals("77", stored);
    }

    /** The existing managed row is changed in place; a fresh instance would be a duplicate-key insert. */
    @Test
    void patchConfigUpdatesTheExistingRowInPlace() throws Exception {
        Weblogger weblogger = weblogger();
        RuntimeConfigProperty existing = new RuntimeConfigProperty("site.pages.maxEntries", "5");
        when(weblogger.getPropertiesManager().getProperty("site.pages.maxEntries")).thenReturn(existing);

        mvc(weblogger).perform(patch("/v1/admin/config").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"site.pages.maxEntries\":\"12\"}"))
                .andExpect(status().isOk());

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, RuntimeConfigProperty>> saved = ArgumentCaptor.forClass(Map.class);
        verify(weblogger.getPropertiesManager()).saveProperties(saved.capture());
        assertSame(existing, saved.getValue().get("site.pages.maxEntries"));
        assertEquals("12", existing.getValue());
    }

    @Test
    void patchConfigAcceptsAWellFormedIntegerFloatAndFalse() throws Exception {
        Weblogger weblogger = weblogger();

        String body = mvc(weblogger).perform(patch("/v1/admin/config").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"site.pages.maxEntries\":\"12\",\"uploads.file.maxsize\":\"1.5\","
                                + "\"groupblogging.enabled\":\"FALSE\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        tools.jackson.databind.JsonNode json = new tools.jackson.databind.ObjectMapper().readTree(body);
        assertEquals(3, json.size());
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, RuntimeConfigProperty>> saved = ArgumentCaptor.forClass(Map.class);
        verify(weblogger.getPropertiesManager()).saveProperties(saved.capture());
        assertEquals("12", saved.getValue().get("site.pages.maxEntries").getValue());
        assertEquals("1.5", saved.getValue().get("uploads.file.maxsize").getValue());
        assertEquals("FALSE", saved.getValue().get("groupblogging.enabled").getValue());
        verify(weblogger).flush();
    }

    @Test
    void patchConfigRefusesANullValueAndSavesNothing() throws Exception {
        Weblogger weblogger = weblogger();

        String body = mvc(weblogger).perform(patch("/v1/admin/config").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"groupblogging.enabled\":null}"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andReturn().getResponse().getContentAsString();

        assertTrue(body.contains("cannot be set to null"), body);
        verify(weblogger.getPropertiesManager(), never()).saveProperties(any());
    }

    @Test
    void patchRejectsShareOutOfRange() throws Exception {
        String body = patchConfigRefused("{\"site.discovery.sharePercent\":\"101\"}");
        assertEquals("site.discovery.sharePercent", firstErrorField(body));
    }

    @Test
    void patchRejectsRefreshSecondsBelowAMinute() throws Exception {
        String body = patchConfigRefused("{\"site.discovery.refreshSeconds\":\"30\"}");
        assertEquals("site.discovery.refreshSeconds", firstErrorField(body));
    }

    @Test
    void patchAcceptsRefreshSeconds() throws Exception {
        Weblogger weblogger = weblogger();

        mvc(weblogger).perform(patch("/v1/admin/config").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"site.discovery.refreshSeconds\":\"120\"}"))
                .andExpect(status().isOk());

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, RuntimeConfigProperty>> saved = ArgumentCaptor.forClass(Map.class);
        verify(weblogger.getPropertiesManager()).saveProperties(saved.capture());
        assertEquals("120", saved.getValue().get("site.discovery.refreshSeconds").getValue());
    }

    /**
     * The snapshot otherwise keeps the old share and window until the next
     * scheduled refresh, up to an hour: a changed discovery setting rebuilds
     * it at once.
     */
    @Test
    void patchChangingADiscoverySettingRefreshesTheSnapshot() throws Exception {
        Weblogger weblogger = weblogger();
        when(weblogger.getPropertiesManager().getProperty("site.discovery.sharePercent"))
                .thenReturn(new RuntimeConfigProperty("site.discovery.sharePercent", "20"));

        mvc(weblogger).perform(patch("/v1/admin/config").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"site.discovery.sharePercent\":\"30\"}"))
                .andExpect(status().isOk());

        verify(weblogger.getDiscoveryManager()).refresh();
    }

    @Test
    void patchRepeatingADiscoverySettingDoesNotRefresh() throws Exception {
        Weblogger weblogger = weblogger();
        when(weblogger.getPropertiesManager().getProperty("site.discovery.sharePercent"))
                .thenReturn(new RuntimeConfigProperty("site.discovery.sharePercent", "30"));

        mvc(weblogger).perform(patch("/v1/admin/config").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"site.discovery.sharePercent\":\"30\"}"))
                .andExpect(status().isOk());

        verify(weblogger.getDiscoveryManager(), never()).refresh();
    }

    @Test
    void patchOfAnotherSettingDoesNotRefresh() throws Exception {
        Weblogger weblogger = weblogger();

        mvc(weblogger).perform(patch("/v1/admin/config").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"groupblogging.enabled\":\"true\"}"))
                .andExpect(status().isOk());

        verify(weblogger.getDiscoveryManager(), never()).refresh();
    }

    /** The field named by the first {@code errors} entry of a problem body. */
    private static String firstErrorField(String body) {
        return new tools.jackson.databind.ObjectMapper().readTree(body)
                .get("errors").get(0).get("field").asString();
    }

    private static String patchUserRefused(String json) throws Exception {
        Weblogger weblogger = weblogger();
        User user = new User();
        user.setUserName("alice");
        when(weblogger.getUserManager().getUserByUserName("alice", null)).thenReturn(user);
        return mvc(weblogger).perform(patch("/v1/admin/users/{userName}", "alice")
                        .contentType(MediaType.APPLICATION_JSON).content(json))
                .andExpect(status().isBadRequest())
                .andReturn().getResponse().getContentAsString();
    }

    private static String patchConfigRefused(String json) throws Exception {
        return mvc(weblogger()).perform(patch("/v1/admin/config").contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isBadRequest())
                .andReturn().getResponse().getContentAsString();
    }

    @Test
    void aCreateWithNoUserNameNamesTheUserNameField() throws Exception {
        String body = postUser(mvc(weblogger()), "{\"emailAddress\":\"a@example.test\"}", 400);
        assertEquals("userName", firstErrorField(body));
    }

    @Test
    void aCreateWithNoEmailAddressNamesTheEmailAddressField() throws Exception {
        String body = postUser(mvc(weblogger()), "{\"userName\":\"alice\"}", 400);
        assertEquals("emailAddress", firstErrorField(body));
    }

    @Test
    void aCreateWithDisallowedCharactersNamesTheUserNameField() throws Exception {
        try (var config = mockStatic(WebloggerConfig.class)) {
            config.when(() -> WebloggerConfig.getProperty("username.allowedChars")).thenReturn("  ");
            String body = postUser(mvc(weblogger()),
                    "{\"userName\":\"al ice!\",\"emailAddress\":\"a@example.test\"}", 400);
            assertEquals("userName", firstErrorField(body));
        }
    }

    @Test
    void aPatchWithAMalformedEmailAddressNamesTheEmailAddressField() throws Exception {
        assertEquals("emailAddress", firstErrorField(patchUserRefused("{\"emailAddress\":\"not-an-address\"}")));
    }

    @Test
    void aPatchWithABlankScreenNameNamesTheScreenNameField() throws Exception {
        assertEquals("screenName", firstErrorField(patchUserRefused("{\"screenName\":\"  \"}")));
    }

    @Test
    void aConfigPatchNullNamesThatProperty() throws Exception {
        assertEquals("groupblogging.enabled", firstErrorField(patchConfigRefused("{\"groupblogging.enabled\":null}")));
    }

    @Test
    void aConfigPatchBadBooleanNamesThatProperty() throws Exception {
        assertEquals("groupblogging.enabled", firstErrorField(patchConfigRefused("{\"groupblogging.enabled\":\"maybe\"}")));
    }

    @Test
    void aConfigPatchBadIntegerNamesThatProperty() throws Exception {
        assertEquals("site.pages.maxEntries", firstErrorField(patchConfigRefused("{\"site.pages.maxEntries\":\"abc\"}")));
    }

    @Test
    void aConfigPatchBadNumberNamesThatProperty() throws Exception {
        assertEquals("uploads.file.maxsize", firstErrorField(patchConfigRefused("{\"uploads.file.maxsize\":\"abc\"}")));
    }

    @Test
    void aConfigPatchOfAnUnknownPropertyNamesIt() throws Exception {
        assertEquals("no.such.property", firstErrorField(patchConfigRefused("{\"no.such.property\":\"x\"}")));
    }
}
