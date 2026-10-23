/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 *  contributor license agreements.  The ASF licenses this file to You
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
package org.apache.roller.weblogger.ui.rendering.servlets;

import java.sql.Timestamp;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.roller.weblogger.TestUtils;
import org.apache.roller.weblogger.business.WeblogEntryManager;
import org.apache.roller.weblogger.pojos.User;
import org.apache.roller.weblogger.pojos.Weblog;
import org.apache.roller.weblogger.pojos.WeblogCategory;
import org.apache.roller.weblogger.pojos.WeblogEntry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The reader chrome the three article themes now share, rendered through the
 * real PageServlet and SearchServlet: the Newer/Older pagers (journal's,
 * ported to travel and portfolio), the visible category heading, the search
 * page's footer and category nav, the author's Edit link, and the title
 * separator. {@code ThemeChromeConsistencyTest} holds the source-level half
 * of the same rules, for pages a fixture cannot reach.
 */
class ThemeChromeRenderingTest {

    private static final Map<String, String> PREFIX =
            Map.of("journal", "qj", "travel", "tg", "portfolio", "pf");

    private static final Pattern MORE_ENTRIES =
            Pattern.compile("<nav [^>]*aria-label=\"More entries\">(.*?)</nav>", Pattern.DOTALL);

    private User user;
    private Weblog weblog;
    private String handle;

    @BeforeEach
    void setUp() throws Exception {
        RenderingTestSupport.ensureRenderingRuntime();
        RenderingTestSupport.clearRenderCaches();
        String suffix = Long.toString(System.nanoTime(), 36);
        handle = "chrome" + suffix;
        user = TestUtils.setupUser("chromeuser" + suffix);
        weblog = TestUtils.setupWeblog(handle, user);
    }

    @AfterEach
    void tearDown() throws Exception {
        TestUtils.teardownWeblog(weblog.getId());
        TestUtils.teardownUser(user.getUserName());
        TestUtils.endSession(true);
    }

    // ---------------------------------------------------------------- helpers

    private void useTheme(String theme, int entriesPerPage) throws Exception {
        Weblog managed = TestUtils.getManagedWebsite(weblog);
        managed.setEditorTheme(theme);
        managed.setEntryDisplayCount(entriesPerPage);
        TestUtils.weblogger().getWeblogManager().saveWeblog(managed);
        TestUtils.endSession(true);
    }

    /** An entry published {@code daysAgo} days back, so neighbours have a fixed order. */
    private void entry(String anchor, int daysAgo) throws Exception {
        WeblogEntry entry = TestUtils.setupWeblogEntry(anchor, weblog, user);
        WeblogEntryManager mgr = TestUtils.weblogger().getWeblogEntryManager();
        WeblogEntry managed = mgr.getWeblogEntry(entry.getId());
        managed.setPubTime(new Timestamp(System.currentTimeMillis()
                - TimeUnit.DAYS.toMillis(daysAgo)));
        mgr.saveWeblogEntry(managed);
        TestUtils.endSession(true);
    }

    private String render(String pathInfo, String page, String userName) throws Exception {
        MockHttpServletRequest request = RenderingTestSupport
                .anonymousGet("/roller-ui/rendering/page", pathInfo);
        if (page != null) {
            request.setParameter("page", page);
        }
        if (userName != null) {
            request.setUserPrincipal(new UsernamePasswordAuthenticationToken(userName, "n/a",
                    AuthorityUtils.createAuthorityList("ROLE_USER")));
        }
        MockHttpServletResponse response = RenderingTestSupport
                .execute(RenderingTestSupport.pageServlet(), request);
        assertEquals(200, response.getStatus(), "page must render for " + pathInfo);
        return response.getContentAsString();
    }

    private String render(String pathInfo) throws Exception {
        return render(pathInfo, null, null);
    }

    private static String moreEntries(String body) {
        Matcher nav = MORE_ENTRIES.matcher(body);
        assertTrue(nav.find(), "no \"More entries\" pager rendered:\n" + body);
        return nav.group(1);
    }

    private static String linkText(String nav, String rel) {
        Matcher link = Pattern.compile("rel=\"" + rel + "\">(.*?)</a>", Pattern.DOTALL).matcher(nav);
        assertTrue(link.find(), "no rel=\"" + rel + "\" link in the pager:\n" + nav);
        return link.group(1);
    }

    // -------------------------------------------------------------- list pager

    /**
     * getNextLink() moves back through the archive and getPrevLink() toward
     * the present (AbstractWeblogEntriesPager), so the rel="next" step is
     * Older and comes first: older on the left, as on the permalink.
     */
    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"journal", "travel", "portfolio"})
    void aMiddleListPageStepsOlderThenNewer(String theme) throws Exception {
        useTheme(theme, 1);
        entry("oldest-entry", 3);
        entry("middle-entry", 2);
        entry("newest-entry", 1);

        String nav = moreEntries(render("/" + handle + "/", "1", null));

        assertTrue(linkText(nav, "next").contains("Older"), nav);
        assertTrue(linkText(nav, "prev").contains("Newer"), nav);
        assertTrue(nav.indexOf("rel=\"next\"") < nav.indexOf("rel=\"prev\""),
                "the older step must come first, so it sits on the left:\n" + nav);
        assertFalse(nav.contains("&laquo;") || nav.contains("|") || nav.contains(">Main<"),
                "the legacy \"« Previous page | Main | Next page »\" strip must be gone:\n" + nav);
        assertTrue(nav.contains(PREFIX.get(theme) + "-step-label"),
                "the steps carry the theme's own caps label:\n" + nav);
    }

    /** Page one has only an older step, and it must not land in the right column. */
    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"journal", "travel", "portfolio"})
    void theFirstListPageLeadsWithTheOlderStep(String theme) throws Exception {
        useTheme(theme, 1);
        entry("older-entry", 2);
        entry("newer-entry", 1);

        String nav = moreEntries(render("/" + handle + "/"));

        assertFalse(nav.contains("rel=\"prev\""), "page one has nothing newer:\n" + nav);
        assertTrue(nav.trim().startsWith("<a class=\"" + PREFIX.get(theme) + "-step "
                        + PREFIX.get(theme) + "-step-back\""),
                "the older step must be the first thing in the pager:\n" + nav);
    }

    /** A one-page weblog has nowhere to go, so no empty "More entries" landmark. */
    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"journal", "travel", "portfolio"})
    void aOnePageListHasNoPagerLandmark(String theme) throws Exception {
        useTheme(theme, 10);
        entry("only-entry", 1);

        String body = render("/" + handle + "/");

        assertFalse(body.contains("aria-label=\"More entries\""),
                "an empty pager is still a landmark a screen reader lists:\n" + body);
    }

    // --------------------------------------------------------- permalink pager

    /** The macro cut each neighbour's title to 15-20 characters; the steps carry it whole. */
    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"journal", "travel", "portfolio"})
    void thePermalinkStepsCarryTheNeighboursWholeTitles(String theme) throws Exception {
        useTheme(theme, 10);
        entry("why-we-stopped-publishing-on-fridays", 3);
        entry("the-middle-of-the-archive", 2);
        entry("what-we-publish-on-mondays-instead", 1);

        String nav = moreEntries(render("/" + handle + "/entry/the-middle-of-the-archive"));

        assertTrue(nav.contains("why-we-stopped-publishing-on-fridays</span>"), nav);
        assertTrue(nav.contains("what-we-publish-on-mondays-instead</span>"), nav);
        assertTrue(nav.indexOf("why-we-stopped") < nav.indexOf("what-we-publish"),
                "older on the left, newer on the right:\n" + nav);
        assertTrue(linkText(nav, "prev").contains("Older"), nav);
        assertTrue(linkText(nav, "next").contains("Newer"), nav);
    }

    // ------------------------------------------------------- views and chrome

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"journal", "travel", "portfolio"})
    void aCategoryViewNamesItsCategoryAboveTheList(String theme) throws Exception {
        useTheme(theme, 10);
        WeblogCategory category = TestUtils.setupWeblogCategory(
                TestUtils.getManagedWebsite(weblog), "Field & Notes");
        TestUtils.endSession(true);
        TestUtils.setupWeblogEntry("filed-entry", TestUtils.weblogger().getWeblogEntryManager()
                .getWeblogCategory(category.getId()), weblog, user);
        TestUtils.endSession(true);

        String body = render("/" + handle + "/category/Field %26 Notes");

        String prefix = PREFIX.get(theme);
        assertTrue(body.contains("<h2 class=\"" + prefix + "-view-name\">Field &amp; Notes</h2>"),
                "the category view must say which category it is, escaped once:\n" + body);
        assertTrue(body.indexOf(prefix + "-view-name") < body.indexOf("filed-entry"),
                "the heading sits above the list it names:\n" + body);
        assertTrue(body.contains("<title>Field &amp; Notes — "),
                "the title separates view and weblog with an em dash:\n" + body);
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"journal", "travel", "portfolio"})
    void theSearchPageKeepsTheFooterAndTheCategoryNav(String theme) throws Exception {
        useTheme(theme, 10);

        MockHttpServletRequest request = RenderingTestSupport
                .anonymousGet("/roller-ui/rendering/search", "/" + handle);
        request.setParameter("q", "zzznope");
        MockHttpServletResponse response = RenderingTestSupport
                .execute(RenderingTestSupport.searchServlet(), request);
        assertEquals(200, response.getStatus());
        String body = response.getContentAsString();

        assertTrue(body.contains("<footer class=\"" + PREFIX.get(theme) + "-footer\">"),
                "the search page must keep the footer every other page has:\n" + body);
        assertTrue(body.contains(">All</a></li>"),
                "and the category nav, whose first item is All:\n" + body);
        assertTrue(body.contains("<title>Search: zzznope — "), body);
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"journal", "travel", "portfolio"})
    void onlyTheAuthorSeesTheEditLinkOnAPermalink(String theme) throws Exception {
        useTheme(theme, 10);
        entry("editable-entry", 1);

        String author = render("/" + handle + "/entry/editable-entry", null, user.getUserName());
        assertTrue(author.contains("entryEdit.rol"), "the author must be offered the editor:\n" + author);
        assertTrue(author.contains(">Edit</a>"), author);

        RenderingTestSupport.clearRenderCaches();
        String reader = render("/" + handle + "/entry/editable-entry");
        assertFalse(reader.contains("entryEdit.rol"), "a reader must not:\n" + reader);
        assertTrue(reader.contains("<title>editable-entry — "), reader);
        assertTrue(reader.contains(">editable-entry</h1>"),
                "the entry's own title is the reading page's h1:\n" + reader);
        assertEquals(reader.indexOf("<h1"), reader.lastIndexOf("<h1"),
                "and the only one -- the weblog name steps down to a paragraph:\n" + reader);
    }
}
