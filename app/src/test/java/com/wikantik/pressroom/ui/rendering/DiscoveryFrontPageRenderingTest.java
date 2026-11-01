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
import com.wikantik.pressroom.pojos.RuntimeConfigProperty;
import com.wikantik.pressroom.pojos.User;
import com.wikantik.pressroom.pojos.Weblog;
import com.wikantik.pressroom.pojos.WeblogEntry;
import com.wikantik.pressroom.ui.rendering.servlets.RenderingTestSupport;
import com.wikantik.pressroom.ui.rendering.velocity.InvalidReferenceRecorder;
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
            "fd-discovery-blogs", "fd-discovery-different", "fd-discovery-all");

    /**
     * What the two templates and the macros they call leave unresolved on
     * these renders, each with its reason.
     */
    private static final Set<String> EXPECTED_REFERENCES = Set.of(
            "frontpage:_header|standard: $fdMetaTitle", // null unless a static page has a meta title; tested with #if in frontpage _header.vm
            "frontpage:_header|standard: $fdPageTitle", // unset on the discovery page, which keeps the bare site name; tested with #elseif in frontpage _header.vm
            "frontpage:_header|standard: $fdReading", // unset except on a page whose title is the h1; tested with #if in frontpage _header.vm
            "weblog.vm: $colophonFacts.copyrightText", // null unless the weblog sets copyright text; tested with $utils.isNotEmpty
            "weblog.vm: $colophonFacts.privacyUrl", // null unless a privacy URL is configured; tested with $utils.isNotEmpty
            "weblog.vm: $model.page", // null when the view is not a static page; tested with #if / negated in #set
            "weblog.vm: $model.tags", // null when the view is not a tag view; tested with #if/#elseif
            "weblog.vm: $model.weblogCategory", // null when the view is not a category view; tested with #if/#elseif
            "weblog.vm: $seoBusinessJson", // null when the weblog has no business profile; tested with #if
            "weblog.vm: $utils.businessJsonLd()", // returns null when the weblog has no business profile; the result is tested with #if($seoBusinessJson)
            "weblog.vm: $weblog.analyticsSiteId", // null unless analytics is configured; tested with $utils.isNotEmpty
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
            assertEquals(4, count(section(body, "fd-discovery-popular"), "class=\"fd-discovery-post"),
                    theme + ": four popular posts:\n" + body);
            assertEquals(3, count(section(body, "fd-discovery-blogs"), "class=\"fd-discovery-card"),
                    theme + ": three blog cards:\n" + body);
            assertEquals(3, count(section(body, "fd-discovery-different"), "class=\"fd-discovery-post"),
                    theme + ": three different posts:\n" + body);
            String all = section(body, "fd-discovery-all");
            for (Weblog blog : blogs) {
                assertTrue(all.contains(">" + blog.getName() + "</a>"), theme + ": all blogs lists "
                        + blog.getName() + ":\n" + all);
            }
            for (String key : List.of("Most read lately", "Popular right now", "Explore the blogs",
                    "Something different", "All blogs")) {
                assertTrue(body.contains(key), theme + ": the heading " + key);
            }
            assertTrue(body.contains("class=\"places-been\" hidden data-places"),
                    theme + ": the places list's container, hidden until the reader's own list fills it:\n"
                            + body);
            assertFalse(body.contains("$discovery") || body.contains("$text") || body.contains("#show"),
                    theme + ": literal Velocity:\n" + body);
        }
        assertTrue(root("frontpage").contains("<body class=\"fd\">"), "frontpage's own template, fd chrome");
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
