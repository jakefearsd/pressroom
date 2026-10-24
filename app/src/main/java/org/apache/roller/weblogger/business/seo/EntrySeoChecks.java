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
package org.apache.roller.weblogger.business.seo;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.text.StringEscapeUtils;
import org.apache.roller.weblogger.pojos.Weblog;
import org.apache.roller.weblogger.pojos.WeblogEntry;

/**
 * The SEO checks for one entry: the one rule set behind {@code audits seo},
 * {@code audits seo_entry} and the body warnings of {@code entries preview}
 * (spec {@code docs/superpowers/specs/2026-10-24-agent-editing-and-seo-design.md},
 * section 6). This class holds the checks on the entry's fields and the
 * weblog-wide duplicates; {@link SeoBody} holds the checks on its rendered
 * body. Both are pure: the caller loads the entries and resolves what the
 * body links to.
 *
 * <p>A finding is advice, never a refusal, and the checks report findings,
 * not a score (spec, Decisions). Lengths are characters as a reader counts
 * them (code points, so an emoji is one) against {@link SeoLimits}.
 */
public final class EntrySeoChecks {

    /** Severity of a finding a search result or a reader would notice. */
    public static final String WARNING = "warning";
    /** Severity of a finding that is worth knowing and often fine. */
    public static final String INFO = "info";

    // Today's audits seo gaps, under the names they have always had.
    public static final String MISSING_SEARCH_DESCRIPTION = "missing_search_description";
    public static final String MISSING_META_TITLE = "missing_meta_title";
    public static final String MISSING_FEATURED_IMAGE = "missing_featured_image";
    public static final String NOINDEX = "noindex";
    public static final String SEARCH_DESCRIPTION_LONG = "search_description_long";
    public static final String SEARCH_DESCRIPTION_SHORT = "search_description_short";
    public static final String TITLE_LONG = "title_long";
    public static final String TITLE_DUPLICATE = "title_duplicate";
    public static final String SEARCH_DESCRIPTION_DUPLICATE = "search_description_duplicate";

    /** Every code the weblog-wide audit reports (no body checks), in the order it reports them. */
    public static final List<String> AUDIT_CODES = List.of(MISSING_SEARCH_DESCRIPTION, MISSING_META_TITLE,
            MISSING_FEATURED_IMAGE, NOINDEX, SEARCH_DESCRIPTION_LONG, SEARCH_DESCRIPTION_SHORT, TITLE_LONG,
            TITLE_DUPLICATE, SEARCH_DESCRIPTION_DUPLICATE);

    /** What a theme prints between the entry title and the weblog name when there is no meta title. */
    static final String TITLE_SEPARATOR = " — ";

    /** How many other entries a duplicate finding names before it only counts the rest. */
    private static final int NAMED_MAX = 3;

    /**
     * One thing to look at. {@code message} is a whole sentence, the same
     * one {@code entries preview} shows as a warning; {@code value} and
     * {@code limit} are set only where a length is involved, so a client can
     * show "172 / 160" without parsing the message.
     */
    public record Finding(String code, String severity, String message, Integer value, Integer limit) {

        static Finding of(String code, String severity, String message) {
            return new Finding(code, severity, message, null, null);
        }
    }

    private EntrySeoChecks() {
    }

    /**
     * Today's four gaps, by blankness: whitespace is not a description. The
     * audit has always been the stricter of it and the renderer, which
     * prints a value that is merely non-empty (see
     * {@code AuditDtos.gapsFor}).
     */
    public static List<Finding> gaps(WeblogEntry entry) {
        List<Finding> gaps = new ArrayList<>();
        if (StringUtils.isBlank(entry.getSearchDescription())) {
            gaps.add(Finding.of(MISSING_SEARCH_DESCRIPTION, WARNING, "There is no search description, so a "
                    + "search result shows whatever text it picks from the page. Write one of "
                    + SeoLimits.DESCRIPTION_MIN + " to " + SeoLimits.DESCRIPTION_MAX + " characters."));
        }
        if (StringUtils.isBlank(entry.getMetaTitle())) {
            gaps.add(Finding.of(MISSING_META_TITLE, WARNING, "There is no meta title, so the page title is "
                    + "the entry title followed by the weblog name. Write one of at most "
                    + SeoLimits.TITLE_MAX + " characters for search results."));
        }
        if (StringUtils.isBlank(entry.getFeaturedImageId())) {
            gaps.add(Finding.of(MISSING_FEATURED_IMAGE, WARNING, "There is no featured image, so a shared "
                    + "link or a search result shows no picture. Set featuredImageId to one of the weblog's "
                    + "images."));
        }
        // Explicitly opted out only: noindex defaults to FALSE, and null is not an opt-out.
        if (Boolean.TRUE.equals(entry.getNoindex())) {
            gaps.add(Finding.of(NOINDEX, WARNING, "The entry is marked noindex, so search engines are asked "
                    + "not to list it."));
        }
        return gaps;
    }

    /** {@link #gaps} and the length checks: every check of the entry's own fields. */
    public static List<Finding> fields(WeblogEntry entry, Weblog weblog) {
        List<Finding> findings = gaps(entry);
        String description = entry.getSearchDescription();
        if (StringUtils.isNotBlank(description)) {
            int length = length(description.strip());
            if (length > SeoLimits.DESCRIPTION_MAX) {
                findings.add(new Finding(SEARCH_DESCRIPTION_LONG, WARNING, "The search description is "
                        + length + " characters; a search result cuts it off after " + SeoLimits.DESCRIPTION_MAX
                        + ".", length, SeoLimits.DESCRIPTION_MAX));
            } else if (length < SeoLimits.DESCRIPTION_MIN) {
                findings.add(new Finding(SEARCH_DESCRIPTION_SHORT, INFO, "The search description is "
                        + length + " characters; under " + SeoLimits.DESCRIPTION_MIN + ", search engines "
                        + "tend to replace it with text from the page.", length, SeoLimits.DESCRIPTION_MIN));
            }
        }
        String title = pageTitle(entry, weblog);
        int length = title == null ? 0 : length(title);
        if (length > SeoLimits.TITLE_MAX) {
            findings.add(new Finding(TITLE_LONG, WARNING, "The page title, \"" + title + "\", is " + length
                    + " characters; a search result cuts it off after " + SeoLimits.TITLE_MAX
                    + ". Set a shorter meta title.", length, SeoLimits.TITLE_MAX));
        }
        return findings;
    }

    /**
     * An index of the weblog's published entries, to tell each entry which
     * of them shares its page title or search description.
     */
    public static Duplicates duplicates(Collection<WeblogEntry> published, Weblog weblog) {
        return new Duplicates(published, weblog);
    }

    /**
     * The {@code <title>} the bundled themes print on the entry's permalink
     * page ({@code themes/<name>/permalink.vm}): the meta title whenever it
     * is non-empty, else the entry title, then " — ", then the weblog's
     * name. The entry title is stored HTML-escaped, so it is unescaped
     * first; the meta title and the weblog name are stored as typed.
     *
     * @return the text of the title, or null when the entry has no title at all
     */
    public static String pageTitle(WeblogEntry entry, Weblog weblog) {
        // isNotEmpty, not isNotBlank: what the themes test, so this is what they print.
        if (StringUtils.isNotEmpty(entry.getMetaTitle())) {
            return entry.getMetaTitle();
        }
        String title = StringEscapeUtils.unescapeHtml4(entry.getTitle());
        if (StringUtils.isBlank(title)) {
            return null;
        }
        String name = weblog.getName();
        return StringUtils.isBlank(name) ? title : title + TITLE_SEPARATOR + name;
    }

    /** Characters as a reader counts them: code points, not UTF-16 units. */
    static int length(String text) {
        return text.codePointCount(0, text.length());
    }

    /** "a, b and 3 more": up to {@link #NAMED_MAX} names, then a count of the rest. */
    static String named(List<String> names) {
        List<String> shown = names.subList(0, Math.min(names.size(), NAMED_MAX));
        String joined = String.join(", ", shown);
        int rest = names.size() - shown.size();
        return rest > 0 ? joined + " and " + rest + " more" : joined;
    }

    /** The published entries of a weblog, indexed by their page title and search description. */
    public static final class Duplicates {
        private final Map<String, List<WeblogEntry>> byTitle;
        private final Map<String, List<WeblogEntry>> byDescription;
        private final Weblog weblog;

        private Duplicates(Collection<WeblogEntry> published, Weblog weblog) {
            this.weblog = weblog;
            this.byTitle = index(published, e -> pageTitle(e, weblog));
            this.byDescription = index(published, WeblogEntry::getSearchDescription);
        }

        /**
         * The entry's duplicates among the indexed entries, never counting
         * itself. An entry that is not one of them (a draft) is compared all
         * the same: it would collide once published.
         */
        public List<Finding> of(WeblogEntry entry) {
            List<Finding> findings = new ArrayList<>();
            List<String> sameTitle = others(byTitle, pageTitle(entry, weblog), entry);
            if (!sameTitle.isEmpty()) {
                findings.add(Finding.of(TITLE_DUPLICATE, WARNING, "Another published entry has the same page "
                        + "title: " + named(sameTitle) + ". Search results cannot tell them apart; give each "
                        + "its own meta title."));
            }
            List<String> sameDescription = others(byDescription, entry.getSearchDescription(), entry);
            if (!sameDescription.isEmpty()) {
                findings.add(Finding.of(SEARCH_DESCRIPTION_DUPLICATE, WARNING, "Another published entry has "
                        + "the same search description: " + named(sameDescription) + ". Describe each entry "
                        + "in its own words."));
            }
            return findings;
        }

        private static Map<String, List<WeblogEntry>> index(Collection<WeblogEntry> entries,
                                                            Function<WeblogEntry, String> value) {
            Map<String, List<WeblogEntry>> index = new HashMap<>();
            for (WeblogEntry entry : entries) {
                String key = key(value.apply(entry));
                if (key != null) {
                    index.computeIfAbsent(key, k -> new ArrayList<>()).add(entry);
                }
            }
            return index;
        }

        /** Equal as a reader sees it: case and runs of whitespace aside. Blank is no value. */
        private static String key(String value) {
            String normalized = StringUtils.normalizeSpace(value);
            return StringUtils.isEmpty(normalized) ? null : normalized.toLowerCase(Locale.ROOT);
        }

        /** The anchors of the indexed entries sharing the value, the entry itself left out. */
        private static List<String> others(Map<String, List<WeblogEntry>> index, String value, WeblogEntry entry) {
            String key = key(value);
            if (key == null) {
                return List.of();
            }
            List<String> anchors = new ArrayList<>();
            for (WeblogEntry other : index.getOrDefault(key, List.of())) {
                if (!sameEntry(other, entry)) {
                    anchors.add(Objects.requireNonNullElse(other.getAnchor(), String.valueOf(other.getId())));
                }
            }
            return anchors;
        }

        /** By id once both have one; before that (an unsaved entry), by anchor and weblog. */
        private static boolean sameEntry(WeblogEntry a, WeblogEntry b) {
            return a.getId() != null && b.getId() != null ? a.getId().equals(b.getId()) : a.equals(b);
        }
    }
}
