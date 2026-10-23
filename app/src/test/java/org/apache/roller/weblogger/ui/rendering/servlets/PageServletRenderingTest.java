package org.apache.roller.weblogger.ui.rendering.servlets;

import org.apache.roller.weblogger.TestUtils;
import org.apache.roller.weblogger.business.WeblogManager;
import org.apache.roller.weblogger.pojos.CustomTemplateRendition;
import org.apache.roller.weblogger.pojos.TemplateRendition.RenditionType;
import org.apache.roller.weblogger.pojos.TemplateRendition.TemplateLanguage;
import org.apache.roller.weblogger.pojos.ThemeTemplate.ComponentType;
import org.apache.roller.weblogger.pojos.User;
import org.apache.roller.weblogger.pojos.Weblog;
import org.apache.roller.weblogger.pojos.WeblogCategory;
import org.apache.roller.weblogger.pojos.WeblogEntry;
import org.apache.roller.weblogger.pojos.WeblogEntry.PubStatus;
import org.apache.roller.weblogger.pojos.WeblogTemplate;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.Date;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Renders real Velocity themes through the real PageServlet against the
 * Testcontainers database. First tests ever on the anonymous-visitor path.
 */
class PageServletRenderingTest {

    private User user;
    private Weblog weblog;

    @BeforeEach
    void setUp() throws Exception {
        RenderingTestSupport.ensureRenderingRuntime();
        RenderingTestSupport.clearRenderCaches();
        user = TestUtils.setupUser("pagerenderuser");
        weblog = TestUtils.setupWeblog("pagerenderblog", user);
        TestUtils.endSession(true);
    }

    @AfterEach
    void tearDown() throws Exception {
        TestUtils.teardownWeblog(weblog.getId());
        TestUtils.teardownUser(user.getUserName());
        TestUtils.endSession(true);
    }

    @Test
    void frontPageRendersPublishedEntry() throws Exception {
        WeblogEntry entry = TestUtils.setupWeblogEntry("smoke-entry", weblog, user);
        TestUtils.endSession(true);

        MockHttpServletRequest request = RenderingTestSupport
                .anonymousGet("/roller-ui/rendering/page", "/pagerenderblog");
        MockHttpServletResponse response = RenderingTestSupport
                .execute(RenderingTestSupport.pageServlet(), request);

        assertEquals(200, response.getStatus());
        assertTrue(response.getContentType().startsWith("text/html"),
                "front page must be html but was: " + response.getContentType());
        String body = response.getContentAsString();
        assertTrue(body.contains("smoke-entry"),
                "entry title must appear on the front page:\n" + body);
        // journal's front page is a reading list -- title, date marginalia and
        // an optional summary, never the entry body (that is the permalink's
        // job, covered by permalinkRendersEntryContent). The link is what
        // proves the entry row rendered.
        assertTrue(body.contains("/entry/smoke-entry\""),
                "the entry row must link to its permalink:\n" + body);
    }

    @Test
    void permalinkRendersEntryContent() throws Exception {
        TestUtils.setupWeblogEntry("perma-entry", weblog, user);
        TestUtils.endSession(true);

        MockHttpServletRequest request = RenderingTestSupport
                .anonymousGet("/roller-ui/rendering/page", "/pagerenderblog/entry/perma-entry");
        MockHttpServletResponse response = RenderingTestSupport
                .execute(RenderingTestSupport.pageServlet(), request);

        assertEquals(200, response.getStatus());
        String body = response.getContentAsString();
        assertTrue(body.contains("perma-entry"), "permalink must show the entry:\n" + body);
        assertTrue(body.contains("blah blah entry"), "permalink must show the text:\n" + body);
    }

    @Test
    void anImageShortcodeExpandsOnTheLivePermalink() throws Exception {
        // End-to-end render-pipeline proof for the unconditional shortcode
        // expander: a real uploaded image (hawk.jpg, 500w, so only the 480
        // rung exists), a real entry naming NO plugins, the real PageServlet.
        org.apache.roller.weblogger.pojos.MediaFile image =
                TestUtils.setupImageMediaFile(weblog, "page-hawk.jpg");
        WeblogEntry entry = TestUtils.setupWeblogEntry("shortcode-entry", weblog, user);
        entry.setText("before [image id=" + image.getId() + " caption=\"A hawk\"] after");
        org.apache.roller.weblogger.TestUtils.weblogger()
                .getWeblogEntryManager().saveWeblogEntry(entry);
        TestUtils.endSession(true);

        MockHttpServletRequest request = RenderingTestSupport
                .anonymousGet("/roller-ui/rendering/page", "/pagerenderblog/entry/shortcode-entry");
        MockHttpServletResponse response = RenderingTestSupport
                .execute(RenderingTestSupport.pageServlet(), request);

        assertEquals(200, response.getStatus());
        String body = response.getContentAsString();
        assertTrue(body.contains("<figure class=\"shortcode-image\">"),
                "the [image] shortcode must expand on the live permalink:\n" + body);
        assertTrue(decoded(body).contains(image.getId() + "?w=480 480w"),
                "srcset must point the ladder rung at the media-resource URL:\n" + body);
        assertTrue(body.contains("<figcaption>A hawk</figcaption>"), body);
        assertFalse(body.contains("[image id="),
                "the raw shortcode text must not leak to readers:\n" + body);
    }

    @Test
    void draftEntryPermalinkIsNotFound() throws Exception {
        Weblog managed = TestUtils.getManagedWebsite(weblog);
        WeblogCategory category = managed.getWeblogCategories().iterator().next();
        TestUtils.setupWeblogEntry("draft-entry", category, PubStatus.DRAFT, managed, user);
        TestUtils.endSession(true);

        MockHttpServletRequest request = RenderingTestSupport
                .anonymousGet("/roller-ui/rendering/page", "/pagerenderblog/entry/draft-entry");
        MockHttpServletResponse response = RenderingTestSupport
                .execute(RenderingTestSupport.pageServlet(), request);

        assertEquals(404, response.getStatus(), "a draft must never render publicly");
    }

    /**
     * A trashed entry's permalink must behave exactly like an unknown one --
     * a clean 404, not a 500 and not a rendered page. {@code TRASHED} is a
     * status {@code WeblogEntry.isPublished()} and every reader-facing query
     * path already excludes the same way {@code DRAFT} does (see
     * {@code draftEntryPermalinkIsNotFound} above), but that is exactly the
     * kind of "obviously fine" default a later change could quietly narrow --
     * this is what would catch it.
     */
    @Test
    void trashedEntryPermalinkIsNotFound() throws Exception {
        Weblog managed = TestUtils.getManagedWebsite(weblog);
        WeblogCategory category = managed.getWeblogCategories().iterator().next();
        TestUtils.setupWeblogEntry("trashed-entry", category, PubStatus.TRASHED, managed, user);
        TestUtils.endSession(true);

        MockHttpServletRequest request = RenderingTestSupport
                .anonymousGet("/roller-ui/rendering/page", "/pagerenderblog/entry/trashed-entry");
        MockHttpServletResponse response = RenderingTestSupport
                .execute(RenderingTestSupport.pageServlet(), request);

        assertEquals(404, response.getStatus(), "a trashed entry must never render publicly");
    }

    /**
     * Same shape as {@code frontPageRendersPublishedEntry}, with a trashed
     * entry alongside the published one -- so "the trashed entry is absent"
     * cannot be satisfied by the whole front page rendering empty.
     */
    @Test
    void frontPageExcludesTrashedEntry() throws Exception {
        Weblog managed = TestUtils.getManagedWebsite(weblog);
        WeblogCategory category = managed.getWeblogCategories().iterator().next();
        TestUtils.setupWeblogEntry("live-entry", weblog, user);
        TestUtils.setupWeblogEntry("gone-entry", category, PubStatus.TRASHED, managed, user);
        TestUtils.endSession(true);

        MockHttpServletRequest request = RenderingTestSupport
                .anonymousGet("/roller-ui/rendering/page", "/pagerenderblog");
        MockHttpServletResponse response = RenderingTestSupport
                .execute(RenderingTestSupport.pageServlet(), request);

        assertEquals(200, response.getStatus());
        String body = response.getContentAsString();
        assertTrue(body.contains("live-entry"),
                "the published entry must be on the front page:\n" + body);
        assertFalse(body.contains("gone-entry"),
                "a trashed entry must not be on the front page:\n" + body);
    }

    @Test
    void unknownWeblogIsNotFound() throws Exception {
        MockHttpServletRequest request = RenderingTestSupport
                .anonymousGet("/roller-ui/rendering/page", "/nosuchblog");
        MockHttpServletResponse response = RenderingTestSupport
                .execute(RenderingTestSupport.pageServlet(), request);

        assertEquals(404, response.getStatus());
    }

    @Test
    void categoryPageRendersItsEntries() throws Exception {
        TestUtils.setupWeblogEntry("category-entry", weblog, user);
        TestUtils.endSession(true);

        MockHttpServletRequest request = RenderingTestSupport
                .anonymousGet("/roller-ui/rendering/page", "/pagerenderblog/category/General");
        MockHttpServletResponse response = RenderingTestSupport
                .execute(RenderingTestSupport.pageServlet(), request);

        assertEquals(200, response.getStatus());
        assertTrue(response.getContentAsString().contains("category-entry"),
                "category page must list the entry");
    }

    /**
     * Every theme's permalink links its tags at {@code /<handle>/tags/<tag>},
     * and no bundled theme ships a tagsIndex template, so those links used
     * to 404. The default template now answers them, and its pager filters by
     * the tag: the tagged entry is listed and the untagged one is not. An
     * unknown tag is still a 404.
     */
    @Test
    void aTagArchiveListsOnlyTheTaggedEntries() throws Exception {
        WeblogEntry tagged = TestUtils.setupWeblogEntry("tag-archive-hit", weblog, user);
        tagged.addTag("harbour");
        TestUtils.weblogger().getWeblogEntryManager().saveWeblogEntry(tagged);
        TestUtils.setupWeblogEntry("tag-archive-miss", weblog, user);
        TestUtils.endSession(true);

        MockHttpServletResponse response = RenderingTestSupport.execute(
                RenderingTestSupport.pageServlet(), RenderingTestSupport.anonymousGet(
                        "/roller-ui/rendering/page", "/pagerenderblog/tags/harbour"));

        assertEquals(200, response.getStatus());
        String body = response.getContentAsString();
        assertTrue(body.contains("tag-archive-hit"), "the tagged entry must be listed:\n" + body);
        assertFalse(body.contains("tag-archive-miss"),
                "an entry without the tag must not be listed:\n" + body);

        MockHttpServletResponse unknown = RenderingTestSupport.execute(
                RenderingTestSupport.pageServlet(), RenderingTestSupport.anonymousGet(
                        "/roller-ui/rendering/page", "/pagerenderblog/tags/no-such-tag"));
        assertEquals(404, unknown.getStatus(), "an unknown tag is still not found");
    }

    /**
     * {@code #showAutodiscoveryLinks} built its tag-feed title from
     * {@code $model.tags.toString()} -- the model holds a {@code List}, so
     * the title in the head literally read "Entries tagged [travel] (Atom)",
     * brackets and all, and a multi-tag URL added the list's ", " separator
     * inside them. The category branch beside it had the other half of the
     * problem: {@code $model.weblogCategory.name} comes off a wrapper that
     * returns the raw pojo value, unescaped, into an attribute.
     */
    @Test
    void theTagFeedDiscoveryTitleIsAPlainJoinedList() throws Exception {
        WeblogEntry entry = TestUtils.setupWeblogEntry("tagged-entry", weblog, user);
        entry.addTag("travel");
        TestUtils.weblogger().getWeblogEntryManager().saveWeblogEntry(entry);
        TestUtils.endSession(true);
        // The ?tags= parameter on a custom page (WeblogPageRequest) reaches a
        // non-null $model.tags with nothing but this macro on the page.
        saveCustomTemplate("tagfeed", "<head>#showAutodiscoveryLinks($model.weblog)</head>");

        MockHttpServletRequest request = RenderingTestSupport
                .anonymousGet("/roller-ui/rendering/page", "/pagerenderblog/page/tagfeed");
        request.setParameter("tags", "travel");
        MockHttpServletResponse response = RenderingTestSupport
                .execute(RenderingTestSupport.pageServlet(), request);

        assertEquals(200, response.getStatus());
        String body = response.getContentAsString();
        assertTrue(body.contains("title=\"Entries tagged travel (Atom)\""),
                "the tag feed's discovery title must read as prose, not as a "
                        + "Java List.toString():\n" + body);
        assertFalse(body.contains("tagged [travel]"),
                "the List's brackets must not reach the head:\n" + body);
    }

    /**
     * The category branch of the same macro. A category name reaches the
     * template raw ({@code WeblogCategoryWrapper#getName} returns
     * {@code this.pojo.getName()}), and it lands inside a double-quoted
     * {@code title} attribute -- so a name carrying a quote or an '&' broke
     * the tag outright.
     */
    @Test
    void theCategoryFeedDiscoveryTitleEscapesTheRawCategoryName() throws Exception {
        WeblogCategory category = TestUtils.setupWeblogCategory(
                TestUtils.getManagedWebsite(weblog), "Tools & Toys");
        TestUtils.setupWeblogEntry("cat-entry", category,
                TestUtils.getManagedWebsite(weblog), user);
        TestUtils.endSession(true);

        MockHttpServletRequest request = RenderingTestSupport
                .anonymousGet("/roller-ui/rendering/page",
                        "/pagerenderblog/category/Tools & Toys");
        MockHttpServletResponse response = RenderingTestSupport
                .execute(RenderingTestSupport.pageServlet(), request);

        assertEquals(200, response.getStatus());
        String body = response.getContentAsString();
        assertTrue(body.contains("title=\"Entries for category Tools &amp; Toys (Atom)\""),
                "the category feed's discovery title must escape the raw "
                        + "wrapper value:\n" + body);
    }

    /** A CUSTOM template gets {@code link = name} and is served at
     * {@code /<handle>/page/<link>}, on a shared-theme weblog too. */
    private void saveCustomTemplate(String name, String contents) throws Exception {
        WeblogManager wmgr = TestUtils.weblogger().getWeblogManager();
        WeblogTemplate template = new WeblogTemplate();
        template.setWeblog(TestUtils.getManagedWebsite(weblog));
        template.setAction(ComponentType.CUSTOM);
        template.setName(name);
        template.setLink(name);
        template.setDescription(name);
        template.setHidden(false);
        template.setNavbar(false);
        template.setLastModified(new Date());
        wmgr.saveTemplate(template);
        CustomTemplateRendition rendition =
                new CustomTemplateRendition(template, RenditionType.STANDARD);
        rendition.setTemplate(contents);
        rendition.setTemplateLanguage(TemplateLanguage.VELOCITY);
        wmgr.saveTemplateRendition(rendition);
        TestUtils.weblogger().flush();
        TestUtils.endSession(true);
        RenderingTestSupport.clearRenderCaches();
    }

    @Test
    void unknownCategoryIsNotFound() throws Exception {
        MockHttpServletRequest request = RenderingTestSupport
                .anonymousGet("/roller-ui/rendering/page", "/pagerenderblog/category/Nope");
        MockHttpServletResponse response = RenderingTestSupport
                .execute(RenderingTestSupport.pageServlet(), request);

        assertEquals(404, response.getStatus());
    }

    /**
     * Development-only path: with theme reload on, a request for a
     * non-custom-themed weblog (the seeded fixture runs the shared "journal"
     * theme) must still render normally after re-loading the theme from
     * disk. {@code themeReload} defaults to false in the test config
     * ({@code themes.reload.mode} is unset in roller-custom.properties), so
     * this is the only test that actually reaches that branch.
     */
    @Test
    void themeReloadDoesNotBreakRenderingOfANonCustomTheme() throws Exception {
        TestUtils.setupWeblogEntry("reload-entry", weblog, user);
        TestUtils.endSession(true);

        PageServlet servlet = RenderingTestSupport.pageServlet();
        servlet.themeReload = true;

        MockHttpServletRequest request = RenderingTestSupport
                .anonymousGet("/roller-ui/rendering/page", "/pagerenderblog");
        MockHttpServletResponse response = RenderingTestSupport.execute(servlet, request);

        assertEquals(200, response.getStatus());
        assertTrue(response.getContentAsString().contains("reload-entry"),
                "the page must still render after the theme-reload check runs");
    }

    /**
     * The rendered page with HTML entities decoded.
     *
     * <p>The sanitizer entity-encodes characters inside attribute values --
     * {@code ?w=480} is serialized as {@code ?w&#61;480}. A browser decodes
     * those before it parses a srcset or resolves a URL, so the page works;
     * an assertion written against the author's literal text would not.
     * Decoding first keeps the assertion about meaning rather than about the
     * serializer's choices.
     */
    private static String decoded(String body) {
        return org.apache.commons.text.StringEscapeUtils.unescapeHtml4(body);
    }
}
