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

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;

import org.apache.commons.lang3.StringUtils;
import com.wikantik.pressroom.business.plugins.PluginManager;
import com.wikantik.pressroom.business.plugins.entry.WeblogEntryPlugin;
import com.wikantik.pressroom.business.shortcodes.ShortcodeCard;
import com.wikantik.pressroom.business.shortcodes.ShortcodeContext;
import com.wikantik.pressroom.business.EntryHtmlCache.Inputs;
import com.wikantik.pressroom.business.EntryHtmlCache.Part;
import com.wikantik.pressroom.business.shortcodes.ShortcodeExpander;
import com.wikantik.pressroom.business.shortcodes.ShortcodeReads;
import com.wikantik.pressroom.config.WebloggerRuntimeConfig;
import com.wikantik.pressroom.pojos.Weblog;
import com.wikantik.pressroom.pojos.WeblogEntry;
import com.wikantik.pressroom.pojos.WeblogEntry.PubStatus;
import com.wikantik.pressroom.pojos.WeblogPage;
import com.wikantik.pressroom.util.HTMLSanitizer;
import com.wikantik.pressroom.util.I18nMessages;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The content pipeline every authored surface shares -- entry plugins, then
 * shortcode expansion, then Markdown, then sanitization -- as a service that
 * receives its collaborators, rather than behaviour reached from inside the
 * entities through the static service locator.
 *
 * <p>This is what {@code WeblogEntry.render()}/{@code getTransformedText()}/
 * {@code displayContent()} and {@code WeblogPage.getRenderedContent()} used to
 * do, with the static {@code ContentRenderer} folded in. It lives on the
 * {@link Weblogger} facade ({@link Weblogger#getEntryRenderer()}) because the
 * template wrappers, which hold the facade, are its main consumer.
 *
 * <p>The order is load-bearing and deliberately not the one it looks like it
 * should be. Markdown first would escape the quotes in
 * {@code [gallery dir="Iceland"]} to {@code &quot;}, and the expander's
 * attribute grammar does not match entity-quoted values, so every shortcode
 * carrying an attribute would silently stop working. Expanding first is safe
 * because commonmark passes raw HTML through verbatim in block and inline
 * positions alike; the only cost is that markdown syntax inside a shortcode's
 * own emitted text is interpreted, which is cosmetic.
 *
 * <p>Raw HTML is deliberately not escaped: the shortcodes emit HTML. The
 * sanitizer at the end is the security boundary.
 *
 * <p>Entry plugins: per-entry opt-in died with {@code weblogentry.plugins}
 * (V021) -- every plugin the site has registered is applied to every entry
 * unconditionally, the same way shortcodes are. In production the registry is
 * empty ({@code plugins.page} is no longer configured), so that loop is a
 * no-op; it stays as the render seam a future page plugin would use. The
 * plugin set is asked of the {@link PluginManager} per render rather than
 * memoised on the weblog entity, which is where the old cache lived -- a
 * manager result cached on a JPA-managed object outlives the request that
 * built it.
 *
 * <p>Two paths. The {@code cached*} methods are the live one -- what a reader
 * is served, through the template wrapper -- and keep each published entry's
 * HTML in an {@link EntryHtmlCache} until anything it was rendered from
 * changes (see that class for the inputs and how each reaches it). The plain
 * methods always run the pipeline and never read or write that cache: the
 * editor and API previews render unsaved text set on the entity, the preview
 * servlet renders drafts and previewed themes, and the newsletter is sent
 * once, so none of them may be served a live render or leave one behind.
 */
public class EntryRenderer {

    private static final Logger log = LoggerFactory.getLogger(EntryRenderer.class);

    private final ShortcodeExpander expander;
    private final PluginManager pluginManager;

    /** The facade's url strategy, for the weblog url in a slot key; null with no cache. */
    private final URLStrategy urlStrategy;
    private final EntryHtmlCache cache;

    /** A renderer whose live path keeps nothing: every render runs the pipeline. */
    public EntryRenderer(ShortcodeExpander expander, PluginManager pluginManager) {
        this(expander, pluginManager, null, EntryHtmlCache.disabled());
    }

    /**
     * @param urlStrategy the strategy the shortcodes build their urls with
     *                    (the facade's), so the slot key carries what those
     *                    urls are built from
     * @param cache       where the live path keeps finished entry HTML
     */
    public EntryRenderer(ShortcodeExpander expander, PluginManager pluginManager, URLStrategy urlStrategy,
                         EntryHtmlCache cache) {
        this.expander = expander;
        this.pluginManager = pluginManager;
        this.urlStrategy = urlStrategy;
        this.cache = cache;
    }

    /**
     * The shared pipeline for any authored text -- shortcodes, markdown,
     * sanitization -- with no entry-plugin pass (pages have no plugins).
     *
     * @param content what is being rendered, for weblog and slug context
     * @param text    the source; null returns null
     */
    public String render(ShortcodeContext content, String text) {
        return render(content, text, ShortcodeReads.IGNORED);
    }

    private String render(ShortcodeContext content, String text, ShortcodeReads reads) {
        if (text == null) {
            return null;
        }
        String out = expander.expand(content, text, reads);
        out = MarkdownRenderer.render(out);
        return HTMLSanitizer.conditionallySanitize(out);
    }

    /** Entry text, transformed by the site's plugins and the shared pipeline. Never cached. */
    public String transformedText(WeblogEntry entry) {
        return renderPart(entry, Part.TEXT, plugins(entry), ShortcodeReads.IGNORED);
    }

    /** Entry summary, transformed by the site's plugins and the shared pipeline. Never cached. */
    public String transformedSummary(WeblogEntry entry) {
        return renderPart(entry, Part.SUMMARY, plugins(entry), ShortcodeReads.IGNORED);
    }

    /**
     * The right transformed display content depending on the situation.
     * Never cached.
     *
     * <p>If the readMoreLink is specified then we assume the caller wants to
     * prefer summary over content and we include a "Read More" link at the
     * end of the summary if it exists. Otherwise, if the readMoreLink is
     * empty, null, or the literal {@code "nil"} (Velocity has no null literal,
     * so themes pass that string) then we assume the caller prefers content
     * over summary.
     */
    public String displayContent(WeblogEntry entry, String readMoreLink) {
        if (isPermalinkForm(readMoreLink)) {
            return renderPart(entry, Part.DISPLAY, plugins(entry), ShortcodeReads.IGNORED);
        }
        return teaser(entry, readMoreLink, this::transformedSummary, this::transformedText);
    }

    /** {@link #transformedText}, kept for a published entry until its inputs change. */
    public String cachedTransformedText(WeblogEntry entry) {
        return cached(entry, Part.TEXT);
    }

    /** {@link #transformedSummary}, kept for a published entry until its inputs change. */
    public String cachedTransformedSummary(WeblogEntry entry) {
        return cached(entry, Part.SUMMARY);
    }

    /**
     * {@link #displayContent}, kept for a published entry until its inputs
     * change. The read-more form is not kept whole -- its link is the
     * theme's -- but is built from the kept summary and text.
     */
    public String cachedDisplayContent(WeblogEntry entry, String readMoreLink) {
        if (isPermalinkForm(readMoreLink)) {
            return cached(entry, Part.DISPLAY);
        }
        return teaser(entry, readMoreLink, this::cachedTransformedSummary, this::cachedTransformedText);
    }

    /** A static page's content, through the same pipeline entries use. */
    public String pageContent(WeblogPage page) {
        return render(page, page.getContent());
    }

    /**
     * What the entry editor should offer for the registered shortcodes --
     * by construction the ones this renderer will actually expand.
     */
    public List<ShortcodeCard> shortcodeCards() {
        return expander.cards();
    }

    private static boolean isPermalinkForm(String readMoreLink) {
        return readMoreLink == null || readMoreLink.isBlank() || "nil".equals(readMoreLink);
    }

    /** Not a permalink, so prefer summary over text, with a "read more" link when there is more. */
    private static String teaser(WeblogEntry entry, String readMoreLink,
                                 Function<WeblogEntry, String> summary, Function<WeblogEntry, String> text) {
        if (StringUtils.isEmpty(entry.getSummary())) {
            return HTMLSanitizer.conditionallySanitize(text.apply(entry));
        }
        String displayContent = summary.apply(entry);
        if (StringUtils.isNotEmpty(entry.getText())) {
            // TODO: we need a more appropriate way to get the view locale here
            displayContent += I18nMessages.getMessages(entry.getWebsite().getLocaleInstance())
                    .getString("macro.weblog.readMoreLink", List.of(readMoreLink));
        }
        return HTMLSanitizer.conditionallySanitize(displayContent);
    }

    private Map<String, WeblogEntryPlugin> plugins(WeblogEntry entry) {
        return pluginManager.getWeblogEntryPlugins(entry.getWebsite());
    }

    /** One part, rendered now; {@code reads} hears what the shortcodes looked up. */
    private String renderPart(WeblogEntry entry, Part part, Map<String, WeblogEntryPlugin> plugins,
                              ShortcodeReads reads) {
        return switch (part) {
            case TEXT -> renderEntry(entry, entry.getText(), plugins, reads);
            case SUMMARY -> renderEntry(entry, entry.getSummary(), plugins, reads);
            // no readMore link means permalink, so prefer text over summary
            case DISPLAY -> HTMLSanitizer.conditionallySanitize(StringUtils.isNotEmpty(entry.getText())
                    ? renderEntry(entry, entry.getText(), plugins, reads)
                    : renderEntry(entry, entry.getSummary(), plugins, reads));
        };
    }

    private String cached(WeblogEntry entry, Part part) {
        Map<String, WeblogEntryPlugin> plugins = plugins(entry);
        String key = slotKey(entry, plugins);
        if (key == null) {
            return renderPart(entry, part, plugins, ShortcodeReads.IGNORED);
        }
        Inputs inputs = new Inputs(entry.getWebsite().getLastModified().getTime(),
                entry.getUpdateTime() == null ? 0L : entry.getUpdateTime().getTime(),
                entry.getText(), entry.getSummary());
        String html = cache.get(key, inputs, part);
        if (html != null) {
            return html;
        }
        ForeignReads reads = new ForeignReads(entry.getWebsite());
        html = renderPart(entry, part, plugins, reads);
        if (!reads.foreign) {
            cache.put(key, inputs, part, html);
        }
        return html;
    }

    /**
     * The entry's slot, or null when its render may not be kept: no cache,
     * not published (a draft's saves do not move {@code lastModified}), no
     * stamp to validate against, or an entry plugin registered (a plugin may
     * read anything).
     */
    private String slotKey(WeblogEntry entry, Map<String, WeblogEntryPlugin> plugins) {
        Weblog weblog = entry.getWebsite();
        if (urlStrategy == null || entry.getId() == null || entry.getStatus() != PubStatus.PUBLISHED
                || weblog == null || weblog.getLastModified() == null
                || (plugins != null && !plugins.isEmpty())) {
            return null;
        }
        // An id is a UUID, so the first space always ends it.
        return entry.getId()
                + (Boolean.TRUE.equals(HTMLSanitizer.xssEnabled) ? " s " : " r ")
                + urlStrategy.getWeblogURL(weblog, null, true)
                + ' ' + WebloggerRuntimeConfig.getRelativeContextURL();
    }

    private String renderEntry(WeblogEntry entry, String str, Map<String, WeblogEntryPlugin> inPlugins,
                               ShortcodeReads reads) {
        String ret = str;
        log.debug("Applying page plugins to string");
        if (str != null && inPlugins != null) {
            for (WeblogEntryPlugin pagePlugin : inPlugins.values()) {
                try {
                    ret = pagePlugin.render(entry, ret);
                } catch (Exception e) {
                    log.error("ERROR from plugin: {}", pagePlugin.getName(), e);
                }
            }
        }
        return render(entry, ret, reads);
    }

    /** Whether a render looked up anything owned by a weblog other than the entry's. */
    private static final class ForeignReads implements ShortcodeReads {

        private final String ownId;
        private boolean foreign;

        ForeignReads(Weblog own) {
            this.ownId = own.getId();
        }

        @Override
        public void readFrom(Weblog weblog) {
            if (weblog == null || !Objects.equals(weblog.getId(), ownId)) {
                foreign = true;
            }
        }
    }
}
