package com.wikantik.pressroom.ui.controllers;

import java.util.List;
import java.util.Set;
import com.wikantik.pressroom.business.UserManager;
import com.wikantik.pressroom.business.WeblogManager;
import com.wikantik.pressroom.business.WeblogPageManager;
import com.wikantik.pressroom.business.Weblogger;
import com.wikantik.pressroom.business.themes.ThemeManager;
import com.wikantik.pressroom.config.WebloggerConfig;
import com.wikantik.pressroom.config.WebloggerRuntimeConfig;
import com.wikantik.pressroom.pojos.User;
import com.wikantik.pressroom.pojos.Weblog;
import com.wikantik.pressroom.pojos.WeblogPage;
import com.wikantik.pressroom.pojos.WeblogPermission;
import com.wikantik.pressroom.ui.controllers.core.CreateWeblogBean;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

class WeblogCreationRulesTest {

    private UserManager users;
    private WeblogManager weblogs;
    private WeblogPageManager pages;

    @BeforeEach
    void setUp() {
        users = mock(UserManager.class);
        weblogs = mock(WeblogManager.class);
        pages = mock(WeblogPageManager.class);
    }

    private WeblogCreationRules.Context context(boolean allowed, boolean groupBlogging) {
        return new WeblogCreationRules.Context(allowed, groupBlogging, "A-Za-z0-9",
                Set.of("api", "page", "search", "pressroom-ui", "mine"), List.of("journal", "travel"), users, weblogs, pages);
    }

    private static CreateWeblogBean bean(String handle) {
        CreateWeblogBean bean = new CreateWeblogBean();
        bean.setHandle(handle);
        bean.setName("A weblog");
        bean.setTheme("journal");
        bean.setLocale("en_US");
        bean.setTimeZone("Europe/Berlin");
        return bean;
    }

    private static User user() {
        User user = new User();
        user.setUserName("jake");
        return user;
    }

    private List<String> keys(CreateWeblogBean bean) throws Exception {
        return WeblogCreationRules.check(bean, context(true, true)).stream()
                .map(WeblogCreationRules.Violation::messageKey).toList();
    }

    @Test
    void aGoodRequestBreaksNoRule() throws Exception {
        assertEquals(List.of(), keys(bean("travels")));
    }

    @Test
    void creationTurnedOffIsA403() throws Exception {
        WeblogCreationRules.Violation v = WeblogCreationRules.mayCreate(user(), context(false, true));
        assertEquals(403, v.status());
        assertEquals("Weblog creation is turned off on this site.", v.detail());
    }

    @Test
    void withoutGroupBloggingAMemberMayNotCreateASecond() throws Exception {
        when(users.getWeblogPermissions(org.mockito.ArgumentMatchers.any(User.class)))
                .thenReturn(List.of(new WeblogPermission()));
        WeblogCreationRules.Violation v = WeblogCreationRules.mayCreate(user(), context(true, false));
        assertEquals("This site allows one weblog per user.", v.detail());
        assertNull(WeblogCreationRules.mayCreate(user(), context(true, true)));
    }

    @Test
    void reservedPathsAreRefusedIgnoringCase() throws Exception {
        for (String handle : List.of("api", "API", "Search", "page", "mine")) {
            assertEquals(List.of("createWeblog.error.reservedHandle"), keys(bean(handle)), handle);
        }
    }

    /**
     * Context.current reads the reserved roots from both protected-URL lists
     * in the static config: comma lists that may carry spaces, empty items
     * and any case, folded into one lower-cased set. So a name a site adds to
     * userProtectedUrls is refused as a handle, whatever its case.
     * Characterisation of the shipped parsing: expected to pass at once.
     */
    @Test
    void theReservedPathsComeFromBothProtectedUrlListsLowerCased() throws Exception {
        Weblogger weblogger = mock(Weblogger.class);
        ThemeManager themes = mock(ThemeManager.class);
        when(themes.getEnabledThemesList()).thenReturn(List.of());
        when(weblogger.getThemeManager()).thenReturn(themes);
        when(weblogger.getUserManager()).thenReturn(users);
        when(weblogger.getWeblogManager()).thenReturn(weblogs);
        WeblogCreationRules.Context context;
        try (MockedStatic<WebloggerConfig> config = mockStatic(WebloggerConfig.class);
             MockedStatic<WebloggerRuntimeConfig> _ = mockStatic(WebloggerRuntimeConfig.class)) {
            config.when(() -> WebloggerConfig.getProperty("rendering.weblogMapper.pressroomProtectedUrls"))
                    .thenReturn(" API, page ,, Search ");
            config.when(() -> WebloggerConfig.getProperty("rendering.weblogMapper.userProtectedUrls"))
                    .thenReturn("Shop, ,about");
            context = WeblogCreationRules.Context.current(weblogger);
        }

        assertEquals(Set.of("api", "page", "search", "shop", "about"), context.reservedPaths());
        CreateWeblogBean shop = bean("SHOP");
        assertEquals(List.of("createWeblog.error.reservedHandle"),
                WeblogCreationRules.check(shop, context).stream()
                        .map(WeblogCreationRules.Violation::messageKey)
                        .filter(key -> key.startsWith("createWeblog.error.") && key.endsWith("Handle"))
                        .toList());
    }

    @Test
    void aBlankOrIllegalOrLongHandleIsRefused() throws Exception {
        assertEquals(List.of("createWeblog.error.invalidHandle"), keys(bean(" ")));
        assertEquals(List.of("createWeblog.error.invalidHandle"), keys(bean("my blog")));
        assertEquals(List.of("createWeblog.error.invalidHandle"), keys(bean("a".repeat(256))));
    }

    @Test
    void aTakenHandleIsAConflict() throws Exception {
        when(weblogs.getWeblogByHandle("travels", null)).thenReturn(new Weblog());
        WeblogCreationRules.Violation v = WeblogCreationRules.check(bean("travels"), context(true, true)).get(0);
        assertEquals(409, v.status());
        assertEquals("handle", v.field());
    }

    /**
     * A disabled weblog still holds its handle (ws_handle_uq), but the
     * one-argument getWeblogByHandle answers only for visible weblogs, so
     * the handle looked free and addWeblog then hit the constraint: an
     * opaque 500 through the API, a generic error on the page.
     */
    @Test
    void aDisabledWeblogsHandleIsTaken() throws Exception {
        Weblog disabled = new Weblog();
        disabled.setHandle("travels");
        disabled.setVisible(Boolean.FALSE);
        when(weblogs.getWeblogByHandle("travels", null)).thenReturn(disabled);

        WeblogCreationRules.Violation v = WeblogCreationRules.check(bean("travels"), context(true, true)).get(0);

        assertEquals(409, v.status());
        assertEquals("createWeblog.error.handleExists", v.messageKey());
    }

    @Test
    void anUnknownOrSitewideThemeIsRefusedWithTheValidIds() throws Exception {
        CreateWeblogBean frontpage = bean("travels");
        frontpage.setTheme("frontpage");
        WeblogCreationRules.Violation v = WeblogCreationRules.check(frontpage, context(true, true)).get(0);
        assertEquals("theme", v.field());
        assertEquals("theme must be one of: journal, travel.", v.detail());
        CreateWeblogBean none = bean("travels");
        none.setTheme(null);
        assertEquals(List.of("createWeblog.error.invalidTheme"), keys(none));
    }

    @Test
    void aMissingNameAndAnUnknownZoneAreRefused() throws Exception {
        CreateWeblogBean bean = bean("travels");
        bean.setName("  ");
        bean.setTimeZone("Mars/Olympus_Mons");
        assertEquals(List.of("createWeblog.error.nameRequired", "websiteSettings.timeZone.invalid"), keys(bean));
    }

    @Test
    void aNameOrDescriptionOverTheColumnLimitIsRefusedAtExactlyTheLimit() throws Exception {
        CreateWeblogBean atLimit = bean("travels");
        atLimit.setName("n".repeat(WeblogCreationRules.MAX_TEXT));
        atLimit.setDescription("d".repeat(WeblogCreationRules.MAX_TEXT));
        assertEquals(List.of(), keys(atLimit));

        CreateWeblogBean over = bean("travels");
        over.setName("n".repeat(WeblogCreationRules.MAX_TEXT + 1));
        over.setDescription("d".repeat(WeblogCreationRules.MAX_TEXT + 1));
        List<WeblogCreationRules.Violation> found = WeblogCreationRules.check(over, context(true, true));

        assertEquals(List.of("name", "description"), found.stream().map(WeblogCreationRules.Violation::field).toList());
        assertTrue(found.stream().allMatch(v -> v.status() == 400 && "createWeblog.error.tooLong".equals(v.messageKey())));
    }

    @Test
    void aLocaleTheSettingsFormDoesNotOfferIsRefusedAndABlankOneIsLeftToTheDefault() throws Exception {
        CreateWeblogBean odd = bean("travels");
        odd.setLocale("xx_YY");
        assertEquals(List.of("websiteSettings.locale.invalid"), keys(odd));

        CreateWeblogBean blank = bean("travels");
        blank.setLocale("  ");
        blank.setTimeZone(null);
        assertEquals(List.of(), keys(blank));
    }

    @Test
    void aUserWithNoWeblogYetMayCreateTheirFirstEvenWithoutGroupBlogging() throws Exception {
        when(users.getWeblogPermissions(org.mockito.ArgumentMatchers.any(User.class))).thenReturn(List.of());

        assertNull(WeblogCreationRules.mayCreate(user(), context(true, false)));
    }

    private void sitePage(String slug, WeblogPage.PubStatus status) throws Exception {
        Weblog site = new Weblog();
        site.setHandle(Weblog.SITE_SPACE_HANDLE);
        site.setSiteSpace(true);
        WeblogPage page = new WeblogPage();
        page.setSlug(slug);
        page.setStatus(status);
        when(weblogs.getSiteSpace()).thenReturn(site);
        when(pages.getPages(site)).thenReturn(List.of(page));
    }

    @Test
    void aHandleHeldByADraftSitePageIsRefusedAs409InAnyCase() throws Exception {
        sitePage("about", WeblogPage.PubStatus.DRAFT);
        for (String handle : new String[] {"about", "ABOUT"}) {
            List<WeblogCreationRules.Violation> found =
                    WeblogCreationRules.check(bean(handle), context(true, true));
            assertTrue(found.stream().anyMatch(v -> "createWeblog.error.handleIsSitePage".equals(v.messageKey())
                    && v.status() == 409 && "handle".equals(v.field())), handle + " -> " + found);
        }
    }

    @Test
    void aHandleNoSitePageHoldsIsNotRefused() throws Exception {
        sitePage("about", WeblogPage.PubStatus.PUBLISHED);
        assertFalse(keys(bean("contact")).contains("createWeblog.error.handleIsSitePage"));
    }
}
