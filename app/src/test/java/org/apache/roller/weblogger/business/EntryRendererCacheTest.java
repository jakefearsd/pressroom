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
package org.apache.roller.weblogger.business;

import java.sql.Timestamp;
import java.util.Date;
import java.util.List;
import java.util.Map;

import org.apache.roller.weblogger.WebloggerException;
import org.apache.roller.weblogger.business.plugins.PluginManager;
import org.apache.roller.weblogger.business.plugins.entry.WeblogEntryPlugin;
import org.apache.roller.weblogger.business.shortcodes.ShortcodeCard;
import org.apache.roller.weblogger.business.shortcodes.ShortcodeContext;
import org.apache.roller.weblogger.business.shortcodes.ShortcodeExpander;
import org.apache.roller.weblogger.business.shortcodes.ShortcodeHandler;
import org.apache.roller.weblogger.business.shortcodes.ShortcodeReads;
import org.apache.roller.weblogger.config.WebloggerRuntimeConfig;
import org.apache.roller.weblogger.pojos.MediaFile;
import org.apache.roller.weblogger.pojos.Weblog;
import org.apache.roller.weblogger.pojos.WeblogEntry;
import org.apache.roller.weblogger.pojos.WeblogEntry.PubStatus;
import org.apache.roller.weblogger.util.HTMLSanitizer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The live render path's entry-HTML cache, through {@link EntryRenderer}: an
 * unchanged entry is not run through the pipeline twice, and every input its
 * HTML depends on makes a fresh render when it changes.
 *
 * <p>The pipeline is observed through a {@code [probe]} shortcode that counts
 * its renders and prints {@link #probeOutput}, so a test can change what the
 * pipeline WOULD produce without touching the entry -- the only way to tell a
 * fresh render from a stale hit when the input under test is not the text.
 */
class EntryRendererCacheTest {

    private static final String SITE = "https://example.com/";

    private Weblog weblog;
    private WeblogEntry entry;
    private PluginManager pluginManager;
    private URLStrategy urls;
    private EntryRenderer renderer;

    private int probeRenders;
    private String probeOutput = "one";

    private String savedRelativeContextURL;
    private Boolean savedXssEnabled;

    /** Counts its renders; reports reading from {@link #probeReadsFrom} when set. */
    private Weblog probeReadsFrom;

    private final ShortcodeHandler probe = new ShortcodeHandler() {
        @Override
        public String getName() {
            return "probe";
        }

        @Override
        public ShortcodeCard getCard() {
            return new ShortcodeCard("probe", "probe.label", "[probe]", false);
        }

        @Override
        public String render(Map<String, String> attributes, String body, ShortcodeContext content) {
            return render(attributes, body, content, ShortcodeReads.IGNORED);
        }

        @Override
        public String render(Map<String, String> attributes, String body, ShortcodeContext content,
                             ShortcodeReads reads) {
            probeRenders++;
            if (probeReadsFrom != null) {
                reads.readFrom(probeReadsFrom);
            }
            return "<b>" + probeOutput + "</b>";
        }
    };

    @BeforeEach
    void setUp() {
        savedRelativeContextURL = WebloggerRuntimeConfig.getRelativeContextURL();
        savedXssEnabled = HTMLSanitizer.xssEnabled;
        WebloggerRuntimeConfig.setRelativeContextURL("");

        weblog = new Weblog();
        weblog.setHandle("cacheblog");
        weblog.setLocale("en_US");
        weblog.setLastModified(new Date(1_000_000L));

        entry = new WeblogEntry();
        entry.setWebsite(weblog);
        entry.setAnchor("cached-entry");
        entry.setStatus(PubStatus.PUBLISHED);
        entry.setUpdateTime(new Timestamp(500_000L));
        entry.setText("Body [probe]");
        entry.setSummary("Teaser [probe]");

        pluginManager = mock(PluginManager.class);
        when(pluginManager.getWeblogEntryPlugins(any())).thenReturn(Map.of());
        urls = mock(URLStrategy.class);
        when(urls.getWeblogURL(any(Weblog.class), any(), any(Boolean.class))).thenReturn(SITE + "cacheblog/");

        renderer = new EntryRenderer(new ShortcodeExpander(List.of(probe)), pluginManager, urls,
                new EntryHtmlCache(1024 * 1024, 64 * 1024, 3_600_000L, System::currentTimeMillis));
    }

    @AfterEach
    void tearDown() {
        WebloggerRuntimeConfig.setRelativeContextURL(savedRelativeContextURL);
        HTMLSanitizer.xssEnabled = savedXssEnabled;
    }

    // ------------------------------------------------------------- hits

    @Test
    void aSecondRenderOfAnUnchangedEntryDoesNotRunThePipeline() {
        String first = renderer.cachedTransformedText(entry);
        String second = renderer.cachedTransformedText(entry);

        assertEquals("<p>Body <b>one</b></p>\n", first);
        assertEquals(first, second);
        assertEquals(1, probeRenders, "the second render must be served from the cache");
    }

    @Test
    void eachPartIsCachedOnItsOwn() {
        assertEquals("<p>Teaser <b>one</b></p>\n", renderer.cachedTransformedSummary(entry));
        assertEquals("<p>Body <b>one</b></p>\n", renderer.cachedDisplayContent(entry, null));
        int afterFirstRenders = probeRenders;

        assertEquals("<p>Teaser <b>one</b></p>\n", renderer.cachedTransformedSummary(entry));
        assertEquals("<p>Body <b>one</b></p>\n", renderer.cachedDisplayContent(entry, null));
        assertEquals(afterFirstRenders, probeRenders);
    }

    @Test
    void theCachedPathRendersExactlyWhatTheUncachedPathDoes() {
        assertEquals(renderer.transformedText(entry), renderer.cachedTransformedText(entry));
        assertEquals(renderer.transformedSummary(entry), renderer.cachedTransformedSummary(entry));
        assertEquals(renderer.displayContent(entry, null), renderer.cachedDisplayContent(entry, null));
        assertEquals(renderer.displayContent(entry, "nil"), renderer.cachedDisplayContent(entry, "nil"));
        assertEquals(renderer.displayContent(entry, "https://example.com/more"),
                renderer.cachedDisplayContent(entry, "https://example.com/more"));
        entry.setText("");
        assertEquals(renderer.displayContent(entry, null), renderer.cachedDisplayContent(entry, null),
                "an entry with no body displays its summary on both paths");
    }

    @Test
    void theReadMoreFormReusesTheCachedSummary() {
        String first = renderer.cachedDisplayContent(entry, "https://example.com/more");
        int afterFirst = probeRenders;
        String second = renderer.cachedDisplayContent(entry, "https://example.com/more");

        assertEquals(first, second);
        assertTrue(second.startsWith("<p>Teaser <b>one</b></p>"), second);
        assertTrue(second.contains("https://example.com/more"), second);
        assertEquals(afterFirst, probeRenders);
    }

    // ------------------------------------------- every input makes a fresh render

    @Test
    void changedTextRendersAfresh() {
        renderer.cachedTransformedText(entry);
        entry.setText("Edited [probe]");

        assertEquals("<p>Edited <b>one</b></p>\n", renderer.cachedTransformedText(entry));
    }

    @Test
    void changedSummaryRendersAfresh() {
        renderer.cachedTransformedSummary(entry);
        entry.setSummary("New teaser [probe]");

        assertEquals("<p>New teaser <b>one</b></p>\n", renderer.cachedTransformedSummary(entry));
    }

    @Test
    void aMovedWeblogLastModifiedRendersAfresh() {
        // Everything a shortcode reads from the weblog's own data -- media,
        // directories and their privacy, the booking link, the newsletter
        // list -- changes only through saves that move this stamp.
        renderer.cachedTransformedText(entry);
        probeOutput = "two";
        weblog.setLastModified(new Date(1_000_001L));

        assertEquals("<p>Body <b>two</b></p>\n", renderer.cachedTransformedText(entry));
    }

    @Test
    void aMovedEntryUpdateTimeRendersAfresh() {
        renderer.cachedTransformedText(entry);
        probeOutput = "two";
        entry.setUpdateTime(new Timestamp(500_001L));

        assertEquals("<p>Body <b>two</b></p>\n", renderer.cachedTransformedText(entry));
    }

    @Test
    void aChangedWeblogUrlRendersAfresh() {
        // site.absoluteurl, the captured absolute context url, the custom
        // domain: all of them reach media urls through the weblog's root.
        renderer.cachedTransformedText(entry);
        probeOutput = "two";
        when(urls.getWeblogURL(any(Weblog.class), any(), any(Boolean.class)))
                .thenReturn("https://travel.example.org/");

        assertEquals("<p>Body <b>two</b></p>\n", renderer.cachedTransformedText(entry));
    }

    @Test
    void aChangedContextPathRendersAfresh() {
        // [contact] and [subscribe] build their endpoints from the relative
        // context url alone.
        renderer.cachedTransformedText(entry);
        probeOutput = "two";
        WebloggerRuntimeConfig.setRelativeContextURL("/roller");

        assertEquals("<p>Body <b>two</b></p>\n", renderer.cachedTransformedText(entry));
    }

    @Test
    void togglingSanitizationRendersAfresh() {
        HTMLSanitizer.xssEnabled = Boolean.FALSE;
        entry.setText("<span onclick=\"x()\">hi</span>");
        String trusted = renderer.cachedTransformedText(entry);
        HTMLSanitizer.xssEnabled = Boolean.TRUE;

        String untrusted = renderer.cachedTransformedText(entry);

        assertTrue(trusted.contains("onclick"), trusted);
        assertTrue(!untrusted.contains("onclick"), "sanitized output must not be served from the trusted render");
    }

    @Test
    void entriesNeverShareASlot() {
        renderer.cachedTransformedText(entry);
        WeblogEntry other = new WeblogEntry();
        other.setWebsite(weblog);
        other.setAnchor("cached-entry");
        other.setStatus(PubStatus.PUBLISHED);
        other.setUpdateTime(entry.getUpdateTime());
        other.setText(entry.getText());
        other.setSummary(entry.getSummary());

        renderer.cachedTransformedText(other);

        assertEquals(2, probeRenders);
    }

    // ------------------------------------------------------------- never cached

    @Test
    void anUnpublishedEntryIsNeverCached() {
        // A draft's saves do not move the weblog's lastModified, so nothing
        // could tell a cached draft stale.
        entry.setStatus(PubStatus.DRAFT);
        renderer.cachedTransformedText(entry);
        renderer.cachedTransformedText(entry);

        assertEquals(2, probeRenders);
    }

    @Test
    void aWeblogWithoutLastModifiedIsNeverCached() {
        weblog.setLastModified(null);
        renderer.cachedTransformedText(entry);
        renderer.cachedTransformedText(entry);

        assertEquals(2, probeRenders);
    }

    @Test
    void nothingIsCachedWhileAnEntryPluginIsRegistered() throws Exception {
        // A plugin may read anything; the registry is empty in production.
        WeblogEntryPlugin plugin = mock(WeblogEntryPlugin.class);
        when(plugin.getName()).thenReturn("Same");
        when(plugin.render(any(), anyString())).thenAnswer(invocation -> invocation.getArgument(1));
        when(pluginManager.getWeblogEntryPlugins(any())).thenReturn(Map.of("Same", plugin));

        renderer.cachedTransformedText(entry);
        renderer.cachedTransformedText(entry);

        assertEquals(2, probeRenders);
    }

    @Test
    void aRenderThatReadAnotherWeblogsDataIsNotCached() {
        // That weblog's changes move its own lastModified, never this one's.
        Weblog other = new Weblog();
        other.setHandle("otherblog");
        probeReadsFrom = other;

        renderer.cachedTransformedText(entry);
        renderer.cachedDisplayContent(entry, null);
        renderer.cachedTransformedText(entry);
        renderer.cachedDisplayContent(entry, null);

        assertEquals(4, probeRenders);
    }

    @Test
    void aRenderThatReadOnlyItsOwnWeblogIsCached() {
        probeReadsFrom = weblog;

        renderer.cachedTransformedText(entry);
        renderer.cachedTransformedText(entry);

        assertEquals(1, probeRenders);
    }

    @Test
    void aRendererBuiltWithoutACacheRendersEveryTime() {
        EntryRenderer uncached = new EntryRenderer(new ShortcodeExpander(List.of(probe)), pluginManager);
        uncached.cachedTransformedText(entry);
        uncached.cachedTransformedText(entry);

        assertEquals(2, probeRenders);
    }

    // ------------------------------------------------------------- preview isolation

    @Test
    void theUncachedPathNeverReadsTheCache() {
        renderer.cachedTransformedText(entry);
        probeOutput = "two";

        assertEquals("<p>Body <b>two</b></p>\n", renderer.transformedText(entry),
                "a preview must render the pipeline's current output, not the live cache's");
        assertEquals("<p>Teaser <b>two</b></p>\n", renderer.transformedSummary(entry));
        assertEquals("<p>Body <b>two</b></p>\n", renderer.displayContent(entry, null));
    }

    @Test
    void theUncachedPathNeverWritesTheCache() {
        String live = renderer.cachedTransformedText(entry);

        // The editor and API previews set unsaved text on the (possibly
        // managed) entity and render it; that text must never reach readers.
        String saved = entry.getText();
        entry.setText("Unsaved draft [probe]");
        String preview = renderer.transformedText(entry);
        entry.setText(saved);

        assertNotEquals(live, preview);
        int before = probeRenders;
        assertEquals(live, renderer.cachedTransformedText(entry));
        assertEquals(before, probeRenders, "the live slot is still the one rendered from the saved text");
    }

    // -------------------------------------------------- [image] reports its media's weblog

    @Test
    void anImageFromAnotherWeblogIsNotCachedButOneFromItsOwnIs() throws Exception {
        Weblogger weblogger = mock(Weblogger.class);
        MediaFileManager mediaFiles = mock(MediaFileManager.class);
        when(weblogger.getMediaFileManager()).thenReturn(mediaFiles);
        URLStrategy mediaUrls = mock(URLStrategy.class);
        when(mediaUrls.getMediaFileURL(any(), anyString(), any(Boolean.class))).thenReturn("https://example.com/f");
        when(weblogger.getUrlStrategy()).thenReturn(mediaUrls);

        Weblog other = new Weblog();
        other.setHandle("otherblog");
        when(mediaFiles.getMediaFile("mine")).thenReturn(image("mine", weblog));
        when(mediaFiles.getMediaFile("theirs")).thenReturn(image("theirs", other));

        EntryRenderer real = new EntryRenderer(ShortcodeExpander.builtIn(weblogger, mediaFiles), pluginManager, urls,
                new EntryHtmlCache(1024 * 1024, 64 * 1024, 3_600_000L, System::currentTimeMillis));

        entry.setText("[image id=\"mine\"]");
        real.cachedTransformedText(entry);
        real.cachedTransformedText(entry);
        verify(mediaFiles, times(1)).getMediaFile("mine");

        entry.setText("[image id=\"theirs\"]");
        real.cachedTransformedText(entry);
        real.cachedTransformedText(entry);
        verify(mediaFiles, times(2)).getMediaFile("theirs");
    }

    /**
     * A shortcode whose lookup failed is left as the author wrote it. That is
     * a degraded render, not the render: keeping it would pin the broken tag
     * on every page until the next edit, after the store had recovered.
     */
    @Test
    void aRenderWhoseLookupFailedIsNotKept() throws Exception {
        Weblogger weblogger = mock(Weblogger.class);
        MediaFileManager mediaFiles = mock(MediaFileManager.class);
        when(weblogger.getMediaFileManager()).thenReturn(mediaFiles);
        when(mediaFiles.getMediaFile(anyString())).thenThrow(new WebloggerException("store down"));
        when(mediaFiles.getMediaFileDirectoryByName(any(), anyString()))
                .thenThrow(new WebloggerException("store down"));
        EntryRenderer real = new EntryRenderer(ShortcodeExpander.builtIn(weblogger, mediaFiles), pluginManager, urls,
                new EntryHtmlCache(1024 * 1024, 64 * 1024, 3_600_000L, System::currentTimeMillis));

        entry.setText("[image id=\"mf\"]");
        real.cachedTransformedText(entry);
        real.cachedTransformedText(entry);
        verify(mediaFiles, times(2)).getMediaFile("mf");

        entry.setText("[gallery dir=\"trip\"]");
        real.cachedTransformedText(entry);
        real.cachedTransformedText(entry);
        verify(mediaFiles, times(2)).getMediaFileDirectoryByName(weblog, "trip");

        entry.setText("[map auto=\"pins\"]");
        real.cachedTransformedText(entry);
        real.cachedTransformedText(entry);
        verify(mediaFiles, times(2)).getMediaFileDirectoryByName(weblog, "pins");
    }

    @Test
    void aRenderWhoseHandlerThrewIsNotKept() {
        int[] calls = {0};
        ShortcodeHandler throwing = new ShortcodeHandler() {
            @Override
            public String getName() {
                return "flaky";
            }

            @Override
            public ShortcodeCard getCard() {
                return new ShortcodeCard("flaky", "flaky.label", "[flaky]", false);
            }

            @Override
            public String render(Map<String, String> attributes, String body, ShortcodeContext content) {
                calls[0]++;
                throw new IllegalStateException("store down");
            }
        };
        EntryRenderer flaky = new EntryRenderer(new ShortcodeExpander(List.of(throwing)), pluginManager, urls,
                new EntryHtmlCache(1024 * 1024, 64 * 1024, 3_600_000L, System::currentTimeMillis));
        entry.setText("[flaky]");

        flaky.cachedTransformedText(entry);
        flaky.cachedTransformedText(entry);

        assertEquals(2, calls[0], "the expander's own catch is a failed lookup too");
    }

    private static MediaFile image(String id, Weblog owner) {
        MediaFile photo = new MediaFile();
        photo.setId(id);
        photo.setWeblog(owner);
        photo.setName(id + ".jpg");
        photo.setContentType("image/jpeg");
        photo.setWidth(1200);
        photo.setHeight(800);
        return photo;
    }
}
