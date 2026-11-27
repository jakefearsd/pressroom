package com.wikantik.pressroom.ui.restapi.v1;

import java.util.ArrayList;
import java.util.List;

import com.wikantik.pressroom.WebloggerException;
import com.wikantik.pressroom.business.UserManager;
import com.wikantik.pressroom.business.analytics.AnalyticsManager;
import com.wikantik.pressroom.business.analytics.AnalyticsStatus;
import com.wikantik.pressroom.config.WebloggerConfig;
import com.wikantik.pressroom.config.WebloggerRuntimeConfig;
import com.wikantik.pressroom.ui.restapi.ApiBodyLimitFilter;
import com.wikantik.pressroom.ui.restapi.ColumnLimits;
import com.wikantik.pressroom.ui.restapi.Paging;
import com.wikantik.pressroom.ui.restapi.dto.MetaDtos;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import java.util.Arrays;
import com.wikantik.pressroom.business.WeblogManager;
import com.wikantik.pressroom.business.Weblogger;
import com.wikantik.pressroom.pojos.ApiToken;
import com.wikantik.pressroom.pojos.GlobalPermission;
import com.wikantik.pressroom.pojos.User;
import com.wikantik.pressroom.pojos.Weblog;
import com.wikantik.pressroom.pojos.WeblogPermission;
import com.wikantik.pressroom.ui.restapi.ApiExceptionHandler;
import com.wikantik.pressroom.ui.restapi.auth.ApiPrincipal;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@link MetaApi}: {@code GET /v1/ping} liveness, and {@code GET /v1/me} --
 * who the caller is, whether they are a global admin, and the ceiling of the
 * API token they authenticated with (empty for Basic auth).
 *
 * <p>Characterisation tests: written against the existing behaviour and
 * expected to pass immediately.
 *
 * <p>Standalone MockMvc, like the other {@code *ApiTest}s: the
 * {@code authenticatedUser} request attribute stands in for what
 * {@code PressroomHandlerInterceptor} sets, and the security context for what
 * the Basic/Bearer filters establish.
 */
class MetaApiTest {

    private static final String AUTHENTICATED_USER = "authenticatedUser";

    private UserManager userManager;
    private WeblogManager weblogManager;
    private MockMvc mockMvc;
    private User user;
    private AnalyticsManager analyticsManager;
    private Weblogger weblogger;
    private com.wikantik.pressroom.business.WeblogEntryManager entryManager;
    private com.wikantik.pressroom.business.CommentManager commentManager;
    private com.wikantik.pressroom.business.URLStrategy urlStrategy;

    @BeforeEach
    void setUp() throws Exception {
        weblogger = mock(Weblogger.class);
        analyticsManager = mock(AnalyticsManager.class);
        when(weblogger.getAnalyticsManager()).thenReturn(analyticsManager);
        when(analyticsManager.getStatus()).thenReturn(AnalyticsStatus.notConfigured());
        userManager = mock(UserManager.class);
        when(weblogger.getUserManager()).thenReturn(userManager);
        weblogManager = mock(WeblogManager.class);
        when(weblogger.getWeblogManager()).thenReturn(weblogManager);
        entryManager = mock(com.wikantik.pressroom.business.WeblogEntryManager.class);
        when(weblogger.getWeblogEntryManager()).thenReturn(entryManager);
        commentManager = mock(com.wikantik.pressroom.business.CommentManager.class);
        when(weblogger.getCommentManager()).thenReturn(commentManager);
        when(weblogger.getFormSubmissionManager())
                .thenReturn(mock(com.wikantik.pressroom.business.FormSubmissionManager.class));
        when(entryManager.getBlogStanding(any(Weblog.class), any(java.util.Date.class)))
                .thenReturn(com.wikantik.pressroom.business.BlogStanding.NONE);
        urlStrategy = mock(com.wikantik.pressroom.business.URLStrategy.class);
        when(weblogger.getUrlStrategy()).thenReturn(urlStrategy);
        when(urlStrategy.getWeblogURL(any(Weblog.class), any(), eq(true)))
                .thenAnswer(call -> "https://blog.example.com/" + ((Weblog) call.getArgument(0)).getHandle());

        MetaApi api = new MetaApi();
        // Field-injected (@Autowired @Lazy) in production; no constructor.
        ReflectionTestUtils.setField(api, "weblogger", weblogger);
        mockMvc = MockMvcBuilders.standaloneSetup(api)
                .setControllerAdvice(new ApiExceptionHandler())
                .build();

        user = new User();
        user.setUserName("alice");
        user.setScreenName("Alice A.");
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void pingAnswersOkWithoutAnyCaller() throws Exception {
        JsonNode body = json(mockMvc.perform(get("/v1/ping"))
                .andExpect(status().isOk()));

        assertEquals("ok", body.get("status").asString());
    }

    @Test
    void meWithoutAnAuthenticatedUserIsA401Problem() throws Exception {
        JsonNode body = json(mockMvc.perform(get("/v1/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json")));

        assertEquals("Authentication required.", body.get("detail").asString());
    }

    /**
     * Characterisation test (spec 2026-11-20-api-contract-design, Task 1,
     * criterion 4): written against the code before problem codes existed and
     * passing immediately. A caller asking for English reads the same detail.
     */
    @Test
    void meWithoutAnAuthenticatedUserIsA401ProblemInEnglishForAnEnglishCaller() throws Exception {
        JsonNode body = json(mockMvc.perform(get("/v1/me").header("Accept-Language", "en-GB"))
                .andExpect(status().isUnauthorized()));

        assertEquals("Authentication required.", body.get("detail").asString());
    }

    /**
     * A Basic-authenticated caller carries no {@link ApiPrincipal}, so there
     * is no token ceiling to report: {@code tokenScope} is an empty object.
     */
    @Test
    void aBasicAuthenticatedAdminIsReportedWithNoTokenScope() throws Exception {
        authenticateWithBasicAuth();
        givenGlobalAdmin(true);

        JsonNode body = me();

        assertEquals("alice", body.get("userName").asString());
        assertEquals("Alice A.", body.get("screenName").asString());
        assertTrue(body.get("globalAdmin").asBoolean());
        assertTrue(body.get("tokenScope").isObject());
        assertTrue(body.get("tokenScope").isEmpty(), body.toString());
    }

    @Test
    void aWeblogPinnedTokenReportsItsWeblogAndRole() throws Exception {
        authenticateWithToken(new ApiPrincipal("alice", "travelblog", ApiToken.Role.POST, "token-1"));
        givenGlobalAdmin(false);

        JsonNode body = me();

        assertFalse(body.get("globalAdmin").asBoolean());
        assertEquals("travelblog", body.get("tokenScope").get("weblog").asString());
        assertEquals("POST", body.get("tokenScope").get("role").asString());
    }

    /** An unpinned token reports its weblog as the empty string, not null. */
    @Test
    void anUnpinnedTokenReportsAnEmptyWeblog() throws Exception {
        authenticateWithToken(new ApiPrincipal("alice", null, ApiToken.Role.READ, "token-1"));
        givenGlobalAdmin(false);

        JsonNode body = me();

        assertEquals("", body.get("tokenScope").get("weblog").asString());
        assertEquals("READ", body.get("tokenScope").get("role").asString());
    }

    @Test
    void noSecurityContextAtAllStillAnswersWithAnEmptyTokenScope() throws Exception {
        givenGlobalAdmin(false);

        JsonNode body = me();

        assertEquals("alice", body.get("userName").asString());
        assertTrue(body.get("tokenScope").isEmpty(), body.toString());
    }

    /**
     * A permission check that cannot be answered is a denial: the caller is
     * reported as not a global admin rather than the request failing, and
     * never as an admin.
     */
    @Test
    void aPermissionLookupFailureReportsNotGlobalAdmin() throws Exception {
        authenticateWithBasicAuth();
        when(userManager.checkPermission(any(), eq(user)))
                .thenThrow(new WebloggerException("store unreachable"));

        JsonNode body = me();

        assertFalse(body.get("globalAdmin").asBoolean());
    }

    /**
     * An unlimited token (no weblog pin) lists every weblog its user is a
     * member of, sorted by handle, each with the highest action it holds.
     */
    @Test
    void anUnlimitedTokenSeesEveryWeblogItsUserBelongsTo() throws Exception {
        authenticateWithToken(new ApiPrincipal("alice", null, ApiToken.Role.POST, "token-1"));
        givenGlobalAdmin(false);
        givenMembership(weblog("travels", "Travels"), List.of(WeblogPermission.ADMIN));
        givenMembership(weblog("food", "Food"), List.of(WeblogPermission.POST));

        JsonNode weblogs = me().get("weblogs");

        assertEquals(2, weblogs.size(), weblogs.toString());
        assertEquals("food", weblogs.get(0).get("handle").asString());
        assertEquals("Food", weblogs.get(0).get("name").asString());
        assertEquals("POST", weblogs.get(0).get("permission").asString());
        assertEquals("travels", weblogs.get(1).get("handle").asString());
        assertEquals("Travels", weblogs.get(1).get("name").asString());
        assertEquals("ADMIN", weblogs.get(1).get("permission").asString());
    }

    /** A weblog-limited token lists only the weblog it is pinned to. */
    @Test
    void aLimitedTokenSeesOnlyItsWeblog() throws Exception {
        authenticateWithToken(new ApiPrincipal("alice", "travels", ApiToken.Role.POST, "token-1"));
        givenGlobalAdmin(false);
        givenMembership(weblog("travels", "Travels"), List.of(WeblogPermission.ADMIN));
        givenMembership(weblog("food", "Food"), List.of(WeblogPermission.POST));

        JsonNode weblogs = me().get("weblogs");

        assertEquals(1, weblogs.size(), weblogs.toString());
        assertEquals("travels", weblogs.get(0).get("handle").asString());
    }

    /** A user with no weblog permissions gets an empty array, not an absent key. */
    @Test
    void aUserWithNoWeblogsSeesAnEmptyList() throws Exception {
        authenticateWithBasicAuth();
        givenGlobalAdmin(false);
        when(userManager.getWeblogPermissions(user)).thenReturn(List.of());

        JsonNode weblogs = me().get("weblogs");

        assertTrue(weblogs.isArray(), weblogs.toString());
        assertTrue(weblogs.isEmpty(), weblogs.toString());
    }

    /** A permission row whose weblog no longer exists is skipped, not an error. */
    @Test
    void aPermissionWhoseWeblogIsGoneIsSkipped() throws Exception {
        authenticateWithBasicAuth();
        givenGlobalAdmin(false);
        when(weblogManager.getWeblogByHandle("gone")).thenReturn(null);
        givenMembership(weblog("food", "Food"), List.of(WeblogPermission.POST));
        when(userManager.getWeblogPermissions(user)).thenReturn(List.of(
                new WeblogPermission(weblog("gone", "Gone"), user, List.of(WeblogPermission.ADMIN)),
                new WeblogPermission(weblog("food", "Food"), user, List.of(WeblogPermission.POST))));

        JsonNode weblogs = me().get("weblogs");

        assertEquals(1, weblogs.size(), weblogs.toString());
        assertEquals("food", weblogs.get(0).get("handle").asString());
    }

    /** A member with only edit_draft is reported as EDIT_DRAFT, the lowest rung. */
    @Test
    void aDraftOnlyMemberIsReportedAsEditDraft() throws Exception {
        authenticateWithBasicAuth();
        givenGlobalAdmin(false);
        givenMembership(weblog("food", "Food"), List.of(WeblogPermission.EDIT_DRAFT));

        JsonNode weblogs = me().get("weblogs");

        assertEquals("EDIT_DRAFT", weblogs.get(0).get("permission").asString());
    }

    /** A weblog with no display name is listed with an empty name, not a 500. */
    @Test
    void aWeblogWithNoNameIsListedWithAnEmptyName() throws Exception {
        authenticateWithBasicAuth();
        givenGlobalAdmin(false);
        givenMembership(weblog("food", null), List.of(WeblogPermission.POST));

        JsonNode weblogs = me().get("weblogs");

        assertEquals("", weblogs.get(0).get("name").asString());
    }

    /**
     * A failed membership lookup is an opaque 500 problem, never a 200 with
     * an empty {@code weblogs} list that would read as "no weblogs".
     */
    @Test
    void aFailedMembershipLookupIsAnOpaque500NotAnEmptyList() throws Exception {
        authenticateWithBasicAuth();
        givenGlobalAdmin(false);
        when(userManager.getWeblogPermissions(user))
                .thenThrow(new WebloggerException("store unreachable"));

        JsonNode body = json(mockMvc.perform(get("/v1/me").requestAttr(AUTHENTICATED_USER, user))
                .andExpect(status().isInternalServerError()));

        assertEquals("The request could not be completed.", body.get("detail").asString());
        assertFalse(body.has("weblogs"), body.toString());
    }

    /**
     * A failed weblog lookup for a membership row is likewise an opaque 500,
     * not a silently skipped weblog.
     */
    @Test
    void aFailedWeblogLookupIsAnOpaque500NotAnEmptyList() throws Exception {
        authenticateWithBasicAuth();
        givenGlobalAdmin(false);
        givenMembership(weblog("food", "Food"), List.of(WeblogPermission.POST));
        when(weblogManager.getWeblogByHandle("food"))
                .thenThrow(new WebloggerException("store unreachable"));

        JsonNode body = json(mockMvc.perform(get("/v1/me").requestAttr(AUTHENTICATED_USER, user))
                .andExpect(status().isInternalServerError()));

        assertEquals("The request could not be completed.", body.get("detail").asString());
        assertFalse(body.has("weblogs"), body.toString());
    }

    /** Criterion 22: /me reports whether the caller is a person or an agent. */
    @Test
    void meReportsTheAccountKind() throws Exception {
        authenticateWithBasicAuth();
        givenGlobalAdmin(false);
        assertEquals("PERSON", me().get("kind").asString());

        user.setKind(User.Kind.AGENT);
        assertEquals("AGENT", me().get("kind").asString());
    }

    // ------------------------------------------------------- GET /v1/meta

    private static final List<String> FLAG_SETTINGS = List.of("themes.customtheme.allowed",
            "groupblogging.enabled", "site.allowUserWeblogCreation", "uploads.enabled");

    /** {@code GET /v1/meta} as {@link #user}; asserts 200 and returns the body. */
    private JsonNode meta() throws Exception {
        return json(mockMvc.perform(get("/v1/meta").requestAttr(AUTHENTICATED_USER, user))
                .andExpect(status().isOk()));
    }

    @Test
    void metaWithoutAnAuthenticatedUserIsA401Problem() throws Exception {
        mockMvc.perform(get("/v1/meta")).andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"));
    }

    @Test
    void metaCarriesEveryMember() throws Exception {
        when(weblogger.getVersion()).thenReturn("0.16.0");
        try (MockedStatic<WebloggerRuntimeConfig> rt = Mockito.mockStatic(WebloggerRuntimeConfig.class)) {
            rt.when(() -> WebloggerRuntimeConfig.getPropertyWithConfigFallback("site.absoluteurl"))
                    .thenReturn("https://blog.example.com/");

            JsonNode body = meta();

            assertEquals("0.16.0", body.get("version").asString());
            assertEquals("https://blog.example.com", body.get("siteUrl").asString());
            assertEquals(List.of("customThemes", "groupBlogging", "userWeblogCreation", "uploads", "readerSignIn",
                    "analytics", "searchEnabled", "mailConfigured"), names(body.get("features")));
            assertEquals(List.of("maxBodyBytes", "maxListLimit", "entryTextMaxChars"), names(body.get("limits")));
        }
    }

    private static List<String> names(JsonNode node) {
        return node.propertyNames().stream().toList();
    }

    /** Each flag follows its setting, read afresh on each request: nothing is cached. */
    @Test
    void eachRuntimeFeatureFlagFollowsItsSettingBetweenTwoRequests() throws Exception {
        String[] members = {"customThemes", "groupBlogging", "userWeblogCreation", "uploads"};
        for (int i = 0; i < FLAG_SETTINGS.size(); i++) {
            String setting = FLAG_SETTINGS.get(i);
            try (MockedStatic<WebloggerRuntimeConfig> rt = Mockito.mockStatic(WebloggerRuntimeConfig.class)) {
                rt.when(() -> WebloggerRuntimeConfig.getBooleanProperty(setting)).thenReturn(true);
                assertTrue(meta().get("features").get(members[i]).asBoolean(), setting + " on");
                for (String other : members) {
                    if (!other.equals(members[i])) {
                        assertFalse(meta().get("features").get(other).asBoolean(), other + " is its own setting");
                    }
                }
                rt.when(() -> WebloggerRuntimeConfig.getBooleanProperty(setting)).thenReturn(false);
                assertFalse(meta().get("features").get(members[i]).asBoolean(), setting + " off");
            }
        }
    }

    /** The Maintenance screen hides "Rebuild index" when the site runs without search (the JSP's rc:getBooleanProp). */
    @Test
    void searchEnabledFollowsItsSetting() throws Exception {
        try (MockedStatic<WebloggerRuntimeConfig> rt = Mockito.mockStatic(WebloggerRuntimeConfig.class)) {
            rt.when(() -> WebloggerRuntimeConfig.getBooleanProperty("search.enabled")).thenReturn(true);
            assertTrue(meta().get("features").get("searchEnabled").asBoolean());
            rt.when(() -> WebloggerRuntimeConfig.getBooleanProperty("search.enabled")).thenReturn(false);
            assertFalse(meta().get("features").get("searchEnabled").asBoolean());
        }
    }

    /** The create-user form offers the emailed link only when the server can send it (PasswordLinkMailer.isReady). */
    @Test
    void mailConfiguredFollowsThePasswordLinkMailer() throws Exception {
        try (MockedStatic<com.wikantik.pressroom.ui.controllers.core.PasswordLinkMailer> mailer =
                     Mockito.mockStatic(com.wikantik.pressroom.ui.controllers.core.PasswordLinkMailer.class)) {
            mailer.when(com.wikantik.pressroom.ui.controllers.core.PasswordLinkMailer::isReady).thenReturn(true);
            assertTrue(meta().get("features").get("mailConfigured").asBoolean());
            mailer.when(com.wikantik.pressroom.ui.controllers.core.PasswordLinkMailer::isReady).thenReturn(false);
            assertFalse(meta().get("features").get("mailConfigured").asBoolean());
        }
    }

    @Test
    void readerSignInFollowsTheSsoConfiguration() throws Exception {
        try (MockedStatic<WebloggerConfig> cfg = Mockito.mockStatic(WebloggerConfig.class)) {
            cfg.when(() -> WebloggerConfig.getProperty("sso.oidc.client.id")).thenReturn("id");
            cfg.when(() -> WebloggerConfig.getProperty("sso.oidc.client.secret")).thenReturn("secret");
            assertTrue(meta().get("features").get("readerSignIn").asBoolean());
            cfg.when(() -> WebloggerConfig.getProperty("sso.oidc.client.secret")).thenReturn("");
            assertFalse(meta().get("features").get("readerSignIn").asBoolean());
        }
    }

    @Test
    void analyticsFollowsWhetherAServiceIsConfigured() throws Exception {
        assertFalse(meta().get("features").get("analytics").asBoolean());
        when(analyticsManager.getStatus())
                .thenReturn(new AnalyticsStatus(true, true, 0, 0, List.of(), null, null));
        assertTrue(meta().get("features").get("analytics").asBoolean());
    }

    @Test
    void siteUrlIsTheConfiguredAddressWithoutATrailingSlashOrNull() throws Exception {
        try (MockedStatic<WebloggerRuntimeConfig> rt = Mockito.mockStatic(WebloggerRuntimeConfig.class)) {
            rt.when(() -> WebloggerRuntimeConfig.getPropertyWithConfigFallback("site.absoluteurl"))
                    .thenReturn("https://blog.example.com");
            assertEquals("https://blog.example.com", meta().get("siteUrl").asString());
            rt.when(() -> WebloggerRuntimeConfig.getPropertyWithConfigFallback("site.absoluteurl"))
                    .thenReturn("");
            assertTrue(meta().get("siteUrl").isNull());
            rt.when(() -> WebloggerRuntimeConfig.getPropertyWithConfigFallback("site.absoluteurl"))
                    .thenReturn(null);
            assertTrue(meta().get("siteUrl").isNull());
        }
    }

    @Test
    void anUnknownVersionIsNull() throws Exception {
        when(weblogger.getVersion()).thenReturn("");
        assertTrue(meta().get("version").isNull());
    }

    /** Each limit is read from its constant, so the response cannot lag the constant. */
    @Test
    void limitsEqualTheirConstants() throws Exception {
        JsonNode limits = meta().get("limits");

        assertEquals(ApiBodyLimitFilter.MAX_BODY_BYTES, limits.get("maxBodyBytes").asInt());
        assertEquals(Paging.MAX_LIMIT, limits.get("maxListLimit").asInt());
        assertEquals(ColumnLimits.ENTRY_TEXT, limits.get("entryTextMaxChars").asInt());
        assertEquals(200, Paging.MAX_LIMIT);
    }

    /** The settings screen's "At most N" tip and the field's max: the ceiling the save refuses above, read afresh. */
    @Test
    void maxEntriesPerPageFollowsTheRuntimeSettingOnEveryRequest() throws Exception {
        try (MockedStatic<WebloggerRuntimeConfig> rt = Mockito.mockStatic(WebloggerRuntimeConfig.class)) {
            rt.when(() -> WebloggerRuntimeConfig.getIntProperty("site.pages.maxEntries")).thenReturn(30);
            assertEquals(30, meta().get("maxEntriesPerPage").asInt());
            rt.when(() -> WebloggerRuntimeConfig.getIntProperty("site.pages.maxEntries")).thenReturn(50);
            assertEquals(50, meta().get("maxEntriesPerPage").asInt());
        }
    }

    /** The trash page's retention sentence needs the runtime property, read afresh (an admin can change it). */
    @Test
    void trashRetentionDaysFollowsTheRuntimeSettingOnEveryRequest() throws Exception {
        try (MockedStatic<WebloggerRuntimeConfig> rt = Mockito.mockStatic(WebloggerRuntimeConfig.class)) {
            rt.when(() -> WebloggerRuntimeConfig.getIntProperty("entry.trash.retention.days")).thenReturn(14);
            assertEquals(14, meta().get("trashRetentionDays").asInt());
            rt.when(() -> WebloggerRuntimeConfig.getIntProperty("entry.trash.retention.days")).thenReturn(-1);
            assertEquals(-1, meta().get("trashRetentionDays").asInt());
        }
    }

    /** The page trash has its own retention setting (page.trash.retention.days), read afresh like the entries'. */
    @Test
    void pageTrashRetentionDaysFollowsItsOwnRuntimeSettingOnEveryRequest() throws Exception {
        try (MockedStatic<WebloggerRuntimeConfig> rt = Mockito.mockStatic(WebloggerRuntimeConfig.class)) {
            rt.when(() -> WebloggerRuntimeConfig.getIntProperty("entry.trash.retention.days")).thenReturn(14);
            rt.when(() -> WebloggerRuntimeConfig.getIntProperty("page.trash.retention.days")).thenReturn(30);
            assertEquals(30, meta().get("pageTrashRetentionDays").asInt());
            assertEquals(14, meta().get("trashRetentionDays").asInt());
            rt.when(() -> WebloggerRuntimeConfig.getIntProperty("page.trash.retention.days")).thenReturn(-1);
            assertEquals(-1, meta().get("pageTrashRetentionDays").asInt());
        }
    }

    // ------------------------------------------------- GET /v1/me/profile

    @Test
    void profileWithoutAnAuthenticatedUserIsA401Problem() throws Exception {
        mockMvc.perform(get("/v1/me/profile")).andExpect(status().isUnauthorized());
    }

    @Test
    void profileIsTheCallersOwnAccountForAPersonAndAnAgent() throws Exception {
        user.setFullName("Alice Atkins");
        user.setEmailAddress("alice@example.com");
        user.setLocale("en_US");
        user.setTimeZone("America/Denver");

        JsonNode body = profile();

        assertEquals("alice", body.get("userName").asString());
        assertEquals("Alice A.", body.get("screenName").asString());
        assertEquals("Alice Atkins", body.get("fullName").asString());
        assertEquals("alice@example.com", body.get("emailAddress").asString());
        assertEquals("en_US", body.get("locale").asString());
        assertEquals("America/Denver", body.get("timeZone").asString());
        assertEquals("PERSON", body.get("kind").asString());

        user.setKind(User.Kind.AGENT);
        assertEquals("AGENT", profile().get("kind").asString());
    }

    /** The Profile page hides the password fields for a single-sign-on account (the JSP's ssoBound). */
    @Test
    void profileSaysWhetherTheAccountIsSingleSignOnBound() throws Exception {
        assertFalse(profile().get("sso").asBoolean());

        user.setSsoSubject("google|123");
        assertTrue(profile().get("sso").asBoolean());
    }

    private JsonNode profile() throws Exception {
        return json(mockMvc.perform(get("/v1/me/profile").requestAttr(AUTHENTICATED_USER, user))
                .andExpect(status().isOk()));
    }

    // ------------------------------------------------- GET /v1/me/weblogs

    @Test
    void myWeblogsWithoutAnAuthenticatedUserIsA401Problem() throws Exception {
        mockMvc.perform(get("/v1/me/weblogs")).andExpect(status().isUnauthorized());
    }

    /** The main menu's rows: sorted by handle, with the facts each row prints and the actions as stored. */
    @Test
    void myWeblogsCarriesEachRowsFactsAndCountsPendingCommentsOnlyForAModerator() throws Exception {
        givenGlobalAdmin(false);
        Weblog travels = weblog("travels", "Travels");
        travels.setAbout("  Notes from the road.  ");
        travels.setEditorTheme("custom");
        Weblog food = weblog("food", "Food");
        givenMembership(travels, List.of(WeblogPermission.ADMIN, WeblogPermission.POST));
        givenMembership(food, List.of(WeblogPermission.EDIT_DRAFT));
        givenCounts(travels, 12, 3);
        givenCounts(food, 4, 9);

        JsonNode view = json(mockMvc.perform(get("/v1/me/weblogs").requestAttr(AUTHENTICATED_USER, user))
                .andExpect(status().isOk()));

        JsonNode rows = view.get("weblogs");
        assertEquals(List.of("food", "travels"), rows.valueStream().map(r -> r.get("handle").asString()).toList());
        JsonNode first = rows.get(1);
        assertEquals("Travels", first.get("name").asString());
        assertEquals("Notes from the road.", first.get("about").asString());
        assertEquals("custom", first.get("theme").asString());
        assertEquals("https://blog.example.com/travels", first.get("absoluteUrl").asString());
        assertEquals("ADMIN", first.get("permission").asString());
        assertEquals(List.of("admin", "post"), first.get("actions").valueStream().map(a -> a.asString()).toList());
        assertTrue(first.get("canModerate").asBoolean());
        assertEquals(12, first.get("entryCount").asLong());
        assertEquals(3, first.get("pendingComments").asLong());
        JsonNode limited = rows.get(0);
        assertFalse(limited.get("canModerate").asBoolean());
        assertEquals(0, limited.get("pendingComments").asLong(), "a limited member has no comments to moderate");
        assertFalse(limited.has("about"), limited.toString());
        assertFalse(view.has("siteSpace"), "only a site admin gets the site space");
    }

    /**
     * Moderation needs the weblog POST permission, and a weblog's creator holds "admin" alone (the Members page
     * grants single-action rows too), so the row answers by implication, never by looking for the literal "post".
     */
    @Test
    void canModerateFollowsTheImpliedPostPermissionNotTheLiteralAction() throws Exception {
        givenGlobalAdmin(false);
        Weblog creator = weblog("creator", "Creator's");
        Weblog limited = weblog("limited", "Limited");
        Weblog nothing = weblog("nothing", "Nothing");
        givenMembership(creator, List.of(WeblogPermission.ADMIN));
        givenMembership(limited, List.of(WeblogPermission.EDIT_DRAFT));
        givenMembership(nothing, List.of());
        givenCounts(creator, 0, 2);
        givenCounts(limited, 0, 5);
        givenCounts(nothing, 0, 5);

        JsonNode rows = json(mockMvc.perform(get("/v1/me/weblogs").requestAttr(AUTHENTICATED_USER, user)))
                .get("weblogs");

        assertTrue(rows.get(0).get("canModerate").asBoolean(), "an admin-only row is a creator's row");
        assertEquals(2, rows.get(0).get("pendingComments").asLong());
        assertFalse(rows.get(1).get("canModerate").asBoolean());
        assertFalse(rows.get(2).get("canModerate").asBoolean(), "a row granting nothing implies nothing");
    }

    @Test
    void aSiteAdminGetsTheSiteSpaceHandleAndAPinnedTokenDoesNot() throws Exception {
        givenGlobalAdmin(true);
        Weblog site = weblog(Weblog.SITE_SPACE_HANDLE, "Site");
        when(weblogManager.getOrCreateSiteSpace(user)).thenReturn(site);

        assertEquals(Weblog.SITE_SPACE_HANDLE, json(mockMvc.perform(
                get("/v1/me/weblogs").requestAttr(AUTHENTICATED_USER, user))).get("siteSpace").asString());

        authenticateWithToken(new ApiPrincipal("alice", "travels", ApiToken.Role.ADMIN, "token-1"));
        assertFalse(json(mockMvc.perform(get("/v1/me/weblogs").requestAttr(AUTHENTICATED_USER, user)))
                .has("siteSpace"));
    }

    @Test
    void aPinnedTokenSeesOnlyItsWeblogInTheMenu() throws Exception {
        authenticateWithToken(new ApiPrincipal("alice", "food", ApiToken.Role.POST, "token-1"));
        givenGlobalAdmin(false);
        Weblog food = weblog("food", "Food");
        givenMembership(weblog("travels", "Travels"), List.of(WeblogPermission.ADMIN));
        givenMembership(food, List.of(WeblogPermission.POST));
        givenCounts(food, 1, 0);

        JsonNode rows = json(mockMvc.perform(get("/v1/me/weblogs").requestAttr(AUTHENTICATED_USER, user)))
                .get("weblogs");

        assertEquals(1, rows.size());
        assertEquals("food", rows.get(0).get("handle").asString());
    }

    private void givenCounts(Weblog weblog, long entries, long pending) throws WebloggerException {
        when(entryManager.getEntryCount(weblog)).thenReturn(entries);
        when(commentManager.countComments(weblog, com.wikantik.pressroom.pojos.ReaderComment.Status.PENDING))
                .thenReturn(pending);
    }

    /** A field added to the profile later is a deliberate change, and is never a credential. */
    @Test
    void theProfileHoldsExactlyTheseMembersAndNoCredential() {
        assertEquals(List.of("userName", "screenName", "fullName", "emailAddress", "locale", "timeZone", "kind", "sso"),
                Arrays.stream(MetaDtos.ProfileView.class.getRecordComponents())
                        .map(java.lang.reflect.RecordComponent::getName).toList());
    }

    // ---------------------------------------------------------------- helpers

    /** {@code GET /v1/me} as {@link #user}; asserts 200 and returns the body. */
    private JsonNode me() throws Exception {
        return json(mockMvc.perform(get("/v1/me").requestAttr(AUTHENTICATED_USER, user))
                .andExpect(status().isOk()));
    }

    private static Weblog weblog(String handle, String name) {
        Weblog weblog = new Weblog();
        weblog.setHandle(handle);
        weblog.setName(name);
        return weblog;
    }

    /**
     * Makes {@code weblog} one {@link #user} belongs to with {@code actions},
     * and answers the weblog lookup for its handle.
     */
    private void givenMembership(Weblog weblog, List<String> actions) throws WebloggerException {
        when(weblogManager.getWeblogByHandle(weblog.getHandle())).thenReturn(weblog);
        List<WeblogPermission> existing = new ArrayList<>(userManager.getWeblogPermissions(user));
        existing.add(new WeblogPermission(weblog, user, actions));
        when(userManager.getWeblogPermissions(user)).thenReturn(existing);
    }

    private static JsonNode json(ResultActions result) throws Exception {
        return new ObjectMapper().readTree(result.andReturn().getResponse().getContentAsString());
    }

    /** Only a check for the global {@code admin} action answers {@code isAdmin}. */
    private void givenGlobalAdmin(boolean isAdmin) throws WebloggerException {
        when(userManager.checkPermission(any(), eq(user))).thenReturn(false);
        when(userManager.checkPermission(
                argThat(p -> p instanceof GlobalPermission g
                        && g.getActionsAsList().equals(List.of(GlobalPermission.ADMIN))),
                eq(user)))
                .thenReturn(isAdmin);
    }

    private static void authenticateWithBasicAuth() {
        var principal = org.springframework.security.core.userdetails.User
                .withUsername("alice").password("n/a").authorities(List.of()).build();
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, null, List.of()));
    }

    private static void authenticateWithToken(ApiPrincipal apiPrincipal) {
        var auth = new UsernamePasswordAuthenticationToken(apiPrincipal.userName(), null, List.of());
        auth.setDetails(apiPrincipal);
        SecurityContextHolder.getContext().setAuthentication(auth);
    }
}
