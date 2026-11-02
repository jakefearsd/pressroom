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

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
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
import org.apache.commons.text.StringEscapeUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * "More from the site" and "places you've been" on every blog: the two
 * macros the colophon macro calls first, so every bundled theme and a
 * forked (database-stored) theme that still calls the colophon get them.
 * Rendered through the real page and search servlets, context
 * {@code /pressroom}; each test refreshes the picker after building its
 * fixture, so the snapshot holds that fixture.
 *
 * <p>The places list's ordering and cap are behaviour of the inline script,
 * pinned in the browser by the discovery browser suite; here the script's
 * source is checked for the cap, the storage key, the try/catch and the
 * absence of any HTML-writing DOM call.
 */
class MoreFromSiteRenderingTest {

    private static final String PAGE_SERVLET = "/pressroom-ui/rendering/page";
    private static final String SEARCH_SERVLET = "/pressroom-ui/rendering/search";
    private static final String ABSOLUTE_HOST = "http://localhost:8080";
    private static final String SITE_NAME = "Morefrom Harbour";
    private static final Path WEBLOG_VM = Path.of("src/main/webapp/WEB-INF/velocity/weblog.vm");
    private static final String CUSTOM = "custom (forked from journal)";
    private static final List<String> THEMES = List.of("journal", "travel", "portfolio", "frontpage", CUSTOM);
    private static final Pattern RECORD = Pattern.compile("<span hidden data-places-record[^>]*>");

    private User admin;
    private final List<Weblog> blogs = new ArrayList<>();
    private final Map<String, String> originalProperties = new LinkedHashMap<>();

    @BeforeEach
    void setUp() throws Exception {
        RenderingTestSupport.ensureRenderingRuntime();
        RenderingTestSupport.clearRenderCaches();
        admin = TestUtils.setupUser("morefromadmin");
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

    @Test
    void blockRendersOnEntryAndListPages() throws Exception {
        Weblog current = threeBlogsOfTwo();
        List<String> others = List.of("Post B0", "Post B1", "Post C0", "Post C1");
        String macro = placesMacro();
        String script = macro.substring(macro.indexOf("<script>"), macro.indexOf("</script>"));
        for (String theme : THEMES) {
            useTheme(current, theme);
            Map<String, String> pages = new LinkedHashMap<>();
            pages.put("home", page("/" + current.getHandle() + "/"));
            pages.put("permalink", page("/" + current.getHandle() + "/entry/morefrom-a-0"));
            pages.put("search results", search("/" + current.getHandle(), "harbour"));
            for (Map.Entry<String, String> view : pages.entrySet()) {
                String label = theme + " " + view.getKey();
                String body = view.getValue();
                String block = block(body, label);
                assertEquals(3, count(block, "class=\"more-from-site-post\""), label + ": three posts:\n" + block);
                int named = 0;
                for (String title : others) {
                    named += block.contains(">" + title + "</a>") ? 1 : 0;
                }
                assertEquals(3, named, label + ": three posts of the other blogs, by title:\n" + block);
                assertTrue(block.contains("More Blog B") || block.contains("More Blog C"),
                        label + ": each post names its blog:\n" + block);
                assertTrue(block.contains("More from " + SITE_NAME), label + ": the heading:\n" + block);
                assertTrue(block.contains("<nav class=\"places-been\" hidden data-places"),
                        label + ": the places list sits in the block, hidden until filled:\n" + block);
                int footer = body.lastIndexOf("<footer");
                int aside = body.indexOf("<aside class=\"more-from-site\"");
                int colophon = body.indexOf("<div class=\"colophon\">");
                assertTrue(footer >= 0 && footer < aside && aside < colophon,
                        label + ": the block is in the footer, just above the colophon:\n" + body);
                assertTrue(RECORD.matcher(body).find(), label + ": the blog is recorded:\n" + body);
                assertTrue(body.contains(script), label + ": the places script reaches the page verbatim "
                        + "(Velocity reads a dollar sign or a hash in it):\n" + body);
                assertFalse(body.contains("$discovery") || body.contains("#showMoreFromSite")
                        || body.contains("#showPlaces") || body.contains("$mfs"),
                        label + ": literal Velocity:\n" + body);
            }
        }
    }

    /**
     * AC 9: the setting hides the other blogs' posts only. Every blog keeps
     * its way back to the front page and the reader's places (the owner:
     * every blog gets navigation home; the opt-out "only stops showing other
     * blogs"), and the visit is still recorded.
     */
    @Test
    void optedOutBlogKeepsTheWayHomeButShowsNoPosts() throws Exception {
        Weblog current = threeBlogsOfTwo();
        Weblog managed = TestUtils.getManagedWebsite(current);
        managed.setShowMoreFromSite(false);
        TestUtils.weblogger().getWeblogManager().saveWeblog(managed);
        TestUtils.weblogger().flush();
        TestUtils.endSession(true);
        refresh();
        for (String theme : THEMES) {
            useTheme(current, theme);
            String body = page("/" + current.getHandle() + "/");
            assertHomeOnly(body, theme);
            assertTrue(RECORD.matcher(body).find(), theme + ": the visit is still recorded:\n" + body);
            assertTrue(body.contains("pressroom.places"), theme + ": the recording script:\n" + body);
        }
        // its posts still reach the other blogs' blocks: B's three come from A's two and C's two
        String other = block(page("/" + blogs.get(1).getHandle() + "/"), "blog B");
        assertTrue(other.contains(">Post A0</a>") || other.contains(">Post A1</a>"),
                "the opted-out blog's posts still appear elsewhere:\n" + other);
    }

    /** The block with no posts: no heading, no list, but the places list and the link home. */
    private static void assertHomeOnly(String body, String label) {
        String block = block(body, label);
        assertFalse(block.contains("more-from-site-title") || block.contains("more-from-site-post"),
                label + ": no posts, and no heading over none:\n" + block);
        assertTrue(block.contains("<nav class=\"places-been\" hidden data-places"),
                label + ": the places list:\n" + block);
        assertTrue(Pattern.compile("<p class=\"more-from-site-home\"><a href=\"[^\"]+\">").matcher(block).find(),
                label + ": the link home:\n" + block);
    }

    @Test
    void blockNeverShowsOwnBlog() throws Exception {
        Weblog current = threeBlogsOfTwo();
        for (String theme : THEMES) {
            useTheme(current, theme);
            String block = block(page("/" + current.getHandle() + "/entry/morefrom-a-1"), theme);
            assertFalse(block.contains("Post A0") || block.contains("Post A1") || block.contains("More Blog A"),
                    theme + ": the current blog is never named:\n" + block);
            assertFalse(block.contains("/" + current.getHandle() + "/"),
                    theme + ": nor linked:\n" + block);
        }
    }

    @Test
    void noOtherBlogsKeepsTheWayHome() throws Exception {
        Weblog only = blog("morefroma", "More Blog A");
        post(only, "morefrom-a-0", "Post A0", 1);
        post(only, "morefrom-a-1", "Post A1", 2);
        refresh();
        assertTrue(TestUtils.weblogger().getDiscoveryManager()
                        .selectMoreFromSite(TestUtils.getManagedWebsite(only), 3).isEmpty(),
                "precondition: no other eligible weblog is left in the database by another test");
        TestUtils.endSession(true);
        for (String theme : THEMES) {
            useTheme(only, theme);
            String body = page("/" + only.getHandle() + "/");
            assertHomeOnly(body, theme);
            assertTrue(RECORD.matcher(body).find(), theme + ": still recorded:\n" + body);
        }
    }

    /**
     * The site space is the front page and the site's own pages, never a
     * blog: no block, and never recorded as a visited blog. The discovery
     * page still carries the script, which fills its places list.
     */
    @Test
    void siteSpaceHasNoBlock() throws Exception {
        threeBlogsOfTwo();
        WeblogPage about = new WeblogPage();
        about.setWeblog(TestUtils.weblogger().getWeblogManager().getSiteSpace());
        about.setSlug("morefromabout");
        about.setTitle("About");
        about.setContent("About the harbour.");
        about.setStatus(WeblogPage.PubStatus.PUBLISHED);
        TestUtils.weblogger().getWeblogPageManager().savePage(about);
        TestUtils.weblogger().flush();
        TestUtils.endSession(true);
        for (String theme : List.of("frontpage", "journal", "travel", "portfolio")) {
            useSiteTheme(theme);
            String front = forwarded(FrontDoor.forwardPath(TestUtils.weblogger()));
            assertTrue(front.contains("fd-discovery-all"), theme + ": the discovery page:\n" + front);
            assertFalse(front.contains("more-from-site"), theme + ": no block on the front page:\n" + front);
            assertFalse(RECORD.matcher(front).find(), theme + ": the site is not a place:\n" + front);
            assertEquals(1, count(front, "<nav class=\"places-been\""),
                    theme + ": one places list, the front page's own:\n" + front);
            assertTrue(front.contains("pressroom.places"), theme + ": the script that fills it:\n" + front);

            String sitePage = forwarded(PAGE_SERVLET + "/site-space/morefromabout");
            assertTrue(sitePage.contains("About the harbour."), theme + ": the site page:\n" + sitePage);
            assertFalse(sitePage.contains("more-from-site"), theme + ": no block on a site page:\n" + sitePage);
            assertFalse(RECORD.matcher(sitePage).find(), theme + ": not recorded:\n" + sitePage);
        }
    }

    @Test
    void homeLinkCarriesContextPath() throws Exception {
        Weblog current = threeBlogsOfTwo();
        Pattern home = Pattern.compile("<p class=\"more-from-site-home\"><a href=\"([^\"]*)\">");
        for (String theme : THEMES) {
            useTheme(current, theme);
            String block = block(page("/" + current.getHandle() + "/"), theme);
            Matcher link = home.matcher(block);
            assertTrue(link.find(), theme + ": a link home:\n" + block);
            assertEquals(ABSOLUTE_HOST + "/pressroom/", link.group(1), theme);
        }
    }

    /**
     * Saving strips the markup from a weblog name, so what reaches the page
     * still carries a quote and an ampersand: escaped once, by the wrapper,
     * inside the recording element's attributes and in another blog's block.
     */
    @Test
    void placesDataAttributesAreEscaped() throws Exception {
        Weblog evil = blog("morefromevil", "\"><script>x</script>' & Co");
        post(evil, "morefrom-evil", "Evil post", 1);
        Weblog other = blog("morefromother", "Other");
        post(other, "morefrom-other", "Other post", 2);
        refresh();
        String stored = TestUtils.weblogger().getWeblogManager().getWeblogByHandle("morefromevil").getName();
        TestUtils.endSession(true);
        assertTrue(stored.contains("\"") && stored.contains("&"), "the fixture still carries " + stored);
        String escaped = StringEscapeUtils.escapeHtml4(stored);
        for (String theme : THEMES) {
            useTheme(evil, theme);
            String body = page("/" + evil.getHandle() + "/");
            assertFalse(body.contains("\"><script>x"), theme + ":\n" + body);
            Matcher record = RECORD.matcher(body);
            assertTrue(record.find(), theme + ":\n" + body);
            String tag = record.group();
            assertTrue(tag.contains(" data-name=\"" + escaped + "\""), theme + ": " + tag);
            assertTrue(tag.contains(" data-handle=\"morefromevil\""), theme + ": " + tag);
            assertTrue(tag.contains(" data-url=\"" + ABSOLUTE_HOST + "/pressroom/morefromevil/\""), theme + ": " + tag);
        }
        String block = block(page("/" + other.getHandle() + "/"), "the other blog");
        assertTrue(block.contains(escaped), "the block names the blog escaped once:\n" + block);
        assertFalse(block.contains("&amp;amp;"), block);
    }

    @Test
    void placesScriptUsesNoInnerHtml() throws Exception {
        String script = placesMacro();
        assertTrue(script.contains("<script>"), "the macro carries an inline script:\n" + script);
        for (String writesHtml : List.of("innerHTML", "outerHTML", "insertAdjacentHTML", "document.write")) {
            assertFalse(script.contains(writesHtml), "writes the DOM with " + writesHtml + ":\n" + script);
        }
        assertTrue(script.contains("textContent"), "names are written as text:\n" + script);
        assertTrue(Pattern.compile("try\\s*\\{[^}]*localStorage\\.getItem").matcher(script).find(),
                "reading storage is wrapped in try/catch:\n" + script);
        assertTrue(Pattern.compile("try\\s*\\{[^}]*localStorage\\.setItem").matcher(script).find(),
                "writing storage is wrapped in try/catch:\n" + script);
    }

    @Test
    void placesScriptCapsAtFive() throws Exception {
        String script = placesMacro();
        assertTrue(script.contains("'pressroom.places'"), "the storage key:\n" + script);
        assertTrue(Pattern.compile("\\bcap\\s*=\\s*5\\b").matcher(script).find(), "the cap is five:\n" + script);
        // Ruling 11: five OTHER blogs on a blog page, so the stored list keeps
        // one more, the current blog included; the front page records nothing
        // and shows five.
        assertTrue(Pattern.compile("places\\s*=\\s*places\\.slice\\(0,\\s*cap\\s*\\+\\s*1\\)").matcher(script).find(),
                "the stored list keeps cap + 1, the current blog included:\n" + script);
        assertTrue(Pattern.compile("others\\s*=\\s*places\\.filter\\([^\\n]*\\)\\.slice\\(0,\\s*cap\\);").matcher(script).find(),
                "five other blogs are shown:\n" + script);
    }

    /**
     * Storage is writable by any script on the origin, so what comes back is
     * untrusted: the url filter is the one barrier between it and a live
     * href, and a name only ever reaches the page as text. Checked on the
     * script as rendered, not just its source.
     */
    @Test
    void placesScriptFiltersUrlsAndWritesNamesAsText() throws Exception {
        Weblog current = threeBlogsOfTwo();
        String body = page("/" + current.getHandle() + "/");
        int start = body.indexOf("<script>", body.indexOf("data-places-record"));
        String script = body.substring(start, body.indexOf("</script>", start));
        assertTrue(script.contains("'pressroom.places'"), "the places script:\n" + script);
        assertTrue(script.contains("&& /^https?:\\/\\//.test(p.url);"),
                "only an http(s) url is usable:\n" + script);
        assertTrue(Pattern.compile("stored\\.filter\\(function \\(p\\) \\{ return usable\\(p\\)").matcher(script).find(),
                "every stored entry passes the filter before use:\n" + script);
        assertTrue(Pattern.compile("if \\(usable\\(current\\)\\)").matcher(script).find(),
                "and so does the current blog:\n" + script);
        assertEquals(2, count(script, "p.name"), "a name is used twice, no more:\n" + script);
        assertTrue(script.contains("typeof p.name === 'string'") && script.contains("a.textContent = p.name;"),
                "a name is only type-checked and written as text:\n" + script);
        assertEquals(1, count(script, ".href = "), "one href, the filtered url:\n" + script);
        assertTrue(script.contains("a.href = p.url;"), script);
    }

    /**
     * A corrupt value must not lock the list out forever: storage that
     * cannot be read stops the script, but a value that will not parse is
     * read as an empty list, so this visit's save overwrites it.
     */
    @Test
    void corruptStoredListIsReplacedNotFatal() throws Exception {
        String script = placesMacro();
        Matcher read = Pattern.compile("try\\s*\\{([^}]*localStorage\\.getItem[^}]*)\\}\\s*catch\\s*\\(e\\)\\s*\\{([^}]*)\\}")
                .matcher(script);
        assertTrue(read.find(), "reading storage has its own try/catch:\n" + script);
        assertFalse(read.group(1).contains("JSON.parse"), "parsing is not in the read's try:\n" + read.group(1));
        assertTrue(read.group(2).contains("return"), "unreadable storage stops the script:\n" + read.group(2));
        Matcher parse = Pattern.compile("try\\s*\\{([^}]*JSON\\.parse[^}]*)\\}\\s*catch\\s*\\(e\\)\\s*\\{([^}]*)\\}")
                .matcher(script);
        assertTrue(parse.find(), "parsing has its own try/catch:\n" + script);
        assertFalse(parse.group(2).contains("return"), "a corrupt value does not stop the script:\n" + parse.group(2));
        assertTrue(Pattern.compile("stored\\s*=\\s*\\[\\]").matcher(parse.group(2)).find(),
                "a corrupt value reads as an empty list:\n" + parse.group(2));
    }

    // ------------------------------------------------------------- helpers

    /** The places macro's source in weblog.vm, from its definition to its end. */
    private static String placesMacro() throws Exception {
        String source = Files.readString(WEBLOG_VM, StandardCharsets.UTF_8);
        int start = source.indexOf("#macro(showPlacesYouveBeen $weblog)");
        assertTrue(start >= 0, "weblog.vm defines the places macro");
        return source.substring(start, source.indexOf("\n#end", source.indexOf("</script>", start)));
    }

    /** Blog A (returned, the current blog) and blogs B and C, two posts each. */
    private Weblog threeBlogsOfTwo() throws Exception {
        int minutes = 10;
        Weblog first = null;
        for (String letter : List.of("A", "B", "C")) {
            Weblog blog = blog("morefrom" + letter.toLowerCase(java.util.Locale.ROOT), "More Blog " + letter);
            for (int e = 0; e < 2; e++) {
                post(blog, "morefrom-" + letter.toLowerCase(java.util.Locale.ROOT) + "-" + e,
                        "Post " + letter + e, minutes++);
            }
            first = first == null ? blog : first;
        }
        refresh();
        return first;
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

    private static void useSiteTheme(String theme) throws Exception {
        Weblog managed = TestUtils.weblogger().getWeblogManager().getSiteSpace();
        managed.setEditorTheme(theme);
        TestUtils.weblogger().getWeblogManager().saveWeblog(managed);
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

    /** A page servlet path as FrontDoor or the mapper forwards it. */
    private static String forwarded(String path) throws Exception {
        MockHttpServletRequest request = RenderingTestSupport.anonymousGet(PAGE_SERVLET,
                path.substring(PAGE_SERVLET.length()));
        request.setDispatcherType(DispatcherType.FORWARD);
        MockHttpServletResponse response = RenderingTestSupport.execute(RenderingTestSupport.pageServlet(), request);
        String body = response.getContentAsString();
        assertEquals(200, response.getStatus(), path + ":\n" + body);
        TestUtils.endSession(true);
        return body;
    }

    /** The more-from-the-site block, from its opening tag to its end. */
    private static String block(String body, String label) {
        int start = body.indexOf("<aside class=\"more-from-site\"");
        assertTrue(start >= 0, label + ": no more-from-site block:\n" + body);
        return body.substring(start, body.indexOf("</aside>", start));
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
