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
package com.wikantik.pressroom.ui.rendering.model;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.wikantik.pressroom.WebloggerException;
import com.wikantik.pressroom.business.MultiWeblogURLStrategy;
import com.wikantik.pressroom.business.PropertiesManager;
import com.wikantik.pressroom.business.WeblogManager;
import com.wikantik.pressroom.business.Weblogger;
import com.wikantik.pressroom.business.discovery.BlogCard;
import com.wikantik.pressroom.business.discovery.DiscoveryManager;
import com.wikantik.pressroom.business.discovery.FrontPageSelection;
import com.wikantik.pressroom.config.RuntimeConfigAttachment;
import com.wikantik.pressroom.config.WebloggerRuntimeConfig;
import com.wikantik.pressroom.pojos.Weblog;
import com.wikantik.pressroom.pojos.WeblogEntry;
import com.wikantik.pressroom.pojos.wrapper.WeblogEntryWrapper;
import com.wikantik.pressroom.pojos.wrapper.WeblogWrapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@code $discovery}: the picker's selections as the template objects themes
 * already know -- every entry a {@link WeblogEntryWrapper}, every weblog a
 * {@link WeblogWrapper} -- built from the url strategy the renderer handed in.
 * The model is on every page, so it asks the picker for nothing until a
 * template does.
 */
class DiscoveryModelTest {

    private RuntimeConfigAttachment runtimeConfig;
    private String previousAbsoluteContextURL;
    private Weblogger weblogger;
    private DiscoveryManager discovery;
    private WeblogManager weblogs;
    private MultiWeblogURLStrategy urls;

    @BeforeEach
    void setUp() {
        runtimeConfig = RuntimeConfigAttachment.of(mock(PropertiesManager.class));
        previousAbsoluteContextURL = WebloggerRuntimeConfig.getAbsoluteContextURL();
        WebloggerRuntimeConfig.setAbsoluteContextURL("http://localhost:8080/pressroom");
        weblogger = mock(Weblogger.class);
        discovery = mock(DiscoveryManager.class);
        weblogs = mock(WeblogManager.class);
        when(weblogger.getDiscoveryManager()).thenReturn(discovery);
        when(weblogger.getWeblogManager()).thenReturn(weblogs);
        urls = new MultiWeblogURLStrategy();
    }

    @AfterEach
    void tearDown() {
        WebloggerRuntimeConfig.setAbsoluteContextURL(previousAbsoluteContextURL);
        runtimeConfig.close();
    }

    private DiscoveryModel model() throws WebloggerException {
        Map<String, Object> initData = new HashMap<>();
        initData.put("weblogger", weblogger);
        initData.put("urlStrategy", urls);
        DiscoveryModel model = new DiscoveryModel();
        model.init(initData);
        return model;
    }

    private static Weblog weblog(String handle, String name) {
        Weblog weblog = new Weblog(handle, "someone", name, "", "a@example.com", "journal", "en_US", "UTC");
        weblog.setId(handle + "-id");
        return weblog;
    }

    private static WeblogEntry entry(Weblog weblog, String anchor) {
        WeblogEntry entry = new WeblogEntry();
        entry.setId(anchor + "-id");
        entry.setAnchor(anchor);
        entry.setTitle(anchor);
        entry.setWebsite(weblog);
        return entry;
    }

    @Test
    void themesReachItAsDiscovery() {
        assertEquals("discovery", new DiscoveryModel().getModelName());
    }

    @Test
    void initRefusesMissingFacadeOrStrategy() {
        Map<String, Object> noStrategy = new HashMap<>(Map.of("weblogger", weblogger));
        assertThrows(WebloggerException.class, () -> new DiscoveryModel().init(noStrategy));
        Map<String, Object> noFacade = new HashMap<>(Map.of("urlStrategy", urls));
        assertThrows(WebloggerException.class, () -> new DiscoveryModel().init(noFacade));
    }

    @Test
    void theFrontPageIsTheSelectionWrapped() throws Exception {
        Weblog a = weblog("a", "Alpha & Co");
        Weblog b = weblog("b", "Beta");
        WeblogEntry lead = entry(a, "lead");
        WeblogEntry pop = entry(b, "pop");
        WeblogEntry top = entry(b, "top");
        WeblogEntry odd = entry(a, "odd");
        WeblogEntry fresh = entry(b, "fresh");
        when(discovery.selectFrontPage()).thenReturn(new FrontPageSelection(lead, true, List.of(pop),
                List.of(new BlogCard(b, List.of(top))), List.of(odd), List.of(a, b), List.of(fresh)));

        DiscoveryModel.FrontPage page = model().getFrontPage();

        assertSame(lead, page.headline().getPojo());
        assertTrue(page.headlineRanked(), "whether the headline was ranked passes through");
        assertSame(pop, page.popular().get(0).getPojo());
        assertSame(b, page.blogs().get(0).weblog().getPojo());
        assertSame(top, page.blogs().get(0).topEntries().get(0).getPojo());
        assertSame(odd, page.different().get(0).getPojo());
        assertSame(fresh, page.latest().get(0).getPojo());
        assertEquals(List.of("Alpha &amp; Co", "Beta"),
                page.allBlogs().stream().map(WeblogWrapper::getName).toList(),
                "the wrapper escapes weblog names");
        assertEquals("http://localhost:8080/pressroom/b/entry/pop", page.popular().get(0).getPermalink(),
                "wrapped with the strategy the renderer gave");
    }

    /**
     * A blog's mark on the front page: the first letter of its first two
     * words, a leading "The" or "A" skipped, letters and digits only, so the
     * mark needs no escaping whatever the name holds.
     */
    @Test
    void initialsAreTheFirstLettersOfTheFirstTwoWords() throws Exception {
        DiscoveryModel model = model();
        assertEquals("EB", model.initials(wrap(weblog("eb", "Exploring Berlin!"))));
        assertEquals("CF", model.initials(wrap(weblog("cf", "The California Foothills"))));
        assertEquals("SD", model.initials(wrap(weblog("sd", "San Diego Local Wiki"))));
        assertEquals("W", model.initials(wrap(weblog("w", "Wikantik"))));
        assertEquals("SC", model.initials(wrap(weblog("x", "<script>x</script>\" & co"))),
                "markup and punctuation give no letter of their own");
        assertEquals("", model.initials(wrap(weblog("n", "!!"))));
        assertEquals("", model.initials(null));
    }

    private WeblogWrapper wrap(Weblog weblog) {
        return WeblogWrapper.wrap(weblog, urls, weblogger);
    }

    @Test
    void theFrontPageIsAskedForOnlyWhenATemplateDoes() throws Exception {
        when(discovery.selectFrontPage()).thenReturn(FrontPageSelection.empty());

        DiscoveryModel model = model();
        verify(discovery, never()).selectFrontPage();

        DiscoveryModel.FrontPage page = model.getFrontPage();
        assertSame(page, model.getFrontPage(), "asked once per render");
        verify(discovery, times(1)).selectFrontPage();
        assertNull(page.headline());
        assertFalse(page.headlineRanked());
        assertTrue(page.popular().isEmpty() && page.blogs().isEmpty() && page.different().isEmpty()
                && page.allBlogs().isEmpty() && page.latest().isEmpty());
    }

    @Test
    void moreFromSiteIsThreePostsFromOtherBlogs() throws Exception {
        Weblog current = weblog("cur", "Current");
        WeblogEntry other = entry(weblog("o", "Other"), "other");
        when(discovery.selectMoreFromSite(current, 3)).thenReturn(List.of(other));

        List<WeblogEntryWrapper> more = model().moreFromSite(WeblogWrapper.wrap(current, urls, weblogger));

        assertEquals(1, more.size());
        assertSame(other, more.get(0).getPojo());
    }

    @Test
    void moreFromSiteIsEmptyOnTheSiteSpaceAnOptedOutBlogOrNoBlog() throws Exception {
        Weblog site = weblog(Weblog.SITE_SPACE_HANDLE, "Site");
        site.setSiteSpace(true);
        Weblog optedOut = weblog("quiet", "Quiet");
        optedOut.setShowMoreFromSite(false);
        DiscoveryModel model = model();

        assertTrue(model.moreFromSite(WeblogWrapper.wrap(site, urls, weblogger)).isEmpty());
        assertTrue(model.moreFromSite(WeblogWrapper.wrap(optedOut, urls, weblogger)).isEmpty());
        assertTrue(model.moreFromSite(null).isEmpty());
        verify(discovery, never()).selectMoreFromSite(any(), anyInt());
    }

    /**
     * The picker is asked from the footer of every blog page and for the
     * whole of {@code /}: a failure there is a display problem, so the block
     * or the sections render empty instead of 500ing the page.
     */
    @Test
    void aPickerFailureLeavesTheFrontPageEmpty() throws Exception {
        when(discovery.selectFrontPage()).thenThrow(new IllegalStateException("picker broke"));

        DiscoveryModel.FrontPage page = model().getFrontPage();

        assertNull(page.headline());
        assertFalse(page.headlineRanked());
        assertTrue(page.popular().isEmpty() && page.blogs().isEmpty() && page.different().isEmpty()
                && page.allBlogs().isEmpty());
    }

    @Test
    void aPickerFailureLeavesMoreFromSiteEmpty() throws Exception {
        Weblog current = weblog("cur", "Current");
        when(discovery.selectMoreFromSite(any(), anyInt())).thenThrow(new IllegalStateException("picker broke"));

        assertTrue(model().moreFromSite(WeblogWrapper.wrap(current, urls, weblogger)).isEmpty());
    }

    @Test
    void theHomeUrlIsTheSiteRoot() throws Exception {
        Weblog site = weblog(Weblog.SITE_SPACE_HANDLE, "Site");
        site.setSiteSpace(true);
        when(weblogs.getSiteSpace()).thenReturn(site);

        assertEquals("http://localhost:8080/pressroom/", model().getHomeUrl());
    }

    /** Before the site space exists, or when it cannot be looked up, {@code /} is still the root. */
    @Test
    void theHomeUrlNeedsNoSiteSpace() throws Exception {
        when(weblogs.getSiteSpace()).thenReturn(null);
        assertEquals("http://localhost:8080/pressroom/", model().getHomeUrl());

        when(weblogs.getSiteSpace()).thenThrow(new WebloggerException("database down"));
        assertEquals("http://localhost:8080/pressroom/", model().getHomeUrl());
    }
}
