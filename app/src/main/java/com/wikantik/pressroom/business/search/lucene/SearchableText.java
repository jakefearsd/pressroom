package com.wikantik.pressroom.business.search.lucene;

import java.util.regex.Pattern;

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

    private static final Pattern WHITESPACE = Pattern.compile("\\s+");

    /** Punctuation a removed tag must not be separated from by a space. */
    private static final String CLOSING = ".,;:!?)";

    /**
     * Removes the tags that sit against punctuation (a run of tags directly
     * before closing punctuation, or directly after an opening parenthesis),
     * so the space every other removed tag leaves (two words a tag separated
     * stay two words) never lands in "the guide ." or "( really )". A single
     * forward scan: every character is looked at a bounded number of times.
     */
    static String closeUpTags(String html) {
        StringBuilder out = new StringBuilder(html.length());
        int i = 0;
        while (i < html.length()) {
            int runEnd = tagRunEnd(html, i);
            if (runEnd == i) {
                out.append(html.charAt(i));
                i++;
                continue;
            }
            boolean againstPunctuation = (runEnd < html.length() && CLOSING.indexOf(html.charAt(runEnd)) >= 0)
                    || (i > 0 && html.charAt(i - 1) == '(');
            if (!againstPunctuation) {
                out.append(html, i, runEnd);
            }
            i = runEnd;
        }
        return out.toString();
    }

    /** Where the run of whole tags starting at {@code from} ends; {@code from} when no tag starts there. */
    private static int tagRunEnd(String html, int from) {
        int pos = from;
        while (pos < html.length() && html.charAt(pos) == '<') {
            int end = tagEnd(html, pos);
            if (end < 0) {
                break;
            }
            pos = end;
        }
        return pos;
    }

    /** Just past the {@code >} closing the tag that opens at {@code open}, or -1 if a {@code <} or the end comes first. */
    private static int tagEnd(String html, int open) {
        for (int j = open + 1; j < html.length(); j++) {
            char c = html.charAt(j);
            if (c == '>') {
                return j + 1;
            }
            if (c == '<') {
                return -1;
            }
        }
        return -1;
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
            String plain = StringEscapeUtils.unescapeHtml4(Utilities.removeHTML(closeUpTags(html), true));
            return WHITESPACE.matcher(plain).replaceAll(" ").trim();
        } catch (RuntimeException e) {
            log.warn("Could not render entry {} for the search index; indexing its title only",
                    entry.getId(), e);
            return "";
        }
    }
}
