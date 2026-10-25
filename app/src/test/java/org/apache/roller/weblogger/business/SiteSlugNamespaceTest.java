package org.apache.roller.weblogger.business;

import org.apache.roller.weblogger.TestUtils;
import org.apache.roller.weblogger.pojos.User;
import org.apache.roller.weblogger.pojos.Weblog;
import org.apache.roller.weblogger.pojos.WeblogPage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

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
        for (String path : new String[] {"roller-ui", "api", "sitemap.xml", "API"}) {
            SiteSlugClashException e = clash(path);
            assertEquals("pageEdit.error.slugIsReservedPath", e.getMessageKey(), path);
            assertEquals(path, e.getArgument());
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
}
