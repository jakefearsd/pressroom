package com.wikantik.pressroom.business.search.lucene;

import org.apache.commons.text.StringEscapeUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.wikantik.pressroom.business.EntryRenderer;
import com.wikantik.pressroom.pojos.WeblogEntry;
import com.wikantik.pressroom.util.Utilities;

/**
 * The words of a post as a reader sees them, for the search index: shortcodes
 * expanded, markdown rendered, sanitized, tags stripped, entities decoded.
 */
final class SearchableText {

    private static final Logger log = LoggerFactory.getLogger(SearchableText.class);

    private SearchableText() {
    }

    /**
     * Plain text of the entry body; {@code ""} when there is no text or it
     * cannot be rendered (the post is then still indexed by its title).
     * Renders uncached: indexing must not fill the render cache.
     */
    static String of(WeblogEntry entry, EntryRenderer renderer) {
        try {
            String html = renderer.transformedText(entry);
            if (html == null) {
                return "";
            }
            String plain = StringEscapeUtils.unescapeHtml4(Utilities.removeHTML(html, true));
            return plain.replaceAll("\\s+", " ").trim();
        } catch (RuntimeException e) {
            log.warn("Could not render entry {} for the search index; indexing its title only",
                    entry.getId(), e);
            return "";
        }
    }
}
