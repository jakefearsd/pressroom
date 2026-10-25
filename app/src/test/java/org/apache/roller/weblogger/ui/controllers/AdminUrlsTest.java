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
package org.apache.roller.weblogger.ui.controllers;

import java.util.concurrent.atomic.AtomicInteger;

import org.apache.roller.weblogger.business.URLStrategy;
import org.apache.roller.weblogger.pojos.MediaFile;
import org.apache.roller.weblogger.pojos.Weblog;
import org.apache.roller.weblogger.pojos.WeblogEntry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * {@link AdminUrls} is the one place the admin JSPs build weblog-content URLs
 * (exposed as the {@code urls} model attribute by {@link BaseController}). It
 * replaced five entity getters -- {@code Weblog.getURL()/getAbsoluteURL()},
 * {@code WeblogEntry.getPermalink()}, {@code MediaFile.getPermalink()/
 * getThumbnailURL()} -- that reached the {@link URLStrategy} through the
 * static service locator from inside JPA entities. Each method here must ask
 * the strategy for exactly what the getter it replaced asked for, or the
 * admin screens would link to a different address than before.
 */
class AdminUrlsTest {

    private URLStrategy strategy;
    private AdminUrls urls;
    private Weblog weblog;

    @BeforeEach
    void setUp() {
        strategy = mock(URLStrategy.class);
        urls = new AdminUrls(() -> strategy);
        weblog = new Weblog();
        weblog.setHandle("testblog");
    }

    @Test
    void weblogAsksForTheContextRelativeForm() {
        when(strategy.getWeblogURL(weblog, null, false)).thenReturn("/roller/testblog/");

        assertEquals("/roller/testblog/", urls.weblog(weblog),
                "weblog(w) replaced Weblog.getURL(), which asked for the relative form");
        verify(strategy).getWeblogURL(weblog, null, false);
    }

    @Test
    void weblogAbsoluteAsksForTheAbsoluteForm() {
        when(strategy.getWeblogURL(weblog, null, true)).thenReturn("http://example.com/roller/testblog/");

        assertEquals("http://example.com/roller/testblog/", urls.weblogAbsolute(weblog),
                "weblogAbsolute(w) replaced Weblog.getAbsoluteURL() -- the settings page, the "
                        + "theme page and the user-status tile all link the weblog's public address");
        verify(strategy).getWeblogURL(weblog, null, true);
    }

    @Test
    void staticPageAsksTheStrategyForTheAbsolutePageUrl() {
        when(strategy.getWeblogStaticPageURL(weblog, "about", true))
                .thenReturn("http://example.com/roller/about");

        assertEquals("http://example.com/roller/about", urls.staticPage(weblog, "about"),
                "an admin link to a page must come from the one rule the renderer uses");
        verify(strategy).getWeblogStaticPageURL(weblog, "about", true);
    }

    @Test
    void entryAsksForTheAbsolutePermalinkOfTheEntrysOwnWeblog() {
        WeblogEntry entry = new WeblogEntry();
        entry.setWebsite(weblog);
        entry.setAnchor("hello-world");
        when(strategy.getWeblogEntryURL(weblog, null, "hello-world", true))
                .thenReturn("http://example.com/roller/testblog/entry/hello-world");

        assertEquals("http://example.com/roller/testblog/entry/hello-world", urls.entry(entry),
                "entry(e) replaced WeblogEntry.getPermalink(): absolute, no locale, the entry's anchor");
        verify(strategy).getWeblogEntryURL(weblog, null, "hello-world", true);
    }

    @Test
    void mediaAsksForTheAbsoluteMediaUrlByFileId() {
        MediaFile file = new MediaFile();
        file.setId("file-1");
        file.setWeblog(weblog);
        when(strategy.getMediaFileURL(weblog, "file-1", true)).thenReturn("http://example.com/m/file-1");

        assertEquals("http://example.com/m/file-1", urls.media(file),
                "media(f) replaced MediaFile.getPermalink()");
        verify(strategy).getMediaFileURL(weblog, "file-1", true);
    }

    @Test
    void mediaThumbnailAsksForTheAbsoluteThumbnailUrlByFileId() {
        MediaFile file = new MediaFile();
        file.setId("file-1");
        file.setWeblog(weblog);
        when(strategy.getMediaFileThumbnailURL(weblog, "file-1", true)).thenReturn("http://example.com/m/file-1?t=true");

        assertEquals("http://example.com/m/file-1?t=true", urls.mediaThumbnail(file),
                "mediaThumbnail(f) replaced MediaFile.getThumbnailURL()");
        verify(strategy).getMediaFileThumbnailURL(weblog, "file-1", true);
    }

    /**
     * The helper is a {@code @ModelAttribute} on {@link BaseController}, so it
     * is built for every request -- including the install wizard's, before
     * the business tier exists. It must therefore not touch the strategy (a
     * {@code @Lazy Weblogger} proxy would try to build the whole graph) until
     * a JSP actually asks for a url, and it must ask only once.
     */
    @Test
    void theStrategyIsResolvedLazilyAndOnce() {
        AtomicInteger resolutions = new AtomicInteger();
        AdminUrls lazy = new AdminUrls(() -> {
            resolutions.incrementAndGet();
            return strategy;
        });
        assertEquals(0, resolutions.get(), "constructing the helper must not resolve the strategy");

        lazy.weblog(weblog);
        lazy.weblogAbsolute(weblog);
        assertEquals(1, resolutions.get(), "the strategy is resolved on first use and then kept");
    }

    @Test
    void constructingTheHelperDoesNotTouchTheStrategy() {
        verifyNoInteractions(strategy);
        assertThrows(NullPointerException.class, () -> urls.weblog(null),
                "a null weblog is a caller bug, not something to build a url for");
        verify(strategy, org.mockito.Mockito.never()).getWeblogURL(any(), any(), org.mockito.ArgumentMatchers.anyBoolean());
    }
}
