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

package com.wikantik.pressroom.ui.rendering.util;

import com.wikantik.pressroom.WebloggerException;
import com.wikantik.pressroom.business.WeblogEntryManager;
import com.wikantik.pressroom.business.WeblogManager;
import com.wikantik.pressroom.business.Weblogger;
import com.wikantik.pressroom.business.themes.ThemeManager;
import com.wikantik.pressroom.business.themes.SharedTheme;
import com.wikantik.pressroom.business.themes.ThemeNotFoundException;
import com.wikantik.pressroom.pojos.Theme;
import com.wikantik.pressroom.pojos.Weblog;
import com.wikantik.pressroom.pojos.WeblogCategory;
import com.wikantik.pressroom.pojos.WeblogEntry;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Covers the lazily-resolved "heavyweight" accessors on the parsed-request
 * classes -- the ones that go to the database.
 *
 * <p>{@code ParsedRequest} documents these classes as deliberately lightweight:
 * they are constructed on every request, so a lookup must happen only when
 * something asks for the object, must happen at most once, and must never
 * propagate a failure out of a getter that a Velocity template is calling
 * mid-render.
 *
 * <p>These three properties are what the tests below check for each accessor.
 * Grouping them here keeps that shared contract in one place rather than
 * repeated across every request class's own test.
 */
class LazyLookupTest {

    private static final String PAGE_SERVLET = "/pressroom-ui/rendering/page";
    private static final String FEED_SERVLET = "/pressroom-ui/rendering/feed";
    private static final String PREVIEW_SERVLET = "/pressroom-ui/authoring/preview";

    private static Weblog weblog() {
        Weblog weblog = new Weblog();
        weblog.setHandle("myblog");
        weblog.setLocale("en_US");
        return weblog;
    }

    // ------------------------------------------------------------ the weblog

    @Test
    void weblogIsLookedUpOnceByHandleAndCached() throws Exception {
        WeblogManager weblogManager = mock(WeblogManager.class);
        Weblogger weblogger = mock(Weblogger.class);
        Weblog weblog = weblog();
        when(weblogger.getWeblogManager()).thenReturn(weblogManager);
        when(weblogManager.getWeblogByHandle("myblog", Boolean.TRUE)).thenReturn(weblog);

        WeblogRequest request = new WeblogRequest(weblogger, MockRequest.with(PAGE_SERVLET, "/myblog"));

        assertSame(weblog, request.getWeblog());
        assertSame(weblog, request.getWeblog());

        verify(weblogManager, times(1)).getWeblogByHandle("myblog", Boolean.TRUE);
    }

    @Test
    void onlyVisibleWeblogsAreResolved() throws Exception {
        // The TRUE argument is what keeps a disabled weblog from rendering. If
        // it ever becomes null, disabled blogs go live again.
        WeblogManager weblogManager = mock(WeblogManager.class);
        Weblogger weblogger = mock(Weblogger.class);
        when(weblogger.getWeblogManager()).thenReturn(weblogManager);
        when(weblogManager.getWeblogByHandle(anyString(), any())).thenReturn(null);

        WeblogRequest request = new WeblogRequest(weblogger, MockRequest.with(PAGE_SERVLET, "/myblog"));

        request.getWeblog();

        verify(weblogManager).getWeblogByHandle("myblog", Boolean.TRUE);
    }

    @Test
    void aFailingWeblogLookupYieldsNullRatherThanBreakingTheRender() throws Exception {
        WeblogManager weblogManager = mock(WeblogManager.class);
        Weblogger weblogger = mock(Weblogger.class);
        when(weblogger.getWeblogManager()).thenReturn(weblogManager);
        when(weblogManager.getWeblogByHandle(anyString(), any()))
                .thenThrow(new WebloggerException("database is down"));

        WeblogRequest request = new WeblogRequest(weblogger, MockRequest.with(PAGE_SERVLET, "/myblog"));

        assertNull(request.getWeblog(),
                "A failed weblog lookup must degrade to null, not propagate "
                        + "out of a getter a template is calling");
    }

    // ------------------------------------------------------- page request bits

    @Test
    void pageRequestEntryIsLookedUpOnceByAnchorAndCached() throws Exception {
        WeblogEntryManager entryManager = mock(WeblogEntryManager.class);
        Weblogger weblogger = mock(Weblogger.class);
        WeblogEntry entry = new WeblogEntry();
        when(weblogger.getWeblogEntryManager()).thenReturn(entryManager);
        when(weblogger.getWeblogManager()).thenReturn(mock(WeblogManager.class));
        when(entryManager.getWeblogEntryByAnchor(any(), eq("hello"))).thenReturn(entry);

        WeblogPageRequest request =
                new WeblogPageRequest(weblogger, MockRequest.with(PAGE_SERVLET, "/myblog/entry/hello"));

        assertSame(entry, request.getWeblogEntry());
        assertSame(entry, request.getWeblogEntry());

        verify(entryManager, times(1)).getWeblogEntryByAnchor(any(), eq("hello"));
    }

    @Test
    void pageRequestAFailingEntryLookupYieldsNull() throws Exception {
        WeblogEntryManager entryManager = mock(WeblogEntryManager.class);
        Weblogger weblogger = mock(Weblogger.class);
        when(weblogger.getWeblogEntryManager()).thenReturn(entryManager);
        when(weblogger.getWeblogManager()).thenReturn(mock(WeblogManager.class));
        when(entryManager.getWeblogEntryByAnchor(any(), anyString()))
                .thenThrow(new WebloggerException("database is down"));

        WeblogPageRequest request =
                new WeblogPageRequest(weblogger, MockRequest.with(PAGE_SERVLET, "/myblog/entry/hello"));

        assertNull(request.getWeblogEntry());
    }

    @Test
    void pageRequestCategoryIsLookedUpOnceByNameAndCached() throws Exception {
        WeblogEntryManager entryManager = mock(WeblogEntryManager.class);
        Weblogger weblogger = mock(Weblogger.class);
        WeblogCategory category = new WeblogCategory();
        when(weblogger.getWeblogEntryManager()).thenReturn(entryManager);
        when(weblogger.getWeblogManager()).thenReturn(mock(WeblogManager.class));
        when(entryManager.getWeblogCategoryByName(any(), eq("Java"))).thenReturn(category);

        WeblogPageRequest request =
                new WeblogPageRequest(weblogger, MockRequest.with(PAGE_SERVLET, "/myblog/category/Java"));

        assertSame(category, request.getWeblogCategory());
        assertSame(category, request.getWeblogCategory());

        verify(entryManager, times(1)).getWeblogCategoryByName(any(), eq("Java"));
    }

    @Test
    void pageRequestAFailingCategoryLookupYieldsNull() throws Exception {
        WeblogEntryManager entryManager = mock(WeblogEntryManager.class);
        Weblogger weblogger = mock(Weblogger.class);
        when(weblogger.getWeblogEntryManager()).thenReturn(entryManager);
        when(weblogger.getWeblogManager()).thenReturn(mock(WeblogManager.class));
        when(entryManager.getWeblogCategoryByName(any(), anyString()))
                .thenThrow(new WebloggerException("database is down"));

        WeblogPageRequest request =
                new WeblogPageRequest(weblogger, MockRequest.with(PAGE_SERVLET, "/myblog/category/Java"));

        assertNull(request.getWeblogCategory());
    }

    @Test
    void pageRequestNoLookupHappensWithoutAnAnchorOrCategory() throws Exception {
        // The homepage names neither, so neither query may run.
        WeblogEntryManager entryManager = mock(WeblogEntryManager.class);
        Weblogger weblogger = mock(Weblogger.class);
        when(weblogger.getWeblogEntryManager()).thenReturn(entryManager);

        WeblogPageRequest request =
                new WeblogPageRequest(weblogger, MockRequest.with(PAGE_SERVLET, "/myblog"));

        assertNull(request.getWeblogEntry());
        assertNull(request.getWeblogCategory());
        assertNull(request.getWeblogPage());

        verify(weblogger, org.mockito.Mockito.never()).getWeblogEntryManager();
    }

    @Test
    void pageRequestInjectedValuesShortCircuitEveryLookup() throws Exception {
        Weblogger weblogger = mock(Weblogger.class);
        WeblogPageRequest request =
                new WeblogPageRequest(weblogger, MockRequest.with(PAGE_SERVLET, "/myblog/entry/hello"));
        WeblogEntry entry = new WeblogEntry();
        WeblogCategory category = new WeblogCategory();
        request.setWeblogEntry(entry);
        request.setWeblogCategory(category);

        assertSame(entry, request.getWeblogEntry());
        assertSame(category, request.getWeblogCategory());

        verify(weblogger, org.mockito.Mockito.never()).getWeblogEntryManager();
    }

    @Test
    void pageRequestCustomPageIsResolvedThroughTheWeblogThemeAndCached() throws Exception {
        // A custom page is looked up by link name against the weblog's theme,
        // not through a manager, so this getter has its own path to cover.
        WeblogManager weblogManager = mock(WeblogManager.class);
        Weblogger weblogger = mock(Weblogger.class);
        ThemeManager themeManager = mock(ThemeManager.class);
        Weblog weblog = mock(Weblog.class);
        com.wikantik.pressroom.pojos.WeblogTheme weblogTheme =
                mock(com.wikantik.pressroom.pojos.WeblogTheme.class);
        com.wikantik.pressroom.pojos.ThemeTemplate template =
                mock(com.wikantik.pressroom.pojos.ThemeTemplate.class);

        when(weblogger.getWeblogManager()).thenReturn(weblogManager);
        when(weblogger.getThemeManager()).thenReturn(themeManager);
        when(weblogManager.getWeblogByHandle(anyString(), any())).thenReturn(weblog);
        when(themeManager.getTheme(weblog)).thenReturn(weblogTheme);
        when(weblogTheme.getTemplateByLink("about")).thenReturn(template);

        WeblogPageRequest request =
                new WeblogPageRequest(weblogger, MockRequest.with(PAGE_SERVLET, "/myblog/page/about"));

        assertSame(template, request.getWeblogPage());
        assertSame(template, request.getWeblogPage());

        verify(weblogTheme, times(1)).getTemplateByLink("about");
    }

    @Test
    void pageRequestAFailingCustomPageLookupYieldsNull() throws Exception {
        WeblogManager weblogManager = mock(WeblogManager.class);
        Weblogger weblogger = mock(Weblogger.class);
        Weblog weblog = mock(Weblog.class);
        com.wikantik.pressroom.pojos.WeblogTheme weblogTheme =
                mock(com.wikantik.pressroom.pojos.WeblogTheme.class);

        ThemeManager themeManager = mock(ThemeManager.class);
        when(weblogger.getWeblogManager()).thenReturn(weblogManager);
        when(weblogger.getThemeManager()).thenReturn(themeManager);
        when(weblogManager.getWeblogByHandle(anyString(), any())).thenReturn(weblog);
        when(themeManager.getTheme(weblog)).thenReturn(weblogTheme);
        when(weblogTheme.getTemplateByLink(anyString()))
                .thenThrow(new WebloggerException("theme store unreadable"));

        WeblogPageRequest request =
                new WeblogPageRequest(weblogger, MockRequest.with(PAGE_SERVLET, "/myblog/page/about"));

        assertNull(request.getWeblogPage(),
                "A failed template lookup must degrade to null, not propagate");
    }

    @Test
    void pageRequestInjectedCustomPageShortCircuitsTheThemeLookup() throws Exception {
        Weblogger weblogger = mock(Weblogger.class);
        WeblogPageRequest request =
                new WeblogPageRequest(weblogger, MockRequest.with(PAGE_SERVLET, "/myblog/page/about"));
        com.wikantik.pressroom.pojos.WeblogTemplate template =
                new com.wikantik.pressroom.pojos.WeblogTemplate();
        request.setWeblogPage(template);

        assertSame(template, request.getWeblogPage());

        verify(weblogger, org.mockito.Mockito.never()).getWeblogManager();
    }

    // -------------------------------------------------- bare-slug page content

    @Test
    void pageRequestPageContentIsLookedUpOnceBySlugAndCached() throws Exception {
        WeblogManager weblogManager = mock(WeblogManager.class);
        Weblogger weblogger = mock(Weblogger.class);
        com.wikantik.pressroom.business.WeblogPageManager pageManager =
                mock(com.wikantik.pressroom.business.WeblogPageManager.class);
        Weblog weblog = weblog();
        com.wikantik.pressroom.pojos.WeblogPage page =
                new com.wikantik.pressroom.pojos.WeblogPage();
        page.setStatus(com.wikantik.pressroom.pojos.WeblogPage.PubStatus.PUBLISHED);
        when(weblogger.getWeblogManager()).thenReturn(weblogManager);
        when(weblogger.getWeblogPageManager()).thenReturn(pageManager);
        when(weblogManager.getWeblogByHandle(anyString(), any())).thenReturn(weblog);
        when(pageManager.getPageBySlug(weblog, "about")).thenReturn(page);

        WeblogPageRequest request =
                new WeblogPageRequest(weblogger, MockRequest.with(PAGE_SERVLET, "/myblog/about"));

        assertSame(page, request.getWeblogPageContent());
        assertSame(page, request.getWeblogPageContent());

        verify(pageManager, times(1)).getPageBySlug(weblog, "about");
    }

    @Test
    void pageRequestNoPageContentLookupHappensWithoutACandidateSlug() throws Exception {
        // The homepage carries no page slug at all, so lookUpPage must return
        // null immediately rather than resolving the weblog it does not need.
        Weblogger weblogger = mock(Weblogger.class);

        WeblogPageRequest request =
                new WeblogPageRequest(weblogger, MockRequest.with(PAGE_SERVLET, "/myblog"));

        assertNull(request.getWeblogPageContent());

        verify(weblogger, org.mockito.Mockito.never()).getWeblogManager();
    }

    @Test
    void pageRequestPageContentIsNullWhenTheWeblogItselfDoesNotResolve() throws Exception {
        WeblogManager weblogManager = mock(WeblogManager.class);
        Weblogger weblogger = mock(Weblogger.class);
        when(weblogger.getWeblogManager()).thenReturn(weblogManager);
        when(weblogManager.getWeblogByHandle(anyString(), any())).thenReturn(null);

        WeblogPageRequest request =
                new WeblogPageRequest(weblogger, MockRequest.with(PAGE_SERVLET, "/myblog/about"));

        assertNull(request.getWeblogPageContent(),
                "a candidate slug on a weblog that does not itself resolve must not throw");
    }

    @Test
    void pageRequestAFailingPageContentLookupYieldsNull() throws Exception {
        WeblogManager weblogManager = mock(WeblogManager.class);
        Weblogger weblogger = mock(Weblogger.class);
        com.wikantik.pressroom.business.WeblogPageManager pageManager =
                mock(com.wikantik.pressroom.business.WeblogPageManager.class);
        Weblog weblog = weblog();
        when(weblogger.getWeblogManager()).thenReturn(weblogManager);
        when(weblogger.getWeblogPageManager()).thenReturn(pageManager);
        when(weblogManager.getWeblogByHandle(anyString(), any())).thenReturn(weblog);
        when(pageManager.getPageBySlug(any(), anyString()))
                .thenThrow(new WebloggerException("database is down"));

        WeblogPageRequest request =
                new WeblogPageRequest(weblogger, MockRequest.with(PAGE_SERVLET, "/myblog/about"));

        assertNull(request.getWeblogPageContent(),
                "A failed page lookup must degrade to null, not propagate out of a "
                        + "getter a template is calling");
    }

    @Test
    void pageRequestInjectedPageContentShortCircuitsTheLookup() throws Exception {
        Weblogger weblogger = mock(Weblogger.class);
        WeblogPageRequest request =
                new WeblogPageRequest(weblogger, MockRequest.with(PAGE_SERVLET, "/myblog/about"));
        com.wikantik.pressroom.pojos.WeblogPage page =
                new com.wikantik.pressroom.pojos.WeblogPage();
        request.setWeblogPageContent(page);

        assertSame(page, request.getWeblogPageContent());

        verify(weblogger, org.mockito.Mockito.never()).getWeblogManager();
    }

    // ------------------------------------------------------- feed and search

    @Test
    void feedRequestCategoryIsLookedUpOnceAndCached() throws Exception {
        WeblogEntryManager entryManager = mock(WeblogEntryManager.class);
        Weblogger weblogger = mock(Weblogger.class);
        WeblogCategory category = new WeblogCategory();
        when(weblogger.getWeblogEntryManager()).thenReturn(entryManager);
        when(weblogger.getWeblogManager()).thenReturn(mock(WeblogManager.class));
        when(entryManager.getWeblogCategoryByName(any(), eq("Java"))).thenReturn(category);

        WeblogFeedRequest request = new WeblogFeedRequest(weblogger, MockRequest.with(FEED_SERVLET, "/myblog/entries/rss", "cat", "Java"));

        assertSame(category, request.getWeblogCategory());
        assertSame(category, request.getWeblogCategory());

        verify(entryManager, times(1)).getWeblogCategoryByName(any(), eq("Java"));
    }

    @Test
    void feedRequestAFailingCategoryLookupYieldsNull() throws Exception {
        WeblogEntryManager entryManager = mock(WeblogEntryManager.class);
        Weblogger weblogger = mock(Weblogger.class);
        when(weblogger.getWeblogEntryManager()).thenReturn(entryManager);
        when(weblogger.getWeblogManager()).thenReturn(mock(WeblogManager.class));
        when(entryManager.getWeblogCategoryByName(any(), anyString()))
                .thenThrow(new WebloggerException("database is down"));

        WeblogFeedRequest request = new WeblogFeedRequest(weblogger, MockRequest.with(FEED_SERVLET, "/myblog/entries/rss", "cat", "Java"));

        assertNull(request.getWeblogCategory());
    }

    @Test
    void feedRequestNoCategoryMeansNoLookup() throws Exception {
        Weblogger weblogger = mock(Weblogger.class);

        WeblogFeedRequest request =
                new WeblogFeedRequest(weblogger, MockRequest.with(FEED_SERVLET, "/myblog/entries/rss"));

        assertNull(request.getWeblogCategory());

        verify(weblogger, org.mockito.Mockito.never()).getWeblogEntryManager();
    }

    @Test
    void searchRequestCategoryIsLookedUpOnceAndCached() throws Exception {
        WeblogEntryManager entryManager = mock(WeblogEntryManager.class);
        Weblogger weblogger = mock(Weblogger.class);
        WeblogCategory category = new WeblogCategory();
        when(weblogger.getWeblogEntryManager()).thenReturn(entryManager);
        when(weblogger.getWeblogManager()).thenReturn(mock(WeblogManager.class));
        when(entryManager.getWeblogCategoryByName(any(), eq("Java"))).thenReturn(category);

        WeblogSearchRequest request = new WeblogSearchRequest(weblogger, MockRequest.with(WeblogSearchRequest.SEARCH_SERVLET, "/myblog",
                        "q", "pressroom", "cat", "Java"));

        assertSame(category, request.getWeblogCategory());
        assertSame(category, request.getWeblogCategory());

        verify(entryManager, times(1)).getWeblogCategoryByName(any(), eq("Java"));
    }

    @Test
    void searchRequestAFailingCategoryLookupYieldsNull() throws Exception {
        WeblogEntryManager entryManager = mock(WeblogEntryManager.class);
        Weblogger weblogger = mock(Weblogger.class);
        when(weblogger.getWeblogEntryManager()).thenReturn(entryManager);
        when(weblogger.getWeblogManager()).thenReturn(mock(WeblogManager.class));
        when(entryManager.getWeblogCategoryByName(any(), anyString()))
                .thenThrow(new WebloggerException("database is down"));

        WeblogSearchRequest request = new WeblogSearchRequest(weblogger, MockRequest.with(WeblogSearchRequest.SEARCH_SERVLET, "/myblog",
                        "q", "pressroom", "cat", "Java"));

        assertNull(request.getWeblogCategory());
    }

    // --------------------------------------------------------------- preview

    @Test
    void previewThemeIsLookedUpOnceByNameAndCached() throws Exception {
        ThemeManager themeManager = mock(ThemeManager.class);
        Weblogger weblogger = mock(Weblogger.class);
        SharedTheme theme = mock(SharedTheme.class);
        when(weblogger.getThemeManager()).thenReturn(themeManager);
        when(themeManager.getTheme("journal")).thenReturn(theme);

        WeblogPreviewRequest request = new WeblogPreviewRequest(weblogger, MockRequest.with(PREVIEW_SERVLET, "/myblog", "theme", "journal"));

        assertSame(theme, request.getTheme());
        assertSame(theme, request.getTheme());

        verify(themeManager, times(1)).getTheme("journal");
    }

    @Test
    void previewOfAThemeThatDoesNotExistFallsBackToNoTheme() throws Exception {
        // A stale ?theme= in a bookmarked preview URL must fall back to the
        // weblog's real theme rather than erroring the preview.
        ThemeManager themeManager = mock(ThemeManager.class);
        Weblogger weblogger = mock(Weblogger.class);
        when(weblogger.getThemeManager()).thenReturn(themeManager);
        when(themeManager.getTheme(anyString()))
                .thenThrow(new ThemeNotFoundException("no such theme"));

        WeblogPreviewRequest request = new WeblogPreviewRequest(weblogger, MockRequest.with(PREVIEW_SERVLET, "/myblog", "theme", "deleted"));

        assertNull(request.getTheme(),
                "An unknown theme name must yield no theme rather than an error");
    }

    @Test
    void previewAFailingThemeLookupYieldsNoTheme() throws Exception {
        ThemeManager themeManager = mock(ThemeManager.class);
        Weblogger weblogger = mock(Weblogger.class);
        when(weblogger.getThemeManager()).thenReturn(themeManager);
        when(themeManager.getTheme(anyString()))
                .thenThrow(new WebloggerException("theme store unreadable"));

        WeblogPreviewRequest request = new WeblogPreviewRequest(weblogger, MockRequest.with(PREVIEW_SERVLET, "/myblog", "theme", "journal"));

        assertNull(request.getTheme());
    }

    @Test
    void previewEntryParameterWinsOverThePathAnchor() throws Exception {
        // The editor previews the entry currently open in the form, which may
        // differ from whatever anchor the preview URL was built around.
        WeblogEntryManager entryManager = mock(WeblogEntryManager.class);
        Weblogger weblogger = mock(Weblogger.class);
        WeblogEntry entry = new WeblogEntry();
        when(weblogger.getWeblogEntryManager()).thenReturn(entryManager);
        when(weblogger.getWeblogManager()).thenReturn(mock(WeblogManager.class));
        when(entryManager.getWeblogEntryByAnchor(any(), anyString())).thenReturn(entry);

        WeblogPreviewRequest request = new WeblogPreviewRequest(weblogger, MockRequest.with(PREVIEW_SERVLET, "/myblog/entry/from-path",
                        "previewEntry", "being-edited"));

        request.getWeblogEntry();

        verify(entryManager).getWeblogEntryByAnchor(any(), eq("being-edited"));
    }

    @Test
    void previewFallsBackToThePathAnchorWhenNoPreviewEntryIsGiven() throws Exception {
        WeblogEntryManager entryManager = mock(WeblogEntryManager.class);
        Weblogger weblogger = mock(Weblogger.class);
        when(weblogger.getWeblogEntryManager()).thenReturn(entryManager);
        when(weblogger.getWeblogManager()).thenReturn(mock(WeblogManager.class));
        when(entryManager.getWeblogEntryByAnchor(any(), anyString()))
                .thenReturn(new WeblogEntry());

        WeblogPreviewRequest request = new WeblogPreviewRequest(weblogger, MockRequest.with(PREVIEW_SERVLET, "/myblog/entry/from-path"));

        request.getWeblogEntry();

        verify(entryManager).getWeblogEntryByAnchor(any(), eq("from-path"));
    }

    @Test
    void previewAFailingEntryLookupYieldsNull() throws Exception {
        WeblogEntryManager entryManager = mock(WeblogEntryManager.class);
        Weblogger weblogger = mock(Weblogger.class);
        when(weblogger.getWeblogEntryManager()).thenReturn(entryManager);
        when(weblogger.getWeblogManager()).thenReturn(mock(WeblogManager.class));
        when(entryManager.getWeblogEntryByAnchor(any(), anyString()))
                .thenThrow(new WebloggerException("database is down"));

        WeblogPreviewRequest request = new WeblogPreviewRequest(weblogger, MockRequest.with(PREVIEW_SERVLET, "/myblog", "previewEntry", "being-edited"));

        assertNull(request.getWeblogEntry());
    }

    @Test
    void previewResourceThemeIsLookedUpOnceByNameAndCached() throws Exception {
        ThemeManager themeManager = mock(ThemeManager.class);
        Weblogger weblogger = mock(Weblogger.class);
        SharedTheme theme = mock(SharedTheme.class);
        when(weblogger.getThemeManager()).thenReturn(themeManager);
        when(themeManager.getTheme("journal")).thenReturn(theme);

        WeblogPreviewResourceRequest request = new WeblogPreviewResourceRequest(weblogger, MockRequest.with("/pressroom-ui/rendering/resources", "/myblog/logo.png",
                        "theme", "journal"));

        assertSame(theme, request.getTheme());
        assertSame(theme, request.getTheme());

        verify(themeManager, times(1)).getTheme("journal");
    }

    @Test
    void previewResourceOfAThemeThatDoesNotExistFallsBackToNoTheme() throws Exception {
        ThemeManager themeManager = mock(ThemeManager.class);
        Weblogger weblogger = mock(Weblogger.class);
        when(weblogger.getThemeManager()).thenReturn(themeManager);
        when(themeManager.getTheme(anyString()))
                .thenThrow(new ThemeNotFoundException("no such theme"));

        WeblogPreviewResourceRequest request = new WeblogPreviewResourceRequest(weblogger, MockRequest.with("/pressroom-ui/rendering/resources", "/myblog/logo.png",
                        "theme", "deleted"));

        assertNull(request.getTheme());
    }

    @Test
    void previewResourceAFailingThemeLookupYieldsNoTheme() throws Exception {
        ThemeManager themeManager = mock(ThemeManager.class);
        Weblogger weblogger = mock(Weblogger.class);
        when(weblogger.getThemeManager()).thenReturn(themeManager);
        when(themeManager.getTheme(anyString()))
                .thenThrow(new WebloggerException("theme store unreadable"));

        WeblogPreviewResourceRequest request = new WeblogPreviewResourceRequest(weblogger, MockRequest.with("/pressroom-ui/rendering/resources", "/myblog/logo.png",
                        "theme", "journal"));

        assertNull(request.getTheme());
    }

    // --------------------------------------------------------------- setters

    @Test
    void injectedValuesAreReturnedAcrossTheRequestClasses() throws Exception {
        // The rendering servlets pre-populate these to avoid re-querying what
        // they have already resolved, so the setters are load-bearing.
        WeblogMediaResourceRequest media = new WeblogMediaResourceRequest();
        media.setResourceId("abc");
        media.setThumbnail(true);
        assertEquals("abc", media.getResourceId());
        assertEquals(true, media.isThumbnail());

        WeblogResourceRequest resource = new WeblogResourceRequest();
        resource.setResourcePath("images/logo.png");
        assertEquals("images/logo.png", resource.getResourcePath());

        WeblogFeedRequest feed = new WeblogFeedRequest();
        feed.setType("entries");
        feed.setFormat("atom");
        feed.setPage(3);
        feed.setExcerpts(true);
        feed.setTerm("pressroom");
        feed.setTags(java.util.List.of("java"));
        feed.setWeblogCategoryName("Java");
        assertEquals("entries", feed.getType());
        assertEquals("atom", feed.getFormat());
        assertEquals(3, feed.getPage());
        assertEquals(true, feed.isExcerpts());
        assertEquals("pressroom", feed.getTerm());
        assertEquals(java.util.List.of("java"), feed.getTags());
        assertEquals("Java", feed.getWeblogCategoryName());

        WeblogPageRequest page = new WeblogPageRequest();
        page.setContext("entry");
        page.setWeblogAnchor("hello");
        page.setWeblogDate("20240115");
        page.setWeblogCategoryName("Java");
        page.setTags(java.util.List.of("java"));
        page.setPageNum(2);
        page.setCustomParams(java.util.Map.of("k", new String[]{"v"}));
        page.setWebsitePageHit(true);
        page.setOtherPageHit(true);
        assertEquals("entry", page.getContext());
        assertEquals("hello", page.getWeblogAnchor());
        assertEquals("20240115", page.getWeblogDate());
        assertEquals("Java", page.getWeblogCategoryName());
        assertEquals(2, page.getPageNum());
        assertEquals(1, page.getCustomParams().size());
        assertEquals(true, page.isWebsitePageHit());
        assertEquals(true, page.isOtherPageHit());
    }

    @Test
    void previewSettersOverrideTheParsedValues() throws Exception {
        WeblogPreviewRequest preview = new WeblogPreviewRequest(mock(Weblogger.class), MockRequest.with(PREVIEW_SERVLET, "/myblog"));
        preview.setThemeName("other");
        preview.setPreviewEntry("being-edited");
        preview.setType("landing");

        assertEquals("other", preview.getThemeName());
        assertEquals("being-edited", preview.getPreviewEntry());
        assertEquals("landing", preview.getType());

        WeblogPreviewResourceRequest previewResource = new WeblogPreviewResourceRequest();
        previewResource.setThemeName("other");
        assertEquals("other", previewResource.getThemeName());
    }
}
