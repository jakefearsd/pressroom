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
package org.apache.roller.weblogger.ui.rendering.servlets;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

import org.apache.roller.weblogger.WebloggerException;
import org.apache.roller.weblogger.business.MockWeblogger;
import org.apache.roller.weblogger.pojos.StaticThemeTemplate;
import org.apache.roller.weblogger.pojos.ThemeTemplate;
import org.apache.roller.weblogger.pojos.ThemeTemplate.ComponentType;
import org.apache.roller.weblogger.pojos.Weblog;
import org.apache.roller.weblogger.pojos.WeblogEntry;
import org.apache.roller.weblogger.pojos.WeblogEntry.PubStatus;
import org.apache.roller.weblogger.pojos.WeblogTheme;
import org.apache.roller.weblogger.ui.rendering.util.WeblogPageRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The two decisions {@link PageServlet} makes before it renders anything:
 * which template answers a request, and whether the request is answerable at
 * all.
 *
 * <p>Both lived inside a 380-line {@code doGet} and could only be reached by
 * driving a whole request through the rendering stack, which is why between
 * them they carried most of that method's 74 uncovered branches. Extracting
 * them changed no behaviour -- the 192 existing rendering tests pass unmoved --
 * but each branch is now reachable directly.
 *
 * <p>The rejection side is worth reading as a list: every one of these ends in
 * the same anonymous 404, so this is the only place that records what each of
 * them actually means.
 */
class PageServletDecisionTest {

    private MockWeblogger weblogger;
    private Weblog weblog;
    private WeblogTheme theme;
    private WeblogPageRequest pageRequest;

    @BeforeEach
    void setUp() throws Exception {
        weblogger = MockWeblogger.attached();

        weblog = new Weblog();
        weblog.setHandle("decisionblog");

        theme = mock(WeblogTheme.class);
        when(weblogger.themeManager().getTheme(weblog)).thenReturn(theme);

        pageRequest = mock(WeblogPageRequest.class);
        when(pageRequest.getWeblog()).thenReturn(weblog);
    }

    @AfterEach
    void tearDown() {
        weblogger.detach();
    }

    // ------------------------------------------------------ template selection

    /**
     * A bare-slug page request whose slug did not resolve to a published page
     * (unknown, or a draft the reader is not entitled to see) is a 404, not a
     * fall-through to the branches below -- a page slug never doubles as a
     * permalink or the weblog's default view. {@link PageRoutingTest} covers
     * this end to end; this is the same decision in isolation.
     */
    @Test
    void aPageSlugThatDidNotResolveIsNotFound() throws Exception {
        when(pageRequest.getPageSlug()).thenReturn("no-such-page");
        when(pageRequest.getWeblogPageContent()).thenReturn(null);

        assertNull(PageServlet.selectTemplate(pageRequest, weblog, weblogger.getThemeManager()));
    }

    /**
     * A resolved page prefers the theme's own {@code _page} override.
     */
    @Test
    void aResolvedPagePrefersTheThemesOwnPageTemplate() throws Exception {
        when(pageRequest.getPageSlug()).thenReturn("about");
        when(pageRequest.getWeblogPageContent())
                .thenReturn(new org.apache.roller.weblogger.pojos.WeblogPage());
        ThemeTemplate override = mock(ThemeTemplate.class);
        when(theme.getTemplateByName("_page")).thenReturn(override);

        assertEquals(override, PageServlet.selectTemplate(pageRequest, weblog, weblogger.getThemeManager()));
    }

    /**
     * A theme with no {@code _page} override falls back to the shipped
     * default rather than 404ing -- a theme does not have to know pages
     * exist. Covers the fallback both when the theme answers null and when
     * looking it up throws.
     */
    @Test
    void aResolvedPageFallsBackToTheBundledPageTemplateWithNoThemeOverride() throws Exception {
        when(pageRequest.getPageSlug()).thenReturn("about");
        when(pageRequest.getWeblogPageContent())
                .thenReturn(new org.apache.roller.weblogger.pojos.WeblogPage());
        when(theme.getTemplateByName("_page")).thenReturn(null);

        assertInstanceOf(StaticThemeTemplate.class,
                PageServlet.selectTemplate(pageRequest, weblog, weblogger.getThemeManager()));
    }

    @Test
    void aResolvedPageFallsBackToTheBundledPageTemplateWhenTheThemeLookupThrows() throws Exception {
        when(pageRequest.getPageSlug()).thenReturn("about");
        when(pageRequest.getWeblogPageContent())
                .thenReturn(new org.apache.roller.weblogger.pojos.WeblogPage());
        when(theme.getTemplateByName("_page")).thenThrow(new WebloggerException("theme is broken"));

        assertInstanceOf(StaticThemeTemplate.class,
                PageServlet.selectTemplate(pageRequest, weblog, weblogger.getThemeManager()),
                "a broken theme lookup must fall back, not propagate a 500");
    }

    /**
     * A named page that does not exist is a 404. It must not fall through to
     * the default template, or every mistyped page url would quietly render the
     * front page and readers would never learn the link was wrong.
     */
    @Test
    void aNamedPageThatDoesNotExistDoesNotFallBackToTheDefault() throws Exception {
        when(pageRequest.getContext()).thenReturn("page");
        when(pageRequest.getWeblogPage()).thenReturn(null);
        ThemeTemplate defaultTemplate = mock(ThemeTemplate.class);
        when(theme.getDefaultTemplate()).thenReturn(defaultTemplate);

        assertNull(PageServlet.selectTemplate(pageRequest, weblog, weblogger.getThemeManager()),
                "a named page is answered by that page or by nothing");
    }

    @Test
    void aNamedPageThatExistsIsUsed() throws Exception {
        ThemeTemplate named = mock(ThemeTemplate.class);
        when(pageRequest.getContext()).thenReturn("page");
        when(pageRequest.getWeblogPage()).thenReturn(named);

        assertEquals(named, PageServlet.selectTemplate(pageRequest, weblog, weblogger.getThemeManager()));
    }

    /**
     * No bundled theme ships a tagsIndex template, yet every theme's
     * permalink links its tags at {@code /<handle>/tags/<tag>}. Those links
     * used to 404; a tags index now falls back to the theme's default
     * template, whose pager already filters by the request's tags. An unknown
     * tag combination is still a 404, through {@code rejectionReason}.
     */
    @Test
    void aTagsIndexWithoutACustomTemplateFallsBackToTheDefault() throws Exception {
        ThemeTemplate defaultTemplate = mock(ThemeTemplate.class);
        when(pageRequest.getContext()).thenReturn("tags");
        when(pageRequest.getTags()).thenReturn(List.of("spain"));
        when(theme.getTemplateByAction(ComponentType.TAGSINDEX)).thenReturn(null);
        when(theme.getDefaultTemplate()).thenReturn(defaultTemplate);

        assertEquals(defaultTemplate,
                PageServlet.selectTemplate(pageRequest, weblog, weblogger.getThemeManager()),
                "a tags index without its own template renders through the default");
    }

    @Test
    void aTagsIndexPrefersATagsIndexTemplateWhenTheThemeHasOne() throws Exception {
        ThemeTemplate tagsIndex = mock(ThemeTemplate.class);
        when(pageRequest.getContext()).thenReturn("tags");
        when(pageRequest.getTags()).thenReturn(List.of("spain"));
        when(theme.getTemplateByAction(ComponentType.TAGSINDEX)).thenReturn(tagsIndex);
        when(theme.getDefaultTemplate()).thenReturn(mock(ThemeTemplate.class));

        assertEquals(tagsIndex,
                PageServlet.selectTemplate(pageRequest, weblog, weblogger.getThemeManager()));
    }

    /**
     * A permalink is the one context that may fall back: a theme without a
     * dedicated permalink template still shows the entry through its default
     * page.
     */
    @Test
    void aPermalinkFallsBackToTheDefaultTemplate() throws Exception {
        ThemeTemplate defaultTemplate = mock(ThemeTemplate.class);
        when(pageRequest.getWeblogAnchor()).thenReturn("some-post");
        when(theme.getTemplateByAction(ComponentType.PERMALINK)).thenReturn(null);
        when(theme.getDefaultTemplate()).thenReturn(defaultTemplate);

        assertEquals(defaultTemplate,
                PageServlet.selectTemplate(pageRequest, weblog, weblogger.getThemeManager()));
    }

    @Test
    void aPermalinkPrefersAPermalinkTemplateWhenTheThemeHasOne() throws Exception {
        ThemeTemplate permalink = mock(ThemeTemplate.class);
        when(pageRequest.getWeblogAnchor()).thenReturn("some-post");
        when(theme.getTemplateByAction(ComponentType.PERMALINK)).thenReturn(permalink);

        assertEquals(permalink, PageServlet.selectTemplate(pageRequest, weblog, weblogger.getThemeManager()));
    }

    @Test
    void anOrdinaryRequestGetsTheDefaultTemplate() throws Exception {
        ThemeTemplate defaultTemplate = mock(ThemeTemplate.class);
        when(theme.getDefaultTemplate()).thenReturn(defaultTemplate);

        assertEquals(defaultTemplate,
                PageServlet.selectTemplate(pageRequest, weblog, weblogger.getThemeManager()));
    }

    /**
     * A theme that throws while being read must produce a 404, not a 500. The
     * theme is on disk and can be edited underneath a running server.
     */
    @Test
    void aThemeThatFailsToLoadYieldsNoTemplateRatherThanAnError() throws Exception {
        when(theme.getDefaultTemplate()).thenThrow(new WebloggerException("theme is broken"));

        assertNull(PageServlet.selectTemplate(pageRequest, weblog, weblogger.getThemeManager()));
    }

    // --------------------------------------------------------------- rejection

    @Test
    void anOrdinaryRequestIsNotRejected() throws Exception {
        assertNull(PageServlet.rejectionReason(pageRequest, weblog, template(), false, weblogger.weblogEntryManager()));
    }

    /**
     * A hidden template is reachable by rendering but not by url -- it is an
     * include, not a page.
     */
    @Test
    void aHiddenTemplateRequestedByNameIsRejected() throws Exception {
        ThemeTemplate hidden = template();
        when(hidden.isHidden()).thenReturn(true);
        when(pageRequest.getWeblogPageName()).thenReturn("_sidebar");

        assertEquals("template is hidden",
                PageServlet.rejectionReason(pageRequest, weblog, hidden, false, weblogger.weblogEntryManager()));
    }

    /**
     * Multi-locale weblogs are gone: a request naming a locale is always
     * rejected now, whatever the weblog. There is no longer a setting that
     * can make this allowed.
     */
    @Test
    void aLocaleViewIsAlwaysRejected() throws Exception {
        when(pageRequest.getLocale()).thenReturn("fr");

        assertNotNull(PageServlet.rejectionReason(pageRequest, weblog, template(), false, weblogger.weblogEntryManager()));
    }

    // -- permalinks: the four ways one can fail to be servable ---------------

    @Test
    void aPermalinkForAnEntryThatDoesNotExistIsRejected() throws Exception {
        when(pageRequest.getWeblogAnchor()).thenReturn("ghost");
        when(pageRequest.getWeblogEntry()).thenReturn(null);

        assertEquals("no entry with that anchor",
                PageServlet.rejectionReason(pageRequest, weblog, template(), false, weblogger.weblogEntryManager()));
    }

    /**
     * The draft gate on the public side. {@code PageServletRenderingTest}
     * covers it end to end; this is the same rule where the decision is made.
     */
    @Test
    void aPermalinkForAnUnpublishedEntryIsRejected() throws Exception {
        givenPermalink(entry(PubStatus.DRAFT, Instant.now().minusSeconds(60), "en_US"));

        assertEquals("entry is not published",
                PageServlet.rejectionReason(pageRequest, weblog, template(), false, weblogger.weblogEntryManager()));
    }

    /**
     * A scheduled entry is published in status but not yet in time, so the
     * publication time is checked separately. Without this a post scheduled
     * for next week would be readable by anyone guessing its permalink.
     */
    @Test
    void aPermalinkForAnEntryDatedInTheFutureIsRejected() throws Exception {
        givenPermalink(entry(PubStatus.PUBLISHED, Instant.now().plusSeconds(86_400), "en_US"));

        assertEquals("entry is scheduled for the future",
                PageServlet.rejectionReason(pageRequest, weblog, template(), false, weblogger.weblogEntryManager()));
    }

    /**
     * The per-entry "wrong locale" check below this in {@code rejectionReason}
     * is unreachable now: any request naming a locale is already rejected by
     * {@link #aLocaleViewIsAlwaysRejected}'s check before it gets there. A
     * permalink is rejected the same generic way once its request carries a
     * locale at all.
     */
    @Test
    void aPermalinkRequestedWithALocaleIsRejected() throws Exception {
        givenPermalink(entry(PubStatus.PUBLISHED, Instant.now().minusSeconds(60), "fr_FR"));
        when(pageRequest.getLocale()).thenReturn("en");

        assertNotNull(PageServlet.rejectionReason(pageRequest, weblog, template(), false, weblogger.weblogEntryManager()));
    }

    @Test
    void aPermalinkForAPublishedEntryIsServed() throws Exception {
        givenPermalink(entry(PubStatus.PUBLISHED, Instant.now().minusSeconds(60), "en_US"));

        assertNull(PageServlet.rejectionReason(pageRequest, weblog, template(), false, weblogger.weblogEntryManager()));
    }

    // -- categories and tags -------------------------------------------------

    @Test
    void aCategoryPageForACategoryThatDoesNotExistIsRejected() throws Exception {
        when(pageRequest.getWeblogCategoryName()).thenReturn("Nonsense");
        when(pageRequest.getWeblogCategory()).thenReturn(null);

        assertEquals("no category by that name",
                PageServlet.rejectionReason(pageRequest, weblog, template(), false, weblogger.weblogEntryManager()));
    }

    @Test
    void aTagComboNoEntryCarriesIsRejected() throws Exception {
        when(pageRequest.getTags()).thenReturn(List.of("spain", "antarctica"));
        when(weblogger.weblogEntryManager().getTagComboExists(any(), any())).thenReturn(false);

        assertEquals("no entries carry that combination of tags",
                PageServlet.rejectionReason(pageRequest, weblog, template(), false, weblogger.weblogEntryManager()));
    }

    @Test
    void aTagComboThatExistsIsServed() throws Exception {
        when(pageRequest.getTags()).thenReturn(List.of("spain"));
        when(weblogger.weblogEntryManager().getTagComboExists(any(), any())).thenReturn(true);

        assertNull(PageServlet.rejectionReason(pageRequest, weblog, template(), false, weblogger.weblogEntryManager()));
    }

    /**
     * A site-wide render searches tags across every weblog, so it must not pass
     * a weblog to scope the lookup by.
     */
    @Test
    void aSiteWideTagLookupIsNotScopedToOneWeblog() throws Exception {
        when(pageRequest.getTags()).thenReturn(List.of("spain"));
        when(weblogger.weblogEntryManager().getTagComboExists(any(), any())).thenReturn(true);

        PageServlet.rejectionReason(pageRequest, weblog, template(), true, weblogger.weblogEntryManager());

        org.mockito.Mockito.verify(weblogger.weblogEntryManager())
                .getTagComboExists(List.of("spain"), null);
    }

    @Test
    void aFailedTagLookupIsRejectedRatherThanThrown() throws Exception {
        when(pageRequest.getTags()).thenReturn(List.of("spain"));
        when(weblogger.weblogEntryManager().getTagComboExists(any(), any()))
                .thenThrow(new WebloggerException("database is unhappy"));

        assertTrue(PageServlet.rejectionReason(pageRequest, weblog, template(), false, weblogger.weblogEntryManager())
                        .startsWith("tag lookup failed"),
                "a failing lookup is a 404, not a 500");
    }

    // ------------------------------------------------------------ content type

    @Test
    void aTemplateDeclaringItsOwnContentTypeKeepsIt() {
        ThemeTemplate page = mock(ThemeTemplate.class);
        when(page.getOutputContentType()).thenReturn("application/rss+xml");

        assertEquals("application/rss+xml; charset=utf-8", PageServlet.resolveContentType(page));
    }

    @Test
    void aTemplateWithNoLinkAndNoDeclarationIsHtml() {
        ThemeTemplate page = mock(ThemeTemplate.class);
        when(page.getLink()).thenReturn(null);

        assertEquals("text/html; charset=utf-8", PageServlet.resolveContentType(page));
    }

    // ---------------------------------------------------------------- helpers

    private ThemeTemplate template() {
        return mock(ThemeTemplate.class);
    }

    private void givenPermalink(WeblogEntry entry) {
        when(pageRequest.getWeblogAnchor()).thenReturn(entry.getAnchor());
        when(pageRequest.getWeblogEntry()).thenReturn(entry);
    }

    private static WeblogEntry entry(PubStatus status, Instant pubTime, String locale) {
        WeblogEntry entry = new WeblogEntry();
        entry.setAnchor("an-entry");
        entry.setStatus(status);
        entry.setPubTime(new Timestamp(pubTime.toEpochMilli()));
        entry.setLocale(locale);
        return entry;
    }
}
