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
package org.apache.roller.weblogger.pojos.wrapper;

import org.apache.roller.weblogger.WebloggerException;
import org.apache.roller.weblogger.business.URLStrategy;
import org.apache.roller.weblogger.business.UserManager;
import org.apache.roller.weblogger.business.WeblogEntryManager;
import org.apache.roller.weblogger.business.Weblogger;
import org.apache.roller.weblogger.business.themes.ThemeManager;
import org.apache.roller.weblogger.pojos.TagStat;
import org.apache.roller.weblogger.pojos.ThemeTemplate.ComponentType;
import org.apache.roller.weblogger.pojos.User;
import org.apache.roller.weblogger.pojos.Weblog;
import org.apache.roller.weblogger.pojos.WeblogCategory;
import org.apache.roller.weblogger.pojos.WeblogEntry;
import org.apache.roller.weblogger.pojos.WeblogEntrySearchCriteria;
import org.apache.roller.weblogger.pojos.WeblogTemplate;
import org.apache.roller.weblogger.pojos.WeblogTheme;
import org.apache.roller.weblogger.util.ServingSince;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Date;
import java.util.List;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Covers the {@link WeblogWrapper} accessors that reach through the business
 * tier -- URLs, counts, recent-content lists and theme templates.
 *
 * <p>These are the methods a Velocity theme actually calls to build a page, and
 * every one of them is a one-line delegation, which is exactly the shape of
 * code that gets wired to the wrong underlying method during a refactor and
 * still compiles. Each is given a value only it could have returned.
 */
class WeblogWrapperDelegationTest {

    private Weblog weblog;
    private URLStrategy urls;
    private Weblogger weblogger;
    private WeblogEntryManager entries;
    private ThemeManager themes;
    private WeblogTheme theme;
    private WeblogWrapper wrapper;

    @BeforeEach
    void setUp() throws Exception {
        weblog = new Weblog();
        weblog.setHandle("testblog");
        weblog.setLocale("en_US");
        weblog.setLastModified(new Date(1_700_000_000_000L));
        weblog.setCreatorUserName("alice");

        urls = mock(URLStrategy.class);
        entries = mock(WeblogEntryManager.class);
        themes = mock(ThemeManager.class);
        theme = mock(WeblogTheme.class);
        weblogger = mock(Weblogger.class);
        when(weblogger.getUrlStrategy()).thenReturn(urls);
        when(weblogger.getWeblogEntryManager()).thenReturn(entries);
        when(weblogger.getThemeManager()).thenReturn(themes);
        when(themes.getTheme(weblog)).thenReturn(theme);

        wrapper = WeblogWrapper.wrap(weblog, urls, weblogger);
    }

    @Test
    void recentEntryRequestsAreClampedToOneHundredAndAskForPublishedOnly() throws Exception {
        when(entries.getWeblogEntries(any())).thenReturn(List.of());
        ArgumentCaptor<WeblogEntrySearchCriteria> criteria =
                ArgumentCaptor.forClass(WeblogEntrySearchCriteria.class);

        wrapper.getRecentWeblogEntries("General", 500);
        wrapper.getRecentWeblogEntriesByTag("java", 500);

        verify(entries, times(2)).getWeblogEntries(criteria.capture());
        for (WeblogEntrySearchCriteria c : criteria.getAllValues()) {
            assertEquals(100, c.getMaxResults(),
                    "The template API caps a theme's recent-entries request at 100, "
                            + "whatever number the template asked for");
            assertEquals(WeblogEntry.PubStatus.PUBLISHED, c.getStatus());
            assertSame(weblog, c.getWeblog());
        }
        assertEquals("General", criteria.getAllValues().get(0).getCatName());
        assertEquals(List.of("java"), criteria.getAllValues().get(1).getTags());
    }

    @Test
    void aNonPositiveLengthAndANilNameAreTheTemplateApisNulls() throws Exception {
        assertTrue(wrapper.getRecentWeblogEntries("General", 0).isEmpty());
        verifyNoInteractions(entries);

        when(entries.getWeblogEntries(any())).thenReturn(List.of());
        wrapper.getRecentWeblogEntries("nil", 5);
        ArgumentCaptor<WeblogEntrySearchCriteria> criteria =
                ArgumentCaptor.forClass(WeblogEntrySearchCriteria.class);
        verify(entries).getWeblogEntries(criteria.capture());
        assertNull(criteria.getValue().getCatName(), "\"nil\" is how a template says no category");
    }

    @Test
    void aNilCategoryNameFallsBackToTheWeblogsFirstCategory() throws Exception {
        // Same "first category found" fallback saveWeblogEntry uses, kept on
        // the wrapper because a template reaches it; an unchecked exception
        // here would take the whole render with it.
        WeblogCategory first = new WeblogCategory();
        first.setName("First");
        weblog.addCategory(first);

        assertEquals("First", wrapper.getWeblogCategory("nil").getName());
        assertEquals("First", wrapper.getWeblogCategory(null).getName());
        verifyNoInteractions(entries);
    }

    @Test
    void aWeblogWithNoCategoriesAnswersNullForNilRatherThanThrowing() throws Exception {
        assertNull(wrapper.getWeblogCategory("nil"));
    }

    @Test
    void askingForASingleEntryIsAValidRequest() throws Exception {
        // One is the smallest useful request and sits directly on the "too few"
        // boundary; a sidebar showing the latest post asks for exactly this.
        when(entries.getWeblogEntries(any())).thenReturn(List.of());
        ArgumentCaptor<WeblogEntrySearchCriteria> criteria =
                ArgumentCaptor.forClass(WeblogEntrySearchCriteria.class);

        wrapper.getRecentWeblogEntries("General", 1);

        verify(entries).getWeblogEntries(criteria.capture());
        assertEquals(1, criteria.getValue().getMaxResults(),
                "A request for one entry must reach the database, not be rejected as if "
                        + "it were a request for none");
    }

    @Test
    void popularTagsWindowIsOnlyAppliedWhenAPositiveNumberOfDaysIsGiven() throws Exception {
        TagStat popular = new TagStat();
        popular.setName("java");
        when(entries.getPopularTags(any(), any(), org.mockito.ArgumentMatchers.anyInt(),
                org.mockito.ArgumentMatchers.anyInt())).thenReturn(List.of(popular));
        ArgumentCaptor<Date> startDate = ArgumentCaptor.forClass(Date.class);

        List<TagStat> returned = wrapper.getPopularTags(-1, 10);
        wrapper.getPopularTags(0, 10);
        wrapper.getPopularTags(30, 10);

        verify(entries, times(3)).getPopularTags(org.mockito.ArgumentMatchers.eq(weblog),
                startDate.capture(), org.mockito.ArgumentMatchers.eq(0), org.mockito.ArgumentMatchers.eq(10));
        List<Date> starts = startDate.getAllValues();
        assertEquals(List.of(popular), returned, "The tag cloud must render the tags the query returned");
        assertNull(starts.get(0),
                "-1 days means 'all time', which has to reach the query as a null start "
                        + "date rather than a date 1 day in the past");
        assertNull(starts.get(1), "0 days means 'all time' too");
        long thirtyDaysAgo = System.currentTimeMillis() - java.util.concurrent.TimeUnit.DAYS.toMillis(30);
        assertTrue(Math.abs(starts.get(2).getTime() - thirtyDaysAgo)
                        < java.util.concurrent.TimeUnit.MINUTES.toMillis(5),
                "A 30 day window must start 30 days ago, not 30 days in the future: " + starts.get(2));
    }

    @Test
    void lookupsAndCountsFailSoftRatherThanBreakingTheRender() throws Exception {
        // These are called from templates. An exception escaping here takes out
        // the whole page, so the accessors swallow it and report nothing.
        when(entries.getEntryCount(weblog)).thenThrow(new WebloggerException("boom"));
        when(entries.getWeblogEntryByAnchor(org.mockito.ArgumentMatchers.eq(weblog),
                org.mockito.ArgumentMatchers.isNull())).thenThrow(new WebloggerException("bad anchor"));
        when(entries.getPopularTags(any(), any(), org.mockito.ArgumentMatchers.anyInt(),
                org.mockito.ArgumentMatchers.anyInt())).thenThrow(new WebloggerException("boom"));
        when(entries.getWeblogEntries(any())).thenThrow(new WebloggerException("boom"));

        assertEquals(0L, wrapper.getEntryCount());
        assertNull(wrapper.getWeblogEntry(null),
                "A lookup failure must read as 'no such entry' so the permalink page "
                        + "can render a 404 instead of a stack trace");
        assertTrue(wrapper.getPopularTags(30, 10).isEmpty());
        assertTrue(wrapper.getRecentWeblogEntries("General", 5).isEmpty());
    }

    @Test
    void aCreatorThatCannotBeResolvedIsNullNotAnException() throws Exception {
        UserManager users = mock(UserManager.class);
        when(weblogger.getUserManager()).thenReturn(users);
        when(users.getUserByUserName("alice")).thenThrow(new WebloggerException("database down"));

        assertNull(wrapper.getCreator(), "A byline must not be able to break the page");
    }

    @Test
    void theRelativeAndAbsoluteUrlsAreKeptDistinct() {
        when(urls.getWeblogURL(weblog, null, false)).thenReturn("/roller/testblog/");
        when(urls.getWeblogURL(weblog, null, true)).thenReturn("http://example.com/testblog/");

        // No static locator: the strategy the wrapper was GIVEN is the only
        // source of its urls (spec Decision 6).
        assertEquals("/roller/testblog/", wrapper.getURL());
        assertEquals("http://example.com/testblog/", wrapper.getAbsoluteURL(),
                "Feeds and notification e-mails are read away from the site and need the "
                        + "absolute form; swapping the two produces links that 404 in a "
                        + "reader and work in a browser");
    }

    @Test
    void countsComeFromTheEntryManager() throws Exception {
        when(entries.getEntryCount(weblog)).thenReturn(13L);

        assertEquals(13L, wrapper.getEntryCount());
    }

    @Test
    void recentContentListsComeBackWrapped() throws Exception {
        WeblogEntry entry = new WeblogEntry();
        entry.setWebsite(weblog);
        entry.setAnchor("hello-world");
        entry.setTitle("Hello World");
        TagStat tag = new TagStat();
        tag.setName("java");

        when(entries.getWeblogEntries(any())).thenReturn(List.of(entry));
        when(entries.getPopularTags(any(), any(), org.mockito.ArgumentMatchers.anyInt(),
                org.mockito.ArgumentMatchers.anyInt())).thenReturn(List.of(tag));

        assertEquals("Hello World",
                wrapper.getRecentWeblogEntries("General", 5)
                        .get(0).getTitle(),
                "Recent entries must reach the theme wrapped, not as raw pojos");
        assertEquals("Hello World",
                wrapper.getRecentWeblogEntriesByTag("java", 5)
                        .get(0).getTitle());
        assertEquals(List.of(tag), wrapper.getPopularTags(30, 5),
                "Tag statistics carry no mutable state, so they are passed through as-is");
    }

    @Test
    void singleEntryAndCategoryLookupsComeBackWrapped() throws Exception {
        WeblogEntry entry = new WeblogEntry();
        entry.setWebsite(weblog);
        entry.setAnchor("hello-world");
        entry.setTitle("Hello World");
        WeblogCategory category = new WeblogCategory();
        category.setName("Travel");

        when(entries.getWeblogEntryByAnchor(weblog, "hello-world")).thenReturn(entry);
        when(entries.getWeblogCategoryByName(weblog, "Travel")).thenReturn(category);

        assertEquals("Hello World",
                wrapper.getWeblogEntry("hello-world").getTitle());
        assertEquals("Travel",
                wrapper.getWeblogCategory("Travel").getName());
        assertNull(wrapper.getWeblogEntry("no-such-anchor"),
                "An anchor that matches nothing must wrap to null so the permalink page "
                        + "can render a 404");
    }

    @Test
    void theCreatorComesBackWrapped() throws Exception {
        User alice = new User();
        alice.setUserName("alice");
        alice.setScreenName("Alice A");
        UserManager users = mock(UserManager.class);
        when(weblogger.getUserManager()).thenReturn(users);
        when(users.getUserByUserName("alice")).thenReturn(alice);

        assertEquals("Alice A", wrapper.getCreator().getScreenName());
    }

    @Test
    void lastModifiedIsTheWeblogsOwn() {
        assertEquals(new Date(1_700_000_000_000L), wrapper.getLastModified(),
                "Theme caching keys on this timestamp, so it has to be the weblog's own "
                        + "rather than a fresh clock reading");
    }

    @Test
    void themeLookupsResolveThroughTheInjectedFacadesThemeManager() throws Exception {
        // Nothing is installed in any static here: the only ThemeManager in the
        // picture is the one on the facade the wrapper was GIVEN. Before plan
        // Task 17 the wrapper delegated to Weblog.getTheme(), which located the
        // manager statically and blew up in exactly this test.
        when(theme.getStylesheet()).thenReturn(null);

        assertNull(wrapper.getStylesheet());
        wrapper.getTemplateByName("About");

        verify(themes, times(2)).getTheme(weblog);
    }

    @Test
    void templateLookupsGoThroughTheWeblogsThemeAndComeBackWrapped() throws Exception {
        WeblogTemplate byAction = template("Weblog", "weblog");
        WeblogTemplate byName = template("About", "about");
        WeblogTemplate byLink = template("Archive", "archive");
        when(theme.getTemplateByAction(ComponentType.WEBLOG)).thenReturn(byAction);
        when(theme.getTemplateByName("About")).thenReturn(byName);
        when(theme.getTemplateByLink("archive")).thenReturn(byLink);
        org.mockito.Mockito.<List<? extends org.apache.roller.weblogger.pojos.ThemeTemplate>>when(
                theme.getTemplates()).thenReturn(List.of(byAction, byName));

        // Three distinct templates, so a lookup that consulted the wrong one
        // would return the wrong name rather than the right one by accident.
        assertEquals("Weblog",
                lookup(() -> wrapper.getTemplateByAction(ComponentType.WEBLOG))
                        .getName());
        assertEquals("About",
                lookup(() -> wrapper.getTemplateByName("About")).getName());
        assertEquals("Archive",
                lookup(() -> wrapper.getTemplateByLink("archive")).getName());
        assertEquals(2, wrapper.getTemplates().size());
    }

    @Test
    void theStylesheetUrlIsBuiltFromTheThemesStylesheetTemplate() throws Exception {
        WeblogTemplate stylesheet = template("Stylesheet", "custom.css");
        when(theme.getStylesheet()).thenReturn(stylesheet);
        when(urls.getWeblogPageURL(weblog, null, "custom.css", null, null, null, null, 0, false))
                .thenReturn("/roller/testblog/page/custom.css");

        assertEquals("/roller/testblog/page/custom.css?v=" + ServingSince.started() / 1000,
                wrapper.getStylesheet(), "The stylesheet link has to be resolved through the URL strategy; emitting "
                + "the template's own link would produce a URL relative to the page");
    }

    /**
     * Cloudflare keeps a .css for four hours without asking the origin, so a
     * stylesheet at a fixed address outlived the 0.3.1 deploy: new list
     * markup, old rules. The address moves whenever the stylesheet's content
     * may have -- the weblog changed, or this server started.
     */
    @Test
    void theStylesheetAddressMovesWithTheWeblog() throws Exception {
        WeblogTemplate stylesheet = template("Stylesheet", "custom.css");
        when(theme.getStylesheet()).thenReturn(stylesheet);
        when(urls.getWeblogPageURL(weblog, null, "custom.css", null, null, null, null, 0, false))
                .thenReturn("/roller/testblog/page/custom.css");
        long changed = ServingSince.started() + 5_000L;
        weblog.setLastModified(new Date(changed));

        assertEquals("/roller/testblog/page/custom.css?v=" + changed / 1000, wrapper.getStylesheet());
    }

    @Test
    void aStylesheetAddressWithAQueryAlreadyGainsTheVersionAsAnotherParameter() throws Exception {
        WeblogTemplate stylesheet = template("Stylesheet", "custom.css");
        when(theme.getStylesheet()).thenReturn(stylesheet);
        when(urls.getWeblogPageURL(weblog, null, "custom.css", null, null, null, null, 0, false))
                .thenReturn("/roller-ui/authoring/preview/testblog/page/custom.css?theme=journal");

        assertEquals("/roller-ui/authoring/preview/testblog/page/custom.css?theme=journal&v="
                + ServingSince.started() / 1000, wrapper.getStylesheet());
    }

    @Test
    void aThemeWithNoStylesheetProducesNoStylesheetLink() throws Exception {
        when(theme.getStylesheet()).thenReturn(null);

        assertNull(wrapper.getStylesheet(), "A theme with no stylesheet must produce no <link>, not one pointing at "
                + "nothing");
    }

    private static <T> T lookup(ThrowingSupplier<T> supplier) {
        try {
            return supplier.get();
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    private interface ThrowingSupplier<T> {
        T get() throws Exception;
    }

    private WeblogTemplate template(String name, String link) {
        WeblogTemplate template = new WeblogTemplate();
        template.setName(name);
        template.setLink(link);
        template.setWeblog(weblog);
        return template;
    }
}
