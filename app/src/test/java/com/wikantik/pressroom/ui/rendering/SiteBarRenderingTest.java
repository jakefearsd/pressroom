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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import jakarta.servlet.DispatcherType;

import com.wikantik.pressroom.TestUtils;
import com.wikantik.pressroom.business.PropertiesManager;
import com.wikantik.pressroom.business.WeblogEntryManager;
import com.wikantik.pressroom.business.WeblogManager;
import com.wikantik.pressroom.business.themes.ThemeManager;
import com.wikantik.pressroom.pojos.RuntimeConfigProperty;
import com.wikantik.pressroom.pojos.User;
import com.wikantik.pressroom.pojos.Weblog;
import com.wikantik.pressroom.pojos.WeblogEntry;
import com.wikantik.pressroom.pojos.WeblogPage;
import com.wikantik.pressroom.pojos.WeblogTheme;
import com.wikantik.pressroom.ui.rendering.servlets.RenderingTestSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The site bar: the strip above every bundled theme's own header that names
 * the site, links its front page and lists its blogs. Before 2026-11-12 a
 * blog's only way to the front page was one small link at the foot of the
 * page, under everything else. Rendered through the real page and search
 * servlets; each test refreshes the picker after building its fixture.
 *
 * <p>The bar is the showSiteBar macro of weblog.vm, called by each bundled
 * theme's four templates right after the skip link; its rules come from the
 * showContentAssets macro every bundled head already calls.
 */
class SiteBarRenderingTest {

    private static final String PAGE_SERVLET = "/pressroom-ui/rendering/page";
    private static final String SEARCH_SERVLET = "/pressroom-ui/rendering/search";
    private static final String SITE_NAME = "Sitebar & Sons";
    private static final String CUSTOM = "custom (never used here)";
    private static final List<String> THEMES = List.of("journal", "travel", "portfolio");
    private static final Pattern BAR = Pattern.compile("<nav class=\"site-bar\"[^>]*>(.*?)</nav>", Pattern.DOTALL);
    private static final Pattern HOME = Pattern.compile(
            "<p class=\"more-from-site-home\"><a href=\"([^\"]+)\">");

    private User admin;
    private final List<Weblog> blogs = new ArrayList<>();
    private final Map<String, String> originalProperties = new LinkedHashMap<>();

    @BeforeEach
    void setUp() throws Exception {
        RenderingTestSupport.ensureRenderingRuntime();
        RenderingTestSupport.clearRenderCaches();
        admin = TestUtils.setupUser("sitebaradmin");
        TestUtils.weblogger().getUserManager().grantRole("admin", admin);
        TestUtils.endSession(true);
        TestUtils.weblogger().getWeblogManager().getOrCreateSiteSpace(
                TestUtils.weblogger().getUserManager().getUserByUserName(admin.getUserName()));
        TestUtils.weblogger().flush();
        TestUtils.endSession(true);
        setProperty("site.name", SITE_NAME);
        setProperty("site.frontpage.page", "");
        setProperty("site.frontpage.weblog.handle", "");
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
        Weblog site = weblogs.getSiteSpace();
        if (site != null) {
            weblogs.removeWeblog(site);
        }
        TestUtils.endSession(true);
        for (Weblog blog : blogs) {
            TestUtils.teardownWeblog(blog.getId());
        }
        TestUtils.endSession(true);
        TestUtils.teardownUser(admin.getUserName());
        TestUtils.endSession(true);
        // the next class must not render this fixture's snapshot
        refresh();
        RenderingTestSupport.clearRenderCaches();
    }

    /**
     * Every view of every bundled theme: the bar is the first thing after the
     * skip link, above the theme's own header; it links the front page by the
     * site's name (escaped once) and lists every blog, this one marked.
     */
    @Test
    void everyBlogPageOpensWithTheSiteBar() throws Exception {
        Weblog current = threeBlogs();
        savePage(current, "about-us", "About this blog");
        for (String theme : THEMES) {
            useTheme(current, theme);
            Map<String, String> pages = new LinkedHashMap<>();
            pages.put("home", page("/" + current.getHandle() + "/"));
            pages.put("permalink", page("/" + current.getHandle() + "/entry/sitebar-a-0"));
            pages.put("static page", page("/" + current.getHandle() + "/about-us"));
            pages.put("search results", search("/" + current.getHandle(), "harbour"));
            for (Map.Entry<String, String> view : pages.entrySet()) {
                String label = theme + " " + view.getKey();
                String body = view.getValue();
                Matcher bar = BAR.matcher(body);
                assertTrue(bar.find(), label + ": no site bar:\n" + body);
                assertFalse(bar.find(), label + ": one site bar only:\n" + body);
                bar.reset();
                bar.find();
                String inner = bar.group(1);
                int skip = body.indexOf("Skip to content");
                assertTrue(skip >= 0 && skip < bar.start() && bar.start() < body.indexOf("<header"),
                        label + ": after the skip link, above the theme's header:\n" + body);
                Matcher home = HOME.matcher(body);
                assertTrue(home.find(), label + ": the footer's link home:\n" + body);
                assertTrue(inner.contains("<a class=\"site-bar-home\" href=\"" + home.group(1) + "\">"),
                        label + ": the bar links the same front page as the footer:\n" + inner);
                assertTrue(inner.contains("<span class=\"site-bar-name\">Sitebar &amp; Sons</span>"),
                        label + ": by the site's name, escaped once:\n" + inner);
                assertTrue(inner.contains("<span class=\"site-bar-mark\" aria-hidden=\"true\">S</span>"),
                        label + ": with the site's mark:\n" + inner);

                assertTrue(inner.contains("<details class=\"site-bar-blogs\">")
                        && inner.contains("<summary>Blogs</summary>"), label + ": the blogs menu:\n" + inner);
                assertTrue(inner.contains("href=\"" + home.group(1) + "\">Front page</a>"),
                        label + ": the menu starts with the front page:\n" + inner);
                assertEquals(4, count(inner, "<li"), label + ": the front page and three blogs:\n" + inner);
                assertTrue(Pattern.compile("<a href=\"[^\"]*/" + current.getHandle()
                        + "/\" aria-current=\"true\"[^>]*>(<span[^>]*></span>)?Bar Blog A</a>").matcher(inner).find(),
                        label + ": this blog is marked:\n" + inner);
                assertEquals(1, count(inner, "aria-current"), label + ": and only this one:\n" + inner);
                assertTrue(inner.contains("Bar Blog B</a>") && inner.contains("Bar Blog C</a>"),
                        label + ": the other blogs are named:\n" + inner);

                int headEnd = body.indexOf("</head>");
                int rules = body.indexOf(".site-bar {");
                assertTrue(rules >= 0 && rules < headEnd, label + ": the bar's rules are in the head:\n" + body);
                assertFalse(body.contains("$sb") || body.contains("#showSiteBar") || body.contains("$discovery"),
                        label + ": literal Velocity:\n" + body);
            }
        }
    }

    /** One blog: nothing to choose between, so the bar is the link home alone. */
    @Test
    void aSiteOfOneBlogHasNoBlogsMenu() throws Exception {
        Weblog only = blog("sitebarsolo", "Bar Solo");
        post(only, "sitebar-solo-0", "Solo post", 10);
        refresh();
        for (String theme : THEMES) {
            useTheme(only, theme);
            Matcher bar = BAR.matcher(page("/" + only.getHandle() + "/"));
            assertTrue(bar.find(), theme + ": the bar still links the front page");
            assertTrue(bar.group(1).contains("class=\"site-bar-home\""), theme + ":\n" + bar.group(1));
            assertFalse(bar.group(1).contains("site-bar-blogs"), theme + ": no menu of one:\n" + bar.group(1));
        }
    }

    /** The site's own pages are the front door's side of the site: their header already is the site. */
    @Test
    void theSiteSpaceHasNoSiteBar() throws Exception {
        threeBlogs();
        Weblog site = TestUtils.weblogger().getWeblogManager().getSiteSpace();
        savePage(site, "house-rules", "House rules");
        for (String theme : THEMES) {
            useTheme(site, theme);
            // a site page is reached only as FrontDoor or the mapper forwards it
            MockHttpServletRequest request = RenderingTestSupport.anonymousGet(PAGE_SERVLET,
                    "/" + site.getHandle() + "/house-rules");
            request.setDispatcherType(DispatcherType.FORWARD);
            MockHttpServletResponse response = RenderingTestSupport.execute(RenderingTestSupport.pageServlet(),
                    request);
            String body = response.getContentAsString();
            assertEquals(200, response.getStatus(), theme + ":\n" + body);
            TestUtils.endSession(true);
            assertTrue(body.contains("Body of house-rules"), theme + ": the site page itself:\n" + body);
            assertFalse(body.contains("<nav class=\"site-bar\""), theme + ": a site page has no bar:\n" + body);
        }
    }

    /** Blog A (returned, the current blog) and blogs B and C, one post each. */
    private Weblog threeBlogs() throws Exception {
        Weblog first = null;
        int minutes = 10;
        for (String letter : List.of("A", "B", "C")) {
            Weblog blog = blog("sitebar" + letter.toLowerCase(java.util.Locale.ROOT), "Bar Blog " + letter);
            post(blog, "sitebar-" + letter.toLowerCase(java.util.Locale.ROOT) + "-0", "Post " + letter, minutes++);
            first = first == null ? blog : first;
        }
        refresh();
        return first;
    }

    private static void savePage(Weblog weblog, String slug, String title) throws Exception {
        WeblogPage page = new WeblogPage();
        page.setWeblog(TestUtils.getManagedWebsite(weblog));
        page.setSlug(slug);
        page.setTitle(title);
        page.setContent("Body of " + slug);
        page.setStatus(WeblogPage.PubStatus.PUBLISHED);
        page.setShowInNav(true);
        page.setNavOrder(1);
        TestUtils.weblogger().getWeblogPageManager().savePage(page);
        TestUtils.weblogger().flush();
        TestUtils.endSession(true);
    }

    private void useTheme(Weblog blog, String theme) throws Exception {
        Weblog managed = TestUtils.getManagedWebsite(blog);
        if (CUSTOM.equals(theme)) {
            ThemeManager themes = TestUtils.weblogger().getThemeManager();
            themes.importTheme(managed, themes.getTheme("journal"), false);
            assertEquals(WeblogTheme.CUSTOM, managed.getEditorTheme());
        } else {
            managed.setEditorTheme(theme);
            TestUtils.weblogger().getWeblogManager().saveWeblog(managed);
        }
        TestUtils.weblogger().flush();
        TestUtils.endSession(true);
        RenderingTestSupport.clearRenderCaches();
    }

    private static String page(String pathInfo) throws Exception {
        MockHttpServletResponse response = RenderingTestSupport.execute(RenderingTestSupport.pageServlet(),
                RenderingTestSupport.anonymousGet(PAGE_SERVLET, pathInfo));
        String body = response.getContentAsString();
        assertEquals(200, response.getStatus(), pathInfo + ":\n" + body);
        TestUtils.endSession(true);
        return body;
    }

    private static String search(String base, String query) throws Exception {
        MockHttpServletRequest request = RenderingTestSupport.anonymousGet(SEARCH_SERVLET, base);
        request.setParameter("q", query);
        MockHttpServletResponse response = RenderingTestSupport.execute(RenderingTestSupport.searchServlet(), request);
        String body = response.getContentAsString();
        assertEquals(200, response.getStatus(), "search " + base + ":\n" + body);
        TestUtils.endSession(true);
        return body;
    }

    private static int count(String haystack, String needle) {
        int n = 0;
        for (int at = haystack.indexOf(needle); at >= 0; at = haystack.indexOf(needle, at + 1)) {
            n++;
        }
        return n;
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
        entry.setText("Harbour body of " + anchor);
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
