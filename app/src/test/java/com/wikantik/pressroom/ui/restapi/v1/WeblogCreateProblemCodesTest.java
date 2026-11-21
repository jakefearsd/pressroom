package com.wikantik.pressroom.ui.restapi.v1;

import java.util.List;
import com.wikantik.pressroom.business.UserManager;
import com.wikantik.pressroom.business.WeblogManager;
import com.wikantik.pressroom.business.WeblogPageManager;
import com.wikantik.pressroom.business.Weblogger;
import com.wikantik.pressroom.business.themes.SharedTheme;
import com.wikantik.pressroom.business.themes.ThemeManager;
import com.wikantik.pressroom.config.WebloggerConfig;
import com.wikantik.pressroom.config.WebloggerRuntimeConfig;
import com.wikantik.pressroom.pojos.User;
import com.wikantik.pressroom.pojos.Weblog;
import com.wikantik.pressroom.pojos.WeblogPage;
import com.wikantik.pressroom.pojos.WeblogPermission;
import com.wikantik.pressroom.ui.restapi.ApiExceptionHandler;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import static com.wikantik.pressroom.ui.restapi.v1.ProblemAsserts.field;
import static com.wikantik.pressroom.ui.restapi.v1.ProblemAsserts.fieldCodes;
import static com.wikantik.pressroom.ui.restapi.v1.ProblemAsserts.oneField;
import static com.wikantik.pressroom.ui.restapi.v1.ProblemAsserts.problem;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Every refusal of a weblog create carries its own code, with the English it
 * always had (spec 2026-11-20-api-contract-design, Task 3). The rules are
 * WeblogCreationRules', so each code here is a rule's meaning; the English
 * half passed before Task 3 (characterisation), the code half is what it adds.
 */
class WeblogCreateProblemCodesTest {

    private static final String VALID = "\"handle\":\"travels\",\"name\":\"Travels\",\"theme\":\"journal\"";

    private final WeblogManager weblogs = mock(WeblogManager.class);
    private final UserManager users = mock(UserManager.class);
    private final WeblogPageManager pages = mock(WeblogPageManager.class);
    private final Weblogger weblogger = mock(Weblogger.class);

    WeblogCreateProblemCodesTest() {
        ThemeManager themes = mock(ThemeManager.class);
        List<SharedTheme> enabled = List.of(theme("journal"), theme("frontpage"));
        when(themes.getEnabledThemesList()).thenReturn(enabled);
        when(weblogger.getThemeManager()).thenReturn(themes);
        when(weblogger.getWeblogManager()).thenReturn(weblogs);
        when(weblogger.getUserManager()).thenReturn(users);
        when(weblogger.getWeblogPageManager()).thenReturn(pages);
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
        return user;
    }

    private JsonNode send(int status, String json) throws Exception {
        return send(status, json, true, true, jake(), "admin@example.com");
    }

    private JsonNode send(int status, String json, boolean creationAllowed, boolean groupBlogging, User creator,
                          String siteEmail) throws Exception {
        WeblogCreateApi controller = new WeblogCreateApi();
        controller.weblogger = weblogger;
        MockMvc mvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new ApiExceptionHandler()).build();
        try (MockedStatic<WebloggerRuntimeConfig> runtime = mockStatic(WebloggerRuntimeConfig.class);
             MockedStatic<WebloggerConfig> config = mockStatic(WebloggerConfig.class)) {
            runtime.when(() -> WebloggerRuntimeConfig.getBooleanProperty("site.allowUserWeblogCreation"))
                    .thenReturn(creationAllowed);
            runtime.when(() -> WebloggerRuntimeConfig.getBooleanProperty("groupblogging.enabled"))
                    .thenReturn(groupBlogging);
            runtime.when(() -> WebloggerRuntimeConfig.getProperty("site.adminemail")).thenReturn(siteEmail);
            config.when(() -> WebloggerConfig.getProperty("username.allowedChars")).thenReturn("A-Za-z0-9");
            config.when(() -> WebloggerConfig.getProperty("rendering.weblogMapper.pressroomProtectedUrls"))
                    .thenReturn("api,page,search");
            config.when(() -> WebloggerConfig.getProperty("rendering.weblogMapper.userProtectedUrls"))
                    .thenReturn("");
            String body = mvc.perform(post("/v1/weblogs").requestAttr("authenticatedUser", creator)
                            .contentType(MediaType.APPLICATION_JSON).content(json))
                    .andExpect(r -> assertEquals(status, r.getResponse().getStatus()))
                    .andReturn().getResponse().getContentAsString();
            return new ObjectMapper().readTree(body);
        }
    }

    @Test
    void aCreatorTheSiteRefusesHasTheRefusalsCode() throws Exception {
        problem(send(403, "{" + VALID + "}", false, true, jake(), "a@example.com"),
                "weblog.creationDisabled", "Weblog creation is turned off on this site.");

        when(users.getWeblogPermissions(any(User.class))).thenReturn(List.of(mock(WeblogPermission.class)));
        problem(send(403, "{" + VALID + "}", true, false, jake(), "a@example.com"),
                "weblog.oneBlogLimit", "This site allows one weblog per user.");
    }

    @Test
    void anAgentWithNoAddressOnASiteWithNoneIsWeblogEmailRequired() throws Exception {
        User agent = jake();
        agent.setKind(User.Kind.AGENT);
        oneField(send(400, "{" + VALID + "}", true, true, agent, null), "emailAddress", "weblog.emailRequired",
                "emailAddress is required: an agent has no address of its own and this site has no default.");
    }

    /** The multi-field route: one 400 names every broken rule, each with its own code. */
    @Test
    void anEmptyCreateIsInvalidFieldsWithEachRulesCode() throws Exception {
        JsonNode body = send(400, "{}");
        assertEquals("handle is required. name is required. theme must be one of: journal.",
                body.get("detail").asString());
        field(body, "theme", "theme.notOffered", "theme must be one of: journal.");
        assertEquals("request.invalidFields", body.get("code").asString());
        assertEquals(List.of("handle=field.required", "name=field.required", "theme=theme.notOffered"),
                fieldCodes(body));
    }

    @Test
    void theHandleRulesHaveTheirCodes() throws Exception {
        String rest = ",\"name\":\"Travels\",\"theme\":\"journal\"}";
        oneField(send(400, "{\"handle\":\"bad-handle\"" + rest), "handle", "handle.invalid",
                "handle may use only A-Za-z0-9, at most 255 characters.");
        oneField(send(400, "{\"handle\":\"api\"" + rest), "handle", "handle.reserved",
                "handle \"api\" is reserved for the site's own pages.");

        when(weblogs.getWeblogByHandle("taken", null)).thenReturn(new Weblog());
        oneField(send(409, "{\"handle\":\"taken\"" + rest), "handle", "handle.inUse",
                "A weblog with that handle already exists.");

        Weblog site = new Weblog();
        when(weblogs.getSiteSpace()).thenReturn(site);
        WeblogPage about = new WeblogPage();
        about.setSlug("about");
        when(pages.getPages(site)).thenReturn(List.of(about));
        oneField(send(409, "{\"handle\":\"about\"" + rest), "handle", "handle.heldBySitePage",
                "A page of the site already uses that address.");
    }

    @Test
    void theTextAndSettingRulesHaveTheirCodes() throws Exception {
        oneField(send(400, "{" + VALID + ",\"tagline\":\"" + "t".repeat(256) + "\"}"), "description",
                "field.tooLong", "'tagline' must be 255 characters or fewer.");
        oneField(send(400, "{" + VALID + ",\"locale\":\"xx_YY\"}"), "locale", "locale.invalid",
                "locale must be one the settings form offers, for example en_US.");
        oneField(send(400, "{" + VALID + ",\"timeZone\":\"Mars/Base\"}"), "timeZone", "timeZone.invalid",
                "timeZone must be a time zone id, for example Europe/Berlin.");
    }
}
