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
package com.wikantik.pressroom.business;

import java.util.Map;

import com.wikantik.pressroom.business.plugins.PluginManager;
import com.wikantik.pressroom.business.plugins.PluginManagerImpl;
import com.wikantik.pressroom.business.plugins.entry.WeblogEntryPlugin;
import com.wikantik.pressroom.business.shortcodes.ShortcodeContext;
import com.wikantik.pressroom.business.shortcodes.ShortcodeExpander;
import com.wikantik.pressroom.pojos.MediaFile;
import com.wikantik.pressroom.pojos.Weblog;
import com.wikantik.pressroom.pojos.WeblogEntry;
import com.wikantik.pressroom.pojos.WeblogPage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The content pipeline as a service: entry plugins, then shortcodes, then
 * markdown, then sanitization, and the summary-vs-text decision
 * {@code displayContent} makes.
 *
 * <p>These assertions are lifted verbatim from {@code WeblogEntryRenderingTest}
 * (when the entity rendered itself), {@code ContentRendererTest} (the static
 * pipeline) and {@code WeblogPageTest} (the page half): the expected strings
 * are the ones those tests held, so a renderer that produced anything other
 * than byte-identical output to the old entity methods fails here. The
 * renderer is constructed with its collaborators -- a real built-in expander
 * over a mocked facade, a mocked plugin manager -- and no static tier exists
 * anywhere in this class.
 */
class EntryRendererTest {

    private Weblog weblog;
    private WeblogEntry entry;
    private Weblogger weblogger;
    private MediaFileManager mediaFiles;
    private PluginManager pluginManager;
    private EntryRenderer renderer;

    @BeforeEach
    void setUp() {
        weblog = new Weblog();
        weblog.setHandle("testblog");
        weblog.setLocale("en_US");

        entry = new WeblogEntry();
        entry.setWebsite(weblog);
        entry.setAnchor("hello-world");

        pluginManager = mock(PluginManager.class);
        mediaFiles = mock(MediaFileManager.class);
        weblogger = mock(Weblogger.class);
        when(weblogger.getMediaFileManager()).thenReturn(mediaFiles);
        when(weblogger.getPluginManager()).thenReturn(pluginManager);
        renderer = new EntryRenderer(ShortcodeExpander.builtIn(weblogger, mediaFiles), pluginManager);
    }

    private void installPlugins(Map<String, WeblogEntryPlugin> plugins) {
        when(pluginManager.getWeblogEntryPlugins(weblog)).thenReturn(plugins);
    }

    private static WeblogEntryPlugin shouting() throws Exception {
        WeblogEntryPlugin plugin = mock(WeblogEntryPlugin.class);
        when(plugin.getName()).thenReturn("Shout");
        when(plugin.render(any(), anyString()))
                .thenAnswer(invocation -> invocation.getArgument(1).toString().toUpperCase());
        return plugin;
    }

    /** Wires a 1200x800 image media file "mf-1" into the mocked tier. */
    private void installMediaFile() throws Exception {
        MediaFile photo = new MediaFile();
        photo.setId("mf-1");
        photo.setWeblog(weblog);
        photo.setName("hawk.jpg");
        photo.setContentType("image/jpeg");
        photo.setWidth(1200);
        photo.setHeight(800);
        when(mediaFiles.getMediaFile("mf-1")).thenReturn(photo);

        URLStrategy urls = mock(URLStrategy.class);
        when(urls.getMediaFileURL(weblog, "mf-1", true)).thenReturn("http://example.com/f");
        when(weblogger.getUrlStrategy()).thenReturn(urls);
    }

    // ------------------------------------------------------------- plugins

    @Test
    void everyRegisteredPluginIsAppliedToEveryEntry() throws Exception {
        installPlugins(Map.of("Shout", shouting()));

        entry.setText("hello");
        entry.setSummary("summary");

        assertEquals("<p>HELLO</p>\n", renderer.transformedText(entry),
                "A registered plugin must be applied to the entry's body");
        assertEquals("<p>SUMMARY</p>\n", renderer.transformedSummary(entry),
                "and to its summary");
    }

    @Test
    void noRegisteredPluginsMeansTheTextIsUntouched() {
        // "untouched" means untouched by PLUGINS. A site with no registered
        // plugins -- the only case left in production -- leaves entry text
        // alone, while the shortcode expander is a platform feature that runs
        // unconditionally regardless; the next test covers that half.
        installPlugins(Map.of());
        entry.setText("hello");

        assertEquals("<p>hello</p>\n", renderer.transformedText(entry),
                "An empty plugin registry must leave the text alone; the paragraph is "
                        + "markdown, which every entry now goes through.");
    }

    @Test
    void shortcodesExpandWhenNoPluginsAreRegistered() throws Exception {
        installPlugins(Map.of());
        installMediaFile();

        entry.setText("look: [image id=mf-1]");

        String rendered = renderer.transformedText(entry);

        assertTrue(rendered.contains("<figure class=\"shortcode-image\">") && rendered.contains("look:"),
                "the [image] shortcode must expand with no plugins registered: " + rendered);
        // The sanitizer entity-encodes "=" inside attribute values; a browser
        // decodes it before parsing the srcset, so compare the decoded form.
        String plain = org.apache.commons.text.StringEscapeUtils.unescapeHtml4(rendered);
        assertTrue(plain.contains("http://example.com/f?w=480 480w"),
                "srcset must climb the rendition ladder: " + rendered);
        assertTrue(rendered.contains("http://example.com/f 1200w"), rendered);
        assertFalse(rendered.contains("[image"), rendered);
    }

    @Test
    void theBusinessPluginSeamExpandsShortcodesUnconditionallyToo() throws Exception {
        // Same contract, second render call-site: PluginManagerImpl's
        // applyWeblogEntryPlugins (used with an explicit, empty plugin map)
        // must also expand shortcodes with no plugins registered -- through
        // the same expander instance this renderer holds.
        installMediaFile();
        entry.setText("irrelevant");

        String rendered = new PluginManagerImpl(ShortcodeExpander.builtIn(weblogger, mediaFiles))
                .applyWeblogEntryPlugins(Map.of(), entry, "see [image id=mf-1] here");

        assertTrue(rendered.contains("<figure class=\"shortcode-image\">"), rendered);
        assertFalse(rendered.contains("[image"), rendered);
    }

    @Test
    void aPluginThatBlowsUpLeavesTheEntryRenderable() throws Exception {
        WeblogEntryPlugin broken = mock(WeblogEntryPlugin.class);
        when(broken.getName()).thenReturn("Broken");
        when(broken.render(any(), anyString())).thenThrow(new RuntimeException("boom"));
        installPlugins(Map.of("Broken", broken));

        entry.setText("hello");

        assertEquals("<p>hello</p>\n", renderer.transformedText(entry),
                "A failing plugin must leave the text as it found it rather than taking "
                        + "out the whole page; plugins are third-party code. The markdown "
                        + "step still runs -- it is not a plugin and cannot be opted out of");
    }

    @Test
    void anEntryWithNoTextRendersAsNothingRatherThanThrowing() throws Exception {
        installPlugins(Map.of("Shout", shouting()));
        entry.setText(null);

        assertNull(renderer.transformedText(entry),
                "An entry with no body must render as nothing; passing null into the "
                        + "plugin chain would NPE inside third-party code");
    }

    @Test
    void aPluginManagerThatAnswersNullMeansNoPlugins() {
        // Mockito's default for an unstubbed getWeblogEntryPlugins is null;
        // the old entity code tolerated a null map and so must this.
        entry.setText("hello");
        assertEquals("<p>hello</p>\n", renderer.transformedText(entry));
    }

    // -------------------------------------------------------- display content

    @Test
    void withoutAReadMoreLinkTheFullTextIsPreferred() {
        installPlugins(Map.of());
        entry.setText("The whole post");
        entry.setSummary("Just a taste");

        assertEquals("<p>The whole post</p>\n", renderer.displayContent(entry, null),
                "No read-more link means this is the permalink page, which shows the "
                        + "whole post rather than the teaser");
        assertEquals("<p>The whole post</p>\n", renderer.displayContent(entry, "  "),
                "A blank link is not a link");
        assertEquals("<p>The whole post</p>\n", renderer.displayContent(entry, "nil"),
                "Velocity has no null literal, so themes pass the string 'nil' -- it must "
                        + "be understood as 'no link'");
    }

    @Test
    void withoutTextTheSummaryStandsIn() {
        installPlugins(Map.of());
        entry.setText("");
        entry.setSummary("Just a taste");

        assertEquals("<p>Just a taste</p>\n", renderer.displayContent(entry, null),
                "An entry with only a summary must still render something on its own page");
    }

    @Test
    void withAReadMoreLinkTheSummaryIsPreferredAndTheLinkAppended() {
        installPlugins(Map.of());
        entry.setText("The whole post");
        entry.setSummary("Just a taste");

        String rendered = renderer.displayContent(entry, "http://example.com/entry");

        assertTrue(rendered.startsWith("<p>Just a taste</p>"),
                "A read-more link means this is a list page, which shows the teaser: "
                        + rendered);
        assertTrue(rendered.contains("http://example.com/entry"),
                "and the link the caller supplied must appear in the appended markup: "
                        + rendered);
    }

    @Test
    void withAReadMoreLinkButNoSummaryTheFullTextIsShown() {
        installPlugins(Map.of());
        entry.setText("The whole post");
        entry.setSummary(null);

        assertEquals("<p>The whole post</p>\n",
                renderer.displayContent(entry, "http://example.com/entry"),
                "An entry with no teaser has to show its body on the list page; showing "
                        + "nothing but a read-more link would give the reader no reason to "
                        + "click it");
    }

    @Test
    void anEntryWithASummaryButNoTextGetsNoReadMoreLink() {
        installPlugins(Map.of());
        entry.setText("");
        entry.setSummary("Just a taste");

        String rendered = renderer.displayContent(entry, "http://example.com/entry");

        assertEquals("<p>Just a taste</p>\n", rendered,
                "There is nothing more to read, so offering a 'read more' link would take "
                        + "the reader to the same words again");
    }

    // ----------------------------------------------------- the shared pipeline

    private static ShortcodeContext context(String rawText) {
        Weblog weblog = new Weblog();
        weblog.setHandle("testblog");
        return new ShortcodeContext() {
            @Override public Weblog getWeblog() { return weblog; }
            @Override public String getSlug() { return "a-slug"; }
            @Override public String getRawText() { return rawText; }
        };
    }

    @Test
    void markdownBecomesHtml() {
        String html = renderer.render(context("**bold**"), "**bold**");

        assertTrue(html.contains("<strong>bold</strong>"), "got: " + html);
    }

    /**
     * Regression check: unknown shortcodes are not silently deleted, and
     * markdown's entity escaping of quote characters in HTML-like text is
     * stable. The expander doesn't touch unknown shortcodes; markdown escapes
     * them in the normal way.
     */
    @Test
    void unknownShortcodeRegressionCheck() {
        String source = "[nosuchcode attr=\"value\"]";

        String html = renderer.render(context(source), source);

        assertTrue(html.contains("[nosuchcode attr") && html.contains("value"),
                "unknown shortcode must survive in output: " + html);
        assertTrue(html.contains("&#34;"),
                "markdown-escaped quotes must be in output: " + html);
    }

    /**
     * Shortcodes expand BEFORE markdown. A registered shortcode with quoted
     * attributes is parsed correctly only when quotes are literal (before
     * markdown runs). If markdown ran first, entity-escaped quotes would be
     * parsed as part of the attribute value itself, not as delimiters.
     */
    @Test
    void registeredShortcodeWithQuotedAttributesPinsExpansionBeforeMarkdown() {
        String source = "[cta href=\"http://example.com\" label=\"test\"]";

        String html = renderer.render(context(source), source);

        assertTrue(html.contains("<span class=\"cta-label\">test</span>"),
                "label must render without quote entities in correct order: " + html);
        assertFalse(html.contains("cta-label\">&#34;") || html.contains("cta-label\">&quot;"),
                "label must not include escaped quotes in correct order: " + html);
    }

    /** Post-sanitizer seam: the click attributes must survive to the output. */
    @Test
    void ctaClickAttributesSurviveTheSanitizer() {
        String source = "[cta href=\"https://book.example.com/x\" label=\"Book\"]";

        String html = renderer.render(context(source), source);

        assertTrue(html.contains("data-umami-event=\"cta-click\""), html);
        assertTrue(html.contains("data-umami-event-entry=\"a-slug\""), html);
        assertTrue(html.contains("data-umami-event-dest=\"book.example.com\""), html);
    }

    /** The sanitizer is the security boundary, and it runs last. */
    @Test
    void scriptIsStrippedEvenThoughRawHtmlPassesThroughMarkdown() {
        String source = "<p>ok</p><script>alert(1)</script>";

        String html = renderer.render(context(source), source);

        assertTrue(html.contains("ok"));
        assertFalse(html.contains("<script"), "the sanitizer must remove it: " + html);
    }

    @Test
    void nullTextRendersAsNullRatherThanThrowing() {
        assertNull(renderer.render(context(null), null));
    }

    // ---------------------------------------------------------------- pages

    private WeblogPage page(String content) {
        WeblogPage page = new WeblogPage();
        page.setWeblog(weblog);
        page.setSlug("about");
        page.setTitle("About");
        page.setContent(content);
        return page;
    }

    @Test
    void pageContentIsRenderedAsMarkdown() {
        assertTrue(renderer.pageContent(page("**bold**")).contains("<strong>bold</strong>"));
    }

    @Test
    void scriptInPageContentIsSanitizedAway() {
        String html = renderer.pageContent(page("ok<script>alert(1)</script>"));

        assertTrue(html.contains("ok"));
        assertFalse(html.contains("<script"));
    }

    @Test
    void nullPageContentRendersAsNullRatherThanThrowing() {
        assertNull(renderer.pageContent(page(null)));
    }

    // ------------------------------------------------------------ the cards

    @Test
    void theEditorsInsertMenuComesFromTheRenderersOwnRegistry() {
        assertEquals(8, renderer.shortcodeCards().size(),
                "the insert menu offers exactly the shortcodes this renderer expands");
        assertEquals("image", renderer.shortcodeCards().get(0).name());
    }
}
