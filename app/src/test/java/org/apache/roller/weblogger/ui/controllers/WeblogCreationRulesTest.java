package org.apache.roller.weblogger.ui.controllers;

import java.util.List;
import java.util.Set;
import org.apache.roller.weblogger.business.UserManager;
import org.apache.roller.weblogger.business.WeblogManager;
import org.apache.roller.weblogger.pojos.User;
import org.apache.roller.weblogger.pojos.Weblog;
import org.apache.roller.weblogger.pojos.WeblogPermission;
import org.apache.roller.weblogger.ui.controllers.core.CreateWeblogBean;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class WeblogCreationRulesTest {

    private UserManager users;
    private WeblogManager weblogs;

    @BeforeEach
    void setUp() {
        users = mock(UserManager.class);
        weblogs = mock(WeblogManager.class);
    }

    private WeblogCreationRules.Context context(boolean allowed, boolean groupBlogging) {
        return new WeblogCreationRules.Context(allowed, groupBlogging, "A-Za-z0-9",
                Set.of("api", "page", "search", "roller-ui", "mine"), List.of("journal", "travel"), users, weblogs);
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
}
