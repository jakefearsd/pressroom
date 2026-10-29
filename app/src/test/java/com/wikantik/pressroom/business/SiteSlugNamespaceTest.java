package com.wikantik.pressroom.business;

import com.wikantik.pressroom.TestUtils;
import com.wikantik.pressroom.WebloggerException;
import com.wikantik.pressroom.pojos.User;
import com.wikantik.pressroom.pojos.Weblog;
import com.wikantik.pressroom.pojos.WeblogPage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A site page is served at {@code /<slug>}, so its slug shares one namespace
 * with blog handles and the application's protected paths. The rule is for the
 * site space only; an ordinary blog's page keeps its freedom.
 */
class SiteSlugNamespaceTest {

    private User admin;
    private Weblog travel;
    private Weblog site;

    @BeforeEach
    void setUp() throws Exception {
        TestUtils.setupWeblogger();
        admin = TestUtils.setupUser("siteSlugAdmin");
        travel = TestUtils.setupWeblog("travel", admin);
        site = TestUtils.weblogger().getWeblogManager().getOrCreateSiteSpace(
                TestUtils.weblogger().getUserManager().getUserByUserName(admin.getUserName()));
        TestUtils.weblogger().flush();
        TestUtils.endSession(true);
    }

    @AfterEach
    void tearDown() throws Exception {
        WeblogManager mgr = TestUtils.weblogger().getWeblogManager();
        Weblog s = mgr.getSiteSpace();
        if (s != null) {
            mgr.removeWeblog(s);
        }
        TestUtils.teardownWeblog(travel.getId());
        TestUtils.teardownUser(admin.getUserName());
        TestUtils.endSession(true);
    }

    private WeblogPage pageOn(Weblog target, String slug) throws Exception {
        WeblogPage page = new WeblogPage();
        page.setWeblog(TestUtils.getManagedWebsite(target));
        page.setSlug(slug);
        page.setTitle("Title " + slug);
        page.setContent("Body");
        page.setStatus(WeblogPage.PubStatus.PUBLISHED);
        return page;
    }

    private SiteSlugClashException clash(String slug) throws Exception {
        return assertThrows(SiteSlugClashException.class,
                () -> TestUtils.weblogger().getWeblogPageManager().savePage(pageOn(site, slug)));
    }

    @Test
    void aSlugEqualToABlogHandleIsRefusedInAnyCase() throws Exception {
        SiteSlugClashException lower = clash("travel");
        assertEquals("pageEdit.error.slugIsWeblog", lower.getMessageKey());
        assertEquals("travel", lower.getArgument());
        assertEquals("pageEdit.error.slugIsWeblog", clash("Travel").getMessageKey());
        assertEquals("pageEdit.error.slugIsWeblog", clash("TRAVEL").getMessageKey());
    }

    @Test
    void aProtectedPathIsRefusedInAnyCase() throws Exception {
        for (String path : new String[] {"pressroom-ui", "api", "sitemap.xml", "API"}) {
            SiteSlugClashException e = clash(path);
            assertEquals("pageEdit.error.slugIsReservedPath", e.getMessageKey(), path);
            assertEquals(path, e.getArgument());
        }
    }

    @Test
    void aSlugStartingWithSitemapDashIsRefusedAsAReservedPath() throws Exception {
        for (String slug : new String[] {"sitemap-travel.xml", "Sitemap-Site-Pages.xml", "sitemap-x"}) {
            assertEquals("pageEdit.error.slugIsReservedPath", clash(slug).getMessageKey(), slug);
        }
    }

    @Test
    void aLocaleShapedSlugIsRefusedOnTheSiteSpace() {
        for (String slug : new String[] {"ai", "en_US", "HI"}) {
            WebloggerException e = assertThrows(WebloggerException.class,
                    () -> TestUtils.weblogger().getWeblogPageManager().savePage(pageOn(site, slug)));
            assertTrue(e.getMessage().contains("reserved"), slug);
        }
    }

    @Test
    void anUnclashingSlugSavesAndKeepsItsOwnSlugOnResave() throws Exception {
        WeblogPage page = pageOn(site, "about-us");
        TestUtils.weblogger().getWeblogPageManager().savePage(page);
        TestUtils.weblogger().flush();
        assertNotNull(page.getId());
        TestUtils.weblogger().getWeblogPageManager().savePage(page);
    }

    @Test
    void anOrdinaryBlogsPageMayUseTheSameSlug() throws Exception {
        WeblogPage page = pageOn(travel, "travel");
        TestUtils.weblogger().getWeblogPageManager().savePage(page);
        TestUtils.weblogger().flush();
        assertNotNull(page.getId());
    }

    @Test
    void theSiteSpacesOwnHandleIsRefusedAsASlug() throws Exception {
        SiteSlugClashException e = clash("site-space");
        assertEquals("pageEdit.error.slugIsWeblog", e.getMessageKey());
        assertEquals("site-space", e.getArgument());
        assertEquals("pageEdit.error.slugIsWeblog", clash("Site-Space").getMessageKey());
    }

    @Test
    void aLegacyMixedCaseHandleClashesWithAnyCaseOfTheSlug() throws Exception {
        Weblog legacy = TestUtils.setupWeblog("MixedBlog", admin);
        TestUtils.endSession(true);
        try {
            SiteSlugClashException e = clash("MIXEDBLOG");
            assertEquals("pageEdit.error.slugIsWeblog", e.getMessageKey());
            assertEquals("MixedBlog", e.getArgument());
            assertEquals("pageEdit.error.slugIsWeblog", clash("mixedblog").getMessageKey());
        } finally {
            TestUtils.teardownWeblog(legacy.getId());
            TestUtils.endSession(true);
        }
    }

    @Test
    void aDraftSitePageHoldsItsSlugAgainstANewBlogHandle() throws Exception {
        WeblogPage draft = pageOn(site, "about");
        draft.setStatus(WeblogPage.PubStatus.DRAFT);
        TestUtils.weblogger().getWeblogPageManager().savePage(draft);
        TestUtils.weblogger().flush();
        TestUtils.endSession(true);

        for (String handle : new String[] {"about", "ABOUT"}) {
            com.wikantik.pressroom.ui.controllers.core.CreateWeblogBean bean =
                    new com.wikantik.pressroom.ui.controllers.core.CreateWeblogBean();
            bean.setHandle(handle);
            bean.setName("A weblog");
            bean.setTheme("journal");
            var found = com.wikantik.pressroom.ui.controllers.WeblogCreationRules.check(bean,
                    com.wikantik.pressroom.ui.controllers.WeblogCreationRules.Context
                            .current(TestUtils.weblogger()));
            assertTrue(found.stream().anyMatch(v -> "createWeblog.error.handleIsSitePage".equals(v.messageKey())
                    && v.status() == 409), handle + " -> " + found);
        }
    }
}
