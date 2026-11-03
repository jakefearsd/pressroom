/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  The ASF licenses this file to You
 * under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.  For additional information regarding
 * copyright in this work, please see the NOTICE file in the top level
 * directory of this distribution.
 */
package com.wikantik.pressroom.ui.rendering;

import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import jakarta.servlet.DispatcherType;

import com.wikantik.pressroom.TestUtils;
import com.wikantik.pressroom.business.PropertiesManager;
import com.wikantik.pressroom.business.WeblogEntryManager;
import com.wikantik.pressroom.business.WeblogManager;
import com.wikantik.pressroom.business.WebloggerProvider;
import com.wikantik.pressroom.business.jpa.QueryRecorder;
import com.wikantik.pressroom.pojos.RuntimeConfigProperty;
import com.wikantik.pressroom.pojos.User;
import com.wikantik.pressroom.pojos.Weblog;
import com.wikantik.pressroom.pojos.WeblogEntry;
import com.wikantik.pressroom.ui.rendering.servlets.RenderingTestSupport;
import com.wikantik.pressroom.ui.rendering.velocity.InvalidReferenceRecorder;
import com.wikantik.pressroom.util.HTMLSanitizer;
import org.apache.commons.text.StringEscapeUtils;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The discovery front page at {@code /}: {@code FrontDoor}'s forward to the
 * site space's {@code discovery} template, rendered in the {@code frontpage}
 * theme (its own template, fd chrome) and in {@code journal} (the shared
 * fallback, {@code templates/weblog/discovery.vm}). Both carry the same
 * section markers, which the browser suite pins too.
 *
 * <p>Each test refreshes the picker after building its fixture, so the
 * snapshot it renders holds that fixture. Context {@code /pressroom}; every
 * reference the two templates leave unresolved is pinned below.
 */
class DiscoveryFrontPageRenderingTest {

    private static final List<String> THEMES = List.of("frontpage", "journal");
    private static final String DISCOVERY = "/pressroom-ui/rendering/page/site-space/page/discovery";
    private static final String PAGE_SERVLET = "/pressroom-ui/rendering/page";
    private static final String SITE_NAME = "Discovery Harbour";
    private static final String ABSOLUTE_HOST = "http://localhost:8080";
    private static final Pattern HREF = Pattern.compile("href=\"([^\"]*)\"");
    private static final List<String> SECTIONS = List.of("fd-discovery-headline", "fd-discovery-popular",
            "fd-discovery-latest", "fd-discovery-blogs", "fd-discovery-different", "fd-discovery-all");

    /**
     * What the two templates and the macros they call leave unresolved on
     * these renders, each with its reason.
     */
    private static final Set<String> EXPECTED_REFERENCES = Set.of(
            "templates/weblog/discovery.vm|standard: $fdPost.featuredImage", // null for a post with no featured image; #set after a false default, so $fdImg stays false
            "templates/weblog/discovery.vm|standard: $fdImg", // that #set of a null featured image; tested with #if($fdImg)
            "templates/weblog/discovery.vm|standard: fdImg", // the same #set, as the recorder names its target
            "templates/weblog/discovery.vm|standard: $model.weblog.newsletterListUuid", // null unless the site space names a newsletter list; tested with $utils.isNotEmpty
            "weblog.vm: $colophonFacts.copyrightText", // null unless the weblog sets copyright text; tested with $utils.isNotEmpty
            "weblog.vm: $colophonFacts.privacyUrl", // null unless a privacy URL is configured; tested with $utils.isNotEmpty
            "weblog.vm: $model.page", // null when the view is not a static page; tested with #if / negated in #set
            "weblog.vm: $model.tags", // null when the view is not a tag view; tested with #if/#elseif
            "weblog.vm: $model.weblogCategory", // null when the view is not a category view; tested with #if/#elseif
            "weblog.vm: $seoBusinessJson", // null when the weblog has no business profile; tested with #if
            "weblog.vm: $utils.businessJsonLd()", // returns null when the weblog has no business profile; the result is tested with #if($seoBusinessJson)
            "weblog.vm: $analytics.websiteId()", // null unless the weblog is registered with Umami; tested with $utils.isNotEmpty
            "weblog.vm: $trackerSiteId", // #set of a null website id; tested with $utils.isNotEmpty
            "weblog.vm: trackerSiteId", // #set of a null website id; tested with $utils.isNotEmpty
            "weblog.vm: seoBusinessJson" // #set of a null business JSON-LD; tested with #if
    );

    @BeforeAll
    static void clearInvalidReferenceRecorder() {
        InvalidReferenceRecorder.clear();
    }

    @AfterAll
    static void onlyTheExpectedReferencesWereUnresolved() {
        StrictReferences.assertRecordedExactly("DiscoveryFrontPageRenderingTest", EXPECTED_REFERENCES);
    }

    private User admin;
    private final List<Weblog> blogs = new ArrayList<>();
    private final Map<String, String> originalProperties = new LinkedHashMap<>();
    private WeblogRequestMapper mapper;

    @BeforeEach
    void setUp() throws Exception {
        RenderingTestSupport.ensureRenderingRuntime();
        RenderingTestSupport.clearRenderCaches();

        admin = TestUtils.setupUser("discfpadmin");
        TestUtils.weblogger().getUserManager().grantRole("admin", admin);
        TestUtils.endSession(true);
        TestUtils.weblogger().getWeblogManager().getOrCreateSiteSpace(
                TestUtils.weblogger().getUserManager().getUserByUserName(admin.getUserName()));
        TestUtils.weblogger().flush();
        TestUtils.endSession(true);

        setProperty("site.name", SITE_NAME);
        setProperty("site.frontpage.page", "");
        setProperty("site.frontpage.weblog.handle", "");

        WebloggerProvider provider = mock(WebloggerProvider.class);
        when(provider.isBootstrapped()).thenReturn(true);
        when(provider.getWeblogger()).thenReturn(TestUtils.weblogger());
        mapper = new WeblogRequestMapper(provider, TestUtils.weblogger());
    }

    @AfterEach
    void tearDown() throws Exception {
        if (!originalProperties.isEmpty()) {
            PropertiesManager pmgr = TestUtils.weblogger().getPropertiesManager();
            Map<String, RuntimeConfigProperty> config = pmgr.getProperties();
            originalProperties.forEach((name, value) -> config.get(name).setValue(value));
            pmgr.saveProperties(config);
            TestUtils.weblogger().flush();
            TestUtils.endSession(true);
        }
        WeblogManager weblogs = TestUtils.weblogger().getWeblogManager();
        Weblog current = weblogs.getSiteSpace();
        if (current != null) {
            weblogs.removeWeblog(current);
        }
        TestUtils.endSession(true);
        for (Weblog blog : blogs) {
            TestUtils.teardownWeblog(blog.getId());
        }
        TestUtils.endSession(true);
        TestUtils.teardownUser(admin.getUserName());
        TestUtils.endSession(true);
        // the next class must not render this fixture's snapshot
        TestUtils.weblogger().getDiscoveryManager().refresh();
        TestUtils.endSession(true);
        RenderingTestSupport.clearRenderCaches();
    }

    @Test
    void rendersAllSections() throws Exception {
        fiveBlogsOfFour();
        for (String theme : THEMES) {
            String body = root(theme);
            for (String marker : SECTIONS) {
                assertTrue(body.contains("class=\"" + marker + "\""), theme + ": no " + marker + ":\n" + body);
            }
            assertEquals(1, count(section(body, "fd-discovery-headline"), "class=\"fd-discovery-post"),
                    theme + ": one headline:\n" + body);
            assertEquals(5, count(section(body, "fd-discovery-popular"), "class=\"fd-discovery-post"),
                    theme + ": five popular posts:\n" + body);
            // twenty posts, fewer than the page holds: Latest lists every post the
            // other sections leave out, so each of the twenty is shown exactly once
            int shown = count(section(body, "fd-discovery-headline"), "class=\"fd-discovery-post")
                    + count(section(body, "fd-discovery-popular"), "class=\"fd-discovery-post")
                    + count(section(body, "fd-discovery-different"), "class=\"fd-discovery-post")
                    + count(section(body, "fd-discovery-blogs"), "<li><a href=");
            assertEquals(20 - shown, count(section(body, "fd-discovery-latest"), "class=\"fd-discovery-post"),
                    theme + ": latest lists what no other section shows:\n" + body);
            Matcher title = Pattern.compile(">(Post \\d\\.\\d)</a>").matcher(body);
            List<String> titles = new ArrayList<>();
            while (title.find()) {
                titles.add(title.group(1));
            }
            assertEquals(20, titles.size(), theme + ": every post once: " + titles);
            assertEquals(20, new java.util.HashSet<>(titles).size(), theme + ": no post twice: " + titles);
            assertEquals(3, count(section(body, "fd-discovery-blogs"), "class=\"fd-discovery-card"),
                    theme + ": three blog cards:\n" + body);
            assertEquals(3, count(section(body, "fd-discovery-different"), "class=\"fd-discovery-post"),
                    theme + ": three different posts:\n" + body);
            String all = section(body, "fd-discovery-all");
            for (Weblog blog : blogs) {
                assertTrue(all.contains(">" + blog.getName() + "</a>"), theme + ": all blogs lists "
                        + blog.getName() + ":\n" + all);
            }
            for (String key : List.of("Popular right now", "Latest", "Explore the blogs",
                    "Something different", "All blogs")) {
                assertTrue(body.contains(key), theme + ": the heading " + key);
            }
            // no Umami in the test tier, so nothing is ranked: the newest post
            // leads, and the label must not claim it is the most read
            String lead = section(body, "fd-discovery-headline");
            assertTrue(lead.contains(">Newest</h2>"), theme + ": an unranked headline is labelled Newest:\n" + lead);
            assertFalse(body.contains("Most read lately"), theme + ": no traffic, so nothing is \"most read\"");
            assertTrue(body.contains("class=\"places-been\" hidden data-places"),
                    theme + ": the places list's container, hidden until the reader's own list fills it:\n"
                            + body);
            assertTrue(body.contains(">Places you've been</h2>"), theme + ": the places list's heading:\n" + body);
            assertEquals(1, count(body, "<h1"), theme + ": one h1:\n" + body);
            assertTrue(Pattern.compile("<h1[^>]*><a href=\"[^\"]*\">" + Pattern.quote(SITE_NAME) + "</a></h1>")
                    .matcher(body).find(), theme + ": the site name is the page's h1 (a list page):\n" + body);
            assertFalse(body.contains("$discovery") || body.contains("$text") || body.contains("#show")
                    || body.contains("$fd") || body.contains("$d"), theme + ": literal Velocity:\n" + body);
            assertTrue(body.contains("<body class=\"fd-front\">"),
                    theme + ": one front page, whatever the site's theme:\n" + body);

            // every post shows its blog, a machine-readable date and a reading time
            String latest = section(body, "fd-discovery-latest");
            assertTrue(Pattern.compile("<time datetime=\"\\d{4}-\\d{2}-\\d{2}\">").matcher(latest).find(),
                    theme + ": a dated post:\n" + latest);
            assertTrue(latest.contains("1 min read"), theme + ": a reading time:\n" + latest);
            // no featured images in this fixture: each post gets its blog's tile instead of an <img>
            assertFalse(latest.contains("<img"), theme + ":\n" + latest);
            assertTrue(latest.contains("class=\"fd-media fd-tile\""), theme + ": the hue tile:\n" + latest);

            // three house slots hold the ad positions until AdSense is approved; no ad code yet
            for (String slot : List.of("a", "b", "c")) {
                assertEquals(1, count(body, "data-slot=\"" + slot + "\""), theme + ": slot " + slot + ":\n" + body);
            }
            assertFalse(body.contains("adsbygoogle") || body.contains("googlesyndication"),
                    theme + ": no ad code until ads are configured:\n" + body);
            // the house slots spotlight blogs the shelves do not show
            String shelves = section(body, "fd-discovery-blogs");
            Matcher spot = Pattern.compile("data-slot=\"[abc]\"[\\s\\S]*?<a class=\"fd-spot-name\" href=\"[^\"]*\">([^<]*)</a>")
                    .matcher(body);
            int spots = 0;
            while (spot.find()) {
                spots++;
                assertFalse(shelves.contains(">" + spot.group(1) + "</a>"),
                        theme + ": a spotlight repeats a shelf: " + spot.group(1));
            }
            assertEquals(3, spots, theme + ": three spotlights:\n" + body);
        }
    }

    /**
     * The newsletter sign-up (rail card and band) is the site space's own
     * list: shown when Weblog Settings names one, absent when it does not,
     * since there is nothing to subscribe to.
     */
    @Test
    void theNewsletterSignupFollowsTheSiteList() throws Exception {
        fiveBlogsOfFour();
        String without = root("frontpage");
        assertFalse(without.contains("class=\"subscribe-form-slot\""), "no list, no form:\n" + without);

        WeblogManager weblogs = TestUtils.weblogger().getWeblogManager();
        Weblog site = weblogs.getSiteSpace();
        site.setNewsletterListUuid("6f1c2c1e-0000-4000-8000-000000000001");
        weblogs.saveWeblog(site);
        TestUtils.weblogger().flush();
        TestUtils.endSession(true);
        RenderingTestSupport.clearRenderCaches();

        String with = root("frontpage");
        assertEquals(2, count(with, "class=\"subscribe-form-slot\" data-list-uuid=\"6f1c2c1e-0000-4000-8000-000000000001\""),
                "the rail card and the band:\n" + with);
        assertTrue(with.contains("id=\"fd-newsletter\""), "the band the masthead's Subscribe button jumps to");
    }

    @Test
    void emptySiteRendersWithoutError() throws Exception {
        TestUtils.weblogger().getDiscoveryManager().refresh();
        TestUtils.endSession(true);
        assertTrue(TestUtils.weblogger().getDiscoveryManager().selectFrontPage().allBlogs().isEmpty(),
                "precondition: no eligible weblog is left in the database by another test");
        for (String theme : THEMES) {
            String body = root(theme);
            assertTrue(body.contains("Nothing has been published here yet."),
                    theme + ": the directory's empty message (siteDirectory.empty):\n" + body);
            assertFalse(body.contains("fd-discovery-popular"), theme + ": no empty sections:\n" + body);
        }
    }

    /**
     * Saving strips the markup from a weblog name, so what reaches the page
     * is whatever survived that, a quote and an ampersand included: escaped
     * exactly once, by the wrapper, wherever the page names the blog.
     */
    @Test
    void frontPageEscapesBlogNames() throws Exception {
        Weblog evil = blog("discfpevil", "<script>x</script>\" & Co");
        post(evil, "discfp-evil-post", "Evil post", 1);
        refresh();
        String stored = TestUtils.weblogger().getWeblogManager().getWeblogByHandle("discfpevil").getName();
        TestUtils.endSession(true);
        assertTrue(stored.contains("\"") && stored.contains("&"), "the fixture still carries " + stored);
        String once = ">" + StringEscapeUtils.escapeHtml4(stored) + "</a>";
        for (String theme : THEMES) {
            String body = root(theme);
            assertFalse(body.contains("<script>x"), theme + ":\n" + body);
            assertTrue(section(body, "fd-discovery-all").contains(once), theme + ": " + once + "\n" + body);
            assertTrue(section(body, "fd-discovery-headline").contains(once), theme + ": " + once + "\n" + body);
        }
    }

    /**
     * A blog's tagline is its owner's text on a page every reader of the site
     * sees. The setter strips tags but keeps a '<' with no '>' after it, and
     * the wrapper's plain-text reduction then decodes entities, so a stored
     * "< &lt;img ...&gt;" came out as a live img tag with the sanitizer ON;
     * with it off the wrapper returns the value untouched. Either way the
     * result is text, not HTML (HTMLSanitizer.toPlainText), so the page escapes
     * it, as every blog theme already does (code review, 2026-11-03).
     */
    @Test
    void taglinesAreEscapedWhateverTheSanitizerSetting() throws Exception {
        fiveBlogsOfFour();
        // a leading '<' and no '>': Utilities.removeHTML keeps it unchanged
        String hostile = "< &lt;img src=x onerror=alert(1)&gt;";
        for (Weblog blog : blogs) {
            Weblog managed = TestUtils.getManagedWebsite(blog);
            managed.setTagline(hostile);
            TestUtils.weblogger().getWeblogManager().saveWeblog(managed);
            TestUtils.weblogger().flush();
            TestUtils.endSession(true);
        }
        refresh();
        String stored = TestUtils.getManagedWebsite(blogs.get(0)).getTagline();
        TestUtils.endSession(true);
        assertEquals(hostile, stored, "the setter keeps the unclosed '<'");
        Boolean previous = HTMLSanitizer.xssEnabled;
        try {
            for (Boolean sanitizing : List.of(Boolean.TRUE, Boolean.FALSE)) {
                HTMLSanitizer.xssEnabled = sanitizing;
                String escaped = StringEscapeUtils.escapeHtml4(HTMLSanitizer.conditionallyToPlainText(stored));
                for (String theme : THEMES) {
                    String body = root(theme);
                    String where = theme + ", sanitizing " + sanitizing;
                    assertFalse(body.contains("<img src=x"), where + ": a tagline's markup is live:\n" + body);
                    assertTrue(section(body, "fd-discovery-blogs").contains("<p class=\"fd-about\">" + escaped + "</p>"),
                            where + ": the shelf shows the tagline as text:\n" + body);
                    assertTrue(body.contains("<p class=\"fd-spot-about\">" + escaped + "</p>"),
                            where + ": so does the house slot:\n" + body);
                }
            }
        } finally {
            HTMLSanitizer.xssEnabled = previous;
        }
    }

    /**
     * The page names a blog's post count in up to three places (the sidebar,
     * its shelf, the all-blogs list), and each read was a COUNT query: 2N+3
     * per render. Each blog is counted once per render.
     */
    @Test
    void eachBlogIsCountedOncePerRender() throws Exception {
        fiveBlogsOfFour();
        useSiteTheme("journal");
        renderRoot(); // cold: the snapshot and lookups are read here
        TestUtils.endSession(false);

        List<String> queries = QueryRecorder.during(() -> renderRoot());

        List<String> counts = QueryRecorder.sqlOn(queries, "weblogentry").stream()
                .filter(q -> q.toUpperCase(java.util.Locale.ROOT).contains("COUNT(")).toList();
        assertEquals(blogs.size(), counts.size(), "one count per blog: " + counts);
        String body = renderRoot();
        for (String marker : List.of("fd-discovery-blogs", "fd-discovery-all")) {
            assertTrue(section(body, marker).contains("4 posts"),
                    marker + " still shows each blog's four posts:\n" + section(body, marker));
        }
        assertTrue(body.contains("<span class=\"fd-count\">4</span>"), "and so does the sidebar:\n" + body);
    }

    @Test
    void entryTitlesNotDoubleEscaped() throws Exception {
        Weblog blog = blog("discfptitles", "Titles");
        // stored escaped, as the editor saves every title (EntryBean.copyTo)
        post(blog, "discfp-amp", "A &amp; B", 0);
        refresh();
        for (String theme : THEMES) {
            String headline = section(root(theme), "fd-discovery-headline");
            assertTrue(headline.contains(">A &amp; B</a>"), theme + ": the newest post leads:\n" + headline);
            assertFalse(headline.contains("&amp;amp;"), theme + ":\n" + headline);
        }
    }

    @Test
    void canonicalIsSiteRoot() throws Exception {
        for (String theme : THEMES) {
            String body = root(theme);
            assertTrue(body.contains("<link rel=\"canonical\" href=\"" + ABSOLUTE_HOST + "/pressroom/\">"),
                    theme + ": / is the page's one address:\n" + body);
            assertFalse(body.contains("page/discovery"), theme + ": the internal address leaks:\n" + body);
        }
    }

    /**
     * Every blog and entry link carries the context path and is one the
     * mapper routes. The links are absolute (a blog on its own custom domain
     * is reached on that host), so the host is stripped before the check.
     */
    @Test
    void linksCarryContextPath() throws Exception {
        fiveBlogsOfFour();
        for (String theme : THEMES) {
            String body = root(theme);
            List<String> hrefs = new ArrayList<>();
            for (String marker : SECTIONS) {
                Matcher href = HREF.matcher(section(body, marker));
                while (href.find()) {
                    hrefs.add(href.group(1));
                }
            }
            assertTrue(hrefs.size() > 20, theme + ": the sections' links: " + hrefs);
            for (String href : hrefs) {
                String path = href.startsWith(ABSOLUTE_HOST) ? href.substring(ABSOLUTE_HOST.length()) : href;
                assertTrue(path.startsWith("/pressroom/"), theme + ": " + href);
                MockHttpServletRequest request = new MockHttpServletRequest("GET", path);
                request.setContextPath("/pressroom");
                assertTrue(mapper.handleRequest(request, new MockHttpServletResponse()),
                        theme + ": the mapper routes " + href);
            }
        }
    }

    // ------------------------------------------------------------- helpers

    /** {@code /} as index.jsp serves it: FrontDoor's choice, forwarded to the page servlet. */
    private String root(String theme) throws Exception {
        useSiteTheme(theme);
        return renderRoot();
    }

    /** {@link #root} in whatever theme the site space has now. */
    private String renderRoot() throws Exception {
        String theme = TestUtils.weblogger().getWeblogManager().getSiteSpace().getEditorTheme();
        String path = FrontDoor.forwardPath(TestUtils.weblogger());
        assertEquals(DISCOVERY, path);
        MockHttpServletRequest request = RenderingTestSupport.anonymousGet(PAGE_SERVLET,
                path.substring(PAGE_SERVLET.length()));
        request.setDispatcherType(DispatcherType.FORWARD);
        MockHttpServletResponse response = RenderingTestSupport.execute(RenderingTestSupport.pageServlet(), request);
        String body = response.getContentAsString();
        assertEquals(200, response.getStatus(), theme + ":\n" + body);
        TestUtils.endSession(true);
        return body;
    }

    private void useSiteTheme(String theme) throws Exception {
        Weblog managed = TestUtils.weblogger().getWeblogManager().getSiteSpace();
        managed.setEditorTheme(theme);
        TestUtils.weblogger().getWeblogManager().saveWeblog(managed);
        TestUtils.weblogger().flush();
        TestUtils.endSession(true);
        RenderingTestSupport.clearRenderCaches();
    }

    /** One section of the page, from its opening tag to the end of that section. */
    private static String section(String body, String marker) {
        int start = body.indexOf("class=\"" + marker + "\"");
        assertTrue(start >= 0, "no " + marker + ":\n" + body);
        int end = body.indexOf("</section>", start);
        return body.substring(start, end < 0 ? body.length() : end);
    }

    private static int count(String haystack, String needle) {
        int n = 0;
        for (int at = haystack.indexOf(needle); at >= 0; at = haystack.indexOf(needle, at + 1)) {
            n++;
        }
        return n;
    }

    /** Five blogs of four posts: enough for every section to fill without sharing a post. */
    private void fiveBlogsOfFour() throws Exception {
        int minutes = 10;
        for (int b = 0; b < 5; b++) {
            Weblog blog = blog("discfp" + b, "Discovery Blog " + b);
            for (int e = 0; e < 4; e++) {
                post(blog, "discfp-" + b + "-" + e, "Post " + b + "." + e, minutes++);
            }
        }
        refresh();
    }

    private Weblog blog(String handle, String name) throws Exception {
        Weblog blog = TestUtils.setupWeblog(handle, admin);
        TestUtils.endSession(true);
        Weblog managed = TestUtils.getManagedWebsite(blog);
        managed.setName(name);
        TestUtils.weblogger().getWeblogManager().saveWeblog(managed);
        TestUtils.weblogger().flush();
        TestUtils.endSession(true);
        blogs.add(managed);
        return managed;
    }

    private void post(Weblog blog, String anchor, String title, int minutesAgo) throws Exception {
        Weblog managed = TestUtils.getManagedWebsite(blog);
        WeblogEntry entry = new WeblogEntry();
        entry.setTitle(title);
        entry.setText("Body of " + anchor);
        entry.setAnchor(anchor);
        Timestamp when = new Timestamp(System.currentTimeMillis() - minutesAgo * 60_000L);
        entry.setPubTime(when);
        entry.setUpdateTime(when);
        entry.setStatus(WeblogEntry.PubStatus.PUBLISHED);
        entry.setWebsite(managed);
        entry.setCreatorUserName(admin.getUserName());
        entry.setCategory(managed.getWeblogCategories().iterator().next());
        WeblogEntryManager entries = TestUtils.weblogger().getWeblogEntryManager();
        entries.saveWeblogEntry(entry);
        TestUtils.weblogger().flush();
        TestUtils.endSession(true);
    }

    private static void refresh() throws Exception {
        TestUtils.weblogger().getDiscoveryManager().refresh();
        TestUtils.endSession(true);
    }

    private void setProperty(String name, String value) throws Exception {
        PropertiesManager pmgr = TestUtils.weblogger().getPropertiesManager();
        Map<String, RuntimeConfigProperty> config = pmgr.getProperties();
        originalProperties.computeIfAbsent(name, key -> config.get(key).getValue());
        config.get(name).setValue(value);
        pmgr.saveProperties(config);
        TestUtils.weblogger().flush();
        TestUtils.endSession(true);
    }
}
