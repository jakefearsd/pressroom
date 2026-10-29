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

package com.wikantik.pressroom.ui.rendering.model;

import com.wikantik.pressroom.WebloggerException;
import com.wikantik.pressroom.business.MultiWeblogURLStrategy;
import com.wikantik.pressroom.business.PropertiesManager;
import com.wikantik.pressroom.business.UserManager;
import com.wikantik.pressroom.business.WeblogEntryManager;
import com.wikantik.pressroom.business.WeblogManager;
import com.wikantik.pressroom.business.Weblogger;
import com.wikantik.pressroom.config.RuntimeConfigAttachment;
import com.wikantik.pressroom.config.WebloggerRuntimeConfig;
import com.wikantik.pressroom.pojos.TagStat;
import com.wikantik.pressroom.pojos.User;
import com.wikantik.pressroom.pojos.Weblog;
import com.wikantik.pressroom.pojos.WeblogEntry;
import com.wikantik.pressroom.pojos.WeblogPermission;
import com.wikantik.pressroom.pojos.WeblogTemplate;
import com.wikantik.pressroom.pojos.wrapper.WeblogWrapper;
import com.wikantik.pressroom.ui.rendering.util.WeblogFeedRequest;
import com.wikantik.pressroom.ui.rendering.util.WeblogPageRequest;
import com.wikantik.pressroom.ui.rendering.util.WeblogRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link SiteModel}, the {@code $site} object that front-page
 * themes use to show activity across every blog on the installation.
 *
 * <p>Two things are worth protecting here. First, every accessor is wrapped in
 * a catch-all: a front page that shows a stale "hot blogs" list is better than
 * one that fails to render, so each of these must degrade to an empty result
 * rather than propagate. Second, the pager URLs differ between page and feed
 * requests, and getting that wrong sends readers of the site-wide feed to an
 * HTML page.
 */
class SiteModelTest {

    private RuntimeConfigAttachment runtimeConfig;
    private Weblogger weblogger;
    private UserManager userManager;
    private WeblogManager weblogManager;
    private WeblogEntryManager entryManager;
    private Weblog weblog;
    private String previousRelativeContextURL;

    @BeforeEach
    void setUp() {
        userManager = mock(UserManager.class);
        weblogManager = mock(WeblogManager.class);
        entryManager = mock(WeblogEntryManager.class);
        // The facade the model is GIVEN through initData (plan Task 10).
        weblogger = mock(Weblogger.class);
        when(weblogger.getUserManager()).thenReturn(userManager);
        when(weblogger.getWeblogManager()).thenReturn(weblogManager);
        when(weblogger.getWeblogEntryManager()).thenReturn(entryManager);
        when(weblogger.getPropertiesManager()).thenReturn(mock(PropertiesManager.class));

        // Nothing is reachable statically any more: the pagers and wrappers
        // the model builds take the facade from the model (plan Tasks 11, 13,
        // 16), and WebloggerRuntimeConfig's property reads go through the
        // properties manager attached to it (plan Task 19) -- a mock one here,
        // so a regression to static location of any manager fails loudly.
        runtimeConfig = RuntimeConfigAttachment.of(mock(PropertiesManager.class));

        previousRelativeContextURL = WebloggerRuntimeConfig.getRelativeContextURL();
        WebloggerRuntimeConfig.setRelativeContextURL("/pressroom");
        WebloggerRuntimeConfig.setAbsoluteContextURL("http://blogs.example.com/pressroom");

        weblog = new Weblog("frontpage", "testuser", "Front Page", "the site blog",
                "blog@example.com", "journal", "en_US", "UTC");
    }

    @AfterEach
    void tearDown() {
        WebloggerRuntimeConfig.setRelativeContextURL(previousRelativeContextURL);
        WebloggerRuntimeConfig.setAbsoluteContextURL(null);
        runtimeConfig.close();
    }

    private SiteModel modelFor(WeblogRequest request) throws WebloggerException {
        Map<String, Object> initData = new HashMap<>();
        initData.put("parsedRequest", request);
        initData.put("weblogger", weblogger);
        initData.put("urlStrategy", new MultiWeblogURLStrategy());
        SiteModel model = new SiteModel();
        model.init(initData);
        return model;
    }

    private WeblogPageRequest pageRequest() {
        WeblogPageRequest request = new WeblogPageRequest();
        request.setWeblog(weblog);
        request.setWeblogHandle(weblog.getHandle());
        return request;
    }

    private SiteModel model() throws WebloggerException {
        return modelFor(pageRequest());
    }

    private static Weblog otherWeblog(String handle, String name) {
        return new Weblog(handle, "someone", name, "d", "e@example.com",
                "journal", "en_US", "UTC");
    }

    // ------------------------------------------------------------------ init

    @Test
    void modelIsRegisteredUnderTheNameThemesUse() {
        assertEquals("site", new SiteModel().getModelName(),
                "Themes reference this model as $site.");
    }

    @Test
    void initWithoutAParsedRequestFails() {
        SiteModel model = new SiteModel();
        WebloggerException thrown = assertThrows(WebloggerException.class,
                () -> model.init(new HashMap<>()),
                "init() must reject init data with no request.");
        assertTrue(thrown.getMessage().contains("weblogRequest"),
                "The failure should name what was missing; was: " + thrown.getMessage());
    }

    @Test
    void initWithoutAUrlStrategyIsRefused() {
        // Used to fall back to the static locator's strategy; the only caller
        // that relied on that (WeblogCacheWarmupJob) now passes one.
        Map<String, Object> initData = new HashMap<>();
        initData.put("parsedRequest", pageRequest());
        initData.put("weblogger", weblogger);
        SiteModel model = new SiteModel();

        WebloggerException thrown = assertThrows(WebloggerException.class,
                () -> model.init(initData),
                "init() must refuse init data with no 'urlStrategy'.");
        assertTrue(thrown.getMessage().contains("urlStrategy"),
                "The failure should name what was missing; was: " + thrown.getMessage());
    }

    @Test
    void initWithoutAWebloggerIsRefused() {
        Map<String, Object> initData = new HashMap<>();
        initData.put("parsedRequest", pageRequest());
        initData.put("urlStrategy", new MultiWeblogURLStrategy());
        SiteModel model = new SiteModel();

        WebloggerException thrown = assertThrows(WebloggerException.class,
                () -> model.init(initData),
                "init() must refuse init data with no 'weblogger'.");
        assertTrue(thrown.getMessage().contains("weblogger"),
                "The failure should name what was missing; was: " + thrown.getMessage());
    }

    // ---------------------------------------------------------- pager urls

    @Test
    void onAPageRequestThePagerLinksBackToThePageBeingRendered() throws Exception {
        // The site-wide entry list usually lives on a custom template, and its
        // paging links have to return to that same template.
        WeblogTemplate template = new WeblogTemplate();
        template.setLink("frontpage");
        WeblogPageRequest request = pageRequest();
        request.setWeblogPageName("frontpage");
        request.setWeblogPage(template);
        request.setTags(List.of("java"));

        String link = modelFor(request).getWeblogEntriesPager(-1, 10).getHomeLink();

        assertTrue(link.startsWith("/pressroom/frontpage/page/frontpage"),
                "Paging must return to the same custom page; link was: " + link);
        assertTrue(link.contains("tags=java"),
                "The tag filter must survive into the paging link; link was: " + link);
    }

    @Test
    void onAFeedRequestThePagerLinksToTheFeed() throws Exception {
        // Rendering the site-wide feed must not produce links into the HTML UI:
        // a feed reader following them gets a web page, not more entries.
        WeblogFeedRequest request = new WeblogFeedRequest();
        request.setWeblog(weblog);
        request.setWeblogHandle(weblog.getHandle());
        request.setType("entries");
        request.setFormat("atom");
        request.setWeblogCategoryName("Tech");
        request.setExcerpts(true);

        String link = modelFor(request).getWeblogEntriesPager(-1, 10).getHomeLink();

        assertTrue(link.startsWith("http://blogs.example.com/pressroom/frontpage/feed/entries/atom"),
                "A feed request must page within the feed, absolutely addressed; link "
                        + "was: " + link);
        assertTrue(link.contains("cat=Tech") && link.contains("excerpts=true"),
                "The feed's filters must survive into its paging link; link was: " + link);
    }

    @Test
    void aPlainWeblogRequestPagesFromTheWeblogRoot() throws Exception {
        WeblogRequest request = new WeblogRequest();
        request.setWeblog(weblog);
        request.setWeblogHandle(weblog.getHandle());

        assertEquals("/pressroom/frontpage/",
                modelFor(request).getWeblogEntriesPager(-1, 10).getHomeLink(),
                "A request that is neither a page nor a feed request still needs a "
                        + "usable base URL.");
    }

    @Test
    void onAFeedRequestEveryPagerLinksToTheFeed() throws Exception {
        // Each accessor builds its own pager URL, so each has to make the same
        // page-versus-feed decision independently.
        WeblogFeedRequest request = new WeblogFeedRequest();
        request.setWeblog(weblog);
        request.setWeblogHandle(weblog.getHandle());
        request.setType("entries");
        request.setFormat("atom");

        SiteModel model = modelFor(request);
        String expected = "http://blogs.example.com/pressroom/frontpage/feed/entries/atom";
        WeblogWrapper wrapper = WeblogWrapper.wrap(otherWeblog("other", "Other"),
                new MultiWeblogURLStrategy(), weblogger);

        assertEquals(expected, model.getWeblogEntriesPager(wrapper, 30, 10).getHomeLink(),
                "The weblog-scoped entries pager must page within the feed.");
        assertEquals(expected, model.getUsersByLetterPager("A", -1, 10).getHomeLink(),
                "The user directory pager must page within the feed.");
    }

    @Test
    void theDirectoryPagersShareThePageUrl() throws Exception {
        SiteModel model = model();

        assertEquals("/pressroom/frontpage/", model.getUsersByLetterPager("A", -1, 10).getHomeLink(),
                "The user directory pages from the same base URL.");
        assertEquals("/pressroom/frontpage/", model.getWeblogsByLetterPager("A", -1, 10).getHomeLink(),
                "The weblog directory pages from the same base URL.");
    }

    @Test
    void aLetterNarrowsTheDirectoryToThatLetter() throws Exception {
        SiteModel model = model();

        model.getUsersByLetterPager("A", -1, 10);
        verify(userManager).getUsersByLetter(eq('A'), anyInt(), anyInt());

        model.getWeblogsByLetterPager("B", -1, 10);
        verify(weblogManager).getWeblogsByLetter(eq('B'), anyInt(), anyInt());
    }

    @Test
    void anEmptyLetterMeansTheWholeDirectoryRatherThanNoResults() throws Exception {
        // The A-Z directory renders an "all" tab whose letter is empty. Passing
        // "" straight through would index off the end of the string and the tab
        // would silently list nobody.
        SiteModel model = model();

        model.getUsersByLetterPager("", -1, 10);
        verify(userManager).getUsers(any(), any(), any(), anyInt(), anyInt());

        model.getWeblogsByLetterPager("", -1, 10);
        verify(weblogManager).getWeblogs(any(), any(), any(), any(), anyInt(), anyInt());
    }

    @Test
    void aNullLetterAlsoMeansTheWholeDirectory() throws Exception {
        SiteModel model = model();

        model.getUsersByLetterPager(null, -1, 10);
        verify(userManager).getUsers(any(), any(), any(), anyInt(), anyInt());

        model.getWeblogsByLetterPager(null, -1, 10);
        verify(weblogManager).getWeblogs(any(), any(), any(), any(), anyInt(), anyInt());
    }

    @Test
    void theRestrictedEntriesPagerScopesToOneWeblog() throws Exception {
        // $site.getWeblogEntriesPager($someBlog, 30, 10) is how a front page
        // features a single blog.
        WeblogWrapper wrapper = WeblogWrapper.wrap(otherWeblog("other", "Other"),
                new MultiWeblogURLStrategy(), weblogger);

        SiteModel model = model();

        assertNotNull(model.getWeblogEntriesPager(wrapper, 30, 10),
                "The weblog-scoped pager must build.");
        assertNotNull(model.getWeblogEntriesPager(wrapper, new User(), 30, 10),
                "The weblog- and user-scoped pager must build.");
        assertNotNull(model.getWeblogEntriesPager(wrapper, new User(), "Tech", 30, 10),
                "The weblog-, user- and category-scoped pager must build.");
    }

    // ------------------------------------------------------------- directory

    @Test
    void letterMapsComeStraightFromTheManagers() throws Exception {
        when(userManager.getUserNameLetterMap()).thenReturn(Map.of("A", 3L));
        when(weblogManager.getWeblogHandleLetterMap()).thenReturn(Map.of("B", 5L));

        SiteModel model = model();

        assertEquals(Map.of("A", 3L), model.getUserNameLetterMap(),
                "$site.userNameLetterMap drives the A-Z user directory tabs.");
        assertEquals(Map.of("B", 5L), model.getWeblogHandleLetterMap(),
                "$site.weblogHandleLetterMap drives the A-Z weblog directory tabs.");
    }

    @Test
    void aFailedLetterMapLookupRendersAnEmptyDirectoryRatherThanNoPage() throws Exception {
        when(userManager.getUserNameLetterMap())
                .thenThrow(new WebloggerException("database is down"));
        when(weblogManager.getWeblogHandleLetterMap())
                .thenThrow(new WebloggerException("database is down"));

        SiteModel model = model();

        assertEquals(Map.of(), model.getUserNameLetterMap(),
                "A failed lookup must degrade to an empty map.");
        assertEquals(Map.of(), model.getWeblogHandleLetterMap(),
                "A failed lookup must degrade to an empty map.");
    }

    @Test
    void aUsersWeblogsAreResolvedThroughTheirPermissions() throws Exception {
        User user = new User();
        user.setUserName("alice");
        Weblog blog = otherWeblog("alicesblog", "Alice's Blog");
        when(userManager.getUserByUserName("alice")).thenReturn(user);
        when(userManager.getWeblogPermissions(user))
                .thenReturn(List.of(new WeblogPermission(blog, user, WeblogPermission.POST)));
        when(weblogManager.getWeblogByHandle("alicesblog", null)).thenReturn(blog);

        List<WeblogWrapper> weblogs = model().getUsersWeblogs("alice");

        assertEquals(1, weblogs.size(), "Alice has exactly one weblog permission.");
        assertEquals("alicesblog", weblogs.get(0).getHandle(),
                "The permission must be resolved to the weblog it grants.");
    }

    @Test
    void anUnknownUserHasNoWeblogs() throws Exception {
        when(userManager.getUserByUserName("nobody")).thenReturn(null);
        when(userManager.getWeblogPermissions((User) null))
                .thenThrow(new WebloggerException("no such user"));

        assertEquals(List.of(), model().getUsersWeblogs("nobody"),
                "An unknown user must produce an empty list, not a failed render.");
    }

    @Test
    void aWeblogsUsersAreResolvedThroughItsPermissions() throws Exception {
        User user = new User();
        user.setUserName("alice");
        user.setScreenName("Alice");
        Weblog blog = otherWeblog("alicesblog", "Alice's Blog");
        when(weblogManager.getWeblogByHandle("alicesblog")).thenReturn(blog);
        when(userManager.getWeblogPermissions(blog))
                .thenReturn(List.of(new WeblogPermission(blog, user, WeblogPermission.POST)));
        when(userManager.getUserByUserName("alice")).thenReturn(user);

        assertEquals(1, model().getWeblogsUsers("alicesblog").size(),
                "The weblog has exactly one member.");
        assertEquals("Alice", model().getWeblogsUsers("alicesblog").get(0).getScreenName(),
                "The permission must be resolved to the user it belongs to.");
    }

    @Test
    void anUnknownWeblogHasNoUsers() throws Exception {
        when(weblogManager.getWeblogByHandle("ghost"))
                .thenThrow(new WebloggerException("no such weblog"));

        assertEquals(List.of(), model().getWeblogsUsers("ghost"),
                "An unknown handle must produce an empty list, not a failed render.");
    }

    @Test
    void lookupsByNameReturnNullWhenNothingMatches() throws Exception {
        when(userManager.getUserByUserName("ghost", Boolean.TRUE)).thenReturn(null);
        when(weblogManager.getWeblogByHandle("ghost")).thenReturn(null);

        SiteModel model = model();

        assertNull(model.getUser("ghost"),
                "$site.getUser() must be null for an unknown name so the theme's "
                        + "#if guard works.");
        assertNull(model.getWeblog("ghost"),
                "$site.getWeblog() must be null for an unknown handle.");
    }

    @Test
    void lookupsByNameWrapWhatTheyFind() throws Exception {
        User user = new User();
        user.setUserName("alice");
        user.setScreenName("Alice");
        when(userManager.getUserByUserName("alice", Boolean.TRUE)).thenReturn(user);
        when(weblogManager.getWeblogByHandle("alicesblog"))
                .thenReturn(otherWeblog("alicesblog", "Alice's Blog"));

        SiteModel model = model();

        assertEquals("Alice", model.getUser("alice").getScreenName(),
                "The wrapped user must be the one that was looked up.");
        assertEquals("Alice's Blog", model.getWeblog("alicesblog").getName(),
                "The wrapped weblog must be the one that was looked up.");
    }

    @Test
    void aFailedLookupByNameDoesNotBreakThePage() throws Exception {
        when(userManager.getUserByUserName("alice", Boolean.TRUE))
                .thenThrow(new WebloggerException("database is down"));
        when(weblogManager.getWeblogByHandle("alicesblog"))
                .thenThrow(new WebloggerException("database is down"));

        SiteModel model = model();

        assertNull(model.getUser("alice"), "A failed user lookup must yield null.");
        assertNull(model.getWeblog("alicesblog"), "A failed weblog lookup must yield null.");
    }

    // ---------------------------------------------------------- what's new

    @Test
    void newWeblogsAreTheVisibleActiveOnesSinceTheCutoff() throws Exception {
        when(weblogManager.getWeblogs(eq(Boolean.TRUE), eq(Boolean.TRUE), any(), isNull(),
                eq(0), eq(5)))
                .thenReturn(List.of(otherWeblog("new1", "New One")));

        List<WeblogWrapper> weblogs = model().getNewWeblogs(30, 5);

        assertEquals(1, weblogs.size(), "The manager returned one weblog.");
        assertEquals("New One", weblogs.get(0).getName(),
                "$site.getNewWeblogs is rendered as a 'recently created' list.");
    }

    @Test
    void newUsersAreTheEnabledOnes() throws Exception {
        User user = new User();
        user.setUserName("newbie");
        user.setScreenName("Newbie");
        when(userManager.getUsers(eq(Boolean.TRUE), isNull(), isNull(), eq(0), eq(5)))
                .thenReturn(List.of(user));

        assertEquals("Newbie", model().getNewUsers(30, 5).get(0).getScreenName(),
                "Only enabled users belong on a public 'new members' list.");
    }

    @Test
    void failuresWhileGatheringWhatsNewLeaveEmptyLists() throws Exception {
        when(weblogManager.getWeblogs(any(), any(), any(), any(), anyInt(), anyInt()))
                .thenThrow(new WebloggerException("database is down"));
        when(userManager.getUsers(any(), any(), any(), anyInt(), anyInt()))
                .thenThrow(new WebloggerException("database is down"));

        SiteModel model = model();

        assertEquals(List.of(), model.getNewWeblogs(30, 5),
                "A failure must leave the section empty, not kill the front page.");
        assertEquals(List.of(), model.getNewUsers(30, 5),
                "A failure must leave the section empty, not kill the front page.");
    }

    // ------------------------------------------------------------ statistics

    @Test
    void pinnedEntriesAreWrappedForTheTemplate() throws Exception {
        WeblogEntry entry = new WeblogEntry();
        entry.setTitle("Pinned Post");
        entry.setWebsite(weblog);
        when(entryManager.getWeblogEntriesPinnedToMain(3)).thenReturn(List.of(entry));

        assertEquals("Pinned Post", model().getPinnedWeblogEntries(3).get(0).getTitle(),
                "$site.getPinnedWeblogEntries is how a front page features a post.");
    }

    @Test
    void aFailureGatheringPinnedEntriesLeavesAnEmptyList() throws Exception {
        when(entryManager.getWeblogEntriesPinnedToMain(anyInt()))
                .thenThrow(new WebloggerException("database is down"));

        assertEquals(List.of(), model().getPinnedWeblogEntries(3),
                "A failure must leave the section empty.");
    }

    @Test
    void popularTagsAreWindowedWhenAPositiveNumberOfDaysIsGiven() throws Exception {
        // A tag cloud "over the last 30 days" must actually pass a start date;
        // without one the cloud silently becomes all-time.
        ArgumentCaptor<Date> startDate = ArgumentCaptor.forClass(Date.class);
        when(entryManager.getPopularTags(isNull(), startDate.capture(), eq(0), eq(20)))
                .thenReturn(List.of(new TagStat()));

        assertEquals(1, model().getPopularTags(30, 20).size(),
                "The manager's result is passed through.");
        assertNotNull(startDate.getValue(),
                "A positive sinceDays must produce a start date.");

        // The window has to be the requested width, not merely non-null: a
        // start date of "now" would produce an empty tag cloud on every blog.
        long ageMillis = System.currentTimeMillis() - startDate.getValue().getTime();
        long thirtyDaysMillis = 30L * 24 * 60 * 60 * 1000;
        long twoHoursMillis = 2L * 60 * 60 * 1000;
        assertTrue(Math.abs(ageMillis - thirtyDaysMillis) < twoHoursMillis,
                "getPopularTags(30, ...) must look back 30 days; the start date was "
                        + (ageMillis / 3_600_000L) + " hours ago.");
    }

    @Test
    void popularTagsAreAllTimeWhenNoWindowIsGiven() throws Exception {
        when(entryManager.getPopularTags(isNull(), isNull(), eq(0), eq(20)))
                .thenReturn(List.of(new TagStat(), new TagStat()));

        assertEquals(2, model().getPopularTags(-1, 20).size(),
                "sinceDays <= 0 must mean 'no time limit', i.e. a null start date.");
        assertEquals(2, model().getPopularTags(0, 20).size(),
                "Zero days is not a one-instant window, it is the same 'no limit' "
                        + "sentinel; treating it as a window would empty the tag cloud.");
    }

    @Test
    void aFailureGatheringPopularTagsLeavesAnEmptyList() throws Exception {
        when(entryManager.getPopularTags(any(), any(), anyInt(), anyInt()))
                .thenThrow(new WebloggerException("database is down"));

        assertEquals(List.of(), model().getPopularTags(30, 20),
                "A failure must leave the tag cloud empty.");
    }

    // ---------------------------------------------------------------- counts

    @Test
    void siteWideCountsComeFromTheManagers() throws Exception {
        when(entryManager.getEntryCount()).thenReturn(22L);
        when(weblogManager.getWeblogCount()).thenReturn(33L);
        when(userManager.getUserCount()).thenReturn(44L);

        SiteModel model = model();

        assertEquals(22L, model.getEntryCount(), "$site.entryCount");
        assertEquals(33L, model.getWeblogCount(), "$site.weblogCount");
        assertEquals(44L, model.getUserCount(), "$site.userCount");
    }

    @Test
    void countsReadAsZeroWhenTheDatabaseIsUnavailable() throws Exception {
        when(entryManager.getEntryCount()).thenThrow(new WebloggerException("down"));
        when(weblogManager.getWeblogCount()).thenThrow(new WebloggerException("down"));
        when(userManager.getUserCount()).thenThrow(new WebloggerException("down"));

        SiteModel model = model();

        assertEquals(0L, model.getEntryCount(), "A failed count must read as 0.");
        assertEquals(0L, model.getWeblogCount(), "A failed count must read as 0.");
        assertEquals(0L, model.getUserCount(), "A failed count must read as 0.");
    }
}
